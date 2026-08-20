package com.example.skinscript.installer

import android.content.Context
import android.util.Log
import com.example.skinscript.data.InstallProgress
import com.example.skinscript.data.InstallSummary
import com.example.skinscript.data.OverwriteMode
import com.example.skinscript.data.SkinPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

enum class OverwriteDecision {
    OVERWRITE,
    SKIP,
    OVERWRITE_ALL,
    SKIP_ALL
}

class SkinInstaller(
    private val context: Context,
    private val backend: FileOperationBackend
) {

    companion object {
        private const val TAG = "SkinInstaller"
    }

    suspend fun install(
        skinPackage: SkinPackage,
        destinationBasePath: String,
        overwriteMode: OverwriteMode,
        onProgress: (InstallProgress) -> Unit = {},
        onPromptOverwrite: (suspend (fileName: String, targetPath: String) -> OverwriteDecision)? = null
    ): InstallSummary = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        var successCount = 0
        var skippedCount = 0
        var failedCount = 0

        val normalizedDestination = File(destinationBasePath).path.replace('\\', '/')
        val destPrefix = if (normalizedDestination.endsWith("/")) normalizedDestination else "$normalizedDestination/"
        val totalAssets = skinPackage.totalFiles

        if (totalAssets == 0) {
            return@withContext InstallSummary(
                total = 0,
                success = 0,
                skipped = 0,
                failed = 0,
                errors = listOf("No valid assets found in the ZIP package"),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val destCreated = backend.createDirectory(normalizedDestination)
        if (!destCreated) {
            Log.w(TAG, "Could not ensure root destination directory: $normalizedDestination")
        }

        val assetMap = skinPackage.allAssetFiles.associateBy { it.entryPath }

        var batchDecision: OverwriteDecision? = when (overwriteMode) {
            OverwriteMode.ALWAYS_OVERWRITE -> OverwriteDecision.OVERWRITE_ALL
            OverwriteMode.NEVER_OVERWRITE -> OverwriteDecision.SKIP_ALL
            OverwriteMode.ASK_EVERY_TIME -> null
        }

        try {
            val inputStream = context.contentResolver.openInputStream(skinPackage.sourceUri)
                ?: throw IllegalStateException("Cannot open input stream for ${skinPackage.sourceUri}")

            var processedCount = 0

            BufferedInputStream(inputStream).use { bis ->
                ZipInputStream(bis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        val assetInfo = assetMap[entryName]

                        if (assetInfo != null && !entry.isDirectory) {
                            processedCount++
                            val fileName = assetInfo.name
                            val relativePath = assetInfo.relativeAssetPath

                            onProgress(
                                InstallProgress(
                                    currentFileName = fileName,
                                    completedCount = processedCount - 1,
                                    totalCount = totalAssets,
                                    isIndeterminate = false
                                )
                            )

                            val targetPath = "$normalizedDestination/$relativePath".replace(Regex("/+"), "/")
                            val normalizedTargetPath = File(targetPath).path.replace('\\', '/')

                            if (!normalizedTargetPath.startsWith(destPrefix)) {
                                val error = "Security check failed: Path traversal detected for $entryName"
                                Log.e(TAG, error)
                                errors.add(error)
                                failedCount++
                                zis.closeEntry()
                                entry = zis.nextEntry
                                continue
                            }

                            val exists = backend.exists(normalizedTargetPath)
                            var shouldWrite = true

                            if (exists) {
                                when (batchDecision) {
                                    OverwriteDecision.OVERWRITE_ALL -> shouldWrite = true
                                    OverwriteDecision.SKIP_ALL -> shouldWrite = false
                                    else -> {
                                        val decision = onPromptOverwrite?.invoke(fileName, normalizedTargetPath)
                                            ?: OverwriteDecision.SKIP

                                        when (decision) {
                                            OverwriteDecision.OVERWRITE_ALL -> {
                                                batchDecision = OverwriteDecision.OVERWRITE_ALL
                                                shouldWrite = true
                                            }
                                            OverwriteDecision.SKIP_ALL -> {
                                                batchDecision = OverwriteDecision.SKIP_ALL
                                                shouldWrite = false
                                            }
                                            OverwriteDecision.OVERWRITE -> shouldWrite = true
                                            OverwriteDecision.SKIP -> shouldWrite = false
                                        }
                                    }
                                }
                            }

                            if (shouldWrite) {
                                val safeEntryStream = object : InputStream() {
                                    override fun read(): Int = zis.read()
                                    override fun read(b: ByteArray, off: Int, len: Int): Int = zis.read(b, off, len)
                                    override fun close() { /* Prevent closing ZipInputStream */ }
                                }

                                val copied = backend.copyFile(safeEntryStream, normalizedTargetPath)
                                if (copied) {
                                    successCount++
                                } else {
                                    failedCount++
                                    errors.add("Failed to write: $relativePath")
                                }
                            } else {
                                skippedCount++
                            }

                            onProgress(
                                InstallProgress(
                                    currentFileName = fileName,
                                    completedCount = processedCount,
                                    totalCount = totalAssets,
                                    isIndeterminate = false
                                )
                            )
                        }

                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Installation failed with exception", e)
            errors.add("Installation error: ${e.localizedMessage ?: "Unknown error"}")
        }

        InstallSummary(
            total = totalAssets,
            success = successCount,
            skipped = skippedCount,
            failed = failedCount + (totalAssets - (successCount + skippedCount + failedCount)).coerceAtLeast(0),
            errors = errors,
            durationMs = System.currentTimeMillis() - startTime
        )
    }
}