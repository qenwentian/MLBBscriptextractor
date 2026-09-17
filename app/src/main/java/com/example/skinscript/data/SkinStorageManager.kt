package com.example.skinscript.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

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
        if (!primaryDir.exists()) {
            val created = primaryDir.mkdirs()
            if (created) {
                Log.d(TAG, "Created primary skinzips directory: ${primaryDir.absolutePath}")
            }
        }

        if (primaryDir.exists() && primaryDir.canWrite()) {
            return primaryDir
        }

        // Fallback to app external files directory if public Downloads is not writable
        val fallbackDir = File(context.getExternalFilesDir(null), FOLDER_NAME)
        if (!fallbackDir.exists()) {
            fallbackDir.mkdirs()
        }
        return fallbackDir
    }

    fun listSkinZips(): List<File> {
        val dir = getSkinZipsDirectory()
        val files = dir.listFiles { file ->
            file.isFile && file.extension.equals("zip", ignoreCase = true)
        } ?: emptyArray()
        return files.sortedByDescending { it.lastModified() }
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
}
