package com.omnix.assistant.presentation.chat

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Передача фразы из другого экрана в чат: экран «Протоколы» отправляет
 * описание протокола, чат берёт его и отправляет агенту.
 */
@Singleton
class ChatHandoff @Inject constructor() {
    private val pending = MutableStateFlow<String?>(null)

    fun put(text: String) {
        pending.value = text.trim().takeIf { it.isNotEmpty() }
    }

    /** Забирает фразу один раз. */
    fun take(): String? = pending.getAndUpdate { null }
}

@HiltViewModel
class ChatHandoffViewModel @Inject constructor(private val handoff: ChatHandoff) : ViewModel() {
    fun put(text: String) = handoff.put(text)

    fun take(): String? = handoff.take()
}
