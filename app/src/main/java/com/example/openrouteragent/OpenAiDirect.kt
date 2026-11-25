package com.example.openrouteragent

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI used directly with the user's own OpenAI API key, beside OpenRouter.
 *
 * OpenAI models appear in the model list with the [OPENAI_MODEL_PREFIX] prefix. A run with one of them uses the same
 * agent runtime as OpenRouter models: the SDK builds an OpenResponses request, and Android rewrites it into an
 * OpenAI Responses API request ([openAiRequestBody]) and sends it to api.openai.com with the OpenAI key. The OpenAI key
 * never goes to OpenRouter or into the WebView, and the OpenRouter key never goes to OpenAI.
 */
internal const val OPENAI_MODEL_PREFIX = "openai-direct/"
internal const val OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
internal const val OPENAI_MODELS_URL = "https://api.openai.com/v1/models"
internal const val OPENAI_KEYS_PAGE = "https://platform.openai.com/api-keys"

/** Stands in for the API key inside the WebView on OpenAI runs. Android replaces the auth header with the real key. */
internal const val OPENAI_RUNTIME_PLACEHOLDER_KEY = "openai-direct-key-held-by-android"

/** Short note shown wherever an OpenRouter-only feature can't apply to OpenAI models. */
internal const val OPENROUTER_ONLY_NOTE = "OpenRouter only. Not used with OpenAI (direct) models."

/** A string field, or "" when it is missing or JSON null (optString would return "null"). */
private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

/** Hides a key in a message that may reach the screen. */
internal fun String.redact(secret: String): String = if (secret.isBlank()) this else replace(secret.trim(), "[API key]")

internal fun isOpenAiModel(id: String?): Boolean = id?.startsWith(OPENAI_MODEL_PREFIX) == true

/** The model ID OpenAI expects: the app's ID without the prefix. */
internal fun openAiModelId(id: String): String = id.removePrefix(OPENAI_MODEL_PREFIX)

/** Words in OpenAI model IDs that mark models the Responses agent loop can't use (audio, images, embeddings…). */
private val nonChatMarkers = listOf("audio", "realtime", "transcribe", "tts", "whisper", "embedding", "moderation",
    "dall-e", "image", "instruct", "search", "computer-use", "deep-research", "davinci", "babbage", "diarize", "sora")

/** True for OpenAI text models that work with the Responses API agent loop. */
internal fun isOpenAiChatModel(id: String): Boolean {
    val lower = id.lowercase()
    val family = lower.startsWith("gpt-") || lower.startsWith("chatgpt-") || lower.startsWith("codex-") ||
        Regex("^o\\d").containsMatchIn(lower) || lower.startsWith("ft:gpt-") || Regex("^ft:o\\d").containsMatchIn(lower)
    return family && nonChatMarkers.none { it in lower }
}

/** OpenAI reasoning models: no temperature or top_p. */
internal fun isOpenAiReasoningModel(id: String): Boolean {
    val lower = openAiModelId(id).lowercase().removePrefix("ft:")
    return Regex("^o\\d").containsMatchIn(lower) || lower.startsWith("codex-") ||
        (lower.startsWith("gpt-5") && !lower.startsWith("gpt-5-chat"))
}

private fun readsImages(id: String): Boolean {
    val lower = id.lowercase().removePrefix("ft:")
    return lower.startsWith("gpt-4o") || lower.startsWith("gpt-4.1") || lower.startsWith("gpt-4-turbo") ||
        lower.startsWith("gpt-4.5") || lower.startsWith("gpt-5") || lower.startsWith("chatgpt-") ||
        Regex("^o(1|3|4)(?!-mini)").containsMatchIn(lower) || lower.startsWith("o4-mini") || lower.startsWith("codex-")
}

/** chatgpt-*-latest snapshots can't call functions. Every other chat model can. */
private fun callsTools(id: String): Boolean = !id.lowercase().startsWith("chatgpt-")

