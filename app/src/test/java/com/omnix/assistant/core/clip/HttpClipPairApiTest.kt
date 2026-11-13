package com.omnix.assistant.core.clip

import com.omnix.assistant.core.security.SecurityManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** Транспорт реестра пар: разбор ответов, коды отказов, сбои сети. */
class HttpClipPairApiTest {
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

    private fun api(storedToken: String = token): HttpClipPairApi {
        val security = mockk<SecurityManager>(relaxed = true)
        every { security.getAccessToken() } returns storedToken
        val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        return HttpClipPairApi(security, base)
    }

    @Test
    fun `bind without account sends no authorization and parses trial`(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"$token","clip_serial":"OMX-1","bound_now":true,""" +
                    """"trial_expires_at":"2026-11-20T00:00:00Z","model":{"id":"clip-1","display_name":"OMNIX Clip",""" +
                    """"color_name":"Black","color_hex":"#111111"}}"""
            )
        )
        val result = api().bind("AA:BB:CC:DD:EE:01", "device-0001", withAccount = false)
        val recorded = server.takeRequest()
        assertEquals("/v1/clip/pair/bind", recorded.path)
        assertNull(recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("AA:BB:CC:DD:EE:01"))
        val ok = result as ClipPairCallResult.Ok
        assertEquals("OMX-1", ok.value.clipSerial)
        assertEquals("2026-11-20T00:00:00Z", ok.value.trialExpiresAt)
        assertEquals("OMNIX Clip · Black", ok.value.model?.label)
    }

    @Test
    fun `refusal carries server error code and authorized calls send token`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"CLIP_OWNER_MISMATCH"}}"""))
        val result = api().status("AA:BB:CC:DD:EE:01")
        assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization"))
        assertEquals(ClipPairCallResult.Refused("CLIP_OWNER_MISMATCH", 403), result)
    }

    @Test
    fun `server errors throttling and broken bodies are unavailable`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ClipPairCallResult.Unavailable, api().release("OMX-1"))
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(ClipPairCallResult.Unavailable, api().operatorRegister("OMX-1", "AA:BB:CC:DD:EE:01", null))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertEquals(ClipPairCallResult.Unavailable, api().status("AA:BB:CC:DD:EE:01"))
        server.enqueue(MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST })
        assertEquals(ClipPairCallResult.Unavailable, api().status("AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun `release and operator register return serial`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"clip_serial":"OMX-1","status":"RELEASED"}"""))
        assertEquals(ClipPairCallResult.Ok("OMX-1"), api().release("OMX-1"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"clip_serial":"OMX-2","status":"PROVISIONED"}"""))
        assertEquals(ClipPairCallResult.Ok("OMX-2"), api().operatorRegister("OMX-2", "AA:BB:CC:DD:EE:02", "OMNIX"))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"CLIP_ALREADY_REGISTERED"}}"""))
        assertEquals(
            ClipPairCallResult.Refused("CLIP_ALREADY_REGISTERED", 409),
            api().operatorRegister("OMX-2", "AA:BB:CC:DD:EE:02", null)
        )
        server.enqueue(MockResponse().setResponseCode(404).setBody("garbage"))
        assertEquals(ClipPairCallResult.Refused("", 404), api().release("OMX-9"))
    }

    @Test
    fun `missing token refuses authorized call without network`(): Unit = runBlocking {
        assertEquals(ClipPairCallResult.Refused("UNAUTHORIZED", 401), api(storedToken = "").status("AA:BB:CC:DD:EE:01"))
        assertEquals(0, server.requestCount)
    }
}
