package eu.kanade.tachiyomi.extension.ar.shared

import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Three pieces per page, five transfers total, and the host's lower connection limit is respected. */
internal class PieceDownloads(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val urls: List<String>,
    private val parent: Call,
) : Closeable {
    private val clientPermits = budget(client)
    private val lock = Any()
    private var closed = false
    private val futures = mutableMapOf<Int, FutureTask<Lease>>()
    private var nextToLaunch = 0
    private var awaitingPermit = false
    private val retries = mutableMapOf<Int, AtomicBoolean>()
    private val calls = mutableSetOf<Call>()
    private val leases = mutableSetOf<Lease>()
    private var watcher: ScheduledFuture<*>? = null
    private inner class Lease(val response: Response, val call: Call) : Closeable {
        private val released = AtomicBoolean()
        override fun close() { finish(false) }
        fun retainBudgetForRetry(): Boolean = finish(true)
        private fun finish(retain: Boolean): Boolean {
            if (!released.compareAndSet(false, true)) return false
            var retained = false
            try { response.close(); retained = retain } finally {
                synchronized(lock) { leases.remove(this); calls.remove(call) }
                if (!retained) { permits.release(); clientPermits.release() }
            }
            return retained
        }
    }
    init {
        watcher = cancelWith(parent) { close() }
        synchronized(lock) { if (closed) watcher?.cancel(false) }
        repeat(minOf(WINDOW, urls.size)) { schedule(it) }
    }
    private fun canceled(): Boolean = synchronized(lock) { closed } || parent.isCanceled() || Thread.currentThread().isInterrupted
    private fun schedule(index: Int) = synchronized(lock) {
        if (!closed && index < urls.size && index !in futures) {
            val retry = AtomicBoolean()
            retries[index] = retry
            futures[index] = FutureTask<Lease> {
                fetch(urls[index], retry) {
                    synchronized(lock) { awaitingPermit = false; launchReady() }
                }
            }
            launchReady()
        }
    }
    // Admit a page's pieces in order so ahead buffers cannot starve its next required piece.
    // Once a piece owns its permits, the following transfer may overlap its network read.
    private fun launchReady() {
        if (closed || awaitingPermit) return
        val task = futures[nextToLaunch] ?: return
        awaitingPermit = true
        nextToLaunch++
        workers.execute(task)
    }
    private fun canRestart(error: Throwable, retry: AtomicBoolean) = error is IOException &&
        error !is SourceFailure && error !is javax.net.ssl.SSLException && !canceled() &&
        !ArabicErrors.canceled(error) && retry.compareAndSet(false, true)
    private fun fetch(url: String, retry: AtomicBoolean, reserved: Boolean = false, onAdmitted: (() -> Unit)? = null): Lease {
        var admitted = false
        var useReservation = reserved
        while (true) {
            var clientPermit = useReservation
            var globalPermit = useReservation
            useReservation = false
            var call: Call? = null
            var lease: Lease? = null
            try {
                if (!clientPermit) {
                    while (!clientPermits.tryAcquire(50, TimeUnit.MILLISECONDS)) if (canceled()) throw IOException("Canceled")
                    clientPermit = true
                }
                if (!globalPermit) {
                    while (!permits.tryAcquire(50, TimeUnit.MILLISECONDS)) if (canceled()) throw IOException("Canceled")
                    globalPermit = true
                }
                if (canceled()) throw IOException("Canceled")
                if (!admitted) { admitted = true; onAdmitted?.invoke() }
                call = client.newCall(Request.Builder().url(url).headers(headers).build())
                synchronized(lock) {
                    if (closed) throw IOException("Canceled")
                    calls.add(call)
                }
                val response = call.execute()
                lease = Lease(response, call)
                synchronized(lock) {
                    if (closed) throw IOException("Canceled")
                    leases.add(lease)
                }
                if (!response.isSuccessful) throw ArabicErrors.http(response.code)
                // Do not read whole large pieces ahead: leave excess bytes in the response stream.
                response.body?.source()?.request(BUFFER_LIMIT + 1) ?: throw SourceFailure(Failure.EMPTY_READER)
                if (canceled()) throw IOException("Canceled")
                return lease
            } catch (error: Throwable) {
                if (lease != null && canRestart(error, retry) && lease.retainBudgetForRetry()) {
                    useReservation = true
                    continue
                }
                if (lease != null) lease.close() else {
                    call?.cancel()
                    synchronized(lock) { calls.remove(call) }
                    if (globalPermit) permits.release()
                    if (clientPermit) clientPermits.release()
                }
                throw error
            }
        }
    }
    fun bytes(index: Int): ByteArray {
        if (canceled()) throw IOException("Canceled")
        val future = synchronized(lock) { futures[index] } ?: throw IOException("Canceled")
        val retry = synchronized(lock) { retries[index] } ?: throw IOException("Canceled")
        var lease: Lease? = null
        var success = false
        try {
            while (lease == null) {
                if (canceled()) throw IOException("Canceled")
                try { lease = future.get(50, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* Check cancellation again. */ }
            }
            while (true) {
                try { return lease!!.response.body!!.bytes().also { success = true } }
                catch (error: IOException) {
                    if (!canRestart(error, retry)) throw error
                    if (!lease!!.retainBudgetForRetry()) throw IOException("Canceled")
                    lease = null
                    lease = fetch(urls[index], retry, reserved = true)
                }
            }
        } catch (error: ExecutionException) {
            if (canceled()) throw IOException("Canceled")
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Canceled")
        } catch (_: CancellationException) {
            throw IOException("Canceled")
        } finally {
            lease?.close()
            synchronized(lock) { futures.remove(index); retries.remove(index) }
            if (success) schedule(index + WINDOW) else close()
        }
    }
    override fun close() {
        val activeCalls: List<Call>
        val activeLeases: List<Lease>
        val activeFutures: List<Future<Lease>>
        synchronized(lock) {
            if (closed) return
            closed = true
            activeCalls = calls.toList()
            activeLeases = leases.toList()
            activeFutures = futures.values.toList()
            futures.clear()
            retries.clear()
        }
        watcher?.cancel(false)
        activeCalls.forEach { it.cancel() }
        activeFutures.forEach { it.cancel(true) }
        activeLeases.forEach { it.close() }
    }
    companion object {
        fun cancelWith(parent: Call, cancel: () -> Unit): ScheduledFuture<*> =
            cancellation.scheduleWithFixedDelay({ if (parent.isCanceled()) cancel() }, 0, 100, TimeUnit.MILLISECONDS)
        private const val WINDOW = 3
        private const val BUFFER_LIMIT = 2L * 1024 * 1024
        private val budgets = java.util.WeakHashMap<OkHttpClient, Semaphore>()
        @Synchronized private fun budget(client: OkHttpClient) = budgets.getOrPut(client) {
            Semaphore(minOf(5, client.dispatcher.maxRequestsPerHost), true)
        }
        private val permits = Semaphore(5, true)
        private val workers = (Executors.newFixedThreadPool(5) { task -> Thread(task, "ProComic image pieces").apply { isDaemon = true } } as ThreadPoolExecutor).apply {
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
        private val cancellation = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "ProComic cancellation").apply { isDaemon = true } }.apply {
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }
}
