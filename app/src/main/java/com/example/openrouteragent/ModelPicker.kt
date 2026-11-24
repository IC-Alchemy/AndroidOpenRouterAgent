package com.example.openrouteragent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

@Composable
internal fun ModelPicker(
    models: List<CatalogModel>,
    selected: CatalogModel?,
    requireTools: Boolean,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    allowManual: Boolean = false,
    onSelect: (CatalogModel) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = models.filter { model ->
        (!requireTools || model.supportsTools) &&
            (model.name.contains(query.trim(), ignoreCase = true) || model.id.contains(query.trim(), ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenRouter models") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CopyableOutlinedTextField(query, { query = it }, label = { Text("Search models") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (requireTools) {
                    Text("Showing models that support your enabled tools.", style = MaterialTheme.typography.bodySmall)
                }
                val typed = query.trim()
                if (allowManual && typed.length in 1..256 && typed.none(Char::isWhitespace) &&
                    models.none { it.id == typed }) {
                    TextButton(onClick = { onSelect(CatalogModel(typed, typed, 0, emptySet())) }) {
                        Text("Use as a model ID")
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) CopyableText(error, color = MaterialTheme.colorScheme.error)
                if (!loading && error == null && visible.isEmpty()) {
                    Text(if (query.isNotBlank()) "No matches." else "OpenRouter returned no eligible models.")
                }
                LazyColumn(Modifier.heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { model ->
                        val current = selected?.id == model.id
                        OutlinedSection(highlighted = current) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClickLabel = "Select model") { onSelect(model) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(model.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (current) Icon(Icons.Default.Check, "Selected model")
                            }
                            CopyableText(model.id, style = MaterialTheme.typography.bodySmall)
                            if (model.contextLength > 0) {
                                val count = NumberFormat.getIntegerInstance().format(model.contextLength)
                                Text("$count context tokens", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { SymbolAction("Done", Icons.Default.Check, onDismiss) },
        dismissButton = { SymbolAction("Refresh", Icons.Default.Refresh, onRefresh, enabled = !loading) },
    )
}
