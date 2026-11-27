package com.omnix.assistant.voice.bargein

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInDetectorTest {

    /** Голос OMNIX в микрофоне: слоги и паузы между -50 и -35 дБ. */
    private fun echo(i: Int): Float = if (i % 4 == 0) -50f else -35f - (i % 3)

    private fun feedEcho(d: BargeInDetector, frames: Int): Boolean {
        var fired = false
        repeat(frames) { if (d.accept(echo(it))) fired = true }
        return fired
    }

    @Test
    fun `own voice alone never triggers`() {
        val d = BargeInDetector()
        assertFalse(feedEcho(d, 500))
    }

    @Test
    fun `person speaking louder than the echo triggers after about 0_3 s`() {
        val d = BargeInDetector()
        feedEcho(d, 100)
        var firedAt = -1
        for (i in 0 until 30) {
            if (d.accept(-20f) && firedAt < 0) firedAt = i
        }
        assertTrue("fired at $firedAt", firedAt in 11..15)
    }

    @Test
    fun `a short click does not trigger`() {
        val d = BargeInDetector()
        feedEcho(d, 100)
        var fired = false
        repeat(3) { if (d.accept(-15f)) fired = true }
        repeat(30) { if (d.accept(echo(it))) fired = true }
        assertFalse(fired)
    }

    @Test
    fun `nothing triggers during calibration`() {
        val d = BargeInDetector(calibrationFrames = 20)
        var fired = false
        repeat(20) { if (d.accept(-10f)) fired = true }
        assertFalse(fired)
    }

    @Test
    fun `resume starts from the interrupted sentence`() {
        val text = "Сегодня солнечно. Температура плюс двадцать градусов. Ветер слабый."
        // ~1,5 с при 14 симв/с ≈ 21 символ: уже во втором предложении.
        assertEquals("Температура плюс двадцать градусов. Ветер слабый.", ResumePoint.remainder(text, 1_500))
        assertEquals(text, ResumePoint.remainder(text, 300))
        assertEquals("", ResumePoint.remainder(text, 60_000))
    }

    @Test
    fun `stop phrases only stop and are not new requests`() {
        listOf("стоп", "Хватит!", "омни стоп", "stop", "bes", "yeter").forEach {
            assertTrue(it, BargeInPhrases.isStop(it))
        }
        listOf("стоп, а какая завтра погода", "позвони маме", "не надо так громко говорить").forEach {
            assertFalse(it, BargeInPhrases.isStop(it))
        }
    }
}
