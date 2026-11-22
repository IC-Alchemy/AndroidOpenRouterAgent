package com.example.openrouteragent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lists the mini apps the agent built, with open, home screen, share and delete actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MiniAppsSheet(onDismiss: () -> Unit, onCreate: () -> Unit) {
    val context = LocalContext.current
    val store = remember { miniAppStore(context) }
    var apps by remember { mutableStateOf<List<MiniApp>?>(null) }
    var deleting by remember { mutableStateOf<MiniApp?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { apps = withContext(Dispatchers.IO) { runCatching { store.list() }.getOrDefault(emptyList()) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
            Text("Mini apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp))
            Text("Ask the agent for a small app, such as “make a habit tracker”. It builds one HTML file that runs " +
                "on its own, can go on your home screen, and can be shared as a file.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val list = apps
                when {
                    list == null -> Text("Loading…")
                    list.isEmpty() -> {
                        Text("No mini apps yet.")
                        FilledTonalButton(onClick = onCreate) { Text("Ask the agent to build one") }
                    }
                    else -> list.forEach { app ->
                        OutlinedSection(title = app.name) {
                            val details = listOfNotNull(app.description.ifBlank { null }, "Version ${app.version}",
                                if (app.allowNetwork) null else "No internet access")
                            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledTonalButton(onClick = { MiniAppActivity.open(context, app) }) { Text("Open") }
                                TextButton(onClick = {
                                    message = if (MiniAppActivity.pin(context, app)) "Confirm on your home screen to add ${app.name}."
                                        else "Your launcher does not support adding shortcuts."
                                }) { Text("Home screen") }
                                TextButton(onClick = { runCatching { MiniAppActivity.share(context, app) } }) { Text("Share") }
                                TextButton(onClick = { deleting = app }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { app ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${app.name}?") },
            text = { Text("The app and the data it saved are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(app.id)
                    runCatching { android.webkit.WebStorage.getInstance().deleteOrigin("https://${app.id}.miniapp.invalid") }
                    MiniAppActivity.unpin(context, app.id)
                    deleting = null
                    message = "Deleted ${app.name}."
                    refresh++
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
