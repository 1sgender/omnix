package com.omnix.server.billing

import com.omnix.server.license.BillingPlan
import com.omnix.server.persistence.getInstant
import com.omnix.server.persistence.setInstant
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random

/** Заявка на ручную оплату тарифа (billing_orders, провайдер MANUAL). */
data class ManualOrder(
    val id: UUID,
    val accountId: UUID,
    val accountRef: String?,
    val orderNumber: String,
    val planId: String,
    val status: BillingOrderStatus,
    val amountMinor: Long,
    val currency: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val paidAt: Instant?,
    val paidAmountMinor: Long?,
    val paymentMethod: String?,
    val resolutionNote: String?,
    val resolvedBy: String?
)

sealed interface ManualCreateResult {
    data class Created(val order: ManualOrder) : ManualCreateResult

    /** Уже есть открытая заявка на этот план: показываем её, новую не создаём. */
    data class Existing(val order: ManualOrder) : ManualCreateResult
    data object NoLicense : ManualCreateResult
}

sealed interface ManualConfirmResult {
    data class Confirmed(val order: ManualOrder) : ManualConfirmResult
    data object NotFound : ManualConfirmResult
    data class NotConfirmable(val status: BillingOrderStatus) : ManualConfirmResult

    /** Лицензию аккаунта нельзя продлить/выдать (отозвана, аккаунт заблокирован). */
    data object FulfillmentFailed : ManualConfirmResult
}

sealed interface ManualCancelResult {
    data class Canceled(val order: ManualOrder) : ManualCancelResult
    data object NotFound : ManualCancelResult
    data class NotCancelable(val status: BillingOrderStatus) : ManualCancelResult
}

/**
 * Ручные заявки поверх существующих billing_orders: тот же заказ, та же выдача
 * тарифа ([JdbcBillingRepository.applyPaidInTransaction]), другой источник
 * подтверждения оплаты — оператор в админке.
 */
