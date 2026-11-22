package com.example.openrouteragent

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private enum class BadgeTone {
    PRIMARY,
    SECONDARY,
    MUTED,
}

private sealed interface AiModalState {
    val kind: CustomizationKind

    data class Create(override val kind: CustomizationKind) : AiModalState
    data class Improve(
        override val kind: CustomizationKind,
        val title: String,
        val target: CustomizationTarget,
    ) : AiModalState
}

private fun normalizeCustomizationTab(tab: String): String = when (tab) {
    "tools", "skills", "subagents" -> tab
    else -> "tools"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CustomizationSheet(
    config: AgentConfig,
    guide: SavedAgentSkill?,
    initialTab: String,
    availableModels: List<CatalogModel>,
    selectedModel: CatalogModel?,
    providerReady: Boolean,
    catalogLoading: Boolean,
    catalogError: String?,
    onRefreshModels: () -> Unit,
    onCreateWithModel: suspend (CustomizationKind, String) -> String,
    onImproveWithModel: suspend (CustomizationTarget, String) -> String,
    onSearchSkills: suspend (String) -> List<RemoteSkillSummary>,
    onFetchRemoteSkill: suspend (RemoteSkillSummary) -> SavedAgentSkill,
    onDismiss: () -> Unit,
    onSave: suspend (AgentConfig) -> Unit,
) {
    var tab by rememberSaveable(initialTab) { mutableStateOf(normalizeCustomizationTab(initialTab)) }
    var tool by remember { mutableStateOf<CustomAgentTool?>(null) }
    var skill by remember { mutableStateOf<SavedAgentSkill?>(null) }
    var oldSkillName by remember { mutableStateOf<String?>(null) }
    var subagent by remember { mutableStateOf<SubagentDraft?>(null) }
    var showSubagentAdvanced by rememberSaveable { mutableStateOf(false) }
    var readGuide by rememberSaveable { mutableStateOf(false) }
    var searchingSkills by rememberSaveable { mutableStateOf(false) }

    var skillQuery by rememberSaveable { mutableStateOf("") }
    var skillResults by remember { mutableStateOf<List<RemoteSkillSummary>>(emptyList()) }
    var hasSearchedSkills by rememberSaveable { mutableStateOf(false) }
    var searchingRemote by remember { mutableStateOf(false) }
    var importingSkillId by remember { mutableStateOf<String?>(null) }
    var skillSearchError by remember { mutableStateOf<String?>(null) }

    var aiModal by remember { mutableStateOf<AiModalState?>(null) }
    var aiPrompt by rememberSaveable { mutableStateOf("") }
    var aiWorking by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }

    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exportText by rememberSaveable { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val uriHandler = LocalUriHandler.current

    val hostedTools = remember(config.extraToolsJson) { readHostedTools(config.extraToolsJson) }
    val workers = remember(hostedTools) { hostedTools.orEmpty().withIndex().filter { it.value.isSubagent() } }
    val canUseModel = providerReady && selectedModel != null
    val busy = working || aiWorking || searchingRemote || importingSkillId != null

    val importSkill = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            error = null
            message = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    val bytes = resolver.openDocumentInput(uri).use { it.readBytesLimited(SKILL_IMPORT_BYTES + 1) }
                    require(bytes.size <= SKILL_IMPORT_BYTES) { "This SKILL.md is too large." }
                    parseSkill(bytes.toString(Charsets.UTF_8))
                }
                oldSkillName = config.savedSkills.firstOrNull { it.name == imported.name }?.name
                skill = imported
                message = if (oldSkillName == null) {
                    "Review the instructions before enabling the skill."
                } else {
                    "A skill with this name exists. Save to replace it."
                }
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
                resolver.writeCreatedDocument(uri) {
                    resolver.openDocumentOutput(uri).bufferedWriter().use { it.write(payload) }
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
        subagent = null
        showSubagentAdvanced = false
        readGuide = false
        searchingSkills = false
        skillSearchError = null
        message = null
        error = null
    }

    fun commit(value: AgentConfig, statusMessage: String? = null) {
        error = null
        working = true
        scope.launch {
            try {
                onSave(value)
                backToList()
                message = statusMessage
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

    fun openCreateModal(kind: CustomizationKind) {
        aiPrompt = ""
        aiError = null
        aiModal = AiModalState.Create(kind)
    }

    fun openImproveModal(kind: CustomizationKind, title: String, target: CustomizationTarget) {
        aiPrompt = ""
        aiError = null
        aiModal = AiModalState.Improve(kind, title, target)
    }

    fun runSkillSearch() {
        val query = skillQuery.trim()
        if (query.isEmpty() || busy) return
        skillSearchError = null
        searchingRemote = true
        hasSearchedSkills = true
        scope.launch {
            try {
                skillResults = onSearchSkills(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skillSearchError = e.message?.take(250) ?: "Could not search skills.sh."
            } finally {
                searchingRemote = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().padding(horizontal = 20.dp)) {
            val editing = tool != null || skill != null || subagent != null
            val inSubScreen = editing || readGuide || searchingSkills

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = when {
                            readGuide -> "App guide"
                            searchingSkills -> "Search skills.sh"
                            tool != null -> if (config.customTools.any { it.id == tool?.id }) "Edit tool" else "New tool"
                            skill != null -> if (oldSkillName != null) "Edit skill" else "New skill"
                            subagent != null -> if (subagent?.index != null) "Edit subagent" else "New subagent"
                            else -> "Tools, skills & subagents"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (!inSubScreen) {
                        Text(
                            text = if (canUseModel) "AI actions use ${selectedModel!!.name}" else "Select a model to enable AI actions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = { if (inSubScreen) backToList() else onDismiss() }, enabled = !busy) {
                    Text(if (inSubScreen) "Back" else "Done")
                }
            }

            if (!inSubScreen) {
                val tabs = listOf(
                    "tools" to "Tools (${config.customTools.size})",
                    "skills" to "Skills (${config.savedSkills.size})",
                    "subagents" to "Subagents (${workers.size})",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
                    tabs.forEachIndexed { index, (key, title) ->
                        SegmentedButton(
                            selected = tab == key,
                            onClick = {
                                tab = key
                                error = null
                                message = null
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                            enabled = !busy,
                            icon = {},
                            label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 4.dp))
            } else {
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                message?.let { NoticeBanner(it, isError = false) }
                error?.let { NoticeBanner(it, isError = true) }

                val currentTool = tool
                val currentSkill = skill
                val currentSubagent = subagent
                when {
                    readGuide -> guide?.let { entry ->
                        OutlinedSection {
                            CopyableText(entry.body, style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(onClick = { export(entry) }) { Text("Export guide") }
                    }

                    searchingSkills -> {
                        OutlinedSection(title = "vercel-labs/skills directory") {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CopyableOutlinedTextField(
                                    value = skillQuery,
                                    onValueChange = { skillQuery = it; skillSearchError = null },
                                    label = { Text("Search query") },
                                    placeholder = { Text("e.g. react, testing, security") },
                                    singleLine = true,
                                    enabled = !busy,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { runSkillSearch() }),
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = { runSkillSearch() },
                                    enabled = !busy && skillQuery.isNotBlank(),
                                ) {
                                    Text("Search")
                                }
                            }
                            Text(
                                "Searches skills.sh via vercel-labs/skills with ADISABLE_TELEMETRY=1 (telemetry disabled).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (searchingRemote || importingSkillId != null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        skillSearchError?.let { NoticeBanner(it, isError = true) }

                        if (!hasSearchedSkills && skillResults.isEmpty() && !searchingRemote) {
                            EmptyCustomizationCard(
                                title = "Search skills.sh",
                                description = "Find reusable SKILL.md workflows published in the vercel-labs/skills ecosystem and import them for review.",
                            )
                        } else if (hasSearchedSkills && skillResults.isEmpty() && !searchingRemote && skillSearchError == null) {
                            EmptyCustomizationCard(
                                title = "No matching skills",
                                description = "Try a different keyword such as react, python, testing, or design.",
                            )
                        } else {
                            skillResults.forEach { remote ->
                                RemoteSkillCard(
                                    skill = remote,
                                    importing = importingSkillId == remote.id,
                                    enabled = !busy,
                                    onImport = {
                                        skillSearchError = null
                                        importingSkillId = remote.id
                                        scope.launch {
                                            try {
                                                val fetched = onFetchRemoteSkill(remote)
                                                searchingSkills = false
                                                oldSkillName = config.savedSkills.firstOrNull { it.name == fetched.name }?.name
                                                skill = fetched
                                                message = if (oldSkillName == null) {
                                                    "Imported “${fetched.name}” from ${remote.source}. Review before enabling and saving."
                                                } else {
                                                    "Imported “${fetched.name}” from ${remote.source}. Save to replace the existing skill."
                                                }
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                skillSearchError = e.message?.take(250) ?: "Could not import ${remote.name}."
                                            } finally {
                                                importingSkillId = null
                                            }
                                        }
                                    },
                                    onOpenWeb = {
                                        runCatching { uriHandler.openUri("https://skills.sh/${remote.id}") }
                                    },
                                )
                            }
                        }
                    }

                    currentTool != null -> {
                        EditField("Tool name", currentTool.name, { tool = currentTool.copy(name = it); error = null })
                        EditField(
                            "What it does and when to use it",
                            currentTool.description,
                            { tool = currentTool.copy(description = it); error = null },
                            2,
                        )
                        ChoiceStrip(
                            listOf("template" to "Template", "ask" to "Ask me", "webhook" to "Webhook"),
                            currentTool.handler,
                        ) { tool = currentTool.copy(handler = it, value = ""); error = null }
                        EditField(
                            "JSON input schema",
                            currentTool.schema,
                            { tool = currentTool.copy(schema = it); error = null },
                            4,
                            "Use an object schema with properties and optional required keys.",
                        )
                        when (currentTool.handler) {
                            "webhook" -> EditField(
                                "HTTPS endpoint",
                                currentTool.value,
                                { tool = currentTool.copy(value = it); error = null },
                                hint = "The agent asks before sending arguments here. The endpoint must allow the app’s WebView origin.",
                            )
                            "template" -> EditField(
                                "Response template",
                                currentTool.value,
                                { tool = currentTool.copy(value = it); error = null },
                                3,
                                "Use {{field}} to insert a validated argument.",
                            )
                            "ask" -> Text(
                                "The run pauses, shows the arguments, and returns your answer to the model.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ToggleLine("Enabled", currentTool.enabled) { tool = currentTool.copy(enabled = it) }
                    }

                    currentSkill != null -> {
                        EditField("Skill name", currentSkill.name, { skill = currentSkill.copy(name = it); error = null })
                        EditField(
                            "When to use it",
                            currentSkill.description,
                            { skill = currentSkill.copy(description = it); error = null },
                            3,
                        )
                        EditField(
                            "Markdown instructions",
                            currentSkill.body,
                            { skill = currentSkill.copy(body = it); error = null },
                            8,
                        )
                        ToggleLine("Enabled", currentSkill.enabled) {
                            skill = currentSkill.copy(enabled = it, pinned = currentSkill.pinned && it)
                        }
                        ToggleLine("Pin to each run", currentSkill.pinned) {
                            skill = currentSkill.copy(pinned = it, enabled = currentSkill.enabled || it)
                        }
                        OutlinedButton(onClick = {
                            val reviewed = currentSkill.copy(
                                name = currentSkill.name.trim(),
                                description = currentSkill.description.trim(),
                            )
                            error = reviewed.problem()
                            if (error == null) export(reviewed)
                        }) { Text("Export SKILL.md") }
                    }

                    currentSubagent != null -> {
                        SubagentEditorFields(
                            name = currentSubagent.name,
                            onNameChange = { subagent = currentSubagent.copy(name = it); error = null },
                            model = currentSubagent.model,
                            onModelChange = { subagent = currentSubagent.copy(model = it); error = null },
                            instructions = currentSubagent.instructions,
                            onInstructionsChange = { subagent = currentSubagent.copy(instructions = it); error = null },
                            advancedJson = currentSubagent.advancedJson,
                            onAdvancedJsonChange = { subagent = currentSubagent.copy(advancedJson = it); error = null },
                            availableModels = availableModels,
                            catalogLoading = catalogLoading,
                            catalogError = catalogError,
                            onRefreshModels = onRefreshModels,
                            showAdvanced = showSubagentAdvanced,
                            onToggleAdvanced = { showSubagentAdvanced = !showSubagentAdvanced },
                        )
                    }

                    tab == "tools" -> {
                        SectionActionHeader(
                            summary = "Custom template, ask-me, and HTTPS webhook tools for your agent.",
                            newLabel = "New tool",
                            canCreateWithAi = canUseModel && config.customTools.size < 32 && !busy,
                            canCreateManual = config.customTools.size < 32 && !busy,
                            showModelHint = !canUseModel,
                            onCreateWithAi = { openCreateModal(CustomizationKind.TOOL) },
                            onCreateManual = { tool = CustomAgentTool(); error = null; message = null },
                        )
                        if (config.customTools.isEmpty()) {
                            EmptyCustomizationCard(
                                title = "No custom tools yet",
                                description = "Create a template, ask-me prompt, or HTTPS webhook manually or with the selected model.",
                            )
                        } else {
                            config.customTools.forEach { item ->
                                CustomizationItemCard(
                                    title = item.name,
                                    description = item.description,
                                    statusLabel = if (item.enabled) "Enabled" else "Disabled",
                                    statusTone = if (item.enabled) BadgeTone.SECONDARY else BadgeTone.MUTED,
                                    metadata = "Handler: ${toolHandlerLabel(item.handler)}",
                                    improveLabel = "Improve Tool",
                                    improveEnabled = canUseModel,
                                    enabled = !busy,
                                    onEdit = { tool = item; error = null; message = null },
                                    onImprove = {
                                        openImproveModal(
                                            CustomizationKind.TOOL,
                                            item.name,
                                            CustomizationTarget.Tool(item),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    tab == "skills" -> {
                        SectionActionHeader(
                            summary = "Reusable Markdown instructions loaded on demand or pinned to runs.",
                            newLabel = "New skill",
                            canCreateWithAi = canUseModel && config.savedSkills.size < 32 && !busy,
                            canCreateManual = config.savedSkills.size < 32 && !busy,
                            showModelHint = !canUseModel,
                            onCreateWithAi = { openCreateModal(CustomizationKind.SKILL) },
                            onCreateManual = { oldSkillName = null; skill = SavedAgentSkill(); error = null; message = null },
                            extraActions = {
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = { searchingSkills = true; skillSearchError = null; error = null; message = null },
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Search skills")
                                }
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = { importSkill.launch(arrayOf("text/*", "application/octet-stream")) },
                                ) {
                                    Text("Import SKILL.md")
                                }
                            },
                        )
                        guide?.let {
                            OutlinedSection(
                                modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { readGuide = true },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "App guide",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatusBadge("Built in", BadgeTone.MUTED)
                                }
                                Text(
                                    "Setup, controls, and customization reference",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(onClick = { readGuide = true }, enabled = !busy) {
                                    Text("View guide")
                                }
                            }
                        }
                        if (config.savedSkills.isEmpty()) {
                            EmptyCustomizationCard(
                                title = "No saved skills yet",
                                description = "Create a skill with AI, write one manually, import a SKILL.md file, or search skills.sh.",
                            )
                        } else {
                            config.savedSkills.forEach { item ->
                                val statusLabel = when {
                                    item.pinned -> "Pinned"
                                    item.enabled -> "Enabled"
                                    else -> "Disabled"
                                }
                                val tone = when {
                                    item.pinned -> BadgeTone.PRIMARY
                                    item.enabled -> BadgeTone.SECONDARY
                                    else -> BadgeTone.MUTED
                                }
                                val detail = when {
                                    item.pinned -> "Included automatically in every run"
                                    item.enabled -> "Discovered on demand"
                                    else -> "Not active until enabled"
                                }
                                CustomizationItemCard(
                                    title = item.name,
                                    description = item.description,
                                    statusLabel = statusLabel,
                                    statusTone = tone,
                                    metadata = detail,
                                    improveLabel = "Improve Skill",
                                    improveEnabled = canUseModel,
                                    enabled = !busy,
                                    onEdit = { oldSkillName = item.name; skill = item; error = null; message = null },
                                    onImprove = {
                                        openImproveModal(
                                            CustomizationKind.SKILL,
                                            item.name,
                                            CustomizationTarget.Skill(item),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    else -> {
                        val toolsEditable = hostedTools != null
                        SectionActionHeader(
                            summary = "Specialized OpenRouter worker models the main agent can delegate to.",
                            newLabel = "New subagent",
                            canCreateWithAi = canUseModel && toolsEditable && !busy,
                            canCreateManual = toolsEditable && !busy,
                            showModelHint = !canUseModel,
                            onCreateWithAi = { openCreateModal(CustomizationKind.SUBAGENT) },
                            onCreateManual = {
                                subagent = SubagentDraft(index = null)
                                showSubagentAdvanced = false
                                error = null
                                message = null
                            },
                        )
                        if (!toolsEditable) {
                            NoticeBanner(
                                "Fix the Additional server tools JSON in Settings before creating or editing subagents.",
                                isError = true,
                            )
                        } else if (workers.isEmpty()) {
                            EmptyCustomizationCard(
                                title = "No subagents yet",
                                description = "Add a subagent manually or generate one with AI, and optionally assign its own default model.",
                            )
                        } else {
                            workers.forEach { (index, worker) ->
                                val hasAssignedModel = !worker.subagentParameters()?.stringValue("model").isNullOrBlank()
                                val modelLabel = worker.subagentModelLabel(availableModels)
                                CustomizationItemCard(
                                    title = worker.subagentDisplayName(),
                                    description = worker.subagentSummary(availableModels),
                                    statusLabel = if (hasAssignedModel) "Custom model" else "Main model",
                                    statusTone = if (hasAssignedModel) BadgeTone.PRIMARY else BadgeTone.SECONDARY,
                                    metadata = "Default model: $modelLabel",
                                    improveLabel = "Improve Subagent",
                                    improveEnabled = canUseModel,
                                    enabled = !busy,
                                    onEdit = {
                                        subagent = worker.toSubagentDraft(index)
                                        showSubagentAdvanced = false
                                        error = null
                                        message = null
                                    },
                                    onImprove = {
                                        openImproveModal(
                                            CustomizationKind.SUBAGENT,
                                            worker.subagentDisplayName(),
                                            CustomizationTarget.Subagent(index, worker),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (editing) {
                val canDelete = config.customTools.any { it.id == tool?.id } ||
                    oldSkillName != null ||
                    subagent?.index != null
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canDelete) {
                        TextButton(onClick = { confirmDelete = true }, enabled = !working) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { backToList() }, enabled = !working) {
                        Text("Cancel")
                    }
                    Button(
                        enabled = !working,
                        onClick = {
                            tool?.let { draft ->
                                val revised = draft.copy(
                                    name = draft.name.trim(),
                                    description = draft.description.trim(),
                                    value = if (draft.handler == "webhook") draft.value.trim() else draft.value,
                                )
                                val tools = config.customTools.filterNot { it.id == draft.id }
                                error = revised.problem()
                                    ?: if (tools.any { it.name == revised.name }) "That tool name is already in use." else null
                                if (error == null) {
                                    val nextTools = if (config.customTools.any { it.id == revised.id }) {
                                        config.customTools.map { if (it.id == revised.id) revised else it }
                                    } else {
                                        tools + revised
                                    }
                                    commit(config.copy(customTools = nextTools), "Saved tool “${revised.name}”.")
                                }
                            }
                            skill?.let { draft ->
                                val revised = draft.copy(
                                    name = draft.name.trim(),
                                    description = draft.description.trim(),
                                    body = draft.body.trim(),
                                )
                                val skills = config.savedSkills.filterNot { it.name == oldSkillName }
                                error = revised.problem()
                                    ?: if (revised.name == GUIDE_NAME || skills.any { it.name == revised.name }) {
                                        "That skill name is already in use."
                                    } else if (skills.size >= 32) {
                                        "You can save up to 32 skills."
                                    } else null
                                if (error == null) {
                                    val nextSkills = if (oldSkillName != null && config.savedSkills.any { it.name == oldSkillName }) {
                                        config.savedSkills.map { if (it.name == oldSkillName) revised else it }
                                    } else {
                                        skills + revised
                                    }
                                    commit(config.copy(savedSkills = nextSkills), "Saved skill “${revised.name}”.")
                                }
                            }
                            subagent?.let { draft ->
                                val currentHosted = hostedTools
                                if (currentHosted == null) {
                                    error = "Fix the Additional server tools JSON in Settings before editing subagents."
                                } else {
                                    try {
                                        val index = draft.index ?: currentHosted.size
                                        val existing = draft.index?.let { currentHosted.getOrNull(it) }
                                        val worker = editSubagent(
                                            existing = existing,
                                            name = draft.name,
                                            model = draft.model,
                                            instructions = draft.instructions,
                                            advancedJson = draft.advancedJson,
                                        )
                                        val nextHosted = currentHosted.withSubagentEdit(index, worker)
                                        val nextJson = writeHostedTools(nextHosted)
                                        hostedToolsError(nextJson)?.let { throw IllegalArgumentException(it) }
                                        commit(
                                            config.copy(extraToolsJson = nextJson),
                                            "Saved subagent “${worker.subagentDisplayName()}”.",
                                        )
                                    } catch (e: IllegalArgumentException) {
                                        error = e.message ?: "Check your subagent settings."
                                    }
                                }
                            }
                        },
                    ) {
                        Text(if (working) "Saving…" else "Save")
                    }
                }
            }
        }
    }

    aiModal?.let { modal ->
        AiPromptDialog(
            modal = modal,
            modelName = selectedModel?.name ?: "Selected model",
            prompt = aiPrompt,
            onPromptChange = { aiPrompt = it; aiError = null },
            working = aiWorking,
            error = aiError,
            onDismiss = { if (!aiWorking) { aiModal = null; aiError = null } },
            onSubmit = {
                aiWorking = true
                aiError = null
                scope.launch {
                    try {
                        val summary = when (modal) {
                            is AiModalState.Create -> onCreateWithModel(modal.kind, aiPrompt)
                            is AiModalState.Improve -> onImproveWithModel(modal.target, aiPrompt)
                        }
                        aiModal = null
                        aiPrompt = ""
                        message = summary
                        error = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        aiError = e.message?.take(300) ?: "Could not complete request."
                    } finally {
                        aiWorking = false
                    }
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    when {
                        tool != null -> "Delete tool?"
                        skill != null -> "Delete skill?"
                        else -> "Delete subagent?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    when {
                        tool != null -> commit(
                            config.copy(customTools = config.customTools.filterNot { it.id == tool?.id }),
                            "Deleted tool.",
                        )
                        skill != null -> commit(
                            config.copy(savedSkills = config.savedSkills.filterNot { it.name == oldSkillName }),
                            "Deleted skill.",
                        )
                        subagent?.index != null -> {
                            val currentHosted = hostedTools
                            val index = subagent?.index
                            if (currentHosted != null && index != null && index in currentHosted.indices) {
                                commit(
                                    config.copy(extraToolsJson = writeHostedTools(currentHosted.withSubagentEdit(index, null))),
                                    "Deleted subagent.",
                                )
                            }
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionActionHeader(
    summary: String,
    newLabel: String,
    canCreateWithAi: Boolean,
    canCreateManual: Boolean,
    showModelHint: Boolean,
    onCreateWithAi: () -> Unit,
    onCreateManual: () -> Unit,
    extraActions: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onCreateWithAi, enabled = canCreateWithAi) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Create with AI")
            }
            FilledTonalButton(onClick = onCreateManual, enabled = canCreateManual) {
                Text(newLabel)
            }
            extraActions()
        }
        if (showModelHint) {
            Text(
                "Connect a provider and choose a model to create or improve items with AI.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CustomizationItemCard(
    title: String,
    description: String,
    statusLabel: String,
    statusTone: BadgeTone,
    metadata: String? = null,
    improveLabel: String,
    improveEnabled: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onImprove: () -> Unit,
) {
    OutlinedSection(
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onEdit),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(statusLabel, statusTone)
        }
        if (!metadata.isNullOrBlank()) {
            Text(
                metadata,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onEdit, enabled = enabled) {
                Text("Edit")
            }
            FilledTonalButton(onClick = onImprove, enabled = enabled && improveEnabled) {
                Text(improveLabel)
            }
        }
    }
}

@Composable
private fun RemoteSkillCard(
    skill: RemoteSkillSummary,
    importing: Boolean,
    enabled: Boolean,
    onImport: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    val installs = formatSkillInstalls(skill.installs)
    OutlinedSection {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                skill.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (installs.isNotEmpty()) {
                StatusBadge(installs, BadgeTone.SECONDARY)
            }
        }
        Text(
            skill.source,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onImport, enabled = enabled) {
                Text(if (importing) "Importing…" else "Import")
            }
            TextButton(onClick = onOpenWeb, enabled = enabled) {
                Text("View on skills.sh")
            }
        }
    }
}

@Composable
private fun AiPromptDialog(
    modal: AiModalState,
    modelName: String,
    prompt: String,
    onPromptChange: (String) -> Unit,
    working: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val isImprove = modal is AiModalState.Improve
    val title = when (modal) {
        is AiModalState.Create -> "Create ${modal.kind.label} with AI"
        is AiModalState.Improve -> "Improve ${modal.kind.label.replaceFirstChar(Char::uppercase)}"
    }
    val subtitle = when (modal) {
        is AiModalState.Create -> "Using $modelName"
        is AiModalState.Improve -> "${modal.title} · Using $modelName"
    }
    val placeholder = when (modal.kind) {
        CustomizationKind.TOOL -> if (isImprove) {
            "e.g. Add validation constraints or a clearer response format"
        } else {
            "e.g. Format a bug report from title, severity, and reproduction steps"
        }
        CustomizationKind.SKILL -> if (isImprove) {
            "e.g. Add concrete edge cases and step-by-step verification checks"
        } else {
            "e.g. Review Kotlin Compose code for accessibility and state bugs"
        }
        CustomizationKind.SUBAGENT -> if (isImprove) {
            "e.g. Restrict to web search and return concise bullet citations"
        } else {
            "e.g. Research worker that searches the web and summarizes primary sources"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CopyableOutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    label = {
                        Text(if (isImprove) "Improvement prompt" else "Describe the ${modal.kind.label}")
                    },
                    placeholder = { Text(placeholder) },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (working) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = !working && prompt.isNotBlank()) {
                Text(
                    when {
                        working -> "Working…"
                        isImprove -> "Apply improvement"
                        else -> "Create"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun EmptyCustomizationCard(title: String, description: String) {
    OutlinedSection {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoticeBanner(text: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (isError) colors.errorContainer else colors.secondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) colors.onErrorContainer else colors.onSecondaryContainer,
        )
    }
}

@Composable
private fun StatusBadge(label: String, tone: BadgeTone) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        BadgeTone.PRIMARY -> colors.primaryContainer to colors.onPrimaryContainer
        BadgeTone.SECONDARY -> colors.secondaryContainer to colors.onSecondaryContainer
        BadgeTone.MUTED -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
        )
    }
}

private fun toolHandlerLabel(handler: String): String = when (handler) {
    "template" -> "Template"
    "ask" -> "Ask me"
    "webhook" -> "Webhook"
    else -> handler
}

@Composable
internal fun ColumnScope.EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    lines: Int = 1,
    hint: String? = null,
) {
    CopyableOutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        maxLines = if (lines == 1) 1 else lines.coerceAtLeast(5) + 5,
        modifier = Modifier.fillMaxWidth(),
        supportingText = hint?.let { { Text(it) } },
    )
}

@Composable
internal fun ChoiceStrip(options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (key, title) ->
            FilterChip(key == value, { onSelect(key) }, label = { Text(title) })
        }
    }
}

@Composable
internal fun ToggleLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}
