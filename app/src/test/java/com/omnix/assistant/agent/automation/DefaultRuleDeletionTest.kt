package com.omnix.assistant.agent.automation

import com.omnix.assistant.agent.automation.engine.survivingDefaults
import com.omnix.assistant.agent.automation.entity.AutomationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gap report 2026-09-26, block 1, проверка №8: deleting a default rule from
 * the UI must survive engine re-initialization — the engine re-inserts
 * missing defaults on the next system event, so deleted ruleIds have to be
 * filtered out before that insert.
 */
class DefaultRuleDeletionTest {

    private fun rule(ruleId: String) = AutomationEntity(
        ruleId = ruleId,
        name = "rule $ruleId",
        triggerType = "TIME_SCHEDULE",
        triggerParam = "07:00",
        actionsJson = "[]"
    )

    @Test
    fun `deleted default rules are not resurrected`() {
        val defaults = listOf(rule("default_morning_schedule"), rule("default_home_wifi"))

        val survivors = survivingDefaults(defaults, setOf("default_morning_schedule"))

        assertEquals(listOf("default_home_wifi"), survivors.map { it.ruleId })
    }

    @Test
    fun `nothing deleted keeps all defaults`() {
        val defaults = listOf(rule("a"), rule("b"))

        val survivors = survivingDefaults(defaults, emptySet())

        assertEquals(defaults, survivors)
    }

    @Test
    fun `markers without matching rules are harmless`() {
        val survivors = survivingDefaults(listOf(rule("a")), setOf("gone_long_ago"))

        assertTrue(survivors.isNotEmpty())
    }
}
