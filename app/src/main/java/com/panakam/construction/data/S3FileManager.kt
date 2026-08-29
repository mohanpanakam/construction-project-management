package com.panakam.construction.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles uploading device files directly to S3 via presigned PUT URLs.
 */
object S3FileManager {

    /** Returns (displayName, mimeType) for a content URI. */
    fun getFileInfo(context: Context, uri: Uri): Pair<String, String> {
        var name = "file_${System.currentTimeMillis()}"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = cursor.getString(idx) ?: name
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return Pair(name, mime)
    }

    /**
     * Uploads a file URI to a presigned S3 PUT URL.
     * Calls [onProgress] with 0-100 percentage during the upload.
     * Must be called from a coroutine-aware scope (runs IO work internally).
     */
    fun uploadToPresignedUrl(
        context: Context,
        uri: Uri,
        uploadUrl: String,
        contentType: String,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Read all bytes (supports content:// URIs from any provider)
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val totalBytes = bytes.size

                val conn = URL(uploadUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(totalBytes.toLong())
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout    = 120_000

                BufferedOutputStream(conn.outputStream).use { out ->
                    val chunkSize = 8192
                    var offset = 0
                    while (offset < totalBytes) {
                        val end = minOf(offset + chunkSize, totalBytes)
                        out.write(bytes, offset, end - offset)
                        offset = end
                        val pct = (offset * 100L / totalBytes).toInt()
                        withContext(Dispatchers.Main) { onProgress(pct) }
                    }
                }

                val code = conn.responseCode
                conn.disconnect()

                if (code in 200..299) {
                    withContext(Dispatchers.Main) { onSuccess() }
                } else {
                    withContext(Dispatchers.Main) {
                        onFailure(Exception("Upload failed: HTTP $code"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onFailure(e) }
            }
        }
    }
}

