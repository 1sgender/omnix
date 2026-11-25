package com.omnix.assistant.voice.offline

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Лёгкая чистка звука офлайн-голоса: срез гула ниже [highPassHz] и шипения выше [lowPassHz].
 * Два фильтра второго порядка (биквады), работают поточно по кускам звука, состояние держится
 * между кусками одной фразы. На каждую фразу создаётся новый экземпляр.
 */
class VoicePostFilter(
    sampleRate: Int,
    highPassHz: Float = DEFAULT_HIGH_PASS_HZ,
    lowPassHz: Float = DEFAULT_LOW_PASS_HZ
) {
    private val highPass = Biquad.highPass(sampleRate, highPassHz)
    private val lowPass = Biquad.lowPass(sampleRate, lowPassHz.coerceAtMost(sampleRate * MAX_CUTOFF_OF_RATE))

    /** Возвращает новый массив: входной кусок не меняется. */
    fun process(samples: FloatArray): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = lowPass.step(highPass.step(samples[i].toDouble())).toFloat()
        }
        return out
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            private const val Q = 0.7071067811865476

            fun highPass(sampleRate: Int, freq: Float): Biquad = build(sampleRate, freq, high = true)

            fun lowPass(sampleRate: Int, freq: Float): Biquad = build(sampleRate, freq, high = false)

            private fun build(sampleRate: Int, freq: Float, high: Boolean): Biquad {
                val w0 = 2.0 * PI * freq / sampleRate
                val c = cos(w0)
                val alpha = sin(w0) / (2.0 * Q)
                val a0 = 1.0 + alpha
                val b0: Double
                val b1: Double
                if (high) {
                    b0 = (1.0 + c) / 2.0
                    b1 = -(1.0 + c)
                } else {
                    b0 = (1.0 - c) / 2.0
                    b1 = 1.0 - c
                }
                return Biquad(b0 / a0, b1 / a0, b0 / a0, -2.0 * c / a0, (1.0 - alpha) / a0)
            }
        }
    }

    companion object {
        const val DEFAULT_HIGH_PASS_HZ = 70f
        const val DEFAULT_LOW_PASS_HZ = 9500f

        /** Срез не выше 45% частоты дискретизации, иначе фильтр теряет смысл. */
        private const val MAX_CUTOFF_OF_RATE = 0.45f
    }
}
