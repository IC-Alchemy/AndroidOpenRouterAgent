package com.example.openrouteragent

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal fun ContentResolver.documentDisplayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }

internal fun ContentResolver.openDocumentInput(uri: Uri) =
    openInputStream(uri) ?: throw IOException("Android could not open the selected file.")

internal fun ContentResolver.openDocumentOutput(uri: Uri) =
    openOutputStream(uri, "wt") ?: throw IOException("Android could not open the destination.")

/** Use only with a new destination returned by CreateDocument. */
internal suspend fun <T> ContentResolver.writeCreatedDocument(uri: Uri, write: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            write()
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                runCatching { DocumentsContract.deleteDocument(this@writeCreatedDocument, uri) }
            }
            throw failure
        }
    }

/** Download into private cache before opening the provider's destination. Call on the I/O dispatcher. */
internal suspend fun ContentResolver.writeDownloadedDocument(
    uri: Uri,
    cacheDir: File,
    download: suspend (() -> OutputStream) -> Unit,
) {
    val staging = File.createTempFile("export-", ".tmp", cacheDir)
    try {
        download { staging.outputStream() }
        staging.inputStream().use { input -> openDocumentOutput(uri).use { input.copyTo(it) } }
    } finally {
        staging.delete()
    }
}
