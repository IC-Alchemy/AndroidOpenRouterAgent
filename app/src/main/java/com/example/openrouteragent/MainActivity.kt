package com.example.openrouteragent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat

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
    val colorScheme = if (darkTheme) StudioDarkColorScheme else StudioLightColorScheme

    CompositionLocalProvider(LocalSelectionClipboard provides clipboard) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StudioTypography,
            shapes = StudioShapes,
            content = content,
        )
    }
}
