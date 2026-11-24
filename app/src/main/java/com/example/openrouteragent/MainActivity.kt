package com.example.openrouteragent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ControlLabelPreferences(this).startOnboarding()
        enableEdgeToEdge()
        setContent {
            // The process-wide store keeps a run going after this activity is closed.
            val agent: AgentViewModel = viewModel(
                viewModelStoreOwner = application as AgentApplication,
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )
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
        if (savedInstanceState == null) takeShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeVoiceRequest(intent)
        takeShare(intent)
    }

    /** Text, photos and files shared from another app go to the composer of a new chat. */
    private fun takeShare(intent: Intent?) {
        // Reopening the app from recents replays the share that started it. Skip it then.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val share = sharedContent(intent, packageName + ".files") ?: return
        val agent = ViewModelProvider(application as AgentApplication,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application))[AgentViewModel::class.java]
        agent.receiveShare(share.first, share.second)
        intent.action = null
    }

    private fun takeVoiceRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(AskTileService.EXTRA_START_VOICE, false) == true) {
            intent.removeExtra(AskTileService.EXTRA_START_VOICE)
            VoiceRequests.pending = true
        }
    }
}

/**
 * Reads a share: its text and its content URIs. Only content URIs from other apps are kept, so a share can't
 * point the app at its own private files.
 */
internal fun sharedContent(intent: Intent?, ownAuthority: String): Pair<String?, List<android.net.Uri>>? {
    if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return null
    val streams = buildList {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            addAll(androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM,
                android.net.Uri::class.java).orEmpty())
        } else {
            androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM,
                android.net.Uri::class.java)?.let { add(it) }
        }
    }.filter { it.scheme == "content" && it.authority != ownAuthority }.distinct().take(MAX_ATTACHMENTS)
    val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?.toString()).map(String::trim).filter(String::isNotEmpty).distinct().joinToString("\n\n").ifBlank { null }
    if (text == null && streams.isEmpty()) return null
    return text to streams
}

internal fun isDarkThemeForAppearance(appearance: String, systemDark: Boolean): Boolean =
    appearance == "oled" || (appearance == "system" && systemDark)

@Composable
fun OpenRouterAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val clipboard = remember(context, scope) { SelectionClipboard(scope) { copyText(context, it) } }
    val colorScheme = if (darkTheme) StudioDarkColorScheme else StudioLightColorScheme

    CompositionLocalProvider(
        LocalSelectionClipboard provides clipboard,
        LocalControlLabels provides rememberControlLabels(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StudioTypography,
            shapes = StudioShapes,
            content = content,
        )
    }
}
