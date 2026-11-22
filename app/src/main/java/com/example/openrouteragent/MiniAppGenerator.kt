package com.example.openrouteragent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal const val MINI_APP_GENERATOR_SYSTEM_PROMPT = """You are an expert mobile web-app generator. Convert the user's request into a polished, self-contained single-file HTML5 Mini App designed for a mobile phone screen.

STRICT OUTPUT RULES:
1. Return ONLY valid JSON matching the required schema.
2. "name": Keep it concise (max 20 characters) so it fits cleanly under a launcher icon.
3. "themeColor": Pick an accessible, vibrant hex color matching the app's purpose (e.g., "#2563EB").
4. "iconSvg": Provide a minimal, centered <svg viewBox="0 0 24 24" ...> icon using #FFFFFF strokes or fills (no external image links, no <script> tags inside the SVG).
5. "htmlCode": Must be a complete HTML5 document (<!DOCTYPE html>...) meeting these engineering rules:
   - VIEWPORT & TOUCH: Include <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">. Use touch-friendly tap targets (min 44px height) and prevent horizontal overflow (overflow-x: hidden).
   - STYLING: Embed all CSS inside a single <style> tag. Use system fonts (-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif), clean spacing, and responsive CSS Grid/Flexbox.
   - JAVASCRIPT & LIBRARIES: Embed all JS inside <script> tags. If external libraries are needed (e.g., Chart.js, Three.js, Canvas Confetti), load them ONLY from reliable HTTPS CDNs (https://cdn.jsdelivr.net/npm/... or https://unpkg.com/...).
   - PERSISTENCE: Persist user data (scores, lists, settings) automatically using localStorage with a unique key prefix so data survives app restarts.
   - NO EXTERNAL NAVIGATION: Never link to external websites or use alert()/confirm()/prompt() dialogs (build custom HTML modals instead, as native JS dialogs can hang WebViews)."""

internal fun miniAppOutputSchema(): JSONObject = JSONObject()
    .put("type", "object")
    .put(
        "properties",
        JSONObject()
            .put(
                "name",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Short, catchy 1-to-3 word app title (e.g., 'Tip Split', 'Pomodoro Focus')."),
            )
            .put(
                "themeColor",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Hex color code for the app icon background and top bar (e.g., '#2563EB')."),
            )
            .put(
                "iconSvg",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Clean, self-contained 24x24 SVG markup using white strokes/fills for the launcher icon."),
            )
            .put(
                "htmlCode",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Complete, valid, self-contained HTML5 document with embedded CSS and JS."),
            ),
    )
    .put("required", JSONArray(listOf("name", "themeColor", "iconSvg", "htmlCode")))
    .put("additionalProperties", false)

/**
 * Generates new Mini Apps and applies conversational edits to existing Mini Apps using Structured Outputs
 * (JSON Schema) + kotlinx.serialization + [MiniAppSanitizer] + [MiniAppIconRenderer].
 */
