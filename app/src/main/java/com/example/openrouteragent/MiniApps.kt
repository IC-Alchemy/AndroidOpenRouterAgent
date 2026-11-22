package com.example.openrouteragent

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/*
 * Mini apps are small single-file HTML apps that the agent builds on request. Each one runs in its own WebView origin
 * with no app bridge, no file access and no other scripts, can be pinned to the home screen, and can be shared as one
 * .html file. Libraries the agent names on a public CDN are written into the file when it is saved. Apps received
 * from someone else never get network access.
 */

/** The largest saved app, including inlined libraries. */
const val MINI_APP_MAX_CHARS = 6_000_000

/** The largest HTML the agent may write itself. Libraries it links are inlined on top of this. */
const val MINI_APP_SOURCE_MAX_CHARS = 400_000

@Serializable
data class MiniApp(
    val id: String,
    val name: String,
    val description: String = "",
    val allowNetwork: Boolean = true,
    val imported: Boolean = false,
    val version: Int = 1,
    val updatedAt: Long = 0,
)

object MiniAppChecks {
    private fun has(html: String, pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .containsMatchIn(html)

    fun title(html: String): String? = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE,
        RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** Problems that stop an app from being saved. The agent gets them back and can fix the file. */
    fun problems(html: String): List<String> = buildList {
        if (html.length > MINI_APP_MAX_CHARS) add("The file is larger than ${MINI_APP_MAX_CHARS / 1_000_000} MB.")
        if (!has(html, "<html[\\s>]") || !has(html, "</html>")) add("Wrap the app in <html> … </html>.")
        if (title(html) == null) add("Add a non-empty <title>.")
        if (!has(html, "<meta[^>]+name=[\"']?viewport")) add("Add a viewport meta tag for phones.")
        if (has(html, "<script[^>]+\\bsrc\\s*=")) add("Inline all JavaScript; external <script src> files are not loaded. " +
            "An empty <script src> tag for a library on ${MINI_APP_LIBRARY_HOSTS.joinToString()} is inlined when saved.")
        if (has(html, "<link[^>]+\\bhref\\s*=\\s*[\"']?(https?:)?//")) add("Inline all CSS and fonts; external <link> files are not loaded. " +
            "A stylesheet <link> on ${MINI_APP_LIBRARY_HOSTS.joinToString()} is inlined when saved.")
        if (has(html, "@import\\s+(url\\()?[\"']?(https?:)?//")) add("Remove CSS @import of external files.")
        if (has(html, "<(iframe|frame|frameset|object|embed)[\\s>]")) add("Remove iframes, frames, objects and embeds.")
        if (has(html, "<form[^>]+\\baction\\s*=")) add("Handle forms in JavaScript instead of a form action.")
        if (has(html, "http-equiv\\s*=\\s*[\"']?refresh")) add("Remove the meta refresh.")
        if (has(html, "<base[\\s>]")) add("Remove the <base> tag.")
    }

    /**
     * Blocks scripts, styles, fonts and frames from anywhere but the page itself, even when the app's own code adds a
     * <script src> tag at runtime. fetch and images still follow the app's network setting.
     */
    const val POLICY = "script-src 'unsafe-inline' 'unsafe-eval' blob: data:; style-src 'unsafe-inline' blob: data:; " +
        "font-src blob: data:; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"

    private val headTag = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)

    /** Adds [POLICY] as the first element of the page, before any of the app's own scripts run. */
    fun withPolicy(html: String): String {
        val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"$POLICY\">"
        val anchor = headTag.find(html) ?: htmlTag.find(html) ?: return meta + html
        return html.substring(0, anchor.range.last + 1) + meta + html.substring(anchor.range.last + 1)
    }
}

