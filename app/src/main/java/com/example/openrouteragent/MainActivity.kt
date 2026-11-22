package com.example.openrouteragent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.ui.res.painterResource
import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The process-wide store keeps a run going after this activity is closed.
            val agent: AgentViewModel = viewModel(viewModelStoreOwner = application as AgentApplication,
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application))
            val appearance = agent.config.sdk.theme
            val dark = isDarkThemeForAppearance(appearance, isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            OpenRouterAgentTheme(darkTheme = dark) { OpenRouterAgentScreen(agent) }
        }
        takeVoiceRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeVoiceRequest(intent)
    }

    private fun takeVoiceRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(AskTileService.EXTRA_START_VOICE, false) == true) {
            intent.removeExtra(AskTileService.EXTRA_START_VOICE)
            VoiceRequests.pending = true
        }
    }
}

internal fun isDarkThemeForAppearance(appearance: String, systemDark: Boolean): Boolean =
    appearance == "oled" || (appearance == "system" && systemDark)

@Composable
fun OpenRouterAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val clipboard = remember(context, scope) { SelectionClipboard(scope) { copyText(context, it) } }
    val colorScheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFFCFC4FF), onPrimary = Color(0xFF23154F),
        primaryContainer = Color(0xFF272046), onPrimaryContainer = Color(0xFFF0EAFF),
        secondary = Color(0xFF9EE4D3), onSecondary = Color(0xFF00382E),
        secondaryContainer = Color(0xFF142E2C), onSecondaryContainer = Color(0xFFD2F3EA),
        background = Color.Black, surface = Color.Black, surfaceTint = Color.Black,
        surfaceDim = Color.Black, surfaceBright = Color(0xFF191821),
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF09090D),
        surfaceContainer = Color(0xFF101017), surfaceContainerHigh = Color(0xFF181820),
        surfaceContainerHighest = Color(0xFF21212A), surfaceVariant = Color(0xFF16161D),
        onSurface = Color(0xFFF1EFF6), onBackground = Color(0xFFF1EFF6),
        onSurfaceVariant = Color(0xFFC2BFCD), outline = Color(0xFF817B96), outlineVariant = Color(0xFF524C62)
    ) else lightColorScheme(
        primary = Color(0xFF544294), onPrimary = Color.White,
        primaryContainer = Color(0xFFEAE2FF), onPrimaryContainer = Color(0xFF271650),
        secondary = Color(0xFF1E665B), secondaryContainer = Color(0xFFCFEDE7),
        background = Color(0xFFFAF8FE), surface = Color(0xFFFAF8FE),
        onSurface = Color(0xFF211C2A), onBackground = Color(0xFF211C2A),
        surfaceVariant = Color(0xFFEAE5F0), onSurfaceVariant = Color(0xFF51495C),
        outline = Color(0xFF81758D), outlineVariant = Color(0xFFC1B6CC)
    )

    CompositionLocalProvider(LocalSelectionClipboard provides clipboard) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

// =============================================================================
// 5. MAIN SCREEN & INTERACTIVE UI
// =============================================================================

