package com.omnix.server.clip

import com.omnix.server.auth.AuthResult
import com.omnix.server.auth.Authenticator
import com.omnix.server.auth.Authorizer
import com.omnix.server.auth.AuthenticatedClient
import com.omnix.server.auth.Permission
import com.omnix.server.api.ApiErrorCode
import com.omnix.server.api.ClipModelDto
import com.omnix.server.admin.AdminAuditLog
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.HttpResponseContext
import com.omnix.server.ratelimit.RateLimitDecision
import com.omnix.server.ratelimit.RateLimiter
import com.omnix.server.config.ValidationConfig
import com.omnix.server.observability.StructuredLogger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/* ------------------------------------------------------------------ DTO */

@Serializable
private data class ClipProvisionRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
private data class ClipProvisionResponse(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("status") val status: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipChallengeRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
private data class ClipChallengeResponse(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("issued_at_ms") val issuedAtMs: Long,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipAttestRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("signature") val signature: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
private data class ClipAttestResponse(
    @SerialName("valid") val valid: Boolean,
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("status") val status: String,
    @SerialName("bound_now") val boundNow: Boolean,
    @SerialName("public_key") val publicKey: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipRevokeRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("reason") val reason: String = "",
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
private data class ClipPairBindRequest(
    @SerialName("bt_address") val btAddress: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("request_id") val requestId: String? = null
)

/** Поля лицензии — те же, что у redeem-ответа: клиент разбирает их одинаково. */
@Serializable
private data class ClipPairBindResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("plan_id") val planId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("billing_status") val billingStatus: String,
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("model") val model: ClipModelDto? = null,
    @SerialName("bound_now") val boundNow: Boolean,
    /** «7 дней Pro»: конец пробного периода, если эта привязка его выдала. */
    @SerialName("trial_expires_at") val trialExpiresAt: String? = null,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipPairAddressRequest(
    @SerialName("bt_address") val btAddress: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
private data class ClipPairStatusResponse(
    @SerialName("state") val state: String,
    @SerialName("clip_serial") val clipSerial: String? = null,
    @SerialName("model") val model: ClipModelDto? = null,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipPairReleaseRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
private data class ClipModelsResponse(
    @SerialName("models") val models: List<ClipModelDto>
)

@Serializable
private data class ClipOperatorRegisterRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("bt_address") val btAddress: String,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("bt_name") val btName: String? = null,
    @SerialName("request_id") val requestId: String? = null
)

/* --------------------------------------------------------------- handler */

/**
 * OMNIX Clip: криптографическая привязка устройства (V008).
 *
 * Телефон не доверяет имени/MAC — только подписи challenge зарегистрированным
 * ключом. Provision/revoke — manufacturing/support (ADMIN), challenge/attest —
 * аутентифицированный аккаунт (первый attest привязывает клип к аккаунту).
 */
class ClipHttpHandler(
    private val authenticator: Authenticator,
    private val authorizer: Authorizer,
    private val attestationService: ClipAttestationService,
    private val rateLimiter: RateLimiter,
    private val validation: ValidationConfig,
    private val logger: StructuredLogger,
    private val json: Json,
    /** V012: реестр пар по BT-адресу; null — маршруты пар отключены (старые тесты). */
    private val pairRegistry: ClipPairRegistry? = null,
    /** Аккаунты-админы (ADMIN_ACCOUNT_IDS): режим оператора в приложении. */
    private val isOperatorAccount: (UUID) -> Boolean = { false },
    /** Аудит действий оператора (admin_audit_log). */
    private val adminAudit: AdminAuditLog? = null
) {
    companion object {
        const val PATH_PROVISION = "/v1/admin/clips/provision"
        const val PATH_REVOKE = "/v1/admin/clips/revoke"
        const val PATH_CHALLENGE = "/v1/clip/challenge"
        const val PATH_ATTEST = "/v1/clip/attest"
        const val PATH_PAIR_BIND = "/v1/clip/pair/bind"
        const val PATH_PAIR_STATUS = "/v1/clip/pair/status"
        const val PATH_PAIR_RELEASE = "/v1/clip/pair/release"
        const val PATH_MODELS = "/v1/clip/models"
        const val PATH_OPERATOR_REGISTER = "/v1/clip/operator/register"

        val HANDLED_PATHS = setOf(
            PATH_PROVISION, PATH_REVOKE, PATH_CHALLENGE, PATH_ATTEST,
            PATH_PAIR_BIND, PATH_PAIR_STATUS, PATH_PAIR_RELEASE, PATH_MODELS, PATH_OPERATOR_REGISTER
        )

        /** Каталог моделей меняется редко: клиент и прокси кэшируют его. */
        private const val MODELS_CACHE = "public, max-age=600"
    }

    fun handle(request: HttpRequestContext): HttpResponseContext? {
        if (request.path !in HANDLED_PATHS) return null
        val fallbackRequestId = UUID.randomUUID().toString()
        if (request.contentLength > validation.maxBodyBytes ||
            request.body.toByteArray(StandardCharsets.UTF_8).size > validation.maxBodyBytes
        ) {
            return error(ApiErrorCode.PAYLOAD_TOO_LARGE, fallbackRequestId)
        }
        return try {
            when (request.path) {
                PATH_PROVISION -> provision(request, fallbackRequestId)
                PATH_REVOKE -> revoke(request, fallbackRequestId)
                PATH_CHALLENGE -> challenge(request, fallbackRequestId)
                PATH_ATTEST -> attest(request, fallbackRequestId)
                else -> pairRegistry?.let { registry -> handlePair(registry, request, fallbackRequestId) }
            }
        } catch (failure: Exception) {
            logger.error(
                "clip request failed",
                "path" to request.path,
                "requestId" to fallbackRequestId,
                "error" to failure.javaClass.simpleName
            )
            error(ApiErrorCode.INTERNAL_ERROR, fallbackRequestId)
        }
    }

    private fun provision(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        if (!authorizer.isAllowed(client, Permission.MANAGE_LICENSES)) {
            return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        }
        rateLimit("clip-admin:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipProvisionRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        return when (val outcome = attestationService.provision(parsed.clipSerial, parsed.publicKey)) {
            is ClipProvisionOutcome.Created -> HttpResponseContext(
                201,
                json.encodeToString(
                    ClipProvisionResponse.serializer(),
                    ClipProvisionResponse(
                        clipSerial = outcome.clip.clipSerial,
                        status = outcome.clip.status.name,
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
            ClipProvisionOutcome.AlreadyExists ->
                error(ApiErrorCode.CLIP_ALREADY_PROVISIONED, requestId)
            ClipProvisionOutcome.InvalidPublicKey ->
                error(ApiErrorCode.INVALID_REQUEST, requestId)
        }
    }

    private fun revoke(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        if (!authorizer.isAllowed(client, Permission.MANAGE_LICENSES)) {
            return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        }
        rateLimit("clip-admin:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipRevokeRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        return if (attestationService.revoke(parsed.clipSerial)) {
            HttpResponseContext(
                200,
                json.encodeToString(
                    ClipProvisionResponse.serializer(),
                    ClipProvisionResponse(
                        clipSerial = parsed.clipSerial.trim(),
                        status = ClipDeviceStatus.REVOKED.name,
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
        } else {
            error(ApiErrorCode.CLIP_UNKNOWN, requestId)
        }
    }

    private fun challenge(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        rateLimit("clip-challenge:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipChallengeRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        return when (val outcome = attestationService.createChallenge(parsed.clipSerial)) {
            is ClipChallengeOutcome.Issued -> HttpResponseContext(
                200,
                json.encodeToString(
                    ClipChallengeResponse.serializer(),
                    ClipChallengeResponse(
                        clipSerial = outcome.challenge.clipSerial,
                        challengeId = outcome.challenge.challengeId.toString(),
                        nonce = Base64.getEncoder().encodeToString(outcome.challenge.nonce),
                        issuedAtMs = outcome.challenge.issuedAtMs,
                        expiresAt = outcome.challenge.expiresAt.toString(),
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
            ClipChallengeOutcome.UnknownClip -> error(ApiErrorCode.CLIP_UNKNOWN, requestId)
            ClipChallengeOutcome.ClipRevoked -> error(ApiErrorCode.CLIP_REVOKED, requestId)
        }
    }

    private fun attest(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        val accountId = client.accountId
            ?: return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        rateLimit("clip-attest:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipAttestRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        val challengeId = runCatching { UUID.fromString(parsed.challengeId.trim()) }.getOrNull()
            ?: return error(ApiErrorCode.INVALID_REQUEST, requestId)
        return when (val outcome = attestationService.attest(parsed.clipSerial, challengeId, parsed.signature, accountId)) {
            is ClipAttestOutcome.Valid -> HttpResponseContext(
                200,
                json.encodeToString(
                    ClipAttestResponse.serializer(),
                    ClipAttestResponse(
                        valid = true,
                        clipSerial = outcome.clipSerial,
                        status = outcome.status.name,
                        boundNow = outcome.boundNow,
                        publicKey = outcome.publicKeyBase64,
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
            ClipAttestOutcome.UnknownClip -> error(ApiErrorCode.CLIP_UNKNOWN, requestId)
            ClipAttestOutcome.ClipRevoked -> error(ApiErrorCode.CLIP_REVOKED, requestId)
            ClipAttestOutcome.ChallengeInvalid -> error(ApiErrorCode.CLIP_CHALLENGE_INVALID, requestId)
            ClipAttestOutcome.BadSignature -> error(ApiErrorCode.CLIP_BAD_SIGNATURE, requestId)
            ClipAttestOutcome.OwnerMismatch -> error(ApiErrorCode.CLIP_OWNER_MISMATCH, requestId)
        }
    }

    /* ------------------------------------------------- V012: пары по MAC */

    private fun handlePair(
        registry: ClipPairRegistry,
        request: HttpRequestContext,
        fallbackRequestId: String
    ): HttpResponseContext? = when (request.path) {
        PATH_PAIR_BIND -> pairBind(registry, request, fallbackRequestId)
        PATH_PAIR_STATUS -> pairStatus(registry, request, fallbackRequestId)
        PATH_PAIR_RELEASE -> pairRelease(registry, request, fallbackRequestId)
        PATH_MODELS -> models(registry, request, fallbackRequestId)
        PATH_OPERATOR_REGISTER -> operatorRegister(registry, request, fallbackRequestId)
        else -> null
    }

    /**
     * Привязка подключённой пары без кода. Без токена — новый покупатель
     * (аккаунт создаётся, как при redeem); с токеном — пара добавляется к
     * аккаунту вызывающего. Невалидный токен — 401, а не «новый аккаунт».
     */
    private fun pairBind(
        registry: ClipPairRegistry,
        request: HttpRequestContext,
        fallbackRequestId: String
    ): HttpResponseContext {
        val client = if (request.authorizationHeader.isNullOrBlank()) {
            null
        } else {
            authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        }
        if (client != null && client.accountId == null) return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        val limiterKey = client?.let { "clip-bind:${it.clientId}" } ?: "clip-bind-ip:${request.remoteAddress}"
        rateLimit(limiterKey, fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipPairBindRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        val outcome = registry.bind(
            rawAddress = parsed.btAddress,
            deviceId = parsed.deviceId,
            accountId = client?.accountId,
            requestId = requestId,
            remoteAddress = request.remoteAddress
        )
        return when (outcome) {
            is ClipPairBindOutcome.Bound -> {
                logger.info(
                    "clip pair bound",
                    "requestId" to requestId,
                    "clipSerial" to outcome.clipSerial,
                    "boundNow" to outcome.boundNow.toString(),
                    "licenseGranted" to outcome.licenseGranted.toString(),
                    "trialGranted" to (outcome.trialExpiresAt != null).toString()
                )
                HttpResponseContext(
                    200,
                    json.encodeToString(
                        ClipPairBindResponse.serializer(),
                        ClipPairBindResponse(
                            accessToken = outcome.accessToken,
                            planId = outcome.planId,
                            productId = outcome.productId,
                            startsAt = outcome.startsAt.toString(),
                            expiresAt = outcome.expiresAt.toString(),
                            billingStatus = outcome.billingStatus.name,
                            clipSerial = outcome.clipSerial,
                            model = outcome.model?.toModelDto(),
                            boundNow = outcome.boundNow,
                            trialExpiresAt = outcome.trialExpiresAt?.toString(),
                            requestId = requestId
                        )
                    ),
                    headers = mapOf("Cache-Control" to "no-store")
                )
            }
            else -> {
                logger.info(
                    "clip pair bind rejected",
                    "requestId" to requestId,
                    "outcome" to outcome.javaClass.simpleName
                )
                error(bindErrorCode(outcome), requestId)
            }
        }
    }

    private fun bindErrorCode(outcome: ClipPairBindOutcome): ApiErrorCode = when (outcome) {
        is ClipPairBindOutcome.Bound -> ApiErrorCode.INTERNAL_ERROR
        ClipPairBindOutcome.InvalidRequest -> ApiErrorCode.INVALID_REQUEST
        ClipPairBindOutcome.UnknownPair -> ApiErrorCode.CLIP_UNKNOWN
        ClipPairBindOutcome.Revoked -> ApiErrorCode.CLIP_REVOKED
        ClipPairBindOutcome.OwnedByAnotherAccount -> ApiErrorCode.CLIP_OWNER_MISMATCH
        ClipPairBindOutcome.PairLimitReached -> ApiErrorCode.CLIP_PAIR_LIMIT
        ClipPairBindOutcome.InvalidPlan -> ApiErrorCode.LICENSE_INVALID_STATE
        ClipPairBindOutcome.AccountDisabled -> ApiErrorCode.LICENSE_REVOKED
        ClipPairBindOutcome.NoLicenseForDevice -> ApiErrorCode.LICENSE_WRONG_DEVICE
    }

    /** Подключённая пара: своя / свободная (можно добавить) / недоступна / неизвестна. */
    private fun pairStatus(
        registry: ClipPairRegistry,
        request: HttpRequestContext,
        fallbackRequestId: String
    ): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        val accountId = client.accountId ?: return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        rateLimit("clip-pair:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipPairAddressRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        val view = registry.statusFor(accountId, parsed.btAddress)
            ?: return error(ApiErrorCode.INVALID_REQUEST, requestId)
        return HttpResponseContext(
            200,
            json.encodeToString(
                ClipPairStatusResponse.serializer(),
                ClipPairStatusResponse(
                    state = view.state.name,
                    clipSerial = view.clipSerial,
                    model = view.model?.toModelDto(),
                    requestId = requestId
                )
            ),
            headers = mapOf("Cache-Control" to "no-store")
        )
    }

    /** Владелец сам отвязывает свою пару (→ RELEASED, можно передать). */
    private fun pairRelease(
        registry: ClipPairRegistry,
        request: HttpRequestContext,
        fallbackRequestId: String
    ): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        val accountId = client.accountId ?: return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        rateLimit("clip-pair:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipPairReleaseRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        return if (registry.releaseByOwner(accountId, parsed.clipSerial, requestId, request.remoteAddress)) {
            HttpResponseContext(
                200,
                json.encodeToString(
                    ClipProvisionResponse.serializer(),
                    ClipProvisionResponse(
                        clipSerial = parsed.clipSerial.trim(),
                        status = ClipDeviceStatus.RELEASED.name,
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
        } else {
            error(ApiErrorCode.CLIP_UNKNOWN, requestId)
        }
    }

    /** Публичный каталог: только ACTIVE-модели и только публичные поля. */
    private fun models(
        registry: ClipPairRegistry,
        request: HttpRequestContext,
        fallbackRequestId: String
    ): HttpResponseContext {
        rateLimit("clip-models-ip:${request.remoteAddress}", fallbackRequestId)?.let { return it }
        val models = registry.listModels(includeHidden = false).map { it.toPublic().toModelDto() }
        return HttpResponseContext(
            200,
            json.encodeToString(ClipModelsResponse.serializer(), ClipModelsResponse(models)),
            headers = mapOf("Cache-Control" to MODELS_CACHE)
        )
    }

    /**
     * Режим оператора в приложении: владелец при упаковке регистрирует пару,
     * подключённую к его телефону. Только аккаунты из ADMIN_ACCOUNT_IDS.
     */
    private fun operatorRegister(
        registry: ClipPairRegistry,
        request: HttpRequestContext,
        fallbackRequestId: String
    ): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        val accountId = client.accountId ?: return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        if (!isOperatorAccount(accountId)) return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        rateLimit("clip-admin:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipOperatorRegisterRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        val outcome = registry.register(
            ClipPairRegistration(
                clipSerial = parsed.clipSerial,
                btAddress = parsed.btAddress,
                modelId = parsed.modelId,
                btName = parsed.btName
            )
        )
        return when (outcome) {
            is ClipRegisterOutcome.Registered -> {
                adminAudit?.append(
                    actor = "account:$accountId",
                    action = "clip.register",
                    entityType = "CLIP",
                    entityId = outcome.entry.id.toString(),
                    oldValue = "{}",
                    newValue = """{"clip_serial":"${outcome.entry.clipSerial}","bt_address":"${BtAddress.mask(outcome.entry.btAddress)}","source":"app_operator"}""",
                    remoteAddress = request.remoteAddress,
                    sessionId = null,
                    requestId = requestId
                )
                HttpResponseContext(
                    201,
                    json.encodeToString(
                        ClipProvisionResponse.serializer(),
                        ClipProvisionResponse(
                            clipSerial = outcome.entry.clipSerial,
                            status = outcome.entry.status.name,
                            requestId = requestId
                        )
                    ),
                    headers = mapOf("Cache-Control" to "no-store")
                )
            }
            is ClipRegisterOutcome.Invalid,
            ClipRegisterOutcome.UnknownModel,
            ClipRegisterOutcome.UnknownPlan -> error(ApiErrorCode.INVALID_REQUEST, requestId)
            ClipRegisterOutcome.DuplicateSerial,
            ClipRegisterOutcome.DuplicateAddress -> error(ApiErrorCode.CLIP_ALREADY_REGISTERED, requestId)
        }
    }

    /* ------------------------------------------------------------- helpers */

    private fun authenticate(request: HttpRequestContext, requestId: String): AuthenticatedClient? =
        when (val result = authenticator.authenticate(request.authorizationHeader)) {
            is AuthResult.Success -> result.client
            AuthResult.InvalidCredentials, AuthResult.MissingCredentials -> {
                logger.warn(
                    "clip unauthorized",
                    "path" to request.path,
                    "requestId" to requestId
                )
                null
            }
        }

    private fun unauthorized(requestId: String) = error(ApiErrorCode.UNAUTHORIZED, requestId)

    private fun rateLimit(key: String, requestId: String): HttpResponseContext? =
        when (val decision = rateLimiter.check(key)) {
            RateLimitDecision.Allowed -> null
            is RateLimitDecision.Limited -> error(
                ApiErrorCode.RATE_LIMITED,
                requestId,
                headers = mapOf("Retry-After" to decision.retryAfterSeconds.toString())
            )
        }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, body) }.getOrNull()

    private fun validRequestId(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl) }

    private fun error(
        code: ApiErrorCode,
        requestId: String,
        headers: Map<String, String> = emptyMap()
    ) = HttpResponseContext(
        code.httpStatus,
        json.encodeToString(
            com.omnix.server.api.ApiErrorResponse.serializer(),
            code.toResponse(requestId)
        ),
        headers
    )
}
