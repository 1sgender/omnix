package com.omnix.assistant.presentation.voice

import com.omnix.assistant.voice.orchestrator.OrchestratorMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePhaseTest {

    @Test
    fun `until the service is up the screen says it is starting`() {
        OrchestratorMode.entries.forEach { mode ->
            assertEquals(VoicePhase.STARTING, voicePhaseOf(serviceActive = false, mode = mode))
        }
    }

    @Test
    fun `each assistant mode maps to what the screen shows`() {
        val expected = mapOf(
            OrchestratorMode.STANDBY_WAKE_WORD to VoicePhase.IDLE,
            OrchestratorMode.LISTENING_USER_QUERY to VoicePhase.LISTENING,
            OrchestratorMode.CONTINUOUS_CONVERSATION to VoicePhase.LISTENING,
            OrchestratorMode.AI_THINKING to VoicePhase.THINKING,
            OrchestratorMode.TTS_SPEAKING to VoicePhase.SPEAKING,
            OrchestratorMode.AWAITING_CONFIRMATION to VoicePhase.CONFIRMING,
            OrchestratorMode.AWAITING_PRIVACY_CONSENT to VoicePhase.CONFIRMING,
            OrchestratorMode.LIVE_EAR_INTERPRETER to VoicePhase.IDLE,
            OrchestratorMode.PAUSED_CALL_OR_SLEEP to VoicePhase.IDLE
        )
        assertEquals(OrchestratorMode.entries.toSet(), expected.keys)
        expected.forEach { (mode, phase) ->
            assertEquals("mode $mode", phase, voicePhaseOf(serviceActive = true, mode = mode))
        }
    }
}
