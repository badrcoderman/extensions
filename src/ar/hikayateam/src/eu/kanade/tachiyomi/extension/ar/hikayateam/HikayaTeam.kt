package eu.kanade.tachiyomi.extension.ar.hikayateam

import eu.kanade.tachiyomi.extension.ar.shared.NextData
import eu.kanade.tachiyomi.extension.ar.shared.siteJson
import eu.kanade.tachiyomi.extension.ar.shared.toChapter
import eu.kanade.tachiyomi.extension.ar.shared.toManga
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import rx.Observable

class HikayaTeam : HttpSource() {
    override val name = "HikayaTeam"
    override val baseUrl = "https://hikayateam.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private fun get(url: String) = Request.Builder().url(url).headers(headers).build()

    private fun post(path: String, body: JsonObject) = get("${Parser.apiUrl}/$path").newBuilder()
        .post(body.toString().toRequestBody("application/json".toMediaType())).build()

    private fun json(response: Response): JsonObject = siteJson.parseToJsonElement(response.body!!.string()).jsonObject

    private fun catalogRequest(page: Int, query: String = "", popular: Boolean = false) = post(
        "series/filter",
        buildJsonObject {
            put("page", page)
            put("limit", 24)
            if (query.isNotBlank()) put("value", query.trim())
            if (popular) put("order_by", "DAILY_VIEWS")
        },
    )

    override fun popularMangaRequest(page: Int) = catalogRequest(page, popular = true)
    override fun latestUpdatesRequest(page: Int) = catalogRequest(page)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = catalogRequest(page, query)

    override fun popularMangaParse(response: Response): MangasPage {
        val page = Parser.catalog(json(response))
        return MangasPage(page.series.map { it.toManga() }, page.hasNext)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun mangaDetailsParse(response: Response) = Parser.details(NextData(response.body!!.string())).toManga(true)

    private fun chaptersRequest(manga: SManga, page: Int) = post(
        "series/chapters/get",
        buildJsonObject {
            put("postId", manga.url.substringAfterLast('/').toLong())
            put("limit", 75)
            put("page", page)
        },
    )

    override fun chapterListRequest(manga: SManga) = chaptersRequest(manga, 1)

    override fun chapterListParse(response: Response): List<SChapter> {
        val buffer = Buffer()
        response.request.body!!.writeTo(buffer)
        val postId = siteJson.parseToJsonElement(buffer.readUtf8()).jsonObject["postId"].toString()
        return Parser.chapters(json(response), postId).chapters.map { it.toChapter() }
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

    override fun pageListRequest(chapter: SChapter) = get(baseUrl + chapter.url).newBuilder()
        .header("Cache-Control", "no-cache").build()

    override fun pageListParse(response: Response): List<Page> = Parser.pages(NextData(response.body!!.string()))
        .mapIndexed { index, url -> Page(index, imageUrl = url) }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun getFilterList() = FilterList()
}
