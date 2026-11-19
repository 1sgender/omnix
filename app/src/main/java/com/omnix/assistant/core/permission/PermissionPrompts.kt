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
 * показывает сообщение, голосовой режим озвучивает его).
 */
object PermissionPrompts {
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private const val OUTCOME_BUFFER = 4
    private val _outcomes = MutableSharedFlow<String>(extraBufferCapacity = OUTCOME_BUFFER)

    /** Итог после запроса: результат повтора команды либо текст отказа. */
    val outcomes: SharedFlow<String> = _outcomes.asSharedFlow()

    private val retryLock = Any()
    private var retry: (suspend () -> String?)? = null
    private var recent: Pair<Set<String>, Long>? = null

    /** Запрос считается «свежим» для голосового ответа столько миллисекунд. */
    const val RECENT_WINDOW_MS = 15_000L

    fun request(permissions: Collection<String>, retryAfterGrant: (suspend () -> String?)? = null) {
        val runtime = permissions.filter { it.startsWith("android.permission.") }
        if (runtime.isEmpty()) return
        synchronized(retryLock) {
            retry = retryAfterGrant
            recent = runtime.toSet() to System.currentTimeMillis()
        }
        _pending.value = _pending.value + runtime
    }

    /** Забирает отложенный повтор команды (одноразово). */
    fun takeRetry(): (suspend () -> String?)? = synchronized(retryLock) {
        val taken = retry
        retry = null
        taken
    }

    /**
     * Какие разрешения запросил инструмент только что (одноразово). Голосовой
     * слой по этому понимает, что вместо ответа инструмента надо сказать
     * «нужен доступ, разрешите в окне на экране»; диалог к тому моменту мог
     * уже забрать [pending], поэтому отдельный след.
     */
    fun consumeRecent(nowMs: Long): Set<String>? = synchronized(retryLock) {
        val taken = recent
        recent = null
        taken?.takeIf { nowMs - it.second <= RECENT_WINDOW_MS }?.first
    }

    /** Сообщает слушателям (голос, чат) итог запроса доступа. */
    fun announce(message: String) {
        _outcomes.tryEmit(message)
    }

    fun clear() {
        _pending.value = emptySet()
    }
}
