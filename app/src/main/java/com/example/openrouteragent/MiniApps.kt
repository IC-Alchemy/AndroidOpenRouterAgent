package com.example.openrouteragent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/*
 * Mini apps are structured web app bundles stored privately under filesDir/mini_apps/<uuid>/:
 *   manifest.json  — metadata (id, name, themeColor, iconUri, entryPoint, createdAt, updatedAt, ...)
 *   icon.png       — optional 192x192 PNG launcher icon (rendered from SVG/emoji or picked from gallery)
 *   index.html     — self-contained HTML5 executable document
 *
 * Each mini app runs inside an isolated WebView virtual origin (https://<uuid>.miniapp.local/) via
 * WebViewAssetLoader so localStorage, cookies and IndexedDB never collide across mini apps.
 */

/** The largest saved app, including inlined libraries. */
const val MINI_APP_MAX_CHARS = 6_000_000

/** The largest HTML the agent may write itself. Libraries it links are inlined on top of this. */
const val MINI_APP_SOURCE_MAX_CHARS = 400_000

const val MINI_APP_DIR_NAME = "mini_apps"
const val MINI_APP_MANIFEST_FILE_NAME = "manifest.json"
const val MINI_APP_ICON_FILE_NAME = "icon.png"
const val MINI_APP_DEFAULT_ENTRY_POINT = "index.html"
const val MINI_APP_DEFAULT_THEME_COLOR = "#2563EB"
const val MINI_APP_ICON_SIZE_PX = 192
const val MINI_APP_DOMAIN_SUFFIX = ".miniapp.local"
const val MINI_APP_VIEWPORT_META =
    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">"

fun miniAppHost(id: String): String = "$id$MINI_APP_DOMAIN_SUFFIX"
fun miniAppOrigin(id: String): String = "https://${miniAppHost(id)}"
fun miniAppEntryUrl(app: MiniApp): String = "${miniAppOrigin(app.id)}/${app.entryPoint}"

/** Structured Output payload returned by an LLM when creating or editing a mini app. */
@Serializable
data class GeneratedMiniAppPayload(
    val name: String,
    val themeColor: String,
    val iconSvg: String,
    val htmlCode: String,
)

/** Bundle metadata persisted inside filesDir/mini_apps/<id>/manifest.json. */
@Serializable
data class MiniAppManifest(
    val id: String,
    val name: String,
    val themeColor: String = MINI_APP_DEFAULT_THEME_COLOR,
    val createdAt: Long = 0L,
    val updatedAt: Long = createdAt,
    val iconUri: String? = null,
    val iconSvg: String? = null,
    val entryPoint: String = MINI_APP_DEFAULT_ENTRY_POINT,
    val description: String = "",
    val allowNetwork: Boolean = true,
    val imported: Boolean = false,
    val version: Int = 1,
)

/** Lightweight indexed model read instantaneously from SQLite for the launcher grid. */
@Serializable
data class MiniApp(
    val id: String,
    val name: String,
    val description: String = "",
    val themeColor: String = MINI_APP_DEFAULT_THEME_COLOR,
    val iconUri: String? = null,
    val entryPoint: String = MINI_APP_DEFAULT_ENTRY_POINT,
    val allowNetwork: Boolean = true,
    val imported: Boolean = false,
    val version: Int = 1,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun toManifest(iconSvg: String? = null): MiniAppManifest = MiniAppManifest(
        id = id,
        name = name,
        themeColor = themeColor,
        createdAt = createdAt,
        updatedAt = updatedAt,
        iconUri = iconUri,
        iconSvg = iconSvg,
        entryPoint = entryPoint,
        description = description,
        allowNetwork = allowNetwork,
        imported = imported,
        version = version,
    )
}

