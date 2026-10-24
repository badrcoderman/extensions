package eu.kanade.tachiyomi.extension.ar.hikayateam

import eu.kanade.tachiyomi.extension.ar.shared.LiveHttp
import eu.kanade.tachiyomi.extension.ar.shared.NextData
import eu.kanade.tachiyomi.extension.ar.shared.siteJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveTest {
    private val base = "https://hikayateam.com/"
    private fun post(path: String, body: String) = siteJson.parseToJsonElement(LiveHttp.post("${Parser.apiUrl}/$path", base, body)).jsonObject

    @Test
    fun browseSearchFullChapterPaginationAndImages() {
        assumeTrue(System.getenv("RUN_LIVE_TESTS") == "1")
        val first = Parser.catalog(post("series/filter", """{"page":1,"limit":2,"order_by":"DAILY_VIEWS"}"""))
        val second = Parser.catalog(post("series/filter", """{"page":2,"limit":2,"order_by":"DAILY_VIEWS"}"""))
        assertTrue(first.hasNext)
        assertNotEquals(first.series.first().url, second.series.first().url)
        assertTrue(Parser.catalog(post("series/filter", """{"page":1,"limit":2}""")).series.isNotEmpty())
        val search = Parser.catalog(post("series/filter", """{"page":1,"limit":2,"value":"Devil Returns"}"""))
        assertTrue(search.series.any { it.title.contains("Devil Returns", ignoreCase = true) })
        val details = Parser.details(NextData(LiveHttp.get("${base}series/1048777891", base)))
        assertTrue(details.title.isNotBlank())
        LiveHttp.image(details.cover!!, base)
        val chapters = mutableListOf<String>()
        var page = 1
        do {
            val batch = Parser.chapters(post("series/chapters/get", """{"postId":1048777891,"page":${page++},"limit":75}"""), "1048777891")
            chapters.addAll(batch.chapters.map { it.url })
        } while (batch.hasNext)
        assertTrue(chapters.size > 75)
        assertEquals(chapters.size, chapters.distinct().size)
        val images = Parser.pages(NextData(LiveHttp.get("${base}series/1048777891/88", base)))
        images.forEach { LiveHttp.image(it, base) }
        println("HikayaTeam: browse, latest, search, details, ${chapters.size} chapters, ${images.size} reader images passed")
    }
}
