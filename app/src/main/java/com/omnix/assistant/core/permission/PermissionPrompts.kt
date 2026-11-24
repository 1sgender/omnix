package com.omnix.assistant.core.permission

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Очередь системных запросов разрешений от инструментов. Инструмент, которому
 * не хватило доступа («позвони маме» без контактов), кладёт сюда нужные
 * разрешения; открытая [com.omnix.assistant.presentation.MainActivity]
 * показывает системный диалог сразу. Если приложение закрыто, запрос ждёт
 * следующего открытия.
 *
 * Тулы не получают Activity: запрос делает UI-слой, а связь идёт через этот
 * объект. Вместе с запросом можно положить [retryAfterGrant]: после выдачи
 * доступа Activity запускает его и объявляет итог через [announce] (чат
 * показывает сообщение, голосовой режим озвучивает его). Повтор живёт
 * ограниченное время ([RETRY_TTL_MS]) и сбрасывается при закрытии диалога
 * без результата ([cancelRetries]).
 */
object PermissionPrompts {
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private const val OUTCOME_BUFFER = 4
    private val _outcomes = MutableSharedFlow<String>(extraBufferCapacity = OUTCOME_BUFFER)

    /** Итог после запроса: результат повтора команды либо текст отказа. */
    val outcomes: SharedFlow<String> = _outcomes.asSharedFlow()

    private val retryLock = Any()
    private val retries = ArrayDeque<PendingRetry>()
    private var recent: RecentRequest? = null

    /** Запрос считается «свежим» для голосового ответа столько миллисекунд. */
    const val RECENT_WINDOW_MS = 15_000L

    /**
     * Срок годности повтора команды. Диалог разрешения отвечают за секунды;
     * если пользователь вернулся через минуты или часы, старая команда
     * (звонок, SMS) уже не его намерение и молча выполняться не должна.
     */
    const val RETRY_TTL_MS = 90_000L

    /** Сколько повторов разных команд держим одновременно; старейший вытесняется. */
    const val MAX_RETRIES = 3

    private class PendingRetry(
        val key: String,
        val createdAtMs: Long,
        val action: suspend () -> String?
    )

    private class RecentRequest(
        val permissions: Set<String>,
        val requestedAtMs: Long
    )

    /**
     * @param key идентификатор вызова (callId либо toolId): повтор той же
     *        команды заменяет прежний, повторы разных команд не затирают друг друга.
     */
    fun request(
        permissions: Collection<String>,
        key: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        retryAfterGrant: (suspend () -> String?)? = null
    ) {
        val runtime = permissions.filter { it.startsWith("android.permission.") }
        if (runtime.isEmpty()) return
        synchronized(retryLock) {
            dropExpired(nowMs)
            if (retryAfterGrant != null) {
                val retryKey = key ?: "anonymous"
                retries.removeAll { it.key == retryKey }
                while (retries.size >= MAX_RETRIES) retries.removeFirst()
                retries.addLast(PendingRetry(retryKey, nowMs, retryAfterGrant))
            }
            recent = RecentRequest(runtime.toSet(), nowMs)
        }
        _pending.value = _pending.value + runtime
    }

    private fun dropExpired(nowMs: Long) {
        retries.removeAll { nowMs - it.createdAtMs > RETRY_TTL_MS }
    }

    /**
     * Забирает отложенные повторы (одноразово): неистёкшие выполняются по
     * очереди, итоги склеиваются. Null, если ничего не ждёт или срок вышел.
     */
    fun takeRetry(nowMs: Long = System.currentTimeMillis()): (suspend () -> String?)? =
        synchronized(retryLock) {
            dropExpired(nowMs)
            val taken = retries.toList()
            retries.clear()
            when (taken.size) {
                0 -> null
                1 -> taken.first().action
                else -> {
                    val combined: suspend () -> String? = {
                        taken.mapNotNull { it.action() }.joinToString(" ").ifBlank { null }
                    }
                    combined
                }
            }
        }

    /**
     * Диалог закрыт без результата (пусто, отказ, отмена): повторы не должны
     * дожидаться чужой следующей выдачи доступа.
     */
    fun cancelRetries() {
        synchronized(retryLock) { retries.clear() }
    }

    /**
     * Какие разрешения запросил инструмент текущей реплики (одноразово).
     * Голосовой слой по этому понимает, что вместо ответа инструмента надо
     * сказать «нужен доступ, разрешите в окне на экране»; диалог к тому
     * моменту мог уже забрать [pending], поэтому отдельный след.
     *
     * @param sinceMs запрос принимается, только если сделан не раньше начала
     *        обработки текущей реплики: запрос от предыдущей команды не
     *        должен подменять ответ на новую.
     */
    fun consumeRecent(nowMs: Long, sinceMs: Long = 0L): Set<String>? = synchronized(retryLock) {
        val taken = recent
        recent = null
        taken
            ?.takeIf { nowMs - it.requestedAtMs <= RECENT_WINDOW_MS && it.requestedAtMs >= sinceMs }
            ?.permissions
    }

    /** Забыть след недавнего запроса (новая реплика, таймаут). */
    fun forgetRecent() {
        synchronized(retryLock) { recent = null }
    }

    /** Сообщает слушателям (голос, чат) итог запроса доступа. */
    fun announce(message: String) {
        _outcomes.tryEmit(message)
    }

    fun clear() {
        _pending.value = emptySet()
    }
}
