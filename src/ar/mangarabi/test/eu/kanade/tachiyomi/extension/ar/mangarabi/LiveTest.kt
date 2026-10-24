package eu.kanade.tachiyomi.extension.ar.mangarabi

import eu.kanade.tachiyomi.extension.ar.shared.LiveHttp
import eu.kanade.tachiyomi.extension.ar.shared.NextData
import eu.kanade.tachiyomi.extension.ar.shared.siteJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveTest {
    private val base = "https://mangarabi.com/"
    private val api = "https://api.mangarabi.com/api/v1"
    private fun json(url: String) = siteJson.parseToJsonElement(LiveHttp.get(url, base)).jsonObject

    @Test
    fun browseSearchFullChapterPaginationAndImages() {
        assumeTrue(System.getenv("RUN_LIVE_TESTS") == "1")
        val first = Parser.catalog(json("$api/series?sort=popular&per_page=2&page=1"))
        val second = Parser.catalog(json("$api/series?sort=popular&per_page=2&page=2"))
        assertTrue(first.hasNext)
        assertTrue(first.series.isNotEmpty())
        assertNotEquals(first.series.first().url, second.series.first().url)
        assertTrue(Parser.catalog(json("$api/series?sort=latest&per_page=2")).series.isNotEmpty())
        val search = Parser.catalog(json("$api/search?q=boruto&per_page=2"))
        assertTrue(search.series.any { it.title.contains("Boruto", ignoreCase = true) })
        val series = Parser.series(json("$api/series/boruto-two-blue-vortex")["data"]!!.jsonObject)
        assertTrue(series.title.isNotBlank())
        LiveHttp.image(series.cover!!, base)
        val chapters = mutableListOf<String>()
        var page = 1
        do {
            val batch = Parser.chapters(json("$api/series/boruto-two-blue-vortex/chapters?per_page=10&page=${page++}"), "boruto-two-blue-vortex")
            chapters.addAll(batch.chapters.map { it.url })
        } while (batch.hasNext)
        assertTrue(chapters.size > 10)
        assertEquals(chapters.size, chapters.distinct().size)
        val html = LiveHttp.get(base.trimEnd('/') + chapters.first(), base)
        val reader = Parser.reader(NextData(html))
        assertTrue("Selected chapter needs preparation; rerun once ready", reader.complete)
        reader.pages.forEach { LiveHttp.image(it, base) }
        println("MangaRabi: browse, latest, search, details, ${chapters.size} chapters, ${reader.pages.size} reader images passed")
    }
}
