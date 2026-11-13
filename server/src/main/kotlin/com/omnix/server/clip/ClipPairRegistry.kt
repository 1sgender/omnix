package com.omnix.server.clip

import com.omnix.server.license.LicenseBillingStatus
import com.omnix.server.license.LicenseCrypto
import com.omnix.server.persistence.getInstant
import com.omnix.server.persistence.setInstant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource

/**
 * Реестр готовых пар OMNIX Clip по BT-адресу и каталог моделей (V012).
 *
 * Привязка пары без кода повторяет redeem одноразового кода: аккаунт (если
 * его нет) + лицензия тарифа пары (если у аккаунта её нет) + omx_-токен,
 * привязанный к телефону (V007) и к записи реестра (`api_tokens.clip_device_id`).
 * Такой токен на AI-пути работает, только пока у аккаунта есть хотя бы одна
 * ACTIVE-пара (проверка в JdbcLicenseRepository.authenticateAccessToken).
 *
 * Правила владения: одна пара — один аккаунт; у аккаунта до
 * [ClipPairConfig.maxPairsPerAccount] ACTIVE-пар. Новый аккаунт получает
 * пару только из PROVISIONED (зарегистрирована) или RELEASED (прежний
 * владелец сам отвязал). REVOKED не привязывается никогда.
 *
 * ВРЕМЕННО: опознание по MAC (подделывается на root-телефоне). Режим KEY
 * (прошивка с ключом, V008) заменит MAC без смены этой таблицы и привязок.
 */
