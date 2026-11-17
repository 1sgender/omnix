package com.omnix.assistant.domain.files

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Подготовка звука из аудиофайла к расшифровке на сервере (Whisper): моно,
 * 16 кГц, куски не длиннее [CHUNK_SECONDS] (сервер принимает фразу до 30 с).
 */
object AudioPcm {

    const val TARGET_RATE = 16_000

    /** Кусок на один запрос расшифровки: чуть меньше серверного предела 30 с. */
    const val CHUNK_SECONDS = 28

    /** Расшифровываем не больше этого: дальше пометка «расшифрована только часть». */
    const val MAX_SECONDS = 10 * CHUNK_SECONDS

    /** Совсем короткие хвосты (тишина в конце) не отправляем. */
    private const val MIN_CHUNK_SECONDS = 0.5

    /** Чередующиеся каналы → моно (среднее). */
    fun toMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            out[f] = (sum / channels).toShort()
        }
        return out
    }

    /**
     * Смена частоты дискретизации: при понижении сначала усреднение по окну
     * (простой фильтр от наложения частот), затем линейная интерполяция.
     */
    fun resample(mono: ShortArray, fromRate: Int, toRate: Int = TARGET_RATE): ShortArray {
        if (fromRate <= 0 || mono.isEmpty()) return ShortArray(0)
        if (fromRate == toRate) return mono
        val ratio = fromRate.toDouble() / toRate
        val source = if (ratio > 1.0) boxFilter(mono, ceil(ratio).toInt()) else mono
        val outSize = floor(mono.size / ratio).toInt()
        val out = ShortArray(outSize)
        for (i in 0 until outSize) {
            val pos = i * ratio
            val left = pos.toInt().coerceAtMost(source.lastIndex)
            val right = (left + 1).coerceAtMost(source.lastIndex)
            val frac = pos - left
            out[i] = (source[left] * (1 - frac) + source[right] * frac).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun boxFilter(samples: ShortArray, width: Int): ShortArray {
        if (width <= 1) return samples
        val out = ShortArray(samples.size)
        var sum = 0L
        for (i in samples.indices) {
            sum += samples[i]
            if (i >= width) sum -= samples[i - width]
            val n = minOf(i + 1, width)
            out[i] = (sum / n).toShort()
        }
        return out
    }

    /** Куски по [seconds] секунд; хвост короче полсекунды отбрасывается. */
    fun chunks(samples: ShortArray, rate: Int = TARGET_RATE, seconds: Int = CHUNK_SECONDS): List<ShortArray> {
        val size = rate * seconds
        if (size <= 0) return emptyList()
        val result = ArrayList<ShortArray>()
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + size, samples.size)
            if (end - start >= rate * MIN_CHUNK_SECONDS) result += samples.copyOfRange(start, end)
            start = end
        }
        return result
    }

    /** Склейка расшифровок кусков в один текст. */
    fun joinTranscripts(parts: List<String>): String =
        parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}
