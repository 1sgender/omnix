package com.omnix.assistant.presentation.firstrun

import com.omnix.assistant.presentation.state.ClipCapability
import com.omnix.assistant.presentation.state.ClipState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Маппинг состояния Clip в фазу кольца подключения (мок 2026-09-24).
 * Подключение — тоже «работа» (дуга бежит); низкий заряд — «подключён»;
 * всё, что пользователь может исправить, — «не найден».
 */
class ClipRingPhaseTest {

    @Test
    fun `searching and connecting keep the arc running`() {
        assertEquals(ClipRingPhase.SEARCH, clipRingPhase(ClipState.Searching))
        assertEquals(ClipRingPhase.SEARCH, clipRingPhase(ClipState.Connecting("OMNIX Clip")))
    }

    @Test
    fun `connected and low battery close the ring`() {
        assertEquals(
            ClipRingPhase.FOUND,
            clipRingPhase(ClipState.Connected("OMNIX Clip"))
        )
        assertEquals(
            ClipRingPhase.FOUND,
            clipRingPhase(ClipState.BatteryLow("OMNIX Clip", percent = 7))
        )
    }

    @Test
    fun `unknown bluetooth-off disconnected and failed show the lost mark`() {
        assertEquals(ClipRingPhase.LOST, clipRingPhase(ClipState.Unknown))
        assertEquals(ClipRingPhase.LOST, clipRingPhase(ClipState.BluetoothOff))
        assertEquals(ClipRingPhase.LOST, clipRingPhase(ClipState.Disconnected("OMNIX Clip")))
        assertEquals(ClipRingPhase.LOST, clipRingPhase(ClipState.ConnectionFailed("OMNIX Clip")))
    }

    @Test
    fun `connected with capability details is still found`() {
        val connected = ClipState.Connected(
            deviceName = "OMNIX Clip",
            battery = ClipCapability.Available(80)
        )
        assertEquals(ClipRingPhase.FOUND, clipRingPhase(connected))
    }
    // ---- clipVisualPhase: таймаут поиска ----

    @Test
    fun `timed-out search shows the lost phase`() {
        assertEquals(
            ClipRingPhase.LOST,
            clipVisualPhase(ClipState.Searching, searchTimedOut = true)
        )
    }

    @Test
    fun `a running search without timeout keeps spinning`() {
        assertEquals(
            ClipRingPhase.SEARCH,
            clipVisualPhase(ClipState.Searching, searchTimedOut = false)
        )
    }

    @Test
    fun `timeout never interrupts an active connection attempt`() {
        assertEquals(
            ClipRingPhase.SEARCH,
            clipVisualPhase(ClipState.Connecting("OMNIX Clip"), searchTimedOut = true)
        )
    }

    @Test
    fun `timeout does not override a real connection`() {
        assertEquals(
            ClipRingPhase.FOUND,
            clipVisualPhase(ClipState.Connected("OMNIX Clip"), searchTimedOut = true)
        )
    }
}
