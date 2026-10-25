package eu.kanade.tachiyomi.extension.ar.hikayateam

import eu.kanade.tachiyomi.extension.ar.shared.Failure
import eu.kanade.tachiyomi.extension.ar.shared.SourceFailure
import eu.kanade.tachiyomi.extension.ar.shared.requiredText
import eu.kanade.tachiyomi.extension.ar.shared.Catalog
import eu.kanade.tachiyomi.extension.ar.shared.ChapterBatch
import eu.kanade.tachiyomi.extension.ar.shared.ChapterEntry
import eu.kanade.tachiyomi.extension.ar.shared.NextData
import eu.kanade.tachiyomi.extension.ar.shared.Series
import eu.kanade.tachiyomi.extension.ar.shared.flag
import eu.kanade.tachiyomi.extension.ar.shared.items
import eu.kanade.tachiyomi.extension.ar.shared.parseDate
import eu.kanade.tachiyomi.extension.ar.shared.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

internal object Parser {
    private const val baseUrl = "https://hikayateam.com"
    const val apiUrl = "$baseUrl/wapi/v1"

    fun cover(key: String?): String? = key?.takeIf(String::isNotBlank)?.let {
        if (it.startsWith("https://")) it else "$apiUrl/media/cover".toHttpUrl().newBuilder().addQueryParameter("key", it).build().toString()
    }

    fun catalog(root: JsonObject): Catalog {
        check(root.flag("success")) { root.text("message") ?: Failure.CHANGED.arabic }
        val data = root["data"]!!.jsonObject
        return Catalog(data.items("series").map { item ->
            val it = item.jsonObject
            Series("/series/${it.requiredText("postId")}", it.requiredText("title"), cover(it.text("imageUrl")))
        }, !data.flag("isLastPage"))
    }

    fun details(data: NextData): Series {
        val manga = data.objectValue("mangaData")
            ?: throw SourceFailure(Failure.UNAVAILABLE)
        return Series(
            "/series/${manga.requiredText("postId")}",
            manga.requiredText("name"),
            cover(manga.text("cover")),
            manga.text("author")?.trim(),
            manga.text("artist")?.trim(),
            manga.text("story"),
            manga.items("genre").joinToString(", ") { it.jsonPrimitive.content },
            when (manga.text("status")) {
                "مستمر" -> 1
                "مكتمل", "منتهي" -> 2
                "ملغي" -> 5
                "متوقف", "متوقف مؤقتا", "متوقف مؤقتًا" -> 6
                else -> 0
            },
        )
    }

    fun chapters(root: JsonObject, postId: String): ChapterBatch {
        check(root.flag("success")) { root.text("message") ?: Failure.CHANGED.arabic }
        val data = root["data"]!!.jsonObject
        return ChapterBatch(data.items("chapters").map { item ->
            val it = item.jsonObject
            val number = it.requiredText("chapter").removeSuffix(".0")
            ChapterEntry(
                "/series/$postId/$number",
                "الفصل $number" + (it.text("title")?.takeIf(String::isNotBlank)?.let { title -> " — $title" } ?: ""),
                number.toFloat(),
                parseDate(it.text("postTime")),
            )
        }, !data.flag("isLastPage"))
    }

    fun pages(data: NextData): List<String> {
        val chapter = data.objectValue("initialData")?.get("currentChapter")?.jsonObject
            ?: throw SourceFailure(Failure.ACCESS)
        check(chapter.flag("hasAccess")) { Failure.ACCESS.arabic }
        val id = chapter.requiredText("id")
        // The browser's image endpoint issues fresh guest media tokens on each request.
        return chapter.items("images").indices.map { "$apiUrl/chapter/image/$id/${it + 1}" }
            .also { check(it.isNotEmpty()) { Failure.EMPTY_READER.arabic } }
    }
}
