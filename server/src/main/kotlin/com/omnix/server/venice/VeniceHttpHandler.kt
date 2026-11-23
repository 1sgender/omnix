package com.omnix.server.venice

import com.omnix.server.admin.AdminModelAccess
import com.omnix.server.api.ApiErrorCode
import com.omnix.server.api.ApiErrorResponse
import com.omnix.server.auth.AuthResult
import com.omnix.server.auth.Authenticator
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.HttpResponseContext
import com.omnix.server.http.OmnixApiHandler
import com.omnix.server.observability.StructuredLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID

@Serializable
data class VeniceModelDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("contextTokens") val contextTokens: Long? = null,
    @SerialName("inputUsdPerMillion") val inputUsdPerMillion: Double? = null,
    @SerialName("outputUsdPerMillion") val outputUsdPerMillion: Double? = null,
    /** private / anonymized / e2ee / unknown. */
    @SerialName("privacy") val privacy: String,
    @SerialName("uncensored") val uncensored: Boolean,
    @SerialName("beta") val beta: Boolean
)

@Serializable
data class VeniceModelsResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("models") val models: List<VeniceModelDto>,
    @SerialName("requestId") val requestId: String
)

@Serializable
data class VeniceUsageDto(
    @SerialName("requests") val requests: Long,
    @SerialName("failures") val failures: Long,
    @SerialName("inputTokens") val inputTokens: Long,
    @SerialName("outputTokens") val outputTokens: Long,
    @SerialName("costUsd") val costUsd: Double
)

@Serializable
data class VeniceLimitsError(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String
)

@Serializable
data class VeniceLimitsResponse(
    @SerialName("success") val success: Boolean = true,
    /** Наши предохранители на админские вызовы. */
    @SerialName("requestsPerMinuteLimit") val requestsPerMinuteLimit: Int,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int,
    /** Расход с запуска сервера (в памяти). */
    @SerialName("usageSinceStart") val usageSinceStart: VeniceUsageDto,
    /** x-ratelimit-* из последнего ответа Venice; пусто, пока Venice не вызывали. */
    @SerialName("lastRateLimitHeaders") val lastRateLimitHeaders: Map<String, String>,
    /** Ответ GET /api_keys/rate_limits как есть (лимиты, баланс); null при ошибке. */
    @SerialName("upstream") val upstream: JsonElement? = null,
    @SerialName("upstreamError") val upstreamError: VeniceLimitsError? = null,
    @SerialName("requestId") val requestId: String
)

/**
 * Админ-эндпоинты Venice (`/v1/venice/…`). Строго только для админа.
 *
 * Для всех остальных эти пути не существуют: без токена, с чужим токеном, с
 * тарифом Pro или omnix, с отозванной лицензией ответ тот же, что у любого
 * неизвестного пути (handle возвращает null и работает общий 404). Админ,
 * как и в тесте моделей, определяется на сервере по `ADMIN_ACCOUNT_IDS`
 * ([AdminModelAccess.isAdmin]); клиент назначить себя админом не может.
 *
 *  - GET /v1/venice/models: текстовые модели (id, контекст, цена, privacy);
 *  - GET /v1/venice/limits: лимиты и баланс Venice + наши предохранители + расход.
 *
 * Чат Venice отдельного эндпоинта не имеет: админ закрепляет модель
 * (`adminProvider = "venice:<id>"`) в обычном /v1/ai/execute, а AiRouter
 * вызывает [VeniceService] только после той же админ-проверки.
 */
