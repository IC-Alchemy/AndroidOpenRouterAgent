package com.example.openrouteragent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject

@Composable
internal fun SubagentsSettings(
    json: String,
    enabled: Boolean,
    availableModels: List<CatalogModel>,
    catalogLoading: Boolean,
    catalogError: String?,
    onRefreshModels: () -> Unit,
    onChange: (String) -> Unit,
) {
    val tools = remember(json) { readHostedTools(json) }
    val workers = tools.orEmpty().withIndex().filter { it.value.isSubagent() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(enabled = enabled, onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide subagents" else "Subagents (${workers.size})")
        }
        if (expanded) {
            Text(
                "Let the main model delegate focused tasks to OpenRouter workers.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (tools == null) {
                Text(
                    "Fix Additional server tools (JSON) at the bottom of Settings → Tools to edit subagents.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                workers.forEach { (index, tool) ->
                    ListItem(
                        headlineContent = { Text(tool.subagentDisplayName()) },
                        supportingContent = { Text(tool.subagentModelLabel(availableModels)) },
                        trailingContent = { Text("Edit", color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { editingIndex = index },
                    )
                }
                OutlinedButton(enabled = enabled, onClick = { editingIndex = tools.size }) {
                    Text("Add subagent")
                }
            }
        }
    }

    val index = editingIndex ?: return
    if (tools == null || index !in 0..tools.size) return
    val worker = tools.getOrNull(index)
    if (worker != null && !worker.isSubagent()) return
    SubagentEditor(
        existing = worker,
        availableModels = availableModels,
        catalogLoading = catalogLoading,
        catalogError = catalogError,
        onRefreshModels = onRefreshModels,
        onDismiss = { editingIndex = null },
        onApply = { result ->
            val next = tools.withSubagentEdit(index, result)
            val updatedJson = writeHostedTools(next)
            hostedToolsError(updatedJson)?.let { throw IllegalArgumentException(it) }
            if (next != tools) onChange(updatedJson)
            editingIndex = null
        },
        onRemove = if (worker == null) null else {
            {
                onChange(writeHostedTools(tools.withSubagentEdit(index, null)))
                editingIndex = null
            }
        },
    )
}

@Composable
internal fun ColumnScope.SubagentEditorFields(
    name: String,
    onNameChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    instructions: String,
    onInstructionsChange: (String) -> Unit,
    advancedJson: String,
    onAdvancedJsonChange: (String) -> Unit,
    availableModels: List<CatalogModel>,
    catalogLoading: Boolean,
    catalogError: String?,
    onRefreshModels: () -> Unit,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
) {
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val trimmedModel = model.trim()
    val matchedModel = availableModels.firstOrNull { it.id == trimmedModel }

    EditField(
        label = "Name (optional)",
        value = name,
        onChange = onNameChange,
        hint = "Leave blank for the default subagent. Names must be unique.",
    )

    OutlinedSection(title = "Default model") {
        Text(
            text = when {
                trimmedModel.isEmpty() -> "Uses the main chat model"
                matchedModel != null && matchedModel.name != matchedModel.id ->
                    "${matchedModel.name} (${matchedModel.id})"
                else -> trimmedModel
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EditField(
            label = "Worker model ID (optional)",
            value = model,
            onChange = onModelChange,
            hint = "Leave blank to use the main chat model.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { showModelPicker = true }) {
                Text("Choose model")
            }
            if (model.isNotBlank()) {
                TextButton(onClick = { onModelChange("") }) {
                    Text("Use main model")
                }
            }
        }
    }

    EditField(
        label = "Worker instructions (optional)",
        value = instructions,
        onChange = onInstructionsChange,
        lines = 4,
    )

    TextButton(onClick = onToggleAdvanced) {
        Text(if (showAdvanced) "Hide advanced worker settings" else "Advanced worker settings")
    }
    if (showAdvanced) {
        EditField(
            label = "Worker settings (JSON)",
            value = advancedJson,
            onChange = onAdvancedJsonChange,
            lines = 4,
            hint = """Example: {"tools":[{"type":"openrouter:web_search"}],"maxToolCalls":8}""",
        )
        Text(
            "Use tools for OpenRouter server tools, maxToolCalls (1–25) for tool steps, " +
                "maxCompletionTokens for output, temperature (0–2), and reasoning with effort or maxTokens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Experimental function inheritance uses inheritFunctions (true or false) or " +
                "inheritedFunctionNames (a list of client function names).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            runCatching { uriHandler.openUri("https://openrouter.ai/docs/guides/features/server-tools/subagent#parameters") }
        }) { Text("OpenRouter subagent parameters") }
    }

    if (showModelPicker) {
        val currentSelection = trimmedModel.takeIf { it.isNotEmpty() }?.let { id ->
            matchedModel ?: CatalogModel(id, id, 0, emptySet())
        }
        ModelPicker(
            models = availableModels,
            selected = currentSelection,
            requireTools = false,
            loading = catalogLoading,
            error = catalogError,
            onRefresh = onRefreshModels,
            onDismiss = { showModelPicker = false },
            allowManual = true,
        ) { chosen ->
            onModelChange(chosen.id)
            showModelPicker = false
        }
    }
}

@Composable
private fun SubagentEditor(
    existing: JsonObject?,
    availableModels: List<CatalogModel>,
    catalogLoading: Boolean,
    catalogError: String?,
    onRefreshModels: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (JsonObject) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val settings = existing?.subagentParameters()
    var name by rememberSaveable { mutableStateOf(settings?.stringValue("name").orEmpty()) }
    var model by rememberSaveable { mutableStateOf(settings?.stringValue("model").orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(settings?.stringValue("instructions").orEmpty()) }
    var advancedJson by rememberSaveable { mutableStateOf(subagentAdvancedJson(settings)) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add subagent" else "Edit subagent") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SubagentEditorFields(
                    name = name,
                    onNameChange = { name = it; error = null },
                    model = model,
                    onModelChange = { model = it; error = null },
                    instructions = instructions,
                    onInstructionsChange = { instructions = it; error = null },
                    advancedJson = advancedJson,
                    onAdvancedJsonChange = { advancedJson = it; error = null },
                    availableModels = availableModels,
                    catalogLoading = catalogLoading,
                    catalogError = catalogError,
                    onRefreshModels = onRefreshModels,
                    showAdvanced = showAdvanced,
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    onApply(editSubagent(existing, name, model, instructions, advancedJson))
                } catch (e: IllegalArgumentException) {
                    error = e.message ?: "Check your subagent settings."
                }
            }) { Text("Apply") }
        },
        dismissButton = {
            Column {
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Row {
                    if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )
}
