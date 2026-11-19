package com.omnix.assistant.core.permission

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPromptsTest {

    @After
    fun tearDown() {
        PermissionPrompts.clear()
        PermissionPrompts.takeRetry()
        PermissionPrompts.consumeRecent(0)
    }

    @Test
    fun `only runtime permissions are queued`() {
        PermissionPrompts.request(listOf("not.a.runtime.permission"))
        assertTrue(PermissionPrompts.pending.value.isEmpty())
        PermissionPrompts.request(listOf("android.permission.READ_CONTACTS"))
        assertEquals(setOf("android.permission.READ_CONTACTS"), PermissionPrompts.pending.value)
    }

    @Test
    fun `retry is kept until taken and runs once`() = runBlocking {
        PermissionPrompts.request(listOf("android.permission.CALL_PHONE")) { "Звоню маме" }
        val retry = PermissionPrompts.takeRetry()
        assertNotNull(retry)
        assertEquals("Звоню маме", retry!!.invoke())
        assertNull(PermissionPrompts.takeRetry())
    }

    @Test
    fun `recent request is consumed once and expires`() {
        PermissionPrompts.request(listOf("android.permission.READ_CONTACTS"))
        PermissionPrompts.clear()
        val now = System.currentTimeMillis()
        assertEquals(setOf("android.permission.READ_CONTACTS"), PermissionPrompts.consumeRecent(now))
        assertNull(PermissionPrompts.consumeRecent(now))

        PermissionPrompts.request(listOf("android.permission.READ_CONTACTS"))
        assertNull(PermissionPrompts.consumeRecent(now + PermissionPrompts.RECENT_WINDOW_MS + 60_000))
    }
}
