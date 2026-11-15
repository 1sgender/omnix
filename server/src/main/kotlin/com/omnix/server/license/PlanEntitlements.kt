package com.omnix.server.license

/**
 * Тарифы OMNIX: FREE / PRO / OMNIX (в продукте «Max»; id `omnix` сохранён,
 * на нём уже выданы лицензии).
 *
 * Утверждено владельцем 2026-11-13 (цены в BILLING_PLANS: 0 / 149 / 299 TMT в мес.):
 * - Free идёт с наушниками навсегда и должен стоить нам ~0 (бесплатные ключи Gemini);
 * - голосовой AI (Flash): 10 / 300 / 1000 в день;
 * - базовый AI (Flash, чат): 10 / 300 / 1000 в день;
 * - Pro (Claude): 0 / 20 в день и 250 в мес. / 50 в день и 600 в мес.;
 * - 7 дней Pro бесплатно при первой привязке наушников (пробная лицензия plan=pro);
 * - сервер считает ТОЛЬКО voice/base (они идут через AiRouter);
 *   остальные квоты (agent/web/translation/ear/automations) и boolean-гейты
 *   везёт клиент: он получает их из validate-ответа (часть 2).
 *
 * Лимиты меняются серверным деплоем, БЕЗ обновления приложения.
 * Неизвестный planId = лимиты FREE (безопасный дефолт, fail-closed).
 */
enum class PlanFeature(val key: String) {
    VOICE_AI("voice_ai"),
    BASE_AI("base_ai"),

    /** Ответы уровня Pro (Claude): отдельная корзина, считается только когда ответил Claude. */
    PRO_AI("pro_ai")
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
    val maxClips: Int,
    /** Pro-ответов в день (Claude); 0 = Pro недоступен на тарифе. */
    val dailyProAi: Int = 0,
    /** Pro-ответов в календарный месяц UTC; 0 = Pro недоступен. */
    val monthlyProAi: Int = 0
) {
    /** Дневная квота, которую считает сервер (null = сервером не считается). */
    fun serverQuota(feature: PlanFeature): Int? =
        when (feature) {
            PlanFeature.VOICE_AI -> dailyVoiceAi
            PlanFeature.BASE_AI -> dailyBaseAi
            PlanFeature.PRO_AI -> dailyProAi
        }
}

object PlanCatalog {
    const val FREE = "free"
    const val OMNIX = "omnix"
    const val PRO = "pro"

    private val free = PlanLimits(
        dailyVoiceAi = 10,
        dailyBaseAi = 10,
        dailyAgentActions = 5,
        dailyWebSearch = 10,
        dailyTranslationUnits = 15,
        dailyEarMinutes = 10,
        maxAutomations = 3,
        screenReading = false,
        uiControl = false,
        priorityRouting = false,
        premiumModels = false,
        maxClips = 1,
        dailyProAi = 0,
        monthlyProAi = 0
    )

    private val omnix = PlanLimits(
        dailyVoiceAi = 1000,
        dailyBaseAi = 1000,
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
        maxClips = 2,
        dailyProAi = 50,
        monthlyProAi = 600
    )

    private val pro = PlanLimits(
        dailyVoiceAi = 300,
        dailyBaseAi = 300,
        dailyAgentActions = 100,
        dailyWebSearch = 100,
        dailyTranslationUnits = 300,
        dailyEarMinutes = 120,
        maxAutomations = Int.MAX_VALUE,
        screenReading = true,
        uiControl = true,
        priorityRouting = false,
        premiumModels = false,
        maxClips = 1,
        dailyProAi = 20,
        monthlyProAi = 250
    )

    fun forPlanId(planId: String): PlanLimits =
        when (planId) {
            OMNIX -> omnix
            PRO -> pro
            else -> free
        }
}
