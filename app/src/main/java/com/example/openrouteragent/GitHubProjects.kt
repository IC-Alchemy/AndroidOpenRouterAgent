package com.example.openrouteragent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.security.MessageDigest
import okio.ByteString.Companion.toByteString
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal data class GitHubListing(val entries: List<GitHubEntry>, val scope: String, val next: Int?)
internal data class GitHubAgentAccess(val project: String, val binding: String, var remainingBytes: Long)
internal data class GitHubReview(
    val project: String,
    val connection: String,
    val revision: Long,
    val repository: String,
    val branch: String,
    val commitMessage: String = "",
    val drafts: List<GitHubDraft> = emptyList(),
    val pullRequest: GitHubPullRequest? = null,
)

internal class GitHubProjects(
    directory: File,
    private val credentials: GitHubCredentials,
    private val api: GitHubClient = GitHubClient(),
    cacheLimit: Long = 32L * 1024 * 1024,
    private val runLimit: Long = 2L * 1024 * 1024,
) {
    private val store = GitHubProjectStore(directory, cacheLimit)
    private val mutex = Mutex()

    suspend fun project(id: String): GitHubProject? = locked { store.load(id) }

    suspend fun discover(url: String, token: String): GitHubRepo = locked {
        api.repository(gitHubRepoName(url), validateToken(token))
    }

    suspend fun connect(id: String, url: String, token: String, branch: String): GitHubProject = locked {
        require(store.load(id) == null) { "Disconnect the current repository first." }
        val name = gitHubRepoName(url)
        val credential = validateToken(token)
        val account = api.account(credential)
        val repo = api.repository(name, credential)
        val base = gitHubBranch(branch.trim().ifEmpty { repo.defaultBranch })
        val head = api.head(repo.name, base, credential)
        val state = GitHubProject(UUID.randomUUID().toString(), repo, account.id, account.login, base, head)
        rootTree(state) { credential }
        credentials.save(id, credential)
        try {
            store.save(id, state)
        } catch (error: IOException) {
            credentials.save(id, "")
            throw error
        }
        state
    }

    suspend fun replaceToken(id: String, newToken: String) = locked {
        val state = requireProject(id)
        val credential = validateToken(newToken)
        require(api.account(credential).id == state.accountId &&
            api.repository(state.repo.name, credential).id == state.repo.id) {
            "Use a token for ${state.login} with access to ${state.repo.name}."
        }
        credentials.save(id, credential)
        store.save(id, state.copy(revision = state.revision + 1))
    }

    suspend fun enableAgent(id: String, enabled: Boolean) = locked {
        val state = requireProject(id)
        store.save(id, state.copy(agentEnabled = enabled, revision = state.revision + 1))
    }

    suspend fun disconnect(id: String) = locked {
        val state = store.load(id) ?: return@locked
        require(state.drafts.isEmpty() && state.pending == null && state.pendingPr == null) {
            "Resolve any pending GitHub changes and publish or discard drafts in the project’s GitHub panel first."
        }
        credentials.save(id, "")
        store.remove(id)
        store.clearCache(state.binding)
    }

    suspend fun session(id: String): GitHubAgentAccess? = locked {
        store.load(id)?.takeIf { it.agentEnabled }?.let { GitHubAgentAccess(id, it.binding, runLimit) }
    }

    suspend fun list(
        id: String, directory: String = "", query: String = "", offset: Int = 0, access: GitHubAgentAccess? = null,
    ): GitHubListing = locked {
        require(offset >= 0 && query.length <= 255) { "Invalid path search." }
        if (directory.isNotEmpty()) gitHubPath(directory)
        val state = requireProject(id, access)
        val prefix = directory.takeIf(String::isNotEmpty)?.plus('/').orEmpty()
        val search = query.trim()
        val root = rootTree(state) { token(id) }
        val remote = when {
            directory.isEmpty() -> root.entries
            !root.truncated -> root.entries.filter { it.path.startsWith(prefix) }
            else -> folderTree(state, directory, id)
        }
        val draftEntries = state.drafts.filter { it.path.startsWith(prefix) }.flatMap { draft ->
            val folders = draft.path.split('/').dropLast(1).runningReduce { path, name -> "$path/$name" }
                .filter { it.startsWith(prefix) }
                .map { GitHubEntry(it, "040000", "tree", "") }
            folders + GitHubEntry(draft.path, draft.original?.mode ?: "100644", "blob", "",
                draft.text.toByteArray().size.toLong())
        }
        val entries = (remote + draftEntries).associateBy { it.path }.values
            .filter { entry ->
                val relative = entry.path.removePrefix(prefix)
                if (search.isEmpty()) '/' !in relative else entry.path.contains(search, ignoreCase = true)
            }
            .sortedWith(compareBy<GitHubEntry> { it.path.lowercase() }.thenBy { it.path })
        val page = entries.drop(offset).take(100)
        val scope = if (root.truncated) directory.ifEmpty { "Current folder" } else directory.ifEmpty { "Repository" }
        GitHubListing(page, scope, (offset + page.size).takeIf { page.isNotEmpty() && it < entries.size })
    }

    suspend fun read(id: String, path: String, access: GitHubAgentAccess? = null): String = locked {
        gitHubPath(path)
        val state = requireProject(id, access)
        state.drafts.firstOrNull { it.path == path }?.text
            ?: readOriginal(id, state, find(state, path, id), access)
    }

    suspend fun readUpstream(id: String, path: String): String? = locked {
        val state = requireProject(id)
        val entry = find(state, gitHubPath(path), id) ?: return@locked null
        readOriginal(id, state, entry, null)
    }

    suspend fun create(id: String, path: String, text: String, access: GitHubAgentAccess? = null) = locked {
        gitHubPath(path)
        editableTextBytes(text)
        val state = requireProject(id, access)
        editable(state)
        require(state.drafts.none { it.path == path || it.path.startsWith("$path/") || path.startsWith("${it.path}/") } &&
            find(state, path, id) == null) { "A file or folder already exists at this path." }
        path.split('/').dropLast(1).runningReduce { parent, part -> "$parent/$part" }.forEach { parent ->
            require(find(state, parent, id)?.type in listOf(null, "tree")) { "A file already occupies a parent path." }
        }
        saveDraft(id, state, GitHubDraft(path, null, "", text))
    }

    suspend fun edit(id: String, path: String, oldText: String, newText: String, access: GitHubAgentAccess? = null) = locked {
        gitHubPath(path)
        val state = requireProject(id, access)
        editable(state)
        val draft = state.drafts.firstOrNull { it.path == path } ?: run {
            val entry = find(state, path, id)
            val text = readOriginal(id, state, entry, access)
            GitHubDraft(path, entry, text, text)
        }
        require(!draft.conflict) { "Resolve the overlapping GitHub edit in the app first." }
        val start = draft.text.indexOf(oldText)
        require(if (oldText.isEmpty()) draft.text.isEmpty()
            else start >= 0 && draft.text.indexOf(oldText, start + 1) < 0) {
            "The original text must appear exactly once."
        }
        val updated = draft.text.replaceRange(start, start + oldText.length, newText)
        editableTextBytes(updated)
        saveDraft(id, state, draft.copy(text = updated))
    }

    suspend fun discard(id: String, path: String) = locked {
        val state = requireProject(id)
        editable(state)
        store.save(id, state.copy(drafts = state.drafts.filterNot { it.path == path }, revision = state.revision + 1))
    }

    suspend fun resolve(id: String, path: String, merged: String) = locked {
        val state = requireProject(id)
        editable(state)
        require(state.drafts.any { it.path == path && it.conflict }) { "This draft no longer has a conflict." }
        editableTextBytes(merged)
        val entry = find(state, gitHubPath(path), id)
        val original = if (entry == null) "" else readOriginal(id, state, entry, null)
        if (entry == null) {
            path.split('/').dropLast(1).runningReduce { parent, part -> "$parent/$part" }.forEach { parent ->
                require(find(state, parent, id)?.type in listOf(null, "tree")) { "A file occupies a parent path." }
            }
        }
        saveDraft(id, state, GitHubDraft(path, entry, original, merged))
    }

    suspend fun refresh(id: String): GitHubProject = locked {
        var state = requireProject(id)
        val token = token(id)
        state.pendingPr?.let { pending ->
            val url = api.existingPr(state.repo.name, pending.branch, pending.request.base, token, pending)
            if (url != null) {
                state = finishPr(id, state, url)
            }
        }
        state.pending?.let { pending ->
            val remote = api.ref(state.repo.name, pending.branch, token)
            if (remote != null && api.containsCommit(state.repo.name, pending.candidate.commit, remote, token)) {
                state = finish(id, state, pending)
            } else if (remote != pending.expected) {
                state = state.copy(pending = null, revision = state.revision + 1)
                store.save(id, state)
            }
        }
        val (branchHead, workBranch) = try {
            api.head(state.repo.name, state.branch, token) to state.workBranch
        } catch (error: GitHubHttpError) {
            if (error.code != 404 || state.workBranch == null) throw error
            api.head(state.repo.name, state.base, token) to null
        }
        if (branchHead == state.head && workBranch == state.workBranch) return@locked state
        val next = state.copy(
            head = branchHead, workBranch = workBranch, pending = null,
            pendingPr = state.pendingPr?.takeIf { it.branch == workBranch && it.request.head == branchHead.commit },
            revision = state.revision + 1,
        )
        rootTree(next) { token }
        val drafts = state.drafts.map { draft ->
            val upstream = find(next, draft.path, id)
            val same = upstream?.sha == draft.original?.sha && upstream?.mode == draft.original?.mode
            val blockedParent = if (upstream == null) {
                draft.path.split('/').dropLast(1).runningReduce { parent, part -> "$parent/$part" }.any { parent ->
                    find(next, parent, id)?.type !in listOf(null, "tree")
                }
            } else false
            draft.copy(conflict = !same || blockedParent)
        }
        next.copy(drafts = drafts).also {
            store.save(id, it)
            store.trimIndexes(it.binding, it.head.tree)
        }
    }

    suspend fun preparePublish(id: String, message: String, access: GitHubAgentAccess? = null): GitHubReview = locked {
        val state = requireProject(id, access)
        require(state.drafts.isNotEmpty()) { "There are no drafts to publish." }
        require(state.drafts.none { it.conflict }) { "Resolve overlapping changes before publishing." }
        require(state.pendingPr == null) { "Check the status of the last pull request before publishing." }
        val title = message.trim()
        require(title.isNotEmpty() && title.length <= 500) { "Enter a commit message of up to 500 characters." }
        GitHubReview(id, state.binding, state.revision, state.repo.name,
            state.pending?.branch ?: state.workBranch ?: "openrouter-agent/${UUID.randomUUID()}",
            state.pending?.message ?: title, state.drafts.sortedBy { it.path })
    }

    suspend fun preparePr(
        id: String, title: String, body: String, access: GitHubAgentAccess? = null,
    ): GitHubReview = locked {
        val state = requireProject(id, access)
        val branch = state.workBranch ?: throw IOException("Publish a commit before opening a pull request.")
        require(state.pending == null && state.drafts.isEmpty()) { "Publish or discard the current drafts first." }
        require(title.isNotBlank() && title.trim().length <= 200 && body.length <= 8000) {
            "Enter a title of up to 200 characters and a body of up to 8,000 characters."
        }
        GitHubReview(id, state.binding, state.revision, state.repo.name, branch,
            pullRequest = state.pendingPr?.request ?: GitHubPullRequest(state.head.commit, state.base, title.trim(), body))
    }

    // Called only with the review object displayed by Android's confirmation dialog.
    suspend fun submit(review: GitHubReview, access: GitHubAgentAccess? = null): String = locked {
        val state = requireProject(review.project, access)
        require(state.binding == review.connection && state.revision == review.revision &&
            state.repo.name == review.repository) { "The GitHub draft changed. Review it again." }
        if (review.pullRequest == null) publish(state, review) else openPr(state, review, review.pullRequest)
    }

    private suspend fun publish(state: GitHubProject, review: GitHubReview): String {
        val id = review.project
        val token = token(id)
        require(review.drafts.isNotEmpty() && review.drafts == state.drafts.sortedBy { it.path } &&
            review.drafts.none { it.conflict }) { "The GitHub draft changed. Review it again." }
        val repository = api.repository(state.repo.name, token)
        require(repository.id == state.repo.id && review.branch != repository.defaultBranch && review.branch != state.base &&
            review.branch.matches(Regex("openrouter-agent/[a-f0-9-]{36}")) &&
            (state.workBranch == null || state.workBranch == review.branch)) { "Choose a valid project work branch." }
        val expected = state.head.commit.takeIf { state.workBranch != null }
        val remote = api.ref(state.repo.name, review.branch, token)
        val submitted = state.pending
        if (submitted != null && remote != null &&
            api.containsCommit(state.repo.name, submitted.candidate.commit, remote, token)) {
            return checkNotNull(finish(id, state, submitted).lastLink)
        }
        if (remote != expected) {
            if (submitted != null) store.save(id, state.copy(pending = null, revision = state.revision + 1))
            throw IOException("The GitHub work branch moved. Refresh and review your drafts again.")
        }
        if (state.workBranch == null && api.ref(state.repo.name, state.base, token) != state.head.commit) {
            throw IOException("The GitHub base branch moved. Refresh and review your drafts again.")
        }
        coroutineContext.ensureActive()
        val pending = submitted ?: GitHubPendingCommit(review.branch, expected,
            api.candidate(state.repo.name, state.head, review.drafts, review.commitMessage, token), review.commitMessage)
            .also { store.save(id, state.copy(pending = it, revision = state.revision + 1)) }
        coroutineContext.ensureActive()
        try {
            api.advance(repository, pending.branch, pending.expected, pending.candidate.commit, token)
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            val latest = runCatching { api.ref(state.repo.name, pending.branch, token) }.getOrElse { throw error }
            if (latest != null && api.containsCommit(state.repo.name, pending.candidate.commit, latest, token)) {
                return checkNotNull(finish(id, state, pending).lastLink)
            }
            if (latest != pending.expected || (error is GitHubHttpError && error.code in listOf(401, 403, 409, 422))) {
                store.save(id, state.copy(pending = null, revision = state.revision + 2))
            }
            throw error
        }
        return checkNotNull(finish(id, state, pending).lastLink)
    }

    private fun finish(id: String, state: GitHubProject, pending: GitHubPendingCommit): GitHubProject {
        val next = state.copy(workBranch = pending.branch, head = pending.candidate, drafts = emptyList(), pending = null,
            revision = state.revision + 2,
            lastLink = "https://github.com/${state.repo.name}/commit/${pending.candidate.commit}")
        // The candidate was made from this index and these drafts. Keep the new snapshot usable offline.
        try {
            val previous = store.readTree(state.binding, state.head.tree, recursive = true, root = true)
            if (previous != null && !previous.truncated) {
                val entries = previous.entries.associateByTo(linkedMapOf()) { it.path }
                for (draft in state.drafts) {
                    val bytes = draft.text.toByteArray()
                    val hash = MessageDigest.getInstance("SHA-1")
                    hash.update(("blob ${bytes.size}" + Char.MIN_VALUE).toByteArray())
                    val oid = hash.digest(bytes).toByteString().hex()
                    entries[draft.path] = GitHubEntry(
                        draft.path, draft.original?.mode ?: "100644", "blob", oid, bytes.size.toLong(),
                    )
                    draft.path.split('/').dropLast(1).runningReduce { parent, name -> "$parent/$name" }.forEach {
                        entries.putIfAbsent(it, GitHubEntry(it, "040000", "tree", ""))
                    }
                    store.writeBlob(state.binding, oid, bytes)
                }
                store.writeTree(next.binding, next.head.tree, recursive = true,
                    entries = GitHubTree(entries.values.toList(), false), root = true)
            }
        } catch (_: IOException) {
            // Cache writes are optional. The remote commit has already been confirmed.
        }
        store.save(id, next)
        store.trimIndexes(next.binding, next.head.tree)
        return next
    }

    private suspend fun openPr(state: GitHubProject, review: GitHubReview, pr: GitHubPullRequest): String {
        val token = token(review.project)
        require(state.workBranch == review.branch && pr.base == state.base && state.head.commit == pr.head &&
            api.repository(state.repo.name, token).id == state.repo.id) {
            "The GitHub pull request changed. Review it again."
        }
        val id = review.project
        val existing = api.existingPr(state.repo.name, review.branch, pr.base, token, state.pendingPr)
        if (existing != null) return checkNotNull(finishPr(id, state, existing).lastLink)
        require(api.ref(state.repo.name, review.branch, token) == pr.head) {
            "The GitHub work branch moved. Refresh before opening a pull request."
        }
        val pending = state.pendingPr ?: GitHubPendingPr(review.branch, pr, Instant.now().epochSecond)
        coroutineContext.ensureActive()
        store.save(id, state.copy(pendingPr = pending, revision = state.revision + 1))
        val url = try {
            api.openPr(state.repo.name, review.branch, pr.base, pr.title, pr.body, token)
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            val recovered = api.existingPr(state.repo.name, review.branch, pr.base, token, pending)
            if (recovered == null) {
                if (error is GitHubHttpError && error.code in listOf(401, 403, 404, 409, 422)) {
                    store.save(id, state.copy(pendingPr = null, revision = state.revision + 2))
                }
                throw error
            }
            recovered
        }
        return checkNotNull(finishPr(id, state, url).lastLink)
    }

    private fun finishPr(id: String, state: GitHubProject, url: String): GitHubProject {
        require(url.startsWith("https://github.com/${state.repo.name}/pull/", ignoreCase = true)) {
            "GitHub returned an invalid pull request link."
        }
        return state.copy(lastLink = url, pendingPr = null, revision = state.revision + 2).also { store.save(id, it) }
    }

    private fun saveDraft(id: String, state: GitHubProject, draft: GitHubDraft) {
        val drafts = state.drafts.filterNot { it.path == draft.path }
        val updated = if (draft.original != null && draft.text == draft.originalText) drafts else drafts + draft
        require(updated.sumOf { it.text.toByteArray().size + it.originalText.toByteArray().size } <= 8 * 1024 * 1024 &&
            updated.size <= 128) { "Publish or discard some drafts before making more changes." }
        store.save(id, state.copy(drafts = updated, revision = state.revision + 1))
    }

    private fun editable(state: GitHubProject) {
        require(state.pending == null && state.pendingPr == null) {
            "Check the status of the last GitHub submission before editing these drafts."
        }
    }

    private fun requireProject(id: String, access: GitHubAgentAccess? = null): GitHubProject {
        val state = store.load(id) ?: throw IOException("Connect a GitHub repository to this project first.")
        if (access != null) require(access.project == id && access.binding == state.binding && state.agentEnabled) {
            "GitHub tools are off for this project."
        }
        return state
    }

    private fun readOriginal(id: String, state: GitHubProject, entry: GitHubEntry?, access: GitHubAgentAccess?): String {
        require(entry != null && entry.canRead) { "Choose a regular UTF-8 file up to 64 KiB." }
        val cached = store.readBlob(state.binding, entry.sha)
        val bytes = cached ?: run {
            if (access != null) require(access.remainingBytes >= checkNotNull(entry.size)) {
                "This run reached its GitHub download limit. Start a new message to download more."
            }
            api.blob(state.repo.name, entry.sha, token(id)).also {
                if (access != null) {
                    val remaining = access.remainingBytes - it.size
                    access.remainingBytes = remaining.coerceAtLeast(0)
                    require(remaining >= 0) { "This run reached its GitHub download limit." }
                }
            }
        }
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true).also(::editableTextBytes)
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("This file is binary. Open it on GitHub.")
        }
        if (cached == null) store.writeBlob(state.binding, entry.sha, bytes)
        return text
    }

    private fun find(state: GitHubProject, path: String, id: String): GitHubEntry? {
        val root = rootTree(state) { token(id) }
        return if (!root.truncated) root.entries.firstOrNull { it.path == path } else
            folderTree(state, path.substringBeforeLast('/', ""), id).firstOrNull { it.path == path }
    }

    private fun folderTree(state: GitHubProject, path: String, id: String): List<GitHubEntry> {
        var tree = state.head.tree
        for (name in path.split('/').filter(String::isNotEmpty)) {
            val entries = tree(state, tree, false) { token(id) }.entries
            tree = entries.firstOrNull { it.path == name && it.type == "tree" }?.sha ?: return emptyList()
        }
        val prefix = path.takeIf(String::isNotEmpty)?.plus('/').orEmpty()
        return tree(state, tree, false) { token(id) }.entries.map { it.copy(path = prefix + it.path) }
    }

    private fun rootTree(state: GitHubProject, credential: () -> String): GitHubTree {
        val full = tree(state, state.head.tree, true, credential)
        return if (!full.truncated) full else tree(state, state.head.tree, false, credential).copy(truncated = true)
    }

    private fun tree(state: GitHubProject, sha: String, recursive: Boolean, token: () -> String): GitHubTree {
        val root = sha == state.head.tree
        return store.readTree(state.binding, sha, recursive, root)
            ?: api.tree(state.repo.name, sha, recursive, token()).also {
                require(recursive || !it.truncated) { "GitHub could not list this folder completely." }
                store.writeTree(state.binding, sha, recursive, it, root)
            }
    }

    private fun token(id: String) = credentials.read(id).ifBlank { throw IOException("Add a GitHub token to continue.") }

    private suspend fun <T> locked(action: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { action() } }
}

private fun validateToken(token: String): String = token.trim().also {
    require(it.startsWith("github_pat_") && it.length in 12..512 && it.none(Char::isWhitespace)) {
        "Enter a fine-grained GitHub personal access token."
    }
}

private fun gitHubPath(path: String): String {
    validateFilePath(path)
    require(path.split('/').none { it.equals(".git", ignoreCase = true) }) { "Choose a repository file." }
    return path
}

private fun gitHubBranch(branch: String): String {
    require(branch.matches(Regex("[A-Za-z0-9_./-]{1,255}")) && !branch.startsWith('-') && ".." !in branch &&
        !branch.endsWith('.') && branch.split('/').none { it.isEmpty() || it.startsWith('.') || it.endsWith(".lock") }) {
        "Enter a valid GitHub branch name."
    }
    return branch
}
