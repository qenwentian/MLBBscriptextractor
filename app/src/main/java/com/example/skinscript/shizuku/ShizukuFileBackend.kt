package com.example.skinscript.shizuku

import android.util.Log
import com.example.skinscript.installer.FileOperationBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.lang.reflect.Method

import java.util.concurrent.ConcurrentHashMap

class ShizukuFileBackend : FileOperationBackend {

    companion object {
        private const val TAG = "ShizukuFileBackend"

        private val newProcessMethod: Method? by lazy {
            try {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to get Shizuku.newProcess method", e)
                null
            }
        }

        fun createProcess(commands: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
            val method = newProcessMethod
                ?: throw IllegalStateException("Shizuku newProcess method is not available")
            return method.invoke(null, commands, env, dir) as Process
        }
    }

    private val knownDirectories = ConcurrentHashMap.newKeySet<String>()

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val escapedPath = escapeShellPath(path)
        val exitCode = executeCommand("test -e $escapedPath")
        exitCode == 0
    }

    override suspend fun isDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val escapedPath = escapeShellPath(path)
        val exitCode = executeCommand("test -d $escapedPath")
        exitCode == 0
    }

    override suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = path.replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty() || knownDirectories.contains(normalized)) {
            return@withContext true
        }

        val escapedPath = escapeShellPath(normalized)
        val exitCode = executeCommand("mkdir -p $escapedPath")
        if (exitCode == 0) {
            markDirectoryKnown(normalized)
            true
        } else {
            false
        }
    }

    override suspend fun createDirectories(paths: List<String>): Boolean = withContext(Dispatchers.IO) {
        val toCreate = paths
            .map { it.replace('\\', '/').trimEnd('/') }
            .filter { it.isNotEmpty() && !knownDirectories.contains(it) }
            .distinct()

        if (toCreate.isEmpty()) return@withContext true

        try {
            val process = createProcess(arrayOf("sh", "-c", "while IFS= read -r d; do [ -n \"\$d\" ] && mkdir -p \"\$d\"; done"))
            process.outputStream.bufferedWriter().use { writer ->
                for (dir in toCreate) {
                    writer.write(dir)
                    writer.write("\n")
                }
                writer.flush()
            }
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                toCreate.forEach { markDirectoryKnown(it) }
                true
            } else {
                Log.e(TAG, "Failed to batch create directories, exit code $exitCode")
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during batch createDirectories", e)
            false
        }
    }

    override suspend fun checkExistingFiles(paths: List<String>): Set<String> = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext emptySet()

        try {
            val process = createProcess(
                arrayOf("sh", "-c", "while IFS= read -r f; do [ -e \"\$f\" ] && printf \"%s\\n\" \"\$f\"; done")
            )

            // Write all candidate paths to process stdin in background thread
            val writerThread = Thread {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        for (path in paths) {
                            writer.write(path)
                            writer.write("\n")
                        }
                        writer.flush()
                    }
                } catch (e: Throwable) {
                    Log.d(TAG, "Writer thread finished or closed: ${e.message}")
                }
            }.apply { start() }

            // Read matched paths from process stdout
            val existing = mutableSetOf<String>()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        existing.add(line.trim())
                    }
                }
            }

            writerThread.join()
            process.waitFor()
            existing
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking existing files in batch", e)
            // Fallback to empty if failed
            emptySet()
        }
    }

    override fun createTarProcess(destination: String): Process? {
        return try {
            val escapedDest = escapeShellPath(destination)
            createProcess(arrayOf("sh", "-c", "tar -xf - -C $escapedDest"))
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create TAR process for $destination", e)
            null
        }
    }

    override suspend fun isWritable(path: String): Boolean = withContext(Dispatchers.IO) {
        val parent = File(path).parent ?: path
        val escapedParent = escapeShellPath(parent)
        val exitCode = executeCommand("mkdir -p $escapedParent && test -w $escapedParent")
        exitCode == 0
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val escapedPath = escapeShellPath(path)
        val exitCode = executeCommand("rm -rf $escapedPath")
        exitCode == 0
    }

    override suspend fun copyFile(source: InputStream, destination: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val parentDir = File(destination).parent
                if (parentDir != null) {
                    val createParentRes = createDirectory(parentDir)
                    if (!createParentRes) {
                        Log.e(TAG, "Failed to create parent directory: $parentDir")
                    }
                }

                val escapedDest = escapeShellPath(destination)
                val process = createProcess(arrayOf("sh", "-c", "cat > $escapedDest"))

                process.outputStream.use { outStream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (source.read(buffer).also { bytesRead = it } != -1) {
                        outStream.write(buffer, 0, bytesRead)
                    }
                    outStream.flush()
                }

                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    val errorMsg = process.errorStream.bufferedReader().use { it.readText() }
                    Log.e(TAG, "Failed to copy file to $destination: exit code $exitCode, error: $errorMsg")
                    false
                } else {
                    true
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Exception during copyFile to $destination", e)
                false
            }
        }

    private fun markDirectoryKnown(dir: String) {
        var current: String? = dir
        while (current != null && current.isNotEmpty() && current != "/") {
            knownDirectories.add(current)
            current = File(current).parent?.replace('\\', '/')
        }
    }

    private fun executeCommand(command: String): Int {
        return try {
            val process = createProcess(arrayOf("sh", "-c", command))
            process.waitFor()
        } catch (e: Throwable) {
            Log.e(TAG, "Error executing Shizuku command: $command", e)
            -1
        }
    }

    private fun escapeShellPath(path: String): String {
        return "'" + path.replace("'", "'\\''") + "'"
    }
}