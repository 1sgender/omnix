package com.omnix.assistant.presentation.state

import com.omnix.assistant.agent.model.ToolCall
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.domain.models.VoiceAssistantState
import com.omnix.assistant.voice.orchestrator.OrchestratorMode

/**
 * Derives the single [OmnixUiState] phase from the voice layer's own state
 * (§14, §60).
 *
 * The orchestrator remains the source of truth; this mapper only translates.
 * Because there is exactly one translation point, the UI cannot drift away
 * from the voice pipeline.
 */
object OmnixStateMapper {

    /**
     * @param assistantState the orchestrator's assistant state
     * @param mode           the orchestrator's mode, which distinguishes cases
     *                       the assistant state alone cannot (executing versus
     *                       thinking, awaiting confirmation versus speaking)
     * @param pendingCall    the tool call in flight, if any
     * @param isOnline       real connectivity, used to phrase failures honestly
     * @param toolResult     result of the last executed tool call, if any —
     *                       the only source of [OmnixPhase.Success] and of
     *                       post-execution [OmnixPhase.Error]
     */
    fun phaseOf(
        assistantState: VoiceAssistantState,
        mode: OrchestratorMode,
        pendingCall: ToolCall?,
        isOnline: Boolean,
        toolResult: ToolExecutionResult? = null
    ): OmnixPhase {
        // Post-execution outcome. After TTS finishes speaking the result the
        // orchestrator parks in (Idle + CONTINUOUS_CONVERSATION) while the
        // last tool call/result are still attached. This branch is the ONLY
        // producer of Success — and of tool-failure Error
        // (Executing -> error -> Error). While TTS is still speaking, or in
        // standby, or without a result, the regular mapping below applies.
        if (assistantState is VoiceAssistantState.Idle &&
            mode == OrchestratorMode.CONTINUOUS_CONVERSATION &&
            pendingCall != null &&
            toolResult != null
        ) {
            postExecutionPhase(pendingCall, toolResult)?.let { return it }
        }
        return when (assistantState) {
            is VoiceAssistantState.Idle -> OmnixPhase.Idle

            is VoiceAssistantState.Listening -> OmnixPhase.Listening

            is VoiceAssistantState.Recognizing ->
                OmnixPhase.Recognizing(assistantState.partialText)

            is VoiceAssistantState.Thinking ->
                // The same assistant state covers "understanding the request" and
                // "running the tool"; the mode plus the pending call tell them
                // apart so the UI can name the action instead of saying
                // "Executing…" (§19 of the specification).
                if (pendingCall != null && mode == OrchestratorMode.AI_THINKING) {
                    OmnixPhase.Executing(ActionMapper.executing(pendingCall))
                } else {
                    OmnixPhase.Thinking
                }

            is VoiceAssistantState.Speaking -> OmnixPhase.Speaking(assistantState.answerText)

            is VoiceAssistantState.Error -> OmnixPhase.Error(
                classifyError(assistantState, isOnline)
            )
        }
    }

    /**
     * Outcome of a finished tool execution, translated through the same
     * [ActionMapper.completed] snapshot the action row uses — there is exactly
     * one status mapping, shared by the phase and the action UI.
     *
     * Returns null when there is no outcome to surface (cancelled is not an
     * error; a result asking for confirmation cannot happen post-execution):
     * the caller then falls through to the regular Idle mapping.
     */
    private fun postExecutionPhase(
        call: ToolCall,
        result: ToolExecutionResult
    ): OmnixPhase? {
        val snapshot = ActionMapper.completed(call, result)
        return when (snapshot.status) {
            ActionStatus.SUCCEEDED ->
                // The executor's own summary is the user-facing outcome
                // ("Alarm set for 7:00"); a blank summary renders through the
                // UI fallback (R.string.omnix_result_done), never as a code.
                OmnixPhase.Success(message = snapshot.result.orEmpty())

            ActionStatus.FAILED ->
                OmnixPhase.Error(snapshot.error ?: SystemStateType.ACTION_FAILED)

            ActionStatus.CANCELLED,
            ActionStatus.PENDING_CONFIRMATION,
            ActionStatus.EXECUTING -> null
        }
    }

    /**
     * Maps a voice-layer error onto a presentable system state.
     *
     * The message the voice layer produces is spoken aloud and is not a UI
     * string; the UI shows the copy attached to the [SystemStateType] instead,
     * so the user never sees an exception or an error code (§18, §50).
     */
    private fun classifyError(
        error: VoiceAssistantState.Error,
        isOnline: Boolean
    ): SystemStateType {
        val text = error.userFriendlyMessage.lowercase()
        return when {
            text.containsAny("наушник", "headset", "clip", "гарнитур") ->
                SystemStateType.CLIP_DISCONNECTED

            text.containsAny("микрофон", "microphone") ->
                SystemStateType.MICROPHONE_DENIED

            text.containsAny("интернет", "network", "сет") || !isOnline ->
                SystemStateType.NETWORK_UNAVAILABLE

            text.containsAny("не расслышал", "ничего не сказали", "didn't catch") ->
                SystemStateType.NOT_UNDERSTOOD

            else -> SystemStateType.ACTION_FAILED
        }
    }

    /**
     * A system condition that outranks the current phase and must be surfaced
     * on Home, or null when nothing is wrong.
     *
     * Order matters: the user is told about the thing that blocks them first.
     */
    fun systemStateOf(
        clip: ClipState,
        isOnline: Boolean,
        microphoneGranted: Boolean,
        accessExpired: Boolean,
        requireClip: Boolean
    ): SystemStateType? = when {
        !microphoneGranted -> SystemStateType.MICROPHONE_DENIED
        clip is ClipState.BluetoothOff && requireClip -> SystemStateType.BLUETOOTH_OFF
        requireClip && clip is ClipState.Disconnected -> SystemStateType.CLIP_DISCONNECTED
        accessExpired -> SystemStateType.ACCESS_EXPIRED
        !isOnline -> null // handled as a status, not a blocking error (§20)
        else -> null
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }
}
