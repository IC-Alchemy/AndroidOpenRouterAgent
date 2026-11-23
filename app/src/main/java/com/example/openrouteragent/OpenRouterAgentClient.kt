package com.example.openrouteragent

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal const val FILE_PREVIEW_LIMIT = 64 * 1024L
internal const val WORKSPACE_FILE_MAX_BYTES = 100L * 1024 * 1024
private val workspaceFileIdPattern = Regex("or_file_[A-Za-z0-9_-]+")
private val containerIdPattern = Regex("[A-Za-z0-9_-]{1,40}")
private val containerFileIdPattern = Regex("cfile_[A-Za-z0-9_-]+")

data class ContainerFilePage(val files: List<ContainerFileRef>, val after: String?)
data class FilePreview(val text: String?, val truncated: Boolean = false)
internal data class WorkspaceFileRef(
    val id: String,
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val downloadable: Boolean,
    val createdAt: String? = null
)
internal data class WorkspaceFilePage(val files: List<WorkspaceFileRef>, val cursor: String?)

internal fun workspaceFilePathError(path: String): String? = when {
    path.length !in 1..255 -> "File paths must contain 1 to 255 characters."
    path.split('/').any { it.isEmpty() || it == "." || it == ".." } ->
        "Enter a relative file path without empty, . or .. segments."
    path.any { it in "<>:\"|?*\\" || it.isISOControl() } || !Charsets.UTF_8.newEncoder().canEncode(path) ->
        "The file path contains characters that OpenRouter does not allow."
    else -> null
}

internal fun readFilePreview(source: BufferedSource): FilePreview {
    val truncated = source.request(FILE_PREVIEW_LIMIT + 1)
    val bytes = source.readByteArray(minOf(source.buffer.size, FILE_PREVIEW_LIMIT))
    val characters = CharBuffer.allocate(bytes.size)
    val decoder = Charsets.UTF_8.newDecoder()
    val result = decoder.decode(ByteBuffer.wrap(bytes), characters, !truncated)
    characters.flip()
    val text = characters.toString()
    val binary = result.isError || text.any { it < ' ' && it !in "\t\n\r\u000C" }
    return FilePreview(if (binary) null else text, truncated)
}
internal class ApiStatusException(val code: Int, message: String) : IOException(message)

