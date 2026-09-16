package com.omnix.assistant.data.remote.interceptor

import com.omnix.assistant.BuildConfig
import com.omnix.assistant.core.security.SecurityManager
import com.omnix.assistant.data.remote.OmnixApiClient
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import javax.inject.Inject

/**
 * Добавляет токен доступа OMNIX API (Этап 3).
 *
 * ВАЖНО: токен подставляется ТОЛЬКО для запросов к собственному бэкенду.
 * Ключей AI-провайдеров на устройстве больше нет, а отправлять свой токен
 * на посторонние хосты недопустимо — поэтому строгая проверка хоста.
 *
 * `OmnixApiClient` ставит заголовок сам; интерсептор нужен для остальных
 * вызовов к api.omnix.ai (лицензии, конфиг) и как страховка.
 */
class AuthInterceptor @Inject constructor(
    private val securityManager: SecurityManager,
    private val licenseManager: com.omnix.assistant.core.license.LicenseManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val requestBuilder = originalRequest.newBuilder()
            .header("Content-Type", "application/json")
            .header("User-Agent", "OMNIX-Android/0.3")

        val isOmnixBackend = BackendRequestPolicy.isTrusted(originalRequest.url)
        if (originalRequest.header("Authorization") != null && !isOmnixBackend) {
            throw IOException("Refusing to send Authorization outside the configured OMNIX origin")
        }
        if (isOmnixBackend) {
            if (originalRequest.header("Authorization") == null) {
                val token = securityManager.getAccessToken().trim()
                if (token.isNotEmpty()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
            }
            // V007: enforcement-путь сервера требует устройство на КАЖДОМ
            // backend-запросе — в том числе там, где Authorization уже
            // выставлен вызывающим кодом (OmnixApiClient ставит его сам,
            // поэтому привязка к наличию Authorization означала бы, что omx_-токены
            // отвергаются сервером fail-closed на AI-пути). Тот же ID,
            // что в redeem/validate (сервер хранит хеш и решает сам).
            // Явно выставленный вызывающим заголовок не перезаписываем.
            if (originalRequest.header("X-Omnix-Device") == null) {
                val deviceId = licenseManager.getDeviceId()
                if (deviceId.isNotBlank()) {
                    requestBuilder.header("X-Omnix-Device", deviceId)
                }
            }
        }

        return chain.proceed(requestBuilder.build())
    }
}

internal object BackendRequestPolicy {
    private val backend: HttpUrl = OmnixApiClient.BASE_URL.toHttpUrl()

    fun isTrusted(url: HttpUrl): Boolean {
        val secureScheme = backend.scheme == "https" ||
            (BuildConfig.ALLOW_CLEARTEXT_BACKEND && backend.scheme == "http")
        return secureScheme && url.scheme == backend.scheme &&
            url.host == backend.host && url.port == backend.port
    }
}
