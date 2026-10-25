package eu.kanade.tachiyomi.extension.ar.shared

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

internal fun cleanQuery(value: String) = value.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim()
internal fun searchKey(value: String) = cleanQuery(value).lowercase(Locale.ROOT)
    .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
    .replace(Regex("[أإآٱ]"), "ا").replace('ى', 'ي')

internal class Choice(val key: String, label: String, private val options: List<Pair<String, String>>) :
    Filter.Select<String>(label, options.map { it.second }.toTypedArray()) {
    val selected get() = options.getOrNull(state)?.first.orEmpty()
}
internal fun FilterList.selection(key: String) = filterIsInstance<Choice>().firstOrNull { it.key == key }?.selected.orEmpty()
internal fun choices(key: String, label: String, vararg options: Pair<String, String>) = Choice(key, label, options.toList())

internal fun Site.searchFilters(): FilterList {
    val filters = mutableListOf<Filter<*>>()
    val sorts = when (this) {
        MangaSidSite -> arrayOf("views" to "الأكثر مشاهدة", "latest" to "آخر التحديثات", "title" to "العنوان")
        KadrHubSite -> arrayOf("views" to "الأكثر مشاهدة", "updated" to "آخر التحديثات", "new" to "الأحدث", "rating" to "التقييم", "alpha" to "العنوان")
        Man9aSite -> arrayOf("popular" to "الأكثر مشاهدة", "updated" to "آخر التحديثات", "rating" to "التقييم", "-createdAt" to "الأحدث", "az" to "العنوان")
        ManhwaLekSite -> arrayOf("popular" to "الأكثر مشاهدة", "latest" to "آخر التحديثات", "title" to "العنوان")
        MangaWeeklySite -> emptyArray()
        else -> arrayOf("popular" to "الأكثر مشاهدة", "latest" to "آخر التحديثات")
    }
    if (sorts.isNotEmpty()) filters.add(Choice("sort", "الترتيب", sorts.toList()))
    if (this in listOf(AssassinScansSite, Man9aSite, MangawySite, ManhwaLekSite)) {
        filters.add(choices("type", "النوع", "" to "الكل", "manga" to "مانجا", "manhwa" to "مانهوا", "manhua" to "مانها"))
        filters.add(choices("status", "الحالة", "" to "الكل", "ongoing" to "مستمر", "completed" to "مكتمل"))
    }
    if (this == KadrHubSite) filters.add(Filter.Header("الترتيب متاح عند ترك حقل البحث فارغًا."))
    if (this == MangaWeeklySite) filters.add(Filter.Header("الموقع يدعم البحث بالعنوان فقط."))
    return FilterList(filters)
}

internal fun Site.searchUrl(page: Int, query: String, filters: FilterList): String {
    val builder = listing(page, cleanQuery(query), true).toHttpUrl().newBuilder()
    val sort = filters.selection("sort")
    if (sort.isNotBlank() && !(this == KadrHubSite && query.isNotBlank())) {
        builder.setQueryParameter(if (this == ManhwaLekSite) "order" else "sort", sort)
        if (this == MangaSidSite && sort == "title") builder.setQueryParameter("sortOrder", "ASC")
    }
    for (key in listOf("type", "status")) {
        val value = filters.selection(key)
        if (value.isNotBlank()) builder.setQueryParameter(key, if (this == AssassinScansSite) value.uppercase(Locale.ROOT) else value)
    }
    return builder.build().toString()
}
