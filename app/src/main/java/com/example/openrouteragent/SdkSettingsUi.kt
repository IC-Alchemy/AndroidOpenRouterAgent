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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun choices(vararg values: String): List<Pair<String, String>> =
    listOf("" to "Provider default") + values.map { it to it.replace('_', ' ').replaceFirstChar(Char::uppercaseChar) }

private val ROUTING_VARIANTS = setOf("floor", "nitro", "exacto")
private val ROUTING_SORTS = setOf("price", "throughput", "latency")
private val ROUTING_OPTIONS = listOf("" to "Auto", "floor" to "Floor", "nitro" to "Nitro", "exacto" to "Exacto",
    "price" to "Price", "throughput" to "Throughput", "latency" to "Latency")

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SdkSettingsSheet(
    current: AgentConfig,
    catalog: List<CatalogModel>,
    onDismiss: () -> Unit,
    onSave: suspend (AgentConfig) -> Unit,
) {
    val sdk = current.sdk
    var section by rememberSaveable { mutableStateOf("generation") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var requireParams by remember { mutableStateOf(sdk.requireParameters) }
    var strictFinal by remember { mutableStateOf(sdk.strictFinalResponse) }
    var detectRepetition by remember { mutableStateOf(sdk.detectRepetition) }
    var showReasoning by remember { mutableStateOf(sdk.showReasoning) }
    var renderMarkdown by remember { mutableStateOf(sdk.renderMarkdown) }
    var discoverSkills by remember { mutableStateOf(sdk.discoverSkills) }
    var zdr by remember { mutableStateOf(sdk.zdr) }
    var distillable by remember { mutableStateOf(sdk.distillableText) }
    val scope = rememberCoroutineScope()
    val state = remember {
        mutableStateMapOf(
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
    }
    fun value(key: String) = state.getValue(key)
    fun number(key: String, label: String): Int? = value(key).trim().takeIf { it.isNotEmpty() }?.let {
        it.toIntOrNull() ?: throw IllegalArgumentException("$label needs a whole number.")
    }
    fun decimal(key: String, label: String): Double? = value(key).trim().takeIf { it.isNotEmpty() }?.let {
        it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: throw IllegalArgumentException("$label needs a number.")
    }

    fun save() {
        error = null
        val updated = try {
            val prefs = sdk.copy(fallbackModels = value("models").trim(), outputTokens = number("output", "Output limit"),
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
                quantizations = value("quant").split(',').map(String::trim).filter(String::isNotEmpty)
                    .joinToString(","),
                minThroughput = decimal("minTp", "Minimum throughput"), maxLatency = decimal("maxLat", "Maximum latency"),
                maxPromptPrice = decimal("maxIn", "Maximum input price"),
                maxCompletionPrice = decimal("maxOut", "Maximum output price"),
                providerFallback = value("fallback"),
                requireParameters = requireParams, dataCollection = value("data"), advancedJson = value("json").trim(),
                followUpTurn = number("turn", "Follow-up step") ?: 2, followUpModel = value("followModel").trim(),
                followUpTemperature = decimal("followTemp", "Follow-up temperature"),
                followUpOutputTokens = number("followOutput", "Follow-up output limit"),
                followUpInstructions = value("followInstructions").trim(), showReasoning = showReasoning,
                renderMarkdown = renderMarkdown,
                discoverSkills = discoverSkills, theme = value("theme"), detectRepetition = detectRepetition,
                strictFinalResponse = strictFinal, stopOnTool = value("stopTool").trim(), finalResponse = value("finalResponse"),
                finalDirective = value("finalDirective").trim(), toolTimeoutSeconds = number("timeout", "Tool timeout"),
                streamIdleSeconds = number("idle", "Stream idle timeout"),
                toolConcurrency = number("concurrency", "Tool concurrency"))
            prefs.problem()?.let { throw IllegalArgumentException(it) }
            val ids = (prefs.fallbackModels.split(',') + prefs.followUpModel).map(String::trim)
                .filter(String::isNotEmpty).distinct()
            if (ids.isNotEmpty() && catalog.isNotEmpty()) {
                val missing = ids.firstOrNull { id -> catalog.none { it.id == id } }
                require(missing == null) { "Model $missing is absent from your OpenRouter catalog." }
            }
            current.copy(sdk = prefs, maxSteps = number("steps", "Step limit") ?: 12,
                maxCost = decimal("cost", "Cost threshold"), temperature = decimal("temperature", "Temperature"))
        } catch (e: IllegalArgumentException) {
            error = e.message
            return
        }
        updated.settingsError()?.let { error = it; return }
        working = true
        scope.launch {
            try {
                onSave(updated)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Android could not save these settings."
            } finally {
                working = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.93f).imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("SDK settings", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, enabled = !working) { Text("Close") }
            }
            ChoiceStrip(listOf("generation" to "Generation", "routing" to "Routing", "turn" to "Follow-up",
                "advanced" to "Advanced", "display" to "Display"), section) { section = it }
            HorizontalDivider()
            val scroll = rememberScrollState()
            androidx.compose.runtime.LaunchedEffect(section) { scroll.scrollTo(0) }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                @Composable
                fun Field(key: String, title: String, hint: String? = null, lines: Int = 1) {
                    EditField(title, value(key), { state[key] = it }, lines, hint)
                }
                @Composable
                fun Options(key: String, title: String, options: List<Pair<String, String>>,
                            descriptions: Map<String, String> = emptyMap()) {
                    OutlinedSection(title = title) {
                        ChoiceStrip(options, value(key)) { state[key] = it }
                        descriptions[value(key)]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                @Composable
                fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (section) {
                    "generation" -> {
                        Field("temperature", "Temperature")
                        Field("output", "Output tokens per response")
                        Field("topP", "Top P")
                        Field("topK", "Top K")
                        Field("frequency", "Frequency penalty")
                        Field("presence", "Presence penalty")
                        Options("reasoning", "Reasoning effort", choices("off", "minimal", "low", "medium", "high", "xhigh"))
                        Options("summary", "Request reasoning summaries", choices("auto", "concise", "detailed"))
                        Options("format", "Response format", choices("text", "json_object"))
                        Options("verbosity", "Verbosity", choices("low", "medium", "high"))
                        Field("stops", "Stop sequences", "Up to four sequences, one per line.", 3)
                    }
                    "routing" -> {
                        Options("route", "Provider routing", ROUTING_OPTIONS, ROUTING_DESCRIPTIONS)
                        if (value("route") in ROUTING_SORTS && value("models").isNotBlank()) {
                            Options("partition", "Sort across fallback models", listOf("" to "Per model", "none" to "All together"),
                                mapOf("" to "Tries every provider of your main model first, then the fallback models.",
                                    "none" to "Sorts all endpoints of all models together, so a fallback model can go first."))
                        }
                        Field("models", "Fallback model IDs", "Comma-separated OpenRouter model IDs, tried if the main model fails.")
                        Field("order", "Provider order", "Comma-separated provider slugs to try first, such as anthropic, openai. " +
                            "Overrides default routing.")
                        Field("only", "Allow only providers", "Comma-separated slugs. Every other provider is skipped.")
                        Field("ignore", "Exclude providers", "Comma-separated slugs to never use.")
                        Options("fallback", "Provider fallbacks", choices("on", "off"), mapOf(
                            "on" to "Use backup providers when the first one is down or busy.",
                            "off" to "Fail instead of using a provider you did not list. Use with Provider order."))
                        HorizontalDivider()
                        Text("Performance preferences", style = MaterialTheme.typography.titleSmall)
                        Note("Providers that miss these are moved to the back, not removed, so requests still run.")
                        Field("minTp", "Preferred minimum throughput (tokens/s)", "Compared with each provider's median speed.")
                        Field("maxLat", "Preferred maximum latency (seconds)", "Compared with each provider's median time to first token.")
                        HorizontalDivider()
                        Text("Filters", style = MaterialTheme.typography.titleSmall)
                        Note("Filters remove providers. A request fails if no provider is left.")
                        Field("maxIn", "Maximum input price (USD per million tokens)")
                        Field("maxOut", "Maximum output price (USD per million tokens)")
                        Field("quant", "Allowed quantizations", "Comma-separated, for open-weight models: " +
                            QUANTIZATIONS.joinToString() + ". Lower bit counts are cheaper but can be less accurate.")
                        Options("data", "Provider data collection", choices("allow", "deny"), mapOf(
                            "allow" to "Any provider may be used, including ones that may store or train on prompts.",
                            "deny" to "Only providers that do not store your data or train on it."))
                        ToggleLine("Zero data retention only", zdr) { zdr = it }
                        Note("Only providers that keep no copy of your prompts or replies.")
                        ToggleLine("Distillable models only", distillable) { distillable = it }
                        Note("Only models whose licence lets you train other models on their output.")
                        ToggleLine("Require all requested parameters", requireParams) { requireParams = it }
                        Note("Skip providers that would ignore a setting you chose, such as a response format.")
                        Options("tier", "Service tier", choices("auto", "default", "flex", "priority", "scale", "ultrafast"), mapOf(
                            "flex" to "Slower or queued endpoints at a lower price, where offered.",
                            "priority" to "Faster, higher-priority endpoints at a higher price, where offered."))
                    }
                    "turn" -> {
                        Text("Apply these overrides to follow-up requests inside the current agent run.",
                            style = MaterialTheme.typography.bodySmall)
                        Field("turn", "Start at step (2–30)")
                        Field("followModel", "Follow-up OpenRouter model ID")
                        Field("followTemp", "Follow-up temperature")
                        Field("followOutput", "Follow-up output tokens")
                        Field("followInstructions", "Additional instructions", lines = 5)
                    }
                    "advanced" -> {
                        Field("steps", "Agent step limit (1–30)")
                        Field("cost", "Cost threshold (USD)", "Checked between steps.")
                        Field("budget", "Total token threshold", "Checked between steps.")
                        Field("stopTool", "Stop when this tool is called", "Use the exact name of an SDK function tool.")
                        Field("timeout", "Tool deadline (seconds)", "Bounds each local or MCP tool call.")
                        Field("idle", "Stream idle timeout (seconds)",
                            "Fails a run after this long with no data from OpenRouter. Default 90, range 30–600.")
                        Field("concurrency", "Maximum concurrent tool calls")
                        ToggleLine("Detect repeated tool calls and replies", detectRepetition) { detectRepetition = it }
                        Options("finalResponse", "Final answer when a stop condition is met", listOf("" to "SDK default",
                            "off" to "Skip final turn", "custom" to "Custom directive"))
                        if (value("finalResponse") == "custom") Field("finalDirective", "Final-answer directive", lines = 3)
                        ToggleLine("Require a final response after tools", strictFinal) { strictFinal = it }
                        Options("toolChoice", "Tool choice", choices("auto", "none", "required"))
                        Options("parallel", "Parallel tool calls", choices("on", "off"))
                        Field("json", "Additional SDK parameters (JSON)",
                            "Examples include provider, reasoning, text, metadata, plugins, truncation, promptCacheKey and promptCacheOptions. Use SDK camelCase keys.", 6)
                    }
                    "display" -> {
                        Options("theme", "App theme", listOf("oled" to "OLED black", "system" to "System", "light" to "Light"))
                        ToggleLine("Show streamed reasoning", showReasoning) { showReasoning = it }
                        ToggleLine("Render Markdown in replies", renderMarkdown) { renderMarkdown = it }
                        ToggleLine("Let the agent discover saved skills", discoverSkills) { discoverSkills = it }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            Button(onClick = ::save, enabled = !working, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(if (working) "Saving…" else "Save SDK settings")
            }
        }
    }
}
