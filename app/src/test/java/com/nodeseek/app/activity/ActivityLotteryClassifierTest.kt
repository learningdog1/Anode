package com.nodeseek.app.activity

import com.nodeseek.app.data.activity.ForumWatchActivityApiClient
import com.nodeseek.app.data.activity.ForumWatchActivityRepositoryImpl
import com.nodeseek.app.data.overseas.OverseasApiCrypto
import com.nodeseek.app.domain.activity.ActivityCounts
import com.nodeseek.app.domain.activity.ActivityKind
import com.nodeseek.app.domain.activity.ActivityLabeledLink
import com.nodeseek.app.domain.activity.ActivityLotteryClassifier
import com.nodeseek.app.domain.activity.ActivityTimeStatus
import com.nodeseek.app.domain.activity.ForumWatchActivityItem
import com.nodeseek.app.ui.activity.formatLotteryTimingLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
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
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityLotteryClassifierTest {

    private lateinit var tempDir: File
    private lateinit var mockServer: MockWebServer

    private val testSecretBytes = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        .toByteArray(Charsets.UTF_8)
    private val testAesKey = OverseasApiCrypto.deriveAesKey(testSecretBytes)

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("activity_lottery_classifier_test").toFile()
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        runCatching { mockServer.shutdown() }
        tempDir.deleteRecursively()
    }

    @Test
    fun `explicit lottery URL matcher accepts real NodeSeek and draw-platform links and rejects non-lottery URLs`() {
        // Accepted explicit lottery links
        assertTrue(
            ActivityLotteryClassifier.isExplicitLotteryUrl(
                "https://www.nodeseek.com/lucky?post=1073388&time=1795438800000&count=1&start=1&duplicate=false&mode=view",
            ),
        )
        assertTrue(
            ActivityLotteryClassifier.isExplicitLotteryUrl(
                "https://nodeseek.com/lucky?post=1071664&amp;time=1795406400000&amp;count=3",
            ),
        )
        assertTrue(ActivityLotteryClassifier.isExplicitLotteryUrl("https://lucky.nodeseek.com/?post=618001"))
        assertTrue(ActivityLotteryClassifier.isExplicitLotteryUrl("https://lucky.nodeseek.com/draw/618001"))
        assertTrue(ActivityLotteryClassifier.isExplicitLotteryUrl("https://lottery.linux.do/draw/42"))
        assertTrue(ActivityLotteryClassifier.isExplicitLotteryUrl("https://www.linux.do/lucky?topic=42"))
        assertTrue(ActivityLotteryClassifier.isExplicitLotteryUrl("https://gleam.io/abc12/nodeseek-vps"))
        assertTrue(ActivityLotteryClassifier.isExplicitLotteryUrl("https://choujiang.org/r/9988"))

        // Rejected non-lottery or bare tool URLs
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl(null))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl(""))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://www.nodeseek.com/lucky"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://www.nodeseek.com/lucky/"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://www.nodeseek.com/lucky?utm_source=forum"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://lucky.nodeseek.com/"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://www.nodeseek.com/post-1071327-1"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://www.nodeseek.com/post-1070353-3#30"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://linux.do/t/topic/42"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://t.me/kotinetworks"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://github.com/user/nodeseek-lottery-script"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("https://gleam.io/"))
        assertFalse(ActivityLotteryClassifier.isExplicitLotteryUrl("javascript:alert(1)"))
    }

    @Test
    fun `extracts draw timestamp from lottery URL in millis, seconds, HTML-escaped query, and ISO-8601`() {
        assertEquals(
            1_795_438_800_000L,
            ActivityLotteryClassifier.extractDrawTimeEpochMillis(
                "https://www.nodeseek.com/lucky?post=1073388&time=1795438800000&count=1&start=1&duplicate=false&mode=view",
            ),
        )
        assertEquals(
            1_795_438_800_000L,
            ActivityLotteryClassifier.extractDrawTimeEpochMillis(
                "https://www.nodeseek.com/lucky?post=1073388&amp;time=1795438800&amp;count=1",
            ),
        )
        assertEquals(
            1_795_438_800_000L,
            ActivityLotteryClassifier.extractDrawTimeEpochMillis(
                "https://lucky.nodeseek.com/?post=1073388&draw_time=2026-11-23T13:00:00Z",
            ),
        )
        assertNull(
            ActivityLotteryClassifier.extractDrawTimeEpochMillis("https://lucky.nodeseek.com/?post=618001"),
        )
        assertNull(
            ActivityLotteryClassifier.extractDrawTimeEpochMillis("https://www.nodeseek.com/lucky?post=1&time=123"),
        )
    }

    @Test
    fun `lottery status bucketing derives UPCOMING, ONGOING, ENDED from start and draw time with documented fallback`() {
        val now = 1_795_402_000_000L
        val baseLuckyUrl = "https://www.nodeseek.com/lucky?post=1073388"

        // 1. Future start + future draw -> UPCOMING
        val upcoming = ForumWatchActivityItem(
            id = "lot-upcoming",
            title = "预告抽奖",
            kind = ActivityKind.LOTTERY,
            status = ActivityTimeStatus.ONGOING,
            startsAtEpochMillis = now + 3_600_000L,
            endsAtEpochMillis = now + 7_200_000L,
            luckyUrl = "$baseLuckyUrl&time=${now + 7_200_000L}",
        )
        assertEquals(ActivityTimeStatus.UPCOMING, upcoming.resolveLotteryStatusOrNull(now))

        // 2. Past start + future draw -> ONGOING
        val ongoing = upcoming.copy(
            id = "lot-ongoing",
            startsAtEpochMillis = now - 3_600_000L,
        )
        assertEquals(ActivityTimeStatus.ONGOING, ongoing.resolveLotteryStatusOrNull(now))

        // 3. Exact draw boundary (now == drawAt) -> ENDED
        val exactBoundary = ongoing.copy(endsAtEpochMillis = now, luckyUrl = "$baseLuckyUrl&time=$now")
        assertEquals(ActivityTimeStatus.ENDED, exactBoundary.resolveLotteryStatusOrNull(now))

        // 4. Draw time extracted solely from luckyUrl when endsAtEpochMillis is null
        val urlTimeOnlyFuture = ForumWatchActivityItem(
            id = "lot-url-future",
            title = "URL 开奖时间在未来",
            kind = ActivityKind.EVENT, // promoted because of explicit lottery link!
            endsAtEpochMillis = null,
            luckyUrl = "$baseLuckyUrl&time=${now + 600_000L}",
        )
        assertTrue(urlTimeOnlyFuture.isLottery)
        assertEquals(ActivityKind.LOTTERY, urlTimeOnlyFuture.effectiveKind)
        assertEquals(now + 600_000L, urlTimeOnlyFuture.resolveDrawTimeEpochMillis())
        assertEquals(ActivityTimeStatus.ONGOING, urlTimeOnlyFuture.resolveLotteryStatusOrNull(now))
        assertEquals(ActivityTimeStatus.ENDED, urlTimeOnlyFuture.resolveLotteryStatusOrNull(now + 600_000L))

        // 5. Troll lottery with draw time before start time -> ENDED once draw time passes
        val backdatedDraw = ForumWatchActivityItem(
            id = "lot-backdated",
            title = "开奖时间早于发帖时间",
            kind = ActivityKind.LOTTERY,
            startsAtEpochMillis = now + 1_800_000L,
            endsAtEpochMillis = now - 60_000L,
            luckyUrl = "$baseLuckyUrl&time=${now - 60_000L}",
        )
        assertEquals(ActivityTimeStatus.ENDED, backdatedDraw.resolveLotteryStatusOrNull(now))

        // 6. Server terminal ENDED overrides future draw time (forceEnded / locked)
        val forceEnded = ongoing.copy(status = ActivityTimeStatus.ENDED)
        assertEquals(ActivityTimeStatus.ENDED, forceEnded.resolveLotteryStatusOrNull(now))

        // 7. No draw time fallback: defaults to ONGOING unless startsAt is future or server UPCOMING with null startsAt
        val noDrawTimeOpen = ForumWatchActivityItem(
            id = "lot-nodraw-open",
            title = "无开奖时间参数抽奖",
            kind = ActivityKind.LOTTERY,
            startsAtEpochMillis = now - 60_000L,
            endsAtEpochMillis = null,
            status = ActivityTimeStatus.ONGOING,
            luckyUrl = "https://lucky.nodeseek.com/?post=618001",
        )
        assertEquals(ActivityTimeStatus.ONGOING, noDrawTimeOpen.resolveLotteryStatusOrNull(now))

        val noDrawTimeUpcoming = noDrawTimeOpen.copy(
            startsAtEpochMillis = null,
            status = ActivityTimeStatus.UPCOMING,
        )
        assertEquals(ActivityTimeStatus.UPCOMING, noDrawTimeUpcoming.resolveLotteryStatusOrNull(now))

        // 8. Non-lottery items ALWAYS return null status (never dumped into ENDED)
        val nonLotteryEnded = ForumWatchActivityItem(
            id = "non-lot-1",
            title = "黑五活动讨论",
            kind = ActivityKind.EVENT,
            status = ActivityTimeStatus.ENDED,
            endsAtEpochMillis = now - 3_600_000L,
            luckyUrl = null,
        )
        assertFalse(nonLotteryEnded.isLottery)
        assertNull(nonLotteryEnded.resolveLotteryStatusOrNull(now))
    }

    @Test
    fun `normalizer downgrades server-tagged LOTTERY without explicit link and promotes items with link in markdown or externalLinks`() {
        val now = 1_795_402_000_000L

        val falsePositiveBugReport = ForumWatchActivityItem(
            id = "nodeseek:1073146",
            title = "抽奖器好像有bug，奖品数量>评论数直接返回",
            summary = "似乎就是直接输出 https://www.nodeseek.com/post-1070353-3#30",
            sourceUrl = "https://www.nodeseek.com/post-1073146-1",
            kind = ActivityKind.LOTTERY,
            tags = listOf("抽奖"),
            luckyUrl = null,
        )
        val downgradedEvent = ActivityLotteryClassifier.normalizeItem(falsePositiveBugReport)
        assertFalse(downgradedEvent.isLottery)
        assertEquals(ActivityKind.EVENT, downgradedEvent.kind)
        assertNull(downgradedEvent.luckyUrl)
        assertNull(downgradedEvent.resolveLotteryStatusOrNull(now))

        val noLinkFloor = falsePositiveBugReport.copy(
            id = "floor-1",
            drawFloor = 200,
        )
        assertEquals(ActivityKind.FLOOR, ActivityLotteryClassifier.normalizeItem(noLinkFloor).kind)

        val noLinkGiveaway = falsePositiveBugReport.copy(
            id = "giveaway-1",
            tags = listOf("抽奖", "免费福利"),
        )
        assertEquals(ActivityKind.GIVEAWAY, ActivityLotteryClassifier.normalizeItem(noLinkGiveaway).kind)

        val noLinkPromo = falsePositiveBugReport.copy(
            id = "promo-1",
            tags = listOf("限时特惠"),
        )
        assertEquals(ActivityKind.PROMO, ActivityLotteryClassifier.normalizeItem(noLinkPromo).kind)

        // Item tagged EVENT by server, but Markdown body contains explicit NodeSeek lucky link
        val hiddenLottery = ForumWatchActivityItem(
            id = "nodeseek:1079000",
            title = "周年庆回馈活动",
            summary = "参与评论送鸡腿",
            sourceUrl = "https://www.nodeseek.com/post-1079000-1",
            kind = ActivityKind.EVENT,
            luckyUrl = null,
        )
        val promotedFromMarkdown = ActivityLotteryClassifier.normalizeItem(
            item = hiddenLottery,
            bodyMarkdown = "欢迎参与！[开奖链接](https://www.nodeseek.com/lucky?post=1079000&time=${now + 1_800_000L}&count=2)",
        )
        assertTrue(promotedFromMarkdown.isLottery)
        assertEquals(ActivityKind.LOTTERY, promotedFromMarkdown.kind)
        assertEquals(
            "https://www.nodeseek.com/lucky?post=1079000&time=${now + 1_800_000L}&count=2",
            promotedFromMarkdown.luckyUrl,
        )
        assertEquals(now + 1_800_000L, promotedFromMarkdown.endsAtEpochMillis)
        assertEquals(ActivityTimeStatus.ONGOING, promotedFromMarkdown.resolveLotteryStatusOrNull(now))

        // Item promoted via externalLinks
        val promotedFromLinks = ActivityLotteryClassifier.normalizeItem(
            item = hiddenLottery,
            externalLinks = listOf(
                ActivityLabeledLink("主贴", "https://www.nodeseek.com/post-1079000-1"),
                ActivityLabeledLink("官方开奖页", "https://lucky.nodeseek.com/?post=1079000"),
            ),
        )
        assertTrue(promotedFromLinks.isLottery)
        assertEquals("https://lucky.nodeseek.com/?post=1079000", promotedFromLinks.luckyUrl)
    }

    @Test
    fun `extracts clean lottery URL and draw time when adjacent to CJK characters or wrapped in Markdown emphasis`() {
        val drawAt = 1_795_438_800_000L
        val expectedUrl = "https://www.nodeseek.com/lucky?post=123&time=$drawAt"

        // 1. Bare URL directly followed by Chinese text without whitespace
        val cjkAdjacentText = "开奖地址：https://www.nodeseek.com/lucky?post=123&time=${drawAt}欢迎参与！"
        val cjkExtracted = ActivityLotteryClassifier.findExplicitLotteryUrl(bodyMarkdown = cjkAdjacentText)
        assertEquals(expectedUrl, cjkExtracted)
        assertEquals(drawAt, ActivityLotteryClassifier.extractDrawTimeEpochMillis(cjkExtracted))

        // 2. URL wrapped in Markdown bold (**...**)
        val boldWrappedText = "请点击 **https://www.nodeseek.com/lucky?post=123&time=$drawAt** 查看开奖"
        val boldExtracted = ActivityLotteryClassifier.findExplicitLotteryUrl(bodyMarkdown = boldWrappedText)
        assertEquals(expectedUrl, boldExtracted)
        assertEquals(drawAt, ActivityLotteryClassifier.extractDrawTimeEpochMillis(boldExtracted))

        // 3. URL wrapped in Markdown strikethrough (~~...~~) and raw luckyUrl with emphasis markers
        val strikeWrappedText = "~~https://www.nodeseek.com/lucky?post=123&time=$drawAt~~"
        val strikeExtracted = ActivityLotteryClassifier.findExplicitLotteryUrl(summary = strikeWrappedText)
        assertEquals(expectedUrl, strikeExtracted)
        assertEquals(drawAt, ActivityLotteryClassifier.extractDrawTimeEpochMillis(strikeExtracted))
        assertEquals(
            expectedUrl,
            ActivityLotteryClassifier.findExplicitLotteryUrl(luckyUrl = "**$expectedUrl**"),
        )
    }

    @Test
    fun `legacy cached JSON without lottery-other counts or with no-link LOTTERY migrates cleanly on cold load and detail fetch upgrades snapshot`() = runTest {
        val nowMs = 1_795_402_000_000L

        // Verify backward-compatible ActivityCounts JSON deserialization (pre-change schema without lottery/other)
        val legacyJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val decodedCounts = legacyJson.decodeFromString<ActivityCounts>(
            """{"all":10,"ongoing":2,"upcoming":1,"ended":1}""",
        )
        assertEquals(10, decodedCounts.all)
        assertEquals(4, decodedCounts.lottery)
        assertEquals(6, decodedCounts.other)

        // Write legacy cached list file containing a false-positive no-link LOTTERY and a valid LOTTERY
        val listCacheFile = File(tempDir, "legacy_activities_list.json")
        val detailCacheFile = File(tempDir, "legacy_activities_detail.json")
        listCacheFile.writeText(
            """
            {
              "version": 1,
              "updatedAtEpochMillis": $nowMs,
              "lastSyncedEpochMillis": $nowMs,
              "totalCount": 2,
              "hasMore": false,
              "counts": { "all": 2, "ongoing": 2, "upcoming": 0, "ended": 0 },
              "items": [
                {
                  "id": "nodeseek:1073146",
                  "title": "抽奖器好像有bug",
                  "summary": "无开奖链接讨论帖",
                  "sourceUrl": "https://www.nodeseek.com/post-1073146-1",
                  "status": "ended",
                  "kind": "lottery",
                  "sourceId": "nodeseek",
                  "topicId": "1073146"
                },
                {
                  "id": "nodeseek:1073388",
                  "title": "真实抽奖",
                  "summary": "含明确开奖链接",
                  "sourceUrl": "https://www.nodeseek.com/post-1073388-1",
                  "status": "ongoing",
                  "kind": "lottery",
                  "sourceId": "nodeseek",
                  "topicId": "1073388",
                  "luckyUrl": "https://www.nodeseek.com/lucky?post=1073388&time=${nowMs + 3_600_000L}&count=1"
                }
              ]
            }
            """.trimIndent(),
        )

        val apiClient = ForumWatchActivityApiClient(
            baseUrlProvider = { mockServer.url("/").toString() },
            masterSecretProvider = { testSecretBytes.copyOf() },
            httpClient = OkHttpClient(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            nowSecondsProvider = { nowMs / 1000L },
            sleeper = {},
        )
        val repo = ForumWatchActivityRepositoryImpl(
            apiClient = apiClient,
            listCacheFile = listCacheFile,
            detailCacheFile = detailCacheFile,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            clockMillis = { nowMs },
            externalScope = backgroundScope,
        )

        val cached = repo.loadCachedActivities()
        assertEquals(2, cached.counts.all)
        assertEquals(1, cached.counts.lottery)
        assertEquals(1, cached.counts.ongoing)
        assertEquals(1, cached.counts.other)
        val migratedDiscussion = cached.activities.first { it.id == "nodeseek:1073146" }
        assertEquals(ActivityKind.EVENT, migratedDiscussion.kind)
        assertFalse(migratedDiscussion.isLottery)

        // When fetching detail for nodeseek:1073146 reveals an explicit lottery link in bodyMarkdown,
        // along with fresh status ("ongoing") and future startsAtEpochMillis, the repository promotes it
        // in the live snapshot and syncs status/startsAtEpochMillis/counts so list & detail agree!
        val futureStartMs = nowMs + 1_800_000L
        val enrichedDetailJson = """
            {
              "ok": true,
              "updatedAtEpochMillis": $nowMs,
              "serverTimeEpochMillis": $nowMs,
              "item": {
                "id": "nodeseek:1073146",
                "title": "补上了开奖链接的活动",
                "summary": "现已补充开奖链接",
                "sourceUrl": "https://www.nodeseek.com/post-1073146-1",
                "startsAtEpochMillis": $futureStartMs,
                "status": "ongoing",
                "kind": "event",
                "sourceId": "nodeseek",
                "topicId": "1073146",
                "bodyMarkdown": "开奖地址：[点击查看](https://www.nodeseek.com/lucky?post=1073146&time=${nowMs + 7_200_000L}&count=1)"
              }
            }
        """.trimIndent()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Content-Encrypted", "AES-256-GCM")
                .setBody(OverseasApiCrypto.encryptEnvelope(testAesKey, enrichedDetailJson)),
        )

        val detail = repo.getActivityDetail("nodeseek:1073146").getOrThrow()
        assertTrue(detail.item.isLottery)
        assertEquals(ActivityKind.LOTTERY, detail.item.kind)
        assertEquals(futureStartMs, detail.item.startsAtEpochMillis)
        assertEquals(nowMs + 7_200_000L, detail.item.endsAtEpochMillis)
        assertEquals(ActivityTimeStatus.UPCOMING, detail.item.resolveLotteryStatusOrNull(nowMs))

        val syncedInSnapshot = repo.snapshot.value.activities.first { it.id == "nodeseek:1073146" }
        assertEquals(ActivityTimeStatus.ONGOING, syncedInSnapshot.status)
        assertEquals(futureStartMs, syncedInSnapshot.startsAtEpochMillis)
        assertEquals(ActivityTimeStatus.UPCOMING, syncedInSnapshot.resolveLotteryStatusOrNull(nowMs))
        assertEquals(2, repo.snapshot.value.counts.lottery)
        assertEquals(1, repo.snapshot.value.counts.ongoing)
        assertEquals(1, repo.snapshot.value.counts.upcoming)
        assertEquals(0, repo.snapshot.value.counts.ended)
        assertEquals(0, repo.snapshot.value.counts.other)
    }

    @Test
    fun `formatLotteryTimingLabel returns timing hints only for lotteries and null for non-lottery items`() {
        val now = 1_795_402_000_000L // 2026-11-23T02:46:40Z
        val drawAt = 1_795_438_800_000L // 2026-11-23T13:00:00Z
        val lottery = ForumWatchActivityItem(
            id = "lot-fmt",
            title = "测试抽奖",
            kind = ActivityKind.LOTTERY,
            startsAtEpochMillis = now - 600_000L,
            endsAtEpochMillis = drawAt,
            luckyUrl = "https://www.nodeseek.com/lucky?post=1&time=$drawAt",
        )

        val compactOngoing = formatLotteryTimingLabel(
            item = lottery,
            lotteryStatus = ActivityTimeStatus.ONGOING,
            nowEpochMillis = now,
            compact = true,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals("开奖 11-23 13:00", compactOngoing)

        val detailedOngoing = formatLotteryTimingLabel(
            item = lottery,
            lotteryStatus = ActivityTimeStatus.ONGOING,
            nowEpochMillis = now,
            compact = false,
            zoneId = ZoneOffset.UTC,
        )
        assertNotNull(detailedOngoing)
        assertTrue(detailedOngoing!!.contains("距开奖"))
        assertTrue(detailedOngoing.contains("11-23 13:00"))

        val compactEnded = formatLotteryTimingLabel(
            item = lottery,
            lotteryStatus = ActivityTimeStatus.ENDED,
            nowEpochMillis = drawAt + 60_000L,
            compact = true,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals("已开奖 11-23 13:00", compactEnded)

        // Non-lottery (null status) returns null
        assertNull(
            formatLotteryTimingLabel(
                item = lottery,
                lotteryStatus = null,
                nowEpochMillis = now,
                compact = true,
                zoneId = ZoneOffset.UTC,
            ),
        )
    }
}
