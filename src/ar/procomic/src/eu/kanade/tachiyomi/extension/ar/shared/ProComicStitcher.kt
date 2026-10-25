package eu.kanade.tachiyomi.extension.ar.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/** Retains upstream decoding, rectangle order and JPEG quality; overlaps only image transfers. */
object ProComicStitcher {
    @JvmStatic fun stitch(client: OkHttpClient, headers: Headers, baseUrl: String, url: HttpUrl, parent: Call): Buffer = guarded {
        if (parent.isCanceled()) throw java.io.IOException("Canceled")
        val payload = siteJson.parseToJsonElement(url.fragment ?: throw SourceFailure(Failure.CHANGED)).jsonObject
        val id = payload.requiredText("chapterId").toLongOrNull() ?: throw SourceFailure(Failure.CHANGED)
        val endpoint = baseUrl.toHttpUrl().newBuilder().encodedPath("/chapter-map-proxy-plan/$id").query(null).fragment(null).build()
        val request = Request.Builder().url(endpoint)
            .headers(headers).post(payload["plan"]!!.toString().toRequestBody("application/json".toMediaType()))
            .tag(ReadOnlyRequest::class.java, ReadOnlyRequest()).build()
        val call = client.newCall(request)
        val watcher = PieceDownloads.cancelWith(parent) { call.cancel() }
        val plan = try {
            call.execute().use { response ->
                check(response.isSuccessful) { ArabicErrors.http(response.code).message.orEmpty() }
                PiecePlan.parse(response.body!!.string())
            }
        } finally { watcher.cancel(false) }
        val page = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            PieceDownloads(client, headers, plan.urls, parent).use { downloads ->
                plan.rects.forEachIndexed { index, rect ->
                    if (parent.isCanceled()) throw java.io.IOException("Canceled")
                    val piece = NativeImageDecoder.decode(downloads.bytes(index))
                    try { canvas.drawBitmap(piece, Rect(0, 0, piece.width, piece.height), Rect(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]), null) }
                    finally { piece.recycle() }
                }
            }
            Buffer().also { check(page.compress(Bitmap.CompressFormat.JPEG, 90, it.outputStream())) }
        } finally { page.recycle() }
    }
}

internal class PiecePlan(val width: Int, val height: Int, val urls: List<String>, val rects: List<List<Int>>) {
    companion object {
        fun parse(body: String): PiecePlan {
            val data = siteJson.parseToJsonElement(body).jsonObject["data"]!!.jsonObject["map"]!!.jsonObject
            val dim = data.items("dim").map { it.jsonPrimitive.content.toInt() }
            if (dim.size != 2 || dim.any { it <= 0 }) throw SourceFailure(Failure.CHANGED)
            val pieces = data.items("pieces").map { it.jsonPrimitive.content }
            val order = data.items("order").map { it.jsonPrimitive.content.toInt() }
            val rects = data.items("rects").map { item -> item.jsonObject.let { r -> listOf("left", "top", "width", "height").map { r.requiredText(it).toInt() } } }
            if (order.size != rects.size || order.isEmpty() || order.any { it !in pieces.indices } || rects.any {
                it[0] < 0 || it[1] < 0 || it[2] <= 0 || it[3] <= 0 || it[0].toLong() + it[2] > dim[0] || it[1].toLong() + it[3] > dim[1]
            }) throw SourceFailure(Failure.CHANGED)
            return PiecePlan(dim[0], dim[1], order.map { pieces[it] }, rects)
        }
    }
}
