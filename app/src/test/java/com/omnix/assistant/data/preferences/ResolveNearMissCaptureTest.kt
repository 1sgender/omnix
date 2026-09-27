package com.omnix.assistant.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гейт near-miss захвата (данные v0.2): dev/staging — включено по умолчанию
 * (сбор датасета v0.2), явный выбор пользователя respected; в prod
 * (flavorAllowed=false) — никогда, даже при устаревшем true в DataStore.
 */
class ResolveNearMissCaptureTest {

    @Test
    fun explicitEnableInAllowedFlavor() {
        assertTrue(resolveNearMissCapture(stored = true, flavorAllowed = true))
    }

    @Test
    fun betaDefaultIsOnWithoutExplicitChoice() {
        // Решение владельца 2026-09-26: бету выпускать с записью включённой
        // (сбор датасета v0.2) — нетронутый ключ в dev/staging = true.
        assertTrue(resolveNearMissCapture(stored = null, flavorAllowed = true))
    }

    @Test
    fun explicitOptOutIsRespected() {
        // Отключивший запись пользователь не включается обратно дефолтом.
        assertFalse(resolveNearMissCapture(stored = false, flavorAllowed = true))
    }

    @Test
    fun prodFlavorNeverCaptures() {
        assertFalse("prod: даже явное true в DataStore не включает запись",
            resolveNearMissCapture(stored = true, flavorAllowed = false))
        assertFalse(resolveNearMissCapture(stored = null, flavorAllowed = false))
        assertFalse(resolveNearMissCapture(stored = false, flavorAllowed = false))
    }
}
