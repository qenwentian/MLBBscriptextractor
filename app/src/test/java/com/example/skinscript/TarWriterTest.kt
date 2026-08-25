package com.example.skinscript

import com.example.skinscript.installer.TarWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class TarWriterTest {

    @Test
    fun testHeaderCreation() {
        val bos = ByteArrayOutputStream()
        val writer = TarWriter(bos)

        val header = writer.createHeader(
            path = "Art/Character/hero.unity3d",
            size = 1024L,
            mtime = 1700000000L,
            mode = 420, // 0644 octal
            typeFlag = '0'
        )

        assertEquals(TarWriter.BLOCK_SIZE, header.size)

        // Verify name
        val nameString = String(header, 0, 26, StandardCharsets.UTF_8)
        assertEquals("Art/Character/hero.unity3d", nameString)

        // Verify magic "ustar\0" at offset 257
        val magicString = String(header, 257, 5, StandardCharsets.US_ASCII)
        assertEquals("ustar", magicString)
        assertEquals(0, header[262].toInt())

        // Verify version "00" at offset 263
        val versionString = String(header, 263, 2, StandardCharsets.US_ASCII)
        assertEquals("00", versionString)

        // Verify checksum
        var sum = 0L
        for (i in 0 until TarWriter.BLOCK_SIZE) {
            if (i in 148 until 156) {
                sum += ' '.code.toLong()
            } else {
                sum += (header[i].toInt() and 0xFF).toLong()
            }
        }
        val checksumStr = String(header, 148, 6, StandardCharsets.US_ASCII).trim()
        val parsedChecksum = checksumStr.toLong(8)
        assertEquals(sum, parsedChecksum)
    }

    @Test
    fun testWriteEntryAndPadding() {
        val bos = ByteArrayOutputStream()
        val writer = TarWriter(bos)

        val content = "Hello, MLBB Skin Installer!".toByteArray(StandardCharsets.UTF_8)
        val contentSize = content.size.toLong()

        writer.writeEntry(
            entryPath = "UI/test.txt",
            size = contentSize,
            inputStream = ByteArrayInputStream(content)
        )
        writer.finish()

        val output = bos.toByteArray()
        // Expected: 1 header block (512) + 1 data block padded (512) + 2 EOF blocks (1024) = 2048 bytes
        assertEquals(2048, output.size)

        // Check content data starts at offset 512
        val extractedContent = String(output, 512, content.size, StandardCharsets.UTF_8)
        assertEquals("Hello, MLBB Skin Installer!", extractedContent)

        // Verify padding bytes after content are zero
        for (i in (512 + content.size) until 1024) {
            assertEquals(0, output[i].toInt())
        }

        // Verify EOF blocks are all zero
        for (i in 1024 until 2048) {
            assertEquals(0, output[i].toInt())
        }
    }

    @Test
    fun testLongPathPrefixSplit() {
        val bos = ByteArrayOutputStream()
        val writer = TarWriter(bos)

        val prefix = "assets/dragon2017/very/long/directory/structure/that/exceeds/normal/path/length/for/testing"
        val name = "hero_skin_texture.unity3d"
        val fullPath = "$prefix/$name"

        val header = writer.createHeader(
            path = fullPath,
            size = 500L,
            mtime = 1700000000L,
            mode = 420,
            typeFlag = '0'
        )

        assertEquals(TarWriter.BLOCK_SIZE, header.size)

        val nameInHeader = String(header, 0, name.length, StandardCharsets.UTF_8)
        assertEquals(name, nameInHeader)

        val prefixInHeader = String(header, 345, prefix.length, StandardCharsets.UTF_8)
        assertEquals(prefix, prefixInHeader)
    }

    @Test
    fun testMultipleEntries() {
        val bos = ByteArrayOutputStream()
        val writer = TarWriter(bos)

        val file1 = "file1 content".toByteArray(StandardCharsets.UTF_8)
        val file2 = "file2 larger content for testing".toByteArray(StandardCharsets.UTF_8)

        writer.writeEntry("Art/file1.txt", file1.size.toLong(), ByteArrayInputStream(file1))
        writer.writeEntry("Audio/file2.txt", file2.size.toLong(), ByteArrayInputStream(file2))
        writer.finish()

        val output = bos.toByteArray()
        // file1: header(512) + data padded(512) = 1024
        // file2: header(512) + data padded(512) = 1024
        // EOF: 1024
        // Total: 3072 bytes
        assertEquals(3072, output.size)
    }

    @Test
    fun testEmptyFile() {
        val bos = ByteArrayOutputStream()
        val writer = TarWriter(bos)

        val emptyBytes = ByteArray(0)
        writer.writeEntry("empty.txt", 0L, ByteArrayInputStream(emptyBytes))
        writer.finish()

        val output = bos.toByteArray()
        // header(512) + EOF(1024) = 1536 bytes
        assertEquals(1536, output.size)
    }
}
