package com.example.openrouteragent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RunRecoveryTest {

    @Test
    fun progressingRunIsNeverCutOffByTime() {
        // 45 minutes in, with recent activity: still fine. The old limit ended every run at 15 minutes.
        assertNull(runStallProblem(elapsedMillis = 45 * 60_000L, idleMillis = 5_000L, waitingForUser = false))
    }

    @Test
    fun quietRunEndsAndCanContinue() {
        val problem = checkNotNull(runStallProblem(20 * 60_000L, RUN_IDLE_LIMIT_MILLIS, waitingForUser = false))
        assertTrue(problem.resumable)
        assertTrue(resumableFailure(problem))
    }

    @Test
    fun waitingForTheUserDoesNotCountAsQuiet() {
        assertNull(runStallProblem(30 * 60_000L, 25 * 60_000L, waitingForUser = true))
    }

    @Test
    fun backstopEndsTheRunWithoutContinuing() {
        val problem = checkNotNull(runStallProblem(RUN_CEILING_MILLIS, 0L, waitingForUser = true))
        assertFalse(problem.resumable)
        assertFalse(resumableFailure(problem))
    }

    @Test
    fun onlyFailuresAFreshRequestCanFixAreResumed() {
        assertTrue(resumableFailure(IOException("OpenRouter sent no data for 90 seconds.")))
        assertTrue(resumableFailure(IOException("OpenRouter HTTP 502: Bad gateway")))
        assertTrue(resumableFailure(IOException("Android restarted the Agent SDK runtime.")))
        assertFalse(resumableFailure(IOException("OpenRouter HTTP 401: No auth credentials found")))
        assertFalse(resumableFailure(IOException("OpenRouter HTTP 402: Insufficient credits")))
        assertFalse(resumableFailure(IOException("OpenRouter HTTP 429: Rate limited")))
        assertFalse(resumableFailure(IllegalStateException()))
    }

    @Test
    fun agentModeGetsLongTaskGuidanceAndChatModeDoesNot() {
        val agent = AgentConfig(systemInstructions = "Be brief.")
        assertTrue(runInstructions(agent).startsWith("Be brief."))
        assertTrue(runInstructions(agent).contains("PROGRESS.md"))
        assertEquals("Be brief.", runInstructions(agent.withoutTools()))
    }

    @Test
    fun checkpointKeepsHistoryAndWhatTheRunMade() {
        val event = JSONObject()
            .put("history", JSONArray().put(JSONObject().put("type", "message").put("role", "user").put("content", "Go")))
            .put("response", JSONObject().put("status", "incomplete").put("output", JSONArray()))
        val checkpoint = parseRunCheckpoint(event, maxHistoryChars = 10_000)
        assertNotNull(checkpoint.history)
        assertNotNull(checkpoint.partial)
        assertNull(parseRunCheckpoint(event, maxHistoryChars = 5).history)
        assertNull(parseRunCheckpoint(JSONObject().put("response", "bad"), 10_000).partial)
    }

    @Test
    fun continuedRunJoinsWhatEachRequestDid() {
        fun result(text: String, step: String, cost: Double) = AgentRunResult(text, null,
            listOf(ShellExecutionStep(null, listOf(step), emptyList(), emptyList())), listOf("q-$step"), emptyList(), "c1",
            TokenUsage(10, 5, 15, cost), "m")
        val merged = mergeContinuedRun(listOf(result("", "a", 0.1)), result("Done", "b", 0.2), continues = 1)
        assertEquals("Done", merged.assistantText)
        assertEquals(2, merged.shellSteps.size)
        assertEquals(listOf("q-a", "q-b"), merged.searchQueries)
        assertEquals(30, merged.usage?.totalTokens)
        assertEquals(0.3, merged.usage?.cost ?: 0.0, 1e-9)
        assertTrue(merged.notice.orEmpty().contains("continued this run once"))
        val plain = result("Hi", "x", 0.0)
        assertEquals(plain, mergeContinuedRun(emptyList(), plain, 0))
    }

    @Test
    fun stoppedTurnMovesIntoTheChatWithItsCheckpoint() {
        val history = """[{"type":"message","role":"user","content":"Build it"}]"""
        val chat = Workspace(
            messages = listOf(ChatEntry(isUser = true, text = "Hi"), ChatEntry(isUser = false, text = "Hello")),
            sdkState = "old-state",
            interruptedTurn = InterruptedTurn("Build it", "Half way", "", "The agent sent no updates.",
                history = history, containerId = "sandbox-7", searchQueries = listOf("docs")),
        )
        val kept = chat.withStoppedTurnKept()
        assertEquals(listOf("Hi", "Hello", "Build it", "Half way"), kept.messages.map { it.text })
        assertTrue(kept.messages.last().notice.orEmpty().contains("The agent sent no updates."))
        assertEquals(listOf("docs"), kept.messages.last().searchQueries)
        assertEquals(history, kept.sdkHistory)
        assertNull(kept.sdkState)
        assertEquals("sandbox-7", kept.containerId)
        assertNull(kept.interruptedTurn)
        // Nothing to move: the chat is unchanged.
        assertEquals(kept, kept.withStoppedTurnKept())
    }

    @Test
    fun stoppedTurnWithoutCheckpointStillReachesTheModel() {
        val chat = Workspace(sdkHistory = """[{"type":"message","role":"user","content":"Hi"}]""",
            interruptedTurn = InterruptedTurn("Next", "Partial", "", "Stopped"))
        val history = JSONArray(checkNotNull(chat.withStoppedTurnKept().sdkHistory))
        assertEquals(3, history.length())
        assertEquals("Next", history.getJSONObject(1).getString("content"))
        assertEquals("Partial", history.getJSONObject(2).getString("content"))
    }

    @Test
    fun stoppedTurnWithNoReplySaysSo() {
        val entries = stoppedTurnEntries(InterruptedTurn("Do it", "", "", null))
        assertEquals("The run stopped before the agent wrote a reply.", entries.last().text)
    }

    @Test
    fun savedStoppedTurnRebuildsItsTimeline() {
        val turn = InterruptedTurn("Do it", "Partial", "Thinking", "Stopped",
            toolActivity = listOf(ToolActivity("shell", "completed", "ls")))
        val progress = stoppedProgress(turn, 0L)
        assertEquals(3, progress.timeline.size)
        assertEquals("shell", progress.tool("saved-0")?.name)
    }

    @Test
    fun toolsStillRunningWhenTheRunStoppedAreMarkedStopped() {
        val progress = AgentProgress(0L, tools = listOf(LiveTool("1", "shell", "in_progress", null),
            LiveTool("2", "web_search", "completed", null)))
        assertEquals(listOf("stopped", "completed"), progress.stopped().tools.map { it.status })
        assertEquals("continuing", progress.continuing(1, 3).phase)
    }
}
