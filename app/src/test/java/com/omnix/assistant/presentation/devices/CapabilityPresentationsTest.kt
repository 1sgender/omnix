package com.omnix.assistant.presentation.devices

import com.omnix.assistant.agent.capability.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Токены презентации capability-снимка (блок 6 плана пересборки фронта,
 * 2026-09-26): статус → подпись/точка, id группы → имя. Инварианты:
 * PERMISSION_REQUIRED и USER_ACTION_REQUIRED — внимание (жёлтый), а не
 * авария; неизвестный id группы — OTHER, не краш.
 */
class CapabilityPresentationsTest {

    @Test
    fun `every status maps to its own label`() {
        assertEquals(
            CapabilityStatusLabel.AVAILABLE,
            capabilityStatusLabel(CapabilityStatus.Available)
        )
        assertEquals(
            CapabilityStatusLabel.PERMISSION_REQUIRED,
            capabilityStatusLabel(CapabilityStatus.PermissionRequired(listOf("p")))
        )
        assertEquals(
            CapabilityStatusLabel.USER_ACTION_REQUIRED,
            capabilityStatusLabel(CapabilityStatus.UserActionRequired("reason"))
        )
        assertEquals(
            CapabilityStatusLabel.UNSUPPORTED,
            capabilityStatusLabel(CapabilityStatus.Unsupported("reason"))
        )
    }

    @Test
    fun `available is green, fixable is attention, impossible is off`() {
        assertEquals(CapabilityDotToken.OK, capabilityDot(CapabilityStatus.Available))
        assertEquals(
            CapabilityDotToken.ATTENTION,
            capabilityDot(CapabilityStatus.PermissionRequired(listOf("p")))
        )
        assertEquals(
            CapabilityDotToken.ATTENTION,
            capabilityDot(CapabilityStatus.UserActionRequired("reason"))
        )
        assertEquals(CapabilityDotToken.OFF, capabilityDot(CapabilityStatus.Unsupported("reason")))
    }

    @Test
    fun `all ten capability group ids map to their tokens`() {
        assertEquals(CapabilityGroupToken.BLUETOOTH, capabilityGroupToken("device.bluetooth"))
        assertEquals(CapabilityGroupToken.WIFI, capabilityGroupToken("device.wifi"))
        assertEquals(CapabilityGroupToken.BRIGHTNESS, capabilityGroupToken("device.brightness"))
        assertEquals(CapabilityGroupToken.SCREENSHOT, capabilityGroupToken("device.screenshot"))
        assertEquals(CapabilityGroupToken.APPS, capabilityGroupToken("device.apps"))
        assertEquals(CapabilityGroupToken.SMS, capabilityGroupToken("communication.sms"))
        assertEquals(CapabilityGroupToken.CALL, capabilityGroupToken("communication.call"))
        assertEquals(CapabilityGroupToken.MEDIA, capabilityGroupToken("media"))
        assertEquals(CapabilityGroupToken.ACCESSIBILITY, capabilityGroupToken("accessibility"))
        assertEquals(CapabilityGroupToken.LOCATION, capabilityGroupToken("location"))
    }

    @Test
    fun `unknown group id falls back to OTHER`() {
        assertEquals(CapabilityGroupToken.OTHER, capabilityGroupToken("device.nfc"))
        assertEquals(CapabilityGroupToken.OTHER, capabilityGroupToken(""))
    }
}
