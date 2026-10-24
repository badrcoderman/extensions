package eu.kanade.tachiyomi.extension.ar.hikayateam

import eu.kanade.tachiyomi.extension.ar.shared.NextData
import eu.kanade.tachiyomi.extension.ar.shared.flightHtml
import eu.kanade.tachiyomi.extension.ar.shared.siteJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ParserTest {
    @Test
    fun encodesCoverKeysWithoutLosingArabicOrSpaces() {
        val url = Parser.cover("series/1/غلاف جديد.webp")!!
        assertTrue(url.contains("key=series%2F1%2F"))
        assertFalse(url.contains(" "))
        assertEquals("https://cdn.example/cover.webp", Parser.cover("https://cdn.example/cover.webp"))
    }

    @Test
    fun readsPaginationAndStablePostIds() {
        val root = siteJson.parseToJsonElement("""{"success":true,"data":{"isLastPage":false,"series":[{"postId":1048777891,"title":"Example","imageUrl":"series/1/cover.webp"}]}}""").jsonObject
        val catalog = Parser.catalog(root)
        assertTrue(catalog.hasNext)
        assertEquals("/series/1048777891", catalog.series.single().url)
    }

    @Test
    fun stripsOnlyWholeNumberSuffixAndKeepsFractionalChapters() {
        val root = siteJson.parseToJsonElement("""{"success":true,"data":{"isLastPage":true,"chapters":[{"chapter":2.0,"title":null},{"chapter":1.5,"title":"Extra"}]}}""").jsonObject
        val chapters = Parser.chapters(root, "123")
        assertFalse(chapters.hasNext)
        assertEquals(listOf("/series/123/2", "/series/123/1.5"), chapters.chapters.map { it.url })
    }

    @Test
    fun usesOneBasedImageEndpointsInsteadOfStoragePathsOrExpiringTokens() {
        val stream = """1:{"initialData":{"currentChapter":{"id":4373,"hasAccess":true,"images":["projects/1.jpg","projects/2.jpg"]}}}"""
        assertEquals(listOf("${Parser.apiUrl}/chapter/image/4373/1", "${Parser.apiUrl}/chapter/image/4373/2"), Parser.pages(NextData(flightHtml(stream))))
    }

    @Test(expected = IllegalStateException::class)
    fun respectsLockedChapterAccess() {
        val stream = """1:{"initialData":{"currentChapter":{"id":4373,"hasAccess":false,"images":[]}}}"""
        Parser.pages(NextData(flightHtml(stream)))
    }

    @Test(expected = IllegalStateException::class)
    fun reportsApiErrorsInsteadOfReturningAnEmptyCatalogue() {
        Parser.catalog(siteJson.parseToJsonElement("""{"success":false,"message":"Unavailable"}""").jsonObject)
    }
}
