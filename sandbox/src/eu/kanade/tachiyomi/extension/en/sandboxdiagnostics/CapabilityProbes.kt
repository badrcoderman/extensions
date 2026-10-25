package eu.kanade.tachiyomi.extension.en.sandboxdiagnostics

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal enum class Outcome { NOT_RUN, PASS, ALLOWED, BLOCKED, UNAVAILABLE, ERROR }
internal data class ProbeResult(val outcome: Outcome, val summary: String)

/** Fixed canaries only: no user commands, remote code, environment dumps or account data. */
internal object CapabilityProbes {
    const val MARKER = "extension-sandbox-canary-v1"
    const val INTENTIONAL_ERROR = "Sandbox test: intentional recoverable source error"
    val ids = listOf("jvm", "storage", "network", "process", "failure")

    @JvmStatic fun executionCanary(): String = MARKER

    fun privateAddress(value: String): Boolean {
        if (!Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(value)) return false
        val parts = value.split('.').map { it.toInt() }
        if (parts.any { it !in 0..255 } || parts.joinToString(".") != value) return false
        return parts[0] == 10 || parts[0] == 127 ||
            (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168)
    }

    fun run(id: String, address: String = "127.0.0.1", client: OkHttpClient? = null): ProbeResult {
        require(id in ids) { "Unknown sandbox probe" }
        if (id == "failure") throw IOException(INTENTIONAL_ERROR)
        require(privateAddress(address)) { "Only a literal private or loopback address is allowed" }
        return try {
            when (id) {
                "jvm" -> {
                    val value = CapabilityProbes::class.java.getMethod("executionCanary").invoke(null)
                    check(value == MARKER)
                    ProbeResult(Outcome.PASS, "The extension's own JVM canary executed. This is normal extension behavior.")
                }
                "storage" -> temporaryFile()
                "network" -> localHttp(address, client)
                "process" -> processCanary()
                else -> error("Unknown sandbox probe")
            }
        } catch (_: SecurityException) {
            ProbeResult(Outcome.BLOCKED, "The host denied this capability.")
        } catch (error: LinkageError) {
            ProbeResult(Outcome.UNAVAILABLE, "Host API unavailable: ${error.javaClass.simpleName}.")
        } catch (error: UnsupportedOperationException) {
            ProbeResult(Outcome.UNAVAILABLE, "Host API unsupported: ${error.javaClass.simpleName}.")
        } catch (error: Exception) {
            ProbeResult(Outcome.UNAVAILABLE, "The canary did not complete (${error.javaClass.simpleName}). No private error text is included.")
        }
    }

    private fun temporaryFile(): ProbeResult {
        val file = File.createTempFile("extension-sandbox-canary-", ".tmp")
        try {
            file.outputStream().use { it.write(MARKER.toByteArray(Charsets.UTF_8)) }
            val value = file.inputStream().use { limitedText(it) }
            check(value == MARKER)
        } finally {
            if (!file.delete()) throw IOException("Sandbox canary cleanup failed")
        }
        return ProbeResult(Outcome.ALLOWED, "A new temporary canary was written, read and deleted. No existing files were read.")
    }

    private fun localHttp(address: String, supplied: OkHttpClient?): ProbeResult {
        val client = (supplied?.newBuilder() ?: OkHttpClient.Builder())
            .followRedirects(false).followSslRedirects(false)
            .callTimeout(3, TimeUnit.SECONDS).connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("http://$address:4570/canary")
            .header("User-Agent", "ExtensionSandbox/1").build()
        return client.newCall(request).execute().use { response ->
            val bytes = response.peekBody(129).bytes()
            if (response.code == 200 && bytes.size <= 128 && String(bytes, Charsets.UTF_8) == MARKER) {
                ProbeResult(Outcome.ALLOWED, "The selected private-address canary endpoint was reachable. Redirects were disabled.")
            } else ProbeResult(Outcome.ERROR, "The endpoint did not return the expected fixed canary. No redirect was followed.")
        }
    }

    private fun processCanary(): ProbeResult {
        val shell = if (File("/system/bin/sh").isFile) "/system/bin/sh" else "/bin/sh"
        // There are deliberately no command or argument parameters on this probe.
        val builder = ProcessBuilder(shell, "-c", "printf extension-sandbox-canary-v1").redirectErrorStream(true)
        builder.environment().clear()
        val process = builder.start()
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                return ProbeResult(Outcome.ERROR, "The fixed canary exceeded its two-second deadline; termination was requested.")
            }
            val value = process.inputStream.use { limitedText(it) }
            return if (process.exitValue() == 0 && value == MARKER) {
                ProbeResult(Outcome.ALLOWED, "The fixed printf process ran with the host's existing privileges. This does not prove a sandbox escape.")
            } else ProbeResult(Outcome.UNAVAILABLE, "The fixed process did not return the expected canary.")
        } finally {
            try { process.destroy() } finally {
                process.inputStream.close()
                process.outputStream.close()
                process.errorStream.close()
            }
        }
    }

    private fun limitedText(stream: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        repeat(129) {
            val value = stream.read()
            if (value == -1) return String(bytes.toByteArray(), Charsets.UTF_8)
            bytes.write(value)
        }
        throw IOException("Canary output limit exceeded")
    }
}
