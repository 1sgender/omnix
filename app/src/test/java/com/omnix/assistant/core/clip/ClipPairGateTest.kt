package com.omnix.assistant.core.clip

import com.omnix.assistant.core.license.ClipBindingState
import com.omnix.assistant.core.license.ClipPairInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гейт облака по паре OMNIX (V012): с ACTIVE-парой у аккаунта открыт текстовый
 * чат; голос и прочее облачное — только пока подключена одна из своих пар.
 * Старый сервер (pair_required нет) не гейтит; заглушки адресов не открывают.
 */
class ClipPairGateTest {

    private val first = ClipPairInfo(clipSerial = "OMX-1", btAddress = "AA:BB:CC:DD:EE:01")
    private val second = ClipPairInfo(clipSerial = "OMX-2", btAddress = "AA:BB:CC:DD:EE:02")
    private val required = ClipBindingState(pairRequired = true, pairs = listOf(first, second))
    private val noPairs = ClipBindingState(pairRequired = true)

    @Test
    fun `normalization accepts common formats and rejects placeholders`() {
        assertEquals("AA:BB:CC:DD:EE:01", ClipPairGate.normalize("aa-bb-cc-dd-ee-01"))
        assertEquals("AA:BB:CC:DD:EE:01", ClipPairGate.normalize(" aabbccddee01 "))
        assertNull(ClipPairGate.normalize("02:00:00:00:00:00"))
        assertNull(ClipPairGate.normalize("not-an-address"))
        assertNull(ClipPairGate.normalize(null))
    }

    @Test
    fun `chat is open with an account pair even without earbuds connected`() {
        assertTrue(ClipPairGate.isCloudChatUnlocked(required))
        assertFalse(ClipPairGate.isCloudVoiceUnlocked(required, emptyList()))
    }

    @Test
    fun `voice is locked without a connected own pair`() {
        assertFalse(ClipPairGate.isCloudVoiceUnlocked(required, listOf("11:22:33:44:55:66")))
        assertFalse(ClipPairGate.isCloudVoiceUnlocked(required, listOf("02:00:00:00:00:00")))
    }

    @Test
    fun `any of the account pairs unlocks voice in any address format`() {
        assertTrue(ClipPairGate.isCloudVoiceUnlocked(required, listOf("aa:bb:cc:dd:ee:02")))
        assertTrue(ClipPairGate.isCloudVoiceUnlocked(required, listOf("11:22:33:44:55:66", "AABBCCDDEE01")))
        assertEquals(second, ClipPairGate.connectedOwnPair(required, listOf("aa-bb-cc-dd-ee-02")))
    }

    @Test
    fun `server that does not require a pair never locks anything`() {
        assertTrue(ClipPairGate.isCloudChatUnlocked(ClipBindingState.UNKNOWN))
        assertTrue(ClipPairGate.isCloudVoiceUnlocked(ClipBindingState.UNKNOWN, emptyList()))
        assertTrue(ClipPairGate.isCloudVoiceUnlocked(required.copy(pairRequired = false), emptyList()))
    }

    @Test
    fun `required with no pairs keeps both chat and voice locked`() {
        assertFalse(ClipPairGate.isCloudChatUnlocked(noPairs))
        assertFalse(ClipPairGate.isCloudVoiceUnlocked(noPairs, listOf("AA:BB:CC:DD:EE:01")))
    }
}
