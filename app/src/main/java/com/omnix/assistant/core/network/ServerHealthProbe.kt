package com.omnix.assistant.core.network

import com.omnix.assistant.core.constants.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Отвечает ли сервер OMNIX прямо сейчас: `GET /v1/health`.
 *
 * Нужен экрану выбора ИИ-модели — облачный вариант сохраняется, только если
 * сервер действительно отвечает. Эндпоинт публичный, токен не отправляется.
 * Любой сбой (нет сети, таймаут, не 2xx) — «недоступен».
 */
@Singleton
class ServerHealthProbe @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun isReachable(baseUrl: String = AppConstants.OMNIX_API_BASE_URL): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url("$baseUrl/v1/health").get().build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
}