class OpenRouterAgentClient(
    private val baseUrl: HttpUrl = "https://openrouter.ai/api/v1/".toHttpUrl(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.MINUTES)
        .callTimeout(12, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build(),
    /** Listing and previewing sandbox files should answer quickly; exports keep the long limits above. */
    private val previewTimeoutSeconds: Long = 30,
    private val log: (String) -> Unit = { runCatching { android.util.Log.i("AgentFiles", it) } },
) {
    private val quick: OkHttpClient = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(previewTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(previewTimeoutSeconds * 2, TimeUnit.SECONDS)
        .build()

    suspend fun availableModels(apiKey: String): List<CatalogModel> =
        execute(request(apiKey, endpoint("models", "user")).build(), apiKey) { parseModelCatalog(it.bodyText()) }

    suspend fun validateKey(apiKey: String) {
        execute(request(apiKey, endpoint("key")).build(), apiKey) { Unit }
    }

    internal suspend fun listWorkspaceFiles(apiKey: String, cursor: String?): WorkspaceFilePage {
        val url = endpoint("files").newBuilder()
            .addQueryParameter("limit", "100")
            .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
            .build()
        return execute(request(apiKey, url).build(), apiKey) {
            val body = JSONObject(it.bodyText())
            val data = body.getJSONArray("data")
            val files = List(data.length()) { index -> parseWorkspaceFile(data.getJSONObject(index)) }
            val next = if (body.optBoolean("has_more")) {
                body.string("cursor")?.takeIf { it != cursor }
                    ?: throw IOException("OpenRouter did not return a new workspace files cursor.")
            } else null
            WorkspaceFilePage(files, next)
        }
    }

    internal suspend fun workspaceFile(apiKey: String, fileId: String): WorkspaceFileRef =
        executeWorkspaceFileRequest(request(apiKey, workspaceFileEndpoint(fileId)).build(), apiKey)

    internal suspend fun uploadWorkspaceFile(apiKey: String, filename: String, file: File): WorkspaceFileRef {
        workspaceFilePathError(filename)?.let { throw IllegalArgumentException(it) }
        require(file.isFile) { "The file to upload is unavailable." }
        val size = file.length()
        require(size > 0) { "OpenRouter cannot upload an empty file." }
        require(size <= WORKSPACE_FILE_MAX_BYTES) { "OpenRouter limits uploads to 100 MiB." }
        val part = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                file.source().use { source ->
                    sink.write(source, size)
                    if (source.read(Buffer(), 1) != -1L) throw IOException("The upload file size changed.")
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, part)
            .build()
        return executeWorkspaceFileRequest(request(apiKey, endpoint("files")).post(body).build(), apiKey)
    }

    internal suspend fun previewWorkspaceFile(apiKey: String, fileId: String): FilePreview =
        execute(request(apiKey, workspaceFileEndpoint(fileId, "content")).build(), apiKey, read = ::readFilePreview)

    internal suspend fun downloadWorkspaceFile(apiKey: String, fileId: String, openDestination: () -> OutputStream) =
        execute(request(apiKey, workspaceFileEndpoint(fileId, "content")).build(), apiKey) {
            copyFileContent(it, openDestination)
        }

    internal suspend fun deleteWorkspaceFile(apiKey: String, fileId: String) {
        execute(request(apiKey, workspaceFileEndpoint(fileId)).delete().build(), apiKey) { Unit }
    }

    internal suspend fun promoteContainerFile(apiKey: String, containerId: String, fileId: String): WorkspaceFileRef {
        require(containerIdPattern.matches(containerId)) { "Invalid sandbox container ID." }
        require(containerFileIdPattern.matches(fileId)) { "Invalid sandbox file ID." }
        val url = endpoint("containers", containerId, "files", fileId, "promote")
        return executeWorkspaceFileRequest(request(apiKey, url).post(ByteArray(0).toRequestBody()).build(), apiKey)
    }

    private fun workspaceFileEndpoint(fileId: String, vararg suffix: String): HttpUrl {
        require(workspaceFileIdPattern.matches(fileId)) { "Invalid workspace file ID." }
        return endpoint("files", fileId, *suffix)
    }

    private suspend fun executeWorkspaceFileRequest(request: Request, apiKey: String): WorkspaceFileRef =
        execute(request, apiKey) { parseWorkspaceFile(JSONObject(it.bodyText())) }

    private fun parseWorkspaceFile(file: JSONObject): WorkspaceFileRef {
        val id = file.string("id")?.takeIf(workspaceFileIdPattern::matches)
            ?: throw IOException("OpenRouter returned an invalid workspace file ID.")
        val filename = (file.opt("filename") as? String)?.takeIf { it.isNotEmpty() }
            ?: throw IOException("OpenRouter returned a workspace file without a filename.")
        return WorkspaceFileRef(
            id, filename, file.string("mime_type"), file.optLong("size_bytes", -1).takeIf { it >= 0 },
            file.optBoolean("downloadable"), file.string("created_at")
        )
    }

    suspend fun listFiles(apiKey: String, containerId: String, after: String?): ContainerFilePage {
        val url = endpoint("containers", containerId, "files").newBuilder()
            .addQueryParameter("limit", "100")
            .apply { if (after != null) addQueryParameter("after", after) }
            .build()
        return execute(request(apiKey, url).build(), apiKey, quick) {
            val body = JSONObject(it.bodyText())
            val files = body.optJSONArray("data").objects().mapNotNull { file ->
                parseFile(file)
            }.toList()
            val next = body.string("last_id").takeIf { body.optBoolean("has_more") && it != after }
            ContainerFilePage(files, next)
        }
    }

    /**
     * Reads at most 64 KB of a hosted sandbox file. The file lives in OpenRouter's container, not on the
     * phone, so "missing" is an HTTP 404 and a hang is bounded by [previewTimeoutSeconds].
     */
    suspend fun previewFile(apiKey: String, containerId: String, fileId: String, path: String = fileId): FilePreview {
        val where = "container=$containerId file=$fileId path=$path"
        log("preview-start $where")
        val started = System.nanoTime()
        return try {
            execute(fileRequest(apiKey, containerId, fileId), apiKey, quick) { response ->
                val body = response.body ?: throw IOException("OpenRouter returned an empty file response.")
                val preview = readFilePreview(body.source())
                log("preview-done $where exists=true size=${body.contentLength()} " +
                    "truncated=${preview.truncated} encoding=${if (preview.text == null) "binary" else "utf-8"} " +
                    "ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}")
                preview
            }
        } catch (e: ApiStatusException) {
            log("preview-failed $where status=${e.code}")
            if (e.code != 404) throw e
            throw java.io.FileNotFoundException("The sandbox file $path no longer exists in container $containerId. " +
                "Hosted sandbox files are deleted when the container expires, and the model may have cited a file " +
                "it never wrote. Run the command again to recreate it, or open Sandbox files to see what exists.")
        } catch (e: java.io.InterruptedIOException) {
            log("preview-timeout $where after=${previewTimeoutSeconds}s")
            throw IOException("OpenRouter did not return the sandbox file $path within $previewTimeoutSeconds s. " +
                "Close the preview and try again, or save the file instead.", e)
        }
    }

    suspend fun downloadFile(apiKey: String, containerId: String, fileId: String, openDestination: () -> OutputStream) {
        execute(fileRequest(apiKey, containerId, fileId), apiKey) { copyFileContent(it, openDestination) }
    }

    private fun readFilePreview(response: Response): FilePreview {
        val body = response.body ?: throw IOException("OpenRouter returned an empty file response.")
        return readFilePreview(body.source())
    }

    private fun copyFileContent(response: Response, openDestination: () -> OutputStream) {
        val body = response.body ?: throw IOException("OpenRouter returned an empty file response.")
        openDestination().use { destination ->
            body.byteStream().use { it.copyTo(destination) }
        }
    }

    private fun fileRequest(apiKey: String, containerId: String, fileId: String): Request =
        request(apiKey, endpoint("containers", containerId, "files", fileId, "content")).build()

    private fun endpoint(vararg segments: String): HttpUrl =
        baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun request(apiKey: String, url: HttpUrl): Request.Builder {
        require(apiKey.isNotBlank()) { "Add your OpenRouter API key in Settings." }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("X-OpenRouter-Title", "OpenRouter Sandbox Agent")
    }

    private suspend fun <T> execute(
        request: Request, apiKey: String, client: OkHttpClient = http, read: (Response) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        val result = runCatching {
                            if (!it.isSuccessful) throw apiError(it, apiKey)
                            read(it)
                        }
                        continuation.resumeWith(result)
                    }
                }
            })
        }

    private fun apiError(response: Response, apiKey: String): IOException {
        val raw = response.peekBody(8192).string()
        val body = runCatching { JSONObject(raw) }.getOrNull()
        val detail = body?.optJSONObject("error")?.string("message") ?: body?.string("message")
        val fallback = when (response.code) {
            401 -> "Check your API key in Settings."
            402 -> "Check your OpenRouter credit balance."
            403 -> "Check workspace tool permissions."
            429 -> "Rate limit reached. Wait a moment before retrying."
            else -> response.message
        }
        val message = (detail ?: fallback).replace(apiKey.trim(), "[API key]").take(500)
        return ApiStatusException(response.code, "OpenRouter HTTP ${response.code}. $message")
    }

    private fun Response.bodyText(): String =
        body?.string()?.takeIf { it.isNotBlank() } ?: throw IOException("OpenRouter returned an empty response.")
}

