package com.omnix.assistant.voice.wakeword.personal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Сжатие окна детектора «Омни» (16 эмбеддингов по 96 чисел) в 192 числа: среднее и максимум
 * по времени. На таком описании личный фильтр учится прямо на телефоне за доли секунды.
 */
object WakeFeatures {
    const val FRAMES = 16
    const val EMB = 96
    const val DIM = EMB * 2

    fun summarize(window: List<FloatArray>): FloatArray {
        val out = FloatArray(DIM)
        val max = FloatArray(EMB) { Float.NEGATIVE_INFINITY }
        for (row in window) {
            for (d in 0 until EMB) {
                val v = row[d]
                out[d] += v
                if (v > max[d]) max[d] = v
            }
        }
        val n = window.size.coerceAtLeast(1)
        for (d in 0 until EMB) {
            out[d] /= n
            out[EMB + d] = if (max[d] == Float.NEGATIVE_INFINITY) 0f else max[d]
        }
        return out
    }
}

/**
 * Личный фильтр «Омни»: логистическая регрессия поверх признаков детектора. Отвечает на вопрос
 * «это хозяин сказал "Омни" или что-то похожее/чужое?». Учится на примерах «я» (позвал и сразу
 * дал команду, записи тренировки) и «не я» (ложные включения, фоновая проверка, общие чужие
 * примеры из обучения модели).
 */
class PersonalWakeVerifier internal constructor(
    private val mean: FloatArray,
    private val scale: FloatArray,
    private val weights: FloatArray,
    private val bias: Float
) {
    /** Вероятность, что это «Омни» хозяина, 0..1. */
    fun probability(x: FloatArray): Float {
        var z = bias.toDouble()
        for (i in weights.indices) z += weights[i] * ((x[i] - mean[i]) / scale[i])
        return (1.0 / (1.0 + exp(-z))).toFloat()
    }

    fun toBytes(): ByteArray {
        val dim = weights.size
        val buf = ByteBuffer.allocate(4 + 4 + dim * 3 * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC).putInt(dim)
        mean.forEach { buf.putFloat(it) }
        scale.forEach { buf.putFloat(it) }
        weights.forEach { buf.putFloat(it) }
        buf.putFloat(bias)
        return buf.array()
    }

    companion object {
        private const val MAGIC = 0x4F4D4E31 // "OMN1"
        private const val MIN_SCALE = 1e-4f

        fun fromBytes(bytes: ByteArray): PersonalWakeVerifier? = try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buf.int == MAGIC)
            val dim = buf.int
            require(dim in 1..4096)
            val mean = FloatArray(dim) { buf.float }
            val scale = FloatArray(dim) { buf.float }
            val w = FloatArray(dim) { buf.float }
            PersonalWakeVerifier(mean, scale, w, buf.float)
        } catch (e: Exception) {
            null
        }

        /**
         * Обучение: стандартизация признаков, логистическая регрессия с весом класса
         * (примеров «я» мало — их ошибка весит больше) и L2-регуляризацией.
         * @return null — данных слишком мало.
         */
        fun train(
            positives: List<FloatArray>,
            negatives: List<FloatArray>,
            iterations: Int = 300,
            learningRate: Float = 0.1f,
            l2: Float = 1e-3f
        ): PersonalWakeVerifier? {
            if (positives.isEmpty() || negatives.isEmpty()) return null
            val dim = positives.first().size
            val all = positives + negatives
            val mean = FloatArray(dim)
            all.forEach { v -> for (i in 0 until dim) mean[i] += v[i] }
            for (i in 0 until dim) mean[i] /= all.size
            val scale = FloatArray(dim)
            all.forEach { v -> for (i in 0 until dim) { val d = v[i] - mean[i]; scale[i] += d * d } }
            for (i in 0 until dim) scale[i] = sqrt(scale[i] / all.size).coerceAtLeast(MIN_SCALE)

            val xs = all.map { v -> FloatArray(dim) { i -> (v[i] - mean[i]) / scale[i] } }
            val ys = FloatArray(all.size) { if (it < positives.size) 1f else 0f }
            val posWeight = negatives.size.toFloat() / positives.size
            val w = FloatArray(dim)
            var b = 0f
            val grad = FloatArray(dim)
            repeat(iterations) {
                grad.fill(0f)
                var gb = 0f
                for (k in xs.indices) {
                    val x = xs[k]
                    var z = b
                    for (i in 0 until dim) z += w[i] * x[i]
                    val p = (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()
                    val g = (p - ys[k]) * (if (ys[k] > 0.5f) posWeight else 1f)
                    for (i in 0 until dim) grad[i] += g * x[i]
                    gb += g
                }
                val n = xs.size.toFloat()
                for (i in 0 until dim) w[i] -= learningRate * (grad[i] / n + l2 * w[i])
                b -= learningRate * gb / n
            }
            return PersonalWakeVerifier(mean, scale, w, b)
        }
    }
}
