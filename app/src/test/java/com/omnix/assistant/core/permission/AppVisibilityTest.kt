package com.omnix.assistant.core.permission

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityTest {

    @After
    fun tearDown() = AppVisibility.resetForTest()

    @Test
    fun `visible while at least one activity is started`() {
        assertFalse(AppVisibility.isForeground)
        AppVisibility.onActivityStarted()
        AppVisibility.onActivityStarted()
        AppVisibility.onActivityStopped()
        assertTrue(AppVisibility.isForeground)
        AppVisibility.onActivityStopped()
        assertFalse(AppVisibility.isForeground)
    }

    @Test
    fun `extra stop never goes negative`() {
        AppVisibility.onActivityStopped()
        AppVisibility.onActivityStarted()
        assertTrue(AppVisibility.isForeground)
    }
}
