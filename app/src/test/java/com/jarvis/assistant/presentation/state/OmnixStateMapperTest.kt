package com.jarvis.assistant.presentation.state

import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.domain.models.VoiceAssistantState
import com.jarvis.assistant.voice.orchestrator.OrchestratorMode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Success must be PRODUCED, not just defined: a successful tool result ends in
 * [OmnixPhase.Success], a failed one in [OmnixPhase.Error] — and nowhere else.
 */
class OmnixStateMapperTest {

    private fun call() = ToolCall(
        toolId = "productivity.alarm_timer",
        arguments = buildJsonObject { put("time", "7:00") }
    )

    @Test
    fun `successful tool result after TTS produces Success with executor summary`() {
        val phase = OmnixStateMapper.phaseOf(
            assistantState = VoiceAssistantState.Idle,
            mode = OrchestratorMode.CONTINUOUS_CONVERSATION,
            pendingCall = call(),
            isOnline = true,
            toolResult = ToolExecutionResult.success("Alarm set for 7:00")
        )

        assertTrue(phase is OmnixPhase.Success)
        assertEquals("Alarm set for 7:00", (phase as OmnixPhase.Success).message)
    }

    @Test
    fun `blank executor summary produces Success with empty message for UI fallback`() {
        val phase = OmnixStateMapper.phaseOf(
            assistantState = VoiceAssistantState.Idle,
            mode = OrchestratorMode.CONTINUOUS_CONVERSATION,
            pendingCall = call(),
            isOnline = true,
            toolResult = ToolExecutionResult.success("   ")
        )

        assertTrue(phase is OmnixPhase.Success)
        assertEquals("", (phase as OmnixPhase.Success).message)
    }

    @Test
    fun `failed tool result produces Error, never Success`() {
        val expectations = mapOf(
            ToolExecutionResult.failure("call failed", "TIMEOUT") to SystemStateType.ACTION_FAILED,
            ToolExecutionResult.timeout("communication.call", 5_000) to
                SystemStateType.SERVICE_UNREACHABLE,
            ToolExecutionResult.permissionRequired("need mic", listOf("MIC")) to
                SystemStateType.PERMISSION_REQUIRED,
            ToolExecutionResult.userActionRequired("open settings", "SYSTEM_UI") to
                SystemStateType.USER_ACTION_REQUIRED,
            ToolExecutionResult.unsupported("no BT", "NO_BT") to
                SystemStateType.CAPABILITY_UNAVAILABLE
        )
        expectations.forEach { (result, expected) ->
            val phase = OmnixStateMapper.phaseOf(
                assistantState = VoiceAssistantState.Idle,
                mode = OrchestratorMode.CONTINUOUS_CONVERSATION,
                pendingCall = call(),
                isOnline = true,
                toolResult = result
            )
            assertTrue("$result must be Error", phase is OmnixPhase.Error)
            assertEquals(expected, (phase as OmnixPhase.Error).systemState)
        }
    }

    @Test
    fun `cancelled tool result falls back to Idle instead of Error`() {
        val phase = OmnixStateMapper.phaseOf(
            assistantState = VoiceAssistantState.Idle,
            mode = OrchestratorMode.CONTINUOUS_CONVERSATION,
            pendingCall = call(),
            isOnline = true,
            toolResult = ToolExecutionResult(
                status = ToolExecutionStatus.CANCELLED,
                summary = "cancelled"
            )
        )

        assertEquals(OmnixPhase.Idle, phase)
    }

    @Test
    fun `Success is not produced while TTS is still speaking`() {
        val phase = OmnixStateMapper.phaseOf(
            assistantState = VoiceAssistantState.Speaking("Alarm set, sir."),
            mode = OrchestratorMode.TTS_SPEAKING,
            pendingCall = call(),
            isOnline = true,
            toolResult = ToolExecutionResult.success("Alarm set for 7:00")
        )

        assertTrue(phase is OmnixPhase.Speaking)
    }

    @Test
    fun `Success is not produced in standby even with a stale result`() {
        val phase = OmnixStateMapper.phaseOf(
            assistantState = VoiceAssistantState.Idle,
            mode = OrchestratorMode.STANDBY_WAKE_WORD,
            pendingCall = call(),
            isOnline = true,
            toolResult = ToolExecutionResult.success("Alarm set for 7:00")
        )

        assertEquals(OmnixPhase.Idle, phase)
    }

    @Test
    fun `no tool result stays Idle in the follow-up window`() {
        val phase = OmnixStateMapper.phaseOf(
            assistantState = VoiceAssistantState.Idle,
            mode = OrchestratorMode.CONTINUOUS_CONVERSATION,
            pendingCall = null,
            isOnline = true,
            toolResult = null
        )

        assertEquals(OmnixPhase.Idle, phase)
    }

    @Test
    fun `executing still wins while the tool is running`() {
        val phase = OmnixStateMapper.phaseOf(
            assistantState = VoiceAssistantState.Thinking,
            mode = OrchestratorMode.AI_THINKING,
            pendingCall = call(),
            isOnline = true,
            toolResult = null
        )

        assertTrue(phase is OmnixPhase.Executing)
    }
}
