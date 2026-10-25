package eu.kanade.tachiyomi.extension.ar.shared

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

/** Added before the upstream virtual-image interceptor; all original reader classes remain intact. */
object ProComicNetwork {
    @JvmStatic fun build(builder: OkHttpClient.Builder): OkHttpClient {
        val session = ProComicSession()
        // Keep the host's exception interceptor first, so errors are handled on Android and iOS.
        val position = builder.interceptors().indexOfFirst { it.javaClass.simpleName == "UncaughtExceptionInterceptor" }.let { if (it < 0) 0 else it + 1 }
        builder.interceptors().add(position, session)
        builder.interceptors().add(position + 1, RecoveryInterceptor())
        return builder.build().also { session.client = it }
    }
}

private class ProComicSession : Interceptor {
    lateinit var client: OkHttpClient
    private val readers = object : LinkedHashMap<String, String>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 16
    }
    private class Snapshot(val time: Long, val split: Int, val maps: JsonArray)
    private val snapshots = RefreshCache<Snapshot>(8, TimeUnit.SECONDS.toNanos(10))
    private val json = Json { ignoreUnknownKeys = true }
    override fun intercept(chain: Interceptor.Chain): Response = guarded {
        val request = chain.request()
        val url = request.url
        if (url.host in listOf("procomic.pro", "procomic.net") && url.encodedPath.contains("/chapter/")) {
            Regex("-(\\d+)$").find(url.encodedPath)?.groupValues?.get(1)?.let { id -> synchronized(readers) { readers[id] = url.toString() } }
        }
        try {
            chain.proceed(request)
        } catch (error: SourceFailure) {
            if (error.failure != Failure.ACCESS) throw error
            when (url.host) {
                "procomic.signed" -> chain.proceed(request.newBuilder().header("Cache-Control", "no-cache").build())
                "procomic.pieces" -> {
                    val payload = json.parseToJsonElement(url.fragment ?: throw error).jsonObject
                    val id = payload["chapterId"]!!.jsonPrimitive.content
                    val plan = payload["plan"]!!.jsonObject
                    val pageIndex = plan["pageIndex"]!!.jsonPrimitive.content.toInt()
                    val snapshot = fresh(id, request.headers)
                    val map = snapshot.maps.getOrNull(pageIndex - snapshot.split)?.jsonObject ?: throw SourceFailure(Failure.EMPTY_READER)
                    val replacement = JsonObject(plan + map)
                    val freshPayload = JsonObject(payload + ("plan" to replacement))
                    chain.proceed(request.newBuilder().url(url.newBuilder().fragment(freshPayload.toString()).build()).header("Cache-Control", "no-cache").build())
                }
                else -> throw error
            }
        }
    }
    private fun fresh(id: String, headers: Headers): Snapshot = snapshots.get(id) {
        val chapter = synchronized(readers) { readers[id] } ?: throw SourceFailure(Failure.ACCESS)
        fun load(url: String): String = client.newCall(Request.Builder().url(url).headers(headers).header("Cache-Control", "no-cache").build()).execute().use { it.body!!.string() }
        val data = NextData(load(chapter))
        val token = data.objectValue("deferredMedia")?.text("token") ?: throw SourceFailure(Failure.ACCESS)
        val endpoint = chapter.substringBefore("/ar/").substringBefore("/en/") + "/chapter-deferred-media/$id"
        val target = endpoint.toHttpUrl().newBuilder().addQueryParameter("token", token).build()
        val deferred = json.parseToJsonElement(load(target.toString())).jsonObject["data"]!!.jsonObject
        Snapshot(System.nanoTime(), deferred.number("splitIndex"), deferred.items("maps"))
    }
}
