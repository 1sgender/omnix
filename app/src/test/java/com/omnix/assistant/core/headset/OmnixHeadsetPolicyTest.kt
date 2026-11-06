package com.omnix.assistant.core.headset

import org.junit.Assert.assertEquals
import org.junit.Test

class OmnixHeadsetPolicyTest {

    private val omnix = ConnectedHeadset("AA:BB:CC:DD:EE:01", "Clip")
    private val other = ConnectedHeadset("11:22:33:44:55:66", "Other")

    private fun decide(
        isAdmin: Boolean = false,
        enforceForAdmin: Boolean = false,
        hasPermission: Boolean = true,
        bluetoothOn: Boolean = true,
        connected: List<ConnectedHeadset> = emptyList(),
        registered: Set<String> = setOf("aa:bb:cc:dd:ee:01")
    ) = OmnixHeadsetPolicy.decide(isAdmin, enforceForAdmin, hasPermission, bluetoothOn, connected, registered)

    @Test
    fun `admin passes without headset`() {
        assertEquals(HeadsetGateState.Unlocked(UnlockReason.ADMIN), decide(isAdmin = true, hasPermission = false))
    }

    @Test
    fun `admin with enforcement is checked like everyone`() {
        assertEquals(
            HeadsetGateState.Locked(LockReason.NO_OMNIX_HEADSET),
            decide(isAdmin = true, enforceForAdmin = true, connected = listOf(other))
        )
    }

    @Test
    fun `registered headset unlocks regardless of address case`() {
        assertEquals(
            HeadsetGateState.Unlocked(UnlockReason.OMNIX_HEADSET, "Clip"),
            decide(connected = listOf(other, omnix))
        )
    }

    @Test
    fun `foreign headset stays locked`() {
        assertEquals(HeadsetGateState.Locked(LockReason.NO_OMNIX_HEADSET), decide(connected = listOf(other)))
    }

    @Test
    fun `missing permission is reported before bluetooth state`() {
        assertEquals(
            HeadsetGateState.Locked(LockReason.NO_PERMISSION),
            decide(hasPermission = false, bluetoothOn = false, connected = listOf(omnix))
        )
    }

    @Test
    fun `bluetooth off locks`() {
        assertEquals(HeadsetGateState.Locked(LockReason.BLUETOOTH_OFF), decide(bluetoothOn = false))
    }

    @Test
    fun `nothing registered locks`() {
        assertEquals(
            HeadsetGateState.Locked(LockReason.NO_OMNIX_HEADSET),
            decide(connected = listOf(omnix), registered = emptySet())
        )
    }
}
