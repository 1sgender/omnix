package com.omnix.assistant.voice.wakeword.verify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeBurstGuardTest {

    @Test
    fun `three silent wakes in five minutes turn strict mode on for ten minutes`() {
        val g = WakeBurstGuard()
        g.onUnconfirmed(0)
        g.onUnconfirmed(60_000)
        assertFalse(g.strict(61_000))
        g.onUnconfirmed(120_000)
        assertTrue(g.strict(121_000))
        assertTrue(g.strict(120_000 + 9 * 60_000))
        assertFalse(g.strict(120_000 + 11 * 60_000))
    }

    @Test
    fun `silent wakes far apart do not add up`() {
        val g = WakeBurstGuard()
        g.onUnconfirmed(0)
        g.onUnconfirmed(4 * 60_000)
        g.onUnconfirmed(10 * 60_000)
        assertFalse(g.strict(10 * 60_000 + 1))
    }

    @Test
    fun `a real command resets the series`() {
        val g = WakeBurstGuard()
        repeat(3) { g.onUnconfirmed(it * 1_000L) }
        assertTrue(g.strict(5_000))
        g.onConfirmed()
        assertFalse(g.strict(5_000))
    }
}
