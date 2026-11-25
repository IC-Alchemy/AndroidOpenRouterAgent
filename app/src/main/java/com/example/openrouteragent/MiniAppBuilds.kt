package com.example.openrouteragent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the mini apps screen says while a build runs: the user can leave, and can also ask from the main chat. */
const val MINI_APP_BUILD_LEAVE_NOTE =
    "We're working on it now. Feel free to leave, and your mini app will be ready when you get back."
const val MINI_APP_BUILD_CHAT_TIP = "Tip: you can also ask for any mini app right from the main chat."

/** A mini app the AI is building or changing. [editing] is the app being changed, or null for a new app. */
data class PendingMiniAppBuild(
    val key: Long,
    val prompt: String,
    val editing: MiniApp? = null,
)

/** How a build ended. [finishedAt] is a [MiniAppBuildTracker.clock] reading, so a screen can tell old results apart. */
sealed class MiniAppBuildOutcome {
    abstract val build: PendingMiniAppBuild
    abstract val finishedAt: Long

    data class Done(override val build: PendingMiniAppBuild, val app: MiniApp, override val finishedAt: Long) :
        MiniAppBuildOutcome()

    data class Failed(override val build: PendingMiniAppBuild, val message: String, override val finishedAt: Long) :
        MiniAppBuildOutcome()
}

/**
 * Runs mini app builds in a scope that outlives the mini apps screen, so the user can close the sheet, go back to
 * chat or leave the app while the AI works. The process-wide view model owns one with its own scope; the screen
 * reads [pending] to show what is being built and takes [outcomes] to say how each build ended.
 *
 * [onBusy] runs when the first build starts and [onIdle] when the last one ends, both on the scope's dispatcher;
 * the view model uses them to keep the app in the foreground while a build runs.
 */
class MiniAppBuildTracker(
    private val scope: CoroutineScope,
    private val onBusy: (PendingMiniAppBuild) -> Unit = {},
    private val onIdle: (MiniAppBuildOutcome?) -> Unit = {},
    val clock: () -> Long = System::currentTimeMillis,
) {
    private val _pending = MutableStateFlow<List<PendingMiniAppBuild>>(emptyList())
    val pending: StateFlow<List<PendingMiniAppBuild>> = _pending.asStateFlow()

    private val _outcomes = MutableStateFlow<List<MiniAppBuildOutcome>>(emptyList())
    /** Results no screen has shown yet. */
    val outcomes: StateFlow<List<MiniAppBuildOutcome>> = _outcomes.asStateFlow()

    private val jobs = mutableMapOf<Long, Job>()
    private var nextKey = 0L

    val busy: Boolean get() = _pending.value.isNotEmpty()

    /** Starts [work] and returns at once. The build shows in [pending] until it ends. */
    fun start(prompt: String, editing: MiniApp? = null, work: suspend () -> MiniApp): PendingMiniAppBuild {
        val build = PendingMiniAppBuild(++nextKey, prompt, editing)
        val wasIdle = !busy
        _pending.update { it + build }
        if (wasIdle) onBusy(build)
        // Started lazily, so a build that ends at once still finds its own entry to remove.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var outcome: MiniAppBuildOutcome? = null
            try {
                val app = work()
                outcome = MiniAppBuildOutcome.Done(build, app, clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                outcome = MiniAppBuildOutcome.Failed(build, e.message ?: "Could not build the mini app.", clock())
            } finally {
                jobs.remove(build.key)
                outcome?.let { result -> _outcomes.update { it + result } }
                _pending.update { list -> list.filterNot { it.key == build.key } }
                if (!busy) onIdle(outcome)
            }
        }
        jobs[build.key] = job
        job.start()
        return build
    }

    /** Removes and returns results the screen is about to show. */
    fun take(outcomes: Collection<MiniAppBuildOutcome>) {
        _outcomes.update { list -> list.filterNot { it in outcomes } }
    }

    /** Stops every build, for the Stop button on the background notice. Stopped builds report no result. */
    fun cancelAll() {
        jobs.values.toList().forEach { it.cancel() }
    }
}
