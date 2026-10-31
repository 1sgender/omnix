package com.omnix.assistant.voice.conversation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Озвучивает перевод в одно ухо. Синтез идёт в файл, звук играется в стерео
 * по обычному каналу (A2DP): левый наушник у одного человека, правый у
 * другого. В режиме гарнитуры (SCO) звук моно и идёт в оба уха — поэтому
 * разговор слушает микрофон телефона, а не наушников.
 */
class EarSplitPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    @Volatile
    private var track: AudioTrack? = null

    /** Есть ли голос для языка (туркменского у системных движков обычно нет). */
    suspend fun canSpeak(language: String): Boolean {
        if (!ready.await()) return false
        return tts.isLanguageAvailable(Locale.forLanguageTag(language)) >= TextToSpeech.LANG_AVAILABLE
    }

    /**
     * [ear] null — в оба канала (вслух на телефоне). false — голоса для языка
     * нет или синтез не удался: перевод только на экране.
     */
    suspend fun speak(text: String, language: String, ear: Ear?): Boolean {
        if (text.isBlank() || !canSpeak(language)) return false
        val wav = synthesize(text, Locale.forLanguageTag(language)) ?: return false
        val decoded = PcmWav.decode(wav) ?: return false
        val mono = PcmWav.toMono(decoded.samples, decoded.channels)
        play(if (ear == null) PcmWav.toBothEars(mono) else PcmWav.toSingleEar(mono, ear), decoded.sampleRate)
        return true
    }

    fun stop() {
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    fun release() {
        stop()
        runCatching { tts.shutdown() }
    }

    private suspend fun synthesize(text: String, locale: Locale): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(appContext.cacheDir, "ear-${UUID.randomUUID()}.wav")
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        try {
            synchronized(tts) {
                tts.setLanguage(locale)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) done.complete(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id) done.complete(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id) done.complete(false)
                    }
                })
                if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) return@withContext null
            }
            val ok = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { done.await() } ?: false
            if (ok && file.length() > 44) file.readBytes() else null
        } catch (e: Exception) {
            Log.w(TAG, "synthesis failed: ${e.javaClass.simpleName}")
            null
        } finally {
            file.delete()
        }
    }

    private suspend fun play(stereo: ShortArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(stereo.size * 2)
            .build()
        track = audioTrack
        try {
            audioTrack.write(stereo, 0, stereo.size)
            audioTrack.play()
            val durationMs = stereo.size / 2 * 1000L / sampleRate
            delay(durationMs + TAIL_MS)
        } finally {
            track = null
            runCatching { audioTrack.stop() }
            audioTrack.release()
        }
    }

    private companion object {
        const val TAG = "EarSplitPlayer"
        const val SYNTH_TIMEOUT_MS = 15_000L
        const val TAIL_MS = 150L
    }
}
