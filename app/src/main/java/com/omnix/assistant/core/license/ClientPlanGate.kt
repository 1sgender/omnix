package com.omnix.assistant.core.license

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Клиентский enforcement тарифа (часть 2).
 *
 * Сервер считает только voice/base AI; всё остальное считает клиент
 * по entitlements из validate-ответа:
 * - boolean-гейты: чтение экрана, управление UI (проверка в ToolExecutor);
 * - дневные счётчики (UTC-сутки): agent/web/translation/ear;
 * - лимиты количества: автоматизации, клипы.
 *
 * Нет validate-ответа в этом процессе → entitlements = FREE (fail-closed,
 * как на сервере). Счётчики — обычные SharedPreferences (не секреты).
 * Неуспешные вызовы квоту НЕ тратят (потребление только после успеха).
 */
@Singleton
class ClientPlanGate @Inject constructor(
    @ApplicationContext context: Context,
    private val licenseManager: LicenseManager
) {
    companion object {
        private const val PREFS_NAME = "omnix_plan_quotas"

        /** toolId → требуемый boolean-гейт. Остальные tools гейтов не имеют. */
        private val BOOLEAN_GATES: Map<String, PlanEntitlements.() -> Boolean> = mapOf(
            "accessibility.screen_reader" to { screenReading },
            "accessibility.ui_click" to { uiControl },
            "accessibility.type_text" to { uiControl }
        )

        const val ERROR_PLAN_LIMIT = "PLAN_LIMIT_REACHED"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Переопределяется в тестах; в проде — системные UTC-часы. */
    var clock: Clock = Clock.systemUTC()

    fun entitlements(): PlanEntitlements = licenseManager.getLicenseInfo().entitlements

    fun clipState(): ClipBindingState = licenseManager.getLicenseInfo().clipBinding

    /** Boolean-гейт тарифа для tool. true = разрешён. */
    fun isToolAllowed(toolId: String): Boolean {
        val gate = BOOLEAN_GATES[toolId] ?: return true
        return entitlements().gate()
    }

    /** Дневной лимит счётчика по текущим entitlements. */
    fun dailyLimit(feature: ClientQuotaFeature): Int = when (feature) {
        ClientQuotaFeature.AGENT_ACTIONS -> entitlements().dailyAgentActions
        ClientQuotaFeature.WEB_SEARCH -> entitlements().dailyWebSearch
        ClientQuotaFeature.TRANSLATION_UNITS -> entitlements().dailyTranslationUnits
        ClientQuotaFeature.EAR_MINUTES -> entitlements().dailyEarMinutes
    }

    /**
     * Атомарно списывает [units] из дневной квоты.
     * @return true — списано; false — квота исчерпана (счётчик не тронут).
     */
    @Synchronized
    fun tryConsume(feature: ClientQuotaFeature, units: Int = 1): Boolean {
        require(units >= 1) { "units must be positive" }
        val today = epochDay()
        val dayKey = feature.prefsKey + "_day"
        val usedKey = feature.prefsKey + "_used"
        val used = if (prefs.getLong(dayKey, -1L) == today) prefs.getInt(usedKey, 0) else 0
        if (used + units > dailyLimit(feature)) return false
        prefs.edit().putLong(dayKey, today).putInt(usedKey, used + units).apply()
        return true
    }

    /** Остаток дневной квоты (для UI «осталось N»). */
    fun remaining(feature: ClientQuotaFeature): Int {
        val today = epochDay()
        val used =
            if (prefs.getLong(feature.prefsKey + "_day", -1L) == today) {
                prefs.getInt(feature.prefsKey + "_used", 0)
            } else {
                0
            }
        return (dailyLimit(feature) - used).coerceAtLeast(0)
    }

    /**
     * Единицы перевода за текст: потолок 1000 символов, минимум 1.
     * Пустой текст = 0 (не вызывается — движок отсекает раньше).
     */
    fun translationUnitsFor(text: String): Int =
        ((text.length + 999) / 1000).coerceAtLeast(1)

    /** Можно ли создать ещё одну автоматизацию при [currentCount] существующих. */
    fun canCreateAutomation(currentCount: Int): Boolean =
        currentCount < entitlements().maxAutomations

    /** Можно ли привязать ещё один Clip при [boundCount] привязанных. */
    fun canBindClip(boundCount: Int): Boolean =
        boundCount < entitlements().maxClips

    private fun epochDay(): Long = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay()
}
