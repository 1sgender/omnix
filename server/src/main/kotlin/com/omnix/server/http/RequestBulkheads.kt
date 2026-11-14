package com.omnix.server.http

import com.omnix.server.api.ApiErrorCode
import com.omnix.server.config.ConcurrencyConfig
import java.sql.SQLTransientConnectionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ограничители одновременных запросов (bulkhead) с быстрым отказом.
 *
 * Общий лимит защищает процесс целиком, отдельные лимиты для ИИ и распознавания
 * речи не дают долгим вызовам облачных провайдеров вытеснить лёгкие запросы
 * (license/validate при массовом первом запуске). Очереди нет: нет свободного
 * места — клиент сразу получает 503 SERVER_BUSY с Retry-After.
 */
class RequestBulkheads(private val config: ConcurrencyConfig) {
    private val total = Semaphore(config.maxInFlight)
    private val ai = Semaphore(config.maxAiInFlight)
    private val stt = Semaphore(config.maxSttInFlight)

    /** Занятое место; [close] освобождает его ровно один раз. */
    inner class Permit internal constructor(private val heavy: Semaphore?) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                heavy?.release()
                total.release()
            }
        }
    }

    /** Занимает место для запроса на [path]; null — перегрузка, отвечать [busyResponse]. */
    fun tryEnter(path: String): Permit? {
        if (!total.tryAcquire()) return null
        val heavy = when (path) {
            OmnixApiHandler.PATH_EXECUTE -> ai
            OmnixApiHandler.PATH_STT -> stt
            else -> null
        }
        if (heavy != null && !heavy.tryAcquire()) {
            total.release()
            return null
        }
        return Permit(heavy)
    }

    /** Единый ответ при перегрузке: 503 SERVER_BUSY + Retry-After + retry_after в теле. */
    fun busyResponse(): HttpResponseContext = busyResponse(config.busyRetryAfterSeconds)

    companion object {
        fun busyResponse(retryAfterSeconds: Int): HttpResponseContext = HttpResponseContext(
            status = ApiErrorCode.SERVER_BUSY.httpStatus,
            body = """{"success":false,"error":{"code":"${ApiErrorCode.SERVER_BUSY.name}",""" +
                """"message":"${ApiErrorCode.SERVER_BUSY.safeMessage}","requestId":"-","retry_after":$retryAfterSeconds}}""",
            headers = mapOf("Retry-After" to retryAfterSeconds.toString(), "Cache-Control" to "no-store")
        )

        /** Пул соединений с БД исчерпан (Hikari не дал соединение за connectionTimeout). */
        fun isDatabaseOverload(failure: Throwable): Boolean {
            var current: Throwable? = failure
            var depth = 0
            while (current != null && depth < 8) {
                if (current is SQLTransientConnectionException) return true
                current = current.cause
                depth++
            }
            return false
        }
    }
}
