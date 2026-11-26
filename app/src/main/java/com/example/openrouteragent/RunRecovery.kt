package com.example.openrouteragent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/*
 * Long agent runs: how the app keeps them alive, continues them after a stop, and keeps a failed run in the chat.
 *
 * A run is no longer cut off at a fixed 15 minutes. It ends only when it finishes, the user stops it, nothing
 * happens for RUN_IDLE_LIMIT_MILLIS (time spent waiting for the user doesn't count), or it reaches the
 * RUN_CEILING_MILLIS backstop. While it works, the runtime sends checkpoints: the conversation so far, ready to
 * replay, and what the run produced. When a run fails for a reason that a fresh request can get past (a stream
 * that went quiet or closed, a server error, a restarted runtime), the app continues it from its last checkpoint
 * in a new request, up to MAX_AUTO_CONTINUES times, with the sandbox it was using. A stream that closes before the
 * first checkpoint is sent again from the start, but only while it had streamed nothing but reasoning and text.
 */

internal const val RUN_IDLE_LIMIT_MILLIS = 10 * 60_000L
internal const val RUN_CEILING_MILLIS = 2 * 60 * 60_000L
internal const val MAX_AUTO_CONTINUES = 3

/** What a run had done when the runtime last reported, so a failure or a stop can keep it. */
internal data class RunCheckpoint(
    /** The conversation so far as SDK history items (JSON array), ready to replay. Null when too large. */
    val history: String?,
    /** What the run produced so far: text, shell steps, searches, sources and its sandbox. */
    val partial: AgentRunResult?,
)

/** The app ended a run that stopped reporting, or that reached the backstop. */
internal class AgentRunStalled(message: String, val resumable: Boolean) : IOException(message)

/** Why a run should end now, or null while it is fine. Waiting for the user to approve or answer never counts. */
internal fun runStallProblem(elapsedMillis: Long, idleMillis: Long, waitingForUser: Boolean): AgentRunStalled? = when {
    elapsedMillis >= RUN_CEILING_MILLIS -> AgentRunStalled(
        "The agent run reached the ${RUN_CEILING_MILLIS / 3_600_000} hour limit, so the app stopped it. " +
            "Its work so far is kept; send a message to continue.", resumable = false)
    !waitingForUser && idleMillis >= RUN_IDLE_LIMIT_MILLIS -> AgentRunStalled(
        "The agent sent no updates for ${RUN_IDLE_LIMIT_MILLIS / 60_000} minutes, so the app ended that request.",
        resumable = true)
    else -> null
}

private val resumableText = listOf(
    "sent no data for", "stream closed unexpectedly", "did not answer within", "connection failed",
    "Android restarted the Agent SDK runtime", "HTTP 408",
)
private val serverError = Regex("""HTTP 5\d\d\b""")

/**
 * Whether a fresh request could get past this failure. Stops, rejected keys, missing credits, moderation, rate
 * limits and bad requests would fail the same way again, so they end the turn.
 */
internal fun resumableFailure(error: Throwable): Boolean {
    if (error is AgentRunStalled) return error.resumable
    val message = error.message ?: return false
    return resumableText.any { message.contains(it, ignoreCase = true) } || serverError.containsMatchIn(message)
}

/** What the app does after a request in a run fails. */
internal sealed class RunRecoveryStep {
    /** Give up and keep the turn as stopped, with Continue and Retry. */
    object Stop : RunRecoveryStep()
    /** Continue from the last checkpoint in a fresh request, telling the model where it stopped. */
    data class Continue(val checkpoint: RunCheckpoint) : RunRecoveryStep()
    /** Send the same request again from the start: it stopped before it saved any step, and nothing had run. */
    object Resend : RunRecoveryStep()
}

/**
 * Decides how to recover from a failed request. A checkpoint wins: the run continues from its last saved step. With
 * no checkpoint, the first request is sent again only when its stream closed while the model was still only
 * reasoning or writing, because then no hosted tool can run twice.
 */
internal fun recoveryStep(error: Throwable, checkpoint: RunCheckpoint?, continues: Int): RunRecoveryStep {
    if (continues >= MAX_AUTO_CONTINUES || !resumableFailure(error)) return RunRecoveryStep.Stop
    checkpoint?.takeIf { it.history != null }?.let { return RunRecoveryStep.Continue(it) }
    return if (error.message?.contains(STREAM_DROP_SAFE_TO_RESEND) == true) RunRecoveryStep.Resend
        else RunRecoveryStep.Stop
}

/** The message that continues a stopped run in a fresh request. The user doesn't see it as a chat message. */
internal fun continuePrompt(reason: String): String =
    "The previous request stopped before you finished (${reason.take(200).trimEnd('.')}). Continue the same task " +
        "from where you left off. Check the files you already saved and your progress notes, don't redo finished " +
        "steps, and keep each step short. When everything is done, give the full answer to my original request."

/** The visible message sent by the Continue button on a stopped reply. */
internal const val CONTINUE_MESSAGE = "Continue from where you stopped."

