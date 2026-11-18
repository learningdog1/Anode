package com.nodeseek.app.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nodeseek.app.data.local.BookmarkEntity
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
import com.nodeseek.app.data.repository.BookmarkAndHistoryRepositoryImpl
import com.nodeseek.app.data.repository.FakeBookmarkDao
import com.nodeseek.app.data.repository.FakeReadHistoryDao
import com.nodeseek.app.data.repository.RadarRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.data.repository.UserRepositoryImpl
import com.nodeseek.app.domain.backup.BackupFileNaming
import com.nodeseek.app.domain.backup.ContentFilterBackupBridge
import com.nodeseek.app.domain.backup.ContentFilterBackupDto
import com.nodeseek.app.domain.backup.ContentFilterRuleBackupDto
import com.nodeseek.app.domain.backup.NodePulseBackupBundle
import com.nodeseek.app.domain.backup.RestoreStrategy
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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

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
                userAgentProvider = UserAgentProvider("NodePulseBackupTest/1.0"),
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
        assertTrue(exportedJson.contains("\"requireBatteryNotLowInBackground\": true"))
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
        assertEquals(2, restoreSummary.blockedUsersSyncedToServer)
        assertEquals(0, restoreSummary.blockedUsersServerSyncSkippedOrFailed)
        assertEquals(1, restoreSummary.contentFilterRulesImported)
        assertEquals(1, restoreSummary.bookmarksImported)
        assertEquals(2, restoreSummary.draftsImported)
        assertEquals(2, blockedApiCallCount.size)

        // Verify Settings restored
        val restoredSettings = withTimeout(3_000L) {
            target.settingsRepository.settings.first { it.nodeImageApiKey == "ni_live_secret_123" }
        }
        assertEquals(ThemeMode.OBSIDIAN_DARK, restoredSettings.themeMode)
        assertTrue(restoredSettings.purePitchAmoled)
        assertTrue(restoredSettings.useDynamicColor)
        assertEquals(1.15f, restoredSettings.fontScale, 0.001f)
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
        assertEquals(2, restoreOutcome.blockedUsersServerSyncSkippedOrFailed)
        assertEquals(
            setOf("cached_user", "offline_added_user"),
            restoreTarget.blockedUsersCache.getBlockedUsers().map { it.username }.toSet(),
        )
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

        // Verify unknown keys + requireBatteryNotLowInBackground = false are accepted cleanly
        val forwardCompatJson = validJson
            .replace("\"requireBatteryNotLowInBackground\": true", "\"requireBatteryNotLowInBackground\": false")
            .replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"futureTopLevelField\": 123,")
        val decoded = BackupManagerImpl.backupJson.decodeFromString<NodePulseBackupBundle>(forwardCompatJson)
        assertFalse(decoded.radar.pollingSettings.requireBatteryNotLowInBackground)
        assertTrue(stack.backupManager.inspectBackup(forwardCompatJson).isSuccess)
    }

    @Test
    fun `BackupFileNaming formats suggested filename consistently`() {
        val millis = 1_763_480_000_000L // 2025-11-18T15:33:20Z
        val fileName = BackupFileNaming.createDefaultFileName(
            epochMillis = millis,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals("nodepulse-backup-20251118-1533.json", fileName)
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
