package com.omnix.assistant.agent.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Решение владельца 2026-10-21: 1.5B на телефонах с ~8 ГБ, 0.5B на остальных. */
class LocalModelSpecSelectionTest {

    @Test
    fun `phones with 8 GB and more get the 1_5B model`() {
        // Galaxy S24 Ultra (12 ГБ) отдаёт ~11 200 МБ, телефоны на 8 ГБ — ~7 300–7 600.
        listOf(11_200L, 7_400L, 7_000L).forEach { ram ->
            assertEquals("ram=$ram", LocalModelSpec.QWEN2_5_1_5B_INSTRUCT_Q8, LocalModelSpec.forTotalRam(ram))
        }
    }

    @Test
    fun `phones with 6 GB and less keep the 0_5B model`() {
        listOf(5_600L, 3_700L, 0L).forEach { ram ->
            assertEquals("ram=$ram", LocalModelSpec.QWEN2_5_0_5B_INSTRUCT_Q8, LocalModelSpec.forTotalRam(ram))
        }
    }

    @Test
    fun `models differ in file, id and integrity data, and use the same prompt format`() {
        val small = LocalModelSpec.QWEN2_5_0_5B_INSTRUCT_Q8
        val large = LocalModelSpec.QWEN2_5_1_5B_INSTRUCT_Q8
        assertNotEquals(small.fileName, large.fileName)
        assertNotEquals(small.modelId, large.modelId)
        assertNotEquals(small.expectedSha256, large.expectedSha256)
        assertEquals(64, large.expectedSha256.length)
        assertEquals(small.contextTokens, large.contextTokens)
        assertTrue(large.downloadUrl.endsWith(large.fileName))
        assertTrue(large.minRuntimeMemoryMb > small.minRuntimeMemoryMb)
        assertEquals(listOf(small, large), LocalModelSpec.ALL)
    }
}
