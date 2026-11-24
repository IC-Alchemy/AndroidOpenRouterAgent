package com.example.openrouteragent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.io.File

/** Where a new attachment comes from. */
internal enum class AttachSource { Photos, Camera, Files }

@Composable
internal fun AgentInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isRunning: Boolean,
    isLoading: Boolean,
    error: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    chatMode: Boolean? = null,
    onChatMode: (Boolean) -> Unit = {},
    attachments: List<ChatMedia> = emptyList(),
    mediaFile: (ChatMedia) -> File? = { null },
    onOpenAttachment: (ChatMedia) -> Unit = {},
    onRemoveAttachment: (ChatMedia) -> Unit = {},
    onAttach: ((AttachSource) -> Unit)? = null,
    /** False when the chosen model can't read images, so photo choices are off. */
    canAttachImages: Boolean = true,
    importing: Boolean = false,
) {
    val showLabels = LocalControlLabels.current.visible
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider(thickness = StudioHairlineWidth, color = MaterialTheme.colorScheme.outlineVariant)
            if (chatMode != null) {
                Row(
                    Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Agent mode can use tools over several steps. Chat mode only answers, which is cheaper and faster.
                    listOf(false to "Agent", true to "Chat").forEach { (chat, label) ->
                        val icon = if (chat) StudioSymbols.Chat else StudioSymbols.Agent
                        ControlTooltip(label, enabled = !showLabels) {
                            FilterChip(
                                selected = chatMode == chat,
                                onClick = { onChatMode(chat) },
                                enabled = !isLoading && !isRunning,
                                label = { SymbolLabel(label, icon) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    if (showLabels) {
                        Text(
                            if (chatMode) "No tools · faster, cheaper" else "Tools on",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    ControlLabelsToggle()
                }
            }
            if (error != null) {
                CopyableText(
                    error,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (importing) LinearProgressIndicator(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 16.dp))
            if (attachments.isNotEmpty()) {
                MediaStrip(attachments, mediaFile, onOpenAttachment,
                    Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    size = 56.dp, onRemove = if (isRunning) null else onRemoveAttachment)
            }
            Row(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onAttach != null && !isRunning) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        ControlTooltip("Attach") {
                            IconButton(onClick = { menu = true }, enabled = !isLoading && !importing,
                                modifier = Modifier.size(48.dp)) {
                                Icon(StudioSymbols.Attach, contentDescription = "Attach a photo or file")
                            }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (canAttachImages) "Photos" else "Photos (this model can't read images)") },
                                leadingIcon = { Icon(StudioSymbols.Photo, null) },
                                enabled = canAttachImages,
                                onClick = { menu = false; onAttach(AttachSource.Photos) },
                            )
                            DropdownMenuItem(
                                text = { Text("Take a photo") },
                                leadingIcon = { Icon(StudioSymbols.Camera, null) },
                                enabled = canAttachImages,
                                onClick = { menu = false; onAttach(AttachSource.Camera) },
                            )
                            DropdownMenuItem(
                                text = { Text("PDF, audio or text file") },
                                leadingIcon = { Icon(StudioSymbols.File, null) },
                                onClick = { menu = false; onAttach(AttachSource.Files) },
                            )
                        }
                    }
                }
                CopyableOutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(if (chatMode == true) "Message" else "Message the agent") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isRunning && !isLoading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                if (!isRunning) {
                    ControlTooltip("Speak a message") {
                        IconButton(onClick = onVoice, enabled = !isLoading, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_mic), contentDescription = "Speak a message")
                        }
                    }
                }
                val action = if (isRunning) "Stop waiting" else "Send prompt"
                ControlTooltip(action) {
                    FilledIconButton(
                        onClick = if (isRunning) onStop else onSend,
                        enabled = isRunning || (!isLoading && !importing && (value.isNotBlank() || attachments.isNotEmpty())),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(if (isRunning) StudioSymbols.Stop else Icons.AutoMirrored.Filled.Send, action)
                    }
                }
            }
        }
    }
}
