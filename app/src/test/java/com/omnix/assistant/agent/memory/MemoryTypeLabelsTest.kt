package com.omnix.assistant.agent.memory

import com.omnix.assistant.agent.memory.model.MemoryTypeLabel
import com.omnix.assistant.agent.memory.model.memoryTypeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Токены типов воспоминаний для браузера памяти (блок 4 плана пересборки
 * фронта, 2026-09-26). Инвариант: колонка Room пишется как MemoryType.name;
 * любое другое значение — OTHER, не краш.
 */
class MemoryTypeLabelsTest {

    @Test
    fun `all four real memory types map to their tokens`() {
        assertEquals(MemoryTypeLabel.FACT, memoryTypeLabel("FACT"))
        assertEquals(MemoryTypeLabel.PREFERENCE, memoryTypeLabel("PREFERENCE"))
        assertEquals(MemoryTypeLabel.EPISODIC, memoryTypeLabel("EPISODIC"))
        assertEquals(MemoryTypeLabel.PROCEDURAL, memoryTypeLabel("PROCEDURAL"))
    }

    @Test
    fun `unknown or broken values fall back to OTHER`() {
        assertEquals(MemoryTypeLabel.OTHER, memoryTypeLabel(null))
        assertEquals(MemoryTypeLabel.OTHER, memoryTypeLabel(""))
        assertEquals(MemoryTypeLabel.OTHER, memoryTypeLabel("fact"))
        assertEquals(MemoryTypeLabel.OTHER, memoryTypeLabel("SOMETHING_ELSE"))
    }
}
