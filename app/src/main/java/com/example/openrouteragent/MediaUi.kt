package com.example.openrouteragent

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decodes a stored photo off the main thread, scaled down to about [maxEdge] pixels. */
@Composable
internal fun rememberMediaBitmap(file: File?, maxEdge: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, file?.path, maxEdge) {
        value = if (file == null) null else withContext(Dispatchers.IO) { decodeScaled(file, maxEdge) }
    }
    return bitmap
}

internal fun decodeScaled(file: File, maxEdge: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** A row of attachments or generated images. [onRemove] adds a remove button to each, for the composer. */
@Composable
internal fun MediaStrip(
    items: List<ChatMedia>,
    fileFor: (ChatMedia) -> File?,
    onOpen: (ChatMedia) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    onRemove: ((ChatMedia) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val remove: (() -> Unit)? = if (onRemove == null) null else ({ onRemove(item) })
            MediaThumbnail(item, fileFor(item), size, { onOpen(item) }, remove)
        }
    }
}

@Composable
internal fun MediaThumbnail(item: ChatMedia, file: File?, size: Dp, onOpen: () -> Unit, onRemove: (() -> Unit)? = null) {
    val label = mediaLabel(item)
    Box {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(StudioHairlineWidth, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = "Open $label", onClick = onOpen),
        ) {
            val bitmap = if (item.isImage) rememberMediaBitmap(file, 384) else null
            if (bitmap != null) {
                Image(bitmap, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (item.isImage) StudioSymbols.Photo else StudioSymbols.File, null, Modifier.size(22.dp))
                    Text(if (file == null) "Missing" else item.name.ifBlank { item.kind },
                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (onRemove != null) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp)
                    .clickable(role = Role.Button, onClickLabel = "Remove $label", onClick = onRemove)) {
                Icon(Icons.Default.Close, "Remove $label", Modifier.padding(3.dp),
                    tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

/** Images the model made, shown full width under its reply. A tap opens them full screen. */
@Composable
internal fun GeneratedImages(items: List<ChatMedia>, fileFor: (ChatMedia) -> File?, onOpen: (ChatMedia) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val file = fileFor(item)
            val label = mediaLabel(item)
            val bitmap = rememberMediaBitmap(file, 1600)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(StudioHairlineWidth, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Open $label", onClick = { onOpen(item) }),
            ) {
                when {
                    bitmap != null -> Image(bitmap, label, Modifier.fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)),
                        contentScale = ContentScale.Fit)
                    file == null -> Text("This image is no longer on this device.", Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall)
                    else -> Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

private fun mediaLabel(item: ChatMedia): String = when (item.kind) {
    MEDIA_IMAGE -> item.name.ifBlank { "image" }
    else -> item.name.ifBlank { "file" }
}

/**
 * Opens a photo full screen, with Share and Save to Photos. Documents and recordings open in another app.
 */
@Composable
internal fun MediaViewer(item: ChatMedia, file: File?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (!item.isImage) {
        // Hand documents and recordings to an app that can show them.
        androidx.compose.runtime.LaunchedEffect(item.file) {
            if (file != null) openMedia(context, item, file) else toast(context, "This file is no longer on this device.")
            onDismiss()
        }
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bitmap = rememberMediaBitmap(file, 2400)
            when {
                bitmap != null -> Image(bitmap, item.name.ifBlank { "Image" }, Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit)
                file == null -> Text("This image is no longer on this device.", Modifier.align(Alignment.Center),
                    color = Color.White)
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
            if (file != null) {
                var saving by remember { mutableStateOf(false) }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 600.dp).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                    TextButton(onClick = { shareMedia(context, item, file) }) {
                        Icon(Icons.Default.Share, null, tint = Color.White)
                        Text("  Share", color = Color.White)
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        TextButton(enabled = !saving, onClick = {
                            saving = true
                            val saved = saveToPhotos(context, item, file)
                            saving = false
                            toast(context, if (saved) "Saved to Photos." else "Android could not save the image.")
                        }) {
                            Icon(StudioSymbols.Import, null, tint = Color.White)
                            Text("  Save to Photos", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** Copies the file into the shared cache folder, which the app's FileProvider serves. */
internal fun shareableUri(context: Context, item: ChatMedia, file: File): Uri {
    val dir = File(context.cacheDir, "shared/media").apply { mkdirs() }
    val ext = file.extension
    val base = item.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(60)
        .ifBlank { "image" }
    val target = File(dir, "$base.$ext")
    file.copyTo(target, overwrite = true)
    return FileProvider.getUriForFile(context, context.packageName + ".files", target)
}

internal fun shareMedia(context: Context, item: ChatMedia, file: File) {
    runCatching {
        val uri = shareableUri(context, item, file)
        val send = Intent(Intent.ACTION_SEND).setType(mimeForMediaFile(file.name)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(context, "Android could not share this file.") }
}

private fun openMedia(context: Context, item: ChatMedia, file: File) {
    try {
        val uri = shareableUri(context, item, file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeForMediaFile(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        toast(context, "No app on this phone can open ${item.name.ifBlank { "this file" }}.")
    } catch (_: Exception) {
        toast(context, "Android could not open this file.")
    }
}

/** Saves an image to Pictures/OpenRouter Agent. Android 10 and later need no permission for this. */
@androidx.annotation.RequiresApi(29)
internal fun saveToPhotos(context: Context, item: ChatMedia, file: File): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, item.name.substringBeforeLast('.').ifBlank { "OpenRouter image" } +
            "." + file.extension)
        put(MediaStore.Images.Media.MIME_TYPE, mimeForMediaFile(file.name))
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenRouter Agent")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("No output stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (_: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
