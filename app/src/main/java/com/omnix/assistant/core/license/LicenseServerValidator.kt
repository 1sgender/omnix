package com.omnix.assistant.core.license

import androidx.annotation.VisibleForTesting
import com.omnix.assistant.core.constants.AppConstants
import com.omnix.assistant.core.network.readUtf8Bounded
import com.omnix.assistant.core.security.AccessTokenPolicy
import com.omnix.assistant.core.security.SecurityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ServerLicenseRecord(
    val accessToken: String? = null,
    val planId: String,
    val productId: String,
    val startsAt: Instant,
    val expiresAt: Instant,
    val billingStatus: String,
    val entitlements: PlanEntitlements = PlanEntitlements.FREE,
    val clipBinding: ClipBindingState = ClipBindingState.UNKNOWN
)

sealed interface ServerRedemptionResult {
    data class Success(val license: ServerLicenseRecord) : ServerRedemptionResult
    data object NotRedeemable : ServerRedemptionResult
    data object RateLimited : ServerRedemptionResult
    data object ServiceUnavailable : ServerRedemptionResult
    /** Таймаут/DNS/обрыв соединения — сервер не ответил, а не отказал. */
    data object NoConnection : ServerRedemptionResult
}

sealed interface ServerLicenseValidationResult {
    data class Valid(val license: ServerLicenseRecord) : ServerLicenseValidationResult
    data object Expired : ServerLicenseValidationResult
    data object RevokedOrDisabled : ServerLicenseValidationResult
    data object WrongDevice : ServerLicenseValidationResult
    data object PaymentRequired : ServerLicenseValidationResult
    data object Unauthorized : ServerLicenseValidationResult
    data object Invalid : ServerLicenseValidationResult
    data object RateLimited : ServerLicenseValidationResult
    data object ServiceUnavailable : ServerLicenseValidationResult
}

interface LicenseServerValidator {
    suspend fun redeem(code: String, hardwareId: String): ServerRedemptionResult
    suspend fun validate(hardwareId: String): ServerLicenseValidationResult
}

@Serializable
private data class RedeemRequest(
    @SerialName("code") val code: String,
    @SerialName("device_id") val hardwareId: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class RedeemResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("plan_id") val planId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("billing_status") val billingStatus: String
)

