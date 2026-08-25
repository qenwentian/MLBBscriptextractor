package com.example.skinscript.data.marketplace

import android.net.Uri
import java.io.File

sealed class DownloadStatus {
    data object Idle : DownloadStatus()
    data class Preparing(val secondsLeft: Int, val message: String = "Resolving link...") : DownloadStatus()
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedFormatted: String
    ) : DownloadStatus() {
        val percentage: Int get() = (progress * 100).toInt().coerceIn(0, 100)
    }
    data class Completed(val fileUri: Uri, val localFile: File) : DownloadStatus()
    data class Failed(val errorMessage: String) : DownloadStatus()
}

data class MarketplaceItem(
    val id: String,
    val title: String,
    val fileSize: String,
    val pageUrl: String,
    val downloadUrl: String? = null,
    val uploadedDate: String? = null,
    val downloadsCount: String? = null
) {
    val heroTag: String?
        get() {
            val lower = title.lowercase()
            val heroes = listOf(
                "martis", "chou", "selena", "gusion", "ling", "fanny", "alucard", "lancelot",
                "hayabusa", "claude", "granger", "beatrix", "lesley", "miya", "layla", "moskov",
                "karrie", "wanwan", "paquito", "yu zhong", "yin", "freya", "zilong", "alpha",
                "roger", "aldous", "badang", "guinevere", "kagura", "kadita", "lunox", "harith",
                "cecilion", "xavier", "julian", "nolan", "cici", "suyou", "zhuxin", "lukas",
                "aot", "anime", "backup", "config", "effect", "recall"
            )
            for (h in heroes) {
                if (lower.contains(h)) {
                    return h.replaceFirstChar { it.uppercase() }
                }
            }
            return null
        }
}

data class MarketplacePage(
    val items: List<MarketplaceItem>,
    val currentPage: Int,
    val totalPages: Int
) {
    val hasNextPage: Boolean get() = currentPage < totalPages
}
