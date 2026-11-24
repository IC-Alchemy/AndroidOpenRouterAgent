package com.example.openrouteragent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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

@OptIn(ExperimentalLayoutApi::class)
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
    var visionOnly by rememberSaveable { mutableStateOf(false) }
    var imagesOnly by rememberSaveable { mutableStateOf(false) }
    val visible = models.filter { model ->
        (!requireTools || model.supportsTools) && (!visionOnly || model.readsImages) &&
            (!imagesOnly || model.makesImages) &&
            (model.name.contains(query.trim(), ignoreCase = true) || model.id.contains(query.trim(), ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenRouter models") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CopyableOutlinedTextField(query, { query = it }, label = { Text("Search models") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(visionOnly, { visionOnly = !visionOnly }, label = { Text("Reads images") })
                    FilterChip(imagesOnly, { imagesOnly = !imagesOnly }, label = { Text("Makes images") })
                }
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
                            val details = listOfNotNull(
                                model.contextLength.takeIf { it > 0 }?.let {
                                    NumberFormat.getIntegerInstance().format(it) + " context tokens"
                                },
                                priceLabel(model.pricing),
                            )
                            if (details.isNotEmpty()) {
                                Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            }
                            val labels = capabilityLabels(model)
                            if (labels.isNotEmpty()) {
                                Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            retirementLabel(model)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
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