class VeniceHttpHandler(
    private val authenticator: Authenticator,
    private val adminAccess: AdminModelAccess,
    private val service: VeniceService,
    private val logger: StructuredLogger,
    private val json: Json
) {
    suspend fun handle(request: HttpRequestContext): HttpResponseContext? {
        if (!request.path.startsWith(PATH_PREFIX)) return null
        // Дальше любой «не админ» (нет токена, неверный токен, не админ) = как неизвестный путь.
        val client = (
            authenticator.authenticate(
                request.authorizationHeader,
                request.header(OmnixApiHandler.HEADER_DEVICE_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ) as? AuthResult.Success
            )?.client ?: return null
        if (!adminAccess.isAdmin(client.accountId)) {
            logger.warn("venice endpoint hidden from non-admin", "path" to request.path)
            return null
        }
        if (request.method != "GET") return null
        val requestId = UUID.randomUUID().toString()
        if (!service.enabled) return failure(ApiErrorCode.VENICE_UNAVAILABLE, requestId, "Venice выключен: на сервере не задан VENICE_API_KEY")

        return when (request.path) {
            PATH_MODELS -> models(requestId)
            PATH_LIMITS -> limits(requestId)
            else -> null
        }
    }

    private suspend fun models(requestId: String): HttpResponseContext = when (val r = service.models()) {
        is VeniceResult.Success -> ok(
            VeniceModelsResponse.serializer(),
            VeniceModelsResponse(
                models = r.value.map {
                    VeniceModelDto(
                        it.id, it.name, it.contextTokens, it.inputUsdPerMillion, it.outputUsdPerMillion,
                        it.privacy, it.uncensored, it.beta
                    )
                },
                requestId = requestId
            )
        )
        is VeniceResult.Failure -> failure(ApiErrorCode.VENICE_UNAVAILABLE, requestId, describe(r))
    }

    private suspend fun limits(requestId: String): HttpResponseContext {
        val upstream = service.rateLimits()
        val usage = service.usage()
        val body = VeniceLimitsResponse(
            requestsPerMinuteLimit = service.config.requestsPerMinute,
            maxOutputTokens = service.config.maxOutputTokens,
            usageSinceStart = VeniceUsageDto(
                usage.requests, usage.failures, usage.inputTokens, usage.outputTokens, usage.costUsd
            ),
            lastRateLimitHeaders = service.lastRateLimitHeaders(),
            upstream = (upstream as? VeniceResult.Success)?.value,
            upstreamError = (upstream as? VeniceResult.Failure)?.let {
                VeniceLimitsError(
                    code = if (it.kind == VeniceFailureKind.AUTH) "VENICE_ADMIN_KEY_REQUIRED" else "VENICE_${it.kind.name}",
                    message = describe(it)
                )
            },
            requestId = requestId
        )
        return ok(VeniceLimitsResponse.serializer(), body)
    }

    private fun describe(f: VeniceResult.Failure): String = when (f.kind) {
        VeniceFailureKind.AUTH ->
            "Нужен админ-ключ Venice для просмотра лимитов (или ключ отклонён Venice)"
        VeniceFailureKind.PAYMENT -> "На аккаунте Venice не хватает средств"
        VeniceFailureKind.RATE_LIMITED -> "Venice ограничил частоту запросов, повторите позже"
        VeniceFailureKind.DISABLED -> "Venice выключен: на сервере не задан VENICE_API_KEY"
        VeniceFailureKind.TIMEOUT -> "Venice не ответил вовремя"
        VeniceFailureKind.ERROR_BUDGET -> "Слишком много неудачных запросов, пауза 30 секунд"
        else -> "Venice временно недоступен"
    }

    private fun <T> ok(serializer: kotlinx.serialization.KSerializer<T>, body: T) = HttpResponseContext(
        status = 200,
        body = json.encodeToString(serializer, body),
        headers = mapOf("Cache-Control" to "no-store", "Content-Type" to "application/json")
    )

    private fun failure(code: ApiErrorCode, requestId: String, message: String) = HttpResponseContext(
        status = code.httpStatus,
        body = json.encodeToString(ApiErrorResponse.serializer(), code.toResponse(requestId, overrideMessage = message)),
        headers = mapOf("Cache-Control" to "no-store")
    )

    companion object {
        const val PATH_PREFIX = "/v1/venice/"
        const val PATH_MODELS = "/v1/venice/models"
        const val PATH_LIMITS = "/v1/venice/limits"
    }
}
