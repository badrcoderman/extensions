package eu.kanade.tachiyomi.extension.ar.mangarabi

import eu.kanade.tachiyomi.extension.ar.shared.NextData
import eu.kanade.tachiyomi.extension.ar.shared.flightHtml
import eu.kanade.tachiyomi.extension.ar.shared.siteJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ParserTest {
    @Test
    fun keepsPaginationWhenAChapterPageContainsOnlyPlaceholders() {
        val root = siteJson.parseToJsonElement("""{"data":[{"is_placeholder":true,"number":"9","slug":"chapter-9"}],"meta":{"pagination":{"current_page":1,"last_page":2}}}""").jsonObject
        val batch = Parser.chapters(root, "example")
        assertTrue(batch.hasNext)
        assertTrue(batch.chapters.isEmpty())
    }

    @Test
    fun keepsDecimalChapterNumbersAndStableWebsiteUrls() {
        val root = siteJson.parseToJsonElement("""{"data":[{"number":"1.5","slug":"chapter-1-5","title":null,"published_at":"2026-10-24T12:00:00Z"}],"meta":{"pagination":{"current_page":2,"last_page":2}}}""").jsonObject
        val batch = Parser.chapters(root, "example")
        assertFalse(batch.hasNext)
        assertEquals("/read/example/chapter-1-5", batch.chapters.single().url)
        assertEquals(1.5f, batch.chapters.single().number)
        assertTrue(batch.chapters.single().date > 0)
    }

    @Test
    fun readsEveryPageFromFlightRatherThanOnlyRenderedImages() {
        val stream = """1:{"initialState":{"chapter_id":"chapter-id","status":"ready","total_pages":3},"initialAccess":{"pages":[{"page":3,"url":"https://cdn.example/3"},{"page":1,"url":"https://cdn.example/1"},{"page":2,"url":"https://cdn.example/2"}]}}"""
        val reader = Parser.reader(NextData("<img src=\"https://cdn.example/1\">" + flightHtml(stream)))
        assertTrue(reader.complete)
        assertEquals(listOf("https://cdn.example/1", "https://cdn.example/2", "https://cdn.example/3"), reader.pages)
    }

    @Test
    fun doesNotTreatPartialChaptersAsComplete() {
        val stream = """1:{"initialState":{"chapter_id":"chapter-id","status":"partial_ready","total_pages":3},"initialAccess":{"pages":[{"page":1,"url":"https://cdn.example/1"}]}}"""
        assertFalse(Parser.reader(NextData(flightHtml(stream))).complete)
    }

    @Test
    fun handlesNullTitlesAndLastCataloguePage() {
        val root = siteJson.parseToJsonElement("""{"data":[{"slug":"example","title":null,"status":"completed","cover_url":null}],"meta":{"pagination":{"current_page":1,"last_page":1}}}""").jsonObject
        val catalog = Parser.catalog(root)
        assertFalse(catalog.hasNext)
        assertEquals("example", catalog.series.single().title)
        assertEquals(2, catalog.series.single().status)
    }
}
