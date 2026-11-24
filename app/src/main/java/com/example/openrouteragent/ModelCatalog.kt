package com.example.openrouteragent

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A model from OpenRouter's catalog. Modalities and prices come from the catalog's `architecture` and `pricing`
 * fields. An empty modality list means the catalog did not say, for example for a model ID typed by hand.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val contextLength: Int,
    val parameters: Set<String>,
    val inputModalities: Set<String> = emptySet(),
    val outputModalities: Set<String> = emptySet(),
    val pricing: ModelPricing? = null,
    /** The day OpenRouter retires this model, when the catalog lists one. */
    val expirationDate: String? = null,
) {
    val supportsTools: Boolean get() = "tools" in parameters
    val modalitiesKnown: Boolean get() = inputModalities.isNotEmpty()

    /** True when the model reads this kind of input, or when the catalog did not say. */
    fun accepts(modality: String): Boolean = !modalitiesKnown || modality in inputModalities

    val readsImages: Boolean get() = "image" in inputModalities
    val readsFiles: Boolean get() = "file" in inputModalities
    val readsAudio: Boolean get() = "audio" in inputModalities
    val makesImages: Boolean get() = "image" in outputModalities
}

/** Prices in US dollars per token, or per image for [image]. */
data class ModelPricing(val prompt: Double?, val completion: Double?, val image: Double?) {
    val isFree: Boolean get() = prompt == 0.0 && completion == 0.0
}

internal fun parseModelCatalog(raw: String): List<CatalogModel> {
    val data = JSONObject(raw).getJSONArray("data")
    return (0 until data.length()).mapNotNull { index ->
        val item = data.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").trim().ifBlank { return@mapNotNull null }
        val parameters = item.optJSONArray("supported_parameters") ?: JSONArray()
        val architecture = item.optJSONObject("architecture")
        CatalogModel(id, item.optString("name").ifBlank { id }, item.optInt("context_length"),
            parameters.strings(),
            inputModalities = architecture?.optJSONArray("input_modalities").strings(),
            outputModalities = architecture?.optJSONArray("output_modalities").strings(),
            pricing = item.optJSONObject("pricing")?.let { prices ->
                ModelPricing(prices.price("prompt"), prices.price("completion"), prices.price("image"))
                    .takeIf { it.prompt != null || it.completion != null || it.image != null }
            },
            expirationDate = (item.opt("expiration_date") as? String)?.trim()?.takeIf { it.isNotEmpty() })
    }.distinctBy { it.id }
}

private fun JSONArray?.strings(): Set<String> =
    if (this == null) emptySet() else (0 until length()).mapNotNull { (opt(it) as? String)?.trim()?.lowercase() }
        .filter(String::isNotEmpty).toSet()

/** OpenRouter sends prices as decimal strings. A negative price marks a router whose price varies. */
private fun JSONObject.price(key: String): Double? = when (val value = opt(key)) {
    is String -> value.toDoubleOrNull()
    is Number -> value.toDouble()
    else -> null
}?.takeIf { it.isFinite() && it >= 0 }

internal fun selectModel(config: AgentConfig, models: List<CatalogModel>): CatalogModel? {
    val eligible = if (config.agentToolsEnabled()) models.filter { it.supportsTools } else models
    return eligible.firstOrNull { it.id == config.model }
        ?: eligible.firstOrNull { it.id == DEFAULT_MODEL }
        ?: eligible.firstOrNull()
}

/** Short labels for what a model reads and makes, in the order the picker shows them. */
internal fun capabilityLabels(model: CatalogModel): List<String> = buildList {
    if (model.readsImages) add("Vision")
    if (model.readsFiles) add("PDF")
    if (model.readsAudio) add("Audio")
    if ("video" in model.inputModalities) add("Video")
    if (model.makesImages) add("Makes images")
    if (model.supportsTools) add("Tools")
}

/** Prompt and completion price per million tokens, such as "$3 in · $15 out per 1M tokens". */
internal fun priceLabel(pricing: ModelPricing?): String? {
    if (pricing == null) return null
    if (pricing.isFree) return "Free"
    val prompt = pricing.prompt ?: return null
    val completion = pricing.completion ?: return null
    return "${dollars(prompt * 1_000_000)} in · ${dollars(completion * 1_000_000)} out per 1M tokens"
}

private fun dollars(value: Double): String {
    val text = when {
        value >= 100 -> "%.0f".format(java.util.Locale.US, value)
        value >= 1 -> "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
        else -> "%.3f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }
    return "$$text"
}

/** A warning when the catalog says the model retires soon, or already has. */
internal fun retirementLabel(model: CatalogModel, today: LocalDate = LocalDate.now()): String? {
    val raw = model.expirationDate ?: return null
    val date = try {
        LocalDate.parse(raw.take(10))
    } catch (_: DateTimeParseException) {
        return null
    }
    return when {
        date.isBefore(today) -> "Retired on $date"
        !date.isAfter(today.plusDays(60)) -> "Retires on $date"
        else -> null
    }
}
