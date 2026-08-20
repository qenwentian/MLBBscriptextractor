package com.example.skinscript.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ZipAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "ZipAnalyzer"
    }

    suspend fun analyze(uri: Uri): Result<SkinPackage> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri) ?: "skin_package.zip"
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open stream for URI: $uri")

            val artList = mutableListOf<ZipEntryInfo>()
            val audioList = mutableListOf<ZipEntryInfo>()
            val uiList = mutableListOf<ZipEntryInfo>()

            BufferedInputStream(inputStream).use { bis ->
                ZipInputStream(bis).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (isValidZipPath(entryName)) {
                            val normalizedPath = normalizePath(entryName)
                            val isDirectory = entry.isDirectory || normalizedPath.endsWith("/")

                            if (!isDirectory) {
                                val entryInfo = categorizeEntry(entry, normalizedPath)
                                if (entryInfo != null) {
                                    when (entryInfo.category) {
                                        AssetCategory.ART -> artList.add(entryInfo)
                                        AssetCategory.AUDIO -> audioList.add(entryInfo)
                                        AssetCategory.UI -> uiList.add(entryInfo)
                                        AssetCategory.OTHER -> Unit // Explicitly ignore non-target assets
                                    }
                                }
                            }
                        } else {
                            Log.w(TAG, "Rejected suspicious ZIP path: $entryName")
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            SkinPackage(
                sourceUri = uri,
                displayName = displayName,
                artFiles = artList,
                audioFiles = audioList,
                uiFiles = uiList,
                otherFiles = emptyList()
            )
        }
    }

    private fun categorizeEntry(entry: ZipEntry, normalizedPath: String): ZipEntryInfo? {
        val filename = normalizedPath.substringAfterLast('/')
        if (filename.isBlank()) return null

        val pathUnderAssets: String
        val lowerPath = normalizedPath.lowercase()

        // 1. Locate the position of "/assets/" or "^assets/"
        val assetsIdx = when {
            lowerPath.contains("/assets/") -> lowerPath.lastIndexOf("/assets/") + "/assets/".length
            lowerPath.startsWith("assets/") -> "assets/".length
            else -> -1
        }

        if (assetsIdx != -1) {
            pathUnderAssets = normalizedPath.substring(assetsIdx).trimStart('/')
        } else {
            // 2. Locate direct "Art/", "Audio/", or "UI/" root folders anywhere in the archive path
            val directIdx = listOf("art/", "audio/", "ui/").map {
                when {
                    lowerPath.contains("/$it") -> lowerPath.lastIndexOf("/$it") + 1
                    lowerPath.startsWith(it) -> 0
                    else -> -1
                }
            }.filter { it != -1 }.maxOrNull() ?: -1

            if (directIdx != -1) {
                pathUnderAssets = normalizedPath.substring(directIdx).trimStart('/')
            } else {
                return null
            }
        }

        if (pathUnderAssets.isBlank()) return null

        val firstFolder = pathUnderAssets.substringBefore('/')
        val remainder = pathUnderAssets.substringAfter('/', "")

        // 3. Keep ONLY Art, Audio, and UI — filter out AstclnPack or any other folder
        val (category, canonicalPath) = when {
            firstFolder.equals("art", ignoreCase = true) -> {
                AssetCategory.ART to if (remainder.isEmpty()) "Art/$filename" else "Art/$remainder"
            }
            firstFolder.equals("audio", ignoreCase = true) -> {
                AssetCategory.AUDIO to if (remainder.isEmpty()) "Audio/$filename" else "Audio/$remainder"
            }
            firstFolder.equals("ui", ignoreCase = true) -> {
                AssetCategory.UI to if (remainder.isEmpty()) "UI/$filename" else "UI/$remainder"
            }
            else -> return null // Discards AstclnPack and other folders
        }

        return ZipEntryInfo(
            entryPath = entry.name,
            relativeAssetPath = canonicalPath,
            category = category,
            name = filename,
            size = if (entry.size >= 0) entry.size else 0L,
            compressedSize = if (entry.compressedSize >= 0) entry.compressedSize else 0L,
            isDirectory = entry.isDirectory
        )
    }

    fun isValidZipPath(rawPath: String): Boolean {
        if (rawPath.isBlank() || rawPath.contains('\u0000')) return false
        val normalized = rawPath.replace('\\', '/')
        val segments = normalized.split('/')
        for (segment in segments) {
            if (segment == "..") return false
        }
        return !(normalized.startsWith("/") || (rawPath.length > 1 && rawPath[1] == ':'))
    }

    fun normalizePath(path: String): String {
        return path.replace('\\', '/')
            .trimStart('/')
            .replace(Regex("/+"), "/")
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