package com.omnix.assistant.core.license

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Что облако сделало с запросами этого процесса: кто ответил (Pro или Flash),
 * какие ответы засчитаны после последнего validate и какие корзины сервер
 * закрыл отказом QUOTA_EXCEEDED.
 *
 * Пишет OmnixApiClient (знает request id, запрошенный и фактический уровень,
 * заголовки отказа); читают SendPromptUseCase (пометка «ответил Flash»,
 * сообщение о лимите) и экран «Сегодня осталось». Только в памяти: после
 * перезапуска свежий снимок даёт validate.
 */
@Singleton
class AiTierLedger @Inject constructor() {

    private val lock = Any()
    private val downgraded = LinkedHashSet<String>()
    private val quotaByRequest = LinkedHashMap<String, QuotaExceeded>()
    private val busyByRequest = LinkedHashMap<String, ServerBusy>()

    private val _answers = MutableStateFlow<List<LocalAnswer>>(emptyList())

    /** Засчитанные облаком ответы за последний месяц (для остатка поверх снимка validate). */
    val answers: StateFlow<List<LocalAnswer>> = _answers.asStateFlow()

    private val _exhausted = MutableStateFlow<List<QuotaExceeded>>(emptyList())

    /** Корзины, закрытые отказом сервера, пока не обновятся. */
    val exhausted: StateFlow<List<QuotaExceeded>> = _exhausted.asStateFlow()

    /** Успешный ответ облака: засчитать его и запомнить, если вместо Pro ответил Flash. */
    fun onAnswered(
        requestId: String,
        requestedPro: Boolean,
        servedPro: Boolean,
        voice: Boolean,
        atMs: Long = System.currentTimeMillis()
    ) {
        if (requestedPro && !servedPro && requestId.isNotBlank()) {
            synchronized(lock) { downgraded.addBounded(requestId) }
        }
        val bucket = AiQuotaBucket.of(servedPro = servedPro, voice = voice)
        val monthAgo = atMs - MONTH_MS
        _answers.update { list ->
            (list.filter { it.atMs >= monthAgo } + LocalAnswer(bucket, atMs)).takeLast(MAX_ANSWERS)
        }
        // Ответ из корзины — значит, она снова открыта (лимит обновился раньше, чем думали).
        _exhausted.update { list -> list.filterNot { it.bucket == bucket } }
    }

    /** Просили Pro, ответил Flash (Pro временно недоступен). Запись забирается. */
    fun takeDowngrade(requestId: String): Boolean = synchronized(lock) { downgraded.remove(requestId) }

    fun onQuotaExceeded(requestId: String, exceeded: QuotaExceeded) {
        if (requestId.isNotBlank()) {
            synchronized(lock) {
                quotaByRequest.remove(requestId)
                quotaByRequest[requestId] = exceeded
                while (quotaByRequest.size > MAX_REQUESTS) quotaByRequest.remove(quotaByRequest.keys.first())
            }
        }
        _exhausted.update { list ->
            list.filter { it.bucket != exceeded.bucket && it.untilMs() > exceeded.atMs } + exceeded
        }
    }

    /** Отказ QUOTA_EXCEEDED по этому запросу, если был. Запись забирается. */
    fun takeQuotaExceeded(requestId: String): QuotaExceeded? = synchronized(lock) { quotaByRequest.remove(requestId) }

    /** Сервер перегружен / ИИ-провайдеры недоступны по этому запросу. */
    fun onServerBusy(requestId: String, busy: ServerBusy) {
        if (requestId.isBlank()) return
        synchronized(lock) {
            busyByRequest.remove(requestId)
            busyByRequest[requestId] = busy
            while (busyByRequest.size > MAX_REQUESTS) busyByRequest.remove(busyByRequest.keys.first())
        }
    }

    /** Отказ «перегружено» по этому запросу, если был. Запись забирается. */
    fun takeServerBusy(requestId: String): ServerBusy? = synchronized(lock) { busyByRequest.remove(requestId) }

    private fun LinkedHashSet<String>.addBounded(id: String) {
        add(id)
        while (size > MAX_REQUESTS) remove(first())
    }

    private companion object {
        const val MAX_REQUESTS = 64
        const val MAX_ANSWERS = 5_000
        const val MONTH_MS = 32L * AiQuotaClock.DAY_MS
    }
}

/**
 * Отказ SERVER_BUSY / ALL_PROVIDERS_UNAVAILABLE: [retryAfterSeconds] — подсказка сервера (Retry-After
 * или retry_after в теле), [aiOverloaded] — перегружены именно ИИ-провайдеры, а не сам сервер.
 */
data class ServerBusy(val retryAfterSeconds: Int?, val aiOverloaded: Boolean) {
    /** Через сколько секунд просить пользователя повторить: подсказка сервера в разумных границах. */
    fun waitSeconds(): Int = (retryAfterSeconds ?: DEFAULT_WAIT_SECONDS).coerceIn(1, MAX_WAIT_SECONDS)

    companion object {
        const val CODE_SERVER_BUSY = "SERVER_BUSY"
        const val CODE_PROVIDERS = "ALL_PROVIDERS_UNAVAILABLE"
        const val CODE_PROVIDER = "PROVIDER_UNAVAILABLE"
        private const val DEFAULT_WAIT_SECONDS = 5
        private const val MAX_WAIT_SECONDS = 120

        /** null — код не про перегрузку. */
        fun parse(code: String, retryAfterHeader: String?, retryAfterBody: Int?): ServerBusy? {
            val aiOverloaded = when (code) {
                CODE_SERVER_BUSY -> false
                CODE_PROVIDERS, CODE_PROVIDER -> true
                else -> return null
            }
            val retry = retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: retryAfterBody?.takeIf { it > 0 }
            return ServerBusy(retry, aiOverloaded)
        }
    }
}
