package com.omnix.assistant.voice.wakeword.oww

/**
 * Стриминговый пайплайн openWakeWord — порт _streaming_features/_streaming_melspectrogram
 * из dscripka/openWakeWord (utils.py) 1:1, без аллокационного мусора портов.
 *
 * Цепочка на каждые 1280 сэмплов (80 мс @16 кГц):
 * 1. хвост (накопленное + 480 сэмплов оверлапа) -> mel -> строки [*, 32] в мел-буфер;
 * 2. на каждый новый чанк — окно 76 мел-кадров шагом 8 с конца -> embedding [*, 96];
 * 3. когда эмбеддингов >= 16 — последние 16 -> классификатор -> скор.
 *
 * Чистый Kotlin (сессии инжектятся) — покрыт JVM-тестами на фейковых сессиях
 * с точными шейпами из живого прогона ONNX.
 */
class OpenWakeWordPipeline(
    private val mel: MelComputer,
    private val embedder: EmbeddingComputer,
    private val classifier: WakeClassifier,
) {
    data class FrameResult(
        val score: Float,
        /** Задержки стадий на этом чанке, мс (монотонные часы). */
        val melMs: Long,
        val embMs: Long,
        val clfMs: Long,
    )

    companion object {
        /** Чанк стриминга: 80 мс @16 кГц (эталон utils.py). */
        const val CHUNK_SAMPLES = 1280
        /** Оверлап сырого аудио для mel: 160*3 (эталон). */
        const val MEL_OVERLAP_SAMPLES = 480
        /** Мел-бинов на кадр (выход melspectrogram.onnx). */
        const val MEL_BINS = 32
        /** Высота окна для embedding-модели. */
        const val EMB_WINDOW = 76
        /** Шаг окон между чанками. */
        const val EMB_STEP = 8
        /** Размерность эмбеддинга. */
        const val EMB_DIM = 96
        /** Кадров эмбеддингов на вход классификатора. */
        const val FEATURE_FRAMES = 16
        /** Капы буферов из эталона (10*97 мел-кадров, 120 эмбеддингов). */
        const val MEL_BUFFER_MAX = 970
        const val FEATURE_BUFFER_MAX = 120
        /** Минимум сырого аудио для mel (эталон кидает ValueError ниже 400). */
        const val MEL_MIN_SAMPLES = 400
    }

    private val raw = FloatCircularBuffer(16000 * 10)
    private var remainder = FloatArray(0)
    private var accumulated = 0
    private val melBuffer = ArrayDeque<FloatArray>()
    private val featureBuffer = ArrayDeque<FloatArray>()

    /**
     * Скормить PCM16-моно-16кГц. Возвращает результат классификатора, если
     * накопился полный чанк и контекст прогрет (>= 16 эмбеддингов), иначе null.
     *
     * Холодный старт честный: первые ~1.3 с после reset() детекции нет
     * (вместо прогрева случайным шумом, как в эталоне).
     */
    fun accept(samples: ShortArray): FrameResult? {
        var x = FloatArray(samples.size) { samples[it].toFloat() }
        if (remainder.isNotEmpty()) {
            x = remainder + x
            remainder = FloatArray(0)
        }
        if (accumulated + x.size >= CHUNK_SAMPLES) {
            val rem = (accumulated + x.size) % CHUNK_SAMPLES
            if (rem != 0) {
                raw.write(x, 0, x.size - rem)
                accumulated += x.size - rem
                remainder = x.copyOfRange(x.size - rem, x.size)
            } else {
                raw.write(x, 0, x.size)
                accumulated += x.size
            }
        } else {
            raw.write(x, 0, x.size)
            accumulated += x.size
        }

        if (accumulated < CHUNK_SAMPLES || accumulated % CHUNK_SAMPLES != 0) return null

        val tail = raw.takeLast(accumulated + MEL_OVERLAP_SAMPLES)
        if (tail.size < MEL_MIN_SAMPLES) {
            accumulated = 0
            return null
        }
        val t0 = nowMs()
        val melRows = mel.compute(tail)
        val t1 = nowMs()
        melBuffer.addAll(melRows)
        while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeFirst()

        val nChunks = accumulated / CHUNK_SAMPLES
        for (i in nChunks - 1 downTo 0) {
            // Окно 76 кадров, отъехав на 8*i от конца (эталон: melBuffer[-76+ndx:ndx]).
            val endExclusive = melBuffer.size - EMB_STEP * i
            val start = endExclusive - EMB_WINDOW
            if (start < 0) continue
            val window = Array(1) { Array(EMB_WINDOW) { r -> Array(MEL_BINS) { c -> floatArrayOf(melBuffer[start + r][c]) } } }
            val emb = embedder.embed(window)
            featureBuffer.addAll(emb.toList())
        }
        val t2 = nowMs()
        while (featureBuffer.size > FEATURE_BUFFER_MAX) featureBuffer.removeFirst()
        accumulated = 0

        if (featureBuffer.size < FEATURE_FRAMES) return null
        val feats = Array(1) { featureBuffer.takeLast(FEATURE_FRAMES).toTypedArray() }
        val s = classifier.score(feats)
        return FrameResult(s, t1 - t0, t2 - t1, nowMs() - t2)
    }

    /** Полный сброс контекста (рестарт движка после STT — без stale-фич). */
    fun reset() {
        raw.clear()
        remainder = FloatArray(0)
        accumulated = 0
        melBuffer.clear()
        featureBuffer.clear()
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** Кольцо float без боксинга; takeLast копирует один раз. */
    private class FloatCircularBuffer(capacity: Int) {
        private val buf = FloatArray(capacity.coerceAtLeast(1))
        private var pos = 0
        private var filled = 0

        fun write(src: FloatArray, offset: Int, length: Int) {
            var rem = length.coerceAtMost(src.size - offset).coerceAtLeast(0)
            var s = offset
            while (rem > 0) {
                val chunk = minOf(rem, buf.size - pos)
                System.arraycopy(src, s, buf, pos, chunk)
                s += chunk
                rem -= chunk
                pos = (pos + chunk) % buf.size
                filled = minOf(buf.size, filled + chunk)
            }
        }

        fun takeLast(n: Int): FloatArray {
            val count = minOf(n.coerceAtLeast(0), filled)
            val out = FloatArray(count)
            var read = (pos - filled + buf.size) % buf.size
            read = (read + (filled - count)) % buf.size
            var rem = count
            var dst = 0
            while (rem > 0) {
                val chunk = minOf(rem, buf.size - read)
                System.arraycopy(buf, read, out, dst, chunk)
                dst += chunk
                rem -= chunk
                read = (read + chunk) % buf.size
            }
            return out
        }

        fun clear() {
            pos = 0
            filled = 0
        }
    }
}
