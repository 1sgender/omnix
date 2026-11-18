package com.omnix.assistant.agent.reminder

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeReminderDao : ReminderDao {
    val rows = mutableListOf<ReminderEntity>()
    private var nextId = 1L

    override suspend fun insert(reminder: ReminderEntity): Long {
        val stored = reminder.copy(id = nextId++)
        rows += stored
        return stored.id
    }

    override suspend fun update(reminder: ReminderEntity) {
        val index = rows.indexOfFirst { it.id == reminder.id }
        if (index >= 0) rows[index] = reminder
    }

    override suspend fun get(id: Long): ReminderEntity? = rows.firstOrNull { it.id == id }

    override fun activeStream(): Flow<List<ReminderEntity>> = flowOf(activeNow())

    override suspend fun active(): List<ReminderEntity> = activeNow()

    override fun allStream(): Flow<List<ReminderEntity>> = flowOf(rows.toList())

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    private fun activeNow() = rows
        .filter { it.status == ReminderStatus.PENDING || it.status == ReminderStatus.FIRED }
        .sortedBy { it.triggerAt }
}

private class FakeReminderAlarms(var exact: Boolean = true) : ReminderAlarms {
    val scheduled = mutableMapOf<Long, Long>()
    val cancelled = mutableListOf<Long>()

    override fun schedule(id: Long, triggerAt: Long): Boolean {
        scheduled[id] = triggerAt
        return exact
    }

    override fun cancel(id: Long) {
        scheduled.remove(id)
        cancelled += id
    }
}

class ReminderManagerTest {

    private val dao = FakeReminderDao()
    private val alarms = FakeReminderAlarms()
    private val manager = ReminderManager(dao, alarms)

    private val now = 1_000_000_000_000L

    @Test
    fun `create stores a pending reminder and sets the alarm`() = runTest {
        val created = manager.create("Позвонить маме", now + 60_000)

        assertTrue(created.exact)
        assertEquals(ReminderStatus.PENDING, dao.rows.single().status)
        assertEquals(now + 60_000, alarms.scheduled[created.reminder.id])
    }

    @Test
    fun `create reports an inexact alarm`() = runTest {
        alarms.exact = false
        assertFalse(manager.create("Позвонить маме", now + 60_000).exact)
    }

    @Test
    fun `cancel removes the alarm and keeps the row as cancelled`() = runTest {
        val id = manager.create("a", now + 60_000).reminder.id
        manager.cancel(id)

        assertNull(alarms.scheduled[id])
        assertEquals(ReminderStatus.CANCELLED, manager.get(id)?.status)
    }

    @Test
    fun `delete removes the alarm and the row`() = runTest {
        val id = manager.create("a", now + 60_000).reminder.id
        manager.delete(id)

        assertNull(alarms.scheduled[id])
        assertNull(manager.get(id))
    }

    @Test
    fun `reschedule moves the alarm and makes it pending again`() = runTest {
        val id = manager.create("a", now + 60_000).reminder.id
        manager.cancel(id)
        manager.reschedule(id, now + 7_200_000)

        assertEquals(now + 7_200_000, alarms.scheduled[id])
        assertEquals(ReminderStatus.PENDING, manager.get(id)?.status)
        assertEquals(now + 7_200_000, manager.get(id)?.triggerAt)
    }

    @Test
    fun `onFired announces once`() = runTest {
        val id = manager.create("a", now).reminder.id

        val first = manager.onFired(id)
        assertNotNull(first)
        assertEquals(ReminderStatus.FIRED, first?.status)
        assertNull("a repeated delivery must not announce again", manager.onFired(id))
    }

    @Test
    fun `onFired ignores cancelled done and missing reminders`() = runTest {
        val cancelled = manager.create("a", now).reminder.id
        manager.cancel(cancelled)
        val done = manager.create("b", now).reminder.id
        manager.done(done)

        assertNull(manager.onFired(cancelled))
        assertNull(manager.onFired(done))
        assertNull(manager.onFired(999))
    }

    @Test
    fun `snooze repeats in ten minutes`() = runTest {
        val id = manager.create("a", now).reminder.id
        manager.onFired(id)

        val snoozed = manager.snooze(id, now = now + 5_000)

        assertEquals(now + 5_000 + 10 * 60_000L, snoozed?.triggerAt)
        assertEquals(ReminderStatus.PENDING, snoozed?.status)
        assertEquals(now + 5_000 + 10 * 60_000L, alarms.scheduled[id])
        // Отложенное напоминание снова может сработать.
        assertNotNull(manager.onFired(id))
    }

    @Test
    fun `snooze of a missing reminder does nothing`() = runTest {
        assertNull(manager.snooze(42, now = now))
        assertTrue(alarms.scheduled.isEmpty())
    }

    @Test
    fun `done drops the alarm`() = runTest {
        val id = manager.create("a", now).reminder.id
        manager.onFired(id)
        manager.done(id)

        assertEquals(ReminderStatus.DONE, manager.get(id)?.status)
        assertTrue(id in alarms.cancelled)
    }

    @Test
    fun `reschedule all sets future alarms again and returns the missed ones`() = runTest {
        val future = manager.create("future", now + 3_600_000).reminder.id
        val past = manager.create("past", now - 60_000).reminder.id
        val finished = manager.create("done", now + 7_200_000).reminder.id
        manager.done(finished)
        alarms.scheduled.clear()

        val missed = manager.rescheduleAll(now)

        assertEquals(listOf(past), missed.map { it.id })
        assertEquals(ReminderStatus.FIRED, manager.get(past)?.status)
        assertEquals(now + 3_600_000, alarms.scheduled[future])
        assertNull("a finished reminder must not get an alarm", alarms.scheduled[finished])
        assertNull("a missed reminder is announced, not scheduled", alarms.scheduled[past])
    }

    @Test
    fun `reschedule all announces a missed reminder only once`() = runTest {
        manager.create("past", now - 60_000)

        assertEquals(1, manager.rescheduleAll(now).size)
        assertTrue(manager.rescheduleAll(now).isEmpty())
    }
}
