package com.example.openrouteragent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject

internal data class ResourceChange(val config: AgentConfig, val result: JSONObject)

internal val RESERVED_CUSTOM_TOOL_NAMES = setOf("shared", "task", "create_subagent", "create_custom_tool",
    "create_skill", "enable_server_tool") + projectFileActions + githubActions

internal fun prepareResourceAction(config: AgentConfig, name: String, args: JSONObject): ResourceChange {
    val change = when (name) {
        "create_subagent" -> prepareSubagent(config, args)
        "create_custom_tool" -> prepareCustomTool(config, args)
        "create_skill" -> prepareSkill(config, args)
        "enable_server_tool" -> prepareServerTool(config, args)
        else -> throw IllegalArgumentException("Unsupported resource action.")
    }
    change.config.settingsError()?.let { throw IllegalArgumentException(it) }
    return change
}

private fun prepareSubagent(config: AgentConfig, args: JSONObject): ResourceChange {
    args.requireFields("name", "model", "instructions", "advancedJson")
    val name = if (args.has("name")) args.requiredText("name", 64) else null
    val model = if (args.has("model")) args.requiredText("model", 300) else ""
    val instructions = args.optionalText("instructions", 36_000).orEmpty()
    val advanced = args.optionalText("advancedJson", 16_000)
    require(advanced == null || advanced.isNotBlank()) {
        "Advanced subagent options must be a JSON object string."
    }
    val worker = editSubagent(null, name.orEmpty(), model, instructions, advanced.orEmpty())
    subagentError(worker, strict = true)?.let { throw IllegalArgumentException(it) }
    val hosted = requireNotNull(readHostedTools(config.extraToolsJson)) { "Check the existing server tools." }
    val updated = config.copy(extraToolsJson = writeHostedTools(hosted + worker))
    return ResourceChange(updated, resourceResult("subagent", name))
}

private fun prepareCustomTool(config: AgentConfig, args: JSONObject): ResourceChange {
    args.requireFields("name", "description", "schema", "handler", "value")
    val name = args.requiredText("name", 63)
    require(name !in RESERVED_CUSTOM_TOOL_NAMES && config.customTools.none { it.name == name }) {
        "Choose a unique custom tool name."
    }
    val tool = CustomAgentTool(name = name, description = args.requiredText("description", 1024),
        schema = args.optionalText("schema", 8192) ?: throw IllegalArgumentException("Enter an input schema."),
        handler = args.requiredText("handler", 12), value = args.optionalText("value", 12_000).orEmpty())
    require(tool.handler != "ask" || tool.value.isBlank()) { "The ask handler takes no value." }
    tool.problem(strictSchema = true)?.let { throw IllegalArgumentException(it) }
    val updated = config.copy(customTools = config.customTools + tool)
    return ResourceChange(updated, resourceResult("custom_tool", name))
}

private fun prepareSkill(config: AgentConfig, args: JSONObject): ResourceChange {
    args.requireFields("name", "description", "body", "pinned")
    val name = args.requiredText("name", 64)
    require(name != GUIDE_NAME && config.savedSkills.none { it.name == name }) { "Choose a unique skill name." }
    val pinned = if (args.has("pinned")) {
        args.opt("pinned") as? Boolean ?: throw IllegalArgumentException("Set pinned to true or false.")
    } else false
    val skill = SavedAgentSkill(name = name, description = args.requiredText("description", 1024),
        body = args.requiredText("body", 36_000), pinned = pinned)
    skill.problem()?.let { throw IllegalArgumentException(it) }
    val updated = config.copy(savedSkills = config.savedSkills + skill)
    return ResourceChange(updated, resourceResult("skill", name))
}

private fun prepareServerTool(config: AgentConfig, args: JSONObject): ResourceChange {
    args.requireFields("type")
    val type = args.requiredText("type", 64)
    val hosted = requireNotNull(readHostedTools(config.extraToolsJson)) { "Check the existing server tools." }
    require(hosted.none { it.stringValue("type") == type }) { "This server tool is already enabled." }
    val updated = when (type) {
        "openrouter:shell" -> config.copy(enableShellSandbox = true)
        "openrouter:web_search" -> config.copy(enableWebSearch = true)
        "openrouter:datetime" -> config.copy(enableDateTime = true)
        "openrouter:web_fetch" -> config.copy(webFetch = true)
        "openrouter:files" -> config.copy(enableWorkspaceFiles = true)
        "openrouter:image_generation", "openrouter:experimental__search_models",
        "openrouter:advisor", "openrouter:fusion" -> {
            val tool = JsonObject(mapOf("type" to JsonPrimitive(type)))
            config.copy(extraToolsJson = writeHostedTools(hosted + tool))
        }
        else -> throw IllegalArgumentException("Choose a supported OpenRouter server tool.")
    }
    require(updated != config) { "This server tool is already enabled." }
    return ResourceChange(updated, JSONObject().put("type", type))
}

private fun resourceResult(type: String, name: String?) = JSONObject().put("type", type).apply {
    if (name != null) put("name", name)
}

private fun JSONObject.requireFields(vararg allowed: String) {
    keys().asSequence().firstOrNull { it !in allowed }?.let { throw IllegalArgumentException("Unexpected field “$it”.") }
}

private fun JSONObject.optionalText(key: String, maxLength: Int): String? {
    if (!has(key)) return null
    val value = opt(key) as? String ?: throw IllegalArgumentException("Enter text for $key.")
    require(value.length <= maxLength) { "Limit $key to $maxLength characters." }
    return value
}

private fun JSONObject.requiredText(key: String, maxLength: Int): String {
    val value = (opt(key) as? String)?.trim().orEmpty()
    require(value.isNotEmpty() && value.length <= maxLength) { "Enter $key using 1–$maxLength characters." }
    return value
}
