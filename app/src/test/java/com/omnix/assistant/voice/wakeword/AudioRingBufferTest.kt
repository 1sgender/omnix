package com.omnix.assistant.voice.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W05: кольцевой буфер PCM16 — порядок, перехлёст, границы.
 */
class AudioRingBufferTest {

    @Test
    fun `без перехлёста возвращает записанное по порядку`() {
        val ring = AudioRingBuffer(8)
        ring.write(shortArrayOf(1, 2, 3))
        assertEquals(3, ring.sizeSamples)
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.snapshotLast(8))
    }

    @Test
    fun `перехлёст хранит только хвост ёмкости`() {
        val ring = AudioRingBuffer(4)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(4, ring.sizeSamples)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), ring.snapshotLast(4))
    }

    @Test
    fun `запись больше ёмкости за один вызов хранит хвост`() {
        val ring = AudioRingBuffer(4)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        assertArrayEquals(shortArrayOf(7, 8, 9, 10), ring.snapshotLast(4))
    }

    @Test
    fun `snapshotLast меньше заполненного берёт самый свежий хвост`() {
        val ring = AudioRingBuffer(8)
        ring.write(shortArrayOf(1, 2, 3, 4, 5))
        assertArrayEquals(shortArrayOf(4, 5), ring.snapshotLast(2))
    }

    @Test
    fun `snapshotLast больше заполненного возвращает всё что есть`() {
        val ring = AudioRingBuffer(8)
        ring.write(shortArrayOf(1, 2))
        assertArrayEquals(shortArrayOf(1, 2), ring.snapshotLast(100))
    }

    @Test
    fun `пустой буфер возвращает пустой снапшот`() {
        val ring = AudioRingBuffer(8)
        assertEquals(0, ring.snapshotLast(8).size)
    }

    @Test
    fun `запись с offset и length`() {
        val ring = AudioRingBuffer(8)
        ring.write(shortArrayOf(9, 1, 2, 3, 9), offset = 1, length = 3)
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.snapshotLast(8))
    }

    @Test
    fun `clear сбрасывает буфер`() {
        val ring = AudioRingBuffer(4)
        ring.write(shortArrayOf(1, 2, 3, 4, 5))
        ring.clear()
        assertEquals(0, ring.sizeSamples)
        ring.write(shortArrayOf(7, 8))
        assertArrayEquals(shortArrayOf(7, 8), ring.snapshotLast(4))
    }

    @Test
    fun `многократный перехлёст через границу массива`() {
        val ring = AudioRingBuffer(5)
        ring.write(shortArrayOf(1, 2, 3))
        ring.write(shortArrayOf(4, 5, 6, 7))
        ring.write(shortArrayOf(8, 9))
        assertArrayEquals(shortArrayOf(5, 6, 7, 8, 9), ring.snapshotLast(5))
    }
}
