package com.omnix.assistant.core.permission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Очередь системных запросов разрешений от инструментов. Инструмент, которому
 * не хватило доступа («позвони маме» без контактов), кладёт сюда нужные
 * разрешения; открытая [com.omnix.assistant.presentation.MainActivity]
 * показывает системный диалог сразу. Если приложение закрыто, запрос ждёт
 * следующего открытия.
 */
object PermissionPrompts {
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    fun request(permissions: Collection<String>) {
        val runtime = permissions.filter { it.startsWith("android.permission.") }
        if (runtime.isEmpty()) return
        _pending.value = _pending.value + runtime
    }

    fun clear() {
        _pending.value = emptySet()
    }
}
