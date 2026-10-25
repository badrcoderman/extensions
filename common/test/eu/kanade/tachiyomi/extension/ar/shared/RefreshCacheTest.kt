package eu.kanade.tachiyomi.extension.ar.shared

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class RefreshCacheTest {
    @Test(timeout = 5000) fun slowRefreshDoesNotBlockAnotherChapterOrCacheHit() {
        val cache=RefreshCache<String>(8,TimeUnit.SECONDS.toNanos(10))
        val entered=CountDownLatch(1);val finish=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        assertEquals("cached",cache.get("cached") {"cached"})
        try {
            val slow=pool.submit<String> {cache.get("slow") {entered.countDown();finish.await();"slow"}}
            assertTrue(entered.await(1,TimeUnit.SECONDS))
            assertEquals("fast",pool.submit<String> {cache.get("fast") {"fast"}}.get(1,TimeUnit.SECONDS))
            assertEquals("cached",pool.submit<String> {cache.get("cached") {error("Cache miss")}}.get(1,TimeUnit.SECONDS))
            finish.countDown();assertEquals("slow",slow.get(1,TimeUnit.SECONDS))
        } finally {finish.countDown();pool.shutdownNow()}
    }
    @Test(timeout = 5000) fun sameChapterIsFetchedOnceForConcurrentReadersAndFailuresAreNotCached() {
        val cache=RefreshCache<String>(8,TimeUnit.SECONDS.toNanos(10));val loads=AtomicInteger()
        val entered=CountDownLatch(1);val finish=CountDownLatch(1);val pool=Executors.newFixedThreadPool(4)
        try {
            val jobs=(0..3).map {pool.submit<String> {cache.get("same") {loads.incrementAndGet();entered.countDown();finish.await();"fresh"}}}
            assertTrue(entered.await(1,TimeUnit.SECONDS));finish.countDown()
            jobs.forEach {assertEquals("fresh",it.get(1,TimeUnit.SECONDS))};assertEquals(1,loads.get())
            try {cache.get("bad") {error("Failed refresh")};fail()} catch(_:IllegalStateException) {}
            assertEquals("recovered",cache.get("bad") {"recovered"})
        } finally {finish.countDown();pool.shutdownNow()}
    }
    @Test fun cacheExpiryStartsAfterTheLoadAndOldEntriesAreEvicted() {
        var now=0L;val cache=RefreshCache<String>(2,10,{now})
        cache.get("a") {now=100;"a"}
        now=109;assertEquals("a",cache.get("a") {error("Expired early")})
        now=110;assertEquals("new",cache.get("a") {"new"})
        cache.get("b") {"b"};cache.get("c") {"c"}
        assertEquals("evicted",cache.get("a") {"evicted"})
    }
}
