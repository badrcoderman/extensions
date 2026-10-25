package eu.kanade.tachiyomi.extension.ar.shared

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProComicNetworkTest {
    private fun reply(request: Request, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
    private val virtual = "http://procomic.pieces/".toHttpUrl().newBuilder().fragment("""{"chapterId":123,"plan":{"token":"old","method":"browser_session","cdnPath":"cdn2","pageIndex":3}}""").build()
    @Test fun expiredMapRenewsFromThePublicReaderOnceAndPreservesPageIndex() {
        var pieces = 0
        var readers = 0
        val body = "1:{\"deferredMedia\":{\"token\":\"fresh-reader-session\"}}"
        val flight = "<script>self.__next_f.push(${JsonArray(listOf(JsonPrimitive(1),JsonPrimitive(body)))})</script>"
        val client = ProComicNetwork.build(OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            when {
                request.url.host=="procomic.pieces" -> {
                    pieces++
                    if (pieces==1) throw SourceFailure(Failure.ACCESS)
                    val plan=siteJson.parseToJsonElement(request.url.fragment!!).jsonObject["plan"]!!.jsonObject
                    assertEquals("new-map",plan.text("token"))
                    assertEquals(3,plan.number("pageIndex"))
                    assertEquals("cdn2",plan.text("cdnPath"))
                    reply(request,"image")
                }
                request.url.encodedPath.contains("chapter-deferred-media") -> {
                    assertEquals("/chapter-deferred-media/123", request.url.encodedPath)
                    assertEquals("fresh-reader-session",request.url.queryParameter("token"))
                    reply(request,"""{"data":{"splitIndex":3,"maps":[{"token":"new-map","method":"browser_session"}]}}""")
                }
                else -> {readers++;reply(request,flight)}
            }
        })
        client.newCall(Request.Builder().url("https://procomic.pro/ar/chapter/example-1-123").build()).execute().close()
        client.newCall(Request.Builder().url(virtual).build()).execute().use { assertEquals("image",it.body!!.string()) }
        assertEquals(2,pieces);assertEquals(2,readers)
    }
    @Test fun unsupportedDecoderIsExplainedWithoutRetryingIt() {
        var attempts=0
        val client=ProComicNetwork.build(OkHttpClient.Builder().addInterceptor { attempts++;throw IOException(Failure.DECODER.arabic) })
        try {client.newCall(Request.Builder().url(virtual).build()).execute();fail()}
        catch(error:SourceFailure) {assertEquals(Failure.DECODER,error.failure)}
        assertEquals(1,attempts)
    }
    @Test fun repeatedExpiredSignedImageStopsAfterOneRenewal() {
        var attempts=0
        val client=ProComicNetwork.build(OkHttpClient.Builder().addInterceptor {attempts++;throw SourceFailure(Failure.ACCESS)})
        try {client.newCall(Request.Builder().url("http://procomic.signed/?url=example").build()).execute();fail()}
        catch(error:SourceFailure) {assertEquals(Failure.ACCESS,error.failure)}
        assertEquals(2,attempts)
    }
}
