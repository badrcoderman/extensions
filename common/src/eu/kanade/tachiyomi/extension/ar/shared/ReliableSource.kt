package eu.kanade.tachiyomi.extension.ar.shared

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import rx.Observable

abstract class ReliableSource : HttpSource() {
    override val client by lazy {
        recoveryClient(network.cloudflareClient, ::reloadImages)
    }
    private fun reloadImages(chapter: String): List<String> = guarded {
        val url = chapter.toHttpUrlOrNull() ?: throw SourceFailure(Failure.CHANGED)
        val host = baseUrl.toHttpUrlOrNull()!!.host
        check(url.scheme == "https" && (url.host == host || url.host == "api.$host"))
        client.newCall(Request.Builder().url(url).headers(headers).header("Cache-Control", "no-cache").build()).execute().use {
            pageListParse(it).map { page -> page.imageUrl ?: throw SourceFailure(Failure.EMPTY_READER) }
        }
    }
    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder().apply {
        if (page.url.isNotBlank()) tag(ImageReference::class.java, ImageReference(page.url, page.index))
    }.build()
    override fun fetchImageUrl(page: Page): Observable<String> = Observable.fromCallable {
        guarded { reloadImages(page.url).getOrNull(page.index) ?: throw SourceFailure(Failure.EMPTY_READER) }
    }
    override fun fetchPopularManga(page: Int) = super.fetchPopularManga(page).arabicErrors()
    override fun fetchLatestUpdates(page: Int) = super.fetchLatestUpdates(page).arabicErrors()
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) = super.fetchSearchManga(page, query, filters).arabicErrors()
    override fun fetchMangaDetails(manga: SManga) = super.fetchMangaDetails(manga).arabicErrors()
    override fun fetchChapterList(manga: SManga) = super.fetchChapterList(manga).arabicErrors()
    override fun fetchPageList(chapter: SChapter) = super.fetchPageList(chapter).arabicErrors()
}

internal fun <T> Observable<T>.arabicErrors(): Observable<T> = onErrorResumeNext { error: Throwable ->
    Observable.error<T>(ArabicErrors.translate(error))
}
