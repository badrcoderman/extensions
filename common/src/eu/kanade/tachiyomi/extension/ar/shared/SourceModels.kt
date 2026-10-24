package eu.kanade.tachiyomi.extension.ar.shared

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

internal fun Series.toManga(initialized: Boolean = false): SManga = SManga.create().also {
    it.url = url
    it.title = title
    it.thumbnail_url = cover
    it.author = author
    it.artist = artist
    it.description = description
    it.genre = genres
    it.status = status
    it.initialized = initialized
}

internal fun ChapterEntry.toChapter(): SChapter = SChapter.create().also {
    it.url = url
    it.name = name
    it.chapter_number = number
    it.date_upload = date
}
