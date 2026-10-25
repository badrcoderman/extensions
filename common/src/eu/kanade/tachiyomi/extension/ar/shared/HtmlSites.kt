package eu.kanade.tachiyomi.extension.ar.shared

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

object MangaSidSite : Site("MangaSid", "https://mangasid.com") {
    override fun listing(page: Int, query: String, popular: Boolean) = url("/manga-list", "page" to page, "search" to query, "sort" to if (popular) "views" else "latest")
    override fun catalog(body: String, url: String): Catalog {
        val doc = document(body, url)
        val series = doc.select(".manga-card").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/manga/]") ?: return@mapNotNull null
            val image = card.selectFirst("img")
            val title = card.selectFirst("h3, h2")?.text() ?: image?.attr("alt").orEmpty()
            Series(link.attr("href"), title, image?.image())
        }.distinctBy { it.url }
        return Catalog(series, doc.nextPage(url))
    }
    override fun details(body: String, url: String): Series {
        val doc = document(body, url)
        return Series(url.toHttpUrl().encodedPath, doc.selectFirst("h1")!!.text(), doc.selectFirst("meta[property=og:image]")?.attr("content"),
            description = doc.selectFirst("#description, .manga-description")?.text() ?: doc.selectFirst("meta[name=description]")?.attr("content"),
            genres = doc.select("a[href*=/genres/]").joinToString { it.text() })
    }
    override fun chapters(body: String, url: String): List<ChapterEntry> = document(body, url).select("a[href^=/reader/]")
        .map { link ->
            val path = link.attr("href")
            val number = path.substringAfterLast('/').toFloatOrNull() ?: -1f
            ChapterEntry(path, "الفصل ${path.substringAfterLast('/')}", number, 0)
        }.distinctBy { it.url }.sortedByDescending { it.number }
    override fun pages(body: String, url: String) = document(body, url).select("[data-page] img").mapNotNull { it.image() }.distinct()
}

object KadrHubSite : Site("KadrHub", "https://kadrhub.com") {
    override fun listing(page: Int, query: String, popular: Boolean) = if (query.isNotBlank()) {
        url("/api/v1/search", "q" to query)
    } else url("/series", "page" to page, "sort" to if (popular) "views" else "updated")
    override fun catalog(body: String, url: String): Catalog {
        if (url.toHttpUrl().encodedPath.startsWith("/api/")) {
            return Catalog(objectJson(body).items("items").map { item ->
                val value = item.jsonObject
                Series(value.text("url")!!.removePrefix(home), value.text("title")!!, value.text("cover")?.let { absolute(it) })
            }, false)
        }
        val doc = document(body, url)
        return Catalog(doc.select(".card-title a[href^=/series/]").map { link ->
            val card = link.parents().firstOrNull { it.hasClass("card") } ?: link.parent()!!.parent()!!
            Series(link.attr("href"), link.text(), card.selectFirst("img")?.image())
        }.distinctBy { it.url }, doc.nextPage(url))
    }
    override fun details(body: String, url: String): Series {
        val doc = document(body, url)
        return Series(url.toHttpUrl().encodedPath, doc.selectFirst("h1.s-title")!!.text(), doc.selectFirst("meta[property=og:image]")?.attr("content"),
            description = doc.selectFirst(".synopsis")?.text(), genres = doc.select("a[href*=/genres/]").joinToString { it.text() })
    }
    override fun chapters(body: String, url: String): List<ChapterEntry> = document(body, url).select("a[href*=/chapter-]")
        .map { link ->
            val path = link.attr("href")
            ChapterEntry(path, link.text().ifBlank { "الفصل ${path.substringAfterLast("chapter-")}" }, path.substringAfterLast("chapter-").toFloatOrNull() ?: -1f, 0)
        }.distinctBy { it.url }.sortedByDescending { it.number }
    override fun pages(body: String, url: String): List<String> {
        val doc = document(body, url)
        doc.selectFirst("#reader-data")?.let {
            val data = objectJson(it.data())
            check(data.flag("canRead")) { "This chapter requires access on KadrHub." }
            check(data.text("kind") != "text") { "This is a text chapter; open it on KadrHub." }
        }
        return doc.select("[data-pages] .page img").mapNotNull { it.image() }
    }
}

