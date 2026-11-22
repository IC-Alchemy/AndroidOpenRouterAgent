package com.example.openrouteragent

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniAppAndroidIntegrationTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun sqliteIndexPersistsOrdersFindsAndDeletesMiniApps() {
        val dbFile = File(folder.newFolder("db"), "mini_apps.db")
        val db1 = MiniAppDatabase(context, dbFile)
        val first = MiniApp(
            id = "550e8400-e29b-41d4-a716-446655440000",
            name = "Tip Calculator",
            themeColor = "#2563EB",
            iconUri = ICON_URI_FILE,
            entryPoint = "index.html",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        val second = MiniApp(
            id = "660e8400-e29b-41d4-a716-446655440001",
            name = "Habit Tracker",
            themeColor = "#16A34A",
            iconUri = "emoji:🔥",
            entryPoint = "index.html",
            allowNetwork = false,
            imported = true,
            createdAt = 2000L,
            updatedAt = 3000L,
        )
        db1.upsert(first)
        db1.upsert(second)
        db1.close()

        // Reopen database from disk to verify real SQLite persistence.
        val db2 = MiniAppDatabase(context, dbFile)
        try {
            val all = db2.list()
            assertEquals(listOf(second.id, first.id), all.map { it.id })
            assertEquals(first.id, db2.find("tip calculator")?.id)
            assertEquals("emoji:🔥", db2.find(second.id)?.iconUri)
            assertFalse(db2.find(second.id)!!.allowNetwork)

            db2.delete(first.id)
            assertNull(db2.find(first.id))
            assertEquals(1, db2.list().size)
        } finally {
            db2.close()
        }
    }

    @Test
    fun androidSvgAndUploadedPhotoRenderTo192x192PngBitmaps() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" stroke-width="2"><circle cx="12" cy="12" r="8"/></svg>"""
        val svgPng = MiniAppIconRenderer.renderSvgToPng(svg, "#2563EB", "Timer")
        val decodedSvg = BitmapFactory.decodeByteArray(svgPng, 0, svgPng.size)
        assertNotNull(decodedSvg)
        assertEquals(MINI_APP_ICON_SIZE_PX, decodedSvg.width)
        assertEquals(MINI_APP_ICON_SIZE_PX, decodedSvg.height)

        // Malicious SVG must safely fall back without crashing and still emit a valid 192x192 PNG.
        val maliciousPng = MiniAppIconRenderer.renderSvgToPng(
            """<svg viewBox="0 0 24 24"><script>alert(1)</script></svg>""",
            "#DC2626",
            "Safe",
        )
        val decodedFallback = BitmapFactory.decodeByteArray(maliciousPng, 0, maliciousPng.size)
        assertNotNull(decodedFallback)
        assertEquals(MINI_APP_ICON_SIZE_PX, decodedFallback.width)
        assertEquals(MINI_APP_ICON_SIZE_PX, decodedFallback.height)

        // SVG missing xmlns and using currentColor renders visible white strokes on a dark background.
        val currentColorSvg = """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><rect x="4" y="4" width="16" height="16"/></svg>"""
        val currentColorPng = MiniAppIconRenderer.renderSvgToPng(currentColorSvg, "#111827", "Box")
        val decodedCurrentColor = BitmapFactory.decodeByteArray(currentColorPng, 0, currentColorPng.size)
        assertNotNull(decodedCurrentColor)
        var brightPixels = 0
        for (y in 0 until decodedCurrentColor.height) {
            for (x in 0 until decodedCurrentColor.width) {
                val px = decodedCurrentColor.getPixel(x, y)
                if (Color.red(px) > 200 && Color.green(px) > 200 && Color.blue(px) > 200) brightPixels++
            }
        }
        assertTrue("Expected bright white SVG strokes to render for currentColor SVG, got $brightPixels", brightPixels > 100)

        // Empty SVG falls back to centered initial text instead of producing a blank icon.
        val emptySvgPng = MiniAppIconRenderer.renderSvgToPng("""<svg viewBox="0 0 24 24"></svg>""", "#111827", "Z")
        val decodedEmptySvg = BitmapFactory.decodeByteArray(emptySvgPng, 0, emptySvgPng.size)
        assertNotNull(decodedEmptySvg)
        var fallbackBright = 0
        for (y in 0 until decodedEmptySvg.height) {
            for (x in 0 until decodedEmptySvg.width) {
                val px = decodedEmptySvg.getPixel(x, y)
                if (Color.red(px) > 200 && Color.green(px) > 200 && Color.blue(px) > 200) fallbackBright++
            }
        }
        assertTrue("Expected fallback initial glyph on empty SVG, got $fallbackBright", fallbackBright > 50)

        // Non-square uploaded photo is center-cropped and resized to 192x192 PNG.
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val photoBytes = ByteArrayOutputStream().use { out ->
            source.compress(Bitmap.CompressFormat.PNG, 100, out)
            source.recycle()
            out.toByteArray()
        }
        val resizedPng = MiniAppIconRenderer.decodeAndResizeUploadedImage(ByteArrayInputStream(photoBytes))
        val decodedResized = BitmapFactory.decodeByteArray(resizedPng, 0, resizedPng.size)
        assertNotNull(decodedResized)
        assertEquals(MINI_APP_ICON_SIZE_PX, decodedResized.width)
        assertEquals(MINI_APP_ICON_SIZE_PX, decodedResized.height)

        // Preset icon on a light theme color uses dark foreground strokes for WCAG legibility.
        val lightPresetBitmap = MiniAppIconRenderer.renderPresetTileBitmap(MINI_APP_PRESET_ICONS.first(), "#FDE047", 96)
        var darkPixels = 0
        for (y in 0 until lightPresetBitmap.height) {
            for (x in 0 until lightPresetBitmap.width) {
                val px = lightPresetBitmap.getPixel(x, y)
                if (Color.red(px) < 60 && Color.green(px) < 60 && Color.blue(px) < 60) darkPixels++
            }
        }
        assertTrue("Expected dark foreground pixels on light preset tile, got $darkPixels", darkPixels > 50)
    }

    @Test
    fun assetLoaderIsolatesVirtualDomainsAndRoutesExternalLinks() {
        val appIdA = "550e8400-e29b-41d4-a716-446655440000"
        val appIdB = "660e8400-e29b-41d4-a716-446655440001"
        val dirA = File(context.filesDir, "$MINI_APP_DIR_NAME/$appIdA").apply { mkdirs() }
        val dirB = File(context.filesDir, "$MINI_APP_DIR_NAME/$appIdB").apply { mkdirs() }
        try {
            File(dirA, "index.html").writeText("<!DOCTYPE html><html><body>App A</body></html>")
            File(dirA, MINI_APP_MANIFEST_FILE_NAME).writeText("""{"id":"$appIdA","name":"App A"}""")
            File(dirA, "index.html.1234.tmp").writeText("transient temp")
            File(dirA, "graphic.svg").writeText("""<svg xmlns="http://www.w3.org/2000/svg"></svg>""")
            File(dirB, "index.html").writeText("<!DOCTYPE html><html><body>App B</body></html>")

            val loaderA = createAssetLoader(context, appIdA)
            val openedExternally = mutableListOf<Uri>()
            val onlineClient = MiniAppWebViewClient(appIdA, allowNetwork = true, assetLoader = loaderA) {
                openedExternally += it
            }

            // Virtual HTTPS origin serves index.html from app A's private directory with 200 OK, UTF-8 + CSP.
            val responseA = onlineClient.interceptUrl(Uri.parse("https://$appIdA.miniapp.local/index.html"))
            assertNotNull(responseA)
            assertEquals(200, responseA!!.statusCode)
            assertEquals("text/html", responseA.mimeType)
            assertEquals("UTF-8", responseA.encoding)
            assertTrue(responseA.responseHeaders["Content-Security-Policy"]!!.contains("'self'"))
            assertTrue(responseA.data.bufferedReader().readText().contains("App A"))

            // Bare root "/" resolves to the mini app entry point (index.html) instead of returning 404.
            val rootResponse = onlineClient.interceptUrl(Uri.parse("https://$appIdA.miniapp.local/"))
            assertNotNull(rootResponse)
            assertEquals(200, rootResponse!!.statusCode)
            assertTrue(rootResponse.data.bufferedReader().readText().contains("App A"))

            // Internal bundle manifest.json and transient .tmp files must never be served to web content (404).
            assertEquals(404, onlineClient.interceptUrl(Uri.parse("https://$appIdA.miniapp.local/manifest.json"))!!.statusCode)
            assertEquals(404, onlineClient.interceptUrl(Uri.parse("https://$appIdA.miniapp.local/MANIFEST.JSON"))!!.statusCode)
            assertEquals(404, onlineClient.interceptUrl(Uri.parse("https://$appIdA.miniapp.local/./manifest.json"))!!.statusCode)
            assertEquals(404, onlineClient.interceptUrl(Uri.parse("https://$appIdA.miniapp.local/sub/../manifest.json"))!!.statusCode)
            assertEquals(404, onlineClient.interceptUrl(Uri.parse("https://$appIdA.miniapp.local/index.html.1234.tmp"))!!.statusCode)

            // Active SVG documents served from bundle also receive Content-Security-Policy headers.
            val svgResponse = onlineClient.interceptUrl(Uri.parse("https://$appIdA.miniapp.local/graphic.svg"))
            assertNotNull(svgResponse)
            assertEquals(200, svgResponse!!.statusCode)
            assertTrue(svgResponse.responseHeaders["Content-Security-Policy"]!!.contains("frame-src 'none'"))

            // Missing local file on app A's virtual host returns 404 instead of hitting DNS.
            val missing = onlineClient.interceptUrl(Uri.parse("https://$appIdA.miniapp.local/missing.js"))
            assertNotNull(missing)
            assertEquals(404, missing!!.statusCode)

            // App A cannot access app B's virtual domain (including trailing-dot DNS variant) or file:// / content:// / http://.
            assertEquals(403, onlineClient.interceptUrl(Uri.parse("https://$appIdB.miniapp.local/index.html"))!!.statusCode)
            assertEquals(403, onlineClient.interceptUrl(Uri.parse("https://$appIdB.miniapp.local./index.html"))!!.statusCode)
            assertEquals(403, onlineClient.interceptUrl(Uri.parse("file://${dirB.absolutePath}/index.html"))!!.statusCode)
            assertEquals(403, onlineClient.interceptUrl(Uri.parse("content://com.example/secret"))!!.statusCode)
            assertEquals(403, onlineClient.interceptUrl(Uri.parse("http://insecure.example.com/a.js"))!!.statusCode)

            // Online app allows HTTPS CDN/API requests (returns null to let WebView fetch).
            assertNull(onlineClient.interceptUrl(Uri.parse("https://cdn.jsdelivr.net/npm/chart.js")))

            // Offline/imported app blocks external HTTPS requests with 403, but allows in-memory data: and blob:.
            val offlineClient = MiniAppWebViewClient(appIdA, allowNetwork = false, assetLoader = loaderA) {}
            assertEquals(403, offlineClient.interceptUrl(Uri.parse("https://cdn.jsdelivr.net/npm/chart.js"))!!.statusCode)
            assertNull(offlineClient.interceptUrl(Uri.parse("data:audio/wav;base64,UklGRg==")))
            assertNull(offlineClient.interceptUrl(Uri.parse("blob:https://$appIdA.miniapp.local/1234")))

            // Navigation routing: internal links stay inside WebView even without a gesture.
            assertFalse(onlineClient.handleNavigation(Uri.parse("https://$appIdA.miniapp.local/details.html"), hasGesture = false))
            assertTrue(openedExternally.isEmpty())

            // Unsolicited script redirects (hasGesture = false) are blocked without opening the system browser.
            assertTrue(onlineClient.handleNavigation(Uri.parse("https://example.com/unsolicited"), hasGesture = false))
            assertTrue(openedExternally.isEmpty())

            // Cross-mini-app virtual domain navigation (including trailing-dot DNS) is blocked even with a gesture.
            assertTrue(onlineClient.handleNavigation(Uri.parse("https://$appIdB.miniapp.local/index.html"), hasGesture = true))
            assertTrue(onlineClient.handleNavigation(Uri.parse("https://$appIdB.miniapp.local./index.html"), hasGesture = true))
            assertTrue(openedExternally.isEmpty())

            // Explicit user-tapped external HTTPS link opens in the system browser.
            assertTrue(onlineClient.handleNavigation(Uri.parse("https://example.com/docs"), hasGesture = true))
            assertEquals(listOf(Uri.parse("https://example.com/docs")), openedExternally)

            // Dangerous non-HTTP schemes are blocked without launching external intent.
            assertTrue(onlineClient.handleNavigation(Uri.parse("javascript:alert(1)"), hasGesture = true))
            assertEquals(1, openedExternally.size)
        } finally {
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }

    @Test
    fun playerScreenRendersChromeHeaderHandlesRestartBackAndExit() {
        val app = MiniApp(
            id = "550e8400-e29b-41d4-a716-446655440000",
            name = "Pomodoro Focus",
            themeColor = "#2563EB",
            iconUri = "emoji:⏱️",
        )
        var exited = false
        var observedUrl = ""
        var observedReloads = 0
        var historyDepth = 1
        val externalReload = mutableIntStateOf(0)
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            OpenRouterAgentTheme {
                MiniAppPlayerScreen(
                    app = app,
                    iconFile = null,
                    onExit = { exited = true },
                    externalReloadTrigger = externalReload.intValue,
                    onBackNavigation = {
                        if (historyDepth > 0) {
                            historyDepth--
                            true
                        } else {
                            false
                        }
                    },
                    webViewContent = { _, url, reloadNonce ->
                        observedUrl = url
                        observedReloads = reloadNonce
                        Text("WebView:$url:$reloadNonce")
                    },
                )
            }
        }

        composeRule.onNodeWithText("Pomodoro Focus").assertIsDisplayed()
        assertEquals("https://${app.id}.miniapp.local/index.html", observedUrl)

        composeRule.onNodeWithContentDescription("Restart Pomodoro Focus").performClick()
        composeRule.waitForIdle()
        assertEquals(1, observedReloads)

        // External reload trigger (e.g., after Edit with AI or Clear App Data) bumps reloadNonce.
        externalReload.intValue = 1
        composeRule.waitForIdle()
        assertEquals(2, observedReloads)

        // First system Back navigates inside mini app history instead of exiting.
        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, historyDepth)
        assertFalse(exited)

        // Second system Back exits cleanly once history is empty.
        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertTrue(exited)
    }

    @Test
    fun changeIconThemeColorRerendersAiGeneratedSvgPngIcon() {
        val db = MiniAppDatabase(context, File(folder.newFolder("sdb"), "mini.db"))
        try {
            val repo = MiniAppRepository(folder.newFolder("svg_color"), db)
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" stroke-width="2"><circle cx="12" cy="12" r="8"/></svg>"""
            val initialPng = MiniAppIconRenderer.renderSvgToPng(svg, "#2563EB", "Focus")
            val saved = repo.save(
                name = "Focus",
                html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Focus</title></head><body>ok</body></html>",
                themeColor = "#2563EB",
                iconUpdate = MiniAppIconUpdate.PngBytes(initialPng, iconSvg = svg),
            )

            composeRule.setContent {
                OpenRouterAgentTheme {
                    MiniAppsSheet(
                        onDismiss = {},
                        providerReady = true,
                        selectedModelName = "GPT-4o",
                        onCreateWithAi = { saved },
                        onEditWithAi = { _, _ -> saved },
                        onAskInChat = {},
                        repository = repo,
                        onOpenApp = {},
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithText("Focus").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            composeRule.onNodeWithText("Focus").performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Change Icon").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()

            // Pick green swatch #16A34A without changing icon tab, then save.
            composeRule.onNodeWithContentDescription("Color #16A34A").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                repo.find(saved.id)?.themeColor == "#16A34A"
            }
            val updatedBytes = repo.iconFile(saved.id)!!.readBytes()
            assertFalse("Expected AI SVG icon.png to re-render with new theme color", updatedBytes.contentEquals(initialPng))
            assertNotNull(repo.manifest(saved.id)?.iconSvg)
            val decoded = BitmapFactory.decodeByteArray(updatedBytes, 0, updatedBytes.size)
            assertNotNull(decoded)
            // Corner/background pixel inside the rounded square should now be green-dominant (G > B), not blue.
            val bgPixel = decoded.getPixel(24, 24)
            assertTrue(
                "Expected green-dominant background after themeColor update, got R=${Color.red(bgPixel)} G=${Color.green(bgPixel)} B=${Color.blue(bgPixel)}",
                Color.green(bgPixel) > Color.blue(bgPixel),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun miniAppActivityRefreshesOnNewIntentAndFinishesWhenDeleted() {
        val repo = miniAppRepository(context)
        val app = repo.save(
            name = "Live Timer",
            html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Live Timer</title></head><body>v1</body></html>",
            themeColor = "#2563EB",
            iconUpdate = MiniAppIconUpdate.Emoji("⏱️"),
        )
        try {
            val controller = Robolectric.buildActivity(MiniAppActivity::class.java, MiniAppActivity.intent(context, app))
                .setup()
            val activity = controller.get()
            assertEquals("Live Timer", activity.title.toString())
            assertFalse(activity.isFinishing)

            // Simulate user editing the mini app in-place and re-opening via intoExisting document launch.
            val updated = repo.save(
                name = "Focus Pro",
                html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Focus Pro</title></head><body>v2</body></html>",
                themeColor = "#16A34A",
                id = app.id,
            )
            controller.newIntent(MiniAppActivity.intent(context, updated))
            assertEquals("Focus Pro", activity.title.toString())

            // Deleting the mini app causes onResume to close the stale activity cleanly.
            repo.delete(app.id)
            controller.pause().resume()
            assertTrue(activity.isFinishing)
            controller.destroy()
        } finally {
            repo.delete(app.id)
        }
    }

    @Test
    fun shareArchiveCleansStaleCacheFilesBuildsPortableZipAndSupportsSafExport() {
        val repo = miniAppRepository(context)
        val app = repo.save(
            name = "Shareable App",
            html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Shareable App</title></head><body>ok</body></html>",
            themeColor = "#7C3AED",
            iconUpdate = MiniAppIconUpdate.Emoji("🚀"),
        )
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val oldTimestamp = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        val staleOne = File(sharedDir, "Old App.miniapp").apply {
            writeText("stale1")
            assertTrue(setLastModified(oldTimestamp))
        }
        val staleTwo = File(sharedDir, "Legacy.MINIAPP").apply {
            writeText("stale2")
            assertTrue(setLastModified(oldTimestamp))
        }
        val staleTmp = File(sharedDir, "Crashed.miniapp.1234.tmp").apply {
            writeText("partial")
            assertTrue(setLastModified(oldTimestamp))
        }
        val staleShare = File(sharedDir, "miniapp-expired").apply {
            mkdir()
            File(this, "Expired.miniapp").writeText("expired")
            assertTrue(setLastModified(oldTimestamp))
        }
        val unrelated = File(sharedDir, "unrelated.txt").apply { writeText("keep") }
        try {
            val archive = MiniAppActivity.prepareShareArchive(context, app)
            assertTrue(archive.isFile && archive.name == "Shareable App.miniapp")
            assertEquals(sharedDir.canonicalFile, archive.parentFile?.parentFile?.canonicalFile)
            assertFalse("Stale .miniapp cache file should be cleaned up", staleOne.exists())
            assertFalse("Stale uppercase .MINIAPP cache file should be cleaned up", staleTwo.exists())
            assertFalse("Stale .miniapp.*.tmp cache file should be cleaned up", staleTmp.exists())
            assertFalse("Expired share directory should be cleaned up", staleShare.exists())
            assertTrue("Non-.miniapp cache files should not be removed", unrelated.isFile)
            val originalBytes = archive.readBytes()
            val reimported = repo.inspectImport(originalBytes, archive.name)
            assertTrue(reimported is MiniAppImportPreview.Bundle)
            val secondArchive = MiniAppActivity.prepareShareArchive(context, app)
            assertTrue("Each Sharesheet invocation needs its own stable URI", archive != secondArchive)
            assertTrue(secondArchive.isFile)
            assertArrayEquals(originalBytes, archive.readBytes())

            // Verify SAF export helper writes a valid round-trippable .miniapp bundle and truncates larger existing files.
            val safDest = File(folder.newFolder("saf_export"), "Backup.miniapp").apply {
                writeBytes(ByteArray(16_384) { 0x5A })
            }
            writeExportBundleToUri(context, repo, app.id, Uri.fromFile(safDest))
            assertTrue(safDest.isFile && safDest.length() > 0 && safDest.length() < 16_384)
            val safPreview = repo.inspectImport(safDest.readBytes(), safDest.name)
            assertTrue(safPreview is MiniAppImportPreview.Bundle)
            val safBundle = safPreview as MiniAppImportPreview.Bundle
            assertEquals("Shareable App", safBundle.suggestedName)
            assertEquals("#7C3AED", safBundle.themeColor)
            assertEquals("emoji:🚀", safBundle.iconUri)

            val uri = if (File.separatorChar == '/') {
                androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", archive)
            } else {
                Uri.parse("content://${context.packageName}.files/shared/${archive.parentFile?.name}/${Uri.encode(archive.name)}")
            }
            val chooser = MiniAppActivity.buildShareChooserIntent(context, app, archive.name, uri)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION")
            val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(inner)
            assertEquals(Intent.ACTION_SEND, inner!!.action)
            assertNotNull(inner.clipData)
            assertEquals(uri, inner.clipData!!.getItemAt(0).uri)
            assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            unrelated.delete()
            repo.delete(app.id)
        }
    }

    @Test
    fun appThemeHelperAndStorageLoadThemeHonorOledLightAndSystemModes() {
        assertTrue(isDarkThemeForAppearance("oled", systemDark = false))
        assertTrue(isDarkThemeForAppearance("oled", systemDark = true))
        assertFalse(isDarkThemeForAppearance("light", systemDark = true))
        assertFalse(isDarkThemeForAppearance("light", systemDark = false))
        assertTrue(isDarkThemeForAppearance("system", systemDark = true))
        assertFalse(isDarkThemeForAppearance("system", systemDark = false))

        val storage = AgentStorage(context)
        val prefs = context.getSharedPreferences("agent_settings", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("config", null)
        try {
            prefs.edit().remove("config").commit()
            assertEquals("oled", storage.loadTheme())

            prefs.edit().putString("config", """{"sdk":{"theme":"light"}}""").commit()
            assertEquals("light", storage.loadTheme())

            prefs.edit().putString("config", """{"sdk":{"theme":"system"}}""").commit()
            assertEquals("system", storage.loadTheme())
        } finally {
            prefs.edit().apply {
                if (previous == null) remove("config") else putString("config", previous)
            }.commit()
        }
    }

    @Test
    fun launcherGridOpensAppOnTapAndShowsContextMenuOnLongPress() {
        val db = MiniAppDatabase(context, File(folder.newFolder("ldb"), "mini.db"))
        try {
            val repo = MiniAppRepository(folder.newFolder("launcher"), db)
            val saved = repo.save(
                name = "Tip Split",
                html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Tip Split</title></head><body>ok</body></html>",
                themeColor = "#16A34A",
                iconUpdate = MiniAppIconUpdate.Emoji("💰"),
            )
            val opened = mutableListOf<MiniApp>()

            composeRule.setContent {
                OpenRouterAgentTheme {
                    MiniAppsSheet(
                        onDismiss = {},
                        providerReady = true,
                        selectedModelName = "GPT-4o",
                        onCreateWithAi = { saved },
                        onEditWithAi = { _, _ -> saved },
                        onAskInChat = {},
                        repository = repo,
                        onOpenApp = { opened += it },
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithText("Tip Split").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            composeRule.waitForIdle()

            // ModalBottomSheet runs in a separate Dialog window in Robolectric; semantics actions target it directly.
            composeRule.onNodeWithText("Tip Split").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            assertEquals(listOf(saved.id), opened.map { it.id })

            composeRule.onNodeWithText("Tip Split").performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Edit with AI").assertIsDisplayed()
            composeRule.onNodeWithText("Rename").assertIsDisplayed()
            composeRule.onNodeWithText("Change Icon").assertIsDisplayed()
            composeRule.onNodeWithText("Export / Share").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Save .miniapp File").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Clear App Data").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Delete").performScrollTo().assertIsDisplayed()

            // Exercise Rename flow from context menu and verify persistence in SQLite + UI refresh.
            composeRule.onNodeWithText("Rename").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Rename Mini App").assertIsDisplayed()
            composeRule.onNodeWithText("Name").performTextClearance()
            composeRule.onNodeWithText("Name").performTextInput("Bill Splitter")
            composeRule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithText("Bill Splitter").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            assertEquals("Bill Splitter", repo.find(saved.id)?.name)

            // Exercise Clear App Data flow and verify clear generation increments without deleting the app.
            val beforeClear = miniAppClearGeneration(saved.id)
            composeRule.onNodeWithText("Bill Splitter").performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Clear App Data").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("localStorage", substring = true, ignoreCase = true).assertDoesNotExist()
            composeRule.onNodeWithText("Clear data").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            assertTrue(miniAppClearGeneration(saved.id) > beforeClear)
            assertNotNull(repo.find(saved.id))

            // Exercise Delete flow and verify both SQLite row and disk directory are removed.
            composeRule.onNodeWithText("Bill Splitter").performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Delete").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Delete").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { repo.find(saved.id) == null }
            assertFalse(repo.appDir(saved.id).exists())
        } finally {
            db.close()
        }
    }

    @Test
    fun miniAppImportActivityHandlesRawHtmlAndInvalidIntentsOffMainThread() {
        val rawHtmlIntent = Intent(context, MiniAppImportActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_TEXT, "<!DOCTYPE html><html><head><title>Shared Pad</title></head><body>Hi</body></html>")
        val controller = Robolectric.buildActivity(MiniAppImportActivity::class.java, rawHtmlIntent).setup()
        try {
            // Activity stays open waiting for user to name the raw HTML app rather than crashing or finishing.
            assertFalse(controller.get().isFinishing)
        } finally {
            controller.destroy()
        }

        // Invalid intent with no URI or HTML finishes gracefully off-thread without crashing.
        val emptyIntent = Intent(context, MiniAppImportActivity::class.java).setAction(Intent.ACTION_SEND)
        val badController = Robolectric.buildActivity(MiniAppImportActivity::class.java, emptyIntent).setup()
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (!badController.get().isFinishing && System.currentTimeMillis() < deadline) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(25)
            }
            assertTrue(badController.get().isFinishing)
        } finally {
            badController.destroy()
        }
    }

    @Test
    fun safExportLauncherWritesBundleAndHandlesCancelCleanly() {
        val db = MiniAppDatabase(context, File(folder.newFolder("saf_ui_db"), "mini.db"))
        try {
            val repo = MiniAppRepository(folder.newFolder("saf_ui_apps"), db)
            val saved = repo.save(
                name = "Budget Buddy",
                html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Budget Buddy</title></head><body>ok</body></html>",
                themeColor = "#0D9488",
                iconUpdate = MiniAppIconUpdate.Emoji("💰"),
            )
            val exportTarget = File(folder.newFolder("saf_ui_out"), "Budget Buddy.miniapp")
            val launchedInputs = mutableListOf<String>()
            var nextUriToReturn: Uri? = null
            val fakeRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    if (input is String) launchedInputs += input
                    dispatchResult(requestCode, nextUriToReturn)
                }
            }
            val registryOwner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = fakeRegistry
            }

            composeRule.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    OpenRouterAgentTheme {
                        MiniAppsSheet(
                            onDismiss = {},
                            providerReady = true,
                            selectedModelName = "GPT-4o",
                            onCreateWithAi = { saved },
                            onEditWithAi = { _, _ -> saved },
                            onAskInChat = {},
                            repository = repo,
                            onOpenApp = {},
                        )
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithText("Budget Buddy").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }

            // 1. User cancels SAF CreateDocument dialog (returns null Uri) -> no file written, no error.
            nextUriToReturn = null
            composeRule.onNodeWithText("Budget Buddy").performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Save .miniapp File").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            assertEquals(listOf("Budget Buddy.miniapp"), launchedInputs)
            assertFalse(exportTarget.exists())

            // 2. User chooses a destination Uri -> writes valid .miniapp bundle and displays status message.
            nextUriToReturn = Uri.fromFile(exportTarget)
            composeRule.onNodeWithText("Budget Buddy").performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Save .miniapp File").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithText("Saved Budget Buddy as a .miniapp file.").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            assertTrue(exportTarget.isFile && exportTarget.length() > 0)
            val reimported = repo.inspectImport(exportTarget.readBytes(), exportTarget.name)
            assertTrue(reimported is MiniAppImportPreview.Bundle)
            assertEquals("Budget Buddy", (reimported as MiniAppImportPreview.Bundle).suggestedName)
        } finally {
            db.close()
        }
    }

    @Test
    fun quickRenameAndChangeIconRegenerateAiFallbackIconWithoutFlickeringPhotoTab() {
        val db = MiniAppDatabase(context, File(folder.newFolder("fallback_ui_db"), "mini.db"))
        try {
            val repo = MiniAppRepository(folder.newFolder("fallback_ui_apps"), db)
            val initialFallbackPng = MiniAppIconRenderer.renderSvgToPng(null, "#2563EB", "Water Log")
            val aiFallbackApp = repo.save(
                name = "Water Log",
                html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Water Log</title></head><body>ok</body></html>",
                themeColor = "#2563EB",
                iconUpdate = MiniAppIconUpdate.PngBytes(initialFallbackPng, iconSvg = null, aiGenerated = true),
            )
            val uploadedPhotoBytes = MiniAppIconRenderer.renderEmojiToPng("🐾", "#DC2626")
            val photoApp = repo.save(
                name = "Alpha Photo",
                html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Alpha Photo</title></head><body>ok</body></html>",
                themeColor = "#DC2626",
                iconUpdate = MiniAppIconUpdate.PngBytes(uploadedPhotoBytes, iconSvg = null, aiGenerated = false),
            )

            composeRule.setContent {
                OpenRouterAgentTheme {
                    MiniAppsSheet(
                        onDismiss = {},
                        providerReady = true,
                        selectedModelName = "GPT-4o",
                        onCreateWithAi = { aiFallbackApp },
                        onEditWithAi = { _, _ -> aiFallbackApp },
                        onAskInChat = {},
                        repository = repo,
                        onOpenApp = {},
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithText("Water Log").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }

            // Opening Change Icon on AI fallback app stays on Icons tab (does NOT flash Photo tab), and color change updates icon.png.
            composeRule.onNodeWithText("Water Log").performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Change Icon").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Choose photo from gallery").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Color #16A34A").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { repo.find(aiFallbackApp.id)?.themeColor == "#16A34A" }
            val greenFallbackBytes = repo.iconFile(aiFallbackApp.id)!!.readBytes()
            assertFalse(greenFallbackBytes.contentEquals(initialFallbackPng))
            assertTrue(repo.manifest(aiFallbackApp.id)!!.aiGeneratedIcon)

            // Quick Rename from "Water Log" to "Hydration" regenerates the AI fallback initial icon.
            composeRule.onNodeWithText("Water Log").performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Rename").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Name").performTextClearance()
            composeRule.onNodeWithText("Name").performTextInput("Hydration")
            composeRule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { repo.find(aiFallbackApp.id)?.name == "Hydration" }
            val renamedFallbackBytes = repo.iconFile(aiFallbackApp.id)!!.readBytes()
            assertFalse(
                "Expected Quick Rename on AI fallback icon to regenerate initial letter PNG",
                renamedFallbackBytes.contentEquals(greenFallbackBytes),
            )

            // Quick Rename on a user-uploaded photo app MUST preserve exact photo bytes untouched.
            composeRule.onNodeWithText("Alpha Photo").performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Rename").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Name").performTextClearance()
            composeRule.onNodeWithText("Name").performTextInput("Zebra Photo")
            composeRule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { repo.find(photoApp.id)?.name == "Zebra Photo" }
            assertArrayEquals(uploadedPhotoBytes, repo.iconFile(photoApp.id)!!.readBytes())
        } finally {
            db.close()
        }
    }

    @Test
    fun miniAppActivityUpdatesThemeOnResumeWhenUserSwitchesAppTheme() {
        val prefs = context.getSharedPreferences("agent_settings", android.content.Context.MODE_PRIVATE)
        val previousConfig = prefs.getString("config", null)
        val repo = miniAppRepository(context)
        val app = repo.save(
            name = "Theme Test",
            html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Theme Test</title></head><body>ok</body></html>",
            themeColor = "#2563EB",
            iconUpdate = MiniAppIconUpdate.Emoji("🎨"),
        )
        try {
            prefs.edit().putString("config", """{"sdk":{"theme":"oled"}}""").commit()
            val controller = Robolectric.buildActivity(MiniAppActivity::class.java, MiniAppActivity.intent(context, app))
                .setup()
            val activity = controller.get()
            composeRule.waitForIdle()
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse("OLED dark theme should use dark navigation bar", insets.isAppearanceLightNavigationBars)

            // Switch stored app theme to Light while MiniAppActivity is paused, then resume it.
            controller.pause()
            prefs.edit().putString("config", """{"sdk":{"theme":"light"}}""").commit()
            controller.resume()
            composeRule.waitForIdle()
            assertTrue("Light theme should update navigation bar appearance on resume", insets.isAppearanceLightNavigationBars)

            // Switching back to OLED black restores dark navigation bar contrast on resume.
            controller.pause()
            prefs.edit().putString("config", """{"sdk":{"theme":"oled"}}""").commit()
            controller.resume()
            composeRule.waitForIdle()
            assertFalse("OLED theme should restore dark navigation bar appearance on resume", insets.isAppearanceLightNavigationBars)
            controller.destroy()
        } finally {
            prefs.edit().apply {
                if (previousConfig == null) remove("config") else putString("config", previousConfig)
            }.commit()
            repo.delete(app.id)
        }
    }
}
