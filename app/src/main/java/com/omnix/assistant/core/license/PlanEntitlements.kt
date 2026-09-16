package com.omnix.assistant.core.license

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Квоты и гейты тарифа для КЛИЕНТСКОГО enforcement (часть 2).
 *
 * Зеркало серверного PlanLimits (validate-ответ, поле entitlements).
 * Все поля с дефолтами = значениям FREE: ответ старого сервера
 * (без entitlements) парсится и даёт fail-closed лимиты FREE.
 *
 * Единицы:
 * - translation unit = 1 вызов перевода (потолок 1000 символов — см. ClientPlanGate);
 * - ear minute = минута озвучки брифинга (отчитывает ProactiveEarBriefingEngine);
 * - agent action = 1 успешный вызов tool через ToolExecutor;
 * - web search = 1 успешный вызов intelligence.web_search.
 */
@Serializable
data class PlanEntitlements(
    @SerialName("daily_voice_ai") val dailyVoiceAi: Int = 30,
    @SerialName("daily_base_ai") val dailyBaseAi: Int = 50,
    @SerialName("daily_agent_actions") val dailyAgentActions: Int = 5,
    @SerialName("daily_web_search") val dailyWebSearch: Int = 10,
    @SerialName("daily_translation_units") val dailyTranslationUnits: Int = 15,
    @SerialName("daily_ear_minutes") val dailyEarMinutes: Int = 10,
    @SerialName("max_automations") val maxAutomations: Int = 3,
    @SerialName("screen_reading") val screenReading: Boolean = false,
    @SerialName("ui_control") val uiControl: Boolean = false,
    @SerialName("priority_routing") val priorityRouting: Boolean = false,
    @SerialName("premium_models") val premiumModels: Boolean = false,
    @SerialName("max_clips") val maxClips: Int = 1
) {
    companion object {
        /** Fail-closed дефолт: нет validate-ответа → лимиты FREE. */
        val FREE = PlanEntitlements()
    }
}

/**
 * Soft-привязка Clip из validate-ответа.
 * Информативно: отсутствие клипа AI и tools НЕ блокирует.
 */
@Serializable
data class ClipBindingState(
    @SerialName("has_bound_clip") val hasBoundClip: Boolean = false,
    @SerialName("bound_clip_count") val boundClipCount: Int = 0
) {
    companion object {
        /** Старый сервер (без поля clip) → считаем, что клипа нет. */
        val UNKNOWN = ClipBindingState(hasBoundClip = false, boundClipCount = 0)
    }
}

/** Дневные счётчики клиента (UTC-сутки, см. ClientPlanGate). */
enum class ClientQuotaFeature(val prefsKey: String) {
    AGENT_ACTIONS("quota_agent_actions"),
    WEB_SEARCH("quota_web_search"),
    TRANSLATION_UNITS("quota_translation_units"),
    EAR_MINUTES("quota_ear_minutes")
}