/** Added to the system instructions when the agent has tools, so long jobs are split up and survive a stop. */
internal const val LONG_TASK_GUIDANCE =
    "Long tasks: for a big job, start with a short numbered plan, then work through it one step at a time. " +
        "Keep each tool call short: don't run commands that take minutes (start servers in the background, add " +
        "timeouts, install only what you need). When you use the sandbox, save your work to files as you go and keep " +
        "a short PROGRESS.md with what is done and what is next, so nothing is lost if a request stops. If you are " +
        "asked to continue after a stop, read PROGRESS.md and your files and carry on from the next step instead of " +
        "starting over."

internal fun runInstructions(config: AgentConfig): String =
    if (config.agentToolsEnabled()) listOf(config.systemInstructions.trim(), LONG_TASK_GUIDANCE)
        .filter(String::isNotBlank).joinToString("\n\n")
    else config.systemInstructions

/** Reads a "checkpoint" event from the runtime. Bad or oversized parts are left out rather than failing the run. */
internal fun parseRunCheckpoint(value: JSONObject, maxHistoryChars: Int): RunCheckpoint {
    val history = value.optJSONArray("history")?.toString()?.takeIf { it.length <= maxHistoryChars }
    val partial = value.optJSONObject("response")?.let { runCatching { parseAgentResult(it.toString()) }.getOrNull() }
    return RunCheckpoint(history, partial)
}

/** Joins the result of a run that was continued with what its earlier, stopped requests produced. */
internal fun mergeContinuedRun(earlier: List<AgentRunResult>, last: AgentRunResult, continues: Int): AgentRunResult {
    if (earlier.isEmpty() && continues == 0) return last
    val usages = (earlier.mapNotNull { it.usage } + listOfNotNull(last.usage))
    val usage = usages.takeIf { it.isNotEmpty() }?.reduce { sum, next ->
        TokenUsage(sum.inputTokens + next.inputTokens, sum.outputTokens + next.outputTokens,
            sum.totalTokens + next.totalTokens, listOfNotNull(sum.cost, next.cost).takeIf { it.isNotEmpty() }?.sum(),
            sum.cachedTokens + next.cachedTokens, sum.reasoningTokens + next.reasoningTokens)
    }
    val note = if (continues == 1) "The app continued this run once after a request stopped."
        else "The app continued this run $continues times after requests stopped."
    return last.copy(
        shellSteps = earlier.flatMap { it.shellSteps } + last.shellSteps,
        searchQueries = (earlier.flatMap { it.searchQueries } + last.searchQueries).distinct(),
        citations = (earlier.flatMap { it.citations } + last.citations).distinctBy { it.url },
        discoveredContainerId = last.discoveredContainerId ?: earlier.lastNotNullOf { it.discoveredContainerId },
        usage = usage,
        notice = listOfNotNull(last.notice, note).joinToString("\n"),
        media = earlier.flatMap { it.media } + last.media,
    )
}

private inline fun <T, R : Any> List<T>.lastNotNullOf(pick: (T) -> R?): R? = asReversed().firstNotNullOfOrNull(pick)

/** The chat entries for a turn that stopped: the user's message, then what the agent had done with the reason. */
internal fun stoppedTurnEntries(turn: InterruptedTurn): List<ChatEntry> = listOf(
    ChatEntry(isUser = true, text = turn.prompt, media = turn.media),
    ChatEntry(
        isUser = false,
        text = turn.reply.ifBlank { "The run stopped before the agent wrote a reply." },
        reasoningSummary = turn.reasoningSummary.ifBlank { null },
        shellSteps = turn.shellSteps,
        searchQueries = turn.searchQueries,
        citations = turn.citations,
        notice = "This reply stopped before it finished: ${turn.error ?: "the run ended early."}",
        toolActivity = turn.toolActivity,
        media = turn.generated,
        usage = turn.usage,
    ),
)

/**
 * Moves a stopped turn into the chat's messages, so nothing is lost when the next message is sent. When the turn
 * kept a checkpoint, the next request continues from it, with the sandbox the run used.
 */
internal fun Workspace.withStoppedTurnKept(): Workspace {
    val turn = interruptedTurn ?: return this
    val history = turn.history
    return copy(
        messages = messages + stoppedTurnEntries(turn),
        sdkHistory = history ?: sdkHistory?.let { appendPlainTurn(it, turn) },
        sdkState = if (history != null) null else sdkState,
        containerId = turn.containerId ?: containerId,
        interruptedTurn = null,
    )
}

/** Adds the stopped turn as plain messages to a saved SDK history, so the model still sees it without a checkpoint. */
private fun appendPlainTurn(history: String, turn: InterruptedTurn): String? = runCatching {
    JSONArray(history).apply {
        put(JSONObject().put("type", "message").put("role", "user").put("content", turn.prompt))
        if (turn.reply.isNotBlank()) {
            put(JSONObject().put("type", "message").put("role", "assistant").put("content", turn.reply))
        }
    }.toString()
}.getOrNull() ?: history