/** Builds model list entries from OpenAI's `GET /v1/models` answer, newest first. */
internal fun parseOpenAiModels(raw: String): List<CatalogModel> {
    val data = JSONObject(raw).optJSONArray("data") ?: throw IOException("OpenAI returned no model list.")
    val rows = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        .mapNotNull { row -> row.text("id").takeIf { it.isNotBlank() }?.let { it to row.optLong("created") } }
        .filter { (id, _) -> isOpenAiChatModel(id) }
        .distinctBy { it.first }
        .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
    return rows.map { (id, _) ->
        val appId = OPENAI_MODEL_PREFIX + id
        val parameters = buildSet {
            if (callsTools(id)) add("tools")
            add("max_tokens")
            if (isOpenAiReasoningModel(appId)) add("reasoning") else { add("temperature"); add("top_p") }
        }
        CatalogModel(
            id = appId,
            name = "OpenAI: $id (direct)",
            contextLength = 0,
            parameters = parameters,
            inputModalities = if (readsImages(id)) setOf("text", "image", "file") else setOf("text"),
            outputModalities = setOf("text"),
        )
    }
}

/** OpenAI's own hosted tools, added to OpenAI runs by Android. */
internal data class OpenAiHostedTools(
    val webSearch: Boolean = false,
    val imageGeneration: Boolean = false,
    val imageQuality: String = "",
    val imageAspect: String = "",
    val codeInterpreter: Boolean = false,
    val fileSearchStores: List<String> = emptyList(),
) {
    fun toJson(): JSONArray = JSONArray().apply {
        if (webSearch) put(JSONObject().put("type", "web_search"))
        if (codeInterpreter) put(JSONObject().put("type", "code_interpreter")
            .put("container", JSONObject().put("type", "auto")))
        if (fileSearchStores.isNotEmpty()) put(JSONObject().put("type", "file_search")
            .put("vector_store_ids", JSONArray(fileSearchStores)))
        if (imageGeneration) put(JSONObject().put("type", "image_generation").apply {
            imageQuality.takeIf { it in setOf("low", "medium", "high") }?.let { put("quality", it) }
            openAiImageSize(imageAspect)?.let { put("size", it) }
        })
    }
}

/** OpenAI image tools take a size, not an aspect ratio. Blank lets the model choose. */
internal fun openAiImageSize(aspect: String): String? = when (aspect) {
    "1:1" -> "1024x1024"
    "16:9", "4:3", "3:2" -> "1536x1024"
    "9:16", "3:4", "2:3" -> "1024x1536"
    else -> null
}

private val vectorStoreId = Regex("vs_[A-Za-z0-9_-]{1,96}")

internal fun parseVectorStoreIds(csv: String): List<String> =
    csv.split(',', '\n', ' ').map(String::trim).filter(String::isNotEmpty).distinct()

internal fun vectorStoreIdsError(csv: String): String? {
    val ids = parseVectorStoreIds(csv)
    return when {
        ids.any { !vectorStoreId.matches(it) } -> "Enter OpenAI vector store IDs that start with vs_, separated by commas."
        ids.size > 10 -> "Enter up to 10 vector store IDs."
        else -> null
    }
}

internal fun AgentConfig.openAiHostedTools(): OpenAiHostedTools =
    if (sdk.toolChoice == "none") OpenAiHostedTools() else OpenAiHostedTools(
        webSearch = openAiWebSearch,
        imageGeneration = openAiImageGeneration,
        imageQuality = imageQuality,
        imageAspect = imageAspect,
        codeInterpreter = openAiCodeInterpreter,
        fileSearchStores = parseVectorStoreIds(openAiFileSearchStores),
    )

/**
 * The settings an OpenAI run sends to the agent runtime. OpenRouter's hosted tools, plugins, routing and fallbacks are
 * turned off here because OpenAI can't run them; OpenAI's own hosted tools are added by [openAiRequestBody] instead.
 * Function tools (custom tools, mini apps, skills, MCP, app actions, device tools) stay on: they run on the device.
 */