internal class MiniAppGenerator(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val openRouterBaseUrl: HttpUrl = "https://openrouter.ai/api/v1/".toHttpUrl(),
    private val providerBase: (CustomProvider) -> HttpUrl = {
        requireNotNull(normalizeChatBase(it.baseUrl)) { "Enter a valid provider API URL." }
    },
    private val libraries: MiniAppLibraries = MiniAppLibraries(null),
    private val renderIcon: (svg: String?, themeColor: String, name: String) -> ByteArray = { svg, color, name ->
        MiniAppIconRenderer.renderSvgToPng(svg, color, name)
    },
) {
    /** Creates a brand-new Mini App bundle from a plain-English user prompt. */
    suspend fun createMiniApp(
        config: AgentConfig,
        model: CatalogModel,
        repository: MiniAppRepository,
        prompt: String,
    ): MiniApp {
        val requestPrompt = prompt.trim()
        require(requestPrompt.isNotBlank()) { "Describe the mini app you want to build." }

        val userPrompt = "Build a complete mobile Mini App for this request:\n$requestPrompt"
        val rawCompletion = completeStructuredJson(config, model, MINI_APP_GENERATOR_SYSTEM_PROMPT, userPrompt)
        val parsed = MiniAppSanitizer.parseGeneratedPayload(rawCompletion)
        val sanitized = MiniAppSanitizer.sanitizeGeneratedPayload(parsed)

        return withContext(Dispatchers.IO) {
            val html = inlineTrustedLibrariesBestEffort(sanitized.htmlCode)
            val iconPng = renderIcon(sanitized.iconSvg, sanitized.themeColor, sanitized.name)
            repository.save(
                name = sanitized.name,
                html = html,
                description = requestPrompt.take(200),
                themeColor = sanitized.themeColor,
                iconUpdate = MiniAppIconUpdate.PngBytes(iconPng, iconSvg = sanitized.iconSvg, aiGenerated = true),
                imported = false,
                allowNetwork = true,
            )
        }
    }

    /**
     * Conversationally edits an existing Mini App in place, preserving its UUID directory and localStorage data,
     * and regenerating icon.png ONLY if themeColor or iconSvg changed.
     */
    suspend fun editMiniApp(
        config: AgentConfig,
        model: CatalogModel,
        repository: MiniAppRepository,
        existingId: String,
        changeRequest: String,
    ): MiniApp {
        val trimmedChange = changeRequest.trim()
        require(trimmedChange.isNotBlank()) { "Describe what you want to change." }

        val (existingApp, existingManifest, existingHtml) = withContext(Dispatchers.IO) {
            val app = repository.find(existingId)?.takeIf { it.id == existingId }
                ?: throw IllegalArgumentException("This mini app could not be found.")
            val manifest = repository.manifest(existingId) ?: app.toManifest()
            val html = repository.html(existingId)
                ?: throw IllegalArgumentException("This mini app's HTML file is missing.")
            Triple(app, manifest, MiniAppLibraries.collapse(html))
        }

        val userPrompt = buildString {
            appendLine("Update the existing Mini App below based on the user's change request.")
            appendLine("Preserve all existing localStorage keys and user data compatibility.")
            appendLine("Keep \"name\", \"themeColor\", and \"iconSvg\" identical to the current values unless the user specifically asks to change them.")
            appendLine()
            appendLine("Current metadata:")
            appendLine("- name: ${existingManifest.name}")
            appendLine("- themeColor: ${existingManifest.themeColor}")
            if (!existingManifest.iconSvg.isNullOrBlank()) {
                appendLine("- iconSvg: ${existingManifest.iconSvg}")
            }
            appendLine()
            appendLine("Current index.html:")
            appendLine(existingHtml)
            appendLine()
            appendLine("User's change request:")
            append(trimmedChange)
        }

        val rawCompletion = completeStructuredJson(config, model, MINI_APP_GENERATOR_SYSTEM_PROMPT, userPrompt)
        val parsed = MiniAppSanitizer.parseGeneratedPayload(rawCompletion)
        val sanitized = MiniAppSanitizer.sanitizeGeneratedPayload(parsed)

        return withContext(Dispatchers.IO) {
            val html = inlineTrustedLibrariesBestEffort(sanitized.htmlCode)
            val colorChanged = !sanitized.themeColor.equals(existingManifest.themeColor, ignoreCase = true)
            val svgChanged = sanitized.iconSvg != null &&
                MiniAppSanitizer.normalizeSvgForDiff(sanitized.iconSvg) != MiniAppSanitizer.normalizeSvgForDiff(existingManifest.iconSvg)
            val isAiOwnedIcon = existingManifest.aiGeneratedIcon || existingManifest.iconSvg != null
            val fallbackInitialChanged = existingManifest.aiGeneratedIcon &&
                existingManifest.iconSvg == null &&
                sanitized.iconSvg == null &&
                fallbackIconInitial(sanitized.name) != fallbackIconInitial(existingManifest.name)
            val shouldRegenerateIcon = when {
                isAiOwnedIcon -> colorChanged || svgChanged || fallbackInitialChanged
                existingManifest.iconUri == null -> sanitized.iconSvg != null
                else -> false // Preserve custom user-uploaded gallery photo, emoji, or preset icon.
            }
            val iconUpdate = if (shouldRegenerateIcon) {
                val svgToUse = sanitized.iconSvg ?: existingManifest.iconSvg
                val png = renderIcon(svgToUse, sanitized.themeColor, sanitized.name)
                MiniAppIconUpdate.PngBytes(png, iconSvg = svgToUse, aiGenerated = true)
            } else {
                MiniAppIconUpdate.Keep
            }

            repository.save(
                name = sanitized.name,
                html = html,
                description = existingApp.description,
                themeColor = sanitized.themeColor,
                id = existingId,
                entryPoint = existingApp.entryPoint,
                iconUpdate = iconUpdate,
            )
        }
    }

    private fun inlineTrustedLibrariesBestEffort(html: String): String {
        val result = libraries.inline(html)
        return if (result.problems.isEmpty()) result.html else html
    }

    private enum class ResponseFormatMode {
        JSON_SCHEMA,
        JSON_OBJECT,
        PLAIN,
    }

    private suspend fun completeStructuredJson(
        config: AgentConfig,
        model: CatalogModel,
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val activeProvider = config.customProviders.firstOrNull { it.id == config.activeProviderId }
        val modes = when {
            activeProvider == null && (model.parameters.isEmpty() || "structured_outputs" in model.parameters) ->
                listOf(ResponseFormatMode.JSON_SCHEMA, ResponseFormatMode.JSON_OBJECT, ResponseFormatMode.PLAIN)
            activeProvider == null && "response_format" in model.parameters ->
                listOf(ResponseFormatMode.JSON_OBJECT, ResponseFormatMode.PLAIN)
            else -> listOf(ResponseFormatMode.PLAIN)
        }

        var lastError: Exception? = null
        for ((idx, mode) in modes.withIndex()) {
            try {
                return sendCompletionRequest(config, activeProvider, model, systemPrompt, userPrompt, mode)
            } catch (e: HttpStatusException) {
                lastError = e
                // If an endpoint rejects response_format (HTTP 400/422 or OpenRouter 404 parameter mismatch), retry softer.
                val canFallback = idx < modes.lastIndex && (
                    e.code == 400 ||
                        e.code == 422 ||
                        (e.code == 404 && isUnsupportedParameterError(e.message))
                    )
                if (!canFallback) throw e
            }
        }
        throw lastError ?: IOException("Mini app generation failed.")
    }

    private fun isUnsupportedParameterError(message: String?): Boolean {
        val lower = message?.lowercase().orEmpty()
        return lower.contains("parameter") ||
            lower.contains("structured") ||
            lower.contains("response_format") ||
            lower.contains("json_schema")
    }

    private suspend fun sendCompletionRequest(
        config: AgentConfig,
        activeProvider: CustomProvider?,
        model: CatalogModel,
        systemPrompt: String,
        userPrompt: String,
        mode: ResponseFormatMode,
    ): String {
        val apiKey = if (activeProvider == null) config.apiKey.trim() else activeProvider.apiKey.trim()
        val providerLabel = activeProvider?.name ?: "OpenRouter"

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))

        val url: HttpUrl
        val body: JSONObject
        val requestBuilder: Request.Builder

        if (activeProvider == null) {
            require(apiKey.isNotBlank()) { "Add your OpenRouter API key in Settings first." }
            url = openRouterBaseUrl.newBuilder().addPathSegments("chat/completions").build()
            body = JSONObject()
                .put("model", model.id)
                .put("stream", false)
                .put("messages", messages)
            when (mode) {
                ResponseFormatMode.JSON_SCHEMA -> {
                    body.put(
                        "response_format",
                        JSONObject()
                            .put("type", "json_schema")
                            .put(
                                "json_schema",
                                JSONObject()
                                    .put("name", "mini_app_bundle")
                                    .put("strict", true)
                                    .put("schema", miniAppOutputSchema()),
                            ),
                    )
                }
                ResponseFormatMode.JSON_OBJECT -> {
                    body.put("response_format", JSONObject().put("type", "json_object"))
                }
                ResponseFormatMode.PLAIN -> Unit
            }
            requestBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("X-OpenRouter-Title", "OpenRouter Sandbox Agent")
                .header("Accept", "application/json")
        } else {
            require(activeProvider.canChat) { "Enter the ${activeProvider.name} API key in Providers first." }
            url = providerBase(activeProvider).newBuilder().addPathSegments("chat/completions").build()
            body = JSONObject(activeProvider.requestJson.ifBlank { "{}" })
            if (activeProvider.id == OPENUNCENSORED_ID && activeProvider.strategy.isNotBlank()) {
                val routing = body.optJSONObject("routing") ?: JSONObject()
                body.put("routing", routing.put("strategy", activeProvider.strategy))
            }
            body.put("model", model.id)
                .put("stream", false)
                .put("messages", messages)
            requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                }
        }

        val request = requestBuilder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        val rawResponse = executeRequest(request, providerLabel, apiKey)
        val payload = runCatching { JSONObject(rawResponse) }
            .getOrElse { throw IOException("$providerLabel returned an invalid JSON response.", it) }
        providerError(payload)?.let { throw IOException(redactSecret(it, apiKey)) }

        val choice = payload.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IOException("$providerLabel returned no completion choices.")
        val message = choice.optJSONObject("message")
            ?: throw IOException("$providerLabel returned no completion message.")
        val text = extractCompletionText(message.opt("content")).ifBlank {
            extractCompletionText(message.opt("refusal")).also { refusal ->
                if (refusal.isNotBlank()) throw IOException(redactSecret(refusal, apiKey))
            }
        }
        if (text.isBlank()) throw IOException("$providerLabel returned an empty completion.")
        return text
    }

    private suspend fun executeRequest(request: Request, providerLabel: String, apiKey: String): String =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    val message = if (e is java.net.SocketTimeoutException) {
                        "$providerLabel timed out while building the mini app."
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
                                val detail = runCatching { providerError(JSONObject(peek)) }.getOrNull()
                                val cleanDetail = redactSecret(detail ?: res.message, apiKey)
                                throw HttpStatusException(res.code, "$providerLabel HTTP ${res.code}. $cleanDetail")
                            }
                            readLimitedBody(res, providerLabel)
                        }
                        continuation.resumeWith(result)
                    }
                }
            })
        }

    private fun readLimitedBody(response: Response, providerLabel: String, maxBytes: Long = 4_000_000): String {
        val source = response.body?.source() ?: throw IOException("$providerLabel returned an empty response.")
        if (source.request(maxBytes + 1)) {
            throw IOException("$providerLabel response exceeded ${maxBytes / 1_000_000} MB.")
        }
        return source.readUtf8()
    }

    private fun extractCompletionText(value: Any?): String = when (value) {
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

    private fun redactSecret(message: String, secret: String): String =
        if (secret.isBlank()) message else message.replace(secret, "[API key]")

    private class HttpStatusException(val code: Int, message: String) : IOException(message)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
