package com.omnix.assistant.agent.automation.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Человекочитаемые подписи автоматизаций для UI (и, при необходимости, TTS).
 *
 * Слой намеренно возвращает ТОКЕНЫ, а не готовые строки: конечный текст живёт
 * в string-resources (en/ru/tk), как у всего остального интерфейса. Домен не
 * знает о ресурсах, UI не знает о структуре правил — оба переиспользуют эту
 * точку. Чистый Kotlin, без Android — покрыт JVM-тестами.
 */
enum class AutomationTriggerLabel {
    /** «Каждый день в 07:00» — параметр = triggerParam ("HH:MM"). */
    TIME_SCHEDULE,
    HEADPHONES_CONNECTED,
    HEADPHONES_DISCONNECTED,
    BATTERY_LOW,
    WIFI_CONNECTED,
    VOICE_MACRO,
    /** Неизвестный тип (правило из будущего релиза) — честная нейтральная подпись. */
    UNKNOWN
}

/** Метка одного действия правила; [OPEN_APP] несёт имя приложения параметром. */
enum class AutomationActionLabel { OPEN_APP, WEATHER, TIME, MEMORY, VOLUME, MEDIA, BRIEFING, OTHER }

data class LabeledAction(
    val label: AutomationActionLabel,
    /** Например, "calendar" для device.open_app(app_name="calendar"). */
    val param: String? = null
)

object AutomationDescriptions {

    /**
     * Триггер правила → (метка для ресурса, параметр если есть).
     * Неизвестный тип не валится: UNKNOWN показывает нейтральный текст.
     */
    fun triggerLabel(triggerType: String, triggerParam: String): Pair<AutomationTriggerLabel, String?> {
        val type = runCatching { AutomationTriggerType.valueOf(triggerType) }
            .getOrNull() ?: return AutomationTriggerLabel.UNKNOWN to null
        return when (type) {
            AutomationTriggerType.TIME_SCHEDULE ->
                AutomationTriggerLabel.TIME_SCHEDULE to triggerParam.ifBlank { null }
            AutomationTriggerType.HEADPHONES_CONNECTED -> AutomationTriggerLabel.HEADPHONES_CONNECTED to null
            AutomationTriggerType.HEADPHONES_DISCONNECTED -> AutomationTriggerLabel.HEADPHONES_DISCONNECTED to null
            AutomationTriggerType.BATTERY_LOW -> AutomationTriggerLabel.BATTERY_LOW to null
            AutomationTriggerType.WIFI_CONNECTED -> AutomationTriggerLabel.WIFI_CONNECTED to null
            AutomationTriggerType.VOICE_MACRO -> AutomationTriggerLabel.VOICE_MACRO to null
        }
    }

    /**
     * actionsJson → список меток действий. Формат — тот же, что пишет
     * PersonalAutomationEngine / productivity.create_automation:
     * `[{"tool":"device.open_app","arguments":{"app_name":"calendar"}}, ...]`.
     * Битый JSON → пустой список: карточка покажет «нет действий» вместо краша.
     */
    fun actionLabels(actionsJson: String, json: Json = Json): List<LabeledAction> {
        if (actionsJson.isBlank()) return emptyList()
        val element = runCatching { json.parseToJsonElement(actionsJson).jsonArray }
            .getOrNull() ?: return emptyList()
        return element.mapNotNull { item ->
            val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val toolId = runCatching {
                obj["tool"]?.jsonPrimitive?.content
            }.getOrNull() ?: return@mapNotNull null
            val args = runCatching {
                obj["arguments"] as? JsonObject
            }.getOrNull() ?: JsonObject(emptyMap())
            LabeledAction(
                label = actionLabel(toolId),
                param = runCatching {
                    args["app_name"]?.jsonPrimitive?.content
                }.getOrNull()
            )
        }
    }

    private fun actionLabel(toolId: String): AutomationActionLabel = when {
        toolId == "device.open_app" -> AutomationActionLabel.OPEN_APP
        toolId == "intelligence.weather" -> AutomationActionLabel.WEATHER
        toolId == "system.time" -> AutomationActionLabel.TIME
        toolId.startsWith("memory.") -> AutomationActionLabel.MEMORY
        toolId == "device.volume" || toolId == "device.brightness" -> AutomationActionLabel.VOLUME
        toolId == "media.control" -> AutomationActionLabel.MEDIA
        toolId == "productivity.ear_briefing" -> AutomationActionLabel.BRIEFING
        else -> AutomationActionLabel.OTHER
    }
}
