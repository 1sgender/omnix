package com.omnix.assistant.core.payments

import com.omnix.assistant.core.security.SecurityManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class HttpManualPaymentApiTest {
    private lateinit var server: MockWebServer
    private val token = "omx_" + "A".repeat(43)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(accessToken: String = token): HttpManualPaymentApi {
        val security = mockk<SecurityManager>(relaxed = true)
        every { security.getAccessToken() } returns accessToken
        val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        return HttpManualPaymentApi(security, base)
    }

    @Test
    fun `tariffs are parsed with plans limits contacts and requests`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(TARIFFS_JSON))
        val result = api().tariffs()
        val catalog = (result as PaymentsResult.Ok).value
        assertEquals(listOf("free", "pro", "omnix"), catalog.plans.map { it.id })
        assertEquals(14_900L, catalog.plans[1].priceMinor)
        assertEquals(300, catalog.plans[1].entitlements.dailyVoiceAi)
        assertEquals(ContactKind.TELEGRAM, catalog.contacts.single().kind)
        assertEquals(ManualOrderStatus.PENDING, catalog.orders.single().status)
        assertEquals("OMX-4821", catalog.orders.single().number)
        val recorded = server.takeRequest()
        assertEquals("/v1/billing/tariffs", recorded.path)
        assertEquals("Bearer $token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `creating a request returns the order and contacts`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(ORDER_JSON))
        val ticket = (api().createOrder("pro") as PaymentsResult.Ok).value
        assertEquals("OMX-4821", ticket.order.number)
        assertEquals(14_900L, ticket.order.amountMinor)
        assertEquals("@omnix_pay", ticket.contacts.single().value)
        val recorded = server.takeRequest()
        assertEquals("/v1/billing/manual/order", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"plan_id\":\"pro\""))
    }

    @Test
    fun `an existing open request comes back with 200`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(ORDER_JSON))
        assertTrue(api().createOrder("pro") is PaymentsResult.Ok)
    }

    @Test
    fun `http errors map to distinct results`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(PaymentsResult.Unauthorized, api().tariffs())
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(PaymentsResult.NoLicense, api().createOrder("pro"))
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(PaymentsResult.RateLimited, api().tariffs())
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(PaymentsResult.Unavailable, api().tariffs())
    }

    @Test
    fun `a malformed body is an unavailable server`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertEquals(PaymentsResult.Unavailable, api().tariffs())
    }

    @Test
    fun `without an access token nothing is sent`(): Unit = runBlocking {
        assertEquals(PaymentsResult.Unauthorized, api(accessToken = "").tariffs())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a dead server is no connection`(): Unit = runBlocking {
        val dead = MockWebServer()
        dead.start(InetAddress.getByName("127.0.0.1"), 0)
        val base = dead.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        dead.shutdown()
        val security = mockk<SecurityManager>(relaxed = true)
        every { security.getAccessToken() } returns token
        assertEquals(PaymentsResult.NoConnection, HttpManualPaymentApi(security, base).tariffs())
    }

    private companion object {
        const val ORDER = """{"order_id":"11111111-1111-1111-1111-111111111111","number":"OMX-4821","plan_id":"pro","status":"PENDING","amount_minor":14900,"currency":"TMT","created_at":"2026-11-14T10:00:00Z","expires_at":"2026-11-17T10:00:00Z"}"""
        const val CONTACTS = """[{"type":"telegram","value":"@omnix_pay","label":"Owner"}]"""
        const val ORDER_JSON = """{"success":true,"order":$ORDER,"created":true,"contacts":$CONTACTS,"order_ttl_hours":72,"request_id":"r"}"""
        const val ENTITLEMENTS = """{"daily_voice_ai":300,"daily_base_ai":300,"daily_agent_actions":100,"daily_web_search":100,"daily_translation_units":300,"daily_ear_minutes":120,"max_automations":2147483647,"screen_reading":true,"ui_control":true,"priority_routing":false,"premium_models":false,"max_clips":1,"daily_pro_ai":20,"monthly_pro_ai":250}"""
        const val TARIFFS_JSON = """{"success":true,"plans":[
            {"id":"free","name":"OMNIX Free","price_minor":0,"currency":"TMT","duration_days":0,"entitlements":$ENTITLEMENTS},
            {"id":"pro","name":"OMNIX Pro","price_minor":14900,"currency":"TMT","duration_days":30,"entitlements":$ENTITLEMENTS},
            {"id":"omnix","name":"OMNIX Max","price_minor":29900,"currency":"TMT","duration_days":30,"entitlements":$ENTITLEMENTS}],
            "contacts":$CONTACTS,"order_ttl_hours":72,"orders":[$ORDER],"request_id":"r"}"""
    }
}
