package com.example.openrouteragent

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A failed or stopped run keeps the chat, and a run that stopped mid-way continues from its checkpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunRecoveryViewModelTest {

    private fun awaitCondition(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() >= deadline) throw AssertionError("Timed out after ${timeoutMs}ms")
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun configuredViewModel(server: MockWebServer): AgentViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AgentViewModel(
            application = app,
            textModerator = null,
            reportClient = ModerationReportClient(endpointUrl = server.url("/report").toString()),
            client = OpenRouterAgentClient(baseUrl = server.url("/v1/")),
            sdk = TestAgentSdkRuntime(app, server),
            storage = testAgentStorage(app),
        )
        awaitCondition { !vm.isLoading }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":[{"id":"mock/model","name":"Mock","supported_parameters":["tools"]}]}"""))
        runBlocking { vm.saveSettings(AgentConfig(apiKey = "test-openrouter-key", model = "mock/model")) }
        awaitCondition { !vm.catalogLoading }
        server.takeRequest()
        return vm
    }

    private fun saved(vm: AgentViewModel): Workspace? =
        ChatDatabase(ApplicationProvider.getApplicationContext<Application>()).use { it.active(chatScope(vm.config)) }

    @Test
    fun failedFirstRunKeepsTheChatAndTheMessage() {
        MockWebServer().use { server ->
            val vm = configuredViewModel(server)
            server.enqueue(MockResponse().setResponseCode(400).setHeader("X-Test-Reasoning", "Working on it"))
            vm.send("Build the whole site")
            awaitCondition { vm.activePrompt == null && vm.interrupted != null }

            // The message stays in the chat, not only in the composer, with a way to continue.
            assertEquals("", vm.promptText)
            assertTrue(vm.canContinue)
            val stored = checkNotNull(saved(vm)?.interruptedTurn)
            assertEquals("Build the whole site", stored.prompt)
            assertEquals(false, stored.pending)
            assertNotNull(stored.error)
        }
    }

    @Test
    fun nextMessageKeepsTheStoppedTurnInTheChat() {
        MockWebServer().use { server ->
            val vm = configuredViewModel(server)
            server.enqueue(MockResponse().setResponseCode(400).setHeader("X-Test-Reasoning", "Working on it"))
            vm.send("First task")
            awaitCondition { vm.activePrompt == null && vm.interrupted != null }
            server.takeRequest()

            server.enqueue(MockResponse().setBody("Second answer"))
            vm.send("Second task")
            awaitCondition { vm.activePrompt == null && vm.workspace.messages.size == 4 }

            val messages = checkNotNull(saved(vm)).messages
            assertEquals(listOf("First task", "The run stopped before the agent wrote a reply.", "Second task",
                "Second answer"), messages.map { it.text })
            assertTrue(messages[1].notice.orEmpty().startsWith("This reply stopped before it finished"))
            assertNull(saved(vm)?.interruptedTurn)
        }
    }

    @Test
    fun runThatFailsWithoutDoingAnythingGoesBackToTheComposer() {
        MockWebServer().use { server ->
            val vm = configuredViewModel(server)
            server.enqueue(MockResponse().setResponseCode(401))
            vm.send("Hello")
            awaitCondition { vm.activePrompt == null && vm.turnError != null }
            assertEquals("Hello", vm.promptText)
            assertNull(vm.interrupted)
            assertNull(vm.workspace.interruptedTurn)
        }
    }

    @Test
    fun resumableFailureContinuesFromTheCheckpoint() {
        MockWebServer().use { server ->
            val vm = configuredViewModel(server)
            server.enqueue(MockResponse().setResponseCode(502)
                .setHeader("X-Test-Checkpoint", """[{"type":"message","role":"user","content":"Long job"}]""")
                .setHeader("X-Test-Partial", "Step 1 done"))
            server.enqueue(MockResponse().setBody("All steps done"))
            vm.send("Long job")
            awaitCondition { vm.activePrompt == null && vm.workspace.messages.size == 2 }

            assertEquals("Long job", server.takeRequest().body.readUtf8())
            assertTrue(server.takeRequest().body.readUtf8().startsWith("The previous request stopped before you finished"))
            val reply = vm.workspace.messages.last()
            assertEquals("All steps done", reply.text)
            assertTrue(reply.notice.orEmpty().contains("continued this run once"))
            assertEquals("Long job", vm.workspace.messages.first().text)
        }
    }

    @Test
    fun pendingTurnFromAnAppThatClosedShowsAsStopped() {
        val turn = InterruptedTurn("Half done", "", "", null, pending = true)
        val shown = stoppedProgress(turn.copy(pending = false, error = APP_CLOSED_MID_RUN), 0L)
        assertEquals("", shown.answer)
        val kept = Workspace(interruptedTurn = turn.copy(pending = false, error = APP_CLOSED_MID_RUN)).withStoppedTurnKept()
        assertEquals("Half done", kept.messages.first().text)
        assertTrue(kept.messages.last().notice.orEmpty().contains(APP_CLOSED_MID_RUN))
    }
}
