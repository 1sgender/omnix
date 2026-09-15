package com.jarvis.assistant.voice.wakeword.oww

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W03/W04: стриминговый пайплайн на фейковых сессиях.
 *
 * Фейки проверяют ТЕНЗОРНЫЙ КОНТРАКТ (шейпы из живого прогона ONNX 1.30
 * на реальных моделях v0.5.1), а не качество детекции:
 * mel in [1, N] -> [T, 32]; emb in [1, 76, 32, 1] -> [B, 96];
 * clf in [1, 16, 96] -> скор.
 */
class OpenWakeWordPipelineTest {

    /** mel-фейк: 8 кадров на вызов (как настоящий на 1760 сэмплах), шейпы наружу. */
    private class FakeMel : MelComputer {
        val inputSizes = mutableListOf<Int>()
        var calls = 0
        override fun compute(samples: FloatArray): Array<FloatArray> {
            inputSizes += samples.size
            calls++
            return Array(8) { r -> FloatArray(32) { c -> (r * 32 + c).toFloat() } }
        }
    }

    private class FakeEmb : EmbeddingComputer {
        var calls = 0
        val windowShapes = mutableListOf<List<Int>>()
        override fun embed(window: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
            calls++
            windowShapes += listOf(
                window.size,
                window[0].size,
                window[0][0].size,
                window[0][0][0].size,
            )
            return Array(1) { FloatArray(96) { 0.1f } }
        }
    }

    private class FakeClf(val fixedScore: Float = 0.42f) : WakeClassifier {
        var calls = 0
        val featureShapes = mutableListOf<List<Int>>()
        override fun score(features: Array<Array<FloatArray>>): Float {
            calls++
            featureShapes += listOf(features.size, features[0].size, features[0][0].size)
            return fixedScore
        }
    }

    private fun chunk(n: Int = OpenWakeWordPipeline.CHUNK_SAMPLES, value: Short = 100): ShortArray =
        ShortArray(n) { value }

    @Test
    fun `первый чанк без контекста не детектит`() {
        val pipeline = OpenWakeWordPipeline(FakeMel(), FakeEmb(), FakeClf())
        assertNull(pipeline.accept(chunk()))
    }

    @Test
    fun `после прогрева классификатор вызывается с шейпом 1x16x96`() {
        val mel = FakeMel()
        val emb = FakeEmb()
        val clf = FakeClf()
        val pipeline = OpenWakeWordPipeline(mel, emb, clf)
        var last: OpenWakeWordPipeline.FrameResult? = null
        repeat(30) { last = pipeline.accept(chunk()) }
        assertNotNull(last)
        assertEquals(0.42f, last!!.score, 0f)
        assertTrue(clf.calls > 0)
        assertEquals(listOf(1, 16, 96), clf.featureShapes.last())
        // Окно эмбеддинга строго [1, 76, 32, 1].
        assertTrue(emb.calls > 0)
        assertTrue(emb.windowShapes.all { it == listOf(1, 76, 32, 1) })
    }

    @Test
    fun `mel получает хвост с оверлапом 480 сэмплов`() {
        val mel = FakeMel()
        val pipeline = OpenWakeWordPipeline(mel, FakeEmb(), FakeClf())
        pipeline.accept(chunk())
        pipeline.accept(chunk())
        // Первый вызов: истории нет — только 1280. Второй: 1280 + 480 оверлапа.
        assertEquals(listOf(1280, 1760), mel.inputSizes)
    }

    @Test
    fun `частичные чанки накапливаются до 1280`() {
        val mel = FakeMel()
        val pipeline = OpenWakeWordPipeline(mel, FakeEmb(), FakeClf())
        assertNull(pipeline.accept(chunk(640)))
        assertEquals(0, mel.calls)
        pipeline.accept(chunk(640))
        assertEquals(1, mel.calls)
    }

    @Test
    fun `двойной чанк за один вызов даёт два эмбеддинга`() {
        val mel = FakeMel()
        val emb = FakeEmb()
        val pipeline = OpenWakeWordPipeline(mel, emb, FakeClf())
        repeat(30) { pipeline.accept(chunk()) }
        val melBefore = mel.calls
        val embBefore = emb.calls
        pipeline.accept(chunk(2560))
        assertEquals(1, mel.calls - melBefore)
        assertEquals(2560 + 480, mel.inputSizes.last())
        assertEquals(2, emb.calls - embBefore)
    }

    @Test
    fun `некратный хвост переносится на следующий вызов`() {
        val mel = FakeMel()
        val pipeline = OpenWakeWordPipeline(mel, FakeEmb(), FakeClf())
        pipeline.accept(chunk(2000))
        assertEquals(1, mel.calls)
        assertEquals(1280, mel.inputSizes.last())
        // Остаток 720 + 560 = 1280 — второй полный чанк.
        pipeline.accept(chunk(560))
        assertEquals(2, mel.calls)
    }

    @Test
    fun `reset чистит контекст и возвращает холодный старт`() {
        val clf = FakeClf()
        val pipeline = OpenWakeWordPipeline(FakeMel(), FakeEmb(), clf)
        repeat(30) { pipeline.accept(chunk()) }
        assertTrue(clf.calls > 0)
        pipeline.reset()
        val callsAfterWarm = clf.calls
        // Сразу после reset контекста нет — классификатор молчит.
        assertNull(pipeline.accept(chunk()))
        assertEquals(callsAfterWarm, clf.calls)
    }

    @Test
    fun `задержки стадий неотрицательны`() {
        val pipeline = OpenWakeWordPipeline(FakeMel(), FakeEmb(), FakeClf())
        var last: OpenWakeWordPipeline.FrameResult? = null
        repeat(30) { last = pipeline.accept(chunk()) }
        assertNotNull(last)
        assertTrue(last!!.melMs >= 0)
        assertTrue(last!!.embMs >= 0)
        assertTrue(last!!.clfMs >= 0)
    }
}
