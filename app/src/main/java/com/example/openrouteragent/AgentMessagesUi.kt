package com.example.openrouteragent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import java.io.File

@Composable
internal fun UserMessageBubble(
    text: String,
    media: List<ChatMedia> = emptyList(),
    mediaFile: (ChatMedia) -> java.io.File? = { null },
    onOpenMedia: (ChatMedia) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MediaStrip(media, mediaFile, onOpenMedia, size = 96.dp)
        if (text.isNotBlank()) Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(StudioHairlineWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            CopyableText(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

internal fun agentResponseReportContentColor(
    isWarningCard: Boolean,
    colors: androidx.compose.material3.ColorScheme,
): Color = if (isWarningCard) colors.onErrorContainer else colors.primary

@Composable
internal fun AgentResponseCard(
    entry: ChatEntry,
    onCopyText: (String) -> Unit,
    onFileClick: (containerId: String, fileRef: ContainerFileRef) -> Unit,
    showReasoning: Boolean = true,
    renderMarkdown: Boolean = true,
    onReport: () -> Unit = {},
    mediaFile: (ChatMedia) -> File? = { null },
    onOpenMedia: (ChatMedia) -> Unit = {},
    speaking: Boolean = false,
    onSpeak: (() -> Unit)? = null,
) {
    val isWarningCard = entry.isError || entry.isModerated
    val containerColor = if (isWarningCard) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            StudioHairlineWidth,
            if (isWarningCard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Main Assistant Text
            Row(verticalAlignment = Alignment.Top) {
                if (isWarningCard) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = if (entry.isModerated) "Content policy warning" else "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(end = 8.dp, top = 2.dp)
                            .size(18.dp)
                    )
                }
                // Errors and moderation notices come from the app, not the model, so they stay plain text.
                if (renderMarkdown && !isWarningCard) {
                    MarkdownText(entry.text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                } else {
                    CopyableText(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isWarningCard) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (entry.media.isNotEmpty() && !isWarningCard) GeneratedImages(entry.media, mediaFile, onOpenMedia)

            entry.notice?.let {
                CopyableText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }

            // Optional collapsible reasoning summary
            if (entry.toolActivity.isNotEmpty()) {
                var showToolActivity by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { showToolActivity = !showToolActivity }) {
                    Text(if (showToolActivity) "Hide tool activity" else "Tool activity")
                }
                if (showToolActivity) entry.toolActivity.forEach { ToolActivityRow(it) }
            }
            if (showReasoning && !entry.reasoningSummary.isNullOrBlank()) {
                CollapsibleReasoningBox(entry.reasoningSummary)
            }

            // Hosted Linux Sandbox Execution Cards
            entry.shellSteps.forEachIndexed { idx, step ->
                SandboxTerminalCard(
                    stepIndex = idx + 1,
                    step = step,
                    onFileClick = onFileClick
                )
            }

            // Searches and sources stay folded into one row so they never crowd out the reply.
            SourcesDisclosure(entry.citations, entry.searchQueries)

            // Footer with usage metadata, Copy reply (when safe), and Report button on every AI bubble
            HorizontalDivider(
                thickness = StudioHairlineWidth,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            val usageDetails = replyUsageDetails(entry.modelUsed, entry.usage)
            var showUsageDetails by rememberSaveable(entry.id) { mutableStateOf(false) }
            val footerTextColor = if (isWarningCard) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (usageDetails.isNotEmpty()) {
                    // Unweighted actions are measured first, so the cost line takes what's left
                    // and never pushes the buttons off a narrow phone.
                    ReplyUsageToggle(
                        summary = replyUsageSummary(entry.usage),
                        expanded = showUsageDetails,
                        color = footerTextColor,
                        onToggle = { showUsageDetails = !showUsageDetails },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isWarningCard) {
                        SymbolAction("Copy reply", StudioSymbols.Copy, onClick = { onCopyText(entry.text) })
                        if (onSpeak != null) {
                            SymbolAction(if (speaking) "Stop reading" else "Read aloud",
                                if (speaking) Icons.Default.Close else StudioSymbols.Speaker, onClick = onSpeak)
                        }
                    }
                    SymbolAction(
                        label = "Report", icon = Icons.Default.Warning,
                        onClick = onReport,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = agentResponseReportContentColor(isWarningCard, MaterialTheme.colorScheme),
                        ),
                    )
                }
            }
            AnimatedVisibility(visible = showUsageDetails && usageDetails.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    usageDetails.forEach { line ->
                        CopyableText(
                            line,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = footerTextColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One line under a reply showing only what the turn cost. Tapping it shows or hides
 * the model and token details below the footer.
 */
@Composable
internal fun ReplyUsageToggle(
    summary: String,
    expanded: Boolean,
    color: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Hide usage details" else "Show usage details",
                onClick = onToggle,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Reply cost"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun CollapsibleReasoningBox(summary: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    StudioRailBlock(
        railColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button) { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            CopyableText(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}
