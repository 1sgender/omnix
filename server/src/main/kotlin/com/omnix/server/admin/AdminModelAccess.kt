package com.omnix.server.admin

import com.omnix.server.api.AdminCloudModelDto
import com.omnix.server.config.ProviderConfig
import com.omnix.server.provider.ProviderId
import java.util.UUID

/**
 * Тест моделей админом (решение владельца 2026-10-21).
 *
 * Аккаунты из `ADMIN_ACCOUNT_IDS` могут закрепить за своим запросом
 * конкретного облачного провайдера и видят, какая модель ответила. Для всех
 * остальных клиент по-прежнему не выбирает модель — её выбирает сервер; поле
 * запроса от не-админа игнорируется. Админа определяет только сервер: клиент
 * не может назначить себя админом.
 */
class AdminModelAccess(
    private val adminAccountIds: Set<UUID>,
    private val providers: List<ProviderConfig>
) {
    fun isAdmin(accountId: UUID?): Boolean = accountId != null && accountId in adminAccountIds

    /** Облачные модели, между которыми админ выбирает: включённые провайдеры по приоритету. */
    fun models(): List<AdminCloudModelDto> = providers
        .filter { it.enabled }
        .sortedBy { it.priority }
        .map { AdminCloudModelDto(provider = it.id.name, model = it.model) }

    /**
     * Провайдер, закреплённый за запросом.
     *
     * @return [Pin.None] — не просили; [Pin.Ignored] — просил не админ или
     *         провайдер неизвестен/выключен (запрос идёт обычным путём);
     *         [Pin.Pinned] — только этот провайдер, без fallback.
     */
    fun pin(requested: String?, accountId: UUID?): Pin {
        val name = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return Pin.None
        if (!isAdmin(accountId)) return Pin.Ignored("not an admin account")
        val id = ProviderId.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return Pin.Ignored("unknown provider")
        if (providers.none { it.id == id && it.enabled }) return Pin.Ignored("provider disabled")
        return Pin.Pinned(id)
    }

    sealed interface Pin {
        data object None : Pin
        data class Ignored(val reason: String) : Pin
        data class Pinned(val provider: ProviderId) : Pin
    }

    companion object {
        /** `ADMIN_ACCOUNT_IDS`: UUID аккаунтов через запятую; пусто — админов нет. */
        fun parseAccountIds(raw: String?): Set<UUID> = raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { value ->
                try {
                    UUID.fromString(value)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("ADMIN_ACCOUNT_IDS contains an invalid UUID", e)
                }
            }
            ?.toSet()
            .orEmpty()
    }
}
