package com.omnix.assistant.agent.automation

import com.omnix.assistant.agent.automation.model.AutomationActionLabel
import com.omnix.assistant.agent.automation.model.AutomationDescriptions
import com.omnix.assistant.agent.automation.model.LabeledAction
import com.omnix.assistant.agent.automation.model.AutomationTriggerLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gap report 2026-09-26, block 1: trigger/action labels for the automations
 * screen. The exact actionsJson here mirrors the default morning rule the
 * engine writes, so the test doubles as a contract on that JSON.
 */
class AutomationDescriptionsTest {

    @Test
    fun `time schedule keeps its HH-MM parameter`() {
        val (label, param) = AutomationDescriptions.triggerLabel("TIME_SCHEDULE", "07:00")
        assertEquals(AutomationTriggerLabel.TIME_SCHEDULE, label)
        assertEquals("07:00", param)
    }

    @Test
    fun `all system trigger types map to their labels`() {
        assertEquals(
            AutomationTriggerLabel.HEADPHONES_CONNECTED,
            AutomationDescriptions.triggerLabel("HEADPHONES_CONNECTED", "").first
        )
        assertEquals(
            AutomationTriggerLabel.HEADPHONES_DISCONNECTED,
            AutomationDescriptions.triggerLabel("HEADPHONES_DISCONNECTED", "").first
        )
        assertEquals(
            AutomationTriggerLabel.BATTERY_LOW,
            AutomationDescriptions.triggerLabel("BATTERY_LOW", "").first
        )
        assertEquals(
            AutomationTriggerLabel.WIFI_CONNECTED,
            AutomationDescriptions.triggerLabel("WIFI_CONNECTED", "").first
        )
        assertEquals(
            AutomationTriggerLabel.VOICE_MACRO,
            AutomationDescriptions.triggerLabel("VOICE_MACRO", "").first
        )
    }

    @Test
    fun `unknown trigger type stays honest instead of crashing`() {
        val (label, param) = AutomationDescriptions.triggerLabel("SOME_FUTURE_TRIGGER", "x")
        assertEquals(AutomationTriggerLabel.UNKNOWN, label)
        assertEquals(null, param)
    }

    @Test
    fun `default morning actions parse into ordered labels`() {
        val actionsJson = """
            [
              {"tool":"device.open_app","arguments":{"app_name":"calendar"}},
              {"tool":"intelligence.weather","arguments":{}},
              {"tool":"system.time","arguments":{}},
              {"tool":"memory.recall","arguments":{"query":"важные задачи"}}
            ]
        """.trimIndent()

        val labels = AutomationDescriptions.actionLabels(actionsJson)

        assertEquals(
            listOf(
                LabeledAction(
                    AutomationActionLabel.OPEN_APP, "calendar"
                ),
                LabeledAction(
                    AutomationActionLabel.WEATHER, null
                ),
                LabeledAction(
                    AutomationActionLabel.TIME, null
                ),
                LabeledAction(
                    AutomationActionLabel.MEMORY, null
                ),
            ),
            labels
        )
    }

    @Test
    fun `unknown tool maps to OTHER and keeps parsing`() {
        val labels = AutomationDescriptions.actionLabels(
            """[{"tool":"future.tool","arguments":{}}]"""
        )
        assertEquals(listOf(AutomationActionLabel.OTHER), labels.map { it.label })
    }

    @Test
    fun `broken json returns empty list instead of throwing`() {
        assertEquals(emptyList(), AutomationDescriptions.actionLabels("not json at all"))
        assertEquals(emptyList(), AutomationDescriptions.actionLabels(""))
    }
}
