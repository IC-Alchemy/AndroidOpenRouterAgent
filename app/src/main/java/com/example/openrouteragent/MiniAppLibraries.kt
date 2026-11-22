package com.example.openrouteragent

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/*
 * Mini apps may name a library on a public CDN with <script src> or <link rel="stylesheet">. When the app is saved,
 * the file is downloaded once, checked against its integrity hash if one is given, and written into the HTML. The
 * app stays one file: it works offline, keeps working when shared, and its code cannot change after it is saved.
 * Each inlined tag remembers its source, so the agent reads the short tag back instead of the whole library.
 */

const val MINI_APP_LIBRARY_MAX_BYTES = 3_000_000
val MINI_APP_LIBRARY_HOSTS = listOf("cdn.jsdelivr.net", "cdnjs.cloudflare.com", "unpkg.com")
private const val SOURCE_ATTRIBUTE = "data-inlined-from"

/** Downloads library files. Returns the bytes, or throws [IOException] with a message the agent can act on. */
fun interface LibraryFetcher {
    fun fetch(url: HttpUrl): ByteArray
}

class OkHttpLibraryFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).build(),
) : LibraryFetcher {
    override fun fetch(url: HttpUrl): ByteArray {
        var target = url
        // CDNs redirect unpinned or short paths; follow a few redirects, but only to the same allowed host.
        repeat(5) {
            client.newCall(Request.Builder().url(target).build()).execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { target.resolve(it) }
                    if (next == null || next.scheme != "https" || next.host != url.host) {
                        throw IOException("$url redirected somewhere else.")
                    }
                    target = next
                    return@repeat
                }
                if (!response.isSuccessful) throw IOException("$url could not be downloaded (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("$url returned nothing.")
                if (body.contentLength() > MINI_APP_LIBRARY_MAX_BYTES) throw tooLarge(url)
                val bytes = body.byteStream().use { it.readBytesLimited(MINI_APP_LIBRARY_MAX_BYTES + 1) }
                if (bytes.size > MINI_APP_LIBRARY_MAX_BYTES) throw tooLarge(url)
                return bytes
            }
        }
        throw IOException("$url redirected too many times.")
    }

    private fun tooLarge(url: HttpUrl) =
        IOException("$url is larger than ${MINI_APP_LIBRARY_MAX_BYTES / 1_000_000} MB; use a smaller build.")
}

/** Inlines CDN libraries into mini app HTML, caching each download under [cacheDir] by URL. */
class MiniAppLibraries(private val cacheDir: File?, private val fetcher: LibraryFetcher = OkHttpLibraryFetcher()) {
    class Result(val html: String, val problems: List<String>, val inlined: List<String>)

    private val scriptTag = Regex("<script\\b([^>]*)>\\s*</script\\s*>", RegexOption.IGNORE_CASE)
    private val linkTag = Regex("<link\\b([^>]*)>", RegexOption.IGNORE_CASE)

    fun inline(html: String): Result {
        val problems = mutableListOf<String>()
        val inlined = mutableListOf<String>()
        val withScripts = scriptTag.replace(html) { match ->
            val attributes = match.groupValues[1]
            val src = attribute(attributes, "src") ?: return@replace match.value
            val code = load(src, attribute(attributes, "integrity"), problems) ?: return@replace match.value
            inlined += src
            val kept = keptAttributes(attributes, "src", "crossorigin", "async", "defer")
            "<script $SOURCE_ATTRIBUTE=\"${escapeAttribute(src)}\"$kept>${escapeContent(code, "script")}</script>"
        }
        val result = linkTag.replace(withScripts) { match ->
            val attributes = match.groupValues[1]
            val rel = attribute(attributes, "rel")?.lowercase()?.split(Regex("\\s+")).orEmpty()
            val href = attribute(attributes, "href")
            if ("stylesheet" !in rel || href == null || !isRemote(href)) return@replace match.value
            val css = load(href, attribute(attributes, "integrity"), problems) ?: return@replace match.value
            inlined += href
            val media = attribute(attributes, "media")?.let { " media=\"${escapeAttribute(it)}\"" }.orEmpty()
            "<style $SOURCE_ATTRIBUTE=\"${escapeAttribute(href)}\"$media>${escapeContent(css, "style")}</style>"
        }
        return Result(result, problems, inlined)
    }

