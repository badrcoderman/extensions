package eu.kanade.tachiyomi.extension.ar.mangarabi

import eu.kanade.tachiyomi.extension.ar.shared.Catalog
import eu.kanade.tachiyomi.extension.ar.shared.ChapterBatch
import eu.kanade.tachiyomi.extension.ar.shared.ChapterEntry
import eu.kanade.tachiyomi.extension.ar.shared.NextData
import eu.kanade.tachiyomi.extension.ar.shared.ReaderState
import eu.kanade.tachiyomi.extension.ar.shared.Series
import eu.kanade.tachiyomi.extension.ar.shared.flag
import eu.kanade.tachiyomi.extension.ar.shared.items
import eu.kanade.tachiyomi.extension.ar.shared.number
import eu.kanade.tachiyomi.extension.ar.shared.parseDate
import eu.kanade.tachiyomi.extension.ar.shared.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal object Parser {
    fun catalog(root: JsonObject): Catalog {
        val pagination = root["meta"]!!.jsonObject["pagination"]!!.jsonObject
        return Catalog(root.items("data").map { series(it.jsonObject) }, pagination.number("current_page") < pagination.number("last_page"))
    }

    fun series(data: JsonObject): Series = Series(
        url = "/manga/${data.text("slug")!!}",
        title = data.text("title") ?: data.text("slug")!!,
        cover = data.text("cover_url"),
        author = data.text("author"),
        artist = data.text("artist"),
        description = data.text("description"),
        genres = data.items("genres").mapNotNull { it.jsonObject.text("name") }.joinToString(", "),
        status = when (data.text("status")) {
            "ongoing" -> 1
            "completed" -> 2
            "cancelled", "canceled" -> 5
            "hiatus", "on_hold" -> 6
            else -> 0
        },
    )

    fun chapters(root: JsonObject, slug: String): ChapterBatch {
        val pagination = root["meta"]!!.jsonObject["pagination"]!!.jsonObject
        return ChapterBatch(
            root.items("data").map { it.jsonObject }.filterNot { it.flag("is_placeholder") }.map {
                val number = it.text("number") ?: "0"
                ChapterEntry(
                    "/read/$slug/${it.text("slug")!!}",
                    "الفصل $number" + (it.text("title")?.takeIf(String::isNotBlank)?.let { title -> " — $title" } ?: ""),
                    number.toFloatOrNull() ?: -1f,
                    parseDate(it.text("published_at")),
                )
            },
            pagination.number("current_page") < pagination.number("last_page"),
        )
    }

    fun reader(data: NextData): ReaderState {
        val state = data.objectValue("initialState")
            ?: throw IllegalStateException("This chapter is unavailable. Open it in WebView to check access.")
        return ReaderState(
            state.text("chapter_id")!!,
            state.text("status") ?: "cold",
            state.number("total_pages"),
            accessPages(data.objectValue("initialAccess")),
        )
    }

    fun accessPages(access: JsonObject?): List<String> = access?.items("pages")
        ?.map { it.jsonObject }
        ?.sortedBy { it.number("page") }
        ?.mapNotNull { it.text("url") }
        ?: emptyList()
}
