package com.example.openrouteragent

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
internal data class GitHubRepo(val name: String, val id: String, val defaultBranch: String)

@Serializable
internal data class GitHubHead(val commit: String, val tree: String)

@Serializable
internal data class GitHubEntry(val path: String, val mode: String, val type: String, val sha: String, val size: Long? = null) {
    val canRead: Boolean get() = type == "blob" && mode in setOf("100644", "100755") &&
        size != null && size in 0..FILE_PREVIEW_LIMIT
}

@Serializable
internal data class GitHubTree(val entries: List<GitHubEntry>, val truncated: Boolean)

internal data class GitHubAccount(val id: Long, val login: String)
internal class GitHubHttpError(val code: Int, message: String) : IOException(message)
private class GitHubResponseTooLarge : IOException("The GitHub response exceeds the download limit.")

internal fun gitHubRepoName(input: String): String {
    val url = runCatching { input.trim().toHttpUrl() }.getOrNull()
    val parts = url?.pathSegments.orEmpty().dropLastWhile { it.isEmpty() }
    require(url != null && url.scheme == "https" && url.host == "github.com" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        parts.size == 2 && parts[0].matches(Regex("[a-zA-Z0-9-]+")) &&
        parts[1].removeSuffix(".git").matches(Regex("[a-zA-Z0-9_.-]+"))) {
        "Enter a GitHub repository URL such as https://github.com/owner/repo."
    }
    return parts[0] + "/" + parts[1].removeSuffix(".git")
}

