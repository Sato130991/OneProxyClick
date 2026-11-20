package com.oneproxyclick.core.storage.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/**
 * Writes export files into the public `Download/OneProxyClick_Exports/` folder.
 *
 * Uses MediaStore.Downloads (API 29+): the app may create and append to its own files in
 * Download without any storage permission on Android 10–15, so neither WRITE_EXTERNAL_STORAGE
 * nor MANAGE_EXTERNAL_STORAGE is needed. Not thread-safe for the same file; callers serialise.
 */
class ExportWriter(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val appendTargets = HashMap<String, Uri>()

    class Saved(val uri: Uri, val fileName: String) {
        /** Human readable absolute path, e.g. /storage/emulated/0/Download/OneProxyClick_Exports/x.json */
        val displayPath: String get() = "$PUBLIC_DIR/$fileName"
    }

    /** Creates a new file (MediaStore de-duplicates the name if it already exists). */
    fun writeNew(fileName: String, mimeType: String, writeBody: (OutputStream) -> Unit): Saved {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused to create $fileName")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> writeBody(out) }
                ?: throw IOException("Cannot open $uri")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Saved(uri, actualName(uri) ?: fileName)
    }

    /**
     * Writes [fileName] in place (truncating an existing copy owned by this app) instead of creating
     * "name (1).ext" duplicates. Used for fixed names such as `last_sent.json` and `opc-cli.sh`.
     */
    fun writeOrReplace(fileName: String, mimeType: String, writeBody: (OutputStream) -> Unit): Saved {
        val existing = find(fileName) ?: return writeNew(fileName, mimeType, writeBody)
        resolver.openOutputStream(existing, "wt")?.use { out -> writeBody(out) }
            ?: throw IOException("Cannot open $existing")
        return Saved(existing, actualName(existing) ?: fileName)
    }

    /**
     * Opens [fileName] for appending and keeps it open. The returned stream is an unbuffered
     * FileOutputStream on the MediaStore file descriptor, so every write() reaches the file
     * immediately (`tail -f` sees each line as soon as it is written).
     */
    fun openAppendStream(fileName: String): OutputStream =
        resolver.openOutputStream(findOrCreate(fileName), "wa") ?: throw IOException("Cannot open $fileName")

    /** Appends one line (JSON Lines) to [fileName], creating it on first use. */
    fun appendLine(fileName: String, line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val uri = appendTargets[fileName] ?: findOrCreate(fileName).also { appendTargets[fileName] = it }
        try {
            write(uri, bytes)
        } catch (e: Exception) {
            // The user may have deleted/moved the file: recreate it once.
            appendTargets.remove(fileName)
            val fresh = findOrCreate(fileName).also { appendTargets[fileName] = it }
            write(fresh, bytes)
        }
    }

    private fun write(uri: Uri, bytes: ByteArray) {
        resolver.openOutputStream(uri, "wa")?.use { it.write(bytes) } ?: throw IOException("Cannot open $uri")
    }

    private fun find(fileName: String): Uri? = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
        arrayOf(fileName, "$RELATIVE_PATH%"),
        null,
    )?.use { c ->
        if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null
    }

    private fun findOrCreate(fileName: String): Uri {
        find(fileName)?.let { return it }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            // octet-stream keeps the ".jsonl" name as-is (a text/* type would get ".txt" appended).
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_BINARY)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }
        return resolver.insert(collection, values) ?: throw IOException("MediaStore refused to create $fileName")
    }

    private fun actualName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val FOLDER = "OneProxyClick_Exports"
        val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
        const val MIME_JSON = "application/json"
        /**
         * MediaProvider never rewrites the extension of an `application/octet-stream` file, so
         * names like `x.jsonl` / `opc-cli.sh` are kept verbatim.
         */
        const val MIME_BINARY = "application/octet-stream"

        @Suppress("DEPRECATION")
        val PUBLIC_DIR: String
            get() = "${Environment.getExternalStorageDirectory().absolutePath}/$RELATIVE_PATH"
    }
}
