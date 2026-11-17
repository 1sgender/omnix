package com.omnix.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.core.license.ClientPlanGate
import com.omnix.assistant.core.license.ClientQuotaFeature
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.license.PlanAiUsage
import com.omnix.assistant.core.license.PlanEntitlements
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Какой дневной лимит показывает счётчик в карточке тарифа. */
enum class PlanMeterKind { FLASH, SEARCH, TRANSLATION, EAR }

/**
 * Один счётчик карточки: [limit] из entitlements тарифа, [left] остаток на сегодня
 * или null, когда остаток неизвестен (Flash считает сервер, и до первого validate его нет).
 */
data class PlanMeter(val kind: PlanMeterKind, val limit: Int, val left: Int?) {
    /** Доля остатка 0..1 для полоски; null, если остаток неизвестен или лимита нет. */
    val fraction: Float?
        get() = if (left == null || limit <= 0) null else (left.toFloat() / limit).coerceIn(0f, 1f)
}

data class MePlanState(
    val planId: String = "",
    val meters: List<PlanMeter> = emptyList()
)

/** Счётчики карточки по entitlements; лимиты берутся с сервера, не хардкодятся. */
internal fun buildPlanMeters(
    entitlements: PlanEntitlements,
    aiUsage: PlanAiUsage?,
    remaining: (ClientQuotaFeature) -> Int
): List<PlanMeter> = listOf(
    PlanMeter(
        PlanMeterKind.FLASH,
        entitlements.dailyBaseAi,
        aiUsage?.let { (entitlements.dailyBaseAi - it.usedToday.baseAi.toInt()).coerceAtLeast(0) }
    ),
    PlanMeter(PlanMeterKind.SEARCH, entitlements.dailyWebSearch, remaining(ClientQuotaFeature.WEB_SEARCH)),
    PlanMeter(PlanMeterKind.TRANSLATION, entitlements.dailyTranslationUnits, remaining(ClientQuotaFeature.TRANSLATION_UNITS)),
    PlanMeter(PlanMeterKind.EAR, entitlements.dailyEarMinutes, remaining(ClientQuotaFeature.EAR_MINUTES))
)

/** Состояние карточки текущего тарифа на экране «Я». */
@HiltViewModel
class MePlanViewModel @Inject constructor(
    private val licenseManager: LicenseManager,
    private val gate: ClientPlanGate
) : ViewModel() {

    private val _state = MutableStateFlow(build())
    val state: StateFlow<MePlanState> = _state.asStateFlow()

    init {
        viewModelScope.launch { licenseManager.licenseFlow.collect { refresh() } }
    }

    /** Остатки живут в SharedPreferences и не шлют событий: перечитываем при показе экрана. */
    fun refresh() {
        _state.value = build()
    }

    private fun build(): MePlanState {
        val info = licenseManager.getLicenseInfo()
        return MePlanState(
            planId = info.planId,
            meters = buildPlanMeters(info.entitlements, info.aiUsage, gate::remaining)
        )
    }
}
