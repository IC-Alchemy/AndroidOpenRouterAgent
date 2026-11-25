package com.example.openrouteragent

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64

/*
 * Photos, videos and audio for decisions. Photos become JPEGs between 256 and 1024 pixels on the long edge, which
 * every image-reading decision model accepts (Jev takes 256 to 1,568 per side, Clef about 300 KB). Videos and audio
 * are sent as they are, up to 16 MB, the limit decision models share.
 */

private const val DECISION_IMAGE_EDGE = 1024
private const val DECISION_IMAGE_MIN_EDGE = 256
private const val DECISION_JPEG_QUALITY = 85

/** A decision input picked on the phone, with a small picture to show in its round window. */
internal data class PickedDecisionInput(val part: DecisionPart, val preview: Bitmap?)

internal class DecisionMediaReader(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    /** Reads a photo, video or audio clip that the user picked. [kind] is what they asked to pick. */
    fun read(uri: Uri, kind: String): PickedDecisionInput {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: kind
        val mimeType = (resolver.getType(uri) ?: "").lowercase().substringBefore(';').trim()
        return when (kind) {
            DECISION_IMAGE -> {
                val bitmap = decodeImage(uri) ?: throw IOException("Android could not read the photo $name.")
                val (part, preview) = imagePart(bitmap, name)
                PickedDecisionInput(part, preview)
            }
            DECISION_VIDEO -> {
                if (!mimeType.startsWith("video/")) throw IOException("$name isn't a video.")
                val bytes = readLimited(uri, name)
                val preview = runCatching { videoFrame(uri) }.getOrNull()
                PickedDecisionInput(DecisionPart(DECISION_VIDEO, mimeType = mimeType,
                    base64 = Base64.getEncoder().encodeToString(bytes), name = name), preview)
            }
            DECISION_AUDIO -> {
                val format = decisionAudioFormat(mimeType) ?: audioFormatFromName(name)
                    ?: throw IOException("Pick WAV, MP3, M4A, AAC, FLAC, OGG, Opus or WebM audio.")
                val bytes = readLimited(uri, name)
                val known = if (decisionAudioFormat(mimeType) != null) mimeType else "audio/$format"
                PickedDecisionInput(DecisionPart(DECISION_AUDIO, mimeType = known,
                    base64 = Base64.getEncoder().encodeToString(bytes), name = name), null)
            }
            else -> throw IllegalArgumentException("Unknown decision input.")
        }
    }

    /** Photos and recordings attached to the current chat message, for the agent's decision tool. */
    fun fromChat(store: ChatMediaStore, chatId: String?, media: List<ChatMedia>): List<DecisionPart> =
        media.mapNotNull { item ->
            val file = store.find(chatId, item.file) ?: return@mapNotNull null
            when (item.kind) {
                MEDIA_IMAGE -> BitmapFactory.decodeFile(file.path)?.let { imagePart(it, item.name).first }
                MEDIA_AUDIO -> if (file.length() <= MAX_DECISION_MEDIA_BYTES) DecisionPart(DECISION_AUDIO,
                    mimeType = item.mimeType, base64 = Base64.getEncoder().encodeToString(file.readBytes()),
                    name = item.name) else null
                else -> null
            }
        }

    private fun imagePart(source: Bitmap, name: String): Pair<DecisionPart, Bitmap> {
        val long = maxOf(source.width, source.height).coerceAtLeast(1)
        val target = when {
            long > DECISION_IMAGE_EDGE -> DECISION_IMAGE_EDGE
            long < DECISION_IMAGE_MIN_EDGE -> DECISION_IMAGE_MIN_EDGE
            else -> long
        }
        val scale = target.toFloat() / long
        val scaled = if (scale == 1f) source else Bitmap.createScaledBitmap(source,
            (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        // JPEG has no transparency, so draw on white first.
        val opaque = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            android.graphics.Canvas(this).drawBitmap(scaled, 0f, 0f, null)
        }
        val bytes = ByteArrayOutputStream().use {
            opaque.compress(Bitmap.CompressFormat.JPEG, DECISION_JPEG_QUALITY, it)
            it.toByteArray()
        }
        return DecisionPart(DECISION_IMAGE, mimeType = "image/jpeg", base64 = Base64.getEncoder().encodeToString(bytes),
            name = name) to opaque
    }

    private fun decodeImage(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                if (long > DECISION_IMAGE_EDGE * 2) {
                    val ratio = DECISION_IMAGE_EDGE * 2f / long
                    decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECISION_IMAGE_EDGE) sample *= 2
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    private fun videoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0)?.let { frame ->
                val long = maxOf(frame.width, frame.height).coerceAtLeast(1)
                if (long <= 512) frame else Bitmap.createScaledBitmap(frame,
                    (frame.width * 512f / long).toInt().coerceAtLeast(1), (frame.height * 512f / long).toInt().coerceAtLeast(1), true)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readLimited(uri: Uri, name: String): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IOException("Android could not open $name.")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_DECISION_MEDIA_BYTES) throw IOException("$name is larger than 16 MB. Pick a shorter clip.")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun audioFormatFromName(name: String): String? =
        when (name.substringAfterLast('.', "").lowercase()) {
            "wav" -> "wav"; "mp3" -> "mp3"; "m4a" -> "m4a"; "aac" -> "aac"; "flac" -> "flac"
            "ogg" -> "ogg"; "opus" -> "opus"; "webm" -> "webm"; "aif", "aiff" -> "aiff"
            else -> null
        }
}

/** A fresh file for a camera photo, shared with the camera app through the app's file provider. */
internal fun decisionCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "shared/decisions").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
}
