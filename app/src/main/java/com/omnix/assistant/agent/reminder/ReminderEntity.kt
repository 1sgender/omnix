package com.omnix.assistant.agent.reminder

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Состояния напоминания. Хранятся строкой в Room, чтобы схема не зависела от enum. */
object ReminderStatus {
    /** Ждёт своего времени, будильник поставлен. */
    const val PENDING = "PENDING"

    /** Сработало и ждёт «Готово» или «Отложить». */
    const val FIRED = "FIRED"

    const val DONE = "DONE"
    const val CANCELLED = "CANCELLED"
}

/** Напоминание пользователя («напомни в 18:00 позвонить маме»). */
@Entity(
    tableName = "reminders",
    indices = [Index(value = ["status", "trigger_at"])]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "text")
    val text: String,

    /** Момент срабатывания, мс с начала эпохи (UTC). */
    @ColumnInfo(name = "trigger_at")
    val triggerAt: Long,

    @ColumnInfo(name = "status")
    val status: String = ReminderStatus.PENDING
)

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    /** Ждущие и сработавшие, ближайшие первыми: экран «Напоминания». */
    @Query("SELECT * FROM reminders WHERE status IN ('PENDING', 'FIRED') ORDER BY trigger_at ASC")
    fun activeStream(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE status IN ('PENDING', 'FIRED') ORDER BY trigger_at ASC")
    suspend fun active(): List<ReminderEntity>

    /** Все, включая выполненные и отменённые: карточки в ленте чата. */
    @Query("SELECT * FROM reminders")
    fun allStream(): Flow<List<ReminderEntity>>

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
}
