package com.omnix.server

import com.omnix.server.auth.ClientTier
import com.omnix.server.auth.TierAuthorizer
import com.omnix.server.auth.TokenAuthenticator
import com.omnix.server.config.RateLimitConfig
import com.omnix.server.config.ValidationConfig
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.SearchHttpHandler
import com.omnix.server.observability.ConsoleStructuredLogger
import com.omnix.server.observability.Metrics
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.HttpTransportResponse
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.ratelimit.SlidingWindowRateLimiter
import com.omnix.server.search.CachedSearchProvider
import com.omnix.server.search.SearchDailyLimiter
import com.omnix.server.search.SearchOutcome
import com.omnix.server.search.SearchProvider
import com.omnix.server.search.SearchResultItem
import com.omnix.server.search.SerperSearchProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SerperSearchTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class FakeTransport(private val status: Int, private val responseBody: String) : HttpTransport {
        var url: String = ""
        var headers: Map<String, String> = emptyMap()
        var sentBody: String = ""
        override suspend fun post(
            url: String, headers: Map<String, String>, body: String,
            connectTimeoutMs: Long, requestTimeoutMs: Long
        ): HttpTransportResponse {
            this.url = url
            this.headers = headers
            this.sentBody = body
            return HttpTransportResponse(status, responseBody)
        }
    }

    private class CountingProvider(private val outcome: SearchOutcome) : SearchProvider {
        var calls = 0
        override suspend fun search(query: String, lang: String?): SearchOutcome {
            calls++
            return outcome
        }
    }

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = instant
    }

    private val okBody = """
        {"organic":[
          {"title":"Погода","link":"https://example.com/a","snippet":"Сегодня <b>солнечно</b>"},
          {"title":"Без ссылки","link":"ftp://bad","snippet":"x"}],
         "answerBox":{"answer":"+12 °C"}}
    """.trimIndent()

    @Test
    fun `serper sends key, query and language and parses results and answer box`() = runBlocking {
        val transport = FakeTransport(200, okBody)
        val outcome = SerperSearchProvider(transport, "secret-key").search("погода в Ашхабаде", "ru")
        assertEquals("https://google.serper.dev/search", transport.url)
        assertEquals("secret-key", transport.headers["X-API-KEY"])
        assertTrue(transport.sentBody.contains("\"q\":\"погода в Ашхабаде\""))
        assertTrue(transport.sentBody.contains("\"hl\":\"ru\""))
        val success = outcome as SearchOutcome.Success
        assertEquals(1, success.results.size)
        assertEquals("https://example.com/a", success.results[0].url)
        assertEquals("Сегодня солнечно", success.results[0].snippet)
        assertEquals("+12 °C", success.answer)
    }

    @Test
    fun `serper does not set language that google does not know`() = runBlocking {
        val transport = FakeTransport(200, okBody)
        SerperSearchProvider(transport, "k").search("salam", "tk")
        assertTrue(!transport.sentBody.contains("\"hl\""))
    }

    @Test
    fun `serper maps http errors to failure kinds`() = runBlocking {
        suspend fun kind(status: Int): ProviderFailureKind =
            (SerperSearchProvider(FakeTransport(status, "{}"), "k").search("q", null) as SearchOutcome.Failure).kind
        assertEquals(ProviderFailureKind.AUTH, kind(401))
        assertEquals(ProviderFailureKind.AUTH, kind(402))
        assertEquals(ProviderFailureKind.RATE_LIMITED, kind(429))
        assertEquals(ProviderFailureKind.BAD_REQUEST, kind(400))
        assertEquals(ProviderFailureKind.SERVER_ERROR, kind(503))
    }

    @Test
    fun `serper broken json is a failure, not a crash`() = runBlocking {
        val outcome = SerperSearchProvider(FakeTransport(200, "not json"), "k").search("q", null)
        assertTrue(outcome is SearchOutcome.Failure)
    }

    @Test
    fun `cache returns repeated query without calling the provider again`() = runBlocking {
        val inner = CountingProvider(SearchOutcome.Success(listOf(SearchResultItem("t", "https://e.com", "s"))))
        val cached = CachedSearchProvider(inner)
        cached.search("Погода  в Ашхабаде", "ru")
        cached.search("погода в ашхабаде", "ru")
        assertEquals(1, inner.calls)
        cached.search("погода в ашхабаде", "en")
        assertEquals(2, inner.calls)
    }

    @Test
    fun `cache expires after ttl and never stores failures`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-17T10:00:00Z"))
        val inner = CountingProvider(SearchOutcome.Success(listOf(SearchResultItem("t", "https://e.com", "s"))))
        val cached = CachedSearchProvider(inner, ttlMillis = 1_000, clock = clock)
        cached.search("q", null)
        clock.instant = clock.instant.plusMillis(1_500)
        cached.search("q", null)
        assertEquals(2, inner.calls)

        val failing = CountingProvider(SearchOutcome.Failure(ProviderFailureKind.TIMEOUT))
        val cachedFail = CachedSearchProvider(failing, clock = clock)
        cachedFail.search("q", null)
        cachedFail.search("q", null)
        assertEquals(2, failing.calls)
    }

    @Test
    fun `daily limiter blocks after the limit and resets next utc day`() {
        val clock = MutableClock(Instant.parse("2026-11-17T23:59:00Z"))
        val limiter = SearchDailyLimiter(clock)
        repeat(3) { assertEquals(SearchDailyLimiter.Decision.Allowed, limiter.tryAcquire("a", 3)) }
        val blocked = limiter.tryAcquire("a", 3)
        assertTrue(blocked is SearchDailyLimiter.Decision.Exceeded)
        assertEquals(60L, (blocked as SearchDailyLimiter.Decision.Exceeded).retryAfterSeconds)
        // другой клиент не задет
        assertEquals(SearchDailyLimiter.Decision.Allowed, limiter.tryAcquire("b", 3))
        clock.instant = Instant.parse("2026-11-18T00:00:05Z")
        assertEquals(SearchDailyLimiter.Decision.Allowed, limiter.tryAcquire("a", 3))
    }

    @Test
    fun `handler answers 429 after the free plan search limit`() = runBlocking {
        val token = "test-client-token-abcdefghijklmnopqrstuvwxyz"
        val inner = CountingProvider(SearchOutcome.Success(listOf(SearchResultItem("t", "https://e.com", "s"))))
        val handler = SearchHttpHandler(
            authenticator = TokenAuthenticator(mapOf(token to "android-1")) { ClientTier.FREE },
            authorizer = TierAuthorizer(),
            rateLimiter = SlidingWindowRateLimiter(RateLimitConfig(perMinute = 1000, perDay = 10_000)),
            validation = ValidationConfig(),
            logger = ConsoleStructuredLogger(sink = {}),
            metrics = Metrics(),
            json = json,
            entitlementChecker = { true },
            searchProvider = inner
        )
        fun post() = HttpRequestContext(
            method = "POST", path = "/v1/search", authorizationHeader = "Bearer $token",
            body = """{"query":"курс доллара"}""", contentLength = 30
        )
        repeat(5) { assertEquals(200, handler.handle(post()).status) }
        val limited = handler.handle(post())
        assertEquals(429, limited.status)
        assertTrue(limited.headers["Retry-After"]?.toLongOrNull()?.let { it > 0 } == true)
    }
}
