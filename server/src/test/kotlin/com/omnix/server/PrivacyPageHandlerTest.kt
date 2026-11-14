package com.omnix.server

import com.omnix.server.http.HttpRequestContext
import com.omnix.server.privacy.PrivacyPageHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PrivacyPageHandlerTest {

    private fun get(
        handler: PrivacyPageHandler,
        path: String,
        query: String? = null,
        method: String = "GET",
        headers: Map<String, String> = emptyMap()
    ) = handler.handle(
        HttpRequestContext(
            method = method, path = path, authorizationHeader = null,
            body = "", contentLength = 0, headers = headers, rawQuery = query
        )
    )

    @Test
    fun `serves russian page without authorization by default`() {
        val response = get(PrivacyPageHandler(), "/privacy")!!
        assertEquals(200, response.status)
        assertTrue(response.headers.getValue("Content-Type").startsWith("text/html"))
        assertTrue(response.body.contains("lang=\"ru\""))
        assertTrue(response.body.contains("Политика конфиденциальности"))
    }

    @Test
    fun `lang parameter selects english and unknown value falls back to russian`() {
        val handler = PrivacyPageHandler()
        val en = get(handler, "/privacy", "lang=en")!!
        assertEquals(200, en.status)
        assertTrue(en.body.contains("lang=\"en\""))
        assertTrue(en.body.contains("Privacy Policy"))
        val unknown = get(handler, "/privacy", "lang=de")!!
        assertTrue(unknown.body.contains("lang=\"ru\""))
        assertTrue(get(handler, "/privacy?lang=en")!!.body.contains("lang=\"en\""))
    }

    @Test
    fun `accept-language is used when lang is absent`() {
        val en = get(PrivacyPageHandler(), "/privacy", headers = mapOf("Accept-Language" to "en-US,en;q=0.9"))!!
        assertTrue(en.body.contains("lang=\"en\""))
    }

    @Test
    fun `sets cache headers and honours If-None-Match`() {
        val handler = PrivacyPageHandler()
        val first = get(handler, "/privacy/")!!
        assertTrue(first.headers.getValue("Cache-Control").startsWith("public"))
        val etag = first.headers.getValue("ETag")
        val cached = get(handler, "/privacy", headers = mapOf("If-None-Match" to etag))!!
        assertEquals(304, cached.status)
        assertEquals("", cached.body)
    }

    @Test
    fun `other paths and methods are not served`() {
        val handler = PrivacyPageHandler()
        assertNull(get(handler, "/privacy-x"))
        assertNull(get(handler, "/admin"))
        assertEquals(405, get(handler, "/privacy", method = "POST")!!.status)
        assertEquals("", get(handler, "/privacy", method = "HEAD")!!.body)
    }

    @Test
    fun `placeholder stays visible until contact email is configured and is escaped`() {
        assertTrue(get(PrivacyPageHandler(), "/privacy")!!.body.contains("{{CONTACT_EMAIL}}"))
        val body = get(PrivacyPageHandler("privacy@example.com"), "/privacy")!!.body
        assertFalse(body.contains("{{CONTACT_EMAIL}}"))
        assertTrue(body.contains("mailto:privacy@example.com"))
        // Некорректное значение env не попадает в HTML.
        val bad = get(PrivacyPageHandler("<script>x</script>"), "/privacy")!!.body
        assertFalse(bad.contains("<script>"))
    }

    @Test
    fun `bundled resources match the primary documents in docs`() {
        val root = repositoryRoot()
        assertEquals(
            Files.readString(root.resolve("docs/PRIVACY_POLICY.md")),
            Files.readString(root.resolve("server/src/main/resources/privacy/PRIVACY_POLICY.ru.md"))
        )
        assertEquals(
            Files.readString(root.resolve("docs/PRIVACY_POLICY.en.md")),
            Files.readString(root.resolve("server/src/main/resources/privacy/PRIVACY_POLICY.en.md"))
        )
    }

    @Test
    fun `privacy route is registered in server composition ahead of authenticated handlers`() {
        val main = Files.readString(repositoryRoot().resolve("server/src/main/kotlin/com/omnix/server/Main.kt"))
        assertTrue(main.contains("privacyPageHandler.handle(request)"))
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Repository root not found")
    }
}
