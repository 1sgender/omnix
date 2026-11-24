package com.omnix.assistant.agent.tools.communication

/**
 * Итог реальной отправки SMS. Не зависит от Android, чтобы SmsTool проверялся
 * юнит-тестами без телефона.
 */
sealed interface SmsSendOutcome {
    /** Все части сообщения приняты радиомодулем к отправке (SENT, не «доставлено адресату»). */
    data class Sent(val parts: Int) : SmsSendOutcome

    /** Система сообщила об ошибке; [reason] — понятная причина для пользователя. */
    data class Failed(val code: Int, val reason: String) : SmsSendOutcome

    /** Результат не пришёл за отведённое время: неизвестно, ушло ли сообщение. */
    data class Unknown(val parts: Int) : SmsSendOutcome

    /** Службы SMS нет на устройстве. */
    object Unavailable : SmsSendOutcome

    /** Android отклонил отправку из-за отсутствия SEND_SMS. */
    object PermissionDenied : SmsSendOutcome

    /** Номер или текст не принял SmsManager. */
    object InvalidArgument : SmsSendOutcome
}

/** Отправка текста по номеру с ожиданием результата от системы. */
interface SmsTransport {
    suspend fun send(phoneNumber: String, message: String): SmsSendOutcome
}

/**
 * Перевод кодов результата SmsManager.RESULT_ERROR_* в человеческий текст.
 * Коды продублированы числами (стабильный публичный API), чтобы не тянуть Android в тесты.
 */
object SmsResultCodes {
    const val RESULT_OK = -1
    private const val GENERIC_FAILURE = 1
    private const val RADIO_OFF = 2
    private const val NULL_PDU = 3
    private const val NO_SERVICE = 4
    private const val LIMIT_EXCEEDED = 5
    private const val FDN_CHECK_FAILURE = 6
    private const val SHORT_CODE_NOT_ALLOWED = 7
    private const val SHORT_CODE_NEVER_ALLOWED = 8
    private const val RADIO_NOT_AVAILABLE = 9
    private const val NETWORK_REJECT = 10
    private const val INVALID_ARGUMENTS = 11
    private const val INVALID_STATE = 12
    private const val NO_MEMORY = 13
    private const val INVALID_SMS_FORMAT = 14
    private const val SYSTEM_ERROR = 15
    private const val MODEM_ERROR = 16
    private const val NETWORK_ERROR = 17
    private const val ENCODING_ERROR = 18
    private const val INVALID_SMSC_ADDRESS = 19
    private const val OPERATION_NOT_ALLOWED = 20
    private const val INTERNAL_ERROR = 21
    private const val NO_RESOURCES = 22
    private const val CANCELLED = 23
    private const val REQUEST_NOT_SUPPORTED = 24

    fun isOk(code: Int): Boolean = code == RESULT_OK

    fun describe(code: Int): String = when (code) {
        RADIO_OFF, RADIO_NOT_AVAILABLE -> "включён режим полёта или выключена связь"
        NO_SERVICE -> "нет сети оператора"
        LIMIT_EXCEEDED -> "превышен лимит SMS"
        FDN_CHECK_FAILURE -> "номер запрещён списком разрешённых номеров SIM"
        SHORT_CODE_NOT_ALLOWED, SHORT_CODE_NEVER_ALLOWED -> "система не разрешает отправку на этот короткий номер"
        NULL_PDU, INVALID_ARGUMENTS, INVALID_SMS_FORMAT, ENCODING_ERROR -> "номер или текст сообщения некорректны"
        NETWORK_REJECT, NETWORK_ERROR -> "сеть оператора отклонила сообщение"
        INVALID_SMSC_ADDRESS -> "в SIM не задан номер SMS-центра"
        NO_MEMORY, NO_RESOURCES -> "не хватает памяти или ресурсов модема"
        OPERATION_NOT_ALLOWED, REQUEST_NOT_SUPPORTED -> "оператор или SIM не поддерживает отправку SMS"
        CANCELLED -> "отправка отменена системой"
        GENERIC_FAILURE, INVALID_STATE, SYSTEM_ERROR, MODEM_ERROR, INTERNAL_ERROR -> "сбой модема или SIM-карты"
        else -> "неизвестная ошибка отправки (код $code)"
    }
}
