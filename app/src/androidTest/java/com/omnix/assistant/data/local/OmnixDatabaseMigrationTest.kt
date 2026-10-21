package com.omnix.assistant.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Миграции Room (пункт аудита #7 — HIGH).
 *
 * Валидирует, что экспортированная схема v5 соответствует сущностям и что
 * БД открывается/мигрирует без потери данных. При изменении схемы:
 * 1. добавь MIGRATION_X_Y в OmnixMigrations.ALL;
 * 2. добавь тест runMigrationsAndValidate(TEST_DB, NEW_VERSION, true, MIGRATION_X_Y).
 *
 * Запуск: ./gradlew connectedDebugAndroidTest (требует эмулятора/устройства).
 */
@RunWith(AndroidJUnit4::class)
class OmnixDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OmnixDatabase::class.java
    )

    @Test
    fun v5SchemaCreatesAllTablesAndValidates() {
        // Создаём БД из экспортированной схемы 5.json и валидируем её.
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *OmnixMigrations.ALL)
    }

    @Test
    fun messagesTableRoundTripsOnV5Schema() {
        val db = helper.createDatabase(TEST_DB, 5)
        db.execSQL(
            "INSERT INTO messages (role, text, timestamp) VALUES ('user', 'тестовое сообщение', 1)"
        )
        db.query("SELECT COUNT(*) FROM messages").use { cursor ->
            cursor.moveToFirst()
            assertTrue("Сообщение должно сохраниться", cursor.getInt(0) >= 1)
        }
        db.close()
    }

    @Test
    fun automationsTableHasTriggerParamColumnOnV5() {
        val db = helper.createDatabase(TEST_DB, 5)
        db.query("SELECT trigger_param FROM automations LIMIT 0").use { cursor ->
            assertTrue("Колонка trigger_param должна существовать", cursor.columnCount >= 1)
        }
        db.close()
    }

    @Test
    fun migration5To6KeepsMessagesInTheEarlierConversation() {
        val v5 = helper.createDatabase(TEST_DB, 5)
        v5.execSQL(
            "INSERT INTO messages (role, text, timestamp) VALUES ('user', 'старое сообщение', 1)"
        )
        v5.close()

        val v6 = helper.runMigrationsAndValidate(TEST_DB, 6, true, OmnixMigrations.MIGRATION_5_6)
        v6.query("SELECT text, conversation_id FROM messages").use { cursor ->
            assertTrue("Сообщение должно пережить миграцию", cursor.moveToFirst())
            assertEquals("старое сообщение", cursor.getString(0))
            assertEquals(0L, cursor.getLong(1))
        }
        v6.close()
    }

    /** Полная цепочка через настоящую Room: схема после миграций совпадает с кодом. */
    @Test
    fun roomOpensV5DatabaseThroughAllMigrations() {
        helper.createDatabase(TEST_DB, 5).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, OmnixDatabase::class.java, TEST_DB)
            .addMigrations(*OmnixMigrations.ALL)
            .build()
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
