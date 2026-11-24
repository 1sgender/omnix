package com.omnix.assistant.agent.tools.communication

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настоящая отправка SMS через [SmsManager] с ожиданием результата.
 *
 * Раньше вызов sendTextMessage шёл без sentIntent, и инструмент говорил «отправлено»,
 * не зная, принял ли модем сообщение (режим полёта, нет сети, лимит). Теперь на каждую
 * часть сообщения вешается sentIntent; успех сообщается, только когда ВСЕ части получили
 * RESULT_OK. «Доставлено адресату» не заявляется: это отдельный отчёт оператора.
 *
 * SIM: берётся SIM, выбранная в системе для SMS; если системного выбора нет
 * («спрашивать каждый раз»), используется менеджер по умолчанию.
 */
@Singleton
class SmsDispatcher @Inject constructor(
    @ApplicationContext private val context: Context
) : SmsTransport {

    private val requestCounter = AtomicInteger(0)

    override suspend fun send(phoneNumber: String, message: String): SmsSendOutcome {
        val manager = smsManager() ?: return SmsSendOutcome.Unavailable
        val action = "$ACTION_PREFIX.${UUID.randomUUID()}"
        val results = CompletableDeferred<SmsSendOutcome>()
        val received = ArrayList<Int>()
        var expected = 0

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val code = resultCode
                synchronized(received) {
                    received += code
                    // Первая же ошибка части = сообщение не ушло целиком: ждать остальные незачем.
                    if (!SmsResultCodes.isOk(code) || (expected > 0 && received.size >= expected)) {
                        results.complete(summarize(received))
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val parts = manager.divideMessage(message)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            repeat(parts.size) {
                val intent = Intent(action).setPackage(context.packageName)
                sentIntents += PendingIntent.getBroadcast(
                    context,
                    requestCounter.incrementAndGet(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
            }
            synchronized(received) {
                expected = parts.size
                if (received.size >= expected) results.complete(summarize(received))
            }
            try {
                if (parts.size == 1) {
                    manager.sendTextMessage(phoneNumber, null, message, sentIntents[0], null)
                } else {
                    manager.sendMultipartTextMessage(phoneNumber, null, parts, ArrayList(sentIntents), null)
                }
            } catch (e: SecurityException) {
                return SmsSendOutcome.PermissionDenied
            } catch (e: IllegalArgumentException) {
                return SmsSendOutcome.InvalidArgument
            }
            val outcome = withTimeoutOrNull(SENT_TIMEOUT_MS) { results.await() }
            return when (outcome) {
                null -> SmsSendOutcome.Unknown(parts.size)
                is SmsSendOutcome.Sent -> SmsSendOutcome.Sent(parts.size)
                else -> outcome
            }
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "receiver already unregistered", e)
            }
        }
    }

    private fun summarize(codes: List<Int>): SmsSendOutcome {
        val failed = codes.firstOrNull { !SmsResultCodes.isOk(it) }
        return if (failed == null) {
            SmsSendOutcome.Sent(codes.size)
        } else {
            SmsSendOutcome.Failed(failed, SmsResultCodes.describe(failed))
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? {
        val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val valid = subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val base = context.getSystemService(SmsManager::class.java)
                if (valid) base?.createForSubscriptionId(subscriptionId) ?: base else base
            }
            valid -> SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            else -> SmsManager.getDefault()
        }
    }

    private companion object {
        const val TAG = "SmsDispatcher"
        const val ACTION_PREFIX = "com.omnix.assistant.SMS_SENT"

        /** Модему обычно нужны секунды; дольше ждать значит держать пользователя в неведении. */
        const val SENT_TIMEOUT_MS = 30_000L
    }
}
