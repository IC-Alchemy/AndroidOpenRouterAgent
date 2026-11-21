package com.example.openrouteragent

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val ALL_CHATS = "all"
private const val UNFILED_CHATS = "unfiled"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatLibrarySheet(agent: AgentViewModel, onClose: () -> Unit) {
    val library = agent.library
    val busy = agent.isWorkingChat
    var filter by rememberSaveable { mutableStateOf(agent.chatProject?.id ?: ALL_CHATS) }
    var query by rememberSaveable { mutableStateOf("") }
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var move by remember { mutableStateOf<SavedChat?>(null) }
    var delete by remember { mutableStateOf<DeleteDialog?>(null) }
    var projectsMenu by remember { mutableStateOf(false) }
    val project = library.projects.firstOrNull { it.id == filter }
    val names = library.projects.associate { it.id to it.name }
    val results = library.chats.filter { chat ->
        val included = filter == ALL_CHATS || (filter == UNFILED_CHATS && chat.projectId == null) || chat.projectId == filter
        included && (chat.title.ifBlank { "New chat" }.contains(query.trim(), ignoreCase = true))
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.90f).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chats", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text(agent.providerName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onClose, enabled = !busy) { Text("Close") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { agent.newConversation(project?.id); onClose() }) { Text("New chat") }
                TextButton(enabled = !busy, onClick = { nameDialog = NameDialog("New project", "", agent::addProject) }) {
                    Text("New project")
                }
            }
            ChoiceStrip(listOf(ALL_CHATS to "All chats", UNFILED_CHATS to "Unfiled") +
                library.projects.map { it.id to it.name }, filter) { filter = it }
            if (project != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(project.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(enabled = !busy, onClick = { projectsMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Manage project")
                    }
                    DropdownMenu(expanded = projectsMenu, onDismissRequest = { projectsMenu = false }) {
                        DropdownMenuItem(text = { Text("Rename project") }, onClick = {
                            projectsMenu = false
                            nameDialog = NameDialog("Rename project", project.name) { agent.renameProject(project.id, it) }
                        })
                        DropdownMenuItem(text = { Text("Remove project") }, onClick = {
                            projectsMenu = false
                            delete = DeleteDialog("Remove project?", "Its chats will move to Unfiled.") {
                                filter = ALL_CHATS
                                agent.deleteProject(project.id)
                            }
                        })
                    }
                }
            }
            EditField("Search chat titles", query, { query = it })
            agent.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (results.isEmpty()) {
                Text(if (query.isBlank()) "No chats here yet." else "No matching chats.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id }) { chat ->
                    val current = chat.id == agent.workspace.id
                    ChatLibraryRow(chat, current, names[chat.projectId], busy,
                        onOpen = { agent.openChat(chat.id); onClose() },
                        onRename = { nameDialog = NameDialog("Rename chat", chat.title) { agent.renameChat(chat.id, it) } },
                        onMove = { move = chat },
                        onDelete = {
                            delete = DeleteDialog("Delete chat?", "This permanently removes the local chat and its saved agent history.") {
                                agent.deleteChat(chat.id)
                            }
                        })
                }
            }
        }
    }
    nameDialog?.let { request ->
        var name by remember(request) { mutableStateOf(request.initial) }
        var error by remember(request) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { nameDialog = null }, title = { Text(request.title) }, text = {
            Column {
                EditField("Name", name, { name = it; error = null })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val valid = runCatching { libraryName(name) }
                valid.onSuccess { request.save(it); nameDialog = null }.onFailure { error = it.message }
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } })
    }
    move?.let { chat ->
        var target by remember(chat) { mutableStateOf(chat.projectId ?: UNFILED_CHATS) }
        AlertDialog(onDismissRequest = { move = null }, title = { Text("Move chat") }, text = {
            ChoiceStrip(listOf(UNFILED_CHATS to "Unfiled") + library.projects.map { it.id to it.name }, target) {
                target = it
            }
        }, confirmButton = {
            TextButton(onClick = { agent.moveChat(chat.id, target.takeUnless { it == UNFILED_CHATS }); move = null }) {
                Text("Move")
            }
        }, dismissButton = { TextButton(onClick = { move = null }) { Text("Cancel") } })
    }
    delete?.let { request ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text(request.title) },
            text = { Text(request.detail) }, confirmButton = {
                TextButton(onClick = { request.remove(); delete = null }) { Text("Remove") }
            }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ChatLibraryRow(chat: SavedChat, current: Boolean, project: String?, busy: Boolean,
    onOpen: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    OutlinedSection(highlighted = current) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(enabled = !busy, role = Role.Button, onClick = onOpen),
                verticalArrangement = Arrangement.Center) {
                Text(chat.title.ifBlank { "New chat" }, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val date = DateUtils.getRelativeTimeSpanString(chat.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                val detail = listOfNotNull("Open".takeIf { current }, project, date).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = !busy, onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Manage chat")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Move to project") }, onClick = { menu = false; onMove() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private data class NameDialog(val title: String, val initial: String, val save: (String) -> Unit)
private data class DeleteDialog(val title: String, val detail: String, val remove: () -> Unit)
