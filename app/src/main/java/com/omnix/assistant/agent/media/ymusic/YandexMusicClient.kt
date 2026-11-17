package com.omnix.assistant.agent.media.ymusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Итог сетевого шага. Причины без текста ответа и без токена: их можно показывать и писать в журнал. */
sealed interface YmResult<out T> {
    data class Ok<T>(val value: T) : YmResult<T>
    data object Unauthorized : YmResult<Nothing>
    data object NoResults : YmResult<Nothing>
    data object Unavailable : YmResult<Nothing>
}

/**
 * Клиент неофициального API Яндекс Музыки: поиск трека и прямая ссылка.
 * Токен уходит только в заголовок Authorization на api.music.yandex.net, никуда больше.
 * Логирующих перехватчиков нет намеренно.
 */
@Singleton
class YandexMusicClient internal constructor(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val nowMs: () -> Long
) {
    @Inject
    constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build(),
        YandexMusicProtocol.BASE_URL,
        System::currentTimeMillis
    )

    suspend fun search(query: String, token: String): YmResult<List<YmTrack>> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("text", query)
            .addQueryParameter("nocorrect", "False")
            .addQueryParameter("type", "track")
            .addQueryParameter("page", "0")
            .addQueryParameter("playlist-in-best", "False")
            .build()
        return when (val body = get(url, token)) {
            is YmResult.Ok -> {
                val tracks = YandexMusicProtocol.playableQueue(YandexMusicProtocol.parseSearch(body.value))
                if (tracks.isEmpty()) YmResult.NoResults else YmResult.Ok(tracks)
            }
            YmResult.Unauthorized -> YmResult.Unauthorized
            YmResult.NoResults -> YmResult.NoResults
            YmResult.Unavailable -> YmResult.Unavailable
        }
    }

    suspend fun resolve(trackId: String, token: String): YmResult<YmStream> {
        val ts = nowMs() / 1000
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("get-file-info")
            .addQueryParameter("ts", ts.toString())
            .addQueryParameter("trackId", trackId)
            .addQueryParameter("quality", YandexMusicProtocol.QUALITY)
            .addQueryParameter("codecs", YandexMusicProtocol.CODECS.joinToString(","))
            .addQueryParameter("transports", YandexMusicProtocol.TRANSPORT)
            .addQueryParameter("sign", YandexMusicProtocol.fileInfoSign(ts, trackId))
            .build()
        return when (val body = get(url, token, desktopClient = true)) {
            is YmResult.Ok -> YandexMusicProtocol.parseFileInfo(body.value)
                ?.let { YmResult.Ok(it) } ?: YmResult.Unavailable
            YmResult.Unauthorized -> YmResult.Unauthorized
            YmResult.NoResults -> YmResult.NoResults
            YmResult.Unavailable -> YmResult.Unavailable
        }
    }

    private suspend fun get(
        url: okhttp3.HttpUrl,
        token: String,
        desktopClient: Boolean = false
    ): YmResult<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", YandexMusicProtocol.authorizationHeader(token))
            .header("User-Agent", "Yandex-Music-API")
            .apply {
                if (desktopClient) header(YandexMusicProtocol.CLIENT_HEADER_NAME, YandexMusicProtocol.CLIENT_HEADER_VALUE)
            }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> YmResult.Unauthorized
                    response.code == 404 -> YmResult.NoResults
                    !response.isSuccessful -> YmResult.Unavailable
                    else -> YmResult.Ok(response.body?.string().orEmpty())
                }
            }
        } catch (_: IOException) {
            YmResult.Unavailable
        }
    }
}
