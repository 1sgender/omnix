package com.omnix.assistant.presentation.automations

import com.omnix.assistant.agent.automation.model.AutomationTriggerType
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDraftTest {

    @Test
    fun `time rule carries its time and fires once a day`() {
        val draft = AutomationDraft(trigger = DraftTrigger.TIME, hour = 6, minute = 5)
        assertEquals(AutomationTriggerType.TIME_SCHEDULE, draft.trigger.type)
        assertEquals("06:05", draft.triggerParam)
        assertTrue(draft.cooldownMs > 20L * 60 * 60 * 1000)
        assertEquals("productivity.ear_briefing", draft.toolCalls().single().toolId)
    }

    @Test
    fun `event rule has no time parameter`() {
        val draft = AutomationDraft(trigger = DraftTrigger.HEADPHONES_OFF, action = DraftAction.PAUSE_MEDIA)
        assertEquals("", draft.triggerParam)
        val call = draft.toolCalls().single()
        assertEquals("media.control", call.toolId)
        assertEquals("pause", call.arguments["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `volume is clamped to a valid percent`() {
        val call = AutomationDraft(action = DraftAction.VOLUME, volumePercent = 140).toolCalls().single()
        assertEquals("device.volume", call.toolId)
        assertEquals(100, call.arguments["percent"]!!.jsonPrimitive.int)
    }

    @Test
    fun `opening an app needs its name`() {
        assertFalse(AutomationDraft(action = DraftAction.OPEN_APP, appName = "  ").isComplete)
        val draft = AutomationDraft(action = DraftAction.OPEN_APP, appName = " YouTube ")
        assertTrue(draft.isComplete)
        assertEquals("YouTube", draft.toolCalls().single().arguments["app_name"]!!.jsonPrimitive.content)
    }
}