internal fun MiniAppManifest.toMiniApp(): MiniApp = MiniApp(
    id = id,
    name = name,
    description = description,
    themeColor = themeColor,
    iconUri = iconUri,
    entryPoint = entryPoint,
    allowNetwork = allowNetwork,
    imported = imported,
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

data class SanitizedMiniAppPayload(
    val name: String,
    val themeColor: String,
    val iconSvg: String?,
    val htmlCode: String,
)

object MiniAppSanitizer {
    private val payloadJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Subdomain-safe ID: lowercase alphanumeric and internal hyphens (covers UUIDs and legacy hex IDs).
    private val validIdPattern = Regex("^[a-z0-9](?:[a-z0-9-]{6,61}[a-z0-9])$")
    private val hex6Pattern = Regex("^#([0-9a-fA-F]{6})$")
    private val hex3Pattern = Regex("^#([0-9a-fA-F]{3})$")
    private val viewportPattern = Regex("<meta[^>]+\\bname\\s*=\\s*[\"']?viewport\\b", RegexOption.IGNORE_CASE)
    private val charsetPattern = Regex("<meta[^>]+\\bcharset\\s*=", RegexOption.IGNORE_CASE)
    private val headTag = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val svgElementPattern = Regex("<svg\\b[\\s\\S]*?</svg\\s*>", RegexOption.IGNORE_CASE)
    private val dangerousSvgTags = Regex(
        "<\\s*(script|foreignobject|iframe|frame|frameset|object|embed|applet|image|meta|link|base)\\b|<!\\s*(doctype|entity)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val svgEventAttr = Regex("\\bon[a-z]{2,}\\s*=", RegexOption.IGNORE_CASE)
    private val svgScriptUri = Regex("(?:java|vb)script\\s*:", RegexOption.IGNORE_CASE)
    private val svgExternalHref = Regex(
        "\\b(?:xlink:)?href\\s*=\\s*(?:\"\\s*(?!#)|'\\s*(?!#)|[^\\s\"'#=>])",
        RegexOption.IGNORE_CASE,
    )
    private val svgExternalUrl = Regex("url\\s*\\(\\s*[\"']?\\s*(?!#)", RegexOption.IGNORE_CASE)
    private val svgCssImport = Regex("@import\\b", RegexOption.IGNORE_CASE)
    private val looksLikeHtmlPattern = Regex(
        "<\\s*(!doctype\\s+html|html|head|body|div|main|section|script|style|canvas|svg|h[1-6]|p|form|button|table)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isValidId(id: String): Boolean = validIdPattern.matches(id)

    fun sanitizeEntryPoint(entryPoint: String?): String {
        val raw = entryPoint?.trim()?.replace('\\', '/').orEmpty()
        if (raw.isEmpty() || raw.startsWith("/") || raw.contains("?") || raw.contains("#")) {
            return MINI_APP_DEFAULT_ENTRY_POINT
        }
        val segments = raw.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) {
            return MINI_APP_DEFAULT_ENTRY_POINT
        }
        return raw
    }

    fun sanitizeName(raw: String?, fallback: String = "Mini App"): String {
        val clean = raw.orEmpty()
            .filterNot(Char::isISOControl)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(40)
        return clean.ifEmpty { fallback.take(40).ifEmpty { "Mini App" } }
    }

    fun sanitizeThemeColor(raw: String?, fallback: String = MINI_APP_DEFAULT_THEME_COLOR): String {
        val candidate = raw?.trim().orEmpty().let { if (it.isNotEmpty() && !it.startsWith("#")) "#$it" else it }
        hex6Pattern.matchEntire(candidate)?.let { return "#${it.groupValues[1].uppercase()}" }
        hex3Pattern.matchEntire(candidate)?.let { match ->
            val short = match.groupValues[1].uppercase()
            return "#${short[0]}${short[0]}${short[1]}${short[1]}${short[2]}${short[2]}"
        }
        return fallback
    }

    /**
     * Validates and cleans an SVG string before passing it to AndroidSVG.
     * Returns null if the SVG is missing, malformed, or contains scripts/event handlers/external refs.
     */
    fun sanitizeSvg(raw: String?): String? {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty() || input.length > 32_768) return null
        val match = svgElementPattern.find(input) ?: return null
        val svg = match.value.trim()
        if (dangerousSvgTags.containsMatchIn(svg)) return null
        if (svgEventAttr.containsMatchIn(svg)) return null
        if (svgScriptUri.containsMatchIn(svg)) return null
        if (svgExternalHref.containsMatchIn(svg)) return null
        if (svgExternalUrl.containsMatchIn(svg)) return null
        if (svgCssImport.containsMatchIn(svg)) return null
        return svg
    }

    fun looksLikeHtml(text: String): Boolean = looksLikeHtmlPattern.containsMatchIn(text)

    fun ensureCharset(html: String): String {
        if (charsetPattern.containsMatchIn(html)) return html
        return injectIntoHead(html, "<meta charset=\"utf-8\">")
    }

    fun ensureViewport(html: String): String {
        if (viewportPattern.containsMatchIn(html)) return html
        return injectIntoHead(html, MINI_APP_VIEWPORT_META)
    }

    fun ensureTitle(html: String, name: String): String {
        if (MiniAppChecks.title(html) != null) return html
        return injectIntoHead(html, "<title>${escapeHtml(name)}</title>")
    }

    /** Ensures an HTML document has UTF-8 charset, mobile viewport meta tag, and title. */
    fun prepareHtmlForBundle(rawHtml: String, name: String): String {
        val trimmed = rawHtml.trim()
        require(trimmed.isNotEmpty()) { "Mini app HTML is empty." }
        require(trimmed.length <= MINI_APP_MAX_CHARS) {
            "The file is larger than ${MINI_APP_MAX_CHARS / 1_000_000} MB."
        }
        val wrapped = if (htmlTag.containsMatchIn(trimmed)) {
            trimmed
        } else {
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">$MINI_APP_VIEWPORT_META" +
                "<title>${escapeHtml(name)}</title></head><body>$trimmed</body></html>"
        }
        return ensureTitle(ensureViewport(ensureCharset(wrapped)), name)
    }

    fun parseGeneratedPayload(rawText: String): GeneratedMiniAppPayload {
        val trimmed = rawText.trim()
        require(trimmed.isNotEmpty()) { "The model returned an empty response." }
        val payload = runCatching {
            payloadJson.decodeFromString(GeneratedMiniAppPayload.serializer(), trimmed)
        }.getOrElse {
            val extracted = extractJsonObject(trimmed).toString()
            runCatching {
                payloadJson.decodeFromString(GeneratedMiniAppPayload.serializer(), extracted)
            }.getOrElse { decodeError ->
                throw IllegalArgumentException(
                    "The model response did not match the mini app JSON schema.",
                    decodeError,
                )
            }
        }
        require(payload.htmlCode.isNotBlank()) { "The generated mini app contained no HTML code." }
        return payload
    }

    fun sanitizeGeneratedPayload(payload: GeneratedMiniAppPayload): SanitizedMiniAppPayload {
        val cleanName = sanitizeName(payload.name, fallback = MiniAppChecks.title(payload.htmlCode) ?: "Mini App")
        val cleanColor = sanitizeThemeColor(payload.themeColor)
        val cleanSvg = sanitizeSvg(payload.iconSvg)
        val cleanHtml = prepareHtmlForBundle(payload.htmlCode, cleanName)
        return SanitizedMiniAppPayload(
            name = cleanName,
            themeColor = cleanColor,
            iconSvg = cleanSvg,
            htmlCode = cleanHtml,
        )
    }

    private fun injectIntoHead(html: String, tag: String): String {
        headTag.find(html)?.let { match ->
            return html.substring(0, match.range.last + 1) + "\n" + tag + html.substring(match.range.last + 1)
        }
        htmlTag.find(html)?.let { match ->
            return html.substring(0, match.range.last + 1) + "<head>$tag</head>" + html.substring(match.range.last + 1)
        }
        return "$tag\n$html"
    }

    internal fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

object MiniAppChecks {
    private fun has(html: String, pattern: String) =
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(html)

    fun title(html: String): String? = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(html)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** Problems that stop an app from being saved via chat tools. The agent gets them back and can fix the file. */
    fun problems(html: String): List<String> = buildList {
        if (html.length > MINI_APP_MAX_CHARS) add("The file is larger than ${MINI_APP_MAX_CHARS / 1_000_000} MB.")
        if (!has(html, "<html[\\s>]") || !has(html, "</html>")) add("Wrap the app in <html> … </html>.")
        if (title(html) == null) add("Add a non-empty <title>.")
        if (!has(html, "<meta[^>]+name=[\"']?viewport")) add("Add a viewport meta tag for phones.")
        if (has(html, "<script[^>]+\\bsrc\\s*=")) add(
            "Inline all JavaScript; external <script src> files are not loaded. " +
                "An empty <script src> tag for a library on ${MINI_APP_LIBRARY_HOSTS.joinToString()} is inlined when saved.",
        )
        if (has(html, "<link[^>]+\\bhref\\s*=\\s*[\"']?(https?:)?//")) add(
            "Inline all CSS and fonts; external <link> files are not loaded. " +
                "A stylesheet <link> on ${MINI_APP_LIBRARY_HOSTS.joinToString()} is inlined when saved.",
        )
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

    /** HTTP Content-Security-Policy header applied when serving bundle HTML through WebViewAssetLoader. */
    fun cspHeader(allowNetwork: Boolean): String {
        val cdnOrigins = MINI_APP_LIBRARY_HOSTS.joinToString(" ") { "https://$it" }
        return if (allowNetwork) {
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: data: $cdnOrigins; " +
                "style-src 'self' 'unsafe-inline' blob: data: $cdnOrigins; " +
                "font-src 'self' blob: data: $cdnOrigins; " +
                "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"
        } else {
            "default-src 'self' data: blob:; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: data:; " +
                "style-src 'self' 'unsafe-inline' blob: data:; " +
                "img-src 'self' data: blob:; " +
                "font-src 'self' blob: data:; " +
                "connect-src 'self'; " +
                "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"
        }
    }

    private val headTag = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)

    /** Adds [POLICY] as the first element of the page, before any of the app's own scripts run. */
    fun withPolicy(html: String): String {
        val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"$POLICY\">"
        val anchor = headTag.find(html) ?: htmlTag.find(html) ?: return meta + html
        return html.substring(0, anchor.range.last + 1) + meta + html.substring(anchor.range.last + 1)
    }
}

/** Handles the agent's mini app tool calls. Returns JSON for the model. */
class MiniAppTools(
    private val repository: MiniAppRepository,
    private val libraries: MiniAppLibraries = MiniAppLibraries(null),
    private val renderIcon: (svg: String?, themeColor: String, name: String) -> ByteArray? = { _, _, _ -> null },
    private val onSaved: (MiniApp) -> Unit = {},
) {
    fun handle(request: JSONObject): JSONObject = try {
        when (request.optString("type")) {
            "list" -> JSONObject().put("ok", true).put(
                "apps",
                JSONArray(
                    repository.list().map {
                        JSONObject()
                            .put("id", it.id)
                            .put("name", it.name)
                            .put("description", it.description)
                            .put("themeColor", it.themeColor)
                            .put("version", it.version)
                    },
                ),
            )
            "read" -> {
                val app = repository.find(request.optString("app"))
                    ?: return fail("No mini app matches that id or name.")
                val html = repository.html(app.id).orEmpty()
                val short = MiniAppLibraries.collapse(html)
                JSONObject()
                    .put("ok", true)
                    .put("id", app.id)
                    .put("name", app.name)
                    .put("themeColor", app.themeColor)
                    .put("html", short)
                    .apply {
                        if (short != html) {
                            put(
                                "note",
                                "Inlined libraries are shown as their <script src> or <link> tags. " +
                                    "Keep those tags as they are; they are inlined again when you save.",
                            )
                        }
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
            return fail(
                "The app was not saved. It is larger than ${MINI_APP_SOURCE_MAX_CHARS / 1000} KB; " +
                    "link large libraries from a CDN instead of pasting them.",
            )
        }
        val inlined = libraries.inline(source)
        val html = inlined.html
        val problems = (inlined.problems + MiniAppChecks.problems(html)).distinct().toMutableList()
        if (name.isEmpty()) problems += "Give the app a name."
        val existing = id?.let { key -> repository.list().firstOrNull { it.id == key } }
        if (id != null && existing?.imported == true) {
            problems += "Shared apps someone sent you cannot be changed. Save a new app instead."
        }
        if (problems.isNotEmpty()) {
            return fail("The app was not saved. Fix these problems and save again.")
                .put("problems", JSONArray(problems))
        }
        val existingManifest = existing?.let { repository.manifest(it.id) }
        val requestedColor = request.optString("themeColor").trim().ifEmpty { null }
        val themeColor = if (requestedColor != null) {
            MiniAppSanitizer.sanitizeThemeColor(requestedColor)
        } else {
            existing?.themeColor ?: MINI_APP_DEFAULT_THEME_COLOR
        }
        val requestedSvg = request.optString("iconSvg").trim().ifEmpty { null }?.let(MiniAppSanitizer::sanitizeSvg)
        val shouldRenderIcon = requestedSvg != null &&
            (existingManifest == null || requestedSvg != existingManifest.iconSvg || themeColor != existingManifest.themeColor)
        val iconUpdate = if (shouldRenderIcon) {
            renderIcon(requestedSvg, themeColor, name)?.let { bytes ->
                MiniAppIconUpdate.PngBytes(bytes, iconSvg = requestedSvg)
            } ?: MiniAppIconUpdate.Keep
        } else {
            MiniAppIconUpdate.Keep
        }
        val app = repository.save(
            name = name,
            html = html,
            description = request.optString("description").trim().take(200),
            themeColor = themeColor,
            id = id,
            iconUpdate = iconUpdate,
        )
        onSaved(app)
        return JSONObject()
            .put("ok", true)
            .put("id", app.id)
            .put("name", app.name)
            .put("version", app.version)
            .apply { if (inlined.inlined.isNotEmpty()) put("inlined", JSONArray(inlined.inlined)) }
            .put(
                "result",
                "Saved. Tell the user to open it from Configure → Mini apps, where they can also add it " +
                    "to the home screen or share it.",
            )
    }

    private fun fail(message: String) = JSONObject().put("ok", false).put("error", message)
}
