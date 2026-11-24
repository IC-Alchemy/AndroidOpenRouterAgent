package com.example.openrouteragent

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit

internal class ControlLabelPreferences(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("ui_state", Context.MODE_PRIVATE)

    fun startOnboarding(now: Long = System.currentTimeMillis()) {
        if (!prefs.contains(FIRST_LAUNCH)) prefs.edit().putLong(FIRST_LAUNCH, now).apply()
    }

    fun showLabels(now: Long = System.currentTimeMillis()): Boolean {
        val firstLaunch = prefs.getLong(FIRST_LAUNCH, now)
        return prefs.getBoolean(SHOW_LABELS, now - firstLaunch < TimeUnit.DAYS.toMillis(1))
    }

    fun setShowLabels(visible: Boolean) = prefs.edit().putBoolean(SHOW_LABELS, visible).apply()

    private companion object {
        const val SHOW_LABELS = "show_control_labels"
        const val FIRST_LAUNCH = "control_label_onboarding_started"
    }
}

internal data class ControlLabels(val visible: Boolean, val setVisible: (Boolean) -> Unit)

internal val LocalControlLabels = staticCompositionLocalOf { ControlLabels(true) {} }

@Composable
internal fun rememberControlLabels(): ControlLabels {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { ControlLabelPreferences(context) }
    var visible by remember(settings) { mutableStateOf(settings.showLabels()) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> visible = settings.showLabels() }
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return ControlLabels(visible) {
        visible = it
        settings.setShowLabels(it)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControlTooltip(label: String, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (enabled) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            content = content,
        )
    } else {
        content()
    }
}

@Composable
internal fun SymbolLabel(label: String, icon: ImageVector, badge: String? = null) {
    val showLabels = LocalControlLabels.current.visible
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label.takeUnless { showLabels }, modifier = Modifier.size(18.dp))
        if (showLabels) Text(label)
        else if (badge != null) Text(badge, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun SymbolAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
) {
    val showLabels = LocalControlLabels.current.visible
    ControlTooltip(label, enabled = !showLabels) {
        TextButton(onClick = onClick, enabled = enabled, shape = shape, colors = colors,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            SymbolLabel(label, icon)
        }
    }
}

@Composable
internal fun ControlLabelsToggle() {
    val labels = LocalControlLabels.current
    val action = if (labels.visible) "Hide control labels" else "Show control labels"
    ControlTooltip(action) {
        IconButton(onClick = { labels.setVisible(!labels.visible) }, modifier = Modifier.size(48.dp)
            .semantics { stateDescription = if (labels.visible) "Labels" else "Symbols" }) {
            Icon(StudioSymbols.Labels, action,
                tint = if (labels.visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal object StudioSymbols {
    val Agent = symbol("Agent", "M12 3 L15 8 L12 13 L9 8 Z M9 9 L5 14 M15 9 L19 14 " +
        "M3 14 H7 V18 H3 Z M17 14 H21 V18 H17 Z M12 13 V18 M10 18 H14 V22 H10 Z")
    val Models = symbol("Models", "M12 3 L21 8 L12 13 L3 8 Z M3 12 L12 17 L21 12 M3 16 L12 21 L21 16")
    val Chat = symbol("Chat", "M3 5 H21 V16 H10 L5 20 V16 H3 Z M7 9 H17 M7 12 H13")
    val Chats = symbol("Chats", "M3 4 H17 V14 H9 L5 18 V14 H3 Z M20 8 H22 V18 H18 V21 L14 18 H11")
    val NewChat = symbol("New chat", "M3 5 H21 V16 H10 L5 20 V16 H3 Z M9 10.5 H15 M12 7.5 V13.5")
    val Labels = symbol("Labels", "M3 17 L7 7 L11 17 M4.5 14 H9.5 M14 11 H18 Q21 11 21 14 V17 H16 " +
        "Q14 17 14 15 Q14 14 16 14 H21")
    val Folder = symbol("Folder", "M3 6 H9 L11 8 H21 V19 H3 Z")
    val Workspace = symbol("Workspace", "M7 18 H18 A4 4 0 0 0 19 10 A6 6 0 0 0 7 9 A4.5 4.5 0 0 0 7 18 Z " +
        "M9 13 H15 M9 16 H13")
    val Sandbox = symbol("Sandbox", "M3 6 H21 V19 H3 Z M7 10 L10 13 L7 16 M13 16 H17")
    val Network = symbol("Network", "M5 6 H9 V10 H5 Z M15 6 H19 V10 H15 Z M10 16 H14 V20 H10 Z M9 8 H15 M7 10 L11 16 M17 10 L13 16")
    val Activity = symbol("Activity", "M3 12 H7 L9 6 L13 18 L16 9 L18 12 H21")
    val Stop = symbol("Stop", "M6 6 H18 V18 H6 Z")
    val Copy = symbol("Copy", "M8 8 H21 V21 H8 Z M16 5 V3 H3 V16 H5")
    val File = symbol("File", "M6 3 H14 L19 8 V21 H6 Z M14 3 V8 H19 M9 13 H16 M9 16 H16")
    val NewFolder = symbol("New folder", "M3 6 H9 L11 8 H21 V19 H3 Z M9 13.5 H15 M12 10.5 V16.5")
    val NewFile = symbol("New file", "M6 3 H14 L19 8 V21 H6 Z M14 3 V8 H19 M10 15 H16 M13 12 V18")
    val Import = symbol("Import", "M3 14 V20 H21 V14 M12 4 V15 M8 11 L12 15 L16 11")
    val Export = symbol("Export", "M3 14 V20 H21 V14 M12 15 V4 M8 8 L12 4 L16 8")
    val Detach = symbol("Detach", "M4 12 H10 M14 12 H20 M9 8 L13 4 M11 20 L15 16")
    val MiniApps = symbol("Mini apps", "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14 14 H20 V20 H14 Z")
    val NewMiniApp = symbol("Create mini app", "M4 4 H10 V10 H4 Z M4 14 H10 V20 H4 Z M14 14 H20 V20 H14 Z M14 7 H20 M17 4 V10")

    private fun symbol(name: String, data: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = PathParser().parsePathString(data).toNodes(), stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.65f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        .build()
}
