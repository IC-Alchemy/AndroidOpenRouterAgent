package com.example.openrouteragent

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal enum class CustomizationKind(val label: String, val pluralLabel: String) {
    TOOL("tool", "tools"),
    SKILL("skill", "skills"),
    SUBAGENT("subagent", "subagents"),
}

internal sealed interface CustomizationTarget {
    data class Tool(val tool: CustomAgentTool) : CustomizationTarget
    data class Skill(val skill: SavedAgentSkill) : CustomizationTarget
    data class Subagent(val index: Int, val worker: JsonObject) : CustomizationTarget
}

internal data class CustomizationUpdate(
    val config: AgentConfig,
    val summary: String,
)

internal class CustomizationAssistant(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val openRouterBaseUrl: HttpUrl = "https://openrouter.ai/api/v1/".toHttpUrl(),
) {
    suspend fun create(
        config: AgentConfig,
        model: CatalogModel,
        kind: CustomizationKind,
        prompt: String,
    ): CustomizationUpdate {
        val requestPrompt = prompt.trim()
        require(requestPrompt.isNotBlank()) { "Enter instructions for the model." }
        validateCreatePreconditions(config, kind)
        val system = createSystemPrompt(config, kind)
        val user = "Create a new ${kind.label} for this request:\n$requestPrompt"
        val json = completeJson(config, model, system, user)
        return when (kind) {
            CustomizationKind.TOOL -> applyTool(config, null, json)
            CustomizationKind.SKILL -> applySkill(config, null, json)
            CustomizationKind.SUBAGENT -> applySubagent(config, null, json)
        }
    }

    suspend fun improve(
        config: AgentConfig,
        model: CatalogModel,
        target: CustomizationTarget,
        prompt: String,
    ): CustomizationUpdate {
        val requestPrompt = prompt.trim()
        require(requestPrompt.isNotBlank()) { "Enter instructions for improving this item." }
        validateImprovePreconditions(config, target)
        val system = improveSystemPrompt(config, target)
        val user = buildString {
            appendLine("Current definition:")
            appendLine(serializeTarget(target).toString(2))
            appendLine()
            appendLine("Requested improvement:")
            appendLine(requestPrompt)
            appendLine()
            append("Return the complete updated JSON object, preserving existing fields unless the improvement changes them.")
        }
        val json = completeJson(config, model, system, user)
        return when (target) {
            is CustomizationTarget.Tool -> applyTool(config, target.tool, json)
            is CustomizationTarget.Skill -> applySkill(config, target.skill, json)
            is CustomizationTarget.Subagent -> applySubagent(config, target, json)
        }
    }

    private fun validateCreatePreconditions(config: AgentConfig, kind: CustomizationKind) {
        when (kind) {
            CustomizationKind.TOOL -> require(config.customTools.size < 32) {
                "You can save up to 32 custom tools."
            }
            CustomizationKind.SKILL -> require(config.savedSkills.size < 32) {
                "You can save up to 32 skills."
            }
            CustomizationKind.SUBAGENT -> requireNotNull(readHostedTools(config.extraToolsJson)) {
                "Fix the Additional server tools JSON before creating subagents."
            }
        }
    }

    private fun validateImprovePreconditions(config: AgentConfig, target: CustomizationTarget) {
        if (target is CustomizationTarget.Subagent) {
            val hosted = requireNotNull(readHostedTools(config.extraToolsJson)) {
                "Fix the Additional server tools JSON before editing subagents."
            }
            require(target.index in hosted.indices && hosted[target.index].isSubagent()) {
                "This subagent could not be found."
            }
        }
    }

    private suspend fun completeJson(
        config: AgentConfig,
        model: CatalogModel,
        systemPrompt: String,
        userPrompt: String,
    ): JSONObject {
        val apiKey = config.apiKey.trim()
        val providerLabel = "OpenRouter"

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))

        require(apiKey.isNotBlank()) { "Add your OpenRouter API key in Settings first." }
        val url = openRouterBaseUrl.newBuilder().addPathSegments("chat/completions").build()
        val body = JSONObject()
            .put("model", model.id)
            .put("stream", false)
            .put("messages", messages)
        if ("response_format" in model.parameters) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .openRouterAttribution()
            .header("Accept", "application/json")

        val request = requestBuilder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        val rawResponse = executeRequest(request, providerLabel, apiKey)
        val payload = runCatching { JSONObject(rawResponse) }
            .getOrElse { throw IOException("$providerLabel returned an invalid JSON response.", it) }
        openRouterError(payload)?.let { throw IOException(redactSecret(it, apiKey)) }

        val choice = payload.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IOException("$providerLabel returned no completion choices.")
        val message = choice.optJSONObject("message")
            ?: throw IOException("$providerLabel returned no completion message.")
        val text = extractMessageText(message.opt("content")).ifBlank {
            extractMessageText(message.opt("refusal")).also { refusal ->
                if (refusal.isNotBlank()) throw IOException(redactSecret(refusal, apiKey))
            }
        }
        if (text.isBlank()) throw IOException("$providerLabel returned an empty completion.")
        return extractJsonObject(text)
    }

    private suspend fun executeRequest(request: Request, providerLabel: String, apiKey: String): String =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    val message = if (e is java.net.SocketTimeoutException) {
                        "$providerLabel timed out."
                    } else {
                        "Could not connect to $providerLabel. Check your connection and settings."
                    }
                    continuation.resumeWithException(IOException(message, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!continuation.isActive) return
                        val result = runCatching {
                            if (!res.isSuccessful) {
                                val peek = res.peekBody(8192).string()
                                val detail = runCatching { openRouterError(JSONObject(peek)) }.getOrNull()
                                val cleanDetail = redactSecret(detail ?: res.message, apiKey)
                                throw IOException("$providerLabel HTTP ${res.code}. $cleanDetail")
                            }
                            readLimitedBody(res, providerLabel)
                        }
                        continuation.resumeWith(result)
                    }
                }
            })
        }

    private fun readLimitedBody(response: Response, providerLabel: String, maxBytes: Long = 2_000_000): String {
        val source = response.body?.source() ?: throw IOException("$providerLabel returned an empty response.")
        if (source.request(maxBytes + 1)) {
            throw IOException("$providerLabel response exceeded ${maxBytes / 1_000_000} MB.")
        }
        return source.readUtf8()
    }

    private fun applyTool(
        config: AgentConfig,
        existing: CustomAgentTool?,
        json: JSONObject,
    ): CustomizationUpdate {
        val name = json.optionalStringField("name")?.trim().orEmpty()
        val description = json.optionalStringField("description")?.trim().orEmpty()
        val handler = json.optionalStringField("handler")?.trim().orEmpty()
        val schema = when (val rawSchema = json.opt("schema")) {
            is JSONObject -> rawSchema.toString(2)
            is String -> rawSchema.trim()
            else -> ""
        }
        val rawValue = when (val valueNode = json.opt("value")) {
            null, JSONObject.NULL -> ""
            is String -> valueNode
            else -> throw IllegalArgumentException("Tool value must be text.")
        }
        val value = when (handler) {
            "webhook" -> rawValue.trim()
            "ask" -> ""
            else -> rawValue
        }
        val enabled = json.optionalBooleanField("enabled") ?: existing?.enabled ?: true
        val others = if (existing == null) config.customTools else config.customTools.filterNot { it.id == existing.id }
        require(name !in RESERVED_CUSTOM_TOOL_NAMES) { "Choose a different custom tool name." }
        require(others.none { it.name == name }) { "That tool name is already in use." }
        require(others.size < 32) { "You can save up to 32 custom tools." }

        val tool = CustomAgentTool(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            description = description,
            schema = schema,
            handler = handler,
            value = value,
            enabled = enabled,
        )
        tool.problem(strictSchema = true)?.let { throw IllegalArgumentException(it) }

        val nextTools = if (existing == null) {
            config.customTools + tool
        } else {
            config.customTools.map { if (it.id == existing.id) tool else it }
        }
        val updated = config.copy(customTools = nextTools)
        updated.settingsError()?.let { throw IllegalArgumentException(it) }
        val verb = if (existing == null) "Created" else "Improved"
        return CustomizationUpdate(updated, "$verb tool “${tool.name}”.")
    }

    private fun applySkill(
        config: AgentConfig,
        existing: SavedAgentSkill?,
        json: JSONObject,
    ): CustomizationUpdate {
        val name = json.optionalStringField("name")?.trim().orEmpty()
        val description = json.optionalStringField("description")?.trim().orEmpty()
        val body = json.optionalStringField("body")?.trim().orEmpty()
        val pinned = json.optionalBooleanField("pinned") ?: existing?.pinned ?: false
        val enabled = (json.optionalBooleanField("enabled") ?: existing?.enabled ?: true) || pinned
        val others = if (existing == null) config.savedSkills else config.savedSkills.filterNot { it.name == existing.name }
        require(name != GUIDE_NAME) { "Choose a different skill name." }
        require(others.none { it.name == name }) { "That skill name is already in use." }
        require(others.size < 32) { "You can save up to 32 skills." }

        val skill = SavedAgentSkill(
            name = name,
            description = description,
            body = body,
            enabled = enabled,
            pinned = pinned,
        )
        skill.problem()?.let { throw IllegalArgumentException(it) }

        val nextSkills = if (existing == null) {
            config.savedSkills + skill
        } else {
            config.savedSkills.map { if (it.name == existing.name) skill else it }
        }
        val updated = config.copy(savedSkills = nextSkills)
        updated.settingsError()?.let { throw IllegalArgumentException(it) }
        val verb = if (existing == null) "Created" else "Improved"
        return CustomizationUpdate(updated, "$verb skill “${skill.name}”.")
    }

    private fun applySubagent(
        config: AgentConfig,
        existing: CustomizationTarget.Subagent?,
        json: JSONObject,
    ): CustomizationUpdate {
        val hosted = requireNotNull(readHostedTools(config.extraToolsJson)) {
            "Fix the Additional server tools JSON before updating subagents."
        }
        val name = json.optionalStringField("name")?.trim().orEmpty()
        val model = json.optionalStringField("model")?.trim().orEmpty()
        val instructions = json.optionalStringField("instructions").orEmpty()

        val mergedAdvanced = JSONObject()
        val rawAdvancedJson = json.optionalStringField("advancedJson")
        if (!rawAdvancedJson.isNullOrBlank()) {
            val parsed = runCatching { JSONObject(rawAdvancedJson) }
                .getOrElse { throw IllegalArgumentException("Advanced subagent options must be a JSON object.") }
            parsed.keys().forEach { key -> mergedAdvanced.put(key, parsed.get(key)) }
        }
        if (json.has("advanced") && !json.isNull("advanced")) {
            val obj = json.optJSONObject("advanced")
                ?: throw IllegalArgumentException("Advanced subagent options must be a JSON object.")
            obj.keys().forEach { key -> mergedAdvanced.put(key, obj.get(key)) }
        }
        for (key in SUBAGENT_ADVANCED_FIELDS) {
            if (json.has(key) && !json.isNull(key) && !mergedAdvanced.has(key)) {
                mergedAdvanced.put(key, json.get(key))
            }
        }
        val advancedJson = if (mergedAdvanced.length() == 0) "" else mergedAdvanced.toString()

        val worker = editSubagent(existing?.worker, name, model, instructions, advancedJson)
        subagentError(worker, strict = true)?.let { throw IllegalArgumentException(it) }

        val nextHosted = if (existing == null) hosted + worker else hosted.withSubagentEdit(existing.index, worker)
        val nextJson = writeHostedTools(nextHosted)
        hostedToolsError(nextJson)?.let { throw IllegalArgumentException(it) }

        val updated = config.copy(extraToolsJson = nextJson)
        updated.settingsError()?.let { throw IllegalArgumentException(it) }
        val verb = if (existing == null) "Created" else "Improved"
        return CustomizationUpdate(updated, "$verb subagent “${worker.subagentDisplayName()}”.")
    }

    private fun createSystemPrompt(config: AgentConfig, kind: CustomizationKind): String = when (kind) {
        CustomizationKind.TOOL -> toolSystemPrompt(config, null)
        CustomizationKind.SKILL -> skillSystemPrompt(config, null)
        CustomizationKind.SUBAGENT -> subagentSystemPrompt(config, null)
    }

    private fun improveSystemPrompt(config: AgentConfig, target: CustomizationTarget): String = when (target) {
        is CustomizationTarget.Tool -> toolSystemPrompt(config, target.tool)
        is CustomizationTarget.Skill -> skillSystemPrompt(config, target.skill)
        is CustomizationTarget.Subagent -> subagentSystemPrompt(config, target)
    }

    private fun toolSystemPrompt(config: AgentConfig, existing: CustomAgentTool?): String {
        val usedNames = config.customTools.filter { it.id != existing?.id }.map { it.name }
        return buildString {
            appendLine("You design custom tools for an OpenRouter Android agent.")
            appendLine("Return ONLY a single valid JSON object with NO markdown prose outside JSON.")
            appendLine("Required fields:")
            appendLine("- \"name\": lowercase letters, digits, underscores only, matching ^[a-z][a-z0-9_]{0,62}$; avoid prefixes remote_, mini_app_, reserved names (${RESERVED_CUSTOM_TOOL_NAMES.sorted().joinToString()}), and existing names (${usedNames.joinToString().ifBlank { "none" }}).")
            appendLine("- \"description\": clear 1-1024 char description of what the tool does and when to call it.")
            appendLine("- \"handler\": one of \"template\", \"ask\", or \"webhook\".")
            appendLine("- \"schema\": JSON object schema (as an object or JSON string) with \"type\": \"object\", \"properties\" (up to 24 keys matching ^[a-zA-Z][a-zA-Z0-9_]{0,63}$), and optional \"required\" array. Only use standard JSON Schema types (string, integer, number, boolean, array, object) and supported keywords (description, title, pattern, minLength, maxLength, items, minItems, maxItems, minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf, required, additionalProperties).")
            appendLine("- \"value\": for \"template\", response text up to 12000 chars using {{field}} placeholders; for \"webhook\", a fixed HTTPS URL without credentials, query parameters, or fragment; for \"ask\", empty string \"\".")
            appendLine("Optional field: \"enabled\" (boolean).")
        }
    }

    private fun skillSystemPrompt(config: AgentConfig, existing: SavedAgentSkill?): String {
        val usedNames = config.savedSkills.filter { it.name != existing?.name }.map { it.name } + GUIDE_NAME
        return buildString {
            appendLine("You write reusable agent skills for an OpenRouter Android agent.")
            appendLine("Return ONLY a single valid JSON object with NO markdown prose outside JSON.")
            appendLine("Required fields:")
            appendLine("- \"name\": lowercase hyphenated slug matching ^[a-z0-9]+(?:-[a-z0-9]+)*$, max 64 chars, unique (avoid: ${usedNames.joinToString()}).")
            appendLine("- \"description\": 1-1024 char trigger description explaining when to load/use this skill.")
            appendLine("- \"body\": actionable Markdown instructions up to 36000 chars.")
            appendLine("Optional boolean fields: \"enabled\", \"pinned\" (pin only if instructions should apply to every run).")
        }
    }

    private fun subagentSystemPrompt(config: AgentConfig, existing: CustomizationTarget.Subagent?): String {
        val others = readHostedTools(config.extraToolsJson).orEmpty()
            .withIndex()
            .filter { it.value.isSubagent() && it.index != existing?.index }
        val usedNames = others.mapNotNull { it.value.subagentParameters()?.stringValue("name")?.trim() }
        val hasUnnamedDefault = others.any { it.value.subagentParameters()?.stringValue("name").isNullOrBlank() }
        return buildString {
            appendLine("You configure OpenRouter hosted subagent workers (`openrouter:subagent`).")
            appendLine("Return ONLY a single valid JSON object with NO markdown prose outside JSON.")
            appendLine("Fields:")
            appendLine("- \"name\": optional unique name (1-64 chars matching ^[A-Za-z0-9 _-]+$). Avoid existing names (${usedNames.joinToString().ifBlank { "none" }}). ${if (hasUnnamedDefault) "An unnamed default subagent already exists, so you MUST provide a non-blank unique name." else "Omit or set \"\" only for the single default subagent."}")
            appendLine("- \"model\": optional OpenRouter model ID without spaces, or \"\" to use the main chat model.")
            appendLine("- \"instructions\": optional worker system instructions text.")
            appendLine("- \"advanced\": optional JSON object containing ONLY supported SDK fields: maxToolCalls (integer 1-25), maxCompletionTokens (positive integer), temperature (0-2), reasoning ({effort: none|minimal|low|medium|high|xhigh|max, maxTokens: positive int}), inheritFunctions (boolean), inheritedFunctionNames (string array), tools (array of {\"type\": \"...\"} using supported OpenRouter server tool types: openrouter:shell, openrouter:web_search, openrouter:datetime, openrouter:web_fetch, openrouter:files, openrouter:image_generation, openrouter:experimental__search_models, openrouter:advisor, openrouter:fusion).")
        }
    }

    private fun serializeTarget(target: CustomizationTarget): JSONObject = when (target) {
        is CustomizationTarget.Tool -> JSONObject()
            .put("name", target.tool.name)
            .put("description", target.tool.description)
            .put("handler", target.tool.handler)
            .put("schema", runCatching { JSONObject(target.tool.schema) }.getOrElse { target.tool.schema })
            .put("value", target.tool.value)
            .put("enabled", target.tool.enabled)
        is CustomizationTarget.Skill -> JSONObject()
            .put("name", target.skill.name)
            .put("description", target.skill.description)
            .put("body", target.skill.body)
            .put("enabled", target.skill.enabled)
            .put("pinned", target.skill.pinned)
        is CustomizationTarget.Subagent -> {
            val params = target.worker.subagentParameters()
            val obj = JSONObject()
                .put("name", params?.stringValue("name").orEmpty())
                .put("model", params?.stringValue("model").orEmpty())
                .put("instructions", params?.stringValue("instructions").orEmpty())
            val advancedRaw = subagentAdvancedJson(params)
            if (advancedRaw.isNotBlank()) {
                obj.put("advanced", JSONObject(advancedRaw))
            }
            obj
        }
    }
}

