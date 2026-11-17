package com.omnix.server

import com.omnix.server.api.ApiErrorCode
import com.omnix.server.api.ImageDto
import com.omnix.server.api.ImageValidator
import com.omnix.server.config.ProviderConfig
import com.omnix.server.config.ValidationConfig
import com.omnix.server.provider.AnthropicProvider
import com.omnix.server.provider.GeminiProvider
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.HttpTransportResponse
import com.omnix.server.provider.ProviderId
import com.omnix.server.provider.ProviderImage
import com.omnix.server.provider.ProviderMessage
import com.omnix.server.provider.ProviderRequest
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
import java.util.Base64

/** Картинки в чате: проверка на входе и формат запроса к Gemini/Claude. */
class ImageSupportTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val validation = ValidationConfig()

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
    private val webpBytes = "RIFF".toByteArray() + byteArrayOf(1, 0, 0, 0) + "WEBPVP8 ".toByteArray()

    // ------------------------------------------------------------ валидатор

    @Test
    fun `no images is valid`() {
        assertNull(ImageValidator.validate(emptyList(), validation))
    }

    @Test
    fun `jpeg png and webp pass`() {
        assertNull(ImageValidator.validate(listOf(ImageDto("image/jpeg", b64(jpegBytes))), validation))
        assertNull(ImageValidator.validate(listOf(ImageDto("image/png", b64(pngBytes))), validation))
        assertNull(ImageValidator.validate(listOf(ImageDto("image/webp", b64(webpBytes))), validation))
    }

    @Test
    fun `mime type is case-insensitive`() {
        assertNull(ImageValidator.validate(listOf(ImageDto("IMAGE/JPEG", b64(jpegBytes))), validation))
    }

    @Test
    fun `unsupported mime is rejected`() {
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            ImageValidator.validate(listOf(ImageDto("image/gif", b64(jpegBytes))), validation)
        )
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            ImageValidator.validate(listOf(ImageDto("application/pdf", b64(jpegBytes))), validation)
        )
    }

    @Test
    fun `content must match declared type`() {
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            ImageValidator.validate(listOf(ImageDto("image/png", b64(jpegBytes))), validation)
        )
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            ImageValidator.validate(listOf(ImageDto("image/jpeg", b64("not an image".toByteArray()))), validation)
        )
    }

    @Test
    fun `broken base64 and empty data are rejected`() {
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            ImageValidator.validate(listOf(ImageDto("image/jpeg", "@@@@")), validation)
        )
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            ImageValidator.validate(listOf(ImageDto("image/jpeg", "")), validation)
        )
    }

    @Test
    fun `oversized image is rejected`() {
        val big = jpegBytes + ByteArray(validation.maxImageBytes)
        assertEquals(
            ApiErrorCode.PAYLOAD_TOO_LARGE,
            ImageValidator.validate(listOf(ImageDto("image/jpeg", b64(big))), validation)
        )
    }

    @Test
    fun `too many images are rejected`() {
        val one = ImageDto("image/jpeg", b64(jpegBytes))
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            ImageValidator.validate(List(validation.maxImagesPerRequest + 1) { one }, validation)
        )
    }

    @Test
    fun `total size of images is limited`() {
        val v = ValidationConfig(maxImageBytes = 1_000, maxImagesTotalBytes = 1_500, maxImagesPerRequest = 3)
        val img = ImageDto("image/jpeg", b64(jpegBytes + ByteArray(800)))
        assertNull(ImageValidator.validate(listOf(img), v))
        assertEquals(ApiErrorCode.PAYLOAD_TOO_LARGE, ImageValidator.validate(listOf(img, img), v))
    }

    // ------------------------------------------------------------ провайдеры

    private class CapturingTransport : HttpTransport {
        var body: String? = null
        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse {
            this.body = body
            return HttpTransportResponse(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}],"content":[{"type":"text","text":"ok"}]}"""
            )
        }
    }

    private fun config(id: ProviderId) = ProviderConfig(
        id = id,
        enabled = true,
        priority = 1,
        apiKey = "k",
        model = "m",
        baseUrl = "https://provider.invalid/v1",
        connectTimeoutMs = 100,
        requestTimeoutMs = 200
    )

    private fun request(images: List<ProviderImage>, history: List<ProviderMessage> = emptyList()) = ProviderRequest(
        requestId = "r",
        prompt = "что на фото?",
        systemPrompt = "правила",
        history = history,
        images = images,
        maxTokens = 100,
        temperature = 0.5
    )

    private val image = ProviderImage("image/jpeg", "QUJD")

    @Test
    fun `gemini sends inlineData before the question`() = runBlocking {
        val t = CapturingTransport()
        GeminiProvider(config(ProviderId.GEMINI), t, json).execute(request(listOf(image)))

        val contents = json.parseToJsonElement(t.body!!).jsonObject["contents"]!!.jsonArray
        val parts = contents.last().jsonObject["parts"]!!.jsonArray
        assertEquals(2, parts.size)
        val inline = parts[0].jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("QUJD", inline["data"]!!.jsonPrimitive.content)
        assertFalse(parts[0].jsonObject.containsKey("text"))
        assertEquals("что на фото?", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini text-only part has no inlineData field`() = runBlocking {
        val t = CapturingTransport()
        GeminiProvider(config(ProviderId.GEMINI), t, json).execute(request(emptyList()))

        assertFalse(t.body!!.contains("inlineData"))
        assertFalse(t.body!!.contains("null"))
    }

    @Test
    fun `claude sends image block then text block`() = runBlocking {
        val t = CapturingTransport()
        AnthropicProvider(config(ProviderId.ANTHROPIC), t, json).execute(request(listOf(image)))

        val messages = json.parseToJsonElement(t.body!!).jsonObject["messages"]!!.jsonArray
        val content = messages.last().jsonObject["content"]!!.jsonArray
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val source = content[0].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", source["data"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `claude keeps plain string content without images`() = runBlocking {
        val t = CapturingTransport()
        AnthropicProvider(config(ProviderId.ANTHROPIC), t, json).execute(request(emptyList()))

        val messages = json.parseToJsonElement(t.body!!).jsonObject["messages"]!!.jsonArray
        assertEquals("что на фото?", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `claude merges image message with previous user turn`() = runBlocking {
        val t = CapturingTransport()
        AnthropicProvider(config(ProviderId.ANTHROPIC), t, json)
            .execute(request(listOf(image), listOf(ProviderMessage("user", "привет"))))

        val messages = json.parseToJsonElement(t.body!!).jsonObject["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val content = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals("привет", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only gemini and claude declare vision`() {
        assertTrue(GeminiProvider(config(ProviderId.GEMINI), CapturingTransport(), json).capabilities.supportsVision)
        assertTrue(AnthropicProvider(config(ProviderId.ANTHROPIC), CapturingTransport(), json).capabilities.supportsVision)
        assertFalse(
            com.omnix.server.provider.GroqProvider(config(ProviderId.GROQ), CapturingTransport(), json)
                .capabilities.supportsVision
        )
        assertFalse(
            com.omnix.server.provider.OpenRouterProvider(config(ProviderId.OPENROUTER), CapturingTransport(), json)
                .capabilities.supportsVision
        )
    }
}
