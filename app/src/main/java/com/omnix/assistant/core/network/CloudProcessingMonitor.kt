package com.omnix.assistant.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Облачная активность приложения — единственный источник сигнала для бейджа
 * CLOUD на ядре (дизайн-матрица 2026-09-23: «ответ считается в облаке»).
 *
 * [com.omnix.assistant.data.remote.OmnixApiClient] отмечает начало и конец
 * каждого облачного запроса; презентация читает [active] через
 * `OmnixUiState.isCloudProcessing`. Бейдж отражает РЕАЛЬНЫЙ трафик, а не
 * предположение о маршруте решения — потому сигнал ставится на единственной
 * точке правды: HTTP-вызове облака.
 *
 * Счётчик, а не Boolean: параллельные запросы (голос + чат) не должны гасить
 * бейдж, пока хотя бы один из них в полёте.
 */
@Singleton
class CloudProcessingMonitor @Inject constructor() {

    private val inflight = MutableStateFlow(0)

    /** true, пока в облаке выполняется хотя бы один запрос. */
    val active: Flow<Boolean> = inflight
        .map { count -> count > 0 }
        .distinctUntilChanged()

    /** Вызывается перед отправкой облачного запроса. */
    fun requestStarted() {
        inflight.value += 1
    }

    /**
     * Вызывается на любом завершении запроса: успех, сетевая ошибка, разбор
     * ответа, отмена корутины (вызов идёт из finally). Повторный вызов на
     * один requestStarted безопасен — счётчик не уходит ниже нуля.
     */
    fun requestFinished() {
        inflight.value = (inflight.value - 1).coerceAtLeast(0)
    }
}
