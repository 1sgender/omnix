package com.omnix.server.venice

import com.omnix.server.observability.LogSanitizer
import com.omnix.server.observability.StructuredLogger
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.HttpTransportResponse
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.provider.TransportException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** Текстовая модель Venice для админа. Цены: USD за 1M токенов. */
data class VeniceModel(
    val id: String,
    val name: String,
    val contextTokens: Long?,
    val inputUsdPerMillion: Double?,
    val outputUsdPerMillion: Double?,
    /** private / anonymized / e2ee / unknown. */
    val privacy: String,
    /** Модель без цензуры (по id: uncensored, abliterated). */
    val uncensored: Boolean,
    val beta: Boolean
)

data class VeniceMessage(val role: String, val content: String)

data class VeniceChatReply(
    val text: String,
    val model: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?
)

enum class VeniceFailureKind {
    /** Нет ключа: провайдер выключен. */
    DISABLED,

    /** 401/403: ключ неверный или без нужных прав. */
    AUTH,

    /** 402: на аккаунте Venice нет средств/кредитов. */
    PAYMENT,

    /** 400/404/422: плохой запрос или неизвестная модель. */
    BAD_REQUEST,

    /** 429 и сброс лимита слишком далеко, чтобы ждать. */
    RATE_LIMITED,

    /** 5xx после всех повторов. */
    UPSTREAM,

    TIMEOUT,
    NETWORK,

    /** Локальный бюджет ошибок модели (50 за 30 с у Venice) почти исчерпан. */
    ERROR_BUDGET
}

sealed interface VeniceResult<out T> {
    data class Success<T>(val value: T) : VeniceResult<T>

    /** [detail] безопасен для логов: ключи вычищены, тела ответов не пересылаются. */
    data class Failure(
        val kind: VeniceFailureKind,
        val httpStatus: Int? = null,
        val detail: String = "",
        val retryAfterSeconds: Long? = null
    ) : VeniceResult<Nothing>
}

/**
 * Клиент Venice AI. Только сеть и разбор ответов; доступ (админ) и частота
 * вызовов — выше, в [VeniceService] и HTTP-обработчике.
 *
 * Повтор: 429/500/502/503/504 и сетевые сбои, экспоненциально (база × 2^n),
 * но не раньше сброса лимита из `x-ratelimit-reset*` / `Retry-After`; если сброс
 * дальше потолка ожидания, сразу отдаём RATE_LIMITED. 400/401/402/403/404/422
 * не повторяются никогда.
 *
 * Бюджет ошибок: Venice блокирует модель после 50 неудачных запросов за 30 с.
 * Мы останавливаемся на [ERROR_BUDGET_LIMIT] раньше и не доводим до блокировки.
 */
