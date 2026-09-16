package com.omnix.assistant.update

import com.omnix.assistant.core.constants.AppConstants
import com.omnix.assistant.core.network.readUtf8Bounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Проверка обновлений: `GET {api}/v1/app/latest?channel=...`.
 *
 * Эндпоинт публичный — токен доступа НЕ нужен и НЕ отправляется.
 * Любой сбой (сеть, мусор в ответе, чужой channel) превращается в null:
 * отсутствие обновления никогда не должно мешать работе приложения.
 */
class AppUpdateChecker(
    private val baseUrl: String = AppConstants.OMNIX_API_BASE_URL,
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        private const val MAX_BODY_BYTES = 64 * 1024L
        private val CHANNEL_REGEX = Regex("^[a-z]{1,16}$")

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun check(channel: String): AppLatestInfo? = withContext(Dispatchers.IO) {
        val safeChannel = channel.trim().lowercase()
        if (!CHANNEL_REGEX.matches(safeChannel)) return@withContext null
        val request = Request.Builder()
            .url("$baseUrl/v1/app/latest?channel=$safeChannel")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                parseAppLatest(body.readUtf8Bounded(MAX_BODY_BYTES))
            }
        } catch (_: Exception) {
            null
        }
    }
}
