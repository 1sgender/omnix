package com.jarvis.assistant.presentation.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timing contract: Success is held for ~1 second ([OmnixSuccessLatch.DISPLAY_MS])
 * and then settles to Idle — while the underlying voice signals keep deriving
 * Success for the whole follow-up window.
 */
class OmnixSuccessLatchTest {

    private var nowMs = 10_000L
    private fun latch() = OmnixSuccessLatch(clock = { nowMs })

    @Test
    fun `success is held for the display window then settles to Idle`() {
        val latch = latch()
        val success = OmnixPhase.Success("Alarm set for 7:00")

        assertEquals(success, latch.resolve(success))

        nowMs += OmnixSuccessLatch.DISPLAY_MS - 1
        assertEquals(success, latch.resolve(success))

        nowMs += 1
        assertEquals(OmnixPhase.Idle, latch.resolve(success))
    }

    @Test
    fun `expired success never resurrects while signals are unchanged`() {
        val latch = latch()
        val success = OmnixPhase.Success("Alarm set for 7:00")
        latch.resolve(success)

        nowMs += OmnixSuccessLatch.DISPLAY_MS + 5_000
        repeat(5) {
            assertEquals(OmnixPhase.Idle, latch.resolve(success))
        }
    }

    @Test
    fun `any other phase resets the latch for the next success`() {
        val latch = latch()
        latch.resolve(OmnixPhase.Success("first"))
        nowMs += OmnixSuccessLatch.DISPLAY_MS + 1
        assertEquals(OmnixPhase.Idle, latch.resolve(OmnixPhase.Success("first")))

        // A new interaction cycle passes through other phases first.
        assertEquals(OmnixPhase.Thinking, latch.resolve(OmnixPhase.Thinking))

        val second = OmnixPhase.Success("second")
        assertEquals(second, latch.resolve(second))
        nowMs += OmnixSuccessLatch.DISPLAY_MS - 1
        assertEquals(second, latch.resolve(second))
    }

    @Test
    fun `identical consecutive success after reset opens a fresh window`() {
        val latch = latch()
        val success = OmnixPhase.Success("Done")
        latch.resolve(success)
        nowMs += OmnixSuccessLatch.DISPLAY_MS + 1
        latch.resolve(success)

        latch.resolve(OmnixPhase.Listening)
        assertEquals(success, latch.resolve(success))
    }

    @Test
    fun `non-success phases pass through untouched`() {
        val latch = latch()

        assertEquals(OmnixPhase.Thinking, latch.resolve(OmnixPhase.Thinking))
        assertEquals(OmnixPhase.Listening, latch.resolve(OmnixPhase.Listening))
        val error = OmnixPhase.Error(SystemStateType.ACTION_FAILED)
        assertEquals(error, latch.resolve(error))
        val speaking = OmnixPhase.Speaking("hi")
        assertEquals(speaking, latch.resolve(speaking))
    }

    @Test
    fun `error is never latched to idle by time alone`() {
        val latch = latch()
        val error = OmnixPhase.Error(SystemStateType.ACTION_FAILED)

        assertEquals(error, latch.resolve(error))
        nowMs += OmnixSuccessLatch.DISPLAY_MS * 10
        assertTrue(latch.resolve(error) is OmnixPhase.Error)
    }
}
