package com.example.openrouteragent

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationReplyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RunNotifications.createChannels(app)
    }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out")
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun finishedNotice(): Notification? = shadowOf(manager).allNotifications.firstOrNull {
        it.channelId == "agent_replies"
    }

    private fun viewModel(server: MockWebServer): AgentViewModel {
        val vm = AgentViewModel(
            application = app,
            textModerator = null,
            reportClient = ModerationReportClient(endpointUrl = server.url("/report").toString()),
            client = OpenRouterAgentClient(baseUrl = server.url("/v1/")),
            sdk = TestAgentSdkRuntime(app, server),
            storage = testAgentStorage(app),
        )
        idleUntil { !vm.isLoading }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":[{"id":"mock/model","name":"Mock","supported_parameters":["tools"]}]}"""))
        runBlocking { vm.saveSettings(AgentConfig(apiKey = "test-openrouter-key", model = "mock/model")) }
        idleUntil { !vm.catalogLoading }
        server.takeRequest()
        return vm
    }

    @Test
    fun theReadyNoticeHasAReplyBoxForItsChat() {
        RunNotifications.finished(app, "Reply ready", "Here is the plan.", "chat-1")
        val action = finishedNotice()!!.actions.single()
        assertEquals("Reply", action.title.toString())
        assertEquals(RunNotifications.KEY_REPLY, action.remoteInputs.single().resultKey)
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(RunCommands.REPLY, intent.getStringExtra(RunActionReceiver.EXTRA_COMMAND))
        assertEquals("chat-1", intent.getStringExtra(RunActionReceiver.EXTRA_CHAT))

        RunNotifications.finished(app, "Message not sent", "Blocked.")
        assertNull(finishedNotice()!!.actions)
    }

    @Test
    fun aReplyToTheOpenChatIsSentLikeATypedMessage() {
        MockWebServer().use { server ->
            val vm = viewModel(server)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("Sure, here it is."))
            RunNotifications.finished(app, "Reply ready", "Done.", vm.workspace.id)

            vm.replyFromNotification(vm.workspace.id, "  And the next step?  ")
            idleUntil { vm.workspace.messages.size == 2 && vm.activePrompt == null }

            assertEquals("And the next step?", vm.workspace.messages[0].text)
            assertEquals("Sure, here it is.", vm.workspace.messages[1].text)
            assertEquals("", vm.promptText)
        }
    }

    @Test
    fun aReplyThatCannotGoSaysWhyAndLeavesTheAppAlone() {
        MockWebServer().use { server ->
            val vm = viewModel(server)
            val requests = server.requestCount

            vm.replyFromNotification("another-chat", "Hello")
            idleUntil { finishedNotice() != null }
            assertEquals("That chat is no longer open. Open the app to reply.",
                finishedNotice()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

            vm.promptText = "half-written draft"
            manager.cancelAll()
            vm.replyFromNotification(vm.workspace.id, "Hello")
            idleUntil { finishedNotice() != null }
            assertTrue(finishedNotice()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                .startsWith("You have an unsent message"))
            assertNotNull(finishedNotice()!!.actions)
            assertEquals("half-written draft", vm.promptText)
            assertTrue(vm.workspace.messages.isEmpty())
            assertEquals(requests, server.requestCount)
        }
    }
}
