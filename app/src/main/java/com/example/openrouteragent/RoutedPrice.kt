package com.example.openrouteragent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * One provider endpoint for a model, from `GET /api/v1/models/{author}/{slug}/endpoints`.
 * Prices are US dollars per token (per request for [request]), already reduced by OpenRouter's discount.
 */
data class ModelEndpoint(
    val providerName: String,
    /** The endpoint's tag, such as `deepinfra/fp8`. Its first part is the provider ID. */
    val tag: String,
    val prompt: Double,
    val completion: Double,
    val request: Double = 0.0,
    val quantization: String? = null,
    val serviceTier: String? = null,
    /** Tokens per second over the last 30 minutes, when OpenRouter reports it. */
    val throughput: Double? = null,
    /** Milliseconds to the first token over the last 30 minutes, when OpenRouter reports it. */
    val latency: Double? = null,
) {
    val providerSlug: String get() = tag.substringBefore('/').trim().lowercase()

    /** True when [token] names this endpoint by provider ID, tag or display name, as provider routing does. */
    fun matches(token: String): Boolean {
        val wanted = token.trim().lowercase()
        return wanted.isNotEmpty() && (wanted == providerSlug || wanted == tag.lowercase() ||
            wanted == providerName.lowercase())
    }
}

/**
 * The price a request would be billed at. [provider] is null when the routing mode spreads requests over
 * several providers; [maxPrompt] and [maxCompletion] then give the top of the range.
 */
data class RoutedPrice(
    val mode: String,
    val provider: String?,
    val prompt: Double,
    val completion: Double,
    val request: Double = 0.0,
    val maxPrompt: Double? = null,
    val maxCompletion: Double? = null,
    val providerCount: Int = 1,
)

