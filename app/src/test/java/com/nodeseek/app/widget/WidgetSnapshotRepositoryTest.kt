package com.nodeseek.app.widget

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.data.widget.WidgetSnapshotRepositoryImpl
import com.nodeseek.app.domain.fleet.FleetAlertTransition
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetRefreshSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.ProbeConnectivityResult
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.LatestSeekMailOtp
import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailConnectionStatus
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailException
import com.nodeseek.app.domain.mail.SeekMailPage
import com.nodeseek.app.domain.mail.SeekMailRateLimit
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailUnreadPage
import com.nodeseek.app.domain.mail.SeekMailbox
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.domain.repository.CheckInRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetSnapshotRepositoryTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 26)

    private class ColdStartFleetRepository(
        private val persistedAssets: List<VpsAssetRecord>,
        private val persistedSnapshots: List<ProbeServerSnapshot>,
        private val persistedLastSync: Long,
    ) : FleetRepository {
        var loadPersistedCallCount = 0

        private val _loaded = MutableStateFlow(false)
        override val isPersistedStateLoadedFlow: StateFlow<Boolean> = _loaded.asStateFlow()

        private val _panels = MutableStateFlow<List<ProbePanelConfig>>(emptyList())
        override val panelsFlow: StateFlow<List<ProbePanelConfig>> = _panels.asStateFlow()

        private val _snapshots = MutableStateFlow<List<ProbeServerSnapshot>>(emptyList())
        override val snapshotsFlow: StateFlow<List<ProbeServerSnapshot>> = _snapshots.asStateFlow()

        private val _assets = MutableStateFlow<List<VpsAssetRecord>>(emptyList())
        override val assetsFlow: StateFlow<List<VpsAssetRecord>> = _assets.asStateFlow()

        private val _settings = MutableStateFlow(FleetMonitorSettings())
        override val monitorSettingsFlow: StateFlow<FleetMonitorSettings> = _settings.asStateFlow()

        private val _lastSync = MutableStateFlow(0L)
        override val lastSyncEpochMillisFlow: StateFlow<Long> = _lastSync.asStateFlow()

        private val _refreshing = MutableStateFlow(false)
        override val isRefreshingFlow: StateFlow<Boolean> = _refreshing.asStateFlow()

        private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
        override val panelErrorsFlow: StateFlow<Map<String, String>> = _errors.asStateFlow()

        override suspend fun loadPersistedState() {
            loadPersistedCallCount++
            _assets.value = persistedAssets
            _snapshots.value = persistedSnapshots
            _lastSync.value = persistedLastSync
            _loaded.value = true
        }

        override suspend fun upsertPanel(config: ProbePanelConfig) = config
        override suspend fun deletePanel(panelId: String) = Unit
        override suspend fun testPanelConnection(config: ProbePanelConfig) = ProbeConnectivityResult(true)
        override suspend fun refreshAllPanels() = FleetRefreshSummary(0, 0, emptyMap(), emptyList(), 0L)
        override suspend fun refreshPanel(panelId: String): Result<List<ProbeServerSnapshot>> = Result.success(emptyList())
        override suspend fun upsertAsset(asset: VpsAssetRecord) = asset
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun linkAssetToProbe(assetId: String, probeKey: String?) = Unit
        override suspend fun updateMonitorSettings(settings: FleetMonitorSettings) = Unit
        override suspend fun evaluateAlerts(
            today: LocalDate,
            nowEpochMillis: Long,
            fxRatesState: FxRatesState?,
            checkedPanels: Int?,
            failedPanels: Int,
        ) = FleetMonitorCheckReport()
    }

    private class FakeSessionRepo(initial: AccountSession?) : SessionRepository {
        private val _state = MutableStateFlow(initial)
        override val sessionState: StateFlow<AccountSession?> = _state.asStateFlow()
        override val challengeRequired: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val authVerifiedEpoch: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        override suspend fun refreshSession(): Result<AccountSession?> = Result.success(_state.value)
        override suspend fun signIn(
            username: String,
            password: String,
            turnstileToken: String,
        ): Result<SignInStepResult> = Result.success(SignInStepResult.Authenticated)
        override suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit> =
            Result.success(Unit)
        override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(_state.value)
        override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(_state.value)
        override suspend fun signOut(): Result<Unit> = Result.success(Unit)
    }

    private class FakeCheckInRepo(initial: CheckInStatus?) : CheckInRepository {
        private val _status = MutableStateFlow(initial)
        override val checkInStatus: StateFlow<CheckInStatus?> = _status.asStateFlow()
        override fun clearCachedStatus() = Unit
        override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean) =
            Result.success(_status.value ?: CheckInStatus(false))
        override suspend fun checkIn(randomReward: Boolean) =
            Result.success(_status.value ?: CheckInStatus(true, todayGain = 5))
        override suspend fun getDailyProgress() = Result.success(DailyQuotaProgress())
    }

    private class FakeRadarRepo(
        private val polling: RadarPollingSettings = RadarPollingSettings(),
        unreadCount: Int = 0,
        alerts: List<LocalAlertRecord> = emptyList(),
    ) : RadarRepository {
        override val pollingSettingsFlow: Flow<RadarPollingSettings> = MutableStateFlow(polling)
        override val keywordRulesFlow: Flow<List<KeywordWatchRule>> = MutableStateFlow(emptyList())
        override val trackedTopicsFlow: Flow<List<TrackedTopic>> = MutableStateFlow(emptyList())
        override val alertRecordsFlow: Flow<List<LocalAlertRecord>> = MutableStateFlow(alerts)
        override val unreadAlertCountFlow: Flow<Int> = MutableStateFlow(unreadCount)
        override fun observeTrackedTopic(topicId: Long): Flow<TrackedTopic?> = MutableStateFlow(null)
        override suspend fun getPollingSettings() = polling
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
        ) = TrackedTopic(topicId, title)
        override suspend fun untrackTopic(topicId: Long) = Unit
        override suspend fun toggleTrackTopic(
            topicId: Long,
            title: String,
            categorySlug: String,
            categoryName: String,
            authorName: String,
            currentMaxFloor: Int,
            totalPages: Int,
        ) = false
        override suspend fun onTopicFloorSeen(topicId: Long, maxSeenFloor: Int, totalPages: Int) = Unit
        override suspend fun recordAlertIfAbsent(record: LocalAlertRecord) = false
        override suspend fun markAlertRead(alertId: Long, read: Boolean) = Unit
        override suspend fun markAlertReadByDedupKey(dedupKey: String) = Unit
        override suspend fun markAlertsReadForTopic(topicId: Long) = Unit
        override suspend fun markAllAlertsRead() = Unit
        override suspend fun deleteAlert(alertId: Long) = Unit
        override suspend fun clearReadAlerts() = Unit
        override suspend fun clearAllAlerts() = Unit
    }

    private class FakeSeekMailRepo(
        var cachedOtp: LatestSeekMailOtp? = null,
        var freshHandler: suspend () -> Result<SeekMailPage> = {
            Result.success(SeekMailPage(emptyList(), 20, 0, 0, false))
        },
    ) : SeekMailRepository {
        var getMailsCalls = 0
        override val locallyReadMailIds: StateFlow<Set<Long>> = MutableStateFlow(emptySet<Long>()).asStateFlow()
        override val latestRateLimit: StateFlow<SeekMailRateLimit?> = MutableStateFlow<SeekMailRateLimit?>(null).asStateFlow()
        override suspend fun verifyApiKey(candidateKey: String?) = Result.success(SeekMailConnectionStatus(emptyList()))
        override suspend fun getMailboxes(forceRefresh: Boolean) = Result.success(emptyList<SeekMailbox>())
        override suspend fun checkMailboxAvailable(address: String) = Result.success(true)
        override suspend fun createMailbox(address: String) = Result.failure<SeekMailbox>(UnsupportedOperationException())
        override suspend fun getMails(
            mailboxId: Long?,
            search: String?,
            tagId: Long?,
            limit: Int,
            offset: Int,
            forceRefresh: Boolean,
        ): Result<SeekMailPage> {
            getMailsCalls++
            return freshHandler()
        }
        override suspend fun getUnreadMails(mailboxId: Long?, limit: Int, cursor: String?) =
            Result.success(SeekMailUnreadPage(emptyList(), limit, false))
        override suspend fun getMailDetail(mailId: Long, forceRefresh: Boolean) =
            Result.failure<SeekMailDetail>(UnsupportedOperationException())
        override suspend fun getAttachments(mailId: Long) = Result.success(emptyList<SeekMailAttachment>())
        override suspend fun downloadAttachmentBytes(mailId: Long, attachmentId: String) =
            Result.success(ByteArray(0))
        override suspend fun getTags(forceRefresh: Boolean) = Result.success(emptyList<SeekMailTag>())
        override suspend fun getContacts(limit: Int, forceRefresh: Boolean) = Result.success(emptyList<SeekMailContact>())
        override suspend fun markMailReadLocally(mailId: Long) = Unit
        override suspend fun clearCache() = Unit
        override suspend fun getCachedLatestOtp(): LatestSeekMailOtp? = cachedOtp
    }

    @Test
    fun `loadServerStatusSnapshot calls loadPersistedState on cold FleetRepository before aggregating`() = runBlocking {
        val probe = ProbeServerSnapshot(
            panelId = "p1",
            remoteServerId = "s1",
            name = "主力节点",
            isOnline = true,
            monthlyTransferInBytes = 50L * 1024L * 1024L * 1024L,
            trafficLimitBytes = 200L * 1024L * 1024L * 1024L,
        )
        val vps = VpsAssetRecord(
            id = "vps1",
            nickname = "JP-VPS",
            providerName = "DMIT",
            specsSummary = "1C1G",
            region = "JP",
            renewalAmount = "36.9",
            expiryDateIso = "2026-11-30",
            assetType = FleetAssetType.VPS,
        )
        val coldFleet = ColdStartFleetRepository(
            persistedAssets = listOf(vps),
            persistedSnapshots = listOf(probe),
            persistedLastSync = 987654L,
        )
        val repo = WidgetSnapshotRepositoryImpl(
            fleetRepository = coldFleet,
            sessionRepository = FakeSessionRepo(null),
            checkInRepository = FakeCheckInRepo(null),
            settingsRepository = FakeSettingsRepository(),
            radarRepository = FakeRadarRepo(),
            seekMailRepository = FakeSeekMailRepo(),
            todayProvider = { today },
        )

        val snapshot = repo.loadServerStatusSnapshot()

        assertEquals(1, coldFleet.loadPersistedCallCount)
        assertEquals(1, snapshot.totalProbeCount)
        assertEquals(1, snapshot.onlineProbeCount)
        assertEquals("主力节点", snapshot.primaryServerName)
        assertEquals("JP-VPS", snapshot.nearestVpsName)
        assertEquals(4L, snapshot.nearestVpsDaysRemaining)
        assertEquals(987654L, snapshot.lastSyncEpochMillis)
    }

    @Test
    fun `loadDailyPulseSnapshot prefers unread radar alert and loads cached OTP when key is configured`() = runBlocking {
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_widget_key")
            setPreferRandomCheckIn(true)
        }
        val readNewerAlert = LocalAlertRecord(
            id = 2L,
            dedupKey = "kw:2",
            kind = LocalAlertKind.KEYWORD_HIT,
            topicId = 200L,
            topicTitle = "已读帖子",
            isRead = true,
        )
        val unreadAlert = LocalAlertRecord(
            id = 1L,
            dedupKey = "kw:1",
            kind = LocalAlertKind.KEYWORD_HIT,
            topicId = 100L,
            topicTitle = "未读补货命中",
            matchedKeyword = "LAX",
            isRead = false,
        )
        val cachedOtp = LatestSeekMailOtp(
            mailId = 55L,
            code = "654321",
            sender = "GitHub",
            subject = "Sudo OTP",
            receivedAt = "2026-11-26T08:15:00Z",
        )
        val repo = WidgetSnapshotRepositoryImpl(
            fleetRepository = ColdStartFleetRepository(emptyList(), emptyList(), 0L),
            sessionRepository = FakeSessionRepo(AccountSession(uid = 7L, username = "bob", drumsticks = 88)),
            checkInRepository = FakeCheckInRepo(CheckInStatus(hasCheckedInToday = false)),
            settingsRepository = settings,
            radarRepository = FakeRadarRepo(
                polling = RadarPollingSettings(isEnabled = true),
                unreadCount = 1,
                alerts = listOf(readNewerAlert, unreadAlert),
            ),
            seekMailRepository = FakeSeekMailRepo(cachedOtp = cachedOtp),
            todayProvider = { today },
        )

        val snapshot = repo.loadDailyPulseSnapshot()

        assertTrue(snapshot.isSignedIn)
        assertEquals("bob", snapshot.username)
        assertEquals(88, snapshot.drumsticks)
        assertEquals("未读补货命中", snapshot.latestAlertTitle)
        assertEquals("LAX", snapshot.latestAlertKeyword)
        assertEquals("654321", snapshot.latestOtpCode)
        assertEquals("GitHub", snapshot.latestOtpSender)
        assertTrue(snapshot.hasSeekMailKey)
    }

    @Test
    fun `fetchOrCachedLatestOtp returns fresh OTP when available and falls back to cache on error or timeout`() = runBlocking {
        val settings = FakeSettingsRepository()
        val cachedOtp = LatestSeekMailOtp(
            mailId = 10L,
            code = "112233",
            sender = "CachedSender",
            subject = "Cached OTP",
            receivedAt = "2026-11-26T07:00:00Z",
        )
        val seekMailRepo = FakeSeekMailRepo(cachedOtp = cachedOtp)
        val repo = WidgetSnapshotRepositoryImpl(
            fleetRepository = ColdStartFleetRepository(emptyList(), emptyList(), 0L),
            sessionRepository = FakeSessionRepo(null),
            checkInRepository = FakeCheckInRepo(null),
            settingsRepository = settings,
            radarRepository = FakeRadarRepo(),
            seekMailRepository = seekMailRepo,
            todayProvider = { today },
        )

        // Missing key -> null without network call
        assertNull(repo.fetchOrCachedLatestOtp())
        assertEquals(0, seekMailRepo.getMailsCalls)

        // Fresh network succeeds -> returns fresh OTP
        settings.setSeekMailApiKey("sk_live_widget_key")
        seekMailRepo.freshHandler = {
            Result.success(
                SeekMailPage(
                    items = listOf(
                        SeekMailSummary(
                            id = 99L,
                            mailboxId = 1L,
                            fromAddress = "auth@example.com",
                            fromName = "AuthService",
                            toAddress = "me@seek.li",
                            subject = "Verification code: 998877",
                            snippet = "Use 998877",
                            verificationCode = "998877",
                            receivedAt = "2026-11-26T08:30:00Z",
                        ),
                    ),
                    limit = 20,
                    offset = 0,
                    total = 1,
                    hasMore = false,
                ),
            )
        }
        val fresh = repo.fetchOrCachedLatestOtp()
        assertNotNull(fresh)
        assertEquals("998877", fresh!!.code)
        assertEquals("AuthService", fresh.sender)

        // Network error -> falls back to cached OTP
        seekMailRepo.freshHandler = {
            Result.failure(SeekMailException(SeekMailErrorKind.NETWORK, "Offline"))
        }
        val fallbackOnError = repo.fetchOrCachedLatestOtp()
        assertNotNull(fallbackOnError)
        assertEquals("112233", fallbackOnError!!.code)

        // Network timeout -> falls back to cached OTP
        seekMailRepo.freshHandler = {
            delay(500L)
            Result.success(SeekMailPage(emptyList(), 20, 0, 0, false))
        }
        val fallbackOnTimeout = repo.fetchOrCachedLatestOtp(timeoutMs = 30L)
        assertNotNull(fallbackOnTimeout)
        assertEquals("112233", fallbackOnTimeout!!.code)
    }

    @Test
    fun `cold start loadDailyPulseSnapshot and fetchOrCachedLatestOtp await delayed SettingsRepository hydration`() = runBlocking {
        val baseSettings = FakeSettingsRepository()
        val delayedSettings = object : SettingsRepository by baseSettings {
            override suspend fun awaitLoadedSettings(): AppSettings {
                delay(40L)
                baseSettings.setSeekMailApiKey("sk_live_delayed_key")
                baseSettings.setPreferRandomCheckIn(true)
                return baseSettings.settings.value
            }
        }
        val cachedOtp = LatestSeekMailOtp(
            mailId = 77L,
            code = "654321",
            sender = "GitHub",
            subject = "Cold-start OTP",
            receivedAt = "2026-11-26T09:00:00Z",
        )
        val seekMailRepo = FakeSeekMailRepo(
            cachedOtp = cachedOtp,
            freshHandler = {
                Result.success(
                    SeekMailPage(
                        items = listOf(
                            SeekMailSummary(
                                id = 88L,
                                mailboxId = 1L,
                                fromAddress = "otp@example.com",
                                fromName = "Example",
                                toAddress = "me@seek.li",
                                subject = "Code 998877",
                                snippet = "998877",
                                verificationCode = "998877",
                                receivedAt = "2026-11-26T09:10:00Z",
                            ),
                        ),
                        limit = 20,
                        offset = 0,
                        total = 1,
                        hasMore = false,
                    ),
                )
            },
        )
        val repo = WidgetSnapshotRepositoryImpl(
            fleetRepository = ColdStartFleetRepository(emptyList(), emptyList(), 0L),
            sessionRepository = FakeSessionRepo(AccountSession(uid = 9L, username = "cold", drumsticks = 12)),
            checkInRepository = FakeCheckInRepo(CheckInStatus(hasCheckedInToday = false)),
            settingsRepository = delayedSettings,
            radarRepository = FakeRadarRepo(),
            seekMailRepository = seekMailRepo,
            todayProvider = { today },
        )

        // Verify settings.value is still empty before awaiting hydration.
        assertEquals("", delayedSettings.settings.value.seekMailApiKey)
        assertFalse(delayedSettings.settings.value.preferRandomCheckIn)

        val pulse = repo.loadDailyPulseSnapshot()
        assertTrue(pulse.hasSeekMailKey)
        assertTrue(pulse.preferRandomCheckIn)
        assertEquals("654321", pulse.latestOtpCode)

        // Reset to unhydrated state and verify fetchOrCachedLatestOtp awaits hydration too.
        baseSettings.setSeekMailApiKey("")
        baseSettings.setPreferRandomCheckIn(false)
        val fetchedOtp = repo.fetchOrCachedLatestOtp()
        assertNotNull(fetchedOtp)
        assertEquals("998877", fetchedOtp!!.code)
    }
}
