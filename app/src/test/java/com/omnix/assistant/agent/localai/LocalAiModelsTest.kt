package com.omnix.assistant.agent.localai

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAiModelsTest {

    @Test
    fun `summary without message is bare class name`() {
        assertEquals(
            "IllegalStateException",
            throwableSummary(IllegalStateException())
        )
    }

    @Test
    fun `summary keeps class and first non-blank line`() {
        assertEquals(
            "RuntimeException: Failed to open zip archive",
            throwableSummary(
                RuntimeException("Failed to open zip archive\n  at native (engine.cc:42)")
            )
        )
    }

    @Test
    fun `summary skips blank leading lines and trims`() {
        assertEquals(
            "RuntimeException: internal error",
            throwableSummary(RuntimeException("\n\n   internal error  "))
        )
    }

    @Test
    fun `summary is capped at max chars`() {
        val long = RuntimeException("x".repeat(400))

        // "RuntimeException: " (18) + 142 символа + "..." = 163.
        assertEquals(163, throwableSummary(long).length)
        assertEquals(
            "RuntimeException: " + "x".repeat(142) + "...",
            throwableSummary(long)
        )
    }
}
