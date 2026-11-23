package com.example.openrouteragent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("OpenRouter Agent", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

internal fun selectedTextForCopy(before: TextFieldValue, after: TextFieldValue): String? {
    if (before.text != after.text || before.selection == after.selection || after.selection.collapsed) return null
    return after.text.substring(after.selection.min, after.selection.max)
}

internal class SelectionClipboard(private val scope: CoroutineScope, private val write: (String) -> Unit) {
    private var pending: Job? = null

    fun select(text: String) {
        pending?.cancel()
        pending = scope.launch {
            // Selection handles can produce several range updates during a drag.
            delay(300)
            pending = null
            write(text)
        }
    }

    fun copy(text: String) {
        pending?.cancel()
        pending = null
        write(text)
    }
}

internal val LocalSelectionClipboard = staticCompositionLocalOf<SelectionClipboard> {
    error("SelectionClipboard must be provided by the app.")
}

internal val LocalTranscriptSelection = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
private fun SelectionCopyField(
    text: AnnotatedString,
    enabled: Boolean = true,
    onTextChange: ((String) -> Unit)? = null,
    keepSelectionOnReplacement: Boolean = false,
    content: @Composable (TextFieldValue, (TextFieldValue) -> Unit) -> Unit,
) {
    val clipboard = LocalSelectionClipboard.current
    val onSelection = LocalTranscriptSelection.current
    var fieldState by remember { mutableStateOf(TextFieldValue(text)) }
    val keepDisplayedText = keepSelectionOnReplacement && !fieldState.selection.collapsed &&
        !text.text.startsWith(fieldState.text)
    // Rebuilds from [text] when its styles change too, since a text field may drop styles from values it reports.
    val value = if (fieldState.annotatedString == text || keepDisplayedText) fieldState else
        TextFieldValue(text, fieldState.selection, fieldState.composition.takeIf { fieldState.text == text.text })
    SideEffect { fieldState = value }

    content(value) { next ->
        fieldState = next
        val selected = if (enabled) selectedTextForCopy(value, next) else null
        if (selected != null) {
            clipboard.select(selected)
            onSelection?.invoke()
        }
        if (next.text != value.text) onTextChange?.invoke(next.text)
    }
}

@Composable
internal fun CopyableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    keepSelectionOnReplacement: Boolean = false,
) = CopyableText(AnnotatedString(text), modifier, style, color, keepSelectionOnReplacement)

/**
 * Selectable read-only text that copies what the user selects. Spans in [text] are drawn, and a tap on a range
 * annotated with [MARKDOWN_URL_TAG] calls [onLinkClick] with its URL.
 */
@Composable
internal fun CopyableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    keepSelectionOnReplacement: Boolean = false,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val contentColor = LocalContentColor.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val linkText by rememberUpdatedState(text)
    val openLink by rememberUpdatedState(onLinkClick)
    val hasLinks = onLinkClick != null && text.hasStringAnnotations(MARKDOWN_URL_TAG, 0, text.length)
    val linkTaps = if (hasLinks) Modifier.linkTaps({ layout?.let { it to linkText } }) { openLink?.invoke(it) } else Modifier
    SelectionCopyField(text, keepSelectionOnReplacement = keepSelectionOnReplacement) { value, onChange ->
        // Expose selectable static text to accessibility services while keeping the native handle and toolbar UI.
        Box(modifier.then(linkTaps).clearAndSetSemantics {
            this.text = value.annotatedString
            textSelectionRange = value.selection
            getTextLayoutResult { results -> layout?.let { results.add(it) } ?: false }
            setSelection { start, end, _ ->
                if (start !in 0..value.text.length || end !in 0..value.text.length) false else {
                    onChange(value.copy(selection = TextRange(start, end)))
                    true
                }
            }
        }) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                readOnly = true,
                onTextLayout = { layout = it },
                textStyle = style.copy(color = color.takeOrElse { style.color.takeOrElse { contentColor } }),
            )
        }
    }
}

@Composable
internal fun CopyableOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: CornerBasedShape = MaterialTheme.shapes.small,
) {
    SelectionCopyField(AnnotatedString(value), enabled = enabled, onTextChange = onValueChange) { fieldValue, onChange ->
        OutlinedTextField(
            value = fieldValue,
            onValueChange = onChange,
            modifier = modifier,
            enabled = enabled,
            label = label,
            placeholder = placeholder,
            supportingText = supportingText,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            shape = shape,
        )
    }
}

/** The URL annotated in [text] at [position] of its [layout], when the position is on the linked characters. */
internal fun linkAt(layout: TextLayoutResult, text: AnnotatedString, position: Offset): String? {
    val offset = layout.getOffsetForPosition(position)
    // The nearest offset may sit just after the character under the finger, so both sides are checked.
    return listOf(offset, offset - 1).filter { it in text.indices }.firstNotNullOfOrNull { index ->
        if (!layout.getBoundingBox(index).inflate(4f).contains(position)) null
        else text.getStringAnnotations(MARKDOWN_URL_TAG, index, index + 1).firstOrNull()?.item
    }
}

/**
 * Opens links on a quick tap without consuming other gestures, so long-press selection keeps working.
 * Runs before the text field sees the pointer and consumes only the release of a tap on a link.
 */
internal fun Modifier.linkTaps(
    layout: () -> Pair<TextLayoutResult, AnnotatedString>?,
    onLinkClick: (String) -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val url = layout()?.let { (result, text) -> linkAt(result, text, down.position) } ?: return@awaitEachGesture
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(PointerEventPass.Initial)
            } ?: return@awaitEachGesture
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            up.consume()
            onLinkClick(url)
        }
    }
