package com.omnix.assistant.agent.tools.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLaunchPolicyTest {

    @Test
    fun `blocked on android 10 plus when app is hidden and no overlay`() {
        assertTrue(BackgroundLaunchPolicy.isBlocked(sdkInt = 36, appVisible = false, canDrawOverlays = false))
    }

    @Test
    fun `allowed when app window is visible`() {
        assertFalse(BackgroundLaunchPolicy.isBlocked(36, appVisible = true, canDrawOverlays = false))
    }

    @Test
    fun `allowed with overlay permission`() {
        assertFalse(BackgroundLaunchPolicy.isBlocked(34, appVisible = false, canDrawOverlays = true))
    }

    @Test
    fun `old android does not restrict background launch`() {
        assertFalse(BackgroundLaunchPolicy.isBlocked(28, appVisible = false, canDrawOverlays = false))
    }
}
