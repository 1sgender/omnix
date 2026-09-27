package com.omnix.assistant.presentation.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Доступ к dev-секции метрик в Diagnostics (блок 8 плана пересборки
 * фронта, 2026-09-26). План владельца: метрики — dev-секция, можно за
 * long-press строки версии.
 *
 * Скрыта по умолчанию и открывается ТОЛЬКО на эту сессию: перезапуск
 * приложения возвращает потребительский вид без dev-данных. Односторонняя
 * семантика [reveal] — повторный long-press не «мигает» секцию.
 */
@Singleton
class DeveloperMetricsAccess @Inject constructor() {

    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed.asStateFlow()

    fun reveal() {
        _revealed.update { true }
    }
}