/** Stores mini apps under the app's private files. The index and each HTML file are written atomically. */
class MiniAppStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val index get() = File(dir, "index.json")
    private val idPattern = Regex("[a-z0-9]{8,32}")

    @Synchronized
    fun list(): List<MiniApp> = try {
        json.decodeFromString(ListSerializer(MiniApp.serializer()), index.readText())
    } catch (_: FileNotFoundException) {
        emptyList()
    }

    fun find(idOrName: String): MiniApp? = list().let { apps ->
        apps.firstOrNull { it.id == idOrName } ?: apps.firstOrNull { it.name.equals(idOrName.trim(), ignoreCase = true) }
    }

    @Synchronized
    fun html(id: String): String? {
        if (!idPattern.matches(id)) return null
        return try {
            File(dir, "$id.html").readText()
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /** Saves a new app, or a new version of [id], keeping its id so its stored data stays. */
    @Synchronized
    fun save(name: String, description: String, html: String, id: String? = null, imported: Boolean = false): MiniApp {
        val apps = list()
        val existing = id?.let { key -> apps.firstOrNull { it.id == key } }
        require(id == null || existing != null) { "No mini app has the id $id." }
        val app = existing?.copy(name = name, description = description, version = existing.version + 1,
            updatedAt = clock()) ?: MiniApp(UUID.randomUUID().toString().replace("-", "").take(16), name, description,
            allowNetwork = !imported, imported = imported, updatedAt = clock())
        dir.mkdirs()
        write(File(dir, "${app.id}.html"), html)
        writeIndex(apps.filter { it.id != app.id } + app)
        return app
    }

    @Synchronized
    fun delete(id: String) {
        val apps = list()
        if (apps.none { it.id == id }) return
        writeIndex(apps.filter { it.id != id })
        File(dir, "$id.html").delete()
    }

    private fun writeIndex(apps: List<MiniApp>) =
        write(index, json.encodeToString(ListSerializer(MiniApp.serializer()), apps.sortedByDescending { it.updatedAt }))

    /** Writes a temporary file, then renames it over the old one so a crash never leaves half a file. */
    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temp.renameTo(file)) throw java.io.IOException("Android could not save ${file.name}.")
        } finally {
            temp.delete()
        }
    }
}

/** Handles the agent's mini app tool calls. Returns JSON for the model. */
class MiniAppTools(
    private val store: MiniAppStore,
    private val libraries: MiniAppLibraries = MiniAppLibraries(null),
    private val onSaved: (MiniApp) -> Unit = {},
) {
    fun handle(request: JSONObject): JSONObject = try {
        when (request.optString("type")) {
            "list" -> JSONObject().put("ok", true).put("apps", JSONArray(store.list().map {
                JSONObject().put("id", it.id).put("name", it.name).put("description", it.description)
                    .put("version", it.version)
            }))
            "read" -> {
                val app = store.find(request.optString("app")) ?: return fail("No mini app matches that id or name.")
                val html = store.html(app.id).orEmpty()
                val short = MiniAppLibraries.collapse(html)
                JSONObject().put("ok", true).put("id", app.id).put("name", app.name).put("html", short).apply {
                    if (short != html) put("note", "Inlined libraries are shown as their <script src> or <link> " +
                        "tags. Keep those tags as they are; they are inlined again when you save.")
                }
            }
            "save" -> save(request)
            else -> fail("Unknown mini app request.")
        }
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "Invalid mini app request.")
    }

    private fun save(request: JSONObject): JSONObject {
        val source = request.optString("html")
        val name = request.optString("name").trim().ifEmpty { MiniAppChecks.title(source).orEmpty() }.take(40)
        val id = request.optString("id").trim().ifEmpty { null }
        if (source.length > MINI_APP_SOURCE_MAX_CHARS) {
            return fail("The app was not saved. It is larger than ${MINI_APP_SOURCE_MAX_CHARS / 1000} KB; link large " +
                "libraries from a CDN instead of pasting them.")
        }
        val inlined = libraries.inline(source)
        val html = inlined.html
        val problems = (inlined.problems + MiniAppChecks.problems(html)).distinct().toMutableList()
        if (name.isEmpty()) problems += "Give the app a name."
        if (id != null && store.list().firstOrNull { it.id == id }?.imported == true) {
            problems += "Shared apps someone sent you cannot be changed. Save a new app instead."
        }
        if (problems.isNotEmpty()) {
            return fail("The app was not saved. Fix these problems and save again.").put("problems", JSONArray(problems))
        }
        val app = store.save(name, request.optString("description").trim().take(200), html, id)
        onSaved(app)
        return JSONObject().put("ok", true).put("id", app.id).put("name", app.name).put("version", app.version)
            .apply { if (inlined.inlined.isNotEmpty()) put("inlined", JSONArray(inlined.inlined)) }
            .put("result", "Saved. Tell the user to open it from Configure → Mini apps, where they can also add it " +
                "to the home screen or share it.")
    }

    private fun fail(message: String) = JSONObject().put("ok", false).put("error", message)
}
