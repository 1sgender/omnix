package com.omnix.assistant.presentation.chat

import com.omnix.assistant.domain.models.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ConversationGroupsTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 22)

    private fun chat(id: Long, title: String?, daysAgo: Long) = ConversationSummary(
        id = id,
        title = title,
        startedAt = 0L,
        lastMessageAt = today.minusDays(daysAgo).atTime(12, 0).toInstant(zone).toEpochMilli(),
        messageCount = 2
    )

    private val chats = listOf(
        chat(1, "Погода утром", 0),
        chat(2, "Будильник", 1),
        chat(3, "План поездки", 5),
        chat(4, "Рецепт плова", 20),
        chat(5, null, 90)
    )

    @Test
    fun `chats fall into date groups newest first`() {
        val groups = conversationGroups(chats, query = "", today = today, zone = zone)
        assertEquals(
            listOf(
                ConversationGroup.TODAY to listOf(1L),
                ConversationGroup.YESTERDAY to listOf(2L),
                ConversationGroup.WEEK to listOf(3L),
                ConversationGroup.MONTH to listOf(4L),
                ConversationGroup.OLDER to listOf(5L)
            ),
            groups.map { (group, items) -> group to items.map { it.id } }
        )
    }

    @Test
    fun `search matches titles ignoring case and drops empty groups`() {
        val groups = conversationGroups(chats, query = "  ПЛ ", today = today, zone = zone)
        assertEquals(
            listOf(ConversationGroup.WEEK to listOf(3L), ConversationGroup.MONTH to listOf(4L)),
            groups.map { (group, items) -> group to items.map { it.id } }
        )
    }
}
