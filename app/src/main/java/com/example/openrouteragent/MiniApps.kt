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
internal fun miniAppArchiveFileName(name: String): String {
    val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "mini-app" }
    return "$safe.miniapp"
}

internal fun fallbackIconInitial(name: String): String =
    name.trim().firstOrNull()?.uppercase() ?: "A"

/** Returns true when an AI-owned icon needs re-rendering due to a theme color or fallback-initial name change. */
internal fun shouldRegenerateAiIconOnMetadataEdit(
    aiGeneratedIcon: Boolean,
    iconSvg: String?,
    oldName: String,
    newName: String,
    oldThemeColor: String,
    newThemeColor: String,
): Boolean {
    if (!aiGeneratedIcon && iconSvg == null) return false
    if (!newThemeColor.equals(oldThemeColor, ignoreCase = true)) return true
    return iconSvg == null && fallbackIconInitial(newName) != fallbackIconInitial(oldName)
}

/** Structured Output payload returned by an LLM when creating or editing a mini app. */
@Serializable
data class GeneratedMiniAppPayload(
    val name: String = "",
    val themeColor: String = MINI_APP_DEFAULT_THEME_COLOR,
    val iconSvg: String = "",
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
    val aiGeneratedIcon: Boolean = false,
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
    fun toManifest(
        iconSvg: String? = null,
        aiGeneratedIcon: Boolean = iconSvg != null,
    ): MiniAppManifest = MiniAppManifest(
        id = id,
        name = name,
        themeColor = themeColor,
        createdAt = createdAt,
        updatedAt = updatedAt,
        iconUri = iconUri,
        iconSvg = iconSvg,
        aiGeneratedIcon = aiGeneratedIcon,
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
        coerceInputValues = true
    }

    // Subdomain-safe ID: lowercase alphanumeric and internal hyphens (covers UUIDs and legacy hex IDs).
    private val validIdPattern = Regex("^[a-z0-9](?:[a-z0-9-]{6,61}[a-z0-9])$")
    private val hex6Pattern = Regex("^#([0-9a-fA-F]{6})$")
    private val hex3Pattern = Regex("^#([0-9a-fA-F]{3})$")
    private val viewportPattern = Regex("<meta[^>]+\\bname\\s*=\\s*[\"']?viewport\\b", RegexOption.IGNORE_CASE)
    private val charsetPattern = Regex("<meta[^>]+\\bcharset\\s*=", RegexOption.IGNORE_CASE)
    private val headTag = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val bodyTag = Regex("<body(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val doctypePattern = Regex("^<!doctype\\s+html\\b", RegexOption.IGNORE_CASE)
    private val svgElementPattern = Regex("<svg\\b[\\s\\S]*?</svg\\s*>", RegexOption.IGNORE_CASE)
    private val svgOpeningTagPattern = Regex("^<svg\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val svgDrawableElementPattern = Regex(
        "<\\s*(path|circle|rect|line|polyline|polygon|ellipse|text|use)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val dangerousSvgTags = Regex(
        "<\\s*(script|foreignobject|iframe|frame|frameset|object|embed|applet|image|feimage|meta|link|base)\\b|<!\\s*(doctype|entity)\\b|<\\?",
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
    private val svgCustomEntityRef = Regex("&(?!amp;|lt;|gt;|quot;|apos;|#\\d+;|#x[0-9a-fA-F]+;)[a-zA-Z_][a-zA-Z0-9._-]*;")
    private val looksLikeHtmlPattern = Regex(
        "<\\s*(!doctype\\s+html|html|head|body|div|main|section|script|style|canvas|svg|h[1-6]|p|form|button|table)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val metaTagPattern = Regex(
        "<meta\\b(?:[^<>\"']|\"[^\"]*\"|'[^']*')*/?>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val metaRefreshAttrPattern = Regex(
        "\\bhttp-equiv\\s*=\\s*(?:\"\\s*refresh\\s*\"|'\\s*refresh\\s*'|refresh(?=[\\s/>]|$))",
        RegexOption.IGNORE_CASE,
    )
    private val htmlNumericEntityPattern = Regex("&#(?:x([0-9a-fA-F]+)|(\\d+));?", RegexOption.IGNORE_CASE)
    private val htmlNamedSpaceEntityPattern = Regex("&(?:tab|newline);", RegexOption.IGNORE_CASE)
    private val openingFencePattern = Regex("^(`{3,})[^`\\r\\n]*\\r?\\n")
    private val closingFenceAtLinePattern = Regex("(?:\\r?\\n)[ \\t]*(`{3,})[ \\t]*$")
    private val closingFenceAfterTagPattern = Regex("(?<=>)[ \\t]*(`{3,})[ \\t]*$")
    private val onlyFencePattern = Regex("^[ \\t]*(`{3,})[ \\t]*$")
    private val singleLineFencePattern = Regex("^(`{3,})(?:(?:html|htm)\\s+)?([^\\r\\n]*?)\\1$", RegexOption.IGNORE_CASE)

    fun isValidId(id: String): Boolean = validIdPattern.matches(id)

    /** Returns a normalized safe relative bundle path, or null if the path is unsafe or empty. */
    fun sanitizeRelativePath(path: String?): String? {
        val raw = path?.trim()?.replace('\\', '/').orEmpty()
        if (raw.isEmpty() || raw.startsWith("/") || raw.contains("?") || raw.contains("#") || raw.contains(":")) {
            return null
        }
        val segments = raw.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) {
            return null
        }
        return segments.joinToString("/")
    }

    fun sanitizeEntryPoint(entryPoint: String?): String =
        sanitizeRelativePath(entryPoint) ?: MINI_APP_DEFAULT_ENTRY_POINT

    fun sanitizeName(raw: String?, fallback: String = "Mini App", maxLength: Int = 40): String {
        val clean = raw.orEmpty()
            .filterNot(Char::isISOControl)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)
            .trimEnd()
        if (clean.isNotEmpty()) return clean
        return fallback
            .filterNot(Char::isISOControl)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)
            .trimEnd()
            .ifEmpty { "Mini App" }
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
     * Returns null if the SVG is missing, empty, malformed, or contains scripts/event handlers/external refs.
     * Ensures the standard SVG XML namespace is present on the root `<svg>` tag.
     */
    fun sanitizeSvg(raw: String?): String? {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty() || input.length > 32_768) return null
        if (dangerousSvgTags.containsMatchIn(input)) return null
        val match = svgElementPattern.find(input) ?: return null
        val svg = match.value.trim()
        if (dangerousSvgTags.containsMatchIn(svg)) return null
        if (svgEventAttr.containsMatchIn(svg)) return null
        if (svgScriptUri.containsMatchIn(svg)) return null
        if (svgExternalHref.containsMatchIn(svg)) return null
        if (svgExternalUrl.containsMatchIn(svg)) return null
        if (svgCssImport.containsMatchIn(svg)) return null
        if (svgCustomEntityRef.containsMatchIn(svg)) return null
        if (!svgDrawableElementPattern.containsMatchIn(svg)) return null
        return ensureSvgXmlns(svg)
    }

    private fun ensureSvgXmlns(svg: String): String {
        val openTag = svgOpeningTagPattern.find(svg) ?: return svg
        val attrs = openTag.groupValues[1]
        if (Regex("\\bxmlns\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(attrs)) return svg
        return svg.replaceRange(
            openTag.range,
            "<svg xmlns=\"http://www.w3.org/2000/svg\"$attrs>",
        )
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

    internal fun normalizeSvgForDiff(svg: String?): String =
        svg.orEmpty().replace(Regex("\\s+"), " ").trim()

    internal fun stripOuterMarkdownFences(raw: String): String {
        val trimmed = raw.removePrefix("\uFEFF").trim()
        if (!trimmed.startsWith("```")) return trimmed
        openingFencePattern.find(trimmed)?.let { match ->
            val fenceLen = match.groupValues[1].length
            var body = trimmed.substring(match.range.last + 1).trimEnd()
            val onlyFence = onlyFencePattern.matchEntire(body)
            val lineClose = closingFenceAtLinePattern.find(body)
            val tagClose = closingFenceAfterTagPattern.find(body)
            body = when {
                onlyFence != null && onlyFence.groupValues[1].length >= fenceLen -> ""
                lineClose != null && lineClose.groupValues[1].length >= fenceLen ->
                    body.substring(0, lineClose.range.first)
                tagClose != null && tagClose.groupValues[1].length >= fenceLen ->
                    body.substring(0, tagClose.range.first)
                else -> body
            }
            return body.removePrefix("\uFEFF").trim()
        }
        singleLineFencePattern.matchEntire(trimmed)?.let { match ->
            return match.groupValues[2].removePrefix("\uFEFF").trim()
        }
        return trimmed
    }

    private fun decodeAsciiHtmlEntities(tag: String): String {
        val withNumeric = htmlNumericEntityPattern.replace(tag) { match ->
            val hex = match.groupValues[1]
            val code = if (hex.isNotEmpty()) hex.toIntOrNull(16) else match.groupValues[2].toIntOrNull(10)
            if (code != null && code in 0x09..0x7E && code != '"'.code && code != '\''.code) {
                code.toChar().toString()
            } else {
                match.value
            }
        }
        return htmlNamedSpaceEntityPattern.replace(withNumeric, " ")
    }

    private fun isMetaRefreshTag(tag: String): Boolean =
        metaRefreshAttrPattern.containsMatchIn(decodeAsciiHtmlEntities(tag))

    internal fun hasMetaRefresh(html: String): Boolean =
        metaTagPattern.findAll(html).any { isMetaRefreshTag(it.value) }

    internal fun stripMetaRefresh(html: String): String =
        metaTagPattern.replace(html) { match -> if (isMetaRefreshTag(match.value)) "" else match.value }

    /**
     * Strips accidental outer markdown fences and `<meta http-equiv="refresh">` redirect tags, then ensures the
     * HTML document has UTF-8 charset, mobile viewport meta tag, standards doctype, and title.
     */
    fun prepareHtmlForBundle(rawHtml: String, name: String): String {
        val unfenced = stripOuterMarkdownFences(rawHtml)
        require(unfenced.isNotEmpty()) { "Mini app HTML is empty." }
        require(unfenced.length <= MINI_APP_MAX_CHARS) {
            "The file is larger than ${MINI_APP_MAX_CHARS / 1_000_000} MB."
        }
        val cleaned = stripMetaRefresh(unfenced).trim()
        require(cleaned.isNotEmpty()) { "Mini app HTML is empty." }
        val wrapped = if (htmlTag.containsMatchIn(cleaned)) {
            if (doctypePattern.containsMatchIn(cleaned)) cleaned else "<!DOCTYPE html>\n$cleaned"
        } else {
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">$MINI_APP_VIEWPORT_META" +
                "<title>${escapeHtml(name)}</title></head><body>$cleaned</body></html>"
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
        val cleanName = sanitizeName(
            raw = payload.name,
            fallback = MiniAppChecks.title(payload.htmlCode) ?: "Mini App",
            maxLength = 24,
        )
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
        val bodyIndex = bodyTag.find(html)?.range?.first ?: Int.MAX_VALUE
        headTag.find(html)?.takeIf { it.range.first < bodyIndex }?.let { match ->
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
        if (MiniAppSanitizer.hasMetaRefresh(html)) add("Remove the meta refresh.")
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
                "connect-src 'self' data: blob:; " +
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
        val rawName = request.optString("name").trim().ifEmpty { MiniAppChecks.title(source).orEmpty() }
        val name = if (rawName.isBlank()) "" else MiniAppSanitizer.sanitizeName(rawName)
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
        val effectiveSvg = requestedSvg ?: existingManifest?.iconSvg
        val isAiOwnedIcon = existingManifest == null ||
            existingManifest.aiGeneratedIcon ||
            existingManifest.iconSvg != null ||
            existingManifest.iconUri == null
        val svgChanged = requestedSvg != null &&
            MiniAppSanitizer.normalizeSvgForDiff(requestedSvg) != MiniAppSanitizer.normalizeSvgForDiff(existingManifest?.iconSvg)
        val colorChanged = existingManifest != null && !themeColor.equals(existingManifest.themeColor, ignoreCase = true)
        val fallbackInitialChanged = existingManifest != null &&
            effectiveSvg == null &&
            existingManifest.aiGeneratedIcon &&
            fallbackIconInitial(name) != fallbackIconInitial(existingManifest.name)
        val shouldRenderIcon = isAiOwnedIcon && when {
            effectiveSvg != null -> existingManifest == null || svgChanged || colorChanged
            existingManifest?.aiGeneratedIcon == true -> colorChanged || fallbackInitialChanged
            else -> false
        }
        val iconUpdate = if (shouldRenderIcon) {
            renderIcon(effectiveSvg, themeColor, name)?.let { bytes ->
                MiniAppIconUpdate.PngBytes(bytes, iconSvg = effectiveSvg, aiGenerated = true)
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