@Serializable
private data class ValidateRequest(
    @SerialName("device_id") val hardwareId: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ValidateResponse(
    @SerialName("valid") val valid: Boolean,
    @SerialName("plan_id") val planId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("billing_status") val billingStatus: String,
    /** null = старый сервер → FREE (fail-closed, см. ClientPlanGate). */
    @SerialName("entitlements") val entitlements: PlanEntitlements? = null,
    @SerialName("clip") val clip: ClipBindingState? = null
)

@Serializable
private data class ErrorEnvelope(@SerialName("error") val error: ErrorBody = ErrorBody())

@Serializable
private data class ErrorBody(@SerialName("code") val code: String = "")

@Singleton
class HttpLicenseServerValidator @Inject constructor(
    private val securityManager: SecurityManager
) : LicenseServerValidator {
    companion object {
        private const val MAX_RESPONSE_BYTES = 64L * 1024
        private val BASE_URL: String = AppConstants.OMNIX_LICENSE_BASE_URL
        private const val RETRY_DELAY_MS = 2_000L
    }

    // Hilt идёт через primary-конструктор; baseUrl переопределяется только в тестах.
    private var baseUrl: String = BASE_URL

    @VisibleForTesting
    constructor(securityManager: SecurityManager, baseUrl: String) : this(securityManager) {
        this.baseUrl = baseUrl
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    // Активация — редкая операция: таймауты щедрые, чтобы медленные мобильные
    // сети (рукопожатие TLS + DNS) не превращались в ложный «сервер недоступен».
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun redeem(code: String, hardwareId: String): ServerRedemptionResult =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()
            val body = json.encodeToString(RedeemRequest(code, hardwareId, requestId))
            val request = Request.Builder()
                .url("$baseUrl/v1/license/redeem")
                .post(body.toRequestBody(mediaType))
                .build()
            // Одна молчаливая повторная попытка при транспортном сбое: мобильные
            // сети рвут соединения, а redeem идемпотентен для того же устройства.
            var result = attemptRedeem(request)
            if (result == ServerRedemptionResult.NoConnection) {
                delay(RETRY_DELAY_MS)
                result = attemptRedeem(request)
            }
            result
        }

    private fun attemptRedeem(request: Request): ServerRedemptionResult {
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty()
                return when (response.code) {
                    200 -> parseRedeem(responseBody)
                    // 400 от redeem — тоже «плохой код», а не авария сервера.
                    400, 404, 409, 410 -> ServerRedemptionResult.NotRedeemable
                    429 -> ServerRedemptionResult.RateLimited
                    else -> ServerRedemptionResult.ServiceUnavailable
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // Любой транспортный сбой (таймаут/DNS/reset/TLS) — не ответ сервера.
            return ServerRedemptionResult.NoConnection
        } catch (_: Exception) {
            return ServerRedemptionResult.ServiceUnavailable
        }
    }

    override suspend fun validate(hardwareId: String): ServerLicenseValidationResult =
        withContext(Dispatchers.IO) {
            val token = securityManager.getAccessToken().trim()
            if (!AccessTokenPolicy.isValid(token)) return@withContext ServerLicenseValidationResult.Unauthorized
            val requestId = UUID.randomUUID().toString()
            val body = json.encodeToString(ValidateRequest(hardwareId, requestId))
            val request = Request.Builder()
                .url("$BASE_URL/v1/license/validate")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(mediaType))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty()
                    when (response.code) {
                        200 -> parseValidation(responseBody)
                        401 -> ServerLicenseValidationResult.Unauthorized
                        402 -> ServerLicenseValidationResult.PaymentRequired
                        403 -> when (errorCode(responseBody)) {
                            "LICENSE_WRONG_DEVICE" -> ServerLicenseValidationResult.WrongDevice
                            else -> ServerLicenseValidationResult.RevokedOrDisabled
                        }
                        404, 409 -> ServerLicenseValidationResult.Invalid
                        410 -> ServerLicenseValidationResult.Expired
                        429 -> ServerLicenseValidationResult.RateLimited
                        else -> ServerLicenseValidationResult.ServiceUnavailable
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ServerLicenseValidationResult.ServiceUnavailable
            }
        }

    private fun parseRedeem(body: String): ServerRedemptionResult = runCatching {
        val parsed = json.decodeFromString(RedeemResponse.serializer(), body)
        if (!AccessTokenPolicy.isValid(parsed.accessToken)) error("invalid access token")
        ServerRedemptionResult.Success(
            parseRecord(
                parsed.planId, parsed.productId, parsed.startsAt, parsed.expiresAt,
                billingStatus = parsed.billingStatus, accessToken = parsed.accessToken
            )
        )
    }.getOrElse { ServerRedemptionResult.ServiceUnavailable }

    private fun parseValidation(body: String): ServerLicenseValidationResult = runCatching {
        val parsed = json.decodeFromString(ValidateResponse.serializer(), body)
        check(parsed.valid)
        ServerLicenseValidationResult.Valid(
            parseRecord(
                parsed.planId, parsed.productId, parsed.startsAt, parsed.expiresAt,
                parsed.billingStatus, null,
                parsed.entitlements ?: PlanEntitlements.FREE,
                parsed.clip ?: ClipBindingState.UNKNOWN
            )
        )
    }.getOrElse { ServerLicenseValidationResult.ServiceUnavailable }

    private fun parseRecord(
        planId: String,
        productId: String,
        startsAtRaw: String,
        expiresAtRaw: String,
        billingStatus: String,
        accessToken: String?,
        entitlements: PlanEntitlements = PlanEntitlements.FREE,
        clipBinding: ClipBindingState = ClipBindingState.UNKNOWN
    ): ServerLicenseRecord {
        require(planId.matches(Regex("[a-z0-9][a-z0-9_-]{1,63}")))
        require(productId.matches(Regex("[a-z0-9][a-z0-9_-]{1,63}")))
        val startsAt = Instant.parse(startsAtRaw)
        val expiresAt = Instant.parse(expiresAtRaw)
        require(expiresAt.isAfter(startsAt))
        require(billingStatus in setOf("GRANTED", "PAID", "CANCELED"))
        return ServerLicenseRecord(
            accessToken, planId, productId, startsAt, expiresAt, billingStatus,
            entitlements, clipBinding
        )
    }

    private fun errorCode(body: String): String = runCatching {
        json.decodeFromString(ErrorEnvelope.serializer(), body).error.code
    }.getOrDefault("")
}
