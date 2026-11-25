package com.example.openrouteragent

import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBottomSheet(
    initialConfig: AgentConfig,
    onDismiss: () -> Unit,
    onSave: suspend (AgentConfig) -> Unit,
    onValidate: suspend (String) -> Unit,
    agent: AgentViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKey by remember { mutableStateOf(initialConfig.apiKey) }
    var signedIn by remember { mutableStateOf(false) }
    val signInContext = LocalContext.current
    LaunchedEffect(agent.signInKey) {
        agent.takeSignInKey()?.let { apiKey = it; signedIn = true }
    }
    var showApiKey by remember { mutableStateOf(false) }
    var openAiKey by remember { mutableStateOf(initialConfig.openAiKey) }
    var showOpenAiKey by remember { mutableStateOf(false) }
    var openAiTestResult by remember { mutableStateOf<String?>(null) }
    var openAiWebSearch by remember { mutableStateOf(initialConfig.openAiWebSearch) }
    var openAiImageGeneration by remember { mutableStateOf(initialConfig.openAiImageGeneration) }
    var openAiCodeInterpreter by remember { mutableStateOf(initialConfig.openAiCodeInterpreter) }
    var openAiFileSearchStores by remember { mutableStateOf(initialConfig.openAiFileSearchStores) }
    var enableShell by remember { mutableStateOf(initialConfig.enableShellSandbox) }
    var enableWeb by remember { mutableStateOf(initialConfig.enableWebSearch) }
    var enableDateTime by remember { mutableStateOf(initialConfig.enableDateTime) }
    var allowNetwork by remember { mutableStateOf(initialConfig.allowSandboxNetwork) }
    var allowedDomains by remember { mutableStateOf(initialConfig.allowedDomainsCsv) }
    var instructions by remember { mutableStateOf(initialConfig.systemInstructions) }
    var webFetch by remember { mutableStateOf(initialConfig.webFetch) }
    var imageGeneration by remember { mutableStateOf(initialConfig.imageGeneration) }
    var imageModel by remember { mutableStateOf(initialConfig.imageModel) }
    var imageAspect by remember { mutableStateOf(initialConfig.imageAspect) }
    var imageQuality by remember { mutableStateOf(initialConfig.imageQuality) }
    var workspaceFiles by remember { mutableStateOf(initialConfig.enableWorkspaceFiles) }
    var advisorTool by remember { mutableStateOf(initialConfig.advisorTool) }
    var fusionTool by remember { mutableStateOf(initialConfig.fusionTool) }
    var modelSearchTool by remember { mutableStateOf(initialConfig.modelSearchTool) }
    var decisionsTool by remember { mutableStateOf(initialConfig.decisionsTool) }
    var decisionModel by remember { mutableStateOf(initialConfig.decisionModel) }
    var responseHealing by remember { mutableStateOf(initialConfig.responseHealing) }
    var contextCompression by remember { mutableStateOf(initialConfig.contextCompression) }
    var deviceTools by remember { mutableStateOf(initialConfig.deviceTools) }
    var miniApps by remember { mutableStateOf(initialConfig.miniApps) }
    var enableModeration by remember { mutableStateOf(initialConfig.enableModeration) }
    var maxSteps by remember { mutableStateOf(initialConfig.maxSteps.toString()) }
    var maxCost by remember { mutableStateOf(initialConfig.maxCost?.toString().orEmpty()) }
    var temperature by remember { mutableStateOf(initialConfig.temperature?.toString().orEmpty()) }
    var mcpUrl by remember { mutableStateOf(initialConfig.mcpUrl) }
    var mcpToken by remember { mutableStateOf(initialConfig.mcpToken) }
    var toolsJson by rememberSaveable { mutableStateOf(initialConfig.extraToolsJson) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val updated = initialConfig.copy(apiKey = apiKey, openAiKey = openAiKey, openAiWebSearch = openAiWebSearch,
        openAiImageGeneration = openAiImageGeneration, openAiCodeInterpreter = openAiCodeInterpreter,
        openAiFileSearchStores = openAiFileSearchStores.trim(), enableShellSandbox = enableShell, enableWebSearch = enableWeb,
        enableDateTime = enableDateTime, webFetch = webFetch, enableWorkspaceFiles = workspaceFiles,
        imageGeneration = imageGeneration, imageModel = imageModel.trim(), imageAspect = imageAspect,
        imageQuality = imageQuality, advisorTool = advisorTool, fusionTool = fusionTool,
        modelSearchTool = modelSearchTool, decisionsTool = decisionsTool, decisionModel = decisionModel.trim(),
        responseHealing = responseHealing, contextCompression = contextCompression,
        deviceTools = deviceTools, miniApps = miniApps, enableModeration = enableModeration, allowSandboxNetwork = allowNetwork,
        allowedDomainsCsv = allowedDomains, systemInstructions = instructions, maxSteps = maxSteps.toIntOrNull() ?: 0,
        maxCost = maxCost.toDoubleOrNull(), temperature = temperature.toDoubleOrNull(), mcpUrl = mcpUrl.trim(),
        mcpToken = mcpToken, extraToolsJson = toolsJson)

    ModalBottomSheet(
        onDismissRequest = { if (!working) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(.93f)
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }.padding(bottom = 12.dp),
            )
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedSection(title = "Connection") {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; testResult = null; error = null },
                        label = { Text("OpenRouter API key") },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showApiKey = !showApiKey }) {
                                Text(if (showApiKey) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { runCatching { uriHandler.openUri("https://openrouter.ai/settings/keys") } }) {
                            Text("Get an API key")
                        }
                        TextButton(enabled = !working && apiKey.isNotBlank(), onClick = {
                            val keyToTest = apiKey.trim()
                            error = AgentConfig(apiKey = keyToTest).settingsError()
                            if (error == null) {
                                working = true
                                scope.launch {
                                    try {
                                        onValidate(keyToTest)
                                        if (apiKey.trim() == keyToTest) testResult = "OpenRouter accepted the key."
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (apiKey.trim() == keyToTest) error = e.userMessage(keyToTest)
                                    } finally {
                                        working = false
                                    }
                                }
                            }
                        }) { Text("Test key") }
                    }
                    testResult?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (agent.signInBusy) {
                        Text("Finish signing in on the OpenRouter page in your browser, then return here.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = agent::cancelSignIn) { Text("Cancel sign-in") }
                    } else {
                        FilledTonalButton(enabled = !working, onClick = {
                            signedIn = false
                            agent.signInWithOpenRouter { url ->
                                runCatching {
                                    signInContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addCategory(Intent.CATEGORY_BROWSABLE))
                                }.isSuccess
                            }
                        }) { Text(if (apiKey.isBlank()) "Sign in with OpenRouter" else "Sign in again with OpenRouter") }
                    }
                    agent.signInError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (signedIn && apiKey.isNotBlank()) {
                        Text("Signed in. OpenRouter created a key for this app. Tap Save to connect.",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (initialConfig.apiKey.isNotBlank() && apiKey.trim() != initialConfig.apiKey) {
                        Text("Changing the key starts a new conversation.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedSection(title = "OpenAI (direct)") {
                    Text("Optional. Add your own OpenAI key to use OpenAI models directly. They show in Models as " +
                        "openai-direct/… and are billed by OpenAI, not OpenRouter. The key goes only to api.openai.com.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = openAiKey,
                        onValueChange = { openAiKey = it; openAiTestResult = null; error = null },
                        label = { Text("OpenAI API key") },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        singleLine = true,
                        visualTransformation = if (showOpenAiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showOpenAiKey = !showOpenAiKey }) {
                                Text(if (showOpenAiKey) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { runCatching { uriHandler.openUri(OPENAI_KEYS_PAGE) } }) {
                            Text("Get an OpenAI key")
                        }
                        TextButton(enabled = !working && openAiKey.isNotBlank(), onClick = {
                            val keyToTest = openAiKey.trim()
                            error = AgentConfig(openAiKey = keyToTest).settingsError()
                            if (error == null) {
                                working = true
                                scope.launch {
                                    try {
                                        agent.validateOpenAiKey(keyToTest)
                                        if (openAiKey.trim() == keyToTest) openAiTestResult = "OpenAI accepted the key."
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (openAiKey.trim() == keyToTest) error = e.userMessage(keyToTest).redact(keyToTest)
                                    } finally {
                                        working = false
                                    }
                                }
                            }
                        }) { Text("Test OpenAI key") }
                    }
                    openAiTestResult?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (openAiKey.isNotBlank()) {
                        Text("OpenAI's own tools for OpenAI models. The app's tools (time zone, mini apps, skills, " +
                            "subagents, remote MCP) work with OpenAI too.", style = MaterialTheme.typography.labelMedium)
                        SettingsToggleRow("OpenAI web search (web_search)", "OpenAI searches the web and cites sources. " +
                            "OpenAI bills each search.", openAiWebSearch, { openAiWebSearch = it })
                        SettingsToggleRow("OpenAI image generation (image_generation)", "OpenAI's image model draws " +
                            "pictures in the chat. Uses the aspect ratio and quality below. OpenAI bills each image.",
                            openAiImageGeneration, { openAiImageGeneration = it })
                        if (openAiImageGeneration) {
                            ImageGenerationSettings(imageModel, { imageModel = it }, imageAspect, { imageAspect = it },
                                imageQuality, { imageQuality = it }, showModel = false)
                        }
                        SettingsToggleRow("OpenAI code interpreter (code_interpreter)", "Runs Python in an OpenAI " +
                            "container. OpenAI bills each container session.", openAiCodeInterpreter,
                            { openAiCodeInterpreter = it })
                        CopyableOutlinedTextField(openAiFileSearchStores, { openAiFileSearchStores = it; error = null },
                            label = { Text("OpenAI file search vector store IDs (optional)") },
                            supportingText = { Text("Up to 10 IDs like vs_abc123, separated by commas. Turns on " +
                                "file_search over files you uploaded to OpenAI.") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    if (initialConfig.openAiKey.isNotBlank() && openAiKey.isBlank()) {
                        Text("Removing the key hides OpenAI (direct) models.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedSection(title = "Appearance") {
                    val labels = LocalControlLabels.current
                    SettingsToggleRow("Show control labels", "Show names beside symbols.", labels.visible, labels.setVisible)
                }

                OutlinedSection(title = "Content moderation") {
                    SettingsToggleRow(
                        title = "On-device content moderation",
                        subtitle = "Checks prompts and AI responses locally before sending or displaying them. Off by default.",
                        checked = enableModeration,
                        onCheckedChange = { enableModeration = it }
                    )
                }

                OutlinedSection(title = "OpenRouter plugins") {
                    OpenRouterOnlyNote()
                    SettingsToggleRow("Response healing (response-healing)", "Repairs malformed JSON replies. OpenRouter " +
                        "applies it only to replies that aren't streamed and use a JSON response format.",
                        responseHealing, { responseHealing = it })
                    SettingsToggleRow("Context compression (context-compression)", "Trims the middle of a chat that is too " +
                        "long for the model. OpenRouter already does this for models with 8K context or less.",
                        contextCompression, { contextCompression = it })
                }

                OutlinedSection(title = "Agent tools") {
                    Text("Tools named openrouter:…, image generation and workspace files run on OpenRouter. With an " +
                        "OpenAI (direct) model they're skipped; use the OpenAI tools above instead. Decisions still " +
                        "works with an OpenAI model when an OpenRouter key is saved.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingsToggleRow(
                        title = "Hosted Linux Sandbox (openrouter:shell)",
                        subtitle = "Runs commands in a hosted Linux container",
                        checked = enableShell,
                        onCheckedChange = { enableShell = it }
                    )

                    SettingsToggleRow(
                        title = "Web Search (openrouter:web_search)",
                        subtitle = "Real-time search with cited URLs",
                        checked = enableWeb,
                        onCheckedChange = { enableWeb = it }
                    )

                    SettingsToggleRow(
                        title = "Date & Time (openrouter:datetime)",
                        subtitle = "Gives the model current UTC date/time",
                        checked = enableDateTime,
                        onCheckedChange = { enableDateTime = it }
                    )

                    SettingsToggleRow("Web fetch", "Reads pages supplied by URL.", webFetch, { webFetch = it })
                    SettingsToggleRow("Image generation", "Lets the agent draw pictures in the chat. Each image is billed by OpenRouter.",
                        imageGeneration, { imageGeneration = it })
                    if (imageGeneration) {
                        ImageGenerationSettings(imageModel, { imageModel = it }, imageAspect, { imageAspect = it },
                            imageQuality, { imageQuality = it })
                    }
                    SettingsToggleRow("Advisor (openrouter:advisor)", "Lets the model ask an advisor model for advice " +
                        "mid-answer. The advisor's tokens are billed.",
                        advisorTool, { advisorTool = it })
                    SettingsToggleRow("Fusion (openrouter:fusion)", "Asks a panel of models in parallel, with web search, " +
                        "and compares their answers. Each call runs several models, so it costs more.",
                        fusionTool, { fusionTool = it })
                    SettingsToggleRow("Model search (openrouter:experimental__search_models)",
                        "Lets the agent search OpenRouter's model list. Experimental.", modelSearchTool, { modelSearchTool = it })
                    SettingsToggleRow("Decisions (openrouter_decide)", "Lets the agent ask a decision model to pick an " +
                        "option, answer yes or no, or score something, including photos and recordings in the message. " +
                        "Each decision is billed by OpenRouter.", decisionsTool, { decisionsTool = it })
                    if (decisionsTool) {
                        CopyableOutlinedTextField(decisionModel, { decisionModel = it },
                            label = { Text("Decision model") },
                            supportingText = { Text("Blank uses $DEFAULT_DECISION_MODEL. Audio and video need a model " +
                                "that reads them, like cloudflare/clef-omni.") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    SettingsToggleRow("Workspace files tool", "Lets unfiled chats work with files in your OpenRouter workspace.",
                        workspaceFiles, { workspaceFiles = it })
                    SubagentsSettings(
                        json = toolsJson,
                        enabled = !working,
                        availableModels = agent.availableModels,
                        catalogLoading = agent.catalogLoading,
                        catalogError = agent.catalogError,
                        onRefreshModels = { agent.refreshModels() },
                        onChange = { toolsJson = it; error = null },
                    )
                    SettingsToggleRow("Device time zone", "Lets the agent read your time zone and UTC offset.", deviceTools,
                        { deviceTools = it })
                    SettingsToggleRow("Mini apps", "Lets the agent build small apps you can open, add to your home " +
                        "screen and share. Find them under Configure → Mini apps.", miniApps, { miniApps = it })

                    if (enableShell) {
                        SettingsToggleRow(
                            title = "Sandbox Outbound Network Allowlist",
                            subtitle = "Changing network access starts a fresh sandbox.",
                            checked = allowNetwork,
                            onCheckedChange = { allowNetwork = it }
                        )

                        if (allowNetwork) {
                            CopyableOutlinedTextField(
                                value = allowedDomains,
                                onValueChange = { allowedDomains = it },
                                label = { Text("Allowed domains, separated by commas") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                    }
                }

                OutlinedSection {
                    CopyableOutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = { Text("System instructions") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }

                OutlinedSection {
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "Hide advanced settings" else "Advanced settings")
                    }
                    if (advanced) {
                        val decimal = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                        CopyableOutlinedTextField(maxSteps, { maxSteps = it }, label = { Text("Agent step limit (1–30)") },
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        CopyableOutlinedTextField(maxCost, { maxCost = it }, label = { Text("Stop threshold in USD (optional)") },
                            supportingText = { Text("Checks accrued cost between steps.") }, keyboardOptions = decimal,
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        CopyableOutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature (optional)") },
                            supportingText = { Text("Applies when the selected model supports it.") }, keyboardOptions = decimal,
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        CopyableOutlinedTextField(mcpUrl, { mcpUrl = it }, label = { Text("Remote MCP HTTPS endpoint") },
                            supportingText = { Text("The app asks before calling a remote tool.") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (mcpUrl.isNotBlank()) {
                            OutlinedTextField(mcpToken, { mcpToken = it }, label = { Text("MCP bearer token (optional)") },
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                                singleLine = true)
                        }
                        CopyableOutlinedTextField(toolsJson, { toolsJson = it; error = null }, label = { Text("Additional server tools (JSON)") },
                            supportingText = { Text("Supports web fetch, model search, image generation, advisor, subagent, and fusion.") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 8)
                    }
                }

            }

            HorizontalDivider()
            error?.let { CopyableText(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !working, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(enabled = !working, onClick = {
                    error = when {
                        maxCost.isNotBlank() && updated.maxCost == null -> "Enter a valid cost threshold."
                        temperature.isNotBlank() && updated.temperature == null -> "Enter a valid temperature."
                        else -> updated.settingsError()
                    }
                    if (error != null) return@Button
                    working = true
                    scope.launch {
                        try {
                            onSave(updated)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.userMessage(apiKey.trim())
                        } finally {
                            working = false
                        }
                    }
                }, modifier = Modifier.weight(1f)) {
                    Text(if (working) "Working…" else "Save")
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageGenerationSettings(
    model: String,
    onModel: (String) -> Unit,
    aspect: String,
    onAspect: (String) -> Unit,
    quality: String,
    onQuality: (String) -> Unit,
    showModel: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showModel) {
            CopyableOutlinedTextField(model, onModel, label = { Text("Image model (optional)") },
                supportingText = { Text("Blank uses OpenRouter's default image model.") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        Text("Aspect ratio", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IMAGE_ASPECTS.forEach { option ->
                FilterChip(aspect == option, { onAspect(option) }, label = { Text(option.ifBlank { "Auto" }) })
            }
        }
        Text("Quality", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IMAGE_QUALITIES.forEach { option ->
                FilterChip(quality == option, { onQuality(option) },
                    label = { Text(option.ifBlank { "Auto" }.replaceFirstChar { it.uppercase() }) })
            }
        }
    }
}

/** Says a setting only applies to OpenRouter models, so OpenAI (direct) chats skip it. */
@Composable
internal fun OpenRouterOnlyNote(modifier: Modifier = Modifier) {
    Text(OPENROUTER_ONLY_NOTE, modifier = modifier, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
