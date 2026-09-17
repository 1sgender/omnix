package com.omnix.server.http

import com.omnix.server.api.ApiErrorCode
import com.omnix.server.api.ApiErrorResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Подмножество ответа api.github.com для одного ассета релиза. */
@Serializable
private data class GhAssetDto(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    @SerialName("size") val sizeBytes: Long = 0L
)

/** Подмножество ответа api.github.com для релиза по тегу. */
@Serializable
private data class GhReleaseDto(
    @SerialName("tag_name") val tag: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("assets") val assets: List<GhAssetDto> = emptyList()
)

/** Успешный ответ GET /v1/app/latest. */
@Serializable
data class AppLatestResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("channel") val channel: String,
    @SerialName("versionCode") val versionCode: Long,
    @SerialName("url") val url: String,
    @SerialName("sizeBytes") val sizeBytes: Long = 0L,
    @SerialName("sha") val sha: String = "",
    @SerialName("publishedAt") val publishedAt: String = "",
    @SerialName("stale") val stale: Boolean = false
)

/**
 * Публичный OTA-канал: `GET /v1/app/latest?channel=staging`.
 *
 * Источник истины — GitHub Release с плавающим тегом (`staging`, `prod`),
 * который публикует release-пайплайн после каждой подписанной сборки.
 * Релизные заметки несут `versionCode=<run_number>` и `sha=<commit>` —
 * приложение сравнивает versionCode со своим и при необходимости качает APK
 * напрямую с `browser_download_url` ассета (без участия сервера в трафике).
 *
 * Публичность осознанная: отдаются только номер сборки и ссылка на уже
 * публичный релиз — секретов здесь нет (как в `/v1/health`). Кэш 5 минут
 * защищает от drown-атаки на GitHub API; при недоступности GitHub отдаётся
 * stale-кэш с флагом `"stale": true`.
 */
class AppUpdateHttpHandler(
    private val releaseRepo: String = System.getenv("OMNIX_APP_RELEASE_REPO")
        ?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_REPO,
    private val githubApiBase: String = DEFAULT_API_BASE,
    private val fetchReleaseJson: suspend (tag: String) -> String? =
        { tag -> fetchFromGitHub(githubApiBase, releaseRepo, tag) },
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        const val PATH_APP_LATEST = "/v1/app/latest"
        const val DEFAULT_REPO = "freelanceTM/omnix"
        const val DEFAULT_API_BASE = "https://api.github.com"
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val MAX_RELEASE_JSON_BYTES = 256 * 1024L
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val CALL_TIMEOUT_MS = 15_000L
        private val CHANNEL_TAG = mapOf("staging" to "staging", "prod" to "prod")
        private val CHANNEL_ASSET = mapOf(
            "staging" to "OMNIX-staging.apk",
            "prod" to "OMNIX-prod.apk"
        )
        private val CHANNEL_REGEX = Regex("^[a-z]{1,16}$")
        private val VERSION_CODE_REGEX = Regex("(?m)^\\s*versionCode=(\\d{1,19})\\s*$")
        private val SHA_REGEX = Regex("(?m)^\\s*sha=([0-9a-f]{7,40})\\s*$")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        private suspend fun fetchFromGitHub(apiBase: String, repo: String, tag: String): String? =
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$apiBase/repos/$repo/releases/tags/$tag")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "omnix-server ota-channel")
                    .get()
                    .build()
                try {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body ?: return@withContext null
                        if (body.contentLength() > MAX_RELEASE_JSON_BYTES) return@withContext null
                        val bytes = body.bytes()
                        if (bytes.size > MAX_RELEASE_JSON_BYTES) return@withContext null
                        bytes.toString(Charsets.UTF_8)
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    private data class CachedRelease(val fetchedAtMs: Long, val response: AppLatestResponse)

    private val cacheLock = Any()
    private val cache = mutableMapOf<String, CachedRelease>()

    /**
     * Возвращает null для чужих путей (встраивается в цепочку
     * `extensionHandler` в Main перед терминальным clip-хендлером).
     */
    suspend fun handle(request: HttpRequestContext): HttpResponseContext? {
        if (request.path != PATH_APP_LATEST) return null
        if (request.method != "GET") {
            return error(ApiErrorCode.INVALID_REQUEST, 405)
        }
        val channel = queryParam(request.rawQuery, "channel")?.lowercase() ?: "staging"
        if (!CHANNEL_REGEX.matches(channel) || channel !in CHANNEL_TAG) {
            return error(ApiErrorCode.INVALID_REQUEST)
        }
        val tag = CHANNEL_TAG.getValue(channel)
        val assetName = CHANNEL_ASSET.getValue(channel)

        val now = clockMs()
        synchronized(cacheLock) {
            cache[channel]?.let { cached ->
                if (now - cached.fetchedAtMs < CACHE_TTL_MS) {
                    return ok(cached.response)
                }
            }
        }
        val releaseJson = try {
            fetchReleaseJson(tag)
        } catch (_: Exception) {
            null
        }
        if (releaseJson == null) {
            synchronized(cacheLock) {
                cache[channel]?.let { cached ->
                    return ok(cached.response.copy(stale = true))
                }
            }
            return error(
                ApiErrorCode.PROVIDER_UNAVAILABLE,
                message = "Application update source unavailable"
            )
        }
        val release = try {
            json.decodeFromString(GhReleaseDto.serializer(), releaseJson)
        } catch (_: Exception) {
            return error(
                ApiErrorCode.PROVIDER_UNAVAILABLE,
                message = "Application update source unavailable"
            )
        }
        val asset = release.assets.firstOrNull { it.name == assetName }
        val versionCode = VERSION_CODE_REGEX.find(release.body)?.groupValues?.get(1)?.toLongOrNull()
        if (asset == null || !asset.url.startsWith("https://") || versionCode == null) {
            return error(
                ApiErrorCode.PROVIDER_UNAVAILABLE,
                message = "No published build for this channel yet"
            )
        }
        val response = AppLatestResponse(
            channel = channel,
            versionCode = versionCode,
            url = asset.url,
            sizeBytes = asset.sizeBytes,
            sha = SHA_REGEX.find(release.body)?.groupValues?.get(1).orEmpty(),
            publishedAt = release.publishedAt,
            stale = false
        )
        synchronized(cacheLock) {
            cache[channel] = CachedRelease(fetchedAtMs = now, response = response)
        }
        return ok(response)
    }

    private fun ok(response: AppLatestResponse): HttpResponseContext =
        HttpResponseContext(
            status = 200,
            body = json.encodeToString(AppLatestResponse.serializer(), response),
            headers = mapOf("Cache-Control" to "public, max-age=60")
        )

    private fun error(
        code: ApiErrorCode,
        statusOverride: Int? = null,
        message: String? = null
    ): HttpResponseContext = HttpResponseContext(
        status = statusOverride ?: code.httpStatus,
        body = json.encodeToString(
            ApiErrorResponse.serializer(),
            code.toResponse(UUID.randomUUID().toString(), message)
        ),
        headers = mapOf("Cache-Control" to "no-store")
    )

    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        return rawQuery.split("&").firstNotNullOfOrNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@firstNotNullOfOrNull null
            val key = part.substring(0, index).trim()
            if (!key.equals(name, ignoreCase = true)) return@firstNotNullOfOrNull null
            part.substring(index + 1).trim().takeIf { it.isNotEmpty() }
        }
    }
}
