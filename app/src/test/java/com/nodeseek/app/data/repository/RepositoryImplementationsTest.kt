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
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.domain.backup.AppSettingsBackupDto
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        assertEquals(0.90f, settingsRepository.settings.value.fontScale, 0.0001f)

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

            // Font scale clamps to [0.75f, 1.30f]
            assertEquals(0.75f, SettingsRepositoryImpl.MIN_FONT_SCALE, 0.0001f)
            assertEquals(1.30f, SettingsRepositoryImpl.MAX_FONT_SCALE, 0.0001f)
            repo.setFontScale(0.50f)
            val clampedLow = withTimeout(5_000L) { repo.settings.first { it.fontScale == 0.75f } }
            assertEquals(0.75f, clampedLow.fontScale, 0.0001f)
            repo.setFontScale(1.50f)
            val clamped = withTimeout(5_000L) { repo.settings.first { it.fontScale == 1.30f } }
            assertEquals(1.30f, clamped.fontScale, 0.0001f)

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
    override suspend fun setPreferRandomCheckIn(preferRandom: Boolean) {
        _settings.value = _settings.value.copy(preferRandomCheckIn = preferRandom)
    }
    override suspend fun setPinnedCategorySlugs(slugs: Set<String>) {
        _settings.value = _settings.value.copy(pinnedCategorySlugs = slugs)
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
            preferRandomCheckIn = cur.preferRandomCheckIn,
            pinnedCategorySlugs = cur.pinnedCategorySlugs,
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
        _settings.value = AppSettings(
            themeMode = backup.themeMode,
            purePitchAmoled = backup.purePitchAmoled,
            useDynamicColor = backup.useDynamicColor,
            fontScale = backup.fontScale.coerceIn(SettingsRepositoryImpl.MIN_FONT_SCALE, SettingsRepositoryImpl.MAX_FONT_SCALE),
            preferRandomCheckIn = backup.preferRandomCheckIn,
            pinnedCategorySlugs = backup.pinnedCategorySlugs.map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet()),
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
        val updated = state.value.toMutableList()
        for (entity in entities) {
            updated.removeAll { it.accountUid == entity.accountUid && it.slug == entity.slug }
            updated.add(entity)
        }
        state.value = updated
    }

    override suspend fun clearForAccount(accountUid: Long) {
        state.value = state.value.filterNot { it.accountUid == accountUid }
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
