package com.example.openrouteragent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

private fun choices(vararg values: String): List<Pair<String, String>> =
    listOf("" to "Provider default") + values.map { it to it.replace('_', ' ').replaceFirstChar(Char::uppercaseChar) }

private val ROUTING_VARIANTS = setOf("floor", "nitro", "exacto")
private val ROUTING_SORTS = setOf("price", "throughput", "latency")
private val ROUTING_OPTIONS = listOf("" to "Auto", "floor" to "Floor", "nitro" to "Nitro", "exacto" to "Exacto",
    "price" to "Price", "throughput" to "Throughput", "latency" to "Latency")

/** Shown above temperature, top P and the other sampling fields. */
internal const val SAMPLING_NOTE = "Most current models ignore these, including most reasoning models. Leave them " +
    "blank unless the model's page on OpenRouter lists the setting."

/** Short descriptions from OpenRouter's provider routing docs. */
private val ROUTING_DESCRIPTIONS = mapOf(
    "" to "OpenRouter's default. Balances price, speed, latency and reliability, and keeps a chat on one provider " +
        "while its prompt cache is warm. When tools are sent, Auto Exacto also moves providers with better " +
        "tool-calling results to the front.",
    "floor" to "Adds :floor to the model. Cheapest provider first, and cheaper flex-tier endpoints may be used. " +
        "Turns off Auto Exacto, so a chat stays on one provider and prompt caching works best.",
    "nitro" to "Adds :nitro to the model. Fastest provider first, and priority-tier endpoints may be used, " +
        "billed at their higher rate.",
    "exacto" to "Adds :exacto to the model. Always puts providers with the most reliable tool calling first, " +
        "even for messages without tools.",
    "price" to "Sorts providers by price only, without flex endpoints. Turns off Auto Exacto.",
    "throughput" to "Sorts providers by tokens per second only, without priority endpoints.",
    "latency" to "Sorts providers by time to first token.",
)

/**
 * The unsaved SDK, routing, limit and display settings shown in Settings. [build] checks them and returns the
 * config to save.
 */
@Stable
internal class SdkSettingsState(current: AgentConfig) {
    private val sdk = current.sdk
    var requireParams by mutableStateOf(sdk.requireParameters)
    var strictFinal by mutableStateOf(sdk.strictFinalResponse)
    var detectRepetition by mutableStateOf(sdk.detectRepetition)
    var showReasoning by mutableStateOf(sdk.showReasoning)
    var renderMarkdown by mutableStateOf(sdk.renderMarkdown)
    var saveReasoning by mutableStateOf(sdk.saveReasoning)
    var readVoiceReplies by mutableStateOf(sdk.readVoiceReplies)
    var discoverSkills by mutableStateOf(sdk.discoverSkills)
    var zdr by mutableStateOf(sdk.zdr)
    var distillable by mutableStateOf(sdk.distillableText)
    val fields = mutableStateMapOf(
        "temperature" to current.temperature?.toString().orEmpty(),
        "steps" to current.maxSteps.toString(), "cost" to current.maxCost?.toString().orEmpty(),
        "output" to sdk.outputTokens?.toString().orEmpty(), "budget" to sdk.maxRunTokens?.toString().orEmpty(),
        "topP" to sdk.topP?.toString().orEmpty(), "topK" to sdk.topK?.toString().orEmpty(),
        "frequency" to sdk.frequencyPenalty?.toString().orEmpty(), "presence" to sdk.presencePenalty?.toString().orEmpty(),
        "reasoning" to sdk.reasoning, "summary" to sdk.reasoningSummary, "format" to sdk.responseFormat,
        "verbosity" to sdk.verbosity, "toolChoice" to sdk.toolChoice, "parallel" to sdk.parallelTools,
        "tier" to sdk.serviceTier, "stops" to sdk.stopSequences, "models" to sdk.fallbackModels,
        "order" to sdk.providerOrder, "only" to sdk.providerOnly, "ignore" to sdk.providerIgnore,
        "route" to sdk.routingVariant.ifEmpty { sdk.providerSort }, "partition" to sdk.sortPartition,
        "quant" to sdk.quantizations, "minTp" to sdk.minThroughput?.toString().orEmpty(),
        "maxLat" to sdk.maxLatency?.toString().orEmpty(), "maxIn" to sdk.maxPromptPrice?.toString().orEmpty(),
        "maxOut" to sdk.maxCompletionPrice?.toString().orEmpty(),
        "fallback" to sdk.providerFallback, "data" to sdk.dataCollection,
        "json" to sdk.advancedJson, "turn" to sdk.followUpTurn.toString(), "followModel" to sdk.followUpModel,
        "followTemp" to sdk.followUpTemperature?.toString().orEmpty(),
        "followOutput" to sdk.followUpOutputTokens?.toString().orEmpty(), "followInstructions" to sdk.followUpInstructions,
        "timeout" to sdk.toolTimeoutSeconds?.toString().orEmpty(),
        "idle" to sdk.streamIdleSeconds?.toString().orEmpty(),
        "concurrency" to sdk.toolConcurrency?.toString().orEmpty(), "stopTool" to sdk.stopOnTool,
        "finalResponse" to sdk.finalResponse, "finalDirective" to sdk.finalDirective,
        "theme" to sdk.theme,
    )