internal fun parseAgentResult(raw: String): AgentRunResult {
    val root = JSONObject(raw)
    val status = root.string("status")
    val error = root.optJSONObject("error")
    if (error != null || status in setOf("failed", "cancelled", "canceled", "queued", "in_progress")) {
        val detail = error?.string("message") ?: status ?: "unknown error"
        throw IOException("OpenRouter run did not complete. ${detail.take(500)}")
    }

    val finalText = mutableListOf<String>()
    val intermediateText = mutableListOf<String>()
    val summary = mutableListOf<String>()
    val citations = mutableListOf<WebCitation>()
    val shellSteps = mutableListOf<ShellExecutionStep>()
    val calls = mutableMapOf<String, Pair<List<String>, String?>>()
    val searchQueries = mutableListOf<String>()
    var containerId: String? = null
    for (item in root.optJSONArray("output").objects()) {
        when (item.string("type")) {
            "message" -> {
                val parts = item.optJSONArray("content").objects()
                val text = parts.mapNotNull { part ->
                    when (part.string("type")) {
                        "output_text", "text" -> part.string("text")
                        "refusal" -> part.string("refusal")
                        else -> null
                    }
                }.joinToString("\n").takeIf { it.isNotBlank() }
                if (text != null) {
                    if (item.string("phase") == "commentary") intermediateText.add(text) else finalText.add(text)
                }
                parts.flatMap { it.optJSONArray("annotations").objects() }.forEach { annotation ->
                    val source = annotation.optJSONObject("url_citation") ?: annotation
                    val url = source.string("url")
                    if (url != null && url.toHttpUrlOrNull() != null) {
                        citations.add(WebCitation(source.string("title") ?: url, url))
                    }
                }
            }
            "reasoning" -> item.optJSONArray("summary").objects().mapNotNull { it.string("text") }.forEach(summary::add)
            "openrouter:shell", "shell_call", "shell_call_output" -> {
                val outputs = item.optJSONArray("output").objects().toList()
                val action = item.optJSONObject("action")
                val callId = item.string("call_id") ?: item.string("id")
                val prior = calls[callId]
                val commands = action?.optJSONArray("commands").strings().toList().ifEmpty { prior?.first.orEmpty() }
                val id = item.string("container_id")
                    ?: action?.optJSONObject("environment")?.string("container_id")
                    ?: outputs.firstNotNullOfOrNull { it.string("container_id") }
                    ?: prior?.second ?: containerId
                val files = (fileRefs(item.optJSONArray("files")) + outputs.flatMap { fileRefs(it.optJSONArray("files")) })
                    .distinctBy { it.fileId }
                containerId = id
                if (item.string("type") == "shell_call" && outputs.isEmpty() && files.isEmpty() && callId != null) {
                    calls[callId] = commands to id
                } else {
                    val results = outputs.mapIndexed { index, output ->
                        val outcome = output.optJSONObject("outcome")
                        val label = when (val type = outcome?.string("type")) {
                            "exit" -> if (outcome.has("exit_code")) "exit ${outcome.optInt("exit_code")}" else "exit"
                            else -> type ?: output.string("outcome") ?: "done"
                        }
                        ShellCommandResult(
                            output.string("command") ?: commands.getOrElse(index) { "Command ${index + 1}" },
                            limitedOutput(output.optString("stdout")), limitedOutput(output.optString("stderr")), label
                        )
                    }
                    if (callId != null) calls.remove(callId)
                    if (commands.isNotEmpty() || results.isNotEmpty() || files.isNotEmpty()) {
                        shellSteps.add(ShellExecutionStep(id, commands, results, files))
                    }
                }
            }
            "openrouter:web_search", "web_search_call" -> {
                val action = item.optJSONObject("action")
                val query = action?.string("query") ?: item.string("query")
                if (query != null) searchQueries.add(query)
                searchQueries.addAll(action?.optJSONArray("queries").strings())
            }
        }
    }
    calls.values.forEach { (commands, id) ->
        if (commands.isNotEmpty()) shellSteps.add(ShellExecutionStep(id, commands, emptyList(), emptyList()))
    }
    val usage = root.optJSONObject("usage")?.let {
        TokenUsage(it.optInt("input_tokens"), it.optInt("output_tokens"), it.optInt("total_tokens"),
            it.optDouble("cost").takeIf { cost -> cost.isFinite() }, it.optInt("cached_tokens"), it.optInt("reasoning_tokens"))
    }
    val text = finalText.ifEmpty { intermediateText }.joinToString("\n\n").ifBlank {
        root.string("output_text") ?: when {
            shellSteps.isNotEmpty() -> "The sandbox returned tool results."
            else -> "OpenRouter did not return a text answer."
        }
    }
    val reason = root.optJSONObject("incomplete_details")?.string("reason")
    val notice = when {
        root.optBoolean("stopped_for_repetition") -> "The agent stopped after detecting repeated steps."
        status == "incomplete" -> "OpenRouter returned a partial response${reason?.let { " (${it.replace('_', ' ')})" }.orEmpty()}."
        else -> null
    }
    return AgentRunResult(text, summary.joinToString("\n").ifBlank { null }, shellSteps,
        searchQueries.distinct(), citations.distinctBy { it.url }, containerId, usage, root.string("model"),
        notice)
}

