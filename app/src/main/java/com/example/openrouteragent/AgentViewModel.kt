package com.example.openrouteragent

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.widget.FrameLayout
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    val client = OpenRouterAgentClient()
    internal val files = OpenRouterFiles(application, client)
    private val storage = AgentStorage(application)
    private val sdk = AgentSdkRuntime(application)
    private val chatClient = CompatibleChatClient()
    private val chats = ChatDatabase(application)
    private val settingsMutex = Mutex()
    var isSavingSettings by mutableStateOf(false)
        private set
    private val ready = CompletableDeferred<Unit>()

    var config by mutableStateOf(AgentConfig())
        private set
    var workspace by mutableStateOf(Workspace())
        private set
    internal var library by mutableStateOf(ChatLibrary())
        private set
    internal var isWorkingChat by mutableStateOf(false)
        private set
    internal val chatProject: ChatProject? get() {
        val projectId = library.chats.firstOrNull { it.id == workspace.id }?.projectId
        return library.projects.firstOrNull { it.id == projectId }
    }
    internal val sandboxId: String? get() = if (usesOpenRouter) {
        chatProject?.let { projectSandboxId(it.id, config) } ?: workspace.containerId
    } else null
    private data class AttachmentSelection(val area: ProjectFileArea, val account: String, val files: List<WorkspaceFileRef>)
    private var attachmentSelection by mutableStateOf<AttachmentSelection?>(null)
    internal val attachedFiles: List<WorkspaceFileRef> get() = attachmentSelection
        ?.takeIf { it.area == fileArea() && it.account == workspaceTag(config) }?.files.orEmpty()
    internal val canAttachWorkspaceFiles: Boolean get() = usesOpenRouter && providerReady && activePrompt == null &&
        config.enableShellSandbox && config.sdk.toolChoice != "none"
    var promptText by mutableStateOf("")
    var isLoading by mutableStateOf(true)
        private set
    var activePrompt by mutableStateOf<String?>(null)
        private set
    var turnError by mutableStateOf<String?>(null)
        private set
    var storageError by mutableStateOf<String?>(null)
        private set

    var isExporting by mutableStateOf(false)
        private set
    var exportMessage by mutableStateOf<String?>(null)
        private set

    var availableModels by mutableStateOf<List<CatalogModel>>(emptyList())
        private set
    var catalogLoading by mutableStateOf(false)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    val provider: CustomProvider? get() = config.customProviders.firstOrNull { it.id == config.activeProviderId }
    val usesOpenRouter: Boolean get() = config.activeProviderId == OPENROUTER_ID
    val providerName: String get() = provider?.name ?: "OpenRouter"
    val providerReady: Boolean get() = provider?.canChat ?: config.apiKey.isNotBlank()
    val selectedModel: CatalogModel? get() {
        val active = provider ?: return selectModel(config, availableModels)
        if (active.model.isBlank()) return availableModels.firstOrNull()
        return availableModels.firstOrNull { it.id == active.model }
            ?: CatalogModel(active.model, active.model, 0, emptySet())
    }

    internal var progress by mutableStateOf<AgentProgress?>(null)
        private set
    internal var interrupted by mutableStateOf<AgentProgress?>(null)
        private set
    private var retryPrompt: String? = null
    internal var builtInGuide by mutableStateOf<SavedAgentSkill?>(null)
        private set
    private var approvals by mutableStateOf<List<ToolApproval>>(emptyList())
    val approval: ToolApproval? get() = approvals.firstOrNull()

    private var catalogJob: Job? = null
    private var running: Job? = null

    init {
        viewModelScope.launch {
            val (savedConfig, savedWorkspace, guide) = withContext(Dispatchers.IO) {
                val profile = runCatching { storage.loadConfig() }
                Triple(profile, runCatching { loadChats(profile.getOrThrow()) }, runCatching {
                    application.assets.open("skills/$GUIDE_NAME/SKILL.md").bufferedReader().use {
                        parseSkill(it.readText()).copy(enabled = true)
                    }
                }.getOrNull())
            }
            builtInGuide = guide
            savedConfig.onSuccess { config = it }.onFailure {
                storageError = "Android could not read the saved API key. Open Settings to enter it again."
            }
            savedWorkspace.onSuccess { (saved, index) ->
                showWorkspace(saved)
                library = index
            }.onFailure {
                if (savedConfig.isSuccess) storageError = "Android could not read the chat library."
            }
            isLoading = false
            ready.complete(Unit)
            refreshModels()
        }
    }

    fun refreshModels(clear: Boolean = false) {
        catalogJob?.cancel()
        val snapshot = config
        val active = provider
        val key = active?.apiKey ?: snapshot.apiKey
        if (clear || !providerReady) availableModels = emptyList()
        catalogError = null
        catalogLoading = providerReady
        if (!providerReady) return
        catalogJob = viewModelScope.launch {
            try {
                val models = if (active == null) client.availableModels(key) else chatClient.models(active)
                availableModels = models
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                catalogError = if (active == null) e.userMessage(key) else
                    providerMessage(active, e) + " You can enter the model ID manually in Providers."
            } finally {
                if (kotlin.coroutines.coroutineContext[Job]?.isCancelled != true) catalogLoading = false
            }
        }
    }

    internal suspend fun discoverModels(profile: CustomProvider): List<CatalogModel> {
        val clean = profile.normalized()
        clean.error()?.let { throw IllegalArgumentException(it) }
        require(clean.canChat) { "Enter the ${clean.name} API key first." }
        return chatClient.models(clean)
    }

    fun chooseModel(model: CatalogModel) {
        if (isLoading || isSavingSettings || isWorkingChat || activePrompt != null ||
            (usesOpenRouter && (model !in availableModels || (config.agentToolsEnabled() && !model.supportsTools)))) return
        viewModelScope.launch {
            try {
                if (activePrompt != null) return@launch
                val current = config
                val active = provider
                if (active == null) saveSettings(current.copy(model = model.id))
                else saveSettings(current.copy(customProviders = current.customProviders.map {
                    if (it.id == active.id) it.copy(model = model.id) else it
                }))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                storageError = "Android could not save the selected model."
            }
        }
    }

    fun decideTool(approve: Boolean, answer: String = "") {
        val pending = approval ?: return
        approvals = approvals.drop(1)
        sdk.decide(pending.decision, approve, answer)
        notifyBackground()
    }

    private val app get() = getApplication<Application>()

    /** Mirrors the live status, or a pending approval, into the ongoing run notification. */
    private fun notifyBackground(force: Boolean = false) {
        val prompt = activePrompt ?: return
        val pending = approval
        val notice = if (pending != null) RunNotice(
            title = if (pending.asksUser) "The agent has a question" else "Allow ${pending.name}?",
            text = if (pending.asksUser) "${pending.name}. Open the app to answer."
                else (pending.url.ifBlank { pending.arguments }).take(400),
            approval = pending,
        ) else RunNotice("Working on: ${replyPreview(prompt, 60)}",
            progress?.status(providerName) ?: "Working")
        RunNotifications.update(app, notice, force)
    }

    private fun finishBackground(title: String, text: String) {
        AgentRunService.stop(app)
        RunNotifications.reset()
        if (!AppVisibility.visible) RunNotifications.finished(app, title, text)
    }

    internal fun attachRuntime(host: FrameLayout) = sdk.attach(host)

    internal fun detachRuntime(host: FrameLayout) = sdk.detach(host)

    internal fun fileArea(projectId: String? = chatProject?.id) = ProjectFileArea(projectId, workspace.id)

    internal fun toggleWorkspaceFile(file: WorkspaceFileRef) {
        require(canAttachWorkspaceFiles) { "Turn on the hosted shell to attach workspace files." }
        val area = fileArea()
        require(file.filename.startsWith(area.remotePrefix)) { "Save a copy in this project before attaching it." }
        val selection = attachedFiles
        val next = if (selection.any { it.id == file.id }) selection.filterNot { it.id == file.id } else {
            require(selection.size < MAX_WORKSPACE_ATTACHMENTS) { "Attach up to 20 files per message." }
            selection + file
        }
        attachmentSelection = AttachmentSelection(area, workspaceTag(config), next)
    }

    internal fun detachWorkspaceFile(fileId: String) {
        val selection = attachmentSelection ?: return
        attachmentSelection = selection.copy(files = selection.files.filterNot { it.id == fileId })
    }

    internal fun clearWorkspaceAttachments() {
        if (activePrompt == null) attachmentSelection = null
    }

    internal suspend fun prepareFileArea(area: ProjectFileArea) {
        if (area.projectId == null && workspace.id == area.chatId && library.chats.none { it.id == area.chatId }) {
            saveWorkspace(scopedWorkspace(workspace))
        }
    }

    fun retryTurn(toolsOff: Boolean = false) {
        val prompt = retryPrompt ?: return
        send(prompt, toolsOff)
    }

    fun send(text: String, toolsOff: Boolean = false) {
        val prompt = text.trim()
        if (isLoading || isSavingSettings || isWorkingChat || activePrompt != null || prompt.isBlank()) return
        if (!providerReady) {
            turnError = if (usesOpenRouter) "Add your OpenRouter API key in Settings before sending."
                else "Enter the $providerName API key in Providers before sending."
            return
        }
        // Reject impossible requests before any network call, keeping the prompt in the composer.
        commandProblem(prompt)?.let {
            turnError = it
            return
        }
        val active = provider
        val model = selectedModel ?: run {
            turnError = if (catalogLoading) "Loading models…" else "Open Models or enter a model ID in Providers."
            return
        }
        val priorWorkspace = workspace
        val area = fileArea()
        val attachments = if (active == null && !toolsOff) attachedFiles else emptyList()
        if (attachments.isNotEmpty() && !canAttachWorkspaceFiles) {
            turnError = "Turn on the hosted shell or remove the file attachments before sending."
            return
        }
        val runFiles = OpenRouterRunFiles(
            projectFolder = area.relativeRoot.takeIf { area.projectId != null },
            projectSandbox = area.projectId?.let { projectSandboxId(it, config) },
            attachments = attachments,
        )
        val currentConfig = if (toolsOff) config.copy(enableShellSandbox = false, enableWebSearch = false,
            enableDateTime = false, webFetch = false, deviceTools = false, enableWorkspaceFiles = false,
            miniApps = false, mcpUrl = "", mcpToken = "",
            extraToolsJson = "", customTools = emptyList(),
            sdk = config.sdk.copy(discoverSkills = false, toolChoice = "none")) else config
        activePrompt = prompt
        promptText = ""
        turnError = null
        interrupted = null
        retryPrompt = null
        progress = AgentProgress(SystemClock.elapsedRealtime())
        RunNotifications.reset()
        notifyBackground(force = true)
        AgentRunService.start(app)

        running = viewModelScope.launch {
            var finishedTitle = "Reply ready"
            var finishedText = "The agent finished."
            try {
                val sdkResult = if (active == null) {
                    sdk.run(currentConfig, priorWorkspace, prompt, model, availableModels, builtInGuide,
                        onUpdate = ::updateProgress, onApproval = { approvals = approvals + it; notifyBackground() },
                        files = runFiles,
                        onAppAction = if (currentConfig.sdk.toolChoice == "none") null else { name, args ->
                            executeAppAction(area, name, args)
                        })
                } else {
                    SdkRunResult(chatClient.run(active, currentConfig, priorWorkspace, prompt, model.id, ::updateProgress), null)
                }
                val result = sdkResult.result
                finishedText = replyPreview(result.assistantText)
                if (attachments.isNotEmpty()) attachmentSelection = null
                val messages = priorWorkspace.messages + listOf(
                    ChatEntry(isUser = true, text = prompt),
                    ChatEntry(
                        isUser = false, text = result.assistantText,
                        reasoningSummary = if (currentConfig.sdk.showReasoning) {
                            result.reasoningSummary ?: progress?.summary?.ifBlank { null }
                        } else null,
                        shellSteps = result.shellSteps, searchQueries = result.searchQueries,
                        citations = (result.citations + progress?.sources.orEmpty()).distinctBy { it.url },
                        usage = result.usage, modelUsed = result.modelUsed,
                        notice = result.notice,
                        toolActivity = progress?.tools.orEmpty().map {
                            ToolActivity(it.name, it.status, it.detail)
                        }
                    )
                )
                val finished = scopedWorkspace(priorWorkspace.copy(
                    messages = messages,
                    containerId = if (area.projectId != null) priorWorkspace.containerId
                        else result.discoveredContainerId ?: priorWorkspace.containerId,
                    sdkHistory = sdkResult.history,
                    sdkState = sdkResult.state,
                    interruptedTurn = null,
                ))
                workspace = finished
                try {
                    saveWorkspace(finished)
                    storageError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    storageError = "Android could not save this reply. It is still open on this screen."
                }
            } catch (e: CancellationException) {
                finishedTitle = "Run stopped"
                finishedText = turnError ?: "The run was stopped."
                if (turnError != null) keepInterrupted(prompt, turnError)
                throw e
            } catch (e: Exception) {
                turnError = if (active == null) e.userMessage(currentConfig.apiKey) else providerMessage(active, e)
                finishedTitle = "The agent could not finish"
                finishedText = turnError ?: "The run failed."
                keepInterrupted(prompt, turnError)
            } finally {
                activePrompt = null
                progress = null
                approvals = emptyList()
                finishBackground(finishedTitle, finishedText)
            }
        }
    }

    private suspend fun executeAppAction(area: ProjectFileArea, name: String, args: JSONObject): JSONObject {
        if (name in projectFileActions) {
            if (name == "write_project_file" || name == "edit_project_file") prepareFileArea(area)
            return withContext(Dispatchers.IO) { files.store.performAction(area, name, args) }
        }
        return settingsMutex.withLock {
            val change = prepareResourceAction(config, name, args)
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { storage.saveConfig(change.config) }
                config = change.config
            }
            change.result
        }
    }

    private fun updateProgress(updates: List<RunUpdate>) {
        progress = progress?.apply(updates, SystemClock.elapsedRealtime())
        notifyBackground()
    }

    private suspend fun keepInterrupted(prompt: String, error: String?) {
        promptText = prompt
        retryPrompt = prompt
        val partial = progress ?: return
        interrupted = partial
        val snapshot = scopedWorkspace(workspace.copy(
            interruptedTurn = InterruptedTurn(prompt, partial.answer, partial.summary, error),
        ))
        workspace = snapshot
        withContext(NonCancellable) {
            try {
                saveWorkspace(snapshot)
            } catch (_: Exception) {
                storageError = "Android could not save the interrupted reply."
            }
        }
    }

    fun cancelTurn() {
        if (activePrompt == null) return
        turnError = "Stopped waiting. $providerName may still finish the submitted request."
        running?.cancel()
    }

    fun newConversation(projectId: String? = chatProject?.id) = chatAction { scope ->
        val fresh = scopedWorkspace(Workspace())
        withContext(Dispatchers.IO) { chats.save(scope, fresh, projectId) }
        showWorkspace(fresh)
    }

    fun openChat(id: String) = chatAction { scope ->
        val saved = withContext(Dispatchers.IO) { chats.open(scope, id) }
        showWorkspace(restoreWorkspace(config, saved))
    }

    fun renameChat(id: String, name: String) = chatAction { scope ->
        withContext(Dispatchers.IO) { chats.renameChat(scope, id, name) }
    }

    fun moveChat(id: String, projectId: String?) = chatAction { scope ->
        withContext(Dispatchers.IO) { chats.moveChat(scope, id, projectId) }
    }

    fun deleteChat(id: String) = chatAction { scope ->
        val replaceCurrent = workspace.id == id
        val next = withContext(Dispatchers.IO) {
            chats.deleteChat(scope, id)
            if (replaceCurrent) chats.active(scope) else null
        }
        if (replaceCurrent) showWorkspace(next?.let { restoreWorkspace(config, it) } ?: Workspace())
    }

    fun addProject(name: String) = chatAction {
        val area = fileArea(UUID.randomUUID().toString())
        withContext(Dispatchers.IO) {
            files.store.ensure(area)
            chats.addProject(name, checkNotNull(area.projectId))
        }
    }

    fun renameProject(id: String, name: String) = chatAction {
        withContext(Dispatchers.IO) { chats.renameProject(id, name) }
    }

    fun deleteProject(id: String) = chatAction {
        withContext(Dispatchers.IO) { chats.deleteProject(id) }
    }

    fun detachSandbox() = chatAction {
        if (chatProject != null) return@chatAction
        val reset = scopedWorkspace(workspace.copy(sessionId = UUID.randomUUID().toString(),
            containerId = null, sdkHistory = null, sdkState = null))
        saveWorkspace(reset)
        workspace = reset
    }

    private fun chatAction(action: suspend (ChatScope) -> Unit) {
        if (activePrompt != null || isLoading || isSavingSettings || isWorkingChat || isExporting) return
        val scope = chatScope(config)
        isWorkingChat = true
        viewModelScope.launch {
            try {
                action(scope)
                library = withContext(Dispatchers.IO) { chats.library(scope) }
                storageError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                storageError = if (e is IllegalArgumentException) e.message else "Android could not update the chat library."
            } finally {
                isWorkingChat = false
            }
        }
    }

    suspend fun saveSettings(updated: AgentConfig) = settingsMutex.withLock {
        check(activePrompt == null && !isLoading && !isWorkingChat && !isExporting)
        updated.settingsError()?.let { throw IllegalArgumentException(it) }
        val clean = updated.copy(apiKey = updated.apiKey.trim(),
            model = updated.model.trim(),
            mcpUrl = updated.mcpUrl.trim(),
            mcpToken = updated.mcpToken.trim(),
            extraToolsJson = updated.extraToolsJson.trim(),
            allowedDomainsCsv = updated.allowedDomainsCsv.trim(),
            systemInstructions = updated.systemInstructions.trim(),
            customProviders = updated.customProviders.map(CustomProvider::normalized))
        isSavingSettings = true
        try {
            withContext(NonCancellable) {
                val previous = config
                val change = chatScope(previous) != chatScope(clean)
                val removed = previous.customProviders.map { it.id }.toSet() - clean.customProviders.map { it.id }.toSet()
                val resetSandbox = !change && clean.activeProviderId == OPENROUTER_ID &&
                    (previous.sandboxDomains() != clean.sandboxDomains() ||
                        previous.enableShellSandbox != clean.enableShellSandbox)
                val next = withContext(Dispatchers.IO) {
                    val loaded = if (change) loadChats(clean) else null
                    storage.saveConfig(clean)
                    loaded
                }
                config = clean
                next?.let { (saved, index) ->
                    showWorkspace(saved)
                    library = index
                    refreshModels(clear = true)
                }
                if (resetSandbox) {
                    workspace = scopedWorkspace(workspace.copy(sessionId = UUID.randomUUID().toString(),
                        containerId = null, sdkHistory = null, sdkState = null))
                    if (workspace.messages.isNotEmpty() || library.chats.any { it.id == workspace.id }) {
                        saveWorkspace(workspace)
                    }
                }
                withContext(Dispatchers.IO) {
                    for (id in removed) {
                        chats.deleteProvider(id)
                        storage.removeLegacyWorkspace(id)
                    }
                }
                storageError = null
            }
        } finally {
            isSavingSettings = false
        }
    }

    private fun loadChats(settings: AgentConfig): Pair<Workspace, ChatLibrary> {
        val scope = chatScope(settings)
        if (chats.importLegacy(scope) { storage.readLegacyWorkspace(scope.provider) }) {
            storage.removeLegacyWorkspace(scope.provider)
        }
        val saved = chats.active(scope)?.let { restoreWorkspace(settings, it) } ?: Workspace()
        return saved to chats.library(scope)
    }

    private fun showWorkspace(saved: Workspace) {
        workspace = saved
        val previous = saved.interruptedTurn
        promptText = previous?.prompt.orEmpty()
        turnError = previous?.error
        interrupted = previous?.let {
            AgentProgress(SystemClock.elapsedRealtime(), answer = it.reply, summary = it.reasoningSummary)
        }
        retryPrompt = previous?.prompt
    }

    fun exportFile(uri: Uri, containerId: String, fileId: String) {
        if (isExporting) return
        isExporting = true
        exportMessage = null
        val application = getApplication<Application>()
        val resolver = application.contentResolver
        viewModelScope.launch {
            var staging: File? = null
            try {
                ready.await()
                val file = withContext(Dispatchers.IO) {
                    File.createTempFile("export-", ".tmp", application.cacheDir).also { staging = it }
                }
                client.downloadFile(config.apiKey, containerId, fileId) { file.outputStream() }
                withContext(Dispatchers.IO) {
                    val destination = resolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Android could not open the destination.")
                    destination.use { output -> file.inputStream().use { it.copyTo(output) } }
                }
                exportMessage = "File saved."
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
                exportMessage = e.userMessage(config.apiKey)
            } finally {
                staging?.delete()
                isExporting = false
            }
        }
    }

    fun clearExportMessage() {
        exportMessage = null
    }

    init {
        // Notification buttons reach the process-wide view model through this handler.
        RunCommands.handler = { command, decision ->
            viewModelScope.launch {
                when (command) {
                    RunCommands.STOP -> cancelTurn()
                    RunCommands.ALLOW, RunCommands.DENY ->
                        if (approval?.decision == decision && approval?.asksUser == false) {
                            decideTool(command == RunCommands.ALLOW)
                        }
                }
            }
        }
    }

    var signInBusy by mutableStateOf(false)
        private set
    var signInError by mutableStateOf<String?>(null)
        private set
    /** A key from OpenRouter sign-in that the Settings sheet has not taken yet. */
    var signInKey by mutableStateOf<String?>(null)
        private set
    private var signInJob: Job? = null
    private val signInHttp by lazy { OkHttpClient() }

    /** Starts OpenRouter's OAuth PKCE sign-in. [open] shows the authorization page in a browser. */
    fun signInWithOpenRouter(open: (String) -> Boolean) {
        if (signInBusy) return
        signInError = null
        signInKey = null
        signInJob = viewModelScope.launch {
            signInBusy = true
            try {
                OpenRouterSignIn(signInHttp, getApplication<Application>().packageName).use { attempt ->
                    if (!open(attempt.authorizeUrl)) throw IOException("No browser is available to open OpenRouter.")
                    signInKey = attempt.awaitKey()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signInError = e.message ?: "Sign-in failed. Try again."
            } finally {
                signInBusy = false
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
    }

    fun takeSignInKey(): String? = signInKey.also { signInKey = null }

    override fun onCleared() {
        sdk.destroy()
        super.onCleared()
    }

    private fun scopedWorkspace(next: Workspace) = next.copy(accountTag = workspaceTag(config),
        sandboxDomains = if (usesOpenRouter) config.sandboxDomains() else emptyList())

    private suspend fun saveWorkspace(next: Workspace) {
        val scope = chatScope(config)
        library = withContext(Dispatchers.IO + NonCancellable) {
            chats.save(scope, next)
            chats.library(scope)
        }
    }
}

internal fun chatScope(config: AgentConfig) = ChatScope(config.activeProviderId, workspaceTag(config))

internal fun workspaceTag(config: AgentConfig): String {
    val active = config.customProviders.firstOrNull { it.id == config.activeProviderId }
    return if (active == null) accountTag(config.apiKey)
    else accountTag("${active.id}|${normalizeChatBase(active.baseUrl)}|${active.apiKey.trim()}")
}

internal fun restoreWorkspace(config: AgentConfig, saved: Workspace): Workspace = when {
    saved.accountTag != workspaceTag(config) -> Workspace()
    config.activeProviderId == OPENROUTER_ID && saved.sandboxDomains != config.sandboxDomains() ->
        saved.copy(sessionId = UUID.randomUUID().toString(), containerId = null, sdkHistory = null, sdkState = null)
    else -> saved
}

// Binds a saved chat to its credential across a restart during a settings change.
private fun accountTag(apiKey: String): String =
    if (apiKey.isBlank()) "" else MessageDigest.getInstance("SHA-256")
        .digest(apiKey.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun Throwable.userMessage(apiKey: String): String {
    if (this is java.net.SocketTimeoutException) return "The OpenRouter request timed out."
    if (this is java.net.UnknownHostException) return "Could not reach OpenRouter. Check your connection."
    val detail = when (this) {
        is javax.net.ssl.SSLException -> "A secure connection to OpenRouter could not be established."
        is org.json.JSONException -> "OpenRouter returned an unreadable response."
        else -> message ?: "The request could not be completed."
    }
    return if (apiKey.isBlank()) detail else detail.replace(apiKey, "[API key]")
}
