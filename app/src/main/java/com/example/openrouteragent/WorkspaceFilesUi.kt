package com.example.openrouteragent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun WorkspaceFilesDialog(
    client: OpenRouterAgentClient,
    apiKey: String,
    prefix: String,
    onDismiss: () -> Unit,
    onCopyToProject: suspend (WorkspaceFileRef) -> String,
    isAttached: (String) -> Boolean,
    onToggleAttachment: ((WorkspaceFileRef) -> Unit)? = null,
    localPreview: (suspend (WorkspaceFileRef) -> FilePreview?)? = null,
    localExport: (suspend (WorkspaceFileRef, Uri) -> Boolean)? = null,
    onDeleted: (WorkspaceFileRef) -> Unit = {},
    onSaveRevision: (suspend (WorkspaceFileRef, String) -> WorkspaceFileRef)? = null
) {
    val scopePrefix = prefix.trim('/')
    var directory by rememberSaveable(scopePrefix) { mutableStateOf("") }
    var selectedId by rememberSaveable(scopePrefix) { mutableStateOf<String?>(null) }
    var pendingExportId by rememberSaveable(scopePrefix) { mutableStateOf<String?>(null) }
    var refresh by remember(scopePrefix) { mutableIntStateOf(0) }
    var pageRequest by remember(apiKey, refresh) { mutableIntStateOf(0) }
    var files by remember(apiKey, refresh) { mutableStateOf(emptyList<WorkspaceFileRef>()) }
    var cursor by remember(apiKey, refresh) { mutableStateOf<String?>(null) }
    var seenCursors by remember(apiKey, refresh) { mutableStateOf(emptySet<String>()) }
    var deletedIds by remember(apiKey) { mutableStateOf(emptySet<String>()) }
    var loadingPage by remember(apiKey, refresh) { mutableStateOf(true) }
    var pageError by remember(apiKey, refresh) { mutableStateOf<String?>(null) }
    var restoredFile by remember(apiKey, selectedId) { mutableStateOf<WorkspaceFileRef?>(null) }
    var detailError by remember(apiKey, selectedId) { mutableStateOf<String?>(null) }
    var detailRequest by remember(apiKey, selectedId) { mutableIntStateOf(0) }
    var confirmDelete by remember(selectedId) { mutableStateOf(false) }
    var originalText by rememberSaveable(scopePrefix, selectedId) { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(scopePrefix, selectedId) { mutableStateOf("") }
    var rejectedLargeEdit by rememberSaveable(scopePrefix, selectedId) { mutableStateOf(false) }
    var confirmDiscard by remember(selectedId) { mutableStateOf(false) }
    val operation = remember(apiKey) { FileDialogOperation(apiKey) }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver

    LaunchedEffect(client, apiKey, refresh, pageRequest) {
        loadingPage = true
        pageError = null
        try {
            val page = client.listWorkspaceFiles(apiKey, cursor)
            files = (files + page.files).filterNot { it.id in deletedIds }.distinctBy { it.id }
            cursor?.let { seenCursors = seenCursors + it }
            cursor = page.cursor?.takeUnless { it in seenCursors }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            pageError = failure.userMessage(apiKey)
        } finally {
            loadingPage = false
        }
    }

    val listedFile = files.firstOrNull { it.id == selectedId }
    LaunchedEffect(client, apiKey, selectedId, scopePrefix, listedFile, detailRequest) {
        val id = selectedId ?: return@LaunchedEffect
        if (listedFile != null) return@LaunchedEffect
        detailError = null
        try {
            val file = client.workspaceFile(apiKey, id)
            require(relativeWorkspacePath(file.filename, scopePrefix) != null) { "This file is outside this project's uploads." }
            restoredFile = file
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            detailError = failure.userMessage(apiKey)
        }
    }

    val selected = listedFile ?: restoredFile
    var preview by remember(apiKey, selected) { mutableStateOf<FilePreview?>(null) }
    var previewError by remember(apiKey, selected) { mutableStateOf<String?>(null) }
    var previewRequest by remember(apiKey, selected) { mutableIntStateOf(0) }
    var checkingLocal by remember(apiKey, selected) { mutableStateOf(selected?.downloadable == false && localPreview != null) }
    LaunchedEffect(client, apiKey, selected, previewRequest) {
        val file = selected ?: return@LaunchedEffect
        previewError = null
        checkingLocal = !file.downloadable && localPreview != null
        try {
            preview = if (file.downloadable) client.previewWorkspaceFile(apiKey, file.id) else localPreview?.invoke(file)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            previewError = failure.userMessage(apiKey)
        } finally {
            checkingLocal = false
        }
    }

    val mime = selected?.mimeType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') }
        ?: fileMimeType(selected?.filename)
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) operation.run(scope, "Exporting file…") {
            val file = selected?.takeIf { it.id == id } ?: client.workspaceFile(apiKey, id)
            if (file.downloadable) {
                client.downloadWorkspaceFile(apiKey, file.id) {
                    resolver.openOutputStream(uri, "wt") ?: error("Android could not open the export destination.")
                }
            } else {
                check(localExport?.invoke(file, uri) == true) { "The copy on this device is no longer available." }
            }
            "Exported ${fileName(file.filename)}."
        }
    }

    fun navigate(to: String) {
        directory = to
        operation.clear()
    }
    fun loadNextPage() {
        if (loadingPage) return
        loadingPage = true
        pageRequest++
    }
    fun closeRevisionEditor() {
        originalText = null
        draft = ""
        rejectedLargeEdit = false
        confirmDiscard = false
        operation.clear()
    }
    fun back() {
        if (operation.busy != null) return
        when {
            originalText != null -> {
                if (draft != originalText) confirmDiscard = true else closeRevisionEditor()
            }
            selectedId != null -> {
                selectedId = null
                operation.clear()
            }
            directory.isNotEmpty() -> navigate(directory.substringBeforeLast('/', ""))
            else -> onDismiss()
        }
    }

    val working = operation.busy != null
    FileBrowserDialog(
        title = selected?.filename?.let { filename ->
            if (originalText == null) fileName(filename) else "Edit ${fileName(filename)}"
        } ?: "OpenRouter Workspace files",
        onBack = ::back,
        nested = selectedId != null || directory.isNotEmpty(),
        busy = working
    ) {
        FileDialogStatus(operation)
        when {
            selected != null && originalText != null && onSaveRevision != null -> {
                FileDetailText("Saving creates a new file in Workspace and keeps the original.")
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { text ->
                            rejectedLargeEdit = text.toByteArray(Charsets.UTF_8).size > FILE_PREVIEW_LIMIT
                            if (!rejectedLargeEdit) draft = text
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Text") },
                        minLines = 8,
                        maxLines = 16,
                        enabled = !working,
                        isError = rejectedLargeEdit,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        supportingText = when {
                            rejectedLargeEdit -> ({ Text("That change was too large. Text files can be up to 64 KiB.") })
                            draft.isEmpty() -> ({ Text("Enter text to create a revision.") })
                            else -> null
                        }
                    )
                    Button(
                        enabled = !working && draft.isNotEmpty() && draft != originalText,
                        modifier = Modifier.align(Alignment.End),
                        onClick = {
                            val updated = draft
                            operation.run(scope, "Saving revision…") {
                                val revision = onSaveRevision(selected, updated)
                                files = listOf(revision) + files.filterNot { it.id == revision.id }
                                closeRevisionEditor()
                                selectedId = revision.id
                                "Saved ${fileName(revision.filename)} as a new file."
                            }
                        }
                    ) { Text("Save revision") }
                }
            }
            selected != null -> {
                val availableOnDevice = !selected.downloadable && preview != null
                val canCopy = selected.downloadable || availableOnDevice
                val canExport = selected.downloadable || (availableOnDevice && localExport != null)
                val editableText = preview?.takeUnless { it.truncated }?.text
                    ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= FILE_PREVIEW_LIMIT }
                WorkspaceFileDetails(selected, isAttached(selected.id), availableOnDevice)
                FileActionRow {
                    if (onSaveRevision != null && editableText != null) TextButton(enabled = !working, onClick = {
                        operation.clear()
                        originalText = editableText
                        draft = editableText
                    }) { Text("Edit text") }
                    if (canCopy) Button(enabled = !working, onClick = {
                        operation.run(scope, "Copying file…") {
                            val path = onCopyToProject(selected)
                            "Copied to $path."
                        }
                    }) { Text("Copy to device files") }
                    if (canExport) TextButton(enabled = !working, onClick = {
                        pendingExportId = selected.id
                        exportDocument.launch(fileName(selected.filename))
                    }) { Text("Export") }
                    if (onToggleAttachment != null) {
                        val attached = isAttached(selected.id)
                        TextButton(enabled = !working, onClick = {
                            operation.run(scope, if (attached) "Detaching file…" else "Attaching file…") {
                                onToggleAttachment(selected)
                                if (attached) "File detached." else "File attached to this chat."
                            }
                        }) { Text(if (attached) "Detach from chat" else "Attach to chat") }
                    }
                    TextButton(enabled = !working, onClick = { confirmDelete = true }) {
                        Text("Delete", color = if (working) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
                if (selected.downloadable || checkingLocal || preview != null || previewError != null) {
                    BrowserFilePreview(preview, previewError, { previewRequest++ },
                        "Export this file to open it in another app.")
                } else {
                    FileDetailText("OpenRouter does not offer a download for this file.")
                }
            }
            selectedId != null -> {
                val error = detailError
                if (error == null) FileLoading("Loading file…") else FileLoadError(error) { detailRequest++ }
            }
            else -> {
                val rootLabel = if (scopePrefix.isEmpty()) "Workspace" else "Project uploads"
                val base = joinFilePath(scopePrefix, directory).trim('/')
                val visible = remember(files, base) {
                    files.mapNotNull { file -> relativeWorkspacePath(file.filename, base)?.let { it to file } }
                }
                val folders = remember(visible) {
                    visible.mapNotNull { (path, _) -> path.substringBefore('/', "").takeIf(String::isNotEmpty) }
                        .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                }
                val visibleFiles = remember(visible) {
                    visible.filter { (path, _) -> '/' !in path }
                        .sortedWith(compareBy({ it.first.lowercase() }, { it.first }, { it.second.id }))
                }
                if (scopePrefix.isNotEmpty() && directory.isEmpty()) FileDetailText("Uploads for this project")
                FileBreadcrumbs(directory, rootLabel, !working, ::navigate)
                FileActionRow {
                    TextButton(enabled = !working && !loadingPage, onClick = {
                        operation.clear()
                        refresh++
                    }) { Text("Refresh") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(folders, key = { "folder/$it" }) { folder ->
                        FileBrowserRow(folder, "Folder", enabled = !working) {
                            navigate(joinFilePath(directory, folder))
                        }
                    }
                    items(visibleFiles, key = { (_, file) -> "file/${file.id}" }) { (name, file) ->
                        val detail = listOfNotNull(
                            fileSizeLabel(file.sizeBytes),
                            file.createdAt?.substringBefore('T')?.takeIf(String::isNotEmpty),
                            "Attached to this chat".takeIf { isAttached(file.id) }
                        ).joinToString(" · ").ifEmpty { null }
                        FileBrowserRow(name, detail, enabled = !working) {
                            operation.clear()
                            selectedId = file.id
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            when {
                                loadingPage -> FileLoading("Loading Workspace files…")
                                pageError != null -> FileLoadError(pageError.orEmpty(), ::loadNextPage)
                                folders.isEmpty() && visibleFiles.isEmpty() -> FileDetailText(
                                    if (cursor == null) "This folder is empty." else "Load more to find files in this folder."
                                )
                            }
                            if (!loadingPage && pageError == null && cursor != null) {
                                TextButton(enabled = !working, onClick = ::loadNextPage) { Text("Load more") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard unsaved changes?") },
        confirmButton = { TextButton(onClick = ::closeRevisionEditor) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } }
    )
    if (confirmDelete && selected != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete Workspace file?") },
        text = { Text("${selected.filename} will be removed from OpenRouter.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                operation.run(scope, "Deleting file…") {
                    client.deleteWorkspaceFile(apiKey, selected.id)
                    deletedIds = deletedIds + selected.id
                    files = files.filterNot { it.id == selected.id }
                    selectedId = null
                    onDeleted(selected)
                    "Deleted ${fileName(selected.filename)} from OpenRouter."
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}

private fun relativeWorkspacePath(filename: String, base: String): String? {
    val path = filename.trim('/')
    if (base.isEmpty()) return path.takeIf(String::isNotEmpty)
    return path.takeIf { it.startsWith("$base/") }?.removePrefix("$base/")?.takeIf(String::isNotEmpty)
}

@Composable
private fun WorkspaceFileDetails(file: WorkspaceFileRef, attached: Boolean, fromDevice: Boolean) {
    FileDetailText(file.filename)
    val detail = listOfNotNull(fileSizeLabel(file.sizeBytes), file.mimeType,
        "Attached to this chat".takeIf { attached }, "Device copy".takeIf { fromDevice })
    if (detail.isNotEmpty()) FileDetailText(detail.joinToString(" · "))
}
