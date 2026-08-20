package com.example.skinscript.data

import android.net.Uri
import java.util.Locale

enum class AssetCategory(val folderName: String, val displayName: String) {
    ART("art", "Art / Visuals"),
    AUDIO("audio", "Audio / Sound FX"),
    UI("ui", "UI / Interface"),
    OTHER("other", "Other Assets")
}

data class ZipEntryInfo(
    val entryPath: String,
    val relativeAssetPath: String,
    val category: AssetCategory,
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean
) {
    val formattedSize: String
        get() = formatByteSize(size)
}

data class SkinPackage(
    val sourceUri: Uri,
    val displayName: String,
    val artFiles: List<ZipEntryInfo> = emptyList(),
    val audioFiles: List<ZipEntryInfo> = emptyList(),
    val uiFiles: List<ZipEntryInfo> = emptyList(),
    val otherFiles: List<ZipEntryInfo> = emptyList()
) {
    val allAssetFiles: List<ZipEntryInfo>
        get() = artFiles + audioFiles + uiFiles

    val totalFiles: Int
        get() = allAssetFiles.size

    val totalSize: Long
        get() = allAssetFiles.sumOf { it.size }

    val formattedTotalSize: String
        get() = formatByteSize(totalSize)

    val hasValidAssets: Boolean
        get() = artFiles.isNotEmpty() || audioFiles.isNotEmpty() || uiFiles.isNotEmpty() || otherFiles.isNotEmpty()
}

enum class OverwriteMode(val title: String, val description: String) {
    ASK_EVERY_TIME("Ask every time", "Prompt before overwriting an existing destination file"),
    ALWAYS_OVERWRITE("Always overwrite", "Automatically replace existing files"),
    NEVER_OVERWRITE("Never overwrite", "Keep existing files and skip any duplicates")
}

data class InstallProgress(
    val currentFileName: String = "",
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val isIndeterminate: Boolean = false
) {
    val fraction: Float
        get() = if (totalCount > 0) completedCount.toFloat() / totalCount.toFloat() else 0f

    val percentage: Int
        get() = (fraction * 100).toInt()
}

data class InstallSummary(
    val total: Int,
    val success: Int,
    val skipped: Int,
    val failed: Int,
    val errors: List<String> = emptyList(),
    val durationMs: Long = 0L
) {
    val isAllSuccessful: Boolean
        get() = failed == 0 && success > 0
}

fun formatByteSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val unitIndex = digitGroups.coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, unitIndex.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
