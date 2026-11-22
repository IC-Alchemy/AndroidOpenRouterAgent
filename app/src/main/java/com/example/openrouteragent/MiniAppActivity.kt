package com.example.openrouteragent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val EXTRA_APP_ID = "mini_app_id"

/**
 * Maps `https://<uuid>.miniapp.local/` directly to `filesDir/mini_apps/<uuid>/` so Chromium isolates
 * each mini app's localStorage, cookies and IndexedDB onto its own virtual HTTPS origin.
 */
fun createAssetLoader(context: Context, miniAppId: String): WebViewAssetLoader {
    require(MiniAppSanitizer.isValidId(miniAppId)) { "Invalid mini app id." }
    val appContext = context.applicationContext
    val appDir = File(appContext.filesDir, "$MINI_APP_DIR_NAME/$miniAppId")
    val handler: WebViewAssetLoader.PathHandler = if (File.separatorChar == '/') {
        WebViewAssetLoader.InternalStoragePathHandler(appContext, appDir)
    } else {
        // AndroidX InternalStoragePathHandler hardcodes '/' in getCanonicalDirPath, which breaks on Windows Robolectric.
        JvmSafeDirectoryPathHandler(appDir)
    }
    return WebViewAssetLoader.Builder()
        .setDomain(miniAppHost(miniAppId))
        .addPathHandler("/", handler)
        .build()
}

private class JvmSafeDirectoryPathHandler(private val directory: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val clean = path.trimStart('/')
        if (clean.isEmpty()) return null
        val base = directory.canonicalFile
        val target = File(base, clean).canonicalFile
        val basePrefix = base.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        if (!target.path.startsWith(basePrefix) || !target.isFile) return null
        val mime = java.net.URLConnection.guessContentTypeFromName(target.name) ?: "application/octet-stream"
        return WebResourceResponse(mime, null, target.inputStream())
    }
}

private val miniAppClearGenerations = ConcurrentHashMap<String, AtomicLong>()

internal fun miniAppClearGeneration(miniAppId: String): Long =
    miniAppClearGenerations[miniAppId]?.get() ?: 0L

