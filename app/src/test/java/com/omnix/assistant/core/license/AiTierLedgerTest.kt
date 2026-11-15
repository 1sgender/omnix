package com.omnix.assistant.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Журнал облака: «ответил Flash», засчитанные ответы, отказы по лимиту. */
class AiTierLedgerTest {

    private val ledger = AiTierLedger()

    @Test
    fun `pro requested but flash served is remembered once`() {
        ledger.onAnswered("r1", requestedPro = true, servedPro = false, voice = false, atMs = 1_000L)
        assertTrue(ledger.takeDowngrade("r1"))
        assertFalse("запись забирается", ledger.takeDowngrade("r1"))
    }

    @Test
    fun `pro served or flash requested is not a downgrade`() {
        ledger.onAnswered("r1", requestedPro = true, servedPro = true, voice = false, atMs = 1_000L)
        ledger.onAnswered("r2", requestedPro = false, servedPro = false, voice = false, atMs = 1_000L)
        assertFalse(ledger.takeDowngrade("r1"))
        assertFalse(ledger.takeDowngrade("r2"))
    }

    @Test
    fun `answers are counted in the bucket the server uses`() {
        ledger.onAnswered("r1", requestedPro = true, servedPro = true, voice = true, atMs = 1_000L)
        ledger.onAnswered("r2", requestedPro = true, servedPro = false, voice = true, atMs = 2_000L)
        ledger.onAnswered("r3", requestedPro = false, servedPro = false, voice = false, atMs = 3_000L)
        assertEquals(
            listOf(AiQuotaBucket.PRO, AiQuotaBucket.FLASH_VOICE, AiQuotaBucket.FLASH_TEXT),
            ledger.answers.value.map { it.bucket }
        )
    }

    @Test
    fun `quota refusal is kept per request and closes the bucket`() {
        val exceeded = QuotaExceeded(AiQuotaBucket.PRO, 3_600L, 1_000L)
        ledger.onQuotaExceeded("r1", exceeded)
        assertEquals(listOf(exceeded), ledger.exhausted.value)
        assertEquals(exceeded, ledger.takeQuotaExceeded("r1"))
        assertNull(ledger.takeQuotaExceeded("r1"))
        assertNull(ledger.takeQuotaExceeded("other"))
    }

    @Test
    fun `a new answer from the bucket reopens it`() {
        ledger.onQuotaExceeded("r1", QuotaExceeded(AiQuotaBucket.FLASH_TEXT, 3_600L, 1_000L))
        ledger.onQuotaExceeded("r2", QuotaExceeded(AiQuotaBucket.PRO, 3_600L, 1_000L))
        ledger.onAnswered("r3", requestedPro = false, servedPro = false, voice = false, atMs = 2_000L)
        assertEquals(listOf(AiQuotaBucket.PRO), ledger.exhausted.value.map { it.bucket })
    }

    @Test
    fun `blank request ids are not remembered`() {
        ledger.onAnswered("", requestedPro = true, servedPro = false, voice = false, atMs = 1_000L)
        ledger.onQuotaExceeded("", QuotaExceeded(AiQuotaBucket.PRO, 60L, 1_000L))
        assertFalse(ledger.takeDowngrade(""))
        assertNull(ledger.takeQuotaExceeded(""))
        // Но ответ и отказ всё равно учтены в остатке.
        assertEquals(1, ledger.answers.value.size)
        assertEquals(1, ledger.exhausted.value.size)
    }
}
