package com.omnix.assistant.voice.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Аппаратные эффекты сессии AudioRecord, где телефон их поддерживает: шумоподавление,
 * автоусиление, эхоподавление. Каждый создаётся только после проверки isAvailable;
 * любой сбой эффекта не мешает записи. Обязательно вызвать [release] вместе с AudioRecord.
 * Для детектора слова вызова не используется.
 */
class MicEffects private constructor(private val effects: List<AudioEffect>) {

    /** Сколько эффектов реально включено (для журнала/диагностики). */
    val activeCount: Int get() = effects.size

    fun release() {
        for (effect in effects) {
            runCatching { effect.release() }
        }
    }

    companion object {
        private const val TAG = "MicEffects"

        fun attach(audioSessionId: Int): MicEffects {
            val list = ArrayList<AudioEffect>(3)
            if (audioSessionId > 0) {
                create("NoiseSuppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(audioSessionId) }?.let(list::add)
                create("AutomaticGainControl", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(audioSessionId) }?.let(list::add)
                create("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(audioSessionId) }?.let(list::add)
            }
            return MicEffects(list)
        }

        private fun create(name: String, available: Boolean, factory: () -> AudioEffect?): AudioEffect? {
            if (!available) return null
            return try {
                val effect = factory() ?: return null
                effect.enabled = true
                effect
            } catch (e: Exception) {
                Log.w(TAG, "$name unavailable: ${e.javaClass.simpleName}")
                null
            }
        }
    }
}
