package com.example.openrouteragent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
internal fun GitHubProjectDialog(project: ChatProject, github: GitHubProjects, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var state by remember(project.id) { mutableStateOf<GitHubProject?>(null) }
    var ready by remember(project.id) { mutableStateOf(false) }
    var busy by remember(project.id) { mutableStateOf(false) }
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    var folder by remember(project.id) { mutableStateOf("") }
    var searchInput by remember(project.id) { mutableStateOf("") }
    var query by remember(project.id) { mutableStateOf("") }
    var listing by remember(project.id) { mutableStateOf<GitHubListing?>(null) }
    var generation by remember(project.id) { mutableStateOf(0) }
    var review by remember(project.id) { mutableStateOf<GitHubReview?>(null) }
    var file by remember(project.id) { mutableStateOf<Pair<String, String>?>(null) }
    var resolve by remember(project.id) { mutableStateOf<DraftResolution?>(null) }
    var dialog by remember(project.id) { mutableStateOf("") }

    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                error = problem.githubMessage()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(project.id, folder, query, generation) {
        error = null
        listing = null
        try {
            state = github.project(project.id)
            if (state != null) listing = github.list(project.id, folder, query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            error = problem.githubMessage()
        } finally {
            ready = true
        }
    }

    Dialog(onDismissRequest = { if (!busy) onClose() }) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("GitHub", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose, enabled = !busy) { Text("Close") }
                }
                if (busy || !ready || (state != null && listing == null && error == null)) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (ready && state == null) {
                    GitHubConnectForm(project.name, busy, onDiscover = { url, token, onResult ->
                        work { onResult(github.discover(url, token)) }
                    }) { url, token, branch ->
                        work { state = github.connect(project.id, url, token, branch); generation++ }
                    }
                }
                state?.let { repo ->
                    LazyColumn(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item {
                            Text(repo.repo.name, style = MaterialTheme.typography.titleMedium)
                            Text("${repo.branch} · ${repo.head.commit.take(8)}", style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(repo.agentEnabled, enabled = !busy, onCheckedChange = { enabled ->
                                    work { github.enableAgent(project.id, enabled); state = github.project(project.id) }
                                })
                                Text("Let the agent work with this repo")
                            }
                            Text("File text the agent reads is sent to OpenRouter.",
                                style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(enabled = !busy, onClick = {
                                    work { state = github.refresh(project.id); generation++ }
                                }) { Text("Refresh") }
                                TextButton(enabled = !busy, onClick = { dialog = "token" }) { Text("Update token") }
                            }
                            if (repo.pending != null) Text("A publish is awaiting confirmation from GitHub.")
                            if (repo.pendingPr != null) Text("A pull request is awaiting confirmation from GitHub.")
                            if (repo.lastLink != null) TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.lastLink)))
                            }) { Text("Open last result on GitHub") }
                            if (repo.drafts.isNotEmpty()) Text("Drafts (${repo.drafts.size})",
                                style = MaterialTheme.typography.titleSmall)
                        }
                        items(repo.drafts, key = { "draft:" + it.path }) { draft ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(draft.path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(enabled = !busy, onClick = {
                                    if (draft.conflict) {
                                        work {
                                            val upstream = try {
                                                github.readUpstream(project.id, draft.path)
                                            } catch (problem: IllegalArgumentException) {
                                                error = problem.githubMessage()
                                                return@work
                                            }
                                            resolve = DraftResolution(draft, upstream)
                                        }
                                    } else file = draft.path to githubDiff(draft)
                                }) { Text(if (draft.conflict) "Resolve" else "Diff") }
                                if (repo.pending == null) TextButton(enabled = !busy, onClick = {
                                    dialog = "discard:" + draft.path
                                }) { Text("Discard") }
                            }
                        }
                        item {
                            if (repo.drafts.isNotEmpty() && repo.drafts.none { it.conflict }) {
                                Button(enabled = !busy, onClick = { dialog = "publish" }) {
                                    Text(if (repo.pending != null) "Review retry" else "Review and publish")
                                }
                            }
                            if (repo.workBranch != null && repo.drafts.isEmpty()) {
                                TextButton(enabled = !busy, onClick = { dialog = "pr" }) { Text("Open pull request") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(searchInput, { searchInput = it.take(255) }, Modifier.weight(1f),
                                    label = { Text("Find a path") }, singleLine = true)
                                TextButton(enabled = !busy, onClick = { query = searchInput.trim() }) { Text("Search") }
                            }
                            Text("${listing?.scope ?: folder.ifEmpty { "Repository" }}${if (query.isEmpty()) "" else " · $query"}",
                                style = MaterialTheme.typography.bodySmall)
                            if (folder.isNotEmpty() || query.isNotEmpty()) TextButton(onClick = {
                                if (query.isEmpty()) folder = folder.substringBeforeLast('/', "")
                                query = ""
                                searchInput = ""
                            }) { Text(if (query.isEmpty()) "Up a folder" else "Clear search") }
                        }
                        items(listing?.entries.orEmpty(), key = { "entry:" + it.path }) { entry ->
                            if (entry.type == "tree") {
                                TextButton(enabled = !busy, onClick = {
                                    folder = entry.path
                                    query = ""
                                    searchInput = ""
                                }) { Text(entry.path + "/") }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    if (entry.canRead) {
                                        TextButton(enabled = !busy, onClick = {
                                            work { file = entry.path to github.read(project.id, entry.path) }
                                        }) { Text("Open") }
                                    }
                                    if (entry.sha.isNotEmpty()) TextButton(onClick = {
                                        val link = "https://github.com/".toHttpUrl().newBuilder()
                                            .addPathSegments(repo.repo.name).addPathSegment("blob")
                                            .addPathSegment(repo.head.commit).addPathSegments(entry.path).build()
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.toString())))
                                    }) { Text("GitHub") }
                                    if (!entry.canRead && entry.size != null) Text("${entry.size / 1024} KiB",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        item {
                            listing?.next?.let { offset ->
                                TextButton(enabled = !busy, onClick = {
                                    work {
                                        val page = github.list(project.id, folder, query, offset)
                                        listing = page.copy(entries = listing?.entries.orEmpty() + page.entries)
                                    }
                                }) { Text("More paths") }
                            }
                            if (repo.drafts.isEmpty() && repo.pending == null && repo.pendingPr == null) {
                                TextButton(enabled = !busy, onClick = { dialog = "disconnect" }) { Text("Disconnect") }
                            }
                        }
                    }
                }
            }
        }
    }

    review?.let { item ->
        GitHubReviewDialog(item, onDecision = { approved ->
            review = null
            if (approved) work {
                try {
                    github.submit(item)
                } finally {
                    state = github.project(project.id)
                }
                generation++
            }
        })
    }
    file?.let { (path, text) ->
        AlertDialog(onDismissRequest = { file = null }, title = { Text(path) }, confirmButton = {
            TextButton(onClick = { file = null }) { Text("Close") }
        }, text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace) } })
    }
    resolve?.let { item ->
        var merged by remember(item) { mutableStateOf(item.draft.text) }
        AlertDialog(onDismissRequest = { resolve = null }, title = { Text("Resolve ${item.draft.path}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("GitHub version", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(item.upstream ?: "The file was removed on GitHub.", fontFamily = FontFamily.Monospace) }
                    OutlinedTextField(merged, { merged = it }, label = { Text("Resolved draft") }, minLines = 4)
                }
            }, confirmButton = {
                TextButton(onClick = {
                    resolve = null
                    work { github.resolve(project.id, item.draft.path, merged); generation++ }
                }) { Text("Save resolution") }
            }, dismissButton = { TextButton(onClick = { resolve = null }) { Text("Cancel") } })
    }
    when {
        dialog == "token" -> GitHubInputDialog("Update GitHub token", "Fine-grained token", password = true,
            onClose = { dialog = "" }) { value, _ ->
            work { github.replaceToken(project.id, value); generation++ }
        }
        dialog == "publish" -> GitHubInputDialog("Commit message", "Message", initial = state?.pending?.message.orEmpty(),
            onClose = { dialog = "" }) { message, _ ->
            work { review = github.preparePublish(project.id, message) }
        }
        dialog == "pr" -> GitHubInputDialog("Pull request", "Title", body = true,
            initial = state?.pendingPr?.request?.title.orEmpty(),
            onClose = { dialog = "" }) { title, body ->
            work { review = github.preparePr(project.id, title, body) }
        }
        dialog == "disconnect" -> GitHubConfirm("Disconnect GitHub?", "Disconnect", { dialog = "" }) {
            work {
                github.disconnect(project.id)
                folder = ""
                query = ""
                searchInput = ""
                generation++
            }
        }
        dialog.startsWith("discard:") -> {
            val path = dialog.removePrefix("discard:")
            GitHubConfirm("Discard draft for $path?", "Discard", { dialog = "" }) {
                work { github.discard(project.id, path); generation++ }
            }
        }
    }
}

