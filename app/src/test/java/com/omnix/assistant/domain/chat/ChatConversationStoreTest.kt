package com.omnix.assistant.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationStoreTest {

    @Test
    fun `a new chat always gets a new, later id`() {
        val store = ChatConversationStore()
        val first = store.currentId.value

        store.startNew()
        val second = store.currentId.value
        store.startNew()
        val third = store.currentId.value

        assertTrue(second > first)
        assertTrue(third > second)
        assertTrue(first > ChatConversationStore.LEGACY_CONVERSATION_ID)
    }

    @Test
    fun `opening a past chat makes it current`() {
        val store = ChatConversationStore()
        store.open(ChatConversationStore.LEGACY_CONVERSATION_ID)
        assertEquals(ChatConversationStore.LEGACY_CONVERSATION_ID, store.currentId.value)
    }

    @Test
    fun `a new chat after opening an old one never reuses an issued id`() {
        val store = ChatConversationStore()
        val first = store.currentId.value
        store.startNew()
        val second = store.currentId.value

        // В ту же миллисекунду: открыть первый чат и начать новый.
        store.open(first)
        store.startNew()

        assertTrue(store.currentId.value > second)
    }
}
