package com.omnix.assistant.voice.conversation

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM 16 бит ↔ WAV: фраза уходит на сервер, синтез речи приходит из файла. */
object PcmWav {

    /** Моно PCM 16 бит → WAV (44-байтный заголовок). */
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // моно
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        for (s in samples) buffer.putShort(s)
        return buffer.array()
    }

    data class Decoded(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    /**
     * WAV PCM 16 бит → сэмплы. Чанки ищутся по именам: движки синтеза речи
     * пишут разные заголовки (LIST и прочее). null — не PCM 16 бит.
     */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 44) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            if (size < 0 || body + size > bytes.size && id != "data") return null
            when (id) {
                "fmt " -> {
                    if (buffer.getShort(body).toInt() != 1) return null
                    channels = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (bits != 16 || channels <= 0 || sampleRate <= 0) return null
                    // Некоторые движки пишут размер данных 0 или «до конца файла».
                    val end = if (size == 0 || body + size > bytes.size) bytes.size else body + size
                    val count = (end - body) / 2
                    val samples = ShortArray(count) { buffer.getShort(body + it * 2) }
                    return Decoded(samples, sampleRate, channels)
                }
            }
            offset = body + size + (size and 1)
        }
        return null
    }

    /**
     * Моно → стерео, звук только в одном канале: собеседник с другим
     * наушником перевода не слышит.
     */
    fun toSingleEar(mono: ShortArray, ear: Ear): ShortArray {
        val stereo = ShortArray(mono.size * 2)
        val index = if (ear == Ear.LEFT) 0 else 1
        for (i in mono.indices) stereo[i * 2 + index] = mono[i]
        return stereo
    }

    /** Моно → стерео в оба канала: перевод вслух на телефоне. */
    fun toBothEars(mono: ShortArray): ShortArray {
        val stereo = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            stereo[i * 2] = mono[i]
            stereo[i * 2 + 1] = mono[i]
        }
        return stereo
    }

    /** Стерео (или больше каналов) → моно: синтез может прийти в стерео. */
    fun toMono(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        return ShortArray(samples.size / channels) { frame ->
            var sum = 0
            for (c in 0 until channels) sum += samples[frame * channels + c]
            (sum / channels).toShort()
        }
    }
}

enum class Ear { LEFT, RIGHT;

    val other: Ear get() = if (this == LEFT) RIGHT else LEFT
}
