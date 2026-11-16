package com.omnix.assistant.data.remote

import com.omnix.assistant.agent.tools.intelligence.ServerSearchOutcome
import com.omnix.assistant.core.network.CloudProcessingMonitor
import com.omnix.assistant.core.security.SecurityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OmnixApiClientSearchTest {
    private lateinit var server: MockWebServer
    private lateinit var security: Security
    private lateinit var client: OmnixApiClient

    private class Security(initial: String) : SecurityManager {
        private val token = MutableStateFlow(initial)
        override fun getAccessToken(): String = token.value
        override fun saveAccessToken(token: String) { this.token.value = token }
        override fun clearAccessToken() { token.value = "" }
        override fun hasValidAccessToken(): Boolean = token.value.length >= 32
        override val accessTokenFlow: Flow<String> = token
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        security = Security(TOKEN)
        client = OmnixApiClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath)).build())
                }
                .build(),
            security,
            Json { ignoreUnknownKeys = true; isLenient = false },
            CloudProcessingMonitor()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search sends bearer token and parses results and answer`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"results":[{"title":"T","url":"https://a.example","snippet":"S"}],"answer":"Ответ","requestId":"r"}"""
            )
        )

        val outcome = client.search("глаза не врут", "ru")
        val request = server.takeRequest()

        assertEquals("/v1/search", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"query\":\"глаза не врут\""))
        val found = outcome as ServerSearchOutcome.Found
        assertEquals("Ответ", found.answer)
        assertEquals("https://a.example", found.results.single().url)
    }

    @Test
    fun `SEARCH_UNAVAILABLE and other errors become Unavailable`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{"success":false,"error":{"code":"SEARCH_UNAVAILABLE","message":"x","requestId":"r"}}"""
            )
        )
        assertTrue(client.search("q", "ru") is ServerSearchOutcome.Unavailable)

        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertTrue(client.search("q", "ru") is ServerSearchOutcome.Unavailable)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[]}"""))
        assertTrue(client.search("q", "ru") is ServerSearchOutcome.Unavailable)
    }

    @Test
    fun `network failure becomes Unavailable`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertTrue(client.search("q", "ru") is ServerSearchOutcome.Unavailable)
    }

    @Test
    fun `invalid token never reaches the network`() = runBlocking {
        security.saveAccessToken("short")

        assertTrue(client.search("q", "ru") is ServerSearchOutcome.Unavailable)
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val TOKEN = "test-access-token-abcdefghijklmnopqrstuvwxyz"
    }
}
