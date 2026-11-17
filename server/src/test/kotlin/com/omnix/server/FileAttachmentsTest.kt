package com.omnix.server

import com.omnix.server.api.ApiErrorCode
import com.omnix.server.api.AttachmentDto
import com.omnix.server.api.AttachmentValidator
import com.omnix.server.api.DocumentDto
import com.omnix.server.config.ProviderConfig
import com.omnix.server.config.ValidationConfig
import com.omnix.server.provider.AnthropicProvider
import com.omnix.server.provider.GeminiProvider
import com.omnix.server.provider.GroqProvider
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.HttpTransportResponse
import com.omnix.server.provider.ProviderAttachment
import com.omnix.server.provider.ProviderDocument
import com.omnix.server.provider.ProviderId
import com.omnix.server.provider.ProviderRequest
import com.omnix.server.provider.promptWithFiles
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

/** Файлы в чате: проверка на входе и как текст/PDF попадают к провайдерам. */
class FileAttachmentsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val validation = ValidationConfig()
    private val pdf = Base64.getEncoder().encodeToString("%PDF-1.4\n%%EOF".toByteArray())

    // ------------------------------------------------------------ валидатор

    @Test
    fun `text attachments and a pdf pass`() {
        assertNull(
            AttachmentValidator.validate(
                listOf(AttachmentDto("a.docx", "текст", "docx")),
                listOf(DocumentDto("application/pdf", pdf, "b.pdf", "текст pdf")),
                validation
            )
        )
    }

    @Test
    fun `attachment text over the limit is rejected`() {
        val big = AttachmentDto("a.txt", "a".repeat(validation.maxAttachmentChars + 1))
        assertEquals(ApiErrorCode.PAYLOAD_TOO_LARGE, AttachmentValidator.validate(listOf(big), emptyList(), validation))
    }

    @Test
    fun `blank attachment and too many attachments are rejected`() {
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            AttachmentValidator.validate(listOf(AttachmentDto("a.txt", "  ")), emptyList(), validation)
        )
        val one = AttachmentDto("a.txt", "x")
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            AttachmentValidator.validate(List(validation.maxAttachmentsPerRequest + 1) { one }, emptyList(), validation)
        )
    }

    @Test
    fun `document must really be a pdf`() {
        val fake = Base64.getEncoder().encodeToString("PK\u0003\u0004zip".toByteArray())
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            AttachmentValidator.validate(emptyList(), listOf(DocumentDto("application/pdf", fake, "x.pdf")), validation)
        )
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            AttachmentValidator.validate(emptyList(), listOf(DocumentDto("application/zip", pdf, "x.pdf")), validation)
        )
        assertEquals(
            ApiErrorCode.INVALID_REQUEST,
            AttachmentValidator.validate(emptyList(), listOf(DocumentDto("application/pdf", "@@@", "x.pdf")), validation)
        )
    }

    @Test
    fun `oversized pdf is rejected`() {
        val v = ValidationConfig(maxDocumentBytes = 10)
        assertEquals(
            ApiErrorCode.PAYLOAD_TOO_LARGE,
            AttachmentValidator.validate(emptyList(), listOf(DocumentDto("application/pdf", pdf, "x.pdf")), v)
        )
    }

    // ------------------------------------------------------------ текст запроса

    private fun request(
        attachments: List<ProviderAttachment> = emptyList(),
        documents: List<ProviderDocument> = emptyList()
    ) = ProviderRequest(
        requestId = "r",
        prompt = "О чём файл?",
        systemPrompt = "правила",
        attachments = attachments,
        documents = documents,
        maxTokens = 100,
        temperature = 0.5
    )

    private val doc = ProviderDocument("application/pdf", "QUJD", "отчёт.pdf", "текст из pdf")

    @Test
    fun `prompt without files is unchanged`() {
        assertEquals("О чём файл?", request().promptWithFiles(nativeDocuments = false))
    }

    @Test
    fun `attachment text goes before the question`() {
        val text = request(listOf(ProviderAttachment("a.xlsx", "A\tB", "xlsx"))).promptWithFiles(false)
        assertTrue(text.startsWith("File «a.xlsx»:"))
        assertTrue(text.contains("A\tB"))
        assertTrue(text.endsWith("О чём файл?"))
    }

    @Test
    fun `audio transcript is labelled`() {
        val text = request(listOf(ProviderAttachment("voice.m4a", "привет", "audio"))).promptWithFiles(false)
        assertTrue(text.startsWith("Audio transcript of «voice.m4a»"))
    }

    @Test
    fun `pdf text is inlined only when the model cannot read pdf`() {
        val r = request(documents = listOf(doc))
        assertTrue(r.promptWithFiles(nativeDocuments = false).contains("текст из pdf"))
        assertFalse(r.promptWithFiles(nativeDocuments = true).contains("текст из pdf"))
        assertTrue(r.promptWithFiles(nativeDocuments = true).contains("отчёт.pdf"))
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
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}],""" +
                    """"content":[{"type":"text","text":"ok"}],"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
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

    @Test
    fun `gemini gets the pdf as inlineData`() = runBlocking {
        val t = CapturingTransport()
        GeminiProvider(config(ProviderId.GEMINI), t, json).execute(request(documents = listOf(doc)))

        val parts = json.parseToJsonElement(t.body!!).jsonObject["contents"]!!.jsonArray
            .last().jsonObject["parts"]!!.jsonArray
        val inline = parts[0].jsonObject["inlineData"]!!.jsonObject
        assertEquals("application/pdf", inline["mimeType"]!!.jsonPrimitive.content)
        assertFalse(parts.last().jsonObject["text"]!!.jsonPrimitive.content.contains("текст из pdf"))
    }

    @Test
    fun `claude gets a document block`() = runBlocking {
        val t = CapturingTransport()
        AnthropicProvider(config(ProviderId.ANTHROPIC), t, json).execute(request(documents = listOf(doc)))

        val content = json.parseToJsonElement(t.body!!).jsonObject["messages"]!!.jsonArray
            .last().jsonObject["content"]!!.jsonArray
        assertEquals("document", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val source = content[0].jsonObject["source"]!!.jsonObject
        assertEquals("application/pdf", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("text", content.last().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `claude gets attachment text as plain content`() = runBlocking {
        val t = CapturingTransport()
        AnthropicProvider(config(ProviderId.ANTHROPIC), t, json)
            .execute(request(attachments = listOf(ProviderAttachment("a.txt", "строка"))))

        val content = json.parseToJsonElement(t.body!!).jsonObject["messages"]!!.jsonArray
            .last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("строка"))
        assertTrue(content.endsWith("О чём файл?"))
    }

    @Test
    fun `groq gets the pdf text instead of the file`() = runBlocking {
        val t = CapturingTransport()
        GroqProvider(config(ProviderId.GROQ), t, json).execute(request(documents = listOf(doc)))

        assertFalse(t.body!!.contains("QUJD"))
        assertTrue(t.body!!.contains("текст из pdf"))
    }

    @Test
    fun `only gemini and claude read documents`() {
        assertTrue(GeminiProvider(config(ProviderId.GEMINI), CapturingTransport(), json).capabilities.supportsDocuments)
        assertTrue(AnthropicProvider(config(ProviderId.ANTHROPIC), CapturingTransport(), json).capabilities.supportsDocuments)
        assertFalse(GroqProvider(config(ProviderId.GROQ), CapturingTransport(), json).capabilities.supportsDocuments)
    }

    @Test
    fun `execute body limit covers the pdf limit`() {
        assertEquals(validation.maxDocumentBodyBytes, validation.maxExecuteBodyBytes())
    }
}
