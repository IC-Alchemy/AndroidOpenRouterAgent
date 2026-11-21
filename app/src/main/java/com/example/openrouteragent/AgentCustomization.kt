package com.example.openrouteragent

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.UUID

@Serializable
data class CustomAgentTool(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val schema: String = """{"type":"object","properties":{}}""",
    val handler: String = "template",
    val value: String = "",
    val enabled: Boolean = true,
) {
    fun problem(): String? = when {
        !Regex("[a-z][a-z0-9_]{0,62}").matches(name) || name.startsWith("remote_") ||
            name in setOf("load_skill", "device_time_zone") ->
            "Use a unique name with lowercase letters, digits, and underscores."
        description.isBlank() || description.length > 1024 -> "Enter a purpose of 1–1,024 characters."
        schema.length > 8192 || !validInputSchema(schema) -> "Enter a JSON object schema with supported properties."
        handler == "webhook" && value.toHttpUrlOrNull()?.let {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
                it.query == null && it.fragment == null
        } != true -> "Enter a fixed HTTPS endpoint. Use a URL without credentials, query parameters, or a fragment."
        handler == "template" && (value.isBlank() || value.length > 12_000) ->
            "Enter a response template of up to 12,000 characters."
        handler !in setOf("template", "ask", "webhook") -> "Choose a tool handler."
        else -> null
    }
}

private fun validInputSchema(text: String): Boolean = runCatching {
    fun field(item: JSONObject, depth: Int): Boolean {
        if (depth > 5) return false
        return when (item.optString("type")) {
            "string", "integer", "number", "boolean" -> true
            "array" -> item.optJSONObject("items")?.let { field(it, depth + 1) } == true
            "object" -> {
                val properties = item.optJSONObject("properties") ?: return false
                val required = item.optJSONArray("required")
                properties.length() <= 24 && properties.keys().asSequence().all { key ->
                    Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}").matches(key) &&
                        properties.optJSONObject(key)?.let { field(it, depth + 1) } == true
                } && (required == null || (0 until required.length()).all { properties.has(required.getString(it)) })
            }
            else -> false
        }
    }
    val schema = JSONObject(text)
    schema.optString("type") == "object" && field(schema, 0)
}.getOrDefault(false)

@Serializable
data class SavedAgentSkill(
    val name: String = "",
    val description: String = "",
    val body: String = "",
    val enabled: Boolean = true,
    val pinned: Boolean = false,
) {
    fun problem(): String? = when {
        name.length > 64 || !Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(name) ->
            "Use a lowercase skill name with digits or single hyphens."
        description.isBlank() || description.length > 1024 -> "Describe when to use the skill in 1–1,024 characters."
        body.isBlank() || body.length > 36_000 -> "Enter Markdown instructions of up to 36,000 characters."
        else -> null
    }

    fun markdown(): String = "---\nname: ${JSONObject.quote(name)}\ndescription: ${JSONObject.quote(description)}\n---\n\n${body.trim()}\n"
}

internal const val GUIDE_NAME = "openrouter-app-guide"
internal const val SKILL_IMPORT_BYTES = 150_000

internal fun parseSkill(markdown: String): SavedAgentSkill {
    require(markdown.length <= SKILL_IMPORT_BYTES) { "This SKILL.md is too large." }
    val text = markdown.removePrefix("\uFEFF").replace("\r\n", "\n")
    val frontmatter = Regex("\\A---[ \\t]*\\n([\\s\\S]*?)\\n---[ \\t]*(?:\\n|\\z)").find(text)
        ?: throw IllegalArgumentException("SKILL.md needs YAML frontmatter with a name and description.")
    val options = LoaderOptions().apply {
        isAllowDuplicateKeys = false
        maxAliasesForCollections = 0
        codePointLimit = SKILL_IMPORT_BYTES
        nestingDepthLimit = 12
    }
    val fields = Yaml(SafeConstructor(options)).load<Any>(frontmatter.groupValues[1]) as? Map<*, *>
        ?: throw IllegalArgumentException("The skill frontmatter must be a YAML mapping.")
    val skill = SavedAgentSkill((fields["name"] as? String).orEmpty(),
        (fields["description"] as? String).orEmpty(), text.substring(frontmatter.range.last + 1).trim(),
        enabled = false)
    skill.problem()?.let { throw IllegalArgumentException(it) }
    return skill
}

@Serializable
data class SdkSettings(
    val fallbackModels: String = "",
    val outputTokens: Int? = null,
    val maxRunTokens: Int? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val reasoning: String = "",
    val reasoningSummary: String = "",
    val responseFormat: String = "",
    val verbosity: String = "",
    val toolChoice: String = "",
    val parallelTools: String = "",
    val serviceTier: String = "",
    val stopSequences: String = "",
    val providerOrder: String = "",
    val providerOnly: String = "",
    val providerIgnore: String = "",
    val providerSort: String = "",
    val providerFallback: String = "",
    val requireParameters: Boolean = false,
    val dataCollection: String = "",
    val advancedJson: String = "",
    val followUpTurn: Int = 2,
    val followUpModel: String = "",
    val followUpTemperature: Double? = null,
    val followUpOutputTokens: Int? = null,
    val followUpInstructions: String = "",
    val stopOnTool: String = "",
    val finalResponse: String = "",
    val finalDirective: String = "",
    val strictFinalResponse: Boolean = false,
    val detectRepetition: Boolean = true,
    val toolTimeoutSeconds: Int? = null,
    val toolConcurrency: Int? = null,
    val showReasoning: Boolean = true,
    val discoverSkills: Boolean = true,
    val theme: String = "oled",
) {
    fun problem(): String? = when {
        listOf(outputTokens, maxRunTokens, followUpOutputTokens).any { it != null && it !in 1..2_000_000 } ->
            "Enter positive token limits up to 2,000,000."
        topP != null && topP !in 0.0..1.0 -> "Top P must be between 0 and 1."
        topK != null && topK !in 1..1000 -> "Top K must be between 1 and 1,000."
        listOf(frequencyPenalty, presencePenalty).any { it != null && it !in -2.0..2.0 } ->
            "Penalties must be between −2 and 2."
        followUpTurn !in 2..30 -> "Begin the follow-up profile from step 2–30."
        followUpTemperature != null && followUpTemperature !in 0.0..2.0 ->
            "Follow-up temperature must be between 0 and 2."
        finalResponse !in setOf("", "off", "custom") || finalDirective.length > 4_000 ->
            "Check the final-answer setting and directive."
        toolTimeoutSeconds != null && toolTimeoutSeconds !in 1..600 -> "Tool timeout must be 1–600 seconds."
        toolConcurrency != null && toolConcurrency !in 1..32 -> "Set 1–32 concurrent tool calls."
        stopOnTool.isNotEmpty() && !Regex("[a-zA-Z0-9_:-]{1,96}").matches(stopOnTool) ->
            "Enter an exact tool name for the stop condition."
        stopSequences.lineSequence().filter(String::isNotBlank).count() > 4 -> "Enter up to four stop sequences."
        advancedJson.length > 20_000 || (advancedJson.isNotBlank() && !runCatching {
            JSONObject(advancedJson).keys().asSequence().all { it in optionalSdkFields }
        }.getOrDefault(false)) -> "Advanced JSON must be an object containing supported optional SDK fields."
        else -> null
    }
}

private val optionalSdkFields = setOf("provider", "reasoning", "text", "include", "metadata", "prompt",
    "promptCacheKey", "promptCacheOptions", "cacheControl", "truncation", "topLogprobs", "safetyIdentifier",
    "route", "user", "plugins", "imageConfig", "modalities")
