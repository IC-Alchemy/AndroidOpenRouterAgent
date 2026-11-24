package com.example.openrouteragent

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SaveFileIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SaveFile",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 20f)
            horizontalLineTo(19f)
            verticalLineTo(18f)
            horizontalLineTo(5f)
            close()
            moveTo(19f, 9f)
            horizontalLineTo(15f)
            verticalLineTo(3f)
            horizontalLineTo(9f)
            verticalLineTo(9f)
            horizontalLineTo(5f)
            lineTo(12f, 16f)
            close()
        }
    }.build()
}

private val MINI_APP_IDEAS = listOf(
    "Pomodoro timer with chime",
    "Tip splitter",
    "Daily habit tracker",
    "Unit converter",
)

private sealed interface AiMiniAppDialogMode {
    data object Create : AiMiniAppDialogMode
    data class Edit(val app: MiniApp) : AiMiniAppDialogMode
}

/**
 * Native Mini App Launcher Grid with AI Prompt Builder, Conversational Edits,
 * `.miniapp` / `.html` SAF import, and Long-Press Context Menu.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MiniAppsSheet(
    onDismiss: () -> Unit,
    providerReady: Boolean,
    selectedModelName: String?,
    onCreateWithAi: suspend (String) -> MiniApp,
    onEditWithAi: suspend (String, String) -> MiniApp,
    onAskInChat: () -> Unit,
    repository: MiniAppRepository? = null,
    onOpenApp: ((MiniApp) -> Unit)? = null,
) {
    val context = LocalContext.current
    val repo = remember(repository, context) { repository ?: miniAppRepository(context) }
    val openApp: (MiniApp) -> Unit = remember(onOpenApp, context) {
        onOpenApp ?: { app -> MiniAppActivity.open(context, app) }
    }
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<MiniApp>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var contextMenuApp by remember { mutableStateOf<MiniApp?>(null) }
    var aiDialogMode by remember { mutableStateOf<AiMiniAppDialogMode?>(null) }
    var renamingApp by remember { mutableStateOf<MiniApp?>(null) }
    var editingAppearanceApp by remember { mutableStateOf<MiniApp?>(null) }
    var clearingDataApp by remember { mutableStateOf<MiniApp?>(null) }
    var deletingApp by remember { mutableStateOf<MiniApp?>(null) }
    var pendingRawHtmlImport by remember { mutableStateOf<MiniAppImportPreview.RawHtml?>(null) }
    var pendingExportAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportAppName by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        apps = withContext(Dispatchers.IO) { runCatching { repo.list() }.getOrDefault(emptyList()) }
    }

    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val appId = pendingExportAppId
        val appName = pendingExportAppName
        pendingExportAppId = null
        pendingExportAppName = null
        if (uri == null || appId == null) return@rememberLauncherForActivityResult
        scope.launch {
            errorMessage = null
            statusMessage = null
            try {
                context.contentResolver.writeCreatedDocument(uri) { writeExportBundleToUri(context, repo, appId, uri) }
                statusMessage = "Saved ${appName ?: "mini app"} as a .miniapp file."
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                errorMessage = err.message ?: "Could not save ${appName ?: "mini app"}."
            }
        }
    }

    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            errorMessage = null
            statusMessage = null
            val outcome = withContext(Dispatchers.IO) {
                runCatching { readImportPreviewFromUri(context, repo, uri) }
            }
            outcome.onSuccess { preview ->
                when (preview) {
                    is MiniAppImportPreview.Bundle -> {
                        val installOutcome = withContext(Dispatchers.IO) {
                            runCatching { repo.installImport(preview) }
                        }
                        installOutcome.onSuccess { installed ->
                            statusMessage = "Imported ${installed.name}."
                            refresh++
                        }.onFailure { err ->
                            errorMessage = err.message ?: "Could not import this mini app."
                        }
                    }
                    is MiniAppImportPreview.RawHtml -> {
                        pendingRawHtmlImport = preview
                    }
                }
            }.onFailure { err ->
                errorMessage = err.message ?: "Could not open this file as a mini app."
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Mini apps",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            val showLabels = LocalControlLabels.current.visible
            if (showLabels) {
                Spacer(Modifier.height(4.dp))
                Text("Each app stores its own data.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ControlTooltip("Create with AI", enabled = !showLabels) {
                    FilledTonalButton(onClick = {
                        errorMessage = null
                        aiDialogMode = AiMiniAppDialogMode.Create
                    }) { SymbolLabel("Create with AI", StudioSymbols.NewMiniApp) }
                }
                ControlTooltip("Import", enabled = !showLabels) {
                    OutlinedButton(onClick = {
                        errorMessage = null
                        try {
                            importDocument.launch(arrayOf("*/*"))
                        } catch (_: ActivityNotFoundException) {
                            errorMessage = "No file picker is available to import mini apps."
                        }
                    }) { SymbolLabel("Import", StudioSymbols.Import) }
                }
            }

            statusMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            errorMessage?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            val list = apps
            when {
                list == null -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                list.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(64.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "No mini apps yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Describe an app in plain English—like a Pomodoro timer, tip calculator, or habit tracker—or import a file shared by a friend.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = onAskInChat) {
                            Text("Or ask the agent in chat")
                        }
                    }
                }
                else -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Tap to open · Hold an icon for options",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 92.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(list, key = { it.id }) { app ->
                            val iconFile = remember(app.id, app.iconUri) { repo.iconFilePath(app.id, app.iconUri) }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(
                                        onClick = { openApp(app) },
                                        onLongClick = { contextMenuApp = app },
                                        onClickLabel = "Open ${app.name}",
                                        onLongClickLabel = "Options for ${app.name}",
                                    )
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                            ) {
                                MiniAppIconBadge(
                                    app = app,
                                    iconFile = iconFile,
                                    size = 64.dp,
                                    cornerRadius = 18.dp,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Long-press context menu bottom sheet
    contextMenuApp?.let { app ->
        val iconFile = remember(app.id, app.iconUri) { repo.iconFilePath(app.id, app.iconUri) }
        ModalBottomSheet(
            onDismissRequest = { contextMenuApp = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    MiniAppIconBadge(app = app, iconFile = iconFile, size = 44.dp, cornerRadius = 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = app.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = buildList {
                            add("Version ${app.version}")
                            if (!app.allowNetwork) add("Offline")
                        }.joinToString(" · ")
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                ContextMenuRow(
                    icon = Icons.Default.Build,
                    label = "Edit with AI",
                    subtitle = "Describe changes while keeping saved data",
                    onClick = {
                        contextMenuApp = null
                        aiDialogMode = AiMiniAppDialogMode.Edit(app)
                    },
                )
                ContextMenuRow(
                    icon = Icons.Default.Edit,
                    label = "Rename",
                    onClick = {
                        contextMenuApp = null
                        renamingApp = app
                    },
                )
                ContextMenuRow(
                    icon = Icons.Default.Star,
                    label = "Change Icon",
                    subtitle = "Photo, preset icon, emoji, or color",
                    onClick = {
                        contextMenuApp = null
                        editingAppearanceApp = app
                    },
                )
                ContextMenuRow(
                    icon = Icons.Default.Home,
                    label = "Add to Home Screen",
                    onClick = {
                        contextMenuApp = null
                        scope.launch {
                            val pinned = runCatching { MiniAppActivity.pinAsync(context, app) }.getOrDefault(false)
                            statusMessage = if (pinned) {
                                "Confirm on your home screen to add ${app.name}."
                            } else {
                                "Your launcher does not support pinned shortcuts."
                            }
                        }
                    },
                )
                ContextMenuRow(
                    icon = Icons.Default.Share,
                    label = "Export / Share",
                    subtitle = "Share as a .miniapp bundle",
                    onClick = {
                        contextMenuApp = null
                        scope.launch {
                            val exported = withContext(Dispatchers.IO) {
                                runCatching { MiniAppActivity.prepareShareArchive(context, app) }
                            }
                            exported.onSuccess { archive ->
                                runCatching { MiniAppActivity.launchShareSheet(context, app, archive) }
                                    .onFailure { err -> errorMessage = err.message ?: "Could not share ${app.name}." }
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Could not export ${app.name}."
                            }
                        }
                    },
                )
                ContextMenuRow(
                    icon = SaveFileIcon,
                    label = "Save .miniapp File",
                    subtitle = "Save a backup copy to device storage",
                    onClick = {
                        contextMenuApp = null
                        pendingExportAppId = app.id
                        pendingExportAppName = app.name
                        try {
                            exportDocument.launch(miniAppArchiveFileName(app.name))
                        } catch (_: ActivityNotFoundException) {
                            pendingExportAppId = null
                            pendingExportAppName = null
                            errorMessage = "No file manager is available to save files."
                        }
                    },
                )
                ContextMenuRow(
                    icon = Icons.Default.Refresh,
                    label = "Clear App Data",
                    subtitle = "Reset saved state for this mini app",
                    onClick = {
                        contextMenuApp = null
                        clearingDataApp = app
                    },
                )
                ContextMenuRow(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    destructive = true,
                    onClick = {
                        contextMenuApp = null
                        deletingApp = app
                    },
                )
            }
        }
    }

    // AI Prompt Builder / Conversational Edit dialog
    aiDialogMode?.let { mode ->
        val editIconFile = remember(mode) {
            (mode as? AiMiniAppDialogMode.Edit)?.let { repo.iconFilePath(it.app.id, it.app.iconUri) }
        }
        MiniAppAiBuilderDialog(
            mode = mode,
            iconFile = editIconFile,
            providerReady = providerReady,
            selectedModelName = selectedModelName,
            onDismiss = { aiDialogMode = null },
            onSubmit = { prompt ->
                val result = when (mode) {
                    AiMiniAppDialogMode.Create -> onCreateWithAi(prompt)
                    is AiMiniAppDialogMode.Edit -> onEditWithAi(mode.app.id, prompt)
                }
                if (mode is AiMiniAppDialogMode.Edit) {
                    withContext(Dispatchers.IO) { MiniAppActivity.updatePinnedShortcut(context, result) }
                }
                aiDialogMode = null
                statusMessage = when (mode) {
                    AiMiniAppDialogMode.Create -> "Created ${result.name}."
                    is AiMiniAppDialogMode.Edit -> "Updated ${result.name} (saved data kept)."
                }
                errorMessage = null
                refresh++
                if (mode is AiMiniAppDialogMode.Create) openApp(result)
            },
        )
    }

    // Quick Rename dialog
    renamingApp?.let { app ->
        var draftName by remember(app.id) { mutableStateOf(app.name) }
        var saving by remember(app.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) renamingApp = null },
            title = { Text("Rename Mini App") },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it.take(40) },
                    enabled = !saving,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !saving && draftName.trim().isNotEmpty(),
                    onClick = {
                        val trimmed = draftName.trim()
                        saving = true
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    val iconUpdate = resolveMetadataIconUpdate(
                                        repo = repo,
                                        app = app,
                                        newName = trimmed,
                                        newThemeColor = app.themeColor,
                                        requestedUpdate = MiniAppIconUpdate.Keep,
                                    )
                                    repo.updateMetadata(app.id, trimmed, app.themeColor, iconUpdate)
                                        .also { updated -> MiniAppActivity.updatePinnedShortcut(context, updated) }
                                }
                            }
                            renamingApp = null
                            outcome.onSuccess { updated ->
                                errorMessage = null
                                statusMessage = "Renamed to ${updated.name}."
                                refresh++
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Could not rename ${app.name}."
                            }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { renamingApp = null }) { Text("Cancel") }
            },
        )
    }

    // Change Icon / Edit appearance dialog
    editingAppearanceApp?.let { app ->
        val existingFile = remember(app.id, app.iconUri) { repo.iconFilePath(app.id, app.iconUri) }
        var existingIconSvg by remember(app.id, app.updatedAt) { mutableStateOf<String?>(null) }
        var existingAiGeneratedIcon by remember(app.id, app.updatedAt) { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(app.id, app.updatedAt) {
            val loadedManifest = withContext(Dispatchers.IO) { repo.manifest(app.id) }
            existingIconSvg = loadedManifest?.iconSvg
            existingAiGeneratedIcon = loadedManifest?.aiGeneratedIcon == true || loadedManifest?.iconSvg != null
        }
        MiniAppMetadataDialog(
            title = "Edit Mini App",
            confirmLabel = "Save",
            initialName = app.name,
            initialThemeColor = app.themeColor,
            initialIconUri = app.iconUri,
            existingIconFile = existingFile,
            existingIconCacheKey = Triple(app.id, app.updatedAt, app.version),
            existingIconSvg = existingIconSvg,
            existingAiGeneratedIcon = existingAiGeneratedIcon,
            onDismiss = { editingAppearanceApp = null },
            onSave = { name, themeColor, iconUpdate ->
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val effectiveUpdate = resolveMetadataIconUpdate(
                            repo = repo,
                            app = app,
                            newName = name,
                            newThemeColor = themeColor,
                            requestedUpdate = iconUpdate,
                        )
                        repo.updateMetadata(app.id, name, themeColor, effectiveUpdate)
                            .also { updated -> MiniAppActivity.updatePinnedShortcut(context, updated) }
                    }
                }
                outcome.onSuccess { updated ->
                    editingAppearanceApp = null
                    errorMessage = null
                    statusMessage = "Updated ${updated.name}."
                    refresh++
                }.onFailure { err ->
                    throw err
                }
            },
        )
    }

    // Raw .html file import naming + icon picker dialog
    pendingRawHtmlImport?.let { rawImport ->
        MiniAppMetadataDialog(
            title = "Name your Mini App",
            confirmLabel = "Import",
            initialName = rawImport.suggestedName,
            initialThemeColor = MINI_APP_DEFAULT_THEME_COLOR,
            initialIconUri = null,
            existingIconFile = null,
            existingIconSvg = null,
            onDismiss = { pendingRawHtmlImport = null },
            onSave = { name, themeColor, iconUpdate ->
                val installed = withContext(Dispatchers.IO) {
                    runCatching {
                        repo.installImport(
                            preview = rawImport,
                            overrideName = name,
                            overrideThemeColor = themeColor,
                            iconUpdate = iconUpdate,
                        )
                    }
                }
                installed.onSuccess { app ->
                    pendingRawHtmlImport = null
                    errorMessage = null
                    statusMessage = "Imported ${app.name}."
                    refresh++
                }.onFailure { err ->
                    throw err
                }
            },
        )
    }

    // Clear App Data confirmation
    clearingDataApp?.let { app ->
        AlertDialog(
            onDismissRequest = { clearingDataApp = null },
            title = { Text("Clear data for ${app.name}?") },
            text = { Text("This clears all saved progress and settings for ${app.name} without deleting the app.") },
            confirmButton = {
                TextButton(onClick = {
                    clearMiniAppOriginData(app.id)
                    clearingDataApp = null
                    statusMessage = "Cleared saved data for ${app.name}."
                }) { Text("Clear data") }
            },
            dismissButton = {
                TextButton(onClick = { clearingDataApp = null }) { Text("Cancel") }
            },
        )
    }

    // Delete confirmation
    deletingApp?.let { app ->
        var deleting by remember(app.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!deleting) deletingApp = null },
            title = { Text("Delete ${app.name}?") },
            text = { Text("The mini app bundle and all of its saved data will be removed from this phone.") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching { repo.delete(app.id) }
                            }
                            deletingApp = null
                            outcome.onSuccess {
                                clearMiniAppOriginData(app.id)
                                MiniAppActivity.unpin(context, app.id)
                                errorMessage = null
                                statusMessage = "Deleted ${app.name}."
                                refresh++
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Could not delete ${app.name}."
                            }
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { deletingApp = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ContextMenuRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MiniAppAiBuilderDialog(
    mode: AiMiniAppDialogMode,
    iconFile: File?,
    providerReady: Boolean,
    selectedModelName: String?,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var prompt by remember(mode) { mutableStateOf("") }
    var working by remember(mode) { mutableStateOf(false) }
    var error by remember(mode) { mutableStateOf<String?>(null) }

    val isEdit = mode is AiMiniAppDialogMode.Edit
    val title = if (isEdit) "Edit ${(mode as AiMiniAppDialogMode.Edit).app.name} with AI" else "Create Mini App with AI"

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mode is AiMiniAppDialogMode.Edit) {
                    MiniAppIconBadge(app = mode.app, iconFile = iconFile, size = 32.dp, cornerRadius = 8.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (isEdit) {
                        "Describe what to change (for example, “make the buttons bigger” or “add a reset button”). Your saved data stays intact."
                    } else {
                        "Describe the app you want in plain English. AI will generate the app, title, color, and icon automatically."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!isEdit) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MINI_APP_IDEAS.forEach { idea ->
                            AssistChip(
                                onClick = { if (!working) prompt = "Make me a $idea" },
                                label = { Text(idea, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    enabled = !working,
                    label = { Text(if (isEdit) "What should change?" else "Describe your Mini App") },
                    placeholder = {
                        Text(
                            if (isEdit) "Add dark mode and bigger buttons"
                            else "Make me a Pomodoro timer with a chime",
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )

                val modelCaption = when {
                    !providerReady -> "Connect your provider API key in Settings or Providers first."
                    selectedModelName.isNullOrBlank() -> "Choose a model first."
                    else -> "Using $selectedModelName"
                }
                Text(
                    text = modelCaption,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (providerReady && !selectedModelName.isNullOrBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )

                if (working) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = if (isEdit) "Updating your Mini App…" else "Building your Mini App…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                error?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !working && providerReady && !selectedModelName.isNullOrBlank() && prompt.trim().isNotEmpty(),
                onClick = {
                    working = true
                    error = null
                    scope.launch {
                        try {
                            onSubmit(prompt.trim())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Could not build the mini app."
                            working = false
                        }
                    }
                },
            ) {
                Text(if (isEdit) "Apply changes" else "Build app")
            }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun resolveMetadataIconUpdate(
    repo: MiniAppRepository,
    app: MiniApp,
    newName: String,
    newThemeColor: String,
    requestedUpdate: MiniAppIconUpdate,
): MiniAppIconUpdate {
    if (requestedUpdate !is MiniAppIconUpdate.Keep) return requestedUpdate
    val storedManifest = repo.manifest(app.id) ?: return requestedUpdate
    val storedSvg = storedManifest.iconSvg
    val shouldRegenerate = shouldRegenerateAiIconOnMetadataEdit(
        aiGeneratedIcon = storedManifest.aiGeneratedIcon,
        iconSvg = storedSvg,
        oldName = app.name,
        newName = newName,
        oldThemeColor = app.themeColor,
        newThemeColor = newThemeColor,
    )
    if (!shouldRegenerate) return requestedUpdate
    val png = MiniAppIconRenderer.renderSvgToPng(storedSvg, newThemeColor, newName)
    return MiniAppIconUpdate.PngBytes(png, iconSvg = storedSvg, aiGenerated = true)
}

private enum class IconPickerTab(val label: String) {
    PRESET("Icons"),
    EMOJI("Emoji"),
    PHOTO("Photo"),
}

/** Modal for setting/editing a Mini App's name, theme color, and icon (preset icon, emoji, or gallery photo). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MiniAppMetadataDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialThemeColor: String,
    initialIconUri: String?,
    existingIconFile: File?,
    existingIconCacheKey: Any? = existingIconFile?.absolutePath,
    existingIconSvg: String? = null,
    existingAiGeneratedIcon: Boolean? = null,
    onDismiss: () -> Unit,
    onSave: suspend (name: String, themeColor: String, iconUpdate: MiniAppIconUpdate) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sanitizedInitialColor = remember(initialThemeColor) { MiniAppSanitizer.sanitizeThemeColor(initialThemeColor) }

    var name by remember(initialName) { mutableStateOf(initialName) }
    var themeColor by remember(sanitizedInitialColor) { mutableStateOf(sanitizedInitialColor) }

    val initialTab = remember(initialIconUri, existingIconSvg, existingAiGeneratedIcon) {
        when {
            parseEmojiFromIconUri(initialIconUri) != null -> IconPickerTab.EMOJI
            initialIconUri == ICON_URI_FILE && existingIconSvg == null && existingAiGeneratedIcon == false ->
                IconPickerTab.PHOTO
            else -> IconPickerTab.PRESET
        }
    }
    var userSelectedTab by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) {
        if (!userSelectedTab) tab = initialTab
    }
    var selectedPresetId by remember(initialIconUri) { mutableStateOf(parsePresetFromIconUri(initialIconUri)?.id) }
    var selectedEmoji by remember(initialIconUri) { mutableStateOf(parseEmojiFromIconUri(initialIconUri).orEmpty()) }
    var uploadedPngBytes by remember { mutableStateOf<ByteArray?>(null) }
    var iconModified by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            photoError = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openDocumentInput(uri).use { stream ->
                        MiniAppIconRenderer.decodeAndResizeUploadedImage(stream)
                    }
                }
            }
            result.onSuccess { pngBytes ->
                uploadedPngBytes = pngBytes
                selectedPresetId = null
                selectedEmoji = ""
                iconModified = true
                userSelectedTab = true
                tab = IconPickerTab.PHOTO
            }.onFailure { err ->
                photoError = err.message ?: "Could not load that image."
            }
        }
    }

    val existingFileBitmap = rememberDecodedIconBitmap(existingIconFile, existingIconCacheKey)

    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
        uploadedPngBytes,
        selectedPresetId,
        selectedEmoji,
        themeColor,
        name,
        existingFileBitmap,
        existingIconSvg,
        existingAiGeneratedIcon,
        iconModified,
        sanitizedInitialColor,
        initialName,
    ) {
        if (selectedEmoji.isNotBlank()) {
            value = null
            return@produceState
        }
        value = when {
            uploadedPngBytes != null -> withContext(Dispatchers.Default) {
                val bytes = uploadedPngBytes!!
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }
            selectedPresetId != null -> withContext(Dispatchers.Default) {
                MINI_APP_PRESET_ICONS.firstOrNull { it.id == selectedPresetId }?.let { preset ->
                    runCatching { MiniAppIconRenderer.renderPresetTileBitmap(preset, themeColor, MINI_APP_ICON_SIZE_PX) }.getOrNull()
                }
            }
            !iconModified && existingIconSvg != null -> withContext(Dispatchers.Default) {
                runCatching { MiniAppIconRenderer.renderSvgBitmap(existingIconSvg, themeColor, name) }.getOrNull()
            }
            !iconModified && existingAiGeneratedIcon == true && shouldRegenerateAiIconOnMetadataEdit(
                aiGeneratedIcon = true,
                iconSvg = null,
                oldName = initialName,
                newName = name,
                oldThemeColor = sanitizedInitialColor,
                newThemeColor = themeColor,
            ) -> withContext(Dispatchers.Default) {
                runCatching { MiniAppIconRenderer.renderSvgBitmap(null, themeColor, name) }.getOrNull()
            }
            !iconModified -> existingFileBitmap
            else -> null
        }
    }

    var cachedPresetColor by remember { mutableStateOf<String?>(null) }
    var presetTileBitmaps by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    LaunchedEffect(tab, themeColor, saving) {
        if (tab != IconPickerTab.PRESET || saving || cachedPresetColor == themeColor) return@LaunchedEffect
        val rendered = withContext(Dispatchers.Default) {
            buildMap {
                for (preset in MINI_APP_PRESET_ICONS) {
                    ensureActive()
                    runCatching {
                        MiniAppIconRenderer.renderPresetTileBitmap(preset, themeColor, 96)
                    }.getOrNull()?.let { put(preset.id, it) }
                }
            }
        }
        presetTileBitmaps = rendered
        cachedPresetColor = themeColor
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Live icon preview
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val shape = RoundedCornerShape(18.dp)
                    val currentPreview = previewBitmap
                    if (currentPreview != null && selectedEmoji.isBlank()) {
                        Image(
                            bitmap = currentPreview.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(68.dp).clip(shape),
                        )
                    } else {
                        val bg = Color(MiniAppIconRenderer.parseColorInt(themeColor))
                        val fg = if (MiniAppIconRenderer.isLightColor(themeColor)) Color(0xFF111827) else Color.White
                        Box(
                            modifier = Modifier.size(68.dp).clip(shape).background(bg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = selectedEmoji.ifBlank { fallbackIconInitial(name) },
                                color = fg,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = if (selectedEmoji.isBlank()) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    enabled = !saving,
                    label = { Text("App name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Theme color swatches
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Theme color", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MINI_APP_THEME_COLORS.forEach { hex ->
                            val selected = hex.equals(themeColor, ignoreCase = true)
                            val swatchColor = Color(MiniAppIconRenderer.parseColorInt(hex))
                            val checkTint = if (MiniAppIconRenderer.isLightColor(hex)) Color(0xFF111827) else Color.White
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .semantics { contentDescription = "Color $hex" }
                                    .clickable(enabled = !saving) { themeColor = hex },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected color",
                                        tint = checkTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    IconPickerTab.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = tab == item,
                            onClick = {
                                if (!saving) {
                                    userSelectedTab = true
                                    tab = item
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, IconPickerTab.entries.size),
                        ) {
                            Text(item.label, maxLines = 1)
                        }
                    }
                }

                when (tab) {
                    IconPickerTab.PRESET -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MINI_APP_PRESET_ICONS.forEach { preset ->
                                val isSelected = selectedPresetId == preset.id
                                val tileBitmap = presetTileBitmaps[preset.id]
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    border = if (isSelected) {
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    } else {
                                        null
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = !saving) {
                                            selectedPresetId = preset.id
                                            selectedEmoji = ""
                                            uploadedPngBytes = null
                                            iconModified = true
                                        },
                                ) {
                                    if (tileBitmap != null) {
                                        Image(
                                            bitmap = tileBitmap.asImageBitmap(),
                                            contentDescription = preset.label,
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.background(Color(MiniAppIconRenderer.parseColorInt(themeColor))),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(preset.label.take(1), color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    IconPickerTab.EMOJI -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MINI_APP_EMOJI_PRESETS.forEach { emoji ->
                                    val isSelected = selectedEmoji == emoji
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(enabled = !saving) {
                                                selectedEmoji = emoji
                                                selectedPresetId = null
                                                uploadedPngBytes = null
                                                iconModified = true
                                            },
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(emoji, style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = selectedEmoji,
                                onValueChange = { value ->
                                    selectedEmoji = sanitizeEmojiText(value)
                                    selectedPresetId = null
                                    uploadedPngBytes = null
                                    iconModified = true
                                },
                                enabled = !saving,
                                label = { Text("Or type any emoji") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    IconPickerTab.PHOTO -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            OutlinedButton(
                                enabled = !saving,
                                onClick = {
                                    photoError = null
                                    try {
                                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    } catch (_: ActivityNotFoundException) {
                                        photoError = "No photo picker is available on this device."
                                    }
                                },
                            ) {
                                Text(if (uploadedPngBytes != null) "Choose a different photo" else "Choose photo from gallery")
                            }
                            Text(
                                text = "Images are automatically cropped and resized to a 192×192 PNG icon.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            photoError?.let { err ->
                                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                saveError?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && name.trim().isNotEmpty(),
                onClick = {
                    val trimmedName = name.trim()
                    val selectedColor = themeColor
                    saving = true
                    saveError = null
                    scope.launch {
                        try {
                            val iconUpdate: MiniAppIconUpdate = when {
                                !iconModified -> MiniAppIconUpdate.Keep
                                uploadedPngBytes != null -> MiniAppIconUpdate.PngBytes(uploadedPngBytes!!, aiGenerated = false)
                                selectedEmoji.isNotBlank() -> MiniAppIconUpdate.Emoji(selectedEmoji)
                                selectedPresetId != null -> MiniAppIconUpdate.Preset(selectedPresetId!!)
                                else -> MiniAppIconUpdate.Clear
                            }
                            onSave(trimmedName, selectedColor, iconUpdate)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            saveError = e.message ?: "Could not save changes."
                            saving = false
                        }
                    }
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
