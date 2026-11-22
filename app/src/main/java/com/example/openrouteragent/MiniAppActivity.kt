package com.example.openrouteragent

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

private const val EXTRA_APP_ID = "mini_app_id"

/**
 * Maps `https://<uuid>.miniapp.local/` directly to `filesDir/mini_apps/<uuid>/` so Chromium isolates
 * each mini app's localStorage, cookies and IndexedDB onto its own virtual HTTPS origin.
 */
fun createAssetLoader(context: Context, miniAppId: String): WebViewAssetLoader {
    require(MiniAppSanitizer.isValidId(miniAppId)) { "Invalid mini app id." }
    val appContext = context.applicationContext
    val appDir = File(appContext.filesDir, "$MINI_APP_DIR_NAME/$miniAppId")
    return WebViewAssetLoader.Builder()
        .setDomain(miniAppHost(miniAppId))
        .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(appContext, appDir))
        .build()
}

/** Wipes WebView origin storage (localStorage / IndexedDB) for a single mini app. */
fun clearMiniAppOriginData(miniAppId: String) {
    if (!MiniAppSanitizer.isValidId(miniAppId)) return
    runCatching { WebStorage.getInstance().deleteOrigin(miniAppOrigin(miniAppId)) }
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
    private val onOpenExternalUrl: (Uri) -> Unit,
) : WebViewClient() {
    private val virtualHost = miniAppHost(miniAppId)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        interceptUrl(request.url)

    internal fun interceptUrl(url: Uri): WebResourceResponse? {
        val scheme = url.scheme?.lowercase()
        val host = url.host?.lowercase()
        if (scheme == "https" && host == virtualHost) {
            val response = assetLoader.shouldInterceptRequest(url)
                ?: return emptyResponse(404, "Not Found")
            val mime = response.mimeType?.lowercase().orEmpty()
            if (response.encoding.isNullOrBlank() && isUtf8Mime(mime)) {
                response.encoding = "UTF-8"
            }
            if (mime == "text/html") {
                val headers = LinkedHashMap<String, String>(response.responseHeaders.orEmpty())
                headers["Content-Security-Policy"] = MiniAppChecks.cspHeader(allowNetwork)
                response.responseHeaders = headers
            }
            return response
        }
        return if (allowNetwork && scheme == "https") {
            null
        } else {
            emptyResponse(403, "Forbidden")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleNavigation(request.url)

    internal fun handleNavigation(url: Uri): Boolean {
        val scheme = url.scheme?.lowercase()
        val host = url.host?.lowercase() ?: return true
        return if (scheme == "https" && host == virtualHost) {
            false
        } else {
            if (scheme == "https" || scheme == "http") {
                onOpenExternalUrl(url)
            }
            true
        }
    }

    private fun isUtf8Mime(mime: String): Boolean =
        mime.startsWith("text/") ||
            mime == "application/javascript" ||
            mime == "application/json" ||
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra(EXTRA_APP_ID) ?: intent.data?.lastPathSegment
        val repo = miniAppRepository(this)
        val app = id?.let { key -> repo.find(key)?.takeIf { it.id == key } }
        val entryExists = app != null && repo.html(app.id) != null
        if (app == null || !entryExists) {
            Toast.makeText(this, "This mini app was deleted.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        title = app.name
        val iconFile = repo.iconFile(app.id)
        val themeInt = MiniAppIconRenderer.parseColorInt(app.themeColor)
        if (Build.VERSION.SDK_INT >= 28) {
            val taskBitmap = runCatching { MiniAppIconRenderer.loadOrRenderAppIconBitmap(app, iconFile) }.getOrNull()
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(app.name, taskBitmap, themeInt))
        }

        setContent {
            val lightHeader = MiniAppIconRenderer.isLightColor(app.themeColor)
            val darkTheme = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightHeader
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            OpenRouterAgentTheme(darkTheme = darkTheme) {
                MiniAppPlayerScreen(
                    app = app,
                    iconFile = iconFile,
                    onExit = { finish() },
                )
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

        /** Asks the launcher to pin a shortcut with the mini app's rendered icon. */
        fun pin(context: Context, app: MiniApp): Boolean {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
            if (!manager.isRequestPinShortcutSupported) return false
            val iconFile = miniAppRepository(context).iconFile(app.id)
            val bitmap = MiniAppIconRenderer.loadOrRenderAppIconBitmap(app, iconFile)
            val shortcut = ShortcutInfo.Builder(context, "miniapp-${app.id}")
                .setShortLabel(app.name.take(24))
                .setLongLabel(app.name)
                .setIcon(Icon.createWithBitmap(bitmap))
                .setIntent(intent(context, app).setFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
                .build()
            return runCatching { manager.requestPinShortcut(shortcut, null) }.getOrDefault(false)
        }

        /** Disables the pinned shortcut of a deleted mini app, where the launcher allows it. */
        fun unpin(context: Context, id: String) {
            runCatching {
                context.getSystemService(ShortcutManager::class.java)
                    ?.disableShortcuts(listOf("miniapp-$id"), "This mini app was deleted.")
            }
        }

        /** Zips the mini app folder into `<MiniAppName>.miniapp` and shares it via FileProvider ShareSheet. */
        fun share(context: Context, app: MiniApp) {
            val repo = miniAppRepository(context)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val safe = app.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "mini-app" }
            val archive = File(dir, "$safe.miniapp")
            repo.exportBundle(app.id, archive)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", archive)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "${app.name}.miniapp")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                Intent.createChooser(send, "Share ${app.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private sealed interface JsDialogState {
    data class Alert(val message: String, val result: JsResult) : JsDialogState
    data class Confirm(val message: String, val result: JsResult) : JsDialogState
    data class Prompt(val message: String, val defaultValue: String, val result: JsPromptResult) : JsDialogState
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
    webViewContent: (@Composable (Modifier, String, Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val entryUrl = remember(app.id, app.entryPoint) { miniAppEntryUrl(app) }
    val headerBg = remember(app.themeColor) { Color(MiniAppIconRenderer.parseColorInt(app.themeColor)) }
    val headerFg = remember(app.themeColor) {
        if (MiniAppIconRenderer.isLightColor(app.themeColor)) Color(0xFF111827) else Color.White
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var reloadNonce by remember { mutableStateOf(0) }
    var jsDialog by remember { mutableStateOf<JsDialogState?>(null) }

    BackHandler(enabled = true) {
        val current = webViewRef
        if (current != null && current.canGoBack()) {
            current.goBack()
        } else {
            onExit()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            when (val pending = jsDialog) {
                is JsDialogState.Alert -> pending.result.cancel()
                is JsDialogState.Confirm -> pending.result.cancel()
                is JsDialogState.Prompt -> pending.result.cancel()
                null -> Unit
            }
            jsDialog = null
            webViewRef?.destroy()
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
                    reloadNonce++
                    webViewRef?.loadUrl(entryUrl)
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
                                jsDialog = JsDialogState.Alert(message.orEmpty(), result)
                                return true
                            }

                            override fun onJsConfirm(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: JsResult,
                            ): Boolean {
                                jsDialog = JsDialogState.Confirm(message.orEmpty(), result)
                                return true
                            }

                            override fun onJsPrompt(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                defaultValue: String?,
                                result: JsPromptResult,
                            ): Boolean {
                                jsDialog = JsDialogState.Prompt(message.orEmpty(), defaultValue.orEmpty(), result)
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
 * `.miniapp` bundles install immediately and launch; raw `.html` files prompt "Name your Mini App" + icon picker.
 */
class MiniAppImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } else {
            intent.data
        }

        val repo = miniAppRepository(this)
        val preview = try {
            val (bytes, fileName) = if (uri != null) {
                val readBytes = contentResolver.openInputStream(uri)?.use { input ->
                    val data = input.readBytesLimited(MINI_APP_MAX_CHARS * 2 + 1)
                    require(data.size <= MINI_APP_MAX_CHARS * 2) { "The file is too large to be a mini app." }
                    data
                } ?: throw IllegalArgumentException("Android could not open the selected file.")
                readBytes to queryDisplayName(uri)
            } else {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.takeIf { MiniAppSanitizer.looksLikeHtml(it) }
                    ?: throw IllegalArgumentException("Android did not pass a mini app file to import.")
                text.toByteArray(Charsets.UTF_8) to "shared.html"
            }
            repo.inspectImport(bytes, fileName)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "The mini app could not be imported.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        when (preview) {
            is MiniAppImportPreview.Bundle -> {
                try {
                    val app = repo.installImport(preview)
                    MiniAppActivity.open(this, app)
                    Toast.makeText(this, "Imported ${app.name}. Shared apps run offline.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "Could not install this mini app.", Toast.LENGTH_LONG).show()
                }
                finish()
            }
            is MiniAppImportPreview.RawHtml -> {
                setContent {
                    val scope = rememberCoroutineScope()
                    OpenRouterAgentTheme {
                        MiniAppMetadataDialog(
                            title = "Name your Mini App",
                            confirmLabel = "Import",
                            initialName = preview.suggestedName,
                            initialThemeColor = MINI_APP_DEFAULT_THEME_COLOR,
                            initialIconUri = null,
                            existingIconFile = null,
                            onDismiss = { finish() },
                            onSave = { name, themeColor, iconUpdate ->
                                scope.launch {
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
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

@Composable
internal fun rememberDecodedIconBitmap(iconFile: File?, cacheKey: Any?): Bitmap? =
    remember(iconFile?.absolutePath, iconFile?.lastModified(), cacheKey) {
        if (iconFile != null && iconFile.isFile) {
            runCatching { BitmapFactory.decodeFile(iconFile.absolutePath) }.getOrNull()
        } else {
            null
        }
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
    val fileBitmap = rememberDecodedIconBitmap(iconFile, app.updatedAt)
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
    val presetBitmap = remember(preset?.id, app.themeColor) {
        preset?.let {
            runCatching { MiniAppIconRenderer.renderSvgBitmap(it.svg, app.themeColor, app.name) }.getOrNull()
        }
    }
    if (presetBitmap != null) {
        Image(
            bitmap = presetBitmap.asImageBitmap(),
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
        val text = emoji ?: app.name.trim().firstOrNull()?.uppercase() ?: "A"
        Text(
            text = text,
            color = fgColor,
            fontWeight = if (emoji == null) FontWeight.Bold else FontWeight.Normal,
            style = if (size >= 56.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        )
    }
}
