package com.omnix.assistant.agent.tools.communication

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageArgsTest {

    @Test
    fun `canonical names are read as is`() {
        val args = buildJsonObject {
            put("recipient", "Осман")
            put("message", "Привет")
        }
        assertEquals("Осман", MessageArgs.recipient(args))
        assertEquals("Привет", MessageArgs.message(args))
    }

    @Test
    fun `model aliases are accepted`() {
        val args = buildJsonObject {
            put("contact", " Осману ")
            put("text", "Привет")
        }
        assertEquals("Осману", MessageArgs.recipient(args))
        assertEquals("Привет", MessageArgs.message(args))
    }

    @Test
    fun `blank canonical value falls back to alias`() {
        val args = buildJsonObject {
            put("recipient", "  ")
            put("to", "+99365123456")
        }
        assertEquals("+99365123456", MessageArgs.recipient(args))
    }

    @Test
    fun `missing fields give empty string`() {
        val args = buildJsonObject { put("unrelated", "x") }
        assertEquals("", MessageArgs.recipient(args))
        assertEquals("", MessageArgs.message(args))
    }
}
