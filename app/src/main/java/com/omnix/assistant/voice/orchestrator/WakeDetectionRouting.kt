package com.omnix.assistant.voice.orchestrator

/**
 * Распоряжение по подтверждённому детекту wake word в зависимости от
 * текущего режима голосового контура. Чистая логика без Android-зависимостей
 * (JVM-тест — WakeDetectionRoutingTest).
 *
 * - [START_LISTENING] — STANDBY: классический сценарий (chime, слушание
 *   команды).
 * - [CANCEL_THINKING_AND_LISTEN] — AI_THINKING: голосовой barge-in. Пользователь
 *   сказал «Omni», ПОТОМУ ЧТО хочет сказать что-то новое (отменить текущий
 *   запрос и сменить тему) — отменяем in-flight запрос и сразу слушаем новую
 *   команду, не заставляя повторять wake word.
 * - [IGNORE] — остальные режимы: движок в них не запущен по построению,
 *   детект здесь невозможен; IGNORE — защитное замалчивание на случай
 *   аномального события.
 */
enum class WakeDetectionAction {
    START_LISTENING,
    CANCEL_THINKING_AND_LISTEN,
    IGNORE,
}

/**
 * Единственная точка маршрутизации wake-детекта: режим → действие.
 *
 * Инвариант think-phase (docs/WAKEWORD_NEURAL.md, «Холодный старт»): движок
 * живёт в STANDBY и в AI_THINKING (микрофон свободен, OMNIX молчит — TTS
 * глушит движок через self-voice guard в оркестраторе); в TTS_SPEAKING и
 * слушающих режимах запуск невозможен (self-trigger / занятый микрофон).
 */
internal fun resolveWakeDetectionAction(mode: OrchestratorMode): WakeDetectionAction = when (mode) {
    OrchestratorMode.STANDBY_WAKE_WORD -> WakeDetectionAction.START_LISTENING
    OrchestratorMode.AI_THINKING -> WakeDetectionAction.CANCEL_THINKING_AND_LISTEN
    else -> WakeDetectionAction.IGNORE
}
