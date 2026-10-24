package eu.kanade.tachiyomi.extension.ar.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

internal fun flightHtml(stream: String): String {
    val midpoint = stream.length / 2
    return listOf(stream.take(midpoint), stream.drop(midpoint)).joinToString("") {
        "<script>self.__next_f.push(${JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(it)))})</script>"
    }
}

class NextDataTest {
    @Test
    fun joinsFlightChunksAndHandlesEscapedBracesAndQuotes() {
        val data = NextData(flightHtml("7:[\"$\",\"div\",null,{\"mangaData\":{\"name\":\"A } \\\"quoted\\\" title\",\"genre\":[\"أكشن\"]}}]"))
        assertEquals("A } \"quoted\" title", data.objectValue("mangaData")!!.text("name"))
    }

    @Test
    fun skipsNullPropsBeforeAnObject() {
        assertEquals("ready", NextData.embeddedValue("0:{\"reader\":null}\n1:{\"reader\":{\"status\":\"ready\"}}", "reader")!!.jsonObject.text("status"))
    }

    @Test
    fun readsActionResultsAndCurrentBuildActionIds() {
        val result = NextData.actionResult("0:{\"a\":\"$@1\"}\n1:{\"status\":\"ready\"}\n")
        assertEquals("ready", result.text("status"))
        val ids = NextData.actionIds("let m=(0,l.createServerReference)(\"4031e23f3d25ce047b18e159746f83797a3fe08e00\",l.callServer,void 0,l.findSourceMapURL,\"startChapterAction\")")
        assertEquals("4031e23f3d25ce047b18e159746f83797a3fe08e00", ids["startChapterAction"])
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsFailedActions() {
        NextData.actionResult("0:{}\n1:E{\"digest\":\"123\"}")
    }

    @Test
    fun parsesUtcDatesAndOffsets() {
        assertEquals(parseDate("2026-10-24T12:00:00Z"), parseDate("2026-10-24T15:00:00+03:00"))
        assertEquals(parseDate("2026-10-24T12:00:00Z"), parseDate("2026-10-24T12:00:00.123456"))
        assertEquals(0L, parseDate(null))
    }
}
