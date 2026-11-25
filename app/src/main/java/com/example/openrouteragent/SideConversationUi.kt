package com.example.openrouteragent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/**
 * The side conversation panel. It slides in from the right on top of the main chat, which keeps running underneath.
 * Tapping outside it, the close button or the back gesture closes it; the side conversation stays until "New side
 * conversation" clears it.
 */
@Composable
internal fun SideConversationPanel(
    agent: AgentViewModel,
    onVoice: () -> Unit,
    onCopyText: (String) -> Unit,
    onReport: (prompt: String, response: String) -> Unit,
    showReasoning: Boolean,
    renderMarkdown: Boolean,
) {
    val side = agent.side
    val visible = side.isOpen
    BackHandler(enabled = visible) { side.close() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Phones keep a sliver of the main chat in view; wider screens get a side sheet.
        val panelWidth = if (maxWidth < 600.dp) maxWidth * 0.94f else min(560.dp, maxWidth * 0.6f)
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .pointerInput(Unit) { detectTapGestures { side.close() } })
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            Surface(
                modifier = Modifier.width(panelWidth).fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 12.dp,
                tonalElevation = 2.dp,
            ) {
                SideConversationContent(agent, onVoice, onCopyText, onReport, showReasoning, renderMarkdown)
            }
        }
    }
}

@Composable
private fun SideConversationContent(
    agent: AgentViewModel,
    onVoice: () -> Unit,
    onCopyText: (String) -> Unit,
    onReport: (prompt: String, response: String) -> Unit,
    showReasoning: Boolean,
    renderMarkdown: Boolean,
) {
    val side = agent.side
    val messages = side.messages
    val pending = side.pendingPrompt
    val listState = rememberLazyListState()
    val count = messages.size + if (pending != null) 2 else 0
    LaunchedEffect(count, side.liveText.length) { if (count > 0) listState.requestScrollToItem(count) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(StudioSymbols.SideConversation, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Side conversation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = listOfNotNull(agent.selectedModel?.name ?: "Choose a model",
                    "main chat running".takeIf { agent.activePrompt != null }).joinToString(" · ")
                Text(status, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ControlTooltip("New side conversation") {
                IconButton(onClick = side::startNew, enabled = messages.isNotEmpty() || pending != null ||
                    side.prompt.isNotBlank() || !side.includeMainContext) {
                    Icon(StudioSymbols.NewChat, "New side conversation")
                }
            }
            ControlTooltip("Close side conversation") {
                IconButton(onClick = side::close) { Icon(Icons.Default.Close, "Close side conversation") }
            }
        }
        IncludeMainContextRow(agent)
        HorizontalDivider(thickness = StudioHairlineWidth, color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty() && pending == null) {
                item("empty") {
                    Text("Ask something on the side. The main chat keeps going, and nothing here is added to it.",
                        Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(messages, key = { _, entry -> entry.id }) { index, entry ->
                if (entry.isUser) UserMessageBubble(entry.text)
                else AgentResponseCard(
                    entry = entry,
                    onCopyText = onCopyText,
                    onFileClick = { _, _ -> },
                    showReasoning = showReasoning,
                    renderMarkdown = renderMarkdown,
                    onReport = { onReport(messages.take(index).lastOrNull { it.isUser }?.text.orEmpty(), entry.text) },
                )
            }
            if (pending != null) {
                item("side_pending") { UserMessageBubble(pending) }
                item("side_live") {
                    val live = side.liveText
                    if (live.isBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Thinking…", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LiveAgentReply(AgentProgress(side.startedAt, phase = "answering", answer = live),
                            renderMarkdown = renderMarkdown)
                    }
                }
            }
        }
        AgentInputBar(
            value = side.prompt,
            onValueChange = {
                side.prompt = it
                side.clearError()
            },
            isRunning = side.isRunning,
            isLoading = agent.isLoading,
            error = side.error,
            onSend = agent::sendSide,
            onStop = side::stop,
            onVoice = onVoice,
            placeholder = "Message on the side",
        )
    }
}

@Composable
private fun IncludeMainContextRow(agent: AgentViewModel) {
    val side = agent.side
    val messages = agent.workspace.messages
    val pending = agent.activePrompt
    val context = remember(messages, pending) { agent.mainChatContextForSide() }
    val detail = when {
        !side.includeMainContext -> "Side messages are sent without the main chat."
        context == null -> "The main chat is empty, so there is nothing to include yet."
        context.omitted > 0 -> "Sends the latest ${context.included} main chat messages, read-only " +
            "(${context.omitted} older left out)."
        else -> "Sends the main chat's ${context.included} " +
            "${if (context.included == 1) "message" else "messages"} with each side message, read-only."
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Switch) { side.includeMainContext = !side.includeMainContext }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Include main chat context", style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = side.includeMainContext, onCheckedChange = null)
    }
}
