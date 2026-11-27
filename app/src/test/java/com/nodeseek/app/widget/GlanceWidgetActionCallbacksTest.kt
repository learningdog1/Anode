package com.nodeseek.app.widget

import android.content.Context
import android.content.ContextWrapper
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import com.nodeseek.app.data.radar.AppContainerHost
import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetRefreshSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.ProbeConnectivityResult
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.repository.CheckInRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

class GlanceWidgetActionCallbacksTest {

    private class FakeGlanceId : GlanceId

    private class FakeWidgetHostContext(
        override val container: AppContainer,
    ) : ContextWrapper(null), AppContainerHost {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `CheckInActionCallback_onAction refreshes null session and skips checkIn when still unauthenticated`() =
        runBlocking {
            val sessionRepo = object : SessionRepository {
                override val sessionState = MutableStateFlow<AccountSession?>(null)
                override val challengeRequired = MutableStateFlow(false)
                override val authVerifiedEpoch = MutableStateFlow(0L)
                var refreshCalls = 0
                override suspend fun refreshSession(): Result<AccountSession?> {
                    refreshCalls += 1
                    return Result.success(null)
                }
                override suspend fun signIn(username: String, password: String, turnstileToken: String) =
                    Result.success(SignInStepResult.Authenticated)
                override suspend fun verifyTwoFactor(otpCode: String, otpSession: String) = Result.success(Unit)
                override suspend fun onWebViewAuthCompleted() = Result.success(null)
                override suspend fun onWebViewDismissed() = Result.success(null)
                override suspend fun signOut() = Result.success(Unit)
            }
            var checkInCalls = 0
            val checkInRepo = object : CheckInRepository {
                override val checkInStatus = MutableStateFlow<CheckInStatus?>(null)
                override fun clearCachedStatus() = Unit
                override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean) =
                    Result.success(CheckInStatus(hasCheckedInToday = false))
                override suspend fun getDailyProgress() =
                    Result.success(DailyQuotaProgress(0, 0, 0, 0))
                override suspend fun checkIn(randomReward: Boolean): Result<CheckInStatus> {
                    checkInCalls += 1
                    return Result.success(CheckInStatus(hasCheckedInToday = true, todayGain = 5))
                }
            }
            val container = buildContainer(
                sessionRepo = sessionRepo,
                checkInRepo = checkInRepo,
                settingsRepo = FakeSettingsRepository(),
                fleetRepo = NoopFleetRepo(),
            )
            CheckInActionCallback().onAction(
                context = FakeWidgetHostContext(container),
                glanceId = FakeGlanceId(),
                parameters = actionParametersOf(),
            )
            assertEquals(1, sessionRepo.refreshCalls)
            assertEquals(0, checkInCalls)
        }

