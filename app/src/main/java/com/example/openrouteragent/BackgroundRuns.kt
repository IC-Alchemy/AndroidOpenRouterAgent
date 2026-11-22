package com.example.openrouteragent

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

private const val TAG = "AgentRun"

/**
 * Keeps the agent alive when the user leaves the app. The view model lives in this process-wide store, so a run
 * continues when the activity is closed, and [AgentRunService] keeps the process in the foreground while a run is
 * active so Android does not freeze or reclaim it.
 */
class AgentApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        RunNotifications.createChannels(this)
        // A new process has no run, so an ongoing notice left behind by a killed process is stale.
        RunNotifications.cancelOngoing(this)
        registerActivityLifecycleCallbacks(AppVisibility.tracker)
    }
}

/** Whether a MainActivity is on screen. Notifications for a finished run are only posted when it is not. */
object AppVisibility {
    @Volatile private var started = 0
    val visible get() = started > 0

    /** Counts started MainActivity instances; lifecycle callbacks always arrive on the main thread. */
    internal val tracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity !is MainActivity) return
            started++
            RunNotifications.clearFinished(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity is MainActivity) started = (started - 1).coerceAtLeast(0)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

/** Commands from notification buttons. The process-wide view model registers the handler. */
object RunCommands {
    const val STOP = "stop"
    const val ALLOW = "allow"
    const val DENY = "deny"

    @Volatile var handler: ((command: String, decision: String?) -> Unit)? = null
}

class RunActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        val handler = RunCommands.handler
        // No live view model means no run, so the notice the button came from is stale.
        if (handler == null) RunNotifications.cancelOngoing(context)
        else handler(command, intent.getStringExtra(EXTRA_DECISION))
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_DECISION = "decision"
    }
}

/** What the ongoing run notification shows. */
data class RunNotice(
    val title: String,
    val text: String,
    val approval: ToolApproval? = null,
)

class AgentRunService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android crashes the app when a startForegroundService call is not answered with startForeground, even if
        // the run already ended. So always go foreground first, then honor a stop that arrived in the meantime.
        val promoted = runCatching {
            val notification = RunNotifications.ongoing(this, RunNotifications.current)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(RunNotifications.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(RunNotifications.ONGOING_ID, notification)
            }
        }.onFailure {
            // Android can refuse a foreground start, for example when the app is no longer allowed to start one.
            // The run still continues while the process stays alive.
            Log.w(TAG, "Could not start the foreground run service: ${it.message}")
        }.isSuccess
        val wanted = onStarted()
        // stopSelf(startId) keeps the service when a newer start is still on its way, which must also go foreground.
        if (!wanted || !promoted) stopSelf(startId)
        // A run cannot resume after the process dies, so do not ask Android to restart the service.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits data-sync services and requires a prompt stop. Runs time out long before this.
        stopSelf()
    }

    companion object {
        /** Whether a run wants the service. Guarded by the companion lock. */
        private var wanted = false
        /** startForegroundService calls not yet delivered to [onStartCommand]. Guarded by the companion lock. */
        private var pendingStarts = 0

        fun start(context: Context) {
            synchronized(this) {
                wanted = true
                runCatching { context.startForegroundService(intent(context)) }
                    .onSuccess { pendingStarts++ }
                    .onFailure { Log.w(TAG, "Could not start the run service: ${it.message}") }
            }
        }

        fun stop(context: Context) {
            synchronized(this) {
                wanted = false
                // Stopping before a pending start goes foreground crashes the app; onStartCommand stops it instead.
                if (pendingStarts == 0) runCatching { context.stopService(intent(context)) }
            }
        }

        private fun onStarted(): Boolean = synchronized(this) {
            pendingStarts = (pendingStarts - 1).coerceAtLeast(0)
            wanted
        }

        private fun intent(context: Context) = Intent(context, AgentRunService::class.java)
    }
}

/**
 * The ongoing run notification and the "reply ready" notification. Progress updates are throttled, but the newest
 * notice is always shown: an update that arrives too soon is posted when the interval ends. All state is guarded by
 * the object lock, because updates can come from the runtime's callback threads.
 */
object RunNotifications {
    const val ONGOING_ID = 41
    private const val DONE_ID = 42
    private const val RUN_CHANNEL = "agent_runs"
    private const val DONE_CHANNEL = "agent_replies"
    private const val APPROVAL_CHANNEL = "agent_approvals"
    private const val UPDATE_INTERVAL_MS = 1_000L
    private const val STOP_REQUEST = 3
    private val STARTING = RunNotice("Agent is working", "Starting agent")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val flush = Runnable { synchronized(this) { postLatest() } }

    private var appContext: Context? = null
    private var active = false
    private var latest: RunNotice? = null
    private var shown: RunNotice? = null
    private var lastPost = 0L
    private var flushScheduled = false
    private var startedAt = 0L
    private var openAppIntent: PendingIntent? = null
    private var stopIntent: PendingIntent? = null

