package com.example.openrouteragent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
internal fun SandboxTerminalCard(
    stepIndex: Int,
    step: ShellExecutionStep,
    onFileClick: (containerId: String, fileRef: ContainerFileRef) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val terminalBg = StudioTerminalColors.background
    val terminalHeaderBg = StudioTerminalColors.headerBackground
    val terminalText = StudioTerminalColors.text
    val terminalMuted = StudioTerminalColors.muted
    val terminalGreen = StudioTerminalColors.green
    val terminalRed = StudioTerminalColors.red
    val terminalStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val outputStyle = terminalStyle.copy(fontSize = 11.sp, lineHeight = 16.sp)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = terminalBg,
        border = BorderStroke(StudioHairlineWidth, StudioTerminalColors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Terminal Title Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(terminalHeaderBg)
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StudioStatusDot(color = terminalGreen, size = 6.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Sandbox run $stepIndex",
                        color = terminalText,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    if (!step.containerId.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = step.containerId.take(10),
                            color = terminalMuted,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = terminalMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (step.results.isNotEmpty()) {
                        step.results.forEach { res ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    CopyableText(
                                        text = res.command,
                                        color = terminalGreen,
                                        style = terminalStyle,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "[${res.outcome}]",
                                        color = if (res.outcome == "exit 0" || res.outcome == "done") {
                                            terminalMuted
                                        } else {
                                            terminalRed
                                        },
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }

                                if (res.stdout.isNotBlank()) {
                                    CopyableText(res.stdout, color = terminalText, style = outputStyle)
                                }

                                if (res.stderr.isNotBlank()) {
                                    CopyableText(res.stderr, color = terminalRed, style = outputStyle)
                                }
                            }
                        }
                    } else {
                        step.commands.forEach { cmd ->
                            CopyableText(cmd, color = terminalGreen, style = terminalStyle)
                        }
                    }

                    // Clickable container output files (`cfile_*`)
                    if (step.files.isNotEmpty() && !step.containerId.isNullOrBlank()) {
                        HorizontalDivider(thickness = StudioHairlineWidth, color = StudioTerminalColors.divider)
                        Text(
                            text = "Created or updated files",
                            color = terminalMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(step.files) { fileRef ->
                                AssistChip(
                                    onClick = { onFileClick(step.containerId, fileRef) },
                                    label = {
                                        Text(
                                            text = fileRef.path,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = terminalText
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
