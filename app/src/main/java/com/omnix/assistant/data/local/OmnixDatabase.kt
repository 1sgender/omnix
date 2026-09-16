package com.omnix.assistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.omnix.assistant.agent.automation.dao.AutomationDao
import com.omnix.assistant.agent.automation.entity.AutomationEntity
import com.omnix.assistant.agent.memory.dao.*
import com.omnix.assistant.agent.memory.entity.*
import com.omnix.assistant.data.local.dao.MessageDao
import com.omnix.assistant.data.local.entity.MessageEntity

@Database(
    entities = [
        MessageEntity::class,
        MemoryEntity::class,
        FactEntity::class,
        PreferenceEntity::class,
        ProcedureEntity::class,
        AutomationEntity::class
    ],
    version = 5,
    // Пункт аудита #7: схема экспортируется в app/schemas/ — обязательное
    // условие для миграций и MigrationTestHelper. ЛЮБОЕ изменение схемы
    // обязано сопровождаться миграцией в OmnixMigrations (см. документацию там).
    exportSchema = true
)
abstract class OmnixDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun factDao(): FactDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun procedureDao(): ProcedureDao
    abstract fun automationDao(): AutomationDao
}
