package com.omnix.assistant.presentation.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Контракт кольцевого буфера амплитуды: 11 свежих сэмплов уровня микрофона,
 * новейший — индекс 0; незаполненная история читается как тишина; вход
 * клампится в 0..1.
 */
class AmplitudeRingTest {

    @Test
    fun `newest sample is index zero and history goes back in time`() {
        val ring = AmplitudeRing()
        ring.push(0.1f)
        ring.push(0.5f)
        ring.push(0.9f)

        assertEquals(0.9f, ring.newestFirst(0), 1e-6f)
        assertEquals(0.5f, ring.newestFirst(1), 1e-6f)
        assertEquals(0.1f, ring.newestFirst(2), 1e-6f)
    }

    @Test
    fun `capacity evicts the oldest sample`() {
        val ring = AmplitudeRing(capacity = 3)
        ring.push(0.1f)
        ring.push(0.2f)
        ring.push(0.3f)
        ring.push(0.4f) // выталкивает 0.1

        assertEquals(0.4f, ring.newestFirst(0), 1e-6f)
        assertEquals(0.3f, ring.newestFirst(1), 1e-6f)
        assertEquals(0.2f, ring.newestFirst(2), 1e-6f)
    }

    @Test
    fun `unfilled history reads as silence`() {
        val ring = AmplitudeRing()
        ring.push(0.5f)

        assertEquals(0.5f, ring.newestFirst(0), 1e-6f)
        assertEquals(0f, ring.newestFirst(1), 1e-6f)
        assertEquals(0f, ring.newestFirst(10), 1e-6f)
    }

    @Test
    fun `out of range indices read as silence`() {
        val ring = AmplitudeRing()
        ring.push(1f)

        assertEquals(0f, ring.newestFirst(-1), 1e-6f)
        assertEquals(0f, ring.newestFirst(AmplitudeRing.TICK_COUNT), 1e-6f)
    }

    @Test
    fun `levels are clamped to unit range`() {
        val ring = AmplitudeRing()
        ring.push(-0.7f)
        assertEquals(0f, ring.newestFirst(0), 1e-6f)

        ring.push(2.4f)
        assertEquals(1f, ring.newestFirst(0), 1e-6f)
    }

    @Test
    fun `reset clears the history`() {
        val ring = AmplitudeRing()
        ring.push(0.9f)
        ring.push(0.8f)

        ring.reset()

        assertEquals(0f, ring.newestFirst(0), 1e-6f)
        assertEquals(0f, ring.newestFirst(1), 1e-6f)
    }
}
