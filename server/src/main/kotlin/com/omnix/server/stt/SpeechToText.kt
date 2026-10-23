package com.omnix.server.stt

import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.provider.TransportException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Распознавание речи для переводчика «по наушнику на каждого» (отзыв
 * владельца на v344): телефон присылает одну фразу, сервер возвращает текст
 * и язык. Распознаватель Android не умеет определять язык сам и не знает
 * туркменского, поэтому фраза идёт в Whisper.
 */
sealed interface SttOutcome {
    data class Success(val text: String, val language: String?) : SttOutcome
    data class Failure(val kind: ProviderFailureKind) : SttOutcome
}

interface SpeechToText {
    suspend fun transcribe(wav: ByteArray, languageHint: String?): SttOutcome
}

@Serializable
private data class WhisperResponse(
    @SerialName("text") val text: String = "",
    @SerialName("language") val language: String? = null
)

/** Whisper в Groq (OpenAI-совместимый /audio/transcriptions). */
class GroqWhisperSpeechToText(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val url: String = DEFAULT_URL,
    private val connectTimeoutMs: Long = 3_000,
    private val requestTimeoutMs: Long = 15_000,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SpeechToText {

    override suspend fun transcribe(wav: ByteArray, languageHint: String?): SttOutcome {
        val fields = buildList {
            add("model" to model)
            add("response_format" to "verbose_json")
            add("temperature" to "0")
            WhisperLanguages.isoCode(languageHint)?.let { add("language" to it) }
        }
        val boundary = "omnix-" + UUID.randomUUID().toString().replace("-", "")
        val body = multipartBody(boundary, fields, fileField = "file", fileName = "speech.wav", fileType = "audio/wav", file = wav)
        val response = try {
            transport.postBytes(
                url = url,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                body = body,
                contentType = "multipart/form-data; boundary=$boundary",
                connectTimeoutMs = connectTimeoutMs,
                requestTimeoutMs = requestTimeoutMs
            )
        } catch (e: TransportException) {
            return SttOutcome.Failure(e.kind)
        }
        return when {
            response.status == 429 -> SttOutcome.Failure(ProviderFailureKind.RATE_LIMITED)
            response.status >= 500 -> SttOutcome.Failure(ProviderFailureKind.SERVER_ERROR)
            response.status !in 200..299 -> SttOutcome.Failure(ProviderFailureKind.UNKNOWN)
            else -> runCatching { json.decodeFromString(WhisperResponse.serializer(), response.body) }
                .map { SttOutcome.Success(it.text.trim(), WhisperLanguages.isoCode(it.language)) }
                .getOrElse { SttOutcome.Failure(ProviderFailureKind.UNKNOWN) }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "whisper-large-v3"
        const val DEFAULT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    }
}

/** multipart/form-data: текстовые поля и один файл. */
internal fun multipartBody(
    boundary: String,
    fields: List<Pair<String, String>>,
    fileField: String,
    fileName: String,
    fileType: String,
    file: ByteArray
): ByteArray {
    val out = ByteArrayOutputStream(file.size + 1024)
    fun write(text: String) = out.write(text.toByteArray(StandardCharsets.UTF_8))
    for ((name, value) in fields) {
        write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
    }
    write("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\nContent-Type: $fileType\r\n\r\n")
    out.write(file)
    write("\r\n--$boundary--\r\n")
    return out.toByteArray()
}

/**
 * WAV PCM от телефона: заголовок RIFF/WAVE и не длиннее [maxSeconds]
 * (16 кГц, 16 бит, моно — 32 КБ в секунду).
 */
object WavCheck {
    fun isPlausible(bytes: ByteArray, maxSeconds: Int = 30): Boolean {
        if (bytes.size < 44 || bytes.size > 44 + maxSeconds * 32_000) return false
        val riff = String(bytes, 0, 4, StandardCharsets.US_ASCII)
        val wave = String(bytes, 8, 4, StandardCharsets.US_ASCII)
        return riff == "RIFF" && wave == "WAVE"
    }
}

/**
 * Whisper отвечает названием языка («russian»), а принимает код («ru»).
 * Нужны языки OMNIX и соседи, с которыми их путают.
 */
object WhisperLanguages {
    private val byName = mapOf(
        "russian" to "ru", "english" to "en", "turkmen" to "tk", "turkish" to "tr",
        "uzbek" to "uz", "kazakh" to "kk", "azerbaijani" to "az", "ukrainian" to "uk",
        "german" to "de", "french" to "fr", "spanish" to "es", "arabic" to "ar",
        "persian" to "fa", "chinese" to "zh", "kyrgyz" to "ky", "tatar" to "tt"
    )
    private val codes = byName.values.toSet()

    fun isoCode(value: String?): String? {
        val v = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            v in codes -> v
            else -> byName[v]
        }
    }
}
