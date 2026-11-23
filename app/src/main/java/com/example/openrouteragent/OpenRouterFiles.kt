package com.example.openrouteragent

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal const val MAX_WORKSPACE_ATTACHMENTS = 20

internal data class OpenRouterRunFiles(
    val projectFolder: String? = null,
    val projectSandbox: String? = null,
    val attachments: List<WorkspaceFileRef> = emptyList(),
    val github: Boolean = false,
)

internal fun projectSandboxId(projectId: String, config: AgentConfig): String {
    val inputs = listOf(projectId, config.apiKey.trim(), config.sandboxDomains().joinToString(","))
    return "studio_" + hash(inputs.joinToString("\n")).take(30)
}

internal fun sandboxRelativePath(path: String): String =
    path.removePrefix("/workspace/home/").removePrefix("~/")

private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal class OpenRouterFiles(context: Context, private val client: OpenRouterAgentClient) {
    private val resolver = context.contentResolver
    private val privateDir = context.noBackupFilesDir
    private val cacheDir = context.cacheDir
    val store = ProjectFileStore(File(privateDir, "files"))

    suspend fun importFile(area: ProjectFileArea?, uri: Uri, folder: String): String = withContext(Dispatchers.IO) {
        val name = safeTransferName(resolver.documentDisplayName(uri) ?: "imported-file")
        store.importCopy(area, joinFilePath(folder, name)) {
            resolver.openDocumentInput(uri)
        }
    }

    suspend fun exportFile(area: ProjectFileArea?, path: String, uri: Uri) = resolver.writeCreatedDocument(uri) {
        store.export(area, path) { resolver.openDocumentOutput(uri) }
    }

    suspend fun upload(apiKey: String, area: ProjectFileArea, path: String): WorkspaceFileRef {
        val filename = area.remotePrefix + path
        validateFilePath(filename)
        return withTransferFile { transfer ->
            withContext(Dispatchers.IO) { store.export(area, path) { transfer.outputStream() } }
            uploadTransfer(apiKey, filename, transfer)
        }
    }

    suspend fun saveWorkspaceRevision(apiKey: String, file: WorkspaceFileRef, text: String): WorkspaceFileRef {
        validateFilePath(file.filename)
        val bytes = editableTextBytes(text)
        require(bytes.isNotEmpty()) { "OpenRouter workspace files cannot be empty." }
        return withTransferFile { transfer ->
            transfer.writeBytes(bytes)
            uploadTransfer(apiKey, file.filename, transfer)
        }
    }

    suspend fun previewUpload(apiKey: String, file: WorkspaceFileRef): FilePreview? = withContext(Dispatchers.IO) {
        uploadCopy(apiKey, file.id).takeIf(File::isFile)?.source()?.buffer()?.use(::readFilePreview)
    }

    suspend fun exportUpload(apiKey: String, file: WorkspaceFileRef, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val cached = uploadCopy(apiKey, file.id)
        if (!cached.isFile) return@withContext false
        cached.inputStream().use { source -> resolver.openDocumentOutput(uri).use { source.copyTo(it) } }
        true
    }

    suspend fun forgetUpload(apiKey: String, file: WorkspaceFileRef) = withContext(Dispatchers.IO) {
        uploadCopy(apiKey, file.id).delete()
    }

    suspend fun copyWorkspaceFile(apiKey: String, area: ProjectFileArea, file: WorkspaceFileRef): String {
        val path = file.filename.removePrefix(area.remotePrefix)
        val filename = path.takeIf { workspaceFilePathError(it) == null }
            ?: safeTransferName(file.filename.substringAfterLast('/'))
        return if (file.downloadable) withTransferFile { transfer ->
            client.downloadWorkspaceFile(apiKey, file.id) { boundedTransfer(transfer) }
            withContext(Dispatchers.IO) { store.importCopy(area, filename) { transfer.inputStream() } }
        } else withContext(Dispatchers.IO) {
            val cached = uploadCopy(apiKey, file.id)
            require(cached.isFile) { "This upload can be attached to the sandbox. Its original copy is unavailable on this device." }
            store.importCopy(area, filename) { cached.inputStream() }
        }
    }

    suspend fun copySandboxFile(
        apiKey: String, area: ProjectFileArea, containerId: String, fileId: String, path: String
    ): String = withTransferFile { transfer ->
        val relative = sandboxRelativePath(path).removePrefix(area.remotePrefix)
        validateFilePath(relative)
        client.downloadFile(apiKey, containerId, fileId) { boundedTransfer(transfer) }
        withContext(Dispatchers.IO) { store.importCopy(area, relative) { transfer.inputStream() } }
    }

    suspend fun saveSandboxToWorkspace(
        apiKey: String, area: ProjectFileArea, containerId: String, fileId: String, path: String
    ): WorkspaceFileRef {
        val relative = sandboxRelativePath(path)
        if (relative.startsWith(area.remotePrefix)) {
            return client.promoteContainerFile(apiKey, containerId, fileId)
        }
        val filename = area.remotePrefix + relative
        validateFilePath(filename)
        return withTransferFile { transfer ->
            client.downloadFile(apiKey, containerId, fileId) { boundedTransfer(transfer) }
            uploadTransfer(apiKey, filename, transfer)
        }
    }

    private suspend fun uploadTransfer(apiKey: String, filename: String, transfer: File): WorkspaceFileRef {
        val created = client.uploadWorkspaceFile(apiKey, filename, transfer)
        // Uploaded files cannot be downloaded from the Files API. Keep the exact uploaded bytes.
        withContext(Dispatchers.IO + NonCancellable) {
            val copy = uploadCopy(apiKey, created.id)
            try {
                Files.createDirectories(checkNotNull(copy.parentFile).toPath())
                Files.move(transfer.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (error: IOException) {
                throw IOException("The upload completed. Android could not retain a local copy.", error)
            }
        }
        return created
    }

    private fun uploadCopy(apiKey: String, id: String): File {
        require(id.matches(Regex("or_file_[A-Za-z0-9_-]{1,200}"))) { "Invalid OpenRouter file ID." }
        return File(privateDir, "workspace-uploads/${hash(apiKey.trim())}/$id")
    }

    private suspend fun <T> withTransferFile(block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("file-transfer-", ".tmp", cacheDir)
        try {
            block(temporary)
        } finally {
            temporary.delete()
        }
    }
}

private fun safeTransferName(name: String): String = name.replace(Regex("[<>:\"|?*\\\\/\\p{Cntrl}]"), "_")
    .trim().take(180).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "imported-file"

private fun boundedTransfer(file: File): OutputStream = object : OutputStream() {
    private val stream = file.outputStream()
    private var written = 0L

    private fun checkSize(count: Int) {
        written += count
        require(written <= WORKSPACE_FILE_MAX_BYTES) { "Files must be 100 MiB or smaller." }
    }

    override fun write(byte: Int) {
        checkSize(1)
        stream.write(byte)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkSize(length)
        stream.write(bytes, offset, length)
    }

    override fun close() = stream.close()
}

internal val projectFileActions = setOf("list_project_files", "read_project_file", "write_project_file", "edit_project_file")

internal fun ProjectFileStore.performAction(area: ProjectFileArea, name: String, args: JSONObject): JSONObject {
    if (name == "list_project_files") {
        val directory = args.optString("directory")
        val files = list(area, directory)
        val offset = args.optInt("offset").coerceIn(0, files.size)
        val page = files.drop(offset).take(100)
        return JSONObject().put("directory", directory).put("files", JSONArray(page.map { file ->
            JSONObject().put("path", file.path).put("isDirectory", file.isDirectory).put("sizeBytes", file.sizeBytes)
        })).put("nextOffset", (offset + page.size).takeIf { it < files.size })
    }
    val path = args.getString("path")
    return when (name) {
        "read_project_file" -> {
            val result = preview(area, path)
            val text = requireNotNull(result.text) { "This file is not UTF-8 text. Open it in Files or use the hosted sandbox." }
            JSONObject().put("path", path).put("text", text).put("truncated", result.truncated)
        }
        "write_project_file" -> {
            writeText(area, path, args.getString("text"), null)
            JSONObject().put("path", path).put("created", true)
        }
        "edit_project_file" -> {
            editText(area, path, args.getString("oldText"), args.getString("newText"))
            JSONObject().put("path", path).put("edited", true)
        }
        else -> throw IllegalArgumentException("Unknown file operation.")
    }
}
