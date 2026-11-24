package com.example.openrouteragent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The built-in app guide on its own page, opened from the Configure menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppGuideSheet(guide: SavedAgentSkill?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    var message by remember { mutableStateOf<String?>(null) }
    val exportGuide = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val payload = guide?.markdown()
        if (uri != null && payload != null) scope.launch {
            message = try {
                resolver.writeCreatedDocument(uri) {
                    resolver.openDocumentOutput(uri).bufferedWriter().use { it.write(payload) }
                }
                "Guide exported."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message?.take(250) ?: "Could not export the guide."
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App guide", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text("Setup, controls, and customization reference", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (guide == null) {
                    Text("The app guide couldn't be loaded.")
                } else {
                    MarkdownText(guide.body)
                    OutlinedButton(onClick = { exportGuide.launch("SKILL.md") }) { Text("Export guide") }
                }
            }
        }
    }
}
