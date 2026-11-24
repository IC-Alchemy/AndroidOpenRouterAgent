package com.example.openrouteragent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Switches between OpenRouter workspaces and shows or hides providers and models in the selected one.
 * The provider and model choices are saved on the workspace's default guardrail at OpenRouter, so they
 * apply to every app and key in that workspace. Only the selected workspace is kept on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacesSheet(agent: AgentViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val config = agent.config
    var tab by rememberSaveable { mutableStateOf("providers") }
    var query by rememberSaveable { mutableStateOf("") }
    var managementDraft by remember { mutableStateOf("") }
    var workspaceKeyDraft by remember(agent.activeWorkspaceId) { mutableStateOf("") }
    var editingManagementKey by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = working || agent.rulesSaving || agent.workspaceLoading || agent.isSavingSettings

    fun act(block: suspend () -> Unit) {
        if (busy) return
        error = null
        working = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.take(300) ?: "OpenRouter couldn't save that change."
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(config.managementKey) {
        if (config.managementKey.isNotBlank() && agent.workspaces.isEmpty() && !agent.workspaceLoading) {
            agent.refreshWorkspaces()
        }
    }
    LaunchedEffect(tab, config.managementKey) {
        if (tab == "models" && config.managementKey.isNotBlank()) {
            try {
                agent.loadFullCatalog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.take(300) ?: "The model list couldn't be loaded."
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!working) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text("Provider and model choices are saved on OpenRouter for the whole workspace.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss, enabled = !working) { Text("Done") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            (error ?: agent.workspaceError)?.let {
                Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            if (config.managementKey.isBlank() || editingManagementKey) {
                ManagementKeyForm(
                    value = managementDraft,
                    onValueChange = { managementDraft = it },
                    enabled = !busy,
                    canRemove = config.managementKey.isNotBlank(),
                    onSave = { act { agent.saveManagementKey(managementDraft); managementDraft = ""; editingManagementKey = false } },
                    onRemove = { act { agent.saveManagementKey(""); editingManagementKey = false } },
                    onCancel = if (config.managementKey.isNotBlank()) ({ editingManagementKey = false }) else null,
                )
                return@Column
            }

            LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { SectionTitle("Workspace") }
                items(agent.workspaces, key = { "workspace-" + it.id }) { workspace ->
                    val selected = workspace.id == agent.activeWorkspaceId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected, enabled = !busy, role = Role.RadioButton) {
                                if (!selected) act { agent.selectWorkspace(workspace.id) }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(workspace.name, style = MaterialTheme.typography.titleSmall)
                            val notes = listOfNotNull(
                                "API key's workspace".takeIf { workspace.id == agent.keyWorkspaceId },
                                "Key saved".takeIf { workspace.id != agent.keyWorkspaceId && workspace.id in config.workspaceKeys },
                                workspace.description,
                            )
                            if (notes.isNotEmpty()) {
                                Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (agent.workspaces.isEmpty() && !agent.workspaceLoading) {
                    item { Text("OpenRouter returned no workspaces for this management key.") }
                }
                val selected = agent.selectedWorkspace
                if (selected != null && agent.keyWorkspaceId != null && agent.keyWorkspaceId != selected.id) {
                    item(key = "workspace-key") {
                        val owner = agent.workspaces.firstOrNull { it.id == agent.keyWorkspaceId }?.name ?: "another workspace"
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Chats still use your API key from $owner, so OpenRouter bills and filters them " +
                                "there. Paste an API key from ${selected.name} to send chats from it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(workspaceKeyDraft, { workspaceKeyDraft = it }, Modifier.weight(1f),
                                    enabled = !busy, singleLine = true, label = { Text("API key for ${selected.name}") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                                Button(onClick = { act { agent.saveWorkspaceKey(selected.id, workspaceKeyDraft); workspaceKeyDraft = "" } },
                                    enabled = !busy && workspaceKeyDraft.isNotBlank()) { Text("Use") }
                            }
                        }
                    }
                }
                if (selected != null) {
                    item(key = "tabs") {
                        Column {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            val tabs = listOf("providers" to "Providers", "models" to "Models")
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                tabs.forEachIndexed { index, (key, title) ->
                                    SegmentedButton(selected = tab == key, onClick = { tab = key; query = "" },
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                                        icon = {}, label = { Text(title) })
                                }
                            }
                        }
                    }
                    val rules = agent.workspaceRules
                    if (rules == null) {
                        item { Text("Loading ${selected.name}'s rules…", style = MaterialTheme.typography.bodySmall) }
                    } else if (tab == "providers") {
                        providerRows(agent, rules, query, { query = it }, !busy) { act { agent.saveWorkspaceRules(it) } }
                    } else {
                        modelRows(agent, rules, query, { query = it }, !busy) { act { agent.saveWorkspaceRules(it) } }
                    }
                }
                item(key = "management") {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Management key saved on this phone", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { editingManagementKey = true }, enabled = !busy) { Text("Change") }
                            TextButton(onClick = { agent.refreshWorkspaces() }, enabled = !busy) { Text("Refresh") }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.providerRows(
    agent: AgentViewModel, rules: GuardrailRules, query: String, onQuery: (String) -> Unit, enabled: Boolean,
    onSave: (GuardrailRules) -> Unit,
) {
    val providers = agent.providerList
    val shownCount = providers.count { rules.showsProvider(it.slug) }
    item(key = "provider-header") {
        FilterHeader(
            summary = if (!rules.hasProviderRules) "All providers are shown." else
                "$shownCount of ${providers.size} providers shown. Models hosted only by hidden providers are hidden.",
            query = query, onQuery = onQuery, placeholder = "Search providers",
            showAll = if (rules.hasProviderRules) ({ onSave(rules.allProviders()) }) else null, enabled = enabled,
        )
    }
    if (providers.isEmpty()) item { Text("OpenRouter's provider list couldn't be loaded. Tap Refresh.") }
    val visible = providers.filter {
        it.name.contains(query.trim(), ignoreCase = true) || it.slug.contains(query.trim(), ignoreCase = true)
    }
    items(visible, key = { "provider-" + it.slug }) { provider ->
        val shown = rules.showsProvider(provider.slug)
        ToggleRow(
            title = provider.name, subtitle = provider.slug, shown = shown,
            // The last shown provider can't be hidden; OpenRouter would block every request.
            enabled = enabled && !(shown && rules.allowedProviders?.size == 1),
            onToggle = { onSave(rules.withProvider(provider.slug, it)) },
            onOnly = { onSave(rules.onlyProvider(provider.slug)) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.modelRows(
    agent: AgentViewModel, rules: GuardrailRules, query: String, onQuery: (String) -> Unit, enabled: Boolean,
    onSave: (GuardrailRules) -> Unit,
) {
    val models = agent.fullCatalog
    item(key = "model-header") {
        FilterHeader(
            summary = when {
                models.isEmpty() -> "Loading OpenRouter's models…"
                !rules.hasModelRules -> "All models are shown, apart from those hidden by provider."
                else -> "${models.count(rules::showsModel)} of ${models.size} models shown."
            },
            query = query, onQuery = onQuery, placeholder = "Search models",
            showAll = if (rules.hasModelRules) ({ onSave(rules.allModels()) }) else null, enabled = enabled,
        )
    }
    val trimmed = query.trim()
    val visible = models.filter { it.name.contains(trimmed, ignoreCase = true) || it.id.contains(trimmed, ignoreCase = true) }
    items(visible, key = { "model-" + it.id }) { model ->
        val shown = rules.showsModel(model)
        ToggleRow(
            title = model.name, subtitle = model.id, shown = shown,
            enabled = enabled && !(shown && rules.allowedModels?.size == 1),
            onToggle = { onSave(rules.withModel(model, it)) },
            onOnly = { onSave(rules.onlyModel(model)) },
        )
    }
}

@Composable
private fun FilterHeader(
    summary: String, query: String, onQuery: (String) -> Unit, placeholder: String,
    showAll: (() -> Unit)?, enabled: Boolean,
) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showAll != null) OutlinedButton(onClick = showAll, enabled = enabled) { Text("Show all") }
        }
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(placeholder) })
    }
}

@Composable
private fun ToggleRow(
    title: String, subtitle: String, shown: Boolean, enabled: Boolean,
    onToggle: (Boolean) -> Unit, onOnly: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onOnly, enabled = enabled) { Text("Only") }
        Switch(checked = shown, onCheckedChange = onToggle, enabled = enabled)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() })
}

@Composable
private fun ManagementKeyForm(
    value: String, onValueChange: (String) -> Unit, enabled: Boolean, canRemove: Boolean,
    onSave: () -> Unit, onRemove: () -> Unit, onCancel: (() -> Unit)?,
) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Workspaces and their provider and model rules are managed with an OpenRouter management key. " +
            "Create one in OpenRouter's settings under Management keys. It can change your workspaces and " +
            "guardrails but can't send chats. The app keeps it encrypted on this phone.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
            label = { Text("Management key") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = enabled && value.isNotBlank()) { Text("Save key") }
            if (canRemove) OutlinedButton(onClick = onRemove, enabled = enabled) { Text("Remove key") }
            if (onCancel != null) TextButton(onClick = onCancel, enabled = enabled) { Text("Cancel") }
        }
    }
}
