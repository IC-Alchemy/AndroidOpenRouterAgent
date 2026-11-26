package com.example.openrouteragent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate
import java.time.ZoneOffset

private const val MANAGEMENT_KEYS_URL = "https://openrouter.ai/settings/management-keys"

@Composable
internal fun UsageScreen(
    client: OpenRouterAgentClient,
    apiKey: String,
    savedManagementKey: String,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    activityManagementKey: String = "",
    onSaveActivityKey: suspend (String) -> Unit,
) {
    val managementKey = activityManagementKey.ifBlank { savedManagementKey }
    var detail by rememberSaveable { mutableStateOf(managementKey.isNotBlank()) }
    var refresh by remember { mutableIntStateOf(0) }
    var showKeyForm by remember { mutableStateOf(false) }
    var showKeyMenu by remember { mutableStateOf(false) }
    var savingKey by remember { mutableStateOf(false) }
    var keySaveError by remember { mutableStateOf(false) }
    val credential = if (detail) managementKey else apiKey
    val tabs = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    fun saveKey(key: String) {
        savingKey = true
        keySaveError = false
        scope.launch {
            try {
                onSaveActivityKey(key)
                showKeyForm = false
                refresh++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                keySaveError = true
            } finally {
                savingKey = false
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat")
                    }
                    Text(
                        "OpenRouter usage",
                        Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { refresh++ }, enabled = credential.isNotBlank()) {
                        Icon(Icons.Default.Refresh, "Refresh usage")
                    }
                    if (detail && managementKey.isNotBlank()) {
                        Box {
                            IconButton(
                                onClick = { showKeyMenu = true },
                                enabled = !savingKey && !showKeyForm,
                            ) {
                                Icon(Icons.Default.MoreVert, "Activity key options")
                            }
                            DropdownMenu(expanded = showKeyMenu, onDismissRequest = { showKeyMenu = false }) {
                                DropdownMenuItem(text = { Text("Change key") }, onClick = {
                                    showKeyMenu = false
                                    keySaveError = false
                                    showKeyForm = true
                                })
                                if (activityManagementKey.isNotBlank()) {
                                    DropdownMenuItem(text = { Text("Forget this key") }, onClick = {
                                        showKeyMenu = false
                                        saveKey("")
                                    })
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = !detail, onClick = { detail = false }, label = { Text("Key spend") })
                    FilterChip(selected = detail, onClick = { detail = true }, label = { Text("Activity") })
                }
                HorizontalDivider()
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            tabs.SaveableStateProvider(detail) {
                Column(
                    Modifier.widthIn(max = 840.dp).fillMaxSize()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!detail) {
                        if (apiKey.isBlank()) {
                            OutlinedSection(title = "Connect an OpenRouter key") {
                                Text("See spending and limits for the key this app uses.")
                                Button(onClick = onConnect) { Text("Open settings") }
                            }
                        } else {
                            UsageRequest(apiKey, refresh, onRetry = { refresh++ }, load = { client.keyUsage(apiKey) }) {
                                KeySpendCard(it)
                            }
                        }
                    } else {
                        if (keySaveError) {
                            Text(
                                "Couldn’t update the saved activity key. Try again.",
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (managementKey.isBlank() || showKeyForm) {
                            UsageKeyForm(
                                canCancel = managementKey.isNotBlank(),
                                saving = savingKey,
                                onCancel = {
                                    keySaveError = false
                                    showKeyForm = false
                                },
                                onLoad = ::saveKey,
                            )
                        } else {
                            UsageRequest(
                                managementKey, refresh, onRetry = { refresh++ },
                                load = {
                                    val today = LocalDate.now(ZoneOffset.UTC)
                                    today to client.usageActivity(managementKey)
                                },
                            ) { (today, report) ->
                                UsageExplorer(report.rows, today, report.unreadableRows)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageKeyForm(canCancel: Boolean, saving: Boolean, onCancel: () -> Unit, onLoad: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    OutlinedSection(title = "Unlock activity", highlighted = true) {
        Text("Use a management key with Activity → Read Only access. This app saves it on this device.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenRouter management key") },
            singleLine = true,
            enabled = !saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = {
                keyboard?.hide()
                onLoad(draft.trim())
            },
            enabled = !saving && draft.isNotBlank(),
        ) { Text(if (saving) "Saving…" else "Save and load activity") }
        TextButton(onClick = { runCatching { uriHandler.openUri(MANAGEMENT_KEYS_URL) } }) { Text("Manage keys on OpenRouter") }
        if (canCancel) TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
    }
}

@Composable
private fun <T : Any> UsageRequest(
    credential: String,
    refresh: Int,
    onRetry: () -> Unit,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
) {
    var value by remember(credential) { mutableStateOf<T?>(null) }
    var error by remember(credential) { mutableStateOf<Exception?>(null) }
    var loading by remember(credential) { mutableStateOf(true) }
    LaunchedEffect(credential, refresh) {
        loading = true
        error = null
        try {
            value = load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        loading = false
    }
    if (loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        Text(if (value == null) "Loading usage…" else "Refreshing usage…", style = MaterialTheme.typography.bodySmall)
    }
    error?.let {
        OutlinedSection(title = if (value == null) "Usage unavailable" else "Showing the last loaded usage") {
            val apiError = it as? ApiStatusException
            val payloadError = it as? UsagePayloadException
            val summary = when {
                payloadError != null -> "OpenRouter returned activity the app couldn’t read."
                apiError?.code == 401 -> "OpenRouter couldn’t authenticate this request."
                apiError?.code == 403 -> "OpenRouter denied access. For Activity, a Custom management key needs Activity → Read Only."
                apiError?.code == 429 -> "OpenRouter is rate limiting requests. Try again in a moment."
                apiError != null -> "OpenRouter couldn’t complete the usage request."
                it is InterruptedIOException -> "OpenRouter took too long to return usage. Try again."
                it is IOException -> "Couldn’t load usage over this connection. Check your connection and retry."
                it is IllegalArgumentException -> "The key couldn’t be sent. Copy it again from OpenRouter."
                else -> "Usage couldn’t be loaded. Try again."
            }
            Text(summary, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            val detail = apiError?.message ?: payloadError?.message
            if (detail != null) {
                val id = apiError?.requestId ?: payloadError?.requestId
                val diagnostic = listOfNotNull(detail, id?.let { "Request ID $it" }).joinToString("\n")
                CopyableText(diagnostic, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRetry, enabled = !loading) { Text("Retry") }
        }
    }
    value?.let { content(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeySpendCard(snapshot: KeyUsage) {
    OutlinedSection(title = snapshot.label.ifBlank { "Current OpenRouter key" }, highlighted = true) {
        Text("Total spend", style = MaterialTheme.typography.labelLarge)
        Text(snapshot.lifetime?.let(::usageMoney) ?: "Unavailable", style = MaterialTheme.typography.headlineMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageValue("Today", snapshot.daily?.let(::usageMoney) ?: "Unavailable")
            UsageValue("This week", snapshot.weekly?.let(::usageMoney) ?: "Unavailable")
            UsageValue("This month", snapshot.monthly?.let(::usageMoney) ?: "Unavailable")
        }
        Text("OpenRouter totals for this API key in USD. Daily, weekly and monthly totals reset on OpenRouter’s UTC schedule.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedSection(title = "Spending limit") {
        val limit = snapshot.limit
        val remaining = snapshot.remaining
        if (limit == null) {
            Text("No spending limit set.")
        } else {
            Text(
                if (remaining == null) usageMoney(limit)
                else "${usageMoney(remaining)} remaining of ${usageMoney(limit)}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (limit > 0 && remaining != null) {
                val fraction = (1.0 - remaining / limit).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            snapshot.reset?.let { Text("Resets $it", style = MaterialTheme.typography.bodySmall) }
        }
        snapshot.byok?.takeIf { it > 0 }?.let {
            Text("External provider spend (BYOK) · ${usageMoney(it)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun UsageValue(label: String, value: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(PaddingValues(horizontal = 11.dp, vertical = 8.dp))) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
