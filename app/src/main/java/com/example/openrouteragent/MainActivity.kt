package com.example.openrouteragent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val agent: AgentViewModel = viewModel()
            val appearance = agent.config.sdk.theme
            val dark = appearance == "oled" || (appearance == "system" && isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            OpenRouterAgentTheme(darkTheme = dark) { OpenRouterAgentScreen(agent) }
        }
    }
}

@Composable
fun OpenRouterAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
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
        onSurfaceVariant = Color(0xFFC2BFCD), outlineVariant = Color(0xFF302F3B)
    ) else lightColorScheme(
        primary = Color(0xFF544294), onPrimary = Color.White,
        primaryContainer = Color(0xFFEAE2FF), onPrimaryContainer = Color(0xFF271650),
        secondary = Color(0xFF1E665B), secondaryContainer = Color(0xFFCFEDE7),
        background = Color(0xFFFAF8FE), surface = Color(0xFFFAF8FE),
        onSurface = Color(0xFF211C2A), onBackground = Color(0xFF211C2A),
        surfaceVariant = Color(0xFFEAE5F0), onSurfaceVariant = Color(0xFF51495C),
        outlineVariant = Color(0xFFD7CEDF)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val config = agent.config
    val messages = agent.workspace.messages
    val activeContainerId = agent.workspace.containerId
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
    var showConfigure by remember { mutableStateOf(false) }
    var showSdkSettings by rememberSaveable { mutableStateOf(false) }
    var customizeTab by rememberSaveable { mutableStateOf<String?>(null) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val model = agent.selectedModel
    var showChatLibrary by rememberSaveable { mutableStateOf(false) }

    var browserContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var previewContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var previewFileId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewPath by rememberSaveable { mutableStateOf("") }
    var pendingExportContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportFile by rememberSaveable { mutableStateOf<String?>(null) }
    val isExporting = agent.isExporting

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
                TopAppBar(
                    title = {
                        Column {
                            Text(if (usesOpenRouter) "OpenRouter Agent" else providerName,
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(enabled = !agent.isLoading && !isRunning && !agent.isSavingSettings && !agent.isWorkingChat && !isExporting) {
                                    editActiveProvider = false
                                    showProviders = true
                                })
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
                            TextButton(enabled = !isRunning && !agent.isSavingSettings && !agent.isWorkingChat && !isExporting, onClick = { showModels = true }) { Text("Models") }
                        }
                        TextButton(enabled = !isRunning && !agent.isLoading && !agent.isSavingSettings &&
                            !agent.isWorkingChat && !isExporting, onClick = { showChatLibrary = true }) { Text("Chats") }
                        Box {
                            IconButton(enabled = !agent.isLoading && !isRunning && !isExporting && !agent.isSavingSettings && !agent.isWorkingChat,
                                onClick = { showConfigure = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Configure")
                            }
                            DropdownMenu(expanded = showConfigure, onDismissRequest = { showConfigure = false }) {
                                DropdownMenuItem(text = { Text("Providers") }, onClick = {
                                    showConfigure = false
                                    editActiveProvider = false
                                    showProviders = true
                                })
                                DropdownMenuItem(text = { Text(if (usesOpenRouter) "Settings" else "OpenRouter settings") }, onClick = {
                                    showConfigure = false
                                    showSettings = true
                                })
                                if (usesOpenRouter) {
                                    DropdownMenuItem(text = { Text("SDK settings") }, onClick = {
                                        showConfigure = false
                                        showSdkSettings = true
                                    })
                                    DropdownMenuItem(text = { Text("Custom tools") }, onClick = {
                                        showConfigure = false
                                        customizeTab = "tools"
                                    })
                                }
                                DropdownMenuItem(text = { Text("Skills & app guide") }, onClick = {
                                    showConfigure = false
                                    customizeTab = "skills"
                                })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    runProgress?.let {
                        AgentRunStatus(it, agent.approval != null, following, providerName,
                            onActivity = { showRunActivity = true }, onFollow = { following = true })
                    }
                    AgentInputBar(
                        value = agent.promptText, onValueChange = { agent.promptText = it },
                        isRunning = isRunning, isLoading = agent.isLoading || agent.isSavingSettings || agent.isWorkingChat,
                        error = agent.turnError, onSend = { send(agent.promptText) }, onStop = agent::cancelTurn
                    )
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                if (!agent.isLoading && usesOpenRouter) {
                    StatusCapabilitiesBar(
                        config = config, activeContainerId = activeContainerId, canDetach = !isRunning && !agent.isWorkingChat && !agent.isSavingSettings,
                        onOpenFiles = { browserContainer = activeContainerId },
                        onResetContainer = agent::detachSandbox
                    )
                }
                agent.storageError?.let {
                    Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error,
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
                    LazyColumn(
                        state = listState, modifier = Modifier.weight(1f).nestedScroll(manualScroll),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(messages, key = { it.id }) { entry ->
                            if (entry.isUser) UserMessageBubble(entry.text)
                            else AgentResponseCard(entry, {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard.setPrimaryClip(ClipData.newPlainText("Agent response", it))
                                if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar("Copied") }
                            }, ::openFile)
                        }
                        if (activePrompt != null) {
                            item("active_prompt") { UserMessageBubble(activePrompt) }
                            item("running") {
                                if (runProgress != null) LiveAgentReply(runProgress)
                            }
                        }
                        if (interrupted != null) {
                            item("interrupted") {
                                LiveAgentReply(interrupted, interrupted = true,
                                    onRetry = { following = true; agent.retryTurn() },
                                    onRetryWithoutTools = if (usesOpenRouter && config.agentToolsEnabled()) {
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

    if (showRunActivity) {
        val selected = runProgress ?: interrupted
        if (selected != null) RunActivityDialog(selected, providerName, { showRunActivity = false }) { file ->
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
    if (showSettings) {
        SettingsBottomSheet(config, { showSettings = false }, agent::saveSettings, agent.client::validateKey)
    }
    if (showSdkSettings) {
        SdkSettingsSheet(config, agent.availableModels, { showSdkSettings = false }, agent::saveSettings)
    }
    customizeTab?.let { tab ->
        CustomizationSheet(config, agent.builtInGuide, tab, { customizeTab = null }, agent::saveSettings)
    }
    if (showModels) {
        ModelPicker(agent.availableModels, model, usesOpenRouter && config.agentToolsEnabled(), agent.catalogLoading,
            agent.catalogError, { agent.refreshModels() }, { showModels = false }, providerName, !usesOpenRouter) { chosen ->
            agent.chooseModel(chosen)
            showModels = false
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
                    if (approval.url.isNotBlank()) Text(approval.url)
                    SelectionContainer {
                        Text(approval.arguments, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (approval.asksUser) EditField("Your answer", answer, { answer = it }, 3)
                }
            },
            confirmButton = {
                TextButton(onClick = { agent.decideTool(true, answer.trim()) },
                    enabled = !approval.asksUser || answer.isNotBlank()) {
                    Text(if (approval.asksUser) "Send answer" else "Allow once")
                }
            },
            dismissButton = {
                TextButton(onClick = { agent.decideTool(false) }) {
                    Text(if (approval.asksUser) "Skip" else "Deny")
                }
            },
        )
    }
    if (showChatLibrary) ChatLibrarySheet(agent) {
        following = true
        showChatLibrary = false
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
            }
        )
    }
}

// =============================================================================
// 6. UI SUBCOMPONENTS
// =============================================================================

@Composable
private fun StatusCapabilitiesBar(
    config: AgentConfig,
    activeContainerId: String?,
    canDetach: Boolean,
    onOpenFiles: () -> Unit,
    onResetContainer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (config.enableShellSandbox) {
            if (activeContainerId != null) {
                AssistChip(onClick = onOpenFiles, label = { Text("Sandbox files") })
                TextButton(onClick = onResetContainer, enabled = canDetach) { Text("Detach") }
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
            fontWeight = FontWeight.Bold
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
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(Modifier.height(8.dp))

        val suggestions = if (usesOpenRouter) QUICK_PROMPTS else listOf(
            "Explain a topic in plain language.", "Help me outline a project.")
        suggestions.forEach { sample ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSelectPrompt(sample) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun AgentResponseCard(
    entry: ChatEntry,
    onCopyText: (String) -> Unit,
    onFileClick: (containerId: String, fileRef: ContainerFileRef) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val containerColor = if (entry.isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            1.dp,
            if (entry.isError) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Main Assistant Text
            Row(verticalAlignment = Alignment.Top) {
                if (entry.isError) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(end = 8.dp, top = 2.dp)
                            .size(18.dp)
                    )
                }
                SelectionContainer {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (entry.isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            entry.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }

            // Optional collapsible reasoning summary
            if (entry.toolActivity.isNotEmpty()) {
                var showToolActivity by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { showToolActivity = !showToolActivity }) {
                    Text(if (showToolActivity) "Hide tool activity" else "Tool activity")
                }
                if (showToolActivity) entry.toolActivity.forEach { ToolActivityRow(it) }
            }
            if (!entry.reasoningSummary.isNullOrBlank()) {
                CollapsibleReasoningBox(entry.reasoningSummary)
            }

            // Web Search Queries strip
            if (entry.searchQueries.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
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
                            Text(
                                text = "• $q",
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

            // Citations list if present
            if (entry.citations.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Sources",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    entry.citations.forEach { cite ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(cite.url) } }) {
                            Text(cite.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Footer with usage metadata and Copy button
            if (!entry.isError) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        entry.modelUsed?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        entry.usage?.let {
                            Text(it.display(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(
                        onClick = { onCopyText(entry.text) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        text = "Reasoning Summary",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle reasoning",
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
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
    val terminalMuted = Color(0xFF8B949E)
    val terminalGreen = Color(0xFF3FB950)
    val terminalRed = Color(0xFFF85149)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = terminalBg,
        border = BorderStroke(1.dp, Color(0xFF30363D)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Terminal Title Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(terminalHeaderBg)
                    .clickable { expanded = !expanded }
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
                    contentDescription = "Toggle terminal",
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
                                    Text(
                                        text = "$ ${res.command}",
                                        color = terminalGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
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
                                    SelectionContainer {
                                        Text(
                                            text = res.stdout.trimEnd(),
                                            color = terminalText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }

                                if (res.stderr.isNotBlank()) {
                                    SelectionContainer {
                                        Text(
                                            text = res.stderr.trimEnd(),
                                            color = terminalRed,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        step.commands.forEach { cmd ->
                            Text(
                                text = "$ $cmd",
                                color = terminalGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
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
    onStop: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column {
            if (error != null) {
                Text(error, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("Message the agent") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    enabled = !isRunning && !isLoading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() })
                )
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
private fun SettingsBottomSheet(
    initialConfig: AgentConfig,
    onDismiss: () -> Unit,
    onSave: suspend (AgentConfig) -> Unit,
    onValidate: suspend (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKey by remember { mutableStateOf(initialConfig.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var enableShell by remember { mutableStateOf(initialConfig.enableShellSandbox) }
    var enableWeb by remember { mutableStateOf(initialConfig.enableWebSearch) }
    var enableDateTime by remember { mutableStateOf(initialConfig.enableDateTime) }
    var allowNetwork by remember { mutableStateOf(initialConfig.allowSandboxNetwork) }
    var allowedDomains by remember { mutableStateOf(initialConfig.allowedDomainsCsv) }
    var instructions by remember { mutableStateOf(initialConfig.systemInstructions) }
    var webFetch by remember { mutableStateOf(initialConfig.webFetch) }
    var deviceTools by remember { mutableStateOf(initialConfig.deviceTools) }
    var maxSteps by remember { mutableStateOf(initialConfig.maxSteps.toString()) }
    var maxCost by remember { mutableStateOf(initialConfig.maxCost?.toString().orEmpty()) }
    var temperature by remember { mutableStateOf(initialConfig.temperature?.toString().orEmpty()) }
    var mcpUrl by remember { mutableStateOf(initialConfig.mcpUrl) }
    var mcpToken by remember { mutableStateOf(initialConfig.mcpToken) }
    var toolsJson by remember { mutableStateOf(initialConfig.extraToolsJson) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val updated = initialConfig.copy(apiKey = apiKey, enableShellSandbox = enableShell, enableWebSearch = enableWeb,
        enableDateTime = enableDateTime, webFetch = webFetch, deviceTools = deviceTools, allowSandboxNetwork = allowNetwork,
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // API Key Input
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
            if (initialConfig.apiKey.isNotBlank() && apiKey.trim() != initialConfig.apiKey) {
                Text("Changing the key starts a new conversation.",
                    style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            // Server Tools Toggles
            Text(
                text = "OpenRouter Server Tools",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

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
            SettingsToggleRow("Device time zone", "Lets the agent read your time zone and UTC offset.", deviceTools,
                { deviceTools = it })

            if (enableShell) {
                SettingsToggleRow(
                    title = "Sandbox Outbound Network Allowlist",
                    subtitle = "Changing network access starts a fresh sandbox.",
                    checked = allowNetwork,
                    onCheckedChange = { allowNetwork = it }
                )

                if (allowNetwork) {
                    OutlinedTextField(
                        value = allowedDomains,
                        onValueChange = { allowedDomains = it },
                        label = { Text("Allowed domains, separated by commas") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            }

            HorizontalDivider()

            // System Instructions
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("System Instructions") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Hide advanced agent settings" else "Advanced agent settings")
            }
            if (advanced) {
                val decimal = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                OutlinedTextField(maxSteps, { maxSteps = it }, label = { Text("Agent step limit (1–30)") },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(maxCost, { maxCost = it }, label = { Text("Stop threshold in USD (optional)") },
                    supportingText = { Text("Checks accrued cost between steps.") }, keyboardOptions = decimal,
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature (optional)") },
                    supportingText = { Text("Applies when the selected model supports it.") }, keyboardOptions = decimal,
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(mcpUrl, { mcpUrl = it }, label = { Text("Remote MCP HTTPS endpoint") },
                    supportingText = { Text("The app asks before calling a remote tool.") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (mcpUrl.isNotBlank()) {
                    OutlinedTextField(mcpToken, { mcpToken = it }, label = { Text("MCP bearer token (optional)") },
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        singleLine = true)
                }
                OutlinedTextField(toolsJson, { toolsJson = it }, label = { Text("Additional server tools (JSON)") },
                    supportingText = { Text("Supports model search, image generation, advisor, subagent, and fusion.") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