    fun value(key: String): String = fields.getValue(key)

    private fun number(key: String, label: String): Int? = value(key).trim().takeIf { it.isNotEmpty() }?.let {
        it.toIntOrNull() ?: throw IllegalArgumentException("$label needs a whole number.")
    }

    private fun decimal(key: String, label: String): Double? = value(key).trim().takeIf { it.isNotEmpty() }?.let {
        it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: throw IllegalArgumentException("$label needs a number.")
    }

    /** The routing fields as they are now, for the live price line. */
    fun routingPreview(): SdkSettings = sdk.copy(
        providerSort = value("route").takeIf { it in ROUTING_SORTS }.orEmpty(),
        routingVariant = value("route").takeIf { it in ROUTING_VARIANTS }.orEmpty(),
        providerOrder = value("order").trim(), providerOnly = value("only").trim(),
        providerIgnore = value("ignore").trim(), quantizations = value("quant").trim(),
        maxPromptPrice = value("maxIn").trim().toDoubleOrNull(),
        maxCompletionPrice = value("maxOut").trim().toDoubleOrNull())

    /** The section that holds [key], so Save can show the field with a problem. */
    fun sectionOf(message: String): String? = when {
        listOf("Output limit", "Top P", "Top K", "Frequency penalty", "Presence penalty", "Temperature")
            .any { message.startsWith(it) } -> "model"
        message.startsWith("Follow-up") -> "followup"
        listOf("Minimum throughput", "Maximum latency", "Maximum input price", "Maximum output price")
            .any { message.startsWith(it) } || message.startsWith("Model ") -> "routing"
        else -> null
    }

    /**
     * [base] with these settings applied. Throws [IllegalArgumentException] with a message for the user when a field
     * is not valid, or when a chosen model is missing from [catalog].
     */
    fun build(base: AgentConfig, catalog: List<CatalogModel>): AgentConfig {
        val prefs = base.sdk.copy(fallbackModels = modelIds(value("models")).joinToString(","),
            outputTokens = number("output", "Output limit"),
            maxRunTokens = number("budget", "Run token threshold"), topP = decimal("topP", "Top P"),
            topK = number("topK", "Top K"), frequencyPenalty = decimal("frequency", "Frequency penalty"),
            presencePenalty = decimal("presence", "Presence penalty"), reasoning = value("reasoning"),
            reasoningSummary = value("summary"), responseFormat = value("format"), verbosity = value("verbosity"),
            toolChoice = value("toolChoice"), parallelTools = value("parallel"), serviceTier = value("tier"),
            stopSequences = value("stops"), providerOrder = value("order").trim(), providerOnly = value("only").trim(),
            providerIgnore = value("ignore").trim(),
            providerSort = value("route").takeIf { it in ROUTING_SORTS }.orEmpty(),
            routingVariant = value("route").takeIf { it in ROUTING_VARIANTS }.orEmpty(),
            sortPartition = value("partition"), zdr = zdr, distillableText = distillable,
            quantizations = value("quant").split(',').map(String::trim).filter(String::isNotEmpty).joinToString(","),
            minThroughput = decimal("minTp", "Minimum throughput"), maxLatency = decimal("maxLat", "Maximum latency"),
            maxPromptPrice = decimal("maxIn", "Maximum input price"),
            maxCompletionPrice = decimal("maxOut", "Maximum output price"),
            providerFallback = value("fallback"),
            requireParameters = requireParams, dataCollection = value("data"), advancedJson = value("json").trim(),
            followUpTurn = number("turn", "Follow-up step") ?: 2, followUpModel = value("followModel").trim(),
            followUpTemperature = decimal("followTemp", "Follow-up temperature"),
            followUpOutputTokens = number("followOutput", "Follow-up output limit"),
            followUpInstructions = value("followInstructions").trim(), showReasoning = showReasoning,
            renderMarkdown = renderMarkdown, saveReasoning = saveReasoning, readVoiceReplies = readVoiceReplies,
            discoverSkills = discoverSkills, theme = value("theme"), detectRepetition = detectRepetition,
            strictFinalResponse = strictFinal, stopOnTool = value("stopTool").trim(), finalResponse = value("finalResponse"),
            finalDirective = value("finalDirective").trim(), toolTimeoutSeconds = number("timeout", "Tool timeout"),
            streamIdleSeconds = number("idle", "Stream idle timeout"),
            toolConcurrency = number("concurrency", "Tool concurrency"))
        prefs.problem()?.let { throw IllegalArgumentException(it) }
        val ids = (modelIds(prefs.fallbackModels) + prefs.followUpModel).filter(String::isNotEmpty).distinct()
        if (ids.isNotEmpty() && catalog.isNotEmpty()) {
            val missing = ids.firstOrNull { id -> catalog.none { it.id == id } }
            require(missing == null) { "Model $missing is absent from your OpenRouter catalog." }
        }
        return base.copy(sdk = prefs, maxSteps = number("steps", "Step limit") ?: 12,
            maxCost = decimal("cost", "Cost threshold"), temperature = decimal("temperature", "Temperature"))
    }
}