internal class GitHubClient(
    private val origin: HttpUrl = "https://api.github.com/".toHttpUrl(),
    client: OkHttpClient = OkHttpClient(),
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()

    fun account(token: String): GitHubAccount = get(url("user"), token).let {
        GitHubAccount(it.getLong("id"), it.getString("login"))
    }

    fun repository(name: String, token: String): GitHubRepo = get(repoUrl(name), token).let {
        GitHubRepo(it.getString("full_name"), it.getString("node_id"), it.getString("default_branch"))
    }

    fun head(name: String, branch: String, token: String): GitHubHead {
        val commit = ref(name, branch, token) ?: throw GitHubHttpError(404, "GitHub couldn’t find this branch.")
        val tree = get(repoUrl(name, "git", "commits", commit), token).getJSONObject("tree")
        return GitHubHead(commit, sha(tree.getString("sha")))
    }

    fun tree(name: String, id: String, recursive: Boolean, token: String): GitHubTree {
        val endpoint = repoUrl(name, "git", "trees", sha(id)).newBuilder()
        if (recursive) endpoint.addQueryParameter("recursive", "1")
        val response = try {
            get(endpoint.build(), token)
        } catch (error: GitHubResponseTooLarge) {
            if (recursive) return GitHubTree(emptyList(), true)
            throw error
        }
        return response.let { result ->
            val truncated = result.optBoolean("truncated")
            if (truncated) return@let GitHubTree(emptyList(), true)
            val tree = result.getJSONArray("tree")
            GitHubTree((0 until tree.length()).map { index ->
                val item = tree.getJSONObject(index)
                GitHubEntry(item.getString("path"), item.getString("mode"), item.getString("type"),
                    sha(item.getString("sha")), item.optLong("size", -1).takeIf { it >= 0 })
            }, false)
        }
    }

    fun blob(name: String, id: String, token: String): ByteArray =
        request(repoUrl(name, "git", "blobs", sha(id)), token, accept = "application/vnd.github.raw+json",
            limit = FILE_PREVIEW_LIMIT)

    fun candidate(name: String, parent: GitHubHead, drafts: List<GitHubDraft>, message: String, token: String): GitHubHead {
        val edits = JSONArray()
        drafts.forEach { draft ->
            edits.put(JSONObject().put("path", draft.path).put("mode", draft.original?.mode ?: "100644")
                .put("type", "blob").put("content", draft.text))
        }
        val newTree = sha(post(repoUrl(name, "git", "trees"), token,
            JSONObject().put("base_tree", parent.tree).put("tree", edits)).getString("sha"))
        val commit = post(repoUrl(name, "git", "commits"), token,
            JSONObject().put("message", message).put("tree", newTree).put("parents", JSONArray().put(parent.commit)))
        return GitHubHead(sha(commit.getString("sha")), newTree)
    }

    fun ref(name: String, branch: String, token: String): String? {
        val endpoint = repoUrl(name, "git", "ref", "heads").newBuilder().addPathSegments(branch).build()
        return try {
            sha(get(endpoint, token).getJSONObject("object").getString("sha"))
        } catch (error: GitHubHttpError) {
            if (error.code == 404) null else throw error
        }
    }

    fun advance(repo: GitHubRepo, branch: String, expected: String?, candidate: String, token: String) {
        val update = JSONObject().put("name", "refs/heads/$branch").put("beforeOid", expected ?: "0".repeat(40))
            .put("afterOid", candidate).put("force", false)
        val input = JSONObject().put("repositoryId", repo.id).put("refUpdates", JSONArray().put(update))
            .put("clientMutationId", candidate)
        val payload = JSONObject().put("query", UPDATE_REFS).put("variables", JSONObject().put("input", input))
        val result = post(url("graphql"), token, payload)
        if (result.optJSONObject("data")?.optJSONObject("updateRefs")?.optString("clientMutationId") != candidate ||
            (result.optJSONArray("errors")?.length() ?: 0) > 0) {
            throw GitHubHttpError(409, "GitHub rejected the branch update. Check branch rules and refresh the repository.")
        }
    }

    fun containsCommit(name: String, commit: String, remote: String, token: String): Boolean {
        if (commit == remote) return true
        val endpoint = repoUrl(name, "compare", "$commit...$remote").newBuilder()
            // The first page includes patches. Later pages still report the comparison status.
            .addQueryParameter("per_page", "1").addQueryParameter("page", "2").build()
        return try {
            get(endpoint, token).optString("status") in setOf("ahead", "identical")
        } catch (error: GitHubHttpError) {
            if (error.code == 404) false else throw error
        }
    }

    fun existingPr(
        name: String, branch: String, base: String, token: String, pending: GitHubPendingPr?,
    ): String? {
        val endpoint = repoUrl(name, "pulls").newBuilder().addQueryParameter("state", if (pending == null) "open" else "all")
            .addQueryParameter("head", "${name.substringBefore('/')}:$branch")
            .addQueryParameter("base", base).addQueryParameter("per_page", if (pending == null) "1" else "20")
            .addQueryParameter("sort", "created").addQueryParameter("direction", "desc").build()
        val results = JSONArray(request(endpoint, token).decodeToString())
        for (index in 0 until results.length()) {
            val pr = results.getJSONObject(index)
            if (pr.optString("state") == "open" || (pending != null &&
                    pr.optJSONObject("head")?.optString("sha") == pending.request.head &&
                    pr.optString("title") == pending.request.title &&
                    (pr.takeUnless { it.isNull("body") }?.optString("body").orEmpty()) == pending.request.body &&
                    runCatching { Instant.parse(pr.getString("created_at")).epochSecond >= pending.submittedAt }
                        .getOrDefault(false))) {
                return pr.getString("html_url")
            }
        }
        return null
    }

    fun openPr(name: String, branch: String, base: String, title: String, body: String, token: String): String =
        post(repoUrl(name, "pulls"), token,
            JSONObject().put("head", branch).put("base", base).put("title", title).put("body", body))
            .getString("html_url")

    private fun url(vararg parts: String): HttpUrl = origin.newBuilder().apply {
        parts.forEach { addPathSegment(it) }
    }.build()

    private fun repoUrl(name: String, vararg parts: String) =
        url("repos", name.substringBefore('/'), name.substringAfter('/'), *parts)

    private fun get(url: HttpUrl, token: String) = JSONObject(request(url, token).decodeToString())
    private fun post(url: HttpUrl, token: String, body: JSONObject) =
        JSONObject(request(url, token, body).decodeToString())

    private fun request(
        url: HttpUrl, token: String, json: JSONObject? = null,
        accept: String = "application/vnd.github+json", limit: Long = 16L * 1024 * 1024,
    ): ByteArray {
        val call = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", accept).header("X-GitHub-Api-Version", "2026-03-10")
        json?.let { call.post(it.toString().toRequestBody("application/json".toMediaType())) }
        http.newCall(call.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401 -> "GitHub rejected the token. Update it in the project’s GitHub settings."
                    403, 429 -> if (response.code == 429 || response.header("X-RateLimit-Remaining") == "0" ||
                        response.header("Retry-After") != null) "GitHub’s request limit was reached. Try again later."
                        else "GitHub denied this request. Check token permissions and repository rules."
                    404 -> "GitHub couldn’t find this repository, branch, or file. Check access and refresh."
                    409, 422 -> "GitHub couldn’t apply this change. Check the branch and repository rules."
                    else -> "GitHub returned HTTP ${response.code}. Try again later."
                }
                throw GitHubHttpError(response.code, message)
            }
            val source = response.body?.source() ?: throw IOException("GitHub returned an empty response.")
            if (source.request(limit + 1)) throw GitHubResponseTooLarge()
            return source.readByteArray()
        }
    }

    private fun sha(value: String): String {
        if (!value.matches(Regex("[0-9a-f]{40}"))) throw IOException("GitHub returned an invalid object ID.")
        return value
    }

    private companion object {
        const val UPDATE_REFS = "mutation(\$input: UpdateRefsInput!) { updateRefs(input: \$input) { clientMutationId } }"
    }
}
