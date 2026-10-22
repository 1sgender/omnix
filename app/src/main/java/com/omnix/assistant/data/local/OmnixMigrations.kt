package com.omnix.assistant.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Supported Room migrations from the first release-candidate schema onward.
 *
 * Only v5 has an authentic exported schema in this repository. Schemas v1-v4
 * cannot be reconstructed safely without Git history/released artifacts, so
 * guessed migrations are forbidden. [LegacyDatabasePolicy] implements the
 * declared breaking-change strategy: archive an unsupported legacy DB inside
 * app-private storage, record the event, then create a clean v5 database.
 *
 * For every future schema change:
 * 1. update entities and increment [OmnixDatabase] version;
 * 2. add `MIGRATION_X_Y` below;
 * 3. export and commit both schema JSON files;
 * 4. add a MigrationTestHelper step and full-chain test;
 * 5. never add a broad destructive fallback.
 */
object OmnixMigrations {
    /** Earliest schema from which in-place upgrades are supported. */
    const val MIN_SUPPORTED_VERSION = 5

    /** The schema [OmnixDatabase] declares; bump it together with a migration. */
    const val CURRENT_VERSION = 6

    /**
     * v6: the chat is split into conversations (owner, 2026-10-21: every
     * launch starts a new chat, past ones stay in history). Existing
     * messages keep every row and land in conversation 0, shown as one
     * earlier conversation.
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `messages` ADD COLUMN `conversation_id` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_conversation_id` " +
                    "ON `messages` (`conversation_id`)"
            )
        }
    }

    /** Supported migrations in ascending order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_5_6)
}
