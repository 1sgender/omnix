package com.omnix.assistant.voice.wakeword.verify

import android.util.Log
import com.omnix.assistant.voice.offline.LocalSpeechRecognizer
import com.omnix.assistant.voice.offline.OfflineSpeechModelStore
import com.omnix.assistant.voice.offline.OfflineSpeechModels
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вторая ступень слова вызова: детектор (openWakeWord, низкий порог) нашёл похожее на «Омни»,
 * GigaAM на телефоне распознаёт ~2 с звука вокруг, [WakeTextRule] решает, было ли там «Омни».
 *
 * Замер 2026-11-28 на звуке комнаты владельца (20 мин, фильм): детектор — 741 кандидат в час,
 * после проверки — 0. GigaAM работает только на кандидатах, поэтому батарею почти не тратит.
 */
@Singleton
class WakeVerifier @Inject constructor(
    private val asr: LocalSpeechRecognizer,
    private val modelStore: OfflineSpeechModelStore
) {
    /** Серия ложных срабатываний → строгое правило на 10 минут. */
    val burst = WakeBurstGuard()

    data class Result(val accepted: Boolean, val text: String?)

    /** Модель GigaAM на телефоне; без неё две ступени невозможны (тогда слушает слово-фраза). */
    fun isReady(): Boolean = asr.gigaAmReady()

    /** Запустить загрузку GigaAM (по Wi-Fi), если её ещё нет. */
    fun requestModel() {
        if (!isReady() && !modelStore.isBusy(OfflineSpeechModels.RU_ASR)) {
            modelStore.download(OfflineSpeechModels.RU_ASR, overMetered = false)
        }
    }

    suspend fun verify(window: ShortArray, strict: Boolean): Result {
        val text = asr.transcribeGigaAm(window)
        val ok = text != null && WakeTextRule.matches(text, strict)
        Log.i(TAG, "wake check: accepted=$ok strict=$strict text=\"${text.orEmpty().take(MAX_LOG_CHARS)}\"")
        return Result(ok, text)
    }

    companion object {
        private const val TAG = "WakeVerifier"
        private const val MAX_LOG_CHARS = 60

        /** Порог первой ступени: чуткий, лишнее отсекает проверка. */
        const val STAGE1_THRESHOLD = 0.2f

        /** Окно для проверки: 1,8 с до срабатывания и 0,3 с после (замер 2026-11-28). */
        const val WINDOW_BEFORE_MS = 1_800
        const val WINDOW_AFTER_MS = 300
    }
}
