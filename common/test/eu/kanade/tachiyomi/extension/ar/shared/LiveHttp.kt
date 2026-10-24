package eu.kanade.tachiyomi.extension.ar.shared

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal object LiveHttp {
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    fun get(url: String, referer: String): String = execute(Request.Builder().url(url).header("Referer", referer)).use { it.body!!.string() }

    fun post(url: String, referer: String, body: String): String = execute(
        Request.Builder().url(url).header("Referer", referer).header("Origin", referer.trimEnd('/'))
            .post(body.toRequestBody("application/json".toMediaType())),
    ).use { it.body!!.string() }

    fun image(url: String, referer: String) {
        execute(Request.Builder().url(url).header("Referer", referer)).use { response ->
            check(response.header("Content-Type")?.startsWith("image/") == true) { "Image URL did not return an image" }
            val bytes = response.body!!.bytes()
            check(bytes.size > 100) { "Image is empty" }
            // The JDK does not include a WebP codec, so check its container signature.
            if (response.header("Content-Type")?.contains("webp") == true) {
                check(String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "RIFF")
                check(String(bytes.copyOfRange(8, 12), Charsets.US_ASCII) == "WEBP")
            } else if (response.header("Content-Type")?.contains("jpeg") == true) {
                check(bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) { "Invalid JPEG signature" }
                check(bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "Truncated JPEG image" }
            } else {
                check(bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))) { "Unexpected image format" }
            }
        }
    }

    private fun execute(request: Request.Builder) = client.newCall(
        request.header("User-Agent", "Mozilla/5.0").header("Cache-Control", "no-cache").build(),
    ).execute().also { response -> check(response.isSuccessful) { "HTTP ${response.code}: ${response.request.url}" } }
}
