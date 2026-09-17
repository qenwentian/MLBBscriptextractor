package com.example.skinscript.data

data class ConflictGroup(
    val title: String,
    val packages: List<SkinPackage>
)

object ConflictDetector {
    fun findConflictGroups(packages: List<SkinPackage>): List<ConflictGroup> {
        val groups = mutableListOf<ConflictGroup>()
        val groupedPackages = mutableSetOf<SkinPackage>()

        for (i in packages.indices) {
            val p1 = packages[i]
            if (p1 in groupedPackages) continue

            val conflicting = mutableListOf<SkinPackage>()
            conflicting.add(p1)

            val p1Assets = p1.allAssetFiles.map { it.relativeAssetPath }.toSet()
            val p1Hero = p1.detectedHero?.lowercase()

            for (j in (i + 1) until packages.size) {
                val p2 = packages[j]
                if (p2 in groupedPackages) continue

                val p2Assets = p2.allAssetFiles.map { it.relativeAssetPath }.toSet()
                val sharesAssets = p1Assets.intersect(p2Assets).isNotEmpty()

                val p2Hero = p2.detectedHero?.lowercase()
                val sameHeroAndReplaceDefault = p1Hero != null && p1Hero == p2Hero &&
                        p1.displayName.contains("replace default", ignoreCase = true) &&
                        p2.displayName.contains("replace default", ignoreCase = true)

                if (sharesAssets || sameHeroAndReplaceDefault) {
                    conflicting.add(p2)
                    groupedPackages.add(p2)
                }
            }

            if (conflicting.size > 1) {
                groupedPackages.add(p1)
                val heroName = p1.detectedHero ?: "Skin Assets"
                groups.add(ConflictGroup(title = "$heroName (Conflicting Skins)", packages = conflicting))
            }
        }
        return groups
    }
}
