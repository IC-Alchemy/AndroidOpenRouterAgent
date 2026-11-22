package com.example.openrouteragent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

const val ICON_URI_FILE = MINI_APP_ICON_FILE_NAME
const val ICON_URI_EMOJI_PREFIX = "emoji:"
const val ICON_URI_PRESET_PREFIX = "preset:"

/**
 * Extracts up to [maxClusters] complete extended grapheme clusters without splitting surrogate pairs,
 * ZWJ emoji sequences (e.g., 👨‍👩‍👧‍👦), variation selectors, skin-tone modifiers, or flag pairs.
 */
internal fun sanitizeEmojiText(raw: String, maxClusters: Int = 2): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || maxClusters <= 0) return ""
    var i = 0
    var clusters = 0
    val len = trimmed.length
    while (i < len && clusters < maxClusters) {
        val cp = trimmed.codePointAt(i)
        i += Character.charCount(cp)
        // Consume second codepoint of a Regional Indicator flag pair (e.g., 🇺🇸).
        if (cp in 0x1F1E6..0x1F1FF && i < len) {
            val next = trimmed.codePointAt(i)
            if (next in 0x1F1E6..0x1F1FF) i += Character.charCount(next)
        }
        // Consume extending characters (variation selectors, skin tones, combining marks, tags) and ZWJ joins.
        while (i < len) {
            val next = trimmed.codePointAt(i)
            if (next == 0x200D) { // Zero-Width Joiner connects the next base codepoint into this same cluster.
                i += Character.charCount(next)
                if (i < len) {
                    val joined = trimmed.codePointAt(i)
                    i += Character.charCount(joined)
                }
            } else if (isEmojiExtender(next)) {
                i += Character.charCount(next)
            } else {
                break
            }
        }
        clusters++
    }
    return trimmed.substring(0, i)
}

private fun isEmojiExtender(cp: Int): Boolean {
    if (cp == 0xFE0E || cp == 0xFE0F || cp == 0x20E3) return true
    if (cp in 0x1F3FB..0x1F3FF) return true // Skin tone modifiers
    if (cp in 0xE0020..0xE007F) return true // Tag sequences (subdivision flags)
    val type = Character.getType(cp)
    return type == Character.NON_SPACING_MARK.toInt() ||
        type == Character.ENCLOSING_MARK.toInt() ||
        type == Character.COMBINING_SPACING_MARK.toInt()
}

fun emojiIconUri(emoji: String): String = "$ICON_URI_EMOJI_PREFIX${sanitizeEmojiText(emoji)}"
fun presetIconUri(presetId: String): String = "$ICON_URI_PRESET_PREFIX${presetId.trim()}"

fun parseEmojiFromIconUri(iconUri: String?): String? =
    iconUri?.takeIf { it.startsWith(ICON_URI_EMOJI_PREFIX) }?.removePrefix(ICON_URI_EMOJI_PREFIX)?.ifBlank { null }

fun parsePresetFromIconUri(iconUri: String?): MiniAppPresetIcon? {
    val id = iconUri?.takeIf { it.startsWith(ICON_URI_PRESET_PREFIX) }?.removePrefix(ICON_URI_PRESET_PREFIX) ?: return null
    return MINI_APP_PRESET_ICONS.firstOrNull { it.id == id }
}

data class MiniAppPresetIcon(
    val id: String,
    val label: String,
    val svg: String,
)

val MINI_APP_THEME_COLORS: List<String> = listOf(
    "#2563EB", // Royal Blue
    "#7C3AED", // Violet
    "#DB2777", // Pink
    "#DC2626", // Crimson
    "#EA580C", // Orange
    "#D97706", // Amber
    "#16A34A", // Green
    "#0D9488", // Teal
    "#0284C7", // Sky
    "#4F46E5", // Indigo
    "#334155", // Slate
    "#111827", // Charcoal
)

val MINI_APP_EMOJI_PRESETS: List<String> = listOf(
    "⏱️", "🧮", "✅", "🔥", "📝", "📊", "💰", "🎯",
    "🎵", "❤️", "🧘", "🏋️", "🍳", "🌤️", "🎲", "🎮",
    "📚", "🗓️", "💡", "🎨", "🚀", "🌿", "☕", "🐾",
)

private fun presetSvg(body: String): String =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">$body</svg>"""

