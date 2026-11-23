package com.nodeseek.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ReadHistoryEntity::class,
        BookmarkEntity::class,
        DraftEntity::class,
        CategoryEntity::class,
        KeywordWatchRuleEntity::class,
        TrackedTopicEntity::class,
        LocalAlertEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NodePulseDatabase : RoomDatabase() {
    abstract fun readHistoryDao(): ReadHistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun draftDao(): DraftDao
    abstract fun categoryDao(): CategoryDao
    abstract fun radarDao(): RadarDao

    companion object {
        const val DATABASE_NAME = "nodepulse.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `radar_keyword_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `keyword` TEXT NOT NULL,
                        `excludeWords` TEXT NOT NULL,
                        `categorySlug` TEXT NOT NULL,
                        `matchMode` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_radar_keyword_rules_enabled_created` ON `radar_keyword_rules` (`isEnabled`, `createdAtEpochMillis`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `radar_tracked_topics` (
                        `topicId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `categorySlug` TEXT NOT NULL,
                        `categoryName` TEXT NOT NULL,
                        `authorName` TEXT NOT NULL,
                        `lastKnownFloor` INTEGER NOT NULL,
                        `lastKnownTotalPages` INTEGER NOT NULL,
                        `unreadNewReplies` INTEGER NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `lastCheckedEpochMillis` INTEGER NOT NULL,
                        `lastUpdatedEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`topicId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_radar_tracked_topics_active_updated` ON `radar_tracked_topics` (`isActive`, `lastUpdatedEpochMillis`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `radar_local_alerts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dedupKey` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `topicId` INTEGER NOT NULL,
                        `topicTitle` TEXT NOT NULL,
                        `targetFloor` INTEGER NOT NULL,
                        `matchedKeyword` TEXT NOT NULL,
                        `actorName` TEXT NOT NULL,
                        `summarySnippet` TEXT NOT NULL,
                        `categoryName` TEXT NOT NULL,
                        `isRead` INTEGER NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `idx_radar_local_alerts_dedup` ON `radar_local_alerts` (`dedupKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_radar_local_alerts_read_created` ON `radar_local_alerts` (`isRead`, `createdAtEpochMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_radar_local_alerts_topic` ON `radar_local_alerts` (`topicId`)",
                )
            }
        }

        @Volatile
        private var instance: NodePulseDatabase? = null

        fun getInstance(context: Context): NodePulseDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }

        fun buildDatabase(context: Context): NodePulseDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NodePulseDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2)
                .build()

        fun buildInMemory(context: Context): NodePulseDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                NodePulseDatabase::class.java,
            ).build()
    }
}
