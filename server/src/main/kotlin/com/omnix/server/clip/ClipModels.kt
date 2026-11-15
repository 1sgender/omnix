package com.omnix.server.clip

import java.time.Instant

/**
 * Криптографическая идентичность OMNIX Clip (V008).
 *
 * Идентичность устройства = пара ключей, сгенерированная при производстве;
 * Bluetooth-имя и MAC используются ТОЛЬКО для транспорта ( discovery),
 * не для доверия: их можно скопировать/подменить. Доверие = подпись challenge
 * приватным ключом, проверенная зарегистрированным публичным ключом.
 *
 * Сервер знает: Clip #serial, Public Key, Owner, License(=account), Status.
 */
/**
 * PROVISIONED — зарегистрирована, ничья; ACTIVE — привязана к аккаунту;
 * RELEASED — владелец сам отвязал (передача/продажа), снова привязываемая;
 * REVOKED — заблокирована, не привязывается и не работает никогда.
 */
enum class ClipDeviceStatus { PROVISIONED, ACTIVE, RELEASED, REVOKED }

/**
 * Режим идентичности записи реестра (V012).
 *
 * MAC — ВРЕМЕННЫЙ режим: пара опознаётся по BT-адресу, который владелец
 * зарегистрировал при упаковке. Адрес подделывается на root-телефоне.
 * KEY — целевой режим V008 (ключ в прошивке, challenge-response); когда
 * появится прошивка, записи переводятся в KEY без смены реестра и привязок.
 */
enum class ClipIdentityMode { MAC, KEY }

data class ClipDevice(
    val id: java.util.UUID,
    val clipSerial: String,
    /** null — запись режима MAC (V012), ключа нет. */
    val publicKey: ByteArray?,
    val ownerAccountId: java.util.UUID?,
    val status: ClipDeviceStatus,
    val boundAt: Instant?,
    val lastVerifiedAt: Instant?
)

/** Выпуск challenge для attest (single-use, ограничен по времени). */
data class ClipChallenge(
    val challengeId: java.util.UUID,
    val clipSerial: String,
    val nonce: ByteArray,
    /** Точное значение, входящее в подписываемое сообщение. */
    val issuedAtMs: Long,
    val expiresAt: Instant
)

sealed interface ClipProvisionOutcome {
    data class Created(val clip: ClipDevice) : ClipProvisionOutcome
    data object AlreadyExists : ClipProvisionOutcome
    data object InvalidPublicKey : ClipProvisionOutcome
}

sealed interface ClipChallengeOutcome {
    data class Issued(val challenge: ClipChallenge) : ClipChallengeOutcome
    data object UnknownClip : ClipChallengeOutcome
    data object ClipRevoked : ClipChallengeOutcome
}

sealed interface ClipAttestOutcome {
    /**
     * @param boundNow true — этот attest ПЕРВЫМ привязал клип к аккаунту
     *        вызывателя (предыдущий владелец отсутствовал).
     */
    data class Valid(
        val clipSerial: String,
        val ownerAccountId: java.util.UUID,
        val boundNow: Boolean,
        val status: ClipDeviceStatus,
        /** Зарегистрированный публичный ключ (base64 SPKI) — якорь офлайн-доверия. */
        val publicKeyBase64: String
    ) : ClipAttestOutcome

    data object UnknownClip : ClipAttestOutcome
    data object ClipRevoked : ClipAttestOutcome

    /** Challenge не существует / истёк / уже использован (replay). */
    data object ChallengeInvalid : ClipAttestOutcome

    /** Подпись не прошла проверку зарегистрированным публичным ключом. */
    data object BadSignature : ClipAttestOutcome

    /** Клип уже привязан к ДРУГОМУ аккаунту. */
    data object OwnerMismatch : ClipAttestOutcome
}
