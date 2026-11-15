package com.omnix.server.privacy

import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.HttpResponseContext
import java.security.MessageDigest

/**
 * Публичная страница политики конфиденциальности: `GET /privacy?lang=ru|en`.
 *
 * Без авторизации (её читают до установки приложения и магазины). Текст берётся
 * из ресурсов `privacy/PRIVACY_POLICY.<lang>.md` (копии docs/PRIVACY_POLICY*.md,
 * совпадение проверяет тест) и превращается в HTML маленьким конвертером
 * подмножества Markdown: заголовки, списки, абзацы, **жирный**, `код`.
 * Весь пользовательский текст экранируется до разметки.
 *
 * Язык: `?lang=`, иначе `Accept-Language`, иначе русский (первичный текст).
 * Плейсхолдер `{{CONTACT_EMAIL}}` заменяется адресом из [contactEmail]; пока
 * владелец его не задал, остаётся видимым в тексте.
 */
class PrivacyPageHandler(contactEmail: String? = null) {

    private val email: String? = contactEmail?.trim()?.takeIf { EMAIL.matches(it) }

    private val pages: Map<String, Page> = LANGUAGES.associateWith { lang -> buildPage(lang) }

    private class Page(val html: String, val etag: String)

    fun handle(request: HttpRequestContext): HttpResponseContext? {
        val path = request.path.substringBefore('?').trimEnd('/')
        if (path != PATH) return null
        if (request.method != "GET" && request.method != "HEAD") {
            return HttpResponseContext(405, "{\"error\":\"method_not_allowed\"}", mapOf("Allow" to "GET, HEAD"))
        }
        val page = pages.getValue(pickLanguage(request))
        val headers = mapOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Cache-Control" to "public, max-age=3600",
            "ETag" to page.etag,
            "Vary" to "Accept-Language",
            "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"
        )
        if (request.header("If-None-Match") == page.etag) {
            return HttpResponseContext(304, "", headers)
        }
        return HttpResponseContext(200, if (request.method == "HEAD") "" else page.html, headers)
    }

    private fun pickLanguage(request: HttpRequestContext): String {
        val fromQuery = (request.rawQuery ?: request.path.substringAfter('?', ""))
            .split('&')
            .firstOrNull { it.startsWith("lang=") }
            ?.removePrefix("lang=")
            ?.lowercase()
        fromQuery?.takeIf { it in LANGUAGES }?.let { return it }
        val accept = request.header("Accept-Language")?.trim()?.lowercase().orEmpty()
        return if (accept.startsWith("en")) "en" else "ru"
    }

    private fun buildPage(lang: String): Page {
        val markdown = PrivacyPageHandler::class.java.classLoader
            .getResourceAsStream("privacy/PRIVACY_POLICY.$lang.md")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("privacy resource missing for $lang")
        val title = if (lang == "en") "OMNIX Privacy Policy" else "Политика конфиденциальности OMNIX"
        val other = if (lang == "en") "<a href=\"?lang=ru\">Русский</a>" else "<a href=\"?lang=en\">English</a>"
        val html = "<!doctype html><html lang=\"$lang\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title><style>$STYLE</style></head><body><main>" +
            "<p class=\"lang\">$other</p>${render(markdown)}</main></body></html>"
        val digest = MessageDigest.getInstance("SHA-256").digest(html.toByteArray(Charsets.UTF_8))
        val etag = "\"" + digest.take(ETAG_BYTES).joinToString("") { "%02x".format(it) } + "\""
        return Page(html, etag)
    }

    private fun render(markdown: String): String {
        val out = StringBuilder()
        var inList = false
        val paragraph = StringBuilder()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out.append("<p>").append(inline(paragraph.toString().trim())).append("</p>")
                paragraph.clear()
            }
        }
        fun closeList() {
            if (inList) {
                out.append("</ul>")
                inList = false
            }
        }
        for (line in markdown.lines()) {
            val heading = HEADING.matchEntire(line)
            when {
                heading != null -> {
                    flushParagraph()
                    closeList()
                    val level = heading.groupValues[1].length
                    out.append("<h$level>").append(inline(heading.groupValues[2])).append("</h$level>")
                }
                line.startsWith("- ") -> {
                    flushParagraph()
                    if (!inList) {
                        out.append("<ul>")
                        inList = true
                    }
                    out.append("<li>").append(inline(line.removePrefix("- "))).append("</li>")
                }
                line.isBlank() -> {
                    flushParagraph()
                    closeList()
                }
                else -> paragraph.append(line).append(' ')
            }
        }
        flushParagraph()
        closeList()
        return out.toString()
    }

    private fun inline(text: String): String {
        var html = escape(text)
        html = BOLD.replace(html) { "<strong>${it.groupValues[1]}</strong>" }
        html = CODE.replace(html) { "<code>${it.groupValues[1]}</code>" }
        val contact = email?.let { "<a href=\"mailto:${escape(it)}\">${escape(it)}</a>" } ?: PLACEHOLDER
        return html.replace(PLACEHOLDER, contact)
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        const val PATH = "/privacy"
        private const val PLACEHOLDER = "{{CONTACT_EMAIL}}"
        private const val ETAG_BYTES = 12
        private val LANGUAGES = listOf("ru", "en")
        private val EMAIL = Regex("""[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,24}""")
        private val HEADING = Regex("""^(#{1,3}) (.+)$""")
        private val BOLD = Regex("""\*\*(.+?)\*\*""")
        private val CODE = Regex("""`([^`]+)`""")
        private const val STYLE = "body{margin:0;background:#fafaf7;color:#1a1a1a;" +
            "font:16px/1.6 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}" +
            "main{max-width:760px;margin:0 auto;padding:24px 20px 64px}" +
            "h1{font-size:28px;line-height:1.25}h2{font-size:20px;margin-top:32px}h3{font-size:17px}" +
            "li{margin:4px 0}code{background:#eee;padding:1px 4px;border-radius:4px}" +
            "a{color:#1a5fb4}.lang{text-align:right;margin:0}" +
            "@media(prefers-color-scheme:dark){body{background:#121212;color:#e8e8e8}" +
            "code{background:#2a2a2a}a{color:#8ab4f8}}"
    }
}
