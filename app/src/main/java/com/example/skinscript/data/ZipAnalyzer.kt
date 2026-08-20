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
        private const val ASSETS_SEGMENT = "assets/"
    }

    suspend fun analyze(uri: Uri): Result<SkinPackage> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri) ?: "skin_package.zip"
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open stream for URI: $uri")

            val artList = mutableListOf<ZipEntryInfo>()
            val audioList = mutableListOf<ZipEntryInfo>()
            val uiList = mutableListOf<ZipEntryInfo>()
            val otherList = mutableListOf<ZipEntryInfo>()

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
                                        AssetCategory.OTHER -> otherList.add(entryInfo)
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
                otherFiles = otherList
            )
        }
    }

    private fun categorizeEntry(entry: ZipEntry, normalizedPath: String): ZipEntryInfo? {
        val filename = normalizedPath.substringAfterLast('/')

        // 1. Check if the entry is nested under an assets/ directory (case-insensitive)
        val assetsMatch = Regex("^assets/", RegexOption.IGNORE_CASE).find(normalizedPath)
        val pathWithoutAssets = if (assetsMatch != null) {
            normalizedPath.substring(assetsMatch.range.last + 1).trimStart('/')
        } else {
            normalizedPath
        }

        // 2. Identify category case-insensitively
        val category = when {
            pathWithoutAssets.startsWith("art/", ignoreCase = true) || pathWithoutAssets.equals("art", ignoreCase = true) -> AssetCategory.ART
            pathWithoutAssets.startsWith("audio/", ignoreCase = true) || pathWithoutAssets.equals("audio", ignoreCase = true) -> AssetCategory.AUDIO
            pathWithoutAssets.startsWith("ui/", ignoreCase = true) || pathWithoutAssets.equals("ui", ignoreCase = true) -> AssetCategory.UI
            assetsMatch != null -> AssetCategory.OTHER
            else -> null
        } ?: return null

        // 3. Reconstruct the relative path using the exact in-game folder casing (Art, Audio, UI)
        val canonicalRelativePath = when (category) {
            AssetCategory.ART -> {
                val subPath = pathWithoutAssets.substringAfter('/', "").ifEmpty { filename }
                if (subPath == filename && !pathWithoutAssets.contains('/')) "Art/$filename" else "Art/$subPath"
            }
            AssetCategory.AUDIO -> {
                val subPath = pathWithoutAssets.substringAfter('/', "").ifEmpty { filename }
                if (subPath == filename && !pathWithoutAssets.contains('/')) "Audio/$filename" else "Audio/$subPath"
            }
            AssetCategory.UI -> {
                val subPath = pathWithoutAssets.substringAfter('/', "").ifEmpty { filename }
                if (subPath == filename && !pathWithoutAssets.contains('/')) "UI/$filename" else "UI/$subPath"
            }
            AssetCategory.OTHER -> pathWithoutAssets
        }

        return ZipEntryInfo(
            entryPath = entry.name,
            relativeAssetPath = canonicalRelativePath,
            category = category,
            name = filename,
            size = if (entry.size >= 0) entry.size else 0L,
            compressedSize = if (entry.compressedSize >= 0) entry.compressedSize else 0L,
            isDirectory = entry.isDirectory
        )
    }

    /**
     * Zip Slip and Path Traversal Protection
     * Validates that the entry path does not contain directory traversal sequences
     * or absolute filesystem paths.
     */
    fun isValidZipPath(rawPath: String): Boolean {
        if (rawPath.isBlank()) return false
        if (rawPath.contains('\u0000')) return false

        val normalized = rawPath.replace('\\', '/')
        val segments = normalized.split('/')

        for (segment in segments) {
            if (segment == "..") {
                return false
            }
        }

        // Reject absolute paths
        if (normalized.startsWith("/") || (rawPath.length > 1 && rawPath[1] == ':')) {
            return false
        }

        return true
    }

    fun normalizePath(path: String): String {
        return path.replace('\\', '/')
            .trimStart('/')
            .replace(Regex("/+"), "/")
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
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