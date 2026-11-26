package com.example.openrouteragent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class QuickPrompt(val icon: ImageVector, val title: String, val message: String)

private val QUICK_PROMPTS = listOf(
    QuickPrompt(
        StudioSymbols.Sandbox,
        "Check sandbox",
        "Check the Linux OS, Python, and Node.js versions in my sandbox.",
    ),
    QuickPrompt(
        StudioSymbols.Models,
        "Compare models",
        "Use Body Builder to compare Claude and Gemini on why stars shine.",
    ),
    QuickPrompt(
        Icons.Default.Search,
        "Search the web",
        "Search the web for the latest OpenRouter server tools updates and summarize them.",
    ),
)

/**
 * The Files & tools toggle, the labels toggle and the chat modes note. They sit in the
 * model row above the chat, so they don't take a row of their own away from the replies.
 */
@Composable
internal fun ChatToolsActions(
    expanded: Boolean,
    onToggle: () -> Unit,
    attachmentCount: Int,
) {
    val label = if (attachmentCount > 0) "Files & tools · $attachmentCount attached" else "Files & tools"
    Row(verticalAlignment = Alignment.CenterVertically) {
        ControlTooltip(label) {
            TextButton(
                onClick = onToggle,
                modifier = Modifier.heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = label
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Icon(StudioSymbols.Folder, null, modifier = Modifier.size(20.dp))
                if (attachmentCount > 0) {
                    Text(attachmentCount.toString(), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 2.dp))
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
                    modifier = Modifier.size(16.dp))
            }
        }
        ControlLabelsToggle()
        StudioInfoButton("Chat modes") {
            Text("Agent can use tools and work through several steps. Chat gives a direct reply.")
        }
    }
}

@Composable
internal fun StatusCapabilitiesBar(
    config: AgentConfig,
    activeContainerId: String?,
    canDetach: Boolean,
    inProject: Boolean,
    attachmentCount: Int,
    onOpenProjectFiles: () -> Unit,
    onOpenWorkspaceFiles: (() -> Unit)?,
    onClearAttachments: () -> Unit,
    onOpenFiles: () -> Unit,
    onResetContainer: () -> Unit,
) {
    val showLabels = LocalControlLabels.current.visible
    Surface(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(StudioHairlineWidth, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val filesLabel = if (inProject) "Project files" else "Chat files"
            ControlTooltip(filesLabel, enabled = !showLabels) {
                AssistChip(
                    onClick = onOpenProjectFiles,
                    label = { SymbolLabel(filesLabel, StudioSymbols.Folder) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
            if (onOpenWorkspaceFiles != null) {
                val workspaceLabel =
                    if (attachmentCount == 0) "Workspace files" else "Workspace files · $attachmentCount attached"
                ControlTooltip(workspaceLabel, enabled = !showLabels) {
                    AssistChip(
                        onClick = onOpenWorkspaceFiles,
                        label = {
                            SymbolLabel(
                                workspaceLabel,
                                StudioSymbols.Workspace,
                                badge = attachmentCount.takeIf { it > 0 }?.toString(),
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                if (attachmentCount > 0) {
                    SymbolAction("Clear attachments", StudioSymbols.Detach, onClearAttachments, canDetach)
                }
            }
            if (config.enableShellSandbox) {
                if (activeContainerId != null) {
                    ControlTooltip("Sandbox files", enabled = !showLabels) {
                        AssistChip(
                            onClick = onOpenFiles,
                            label = { SymbolLabel("Sandbox files", StudioSymbols.Sandbox) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                    if (!inProject) {
                        SymbolAction("Detach sandbox", StudioSymbols.Detach, onResetContainer, canDetach)
                    }
                } else {
                    StatusPill("Hosted shell", StudioSymbols.Sandbox, MaterialTheme.colorScheme.secondary)
                }
            }
            if (config.enableWebSearch) {
                StatusPill(
                    label = "Web Search",
                    icon = Icons.Default.Search,
                    accent = MaterialTheme.colorScheme.primary,
                )
            }
            if (config.enableShellSandbox && config.allowSandboxNetwork) {
                StatusPill(
                    label = "Egress Allowlist",
                    icon = StudioSymbols.Network,
                    accent = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
internal fun StatusPill(
    label: String,
    icon: ImageVector,
    accent: Color,
) {
    ControlTooltip(label, enabled = !LocalControlLabels.current.visible) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = accent.copy(alpha = 0.11f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(StudioHairlineWidth, accent.copy(alpha = 0.38f)),
        ) {
            Box(Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    SymbolLabel(label, icon)
                }
            }
        }
    }
}

@Composable
internal fun EmptyAgentState(
    hasApiKey: Boolean,
    onOpenSettings: () -> Unit,
    tools: AgentConfig? = AgentConfig(enableShellSandbox = true, enableWebSearch = true),
    onSelectPrompt: (String) -> Unit,
) {
    val starters = listOfNotNull(
        QUICK_PROMPTS[0].takeIf { tools?.enableShellSandbox == true },
        QUICK_PROMPTS[1].takeIf { tools?.enableShellSandbox == true },
        QUICK_PROMPTS[2].takeIf { tools?.enableWebSearch == true },
    ).ifEmpty {
        listOf(
            QuickPrompt(StudioSymbols.Agent, "Plan a project", "Help me turn a project idea into a realistic plan."),
            QuickPrompt(StudioSymbols.Chat, "Rewrite a draft", "Ask me for a draft, then help me make it clearer."),
            QuickPrompt(StudioSymbols.Models, "Learn something", "Help me learn a topic one short exercise at a time."),
        )
    }
    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(StudioHairlineWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier.size(60.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = StudioSymbols.Agent,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "What are you working on?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(26.dp))

        if (!hasApiKey) {
            Button(onClick = onOpenSettings) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Connect OpenRouter")
            }
            Spacer(Modifier.height(20.dp))
        }

        Text(
            text = "Try a prompt",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Start).semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))

        if (LocalControlLabels.current.visible) {
            starters.forEach { prompt ->
                QuickPromptCard(
                    prompt,
                    compact = false,
                    onSelect = onSelectPrompt,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                starters.forEach { prompt ->
                    Box(Modifier.weight(1f)) {
                        ControlTooltip(prompt.message) {
                            QuickPromptCard(prompt, compact = true, onSelect = onSelectPrompt)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPromptCard(
    prompt: QuickPrompt,
    compact: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val symbol: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = colors.primaryContainer.copy(alpha = 0.6f),
            border = BorderStroke(StudioHairlineWidth, colors.primary.copy(alpha = 0.35f)),
        ) {
            Icon(
                prompt.icon,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.padding(7.dp).size(20.dp),
            )
        }
    }
    Card(
        onClick = { onSelect(prompt.message) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        border = BorderStroke(StudioHairlineWidth, colors.outlineVariant),
    ) {
        if (compact) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
            ) {
                symbol()
                Text(prompt.title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        } else {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                symbol()
                Text(prompt.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
