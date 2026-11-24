package com.omnix.assistant.agent.tools.accessibility

import com.omnix.assistant.agent.tools.accessibility.AccessibilityEnablement.Oem
import com.omnix.assistant.agent.tools.accessibility.AccessibilityEnablement.Status
import com.omnix.assistant.agent.tools.accessibility.AccessibilityEnablement.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityEnablementTest {

    private val pkg = "com.omnix.assistant"
    private val cls = "com.omnix.assistant.agent.tools.accessibility.OmnixAccessibilityService"

    @Test
    fun `empty or null setting means service is off`() {
        assertFalse(AccessibilityEnablement.isServiceEnabled(null, pkg, cls))
        assertFalse(AccessibilityEnablement.isServiceEnabled("", pkg, cls))
        assertFalse(AccessibilityEnablement.isServiceEnabled("   ", pkg, cls))
    }

    @Test
    fun `full component name is recognised`() {
        assertTrue(AccessibilityEnablement.isServiceEnabled("$pkg/$cls", pkg, cls))
    }

    @Test
    fun `short component name with leading dot is recognised`() {
        val short = "$pkg/.agent.tools.accessibility.OmnixAccessibilityService"
        assertTrue(AccessibilityEnablement.isServiceEnabled(short, pkg, cls))
    }

    @Test
    fun `service is found among several others and case is ignored`() {
        val setting = "com.other/com.other.Svc:${"$pkg/$cls".uppercase()}:com.third/.X"
        assertTrue(AccessibilityEnablement.isServiceEnabled(setting, pkg, cls))
    }

    @Test
    fun `other services and lookalike packages do not count`() {
        assertFalse(AccessibilityEnablement.isServiceEnabled("com.other/com.other.Svc", pkg, cls))
        assertFalse(AccessibilityEnablement.isServiceEnabled("com.omnix.assistant2/$cls", pkg, cls))
        assertFalse(AccessibilityEnablement.isServiceEnabled("$pkg/com.omnix.assistant.Other", pkg, cls))
        assertFalse(AccessibilityEnablement.isServiceEnabled("garbage-without-slash", pkg, cls))
    }

    @Test
    fun `enabled service wins over any install source`() {
        val status = AccessibilityEnablement.status("$pkg/$cls", pkg, cls, sdkInt = 35, installerPackage = null)
        assertEquals(Status.ENABLED, status)
    }

    @Test
    fun `sideloaded app on android 13 plus is likely restricted`() {
        assertEquals(
            Status.DISABLED_RESTRICTED_LIKELY,
            AccessibilityEnablement.status(null, pkg, cls, sdkInt = 33, installerPackage = null)
        )
        assertEquals(
            Status.DISABLED_RESTRICTED_LIKELY,
            AccessibilityEnablement.status("", pkg, cls, sdkInt = 36, installerPackage = "com.google.android.packageinstaller")
        )
    }

    @Test
    fun `app installed from play store is not treated as restricted`() {
        assertEquals(
            Status.DISABLED,
            AccessibilityEnablement.status(null, pkg, cls, sdkInt = 35, installerPackage = "com.android.vending")
        )
    }

    @Test
    fun `before android 13 there is no restricted settings`() {
        assertEquals(
            Status.DISABLED,
            AccessibilityEnablement.status(null, pkg, cls, sdkInt = 32, installerPackage = null)
        )
    }

    @Test
    fun `current step follows the status`() {
        assertEquals(Step.DONE, AccessibilityEnablement.currentStep(Status.ENABLED))
        assertEquals(Step.UNLOCK_RESTRICTED, AccessibilityEnablement.currentStep(Status.DISABLED_RESTRICTED_LIKELY))
        assertEquals(Step.ENABLE_SERVICE, AccessibilityEnablement.currentStep(Status.DISABLED))
    }

    @Test
    fun `blocked launch hint is offered only while the service is off`() {
        assertFalse(AccessibilityEnablement.shouldSuggestInBlockedLaunch(Status.ENABLED))
        assertTrue(AccessibilityEnablement.shouldSuggestInBlockedLaunch(Status.DISABLED))
        assertTrue(AccessibilityEnablement.shouldSuggestInBlockedLaunch(Status.DISABLED_RESTRICTED_LIKELY))
    }

    @Test
    fun `manufacturer maps to the matching hint variant`() {
        assertEquals(Oem.SAMSUNG, AccessibilityEnablement.oemFor("samsung"))
        assertEquals(Oem.SAMSUNG, AccessibilityEnablement.oemFor("  Samsung "))
        assertEquals(Oem.PIXEL, AccessibilityEnablement.oemFor("Google"))
        assertEquals(Oem.XIAOMI, AccessibilityEnablement.oemFor("Xiaomi"))
        assertEquals(Oem.XIAOMI, AccessibilityEnablement.oemFor("POCO"))
        assertEquals(Oem.OTHER, AccessibilityEnablement.oemFor("OnePlus"))
        assertEquals(Oem.OTHER, AccessibilityEnablement.oemFor(null))
    }
}