class ManualOrderRepository(
    private val dataSource: DataSource,
    private val billing: JdbcBillingRepository,
    private val random: Random = Random.Default
) {
    companion object {
        const val PROVIDER = "MANUAL"
        const val NUMBER_PREFIX = "OMX-"
        private const val SHORT_DIGITS = 4
        private const val LONG_DIGITS = 6
        private const val SHORT_ATTEMPTS = 12
        private const val LONG_ATTEMPTS = 12
        private const val MAX_APP_ORDERS = 10
        const val MAX_PAGE = 200

        /** Допустимые способы оплаты, которые оператор может указать при подтверждении. */
        val PAYMENT_METHODS: Set<String> = setOf("cash", "bank_transfer", "card_terminal", "mobile_wallet", "other")
        val FILTER_STATUSES: Set<String> = setOf("PENDING", "PAID", "CANCELED", "EXPIRED")
    }

    /** Переводит просроченные открытые заявки в EXPIRED. */
    fun expireDue(now: Instant): Int = dataSource.connection.use { expireDue(it, now) }

    private fun expireDue(connection: Connection, now: Instant): Int = connection.prepareStatement(
        """
        UPDATE billing_orders SET status = 'EXPIRED', updated_at = ?
        WHERE provider = 'MANUAL' AND status = 'PENDING' AND expires_at <= ?
        """.trimIndent()
    ).use {
        it.setInstant(1, now)
        it.setInstant(2, now)
        it.executeUpdate()
    }

    fun createOrGet(accountId: UUID, plan: BillingPlan, ttl: Duration, now: Instant): ManualCreateResult =
        transaction { connection ->
            // Тот же ключ advisory-lock, что и у checkout других провайдеров: не более одной
            // открытой заявки на аккаунт и план при параллельных нажатиях.
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
                it.setString(1, "checkout:$accountId:${plan.id}:$PROVIDER")
                it.execute()
            }
            expireDue(connection, now)
            val open = selectOne(
                connection,
                "o.account_id = ? AND o.plan_id = ? AND o.provider = 'MANUAL' AND o.status = 'PENDING'"
            ) {
                it.setObject(1, accountId)
                it.setString(2, plan.id)
            }
            if (open != null) return@transaction ManualCreateResult.Existing(open)
            val licenseId = connection.prepareStatement(
                """
                SELECT l.id FROM licenses l JOIN accounts a ON a.id = l.account_id
                WHERE l.account_id = ? AND a.status = 'ACTIVE' AND l.status IN ('ACTIVE', 'EXPIRED')
                ORDER BY l.expires_at DESC NULLS LAST LIMIT 1
                """.trimIndent()
            ).use {
                it.setObject(1, accountId)
                it.executeQuery().use { result -> if (result.next()) result.getObject(1, UUID::class.java) else null }
            } ?: return@transaction ManualCreateResult.NoLicense
            val id = UUID.randomUUID()
            val number = insertWithUniqueNumber(connection, id, accountId, licenseId, plan, ttl, now)
            check(number != null) { "Cannot allocate a unique manual order number" }
            ManualCreateResult.Created(checkNotNull(selectOne(connection, "o.id = ?") { it.setObject(1, id) }))
        }

    private fun insertWithUniqueNumber(
        connection: Connection,
        id: UUID,
        accountId: UUID,
        licenseId: UUID,
        plan: BillingPlan,
        ttl: Duration,
        now: Instant
    ): String? {
        val attempts = List(SHORT_ATTEMPTS) { SHORT_DIGITS } + List(LONG_ATTEMPTS) { LONG_DIGITS }
        for (digits in attempts) {
            val number = NUMBER_PREFIX + randomDigits(digits)
            val inserted = connection.prepareStatement(
                """
                INSERT INTO billing_orders(
                    id, account_id, license_id, plan_id, provider, status, amount_minor, currency,
                    idempotency_key, order_number, expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'MANUAL', 'PENDING', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_number) WHERE order_number IS NOT NULL DO NOTHING
                """.trimIndent()
            ).use {
                it.setObject(1, id)
                it.setObject(2, accountId)
                it.setObject(3, licenseId)
                it.setString(4, plan.id)
                it.setLong(5, plan.amountMinor)
                it.setString(6, plan.currency)
                it.setString(7, "manual-$id")
                it.setString(8, number)
                it.setInstant(9, now.plus(ttl))
                it.setInstant(10, now)
                it.setInstant(11, now)
                it.executeUpdate() == 1
            }
            if (inserted) return number
        }
        return null
    }

    private fun randomDigits(count: Int): String {
        val low = if (count <= SHORT_DIGITS) 1_000 else 100_000
        return random.nextInt(low, low * 10).toString()
    }

    /** Заявки аккаунта для экрана «Тарифы» (последние [MAX_APP_ORDERS]). */
    fun listForAccount(accountId: UUID, now: Instant): List<ManualOrder> = dataSource.connection.use { connection ->
        expireDue(connection, now)
        selectMany(connection, "o.account_id = ? AND o.provider = 'MANUAL'", MAX_APP_ORDERS, 0) {
            it.setObject(1, accountId)
        }
    }

    fun find(id: UUID): ManualOrder? = dataSource.connection.use { connection ->
        selectOne(connection, "o.id = ? AND o.provider = 'MANUAL'") { it.setObject(1, id) }
    }

    /** Список для админки: фильтр по статусу и поиск по номеру заявки (подстрока). */
    fun list(status: String?, numberQuery: String?, limit: Int, offset: Long, now: Instant): List<ManualOrder> =
        dataSource.connection.use { connection ->
            expireDue(connection, now)
            val where = mutableListOf("o.provider = 'MANUAL'")
            val binds = mutableListOf<String>()
            if (status != null) {
                where += "o.status = ?"
                binds += status
            }
            val needle = numberQuery?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (needle != null) {
                where += "o.order_number LIKE ? ESCAPE '!'"
                binds += "%" + needle.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
            }
            selectMany(connection, where.joinToString(" AND "), limit.coerceIn(1, MAX_PAGE), offset) { ps ->
                binds.forEachIndexed { index, value -> ps.setString(index + 1, value) }
            }
        }

    /**
     * Оператор подтвердил получение денег. Заявка PENDING (или уже EXPIRED, если
     * человек заплатил позже срока) становится PAID, тариф выдаётся тем же
     * путём, что и для оплаченного заказа любого провайдера.
     */
    fun confirm(
        orderId: UUID,
        paidAmountMinor: Long,
        method: String,
        note: String?,
        actor: String,
        now: Instant
    ): ManualConfirmResult = transaction { connection ->
        expireDue(connection, now)
        val locked = lockOrder(connection, orderId) ?: return@transaction ManualConfirmResult.NotFound
        if (locked.status != BillingOrderStatus.PENDING && locked.status != BillingOrderStatus.EXPIRED) {
            return@transaction ManualConfirmResult.NotConfirmable(locked.status)
        }
        val billingOrder = BillingOrder(
            id = locked.id,
            accountId = locked.accountId,
            licenseId = null,
            planId = locked.planId,
            provider = BillingProviderId.MANUAL,
            status = BillingOrderStatus.PENDING,
            amountMinor = locked.amountMinor,
            currency = locked.currency,
            idempotencyKey = "manual-${locked.id}",
            providerOrderId = null,
            providerSubscriptionId = null,
            checkoutUrl = null,
            paidAt = null
        )
        val applied = billing.applyPaidInTransaction(connection, billingOrder, null, now)
        if (applied != BillingEventApplyResult.PROCESSED) return@transaction ManualConfirmResult.FulfillmentFailed
        connection.prepareStatement(
            """
            UPDATE billing_orders SET paid_amount_minor = ?, payment_method = ?, resolution_note = ?,
                resolved_by = ?, resolved_at = ? WHERE id = ?
            """.trimIndent()
        ).use {
            it.setLong(1, paidAmountMinor)
            it.setString(2, method)
            it.setString(3, note?.take(512))
            it.setString(4, actor.take(128))
            it.setInstant(5, now)
            it.setObject(6, orderId)
            it.executeUpdate()
        }
        ManualConfirmResult.Confirmed(checkNotNull(selectOne(connection, "o.id = ?") { it.setObject(1, orderId) }))
    }

    fun cancel(orderId: UUID, reason: String, actor: String, now: Instant): ManualCancelResult =
        transaction { connection ->
            expireDue(connection, now)
            val locked = lockOrder(connection, orderId) ?: return@transaction ManualCancelResult.NotFound
            if (locked.status != BillingOrderStatus.PENDING) {
                return@transaction ManualCancelResult.NotCancelable(locked.status)
            }
            connection.prepareStatement(
                """
                UPDATE billing_orders SET status = 'CANCELED', updated_at = ?, resolution_note = ?,
                    resolved_by = ?, resolved_at = ? WHERE id = ?
                """.trimIndent()
            ).use {
                it.setInstant(1, now)
                it.setString(2, reason.take(512))
                it.setString(3, actor.take(128))
                it.setInstant(4, now)
                it.setObject(5, orderId)
                it.executeUpdate()
            }
            ManualCancelResult.Canceled(checkNotNull(selectOne(connection, "o.id = ?") { it.setObject(1, orderId) }))
        }

    private fun lockOrder(connection: Connection, orderId: UUID): ManualOrder? {
        val locked = connection.prepareStatement(
            "SELECT id FROM billing_orders WHERE id = ? AND provider = 'MANUAL' FOR UPDATE"
        ).use {
            it.setObject(1, orderId)
            it.executeQuery().use { result -> result.next() }
        }
        return if (locked) selectOne(connection, "o.id = ?") { it.setObject(1, orderId) } else null
    }

    private val selectSql =
        """
        SELECT o.id, o.account_id, a.external_ref, o.order_number, o.plan_id, o.status, o.amount_minor,
               o.currency, o.created_at, o.expires_at, o.paid_at, o.paid_amount_minor, o.payment_method,
               o.resolution_note, o.resolved_by
        FROM billing_orders o JOIN accounts a ON a.id = o.account_id
        """.trimIndent()

    private fun selectOne(
        connection: Connection,
        where: String,
        bind: (java.sql.PreparedStatement) -> Unit
    ): ManualOrder? = connection.prepareStatement("$selectSql WHERE $where").use {
        bind(it)
        it.executeQuery().use { result -> if (result.next()) result.toManualOrder() else null }
    }

    private fun selectMany(
        connection: Connection,
        where: String,
        limit: Int,
        offset: Long,
        bind: (java.sql.PreparedStatement) -> Unit
    ): List<ManualOrder> = connection.prepareStatement(
        "$selectSql WHERE $where ORDER BY o.created_at DESC LIMIT $limit OFFSET ${offset.coerceAtLeast(0)}"
    ).use {
        bind(it)
        it.executeQuery().use { result ->
            buildList { while (result.next()) add(result.toManualOrder()) }
        }
    }

    private fun ResultSet.toManualOrder() = ManualOrder(
        id = getObject("id", UUID::class.java),
        accountId = getObject("account_id", UUID::class.java),
        accountRef = getString("external_ref"),
        orderNumber = getString("order_number"),
        planId = getString("plan_id"),
        status = BillingOrderStatus.valueOf(getString("status")),
        amountMinor = getLong("amount_minor"),
        currency = getString("currency"),
        createdAt = checkNotNull(getInstant("created_at")),
        expiresAt = checkNotNull(getInstant("expires_at")),
        paidAt = getInstant("paid_at"),
        paidAmountMinor = getLong("paid_amount_minor").takeIf { !wasNull() },
        paymentMethod = getString("payment_method"),
        resolutionNote = getString("resolution_note"),
        resolvedBy = getString("resolved_by")
    )

    /** Активные тарифы из каталога планов (billing_plans) по возрастанию цены. */
    fun activePlans(): List<BillingPlan> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT id, product_id, display_name, duration_days, amount_minor, currency,
                   paddle_price_id, heleket_currency, active
            FROM billing_plans WHERE active = TRUE ORDER BY amount_minor ASC, id ASC
            """.trimIndent()
        ).use {
            it.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            BillingPlan(
                                id = result.getString("id"),
                                productId = result.getString("product_id"),
                                displayName = result.getString("display_name"),
                                durationDays = result.getInt("duration_days"),
                                amountMinor = result.getLong("amount_minor"),
                                currency = result.getString("currency").trim(),
                                paddlePriceId = result.getString("paddle_price_id"),
                                heleketCurrency = result.getString("heleket_currency"),
                                active = result.getBoolean("active")
                            )
                        )
                    }
                }
            }
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val value = block(connection)
            connection.commit()
            value
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }
}
