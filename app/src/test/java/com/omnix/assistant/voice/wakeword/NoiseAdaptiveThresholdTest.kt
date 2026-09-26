package com.omnix.assistant.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Динамический порог по шуму (owner review 2026-09-26, п.2b): в тишине —
 * базовый порог, в шуме — буст до +0.10, живая тишина пол не «гонит».
 */
class NoiseAdaptiveThresholdTest {

    private val base = 0.35f

    private fun seedSilence(n: Int, rms: Float) {
        // Окно из тихих кадров — пол оседает на уровне тишины.
        val n0 = n
        repeat(n0) { adapter.update(rms) }
    }

    private val adapter = NoiseAdaptiveThreshold(
        refSnrDb = 10f,
        maxBoost = 0.10f,
        maxEffective = 0.60f,
        frameWindow = 25,
    )

    @Test
    fun rmsOfConstantChunkMatchesValue() {
        val chunk = ShortArray(1280) { 1000 }
        assertEquals(1000f, adapter.rmsLsb(chunk), 1f)
        assertEquals(0f, adapter.rmsLsb(ShortArray(0)), 0f)
    }

    @Test
    fun quietEnvironment_keepsBaseThreshold() {
        seedSilence(30, rms = 10f)
        // Чистый громкий кадр: SNR = 20*log10(1000/10) = 40 дБ >> 10 дБ.
        val effective = adapter.effectiveThreshold(base, 1000f)
        assertEquals("в чистой среде порог не трогается", base, effective, 1e-4f)
    }

    @Test
    fun noisyCrowd_boostsThresholdTowardMax() {
        seedSilence(30, rms = 500f)
        // Кадр на уровне шума: SNR = 20*log10(600/500) ≈ 1.6 дБ.
        val effective = adapter.effectiveThreshold(base, 600f)
        assertTrue(
            "в толпе порог растёт (got $effective)",
            effective > base + 0.05f
        )
        // И не выше maxBoost.
        assertTrue("буст ограничен", effective <= base + 0.10f + 1e-4f)
    }

    @Test
    fun snrAtReferenceLevel_givesZeroBoost() {
        seedSilence(30, rms = 10f)
        // SNR = 10 дБ: отношение 10^(10/20) ≈ 3.16 → 10 × 3.16 = 31.6.
        val effective = adapter.effectiveThreshold(base, 31.6f)
        assertTrue("на референсном SNR буст ~0 (got $effective)", effective - base < 0.002f)
    }

    @Test
    fun frameQuieterThanFloor_givesNoBoost() {
        seedSilence(30, rms = 100f)
        val effective = adapter.effectiveThreshold(base, 50f)
        assertEquals(base, effective, 1e-4f)
        assertNull(adapter.snrDb(50f))
    }

    @Test
    fun loudSpeechDoesNotChaseNoiseFloor() {
        // 20 кадров тишины (пол ≈ 10), потом 100 громких кадров:
        // пол не должен подняться до уровня речи (20-й перцентиль окна 25).
        seedSilence(20, rms = 10f)
        repeat(100) {
            adapter.update(5000f)
            adapter.update(10f) // вкрапления тишины каждый кадр
        }
        assertTrue(
            "пол не погоняется за речью (floor=${adapter.noiseFloorLsb})",
            adapter.noiseFloorLsb < 100f
        )
    }

    @Test
    fun effectiveThresholdIsClamped() {
        seedSilence(30, rms = 500f)
        val clamped = adapter.effectiveThreshold(0.55f, 501f)
        assertEquals("потолок эффективного порога", 0.60f, clamped, 1e-4f)
    }

    @Test
    fun snrDbValuesAreConsistent() {
        seedSilence(30, rms = 10f)
        // 10x = 20 дБ.
        assertEquals(20f, adapter.snrDb(100f)!!, 0.05f)
        // 100x = 40 дБ.
        assertEquals(40f, adapter.snrDb(1000f)!!, 0.05f)
        // Кадр ровно на уровне пола — SNR не считается (буста и так не будет).
        assertNull(adapter.snrDb(10f))
    }

    @Test
    fun resetClearsFloor() {
        seedSilence(30, rms = 500f)
        adapter.reset()
        assertEquals("после reset пол = минимум", 1f, adapter.noiseFloorLsb, 1e-4f)
    }
}
