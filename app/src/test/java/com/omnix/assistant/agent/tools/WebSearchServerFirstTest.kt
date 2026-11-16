package com.omnix.assistant.agent.tools

import com.omnix.assistant.agent.model.ToolExecutionStatus
import com.omnix.assistant.agent.tools.intelligence.ServerSearchOutcome
import com.omnix.assistant.agent.tools.intelligence.ServerSearchResult
import com.omnix.assistant.agent.tools.intelligence.ServerSearchSummary
import com.omnix.assistant.agent.tools.intelligence.ServerWebSearch
import com.omnix.assistant.agent.tools.intelligence.WebSearchTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class WebSearchServerFirstTest {

    private val ddgBody = """{"AbstractText":"Ответ DuckDuckGo","AbstractSource":"DuckDuckGo"}"""

    private fun client(calls: AtomicInteger, body: String = ddgBody) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            calls.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    private val query = buildJsonObject { put("query", "кто написал глаза не врут") }

    @Test
    fun `server answer is used and the phone never touches DuckDuckGo`() = runBlocking {
        val calls = AtomicInteger()
        var seenLang: String? = null
        val server = ServerWebSearch { _, lang ->
            seenLang = lang
            ServerSearchOutcome.Found("Серверный ответ", listOf(ServerSearchResult("t", "https://a.example", "s")))
        }

        val result = WebSearchTool(client(calls), server).execute(buildJsonObject {
            put("query", "кто написал глаза не врут")
            put("lang", "ru")
        })

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals("Серверный ответ", result.summary)
        assertEquals(0, calls.get())
        assertEquals("ru", seenLang)
        assertEquals("OMNIX", result.data?.get("source")?.jsonPrimitive?.content)
    }

    @Test
    fun `server without answer builds a short summary from snippets`() = runBlocking {
        val server = ServerWebSearch { _, _ ->
            ServerSearchOutcome.Found(
                null,
                listOf(
                    ServerSearchResult("A", "https://a.example", "Первое"),
                    ServerSearchResult("B", "https://b.example", ""),
                    ServerSearchResult("C", "https://c.example", "Третье")
                )
            )
        }

        val result = WebSearchTool(client(AtomicInteger()), server).execute(query)

        assertEquals("Первое. B. Третье", result.summary)
    }

    @Test
    fun `SEARCH_UNAVAILABLE falls back to the old DuckDuckGo path`() = runBlocking {
        val calls = AtomicInteger()
        val server = ServerWebSearch { _, _ -> ServerSearchOutcome.Unavailable("HTTP_503") }

        val result = WebSearchTool(client(calls), server).execute(query)

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals("Ответ DuckDuckGo", result.summary)
        assertEquals(1, calls.get())
    }

    @Test
    fun `server exception falls back instead of failing the search`() = runBlocking {
        val calls = AtomicInteger()
        val server = ServerWebSearch { _, _ -> throw IOException("down") }

        val result = WebSearchTool(client(calls), server).execute(query)

        assertEquals("Ответ DuckDuckGo", result.summary)
    }

    @Test
    fun `no server configured keeps the previous behaviour`() = runBlocking {
        val calls = AtomicInteger()

        val result = WebSearchTool(client(calls)).execute(query)

        assertEquals("Ответ DuckDuckGo", result.summary)
        assertEquals(1, calls.get())
    }

    @Test
    fun `summary composer limits length and ignores empty results`() {
        assertNull(ServerSearchSummary.compose(ServerSearchOutcome.Found(null, emptyList())))
        assertNull(ServerSearchSummary.compose(ServerSearchOutcome.Found("  ", listOf(ServerSearchResult("", "u", "")))))
        val long = ServerSearchSummary.compose(ServerSearchOutcome.Found("я".repeat(900), emptyList()))!!
        assertTrue(long.length <= 503)
        assertTrue(long.endsWith("..."))
    }
}
