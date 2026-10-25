package eu.kanade.tachiyomi.extension.ar.shared

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class SessionRequest(val renew: (Request) -> Request)
internal class ReadOnlyRequest
internal class ImageReference(val chapterUrl: String, val index: Int)

/** Bounded retries; refresh the chapter once when an image session has expired. */
internal class RecoveryInterceptor(
    private val refresh: ((String) -> List<String>)? = null,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.nanoTime() },
) : Interceptor {
    private val refreshed = RefreshCache<List<String>>(8, TimeUnit.SECONDS.toNanos(10), now)
    private fun fresh(chapter: String): List<String> = refreshed.get(chapter) {
        guarded { refresh!!(chapter) }.also { if (it.isEmpty()) throw SourceFailure(Failure.EMPTY_READER) }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val reference = request.tag(ImageReference::class.java)
        val mayRetry = request.method in listOf("GET", "HEAD") || request.tag(ReadOnlyRequest::class.java) != null
        var retries = 0
        var didRefresh = false
        var renewedSession = false
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            val response = try {
                chain.proceed(request)
            } catch (error: IOException) {
                val translated = ArabicErrors.translate(error)
                val temporary = translated is SourceFailure && translated.failure in listOf(Failure.NETWORK, Failure.TIMEOUT)
                if (mayRetry && retries < 2 && temporary && !ArabicErrors.canceled(error) && error !is SourceFailure) {
                    pause(chain, 250L * (1L shl retries++))
                    continue
                }
                throw translated
            }
            val session = request.tag(SessionRequest::class.java)
            if (!renewedSession && session != null && (response.code == 403 ||
                (response.isSuccessful && response.peekBody(128).string().trim() in listOf("-1", "0")))) {
                response.close()
                renewedSession = true
                request = guarded { session.renew(request) }
                continue
            }
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val badImage = reference != null && response.isSuccessful &&
                (contentType.contains("text/html") || contentType.contains("application/json") || contentType.contains("text/plain"))
            if (!didRefresh && refresh != null && reference != null &&
                (response.code in listOf(401, 403, 410) || badImage)) {
                response.close()
                didRefresh = true
                val image = fresh(reference.chapterUrl).getOrNull(reference.index) ?: throw SourceFailure(Failure.EMPTY_READER)
                request = request.newBuilder().url(image).header("Cache-Control", "no-cache").build()
                continue
            }
            if (mayRetry && retries < 2 && response.code in listOf(408, 429, 500, 502, 503, 504)) {
                val seconds = response.header("Retry-After")?.toLongOrNull()
                // Do not retry earlier than a long or HTTP-date Retry-After requested by the site.
                val delay = if (response.header("Retry-After") != null) {
                    seconds?.takeIf { it in 0..2 }?.times(1000)
                } else 250L * (1L shl retries)
                if (delay != null) {
                    response.close()
                    retries++
                    pause(chain, delay)
                    continue
                }
            }
            if (!response.isSuccessful && !response.isRedirect) {
                val error = ArabicErrors.http(response.code)
                response.close()
                throw error
            }
            if (badImage) {
                response.close()
                throw SourceFailure(Failure.ACCESS)
            }
            return response
        }
    }

    private fun pause(chain: Interceptor.Chain, milliseconds: Long) {
        if (chain.call().isCanceled()) throw IOException("Canceled")
        try {
            sleep(milliseconds)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Canceled")
        }
    }
}

// Recovery must surround the host's challenge handler so a 403 can be resolved first.
internal fun recoveryClient(base: okhttp3.OkHttpClient, refresh: (String) -> List<String>): okhttp3.OkHttpClient {
    val builder = base.newBuilder()
    val handlers = builder.interceptors()
    val position = handlers.indexOfFirst { it.javaClass.simpleName == "UncaughtExceptionInterceptor" }.let { if (it < 0) 0 else it + 1 }
    handlers.add(position, RecoveryInterceptor(refresh))
    return builder.build()
}

/** Same-key work is shared; network work and waiting never hold the cache lock. */
internal class RefreshCache<T>(
    private val capacity: Int,
    private val lifetime: Long,
    private val now: () -> Long = { System.nanoTime() },
) {
    private class Entry<T>(val value: T, val time: Long)
    private val lock = Any()
    private val completed = LinkedHashMap<String, Entry<T>>(capacity, .75f, true)
    private val loading = mutableMapOf<String, java.util.concurrent.FutureTask<T>>()
    fun get(key: String, load: () -> T): T {
        var owner = false
        val task = synchronized(lock) {
            completed[key]?.let {
                if (now() - it.time < lifetime) return it.value
                completed.remove(key)
            }
            loading[key] ?: java.util.concurrent.FutureTask<T> { load() }.also {
                owner = true
                loading[key] = it
            }
        }
        try {
            if (owner) task.run()
            val value = task.get()
            if (owner) synchronized(lock) {
                completed[key] = Entry(value, now())
                while (completed.size > capacity) completed.remove(completed.keys.first())
            }
            return value
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Canceled")
        } finally {
            if (owner) synchronized(lock) { loading.remove(key) }
        }
    }
}
