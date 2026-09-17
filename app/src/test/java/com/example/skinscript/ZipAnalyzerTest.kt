package com.example.skinscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipAnalyzerTest {

    private val analyzerHelper = ZipAnalyzerPathHelper()

    @Test
    fun testValidZipPaths() {
        assertTrue(analyzerHelper.isValidZipPath("assets/art/character/hero.unity3d"))
        assertTrue(analyzerHelper.isValidZipPath("assets/audio/hero/speech.bank"))
        assertTrue(analyzerHelper.isValidZipPath("assets/ui/icon.png"))
        assertTrue(analyzerHelper.isValidZipPath("com.mobilelegends/files/dragon2017/assets/art/skin.bytes"))
    }

    @Test
    fun testRejectDirectoryTraversal() {
        assertFalse(analyzerHelper.isValidZipPath("../secret.txt"))
        assertFalse(analyzerHelper.isValidZipPath("assets/../../root.txt"))
        assertFalse(analyzerHelper.isValidZipPath("assets/art/../../../etc/passwd"))
        assertFalse(analyzerHelper.isValidZipPath("assets/art/..\\..\\system.img"))
    }

    @Test
    fun testRejectAbsolutePaths() {
        assertFalse(analyzerHelper.isValidZipPath("/storage/emulated/0/file.txt"))
        assertFalse(analyzerHelper.isValidZipPath("C:\\Windows\\System32\\calc.exe"))
    }

    @Test
    fun testRejectNullBytes() {
        assertFalse(analyzerHelper.isValidZipPath("assets/art\u0000/bad.txt"))
    }

    @Test
    fun testPathNormalization() {
        assertEquals("assets/art/file.unity3d", analyzerHelper.normalizePath("\\assets\\art\\file.unity3d"))
        assertEquals("assets/audio/voice.bank", analyzerHelper.normalizePath("/assets///audio//voice.bank"))
    }

    @Test
    fun testNestedZipUnwrapping() {
        val tempDir = java.nio.file.Files.createTempDirectory("zip_test").toFile()
        try {
            // 1. Create inner zip with Art/character/hero.unity3d
            val innerZipFile = java.io.File(tempDir, "inner_skin.zip")
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(innerZipFile)).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("com.mobilelegends/files/dragon2017/assets/Art/character/hero.unity3d"))
                zos.write("unity3d data".toByteArray())
                zos.closeEntry()
            }

            // 2. Create outer zip wrapping inner_skin.zip (matching Rafaela sfile archive structure)
            val outerZipFile = java.io.File(tempDir, "outer_skin.zip")
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(outerZipFile)).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("AstclnPack/read_me.txt"))
                zos.write("readme data".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(java.util.zip.ZipEntry("inner_skin.zip"))
                java.nio.file.Files.copy(innerZipFile.toPath(), zos)
                zos.closeEntry()
            }

            // 3. Verify that outer zip contains no direct Art/Audio/UI assets
            val outerZip = java.util.zip.ZipFile(outerZipFile)
            val nestedEntries = mutableListOf<String>()
            var directGameAssets = 0
            val entries = outerZip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith(".zip", ignoreCase = true)) {
                    nestedEntries.add(entry.name)
                }
                if (entry.name.contains("Art/") || entry.name.contains("Audio/") || entry.name.contains("UI/")) {
                    directGameAssets++
                }
            }
            outerZip.close()

            assertEquals(0, directGameAssets)
            assertEquals(1, nestedEntries.size)
            assertEquals("inner_skin.zip", nestedEntries.first())

            // 4. Extract nested inner zip and verify game assets exist inside
            val unwrapDir = java.io.File(tempDir, "unwrapped")
            unwrapDir.mkdirs()
            val extractedInner = java.io.File(unwrapDir, "inner_skin.zip")
            java.util.zip.ZipFile(outerZipFile).use { zf ->
                val entry = zf.getEntry("inner_skin.zip")
                zf.getInputStream(entry).use { input ->
                    java.io.FileOutputStream(extractedInner).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            assertTrue(extractedInner.exists())
            java.util.zip.ZipFile(extractedInner).use { innerZf ->
                val gameEntry = innerZf.getEntry("com.mobilelegends/files/dragon2017/assets/Art/character/hero.unity3d")
                org.junit.Assert.assertNotNull(gameEntry)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

class ZipAnalyzerPathHelper {
    fun isValidZipPath(rawPath: String): Boolean {
        if (rawPath.isBlank()) return false
        if (rawPath.contains('\u0000')) return false

        val normalized = rawPath.replace('\\', '/')
        val segments = normalized.split('/')

        for (segment in segments) {
            if (segment == ".." || segment == ".") {
                if (segment == "..") return false
            }
        }

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
}