    /** The newest notice, which the run service shows when it goes foreground. */
    val current: RunNotice get() = synchronized(this) { latest ?: STARTING }

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(listOf(
            NotificationChannel(RUN_CHANNEL, "Agent runs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress while the agent works in the background."
                setShowBadge(false)
            },
            NotificationChannel(APPROVAL_CHANNEL, "Agent approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks you to allow a tool call while the agent works in the background."
            },
            NotificationChannel(DONE_CHANNEL, "Agent replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a reply is ready."
            },
        ))
    }

    fun canPost(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Starts tracking a new run. The first [update] after this posts at once. */
    fun begin(context: Context) {
        synchronized(this) {
            cancelFlush()
            appContext = context.applicationContext
            active = true
            latest = null
            shown = null
            lastPost = 0L
            startedAt = System.currentTimeMillis()
        }
    }

    /**
     * Shows [notice] in the ongoing notification. Approvals, and the change back from one, post at once so their
     * buttons appear and disappear promptly; progress posts at most once per interval. Ignored when no run is active.
     */
    fun update(notice: RunNotice) {
        synchronized(this) {
            if (!active || notice == latest) return
            latest = notice
            val wait = lastPost + UPDATE_INTERVAL_MS - SystemClock.elapsedRealtime()
            if (wait <= 0 || notice.approval != shown?.approval) {
                cancelFlush()
                postLatest()
            } else if (!flushScheduled) {
                flushScheduled = mainHandler.postDelayed(flush, wait)
            }
        }
    }

    /** Stops tracking the run and removes its notice. Late updates from the finished run are ignored. */
    fun end(context: Context) {
        synchronized(this) {
            active = false
            cancelFlush()
            latest = null
            shown = null
        }
        // The service removes its own notice when it stops; this clears one posted while it was not foreground.
        cancelOngoing(context)
    }

    fun cancelOngoing(context: Context) {
        runCatching { manager(context)?.cancel(ONGOING_ID) }
    }

    fun ongoing(context: Context, notice: RunNotice): Notification = synchronized(this) {
        val approval = notice.approval
        val builder = Notification.Builder(context, if (approval != null) APPROVAL_CHANNEL else RUN_CHANNEL)
            .setSmallIcon(R.drawable.ic_sandbox)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(Notification.BigTextStyle().bigText(notice.text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(approval == null)
            .setCategory(if (approval != null) Notification.CATEGORY_REMINDER else Notification.CATEGORY_PROGRESS)
        if (startedAt > 0) builder.setWhen(startedAt).setShowWhen(true).setUsesChronometer(true)
        // Android 12+ may hide a new foreground notice for 10 seconds; show the Stop button right away.
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (approval == null) builder.setProgress(0, 0, true)
        if (approval != null && !approval.asksUser) {
            builder.addAction(action(context, "Allow once", RunCommands.ALLOW, approval.decision, 1))
            builder.addAction(action(context, "Deny", RunCommands.DENY, approval.decision, 2))
        }
        builder.addAction(Notification.Action.Builder(null, "Stop", stop(context)).build())
        builder.build()
    }

    /** Posts the result of a run that finished while the app was not on screen. */
    fun finished(context: Context, title: String, text: String) {
        if (!canPost(context)) return
        val notification = Notification.Builder(context, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_sandbox)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(synchronized(this) { openApp(context) })
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        runCatching { manager(context)?.notify(DONE_ID, notification) }
    }

    fun clearFinished(context: Context) {
        runCatching { manager(context)?.cancel(DONE_ID) }
    }

    /** Posts the newest notice if it is not already shown. Call with the lock held. */
    private fun postLatest() {
        flushScheduled = false
        val notice = latest ?: return
        val context = appContext ?: return
        if (!active || notice == shown) return
        shown = notice
        lastPost = SystemClock.elapsedRealtime()
        if (canPost(context)) runCatching { manager(context)?.notify(ONGOING_ID, ongoing(context, notice)) }
    }

    private fun cancelFlush() {
        mainHandler.removeCallbacks(flush)
        flushScheduled = false
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    // Fixed pending intents are made once per process instead of on every progress update. Call with the lock held.
    private fun openApp(context: Context) = openAppIntent ?: PendingIntent.getActivity(context.applicationContext, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).also { openAppIntent = it }

    private fun stop(context: Context) = stopIntent ?: broadcast(context, RunCommands.STOP, null, STOP_REQUEST)
        .also { stopIntent = it }

    private fun action(context: Context, label: String, command: String, decision: String?, code: Int) =
        Notification.Action.Builder(null, label, broadcast(context, command, decision, code)).build()

    private fun broadcast(context: Context, command: String, decision: String?, code: Int) =
        PendingIntent.getBroadcast(context.applicationContext, code,
            Intent(context, RunActionReceiver::class.java)
                .putExtra(RunActionReceiver.EXTRA_COMMAND, command)
                .putExtra(RunActionReceiver.EXTRA_DECISION, decision),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

private val MARKDOWN_LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
private val MARKDOWN_LINE_MARK = Regex("""(?m)^\s*(?:#{1,6}|>+|[-*+])\s+""")
private val MARKDOWN_INLINE_MARK = Regex("""\*\*|__|~~|[*`]""")
private val WHITESPACE = Regex("""\s+""")

/** Short plain-text reply preview for the "reply ready" notification. Keeps link text and snake_case names. */
internal fun replyPreview(text: String, max: Int = 240): String {
    val flat = text
        .replace(MARKDOWN_LINK, "\$1")
        .replace(MARKDOWN_LINE_MARK, "")
        .replace(MARKDOWN_INLINE_MARK, "")
        .replace(WHITESPACE, " ")
        .trim()
    if (flat.isEmpty()) return "The agent finished."
    if (flat.length <= max) return flat
    // Leave room for the ellipsis and never split an emoji or other surrogate pair.
    var end = (max - 1).coerceAtLeast(0)
    if (end > 0 && flat[end - 1].isHighSurrogate()) end--
    return flat.take(end).trimEnd() + "…"
}
