package com.omnix.server

import com.omnix.server.admin.AdminModelAccess
import com.omnix.server.config.ProviderConfig
import com.omnix.server.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Тест моделей админом (решение владельца 2026-10-21): выбор модели — только админу. */
class AdminModelAccessTest {

    private val admin = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val user = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun cfg(id: ProviderId, priority: Int, model: String, enabled: Boolean = true) = ProviderConfig(
        id = id,
        enabled = enabled,
        priority = priority,
        apiKey = "test-key",
        model = model,
        baseUrl = "https://example.invalid",
        connectTimeoutMs = 1000,
        requestTimeoutMs = 2000
    )

    private val access = AdminModelAccess(
        adminAccountIds = setOf(admin),
        providers = listOf(
            cfg(ProviderId.OPENROUTER, 3, "llama"),
            cfg(ProviderId.GROQ, 1, "gpt-oss"),
            cfg(ProviderId.GEMINI, 2, "gemini", enabled = false)
        )
    )

    @Test
    fun `only listed accounts are admins`() {
        assertTrue(access.isAdmin(admin))
        assertFalse(access.isAdmin(user))
        assertFalse(access.isAdmin(null))
    }

    @Test
    fun `admin pins an enabled provider by name, any case`() {
        assertEquals(AdminModelAccess.Pin.Pinned(ProviderId.OPENROUTER), access.pin("openrouter", admin))
        assertEquals(AdminModelAccess.Pin.Pinned(ProviderId.GROQ), access.pin("GROQ", admin))
    }

    @Test
    fun `a non-admin request is ignored and the server keeps choosing`() {
        assertTrue(access.pin("GROQ", user) is AdminModelAccess.Pin.Ignored)
        assertTrue(access.pin("GROQ", null) is AdminModelAccess.Pin.Ignored)
    }

    @Test
    fun `unknown or disabled providers are ignored, empty is no request`() {
        assertTrue(access.pin("openai", admin) is AdminModelAccess.Pin.Ignored)
        assertTrue(access.pin("GEMINI", admin) is AdminModelAccess.Pin.Ignored)
        assertEquals(AdminModelAccess.Pin.None, access.pin(null, admin))
        assertEquals(AdminModelAccess.Pin.None, access.pin("  ", admin))
    }

    @Test
    fun `models list enabled providers by priority`() {
        assertEquals(
            listOf("GROQ" to "gpt-oss", "OPENROUTER" to "llama"),
            access.models().map { it.provider to it.model }
        )
    }

    @Test
    fun `admin ids parse from env and reject garbage`() {
        assertEquals(setOf(admin, user), AdminModelAccess.parseAccountIds(" $admin , $user ,"))
        assertTrue(AdminModelAccess.parseAccountIds(null).isEmpty())
        val failure = runCatching { AdminModelAccess.parseAccountIds("not-a-uuid") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertNull(failure?.message?.takeIf { it.contains("not-a-uuid") })
    }
}
