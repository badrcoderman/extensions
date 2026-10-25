package eu.kanade.tachiyomi.extension.ar.shared

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

// Keep network code thin: the same parsers are exercised by fixtures and live checks.
abstract class ReaderSite(private val site: Site) : HttpSource() {
    override val name get() = site.label
    override val baseUrl get() = site.home
    override val lang get() = site.language
    override val supportsLatest get() = site.latestAvailable
    override val client = network.cloudflareClient
    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/").set("Origin", baseUrl)
    private fun get(url: String) = site.request(url, client, headers)
    override fun popularMangaRequest(page: Int) = get(site.listing(page, "", true))
    override fun latestUpdatesRequest(page: Int) = get(site.listing(page, "", false))
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = get(site.listing(page, query, true))
    override fun popularMangaParse(response: Response): MangasPage {
        val result = site.catalog(response.body!!.string(), response.request.url.toString())
        return MangasPage(result.series.map { it.toManga() }, result.hasNext)
    }
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)
    override fun mangaDetailsRequest(manga: SManga) = get(site.detailsUrl(manga.url))
    override fun mangaDetailsParse(response: Response) = site.details(response.body!!.string(), response.request.url.toString()).toManga(true)
    override fun chapterListRequest(manga: SManga) = get(site.detailsUrl(manga.url))
    override fun chapterListParse(response: Response) = site.chapters(response.body!!.string(), response.request.url.toString()).map { it.toChapter() }
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapters = mutableListOf<ChapterEntry>()
        var next: String? = site.detailsUrl(manga.url)
        while (next != null) {
            client.newCall(get(next)).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} while loading chapters" }
                val body = response.body!!.string()
                val url = response.request.url.toString()
                chapters.addAll(site.chapters(body, url))
                next = site.nextChapters(body, url)
            }
        }
        chapters.distinctBy { it.url }.map { it.toChapter() }
    }
    override fun pageListRequest(chapter: SChapter) = get(site.readerUrl(chapter.url)).newBuilder().header("Cache-Control", "no-cache").build()
    override fun pageListParse(response: Response): List<Page> {
        val pages = site.pages(response.body!!.string(), response.request.url.toString())
        check(pages.isNotEmpty()) { "This chapter has no readable images. Check access on the website." }
        return pages.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun getFilterList() = FilterList()
}

abstract class Site(val label: String, val home: String, val language: String = "ar") {
    open val latestAvailable = true
    open fun request(url: String, client: OkHttpClient, headers: Headers): Request = Request.Builder().url(url).headers(headers).build()
    abstract fun listing(page: Int, query: String, popular: Boolean): String
    internal abstract fun catalog(body: String, url: String): Catalog
    open fun detailsUrl(path: String) = home + path
    internal abstract fun details(body: String, url: String): Series
    internal abstract fun chapters(body: String, url: String): List<ChapterEntry>
    internal open fun nextChapters(body: String, url: String): String? = null
    open fun readerUrl(path: String) = home + path
    internal abstract fun pages(body: String, url: String): List<String>

    internal fun url(path: String, vararg params: Pair<String, Any>) = (home + path).toHttpUrl().newBuilder().apply {
        params.forEach { (key, value) -> if (value.toString().isNotBlank()) addQueryParameter(key, value.toString()) }
    }.build().toString()
    internal fun absolute(path: String) = home.toHttpUrl().resolve(path)?.toString() ?: path
    internal fun document(body: String, url: String) = Jsoup.parse(body, url)
}

internal fun objectJson(body: String) = siteJson.parseToJsonElement(body).jsonObject
internal fun JsonElement.stringValue(): String = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()
internal fun JsonObject.joined(key: String): String? = when (val value = this[key]) {
    is JsonArray -> value.map { it.stringValue() }.filter { it.isNotBlank() }.joinToString().ifBlank { null }
    else -> text(key)
}
internal fun state(value: String?): Int = when (value?.lowercase()) {
    "ongoing", "مستمر", "مستمرة" -> 1
    "completed", "مكتمل", "مكتملة" -> 2
    "hiatus", "متوقف" -> 6
    else -> 0
}
internal fun Element.image(): String? = listOf("data-hq", "data-src", "data-lazy-src", "src").firstNotNullOfOrNull { key ->
    attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { absUrl(key).ifBlank { it } }
}
internal fun Document.nextPage(url: String): Boolean {
    val page = url.toHttpUrl().queryParameter("page")?.toIntOrNull() ?: 1
    return select("a[href]").any { link ->
        val target = link.absUrl("href").toHttpUrlOrNull() ?: return@any false
        (target.queryParameter("page")?.toIntOrNull() ?: 0) == page + 1 ||
            target.encodedPath.contains("/page/${page + 1}/")
    }
}

// Nuxt's devalue payload stores object properties and array entries as table references.
internal object NuxtData {
    fun data(document: Document): JsonObject {
        val table = siteJson.parseToJsonElement(document.selectFirst("#__NUXT_DATA__")!!.data()).jsonArray
        val visiting = mutableSetOf<Int>()
        fun resolve(index: Int): JsonElement {
            if (index < 0 || !visiting.add(index)) return JsonNull
            val value = table[index]
            val result = when (value) {
                is JsonObject -> JsonObject(value.mapValues { resolve(it.value.jsonPrimitive.content.toInt()) })
                is JsonArray -> if (value.firstOrNull() is JsonPrimitive && value.first().jsonPrimitive.isString) {
                    value.getOrNull(1)?.jsonPrimitive?.content?.toIntOrNull()?.let { resolve(it) } ?: JsonNull
                } else JsonArray(value.map { resolve(it.jsonPrimitive.content.toInt()) })
                else -> value
            }
            visiting.remove(index)
            return result
        }
        return resolve(0).jsonObject["data"]!!.jsonObject
    }
}
