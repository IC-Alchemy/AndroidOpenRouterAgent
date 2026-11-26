package com.example.openrouteragent

import org.json.JSONArray
import org.json.JSONObject

/** OpenRouter server tools with an on/off switch in Settings. Each is sent with OpenRouter's default settings. */
internal const val ADVISOR_TOOL = "openrouter:advisor"
internal const val FUSION_TOOL = "openrouter:fusion"
internal const val MODEL_SEARCH_TOOL = "openrouter:experimental__search_models"

/** OpenRouter plugins with an on/off switch in Settings. */
internal const val RESPONSE_HEALING_PLUGIN = "response-healing"
internal const val CONTEXT_COMPRESSION_PLUGIN = "context-compression"
private const val FILE_PARSER_PLUGIN = "file-parser"

/** The switched-on server tools, in the order they go in the request. */
internal fun AgentConfig.toggledServerTools(): List<String> = buildList {
    if (advisorTool) add(ADVISOR_TOOL)
    if (fusionTool) add(FUSION_TOOL)
    if (modelSearchTool) add(MODEL_SEARCH_TOOL)
}

/** Parameters for switched-on server tools that have a setting, such as the advisor's model, keyed by tool type. */
internal fun AgentConfig.toggledServerToolParameters(): Map<String, JSONObject> = buildMap {
    advisorModel.trim().takeIf { advisorTool && it.isNotEmpty() }?.let { put(ADVISOR_TOOL, JSONObject().put("model", it)) }
}

/** The switched-on plugins, in the order they go in the request. */
internal fun AgentConfig.toggledPlugins(): List<String> = buildList {
    if (responseHealing) add(RESPONSE_HEALING_PLUGIN)
    if (contextCompression) add(CONTEXT_COMPRESSION_PLUGIN)
}

/**
 * The runtime's additional server tools: the JSON list from Settings plus each switched-on tool. A tool already in the
 * JSON list keeps its own settings there, so it isn't added twice. Nothing is added when tools are off for this message.
 * [parameters] holds settings for a switched-on tool, such as the advisor's model.
 */
internal fun runExtraTools(
    extraToolsJson: String,
    toggled: List<String>,
    toolsOff: Boolean,
    parameters: Map<String, JSONObject> = emptyMap(),
): JSONArray {
    val tools = JSONArray(extraToolsJson.ifBlank { "[]" })
    if (toolsOff) return tools
    val present = (0 until tools.length()).mapNotNull { (tools.opt(it) as? JSONObject)?.optString("type") }.toSet()
    toggled.filterNot { it in present }.forEach { type ->
        tools.put(JSONObject().put("type", type).apply { parameters[type]?.let { put("parameters", it) } })
    }
    return tools
}

/**
 * The SDK's advanced JSON with each switched-on plugin added to `plugins`. A plugin already listed there keeps its own
 * settings. The runtime adds the free PDF parser only when no plugins are set, so when switches add plugins to a
 * message with a PDF the model can't read, the parser is added here instead. A `plugins` value that isn't a list is
 * left alone.
 */
internal fun advancedJsonWithPlugins(advancedJson: String, toggled: List<String>, needsPdfParser: Boolean): String {
    if (toggled.isEmpty()) return advancedJson
    val advanced = if (advancedJson.isBlank()) JSONObject() else JSONObject(advancedJson)
    if (advanced.has("plugins") && advanced.opt("plugins") !is JSONArray) return advancedJson
    val hadPlugins = advanced.has("plugins")
    val plugins = advanced.optJSONArray("plugins") ?: JSONArray()
    val present = (0 until plugins.length()).mapNotNull { (plugins.opt(it) as? JSONObject)?.optString("id") }.toSet()
    toggled.filterNot { it in present }.forEach { plugins.put(JSONObject().put("id", it)) }
    if (!hadPlugins && needsPdfParser && FILE_PARSER_PLUGIN !in present) {
        plugins.put(JSONObject().put("id", FILE_PARSER_PLUGIN).put("pdf", JSONObject().put("engine", "cloudflare-ai")))
    }
    return advanced.put("plugins", plugins).toString()
}
