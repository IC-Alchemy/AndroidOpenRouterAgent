package com.example.openrouteragent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    providerName: String = "OpenRouter",
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
        title = { Text("$providerName models") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search models") },
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
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                if (!loading && error == null && visible.isEmpty()) {
                    Text(if (query.isNotBlank()) "No matches." else "$providerName returned no eligible models.")
                }
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(visible, key = { it.id }) { model ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(model) }.padding(vertical = 9.dp)) {
                            Text(model.name, style = MaterialTheme.typography.bodyMedium,
                                color = if (selected?.id == model.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                            Text(model.id, style = MaterialTheme.typography.bodySmall)
                            if (model.contextLength > 0) {
                                val count = NumberFormat.getIntegerInstance().format(model.contextLength)
                                Text("$count context tokens", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") } },
    )
}
