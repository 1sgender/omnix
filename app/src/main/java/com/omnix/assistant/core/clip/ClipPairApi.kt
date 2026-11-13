package com.omnix.assistant.core.clip

import com.omnix.assistant.core.constants.AppConstants
import com.omnix.assistant.core.license.ClipModelInfo
import com.omnix.assistant.core.network.readUtf8Bounded
import com.omnix.assistant.core.security.AccessTokenPolicy
import com.omnix.assistant.core.security.SecurityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Результат обращения к реестру пар OMNIX Clip (сервер V012). */
sealed interface ClipPairCallResult<out T> {
    data class Ok<T>(val value: T) : ClipPairCallResult<T>

    /** Сервер ответил отказом: код из `error.code` (CLIP_UNKNOWN, CLIP_OWNER_MISMATCH, …). */
    data class Refused(val code: String, val httpStatus: Int) : ClipPairCallResult<Nothing>

    /** Нет сети / сервер недоступен — не вердикт против пары. */
    data object Unavailable : ClipPairCallResult<Nothing>
}

@Serializable
data class ClipPairBound(
    @SerialName("access_token") val accessToken: String,
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("model") val model: ClipModelInfo? = null,
    @SerialName("bound_now") val boundNow: Boolean = true,
    /** «7 дней Pro при первом подключении»: конец пробного периода, если выдан сейчас. */
    @SerialName("trial_expires_at") val trialExpiresAt: String? = null
)

/** OWNED / AVAILABLE / UNAVAILABLE / UNKNOWN — см. сервер ClipPairState. */
@Serializable
data class ClipPairStatus(
    @SerialName("state") val state: String,
    @SerialName("clip_serial") val clipSerial: String? = null,
    @SerialName("model") val model: ClipModelInfo? = null
) {
    companion object {
        const val OWNED = "OWNED"
        const val AVAILABLE = "AVAILABLE"
    }
}

@Serializable
private data class BindRequest(
    @SerialName("bt_address") val btAddress: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class AddressRequest(
    @SerialName("bt_address") val btAddress: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ReleaseRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class OperatorRegisterRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("bt_address") val btAddress: String,
    @SerialName("bt_name") val btName: String? = null,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class SerialResponse(@SerialName("clip_serial") val clipSerial: String)

@Serializable
private data class ErrorEnvelope(@SerialName("error") val error: ErrorBody = ErrorBody())

@Serializable
private data class ErrorBody(@SerialName("code") val code: String = "")

/**
 * HTTP-клиент реестра пар: привязка подключённой пары без кода, статус
 * подключённой пары, отвязка своей пары, регистрация пары оператором.
 *
 * ВРЕМЕННО: пара опознаётся по BT-адресу; прошивка с ключом заменит это
 * подписью (ClipAttestationApi), эндпоинты реестра останутся.
 */
@Singleton
class HttpClipPairApi @Inject constructor(
    private val securityManager: SecurityManager
) {
    private val baseUrl: String = AppConstants.OMNIX_LICENSE_BASE_URL
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Привязать подключённую пару. С токеном — к своему аккаунту; без токена
     * (ещё не активирован) — сервер создаёт аккаунт, как при вводе кода.
     */
    suspend fun bind(btAddress: String, deviceId: String, withAccount: Boolean): ClipPairCallResult<ClipPairBound> {
        val body = json.encodeToString(BindRequest.serializer(), BindRequest(btAddress, deviceId, requestId()))
        return post("/v1/clip/pair/bind", body, authorized = withAccount, serializer = ClipPairBound.serializer())
    }

    suspend fun status(btAddress: String): ClipPairCallResult<ClipPairStatus> {
        val body = json.encodeToString(AddressRequest.serializer(), AddressRequest(btAddress, requestId()))
        return post("/v1/clip/pair/status", body, authorized = true, serializer = ClipPairStatus.serializer())
    }

    suspend fun release(clipSerial: String): ClipPairCallResult<String> {
        val body = json.encodeToString(ReleaseRequest.serializer(), ReleaseRequest(clipSerial, requestId()))
        return when (val result = post("/v1/clip/pair/release", body, authorized = true, serializer = SerialResponse.serializer())) {
            is ClipPairCallResult.Ok -> ClipPairCallResult.Ok(result.value.clipSerial)
            is ClipPairCallResult.Refused -> result
            ClipPairCallResult.Unavailable -> ClipPairCallResult.Unavailable
        }
    }

    /** Режим оператора (аккаунт-админ): зарегистрировать подключённую пару. */
    suspend fun operatorRegister(clipSerial: String, btAddress: String, btName: String?): ClipPairCallResult<String> {
        val body = json.encodeToString(
            OperatorRegisterRequest.serializer(),
            OperatorRegisterRequest(clipSerial, btAddress, btName, requestId())
        )
        return when (val result = post("/v1/clip/operator/register", body, authorized = true, serializer = SerialResponse.serializer())) {
            is ClipPairCallResult.Ok -> ClipPairCallResult.Ok(result.value.clipSerial)
            is ClipPairCallResult.Refused -> result
            ClipPairCallResult.Unavailable -> ClipPairCallResult.Unavailable
        }
    }

    private suspend fun <T> post(
        path: String,
        body: String,
        authorized: Boolean,
        serializer: KSerializer<T>
    ): ClipPairCallResult<T> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(baseUrl + path).post(body.toRequestBody(mediaType))
        if (authorized) {
            val token = securityManager.getAccessToken().trim()
            if (!AccessTokenPolicy.isValid(token)) return@withContext ClipPairCallResult.Refused("UNAUTHORIZED", 401)
            builder.header("Authorization", "Bearer $token")
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty()
                when {
                    response.isSuccessful ->
                        runCatching { ClipPairCallResult.Ok(json.decodeFromString(serializer, text)) }
                            .getOrElse { ClipPairCallResult.Unavailable }
                    response.code == 429 || response.code >= 500 -> ClipPairCallResult.Unavailable
                    else -> ClipPairCallResult.Refused(errorCode(text), response.code)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ClipPairCallResult.Unavailable
        }
    }

    private fun errorCode(body: String): String = runCatching {
        json.decodeFromString(ErrorEnvelope.serializer(), body).error.code
    }.getOrDefault("")

    private fun requestId(): String = UUID.randomUUID().toString()

    private companion object {
        const val MAX_RESPONSE_BYTES = 64L * 1024
    }
}
