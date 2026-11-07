package com.omnix.assistant.core.headset

/**
 * Привязка OMNIX к своим наушникам (решение владельца 2026-11-06): ассистент
 * работает, только когда подключены наушники OMNIX.
 *
 * Уровень 1 (пилот, безбрендовые клипсы с маркетплейса): наушники узнаются по
 * Bluetooth-адресу из списка, который админ заполняет на телефоне через окно
 * «Связать устройство». Уровень 2 (своя прошивка) добавит проверку ключом;
 * для неё достаточно поменять, как считается [registered], — правило то же.
 */
sealed interface HeadsetGateState {
    /** Ещё не знаем, что подключено (первые миллисекунды после старта). */
    data object Checking : HeadsetGateState

    data class Unlocked(val reason: UnlockReason, val deviceName: String? = null) : HeadsetGateState

    data class Locked(val reason: LockReason) : HeadsetGateState
}

enum class UnlockReason {
    /** Админ (владелец) тестирует без наушников. */
    ADMIN,

    /** Подключены зарегистрированные наушники OMNIX. */
    OMNIX_HEADSET
}

enum class LockReason {
    /** Нет разрешения «Устройства поблизости» — подключённые наушники не видно. */
    NO_PERMISSION,

    /** Bluetooth выключен. */
    BLUETOOTH_OFF,

    /** Наушники OMNIX не подключены. */
    NO_OMNIX_HEADSET
}

/** Наушник, подключённый к телефону: адрес и имя (если система его отдала). */
data class ConnectedHeadset(val address: String, val name: String?)

object OmnixHeadsetPolicy {

    /** Адреса сравниваются без учёта регистра и пробелов: «aa:bb» == «AA:BB». */
    fun normalize(address: String): String = address.trim().uppercase()

    fun decide(
        isAdmin: Boolean,
        enforceForAdmin: Boolean,
        hasPermission: Boolean,
        bluetoothOn: Boolean,
        connected: List<ConnectedHeadset>,
        registered: Set<String>
    ): HeadsetGateState {
        if (isAdmin && !enforceForAdmin) return HeadsetGateState.Unlocked(UnlockReason.ADMIN)
        if (!hasPermission) return HeadsetGateState.Locked(LockReason.NO_PERMISSION)
        if (!bluetoothOn) return HeadsetGateState.Locked(LockReason.BLUETOOTH_OFF)
        val known = registered.mapTo(HashSet()) { normalize(it) }
        val match = connected.firstOrNull { normalize(it.address) in known }
            ?: return HeadsetGateState.Locked(LockReason.NO_OMNIX_HEADSET)
        return HeadsetGateState.Unlocked(UnlockReason.OMNIX_HEADSET, match.name)
    }
}
