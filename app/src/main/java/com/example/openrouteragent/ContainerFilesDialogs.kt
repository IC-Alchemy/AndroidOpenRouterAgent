package com.example.openrouteragent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun ContainerFilesDialog(
    client: OpenRouterAgentClient,
    apiKey: String,
    containerId: String,
    onDismiss: () -> Unit,
    onFile: (ContainerFileRef) -> Unit
) {
    var files by remember(containerId) { mutableStateOf(emptyList<ContainerFileRef>()) }
    var next by remember(containerId) { mutableStateOf<String?>(null) }
    var request by remember(containerId) { mutableIntStateOf(0) }
    var loaded by remember(containerId) { mutableStateOf(false) }
    var loading by remember(containerId) { mutableStateOf(true) }
    var error by remember(containerId) { mutableStateOf<String?>(null) }

    LaunchedEffect(containerId, request) {
        loading = true
        error = null
        try {
            val page = client.listFiles(apiKey, containerId, next)
            files = (files + page.files).distinctBy { it.fileId }
            next = page.after
            loaded = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.userMessage(apiKey)
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sandbox files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (files.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(files, key = { it.fileId }) { file ->
                            Column(Modifier.fillMaxWidth().clickable { onFile(file) }.padding(vertical = 12.dp)) {
                                Text(file.path, style = MaterialTheme.typography.bodyMedium)
                                file.bytes?.let { Text("$it bytes", style = MaterialTheme.typography.bodySmall) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                when {
                    loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    loaded && files.isEmpty() && next == null -> Text("No files in this sandbox.")
                }
                if (!loading && (next != null || error != null)) {
                    TextButton(onClick = { loading = true; request++ }) {
                        Text(if (error != null) "Retry" else "Load more")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
internal fun ContainerFilePreviewDialog(
    client: OpenRouterAgentClient,
    apiKey: String,
    containerId: String,
    fileId: String,
    title: String,
    isExporting: Boolean,
    onDismiss: () -> Unit,
    onExport: () -> Unit
) {
    var preview by remember(containerId, fileId) { mutableStateOf<FilePreview?>(null) }
    var error by remember(containerId, fileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(containerId, fileId, apiKey) {
        try {
            preview = client.previewFile(apiKey, containerId, fileId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.userMessage(apiKey)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        title = { Text(title, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val result = preview
                when {
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    result == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    result.text == null -> Text("Download this file to open it in another app.")
                    result.text.isEmpty() -> Text("Empty file.")
                    else -> OutlinedSection {
                        CopyableText(
                            result.text,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                if (result?.truncated == true && result.text != null) Text("Showing the first 64 KB.")
            }
        },
        confirmButton = {
            TextButton(enabled = !isExporting, onClick = onExport) {
                Text(if (isExporting) "Saving…" else "Save file")
            }
        },
        dismissButton = { TextButton(enabled = !isExporting, onClick = onDismiss) { Text("Close") } }
    )
}
