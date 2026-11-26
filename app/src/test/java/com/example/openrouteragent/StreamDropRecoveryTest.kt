package com.example.openrouteragent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StreamDropRecoveryTest {
    private val droppedWhileThinking = IOException("The OpenRouter stream closed unexpectedly (SocketException: " +
        "Software caused connection abort). $STREAM_DROP_SAFE_TO_RESEND, so it can be sent again. [request r/1]")
    private val droppedAfterTool = IOException("The OpenRouter stream closed unexpectedly (SocketException: " +
        "Software caused connection abort). A hosted tool had started in this response, so it was not simply sent again.")
    private val saved = RunCheckpoint(history = "[]", partial = null)

    @Test
    fun aDropWhileOnlyThinkingIsResentWhenNothingWasSaved() {
        assertEquals(RunRecoveryStep.Resend, recoveryStep(droppedWhileThinking, checkpoint = null, continues = 0))
    }

    @Test
    fun aDropAfterAHostedToolStartedIsNotResent() {
        assertEquals(RunRecoveryStep.Stop, recoveryStep(droppedAfterTool, checkpoint = null, continues = 0))
    }

    @Test
    fun aCheckpointIsUsedWhenOneExistsWhateverTheDrop() {
        assertEquals(RunRecoveryStep.Continue(saved), recoveryStep(droppedAfterTool, saved, continues = 0))
        assertEquals(RunRecoveryStep.Continue(saved), recoveryStep(droppedWhileThinking, saved, continues = 1))
    }

    @Test
    fun aCheckpointWithoutReplayableHistoryCountsAsNone() {
        val tooBig = RunCheckpoint(history = null, partial = null)
        assertEquals(RunRecoveryStep.Resend, recoveryStep(droppedWhileThinking, tooBig, continues = 0))
        assertEquals(RunRecoveryStep.Stop, recoveryStep(droppedAfterTool, tooBig, continues = 0))
    }

    @Test
    fun recoveryStopsAfterTheLimit() {
        assertEquals(RunRecoveryStep.Stop, recoveryStep(droppedWhileThinking, null, MAX_AUTO_CONTINUES))
        assertEquals(RunRecoveryStep.Stop, recoveryStep(droppedWhileThinking, saved, MAX_AUTO_CONTINUES))
    }

    @Test
    fun failuresThatWouldRepeatAreNotRecovered() {
        val badKey = IOException("OpenRouter HTTP 401. OpenRouter rejected the API key. $STREAM_DROP_SAFE_TO_RESEND")
        assertEquals(RunRecoveryStep.Stop, recoveryStep(badKey, saved, continues = 0))
    }

    @Test
    fun thePhoneLineNamesARecentNetworkSwitch() {
        val now = 1_000_000L
        val line = describePhoneState(screenOn = false, appInFront = false, powerSave = true, deviceIdle = false,
            changes = listOf(NetworkChange(now - 600_000, "lost its network"),
                NetworkChange(now - 2_000, "switched from Wi-Fi to mobile data")), now = now)
        assertEquals("screen off, app in the background, battery saver on, " +
            "phone switched from Wi-Fi to mobile data 2 s earlier", line)
    }

    @Test
    fun thePhoneLineSaysWhenTheNetworkDidNotChange() {
        val line = describePhoneState(screenOn = true, appInFront = true, powerSave = false, deviceIdle = false,
            changes = emptyList(), now = 5_000L)
        assertTrue(line, line.startsWith("screen on, app on screen, no network change"))
    }
}
