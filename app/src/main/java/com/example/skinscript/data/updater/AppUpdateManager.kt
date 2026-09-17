package com.example.skinscript.data.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionName: String,
    val releaseTitle: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val publishedAt: String
)

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckState()
    data object UpToDate : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

sealed class UpdateDownloadState {
    data object Idle : UpdateDownloadState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : UpdateDownloadState()
    data class ReadyToInstall(val apkFile: File) : UpdateDownloadState()
    data class Error(val message: String) : UpdateDownloadState()
}

class AppUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
        const val DEFAULT_GITHUB_OWNER = "qenwentian"
        const val DEFAULT_GITHUB_REPO = "MLBBscriptextractor"
        private const val GITHUB_API_BASE = "https://api.github.com/repos"

        fun isVersionNewer(latest: String, current: String, currentCode: Long = 0L): Boolean {
            val cleanLatest = latest.trim().removePrefix("v").removePrefix("V").trim()
            val cleanCurrent = current.trim().removePrefix("v").removePrefix("V").trim()
            if (cleanLatest.isBlank() || cleanCurrent.isBlank()) return false

            // If latest tag is a simple integer (e.g. "5" from "v5"), compare against versionCode too
            val latestInt = cleanLatest.toLongOrNull()
            if (latestInt != null && currentCode > 0L && currentCode >= latestInt) {
                return false
            }

            val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val length = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until length) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val currentVersionName: String
        get() {
            return try {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                packageInfo.versionName ?: "1.0"
            } catch (_: Throwable) {
                "1.0"
            }
        }

    val currentVersionCode: Long
        get() {
            return try {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
            } catch (_: Throwable) {
                1L
            }
        }

    suspend fun checkForUpdates(
        owner: String = DEFAULT_GITHUB_OWNER,
        repo: String = DEFAULT_GITHUB_REPO
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val url = "$GITHUB_API_BASE/$owner/$repo/releases/latest"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SkinScript-Android-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    // No releases published yet
                    return@withContext Result.success(null)
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("GitHub API error: HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty release response"))
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                val releaseName = json.optString("name", "New Update")
                val bodyText = json.optString("body", "No changelog provided.")
                val publishedAt = json.optString("published_at", "")

                // Find APK asset
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var apkName = "SkinScript-$tagName.apk"

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            apkName = name
                            break
                        }
                    }
                }

                if (apkUrl == null) {
                    // No APK attached in the release
                    return@withContext Result.success(null)
                }

                val current = currentVersionName.removePrefix("v").trim()
                val isNewer = isVersionNewer(tagName, current, currentVersionCode)

                if (isNewer) {
                    val updateInfo = AppUpdateInfo(
                        versionName = tagName,
                        releaseTitle = releaseName,
                        changelog = bodyText,
                        apkDownloadUrl = apkUrl,
                        apkFileName = apkName,
                        publishedAt = publishedAt
                    )
                    Result.success(updateInfo)
                } else {
                    Result.success(null)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check for updates", e)
            Result.failure(e)
        }
    }

    init {
        cleanOldUpdates()
    }

    fun cleanOldUpdates() {
        try {
            val updatesDir = File(context.getExternalFilesDir(null), "updates")
            if (updatesDir.exists()) {
                updatesDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.extension.equals("apk", ignoreCase = true)) {
                        val deleted = file.delete()
                        Log.d(TAG, "Cleaned up old update APK: ${file.name} ($deleted)")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error cleaning old updates", e)
        }
    }

    fun setDismissedVersion(version: String) {
        val clean = version.trim().removePrefix("v").removePrefix("V").trim()
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
            .edit()
            .putString("dismissed_version", clean)
            .apply()
    }

    fun isVersionDismissed(version: String): Boolean {
        val clean = version.trim().removePrefix("v").removePrefix("V").trim()
        val dismissed = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
            .getString("dismissed_version", null)
        return dismissed != null && dismissed.equals(clean, ignoreCase = true)
    }

    suspend fun downloadApk(
        updateInfo: AppUpdateInfo,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val targetDir = File(context.getExternalFilesDir(null), "updates")
            if (targetDir.exists()) {
                // Delete all previous update APKs to prevent storage pileup
                targetDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.extension.equals("apk", ignoreCase = true)) {
                        file.delete()
                    }
                }
            } else {
                targetDir.mkdirs()
            }

            val targetFile = File(targetDir, updateInfo.apkFileName)
            if (targetFile.exists()) targetFile.delete()

            val request = Request.Builder()
                .url(updateInfo.apkDownloadUrl)
                .header("User-Agent", "SkinScript-Android-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("APK download failed: HTTP ${response.code}"))
                }
                val body = response.body ?: return@withContext Result.failure(Exception("Empty APK response body"))
                val totalLength = body.contentLength()

                var downloaded = 0L
                val buffer = ByteArray(8192)
                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)

                outputStream.use { out ->
                    inputStream.use { inStream ->
                        while (true) {
                            val read = inStream.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            val fraction = if (totalLength > 0) downloaded.toFloat() / totalLength.toFloat() else 0f
                            onProgress(fraction, downloaded, totalLength)
                        }
                    }
                }

                Result.success(targetFile)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed downloading update APK", e)
            Result.failure(e)
        }
    }

    fun promptInstallApk(apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch package installer", e)
        }
    }
}
