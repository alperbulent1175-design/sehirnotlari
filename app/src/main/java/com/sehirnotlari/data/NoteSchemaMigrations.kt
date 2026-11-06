package com.sehirnotlari.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 keeps every row, id and tombstone:
 * - `updatedAt` (a write ticket since P2) moves verbatim to `writeTicket`.
 * - `modifiedAt` (real change time) and `isAI` were never recorded in v1, so they stay NULL.
 * The table is rebuilt because `ALTER TABLE … RENAME COLUMN` needs SQLite 3.25 (API 30), above minSdk 24.
 */
object NoteSchemaMigrations {
    const val NOTES_V2_CREATE_SQL =
        "CREATE TABLE IF NOT EXISTS `notes_v2` (`id` TEXT NOT NULL, `city` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`content` TEXT NOT NULL, `category` TEXT NOT NULL, `star` INTEGER NOT NULL, `color` TEXT NOT NULL, " +
            "`favorite` INTEGER NOT NULL, `isAI` INTEGER, `dateLabel` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`writeTicket` INTEGER NOT NULL, `modifiedAt` INTEGER, `deletedAt` INTEGER, `syncState` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`))"

    val MIGRATION_1_2_SQL: List<String> = listOf(
        NOTES_V2_CREATE_SQL,
        "INSERT INTO `notes_v2` (`id`, `city`, `title`, `content`, `category`, `star`, `color`, `favorite`, `isAI`, " +
            "`dateLabel`, `createdAt`, `writeTicket`, `modifiedAt`, `deletedAt`, `syncState`) " +
            "SELECT `id`, `city`, `title`, `content`, `category`, `star`, `color`, `favorite`, NULL, " +
            "`dateLabel`, `createdAt`, `updatedAt`, NULL, `deletedAt`, `syncState` FROM `notes`",
        "DROP TABLE `notes`",
        "ALTER TABLE `notes_v2` RENAME TO `notes`",
        "CREATE INDEX IF NOT EXISTS `index_notes_city` ON `notes` (`city`)",
        "CREATE INDEX IF NOT EXISTS `index_notes_writeTicket` ON `notes` (`writeTicket`)",
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_SQL.forEach(db::execSQL)
        }
    }

    /** v2 → v3 only adds the write journal; `notes` is not touched. */
    val MIGRATION_2_3_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `note_write_journal` (`ticket` INTEGER NOT NULL, `noteKey` TEXT NOT NULL, " +
            "`city` TEXT NOT NULL, `op` TEXT NOT NULL, `fingerprint` TEXT, `state` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `resolvedAt` INTEGER, `previousJson` TEXT, PRIMARY KEY(`ticket`))",
        "CREATE INDEX IF NOT EXISTS `index_note_write_journal_noteKey` ON `note_write_journal` (`noteKey`)",
    )

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_2_3_SQL.forEach(db::execSQL)
        }
    }

    /** v3 → v4 only adds the user-resolution backup table; `notes` and the journal are not touched. */
    val MIGRATION_3_4_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `note_resolution_backup` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`noteKey` TEXT NOT NULL, `city` TEXT NOT NULL, `action` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
            "`beforeJson` TEXT, `afterTicket` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `undoneAt` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_note_resolution_backup_noteKey` ON `note_resolution_backup` (`noteKey`)",
    )

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_3_4_SQL.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
