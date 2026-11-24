package com.nodeseek.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryDaoTest {

    private lateinit var db: NodePulseDatabase
    private lateinit var dao: CategoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = NodePulseDatabase.buildInMemory(context)
        dao = db.categoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun replaceForAccount_isolatesAccountsSortsBySortOrderAndPreservesVisibilityFlags() = runTest {
        // P2-17 related: CategoryDao persistence must preserve isCustomizable / visibleOnHome per account
        dao.replaceForAccount(
            accountUid = 0L,
            entities = listOf(
                sampleCategory(accountUid = 0L, slug = "trade", sortOrder = 2, isCustomizable = true, visibleOnHome = false, syncedAt = 100L),
                sampleCategory(accountUid = 0L, slug = "daily", sortOrder = 0, isCustomizable = false, visibleOnHome = true, syncedAt = 250L),
            ),
        )
        dao.replaceForAccount(
            accountUid = 42L,
            entities = listOf(
                sampleCategory(accountUid = 42L, slug = "inside", sortOrder = 1, isCustomizable = true, visibleOnHome = true, isAdminOnly = true, syncedAt = 500L),
            ),
        )

        val guestCats = dao.observeCategories(0L).first()
        assertEquals(listOf("daily", "trade"), guestCats.map { it.slug })
        assertTrue(guestCats[1].isCustomizable)
        assertFalse(guestCats[1].visibleOnHome)
        assertEquals(250L, dao.getLastSyncedAtMillis(0L))

        // Replacing account 0L must not affect account 42L
        dao.replaceForAccount(
            accountUid = 0L,
            entities = listOf(
                sampleCategory(accountUid = 0L, slug = "tech", sortOrder = 0, syncedAt = 400L),
            ),
        )
        assertEquals(listOf("tech"), dao.getCategories(0L).map { it.slug })
        assertEquals(400L, dao.getLastSyncedAtMillis(0L))

        val userCats = dao.getCategories(42L)
        assertEquals(listOf("inside"), userCats.map { it.slug })
        assertTrue(userCats.single().isAdminOnly)
        assertEquals(500L, dao.getLastSyncedAtMillis(42L))
    }

    @Test
    fun clearForAccount_andEmptyAccountReturnNullLastSyncedAt() = runTest {
        assertNull(dao.getLastSyncedAtMillis(99L))

        dao.upsertAll(
            listOf(
                sampleCategory(accountUid = 10L, slug = "daily", sortOrder = 0, syncedAt = 100L),
                sampleCategory(accountUid = 20L, slug = "daily", sortOrder = 0, syncedAt = 200L),
            ),
        )
        dao.clearForAccount(10L)

        assertTrue(dao.getCategories(10L).isEmpty())
        assertNull(dao.getLastSyncedAtMillis(10L))
        assertEquals(200L, dao.getLastSyncedAtMillis(20L))
    }

    private fun sampleCategory(
        accountUid: Long,
        slug: String,
        sortOrder: Int,
        isCustomizable: Boolean = true,
        visibleOnHome: Boolean = true,
        isAdminOnly: Boolean = false,
        syncedAt: Long = 1_000L,
    ): CategoryEntity = CategoryEntity(
        accountUid = accountUid,
        slug = slug,
        name = slug.uppercase(),
        description = "Desc $slug",
        sortOrder = sortOrder,
        isCustomizable = isCustomizable,
        visibleOnHome = visibleOnHome,
        isAdminOnly = isAdminOnly,
        topicCoinCost = 0,
        supportsRss = true,
        syncedAtEpochMillis = syncedAt,
    )
}