private val QUICK_PROMPTS = listOf(
    "Check the Linux OS, Python, and Node.js versions in my sandbox.",
    "Write and run a Python script to simulate 10,000 dice rolls and save a summary report to report.txt.",
    "Search the web for the latest OpenRouter server tools updates and summarize them."
)

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
    var showProviders by rememberSaveable { mutableStateOf(false) }
    var editActiveProvider by rememberSaveable { mutableStateOf(false) }
    val usesOpenRouter = agent.usesOpenRouter
    val providerName = agent.providerName
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMiniApps by rememberSaveable { mutableStateOf(false) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!heard.isNullOrEmpty()) agent.promptText = listOf(agent.promptText.trim(), heard)
            .filter { it.isNotEmpty() }.joinToString(" ")
    }
    val voiceContext = LocalContext.current
    val listen: () -> Unit = {
        runCatching { speech.launch(speechIntent()) }.onFailure {
            android.widget.Toast.makeText(voiceContext, "This phone has no speech recognizer. Install or enable " +
                "Speech Recognition and Synthesis from Google, or another voice input app.",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(VoiceRequests.pending) {
        if (VoiceRequests.pending) {
            VoiceRequests.pending = false
            if (agent.activePrompt == null) listen()
        }
    }
    NotificationPermissionPrompt(agent.activePrompt != null)
    // Reopen Settings when an OpenRouter sign-in finishes while the sheet is closed.
    LaunchedEffect(agent.signInKey) { if (agent.signInKey != null) showSettings = true }
    var showConfigure by remember { mutableStateOf(false) }
    var showSdkSettings by rememberSaveable { mutableStateOf(false) }
    var customizeTab by rememberSaveable { mutableStateOf<String?>(null) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showImageGeneration by rememberSaveable { mutableStateOf(false) }
    val imageDialogState = rememberSaveableStateHolder()
    val usesOpenUncensored = config.activeProviderId == OPENUNCENSORED_ID
    val model = agent.selectedModel
    var showChatLibrary by rememberSaveable { mutableStateOf(false) }
    var deviceFilesTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var workspaceFilesTarget by rememberSaveable { mutableStateOf<String?>(null) }

    var browserContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var previewContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var previewFileId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewPath by rememberSaveable { mutableStateOf("") }
    var pendingExportContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportFile by rememberSaveable { mutableStateOf<String?>(null) }
    val isExporting = agent.isExporting
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
        when {
            !agent.providerReady -> if (usesOpenRouter) { showSettings = true } else {
                editActiveProvider = true
                showProviders = true
            }
            model == null -> showModels = true
            else -> {
                following = true
                agent.send(text)
            }
        }
    }

    val count = messages.size + (if (isRunning) 2 else 0) + (if (interrupted != null) 1 else 0)
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
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column(
                                Modifier.heightIn(min = 48.dp).clickable(
                                    enabled = !agent.isLoading && !isRunning && !agent.isSavingSettings && !agent.isWorkingChat && !isExporting && !agent.isModerating,
                                    onClickLabel = "Choose provider", role = Role.Button,
                                ) {
                                    editActiveProvider = false
                                    showProviders = true
                                },
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (usesOpenRouter) "OpenRouter Agent" else providerName,
                                        Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
                                }
                                val detail = when {
                                    model != null -> model.name
                                    agent.catalogLoading -> "Loading models…"
                                    agent.providerReady -> "Choose a model"
                                    else -> "Connect $providerName"
                                }
                                Text(listOfNotNull(agent.chatProject?.name, detail).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        },
                        actions = {
                            if (agent.providerReady) {
                                TextButton(enabled = !isRunning && !agent.isSavingSettings && !agent.isWorkingChat && !isExporting && !agent.isModerating, onClick = { showModels = true }) { Text("Models") }
                            }
                            TextButton(enabled = !isRunning && !agent.isLoading && !agent.isSavingSettings &&
                                !agent.isWorkingChat && !isExporting && !agent.isModerating, onClick = { showChatLibrary = true }) { Text("Chats") }
                            Box {
                                IconButton(enabled = !agent.isLoading && !isRunning && !isExporting && !agent.isSavingSettings && !agent.isWorkingChat && !agent.isModerating,
                                    onClick = { showConfigure = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Configure")
                                }
                                DropdownMenu(expanded = showConfigure, onDismissRequest = { showConfigure = false }) {
                                    DropdownMenuItem(text = { Text("Providers") }, onClick = {
                                        showConfigure = false
                                        editActiveProvider = false
                                        showProviders = true
                                    })
                                    DropdownMenuItem(text = { Text("Settings") }, onClick = {
                                        showConfigure = false
                                        showSettings = true
                                    })
                                    if (usesOpenRouter) {
                                        DropdownMenuItem(text = { Text("Device files") }, onClick = {
                                            showConfigure = false
                                            deviceFilesTarget = "all"
                                        })
                                        if (agent.chatProject != null && agent.providerReady) {
                                            DropdownMenuItem(text = { Text("All workspace files") }, onClick = {
                                                showConfigure = false
                                                workspaceFilesTarget = "all"
                                            })
                                        }
                                        DropdownMenuItem(text = { Text("SDK settings") }, onClick = {
                                            showConfigure = false
                                            showSdkSettings = true
                                        })
                                        DropdownMenuItem(text = { Text("Custom tools") }, onClick = {
                                            showConfigure = false
                                            customizeTab = "tools"
                                        })
                                        DropdownMenuItem(text = { Text("Subagents") }, onClick = {
                                            showConfigure = false
                                            customizeTab = "subagents"
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("Show reasoning") }, onClick = {
                                        agent.setShowReasoning(!config.sdk.showReasoning)
                                    }, trailingIcon = {
                                        Switch(checked = config.sdk.showReasoning, onCheckedChange = null)
                                    })
                                    DropdownMenuItem(text = { Text("Mini apps") }, onClick = {
                                        showConfigure = false
                                        showMiniApps = true
                                    })
                                    DropdownMenuItem(text = { Text("Skills & app guide") }, onClick = {
                                        showConfigure = false
                                        customizeTab = "skills"
                                    })
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    runProgress?.let {
                        AgentRunStatus(it, agent.approval != null, following, providerName,
                            onActivity = { showRunActivity = true }, onFollow = { following = true })
                    }
                    AgentInputBar(
                        value = agent.promptText, onValueChange = { agent.promptText = it },
                        isRunning = isRunning,
                        isLoading = agent.isLoading || agent.isSavingSettings || agent.isWorkingChat || agent.isModerating,
                        error = agent.turnError, onSend = { send(agent.promptText) }, onStop = agent::cancelTurn,
                        onVoice = listen,
                        onImages = if (usesOpenUncensored) ({ showImageGeneration = true }) else null,
                        chatMode = if (usesOpenRouter && !agent.isLoading) agent.chatMode else null,
                        onChatMode = agent::setChatMode,
                    )
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!agent.isLoading && usesOpenRouter) {
                    StatusCapabilitiesBar(
                        config = if (agent.chatMode) config.withoutTools() else config, activeContainerId = activeContainerId, canDetach = !isRunning && !agent.isWorkingChat && !agent.isSavingSettings,
                        inProject = agent.chatProject != null, attachmentCount = agent.attachedFiles.size,
                        onOpenProjectFiles = { deviceFilesTarget = "current" },
                        onOpenWorkspaceFiles = if (agent.providerReady) ({ workspaceFilesTarget = "current" }) else null,
                        onClearAttachments = agent::clearWorkspaceAttachments,
                        onOpenFiles = { browserContainer = activeContainerId },
                        onResetContainer = agent::detachSandbox
                    )
                }
                agent.storageError?.let {
                    CopyableText(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (agent.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (messages.isEmpty() && activePrompt == null && interrupted == null) {
                    EmptyAgentState(agent.providerReady, {
                        if (usesOpenRouter) showSettings = true else {
                            editActiveProvider = true
                            showProviders = true
                        }
                    }, providerName = providerName, usesOpenRouter = usesOpenRouter) { agent.promptText = it }
                } else {
                    CompositionLocalProvider(LocalTranscriptSelection provides { if (isRunning) following = false }) {
                        LazyColumn(
                            state = listState, modifier = Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth()
                                .nestedScroll(manualScroll),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(messages, key = { _, entry -> entry.id }) { index, entry ->
                                if (entry.isUser) UserMessageBubble(entry.text)
                                else {
                                    val prompt = messages.take(index).lastOrNull { it.isUser }?.text.orEmpty()
                                    AgentResponseCard(
                                        entry = entry,
                                        onCopyText = clipboard::copy,
                                        onFileClick = ::openFile,
                                        showReasoning = config.sdk.showReasoning,
                                        onReport = { reportTarget = ReportTarget(prompt = prompt, response = entry.text) },
                                    )
                                }
                            }
                            if (activePrompt != null) {
                                item("active_prompt") { UserMessageBubble(activePrompt) }
                                item("running") {
                                    if (runProgress != null) LiveAgentReply(runProgress,
                                        showReasoning = config.sdk.showReasoning, onShowReasoning = agent::setShowReasoning)
                                }
                            }
                            if (interrupted != null) {
                                item("interrupted") {
                                    LiveAgentReply(interrupted, interrupted = true,
                                        showReasoning = config.sdk.showReasoning, onShowReasoning = agent::setShowReasoning,
                                        onRetry = { following = true; agent.retryTurn() },
                                        onRetryWithoutTools = if (usesOpenRouter && !agent.chatMode && config.agentToolsEnabled()) {
                                            { following = true; agent.retryTurn(toolsOff = true) }
                                        } else null,
                                        onActivity = { showRunActivity = true })
                                }
                            }
                            item("latest") { Spacer(Modifier.size(1.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (showRunActivity) {
        val selected = runProgress ?: interrupted
        if (selected != null) RunActivityDialog(selected, providerName, { showRunActivity = false },
            config.sdk.showReasoning) { file ->
            showRunActivity = false
            openFile(file.containerId, file.file)
        }
    }
    LaunchedEffect(runProgress == null && interrupted == null) {
        if (runProgress == null && interrupted == null) showRunActivity = false
    }

    if (showProviders) {
        ProvidersSheet(config, editActiveProvider, { showProviders = false }, agent::saveSettings, agent::discoverModels)
    }
    if (showMiniApps) {
        MiniAppsSheet(
            onDismiss = { showMiniApps = false },
            providerReady = agent.providerReady,
            selectedModelName = model?.name,
            onCreateWithAi = agent::createMiniAppWithModel,
            onEditWithAi = agent::editMiniAppWithModel,
            onAskInChat = {
                showMiniApps = false
                if (agent.activePrompt == null) agent.promptText = "Build me a mini app that "
            },
        )
    }
    if (showSettings) {
        SettingsBottomSheet(config, { showSettings = false }, agent::saveSettings, agent.client::validateKey, agent)
    }
    if (showSdkSettings) {
        SdkSettingsSheet(config, agent.availableModels, { showSdkSettings = false }, agent::saveSettings)
    }
    customizeTab?.let { tab ->
        CustomizationSheet(
            config = config,
            guide = agent.builtInGuide,
            initialTab = tab,
            availableModels = agent.availableModels,
            selectedModel = model,
            providerReady = agent.providerReady,
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
        ModelPicker(agent.availableModels, model, usesOpenRouter && config.agentToolsEnabled(), agent.catalogLoading,
            agent.catalogError, { agent.refreshModels() }, { showModels = false }, providerName, !usesOpenRouter) { chosen ->
            agent.chooseModel(chosen)
            showModels = false
        }
    }
    if (usesOpenUncensored && showImageGeneration && !agent.isLoading) {
        imageDialogState.SaveableStateProvider(workspaceTag(config)) {
            OpenUncensoredImagesDialog(agent, onDismiss = { showImageGeneration = false }, onConnect = {
                showImageGeneration = false
                editActiveProvider = true
                showProviders = true
            })
        }
    }
    agent.approval?.let { approval ->
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
    }) {
        following = true
        showChatLibrary = false
    }
    val currentArea = agent.fileArea()
    val localTarget = deviceFilesTarget
    if (!agent.isLoading && usesOpenRouter && localTarget != null) {
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
    if (!agent.isLoading && usesOpenRouter && agent.providerReady && cloudTarget != null) {
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

internal val REPORT_REASONS = listOf("Sexual content", "Harassment", "Other")

@Composable
internal fun ReportResponseDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var selectedReason by remember { mutableStateOf(REPORT_REASONS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report response") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Select a reason for reporting this AI response. Only the prompt, response, and reason are sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                REPORT_REASONS.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = selectedReason == reason,
                                role = Role.RadioButton,
                                onClick = { selectedReason = reason },
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedReason == reason,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(selectedReason) }) {
                Text("Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

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

// =============================================================================
// 6. UI SUBCOMPONENTS
// =============================================================================

@Composable
private fun StatusCapabilitiesBar(
    config: AgentConfig,
    activeContainerId: String?,
    canDetach: Boolean,
    inProject: Boolean,
    attachmentCount: Int,
    onOpenProjectFiles: () -> Unit,
    onOpenWorkspaceFiles: (() -> Unit)?,
    onClearAttachments: () -> Unit,
    onOpenFiles: () -> Unit,
    onResetContainer: () -> Unit
) {
    Surface(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(onClick = onOpenProjectFiles, label = { Text(if (inProject) "Project files" else "Chat files") },
                modifier = Modifier.heightIn(min = 48.dp))
            if (onOpenWorkspaceFiles != null) {
                AssistChip(onClick = onOpenWorkspaceFiles, label = {
                    Text(if (attachmentCount == 0) "Workspace files" else "Workspace files · $attachmentCount attached")
                }, modifier = Modifier.heightIn(min = 48.dp))
                if (attachmentCount > 0) TextButton(onClick = onClearAttachments, enabled = canDetach) {
                    Text("Clear attachments")
                }
            }
            if (config.enableShellSandbox) {
                if (activeContainerId != null) {
                    AssistChip(onClick = onOpenFiles, label = { Text("Sandbox files") }, modifier = Modifier.heightIn(min = 48.dp))
                    if (!inProject) TextButton(onClick = onResetContainer, enabled = canDetach) { Text("Detach") }
                } else StatusPill("Hosted shell", MaterialTheme.colorScheme.primary)
            }
            if (config.enableWebSearch) {
                StatusPill(
                    label = "Web Search",
                    accent = MaterialTheme.colorScheme.primary
                )
            }
            if (config.enableShellSandbox && config.allowSandboxNetwork) {
                StatusPill(
                    label = "Egress Allowlist",
                    accent = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )

        }
    }
}

@Composable
private fun EmptyAgentState(
    hasApiKey: Boolean,
    onOpenSettings: () -> Unit,
    providerName: String,
    usesOpenRouter: Boolean,
    onSelectPrompt: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "What are you working on?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (usesOpenRouter) "Run code, work with files, and search the web using OpenRouter’s hosted tools."
                else "Start a conversation with $providerName.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        if (!hasApiKey) {
            Button(onClick = onOpenSettings) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Connect $providerName")
            }
            Spacer(Modifier.height(20.dp))
        }

        Text(
            text = "Try a prompt",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Start).semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))

        val suggestions = if (usesOpenRouter) QUICK_PROMPTS else listOf(
            "Explain a topic in plain language.", "Help me outline a project.")
        suggestions.forEach { sample ->
            Card(
                onClick = { onSelectPrompt(sample) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = sample,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            CopyableText(
                text = text,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
internal fun AgentResponseCard(
    entry: ChatEntry,
    onCopyText: (String) -> Unit,
    onFileClick: (containerId: String, fileRef: ContainerFileRef) -> Unit,
    showReasoning: Boolean = true,
    onReport: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val isWarningCard = entry.isError || entry.isModerated
    val containerColor = if (isWarningCard) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            1.dp,
            if (isWarningCard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Main Assistant Text
            Row(verticalAlignment = Alignment.Top) {
                if (isWarningCard) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = if (entry.isModerated) "Content policy warning" else "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(end = 8.dp, top = 2.dp)
                            .size(18.dp)
                    )
                }
                CopyableText(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isWarningCard) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurface,
                )
            }

            entry.notice?.let {
                CopyableText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }

            // Optional collapsible reasoning summary
            if (entry.toolActivity.isNotEmpty()) {
                var showToolActivity by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { showToolActivity = !showToolActivity }) {
                    Text(if (showToolActivity) "Hide tool activity" else "Tool activity")
                }
                if (showToolActivity) entry.toolActivity.forEach { ToolActivityRow(it) }
            }
            if (showReasoning && !entry.reasoningSummary.isNullOrBlank()) {
                CollapsibleReasoningBox(entry.reasoningSummary)
            }

            // Web Search Queries strip
            if (entry.searchQueries.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Web Search",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        entry.searchQueries.forEach { q ->
                            CopyableText(
                                text = q,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // Hosted Linux Sandbox Execution Cards
            entry.shellSteps.forEachIndexed { idx, step ->
                SandboxTerminalCard(
                    stepIndex = idx + 1,
                    step = step,
                    onFileClick = onFileClick
                )
            }

            if (entry.citations.isNotEmpty()) {
                OutlinedSection(title = "Sources") {
                    entry.citations.forEach { cite ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(cite.url) } }) {
                            Text(cite.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Footer with usage metadata, Copy reply (when safe), and Report button on every AI bubble
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    entry.modelUsed?.let {
                        CopyableText(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isWarningCard) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.usage?.let {
                        Text(
                            it.display(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isWarningCard) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isWarningCard) {
                        TextButton(
                            onClick = { onCopyText(entry.text) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Copy reply")
                        }
                    }
                    TextButton(
                        onClick = onReport,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Report")
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleReasoningBox(summary: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Reasoning",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                CopyableText(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SandboxTerminalCard(
    stepIndex: Int,
    step: ShellExecutionStep,
    onFileClick: (containerId: String, fileRef: ContainerFileRef) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val terminalBg = Color(0xFF0D1117)
    val terminalHeaderBg = Color(0xFF161B22)
    val terminalText = Color(0xFFE6EDF3)
    val terminalMuted = Color(0xFFA1AAB5)
    val terminalGreen = Color(0xFF3FB950)
    val terminalRed = Color(0xFFF85149)
    val terminalStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val outputStyle = terminalStyle.copy(fontSize = 11.sp, lineHeight = 16.sp)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = terminalBg,
        border = BorderStroke(1.dp, Color(0xFF626E7C)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Terminal Title Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(terminalHeaderBg)
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(terminalGreen)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Sandbox run $stepIndex",
                        color = terminalText,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    if (!step.containerId.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = step.containerId.take(10),
                            color = terminalMuted,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = terminalMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (step.results.isNotEmpty()) {
                        step.results.forEach { res ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    CopyableText(
                                        text = res.command,
                                        color = terminalGreen,
                                        style = terminalStyle,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "[${res.outcome}]",
                                        color = if (res.outcome == "exit 0" || res.outcome == "done") {
                                            terminalMuted
                                        } else {
                                            terminalRed
                                        },
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }

                                if (res.stdout.isNotBlank()) {
                                    CopyableText(res.stdout, color = terminalText, style = outputStyle)
                                }

                                if (res.stderr.isNotBlank()) {
                                    CopyableText(res.stderr, color = terminalRed, style = outputStyle)
                                }
                            }
                        }
                    } else {
                        step.commands.forEach { cmd ->
                            CopyableText(cmd, color = terminalGreen, style = terminalStyle)
                        }
                    }

                    // Clickable container output files (`cfile_*`)
                    if (step.files.isNotEmpty() && !step.containerId.isNullOrBlank()) {
                        HorizontalDivider(color = Color(0xFF30363D))
                        Text(
                            text = "Created or updated files",
                            color = terminalMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(step.files) { fileRef ->
                                AssistChip(
                                    onClick = { onFileClick(step.containerId, fileRef) },
                                    label = {
                                        Text(
                                            text = fileRef.path,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = terminalText
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isRunning: Boolean,
    isLoading: Boolean,
    error: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onImages: (() -> Unit)?,
    chatMode: Boolean? = null,
    onChatMode: (Boolean) -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (onImages != null || chatMode != null) {
                Row(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (chatMode != null) {
                        // Agent mode can use tools over several steps. Chat mode only answers, which is cheaper and faster.
                        listOf(false to "Agent", true to "Chat").forEach { (chat, label) ->
                            FilterChip(selected = chatMode == chat, onClick = { onChatMode(chat) },
                                enabled = !isLoading && !isRunning, label = { Text(label) })
                        }
                        Text(if (chatMode) "No tools · faster, cheaper" else "Tools on",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                    if (onImages != null) {
                        TextButton(onClick = onImages, enabled = !isLoading && !isRunning) { Text("Generate images") }
                    }
                }
            }
            if (error != null) {
                CopyableText(error, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CopyableOutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(if (chatMode == true) "Message" else "Message the agent") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    enabled = !isRunning && !isLoading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() })
                )
                if (!isRunning) IconButton(onClick = onVoice, enabled = !isLoading, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = "Speak a message")
                }
                FilledIconButton(
                    onClick = if (isRunning) onStop else onSend,
                    enabled = isRunning || (!isLoading && value.isNotBlank()),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Close else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isRunning) "Stop waiting" else "Send prompt"
                    )
                }
            }
        }
    }
}

// =============================================================================
// 7. SETTINGS BOTTOM SHEET & FILE PREVIEW DIALOG
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBottomSheet(
    initialConfig: AgentConfig,
    onDismiss: () -> Unit,
    onSave: suspend (AgentConfig) -> Unit,
    onValidate: suspend (String) -> Unit,
    agent: AgentViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKey by remember { mutableStateOf(initialConfig.apiKey) }
    var signedIn by remember { mutableStateOf(false) }
    val signInContext = LocalContext.current
    LaunchedEffect(agent.signInKey) {
        agent.takeSignInKey()?.let { apiKey = it; signedIn = true }
    }
    var showApiKey by remember { mutableStateOf(false) }
    var enableShell by remember { mutableStateOf(initialConfig.enableShellSandbox) }
    var enableWeb by remember { mutableStateOf(initialConfig.enableWebSearch) }
    var enableDateTime by remember { mutableStateOf(initialConfig.enableDateTime) }
    var allowNetwork by remember { mutableStateOf(initialConfig.allowSandboxNetwork) }
    var allowedDomains by remember { mutableStateOf(initialConfig.allowedDomainsCsv) }
    var instructions by remember { mutableStateOf(initialConfig.systemInstructions) }
    var webFetch by remember { mutableStateOf(initialConfig.webFetch) }
    var workspaceFiles by remember { mutableStateOf(initialConfig.enableWorkspaceFiles) }
    var deviceTools by remember { mutableStateOf(initialConfig.deviceTools) }
    var miniApps by remember { mutableStateOf(initialConfig.miniApps) }
    var enableModeration by remember { mutableStateOf(initialConfig.enableModeration) }
    var maxSteps by remember { mutableStateOf(initialConfig.maxSteps.toString()) }
    var maxCost by remember { mutableStateOf(initialConfig.maxCost?.toString().orEmpty()) }
    var temperature by remember { mutableStateOf(initialConfig.temperature?.toString().orEmpty()) }
    var mcpUrl by remember { mutableStateOf(initialConfig.mcpUrl) }
    var mcpToken by remember { mutableStateOf(initialConfig.mcpToken) }
    var toolsJson by rememberSaveable { mutableStateOf(initialConfig.extraToolsJson) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val updated = initialConfig.copy(apiKey = apiKey, enableShellSandbox = enableShell, enableWebSearch = enableWeb,
        enableDateTime = enableDateTime, webFetch = webFetch, enableWorkspaceFiles = workspaceFiles,
        deviceTools = deviceTools, miniApps = miniApps, enableModeration = enableModeration, allowSandboxNetwork = allowNetwork,
        allowedDomainsCsv = allowedDomains, systemInstructions = instructions, maxSteps = maxSteps.toIntOrNull() ?: 0,
        maxCost = maxCost.toDoubleOrNull(), temperature = temperature.toDoubleOrNull(), mcpUrl = mcpUrl.trim(),
        mcpToken = mcpToken, extraToolsJson = toolsJson)

    ModalBottomSheet(
        onDismissRequest = { if (!working) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(.93f)
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }.padding(bottom = 12.dp),
            )
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedSection(title = "Connection") {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; testResult = null; error = null },
                        label = { Text("OpenRouter API key") },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showApiKey = !showApiKey }) {
                                Text(if (showApiKey) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { runCatching { uriHandler.openUri("https://openrouter.ai/settings/keys") } }) {
                            Text("Get an API key")
                        }
                        TextButton(enabled = !working && apiKey.isNotBlank(), onClick = {
                            val keyToTest = apiKey.trim()
                            error = AgentConfig(apiKey = keyToTest).settingsError()
                            if (error == null) {
                                working = true
                                scope.launch {
                                    try {
                                        onValidate(keyToTest)
                                        if (apiKey.trim() == keyToTest) testResult = "OpenRouter accepted the key."
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (apiKey.trim() == keyToTest) error = e.userMessage(keyToTest)
                                    } finally {
                                        working = false
                                    }
                                }
                            }
                        }) { Text("Test key") }
                    }
                    testResult?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (agent.signInBusy) {
                        Text("Finish signing in on the OpenRouter page in your browser, then return here.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = agent::cancelSignIn) { Text("Cancel sign-in") }
                    } else {
                        FilledTonalButton(enabled = !working, onClick = {
                            signedIn = false
                            agent.signInWithOpenRouter { url ->
                                runCatching {
                                    signInContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addCategory(Intent.CATEGORY_BROWSABLE))
                                }.isSuccess
                            }
                        }) { Text(if (apiKey.isBlank()) "Sign in with OpenRouter" else "Sign in again with OpenRouter") }
                    }
                    agent.signInError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (signedIn && apiKey.isNotBlank()) {
                        Text("Signed in. OpenRouter created a key for this app. Tap Save to connect.",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (initialConfig.apiKey.isNotBlank() && apiKey.trim() != initialConfig.apiKey) {
                        Text("Changing the key starts a new conversation.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedSection(title = "Content moderation") {
                    SettingsToggleRow(
                        title = "On-device content moderation",
                        subtitle = "Checks prompts and AI responses locally before sending or displaying them. Off by default.",
                        checked = enableModeration,
                        onCheckedChange = { enableModeration = it }
                    )
                }

                OutlinedSection(title = "Agent tools") {
                    SettingsToggleRow(
                        title = "Hosted Linux Sandbox (openrouter:shell)",
                        subtitle = "Runs commands in a hosted Linux container",
                        checked = enableShell,
                        onCheckedChange = { enableShell = it }
                    )

                    SettingsToggleRow(
                        title = "Web Search (openrouter:web_search)",
                        subtitle = "Real-time search with cited URLs",
                        checked = enableWeb,
                        onCheckedChange = { enableWeb = it }
                    )

                    SettingsToggleRow(
                        title = "Date & Time (openrouter:datetime)",
                        subtitle = "Gives the model current UTC date/time",
                        checked = enableDateTime,
                        onCheckedChange = { enableDateTime = it }
                    )

                    SettingsToggleRow("Web fetch", "Reads pages supplied by URL.", webFetch, { webFetch = it })
                    SettingsToggleRow("Workspace files tool", "Lets unfiled chats work with files in your OpenRouter workspace.",
                        workspaceFiles, { workspaceFiles = it })
                    SubagentsSettings(
                        json = toolsJson,
                        enabled = !working,
                        availableModels = agent.availableModels,
                        catalogLoading = agent.catalogLoading,
                        catalogError = agent.catalogError,
                        onRefreshModels = { agent.refreshModels() },
                        onChange = { toolsJson = it; error = null },
                    )
                    SettingsToggleRow("Device time zone", "Lets the agent read your time zone and UTC offset.", deviceTools,
                        { deviceTools = it })
                    SettingsToggleRow("Mini apps", "Lets the agent build small apps you can open, add to your home " +
                        "screen and share. Find them under Configure → Mini apps.", miniApps, { miniApps = it })

                    if (enableShell) {
                        SettingsToggleRow(
                            title = "Sandbox Outbound Network Allowlist",
                            subtitle = "Changing network access starts a fresh sandbox.",
                            checked = allowNetwork,
                            onCheckedChange = { allowNetwork = it }
                        )

                        if (allowNetwork) {
                            CopyableOutlinedTextField(
                                value = allowedDomains,
                                onValueChange = { allowedDomains = it },
                                label = { Text("Allowed domains, separated by commas") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                    }
                }

                OutlinedSection {
                    CopyableOutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = { Text("System instructions") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }

                OutlinedSection {
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "Hide advanced settings" else "Advanced settings")
                    }
                    if (advanced) {
                        val decimal = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                        CopyableOutlinedTextField(maxSteps, { maxSteps = it }, label = { Text("Agent step limit (1–30)") },
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        CopyableOutlinedTextField(maxCost, { maxCost = it }, label = { Text("Stop threshold in USD (optional)") },
                            supportingText = { Text("Checks accrued cost between steps.") }, keyboardOptions = decimal,
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        CopyableOutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature (optional)") },
                            supportingText = { Text("Applies when the selected model supports it.") }, keyboardOptions = decimal,
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        CopyableOutlinedTextField(mcpUrl, { mcpUrl = it }, label = { Text("Remote MCP HTTPS endpoint") },
                            supportingText = { Text("The app asks before calling a remote tool.") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (mcpUrl.isNotBlank()) {
                            OutlinedTextField(mcpToken, { mcpToken = it }, label = { Text("MCP bearer token (optional)") },
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                                singleLine = true)
                        }
                        CopyableOutlinedTextField(toolsJson, { toolsJson = it; error = null }, label = { Text("Additional server tools (JSON)") },
                            supportingText = { Text("Supports web fetch, model search, image generation, advisor, subagent, and fusion.") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 8)
                    }
                }

            }

            HorizontalDivider()
            error?.let { CopyableText(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !working, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(enabled = !working, onClick = {
                    error = when {
                        maxCost.isNotBlank() && updated.maxCost == null -> "Enter a valid cost threshold."
                        temperature.isNotBlank() && updated.temperature == null -> "Enter a valid temperature."
                        else -> updated.settingsError()
                    }
                    if (error != null) return@Button
                    working = true
                    scope.launch {
                        try {
                            onSave(updated)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.userMessage(apiKey.trim())
                        } finally {
                            working = false
                        }
                    }
                }, modifier = Modifier.weight(1f)) {
                    Text(if (working) "Working…" else "Save")
                }
            }
        }
    }
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

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
