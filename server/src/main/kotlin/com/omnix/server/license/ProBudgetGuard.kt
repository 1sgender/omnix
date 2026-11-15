package com.omnix.server.license

import com.omnix.server.config.ProTierConfig
import com.omnix.server.provider.ProviderId
import com.omnix.server.usage.UsageRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Предохранитель расходов на Pro (Claude).
 *
 * Считает потраченное за календарный месяц UTC по токенам успешных ответов
 * Claude и цене из [ProTierConfig]. Когда расход достигает порога
 * (`downgradeAtPercent` от месячного бюджета), Pro-запросы обслуживаются как
 * Flash. Бюджет 0 — ограничения нет, расход всё равно считается.
 *
 * Значение кэшируется на [CACHE_MS]: проверка стоит запроса в БД, а точность
 * до минуты для месячного бюджета достаточна. При сбое БД решает в пользу
 * экономии: показатель считается «бюджет исчерпан» только если был известен
 * последний расход; без данных Pro не блокируется (иначе сбой БД выключил бы Pro всем).
 */
class ProBudgetGuard(
    private val usage: UsageRepository,
    private val config: ProTierConfig,
    private val clock: Clock = Clock.systemUTC()
) {
    private data class Snapshot(val spentUsd: Double, val monthStartEpochDay: Long, val atMs: Long)

    @Volatile
    private var cached: Snapshot? = null

    /** Расход Pro за текущий месяц, USD. */
    suspend fun spentUsd(): Double {
        val now = clock.millis()
        val monthStart = LocalDate.now(clock).withDayOfMonth(1)
        val snapshot = cached
        if (snapshot != null &&
            snapshot.monthStartEpochDay == monthStart.toEpochDay() &&
            now - snapshot.atMs < CACHE_MS
        ) {
            return snapshot.spentUsd
        }
        val totals = try {
            usage.tokenTotalsSince(
                ProviderId.ANTHROPIC.name,
                monthStart.atStartOfDay(ZoneOffset.UTC).toInstant()
            )
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: Throwable) {
            return snapshot?.spentUsd ?: 0.0
        }
        val spent = totals.inputTokens * config.inputUsdPerMillion / 1_000_000.0 +
            totals.outputTokens * config.outputUsdPerMillion / 1_000_000.0
        cached = Snapshot(spent, monthStart.toEpochDay(), now)
        return spent
    }

    /** true — бюджет достиг порога, Pro нужно понизить до Flash. */
    suspend fun shouldDowngrade(): Boolean {
        val threshold = config.downgradeThresholdUsd ?: return false
        return spentUsd() >= threshold
    }

    companion object {
        const val CACHE_MS = 60_000L
    }
}
