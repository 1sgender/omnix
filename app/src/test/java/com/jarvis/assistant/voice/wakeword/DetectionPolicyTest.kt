package com.jarvis.assistant.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W01/W02: временная политика детекции (threshold + patience + cooldown).
 * Чистая логика без Android — детерминированные часы инжектятся.
 */
class DetectionPolicyTest {

    @Test
    fun `одиночный всплеск выше порога не детектит при patience 2`() {
        val policy = DetectionPolicy(threshold = 0.5f, patienceFrames = 2)
        assertFalse(policy.observe(0.9f))
        assertFalse(policy.observe(0.1f))
        assertFalse(policy.observe(0.9f))
    }

    @Test
    fun `два хита подряд детектят`() {
        val policy = DetectionPolicy(threshold = 0.5f, patienceFrames = 2)
        assertFalse(policy.observe(0.6f))
        assertTrue(policy.observe(0.7f))
    }

    @Test
    fun `скор ниже порога сбрасывает серию`() {
        val policy = DetectionPolicy(threshold = 0.5f, patienceFrames = 2)
        assertFalse(policy.observe(0.9f))
        assertFalse(policy.observe(0.49f))
        assertFalse(policy.observe(0.9f))
        assertTrue(policy.observe(0.9f))
    }

    @Test
    fun `скор ровно на пороге считается хитом`() {
        val policy = DetectionPolicy(threshold = 0.5f, patienceFrames = 1)
        assertTrue(policy.observe(0.5f))
    }

    @Test
    fun `cooldown давит дубликат одной фразы`() {
        var now = 1_000L
        val policy = DetectionPolicy(
            threshold = 0.5f,
            patienceFrames = 1,
            cooldownMs = 2000L,
            clockMs = { now },
        )
        assertTrue(policy.observe(0.9f))
        now += 500
        assertFalse(policy.observe(0.95f))
        now += 1500
        assertTrue(policy.observe(0.95f))
    }

    @Test
    fun `reset снимает серию и cooldown`() {
        var now = 1_000L
        val policy = DetectionPolicy(
            threshold = 0.5f,
            patienceFrames = 2,
            cooldownMs = 60_000L,
            clockMs = { now },
        )
        assertFalse(policy.observe(0.9f))
        policy.reset()
        assertFalse(policy.observe(0.9f))
        assertTrue(policy.observe(0.9f))
        now += 100
        policy.reset()
        assertFalse(policy.observe(0.9f))
        assertTrue(policy.observe(0.9f))
    }

    @Test
    fun `невалидные параметры нормализуются без исключений`() {
        val policy = DetectionPolicy(threshold = 5f, patienceFrames = 0, cooldownMs = -1L)
        assertEquals(1f, policy.threshold, 0f)
        assertEquals(1, policy.patienceFrames)
        assertEquals(0L, policy.cooldownMs)
    }
}
