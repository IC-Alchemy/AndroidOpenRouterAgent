package com.example.openrouteragent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
internal fun SubagentsSettings(json: String, enabled: Boolean, onChange: (String) -> Unit) {
    val tools = remember(json) { readHostedTools(json) }
    val workers = tools.orEmpty().withIndex().filter { it.value.isSubagent() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(enabled = enabled, onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide subagents" else "Subagents (${workers.size})")
        }
        if (expanded) {
            Text("Let the main model delegate focused tasks to OpenRouter workers.",
                style = MaterialTheme.typography.bodySmall)
            if (tools == null) {
                Text("Fix the Additional server tools JSON in Advanced agent settings to edit subagents.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                workers.forEach { (index, tool) ->
                    val params = tool.subagentParameters()
                    val name = params?.stringValue("name")?.trim()
                    val model = params?.stringValue("model")?.trim()
                    ListItem(
                        headlineContent = { Text(name?.takeIf(String::isNotBlank) ?: "Default subagent") },
                        supportingContent = { Text(model?.takeIf(String::isNotBlank) ?: "Uses the main model") },
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
private fun SubagentEditor(
    existing: JsonObject?,
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
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add subagent" else "Edit subagent") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Name (optional)") },
                    supportingText = { Text("Leave blank for the default subagent. Names must be unique.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it; error = null }, label = { Text("Worker model ID (optional)") },
                    supportingText = { Text("Leave blank to use the main model.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(instructions, { instructions = it; error = null },
                    label = { Text("Worker instructions (optional)") }, minLines = 2, maxLines = 5,
                    modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide advanced worker settings" else "Advanced worker settings")
                }
                if (showAdvanced) {
                    OutlinedTextField(advancedJson, { advancedJson = it; error = null },
                        label = { Text("Worker settings (JSON)") },
                        placeholder = { Text("""{"tools":[{"type":"openrouter:web_search"}]}""") },
                        minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Use tools for OpenRouter server tools, maxToolCalls (1–25) for tool steps, " +
                            "maxCompletionTokens for output, temperature (0–2), and reasoning with effort or maxTokens.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Experimental function inheritance uses inheritFunctions (true or false) or " +
                            "inheritedFunctionNames (a list of client function names).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        runCatching { uriHandler.openUri("https://openrouter.ai/docs/guides/features/server-tools/subagent#parameters") }
                    }) { Text("OpenRouter subagent parameters") }
                }
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
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().semantics {
                        liveRegion = LiveRegionMode.Polite
                    })
                }
                Row {
                    if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )
}