internal fun parseModelEndpoints(raw: String): List<ModelEndpoint> {
    val data = JSONObject(raw).optJSONObject("data") ?: return emptyList()
    val endpoints = data.optJSONArray("endpoints") ?: JSONArray()
    return (0 until endpoints.length()).mapNotNull { index ->
        val item = endpoints.optJSONObject(index) ?: return@mapNotNull null
        val pricing = item.optJSONObject("pricing") ?: return@mapNotNull null
        val discount = (pricing.opt("discount") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0
        fun price(key: String) = pricing.decimal(key)?.let { it * (1 - discount) }
        val prompt = price("prompt") ?: return@mapNotNull null
        val completion = price("completion") ?: return@mapNotNull null
        val name = (item.opt("provider_name") as? String)?.trim().orEmpty()
        val tag = (item.opt("tag") as? String)?.trim().orEmpty().ifEmpty { name.lowercase() }
        if (name.isEmpty() && tag.isEmpty()) return@mapNotNull null
        ModelEndpoint(name.ifEmpty { tag }, tag, prompt, completion, price("request") ?: 0.0,
            (item.opt("quantization") as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" },
            (item.opt("service_tier") as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            item.metric("throughput_last_30m"), item.metric("latency_last_30m"))
    }
}

private fun JSONObject.decimal(key: String): Double? = when (val value = opt(key)) {
    is String -> value.toDoubleOrNull()
    is Number -> value.toDouble()
    else -> null
}?.takeIf { it.isFinite() && it >= 0 }

/** A recent speed figure: a number, or the p50 of a percentile object. */
private fun JSONObject.metric(key: String): Double? = when (val value = opt(key)) {
    is Number -> value.toDouble()
    is JSONObject -> (value.opt("p50") as? Number)?.toDouble()
    else -> null
}?.takeIf { it.isFinite() && it > 0 }

private val ROUTING_SUFFIXES = setOf("floor", "nitro", "exacto")

/** The model ID without a `:floor`, `:nitro` or `:exacto` suffix. Other suffixes such as `:free` stay. */
internal fun withoutRoutingVariant(modelId: String): String {
    val parts = modelId.split(':')
    return (listOf(parts.first()) + parts.drop(1).filter { it !in ROUTING_SUFFIXES }).joinToString(":")
}

private fun csv(value: String) = value.split(',').map(String::trim).filter(String::isNotEmpty)

/**
 * Works out which provider OpenRouter would bill a request to, and at what price, from the model's endpoints,
 * the routing settings and the workspace's provider rules. Returns null when no endpoint is allowed.
 *
 * Provider order, only and ignore lists, quantizations and maximum prices narrow the endpoints first, as
 * provider routing does. Then Floor and Price pick the cheapest, Nitro and Throughput the fastest, and
 * Latency the quickest to answer. Auto and Exacto weigh price with other signals, so they return the
 * range across the allowed endpoints instead of one provider.
 */
internal fun routedPrice(
    endpoints: List<ModelEndpoint>, sdk: SdkSettings, rules: GuardrailRules?,
): RoutedPrice? {
    val only = csv(sdk.providerOnly)
    val ignore = csv(sdk.providerIgnore)
    val quantizations = csv(sdk.quantizations).map(String::lowercase)
    var pool = endpoints.filter { endpoint ->
        (rules == null || rules.showsProvider(endpoint.providerSlug)) &&
            (only.isEmpty() || only.any(endpoint::matches)) && ignore.none(endpoint::matches) &&
            (quantizations.isEmpty() || endpoint.quantization == null || endpoint.quantization.lowercase() in quantizations) &&
            // Maximum prices are set in dollars per million tokens.
            (sdk.maxPromptPrice == null || endpoint.prompt * 1_000_000 <= sdk.maxPromptPrice + 1e-9) &&
            (sdk.maxCompletionPrice == null || endpoint.completion * 1_000_000 <= sdk.maxCompletionPrice + 1e-9)
    }
    if (pool.isEmpty()) return null
    val mode = sdk.routingVariant.ifEmpty { sdk.providerSort }
    // Flex endpoints are only used with Floor, and priority endpoints only with Nitro.
    val tiers = pool.filter { endpoint ->
        when (endpoint.serviceTier) {
            "flex" -> mode == "floor"
            "priority" -> mode == "nitro"
            else -> true
        }
    }
    if (tiers.isNotEmpty()) pool = tiers
    val label = ROUTING_LABELS[mode] ?: "Auto"
    fun single(endpoint: ModelEndpoint, why: String = label) =
        RoutedPrice(why, endpoint.providerName, endpoint.prompt, endpoint.completion, endpoint.request)
    val cost = compareBy<ModelEndpoint>({ it.prompt + it.completion }, { it.request })

    csv(sdk.providerOrder).firstNotNullOfOrNull { token -> pool.firstOrNull { it.matches(token) } }?.let {
        return single(it, "Provider order")
    }
    if (pool.size == 1) return single(pool.single())
    return when (mode) {
        "floor", "price" -> single(pool.minWith(cost))
        "nitro", "throughput" -> single(pool.maxWith(compareBy<ModelEndpoint> { it.throughput ?: -1.0 }.then(cost.reversed())))
        "latency" -> single(pool.minWith(compareBy<ModelEndpoint> { it.latency ?: Double.MAX_VALUE }.then(cost)))
        else -> {
            val cheapest = pool.minWith(cost)
            RoutedPrice(label, null, pool.minOf { it.prompt }, pool.minOf { it.completion }, cheapest.request,
                pool.maxOf { it.prompt }, pool.maxOf { it.completion }, pool.size)
                .let { if (it.maxPrompt == it.prompt && it.maxCompletion == it.completion) it.copy(maxPrompt = null, maxCompletion = null) else it }
        }
    }
}

private val ROUTING_LABELS = mapOf("" to "Auto", "floor" to "Floor", "nitro" to "Nitro", "exacto" to "Exacto",
    "price" to "Price", "throughput" to "Throughput", "latency" to "Latency")

/** Such as "Together · $0.6 in · $0.8 out per 1M tokens", or a range across providers for Auto. */
internal fun routedPriceLabel(price: RoutedPrice): String {
    fun perMillion(value: Double) = money(value * 1_000_000)
    fun range(low: Double, high: Double?) = if (high == null || high == low) perMillion(low) else
        "${perMillion(low)}–${perMillion(high)}"
    val tokens = "${range(price.prompt, price.maxPrompt)} in · ${range(price.completion, price.maxCompletion)} out per 1M tokens"
    val perRequest = if (price.request > 0) " + ${money(price.request)} per request" else ""
    val who = price.provider ?: "${price.providerCount} providers"
    return "$who · $tokens$perRequest"
}

private fun money(value: Double): String {
    if (value == 0.0) return "$0"
    val text = when {
        value >= 100 -> "%.0f".format(Locale.US, value)
        value >= 1 -> "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')
        value >= 0.001 -> "%.3f".format(Locale.US, value).trimEnd('0').trimEnd('.')
        else -> "%.6f".format(Locale.US, value).trimEnd('0').trimEnd('.')
    }
    return "$$text"
}
