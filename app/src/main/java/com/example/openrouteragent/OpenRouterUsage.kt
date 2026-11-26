package com.example.openrouteragent

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
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
    val byok: Double? = 0.0,
    val requests: Long = 0,
    val prompt: Long = 0,
    val completion: Long = 0,
    val cached: Long? = 0,
    val reasoning: Long? = 0,
) {
    val tokens: Long get() = prompt + completion

    operator fun plus(other: UsageTotals) = UsageTotals(
        cost = cost + other.cost,
        byok = if (byok != null && other.byok != null) byok + other.byok else null,
        requests = requests + other.requests,
        prompt = prompt + other.prompt,
        completion = completion + other.completion,
        cached = if (cached != null && other.cached != null) cached + other.cached else null,
        reasoning = if (reasoning != null && other.reasoning != null) reasoning + other.reasoning else null,
    )
}

internal data class UsageActivity(
    val date: LocalDate,
    val model: String,
    val version: String?,
    val provider: String,
    val endpoint: String?,
    val totals: UsageTotals,
)

internal data class UsageActivityReport(val rows: List<UsageActivity>, val unreadableRows: Int)

internal class UsagePayloadException(message: String, val requestId: String? = null) : IOException(message)

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

internal fun parseUsageActivity(body: String): UsageActivityReport {
    val root = try {
        JSONObject(body)
    } catch (_: JSONException) {
        throw UsagePayloadException("Expected a JSON object containing activity records.")
    }
    val data = root.optJSONArray("data") ?: throw UsagePayloadException("Expected an activity list in the data field.")
    val rows = (0 until data.length()).mapNotNull { index -> data.optJSONObject(index)?.let(::parseActivityRow) }
    if (data.length() > 0 && rows.isEmpty()) {
        throw UsagePayloadException("The activity records did not contain readable dates, models, and usage totals.")
    }
    return UsageActivityReport(rows, unreadableRows = data.length() - rows.size)
}

private fun parseActivityRow(row: JSONObject): UsageActivity? {
    val date = row.activityText("date")?.let(::parseActivityDate) ?: return null
    val version = row.activityText("model_permaslug")
    val model = row.activityText("model") ?: version ?: return null
    return UsageActivity(
        date = date,
        model = model,
        version = version,
        provider = row.activityText("provider_name") ?: "Unknown provider",
        endpoint = row.activityText("endpoint_id"),
        totals = UsageTotals(
            cost = row.optDouble("usage").takeIf(Double::isFinite) ?: return null,
            byok = row.optDouble("byok_usage_inference").takeIf(Double::isFinite),
            requests = row.activityCount("requests") ?: return null,
            prompt = row.activityCount("prompt_tokens") ?: return null,
            completion = row.activityCount("completion_tokens") ?: return null,
            cached = row.activityCount("cached_tokens"),
            reasoning = row.activityCount("reasoning_tokens"),
        ),
    )
}

private fun JSONObject.activityText(name: String): String? = (opt(name) as? String)?.takeIf { it.isNotBlank() }

private fun JSONObject.activityCount(name: String): Long? = optLong(name, -1).takeIf { it >= 0 }

private fun parseActivityDate(value: String): LocalDate? = try {
    LocalDate.parse(value)
} catch (_: DateTimeParseException) {
    try {
        OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
    } catch (_: DateTimeParseException) {
        null
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
