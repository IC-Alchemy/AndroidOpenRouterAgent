package com.example.openrouteragent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val QUICK_PROMPTS = listOf(
    "Check the Linux OS, Python, and Node.js versions in my sandbox.",
    "Write and run a Python script to simulate 10,000 dice rolls and save a summary report to report.txt.",
    "Search the web for the latest OpenRouter server tools updates and summarize them."
)

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
    onResetContainer: () -> Unit
) {
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(onClick = onOpenProjectFiles, label = { Text(if (inProject) "Project files" else "Chat files") },
                modifier = Modifier.heightIn(min = 48.dp))
            if (onOpenWorkspaceFiles != null) {
                AssistChip(onClick = onOpenWorkspaceFiles, label = {
                    Text(if (attachmentCount == 0) "Workspace files" else "Workspace files · $attachmentCount attached")
                }, modifier = Modifier.heightIn(min = 48.dp))
                if (attachmentCount > 0) TextButton(onClick = onClearAttachments, enabled = canDetach) {
                    Text("Clear attachments")
                }
            }
            if (config.enableShellSandbox) {
                if (activeContainerId != null) {
                    AssistChip(onClick = onOpenFiles, label = { Text("Sandbox files") }, modifier = Modifier.heightIn(min = 48.dp))
                    if (!inProject) TextButton(onClick = onResetContainer, enabled = canDetach) { Text("Detach") }
                } else StatusPill("Hosted shell", MaterialTheme.colorScheme.secondary)
            }
            if (config.enableWebSearch) {
                StatusPill(
                    label = "Web Search",
                    accent = MaterialTheme.colorScheme.primary
                )
            }
            if (config.enableShellSandbox && config.allowSandboxNetwork) {
                StatusPill(
                    label = "Egress Allowlist",
                    accent = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
internal fun StatusPill(
    label: String,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(StudioHairlineWidth, accent.copy(alpha = 0.38f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StudioStatusDot(color = accent, size = 5.dp)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
internal fun EmptyAgentState(
    hasApiKey: Boolean,
    onOpenSettings: () -> Unit,
    onSelectPrompt: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(StudioHairlineWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier.size(60.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Run code, work with files, and search the web using OpenRouter’s hosted tools.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

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
            modifier = Modifier.align(Alignment.Start).semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))

        QUICK_PROMPTS.forEachIndexed { index, sample ->
            Card(
                onClick = { onSelectPrompt(sample) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                border = BorderStroke(StudioHairlineWidth, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        border = BorderStroke(StudioHairlineWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            text = "%02d".format(index + 1),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = sample,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
