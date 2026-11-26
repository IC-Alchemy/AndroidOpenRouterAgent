package com.example.openrouteragent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRouterAgentScreen(agent: AgentViewModel = viewModel()) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalSelectionClipboard.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val config = agent.config
    val messages = agent.workspace.messages
    val activeContainerId = agent.sandboxId.takeIf { config.apiKey.isNotBlank() }
    val activePrompt = agent.activePrompt
    val isRunning = activePrompt != null
    val runProgress = agent.progress
    val interrupted = agent.interrupted
    var showRunActivity by rememberSaveable { mutableStateOf(false) }
    var following by rememberSaveable { mutableStateOf(true) }
    val manualScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) following = false
                return Offset.Zero
            }
        }
    }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var showChatTools by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showWorkspaces by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showMiniApps by rememberSaveable { mutableStateOf(false) }
    var showDecisions by rememberSaveable { mutableStateOf(false) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!heard.isNullOrEmpty()) {
            if (agent.side.isOpen) {
                agent.side.prompt = listOf(agent.side.prompt.trim(), heard).filter { it.isNotEmpty() }.joinToString(" ")
            } else {
                agent.promptText = listOf(agent.promptText.trim(), heard).filter { it.isNotEmpty() }.joinToString(" ")
                agent.promptFromVoice = true
            }
        }
    }
    val speaker = rememberReplySpeaker()
    LaunchedEffect(agent.replyToSpeak) {
        agent.replyToSpeak?.let { speaker.speak(it.id, it.text) }
        agent.replyToSpeak = null
    }
    // Clearing the composer means the next prompt is typed, so its reply isn't read aloud.
    LaunchedEffect(agent.promptText.isBlank()) { if (agent.promptText.isBlank()) agent.promptFromVoice = false }
    val voiceContext = LocalContext.current
    var viewingMedia by remember { mutableStateOf<ChatMedia?>(null) }
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { uris -> agent.attach(uris) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = cameraUri
        cameraUri = null
        if (taken && uri != null) agent.attach(listOf(android.net.Uri.parse(uri)))
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        agent.attach(uris)
    }
    val attach: (AttachSource) -> Unit = { source ->
        runCatching {
            when (source) {
                AttachSource.Photos -> pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly))
                AttachSource.Camera -> {
                    val uri = cameraPhotoUri(voiceContext)
                    cameraUri = uri.toString()
                    takePhoto.launch(uri)
                }
                AttachSource.Files -> pickFiles.launch(arrayOf("application/pdf", "audio/mpeg", "audio/wav",
                    "audio/x-wav", "text/*", "application/json", "application/xml", "image/*"))
            }
        }.onFailure {
            android.widget.Toast.makeText(voiceContext, when (source) {
                AttachSource.Camera -> "This phone has no camera app that can take a photo for the app."
                else -> "Android could not open the picker."
            }, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val listen: () -> Unit = {
        speaker.stop() // so the recognizer doesn't hear the last reply
        runCatching { speech.launch(speechIntent()) }.onFailure {
            android.widget.Toast.makeText(voiceContext, "This phone has no speech recognizer. Install or enable " +
                "Speech Recognition and Synthesis from Google, or another voice input app.",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(VoiceRequests.pending) {
        if (VoiceRequests.pending) {
            VoiceRequests.pending = false
            showUsage = false
            if (agent.activePrompt == null) listen()
        }
    }
    LaunchedEffect(ShareRequests.pending) {
        if (ShareRequests.pending) {
            ShareRequests.pending = false
            showUsage = false
        }
    }
    NotificationPermissionPrompt(agent.activePrompt != null)
    // Reopen Settings when an OpenRouter sign-in finishes while the sheet is closed.
    LaunchedEffect(agent.signInKey) { if (agent.signInKey != null) showSettings = true }
    var showConfigure by remember { mutableStateOf(false) }
    var customizeTab by rememberSaveable { mutableStateOf<String?>(null) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val model = agent.selectedModel
    val chatTitle = agent.library.chats.firstOrNull { it.id == agent.workspace.id }
        ?.title?.takeIf { it.isNotBlank() }
    val isExporting = agent.isExporting
    val canManageChat = !isRunning && !agent.isLoading && !agent.isSavingSettings &&
        !agent.isWorkingChat && !isExporting && !agent.isModerating
    var showChatLibrary by rememberSaveable { mutableStateOf(false) }
    var deviceFilesTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var workspaceFilesTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var githubTarget by rememberSaveable { mutableStateOf<String?>(null) }

    var browserContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var previewContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var previewFileId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewPath by rememberSaveable { mutableStateOf("") }
    var pendingExportContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportFile by rememberSaveable { mutableStateOf<String?>(null) }
    var reportTarget by remember { mutableStateOf<ReportTarget?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val containerId = pendingExportContainer
        val fileId = pendingExportFile
        pendingExportContainer = null
        pendingExportFile = null
        if (uri != null && containerId != null && fileId != null) agent.exportFile(uri, containerId, fileId)
    }

    LaunchedEffect(agent.exportMessage) {
        agent.exportMessage?.let {
            snackbar.showSnackbar(it)
            agent.clearExportMessage()
        }
    }

    LaunchedEffect(agent.reportMessage) {
        agent.reportMessage?.let {
            snackbar.showSnackbar(it)
            agent.clearReportMessage()
        }
    }

    fun openFile(containerId: String, file: ContainerFileRef) {
        previewContainer = containerId
        previewFileId = file.fileId
        previewPath = file.path
    }

    fun send(text: String) {
        // Slash commands such as /btw are handled here and never reach the main chat.
        if (agent.runSlashCommand(text)) return
        when {
            !agent.providerReady -> showSettings = true
            model == null -> showModels = true
            else -> {
                following = true
                agent.send(text)
            }
        }
    }

    val stoppedTurn = agent.workspace.interruptedTurn?.takeIf { interrupted != null && !isRunning }
    val count = messages.size + (if (isRunning) 2 else 0) + (if (interrupted != null) 1 else 0) +
        (if (stoppedTurn != null) 1 else 0)
    LaunchedEffect(agent.workspace.id, count, runProgress?.answer?.length, runProgress?.summary?.length, following) {
        if (following && count > 0) listState.requestScrollToItem(count)
    }
    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollForward && !listState.isScrollInProgress }.collect { atEnd ->
            if (atEnd) following = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        AgentRuntimeHost(agent, Modifier.align(Alignment.TopStart).size(1.dp))
        if (showUsage) {
            UsageScreen(
                client = agent.client,
                apiKey = config.apiKey,
                savedManagementKey = config.managementKey,
                activityManagementKey = config.activityManagementKey,
                onSaveActivityKey = agent::saveActivityManagementKey,
                onBack = { showUsage = false },
                onConnect = { showSettings = true },
            )
        } else {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        chatTitle ?: "OpenRouter Agent",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val context = agent.chatProject?.name
                                    if (context != null) {
                                        Text(context, style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            },
                            navigationIcon = {
                                ControlTooltip("Chats") {
                                    IconButton(onClick = { showChatLibrary = true }, enabled = canManageChat) {
                                        Icon(StudioSymbols.Chats, contentDescription = "Chats")
                                    }
                                }
                            },
                            actions = {
                                ControlTooltip("Settings") {
                                    IconButton(onClick = { showSettings = true }, enabled = canManageChat) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                }
                                Box {
                                    ControlTooltip("More options") {
                                        IconButton(onClick = { showConfigure = true }, enabled = canManageChat) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                                        }
                                    }
                                    DropdownMenu(expanded = showConfigure, onDismissRequest = { showConfigure = false }) {
                                        DropdownMenuItem(text = { Text("Device files") },
                                            leadingIcon = { Icon(StudioSymbols.Folder, null) }, onClick = {
                                            showConfigure = false
                                            deviceFilesTarget = "all"
                                        })
                                        if (agent.chatProject != null && agent.openRouterReady) {
                                            DropdownMenuItem(text = { Text("All workspace files") },
                                                leadingIcon = { Icon(StudioSymbols.Workspace, null) }, onClick = {
                                                showConfigure = false
                                                workspaceFilesTarget = "all"
                                            })
                                        }
                                        // Every setting lives behind the Settings icon. This menu holds places to go.
                                        DropdownMenuItem(text = { Text("Workspaces") }, onClick = {
                                            showConfigure = false
                                            showWorkspaces = true
                                        })
                                        DropdownMenuItem(text = { Text("Mini apps") },
                                            leadingIcon = { Icon(StudioSymbols.MiniApps, null) }, onClick = {
                                            showConfigure = false
                                            showMiniApps = true
                                        })
                                        DropdownMenuItem(text = { Text("Decisions") },
                                            leadingIcon = { Icon(StudioSymbols.Decisions, null) }, onClick = {
                                            showConfigure = false
                                            showDecisions = true
                                        })
                                        DropdownMenuItem(text = { Text("App guide") }, onClick = {
                                            showConfigure = false
                                            showGuide = true
                                        })
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                        )
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Row(
                                    Modifier.widthIn(max = 840.dp).fillMaxWidth()
                                        .padding(start = 8.dp, end = 12.dp, bottom = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val modelLabel = when {
                                        model != null -> model.name
                                        agent.catalogLoading -> "Loading models…"
                                        agent.providerReady -> "Choose a model"
                                        else -> "Connect a provider"
                                    }
                                    TextButton(
                                        onClick = {
                                            if (agent.providerReady) showModels = true else showSettings = true
                                        },
                                        enabled = canManageChat,
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(StudioSymbols.Models, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Text(modelLabel, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    ChatToolsActions(
                                        expanded = showChatTools,
                                        onToggle = { showChatTools = !showChatTools },
                                        attachmentCount = agent.attachedFiles.size,
                                    )
                                    val usageLabel = LocalControlLabels.current.visible
                                    ControlTooltip("Usage", enabled = !usageLabel) {
                                        FilledTonalButton(
                                            onClick = { showUsage = true },
                                            enabled = canManageChat && !agent.side.isOpen,
                                            contentPadding = PaddingValues(horizontal = if (usageLabel) 12.dp else 10.dp,
                                                vertical = 6.dp),
                                            modifier = Modifier.semantics { contentDescription = "Usage" },
                                        ) {
                                            Icon(StudioSymbols.Usage, contentDescription = null, modifier = Modifier.size(18.dp))
                                            if (usageLabel) {
                                                Spacer(Modifier.width(6.dp))
                                                Text("Usage")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(
                            thickness = StudioHairlineWidth,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        runProgress?.let {
                            AgentRunStatus(it, agent.approval != null, following,
                                onActivity = { showRunActivity = true }, onFollow = { following = true })
                        }
                        AgentInputBar(
                            value = agent.promptText, onValueChange = { agent.promptText = it },
                            isRunning = isRunning,
                            isLoading = agent.isLoading || agent.isSavingSettings || agent.isWorkingChat || agent.isModerating,
                            error = agent.turnError, onSend = { send(agent.promptText) }, onStop = agent::cancelTurn,
                            onVoice = listen,
                            chatMode = if (!agent.isLoading) agent.chatMode else null,
                            onChatMode = agent::setChatMode,
                            attachments = if (isRunning) agent.activeMedia else agent.pendingMedia,
                            mediaFile = agent::mediaFile,
                            onOpenAttachment = { viewingMedia = it },
                            onRemoveAttachment = agent::removeAttachment,
                            onAttach = attach,
                            canAttachImages = model?.accepts("image") != false,
                            importing = agent.isImporting,
                            placeholder = if (isRunning) "Type /btw to ask on the side" else null,
                            onSideConversation = agent.side::open,
                            sideConversationBusy = agent.side.isRunning || agent.side.unread,
                            // During a main run the field takes only slash commands, so /btw works while the chat continues.
                            acceptsInputWhileRunning = true,
                            sendWhileRunning = isRunning && SlashCommands.parse(agent.promptText) != null,
                        )
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!agent.isLoading && showChatTools) {
                        Box(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 8.dp)) {
                            StatusCapabilitiesBar(
                                config = if (agent.chatMode) config.withoutTools() else config,
                                activeContainerId = activeContainerId,
                                canDetach = !isRunning && !agent.isWorkingChat && !agent.isSavingSettings,
                                inProject = agent.chatProject != null, attachmentCount = agent.attachedFiles.size,
                                onOpenProjectFiles = { deviceFilesTarget = "current" },
                                onOpenWorkspaceFiles = if (agent.openRouterReady) ({ workspaceFilesTarget = "current" }) else null,
                                onClearAttachments = agent::clearWorkspaceAttachments,
                                onOpenFiles = { browserContainer = activeContainerId },
                                onResetContainer = agent::detachSandbox,
                            )
                        }
                    }
                    agent.storageError?.let {
                        CopyableText(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (agent.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (messages.isEmpty() && activePrompt == null && interrupted == null) {
                        EmptyAgentState(
                            hasApiKey = agent.providerReady,
                            onOpenSettings = { showSettings = true },
                            tools = config.takeUnless { agent.chatMode || agent.usingOpenAiDirect },
                        ) { agent.promptText = it }
                    } else {
                        CompositionLocalProvider(LocalTranscriptSelection provides { if (isRunning) following = false }) {
                            LazyColumn(
                                state = listState, modifier = Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth()
                                    .nestedScroll(manualScroll),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(messages, key = { _, entry -> entry.id }) { index, entry ->
                                    if (entry.isUser) UserMessageBubble(entry.text, entry.media, agent::mediaFile) {
                                        viewingMedia = it
                                    }
                                    else {
                                        val prompt = messages.take(index).lastOrNull { it.isUser }?.text.orEmpty()
                                        AgentResponseCard(
                                            entry = entry,
                                            onCopyText = clipboard::copy,
                                            onFileClick = ::openFile,
                                            showReasoning = config.sdk.showReasoning,
                                            renderMarkdown = config.sdk.renderMarkdown,
                                            onReport = { reportTarget = ReportTarget(prompt = prompt, response = entry.text) },
                                            mediaFile = agent::mediaFile,
                                            onOpenMedia = { viewingMedia = it },
                                            speaking = speaker.speakingId == entry.id,
                                            onSpeak = { speaker.toggle(entry.id, entry.text) },
                                        )
                                    }
                                }
                                if (activePrompt != null) {
                                    item("active_prompt") {
                                        UserMessageBubble(activePrompt, agent.activeMedia, agent::mediaFile) { viewingMedia = it }
                                    }
                                    item("running") {
                                        if (runProgress != null) LiveAgentReply(runProgress,
                                            showReasoning = config.sdk.showReasoning, onShowReasoning = agent::setShowReasoning,
                                            renderMarkdown = config.sdk.renderMarkdown)
                                    }
                                }
                                if (stoppedTurn != null) {
                                    // The message of a reply that stopped stays in the chat, above what the agent did.
                                    item("stopped_prompt") {
                                        UserMessageBubble(stoppedTurn.prompt, stoppedTurn.media, agent::mediaFile) {
                                            viewingMedia = it
                                        }
                                    }
                                }
                                if (interrupted != null) {
                                    item("interrupted") {
                                        LiveAgentReply(interrupted, interrupted = true,
                                            showReasoning = config.sdk.showReasoning, onShowReasoning = agent::setShowReasoning,
                                            renderMarkdown = config.sdk.renderMarkdown,
                                            onRetry = { following = true; agent.retryTurn() },
                                            onRetryWithoutTools = if (!agent.chatMode && config.agentToolsEnabled()) {
                                                { following = true; agent.retryTurn(toolsOff = true) }
                                            } else null,
                                            onActivity = { showRunActivity = true },
                                            onContinue = if (agent.canContinue) {
                                                { following = true; agent.continueTurn() }
                                            } else null)
                                    }
                                }
                                item("latest") { Spacer(Modifier.size(1.dp)) }
                            }
                        }
                    }
                }
            }

            SideConversationPanel(
                agent = agent,
                onVoice = listen,
                onCopyText = clipboard::copy,
                onReport = { prompt, response -> reportTarget = ReportTarget(prompt = prompt, response = response) },
                showReasoning = config.sdk.showReasoning,
                renderMarkdown = config.sdk.renderMarkdown,
            )
        }
    }

    viewingMedia?.let { item ->
        MediaViewer(item, agent.mediaFile(item)) { viewingMedia = null }
    }

    if (showRunActivity) {
        val selected = runProgress ?: interrupted
        if (selected != null) RunActivityDialog(selected, "OpenRouter", { showRunActivity = false }) { file ->
            showRunActivity = false
            openFile(file.containerId, file.file)
        }
    }
    LaunchedEffect(runProgress == null && interrupted == null) {
        if (runProgress == null && interrupted == null) showRunActivity = false
    }

    if (showMiniApps) {
        MiniAppsSheet(
            onDismiss = { showMiniApps = false },
            // Creating with AI calls OpenRouter, so it needs an OpenRouter key and an OpenRouter model.
            providerReady = agent.openRouterReady && !agent.usingOpenAiDirect,
            selectedModelName = model?.name,
            onCreateWithAi = agent::createMiniAppWithModel,
            onEditWithAi = agent::editMiniAppWithModel,
            builds = agent.miniAppBuilds,
            onAskInChat = {
                showMiniApps = false
                if (agent.activePrompt == null) agent.promptText = "Build me a mini app that "
            },
        )
    }
    if (showSettings) {
        SettingsBottomSheet(config, { showSettings = false }, agent::saveSettings, agent.client::validateKey, agent,
            routedPrice = { sdk -> model?.let { agent.routedPriceFor(it.id, sdk) } },
            onOpenCustomization = { customizeTab = "tools" })
    }
    if (showWorkspaces) {
        WorkspacesSheet(agent) { showWorkspaces = false }
    }
    if (showDecisions) {
        DecisionsSheet(agent) { showDecisions = false }
    }
    if (showGuide) {
        AppGuideSheet(agent.builtInGuide) { showGuide = false }
    }
    customizeTab?.let { tab ->
        CustomizationSheet(
            config = config,
            initialTab = tab,
            availableModels = agent.availableModels,
            selectedModel = model,
            // Creating with AI calls OpenRouter, so it needs an OpenRouter key and an OpenRouter model.
            providerReady = agent.openRouterReady && !agent.usingOpenAiDirect,
            catalogLoading = agent.catalogLoading,
            catalogError = agent.catalogError,
            onRefreshModels = { agent.refreshModels() },
            onCreateWithModel = agent::createCustomizationWithModel,
            onImproveWithModel = agent::improveCustomizationWithModel,
            onSearchSkills = agent::searchSkillsDirectory,
            onFetchRemoteSkill = agent::fetchRemoteSkill,
            onDismiss = { customizeTab = null },
            onSave = agent::saveSettings,
        )
    }
    if (showModels) {
        ModelPicker(agent.availableModels, model, config.agentToolsEnabled(), agent.catalogLoading,
            agent.catalogError, { agent.refreshModels() }, { showModels = false },
            routedPrice = { id -> agent.routedPriceFor(id, config.sdk) },
            workspaceName = agent.selectedWorkspace?.name?.takeIf { agent.workspaceRules != null }) { chosen ->
            agent.chooseModel(chosen)
            showModels = false
        }
    }
    agent.approval?.takeIf { agent.githubReview == null }?.let { approval ->
        var answer by rememberSaveable(approval.decision) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { agent.decideTool(false) },
            title = { Text(if (approval.asksUser) approval.name else "Allow ${approval.name}?") },
            text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (approval.url.isNotBlank()) CopyableText(approval.url)
                    if (approval.savesSettings) Text("The change will be saved and available for future messages.")
                    OutlinedSection(title = "Arguments") {
                        CopyableText(approval.arguments, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                    if (approval.asksUser) EditField("Your answer", answer, { answer = it }, 3)
                }
            },
            confirmButton = {
                TextButton(onClick = { agent.decideTool(true, answer.trim()) },
                    enabled = !approval.asksUser || answer.isNotBlank()) {
                    Text(when {
                        approval.asksUser -> "Send answer"
                        approval.savesSettings -> "Save change"
                        else -> "Allow once"
                    })
                }
            },
            dismissButton = {
                TextButton(onClick = { agent.decideTool(false) }) {
                    Text(if (approval.asksUser) "Skip" else "Deny")
                }
            },
        )
    }
    if (showChatLibrary) ChatLibrarySheet(agent, onProjectFiles = { id ->
        deviceFilesTarget = id
        showChatLibrary = false
    }, onGitHub = { id ->
        githubTarget = id
        showChatLibrary = false
    }) {
        following = true
        showChatLibrary = false
    }
    agent.library.projects.firstOrNull { it.id == githubTarget }?.let { project ->
        GitHubProjectDialog(project, agent.github) { githubTarget = null }
    }
    agent.githubReview?.let { review ->
        GitHubReviewDialog(review) { approved -> agent.decideGitHubReview(review, approved) }
    }
    val currentArea = agent.fileArea()
    val localTarget = deviceFilesTarget
    if (!agent.isLoading && localTarget != null) {
        val area = when (localTarget) {
            "all" -> null
            "current" -> currentArea
            else -> agent.fileArea(localTarget)
        }
        val project = agent.library.projects.firstOrNull { it.id == area?.projectId }
        ProjectFilePanel(agent, area, when {
            project != null -> "Files in ${project.name}"
            area == null -> "Device files"
            else -> "Chat files"
        }) { deviceFilesTarget = null }
    }
    val cloudTarget = workspaceFilesTarget
    if (!agent.isLoading && agent.openRouterReady && cloudTarget != null) {
        val apiKey = config.apiKey
        val prefix = if (cloudTarget == "all") "" else currentArea.remotePrefix
        WorkspaceFilesDialog(
            client = agent.client, apiKey = apiKey, prefix = prefix,
            onDismiss = { workspaceFilesTarget = null },
            onCopyToProject = { file ->
                agent.prepareFileArea(currentArea)
                agent.files.copyWorkspaceFile(apiKey, currentArea, file)
            },
            isAttached = { id -> agent.attachedFiles.any { it.id == id } },
            onToggleAttachment = (agent::toggleWorkspaceFile).takeIf {
                agent.canAttachWorkspaceFiles && prefix == currentArea.remotePrefix
            },
            localPreview = { agent.files.previewUpload(apiKey, it) },
            localExport = { file, uri -> agent.files.exportUpload(apiKey, file, uri) },
            onSaveRevision = { file, text -> agent.files.saveWorkspaceRevision(apiKey, file, text) },
            onDeleted = { file ->
                agent.detachWorkspaceFile(file.id)
                scope.launch { agent.files.forgetUpload(apiKey, file) }
            },
        )
    }
    val browsing = browserContainer
    if (!agent.isLoading && browsing != null && previewFileId == null) {
        ContainerFilesDialog(agent.client, config.apiKey, browsing, { browserContainer = null }) { openFile(browsing, it) }
    }
    val previewing = previewContainer
    val fileId = previewFileId
    if (!agent.isLoading && previewing != null && fileId != null) {
        ContainerFilePreviewDialog(
            client = agent.client, apiKey = config.apiKey, containerId = previewing,
            fileId = fileId, title = previewPath, isExporting = isExporting,
            onDismiss = { previewFileId = null; previewContainer = null },
            onExport = {
                val filename = previewPath.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[\\p{Cntrl}/]"), "_").take(180).ifBlank { "sandbox-file" }
                pendingExportContainer = previewing
                pendingExportFile = fileId
                export.launch(filename)
            },
            onSaveCopy = {
                agent.prepareFileArea(currentArea)
                agent.files.copySandboxFile(config.apiKey, currentArea, previewing, fileId, previewPath)
            },
            onPromote = {
                agent.files.saveSandboxToWorkspace(config.apiKey, currentArea, previewing, fileId, previewPath)
            },
        )
    }
    reportTarget?.let { target ->
        ReportResponseDialog(
            onDismiss = { reportTarget = null },
            onSubmit = { reason ->
                reportTarget = null
                agent.reportResponse(target.prompt, target.response, reason)
            },
        )
    }
}

private data class ReportTarget(val prompt: String, val response: String)

@Composable
private fun ProjectFilePanel(
    agent: AgentViewModel,
    area: ProjectFileArea?,
    title: String,
    close: () -> Unit,
) {
    val files = agent.files
    val apiKey = agent.config.apiKey
    val upload: (suspend (String) -> WorkspaceFileRef)? = if (area != null && apiKey.isNotBlank()) {
        { path -> files.upload(apiKey, area, path) }
    } else null
    ProjectFilesDialog(
        title = title, onDismiss = close,
        list = { withContext(Dispatchers.IO) { files.store.list(area, it) } },
        createFolder = { path ->
            if (area != null) agent.prepareFileArea(area)
            withContext(Dispatchers.IO) { files.store.createFolder(area, path) }
        },
        preview = { withContext(Dispatchers.IO) { files.store.preview(area, it) } },
        writeText = { path, text, original ->
            if (area != null) agent.prepareFileArea(area)
            withContext(Dispatchers.IO) { files.store.writeText(area, path, text, original) }
        },
        importFile = { uri, folder ->
            if (area != null) agent.prepareFileArea(area)
            files.importFile(area, uri, folder)
        },
        exportFile = { path, uri -> files.exportFile(area, path, uri) },
        upload = upload,
    )
}

/** A new file in the shared cache folder for the camera app to write a photo into. */
private fun cameraPhotoUri(context: android.content.Context): android.net.Uri {
    val dir = java.io.File(context.cacheDir, "shared/camera").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
}

/** Set by the Ask tile; the screen starts voice input when it sees it. */
object VoiceRequests {
    var pending by mutableStateOf(false)
}

/** Android's speech recognizer, on the device when possible. */
private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask the agent")
    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

/** Asks once, when the first run starts, so background progress and replies can be shown. */
@Composable
private fun NotificationPermissionPrompt(running: Boolean) {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(running) {
        if (!running || RunNotifications.canPost(context)) return@LaunchedEffect
        val prefs = context.getSharedPreferences("ui_state", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return@LaunchedEffect
        prefs.edit().putBoolean("asked_notifications", true).apply()
        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}
