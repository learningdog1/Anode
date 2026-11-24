package com.nodeseek.app.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodePulseDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NodePulseDatabase::class.java,
    )

    private val createdDbNames = mutableListOf<String>()

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        createdDbNames.forEach { dbName ->
            context.deleteDatabase(dbName)
        }
        createdDbNames.clear()
    }

    private fun trackDb(name: String): String {
        createdDbNames += name
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(name)
        return name
    }

    @Test
    fun migrate1To2_preservesRepresentativeDataAndCreatesRadarTablesAndIndices() = runTest {
        val dbName = trackDb("migration-1-to-2-data.db")

        // 1. Create v1 schema from 1.json and insert representative records into all 4 v1 tables
        helper.createDatabase(dbName, 1).use { db ->
            assertFalse(queryTables(db).contains("radar_keyword_rules"))
            assertFalse(queryTables(db).contains("radar_tracked_topics"))
            assertFalse(queryTables(db).contains("radar_local_alerts"))

            // read_history: guest + signed-in account rows
            db.insert(
                "read_history",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("accountUid", 0L)
                    put("topicId", 1001L)
                    put("title", "NodeSeek 东京线路测评")
                    put("categorySlug", "review")
                    put("categoryName", "测评")
                    put("authorName", "alice")
                    put("authorAvatarUrl", "https://www.nodeseek.com/avatar/1.png")
                    put("lastReadPage", 2)
                    put("lastReadFloor", 18)
                    put("visitedAtEpochMillis", 1_700_000_100_000L)
                },
            )
            db.insert(
                "read_history",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("accountUid", 42L)
                    put("topicId", 1002L)
                    put("title", "二手出机帖")
                    put("categorySlug", "trade")
                    put("categoryName", "交易")
                    put("authorName", "bob")
                    put("authorAvatarUrl", "https://www.nodeseek.com/avatar/2.png")
                    put("lastReadPage", 1)
                    put("lastReadFloor", 5)
                    put("visitedAtEpochMillis", 1_700_000_200_000L)
                },
            )

            // bookmarks: one fully populated with nullable fields, one with nulls
            db.insert(
                "bookmarks",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("accountUid", 42L)
                    put("topicId", 2001L)
                    put("title", "高配独服折价转手")
                    put("categorySlug", "trade")
                    put("categoryName", "交易")
                    put("authorId", 900L)
                    put("authorName", "sellerA")
                    put("authorAvatarUrl", "https://www.nodeseek.com/avatar/900.png")
                    put("authorLevel", 5)
                    put("viewCount", 888)
                    put("replyCount", 36)
                    put("lastActivityIso", "2026-11-20T12:00:00Z")
                    put("requiredViewLevel", 2)
                    put("isLocked", 1)
                    put("offlineDetailJson", """{"topicId":2001}""")
                    put("isSyncedWithServer", 1)
                    put("savedAtEpochMillis", 1_700_000_300_000L)
                },
            )
            db.insert(
                "bookmarks",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("accountUid", 0L)
                    put("topicId", 2002L)
                    put("title", "游客本地收藏")
                    put("categorySlug", "daily")
                    put("categoryName", "日常")
                    putNull("authorId")
                    put("authorName", "guestAuthor")
                    put("authorAvatarUrl", "")
                    putNull("authorLevel")
                    put("viewCount", 12)
                    put("replyCount", 0)
                    put("lastActivityIso", "2026-11-21T08:30:00Z")
                    putNull("requiredViewLevel")
                    put("isLocked", 0)
                    putNull("offlineDetailJson")
                    put("isSyncedWithServer", 0)
                    put("savedAtEpochMillis", 1_700_000_400_000L)
                },
            )

            // editor_drafts
            db.insert(
                "editor_drafts",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("accountUid", 42L)
                    put("draftKey", "new_topic")
                    put("title", "草稿标题")
                    put("markdownContent", "## 正文内容\n- 项 1")
                    put("categorySlug", "tech")
                    put("updatedAtMillis", 1_700_000_500_000L)
                },
            )

            // categories
            db.insert(
                "categories",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("accountUid", 42L)
                    put("slug", "trade")
                    put("name", "交易")
                    put("description", "闲置出收")
                    put("sortOrder", 3)
                    put("isCustomizable", 1)
                    put("visibleOnHome", 0)
                    put("isAdminOnly", 0)
                    put("topicCoinCost", 5)
                    put("supportsRss", 1)
                    put("syncedAtEpochMillis", 1_700_000_600_000L)
                },
            )
        }

        // 2. Run MIGRATION_1_2 and validate schema against exported 2.json
        helper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            NodePulseDatabase.MIGRATION_1_2,
        ).use { migratedDb ->
            val tables = queryTables(migratedDb)
            assertTrue(tables.containsAll(
                setOf(
                    "read_history",
                    "bookmarks",
                    "editor_drafts",
                    "categories",
                    "radar_keyword_rules",
                    "radar_tracked_topics",
                    "radar_local_alerts",
                ),
            ))

            val indices = queryIndices(migratedDb)
            assertTrue(indices.containsAll(
                setOf(
                    "index_read_history_accountUid_visitedAtEpochMillis",
                    "index_bookmarks_accountUid_savedAtEpochMillis",
                    "index_editor_drafts_accountUid_updatedAtMillis",
                    "index_categories_accountUid_sortOrder",
                    "idx_radar_keyword_rules_enabled_created",
                    "idx_radar_tracked_topics_active_updated",
                    "idx_radar_local_alerts_dedup",
                    "idx_radar_local_alerts_read_created",
                    "idx_radar_local_alerts_topic",
                ),
            ))

            assertTrue(
                "idx_radar_local_alerts_dedup must be UNIQUE",
                isIndexUnique(migratedDb, "radar_local_alerts", "idx_radar_local_alerts_dedup"),
            )
            assertFalse(
                "idx_radar_local_alerts_topic should be non-unique",
                isIndexUnique(migratedDb, "radar_local_alerts", "idx_radar_local_alerts_topic"),
            )
        }

        // 3. Re-open via full Room NodePulseDatabase and assert data integrity + new RadarDao usability
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val roomDb = Room.databaseBuilder(context, NodePulseDatabase::class.java, dbName)
            .addMigrations(NodePulseDatabase.MIGRATION_1_2)
            .build()
        try {
            // Verify read_history
            val guestHistory = roomDb.readHistoryDao().getByTopicId(0L, 1001L)
            assertNotNull(guestHistory)
            assertEquals("NodeSeek 东京线路测评", guestHistory!!.title)
            assertEquals(2, guestHistory.lastReadPage)
            assertEquals(18, guestHistory.lastReadFloor)
            assertEquals(1_700_000_100_000L, guestHistory.visitedAtEpochMillis)

            val userHistory = roomDb.readHistoryDao().getByTopicId(42L, 1002L)
            assertNotNull(userHistory)
            assertEquals("trade", userHistory!!.categorySlug)
            assertEquals("bob", userHistory.authorName)

            // Verify bookmarks (both populated and nullable columns)
            val richBookmark = roomDb.bookmarkDao().getBookmark(42L, 2001L)
            assertNotNull(richBookmark)
            assertEquals(900L, richBookmark!!.authorId)
            assertEquals(5, richBookmark.authorLevel)
            assertEquals(2, richBookmark.requiredViewLevel)
            assertTrue(richBookmark.isLocked)
            assertTrue(richBookmark.isSyncedWithServer)
            assertEquals("""{"topicId":2001}""", richBookmark.offlineDetailJson)

            val minimalBookmark = roomDb.bookmarkDao().getBookmark(0L, 2002L)
            assertNotNull(minimalBookmark)
            assertNull(minimalBookmark!!.authorId)
            assertNull(minimalBookmark.authorLevel)
            assertNull(minimalBookmark.requiredViewLevel)
            assertNull(minimalBookmark.offlineDetailJson)
            assertFalse(minimalBookmark.isLocked)
            assertFalse(minimalBookmark.isSyncedWithServer)

            // Verify editor_drafts
            val draft = roomDb.draftDao().getDraft(42L, "new_topic")
            assertNotNull(draft)
            assertEquals("草稿标题", draft!!.title)
            assertEquals("## 正文内容\n- 项 1", draft.markdownContent)
            assertEquals("tech", draft.categorySlug)
            assertEquals(1_700_000_500_000L, draft.updatedAtMillis)

            // Verify categories
            val categories = roomDb.categoryDao().getCategories(42L)
            assertEquals(1, categories.size)
            val trade = categories.single()
            assertEquals("trade", trade.slug)
            assertTrue(trade.isCustomizable)
            assertFalse(trade.visibleOnHome)
            assertFalse(trade.isAdminOnly)
            assertEquals(5, trade.topicCoinCost)
            assertTrue(trade.supportsRss)
            assertEquals(1_700_000_600_000L, trade.syncedAtEpochMillis)

            // Verify newly migrated radar tables accept writes & enforce unique dedup index
            val radarDao = roomDb.radarDao()
            val ruleId = radarDao.insertKeywordRule(
                KeywordWatchRuleEntity(
                    keyword = "搬瓦工",
                    excludeWords = "收",
                    categorySlug = "trade",
                    matchMode = "CONTAINS",
                    isEnabled = true,
                    createdAtEpochMillis = 1_700_001_000_000L,
                ),
            )
            assertTrue(ruleId > 0L)

            radarDao.upsertTrackedTopic(
                TrackedTopicEntity(
                    topicId = 2001L,
                    title = "高配独服折价转手",
                    categorySlug = "trade",
                    categoryName = "交易",
                    authorName = "sellerA",
                    lastKnownFloor = 36,
                    lastKnownTotalPages = 4,
                    unreadNewReplies = 2,
                    isActive = true,
                    createdAtEpochMillis = 1_700_001_000_000L,
                    lastCheckedEpochMillis = 1_700_001_010_000L,
                    lastUpdatedEpochMillis = 1_700_001_010_000L,
                ),
            )
            assertEquals(2, radarDao.getTrackedTopic(2001L)?.unreadNewReplies)

            val firstAlertId = radarDao.insertAlertIfAbsent(
                LocalAlertEntity(
                    dedupKey = "kw:2001:37",
                    kind = "KEYWORD_HIT",
                    topicId = 2001L,
                    topicTitle = "高配独服折价转手",
                    targetFloor = 37,
                    matchedKeyword = "搬瓦工",
                    actorName = "buyerB",
                    summarySnippet = "降价出",
                    categoryName = "交易",
                    isRead = false,
                    createdAtEpochMillis = 1_700_001_020_000L,
                ),
            )
            val duplicateAlertId = radarDao.insertAlertIfAbsent(
                LocalAlertEntity(
                    dedupKey = "kw:2001:37",
                    kind = "KEYWORD_HIT",
                    topicId = 2001L,
                    topicTitle = "高配独服折价转手",
                    targetFloor = 37,
                    matchedKeyword = "搬瓦工",
                    actorName = "buyerB",
                    summarySnippet = "重复告警",
                    categoryName = "交易",
                    isRead = false,
                    createdAtEpochMillis = 1_700_001_030_000L,
                ),
            )
            assertTrue(firstAlertId > 0L)
            assertEquals(-1L, duplicateAlertId)
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun migrate1To2_emptyV1DatabaseSucceedsAndMatchesV2Schema() {
        val dbName = trackDb("migration-1-to-2-empty.db")
        helper.createDatabase(dbName, 1).close()

        helper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            NodePulseDatabase.MIGRATION_1_2,
        ).use { db ->
            assertTrue(queryTables(db).contains("radar_local_alerts"))
            assertTrue(isIndexUnique(db, "radar_local_alerts", "idx_radar_local_alerts_dedup"))
        }
    }

    @Test
    fun freshV2Database_exportedSchemaMatchesRuntimeImplementation() = runTest {
        val dbName = trackDb("fresh-v2-schema.db")
        helper.createDatabase(dbName, 2).close()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, NodePulseDatabase::class.java, dbName)
            .addMigrations(NodePulseDatabase.MIGRATION_1_2)
            .build()
        try {
            // Opening and executing a query triggers Room's schema identity & structure validation
            assertTrue(db.radarDao().getKeywordRules().isEmpty())
            assertTrue(db.bookmarkDao().getAllBookmarks().isEmpty())
            assertTrue(db.draftDao().getAllDrafts().isEmpty())
            assertTrue(db.categoryDao().getCategories(0L).isEmpty())
            assertNull(db.readHistoryDao().getByTopicId(0L, 1L))
        } finally {
            db.close()
        }
    }

    private fun queryTables(db: SupportSQLiteDatabase): Set<String> {
        val result = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.getString(0)
            }
        }
        return result
    }

    private fun queryIndices(db: SupportSQLiteDatabase): Set<String> {
        val result = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_autoindex_%'").use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.getString(0)
            }
        }
        return result
    }

    private fun isIndexUnique(db: SupportSQLiteDatabase, table: String, indexName: String): Boolean {
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow("name")
            val uniqueCol = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == indexName) {
                    return cursor.getInt(uniqueCol) == 1
                }
            }
        }
        return false
    }
}
