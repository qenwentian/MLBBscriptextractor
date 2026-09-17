package com.example.skinscript

import android.net.Uri
import com.example.skinscript.data.AssetCategory
import com.example.skinscript.data.ConflictDetector
import com.example.skinscript.data.SkinPackage
import com.example.skinscript.data.ZipEntryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConflictDetectionTest {

    private fun createDummyPackage(
        name: String,
        assets: List<String>
    ): SkinPackage {
        val zipEntries = assets.map { assetPath ->
            ZipEntryInfo(
                entryPath = "com.mobilelegends/files/dragon2017/assets/$assetPath",
                relativeAssetPath = assetPath,
                category = when {
                    assetPath.startsWith("Art/", ignoreCase = true) -> AssetCategory.ART
                    assetPath.startsWith("Audio/", ignoreCase = true) -> AssetCategory.AUDIO
                    else -> AssetCategory.UI
                },
                name = File(assetPath).name,
                size = 1024L,
                compressedSize = 512L,
                isDirectory = false
            )
        }

        return SkinPackage(
            sourceUri = null,
            displayName = name,
            artFiles = zipEntries.filter { it.category == AssetCategory.ART },
            audioFiles = zipEntries.filter { it.category == AssetCategory.AUDIO },
            uiFiles = zipEntries.filter { it.category == AssetCategory.UI }
        )
    }

    @Test
    fun testSameHeroReplaceDefaultConflictDetected() {
        // User scenario: "you have 3 soyou sasuke skins replace default then the user gets prompted which one to extract"
        val skin1 = createDummyPackage(
            name = "Suyou Sasuke Curse Mark - Replace Default.zip",
            assets = listOf("Art/character/suyou_sasuke_a.unity3d")
        )
        val skin2 = createDummyPackage(
            name = "Suyou Sasuke Susanoo - Replace Default.zip",
            assets = listOf("Art/character/suyou_sasuke_b.unity3d")
        )
        val skin3 = createDummyPackage(
            name = "Suyou Sasuke Rinnegan - Replace Default.zip",
            assets = listOf("Art/character/suyou_sasuke_c.unity3d")
        )

        val groups = ConflictDetector.findConflictGroups(listOf(skin1, skin2, skin3))

        assertEquals(1, groups.size)
        val group = groups.first()
        assertTrue(group.title.contains("Suyou"))
        assertEquals(3, group.packages.size)
        assertTrue(group.packages.contains(skin1))
        assertTrue(group.packages.contains(skin2))
        assertTrue(group.packages.contains(skin3))
    }

    @Test
    fun testSharedAssetPathConflictDetected() {
        val skin1 = createDummyPackage(
            name = "Martis Custom Skin A.zip",
            assets = listOf("Art/character/1053.unity3d", "UI/hero_1053.png")
        )
        val skin2 = createDummyPackage(
            name = "Martis Custom Skin B.zip",
            assets = listOf("Art/character/1053.unity3d") // Overlapping 1053.unity3d
        )

        val groups = ConflictDetector.findConflictGroups(listOf(skin1, skin2))

        assertEquals(1, groups.size)
        assertEquals(2, groups.first().packages.size)
    }

    @Test
    fun testNoConflictDifferentHeroesAndAssets() {
        val chouSkin = createDummyPackage(
            name = "Chou Thunderfist - Replace Default.zip",
            assets = listOf("Art/character/chou_thunder.unity3d")
        )
        val selenaSkin = createDummyPackage(
            name = "Selena Stun - Replace Default.zip",
            assets = listOf("Art/character/selena_stun.unity3d")
        )

        val groups = ConflictDetector.findConflictGroups(listOf(chouSkin, selenaSkin))

        assertEquals(0, groups.size)
    }
}
