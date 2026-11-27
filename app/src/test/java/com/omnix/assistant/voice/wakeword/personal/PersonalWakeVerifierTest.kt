package com.omnix.assistant.voice.wakeword.personal

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalWakeVerifierTest {

    private fun cloud(center: Float, n: Int, seed: Long): List<FloatArray> {
        val r = Random(seed)
        return List(n) { FloatArray(WakeFeatures.DIM) { center + r.nextGaussian().toFloat() * 0.5f } }
    }

    @Test
    fun `summary is mean and max over time`() {
        val window = List(WakeFeatures.FRAMES) { t -> FloatArray(WakeFeatures.EMB) { t.toFloat() } }
        val s = WakeFeatures.summarize(window)
        assertEquals(7.5f, s[0], 1e-5f)
        assertEquals(15f, s[WakeFeatures.EMB], 1e-5f)
    }

    @Test
    fun `learns to separate owner from others`() {
        val pos = cloud(1f, 12, 1)
        val neg = cloud(-1f, 300, 2)
        val v = assertNotNullAndGet(PersonalWakeVerifier.train(pos, neg))
        cloud(1f, 20, 3).forEach { assertTrue(v.probability(it) > 0.9f) }
        cloud(-1f, 50, 4).forEach { assertTrue(v.probability(it) < 0.5f) }
    }

    @Test
    fun `round trips through bytes`() {
        val v = assertNotNullAndGet(PersonalWakeVerifier.train(cloud(1f, 5, 5), cloud(-1f, 20, 6)))
        val copy = assertNotNullAndGet(PersonalWakeVerifier.fromBytes(v.toBytes()))
        val x = cloud(0.3f, 1, 7).first()
        assertEquals(v.probability(x), copy.probability(x), 1e-6f)
    }

    @Test
    fun `no data no model and corrupt bytes are rejected`() {
        assertNull(PersonalWakeVerifier.train(emptyList(), cloud(-1f, 5, 8)))
        assertNull(PersonalWakeVerifier.fromBytes(byteArrayOf(1, 2, 3)))
    }

    private fun assertNotNullAndGet(v: PersonalWakeVerifier?): PersonalWakeVerifier {
        assertNotNull(v)
        return requireNotNull(v)
    }
}
