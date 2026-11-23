package com.example.openrouteragent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface GitHubCredentials {
    fun read(id: String): String
    fun save(id: String, token: String)
}

@Serializable
internal data class GitHubDraft(
    val path: String,
    val original: GitHubEntry?,
    val originalText: String,
    val text: String,
    val conflict: Boolean = false,
)

@Serializable
internal data class GitHubPendingCommit(
    val branch: String,
    val expected: String?,
    val candidate: GitHubHead,
    val message: String,
)

@Serializable
internal data class GitHubPullRequest(val head: String, val base: String, val title: String, val body: String)

@Serializable
internal data class GitHubPendingPr(val branch: String, val request: GitHubPullRequest, val submittedAt: Long)

@Serializable
internal data class GitHubProject(
    val binding: String,
    val repo: GitHubRepo,
    val accountId: Long,
    val login: String,
    val base: String,
    val head: GitHubHead,
    val workBranch: String? = null,
    val agentEnabled: Boolean = false,
    val revision: Long = 0,
    val drafts: List<GitHubDraft> = emptyList(),
    val pending: GitHubPendingCommit? = null,
    val pendingPr: GitHubPendingPr? = null,
    val lastLink: String? = null,
) {
    val branch: String get() = workBranch ?: base
}

internal class GitHubProjectStore(private val root: File, private val cacheLimit: Long) {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastRoot: Pair<File, GitHubTree>? = null

    fun load(id: String): GitHubProject? = projectFile(id).takeIf(File::isFile)?.let {
        json.decodeFromString(it.readText())
    }

    fun save(id: String, state: GitHubProject) =
        write(projectFile(id), json.encodeToString(GitHubProject.serializer(), state).toByteArray())

    fun remove(id: String) {
        Files.deleteIfExists(projectFile(id).toPath())
    }

    fun readTree(binding: String, tree: String, recursive: Boolean, root: Boolean): GitHubTree? {
        val key = treeKey(tree, recursive)
        if (!root) return readCache(binding, key)?.let { json.decodeFromString(it.decodeToString()) }
        val file = File(indexDirectory(binding), key)
        return lastRoot?.takeIf { it.first == file }?.second ?: file.takeIf(File::isFile)?.let {
            json.decodeFromString<GitHubTree>(it.readText()).also { result -> lastRoot = file to result }
        }
    }

    fun writeTree(binding: String, tree: String, recursive: Boolean, entries: GitHubTree, root: Boolean) {
        val key = treeKey(tree, recursive)
        val bytes = json.encodeToString(GitHubTree.serializer(), entries).toByteArray()
        if (root) {
            val file = File(indexDirectory(binding), key)
            write(file, bytes)
            lastRoot = file to entries
        } else {
            writeCache(binding, key, bytes)
        }
    }

    fun trimIndexes(binding: String, tree: String) {
        val directory = indexDirectory(binding)
        directory.listFiles().orEmpty().filterNot { it.name.endsWith(tree) }.forEach { it.delete() }
    }

    fun readBlob(binding: String, sha: String) = readCache(binding, "blob-$sha")
    fun writeBlob(binding: String, sha: String, bytes: ByteArray) = writeCache(binding, "blob-$sha", bytes)

    fun clearCache(binding: String) {
        lastRoot = null
        for (directory in listOf(cacheDirectory(binding), indexDirectory(binding))) {
            directory.listFiles().orEmpty().forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(directory.toPath())
        }
    }

    private fun treeKey(sha: String, recursive: Boolean) = (if (recursive) "full-" else "tree-") + sha

    private fun projectFile(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid project." }
        return File(File(root, "projects"), "$id.json")
    }

    private fun indexDirectory(binding: String) = bindingDirectory("indexes", binding)

    private fun cacheDirectory(binding: String) = bindingDirectory("cache", binding)

    private fun bindingDirectory(kind: String, binding: String): File {
        require(binding.matches(Regex("[a-f0-9-]{36}"))) { "Invalid GitHub connection." }
        return File(File(root, kind), binding)
    }

    private fun readCache(binding: String, key: String): ByteArray? =
        File(cacheDirectory(binding), key).takeIf(File::isFile)?.let {
            it.setLastModified(System.currentTimeMillis())
            it.readBytes()
        }

    private fun writeCache(binding: String, key: String, bytes: ByteArray) {
        if (bytes.size > cacheLimit) return
        val target = File(cacheDirectory(binding), key)
        write(target, bytes)
        val files = File(root, "cache").listFiles().orEmpty().flatMap { it.listFiles().orEmpty().asList() }
            .filter(File::isFile)
        var size = files.sumOf(File::length)
        for (file in files.filter { it != target }.sortedBy(File::lastModified)) {
            if (size <= cacheLimit) break
            val removed = file.length()
            if (Files.deleteIfExists(file.toPath())) size -= removed
        }
    }

    private fun write(target: File, content: ByteArray) {
        val parent = checkNotNull(target.parentFile)
        Files.createDirectories(parent.toPath())
        val temporary = Files.createTempFile(parent.toPath(), ".incoming-", ".tmp").toFile()
        try {
            temporary.outputStream().use { stream ->
                stream.write(content)
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }
}