class ClipPairRegistry(
    private val dataSource: DataSource,
    private val crypto: LicenseCrypto,
    private val config: ClipPairConfig = ClipPairConfig(),
    private val clock: Clock = Clock.systemUTC()
) {
    private val patternsSerializer = ListSerializer(String.serializer())

    /* ------------------------------------------------------------ catalog */

    fun listModels(includeHidden: Boolean): List<ClipEarbudModel> = transaction { connection ->
        connection.prepareStatement(
            """
            SELECT id, display_name, color_name, color_hex, image_url, bt_name_patterns::text AS patterns,
                   status, created_at, updated_at
            FROM clip_models ${if (includeHidden) "" else "WHERE status = 'ACTIVE'"}
            ORDER BY display_name, color_name, id
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toModel()) } }
        }
    }

    /** Добавить ([create] = true) или изменить модель. */
    fun saveModel(command: ClipModelCommand, create: Boolean): ClipModelSaveOutcome {
        val normalized = normalizeModel(command) ?: return ClipModelSaveOutcome.Invalid(MODEL_INVALID_REASON)
        val now = clock.instant()
        return transaction { connection ->
            val previous = findModel(connection, normalized.id)
            if (create && previous != null) return@transaction ClipModelSaveOutcome.AlreadyExists
            if (!create && previous == null) return@transaction ClipModelSaveOutcome.NotFound
            val sql = if (create) {
                """
                INSERT INTO clip_models(display_name, color_name, color_hex, image_url, bt_name_patterns,
                                        status, created_at, updated_at, id)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """.trimIndent()
            } else {
                """
                UPDATE clip_models SET display_name = ?, color_name = ?, color_hex = ?, image_url = ?,
                       bt_name_patterns = CAST(? AS jsonb), status = ?, created_at = COALESCE(created_at, ?),
                       updated_at = ?
                WHERE id = ?
                """.trimIndent()
            }
            connection.prepareStatement(sql).use {
                it.setString(1, normalized.displayName)
                it.setString(2, normalized.colorName)
                it.setString(3, normalized.colorHex)
                it.setString(4, normalized.imageUrl)
                it.setString(5, Json.encodeToString(patternsSerializer, normalized.btNamePatterns))
                it.setString(6, normalized.status.name)
                it.setInstant(7, now)
                it.setInstant(8, now)
                it.setString(9, normalized.id)
                it.executeUpdate()
            }
            ClipModelSaveOutcome.Saved(checkNotNull(findModel(connection, normalized.id)), previous)
        }
    }

    /* ----------------------------------------------------------- registry */

    fun register(registration: ClipPairRegistration): ClipRegisterOutcome {
        val serial = registration.clipSerial.trim()
        if (!SERIAL.matches(serial)) return ClipRegisterOutcome.Invalid("serial")
        val address = BtAddress.normalize(registration.btAddress)
            ?: return ClipRegisterOutcome.Invalid("bt_address")
        val btName = registration.btName?.trim()?.takeIf { it.isNotEmpty() }
        if (btName != null && (btName.length > 64 || btName.any(Char::isISOControl))) {
            return ClipRegisterOutcome.Invalid("bt_name")
        }
        val modelId = registration.modelId?.trim()?.takeIf { it.isNotEmpty() }
        val planId = registration.planId?.trim()?.takeIf { it.isNotEmpty() }
        val now = clock.instant()
        return transaction { connection ->
            if (modelId != null && findModel(connection, modelId) == null) {
                return@transaction ClipRegisterOutcome.UnknownModel
            }
            if (planId != null && planProduct(connection, planId) == null) {
                return@transaction ClipRegisterOutcome.UnknownPlan
            }
            if (exists(connection, "SELECT 1 FROM clip_devices WHERE clip_serial = ?", serial)) {
                return@transaction ClipRegisterOutcome.DuplicateSerial
            }
            if (exists(connection, "SELECT 1 FROM clip_devices WHERE bt_address = ?", address)) {
                return@transaction ClipRegisterOutcome.DuplicateAddress
            }
            val id = UUID.randomUUID()
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO clip_devices(id, clip_serial, public_key, status, created_at, updated_at,
                                             bt_address, identity_mode, plan_id, model_id, bt_name)
                    VALUES (?, ?, NULL, 'PROVISIONED', ?, ?, ?, 'MAC', ?, ?, ?)
                    """.trimIndent()
                ).use {
                    it.setObject(1, id)
                    it.setString(2, serial)
                    it.setInstant(3, now)
                    it.setInstant(4, now)
                    it.setString(5, address)
                    it.setString(6, planId)
                    it.setString(7, modelId)
                    it.setString(8, btName)
                    it.executeUpdate()
                }
            } catch (e: SQLException) {
                // Гонка двух регистраций одной пары: UNIQUE решает за нас.
                if (e.sqlState == UNIQUE_VIOLATION) return@transaction ClipRegisterOutcome.DuplicateAddress
                throw e
            }
            ClipRegisterOutcome.Registered(checkNotNull(findEntry(connection, "d.id = ?", id)))
        }
    }

    fun list(status: ClipDeviceStatus?, query: String?, limit: Int): List<ClipRegistryEntry> =
        transaction { connection ->
            val q = query?.trim()?.takeIf { it.isNotEmpty() }?.take(64)
            val address = BtAddress.normalize(q)
            val conditions = buildList {
                if (status != null) add("d.status = ?")
                if (q != null) add("(d.clip_serial ILIKE ? OR d.bt_address = ? OR CAST(d.owner_account_id AS TEXT) = ?)")
            }
            val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
            connection.prepareStatement(
                "$ENTRY_SELECT $where ORDER BY d.created_at DESC LIMIT ?"
            ).use { statement ->
                var index = 1
                if (status != null) statement.setString(index++, status.name)
                if (q != null) {
                    statement.setString(index++, "%" + q.replace("%", "").replace("_", "\\_") + "%")
                    statement.setString(index++, address ?: "")
                    statement.setString(index++, q.lowercase(Locale.ROOT))
                }
                statement.setInt(index, limit.coerceIn(1, 1_000))
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toEntry()) } }
            }
        }

    /** Админ: отвязать (→ PROVISIONED). Снимает и блокировку REVOKED. */
    fun adminUnbind(id: UUID, reason: String): ClipAdminActionOutcome =
        adminTransition(id, reason, ClipDeviceStatus.PROVISIONED) { it != ClipDeviceStatus.PROVISIONED }

    /** Админ: заблокировать (→ REVOKED). Пара перестаёт давать доступ сразу. */
    fun adminRevoke(id: UUID, reason: String): ClipAdminActionOutcome =
        adminTransition(id, reason, ClipDeviceStatus.REVOKED) { it != ClipDeviceStatus.REVOKED }

    /* ------------------------------------------------------------- client */

    /**
     * Что за пара подключена к телефону аутентифицированного аккаунта.
     * Своя ACTIVE — заодно обновляет «последний раз» (`last_verified_at`).
     * Про чужие/заблокированные — только UNAVAILABLE, без подробностей.
     */
    fun statusFor(accountId: UUID, rawAddress: String): ClipPairStatusView? {
        val address = BtAddress.normalize(rawAddress) ?: return null
        val now = clock.instant()
        return transaction { connection ->
            val entry = findEntry(connection, "d.bt_address = ?", address)
                ?: return@transaction ClipPairStatusView(ClipPairState.UNKNOWN)
            when (entry.status) {
                ClipDeviceStatus.ACTIVE ->
                    if (entry.ownerAccountId == accountId) {
                        touchSeen(connection, entry.id, now)
                        ClipPairStatusView(ClipPairState.OWNED, entry.clipSerial, publicModel(connection, entry.modelId))
                    } else {
                        ClipPairStatusView(ClipPairState.UNAVAILABLE)
                    }
                ClipDeviceStatus.PROVISIONED, ClipDeviceStatus.RELEASED ->
                    ClipPairStatusView(ClipPairState.AVAILABLE, entry.clipSerial, publicModel(connection, entry.modelId))
                ClipDeviceStatus.REVOKED -> ClipPairStatusView(ClipPairState.UNAVAILABLE)
            }
        }
    }

    /**
     * Привязка подключённой пары.
     *
     * @param accountId аккаунт вызывающего (omx_-токен) или null — новый
     *        покупатель без аккаунта: аккаунт создаётся, как при redeem.
     */
    fun bind(
        rawAddress: String,
        deviceId: String,
        accountId: UUID?,
        requestId: String,
        remoteAddress: String?
    ): ClipPairBindOutcome {
        val address = BtAddress.normalize(rawAddress) ?: return ClipPairBindOutcome.InvalidRequest
        val deviceHash = runCatching { crypto.deviceHash(deviceId) }.getOrElse {
            return ClipPairBindOutcome.InvalidRequest
        }
        val now = clock.instant()
        return transaction { connection ->
            val pair = lockPair(connection, address) ?: return@transaction ClipPairBindOutcome.UnknownPair
            if (pair.status == ClipDeviceStatus.REVOKED) return@transaction ClipPairBindOutcome.Revoked
            val retry = pair.status == ClipDeviceStatus.ACTIVE
            if (retry) {
                val sameAccount = accountId != null && pair.ownerAccountId == accountId
                // Без токена (переустановка) повтор допустим только с того же телефона.
                val boundHash = pair.boundDeviceHash
                val sameDevice = accountId == null && boundHash != null &&
                    crypto.constantTimeEquals(boundHash, deviceHash)
                if (!sameAccount && !sameDevice) {
                    return@transaction ClipPairBindOutcome.OwnedByAnotherAccount
                }
            }
            // PROVISIONED/RELEASED — свободна: владельцем станет вызывающий
            // (или новый аккаунт); ACTIVE-повтор — прежний владелец.
            val owner: UUID? = if (retry) pair.ownerAccountId else accountId
            if (owner != null) {
                if (!accountActive(connection, owner)) return@transaction ClipPairBindOutcome.AccountDisabled
                if (!retry && activePairCount(connection, owner) >= config.maxPairsPerAccount) {
                    return@transaction ClipPairBindOutcome.PairLimitReached
                }
            }
            // Лицензия: действующая этого телефона, иначе (если у аккаунта нет
            // никакой) — новая тарифа пары. Решаем ДО записей: отказ ничего не меняет.
            val existing = owner?.let { activeLicenses(connection, it, now) }.orEmpty()
            val forDevice = existing.firstOrNull { grant ->
                grant.deviceHash?.let { crypto.constantTimeEquals(it, deviceHash) } == true
            }
            if (forDevice == null && existing.isNotEmpty()) return@transaction ClipPairBindOutcome.NoLicenseForDevice
            val grantPlanId = pair.planId ?: config.defaultPlanId
            val grantPlan = if (forDevice == null) {
                planProduct(connection, grantPlanId) ?: return@transaction ClipPairBindOutcome.InvalidPlan
            } else {
                null
            }

            val account = owner ?: createAccount(connection, now)
            if (!retry) {
                connection.prepareStatement(
                    """
                    UPDATE clip_devices SET status = 'ACTIVE', owner_account_id = ?, bound_at = ?,
                           last_verified_at = ?, bound_device_hash = ?, status_reason = NULL, updated_at = ?
                    WHERE id = ? AND status IN ('PROVISIONED', 'RELEASED')
                    """.trimIndent()
                ).use {
                    it.setObject(1, account)
                    it.setInstant(2, now)
                    it.setInstant(3, now)
                    it.setBytes(4, deviceHash)
                    it.setInstant(5, now)
                    it.setObject(6, pair.id)
                    check(it.executeUpdate() == 1) { "clip pair changed concurrently" }
                }
            } else {
                touchSeen(connection, pair.id, now)
            }
            val license = forDevice ?: grantLicense(
                connection, account, grantPlanId, checkNotNull(grantPlan), deviceHash, pair.clipSerial, now,
                trial = false
            )
            // «7 дней Pro при первом подключении»: только новая привязка, пара
            // ни разу не давала пробный, у аккаунта его не было, платного нет.
            val trialExpiresAt = if (!retry && pair.trialGrantedAt == null) {
                grantTrialIfEligible(connection, account, pair, deviceHash, now)
            } else {
                null
            }
            val token = crypto.generateAccessToken()
            connection.prepareStatement(
                """
                INSERT INTO api_tokens(id, account_id, token_hash, status, issued_at, expires_at, created_at,
                                       device_hash, clip_device_id)
                VALUES (?, ?, ?, 'ACTIVE', ?, NULL, ?, ?, ?)
                """.trimIndent()
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, account)
                it.setBytes(3, crypto.accessTokenHash(token))
                it.setInstant(4, now)
                it.setInstant(5, now)
                it.setBytes(6, deviceHash)
                it.setObject(7, pair.id)
                it.executeUpdate()
            }
            licenseAudit(
                connection, if (retry) "CLIP_PAIR_BIND_RETRY" else "CLIP_PAIR_BOUND", pair.id,
                requestId, remoteAddress,
                """{"clip_serial":"${pair.clipSerial}","bt_address":"${BtAddress.mask(address)}","license_granted":${forDevice == null},"trial_granted":${trialExpiresAt != null}}""",
                now
            )
            ClipPairBindOutcome.Bound(
                accountId = account,
                accessToken = token,
                clipSerial = pair.clipSerial,
                model = publicModel(connection, pair.modelId),
                boundNow = !retry,
                licenseGranted = forDevice == null,
                planId = license.planId,
                productId = license.productId,
                startsAt = license.startsAt,
                expiresAt = license.expiresAt,
                billingStatus = license.billingStatus,
                trialExpiresAt = trialExpiresAt
            )
        }
    }

    /**
     * Пробный Pro: отдельная лицензия плана [ClipPairConfig.trialPlanId] на
     * [ClipPairConfig.trialDays] дней с `metadata.trial = true`. Базовая
     * лицензия остаётся — после пробного аккаунт просто на ней (free).
     * Выбор активного тарифа предпочитает действующий пробный (см.
     * JdbcLicenseRepository: ORDER BY trial). Нет плана pro — без пробного.
     *
     * @return конец пробного периода или null (не положен).
     */
    private fun grantTrialIfEligible(
        connection: Connection,
        accountId: UUID,
        pair: LockedPair,
        deviceHash: ByteArray,
        now: Instant
    ): Instant? {
        val hadTrial = connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM licenses WHERE account_id = ? AND metadata->>'trial' = 'true')"
        ).use {
            it.setObject(1, accountId)
            it.executeQuery().use { result -> result.next(); result.getBoolean(1) }
        }
        if (hadTrial) return null
        val hasPaid = connection.prepareStatement(
            """
            SELECT EXISTS(
                SELECT 1 FROM licenses l JOIN billing_plans p ON p.id = l.plan_id
                WHERE l.account_id = ? AND l.status = 'ACTIVE' AND p.active = TRUE AND p.amount_minor > 0
                  AND l.starts_at <= ? AND l.expires_at > ?
                  AND l.billing_status IN ('GRANTED', 'PAID', 'CANCELED')
            )
            """.trimIndent()
        ).use {
            it.setObject(1, accountId)
            it.setInstant(2, now)
            it.setInstant(3, now)
            it.executeQuery().use { result -> result.next(); result.getBoolean(1) }
        }
        if (hasPaid) return null
        val plan = planProduct(connection, config.trialPlanId) ?: return null
        val trial = grantLicense(
            connection, accountId, config.trialPlanId, plan.copy(durationDays = config.trialDays.toInt()),
            deviceHash, pair.clipSerial, now, trial = true
        )
        connection.prepareStatement("UPDATE clip_devices SET trial_granted_at = ? WHERE id = ?").use {
            it.setInstant(1, now)
            it.setObject(2, pair.id)
            it.executeUpdate()
        }
        return trial.expiresAt
    }

    /**
     * Владелец сам отвязывает свою пару (передача/продажа) → RELEASED.
     * После этого её может привязать другой аккаунт.
     */
    fun releaseByOwner(accountId: UUID, clipSerial: String, requestId: String, remoteAddress: String?): Boolean {
        val serial = clipSerial.trim()
        if (!SERIAL.matches(serial)) return false
        val now = clock.instant()
        return transaction { connection ->
            val id = connection.prepareStatement(
                """
                UPDATE clip_devices SET status = 'RELEASED', owner_account_id = NULL, bound_at = NULL,
                       bound_device_hash = NULL, status_reason = 'released by owner', updated_at = ?
                WHERE clip_serial = ? AND owner_account_id = ? AND status = 'ACTIVE'
                RETURNING id
                """.trimIndent()
            ).use {
                it.setInstant(1, now)
                it.setString(2, serial)
                it.setObject(3, accountId)
                it.executeQuery().use { result -> if (result.next()) result.getObject(1, UUID::class.java) else null }
            } ?: return@transaction false
            licenseAudit(
                connection, "CLIP_PAIR_RELEASED", id, requestId, remoteAddress,
                """{"clip_serial":"$serial","account_id":"$accountId"}""", now
            )
            true
        }
    }

    /* ------------------------------------------------------------ helpers */

    private fun adminTransition(
        id: UUID,
        reason: String,
        target: ClipDeviceStatus,
        allowedFrom: (ClipDeviceStatus) -> Boolean
    ): ClipAdminActionOutcome {
        val cleanReason = reason.trim().take(256)
        val now = clock.instant()
        return transaction { connection ->
            val before = findEntry(connection, "d.id = ?", id, forUpdate = true)
                ?: return@transaction ClipAdminActionOutcome.NotFound
            if (!allowedFrom(before.status)) return@transaction ClipAdminActionOutcome.InvalidState
            val sql = if (target == ClipDeviceStatus.PROVISIONED) {
                """
                UPDATE clip_devices SET status = 'PROVISIONED', owner_account_id = NULL, bound_at = NULL,
                       bound_device_hash = NULL, status_reason = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            } else {
                // Владельца оставляем для истории: доступ даёт только ACTIVE.
                "UPDATE clip_devices SET status = 'REVOKED', status_reason = ?, updated_at = ? WHERE id = ?"
            }
            connection.prepareStatement(sql).use {
                it.setString(1, cleanReason)
                it.setInstant(2, now)
                it.setObject(3, id)
                it.executeUpdate()
            }
            ClipAdminActionOutcome.Done(before, checkNotNull(findEntry(connection, "d.id = ?", id)))
        }
    }

    private data class LockedPair(
        val id: UUID,
        val clipSerial: String,
        val status: ClipDeviceStatus,
        val ownerAccountId: UUID?,
        val boundDeviceHash: ByteArray?,
        val planId: String?,
        val modelId: String?,
        val trialGrantedAt: Instant?
    )

    private data class LicenseGrant(
        val planId: String,
        val productId: String,
        val startsAt: Instant,
        val expiresAt: Instant,
        val billingStatus: LicenseBillingStatus,
        val deviceHash: ByteArray?
    )

    private data class PlanProduct(val productId: String, val durationDays: Int)

    private fun lockPair(connection: Connection, address: String): LockedPair? =
        connection.prepareStatement(
            """
            SELECT id, clip_serial, status, owner_account_id, bound_device_hash, plan_id, model_id,
                   trial_granted_at
            FROM clip_devices WHERE bt_address = ? FOR UPDATE
            """.trimIndent()
        ).use {
            it.setString(1, address)
            it.executeQuery().use { result ->
                if (!result.next()) return@use null
                LockedPair(
                    id = result.getObject("id", UUID::class.java),
                    clipSerial = result.getString("clip_serial"),
                    status = ClipDeviceStatus.valueOf(result.getString("status")),
                    ownerAccountId = result.getObject("owner_account_id", UUID::class.java),
                    boundDeviceHash = result.getBytes("bound_device_hash"),
                    planId = result.getString("plan_id"),
                    modelId = result.getString("model_id"),
                    trialGrantedAt = result.getInstant("trial_granted_at")
                )
            }
        }

    private fun accountActive(connection: Connection, accountId: UUID): Boolean =
        connection.prepareStatement("SELECT status FROM accounts WHERE id = ?").use {
            it.setObject(1, accountId)
            it.executeQuery().use { result -> result.next() && result.getString(1) == "ACTIVE" }
        }

    private fun activePairCount(connection: Connection, accountId: UUID): Int =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM clip_devices WHERE owner_account_id = ? AND status = 'ACTIVE'"
        ).use {
            it.setObject(1, accountId)
            it.executeQuery().use { result -> result.next(); result.getInt(1) }
        }

    /** Тот же критерий действующей лицензии, что у hasActiveEntitlement. */
    private fun activeLicenses(connection: Connection, accountId: UUID, now: Instant): List<LicenseGrant> =
        connection.prepareStatement(
            """
            SELECT l.plan_id, l.product_id, l.starts_at, l.expires_at, l.billing_status, l.redeemed_device_hash
            FROM licenses l JOIN billing_plans p ON p.id = l.plan_id
            WHERE l.account_id = ? AND l.status = 'ACTIVE' AND p.active = TRUE
              AND l.starts_at <= ? AND l.expires_at > ?
              AND l.billing_status IN ('GRANTED', 'PAID', 'CANCELED')
            ORDER BY l.expires_at DESC
            """.trimIndent()
        ).use {
            it.setObject(1, accountId)
            it.setInstant(2, now)
            it.setInstant(3, now)
            it.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            LicenseGrant(
                                planId = result.getString("plan_id"),
                                productId = result.getString("product_id"),
                                startsAt = checkNotNull(result.getInstant("starts_at")),
                                expiresAt = checkNotNull(result.getInstant("expires_at")),
                                billingStatus = LicenseBillingStatus.valueOf(result.getString("billing_status")),
                                deviceHash = result.getBytes("redeemed_device_hash")
                            )
                        )
                    }
                }
            }
        }

    private fun planProduct(connection: Connection, planId: String): PlanProduct? =
        connection.prepareStatement(
            "SELECT product_id, duration_days FROM billing_plans WHERE id = ? AND active = TRUE"
        ).use {
            it.setString(1, planId)
            it.executeQuery().use { result ->
                if (result.next()) PlanProduct(result.getString(1), result.getInt(2)) else null
            }
        }

    private fun createAccount(connection: Connection, now: Instant): UUID {
        val id = UUID.randomUUID()
        connection.prepareStatement(
            "INSERT INTO accounts(id, external_ref, status, created_at, updated_at) VALUES (?, NULL, 'ACTIVE', ?, ?)"
        ).use {
            it.setObject(1, id)
            it.setInstant(2, now)
            it.setInstant(3, now)
            it.executeUpdate()
        }
        return id
    }

    /**
     * Лицензия, которую даёт привязка пары аккаунту без лицензии: сразу
     * ACTIVE, привязана к телефону. Кода у неё нет: code_hash — хеш случайного
     * значения, которое нигде не сохраняется, погасить его невозможно.
     */
    @Suppress("LongParameterList")
    private fun grantLicense(
        connection: Connection,
        accountId: UUID,
        planId: String,
        plan: PlanProduct,
        deviceHash: ByteArray,
        clipSerial: String,
        now: Instant,
        trial: Boolean
    ): LicenseGrant {
        val id = UUID.randomUUID()
        val expiresAt = now.plus(plan.durationDays.toLong(), ChronoUnit.DAYS)
        connection.prepareStatement(
            """
            INSERT INTO licenses(
                id, code_hash, code_hint, status, billing_status, issued_at, starts_at, expires_at,
                product_id, plan_id, account_id, one_time, redeemed_at, redeemed_device_hash,
                metadata, created_at, updated_at
            ) VALUES (?, ?, 'CLIP', 'ACTIVE', 'GRANTED', ?, ?, ?, ?, ?, ?, TRUE, ?, ?, CAST(? AS jsonb), ?, ?)
            """.trimIndent()
        ).use {
            it.setObject(1, id)
            it.setBytes(2, crypto.licenseCodeHash("clip-pair:" + UUID.randomUUID()))
            it.setInstant(3, now)
            it.setInstant(4, now)
            it.setInstant(5, expiresAt)
            it.setString(6, plan.productId)
            it.setString(7, planId)
            it.setObject(8, accountId)
            it.setInstant(9, now)
            it.setBytes(10, deviceHash)
            it.setString(11, """{"source":"clip_pair","clip_serial":"$clipSerial","trial":$trial}""")
            it.setInstant(12, now)
            it.setInstant(13, now)
            it.executeUpdate()
        }
        return LicenseGrant(planId, plan.productId, now, expiresAt, LicenseBillingStatus.GRANTED, deviceHash)
    }

    private fun touchSeen(connection: Connection, id: UUID, now: Instant) {
        connection.prepareStatement(
            "UPDATE clip_devices SET last_verified_at = ? WHERE id = ?"
        ).use {
            it.setInstant(1, now)
            it.setObject(2, id)
            it.executeUpdate()
        }
    }

    private fun publicModel(connection: Connection, modelId: String?): ClipModelPublic? =
        modelId?.let { findModel(connection, it) }
            ?.takeIf { it.status == ClipModelStatus.ACTIVE }
            ?.toPublic()

    private fun findModel(connection: Connection, id: String): ClipEarbudModel? =
        connection.prepareStatement(
            """
            SELECT id, display_name, color_name, color_hex, image_url, bt_name_patterns::text AS patterns,
                   status, created_at, updated_at
            FROM clip_models WHERE id = ?
            """.trimIndent()
        ).use {
            it.setString(1, id)
            it.executeQuery().use { result -> if (result.next()) result.toModel() else null }
        }

    private fun findEntry(connection: Connection, condition: String, value: Any, forUpdate: Boolean = false): ClipRegistryEntry? =
        connection.prepareStatement(
            "$ENTRY_SELECT WHERE $condition${if (forUpdate) " FOR UPDATE" else ""}"
        ).use {
            it.setObject(1, value)
            it.executeQuery().use { result -> if (result.next()) result.toEntry() else null }
        }

    private fun exists(connection: Connection, sql: String, value: String): Boolean =
        connection.prepareStatement(sql).use {
            it.setString(1, value)
            it.executeQuery().use { result -> result.next() }
        }

    @Suppress("LongParameterList")
    private fun licenseAudit(
        connection: Connection,
        action: String,
        clipId: UUID,
        requestId: String,
        remoteAddress: String?,
        metadataJson: String,
        now: Instant
    ) {
        connection.prepareStatement(
            """
            INSERT INTO license_audit_log(
                id, occurred_at, actor_type, actor_id, action, entity_type,
                entity_id, request_id, remote_address, metadata
            ) VALUES (?, ?, 'DEVICE', NULL, ?, 'CLIP', ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use {
            it.setObject(1, UUID.randomUUID())
            it.setInstant(2, now)
            it.setString(3, action)
            it.setObject(4, clipId)
            it.setString(5, requestId.take(64))
            it.setString(6, remoteAddress?.take(64))
            it.setString(7, metadataJson)
            it.executeUpdate()
        }
    }

    private fun normalizeModel(command: ClipModelCommand): ClipModelCommand? {
        val id = command.id.trim().lowercase(Locale.ROOT)
        val name = command.displayName.trim()
        val color = command.colorName.trim()
        val hex = command.colorHex.trim().uppercase(Locale.ROOT).let { if (it.startsWith("#")) it else "#$it" }
        val image = command.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
        val patterns = command.btNamePatterns.map(String::trim).filter(String::isNotEmpty).distinct()
        val valid = MODEL_ID.matches(id) &&
            name.length in 1..128 && name.none(Char::isISOControl) &&
            color.length in 1..64 && color.none(Char::isISOControl) &&
            COLOR_HEX.matches(hex) &&
            (image == null || (image.length <= 512 && image.startsWith("https://"))) &&
            patterns.size <= 16 && patterns.all { it.length <= 64 && it.none(Char::isISOControl) }
        if (!valid) return null
        return ClipModelCommand(id, name, color, hex, image, patterns, command.status)
    }

    private fun ResultSet.toModel(): ClipEarbudModel = ClipEarbudModel(
        id = getString("id"),
        displayName = getString("display_name"),
        colorName = getString("color_name"),
        colorHex = getString("color_hex"),
        imageUrl = getString("image_url"),
        btNamePatterns = runCatching { Json.decodeFromString(patternsSerializer, getString("patterns")) }
            .getOrDefault(emptyList()),
        status = ClipModelStatus.valueOf(getString("status")),
        createdAt = checkNotNull(getInstant("created_at")),
        updatedAt = checkNotNull(getInstant("updated_at"))
    )

    private fun ResultSet.toEntry(): ClipRegistryEntry = ClipRegistryEntry(
        id = getObject("id", UUID::class.java),
        clipSerial = getString("clip_serial"),
        btAddress = getString("bt_address"),
        identityMode = ClipIdentityMode.valueOf(getString("identity_mode")),
        status = ClipDeviceStatus.valueOf(getString("status")),
        ownerAccountId = getObject("owner_account_id", UUID::class.java),
        modelId = getString("model_id"),
        planId = getString("plan_id"),
        btName = getString("bt_name"),
        statusReason = getString("status_reason"),
        boundAt = getInstant("bound_at"),
        lastSeenAt = getInstant("last_verified_at"),
        createdAt = checkNotNull(getInstant("created_at"))
    )

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    companion object {
        /** Серийник: безопасный набор символов (попадает в JSON аудита без экранирования). */
        val SERIAL = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val MODEL_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val COLOR_HEX = Regex("#[0-9A-F]{6}")
        private const val UNIQUE_VIOLATION = "23505"
        const val MODEL_INVALID_REASON = "model fields are invalid"

        private val ENTRY_SELECT = """
            SELECT d.id, d.clip_serial, d.bt_address, d.identity_mode, d.status, d.owner_account_id,
                   d.model_id, d.plan_id, d.bt_name, d.status_reason, d.bound_at, d.last_verified_at, d.created_at
            FROM clip_devices d
        """.trimIndent()
    }
}
