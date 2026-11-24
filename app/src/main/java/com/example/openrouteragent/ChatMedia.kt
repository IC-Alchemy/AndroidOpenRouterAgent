package com.example.openrouteragent

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * A photo, document or recording in a chat: one the user attached, or an image the model made.
 * The bytes live in app storage under [file], a name made from their SHA-256, so the same bytes are stored once.
 */
@Serializable
data class ChatMedia(
    val file: String,
    val kind: String,
    val mimeType: String,
    val name: String = "",
    val bytes: Long = 0,
) {
    val isImage: Boolean get() = kind == MEDIA_IMAGE
}

internal const val MEDIA_IMAGE = "image"
internal const val MEDIA_FILE = "file"
internal const val MEDIA_AUDIO = "audio"
internal const val MEDIA_TEXT = "text"

internal const val MAX_ATTACHMENTS = 8
internal const val MAX_ATTACHMENT_BYTES = 6L * 1024 * 1024
internal const val MAX_TURN_ATTACHMENT_BYTES = 8L * 1024 * 1024
internal const val MAX_TEXT_ATTACHMENT_BYTES = 200L * 1024
private const val MAX_IMAGE_EDGE = 1600
private const val MAX_SOURCE_BYTES = 40L * 1024 * 1024

private val mediaName = Regex("^[0-9a-f]{64}\\.[a-z0-9]{2,4}$")

private val extensions = mapOf(
    "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif",
    "application/pdf" to "pdf", "audio/mpeg" to "mp3", "audio/wav" to "wav", "text/plain" to "txt",
)

internal fun mimeForMediaFile(file: String): String =
    extensions.entries.firstOrNull { file.endsWith("." + it.value) }?.key ?: "application/octet-stream"

internal fun isMediaFileName(file: String): Boolean = mediaName.matches(file)

/**
 * Stores chat media in app-private storage. Each chat has its own folder, removed with the chat.
 * Attachments wait in the drafts folder until their message is sent.
 */
internal class ChatMediaStore(private val root: File) {
    val drafts: File get() = File(root, "drafts")

    fun chatDir(chatId: String): File = File(root, "chats/" + sha256(chatId.toByteArray()).take(32))

    /** Writes [bytes] once and returns the file name. */
    fun store(dir: File, bytes: ByteArray, mimeType: String): String {
        val ext = extensions[mimeType] ?: throw IOException("Unsupported media type $mimeType.")
        val name = sha256(bytes) + "." + ext
        val target = File(dir, name)
        if (!target.isFile || target.length() != bytes.size.toLong()) {
            dir.mkdirs()
            val temp = File(dir, "$name.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("Android could not save the attachment.")
            }
        }
        return name
    }

    /** The stored file, looked up in the chat's folder and then in drafts. */
    fun find(chatId: String?, file: String): File? {
        if (!isMediaFileName(file)) return null
        return listOfNotNull(chatId?.let { chatDir(it) }, drafts).map { File(it, file) }.firstOrNull { it.isFile }
    }

    /** Copies sent attachments out of drafts into the chat's folder. */
    fun adopt(chatId: String, media: List<ChatMedia>) {
        val dir = chatDir(chatId)
        media.forEach { item ->
            val source = File(drafts, item.file)
            val target = File(dir, item.file)
            if (!isMediaFileName(item.file) || target.isFile || !source.isFile) return@forEach
            dir.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    fun discardDrafts(media: List<ChatMedia>) {
        media.filter { isMediaFileName(it.file) }.forEach { File(drafts, it.file).delete() }
    }

    /** Removes drafts that nothing has used for a week. */
    fun pruneDrafts(now: Long = System.currentTimeMillis()) {
        drafts.listFiles()?.filter { now - it.lastModified() > 7L * 24 * 60 * 60 * 1000 }?.forEach { it.delete() }
    }

    fun deleteChat(chatId: String) {
        chatDir(chatId).deleteRecursively()
    }

    /** A data URL, or the bare base64, for the runtime. Empty when the file is gone. */
    fun encoded(chatId: String?, file: String, dataUrl: Boolean): String {
        val stored = find(chatId, file) ?: return ""
        val base64 = java.util.Base64.getEncoder().encodeToString(stored.readBytes())
        return if (dataUrl) "data:${mimeForMediaFile(file)};base64,$base64" else base64
    }

    /**
     * Saves media the runtime found in a reply: a data URL, or bare base64 for an image.
     * Returns the file name, or null when the value is not a supported image, document or recording.
     */
    fun storeEncoded(chatId: String, value: String, dataUrl: Boolean): String? {
        val (declared, payload) = if (dataUrl) {
            val comma = value.indexOf(',')
            val header = if (comma > 5) value.substring(5, comma) else return null
            if (!header.endsWith(";base64")) return null
            header.removeSuffix(";base64").lowercase() to value.substring(comma + 1)
        } else null to value
        val bytes = try {
            java.util.Base64.getMimeDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty()) return null
        // Only images, PDFs and recordings are kept. Text stays in the conversation as it is.
        val mimeType = sniffMime(bytes) ?: declared?.let { normalizeMime(it) }
            ?.takeIf { it in extensions && it != "text/plain" } ?: return null
        return store(chatDir(chatId), bytes, mimeType)
    }
}

internal fun chatMediaStore(context: android.content.Context) =
    ChatMediaStore(File(context.applicationContext.noBackupFilesDir, "chat_media"))

/** An attachment sent with a message. Text files carry their text, which goes to the model inline. */
internal data class RunMedia(val media: ChatMedia, val text: String? = null)

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sniffMime(bytes: ByteArray): String? {
    fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[it].toInt() and 0xff == values[it] }
    return when {
        starts(0x89, 0x50, 0x4e, 0x47) -> "image/png"
        starts(0xff, 0xd8, 0xff) -> "image/jpeg"
        starts(0x47, 0x49, 0x46, 0x38) -> "image/gif"
        bytes.size >= 12 && starts(0x52, 0x49, 0x46, 0x46) && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 12 && starts(0x52, 0x49, 0x46, 0x46) && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE" -> "audio/wav"
        starts(0x25, 0x50, 0x44, 0x46) -> "application/pdf"
        starts(0x49, 0x44, 0x33) || starts(0xff, 0xfb) || starts(0xff, 0xf3) || starts(0xff, 0xf2) -> "audio/mpeg"
        else -> null
    }
}

internal fun normalizeMime(type: String): String = when (val lower = type.lowercase().substringBefore(';').trim()) {
    "image/jpg", "image/pjpeg" -> "image/jpeg"
    "audio/mp3", "audio/mpeg3", "audio/x-mpeg" -> "audio/mpeg"
    "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "audio/wav"
    else -> lower
}

private val textTypes = setOf("application/json", "application/xml", "application/x-yaml", "application/yaml",
    "application/javascript", "application/x-sh", "application/sql", "application/toml", "application/x-kotlin")
private val textExtensions = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "html", "htm",
    "css", "js", "ts", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "swift", "sh", "sql",
    "toml", "ini", "log", "gradle", "properties")

