package com.example.openrouteragent

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

internal data class KeyUsage(
    val label: String,
    val lifetime: Double?,
    val daily: Double?,
    val weekly: Double?,
    val monthly: Double?,
    val byok: Double?,
    val limit: Double?,
    val remaining: Double?,
    val reset: String?,
)

internal data class UsageTotals(
    val cost: Double = 0.0,
    val byok: Double = 0.0,
    val requests: Long = 0,
    val prompt: Long = 0,
    val completion: Long = 0,
    val cached: Long = 0,
    val reasoning: Long = 0,
) {
    val tokens: Long get() = prompt + completion

    operator fun plus(other: UsageTotals) = UsageTotals(
        cost + other.cost, byok + other.byok, requests + other.requests,
        prompt + other.prompt, completion + other.completion, cached + other.cached, reasoning + other.reasoning,
    )
}

internal data class UsageActivity(
    val date: LocalDate,
    val model: String,
    val version: String,
    val provider: String,
    val endpoint: String,
    val totals: UsageTotals,
)

internal enum class UsageMetric(val label: String) {
    Cost("Spend"),
    Requests("Requests"),
    Tokens("Tokens");

    fun value(totals: UsageTotals): Double = when (this) {
        Cost -> totals.cost
        Requests -> totals.requests.toDouble()
        Tokens -> totals.tokens.toDouble()
    }

    fun format(totals: UsageTotals): String = when (this) {
        Cost -> usageMoney(totals.cost)
        Requests -> usageCount(totals.requests)
        Tokens -> usageCount(totals.tokens)
    }
}

internal fun usageMoney(value: Double): String {
    val amount = BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros()
    if (value > 0 && amount.signum() == 0) return "<$0.00000001"
    return "$" + amount.setScale(maxOf(2, amount.scale())).toPlainString()
}

internal fun usageCount(value: Long): String = String.format(Locale.getDefault(), "%,d", value)

internal fun parseKeyUsage(body: String): KeyUsage {
    val data = JSONObject(body).getJSONObject("data")
    fun amount(name: String) = data.optDouble(name).takeIf { it.isFinite() && it >= 0 }
    return KeyUsage(
        data.optString("label").takeUnless { data.isNull("label") }.orEmpty(),
        amount("usage"), amount("usage_daily"), amount("usage_weekly"), amount("usage_monthly"),
        amount("byok_usage"), amount("limit"), amount("limit_remaining"),
        data.optString("limit_reset").takeUnless { data.isNull("limit_reset") || it.isBlank() },
    )
}

internal fun parseUsageActivity(body: String): List<UsageActivity> {
    val rows = JSONObject(body).getJSONArray("data")
    return List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        UsageActivity(
            date = LocalDate.parse(row.getString("date")),
            model = row.getString("model"),
            version = row.getString("model_permaslug"),
            provider = row.getString("provider_name"),
            endpoint = row.getString("endpoint_id"),
            totals = UsageTotals(
                cost = row.getDouble("usage"),
                byok = row.getDouble("byok_usage_inference"),
                requests = row.getLong("requests"),
                prompt = row.getLong("prompt_tokens"),
                completion = row.getLong("completion_tokens"),
                cached = row.getLong("cached_tokens"),
                reasoning = row.getLong("reasoning_tokens"),
            ),
        )
    }
}

internal fun filterUsageActivity(
    rows: List<UsageActivity>,
    today: LocalDate,
    days: Int,
    model: String? = null,
    provider: String? = null,
): List<UsageActivity> {
    val start = today.minusDays(days.toLong())
    return rows.filter {
        it.date >= start && it.date < today &&
            (model == null || it.model == model) && (provider == null || it.provider == provider)
    }
}

internal fun Iterable<UsageActivity>.usageTotals(): UsageTotals =
    fold(UsageTotals()) { total, row -> total + row.totals }

internal fun dailyUsage(
    rows: List<UsageActivity>,
    today: LocalDate,
    days: Int,
): List<Pair<LocalDate, UsageTotals>> {
    val grouped = rows.groupBy { it.date }
    return List(days) { index ->
        val date = today.minusDays((days - index).toLong())
        date to grouped[date].orEmpty().usageTotals()
    }
}

internal fun usageRanking(
    rows: List<UsageActivity>,
    metric: UsageMetric,
    group: (UsageActivity) -> String,
): List<Pair<String, UsageTotals>> =
    rows.groupBy(group).map { (label, entries) -> label to entries.usageTotals() }
        .sortedWith(compareByDescending<Pair<String, UsageTotals>> { metric.value(it.second) }.thenBy { it.first })
