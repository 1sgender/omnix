package com.omnix.assistant.core.clip

import com.omnix.assistant.core.license.ClipBindingState
import com.omnix.assistant.core.license.ClipModelInfo
import com.omnix.assistant.core.license.ClipPairInfo
import com.omnix.assistant.core.license.LicenseInfo
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.license.LicenseRefreshResult
import com.omnix.assistant.voice.audio.BluetoothAudioRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Действия с парой: привязка (в т.ч. пробный Pro), статус, отвязка, оператор, коды отказов. */
class ClipPairServiceTest {
    private val api = mockk<HttpClipPairApi>()
    private val license = mockk<LicenseManager>(relaxed = true)
    private val router = mockk<BluetoothAudioRouter>()
    private val service = ClipPairService(api, license, router)
    private val token = "omx_" + "A".repeat(43)
    private val model = ClipModelInfo("clip-1", "OMNIX Clip", "Black", "#111111")

    private fun connected(vararg addresses: String) {
        every { router.connectedAudioAddresses() } returns addresses.toSet()
    }

    @Test
    fun `nothing connected is reported without calling the server`(): Unit = runBlocking {
        connected("02:00:00:00:00:00")
        assertNull(service.connectedAddress())
        assertEquals(ClipPairOutcome.NOT_CONNECTED, service.bindConnected(withAccount = false).outcome)
        assertEquals(ConnectedPair.None, service.connectedPair())
        assertEquals(ClipPairOutcome.NOT_CONNECTED, service.operatorRegister("OMX-1", null))
    }

    @Test
    fun `successful bind adopts token and returns trial end`(): Unit = runBlocking {
        connected("aa-bb-cc-dd-ee-01")
        every { license.getDeviceId() } returns "device-0001"
        coEvery { api.bind("AA:BB:CC:DD:EE:01", "device-0001", false) } returns
            ClipPairCallResult.Ok(ClipPairBound(token, "OMX-1", model, true, "2026-11-20T00:00:00Z"))
        coEvery { license.adoptPairAccessToken(token) } returns LicenseRefreshResult.Valid(LicenseInfo(isActivated = true))
        val result = service.bindConnected(withAccount = false)
        assertEquals(ClipPairOutcome.ADDED, result.outcome)
        assertEquals(Instant.parse("2026-11-20T00:00:00Z"), result.trialUntil)
    }

    @Test
    fun `bind failures map to user facing outcomes`(): Unit = runBlocking {
        connected("AA:BB:CC:DD:EE:01")
        every { license.getDeviceId() } returns "device-0001"
        val codes = mapOf(
            "CLIP_UNKNOWN" to ClipPairOutcome.UNKNOWN,
            "CLIP_OWNER_MISMATCH" to ClipPairOutcome.TAKEN,
            "CLIP_REVOKED" to ClipPairOutcome.REVOKED,
            "CLIP_PAIR_LIMIT" to ClipPairOutcome.LIMIT,
            "CLIP_ALREADY_REGISTERED" to ClipPairOutcome.ALREADY_REGISTERED,
            "SOMETHING_ELSE" to ClipPairOutcome.FAILED
        )
        codes.forEach { (code, expected) ->
            coEvery { api.bind(any(), any(), any()) } returns ClipPairCallResult.Refused(code, 403)
            assertEquals(code, expected, service.bindConnected(withAccount = true).outcome)
        }
        coEvery { api.bind(any(), any(), any()) } returns ClipPairCallResult.Unavailable
        assertEquals(ClipPairOutcome.FAILED, service.bindConnected(withAccount = true).outcome)
        // Токен выдан, но validate не прошёл — это не успех.
        coEvery { api.bind(any(), any(), any()) } returns ClipPairCallResult.Ok(ClipPairBound(token, "OMX-1"))
        coEvery { license.adoptPairAccessToken(token) } returns LicenseRefreshResult.ServiceUnavailable
        val failed = service.bindConnected(withAccount = true)
        assertEquals(ClipPairOutcome.FAILED, failed.outcome)
        assertNull(failed.trialUntil)
    }

    @Test
    fun `connected pair status distinguishes own available and foreign`(): Unit = runBlocking {
        connected("AA:BB:CC:DD:EE:01")
        coEvery { api.status(any()) } returns ClipPairCallResult.Ok(ClipPairStatus("OWNED", "OMX-1", model))
        assertEquals(ConnectedPair.Own("OMX-1", model), service.connectedPair())
        coEvery { api.status(any()) } returns ClipPairCallResult.Ok(ClipPairStatus("AVAILABLE", "OMX-2", null))
        assertEquals(ConnectedPair.Available("OMX-2", null), service.connectedPair())
        coEvery { api.status(any()) } returns ClipPairCallResult.Ok(ClipPairStatus("UNAVAILABLE"))
        assertEquals(ConnectedPair.NotOmnix, service.connectedPair())
        coEvery { api.status(any()) } returns ClipPairCallResult.Unavailable
        assertEquals(ConnectedPair.None, service.connectedPair())
    }

    @Test
    fun `release refreshes license and operator register maps outcomes`(): Unit = runBlocking {
        coEvery { api.release("OMX-1") } returns ClipPairCallResult.Ok("OMX-1")
        assertEquals(ClipPairOutcome.RELEASED, service.release("OMX-1"))
        coVerify { license.refreshFromServer() }
        coEvery { api.release("OMX-2") } returns ClipPairCallResult.Refused("CLIP_UNKNOWN", 404)
        assertEquals(ClipPairOutcome.UNKNOWN, service.release("OMX-2"))
        coEvery { api.release("OMX-3") } returns ClipPairCallResult.Unavailable
        assertEquals(ClipPairOutcome.FAILED, service.release("OMX-3"))

        connected("AA:BB:CC:DD:EE:01")
        coEvery { api.operatorRegister("OMX-9", "AA:BB:CC:DD:EE:01", "Clip") } returns ClipPairCallResult.Ok("OMX-9")
        assertEquals(ClipPairOutcome.REGISTERED, service.operatorRegister(" OMX-9 ", "Clip"))
        coEvery { api.operatorRegister(any(), any(), any()) } returns ClipPairCallResult.Unavailable
        assertEquals(ClipPairOutcome.FAILED, service.operatorRegister("OMX-9", null))
    }

    @Test
    fun `my pairs come from the validated license`() {
        val pair = ClipPairInfo("OMX-1", "AA:BB:CC:DD:EE:01", model)
        every { license.getLicenseInfo() } returns LicenseInfo(
            isActivated = true,
            clipBinding = ClipBindingState(pairRequired = true, pairs = listOf(pair))
        )
        assertEquals(listOf(pair), service.myPairs())
    }
}
