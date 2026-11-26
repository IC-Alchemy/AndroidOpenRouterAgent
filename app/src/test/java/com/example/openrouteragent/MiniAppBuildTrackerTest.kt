package com.example.openrouteragent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiniAppBuildTrackerTest {
    private val app = MiniApp(id = "a1", name = "Pomodoro")

    @Test
    fun `a build keeps going after the screen that started it goes away`() = runTest {
        // The view model's scope outlives the sheet; the sheet's own scope is cancelled when it closes.
        val ownerScope = TestScope(StandardTestDispatcher(testScheduler))
        val sheetScope = TestScope(StandardTestDispatcher(testScheduler))
        val tracker = MiniAppBuildTracker(ownerScope)
        val result = CompletableDeferred<MiniApp>()

        tracker.start("Make me a pomodoro timer") { result.await() }
        sheetScope.cancel()
        ownerScope.runCurrent()
        assertEquals(1, tracker.pending.value.size)

        result.complete(app)
        ownerScope.runCurrent()
        assertTrue(tracker.pending.value.isEmpty())
        val done = tracker.outcomes.value.single() as MiniAppBuildOutcome.Done
        assertEquals("Pomodoro", done.app.name)
        assertEquals("Make me a pomodoro timer", done.build.prompt)
        ownerScope.cancel()
    }

    @Test
    fun `busy and idle callbacks fire once for overlapping builds`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val events = mutableListOf<String>()
        var lastOutcome: MiniAppBuildOutcome? = null
        val tracker = MiniAppBuildTracker(
            scope,
            onBusy = { events += "busy:${it.prompt}" },
            onIdle = { events += "idle"; lastOutcome = it },
        )
        val first = CompletableDeferred<MiniApp>()
        val second = CompletableDeferred<MiniApp>()
        tracker.start("one") { first.await() }
        tracker.start("two") { second.await() }
        scope.runCurrent()
        assertTrue(tracker.busy)

        first.complete(app)
        scope.runCurrent()
        assertTrue(tracker.busy)
        assertEquals(listOf("busy:one"), events)

        second.completeExceptionally(IllegalStateException("No model"))
        scope.runCurrent()
        assertFalse(tracker.busy)
        assertEquals(listOf("busy:one", "idle"), events)
        assertEquals("No model", (lastOutcome as MiniAppBuildOutcome.Failed).message)
        assertEquals(2, tracker.outcomes.value.size)
        scope.cancel()
    }

    @Test
    fun `taken outcomes are not shown twice`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val tracker = MiniAppBuildTracker(scope)
        tracker.start("one") { app }
        scope.runCurrent()
        val shown = tracker.outcomes.value
        tracker.take(shown)
        assertTrue(tracker.outcomes.value.isEmpty())
        scope.cancel()
    }

    @Test
    fun `stopping builds clears them and reports no result`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var idle: Pair<Boolean, MiniAppBuildOutcome?>? = null
        val tracker = MiniAppBuildTracker(scope, onIdle = { idle = true to it })
        tracker.start("never ends") { CompletableDeferred<MiniApp>().await() }
        scope.runCurrent()
        tracker.cancelAll()
        scope.runCurrent()
        assertTrue(tracker.pending.value.isEmpty())
        assertTrue(tracker.outcomes.value.isEmpty())
        assertNull(idle!!.second)
        scope.cancel()
    }

    @Test
    fun `an edit build remembers the app it changes`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val tracker = MiniAppBuildTracker(scope)
        val build = tracker.start("bigger buttons", editing = app) { app.copy(version = 2) }
        assertEquals(app, build.editing)
        scope.runCurrent()
        assertEquals(app, tracker.outcomes.value.single().build.editing)
        scope.cancel()
    }
}
