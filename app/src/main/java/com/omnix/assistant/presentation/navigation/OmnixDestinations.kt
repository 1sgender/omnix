package com.omnix.assistant.presentation.navigation

/**
 * The OMNIX destination map (§20, §44, §66).
 *
 * Three primary tabs (owner decision 2026-10-21):
 *
 * ```
 * Home  |  Chat  |  Me
 * ```
 *
 * Everything else — Headphones, Translator, Privacy, Automations, the
 * settings pages, the first-run flow — is reached from one of those three. Secondary sections
 * never compete with the Core for attention (§45).
 */
sealed class OmnixDestination(val route: String) {

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

    /** Secondary: what OMNIX remembers about the user (owner report on v344). */
    data object Memory : OmnixDestination("omnix/memory")

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
        /**
         * The three destinations in the navigation bar (owner decision
         * 2026-10-21): Home · Chat · Me. Headphones moved to a Me row; their
         * status stays on Home, and the Home pill opens the same screen.
         *
         * A getter, not a stored value: the objects are subclasses of this
         * sealed class, so a list built during class initialisation can pick
         * up a still-null object when one of them is the first to be touched.
         */
        val primary: List<OmnixDestination>
            get() = listOf(Home, Chat, Me)
    }
}

/**
 * The primary tab a route belongs to (mock 2026-09-25: the active tab is
 * white + bold, the rest dimmed — "where am I" in one glance).
 *
 * Secondary screens keep their PARENT tab lit, the iOS reading: the
 * Translator mode keeps Home lit; everything reached from Me (history,
 * privacy, settings sections) keeps Me lit. A route that belongs to no tab
 * (first run, none) lights nothing.
 */
fun tabForRoute(route: String?): OmnixDestination? = when (route) {
    OmnixDestination.Home.route,
    OmnixDestination.Translator.route -> OmnixDestination.Home
    OmnixDestination.Chat.route -> OmnixDestination.Chat
    OmnixDestination.Me.route,
    OmnixDestination.Devices.route,
    OmnixDestination.Privacy.route,
    OmnixDestination.Memory.route,
    OmnixDestination.Automations.route -> OmnixDestination.Me
    else -> route
        ?.takeIf { it.startsWith(OmnixDestination.SettingsSection.ROUTE_PREFIX) }
        ?.let { OmnixDestination.Me }
}