@Composable
internal fun rememberSdkSettingsState(current: AgentConfig): SdkSettingsState = remember { SdkSettingsState(current) }

@Composable
private fun ColumnScope.Field(state: SdkSettingsState, key: String, title: String, hint: String? = null, lines: Int = 1) {
    EditField(title, state.value(key), { state.fields[key] = it }, lines, hint)
}

@Composable
private fun Options(
    state: SdkSettingsState,
    key: String,
    title: String,
    options: List<Pair<String, String>>,
    descriptions: Map<String, String> = emptyMap(),
) {
    OutlinedSection(title = title) {
        SettingsChoices(options, state.value(key)) { state.fields[key] = it }
        descriptions[state.value(key)]?.let { Note(it) }
    }
}

@Composable
internal fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Subheading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

/** Which providers serve each request, the fallback models, and provider filters. */
@Composable
internal fun ColumnScope.RoutingSettings(
    state: SdkSettingsState,
    choices: ModelChoices,
    showOpenRouterOnlyNote: Boolean,
    routedPrice: (suspend (SdkSettings) -> String?)?,
) {
    if (showOpenRouterOnlyNote) OpenRouterOnlyNote()
    Options(state, "route", "Provider routing", ROUTING_OPTIONS, ROUTING_DESCRIPTIONS)
    if (routedPrice != null) {
        // The price follows the unsaved routing fields, so it changes as they are edited.
        val preview = state.routingPreview()
        RoutedPriceLine(preview) { routedPrice(preview) }
    }
    ModelListField("Fallback models", state.value("models"), { state.fields["models"] = it }, choices,
        hint = "Tried in this order if the main model fails. The list shows the models your OpenRouter account allows.")
    if (state.value("route") in ROUTING_SORTS && state.value("models").isNotBlank()) {
        Options(state, "partition", "Sort across fallback models", listOf("" to "Per model", "none" to "All together"),
            mapOf("" to "Tries every provider of your main model first, then the fallback models.",
                "none" to "Sorts all endpoints of all models together, so a fallback model can go first."))
    }
    Options(state, "fallback", "Provider fallbacks", choices("on", "off"), mapOf(
        "on" to "Use backup providers when the first one is down or busy.",
        "off" to "Fail instead of using a provider you did not list. Use with Provider order."))
    Field(state, "order", "Provider order", "Comma-separated provider slugs to try first, such as anthropic, openai. " +
        "Overrides default routing.")
    Field(state, "only", "Allow only providers", "Comma-separated slugs. Every other provider is skipped.")
    Field(state, "ignore", "Exclude providers", "Comma-separated slugs to never use.")
    HorizontalDivider()
    Subheading("Performance preferences")
    Note("Providers that miss these are moved to the back, not removed, so requests still run.")
    Field(state, "minTp", "Preferred minimum throughput (tokens/s)", "Compared with each provider's median speed.")
    Field(state, "maxLat", "Preferred maximum latency (seconds)", "Compared with each provider's median time to first token.")
    HorizontalDivider()
    Subheading("Filters")
    Note("Filters remove providers. A request fails if no provider is left.")
    Field(state, "maxIn", "Maximum input price (USD per million tokens)")
    Field(state, "maxOut", "Maximum output price (USD per million tokens)")
    Field(state, "quant", "Allowed quantizations", "Comma-separated, for open-weight models: " +
        QUANTIZATIONS.joinToString() + ". Lower bit counts are cheaper but can be less accurate.")
    Options(state, "data", "Provider data collection", choices("allow", "deny"), mapOf(
        "allow" to "Any provider may be used, including ones that may store or train on prompts.",
        "deny" to "Only providers that do not store your data or train on it."))
    ToggleLine("Zero data retention only", state.zdr) { state.zdr = it }
    Note("Only providers that keep no copy of your prompts or replies.")
    ToggleLine("Distillable models only", state.distillable) { state.distillable = it }
    Note("Only models whose licence lets you train other models on their output.")
    ToggleLine("Require all requested parameters", state.requireParams) { state.requireParams = it }
    Note("Skip providers that would ignore a setting you chose, such as a response format.")
    Options(state, "tier", "Service tier", choices("auto", "default", "flex", "priority", "scale", "ultrafast"), mapOf(
        "flex" to "Slower or queued endpoints at a lower price, where offered.",
        "priority" to "Faster, higher-priority endpoints at a higher price, where offered."))
}

