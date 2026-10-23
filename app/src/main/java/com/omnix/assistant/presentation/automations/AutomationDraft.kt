package com.omnix.assistant.presentation.automations

import com.omnix.assistant.agent.automation.model.AutomationTriggerType
import com.omnix.assistant.agent.model.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** «Когда» в форме новой автоматизации. */
enum class DraftTrigger(val type: AutomationTriggerType) {
    TIME(AutomationTriggerType.TIME_SCHEDULE),
    HEADPHONES_ON(AutomationTriggerType.HEADPHONES_CONNECTED),
    HEADPHONES_OFF(AutomationTriggerType.HEADPHONES_DISCONNECTED),
    WIFI(AutomationTriggerType.WIFI_CONNECTED),
    BATTERY_LOW(AutomationTriggerType.BATTERY_LOW)
}

/**
 * «Что сделать»: только действия, которые движок выполняет без подтверждения
 * и без особых разрешений (режим «Не беспокоить» требует отдельного доступа и
 * молча не сработал бы).
 */
enum class DraftAction { BRIEFING, PAUSE_MEDIA, VOLUME, OPEN_APP }

/**
 * Форма новой автоматизации (отзыв владельца на v344: создать правило можно
 * было только голосом, экран умел лишь включать и удалять).
 */
data class AutomationDraft(
    val trigger: DraftTrigger = DraftTrigger.TIME,
    val hour: Int = 7,
    val minute: Int = 0,
    val action: DraftAction = DraftAction.BRIEFING,
    val volumePercent: Int = 50,
    val appName: String = ""
) {
    val time: String get() = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    val isComplete: Boolean get() = action != DraftAction.OPEN_APP || appName.isNotBlank()

    val triggerParam: String get() = if (trigger == DraftTrigger.TIME) time else ""

    /** Правило по времени срабатывает раз в сутки, остальные — не чаще раза в минуту. */
    val cooldownMs: Long get() = if (trigger == DraftTrigger.TIME) TIME_COOLDOWN_MS else EVENT_COOLDOWN_MS

    fun toolCalls(): List<ToolCall> = listOf(
        when (action) {
            DraftAction.BRIEFING -> ToolCall("productivity.ear_briefing", buildJsonObject { })
            DraftAction.PAUSE_MEDIA -> ToolCall("media.control", buildJsonObject { put("action", "pause") })
            DraftAction.VOLUME -> ToolCall(
                "device.volume",
                buildJsonObject {
                    put("action", "set")
                    put("percent", volumePercent.coerceIn(0, 100))
                }
            )
            DraftAction.OPEN_APP -> ToolCall("device.open_app", buildJsonObject { put("app_name", appName.trim()) })
        }
    )

    private companion object {
        const val TIME_COOLDOWN_MS = 23L * 60 * 60 * 1000
        const val EVENT_COOLDOWN_MS = 60_000L
    }
}
