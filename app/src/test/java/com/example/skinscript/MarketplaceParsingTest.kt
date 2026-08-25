package com.example.skinscript

import com.example.skinscript.data.marketplace.MarketplaceItem
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class MarketplaceParsingTest {

    @Test
    fun testHeroTagExtraction() {
        val item1 = MarketplaceItem(
            id = "PWJMCcaVInL",
            title = "Default - Martis Attack on Titan",
            fileSize = "17.79 MB",
            pageUrl = "https://sfile.co/PWJMCcaVInL"
        )
        assertEquals("Martis", item1.heroTag)

        val item2 = MarketplaceItem(
            id = "K1jx47CbG4N",
            title = "Default Logo - Chou Thunderfist",
            fileSize = "15.00 MB",
            pageUrl = "https://sfile.co/K1jx47CbG4N"
        )
        assertEquals("Chou", item2.heroTag)

        val item3 = MarketplaceItem(
            id = "m0d3ZGBeGLs",
            title = "Latest Selena Backup File",
            fileSize = "13.63 MB",
            pageUrl = "https://sfile.co/m0d3ZGBeGLs"
        )
        assertEquals("Selena", item3.heroTag)
    }

    @Test
    fun testUserPageHtmlParsing() {
        val sampleHtml = """
            <div class="divide-y divide-slate-100">
                <div class="group px-2 py-2 lg:py-4 transition-colors hover:bg-slate-50">
                    <div class="flex items-center gap-1 lg:gap-2">
                        <div class="min-w-0 flex-1">
                            <a href="https://sfile.co/PWJMCcaVInL" class="block truncate font-semibold text-sfile-500">
                                Default - Martis Attack on Titan
                            </a>
                            <p class="mt-1 text-sm text-slate-500">
                                17.79 MB
                            </p>
                        </div>
                    </div>
                </div>
                <div class="group px-2 py-2 lg:py-4 transition-colors hover:bg-slate-50">
                    <div class="flex items-center gap-1 lg:gap-2">
                        <div class="min-w-0 flex-1">
                            <a href="https://sfile.co/K1jx47CbG4N" class="block truncate font-semibold text-sfile-500">
                                Default Logo - Martis AOT
                            </a>
                            <p class="mt-1 text-sm text-slate-500">
                                17.87 MB
                            </p>
                        </div>
                    </div>
                </div>
            </div>
            <div class="border-t border-slate-200 bg-slate-50 px-6 py-4">
                <div class="text-sm text-slate-600">
                    Page <span class="font-semibold text-slate-900">1</span> of <span class="font-semibold text-slate-900">47</span>
                </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml)
        val fileElements = doc.select("div.group")
        assertEquals(2, fileElements.size)

        val items = mutableListOf<MarketplaceItem>()
        for (el in fileElements) {
            val linkEl = el.selectFirst("a[href*='sfile.co/']")
            assertNotNull(linkEl)
            val href = linkEl!!.attr("href")
            val id = href.substringAfterLast("/").substringBefore("?")
            val title = linkEl.text().trim()
            val sizeEl = el.selectFirst("p.text-slate-500, p.text-sm")
            val sizeText = sizeEl?.text()?.trim() ?: ""

            items.add(MarketplaceItem(id = id, title = title, fileSize = sizeText, pageUrl = href))
        }

        assertEquals(2, items.size)
        assertEquals("PWJMCcaVInL", items[0].id)
        assertEquals("Default - Martis Attack on Titan", items[0].title)
        assertEquals("17.79 MB", items[0].fileSize)
        assertEquals("Martis", items[0].heroTag)

        val paginationMatch = Pattern.compile("Page\\s+<span[^>]*>(\\d+)</span>\\s+of\\s+<span[^>]*>(\\d+)</span>").matcher(sampleHtml)
        assertTrue(paginationMatch.find())
        assertEquals("1", paginationMatch.group(1))
        assertEquals("47", paginationMatch.group(2))
    }

    @Test
    fun testDirectUrlExtraction() {
        val sampleJs = """
            if (adblockDetected) {
                downloadButton.href = "https:\/\/download3526.sfile.co\/downloadfile\/2399921\/756594\/fd75d9f46f89ce8da60e346d1c88cd88\/default-martis-attack-on-titan.zip?k=1dec01470c127cd57f22792d7815ad93";
            }
        """.trimIndent()

        val patterns = listOf(
            Pattern.compile("https:\\\\/\\\\/download\\d*\\.sfile\\.co\\\\/downloadfile\\\\/[^\"'\\s]+"),
            Pattern.compile("https://download\\d*\\.sfile\\.co/downloadfile/[^\"'\\s]+")
        )

        var found: String? = null
        for (p in patterns) {
            val m = p.matcher(sampleJs)
            if (m.find()) {
                found = m.group(0)?.replace("\\/", "/")
                break
            }
        }

        assertNotNull(found)
        assertEquals(
            "https://download3526.sfile.co/downloadfile/2399921/756594/fd75d9f46f89ce8da60e346d1c88cd88/default-martis-attack-on-titan.zip?k=1dec01470c127cd57f22792d7815ad93",
            found
        )
    }
}
