package com.omnix.assistant.agent.media.ymusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Неофициальный протокол api.music.yandex.net (по исходникам библиотеки
 * MarshalX/yandex-music-api). Официального публичного API у Яндекс Музыки нет:
 * формат может измениться без предупреждения, использование нарушает условия
 * сервиса. Это решение владельца.
 *
 * Здесь только чистая логика: подпись запроса и разбор ответов. Сеть в
 * [YandexMusicClient], чтобы всё это проверялось обычными юнит-тестами.
 */
data class YmTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val durationMs: Long?
) {
    val artistLine: String get() = artists.joinToString(", ")
}

/** Прямая ссылка на аудио (без шифрования), готовая для плеера. */
data class YmStream(val url: String, val codec: String?, val bitrateKbps: Int?)

object YandexMusicProtocol {
    const val BASE_URL = "https://api.music.yandex.net"

    /** Заголовок и ключ подписи десктопного клиента (публичные константы клиента, не секрет пользователя). */
    const val CLIENT_HEADER_NAME = "X-Yandex-Music-Client"
    const val CLIENT_HEADER_VALUE = "YandexMusicDesktopAppWindows/5.95.0"
    private const val FILE_INFO_SIGN_KEY = "kzqU4XhfCaY6B6JTHODeq5"

    /** Обычное качество, нешифрованная доставка: ссылку играет любой плеер. */
    const val QUALITY = "nq"
    const val TRANSPORT = "raw"
    val CODECS: List<String> = listOf("mp3", "aac")

    /** Заголовок авторизации: токен никогда не попадает в URL и в логи. */
    fun authorizationHeader(token: String): String = "OAuth ${token.trim()}"

    /**
     * Подпись get-file-info: HMAC-SHA256 от «время + id + качество + кодеки (подряд) + транспорт»,
     * base64 без завершающего «=».
     */
    fun fileInfoSign(
        timestampSec: Long,
        trackId: String,
        quality: String = QUALITY,
        codecs: List<String> = CODECS,
        transport: String = TRANSPORT
    ): String {
        val message = "$timestampSec$trackId$quality${codecs.joinToString("")}$transport"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(FILE_INFO_SIGN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest).trimEnd('=')
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Ответ /search?type=track. Недоступные для воспроизведения треки отбрасываются. */
    fun parseSearch(body: String): List<YmTrack> {
        val root = parseObject(body) ?: return emptyList()
        val results = root.obj("result")?.obj("tracks")?.arr("results") ?: return emptyList()
        return results.mapNotNull { it.asObjectOrNull()?.toTrack() }
    }

    /** Ответ /get-file-info. null — ссылки нет или она зашифрована (encraw) и играть её нечем. */
    fun parseFileInfo(body: String): YmStream? {
        val info = parseObject(body)?.obj("result")?.obj("downloadInfo") ?: return null
        val url = info.str("url")?.takeIf { it.startsWith("https://") } ?: return null
        val transport = info.str("transport")
        val key = info.str("key")
        if ((transport != null && transport != TRANSPORT) || !key.isNullOrBlank()) return null
        return YmStream(url, info.str("codec"), info["bitrate"]?.primitiveOrNull()?.longOrNull?.toInt())
    }

    /** Оставляет не более [limit] проигрываемых треков очереди. */
    fun playableQueue(tracks: List<YmTrack>, limit: Int = 10): List<YmTrack> =
        tracks.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(limit)

    private fun parseObject(body: String): JsonObject? =
        try { json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }

    private fun JsonObject.toTrack(): YmTrack? {
        if (this["available"]?.primitiveOrNull()?.booleanOrNull == false) return null
        val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
        val base = str("title")?.takeIf { it.isNotBlank() } ?: return null
        val version = str("version")?.takeIf { it.isNotBlank() }
        val artists = arr("artists")?.mapNotNull { it.asObjectOrNull()?.str("name") }.orEmpty()
        return YmTrack(
            id = id,
            title = if (version != null) "$base ($version)" else base,
            artists = artists,
            durationMs = this["durationMs"]?.primitiveOrNull()?.longOrNull
        )
    }

    private fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray
    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
