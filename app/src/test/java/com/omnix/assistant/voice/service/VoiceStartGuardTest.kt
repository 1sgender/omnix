package com.omnix.assistant.voice.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Системный перезапуск службы запрещён, только если прошлый запуск не выжил. */
class VoiceStartGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun guard() = VoiceStartGuard(tmp.root.resolve("voice/start_marker"))

    @Test
    fun `a fresh install allows a system restart`() {
        assertTrue(guard().systemRestartAllowed())
    }

    @Test
    fun `a start that did not survive blocks the next system restart`() {
        guard().attemptStarted()
        // The process died here: a new guard over the same file sees the marker.
        assertFalse(guard().systemRestartAllowed())
    }

    @Test
    fun `a start that survived or stopped cleanly allows restarts again`() {
        val g = guard()
        g.attemptStarted()
        g.cleared()
        assertTrue(guard().systemRestartAllowed())
    }
}
