package com.example.openrouteragent

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MiniAppsTest {
    @get:Rule val folder = TemporaryFolder()

    private val good = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
        <title> Tip   calculator </title><style>body{margin:0}</style></head>
        <body><script>localStorage.tip = 18</script></body></html>"""

    @Test
    fun aSelfContainedAppPassesTheChecks() {
        assertEquals(emptyList<String>(), MiniAppChecks.problems(good))
        assertEquals("Tip calculator", MiniAppChecks.title(good))
    }

    @Test
    fun externalCodeFramesAndMissingBasicsAreReported() {
        val bad = """<html><head><script src="https://cdn.example.com/x.js"></script>
            <link rel="stylesheet" href="//fonts.example.com/a.css"><style>@import url("https://x.com/a.css");</style>
            <meta http-equiv="refresh" content="0;url=https://x.com"><base href="https://x.com/"></head>
            <body><iframe src="https://x.com"></iframe><form action="https://x.com/post"></form></body></html>"""
        val problems = MiniAppChecks.problems(bad).joinToString("\n")
        listOf("<title>", "viewport", "<script src>", "<link>", "@import", "iframes", "form action", "meta refresh",
            "<base>").forEach { assertTrue("missing $it in\n$problems", problems.contains(it)) }
        assertTrue(MiniAppChecks.problems("<html></html>" + "x".repeat(MINI_APP_MAX_CHARS)).any { it.contains("larger") })
        assertTrue(MiniAppChecks.problems("<div>no html</div>").any { it.contains("<html>") })
    }

    @Test
    fun repositorySavesBundlesUnderUuidDirectoryUpdatesInPlaceAndDeletesCleanly() {
        var now = 100L
        val root = folder.newFolder("mini_apps")
        val repo = MiniAppRepository(root, InMemoryMiniAppIndex(), clock = { now++ })

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val first = repo.save(
            name = "Tips",
            html = good,
            description = "Split bills",
            themeColor = "#2563eb",
            iconUpdate = MiniAppIconUpdate.PngBytes(png, """<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/></svg>"""),
        )
        assertEquals(1, first.version)
        assertTrue(first.allowNetwork)
        assertEquals("#2563EB", first.themeColor)
        assertEquals(ICON_URI_FILE, first.iconUri)
        assertEquals(MINI_APP_DEFAULT_ENTRY_POINT, first.entryPoint)
        assertEquals(good, repo.html(first.id))

        val appDir = File(root, first.id)
        assertTrue(File(appDir, MINI_APP_MANIFEST_FILE_NAME).isFile)
        assertTrue(File(appDir, MINI_APP_ICON_FILE_NAME).isFile)
        assertTrue(File(appDir, MINI_APP_DEFAULT_ENTRY_POINT).isFile)
        assertArrayEquals(png, File(appDir, MINI_APP_ICON_FILE_NAME).readBytes())

        // Updating under same id preserves id, createdAt, and icon when iconUpdate is Keep.
        val second = repo.save(
            name = "Tips Pro",
            html = good.replace("18", "20"),
            description = "Split bills fast",
            id = first.id,
        )
        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals(2, second.version)
        assertEquals(ICON_URI_FILE, second.iconUri)
        assertTrue(repo.html(first.id)!!.contains("20"))
        assertEquals(first.id, repo.find("tips pro")?.id)

        // Updating metadata changes icon to emoji and removes stale icon.png.
        val updatedMeta = repo.updateMetadata(first.id, "Tip Split", "#16a34a", MiniAppIconUpdate.Emoji("💰"))
        assertEquals("Tip Split", updatedMeta.name)
        assertEquals("#16A34A", updatedMeta.themeColor)
        assertEquals("emoji:💰", updatedMeta.iconUri)
        assertFalse(File(appDir, MINI_APP_ICON_FILE_NAME).exists())

        val shared = repo.save("Shared", good, imported = true)
        assertFalse(shared.allowNetwork)
        assertEquals(listOf(shared.id, first.id), repo.list().map { it.id })

        repo.delete(first.id)
        assertNull(repo.html(first.id))
        assertFalse(appDir.exists())
        assertEquals(listOf(shared.id), repo.list().map { it.id })
        assertNull(repo.html("../index"))
    }

    @Test
    fun repositoryReconcilesManifestsFromDiskWhenIndexIsEmpty() {
        val root = folder.newFolder("reconcile")
        val repo1 = MiniAppRepository(root, InMemoryMiniAppIndex())
        val saved = repo1.save("Habits", good, themeColor = "#7C3AED")

        val repo2 = MiniAppRepository(root, InMemoryMiniAppIndex())
        val list = repo2.list()
        assertEquals(1, list.size)
        assertEquals(saved.id, list.single().id)
        assertEquals("Habits", list.single().name)
        assertEquals("#7C3AED", list.single().themeColor)
    }

    @Test
    fun toolCallsSaveReportProblemsAndProtectSharedApps() {
        val saved = mutableListOf<MiniApp>()
        val repo = MiniAppRepository(folder.newFolder("tools"), InMemoryMiniAppIndex())
        val tools = MiniAppTools(repo) { saved += it }
        val rejected = tools.handle(JSONObject().put("type", "save").put("name", "X").put("html", "<p>hi</p>"))
        assertFalse(rejected.getBoolean("ok"))
        assertTrue(rejected.getJSONArray("problems").length() > 0)
        assertTrue(saved.isEmpty())

        val ok = tools.handle(JSONObject().put("type", "save").put("html", good).put("themeColor", "#db2777"))
        assertTrue(ok.getBoolean("ok"))
        assertEquals("Tip calculator", ok.getString("name"))
        assertEquals(1, saved.size)
        assertEquals("#DB2777", saved.single().themeColor)
        val read = tools.handle(JSONObject().put("type", "read").put("app", ok.getString("id")))
        assertEquals(good, read.getString("html"))
        assertEquals(1, tools.handle(JSONObject().put("type", "list")).getJSONArray("apps").length())

        val shared = repo.save("Gift", good, imported = true)
        val update = tools.handle(JSONObject().put("type", "save").put("id", shared.id).put("name", "Gift").put("html", good))
        assertFalse(update.getBoolean("ok"))
        assertTrue(
            tools.handle(JSONObject().put("type", "save").put("id", "nope1234").put("name", "A").put("html", good))
                .getString("error").contains("No mini app"),
        )
    }

    @Test
    fun sanitizerDefendsAgainstBadColorsMissingViewportAndUntrustedSvgs() {
        assertEquals("#2563EB", MiniAppSanitizer.sanitizeThemeColor("not-a-color"))
        assertEquals("#2563EB", MiniAppSanitizer.sanitizeThemeColor("#12345"))
        assertEquals("#AABBCC", MiniAppSanitizer.sanitizeThemeColor("#abc"))
        assertEquals("#0D9488", MiniAppSanitizer.sanitizeThemeColor("0d9488"))

        val noViewport = "<!DOCTYPE html><html><head><title>Plain</title></head><body>Hi</body></html>"
        val fixed = MiniAppSanitizer.prepareHtmlForBundle(noViewport, "Plain")
        assertTrue(fixed.contains("name=\"viewport\""))
        assertTrue(fixed.contains("charset=\"utf-8\""))
        // Should not duplicate viewport when already present.
        val fixedAgain = MiniAppSanitizer.ensureViewport(fixed)
        assertEquals(fixed, fixedAgain)

        val fragment = "<div>Hello</div>"
        val wrapped = MiniAppSanitizer.prepareHtmlForBundle(fragment, "Hello <App>")
        assertTrue(wrapped.contains("<!DOCTYPE html><html>"))
        assertTrue(wrapped.contains("<title>Hello &lt;App&gt;</title>"))
        assertTrue(wrapped.contains("name=\"viewport\""))

        val safeSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><defs><linearGradient id="g"/></defs><path fill="url(#g)" d="M4 4h16v16H4z"/></svg>"""
        assertNotNull(MiniAppSanitizer.sanitizeSvg(safeSvg))

        // SVG missing xmlns gets standard SVG namespace injected automatically.
        val noXmlns = MiniAppSanitizer.sanitizeSvg("""<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/></svg>""")
        assertNotNull(noXmlns)
        assertTrue(noXmlns!!.contains("xmlns=\"http://www.w3.org/2000/svg\""))

        // Empty SVGs with no drawable primitives are rejected so renderer falls back to initial.
        assertNull(MiniAppSanitizer.sanitizeSvg("""<svg viewBox="0 0 24 24"></svg>"""))
        assertNull(MiniAppSanitizer.sanitizeSvg("""<svg viewBox="0 0 24 24"><script>alert(1)</script></svg>"""))
        assertNull(MiniAppSanitizer.sanitizeSvg("""<svg onload="alert(1)" viewBox="0 0 24 24"><circle r="5"/></svg>"""))
        assertNull(MiniAppSanitizer.sanitizeSvg("""<svg viewBox="0 0 24 24"><foreignObject><div>x</div></foreignObject></svg>"""))
        assertNull(MiniAppSanitizer.sanitizeSvg("""<svg viewBox="0 0 24 24"><image href="https://evil.test/x.png"/></svg>"""))
        assertNull(MiniAppSanitizer.sanitizeSvg("""<svg viewBox="0 0 24 24"><feImage href="https://evil.test/x.png"/></svg>"""))
        assertNull(MiniAppSanitizer.sanitizeSvg("""<svg viewBox="0 0 24 24"><?xml-stylesheet href="evil.css"?><circle r="5"/></svg>"""))
        assertNull(MiniAppSanitizer.sanitizeSvg("""<svg viewBox="0 0 24 24"><a xlink:href="javascript:alert(1)"><rect/></a></svg>"""))
        assertNull(MiniAppSanitizer.sanitizeSvg("""<svg viewBox="0 0 24 24"><path style="fill:url(https://evil.test/a)"/></svg>"""))
        assertNull(MiniAppSanitizer.sanitizeSvg("""<!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><svg>&xxe;</svg>"""))

        // UTF-8 BOM, missing doctype, and <head> inside a <script> string must not corrupt JS.
        val trickyHtml = "\uFEFF<html><body><script>const s = '<head>';</script><h1>Hi</h1></body></html>"
        val prepared = MiniAppSanitizer.prepareHtmlForBundle(trickyHtml, "Tricky")
        assertFalse(prepared.startsWith("\uFEFF"))
        assertTrue(prepared.startsWith("<!DOCTYPE html>"))
        assertTrue(prepared.contains("const s = '<head>';"))
        assertTrue(prepared.indexOf("name=\"viewport\"") < prepared.indexOf("<body>"))
    }

    @Test
    fun prepareHtmlStripsOuterMarkdownFencesAndMetaRefreshWhilePreservingInnerCodeFences() {
        val fencedHtml = "```html\n<!DOCTYPE html><html><head><title>Notes</title></head>" +
            "<body><pre>```js\nconsole.log(1);\n```</pre></body></html>\n```"
        val cleaned = MiniAppSanitizer.prepareHtmlForBundle(fencedHtml, "Notes")
        assertTrue(cleaned.startsWith("<!DOCTYPE html>"))
        assertFalse(cleaned.startsWith("```"))
        assertFalse(cleaned.endsWith("```"))
        assertTrue(cleaned.contains("<pre>```js\nconsole.log(1);\n```</pre>"))

        val genericFencedFragment = "```\n<div>Hello</div>\n```"
        val cleanedFragment = MiniAppSanitizer.prepareHtmlForBundle(genericFencedFragment, "Hello")
        assertFalse(cleanedFragment.contains("```"))
        assertTrue(cleanedFragment.contains("<body><div>Hello</div></body>"))

        val singleLineFenced = "```html <p>Inline</p> ```"
        val cleanedSingle = MiniAppSanitizer.prepareHtmlForBundle(singleLineFenced, "Inline")
        assertFalse(cleanedSingle.contains("```"))
        assertTrue(cleanedSingle.contains("<p>Inline</p>"))

        // Non-alphanumeric info string, extra closing backticks, CRLF, and closing fence right after </html>.
        val mimeFenced = "```text/html\r\n<!DOCTYPE html><html><head><title>Mime</title></head><body>OK</body></html>\r\n````"
        val cleanedMime = MiniAppSanitizer.prepareHtmlForBundle(mimeFenced, "Mime")
        assertTrue(cleanedMime.startsWith("<!DOCTYPE html>"))
        assertFalse(cleanedMime.contains("text/html"))
        assertFalse(cleanedMime.contains("```"))

        val inlineCloseFenced = "```html title=\"app.html\"\n<!DOCTYPE html><html><body>Tight</body></html>```"
        val cleanedTight = MiniAppSanitizer.prepareHtmlForBundle(inlineCloseFenced, "Tight")
        assertTrue(cleanedTight.startsWith("<!DOCTYPE html>"))
        assertFalse(cleanedTight.contains("app.html"))
        assertFalse(cleanedTight.contains("```"))

        assertTrue(runCatching { MiniAppSanitizer.prepareHtmlForBundle("```html\n```", "Empty") }.isFailure)
        assertTrue(runCatching { MiniAppSanitizer.prepareHtmlForBundle("```html\n````", "Empty4") }.isFailure)

        // Strip meta refresh variants (reordered attrs, quotes, uppercase, '>' inside quoted value, HTML entities, spaces).
        val redirectHtml = """<!DOCTYPE html><html><head>
            <meta charset="utf-8">
            <meta http-equiv="refresh" content="0;url=https://evil.test?a=1>2">
            <META content='5; URL=https://evil.test' HTTP-EQUIV='REFRESH' />
            <meta http-equiv=refresh content=0;url=https://evil.test>
            <meta http-equiv=" refresh " content="0;url=https://evil.test">
            <meta http-equiv="&#114;efresh" content="0;url=https://evil.test">
            <meta http-equiv="&#x52;efresh" content="0;url=https://evil.test">
            <meta http-equiv="&#114&#101&#102&#114&#101&#115&#104" content="0;url=https://evil.test">
            <title>Safe</title>
            </head><body><h1>Safe</h1></body></html>"""
        assertTrue(MiniAppChecks.problems(redirectHtml).any { it.contains("meta refresh") })
        val spacedOnly = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width"><title>S</title>
            <meta http-equiv=" refresh " content="0;url=https://evil.test"></head><body>x</body></html>"""
        assertTrue(MiniAppChecks.problems(spacedOnly).any { it.contains("meta refresh") })
        val entityOnly = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width"><title>E</title>
            <meta http-equiv="&#114;efresh" content="0;url=https://evil.test"></head><body>x</body></html>"""
        assertTrue(MiniAppChecks.problems(entityOnly).any { it.contains("meta refresh") })
        val noRefresh = MiniAppSanitizer.prepareHtmlForBundle(redirectHtml, "Safe")
        assertFalse(noRefresh.contains("refresh", ignoreCase = true))
        assertFalse(noRefresh.contains("evil.test", ignoreCase = true))
        assertTrue(noRefresh.contains("charset=\"utf-8\""))
        assertTrue(noRefresh.contains("name=\"viewport\""))
        assertEquals(emptyList<String>(), MiniAppChecks.problems(noRefresh))
    }

    @Test
    fun payloadParserHandlesStrictJsonMarkdownFencesMissingOptionalMetadataAndRejectsEmptyHtml() {
        val strict = """{"name":"Pomodoro","themeColor":"#dc2626","iconSvg":"<svg viewBox='0 0 24 24'><circle cx='12' cy='12' r='8'/></svg>","htmlCode":"<h1>Hi</h1>"}"""
        val p1 = MiniAppSanitizer.parseGeneratedPayload(strict)
        assertEquals("Pomodoro", p1.name)

        val fenced = "Here is your app:\n```json\n$strict\n```"
        val p2 = MiniAppSanitizer.parseGeneratedPayload(fenced)
        assertEquals("#dc2626", p2.themeColor)

        // Fallback models that omit iconSvg or return null metadata still parse cleanly.
        val sparse = """{"name":"Quick Timer","themeColor":null,"iconSvg":null,"htmlCode":"<h1>Timer</h1>"}"""
        val p3 = MiniAppSanitizer.parseGeneratedPayload(sparse)
        val sanitizedSparse = MiniAppSanitizer.sanitizeGeneratedPayload(p3)
        assertEquals("Quick Timer", sanitizedSparse.name)
        assertEquals(MINI_APP_DEFAULT_THEME_COLOR, sanitizedSparse.themeColor)
        assertNull(sanitizedSparse.iconSvg)

        // AI-generated names are capped at 24 characters (including fallback title).
        val longPayload = GeneratedMiniAppPayload(
            name = "Super Ultra Comprehensive Daily Habit Tracker Pro",
            themeColor = "#2563EB",
            iconSvg = "",
            htmlCode = "```html\n<html><body>Hi</body></html>\n```",
        )
        val sanitizedLong = MiniAppSanitizer.sanitizeGeneratedPayload(longPayload)
        assertTrue("Expected <= 24 chars, got '${sanitizedLong.name}' (${sanitizedLong.name.length})", sanitizedLong.name.length <= 24)
        assertEquals("Super Ultra Comprehensiv", sanitizedLong.name)
        assertFalse(sanitizedLong.htmlCode.contains("```"))

        val blankNameLongTitle = GeneratedMiniAppPayload(
            name = "   ",
            themeColor = "#2563EB",
            iconSvg = "",
            htmlCode = "<html><head><title>Extremely Long Fallback Title For Mini App</title></head><body>Hi</body></html>",
        )
        val sanitizedFallback = MiniAppSanitizer.sanitizeGeneratedPayload(blankNameLongTitle)
        assertTrue(sanitizedFallback.name.length <= 24)
        assertEquals("Extremely Long Fallback", sanitizedFallback.name)

        val emptyHtml = """{"name":"Bad","themeColor":"#000","iconSvg":"","htmlCode":"   "}"""
        val failed = runCatching { MiniAppSanitizer.parseGeneratedPayload(emptyHtml) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun miniAppArchiveExportAndImportRoundTripsAndBlocksZipSlip() {
        val repo = MiniAppRepository(folder.newFolder("bundles"), InMemoryMiniAppIndex())
        val iconBytes = byteArrayOf(1, 2, 3, 4, 5)
        val original = repo.save(
            name = "Habit Tracker",
            html = good,
            description = "Track streaks",
            themeColor = "#16A34A",
            iconUpdate = MiniAppIconUpdate.PngBytes(iconBytes),
            extraAssets = mapOf("assets/extra.txt" to "hello".toByteArray()),
        )

        val archive = File(folder.newFolder("exports"), "Habit Tracker.miniapp")
        repo.exportBundle(original.id, archive)
        assertTrue(archive.isFile && archive.length() > 0)

        val preview = repo.inspectImport(archive.readBytes(), archive.name)
        assertTrue(preview is MiniAppImportPreview.Bundle)
        val imported = repo.installImport(preview)
        assertNotEquals(original.id, imported.id)
        assertEquals("Habit Tracker", imported.name)
        assertEquals("#16A34A", imported.themeColor)
        assertTrue(imported.imported)
        assertFalse(imported.allowNetwork)
        assertArrayEquals(iconBytes, repo.iconFile(imported.id)!!.readBytes())
        assertEquals("hello", File(repo.appDir(imported.id), "assets/extra.txt").readText())

        // Raw HTML import strips UTF-8 BOM, strips uppercase .HTML suffix, and injects viewport.
        val rawHtml = "\uFEFF<html><body><h1>Notes</h1></body></html>"
        val rawPreview = repo.inspectImport(rawHtml.toByteArray(), "Quick_Notes.HTML")
        assertTrue(rawPreview is MiniAppImportPreview.RawHtml)
        assertEquals("Quick Notes", (rawPreview as MiniAppImportPreview.RawHtml).suggestedName)
        val installedRaw = repo.installImport(rawPreview, overrideName = "Quick Notes", overrideThemeColor = "#EA580C", iconUpdate = MiniAppIconUpdate.Emoji("📝"))
        assertEquals("Quick Notes", installedRaw.name)
        assertEquals("emoji:📝", installedRaw.iconUri)
        assertTrue(repo.html(installedRaw.id)!!.contains("name=\"viewport\""))

        // Zip Slip and URL-unsafe (? / #) archive paths must be rejected.
        val maliciousZip = zipOf("../../evil.html" to "<html></html>".toByteArray())
        assertTrue(runCatching { repo.inspectImport(maliciousZip, "evil.miniapp") }.isFailure)
        val queryZip = zipOf(
            "index.html" to "<html><body>ok</body></html>".toByteArray(),
            "bad?name.txt" to "nope".toByteArray(),
        )
        assertTrue(runCatching { repo.inspectImport(queryZip, "query.miniapp") }.isFailure)

        // Extra assets with invalid paths never clobber index.html when entryPoint is custom.
        val customEntryApp = repo.save(
            name = "Custom Entry",
            html = good,
            entryPoint = "app/main.html",
            extraAssets = mapOf("bad?asset.txt" to "corrupt".toByteArray()),
        )
        assertFalse(File(repo.appDir(customEntryApp.id), "index.html").exists())
        assertTrue(File(repo.appDir(customEntryApp.id), "app/main.html").isFile)

        // Binary garbage must be rejected.
        val binary = byteArrayOf(0, 1, 2, 0, 4, 5)
        assertTrue(runCatching { repo.inspectImport(binary, "photo.png") }.isFailure)

        // macOS ZIP with __MACOSX metadata and wrapper folder still unwraps manifest.json cleanly.
        val macOsZip = zipOf(
            "__MACOSX/MyBundle/._index.html" to byteArrayOf(0, 5, 22, 7),
            "MyBundle/.DS_Store" to byteArrayOf(0, 0, 0, 1),
            "MyBundle/manifest.json" to """{"id":"550e8400-e29b-41d4-a716-446655440000","name":"Mac Bundle","themeColor":"#7C3AED","createdAt":1}""".toByteArray(),
            "MyBundle/index.html" to "<html><head><title>Ignored</title></head><body>ok</body></html>".toByteArray(),
        )
        val macPreview = repo.inspectImport(macOsZip, "mac.miniapp")
        assertTrue(macPreview is MiniAppImportPreview.Bundle)
        val macBundle = macPreview as MiniAppImportPreview.Bundle
        assertEquals("Mac Bundle", macBundle.suggestedName)
        assertEquals("#7C3AED", macBundle.themeColor)
    }

    @Test
    fun toolUpdatesRerenderSvgIconOnThemeColorChangeAndEmojisKeepGraphemeClusters() {
        val renderedColors = mutableListOf<String>()
        val repo = MiniAppRepository(folder.newFolder("tool_color"), InMemoryMiniAppIndex())
        val svg = """<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/></svg>"""
        val tools = MiniAppTools(
            repository = repo,
            renderIcon = { _, color, _ ->
                renderedColors += color
                color.toByteArray()
            },
        )
        val created = tools.handle(
            JSONObject().put("type", "save").put("name", "Timer").put("html", good)
                .put("themeColor", "#2563EB").put("iconSvg", svg),
        )
        assertTrue(created.getBoolean("ok"))
        val appId = created.getString("id")
        assertEquals(listOf("#2563EB"), renderedColors)

        // Changing themeColor without re-sending iconSvg still re-renders the stored SVG icon.
        val updated = tools.handle(
            JSONObject().put("type", "save").put("id", appId).put("name", "Timer")
                .put("html", good).put("themeColor", "#16A34A"),
        )
        assertTrue(updated.getBoolean("ok"))
        assertEquals(listOf("#2563EB", "#16A34A"), renderedColors)
        assertEquals("#16A34A", repo.iconFile(appId)!!.readText())

        // Multi-codepoint ZWJ emoji (11 UTF-16 chars) is preserved whole without breaking surrogates.
        val familyEmoji = "👨‍👩‍👧‍👦"
        assertEquals(familyEmoji, sanitizeEmojiText(familyEmoji))
        assertEquals("emoji:$familyEmoji", emojiIconUri(familyEmoji))

        // Offline CSP allows local data: and blob: fetch while blocking network.
        assertTrue(MiniAppChecks.cspHeader(allowNetwork = false).contains("connect-src 'self' data: blob:"))

        // Updating an app with a custom uploaded photo via chat tools preserves the uploaded photo.
        val userPhoto = byteArrayOf(7, 7, 7, 7)
        val photoApp = repo.save(
            name = "Photo App",
            html = good,
            themeColor = "#2563EB",
            iconUpdate = MiniAppIconUpdate.PngBytes(userPhoto, iconSvg = null, aiGenerated = false),
        )
        val photoUpdate = tools.handle(
            JSONObject().put("type", "save").put("id", photoApp.id).put("name", "Photo App")
                .put("html", good).put("themeColor", "#DC2626").put("iconSvg", svg),
        )
        assertTrue(photoUpdate.getBoolean("ok"))
        assertArrayEquals(userPhoto, repo.iconFile(photoApp.id)!!.readBytes())
        assertEquals(listOf("#2563EB", "#16A34A"), renderedColors)

        // Updating an AI fallback icon app with a new initial letter regenerates its fallback PNG.
        val fallbackApp = repo.save(
            name = "Water Log",
            html = good,
            themeColor = "#2563EB",
            iconUpdate = MiniAppIconUpdate.PngBytes("W:#2563EB".toByteArray(), iconSvg = null, aiGenerated = true),
        )
        val fallbackRename = tools.handle(
            JSONObject().put("type", "save").put("id", fallbackApp.id).put("name", "Hydration")
                .put("html", good).put("themeColor", "#2563EB"),
        )
        assertTrue(fallbackRename.getBoolean("ok"))
        assertEquals(listOf("#2563EB", "#16A34A", "#2563EB"), renderedColors)

        // Monotonic updatedAt even when clock returns the exact same millisecond across rapid updates.
        val fixedClockRepo = MiniAppRepository(folder.newFolder("fixed_clock"), InMemoryMiniAppIndex(), clock = { 5000L })
        val v1 = fixedClockRepo.save("Pulse", good)
        val v2 = fixedClockRepo.updateMetadata(v1.id, "Pulse Pro", "#16A34A")
        val v3 = fixedClockRepo.save("Pulse Max", good, id = v1.id)
        assertTrue(v2.updatedAt > v1.updatedAt)
        assertTrue(v3.updatedAt > v2.updatedAt)
    }

    @Test
    fun replyPreviewsAreShortPlainText() {
        assertEquals("Done. Saved the file.", replyPreview("## Done.\n\n**Saved** the file."))
        assertEquals("The agent finished.", replyPreview("   "))
        assertTrue(replyPreview("a".repeat(500)).length <= 240)
        assertEquals("See the docs for my_file.py.", replyPreview("> See [the docs](https://x.dev) for `my_file.py`."))
        assertEquals("One Two", replyPreview("- One\n* Two"))
        // An emoji straddling the cut is dropped whole instead of leaving half a surrogate pair.
        val cut = replyPreview("abc\uD83D\uDE00def", max = 5)
        assertEquals("abc…", cut)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zos ->
                for ((name, bytes) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            out.toByteArray()
        }
}
