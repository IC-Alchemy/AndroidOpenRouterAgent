package com.example.openrouteragent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File

private const val MINI_HOST_SUFFIX = ".miniapp.invalid"
private const val EXTRA_APP_ID = "mini_app_id"

fun miniAppStore(context: Context) = MiniAppStore(File(context.applicationContext.filesDir, "miniapps"))

/** Runs one mini app. Each app gets its own origin, so its saved data stays separate from other apps. */
class MiniAppActivity : Activity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled") // The page is the user's own mini app, isolated from the app and files.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_APP_ID) ?: intent.data?.lastPathSegment
        val store = miniAppStore(this)
        val app = id?.let { key -> store.list().firstOrNull { it.id == key } }
        val html = app?.let { store.html(it.id) }
        if (app == null || html == null) {
            Toast.makeText(this, "This mini app was deleted.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        title = app.name
        if (android.os.Build.VERSION.SDK_INT >= 28) setTaskDescription(android.app.ActivityManager.TaskDescription(app.name))
        val view = WebView(this)
        web = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
        }
        val origin = "https://${app.id}$MINI_HOST_SUFFIX/"
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                // Links open in the browser after a tap; the app page itself never navigates away.
                if (request.hasGesture() && url.scheme == "https" && url.host?.endsWith(MINI_HOST_SUFFIX) != true) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
                return true
            }

            override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                val allowed = app.allowNetwork && url.scheme == "https" && url.host?.endsWith(MINI_HOST_SUFFIX) == false
                return if (allowed) null else WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                    emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
        setContentView(view)
        view.loadDataWithBaseURL(origin, MiniAppChecks.withPolicy(html), "text/html", "UTF-8", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val view = web
        if (view != null && view.canGoBack()) view.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, app: MiniApp): Intent = Intent(context, MiniAppActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("icalchemy-miniapp://app/${app.id}"))
            .putExtra(EXTRA_APP_ID, app.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)

        fun open(context: Context, app: MiniApp) = context.startActivity(intent(context, app))

        /** Asks the launcher to pin a shortcut. Returns false when the launcher does not support it. */
        fun pin(context: Context, app: MiniApp): Boolean {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
            if (!manager.isRequestPinShortcutSupported) return false
            val shortcut = ShortcutInfo.Builder(context, "miniapp-${app.id}")
                .setShortLabel(app.name.take(24))
                .setLongLabel(app.name)
                .setIcon(Icon.createWithBitmap(letterIcon(app.name)))
                .setIntent(intent(context, app).setFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
                .build()
            return runCatching { manager.requestPinShortcut(shortcut, null) }.getOrDefault(false)
        }

        /** Removes the pinned shortcut of a deleted app, where the launcher allows it. */
        fun unpin(context: Context, id: String) {
            runCatching {
                context.getSystemService(ShortcutManager::class.java)
                    ?.disableShortcuts(listOf("miniapp-$id"), "This mini app was deleted.")
            }
        }

        /** Shares the app as one .html file. */
        fun share(context: Context, app: MiniApp) {
            val html = miniAppStore(context).html(app.id) ?: return
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val safe = app.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "mini-app" }
            val file = File(dir, "$safe.html").apply { writeText(html) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, app.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share ${app.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        private fun letterIcon(name: String): Bitmap {
            val size = 192
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val hue = (name.hashCode() and 0x7fffffff) % 360
            canvas.drawCircle(size / 2f, size / 2f, size / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(hue.toFloat(), .45f, .85f)) })
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = size * .5f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val letter = name.trim().firstOrNull()?.uppercase() ?: "A"
            canvas.drawText(letter, size / 2f, size / 2f - (text.descent() + text.ascent()) / 2, text)
            return bitmap
        }
    }
}

/** Receives a shared .html mini app. It is checked, saved without network access, and opened. */
class MiniAppImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } else intent.data
        val message = try {
            val html = uri?.let { source ->
                contentResolver.openInputStream(source)?.use { input ->
                    val bytes = input.readNBytesCompat(MINI_APP_MAX_CHARS * 2 + 1)
                    require(bytes.size <= MINI_APP_MAX_CHARS * 2) { "The file is too large to be a mini app." }
                    bytes.toString(Charsets.UTF_8)
                }
            } ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.contains("<html", ignoreCase = true) }
                ?: throw IllegalArgumentException("Android did not pass a file to import.")
            val problems = MiniAppChecks.problems(html)
            require(problems.isEmpty()) { "This file is not a mini app: ${problems.first()}" }
            val app = miniAppStore(this).save(MiniAppChecks.title(html)!!.take(40), "Shared with you", html, imported = true)
            MiniAppActivity.open(this, app)
            "Added ${app.name}. Shared apps cannot use the internet."
        } catch (e: Exception) {
            e.message ?: "The mini app could not be imported."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
