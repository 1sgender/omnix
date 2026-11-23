package com.omnix.server.venice

import com.omnix.server.observability.Metrics
import com.omnix.server.observability.StructuredLogger
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Итог чат-вызова Venice с подсчитанным расходом. */
data class VeniceChatOutcome(
    val reply: VeniceChatReply,
    /** Оценка по цене модели из списка; null, если цена неизвестна. */
    val costUsd: Double?
)

/** Накопленный с запуска сервера расход Venice (в памяти; счётчики идут и в /metrics). */
data class VeniceUsageSnapshot(
    val requests: Long,
    val failures: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double
)

/**
 * Прикладной слой Venice: потолок запросов в минуту, потолок токенов ответа,
 * проверка модели по списку сервера и учёт расхода. НЕ проверяет, кто
 * вызывает: админ-доступ решают вызывающие ([VeniceHttpHandler], AiRouter) —
 * и ни один путь, кроме них, к этому классу не ведёт.
 *
 * В логи идут только id запроса, модель, токены и цена. Текст запросов и
 * ответов и ключ не логируются.
 */
class VeniceService(
    val config: VeniceConfig,
    private val client: VeniceClient,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    private val clock: () -> Long = System::currentTimeMillis
) {
    val enabled: Boolean get() = config.enabled

    private val window = ArrayDeque<Long>()
    private val requests = AtomicLong()
    private val failures = AtomicLong()
    private val inputTokens = AtomicLong()
    private val outputTokens = AtomicLong()
    private val costMicroUsd = AtomicLong()

    fun usage(): VeniceUsageSnapshot = VeniceUsageSnapshot(
        requests = requests.get(),
        failures = failures.get(),
        inputTokens = inputTokens.get(),
        outputTokens = outputTokens.get(),
        costUsd = costMicroUsd.get() / 1_000_000.0
    )

    suspend fun models(): VeniceResult<List<VeniceModel>> = client.listModels()

    suspend fun rateLimits(): VeniceResult<kotlinx.serialization.json.JsonElement> = client.rateLimits()

    fun lastRateLimitHeaders(): Map<String, String> = client.lastRateLimitHeaders()

    /** null — можно звонить; иначе через сколько секунд освободится окно. */
    private fun admit(): Long? = synchronized(window) {
        val now = clock()
        while (window.isNotEmpty() && now - window.peekFirst() >= 60_000) window.pollFirst()
        if (window.size >= config.requestsPerMinute) {
            ((window.peekFirst() + 60_000 - now) + 999) / 1000
        } else {
            window.addLast(now)
            null
        }
    }

    /**
     * Чат-вызов от имени админа. [requestedMaxTokens] урезается до
     * [VeniceConfig.maxOutputTokens]; модель обязана быть в списке текстовых
     * моделей Venice (картинки, эмбеддинги и придуманные id отсекаются).
     */
    suspend fun chat(
        requestId: String,
        model: String,
        messages: List<VeniceMessage>,
        requestedMaxTokens: Int,
        temperature: Double
    ): VeniceResult<VeniceChatOutcome> {
        if (!enabled) return VeniceResult.Failure(VeniceFailureKind.DISABLED, null, "venice disabled")
        if (!MODEL_ID.matches(model)) {
            return VeniceResult.Failure(VeniceFailureKind.BAD_REQUEST, null, "invalid model id")
        }
        val known = when (val listed = client.listModels()) {
            is VeniceResult.Failure -> return listed
            is VeniceResult.Success -> listed.value.firstOrNull { it.id == model }
        } ?: return VeniceResult.Failure(VeniceFailureKind.BAD_REQUEST, null, "model is not a Venice text model")

        admit()?.let { retryAfter ->
            logger.warn("venice local rate limit", "requestId" to requestId, "retryAfter" to retryAfter.toString())
            metrics.increment("venice_local_rate_limited_total")
            return VeniceResult.Failure(
                VeniceFailureKind.RATE_LIMITED, null, "local limit ${config.requestsPerMinute}/min",
                retryAfterSeconds = retryAfter
            )
        }

        val maxTokens = requestedMaxTokens.coerceIn(1, config.maxOutputTokens)
        requests.incrementAndGet()
        metrics.increment("venice_requests_total")
        return when (val r = client.chat(model, messages, maxTokens, temperature)) {
            is VeniceResult.Failure -> {
                failures.incrementAndGet()
                metrics.increment("venice_failures_total")
                logger.warn(
                    "venice chat failed",
                    "requestId" to requestId, "model" to model, "kind" to r.kind.name,
                    "status" to (r.httpStatus?.toString() ?: "-")
                )
                r
            }
            is VeniceResult.Success -> {
                val reply = r.value
                val inTok = reply.inputTokens ?: 0
                val outTok = reply.outputTokens ?: 0
                val cost = costUsd(known, inTok, outTok)
                inputTokens.addAndGet(inTok)
                outputTokens.addAndGet(outTok)
                if (cost != null) costMicroUsd.addAndGet((cost * 1_000_000).toLong())
                metrics.add("venice_input_tokens_total", inTok)
                metrics.add("venice_output_tokens_total", outTok)
                if (cost != null) metrics.add("venice_cost_microusd_total", (cost * 1_000_000).toLong())
                logger.info(
                    "venice chat completed",
                    "requestId" to requestId, "model" to model,
                    "inputTokens" to inTok.toString(), "outputTokens" to outTok.toString(),
                    "costUsd" to (cost?.let { "%.6f".format(java.util.Locale.ROOT, it) } ?: "-")
                )
                VeniceResult.Success(VeniceChatOutcome(reply, cost))
            }
        }
    }

    private fun costUsd(model: VeniceModel, inTokens: Long, outTokens: Long): Double? {
        val i = model.inputUsdPerMillion ?: return null
        val o = model.outputUsdPerMillion ?: return null
        return i * inTokens / 1_000_000.0 + o * outTokens / 1_000_000.0
    }

    companion object {
        /** Допустимый вид id модели Venice (защита от мусора в пути/теле). */
        val MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")

        /** Префикс поля adminProvider: `venice:<model-id>`. */
        const val PIN_PREFIX = "venice:"

        /** Извлекает id модели из adminProvider; null — это не Venice-запрос. */
        fun modelFromPin(adminProvider: String?): String? {
            val raw = adminProvider?.trim() ?: return null
            if (!raw.startsWith(PIN_PREFIX, ignoreCase = true)) return null
            return raw.substring(PIN_PREFIX.length).trim().ifEmpty { null }
        }
    }
}
