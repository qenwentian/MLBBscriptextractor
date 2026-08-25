package com.example.skinscript.installer

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Lightweight, zero-dependency streaming TAR archive writer conforming to POSIX USTAR format.
 * Enables piping extracted assets directly to `tar -xf - -C <dest>` in a single process.
 */
class TarWriter(private val outputStream: OutputStream) : AutoCloseable {

    companion object {
        const val BLOCK_SIZE = 512
        private const val USTAR_MAGIC = "ustar\u0000"
        private const val USTAR_VERSION = "00"
    }

    private val zeroBlock = ByteArray(BLOCK_SIZE)
    private var isClosed = false

    /**
     * Writes a file entry to the TAR archive from an [InputStream].
     *
     * @param entryPath Relative path of the file (e.g. "Art/Character/hero.unity3d").
     * @param size Size in bytes of the file.
     * @param inputStream Stream containing the raw file contents.
     * @param mtime Modification time in seconds (defaults to current time).
     * @param mode File permission mode (default 0644).
     */
    fun writeEntry(
        entryPath: String,
        size: Long,
        inputStream: InputStream,
        mtime: Long = System.currentTimeMillis() / 1000,
        mode: Int = 0b110100100 // 0644
    ) {
        check(!isClosed) { "TarWriter is closed" }
        val normalizedPath = entryPath.replace('\\', '/').trimStart('/')
        val header = createHeader(normalizedPath, size, mtime, mode, '0')
        outputStream.write(header)

        val buffer = ByteArray(64 * 1024)
        var totalWritten = 0L
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalWritten += bytesRead
        }

        // Pad to next 512-byte block boundary
        val remainder = (totalWritten % BLOCK_SIZE).toInt()
        if (remainder > 0) {
            val padding = BLOCK_SIZE - remainder
            outputStream.write(zeroBlock, 0, padding)
        }
    }

    /**
     * Finishes writing the archive by appending the standard two 512-byte EOF zero blocks.
     */
    fun finish() {
        if (!isClosed) {
            outputStream.write(zeroBlock)
            outputStream.write(zeroBlock)
            outputStream.flush()
        }
    }

    override fun close() {
        if (!isClosed) {
            try {
                finish()
            } finally {
                isClosed = true
                outputStream.close()
            }
        }
    }

    internal fun createHeader(
        path: String,
        size: Long,
        mtime: Long,
        mode: Int,
        typeFlag: Char
    ): ByteArray {
        val header = ByteArray(BLOCK_SIZE)

        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        if (pathBytes.size <= 100) {
            System.arraycopy(pathBytes, 0, header, 0, pathBytes.size)
        } else {
            // USTAR prefix split: find last '/' before index 155
            val splitIdx = path.lastIndexOf('/', 154)
            if (splitIdx != -1 && (path.length - splitIdx - 1) <= 100) {
                val prefix = path.substring(0, splitIdx)
                val name = path.substring(splitIdx + 1)
                val prefixBytes = prefix.toByteArray(StandardCharsets.UTF_8)
                val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
                System.arraycopy(nameBytes, 0, header, 0, nameBytes.size)
                System.arraycopy(prefixBytes, 0, header, 345, prefixBytes.size)
            } else {
                // Fallback: truncate to 100 bytes
                System.arraycopy(pathBytes, 0, header, 0, minOf(pathBytes.size, 100))
            }
        }

        // Mode (8 bytes): 7 octal digits + null/space (offset 100)
        writeOctal(header, 100, 8, mode.toLong())

        // UID (8 bytes): offset 108
        writeOctal(header, 108, 8, 0L)

        // GID (8 bytes): offset 116
        writeOctal(header, 116, 8, 0L)

        // Size (12 bytes): 11 octal digits + null/space (offset 124)
        writeOctal(header, 124, 12, size)

        // Mtime (12 bytes): offset 136
        writeOctal(header, 136, 12, mtime)

        // Checksum placeholder: 8 spaces (0x20) (offset 148)
        for (i in 148 until 156) {
            header[i] = ' '.code.toByte()
        }

        // Type flag (1 byte): offset 156
        header[156] = typeFlag.code.toByte()

        // Magic "ustar\0" (6 bytes): offset 257
        val magicBytes = USTAR_MAGIC.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(magicBytes, 0, header, 257, magicBytes.size)

        // Version "00" (2 bytes): offset 263
        val versionBytes = USTAR_VERSION.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(versionBytes, 0, header, 263, versionBytes.size)

        // Compute and write checksum (6 octal digits + null + space)
        var checksum = 0L
        for (b in header) {
            checksum += (b.toInt() and 0xFF)
        }
        writeChecksum(header, 148, checksum)

        return header
    }

    private fun writeOctal(buffer: ByteArray, offset: Int, length: Int, value: Long) {
        val octalStr = java.lang.Long.toOctalString(value)
        val numDigits = length - 1 // Leave 1 byte for null terminator
        val padded = octalStr.padStart(numDigits, '0')
        val bytes = padded.toByteArray(StandardCharsets.US_ASCII)
        val copyLen = minOf(bytes.size, numDigits)
        System.arraycopy(bytes, bytes.size - copyLen, buffer, offset + (numDigits - copyLen), copyLen)
        buffer[offset + length - 1] = 0 // null terminator
    }

    private fun writeChecksum(buffer: ByteArray, offset: Int, checksum: Long) {
        val octalStr = java.lang.Long.toOctalString(checksum).padStart(6, '0')
        val bytes = octalStr.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(bytes, 0, buffer, offset, 6)
        buffer[offset + 6] = 0 // null
        buffer[offset + 7] = ' '.code.toByte() // space
    }
}
