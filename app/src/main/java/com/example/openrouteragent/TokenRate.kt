package com.example.openrouteragent

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tokens per second for a run, which may make several model calls. The Windows app has the same rules in
 * src/main/token-rate.mjs.
 *
 * While a call streams, its rate is estimated from the streamed reply and reasoning text (about four characters a
 * token). When the provider reports usage at the end of the call, the call's reported output tokens, reasoning
 * included, replace the estimate. Only time spent generating counts: pauses over [RATE_STREAM_GAP_MS], such as tool
 * runs, are left out. When a call made more tokens than it streamed, such as private reasoning, the time before its
 * first streamed text counts too, since that is when those tokens were made.
 */
internal data class TokenRate(
    val call: Call? = null,
    val calls: Int = 0,
    val doneTokens: Long = 0,
    val doneMillis: Long = 0,
    val lastRate: Double? = null,
    /** Cumulative counts from the provider. A resent request starts counting again, so earlier counts are carried. */
    val output: Int = 0,
    val total: Int = 0,
    val carriedOutput: Int = 0,
    val carriedTotal: Int = 0,
    /** Estimated tokens of calls that ended with no usage report. */
    val unreported: Int = 0,
) {
    data class Call(
        val at: Long,
        val first: Long? = null,
        val last: Long? = null,
        val chars: Long = 0,
        val millis: Long = 0,
        val hidden: Boolean = false,
    )

    private val liveTokens: Int get() = call?.let { (it.chars / CHARS_PER_TOKEN).toInt() } ?: 0
    private val timing: Boolean get() = call.let { it != null && it.chars > 0 && it.millis >= MIN_RATE_MS }

    /** The current call's rate while it streams, else the latest finished call's, or null before there is one. */
    val tokensPerSecond: Double?
        get() = if (timing) liveTokens * 1000.0 / call!!.millis else lastRate

    /** Output tokens over generation time for the whole run so far. */
    val averageTokensPerSecond: Double?
        get() {
            val tokens = doneTokens + if (timing) liveTokens else 0
            val millis = doneMillis + if (timing) call!!.millis else 0
            return if (millis >= MIN_RATE_MS) tokens * 1000.0 / millis else null
        }

    val generationMillis: Long get() = doneMillis + if (timing) call!!.millis else 0
    val outputTokens: Int get() = carriedOutput + output + unreported + liveTokens
    val totalTokens: Int get() = carriedTotal + total + unreported + liveTokens
    /** True when part of the counts is an estimate from streamed text. */
    val estimated: Boolean get() = liveTokens + unreported > 0

    fun streaming(now: Long): Boolean = call?.last?.let { now - it <= RATE_STREAM_GAP_MS } == true

    fun text(length: Int, now: Long): TokenRate {
        if (length <= 0) return this
        val started = if (call == null) start(now) else this
        val c = started.call!!
        val gap = c.last?.let { now - it }?.takeIf { it in 0..RATE_STREAM_GAP_MS } ?: 0
        return started.copy(call = c.copy(chars = c.chars + length, millis = c.millis + gap,
            first = c.first ?: now, last = now))
    }

    fun phase(phase: String, detail: String?, now: Long): TokenRate = when {
        phase in CALL_STARTS -> {
            val c = call
            when {
                c != null && (c.chars > 0 || c.hidden) -> finish(now, 0).start(now)
                c == null -> start(now)
                else -> copy(call = c.copy(at = now))
            }
        }
        phase == "reasoning" && detail == "hidden" -> {
            val started = if (call == null) start(now) else this
            started.copy(call = started.call!!.copy(hidden = true))
        }
        else -> this
    }

    fun usage(outputTokens: Int, totalTokens: Int, now: Long): TokenRate {
        val nextOutput = max(0, outputTokens)
        val nextTotal = max(0, totalTokens)
        val reset = nextOutput < output || nextTotal < total
        val base = if (reset) copy(carriedOutput = carriedOutput + output, carriedTotal = carriedTotal + total,
            output = 0, total = 0) else this
        val reported = nextOutput - base.output
        val counted = base.copy(output = nextOutput, total = nextTotal)
        return if (counted.call != null) counted.finish(now, reported) else counted
    }

    /** Ends the run: a call still streaming counts with its estimate. */
    fun finished(now: Long): TokenRate = if (call != null && call.chars > 0) finish(now, 0) else this

    private fun start(now: Long) = copy(call = Call(at = now), calls = calls + 1)

    private fun finish(now: Long, reported: Int): TokenRate {
        val c = call ?: return this
        val estimate = (c.chars / CHARS_PER_TOKEN).toInt()
        val tokens = if (reported > 0) reported else estimate
        val ended = copy(call = null, unreported = unreported + if (reported > 0) 0 else estimate)
        // A call that streamed nothing, such as one that only asks for a tool, has no generation time to measure.
        if (c.chars == 0L && !c.hidden) return ended
        var millis = c.millis + (c.last?.let { now - it }?.takeIf { it in 0..RATE_STREAM_GAP_MS } ?: 0)
        if (c.hidden || tokens > estimate * 1.5 + 16) millis += max(0, (c.first ?: now) - c.at)
        if (tokens <= 0 || millis < MIN_RATE_MS) return ended
        return ended.copy(doneTokens = doneTokens + tokens, doneMillis = doneMillis + millis,
            lastRate = tokens * 1000.0 / millis)
    }

    private companion object {
        const val CHARS_PER_TOKEN = 4L
        /** Streaming time a call needs before its rate shows, so the first burst of text doesn't read as thousands. */
        const val MIN_RATE_MS = 500L
        val CALL_STARTS = setOf("requesting", "connecting", "waiting")
    }
}

internal const val RATE_STREAM_GAP_MS = 2_000L

/** "42", "4.3" or "152": a rate without its unit, or "–" before there is one. */
internal fun formatRateNumber(rate: Double?): String = when {
    rate == null || !rate.isFinite() -> "–"
    rate >= 10 -> rate.roundToInt().toString()
    else -> String.format(Locale.US, "%.1f", rate)
}

/** "42 tok/s", or "–" before there is a rate. */
internal fun formatRate(rate: Double?): String =
    if (rate == null || !rate.isFinite()) "–" else formatRateNumber(rate) + " tok/s"
