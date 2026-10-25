package eu.kanade.tachiyomi.extension.ar.shared

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class Failure(val arabic: String) {
    ACCESS("يتطلب هذا المحتوى تسجيل الدخول أو صلاحية وصول. افتحه في عرض الويب للتحقق."),
    UNAVAILABLE("هذا المحتوى غير متاح أو حُذف من الموقع."),
    PREPARING("الفصل قيد التجهيز على الموقع. حاول مجددًا بعد قليل."),
    RATE_LIMIT("طلبات كثيرة إلى الموقع. انتظر قليلًا ثم حاول مجددًا."),
    SERVER("الموقع غير متاح مؤقتًا. حاول مجددًا بعد قليل."),
    NETWORK("تعذر الاتصال بالموقع. تحقق من اتصال الإنترنت ثم حاول مجددًا."),
    TIMEOUT("انتهت مهلة الاتصال بالموقع. حاول مجددًا."),
    CERTIFICATE("تعذر إنشاء اتصال آمن بالموقع. تحقق من تاريخ الجهاز وحدّث التطبيق."),
    CHANGED("استجابة الموقع غير متوقعة. حدّث الإضافة أو افتح المحتوى في عرض الويب."),
    EMPTY_READER("لم يُرجع الموقع صورًا قابلة للقراءة. افتح الفصل في عرض الويب للتحقق."),
    TEXT_CHAPTER("هذا فصل نصي. افتحه على الموقع لقراءته."),
    PAGINATION("كرر الموقع صفحة الفصول أو توقف تحميلها. حاول مجددًا لتجنب قائمة ناقصة."),
    DECODER("تعذر فك ترميز صور الفصل في هذا التطبيق. حدّث التطبيق أو افتح الفصل في عرض الويب."),
    IMAGE_LIMIT("حجم صور الفصل يتجاوز الحد الآمن في الإضافة. افتحه في عرض الويب."),
    COMPATIBILITY("هذه الميزة غير مدعومة في إصدار التطبيق الحالي. حدّث التطبيق والإضافة."),
}

class SourceFailure(val failure: Failure, cause: Throwable? = null) : IOException(failure.arabic, cause)

internal object ArabicErrors {
    fun http(code: Int): SourceFailure = SourceFailure(when (code) {
        401, 403 -> Failure.ACCESS
        404, 410 -> Failure.UNAVAILABLE
        408, 504 -> Failure.TIMEOUT
        429 -> Failure.RATE_LIMIT
        in 500..599 -> Failure.SERVER
        else -> Failure.CHANGED
    })

    fun canceled(error: Throwable): Boolean = error is InterruptedException ||
        (error is InterruptedIOException && error !is SocketTimeoutException) ||
        error.message.equals("Canceled", true) || Thread.currentThread().isInterrupted

    fun translate(error: Throwable): Throwable {
        if (error is SourceFailure || canceled(error)) return error
        Failure.values().firstOrNull { it.arabic == error.message }?.let { return SourceFailure(it, error) }
        val failure = when (error) {
            is SocketTimeoutException -> Failure.TIMEOUT
            is SSLException -> Failure.CERTIFICATE
            is UnknownHostException, is IOException -> Failure.NETWORK
            is LinkageError -> Failure.COMPATIBILITY
            else -> Failure.CHANGED
        }
        return SourceFailure(failure, error)
    }
}

internal inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (error: Exception) {
    throw ArabicErrors.translate(error)
} catch (error: LinkageError) {
    throw ArabicErrors.translate(error)
}

internal class PaginationGuard(private val maxPages: Int = 1000) {
    private val seen = mutableSetOf<String>()
    private var pages = 0
    fun accept(keys: List<String>, hasNext: Boolean) {
        val newItems = keys.count { seen.add(it) }
        if (++pages > maxPages || (hasNext && (keys.isEmpty() || newItems == 0))) throw SourceFailure(Failure.PAGINATION)
    }
}
