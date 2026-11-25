package com.example.openrouteragent

import android.os.SystemClock
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun AgentRuntimeHost(agent: AgentViewModel, modifier: Modifier) {
    AndroidView(
        factory = { context -> FrameLayout(context).also(agent::attachRuntime) },
        modifier = modifier,
        onRelease = agent::detachRuntime,
    )
}

@Composable
internal fun AgentRunStatus(
    progress: AgentProgress,
    waitingForApproval: Boolean,
    following: Boolean,
    providerName: String = "OpenRouter",
    onActivity: () -> Unit,
    onFollow: () -> Unit,
) {
    var now by remember(progress.started) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(progress.started) {
        while (isActive) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val idle = ((now - progress.lastEvent) / 1_000).coerceAtLeast(0)
    val runningTime = ((now - progress.started) / 1_000).coerceAtLeast(0)
    val status = if (waitingForApproval) "Waiting for your approval" else progress.status(providerName)
    OutlinedSection(Modifier.widthIn(max = 840.dp).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text(status, style = MaterialTheme.typography.labelMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val duration = if (runningTime < 60) "${runningTime}s"
                    else "${runningTime / 60}m ${runningTime % 60}s"
                val model = progress.model?.let { " · $it" }.orEmpty()
                val tokens = progress.outputTokens.takeIf { it > 0 }?.let { count ->
                    val estimate = if ((progress.usage?.outputTokens ?: 0) >= count) "" else "~"
                    val speed = progress.tokensPerSecond()?.let { " · ${it.toInt()} tok/s" }.orEmpty()
                    " · $estimate${compactCount(count)} tokens$speed"
                }.orEmpty()
                Text(
                    "Step ${progress.step} · $duration$tokens$model",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!following) SymbolAction("Latest", Icons.Default.KeyboardArrowDown, onFollow)
            SymbolAction("Activity", StudioSymbols.Activity, onActivity)
        }
        if (!waitingForApproval && idle >= 12) {
            Text("No new events for ${idle}s", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
internal fun LiveAgentReply(progress: AgentProgress, interrupted: Boolean = false, showReasoning: Boolean = false,
    onShowReasoning: ((Boolean) -> Unit)? = null, renderMarkdown: Boolean = true, onRetry: (() -> Unit)? = null,
    onRetryWithoutTools: (() -> Unit)? = null, onActivity: (() -> Unit)? = null, onContinue: (() -> Unit)? = null) {
    // Interrupted turns restored from storage have no timeline, only the joined text.
    val blocks = progress.timeline.ifEmpty {
        listOfNotNull(progress.summary.takeIf { it.isNotBlank() }?.let { LiveBlock.Thinking(it) },
            progress.answer.takeIf { it.isNotBlank() }?.let { LiveBlock.Answer(it) })
    }
    // The model is reasoning but no reasoning text is streaming yet, or the provider keeps it private.
    val silentThinking = !interrupted && progress.phase == "reasoning" && blocks.lastOrNull() !is LiveBlock.Thinking
    if (!interrupted && blocks.isEmpty() && !silentThinking) return
    OutlinedSection(title = if (interrupted) "Run interrupted" else null) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { index, block ->
                val live = !interrupted && !silentThinking && index == blocks.lastIndex
                // Keys follow each block, so expanded state stays put when old blocks are trimmed.
                key(progress.timelineOffset + index) {
                    when (block) {
                        is LiveBlock.Thinking -> ThinkingBlock(block.text, live, showReasoning, onShowReasoning)
                        is LiveBlock.ToolCall -> progress.tool(block.id)?.let { LiveToolRow(it) }
                        is LiveBlock.Answer -> ReplyText(block.text, renderMarkdown = renderMarkdown,
                            keepSelectionOnReplacement = live)
                    }
                }
            }
            if (silentThinking) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Text(if (progress.detail == "hidden") "Thinking privately · the provider does not share its reasoning"
                    else "Thinking…", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (interrupted) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onContinue?.let { TextButton(onClick = it) { Text("Continue") } }
                onRetry?.let { TextButton(onClick = it) { Text("Retry") } }
                onRetryWithoutTools?.let { TextButton(onClick = it) { Text("Retry without tools") } }
            }
            onActivity?.let { SymbolAction("Run activity", StudioSymbols.Activity, it) }
        }
    }
}

private const val LIVE_THINKING_TAIL = 520

@Composable
private fun ThinkingBlock(text: String, live: Boolean, show: Boolean, onShow: ((Boolean) -> Unit)?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (!show) {
        if (live) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text("Thinking…", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            onShow?.let { TextButton(onClick = { it(true) }) { Text("Show reasoning") } }
        }
        return
    }
    StudioRailBlock(
        railColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (live) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(if (live) "Thinking…" else "Thought", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            onShow?.let { TextButton(onClick = { it(false) }) { Text("Hide") } }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null, modifier = Modifier.size(18.dp))
        }
        val style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic)
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            expanded -> CopyableText(
                text.trim(),
                modifier = Modifier.padding(bottom = 8.dp),
                style = style,
                color = color,
                keepSelectionOnReplacement = live,
            )
            live -> Text(
                if (text.length > LIVE_THINKING_TAIL) "…" + text.takeLast(LIVE_THINKING_TAIL).trimStart()
                else text.trim(),
                modifier = Modifier.padding(bottom = 8.dp),
                style = style,
                color = color,
            )
        }
    }
}

