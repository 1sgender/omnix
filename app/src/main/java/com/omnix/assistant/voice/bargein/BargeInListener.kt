package com.omnix.assistant.voice.bargein

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.omnix.assistant.voice.live.DualMicMixer

/**
 * Слушает микрофон, пока OMNIX говорит, и сообщает, что человек его перебил.
 *
 * Запись идёт источником голосовой связи с эхоподавлением и шумоподавлением (без
 * автоусиления: оно выравнивало бы громкость и мешало отличать человека от эха). Решение
 * принимает [BargeInDetector]. Последняя секунда звука хранится, чтобы после срабатывания
 * распознаватель получил и начало фразы ([preRoll]).
 *
 * После срабатывания запись продолжается: кадры уходят в [onFrameAfterTrigger] (канал в
 * распознаватель на Android 13+), пока вызывающий не остановит слушатель.
 */
class BargeInListener(
    private val onBargeIn: (preRoll: List<ShortArray>) -> Unit,
    private val onFrameAfterTrigger: (ShortArray) -> Unit = {}
) {
    private val detector = BargeInDetector()
    private val preRoll = ArrayDeque<ShortArray>()

    @Volatile
    private var running = false

    @Volatile
    private var triggered = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val effects = ArrayList<AudioEffect>(2)

    /** false — микрофон не открылся (занят): перебивание в этот раз недоступно. */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val r = open() ?: return false
        record = r
        attachEffects(r.audioSessionId)
        detector.reset()
        preRoll.clear()
        triggered = false
        running = true
        thread = Thread(::loop, "omnix-barge-in").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        runCatching { thread?.join(JOIN_MS) }
        thread = null
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    private fun loop() {
        val buffer = ShortArray(FRAME)
        while (running) {
            val r = record ?: return
            var off = 0
            while (off < FRAME && running) {
                val n = r.read(buffer, off, FRAME - off)
                if (n <= 0) return
                off += n
            }
            if (!running) return
            val frame = buffer.copyOf()
            if (triggered) {
                onFrameAfterTrigger(frame)
                continue
            }
            preRoll.addLast(frame)
            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            if (detector.accept(DualMicMixer.levelDb(frame))) {
                triggered = true
                onBargeIn(preRoll.toList())
            }
        }
    }

    @SuppressLint("MissingPermission") // Голосовой помощник работает только с разрешением на микрофон.
    private fun open(): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, FRAME * 2 * BUFFER_FRAMES)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            null
        } else {
            r.startRecording()
            r
        }
    } catch (e: Exception) {
        Log.w(TAG, "barge-in microphone unavailable: ${e.javaClass.simpleName}")
        null
    }

    private fun attachEffects(sessionId: Int) {
        if (sessionId <= 0) return
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()?.let(effects::add)
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()?.let(effects::add)
        }
    }

    companion object {
        private const val TAG = "BargeInListener"
        const val SAMPLE_RATE = 16_000
        private const val FRAME = 320
        private const val BUFFER_FRAMES = 8
        private const val PRE_ROLL_FRAMES = 50
        private const val JOIN_MS = 500L
    }
}
