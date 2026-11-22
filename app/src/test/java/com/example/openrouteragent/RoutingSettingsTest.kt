package com.example.openrouteragent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingSettingsTest {
    private val model = CatalogModel("mock/model", "Fixture", 30_000, setOf("tools"))

    @Test
    fun routingSettingsAreCheckedAndSentToTheRuntime() {
        val sdk = SdkSettings(routingVariant = "floor", zdr = true, distillableText = true, quantizations = "fp8,bf16",
            minThroughput = 40.0, maxLatency = 3.0, maxPromptPrice = 1.5, maxCompletionPrice = 6.0)
        assertNull(sdk.problem())
        val sent = runtimeOptions(AgentConfig(apiKey = "k", sdk = sdk), Workspace(), "Hi", model).getJSONObject("sdk")
        assertEquals("floor", sent.getString("routingVariant"))
        assertTrue(sent.getBoolean("zdr"))
        assertTrue(sent.getBoolean("distillableText"))
        assertEquals("fp8,bf16", sent.getString("quantizations"))
        assertEquals(1.5, sent.getDouble("maxPromptPrice"), 0.0)
    }

    @Test
    fun invalidRoutingSettingsAreRejected() {
        listOf(
            SdkSettings(routingVariant = "turbo"),
            SdkSettings(routingVariant = "nitro", providerSort = "price"),
            SdkSettings(quantizations = "fp8, int3"),
            SdkSettings(minThroughput = 0.0),
            SdkSettings(maxLatency = -1.0),
            SdkSettings(maxCompletionPrice = -0.1),
        ).forEach { assertNotNull(it.toString(), it.problem()) }
    }
}
