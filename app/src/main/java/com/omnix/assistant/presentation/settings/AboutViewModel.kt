package com.omnix.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import com.omnix.assistant.presentation.diagnostics.DeveloperMetricsAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Тонкая обвязка About-секции: единственная обязанность — long-press по
 * строке версии открывает dev-секцию метрик в Diagnostics (план владельца,
 * блок 8 пересборки фронта). Живёт отдельно от [SettingsViewModel], чтобы
 * не менять его конструктор и тесты второго разработчика.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val developerMetricsAccess: DeveloperMetricsAccess
) : ViewModel() {

    fun revealDevMetrics() {
        developerMetricsAccess.reveal()
    }
}
