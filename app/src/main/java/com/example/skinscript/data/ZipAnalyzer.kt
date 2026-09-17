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

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ZipAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "ZipAnalyzer"
        private const val BUFFER_SIZE = 128 * 1024
    }

    suspend fun analyze(uri: Uri): Result<SkinPackage> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri) ?: "skin_package.zip"

            // 1. Fast-path: try random-access ZipFile via ParcelFileDescriptor or file path
            val fastResult = tryAnalyzeWithZipFile(uri, displayName)
            if (fastResult != null) {
                return@runCatching fastResult
            }

            // 2. Fallback: streaming ZipInputStream with 128KB buffer
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open stream for URI: $uri")

            val artList = mutableListOf<ZipEntryInfo>()
            val audioList = mutableListOf<ZipEntryInfo>()
            val uiList = mutableListOf<ZipEntryInfo>()
            var unwrappedTempFile: File? = null

            BufferedInputStream(inputStream, BUFFER_SIZE).use { bis ->
                ZipInputStream(bis).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (isValidZipPath(entryName)) {
                            val normalizedPath = normalizePath(entryName)
                            val isDirectory = entry.isDirectory || normalizedPath.endsWith("/")

                            if (!isDirectory) {
                                if (entryName.endsWith(".zip", ignoreCase = true) && unwrappedTempFile == null) {
                                    unwrappedTempFile = unwrapNestedZipFromStream(zis, entryName)
                                }
                                val entryInfo = categorizeEntry(entry, normalizedPath)
                                if (entryInfo != null) {
                                    when (entryInfo.category) {
                                        AssetCategory.ART -> artList.add(entryInfo)
                                        AssetCategory.AUDIO -> audioList.add(entryInfo)
                                        AssetCategory.UI -> uiList.add(entryInfo)
                                        AssetCategory.OTHER -> Unit
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

            if (artList.isEmpty() && audioList.isEmpty() && uiList.isEmpty() && unwrappedTempFile != null) {
                try {
                    val innerZip = ZipFile(unwrappedTempFile)
                    val innerPackage = parseZipFileEntries(innerZip, uri, displayName)
                    if (innerPackage.hasValidAssets) {
                        return@runCatching innerPackage.copy(unwrappedFile = unwrappedTempFile)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse streaming unwrapped zip: ${e.message}")
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

    private fun tryAnalyzeWithZipFile(uri: Uri, displayName: String): SkinPackage? {
        return try {
            if (uri.scheme == "file" && uri.path != null) {
                val file = File(uri.path!!)
                if (file.exists() && file.canRead()) {
                    return parseZipFileEntries(ZipFile(file), uri, displayName)
                }
            }

            val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pfd.use {
                    val procFile = File("/proc/self/fd/${pfd.fd}")
                    val zipFile = ZipFile(procFile)
                    parseZipFileEntries(zipFile, uri, displayName)
                }
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.d(TAG, "ZipFile fast-path unavailable, falling back to ZipInputStream: ${e.message}")
            null
        }
    }

    private fun parseZipFileEntries(zipFile: ZipFile, uri: Uri, displayName: String): SkinPackage {
        val nestedZipEntries = mutableListOf<String>()
        val artList = mutableListOf<ZipEntryInfo>()
        val audioList = mutableListOf<ZipEntryInfo>()
        val uiList = mutableListOf<ZipEntryInfo>()

        zipFile.use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name
                if (isValidZipPath(entryName)) {
                    val normalizedPath = normalizePath(entryName)
                    val isDirectory = entry.isDirectory || normalizedPath.endsWith("/")

                    if (!isDirectory) {
                        if (entryName.endsWith(".zip", ignoreCase = true)) {
                            nestedZipEntries.add(entryName)
                        }
                        val entryInfo = categorizeEntry(entry, normalizedPath)
                        if (entryInfo != null) {
                            when (entryInfo.category) {
                                AssetCategory.ART -> artList.add(entryInfo)
                                AssetCategory.AUDIO -> audioList.add(entryInfo)
                                AssetCategory.UI -> uiList.add(entryInfo)
                                AssetCategory.OTHER -> Unit
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Rejected suspicious ZIP path: $entryName")
                }
            }

            // If no valid assets were detected, check if archive contains an inner ZIP
            if (artList.isEmpty() && audioList.isEmpty() && uiList.isEmpty() && nestedZipEntries.isNotEmpty()) {
                for (nestedName in nestedZipEntries) {
                    val unwrapped = unwrapNestedZip(zf, nestedName)
                    if (unwrapped != null) {
                        try {
                            val innerZip = ZipFile(unwrapped)
                            val innerPackage = parseZipFileEntries(innerZip, uri, displayName)
                            if (innerPackage.hasValidAssets) {
                                return innerPackage.copy(unwrappedFile = unwrapped)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse unwrapped nested zip ($nestedName): ${e.message}")
                        }
                    }
                }
            }

            return SkinPackage(
                sourceUri = uri,
                displayName = displayName,
                artFiles = artList,
                audioFiles = audioList,
                uiFiles = uiList,
                otherFiles = emptyList()
            )
        }
    }

    private fun unwrapNestedZip(zipFile: ZipFile, entryName: String): File? {
        return try {
            val entry = zipFile.getEntry(entryName) ?: return null
            val unwrapDir = File(context.cacheDir, "unwrapped_skins")
            if (!unwrapDir.exists()) unwrapDir.mkdirs()
            val tempFile = File(unwrapDir, File(entryName).name)
            zipFile.getInputStream(entry).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unwrap nested zip: $entryName", e)
            null
        }
    }

    private fun unwrapNestedZipFromStream(zis: ZipInputStream, entryName: String): File? {
        return try {
            val unwrapDir = File(context.cacheDir, "unwrapped_skins")
            if (!unwrapDir.exists()) unwrapDir.mkdirs()
            val tempFile = File(unwrapDir, File(entryName).name)
            FileOutputStream(tempFile).use { output ->
                zis.copyTo(output)
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unwrap nested zip from stream: $entryName", e)
            null
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