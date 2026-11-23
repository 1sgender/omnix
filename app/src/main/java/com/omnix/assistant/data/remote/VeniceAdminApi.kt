package com.omnix.assistant.data.remote

import com.omnix.assistant.core.network.readUtf8Bounded
import com.omnix.assistant.core.security.AccessTokenPolicy
import com.omnix.assistant.core.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Текстовая модель Venice из списка сервера. Цены: USD за 1M токенов. */
@Serializable
data class VeniceModelInfo(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("contextTokens") val contextTokens: Long? = null,
    @SerialName("inputUsdPerMillion") val inputUsdPerMillion: Double? = null,
    @SerialName("outputUsdPerMillion") val outputUsdPerMillion: Double? = null,
    /** private / anonymized / e2ee / unknown. */
    @SerialName("privacy") val privacy: String = "unknown",
    /** Модель без цензуры (uncensored, abliterated). */
    @SerialName("uncensored") val uncensored: Boolean = false
)

@Serializable
private data class VeniceModelsDto(
    @SerialName("models") val models: List<VeniceModelInfo> = emptyList()
)

/** Итог запроса списка моделей Venice. */
sealed interface VeniceModelsResult {
    data class Loaded(val models: List<VeniceModelInfo>) : VeniceModelsResult

    /** Сервер не отдал список: не админ, Venice выключен или нет сети. */
    data object Unavailable : VeniceModelsResult
}

/**
 * Список моделей Venice: приходит с СЕРВЕРА и только админу (для остальных
 * эндпоинта «нет», 404). Ключа Venice в приложении нет и быть не может; чат
 * идёт через обычный облачный запрос с закреплённой моделью.
 */
@Singleton
class VeniceAdminApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val securityManager: SecurityManager
) {
    private val lenient = Json { ignoreUnknownKeys = true }
    private val http = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun models(): VeniceModelsResult = withContext(Dispatchers.IO) {
        val token = securityManager.getAccessToken()
        if (!AccessTokenPolicy.isValid(token)) return@withContext VeniceModelsResult.Unavailable
        val request = Request.Builder()
            .url("${OmnixApiClient.BASE_URL}$MODELS_PATH")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext VeniceModelsResult.Unavailable
                val body = response.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty()
                VeniceModelsResult.Loaded(lenient.decodeFromString(VeniceModelsDto.serializer(), body).models)
            }
        } catch (_: Exception) {
            VeniceModelsResult.Unavailable
        }
    }

    private companion object {
        const val MODELS_PATH = "/v1/venice/models"
        const val CALL_TIMEOUT_SECONDS = 20L
        const val MAX_RESPONSE_BYTES = 1L * 1024 * 1024
    }
}
