package com.example.openrouteragent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal val SKILLS_NO_TELEMETRY_ENV: Map<String, String> = mapOf(
    "ADISABLE_TELEMETRY" to "1",
    "DISABLE_TELEMETRY" to "1",
    "DO_NOT_TRACK" to "1",
)

internal fun skillsCliInstallCommand(skill: RemoteSkillSummary): String =
    "ADISABLE_TELEMETRY=1 DISABLE_TELEMETRY=1 npx skills add ${skill.source} --skill ${skill.skillId}"

internal data class RemoteSkillSummary(
    val id: String,
    val skillId: String,
    val name: String,
    val source: String,
    val installs: Int = 0,
)

internal fun formatSkillInstalls(count: Int): String = when {
    count <= 0 -> ""
    count >= 1_000_000 -> "${formatCompactInstalls(count / 1_000_000.0)}M installs"
    count >= 1_000 -> "${formatCompactInstalls(count / 1_000.0)}K installs"
    count == 1 -> "1 install"
    else -> "$count installs"
}

private fun formatCompactInstalls(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")

internal class SkillsDirectoryClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val searchBaseUrl: HttpUrl = "https://skills.sh/".toHttpUrl(),
    private val rawGitHubBaseUrl: HttpUrl = "https://raw.githubusercontent.com/".toHttpUrl(),
    private val gitHubApiBaseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val wellKnownBase: (String) -> HttpUrl = { host ->
        requireNotNull("https://$host/".toHttpUrlOrNull()) { "Unsupported skill host." }
    },
) {
    suspend fun search(query: String, limit: Int = 25): List<RemoteSkillSummary> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val boundedLimit = limit.coerceIn(1, 50)
        val exact = searchOnce(trimmed, boundedLimit)
        val terms = trimmed.split(Regex("\\s+")).filter(String::isNotBlank).distinct()
        if (exact.size >= 10 || terms.size !in 2..4) return exact

        val subqueries = terms.indices.map { dropIndex ->
            terms.filterIndexed { index, _ -> index != dropIndex }.joinToString(" ")
        }
        val partialLists = coroutineScope {
            subqueries.map { sub ->
                async { runCatching { searchOnce(sub, boundedLimit) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }

        val seen = exact.mapTo(mutableSetOf()) { it.id }
        val candidates = linkedMapOf<String, PartialMatch>()
        for (list in partialLists) {
            list.forEachIndexed { rank, skill ->
                if (skill.id in seen) return@forEachIndexed
                val existing = candidates[skill.id]
                if (existing != null) {
                    existing.hits++
                    if (rank < existing.bestRank) existing.bestRank = rank
                } else {
                    candidates[skill.id] = PartialMatch(skill, hits = 1, bestRank = rank)
                }
            }
        }

        val mergedPartial = candidates.values
            .sortedWith(compareByDescending<PartialMatch> { it.hits }
                .thenBy { it.bestRank }
                .thenByDescending { it.skill.installs })
            .take((boundedLimit - exact.size).coerceAtLeast(0))
            .map { it.skill }
        return exact + mergedPartial
    }

    private suspend fun searchOnce(query: String, limit: Int): List<RemoteSkillSummary> {
        val url = searchBaseUrl.newBuilder()
            .addPathSegments("api/search")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("DNT", "1")
            .build()
        val response = fetchText(request, maxBytes = 1_000_000, errorLabel = "skills.sh")
        if (response.code !in 200..299) {
            throw IOException("Skill search failed (HTTP ${response.code}).")
        }
        val payload = runCatching { JSONObject(response.body) }
            .getOrElse { throw IOException("skills.sh returned an unreadable search response.", it) }
        val array = payload.optJSONArray("skills") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            val source = item.optString("source").trim()
            if (name.isEmpty() || source.isEmpty() || source.any(Char::isWhitespace)) return@mapNotNull null
            val skillId = item.optString("skillId").trim().ifEmpty { name }
            val id = item.optString("id").trim().ifEmpty { "$source/$skillId" }
            val installs = item.optInt("installs", 0).coerceAtLeast(0)
            RemoteSkillSummary(id = id, skillId = skillId, name = name, source = source, installs = installs)
        }
    }

    suspend fun fetchSkill(skill: RemoteSkillSummary): SavedAgentSkill {
        val normalizedSource = skill.source.trim().removePrefix("https://github.com/").removeSuffix("/")
        if (gitHubRepoPattern.matches(normalizedSource)) {
            fetchFromGitHub(normalizedSource, skill)?.let { return it }
        } else if (hostPattern.matches(normalizedSource)) {
            fetchFromWellKnown(normalizedSource, skill)?.let { return it }
        }
        throw IOException("Could not load SKILL.md for ${skill.name} from ${skill.source}.")
    }

    private suspend fun fetchFromGitHub(repoPath: String, skill: RemoteSkillSummary): SavedAgentSkill? {
        val parts = repoPath.split('/', limit = 2)
        val owner = parts[0]
        val repo = parts[1]
        if (owner in invalidPathSegments || repo in invalidPathSegments) return null

        val directPaths = buildList {
            val segments = safeRelativeSegments(skill.skillId)
            if (segments != null) {
                add(listOf("skills") + segments + "SKILL.md")
                if (segments.firstOrNull() != "skills") add(segments + "SKILL.md")
            }
        }
        for (segments in directPaths) {
            fetchRawSkill(owner, repo, segments)?.let { return it }
        }
        fetchRawSkill(owner, repo, listOf("SKILL.md"))?.let { parsed ->
            if (parsed.matchesSummary(skill)) return parsed
        }

        val treeUrl = gitHubApiBaseUrl.newBuilder()
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegment("git")
            .addPathSegment("trees")
            .addPathSegment("HEAD")
            .addQueryParameter("recursive", "1")
            .build()
        val treeRequest = Request.Builder()
            .url(treeUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OpenRouterAgentAndroid")
            .header("DNT", "1")
            .build()
        val treeRes = runCatching { fetchText(treeRequest, maxBytes = 2_000_000, errorLabel = "GitHub") }.getOrNull()
            ?: return null
        if (treeRes.code !in 200..299) return null

        val treeArray = runCatching { JSONObject(treeRes.body).optJSONArray("tree") }.getOrNull() ?: return null
        val candidates = (0 until treeArray.length()).mapNotNull { index ->
            val node = treeArray.optJSONObject(index) ?: return@mapNotNull null
            if (node.optString("type") != "blob") return@mapNotNull null
            val path = node.optString("path").trim()
            if (path == "SKILL.md" || path.endsWith("/SKILL.md")) {
                safeRelativeSegments(path)?.let { path to it }
            } else null
        }

        val targets = listOf(skill.skillId, skill.name)
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .distinct()

        val sorted = candidates.sortedWith(
            compareBy<Pair<String, List<String>>>(
                { (path, _) -> candidateTier(path, targets) },
                { (path, _) -> if (path.startsWith("skills/")) 0 else 1 },
                { (path, _) -> path.count { it == '/' } },
                { (path, _) -> path },
            )
        )

        var exactFolderFallback: SavedAgentSkill? = null
        for ((path, segments) in sorted.take(12)) {
            val parsed = fetchRawSkill(owner, repo, segments) ?: continue
            if (parsed.matchesSummary(skill)) return parsed
            if (exactFolderFallback == null && candidateTier(path, targets) == 0) {
                exactFolderFallback = parsed
            }
        }
        return exactFolderFallback
    }

    private suspend fun fetchRawSkill(owner: String, repo: String, segments: List<String>): SavedAgentSkill? {
        val url = rawGitHubBaseUrl.newBuilder()
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegment("HEAD")
            .apply { segments.forEach(::addPathSegment) }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain, text/markdown")
            .header("DNT", "1")
            .build()
        val res = fetchText(request, maxBytes = SKILL_IMPORT_BYTES.toLong(), errorLabel = "GitHub")
        if (res.code !in 200..299) return null
        return runCatching { parseSkill(res.body) }.getOrNull()
    }

    private suspend fun fetchFromWellKnown(host: String, skill: RemoteSkillSummary): SavedAgentSkill? {
        val base = wellKnownBase(host)
        for (dir in listOf("agent-skills", "skills")) {
            val indexUrl = base.newBuilder()
                .addPathSegment(".well-known")
                .addPathSegment(dir)
                .addPathSegment("index.json")
                .build()
            val indexRes = runCatching {
                fetchText(
                    Request.Builder().url(indexUrl).header("Accept", "application/json").header("DNT", "1").build(),
                    maxBytes = 1_000_000,
                    errorLabel = host,
                )
            }.getOrNull() ?: continue
            if (indexRes.code !in 200..299) continue

            val entries = runCatching { JSONObject(indexRes.body).optJSONArray("skills") }.getOrNull() ?: continue
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val entryName = entry.optString("name").trim()
                if (!entryName.equals(skill.skillId, true) && !entryName.equals(skill.name, true)) continue

                val directUrl = entry.optString("url").trim()
                if (directUrl.isNotEmpty()) {
                    val resolved = directUrl.toHttpUrlOrNull() ?: base.resolve(directUrl) ?: continue
                    if (base.isHttps && !resolved.isHttps) continue
                    val res = fetchText(
                        Request.Builder().url(resolved).header("Accept", "text/plain, text/markdown").header("DNT", "1").build(),
                        maxBytes = SKILL_IMPORT_BYTES.toLong(),
                        errorLabel = host,
                    )
                    if (res.code in 200..299) {
                        runCatching { parseSkill(res.body) }.getOrNull()?.let { return it }
                    }
                }

                val files = entry.optJSONArray("files")
                val hasSkillMd = files == null || (0 until files.length()).any {
                    files.optString(it).equals("SKILL.md", ignoreCase = true)
                }
                val entrySegments = safeRelativeSegments(entryName) ?: continue
                if (hasSkillMd) {
                    val skillUrl = base.newBuilder()
                        .addPathSegment(".well-known")
                        .addPathSegment(dir)
                        .apply { entrySegments.forEach(::addPathSegment) }
                        .addPathSegment("SKILL.md")
                        .build()
                    val res = fetchText(
                        Request.Builder().url(skillUrl).header("Accept", "text/plain, text/markdown").header("DNT", "1").build(),
                        maxBytes = SKILL_IMPORT_BYTES.toLong(),
                        errorLabel = host,
                    )
                    if (res.code in 200..299) {
                        runCatching { parseSkill(res.body) }.getOrNull()?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private suspend fun fetchText(request: Request, maxBytes: Long, errorLabel: String): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    val message = if (e is java.net.SocketTimeoutException) {
                        "$errorLabel request timed out."
                    } else {
                        "Could not reach $errorLabel. Check your connection."
                    }
                    continuation.resumeWithException(IOException(message, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!continuation.isActive) return
                        val result = runCatching {
                            val source = res.body?.source()
                            val text = if (source == null) {
                                ""
                            } else {
                                if (source.request(maxBytes + 1)) {
                                    throw IllegalArgumentException("This skill file exceeds the ${maxBytes / 1000} KB limit.")
                                }
                                source.readUtf8()
                            }
                            HttpResult(res.code, text)
                        }
                        continuation.resumeWith(result)
                    }
                }
            })
        }

    private data class HttpResult(val code: Int, val body: String)
    private data class PartialMatch(val skill: RemoteSkillSummary, var hits: Int, var bestRank: Int)
}

private fun SavedAgentSkill.matchesSummary(summary: RemoteSkillSummary): Boolean =
    name.equals(summary.skillId, ignoreCase = true) || name.equals(summary.name, ignoreCase = true)

private fun candidateTier(path: String, targets: List<String>): Int {
    val folder = path.removeSuffix("/SKILL.md").substringAfterLast('/', "").lowercase(Locale.US)
    if (folder.isEmpty()) return 2
    if (folder in targets) return 0
    if (targets.any { target ->
            target.endsWith("-$folder") || target.endsWith("_$folder") ||
                target.contains(folder) || folder.contains(target)
        }) {
        return 1
    }
    return 2
}

private fun safeRelativeSegments(path: String): List<String>? {
    if (path.isBlank() || path.contains('\\')) return null
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it in invalidPathSegments }) return null
    return segments
}

private val invalidPathSegments = setOf(".", "..")
private val gitHubRepoPattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
private val hostPattern = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$", RegexOption.IGNORE_CASE)
