package com.omnix.assistant.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Вкладка-«владелица» маршрута (мок 2026-09-25): активный пункт в навбаре —
 * белый и жирный, остальные приглушены; под-экраны держат вкладку родителя.
 * С 2026-10-21 вкладок три: Главная · Чат · Я; «Наушники» — строка в «Я».
 */
class NavTabMappingTest {

    @Test
    fun `primary routes map to themselves`() {
        assertEquals(OmnixDestination.Home, tabForRoute(OmnixDestination.Home.route))
        assertEquals(OmnixDestination.Chat, tabForRoute(OmnixDestination.Chat.route))
        assertEquals(OmnixDestination.Me, tabForRoute(OmnixDestination.Me.route))
    }

    @Test
    fun `the bar shows exactly the three primary tabs in order`() {
        assertEquals(
            listOf(
                OmnixDestination.Home,
                OmnixDestination.Chat,
                OmnixDestination.Me
            ),
            OmnixDestination.primary
        )
    }

    @Test
    fun `translator keeps home lit`() {
        assertEquals(OmnixDestination.Home, tabForRoute(OmnixDestination.Translator.route))
    }

    @Test
    fun `me sub screens keep me lit`() {
        assertEquals(OmnixDestination.Me, tabForRoute(OmnixDestination.Privacy.route))
        assertEquals(OmnixDestination.Me, tabForRoute(OmnixDestination.Devices.route))
        assertEquals(OmnixDestination.Me, tabForRoute(OmnixDestination.Automations.route))
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
