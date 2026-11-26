package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkWaitTest {
    @Test
    fun returnsAtOnceWhenTheNetworkWorks() = runBlocking {
        var checks = 0
        assertTrue(waitForNetwork({ checks++; true }, timeoutMillis = 1_000, pollMillis = 1))
        assertEquals(1, checks)
    }

    @Test
    fun waitsUntilTheNewNetworkComesUp() = runBlocking {
        var checks = 0
        assertTrue(waitForNetwork({ ++checks >= 3 }, timeoutMillis = 5_000, pollMillis = 1))
        assertEquals(3, checks)
    }

    @Test
    fun givesUpAfterTheTimeout() = runBlocking {
        var clock = 0L
        val came = waitForNetwork({ false }, timeoutMillis = 30_000, pollMillis = 1, now = { clock.also { clock += 10_000 } })
        assertFalse(came)
    }
}
