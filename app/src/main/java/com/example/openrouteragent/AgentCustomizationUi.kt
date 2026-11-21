package com.example.openrouteragent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomizationSheet(
    config: AgentConfig,
    guide: SavedAgentSkill?,
    initialTab: String,
    onDismiss: () -> Unit,
    onSave: suspend (AgentConfig) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var tool by remember { mutableStateOf<CustomAgentTool?>(null) }
    var skill by remember { mutableStateOf<SavedAgentSkill?>(null) }
    var oldSkillName by remember { mutableStateOf<String?>(null) }
    var readGuide by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exportText by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver

    val importSkill = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            error = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { input ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= SKILL_IMPORT_BYTES) { "This SKILL.md is too large." }
                            bytes.write(buffer, 0, count)
                        }
                        parseSkill(bytes.toString(Charsets.UTF_8.name()))
                    } ?: throw IllegalArgumentException("Android could not read this file.")
                }
                oldSkillName = config.savedSkills.firstOrNull { it.name == imported.name }?.name
                skill = imported
                message = if (oldSkillName == null) "Review the instructions before enabling the skill."
                    else "A skill with this name exists. Save to replace it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.take(250) ?: "Could not import the skill."
            }
        }
    }
    val exportSkill = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val payload = exportText
        exportText = null
        if (uri != null && payload != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = resolver.openOutputStream(uri, "wt") ?: kotlin.error("Android could not open the file.")
                    out.bufferedWriter().use { it.write(payload) }
                }
                message = "Skill exported."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.take(250) ?: "Could not export the skill."
            }
        }
    }

    fun backToList() {
        tool = null
        skill = null
        oldSkillName = null
        readGuide = false
        message = null
        error = null
    }
    fun commit(value: AgentConfig) {
        error = null
        working = true
        scope.launch {
            try {
                onSave(value)
                backToList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Android could not save this change."
            } finally {
                working = false
            }
        }
    }
    fun export(value: SavedAgentSkill) {
        exportText = value.markdown()
        exportSkill.launch("SKILL.md")
    }

    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.91f).imePadding().padding(horizontal = 20.dp)) {
            val editing = tool != null || skill != null || readGuide
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when {
                    readGuide -> "App guide"
                    tool != null -> "Custom tool"
                    skill != null -> "Skill"
                    else -> "Tools & skills"
                }, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { if (editing) backToList() else onDismiss() }, enabled = !working) {
                    Text(if (editing) "Back" else "Done")
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val currentTool = tool
                val currentSkill = skill
                when {
                    readGuide -> guide?.let { entry ->
                        OutlinedSection {
                            CopyableText(entry.body, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { export(entry) }) { Text("Export guide") }
                    }
                    currentTool != null -> {
                        EditField("Tool name", currentTool.name, { tool = currentTool.copy(name = it) })
                        EditField("What it does and when to use it", currentTool.description,
                            { tool = currentTool.copy(description = it) }, 2)
                        ChoiceStrip(listOf("template" to "Template", "ask" to "Ask me", "webhook" to "Webhook"),
                            currentTool.handler) { tool = currentTool.copy(handler = it, value = "") }
                        EditField("JSON input schema", currentTool.schema, { tool = currentTool.copy(schema = it) }, 4,
                            "Use an object schema with properties and optional required keys.")
                        when (currentTool.handler) {
                            "webhook" -> EditField("HTTPS endpoint", currentTool.value, { tool = currentTool.copy(value = it) },
                                hint = "The agent asks before sending arguments here. The endpoint must allow the app’s WebView origin.")
                            "template" -> EditField("Response template", currentTool.value, { tool = currentTool.copy(value = it) },
                                3, "Use {{field}} to insert a validated argument.")
                            "ask" -> Text("The run pauses, shows the arguments, and returns your answer to the model.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        ToggleLine("Enabled", currentTool.enabled) { tool = currentTool.copy(enabled = it) }
                    }
                    currentSkill != null -> {
                        EditField("Skill name", currentSkill.name, { skill = currentSkill.copy(name = it) })
                        EditField("When to use it", currentSkill.description, { skill = currentSkill.copy(description = it) }, 3)
                        EditField("Markdown instructions", currentSkill.body, { skill = currentSkill.copy(body = it) }, 8)
                        ToggleLine("Enabled", currentSkill.enabled) {
                            skill = currentSkill.copy(enabled = it, pinned = currentSkill.pinned && it)
                        }
                        ToggleLine("Pin to each run", currentSkill.pinned) {
                            skill = currentSkill.copy(pinned = it, enabled = currentSkill.enabled || it)
                        }
                        TextButton(onClick = {
                            val reviewed = currentSkill.copy(name = currentSkill.name.trim(), description = currentSkill.description.trim())
                            error = reviewed.problem()
                            if (error == null) export(reviewed)
                        }) { Text("Export SKILL.md") }
                    }
                    else -> {
                        ChoiceStrip(listOf("tools" to "Custom tools", "skills" to "Skills"), tab) { tab = it; error = null }
                        if (tab == "tools") {
                            Button(enabled = config.customTools.size < 32, onClick = { tool = CustomAgentTool() }) { Text("New tool") }
                            if (config.customTools.isEmpty()) Text("Create a template, ask-me tool, or HTTPS webhook.")
                            config.customTools.forEach { item ->
                                CustomizationRow(item.name, item.description,
                                    if (item.enabled) "Enabled" else "Disabled") { tool = item }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = config.savedSkills.size < 32, onClick = { skill = SavedAgentSkill() }) { Text("New skill") }
                                TextButton(onClick = { importSkill.launch(arrayOf("text/*", "application/octet-stream")) }) {
                                    Text("Import SKILL.md")
                                }
                            }
                            guide?.let { CustomizationRow("App guide", "Setup, controls, and customization", "Built in") { readGuide = true } }
                            config.savedSkills.forEach { item ->
                                CustomizationRow(item.name, item.description,
                                    if (item.pinned) "Pinned" else if (item.enabled) "Enabled" else "Disabled") {
                                    oldSkillName = item.name
                                    skill = item
                                }
                            }
                        }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            if (tool != null || skill != null) {
                val canDelete = config.customTools.any { it.id == tool?.id } || oldSkillName != null
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (canDelete) TextButton(onClick = { confirmDelete = true }, enabled = !working) { Text("Delete") }
                    Button(enabled = !working, onClick = {
                        tool?.let { draft ->
                            val revised = draft.copy(name = draft.name.trim(), description = draft.description.trim(),
                                value = if (draft.handler == "webhook") draft.value.trim() else draft.value)
                            val tools = config.customTools.filterNot { it.id == draft.id }
                            error = revised.problem() ?: if (tools.any { it.name == revised.name }) "That tool name is already in use." else null
                            if (error == null) commit(config.copy(customTools = tools + revised))
                        }
                        skill?.let { draft ->
                            val revised = draft.copy(name = draft.name.trim(), description = draft.description.trim(), body = draft.body.trim())
                            val skills = config.savedSkills.filterNot { it.name == oldSkillName }
                            error = revised.problem() ?: if (revised.name == GUIDE_NAME || skills.any { it.name == revised.name })
                                "That skill name is already in use." else if (skills.size >= 32) "You can save up to 32 skills." else null
                            if (error == null) commit(config.copy(savedSkills = skills + revised))
                        }
                    }) { Text(if (working) "Saving…" else "Save") }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(if (tool != null) "Delete tool?" else "Delete skill?") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                if (tool != null) commit(config.copy(customTools = config.customTools.filterNot { it.id == tool?.id }))
                else commit(config.copy(savedSkills = config.savedSkills.filterNot { it.name == oldSkillName }))
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun CustomizationRow(title: String, description: String, status: String, onClick: () -> Unit) {
    OutlinedSection {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun ColumnScope.EditField(label: String, value: String, onChange: (String) -> Unit,
    lines: Int = 1, hint: String? = null) {
    CopyableOutlinedTextField(value, onChange, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        maxLines = if (lines == 1) 1 else lines.coerceAtLeast(5) + 5,
        modifier = Modifier.fillMaxWidth(), supportingText = hint?.let { { Text(it) } })
}

@Composable
internal fun ChoiceStrip(options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, title) -> FilterChip(key == value, { onSelect(key) }, label = { Text(title) }) }
    }
}

@Composable
internal fun ToggleLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}
