package com.omnix.server

import com.omnix.server.observability.StructuredLogger
import com.omnix.server.provider.ApiKeyPool
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.HttpTransportResponse
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.provider.TransportException
import com.omnix.server.stt.ChainedSpeechToText
import com.omnix.server.stt.GeminiSpeechToText
import com.omnix.server.stt.SpeechToText
import com.omnix.server.stt.SttOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSttTest {

    private class FakeJsonTransport(private val responses: List<HttpTransportResponse>) : HttpTransport {
        val urls = mutableListOf<String>()
        val keysUsed = mutableListOf<String>()
        var lastBody: String = ""

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse {
            urls += url
            keysUsed += headers["x-goog-api-key"].orEmpty()
            lastBody = body
            return responses[minOf(urls.size - 1, responses.size - 1)]
        }

        override suspend fun postBytes(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
            contentType: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse = error("bytes post not expected")
    }

    private class FakeStt(private val outcome: SttOutcome) : SpeechToText {
        var calls = 0
        override suspend fun transcribe(wav: ByteArray, languageHint: String?): SttOutcome {
            calls++
            return outcome
        }
    }

    private class RecordingLogger : StructuredLogger {
        val lines = mutableListOf<String>()
        override fun info(message: String, vararg fields: Pair<String, String>) {
            lines += "$message ${fields.joinToString { "${it.first}=${it.second}" }}"
        }
        override fun warn(message: String, vararg fields: Pair<String, String>) = info(message, *fields)
        override fun error(message: String, vararg fields: Pair<String, String>) = info(message, *fields)
    }

    private val wav = ByteArray(64)

    private fun answer(inner: String): HttpTransportResponse {
        val escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"")
        return HttpTransportResponse(200, """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]}}]}""")
    }

    private fun gemini(transport: HttpTransport, vararg keys: String) =
        GeminiSpeechToText(transport, ApiKeyPool(keys.toList()), model = "gemini-test")

    @Test
    fun `successful answer gives text and ISO language`() = runBlocking {
        val transport = FakeJsonTransport(listOf(answer("""{"text":" Salam, nähili? ","language":"tk"}""")))

        val outcome = gemini(transport, "k1").transcribe(wav, languageHint = "tk")

        assertEquals(SttOutcome.Success("Salam, nähili?", "tk"), outcome)
        assertTrue(transport.urls.single().endsWith("/gemini-test:generateContent"))
        assertEquals("k1", transport.keysUsed.single())
        assertTrue(transport.lastBody.contains("audio/wav"))
        assertTrue(transport.lastBody.contains("responseSchema"))
    }

    @Test
    fun `plain text answer is accepted and language name is normalised`() = runBlocking {
        val plain = gemini(FakeJsonTransport(listOf(answer("Привет"))), "k").transcribe(wav, null)
        assertEquals(SttOutcome.Success("Привет", null), plain)
        val named = gemini(FakeJsonTransport(listOf(answer("""{"text":"Hi","language":"English"}"""))), "k")
            .transcribe(wav, null)
        assertEquals(SttOutcome.Success("Hi", "en"), named)
    }

    @Test
    fun `empty or broken answers are failures`() = runBlocking {
        val bodies = listOf(
            answer("""{"text":"  ","language":"ru"}"""),
            answer(""),
            HttpTransportResponse(200, """{"candidates":[]}"""),
            HttpTransportResponse(200, "not json")
        )
        for (response in bodies) {
            val outcome = gemini(FakeJsonTransport(listOf(response)), "k").transcribe(wav, null)
            assertEquals(SttOutcome.Failure(ProviderFailureKind.UNKNOWN), outcome)
        }
    }

    @Test
    fun `http statuses map to failure kinds`() = runBlocking {
        fun kind(status: Int) = (gemini(FakeJsonTransport(listOf(HttpTransportResponse(status, "{}"))), "k")
            .let { runBlocking { it.transcribe(wav, null) } } as SttOutcome.Failure).kind
        assertEquals(ProviderFailureKind.AUTH, kind(401))
        assertEquals(ProviderFailureKind.AUTH, kind(403))
        assertEquals(ProviderFailureKind.RATE_LIMITED, kind(429))
        assertEquals(ProviderFailureKind.SERVER_ERROR, kind(503))
        assertEquals(ProviderFailureKind.BAD_REQUEST, kind(400))
        assertEquals(ProviderFailureKind.UNKNOWN, kind(302))
    }

    @Test
    fun `transport exception and missing key are failures`() = runBlocking {
        val broken = object : HttpTransport {
            override suspend fun post(
                url: String,
                headers: Map<String, String>,
                body: String,
                connectTimeoutMs: Long,
                requestTimeoutMs: Long
            ): HttpTransportResponse = throw TransportException(ProviderFailureKind.TIMEOUT, "t")

            override suspend fun postBytes(
                url: String,
                headers: Map<String, String>,
                body: ByteArray,
                contentType: String,
                connectTimeoutMs: Long,
                requestTimeoutMs: Long
            ): HttpTransportResponse = error("not expected")
        }
        assertEquals(SttOutcome.Failure(ProviderFailureKind.TIMEOUT), gemini(broken, "k").transcribe(wav, null))
        assertEquals(
            SttOutcome.Failure(ProviderFailureKind.NOT_CONFIGURED),
            gemini(FakeJsonTransport(emptyList())).transcribe(wav, null)
        )
    }

    @Test
    fun `rate limited key rotates to the next key`() = runBlocking {
        val transport = FakeJsonTransport(
            listOf(HttpTransportResponse(429, "{}"), answer("""{"text":"ok","language":"ru"}"""))
        )

        val outcome = gemini(transport, "k1", "k2").transcribe(wav, null)

        assertEquals(SttOutcome.Success("ok", "ru"), outcome)
        assertEquals(listOf("k1", "k2"), transport.keysUsed)
    }

    @Test
    fun `chain falls back to the second provider when the first fails`() = runBlocking {
        val first = FakeStt(SttOutcome.Failure(ProviderFailureKind.SERVER_ERROR))
        val second = FakeStt(SttOutcome.Success("Salam", "tk"))
        val logger = RecordingLogger()

        val outcome = ChainedSpeechToText(listOf("groq" to first, "gemini" to second), logger).transcribe(wav, null)

        assertEquals(SttOutcome.Success("Salam", "tk"), outcome)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertTrue(logger.lines.any { it.contains("provider=gemini") })
        assertFalse("recognised text must not be logged", logger.lines.any { it.contains("Salam") })
    }

    @Test
    fun `chain does not call the second provider after success`() = runBlocking {
        val first = FakeStt(SttOutcome.Success("a", null))
        val second = FakeStt(SttOutcome.Success("b", null))

        val outcome = ChainedSpeechToText(listOf("groq" to first, "gemini" to second)).transcribe(wav, null)

        assertEquals(SttOutcome.Success("a", null), outcome)
        assertEquals(0, second.calls)
    }

    @Test
    fun `bad request stops the chain and last failure is returned`() = runBlocking {
        val bad = FakeStt(SttOutcome.Failure(ProviderFailureKind.BAD_REQUEST))
        val next = FakeStt(SttOutcome.Success("b", null))
        assertEquals(
            SttOutcome.Failure(ProviderFailureKind.BAD_REQUEST),
            ChainedSpeechToText(listOf("groq" to bad, "gemini" to next)).transcribe(wav, null)
        )
        assertEquals(0, next.calls)

        val down1 = FakeStt(SttOutcome.Failure(ProviderFailureKind.TIMEOUT))
        val down2 = FakeStt(SttOutcome.Failure(ProviderFailureKind.RATE_LIMITED))
        assertEquals(
            SttOutcome.Failure(ProviderFailureKind.RATE_LIMITED),
            ChainedSpeechToText(listOf("a" to down1, "b" to down2)).transcribe(wav, null)
        )
    }

    @Test
    fun `fromConfig honours order and skips unavailable providers`() {
        val groq: SpeechToText = FakeStt(SttOutcome.Success("g", null))
        val gem: SpeechToText = FakeStt(SttOutcome.Success("m", null))
        val available = mapOf("groq" to groq, "gemini" to gem)

        assertTrue(ChainedSpeechToText.fromConfig("gemini,groq", available) is ChainedSpeechToText)
        assertEquals(groq, ChainedSpeechToText.fromConfig(null, available))
        assertEquals(gem, ChainedSpeechToText.fromConfig(" Gemini ", available))
        assertEquals(groq, ChainedSpeechToText.fromConfig("groq,bogus", available))
        assertNull(ChainedSpeechToText.fromConfig("gemini", mapOf("groq" to groq)))
    }
}
