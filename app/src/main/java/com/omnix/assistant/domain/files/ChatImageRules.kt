package com.omnix.assistant.domain.files

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Правила для картинок в чате: какие форматы берём, как сильно сжимаем и что
 * считаем слишком большим. Чистая логика без Android, чтобы проверять юнит-тестами.
 */
object ChatImageRules {
    /** Длинная сторона после сжатия, пикселей. */
    const val MAX_SIDE_PX = 1536

    /** Качество JPEG при сжатии. */
    const val JPEG_QUALITY = 80

    /** Если после сжатия файл всё ещё крупный, качество снижается по этим шагам. */
    val QUALITY_STEPS = intArrayOf(JPEG_QUALITY, 65, 50)

    /** Исходный файл больше этого не открываем (защита от нехватки памяти). */
    const val MAX_SOURCE_BYTES = 40L * 1024 * 1024

    /** Исходник больше этого числа пикселей не открываем. */
    const val MAX_SOURCE_PIXELS = 120_000_000L

    /** Потолок размера готового JPEG: сервер принимает картинку до 1,5 МБ. */
    const val MAX_ENCODED_BYTES = 1_200_000

    /** Почему картинку не удалось приложить. */
    enum class Reject { UNSUPPORTED, TOO_LARGE, UNREADABLE }

    /** Формат по первым байтам файла: расширению и типу от приложения-источника верить нельзя. */
    fun detectMime(header: ByteArray): String? = when {
        header.size >= 3 &&
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() ->
            "image/jpeg"
        header.size >= 8 && PNG_SIGNATURE.indices.all { header[it] == PNG_SIGNATURE[it] } ->
            "image/png"
        header.size >= 12 &&
            String(header, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(header, 8, 4, Charsets.ISO_8859_1) == "WEBP" ->
            "image/webp"
        else -> null
    }

    /** Проверка исходника до декодирования: формат и размер файла. */
    fun validateSource(mime: String?, sizeBytes: Long): Reject? = when {
        mime == null -> Reject.UNSUPPORTED
        sizeBytes > MAX_SOURCE_BYTES -> Reject.TOO_LARGE
        else -> null
    }

    /** Проверка размеров картинки в пикселях. */
    fun validatePixels(width: Int, height: Int): Reject? = when {
        width <= 0 || height <= 0 -> Reject.UNREADABLE
        width.toLong() * height.toLong() > MAX_SOURCE_PIXELS -> Reject.TOO_LARGE
        else -> null
    }

    /** Итоговый размер: длинная сторона не больше [maxSide], пропорции сохраняются, не увеличиваем. */
    fun targetSize(width: Int, height: Int, maxSide: Int = MAX_SIDE_PX): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return if (width >= height) {
            maxSide to (height * scale).roundToInt().coerceAtLeast(1)
        } else {
            (width * scale).roundToInt().coerceAtLeast(1) to maxSide
        }
    }

    /**
     * Во сколько раз (степень двойки) грубо уменьшать при декодировании, чтобы
     * не держать в памяти полноразмерный снимок; после уменьшения длинная сторона
     * остаётся не меньше [maxSide].
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE_PX): Int {
        val longest = max(width, height)
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /**
     * Поворот снимка по EXIF (тег Orientation) в градусах: 0, 90, 180 или 270.
     * Читает только начало JPEG; отражения не учитываются.
     */
    fun exifRotation(jpeg: ByteArray): Int {
        fun u16(i: Int, little: Boolean): Int =
            if (little) (jpeg[i].toInt() and 0xFF) or ((jpeg[i + 1].toInt() and 0xFF) shl 8)
            else ((jpeg[i].toInt() and 0xFF) shl 8) or (jpeg[i + 1].toInt() and 0xFF)
        fun u32(i: Int, little: Boolean): Long =
            if (little) (u16(i, true).toLong() or (u16(i + 2, true).toLong() shl 16))
            else ((u16(i, false).toLong() shl 16) or u16(i + 2, false).toLong())

        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return 0
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) return 0
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return 0
            val length = u16(pos + 2, false)
            if (length < 2 || pos + 2 + length > jpeg.size) return 0
            val segment = pos + 4
            if (marker == 0xE1 && length >= 16 &&
                String(jpeg, segment, 4, Charsets.ISO_8859_1) == "Exif"
            ) {
                val tiff = segment + 6
                val little = when (String(jpeg, tiff, 2, Charsets.ISO_8859_1)) {
                    "II" -> true
                    "MM" -> false
                    else -> return 0
                }
                val ifd = tiff + u32(tiff + 4, little).toInt()
                if (ifd < tiff || ifd + 2 > jpeg.size) return 0
                val count = u16(ifd, little)
                for (k in 0 until count) {
                    val entry = ifd + 2 + k * 12
                    if (entry + 12 > jpeg.size) return 0
                    if (u16(entry, little) == 0x0112) {
                        return when (u16(entry + 8, little)) {
                            3 -> 180
                            6 -> 90
                            8 -> 270
                            else -> 0
                        }
                    }
                }
                return 0
            }
            pos += 2 + length
        }
        return 0
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
