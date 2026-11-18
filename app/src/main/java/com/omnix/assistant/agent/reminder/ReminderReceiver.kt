package com.omnix.assistant.agent.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun reminderManager(): ReminderManager
    fun reminderNotifier(): ReminderNotifier
    fun reminderAnnouncer(): ReminderAnnouncer
}

/**
 * Будильник напоминания, кнопки «Готово» / «Отложить» из уведомления и
 * перепланирование после перезагрузки, смены времени, часового пояса и
 * обновления приложения. Асинхронная часть устроена как у
 * AutomationScheduleReceiver: общий scope, goAsync, жёсткий таймаут и
 * ровно один finish().
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val entryPoint = try {
            EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cannot obtain reminder entry point", e)
            return
        }
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        scope.launch {
            try {
                withTimeout(DISPATCH_TIMEOUT_MS) { handle(entryPoint, action, id) }
            } catch (ce: CancellationException) {
                Log.w(TAG, "reminder dispatch cancelled: $action")
                throw ce
            } finally {
                if (finished.compareAndSet(false, true)) pending.finish()
            }
        }
    }

    private suspend fun handle(entryPoint: ReminderEntryPoint, action: String, id: Long) {
        val manager = entryPoint.reminderManager()
        val notifier = entryPoint.reminderNotifier()
        val announcer = entryPoint.reminderAnnouncer()
        when (action) {
            ACTION_FIRE -> manager.onFired(id)?.let { fired ->
                notifier.show(fired)
                announcer.announce(fired)
            }
            ACTION_DONE -> {
                manager.done(id)
                notifier.cancel(id)
            }
            ACTION_SNOOZE -> {
                manager.snooze(id)
                notifier.cancel(id)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val missed = manager.rescheduleAll()
                missed.forEach(notifier::show)
                missed.firstOrNull()?.let(announcer::announce)
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"

        const val ACTION_FIRE = "com.omnix.assistant.action.REMINDER_FIRE"
        const val ACTION_DONE = "com.omnix.assistant.action.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.omnix.assistant.action.REMINDER_SNOOZE"
        const val EXTRA_ID = "reminder_id"

        /** Система даёт ~10 с после goAsync(): укладываемся в 8. */
        private const val DISPATCH_TIMEOUT_MS = 8_000L

        private val handler = CoroutineExceptionHandler { _, t ->
            if (t is CancellationException) throw t
            Log.e(TAG, "reminder dispatch failed", t)
        }
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
    }
}
