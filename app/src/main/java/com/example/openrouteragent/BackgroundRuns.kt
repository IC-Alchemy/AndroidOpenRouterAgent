package com.example.openrouteragent

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
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

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
    }
}

/** Whether a MainActivity is on screen. Notifications for a finished run are only posted when it is not. */
object AppVisibility {
    @Volatile var visibleActivities = 0
    val visible get() = visibleActivities > 0
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
        RunCommands.handler?.invoke(command, intent.getStringExtra(EXTRA_DECISION))
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
        val notification = RunNotifications.ongoing(this, RunNotifications.current)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(RunNotifications.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(RunNotifications.ONGOING_ID, notification)
            }
        } catch (e: Exception) {
            // Android can refuse a foreground start, for example when the app is no longer allowed to start one.
            // The run still continues while the process stays alive.
            android.util.Log.w("AgentRun", "Could not start the foreground run service: ${e.message}")
            stopSelf()
        }
        // A run cannot resume after the process dies, so do not ask Android to restart the service.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits data-sync services. Stop cleanly; runs time out long before this.
        stopSelf()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, AgentRunService::class.java))
            }.onFailure { android.util.Log.w("AgentRun", "Could not start the run service: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentRunService::class.java))
        }
    }
}

object RunNotifications {
    const val ONGOING_ID = 41
    private const val DONE_ID = 42
    private const val RUN_CHANNEL = "agent_runs"
    private const val DONE_CHANNEL = "agent_replies"
    private const val APPROVAL_CHANNEL = "agent_approvals"

    @Volatile var current = RunNotice("Agent is working", "Starting agent")
        private set
    private var lastPost = 0L

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(listOf(
            NotificationChannel(RUN_CHANNEL, "Agent runs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress while the agent works in the background."
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

    /** Updates the ongoing notification. Progress is throttled; approvals and the first notice post at once. */
    fun update(context: Context, notice: RunNotice, force: Boolean = false) {
        val changed = notice != current
        current = notice
        val now = SystemClock.elapsedRealtime()
        if (!changed || (!force && notice.approval == null && now - lastPost < 1_000)) return
        lastPost = now
        if (!canPost(context)) return
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(ONGOING_ID, ongoing(context, notice))
        }
    }

    fun ongoing(context: Context, notice: RunNotice): Notification {
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
        if (approval == null) builder.setProgress(0, 0, true)
        if (approval != null && !approval.asksUser) {
            builder.addAction(action(context, "Allow once", RunCommands.ALLOW, approval.decision, 1))
            builder.addAction(action(context, "Deny", RunCommands.DENY, approval.decision, 2))
        }
        builder.addAction(action(context, "Stop", RunCommands.STOP, null, 3))
        return builder.build()
    }

    /** Posts the result of a run that finished while the app was not on screen. */
    fun finished(context: Context, title: String, text: String) {
        if (!canPost(context)) return
        val notification = Notification.Builder(context, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_sandbox)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(DONE_ID, notification) }
    }

    fun clearFinished(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(DONE_ID) }
    }

    fun reset() {
        current = RunNotice("Agent is working", "Starting agent")
        lastPost = 0L
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(context: Context, label: String, command: String, decision: String?, code: Int) =
        Notification.Action.Builder(null, label, PendingIntent.getBroadcast(context, code,
            Intent(context, RunActionReceiver::class.java)
                .putExtra(RunActionReceiver.EXTRA_COMMAND, command)
                .putExtra(RunActionReceiver.EXTRA_DECISION, decision),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
}

/** Short reply preview for the "reply ready" notification. */
internal fun replyPreview(text: String, max: Int = 240): String {
    val flat = text.replace(Regex("[#*_`>]+"), "").replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat.ifBlank { "The agent finished." } else flat.take(max - 1).trimEnd() + "…"
}
