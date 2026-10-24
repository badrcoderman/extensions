package eu.kanade.tachiyomi.extension.ar.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal val siteJson = Json { ignoreUnknownKeys = true }

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.items(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

internal fun JsonObject.flag(key: String): Boolean = text(key) == "true"

internal fun JsonObject.number(key: String): Int = text(key)?.toIntOrNull() ?: 0

internal fun parseDate(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    val normalized = value.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00")
    val pattern = if (normalized.length > 19) "yyyy-MM-dd'T'HH:mm:ssXXX" else "yyyy-MM-dd'T'HH:mm:ss"
    return runCatching {
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(normalized)?.time ?: 0L
    }.getOrDefault(0L)
}

internal class Series(
    val url: String,
    val title: String,
    val cover: String?,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: String? = null,
    val status: Int = 0,
)

internal class ChapterEntry(
    val url: String,
    val name: String,
    val number: Float,
    val date: Long,
)

internal class Catalog(val series: List<Series>, val hasNext: Boolean)

internal class ChapterBatch(val chapters: List<ChapterEntry>, val hasNext: Boolean)

internal class ReaderState(
    val chapterId: String,
    val status: String,
    val total: Int,
    val pages: List<String>,
) {
    val complete: Boolean get() = status == "ready" && pages.isNotEmpty() && pages.size == total
}

// Next.js renders only the first few images; its Flight props contain the full reader.
internal class NextData(val document: Document) {
    constructor(html: String) : this(Jsoup.parse(html))

    private val flight: String by lazy {
        document.select("script").mapNotNull { script ->
            val data = script.data()
            if (!data.startsWith("self.__next_f.push(")) return@mapNotNull null
            runCatching {
                val args = siteJson.parseToJsonElement(data.substringAfter("push(").substringBeforeLast(")")).jsonArray
                if (args.firstOrNull()?.jsonPrimitive?.content == "1") args[1].jsonPrimitive.content else null
            }.getOrNull()
        }.joinToString("")
    }

    fun objectValue(key: String): JsonObject? = embeddedValue(flight, key) as? JsonObject

    companion object {
        internal fun embeddedValue(stream: String, key: String): JsonElement? {
            val marker = "\"$key\":"
            var offset = stream.indexOf(marker)
            while (offset >= 0) {
                var start = offset + marker.length
                while (start < stream.length && stream[start].isWhitespace()) start++
                if (start < stream.length && stream[start] in "{[") {
                    var depth = 0
                    var quoted = false
                    var escaped = false
                    for (end in start until stream.length) {
                        val char = stream[end]
                        if (quoted) {
                            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                        } else {
                            when (char) {
                                '"' -> quoted = true
                                '{', '[' -> depth++
                                '}', ']' -> {
                                    depth--
                                    if (depth == 0) return siteJson.parseToJsonElement(stream.substring(start, end + 1))
                                }
                            }
                        }
                    }
                }
                offset = stream.indexOf(marker, offset + marker.length)
            }
            return null
        }

        fun actionResult(body: String): JsonObject = body.lineSequence()
            .firstOrNull { it.startsWith("1:{") }
            ?.substringAfter(":")
            ?.let { siteJson.parseToJsonElement(it).jsonObject }
            ?: throw IllegalStateException("The reader could not prepare this chapter. Open it in WebView and try again.")

        fun actionIds(script: String): Map<String, String> =
            Regex("createServerReference\\)\\(\"([a-f0-9]+)\"[^)]*?\"(\\w+Action)\"\\)")
                .findAll(script).associate { it.groupValues[2] to it.groupValues[1] }
    }
}
