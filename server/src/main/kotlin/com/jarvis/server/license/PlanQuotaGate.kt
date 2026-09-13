package com.jarvis.server.license

import com.jarvis.server.auth.AuthSource
import com.jarvis.server.auth.AuthenticatedClient
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.usage.UsageRepository
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Дневные квоты тарифа (voice/base AI).
 *
 * Считает ТОЛЬКО успешные запросы за календарные сутки UTC — отклонённые
 * (лимит, приватность, ошибки) квоту не тратят. Наследие без feature-метки
 * (NULL, до V009) консервативно засчитывается в каждую корзину.
 *
 * - LICENSE_TOKEN без плана → FREE (fail-closed);
 * - STATIC ADMIN/INTERNAL → без квот (доверенный операторский путь, не
 *   раздаётся пользователям);
 * - STATIC FREE → квоты FREE (статический тестовый токен не должен быть
 *   безлимитной дырой).
 */
class PlanQuotaGate(
    private val usage: UsageRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    sealed interface Verdict {
        data object Allowed : Verdict
        data class Limited(
            val retryAfterSeconds: Long,
            val limit: Int,
            val used: Long
        ) : Verdict
    }

    suspend fun check(client: AuthenticatedClient, feature: PlanFeature): Verdict {
        if (client.authSource == AuthSource.STATIC &&
            client.tier != ClientTier.FREE
        ) {
            return Verdict.Allowed
        }
        val limits = PlanCatalog.forPlanId(client.planId ?: PlanCatalog.FREE)
        val quota = limits.serverQuota(feature) ?: return Verdict.Allowed
        val dayStart = LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toInstant()
        val used = usage.countSince(client.clientId, dayStart, feature.key)
        if (used < quota) return Verdict.Allowed
        val retryAfter = Duration.between(clock.instant(), dayStart.plus(Duration.ofDays(1)))
            .seconds.coerceAtLeast(1)
        return Verdict.Limited(retryAfterSeconds = retryAfter, limit = quota, used = used)
    }
}
