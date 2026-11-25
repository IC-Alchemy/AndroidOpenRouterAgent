package com.example.openrouteragent

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Sends one side request: where it goes, the side conversation so far, the main chat as context, and live text. */
internal typealias SideStreamer =
    suspend (SideTarget, List<SideTextMessage>, MainChatContext?, (String) -> Unit) -> SideReply

/**
 * The side conversation's state. It runs in its own coroutine with its own HTTP client, so it never waits for, blocks
 * or cancels a main chat run, and the main chat never cancels it. It lives as long as the screen's view model: closing
 * the panel keeps it, and "New side conversation" clears it. It is not saved to the chat library.
 */
internal class SideConversationModel(
    private val scope: CoroutineScope,
    private val stream: SideStreamer = SideConversationClient()::stream,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    var isOpen by mutableStateOf(false)
        private set
    /** Send the main chat with each side message as read-only context. On by default and for each new side conversation. */
    var includeMainContext by mutableStateOf(true)
    var prompt by mutableStateOf("")
    var messages by mutableStateOf<List<ChatEntry>>(emptyList())
        private set
    /** The side message waiting for its reply, or null. */
    var pendingPrompt by mutableStateOf<String?>(null)
        private set
    /** The reply so far. Empty while moderation is on, because a reply is only shown once it has been checked. */
    var liveText by mutableStateOf("")
        private set
    var startedAt by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** A side reply finished while the panel was closed. */
    var unread by mutableStateOf(false)
        private set
    val isRunning: Boolean get() = pendingPrompt != null

    private var job: Job? = null
    /** Bumped by [startNew], so a request from a cleared conversation can't write into the new one. */
    private var generation = 0

    fun open() {
        isOpen = true
        unread = false
    }

    fun close() {
        isOpen = false
    }

    fun fail(message: String) {
        error = message
    }

    fun clearError() {
        error = null
    }

    /** Stops the side request. The main chat is not touched. */
    fun stop() {
        job?.cancel()
    }

    fun startNew() {
        job?.cancel()
        generation++
        job = null
        messages = emptyList()
        pendingPrompt = null
        liveText = ""
        prompt = ""
        error = null
        unread = false
        includeMainContext = true
    }

    /** Puts [text] in the side composer after any draft there, so it isn't lost when it can't be sent now. */
    fun keepPrompt(text: String) {
        val typed = text.trim()
        if (typed.isNotEmpty()) prompt = listOf(prompt.trim(), typed).filter(String::isNotEmpty).joinToString("\n")
    }

    /**
     * Sends [typed], or the side composer's [prompt] when it is null. [context] is the main chat, or null when it isn't
     * included. Text typed elsewhere (such as "/btw …" in the main composer) leaves the side composer's draft alone, and
     * waits there when a side reply is still running.
     */
    fun send(target: SideTarget, context: MainChatContext?, moderator: TextModerator?, typed: String? = null) {
        val text = (typed ?: prompt).trim()
        if (text.isEmpty()) return
        if (isRunning) {
            if (typed != null) keepPrompt(typed)
            return
        }
        val history = messages.filter { !it.isError && !it.isModerated }.map { SideTextMessage(it.isUser, it.text) } +
            SideTextMessage(true, text)
        val run = generation
        val question = ChatEntry(isUser = true, text = text)
        error = null
        if (typed == null) prompt = ""
        liveText = ""
        startedAt = clock()
        pendingPrompt = text
        job = scope.launch {
            try {
                if (moderator != null && !moderator.safe(text)) {
                    if (run == generation) {
                        error = POLICY_PROMPT_BLOCKED
                        if (prompt.isBlank()) prompt = text
                    }
                    return@launch
                }
                val reply = stream(target, history, context) { partial ->
                    if (run == generation && moderator == null) liveText = partial
                }
                val answer = if (moderator == null || moderator.safe(reply.text)) {
                    ChatEntry(isUser = false, text = reply.text, usage = reply.usage, modelUsed = reply.model)
                } else {
                    ChatEntry(isUser = false, text = POLICY_RESPONSE_WARNING, usage = reply.usage,
                        modelUsed = reply.model, isModerated = true)
                }
                if (run == generation) {
                    messages = messages + question + answer
                    if (!isOpen) unread = true
                }
            } catch (e: CancellationException) {
                if (run == generation) {
                    val partial = liveText
                    messages = messages + question + listOfNotNull(partial.takeIf { it.isNotBlank() }?.let {
                        ChatEntry(isUser = false, text = it, notice = "Stopped before the reply finished.")
                    })
                }
                throw e
            } catch (e: Exception) {
                if (run == generation) {
                    messages = messages + question +
                        ChatEntry(isUser = false, text = e.message ?: "The side request failed.", isError = true)
                    if (!isOpen) unread = true
                }
            } finally {
                if (run == generation) {
                    pendingPrompt = null
                    liveText = ""
                    job = null
                }
            }
        }
    }

    private suspend fun TextModerator.safe(text: String): Boolean = try {
        isSafe(text)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        true // the main chat also lets a message through when the checker itself fails
    }
}

/** A main chat message as context text: its words, plus a note naming any attachments, which aren't sent. */
internal fun ChatEntry.asSideContext(): SideTextMessage? {
    if (isError || isModerated) return null
    val note = media.takeIf { it.isNotEmpty() }?.joinToString(", ", "[Attached: ", "]") { it.name.ifBlank { it.kind } }
    val text = listOfNotNull(text.trim().takeIf(String::isNotEmpty), note).joinToString("\n")
    return text.takeIf(String::isNotEmpty)?.let { SideTextMessage(isUser, it) }
}
