package com.omnix.assistant.agent.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardTest {

    @Test
    fun `skeleton keeps consonants and merges similar sounds`() {
        assertEquals("prvtkdl", EchoGuard.skeleton(EchoGuard.latin("Привет, как дела")))
        assertEquals("prvtkdl", EchoGuard.skeleton("pre vet cuck della"))
    }

    @Test
    fun `own russian translation heard by english recognizer is echo`() {
        val guard = EchoGuard()
        guard.onSpoken("Привет, как дела", nowMs = 1_000)
        assertTrue(guard.isEcho("pre vet cuck della", nowMs = 2_500))
    }

    @Test
    fun `real english speech is not echo`() {
        val guard = EchoGuard()
        guard.onSpoken("Привет, как дела", nowMs = 1_000)
        assertFalse(guard.isEcho("we can meet tomorrow at noon", nowMs = 2_000))
        assertFalse(guard.isEcho("I would like a coffee", nowMs = 2_000))
    }

    @Test
    fun `old translations are forgotten`() {
        val guard = EchoGuard(windowMs = 5_000)
        guard.onSpoken("Привет, как дела", nowMs = 0)
        assertFalse(guard.isEcho("pre vet cuck della", nowMs = 6_000))
    }

    @Test
    fun `short pieces are never treated as echo`() {
        val guard = EchoGuard()
        guard.onSpoken("да", nowMs = 0)
        assertFalse(guard.isEcho("the", nowMs = 100))
    }
}