private fun limitedOutput(text: String): String =
    if (text.length > 24_000) text.take(24_000) + "\n[Output shortened]" else text

private fun parseFile(file: JSONObject): ContainerFileRef? {
    val id = file.string("file_id") ?: file.string("id") ?: return null
    val path = file.string("path") ?: file.string("filename") ?: id
    return ContainerFileRef(id, path, file.optLong("bytes", -1).takeIf { it >= 0 })
}

private fun fileRefs(array: JSONArray?): List<ContainerFileRef> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        when (val file = array.opt(index)) {
            is JSONObject -> parseFile(file)
            is String -> file.takeIf { it.startsWith("cfile_") }?.let { ContainerFileRef(it, it) }
            else -> null
        }
    }
}

private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
private fun JSONArray?.objects(): Sequence<JSONObject> =
    if (this == null) emptySequence() else (0 until length()).asSequence().mapNotNull { optJSONObject(it) }
private fun JSONArray?.strings(): Sequence<String> =
    if (this == null) emptySequence() else (0 until length()).asSequence().mapNotNull { opt(it) as? String }

internal fun openRouterError(payload: JSONObject): String? = when (val error = payload.opt("error")) {
    is JSONObject -> error.optString("message").ifBlank { "OpenRouter returned an error." }
    is String -> error.takeIf(String::isNotBlank)
    else -> (payload.opt("message") as? String)?.takeIf { payload.optJSONArray("choices") == null }
}
