package com.example.openrouteragent

import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class ProjectFileEntry(val path: String, val isDirectory: Boolean, val sizeBytes: Long? = null)

internal data class ProjectFileArea(val projectId: String?, val chatId: String) {
    init {
        require((projectId ?: chatId).matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid file folder." }
    }

    val relativeRoot = projectId?.let { "projects/$it" } ?: "chats/$chatId"
    val remotePrefix = projectId?.let { "projects/$it/" }.orEmpty()
}

/** App-private storage. All paths from UI, document pickers, and models pass the same boundary. */
internal class ProjectFileStore(private val root: File) {
    @Synchronized
    fun ensure(area: ProjectFileArea) {
        folder(resolve(area, "", allowRoot = true))
    }

    @Synchronized
    fun list(area: ProjectFileArea?, directory: String): List<ProjectFileEntry> {
        val current = resolve(area, directory, allowRoot = true)
        if (!current.exists()) return emptyList()
        require(current.isDirectory) { "Choose a folder." }
        return (current.listFiles() ?: throw IOException("Android could not list this folder."))
            .asSequence()
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .map { file ->
                val path = joinFilePath(directory, file.name)
                ProjectFileEntry(path, file.isDirectory, file.length().takeIf { file.isFile })
            }
            .sortedWith(compareBy<ProjectFileEntry> { !it.isDirectory }.thenBy { it.path.lowercase() }.thenBy { it.path })
            .toList()
    }

    @Synchronized
    fun createFolder(area: ProjectFileArea?, path: String) {
        folder(resolve(area, path))
    }

    @Synchronized
    fun preview(area: ProjectFileArea?, path: String): FilePreview =
        regularFile(area, path).source().buffer().use(::readFilePreview)

    @Synchronized
    fun writeText(area: ProjectFileArea?, path: String, text: String, previousText: String?) {
        val target = resolve(area, path)
        val bytes = editableTextBytes(text)
        if (previousText == null) {
            require(!target.exists()) { "A file or folder already exists at this path." }
        } else {
            require(completeText(area, path) == previousText) { "The file changed. Reopen it before saving." }
        }
        atomicWrite(target) { it.write(bytes) }
    }

    @Synchronized
    fun editText(area: ProjectFileArea, path: String, oldText: String, newText: String) {
        require(oldText.isNotEmpty()) { "Include the exact text to replace." }
        val current = completeText(area, path)
        val offset = current.indexOf(oldText)
        require(offset >= 0 && current.indexOf(oldText, offset + 1) < 0) {
            "The original text must appear exactly once."
        }
        val updated = current.replaceRange(offset, offset + oldText.length, newText)
        writeText(area, path, updated, current)
    }

    @Synchronized
    fun importCopy(area: ProjectFileArea?, path: String, openSource: () -> InputStream): String {
        val original = resolve(area, path)
        val directory = path.substringBeforeLast('/', "")
        val name = original.name
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && !name.startsWith(".$it") }
            ?.let { ".$it" }.orEmpty()
        val stem = name.removeSuffix(extension)
        var selected = path
        var copy = 2
        while (resolve(area, selected).exists()) {
            val suffix = " (${copy++})$extension"
            val prefixLength = if (directory.isEmpty()) 0 else directory.length + 1
            val stemRoom = 255 - prefixLength - suffix.length
            require(stemRoom > 0) { "Use a shorter folder or file name." }
            selected = joinFilePath(directory, stem.take(stemRoom) + suffix)
        }
        atomicWrite(resolve(area, selected)) { destination ->
            openSource().use { source ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val size = source.read(buffer)
                    if (size < 0) break
                    total += size
                    require(total <= WORKSPACE_FILE_MAX_BYTES) { "Files must be 100 MiB or smaller." }
                    destination.write(buffer, 0, size)
                }
            }
        }
        return selected
    }

    @Synchronized
    fun export(area: ProjectFileArea?, path: String, openDestination: () -> OutputStream) {
        regularFile(area, path).inputStream().use { source ->
            openDestination().use { source.copyTo(it) }
        }
    }

    private fun regularFile(area: ProjectFileArea?, path: String): File =
        resolve(area, path).also { require(it.isFile) { "The file was not found." } }

    private fun completeText(area: ProjectFileArea?, path: String): String {
        val preview = preview(area, path)
        require(!preview.truncated && preview.text != null) { "Editing requires a UTF-8 file no larger than 64 KiB." }
        return preview.text
    }

    private fun resolve(area: ProjectFileArea?, path: String, allowRoot: Boolean = false): File {
        if (path.isNotEmpty() || !allowRoot) validateFilePath(path)
        val relative = listOfNotNull(area?.relativeRoot, path.takeIf { it.isNotEmpty() }).joinToString("/")
        var target = root
        if (relative.isNotEmpty()) {
            for (segment in relative.split('/')) {
                target = File(target, segment)
                require(!Files.isSymbolicLink(target.toPath())) { "This path is outside the file folder." }
            }
        }
        require(target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            "This path is outside the file folder."
        }
        return target
    }

    private fun folder(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Android could not create this folder.")
    }

    private fun atomicWrite(target: File, write: (OutputStream) -> Unit) {
        val parent = checkNotNull(target.parentFile)
        folder(parent)
        val temporary = File.createTempFile(".writing-", ".tmp", parent)
        try {
            temporary.outputStream().use(write)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

internal fun joinFilePath(directory: String, name: String): String =
    if (directory.isEmpty()) name else "$directory/$name"

internal fun validateFilePath(path: String) {
    workspaceFilePathError(path)?.let { throw IllegalArgumentException(it) }
}

internal fun editableTextBytes(text: String): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.size <= FILE_PREVIEW_LIMIT && bytes.toString(Charsets.UTF_8) == text &&
        text.none { it < ' ' && it !in "\t\n\r\u000C" }) {
        "Use valid UTF-8 text no larger than 64 KiB."
    }
    return bytes
}
