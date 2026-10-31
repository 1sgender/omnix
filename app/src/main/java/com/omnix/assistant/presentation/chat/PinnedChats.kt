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

@HiltViewModel
class PinnedChatsViewModel @Inject constructor(private val store: PinnedChatsStore) : ViewModel() {
    val pinned: StateFlow<Set<Long>> = store.pinned

    fun toggle(conversationId: Long) = store.toggle(conversationId)

    fun forget(conversationId: Long) = store.forget(conversationId)
}
