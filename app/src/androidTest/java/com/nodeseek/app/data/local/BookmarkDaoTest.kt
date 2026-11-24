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
class BookmarkDaoTest {

    private lateinit var db: NodePulseDatabase
    private lateinit var dao: BookmarkDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = NodePulseDatabase.buildInMemory(context)
        dao = db.bookmarkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getBookmarkedTopicIds_andObserveBookmarks_isolateByAccountAndSortNewestFirst() = runTest {
        // P3-10 regression: one-shot getBookmarkedTopicIds must match account-scoped rows
        dao.upsertAll(
            listOf(
                sampleBookmark(accountUid = 0L, topicId = 10L, savedAt = 100L),
                sampleBookmark(accountUid = 42L, topicId = 10L, savedAt = 200L),
                sampleBookmark(accountUid = 42L, topicId = 20L, savedAt = 500L),
                sampleBookmark(accountUid = 42L, topicId = 30L, savedAt = 300L),
            ),
        )

        assertEquals(setOf(10L), dao.getBookmarkedTopicIds(0L).toSet())
        assertEquals(setOf(10L, 20L, 30L), dao.getBookmarkedTopicIds(42L).toSet())
        assertEquals(setOf(10L, 20L, 30L), dao.observeBookmarkedTopicIds(42L).first().toSet())

        val ordered = dao.observeBookmarks(42L).first()
        assertEquals(listOf(20L, 30L, 10L), ordered.map { it.topicId })
    }

    @Test
    fun updateOfflineSnapshot_updatesOnlyMatchingAccountAndTopicAndAllowsClearing() = runTest {
        dao.upsert(sampleBookmark(accountUid = 0L, topicId = 55L, offlineDetailJson = null))
        dao.upsert(sampleBookmark(accountUid = 42L, topicId = 55L, offlineDetailJson = null))

        dao.updateOfflineSnapshot(accountUid = 42L, topicId = 55L, offlineDetailJson = """{"cached":true}""")

        assertEquals("""{"cached":true}""", dao.getBookmark(42L, 55L)!!.offlineDetailJson)
        assertNull(dao.getBookmark(0L, 55L)!!.offlineDetailJson)

        dao.updateOfflineSnapshot(accountUid = 42L, topicId = 55L, offlineDetailJson = null)
        assertNull(dao.getBookmark(42L, 55L)!!.offlineDetailJson)
    }

    @Test
    fun deleteClearAndReplaceAllBookmarks_respectAccountScopeAndTransactions() = runTest {
        dao.upsertAll(
            listOf(
                sampleBookmark(accountUid = 1L, topicId = 100L, savedAt = 10L),
                sampleBookmark(accountUid = 1L, topicId = 101L, savedAt = 20L),
                sampleBookmark(accountUid = 2L, topicId = 100L, savedAt = 30L),
            ),
        )

        dao.deleteBookmark(accountUid = 1L, topicId = 100L)
        assertNull(dao.getBookmark(1L, 100L))
        assertEquals(listOf(101L), dao.getBookmarkedTopicIds(1L))
        assertEquals(listOf(100L), dao.getBookmarkedTopicIds(2L))

        dao.clearForAccount(1L)
        assertTrue(dao.getBookmarkedTopicIds(1L).isEmpty())
        assertEquals(listOf(100L), dao.getBookmarkedTopicIds(2L))

        dao.replaceAllBookmarks(
            listOf(
                sampleBookmark(accountUid = 9L, topicId = 900L, savedAt = 900L),
            ),
        )
        val all = dao.getAllBookmarks()
        assertEquals(1, all.size)
        assertEquals(9L, all.single().accountUid)
        assertEquals(900L, all.single().topicId)

        dao.replaceAllBookmarks(emptyList())
        assertTrue(dao.getAllBookmarks().isEmpty())
    }

    private fun sampleBookmark(
        accountUid: Long,
        topicId: Long,
        savedAt: Long = 1_000L,
        offlineDetailJson: String? = null,
    ): BookmarkEntity = BookmarkEntity(
        accountUid = accountUid,
        topicId = topicId,
        title = "Bookmark $topicId",
        categorySlug = "tech",
        categoryName = "技术",
        authorId = 7L,
        authorName = "tester",
        authorAvatarUrl = "",
        authorLevel = 3,
        viewCount = 100,
        replyCount = 12,
        lastActivityIso = "2026-11-20T00:00:00Z",
        requiredViewLevel = null,
        isLocked = false,
        offlineDetailJson = offlineDetailJson,
        isSyncedWithServer = true,
        savedAtEpochMillis = savedAt,
    )
}
