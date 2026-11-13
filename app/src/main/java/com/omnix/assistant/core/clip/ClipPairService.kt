package com.omnix.assistant.core.clip

import androidx.annotation.StringRes
import com.omnix.assistant.R
import com.omnix.assistant.core.license.ClipModelInfo
import com.omnix.assistant.core.license.ClipPairInfo
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.license.LicenseRefreshResult
import com.omnix.assistant.voice.audio.BluetoothAudioRouter
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Итог действия с парой — сразу с текстом для пользователя. */
enum class ClipPairOutcome(@StringRes val messageRes: Int) {
    ADDED(R.string.omnix_pair_added),
    RELEASED(R.string.omnix_pair_released),
    REGISTERED(R.string.omnix_pair_operator_done),
    NOT_CONNECTED(R.string.omnix_pair_none_connected),
    UNKNOWN(R.string.omnix_pair_unknown),
    TAKEN(R.string.omnix_pair_taken),
    REVOKED(R.string.omnix_pair_revoked),
    LIMIT(R.string.omnix_pair_limit),
    ALREADY_REGISTERED(R.string.omnix_pair_operator_exists),
    FAILED(R.string.omnix_pair_failed)
}

/** Итог привязки: [trialUntil] — конец пробного Pro, если привязка его выдала. */
data class ClipPairBindResult(val outcome: ClipPairOutcome, val trialUntil: Instant? = null)

/** Подключённая пара глазами сервера (для подтверждения «добавить?»). */
sealed interface ConnectedPair {
    data object None : ConnectedPair
    data class Own(val clipSerial: String, val model: ClipModelInfo?) : ConnectedPair
    data class Available(val clipSerial: String, val model: ClipModelInfo?) : ConnectedPair
    data object NotOmnix : ConnectedPair
}

/**
 * Пары OMNIX Clip на телефоне: привязка подключённой пары без кода,
 * «Мои наушники», отвязка своей пары, регистрация пары оператором.
 *
 * Список своих пар — из validate-ответа сервера (LicenseInfo.clipBinding):
 * после каждого изменения лицензия перепроверяется, второго хранилища нет.
 */
@Singleton
class ClipPairService @Inject constructor(
    private val api: HttpClipPairApi,
    private val licenseManager: LicenseManager,
    private val bluetoothAudioRouter: BluetoothAudioRouter
) {
    /** Адрес подключённых сейчас наушников (нормализованный) или null. */
    fun connectedAddress(): String? =
        bluetoothAudioRouter.connectedAudioAddresses().firstNotNullOfOrNull(ClipPairGate::normalize)

    fun myPairs(): List<ClipPairInfo> = licenseManager.getLicenseInfo().clipBinding.pairs

    /**
     * Привязать подключённую пару. [withAccount] = false — первая активация
     * покупателя (кода нет, аккаунт создаёт сервер).
     */
    suspend fun bindConnected(withAccount: Boolean): ClipPairBindResult {
        val address = connectedAddress() ?: return ClipPairBindResult(ClipPairOutcome.NOT_CONNECTED)
        return when (val result = api.bind(address, licenseManager.getDeviceId(), withAccount)) {
            is ClipPairCallResult.Ok -> when (licenseManager.adoptPairAccessToken(result.value.accessToken)) {
                is LicenseRefreshResult.Valid, is LicenseRefreshResult.OfflineGrace -> ClipPairBindResult(
                    ClipPairOutcome.ADDED,
                    result.value.trialExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                )
                else -> ClipPairBindResult(ClipPairOutcome.FAILED)
            }
            is ClipPairCallResult.Refused -> ClipPairBindResult(refusal(result.code))
            ClipPairCallResult.Unavailable -> ClipPairBindResult(ClipPairOutcome.FAILED)
        }
    }

    /** Что за пара подключена: своя, свободная (можно добавить) или не OMNIX. */
    suspend fun connectedPair(): ConnectedPair {
        val address = connectedAddress() ?: return ConnectedPair.None
        return when (val result = api.status(address)) {
            is ClipPairCallResult.Ok -> {
                val status = result.value
                val serial = status.clipSerial
                when {
                    status.state == ClipPairStatus.OWNED && serial != null -> ConnectedPair.Own(serial, status.model)
                    status.state == ClipPairStatus.AVAILABLE && serial != null -> ConnectedPair.Available(serial, status.model)
                    else -> ConnectedPair.NotOmnix
                }
            }
            is ClipPairCallResult.Refused, ClipPairCallResult.Unavailable -> ConnectedPair.None
        }
    }

    /** Отвязать свою пару (её можно будет передать другому человеку). */
    suspend fun release(clipSerial: String): ClipPairOutcome =
        when (val result = api.release(clipSerial)) {
            is ClipPairCallResult.Ok -> {
                licenseManager.refreshFromServer()
                ClipPairOutcome.RELEASED
            }
            is ClipPairCallResult.Refused -> refusal(result.code)
            ClipPairCallResult.Unavailable -> ClipPairOutcome.FAILED
        }

    /** Режим оператора: зарегистрировать подключённую пару под серийником. */
    suspend fun operatorRegister(clipSerial: String, btName: String?): ClipPairOutcome {
        val address = connectedAddress() ?: return ClipPairOutcome.NOT_CONNECTED
        return when (val result = api.operatorRegister(clipSerial.trim(), address, btName)) {
            is ClipPairCallResult.Ok -> ClipPairOutcome.REGISTERED
            is ClipPairCallResult.Refused -> refusal(result.code)
            ClipPairCallResult.Unavailable -> ClipPairOutcome.FAILED
        }
    }

    private fun refusal(code: String): ClipPairOutcome = when (code) {
        "CLIP_UNKNOWN" -> ClipPairOutcome.UNKNOWN
        "CLIP_OWNER_MISMATCH" -> ClipPairOutcome.TAKEN
        "CLIP_REVOKED" -> ClipPairOutcome.REVOKED
        "CLIP_PAIR_LIMIT" -> ClipPairOutcome.LIMIT
        "CLIP_ALREADY_REGISTERED" -> ClipPairOutcome.ALREADY_REGISTERED
        else -> ClipPairOutcome.FAILED
    }
}