private val finishedToolStatuses = setOf("completed", "failed", "cancelled", "timed_out", "expired", "incomplete", "declined",
    "stopped")

/** One tool call in the live timeline: a spinner while it runs, its streaming input or result underneath. */
@Composable
private fun LiveToolRow(tool: LiveTool) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = tool.status !in finishedToolStatuses
    val failed = tool.status in setOf("failed", "cancelled", "timed_out", "expired", "declined", "stopped")
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 2.dp).size(16.dp), contentAlignment = Alignment.Center) {
            when {
                active -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                failed -> Text("✕", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                else -> Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text("${toolLabel(tool.name)} · ${tool.status.replace('_', ' ')}", style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            tool.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                if (expanded) CopyableText(detail, style = mono, color = color, keepSelectionOnReplacement = active)
                else {
                    val line = detail.replace(whitespace, " ").trim()
                    // While the model writes the input, the newest part is the interesting part.
                    Text(if (active && line.length > 180) "…" + line.takeLast(180) else line, style = mono, color = color,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val whitespace = Regex("\\s+")

@Composable
internal fun RunActivityDialog(
    progress: AgentProgress,
    providerName: String,
    onDismiss: () -> Unit,
    onFile: (LiveFile) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run activity") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (progress.summary.isNotBlank()) CollapsibleReasoningBox(progress.summary)
                progress.tools.forEach {
                    ToolActivityRow(ToolActivity(it.name, it.status, it.detail))
                }
                if (progress.files.isNotEmpty()) OutlinedSection(title = "Sandbox files") {
                    progress.files.forEach { file ->
                        TextButton(onClick = { onFile(file) }) { Text(file.file.path) }
                    }
                }
                if (progress.sources.isNotEmpty()) OutlinedSection(title = "Sources") {
                    progress.sources.forEach { source ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(source.url) } }) {
                            Text(source.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                progress.usage?.let { Text(it.display(), style = MaterialTheme.typography.labelSmall) }
                if (progress.summary.isBlank() && progress.tools.isEmpty() && progress.usage == null &&
                    progress.files.isEmpty() && progress.sources.isEmpty()) {
                    Text(progress.status(providerName), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun ToolActivityRow(activity: ToolActivity) {
    OutlinedSection(title = "${toolLabel(activity.name)} · ${activity.status.replace('_', ' ')}") {
        activity.detail?.takeIf { it.isNotBlank() }?.let {
            CopyableText(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

private fun toolLabel(name: String): String = if (name == "openrouter_body_builder") "Body Builder" else name

internal fun compactCount(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> "${value / 1_000}k"
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

internal fun TokenUsage.display(): String = buildString {
    append("$totalTokens tokens · $inputTokens in / $outputTokens out")
    if (cachedTokens > 0) append(" · $cachedTokens cached")
    if (reasoningTokens > 0) append(" · $reasoningTokens reasoning")
    cost?.let { value ->
        append(" · ")
        append(formatCost(value))
    }
}

internal fun formatCost(value: Double): String =
    NumberFormat.getCurrencyInstance(Locale.US).apply { maximumFractionDigits = 5 }.format(value)

/**
 * The one short line shown under a reply: its cost, or its token total when the
 * provider reported no cost, or a dash when there is no usage at all.
 */
internal fun replyUsageSummary(usage: TokenUsage?): String = when {
    usage == null -> "–"
    usage.cost != null -> formatCost(usage.cost)
    else -> "${usage.totalTokens} tokens"
}

/** The full details shown when the reply's usage line is expanded, one fact per line. */
internal fun replyUsageDetails(model: String?, usage: TokenUsage?): List<String> = buildList {
    model?.takeIf { it.isNotBlank() }?.let { add("Model: $it") }
    usage?.let {
        add("Tokens: ${it.totalTokens} total · ${it.inputTokens} in / ${it.outputTokens} out")
        if (it.cachedTokens > 0) add("Cached: ${it.cachedTokens} tokens")
        if (it.reasoningTokens > 0) add("Reasoning: ${it.reasoningTokens} tokens")
        it.cost?.let { value -> add("Cost: ${formatCost(value)}") }
    }
}

internal fun AgentProgress.status(providerName: String = "OpenRouter"): String = when (phase) {
    "starting" -> "Starting agent"
    "mcp" -> "Connecting to MCP server"
    "requesting" -> "Preparing model request"
    "connecting" -> "Connecting to $providerName"
    "retrying" -> "Retrying $providerName" + detail?.let { " · $it" }.orEmpty()
    "connected" -> "Connected · waiting for model events"
    "waiting" -> "Waiting for the model"
    "reasoning" -> if (detail == "hidden") "Model is reasoning privately" else "Model is reasoning"
    "tools" -> {
        val active = tools.lastOrNull { it.status in setOf("preparing", "in_progress", "searching", "running", "running in background") }
        active?.let { "Tool · ${toolLabel(it.name)}" } ?: "Processing tools"
    }
    "answering" -> "Streaming reply"
    "finishing" -> "Saving reply"
    "continuing" -> "Request stopped · continuing from saved progress" + detail?.let { " ($it)" }.orEmpty()
    "repetition" -> "Agent detected repeating steps"
    else -> "Waiting for updates"
}
