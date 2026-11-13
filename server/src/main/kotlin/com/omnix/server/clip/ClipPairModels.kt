package com.omnix.server.clip

import com.omnix.server.license.LicenseBillingStatus
import java.sql.ResultSet
import java.time.Instant
import java.util.Locale
import java.util.UUID

/*
 * Реестр готовых пар OMNIX Clip по Bluetooth-адресу (V012, режим MAC) и
 * каталог моделей/цветов.
 *
 * ВРЕМЕННАЯ мера до прошивки с ключом (режим KEY, V008): BT-адрес можно
 * подделать на root-телефоне. Реестр, статусы и привязки к аккаунтам при
 * переходе на KEY не меняются — меняется только способ опознать пару.
 */

/** Настройки реестра пар (env, см. ServerConfig). */
data class ClipPairConfig(
    /** CLIP_MAX_PAIRS_PER_ACCOUNT: сколько ACTIVE-пар может быть у одного аккаунта. */
    val maxPairsPerAccount: Int = DEFAULT_MAX_PAIRS_PER_ACCOUNT,
    /**
     * CLIP_PAIR_REQUIRED: клиент закрывает облачный ИИ без подключённой
     * привязанной пары. Выключатель на стороне сервера — без обновления приложения.
     */
    val pairRequired: Boolean = true,
    /** Тариф лицензии, которую даёт привязка пары аккаунту без лицензии. */
    val defaultPlanId: String = DEFAULT_PLAN_ID,
    /** Пробный период при первой в истории привязке пары («7 дней Pro»). */
    val trialPlanId: String = DEFAULT_TRIAL_PLAN_ID,
    val trialDays: Long = DEFAULT_TRIAL_DAYS
) {
    init {
        require(maxPairsPerAccount in 1..100) { "CLIP_MAX_PAIRS_PER_ACCOUNT must be in 1..100" }
    }

    companion object {
        const val DEFAULT_MAX_PAIRS_PER_ACCOUNT = 5
        const val DEFAULT_PLAN_ID = "free"
        const val DEFAULT_TRIAL_PLAN_ID = "pro"
        const val DEFAULT_TRIAL_DAYS = 7L
    }
}

/** Нормализация и маскирование BT-адреса. */
object BtAddress {
    private val HEX = Regex("[0-9A-F]{12}")

    /** Адреса-заглушки Android/стека: за ними нет реального устройства. */
    private val PLACEHOLDERS = setOf("000000000000", "020000000000", "FFFFFFFFFFFF")

    /**
     * `aa:bb:cc:dd:ee:ff`, `AA-BB-…`, `aabbccddeeff` → `AA:BB:CC:DD:EE:FF`.
     * null — не адрес или заглушка.
     */
    fun normalize(raw: String?): String? {
        val compact = raw?.trim()?.uppercase(Locale.ROOT)
            ?.filterNot { it == ':' || it == '-' || it == '.' || it.isWhitespace() }
            ?: return null
        if (!HEX.matches(compact) || compact in PLACEHOLDERS) return null
        return compact.chunked(2).joinToString(":")
    }

    /** Для логов и админки: `AA:••:••:••:EE:FF`. */
    fun mask(normalized: String?): String {
        val parts = normalized?.split(':')?.takeIf { it.size == 6 } ?: return "—"
        return "${parts[0]}:••:••:••:${parts[4]}:${parts[5]}"
    }
}

enum class ClipModelStatus { ACTIVE, HIDDEN }

/** Модель/цвет из каталога (полная запись — для админки). */
data class ClipEarbudModel(
    val id: String,
    val displayName: String,
    val colorName: String,
    val colorHex: String,
    val imageUrl: String?,
    val btNamePatterns: List<String>,
    val status: ClipModelStatus,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun toPublic() = ClipModelPublic(id, displayName, colorName, colorHex, imageUrl)
}

/** Публичные поля модели: то, что видит клиент. */
data class ClipModelPublic(
    val id: String,
    val displayName: String,
    val colorName: String,
    val colorHex: String,
    val imageUrl: String?
) {
    companion object {
        /** Из LEFT JOIN с префиксом `m_`; null — у пары нет модели. */
        fun from(result: ResultSet): ClipModelPublic? {
            val id = result.getString("m_id") ?: return null
            return ClipModelPublic(
                id = id,
                displayName = result.getString("m_name"),
                colorName = result.getString("m_color"),
                colorHex = result.getString("m_hex"),
                imageUrl = result.getString("m_image")
            )
        }
    }
}

/** Команда «добавить/изменить модель» из админки. */
data class ClipModelCommand(
    val id: String,
    val displayName: String,
    val colorName: String,
    val colorHex: String,
    val imageUrl: String?,
    val btNamePatterns: List<String>,
    val status: ClipModelStatus
)

