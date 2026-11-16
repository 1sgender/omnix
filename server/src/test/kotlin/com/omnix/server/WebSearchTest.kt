package com.omnix.server

import com.omnix.server.api.ApiErrorResponse
import com.omnix.server.api.SearchResponse
import com.omnix.server.auth.ClientTier
import com.omnix.server.auth.TierAuthorizer
import com.omnix.server.auth.TokenAuthenticator
import com.omnix.server.config.RateLimitConfig
import com.omnix.server.config.ValidationConfig
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.SearchHttpHandler
import com.omnix.server.observability.ConsoleStructuredLogger
import com.omnix.server.observability.Metrics
import com.omnix.server.provider.ApiKeyPool
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.HttpTransportResponse
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.provider.TransportException
import com.omnix.server.ratelimit.SlidingWindowRateLimiter
import com.omnix.server.search.BraveSearchProvider
import com.omnix.server.search.ChainedSearchProvider
import com.omnix.server.search.GeminiGroundedSearchProvider
import com.omnix.server.search.SearchOutcome
import com.omnix.server.search.SearchProvider
import com.omnix.server.search.SearchResultItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val token = "test-client-token-abcdefghijklmnopqrstuvwxyz"

    // -------------------------------------------------------------- фейки

    private class FakeTransport(private val responses: List<HttpTransportResponse>) : HttpTransport {
        val urls = mutableListOf<String>()
        val headersSeen = mutableListOf<Map<String, String>>()
        var lastBody: String = ""

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse {
            urls += url
            headersSeen += headers
            lastBody = body
            return responses[minOf(urls.size - 1, responses.size - 1)]
        }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse {
            urls += url
            headersSeen += headers
            return responses[minOf(urls.size - 1, responses.size - 1)]
        }
    }

    private class FakeProvider(private val outcome: SearchOutcome) : SearchProvider {
        var calls = 0
        var lastQuery: String? = null
        var lastLang: String? = null
        override suspend fun search(query: String, lang: String?): SearchOutcome {
            calls++
            lastQuery = query
            lastLang = lang
            return outcome
        }
    }

    private class ThrowingTransport : HttpTransport {
        override suspend fun post(
            url: String, headers: Map<String, String>, body: String,
            connectTimeoutMs: Long, requestTimeoutMs: Long
        ): HttpTransportResponse = throw TransportException(ProviderFailureKind.TIMEOUT, "timeout")

        override suspend fun get(
            url: String, headers: Map<String, String>,
            connectTimeoutMs: Long, requestTimeoutMs: Long
        ): HttpTransportResponse = throw TransportException(ProviderFailureKind.CONNECTION, "down")
    }

    private fun handler(
        provider: SearchProvider?,
        entitlementAllowed: Boolean = true,
        rateLimit: RateLimitConfig = RateLimitConfig(perMinute = 100, perDay = 1000)
    ) = SearchHttpHandler(
        authenticator = TokenAuthenticator(mapOf(token to "android-1")) { ClientTier.FREE },
        authorizer = TierAuthorizer(),
        rateLimiter = SlidingWindowRateLimiter(rateLimit),
        validation = ValidationConfig(),
        logger = ConsoleStructuredLogger(sink = {}),
        metrics = Metrics(),
        json = json,
        entitlementChecker = { entitlementAllowed },
        searchProvider = provider
    )

    private fun post(body: String, bearer: String? = token) = HttpRequestContext(
        method = "POST",
        path = "/v1/search",
        authorizationHeader = bearer?.let { "Bearer $it" },
        body = body,
        contentLength = body.length.toLong()
    )

    private fun errorCode(body: String) = json.decodeFromString(ApiErrorResponse.serializer(), body).error.code

    private val sample = SearchOutcome.Success(
        listOf(SearchResultItem("Глаза не врут", "https://example.org/song", "Песня")),
        answer = "Это песня."
    )

    // ------------------------------------------------------------ эндпоинт

    @Test
    fun `without any provider the endpoint says SEARCH_UNAVAILABLE and not 500`() = runBlocking {
        val response = handler(provider = null).handle(post("""{"query":"погода","lang":"ru"}"""))

        assertEquals(503, response.status)
        assertEquals("SEARCH_UNAVAILABLE", errorCode(response.body))
    }

    @Test
    fun `fake provider answer is returned with results and requestId`() = runBlocking {
        val provider = FakeProvider(sample)

        val response = handler(provider).handle(post("""{"query":"  найди песню глаза не врут ","lang":"ru","requestId":"r-1"}"""))

        assertEquals(200, response.status)
        val body = json.decodeFromString(SearchResponse.serializer(), response.body)
        assertEquals("r-1", body.requestId)
        assertEquals("Это песня.", body.answer)
        assertEquals("https://example.org/song", body.results.single().url)
        assertEquals("найди песню глаза не врут", provider.lastQuery)
        assertEquals("ru", provider.lastLang)
    }

    @Test
    fun `provider failure maps to SEARCH_UNAVAILABLE`() = runBlocking {
        val provider = FakeProvider(SearchOutcome.Failure(ProviderFailureKind.TIMEOUT))

        val response = handler(provider).handle(post("""{"query":"новости"}"""))

        assertEquals(503, response.status)
        assertEquals("SEARCH_UNAVAILABLE", errorCode(response.body))
    }

    @Test
    fun `missing or wrong token is unauthorized and never reaches the provider`() = runBlocking {
        val provider = FakeProvider(sample)
        val h = handler(provider)

        assertEquals(401, h.handle(post("""{"query":"x"}""", bearer = null)).status)
        assertEquals(401, h.handle(post("""{"query":"x"}""", bearer = "wrong-token-wrong-token-wrong-token-1")).status)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `plan without entitlement gets PAYMENT_REQUIRED`() = runBlocking {
        val provider = FakeProvider(sample)

        val response = handler(provider, entitlementAllowed = false).handle(post("""{"query":"x"}"""))

        assertEquals(402, response.status)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `rate limit is enforced`() = runBlocking {
        val provider = FakeProvider(sample)
        val h = handler(provider, rateLimit = RateLimitConfig(perMinute = 1, perDay = 1000))

        assertEquals(200, h.handle(post("""{"query":"один"}""")).status)
        assertEquals(429, h.handle(post("""{"query":"два"}""")).status)
    }

    @Test
    fun `blank, oversized and malformed queries are rejected before the provider`() = runBlocking {
        val provider = FakeProvider(sample)
        val h = handler(provider)

        assertEquals(400, h.handle(post("""{"query":"   "}""")).status)
        assertEquals(400, h.handle(post("""{"query":"${"a".repeat(SearchHttpHandler.MAX_QUERY_CHARS + 1)}"}""")).status)
        assertEquals(400, h.handle(post("not json")).status)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `a song title passes the privacy gate but a secret does not`() = runBlocking {
        val provider = FakeProvider(sample)
        val h = handler(provider)

        assertEquals(200, h.handle(post("""{"query":"песня глаза не врут"}""")).status)
        assertEquals(1, provider.calls)

        val secret = h.handle(post("""{"query":"мой пароль: hunter2hunter2"}"""))
        assertEquals(403, secret.status)
        assertEquals("PRIVACY_POLICY_VIOLATION", errorCode(secret.body))
        assertEquals(1, provider.calls)
    }

    // ------------------------------------------------------------ Brave

    @Test
    fun `brave results are parsed, cleaned and the key goes in a header`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                HttpTransportResponse(
                    200,
                    """{"web":{"results":[
                        {"title":"A &amp; B","url":"https://a.example/x","description":"<strong>Глаза</strong> не врут"},
                        {"title":"bad","url":"javascript:alert(1)","description":"x"}
                    ]}}"""
                )
            )
        )
        val brave = BraveSearchProvider(transport, apiKey = "brave-key")

        val outcome = brave.search("глаза не врут", "ru") as SearchOutcome.Success

        assertEquals(1, outcome.results.size)
        assertEquals("A & B", outcome.results[0].title)
        assertEquals("Глаза не врут", outcome.results[0].snippet)
        assertEquals("brave-key", transport.headersSeen.single()["X-Subscription-Token"])
        val url = transport.urls.single()
        assertTrue(url.startsWith(BraveSearchProvider.DEFAULT_URL + "?"))
        assertTrue(url.contains("search_lang=ru"))
        assertFalse(url.contains("brave-key"))
    }

    @Test
    fun `brave maps http statuses to failure kinds and skips unsupported language`() = runBlocking {
        fun kind(status: Int) = (BraveSearchProvider(
            FakeTransport(listOf(HttpTransportResponse(status, ""))), "k"
        ).let { p -> runBlocking { p.search("q", "tk") } } as SearchOutcome.Failure).kind

        assertEquals(ProviderFailureKind.AUTH, kind(401))
        assertEquals(ProviderFailureKind.RATE_LIMITED, kind(429))
        assertEquals(ProviderFailureKind.SERVER_ERROR, kind(503))
        assertNull(BraveSearchProvider.braveLanguage("tk"))
        assertEquals("tr", BraveSearchProvider.braveLanguage("TR"))

        val transport = FakeTransport(listOf(HttpTransportResponse(200, """{"web":{"results":[]}}""")))
        BraveSearchProvider(transport, "k").search("q", "tk")
        assertFalse(transport.urls.single().contains("search_lang"))
    }

    @Test
    fun `brave transport failure is reported as failure`() = runBlocking {
        val outcome = BraveSearchProvider(ThrowingTransport(), "k").search("q", null)

        assertEquals(SearchOutcome.Failure(ProviderFailureKind.CONNECTION), outcome)
    }

    // ----------------------------------------------------------- Gemini

    private fun grounded(text: String) = HttpTransportResponse(
        200,
        """{"candidates":[{"content":{"parts":[{"text":"$text"}]},
            "groundingMetadata":{"groundingChunks":[
              {"web":{"uri":"https://s1.example/a","title":"Источник 1"}},
              {"web":{"uri":"https://s1.example/a","title":"Дубль"}},
              {"web":{"uri":"ftp://bad","title":"плохой"}}
            ]}}]}"""
    )

    @Test
    fun `gemini grounding gives a short answer and distinct sources`() = runBlocking {
        val transport = FakeTransport(listOf(grounded("Песню исполняет группа.")))
        val gemini = GeminiGroundedSearchProvider(transport, ApiKeyPool(listOf("g1")), model = "gemini-test")

        val outcome = gemini.search("глаза не врут", "ru") as SearchOutcome.Success

        assertEquals("Песню исполняет группа.", outcome.answer)
        assertEquals(listOf("https://s1.example/a"), outcome.results.map { it.url })
        assertTrue(transport.urls.single().endsWith("/gemini-test:generateContent"))
        assertEquals("g1", transport.headersSeen.single()["x-goog-api-key"])
        assertTrue(transport.lastBody.contains("google_search"))
    }

    @Test
    fun `gemini rotates to the next key on 429`() = runBlocking {
        val transport = FakeTransport(listOf(HttpTransportResponse(429, ""), grounded("ok")))
        val gemini = GeminiGroundedSearchProvider(transport, ApiKeyPool(listOf("g1", "g2")), model = "m")

        val outcome = gemini.search("q", null)

        assertTrue(outcome is SearchOutcome.Success)
        assertEquals(listOf("g1", "g2"), transport.headersSeen.map { it["x-goog-api-key"] })
    }

    @Test
    fun `gemini without keys or text is a failure`() = runBlocking {
        val noKeys = GeminiGroundedSearchProvider(FakeTransport(emptyList()), ApiKeyPool(emptyList()), model = "m")
        assertEquals(SearchOutcome.Failure(ProviderFailureKind.NOT_CONFIGURED), noKeys.search("q", null))

        val empty = GeminiGroundedSearchProvider(
            FakeTransport(listOf(HttpTransportResponse(200, """{"candidates":[]}"""))),
            ApiKeyPool(listOf("g")), model = "m"
        )
        assertTrue(empty.search("q", null) is SearchOutcome.Failure)
    }

    // ----------------------------------------------------------- цепочка

    @Test
    fun `chain falls through to the next provider on failure and on empty results`() = runBlocking {
        val failing = FakeProvider(SearchOutcome.Failure(ProviderFailureKind.SERVER_ERROR))
        val empty = FakeProvider(SearchOutcome.Success(emptyList()))
        val good = FakeProvider(sample)

        val outcome = ChainedSearchProvider(listOf("a" to failing, "b" to empty, "c" to good)).search("q", null)

        assertEquals(sample, outcome)
        assertEquals(1, failing.calls)
        assertEquals(1, empty.calls)
    }

    @Test
    fun `chain from config follows SEARCH_PROVIDERS order and skips unconfigured`() = runBlocking {
        val brave = FakeProvider(sample)
        val gemini = FakeProvider(SearchOutcome.Success(emptyList(), "gemini"))
        val available = mapOf("brave" to brave, "gemini" to gemini)

        assertNull(ChainedSearchProvider.fromConfig("brave", emptyMap()))
        assertNull(ChainedSearchProvider.fromConfig("unknown", available))
        // Один настроенный провайдер: цепочки нет, он сам.
        assertTrue(ChainedSearchProvider.fromConfig(null, mapOf("gemini" to gemini)) === gemini)

        val geminiFirst = ChainedSearchProvider.fromConfig("gemini,brave", available)!!
        val outcome = geminiFirst.search("q", null)
        assertEquals("gemini", (outcome as SearchOutcome.Success).answer)
        assertEquals(0, brave.calls)
    }
}
