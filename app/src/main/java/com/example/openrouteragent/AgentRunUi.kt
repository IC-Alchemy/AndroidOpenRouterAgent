package com.example.openrouteragent

import android.os.SystemClock
import android.widget.FrameLayout
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
                Text("Step ${progress.step} · $duration$model", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!following) TextButton(onClick = onFollow) { Text("Latest") }
            TextButton(onClick = onActivity) { Text("Activity") }
        }
        if (!waitingForApproval && idle >= 12) {
            Text("No new events for ${idle}s", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
internal fun LiveAgentReply(progress: AgentProgress, interrupted: Boolean = false, onRetry: (() -> Unit)? = null,
    onRetryWithoutTools: (() -> Unit)? = null, onActivity: (() -> Unit)? = null) {
    if (!interrupted && progress.answer.isBlank() && progress.summary.isBlank()) return
    OutlinedSection(title = if (interrupted) "Run interrupted" else null) {
        if (progress.answer.isNotBlank()) {
            CopyableText(progress.answer, style = MaterialTheme.typography.bodyMedium)
        } else if (progress.summary.isNotBlank()) {
            Text("Reasoning summary", style = MaterialTheme.typography.labelMedium)
            CopyableText(progress.summary.takeLast(420), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, keepSelectionOnReplacement = true)
        }
        if (interrupted) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onRetry?.let { TextButton(onClick = it) { Text("Retry") } }
                onRetryWithoutTools?.let { TextButton(onClick = it) { Text("Retry without tools") } }
            }
            onActivity?.let { TextButton(onClick = it) { Text("Run activity") } }
        }
    }
}

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
                if (progress.summary.isNotBlank()) {
                    OutlinedSection(title = "Reasoning summary") {
                        CopyableText(progress.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
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
    OutlinedSection(title = "${activity.name} · ${activity.status.replace('_', ' ')}") {
        activity.detail?.takeIf { it.isNotBlank() }?.let {
            CopyableText(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

internal fun TokenUsage.display(): String = buildString {
    append("$totalTokens tokens · $inputTokens in / $outputTokens out")
    if (cachedTokens > 0) append(" · $cachedTokens cached")
    cost?.let { value ->
        append(" · ")
        append(NumberFormat.getCurrencyInstance(Locale.US).apply { maximumFractionDigits = 5 }.format(value))
    }
}

private fun AgentProgress.status(providerName: String = "OpenRouter"): String = when (phase) {
    "starting" -> "Starting agent"
    "mcp" -> "Connecting to MCP server"
    "requesting" -> "Preparing model request"
    "connecting" -> "Connecting to $providerName"
    "retrying" -> "Retrying $providerName" + detail?.let { " · $it" }.orEmpty()
    "connected" -> "Connected · waiting for model events"
    "waiting" -> "Waiting for the model"
    "reasoning" -> "Model is reasoning"
    "tools" -> {
        val active = tools.lastOrNull { it.status in setOf("preparing", "in_progress", "searching", "running", "running in background") }
        active?.let { "Tool · ${it.name}" } ?: "Processing tools"
    }
    "answering" -> "Streaming reply"
    "finishing" -> "Saving reply"
    "repetition" -> "Agent detected repeating steps"
    else -> "Waiting for updates"
}