internal fun extractJsonObject(text: String): JSONObject {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("The model did not return a valid JSON object.")
    runCatching { return JSONObject(trimmed) }

    val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
    for (match in fenceRegex.findAll(trimmed)) {
        val candidate = match.groupValues[1].trim()
        runCatching { return JSONObject(candidate) }
    }

    var start = -1
    var depth = 0
    var inString = false
    var escaped = false
    for (i in trimmed.indices) {
        val ch = trimmed[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }
        when (ch) {
            '"' -> if (depth > 0) inString = true
            '{' -> {
                if (depth == 0) start = i
                depth++
            }
            '}' -> if (depth > 0) {
                depth--
                if (depth == 0 && start >= 0) {
                    val slice = trimmed.substring(start, i + 1)
                    runCatching { return JSONObject(slice) }
                    start = -1
                }
            }
        }
    }
    throw IllegalArgumentException("The model did not return a valid JSON object.")
}

private fun extractMessageText(value: Any?): String = when (value) {
    is String -> value
    is JSONArray -> buildString {
        for (i in 0 until value.length()) {
            val part = value.optJSONObject(i) ?: continue
            if (part.optString("type") in setOf("text", "output_text")) {
                append(part.optString("text"))
            }
        }
    }
    else -> ""
}

private fun JSONObject.optionalStringField(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return opt(key) as? String ?: throw IllegalArgumentException("Field “$key” must be text.")
}

private fun JSONObject.optionalBooleanField(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return opt(key) as? Boolean ?: throw IllegalArgumentException("Field “$key” must be true or false.")
}

private fun redactSecret(message: String, secret: String): String =
    if (secret.isBlank()) message else message.replace(secret, "[API key]")

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
