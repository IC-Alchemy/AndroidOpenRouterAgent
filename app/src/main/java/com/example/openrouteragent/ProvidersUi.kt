package com.example.openrouteragent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProvidersSheet(
    config: AgentConfig,
    editActive: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (AgentConfig) -> Unit,
    onDiscover: suspend (CustomProvider) -> List<CatalogModel>,
) {
    var draft by remember { mutableStateOf(if (editActive) config.customProviders.firstOrNull { it.id == config.activeProviderId } else null) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var showDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun edit(profile: CustomProvider?) {
        draft = profile
        status = null
        error = null
        found = emptyList()
    }

    fun persist(updated: AgentConfig) {
        working = true
        error = null
        scope.launch {
            try {
                onSave(updated)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Android could not save the provider."
            } finally {
                working = false
            }
        }
    }

    fun select(profile: CustomProvider) {
        if (!profile.canChat) {
            edit(profile)
            return
        }
        persist(config.copy(activeProviderId = profile.id))
    }

    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding().padding(horizontal = 20.dp)) {
            val entry = draft
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry?.let { if (it.id == OPENUNCENSORED_ID) "OpenUncensored" else "Provider settings" } ?: "Providers",
                    style = MaterialTheme.typography.titleLarge)
                TextButton(enabled = !working, onClick = { if (entry == null) onDismiss() else edit(null) }) {
                    Text(if (entry == null) "Done" else "Back")
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entry == null) {
                    ProviderRow("OpenRouter", "Agent SDK · hosted tools", config.activeProviderId == OPENROUTER_ID, working,
                        action = { persist(config.copy(activeProviderId = OPENROUTER_ID)) })
                    config.customProviders.forEach { profile ->
                        ProviderRow(profile.name, profile.model.ifBlank { profile.baseUrl },
                            config.activeProviderId == profile.id, working,
                            action = { select(profile) }, edit = { edit(profile) })
                    }
                    Button(onClick = { edit(CustomProvider()) }, enabled = !working) { Text("Add provider") }
                    Text("Custom providers use OpenAI-compatible chat and have separate conversations.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    EditField("Provider name", entry.name, { draft = entry.copy(name = it) })
                    EditField("API base URL", entry.baseUrl, {
                        val previous = normalizeChatBase(entry.baseUrl)
                        val next = normalizeChatBase(it)
                        val originChanged = previous != null &&
                            (previous.host != next?.host || previous.port != next.port)
                        draft = entry.copy(baseUrl = it, apiKey = if (originChanged) "" else entry.apiKey)
                        found = emptyList()
                        status = if (originChanged && entry.apiKey.isNotBlank()) "Enter the API key for the new host." else null
                    }, hint = "Use the API base, such as https://example.com/v1. A full chat/completions URL works too.")
                    OutlinedTextField(entry.apiKey, {
                        draft = entry.copy(apiKey = it); found = emptyList(); status = null
                    }, label = { Text(if (entry.needsKey) "OpenUncensored API key" else "Provider API key (optional)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                    EditField("Model ID", entry.model, { draft = entry.copy(model = it) },
                        hint = "Pick a discovered model or enter its exact ID.")
                    TextButton(enabled = !working, onClick = {
                        val candidate = entry.normalized()
                        error = candidate.error()
                        if (error == null) {
                            working = true
                            status = null
                            scope.launch {
                                try {
                                    val models = onDiscover(candidate)
                                    if (draft?.baseUrl?.let(::normalizeChatBase)?.toString() == candidate.baseUrl &&
                                        draft?.apiKey?.trim() == candidate.apiKey) {
                                        found = models
                                        status = if (models.isEmpty()) "No models returned. Enter a model ID manually."
                                            else "Found ${models.size} models."
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = providerMessage(candidate, e) + " You can still enter a model ID manually."
                                } finally {
                                    working = false
                                }
                            }
                        }
                    }) { Text(if (working) "Working…" else "Load models") }
                    if (found.isNotEmpty()) {
                        var filter by remember { mutableStateOf("") }
                        EditField("Find a model", filter, { filter = it })
                        found.filter { it.id.contains(filter, ignoreCase = true) || it.name.contains(filter, ignoreCase = true) }
                            .take(25).forEach { model ->
                                TextButton(onClick = { draft = draft?.copy(model = model.id); found = emptyList() }) {
                                    Text(model.name)
                                }
                            }
                    }
                    if (entry.id == OPENUNCENSORED_ID) {
                        Text("Routing", style = MaterialTheme.typography.labelLarge)
                        ChoiceStrip(listOf("quality" to "Quality", "" to "Provider default", "speed" to "Speed",
                            "throughput" to "Throughput", "cost" to "Cost", "random" to "Random"), entry.strategy) {
                            draft = entry.copy(strategy = it)
                        }
                    }
                    ToggleLine("Stream responses", entry.stream) { draft = entry.copy(stream = it) }
                    EditField("System instructions (optional)", entry.systemInstructions,
                        { draft = entry.copy(systemInstructions = it) }, 2)
                    EditField("Extra request fields (JSON)", entry.requestJson, { draft = entry.copy(requestJson = it) },
                        3, "Set provider-specific options such as temperature. Keep secrets in the API key field.")
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (entry != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (entry.id != OPENUNCENSORED_ID && config.customProviders.any { it.id == entry.id }) {
                        TextButton(enabled = !working, onClick = { showDelete = true }) { Text("Remove") }
                    }
                    Button(enabled = !working, onClick = {
                        val candidate = entry.normalized()
                        error = candidate.error()
                        if (error == null) {
                            val providers = config.customProviders.map { if (it.id == candidate.id) candidate else it }
                            persist(config.copy(
                                activeProviderId = candidate.id,
                                customProviders = if (providers.any { it.id == candidate.id }) providers else providers + candidate,
                            ))
                        }
                    }) { Text("Save and use") }
                }
            }
        }
    }

    if (showDelete) AlertDialog(
        title = { Text("Remove this provider?") },
        text = { Text("This removes the saved connection, API key, and local conversation for this provider.") },
        onDismissRequest = { showDelete = false },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = {
                showDelete = false
                val id = draft?.id ?: return@TextButton
                persist(config.copy(activeProviderId = if (config.activeProviderId == id) OPENROUTER_ID else config.activeProviderId,
                    customProviders = config.customProviders.filterNot { it.id == id }))
            }) { Text("Remove provider") }
        },
    )
}

@Composable
private fun ProviderRow(name: String, model: String, active: Boolean, working: Boolean,
    action: () -> Unit, edit: (() -> Unit)? = null) {
    Column {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(model, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            edit?.let { TextButton(enabled = !working, onClick = it) { Text("Edit") } }
            TextButton(enabled = !working && !active, onClick = action) { Text(if (active) "Active" else "Use") }
        }
    }
}
