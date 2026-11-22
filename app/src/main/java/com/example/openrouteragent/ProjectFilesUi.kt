package com.example.openrouteragent

import android.net.Uri
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class ProjectEditor { Folder, NewText, EditText }

@Composable
internal fun ProjectFilesDialog(
    title: String,
    onDismiss: () -> Unit,
    list: suspend (directory: String) -> List<ProjectFileEntry>,
    createFolder: suspend (path: String) -> Unit,
    preview: suspend (path: String) -> FilePreview,
    writeText: suspend (path: String, text: String, previousText: String?) -> Unit,
    importFile: suspend (uri: Uri, directory: String) -> String,
    exportFile: suspend (path: String, uri: Uri) -> Unit,
    upload: (suspend (path: String) -> WorkspaceFileRef)? = null
) {
    var directory by rememberSaveable { mutableStateOf("") }
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImportDirectory by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var entries by remember(directory, revision) { mutableStateOf<List<ProjectFileEntry>?>(null) }
    var listError by remember(directory, revision) { mutableStateOf<String?>(null) }
    var filePreview by remember(selectedPath, revision) { mutableStateOf<FilePreview?>(null) }
    var previewError by remember(selectedPath, revision) { mutableStateOf<String?>(null) }
    var retryPreview by remember(selectedPath) { mutableIntStateOf(0) }
    var editor by rememberSaveable { mutableStateOf<ProjectEditor?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var originalText by rememberSaveable { mutableStateOf<String?>(null) }
    var rejectedLargeEdit by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val operation = remember { FileDialogOperation() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(directory, revision) {
        try {
            entries = list(directory)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            listError = failure.userMessage("")
        }
    }
    LaunchedEffect(selectedPath, revision, retryPreview) {
        val path = selectedPath ?: return@LaunchedEffect
        previewError = null
        try {
            filePreview = preview(path)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            previewError = failure.userMessage("")
        }
    }

    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val destination = pendingImportDirectory
        pendingImportDirectory = null
        if (uri != null && destination != null) operation.run(scope, "Importing file…") {
            val path = importFile(uri, destination)
            revision++
            "Imported ${fileName(path)}."
        }
    }
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(fileMimeType(selectedPath))) { uri ->
        val path = pendingExportPath
        pendingExportPath = null
        if (uri != null && path != null) operation.run(scope, "Exporting file…") {
            exportFile(path, uri)
            "Exported ${fileName(path)}."
        }
    }

    fun navigate(to: String) {
        directory = to
        operation.clear()
    }
    fun closeEditor() {
        editor = null
        name = ""
        content = ""
        originalText = null
        rejectedLargeEdit = false
        confirmDiscard = false
        operation.clear()
    }
    fun back() {
        if (operation.busy != null) return
        when {
            editor != null -> {
                if (name.isNotBlank() || content != originalText.orEmpty()) confirmDiscard = true else closeEditor()
            }
            selectedPath != null -> {
                selectedPath = null
                operation.clear()
            }
            directory.isNotEmpty() -> navigate(directory.substringBeforeLast('/', ""))
            else -> onDismiss()
        }
    }

    val editMode = editor
    val path = selectedPath
    val working = operation.busy != null
    FileBrowserDialog(
        title = when (editMode) {
            ProjectEditor.Folder -> "New folder"
            ProjectEditor.NewText -> "New text file"
            ProjectEditor.EditText -> "Edit ${fileName(path.orEmpty())}"
            null -> path?.let(::fileName) ?: title
        },
        onBack = ::back,
        nested = editMode != null || path != null || directory.isNotEmpty(),
        busy = working
    ) {
        FileDialogStatus(operation)
        when {
            editMode != null -> {
                val filename = name.trim()
                val destination = path ?: joinFilePath(directory, filename)
                val nameError = if (editMode == ProjectEditor.EditText || filename.isEmpty()) null
                    else if ('/' in filename) "Enter a name without a slash."
                    else workspaceFilePathError(destination)
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editMode != ProjectEditor.EditText) {
                        if (directory.isNotEmpty()) FileDetailText(directory)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (editMode == ProjectEditor.Folder) "Folder name" else "File name") },
                            singleLine = true,
                            enabled = !working,
                            isError = nameError != null,
                            supportingText = nameError?.let { { Text(it) } }
                        )
                    }
                    if (editMode != ProjectEditor.Folder) {
                        OutlinedTextField(
                            value = content,
                            onValueChange = { draft ->
                                rejectedLargeEdit = draft.toByteArray(Charsets.UTF_8).size > FILE_PREVIEW_LIMIT
                                if (!rejectedLargeEdit) content = draft
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Text") },
                            minLines = 8,
                            maxLines = 16,
                            enabled = !working,
                            isError = rejectedLargeEdit,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            supportingText = if (rejectedLargeEdit) {
                                { Text("That change was too large. Text files can be up to 64 KB.") }
                            } else null
                        )
                    }
                    Button(
                        onClick = {
                            operation.run(scope, "Saving…") {
                                if (editMode == ProjectEditor.Folder) createFolder(destination)
                                else writeText(destination, content, originalText)
                                closeEditor()
                                revision++
                                if (editMode == ProjectEditor.NewText) selectedPath = destination
                                if (editMode == ProjectEditor.Folder) "Created ${fileName(destination)}."
                                else "Saved ${fileName(destination)}."
                            }
                        },
                        enabled = !working && when (editMode) {
                            ProjectEditor.EditText -> path != null && originalText != null && content != originalText
                            else -> filename.isNotEmpty() && nameError == null
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text(if (editMode == ProjectEditor.Folder) "Create folder" else "Save file") }
                }
            }
            path != null -> {
                if (path.contains('/')) FileDetailText(path)
                val previewText = filePreview?.takeUnless { it.truncated }?.text
                FileActionRow {
                    if (previewText != null) TextButton(enabled = !working, onClick = {
                        operation.clear()
                        originalText = previewText
                        content = previewText
                        editor = ProjectEditor.EditText
                    }) { Text("Edit text") }
                    TextButton(enabled = !working, onClick = {
                        pendingExportPath = path
                        exportDocument.launch(fileName(path))
                    }) { Text("Export") }
                    if (upload != null) Button(enabled = !working, onClick = {
                        operation.run(scope, "Uploading to OpenRouter…") {
                            val file = upload(path)
                            "Uploaded ${file.filename} to OpenRouter."
                        }
                    }) { Text("Upload to OpenRouter") }
                }
                BrowserFilePreview(
                    result = filePreview,
                    error = previewError,
                    onRetry = { retryPreview++ },
                    binaryMessage = "Export this file to open it in another app."
                )
            }
            else -> {
                FileBreadcrumbs(directory, "Files", !working, ::navigate)
                if (upload != null && directory.isEmpty()) {
                    FileDetailText("Agent mode can send file names and text it reads in this folder to OpenRouter during a run.")
                }
                FileActionRow {
                    Button(enabled = !working, onClick = {
                        pendingImportDirectory = directory
                        importDocument.launch(arrayOf("*/*"))
                    }) { Text("Import file") }
                    TextButton(enabled = !working, onClick = {
                        operation.clear()
                        editor = ProjectEditor.Folder
                    }) { Text("New folder") }
                    TextButton(enabled = !working, onClick = {
                        operation.clear()
                        editor = ProjectEditor.NewText
                    }) { Text("New text file") }
                }
                val visibleEntries = entries
                when {
                    listError != null -> FileLoadError(listError.orEmpty()) { revision++ }
                    visibleEntries == null -> FileLoading("Loading files…")
                    visibleEntries.isEmpty() -> FileDetailText("This folder is empty.")
                    else -> LazyColumn(Modifier.weight(1f)) {
                        items(visibleEntries, key = { it.path }) { entry ->
                            FileBrowserRow(
                                fileName(entry.path),
                                if (entry.isDirectory) "Folder" else fileSizeLabel(entry.sizeBytes),
                                enabled = !working
                            ) {
                                if (entry.isDirectory) navigate(entry.path) else {
                                    operation.clear()
                                    selectedPath = entry.path
                                }
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
        confirmButton = { TextButton(onClick = ::closeEditor) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } }
    )
}

internal fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/')

internal fun fileMimeType(path: String?): String {
    val extension = path?.let(::fileName)?.substringAfterLast('.', "")?.lowercase()
    return extension?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension) ?: "application/octet-stream"
}

internal class FileDialogOperation(private val apiKey: String = "") {
    var busy by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun clear() {
        status = null
        error = null
    }

    fun run(scope: CoroutineScope, label: String, action: suspend () -> String) {
        if (busy != null) return
        clear()
        busy = label
        scope.launch {
            try {
                status = action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.userMessage(apiKey)
            } finally {
                busy = null
            }
        }
    }
}

@Composable
internal fun FileBrowserDialog(
    title: String,
    onBack: () -> Unit,
    nested: Boolean,
    busy: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = if (nested) 4.dp else 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (nested) IconButton(enabled = !busy, onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(title, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!nested) TextButton(enabled = !busy, onClick = onBack) { Text("Done") }
                }
                HorizontalDivider()
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
internal fun FileBreadcrumbs(path: String, rootLabel: String, enabled: Boolean, onNavigate: (String) -> Unit) {
    if (path.isEmpty()) return
    val names = remember(path, rootLabel) { listOf(rootLabel) + path.split('/') }
    val paths = remember(path) { path.split('/').runningFold("", ::joinFilePath) }
    val scroll = rememberScrollState()
    LaunchedEffect(path, scroll.maxValue) {
        if (scroll.maxValue != Int.MAX_VALUE) scroll.scrollTo(scroll.maxValue)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { index, name ->
            if (index == names.lastIndex) {
                Text(name, Modifier.widthIn(max = 220.dp).padding(horizontal = 8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            } else {
                TextButton(enabled = enabled, onClick = { onNavigate(paths[index]) }) {
                    Text(name, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileActionRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
internal fun FileBrowserRow(name: String, detail: String?, enabled: Boolean, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onOpen)
    )
    HorizontalDivider()
}

@Composable
internal fun fileSizeLabel(bytes: Long?): String? = bytes?.let { Formatter.formatShortFileSize(LocalContext.current, it) }

@Composable
internal fun FileDetailText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun FileDialogStatus(operation: FileDialogOperation) {
    val error = operation.error
    val status = operation.busy ?: operation.status
    when {
        error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        status != null -> Text(status, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
internal fun FileLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        FileDetailText(label)
    }
}

@Composable
internal fun FileLoadError(error: String, onRetry: () -> Unit) {
    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onRetry) { Text("Retry") }
}

@Composable
internal fun BrowserFilePreview(result: FilePreview?, error: String?, onRetry: () -> Unit, binaryMessage: String) {
    when {
        error != null -> FileLoadError(error, onRetry)
        result == null -> FileLoading("Loading preview…")
        result.text == null -> FileDetailText(binaryMessage)
        result.text.isEmpty() -> FileDetailText("Empty file.")
        else -> {
            if (result.truncated) FileDetailText("Showing the first 64 KB.")
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                SelectionContainer(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(result.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
