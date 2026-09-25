package com.omnix.assistant.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Вкладка-«владелица» маршрута (мок 2026-09-25): активный пункт в навбаре —
 * белый и жирный, остальные приглушены; под-экраны держат вкладку родителя.
 */
class NavTabMappingTest {

    @Test
    fun `primary routes map to themselves`() {
        assertEquals(OmnixDestination.History, tabForRoute(OmnixDestination.History.route))
        assertEquals(OmnixDestination.Home, tabForRoute(OmnixDestination.Home.route))
        assertEquals(OmnixDestination.Me, tabForRoute(OmnixDestination.Me.route))
    }

    @Test
    fun `omnix modes keep the centre tab lit`() {
        assertEquals(OmnixDestination.Home, tabForRoute(OmnixDestination.Translator.route))
        assertEquals(OmnixDestination.Home, tabForRoute(OmnixDestination.Chat.route))
    }

    @Test
    fun `me sub screens keep me lit`() {
        assertEquals(OmnixDestination.Me, tabForRoute(OmnixDestination.Devices.route))
        assertEquals(OmnixDestination.Me, tabForRoute(OmnixDestination.Privacy.route))
        assertEquals(OmnixDestination.Me, tabForRoute("omnix/settings/voice"))
        assertEquals(OmnixDestination.Me, tabForRoute("omnix/settings/appearance"))
    }

    @Test
    fun `unknown or absent routes light nothing`() {
        assertNull(tabForRoute(null))
        assertNull(tabForRoute("omnix/first-run"))
        assertNull(tabForRoute("somewhere/else"))
    }
}
