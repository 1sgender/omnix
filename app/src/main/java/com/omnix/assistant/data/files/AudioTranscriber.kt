package com.omnix.assistant.data.files

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.data.remote.OmnixApiClient
import com.omnix.assistant.domain.files.AudioPcm
import com.omnix.assistant.voice.conversation.PcmWav
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Аудиофайл → текст (для вложения в чат). */
fun interface AudioTranscriber {
    suspend fun transcribe(uri: Uri): Outcome

    sealed interface Outcome {
        data class Success(val text: String, val truncated: Boolean) : Outcome
        /** Файл не декодируется (нет звуковой дорожки, повреждён). */
        data object Unreadable : Outcome
        /** Расшифровка на сервере не удалась (сеть, лимит, ошибка распознавания). */
        data object Failed : Outcome
        /** Речи не нашлось. */
        data object Empty : Outcome
    }

    companion object {
        val NONE: AudioTranscriber = AudioTranscriber { Outcome.Failed }
    }
}

/**
 * Расшифровка через серверный Whisper (/v1/stt/transcribe): файл декодируется
 * системным MediaCodec (mp3, m4a/aac, ogg/opus, wav, flac, amr), сводится в моно
 * 16 кГц и уходит кусками по 28 с — сервер принимает фразу до 30 с.
 */
@Singleton
class ServerAudioTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: OmnixApiClient
) : AudioTranscriber {

    override suspend fun transcribe(uri: Uri): AudioTranscriber.Outcome {
        val decoded = withContext(Dispatchers.IO) {
            try {
                decode(uri, AudioPcm.MAX_SECONDS)
            } catch (e: IOException) {
                Log.w(TAG, "audio decode failed: ${e.javaClass.simpleName}")
                null
            } catch (e: IllegalStateException) {
                Log.w(TAG, "audio decode failed: ${e.javaClass.simpleName}")
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "audio decode failed: ${e.javaClass.simpleName}")
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        } ?: return AudioTranscriber.Outcome.Unreadable

        val pcm16k = withContext(Dispatchers.Default) { AudioPcm.resample(decoded.mono, decoded.sampleRate) }
        val chunks = AudioPcm.chunks(pcm16k)
        if (chunks.isEmpty()) return AudioTranscriber.Outcome.Empty

        val parts = ArrayList<String>(chunks.size)
        for (chunk in chunks) {
            val wav = PcmWav.encode(chunk, AudioPcm.TARGET_RATE)
            when (val result = apiClient.transcribe(wav)) {
                is Resource.Success -> parts += result.data.text
                else -> return AudioTranscriber.Outcome.Failed
            }
        }
        val text = AudioPcm.joinTranscripts(parts)
        if (text.isBlank()) return AudioTranscriber.Outcome.Empty
        return AudioTranscriber.Outcome.Success(text, decoded.truncated)
    }

    private class Decoded(val mono: ShortArray, val sampleRate: Int, val truncated: Boolean)

    /** Первая звуковая дорожка → моно PCM 16 бит не длиннее [maxSeconds]. */
    private fun decode(uri: Uri, maxSeconds: Int): Decoded? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            val mono = GrowingShorts()
            var truncated = false
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var idle = 0
                while (!outputDone && idle < MAX_IDLE_POLLS) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex) ?: return null
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex >= 0 -> {
                            idle = 0
                            val out = codec.getOutputBuffer(outIndex)
                            if (out != null && info.size > 0) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val frame = ShortArray(shorts.remaining())
                                shorts.get(frame)
                                mono.add(AudioPcm.toMono(frame, channels))
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (sampleRate > 0 && mono.size >= sampleRate.toLong() * maxSeconds) {
                                truncated = !outputDone
                                outputDone = true
                            }
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        else -> idle++
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            if (mono.size == 0 || sampleRate <= 0) return null
            val limit = (sampleRate.toLong() * maxSeconds).toInt()
            val samples = mono.toArray().let { if (it.size > limit) it.copyOf(limit) else it }
            return Decoded(samples, sampleRate, truncated)
        } finally {
            extractor.release()
        }
    }

    /** Растущий массив сэмплов без упаковки в List<Short>. */
    private class GrowingShorts {
        private var data = ShortArray(64 * 1024)
        var size = 0
            private set

        fun add(chunk: ShortArray) {
            if (size + chunk.size > data.size) {
                var capacity = data.size
                while (capacity < size + chunk.size) capacity *= 2
                data = data.copyOf(capacity)
            }
            chunk.copyInto(data, size)
            size += chunk.size
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    private companion object {
        const val TAG = "AudioTranscriber"
        const val TIMEOUT_US = 10_000L
        /** Защита от зависшего декодера: столько пустых опросов подряд — выходим. */
        const val MAX_IDLE_POLLS = 1_000
    }
}