internal fun AgentConfig.forOpenAiRun(): AgentConfig = copy(
    apiKey = OPENAI_RUNTIME_PLACEHOLDER_KEY,
    enableShellSandbox = false,
    enableWebSearch = false,
    enableDateTime = false,
    webFetch = false,
    enableWorkspaceFiles = false,
    imageGeneration = false,
    advisorTool = false,
    fusionTool = false,
    modelSearchTool = false,
    responseHealing = false,
    contextCompression = false,
    extraToolsJson = "",
    sdk = sdk.copy(
        routingVariant = "", providerOrder = "", providerOnly = "", providerIgnore = "", providerSort = "",
        sortPartition = "", zdr = false, distillableText = false, quantizations = "", minThroughput = null,
        maxLatency = null, maxPromptPrice = null, maxCompletionPrice = null, providerFallback = "",
        requireParameters = false, dataCollection = "", fallbackModels = "",
        followUpModel = sdk.followUpModel.takeIf { isOpenAiModel(it.trim()) }.orEmpty(),
        advancedJson = openAiAdvancedJson(sdk.advancedJson),
    ),
)

/** Keeps only the advanced SDK fields that mean the same thing on OpenAI. */
private fun openAiAdvancedJson(raw: String): String {
    if (raw.isBlank()) return raw
    val source = runCatching { JSONObject(raw) }.getOrNull() ?: return ""
    val keep = setOf("reasoning", "text", "include", "metadata", "promptCacheKey", "truncation", "topLogprobs",
        "safetyIdentifier", "user")
    val result = JSONObject()
    source.keys().forEach { if (it in keep) result.put(it, source.get(it)) }
    return if (result.length() == 0) "" else result.toString()
}

/** Request fields that OpenAI's Responses API accepts with the same meaning. Everything else is OpenRouter's. */
private val openAiRequestFields = setOf("model", "input", "instructions", "tools", "tool_choice", "temperature",
    "top_p", "max_output_tokens", "max_tool_calls", "parallel_tool_calls", "prompt_cache_key", "safety_identifier",
    "service_tier", "store", "stream", "text", "reasoning", "truncation", "metadata", "include", "top_logprobs",
    "user", "previous_response_id")
private val openAiIncludes = setOf("web_search_call.action.sources", "code_interpreter_call.outputs",
    "file_search_call.results", "message.input_image.image_url", "message.output_text.logprobs",
    "reasoning.encrypted_content", "computer_call_output.output.image_url")
private val openAiServiceTiers = setOf("auto", "default", "flex", "priority")
private val inputContentTypes = setOf("input_text", "input_image", "input_file", "output_text", "refusal")

/**
 * Rewrites an OpenRouter Responses request from the SDK into an OpenAI Responses request.
 *
 * - Drops OpenRouter-only fields (provider, plugins, models, session_id, stop_server_tools_when, top_k, penalties…).
 * - Keeps function tools as they are (the shape is the same) and drops OpenRouter server tools, then adds the
 *   OpenAI hosted tools chosen in Settings.
 * - Cleans the input so a chat that began on OpenRouter can continue on OpenAI: OpenRouter-only items, reasoning
 *   items (their encrypted content only works where it was made) and hosted tool calls are left out, and item IDs
 *   are removed because nothing is stored on OpenAI (store is false).
 */
