package com.omnix.assistant.agent.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DataStore↔домен разбор настроек политики (блок «Privacy-органы управления»,
 * план пересборки фронта 2026-09-26). Инвариант: любое неизвестное/битое
 * значение читается как безопасный дефолт ALWAYS — парсинг настроек не может
 * открыть действие без подтверждения.
 */
class ActionPolicySerializationTest {

    @Test
    fun `known call policies round-trip by name`() {
        assertEquals(CallConfirmationPolicy.ALWAYS, parseCallPolicy("ALWAYS"))
        assertEquals(CallConfirmationPolicy.TRUSTED_ONLY, parseCallPolicy("TRUSTED_ONLY"))
        assertEquals(CallConfirmationPolicy.NEVER, parseCallPolicy("NEVER"))
    }

    @Test
    fun `unknown or missing call value falls back to ALWAYS`() {
        assertEquals(CallConfirmationPolicy.ALWAYS, parseCallPolicy(null))
        assertEquals(CallConfirmationPolicy.ALWAYS, parseCallPolicy(""))
        assertEquals(CallConfirmationPolicy.ALWAYS, parseCallPolicy("WHENEVER"))
        assertEquals(CallConfirmationPolicy.ALWAYS, parseCallPolicy("never"))
    }

    @Test
    fun `known messaging policies round-trip by name`() {
        assertEquals(MessagingConfirmationPolicy.ALWAYS, parseMessagingPolicy("ALWAYS"))
        assertEquals(MessagingConfirmationPolicy.MONEY_ONLY, parseMessagingPolicy("MONEY_ONLY"))
        assertEquals(MessagingConfirmationPolicy.NEVER, parseMessagingPolicy("NEVER"))
    }

    @Test
    fun `unknown or missing messaging value falls back to ALWAYS`() {
        assertEquals(MessagingConfirmationPolicy.ALWAYS, parseMessagingPolicy(null))
        assertEquals(MessagingConfirmationPolicy.ALWAYS, parseMessagingPolicy("money_only"))
        assertEquals(MessagingConfirmationPolicy.ALWAYS, parseMessagingPolicy("OOPS"))
    }

    @Test
    fun `trusted contacts are trimmed, deduplicated and emptied of blanks`() {
        assertEquals(
            setOf("Мама", "+998 90 123 45 67"),
            normalizeTrustedContacts(setOf(" Мама ", "+998 90 123 45 67", "Мама", "", "   "))
        )
    }

    @Test
    fun `null trusted contacts read as empty set`() {
        assertTrue(normalizeTrustedContacts(null).isEmpty())
    }
}