private data class DraftResolution(val draft: GitHubDraft, val upstream: String?)

@Composable
private fun GitHubConnectForm(
    projectName: String,
    busy: Boolean,
    onDiscover: (String, String, (GitHubRepo) -> Unit) -> Unit,
    onConnect: (String, String, String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf<GitHubRepo?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connect a repository to $projectName")
        OutlinedTextField(url, { url = it; repo = null }, label = { Text("GitHub repository URL") },
            enabled = !busy, singleLine = true)
        OutlinedTextField(token, { token = it; repo = null }, label = { Text("Fine-grained token") },
            enabled = !busy, visualTransformation = PasswordVisualTransformation(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Text("Choose Only select repositories when creating the token and select this repo. Grant Contents read and write for commits and Pull requests read and write for PRs.",
            style = MaterialTheme.typography.bodySmall)
        if (repo == null) {
            Button(onClick = { onDiscover(url, token) { repo = it; branch = it.defaultBranch } },
                enabled = !busy && url.isNotBlank() && token.isNotBlank()) { Text("Find repository") }
        } else {
            OutlinedTextField(branch, { branch = it }, label = { Text("Base branch") }, enabled = !busy, singleLine = true)
            Button(onClick = { onConnect(url, token, branch) }, enabled = !busy) { Text("Connect") }
        }
    }
}

@Composable
private fun GitHubInputDialog(
    title: String, field: String, password: Boolean = false, body: Boolean = false, initial: String = "",
    onClose: () -> Unit, submit: (String, String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var detail by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value, { value = it }, label = { Text(field) }, singleLine = password || body,
                visualTransformation = if (password) PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default)
            if (body) OutlinedTextField(detail, { detail = it }, label = { Text("Description") }, minLines = 3)
        }
    }, confirmButton = {
        TextButton(enabled = value.isNotBlank(), onClick = { onClose(); submit(value, detail) }) { Text("Continue") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun GitHubConfirm(title: String, action: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, confirmButton = {
        TextButton(onClick = { close(); confirm() }) { Text(action) }
    }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
internal fun GitHubReviewDialog(review: GitHubReview, onDecision: (Boolean) -> Unit) {
    val pr = review.pullRequest
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        title = { Text(if (pr == null) "Publish GitHub commit?" else "Open GitHub pull request?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(review.repository, style = MaterialTheme.typography.titleSmall)
                    Text(if (pr == null) review.branch else "${review.branch} → ${pr.base}")
                    if (pr != null) Text("Head ${pr.head.take(8)}")
                    SelectionContainer { Text(pr?.title ?: review.commitMessage) }
                    if (!pr?.body.isNullOrEmpty()) SelectionContainer { Text(checkNotNull(pr).body) }
                }
                items(review.drafts, key = { it.path }) { draft ->
                    Text(draft.path, style = MaterialTheme.typography.titleSmall)
                    val diff = remember(draft) { githubDiff(draft) }
                    SelectionContainer { Text(diff, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(true) }) { Text(if (pr == null) "Publish commit" else "Open PR") }
        },
        dismissButton = { TextButton(onClick = { onDecision(false) }) { Text("Cancel") } },
    )
}

internal fun githubDiff(draft: GitHubDraft): String {
    val original = if (draft.original == null) emptyList() else draft.originalText.split('\n')
    val proposed = draft.text.split('\n')
    val prefix = original.zip(proposed).takeWhile { (a, b) -> a == b }.size
    val maxSuffix = minOf(original.size, proposed.size) - prefix
    var suffix = 0
    while (suffix < maxSuffix && original[original.lastIndex - suffix] == proposed[proposed.lastIndex - suffix]) suffix++
    val removed = original.subList(prefix, original.size - suffix)
    val added = proposed.subList(prefix, proposed.size - suffix)
    return buildString {
        appendLine("@@ -${prefix + 1},${removed.size} +${prefix + 1},${added.size} @@")
        removed.forEach { appendLine("-$it") }
        added.forEach { appendLine("+$it") }
    }
}
