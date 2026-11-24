package com.nodeseek.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadarDaoTest {

    private lateinit var db: NodePulseDatabase
    private lateinit var dao: RadarDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = NodePulseDatabase.buildInMemory(context)
        dao = db.radarDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun markAlertsAndTrackedRepliesReadForTopic_marksTopicAlertsReadAndResetsUnreadNewReplies() = runTest {
        // P1-60 regression: opening/marking a topic's alerts read must atomically reset unreadNewReplies
        dao.upsertTrackedTopics(
            listOf(
                sampleTrackedTopic(topicId = 100L, unreadNewReplies = 5, lastUpdated = 2_000L),
                sampleTrackedTopic(topicId = 200L, unreadNewReplies = 3, lastUpdated = 1_000L),
            ),
        )
        dao.insertAlertIfAbsent(sampleAlert(dedupKey = "a1", topicId = 100L, isRead = false, createdAt = 100L))
        dao.insertAlertIfAbsent(sampleAlert(dedupKey = "a2", topicId = 100L, isRead = false, createdAt = 200L))
        dao.insertAlertIfAbsent(sampleAlert(dedupKey = "b1", topicId = 200L, isRead = false, createdAt = 300L))

        assertEquals(3, dao.observeUnreadAlertCount().first())

        dao.markAlertsAndTrackedRepliesReadForTopic(100L)

        // Topic 100: alerts read + unreadNewReplies zeroed
        assertEquals(0, dao.getTrackedTopic(100L)!!.unreadNewReplies)
        // Topic 200: untouched
        assertEquals(3, dao.getTrackedTopic(200L)!!.unreadNewReplies)
        assertEquals(1, dao.observeUnreadAlertCount().first())

        val records = dao.observeAlertRecords().first().associateBy { it.dedupKey }
        assertTrue(records.getValue("a1").isRead)
        assertTrue(records.getValue("a2").isRead)
        assertFalse(records.getValue("b1").isRead)
    }

    @Test
    fun markAlertsAndTrackedRepliesReadForTopic_handlesUntrackedTopicAndZeroUnreadSafely() = runTest {
        dao.insertAlertIfAbsent(sampleAlert(dedupKey = "kw1", topicId = 999L, isRead = false, createdAt = 100L))
        dao.upsertTrackedTopic(sampleTrackedTopic(topicId = 500L, unreadNewReplies = 0, lastUpdated = 1_000L))

        // Untracked topic with alert
        dao.markAlertsAndTrackedRepliesReadForTopic(999L)
        assertEquals(0, dao.observeUnreadAlertCount().first())
        assertNull(dao.getTrackedTopic(999L))

        // Tracked topic already at 0 unread
        dao.markAlertsAndTrackedRepliesReadForTopic(500L)
        assertEquals(0, dao.getTrackedTopic(500L)!!.unreadNewReplies)
    }

    @Test
    fun recordMonitorTopicProgress_enforcesMonotonicFloorAndConditionalMetadataAndTimestamp() = runTest {
        dao.upsertTrackedTopic(
            sampleTrackedTopic(
                topicId = 300L,
                title = "原标题",
                categorySlug = "trade",
                categoryName = "交易",
                authorName = "origAuthor",
                lastKnownFloor = 20,
                lastKnownTotalPages = 2,
                unreadNewReplies = 1,
                lastChecked = 1_000L,
                lastUpdated = 1_000L,
            ),
        )

        // Smaller floor/pages & blank/null metadata & zero delta: must not regress or overwrite
        dao.recordMonitorTopicProgress(
            topicId = 300L,
            title = "",
            categorySlug = null,
            categoryName = "",
            authorName = null,
            newMaxFloor = 15,
            newTotalPages = 1,
            unreadDelta = 0,
            checkedAtEpochMillis = 2_000L,
        )
        val afterNoop = dao.getTrackedTopic(300L)!!
        assertEquals("原标题", afterNoop.title)
        assertEquals("trade", afterNoop.categorySlug)
        assertEquals("交易", afterNoop.categoryName)
        assertEquals("origAuthor", afterNoop.authorName)
        assertEquals(20, afterNoop.lastKnownFloor)
        assertEquals(2, afterNoop.lastKnownTotalPages)
        assertEquals(1, afterNoop.unreadNewReplies)
        assertEquals(2_000L, afterNoop.lastCheckedEpochMillis)
        assertEquals(1_000L, afterNoop.lastUpdatedEpochMillis)

        // Larger floor/pages & non-blank metadata & positive delta: advances everything
        dao.recordMonitorTopicProgress(
            topicId = 300L,
            title = "更新标题",
            categorySlug = "review",
            categoryName = "测评",
            authorName = "newAuthor",
            newMaxFloor = 28,
            newTotalPages = 3,
            unreadDelta = 4,
            checkedAtEpochMillis = 3_500L,
        )
        val afterProgress = dao.getTrackedTopic(300L)!!
        assertEquals("更新标题", afterProgress.title)
        assertEquals("review", afterProgress.categorySlug)
        assertEquals("测评", afterProgress.categoryName)
        assertEquals("newAuthor", afterProgress.authorName)
        assertEquals(28, afterProgress.lastKnownFloor)
        assertEquals(3, afterProgress.lastKnownTotalPages)
        assertEquals(5, afterProgress.unreadNewReplies)
        assertEquals(3_500L, afterProgress.lastCheckedEpochMillis)
        assertEquals(3_500L, afterProgress.lastUpdatedEpochMillis)
    }

    @Test
    fun markTopicFloorSeen_andMarkTopicCheckedWithoutNewReplies_workMonotonically() = runTest {
        dao.upsertTrackedTopic(
            sampleTrackedTopic(
                topicId = 400L,
                lastKnownFloor = 10,
                lastKnownTotalPages = 2,
                unreadNewReplies = 4,
                lastChecked = 1_000L,
                lastUpdated = 1_000L,
            ),
        )

        // Null totalPages preserves existing totalPages
        dao.markTopicCheckedWithoutNewReplies(topicId = 400L, totalPages = null, checkedAtEpochMillis = 1_500L)
        assertEquals(2, dao.getTrackedTopic(400L)!!.lastKnownTotalPages)
        assertEquals(1_500L, dao.getTrackedTopic(400L)!!.lastCheckedEpochMillis)
        assertEquals(4, dao.getTrackedTopic(400L)!!.unreadNewReplies)

        // Larger totalPages advances
        dao.markTopicCheckedWithoutNewReplies(topicId = 400L, totalPages = 3, checkedAtEpochMillis = 2_000L)
        assertEquals(3, dao.getTrackedTopic(400L)!!.lastKnownTotalPages)

        // markTopicFloorSeen with lower floor does not regress floor, resets unreadNewReplies
        dao.markTopicFloorSeen(topicId = 400L, maxSeenFloor = 8, totalPages = 1, checkedAtEpochMillis = 2_500L)
        val seen = dao.getTrackedTopic(400L)!!
        assertEquals(10, seen.lastKnownFloor)
        assertEquals(3, seen.lastKnownTotalPages)
        assertEquals(0, seen.unreadNewReplies)
        assertEquals(2_500L, seen.lastCheckedEpochMillis)
    }

    @Test
    fun alerts_dedupTrimAndReadLifecycleWorkOnRealSqlite() = runTest {
        val id1 = dao.insertAlertIfAbsent(sampleAlert(dedupKey = "k1", topicId = 1L, isRead = false, createdAt = 100L))
        val dup = dao.insertAlertIfAbsent(sampleAlert(dedupKey = "k1", topicId = 1L, isRead = false, createdAt = 150L))
        val id2 = dao.insertAlertIfAbsent(sampleAlert(dedupKey = "k2", topicId = 2L, isRead = false, createdAt = 200L))
        val id3 = dao.insertAlertIfAbsent(sampleAlert(dedupKey = "k3", topicId = 3L, isRead = false, createdAt = 300L))

        assertTrue(id1 > 0L)
        assertEquals(-1L, dup)
        assertTrue(id2 > id1)
        assertTrue(id3 > id2)

        // Trim to newest 2 -> k3, k2 remain; k1 dropped
        dao.trimAlertsToLimit(maxRows = 2)
        val remaining = dao.observeAlertRecords().first()
        assertEquals(listOf("k3", "k2"), remaining.map { it.dedupKey })

        // Mark k2 read by dedupKey, then clear read alerts -> only k3 remains
        dao.markAlertReadByDedupKey("k2")
        assertEquals(1, dao.observeUnreadAlertCount().first())
        dao.clearReadAlerts()
        assertEquals(listOf("k3"), dao.observeAlertRecords().first().map { it.dedupKey })

        // Mark all read & clear all
        dao.markAllAlertsRead()
        assertEquals(0, dao.observeUnreadAlertCount().first())
        dao.clearAllAlerts()
        assertTrue(dao.observeAlertRecords().first().isEmpty())
    }

    @Test
    fun keywordRulesAndTrackedTopics_filteringOrderingAndTransactionalReplace() = runTest {
        val r1 = dao.insertKeywordRule(
            KeywordWatchRuleEntity(
                keyword = "alpha",
                excludeWords = "",
                categorySlug = "",
                matchMode = "CONTAINS",
                isEnabled = true,
                createdAtEpochMillis = 100L,
            ),
        )
        val r2 = dao.insertKeywordRule(
            KeywordWatchRuleEntity(
                keyword = "beta",
                excludeWords = "noise",
                categorySlug = "trade",
                matchMode = "REGEX",
                isEnabled = true,
                createdAtEpochMillis = 200L,
            ),
        )
        assertEquals(listOf("beta", "alpha"), dao.observeKeywordRules().first().map { it.keyword })

        dao.setKeywordRuleEnabled(r2, enabled = false)
        assertFalse(dao.getKeywordRules().first { it.id == r2 }.isEnabled)

        dao.deleteKeywordRule(r1)
        assertEquals(listOf("beta"), dao.getKeywordRules().map { it.keyword })

        dao.replaceAllKeywordRules(
            listOf(
                KeywordWatchRuleEntity(
                    keyword = "gamma",
                    excludeWords = "",
                    categorySlug = "daily",
                    matchMode = "CONTAINS",
                    isEnabled = true,
                    createdAtEpochMillis = 300L,
                ),
            ),
        )
        assertEquals(listOf("gamma"), dao.getKeywordRules().map { it.keyword })

        // Active vs inactive tracked topics
        dao.replaceAllTrackedTopics(
            listOf(
                sampleTrackedTopic(topicId = 1L, isActive = true, lastUpdated = 100L),
                sampleTrackedTopic(topicId = 2L, isActive = false, lastUpdated = 300L),
                sampleTrackedTopic(topicId = 3L, isActive = true, lastUpdated = 200L),
            ),
        )
        assertEquals(listOf(3L, 1L), dao.observeTrackedTopics().first().map { it.topicId })
        assertEquals(listOf(3L, 1L), dao.getActiveTrackedTopics().map { it.topicId })
        assertEquals(listOf(2L, 3L, 1L), dao.getAllTrackedTopics().map { it.topicId })
        assertNull(dao.observeTrackedTopic(2L).first())
        assertNotNull(dao.getTrackedTopic(2L))

        dao.replaceAllTrackedTopics(emptyList())
        assertTrue(dao.getAllTrackedTopics().isEmpty())
    }

    private fun sampleTrackedTopic(
        topicId: Long,
        title: String = "Topic $topicId",
        categorySlug: String = "trade",
        categoryName: String = "交易",
        authorName: String = "author",
        lastKnownFloor: Int = 10,
        lastKnownTotalPages: Int = 1,
        unreadNewReplies: Int = 0,
        isActive: Boolean = true,
        lastChecked: Long = 1_000L,
        lastUpdated: Long = 1_000L,
    ): TrackedTopicEntity = TrackedTopicEntity(
        topicId = topicId,
        title = title,
        categorySlug = categorySlug,
        categoryName = categoryName,
        authorName = authorName,
        lastKnownFloor = lastKnownFloor,
        lastKnownTotalPages = lastKnownTotalPages,
        unreadNewReplies = unreadNewReplies,
        isActive = isActive,
        createdAtEpochMillis = 500L,
        lastCheckedEpochMillis = lastChecked,
        lastUpdatedEpochMillis = lastUpdated,
    )

    private fun sampleAlert(
        dedupKey: String,
        topicId: Long,
        isRead: Boolean,
        createdAt: Long,
    ): LocalAlertEntity = LocalAlertEntity(
        dedupKey = dedupKey,
        kind = "TOPIC_REPLY",
        topicId = topicId,
        topicTitle = "Topic $topicId",
        targetFloor = 12,
        matchedKeyword = "",
        actorName = "replier",
        summarySnippet = "snippet",
        categoryName = "交易",
        isRead = isRead,
        createdAtEpochMillis = createdAt,
    )
}
