package com.omnix.assistant.core.license

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Клиентский enforcement тарифа: boolean-гейты, дневные UTC-счётчики,
 * лимиты автоматизаций/клипов. Чистый JVM-тест (MockK + фейковые prefs).
 */
class ClientPlanGateTest {

    private class FakeEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(k: String, v: String?): SharedPreferences.Editor = apply { map[k] = v }
        override fun putStringSet(k: String, v: Set<String>?): SharedPreferences.Editor =
            apply { map[k] = v }
        override fun putInt(k: String, v: Int): SharedPreferences.Editor = apply { map[k] = v }
        override fun putLong(k: String, v: Long): SharedPreferences.Editor = apply { map[k] = v }
        override fun putFloat(k: String, v: Float): SharedPreferences.Editor = apply { map[k] = v }
        override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor =
            apply { map[k] = v }
        override fun remove(k: String): SharedPreferences.Editor = apply { map.remove(k) }
        override fun clear(): SharedPreferences.Editor = apply { map.clear() }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }

    private class FakePrefs(private val map: MutableMap<String, Any?> = mutableMapOf()) :
        SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(k: String, d: String?): String? = map[k] as? String ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String, d: Set<String>?): Set<String>? =
            map[k] as? Set<String> ?: d
        override fun getInt(k: String, d: Int): Int = map[k] as? Int ?: d
        override fun getLong(k: String, d: Long): Long = map[k] as? Long ?: d
        override fun getFloat(k: String, d: Float): Float = map[k] as? Float ?: d
        override fun getBoolean(k: String, d: Boolean): Boolean = map[k] as? Boolean ?: d
        override fun contains(k: String): Boolean = map.containsKey(k)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
            Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
            Unit
    }

    private class FakeLicenseManager(var entitlements: PlanEntitlements) : LicenseManager {
        override val licenseFlow: Flow<LicenseInfo> = MutableStateFlow(getLicenseInfo())
        override fun getLicenseInfo(): LicenseInfo =
            LicenseInfo(isActivated = true, entitlements = entitlements)
        override fun isActivatedAndValid(): Boolean = true
        override suspend fun refreshFromServer(): LicenseRefreshResult =
            LicenseRefreshResult.ServiceUnavailable
        override suspend fun activateWithCode(code: String): ActivationResult =
            ActivationResult.ServiceUnavailable("fake")
    }

    private fun gate(
        entitlements: PlanEntitlements = PlanEntitlements.FREE,
        nowUtc: String = "2026-09-13T10:00:00Z"
    ): ClientPlanGate {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns FakePrefs()
        return ClientPlanGate(context, FakeLicenseManager(entitlements)).also {
            it.clock = Clock.fixed(Instant.parse(nowUtc), ZoneOffset.UTC)
        }
    }

    @Test
    fun `free blocks screen reading and ui control`() {
        val gate = gate()
        assertFalse(gate.isToolAllowed("accessibility.screen_reader"))
        assertFalse(gate.isToolAllowed("accessibility.ui_click"))
        assertFalse(gate.isToolAllowed("accessibility.type_text"))
        // Остальные tools гейтов не имеют.
        assertTrue(gate.isToolAllowed("intelligence.web_search"))
        assertTrue(gate.isToolAllowed("productivity.ear_briefing"))
    }

    @Test
    fun `paid allows screen reading and ui control`() {
        val paid = PlanEntitlements.FREE.copy(screenReading = true, uiControl = true)
        val gate = gate(paid)
        assertTrue(gate.isToolAllowed("accessibility.screen_reader"))
        assertTrue(gate.isToolAllowed("accessibility.ui_click"))
        assertTrue(gate.isToolAllowed("accessibility.type_text"))
    }

    @Test
    fun `consume exhausts daily quota at boundary`() {
        val gate = gate() // FREE: 5 agent actions
        repeat(5) { assertTrue(gate.tryConsume(ClientQuotaFeature.AGENT_ACTIONS)) }
        assertFalse(gate.tryConsume(ClientQuotaFeature.AGENT_ACTIONS))
        assertEquals(0, gate.remaining(ClientQuotaFeature.AGENT_ACTIONS))
    }

    @Test
    fun `buckets are independent`() {
        val gate = gate()
        repeat(5) { gate.tryConsume(ClientQuotaFeature.AGENT_ACTIONS) }
        // Агент исчерпан, перевод свободен.
        assertTrue(gate.tryConsume(ClientQuotaFeature.TRANSLATION_UNITS))
    }

    @Test
    fun `quota resets on utc day rollover`() {
        val gate = gate()
        repeat(5) { gate.tryConsume(ClientQuotaFeature.AGENT_ACTIONS) }
        assertEquals(0, gate.remaining(ClientQuotaFeature.AGENT_ACTIONS))
        gate.clock = Clock.fixed(Instant.parse("2026-09-14T00:00:01Z"), ZoneOffset.UTC)
        assertEquals(5, gate.remaining(ClientQuotaFeature.AGENT_ACTIONS))
        assertTrue(gate.tryConsume(ClientQuotaFeature.AGENT_ACTIONS))
    }

    @Test
    fun `translation units scale with text length`() {
        val gate = gate()
        assertEquals(1, gate.translationUnitsFor("hi"))
        assertEquals(1, gate.translationUnitsFor("x".repeat(1000)))
        assertEquals(2, gate.translationUnitsFor("x".repeat(1001)))
        assertEquals(3, gate.translationUnitsFor("x".repeat(2500)))
    }

    @Test
    fun `multi-unit consume fails atomically when exceeding`() {
        val gate = gate() // FREE: 15 translation units
        assertTrue(gate.tryConsume(ClientQuotaFeature.TRANSLATION_UNITS, 15))
        assertFalse(gate.tryConsume(ClientQuotaFeature.TRANSLATION_UNITS, 2))
        assertEquals(0, gate.remaining(ClientQuotaFeature.TRANSLATION_UNITS))
    }

    @Test
    fun `automation limit respects plan`() {
        val gate = gate() // FREE: 3
        assertTrue(gate.canCreateAutomation(2))
        assertFalse(gate.canCreateAutomation(3))
        val unlimited = gate(PlanEntitlements.FREE.copy(maxAutomations = Int.MAX_VALUE))
        assertTrue(unlimited.canCreateAutomation(10_000))
    }

    @Test
    fun `clip bind limit respects plan`() {
        val gate = gate() // FREE: 1
        assertTrue(gate.canBindClip(0))
        assertFalse(gate.canBindClip(1))
    }
}
