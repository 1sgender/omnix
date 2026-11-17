package com.omnix.server.http

import com.omnix.server.api.ApiErrorCode
import com.omnix.server.api.ApiPrivacyLevel
import com.omnix.server.api.SearchRequest
import com.omnix.server.api.SearchResponse
import com.omnix.server.api.SearchResultDto
import com.omnix.server.auth.AuthResult
import com.omnix.server.auth.AuthenticatedClient
import com.omnix.server.auth.Authenticator
import com.omnix.server.auth.Authorizer
import com.omnix.server.auth.ClientTier
import com.omnix.server.auth.Permission
import com.omnix.server.config.ValidationConfig
import com.omnix.server.observability.Metrics
import com.omnix.server.observability.StructuredLogger
import com.omnix.server.privacy.PrivacyContent
import com.omnix.server.privacy.PromptPrivacyClassifier
import com.omnix.server.privacy.ServerPrivacyClassifier
import com.omnix.server.ratelimit.RateLimitDecision
import com.omnix.server.ratelimit.RateLimiter
import com.omnix.server.license.PlanCatalog
import com.omnix.server.search.SearchDailyLimiter
import com.omnix.server.search.SearchOutcome
import com.omnix.server.search.SearchProvider
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * POST /v1/search: веб-поиск для телефона, которому DuckDuckGo и Википедия
 * часто недоступны. Конвейер как у /v1/ai/execute: размер, аутентификация,
 * авторизация, тариф, лимит частоты; затем приватный классификатор (запрос с
 * секретом наружу не уходит) и провайдер поиска. Текст запроса в логи не пишем.
 */
class SearchHttpHandler(
    private val authenticator: Authenticator,
    private val authorizer: Authorizer,
    private val rateLimiter: RateLimiter,
    private val validation: ValidationConfig,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    private val json: Json,
    private val entitlementChecker: (AuthenticatedClient) -> Boolean,
    private val searchProvider: SearchProvider?,
    private val privacyClassifier: ServerPrivacyClassifier = PromptPrivacyClassifier,
    private val dailyLimiter: SearchDailyLimiter = SearchDailyLimiter()
) {
    suspend fun handle(request: HttpRequestContext): HttpResponseContext {
        val bodySize = request.body.toByteArray(Charsets.UTF_8).size.toLong()
        if (request.contentLength > validation.maxBodyBytes || bodySize > validation.maxBodyBytes) {
            return error(ApiErrorCode.PAYLOAD_TOO_LARGE, newRequestId())
        }
        val parsed = runCatching { json.decodeFromString(SearchRequest.serializer(), request.body) }.getOrNull()
        val requestId = parsed?.requestId?.takeIf { it.isNotBlank() && it.length <= MAX_REQUEST_ID } ?: newRequestId()

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
        if (client.tier !in setOf(ClientTier.ADMIN, ClientTier.INTERNAL) && !entitlementChecker(client)) {
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

        // Дневной лимит поиска по тарифу считаем здесь: правкой приложения его не обойти.
        if (client.tier !in setOf(ClientTier.ADMIN, ClientTier.INTERNAL)) {
            val limit = PlanCatalog.forPlanId(client.planId ?: PlanCatalog.FREE).dailyWebSearch
            val decision = dailyLimiter.tryAcquire(client.clientId, limit)
            if (decision is SearchDailyLimiter.Decision.Exceeded) {
                return error(
                    ApiErrorCode.QUOTA_EXCEEDED,
                    requestId,
                    headers = mapOf("Retry-After" to decision.retryAfterSeconds.toString())
                )
            }
        }

        val searchRequest = parsed ?: return error(ApiErrorCode.INVALID_REQUEST, requestId)
        val query = searchRequest.query.trim()
        if (query.isEmpty() || query.length > MAX_QUERY_CHARS) {
            logger.warn("malformed search request", "requestId" to requestId)
            return error(ApiErrorCode.INVALID_REQUEST, requestId)
        }
        val lang = searchRequest.lang?.trim()?.takeIf { it.length in 2..MAX_LANG_CHARS && it.all(Char::isLetter) }

        // Секрет в запросе не должен уйти стороннему поисковику.
        val privacy = PromptPrivacyClassifier.classifySafely(PrivacyContent(query), privacyClassifier)
        if (privacy.level != ApiPrivacyLevel.NORMAL) {
            logger.warn("search blocked by privacy", "requestId" to requestId, "level" to privacy.level.name)
            return error(ApiErrorCode.PRIVACY_POLICY_VIOLATION, requestId)
        }

        val provider = searchProvider ?: return error(ApiErrorCode.SEARCH_UNAVAILABLE, requestId)
        logger.info(
            "search request accepted",
            "requestId" to requestId,
            "clientId" to client.clientId,
            "queryChars" to query.length.toString()
        )
        return when (val outcome = provider.search(query, lang)) {
            is SearchOutcome.Success -> HttpResponseContext(
                status = 200,
                body = json.encodeToString(
                    SearchResponse.serializer(),
                    SearchResponse(
                        results = outcome.results.map { SearchResultDto(it.title, it.url, it.snippet) },
                        answer = outcome.answer?.takeIf { it.isNotBlank() },
                        requestId = requestId
                    )
                )
            )
            is SearchOutcome.Failure -> {
                logger.warn("search failed", "requestId" to requestId, "kind" to outcome.kind.name)
                error(ApiErrorCode.SEARCH_UNAVAILABLE, requestId)
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
            code.toResponse(requestId, retryAfterSeconds = headers["Retry-After"]?.toIntOrNull())
        ),
        headers = mapOf("Cache-Control" to "no-store") + headers
    )

    companion object {
        const val MAX_QUERY_CHARS = 300
        private const val MAX_LANG_CHARS = 8
        private const val MAX_REQUEST_ID = 64
    }
}
