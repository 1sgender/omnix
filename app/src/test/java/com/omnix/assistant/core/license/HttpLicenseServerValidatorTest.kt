package com.omnix.assistant.core.license

import com.omnix.assistant.core.security.SecurityManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** Transport blips retry once silently; definitive answers never retry. */
class HttpLicenseServerValidatorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        // Именно 127.0.0.1, а не localhost: localhost резолвится и в ::1, и в
        // 127.0.0.1, а MockWebServer слушает один адрес. После первой упавшей
        // попытки отравленный маршрут уходит вниз, вторая попытка идёт на
        // мёртвый адрес и (retryOnConnectionFailure=false) падает без
        // перебора — тест становится флакающим от порядка DNS-ответов.
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrlFor(target: MockWebServer): String =
        target.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')

    private fun validator(baseUrl: String = baseUrlFor(server)) =
        HttpLicenseServerValidator(mockk<SecurityManager>(relaxed = true), baseUrl)

    @Test
    fun `transport failure retries once then succeeds`(): Unit = runBlocking {
        val attempts = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (attempts.incrementAndGet() == 1) {
                    // DISCONNECT_AT_START здесь не годится: MockWebServer 4.x
                    // читает его только через dispatcher.peek() (очередь), а из
                    // dispatch() он игнорируется — ушёл бы обычный 200 с пустым
                    // телом. AFTER_REQUEST рвёт соединение без ответа всегда.
                    MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST }
                } else {
                    MockResponse().setResponseCode(200).setBody(SUCCESS_JSON)
                }
            }
        }
        val result = validator().redeem("OMX-ABCDE-FGHJK-LMNPQ-RSTUV", "OMX-TEST-DEVICE-ABCDEF")
        assertTrue(result is ServerRedemptionResult.Success)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `persistent transport failure surfaces as no connection`(): Unit = runBlocking {
        val dead = MockWebServer()
        dead.start(InetAddress.getByName("127.0.0.1"), 0)
        val deadUrl = baseUrlFor(dead)
        dead.shutdown()
        val result = validator(deadUrl).redeem("OMX-ABCDE-FGHJK-LMNPQ-RSTUV", "OMX-TEST-DEVICE-ABCDEF")
        assertEquals(ServerRedemptionResult.NoConnection, result)
    }

    @Test
    fun `bad code does not retry`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val result = validator().redeem("OMX-ABCDE-FGHJK-LMNPQ-RSTUV", "OMX-TEST-DEVICE-ABCDEF")
        assertEquals(ServerRedemptionResult.NotRedeemable, result)
        assertEquals(1, server.requestCount)
    }

    private companion object {
        // Фиктивный токен собирается программно: секретоподобных литералов
        // в исходнике нет (иначе срабатывает gitleaks generic-api-key).
        val SUCCESS_JSON = """{"access_token":"""" + "omx_" + "A".repeat(43) +
            """","plan_id":"omnix","product_id":"omnix","starts_at":"2026-09-16T00:00:00Z","expires_at":"2026-10-16T00:00:00Z","billing_status":"GRANTED"}"""
    }
}