val MINI_APP_PRESET_ICONS: List<MiniAppPresetIcon> = listOf(
    MiniAppPresetIcon("timer", "Timer", presetSvg("""<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 1.5"/><path d="M9 2h6"/>""")),
    MiniAppPresetIcon("calculator", "Calculator", presetSvg("""<rect x="4" y="2" width="16" height="20" rx="2"/><line x1="8" y1="6" x2="16" y2="6"/><line x1="8" y1="11" x2="10" y2="11"/><line x1="14" y1="11" x2="16" y2="11"/><line x1="8" y1="15" x2="10" y2="15"/><line x1="14" y1="15" x2="16" y2="15"/><line x1="8" y1="19" x2="16" y2="19"/>""")),
    MiniAppPresetIcon("checklist", "Checklist", presetSvg("""<path d="M9 6h11"/><path d="M9 12h11"/><path d="M9 18h11"/><path d="M4 6l1 1 2-2"/><path d="M4 12l1 1 2-2"/><path d="M4 18l1 1 2-2"/>""")),
    MiniAppPresetIcon("flame", "Habit", presetSvg("""<path d="M12 2c1 4-2 6-2 10a4 4 0 0 0 8 0c0-3-2-5-3-7 0 2-1 3-2 3 0-2 0-4-1-6z"/><path d="M8.5 11.5C7 13 6 14.5 6 16a6 6 0 0 0 12 0"/>""")),
    MiniAppPresetIcon("calendar", "Calendar", presetSvg("""<rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/>""")),
    MiniAppPresetIcon("chart", "Chart", presetSvg("""<line x1="4" y1="20" x2="20" y2="20"/><rect x="6" y="12" width="3" height="8"/><rect x="11" y="8" width="3" height="12"/><rect x="16" y="4" width="3" height="16"/>""")),
    MiniAppPresetIcon("notes", "Notes", presetSvg("""<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="17" x2="13" y2="17"/>""")),
    MiniAppPresetIcon("wallet", "Budget", presetSvg("""<rect x="2" y="6" width="20" height="14" rx="2"/><path d="M16 13h2"/><path d="M2 10h20"/>""")),
    MiniAppPresetIcon("heart", "Health", presetSvg("""<path d="M12 21s-7-4.5-9-9a5.5 5.5 0 0 1 9-6 5.5 5.5 0 0 1 9 6c-2 4.5-9 9-9 9z"/>""")),
    MiniAppPresetIcon("music", "Music", presetSvg("""<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>""")),
    MiniAppPresetIcon("star", "Star", presetSvg("""<polygon points="12 2 15.1 8.3 22 9.3 17 14.1 18.2 21 12 17.8 5.8 21 7 14.1 2 9.3 8.9 8.3 12 2"/>""")),
    MiniAppPresetIcon("compass", "Explore", presetSvg("""<circle cx="12" cy="12" r="10"/><polygon points="16.2 7.8 14.1 14.1 7.8 16.2 9.9 9.9 16.2 7.8"/>""")),
    MiniAppPresetIcon("bulb", "Idea", presetSvg("""<path d="M9 18h6"/><path d="M10 22h4"/><path d="M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z"/>""")),
    MiniAppPresetIcon("trophy", "Game", presetSvg("""<path d="M8 21h8"/><path d="M12 17v4"/><path d="M7 4h10v6a5 5 0 0 1-10 0V4z"/><path d="M7 6H4v2a3 3 0 0 0 3 3"/><path d="M17 6h3v2a3 3 0 0 1-3 3"/>""")),
    MiniAppPresetIcon("palette", "Design", presetSvg("""<circle cx="12" cy="12" r="9"/><circle cx="9" cy="10" r="1.2" fill="#FFFFFF"/><circle cx="13" cy="8.5" r="1.2" fill="#FFFFFF"/><circle cx="16" cy="11.5" r="1.2" fill="#FFFFFF"/><circle cx="9.5" cy="14.5" r="1.2" fill="#FFFFFF"/>""")),
    MiniAppPresetIcon("sparkle", "AI App", presetSvg("""<path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3z"/><path d="M19 16l.9 2.1L22 19l-2.1.9L19 22l-.9-2.1L16 19l2.1-.9L19 16z"/>""")),
)

/** Renders launcher icons (SVG via AndroidSVG, emoji, initial, or uploaded gallery photos) into 192x192 PNGs. */
object MiniAppIconRenderer {
    /** Parses a sanitized hex color into an Android ARGB int, never throwing on malformed input. */
    fun parseColorInt(hex: String?): Int {
        val safe = MiniAppSanitizer.sanitizeThemeColor(hex)
        val rgb = safe.removePrefix("#").toInt(16)
        return (0xFF shl 24) or rgb
    }

    /** Returns true if a dark foreground should be used on top of [hex] for WCAG contrast. */
    fun isLightColor(hex: String?): Boolean {
        val color = parseColorInt(hex)
        val r = linearize(((color shr 16) and 0xFF) / 255.0)
        val g = linearize(((color shr 8) and 0xFF) / 255.0)
        val b = linearize((color and 0xFF) / 255.0)
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return luminance > 0.45
    }

