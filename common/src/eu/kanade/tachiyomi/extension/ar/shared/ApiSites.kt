package eu.kanade.tachiyomi.extension.ar.shared

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

object AssassinScansSite : Site("Assassin Scans", "https://as-scans.net") {
    override fun listing(page: Int, query: String, popular: Boolean) = url("/api/manga", "page" to page, "limit" to 24, "search" to query, "sort" to if (popular) "popular" else "latest")
    override fun detailsUrl(path: String) = "$home/api/manga/${path.substringAfterLast('/')}"
    private fun series(data: JsonObject) = Series("/comics/${data.text("slug")}", data.requiredText("title"), data.text("cover")?.let { absolute(it) },
        data.joined("author"), data.joined("artist"), data.text("description"), data.joined("genres"), state(data.text("status")))
    override fun catalog(body: String, url: String): Catalog {
        val data = objectJson(body)
        val pagination = data["pagination"]!!.jsonObject
        return Catalog(data.items("data").map { series(it.jsonObject) }, pagination.number("page") < pagination.number("totalPages"))
    }
    override fun details(body: String, url: String) = series(objectJson(body)["data"]!!.jsonObject)
    override fun chapters(body: String, url: String): List<ChapterEntry> {
        val data = objectJson(body)["data"]!!.jsonObject
        return data.items("chapters").map { item ->
            val chapter = item.jsonObject
            val number = chapter.requiredText("number")
            ChapterEntry("/reader/${data.text("slug")}/$number?chapterId=${chapter.text("id")}", "الفصل $number" + chapter.text("title")?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty(), number.toFloat(), parseDate(chapter.text("createdAt")))
        }.sortedByDescending { it.number }
    }
    override fun readerUrl(path: String) = "$home/api/manga/chapters/${(home + path).toHttpUrl().queryParameter("chapterId")}/images"
    override fun pages(body: String, url: String) = objectJson(body).items("data").map { absolute(it.stringValue()) }
}

object ManhwaLekSite : Site("ManhwaLek", "https://manhwalek.com") {
    override fun listing(page: Int, query: String, popular: Boolean) = url("/api/catalog", "page" to page, "query" to query, "order" to if (popular) "popular" else "latest")
    override fun detailsUrl(path: String) = url("/api/series-info", "id" to path.substringAfterLast('/'))
    private fun series(data: JsonObject) = Series("/series/${data.text("slug") ?: data.text("id")}", data.requiredText("title"),
        data.text("cover")?.let { url("/api/proxy-image", "url" to it, "w" to 400, "q" to 85) },
        data.joined("author"), data.joined("artist"), data.text("synopsis"), data.joined("genres"), state(data.text("status")))
    override fun catalog(body: String, url: String): Catalog {
        val request = url.toHttpUrl()
        val query = request.queryParameter("query").orEmpty()
        val page = request.queryParameter("page")!!.toInt()
        var values = siteJson.parseToJsonElement(body).jsonArray.map { it.jsonObject }.filter {
            query.isBlank() || listOf("title", "arTitle").any { key -> searchKey(it.text(key).orEmpty()).contains(searchKey(query)) }
        }
        val type = request.queryParameter("type").orEmpty()
        val status = request.queryParameter("status").orEmpty()
        if (type.isNotBlank()) values = values.filter {
            val actual = searchKey(it.text("type").orEmpty())
            actual == type || actual.substringBefore(' ') == mapOf("manga" to "مانجا", "manhwa" to "مانهوا", "manhua" to "مانها")[type]
        }
        if (status.isNotBlank()) values = values.filter { state(it.text("status")) == state(status) }
        if (request.queryParameter("order") == "title") values = values.sortedBy { searchKey(it.requiredText("title")) }
        if (request.queryParameter("order") == "popular") values = values.sortedByDescending {
            val views = it.text("views").orEmpty().uppercase()
            val scale = when { views.endsWith("M") -> 1_000_000; views.endsWith("K") -> 1_000; else -> 1 }
            (Regex("[0-9.]+").find(views)?.value?.toDoubleOrNull() ?: 0.0) * scale
        }
        val start = (page - 1) * 24
        return Catalog(values.drop(start).take(24).map { series(it) }, start + 24 < values.size)
    }
    override fun details(body: String, url: String) = series(objectJson(body))
    override fun chapters(body: String, url: String): List<ChapterEntry> {
        val data = objectJson(body)
        return data.items("chapters").map { item ->
            val number = item.stringValue()
            ChapterEntry("/reader/${data.text("slug") ?: data.text("id")}/$number", "الفصل $number", number.toFloat(), 0)
        }.sortedByDescending { it.number }
    }
    override fun readerUrl(path: String): String {
        val parts = path.trim('/').split('/')
        return url("/api/chapter-pages", "seriesId" to parts[1], "chapter" to parts[2])
    }
    override fun pages(body: String, url: String) = objectJson(body).items("pages").map { image ->
        // Use the same origin proxy as the website for remote hosts that require it.
        this.url("/api/proxy-image", "url" to image.stringValue())
    }
}

object Man9aSite : Site("Man9a", "https://man9a.net") {
    override fun listing(page: Int, query: String, popular: Boolean) = url("/api/titles", "page" to page, "limit" to 24, "search" to query, "sort" to if (popular) "popular" else "updated")
    override fun detailsUrl(path: String) = "$home/api/titles/${path.substringAfterLast('/')}"
    private fun cover(image: String) = url("/api/stream/image", "url" to image, "type" to "cover")
    private fun series(data: JsonObject) = Series("/${data.text("type")}/${data.text("slug")}", data.requiredText("title"), data.text("coverImage")?.let { cover(it) },
        data.joined("author"), data.joined("artist"), data.text("description")?.let { Jsoup.parse(it).text() }, data.joined("genres"), state(data.text("status")))
    override fun catalog(body: String, url: String): Catalog {
        val data = objectJson(body)
        return Catalog(data.items("data").map { series(it.jsonObject) }.filter { !it.url.startsWith("/novel/") }, data.number("page") < data.number("totalPages"))
    }
    override fun details(body: String, url: String) = series(objectJson(body)["manga"]!!.jsonObject)
    override fun chapters(body: String, url: String): List<ChapterEntry> {
        val data = objectJson(body)
        val manga = data["manga"]!!.jsonObject
        return data.items("chapters").filterNot { it.jsonObject.flag("isPremium") }.map { item ->
            val chapter = item.jsonObject
            val number = chapter.requiredText("number")
            ChapterEntry("/read/${manga.text("slug")}/$number", chapter.text("title")?.ifBlank { null } ?: "الفصل $number", number.toFloat(), parseDate(chapter.text("releaseDate")))
        }.sortedByDescending { it.number }
    }
    override fun readerUrl(path: String): String {
        val parts = path.trim('/').split('/')
        return "$home/api/chapters/manga/${parts[1]}/${parts[2]}"
    }
    override fun pages(body: String, url: String): List<String> {
        val data = objectJson(body)
        check(!data.flag("isLocked")) { Failure.ACCESS.arabic }
        check(data.text("isReady") != "false") { Failure.PREPARING.arabic }
        return data["chapter"]!!.jsonObject.items("pages").map { image -> this.url("/api/stream/image", "url" to image.stringValue(), "type" to "page") }
    }
}
