package com.omnix.assistant.core.permission

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPromptsTest {

    private val contacts = "android.permission.READ_CONTACTS"
    private val callPhone = "android.permission.CALL_PHONE"

    @After
    fun tearDown() {
        PermissionPrompts.clear()
        PermissionPrompts.cancelRetries()
        PermissionPrompts.forgetRecent()
    }

    @Test
    fun `only runtime permissions are queued`() {
        PermissionPrompts.request(listOf("not.a.runtime.permission"))
        assertTrue(PermissionPrompts.pending.value.isEmpty())
        PermissionPrompts.request(listOf(contacts))
        assertEquals(setOf(contacts), PermissionPrompts.pending.value)
    }

    @Test
    fun `retry is kept until taken and runs once`() = runBlocking {
        PermissionPrompts.request(listOf(callPhone)) { "Звоню маме" }
        val retry = PermissionPrompts.takeRetry()
        assertNotNull(retry)
        assertEquals("Звоню маме", retry!!.invoke())
        assertNull(PermissionPrompts.takeRetry())
    }

    @Test
    fun `retry expires after its ttl`() {
        val t0 = 1_000_000L
        PermissionPrompts.request(listOf(callPhone), nowMs = t0) { "Звоню маме" }
        assertNull(PermissionPrompts.takeRetry(nowMs = t0 + PermissionPrompts.RETRY_TTL_MS + 1))
        // Просроченный повтор не «воскресает».
        assertNull(PermissionPrompts.takeRetry(nowMs = t0))
    }

    @Test
    fun `retry within ttl is still taken`() {
        val t0 = 1_000_000L
        PermissionPrompts.request(listOf(callPhone), nowMs = t0) { "Звоню маме" }
        assertNotNull(PermissionPrompts.takeRetry(nowMs = t0 + PermissionPrompts.RETRY_TTL_MS))
    }

    @Test
    fun `cancelled or empty dialog drops the pending retry`() {
        PermissionPrompts.request(listOf(callPhone)) { "Звоню маме" }
        PermissionPrompts.cancelRetries()
        assertNull(PermissionPrompts.takeRetry())
    }

    @Test
    fun `retries of different commands do not overwrite each other silently`() = runBlocking {
        PermissionPrompts.request(listOf(callPhone), key = "call-1") { "Звоню" }
        PermissionPrompts.request(listOf(contacts), key = "call-2") { "Нашёл" }
        val retry = PermissionPrompts.takeRetry()
        assertEquals("Звоню Нашёл", retry!!.invoke())
    }

    @Test
    fun `the same command replaces its own earlier retry`() = runBlocking {
        PermissionPrompts.request(listOf(callPhone), key = "call-1") { "старый" }
        PermissionPrompts.request(listOf(callPhone), key = "call-1") { "новый" }
        assertEquals("новый", PermissionPrompts.takeRetry()!!.invoke())
        assertNull(PermissionPrompts.takeRetry())
    }

    @Test
    fun `retry queue is bounded and drops the oldest`() = runBlocking {
        (1..PermissionPrompts.MAX_RETRIES + 1).forEach { i ->
            PermissionPrompts.request(listOf(callPhone), key = "k$i") { "r$i" }
        }
        val outcome = PermissionPrompts.takeRetry()!!.invoke()
        assertEquals("r2 r3 r4", outcome)
    }

    @Test
    fun `recent request is consumed once and expires`() {
        PermissionPrompts.request(listOf(contacts))
        PermissionPrompts.clear()
        val now = System.currentTimeMillis()
        assertEquals(setOf(contacts), PermissionPrompts.consumeRecent(now))
        assertNull(PermissionPrompts.consumeRecent(now))

        PermissionPrompts.request(listOf(contacts))
        assertNull(PermissionPrompts.consumeRecent(now + PermissionPrompts.RECENT_WINDOW_MS + 60_000))
    }

    @Test
    fun `hint is bound to the current command and ignores an earlier request`() {
        val t0 = 5_000_000L
        PermissionPrompts.request(listOf(contacts), nowMs = t0)
        // Новая реплика началась позже запроса: «нужен доступ» ей не принадлежит.
        assertNull(PermissionPrompts.consumeRecent(nowMs = t0 + 3_000, sinceMs = t0 + 2_000))
    }

    @Test
    fun `hint matches a request made while the current command was processed`() {
        val t0 = 5_000_000L
        PermissionPrompts.request(listOf(contacts), nowMs = t0 + 500)
        assertEquals(setOf(contacts), PermissionPrompts.consumeRecent(nowMs = t0 + 1_000, sinceMs = t0))
    }

    @Test
    fun `forgetting recent drops the hint`() {
        PermissionPrompts.request(listOf(contacts))
        PermissionPrompts.forgetRecent()
        assertNull(PermissionPrompts.consumeRecent(System.currentTimeMillis()))
    }
}
