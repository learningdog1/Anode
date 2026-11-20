package com.nodeseek.app.navigation

import com.nodeseek.app.domain.activity.EmptyActivityRepository
import com.nodeseek.app.ui.components.PulseNavTab
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavShellAndActivityTest {

    @Test
    fun `top level navigation tabs expose explore, feature hub, and pulse hub`() {
        val tabs = PulseNavTab.entries
        assertEquals(
            listOf(PulseNavTab.EXPLORE, PulseNavTab.FEATURE_HUB, PulseNavTab.PULSE_HUB),
            tabs,
        )
        assertEquals(listOf("首页", "功能栏", "我的"), tabs.map { it.label })
    }

    @Test
    fun `empty activity repository returns empty placeholder snapshot for ForumWatch hook`() = runTest {
        val repository = EmptyActivityRepository()

        assertTrue(repository.snapshot.value.activities.isEmpty())
        assertEquals(0L, repository.snapshot.value.updatedAtEpochMillis)

        val cached = repository.loadCachedActivities()
        assertTrue(cached.activities.isEmpty())

        val refreshed = repository.refreshActivities(force = true)
        assertTrue(refreshed.isSuccess)
        assertTrue(refreshed.getOrThrow().activities.isEmpty())
    }

    @Test
    fun `activity route round trips with kotlinx serialization`() {
        val encoded = Json.encodeToString(ActivityRoute)
        val decoded = Json.decodeFromString<ActivityRoute>(encoded)
        assertEquals(ActivityRoute, decoded)
    }
}