/** Reasoning, output and format first. Sampling fields that most models ignore come last. */
@Composable
internal fun ColumnScope.ModelBehaviorSettings(state: SdkSettingsState) {
    Options(state, "reasoning", "Reasoning effort", choices("off", "minimal", "low", "medium", "high", "xhigh"))
    Options(state, "summary", "Request reasoning summaries", choices("auto", "concise", "detailed"))
    Options(state, "verbosity", "Verbosity", choices("low", "medium", "high"))
    Options(state, "format", "Response format", choices("text", "json_object"))
    Field(state, "output", "Output tokens per response")
    HorizontalDivider()
    Subheading("Sampling (rarely supported)")
    Note(SAMPLING_NOTE)
    Field(state, "temperature", "Temperature")
    Field(state, "topP", "Top P")
    Field(state, "topK", "Top K")
    Field(state, "frequency", "Frequency penalty")
    Field(state, "presence", "Presence penalty")
    Field(state, "stops", "Stop sequences", "Up to four sequences, one per line.", 3)
}

/** Overrides for the later requests inside one agent run. */
@Composable
internal fun ColumnScope.FollowUpSettings(state: SdkSettingsState, choices: ModelChoices) {
    Note("Apply these overrides to follow-up requests inside the current agent run.")
    Field(state, "turn", "Start at step (2–30)")
    ModelField("Follow-up model", state.value("followModel"), { state.fields["followModel"] = it }, choices,
        emptyLabel = "Same as the chat's model")
    Field(state, "followOutput", "Follow-up output tokens")
    Field(state, "followInstructions", "Additional instructions", lines = 5)
    Field(state, "followTemp", "Follow-up temperature", SAMPLING_NOTE)
}

/** Step, cost and time limits, stop conditions and raw SDK parameters. */
@Composable
internal fun ColumnScope.RunLimitSettings(state: SdkSettingsState) {
    Field(state, "steps", "Agent step limit (1–30)")
    Field(state, "cost", "Cost threshold (USD)", "Checked between steps.")
    Field(state, "budget", "Total token threshold", "Checked between steps.")
    Field(state, "timeout", "Tool deadline (seconds)", "Bounds each local or MCP tool call.")
    Field(state, "idle", "Stream idle timeout (seconds)",
        "Fails a run after this long with no data from OpenRouter. Default 90, range 30–600.")
    Field(state, "concurrency", "Maximum concurrent tool calls")
    Field(state, "stopTool", "Stop when this tool is called", "Use the exact name of an SDK function tool.")
    ToggleLine("Detect repeated tool calls and replies", state.detectRepetition) { state.detectRepetition = it }
    Options(state, "finalResponse", "Final answer when a stop condition is met", listOf("" to "SDK default",
        "off" to "Skip final turn", "custom" to "Custom directive"))
    if (state.value("finalResponse") == "custom") Field(state, "finalDirective", "Final-answer directive", lines = 3)
    ToggleLine("Require a final response after tools", state.strictFinal) { state.strictFinal = it }
    Options(state, "toolChoice", "Tool choice", choices("auto", "none", "required"))
    Options(state, "parallel", "Parallel tool calls", choices("on", "off"))
    Field(state, "json", "Additional SDK parameters (JSON)",
        "Examples include provider, reasoning, text, metadata, plugins, truncation, promptCacheKey and " +
            "promptCacheOptions. Use SDK camelCase keys.", 6)
}

/** Theme and how replies and reasoning show in the chat. */
@Composable
internal fun ColumnScope.ReplyDisplaySettings(state: SdkSettingsState) {
    Options(state, "theme", "App theme", listOf("oled" to "OLED black", "system" to "System", "light" to "Light"))
    ToggleLine("Render Markdown in replies", state.renderMarkdown) { state.renderMarkdown = it }
    ToggleLine("Show streamed reasoning", state.showReasoning) { state.showReasoning = it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            ToggleLine("Save reasoning in chat history", state.saveReasoning) { state.saveReasoning = it }
        }
        StudioInfoButton("Saved reasoning") {
            Text("Includes thinking in new saved replies and interrupted replies.")
        }
    }
    ToggleLine("Read voice replies aloud", state.readVoiceReplies) { state.readVoiceReplies = it }
}
