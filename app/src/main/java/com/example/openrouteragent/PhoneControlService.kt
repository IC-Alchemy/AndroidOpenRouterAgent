package com.example.openrouteragent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MAX_NODES = 150
private const val MAX_DEPTH = 40

/**
 * Reads the active window and acts on it for the agent. The service never acts on its own: it only runs actions that
 * [PhoneController] has checked. While a run operates the phone it shows a small overlay with a Stop button.
 */
class PhoneControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var liveNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private var overlay: TextView? = null
    private var overlayRoot: LinearLayout? = null

    override fun onServiceConnected() {
        instance = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance?.get() === this) instance = null
        main.post { hideOverlay() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        hideOverlay()
        super.onDestroy()
    }

    fun snapshot(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        val nodes = ArrayList<ScreenNode>()
        val live = HashMap<Int, AccessibilityNodeInfo>()
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (nodes.size >= MAX_NODES) {
                truncated = true
                return
            }
            if (!node.isVisibleToUser) return
            val bounds = Rect().also(node::getBoundsInScreen)
            val text = node.text?.toString().orEmpty().trim()
            val description = node.contentDescription?.toString().orEmpty().trim()
            val hint = (if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null).orEmpty().trim()
            val interactive = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable ||
                node.isCheckable
            if (interactive || text.isNotEmpty() || description.isNotEmpty()) {
                val id = nodes.size + 1
                // A tappable row often carries its label on child text views.
                val derived = if (interactive && !node.isEditable && text.isEmpty() && description.isEmpty())
                    childText(node).take(120) else ""
                nodes += ScreenNode(
                    id = id, role = roleOf(node), text = text.ifEmpty { derived }, description = description,
                    hint = hint, clickable = node.isClickable, longClickable = node.isLongClickable,
                    editable = node.isEditable, scrollable = node.isScrollable, checkable = node.isCheckable,
                    checked = node.isChecked, enabled = node.isEnabled, focused = node.isFocused,
                    password = node.isPassword,
                    bounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                )
                live[id] = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        liveNodes = live
        return ScreenSnapshot(packageName, labelOf(packageName), nodes, truncated)
    }

    fun perform(action: PhoneAction, node: ScreenNode?): Boolean {
        val live = node?.let { liveNodes[it.id] }
        return when (action) {
            is PhoneAction.Tap -> live?.let {
                (it.isClickable && it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) || gesture(node, 60)
            } ?: false
            is PhoneAction.LongPress -> live?.let {
                (it.isLongClickable && it.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) || gesture(node, 700)
            } ?: false
            is PhoneAction.Type -> live?.let {
                it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                }
                val typed = it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!typed || !action.submit) typed
                else Build.VERSION.SDK_INT >= 30 &&
                    it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } ?: false
            is PhoneAction.Scroll -> scroll(action.direction, live)
            is PhoneAction.Global -> performGlobalAction(when (action.kind) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                else -> GLOBAL_ACTION_NOTIFICATIONS
            })
            else -> false
        }
    }

    private fun scroll(direction: String, live: AccessibilityNodeInfo?): Boolean {
        val forward = direction == "down" || direction == "right"
        if (live != null && live.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val path = Path()
        when (direction) {
            "down" -> { path.moveTo(w / 2, h * .72f); path.lineTo(w / 2, h * .28f) }
            "up" -> { path.moveTo(w / 2, h * .28f); path.lineTo(w / 2, h * .72f) }
            "right" -> { path.moveTo(w * .8f, h / 2); path.lineTo(w * .2f, h / 2) }
            else -> { path.moveTo(w * .2f, h / 2); path.lineTo(w * .8f, h / 2) }
        }
        return dispatch(GestureDescription.StrokeDescription(path, 0, 350))
    }

    private fun gesture(node: ScreenNode, duration: Long): Boolean {
        if (node.bounds.isEmpty) return false
        val path = Path().apply { moveTo(node.bounds.centerX.toFloat(), node.bounds.centerY.toFloat()) }
        return dispatch(GestureDescription.StrokeDescription(path, 0, duration))
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean {
        val done = CountDownLatch(1)
        var completed = false
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { completed = true; done.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
            }, main)
        if (!sent) return false
        // Gesture callbacks arrive on the main thread, so never wait for them there.
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        done.await(3, TimeUnit.SECONDS)
        return completed
    }

    fun installedApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(intent, 0).map {
            InstalledApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName)
        }.distinctBy { it.packageName }
    }

    fun launch(packageName: String): Boolean = runCatching {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun showAgentApp() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    val locked: Boolean get() = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked

    private fun labelOf(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault("")

    /** Shows or hides the floating status with its Stop button. Call from any thread. */
    fun status(text: String?) = main.post {
        if (text == null) hideOverlay() else showOverlay(text)
    }

    private fun showOverlay(text: String) {
        overlay?.let {
            it.text = text
            return
        }
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            resources.displayMetrics).toInt() }
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            this.text = text
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        val stop = TextView(this).apply {
            setTextColor(Color.parseColor("#CFC4FF"))
            textSize = 13f
            this.text = getString(R.string.phone_control_stop)
            setPadding(dp(10), dp(8), dp(14), dp(8))
            contentDescription = getString(R.string.phone_control_stop_description)
            setOnClickListener {
                hideOverlay()
                onStopRequested?.invoke()
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6201C2B"))
                cornerRadius = dp(20).toFloat()
            }
            addView(label)
            addView(stop)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(root, params)
            overlay = label
            overlayRoot = root
        }
    }

    private fun hideOverlay() {
        overlayRoot?.let { root ->
            runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(root) }
        }
        overlayRoot = null
        overlay = null
    }

    companion object {
        @Volatile private var instance: WeakReference<PhoneControlService>? = null

        /** Called when the user taps Stop on the overlay. */
        @Volatile var onStopRequested: (() -> Unit)? = null

        val connected: PhoneControlService? get() = instance?.get()

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, PhoneControlService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        }

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

private fun childText(node: AccessibilityNodeInfo, depth: Int = 0): String {
    if (depth > 4) return ""
    val parts = ArrayList<String>()
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        val own = child.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: child.contentDescription?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
        if (own != null) parts += own else childText(child, depth + 1).takeIf { it.isNotEmpty() }?.let(parts::add)
        if (parts.sumOf { it.length } > 120) break
    }
    return parts.joinToString(" · ")
}

private fun roleOf(node: AccessibilityNodeInfo): String {
    val name = node.className?.toString().orEmpty().substringAfterLast('.')
    return when {
        node.isEditable -> "field"
        name.contains("Switch") -> "switch"
        name.contains("CheckBox") -> "checkbox"
        name.contains("RadioButton") -> "radio"
        name.contains("Button") -> "button"
        name.contains("Image") && node.isClickable -> "button"
        node.isScrollable -> "list"
        node.isClickable -> "item"
        name.contains("Image") -> "image"
        else -> "text"
    }
}

/** Connects [PhoneController] to the running accessibility service. */
internal class AccessibilityPhoneDevice(private val context: Context) : PhoneDevice {
    private val service get() = PhoneControlService.connected
    override val available get() = service != null
    override val locked get() = service?.locked
        ?: (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
    override fun snapshot() = service?.snapshot()
    override fun perform(action: PhoneAction, node: ScreenNode?) = service?.perform(action, node) ?: false
    override fun installedApps() = service?.installedApps().orEmpty()
    override fun launch(packageName: String) = service?.launch(packageName) ?: false
    override fun showAgentApp() { service?.showAgentApp() }
    override fun status(text: String?) { service?.status(text) }
}
