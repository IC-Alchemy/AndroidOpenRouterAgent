package com.example.openrouteragent

import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OpenUncensoredImagesDialog(agent: AgentViewModel, onDismiss: () -> Unit, onConnect: () -> Unit) {
    var modelId by rememberSaveable { mutableStateOf("z-image-turbo") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var negativePrompt by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("1024x1024") }
    var count by rememberSaveable { mutableStateOf(1) }
    val saving = remember { FileDialogOperation() }
    val models = agent.imageModels
    val selected = models.firstOrNull { it.id == modelId } ?: models.firstOrNull()
    val generating = agent.generatingImages
    val editable = !generating && saving.busy == null
    val promptLength = prompt.trim().length
    val promptLimit = selected?.promptLimit
    val result = agent.imageResult
    val error = agent.imageError
    val output = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { if (models.isEmpty()) agent.refreshImageModels() }
    LaunchedEffect(generating, result, error) {
        if (generating || result != null || error != null) output.bringIntoView()
    }

    AlertDialog(
        onDismissRequest = { if (saving.busy == null) onDismiss() },
        title = { Text("Generate images") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Image model", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = agent::refreshImageModels, enabled = editable && !agent.imageModelsLoading) {
                        Text("Refresh")
                    }
                }
                ImageOptionMenu(selected?.name ?: "Choose image model", models, editable,
                    label = { model ->
                        Column {
                            Text(model.name)
                            model.priceUsd?.let { Text("${usdLabel(it)} per image", style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    onSelect = { modelId = it.id },
                )
                if (agent.imageModelsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                agent.imageModelsError?.let { CopyableText(it, color = MaterialTheme.colorScheme.error) }
                if (models.isEmpty() && !agent.imageModelsLoading && agent.imageModelsError == null) {
                    Text("No image generation models are listed.")
                }
                CopyableOutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), enabled = editable,
                    label = { Text("Prompt") }, minLines = 2, maxLines = 4,
                    supportingText = if (promptLimit != null && promptLength > promptLimit) {
                        { Text("Use up to $promptLimit characters for this model.", color = MaterialTheme.colorScheme.error) }
                    } else null)
                CopyableOutlinedTextField(negativePrompt, { negativePrompt = it }, Modifier.fillMaxWidth(),
                    enabled = editable, label = { Text("Negative prompt (optional)") }, maxLines = 2,
                    supportingText = { Text("Used by Civitai models.") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Size", style = MaterialTheme.typography.labelLarge)
                        ImageOptionMenu(size, imageGenerationSizes, editable, { Text(it) }, { size = it })
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Images", style = MaterialTheme.typography.labelLarge)
                        ImageOptionMenu(count.toString(), (1..4).toList(), editable, { Text(it.toString()) }, { count = it })
                    }
                }
                selected?.priceUsd?.let { price ->
                    Text("Estimated total ${usdLabel(price, count)}", style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.bringIntoViewRequester(output), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (generating) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Generating images…")
                    }
                    error?.let { CopyableText(it, color = MaterialTheme.colorScheme.error) }
                    if (result != null) {
                        Text(if (generating || error != null) "Previous images" else "Generated images",
                            style = MaterialTheme.typography.titleSmall)
                        if (result.failedCount > 0) {
                            Text("${result.images.size} of ${result.images.size + result.failedCount} images received.")
                        }
                        result.costUsd?.let { Text("Charged ${usdLabel(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (result != null) {
                    result.images.forEachIndexed { index, bytes ->
                        key(bytes) { GeneratedImage(bytes, index + 1, saving, editable) }
                    }
                }
                FileDialogStatus(saving)
            }
        },
        confirmButton = {
            when {
                generating -> TextButton(onClick = agent::cancelImageGeneration) { Text("Stop waiting") }
                !agent.providerReady -> Button(onClick = onConnect) { Text("Connect provider") }
                else -> Button(
                    enabled = editable && promptLimit != null && promptLength in 1..promptLimit,
                    onClick = {
                        selected?.let {
                            saving.clear()
                            agent.generateImages(ImageGenerationRequest(it, prompt, size, count, negativePrompt))
                        }
                    },
                ) { Text("Generate") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = saving.busy == null) { Text("Close") } },
    )
}

@Composable
private fun <T> ImageOptionMenu(
    value: String, options: List<T>, enabled: Boolean, label: @Composable (T) -> Unit, onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { label(option) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}

@Composable
private fun GeneratedImage(bytes: ByteArray, number: Int, saving: FileDialogOperation, canSave: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember(bytes) { mutableStateOf<Result<ImagePreview>?>(null) }
    LaunchedEffect(bytes) {
        preview = withContext(Dispatchers.IO) { runCatching { imagePreview(bytes) } }
    }
    val image = preview?.getOrNull()
    val mime = image?.mime ?: "application/octet-stream"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) saving.run(scope, "Saving image…") {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open the destination.")
                output.use { it.write(bytes) }
            }
            "Image saved."
        }
    }
    when {
        image != null -> {
            Image(image.bitmap, contentDescription = "Generated image $number",
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
            TextButton(enabled = canSave, onClick = {
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
                save.launch("openuncensored-image-$number.$extension")
            }) { Text("Save image $number") }
        }
        preview == null -> Text("Loading preview…")
        else -> Text("Image $number could not be displayed.", color = MaterialTheme.colorScheme.error)
    }
}

private data class ImagePreview(val bitmap: ImageBitmap, val mime: String)

private fun imagePreview(bytes: ByteArray): ImagePreview {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val mime = options.outMimeType ?: throw IOException("Unknown image format.")
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1_024) {
        options.inSampleSize *= 2
    }
    options.inJustDecodeBounds = false
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw IOException("Cannot decode image.")
    return ImagePreview(bitmap.asImageBitmap(), mime)
}

private fun usdLabel(price: Double, count: Int = 1): String =
    "$" + (price.toBigDecimal() * count.toBigDecimal()).stripTrailingZeros().toPlainString()
