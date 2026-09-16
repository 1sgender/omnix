package com.omnix.server

import com.omnix.server.http.AppLatestResponse
import com.omnix.server.http.AppUpdateHttpHandler
import com.omnix.server.http.HttpRequestContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateHttpHandlerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun request(
        path: String = AppUpdateHttpHandler.PATH_APP_LATEST,
        method: String = "GET",
        rawQuery: String? = "channel=staging"
    ) = HttpRequestContext(
        method = method,
        path = path,
        authorizationHeader = null,
        body = "",
        contentLength = 0
    ).copy(rawQuery = rawQuery)

    private fun releaseJson(
        versionCodeLine: String = "versionCode=35071367107",
        assetName: String = "OMNIX-staging.apk",
        assetUrl: String = "https://github.com/freelanceTM/jarvis/releases/download/staging/OMNIX-staging.apk"
    ) = """
        {"tag_name":"staging",
         "body":"$versionCodeLine\nsha=9782910cd3e9570d5931cd4a5f7567735c7380a3",
         "published_at":"2026-09-16T08:00:00Z",
         "assets":[{"name":"$assetName","browser_download_url":"$assetUrl","size":42000000}]}
    """.trimIndent()

    @Test
    fun `latest returns version and download url without auth`() = runBlocking {
        val handler = AppUpdateHttpHandler(
            releaseRepo = "freelanceTM/jarvis",
            fetchReleaseJson = { releaseJson() }
        )

        val response = handler.handle(request())!!

        assertEquals(200, response.status)
        val parsed = json.decodeFromString(AppLatestResponse.serializer(), response.body)
        assertTrue(parsed.success)
        assertEquals("staging", parsed.channel)
        assertEquals(35071367107L, parsed.versionCode)
        assertTrue(parsed.url.startsWith("https://"))
        assertEquals(42000000L, parsed.sizeBytes)
        assertFalse(parsed.stale)
    }

    @Test
    fun `unknown channel is rejected`() = runBlocking {
        var fetched = false
        val handler = AppUpdateHttpHandler(
            fetchReleaseJson = { fetched = true; releaseJson() }
        )

        val response = handler.handle(request(rawQuery = "channel=hacker%20x"))!!

        assertEquals(400, response.status)
        assertFalse("must not touch GitHub for unknown channel", fetched)
    }

    @Test
    fun `non-get method returns 405`() = runBlocking {
        val handler = AppUpdateHttpHandler(fetchReleaseJson = { releaseJson() })

        val response = handler.handle(request(method = "POST"))!!

        assertEquals(405, response.status)
    }

    @Test
    fun `foreign paths fall through to next handler`() = runBlocking {
        val handler = AppUpdateHttpHandler(fetchReleaseJson = { releaseJson() })

        assertNull(handler.handle(request(path = "/v1/health", rawQuery = null)))
    }

    @Test
    fun `github outage yields 503 and later serves stale cache`() = runBlocking {
        var json: String? = releaseJson()
        var now = 1_000_000L
        val handler = AppUpdateHttpHandler(
            fetchReleaseJson = { json },
            clockMs = { now }
        )

        val primed = handler.handle(request())!!
        assertEquals(200, primed.status)

        // Кэш свежий — GitHub не трогаем даже при аварии.
        json = null
        now += 60_000L
        val cached = handler.handle(request())!!
        assertEquals(200, cached.status)
        assertFalse(jsonObj(cached.body).stale)

        // Кэш протух — отдаём stale вместо 503.
        now += AppUpdateHttpHandler.CACHE_TTL_MS + 1
        val stale = handler.handle(request())!!
        assertEquals(200, stale.status)
        assertTrue(jsonObj(stale.body).stale)
    }

    @Test
    fun `github outage with empty cache yields 503`() = runBlocking {
        val handler = AppUpdateHttpHandler(fetchReleaseJson = { null })

        val response = handler.handle(request())!!

        assertEquals(503, response.status)
    }

    @Test
    fun `release without apk asset yields 503`() = runBlocking {
        val handler = AppUpdateHttpHandler(
            fetchReleaseJson = { releaseJson(assetName = "notes.txt") }
        )

        val response = handler.handle(request())!!

        assertEquals(503, response.status)
    }

    @Test
    fun `missing and malformed query default to staging channel`() = runBlocking {
        val handler = AppUpdateHttpHandler(fetchReleaseJson = { releaseJson() })

        val noQuery = handler.handle(request(rawQuery = null))!!
        val malformed = handler.handle(request(rawQuery = "channel"))!!

        assertEquals(200, noQuery.status)
        assertEquals(200, malformed.status)
        assertEquals("staging", jsonObj(noQuery.body).channel)
    }

    @Test
    fun `malformed release json yields 503`() = runBlocking {
        val handler = AppUpdateHttpHandler(fetchReleaseJson = { "not-json{{{" })

        val response = handler.handle(request())!!

        assertEquals(503, response.status)
    }

    @Test
    fun `throwing fetcher yields 503`() = runBlocking {
        val handler = AppUpdateHttpHandler(
            fetchReleaseJson = { throw java.io.IOException("boom") }
        )

        val response = handler.handle(request())!!

        assertEquals(503, response.status)
    }

    @Test
    fun `default fetcher reads release json over http`() = runBlocking {
        val payload = releaseJson()
        val server = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress("127.0.0.1", 0), 0
        )
        try {
            server.createContext("/repos/acme/app/releases/tags/staging") { exchange ->
                val bytes = payload.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val handler = AppUpdateHttpHandler(
                releaseRepo = "acme/app",
                githubApiBase = "http://127.0.0.1:${server.address.port}"
            )

            val response = handler.handle(request())!!

            assertEquals(200, response.status)
            assertEquals(35071367107L, jsonObj(response.body).versionCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `default fetcher outage yields 503`() = runBlocking {
        // Неоткрываемый порт: connection refused без выхода в сеть.
        val handler = AppUpdateHttpHandler(
            releaseRepo = "acme/app",
            githubApiBase = "http://127.0.0.1:1"
        )

        val response = handler.handle(request())!!

        assertEquals(503, response.status)
    }

    private fun jsonObj(body: String): AppLatestResponse =
        json.decodeFromString(AppLatestResponse.serializer(), body)
}