internal fun openAiRequestBody(raw: String, hosted: OpenAiHostedTools): String {
    val source = JSONObject(raw)
    val body = JSONObject()
    source.keys().forEach { key -> if (key in openAiRequestFields) body.put(key, source.get(key)) }
    val model = source.text("model")
    if (!isOpenAiModel(model)) throw IOException("This request is for an OpenRouter model, not an OpenAI model.")
    body.put("model", openAiModelId(model))
    body.put("store", false)
    if (isOpenAiReasoningModel(model)) {
        body.remove("temperature")
        body.remove("top_p")
    }
    (source.opt("reasoning") as? JSONObject)?.let { reasoning ->
        val clean = JSONObject()
        reasoning.text("effort").takeIf(String::isNotBlank)?.let { clean.put("effort", it) }
        reasoning.text("summary").takeIf { it in setOf("auto", "concise", "detailed") }?.let { clean.put("summary", it) }
        if (clean.length() == 0 || !isOpenAiReasoningModel(model)) body.remove("reasoning") else body.put("reasoning", clean)
    }
    (source.opt("include") as? JSONArray)?.let { include ->
        val kept = (0 until include.length()).map { include.optString(it) }.filter { it in openAiIncludes }.distinct()
        if (kept.isEmpty()) body.remove("include") else body.put("include", JSONArray(kept))
    }
    if (body.text("service_tier").let { it.isNotEmpty() && it !in openAiServiceTiers }) body.remove("service_tier")
    if (body.has("metadata") && body.opt("metadata") !is JSONObject) body.remove("metadata")

    val tools = JSONArray()
    (source.opt("tools") as? JSONArray)?.let { list ->
        for (index in 0 until list.length()) {
            val tool = list.optJSONObject(index) ?: continue
            if (tool.text("type") == "function") tools.put(functionTool(tool))
        }
    }
    val hostedTools = hosted.toJson()
    for (index in 0 until hostedTools.length()) tools.put(hostedTools.get(index))
    if (tools.length() == 0) {
        body.remove("tools")
        body.remove("tool_choice")
        body.remove("parallel_tool_calls")
    } else {
        body.put("tools", tools)
        openAiToolChoice(source.opt("tool_choice"), tools)?.let { body.put("tool_choice", it) } ?: body.remove("tool_choice")
    }
    body.put("input", openAiInput(source.opt("input")))
    return body.toString()
}

private fun functionTool(tool: JSONObject): JSONObject {
    val clean = JSONObject().put("type", "function").put("name", tool.getString("name"))
    tool.text("description").takeIf(String::isNotEmpty)?.let { clean.put("description", it) }
    clean.put("parameters", tool.optJSONObject("parameters") ?: JSONObject().put("type", "object")
        .put("properties", JSONObject()))
    // OpenAI's Responses API makes function tools strict unless told otherwise, and strict schemas reject optional
    // fields, which most app tools have. Only a tool that asks for strict gets it.
    clean.put("strict", tool.optBoolean("strict", false))
    return clean
}

private fun openAiToolChoice(choice: Any?, tools: JSONArray): Any? = when (choice) {
    null, JSONObject.NULL -> null
    is String -> choice.takeIf { it in setOf("auto", "none", "required") }
    is JSONObject -> {
        val type = choice.text("type")
        val names = (0 until tools.length()).mapNotNull { tools.optJSONObject(it)?.text("name") }
        when {
            type == "function" && choice.text("name") in names -> JSONObject().put("type", "function")
                .put("name", choice.text("name"))
            type == "openrouter:web_search" && hasTool(tools, "web_search") -> JSONObject().put("type", "web_search")
            type == "openrouter:image_generation" && hasTool(tools, "image_generation") ->
                JSONObject().put("type", "image_generation")
            type in setOf("web_search", "image_generation", "code_interpreter", "file_search") && hasTool(tools, type) ->
                JSONObject().put("type", type)
            else -> "auto"
        }
    }
    else -> null
}

private fun hasTool(tools: JSONArray, type: String) = (0 until tools.length()).any { tools.optJSONObject(it)?.text("type") == type }

private fun openAiInput(input: Any?): Any = when (input) {
    is String -> input
    is JSONArray -> JSONArray().also { out ->
        for (index in 0 until input.length()) openAiInputItem(input.opt(index))?.let(out::put)
    }
    else -> JSONArray()
}

private fun openAiInputItem(value: Any?): JSONObject? {
    val item = value as? JSONObject ?: return null
    val type = item.text("type").ifEmpty { if (item.has("role")) "message" else "" }
    return when (type) {
        "message" -> {
            val role = item.text("role").takeIf { it in setOf("user", "assistant", "system", "developer") }
                ?: return null
            val content = when (val raw = item.opt("content")) {
                is String -> raw
                is JSONArray -> JSONArray().also { parts ->
                    for (index in 0 until raw.length()) openAiContentPart(raw.opt(index), role)?.let(parts::put)
                }.takeIf { it.length() > 0 } ?: return null
                else -> return null
            }
            JSONObject().put("type", "message").put("role", role).put("content", content)
        }
        "function_call" -> {
            val callId = item.text("call_id").ifEmpty { item.text("callId") }.takeIf(String::isNotEmpty)
                ?: return null
            JSONObject().put("type", "function_call").put("call_id", callId).put("name", item.text("name"))
                .put("arguments", item.text("arguments").ifEmpty { "{}" })
        }
        "function_call_output" -> {
            val callId = item.text("call_id").ifEmpty { item.text("callId") }.takeIf(String::isNotEmpty)
                ?: return null
            val output = when (val raw = item.opt("output")) {
                is String -> raw
                null, JSONObject.NULL -> ""
                else -> raw.toString()
            }
            JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", output)
        }
        // Reasoning, hosted tool calls and OpenRouter-only items can't be replayed on OpenAI with store off.
        else -> null
    }
}

