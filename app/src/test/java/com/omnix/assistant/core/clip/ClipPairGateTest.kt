package com.omnix.assistant.core.clip

import com.omnix.assistant.core.license.ClipBindingState
import com.omnix.assistant.core.license.ClipPairInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гейт облачного ИИ по паре OMNIX (V012): облако открыто, только пока
 * подключена одна из своих ACTIVE-пар; старый сервер (pair_required нет)
 * не гейтит; заглушки Android-адресов не открывают гейт.
 */
class ClipPairGateTest {

    private val first = ClipPairInfo(clipSerial = "OMX-1", btAddress = "AA:BB:CC:DD:EE:01")
    private val second = ClipPairInfo(clipSerial = "OMX-2", btAddress = "AA:BB:CC:DD:EE:02")
    private val required = ClipBindingState(pairRequired = true, pairs = listOf(first, second))

    @Test
    fun `normalization accepts common formats and rejects placeholders`() {
        assertEquals("AA:BB:CC:DD:EE:01", ClipPairGate.normalize("aa-bb-cc-dd-ee-01"))
        assertEquals("AA:BB:CC:DD:EE:01", ClipPairGate.normalize(" aabbccddee01 "))
        assertNull(ClipPairGate.normalize("02:00:00:00:00:00"))
        assertNull(ClipPairGate.normalize("not-an-address"))
        assertNull(ClipPairGate.normalize(null))
    }

    @Test
    fun `cloud is locked without a connected own pair`() {
        assertFalse(ClipPairGate.isCloudUnlocked(required, emptyList()))
        assertFalse(ClipPairGate.isCloudUnlocked(required, listOf("11:22:33:44:55:66")))
        assertFalse(ClipPairGate.isCloudUnlocked(required, listOf("02:00:00:00:00:00")))
    }

    @Test
    fun `any of the account pairs unlocks cloud in any address format`() {
        assertTrue(ClipPairGate.isCloudUnlocked(required, listOf("aa:bb:cc:dd:ee:02")))
        assertTrue(ClipPairGate.isCloudUnlocked(required, listOf("11:22:33:44:55:66", "AABBCCDDEE01")))
        assertEquals(second, ClipPairGate.connectedOwnPair(required, listOf("aa-bb-cc-dd-ee-02")))
    }

    @Test
    fun `server that does not require a pair never locks cloud`() {
        assertTrue(ClipPairGate.isCloudUnlocked(ClipBindingState.UNKNOWN, emptyList()))
        assertTrue(ClipPairGate.isCloudUnlocked(required.copy(pairRequired = false), emptyList()))
    }

    @Test
    fun `required with no pairs keeps cloud locked`() {
        assertFalse(ClipPairGate.isCloudUnlocked(ClipBindingState(pairRequired = true), listOf("AA:BB:CC:DD:EE:01")))
    }
}
