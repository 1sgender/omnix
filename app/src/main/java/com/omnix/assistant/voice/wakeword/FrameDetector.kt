package com.omnix.assistant.voice.wakeword

import com.omnix.assistant.voice.wakeword.oww.OpenWakeWordPipeline
import com.omnix.assistant.voice.wakeword.oww.OrtOwwSessions
import java.io.Closeable

/**
 * Распознаватель на потоке кадров микрофона (16 кГц, моно). Движок
 * [NeuralWakeWordEngine] владеет микрофоном, порогами и ошибками, а что
 * именно слушать — решает детектор: прежняя модель «Omni» (openWakeWord)
 * или фраза «Окей, Омни» (sherpa-onnx, см. [com.omnix.assistant.voice.wakeword.phrase]).
 */
interface FrameDetector : Closeable {
    /** Скор кадра 0..1; null — кадр ещё копится. */
    fun accept(frame: ShortArray): OpenWakeWordPipeline.FrameResult?

    /** Забыть накопленный звук: после STT слушаем с чистого листа. */
    fun reset()
}

/** Прежняя модель «Omni»: мел-спектрограмма → эмбеддинги → классификатор. */
class OwwFrameDetector(private val sessions: OrtOwwSessions) : FrameDetector {
    private val pipeline = OpenWakeWordPipeline(sessions, sessions, sessions)

    override fun accept(frame: ShortArray): OpenWakeWordPipeline.FrameResult? = pipeline.accept(frame)

    override fun reset() = pipeline.reset()

    override fun close() = sessions.close()
}