object MangaWeeklySite : Site("MangaWeekly", "https://www.mangaweekly.com", "en") {
    override val latestAvailable = false
    override fun listing(page: Int, query: String, popular: Boolean): String = if (page == 1) url("/en/", "s" to query) else {
        ajax(page, if (query.isBlank()) "series_grid" else "search", "", "", query)
    }
    private fun ajax(page: Int, type: String, nonce: String, category: String, query: String = "") = url(
        "/wp-admin/admin-ajax.php", "extension_page" to page, "extension_type" to type,
        "extension_nonce" to nonce, "extension_category" to category, "extension_query" to query,
    )
    private fun nonce(body: String) = Regex("\"nonce\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    override fun request(url: String, client: okhttp3.OkHttpClient, headers: okhttp3.Headers): okhttp3.Request {
        val target = url.toHttpUrl()
        val builder = okhttp3.Request.Builder().url(url).headers(headers)
        val page = target.queryParameter("extension_page") ?: return builder.build()
        val token = target.queryParameter("extension_nonce") ?: client.newCall(
            okhttp3.Request.Builder().url("$home/en/").headers(headers).build(),
        ).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} while preparing MangaWeekly" }
            nonce(response.body!!.string())
        }
        val form = okhttp3.FormBody.Builder().add("action", "mangaverse_load_more").add("nonce", token)
            .add("page", page).add("type", target.queryParameter("extension_type")!!).add("lang", "en")
            .add("order", "desc").add("category_id", target.queryParameter("extension_category").orEmpty())
            .add("search_query", target.queryParameter("extension_query").orEmpty()).build()
        return builder.post(form).build()
    }
    private fun html(body: String) = if (body.trimStart().startsWith("{")) objectJson(body)["data"]!!.jsonObject.text("html")!! else body
    override fun catalog(body: String, url: String): Catalog {
        val doc = document(html(body), url)
        val cards = doc.select("a.series-card-link").map { link ->
            val title = link.selectFirst("h2, h3, .series-card-title")?.text() ?: link.selectFirst("img")?.attr("alt").orEmpty()
            val cover = link.selectFirst("img")?.image() ?: link.selectFirst(".series-card-thumb")?.attr("style")
                ?.let { Regex("url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1) }
            Series(link.absUrl("href").removePrefix(home), title, cover)
        }
        val search = doc.select("a.chapter-link").map { link ->
            val slug = link.absUrl("href").toHttpUrl().pathSegments[1]
            Series("/en/manga/$slug.html", link.selectFirst(".chapter-title")!!.text().substringBefore(", Chapter"), null)
        }
        val more = if (body.trimStart().startsWith("{")) objectJson(body)["data"]!!.jsonObject.flag("has_more") else doc.selectFirst("#load-more-series-grid, #load-more-search") != null
        return Catalog((cards + search).distinctBy { it.url }, more)
    }
    override fun details(body: String, url: String): Series {
        val doc = document(body, url)
        return Series(url.toHttpUrl().encodedPath, doc.selectFirst("h1.series-title")!!.text(), doc.selectFirst("meta[property=og:image]")?.attr("content"),
            description = doc.selectFirst(".series-description, .series-synopsis, .description")?.text(), genres = doc.select("a[href*=/genre/]").joinToString { it.text() })
    }
    override fun chapters(body: String, url: String) = document(html(body), url).select(".chapters-list a.chapter-link, a.chapter-link").map { link ->
        val path = link.absUrl("href").removePrefix(home)
        val number = Regex("-chapter-([0-9.]+)\\.html").find(path)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        ChapterEntry(path, link.text(), number, 0)
    }.distinctBy { it.url }.sortedByDescending { it.number }
    override fun nextChapters(body: String, url: String): String? {
        val request = url.toHttpUrl()
        if (body.trimStart().startsWith("{")) {
            if (!objectJson(body)["data"]!!.jsonObject.flag("has_more")) return null
            return ajax(request.queryParameter("extension_page")!!.toInt() + 1, "series", request.queryParameter("extension_nonce")!!, request.queryParameter("extension_category")!!)
        }
        val doc = document(body, url)
        val more = doc.selectFirst("#load-more-series") ?: return null
        return ajax(2, "series", nonce(body), more.attr("data-category"))
    }
    override fun pages(body: String, url: String) = document(body, url).select("img[data-src*=/cdn/Book], img[src*=/cdn/Book]").mapNotNull { it.image() }.distinct()
}

object MangawySite : Site("Mangawy", "https://mangawy.org") {
    override fun listing(page: Int, query: String, popular: Boolean) = url("/api/browse", "page" to page, "q" to query, "sort" to if (popular) "popular" else "latest")
    private fun series(value: JsonObject) = Series(value.text("path") ?: "/series/${value.text("slug")}", value.text("title")!!, value.text("cover_url"),
        author = (value["metadata"] as? JsonObject)?.joined("authors"), artist = (value["metadata"] as? JsonObject)?.joined("artists"),
        description = value.text("description"), genres = value.joined("genres"), status = state(value.text("status")))
    override fun catalog(body: String, url: String): Catalog {
        val data = objectJson(body)
        val pagination = data["pagination"]!!.jsonObject
        return Catalog(data.items("items").map { series(it.jsonObject) }, pagination.number("page") < pagination.number("total_pages"))
    }
    override fun details(body: String, url: String) = series(NuxtData.data(document(body, url)).entries.first { it.key.startsWith("series-detail-") }.value.jsonObject)
    override fun chapters(body: String, url: String): List<ChapterEntry> {
        val data = NuxtData.data(document(body, url)).entries.first { it.key.startsWith("series-chapters-") }.value
        val items = if (data is JsonArray) data else data.jsonObject.items("chapters")
        return items.map { item ->
            val value = item.jsonObject
            val number = value.text("number")!!
            ChapterEntry(value.text("path") ?: "${url.toHttpUrl().encodedPath}/chapter/$number-${value.text("public_id")}", value.text("title_ar")?.takeIf { it.isNotBlank() }?.let { "الفصل $number - $it" } ?: "الفصل $number", number.toFloat(), parseDate(value.text("published_at")))
        }.sortedByDescending { it.number }
    }
    override fun pages(body: String, url: String): List<String> {
        val data = NuxtData.data(document(body, url)).entries.first { it.key.startsWith("chapter-") }.value.jsonObject
        val chapter = data["chapter"]!!.jsonObject
        // Tiles are full-width vertical strips. Keeping them separate avoids giant bitmaps on iOS.
        return chapter.items("pages").sortedBy { it.jsonObject.number("number") }.flatMap { page ->
            page.jsonObject.items("tiles").sortedBy { it.jsonObject.number("index") }.map { it.jsonObject.text("imageUrl")!! }
        }
    }
}
