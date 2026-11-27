package com.nodeseek.app.ui.radar

import android.content.ContextWrapper
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarBackgroundPollOutcome
import com.nodeseek.app.domain.radar.RadarMonitorController
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.RadarRuntimeStatus
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PulseRadarViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `headless triggerImmediateCheck respects pollingSettings isEnabled without uiState collector for both Flow and StateFlow`() = runTest(testDispatcher) {
        // Case 1: non-StateFlow DataStore-like Flow + getPollingSettings()
        var currentSettings = RadarPollingSettings(isEnabled = false)
        val nonStateFlowRepo = FakeRadarRepository(
            pollingSettingsFlow = flow { emit(currentSettings) },
            settingsReader = { currentSettings },
        )
        val controller1 = CountingRadarMonitorController()
        val dispatcher = NoOpTestNotificationDispatcher()
        val vm1 = PulseRadarViewModel(
            radarRepository = nonStateFlowRepo,
            monitorController = controller1,
            notificationDispatcher = dispatcher,
        )

        // Zero uiState collectors: must NOT trigger when isEnabled == false
        vm1.triggerImmediateCheck()
        advanceUntilIdle()
        assertEquals(0, controller1.triggerCount)

        // When enabled == true, must trigger even without uiState collector
        currentSettings = RadarPollingSettings(isEnabled = true)
        vm1.triggerImmediateCheck()
        advanceUntilIdle()
        assertEquals(1, controller1.triggerCount)

        // Case 2: StateFlow pollingSettingsFlow
        val stateFlowSettings = MutableStateFlow(RadarPollingSettings(isEnabled = false))
        val stateFlowRepo = FakeRadarRepository(
            pollingSettingsFlow = stateFlowSettings,
            settingsReader = { stateFlowSettings.value },
        )
        val controller2 = CountingRadarMonitorController()
        val vm2 = PulseRadarViewModel(
            radarRepository = stateFlowRepo,
            monitorController = controller2,
            notificationDispatcher = dispatcher,
        )

        vm2.triggerImmediateCheck()
        assertEquals(0, controller2.triggerCount)

        stateFlowSettings.value = RadarPollingSettings(isEnabled = true)
        vm2.triggerImmediateCheck()
        assertEquals(1, controller2.triggerCount)
    }

    private class NoOpTestNotificationDispatcher : PulseNotificationDispatcher(ContextWrapper(null)) {
        override fun hasPostNotificationsPermission(): Boolean = true
        override fun isIgnoringBatteryOptimizations(): Boolean = true
        override fun isSystemDoNotDisturbActive(): Boolean = false
    }

    private class CountingRadarMonitorController : RadarMonitorController {
        var triggerCount = 0
        override val runtimeStatus: StateFlow<RadarRuntimeStatus> =
            MutableStateFlow(RadarRuntimeStatus()).asStateFlow()

        override fun start() = Unit
        override fun stop() = Unit
        override fun onForegroundChanged(inForeground: Boolean) = Unit
        override fun triggerImmediateCheck() {
            triggerCount++
        }
        override suspend fun runBackgroundPollPass(): RadarBackgroundPollOutcome =
            RadarBackgroundPollOutcome.Completed()
    }

    private class FakeRadarRepository(
        override val pollingSettingsFlow: Flow<RadarPollingSettings>,
        private val settingsReader: () -> RadarPollingSettings,
    ) : RadarRepository {
        override val keywordRulesFlow: Flow<List<KeywordWatchRule>> = MutableStateFlow(emptyList())
        override val trackedTopicsFlow: Flow<List<TrackedTopic>> = MutableStateFlow(emptyList())
        override val alertRecordsFlow: Flow<List<LocalAlertRecord>> = MutableStateFlow(emptyList())
        override val unreadAlertCountFlow: Flow<Int> = MutableStateFlow(0)
        override fun observeTrackedTopic(topicId: Long): Flow<TrackedTopic?> = MutableStateFlow(null)
        override suspend fun getPollingSettings(): RadarPollingSettings = settingsReader()
        override suspend fun updatePollingSettings(transform: (RadarPollingSettings) -> RadarPollingSettings) = Unit
        override suspend fun addKeywordRule(
            keyword: String,
            excludeWords: String,
            categorySlug: String,
            matchMode: KeywordMatchMode,
        ): Result<KeywordWatchRule> = Result.failure(UnsupportedOperationException())
        override suspend fun toggleKeywordRule(ruleId: Long, enabled: Boolean) = Unit
        override suspend fun deleteKeywordRule(ruleId: Long) = Unit
        override suspend fun trackTopic(
            topicId: Long,
            title: String,
            categorySlug: String,
            categoryName: String,
            authorName: String,
            currentMaxFloor: Int,
            totalPages: Int,
        ): TrackedTopic = error("unused")
        override suspend fun untrackTopic(topicId: Long) = Unit
        override suspend fun toggleTrackTopic(
            topicId: Long,
            title: String,
            categorySlug: String,
            categoryName: String,
            authorName: String,
            currentMaxFloor: Int,
            totalPages: Int,
        ): Boolean = false
        override suspend fun onTopicFloorSeen(topicId: Long, maxSeenFloor: Int, totalPages: Int) = Unit
        override suspend fun recordAlertIfAbsent(record: LocalAlertRecord): Boolean = false
        override suspend fun markAlertRead(alertId: Long, read: Boolean) = Unit
        override suspend fun markAlertReadByDedupKey(dedupKey: String) = Unit
        override suspend fun markAlertsReadForTopic(topicId: Long) = Unit
        override suspend fun markAllAlertsRead() = Unit
        override suspend fun deleteAlert(alertId: Long) = Unit
        override suspend fun clearReadAlerts() = Unit
        override suspend fun clearAllAlerts() = Unit
    }
}
