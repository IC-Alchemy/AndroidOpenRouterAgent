package com.example.openrouteragent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
    fun theStoreSavesNewVersionsUnderTheSameIdAndDeletes() {
        var now = 1L
        val store = MiniAppStore(folder.newFolder("apps"), clock = { now++ })
        val first = store.save("Tips", "Split bills", good)
        assertEquals(1, first.version)
        assertTrue(first.allowNetwork)
        assertEquals(good, store.html(first.id))
        val second = store.save("Tips", "Split bills fast", good.replace("18", "20"), id = first.id)
        assertEquals(first.id, second.id)
        assertEquals(2, second.version)
        assertTrue(store.html(first.id)!!.contains("20"))
        assertEquals(first.id, store.find("tips")?.id)
        val shared = store.save("Shared", "", good, imported = true)
        assertFalse(shared.allowNetwork)
        assertEquals(listOf(shared.id, first.id), store.list().map { it.id })
        store.delete(first.id)
        assertNull(store.html(first.id))
        assertEquals(listOf(shared.id), store.list().map { it.id })
        assertNull(store.html("../index"))
    }

    @Test
    fun toolCallsSaveReportProblemsAndProtectSharedApps() {
        val saved = mutableListOf<MiniApp>()
        val store = MiniAppStore(folder.newFolder("tools"))
        val tools = MiniAppTools(store) { saved += it }
        val rejected = tools.handle(JSONObject().put("type", "save").put("name", "X").put("html", "<p>hi</p>"))
        assertFalse(rejected.getBoolean("ok"))
        assertTrue(rejected.getJSONArray("problems").length() > 0)
        assertTrue(saved.isEmpty())

        val ok = tools.handle(JSONObject().put("type", "save").put("html", good))
        assertTrue(ok.getBoolean("ok"))
        assertEquals("Tip calculator", ok.getString("name"))
        assertEquals(1, saved.size)
        val read = tools.handle(JSONObject().put("type", "read").put("app", ok.getString("id")))
        assertEquals(good, read.getString("html"))
        assertEquals(1, tools.handle(JSONObject().put("type", "list")).getJSONArray("apps").length())

        val shared = store.save("Gift", "", good, imported = true)
        val update = tools.handle(JSONObject().put("type", "save").put("id", shared.id).put("name", "Gift").put("html", good))
        assertFalse(update.getBoolean("ok"))
        assertTrue(tools.handle(JSONObject().put("type", "save").put("id", "nope1234").put("name", "A").put("html", good))
            .getString("error").contains("No mini app"))
    }

    @Test
    fun replyPreviewsAreShortPlainText() {
        assertEquals("Done. Saved the file.", replyPreview("## Done.\n\n**Saved** the file."))
        assertEquals("The agent finished.", replyPreview("   "))
        assertTrue(replyPreview("a".repeat(500)).length <= 240)
    }
}
