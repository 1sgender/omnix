package com.omnix.assistant.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerBusyTest {

    @Test
    fun `SERVER_BUSY берёт паузу из заголовка, затем из тела`() {
        assertEquals(ServerBusy(7, false), ServerBusy.parse("SERVER_BUSY", "7", 3))
        assertEquals(ServerBusy(3, false), ServerBusy.parse("SERVER_BUSY", null, 3))
        assertEquals(ServerBusy(null, false), ServerBusy.parse("SERVER_BUSY", "мусор", null))
    }

    @Test
    fun `провайдеры недоступны помечаются как перегрузка ИИ`() {
        assertTrue(ServerBusy.parse("ALL_PROVIDERS_UNAVAILABLE", "30", null)!!.aiOverloaded)
        assertTrue(ServerBusy.parse("PROVIDER_UNAVAILABLE", null, null)!!.aiOverloaded)
    }

    @Test
    fun `другие коды не про перегрузку`() {
        assertNull(ServerBusy.parse("QUOTA_EXCEEDED", "10", null))
        assertNull(ServerBusy.parse("RATE_LIMITED", "10", null))
    }

    @Test
    fun `пауза ограничена разумными границами`() {
        assertEquals(5, ServerBusy(null, false).waitSeconds())
        assertEquals(120, ServerBusy(9_999, true).waitSeconds())
        assertEquals(1, ServerBusy(0, true).waitSeconds())
    }

    @Test
    fun `запись об отказе забирается один раз`() {
        val ledger = AiTierLedger()
        val busy = ServerBusy(4, false)
        ledger.onServerBusy("req-1", busy)
        assertEquals(busy, ledger.takeServerBusy("req-1"))
        assertNull(ledger.takeServerBusy("req-1"))
        ledger.onServerBusy("", busy)
        assertFalse(ledger.takeServerBusy("") != null)
    }
}
