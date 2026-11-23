package com.omnix.assistant.agent.tools.device

import com.omnix.assistant.agent.tools.device.BackgroundLaunchPolicy.Route
import com.omnix.assistant.agent.tools.device.BackgroundLaunchPolicy.Step
import com.omnix.assistant.agent.tools.device.BackgroundLaunchPolicy.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLaunchPolicyTest {

    @Test
    fun `no legal route on android 10 plus when hidden without overlay and accessibility`() {
        val plan = BackgroundLaunchPolicy.plan(36, appVisible = false, accessibilityConnected = false, canDrawOverlays = false)
        assertTrue("пустой план = backgroundBlocked: уведомление и честный ответ", plan.isEmpty())
    }

    @Test
    fun `visible app window launches directly`() {
        assertEquals(
            listOf(Route.DIRECT),
            BackgroundLaunchPolicy.plan(36, appVisible = true, accessibilityConnected = true, canDrawOverlays = true)
        )
    }

    @Test
    fun `old android does not restrict background launch`() {
        assertEquals(
            listOf(Route.DIRECT),
            BackgroundLaunchPolicy.plan(28, appVisible = false, accessibilityConnected = false, canDrawOverlays = false)
        )
    }

    @Test
    fun `overlay permission alone gives overlay route which shows a visible window first`() {
        assertEquals(
            listOf(Route.OVERLAY),
            BackgroundLaunchPolicy.plan(35, appVisible = false, accessibilityConnected = false, canDrawOverlays = true)
        )
    }

    @Test
    fun `accessibility is tried before overlay`() {
        assertEquals(
            listOf(Route.ACCESSIBILITY, Route.OVERLAY),
            BackgroundLaunchPolicy.plan(36, appVisible = false, accessibilityConnected = true, canDrawOverlays = true)
        )
        assertEquals(
            listOf(Route.ACCESSIBILITY),
            BackgroundLaunchPolicy.plan(36, appVisible = false, accessibilityConnected = true, canDrawOverlays = false)
        )
    }

    @Test
    fun `unconfirmed launch moves to the next route but direct is final`() {
        assertEquals(Step.TRY_NEXT, BackgroundLaunchPolicy.afterAttempt(Route.ACCESSIBILITY, Verification.NOT_CONFIRMED))
        assertEquals(Step.DONE, BackgroundLaunchPolicy.afterAttempt(Route.ACCESSIBILITY, Verification.CONFIRMED))
        assertEquals(Step.DONE, BackgroundLaunchPolicy.afterAttempt(Route.DIRECT, Verification.NOT_CONFIRMED))
    }

    @Test
    fun `overlay launch without any way to verify counts as done`() {
        assertEquals(Step.DONE, BackgroundLaunchPolicy.afterAttempt(Route.OVERLAY, Verification.UNKNOWN))
        assertEquals(Step.TRY_NEXT, BackgroundLaunchPolicy.afterAttempt(Route.OVERLAY, Verification.NOT_CONFIRMED))
    }
}
