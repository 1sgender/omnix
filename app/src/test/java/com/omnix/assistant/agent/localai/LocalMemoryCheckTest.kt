package com.omnix.assistant.agent.localai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMemoryCheckTest {

    @Test
    fun `big phone with busy memory still loads the model`() {
        // S24 Ultra, 2026-11-27: 12 ГБ всего, 2943 МБ свободно, модель 1.5B просит 3072 МБ.
        assertTrue(LocalMemoryCheck.enough(totalMb = 11_200, availableMb = 2_943, lowMemory = false, requiredMb = 3_072))
    }

    @Test
    fun `enough free memory is always fine`() {
        assertTrue(LocalMemoryCheck.enough(totalMb = 4_000, availableMb = 2_000, lowMemory = true, requiredMb = 1_536))
    }

    @Test
    fun `system low memory blocks when free memory is short`() {
        assertFalse(LocalMemoryCheck.enough(totalMb = 11_200, availableMb = 900, lowMemory = true, requiredMb = 3_072))
    }

    @Test
    fun `small phone without free memory is refused`() {
        assertFalse(LocalMemoryCheck.enough(totalMb = 5_600, availableMb = 1_000, lowMemory = false, requiredMb = 3_072))
    }
}
