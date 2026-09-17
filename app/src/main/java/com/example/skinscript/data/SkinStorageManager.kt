package com.example.skinscript.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class SkinStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "SkinStorageManager"
        const val FOLDER_NAME = "skinzips"
    }

    fun getSkinZipsDirectory(): File {
        val primaryDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER_NAME
        )
        try {
            if (!primaryDir.exists()) {
                primaryDir.mkdirs()
            }
            if (primaryDir.exists()) {
                val probe = File(primaryDir, ".probe_${System.currentTimeMillis()}")
                if (probe.createNewFile()) {
                    probe.delete()
                    return primaryDir
                }
            }
        } catch (_: Throwable) {
        }

        // Fallback to app external files directory if public Downloads is not writable
        val fallbackDir = File(context.getExternalFilesDir(null), FOLDER_NAME)
        if (!fallbackDir.exists()) {
            fallbackDir.mkdirs()
        }
        return fallbackDir
    }

    fun listSkinZips(): List<File> {
        val foundFiles = mutableMapOf<String, File>()

        // 1. Scan primary directory
        val primaryDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER_NAME
        )
        if (primaryDir.exists()) {
            primaryDir.listFiles { file ->
                file.isFile && file.extension.equals("zip", ignoreCase = true)
            }?.forEach { f ->
                foundFiles[f.name.lowercase()] = f
            }
        }

        // 2. Scan fallback directory
        val fallbackDir = File(context.getExternalFilesDir(null), FOLDER_NAME)
        if (fallbackDir.exists()) {
            fallbackDir.listFiles { file ->
                file.isFile && file.extension.equals("zip", ignoreCase = true)
            }?.forEach { f ->
                if (!foundFiles.containsKey(f.name.lowercase())) {
                    foundFiles[f.name.lowercase()] = f
                }
            }
        }

        return foundFiles.values.sortedByDescending { it.lastModified() }
    }

    fun importZip(sourceUri: Uri): File? {
        return try {
            val targetDir = getSkinZipsDirectory()
            if (!targetDir.exists()) targetDir.mkdirs()

            val rawName = queryDisplayName(sourceUri)?.let { Uri.decode(it) } ?: "imported_skin_${System.currentTimeMillis()}.zip"
            val sanitizedName = rawName.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
            val fileName = if (sanitizedName.endsWith(".zip", ignoreCase = true)) sanitizedName else "$sanitizedName.zip"

            val targetFile = File(targetDir, fileName)
            val inputStream: InputStream = context.contentResolver.openInputStream(sourceUri)
                ?: return null

            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Successfully imported ZIP into skinzips: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import ZIP from $sourceUri", e)
            null
        }
    }

    fun importZips(uris: List<Uri>): List<File> {
        val imported = mutableListOf<File>()
        for (uri in uris) {
            val file = importZip(uri)
            if (file != null) {
                imported.add(file)
            }
        }
        return imported
    }

    fun deleteZip(file: File): Boolean {
        return try {
            val deleted = file.delete()
            Log.d(TAG, "Deleted zip ${file.name}: $deleted")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting zip ${file.name}", e)
            false
        }
    }

    fun getFileUri(file: File): Uri {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Uri.fromFile(file)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) cursor.getString(index) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }
}
