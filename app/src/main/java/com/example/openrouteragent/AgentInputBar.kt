package com.example.openrouteragent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.io.File

/** Where a new attachment comes from. */
internal enum class AttachSource { Photos, Camera, Files }

@OptIn(ExperimentalLayoutApi::class)
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
    /** Text shown in the empty field. Null picks one from [chatMode]. */
    placeholder: String? = null,
    /** Opens the side conversation from a small button beside the field; null hides the button. */
    onSideConversation: (() -> Unit)? = null,
    /** Shows a dot on the side conversation button: a side reply is running or unread. */
    sideConversationBusy: Boolean = false,
    /**
     * Keeps the field editable while [isRunning], so a slash command such as /btw can be typed during a run. The send
     * button and the keyboard's send key only act then when [sendWhileRunning] is true; otherwise the button stops.
     */
    acceptsInputWhileRunning: Boolean = false,
    sendWhileRunning: Boolean = false,
) {
    val showLabels = LocalControlLabels.current.visible
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider(thickness = StudioHairlineWidth, color = MaterialTheme.colorScheme.outlineVariant)
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
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val sends = !isRunning || sendWhileRunning
                CopyableOutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(placeholder ?: if (chatMode == false) "Message the agent" else "Message") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    enabled = (!isRunning || acceptsInputWhileRunning) && !isLoading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (sends) onSend() }),
                )
                val action = if (sends) "Send prompt" else "Stop waiting"
                ControlTooltip(action) {
                    FilledIconButton(
                        onClick = if (sends) onSend else onStop,
                        enabled = !sends || (!isLoading && !importing &&
                            (value.isNotBlank() || (!isRunning && attachments.isNotEmpty()))),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(if (sends) Icons.AutoMirrored.Filled.Send else StudioSymbols.Stop, action)
                    }
                }
            }
            FlowRow(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (chatMode != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                    }
                }
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
                if (onSideConversation != null) {
                    // Stays usable while the main chat runs: the side conversation is separate from it.
                    ControlTooltip("Side conversation") {
                        IconButton(onClick = onSideConversation, enabled = !isLoading, modifier = Modifier.size(48.dp)) {
                            Box {
                                Icon(StudioSymbols.SideConversation, contentDescription = "Side conversation",
                                    modifier = Modifier.size(20.dp))
                                if (sideConversationBusy) {
                                    StudioStatusDot(color = MaterialTheme.colorScheme.primary, size = 6.dp,
                                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
                                }
                            }
                        }
                    }
                }
                if (!isRunning) {
                    ControlTooltip("Speak a message") {
                        IconButton(onClick = onVoice, enabled = !isLoading, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_mic), contentDescription = "Speak a message")
                        }
                    }
                }
            }
        }
    }
}
