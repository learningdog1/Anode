package com.nodeseek.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadHistoryDaoTest {

    private lateinit var db: NodePulseDatabase
    private lateinit var dao: ReadHistoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = NodePulseDatabase.buildInMemory(context)
        dao = db.readHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getByTopicIds_returnsOneShotAccountScopedMatchesAndHandlesEmptyIds() = runTest {
        // P3-10 regression: one-shot batch lookup replacing Flow.first()
        dao.upsert(sampleHistory(accountUid = 0L, topicId = 100L, floor = 3, visitedAt = 100L))
        dao.upsert(sampleHistory(accountUid = 42L, topicId = 100L, floor = 12, visitedAt = 200L))
        dao.upsert(sampleHistory(accountUid = 42L, topicId = 200L, floor = 25, visitedAt = 300L))
        dao.upsert(sampleHistory(accountUid = 42L, topicId = 300L, floor = 1, visitedAt = 400L))

        val subset = dao.getByTopicIds(accountUid = 42L, topicIds = listOf(100L, 200L, 999L))
        assertEquals(mapOf(100L to 12, 200L to 25), subset.associate { it.topicId to it.lastReadFloor })

        val observedSubset = dao.observeByTopicIds(accountUid = 42L, topicIds = listOf(100L, 300L)).first()
        assertEquals(setOf(100L, 300L), observedSubset.map { it.topicId }.toSet())

        assertTrue(dao.getByTopicIds(accountUid = 42L, topicIds = emptyList()).isEmpty())
        assertEquals(3, dao.getByTopicIds(accountUid = 0L, topicIds = listOf(100L)).single().lastReadFloor)
    }

    @Test
    fun observeHistory_ordersByVisitedAtDescAndRespectsLimitAndUpsertUpdatesProgress() = runTest {
        dao.upsert(sampleHistory(accountUid = 7L, topicId = 1L, floor = 2, visitedAt = 100L))
        dao.upsert(sampleHistory(accountUid = 7L, topicId = 2L, floor = 5, visitedAt = 300L))
        dao.upsert(sampleHistory(accountUid = 7L, topicId = 3L, floor = 8, visitedAt = 200L))

        assertEquals(listOf(2L, 3L), dao.observeHistory(accountUid = 7L, limit = 2).first().map { it.topicId })

        // Re-visiting topic 1 moves it to the top and updates floor/page
        dao.upsert(sampleHistory(accountUid = 7L, topicId = 1L, page = 4, floor = 40, visitedAt = 500L))
        val top = dao.observeHistory(accountUid = 7L, limit = 10).first()
        assertEquals(listOf(1L, 2L, 3L), top.map { it.topicId })
        assertEquals(4, top.first().lastReadPage)
        assertEquals(40, top.first().lastReadFloor)
    }

    @Test
    fun deleteAndClearOperations_respectAccountScope() = runTest {
        dao.upsert(sampleHistory(accountUid = 1L, topicId = 10L, floor = 1, visitedAt = 10L))
        dao.upsert(sampleHistory(accountUid = 1L, topicId = 11L, floor = 1, visitedAt = 20L))
        dao.upsert(sampleHistory(accountUid = 2L, topicId = 10L, floor = 9, visitedAt = 30L))

        dao.deleteByTopicId(accountUid = 1L, topicId = 10L)
        assertNull(dao.getByTopicId(1L, 10L))
        assertEquals(9, dao.getByTopicId(2L, 10L)!!.lastReadFloor)

        dao.clearForAccount(1L)
        assertTrue(dao.observeHistory(1L).first().isEmpty())
        assertEquals(1, dao.observeHistory(2L).first().size)

        dao.clearAll()
        assertTrue(dao.observeHistory(2L).first().isEmpty())
    }

    private fun sampleHistory(
        accountUid: Long,
        topicId: Long,
        page: Int = 1,
        floor: Int,
        visitedAt: Long,
    ): ReadHistoryEntity = ReadHistoryEntity(
        accountUid = accountUid,
        topicId = topicId,
        title = "Topic $topicId",
        categorySlug = "daily",
        categoryName = "日常",
        authorName = "author",
        authorAvatarUrl = "",
        lastReadPage = page,
        lastReadFloor = floor,
        visitedAtEpochMillis = visitedAt,
    )
}
