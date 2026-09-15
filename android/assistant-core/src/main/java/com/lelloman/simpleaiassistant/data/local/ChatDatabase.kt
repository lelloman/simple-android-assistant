package com.lelloman.simpleaiassistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ChatMessageEntity::class, EngineArchiveEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun engineArchiveDao(): EngineArchiveDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `assistant_archive` (`id` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }
}
