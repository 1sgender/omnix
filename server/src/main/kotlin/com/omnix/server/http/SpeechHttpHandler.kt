package com.omnix.server.http

import com.omnix.server.api.ApiErrorCode
import com.omnix.server.auth.AuthResult
import com.omnix.server.auth.Authenticator
import com.omnix.server.auth.Authorizer
import com.omnix.server.auth.Permission
import com.omnix.server.config.ValidationConfig
import com.omnix.server.observability.Metrics
import com.omnix.server.observability.StructuredLogger
import com.omnix.server.ratelimit.RateLimitDecision
import com.omnix.server.ratelimit.RateLimiter
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * POST /v1/stt/transcribe — отдельно от [OmnixApiHandler], где тело
 * /v1/ai/execute разбирается ровно один раз (инвариант M-01).
 */
class SpeechHttpHandler(
    private val authenticator: Authenticator,
    private val authorizer: Authorizer,
    private val rateLimiter: RateLimiter,
    private val validation: ValidationConfig,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    private val json: Json,
    private val entitlementChecker: (com.omnix.server.auth.AuthenticatedClient) -> Boolean,
    private val speechToText: com.omnix.server.stt.SpeechToText?
) {
    /**
     * Распознавание одной фразы (переводчик «по наушнику на каждого»).
     * Тот же конвейер, что у /v1/ai/execute: размер → аутентификация →
     * авторизация → тариф → лимит частоты; затем Whisper. Аудио нигде не
     * сохраняется и не логируется — только размер.
     */
    suspend fun handle(request: HttpRequestContext): HttpResponseContext {
        val bodySize = request.body.toByteArray(Charsets.UTF_8).size.toLong()
        if (request.contentLength > validation.maxSttBodyBytes || bodySize > validation.maxSttBodyBytes) {
            return error(ApiErrorCode.PAYLOAD_TOO_LARGE, newRequestId())
        }
        val parsed = runCatching { json.decodeFromString(com.omnix.server.api.SttRequest.serializer(), request.body) }
            .getOrNull()
        val requestId = parsed?.requestId?.takeIf { it.isNotBlank() && it.length <= 64 } ?: newRequestId()

        val client = when (val auth = authenticator.authenticate(request.authorizationHeader, request.deviceIdHeader())) {
            is AuthResult.Success -> auth.client
            AuthResult.MissingCredentials, AuthResult.InvalidCredentials -> {
                metrics.recordUnauthorized()
                logger.warn("unauthorized request", "requestId" to requestId, "path" to request.path)
                return error(ApiErrorCode.UNAUTHORIZED, requestId)
            }
        }
        if (!authorizer.isAllowed(client, Permission.EXECUTE_AI)) {
            return error(ApiErrorCode.FORBIDDEN, requestId)
        }
        if (client.tier !in setOf(
                com.omnix.server.auth.ClientTier.ADMIN,
                com.omnix.server.auth.ClientTier.INTERNAL
            ) && !entitlementChecker(client)
        ) {
            return error(ApiErrorCode.PAYMENT_REQUIRED, requestId)
        }
        when (val decision = rateLimiter.check(client.clientId)) {
            is RateLimitDecision.Limited -> {
                metrics.recordRateLimited()
                return error(
                    ApiErrorCode.RATE_LIMITED,
                    requestId,
                    headers = mapOf("Retry-After" to decision.retryAfterSeconds.toString())
                )
            }
            RateLimitDecision.Allowed -> Unit
        }

        val sttRequest = parsed ?: return error(ApiErrorCode.INVALID_REQUEST, requestId)
        val wav = runCatching { java.util.Base64.getDecoder().decode(sttRequest.audioBase64) }.getOrNull()
        if (wav == null || !com.omnix.server.stt.WavCheck.isPlausible(wav)) {
            logger.warn("malformed stt request", "requestId" to requestId)
            return error(ApiErrorCode.INVALID_REQUEST, requestId)
        }
        val stt = speechToText ?: return error(ApiErrorCode.PROVIDER_UNAVAILABLE, requestId)

        logger.info(
            "stt request accepted",
            "requestId" to requestId,
            "clientId" to client.clientId,
            "audioBytes" to wav.size.toString()
        )
        return when (val outcome = stt.transcribe(wav, sttRequest.languageHint)) {
            is com.omnix.server.stt.SttOutcome.Success -> HttpResponseContext(
                status = 200,
                body = json.encodeToString(
                    com.omnix.server.api.SttResponse.serializer(),
                    com.omnix.server.api.SttResponse(text = outcome.text, language = outcome.language, requestId = requestId)
                )
            )
            is com.omnix.server.stt.SttOutcome.Failure -> {
                logger.warn("stt failed", "requestId" to requestId, "kind" to outcome.kind.name)
                error(
                    when (outcome.kind) {
                        com.omnix.server.provider.ProviderFailureKind.TIMEOUT -> ApiErrorCode.PROVIDER_TIMEOUT
                        com.omnix.server.provider.ProviderFailureKind.RATE_LIMITED,
                        com.omnix.server.provider.ProviderFailureKind.CONNECTION,
                        com.omnix.server.provider.ProviderFailureKind.SERVER_ERROR -> ApiErrorCode.PROVIDER_UNAVAILABLE
                        else -> ApiErrorCode.PROVIDER_ERROR
                    },
                    requestId
                )
            }
        }
    }


    private fun HttpRequestContext.deviceIdHeader(): String? =
        headers.entries
            .firstOrNull { it.key.equals(OmnixApiHandler.HEADER_DEVICE_ID, ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }

    private fun newRequestId(): String = UUID.randomUUID().toString()

    private fun error(
        code: ApiErrorCode,
        requestId: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponseContext = HttpResponseContext(
        status = code.httpStatus,
        body = json.encodeToString(
            com.omnix.server.api.ApiErrorResponse.serializer(),
            code.toResponse(requestId)
        ),
        headers = mapOf("Cache-Control" to "no-store") + headers
    )
}
