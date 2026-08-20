package com.example.skinscript.shizuku

import android.util.Log
import com.example.skinscript.installer.FileOperationBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.lang.reflect.Method

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
        val escapedPath = escapeShellPath(path)
        val exitCode = executeCommand("mkdir -p $escapedPath")
        exitCode == 0
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
                    val buffer = ByteArray(32 * 1024)
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