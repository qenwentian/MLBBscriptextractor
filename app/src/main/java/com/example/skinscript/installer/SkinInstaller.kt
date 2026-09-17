package com.example.skinscript.installer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.skinscript.data.InstallProgress
import com.example.skinscript.data.InstallSummary
import com.example.skinscript.data.OverwriteMode
import com.example.skinscript.data.SkinPackage
import com.example.skinscript.data.ZipEntryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

enum class OverwriteDecision {
    OVERWRITE,
    SKIP,
    OVERWRITE_ALL,
    SKIP_ALL
}

private data class PlannedAsset(
    val info: ZipEntryInfo,
    val relativePath: String,
    val targetPath: String
)

class SkinInstaller(
    private val context: Context,
    private val backend: FileOperationBackend
) {

    companion object {
        private const val TAG = "SkinInstaller"
        private const val STREAM_BUFFER_SIZE = 128 * 1024
    }

    suspend fun installBatch(
        packages: List<SkinPackage>,
        destinationBasePath: String,
        overwriteMode: OverwriteMode,
        onPackageStart: (index: Int, total: Int, pkg: SkinPackage) -> Unit = { _, _, _ -> },
        onProgress: (InstallProgress) -> Unit = {},
        onPromptOverwrite: (suspend (fileName: String, targetPath: String) -> OverwriteDecision)? = null
    ): InstallSummary = withContext(Dispatchers.IO) {
        val overallStartTime = System.currentTimeMillis()
        var totalCount = 0
        var successCount = 0
        var skippedCount = 0
        var failedCount = 0
        val allErrors = mutableListOf<String>()

        val grandTotalFiles = packages.sumOf { it.totalFiles }
        var filesProcessedSoFar = 0

        for ((idx, pkg) in packages.withIndex()) {
            onPackageStart(idx, packages.size, pkg)
            val pkgSummary = install(
                skinPackage = pkg,
                destinationBasePath = destinationBasePath,
                overwriteMode = overwriteMode,
                onProgress = { p ->
                    onProgress(
                        InstallProgress(
                            currentFileName = "[${idx + 1}/${packages.size}] ${p.currentFileName}",
                            completedCount = filesProcessedSoFar + p.completedCount,
                            totalCount = grandTotalFiles,
                            isIndeterminate = p.isIndeterminate
                        )
                    )
                },
                onPromptOverwrite = onPromptOverwrite
            )

            totalCount += pkgSummary.total
            successCount += pkgSummary.success
            skippedCount += pkgSummary.skipped
            failedCount += pkgSummary.failed
            allErrors.addAll(pkgSummary.errors)
            filesProcessedSoFar += pkgSummary.total
        }

        InstallSummary(
            total = totalCount,
            success = successCount,
            skipped = skippedCount,
            failed = failedCount,
            errors = allErrors,
            durationMs = System.currentTimeMillis() - overallStartTime
        )
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

        // 1. Filter out traversal attempts and plan target paths
        val validAssets = mutableListOf<PlannedAsset>()
        for (asset in skinPackage.allAssetFiles) {
            val relativePath = asset.relativeAssetPath
            val rawTargetPath = "$normalizedDestination/$relativePath".replace(Regex("/+"), "/")
            val normalizedTargetPath = File(rawTargetPath).path.replace('\\', '/')

            if (!normalizedTargetPath.startsWith(destPrefix)) {
                val error = "Security check failed: Path traversal detected for ${asset.entryPath}"
                Log.e(TAG, error)
                errors.add(error)
                failedCount++
            } else {
                validAssets.add(PlannedAsset(asset, relativePath, normalizedTargetPath))
            }
        }

        // 2. Batch check existence of target paths if needed
        val candidatePaths = validAssets.map { it.targetPath }
        val existingFilesSet = if (overwriteMode != OverwriteMode.ALWAYS_OVERWRITE && candidatePaths.isNotEmpty()) {
            backend.checkExistingFiles(candidatePaths)
        } else {
            emptySet()
        }

        var batchDecision: OverwriteDecision? = when (overwriteMode) {
            OverwriteMode.ALWAYS_OVERWRITE -> OverwriteDecision.OVERWRITE_ALL
            OverwriteMode.NEVER_OVERWRITE -> OverwriteDecision.SKIP_ALL
            OverwriteMode.ASK_EVERY_TIME -> null
        }

        val assetsToWrite = mutableListOf<PlannedAsset>()

        for (item in validAssets) {
            val exists = existingFilesSet.contains(item.targetPath)
            var shouldWrite = true

            if (exists) {
                when (batchDecision) {
                    OverwriteDecision.OVERWRITE_ALL -> shouldWrite = true
                    OverwriteDecision.SKIP_ALL -> shouldWrite = false
                    else -> {
                        val decision = onPromptOverwrite?.invoke(item.info.name, item.targetPath)
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
                assetsToWrite.add(item)
            } else {
                skippedCount++
            }
        }

        if (assetsToWrite.isEmpty()) {
            return@withContext InstallSummary(
                total = totalAssets,
                success = successCount,
                skipped = skippedCount,
                failed = failedCount,
                errors = errors,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // 3. High-speed extraction via single-process TAR stream pipeline
        val tarInstalled = tryExtractViaTarStream(
            skinPackage = skinPackage,
            assetsToWrite = assetsToWrite,
            destination = normalizedDestination,
            totalAssets = totalAssets,
            initialCompleted = skippedCount,
            onProgress = onProgress,
            errors = errors
        )

        if (tarInstalled != null) {
            successCount += tarInstalled
        } else {
            // 4. Fallback: individual file copying with batch directory creation and directory caching
            Log.w(TAG, "TAR extraction not available or failed, falling back to direct copying")
            val fallbackSuccess = extractViaDirectCopy(
                skinPackage = skinPackage,
                assetsToWrite = assetsToWrite,
                totalAssets = totalAssets,
                initialCompleted = skippedCount,
                onProgress = onProgress,
                errors = errors
            )
            successCount += fallbackSuccess
        }

        val calculatedFailed = totalAssets - (successCount + skippedCount)
        if (calculatedFailed > 0 && failedCount < calculatedFailed) {
            failedCount = calculatedFailed
        }

        InstallSummary(
            total = totalAssets,
            success = successCount,
            skipped = skippedCount,
            failed = failedCount,
            errors = errors,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun tryExtractViaTarStream(
        skinPackage: SkinPackage,
        assetsToWrite: List<PlannedAsset>,
        destination: String,
        totalAssets: Int,
        initialCompleted: Int,
        onProgress: (InstallProgress) -> Unit,
        errors: MutableList<String>
    ): Int? {
        val tarProcess = backend.createTarProcess(destination) ?: return null

        return try {
            val assetMap = assetsToWrite.associateBy { it.info.entryPath }
            var writtenCount = 0

            // Try random-access ZipFile first
            val zipFile = tryOpenZipFile(skinPackage.sourceUri, skinPackage.unwrappedFile)

            if (zipFile != null) {
                zipFile.use { zf ->
                    TarWriter(BufferedOutputStream(tarProcess.outputStream, STREAM_BUFFER_SIZE)).use { tarWriter ->
                        for ((index, item) in assetsToWrite.withIndex()) {
                            onProgress(
                                InstallProgress(
                                    currentFileName = item.info.name,
                                    completedCount = initialCompleted + index,
                                    totalCount = totalAssets,
                                    isIndeterminate = false
                                )
                            )

                            val entry = zf.getEntry(item.info.entryPath)
                            if (entry != null) {
                                zf.getInputStream(entry).use { entryStream ->
                                    tarWriter.writeEntry(
                                        entryPath = item.relativePath,
                                        size = if (entry.size >= 0) entry.size else item.info.size,
                                        inputStream = entryStream
                                    )
                                }
                                writtenCount++
                            } else {
                                Log.w(TAG, "Entry not found in ZipFile: ${item.info.entryPath}")
                            }
                        }
                    }
                }
            } else {
                // Fallback to streaming ZipInputStream into tarWriter
                val inStream = openInputStreamForPackage(skinPackage)

                BufferedInputStream(inStream, STREAM_BUFFER_SIZE).use { bis ->
                    ZipInputStream(bis).use { zis ->
                        TarWriter(BufferedOutputStream(tarProcess.outputStream, STREAM_BUFFER_SIZE)).use { tarWriter ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val item = assetMap[entry.name]
                                if (item != null && !entry.isDirectory) {
                                    onProgress(
                                        InstallProgress(
                                            currentFileName = item.info.name,
                                            completedCount = initialCompleted + writtenCount,
                                            totalCount = totalAssets,
                                            isIndeterminate = false
                                        )
                                    )

                                    val safeStream = object : InputStream() {
                                        override fun read(): Int = zis.read()
                                        override fun read(b: ByteArray, off: Int, len: Int): Int = zis.read(b, off, len)
                                        override fun close() { /* Do not close ZipInputStream */ }
                                    }

                                    tarWriter.writeEntry(
                                        entryPath = item.relativePath,
                                        size = if (entry.size >= 0) entry.size else item.info.size,
                                        inputStream = safeStream
                                    )
                                    writtenCount++
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                }
            }

            val exitCode = tarProcess.waitFor()
            if (exitCode == 0) {
                onProgress(
                    InstallProgress(
                        currentFileName = "Done",
                        completedCount = totalAssets,
                        totalCount = totalAssets,
                        isIndeterminate = false
                    )
                )
                writtenCount
            } else {
                val errorMsg = tarProcess.errorStream.bufferedReader().use { it.readText() }
                Log.e(TAG, "Tar process failed with exit code $exitCode: $errorMsg")
                errors.add("TAR extraction error ($exitCode): $errorMsg")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during TAR extraction", e)
            errors.add("TAR extraction exception: ${e.localizedMessage}")
            try { tarProcess.destroy() } catch (_: Throwable) {}
            null
        }
    }

    private suspend fun extractViaDirectCopy(
        skinPackage: SkinPackage,
        assetsToWrite: List<PlannedAsset>,
        totalAssets: Int,
        initialCompleted: Int,
        onProgress: (InstallProgress) -> Unit,
        errors: MutableList<String>
    ): Int {
        // Upfront batch directory creation
        val parentDirs = assetsToWrite.mapNotNull { File(it.targetPath).parent }.distinct()
        backend.createDirectories(parentDirs)

        var writtenCount = 0
        val assetMap = assetsToWrite.associateBy { it.info.entryPath }

        // Try ZipFile first
        val zipFile = tryOpenZipFile(skinPackage.sourceUri, skinPackage.unwrappedFile)

        if (zipFile != null) {
            zipFile.use { zf ->
                for ((index, item) in assetsToWrite.withIndex()) {
                    onProgress(
                        InstallProgress(
                            currentFileName = item.info.name,
                            completedCount = initialCompleted + index,
                            totalCount = totalAssets,
                            isIndeterminate = false
                        )
                    )

                    val entry = zf.getEntry(item.info.entryPath)
                    if (entry != null) {
                        val copied = zf.getInputStream(entry).use { entryStream ->
                            backend.copyFile(entryStream, item.targetPath)
                        }
                        if (copied) {
                            writtenCount++
                        } else {
                            errors.add("Failed to write: ${item.relativePath}")
                        }
                    }
                }
            }
        } else {
            val inStream = openInputStreamForPackage(skinPackage)

            BufferedInputStream(inStream, STREAM_BUFFER_SIZE).use { bis ->
                ZipInputStream(bis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val item = assetMap[entry.name]
                        if (item != null && !entry.isDirectory) {
                            onProgress(
                                InstallProgress(
                                    currentFileName = item.info.name,
                                    completedCount = initialCompleted + writtenCount,
                                    totalCount = totalAssets,
                                    isIndeterminate = false
                                )
                            )

                            val safeStream = object : InputStream() {
                                override fun read(): Int = zis.read()
                                override fun read(b: ByteArray, off: Int, len: Int): Int = zis.read(b, off, len)
                                override fun close() { /* Do not close ZipInputStream */ }
                            }

                            val copied = backend.copyFile(safeStream, item.targetPath)
                            if (copied) {
                                writtenCount++
                            } else {
                                errors.add("Failed to write: ${item.relativePath}")
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }

        return writtenCount
    }

    private fun openInputStreamForPackage(pkg: SkinPackage): InputStream {
        if (pkg.unwrappedFile != null && pkg.unwrappedFile.exists()) {
            return java.io.FileInputStream(pkg.unwrappedFile)
        }
        val uri = pkg.sourceUri ?: throw IllegalStateException("Cannot open input stream for ${pkg.displayName}: no sourceUri")
        return context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open input stream for $uri")
    }

    private fun tryOpenZipFile(uri: Uri?, unwrappedFile: File? = null): ZipFile? {
        if (unwrappedFile != null && unwrappedFile.exists() && unwrappedFile.canRead()) {
            return try { ZipFile(unwrappedFile) } catch (_: Throwable) { null }
        }
        if (uri == null) return null
        return try {
            if (uri.scheme == "file" && uri.path != null) {
                val file = File(uri.path!!)
                if (file.exists() && file.canRead()) {
                    return ZipFile(file)
                }
            }
            val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val procFile = File("/proc/self/fd/${pfd.fd}")
                ZipFile(procFile)
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.d(TAG, "ZipFile random access unavailable: ${e.message}")
            null
        }
    }
}