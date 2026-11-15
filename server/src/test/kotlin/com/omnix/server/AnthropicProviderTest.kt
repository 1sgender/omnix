package com.omnix.server

import com.omnix.server.config.ProviderConfig
import com.omnix.server.provider.AnthropicProvider
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.HttpTransportResponse
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.provider.ProviderId
import com.omnix.server.provider.ProviderMessage
import com.omnix.server.provider.ProviderRequest
import com.omnix.server.provider.ProviderResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Claude (уровень Pro): формат запроса и разбор ответа Messages API. */
class AnthropicProviderTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private class FakeTransport(private val response: HttpTransportResponse) : HttpTransport {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var body: String? = null

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse {
            this.url = url
            this.headers = headers
            this.body = body
            return response
        }
    }

    private val config = ProviderConfig(
        id = ProviderId.ANTHROPIC,
        enabled = true,
        priority = 4,
        apiKey = "sk-ant-test",
        model = "claude-sonnet-5-5",
        baseUrl = "https://anthropic.invalid/v1/messages",
        connectTimeoutMs = 100,
        requestTimeoutMs = 200
    )

    private fun request(history: List<ProviderMessage> = emptyList()) = ProviderRequest(
        requestId = "r-1",
        prompt = "вопрос",
        systemPrompt = "правила",
        history = history,
        maxTokens = 321,
        temperature = 0.7
    )

    private fun ok(content: String, usage: String = """{"input_tokens":11,"output_tokens":7}""") =
        HttpTransportResponse(200, """{"content":$content,"usage":$usage,"stop_reason":"end_turn"}""")

    @Test
    fun `request uses x-api-key and version header and omits sampling params`() = runBlocking {
        val transport = FakeTransport(ok("""[{"type":"text","text":"ответ"}]"""))
        AnthropicProvider(config, transport, json).execute(request())

        assertEquals("https://anthropic.invalid/v1/messages", transport.url)
        assertEquals("sk-ant-test", transport.headers["x-api-key"])
        assertEquals("2023-06-01", transport.headers["anthropic-version"])
        assertFalse(transport.headers.containsKey("Authorization"))

        val body = json.parseToJsonElement(transport.body!!).jsonObject
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("321", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("правила", body["system"]!!.jsonPrimitive.content)
        assertFalse("temperature is rejected by new Claude models", body.containsKey("temperature"))
        assertEquals("between_tools", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `success joins text blocks ignores thinking and reports tokens`() = runBlocking {
        val transport = FakeTransport(
            ok(
                """[{"type":"thinking","thinking":"скрыто"},{"type":"text","text":"раз "},{"type":"text","text":"два"}]"""
            )
        )
        val result = AnthropicProvider(config, transport, json).execute(request())

        assertTrue(result is ProviderResult.Success)
        result as ProviderResult.Success
        assertEquals("раз два", result.text)
        assertEquals("claude-sonnet-5-5", result.model)
        assertEquals(11L, result.inputTokens)
        assertEquals(7L, result.outputTokens)
        assertEquals(18L, result.totalTokens)
    }

    @Test
    fun `missing usage leaves token counts null`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(200, """{"content":[{"type":"text","text":"ok"}]}""")
        )
        val result = AnthropicProvider(config, transport, json).execute(request()) as ProviderResult.Success

        assertNull(result.inputTokens)
        assertNull(result.totalTokens)
    }

    @Test
    fun `empty content is a failure`() = runBlocking {
        val transport = FakeTransport(ok("""[{"type":"thinking","thinking":"только мысли"}]"""))
        val result = AnthropicProvider(config, transport, json).execute(request())

        assertTrue(result is ProviderResult.Failure)
    }

    @Test
    fun `history starts with user merges same roles and moves system to system prompt`() = runBlocking {
        val transport = FakeTransport(ok("""[{"type":"text","text":"ok"}]"""))
        val history = listOf(
            ProviderMessage("assistant", "висящий ответ"),
            ProviderMessage("system", "доп. правило"),
            ProviderMessage("user", "привет"),
            ProviderMessage("assistant", "здравствуйте"),
            ProviderMessage("user", "ещё")
        )
        AnthropicProvider(config, transport, json).execute(request(history))

        val body = json.parseToJsonElement(transport.body!!).jsonObject
        assertEquals("правила\n\nдоп. правило", body["system"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        val roles = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user", "assistant", "user"), roles)
        // последняя реплика user из истории склеена с текущим вопросом
        assertEquals("ещё\n\nвопрос", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `status codes are classified`() = runBlocking {
        suspend fun kind(status: Int): ProviderFailureKind {
            val transport = FakeTransport(HttpTransportResponse(status, "{}"))
            val result = AnthropicProvider(config, transport, json).execute(request())
            return (result as ProviderResult.Failure).kind
        }
        assertEquals(ProviderFailureKind.AUTH, kind(401))
        assertEquals(ProviderFailureKind.RATE_LIMITED, kind(429))
        assertEquals(ProviderFailureKind.SERVER_ERROR, kind(529))
        assertEquals(ProviderFailureKind.BAD_REQUEST, kind(400))
    }
}
