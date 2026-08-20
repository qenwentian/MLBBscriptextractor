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
