package com.nodeseek.app.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nodeseek.app.data.local.CachedFeedSnapshot
import com.nodeseek.app.data.local.DataStoreBlockedUsersCache
import com.nodeseek.app.data.local.FeedDiskCache
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.TopicSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MultiAccountBlockedAndFeedIsolationTest {

    private lateinit var tempDir: File
    private lateinit var dsScope: CoroutineScope
    private lateinit var repoScope: CoroutineScope
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("multi_acct_isolation_test").toFile()
        dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        repoScope.cancel()
        dsScope.cancel()
        tempDir.deleteRecursively()
    }

    private fun buildApiClient(
        cookieBridge: WebViewCookieBridge,
        sessionRepo: SessionRepositoryImpl,
    ): NodeSeekApiClient {
        val baseUrl = server.url("/")
        val uaProvider = UserAgentProvider("AnodeTestAgent/1.0")
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = uaProvider,
            additionalTargetHost = baseUrl.host,
        )
        return NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
            onConfigObserved = sessionRepo::onConfigPayloadObserved,
        )
    }

    @Test
    fun `signOutLocalOnly clears signed-out account DataStoreBlockedUsersCache via clearForUid without clearing guest or other accounts`() =
        runBlocking {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { File(tempDir, "blocked_users.preferences_pb") },
            )
            val cookieBridge = WebViewCookieBridge(forceInMemory = true)
            lateinit var apiClient: NodeSeekApiClient
            val sessionRepo = SessionRepositoryImpl(
                apiClientProvider = { apiClient },
                cookieBridge = cookieBridge,
            )
            apiClient = buildApiClient(cookieBridge, sessionRepo)

            val blockedCache = DataStoreBlockedUsersCache(
                dataStore = dataStore,
                accountUidProvider = { sessionRepo.sessionState.value?.uid ?: 0L },
            )
            sessionRepo.onSignedOutWithUidCallback = { signedOutUid ->
                runBlocking {
                    blockedCache.clearForUid(signedOutUid)
                }
            }

            // Guest (0L) has a blocked entry
            blockedCache.replaceAll(listOf(FloorAuthor(uid = 9001L, username = "guest_blocked")))
            // Account Bob (202L) has a blocked entry
            sessionRepo.applyOptimisticSwitchedSession(AccountSession(uid = 202L, username = "Bob"))
            blockedCache.replaceAll(listOf(FloorAuthor(uid = 9002L, username = "bob_blocked")))
            // Account Alice (101L) has a blocked entry
            sessionRepo.applyOptimisticSwitchedSession(AccountSession(uid = 101L, username = "Alice"))
            blockedCache.replaceAll(listOf(FloorAuthor(uid = 9003L, username = "alice_blocked")))

            // Alice signs out -> Alice's uid=101 cache must be cleared, Guest (0L) & Bob (202L) must remain
            sessionRepo.signOutLocalOnly()
            assertEquals(listOf("guest_blocked"), blockedCache.getBlockedUsers().map { it.username })

            sessionRepo.applyOptimisticSwitchedSession(AccountSession(uid = 101L, username = "Alice"))
            assertTrue("Signed-out Alice blocklist must not survive re-sign-in", blockedCache.getBlockedUsers().isEmpty())

            sessionRepo.applyOptimisticSwitchedSession(AccountSession(uid = 202L, username = "Bob"))
            assertEquals(listOf("bob_blocked"), blockedCache.getBlockedUsers().map { it.username })
        }

    @Test
    fun `getLocalCachedTopics strictly isolates inside and per-uid cached topics across accounts`() =
        runBlocking {
            val feedCacheDir = File(tempDir, "feed_disk")
            val feedDiskCache = FeedDiskCache(
                cacheDir = feedCacheDir,
                ioScope = repoScope,
            )
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { File(tempDir, "settings.preferences_pb") },
            )
            val settingsRepo = SettingsRepositoryImpl(dataStore = dataStore, externalScope = repoScope)
            val cookieBridge = WebViewCookieBridge(forceInMemory = true)
            lateinit var apiClient: NodeSeekApiClient
            val sessionRepo = SessionRepositoryImpl(
                apiClientProvider = { apiClient },
                cookieBridge = cookieBridge,
            )
            apiClient = buildApiClient(cookieBridge, sessionRepo)

            val categoryDao = FakeCategoryDao()
            val readHistoryDao = FakeReadHistoryDao()
            val bookmarkDao = FakeBookmarkDao()
            val bookmarkRepo = BookmarkAndHistoryRepositoryImpl(
                apiClientProvider = { apiClient },
                sessionRepository = sessionRepo,
                bookmarkDao = bookmarkDao,
                readHistoryDao = readHistoryDao,
            )
            val forumRepo = ForumRepositoryImpl(
                apiClientProvider = { apiClient },
                sessionRepository = sessionRepo,
                settingsRepository = settingsRepo,
                categoryDao = categoryDao,
                readHistoryDao = readHistoryDao,
                bookmarkDao = bookmarkDao,
                bookmarkAndHistoryRepository = bookmarkRepo,
                feedDiskCache = feedDiskCache,
                backgroundScope = repoScope,
            )

            // Persist Alice (uid=1001L) private inside feed snapshot to disk & memory
            sessionRepo.applyOptimisticSwitchedSession(AccountSession(uid = 1001L, username = "Alice"))
            val aliceTopic = TopicSummary(
                id = 88001L,
                title = "ALICE_PRIVATE_INSIDE_THREAD",
                categorySlug = "inside",
                categoryName = "内版",
                authorId = 1001L,
                authorName = "Alice",
            )
            feedDiskCache.put(
                accountUid = 1001L,
                categorySlug = "inside",
                sort = FeedSort.NEWEST_TOPIC,
                snapshot = CachedFeedSnapshot(
                    fetchedAtEpochMillis = 1_700_000_000_000L,
                    result = FeedPageResult(topics = listOf(aliceTopic), currentPage = 1, hasNextPage = false),
                ),
            )
            delay(100L)
            assertEquals(listOf("ALICE_PRIVATE_INSIDE_THREAD"), forumRepo.getLocalCachedTopics("inside").map { it.title })

            // Switch to Bob (uid=2002L): must NOT see Alice's cached topic
            sessionRepo.applyOptimisticSwitchedSession(AccountSession(uid = 2002L, username = "Bob"))
            val bobInside = forumRepo.getLocalCachedTopics("inside")
            assertFalse(
                "Bob must not see Alice's inside topics: ${bobInside.map { it.title }}",
                bobInside.any { it.title == "ALICE_PRIVATE_INSIDE_THREAD" },
            )
            val bobAll = forumRepo.getLocalCachedTopics("")
            assertFalse(
                "Bob getLocalCachedTopics(\"\") must not leak Alice's topics",
                bobAll.any { it.title == "ALICE_PRIVATE_INSIDE_THREAD" },
            )

            // Sign out to Guest (uid=0L): must NOT see Alice's cached topic either
            sessionRepo.signOutLocalOnly()
            val guestInside = forumRepo.getLocalCachedTopics("inside")
            assertTrue("Guest must not see Alice's cached inside topics", guestInside.isEmpty())
            assertTrue("Guest must not see Alice's topics in all-feed", forumRepo.getLocalCachedTopics("").isEmpty())
        }
}
