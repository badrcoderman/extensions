package eu.kanade.tachiyomi.extension.ar.shared

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RecoveryTest {
    private fun response(code: Int = 200, body: String = "ok") = MockResponse().setResponseCode(code).setBody(body)
    @Test fun temporaryFailureRetriesAreBounded() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(response(503)) }
            val pauses = mutableListOf<Long>()
            val client = OkHttpClient.Builder().addInterceptor(RecoveryInterceptor(sleep = { pauses.add(it) })).build()
            try { client.newCall(Request.Builder().url(server.url("/")).build()).execute(); fail() }
            catch (error: SourceFailure) { assertEquals(Failure.SERVER, error.failure) }
            assertEquals(3, server.requestCount)
            assertEquals(listOf(250L, 500L), pauses)
        }
    }
    @Test fun hostChallengeHandlingRunsBeforeRecoveryMapsTheResponse() {
        MockWebServer().use { server ->
            server.enqueue(response(403));server.enqueue(response())
            var resolved = false
            val host = OkHttpClient.Builder().addInterceptor { chain ->
                val first = chain.proceed(chain.request())
                if (first.code == 403) {
                    first.close();resolved = true
                    chain.proceed(chain.request().newBuilder().header("Resolved", "yes").build())
                } else first
            }.build()
            val client = recoveryClient(host) { emptyList() }
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertEquals("ok", it.body!!.string()) }
            assertTrue(resolved);assertEquals(2,server.requestCount)
        }
    }
    @Test fun ordinaryPostsAreNotReplayed() {
        MockWebServer().use { server ->
            server.enqueue(response(503))
            val client = OkHttpClient.Builder().addInterceptor(RecoveryInterceptor(sleep = {})).build()
            try { client.newCall(Request.Builder().url(server.url("/")).post("{}".toRequestBody()).build()).execute(); fail() }
            catch (error: SourceFailure) { assertEquals(Failure.SERVER, error.failure) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun readOnlyPostsCanRecoverAndLongRetryAfterIsRespected() {
        MockWebServer().use { server ->
            server.enqueue(response(503)); server.enqueue(response())
            val client = OkHttpClient.Builder().addInterceptor(RecoveryInterceptor(sleep = {})).build()
            val request = Request.Builder().url(server.url("/")).post("{}".toRequestBody()).tag(ReadOnlyRequest::class.java, ReadOnlyRequest()).build()
            client.newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
            server.enqueue(response(429).setHeader("Retry-After", "120"))
            try { client.newCall(request).execute(); fail() }
            catch (error: SourceFailure) { assertEquals(Failure.RATE_LIMIT, error.failure) }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun expiredImageRefreshesOnceAndConcurrentPagesReuseTheSnapshot() {
        MockWebServer().use { server ->
            var refreshes = 0
            val client = OkHttpClient.Builder().addInterceptor(RecoveryInterceptor(refresh = {
                refreshes++; listOf(server.url("/fresh-0").toString(), server.url("/fresh-1").toString())
            }, sleep = {})).build()
            for (index in 0..1) {
                server.enqueue(response(403)); server.enqueue(response().setHeader("Content-Type", "image/jpeg"))
                val request = Request.Builder().url(server.url("/expired")).tag(ImageReference::class.java, ImageReference("chapter", index)).build()
                client.newCall(request).execute().use { assertTrue(it.isSuccessful) }
                assertEquals("/expired", server.takeRequest().path)
                val renewed = server.takeRequest()
                assertEquals("/fresh-$index", renewed.path)
                assertEquals("no-cache", renewed.getHeader("Cache-Control"))
            }
            assertEquals(1, refreshes)
        }
    }
    @Test fun htmlImageRefreshCannotLoop() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(response().setHeader("Content-Type", "text/html")) }
            var count = 0
            val client = OkHttpClient.Builder().addInterceptor(RecoveryInterceptor(refresh = { count++; listOf(server.url("/fresh").toString()) })).build()
            try { client.newCall(Request.Builder().url(server.url("/old")).tag(ImageReference::class.java, ImageReference("chapter", 0)).build()).execute(); fail() }
            catch (error: SourceFailure) { assertEquals(Failure.ACCESS, error.failure) }
            assertEquals(1, count); assertEquals(2, server.requestCount)
        }
    }
    @Test fun cancellationStopsTheRetry() {
        MockWebServer().use { server ->
            server.enqueue(response(503))
            lateinit var call: okhttp3.Call
            val client = OkHttpClient.Builder().addInterceptor(RecoveryInterceptor(sleep = { call.cancel() })).build()
            call = client.newCall(Request.Builder().url(server.url("/")).build())
            try { call.execute(); fail() } catch (error: IOException) { assertEquals("Canceled", error.message) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun expiredNonceIsRenewedOnlyOnce() {
        MockWebServer().use { server ->
            server.enqueue(response(403));server.enqueue(response(403))
            var renewals = 0
            val client = OkHttpClient.Builder().addInterceptor(RecoveryInterceptor()).build()
            val request = Request.Builder().url(server.url("/")).tag(SessionRequest::class.java, SessionRequest { renewals++; it }).build()
            try { client.newCall(request).execute();fail() } catch (error: SourceFailure) { assertEquals(Failure.ACCESS,error.failure) }
            assertEquals(1,renewals);assertEquals(2,server.requestCount)
        }
    }
    @Test fun errorsKeepCancellationAndIdentifyCompatibilityFailures() {
        val canceled = IOException("Canceled")
        assertSame(canceled, ArabicErrors.translate(canceled))
        assertEquals(Failure.TIMEOUT, (ArabicErrors.translate(SocketTimeoutException()) as SourceFailure).failure)
        assertEquals(Failure.COMPATIBILITY, (ArabicErrors.translate(NoClassDefFoundError()) as SourceFailure).failure)
    }
    @Test fun repeatedChapterPagesStopAndMalformedNuxtReferencesFailClearly() {
        val guard = PaginationGuard()
        guard.accept(listOf("a", "b"), true)
        try { guard.accept(listOf("a", "b"),true);fail() } catch (error: SourceFailure) { assertEquals(Failure.PAGINATION,error.failure) }
        val doc = Jsoup.parse("""<script id="__NUXT_DATA__">[{"data":1},{"chapter":999}]</script>""")
        try { NuxtData.data(doc);fail() } catch (error: SourceFailure) { assertEquals(Failure.CHANGED,error.failure) }
        val cyclic = Jsoup.parse("""<script id="__NUXT_DATA__">[{"data":1,"irrelevant":999},{"self":1}]</script>""")
        assertEquals(kotlinx.serialization.json.JsonNull, NuxtData.data(cyclic)["self"])
    }
    @Test fun arabicSearchAndVerifiedFiltersKeepTheirParameters() {
        assertEquals(searchKey("إمـبِراطور"), searchKey("امبراطور"))
        assertEquals("solo leveling",cleanQuery(" solo\u202E  leveling \n"))
        val filters = Man9aSite.searchFilters()
        filters.filterIsInstance<Choice>().first { it.key=="type" }.state=1
        filters.filterIsInstance<Choice>().first { it.key=="status" }.state=2
        val url = Man9aSite.searchUrl(2,"solo", filters)
        assertTrue(url.contains("type=manga")); assertTrue(url.contains("status=completed"));assertTrue(url.contains("page=2"))
        assertTrue(Man9aSite.listing(1,"",false).contains("sort=updated"))
        val lek = ManhwaLekSite.searchFilters()
        lek.filterIsInstance<Choice>().first { it.key == "type" }.state = 2
        val json = """[{"slug":"sample","title":"إمبراطور","type":"مانهوا كورية","status":"مستمر"},{"slug":"other","title":"Other","type":"مانها صيني"}]"""
        val results = ManhwaLekSite.catalog(json, ManhwaLekSite.searchUrl(1,"امبراطور",lek))
        assertEquals(1,results.series.size)
        assertEquals("/series/sample",results.series.single().url)
    }
}
