package com.example.openrouteragent

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed interface MiniAppIconUpdate {
    data object Keep : MiniAppIconUpdate
    class PngBytes(val bytes: ByteArray, val iconSvg: String? = null) : MiniAppIconUpdate
    data class Emoji(val emoji: String) : MiniAppIconUpdate
    data class Preset(val presetId: String) : MiniAppIconUpdate
    data object Clear : MiniAppIconUpdate
}

sealed interface MiniAppImportPreview {
    class Bundle(
        val suggestedName: String,
        val description: String,
        val themeColor: String,
        val entryPoint: String,
        val iconUri: String?,
        val iconSvg: String?,
        val files: Map<String, ByteArray>,
    ) : MiniAppImportPreview

    data class RawHtml(
        val suggestedName: String,
        val html: String,
    ) : MiniAppImportPreview
}

private const val MAX_BUNDLE_ZIP_ENTRIES = 64
private const val MAX_IMPORT_BYTES = MINI_APP_MAX_CHARS * 2

/**
 * Manages Mini App bundles under `filesDir/mini_apps/<uuid>/` (`manifest.json`, `icon.png`, `index.html`)
 * backed by a lightweight [MiniAppIndex] (SQLiteOpenHelper in production) for instant launcher loads.
 */
class MiniAppRepository(
    val rootDir: File,
    private val index: MiniAppIndex,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Volatile
    private var syncedFromDisk = false

    @Synchronized
    fun list(): List<MiniApp> {
        ensureSyncedFromDisk()
        return index.list()
    }

    @Synchronized
    fun find(idOrName: String): MiniApp? {
        ensureSyncedFromDisk()
        return index.find(idOrName)
    }

    @Synchronized
    fun appDir(id: String): File {
        require(MiniAppSanitizer.isValidId(id)) { "Invalid mini app id." }
        return File(rootDir, id)
    }

    @Synchronized
    fun manifest(id: String): MiniAppManifest? {
        if (!MiniAppSanitizer.isValidId(id)) return null
        val file = File(File(rootDir, id), MINI_APP_MANIFEST_FILE_NAME)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(MiniAppManifest.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    @Synchronized
    fun iconFile(id: String): File? {
        if (!MiniAppSanitizer.isValidId(id)) return null
        val file = File(File(rootDir, id), MINI_APP_ICON_FILE_NAME)
        return file.takeIf { it.isFile }
    }

    @Synchronized
    fun html(id: String): String? {
        if (!MiniAppSanitizer.isValidId(id)) return null
        val entry = index.find(id)?.entryPoint ?: manifest(id)?.entryPoint ?: MINI_APP_DEFAULT_ENTRY_POINT
        val safeEntry = MiniAppSanitizer.sanitizeEntryPoint(entry)
        val dir = File(rootDir, id)
        val target = resolveInsideDir(dir, safeEntry) ?: return null
        return try {
            target.readText(Charsets.UTF_8)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /**
     * Saves a new mini app bundle (generating a fresh UUID) or updates an existing bundle in place under [id]
     * so the app's WebView origin and saved localStorage/IndexedDB data stay intact.
     */
    @Synchronized
    fun save(
        name: String,
        html: String,
        description: String = "",
        themeColor: String? = null,
        id: String? = null,
        entryPoint: String = MINI_APP_DEFAULT_ENTRY_POINT,
        iconUpdate: MiniAppIconUpdate = MiniAppIconUpdate.Keep,
        imported: Boolean = false,
        allowNetwork: Boolean = !imported,
        extraAssets: Map<String, ByteArray> = emptyMap(),
    ): MiniApp {
        ensureSyncedFromDisk()
        val existing = id?.let { key ->
            require(MiniAppSanitizer.isValidId(key)) { "No mini app has the id $id." }
            index.find(key)?.takeIf { it.id == key }
        }
        require(id == null || existing != null) { "No mini app has the id $id." }

        val now = clock()
        val appId = existing?.id ?: UUID.randomUUID().toString()
        val existingManifest = existing?.let { manifest(it.id) }
        val cleanName = MiniAppSanitizer.sanitizeName(name, fallback = existing?.name ?: "Mini App")
        val cleanColor = if (themeColor != null) {
            MiniAppSanitizer.sanitizeThemeColor(themeColor)
        } else {
            existing?.themeColor ?: MINI_APP_DEFAULT_THEME_COLOR
        }
        val cleanEntry = MiniAppSanitizer.sanitizeEntryPoint(
            if (existing != null && entryPoint == MINI_APP_DEFAULT_ENTRY_POINT) existing.entryPoint else entryPoint,
        )
        val bundleDir = File(rootDir, appId)
        bundleDir.mkdirs()

        val entryFile = requireNotNull(resolveInsideDir(bundleDir, cleanEntry)) {
            "Invalid mini app entry point."
        }
        atomicWriteBytes(entryFile, html.toByteArray(Charsets.UTF_8))

        for ((relativePath, bytes) in extraAssets) {
            val safePath = MiniAppSanitizer.sanitizeEntryPoint(relativePath)
            if (safePath == MINI_APP_MANIFEST_FILE_NAME || safePath == cleanEntry || safePath == MINI_APP_ICON_FILE_NAME) {
                continue
            }
            val assetFile = resolveInsideDir(bundleDir, safePath) ?: continue
            atomicWriteBytes(assetFile, bytes)
        }

        val (resolvedIconUri, resolvedIconSvg) = applyIconUpdate(
            bundleDir = bundleDir,
            currentIconUri = existing?.iconUri,
            currentIconSvg = existingManifest?.iconSvg,
            update = iconUpdate,
        )

        val app = MiniApp(
            id = appId,
            name = cleanName,
            description = description.trim().take(200),
            themeColor = cleanColor,
            iconUri = resolvedIconUri,
            entryPoint = cleanEntry,
            allowNetwork = existing?.allowNetwork ?: allowNetwork,
            imported = existing?.imported ?: imported,
            version = (existing?.version ?: 0) + 1,
            createdAt = existing?.createdAt?.takeIf { it > 0L } ?: now,
            updatedAt = now,
        )
        writeManifest(bundleDir, app.toManifest(iconSvg = resolvedIconSvg))
        index.upsert(app)
        return app
    }

    /** Updates metadata (name, theme color, icon) without modifying index.html or clearing app data. */
    @Synchronized
    fun updateMetadata(
        id: String,
        name: String,
        themeColor: String,
        iconUpdate: MiniAppIconUpdate = MiniAppIconUpdate.Keep,
    ): MiniApp {
        ensureSyncedFromDisk()
        val existing = index.find(id)?.takeIf { it.id == id }
            ?: throw IllegalArgumentException("No mini app has the id $id.")
        val existingManifest = manifest(id)
        val bundleDir = appDir(id)
        bundleDir.mkdirs()

        val cleanName = MiniAppSanitizer.sanitizeName(name, fallback = existing.name)
        val cleanColor = MiniAppSanitizer.sanitizeThemeColor(themeColor, fallback = existing.themeColor)
        val (resolvedIconUri, resolvedIconSvg) = applyIconUpdate(
            bundleDir = bundleDir,
            currentIconUri = existing.iconUri,
            currentIconSvg = existingManifest?.iconSvg,
            update = iconUpdate,
        )

        val updated = existing.copy(
            name = cleanName,
            themeColor = cleanColor,
            iconUri = resolvedIconUri,
            updatedAt = clock(),
        )
        writeManifest(bundleDir, updated.toManifest(iconSvg = resolvedIconSvg))
        index.upsert(updated)
        return updated
    }

    @Synchronized
    fun delete(id: String) {
        ensureSyncedFromDisk()
        if (!MiniAppSanitizer.isValidId(id)) return
        index.delete(id)
        File(rootDir, id).deleteRecursively()
    }

    /** Zips the `<uuid>` folder into a portable `.miniapp` archive at [destination]. */
    @Synchronized
    fun exportBundle(id: String, destination: File): File {
        ensureSyncedFromDisk()
        val app = index.find(id)?.takeIf { it.id == id }
            ?: throw IllegalArgumentException("Mini app not found.")
        val bundleDir = appDir(id)
        require(bundleDir.isDirectory) { "Mini app files are missing." }

        val manifestToWrite = (manifest(id) ?: app.toManifest()).copy(
            iconUri = normalizePortableIconUri(app.iconUri, File(bundleDir, MINI_APP_ICON_FILE_NAME).isFile),
            entryPoint = MiniAppSanitizer.sanitizeEntryPoint(app.entryPoint),
        )
        val manifestBytes = json.encodeToString(MiniAppManifest.serializer(), manifestToWrite).toByteArray(Charsets.UTF_8)

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry(MINI_APP_MANIFEST_FILE_NAME))
                zos.write(manifestBytes)
                zos.closeEntry()

                val baseCanonical = bundleDir.canonicalPath + File.separator
                val files = bundleDir.walkTopDown()
                    .filter { it.isFile && !it.name.endsWith(".tmp") && it.name != MINI_APP_MANIFEST_FILE_NAME }
                    .sortedBy { it.canonicalPath }
                    .toList()
                for (file in files) {
                    val canonical = file.canonicalPath
                    if (!canonical.startsWith(baseCanonical)) continue
                    val entryName = canonical.removePrefix(baseCanonical).replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            baos.toByteArray()
        }
        atomicWriteBytes(destination, zipBytes)
        return destination
    }

    /**
     * Inspects uploaded bytes from either a `.miniapp` (ZIP) bundle or a raw `.html` file,
     * applying Zip Slip and zip-bomb protections before installation.
     */
    fun inspectImport(bytes: ByteArray, suggestedFileName: String? = null): MiniAppImportPreview {
        require(bytes.isNotEmpty()) { "The selected file is empty." }
        require(bytes.size <= MAX_IMPORT_BYTES) {
            "The file is larger than ${MAX_IMPORT_BYTES / 1_000_000} MB."
        }
        val lowerName = suggestedFileName?.lowercase().orEmpty()
        val isZipMagic = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
        return if (isZipMagic || lowerName.endsWith(".miniapp") || lowerName.endsWith(".zip")) {
            inspectZipBundle(bytes, suggestedFileName)
        } else {
            inspectRawHtml(bytes, suggestedFileName)
        }
    }

    @Synchronized
    fun installImport(
        preview: MiniAppImportPreview,
        overrideName: String? = null,
        overrideThemeColor: String? = null,
        iconUpdate: MiniAppIconUpdate? = null,
    ): MiniApp = when (preview) {
        is MiniAppImportPreview.Bundle -> {
            val name = MiniAppSanitizer.sanitizeName(overrideName ?: preview.suggestedName, fallback = "Imported App")
            val themeColor = MiniAppSanitizer.sanitizeThemeColor(overrideThemeColor ?: preview.themeColor)
            val entryBytes = preview.files[preview.entryPoint]
                ?: throw IllegalArgumentException("The mini app archive is missing ${preview.entryPoint}.")
            val sanitizedHtml = MiniAppSanitizer.prepareHtmlForBundle(entryBytes.toString(Charsets.UTF_8), name)

            val resolvedIconUpdate: MiniAppIconUpdate = iconUpdate ?: when {
                preview.files[MINI_APP_ICON_FILE_NAME] != null ->
                    MiniAppIconUpdate.PngBytes(preview.files.getValue(MINI_APP_ICON_FILE_NAME), preview.iconSvg)
                parseEmojiFromIconUri(preview.iconUri) != null ->
                    MiniAppIconUpdate.Emoji(parseEmojiFromIconUri(preview.iconUri)!!)
                parsePresetFromIconUri(preview.iconUri) != null ->
                    MiniAppIconUpdate.Preset(parsePresetFromIconUri(preview.iconUri)!!.id)
                else -> MiniAppIconUpdate.Clear
            }
            save(
                name = name,
                html = sanitizedHtml,
                description = preview.description.ifBlank { "Imported mini app" },
                themeColor = themeColor,
                entryPoint = preview.entryPoint,
                iconUpdate = resolvedIconUpdate,
                imported = true,
                allowNetwork = false,
                extraAssets = preview.files,
            )
        }
        is MiniAppImportPreview.RawHtml -> {
            val name = MiniAppSanitizer.sanitizeName(overrideName ?: preview.suggestedName, fallback = "Imported App")
            val themeColor = MiniAppSanitizer.sanitizeThemeColor(overrideThemeColor)
            val sanitizedHtml = MiniAppSanitizer.prepareHtmlForBundle(preview.html, name)
            save(
                name = name,
                html = sanitizedHtml,
                description = "Imported mini app",
                themeColor = themeColor,
                iconUpdate = iconUpdate ?: MiniAppIconUpdate.Clear,
                imported = true,
                allowNetwork = false,
            )
        }
    }

    private fun inspectZipBundle(bytes: ByteArray, suggestedFileName: String?): MiniAppImportPreview.Bundle {
        val rawEntries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    entryCount++
                    require(entryCount <= MAX_BUNDLE_ZIP_ENTRIES) {
                        "This .miniapp archive contains too many files (max $MAX_BUNDLE_ZIP_ENTRIES)."
                    }
                    val normalized = validateZipEntryPath(entry.name)
                    val remaining = MAX_IMPORT_BYTES - totalBytes
                    val data = zis.readBytesLimited(remaining + 1)
                    totalBytes += data.size
                    require(totalBytes <= MAX_IMPORT_BYTES) {
                        "This .miniapp archive exceeds the maximum uncompressed size."
                    }
                    rawEntries[normalized] = data
                } finally {
                    zis.closeEntry()
                }
            }
        }
        require(rawEntries.isNotEmpty()) { "This .miniapp archive is empty." }
        val entries = stripCommonRootFolder(rawEntries)

        val parsedManifest = entries[MINI_APP_MANIFEST_FILE_NAME]?.let { manifestBytes ->
            runCatching {
                json.decodeFromString(MiniAppManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
            }.getOrNull()
        }

        val candidateEntry = parsedManifest?.entryPoint?.let(MiniAppSanitizer::sanitizeEntryPoint)
            ?: MINI_APP_DEFAULT_ENTRY_POINT
        val entryPoint = when {
            entries.containsKey(candidateEntry) -> candidateEntry
            entries.containsKey(MINI_APP_DEFAULT_ENTRY_POINT) -> MINI_APP_DEFAULT_ENTRY_POINT
            else -> entries.keys.firstOrNull { it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true) }
                ?: throw IllegalArgumentException("This .miniapp archive does not contain an index.html file.")
        }

        val htmlText = entries.getValue(entryPoint).toString(Charsets.UTF_8)
        require(htmlText.isNotBlank() && MiniAppSanitizer.looksLikeHtml(htmlText)) {
            "The $entryPoint file inside this archive is not valid HTML."
        }

        val fallbackName = cleanFileNameStem(suggestedFileName) ?: MiniAppChecks.title(htmlText) ?: "Imported App"
        val suggestedName = MiniAppSanitizer.sanitizeName(parsedManifest?.name ?: MiniAppChecks.title(htmlText), fallbackName)
        val themeColor = MiniAppSanitizer.sanitizeThemeColor(parsedManifest?.themeColor)
        val sanitizedSvg = MiniAppSanitizer.sanitizeSvg(parsedManifest?.iconSvg)

        return MiniAppImportPreview.Bundle(
            suggestedName = suggestedName,
            description = parsedManifest?.description?.take(200).orEmpty(),
            themeColor = themeColor,
            entryPoint = entryPoint,
            iconUri = parsedManifest?.iconUri,
            iconSvg = sanitizedSvg,
            files = entries,
        )
    }

    private fun inspectRawHtml(bytes: ByteArray, suggestedFileName: String?): MiniAppImportPreview.RawHtml {
        // Reject obvious binary files containing NUL bytes.
        require(bytes.none { it == 0.toByte() }) {
            "This file is not a supported .miniapp bundle or HTML file."
        }
        val html = bytes.toString(Charsets.UTF_8).trim()
        require(html.isNotEmpty() && MiniAppSanitizer.looksLikeHtml(html)) {
            "This file does not look like a valid HTML mini app."
        }
        val suggested = MiniAppSanitizer.sanitizeName(
            MiniAppChecks.title(html) ?: cleanFileNameStem(suggestedFileName),
            fallback = "My Mini App",
        )
        return MiniAppImportPreview.RawHtml(suggestedName = suggested, html = html)
    }

    private fun validateZipEntryPath(rawName: String): String {
        val cleaned = rawName.trim().replace('\\', '/')
        require(cleaned.isNotEmpty() && !cleaned.startsWith("/") && !cleaned.contains(':')) {
            "The archive contains an unsafe file path: $rawName"
        }
        val segments = cleaned.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) {
            "The archive contains an unsafe file path: $rawName"
        }
        return segments.joinToString("/")
    }

    private fun stripCommonRootFolder(entries: Map<String, ByteArray>): Map<String, ByteArray> {
        if (entries.containsKey(MINI_APP_DEFAULT_ENTRY_POINT) || entries.containsKey(MINI_APP_MANIFEST_FILE_NAME)) {
            return entries
        }
        val prefixes = entries.keys.map { it.substringBefore('/', "") }.toSet()
        if (prefixes.size == 1 && prefixes.first().isNotEmpty() && entries.keys.all { it.contains('/') }) {
            val prefix = prefixes.first() + "/"
            return entries.mapKeys { (k, _) -> k.removePrefix(prefix) }
        }
        return entries
    }

    private fun cleanFileNameStem(fileName: String?): String? {
        val raw = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val stem = raw
            .removeSuffix(".miniapp")
            .removeSuffix(".html")
            .removeSuffix(".htm")
            .removeSuffix(".zip")
            .replace(Regex("[_-]+"), " ")
            .trim()
        return stem.takeIf { it.isNotEmpty() }
    }

    private fun applyIconUpdate(
        bundleDir: File,
        currentIconUri: String?,
        currentIconSvg: String?,
        update: MiniAppIconUpdate,
    ): Pair<String?, String?> {
        val iconFile = File(bundleDir, MINI_APP_ICON_FILE_NAME)
        return when (update) {
            MiniAppIconUpdate.Keep -> {
                val uri = if (currentIconUri == ICON_URI_FILE && !iconFile.isFile) null else currentIconUri
                uri to currentIconSvg
            }
            is MiniAppIconUpdate.PngBytes -> {
                atomicWriteBytes(iconFile, update.bytes)
                ICON_URI_FILE to MiniAppSanitizer.sanitizeSvg(update.iconSvg)
            }
            is MiniAppIconUpdate.Emoji -> {
                iconFile.delete()
                emojiIconUri(update.emoji) to null
            }
            is MiniAppIconUpdate.Preset -> {
                iconFile.delete()
                presetIconUri(update.presetId) to null
            }
            MiniAppIconUpdate.Clear -> {
                iconFile.delete()
                null to null
            }
        }
    }

    private fun normalizePortableIconUri(iconUri: String?, hasIconFile: Boolean): String? = when {
        iconUri == ICON_URI_FILE && hasIconFile -> ICON_URI_FILE
        iconUri?.startsWith(ICON_URI_EMOJI_PREFIX) == true -> iconUri
        iconUri?.startsWith(ICON_URI_PRESET_PREFIX) == true -> iconUri
        hasIconFile -> ICON_URI_FILE
        else -> null
    }

    private fun writeManifest(bundleDir: File, manifest: MiniAppManifest) {
        val encoded = json.encodeToString(MiniAppManifest.serializer(), manifest)
        atomicWriteBytes(File(bundleDir, MINI_APP_MANIFEST_FILE_NAME), encoded.toByteArray(Charsets.UTF_8))
    }

    private fun resolveInsideDir(dir: File, relativePath: String): File? {
        val base = dir.canonicalFile
        val candidate = File(base, relativePath).canonicalFile
        val basePath = base.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        return candidate.takeIf { it.path.startsWith(basePath) }
    }

    private fun ensureSyncedFromDisk() {
        if (syncedFromDisk) return
        syncedFromDisk = true
        if (index.list().isNotEmpty() || !rootDir.isDirectory) return
        val discovered = rootDir.listFiles()
            ?.filter { it.isDirectory && MiniAppSanitizer.isValidId(it.name) }
            ?.mapNotNull { dir ->
                val manifestFile = File(dir, MINI_APP_MANIFEST_FILE_NAME)
                if (!manifestFile.isFile) return@mapNotNull null
                runCatching {
                    val m = json.decodeFromString(MiniAppManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
                    val safeEntry = MiniAppSanitizer.sanitizeEntryPoint(m.entryPoint)
                    if (!File(dir, safeEntry).isFile) return@runCatching null
                    m.copy(
                        id = dir.name,
                        entryPoint = safeEntry,
                        themeColor = MiniAppSanitizer.sanitizeThemeColor(m.themeColor),
                    ).toMiniApp()
                }.getOrNull()
            }
            .orEmpty()
        if (discovered.isNotEmpty()) index.replaceAll(discovered)
    }
}

private object MiniAppRepositoryHolder {
    @Volatile
    private var instance: MiniAppRepository? = null

    fun get(context: Context): MiniAppRepository {
        val appContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: MiniAppRepository(
                rootDir = File(appContext.filesDir, MINI_APP_DIR_NAME),
                index = MiniAppDatabase(appContext),
            ).also { instance = it }
        }
    }
}

fun miniAppRepository(context: Context): MiniAppRepository = MiniAppRepositoryHolder.get(context)

/** Writes a temporary file, fsyncs, and moves over [file] atomically (works on Android and Windows). */
internal fun atomicWriteBytes(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
    try {
        temp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw IOException("Android could not save ${file.name}.", e)
        }
    } finally {
        temp.delete()
    }
}