class VeniceClient(
    private val config: VeniceConfig,
    private val transport: HttpTransport,
    private val logger: StructuredLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val failureWindows = ConcurrentHashMap<String, ArrayDeque<Long>>()

    @Volatile
    private var modelsCache: Pair<Long, List<VeniceModel>>? = null

    @Volatile
    private var lastRateHeaders: Map<String, String> = emptyMap()

    /** `x-ratelimit-*` из последнего ответа Venice (пусто, пока вызовов не было). */
    fun lastRateLimitHeaders(): Map<String, String> = lastRateHeaders

    suspend fun listModels(forceRefresh: Boolean = false): VeniceResult<List<VeniceModel>> {
        val key = config.inferenceKey() ?: return disabled()
        val cached = modelsCache
        if (!forceRefresh && cached != null && clock() - cached.first < config.modelsTtlMs) {
            return VeniceResult.Success(cached.second)
        }
        return when (val r = call("GET", "/models?type=text", null, key, budgetKey = "_models")) {
            is VeniceResult.Failure -> r
            is VeniceResult.Success -> {
                val models = parseModels(r.value.body)
                    ?: return VeniceResult.Failure(VeniceFailureKind.UPSTREAM, r.value.status, "unparseable models list")
                modelsCache = clock() to models
                VeniceResult.Success(models)
            }
        }
    }

    suspend fun chat(
        model: String,
        messages: List<VeniceMessage>,
        maxTokens: Int,
        temperature: Double
    ): VeniceResult<VeniceChatReply> {
        val key = config.inferenceKey() ?: return disabled()
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { m ->
                        add(buildJsonObject { put("role", m.role); put("content", m.content) })
                    }
                }
            )
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", false)
        }.toString()
        return when (val r = call("POST", "/chat/completions", body, key, budgetKey = model)) {
            is VeniceResult.Failure -> r
            is VeniceResult.Success -> parseChat(r.value.body, model)
                ?: VeniceResult.Failure(VeniceFailureKind.UPSTREAM, r.value.status, "empty or unparseable completion")
        }
    }

    /**
     * GET /api_keys/rate_limits. Нужен админ-ключ Venice: с обычным ключом
     * приходит 401/403, и вызывающий покажет понятное сообщение.
     */
    suspend fun rateLimits(): VeniceResult<JsonElement> {
        val key = config.rateLimitsKey() ?: return disabled()
        return when (val r = call("GET", "/api_keys/rate_limits", null, key, budgetKey = "_limits")) {
            is VeniceResult.Failure -> r
            is VeniceResult.Success -> runCatching {
                // Ответ уходит админу в приложение: даже случайно присланный ключ вычищаем.
                val raw = config.secrets().fold(r.value.body) { acc, s -> acc.replace(s, "[REDACTED]") }
                json.parseToJsonElement(raw)
            }
                .map { VeniceResult.Success(it) }
                .getOrElse { VeniceResult.Failure(VeniceFailureKind.UPSTREAM, r.value.status, "unparseable limits") }
        }
    }

    // ------------------------------------------------------------------ transport + retry

    private suspend fun call(
        method: String,
        path: String,
        body: String?,
        key: String,
        budgetKey: String
    ): VeniceResult<HttpTransportResponse> {
        val url = config.baseUrl.trimEnd('/') + path
        val headers = mapOf(
            "Authorization" to "Bearer $key",
            "Accept" to "application/json",
            "Content-Type" to "application/json"
        )
        var attempt = 0
        while (true) {
            if (budgetExhausted(budgetKey)) {
                return VeniceResult.Failure(
                    VeniceFailureKind.ERROR_BUDGET, null,
                    "local error budget reached for this model; wait 30 seconds"
                )
            }
            val response = try {
                if (method == "POST") {
                    transport.post(url, headers, body.orEmpty(), config.connectTimeoutMs, config.requestTimeoutMs)
                } else {
                    transport.get(url, headers, config.connectTimeoutMs, config.requestTimeoutMs)
                }
            } catch (e: TransportException) {
                recordFailure(budgetKey)
                val kind = if (e.kind == ProviderFailureKind.TIMEOUT) VeniceFailureKind.TIMEOUT else VeniceFailureKind.NETWORK
                if (attempt >= config.maxRetries) {
                    return VeniceResult.Failure(kind, null, scrub(e.detail))
                }
                waitBeforeRetry(path, attempt, status = null, headers = emptyMap())
                attempt++
                continue
            }
            captureRateHeaders(response.headers)
            val status = response.status
            if (status in 200..299) return VeniceResult.Success(response)

            recordFailure(budgetKey)
            if (status in RETRYABLE) {
                val resetMs = resetWaitMs(response.headers, clock())
                val tooFar = resetMs != null && resetMs > config.retryMaxDelayMs
                if (attempt >= config.maxRetries || tooFar) {
                    val kind = if (status == 429) VeniceFailureKind.RATE_LIMITED else VeniceFailureKind.UPSTREAM
                    return VeniceResult.Failure(
                        kind, status, "HTTP $status after ${attempt + 1} attempt(s)",
                        retryAfterSeconds = resetMs?.let { (it + 999) / 1000 }
                    )
                }
                waitBeforeRetry(path, attempt, status, response.headers)
                attempt++
                continue
            }
            val kind = when (status) {
                401, 403 -> VeniceFailureKind.AUTH
                402 -> VeniceFailureKind.PAYMENT
                400, 404, 413, 415, 422 -> VeniceFailureKind.BAD_REQUEST
                else -> VeniceFailureKind.UPSTREAM
            }
            return VeniceResult.Failure(kind, status, "HTTP $status")
        }
    }

    private suspend fun waitBeforeRetry(path: String, attempt: Int, status: Int?, headers: Map<String, String>) {
        val backoff = (config.retryBaseDelayMs shl attempt.coerceAtMost(10)).coerceAtMost(config.retryMaxDelayMs)
        val reset = resetWaitMs(headers, clock()) ?: 0L
        val wait = maxOf(backoff, reset).coerceAtMost(config.retryMaxDelayMs)
        logger.warn(
            "venice retry",
            "path" to path.substringBefore('?'),
            "status" to (status?.toString() ?: "transport"),
            "attempt" to (attempt + 1).toString(),
            "waitMs" to wait.toString()
        )
        sleeper(wait)
    }

    private fun captureRateHeaders(headers: Map<String, String>) {
        val rate = headers.filterKeys { it.startsWith("x-ratelimit") }
        if (rate.isNotEmpty()) lastRateHeaders = rate.mapValues { scrub(it.value).take(64) }
    }

    private fun scrub(text: String): String {
        var out = text
        config.secrets().forEach { out = out.replace(it, "[REDACTED]") }
        return LogSanitizer.forLog(out).take(200)
    }

    private fun disabled() = VeniceResult.Failure(VeniceFailureKind.DISABLED, null, "VENICE_API_KEY is not set")

    // ------------------------------------------------------------------ error budget

    private fun recordFailure(budgetKey: String) {
        val window = failureWindows.computeIfAbsent(budgetKey) { ArrayDeque() }
        synchronized(window) {
            val now = clock()
            window.addLast(now)
            trim(window, now)
        }
    }

    private fun budgetExhausted(budgetKey: String): Boolean {
        val window = failureWindows[budgetKey] ?: return false
        return synchronized(window) {
            trim(window, clock())
            window.size >= ERROR_BUDGET_LIMIT
        }
    }

    private fun trim(window: ArrayDeque<Long>, now: Long) {
        while (window.isNotEmpty() && now - window.peekFirst() > ERROR_BUDGET_WINDOW_MS) window.pollFirst()
    }

    // ------------------------------------------------------------------ parsing

    private fun parseModels(body: String): List<VeniceModel>? {
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"] as? JsonArray }.getOrNull()
            ?: return null
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val type = obj.str("type")
            if (type != null && type != "text") return@mapNotNull null
            val spec = obj["model_spec"] as? JsonObject
            if (spec?.get("offline")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return@mapNotNull null
            val pricing = spec?.get("pricing") as? JsonObject
            val lower = id.lowercase()
            VeniceModel(
                id = id,
                name = spec?.str("name") ?: id,
                contextTokens = (spec?.get("availableContextTokens") as? JsonPrimitive)?.longOrNull,
                inputUsdPerMillion = ((pricing?.get("input") as? JsonObject)?.get("usd") as? JsonPrimitive)?.doubleOrNull,
                outputUsdPerMillion = ((pricing?.get("output") as? JsonObject)?.get("usd") as? JsonPrimitive)?.doubleOrNull,
                privacy = spec?.str("privacy")?.lowercase()
                    ?: if ("e2ee" in lower) "e2ee" else "unknown",
                uncensored = "uncensored" in lower || "abliterated" in lower,
                beta = (spec?.get("beta") as? JsonPrimitive)?.booleanOrNull == true
            )
        }.sortedBy { it.id }
    }

    private fun parseChat(body: String, requestedModel: String): VeniceResult<VeniceChatReply>? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        val text = message?.str("content")?.takeIf { it.isNotBlank() } ?: return null
        val usage = root["usage"] as? JsonObject
        fun tok(name: String) = (usage?.get(name) as? JsonPrimitive)?.longOrNull
        return VeniceResult.Success(
            VeniceChatReply(
                text = text,
                model = root.str("model") ?: requestedModel,
                inputTokens = tok("prompt_tokens"),
                outputTokens = tok("completion_tokens"),
                totalTokens = tok("total_tokens")
            )
        )
    }

    private fun JsonObject.str(name: String): String? {
        val v = this[name]
        return if (v is JsonPrimitive && v !is JsonNull) v.contentOrNull else null
    }

    companion object {
        /** Venice блокирует модель на 50 неудачных запросах за 30 с; встаём раньше. */
        const val ERROR_BUDGET_LIMIT = 40
        const val ERROR_BUDGET_WINDOW_MS = 30_000L
        private val RETRYABLE = setOf(429, 500, 502, 503, 504)

        /**
         * Сколько ждать до сброса лимита, мс: `Retry-After` (секунды) либо
         * наименьший из `x-ratelimit-*reset*`: unix-время (с или мс) или секунды.
         * null — заголовков нет.
         */
        internal fun resetWaitMs(headers: Map<String, String>, nowMs: Long): Long? {
            headers.entries.firstOrNull { it.key.equals("retry-after", true) }
                ?.value?.trim()?.toDoubleOrNull()
                ?.let { return (it * 1000).toLong().coerceAtLeast(0) }
            return headers.entries
                .filter { it.key.startsWith("x-ratelimit", true) && it.key.contains("reset", true) }
                .mapNotNull { (_, raw) ->
                    val n = raw.trim().toDoubleOrNull() ?: return@mapNotNull null
                    when {
                        n > 1e12 -> (n - nowMs).toLong()
                        n > 1e9 -> (n * 1000 - nowMs).toLong()
                        else -> (n * 1000).toLong()
                    }.coerceAtLeast(0)
                }
                .minOrNull()
        }
    }
}
