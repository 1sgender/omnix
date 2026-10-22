package com.omnix.assistant.voice.conversation

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Режет поток с микрофона на фразы по паузам: фраза начинается, когда звук
 * громче фонового шума, и заканчивается после [endSilenceMs] тишины.
 * Порог плавает за шумом: в кафе и в тихой комнате он разный.
 *
 * Кормится кадрами по 20 мс (320 сэмплов при 16 кГц); возвращает готовую
 * фразу или null.
 */
class UtteranceSegmenter(
    private val sampleRate: Int = 16_000,
    private val minSpeechMs: Int = 250,
    private val endSilenceMs: Int = 700,
    private val maxUtteranceMs: Int = 15_000,
    private val preRollMs: Int = 300,
    /** Во сколько раз голос громче шума. */
    private val speechToNoise: Double = 3.0,
    /** Ниже этого уровня — всегда тишина (цифровой ноль, выключенный микрофон). */
    private val absoluteFloor: Double = 300.0,
    /** Первые кадры только измеряют фон: гул с самого начала — не речь. */
    private val warmupMs: Int = 300
) {
    private var noiseLevel = absoluteFloor
    private var warmupLeft = ms(warmupMs)
    private var warmupSum = 0.0
    private var warmupFrames = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private var preRollSamples = 0
    private val utterance = ArrayList<ShortArray>()
    private var utteranceSamples = 0
    private var speechSamples = 0
    private var silenceSamples = 0
    private var inSpeech = false

    /** true, пока идёт фраза: экран показывает «слушаю». */
    val isCapturing: Boolean get() = inSpeech

    fun accept(frame: ShortArray): ShortArray? {
        val level = rms(frame)
        if (warmupLeft > 0) {
            warmupLeft -= frame.size
            warmupSum += max(level, absoluteFloor / 3)
            warmupFrames++
            noiseLevel = warmupSum / warmupFrames
            pushPreRoll(frame)
            return null
        }
        val loud = level > max(absoluteFloor, noiseLevel * speechToNoise)

        if (!inSpeech) {
            // Фон учитываем только вне речи, медленно.
            noiseLevel = noiseLevel * 0.95 + max(level, absoluteFloor / 3) * 0.05
            pushPreRoll(frame)
            if (loud) {
                inSpeech = true
                utterance.clear()
                utteranceSamples = 0
                for (f in preRoll) add(f)
                preRoll.clear()
                preRollSamples = 0
                speechSamples = frame.size
                silenceSamples = 0
            }
            return null
        }

        add(frame)
        if (loud) {
            speechSamples += frame.size
            silenceSamples = 0
        } else {
            silenceSamples += frame.size
        }

        val ended = silenceSamples >= ms(endSilenceMs)
        val tooLong = utteranceSamples >= ms(maxUtteranceMs)
        if (!ended && !tooLong) return null

        inSpeech = false
        val enoughSpeech = speechSamples >= ms(minSpeechMs)
        // Хвост тишины не нужен распознавателю — оставляем 200 мс.
        val keep = if (ended) utteranceSamples - silenceSamples + ms(200) else utteranceSamples
        val result = if (enoughSpeech) flatten(keep.coerceAtMost(utteranceSamples)) else null
        utterance.clear()
        utteranceSamples = 0
        speechSamples = 0
        silenceSamples = 0
        return result
    }

    /** Сброс между сессиями и после озвучки перевода. */
    fun reset() {
        inSpeech = false
        utterance.clear()
        utteranceSamples = 0
        speechSamples = 0
        silenceSamples = 0
        preRoll.clear()
        preRollSamples = 0
    }

    private fun add(frame: ShortArray) {
        utterance.add(frame)
        utteranceSamples += frame.size
    }

    private fun pushPreRoll(frame: ShortArray) {
        preRoll.addLast(frame)
        preRollSamples += frame.size
        while (preRollSamples > ms(preRollMs) && preRoll.size > 1) {
            preRollSamples -= preRoll.removeFirst().size
        }
    }

    private fun flatten(count: Int): ShortArray {
        val out = ShortArray(count)
        var pos = 0
        for (f in utterance) {
            val n = minOf(f.size, count - pos)
            if (n <= 0) break
            f.copyInto(out, pos, 0, n)
            pos += n
        }
        return out
    }

    private fun ms(value: Int): Int = sampleRate / 1000 * value

    companion object {
        fun rms(frame: ShortArray): Double {
            if (frame.isEmpty()) return 0.0
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            return sqrt(sum / frame.size)
        }
    }
}
