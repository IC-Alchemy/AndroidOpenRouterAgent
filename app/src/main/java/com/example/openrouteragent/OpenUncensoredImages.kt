package com.example.openrouteragent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

internal val imageGenerationSizes = listOf(
    "auto", "256x256", "512x512", "1024x1024", "1536x1024", "1024x1536", "1792x1024", "1024x1792",
)
private const val IMAGE_PROMPT_LIMIT = 8_000
private const val VENICE_IMAGE_PROMPT_LIMIT = 1_500

internal data class OpenUncensoredImageModel(
    val id: String,
    val name: String,
    val priceUsd: Double?,
    val promptLimit: Int = IMAGE_PROMPT_LIMIT,
)

internal data class ImageGenerationRequest(
    val model: OpenUncensoredImageModel,
    val prompt: String,
    val size: String = "1024x1024",
    val count: Int = 1,
    val negativePrompt: String = "",
) {
    fun payload(): JSONObject {
        require(prompt.trim().length in 1..model.promptLimit) {
            "Enter a prompt of up to ${model.promptLimit} characters."
        }
        require(size in imageGenerationSizes) { "Choose a supported image size." }
        require(count in 1..4) { "Choose between one and four images." }
        return JSONObject()
            .put("model", model.id)
            .put("prompt", prompt.trim())
            .put("size", size)
            .put("n", count)
            .put("response_format", "b64_json")
            .apply { if (negativePrompt.isNotBlank()) put("negative_prompt", negativePrompt.trim()) }
    }
}

internal data class ImageGenerationResult(val images: List<ByteArray>, val failedCount: Int, val costUsd: Double?)

internal fun parseImageModels(data: JSONArray): List<OpenUncensoredImageModel> = buildList {
    for (index in 0 until data.length()) {
        val entry = data.optJSONObject(index) ?: continue
        val id = (entry.opt("id") as? String)?.takeIf(String::isNotBlank) ?: continue
        val operations = entry.optJSONArray("operations") ?: continue
        if (!operations.containsString("generate")) continue
        val name = (entry.opt("name") as? String)?.takeIf(String::isNotBlank) ?: id
        val promptLimit = if (entry.optJSONArray("providers")?.containsString("venice") == true) {
            VENICE_IMAGE_PROMPT_LIMIT
        } else IMAGE_PROMPT_LIMIT
        add(OpenUncensoredImageModel(id, name, entry.usd("price_per_image_usd"), promptLimit))
    }
}.distinctBy { it.id }

internal fun parseGeneratedImages(payload: JSONObject, count: Int): ImageGenerationResult {
    providerError(payload)?.let { throw IOException(it) }
    val data = payload.getJSONArray("data")
    val images = (0 until minOf(data.length(), count)).mapNotNull { index ->
        val entry = data.optJSONObject(index) ?: return@mapNotNull null
        decodeImage(entry.opt("b64_json") as? String)
            ?: decodeImage((entry.opt("url") as? String)?.let(::imageData))
    }
    if (images.isEmpty()) throw IOException("OpenUncensored returned no usable images.")
    return ImageGenerationResult(images, count - images.size, payload.optJSONObject("ou")?.usd("cost_usd"))
}

private fun imageData(url: String): String? {
    val metadata = url.substringBefore(',')
    return if (metadata.startsWith("data:image/") && metadata.endsWith(";base64")) url.substringAfter(',', "")
    else null
}

private fun decodeImage(value: String?): ByteArray? {
    if (value.isNullOrBlank()) return null
    return try {
        Base64.getDecoder().decode(value).takeIf { it.isNotEmpty() }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun JSONObject.usd(key: String): Double? = optDouble(key).takeIf { it.isFinite() && it >= 0.0 }

private fun JSONArray.containsString(value: String): Boolean = (0 until length()).any { optString(it) == value }
