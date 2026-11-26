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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZoneOffset

private const val MANAGEMENT_KEYS_URL = "https://openrouter.ai/settings/management-keys"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageScreen(
    client: OpenRouterAgentClient,
    apiKey: String,
    savedManagementKey: String,
    onBack: () -> Unit,
    onConnect: () -> Unit,
) {
    var detail by rememberSaveable { mutableStateOf(savedManagementKey.isNotBlank()) }
    var refresh by remember { mutableIntStateOf(0) }
    var temporaryKey by remember { mutableStateOf("") }
    var showKeyForm by remember { mutableStateOf(false) }
    val managementKey = temporaryKey.ifBlank { savedManagementKey }
    val credential = if (detail) managementKey else apiKey
    val tabs = rememberSaveableStateHolder()
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("OpenRouter usage", Modifier.semantics { heading() }) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat")
                        }
                    },
                    actions = {
                        IconButton(onClick = { refresh++ }, enabled = credential.isNotBlank()) {
                            Icon(Icons.Default.Refresh, "Refresh usage")
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    FilterChip(selected = !detail, onClick = { detail = false }, label = { Text("Key spend") },
                        modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = detail, onClick = { detail = true }, label = { Text("Activity") },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
                HorizontalDivider()
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            tabs.SaveableStateProvider(detail) {
                Column(
                    Modifier.widthIn(max = 840.dp).fillMaxSize()
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        if (managementKey.isBlank() || showKeyForm) {
                            UsageKeyForm(
                                canCancel = managementKey.isNotBlank(),
                                onCancel = { showKeyForm = false },
                                onLoad = { temporaryKey = it; showKeyForm = false; refresh++ },
                            )
                        }
                        if (managementKey.isNotBlank()) {
                            OutlinedSection(title = "Activity access") {
                                Text(
                                    if (temporaryKey.isNotBlank()) "Management key for this visit"
                                    else "Management key from Workspaces",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text("OpenRouter shows activity visible to this key for the last 30 completed UTC days.",
                                    style = MaterialTheme.typography.bodySmall)
                                if (!showKeyForm) {
                                    TextButton(onClick = {
                                        if (temporaryKey.isNotBlank()) temporaryKey = "" else showKeyForm = true
                                    }) {
                                        Text(if (temporaryKey.isNotBlank()) "Forget this key" else "Use another key")
                                    }
                                }
                            }
                            UsageRequest(managementKey, refresh, onRetry = { refresh++ }, load = {
                                val today = LocalDate.now(ZoneOffset.UTC)
                                today to client.usageActivity(managementKey)
                            }) { (today, rows) ->
                                UsageExplorer(rows, today)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageKeyForm(canCancel: Boolean, onCancel: () -> Unit, onLoad: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    OutlinedSection(title = "Unlock activity", highlighted = true) {
        Text("Use a read-only management key with activity access to see trends and endpoint details. A key entered here is kept for this visit.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenRouter management key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = {
                keyboard?.hide()
                onLoad(draft.trim())
                draft = ""
            },
            enabled = draft.isNotBlank(),
        ) { Text("Load activity") }
        TextButton(onClick = { runCatching { uriHandler.openUri(MANAGEMENT_KEYS_URL) } }) { Text("Manage keys on OpenRouter") }
        if (canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
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
    var error by remember(credential) { mutableStateOf<String?>(null) }
    var loading by remember(credential) { mutableStateOf(true) }
    LaunchedEffect(credential, refresh) {
        loading = true
        error = null
        try {
            value = load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = when ((e as? ApiStatusException)?.code) {
                401 -> "OpenRouter rejected this key. Check that it’s still active."
                403 -> "This key can’t access these usage records. Activity requires a management key with activity access."
                429 -> "OpenRouter is rate limiting requests. Try again in a moment."
                else -> "Usage couldn’t be loaded. Check your connection and try again."
            }
        }
        loading = false
    }
    if (loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        Text(if (value == null) "Loading usage…" else "Refreshing usage…", style = MaterialTheme.typography.bodySmall)
    }
    error?.let {
        OutlinedSection(title = if (value == null) "Usage unavailable" else "Showing the last loaded usage") {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
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