    @Test
    fun `CheckInActionCallback_onAction executes checkIn with preferRandomCheckIn setting when authenticated`() =
        runBlocking {
            val sessionRepo = object : SessionRepository {
                override val sessionState = MutableStateFlow<AccountSession?>(
                    AccountSession(uid = 101L, username = "Alice", drumsticks = 50),
                )
                override val challengeRequired = MutableStateFlow(false)
                override val authVerifiedEpoch = MutableStateFlow(0L)
                override suspend fun refreshSession() = Result.success(sessionState.value)
                override suspend fun signIn(username: String, password: String, turnstileToken: String) =
                    Result.success(SignInStepResult.Authenticated)
                override suspend fun verifyTwoFactor(otpCode: String, otpSession: String) = Result.success(Unit)
                override suspend fun onWebViewAuthCompleted() = Result.success(sessionState.value)
                override suspend fun onWebViewDismissed() = Result.success(sessionState.value)
                override suspend fun signOut() = Result.success(Unit)
            }
            val observedRandomFlags = mutableListOf<Boolean>()
            val checkInRepo = object : CheckInRepository {
                override val checkInStatus = MutableStateFlow<CheckInStatus?>(null)
                override fun clearCachedStatus() = Unit
                override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean) =
                    Result.success(CheckInStatus(hasCheckedInToday = false))
                override suspend fun getDailyProgress() =
                    Result.success(DailyQuotaProgress(0, 0, 0, 0))
                override suspend fun checkIn(randomReward: Boolean): Result<CheckInStatus> {
                    observedRandomFlags += randomReward
                    return Result.failure(IllegalStateException("今日已签到"))
                }
            }
            val settingsRepo = FakeSettingsRepository().apply {
                setPreferRandomCheckIn(false)
            }
            val container = buildContainer(
                sessionRepo = sessionRepo,
                checkInRepo = checkInRepo,
                settingsRepo = settingsRepo,
                fleetRepo = NoopFleetRepo(),
            )
            CheckInActionCallback().onAction(
                context = FakeWidgetHostContext(container),
                glanceId = FakeGlanceId(),
                parameters = actionParametersOf(),
            )
            assertEquals(listOf(false), observedRandomFlags)
        }

    @Test
    fun `RefreshServerStatusActionCallback_onAction loads persisted state and triggers silent panel refresh`() =
        runBlocking {
            val events = mutableListOf<String>()
            val fleetRepo = object : NoopFleetRepo() {
                override suspend fun loadPersistedState() {
                    events += "loadPersistedState"
                }

                override suspend fun refreshAllPanels(silent: Boolean): FleetRefreshSummary {
                    events += "refreshAllPanels:silent=$silent"
                    return FleetRefreshSummary(
                        refreshedPanelCount = 1,
                        succeededPanelCount = 1,
                        failedPanelErrors = emptyMap(),
                        snapshots = emptyList(),
                        syncedAtEpochMillis = 123L,
                    )
                }
            }
            val container = buildContainer(
                sessionRepo = object : SessionRepository {
                    override val sessionState = MutableStateFlow<AccountSession?>(null)
                    override val challengeRequired = MutableStateFlow(false)
                    override val authVerifiedEpoch = MutableStateFlow(0L)
                    override suspend fun refreshSession() = Result.success(null)
                    override suspend fun signIn(username: String, password: String, turnstileToken: String) =
                        Result.success(SignInStepResult.Authenticated)
                    override suspend fun verifyTwoFactor(otpCode: String, otpSession: String) = Result.success(Unit)
                    override suspend fun onWebViewAuthCompleted() = Result.success(null)
                    override suspend fun onWebViewDismissed() = Result.success(null)
                    override suspend fun signOut() = Result.success(Unit)
                },
                checkInRepo = object : CheckInRepository {
                    override val checkInStatus = MutableStateFlow<CheckInStatus?>(null)
                    override fun clearCachedStatus() = Unit
                    override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean) =
                        Result.success(CheckInStatus(false))
                    override suspend fun getDailyProgress() =
                        Result.success(DailyQuotaProgress(0, 0, 0, 0))
                    override suspend fun checkIn(randomReward: Boolean) =
                        Result.success(CheckInStatus(true))
                },
                settingsRepo = FakeSettingsRepository(),
                fleetRepo = fleetRepo,
            )

            RefreshServerStatusActionCallback().onAction(
                context = FakeWidgetHostContext(container),
                glanceId = FakeGlanceId(),
                parameters = actionParametersOf(),
            )
            assertTrue(events == listOf("loadPersistedState", "refreshAllPanels:silent=true"))
        }

    private fun buildContainer(
        sessionRepo: SessionRepository,
        checkInRepo: CheckInRepository,
        settingsRepo: FakeSettingsRepository,
        fleetRepo: FleetRepository,
    ): AppContainer = Proxy.newProxyInstance(
        AppContainer::class.java.classLoader,
        arrayOf(AppContainer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSessionRepository" -> sessionRepo
            "getCheckInRepository" -> checkInRepo
            "getSettingsRepository" -> settingsRepo
            "getFleetRepository" -> fleetRepo
            else -> throw UnsupportedOperationException("Unexpected AppContainer call: ${method.name}")
        }
    } as AppContainer

    private open class NoopFleetRepo : FleetRepository {
        override val panelsFlow: StateFlow<List<ProbePanelConfig>> = MutableStateFlow(emptyList<ProbePanelConfig>()).asStateFlow()
        override val snapshotsFlow: StateFlow<List<ProbeServerSnapshot>> = MutableStateFlow(emptyList<ProbeServerSnapshot>()).asStateFlow()
        override val assetsFlow: StateFlow<List<VpsAssetRecord>> = MutableStateFlow(emptyList<VpsAssetRecord>()).asStateFlow()
        override val monitorSettingsFlow: StateFlow<FleetMonitorSettings> = MutableStateFlow(FleetMonitorSettings()).asStateFlow()
        override val panelErrorsFlow: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap<String, String>()).asStateFlow()
        override val isRefreshingFlow: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val lastSyncEpochMillisFlow: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        override suspend fun loadPersistedState() = Unit
        override suspend fun upsertPanel(config: ProbePanelConfig): ProbePanelConfig = config
        override suspend fun deletePanel(panelId: String) = Unit
        override suspend fun testPanelConnection(config: ProbePanelConfig): ProbeConnectivityResult =
            ProbeConnectivityResult(success = true, detectedPanelType = ProbePanelType.KOMARI)
        override suspend fun refreshAllPanels(): FleetRefreshSummary =
            FleetRefreshSummary(0, 0, emptyMap(), emptyList(), 0L)
        override suspend fun refreshPanel(panelId: String): Result<List<ProbeServerSnapshot>> =
            Result.success(emptyList())
        override suspend fun updateMonitorSettings(settings: FleetMonitorSettings) = Unit
        override suspend fun upsertAsset(asset: VpsAssetRecord): VpsAssetRecord = asset
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun linkAssetToProbe(assetId: String, probeKey: String?) = Unit
        override suspend fun evaluateAlerts(
            today: LocalDate,
            nowEpochMillis: Long,
            fxRatesState: FxRatesState?,
            checkedPanels: Int?,
            failedPanels: Int,
        ): FleetMonitorCheckReport = FleetMonitorCheckReport()
    }
}
