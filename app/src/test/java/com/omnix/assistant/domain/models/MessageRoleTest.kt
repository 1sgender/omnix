package com.omnix.assistant.domain.models

import com.omnix.assistant.data.local.entity.toDomain
import com.omnix.assistant.data.local.entity.toEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ERROR role (mock 2026-09-26): a failed chat round trip must survive
 * the Room round trip with its identity intact. The column is a plain
 * string, so the contract lives in [MessageRole.value] /
 * [MessageRole.fromString] and the entity mappers — no schema migration
 * accompanies the new value, old rows must keep mapping.
 */
class MessageRoleTest {

    @Test
    fun errorRole_mapsToStoredValue() {
        assertEquals("error", MessageRole.ERROR.value)
    }

    @Test
    fun errorRole_readsBackFromStoredValue() {
        assertEquals(MessageRole.ERROR, MessageRole.fromString("error"))
    }

    @Test
    fun errorRole_survivesEntityRoundTrip() {
        val message = Message(
            role = MessageRole.ERROR,
            text = "Ошибка сети. Проверьте соединение и повторите.",
            timestamp = 1758864000000L
        )
        assertEquals(message, message.toEntity().toDomain())
    }

    @Test
    fun preexistingRoles_keepTheirStoredValues() {
        assertEquals("user", MessageRole.USER.value)
        assertEquals("assistant", MessageRole.ASSISTANT.value)
        assertEquals("system", MessageRole.SYSTEM.value)
        assertEquals(MessageRole.USER, MessageRole.fromString("user"))
        assertEquals(MessageRole.ASSISTANT, MessageRole.fromString("assistant"))
        assertEquals(MessageRole.SYSTEM, MessageRole.fromString("system"))
    }

    @Test
    fun unknownStoredValue_fallsBackToUser() {
        assertEquals(MessageRole.USER, MessageRole.fromString("???"))
    }
}
