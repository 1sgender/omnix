package com.omnix.server.license

/**
 * Тарифы OMNIX: FREE / PRO / OMNIX (средний — PRO, флагман — OMNIX).
 *
 * Утверждено фаундером (лимиты):
 * - голосовой AI: 30 / 500 / 2000 в день;
 * - базовый AI (чат): 50 / 500 / 2000 в день;
 * - сервер считает ТОЛЬКО voice/base (они идут через AiRouter);
 *   остальные квоты (agent/web/translation/ear/automations) и boolean-гейты
 *   везёт клиент: он получает их из validate-ответа (часть 2).
 *
 * Лимиты меняются серверным деплоем, БЕЗ обновления приложения.
 * Неизвестный planId = лимиты FREE (безопасный дефолт, fail-closed).
 */
enum class PlanFeature(val key: String) {
    VOICE_AI("voice_ai"),
    BASE_AI("base_ai")
}

data class PlanLimits(
    val dailyVoiceAi: Int,
    val dailyBaseAi: Int,
    val dailyAgentActions: Int,
    val dailyWebSearch: Int,
    val dailyTranslationUnits: Int,
    val dailyEarMinutes: Int,
    val maxAutomations: Int,
    val screenReading: Boolean,
    val uiControl: Boolean,
    val priorityRouting: Boolean,
    val premiumModels: Boolean,
    val maxClips: Int
) {
    /** Дневная квота, которую считает сервер (null = сервером не считается). */
    fun serverQuota(feature: PlanFeature): Int? =
        when (feature) {
            PlanFeature.VOICE_AI -> dailyVoiceAi
            PlanFeature.BASE_AI -> dailyBaseAi
        }
}

object PlanCatalog {
    const val FREE = "free"
    const val OMNIX = "omnix"
    const val PRO = "pro"

    private val free = PlanLimits(
        dailyVoiceAi = 30,
        dailyBaseAi = 50,
        dailyAgentActions = 5,
        dailyWebSearch = 10,
        dailyTranslationUnits = 15,
        dailyEarMinutes = 10,
        maxAutomations = 3,
        screenReading = false,
        uiControl = false,
        priorityRouting = false,
        premiumModels = false,
        maxClips = 1
    )

    private val omnix = PlanLimits(
        dailyVoiceAi = 2000,
        dailyBaseAi = 2000,
        dailyAgentActions = 500,
        dailyWebSearch = 500,
        dailyTranslationUnits = 1000,
        // Fair-use cap против абуза; в UI подаётся как «безлимит».
        dailyEarMinutes = 600,
        maxAutomations = Int.MAX_VALUE,
        screenReading = true,
        uiControl = true,
        priorityRouting = true,
        premiumModels = true,
        maxClips = 2
    )

    private val pro = PlanLimits(
        dailyVoiceAi = 500,
        dailyBaseAi = 500,
        dailyAgentActions = 100,
        dailyWebSearch = 100,
        dailyTranslationUnits = 300,
        dailyEarMinutes = 120,
        maxAutomations = Int.MAX_VALUE,
        screenReading = true,
        uiControl = true,
        priorityRouting = false,
        premiumModels = false,
        maxClips = 1
    )

    fun forPlanId(planId: String): PlanLimits =
        when (planId) {
            OMNIX -> omnix
            PRO -> pro
            else -> free
        }
}
