package com.omnix.server.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ротация API-ключей при 429. Чистый unit-тест (часы подменяются).
 */
class ApiKeyPoolTest {

    private var now = 1_000_000L
    private fun pool(vararg keys: String, cooldownMs: Long = 60_000) =
        ApiKeyPool(keys.toList(), cooldownMs, clock = { now })

    @Test
    fun `single key pool always returns it`() {
        val pool = pool("k1")
        assertEquals("k1", pool.current())
        assertEquals("k1", pool.current())
        assertEquals(1, pool.size)
        assertFalse(pool.isEmpty)
    }

    @Test
    fun `empty pool returns null`() {
        val pool = pool()
        assertNull(pool.current())
        assertTrue(pool.isEmpty)
        assertEquals(0, pool.size)
    }

    @Test
    fun `blank keys are filtered out`() {
        val pool = pool("k1", "  ", "", "k2")
        assertEquals(2, pool.size)
    }

    @Test
    fun `requests round-robin across keys`() {
        val pool = pool("k1", "k2", "k3")
        assertEquals("k1", pool.current())
        assertEquals("k2", pool.current())
        assertEquals("k3", pool.current())
        assertEquals("k1", pool.current())
    }

    @Test
    fun `rate limited key goes to cooldown`() {
        val pool = pool("k1", "k2")
        assertEquals("k1", pool.current())
        pool.reportRateLimited("k1")
        // k1 в cooldown — следующие вызовы дают k2.
        assertEquals("k2", pool.current())
        assertEquals("k2", pool.current())
    }

    @Test
    fun `cooldown expires and key returns to rotation`() {
        val pool = pool("k1", "k2", cooldownMs = 60_000)
        assertEquals("k1", pool.current())
        pool.reportRateLimited("k1")
        assertEquals("k2", pool.current())
        now += 60_001
        // Оба снова доступны; курсор стоял после k2 → k1, k2, ...
        assertEquals("k1", pool.current())
        assertEquals("k2", pool.current())
    }

    @Test
    fun `all cooled down still returns a key`() {
        val pool = pool("k1", "k2")
        pool.reportRateLimited("k1")
        pool.reportRateLimited("k2")
        // Честная попытка вместо отказа (пусть вернётся настоящий 429).
        val key = pool.current()
        assertTrue(key == "k1" || key == "k2")
    }

    @Test
    fun `success clears cooldown early`() {
        val pool = pool("k1", "k2", cooldownMs = 60_000)
        pool.reportRateLimited("k1")
        assertEquals("k2", pool.current())
        // Лимит подняли / ключ ожил — успех снимает cooldown сразу.
        pool.reportSuccess("k1")
        // Курсор после k2 → k1 снова в ротации.
        assertEquals("k1", pool.current())
    }

    @Test
    fun `unknown key reports are ignored`() {
        val pool = pool("k1")
        pool.reportRateLimited("nope")
        pool.reportSuccess("nope")
        assertEquals("k1", pool.current())
        assertEquals(-1, pool.indexOf("nope"))
    }

    @Test
    fun `indexOf exposes key position for logs`() {
        val pool = pool("k1", "k2")
        assertEquals(0, pool.indexOf("k1"))
        assertEquals(1, pool.indexOf("k2"))
    }
}
