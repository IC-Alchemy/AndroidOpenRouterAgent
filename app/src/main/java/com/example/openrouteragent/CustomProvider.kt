package com.example.openrouteragent

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.UUID

const val OPENROUTER_ID = "openrouter"
const val OPENUNCENSORED_ID = "openuncensored"

@Serializable
data class CustomProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val stream: Boolean = true,
    val strategy: String = "",
    val requestJson: String = "",
    val systemInstructions: String = "",
) {
    val needsKey: Boolean get() = id == OPENUNCENSORED_ID
    val canChat: Boolean get() = !needsKey || apiKey.isNotBlank()

    fun error(): String? = when {
        !Regex("[a-z0-9-]{1,64}").matches(id) || id == OPENROUTER_ID -> "Invalid provider ID."
        name.isBlank() || name.length > 72 -> "Enter a provider name of up to 72 characters."
        normalizeChatBase(baseUrl) == null -> "Enter an HTTPS API URL without credentials, a query, or a fragment."
        needsKey && normalizeChatBase(baseUrl)?.host != "api.openuncensored.com" ->
            "The OpenUncensored preset uses its official API host. Add a separate provider for another host."
        apiKey.trim().any { it.code !in 33..126 } -> "Enter an API key without spaces."
        model.length > 256 || model.any(Char::isWhitespace) -> "Enter a model ID without spaces."
        strategy !in setOf("", "quality", "speed", "throughput", "cost", "random") -> "Choose a routing strategy."
        systemInstructions.length > 20_000 -> "Enter system instructions of up to 20,000 characters."
        requestJson.length > 20_000 || !isValidRequestJson(requestJson) ->
            "Enter a JSON object for request options. Model, messages, streaming, and tools are controlled by the app."
        else -> null
    }

    fun normalized(): CustomProvider = copy(name = name.trim(), baseUrl = normalizeChatBase(baseUrl)?.toString().orEmpty(),
        apiKey = apiKey.trim(), model = model.trim(), requestJson = requestJson.trim(),
        systemInstructions = systemInstructions.trim())
}

internal fun openUncensoredProvider() = CustomProvider(id = OPENUNCENSORED_ID, name = "OpenUncensored",
    baseUrl = "https://api.openuncensored.com/v1/", model = "deepseek-v4.1-flash-1017-uncensored", strategy = "quality")

internal fun normalizeChatBase(value: String): HttpUrl? {
    val url = value.trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null) return null
    val segments = url.pathSegments.dropLastWhile(String::isEmpty)
    val base = if (segments.takeLast(2) == listOf("chat", "completions")) segments.dropLast(2) else segments
    return url.newBuilder().encodedPath("/").apply {
        if (base.isNotEmpty()) {
            base.forEach { addPathSegment(it) }
            addPathSegment("")
        }
    }.build()
}

private val appFields = setOf("model", "messages", "stream", "tools", "tool_choice", "functions",
    "function_call", "n")

private fun isValidRequestJson(value: String): Boolean = value.isBlank() || runCatching {
    JSONObject(value).keys().asSequence().none { it in appFields }
}.getOrDefault(false)
