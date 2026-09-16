package com.omnix.assistant.voice.wakeword

/**
 * Кольцевой буфер PCM16 (§8 ТЗ): хранит хвост аудио (~640 мс при 16 кГц),
 * чтобы не потерять начало команды, сказанной сразу после wake word.
 *
 * Честное ограничение: системный SpeechRecognizer не умеет принимать байты
 * (нет API) — буфер используется для метрик/диагностики и будущего локального
 * STT. Зазор маскируется UX chime-then-speak. Сырое аудио в проде не логируется.
 *
 * Чистый Kotlin, без аллокаций на запись — покрыт JVM-тестами.
 */
class AudioRingBuffer(val capacitySamples: Int) {
    private val buf = ShortArray(capacitySamples.coerceAtLeast(1))
    private var writePos = 0
    private var filled = 0

    val sizeSamples: Int get() = filled

    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        var remaining = length.coerceAtMost(samples.size - offset).coerceAtLeast(0)
        var src = offset
        while (remaining > 0) {
            // Кусок до конца массива за один проход.
            val chunk = minOf(remaining, buf.size - writePos)
            System.arraycopy(samples, src, buf, writePos, chunk)
            src += chunk
            remaining -= chunk
            writePos = (writePos + chunk) % buf.size
            filled = minOf(buf.size, filled + chunk)
        }
    }

    /**
     * Последние [n] сэмплов (или меньше, если буфер не заполнен).
     * Единственная аллокация — результирующий массив.
     */
    fun snapshotLast(n: Int): ShortArray {
        val count = minOf(n.coerceAtLeast(0), filled)
        val out = ShortArray(count)
        var readPos = (writePos - filled + buf.size) % buf.size
        val skip = filled - count
        readPos = (readPos + skip) % buf.size
        var remaining = count
        var dst = 0
        while (remaining > 0) {
            val chunk = minOf(remaining, buf.size - readPos)
            System.arraycopy(buf, readPos, out, dst, chunk)
            dst += chunk
            remaining -= chunk
            readPos = (readPos + chunk) % buf.size
        }
        return out
    }

    fun clear() {
        writePos = 0
        filled = 0
    }
}
