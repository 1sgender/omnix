package com.omnix.assistant.domain.models

import com.omnix.assistant.agent.decision.ExecutionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило бейджа «обработано на устройстве» (audit 2026-09-26): локальные
 * пути (команда инструмента, офлайн-слой) — да; облако и агент — «норма»
 * без бейджа; неизвестный путь — без бейджа.
 */
class HandledOnDeviceTest {

    @Test
    fun `local paths carry the on-device badge`() {
        assertTrue(ExecutionType.DEVICE_TOOL.handledOnDevice)
        assertTrue(ExecutionType.LOCAL_AI.handledOnDevice)
    }

    @Test
    fun `cloud and agent are the unbadged norm`() {
        assertFalse(ExecutionType.CLOUD_AI.handledOnDevice)
        assertFalse(ExecutionType.AGENT.handledOnDevice)
    }

    @Test
    fun `unknown path is unbadged`() {
        assertFalse(null.handledOnDevice)
    }
}
