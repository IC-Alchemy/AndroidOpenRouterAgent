package com.example.openrouteragent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the Windows app's test/main/token-rate.test.mjs. */
class TokenRateTest {
    @Test
    fun noRateUntilACallHasStreamedForHalfASecond() {
        val rate = TokenRate().phase("waiting", null, 0).text(4000, 100)
        assertNull(rate.tokensPerSecond)
        assertEquals(1000, rate.outputTokens)
        assertTrue(rate.estimated)
    }

    @Test
    fun liveRateIsEstimatedFromStreamedTextOverStreamingTime() {
        // Two seconds before the first token is latency, not generation.
        val rate = TokenRate().phase("waiting", null, 0).text(40, 2000).text(400, 2500).text(360, 3000)
        assertEquals(200.0, rate.tokensPerSecond!!, 0.001)
        assertTrue(rate.streaming(3000))
        assertEquals(200, rate.totalTokens)
    }

    @Test
    fun pausesLongerThanTheStreamGapAreLeftOut() {
        val rate = TokenRate().text(200, 0).text(200, 500).text(200, 10_500).text(200, 11_000)
        assertEquals(200.0, rate.tokensPerSecond!!, 0.001)
        assertFalse(rate.streaming(20_000))
    }

    @Test
    fun reportedUsageReplacesTheEstimateWhenTheCallEnds() {
        val rate = TokenRate().phase("waiting", null, 0).text(400, 1000).text(400, 2000).usage(300, 1300, 2000)
        assertEquals(300.0, rate.tokensPerSecond!!, 0.001)
        assertEquals(300.0, rate.averageTokensPerSecond!!, 0.001)
        assertEquals(300, rate.outputTokens)
        assertEquals(1300, rate.totalTokens)
        assertFalse(rate.estimated)
    }

    @Test
    fun privateReasoningCountsTheTimeBeforeTheFirstStreamedText() {
        // 800 reasoning tokens made privately plus 200 streamed, over 5 s.
        val rate = TokenRate().phase("waiting", null, 0).phase("reasoning", "hidden", 3000)
            .text(400, 4000).text(400, 5000).usage(1000, 1050, 5000)
        assertEquals(200.0, rate.tokensPerSecond!!, 0.001)
    }

    @Test
    fun aMultiCallRunShowsTheCurrentCallAndTheRunAverage() {
        val second = TokenRate().phase("requesting", null, 0).text(400, 0).text(400, 1000).usage(200, 300, 1000)
            .phase("tools", null, 1100).phase("requesting", null, 9000)
            .text(400, 10_000).text(400, 11_000).text(400, 11_500)
        assertEquals(2, second.calls)
        assertEquals(200.0, second.tokensPerSecond!!, 0.001) // 300 tokens over 1.5 s
        assertEquals(200.0, second.averageTokensPerSecond!!, 0.001) // 500 tokens over 2.5 s
        assertEquals(500, second.outputTokens)
        assertEquals(600, second.totalTokens)
        val done = second.usage(500, 1000, 11_500)
        assertEquals(200.0, done.tokensPerSecond!!, 0.001)
        assertEquals(1000, done.totalTokens)
    }

    @Test
    fun aCallThatOnlyAsksForAToolIsNotTimed() {
        val rate = TokenRate().phase("waiting", null, 0).usage(40, 50, 3000)
        assertNull(rate.tokensPerSecond)
        assertEquals(40, rate.outputTokens)
    }

    @Test
    fun countsThatRestartAfterAResentRequestAreCarried() {
        val rate = TokenRate().usage(100, 200, 0).usage(30, 80, 1)
        assertEquals(130, rate.outputTokens)
        assertEquals(280, rate.totalTokens)
    }

    @Test
    fun finishedKeepsTheRunAverageForTheReply() {
        val rate = TokenRate().text(400, 0).text(400, 1000).finished(1000)
        assertEquals(200.0, rate.averageTokensPerSecond!!, 0.001)
        assertEquals(200, rate.outputTokens)
        assertEquals(1000L, rate.generationMillis)
        assertNull(TokenRate().finished(0).averageTokensPerSecond)
    }

    @Test
    fun progressFeedsTheRateFromRunUpdates() {
        val progress = AgentProgress(0)
            .apply(listOf(RunUpdate.Phase("waiting", "model", 1)), 0)
            .apply(listOf(RunUpdate.Text("x".repeat(400), false)), 1000)
            .apply(listOf(RunUpdate.Text("y".repeat(400), true)), 2000)
        assertEquals(200.0, progress.rate.tokensPerSecond!!, 0.001)
        val reported = progress.apply(listOf(RunUpdate.Usage(TokenUsage(100, 250, 350))), 2000)
        assertEquals(250.0, reported.rate.tokensPerSecond!!, 0.001)
        assertEquals(350, reported.rate.totalTokens)
    }

    @Test
    fun formatsRates() {
        assertEquals("–", formatRate(null))
        assertEquals("4.3 tok/s", formatRate(4.26))
        assertEquals("42 tok/s", formatRate(42.4))
        assertEquals("152 tok/s", formatRate(151.6))
        assertEquals("–", formatRateNumber(Double.NaN))
    }

    @Test
    fun replyFooterLeadsWithTheSpeed() {
        val usage = TokenUsage(100, 250, 350, cost = 0.0004)
        assertEquals("42 tok/s · $0.0004", replyUsageSummary(usage, 42.4))
        assertEquals("42 tok/s", replyUsageSummary(null, 42.4))
        assertEquals("–", replyUsageSummary(null, null))
        assertTrue(replyUsageDetails(null, usage, 42.4).contains("Speed: 42 tok/s"))
    }
}
