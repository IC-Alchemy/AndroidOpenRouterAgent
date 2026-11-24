package com.example.openrouteragent

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.widget.FrameLayout
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

internal const val POLICY_PROMPT_BLOCKED = "This prompt violates our content policy."
internal const val POLICY_RESPONSE_WARNING = "This response was hidden because it violates our content policy."

class AgentViewModel internal constructor(
    application: Application,
    private val textModerator: TextModerator?,
    private val reportClient: ModerationReportClient,
    val client: OpenRouterAgentClient,
    private val sdk: AgentSdkRuntime = AgentSdkRuntime(application),
    private val storage: AgentStorage = AgentStorage(application),
) : AndroidViewModel(application) {
    @JvmOverloads
    constructor(
        application: Application,
        textModerator: TextModerator? = ContentModerator.fromAssets(application),
        reportClient: ModerationReportClient = ModerationReportClient(),
    ) : this(application, textModerator, reportClient, OpenRouterAgentClient())

    internal val files = OpenRouterFiles(application, client)
    private val mediaStore = chatMediaStore(application)
    private val importer = AttachmentImporter(application.contentResolver, mediaStore)

    /** Photos and files waiting in the composer. They are sent with the next message. */
    var pendingMedia by mutableStateOf<List<ChatMedia>>(emptyList())
        private set
    /** Attachments of the message being sent. */
    var activeMedia by mutableStateOf<List<ChatMedia>>(emptyList())
        private set
    var isImporting by mutableStateOf(false)
        private set
    internal val github = GitHubProjects(File(application.noBackupFilesDir, "github"), storage.githubCredentials())
    private var githubDecision: CompletableDeferred<Boolean>? = null
    internal var githubReview by mutableStateOf<GitHubReview?>(null)
        private set
    private val customizationAssistant = CustomizationAssistant()
    private val miniAppGenerator by lazy {
        MiniAppGenerator(libraries = MiniAppLibraries(File(application.cacheDir, "miniapp-libraries")))
    }
    private val skillsDirectory = SkillsDirectoryClient()
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
    internal val sandboxId: String? get() =
        chatProject?.let { projectSandboxId(it.id, config) } ?: workspace.containerId
    private data class AttachmentSelection(val area: ProjectFileArea, val account: String, val files: List<WorkspaceFileRef>)
    private var attachmentSelection by mutableStateOf<AttachmentSelection?>(null)
    internal val attachedFiles: List<WorkspaceFileRef> get() = attachmentSelection
        ?.takeIf { it.area == fileArea() && it.account == workspaceTag(config) }?.files.orEmpty()
    internal val canAttachWorkspaceFiles: Boolean get() = providerReady && activePrompt == null &&
        config.enableShellSandbox && config.sdk.toolChoice != "none" && !chatMode
    val chatMode: Boolean get() = workspace.chatMode
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
    var isModerating by mutableStateOf(false)
        private set
    var reportMessage by mutableStateOf<String?>(null)
        private set

    var availableModels by mutableStateOf<List<CatalogModel>>(emptyList())
        private set
    var catalogLoading by mutableStateOf(false)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    val providerReady: Boolean get() = config.apiKey.isNotBlank()
    val selectedModel: CatalogModel? get() = selectModel(config, availableModels)

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
            withContext(Dispatchers.IO) { runCatching { mediaStore.pruneDrafts() } }
            savedConfig.onSuccess {
                config = it
                if (it.enableModeration && textModerator is ContentModerator) {
                    LiteRtInitializer.initialize(application)
                }
            }.onFailure {
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
        val key = config.apiKey
        if (clear || !providerReady) availableModels = emptyList()
        catalogError = null
        catalogLoading = providerReady
        if (!providerReady) return
        catalogJob = viewModelScope.launch {
            try {
                availableModels = client.availableModels(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                catalogError = e.userMessage(key)
            } finally {
                if (kotlin.coroutines.coroutineContext[Job]?.isCancelled != true) catalogLoading = false
            }
        }
    }

    fun chooseModel(model: CatalogModel) {
        if (isLoading || isSavingSettings || isWorkingChat || isModerating || activePrompt != null ||
            model !in availableModels || (config.agentToolsEnabled() && !model.supportsTools)) return
        viewModelScope.launch {
            try {
                if (activePrompt != null) return@launch
                saveSettings(config.copy(model = model.id))
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

    internal fun decideGitHubReview(review: GitHubReview, approve: Boolean) {
        if (githubReview === review) githubDecision?.complete(approve)
    }

    private suspend fun reviewGitHub(review: GitHubReview, access: GitHubAgentAccess): String =
        withContext(Dispatchers.Main.immediate) {
            check(githubDecision == null) { "Another GitHub change is waiting for review." }
            val decision = CompletableDeferred<Boolean>()
            githubDecision = decision
            githubReview = review
            notifyBackground()
            val approved = try {
                decision.await()
            } finally {
                githubDecision = null
                githubReview = null
                notifyBackground()
            }
            check(approved) { "The user declined the GitHub change." }
            github.submit(review, access)
        }

    private val app get() = getApplication<Application>()

    /** Mirrors the live status, or a pending approval, into the ongoing run notification. */
    private fun notifyBackground() {
        val prompt = activePrompt ?: return
        val pending = approval
        val notice = if (githubReview != null) {
            RunNotice("GitHub change ready for review", "Open the app to see the details and decide.")
        } else if (pending != null) RunNotice(
            title = if (pending.asksUser) "The agent has a question" else "Allow ${pending.name}?",
            text = if (pending.asksUser) "${pending.name}. Open the app to answer."
                else (pending.url.ifBlank { pending.arguments }).take(400),
            approval = pending,
        ) else RunNotice("Working on: ${replyPreview(prompt, 60)}",
            progress?.status() ?: "Working")
        RunNotifications.update(notice)
    }

    private fun finishBackground(title: String, text: String) {
        AgentRunService.stop(app)
        RunNotifications.end(app)
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

    private fun beginTurn(prompt: String, media: List<ChatMedia>) {
        activePrompt = prompt
        activeMedia = media
        pendingMedia = emptyList()
        promptText = ""
        interrupted = null
        retryPrompt = null
        progress = AgentProgress(SystemClock.elapsedRealtime())
        RunNotifications.begin(app)
        notifyBackground()
        AgentRunService.start(app)
    }

    fun send(text: String, toolsOff: Boolean = false) {
        val prompt = text.trim()
        val sentMedia = pendingMedia
        if (isLoading || isSavingSettings || isWorkingChat || isModerating || isImporting || activePrompt != null ||
            (prompt.isBlank() && sentMedia.isEmpty())) return
        if (!providerReady) {
            turnError = "Add your OpenRouter API key in Settings before sending."
            return
        }
        // Reject impossible requests before any network call, keeping the prompt in the composer.
        commandProblem(prompt)?.let {
            turnError = it
            return
        }
        val model = selectedModel ?: run {
            turnError = if (catalogLoading) "Loading models…" else "Open Models to choose a model."
            return
        }
        attachmentProblem(sentMedia, model)?.let {
            turnError = it
            return
        }
        val priorWorkspace = workspace
        val area = fileArea()
        val noTools = toolsOff || chatMode
        val attachments = if (!noTools) attachedFiles else emptyList()
        if (attachments.isNotEmpty() && !canAttachWorkspaceFiles) {
            turnError = "Turn on the hosted shell or remove the file attachments before sending."
            return
        }
        val runFiles = OpenRouterRunFiles(
            projectFolder = area.relativeRoot.takeIf { area.projectId != null },
            projectSandbox = area.projectId?.let { projectSandboxId(it, config) },
            attachments = attachments,
        )
        val currentConfig = if (noTools) config.withoutTools() else config
        val activeModerator = textModerator?.takeIf { currentConfig.enableModeration }
        turnError = null
        if (activeModerator != null) {
            isModerating = true
        } else {
            beginTurn(prompt, sentMedia)
        }

        running = viewModelScope.launch {
            if (activeModerator != null) {
                val promptSafe = try {
                    activeModerator.isSafe(prompt)
                } catch (e: CancellationException) {
                    isModerating = false
                    throw e
                } catch (_: Throwable) {
                    true
                }
                isModerating = false
                if (!promptSafe) {
                    turnError = POLICY_PROMPT_BLOCKED
                    return@launch
                }
                if (activePrompt != null || isLoading || isSavingSettings || isWorkingChat) return@launch
                beginTurn(prompt, sentMedia)
            }

            var finishedTitle = "Reply ready"
            var finishedText = "The agent finished."
            try {
                val gitHubAccess = if (noTools || currentConfig.sdk.toolChoice == "none") null
                    else area.projectId?.let { github.session(it) }
                val runMedia = withContext(Dispatchers.IO) {
                    sentMedia.map { item ->
                        RunMedia(item, if (item.kind == MEDIA_TEXT) {
                            mediaStore.find(priorWorkspace.id, item.file)?.readText()
                                ?: throw IOException("The attachment ${item.name} is no longer on this device.")
                        } else null)
                    }
                }
                val sdkResult = sdk.run(currentConfig, priorWorkspace, prompt, model, availableModels, builtInGuide,
                    onUpdate = ::updateProgress, onApproval = { approvals = approvals + it; notifyBackground() },
                    files = runFiles.copy(github = gitHubAccess != null, media = runMedia),
                    onAppAction = if (currentConfig.sdk.toolChoice == "none") null else { name, args ->
                        executeAppAction(area, gitHubAccess, name, args)
                    })
                val result = sdkResult.result
                val responseSafe = if (activeModerator != null) {
                    try {
                        activeModerator.isSafe(result.assistantText)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        true
                    }
                } else {
                    true
                }
                val assistantEntry = if (responseSafe) {
                    finishedText = replyPreview(result.assistantText)
                    ChatEntry(
                        isUser = false, text = result.assistantText,
                        reasoningSummary = if (currentConfig.sdk.saveReasoning) {
                            progress?.summary?.ifBlank { null } ?: result.reasoningSummary
                        } else null,
                        shellSteps = result.shellSteps, searchQueries = result.searchQueries,
                        citations = (result.citations + progress?.sources.orEmpty()).distinctBy { it.url },
                        usage = result.usage, modelUsed = result.modelUsed,
                        notice = result.notice,
                        toolActivity = progress?.tools.orEmpty().map {
                            ToolActivity(it.name, it.status, it.detail)
                        }
                    )
                } else {
                    finishedText = POLICY_RESPONSE_WARNING
                    ChatEntry(
                        isUser = false,
                        text = POLICY_RESPONSE_WARNING,
                        usage = result.usage,
                        modelUsed = result.modelUsed,
                        isModerated = true,
                    )
                }
                if (attachments.isNotEmpty()) attachmentSelection = null
                if (sentMedia.isNotEmpty()) withContext(Dispatchers.IO + NonCancellable) {
                    runCatching {
                        mediaStore.adopt(priorWorkspace.id, sentMedia)
                        mediaStore.discardDrafts(sentMedia)
                    }
                }
                val messages = priorWorkspace.messages + listOf(
                    ChatEntry(isUser = true, text = prompt, media = sentMedia),
                    assistantEntry,
                )
                val finished = scopedWorkspace(priorWorkspace.copy(
                    messages = messages,
                    containerId = if (area.projectId != null) priorWorkspace.containerId
                        else if (responseSafe) result.discoveredContainerId ?: priorWorkspace.containerId
                        else priorWorkspace.containerId,
                    sdkHistory = if (responseSafe) sdkResult.history else priorWorkspace.sdkHistory,
                    sdkState = if (responseSafe) sdkResult.state else priorWorkspace.sdkState,
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
                turnError = e.userMessage(currentConfig.apiKey)
                finishedTitle = "The agent could not finish"
                finishedText = turnError ?: "The run failed."
                keepInterrupted(prompt, turnError)
            } finally {
                activePrompt = null
                activeMedia = emptyList()
                progress = null
                approvals = emptyList()
                finishBackground(finishedTitle, finishedText)
            }
        }
    }

    private suspend fun executeAppAction(
        area: ProjectFileArea, gitHubAccess: GitHubAgentAccess?, name: String, args: JSONObject,
    ): JSONObject {
        if (name in githubActions) {
            check(gitHubAccess != null && gitHubAccess.project == area.projectId) {
                "GitHub tools are unavailable in this chat."
            }
            return github.performAction(gitHubAccess, name, args, ::reviewGitHub)
        }
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

    /** Shows or hides reasoning. Works during a run, because it changes only what the app displays. */
    fun setShowReasoning(show: Boolean) {
        if (config.sdk.showReasoning == show) return
        viewModelScope.launch {
            settingsMutex.withLock {
                val next = config.copy(sdk = config.sdk.copy(showReasoning = show))
                config = next
                try {
                    withContext(Dispatchers.IO) { storage.saveConfig(next) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    storageError = "Android could not save the reasoning setting."
                }
            }
        }
    }

    /** Renders replies as Markdown or as raw text. Works during a run, because it changes only what the app displays. */
    fun setRenderMarkdown(render: Boolean) {
        if (config.sdk.renderMarkdown == render) return
        viewModelScope.launch {
            settingsMutex.withLock {
                val next = config.copy(sdk = config.sdk.copy(renderMarkdown = render))
                config = next
                try {
                    withContext(Dispatchers.IO) { storage.saveConfig(next) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    storageError = "Android could not save the Markdown setting."
                }
            }
        }
    }

    private fun updateProgress(updates: List<RunUpdate>) {
        progress = progress?.apply(updates, SystemClock.elapsedRealtime())
        notifyBackground()
    }

    private suspend fun keepInterrupted(prompt: String, error: String?) {
        promptText = prompt
        retryPrompt = prompt
        val media = activeMedia
        pendingMedia = media
        val partial = progress ?: return
        interrupted = partial
        val snapshot = scopedWorkspace(workspace.copy(
            interruptedTurn = InterruptedTurn(prompt, partial.answer, partial.summary, error, media),
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

    /** Adds photos or files to the composer. Each is copied into app storage, so the picker's access can end. */
    fun attach(uris: List<Uri>) {
        if (uris.isEmpty() || isImporting || activePrompt != null || isLoading) return
        isImporting = true
        viewModelScope.launch {
            try {
                val (added, problem) = importMedia(uris)
                pendingMedia = (pendingMedia + added).distinctBy { it.file }
                turnError = problem
            } finally {
                isImporting = false
            }
        }
    }

    private suspend fun importMedia(uris: List<Uri>): Pair<List<ChatMedia>, String?> = withContext(Dispatchers.IO) {
        val room = MAX_ATTACHMENTS - pendingMedia.size
        val added = mutableListOf<ChatMedia>()
        var problem: String? = if (uris.size > room) "Attach at most $MAX_ATTACHMENTS files to one message." else null
        for (uri in uris.take(maxOf(0, room))) {
            try {
                added += importer.import(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message ?: "Android could not attach that file."
            }
        }
        added to problem
    }

    fun removeAttachment(item: ChatMedia) {
        if (activePrompt != null) return
        pendingMedia = pendingMedia.filter { it.file != item.file }
        viewModelScope.launch(Dispatchers.IO) { runCatching { mediaStore.discardDrafts(listOf(item)) } }
    }

    /** The stored file behind a message's photo or document, or null when it is gone. */
    internal fun mediaFile(item: ChatMedia): File? = mediaStore.find(workspace.id, item.file)

    /**
     * Takes text, photos or files shared from another app. They open in a new chat, ready to send.
     * Called from the activity while it still holds access to the shared files.
     */
    fun receiveShare(text: String?, uris: List<Uri>) {
        viewModelScope.launch {
            ready.await()
            if (activePrompt != null || isModerating || isWorkingChat || isSavingSettings || isImporting) {
                turnError = "Wait for the current reply, then share again."
                return@launch
            }
            isImporting = true
            val (added, problem) = try {
                importMedia(uris)
            } finally {
                isImporting = false
            }
            val fill = {
                promptText = listOf(promptText.trim(), text?.trim().orEmpty()).filter { it.isNotEmpty() }
                    .joinToString("\n\n")
                pendingMedia = (pendingMedia + added).distinctBy { it.file }
                turnError = problem
            }
            if (workspace.messages.isEmpty() && workspace.interruptedTurn == null) {
                fill()
                return@launch
            }
            chatAction { scope ->
                val fresh = scopedWorkspace(Workspace(chatMode = workspace.chatMode))
                withContext(Dispatchers.IO) { chats.save(scope, fresh, chatProject?.id) }
                showWorkspace(fresh)
                fill()
            }
        }
    }

    fun cancelTurn() {
        if (activePrompt == null) return
        turnError = "Stopped waiting. OpenRouter may still finish the submitted request."
        running?.cancel()
    }

    /** Switches this chat between chat mode and agent mode. New chats keep the mode of the chat they replace. */
    fun setChatMode(chat: Boolean) = chatAction { scope ->
        if (workspace.chatMode == chat) return@chatAction
        val next = workspace.copy(chatMode = chat)
        workspace = next
        if (next.messages.isNotEmpty() || library.chats.any { it.id == next.id }) {
            withContext(Dispatchers.IO + NonCancellable) { chats.save(scope, next) }
        }
    }

    fun newConversation(projectId: String? = chatProject?.id) = chatAction { scope ->
        val fresh = scopedWorkspace(Workspace(chatMode = workspace.chatMode))
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
            runCatching { mediaStore.deleteChat(id) }
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
        github.disconnect(id)
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
        if (activePrompt != null || isLoading || isSavingSettings || isWorkingChat || isExporting || isModerating) return
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

    internal suspend fun createCustomizationWithModel(kind: CustomizationKind, prompt: String): String {
        check(activePrompt == null && !isLoading && !isWorkingChat && !isExporting && !isModerating) {
            "Wait for the current task to finish before updating settings."
        }
        require(providerReady) { "Connect OpenRouter in Settings first." }
        val model = selectedModel ?: throw IllegalStateException("Choose a model first.")
        val update = customizationAssistant.create(config, model, kind, prompt)
        saveSettings(update.config)
        return update.summary
    }

    internal suspend fun improveCustomizationWithModel(target: CustomizationTarget, prompt: String): String {
        check(activePrompt == null && !isLoading && !isWorkingChat && !isExporting && !isModerating) {
            "Wait for the current task to finish before updating settings."
        }
        require(providerReady) { "Connect OpenRouter in Settings first." }
        val model = selectedModel ?: throw IllegalStateException("Choose a model first.")
        val update = customizationAssistant.improve(config, model, target, prompt)
        saveSettings(update.config)
        return update.summary
    }

    internal suspend fun createMiniAppWithModel(prompt: String): MiniApp {
        require(providerReady) { "Connect OpenRouter in Settings first." }
        val model = selectedModel ?: throw IllegalStateException("Choose a model first.")
        return miniAppGenerator.createMiniApp(config, model, miniAppRepository(app), prompt)
    }

    internal suspend fun editMiniAppWithModel(id: String, changeRequest: String): MiniApp {
        require(providerReady) { "Connect OpenRouter in Settings first." }
        val model = selectedModel ?: throw IllegalStateException("Choose a model first.")
        return miniAppGenerator.editMiniApp(config, model, miniAppRepository(app), id, changeRequest)
    }

    internal suspend fun searchSkillsDirectory(query: String): List<RemoteSkillSummary> =
        skillsDirectory.search(query)

    internal suspend fun fetchRemoteSkill(skill: RemoteSkillSummary): SavedAgentSkill =
        skillsDirectory.fetchSkill(skill)

    suspend fun saveSettings(updated: AgentConfig) = settingsMutex.withLock {
        check(activePrompt == null && !isLoading && !isWorkingChat && !isExporting && !isModerating)
        updated.settingsError()?.let { throw IllegalArgumentException(it) }
        val clean = updated.copy(apiKey = updated.apiKey.trim(),
            model = updated.model.trim(),
            mcpUrl = updated.mcpUrl.trim(),
            mcpToken = updated.mcpToken.trim(),
            extraToolsJson = updated.extraToolsJson.trim(),
            allowedDomainsCsv = updated.allowedDomainsCsv.trim(),
            systemInstructions = updated.systemInstructions.trim())
        isSavingSettings = true
        try {
            withContext(NonCancellable) {
                val previous = config
                val change = chatScope(previous) != chatScope(clean)
                val resetSandbox = !change &&
                    (previous.sandboxDomains() != clean.sandboxDomains() ||
                        previous.enableShellSandbox != clean.enableShellSandbox)
                val next = withContext(Dispatchers.IO) {
                    val loaded = if (change) loadChats(clean) else null
                    storage.saveConfig(clean)
                    loaded
                }
                config = clean
                if (clean.enableModeration && !previous.enableModeration && textModerator is ContentModerator) {
                    LiteRtInitializer.initialize(app)
                }
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
                storageError = null
            }
        } finally {
            isSavingSettings = false
        }
    }

    private fun loadChats(settings: AgentConfig): Pair<Workspace, ChatLibrary> {
        val scope = chatScope(settings)
        if (chats.importLegacy(scope) { storage.readLegacyWorkspace() }) {
            storage.removeLegacyWorkspace()
        }
        val saved = chats.active(scope)?.let { restoreWorkspace(settings, it) } ?: Workspace()
        return saved to chats.library(scope)
    }

    private fun showWorkspace(saved: Workspace) {
        workspace = saved
        val previous = saved.interruptedTurn
        promptText = previous?.prompt.orEmpty()
        pendingMedia = previous?.media.orEmpty()
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
            try {
                resolver.writeCreatedDocument(uri) {
                    ready.await()
                    resolver.writeDownloadedDocument(uri, application.cacheDir) { openDestination ->
                        client.downloadFile(config.apiKey, containerId, fileId, openDestination)
                    }
                }
                exportMessage = "File saved."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exportMessage = e.userMessage(config.apiKey)
            } finally {
                isExporting = false
            }
        }
    }

    fun clearExportMessage() {
        exportMessage = null
    }

    fun reportResponse(prompt: String, response: String, reason: String) {
        viewModelScope.launch {
            reportMessage = when (val outcome = reportClient.submitReport(prompt, response, reason)) {
                ReportResult.Sent -> "Report submitted. Thank you."
                ReportResult.Unconfigured -> "Reporting endpoint is not configured in this build."
                is ReportResult.Failed -> "Could not send report: ${outcome.message}"
            }
        }
    }

    fun clearReportMessage() {
        reportMessage = null
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
        (textModerator as? AutoCloseable)?.close()
        super.onCleared()
    }

    private fun scopedWorkspace(next: Workspace) = next.copy(accountTag = workspaceTag(config),
        sandboxDomains = config.sandboxDomains())

    private suspend fun saveWorkspace(next: Workspace) {
        val scope = chatScope(config)
        library = withContext(Dispatchers.IO + NonCancellable) {
            chats.save(scope, next)
            chats.library(scope)
        }
    }
}

internal fun chatScope(config: AgentConfig) = ChatScope(OPENROUTER_ID, workspaceTag(config))

internal fun workspaceTag(config: AgentConfig): String = accountTag(config.apiKey)

internal fun restoreWorkspace(config: AgentConfig, saved: Workspace): Workspace = when {
    saved.accountTag != workspaceTag(config) -> Workspace()
    saved.sandboxDomains != config.sandboxDomains() ->
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
