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
class DraftDaoTest {

    private lateinit var db: NodePulseDatabase
    private lateinit var dao: DraftDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = NodePulseDatabase.buildInMemory(context)
        dao = db.draftDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertAndObserveDrafts_isolatesByAccountUidAndOrdersByUpdatedAtDesc() = runTest {
        dao.upsert(sampleDraft(accountUid = 0L, key = "new_topic", title = "Guest Draft", updatedAt = 100L))
        dao.upsert(sampleDraft(accountUid = 42L, key = "new_topic", title = "User Draft v1", updatedAt = 200L))
        dao.upsert(sampleDraft(accountUid = 42L, key = "reply:500", title = "Reply Draft", updatedAt = 500L))

        // Overwrite existing composite key (42L, "new_topic")
        dao.upsert(sampleDraft(accountUid = 42L, key = "new_topic", title = "User Draft v2", updatedAt = 700L))

        assertEquals("Guest Draft", dao.getDraft(0L, "new_topic")!!.title)
        assertEquals("User Draft v2", dao.observeDraft(42L, "new_topic").first()!!.title)

        val userDrafts = dao.observeDrafts(42L).first()
        assertEquals(listOf("new_topic", "reply:500"), userDrafts.map { it.draftKey })
    }

    @Test
    fun deleteClearAndReplaceAllDrafts_preserveIsolationAndAtomicity() = runTest {
        dao.upsertAll(
            listOf(
                sampleDraft(accountUid = 1L, key = "k1", title = "A", updatedAt = 10L),
                sampleDraft(accountUid = 1L, key = "k2", title = "B", updatedAt = 20L),
                sampleDraft(accountUid = 2L, key = "k1", title = "C", updatedAt = 30L),
            ),
        )

        dao.deleteDraft(1L, "k1")
        assertNull(dao.getDraft(1L, "k1"))
        assertEquals("C", dao.getDraft(2L, "k1")!!.title)

        dao.clearForAccount(1L)
        assertTrue(dao.observeDrafts(1L).first().isEmpty())
        assertEquals(1, dao.observeDrafts(2L).first().size)

        dao.replaceAllDrafts(
            listOf(
                sampleDraft(accountUid = 7L, key = "restored", title = "Restored", updatedAt = 999L),
            ),
        )
        val all = dao.getAllDrafts()
        assertEquals(1, all.size)
        assertEquals("restored", all.single().draftKey)

        dao.replaceAllDrafts(emptyList())
        assertTrue(dao.getAllDrafts().isEmpty())
    }

    private fun sampleDraft(
        accountUid: Long,
        key: String,
        title: String,
        updatedAt: Long,
    ): DraftEntity = DraftEntity(
        accountUid = accountUid,
        draftKey = key,
        title = title,
        markdownContent = "Body for $key",
        categorySlug = "daily",
        updatedAtMillis = updatedAt,
    )
}
