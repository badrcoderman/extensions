package eu.kanade.tachiyomi.extension.ar.mangarabi

import eu.kanade.tachiyomi.extension.ar.shared.NextData
import eu.kanade.tachiyomi.extension.ar.shared.number
import eu.kanade.tachiyomi.extension.ar.shared.siteJson
import eu.kanade.tachiyomi.extension.ar.shared.text
import eu.kanade.tachiyomi.extension.ar.shared.toChapter
import eu.kanade.tachiyomi.extension.ar.shared.toManga
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import rx.Observable

class MangaRabi : HttpSource() {
    override val name = "MangaRabi"
    override val baseUrl = "https://mangarabi.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val client = network.cloudflareClient

    private val apiUrl = "https://api.mangarabi.com/api/v1"

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private fun request(url: String) = Request.Builder().url(url).headers(headers).build()

    private fun json(response: Response): JsonObject = siteJson.parseToJsonElement(response.body!!.string()).jsonObject

    private fun catalogRequest(page: Int, sort: String): Request = request(
        "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "24")
            .build().toString(),
    )

    override fun popularMangaRequest(page: Int) = catalogRequest(page, "popular")
    override fun latestUpdatesRequest(page: Int) = catalogRequest(page, "latest")

    override fun popularMangaParse(response: Response): MangasPage {
        val page = Parser.catalog(json(response))
        return MangasPage(page.series.map { it.toManga() }, page.hasNext)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)
        return request(
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query.trim())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", "24")
                .build().toString(),
        )
    }

    override fun mangaDetailsRequest(manga: SManga) = request("$apiUrl/series/${manga.url.substringAfterLast('/')}")

    override fun mangaDetailsParse(response: Response) = Parser.series(json(response)["data"]!!.jsonObject).toManga(true)

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    private fun chaptersRequest(manga: SManga, page: Int) = request(
        "$apiUrl/series/${manga.url.substringAfterLast('/')}/chapters?per_page=100&page=$page",
    )

    override fun chapterListRequest(manga: SManga) = chaptersRequest(manga, 1)

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.encodedPath.substringAfter("/series/").substringBefore('/')
        return Parser.chapters(json(response), slug).chapters.map { it.toChapter() }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        do {
            val batch = client.newCall(chaptersRequest(manga, page++)).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} while loading chapters" }
                Parser.chapters(json(response), manga.url.substringAfterLast('/'))
            }
            chapters.addAll(batch.chapters.map { it.toChapter() })
        } while (batch.hasNext)
        chapters.distinctBy { it.url }
    }

    override fun pageListRequest(chapter: SChapter) = request(baseUrl + chapter.url).newBuilder()
        .header("Cache-Control", "no-cache").build()

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val initial = Parser.reader(NextData(document))
        if (initial.complete) return initial.pages.mapIndexed { index, url -> Page(index, imageUrl = url) }

        val actions = readerActions(document)
        val readerUrl = response.request.url.toString()
        var state = action(readerUrl, actions.getValue("startChapterAction"), initial.chapterId)
        val deadline = System.nanoTime() + 60_000_000_000L
        while (state.text("status") != "ready" && System.nanoTime() < deadline) {
            check(state.text("status") in listOf("cold", "processing", "partial_ready")) {
                "MangaRabi could not prepare this chapter. Try opening it in WebView."
            }
            Thread.sleep(2000)
            state = action(readerUrl, actions.getValue("chapterStatusAction"), initial.chapterId)
        }
        check(state.text("status") == "ready") { "This chapter is still being prepared. Please try again shortly." }
        val pages = Parser.accessPages(action(readerUrl, actions.getValue("refreshReaderAction"), initial.chapterId))
        check(pages.isNotEmpty() && pages.size == state.number("total_pages")) {
            "MangaRabi has not returned all pages yet. Please try again shortly."
        }
        return pages.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun readerActions(document: Document): Map<String, String> {
        val actions = mutableMapOf<String, String>()
        for (script in document.select("script[src]").asReversed()) {
            val url = script.absUrl("src")
            if (!url.startsWith("$baseUrl/_next/static/")) continue
            client.newCall(request(url)).execute().use { response ->
                if (response.isSuccessful) actions.putAll(NextData.actionIds(response.body!!.string()))
            }
            if (listOf("startChapterAction", "chapterStatusAction", "refreshReaderAction").all { it in actions }) return actions
        }
        throw IllegalStateException("The MangaRabi reader changed. Please update the extension.")
    }

    private fun action(url: String, id: String, chapterId: String): JsonObject {
        val body = JsonArray(listOf(JsonPrimitive(chapterId))).toString().toRequestBody("text/plain;charset=UTF-8".toMediaType())
        val request = request(url).newBuilder()
            .header("Next-Action", id)
            .header("Accept", "text/x-component")
            .header("Cache-Control", "no-cache")
            .post(body).build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} while preparing the chapter" }
            NextData.actionResult(response.body!!.string())
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun getFilterList() = FilterList()
}
