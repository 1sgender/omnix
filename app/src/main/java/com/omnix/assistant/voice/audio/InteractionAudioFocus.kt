package com.omnix.assistant.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Музыка и видео встают на паузу, пока человек разговаривает с OMNIX (сказал «Омни» → команда →
 * ответ → окно продолжения), и продолжают играть сами, когда OMNIX снова ждёт «Омни».
 *
 * Временный фокус звука (AUDIOFOCUS_GAIN_TRANSIENT): YouTube, музыкальные плееры и другие
 * приложения, которые уважают фокус, ставят себя на паузу и сами возобновляются, когда фокус
 * отпущен. Повторные [hold]/[release] безопасны.
 */
@Singleton
class InteractionAudioFocus @Inject constructor(@ApplicationContext private val context: Context) {

    private val audioManager: AudioManager? by lazy {
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    }

    private val request: AudioFocusRequest? by lazy {
        runCatching {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
        }.getOrNull()
    }

    @Volatile
    private var held = false

    /** Разговор начался: остановить чужой звук. */
    fun hold() {
        if (held) return
        held = try {
            val am = audioManager ?: return
            val r = request ?: return
            am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "interaction focus failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Разговор закончился: чужой звук продолжает играть. */
    fun release() {
        if (!held) return
        held = false
        val r = request ?: return
        runCatching { audioManager?.abandonAudioFocusRequest(r) }
    }

    private companion object {
        const val TAG = "InteractionFocus"
    }
}
