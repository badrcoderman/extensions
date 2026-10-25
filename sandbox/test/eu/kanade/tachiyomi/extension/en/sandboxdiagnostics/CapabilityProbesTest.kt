package eu.kanade.tachiyomi.extension.en.sandboxdiagnostics

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class CapabilityProbesTest {
    @Test fun onlyLiteralPrivateAddressesAreAccepted() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.2").forEach {
            assertTrue(it, CapabilityProbes.privateAddress(it))
        }
        listOf("8.8.8.8", "172.32.0.1", "192.169.1.2", "127.0.0.1@example.com", "127.0.0.1/extra",
            "127.1", "0177.0.0.1", "http://127.0.0.1", "localhost", "10.256.0.1", "10.0.0.1:99").forEach {
            assertFalse(it, CapabilityProbes.privateAddress(it))
        }
    }
    @Test fun unknownProbesAndPublicTargetsFailBeforeAnyNetworkRequest() {
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected request") }.build()
        for ((id, address) in listOf("unknown" to "127.0.0.1", "network" to "8.8.8.8")) {
            try { CapabilityProbes.run(id, address, client); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun ownJvmCanaryRunsWithoutAChildProcess() {
        assertEquals(Outcome.PASS, CapabilityProbes.run("jvm").outcome)
    }
    @Test fun temporaryCanaryIsDeletedAndNoExistingFileIsUsed() {
        val folder = File(System.getProperty("java.io.tmpdir"))
        fun canaries() = folder.list()?.filter { it.startsWith("extension-sandbox-canary-") }?.toSet()
        val before = canaries()
        assertEquals(Outcome.ALLOWED, CapabilityProbes.run("storage").outcome)
        assertEquals(before, canaries())
    }
    private fun fixture(code: Int = 200, body: String, redirect: Boolean = false): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("127.0.0.1", chain.request().url.host)
            assertEquals(4570, chain.request().url.port)
            assertEquals("/canary", chain.request().url.encodedPath)
            assertNull(chain.request().header("Cookie"))
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .apply { if (redirect) header("Location", "https://public.example/should-not-run") }
                .body(body.toResponseBody()).build()
        }.build()
    @Test fun expectedLocalCanarySucceedsAndOversizedOrRedirectedBodiesFail() {
        assertEquals(Outcome.ALLOWED, CapabilityProbes.run("network", client = fixture(body = CapabilityProbes.MARKER)).outcome)
        assertEquals(Outcome.ERROR, CapabilityProbes.run("network", client = fixture(body = "x".repeat(200))).outcome)
        assertEquals(Outcome.ERROR, CapabilityProbes.run("network", client = fixture(302, "", true)).outcome)
    }
    @Test fun deniedOrMissingHostCapabilitiesBecomeResultsWithoutPrivateExceptionText() {
        val errors = listOf(SecurityException("PRIVATE"), NoClassDefFoundError("PRIVATE"), IOException("PRIVATE"))
        val expected = listOf(Outcome.BLOCKED, Outcome.UNAVAILABLE, Outcome.UNAVAILABLE)
        errors.forEachIndexed { index, error ->
            val client = OkHttpClient.Builder().addInterceptor { throw error }.build()
            val result = CapabilityProbes.run("network", client = client)
            assertEquals(expected[index], result.outcome)
            assertFalse(result.summary.contains("PRIVATE"))
        }
    }
    @Test fun theNetworkProbeNeverFollowsALocalServerRedirect() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/redirected")))
            server.enqueue(MockResponse().setBody(CapabilityProbes.MARKER))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/canary")).build())
            }.build()
            assertEquals(Outcome.ERROR, CapabilityProbes.run("network", client = client).outcome)
            assertEquals(1, server.requestCount)
        }
    }
    @Test(timeout = 5000) fun fixedProcessCanaryReturnsOnlyTheExpectedMarkerOnTheTestJvm() {
        assertEquals(Outcome.ALLOWED, CapabilityProbes.run("process").outcome)
    }
    @Test fun deliberateSourceFailureStaysAnExplicitRecoverableException() {
        try { CapabilityProbes.run("failure"); fail() }
        catch (error: IOException) { assertEquals(CapabilityProbes.INTENTIONAL_ERROR, error.message) }
    }
    @Test fun everyOutcomeHasAStaticPngResultCard() {
        Outcome.values().forEach { assertArrayEquals(byteArrayOf(-119,80,78,71,13,10,26,10), ReportImages.bytes(it).take(8).toByteArray()) }
    }
}
