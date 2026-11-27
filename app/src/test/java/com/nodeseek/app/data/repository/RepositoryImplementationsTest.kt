package com.nodeseek.app.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nodeseek.app.data.local.BookmarkDao
import com.nodeseek.app.data.local.BookmarkEntity
import com.nodeseek.app.data.local.CategoryDao
import com.nodeseek.app.data.local.CategoryEntity
import com.nodeseek.app.data.local.DataStoreBlockedUsersCache
import com.nodeseek.app.data.local.ReadHistoryDao
import com.nodeseek.app.data.local.ReadHistoryEntity
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.local.SettingsSecretCipher
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.parser.CategoryDto
import com.nodeseek.app.domain.backup.AppSettingsBackupDto
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
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
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RepositoryImplementationsTest {

    private lateinit var server: MockWebServer
    private lateinit var cookieBridge: WebViewCookieBridge
    private lateinit var apiClient: NodeSeekApiClient
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var inboxRepository: InboxRepositoryImpl
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var categoryDao: FakeCategoryDao
    private lateinit var readHistoryDao: FakeReadHistoryDao
    private lateinit var bookmarkDao: FakeBookmarkDao
    private lateinit var bookmarkAndHistoryRepository: BookmarkAndHistoryRepositoryImpl
    private lateinit var forumRepository: ForumRepositoryImpl
    private lateinit var checkInRepository: CheckInRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/")
        cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val uaProvider = UserAgentProvider("AnodeRepoTest/1.0")
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = uaProvider,
            additionalTargetHost = baseUrl.host,
        )

        settingsRepository = FakeSettingsRepository()
        categoryDao = FakeCategoryDao()
        readHistoryDao = FakeReadHistoryDao()
        bookmarkDao = FakeBookmarkDao()

        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        inboxRepository = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
        )
        sessionRepository.unreadCountListener = inboxRepository::onPiggybackedUnreadCount

        bookmarkAndHistoryRepository = BookmarkAndHistoryRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            bookmarkDao = bookmarkDao,
            readHistoryDao = readHistoryDao,
        )

        forumRepository = ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            categoryDao = categoryDao,
            readHistoryDao = readHistoryDao,
            bookmarkDao = bookmarkDao,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
        )
        sessionRepository.embeddedCategoriesListener = forumRepository::onEmbeddedCategoriesObserved

        checkInRepository = CheckInRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
        )

        apiClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
            onConfigObserved = sessionRepository::onConfigPayloadObserved,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun encodeConfig(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `HTML feed fetch piggybacks sessionState and unreadTotalCount and enriches read and bookmark state`() = runTest {
        val configB64 = encodeConfig(
            """
            {
              "enableCustomedStyle": 1,
              "user": {
                "member_id": 101,
                "member_name": "PulseDev",
                "coin": 950,
                "stardust": 18,
                "nPost": 10,
                "nComment": 85,
                "rank": 2,
                "roles": ["dev"],
                "collectionCount": 3,
                "unViewedCount": { "all": 7 }
              },
              "allCategory": [
                { "key": "tech", "cn_text": "技术" },
                { "key": "daily", "cn_text": "日常" }
              ]
            }
            """.trimIndent(),
        )

        // Pre-populate read history and bookmark for uid 101 (note: before first load uid is 0, so let's seedsession first or check second load)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    <html><body>
                      <div id="nsk-body">
                        <div class="post-list-item">
                          <div class="post-title"><a href="/post-501-1">高热帖 A</a></div>
                          <a class="post-category" href="/categories/tech">技术</a>
                          <span class="info-author"><a href="/space/101">PulseDev</a></span>
                          <span class="info-views"><span>2,000</span></span>
                          <span class="info-comments-count"><span>50</span></span>
                        </div>
                        <div class="post-list-item">
                          <div class="post-title"><a href="/post-502-1">普通帖 B</a></div>
                          <a class="post-category" href="/categories/daily">日常</a>
                          <span class="info-author"><a href="/space/102">Reader</a></span>
                          <span class="info-views"><span>100</span></span>
                          <span class="info-comments-count"><span>2</span></span>
                        </div>
                      </div>
                      <script id="temp-script" type="application/json">$configB64</script>
                    </body></html>
                    """.trimIndent(),
                ),
        )

        val feedResult = forumRepository.getFeed(sort = FeedSort.HOT, page = 1).getOrThrow()

        // Piggybacked session and unread count updated automatically!
        val session = sessionRepository.sessionState.value
        assertNotNull(session)
        assertEquals(101L, session!!.uid)
        assertEquals("PulseDev", session.username)
        assertEquals(3, session.level) // 900 <= 950 < 1600 -> Lv.3
        assertEquals(7, inboxRepository.unreadTotalCount.value)

        // HOT sort orders 501 (50*8 + 2000/20 = 500) ahead of 502 (2*8 + 5 = 21)
        assertEquals(listOf(501L, 502L), feedResult.topics.map { it.id })

        // Now record visit to 502 and bookmark 501, then verify enrichment
        bookmarkAndHistoryRepository.recordTopicVisit(
            ReadHistoryEntry(
                topicId = 502L,
                title = "普通帖 B",
                categoryName = "日常",
                authorName = "Reader",
                lastReadPage = 2,
                lastReadFloor = 18,
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        bookmarkAndHistoryRepository.setTopicBookmarked(topicId = 501L, bookmarked = true).getOrThrow()

        assertTrue(501L in bookmarkAndHistoryRepository.observeBookmarkedIds().first())
        val historyMap = bookmarkAndHistoryRepository.observeReadStateMap(listOf(502L)).first()
        assertEquals(18, historyMap[502L]?.lastReadFloor)
    }

    @Test
    fun `Cloudflare challenge on guest feed flags challengeRequired and falls back to RSS`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("<html><title>Just a moment...</title></html>"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <channel>
                        <item>
                          <title>RSS 降级技术帖</title>
                          <link>https://www.nodeseek.com/post-777-1</link>
                          <dc:creator>GuestAuthor</dc:creator>
                          <category>技术</category>
                          <pubDate>Thu, 15 Oct 2026 08:00:00 GMT</pubDate>
                        </item>
                        <item>
                          <title>RSS 日常帖</title>
                          <link>https://www.nodeseek.com/post-778-1</link>
                          <dc:creator>Other</dc:creator>
                          <category>日常</category>
                        </item>
                      </channel>
                    </rss>
                    """.trimIndent(),
                ),
        )

        val result = forumRepository.getFeed(categorySlug = "tech", page = 1).getOrThrow()
        assertTrue(sessionRepository.challengeRequired.value)
        assertTrue(result.isFromRssFallback)
        assertEquals(listOf(777L), result.topics.map { it.id })
    }

    @Test
    fun `getTopicDetail maps post access restrictions to TopicAccessException subclasses`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body-left">查看本帖需要 Lv 5</div></body></html>"""),
        )

        val failure = forumRepository.getTopicDetail(topicId = 900L, page = 1).exceptionOrNull()
        assertTrue(failure is TopicAccessException.LevelRequired)
        assertEquals(5, (failure as TopicAccessException.LevelRequired).minimumLevel)
    }

    @Test
    fun `checkIn and reactToComment update live AccountSession drumstick balance`() = runTest {
        val signedInConfigB64 = encodeConfig(
            """
            {
              "user": { "member_id": 88, "member_name": "Tester", "coin": 100 },
              "allCategory": [{ "key": "daily", "cn_text": "日常" }]
            }
            """.trimIndent(),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$signedInConfigB64</script></body></html>"""),
        )
        sessionRepository.refreshSession().getOrThrow()
        assertEquals(100, sessionRepository.sessionState.value?.drumsticks)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"gain":5,"current":105,"message":"签到成功 +5"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"record":{"gain":5},"order":12,"total":300,"list":[]}"""),
        )

        val checkInStatus = checkInRepository.checkIn(randomReward = false).getOrThrow()
        assertTrue(checkInStatus.hasCheckedInToday)
        assertEquals(5, checkInStatus.todayGain)
        assertEquals(12, checkInStatus.todayOrder)
        assertEquals(105, sessionRepository.sessionState.value?.drumsticks)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"current":9,"coin":104}"""),
        )
        val reaction = forumRepository.reactToComment(1L, 55L, ReactionType.DRUMSTICK).getOrThrow()
        assertEquals(9, reaction.updatedCount)
        assertEquals(104, sessionRepository.sessionState.value?.drumsticks)
    }

    @Test
    fun `signIn 2FA flow and Inbox notifications work end-to-end`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"need2FA":true,"otpSession":"sess_2fa"}"""),
        )
        val step1 = sessionRepository.signIn("bob", "secret", "tok_1").getOrThrow()
        assertEquals(SignInStepResult.TwoFactorRequired("sess_2fa"), step1)

        val configB64 = encodeConfig(
            """{"user":{"member_id":202,"member_name":"bob","coin":400},"allCategory":[{"key":"dev","cn_text":"Dev"}]}""",
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$configB64</script></body></html>"""),
        )
        sessionRepository.verifyTwoFactor("123456", "sess_2fa").getOrThrow()
        assertEquals("bob", sessionRepository.sessionState.value?.username)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"success":true,"data":[{"id":1,"viewed":0,"post_id":500,"title":"测试回复","floor_id":3,"commenter_id":303,"commenter_name":"Alice","created_at":"2026-10-15T10:00:00Z"}]}""",
                ),
        )
        val notices = inboxRepository.getNotifications(NotificationKind.REPLIES).getOrThrow()
        assertEquals(1, notices.size)
        assertFalse(notices.single().isRead)
        assertEquals("Alice", notices.single().senderName)

        // Sign out verifies cleared session
        val signedOutB64 = encodeConfig("""{"user":null,"allCategory":[{"key":"dev","cn_text":"Dev"}]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$signedOutB64</script></body></html>"""),
        )
        sessionRepository.signOut().getOrThrow()
        assertNull(sessionRepository.sessionState.value)
    }

    @Test
    fun `UserRepositoryImpl omits self-only signature=1 for other users and falls back cleanly on expired self session`() = runTest {
        val userRepository = UserRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
        )

        // 1. Signed-out / viewing another user (`holybug`, uid = 512): even if caller passes
        // includeEditableSignature = true, repository MUST omit `signature=1` so NodeSeek never returns USER NOT FOUND!
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"success":true,"detail":{"member_id":512,"member_name":"holybug","bio":"NodeSeek member","readme":"# My Space","coin":250,"nPost":9,"nComment":15}}""",
                ),
        )
        val otherProfile = userRepository.getUserProfile(uid = 512L, includeEditableSignature = true).getOrThrow()
        assertEquals(512L, otherProfile.uid)
        assertEquals("holybug", otherProfile.username)
        assertEquals(9, otherProfile.topicCount)
        assertEquals(15, otherProfile.replyCount)
        val otherReq = server.takeRequest()
        assertEquals("/api/account/getInfo/512?readme=1", otherReq.path)

        // 2. Signed-in as uid = 101: if `?readme=1&signature=1` returns USER NOT FOUND (e.g. server session expired),
        // repository automatically falls back to `/api/account/getInfo/101?readme=1` and still loads profile!
        val signedInConfigB64 = encodeConfig(
            """{"user":{"member_id":101,"member_name":"SelfUser","coin":500},"allCategory":[{"key":"tech","cn_text":"技术"}]}""",
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$signedInConfigB64</script></body></html>"""),
        )
        sessionRepository.refreshSession().getOrThrow()
        val _bootstrapReq = server.takeRequest()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":false,"message":"USER NOT FOUND"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"readme":"Fallback self readme","nPost":4,"nComment":12}}"""),
        )

        val selfProfile = userRepository.getUserProfile(uid = 101L, includeEditableSignature = true).getOrThrow()
        assertEquals(101L, selfProfile.uid)
        assertEquals("SelfUser", selfProfile.username)
        assertEquals("Fallback self readme", selfProfile.readmeMarkdown)
        assertEquals(500, selfProfile.drumsticks)

        val selfAttempt1 = server.takeRequest()
        val selfAttempt2 = server.takeRequest()
        assertEquals("/api/account/getInfo/101?readme=1&signature=1", selfAttempt1.path)
        assertEquals("/api/account/getInfo/101?readme=1", selfAttempt2.path)

        // 3. User topics wrapped in `data` envelope decode category & permissions accurately
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"success":true,"data":{"discussions":[{"post_id":9001,"title":"Oracle新家坡免费账号尝试中","category":"tech","rank":2}],"totalPage":1}}""",
                ),
        )
        val (topics, hasMore) = userRepository.getUserTopics(uid = 512L, page = 1).getOrThrow()
        assertEquals(1, topics.size)
        assertEquals("技术", topics.single().categoryName)
        assertEquals(2, topics.single().requiredViewLevel)
        assertFalse(hasMore)
    }

    @Test
    fun `default AppSettings uses palette defaults with 90 percent font scale and checkIn invalidates across Beijing midnight`() = runTest {
        assertFalse(AppSettings().useDynamicColor)
        assertFalse(settingsRepository.settings.value.useDynamicColor)
        assertEquals(0.90f, AppSettings().fontScale, 0.0001f)
        assertEquals(0.90f, AppSettings().homeCardScale, 0.0001f)
        assertEquals(0.90f, settingsRepository.settings.value.fontScale, 0.0001f)
        assertEquals(0.90f, settingsRepository.settings.value.homeCardScale, 0.0001f)

        val signedInConfigB64 = encodeConfig(
            """
            {
              "user": { "member_id": 88, "member_name": "Tester", "coin": 100 },
              "allCategory": [{ "key": "daily", "cn_text": "日常" }]
            }
            """.trimIndent(),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$signedInConfigB64</script></body></html>"""),
        )
        sessionRepository.refreshSession().getOrThrow()

        // Clock starts at 2026-11-15 23:59:55 UTC+8 (15:59:55 UTC)
        var fakeNowMillis = java.time.Instant.parse("2026-11-15T15:59:55Z").toEpochMilli()
        val timedCheckInRepo = CheckInRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            clockMillis = { fakeNowMillis },
        )

        // 1. Before midnight: checked in with +1 drumstick
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"success":true,"record":{"id":10,"member_id":88,"day_id":90,"gain":1,"created_at":"2026-11-15T14:20:00.000Z"},"order":42,"total":500,"list":[]}""",
                ),
        )
        val day1Status = timedCheckInRepo.getCheckInBoard(page = 1).getOrThrow()
        assertTrue(day1Status.hasCheckedInToday)
        assertEquals(1, day1Status.todayGain)
        assertEquals(1, timedCheckInRepo.checkInStatus.value?.todayGain)

        // 2. Advance 10 seconds across Beijing midnight -> 2026-11-16 00:00:05 UTC+8 (within 15s TTL!)
        // Even if server/CDN momentarily returned yesterday's created_at or null record, it must NOT show +1!
        fakeNowMillis += 10_000L
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"record":{},"order":null,"total":12,"list":[]}"""),
        )
        val day2Unchecked = timedCheckInRepo.getCheckInBoard(page = 1).getOrThrow()
        assertFalse(day2Unchecked.hasCheckedInToday)
        assertEquals(null, day2Unchecked.todayGain)
        assertEquals(false, timedCheckInRepo.checkInStatus.value?.hasCheckedInToday)

        // 3. When user already checked in on web (+5) and taps check-in, "已完成签到" syncs actual +5 gain
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":false,"message":"今天已完成签到，请勿重复操作"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"success":true,"record":{"id":11,"member_id":88,"day_id":91,"gain":5,"created_at":"2026-11-15T16:00:08.000Z"},"order":9,"total":20,"list":[]}""",
                ),
        )
        val syncedAfterDuplicate = timedCheckInRepo.checkIn(randomReward = false).getOrThrow()
        assertTrue(syncedAfterDuplicate.hasCheckedInToday)
        assertEquals(5, syncedAfterDuplicate.todayGain)
        assertEquals(9, syncedAfterDuplicate.todayOrder)
        assertEquals(5, timedCheckInRepo.checkInStatus.value?.todayGain)
    }

    @Test
    fun `SettingsRepositoryImpl persists trimmed seekMailApiKey across DataStore round-trip`() = runBlocking {
        val tempDir = Files.createTempDirectory("settings_seek_mail_test").toFile()
        val dataStoreFile = File(tempDir, "nodepulse_settings_test.preferences_pb")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { dataStoreFile },
            )
            val writerRepo = SettingsRepositoryImpl(
                dataStore = dataStore,
                externalScope = repoScope1,
            )
            assertEquals("", writerRepo.settings.value.seekMailApiKey)

            writerRepo.setSeekMailApiKey("  sk_test_seekmail_roundtrip_key  ")
            val persisted = withTimeout(5_000L) {
                writerRepo.settings.first { it.seekMailApiKey.isNotEmpty() }
            }
            assertEquals("sk_test_seekmail_roundtrip_key", persisted.seekMailApiKey)

            val rawPrefs = dataStore.data.first()
            val storedRaw = rawPrefs[stringPreferencesKey("seek_mail_api_key")].orEmpty()
            assertTrue(
                "Stored seek_mail_api_key must be encrypted at rest, got: $storedRaw",
                storedRaw.startsWith(SettingsSecretCipher.PREFIX_SW) || storedRaw.startsWith(SettingsSecretCipher.PREFIX_AKS),
            )
            assertFalse("Stored value must not contain plaintext secret", storedRaw.contains("sk_test_seekmail_roundtrip_key"))

            // Rebind a fresh SettingsRepositoryImpl to verify decoding from persisted Preferences.
            val readerRepo = SettingsRepositoryImpl(
                dataStore = dataStore,
                externalScope = repoScope2,
            )
            val reloaded = withTimeout(5_000L) {
                readerRepo.settings.first { it.seekMailApiKey.isNotEmpty() }
            }
            assertEquals("sk_test_seekmail_roundtrip_key", reloaded.seekMailApiKey)

            // Whitespace-only write clears the stored key.
            readerRepo.setSeekMailApiKey("   ")
            val cleared = withTimeout(5_000L) {
                readerRepo.settings.first { it.seekMailApiKey.isEmpty() }
            }
            assertEquals("", cleared.seekMailApiKey)
        } finally {
            repoScope1.cancel()
            repoScope2.cancel()
            dataStoreScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `SettingsRepositoryImpl persists AwayDigest custom duration, trigger mode, and backup round-trip`() = runBlocking {
        val tempDir = Files.createTempDirectory("settings_away_digest_test").toFile()
        val dataStoreFile = File(tempDir, "away_digest_settings_test.preferences_pb")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { dataStoreFile },
            )
            val writerRepo = SettingsRepositoryImpl(
                dataStore = dataStore,
                externalScope = repoScope1,
            )
            assertEquals(true, writerRepo.settings.value.awayDigestEnabled)
            assertEquals(AwayDigestTriggerMode.CUSTOM_DURATION, writerRepo.settings.value.awayDigestTriggerMode)
            assertEquals(60, writerRepo.settings.value.awayDigestThresholdMinutes)

            writerRepo.setAwayDigestTriggerMode(AwayDigestTriggerMode.DURATION_OR_REOPEN)
            writerRepo.setAwayDigestThresholdMinutes(90)
            writerRepo.setAwayDigestEnabled(false)

            val updated = withTimeout(5_000L) {
                writerRepo.settings.first {
                    !it.awayDigestEnabled &&
                        it.awayDigestTriggerMode == AwayDigestTriggerMode.DURATION_OR_REOPEN &&
                        it.awayDigestThresholdMinutes == 90
                }
            }
            assertEquals(false, updated.awayDigestEnabled)
            assertEquals(AwayDigestTriggerMode.DURATION_OR_REOPEN, updated.awayDigestTriggerMode)
            assertEquals(90, updated.awayDigestThresholdMinutes)

            // Verify clamping of out-of-range custom minutes
            writerRepo.setAwayDigestThresholdMinutes(1)
            val clampedMin = withTimeout(5_000L) {
                writerRepo.settings.first { it.awayDigestThresholdMinutes == AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES }
            }
            assertEquals(5, clampedMin.awayDigestThresholdMinutes)

            writerRepo.setAwayDigestThresholdMinutes(99_999)
            val clampedMax = withTimeout(5_000L) {
                writerRepo.settings.first { it.awayDigestThresholdMinutes == AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES }
            }
            assertEquals(4320, clampedMax.awayDigestThresholdMinutes)

            writerRepo.setAwayDigestThresholdMinutes(270)
            writerRepo.setAwayDigestEnabled(true)
            writerRepo.setAwayDigestTriggerMode(AwayDigestTriggerMode.ON_APP_REOPEN)
            withTimeout(5_000L) {
                writerRepo.settings.first {
                    it.awayDigestEnabled &&
                        it.awayDigestTriggerMode == AwayDigestTriggerMode.ON_APP_REOPEN &&
                        it.awayDigestThresholdMinutes == 270
                }
            }

            // Rebind fresh reader repo over persisted DataStore
            val readerRepo = SettingsRepositoryImpl(
                dataStore = dataStore,
                externalScope = repoScope2,
            )
            val reloaded = withTimeout(5_000L) {
                readerRepo.settings.first {
                    it.awayDigestTriggerMode == AwayDigestTriggerMode.ON_APP_REOPEN &&
                        it.awayDigestThresholdMinutes == 270
                }
            }
            assertTrue(reloaded.awayDigestEnabled)
            assertEquals(AwayDigestTriggerMode.ON_APP_REOPEN, reloaded.awayDigestTriggerMode)
            assertEquals(270, reloaded.awayDigestThresholdMinutes)

            val backupDto = readerRepo.exportSettingsForBackup(includeApiKeys = false)
            assertEquals(true, backupDto.awayDigestEnabled)
            assertEquals(AwayDigestTriggerMode.ON_APP_REOPEN, backupDto.awayDigestTriggerMode)
            assertEquals(270, backupDto.awayDigestThresholdMinutes)
        } finally {
            repoScope1.cancel()
            repoScope2.cancel()
            dataStoreScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `signOut clears local session cookies and state even when server returns 403 and signOutLocalOnly skips network`() = runTest {
        val baseUrl = server.url("/")
        cookieBridge.saveFromResponse(
            baseUrl,
            listOf(
                Cookie.Builder().name("session").value("sess_abc").domain(baseUrl.host).path("/").build(),
                Cookie.Builder().name("smac").value("smac_xyz").domain(baseUrl.host).path("/").build(),
                Cookie.Builder().name("cf_clearance").value("cf_ok").domain(baseUrl.host).path("/").build(),
            ),
        )
        val signedInConfigB64 = encodeConfig(
            """{"user":{"member_id":101,"member_name":"Alice","coin":500,"unViewedCount":{"all":3}},"allCategory":[{"key":"tech","cn_text":"技术"}]}""",
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$signedInConfigB64</script></body></html>"""),
        )
        sessionRepository.refreshSession().getOrThrow()
        assertNotNull(sessionRepository.sessionState.value)
        assertEquals(3, inboxRepository.unreadTotalCount.value)

        // Server rejects signOut with 403: local session & auth cookies MUST still clear without failing!
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"success":false,"message":"Forbidden"}"""),
        )
        sessionRepository.signOut().getOrThrow()
        assertNull(sessionRepository.sessionState.value)
        assertFalse(sessionRepository.challengeRequired.value)
        assertEquals(0, inboxRepository.unreadTotalCount.value)
        val remainingCookiesAfter403 = cookieBridge.loadForRequest(baseUrl).associate { it.name to it.value }
        assertFalse(remainingCookiesAfter403.containsKey("session"))
        assertFalse(remainingCookiesAfter403.containsKey("smac"))
        assertEquals("cf_ok", remainingCookiesAfter403["cf_clearance"])

        // Re-authenticate, then test signOutLocalOnly skips server /api/account/signOut completely
        val reqBeforeLocalOnly = server.requestCount
        cookieBridge.saveFromResponse(
            baseUrl,
            listOf(
                Cookie.Builder().name("session").value("sess_new").domain(baseUrl.host).path("/").build(),
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$signedInConfigB64</script></body></html>"""),
        )
        sessionRepository.refreshSession().getOrThrow()
        assertNotNull(sessionRepository.sessionState.value)

        sessionRepository.signOutLocalOnly().getOrThrow()
        assertNull(sessionRepository.sessionState.value)
        assertEquals(0, inboxRepository.unreadTotalCount.value)
        // Only the 1 refreshSession request was sent; zero signOut requests hit server
        assertEquals(reqBeforeLocalOnly + 1, server.requestCount)
        val remainingAfterLocalOnly = cookieBridge.loadForRequest(baseUrl).associate { it.name to it.value }
        assertFalse(remainingAfterLocalOnly.containsKey("session"))
        assertEquals("cf_ok", remainingAfterLocalOnly["cf_clearance"])
    }

    @Test
    fun `signIn handles snake_case 2FA, rejects missing session user after signIn, and maps expired OTP`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":false,"need_2fa":1,"otp_session":"otp_snake_99"}"""),
        )
        val step1 = sessionRepository.signIn("bob", "secret", "turnstile_tok").getOrThrow()
        assertEquals(SignInStepResult.TwoFactorRequired("otp_snake_99"), step1)

        // Expired OTP throws NodeSeekApiException with isOtpExpired = true
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":false,"message":"OTP_EXPIRED_OR_NOT_EXIST"}"""),
        )
        val otpErr = sessionRepository.verifyTwoFactor("123456", "otp_snake_99").exceptionOrNull()
        assertTrue(otpErr is com.nodeseek.app.data.network.NodeSeekApiException)
        assertTrue((otpErr as com.nodeseek.app.data.network.NodeSeekApiException).isOtpExpired)

        // If signIn returns success=true but subsequent GET / shows user=null, fail cleanly instead of fake login
        val signedOutConfigB64 = encodeConfig("""{"user":null,"allCategory":[{"key":"tech","cn_text":"技术"}]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$signedOutConfigB64</script></body></html>"""),
        )
        val syncErr = sessionRepository.signIn("bob", "secret", "turnstile_tok_2").exceptionOrNull()
        assertNotNull(syncErr)
        assertNull(sessionRepository.sessionState.value)
    }

    @Test
    fun `SettingsRepositoryImpl migrates legacy plaintext API keys, merges pinnedCategorySlugs, and clamps MAX_FONT_SCALE to 1_30`() = runBlocking {
        val tempDir = Files.createTempDirectory("settings_migration_merge_test").toFile()
        val dataStoreFile = File(tempDir, "settings_mig.preferences_pb")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { dataStoreFile },
            )
            // Seed legacy unencrypted API keys
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("node_image_api_key")] = "legacy_nodeimage_key_123"
                prefs[stringPreferencesKey("seek_mail_api_key")] = "legacy_seekmail_key_456"
            }

            val repo = SettingsRepositoryImpl(dataStore = dataStore, externalScope = repoScope)
            val loaded = withTimeout(5_000L) {
                repo.settings.first {
                    it.nodeImageApiKey == "legacy_nodeimage_key_123" &&
                        it.seekMailApiKey == "legacy_seekmail_key_456"
                }
            }
            assertEquals("legacy_nodeimage_key_123", loaded.nodeImageApiKey)
            assertEquals("legacy_seekmail_key_456", loaded.seekMailApiKey)

            // Wait for background plaintext migration to rewrite encrypted envelopes on disk.
            // Poll dataStore.data.first() instead of holding a single DataStoreImpl.data collector
            // across a concurrent edit() to avoid androidx.datastore 1.1.x's version-race flake.
            try {
                withTimeout(5_000L) {
                    while (true) {
                        val prefs = dataStore.data.first()
                        val rawImg = prefs[stringPreferencesKey("node_image_api_key")].orEmpty()
                        val rawMail = prefs[stringPreferencesKey("seek_mail_api_key")].orEmpty()
                        if (rawImg.startsWith(SettingsSecretCipher.PREFIX_SW) &&
                            rawMail.startsWith(SettingsSecretCipher.PREFIX_SW)
                        ) {
                            break
                        }
                        kotlinx.coroutines.delay(20L)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val snapshot = dataStore.data.first()
                throw AssertionError(
                    "Timed out waiting for migration: rawImg=${snapshot[stringPreferencesKey("node_image_api_key")]}, rawMail=${snapshot[stringPreferencesKey("seek_mail_api_key")]}",
                    e,
                )
            }

            // Font scale & home card scale independently clamp to [0.60f, 1.30f]
            assertEquals(0.60f, SettingsRepositoryImpl.MIN_FONT_SCALE, 0.0001f)
            assertEquals(1.30f, SettingsRepositoryImpl.MAX_FONT_SCALE, 0.0001f)
            assertEquals(0.60f, SettingsRepositoryImpl.MIN_HOME_CARD_SCALE, 0.0001f)
            assertEquals(1.30f, SettingsRepositoryImpl.MAX_HOME_CARD_SCALE, 0.0001f)
            repo.setFontScale(0.40f)
            val clampedLow = withTimeout(5_000L) { repo.settings.first { it.fontScale == 0.60f } }
            assertEquals(0.60f, clampedLow.fontScale, 0.0001f)
            assertEquals(0.90f, clampedLow.homeCardScale, 0.0001f)
            repo.setHomeCardScale(0.45f)
            val clampedCardLow = withTimeout(5_000L) { repo.settings.first { it.homeCardScale == 0.60f } }
            assertEquals(0.60f, clampedCardLow.homeCardScale, 0.0001f)
            repo.setFontScale(1.50f)
            repo.setHomeCardScale(1.60f)
            val clamped = withTimeout(5_000L) {
                repo.settings.first { it.fontScale == 1.30f && it.homeCardScale == 1.30f }
            }
            assertEquals(1.30f, clamped.fontScale, 0.0001f)
            assertEquals(1.30f, clamped.homeCardScale, 0.0001f)

            // Merge restore preserves existing pinned categories and appends backup categories
            repo.setPinnedCategorySlugs(linkedSetOf("tech", "daily"))
            withTimeout(5_000L) { repo.settings.first { it.pinnedCategorySlugs == setOf("tech", "daily") } }
            repo.restoreSettingsFromBackup(
                backup = AppSettingsBackupDto(pinnedCategorySlugs = setOf("trade", "review")),
                merge = true,
            )
            val merged = withTimeout(5_000L) {
                repo.settings.first { it.pinnedCategorySlugs.size == 4 }
            }
            assertEquals(setOf("tech", "daily", "trade", "review"), merged.pinnedCategorySlugs)
        } finally {
            repoScope.cancel()
            dataStoreScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `CheckInRepositoryImpl ignores late in-flight response that finishes after signOut`() = runBlocking {
        val signedInConfigB64 = encodeConfig(
            """{"user":{"member_id":88,"member_name":"Alice","coin":100},"allCategory":[{"key":"tech","cn_text":"技术"}]}""",
        )
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200)
                        .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$signedInConfigB64</script></body></html>""")
                    "/api/attendance/board?page=1" -> {
                        entered.countDown()
                        release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        MockResponse().setResponseCode(200)
                            .setBody("""{"success":true,"record":{"gain":5,"created_at":"2026-11-23T08:00:00Z"},"order":1,"total":10,"list":[]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        assertEquals(88L, sessionRepository.sessionState.value?.uid)

        val inFlight = async(Dispatchers.IO) {
            checkInRepository.getCheckInBoard(page = 1, forceRefresh = true)
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // Sign out locally while board request is still in-flight
        sessionRepository.signOutLocalOnly().getOrThrow()
        checkInRepository.clearCachedStatus()
        assertNull(sessionRepository.sessionState.value)
        assertNull(checkInRepository.checkInStatus.value)

        release.countDown()
        val result = inFlight.await()
        assertTrue(result.isSuccess)
        // Crucial: late response must NOT repopulate _checkInStatus after sign-out!
        assertNull(checkInRepository.checkInStatus.value)
    }

    @Test
    fun `SessionRepositoryImpl refreshSession notifies config once and signOut invokes onSignedOutCallback`() = runTest {
        var configCalls = 0
        var signedOutCallbacks = 0
        sessionRepository.embeddedCategoriesListener = { _, _ -> configCalls += 1 }
        sessionRepository.onSignedOutCallback = { signedOutCallbacks += 1 }

        val signedInConfigB64 = encodeConfig(
            """{"user":{"member_id":101,"member_name":"Alice","coin":500},"allCategory":[{"key":"tech","cn_text":"技术"}]}""",
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$signedInConfigB64</script></body></html>"""),
        )
        sessionRepository.refreshSession().getOrThrow()
        assertEquals("refreshSession must notify embeddedCategoriesListener exactly once", 1, configCalls)

        sessionRepository.signOutLocalOnly().getOrThrow()
        assertEquals(1, signedOutCallbacks)
    }

    @Test
    fun `ForumRepositoryImpl preserves category customizability on refresh and preserves list timestamps on detail cache`() = runBlocking {
        // Seed customized category with isCustomizable=true, visibleOnHome=false
        categoryDao.upsertAll(
            listOf(
                CategoryEntity(
                    accountUid = 0L,
                    slug = "tech",
                    name = "技术",
                    description = "技术讨论",
                    sortOrder = 0,
                    isCustomizable = true,
                    visibleOnHome = false,
                    isAdminOnly = false,
                    topicCoinCost = 0,
                    supportsRss = true,
                    syncedAtEpochMillis = 1_000L,
                ),
            ),
        )
        val configB64 = encodeConfig(
            """{"allCategory":[{"key":"tech","cn_text":"技术"},{"key":"daily","cn_text":"日常"}]}""",
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":[{"key":"tech","cn_text":"技术"},{"key":"daily","cn_text":"日常"}]}"""),
        )
        forumRepository.refreshCategories(force = true).getOrThrow()
        val persistedTech = categoryDao.getCategories(0L).first { it.slug == "tech" }
        assertTrue("Existing isCustomizable must be preserved", persistedTech.isCustomizable)
        assertFalse("Existing visibleOnHome must be preserved", persistedTech.visibleOnHome)

        // Feed loads topic 505 with accurate lastActivityIso & replyCount = 42
        val feedHtml = """
            <html><body>
              <div id="nsk-body">
                <div class="post-list-item">
                  <div class="post-title"><a href="/post-505-1">保留时间戳测试贴</a></div>
                  <a class="post-category" href="/categories/tech">技术</a>
                  <span class="info-author"><a href="/space/7">Author</a></span>
                  <span class="info-views"><span>300</span></span>
                  <span class="info-comments-count"><span>42</span></span>
                  <time datetime="2026-11-23T12:34:56.000Z"></time>
                </div>
              </div>
              <script id="temp-script" type="application/json">$configB64</script>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(feedHtml))
        forumRepository.getFeed(categorySlug = "tech", page = 1).getOrThrow()

        // Opening page 1 of detail (only 1 reply on page 1) must NOT overwrite replyCount=42 or blank lastActivityIso
        val threadConfigB64 = encodeConfig(
            """
            {
              "allCategory":[{"key":"tech","cn_text":"技术"}],
              "postData":{
                "postId":505,
                "postPage":1,
                "postPageCount":5,
                "title":"保留时间戳测试贴",
                "category":"tech",
                "categoryWord":"技术",
                "views":310,
                "comments":[
                  {"commentId":1,"floorIndex":0,"poster":{"uid":7,"name":"Author"},"markdown":"正文"},
                  {"commentId":2,"floorIndex":1,"poster":{"uid":8,"name":"Replier"},"markdown":"回复1"}
                ]
              }
            }
            """.trimIndent(),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"><div class="nsk-post"></div></div><script id="temp-script" type="application/json">$threadConfigB64</script></body></html>"""),
        )
        forumRepository.getTopicDetail(topicId = 505L, page = 1).getOrThrow()
        // Bookmarking after viewing detail uses recentSummaryCache -> must retain exact list lastActivityIso & replyCount!
        bookmarkAndHistoryRepository.setTopicBookmarked(topicId = 505L, bookmarked = true).getOrThrow()
        val bookmark = bookmarkDao.getBookmark(0L, 505L)
        assertNotNull(bookmark)
        assertEquals("2026-11-23T12:34:56.000Z", bookmark!!.lastActivityIso)
        assertEquals(42, bookmark.replyCount)
        assertEquals(310, bookmark.viewCount)
    }

    @Test
    fun `UserRepositoryImpl searchUsers strips leading at-sign`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"memberList":[{"member_id":42,"member_name":"alice"}]}"""),
        )
        val userRepo = UserRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
        )
        val users = userRepo.searchUsers("  @alice  ").getOrThrow()
        assertEquals(1, users.size)
        assertEquals("alice", users.single().username)
        val req = server.takeRequest()
        assertEquals("/api/account/find/alice", req.path)
    }

    @Test
    fun `DataStoreBlockedUsersCache isolates accounts by uid and supports clear`() = runBlocking {
        val tempDir = Files.createTempDirectory("blocked_users_isolation_test").toFile()
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { File(tempDir, "blocked_users.preferences_pb") },
            )
            var currentUid = 101L
            val cache = DataStoreBlockedUsersCache(
                dataStore = dataStore,
                accountUidProvider = { currentUid },
            )
            cache.replaceAll(
                listOf(
                    com.nodeseek.app.domain.model.FloorAuthor(
                        uid = 999L,
                        username = "SpammerA",
                    ),
                ),
            )
            assertEquals(listOf(999L), cache.getBlockedUsers().map { it.uid })

            // Switch to Account B: must NOT see Account A's blocked users
            currentUid = 202L
            assertTrue(cache.getBlockedUsers().isEmpty())

            // Switch back to Account A and clear
            currentUid = 101L
            assertEquals(1, cache.getBlockedUsers().size)
            cache.clear()
            assertTrue(cache.getBlockedUsers().isEmpty())
        } finally {
            dsScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `SettingsRepositoryImpl persists exact homepageCategoryOrder and round-trips through backup`() = runBlocking {
        val tempDir = Files.createTempDirectory("settings_category_order_test").toFile()
        val dataStoreFile = File(tempDir, "settings_order.preferences_pb")
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { dataStoreFile },
            )
            val repo = SettingsRepositoryImpl(dataStore = dataStore, externalScope = repoScope)

            // Reordered permutation of DEFAULT_PINNED_CATEGORY_SLUGS must preserve exact order
            val customOrder = listOf("trade", "review", "daily", "info", "tech")
            repo.setHomepageCategoryOrder(customOrder)
            val updated = withTimeout(5_000L) {
                repo.settings.first { it.homepageCategoryOrder == customOrder }
            }
            assertEquals(customOrder, updated.homepageCategoryOrder)

            val exported = repo.exportSettingsForBackup(includeApiKeys = false)
            assertEquals(customOrder, exported.homepageCategoryOrder)

            // Empty list clears key
            repo.setHomepageCategoryOrder(emptyList())
            val cleared = withTimeout(5_000L) {
                repo.settings.first { it.homepageCategoryOrder.isEmpty() }
            }
            assertTrue(cleared.homepageCategoryOrder.isEmpty())

            // Restore with merge appends without losing order
            repo.setHomepageCategoryOrder(listOf("dev", "tech"))
            withTimeout(5_000L) { repo.settings.first { it.homepageCategoryOrder == listOf("dev", "tech") } }
            repo.restoreSettingsFromBackup(
                backup = exported,
                merge = true,
            )
            val merged = withTimeout(5_000L) {
                repo.settings.first { it.homepageCategoryOrder.size == 6 }
            }
            assertEquals(listOf("dev", "tech", "trade", "review", "daily", "info"), merged.homepageCategoryOrder)
        } finally {
            repoScope.cancel()
            dsScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `SettingsRepositoryImpl isolates homepageCategoryOrder per accountUid across switches and cold reload`() = runBlocking {
        val tempDir = Files.createTempDirectory("settings_uid_isolation_test").toFile()
        val dataStoreFile = File(tempDir, "settings_uid_isolation.preferences_pb")
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore1 = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { dataStoreFile },
            )
            // Pre-seed legacy global key (must migrate only to guest 0L, never leak to signed-in UIDs)
            dataStore1.edit { prefs ->
                prefs[stringPreferencesKey("homepage_category_order")] = """["daily","info"]"""
            }

            val repo1 = SettingsRepositoryImpl(dataStore = dataStore1, externalScope = repoScope1)
            val guestInitial = repo1.awaitLoadedSettings()
            assertEquals(listOf("daily", "info"), guestInitial.homepageCategoryOrder)
            assertEquals(listOf("daily", "info"), repo1.getHomepageCategoryOrder(0L))

            // Switch to 101L: legacy guest order must not leak into 101L
            repo1.onActiveAccountChanged(101L)
            assertEquals(emptyList<String>(), repo1.settings.value.homepageCategoryOrder)
            assertEquals(emptyList<String>(), repo1.getHomepageCategoryOrder(101L))

            val order101 = listOf("tech", "trade", "review")
            repo1.setHomepageCategoryOrder(101L, order101)
            assertEquals(order101, repo1.settings.value.homepageCategoryOrder)
            assertEquals(order101, repo1.getHomepageCategoryOrder(101L))

            // Switch to 202L and set distinct order; 101L and 0L must stay untouched
            repo1.setActiveHomepageCategoryAccountUid(202L)
            assertEquals(emptyList<String>(), repo1.settings.value.homepageCategoryOrder)
            val order202 = listOf("carpool", "daily")
            repo1.setHomepageCategoryOrder(202L, order202)
            assertEquals(order202, repo1.settings.value.homepageCategoryOrder)
            assertEquals(order202, repo1.getHomepageCategoryOrder(202L))
            assertEquals(order101, repo1.getHomepageCategoryOrder(101L))
            assertEquals(listOf("daily", "info"), repo1.getHomepageCategoryOrder(0L))

            // Writing to non-active 101L must not mutate active 202L projection
            val updatedOrder101 = listOf("review", "tech")
            repo1.setHomepageCategoryOrder(101L, updatedOrder101)
            assertEquals(order202, repo1.settings.value.homepageCategoryOrder)

            // Switch active UID back to 101L and cold-reload without SessionRepository
            repo1.setActiveHomepageCategoryAccountUid(101L)
            assertEquals(updatedOrder101, repo1.settings.value.homepageCategoryOrder)

            repoScope1.cancel()
            val repo2 = SettingsRepositoryImpl(dataStore = dataStore1, externalScope = repoScope2)
            val reloaded = repo2.awaitLoadedSettings()
            assertEquals(updatedOrder101, reloaded.homepageCategoryOrder)
            assertEquals(updatedOrder101, repo2.getHomepageCategoryOrder(101L))
            assertEquals(order202, repo2.getHomepageCategoryOrder(202L))
            assertEquals(listOf("daily", "info"), repo2.getHomepageCategoryOrder(0L))
        } finally {
            repoScope1.cancel()
            repoScope2.cancel()
            dsScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `ForumRepositoryImpl syncHomepageCategories, saveHomepageCategories, and refreshes preserve custom order and visibility`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":[{"category":"trade","showInIndex":true},{"category":"info","showInIndex":false}]}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":[{"key":"daily","cn_text":"日常","showInIndex":true},{"key":"trade","cn_text":"交易","showInIndex":true},{"key":"info","cn_text":"情报","showInIndex":true}]}"""),
        )

        val synced = forumRepository.syncHomepageCategories().getOrThrow()
        assertFalse(synced.first { it.slug == "info" }.visibleOnHome)
        assertTrue(synced.first { it.slug == "trade" }.visibleOnHome)

        val customList = listOf(
            com.nodeseek.app.domain.model.Category(slug = "trade", name = "交易", isCustomizable = true, visibleOnHome = true),
            com.nodeseek.app.domain.model.Category(slug = "daily", name = "日常", isCustomizable = true, visibleOnHome = false),
            com.nodeseek.app.domain.model.Category(slug = "info", name = "情报", isCustomizable = true, visibleOnHome = true),
        )
        forumRepository.saveHomepageCategories(customList, syncToServer = true).getOrThrow()
        assertEquals(listOf("trade", "daily", "info"), settingsRepository.settings.value.homepageCategoryOrder)

        // Local-only save skips network
        forumRepository.saveHomepageCategories(
            listOf(
                com.nodeseek.app.domain.model.Category(slug = "info", name = "情报", isCustomizable = true, visibleOnHome = true),
                com.nodeseek.app.domain.model.Category(slug = "trade", name = "交易", isCustomizable = true, visibleOnHome = true),
                com.nodeseek.app.domain.model.Category(slug = "daily", name = "日常", isCustomizable = true, visibleOnHome = false),
            ),
            syncToServer = false,
        ).getOrThrow()

        // refreshCategories(force = true) must NOT clobber custom order or visibleOnHome=false on daily!
        val afterRefresh = forumRepository.refreshCategories(force = true).getOrThrow()
        assertEquals("", afterRefresh[0].slug)
        assertEquals("info", afterRefresh[1].slug)
        assertEquals("trade", afterRefresh[2].slug)
        assertFalse(afterRefresh.first { it.slug == "daily" }.visibleOnHome)

        val _getHomepage = server.takeRequest()
        val postHomepage = server.takeRequest()
        assertEquals("/api/homepage", postHomepage.path)
        assertTrue(postHomepage.body.readUtf8().contains(""""category":"daily","showInIndex":false"""))
        val listCategoriesReq = server.takeRequest()
        assertEquals("/api/content/list-categories", listCategoriesReq.path)
    }

    @Test
    fun `syncHomepageCategories propagates server order into settings and buildOrderedTabs even when Room is pre-seeded by onEmbeddedCategoriesObserved`() = runBlocking {
        // 1. Simulate fresh login where temp-script seeds Room via onEmbeddedCategoriesObserved
        // while settings.homepageCategoryOrder is still empty.
        val configB64 = encodeConfig(
            """{"user":{"member_id":101,"member_name":"Alice","coin":500},"allCategory":[{"key":"daily","cn_text":"日常"},{"key":"tech","cn_text":"技术"},{"key":"info","cn_text":"情报"},{"key":"review","cn_text":"测评"},{"key":"trade","cn_text":"交易"}]}""",
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$configB64</script></body></html>"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":[{"category":"review","showInIndex":true},{"category":"trade","showInIndex":true},{"category":"info","showInIndex":false}]}"""),
        )

        sessionRepository.refreshSession().getOrThrow()
        withTimeout(5_000L) {
            while (categoryDao.getCategories(101L).isEmpty()) {
                kotlinx.coroutines.delay(10L)
            }
        }
        assertTrue(settingsRepository.settings.value.homepageCategoryOrder.isEmpty())

        // 2. syncHomepageCategories must apply server order despite pre-seeded Room AND persist homepageCategoryOrder
        val synced = forumRepository.syncHomepageCategories().getOrThrow()
        assertEquals(listOf("", "review", "trade", "info", "daily", "tech"), synced.map { it.slug })
        assertEquals(listOf("review", "trade", "info", "daily", "tech"), settingsRepository.settings.value.homepageCategoryOrder)

        val tabs = com.nodeseek.app.ui.explore.ExploreViewModel.buildOrderedTabs(
            categories = synced,
            homepageCategoryOrder = settingsRepository.settings.value.homepageCategoryOrder,
            pinnedCategorySlugs = settingsRepository.settings.value.pinnedCategorySlugs,
        )
        assertEquals(listOf("", "review", "trade", "daily", "tech"), tabs.map { it.slug })
    }

    @Test
    fun `onEmbeddedCategoriesObserved cannot clobber syncHomepageCategories visibility under concurrent RMW interleaving`() = runBlocking {
        val allCategoriesWithTradeTrue = listOf(
            CategoryDto(key = "daily", cnText = "日常", customizable = true, showInIndex = true),
            CategoryDto(key = "tech", cnText = "技术", customizable = true, showInIndex = true),
            CategoryDto(key = "info", cnText = "情报", customizable = true, showInIndex = true),
            CategoryDto(key = "review", cnText = "测评", customizable = true, showInIndex = true),
            CategoryDto(key = "trade", cnText = "交易", customizable = true, showInIndex = true),
            CategoryDto(key = "carpool", cnText = "拼车", customizable = true, showInIndex = true),
        )
        val homepageJson = """{"success":true,"data":[{"category":"carpool","showInIndex":true},{"category":"info","showInIndex":true},{"category":"daily","showInIndex":true},{"category":"trade","showInIndex":false}]}"""

        // Direction A: onEmbeddedCategoriesObserved enters getCategories(202L) first while syncHomepageCategories is in-flight
        sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 202L, username = "Bob"))
        categoryDao.clearForAccount(202L)
        settingsRepository.setHomepageCategoryOrder(emptyList())

        val embeddedEnteredGet = CompletableDeferred<Unit>()
        val releaseEmbeddedGet = CompletableDeferred<Unit>()
        val homepageNetworkServed = CompletableDeferred<Unit>()
        val pausingDao = object : CategoryDao by categoryDao {
            override suspend fun getCategories(accountUid: Long): List<CategoryEntity> {
                val rows = categoryDao.getCategories(accountUid)
                if (accountUid == 202L && rows.isEmpty() && !embeddedEnteredGet.isCompleted) {
                    embeddedEnteredGet.complete(Unit)
                    releaseEmbeddedGet.await()
                }
                return rows
            }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/homepage") == true) {
                    homepageNetworkServed.complete(Unit)
                    return MockResponse().setResponseCode(200).setBody(homepageJson)
                }
                return MockResponse().setResponseCode(404)
            }
        }
        val bgScopeA = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoA = ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            categoryDao = pausingDao,
            readHistoryDao = readHistoryDao,
            bookmarkDao = bookmarkDao,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            backgroundScope = bgScopeA,
        )
        try {
            repoA.onEmbeddedCategoriesObserved(allCategoriesWithTradeTrue, 202L)
            withTimeout(5_000L) { embeddedEnteredGet.await() }

            val syncJob = async(Dispatchers.IO) { repoA.syncHomepageCategories().getOrThrow() }
            withTimeout(5_000L) { homepageNetworkServed.await() }
            releaseEmbeddedGet.complete(Unit)

            withTimeout(5_000L) {
                syncJob.await()
                bgScopeA.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            }

            val catsA = categoryDao.getCategories(202L)
            assertFalse("Direction A: trade must stay hidden", catsA.first { it.slug == "trade" }.visibleOnHome)
            assertTrue("Direction A: daily must stay visible", catsA.first { it.slug == "daily" }.visibleOnHome)
            assertEquals(listOf("", "carpool", "info", "daily", "trade"), catsA.take(5).map { it.slug })
        } finally {
            bgScopeA.cancel()
        }

        // Direction B: onEmbeddedCategoriesObserved is queued while syncHomepageCategories commits first, then embedded runs afterward
        categoryDao.clearForAccount(202L)
        settingsRepository.setHomepageCategoryOrder(emptyList())
        val gateDispatcher = StandardTestDispatcher()
        val bgScopeB = CoroutineScope(SupervisorJob() + gateDispatcher)
        val repoB = ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            categoryDao = categoryDao,
            readHistoryDao = readHistoryDao,
            bookmarkDao = bookmarkDao,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            backgroundScope = bgScopeB,
        )
        try {
            repoB.onEmbeddedCategoriesObserved(allCategoriesWithTradeTrue, 202L)
            repoB.syncHomepageCategories().getOrThrow()
            gateDispatcher.scheduler.advanceUntilIdle()
            withTimeout(5_000L) {
                bgScopeB.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            }

            val catsB = categoryDao.getCategories(202L)
            assertFalse("Direction B: trade must stay hidden", catsB.first { it.slug == "trade" }.visibleOnHome)
            assertTrue("Direction B: daily must stay visible", catsB.first { it.slug == "daily" }.visibleOnHome)
            assertEquals(listOf("", "carpool", "info", "daily", "trade"), catsB.take(5).map { it.slug })
        } finally {
            bgScopeB.cancel()
        }
    }

    @Test
    fun `stale pre-switch onEmbeddedCategoriesObserved and syncHomepageCategories do not mutate Room or settings after session switch`() = runBlocking {
        // Part A: stale pre-switch onEmbeddedCategoriesObserved for 101L released after switch to 202L
        sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 101L, username = "Alice"))
        val seeded101 = listOf(
            Category(slug = "", name = "全部", isCustomizable = false, visibleOnHome = true).toEntityCompat(101L, 0),
            Category(slug = "trade", name = "交易", isCustomizable = true, visibleOnHome = true).toEntityCompat(101L, 1),
            Category(slug = "tech", name = "技术", isCustomizable = true, visibleOnHome = true).toEntityCompat(101L, 2),
        )
        categoryDao.replaceForAccount(101L, seeded101)
        settingsRepository.setHomepageCategoryOrder(listOf("trade", "tech"))

        val gateDispatcher = StandardTestDispatcher()
        val bgScope = CoroutineScope(SupervisorJob() + gateDispatcher)
        val gatedRepo = ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            categoryDao = categoryDao,
            readHistoryDao = readHistoryDao,
            bookmarkDao = bookmarkDao,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            backgroundScope = bgScope,
        )
        try {
            gatedRepo.onEmbeddedCategoriesObserved(
                listOf(
                    CategoryDto(key = "daily", cnText = "日常", customizable = true, showInIndex = true),
                    CategoryDto(key = "tech", cnText = "技术", customizable = true, showInIndex = true),
                    CategoryDto(key = "trade", cnText = "交易", customizable = true, showInIndex = true),
                ),
                accountUid = 101L,
            )

            // Switch to 202L and clear global homepageCategoryOrder before queued 101L embedded task executes
            sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 202L, username = "Bob"))
            settingsRepository.setHomepageCategoryOrder(emptyList())

            gateDispatcher.scheduler.advanceUntilIdle()
            withTimeout(5_000L) {
                bgScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            }

            assertEquals(listOf("", "trade", "tech"), categoryDao.getCategories(101L).map { it.slug })
        } finally {
            bgScope.cancel()
        }

        // Part B: in-flight syncHomepageCategories for 101L completes after session switches to 202L
        sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 101L, username = "Alice"))
        settingsRepository.setHomepageCategoryOrder(emptyList())
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/homepage") == true) {
                    requestStarted.countDown()
                    releaseResponse.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"category":"daily","showInIndex":true},{"category":"tech","showInIndex":true}]}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val syncDeferred = async(Dispatchers.IO) { forumRepository.syncHomepageCategories() }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

        sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 202L, username = "Bob"))
        settingsRepository.setHomepageCategoryOrder(listOf("carpool"))
        releaseResponse.countDown()

        val result = withTimeout(5_000L) { syncDeferred.await() }
        assertTrue("Stale syncHomepageCategories must fail cleanly", result.isFailure)
        assertTrue(result.exceptionOrNull() is NodeSeekApiException)
        assertEquals(listOf("carpool"), settingsRepository.settings.value.homepageCategoryOrder)
        assertEquals(listOf("", "trade", "tech"), categoryDao.getCategories(101L).map { it.slug })
    }

    @Test
    fun `in-flight syncHomepageCategories does not overwrite newer local saveHomepageCategories`() = runBlocking {
        sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 101L, username = "Alice"))
        categoryDao.clearForAccount(101L)
        settingsRepository.setHomepageCategoryOrder(emptyList())

        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/homepage") == true) {
                    requestStarted.countDown()
                    releaseResponse.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"category":"trade","showInIndex":true},{"category":"tech","showInIndex":true}]}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val syncDeferred = async(Dispatchers.IO) { forumRepository.syncHomepageCategories() }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

        forumRepository.saveHomepageCategories(
            orderedCategories = listOf(
                Category(slug = "tech", name = "技术", isCustomizable = true, visibleOnHome = true),
                Category(slug = "trade", name = "交易", isCustomizable = true, visibleOnHome = false),
            ),
            syncToServer = false,
        ).getOrThrow()

        releaseResponse.countDown()
        val synced = withTimeout(5_000L) { syncDeferred.await().getOrThrow() }

        val stored101 = categoryDao.getCategories(101L)
        assertFalse(stored101.first { it.slug == "trade" }.visibleOnHome)
        assertFalse(synced.first { it.slug == "trade" }.visibleOnHome)
        assertEquals(listOf("tech", "trade"), settingsRepository.settings.value.homepageCategoryOrder)
    }

    @Test
    fun `stress test concurrent onEmbeddedCategoriesObserved and syncHomepageCategories across 30 rounds never leaks trade visibility`() = runBlocking {
        val configB64 = encodeConfig(
            """{"user":{"member_id":202,"member_name":"Bob","coin":300},"allCategory":[{"key":"daily","cn_text":"日常","customizable":true,"showInIndex":1},{"key":"tech","cn_text":"技术","customizable":true,"showInIndex":1},{"key":"info","cn_text":"情报","customizable":true,"showInIndex":1},{"key":"review","cn_text":"测评","customizable":true,"showInIndex":1},{"key":"trade","cn_text":"交易","customizable":true,"showInIndex":1},{"key":"carpool","cn_text":"拼车","customizable":true,"showInIndex":1}]}""",
        )
        val feedHtml = """<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$configB64</script></body></html>"""
        val hpJson = """{"success":true,"data":[{"category":"carpool","showInIndex":true},{"category":"info","showInIndex":true},{"category":"daily","showInIndex":true},{"category":"trade","showInIndex":false}]}"""

        val feedRequests = java.util.concurrent.atomic.AtomicInteger(0)
        val homepageRequests = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/homepage") -> {
                        homepageRequests.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody(hpJson)
                    }
                    path.startsWith("/") -> {
                        feedRequests.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody(feedHtml)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val stressClock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val stressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stressRepo = ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            categoryDao = categoryDao,
            readHistoryDao = readHistoryDao,
            bookmarkDao = bookmarkDao,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            backgroundScope = stressScope,
            clockMillis = { stressClock.get() },
        )
        sessionRepository.embeddedCategoriesListener = stressRepo::onEmbeddedCategoriesObserved
        try {
            repeat(30) { round ->
                stressClock.addAndGet(10_000L)
                sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 202L, username = "Bob"))
                categoryDao.clearForAccount(202L)
                settingsRepository.setHomepageCategoryOrder(emptyList())

                val feedJob = async(Dispatchers.IO) {
                    stressRepo.getFeed(categorySlug = "", page = 1, forceRefresh = true).getOrThrow()
                }
                val syncJob = async(Dispatchers.IO) {
                    stressRepo.syncHomepageCategories().getOrThrow()
                }
                withTimeout(5_000L) {
                    feedJob.await()
                    syncJob.await()
                    stressScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
                }

                val cats202 = categoryDao.getCategories(202L)
                assertFalse("Round $round leaked trade visibility", cats202.first { it.slug == "trade" }.visibleOnHome)
                assertTrue("Round $round lost daily visibility", cats202.first { it.slug == "daily" }.visibleOnHome)
            }
            assertEquals(30, feedRequests.get())
            assertEquals(30, homepageRequests.get())
        } finally {
            stressScope.cancel()
        }
    }

    @Test
    fun `saveHomepageCategories_midFlightSessionSwitch_abortsRemoteWrite`() = runBlocking {
        sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 101L, username = "Alice"))
        val homepageRequests = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/homepage") == true) {
                    homepageRequests.incrementAndGet()
                }
                return MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
        }

        val switchingCategoryDao = object : CategoryDao by categoryDao {
            override suspend fun replaceForAccount(accountUid: Long, categories: List<CategoryEntity>) {
                categoryDao.replaceForAccount(accountUid, categories)
                if (accountUid == 101L) {
                    // Simulate mid-flight session switch right after local save and before remote /api/homepage call
                    sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 202L, username = "Bob"))
                }
            }
        }
        val guardedRepo = ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            categoryDao = switchingCategoryDao,
            readHistoryDao = readHistoryDao,
            bookmarkDao = bookmarkDao,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        val res = guardedRepo.saveHomepageCategories(
            orderedCategories = listOf(
                Category(slug = "tech", name = "技术", isCustomizable = true, visibleOnHome = true),
            ),
            syncToServer = true,
        )
        assertTrue(res.isFailure)
        assertEquals(0, homepageRequests.get())
    }

    @Test
    fun `refreshCategories_midFlightSessionSwitch_rejectsStaleResponse`() = runBlocking {
        sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 101L, username = "Alice"))
        categoryDao.clearForAccount(101L)
        categoryDao.clearForAccount(202L)

        val reqStarted = CountDownLatch(1)
        val releaseResp = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/content/list-categories") == true) {
                    reqStarted.countDown()
                    releaseResp.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"key":"tech","cn_text":"技术"}]}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val refreshDeferred = async(Dispatchers.IO) { forumRepository.refreshCategories(force = true) }
        assertTrue(reqStarted.await(5, TimeUnit.SECONDS))

        sessionRepository.applyOptimisticSwitchedSession(AccountSession(uid = 202L, username = "Bob"))
        releaseResp.countDown()

        val result = withTimeout(5_000L) { refreshDeferred.await() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("账号会话已切换") == true)
        assertTrue(categoryDao.getCategories(101L).isEmpty())
        assertTrue(categoryDao.getCategories(202L).isEmpty())
    }

    @Test
    fun `refreshCategories_concurrentTwoUids_isolatesStorage`() = runBlocking {
        val sessA = SessionRepositoryImpl(apiClientProvider = { apiClient }, cookieBridge = cookieBridge)
        val sessB = SessionRepositoryImpl(apiClientProvider = { apiClient }, cookieBridge = cookieBridge)
        sessA.applyOptimisticSwitchedSession(AccountSession(uid = 101L, username = "Alice"))
        sessB.applyOptimisticSwitchedSession(AccountSession(uid = 202L, username = "Bob"))
        categoryDao.clearForAccount(101L)
        categoryDao.clearForAccount(202L)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/content/list-categories") == true) {
                    return MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"key":"tech","cn_text":"技术"},{"key":"trade","cn_text":"交易"}]}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repoA = ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessA,
            settingsRepository = settingsRepository,
            categoryDao = categoryDao,
            readHistoryDao = readHistoryDao,
            bookmarkDao = bookmarkDao,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        val repoB = ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessB,
            settingsRepository = settingsRepository,
            categoryDao = categoryDao,
            readHistoryDao = readHistoryDao,
            bookmarkDao = bookmarkDao,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        val dA = async(Dispatchers.IO) { repoA.refreshCategories(force = true) }
        val dB = async(Dispatchers.IO) { repoB.refreshCategories(force = true) }
        assertTrue(dA.await().isSuccess)
        assertTrue(dB.await().isSuccess)
        assertTrue(categoryDao.getCategories(101L).any { it.slug == "trade" })
        assertTrue(categoryDao.getCategories(202L).any { it.slug == "trade" })
        assertTrue(categoryDao.getCategories(0L).isEmpty())
    }

    private fun Category.toEntityCompat(accountUid: Long, sortOrder: Int): CategoryEntity =
        CategoryEntity(
            accountUid = accountUid,
            slug = slug,
            name = name,
            description = description,
            sortOrder = sortOrder,
            isCustomizable = isCustomizable,
            visibleOnHome = visibleOnHome,
            isAdminOnly = isAdminOnly,
            topicCoinCost = topicCoinCost,
            supportsRss = supportsRss,
            syncedAtEpochMillis = 1_000L,
        )
}

// =========================================================================
// Lightweight In-Memory Test Doubles for Room DAOs & SettingsRepository
// =========================================================================

internal class FakeSettingsRepository : SettingsRepository {
    private val _settings = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = _settings
    private val _recentSearchQueries = MutableStateFlow<List<String>>(emptyList())
    override val recentSearchQueries: StateFlow<List<String>> = _recentSearchQueries

    override suspend fun setThemeMode(mode: ThemeMode) {
        _settings.value = _settings.value.copy(themeMode = mode)
    }
    override suspend fun setPurePitchAmoled(enabled: Boolean) {
        _settings.value = _settings.value.copy(purePitchAmoled = enabled)
    }
    override suspend fun setUseDynamicColor(enabled: Boolean) {
        _settings.value = _settings.value.copy(useDynamicColor = enabled)
    }
    override suspend fun setFontScale(scale: Float) {
        _settings.value = _settings.value.copy(fontScale = scale)
    }
    override suspend fun setHomeCardScale(scale: Float) {
        _settings.value = _settings.value.copy(homeCardScale = scale)
    }
    override suspend fun setPreferRandomCheckIn(preferRandom: Boolean) {
        _settings.value = _settings.value.copy(preferRandomCheckIn = preferRandom)
    }
    override suspend fun setPinnedCategorySlugs(slugs: Set<String>) {
        _settings.value = _settings.value.copy(pinnedCategorySlugs = slugs)
    }
    override suspend fun setHomepageCategoryOrder(slugs: List<String>) {
        _settings.value = _settings.value.copy(
            homepageCategoryOrder = slugs.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }
    override suspend fun togglePinnedCategory(slug: String) = Unit
    override suspend fun setIncludeBlockedTopics(include: Boolean) {
        _settings.value = _settings.value.copy(includeBlockedTopics = include)
    }
    override suspend fun setPreferRssFallback(preferRss: Boolean) {
        _settings.value = _settings.value.copy(preferRssFallback = preferRss)
    }
    override suspend fun setNodeImageApiKey(apiKey: String) {
        _settings.value = _settings.value.copy(nodeImageApiKey = apiKey)
    }
    override suspend fun setSeekMailApiKey(apiKey: String) {
        _settings.value = _settings.value.copy(seekMailApiKey = apiKey.trim())
    }
    override suspend fun setAutoCheckUpdates(enabled: Boolean) {
        _settings.value = _settings.value.copy(autoCheckUpdates = enabled)
    }
    override suspend fun setIncludePrereleaseUpdates(enabled: Boolean) {
        _settings.value = _settings.value.copy(includePrereleaseUpdates = enabled)
    }
    override suspend fun setIgnoredUpdateTag(tag: String) {
        _settings.value = _settings.value.copy(ignoredUpdateTag = tag.trim())
    }
    override suspend fun setAwayDigestEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(awayDigestEnabled = enabled)
    }
    override suspend fun setAwayDigestTriggerMode(mode: AwayDigestTriggerMode) {
        _settings.value = _settings.value.copy(awayDigestTriggerMode = mode)
    }
    override suspend fun setAwayDigestThresholdMinutes(minutes: Int) {
        _settings.value = _settings.value.copy(
            awayDigestThresholdMinutes = minutes.coerceIn(
                AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
                AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
            ),
        )
    }
    override suspend fun addRecentSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        _recentSearchQueries.value = (listOf(trimmed) + _recentSearchQueries.value)
            .distinctBy { it.lowercase() }
            .take(SettingsRepositoryImpl.MAX_RECENT_SEARCHES)
    }
    override suspend fun removeRecentSearchQuery(query: String) {
        _recentSearchQueries.value = _recentSearchQueries.value.filterNot { it == query }
    }
    override suspend fun clearRecentSearchQueries() {
        _recentSearchQueries.value = emptyList()
    }
    override suspend fun exportSettingsForBackup(includeApiKeys: Boolean): AppSettingsBackupDto {
        val cur = _settings.value
        return AppSettingsBackupDto(
            themeMode = cur.themeMode,
            purePitchAmoled = cur.purePitchAmoled,
            useDynamicColor = cur.useDynamicColor,
            fontScale = cur.fontScale,
            homeCardScale = cur.homeCardScale,
            preferRandomCheckIn = cur.preferRandomCheckIn,
            pinnedCategorySlugs = cur.pinnedCategorySlugs,
            homepageCategoryOrder = cur.homepageCategoryOrder,
            includeBlockedTopics = cur.includeBlockedTopics,
            preferRssFallback = cur.preferRssFallback,
            nodeImageApiKey = if (includeApiKeys) cur.nodeImageApiKey else "",
            seekMailApiKey = if (includeApiKeys) cur.seekMailApiKey else "",
            autoCheckUpdates = cur.autoCheckUpdates,
            includePrereleaseUpdates = cur.includePrereleaseUpdates,
            ignoredUpdateTag = cur.ignoredUpdateTag,
            awayDigestEnabled = cur.awayDigestEnabled,
            awayDigestTriggerMode = cur.awayDigestTriggerMode,
            awayDigestThresholdMinutes = cur.awayDigestThresholdMinutes,
            recentSearchQueries = _recentSearchQueries.value,
        )
    }
    override suspend fun restoreSettingsFromBackup(backup: AppSettingsBackupDto, merge: Boolean) {
        val cur = _settings.value
        val nodeKey = if (merge && backup.nodeImageApiKey.isBlank() && cur.nodeImageApiKey.isNotBlank()) {
            cur.nodeImageApiKey
        } else {
            backup.nodeImageApiKey.trim()
        }
        val mailKey = if (merge && backup.seekMailApiKey.isBlank() && cur.seekMailApiKey.isNotBlank()) {
            cur.seekMailApiKey
        } else {
            backup.seekMailApiKey.trim()
        }
        val ignored = if (merge && backup.ignoredUpdateTag.isBlank() && cur.ignoredUpdateTag.isNotBlank()) {
            cur.ignoredUpdateTag
        } else {
            backup.ignoredUpdateTag.trim()
        }
        val resolvedOrder = if (merge && cur.homepageCategoryOrder.isNotEmpty()) {
            (cur.homepageCategoryOrder + backup.homepageCategoryOrder.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
        } else {
            backup.homepageCategoryOrder.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }
        _settings.value = AppSettings(
            themeMode = backup.themeMode,
            purePitchAmoled = backup.purePitchAmoled,
            useDynamicColor = backup.useDynamicColor,
            fontScale = backup.fontScale.coerceIn(SettingsRepositoryImpl.MIN_FONT_SCALE, SettingsRepositoryImpl.MAX_FONT_SCALE),
            homeCardScale = backup.homeCardScale.coerceIn(SettingsRepositoryImpl.MIN_HOME_CARD_SCALE, SettingsRepositoryImpl.MAX_HOME_CARD_SCALE),
            preferRandomCheckIn = backup.preferRandomCheckIn,
            pinnedCategorySlugs = backup.pinnedCategorySlugs.map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet()),
            homepageCategoryOrder = resolvedOrder,
            includeBlockedTopics = backup.includeBlockedTopics,
            preferRssFallback = backup.preferRssFallback,
            nodeImageApiKey = nodeKey,
            seekMailApiKey = mailKey,
            autoCheckUpdates = backup.autoCheckUpdates,
            includePrereleaseUpdates = backup.includePrereleaseUpdates,
            ignoredUpdateTag = ignored,
            awayDigestEnabled = backup.awayDigestEnabled,
            awayDigestTriggerMode = backup.awayDigestTriggerMode,
            awayDigestThresholdMinutes = backup.awayDigestThresholdMinutes.coerceIn(
                AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
                AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
            ),
        )
        _recentSearchQueries.value = (
            if (merge) backup.recentSearchQueries + _recentSearchQueries.value else backup.recentSearchQueries
            )
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(SettingsRepositoryImpl.MAX_RECENT_SEARCHES)
    }
}

internal class FakeCategoryDao : CategoryDao {
    private val state = MutableStateFlow<List<CategoryEntity>>(emptyList())

    override fun observeCategories(accountUid: Long): Flow<List<CategoryEntity>> =
        state.map { list -> list.filter { it.accountUid == accountUid }.sortedBy { it.sortOrder } }

    override suspend fun getCategories(accountUid: Long): List<CategoryEntity> =
        state.value.filter { it.accountUid == accountUid }.sortedBy { it.sortOrder }

    override suspend fun getLastSyncedAtMillis(accountUid: Long): Long? =
        getCategories(accountUid).maxOfOrNull { it.syncedAtEpochMillis }

    override suspend fun upsertAll(entities: List<CategoryEntity>) {
        synchronized(this) {
            val updated = state.value.toMutableList()
            for (entity in entities) {
                updated.removeAll { it.accountUid == entity.accountUid && it.slug == entity.slug }
                updated.add(entity)
            }
            state.value = updated
        }
    }

    override suspend fun clearForAccount(accountUid: Long) {
        synchronized(this) {
            state.value = state.value.filterNot { it.accountUid == accountUid }
        }
    }

    override suspend fun replaceForAccount(accountUid: Long, categories: List<CategoryEntity>) {
        synchronized(this) {
            state.value = state.value.filterNot { it.accountUid == accountUid } + categories
        }
    }
}

internal class FakeReadHistoryDao : ReadHistoryDao {
    private val state = MutableStateFlow<List<ReadHistoryEntity>>(emptyList())

    override fun observeHistory(accountUid: Long, limit: Int): Flow<List<ReadHistoryEntity>> =
        state.map { rows ->
            rows.filter { it.accountUid == accountUid }
                .sortedByDescending { it.visitedAtEpochMillis }
                .take(limit)
        }

    override fun observeByTopicIds(
        accountUid: Long,
        topicIds: Collection<Long>,
    ): Flow<List<ReadHistoryEntity>> =
        state.map { rows ->
            rows.filter { it.accountUid == accountUid && it.topicId in topicIds }
        }

    override suspend fun getByTopicIds(
        accountUid: Long,
        topicIds: Collection<Long>,
    ): List<ReadHistoryEntity> =
        state.value.filter { it.accountUid == accountUid && it.topicId in topicIds }

    override suspend fun getByTopicId(accountUid: Long, topicId: Long): ReadHistoryEntity? =
        state.value.firstOrNull { it.accountUid == accountUid && it.topicId == topicId }

    override suspend fun upsert(entity: ReadHistoryEntity) {
        val next = state.value.filterNot {
            it.accountUid == entity.accountUid && it.topicId == entity.topicId
        } + entity
        state.value = next
    }

    override suspend fun deleteByTopicId(accountUid: Long, topicId: Long) {
        state.value = state.value.filterNot { it.accountUid == accountUid && it.topicId == topicId }
    }

    override suspend fun clearForAccount(accountUid: Long) {
        state.value = state.value.filterNot { it.accountUid == accountUid }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}

internal class FakeBookmarkDao : BookmarkDao {
    private val state = MutableStateFlow<List<BookmarkEntity>>(emptyList())

    override fun observeBookmarks(accountUid: Long): Flow<List<BookmarkEntity>> =
        state.map { rows ->
            rows.filter { it.accountUid == accountUid }.sortedByDescending { it.savedAtEpochMillis }
        }

    override fun observeBookmarkedTopicIds(accountUid: Long): Flow<List<Long>> =
        observeBookmarks(accountUid).map { rows -> rows.map { it.topicId } }

    override suspend fun getBookmarkedTopicIds(accountUid: Long): List<Long> =
        state.value
            .filter { it.accountUid == accountUid }
            .sortedByDescending { it.savedAtEpochMillis }
            .map { it.topicId }

    override suspend fun getBookmark(accountUid: Long, topicId: Long): BookmarkEntity? =
        state.value.firstOrNull { it.accountUid == accountUid && it.topicId == topicId }

    override suspend fun upsert(entity: BookmarkEntity) {
        upsertAll(listOf(entity))
    }

    override suspend fun upsertAll(entities: List<BookmarkEntity>) {
        val next = state.value.toMutableList()
        for (entity in entities) {
            next.removeAll { it.accountUid == entity.accountUid && it.topicId == entity.topicId }
            next.add(entity)
        }
        state.value = next
    }

    override suspend fun updateOfflineSnapshot(
        accountUid: Long,
        topicId: Long,
        offlineDetailJson: String?,
    ) {
        val existing = getBookmark(accountUid, topicId) ?: return
        upsert(existing.copy(offlineDetailJson = offlineDetailJson))
    }

    override suspend fun deleteBookmark(accountUid: Long, topicId: Long) {
        state.value = state.value.filterNot { it.accountUid == accountUid && it.topicId == topicId }
    }

    override suspend fun clearForAccount(accountUid: Long) {
        state.value = state.value.filterNot { it.accountUid == accountUid }
    }

    override suspend fun getAllBookmarks(): List<BookmarkEntity> =
        state.value.sortedByDescending { it.savedAtEpochMillis }

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}
