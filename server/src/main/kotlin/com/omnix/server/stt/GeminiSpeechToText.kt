package com.omnix.server.stt

import com.omnix.server.provider.ApiKeyPool
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.provider.TransportException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Распознавание речи через Gemini generateContent (аудио как inline_data).
 * Запасной/альтернативный вариант к Whisper в Groq: см. [ChainedSpeechToText].
 * Ключи берутся из пула (GEMINI_API_KEY через запятую), при 429 пробуем
 * следующий ключ. Текст распознавания и ключи в логи не пишем.
 */
class GeminiSpeechToText(
    private val transport: HttpTransport,
    private val keys: ApiKeyPool,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Long = 3_000,
    private val requestTimeoutMs: Long = 20_000,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SpeechToText {

    override suspend fun transcribe(wav: ByteArray, languageHint: String?): SttOutcome {
        if (keys.isEmpty) return SttOutcome.Failure(ProviderFailureKind.NOT_CONFIGURED)
        val body = buildBody(wav, WhisperLanguages.isoCode(languageHint))
        var last: SttOutcome = SttOutcome.Failure(ProviderFailureKind.NOT_CONFIGURED)
        repeat(minOf(keys.size, MAX_KEY_ATTEMPTS)) {
            val key = keys.current() ?: return last
            val outcome = attempt(key, body)
            if (outcome is SttOutcome.Failure && outcome.kind == ProviderFailureKind.RATE_LIMITED) {
                keys.reportRateLimited(key)
                last = outcome
            } else {
                if (outcome is SttOutcome.Success) keys.reportSuccess(key)
                return outcome
            }
        }
        return last
    }

    private suspend fun attempt(key: String, body: String): SttOutcome {
        val response = try {
            transport.post(
                url = "$baseUrl/$model:generateContent",
                headers = mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"),
                body = body,
                connectTimeoutMs = connectTimeoutMs,
                requestTimeoutMs = requestTimeoutMs
            )
        } catch (e: TransportException) {
            return SttOutcome.Failure(e.kind)
        }
        val status = response.status
        return when {
            status == 401 || status == 403 -> SttOutcome.Failure(ProviderFailureKind.AUTH)
            status == 429 -> SttOutcome.Failure(ProviderFailureKind.RATE_LIMITED)
            status == 400 || status == 404 || status == 422 -> SttOutcome.Failure(ProviderFailureKind.BAD_REQUEST)
            status >= 500 -> SttOutcome.Failure(ProviderFailureKind.SERVER_ERROR)
            status !in 200..299 -> SttOutcome.Failure(ProviderFailureKind.UNKNOWN)
            else -> parse(response.body)
        }
    }

    private fun buildBody(wav: ByteArray, hintIso: String?): String {
        val hint = if (hintIso != null) " Язык записи, скорее всего: $hintIso." else ""
        val prompt = "Дословно транскрибируй речь из аудио на языке оригинала. " +
            "Не переводи, не добавляй комментариев, не исправляй слова.$hint " +
            "Если речи нет, верни пустой text. " +
            "Верни JSON: text — расшифровка, language — ISO 639-1 код языка речи."
        val request = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", prompt) })
                                    add(
                                        buildJsonObject {
                                            put(
                                                "inline_data",
                                                buildJsonObject {
                                                    put("mime_type", "audio/wav")
                                                    put("data", Base64.getEncoder().encodeToString(wav))
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", 0)
                    put("responseMimeType", "application/json")
                    put(
                        "responseSchema",
                        buildJsonObject {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                buildJsonObject {
                                    put("text", buildJsonObject { put("type", "STRING") })
                                    put("language", buildJsonObject { put("type", "STRING") })
                                }
                            )
                            put("required", JsonArray(listOf(JsonPrimitive("text"))))
                        }
                    )
                }
            )
        }
        return json.encodeToString(JsonObject.serializer(), request)
    }

    private fun parse(body: String): SttOutcome {
        val answer = runCatching {
            json.parseToJsonElement(body).jsonObject["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
        }.getOrNull()?.trim()
        if (answer.isNullOrEmpty()) return SttOutcome.Failure(ProviderFailureKind.UNKNOWN)
        val structured = runCatching { json.parseToJsonElement(answer).jsonObject }.getOrNull()
        val text = if (structured != null) {
            structured["text"]?.jsonPrimitive?.contentOrNull
        } else {
            answer
        }?.trim()
        if (text.isNullOrEmpty()) return SttOutcome.Failure(ProviderFailureKind.UNKNOWN)
        val language = structured?.get("language")?.jsonPrimitive?.contentOrNull
        return SttOutcome.Success(text, WhisperLanguages.isoCode(language))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_KEY_ATTEMPTS = 3
    }
}
