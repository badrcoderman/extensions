package eu.kanade.tachiyomi.extension.ar.shared

import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class PieceDownloadsTest {
    @Test(timeout = 10000) fun emptyAndHtmlPieceResponsesAreNotMisreportedAsMissingDecoders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(""))
            server.enqueue(MockResponse().setBody("<html>Login required</html>").setHeader("Content-Type", "text/html"))
            val client = OkHttpClient()
            val parent = client.newCall(Request.Builder().url(server.url("/parent")).build())
            listOf(Failure.EMPTY_READER, Failure.ACCESS).forEach { expected ->
                PieceDownloads(client, Headers.headersOf(), listOf(server.url("/piece").toString()), parent).use {
                    try { it.bytes(0); fail() }
                    catch (error: SourceFailure) { assertEquals(expected, error.failure) }
                }
            }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun missingHeapIntrospectionUsesABoundedCompatibilityFallback() {
        assertEquals(8 * 1024 * 1024, PageBudget.capacityFor { throw NoSuchMethodError("Fixture") })
        assertEquals(8 * 1024 * 1024, PageBudget.capacityFor { 0L })
        assertEquals(16 * 1024 * 1024, PageBudget.capacityFor { 256L * 1024 * 1024 })
        assertEquals(32 * 1024 * 1024, PageBudget.capacityFor { Long.MAX_VALUE })
    }
    @Test fun allThreePreviouslyVerifiedLiveGeometriesRemainAccepted() {
        val body = javaClass.classLoader!!.getResourceAsStream("procomic/geometry.json")!!.bufferedReader().use { it.readText() }
        val plans = siteJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonArray
        assertEquals(3, plans.size)
        plans.forEach { entry ->
            val map = entry as kotlinx.serialization.json.JsonObject
            val count = (map["rects"] as kotlinx.serialization.json.JsonArray).size
            val indices = (0 until count).joinToString(",")
            val pieces = (0 until count).joinToString(",") { "\"https://fixture.invalid/$it\"" }
            val input = """{"data":{"map":{"dim":${map["dim"]},"pieces":[$pieces],"order":[$indices],"rects":${map["rects"]}}}}"""
            assertEquals(count, PiecePlan.parse(input).rects.size)
        }
    }
    @Test(timeout = 10000) fun canvasBudgetWaitsAreCancelableAndDoNotLeakCredits() {
        val budget = PageBudget(10)
        val client = OkHttpClient()
        fun parent() = client.newCall(Request.Builder().url("http://127.0.0.1/unused").build())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val waitingParent = parent()
            budget.use(8, parent()) {
                val started = CountDownLatch(1)
                val waiting = pool.submit<Boolean> {
                    started.countDown()
                    budget.use(5, waitingParent) { true }
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                waitingParent.cancel()
                try { waiting.get(1, TimeUnit.SECONDS); fail() }
                catch (error: ExecutionException) { assertEquals("Canceled", error.cause?.message) }
            }
            assertTrue(budget.use(10, parent()) { true })
            try { budget.use(11, parent()) { fail() }; fail() }
            catch (error: SourceFailure) { assertEquals(Failure.IMAGE_LIMIT, error.failure) }
            try { budget.use(10, parent()) { throw IOException("Fixture") }; fail() }
            catch (_: IOException) { }
            assertTrue(budget.use(10, parent()) { true })
        } finally { pool.shutdownNow() }
    }
    @Test(timeout = 10000) fun aConnectionLimitChangedOnTheSameClientAppliesToNewTransfers() {
        MockWebServer().use { server ->
            repeat(5) { server.enqueue(MockResponse().setBody("piece").setBodyDelay(50, TimeUnit.MILLISECONDS)) }
            val active = AtomicInteger(); val peak = AtomicInteger()
            val client = OkHttpClient.Builder().eventListenerFactory {
                object : EventListener() {
                    override fun callStart(call: Call) { val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) } }
                    override fun callEnd(call: Call) { active.decrementAndGet() }
                    override fun callFailed(call: Call, ioe: IOException) { active.decrementAndGet() }
                }
            }.build()
            val parent = client.newCall(Request.Builder().url(server.url("/parent")).build())
            PieceDownloads(client, Headers.headersOf(), listOf(server.url("/seed").toString()), parent).use { it.bytes(0) }
            client.dispatcher.maxRequestsPerHost = 1
            peak.set(0)
            PieceDownloads(client, Headers.headersOf(), (0..3).map { server.url("/piece-$it").toString() }, parent).use { batch ->
                repeat(4) { assertEquals("piece", String(batch.bytes(it))) }
            }
            assertEquals(1, peak.get())
        }
    }
    @Test(timeout = 15000) fun oversizedBodiesFailWithoutRetryAndReleaseTransferSlots() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 16L * 1024 * 1024 + 1))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(16 * 1024 * 1024 + 1), 8192))
            val client = OkHttpClient()
            val parent = client.newCall(Request.Builder().url(server.url("/parent")).build())
            for (path in listOf("/declared", "/chunked")) {
                PieceDownloads(client, Headers.headersOf(), listOf(server.url(path).toString()), parent).use {
                    try { it.bytes(0); fail("An oversized body was accepted") }
                    catch (error: SourceFailure) { assertEquals("IMAGE_LIMIT", error.failure.name) }
                }
            }
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setBody("next"))
            PieceDownloads(client, Headers.headersOf(), listOf(server.url("/next").toString()), parent).use {
                assertEquals("next", String(it.bytes(0)))
            }
        }
    }
    @Test fun overlappingOrOversizedMapsAreRejectedBeforeAllocatingBitmaps() {
        val overlap = """{"data":{"map":{"dim":[2,2],"pieces":["a","b"],"order":[0,1],"rects":[{"left":0,"top":0,"width":1,"height":2},{"left":0,"top":0,"width":1,"height":2}]}}}"""
        val huge = """{"data":{"map":{"dim":[50000,50000],"pieces":["a"],"order":[0],"rects":[{"left":0,"top":0,"width":50000,"height":50000}]}}}"""
        for (body in listOf(overlap, huge)) {
            try { PiecePlan.parse(body); fail("Unsafe bitmap plan accepted") }
            catch (_: SourceFailure) { /* Expected validation failure, without allocating a bitmap. */ }
        }
    }
    @Test(timeout = 15000) fun transfersOverlapWithAGlobalLimitAndKeepTheRequestedOrder() {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(request.path!!).setBodyDelay(150, TimeUnit.MILLISECONDS)
            }
            val active = AtomicInteger();val peak = AtomicInteger()
            val client = OkHttpClient.Builder().eventListenerFactory {
                object : EventListener() {
                    override fun callStart(call: Call) { val count = active.incrementAndGet();peak.updateAndGet { maxOf(it,count) } }
                    override fun callEnd(call: Call) { active.decrementAndGet() }
                    override fun callFailed(call: Call, ioe: IOException) { active.decrementAndGet() }
                }
            }.build()
            val pool=Executors.newFixedThreadPool(2)
            try {
                val jobs=(0..1).map { page -> pool.submit<List<String>> {
                    val urls=(0..5).map { server.url("/page-$page-piece-${5-it}").toString() }
                    val parent=client.newCall(Request.Builder().url(server.url("/parent")).build())
                    PieceDownloads(client,Headers.headersOf(),urls,parent).use { batch -> urls.indices.map { String(batch.bytes(it)) } }
                } }
                jobs.forEachIndexed { page, job -> assertEquals((0..5).map { "/page-$page-piece-${5-it}" },job.get(10,TimeUnit.SECONDS)) }
                assertTrue("Transfers did not overlap",peak.get()>=2)
                assertTrue("More than five piece transfers",peak.get()<=5)
            } finally { pool.shutdownNow() }
        }
    }
    @Test(timeout = 10000) fun aLowerHostConnectionLimitIsPreserved() {
        MockWebServer().use { server ->
            repeat(6) { server.enqueue(MockResponse().setBody("piece").setBodyDelay(100,TimeUnit.MILLISECONDS)) }
            val active=AtomicInteger();val peak=AtomicInteger()
            val client=OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost=2 }).eventListenerFactory {
                object:EventListener() {
                    override fun callStart(call:Call) {val count=active.incrementAndGet();peak.updateAndGet {maxOf(it,count)}}
                    override fun callEnd(call:Call) {active.decrementAndGet()}
                    override fun callFailed(call:Call,ioe:IOException) {active.decrementAndGet()}
                }
            }.build()
            val parent=client.newCall(Request.Builder().url(server.url("/parent")).build())
            PieceDownloads(client,Headers.headersOf(),(0..5).map {server.url("/piece-$it").toString()},parent).use { batch -> repeat(6) {assertEquals("piece",String(batch.bytes(it)))} }
            assertTrue(peak.get()<=2)
        }
    }
    @Test(timeout = 15000) fun severalPagesKeepProgressingWithOnlyOneHostSlot() {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(request.path!!).setBodyDelay(10, TimeUnit.MILLISECONDS)
            }
            val client = OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 1 }).build()
            val pool = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)
            try {
                val jobs = (0..3).map { page -> pool.submit<List<String>> {
                    start.await()
                    val urls = (0..7).map { server.url("/page-$page-piece-$it").toString() }
                    val parent = client.newCall(Request.Builder().url(server.url("/parent")).build())
                    PieceDownloads(client, Headers.headersOf(), urls, parent).use { batch -> urls.indices.map { String(batch.bytes(it)) } }
                } }
                start.countDown()
                jobs.forEachIndexed { page, job -> assertEquals((0..7).map { "/page-$page-piece-$it" }, job.get(10, TimeUnit.SECONDS)) }
            } finally { pool.shutdownNow() }
        }
    }
    @Test(timeout = 10000) fun largeChunkedPiecesAreNotTruncatedAtThePrefetchLimit() {
        MockWebServer().use { server ->
            val content="ABCD".repeat(800_000)
            server.enqueue(MockResponse().setChunkedBody(content,8192))
            val client=OkHttpClient();val parent=client.newCall(Request.Builder().url(server.url("/parent")).build())
            PieceDownloads(client,Headers.headersOf(),listOf(server.url("/large").toString()),parent).use { assertArrayEquals(content.toByteArray(),it.bytes(0)) }
        }
    }
    @Test(timeout = 10000) fun aLargeBodyInterruptedAfterThePrefetchWindowAlsoRestartsOnlyOnce() {
        MockWebServer().use { server ->
            val content="x".repeat(5*1024*1024)
            server.enqueue(MockResponse().setBody(content).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody(content))
            server.enqueue(MockResponse().setBody("next"))
            val client=OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost=1 }).build()
            val parent=client.newCall(Request.Builder().url(server.url("/parent")).build())
            PieceDownloads(client,Headers.headersOf(),listOf("/large","/next").map {server.url(it).toString()},parent).use {
                assertArrayEquals(content.toByteArray(),it.bytes(0));assertEquals("next",String(it.bytes(1)))
            }
            assertEquals(3,server.requestCount)
            assertEquals(listOf("/large","/large","/next"),(0..2).map {server.takeRequest().path})
        }
    }
    @Test(timeout = 10000) fun cancellationClosesStalledTransfersAndReleasesSlotsForTheNextChapter() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val client=OkHttpClient();val parent=client.newCall(Request.Builder().url(server.url("/parent")).build())
            PieceDownloads(client,Headers.headersOf(),(0..2).map { server.url("/stall-$it").toString() },parent).use { batch ->
                repeat(3) { assertNotNull(server.takeRequest(3,TimeUnit.SECONDS)) }
                parent.cancel()
                try {batch.bytes(0);fail()} catch(error:IOException) {assertEquals("Canceled",error.message)}
            }
            server.enqueue(MockResponse().setBody("next chapter"))
            val next=client.newCall(Request.Builder().url(server.url("/parent-2")).build())
            PieceDownloads(client,Headers.headersOf(),listOf(server.url("/next").toString()),next).use { assertEquals("next chapter",String(it.bytes(0))) }
        }
    }
    @Test(timeout = 10000) fun interruptedImageBodiesRestartOnceAndRateLimitsAreNotReplayed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("short").setHeader("Content-Length",100).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            server.enqueue(MockResponse().setBody("complete"))
            val client=OkHttpClient();val parent=client.newCall(Request.Builder().url(server.url("/parent")).build())
            PieceDownloads(client,Headers.headersOf(),listOf(server.url("/piece").toString()),parent).use { assertEquals("complete",String(it.bytes(0))) }
            assertEquals(2,server.requestCount)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After",120))
            PieceDownloads(client,Headers.headersOf(),listOf(server.url("/limited").toString()),parent).use {
                try {it.bytes(0);fail()} catch(error:SourceFailure) {assertEquals(Failure.RATE_LIMIT,error.failure)}
            }
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun malformedPlansFailBeforeStartingTransfersAndGeometryPreservesOrder() {
        val body="""{"data":{"map":{"dim":[100,50],"pieces":["a","b"],"order":[1,0],"rects":[{"left":0,"top":0,"width":50,"height":50},{"left":50,"top":0,"width":50,"height":50}]}}}"""
        assertEquals(listOf("b","a"),PiecePlan.parse(body).urls)
        try {PiecePlan.parse(body.replace("[1,0]","[2,0]"));fail()} catch(error:SourceFailure) {assertEquals(Failure.CHANGED,error.failure)}
        try {PiecePlan.parse(body.replace("\"width\":50","\"width\":101"));fail()} catch(error:SourceFailure) {assertEquals(Failure.CHANGED,error.failure)}
    }
}
