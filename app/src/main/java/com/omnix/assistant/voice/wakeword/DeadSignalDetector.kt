package com.omnix.assistant.voice.wakeword

/**
 * Детектор «мёртвого» микрофона (owner review 2026-09-26, п.5): некоторые
 * прошивки носимых устройств дают AudioRecord «живой», но поток — константа
 * (нули или DC-смещение без ADC-шума). Движок в этом состоянии «слышит»
 * ничего и молчит — тихая смерть, которую нельзя оставить.
 *
 * Сигнатура застревания: амплитудный размах (max − min) по окну из
 * [framesWindow] чанков (по 80 мс) держится ≤ [maxSpanLsb] LSB. Живая тишина
 * ВСЕГДА несёт ADC-джиттер (размах десятки LSB), поэтому порог 4 LSB не даёт
 * ложных срабатываний на настоящей тишине.
 *
 * Чистый Kotlin, без Android — покрыт JVM-тестами.
 */
class DeadSignalDetector(
    private val framesWindow: Int = 250,
    private val maxSpanLsb: Int = 4,
) {
    private val chunkExtents = ArrayDeque<ChunkExtent>(framesWindow.coerceAtLeast(1))

    data class ChunkExtent(val min: Int, val max: Int)

    /**
     * Наблюдать очередной 80-мс чанк.
     * @return true, когда ВСЁ окно чанков держится в мёртвом размахе
     *         (обсервировано ровно [framesWindow] чанков).
     */
    fun observe(chunk: ShortArray): Boolean {
        if (chunk.isEmpty()) return false
        var min = Short.MAX_VALUE
        var max = Short.MIN_VALUE
        for (s in chunk) {
            if (s < min) min = s
            if (s > max) max = s
        }
        chunkExtents.addLast(ChunkExtent(min, max))
        while (chunkExtents.size > framesWindow) chunkExtents.removeFirst()
        if (chunkExtents.size < framesWindow) return false
        var windowMin = Int.MAX_VALUE
        var windowMax = Int.MIN_VALUE
        for (e in chunkExtents) {
            if (e.min < windowMin) windowMin = e.min
            if (e.max > windowMax) windowMax = e.max
        }
        return windowMax - windowMin <= maxSpanLsb
    }

    /** Сброс окна (перезапуск движка). */
    fun reset() {
        chunkExtents.clear()
    }
}
