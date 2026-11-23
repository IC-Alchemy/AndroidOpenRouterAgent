package com.example.openrouteragent

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal val githubActions = setOf("github_list_files", "github_read_files", "github_create_file", "github_edit_file",
    "github_publish", "github_open_pull_request")

internal suspend fun GitHubProjects.performAction(
    access: GitHubAgentAccess,
    action: String,
    input: JSONObject,
    review: suspend (GitHubReview, GitHubAgentAccess) -> String,
): JSONObject {
    val project = access.project
    return when (action) {
        "github_list_files" -> {
            val result = list(project, input.optString("directory"), input.optString("query"), input.optInt("offset"), access)
            JSONObject().put("scope", result.scope).put("nextOffset", result.next ?: JSONObject.NULL)
                .put("entries", JSONArray().apply {
                    result.entries.forEach {
                        put(JSONObject().put("path", it.path).put("type", it.type)
                            .put("size", it.size ?: JSONObject.NULL).put("canRead", it.canRead))
                    }
                })
        }
        "github_read_files" -> {
            val paths = input.getJSONArray("paths")
            require(paths.length() in 1..8) { "Read between one and eight files at a time." }
            JSONObject().put("files", JSONArray().apply {
                for (i in 0 until paths.length()) {
                    val path = paths.getString(i)
                    val item = JSONObject().put("path", path)
                    try {
                        item.put("text", read(project, path, access))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        item.put("error", error.githubMessage())
                    }
                    put(item)
                }
            })
        }
        "github_create_file" -> {
            val path = input.getString("path")
            create(project, path, input.getString("text"), access)
            JSONObject().put("draft", path)
        }
        "github_edit_file" -> {
            val path = input.getString("path")
            edit(project, path, input.getString("oldText"), input.getString("newText"), access)
            JSONObject().put("draft", path)
        }
        "github_publish" -> JSONObject().put("commit", review(preparePublish(project, input.getString("message"), access), access))
        "github_open_pull_request" -> JSONObject().put("pullRequest",
            review(preparePr(project, input.getString("title"), input.optString("body"), access), access))
        else -> throw IllegalArgumentException("Unknown GitHub tool.")
    }
}

internal fun Throwable.githubMessage(): String = when (this) {
    is GitHubHttpError, is IllegalArgumentException, is IllegalStateException -> message ?: "GitHub could not complete this action."
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
        "GitHub couldn’t be reached. Try again when connected."
    is java.io.IOException -> if (javaClass == java.io.IOException::class.java) message ?: "GitHub could not finish the request."
        else "GitHub could not finish the request. Check your connection and retry."
    else -> "GitHub could not complete this action."
}
