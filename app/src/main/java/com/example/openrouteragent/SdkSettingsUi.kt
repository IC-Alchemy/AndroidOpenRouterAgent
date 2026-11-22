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
    var discoverSkills by remember { mutableStateOf(sdk.discoverSkills) }
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
            "sort" to sdk.providerSort, "fallback" to sdk.providerFallback, "data" to sdk.dataCollection,
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
                providerIgnore = value("ignore").trim(), providerSort = value("sort"), providerFallback = value("fallback"),
                requireParameters = requireParams, dataCollection = value("data"), advancedJson = value("json").trim(),
                followUpTurn = number("turn", "Follow-up step") ?: 2, followUpModel = value("followModel").trim(),
                followUpTemperature = decimal("followTemp", "Follow-up temperature"),
                followUpOutputTokens = number("followOutput", "Follow-up output limit"),
                followUpInstructions = value("followInstructions").trim(), showReasoning = showReasoning,
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
                fun Options(key: String, title: String, options: List<Pair<String, String>>) {
                    OutlinedSection(title = title) {
                        ChoiceStrip(options, value(key)) { state[key] = it }
                    }
                }
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
                        Field("models", "Fallback model IDs", "Comma-separated OpenRouter model IDs.")
                        Field("order", "Provider order", "Comma-separated provider slugs.")
                        Field("only", "Allow only providers")
                        Field("ignore", "Exclude providers")
                        Options("sort", "Provider sorting", choices("price", "throughput", "latency"))
                        Options("fallback", "Provider fallbacks", choices("on", "off"))
                        Options("data", "Provider data collection", choices("allow", "deny"))
                        Options("tier", "Service tier", choices("auto", "default", "flex", "priority", "scale", "ultrafast"))
                        ToggleLine("Require all requested parameters", requireParams) { requireParams = it }
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