sealed interface ClipModelSaveOutcome {
    data class Saved(val model: ClipEarbudModel, val previous: ClipEarbudModel?) : ClipModelSaveOutcome
    data class Invalid(val reason: String) : ClipModelSaveOutcome
    data object AlreadyExists : ClipModelSaveOutcome
    data object NotFound : ClipModelSaveOutcome
}

/** Своя пара аккаунта (validate-ответ, «Мои наушники»). */
data class ClipPairView(
    val clipSerial: String,
    val btAddress: String,
    val model: ClipModelPublic?,
    val boundAt: Instant?,
    val lastSeenAt: Instant?
)

/** Запись реестра для админки. Адрес наружу — только через [BtAddress.mask]. */
data class ClipRegistryEntry(
    val id: UUID,
    val clipSerial: String,
    val btAddress: String?,
    val identityMode: ClipIdentityMode,
    val status: ClipDeviceStatus,
    val ownerAccountId: UUID?,
    val modelId: String?,
    val planId: String?,
    val btName: String?,
    val statusReason: String?,
    val boundAt: Instant?,
    val lastSeenAt: Instant?,
    val createdAt: Instant
)

/** Регистрация пары при упаковке. */
data class ClipPairRegistration(
    val clipSerial: String,
    val btAddress: String,
    val modelId: String? = null,
    val planId: String? = null,
    val btName: String? = null
)

sealed interface ClipRegisterOutcome {
    data class Registered(val entry: ClipRegistryEntry) : ClipRegisterOutcome
    data class Invalid(val reason: String) : ClipRegisterOutcome
    data object DuplicateSerial : ClipRegisterOutcome
    data object DuplicateAddress : ClipRegisterOutcome
    data object UnknownModel : ClipRegisterOutcome
    data object UnknownPlan : ClipRegisterOutcome
}

sealed interface ClipPairBindOutcome {
    /**
     * @param boundNow true — пара привязана этим вызовом; false — повтор
     *        (пара уже была ACTIVE у этого аккаунта), токен перевыпущен.
     * @param licenseGranted true — аккаунту без лицензии выдана лицензия тарифа пары.
     */
    data class Bound(
        val accountId: UUID,
        val accessToken: String,
        val clipSerial: String,
        val model: ClipModelPublic?,
        val boundNow: Boolean,
        val licenseGranted: Boolean,
        val planId: String,
        val productId: String,
        val startsAt: Instant,
        val expiresAt: Instant,
        val billingStatus: LicenseBillingStatus,
        /** Конец пробного Pro, если этот вызов его выдал; иначе null. */
        val trialExpiresAt: Instant? = null
    ) : ClipPairBindOutcome

    data object InvalidRequest : ClipPairBindOutcome
    data object UnknownPair : ClipPairBindOutcome
    data object Revoked : ClipPairBindOutcome
    data object OwnedByAnotherAccount : ClipPairBindOutcome
    data object PairLimitReached : ClipPairBindOutcome
    data object InvalidPlan : ClipPairBindOutcome
    data object AccountDisabled : ClipPairBindOutcome

    /** У аккаунта лицензия, но действующей для этого телефона нет (validate не пройдёт). */
    data object NoLicenseForDevice : ClipPairBindOutcome
}

/** Что клиент узнаёт о подключённой паре (только для аутентифицированного). */
enum class ClipPairState {
    /** Пара привязана к этому аккаунту. */
    OWNED,

    /** Зарегистрирована и свободна: можно добавить к аккаунту. */
    AVAILABLE,

    /** Чужая или заблокированная: подробности не раскрываем. */
    UNAVAILABLE,

    /** Адреса нет в реестре. */
    UNKNOWN
}

data class ClipPairStatusView(
    val state: ClipPairState,
    /** Серийник и модель — только для OWNED/AVAILABLE. */
    val clipSerial: String? = null,
    val model: ClipModelPublic? = null
)

sealed interface ClipAdminActionOutcome {
    data class Done(val before: ClipRegistryEntry, val after: ClipRegistryEntry) : ClipAdminActionOutcome
    data object NotFound : ClipAdminActionOutcome
    data object InvalidState : ClipAdminActionOutcome
}

fun ClipModelPublic.toModelDto(): com.omnix.server.api.ClipModelDto =
    com.omnix.server.api.ClipModelDto(id, displayName, colorName, colorHex, imageUrl)

fun ClipPairView.toPairDto(): com.omnix.server.api.ClipPairDto =
    com.omnix.server.api.ClipPairDto(
        clipSerial = clipSerial,
        btAddress = btAddress,
        model = model?.toModelDto(),
        boundAt = boundAt?.toString(),
        lastSeenAt = lastSeenAt?.toString()
    )