    private fun load(source: String, integrity: String?, problems: MutableList<String>): String? {
        val url = (if (source.startsWith("//")) "https:$source" else source).toHttpUrlOrNull()
        if (url == null || url.scheme != "https" || url.host !in MINI_APP_LIBRARY_HOSTS) {
            problems += "Only libraries from ${MINI_APP_LIBRARY_HOSTS.joinToString()} can be loaded, over https. " +
                "Inline $source yourself or use one of those CDNs."
            return null
        }
        return try {
            val cached = cacheFile(url)?.takeIf { it.isFile }?.readBytes()
            val bytes = cached?.takeIf { integrity == null || matches(it, integrity) } ?: fetcher.fetch(url)
            if (integrity != null && !matches(bytes, integrity)) {
                problems += "$source does not match its integrity hash. Fix the hash or remove the integrity attribute."
                return null
            }
            if (bytes !== cached) cacheFile(url)?.let { file -> saveCache(file, bytes) }
            bytes.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            problems += e.message ?: "$source could not be downloaded."
            null
        }
    }

    private fun cacheFile(url: HttpUrl) = cacheDir?.let { File(it, sha256Hex(url.toString())) }

    private fun saveCache(file: File, bytes: ByteArray) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.delete()
        } catch (_: IOException) {
            // The cache only saves a download next time.
        }
    }

    companion object {
        private val sourceScript = Regex(
            "<script\\b([^>]*\\b$SOURCE_ATTRIBUTE\\s*=\\s*\"([^\"]*)\"[^>]*)>.*?</script\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val sourceStyle = Regex(
            "<style\\b([^>]*\\b$SOURCE_ATTRIBUTE\\s*=\\s*\"([^\"]*)\"[^>]*)>.*?</style\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        /** Turns inlined libraries back into short tags, so the agent can read and edit the app's own code. */
        fun collapse(html: String): String {
            val scripts = sourceScript.replace(html) { match ->
                val src = escapeAttribute(unescapeAttribute(match.groupValues[2]))
                val kept = keptAttributes(match.groupValues[1], SOURCE_ATTRIBUTE)
                "<script src=\"$src\"$kept></script>"
            }
            return sourceStyle.replace(scripts) { match ->
                val href = escapeAttribute(unescapeAttribute(match.groupValues[2]))
                val kept = keptAttributes(match.groupValues[1], SOURCE_ATTRIBUTE)
                "<link rel=\"stylesheet\" href=\"$href\"$kept>"
            }
        }

        private val attributePattern = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+))?")

        private fun attributes(text: String) = attributePattern.findAll(text.removeSuffix("/")).map { match ->
            val raw = match.groupValues[2]
            match.groupValues[1].lowercase() to (if (raw.isEmpty()) null else raw.trim('"', '\''))
        }.toList()

        fun attribute(text: String, name: String): String? =
            attributes(text).firstOrNull { it.first == name }?.second?.let(::unescapeAttribute)

        private fun keptAttributes(text: String, vararg dropped: String) = attributes(text)
            .filter { it.first !in dropped }
            .joinToString("") { (name, value) -> if (value == null) " $name" else " $name=\"${escapeAttribute(unescapeAttribute(value))}\"" }

        private fun isRemote(value: String) = value.startsWith("//") || value.startsWith("https:", ignoreCase = true) ||
            value.startsWith("http:", ignoreCase = true)

        private fun escapeAttribute(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
        private fun unescapeAttribute(value: String) = value.replace("&quot;", "\"").replace("&amp;", "&")

        /** Keeps the code from ending its own tag early. `<\/` means the same as `</` in JavaScript and CSS strings. */
        private fun escapeContent(text: String, tag: String) =
            text.replace(Regex("</($tag)", RegexOption.IGNORE_CASE), "<\\\\/$1")
                .let { if (tag == "script") it.replace("<!--", "<\\!--") else it }

        /** Checks a Subresource Integrity value such as `sha384-…`. Any listed hash that matches is enough. */
        fun matches(bytes: ByteArray, integrity: String): Boolean = integrity.trim().split(Regex("\\s+")).any { entry ->
            val (algorithm, expected) = entry.substringBefore('?').split('-', limit = 2).takeIf { it.size == 2 }
                ?: return@any false
            val name = when (algorithm.lowercase()) {
                "sha256" -> "SHA-256"
                "sha384" -> "SHA-384"
                "sha512" -> "SHA-512"
                else -> return@any false
            }
            Base64.getEncoder().encodeToString(MessageDigest.getInstance(name).digest(bytes)) == expected
        }

        private fun sha256Hex(text: String) =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
