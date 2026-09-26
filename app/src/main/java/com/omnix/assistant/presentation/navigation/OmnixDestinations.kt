package com.omnix.assistant.presentation.navigation

/**
 * The OMNIX destination map (§20, §44, §66).
 *
 * Three primary destinations, with the OMNIX atom mark over the "Home"
 * label in the centre (mock 2026-09-26 — the wordmark used to duplicate
 * the chat's sender labels):
 *
 * ```
 * History  |  ◎ Home  |  Me
 * ```
 *
 * Everything else — Chat, Translator, Devices, Privacy, the first-run flow —
 * is reached from one of those three. Secondary sections never compete with
 * the Core for attention (§45).
 */
sealed class OmnixDestination(val route: String) {

    /** Primary: the past. Conversations and completed actions. */
    data object History : OmnixDestination("omnix/history")

    /** Primary: the present. Home — presence and orientation. */
    data object Home : OmnixDestination("omnix/home")

    /** Primary: the user. Settings, devices, privacy, account. */
    data object Me : OmnixDestination("omnix/me")

    /** Secondary: text conversation, deliberately not the main surface (§41). */
    data object Chat : OmnixDestination("omnix/chat")

    /** Secondary: translation as a mode, not a separate app (§43). */
    data object Translator : OmnixDestination("omnix/translator")

    /** Secondary: the Clip and other devices (§40). */
    data object Devices : OmnixDestination("omnix/devices")

    /**
     * Secondary: automation rules — list, toggle, delete, briefing now
     * (gap report 2026-09-26). Reached from the Voice settings chevron;
     * belongs to the Me subtree.
     */
    data object Automations : OmnixDestination("omnix/automations")

    /** Secondary: what OMNIX keeps and where it goes (§42, §52). */
    data object Privacy : OmnixDestination("omnix/privacy")

    /** Settings sub-pages, addressed by a human concept (§42). */
    data class SettingsSection(val section: String) :
        OmnixDestination("omnix/settings/$section") {
        companion object {
            const val ROUTE_PREFIX = "omnix/settings/"
            const val ROUTE_PATTERN = ROUTE_PREFIX + "{section}"
            const val ARG_SECTION = "section"
        }
    }

    /** The first-run flow (§34, §67). */
    data object FirstRun : OmnixDestination("omnix/first-run")

    companion object {
        /** The three destinations that appear in the navigation bar. */
        val primary = listOf(History, Home, Me)
    }
}

/**
 * The primary tab a route belongs to (mock 2026-09-25: the active tab is
 * white + bold, the rest dimmed — "where am I" in one glance).
 *
 * Secondary screens keep their PARENT tab lit, the iOS reading: OMNIX's
 * modes — Chat, Translator — keep the centre OMNIX tab lit; everything
 * reached from Me (devices, privacy, settings sections) keeps Me lit. A
 * route that belongs to no tab (first run, none) lights nothing.
 */
fun tabForRoute(route: String?): OmnixDestination? = when (route) {
    OmnixDestination.History.route -> OmnixDestination.History
    OmnixDestination.Home.route,
    OmnixDestination.Chat.route,
    OmnixDestination.Translator.route -> OmnixDestination.Home
    OmnixDestination.Me.route,
    OmnixDestination.Devices.route,
    OmnixDestination.Privacy.route,
    OmnixDestination.Automations.route -> OmnixDestination.Me
    else -> route
        ?.takeIf { it.startsWith(OmnixDestination.SettingsSection.ROUTE_PREFIX) }
        ?.let { OmnixDestination.Me }
}
