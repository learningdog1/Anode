package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.CachedFeedSnapshot
import com.nodeseek.app.data.local.FeedDiskCache
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.TopicSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class FeedCacheAndRateLimitTest {

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private var fakeClockMillis: Long = 10_000L
    private lateinit var apiClient: NodeSeekApiClient
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var forumRepository: ForumRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = Files.createTempDirectory("feed_cache_rate_limit_test").toFile()
        fakeClockMillis = 10_000L

        val baseUrl = server.url("/")
        val cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("NodeSeekRepoTest/1.0"),
            additionalTargetHost = baseUrl.host,
        )

        val settingsRepository = FakeSettingsRepository()
        val categoryDao = FakeCategoryDao()
        val readHistoryDao = FakeReadHistoryDao()
        val bookmarkDao = FakeBookmarkDao()

        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        val bookmarkAndHistoryRepository = BookmarkAndHistoryRepositoryImpl(
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
            feedDiskCache = FeedDiskCache(cacheDir = cacheDir),
            clockMillis = { fakeClockMillis },
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
        cacheDir.deleteRecursively()
    }

    private fun sampleFeedHtml(
        categorySlug: String,
        topicId: Long,
        title: String,
    ): String {
        val configB64 = Base64.getEncoder().encodeToString(
            """
            {
              "enableCustomedStyle": 1,
              "allCategory": [
                { "key": "tech", "cn_text": "技术" },
                { "key": "daily", "cn_text": "日常" },
                { "key": "trade", "cn_text": "交易" }
              ]
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        return """
        <html><body>
          <div id="nsk-body">
            <div class="post-list-item">
              <div class="post-title"><a href="/post-$topicId-1">$title</a></div>
              <a class="post-category" href="/categories/$categorySlug">$categorySlug</a>
              <span class="info-author"><a href="/space/101">Tester</a></span>
              <span class="info-views"><span>120</span></span>
              <span class="info-comments-count"><span>8</span></span>
            </div>
          </div>
          <script id="temp-script" type="application/json">$configB64</script>
        </body></html>
        """.trimIndent()
    }

    @Test
    fun `feedDiskCache persists snapshots across cold start and prunes oldest entries beyond maxEntries`() = runBlocking {
        val dir = Files.createTempDirectory("feed_disk_cache_cold_start").toFile()
        try {
            val cache = FeedDiskCache(cacheDir = dir, maxEntries = 2)

            fun snapshotFor(id: Long, slug: String, fetchedAt: Long) = CachedFeedSnapshot(
                fetchedAtEpochMillis = fetchedAt,
                result = FeedPageResult(
                    topics = listOf(
                        TopicSummary(
                            id = id,
                            title = "帖子 #$id",
                            categorySlug = slug,
                            categoryName = slug,
                            authorName = "Tester",
                        ),
                    ),
                    currentPage = 1,
                    hasNextPage = true,
                ),
            )

            cache.persistNowForTest(0L, "daily", FeedSort.LATEST_REPLY, snapshotFor(1L, "daily", 1_000L))
            File(dir, "${FeedDiskCache.buildCacheKey(0L, "daily", FeedSort.LATEST_REPLY)}.json").setLastModified(1_000L)

            cache.persistNowForTest(0L, "tech", FeedSort.LATEST_REPLY, snapshotFor(2L, "tech", 2_000L))
            File(dir, "${FeedDiskCache.buildCacheKey(0L, "tech", FeedSort.LATEST_REPLY)}.json").setLastModified(2_000L)

            cache.persistNowForTest(0L, "info", FeedSort.LATEST_REPLY, snapshotFor(3L, "info", 3_000L))

            val coldRestartCache = FeedDiskCache(cacheDir = dir, maxEntries = 2)
            assertNull(coldRestartCache.get(0L, "daily", FeedSort.LATEST_REPLY))

            val techRestored = coldRestartCache.get(0L, "tech", FeedSort.LATEST_REPLY)
            assertNotNull(techRestored)
            assertEquals("帖子 #2", techRestored!!.result.topics.single().title)

            val infoRestored = coldRestartCache.get(0L, "info", FeedSort.LATEST_REPLY)
            assertNotNull(infoRestored)
            assertEquals("帖子 #3", infoRestored!!.result.topics.single().title)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `singleFlightFeed coalesces concurrent identical getFeed calls into a single HTTP request`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBodyDelay(150, TimeUnit.MILLISECONDS)
                .setBody(sampleFeedHtml(categorySlug = "tech", topicId = 601L, title = "并发去重测试帖")),
        )

        val results = (1..4).map {
            async(Dispatchers.IO) {
                forumRepository.getFeed(
                    categorySlug = "tech",
                    sort = FeedSort.LATEST_REPLY,
                    page = 1,
                )
            }
        }.awaitAll()

        results.forEach { result ->
            assertTrue(result.isSuccess)
            assertEquals(601L, result.getOrThrow().topics.single().id)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `forceRefresh respects 4s polite cooldown and serves cached snapshot without hitting server`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sampleFeedHtml(categorySlug = "daily", topicId = 701L, title = "首次加载帖子")),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sampleFeedHtml(categorySlug = "daily", topicId = 702L, title = "冷却后最新帖子")),
        )

        fakeClockMillis = 10_000L
        val first = forumRepository.getFeed(
            categorySlug = "daily",
            sort = FeedSort.LATEST_REPLY,
            forceRefresh = true,
        ).getOrThrow()
        assertEquals(701L, first.topics.single().id)
        assertEquals(1, server.requestCount)

        fakeClockMillis = 12_500L
        val duringCooldown = forumRepository.getFeed(
            categorySlug = "daily",
            sort = FeedSort.LATEST_REPLY,
            forceRefresh = true,
        ).getOrThrow()
        assertEquals(701L, duringCooldown.topics.single().id)
        assertEquals(1, server.requestCount)

        fakeClockMillis = 15_000L
        val afterCooldown = forumRepository.getFeed(
            categorySlug = "daily",
            sort = FeedSort.LATEST_REPLY,
            forceRefresh = true,
        ).getOrThrow()
        assertEquals(702L, afterCooldown.topics.single().id)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `HTTP 429 Retry-After triggers circuit breaker and fails fast without upstream hit`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "15")
                .setBody("Too Many Requests"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sampleFeedHtml(categorySlug = "trade", topicId = 801L, title = "限流恢复后首帖")),
        )

        fakeClockMillis = 20_000L
        val first = forumRepository.getFeed(
            categorySlug = "trade",
            sort = FeedSort.LATEST_REPLY,
        )
        assertTrue(first.isFailure)
        assertEquals(1, server.requestCount)

        fakeClockMillis = 25_000L
        val duringBackoff = forumRepository.getFeed(
            categorySlug = "trade",
            sort = FeedSort.LATEST_REPLY,
            forceRefresh = true,
        )
        assertTrue(duringBackoff.isFailure)
        assertEquals(1, server.requestCount)

        fakeClockMillis = 36_000L
        val afterBackoff = forumRepository.getFeed(
            categorySlug = "trade",
            sort = FeedSort.LATEST_REPLY,
            forceRefresh = true,
        )
        assertTrue(afterBackoff.isSuccess)
        assertEquals(801L, afterBackoff.getOrThrow().topics.single().id)
        assertEquals(2, server.requestCount)
    }
}
