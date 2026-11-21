package com.example.openrouteragent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal const val SUBAGENT_TOOL_TYPE = "openrouter:subagent"

private val configurableServerTools = setOf(
    "openrouter:web_fetch", "openrouter:image_generation", "openrouter:experimental__search_models",
    "openrouter:advisor", SUBAGENT_TOOL_TYPE, "openrouter:fusion"
)
private val workerServerTools = configurableServerTools - SUBAGENT_TOOL_TYPE +
    setOf("openrouter:shell", "openrouter:web_search", "openrouter:datetime", "openrouter:files")
private val subagentNamePattern = Regex("[A-Za-z0-9 _-]{1,64}")
private val workerToolTypePattern = Regex("openrouter:[a-z][a-z0-9_]*")
private val reasoningEfforts = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
private val basicSubagentFields = setOf("name", "model", "instructions")
private val subagentFields = basicSubagentFields + setOf(
    "tools", "maxToolCalls", "maxCompletionTokens", "temperature", "reasoning",
    "inheritFunctions", "inheritedFunctionNames",
)
private val reasoningFields = setOf("effort", "maxTokens")
private val workerToolFields = setOf("type", "parameters", "additionalProperties")
private val formattedJson = Json { prettyPrint = true }
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

internal fun JsonObject.stringValue(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.isSubagent(): Boolean = stringValue("type") == SUBAGENT_TOOL_TYPE

internal fun JsonObject.subagentParameters(): JsonObject? = get("parameters") as? JsonObject

internal fun readHostedTools(json: String): List<JsonObject>? {
    if (json.isBlank()) return emptyList()
    val tools = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return null
    return tools.map { it as? JsonObject ?: return null }
}

internal fun writeHostedTools(tools: List<JsonObject>): String =
    if (tools.isEmpty()) "" else formattedJson.encodeToString(JsonArray.serializer(), JsonArray(tools))

internal fun List<JsonObject>.withSubagentEdit(index: Int, worker: JsonObject?): List<JsonObject> =
    toMutableList().apply {
        when {
            worker == null -> removeAt(index)
            index == size -> add(worker)
            else -> this[index] = worker
        }
    }

internal fun hostedToolsError(json: String): String? {
    val tools = readHostedTools(json) ?: return "Enter a JSON array of server tool objects."
    val seenTypes = mutableSetOf<String>()
    val seenWorkers = mutableSetOf<String?>()
    var workerNumber = 0
    for (tool in tools) {
        val type = tool.stringValue("type")
        when {
            type == SUBAGENT_TOOL_TYPE -> {
                workerNumber++
                subagentError(tool)?.let { return "Subagent $workerNumber. $it" }
                val name = tool.subagentParameters()?.stringValue("name")?.trim()
                if (!seenWorkers.add(name)) {
                    return if (name == null) "Only one unnamed subagent is allowed."
                    else "Each subagent needs a unique name. “$name” is already in use."
                }
            }
            type == null || type !in configurableServerTools -> return "Enter a JSON array of supported server tools."
            !seenTypes.add(type) -> return "Each additional server tool other than subagent can appear once."
        }
    }
    return null
}

internal fun subagentError(tool: JsonObject, strict: Boolean = false): String? {
    if ("parameters" !in tool) return null
    val settings = tool.subagentParameters() ?: return "Enter a JSON object for subagent parameters."
    val name = settings.stringValue("name")?.trim()
    val model = settings.stringValue("model")?.trim()
    val reasoning = settings["reasoning"] as? JsonObject
    val workerTools = settings["tools"] as? JsonArray
    val inheritedNames = settings["inheritedFunctionNames"] as? JsonArray
    if (strict) {
        (settings.keys - subagentFields).firstOrNull()?.let { return "Unknown subagent setting “$it”." }
        (reasoning?.keys.orEmpty() - reasoningFields).firstOrNull()?.let { return "Unknown reasoning setting “$it”." }
        workerTools?.forEach { workerTool ->
            ((workerTool as? JsonObject)?.keys.orEmpty() - workerToolFields).firstOrNull()
                ?.let { return "Unknown worker tool setting “$it”." }
        }
    }
    return when {
        "name" in settings && (name == null || !subagentNamePattern.matches(name)) ->
            "Use 1–64 letters, digits, spaces, underscores, or dashes for the name."
        "model" in settings && (model.isNullOrEmpty() || model.any(Char::isWhitespace) || model == SUBAGENT_TOOL_TYPE) ->
            "Enter an OpenRouter worker model ID without spaces."
        "instructions" in settings && settings.stringValue("instructions") == null ->
            "Enter worker instructions as text."
        "tools" in settings && (workerTools == null || workerTools.any { !isWorkerServerTool(it as? JsonObject, strict) }) ->
            "Choose supported OpenRouter server tool objects. Use function inheritance for client functions."
        "maxToolCalls" in settings && !settings.wholeNumberIn("maxToolCalls", 1..25L) ->
            "Set maxToolCalls to a whole number from 1 to 25."
        "maxCompletionTokens" in settings && !settings.wholeNumberIn("maxCompletionTokens", 1..MAX_SAFE_INTEGER) ->
            "Set maxCompletionTokens to a positive whole number."
        "temperature" in settings && settings.numberValue("temperature")?.toDouble()?.let { it in 0.0..2.0 } != true ->
            "Set temperature to a number from 0 to 2."
        "reasoning" in settings && reasoning == null -> "Enter a JSON object for reasoning."
        reasoning != null && "effort" in reasoning && reasoning.stringValue("effort") !in reasoningEfforts ->
            "Choose a reasoning effort of none, minimal, low, medium, high, xhigh, or max."
        reasoning != null && "maxTokens" in reasoning && !reasoning.wholeNumberIn("maxTokens", 1..MAX_SAFE_INTEGER) ->
            "Set reasoning.maxTokens to a positive whole number."
        "inheritFunctions" in settings && (settings["inheritFunctions"] as? JsonPrimitive)
            ?.takeUnless { it.isString }?.booleanOrNull == null ->
            "Set inheritFunctions to true or false."
        "inheritedFunctionNames" in settings && (inheritedNames == null || inheritedNames.any {
            val nameValue = it as? JsonPrimitive
            nameValue == null || !nameValue.isString || nameValue.content.isBlank()
        }) -> "Enter inheritedFunctionNames as a list of nonblank function names."
        else -> null
    }
}

private fun isWorkerServerTool(tool: JsonObject?, strict: Boolean): Boolean {
    val type = tool?.stringValue("type") ?: return false
    val extendedFields = tool["additionalProperties"] as? JsonObject
    return type != SUBAGENT_TOOL_TYPE && workerToolTypePattern.matches(type) &&
        ("parameters" !in tool || tool["parameters"] is JsonObject) &&
        ("additionalProperties" !in tool || extendedFields != null) &&
        (!strict || (type in workerServerTools && extendedFields?.keys.orEmpty().none { it in workerToolFields }))
}

private fun JsonObject.numberValue(key: String) = (get(key) as? JsonPrimitive)
    ?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()

private fun JsonObject.wholeNumberIn(key: String, range: LongRange): Boolean =
    numberValue(key)?.let { number ->
        number >= range.first.toBigDecimal() && number <= range.last.toBigDecimal() &&
            number.stripTrailingZeros().scale() <= 0
    } == true

internal fun subagentAdvancedJson(parameters: JsonObject?): String {
    val advanced = parameters.orEmpty().filterKeys { it !in basicSubagentFields }
    return if (advanced.isEmpty()) "" else formattedJson.encodeToString(JsonObject.serializer(), JsonObject(advanced))
}

internal fun editSubagent(
    existing: JsonObject?,
    name: String,
    model: String,
    instructions: String,
    advancedJson: String,
): JsonObject {
    val advanced = if (advancedJson.isBlank()) emptyMap() else
        (runCatching { Json.parseToJsonElement(advancedJson) }.getOrNull() as? JsonObject)
            ?: throw IllegalArgumentException("Enter a JSON object for advanced subagent settings.")
    require(basicSubagentFields.none { it in advanced }) {
        "Set name, model, and instructions using the fields above."
    }
    val settings = advanced.toMutableMap()
    if (name.isNotBlank()) settings["name"] = JsonPrimitive(name.trim())
    if (model.isNotBlank()) settings["model"] = JsonPrimitive(model.trim())
    if (instructions.isNotBlank()) settings["instructions"] = JsonPrimitive(instructions)
    val tool = existing.orEmpty().toMutableMap()
    tool["type"] = JsonPrimitive(SUBAGENT_TOOL_TYPE)
    if (settings.isEmpty() && existing?.containsKey("parameters") != true) tool.remove("parameters")
    else tool["parameters"] = JsonObject(settings)
    return JsonObject(tool)
}
