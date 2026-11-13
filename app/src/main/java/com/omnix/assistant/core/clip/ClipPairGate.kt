package com.omnix.assistant.core.clip

import com.omnix.assistant.core.license.ClipBindingState
import com.omnix.assistant.core.license.ClipPairInfo
import java.util.Locale

/**
 * Чистое решение «можно ли облачный ИИ» по привязанным парам OMNIX Clip.
 *
 * Источник истины — сервер: список своих пар и флаг обязательности приходят в
 * validate-ответе ([ClipBindingState]); телефон лишь сверяет адрес реально
 * подключённых наушников с этим списком. Офлайн-функции этим не гейтятся.
 *
 * ВРЕМЕННО: пара опознаётся по BT-адресу (подделывается на root-телефоне);
 * прошивка с ключом (challenge-response, ClipAttestationManager) заменит
 * сравнение адреса проверкой подписи.
 */
object ClipPairGate {
    private val HEX = Regex("[0-9A-F]{12}")
    private val PLACEHOLDERS = setOf("000000000000", "020000000000", "FFFFFFFFFFFF")

    /** `aa-bb-…`/`aabb…` → `AA:BB:CC:DD:EE:FF`; null — не адрес или заглушка Android. */
    fun normalize(raw: String?): String? {
        val compact = raw?.trim()?.uppercase(Locale.ROOT)
            ?.filterNot { it == ':' || it == '-' || it == '.' || it.isWhitespace() }
            ?: return null
        if (!HEX.matches(compact) || compact in PLACEHOLDERS) return null
        return compact.chunked(2).joinToString(":")
    }

    /** Своя пара среди подключённых сейчас устройств (null — ни одной). */
    fun connectedOwnPair(binding: ClipBindingState, connectedAddresses: Collection<String>): ClipPairInfo? {
        val connected = connectedAddresses.mapNotNull(::normalize).toSet()
        if (connected.isEmpty()) return null
        return binding.pairs.firstOrNull { normalize(it.btAddress) in connected }
    }

    /**
     * true — облачный ИИ разрешён: сервер пару не требует, либо подключена
     * ЛЮБАЯ из ACTIVE-пар аккаунта.
     */
    fun isCloudUnlocked(binding: ClipBindingState, connectedAddresses: Collection<String>): Boolean =
        !binding.pairRequired || connectedOwnPair(binding, connectedAddresses) != null
}
