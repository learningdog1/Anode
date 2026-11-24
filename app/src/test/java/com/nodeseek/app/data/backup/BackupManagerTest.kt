package com.nodeseek.app.data.backup

import android.annotation.SuppressLint
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import com.nodeseek.app.data.local.BookmarkEntity
import com.nodeseek.app.data.local.ContentFilterRepositoryImpl
import com.nodeseek.app.data.local.DataStoreBlockedUsersCache
import com.nodeseek.app.data.local.DraftDao
import com.nodeseek.app.data.local.DraftEntity
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.local.DraftRepositoryImpl
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.radar.FakeRadarDao
import com.nodeseek.app.data.radar.RadarWorkManagerGateway
import com.nodeseek.app.data.radar.RadarWorkScheduler
import com.nodeseek.app.data.repository.BookmarkAndHistoryRepositoryImpl
import com.nodeseek.app.data.repository.FakeBookmarkDao
import com.nodeseek.app.data.repository.FakeReadHistoryDao
import com.nodeseek.app.data.repository.RadarRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.data.repository.UserRepositoryImpl
import com.nodeseek.app.domain.backup.BackupFileNaming
import com.nodeseek.app.domain.backup.BackupSyncTargetAccount
import com.nodeseek.app.domain.backup.ContentFilterBackupBridge
import com.nodeseek.app.domain.backup.ContentFilterBackupDto
import com.nodeseek.app.domain.backup.ContentFilterRuleBackupDto
import com.nodeseek.app.domain.backup.NodePulseBackupBundle
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.DraftRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BackupManagerTest {

    private lateinit var tempDir: File
    private lateinit var ioScope: CoroutineScope
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("backup_manager_test").toFile()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ioScope.cancel()
        tempDir.deleteRecursively()
    }

    private class TestNodeStack(
        name: String,
        tempDir: File,
        ioScope: CoroutineScope,
        server: MockWebServer,
        clockMillis: () -> Long = { 1_763_480_000_000L },
    ) {
        val settingsDataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(tempDir, "${name}_settings.preferences_pb") },
        )
        val radarDataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(tempDir, "${name}_radar.preferences_pb") },
        )
        val blockedCacheDataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(tempDir, "${name}_blocked.preferences_pb") },
        )

        val settingsRepository = SettingsRepositoryImpl(
            dataStore = settingsDataStore,
            externalScope = ioScope,
        )
        val radarDao = FakeRadarDao()
        val radarPreferencesStore = RadarPreferencesStore(radarDataStore)
        val radarRepository = RadarRepositoryImpl(
            radarDao = radarDao,
            preferencesStore = radarPreferencesStore,
            clockMillis = clockMillis,
        )
        val bookmarkDao = FakeBookmarkDao()
        val readHistoryDao = FakeReadHistoryDao()
        val draftDao = FakeDraftDao()
        val draftRepository = DraftRepositoryImpl(draftDao)
        val blockedUsersCache = DataStoreBlockedUsersCache(blockedCacheDataStore)
        val contentFilterBridge = FakeContentFilterBackupBridge()

        val cookieBridge = WebViewCookieBridge(forceInMemory = true)
        lateinit var apiClient: NodeSeekApiClient
        val sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        val bookmarkRepository = BookmarkAndHistoryRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            bookmarkDao = bookmarkDao,
            readHistoryDao = readHistoryDao,
        )
        val userRepository = UserRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            blockedUsersCache = blockedUsersCache,
        )

        init {
            val baseUrl = server.url("/")
            val okHttp = NodeSeekApiClient.createDefaultHttpClient(
                cookieBridge = cookieBridge,
                userAgentProvider = UserAgentProvider("AnodeBackupTest/1.0"),
                additionalTargetHost = baseUrl.host,
            )
            apiClient = NodeSeekApiClient(
                httpClient = okHttp,
                baseOrigin = baseUrl,
                rssUrl = server.url("/rss.xml"),
                nodeImageOrigin = baseUrl,
                onConfigObserved = sessionRepository::onConfigPayloadObserved,
            )
        }

        val backupManager = BackupManagerImpl(
            settingsRepository = settingsRepository,
            radarRepository = radarRepository,
            bookmarkAndHistoryRepository = bookmarkRepository,
            draftRepository = draftRepository,
            userRepository = userRepository,
            sessionRepository = sessionRepository,
            blockedUsersCache = blockedUsersCache,
            contentFilterBackupBridge = contentFilterBridge,
            appVersionProvider = { "2.0.1-test" },
            clockMillis = clockMillis,
        )
    }

    private fun encodeConfig(uid: Long, username: String): String {
        val raw = """{"user":{"member_id":$uid,"member_name":"$username","coin":500},"allCategory":[{"key":"tech","cn_text":"技术"}]}"""
        return Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `full round-trip JSON export and import preserves all 5 categories`() = runBlocking {
        val blockedApiCallCount = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> {
                        val b64 = encodeConfig(101L, "Tester")
                        MockResponse().setResponseCode(200)
                            .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$b64</script></body></html>""")
                    }
                    path.startsWith("/api/block-list/list") -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":[{"block_member_id":11,"block_member_name":"spam_bot"},{"block_member_id":22,"block_member_name":"aff_troll"}]}""",
                    )
                    path.startsWith("/api/block-list/add") -> {
                        blockedApiCallCount.add(request.body.readUtf8())
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    path.startsWith("/api/block-list/del") -> MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val source = TestNodeStack("source", tempDir, ioScope, server)
        source.sessionRepository.refreshSession().getOrThrow()

        // 1. Populate Settings
        source.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)
        source.settingsRepository.setPurePitchAmoled(true)
        source.settingsRepository.setUseDynamicColor(true)
        source.settingsRepository.setFontScale(1.15f)
        source.settingsRepository.setHomeCardScale(0.65f)
        source.settingsRepository.setPreferRandomCheckIn(true)
        source.settingsRepository.setPinnedCategorySlugs(setOf("tech", "dev", "review"))
        source.settingsRepository.setIncludeBlockedTopics(true)
        source.settingsRepository.setPreferRssFallback(true)
        source.settingsRepository.setNodeImageApiKey("ni_live_secret_123")
        source.settingsRepository.setSeekMailApiKey("sm_live_secret_456")
        source.settingsRepository.setAutoCheckUpdates(false)
        source.settingsRepository.setIncludePrereleaseUpdates(true)
        source.settingsRepository.setIgnoredUpdateTag("v2.0.5-beta1")
        source.settingsRepository.addRecentSearchQuery("DMIT LAX")
        source.settingsRepository.addRecentSearchQuery("CN2 GIA")

        // 2. Populate Radar
        source.radarRepository.updatePollingSettings {
            RadarPollingSettings(
                isEnabled = false,
                keywordAlertsEnabled = false,
                replyTrackingEnabled = true,
                inboxPushEnabled = false,
                foregroundIntervalMinutes = 5,
                backgroundIntervalMinutes = 15,
                wifiOnlyInBackground = true,
                quietHoursEnabled = true,
                quietStartMinuteOfDay = 22 * 60 + 30,
                quietEndMinuteOfDay = 6 * 60 + 45,
            )
        }
        val rule1 = source.radarRepository.addKeywordRule(
            keyword = "DMIT TYO",
            excludeWords = "收, 求购",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()
        val rule2 = source.radarRepository.addKeywordRule(
            keyword = "(?i)claw.*jp",
            categorySlug = "",
            matchMode = KeywordMatchMode.REGEX,
        ).getOrThrow()
        source.radarRepository.toggleKeywordRule(rule2.id, enabled = false)
        source.radarRepository.trackTopic(
            topicId = 88001L,
            title = "东京 Tier-1 实测",
            categorySlug = "review",
            categoryName = "测评",
            authorName = "net_guru",
            currentMaxFloor = 42,
            totalPages = 5,
        )

        // 3. Populate Content Filter
        source.contentFilterBridge.state = ContentFilterBackupDto(
            isEnabled = true,
            mutedCategorySlugs = setOf("promotion", "carpool"),
            rules = listOf(
                ContentFilterRuleBackupDto(
                    id = "cf_1",
                    pattern = "(^|\\s)(收|求购)",
                    matchMode = KeywordMatchMode.REGEX.name,
                    categorySlug = "trade",
                    isEnabled = true,
                    note = "屏蔽收鸡贴",
                    createdAtEpochMillis = 1_763_000_000_000L,
                ),
            ),
        )

        // 4. Populate Bookmarks (with offline detail snapshot & accountUid)
        val sampleTopic = TopicSummary(
            id = 5501L,
            title = "NodeSeek 离线快照收藏测试",
            categorySlug = "tech",
            categoryName = "技术",
            authorId = 77L,
            authorName = "author_a",
            viewCount = 1234,
            replyCount = 56,
            lastActivityIso = "2026-11-18T10:00:00Z",
            isBookmarked = true,
        )
        val sampleSnapshot = TopicDetailPage(
            topicId = 5501L,
            title = sampleTopic.title,
            categorySlug = "tech",
            categoryName = "技术",
            author = FloorAuthor(uid = 77L, username = "author_a"),
            viewCount = 1234,
            currentPage = 1,
            totalPages = 2,
            openingFloor = FloorComment(
                commentId = 1L,
                floorNumber = 0,
                author = FloorAuthor(uid = 77L, username = "author_a"),
                createdAtIso = "2026-11-18T10:00:00Z",
                markdownBody = "# 详细测试正文\n\n延迟稳定 135ms。",
            ),
            isOfflineSnapshot = true,
        )
        source.bookmarkDao.upsert(
            BookmarkEntity.fromSummary(
                accountUid = 101L,
                summary = sampleTopic,
                detailSnapshot = sampleSnapshot,
                isSyncedWithServer = true,
                savedAtEpochMillis = 1_763_111_000_000L,
            ),
        )

        // 5. Populate Drafts
        source.draftRepository.saveDraft(
            EditorDraft(
                draftKey = EditorDraft.NEW_TOPIC_KEY,
                accountUid = 101L,
                title = "草稿标题",
                markdownContent = "草稿 Markdown 内容",
                categorySlug = "dev",
                updatedAtMillis = 1_763_222_000_000L,
            ),
        )
        source.draftRepository.saveDraft(
            EditorDraft(
                draftKey = EditorDraft.replyKey(5501L),
                accountUid = 0L,
                title = "",
                markdownContent = "访客回复草稿",
                categorySlug = "daily",
                updatedAtMillis = 1_763_223_000_000L,
            ),
        )

        // Export JSON
        val exportedJson = source.backupManager.exportBackupJson(includeApiKeys = true).getOrThrow()
        assertTrue(exportedJson.contains("\"schemaVersion\": 1"))
        assertTrue(exportedJson.contains("\"requireBatteryNotLowInBackground\": false"))
        assertTrue(exportedJson.contains("ni_live_secret_123"))

        // Inspect summary before restoring
        val preview = source.backupManager.inspectBackup(exportedJson).getOrThrow()
        assertEquals(1, preview.schemaVersion)
        assertEquals("2.0.1-test", preview.appVersion)
        assertEquals(1_763_480_000_000L, preview.exportedAtEpochMillis)
        assertEquals(2, preview.radarKeywordRuleCount)
        assertEquals(1, preview.radarTrackedTopicCount)
        assertEquals(2, preview.blockedUserCount)
        assertEquals(1, preview.contentFilterRuleCount)
        assertEquals(2, preview.mutedCategoryCount)
        assertEquals(1, preview.bookmarkCount)
        assertEquals(2, preview.draftCount)
        assertEquals(2, preview.recentSearchQueryCount)
        assertTrue(preview.containsApiKeys)

        // Restore onto a clean target stack (signed in so block sync triggers)
        val target = TestNodeStack("target", tempDir, ioScope, server)
        target.sessionRepository.refreshSession().getOrThrow()
        blockedApiCallCount.clear()

        val restoreSummary = target.backupManager.restoreBackup(
            rawJson = exportedJson,
            strategy = RestoreStrategy.MERGE,
        ).getOrThrow()

        assertEquals(2, restoreSummary.radarKeywordRulesImported)
        assertEquals(1, restoreSummary.radarTrackedTopicsImported)
        assertEquals(2, restoreSummary.blockedUsersImported)
        assertEquals(0, restoreSummary.blockedUsersSyncedToServer)
        assertEquals(0, restoreSummary.blockedUsersServerSyncSkippedOrFailed)
        assertEquals(listOf("spam_bot", "aff_troll"), restoreSummary.pendingRemoteBlockUsers.map { it.username })
        assertEquals(BackupSyncTargetAccount(uid = 101L, username = "Tester"), restoreSummary.targetSyncAccount)
        assertEquals(1, restoreSummary.contentFilterRulesImported)
        assertEquals(1, restoreSummary.bookmarksImported)
        assertEquals(2, restoreSummary.draftsImported)
        // Local restore is strictly decoupled from remote server blocklist mutations!
        assertEquals(0, blockedApiCallCount.size)

        // Explicit remote sync verifies target account and syncs both blocked users cleanly
        val remoteSync = target.backupManager.syncBlockedUsersToServer(
            expectedAccountUid = 101L,
            expectedUsername = "Tester",
        ).getOrThrow()
        assertEquals(2, remoteSync.syncedCount)
        assertEquals(0, remoteSync.failedCount)
        assertEquals(2, blockedApiCallCount.size)
        assertNull(target.backupManager.pendingRemoteBlocklistSyncState.value)

        // Verify Settings restored
        val restoredSettings = withTimeout(3_000L) {
            target.settingsRepository.settings.first { it.nodeImageApiKey == "ni_live_secret_123" }
        }
        assertEquals(ThemeMode.OBSIDIAN_DARK, restoredSettings.themeMode)
        assertTrue(restoredSettings.purePitchAmoled)
        assertTrue(restoredSettings.useDynamicColor)
        assertEquals(1.15f, restoredSettings.fontScale, 0.001f)
        assertEquals(0.65f, restoredSettings.homeCardScale, 0.001f)
        assertTrue(restoredSettings.preferRandomCheckIn)
        assertEquals(setOf("tech", "dev", "review"), restoredSettings.pinnedCategorySlugs)
        assertTrue(restoredSettings.includeBlockedTopics)
        assertTrue(restoredSettings.preferRssFallback)
        assertEquals("sm_live_secret_456", restoredSettings.seekMailApiKey)
        assertFalse(restoredSettings.autoCheckUpdates)
        assertTrue(restoredSettings.includePrereleaseUpdates)
        assertEquals("v2.0.5-beta1", restoredSettings.ignoredUpdateTag)
        val restoredQueries = withTimeout(3_000L) {
            target.settingsRepository.recentSearchQueries.first { it.isNotEmpty() }
        }
        assertEquals(listOf("CN2 GIA", "DMIT LAX"), restoredQueries)

        // Verify Radar restored
        val restoredPolling = target.radarRepository.getPollingSettings()
        assertFalse(restoredPolling.isEnabled)
        assertFalse(restoredPolling.keywordAlertsEnabled)
        assertTrue(restoredPolling.replyTrackingEnabled)
        assertFalse(restoredPolling.inboxPushEnabled)
        assertEquals(5, restoredPolling.foregroundIntervalMinutes)
        assertEquals(15, restoredPolling.backgroundIntervalMinutes)
        assertTrue(restoredPolling.wifiOnlyInBackground)
        assertTrue(restoredPolling.quietHoursEnabled)
        assertEquals(22 * 60 + 30, restoredPolling.quietStartMinuteOfDay)
        assertEquals(6 * 60 + 45, restoredPolling.quietEndMinuteOfDay)

        val restoredRules = target.radarRepository.getKeywordRulesForBackup()
        assertEquals(2, restoredRules.size)
        val restoredContains = restoredRules.first { it.keyword == "DMIT TYO" }
        assertEquals(rule1.excludeWords, restoredContains.excludeWords)
        assertEquals("trade", restoredContains.categorySlug)
        assertTrue(restoredContains.isEnabled)
        val restoredRegex = restoredRules.first { it.matchMode == KeywordMatchMode.REGEX }
        assertFalse(restoredRegex.isEnabled)

        val restoredTracked = target.radarRepository.getTrackedTopicsForBackup()
        assertEquals(1, restoredTracked.size)
        assertEquals(88001L, restoredTracked.single().topicId)
        assertEquals(42, restoredTracked.single().lastKnownFloor)
        assertEquals(5, restoredTracked.single().lastKnownTotalPages)

        // Verify Blocklist (local cache + content filter) restored
        val cachedUsers = target.blockedUsersCache.getBlockedUsers()
        assertEquals(listOf("spam_bot", "aff_troll"), cachedUsers.map { it.username })
        assertEquals(source.contentFilterBridge.state, target.contentFilterBridge.state)

        // Verify Bookmarks (preserving accountUid, offlineDetailSnapshot, savedAt, isSyncedWithServer)
        val restoredBookmarks = target.bookmarkRepository.getAllBookmarksForBackup()
        assertEquals(1, restoredBookmarks.size)
        val b = restoredBookmarks.single()
        assertEquals(101L, b.accountUid)
        assertEquals(5501L, b.topicId)
        assertTrue(b.isSyncedWithServer)
        assertEquals(1_763_111_000_000L, b.savedAtEpochMillis)
        assertEquals("# 详细测试正文\n\n延迟稳定 135ms。", b.offlineDetailSnapshot?.openingFloor?.markdownBody)

        // Verify Drafts
        val restoredDrafts = target.draftRepository.getAllDrafts()
        assertEquals(2, restoredDrafts.size)
        assertEquals("草稿标题", restoredDrafts.first { it.accountUid == 101L }.title)
        assertEquals("访客回复草稿", restoredDrafts.first { it.accountUid == 0L }.markdownContent)
    }

    @Test
    fun `includeApiKeys false blanks keys in JSON and MERGE preserves non-blank local keys while OVERWRITE clears them`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500)
        }

        val source = TestNodeStack("keys_src", tempDir, ioScope, server)
        source.settingsRepository.setNodeImageApiKey("ni_source_key")
        source.settingsRepository.setSeekMailApiKey("sm_source_key")
        source.settingsRepository.setThemeMode(ThemeMode.PAPER_LIGHT)

        val redactedJson = source.backupManager.exportBackupJson(includeApiKeys = false).getOrThrow()
        assertFalse(redactedJson.contains("ni_source_key"))
        assertFalse(redactedJson.contains("sm_source_key"))

        // Default export methods must exclude plaintext API keys unless explicitly opted in
        val defaultJson = source.backupManager.exportBackupJson().getOrThrow()
        assertFalse(defaultJson.contains("ni_source_key"))
        assertFalse(defaultJson.contains("sm_source_key"))
        val defaultBundle = source.backupManager.exportBundle().getOrThrow()
        assertEquals("", defaultBundle.settings.nodeImageApiKey)
        assertEquals("", defaultBundle.settings.seekMailApiKey)
        val defaultPayload = source.backupManager.exportBackupPayload().getOrThrow()
        assertFalse(defaultPayload.rawJson.contains("ni_source_key"))
        assertFalse(defaultPayload.rawJson.contains("sm_source_key"))

        val preview = source.backupManager.inspectBackup(redactedJson).getOrThrow()
        assertFalse(preview.containsApiKeys)

        val target = TestNodeStack("keys_dst", tempDir, ioScope, server)
        target.settingsRepository.setNodeImageApiKey("ni_local_keep")
        target.settingsRepository.setSeekMailApiKey("sm_local_keep")
        target.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)

        // MERGE must update settings (themeMode -> PAPER_LIGHT) WITHOUT wiping existing non-blank local API keys!
        target.backupManager.restoreBackup(redactedJson, RestoreStrategy.MERGE).getOrThrow()
        val afterMerge = withTimeout(3_000L) {
            target.settingsRepository.settings.first { it.themeMode == ThemeMode.PAPER_LIGHT }
        }
        assertEquals("ni_local_keep", afterMerge.nodeImageApiKey)
        assertEquals("sm_local_keep", afterMerge.seekMailApiKey)

        // OVERWRITE replaces settings with backup (so blank keys in backup clear local keys)
        target.backupManager.restoreBackup(redactedJson, RestoreStrategy.OVERWRITE).getOrThrow()
        val afterOverwrite = withTimeout(3_000L) {
            target.settingsRepository.settings.first { it.nodeImageApiKey.isEmpty() }
        }
        assertEquals("", afterOverwrite.nodeImageApiKey)
        assertEquals("", afterOverwrite.seekMailApiKey)
    }

    @Test
    fun `MERGE dedupes radar rules and preserves existing local data while OVERWRITE replaces everything`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500)
        }

        val source = TestNodeStack("merge_src", tempDir, ioScope, server)
        source.settingsRepository.addRecentSearchQuery("sharedQuery")
        source.settingsRepository.addRecentSearchQuery("backupOnlyQuery")
        source.radarRepository.addKeywordRule("DMIT", categorySlug = "TRADE", matchMode = KeywordMatchMode.CONTAINS)
        source.radarRepository.addKeywordRule("racknerd", categorySlug = "", matchMode = KeywordMatchMode.REGEX)
        source.radarRepository.trackTopic(9001L, "Tracked Shared", currentMaxFloor = 40, totalPages = 3)
        source.radarRepository.trackTopic(9003L, "Tracked Backup Only", currentMaxFloor = 12, totalPages = 1)
        source.blockedUsersCache.replaceAll(listOf(FloorAuthor(uid = 2L, username = "bob")))
        source.contentFilterBridge.state = ContentFilterBackupDto(
            isEnabled = true,
            mutedCategorySlugs = setOf("promotion"),
            rules = listOf(ContentFilterRuleBackupDto(id = "cf_backup", pattern = "AFF")),
        )
        source.bookmarkDao.upsert(
            BookmarkEntity.fromSummary(
                accountUid = 0L,
                summary = TopicSummary(id = 100L, title = "Backup 100", categorySlug = "tech", categoryName = "技术", authorName = "a"),
                detailSnapshot = null,
                isSyncedWithServer = false,
                savedAtEpochMillis = 1000L,
            ),
        )
        source.bookmarkDao.upsert(
            BookmarkEntity.fromSummary(
                accountUid = 0L,
                summary = TopicSummary(id = 300L, title = "Backup 300", categorySlug = "tech", categoryName = "技术", authorName = "c"),
                savedAtEpochMillis = 2000L,
            ),
        )
        source.draftRepository.saveDraft(
            EditorDraft(
                draftKey = EditorDraft.NEW_TOPIC_KEY,
                accountUid = 0L,
                title = "Older Backup Title",
                markdownContent = "older",
                updatedAtMillis = 1000L,
            ),
        )
        source.draftRepository.saveDraft(
            EditorDraft(
                draftKey = EditorDraft.replyKey(999L),
                accountUid = 0L,
                markdownContent = "backup reply",
                updatedAtMillis = 2000L,
            ),
        )

        val backupJson = source.backupManager.exportBackupJson().getOrThrow()

        val target = TestNodeStack("merge_dst", tempDir, ioScope, server)
        target.settingsRepository.addRecentSearchQuery("localOnlyQuery")
        target.settingsRepository.addRecentSearchQuery("SharedQuery")
        target.radarRepository.addKeywordRule("dmit", categorySlug = "trade", matchMode = KeywordMatchMode.CONTAINS)
        target.radarRepository.addKeywordRule("oracle", categorySlug = "info", matchMode = KeywordMatchMode.CONTAINS)
        target.radarRepository.trackTopic(9001L, "Tracked Shared Local", currentMaxFloor = 80, totalPages = 6)
        target.radarRepository.trackTopic(9002L, "Tracked Local Only", currentMaxFloor = 25, totalPages = 2)
        target.blockedUsersCache.replaceAll(listOf(FloorAuthor(uid = 1L, username = "alice")))
        target.contentFilterBridge.state = ContentFilterBackupDto(
            isEnabled = true,
            mutedCategorySlugs = setOf("meaningless"),
            rules = listOf(ContentFilterRuleBackupDto(id = "cf_local", pattern = "水贴")),
        )
        target.bookmarkDao.upsert(
            BookmarkEntity.fromSummary(
                accountUid = 0L,
                summary = TopicSummary(id = 100L, title = "Local 100", categorySlug = "tech", categoryName = "技术", authorName = "a"),
                detailSnapshot = TopicDetailPage(
                    topicId = 100L,
                    title = "Local 100",
                    categorySlug = "tech",
                    categoryName = "技术",
                    author = FloorAuthor(uid = 10L, username = "a"),
                    openingFloor = FloorComment(
                        commentId = 1L,
                        floorNumber = 0,
                        author = FloorAuthor(uid = 10L, username = "a"),
                        createdAtIso = "2026-11-18T00:00:00Z",
                        markdownBody = "本地离线快照必须保留",
                    ),
                ),
                isSyncedWithServer = true,
                savedAtEpochMillis = 5000L,
            ),
        )
        target.bookmarkDao.upsert(
            BookmarkEntity.fromSummary(
                accountUid = 0L,
                summary = TopicSummary(id = 200L, title = "Local 200", categorySlug = "daily", categoryName = "日常", authorName = "b"),
                savedAtEpochMillis = 3000L,
            ),
        )
        target.draftRepository.saveDraft(
            EditorDraft(
                draftKey = EditorDraft.NEW_TOPIC_KEY,
                accountUid = 0L,
                title = "Newer Local Title",
                markdownContent = "newer local",
                updatedAtMillis = 9000L,
            ),
        )
        target.draftRepository.saveDraft(
            EditorDraft(
                draftKey = EditorDraft.replyKey(888L),
                accountUid = 0L,
                markdownContent = "local reply",
                updatedAtMillis = 3000L,
            ),
        )

        // 1. Test MERGE
        val mergeSummary = target.backupManager.restoreBackup(backupJson, RestoreStrategy.MERGE).getOrThrow()
        assertEquals(1, mergeSummary.radarKeywordRulesImported) // DMIT/trade was deduped; only racknerd inserted!

        val mergedRules = target.radarRepository.getKeywordRulesForBackup()
        assertEquals(setOf("dmit", "oracle", "racknerd"), mergedRules.map { it.keyword.lowercase() }.toSet())

        val mergedTopics = target.radarRepository.getTrackedTopicsForBackup()
        assertEquals(setOf(9001L, 9002L, 9003L), mergedTopics.map { it.topicId }.toSet())
        assertEquals(80, mergedTopics.first { it.topicId == 9001L }.lastKnownFloor)

        assertEquals(setOf("alice", "bob"), target.blockedUsersCache.getBlockedUsers().map { it.username }.toSet())
        assertEquals(setOf("meaningless", "promotion"), target.contentFilterBridge.state.mutedCategorySlugs)
        assertEquals(setOf("cf_local", "cf_backup"), target.contentFilterBridge.state.rules.map { it.id }.toSet())

        val mergedBookmarks = target.bookmarkRepository.getAllBookmarksForBackup()
        assertEquals(setOf(100L, 200L, 300L), mergedBookmarks.map { it.topicId }.toSet())
        assertEquals(
            "本地离线快照必须保留",
            mergedBookmarks.first { it.topicId == 100L }.offlineDetailSnapshot?.openingFloor?.markdownBody,
        )

        val mergedDrafts = target.draftRepository.getAllDrafts()
        assertEquals(setOf(EditorDraft.NEW_TOPIC_KEY, EditorDraft.replyKey(888L), EditorDraft.replyKey(999L)), mergedDrafts.map { it.draftKey }.toSet())
        assertEquals("Newer Local Title", mergedDrafts.first { it.draftKey == EditorDraft.NEW_TOPIC_KEY }.title)

        val mergedQueries = withTimeout(3_000L) {
            target.settingsRepository.recentSearchQueries.first { it.size == 3 }
        }
        assertEquals(setOf("backupOnlyQuery", "sharedQuery", "localOnlyQuery"), mergedQueries.toSet())

        // 2. Test OVERWRITE
        target.backupManager.restoreBackup(backupJson, RestoreStrategy.OVERWRITE).getOrThrow()
        val overwrittenRules = target.radarRepository.getKeywordRulesForBackup()
        assertEquals(setOf("DMIT", "racknerd"), overwrittenRules.map { it.keyword }.toSet())

        val overwrittenTopics = target.radarRepository.getTrackedTopicsForBackup()
        assertEquals(setOf(9001L, 9003L), overwrittenTopics.map { it.topicId }.toSet())
        assertEquals(40, overwrittenTopics.first { it.topicId == 9001L }.lastKnownFloor)

        assertEquals(listOf("bob"), target.blockedUsersCache.getBlockedUsers().map { it.username })
        assertEquals(setOf("promotion"), target.contentFilterBridge.state.mutedCategorySlugs)
        assertEquals(listOf("cf_backup"), target.contentFilterBridge.state.rules.map { it.id })

        val overwrittenBookmarks = target.bookmarkRepository.getAllBookmarksForBackup()
        assertEquals(setOf(100L, 300L), overwrittenBookmarks.map { it.topicId }.toSet())

        val overwrittenDrafts = target.draftRepository.getAllDrafts()
        assertEquals(setOf(EditorDraft.NEW_TOPIC_KEY, EditorDraft.replyKey(999L)), overwrittenDrafts.map { it.draftKey }.toSet())
        assertEquals("Older Backup Title", overwrittenDrafts.first { it.draftKey == EditorDraft.NEW_TOPIC_KEY }.title)

        val overwrittenQueries = withTimeout(3_000L) {
            target.settingsRepository.recentSearchQueries.first { it.size == 2 }
        }
        assertEquals(listOf("backupOnlyQuery", "sharedQuery"), overwrittenQueries)
    }

    @Test
    fun `offline blocklist export uses DataStore cache and restore handles network or auth failure gracefully`() = runBlocking {
        var failNetwork = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failNetwork) {
                    return MockResponse().setResponseCode(503).setBody("""{"success":false,"message":"offline"}""")
                }
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> {
                        val b64 = encodeConfig(101L, "Tester")
                        MockResponse().setResponseCode(200)
                            .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$b64</script></body></html>""")
                    }
                    path.startsWith("/api/block-list/list") -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":[{"block_member_id":55,"block_member_name":"cached_user"},{"block_member_id":66,"block_member_name":"to_unblock"}]}""",
                    )
                    path.startsWith("/api/block-list/add") -> MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    path.startsWith("/api/block-list/del") -> MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val stack = TestNodeStack("offline_test", tempDir, ioScope, server)
        stack.sessionRepository.refreshSession().getOrThrow()

        // Online fetch populates local DataStore cache; unblock & blockByName keep cache synced
        assertEquals(2, stack.userRepository.getBlockedUsers().getOrThrow().size)
        stack.userRepository.unblockUserById(66L).getOrThrow()
        assertEquals(listOf("cached_user"), stack.blockedUsersCache.getBlockedUsers().map { it.username })
        stack.userRepository.blockUserByName("offline_added_user").getOrThrow()

        // Go offline -> export must fall back to local DataStore cache and include both users!
        failNetwork = true
        val offlineExport = stack.backupManager.exportBackupJson().getOrThrow()
        val preview = stack.backupManager.inspectBackup(offlineExport).getOrThrow()
        assertEquals(2, preview.blockedUserCount)

        // Restore onto another signed-in stack while network fails -> must succeed & populate local cache!
        val restoreTarget = TestNodeStack("offline_target", tempDir, ioScope, server)
        failNetwork = false
        restoreTarget.sessionRepository.refreshSession().getOrThrow()
        failNetwork = true

        val restoreOutcome = restoreTarget.backupManager.restoreBackup(offlineExport, RestoreStrategy.MERGE).getOrThrow()
        assertEquals(2, restoreOutcome.blockedUsersImported)
        assertEquals(0, restoreOutcome.blockedUsersSyncedToServer)
        assertEquals(2, restoreOutcome.pendingRemoteBlockUsers.size)
        assertEquals(BackupSyncTargetAccount(uid = 101L, username = "Tester"), restoreOutcome.targetSyncAccount)
        assertEquals(
            setOf("cached_user", "offline_added_user"),
            restoreTarget.blockedUsersCache.getBlockedUsers().map { it.username }.toSet(),
        )

        // Explicit remote sync while offline reports failures cleanly and retains failed users for retry
        val offlineSyncResult = restoreTarget.backupManager.syncBlockedUsersToServer(
            expectedAccountUid = 101L,
            expectedUsername = "Tester",
        ).getOrThrow()
        assertEquals(0, offlineSyncResult.syncedCount)
        assertEquals(2, offlineSyncResult.failedCount)
        assertEquals(2, restoreTarget.backupManager.pendingRemoteBlocklistSyncState.value?.users?.size)
    }

    @Test
    fun `malformed JSON, missing fields, and unsupported schema versions fail cleanly without mutating state`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503)
        }
        val stack = TestNodeStack("error_test", tempDir, ioScope, server)
        stack.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)

        assertTrue(stack.backupManager.inspectBackup("").isFailure)
        assertTrue(stack.backupManager.inspectBackup("{bad json").isFailure)
        assertTrue(stack.backupManager.inspectBackup("""{"hello":"world"}""").isFailure)

        val validJson = stack.backupManager.exportBackupJson().getOrThrow()
        val v99Json = validJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val v0Json = validJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 0")

        val v99Preview = stack.backupManager.inspectBackup(v99Json)
        assertTrue(v99Preview.isFailure)
        assertTrue(v99Preview.exceptionOrNull()?.message?.contains("99") == true)

        assertTrue(stack.backupManager.inspectBackup(v0Json).isFailure)

        val restoreFailure = stack.backupManager.restoreBackup(v99Json, RestoreStrategy.OVERWRITE)
        assertTrue(restoreFailure.isFailure)

        // Verify existing settings were NOT wiped or modified
        val unchanged = withTimeout(3_000L) {
            stack.settingsRepository.settings.first { it.themeMode == ThemeMode.OBSIDIAN_DARK }
        }
        assertEquals(ThemeMode.OBSIDIAN_DARK, unchanged.themeMode)

        // Verify unknown keys + requireBatteryNotLowInBackground = true override are accepted cleanly
        val forwardCompatJson = validJson
            .replace("\"requireBatteryNotLowInBackground\": false", "\"requireBatteryNotLowInBackground\": true")
            .replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"futureTopLevelField\": 123,")
        val decoded = BackupManagerImpl.backupJson.decodeFromString<NodePulseBackupBundle>(forwardCompatJson)
        assertTrue(decoded.radar.pollingSettings.requireBatteryNotLowInBackground)
        assertTrue(stack.backupManager.inspectBackup(forwardCompatJson).isSuccess)
    }

    @Test
    fun `BackupFileNaming formats suggested filename consistently`() {
        val millis = 1_763_480_000_000L // 2025-11-18T15:33:20Z
        val fileName = BackupFileNaming.createDefaultFileName(
            epochMillis = millis,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals("anode-backup-20251118-1533.json", fileName)
    }

    @Test
    fun `BackupManager round-trips real ContentFilterRepositoryImpl rules and muted categories across MERGE and OVERWRITE`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503)
        }

        val sourceFilterStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(tempDir, "cf_source.preferences_pb") },
        )
        val targetFilterStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(tempDir, "cf_target.preferences_pb") },
        )
        val sourceFilterRepo = ContentFilterRepositoryImpl(sourceFilterStore, ioScope)
        val targetFilterRepo = ContentFilterRepositoryImpl(targetFilterStore, ioScope)

        sourceFilterRepo.replaceConfig(
            ContentFilterConfig(
                isEnabled = true,
                mutedCategorySlugs = setOf("promotion", "carpool"),
                rules = listOf(
                    ContentBlockRule(
                        id = "cf_regex_buy",
                        pattern = """(^|\s)(收|求购)""",
                        matchMode = KeywordMatchMode.REGEX,
                        categorySlug = "trade",
                        isEnabled = true,
                        note = "屏蔽求购",
                        createdAtEpochMillis = 1_763_000_100_000L,
                    ),
                    ContentBlockRule(
                        id = "cf_plain_aff",
                        pattern = "AFF",
                        matchMode = KeywordMatchMode.CONTAINS,
                        categorySlug = "",
                        isEnabled = false,
                        note = "推广词",
                        createdAtEpochMillis = 1_763_000_200_000L,
                    ),
                ),
            ),
        )

        targetFilterRepo.replaceConfig(
            ContentFilterConfig(
                isEnabled = true,
                mutedCategorySlugs = setOf("meaningless"),
                rules = listOf(
                    ContentBlockRule(
                        id = "cf_local_only",
                        pattern = "每日一水",
                        matchMode = KeywordMatchMode.CONTAINS,
                        categorySlug = "daily",
                        isEnabled = true,
                        note = "本地规则",
                        createdAtEpochMillis = 1_763_000_050_000L,
                    ),
                ),
            ),
        )

        val sourceStack = TestNodeStack("cf_src", tempDir, ioScope, server)
        val sourceManager = BackupManagerImpl(
            settingsRepository = sourceStack.settingsRepository,
            radarRepository = sourceStack.radarRepository,
            bookmarkAndHistoryRepository = sourceStack.bookmarkRepository,
            draftRepository = sourceStack.draftRepository,
            userRepository = sourceStack.userRepository,
            sessionRepository = sourceStack.sessionRepository,
            blockedUsersCache = sourceStack.blockedUsersCache,
            contentFilterBackupBridge = ContentFilterRepositoryBackupBridge(sourceFilterRepo),
            appVersionProvider = { "2.0.1-test" },
            clockMillis = { 1_763_480_000_000L },
        )

        val exportedJson = sourceManager.exportBackupJson().getOrThrow()
        val preview = sourceManager.inspectBackup(exportedJson).getOrThrow()
        assertEquals(2, preview.contentFilterRuleCount)
        assertEquals(2, preview.mutedCategoryCount)

        val targetStack = TestNodeStack("cf_dst", tempDir, ioScope, server)
        val targetManager = BackupManagerImpl(
            settingsRepository = targetStack.settingsRepository,
            radarRepository = targetStack.radarRepository,
            bookmarkAndHistoryRepository = targetStack.bookmarkRepository,
            draftRepository = targetStack.draftRepository,
            userRepository = targetStack.userRepository,
            sessionRepository = targetStack.sessionRepository,
            blockedUsersCache = targetStack.blockedUsersCache,
            contentFilterBackupBridge = ContentFilterRepositoryBackupBridge(targetFilterRepo),
            appVersionProvider = { "2.0.1-test" },
            clockMillis = { 1_763_480_000_000L },
        )

        // 1. MERGE retains existing local rule & muted board while adding backup rules & muted boards
        val mergeSummary = targetManager.restoreBackup(exportedJson, RestoreStrategy.MERGE).getOrThrow()
        assertEquals(2, mergeSummary.contentFilterRulesImported)
        assertEquals(2, mergeSummary.mutedCategoriesCount)

        val mergedConfig = targetFilterRepo.getConfigSnapshot()
        assertTrue(mergedConfig.isEnabled)
        assertEquals(setOf("meaningless", "promotion", "carpool"), mergedConfig.mutedCategorySlugs)
        assertEquals(3, mergedConfig.rules.size)
        val mergedRegex = mergedConfig.rules.first { it.id == "cf_regex_buy" }
        assertEquals(KeywordMatchMode.REGEX, mergedRegex.matchMode)
        assertEquals("trade", mergedRegex.categorySlug)
        assertEquals("屏蔽求购", mergedRegex.note)
        assertEquals(1_763_000_100_000L, mergedRegex.createdAtEpochMillis)
        val mergedDisabled = mergedConfig.rules.first { it.id == "cf_plain_aff" }
        assertFalse(mergedDisabled.isEnabled)

        // 2. OVERWRITE replaces local content filter state completely (including disabled state)
        sourceFilterRepo.setEnabled(false)
        val disabledExportJson = sourceManager.exportBackupJson().getOrThrow()
        targetManager.restoreBackup(disabledExportJson, RestoreStrategy.OVERWRITE).getOrThrow()

        val overwrittenConfig = targetFilterRepo.getConfigSnapshot()
        assertFalse(overwrittenConfig.isEnabled)
        assertEquals(setOf("promotion", "carpool"), overwrittenConfig.mutedCategorySlugs)
        assertEquals(setOf("cf_regex_buy", "cf_plain_aff"), overwrittenConfig.rules.map { it.id }.toSet())

        // Verify persistence survives fresh repository instantiation against same DataStore
        val reloadedRepo = ContentFilterRepositoryImpl(targetFilterStore, ioScope)
        val reloadedConfig = reloadedRepo.getConfigSnapshot()
        assertEquals(overwrittenConfig, reloadedConfig)
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `BackupManager preserves requireBatteryNotLowInBackground and triggers RadarWorkScheduler sync on restore`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503)
        }

        class RecordingGateway : RadarWorkManagerGateway {
            val enqueued = CopyOnWriteArrayList<Pair<ExistingPeriodicWorkPolicy, PeriodicWorkRequest>>()
            val cancelled = CopyOnWriteArrayList<String>()

            override fun enqueueUniquePeriodicWork(
                uniqueWorkName: String,
                existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
                request: PeriodicWorkRequest,
            ) {
                enqueued.add(existingPeriodicWorkPolicy to request)
            }

            override fun cancelUniqueWork(uniqueWorkName: String) {
                cancelled.add(uniqueWorkName)
            }
        }

        val sourceStack = TestNodeStack("wm_src", tempDir, ioScope, server)
        sourceStack.radarRepository.updatePollingSettings {
            RadarPollingSettings(
                isEnabled = true,
                keywordAlertsEnabled = true,
                replyTrackingEnabled = true,
                inboxPushEnabled = true,
                foregroundIntervalMinutes = 3,
                backgroundIntervalMinutes = 60,
                wifiOnlyInBackground = true,
                requireBatteryNotLowInBackground = false,
            )
        }
        val exportedEnabledJson = sourceStack.backupManager.exportBackupJson().getOrThrow()
        assertTrue(exportedEnabledJson.contains("\"requireBatteryNotLowInBackground\": false"))

        val targetStack = TestNodeStack("wm_dst", tempDir, ioScope, server)
        val gateway = RecordingGateway()
        val scheduler = RadarWorkScheduler(gateway)
        scheduler.observePollingSettings(
            scope = ioScope,
            pollingSettingsFlow = targetStack.radarRepository.pollingSettingsFlow,
        )
        val targetManager = BackupManagerImpl(
            settingsRepository = targetStack.settingsRepository,
            radarRepository = targetStack.radarRepository,
            bookmarkAndHistoryRepository = targetStack.bookmarkRepository,
            draftRepository = targetStack.draftRepository,
            userRepository = targetStack.userRepository,
            sessionRepository = targetStack.sessionRepository,
            blockedUsersCache = targetStack.blockedUsersCache,
            contentFilterBackupBridge = ContentFilterRepositoryBackupBridge(
                ContentFilterRepositoryImpl(
                    PreferenceDataStoreFactory.create(
                        scope = ioScope,
                        produceFile = { File(tempDir, "wm_dst_cf.preferences_pb") },
                    ),
                    ioScope,
                ),
            ),
            onRadarSettingsRestored = { restored -> scheduler.sync(restored) },
        )

        targetManager.restoreBackup(exportedEnabledJson, RestoreStrategy.OVERWRITE).getOrThrow()

        val restoredPolling = targetStack.radarRepository.getPollingSettings()
        assertEquals(60, restoredPolling.backgroundIntervalMinutes)
        assertTrue(restoredPolling.wifiOnlyInBackground)
        assertFalse(restoredPolling.requireBatteryNotLowInBackground)

        assertTrue(gateway.enqueued.isNotEmpty())
        val (policy, request) = gateway.enqueued.last()
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, policy)
        assertEquals(60L * 60_000L, request.workSpec.intervalDuration)
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertFalse(request.workSpec.constraints.requiresBatteryNotLow())

        // Restoring disabled radar settings (even with legacy <15m interval) coerces interval >= 15 and cancels WorkManager
        val disabledLegacyJson = exportedEnabledJson
            .replace("\"isEnabled\": true", "\"isEnabled\": false")
            .replace("\"backgroundIntervalMinutes\": 60", "\"backgroundIntervalMinutes\": 5")
            .replace("\"requireBatteryNotLowInBackground\": false", "\"requireBatteryNotLowInBackground\": true")
        targetManager.restoreBackup(disabledLegacyJson, RestoreStrategy.OVERWRITE).getOrThrow()

        val disabledPolling = targetStack.radarRepository.getPollingSettings()
        assertFalse(disabledPolling.isEnabled)
        assertEquals(15, disabledPolling.backgroundIntervalMinutes)
        assertTrue(disabledPolling.requireBatteryNotLowInBackground)
        assertTrue(gateway.cancelled.contains(RadarWorkScheduler.UNIQUE_WORK_NAME))

        scheduler.stopObserving()
    }

    @Test
    fun `2 radar rules differing only in exclude keywords export 2, preview 2, and restore 2 across OVERWRITE and MERGE`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503)
        }

        val source = TestNodeStack("excl_src", tempDir, ioScope, server)
        source.radarRepository.addKeywordRule(
            keyword = "DMIT",
            excludeWords = "收",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()
        source.radarRepository.addKeywordRule(
            keyword = "DMIT",
            excludeWords = "求购",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()

        // 1. Export -> 2 rules
        val payload = source.backupManager.exportBackupPayload().getOrThrow()
        assertEquals(2, payload.bundle.radar.keywordRules.size)

        // 2. Preview -> 2 rules
        val preview = source.backupManager.inspectBackup(payload.rawJson).getOrThrow()
        assertEquals(2, preview.radarKeywordRuleCount)

        // 3. OVERWRITE restore -> 2 rules restored
        val overwriteTarget = TestNodeStack("excl_overwrite_dst", tempDir, ioScope, server)
        overwriteTarget.radarRepository.addKeywordRule(
            keyword = "RackNerd",
            excludeWords = "",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()

        val overwriteSummary = overwriteTarget.backupManager
            .restoreBackup(payload.rawJson, RestoreStrategy.OVERWRITE)
            .getOrThrow()
        assertEquals(2, overwriteSummary.radarKeywordRulesImported)
        val overwrittenRules = overwriteTarget.radarRepository.getKeywordRulesForBackup()
        assertEquals(2, overwrittenRules.size)
        assertEquals(setOf("收", "求购"), overwrittenRules.map { it.excludeWords }.toSet())

        // 4. MERGE restore onto a target with DMIT (empty excludeWords) + one matching rule -> imports only the distinct rule
        val mergeTarget = TestNodeStack("excl_merge_dst", tempDir, ioScope, server)
        mergeTarget.radarRepository.addKeywordRule(
            keyword = "DMIT",
            excludeWords = "",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()
        mergeTarget.radarRepository.addKeywordRule(
            keyword = "dmit",
            excludeWords = "收",
            categorySlug = "TRADE",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()

        val mergeSummary = mergeTarget.backupManager
            .restoreBackup(payload.rawJson, RestoreStrategy.MERGE)
            .getOrThrow()
        assertEquals(1, mergeSummary.radarKeywordRulesImported)
        val mergedRules = mergeTarget.radarRepository.getKeywordRulesForBackup()
        assertEquals(3, mergedRules.size)
        assertEquals(setOf("", "收", "求购"), mergedRules.map { it.excludeWords }.toSet())
    }

    @Test
    fun `late-stage failure in restoreBookmarks, restoreDrafts, or cancellation rolls back all local stores and makes zero server calls`() = runBlocking {
        val remoteBlockCalls = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> {
                        val b64 = encodeConfig(101L, "Tester")
                        MockResponse().setResponseCode(200)
                            .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$b64</script></body></html>""")
                    }
                    path.startsWith("/api/block-list/list") -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":[{"block_member_id":88,"block_member_name":"incoming_block_1"},{"block_member_id":89,"block_member_name":"incoming_block_2"}]}""",
                    )
                    path.startsWith("/api/block-list/add") -> {
                        remoteBlockCalls.add(request.body.readUtf8())
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Build incoming backup payload that overwrites all 5 categories
        val incomingStack = TestNodeStack("rollback_src", tempDir, ioScope, server)
        incomingStack.sessionRepository.refreshSession().getOrThrow()
        incomingStack.settingsRepository.setThemeMode(ThemeMode.PAPER_LIGHT)
        incomingStack.settingsRepository.setPurePitchAmoled(false)
        incomingStack.settingsRepository.setFontScale(1.30f)
        incomingStack.settingsRepository.setNodeImageApiKey("ni_incoming_overwrite")
        incomingStack.settingsRepository.addRecentSearchQuery("incoming_query")
        incomingStack.radarRepository.updatePollingSettings {
            RadarPollingSettings(
                isEnabled = false,
                foregroundIntervalMinutes = 15,
                backgroundIntervalMinutes = 90,
                wifiOnlyInBackground = true,
            )
        }
        incomingStack.radarRepository.addKeywordRule("IncomingKeyword", excludeWords = "AFF", categorySlug = "trade").getOrThrow()
        incomingStack.radarRepository.trackTopic(66001L, "Incoming Tracked Topic", currentMaxFloor = 99, totalPages = 10)
        incomingStack.contentFilterBridge.state = ContentFilterBackupDto(
            isEnabled = false,
            mutedCategorySlugs = setOf("carpool"),
            rules = listOf(ContentFilterRuleBackupDto(id = "cf_incoming", pattern = "IncomingFilter")),
        )
        incomingStack.bookmarkDao.upsert(
            BookmarkEntity.fromSummary(
                accountUid = 101L,
                summary = TopicSummary(id = 7701L, title = "Incoming Bookmark", categorySlug = "tech", categoryName = "技术", authorName = "inc"),
                savedAtEpochMillis = 8_000L,
            ),
        )
        incomingStack.draftRepository.saveDraft(
            EditorDraft(
                draftKey = EditorDraft.NEW_TOPIC_KEY,
                accountUid = 101L,
                title = "Incoming Draft",
                markdownContent = "incoming body",
                categorySlug = "tech",
                updatedAtMillis = 9_000L,
            ),
        )
        val incomingJson = incomingStack.backupManager.exportBackupJson(includeApiKeys = true).getOrThrow()

        // Populate target stack with distinct pre-restore state
        val target = TestNodeStack("rollback_dst", tempDir, ioScope, server)
        target.sessionRepository.refreshSession().getOrThrow()
        target.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)
        target.settingsRepository.setPurePitchAmoled(true)
        target.settingsRepository.setFontScale(0.95f)
        target.settingsRepository.setNodeImageApiKey("ni_original_keep")
        target.settingsRepository.setSeekMailApiKey("sm_original_keep")
        target.settingsRepository.addRecentSearchQuery("original_query")

        target.radarRepository.updatePollingSettings {
            RadarPollingSettings(
                isEnabled = true,
                foregroundIntervalMinutes = 3,
                backgroundIntervalMinutes = 30,
                wifiOnlyInBackground = false,
            )
        }
        val originalRule = target.radarRepository
            .addKeywordRule("OriginalRule", excludeWords = "求购", categorySlug = "review")
            .getOrThrow()
        target.radarRepository.toggleKeywordRule(originalRule.id, enabled = false)
        target.radarRepository.trackTopic(12345L, "Original Tracked", currentMaxFloor = 12, totalPages = 2)
        target.blockedUsersCache.replaceAll(listOf(FloorAuthor(uid = 5L, username = "original_blocked_user")))

        val targetFilterStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(tempDir, "rollback_dst_cf.preferences_pb") },
        )
        val targetFilterRepo = ContentFilterRepositoryImpl(targetFilterStore, ioScope)
        targetFilterRepo.replaceConfig(
            ContentFilterConfig(
                isEnabled = true,
                mutedCategorySlugs = setOf("meaningless"),
                rules = listOf(
                    ContentBlockRule(
                        id = "cf_original",
                        pattern = "水一贴",
                        matchMode = KeywordMatchMode.CONTAINS,
                        categorySlug = "daily",
                        isEnabled = true,
                        note = "原始规则",
                        createdAtEpochMillis = 1_500L,
                    ),
                ),
            ),
        )

        target.bookmarkDao.upsert(
            BookmarkEntity.fromSummary(
                accountUid = 101L,
                summary = TopicSummary(id = 1111L, title = "Original Bookmark", categorySlug = "review", categoryName = "测评", authorName = "orig"),
                savedAtEpochMillis = 2_000L,
            ),
        )
        target.draftRepository.saveDraft(
            EditorDraft(
                draftKey = EditorDraft.replyKey(1111L),
                accountUid = 101L,
                title = "",
                markdownContent = "Original reply draft",
                categorySlug = "review",
                updatedAtMillis = 3_000L,
            ),
        )

        val expectedSettings = target.settingsRepository.exportSettingsForBackup(includeApiKeys = true)
        val expectedPolling = target.radarRepository.getPollingSettings()
        val expectedRules = target.radarRepository.getKeywordRulesForBackup()
        val expectedTopics = target.radarRepository.getTrackedTopicsForBackup()
        val expectedContentFilter = targetFilterRepo.getConfigSnapshot()
        val expectedBlockedUsers = target.blockedUsersCache.getBlockedUsers()
        val expectedBookmarks = target.bookmarkRepository.getAllBookmarksForBackup()
        val expectedDrafts = target.draftRepository.getAllDrafts()

        var failBookmarksWith: Throwable? = null
        var failDraftsWith: Throwable? = null
        val radarRestoredCallbacks = CopyOnWriteArrayList<RadarPollingSettings>()
        val txCount = AtomicInteger(0)
        val journalFile = File(tempDir, "rollback_journal.json")

        val guardedBookmarkRepo = object : BookmarkAndHistoryRepository by target.bookmarkRepository {
            override suspend fun restoreBookmarks(items: List<BookmarkedTopic>, replaceExisting: Boolean) {
                failBookmarksWith?.let { throw it }
                target.bookmarkRepository.restoreBookmarks(items, replaceExisting)
            }
        }
        val guardedDraftRepo = object : DraftRepository by target.draftRepository {
            override suspend fun restoreDrafts(items: List<EditorDraft>, replaceExisting: Boolean) {
                failDraftsWith?.let { throw it }
                target.draftRepository.restoreDrafts(items, replaceExisting)
            }
        }

        val manager = BackupManagerImpl(
            settingsRepository = target.settingsRepository,
            radarRepository = target.radarRepository,
            bookmarkAndHistoryRepository = guardedBookmarkRepo,
            draftRepository = guardedDraftRepo,
            userRepository = target.userRepository,
            sessionRepository = target.sessionRepository,
            blockedUsersCache = target.blockedUsersCache,
            contentFilterBackupBridge = ContentFilterRepositoryBackupBridge(targetFilterRepo),
            onRadarSettingsRestored = { restored -> radarRestoredCallbacks.add(restored) },
            transactionRunner = object : LocalRestoreTransactionRunner {
                override suspend fun <R> runInTransaction(block: suspend () -> R): R {
                    txCount.incrementAndGet()
                    return block()
                }
            },
            restoreJournalStore = FileBackupRestoreJournalStore(journalFile),
        )

        suspend fun assertAllLocalStoresUnchanged() {
            assertEquals(expectedSettings, target.settingsRepository.exportSettingsForBackup(includeApiKeys = true))
            assertEquals(expectedPolling, target.radarRepository.getPollingSettings())
            assertEquals(expectedRules, target.radarRepository.getKeywordRulesForBackup())
            assertEquals(expectedTopics, target.radarRepository.getTrackedTopicsForBackup())
            assertEquals(expectedContentFilter, targetFilterRepo.getConfigSnapshot())
            assertEquals(expectedBlockedUsers, target.blockedUsersCache.getBlockedUsers())
            assertEquals(expectedBookmarks, target.bookmarkRepository.getAllBookmarksForBackup())
            assertEquals(expectedDrafts, target.draftRepository.getAllDrafts())
            assertTrue(radarRestoredCallbacks.isEmpty())
            assertTrue(remoteBlockCalls.isEmpty())
            assertNull(manager.pendingRemoteBlocklistSyncState.value)
            assertFalse(journalFile.exists())
        }

        // Case A: Failure injected during restoreBookmarks on OVERWRITE
        remoteBlockCalls.clear()
        failBookmarksWith = IOException("Simulated bookmark DB write error")
        val bookmarkFailResult = manager.restoreBackup(incomingJson, RestoreStrategy.OVERWRITE)
        assertTrue(bookmarkFailResult.isFailure)
        assertTrue(txCount.get() >= 1)
        failBookmarksWith = null
        assertAllLocalStoresUnchanged()

        // Case B: Failure injected during restoreDrafts (after restoreBookmarks already replaced bookmarks!)
        var threwOnce = false
        failDraftsWith = IllegalStateException("Simulated draft table constraint error")
        val failingOnceDraftRepo = object : DraftRepository by target.draftRepository {
            override suspend fun restoreDrafts(items: List<EditorDraft>, replaceExisting: Boolean) {
                if (!threwOnce) {
                    threwOnce = true
                    throw failDraftsWith!!
                }
                target.draftRepository.restoreDrafts(items, replaceExisting)
            }
        }
        val managerDraftFail = BackupManagerImpl(
            settingsRepository = target.settingsRepository,
            radarRepository = target.radarRepository,
            bookmarkAndHistoryRepository = target.bookmarkRepository,
            draftRepository = failingOnceDraftRepo,
            userRepository = target.userRepository,
            sessionRepository = target.sessionRepository,
            blockedUsersCache = target.blockedUsersCache,
            contentFilterBackupBridge = ContentFilterRepositoryBackupBridge(targetFilterRepo),
            onRadarSettingsRestored = { restored -> radarRestoredCallbacks.add(restored) },
            restoreJournalStore = FileBackupRestoreJournalStore(journalFile),
        )
        val draftFailResult = managerDraftFail.restoreBackup(incomingJson, RestoreStrategy.OVERWRITE)
        assertTrue(draftFailResult.isFailure)
        failDraftsWith = null
        assertAllLocalStoresUnchanged()

        // Case C: Coroutine cancellation mid-restore rethrows CancellationException AND rolls back cleanly
        var cancelTriggered = false
        val cancellingDraftRepo = object : DraftRepository by target.draftRepository {
            override suspend fun restoreDrafts(items: List<EditorDraft>, replaceExisting: Boolean) {
                if (!cancelTriggered) {
                    cancelTriggered = true
                    throw CancellationException("User cancelled restore")
                }
                target.draftRepository.restoreDrafts(items, replaceExisting)
            }
        }
        val managerCancel = BackupManagerImpl(
            settingsRepository = target.settingsRepository,
            radarRepository = target.radarRepository,
            bookmarkAndHistoryRepository = target.bookmarkRepository,
            draftRepository = cancellingDraftRepo,
            userRepository = target.userRepository,
            sessionRepository = target.sessionRepository,
            blockedUsersCache = target.blockedUsersCache,
            contentFilterBackupBridge = ContentFilterRepositoryBackupBridge(targetFilterRepo),
            onRadarSettingsRestored = { restored -> radarRestoredCallbacks.add(restored) },
            restoreJournalStore = FileBackupRestoreJournalStore(journalFile),
        )
        var caughtCancel: CancellationException? = null
        try {
            managerCancel.restoreBackup(incomingJson, RestoreStrategy.OVERWRITE)
        } catch (ce: CancellationException) {
            caughtCancel = ce
        }
        assertNotNull(caughtCancel)
        assertAllLocalStoresUnchanged()
    }

    @Test
    fun `uncommitted restore journal on disk is recovered cleanly on startup and unwritable journal aborts upfront`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503)
        }

        val stack = TestNodeStack("journal_recovery", tempDir, ioScope, server)
        val filterStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(tempDir, "journal_recovery_cf.preferences_pb") },
        )
        val filterRepo = ContentFilterRepositoryImpl(filterStore, ioScope)
        val filterBridge = ContentFilterRepositoryBackupBridge(filterRepo)

        // 1. Set clean pre-crash baseline state across all 6 stores
        stack.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)
        stack.settingsRepository.setNodeImageApiKey("ni_baseline_key")
        stack.settingsRepository.addRecentSearchQuery("baseline_search")
        stack.radarRepository.updatePollingSettings {
            RadarPollingSettings(isEnabled = true, foregroundIntervalMinutes = 4, backgroundIntervalMinutes = 45)
        }
        val baselineRule = stack.radarRepository.addKeywordRule("BaselineRule", excludeWords = "收", categorySlug = "trade").getOrThrow()
        stack.radarRepository.trackTopic(3001L, "Baseline Topic", currentMaxFloor = 22, totalPages = 3)
        stack.blockedUsersCache.replaceAll(listOf(FloorAuthor(uid = 9L, username = "baseline_blocked")))
        filterRepo.replaceConfig(
            ContentFilterConfig(
                isEnabled = true,
                mutedCategorySlugs = setOf("promotion"),
                rules = listOf(ContentBlockRule(id = "cf_base", pattern = "AFF", matchMode = KeywordMatchMode.CONTAINS)),
            ),
        )
        stack.bookmarkDao.upsert(
            BookmarkEntity.fromSummary(
                accountUid = 0L,
                summary = TopicSummary(id = 4001L, title = "Baseline Bookmark", categorySlug = "tech", categoryName = "技术", authorName = "u"),
                savedAtEpochMillis = 4_000L,
            ),
        )
        stack.draftRepository.saveDraft(
            EditorDraft(
                draftKey = EditorDraft.NEW_TOPIC_KEY,
                accountUid = 0L,
                title = "Baseline Draft",
                markdownContent = "baseline body",
                categorySlug = "tech",
                updatedAtMillis = 5_000L,
            ),
        )

        val baselineManager = BackupManagerImpl(
            settingsRepository = stack.settingsRepository,
            radarRepository = stack.radarRepository,
            bookmarkAndHistoryRepository = stack.bookmarkRepository,
            draftRepository = stack.draftRepository,
            userRepository = stack.userRepository,
            sessionRepository = stack.sessionRepository,
            blockedUsersCache = stack.blockedUsersCache,
            contentFilterBackupBridge = filterBridge,
        )
        val baselineSnapshot = baselineManager.exportBundle(includeApiKeys = true).getOrThrow()

        // 2. Write uncommitted journal to disk, then simulate partial mid-restore crash corrupting local stores
        val journalFile = File(tempDir, FileBackupRestoreJournalStore.DEFAULT_JOURNAL_FILE_NAME)
        val journalStore = FileBackupRestoreJournalStore(journalFile)
        journalStore.writePending(
            BackupRestoreJournalEntry(
                journalId = "crash-sim-001",
                createdAtEpochMillis = 1_765_000_000_000L,
                strategy = RestoreStrategy.OVERWRITE,
                preRestoreSnapshot = baselineSnapshot,
            ),
        )
        assertTrue(journalFile.isFile)

        // Corrupt/partially overwrite local stores as if process died mid-restore
        stack.settingsRepository.setThemeMode(ThemeMode.PAPER_LIGHT)
        stack.settingsRepository.setNodeImageApiKey("")
        stack.radarRepository.updatePollingSettings { RadarPollingSettings(isEnabled = false, backgroundIntervalMinutes = 120) }
        stack.radarRepository.restoreFromBackup(
            pollingSettings = RadarPollingSettings(isEnabled = false, backgroundIntervalMinutes = 120),
            keywordRules = emptyList(),
            trackedTopics = emptyList(),
            replaceExisting = true,
        )
        stack.blockedUsersCache.replaceAll(listOf(FloorAuthor(uid = 99L, username = "partial_dirty_user")))
        filterRepo.replaceConfig(ContentFilterConfig(isEnabled = false))
        stack.bookmarkDao.clearAll()

        // 3. Fresh BackupManager on restart recovers uncommitted journal cleanly
        val syncedPollingOnRecovery = CopyOnWriteArrayList<RadarPollingSettings>()
        val restartedManager = BackupManagerImpl(
            settingsRepository = stack.settingsRepository,
            radarRepository = stack.radarRepository,
            bookmarkAndHistoryRepository = stack.bookmarkRepository,
            draftRepository = stack.draftRepository,
            userRepository = stack.userRepository,
            sessionRepository = stack.sessionRepository,
            blockedUsersCache = stack.blockedUsersCache,
            contentFilterBackupBridge = filterBridge,
            onRadarSettingsRestored = { restored -> syncedPollingOnRecovery.add(restored) },
            restoreJournalStore = journalStore,
        )

        val recovered = restartedManager.recoverPendingRestoreIfNeeded().getOrThrow()
        assertTrue(recovered)
        assertFalse(journalFile.exists())
        assertEquals(1, syncedPollingOnRecovery.size)
        assertEquals(45, syncedPollingOnRecovery.single().backgroundIntervalMinutes)

        // Verify all 6 stores were restored to pre-crash baseline
        assertEquals(baselineSnapshot.settings, stack.settingsRepository.exportSettingsForBackup(includeApiKeys = true))
        assertEquals(listOf(baselineRule), stack.radarRepository.getKeywordRulesForBackup())
        assertEquals(3001L, stack.radarRepository.getTrackedTopicsForBackup().single().topicId)
        assertEquals(listOf("baseline_blocked"), stack.blockedUsersCache.getBlockedUsers().map { it.username })
        assertTrue(filterRepo.getConfigSnapshot().isEnabled)
        assertEquals(listOf("cf_base"), filterRepo.getConfigSnapshot().rules.map { it.id })
        assertEquals(4001L, stack.bookmarkRepository.getAllBookmarksForBackup().single().topicId)
        assertEquals("Baseline Draft", stack.draftRepository.getAllDrafts().single().title)

        // Second call is a clean no-op
        assertFalse(restartedManager.recoverPendingRestoreIfNeeded().getOrThrow())

        // 4. Unwritable journal path (directory occupying journal file path) fails upfront BEFORE mutating any store
        val unwritableJournalPath = File(tempDir, "unwritable_journal_dir").apply { mkdirs() }
        val unwritableManager = BackupManagerImpl(
            settingsRepository = stack.settingsRepository,
            radarRepository = stack.radarRepository,
            bookmarkAndHistoryRepository = stack.bookmarkRepository,
            draftRepository = stack.draftRepository,
            userRepository = stack.userRepository,
            sessionRepository = stack.sessionRepository,
            blockedUsersCache = stack.blockedUsersCache,
            contentFilterBackupBridge = filterBridge,
            restoreJournalStore = FileBackupRestoreJournalStore(unwritableJournalPath),
        )
        val otherSource = TestNodeStack("unwritable_src", tempDir, ioScope, server)
        otherSource.settingsRepository.setThemeMode(ThemeMode.PAPER_LIGHT)
        val otherJson = otherSource.backupManager.exportBackupJson().getOrThrow()

        val unwritableResult = unwritableManager.restoreBackup(otherJson, RestoreStrategy.OVERWRITE)
        assertTrue(unwritableResult.isFailure)
        assertEquals(ThemeMode.OBSIDIAN_DARK, stack.settingsRepository.exportSettingsForBackup().themeMode)
    }

    @Test
    fun `upfront full validation rejects invalid radar regex, content filter regex, and unknown matchMode without mutating state`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503)
        }

        val stack = TestNodeStack("upfront_validation", tempDir, ioScope, server)
        stack.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)
        stack.radarRepository.addKeywordRule("SafeKeyword", excludeWords = "收").getOrThrow()
        val baselineRules = stack.radarRepository.getKeywordRulesForBackup()

        val baseBundle = stack.backupManager.exportBundle().getOrThrow()

        // 1. Invalid radar regex in bundle
        val badRadarRegexJson = BackupManagerImpl.backupJson.encodeToString(
            baseBundle.copy(
                settings = baseBundle.settings.copy(themeMode = ThemeMode.PAPER_LIGHT),
                radar = baseBundle.radar.copy(
                    keywordRules = listOf(
                        baseBundle.radar.keywordRules.first().copy(
                            keyword = "[unclosed(",
                            matchMode = KeywordMatchMode.REGEX.name,
                        ),
                    ),
                ),
            ),
        )
        assertTrue(stack.backupManager.inspectBackup(badRadarRegexJson).isFailure)
        assertTrue(stack.backupManager.restoreBackup(badRadarRegexJson, RestoreStrategy.OVERWRITE).isFailure)

        // 2. Invalid content filter regex in bundle
        val badFilterRegexJson = BackupManagerImpl.backupJson.encodeToString(
            baseBundle.copy(
                settings = baseBundle.settings.copy(themeMode = ThemeMode.PAPER_LIGHT),
                blocklist = baseBundle.blocklist.copy(
                    contentFilter = ContentFilterBackupDto(
                        isEnabled = true,
                        rules = listOf(
                            ContentFilterRuleBackupDto(
                                id = "bad_cf",
                                pattern = "*invalid[regex",
                                matchMode = KeywordMatchMode.REGEX.name,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(stack.backupManager.inspectBackup(badFilterRegexJson).isFailure)
        assertTrue(stack.backupManager.restoreBackup(badFilterRegexJson, RestoreStrategy.OVERWRITE).isFailure)

        // 3. Unknown matchMode string in bundle
        val unknownModeJson = BackupManagerImpl.backupJson.encodeToString(
            baseBundle.copy(
                settings = baseBundle.settings.copy(themeMode = ThemeMode.PAPER_LIGHT),
                radar = baseBundle.radar.copy(
                    keywordRules = listOf(
                        baseBundle.radar.keywordRules.first().copy(matchMode = "FUZZY_AI_V9"),
                    ),
                ),
            ),
        )
        assertTrue(stack.backupManager.restoreBackup(unknownModeJson, RestoreStrategy.OVERWRITE).isFailure)

        // Verify local settings & radar rules were never touched by any rejected bundle
        assertEquals(ThemeMode.OBSIDIAN_DARK, stack.settingsRepository.exportSettingsForBackup().themeMode)
        assertEquals(baselineRules, stack.radarRepository.getKeywordRulesForBackup())
    }

    @Test
    fun `explicit remote blocklist sync verifies target account and aborts on logout or mid-sync account switch`() = runBlocking {
        var activeServerAccountUid = 101L
        var activeServerUsername = "AccountA"
        var switchAccountMidSync = false
        val serverBlockedByAccount = CopyOnWriteArrayList<Pair<Long, String>>()
        lateinit var target: TestNodeStack

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> {
                        val b64 = encodeConfig(activeServerAccountUid, activeServerUsername)
                        MockResponse().setResponseCode(200)
                            .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$b64</script></body></html>""")
                    }
                    path.startsWith("/api/block-list/add") -> {
                        val body = request.body.readUtf8()
                        serverBlockedByAccount.add(activeServerAccountUid to body)
                        if (switchAccountMidSync && serverBlockedByAccount.size == 1) {
                            // Simulate concurrent account switch right after first remote call finishes
                            activeServerAccountUid = 202L
                            activeServerUsername = "AccountB"
                            runBlocking { target.sessionRepository.refreshSession() }
                        }
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val source = TestNodeStack("sync_src", tempDir, ioScope, server)
        source.blockedUsersCache.replaceAll(
            listOf(
                FloorAuthor(uid = 11L, username = "spam_one"),
                FloorAuthor(uid = 12L, username = "spam_two"),
            ),
        )
        val backupJson = source.backupManager.exportBackupJson().getOrThrow()

        target = TestNodeStack("sync_dst", tempDir, ioScope, server)
        target.sessionRepository.refreshSession().getOrThrow()

        val summary = target.backupManager.restoreBackup(backupJson, RestoreStrategy.OVERWRITE).getOrThrow()
        assertEquals(2, summary.blockedUsersImported)
        assertEquals(0, summary.blockedUsersSyncedToServer)
        assertEquals(BackupSyncTargetAccount(uid = 101L, username = "AccountA"), summary.targetSyncAccount)
        assertTrue(serverBlockedByAccount.isEmpty())

        // 1. Mismatched expected UID is rejected with 0 server calls
        val wrongUidResult = target.backupManager.syncBlockedUsersToServer(
            expectedAccountUid = 999L,
            expectedUsername = "AccountA",
        )
        assertTrue(wrongUidResult.isFailure)
        assertTrue(serverBlockedByAccount.isEmpty())

        // 2. Mismatched expected username is rejected with 0 server calls
        val wrongNameResult = target.backupManager.syncBlockedUsersToServer(
            expectedAccountUid = 101L,
            expectedUsername = "SomeoneElse",
        )
        assertTrue(wrongNameResult.isFailure)
        assertTrue(serverBlockedByAccount.isEmpty())

        // 3. Signed-out state is rejected with 0 server calls
        target.sessionRepository.signOutLocalOnly().getOrThrow()
        val signedOutResult = target.backupManager.syncBlockedUsersToServer(
            expectedAccountUid = 101L,
            expectedUsername = "AccountA",
        )
        assertTrue(signedOutResult.isFailure)
        assertTrue(serverBlockedByAccount.isEmpty())

        // 4. Sign back in as AccountA, trigger mid-sync switch to AccountB after first user -> second user MUST NOT hit AccountB!
        activeServerAccountUid = 101L
        activeServerUsername = "AccountA"
        target.sessionRepository.refreshSession().getOrThrow()
        switchAccountMidSync = true

        val midSwitchResult = target.backupManager.syncBlockedUsersToServer(
            expectedAccountUid = 101L,
            expectedUsername = "AccountA",
        )
        assertTrue(midSwitchResult.isFailure)
        assertEquals(1, serverBlockedByAccount.size)
        assertEquals(101L, serverBlockedByAccount.single().first)

        // 5. Explicit confirmation targeting the new AccountB (UID 202) now succeeds cleanly
        switchAccountMidSync = false
        serverBlockedByAccount.clear()
        val confirmedAccountBSync = target.backupManager.syncBlockedUsersToServer(
            expectedAccountUid = 202L,
            expectedUsername = "AccountB",
        ).getOrThrow()
        assertEquals(BackupSyncTargetAccount(uid = 202L, username = "AccountB"), confirmedAccountBSync.targetAccount)
        assertEquals(2, confirmedAccountBSync.syncedCount)
        assertEquals(0, confirmedAccountBSync.failedCount)
        assertEquals(2, serverBlockedByAccount.size)
        assertTrue(serverBlockedByAccount.all { it.first == 202L })
    }
}

private class FakeDraftDao : DraftDao {
    private val state = MutableStateFlow<List<DraftEntity>>(emptyList())

    override suspend fun getDraft(accountUid: Long, draftKey: String): DraftEntity? =
        state.value.firstOrNull { it.accountUid == accountUid && it.draftKey == draftKey }

    override fun observeDraft(accountUid: Long, draftKey: String): Flow<DraftEntity?> =
        state.map { rows -> rows.firstOrNull { it.accountUid == accountUid && it.draftKey == draftKey } }

    override fun observeDrafts(accountUid: Long): Flow<List<DraftEntity>> =
        state.map { rows ->
            rows.filter { it.accountUid == accountUid }.sortedByDescending { it.updatedAtMillis }
        }

    override suspend fun upsert(entity: DraftEntity) {
        upsertAll(listOf(entity))
    }

    override suspend fun upsertAll(entities: List<DraftEntity>) {
        val next = state.value.toMutableList()
        for (entity in entities) {
            next.removeAll { it.accountUid == entity.accountUid && it.draftKey == entity.draftKey }
            next.add(entity)
        }
        state.value = next
    }

    override suspend fun deleteDraft(accountUid: Long, draftKey: String) {
        state.value = state.value.filterNot { it.accountUid == accountUid && it.draftKey == draftKey }
    }

    override suspend fun clearForAccount(accountUid: Long) {
        state.value = state.value.filterNot { it.accountUid == accountUid }
    }

    override suspend fun getAllDrafts(): List<DraftEntity> =
        state.value.sortedByDescending { it.updatedAtMillis }

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}

private class FakeContentFilterBackupBridge : ContentFilterBackupBridge {
    var state: ContentFilterBackupDto = ContentFilterBackupDto()

    override suspend fun export(): ContentFilterBackupDto = state

    override suspend fun restore(dto: ContentFilterBackupDto, merge: Boolean) {
        if (!merge) {
            state = dto
            return
        }
        val mergedMuted = (state.mutedCategorySlugs + dto.mutedCategorySlugs).toSet()
        val mergedRules = LinkedHashMap<String, ContentFilterRuleBackupDto>()
        for (rule in state.rules + dto.rules) {
            mergedRules[rule.id] = rule
        }
        state = ContentFilterBackupDto(
            isEnabled = dto.isEnabled,
            mutedCategorySlugs = mergedMuted,
            rules = mergedRules.values.toList(),
        )
    }
}