private fun openAiContentPart(value: Any?, role: String): JSONObject? {
    val part = value as? JSONObject ?: return null
    val type = part.text("type")
    if (type !in inputContentTypes) return null
    return when (type) {
        "input_text" -> JSONObject().put("type", if (role == "assistant") "output_text" else "input_text")
            .put("text", part.text("text")).also { if (role == "assistant") it.put("annotations", JSONArray()) }
        "output_text" -> if (role == "assistant") JSONObject().put("type", "output_text")
            .put("text", part.text("text")).put("annotations", JSONArray())
            else JSONObject().put("type", "input_text").put("text", part.text("text"))
        "refusal" -> if (role == "assistant") JSONObject().put("type", "refusal").put("refusal", part.text("refusal"))
            else null
        "input_image" -> {
            if (role == "assistant") return null
            val url = part.text("image_url").ifEmpty { part.text("imageUrl") }
            val fileId = part.text("file_id").ifEmpty { part.text("fileId") }
            if (url.isEmpty() && fileId.isEmpty()) return null
            JSONObject().put("type", "input_image").put("detail", part.text("detail").ifEmpty { "auto" }).apply {
                if (url.isNotEmpty()) put("image_url", url) else put("file_id", fileId)
            }
        }
        "input_file" -> {
            if (role == "assistant") return null
            val clean = JSONObject().put("type", "input_file")
            listOf("file_data" to "fileData", "file_url" to "fileUrl", "file_id" to "fileId", "filename" to "filename")
                .forEach { (snake, camel) ->
                    part.text(snake).ifEmpty { part.text(camel) }.takeIf(String::isNotEmpty)?.let { clean.put(snake, it) }
                }
            if (clean.length() <= 1) null else clean
        }
        else -> null
    }
}

/** Reads the OpenAI model list for a key. Also checks the key. */
internal class OpenAiClient(
    private val modelsUrl: HttpUrl = OPENAI_MODELS_URL.toHttpUrl(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun models(apiKey: String): List<CatalogModel> {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "Add your OpenAI API key in Settings." }
        val request = Request.Builder().url(modelsUrl).header("Authorization", "Bearer $key")
            .header("Accept", "application/json").get().build()
        val raw = suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(IOException(when (e) {
                        is java.net.UnknownHostException -> "Could not reach OpenAI. Check your connection."
                        is java.net.SocketTimeoutException -> "OpenAI did not answer in time."
                        else -> "The OpenAI request failed."
                    }, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) continuation.resume(it.body?.string().orEmpty())
                        else continuation.resumeWithException(ApiStatusException(it.code, openAiStatusMessage(it.code)))
                    }
                }
            })
        }
        return try {
            parseOpenAiModels(raw)
        } catch (e: org.json.JSONException) {
            throw IOException("OpenAI returned an unreadable model list.", e)
        }
    }

    /** Throws with a readable message when OpenAI doesn't accept the key. */
    suspend fun validateKey(apiKey: String) {
        models(apiKey)
    }
}

internal fun openAiStatusMessage(code: Int): String = "OpenAI HTTP $code. " + when (code) {
    401 -> "OpenAI rejected the API key. Check the OpenAI key in Settings."
    403 -> "OpenAI refused this request for the key, project or region."
    429 -> "OpenAI rate-limited this key or the account is out of quota. Check billing on OpenAI."
    in 500..599 -> "OpenAI failed. Try again shortly."
    else -> "OpenAI returned an unexpected status."
}