/** Wipes WebView origin storage (localStorage / IndexedDB) and origin cookies for a single mini app. */
fun clearMiniAppOriginData(miniAppId: String) {
    if (!MiniAppSanitizer.isValidId(miniAppId)) return
    miniAppClearGenerations.computeIfAbsent(miniAppId) { AtomicLong(0L) }.incrementAndGet()
    val origin = miniAppOrigin(miniAppId)
    runCatching { WebStorage.getInstance().deleteOrigin(origin) }
    runCatching {
        val cookies = CookieManager.getInstance()
        val existing = cookies.getCookie(origin)
        if (!existing.isNullOrBlank()) {
            existing.split(';').forEach { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    cookies.setCookie(origin, "$name=; Max-Age=0; path=/; Secure")
                }
            }
            cookies.flush()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled") // Each mini app runs in its own isolated origin with no app bridge or file access.
internal fun configureMiniAppWebViewSettings(settings: WebSettings) {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true       // Allows mini app to save user state
        allowFileAccess = false        // Blocks access to device files
        allowContentAccess = false     // Blocks access to content:// providers
        javaScriptCanOpenWindowsAutomatically = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
    }
}

internal class MiniAppWebViewClient(
    private val miniAppId: String,
    private val allowNetwork: Boolean,
    private val assetLoader: WebViewAssetLoader,
    private val entryPoint: String = MINI_APP_DEFAULT_ENTRY_POINT,
    private val onOpenExternalUrl: (Uri) -> Unit,
) : WebViewClient() {
    private val virtualHost = miniAppHost(miniAppId)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        interceptUrl(request.url)

    internal fun interceptUrl(url: Uri): WebResourceResponse? {
        val scheme = url.scheme?.lowercase()
        // Allow safe in-memory data: and blob: subresources (e.g., audio chimes, canvas blobs, inline SVGs).
        if (scheme == "data" || scheme == "blob") return null

        val host = url.host?.lowercase()?.trimEnd('.')
        if (scheme == "https" && host == virtualHost) {
            val lookupUrl = if (url.path.isNullOrEmpty() || url.path == "/") {
                url.buildUpon().path("/$entryPoint").build()
            } else {
                url
            }
            if (isBlockedInternalPath(lookupUrl.path)) {
                return emptyResponse(404, "Not Found")
            }
            val response = assetLoader.shouldInterceptRequest(lookupUrl)
                ?: return emptyResponse(404, "Not Found")
            val mime = response.mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
            if (response.encoding.isNullOrBlank() && isUtf8Mime(mime)) {
                response.encoding = "UTF-8"
            }
            if (isCspDocumentMime(mime)) {
                val headers = LinkedHashMap<String, String>(response.responseHeaders.orEmpty())
                headers["Content-Security-Policy"] = MiniAppChecks.cspHeader(allowNetwork)
                response.responseHeaders = headers
            }
            if (response.statusCode == 0) {
                response.setStatusCodeAndReasonPhrase(200, "OK")
            }
            return response
        }
        // Never leak requests targeting another mini app's virtual domain to the network.
        if (host != null && host.endsWith(MINI_APP_DOMAIN_SUFFIX)) {
            return emptyResponse(403, "Forbidden")
        }
        return if (allowNetwork && scheme == "https") {
            null
        } else {
            emptyResponse(403, "Forbidden")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleNavigation(request.url, hasGesture = request.hasGesture())

    internal fun handleNavigation(url: Uri, hasGesture: Boolean): Boolean {
        val scheme = url.scheme?.lowercase()
        val host = url.host?.lowercase()?.trimEnd('.') ?: return true
        return if (scheme == "https" && host == virtualHost) {
            false
        } else {
            if (hasGesture && (scheme == "https" || scheme == "http") && !host.endsWith(MINI_APP_DOMAIN_SUFFIX)) {
                onOpenExternalUrl(url)
            }
            true
        }
    }

    private fun isBlockedInternalPath(rawPath: String?): Boolean {
        val raw = rawPath?.replace('\\', '/') ?: return false
        val stack = ArrayDeque<String>()
        for (segment in raw.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> continue
                segment == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        val normalized = stack.joinToString("/")
        val leaf = stack.lastOrNull().orEmpty()
        return normalized.equals(MINI_APP_MANIFEST_FILE_NAME, ignoreCase = true) ||
            leaf.endsWith(".tmp", ignoreCase = true)
    }

    private fun isCspDocumentMime(mime: String): Boolean =
        mime == "text/html" ||
            mime == "application/xhtml+xml" ||
            mime == "image/svg+xml"

    private fun isUtf8Mime(mime: String): Boolean =
        mime.startsWith("text/") ||
            mime == "application/javascript" ||
            mime == "application/x-javascript" ||
            mime == "application/json" ||
            mime == "application/xhtml+xml" ||
            mime == "image/svg+xml"

    private fun emptyResponse(code: Int, reason: String) = WebResourceResponse(
        "text/plain",
        "UTF-8",
        code,
        reason,
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )
}

/** Runs one mini app in its own Recents document with native app chrome and isolated WebView storage. */
class MiniAppActivity : ComponentActivity() {
    private var activeAppId: String? = null
    private var appState by mutableStateOf<MiniApp?>(null)
    private var iconFileState by mutableStateOf<File?>(null)
    private var appThemeState by mutableStateOf("oled")
    private var externalReloadTrigger by mutableIntStateOf(0)
    private var clearCacheOnReload by mutableStateOf(false)
    private var observedClearGen: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appThemeState = AgentStorage(this).loadTheme()
        if (!loadAppFromIntent(intent, initialLoad = true)) {
            Toast.makeText(this, "This mini app was deleted.", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        setContent {
            val currentApp = appState ?: return@setContent
            val currentIconFile = iconFileState
            val lightHeader = MiniAppIconRenderer.isLightColor(currentApp.themeColor)
            val darkTheme = isDarkThemeForAppearance(appThemeState, isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightHeader
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            OpenRouterAgentTheme(darkTheme = darkTheme) {
                MiniAppPlayerScreen(
                    app = currentApp,
                    iconFile = currentIconFile,
                    onExit = { finish() },
                    externalReloadTrigger = externalReloadTrigger,
                    clearCacheOnExternalReload = clearCacheOnReload,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appThemeState = AgentStorage(this).loadTheme()
        if (!loadAppFromIntent(intent, initialLoad = false)) {
            Toast.makeText(this, "This mini app was deleted.", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }
    }

    override fun onResume() {
        super.onResume()
        appThemeState = AgentStorage(this).loadTheme()
        if (activeAppId != null && !loadAppFromIntent(intent, initialLoad = false)) {
            Toast.makeText(this, "This mini app was deleted.", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }
    }

    private fun loadAppFromIntent(sourceIntent: Intent, initialLoad: Boolean): Boolean {
        val id = sourceIntent.getStringExtra(EXTRA_APP_ID) ?: sourceIntent.data?.lastPathSegment ?: activeAppId
        val repo = miniAppRepository(this)
        val latest = id?.let { key -> repo.find(key)?.takeIf { it.id == key } }
        if (latest == null || !repo.hasEntryFile(latest.id)) return false

        val previous = appState
        val latestIconFile = repo.iconFilePath(latest.id, latest.iconUri)
        val clearGen = miniAppClearGeneration(latest.id)

        activeAppId = latest.id
        appState = latest
        iconFileState = latestIconFile

        val metadataChanged = previous == null ||
            previous.name != latest.name ||
            previous.themeColor != latest.themeColor ||
            previous.iconUri != latest.iconUri ||
            previous.updatedAt != latest.updatedAt
        if (metadataChanged) {
            title = latest.name
            updateTaskDescription(latest, latestIconFile)
        }

        if (initialLoad) {
            observedClearGen = clearGen
        } else {
            val dataCleared = clearGen > observedClearGen
            val versionChanged = previous != null && (previous.id != latest.id || previous.version != latest.version)
            observedClearGen = clearGen
            if (dataCleared || versionChanged) {
                clearCacheOnReload = dataCleared
                externalReloadTrigger++
            }
        }
        return true
    }

    private fun updateTaskDescription(app: MiniApp, iconFile: File?) {
        if (Build.VERSION.SDK_INT >= 28) {
            val themeInt = MiniAppIconRenderer.parseColorInt(app.themeColor)
            lifecycleScope.launch {
                val taskBitmap = withContext(Dispatchers.IO) {
                    runCatching { MiniAppIconRenderer.loadOrRenderAppIconBitmap(app, iconFile) }.getOrNull()
                }
                if (!isFinishing && !isDestroyed && appState?.id == app.id) {
                    @Suppress("DEPRECATION")
                    setTaskDescription(ActivityManager.TaskDescription(app.name, taskBitmap, themeInt))
                }
            }
        }
    }

    companion object {
        fun intent(context: Context, app: MiniApp): Intent = Intent(context, MiniAppActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("icalchemy-miniapp://app/${app.id}"))
            .putExtra(EXTRA_APP_ID, app.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)

        fun open(context: Context, app: MiniApp) = context.startActivity(intent(context, app))

        internal fun buildShortcutInfo(context: Context, app: MiniApp, bitmap: Bitmap): ShortcutInfo =
            ShortcutInfo.Builder(context, "miniapp-${app.id}")
                .setShortLabel(app.name.take(24))
                .setLongLabel(app.name)
                .setIcon(Icon.createWithBitmap(bitmap))
                .setIntent(intent(context, app).setFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
                .build()

        /** Asks the launcher to pin a shortcut with the mini app's rendered icon (loads bitmap off main thread). */
        suspend fun pinAsync(context: Context, app: MiniApp): Boolean {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
            if (!manager.isRequestPinShortcutSupported) return false
            val bitmap = withContext(Dispatchers.IO) {
                val iconFile = miniAppRepository(context).iconFile(app.id)
                MiniAppIconRenderer.loadOrRenderAppIconBitmap(app, iconFile)
            }
            val shortcut = buildShortcutInfo(context, app, bitmap)
            return runCatching { manager.requestPinShortcut(shortcut, null) }.getOrDefault(false)
        }

        /** Synchronous shortcut pinning helper. */
        fun pin(context: Context, app: MiniApp): Boolean {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
            if (!manager.isRequestPinShortcutSupported) return false
            val iconFile = miniAppRepository(context).iconFile(app.id)
            val bitmap = MiniAppIconRenderer.loadOrRenderAppIconBitmap(app, iconFile)
            return runCatching { manager.requestPinShortcut(buildShortcutInfo(context, app, bitmap), null) }
                .getOrDefault(false)
        }

        /** Refreshes a pinned home-screen shortcut after rename, icon change, or AI edit. */
        fun updatePinnedShortcut(context: Context, app: MiniApp) {
            runCatching {
                val manager = context.getSystemService(ShortcutManager::class.java) ?: return
                val shortcutId = "miniapp-${app.id}"
                if (manager.pinnedShortcuts.none { it.id == shortcutId }) return
                val iconFile = miniAppRepository(context).iconFile(app.id)
                val bitmap = MiniAppIconRenderer.loadOrRenderAppIconBitmap(app, iconFile)
                manager.updateShortcuts(listOf(buildShortcutInfo(context, app, bitmap)))
            }
        }

        /** Disables the pinned shortcut of a deleted mini app, where the launcher allows it. */
        fun unpin(context: Context, id: String) {
            runCatching {
                context.getSystemService(ShortcutManager::class.java)
                    ?.disableShortcuts(listOf("miniapp-$id"), "This mini app was deleted.")
            }
        }

        /** Builds the `.miniapp` archive on IO threads (cleaning stale shared archives), then opens ShareSheet via FileProvider. */
        fun prepareShareArchive(context: Context, app: MiniApp): File {
            val repo = miniAppRepository(context)
            val bytes = repo.exportBundleBytes(app.id)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val archive = File(dir, miniAppArchiveFileName(app.name))
            dir.listFiles()?.forEach { existing ->
                val isStaleArchive = existing.name.endsWith(".miniapp", ignoreCase = true) ||
                    (existing.name.contains(".miniapp.", ignoreCase = true) && existing.name.endsWith(".tmp", ignoreCase = true))
                if (existing.isFile && isStaleArchive && existing != archive) {
                    existing.delete()
                }
            }
            atomicWriteBytes(archive, bytes)
            return archive
        }

        internal fun buildShareChooserIntent(context: Context, app: MiniApp, archiveName: String, uri: Uri): Intent {
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "${app.name}.miniapp")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(archiveName, uri) }
            return Intent.createChooser(send, "Share ${app.name}")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }

        fun launchShareSheet(context: Context, app: MiniApp, archive: File) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", archive)
            context.startActivity(buildShareChooserIntent(context, app, archive.name, uri))
        }

        /** Zips the mini app folder into `<MiniAppName>.miniapp` and shares it via FileProvider ShareSheet. */
        fun share(context: Context, app: MiniApp) = launchShareSheet(context, app, prepareShareArchive(context, app))
    }
}

private sealed interface JsDialogState {
    data class Alert(val message: String, val result: JsResult) : JsDialogState
    data class Confirm(val message: String, val result: JsResult) : JsDialogState
    data class Prompt(val message: String, val defaultValue: String, val result: JsPromptResult) : JsDialogState

    fun cancel() = when (this) {
        is Alert -> result.cancel()
        is Confirm -> result.cancel()
        is Prompt -> result.cancel()
    }
}

/**
 * Isolated Jetpack Compose Mini App player with dedicated top chrome (icon + title + Restart + Exit),
 * WebViewAssetLoader virtual HTTPS domain isolation, keyboard resize handling, and Back button integration.
 */
@Composable
fun MiniAppPlayerScreen(
    app: MiniApp,
    iconFile: File?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    externalReloadTrigger: Int = 0,
    clearCacheOnExternalReload: Boolean = false,
    onBackNavigation: (() -> Boolean)? = null,
    webViewContent: (@Composable (Modifier, String, Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val entryUrl = remember(app.id, app.entryPoint) { miniAppEntryUrl(app) }
    val headerBg = remember(app.themeColor) { Color(MiniAppIconRenderer.parseColorInt(app.themeColor)) }
    val headerFg = remember(app.themeColor) {
        if (MiniAppIconRenderer.isLightColor(app.themeColor)) Color(0xFF111827) else Color.White
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var jsDialog by remember { mutableStateOf<JsDialogState?>(null) }

    fun replaceJsDialog(next: JsDialogState?) {
        jsDialog?.cancel()
        jsDialog = next
    }

    LaunchedEffect(externalReloadTrigger) {
        if (externalReloadTrigger > 0) {
            replaceJsDialog(null)
            reloadNonce++
            val current = webViewRef
            if (current != null) {
                if (clearCacheOnExternalReload) {
                    current.clearCache(true)
                    current.clearHistory()
                }
                current.loadUrl(entryUrl)
            }
        }
    }

    BackHandler(enabled = true) {
        val handledCustom = onBackNavigation?.invoke() == true
        if (handledCustom) return@BackHandler
        val current = webViewRef
        if (current != null && current.canGoBack()) {
            current.goBack()
        } else {
            onExit()
        }
    }

    DisposableEffect(app.id) {
        onDispose {
            jsDialog?.cancel()
            jsDialog = null
            webViewRef?.let { view ->
                view.stopLoading()
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            webViewRef = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Surface(
            color = headerBg,
            contentColor = headerFg,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniAppIconBadge(
                    app = app,
                    iconFile = iconFile,
                    size = 32.dp,
                    cornerRadius = 8.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = headerFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    replaceJsDialog(null)
                    reloadNonce++
                    val current = webViewRef
                    if (current != null) {
                        if (current.url == entryUrl) current.reload() else current.loadUrl(entryUrl)
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Restart ${app.name}",
                        tint = headerFg,
                    )
                }
                IconButton(onClick = onExit) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close ${app.name}",
                        tint = headerFg,
                    )
                }
            }
        }

        if (webViewContent != null) {
            webViewContent(Modifier.weight(1f).fillMaxWidth(), entryUrl, reloadNonce)
        } else {
            key(app.id) {
                val assetLoader = remember(context, app.id) { createAssetLoader(context, app.id) }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            configureMiniAppWebViewSettings(settings)
                            webViewClient = MiniAppWebViewClient(
                                miniAppId = app.id,
                                allowNetwork = app.allowNetwork,
                                assetLoader = assetLoader,
                                entryPoint = app.entryPoint,
                                onOpenExternalUrl = { url ->
                                    try {
                                        viewContext.startActivity(
                                            Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE),
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                    }
                                },
                            )
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                                override fun onJsAlert(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    result: JsResult,
                                ): Boolean {
                                    replaceJsDialog(JsDialogState.Alert(message.orEmpty(), result))
                                    return true
                                }

                                override fun onJsConfirm(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    result: JsResult,
                                ): Boolean {
                                    replaceJsDialog(JsDialogState.Confirm(message.orEmpty(), result))
                                    return true
                                }

                                override fun onJsPrompt(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    defaultValue: String?,
                                    result: JsPromptResult,
                                ): Boolean {
                                    replaceJsDialog(JsDialogState.Prompt(message.orEmpty(), defaultValue.orEmpty(), result))
                                    return true
                                }
                            }
                            webViewRef = this
                            loadUrl(entryUrl)
                        }
                    },
                )
            }
        }
    }

    when (val dialog = jsDialog) {
        is JsDialogState.Alert -> AlertDialog(
            onDismissRequest = {
                dialog.result.confirm()
                jsDialog = null
            },
            title = { Text(app.name) },
            text = { Text(dialog.message) },
            confirmButton = {
                TextButton(onClick = {
                    dialog.result.confirm()
                    jsDialog = null
                }) { Text("OK") }
            },
        )
        is JsDialogState.Confirm -> AlertDialog(
            onDismissRequest = {
                dialog.result.cancel()
                jsDialog = null
            },
            title = { Text(app.name) },
            text = { Text(dialog.message) },
            confirmButton = {
                TextButton(onClick = {
                    dialog.result.confirm()
                    jsDialog = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialog.result.cancel()
                    jsDialog = null
                }) { Text("Cancel") }
            },
        )
        is JsDialogState.Prompt -> {
            var value by remember(dialog) { mutableStateOf(dialog.defaultValue) }
            AlertDialog(
                onDismissRequest = {
                    dialog.result.cancel()
                    jsDialog = null
                },
                title = { Text(app.name) },
                text = {
                    Column {
                        if (dialog.message.isNotBlank()) {
                            Text(dialog.message, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog.result.confirm(value)
                        jsDialog = null
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dialog.result.cancel()
                        jsDialog = null
                    }) { Text("Cancel") }
                },
            )
        }
        null -> Unit
    }
}

/**
 * Receives shared `.miniapp` bundles or `.html` files from other apps.
 * Inspects and installs off the main thread; `.miniapp` bundles launch immediately,
 * while raw `.html` files prompt "Name your Mini App" + icon picker.
 */
class MiniAppImportActivity : ComponentActivity() {
    private var rawHtmlPreview by mutableStateOf<MiniAppImportPreview.RawHtml?>(null)
    private var appThemeState by mutableStateOf("oled")

    override fun onResume() {
        super.onResume()
        appThemeState = AgentStorage(this).loadTheme()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appThemeState = AgentStorage(this).loadTheme()
        val uri = if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } else {
            intent.data
        }
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val repo = miniAppRepository(this)

        lifecycleScope.launch {
            val inspected = withContext(Dispatchers.IO) {
                runCatching {
                    if (uri != null) {
                        readImportPreviewFromUri(this@MiniAppImportActivity, repo, uri)
                    } else {
                        val text = extraText?.takeIf { MiniAppSanitizer.looksLikeHtml(it) }
                            ?: throw IllegalArgumentException("Android did not pass a mini app file to import.")
                        repo.inspectImport(text.toByteArray(Charsets.UTF_8), "shared.html")
                    }
                }
            }
            inspected.onFailure { err ->
                Toast.makeText(
                    this@MiniAppImportActivity,
                    err.message ?: "The mini app could not be imported.",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }.onSuccess { preview ->
                when (preview) {
                    is MiniAppImportPreview.Bundle -> {
                        val installed = withContext(Dispatchers.IO) {
                            runCatching { repo.installImport(preview) }
                        }
                        installed.onSuccess { app ->
                            MiniAppActivity.open(this@MiniAppImportActivity, app)
                            Toast.makeText(
                                this@MiniAppImportActivity,
                                "Imported ${app.name}. Shared apps run offline.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }.onFailure { err ->
                            Toast.makeText(
                                this@MiniAppImportActivity,
                                err.message ?: "Could not install this mini app.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        finish()
                    }
                    is MiniAppImportPreview.RawHtml -> {
                        rawHtmlPreview = preview
                    }
                }
            }
        }

        setContent {
            val darkTheme = isDarkThemeForAppearance(appThemeState, isSystemInDarkTheme())
            OpenRouterAgentTheme(darkTheme = darkTheme) {
                val preview = rawHtmlPreview
                if (preview == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 6.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                Spacer(Modifier.width(14.dp))
                                Text("Importing mini app…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } else {
                    MiniAppMetadataDialog(
                        title = "Name your Mini App",
                        confirmLabel = "Import",
                        initialName = preview.suggestedName,
                        initialThemeColor = MINI_APP_DEFAULT_THEME_COLOR,
                        initialIconUri = null,
                        existingIconFile = null,
                        existingIconSvg = null,
                        onDismiss = { finish() },
                        onSave = { name, themeColor, iconUpdate ->
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    repo.installImport(
                                        preview = preview,
                                        overrideName = name,
                                        overrideThemeColor = themeColor,
                                        iconUpdate = iconUpdate,
                                    )
                                }
                            }
                            withContext(Dispatchers.Main.immediate) {
                                result.onSuccess { app ->
                                    MiniAppActivity.open(this@MiniAppImportActivity, app)
                                    Toast.makeText(
                                        this@MiniAppImportActivity,
                                        "Added ${app.name}. Imported apps run offline.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    finish()
                                }.onFailure { err ->
                                    Toast.makeText(
                                        this@MiniAppImportActivity,
                                        err.message ?: "Could not import mini app.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    throw err
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberDecodedIconBitmap(iconFile: File?, cacheKey: Any?): Bitmap? {
    val path = iconFile?.absolutePath
    val entryKey = remember(path, cacheKey) { path?.let { "$it|$cacheKey" } }
    val cached = remember(entryKey) { entryKey?.let(MiniAppIconRenderer::cachedDecodedIcon) }
    var bitmap by remember(entryKey) { mutableStateOf(cached) }
    LaunchedEffect(path, entryKey) {
        if (path == null || entryKey == null) {
            bitmap = null
        } else if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) {
                MiniAppIconRenderer.decodeBitmapFileCached(File(path), entryKey)
            }
        }
    }
    return if (path == null) null else bitmap
}

/** Shared rounded-square icon badge used across Launcher Grid, Context Menu, Player Top Bar, and Editor preview. */
@Composable
internal fun MiniAppIconBadge(
    app: MiniApp,
    iconFile: File?,
    size: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val fileBitmap = rememberDecodedIconBitmap(iconFile, Triple(app.id, app.updatedAt, app.version))
    val shape = RoundedCornerShape(cornerRadius)
    if (fileBitmap != null) {
        Image(
            bitmap = fileBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
        return
    }
    val preset = remember(app.iconUri) { parsePresetFromIconUri(app.iconUri) }
    val presetKey = remember(preset?.id, app.themeColor) { preset?.let { it.id to app.themeColor } }
    val cachedPreset = remember(presetKey) {
        preset?.let { MiniAppIconRenderer.cachedPresetTileBitmap(it, app.themeColor, MINI_APP_ICON_SIZE_PX) }
    }
    var presetBitmap by remember(presetKey) { mutableStateOf(cachedPreset) }
    LaunchedEffect(presetKey) {
        if (preset == null) {
            presetBitmap = null
        } else if (presetBitmap == null) {
            presetBitmap = withContext(Dispatchers.Default) {
                runCatching {
                    MiniAppIconRenderer.renderPresetTileBitmap(preset, app.themeColor, MINI_APP_ICON_SIZE_PX)
                }.getOrNull()
            }
        }
    }
    val currentPresetBitmap = presetBitmap
    if (preset != null && currentPresetBitmap != null) {
        Image(
            bitmap = currentPresetBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
        return
    }
    val emoji = remember(app.iconUri) { parseEmojiFromIconUri(app.iconUri) }
    val bgColor = remember(app.themeColor) { Color(MiniAppIconRenderer.parseColorInt(app.themeColor)) }
    val fgColor = remember(app.themeColor) {
        if (MiniAppIconRenderer.isLightColor(app.themeColor)) Color(0xFF111827) else Color.White
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        val text = emoji ?: fallbackIconInitial(app.name)
        Text(
            text = text,
            color = fgColor,
            fontWeight = if (emoji == null) FontWeight.Bold else FontWeight.Normal,
            style = if (size >= 56.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        )
    }
}
