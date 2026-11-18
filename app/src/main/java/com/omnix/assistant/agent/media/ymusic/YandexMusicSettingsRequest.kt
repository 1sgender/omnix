package com.omnix.assistant.agent.media.ymusic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Просьба открыть раздел настроек «Яндекс Музыка» (когда нет токена или он не принят).
 * Инструмент ставит просьбу и поднимает приложение; навигация подхватывает её и очищает.
 */
object YandexMusicSettingsRequest {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() { _pending.value = true }

    fun consume() { _pending.value = false }
}
