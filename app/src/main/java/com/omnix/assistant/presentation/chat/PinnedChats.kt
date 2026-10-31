package com.omnix.assistant.presentation.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Закреплённые чаты (просьба владельца): номера разговоров, которые остаются
 * вверху списка. Хранятся в настройках приложения, база данных не меняется.
 */
@Singleton
class PinnedChatsStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _pinned = MutableStateFlow(read())
    val pinned: StateFlow<Set<Long>> = _pinned.asStateFlow()

    fun toggle(conversationId: Long) {
        write(if (conversationId in _pinned.value) _pinned.value - conversationId else _pinned.value + conversationId)
    }

    /** Чат удалён — закрепление больше не нужно. */
    fun forget(conversationId: Long) {
        if (conversationId in _pinned.value) write(_pinned.value - conversationId)
    }

    private fun read(): Set<Long> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    private fun write(ids: Set<Long>) {
        _pinned.value = ids
        prefs.edit().putStringSet(KEY, ids.map { it.toString() }.toSet()).apply()
    }

    private companion object {
        const val PREFS = "omnix_pinned_chats"
        const val KEY = "ids"
    }
}

/** Свои названия чатов (просьба владельца: переименование). Хранятся в настройках приложения. */
@Singleton
class ChatTitlesStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _titles = MutableStateFlow(read())
    val titles: StateFlow<Map<Long, String>> = _titles.asStateFlow()

    /** Пустое название возвращает исходное. */
    fun rename(conversationId: Long, title: String) {
        val clean = title.trim().take(MAX_TITLE)
        write(if (clean.isEmpty()) _titles.value - conversationId else _titles.value + (conversationId to clean))
    }

    fun forget(conversationId: Long) {
        if (conversationId in _titles.value) write(_titles.value - conversationId)
    }

    private fun read(): Map<Long, String> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { entry ->
            val at = entry.indexOf(SEPARATOR)
            val id = if (at > 0) entry.substring(0, at).toLongOrNull() else null
            if (id == null) null else id to entry.substring(at + 1)
        }.toMap()

    private fun write(titles: Map<Long, String>) {
        _titles.value = titles
        prefs.edit().putStringSet(KEY, titles.map { (id, title) -> "$id$SEPARATOR$title" }.toSet()).apply()
    }

    private companion object {
        const val PREFS = "omnix_chat_titles"
        const val KEY = "titles"
        const val SEPARATOR = ':'
        const val MAX_TITLE = 80
    }
}

@HiltViewModel
class PinnedChatsViewModel @Inject constructor(
    private val store: PinnedChatsStore,
    private val titleStore: ChatTitlesStore
) : ViewModel() {
    val pinned: StateFlow<Set<Long>> = store.pinned
    val titles: StateFlow<Map<Long, String>> = titleStore.titles

    fun toggle(conversationId: Long) = store.toggle(conversationId)

    fun rename(conversationId: Long, title: String) = titleStore.rename(conversationId, title)

    /** Чат удалён — закрепление и своё название больше не нужны. */
    fun forget(conversationId: Long) {
        store.forget(conversationId)
        titleStore.forget(conversationId)
    }
}
