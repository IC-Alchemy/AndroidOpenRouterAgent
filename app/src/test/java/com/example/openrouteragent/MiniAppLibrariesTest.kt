package com.example.openrouteragent

import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

class MiniAppLibrariesTest {
    @get:Rule val folder = TemporaryFolder()

    private val chart = "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"
    private val css = "https://cdnjs.cloudflare.com/ajax/libs/pico/2.0.6/pico.min.css"
    private val code = "window.Chart = function () {}; var s = '</script><!--';"

    private fun page(head: String) = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
        <title>Chart</title>$head</head><body><script>new Chart()</script></body></html>"""

    private class Fake(val files: Map<String, String>) : LibraryFetcher {
        val calls = mutableListOf<String>()
        override fun fetch(url: HttpUrl): ByteArray {
            calls += url.toString()
            return files[url.toString()]?.toByteArray() ?: throw IOException("$url could not be downloaded (HTTP 404).")
        }
    }

    private fun sri(text: String) =
        "sha384-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-384").digest(text.toByteArray()))

    @Test
    fun cdnScriptsAndStylesheetsAreInlinedAndPassTheChecks() {
        val fake = Fake(mapOf(chart to code, css to "body{color:red}</style>"))
        val source = page("""<script src="$chart" integrity="${sri(code)}" crossorigin="anonymous" defer></script>
            <link rel="stylesheet" href="$css" media="screen">""")
        val result = MiniAppLibraries(null, fake).inline(source)
        assertEquals(emptyList<String>(), result.problems)
        assertEquals(listOf(chart, css), result.inlined)
        assertEquals(emptyList<String>(), MiniAppChecks.problems(result.html))
        assertTrue(result.html.contains("window.Chart = function () {}"))
        // The library cannot close its own tag early.
        assertTrue(result.html.contains("'<\\/script><\\!--'"))
        assertTrue(result.html.contains("body{color:red}<\\/style>"))
        assertFalse(result.html.contains("crossorigin"))
        assertTrue(result.html.contains("media=\"screen\""))
    }

    @Test
    fun collapsingGivesTheAgentBackTheShortTags() {
        val fake = Fake(mapOf(chart to code, css to "body{}"))
        val source = page("""<script src="$chart" integrity="${sri(code)}"></script><link rel="stylesheet" href="$css">""")
        val saved = MiniAppLibraries(null, fake).inline(source).html
        val short = MiniAppLibraries.collapse(saved)
        assertFalse(short.contains("window.Chart"))
        assertTrue(short.contains("""<script src="$chart" integrity="${sri(code)}"></script>"""))
        assertTrue(short.contains("""<link rel="stylesheet" href="$css">"""))
        // Saving the collapsed copy again gives the same file.
        assertEquals(saved, MiniAppLibraries(null, fake).inline(short).html)
    }

    @Test
    fun otherHostsBadHashesAndFailedDownloadsAreReported() {
        val fake = Fake(mapOf(chart to code))
        val problems = MiniAppLibraries(null, fake).inline(page("""
            <script src="https://evil.example.com/x.js"></script>
            <script src="http://cdn.jsdelivr.net/npm/a@1/a.js"></script>
            <script src="$chart" integrity="sha384-AAAA"></script>
            <script src="https://unpkg.com/missing@1.0.0/x.js"></script>""")).problems.joinToString("\n")
        assertTrue(problems, problems.contains("evil.example.com/x.js yourself"))
        assertTrue(problems, problems.contains("http://cdn.jsdelivr.net/npm/a@1/a.js yourself"))
        assertTrue(problems, problems.contains("integrity hash"))
        assertTrue(problems, problems.contains("HTTP 404"))
    }

    @Test
    fun downloadsAreCachedAndACachedCopyIsCheckedAgainstTheHash() {
        val cache = folder.newFolder("cache")
        val fake = Fake(mapOf(chart to code))
        val source = page("""<script src="$chart"></script>""")
        MiniAppLibraries(cache, fake).inline(source)
        MiniAppLibraries(cache, fake).inline(source)
        assertEquals(1, fake.calls.size)
        cache.listFiles()!!.single().writeText("tampered")
        val result = MiniAppLibraries(cache, fake).inline(page("""<script src="$chart" integrity="${sri(code)}"></script>"""))
        assertEquals(emptyList<String>(), result.problems)
        assertEquals(2, fake.calls.size)
        assertTrue(result.html.contains("window.Chart"))
    }

    @Test
    fun theSaveToolInlinesLibrariesAndReadShowsTheShortForm() {
        val store = MiniAppRepository(folder.newFolder("apps"), InMemoryMiniAppIndex())
        val tools = MiniAppTools(store, MiniAppLibraries(null, Fake(mapOf(chart to code))))
        val saved = tools.handle(JSONObject().put("type", "save").put("html", page("""<script src="$chart"></script>""")))
        assertTrue(saved.toString(), saved.getBoolean("ok"))
        assertEquals(chart, saved.getJSONArray("inlined").getString(0))
        assertTrue(store.html(saved.getString("id"))!!.contains("window.Chart"))
        val read = tools.handle(JSONObject().put("type", "read").put("app", saved.getString("id")))
        assertFalse(read.getString("html").contains("window.Chart"))
        assertTrue(read.has("note"))

        val failed = tools.handle(JSONObject().put("type", "save")
            .put("html", page("""<script src="https://unpkg.com/nope@1.0.0/x.js"></script>""")))
        assertFalse(failed.getBoolean("ok"))
        assertTrue(failed.getJSONArray("problems").toString().contains("HTTP 404"))
        val tooLong = tools.handle(JSONObject().put("type", "save").put("html", page("x".repeat(MINI_APP_SOURCE_MAX_CHARS))))
        assertTrue(tooLong.getString("error").contains("CDN"))
    }

    @Test
    fun thePolicyIsAddedBeforeTheAppsOwnCode() {
        val html = page("<script>1</script>")
        val secured = MiniAppChecks.withPolicy(html)
        val policy = secured.indexOf("Content-Security-Policy")
        assertTrue(policy in 0 until secured.indexOf("<script>"))
        assertTrue(secured.startsWith("<!doctype html><html><head><meta http-equiv"))
        assertTrue(MiniAppChecks.withPolicy("<p>x</p>").startsWith("<meta http-equiv"))
        assertTrue(MiniAppChecks.withPolicy("<html lang=\"en\"><header></header></html>")
            .startsWith("<html lang=\"en\"><meta http-equiv"))
    }

    @Test
    fun theFetcherReadsSuccessfulFilesAndRefusesOthers() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok();"))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("x".repeat(MINI_APP_LIBRARY_MAX_BYTES + 1)))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/x.js"))
            val fetcher = OkHttpLibraryFetcher()
            val url = server.url("/lib.js")
            assertEquals("ok();", fetcher.fetch(url).toString(Charsets.UTF_8))
            listOf("HTTP 404", "larger than", "redirected").forEach { expected ->
                val message = try {
                    fetcher.fetch(url)
                    ""
                } catch (e: IOException) {
                    e.message.orEmpty()
                }
                assertTrue(message, message.contains(expected))
            }
        }
    }
}
