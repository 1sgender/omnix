package com.omnix.assistant.voice.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Маршрутизация wake-детекта (think-phase, бариж-ин): режим голосового
 * контура → действие. Чистая логика — без Android-зависимостей.
 */
class WakeDetectionRoutingTest {

    @Test
    fun standbyRoutesToStartListening() {
        assertEquals(
            WakeDetectionAction.START_LISTENING,
            resolveWakeDetectionAction(OrchestratorMode.STANDBY_WAKE_WORD)
        )
    }

    @Test
    fun thinkingRoutesToCancelAndListen() {
        assertEquals(
            WakeDetectionAction.CANCEL_THINKING_AND_LISTEN,
            resolveWakeDetectionAction(OrchestratorMode.AI_THINKING)
        )
    }

    @Test
    fun listeningModesAreIgnored() {
        // Микрофон занят STT — детект здесь невозможен по построению.
        assertEquals(
            WakeDetectionAction.IGNORE,
            resolveWakeDetectionAction(OrchestratorMode.LISTENING_USER_QUERY)
        )
        assertEquals(
            WakeDetectionAction.IGNORE,
            resolveWakeDetectionAction(OrchestratorMode.CONTINUOUS_CONVERSATION)
        )
    }

    @Test
    fun speakingAndAwaitingModesAreIgnored() {
        // TTS_SPEAKING: self-trigger — движок в этом режиме не запущен.
        assertEquals(
            WakeDetectionAction.IGNORE,
            resolveWakeDetectionAction(OrchestratorMode.TTS_SPEAKING)
        )
        assertEquals(
            WakeDetectionAction.IGNORE,
            resolveWakeDetectionAction(OrchestratorMode.AWAITING_CONFIRMATION)
        )
        assertEquals(
            WakeDetectionAction.IGNORE,
            resolveWakeDetectionAction(OrchestratorMode.AWAITING_PRIVACY_CONSENT)
        )
    }

    @Test
    fun interpreterAndPausedModesAreIgnored() {
        assertEquals(
            WakeDetectionAction.IGNORE,
            resolveWakeDetectionAction(OrchestratorMode.LIVE_EAR_INTERPRETER)
        )
        assertEquals(
            WakeDetectionAction.IGNORE,
            resolveWakeDetectionAction(OrchestratorMode.PAUSED_CALL_OR_SLEEP)
        )
    }
}