    private fun linearize(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    private class BoundedBitmapCache(private val maxEntries: Int) {
        private val entries = object : LinkedHashMap<String, Bitmap>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
                size > maxEntries
        }

        @Synchronized
        fun get(key: String): Bitmap? = entries[key]?.takeUnless { it.isRecycled }

        @Synchronized
        fun put(key: String, bitmap: Bitmap): Bitmap {
            entries[key] = bitmap
            return bitmap
        }
    }

    private val presetForegroundCache = BoundedBitmapCache(maxEntries = 32)
    private val presetTileCache = BoundedBitmapCache(maxEntries = 48)
    private val decodedIconCache = BoundedBitmapCache(maxEntries = 48)

    internal fun cachedPresetTileBitmap(
        preset: MiniAppPresetIcon,
        themeColor: String,
        sizePx: Int = 96,
    ): Bitmap? = presetTileCache.get("${preset.id}:${MiniAppSanitizer.sanitizeThemeColor(themeColor)}:$sizePx")

    /** Renders a preset icon tile, caching parsed SVG foregrounds and rendered tiles with WCAG foreground contrast. */
    fun renderPresetTileBitmap(
        preset: MiniAppPresetIcon,
        themeColor: String,
        sizePx: Int = 96,
    ): Bitmap {
        val safeColor = MiniAppSanitizer.sanitizeThemeColor(themeColor)
        val tileKey = "${preset.id}:$safeColor:$sizePx"
        presetTileCache.get(tileKey)?.let { return it }

        val fgKey = "${preset.id}:$sizePx"
        val foreground = presetForegroundCache.get(fgKey) ?: run {
            val layer = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layer)
            val rendered = drawSanitizedSvg(layerCanvas, MiniAppSanitizer.sanitizeSvg(preset.svg), "#FFFFFF", sizePx)
            if (!rendered) {
                drawCenteredText(layerCanvas, fallbackIconInitial(preset.label), sizePx, 0.46f, true, Color.WHITE)
            }
            presetForegroundCache.put(fgKey, layer)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawRoundedBackground(canvas, sizePx, parseColorInt(safeColor))
        val tintPaint = if (isLightColor(safeColor)) {
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(0xFF111827.toInt(), PorterDuff.Mode.SRC_IN)
            }
        } else {
            null
        }
        canvas.drawBitmap(foreground, 0f, 0f, tintPaint)
        return presetTileCache.put(tileKey, bitmap)
    }

    internal fun cachedDecodedIcon(cacheKey: String): Bitmap? = decodedIconCache.get(cacheKey)

    internal fun decodeBitmapFileCached(file: File, cacheKey: String): Bitmap? {
        decodedIconCache.get(cacheKey)?.let { return it }
        return decodeBitmapFileSafe(file)?.also { decodedIconCache.put(cacheKey, it) }
    }

    fun renderSvgBitmap(
        svgMarkup: String?,
        themeColor: String,
        fallbackLabel: String,
        sizePx: Int = MINI_APP_ICON_SIZE_PX,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawRoundedBackground(canvas, sizePx, parseColorInt(themeColor))

        val cleanSvg = MiniAppSanitizer.sanitizeSvg(svgMarkup)
        val foregroundHex = if (isLightColor(themeColor)) "#111827" else "#FFFFFF"
        val rendered = drawSanitizedSvg(canvas, cleanSvg, foregroundHex, sizePx)

        if (!rendered) {
            drawCenteredText(
                canvas = canvas,
                text = fallbackIconInitial(fallbackLabel),
                sizePx = sizePx,
                scale = 0.46f,
                bold = true,
                color = if (isLightColor(themeColor)) 0xFF111827.toInt() else Color.WHITE,
            )
        }
        return bitmap
    }

    private fun drawSanitizedSvg(
        canvas: Canvas,
        cleanSvg: String?,
        foregroundHex: String,
        sizePx: Int,
    ): Boolean {
        if (cleanSvg == null) return false
        return runCatching {
            val resolvedSvg = currentColorPattern.replace(cleanSvg, foregroundHex)
            val svg = SVG.getFromString(resolvedSvg)
            val pad = sizePx * 0.22f
            val targetSize = sizePx - pad * 2f
            if (svg.documentViewBox == null) {
                val w = svg.documentWidth.takeIf { it > 0f } ?: 24f
                val h = svg.documentHeight.takeIf { it > 0f } ?: 24f
                svg.setDocumentViewBox(0f, 0f, w, h)
            }
            svg.setDocumentWidth(targetSize)
            svg.setDocumentHeight(targetSize)
            canvas.save()
            try {
                canvas.translate(pad, pad)
                svg.renderToCanvas(canvas)
            } finally {
                canvas.restore()
            }
            true
        }.getOrDefault(false)
    }

    private val currentColorPattern = Regex("\\bcurrentColor\\b", RegexOption.IGNORE_CASE)

    fun renderSvgToPng(
        svgMarkup: String?,
        themeColor: String,
        fallbackLabel: String,
        sizePx: Int = MINI_APP_ICON_SIZE_PX,
    ): ByteArray {
        val bitmap = renderSvgBitmap(svgMarkup, themeColor, fallbackLabel, sizePx)
        return bitmap.compressToPngAndRecycle()
    }

    fun renderEmojiBitmap(
        emoji: String,
        themeColor: String,
        sizePx: Int = MINI_APP_ICON_SIZE_PX,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawRoundedBackground(canvas, sizePx, parseColorInt(themeColor))
        drawCenteredText(
            canvas = canvas,
            text = emoji.trim().ifEmpty { "✨" },
            sizePx = sizePx,
            scale = 0.48f,
            bold = false,
            color = Color.WHITE,
        )
        return bitmap
    }

    fun renderEmojiToPng(
        emoji: String,
        themeColor: String,
        sizePx: Int = MINI_APP_ICON_SIZE_PX,
    ): ByteArray = renderEmojiBitmap(emoji, themeColor, sizePx).compressToPngAndRecycle()

    fun renderInitialBitmap(
        name: String,
        themeColor: String,
        sizePx: Int = MINI_APP_ICON_SIZE_PX,
    ): Bitmap = renderSvgBitmap(null, themeColor, name, sizePx)

    /** Reads [file] into memory and closes the descriptor before PNG decoding so concurrent atomic updates never clash. */
    fun decodeBitmapFileSafe(file: File): Bitmap? = runCatching {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** Renders or loads a 192x192 launcher icon Bitmap suitable for Home Screen shortcuts and Recents. */
    fun loadOrRenderAppIconBitmap(app: MiniApp, iconFile: File?): Bitmap {
        if (iconFile != null) {
            val decoded = decodeBitmapFileSafe(iconFile)
            if (decoded != null) {
                if (decoded.width == MINI_APP_ICON_SIZE_PX && decoded.height == MINI_APP_ICON_SIZE_PX) return decoded
                val scaled = Bitmap.createScaledBitmap(decoded, MINI_APP_ICON_SIZE_PX, MINI_APP_ICON_SIZE_PX, true)
                if (scaled !== decoded) decoded.recycle()
                return scaled
            }
        }
        parseEmojiFromIconUri(app.iconUri)?.let { emoji ->
            return renderEmojiBitmap(emoji, app.themeColor)
        }
        parsePresetFromIconUri(app.iconUri)?.let { preset ->
            return renderPresetTileBitmap(preset, app.themeColor, MINI_APP_ICON_SIZE_PX)
        }
        return renderInitialBitmap(app.name, app.themeColor)
    }

    /**
     * Decodes a user-uploaded image stream safely (subsampling large photos), center-crops to square,
     * resizes to 192x192, and encodes as PNG bytes.
     */
    fun decodeAndResizeUploadedImage(
        input: InputStream,
        sizePx: Int = MINI_APP_ICON_SIZE_PX,
        maxInputBytes: Int = 12 * 1024 * 1024,
    ): ByteArray {
        val raw = input.readBytesLimited(maxInputBytes + 1)
        require(raw.isNotEmpty()) { "The selected image is empty." }
        require(raw.size <= maxInputBytes) { "The selected image is too large (max ${maxInputBytes / (1024 * 1024)} MB)." }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a supported image." }

        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, sizePx * 2)
        val decoded = BitmapFactory.decodeByteArray(
            raw,
            0,
            raw.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw IllegalArgumentException("Could not decode the selected image.")

        try {
            val side = min(decoded.width, decoded.height)
            val left = (decoded.width - side) / 2
            val top = (decoded.height - side) / 2
            val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                decoded,
                Rect(left, top, left + side, top + side),
                RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
                paint,
            )
            return output.compressToPngAndRecycle()
        } finally {
            decoded.recycle()
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val minDim = min(width, height)
        while (minDim / (sample * 2) >= max(1, target)) {
            sample *= 2
        }
        return sample
    }

    private fun drawRoundedBackground(canvas: Canvas, sizePx: Int, baseColor: Int) {
        val radius = sizePx * 0.22f
        val darker = darkenColor(baseColor, 0.84f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, sizePx.toFloat(), baseColor, darker, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, paint)
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        sizePx: Int,
        scale: Float,
        bold: Boolean,
        color: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx * scale
            textAlign = Paint.Align.CENTER
            isFakeBoldText = bold
        }
        val baseline = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, sizePx / 2f, baseline, paint)
    }

    private fun Bitmap.compressToPngAndRecycle(): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            check(compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not encode PNG icon." }
            return out.toByteArray()
        } finally {
            recycle()
        }
    }
}

internal fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
