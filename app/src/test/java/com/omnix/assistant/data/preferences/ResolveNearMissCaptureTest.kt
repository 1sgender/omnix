package com.omnix.assistant.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гейт near-miss захвата (данные v0.2): запись возможна только при явном
 * включении пользователя И dev/staging-сборке. В prod (flavorAllowed=false)
 * — никогда, даже при устаревшем true в DataStore.
 */
class ResolveNearMissCaptureTest {

    @Test
    fun explicitEnableInAllowedFlavor() {
        assertTrue(resolveNearMissCapture(stored = true, flavorAllowed = true))
    }

    @Test
    fun prodFlavorNeverCaptures() {
        assertFalse("prod: даже явное true в DataStore не включает запись",
            resolveNearMissCapture(stored = true, flavorAllowed = false))
        assertFalse(resolveNearMissCapture(stored = null, flavorAllowed = false))
    }

    @Test
    fun defaultIsOffWithoutExplicitEnable() {
        assertFalse(resolveNearMissCapture(stored = null, flavorAllowed = true))
        assertFalse(resolveNearMissCapture(stored = false, flavorAllowed = true))
    }
}
