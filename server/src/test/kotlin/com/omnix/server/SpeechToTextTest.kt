package com.omnix.server

import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.HttpTransportResponse
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.stt.GroqWhisperSpeechToText
import com.omnix.server.stt.SttOutcome
import com.omnix.server.stt.WavCheck
import com.omnix.server.stt.WhisperLanguages
import com.omnix.server.stt.multipartBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechToTextTest {

    private class FakeBinaryTransport(private val response: HttpTransportResponse) : HttpTransport {
        var contentType: String? = null
        var body: ByteArray? = null
        var headers: Map<String, String> = emptyMap()

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse = error("json post not expected")

        override suspend fun postBytes(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
            contentType: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse {
            this.contentType = contentType
            this.body = body
            this.headers = headers
            return response
        }
    }

    private fun wav(seconds: Int = 1): ByteArray {
        val data = ByteArray(44 + seconds * 32_000)
        "RIFF".toByteArray().copyInto(data, 0)
        "WAVE".toByteArray().copyInto(data, 8)
        return data
    }

    @Test
    fun `whisper answer gives text and an ISO language code`() = runBlocking {
        val transport = FakeBinaryTransport(HttpTransportResponse(200, """{"text":" Salam, nähili? ","language":"turkmen","duration":1.2}"""))
        val stt = GroqWhisperSpeechToText(transport, apiKey = "k")

        val outcome = stt.transcribe(wav(), languageHint = null)

        assertEquals(SttOutcome.Success("Salam, nähili?", "tk"), outcome)
        assertTrue(transport.contentType!!.startsWith("multipart/form-data; boundary="))
        assertEquals("Bearer k", transport.headers["Authorization"])
        val text = String(transport.body!!, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"model\"\r\n\r\nwhisper-large-v3\r\n"))
        assertTrue(text.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"))
        assertFalse("no language hint means auto-detect", text.contains("name=\"language\""))
    }

    @Test
    fun `a language hint is passed as a code`() = runBlocking {
        val transport = FakeBinaryTransport(HttpTransportResponse(200, """{"text":"Привет","language":"russian"}"""))
        GroqWhisperSpeechToText(transport, apiKey = "k").transcribe(wav(), languageHint = "ru")
        assertTrue(String(transport.body!!, Charsets.ISO_8859_1).contains("name=\"language\"\r\n\r\nru\r\n"))
    }

    @Test
    fun `provider errors map to failure kinds`() = runBlocking {
        val limited = GroqWhisperSpeechToText(FakeBinaryTransport(HttpTransportResponse(429, "{}")), "k")
        assertEquals(SttOutcome.Failure(ProviderFailureKind.RATE_LIMITED), limited.transcribe(wav(), null))
        val down = GroqWhisperSpeechToText(FakeBinaryTransport(HttpTransportResponse(503, "{}")), "k")
        assertEquals(SttOutcome.Failure(ProviderFailureKind.SERVER_ERROR), down.transcribe(wav(), null))
        val garbage = GroqWhisperSpeechToText(FakeBinaryTransport(HttpTransportResponse(200, "not json")), "k")
        assertEquals(SttOutcome.Failure(ProviderFailureKind.UNKNOWN), garbage.transcribe(wav(), null))
    }

    @Test
    fun `multipart keeps the file bytes intact`() {
        val file = byteArrayOf(0, -1, 10, 13, 127)
        val body = multipartBody("b", listOf("model" to "m"), "file", "a.wav", "audio/wav", file)
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--b\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nm\r\n"))
        assertTrue(text.endsWith("\r\n--b--\r\n"))
        val start = text.indexOf("audio/wav\r\n\r\n") + "audio/wav\r\n\r\n".length
        assertEquals(file.toList(), body.copyOfRange(start, start + file.size).toList())
    }

    @Test
    fun `wav check accepts short riff wave and rejects the rest`() {
        assertTrue(WavCheck.isPlausible(wav(5)))
        assertFalse(WavCheck.isPlausible(wav(31)))
        assertFalse(WavCheck.isPlausible(ByteArray(100)))
        assertFalse(WavCheck.isPlausible(ByteArray(10)))
    }

    @Test
    fun `language names and codes normalise`() {
        assertEquals("tk", WhisperLanguages.isoCode("Turkmen"))
        assertEquals("ru", WhisperLanguages.isoCode("ru"))
        assertEquals("kk", WhisperLanguages.isoCode("kazakh"))
        assertNull(WhisperLanguages.isoCode("klingon"))
        assertNull(WhisperLanguages.isoCode(null))
    }
}
