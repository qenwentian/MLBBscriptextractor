package com.example.skinscript

import com.example.skinscript.data.updater.AppUpdateManager
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testVersionComparison() {
        // When user is on v5.0 (versionCode 5), release v5 should NOT trigger update
        assertFalse(AppUpdateManager.isVersionNewer("v5", "5.0", 5L))
        assertFalse(AppUpdateManager.isVersionNewer("5", "5.0", 5L))
        assertFalse(AppUpdateManager.isVersionNewer("v5.0", "5.0", 5L))
        assertFalse(AppUpdateManager.isVersionNewer("5.0", "5.0", 5L))

        // When user was on older v4.0 (versionCode 4), release v5 DOES trigger update
        assertTrue(AppUpdateManager.isVersionNewer("v5", "4.0", 4L))
        assertTrue(AppUpdateManager.isVersionNewer("5", "4.0", 4L))

        // Newer version tags
        assertTrue(AppUpdateManager.isVersionNewer("v6", "5.0", 5L))
        assertTrue(AppUpdateManager.isVersionNewer("6", "5.0", 5L))
        assertTrue(AppUpdateManager.isVersionNewer("v5.1", "5.0", 5L))

        // Older or equal version tags
        assertFalse(AppUpdateManager.isVersionNewer("v4", "5.0", 5L))
        assertFalse(AppUpdateManager.isVersionNewer("4", "5.0", 5L))
        assertFalse(AppUpdateManager.isVersionNewer("", "5.0", 5L))
    }
}