package eu.kanade.tachiyomi.extension.ar.shared

import kotlinx.serialization.json.*
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NewSitesTest {
    private val slug = System.getProperty("sourceSlug", "")
    private val sites = mapOf(
        "mangasid" to MangaSidSite, "assassinscans" to AssassinScansSite, "kadrhub" to KadrHubSite,
        "manhwalek" to ManhwaLekSite, "mangawy" to MangawySite, "man9a" to Man9aSite, "mangaweekly" to MangaWeeklySite,
    )
    private fun resource(name: String) = javaClass.getResource("/sites/$name")!!.readText()
    private fun sample() = objectJson(resource("cases.json"))[slug]!!.jsonObject
    private fun fixture(kind: String) = resource("$slug-$kind.txt")

    @Test fun savedCatalogDetailsCompleteChaptersAndReader() {
        assumeTrue(slug in sites)
        val site = sites[slug]!!
        val sample = sample()
        val catalog = site.catalog(fixture("catalog"), site.listing(1, "", true))
        assertTrue("Catalog is empty", catalog.series.isNotEmpty())
        assertTrue("Catalog title is missing", catalog.series.all { it.title.isNotBlank() })
        assertTrue("Catalog cover is missing", catalog.series.all { !it.cover.isNullOrBlank() })
        val url = site.detailsUrl(sample.text("path")!!)
        val detail = site.details(fixture("details"), url)
        assertTrue(detail.title.isNotBlank())
        assertTrue(!detail.cover.isNullOrBlank())
        val chapters = site.chapters(fixture("details"), url)
        // MangaWeekly's remaining chapters are loaded through its pagination callback.
        if (slug == "mangaweekly") {
            assertTrue(chapters.isNotEmpty())
            assertNotNull(site.nextChapters(fixture("details"), url))
        } else assertEquals(sample.number("chapters"), chapters.size)
        assertEquals(chapters.size, chapters.distinctBy { it.url }.size)
        assertTrue(chapters.zipWithNext().all { it.first.number >= it.second.number })
        val pages = site.pages(fixture("reader"), site.readerUrl(chapters.first().url))
        assertEquals(sample.number("pages"), pages.size)
        assertEquals(pages.size, pages.distinct().size)
        assertTrue(pages.all { it.startsWith("https://") })
    }

    @Test fun kadrTextChapterReportsUsefulError() {
        assumeTrue(slug == "kadrhub")
        try {
            KadrHubSite.pages("<script id='reader-data'>{\"canRead\":true,\"kind\":\"text\"}</script>", "https://kadrhub.com/")
            fail("Text chapter was accepted as an image reader")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("text chapter"))
        }
    }

    @Test fun liveBrowseSearchChaptersAndSampleImages() {
        assumeTrue(System.getenv("RUN_LIVE_TESTS") == "1" && slug in sites)
        val site = sites[slug]!!
        val sample = sample()
        val client = OkHttpClient.Builder().callTimeout(75, TimeUnit.SECONDS).build()
        val headers = Headers.Builder().add("User-Agent", "Mozilla/5.0").add("Referer", site.home + "/").add("Origin", site.home).build()
        fun load(url: String) = client.newCall(site.request(url, client, headers)).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}: $url" }
            response.body!!.string()
        }
        val firstUrl = site.listing(1, "", true)
        val first = site.catalog(load(firstUrl), firstUrl)
        assertTrue(first.series.isNotEmpty())
        if (first.hasNext) {
            val secondUrl = site.listing(2, "", true)
            val second = site.catalog(load(secondUrl), secondUrl)
            assertTrue(second.series.isNotEmpty())
            assertNotEquals(first.series.first().url, second.series.first().url)
        }
        if (site.latestAvailable) {
            val latest = site.listing(1, "", false)
            assertTrue(site.catalog(load(latest), latest).series.isNotEmpty())
        }
        val search = site.listing(1, sample.text("query")!!, true)
        assertTrue(site.catalog(load(search), search).series.isNotEmpty())
        val detailUrl = site.detailsUrl(sample.text("path")!!)
        val body = load(detailUrl)
        val detail = site.details(body, detailUrl)
        LiveHttp.image(detail.cover!!, site.home + "/")
        val chapters = site.chapters(body, detailUrl).toMutableList()
        var next = site.nextChapters(body, detailUrl)
        while (next != null) {
            val url = next
            val batch = load(url)
            chapters.addAll(site.chapters(batch, url))
            next = site.nextChapters(batch, url)
        }
        assertTrue(chapters.isNotEmpty())
        assertEquals(chapters.size, chapters.distinctBy { it.url }.size)
        val reader = site.readerUrl(chapters.first().url)
        val pages = site.pages(load(reader), reader)
        assertTrue(pages.isNotEmpty())
        listOf(0, pages.size / 2, pages.lastIndex).distinct().forEach { LiveHttp.image(pages[it], site.home + "/") }
        println("${site.label}: browse, pagination, search, cover, ${chapters.size} chapters, ${pages.size} image sections; first/middle/last images passed")
    }
}