/** What kind of attachment a picked file is, from its type and name. Null when the app cannot send it. */
internal fun attachmentKind(mimeType: String, name: String): String? {
    val type = normalizeMime(mimeType)
    return when {
        type.startsWith("image/") -> MEDIA_IMAGE
        type == "application/pdf" || name.lowercase().endsWith(".pdf") -> MEDIA_FILE
        type == "audio/mpeg" || type == "audio/wav" -> MEDIA_AUDIO
        type.startsWith("text/") || type in textTypes || name.substringAfterLast('.', "").lowercase() in textExtensions ->
            MEDIA_TEXT
        else -> null
    }
}

/** Reads a picked or shared file, shrinks photos, and stores it as a draft attachment. */
internal class AttachmentImporter(private val resolver: ContentResolver, private val store: ChatMediaStore) {
    fun import(uri: Uri, typeHint: String? = null): ChatMedia {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IOException("Android could not open that file.")
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val mimeType = normalizeMime(resolver.getType(uri) ?: typeHint ?: "application/octet-stream")
        val kind = attachmentKind(mimeType, name)
            ?: throw IOException("$name can't be attached. Attach a photo, PDF, MP3, WAV or text file.")
        return when (kind) {
            MEDIA_IMAGE -> {
                val bytes = shrinkImage(uri) ?: throw IOException("Android could not read the photo $name.")
                media(bytes, "image/jpeg", kind, name)
            }
            MEDIA_TEXT -> {
                val bytes = readLimited(uri, MAX_TEXT_ATTACHMENT_BYTES, name)
                // Text goes to the model as text, so it must decode as UTF-8.
                val text = bytes.toString(Charsets.UTF_8)
                if ('�' in text) throw IOException("$name isn't plain text the app can read.")
                media(bytes, "text/plain", kind, name)
            }
            else -> {
                val bytes = readLimited(uri, MAX_ATTACHMENT_BYTES, name)
                val sniffed = sniffMime(bytes)
                val actual = if (kind == MEDIA_FILE) "application/pdf" else sniffed ?: mimeType
                if (kind == MEDIA_FILE && sniffed != "application/pdf") throw IOException("$name isn't a PDF.")
                if (kind == MEDIA_AUDIO && actual != "audio/mpeg" && actual != "audio/wav") {
                    throw IOException("Attach audio as MP3 or WAV.")
                }
                media(bytes, actual, kind, name)
            }
        }
    }

    private fun media(bytes: ByteArray, mimeType: String, kind: String, name: String) =
        ChatMedia(store.store(store.drafts, bytes, mimeType), kind, mimeType, name.take(120), bytes.size.toLong())

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun readLimited(uri: Uri, limit: Long, name: String): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IOException("Android could not open $name.")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) {
                    throw IOException("$name is larger than ${sizeLabel(limit)}.")
                }
            }
            out.toByteArray()
        }
    }

    /** Scales the photo so its long edge is at most 1600 pixels, and saves it as JPEG. */
    private fun shrinkImage(uri: Uri): ByteArray? {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the photo's EXIF rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (width, height) = info.size.width to info.size.height
                val scale = MAX_IMAGE_EDGE.toFloat() / maxOf(width, height)
                if (scale < 1f) decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val scale = MAX_IMAGE_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
            if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
        }
        val out = ByteArrayOutputStream()
        // JPEG has no transparency, so draw transparent photos over white.
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                val canvas = android.graphics.Canvas(it)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        } else bitmap
        opaque.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}

internal fun sizeLabel(bytes: Long): String =
    if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "${maxOf(1, bytes / 1024)} KB"

/** The total size of attachments, for the per-message limit. */
internal fun attachmentProblem(media: List<ChatMedia>, model: CatalogModel?): String? = when {
    media.size > MAX_ATTACHMENTS -> "Attach at most $MAX_ATTACHMENTS files to one message."
    media.sumOf { it.bytes } > MAX_TURN_ATTACHMENT_BYTES -> "Attachments can add up to 8 MB per message."
    model == null -> null
    media.any { it.kind == MEDIA_IMAGE } && !model.accepts("image") ->
        "${model.name} can't read images. Choose a model marked Vision, or remove the photo."
    media.any { it.kind == MEDIA_AUDIO } && !model.accepts("audio") ->
        "${model.name} can't listen to audio. Choose a model marked Audio, or remove the recording."
    else -> null
}
