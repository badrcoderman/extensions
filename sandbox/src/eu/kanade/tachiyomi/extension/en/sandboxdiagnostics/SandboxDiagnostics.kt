package eu.kanade.tachiyomi.extension.en.sandboxdiagnostics

import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import java.io.IOException

/** Merely installing, browsing or refreshing metadata does not run any probe. */
class SandboxDiagnostics : HttpSource() {
    override val name = "Sandbox Diagnostics (LOCAL TEST)"
    override val baseUrl = "https://extension-sandbox.invalid"
    override val lang = "en"
    override val supportsLatest = false
    private val lastResults = object : LinkedHashMap<String, ProbeResult>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProbeResult>) = size > 16
    }
    override fun headersBuilder() = Headers.Builder().set("User-Agent", "ExtensionSandbox/1")
    override val client: OkHttpClient by lazy {
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != "extension-sandbox.invalid") throw IOException("Unknown sandbox fixture")
            val state = request.url.pathSegments.last().removeSuffix(".png")
            val outcome = Outcome.values().firstOrNull { it.name == state } ?: throw IOException("Unknown sandbox result")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Local fixture")
                .body(ReportImages.bytes(outcome).toResponseBody("image/png".toMediaType())).build()
        }.build()
    }

    private fun parse(path: String): Pair<String, String> {
        val parts = path.removeSuffix("/run").trim('/').split('/')
        require(parts.size in 2..3 && parts[0] == "probe" && parts[1] in CapabilityProbes.ids)
        val address = parts.getOrElse(2) { "127.0.0.1" }
        require(CapabilityProbes.privateAddress(address))
        require(parts.size == 2 || parts[1] == "network")
        return parts[1] to address
    }
    private fun title(id: String) = when (id) {
        "jvm" -> "01 - JVM execution canary"
        "storage" -> "02 - Temporary-file canary"
        "network" -> "03 - Private-network HTTP canary"
        "process" -> "04 - OS process canary (opt in)"
        else -> "05 - Intentional recoverable source error"
    }
    private fun manga(path: String): SManga {
        val (id, address) = parse(path)
        val result = synchronized(lastResults) { lastResults[path] }
        return SManga.create().apply {
            url = path
            title = this@SandboxDiagnostics.title(id) + if (id == "network") " [$address]" else ""
            thumbnail_url = "$baseUrl/result/${result?.outcome?.name ?: Outcome.NOT_RUN.name}.png"
            description = "LOCAL SANDBOX TEST. Open the 'Run this probe' chapter to execute this specific canary. " +
                "Browsing and metadata refreshes do not execute probes. Results stay in this app instance. " +
                "An ALLOWED result describes a capability, not a sandbox escape.\n\n" +
                (if (id == "network") "For an iPhone-to-PC check, search this source for the PC's private IPv4 address. " +
                    "Run scripts/sandbox_fixture.py on that PC; the fixed port is 4570.\n\n" else "") +
                (if (id == "failure") "This test intentionally shows one source error. Verify that the app remains usable afterward.\n\n" else "") +
                (result?.let { "Last local result: ${it.outcome}\n${it.summary}" } ?: "No probe has run for this entry.")
            status = SManga.COMPLETED
            initialized = true
        }
    }
    private fun catalog(query: String = ""): MangasPage {
        val text = query.trim()
        if (CapabilityProbes.privateAddress(text)) return MangasPage(listOf(manga("/probe/network/$text")), false)
        return MangasPage(CapabilityProbes.ids.map { manga("/probe/$it") }.filter {
            text.isBlank() || it.title.contains(text, ignoreCase = true)
        }, false)
    }
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.just(if (page == 1) catalog() else MangasPage(emptyList(), false))
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable { if (page == 1) catalog(query) else MangasPage(emptyList(), false) }
    override fun fetchLatestUpdates(page: Int) = fetchPopularManga(page)
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable { this.manga(manga.url) }
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        parse(manga.url)
        listOf(SChapter.create().apply { url = manga.url + "/run"; name = "Run this probe (manual)"; chapter_number = 1f })
    }
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val path = chapter.url.removeSuffix("/run")
        val (id, address) = parse(path)
        val result = CapabilityProbes.run(id, address)
        synchronized(lastResults) { lastResults[path] = result }
        listOf(Page(0, imageUrl = "$baseUrl/result/${result.outcome.name}.png"))
    }
    override fun popularMangaRequest(page: Int) = Request.Builder().url(baseUrl).build()
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = popularMangaRequest(page)
    override fun popularMangaParse(response: Response) = catalog()
    override fun latestUpdatesParse(response: Response) = catalog()
    override fun searchMangaParse(response: Response) = catalog()
    override fun mangaDetailsParse(response: Response): SManga = throw IOException("Use local sandbox metadata")
    override fun chapterListParse(response: Response): List<SChapter> = throw IOException("Use local sandbox chapters")
    override fun pageListParse(response: Response): List<Page> = throw IOException("Use the explicit sandbox probe")
    override fun imageUrlParse(response: Response): String = throw IOException("Use the local result image")
}
