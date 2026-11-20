package com.omnix.assistant.core.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionKindTest {

    @Test
    fun `permissions map to human groups`() {
        assertEquals(PermissionKind.CONTACTS, PermissionKind.of("android.permission.READ_CONTACTS"))
        assertEquals(PermissionKind.CALLS, PermissionKind.of("android.permission.CALL_PHONE"))
        assertEquals(PermissionKind.SMS, PermissionKind.of("android.permission.SEND_SMS"))
        assertEquals(PermissionKind.OTHER, PermissionKind.of("android.permission.SOMETHING_NEW"))
    }

    @Test
    fun `groups are deduplicated in order`() {
        val kinds = PermissionKind.distinctOf(
            listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.READ_CONTACTS", "android.permission.ACCESS_COARSE_LOCATION")
        )
        assertEquals(listOf(PermissionKind.LOCATION, PermissionKind.CONTACTS), kinds)
    }

    @Test
    fun `permanently denied are those the system will not ask again`() {
        val denied = listOf("android.permission.READ_CONTACTS", "android.permission.CALL_PHONE")
        val result = PermissionOutcome.permanentlyDenied(denied) { it.endsWith("CALL_PHONE") }
        assertEquals(listOf("android.permission.READ_CONTACTS"), result)
    }
}
