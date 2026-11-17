package com.omnix.assistant.agent.reminder

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Системный будильник напоминаний. Отдельный интерфейс, чтобы логику проверять без Android. */
interface ReminderAlarms {
    /** Ставит будильник на [triggerAt]. true — точный, false — система может сдвинуть время. */
    fun schedule(id: Long, triggerAt: Long): Boolean

    fun cancel(id: Long)
}

/** Что чат умеет делать с карточкой напоминания в ленте. */
interface ReminderActions {
    /** Все напоминания: карточка показывает их живое состояние. */
    val all: Flow<List<ReminderEntity>>

    suspend fun cancel(id: Long)

    suspend fun reschedule(id: Long, triggerAt: Long)

    companion object {
        /** Без напоминаний (юнит-тесты, сборка без DI). */
        val NONE: ReminderActions = object : ReminderActions {
            override val all: Flow<List<ReminderEntity>> = emptyFlow()
            override suspend fun cancel(id: Long) = Unit
            override suspend fun reschedule(id: Long, triggerAt: Long) = Unit
        }
    }
}

/**
 * Единственное место, где меняются напоминания: Room — источник правды,
 * будильник всегда перестраивается из него (после перезагрузки, смены времени
 * и обновления приложения тоже).
 */
@Singleton
class ReminderManager @Inject constructor(
    private val dao: ReminderDao,
    private val alarms: ReminderAlarms
) : ReminderActions {

    /** Созданное напоминание и точность будильника. */
    data class Created(val reminder: ReminderEntity, val exact: Boolean)

    override val all: Flow<List<ReminderEntity>> get() = dao.allStream()

    /** Ждущие и сработавшие напоминания для экрана-списка. */
    val active: Flow<List<ReminderEntity>> get() = dao.activeStream()

    suspend fun get(id: Long): ReminderEntity? = dao.get(id)

    suspend fun create(text: String, triggerAt: Long): Created {
        val id = dao.insert(ReminderEntity(text = text, triggerAt = triggerAt))
        val exact = alarms.schedule(id, triggerAt)
        return Created(ReminderEntity(id = id, text = text, triggerAt = triggerAt), exact)
    }

    /** «Отменить» в карточке: запись остаётся со статусом, карточка это покажет. */
    override suspend fun cancel(id: Long) {
        alarms.cancel(id)
        val current = dao.get(id) ?: return
        dao.update(current.copy(status = ReminderStatus.CANCELLED))
    }

    /** Удаление из списка: запись исчезает совсем. */
    suspend fun delete(id: Long) {
        alarms.cancel(id)
        dao.delete(id)
    }

    /** «Изменить»: новое время, напоминание снова ждёт. */
    override suspend fun reschedule(id: Long, triggerAt: Long) {
        val current = dao.get(id) ?: return
        dao.update(current.copy(triggerAt = triggerAt, status = ReminderStatus.PENDING))
        alarms.schedule(id, triggerAt)
    }

    /** «Отложить»: сработавшее напоминание повторится через [minutes]. */
    suspend fun snooze(id: Long, minutes: Int = SNOOZE_MINUTES, now: Long = System.currentTimeMillis()): ReminderEntity? {
        val current = dao.get(id) ?: return null
        val snoozed = current.copy(
            triggerAt = now + minutes * MINUTE_MS,
            status = ReminderStatus.PENDING
        )
        dao.update(snoozed)
        alarms.schedule(id, snoozed.triggerAt)
        return snoozed
    }

    /** «Готово»: напоминание выполнено, будильника больше нет. */
    suspend fun done(id: Long) {
        alarms.cancel(id)
        val current = dao.get(id) ?: return
        dao.update(current.copy(status = ReminderStatus.DONE))
    }

    /**
     * Сработал будильник. Возвращает напоминание, о котором надо объявить,
     * или null: оно отменено, выполнено, удалено или уже объявлено
     * (повторная доставка того же будильника).
     */
    suspend fun onFired(id: Long): ReminderEntity? {
        val current = dao.get(id) ?: return null
        if (current.status != ReminderStatus.PENDING) return null
        val fired = current.copy(status = ReminderStatus.FIRED)
        dao.update(fired)
        return fired
    }

    /**
     * Перезагрузка, смена времени или обновление: будильники пропали или
     * сдвинулись. Будущие напоминания ставятся заново; те, чьё время уже
     * прошло, помечаются сработавшими и возвращаются: о них надо объявить сейчас.
     */
    suspend fun rescheduleAll(now: Long = System.currentTimeMillis()): List<ReminderEntity> {
        val missed = mutableListOf<ReminderEntity>()
        for (reminder in dao.active()) {
            if (reminder.status != ReminderStatus.PENDING) continue
            if (reminder.triggerAt <= now) {
                val fired = reminder.copy(status = ReminderStatus.FIRED)
                dao.update(fired)
                missed += fired
            } else {
                alarms.schedule(reminder.id, reminder.triggerAt)
            }
        }
        return missed
    }

    companion object {
        const val SNOOZE_MINUTES = 10
        private const val MINUTE_MS = 60_000L
    }
}
