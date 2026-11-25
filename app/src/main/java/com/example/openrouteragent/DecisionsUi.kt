package com.example.openrouteragent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Option colors, in order A to D. Bright enough to read on the dark and light Studio themes. */
private val optionColors = listOf(Color(0xFF38BDF8), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFFF472B6))
private val yesColor = Color(0xFF10B981)
private val noColor = Color(0xFFF43F5E)

private val SmallWindow = 108.dp
private val BigWindow = 176.dp

/** One round window on the left: an option, or the thing a yes-or-no or score decision judges. */
private data class DecisionSlot(val label: String, val part: DecisionPart? = null, val preview: ImageBitmap? = null)

private const val SUBJECT = -1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DecisionsSheet(agent: AgentViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember { DecisionMediaReader(context.applicationContext) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val apiKey = agent.config.apiKey

    var kind by remember { mutableStateOf(DecisionKind.Choice) }
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf(DecisionSlot("A"), DecisionSlot("B")) }
    var subject by remember { mutableStateOf(DecisionSlot("")) }
    var levels by remember { mutableStateOf("Not good, Okay, Great") }

    var models by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var modelId by remember { mutableStateOf(agent.config.decisionModel.ifBlank { DEFAULT_DECISION_MODEL }) }
    var showModels by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<DecisionResult?>(null) }
    var sentAsk by remember { mutableStateOf<DecisionAsk?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }

    // Where the windows are on screen, for the flight of the chosen one to the right.
    val centers = remember { mutableStateMapOf<Int, Offset>() }
    var stageOrigin by remember { mutableStateOf(Offset.Zero) }
    var bigCenter by remember { mutableStateOf(Offset.Zero) }
    var flying by remember { mutableStateOf<Int?>(null) }
    var landed by remember { mutableStateOf<Int?>(null) }
    val flight = remember { Animatable(0f) }

    LaunchedEffect(apiKey) {
        if (apiKey.isBlank()) return@LaunchedEffect
        try {
            models = agent.decisions.models(apiKey).sortedBy { it.name.lowercase() }
            modelsError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modelsError = e.message ?: "Couldn't load decision models."
        }
    }

    fun reset() {
        busy?.cancel()
        busy = null
        result = null
        sentAsk = null
        error = null
        flying = null
        landed = null
    }

    // The slot being filled by a picker or the text box.
    var target by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf<Int?>(null) }
    fun slotAt(index: Int) = if (index == SUBJECT) subject else options.getOrNull(index)
    fun setSlot(index: Int, slot: DecisionSlot) {
        reset()
        if (index == SUBJECT) subject = slot else if (index in options.indices) options[index] = slot
    }
    fun accept(uri: Uri?, mediaKind: String) {
        val index = target ?: return
        target = null
        if (uri == null) return
        reading = true
        scope.launch {
            try {
                val picked = withContext(Dispatchers.IO) { reader.read(uri, mediaKind) }
                val label = slotAt(index)?.label.orEmpty()
                setSlot(index, DecisionSlot(label, picked.part, picked.preview?.asImageBitmap()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Android couldn't read that file."
            } finally {
                reading = false
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { accept(it, DECISION_IMAGE) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { accept(it, DECISION_VIDEO) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { accept(it, DECISION_AUDIO) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        accept(if (taken) cameraUri else null, DECISION_IMAGE)
        cameraUri = null
    }
    fun pick(index: Int, source: String) {
        target = index
        runCatching {
            when (source) {
                "photo" -> pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "camera" -> decisionCameraUri(context).also { cameraUri = it }.let(takePhoto::launch)
                "video" -> pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                "audio" -> pickAudio.launch(arrayOf("audio/*"))
            }
        }.onFailure {
            target = null
            error = if (source == "camera") "This phone has no camera app that can take a photo for the app."
                else "Android could not open the picker."
        }
    }

    val selectedModel = models.firstOrNull { it.id == modelId }
    val labels = remember(kind, levels) { levels.split(',').map(String::trim).filter(String::isNotEmpty) }
    val ask = DecisionAsk(
        model = modelId,
        question = question,
        kind = kind,
        options = if (kind == DecisionKind.Choice) options.map { DecisionOption(it.label, listOfNotNull(it.part)) } else emptyList(),
        subject = if (kind == DecisionKind.Choice) emptyList() else listOfNotNull(subject.part),
        levels = if (kind == DecisionKind.Score) labels else emptyList(),
    )
    val unsupported = unsupportedDecisionMedia(selectedModel, ask.parts)
    val problem = when {
        apiKey.isBlank() -> "Add your OpenRouter API key in Settings."
        unsupported.isNotEmpty() -> unsupportedMediaMessage(selectedModel?.name ?: modelId, unsupported, models)
        else -> ask.problem()
    }

    fun decide() {
        if (problem != null || busy != null) return
        reset()
        val sending = ask
        sentAsk = sending
        busy = scope.launch {
            try {
                val answer = agent.decisions.decide(apiKey, sending)
                result = answer
                agent.setDecisionModel(modelId)
                val chosen = answer.answer
                // The chosen option, or the judged item, flies to the big window on the right.
                val from = when {
                    chosen == null || chosen.refused -> null
                    sending.kind == DecisionKind.Choice -> options.indexOfFirst { it.label == chosen.choice }.takeIf { it >= 0 }
                    else -> SUBJECT
                }
                if (from != null && centers[from] != null) {
                    flight.snapTo(0f)
                    flying = from
                    flight.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow))
                }
                landed = from
                flying = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "The decision failed."
            } finally {
                busy = null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { busy?.cancel(); onDismiss() }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.95f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Decisions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Put the options on the left and let a decision model pick.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { busy?.cancel(); onDismiss() }) { Text("Close") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecisionKind.entries.forEach { entry ->
                    FilterChip(kind == entry, { if (kind != entry) { reset(); kind = entry } }, label = { Text(entry.label) })
                }
            }

            Box {
                OutlinedButton(onClick = { showModels = true }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(selectedModel?.name ?: modelId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val caps = selectedModel?.let(::decisionCapabilities)
                        if (!caps.isNullOrBlank()) Text(caps, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Model", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded = showModels, onDismissRequest = { showModels = false }) {
                    if (models.isEmpty()) {
                        DropdownMenuItem(text = { Text(modelsError ?: if (apiKey.isBlank()) "Add your API key first."
                            else "Loading decision models…") }, onClick = { showModels = false })
                    }
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(decisionCapabilities(model), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { reset(); modelId = model.id; showModels = false },
                        )
                    }
                }
            }

            OutlinedTextField(question, { question = it; if (result != null) reset() }, Modifier.fillMaxWidth(),
                label = { Text(when (kind) {
                    DecisionKind.Choice -> "Question, e.g. Which photo should I post?"
                    DecisionKind.YesNo -> "Question, e.g. Is this ripe?"
                    DecisionKind.Score -> "Question, e.g. How tidy is this room?"
                }) }, minLines = 1, maxLines = 4)
            if (kind == DecisionKind.Score) {
                OutlinedTextField(levels, { levels = it; if (result != null) reset() }, Modifier.fillMaxWidth(),
                    label = { Text("Score levels, lowest first, separated by commas") }, singleLine = true)
            }

            // The stage: round windows on the left, the decision on the right.
            val thinking = busy != null
            val pulse = rememberInfiniteTransition(label = "thinking")
            val bob by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                label = "bob")
            val spin by pulse.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
            val answer = result?.answer
            Box(Modifier.fillMaxWidth().onGloballyPositioned { stageOrigin = it.positionInRoot() }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val slots = if (kind == DecisionKind.Choice) options.mapIndexed { i, slot -> i to slot }
                            else listOf(SUBJECT to subject)
                        slots.forEachIndexed { position, (index, slot) ->
                            val color = if (index == SUBJECT) MaterialTheme.colorScheme.primary
                                else optionColors[index % optionColors.size]
                            val probability = if (kind == DecisionKind.Choice && landed != null)
                                answer?.probabilities?.get(slot.label) else null
                            val chosen = landed == index || flying == index
                            val faded = landed != null && kind == DecisionKind.Choice && !chosen
                            val hop = if (thinking) (sin((bob + position * 0.33f) * PI).toFloat() * 6f) else 0f
                            SlotWindow(
                                slot = slot,
                                color = color,
                                size = SmallWindow,
                                probability = probability,
                                ghost = chosen,
                                enabled = !thinking && !reading,
                                modifier = Modifier.offset(y = hop.dp).alpha(if (faded) .45f else 1f)
                                    .onGloballyPositioned { centers[index] = it.boundsInRoot().center },
                                onPick = { source -> if (source == "text") editingText = index else pick(index, source) },
                                onClear = { setSlot(index, DecisionSlot(slot.label)) },
                                onRemove = if (kind == DecisionKind.Choice && options.size > 2) ({
                                    reset()
                                    options.removeAt(index)
                                    centers.clear()
                                    options.forEachIndexed { i, s -> options[i] = s.copy(label = ('A' + i).toString()) }
                                }) else null,
                            )
                        }
                        if (kind == DecisionKind.Choice && options.size < MAX_DECISION_OPTIONS) {
                            TextButton(onClick = { reset(); options.add(DecisionSlot(('A' + options.size).toString())) },
                                enabled = !thinking) { Text("+ Add option") }
                        }
                    }
                    Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
                        BigWindow(
                            thinking = thinking,
                            spin = spin,
                            landedSlot = landed?.let(::slotAt),
                            landedColor = landed?.let { if (it == SUBJECT) MaterialTheme.colorScheme.primary
                                else optionColors[it % optionColors.size] },
                            kind = sentAsk?.kind ?: kind,
                            answer = answer.takeIf { landed != null || it?.refused == true },
                            modifier = Modifier.onGloballyPositioned { bigCenter = it.boundsInRoot().center },
                        )
                    }
                }
                // The flight: a copy of the chosen window hops from its place on the left into the big window.
                flying?.let(::slotAt)?.let { slot ->
                    val index = flying!!
                    val from = centers[index] ?: return@let
                    val p = flight.value
                    val density = LocalDensity.current
                    val size = lerp(SmallWindow, BigWindow, p.coerceIn(0f, 1f))
                    val half = with(density) { size.toPx() / 2 }
                    val hopPx = with(density) { 48.dp.toPx() } * sin(PI * p.coerceIn(0f, 1f)).toFloat()
                    val x = from.x + (bigCenter.x - from.x) * p - stageOrigin.x - half
                    val y = from.y + (bigCenter.y - from.y) * p - stageOrigin.y - half - hopPx
                    Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .graphicsLayer { rotationZ = 360f * p.coerceIn(0f, 1f) }) {
                        SlotWindow(slot, if (index == SUBJECT) MaterialTheme.colorScheme.primary
                            else optionColors[index % optionColors.size], size, null, false, false,
                            Modifier, {}, {}, null)
                    }
                }
            }

            if (reading) Text("Reading the file…", style = MaterialTheme.typography.bodySmall)
            (error ?: problem?.takeIf { question.isNotBlank() || ask.parts.any { !it.isEmpty } || unsupported.isNotEmpty() })
                ?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = { if (thinking) { busy?.cancel(); busy = null } else decide() },
                enabled = thinking || (problem == null && !reading),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                if (thinking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Deciding… tap to stop")
                } else Text(if (result != null) "Decide again" else "Decide", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text("Each decision is billed by OpenRouter at the model's price.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            AnimatedVisibility(result != null && busy == null && (landed != null || answer?.refused == true || answer == null),
                enter = fadeIn()) {
                result?.let { DecisionDetails(it, sentAsk ?: ask) }
            }
        }
    }

    editingText?.let { index ->
        var text by remember(index) { mutableStateOf(slotAt(index)?.part?.takeIf { it.kind == DECISION_TEXT }?.text.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingText = null },
            title = { Text(if (index == SUBJECT) "What to judge" else "Option ${slotAt(index)?.label}") },
            text = {
                OutlinedTextField(text, { text = it.take(4_000) }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("Text") }, minLines = 3, maxLines = 8)
            },
            confirmButton = {
                TextButton(onClick = {
                    val label = slotAt(index)?.label.orEmpty()
                    setSlot(index, DecisionSlot(label, if (text.isBlank()) null else DecisionPart.text(text)))
                    editingText = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { editingText = null }) { Text("Cancel") } },
        )
    }
}

/** What a decision model reads, for the model menu. */
private fun decisionCapabilities(model: CatalogModel): String = buildList {
    add("Text")
    if ("image" in model.inputModalities) add("Photos")
    if ("video" in model.inputModalities) add("Video")
    if ("audio" in model.inputModalities) add("Audio")
}.joinToString(" · ")

@Composable
private fun SlotWindow(
    slot: DecisionSlot,
    color: Color,
    size: Dp,
    probability: Double?,
    ghost: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val ring by animateFloatAsState((probability ?: 0.0).toFloat(), tween(700), label = "probability")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(size + 12.dp).drawBehind {
                    val stroke = 5.dp.toPx()
                    if (probability != null) {
                        drawArc(color.copy(alpha = .18f), 0f, 360f, false, style = Stroke(stroke))
                        drawArc(color, -90f, 360f * ring, false, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                },
            )
            Box(
                Modifier.size(size).clip(CircleShape)
                    .background(if (ghost) Color.Transparent else color.copy(alpha = .14f))
                    .then(if (ghost) Modifier.dashedCircle(color) else Modifier.border(3.dp, color, CircleShape))
                    .clickable(enabled = enabled) { menu = true }
                    .semantics { contentDescription = slotDescription(slot) },
                contentAlignment = Alignment.Center,
            ) {
                if (!ghost) SlotContent(slot, color, size)
            }
            if (slot.label.isNotEmpty()) {
                Box(Modifier.align(Alignment.TopStart).size(30.dp).clip(CircleShape).background(color),
                    contentAlignment = Alignment.Center) {
                    Text(slot.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf("text" to "Type text", "photo" to "Photo", "camera" to "Take a photo", "video" to "Video",
                    "audio" to "Audio").forEach { (source, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { menu = false; onPick(source) })
                }
                if (slot.part != null) DropdownMenuItem(text = { Text("Clear") }, onClick = { menu = false; onClear() })
                if (onRemove != null) DropdownMenuItem(text = { Text("Remove option") }, onClick = { menu = false; onRemove() })
            }
        }
        if (probability != null) {
            Text(percent(probability), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun slotDescription(slot: DecisionSlot): String {
    val name = if (slot.label.isEmpty()) "Input" else "Option ${slot.label}"
    val part = slot.part ?: return "$name, empty. Tap to add text, a photo, video or audio."
    return when (part.kind) {
        DECISION_TEXT -> "$name: ${part.text.take(120)}"
        DECISION_IMAGE -> "$name: photo"
        DECISION_VIDEO -> "$name: video ${part.name}"
        else -> "$name: audio ${part.name}"
    }
}

@Composable
private fun BoxScope.SlotContent(slot: DecisionSlot, color: Color, size: Dp) {
    val part = slot.part
    when {
        part == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+", fontSize = (size.value / 3).sp, color = color, fontWeight = FontWeight.Light)
            Text("Tap to add", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        part.kind == DECISION_TEXT -> Text(
            part.text.trim(),
            Modifier.padding(horizontal = size / 7),
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value / 108f * when {
                part.text.length <= 14 -> 18f
                part.text.length <= 48 -> 14f
                else -> 12f
            }).sp,
        )
        slot.preview != null -> {
            Image(slot.preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (part.kind == DECISION_VIDEO) {
                Box(Modifier.size(size / 3).clip(CircleShape).background(Color.Black.copy(alpha = .55f)),
                    contentAlignment = Alignment.Center) { Text("▶", color = Color.White, fontSize = (size.value / 7).sp) }
            }
        }
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (part.kind == DECISION_AUDIO) StudioSymbols.Audio else StudioSymbols.Video, null,
                Modifier.size(size / 3), tint = color)
            Text(part.name.ifBlank { part.kind }, Modifier.padding(horizontal = size / 6),
                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
        }
    }
}

/** A dashed outline where a window was before it flew to the right. */
private fun Modifier.dashedCircle(color: Color): Modifier = drawBehind {
    val stroke = 3.dp.toPx()
    drawCircle(color.copy(alpha = .6f), radius = size.minDimension / 2 - stroke / 2,
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
}

@Composable
private fun BigWindow(
    thinking: Boolean,
    spin: Float,
    landedSlot: DecisionSlot?,
    landedColor: Color?,
    kind: DecisionKind,
    answer: DecisionAnswer?,
    modifier: Modifier,
) {
    val accent = when {
        answer == null -> MaterialTheme.colorScheme.primary
        kind == DecisionKind.YesNo -> if (answer.yes == true) yesColor else noColor
        else -> landedColor ?: MaterialTheme.colorScheme.primary
    }
    Box(modifier.size(BigWindow + 24.dp), contentAlignment = Alignment.Center) {
        // A spinning rainbow ring while the model thinks; a solid ring once it has answered.
        Box(Modifier.size(BigWindow + 20.dp).graphicsLayer { rotationZ = if (thinking) spin else 0f }.drawBehind {
            val stroke = 6.dp.toPx()
            if (thinking) {
                drawCircle(Brush.sweepGradient(optionColors + optionColors.first()), radius = size.minDimension / 2 - stroke,
                    style = Stroke(stroke))
            } else {
                drawCircle(accent.copy(alpha = if (answer == null) .35f else 1f), radius = size.minDimension / 2 - stroke,
                    style = Stroke(stroke, pathEffect = if (answer == null) PathEffect.dashPathEffect(floatArrayOf(18f, 14f)) else null))
            }
        })
        Box(Modifier.size(BigWindow).clip(CircleShape).background(accent.copy(alpha = .12f)),
            contentAlignment = Alignment.Center) {
            when {
                landedSlot != null && answer != null -> SlotContent(landedSlot, accent, BigWindow)
                answer?.refused == true -> Text("No answer", fontWeight = FontWeight.Bold)
                thinking -> Text("Hmm…", style = MaterialTheme.typography.headlineSmall, color = accent)
                else -> Text("?", fontSize = 72.sp, fontWeight = FontWeight.Black, color = accent.copy(alpha = .6f))
            }
        }
        // The verdict stamp.
        if (answer != null && !answer.refused) {
            val stamp = verdictText(kind, answer, landedSlot)
            val shown = remember(answer) { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter),
                enter = scaleIn(spring(dampingRatio = .45f)) + fadeIn()) {
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(accent).padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(stamp, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun verdictText(kind: DecisionKind, answer: DecisionAnswer, slot: DecisionSlot?): String = when (kind) {
    DecisionKind.Choice -> "${answer.choice ?: slot?.label.orEmpty()} wins" +
        (answer.probabilities[answer.choice]?.let { " · ${percent(it)}" } ?: "")
    DecisionKind.YesNo -> (if (answer.yes == true) "YES" else "NO") +
        (answer.noul?.let { " · ${percent(if (answer.yes == true) it else 1 - it)}" } ?: "")
    DecisionKind.Score -> answer.scoreLevel?.second?.takeIf { it.isNotBlank() }
        ?: answer.score?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
}

@Composable
private fun DecisionDetails(result: DecisionResult, ask: DecisionAsk) {
    val answer = result.answer
    OutlinedSection(title = "Why this answer") {
        when {
            answer == null -> Text("The model returned no answer.")
            answer.refused -> Text("The model declined to answer this question.")
            else -> {
                val rows: List<Pair<String, Double>> = when (ask.kind) {
                    DecisionKind.Choice -> ask.options.filter { it.parts.isNotEmpty() }
                        .map { "Option ${it.label}" to (answer.probabilities[it.label] ?: 0.0) }
                    DecisionKind.YesNo -> answer.noul?.let { listOf("Yes" to it, "No" to 1 - it) }.orEmpty()
                    DecisionKind.Score -> answer.legend.entries.sortedBy { it.key.toIntOrNull() ?: 0 }
                        .map { it.value to (answer.probabilities[it.key] ?: 0.0) }
                }
                rows.forEachIndexed { index, (name, value) ->
                    val color = when (ask.kind) {
                        DecisionKind.Choice -> optionColors[index % optionColors.size]
                        DecisionKind.YesNo -> if (index == 0) yesColor else noColor
                        DecisionKind.Score -> MaterialTheme.colorScheme.primary
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { value.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)), color = color,
                            trackColor = color.copy(alpha = .15f))
                        Text(percent(value), Modifier.width(52.dp), textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (ask.kind == DecisionKind.Score && answer.score != null) {
                    Text("Score ${String.format(Locale.US, "%.2f", answer.score)} on a scale of 0 to ${answer.legend.size - 1}.",
                        style = MaterialTheme.typography.bodySmall)
                }
                answer.confidence?.let { Text("Confidence ${percent(it)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text(listOfNotNull(result.model.ifBlank { null }, result.provider.ifBlank { null },
            result.cost?.let { "cost $" + String.format(Locale.US, "%.6f", it).trimEnd('0').trimEnd('.') })
            .joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun percent(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100).roundToInt()}%"
