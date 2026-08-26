package com.example.skinscript.data.marketplace

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SfileRepository(private val context: Context) {

    companion object {
        private const val TAG = "SfileRepository"
        const val BASE_USER_URL = "https://sfile.co/user/7565941754039110/files"
        const val BASE_SEARCH_URL = "https://sfile.co/search"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val existing = cookieStore.getOrPut(host) { mutableListOf() }
            synchronized(existing) {
                for (newCookie in cookies) {
                    existing.removeAll { it.name == newCookie.name }
                    existing.add(newCookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            return cookieStore[host]?.toList() ?: emptyList()
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadDirectory: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "downloads/skins")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val userRepoCache = ConcurrentHashMap<String, MarketplaceItem>()
    private var totalCachedPages = 1

    fun getCachedUserItems(): List<MarketplaceItem> {
        return userRepoCache.values.toList()
    }

    suspend fun fetchUserFiles(page: Int = 1): Result<MarketplacePage> = withContext(Dispatchers.IO) {
        try {
            val url = if (page <= 1) BASE_USER_URL else "$BASE_USER_URL?page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP error ${response.code}: ${response.message}"))
                }
                val html = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
                val parsedPage = parseUserFilesHtml(html, page)
                
                // Cache items
                for (item in parsedPage.items) {
                    userRepoCache[item.id] = item
                }
                totalCachedPages = maxOf(totalCachedPages, parsedPage.totalPages)
                
                Result.success(parsedPage)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching user files page $page", e)
            Result.failure(e)
        }
    }

    suspend fun indexAllUserPages(
        onBatchLoaded: (List<MarketplaceItem>) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // First ensure page 1 is loaded to get total pages
            val p1 = fetchUserFiles(1).getOrNull()
            val total = p1?.totalPages ?: totalCachedPages
            onBatchLoaded(userRepoCache.values.toList())

            if (total <= 1) return@withContext

            // Fetch remaining pages in concurrent chunks
            val pagesToFetch = (2..total).toList()
            val chunks = pagesToFetch.chunked(6)
            for (chunk in chunks) {
                coroutineScope {
                    val deferreds = chunk.map { pageNum ->
                        async {
                            fetchUserFiles(pageNum)
                        }
                    }
                    deferreds.awaitAll()
                }
                onBatchLoaded(userRepoCache.values.toList())
            }
            Log.d(TAG, "Completed indexing all $total pages. Total items: ${userRepoCache.size}")
        } catch (e: Throwable) {
            Log.e(TAG, "Error during full repo background indexing", e)
        }
    }

    suspend fun searchFiles(query: String, page: Int = 1): Result<MarketplacePage> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = if (page <= 1) "$BASE_SEARCH_URL?q=$encodedQuery" else "$BASE_SEARCH_URL?q=$encodedQuery&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP error ${response.code}: ${response.message}"))
                }
                val html = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
                val parsedPage = parseSearchHtml(html, page)
                Result.success(parsedPage)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error searching files for '$query' page $page", e)
            Result.failure(e)
        }
    }

    private fun parseUserFilesHtml(html: String, page: Int): MarketplacePage {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<MarketplaceItem>()

        // Find file items
        val fileElements = doc.select("div.group")
        for (el in fileElements) {
            val linkEl = el.selectFirst("a[href*='sfile.co/']")
            val href = linkEl?.attr("href") ?: continue
            val id = href.substringAfterLast("/").substringBefore("?")
            val title = linkEl.text().trim()
            val sizeEl = el.selectFirst("p.text-slate-500, p.text-sm")
            val sizeText = sizeEl?.text()?.trim() ?: "Unknown size"

            if (id.isNotEmpty() && title.isNotEmpty()) {
                items.add(
                    MarketplaceItem(
                        id = id,
                        title = title,
                        fileSize = sizeText,
                        pageUrl = href
                    )
                )
            }
        }

        // Parse pagination
        var totalPages = page
        val paginationEl = doc.selectFirst("div.text-slate-600:contains(Page)")
        if (paginationEl != null) {
            val spans = paginationEl.select("span.font-semibold")
            if (spans.size >= 2) {
                totalPages = spans[1].text().trim().toIntOrNull() ?: totalPages
            }
        } else {
            val maxPageMatch = Pattern.compile("of\\s+<span[^>]*>(\\d+)</span>").matcher(html)
            if (maxPageMatch.find()) {
                totalPages = maxPageMatch.group(1)?.toIntOrNull() ?: totalPages
            }
        }

        return MarketplacePage(
            items = items,
            currentPage = page,
            totalPages = totalPages.coerceAtLeast(1)
        )
    }

    private fun parseSearchHtml(html: String, page: Int): MarketplacePage {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<MarketplaceItem>()

        val itemElements = doc.select("div.group")
        for (el in itemElements) {
            val linkEl = el.selectFirst("a[href*='sfile.co/']")
            val href = linkEl?.attr("href") ?: continue
            val id = href.substringAfterLast("/").substringBefore("?")
            val title = linkEl.text().trim()
            if (title.equals("Download", ignoreCase = true)) continue

            val sizeEl = el.selectFirst("span:contains(MB), span:contains(KB), span:contains(GB), p.text-slate-500")
            val sizeText = sizeEl?.text()?.trim() ?: ""

            if (id.isNotEmpty() && title.isNotEmpty()) {
                items.add(
                    MarketplaceItem(
                        id = id,
                        title = title,
                        fileSize = sizeText,
                        pageUrl = href
                    )
                )
            }
        }

        // If .group selector found nothing, try direct link matching
        if (items.isEmpty()) {
            val links = doc.select("a[href*='sfile.co/']")
            for (link in links) {
                val href = link.attr("href")
                val id = href.substringAfterLast("/").substringBefore("?")
                val text = link.text().trim()
                if (id.length in 5..20 && !text.equals("Download", ignoreCase = true) && !text.contains("Sign In") && !text.contains("Sign Up") && !text.contains("Latest Uploads")) {
                    items.add(
                        MarketplaceItem(
                            id = id,
                            title = text,
                            fileSize = "",
                            pageUrl = href
                        )
                    )
                }
            }
        }

        // Pagination
        var totalPages = page
        val paginationMatch = Pattern.compile("Page\\s+<span[^>]*>(\\d+)</span>\\s+of\\s+<span[^>]*>(\\d+)</span>").matcher(html)
        if (paginationMatch.find()) {
            totalPages = paginationMatch.group(2)?.toIntOrNull() ?: totalPages
        }

        return MarketplacePage(
            items = items.distinctBy { it.id },
            currentPage = page,
            totalPages = totalPages.coerceAtLeast(1)
        )
    }

    suspend fun resolveAndDownloadZip(
        item: MarketplaceItem,
        onCountdown: (Int) -> Unit,
        onProgress: (progress: Float, bytesDownloaded: Long, totalBytes: Long, speedFormatted: String) -> Unit
    ): Result<Pair<File, Uri>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download resolution for: ${item.title} (${item.pageUrl})")

            // STEP 1: Fetch initial file details page
            val step1Request = Request.Builder()
                .url(item.pageUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val (intermediateUrl, waitSeconds) = client.newCall(step1Request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to open file page: HTTP ${resp.code}"))
                }
                val html = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty file page HTML"))

                val dwUrlMatch = Pattern.compile("id=[\"']download[\"'][^>]*data-dw-url=[\"']([^\"']+)[\"']").matcher(html)
                val dwUrl = if (dwUrlMatch.find()) {
                    dwUrlMatch.group(1)
                } else {
                    val fallbackMatch = Pattern.compile("data-dw-url=[\"']([^\"']+)[\"']").matcher(html)
                    if (fallbackMatch.find()) fallbackMatch.group(1) else null
                } ?: return@withContext Result.failure(Exception("Could not locate download token in page."))

                val waitMatch = Pattern.compile("data-wait-seconds=[\"']?(\\d+)[\"']?").matcher(html)
                val wait = if (waitMatch.find()) waitMatch.group(1)?.toIntOrNull() ?: 5 else 5
                Pair(dwUrl, wait)
            }

            Log.d(TAG, "Step 1 complete. Intermediate URL: $intermediateUrl, Wait: $waitSeconds s")

            // STEP 2: Request intermediate download page
            val step2Request = Request.Builder()
                .url(intermediateUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", item.pageUrl)
                .build()

            val directDownloadUrl = client.newCall(step2Request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed intermediate step: HTTP ${resp.code}"))
                }
                val html2 = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty intermediate HTML"))

                // Search for direct download links in JS or HTML
                val patterns = listOf(
                    Pattern.compile("https:\\\\/\\\\/download\\d*\\.sfile\\.co\\\\/downloadfile\\\\/[^\"'\\s]+"),
                    Pattern.compile("https://download\\d*\\.sfile\\.co/downloadfile/[^\"'\\s]+"),
                    Pattern.compile("https:\\\\/\\\\/sfile\\.co\\\\/download\\\\/\\d+\\\\/[^\"'\\s]+"),
                    Pattern.compile("https://sfile\\.co/download/\\d+/[^\"'\\s]+")
                )

                var found: String? = null
                for (p in patterns) {
                    val m = p.matcher(html2)
                    if (m.find()) {
                        found = m.group(0)?.replace("\\/", "/")
                        break
                    }
                }
                found ?: return@withContext Result.failure(Exception("Could not extract direct download URL."))
            }

            Log.d(TAG, "Step 2 complete. Direct download URL: $directDownloadUrl")

            // STEP 3: Countdown animation/wait
            val countdown = waitSeconds.coerceIn(1, 10)
            for (sec in countdown downTo 1) {
                onCountdown(sec)
                delay(1000)
            }
            onCountdown(0)

            // STEP 4: Stream download binary ZIP
            val sanitizedName = item.title.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
            val fileName = if (sanitizedName.endsWith(".zip", ignoreCase = true)) sanitizedName else "$sanitizedName.zip"
            val targetFile = File(downloadDirectory, fileName)

            val step3Request = Request.Builder()
                .url(directDownloadUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", intermediateUrl)
                .build()

            client.newCall(step3Request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed: HTTP ${resp.code}"))
                }
                val body = resp.body ?: return@withContext Result.failure(Exception("Download response body is null"))
                val totalLength = body.contentLength()

                var bytesDownloaded = 0L
                val buffer = ByteArray(8192)
                var lastProgressTime = System.currentTimeMillis()
                var bytesSinceLastTime = 0L

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)

                outputStream.use { out ->
                    inputStream.use { inStream ->
                        while (true) {
                            val read = inStream.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            bytesDownloaded += read
                            bytesSinceLastTime += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastProgressTime
                            if (elapsed >= 300 || bytesDownloaded == totalLength) {
                                val speedBytesPerSec = if (elapsed > 0) (bytesSinceLastTime * 1000) / elapsed else 0
                                val speedStr = formatSpeed(speedBytesPerSec)
                                val progressFraction = if (totalLength > 0) bytesDownloaded.toFloat() / totalLength.toFloat() else 0f
                                onProgress(progressFraction, bytesDownloaded, totalLength, speedStr)
                                lastProgressTime = now
                                bytesSinceLastTime = 0
                            }
                        }
                    }
                }

                // Verify file is valid
                if (targetFile.length() < 22) {
                    targetFile.delete()
                    return@withContext Result.failure(Exception("Downloaded file is too small or invalid."))
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    targetFile
                )

                Log.d(TAG, "Download finished successfully: ${targetFile.absolutePath} (Size: ${targetFile.length()} bytes)")
                Result.success(Pair(targetFile, uri))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in resolveAndDownloadZip for ${item.title}", e)
            Result.failure(e)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec.toFloat() / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec.toFloat() / 1024)
            else -> "$bytesPerSec B/s"
        }
    }
}
