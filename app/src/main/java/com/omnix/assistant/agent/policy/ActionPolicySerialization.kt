package com.omnix.assistant.agent.policy

/**
 * Чистые функции DataStore ↔ домен для настроек политики подтверждений
 * (блок «Privacy-органы управления» плана пересборки фронта, 2026-09-26).
 *
 * Правило устойчивости к будущим значениям: неизвестная строка в хранилище
 * (enum переименовали, значение пришло из более новой версии) читается как
 * безопасный дефолт ALWAYS — политика строже, лучше. Падение парсинга
 * настроек не должно открывать действие без подтверждения.
 */

internal fun parseCallPolicy(raw: String?): CallConfirmationPolicy =
    CallConfirmationPolicy.entries.firstOrNull { it.name == raw }
        ?: CallConfirmationPolicy.ALWAYS

internal fun parseMessagingPolicy(raw: String?): MessagingConfirmationPolicy =
    MessagingConfirmationPolicy.entries.firstOrNull { it.name == raw }
        ?: MessagingConfirmationPolicy.ALWAYS

/**
 * Доверенные контакты: trim, без пустых, без дублей. Регистр НЕ опускаем —
 * имена отображаются пользователю как введены; матчинг [TrustedContactMatcher]
 * сам регистронезависим для имён и сравнивает цифры номеров.
 */
internal fun normalizeTrustedContacts(raw: Set<String>?): Set<String> =
    raw?.map(String::trim)?.filter(String::isNotEmpty)?.toSet() ?: emptySet()
