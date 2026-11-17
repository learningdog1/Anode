package com.nodeseek.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.Wallpapers
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailAuthTelemetry
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailRateLimit
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailbox
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AuthorBadge
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.CheckInMember
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.model.DirectMessage
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRuntimeStatus
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.ValuationMarkdownStyle
import com.nodeseek.app.domain.valuation.ValuationMode
import com.nodeseek.app.ui.boards.BoardsScreen
import com.nodeseek.app.ui.boards.BoardsUiState
import com.nodeseek.app.ui.boards.SearchScope
import com.nodeseek.app.ui.components.CategoryMicroTag
import com.nodeseek.app.ui.components.LocalPreviewAvatarResResolver
import com.nodeseek.app.ui.components.CollapsingSquircleFab
import com.nodeseek.app.ui.components.DrumstickCheckInChip
import com.nodeseek.app.ui.components.FloatingPillDock
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseNavTab
import com.nodeseek.app.ui.components.PulseTopicCard
import com.nodeseek.app.ui.components.PulseTopicCardModel
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.composer.TopicComposerScreen
import com.nodeseek.app.ui.composer.TopicComposerUiState
import com.nodeseek.app.ui.explore.ExploreBoardSliceUiState
import com.nodeseek.app.ui.explore.ExploreScreen
import com.nodeseek.app.ui.explore.ExploreUiState
import com.nodeseek.app.ui.hub.CheckInBoardScreen
import com.nodeseek.app.ui.hub.CheckInBoardUiState
import com.nodeseek.app.ui.hub.DirectMessageScreen
import com.nodeseek.app.ui.hub.DirectMessageUiState
import com.nodeseek.app.ui.hub.HubBookmarkDisplayItem
import com.nodeseek.app.ui.hub.HubCollectionScreen
import com.nodeseek.app.ui.hub.InboxScreen
import com.nodeseek.app.ui.hub.InboxTab
import com.nodeseek.app.ui.hub.InboxUiState
import com.nodeseek.app.ui.hub.PulseHubScreen
import com.nodeseek.app.ui.hub.PulseHubUiState
import com.nodeseek.app.ui.hub.SettingsScreen
import com.nodeseek.app.ui.hub.SettingsUiState
import com.nodeseek.app.ui.hub.UserProfileScreen
import com.nodeseek.app.ui.hub.UserProfileUiState
import com.nodeseek.app.ui.mail.SeekMailFilterMode
import com.nodeseek.app.ui.mail.SeekMailReaderMode
import com.nodeseek.app.ui.mail.SeekMailScreen
import com.nodeseek.app.ui.mail.SeekMailUiState
import com.nodeseek.app.ui.radar.PulseRadarScreen
import com.nodeseek.app.ui.radar.PulseRadarUiState
import com.nodeseek.app.ui.radar.RadarAlertFilter
import com.nodeseek.app.ui.radar.RadarEnvironmentDiagnostics
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.topic.QuotedFloorContext
import com.nodeseek.app.ui.topic.ReplyComposerUiState
import com.nodeseek.app.ui.topic.TopicDetailScreen
import com.nodeseek.app.ui.topic.TopicDetailUiState
import com.nodeseek.app.ui.valuation.VpsValuationBottomSheet

private const val PHONE_DEVICE_SPEC = "spec:width=412dp,height=915dp,dpi=320"

private val SHOWCASE_SESSION = AccountSession(
    uid = 1024L,
    username = "latency_hunter",
    avatarUrl = "",
    drumsticks = 2860,
    stardust = 42,
    topicCount = 28,
    replyCount = 314,
    bookmarkCount = 19,
    permissionRank = 5,
    unreadNotificationCount = 3,
    canCustomizeCss = true,
    isTelegramBound = true,
)

private val SHOWCASE_SETTINGS = AppSettings(
    themeMode = ThemeMode.PAPER_LIGHT,
    purePitchAmoled = false,
    useDynamicColor = false,
    fontScale = 1.0f,
    preferRandomCheckIn = true,
    pinnedCategorySlugs = setOf("daily", "tech", "info", "review", "trade", "dev"),
    nodeImageApiKey = "ni_live_84a2f09c41d3a8e190b7",
    seekMailApiKey = "sk_mail_e91f38b7c042a16f981c",
)

private val SHOWCASE_ORDERED_TABS = listOf(
    Category(slug = "", name = "全部", isCustomizable = false, supportsRss = true),
    Category(slug = "tech", name = "技术"),
    Category(slug = "review", name = "测评"),
    Category(slug = "info", name = "情报"),
    Category(slug = "trade", name = "交易"),
    Category(slug = "daily", name = "日常"),
    Category(slug = "dev", name = "Dev"),
) + Category.DEFAULT_CATEGORIES.filter {
    !it.isAdminOnly && it.slug.isNotBlank() &&
        it.slug !in setOf("tech", "review", "info", "trade", "daily", "dev")
}

private val SHOWCASE_PINNED_TOPICS = listOf(
    TopicSummary(
        id = 10001L,
        title = "[公告] NodeSeek 二手服务器交易安全指引与中介规范 2026 修订版",
        categorySlug = "inside",
        categoryName = "内版",
        authorId = 1L,
        authorName = "admin",
        viewCount = 48210,
        replyCount = 312,
        lastActivityIso = "2026-11-16T01:05:00Z",
        isPinned = true,
    ),
    TopicSummary(
        id = 10002L,
        title = "[置顶] 欢迎使用 Anode 原生客户端，支持脉搏雷达与 VPS 估值",
        categorySlug = "dev",
        categoryName = "Dev",
        authorId = 1024L,
        authorName = "latency_hunter",
        viewCount = 12940,
        replyCount = 87,
        lastActivityIso = "2026-11-16T01:12:00Z",
        isPinned = true,
    ),
)

private val SHOWCASE_TOPICS = listOf(
    TopicSummary(
        id = 884210L,
        title = "【深度测评】东京 Tier-1 BGP 双栈小鸡首发实测：三网 CMI/IIJ 回程延迟、YABS Geekbench 6 与晚高峰流媒体报告",
        categorySlug = "review",
        categoryName = "测评",
        authorId = 1024L,
        authorName = "latency_hunter",
        viewCount = 6840,
        replyCount = 142,
        lastActivityIso = "2026-11-16T01:36:00Z",
        isRead = false,
        isBookmarked = true,
    ),
    TopicSummary(
        id = 883905L,
        title = "自建 WireGuard + Bird2 任意播选路笔记：如何优雅规避跨太平洋海缆抖动并保持 MTU 1420 零分片",
        categorySlug = "tech",
        categoryName = "技术",
        authorId = 2048L,
        authorName = "bgp_artisan",
        viewCount = 1920,
        replyCount = 46,
        lastActivityIso = "2026-11-16T01:10:00Z",
        requiredViewLevel = 1,
        isRead = true,
        lastReadFloor = 18,
    ),
    TopicSummary(
        id = 883712L,
        title = "[情报] DMIT Tokyo Pro 限时补货预警：EYO.Pro.Wee 年付 $39.90 三网 CN2 GIA / 9929 / CMIN2",
        categorySlug = "info",
        categoryName = "情报",
        authorId = 3090L,
        authorName = "bgp_watcher",
        viewCount = 4380,
        replyCount = 79,
        lastActivityIso = "2026-11-16T00:52:00Z",
        isRead = false,
    ),
    TopicSummary(
        id = 883518L,
        title = "[出] HKBN 家宽大带宽 NAT 剩余 182 天包 Push，带原邮 seek.li 别名可整箱交付",
        categorySlug = "trade",
        categoryName = "交易",
        authorId = 4102L,
        authorName = "cloud_nomad",
        viewCount = 1280,
        replyCount = 24,
        lastActivityIso = "2026-11-16T00:25:00Z",
        isRead = false,
    ),
    TopicSummary(
        id = 883301L,
        title = "开源自用 Jetpack Compose Material 3 Expressive 论坛客户端 Anode：纸墨日间 + 脉搏雷达 + Seek 邮箱",
        categorySlug = "dev",
        categoryName = "Dev",
        authorId = 5120L,
        authorName = "compose_craft",
        viewCount = 3150,
        replyCount = 63,
        lastActivityIso = "2026-11-15T23:40:00Z",
        isRead = true,
        lastReadFloor = 9,
        isBookmarked = true,
    ),
)

private val SHOWCASE_EXPLORE_STATE = ExploreUiState(
    selectedCategorySlug = "",
    selectedSort = FeedSort.LATEST_REPLY,
    categories = Category.DEFAULT_CATEGORIES,
    pinnedCategorySlugs = SHOWCASE_SETTINGS.pinnedCategorySlugs,
    orderedBoardTabs = SHOWCASE_ORDERED_TABS,
    slicesBySlug = mapOf(
        "" to ExploreBoardSliceUiState(
            categorySlug = "",
            sort = FeedSort.LATEST_REPLY,
            pinnedTopics = SHOWCASE_PINNED_TOPICS,
            topics = SHOWCASE_TOPICS,
            isInitialLoading = false,
            hasNextPage = true,
        ),
    ),
    pinnedTopics = SHOWCASE_PINNED_TOPICS,
    topics = SHOWCASE_TOPICS,
    isInitialLoading = false,
    hasNextPage = true,
    session = SHOWCASE_SESSION,
)

private val SHOWCASE_PULSE_HUB_STATE = PulseHubUiState(
    session = SHOWCASE_SESSION,
    settings = SHOWCASE_SETTINGS,
    unreadInboxCount = 3,
    isRefreshing = false,
    checkInStatus = CheckInStatus(
        hasCheckedInToday = true,
        todayGain = 12,
        todayOrder = 18,
        totalCheckedInUsers = 4820,
    ),
    dailyQuota = DailyQuotaProgress(
        postBonusEarned = 5,
        maxPostBonus = 5,
        replyBonusEarned = 3,
        maxReplyBonus = 5,
        freeDrumsticksUsed = 2,
        maxFreeDrumsticks = 5,
    ),
    bookmarks = SHOWCASE_TOPICS.take(3).map { summary ->
        HubBookmarkDisplayItem(
            topicId = summary.id,
            summary = summary,
            hasOfflineSnapshot = summary.id != 883712L,
            savedAtEpochMillis = 1794798000000L,
        )
    },
    readHistory = listOf(
        ReadHistoryEntry(
            topicId = 883905L,
            title = SHOWCASE_TOPICS[1].title,
            categoryName = "技术",
            authorName = "bgp_artisan",
            lastReadPage = 2,
            lastReadFloor = 18,
            categorySlug = "tech",
        ),
    ),
    seekMailConfigured = true,
    seekMailboxCount = 2,
    seekMailPrimaryAddress = "ops@seek.li",
    seekMailUnreadCount = 2,
)

private val SHOWCASE_OP_MARKDOWN = """
> **评测摘要**：东京 Equinix TY8 机房直连上游，三网去程各自优化，晚高峰 21:30 实测丢包率 `0.0%`。感谢 @bgp_artisan 在 #18 提供的内核 BBRv3 调优参数 :ac01:

:::: tabs
::: tab-item YABS 基准跑分
```ansi
${'\u001B'}[1;36m# ## ## ## ## ## ## ## ## ## ## ## ## ## ## #${'\u001B'}[0m
${'\u001B'}[1;36m#              Yet-Another-Bench-Script      #${'\u001B'}[0m
${'\u001B'}[1;36m# ## ## ## ## ## ## ## ## ## ## ## ## ## ## #${'\u001B'}[0m
CPU Model   : AMD EPYC 9654 96-Core Processor @ 3.70GHz
AES-NI      : ${'\u001B'}[1;32m✔ Enabled${'\u001B'}[0m    VM-x/AMD-V : ${'\u001B'}[1;32m✔ Enabled${'\u001B'}[0m
4k Mixed    : ${'\u001B'}[1;33m418.6 MB/s${'\u001B'}[0m  (104.6k IOPS)
Geekbench 6 : ${'\u001B'}[1;32m2,418 Single-Core${'\u001B'}[0m | ${'\u001B'}[1;35m9,860 Multi-Core${'\u001B'}[0m
```
:::
::: tab-item 三网回程矩阵
| 运营商 | 回程路由节点 | 晚高峰 RTT | 抖动 |
| :--- | :--- | ---: | ---: |
| 电信 CT | IIJ -> 202.97 骨干直连 | 48.2 ms | ±1.4 ms |
| 联通 CU | SoftBank -> AS4837 | 43.9 ms | ±0.9 ms |
| 移动 CM | CMI -> 香港 -> 东京 | 51.6 ms | ±1.8 ms |
:::
::::
""".trimIndent()

private val SHOWCASE_OP_AUTHOR = FloorAuthor(
    uid = 1024L,
    username = "latency_hunter",
    isTopicAuthor = true,
    roleBadges = listOf(AuthorBadge("dev", "开源贡献")),
    level = 5,
)

private val SHOWCASE_OPENING_FLOOR = FloorComment(
    commentId = 900100L,
    floorNumber = 0,
    author = SHOWCASE_OP_AUTHOR,
    createdAtIso = "2026-11-16T01:10:00Z",
    markdownBody = SHOWCASE_OP_MARKDOWN,
    signatureMarkdown = "节点不在多，稳字当头 · `mtr -rwzc 50`",
    upvoteCount = 26,
    drumstickCount = 14,
    hasUpvoted = true,
    hasGivenDrumstick = true,
)

private val SHOWCASE_REPLIES = listOf(
    FloorComment(
        commentId = 900101L,
        floorNumber = 1,
        author = FloorAuthor(uid = 2048L, username = "bgp_artisan", level = 3),
        createdAtIso = "2026-11-16T01:18:00Z",
        markdownBody = "晚高峰联通 AS4837 确实很稳，建议把 `net.ipv4.tcp_rmem` 调大到 `16777216` 跑单线程还能再提 25% :ac05:",
        upvoteCount = 9,
        drumstickCount = 3,
        isPinned = true,
    ),
    FloorComment(
        commentId = 900102L,
        floorNumber = 2,
        author = FloorAuthor(uid = 3090L, username = "bgp_watcher", level = 4),
        createdAtIso = "2026-11-16T01:26:00Z",
        markdownBody = "补一张刚才刚出炉的东京到上海电信去回程对比，几乎全程无丢包，加鸡腿支持！",
        upvoteCount = 5,
        drumstickCount = 2,
    ),
)

private val SHOWCASE_TOPIC_PAGE = TopicDetailPage(
    topicId = 884210L,
    title = "【深度测评】东京 Tier-1 BGP 双栈小鸡首发实测：三网 CMI/IIJ 回程延迟与 YABS 报告",
    categorySlug = "review",
    categoryName = "测评",
    author = SHOWCASE_OP_AUTHOR,
    viewCount = 6840,
    bookmarkCount = 128,
    currentPage = 1,
    totalPages = 6,
    requiredViewLevel = 0,
    isBookmarked = true,
    isCuratedAward = true,
    openingFloor = SHOWCASE_OPENING_FLOOR,
    replies = SHOWCASE_REPLIES,
    createdAtIso = "2026-11-16T01:10:00Z",
)

private val SHOWCASE_TOPIC_STATE = TopicDetailUiState(
    topicId = 884210L,
    titleHint = SHOWCASE_TOPIC_PAGE.title,
    session = SHOWCASE_SESSION,
    settings = SHOWCASE_SETTINGS,
    isInitialLoading = false,
    pageData = SHOWCASE_TOPIC_PAGE,
    openingFloor = SHOWCASE_OPENING_FLOOR,
    displayedReplies = SHOWCASE_REPLIES,
    currentPage = 1,
    loadedStartPage = 1,
    loadedEndPage = 1,
    totalPages = 6,
    estimatedTotalFloors = 58,
    currentVisibleFloor = 1,
    isBookmarked = true,
    effectiveBookmarkCount = 128,
    isTrackingReplies = true,
    trackedBaselineFloor = 58,
)

private val SHOWCASE_RADAR_RULES = listOf(
    KeywordWatchRule(
        id = 1L,
        keyword = "DMIT TYO",
        excludeWords = "求购,收",
        categorySlug = "trade",
        matchMode = KeywordMatchMode.CONTAINS,
        isEnabled = true,
    ),
    KeywordWatchRule(
        id = 2L,
        keyword = "(补货|限免|CN2\\s*GIA).*?(东京|香港)",
        excludeWords = "过期",
        categorySlug = "info",
        matchMode = KeywordMatchMode.REGEX,
        isEnabled = true,
    ),
    KeywordWatchRule(
        id = 3L,
        keyword = "SeekMail",
        excludeWords = "",
        categorySlug = "dev",
        matchMode = KeywordMatchMode.CONTAINS,
        isEnabled = false,
    ),
)

private val SHOWCASE_RADAR_TRACKED = listOf(
    TrackedTopic(
        topicId = 884210L,
        title = "【深度测评】东京 Tier-1 BGP 双栈小鸡首发实测",
        categorySlug = "review",
        categoryName = "测评",
        authorName = "latency_hunter",
        lastKnownFloor = 58,
        lastKnownTotalPages = 6,
        unreadNewReplies = 4,
        isActive = true,
        lastCheckedEpochMillis = 1794800000000L,
        lastUpdatedEpochMillis = 1794800000000L,
    ),
)

private val SHOWCASE_RADAR_ALERTS = listOf(
    LocalAlertRecord(
        id = 101L,
        dedupKey = "kw_884290",
        kind = LocalAlertKind.KEYWORD_HIT,
        topicId = 884290L,
        topicTitle = "[出] DMIT TYO.Pro.Tiny 东京年付续费款剩余 240 天，明盘 165 USD 包 Push",
        targetFloor = 0,
        matchedKeyword = "DMIT TYO",
        actorName = "cloud_nomad",
        summarySnippet = "交易 · 命中关键词「DMIT TYO」，已过滤排除词",
        categoryName = "交易",
        isRead = false,
        createdAtEpochMillis = 1794800100000L,
    ),
    LocalAlertRecord(
        id = 102L,
        dedupKey = "reply_884210_58",
        kind = LocalAlertKind.TRACKED_REPLY,
        topicId = 884210L,
        topicTitle = "【深度测评】东京 Tier-1 BGP 双栈小鸡首发实测",
        targetFloor = 58,
        matchedKeyword = "",
        actorName = "bgp_artisan",
        summarySnippet = "新增 4 条楼层回复（最新至 #58 楼）",
        categoryName = "测评",
        isRead = false,
        createdAtEpochMillis = 1794799500000L,
    ),
    LocalAlertRecord(
        id = 103L,
        dedupKey = "kw_883712",
        kind = LocalAlertKind.KEYWORD_HIT,
        topicId = 883712L,
        topicTitle = "[情报] 东京 CN2 GIA 补货提醒：年付套餐限量 50 台放货",
        targetFloor = 0,
        matchedKeyword = "(补货|限免|CN2\\s*GIA).*?(东京|香港)",
        actorName = "bgp_watcher",
        summarySnippet = "情报 · 正则命中，来自 RSS 快速通道",
        categoryName = "情报",
        isRead = true,
        createdAtEpochMillis = 1794796000000L,
    ),
)

private val SHOWCASE_MAILBOXES = listOf(
    SeekMailbox(
        id = 1L,
        address = "ops@seek.li",
        alias = "主工作邮箱",
        isPinned = true,
        archiveCount = 12,
    ),
    SeekMailbox(
        id = 2L,
        address = "tokyo-vps@nodeseek.org",
        alias = "VPS 交付专属",
        archiveCount = 5,
    ),
)

private val SHOWCASE_MAIL_TAGS = listOf(
    SeekMailTag(id = 1L, name = "验证码", color = "#3655D6"),
    SeekMailTag(id = 2L, name = "账单收据", color = "#0B7A75"),
)

private val SHOWCASE_MAIL_ITEMS = listOf(
    SeekMailSummary(
        id = 7001L,
        mailboxId = 1L,
        fromAddress = "noreply@cloudflare.com",
        fromName = "Cloudflare Security",
        toAddress = "ops@seek.li",
        subject = "Your Cloudflare verification code: 849206",
        snippet = "Use the one-time verification code 849206 to finish signing in to your Zero Trust dashboard.",
        verificationCode = "849206",
        isRead = false,
        isFlagged = true,
        hasAttachments = true,
        receivedAt = "2026-11-16T01:35:00Z",
        sizeBytes = 18420L,
        authScore = 100,
        tags = listOf(SHOWCASE_MAIL_TAGS[0]),
    ),
    SeekMailSummary(
        id = 7002L,
        mailboxId = 2L,
        fromAddress = "billing@dmit.io",
        fromName = "DMIT Billing",
        toAddress = "tokyo-vps@nodeseek.org",
        subject = "[DMIT] Invoice #904218 Payment Confirmation & Push Receipt",
        snippet = "Your service TYO.Pro.Tiny ownership transfer has been completed. Attached is the PDF receipt.",
        verificationCode = null,
        isRead = false,
        hasAttachments = true,
        receivedAt = "2026-11-16T00:48:00Z",
        sizeBytes = 42100L,
        authScore = 98,
        tags = listOf(SHOWCASE_MAIL_TAGS[1]),
    ),
    SeekMailSummary(
        id = 7003L,
        mailboxId = 1L,
        fromAddress = "auth@github.com",
        fromName = "GitHub",
        toAddress = "ops@seek.li",
        subject = "[GitHub] Sudo verification code 391042",
        snippet = "Please enter the following code to confirm your personal access token update: 391042",
        verificationCode = "391042",
        isRead = true,
        receivedAt = "2026-11-15T22:15:00Z",
        sizeBytes = 9420L,
        authScore = 100,
        tags = listOf(SHOWCASE_MAIL_TAGS[0]),
    ),
)

// ── 01–03. Explore Feed across 3 Theme Modes ────────────────────────────────────────────

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview01ExplorePaperLight() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.PAPER_LIGHT,
        bottomDockTab = PulseNavTab.EXPLORE,
        unreadCount = 3,
    ) {
        ExploreScreen(
            onOpenTopic = { _, _, _ -> },
            onOpenSearch = {},
            onOpenBoardsTab = {},
            previewUiState = SHOWCASE_EXPLORE_STATE,
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview02ExploreObsidianDark() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.OBSIDIAN_DARK,
        purePitchAmoled = false,
        bottomDockTab = PulseNavTab.EXPLORE,
        unreadCount = 3,
    ) {
        ExploreScreen(
            onOpenTopic = { _, _, _ -> },
            onOpenSearch = {},
            onOpenBoardsTab = {},
            previewUiState = SHOWCASE_EXPLORE_STATE,
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview03ExplorePitchAmoled() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.OBSIDIAN_DARK,
        purePitchAmoled = true,
        bottomDockTab = PulseNavTab.EXPLORE,
        unreadCount = 3,
    ) {
        ExploreScreen(
            onOpenTopic = { _, _, _ -> },
            onOpenSearch = {},
            onOpenBoardsTab = {},
            previewUiState = SHOWCASE_EXPLORE_STATE,
        )
    }
}

// ── 04–05. Collections & History and Unified Search ─────────────────────────────────────

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview04CollectionsAndHistory() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.PAPER_LIGHT,
        bottomDockTab = null,
    ) {
        HubCollectionScreen(
            initialTab = 0,
            onBack = {},
            onOpenTopic = { _, _, _ -> },
            previewUiState = SHOWCASE_PULSE_HUB_STATE,
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview05UnifiedSearch() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.PAPER_LIGHT,
        bottomDockTab = null,
    ) {
        BoardsScreen(
            onOpenTopic = { _, _, _ -> },
            standaloneSearchMode = true,
            onBack = {},
            previewUiState = BoardsUiState(
                categories = Category.DEFAULT_CATEGORIES,
                pinnedCategorySlugs = SHOWCASE_SETTINGS.pinnedCategorySlugs,
                recentSearchQueries = listOf("东京 IIJ", "DMIT TYO", "BBRv3", "seek.li"),
                isSignedIn = true,
                queryInput = "东京 IIJ",
                submittedQuery = "东京 IIJ",
                isSearchModeActive = true,
                searchScope = SearchScope.TOPICS,
                searchSort = FeedSort.LATEST_REPLY,
                searchTopics = SHOWCASE_TOPICS.take(3),
                isSearching = false,
                searchHasNextPage = true,
            ),
        )
    }
}

// ── 06–07. Magazine Topic Reader & Reply Composer + Stickers ────────────────────────────

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview06TopicReader() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        TopicDetailScreen(
            topicId = 884210L,
            onBack = {},
            previewUiState = SHOWCASE_TOPIC_STATE,
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview07TopicReplyAndStickers() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        TopicDetailScreen(
            topicId = 884210L,
            onBack = {},
            previewUiState = SHOWCASE_TOPIC_STATE.copy(
                replyComposer = ReplyComposerUiState(
                    isSheetOpen = true,
                    quotedContext = QuotedFloorContext(username = "bgp_artisan", floorNumber = 18),
                    draftMarkdown = "实测晚高峰电信走 IIJ 依然稳定在 `48.2 ms` 左右，附上 BBRv3 参数微调与路由追踪对比：\n\n> `net.ipv4.tcp_congestion_control = bbr` 实测单线程提速明显。",
                    composerRevision = 1,
                    showStickerPicker = false,
                ),
            ),
        )
    }
}

// ── 08–09. Topic Composer & VPS Valuation Bottom Sheet ──────────────────────────────────

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview08TopicComposer() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        TopicComposerScreen(
            onBack = {},
            initialCategorySlug = "review",
            previewUiState = TopicComposerUiState(
                session = SHOWCASE_SESSION,
                settings = SHOWCASE_SETTINGS,
                availableBoards = Category.DEFAULT_CATEGORIES.filter { it.slug.isNotEmpty() },
                selectedCategorySlug = "review",
                selectedCategory = Category.DEFAULT_CATEGORIES.first { it.slug == "review" },
                requiredViewLevel = 1,
                title = "DMIT TYO.Pro.Tiny 半年长期稳定性观察：三网延迟曲线与流媒体解锁记录",
                markdownContent = "## 核心配置与实测结论\n\n- 地区：Tokyo Equinix TY8（双栈 BGP）\n- 晚高峰 21:00–23:00 电信/联通/移动三网均值 `< 52 ms`\n\n> 附带本地剩余价值折算与 YABS 完整日志。",
                editorRevision = 1,
                hasRestoredDraft = true,
            ),
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview09VpsValuation() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        Box(modifier = Modifier.fillMaxSize()) {
            TopicComposerScreen(
                onBack = {},
                initialCategorySlug = "trade",
                previewUiState = TopicComposerUiState(
                    session = SHOWCASE_SESSION,
                    settings = SHOWCASE_SETTINGS,
                    availableBoards = Category.DEFAULT_CATEGORIES.filter { it.slug.isNotEmpty() },
                    selectedCategorySlug = "trade",
                    selectedCategory = Category.DEFAULT_CATEGORIES.first { it.slug == "trade" },
                    title = "[出] DMIT TYO.Pro.Tiny 东京精品网",
                    markdownContent = "明盘出让，含 Push 过户与剩余价值明细。",
                    editorRevision = 1,
                ),
            )
            VpsValuationBottomSheet(
                primaryActionLabel = "插入发帖正文",
                onInsertMarkdown = {},
                onDismiss = {},
                initialMachineLabel = "DMIT TYO.Pro.Tiny 东京精品网",
                initialMarkdownStyle = ValuationMarkdownStyle.THREE_COLUMN_TABLE,
                initialMode = ValuationMode.TRANSFER_DEAL,
                initialCurrency = ValuationCurrency.USD,
                initialRenewalAmountText = "39.90",
                initialAskingPriceText = "165.00",
                initialPushFeeText = "5",
                initialMiddlemanEnabled = true,
            )
        }
    }
}

// ── 10–11. Pulse Hub Bento Dashboard & Daily Check-In Leaderboard ───────────────────────

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview10PulseHub() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.PAPER_LIGHT,
        bottomDockTab = PulseNavTab.PULSE_HUB,
        showFab = false,
        unreadCount = 3,
    ) {
        PulseHubScreen(
            onOpenTopic = { _, _, _ -> },
            onOpenUserProfile = { _, _ -> },
            onOpenCheckInBoard = {},
            onOpenInbox = {},
            onOpenSettings = {},
            previewUiState = SHOWCASE_PULSE_HUB_STATE,
            previewRadarUnreadCount = 2,
            previewRadarRules = SHOWCASE_RADAR_RULES,
            previewRadarTrackedTopics = SHOWCASE_RADAR_TRACKED,
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview11CheckInLeaderboard() {
    val members = listOf(
        CheckInMember(rank = 1, uid = 101L, username = "tokyo_core", gain = 18),
        CheckInMember(rank = 2, uid = 202L, username = "bgp_artisan", gain = 16),
        CheckInMember(rank = 3, uid = 303L, username = "compose_craft", gain = 15),
        CheckInMember(rank = 4, uid = 1024L, username = "latency_hunter", gain = 12),
        CheckInMember(rank = 5, uid = 404L, username = "cloud_nomad", gain = 11),
        CheckInMember(rank = 6, uid = 505L, username = "ipv6_pilot", gain = 10),
        CheckInMember(rank = 7, uid = 606L, username = "mtr_scanner", gain = 9),
    )
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        CheckInBoardScreen(
            onBack = {},
            onOpenUserProfile = { _, _ -> },
            previewUiState = CheckInBoardUiState(
                currentUid = 1024L,
                currentUsername = "latency_hunter",
                preferRandomCheckIn = true,
                hasCheckedInToday = true,
                todayGain = 12,
                todayOrder = 4,
                totalCheckedInUsers = 4820,
                members = members,
                currentPage = 1,
                hasNextPage = true,
                isRefreshing = false,
            ),
        )
    }
}

// ── 12–14. Inbox Notifications, Direct Messages & User Profile ──────────────────────────

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview12InboxNotifications() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        InboxScreen(
            onBack = {},
            onOpenTopic = { _, _, _ -> },
            onOpenDirectMessage = { _, _ -> },
            onOpenUserProfile = { _, _ -> },
            previewHasSeekMailKey = true,
            previewRadarUnreadCount = 2,
            previewUiState = InboxUiState(
                selectedTab = InboxTab.REPLIES,
                replies = listOf(
                    NotificationItem(
                        id = 1L,
                        kind = NotificationKind.REPLIES,
                        topicId = 884210L,
                        topicTitle = "【深度测评】东京 Tier-1 BGP 双栈小鸡首发实测",
                        floorNumber = 18,
                        senderUid = 2048L,
                        senderName = "bgp_artisan",
                        createdAtIso = "2026-11-16T01:32:00Z",
                        isRead = false,
                        excerpt = "实测联通 AS4837 在开启 BBRv3 后晚高峰 4K 流畅不降速，已加鸡腿！",
                    ),
                    NotificationItem(
                        id = 2L,
                        kind = NotificationKind.REPLIES,
                        topicId = 883301L,
                        topicTitle = "开源自用 Jetpack Compose Material 3 Expressive 论坛客户端 Anode",
                        floorNumber = 12,
                        senderUid = 5120L,
                        senderName = "compose_craft",
                        createdAtIso = "2026-11-16T00:50:00Z",
                        isRead = false,
                        excerpt = "Pure Pitch AMOLED 夜间对比度太舒服了，求问 Seek Mail 验证码识别支持正则自定义吗？",
                    ),
                    NotificationItem(
                        id = 3L,
                        kind = NotificationKind.REPLIES,
                        topicId = 883905L,
                        topicTitle = "自建 WireGuard + Bird2 任意播选路笔记",
                        floorNumber = 7,
                        senderUid = 4102L,
                        senderName = "cloud_nomad",
                        createdAtIso = "2026-11-15T22:18:00Z",
                        isRead = true,
                        excerpt = "感谢方案！把 MSS clamping 设为 1360 后彻底解决了内网大包卡顿。",
                    ),
                ),
                mentions = listOf(
                    NotificationItem(
                        id = 4L,
                        kind = NotificationKind.MENTIONS,
                        topicId = 883712L,
                        topicTitle = "[情报] DMIT Tokyo Pro 限时补货预警",
                        floorNumber = 4,
                        senderUid = 3090L,
                        senderName = "bgp_watcher",
                        createdAtIso = "2026-11-16T01:15:00Z",
                        isRead = false,
                        excerpt = "@latency_hunter 之前测过的那款 TY8 同机房补货了",
                    ),
                ),
                messageThreads = listOf(
                    DirectMessageSummary(
                        peerUid = 4102L,
                        peerName = "cloud_nomad",
                        previewText = "Push 订单已提交，确认收到工单邮件后麻烦回复我一下",
                        updatedAtIso = "2026-11-16T01:20:00Z",
                        isRead = false,
                        unreadCount = 1,
                    ),
                ),
                isRefreshing = false,
                serverUnreadTotalCount = 4,
            ),
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview13DirectMessages() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        DirectMessageScreen(
            peerUid = 4102L,
            peerName = "cloud_nomad",
            onBack = {},
            onOpenUserProfile = { _, _ -> },
            previewUiState = DirectMessageUiState(
                peerUid = 4102L,
                peerName = "cloud_nomad",
                currentUid = 1024L,
                isLoading = false,
                messages = listOf(
                    DirectMessage(
                        id = 1L,
                        senderUid = 4102L,
                        receiverUid = 1024L,
                        body = "你好！看到你在帖子下问 DMIT TYO.Pro.Tiny，剩余 240 天刚在 Anode 里用估值器算完净值大概 ¥1,120，明盘 165 USD 包 Push 费。",
                        createdAtIso = "2026-11-16T01:12:00Z",
                    ),
                    DirectMessage(
                        id = 2L,
                        senderUid = 1024L,
                        receiverUid = 4102L,
                        body = "收到，我刚才用 `ops@seek.li` 别名试了收件没问题，走论坛标准 Push 即可，口令发你。",
                        isMarkdown = true,
                        createdAtIso = "2026-11-16T01:16:00Z",
                    ),
                    DirectMessage(
                        id = 3L,
                        senderUid = 4102L,
                        receiverUid = 1024L,
                        body = "Push 申请已经发起，Invoice 收据和确认码会发到你邮箱，注意查收！",
                        createdAtIso = "2026-11-16T01:20:00Z",
                    ),
                ),
            ),
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview14UserProfile() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        UserProfileScreen(
            uid = 1024L,
            onBack = {},
            onOpenTopic = { _, _, _ -> },
            usernameHint = "latency_hunter",
            previewUiState = UserProfileUiState(
                uid = 1024L,
                usernameHint = "latency_hunter",
                isOwnProfile = false,
                isSignedIn = true,
                isRefreshing = false,
                profile = UserProfile(
                    uid = 1024L,
                    username = "latency_hunter",
                    bio = "专注亚太 BGP 线路实测、WireGuard 选路与 Android 原生客户端工程化。",
                    readmeMarkdown = "- 常驻测试点：上海电信 / 杭州联通 / 广州移动\n- 常用工具：`NextTrace` · `YABS` · `Anode Pulse Radar`",
                    joinedAtIso = "2023-08-14T08:00:00Z",
                    drumsticks = 2860,
                    stardust = 42,
                    topicCount = 28,
                    replyCount = 314,
                    followingCount = 36,
                    followerCount = 194,
                    isFollowedByViewer = true,
                ),
                topics = SHOWCASE_TOPICS.take(3).map { it.copy(authorId = 1024L, authorName = "latency_hunter") },
                hasMoreTopics = true,
            ),
        )
    }
}

// ── 15–16. Pulse Radar Alerts & Keyword/Regex Watch Rules ───────────────────────────────

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview15PulseRadarAlerts() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        PulseRadarScreen(
            onBack = {},
            onOpenTopic = { _, _, _ -> },
            initialTab = 0,
            previewUiState = PulseRadarUiState(
                selectedFilter = RadarAlertFilter.ALL,
                alerts = SHOWCASE_RADAR_ALERTS,
                filteredAlerts = SHOWCASE_RADAR_ALERTS,
                unreadAlertCount = 2,
                keywordHitCount = 2,
                trackedReplyCount = 1,
                keywordRules = SHOWCASE_RADAR_RULES,
                trackedTopics = SHOWCASE_RADAR_TRACKED,
                pollingSettings = RadarPollingSettings(
                    isEnabled = true,
                    foregroundIntervalMinutes = 2,
                    backgroundIntervalMinutes = 5,
                ),
                runtimeStatus = RadarRuntimeStatus(
                    isRunningInForeground = true,
                    lastCheckEpochMillis = 1794800150000L,
                ),
                environmentDiagnostics = RadarEnvironmentDiagnostics(
                    hasNotificationPermission = true,
                    isIgnoringBatteryOptimizations = true,
                    isSystemDoNotDisturbActive = false,
                ),
            ),
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview16PulseRadarRules() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        PulseRadarScreen(
            onBack = {},
            onOpenTopic = { _, _, _ -> },
            initialTab = 1,
            previewUiState = PulseRadarUiState(
                selectedFilter = RadarAlertFilter.ALL,
                alerts = SHOWCASE_RADAR_ALERTS,
                filteredAlerts = SHOWCASE_RADAR_ALERTS,
                unreadAlertCount = 2,
                keywordHitCount = 2,
                trackedReplyCount = 1,
                keywordRules = SHOWCASE_RADAR_RULES,
                trackedTopics = SHOWCASE_RADAR_TRACKED,
                pollingSettings = RadarPollingSettings(
                    isEnabled = true,
                    foregroundIntervalMinutes = 2,
                    backgroundIntervalMinutes = 5,
                    quietHoursEnabled = true,
                ),
                runtimeStatus = RadarRuntimeStatus(
                    isRunningInForeground = true,
                    lastCheckEpochMillis = 1794800150000L,
                ),
                environmentDiagnostics = RadarEnvironmentDiagnostics(
                    hasNotificationPermission = true,
                    isIgnoringBatteryOptimizations = true,
                    isSystemDoNotDisturbActive = false,
                ),
            ),
        )
    }
}

// ── 17–18. Seek Mail Client (Inbox + OTP Extraction & Isolated Reader) ──────────────────

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview17SeekMailInbox() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.PAPER_LIGHT,
        bottomDockTab = PulseNavTab.SEEK_MAIL,
        showFab = false,
        unreadCount = 3,
    ) {
        SeekMailScreen(
            onBack = {},
            showBackButton = false,
            bottomContentPadding = 96.dp,
            previewUiState = SeekMailUiState(
                hasConfiguredKey = true,
                apiKeyMaskedHint = "sk_mail_••••981c",
                mailboxes = SHOWCASE_MAILBOXES,
                selectedMailboxId = null,
                filterMode = SeekMailFilterMode.ALL,
                tags = SHOWCASE_MAIL_TAGS,
                mails = SHOWCASE_MAIL_ITEMS,
                totalMailCount = 3,
                hasMoreMails = false,
                rateLimit = SeekMailRateLimit(limit = 60, remaining = 56, resetEpochSeconds = 0L),
            ),
        )
    }
}

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview18SeekMailReader() {
    val mailSummary = SHOWCASE_MAIL_ITEMS.first()
    val sanitizedHtml = """
        <h3>Cloudflare Zero Trust 登录验证</h3>
        <p>我们检测到一次来自 <strong>Anode Android 客户端</strong> 的控制台访问请求，请在 10 分钟内输入下方一次性验证码：</p>
        <pre>849206</pre>
        <blockquote>如非本人操作，请立即前往 Account Security 吊销当前设备会话并轮换 API Token。</blockquote>
        <p>安全提示：Anode 内置 Seek 邮箱阅读器默认禁用 JavaScript 并拦截远程追踪像素，仅在你主动授权时加载外链图片。</p>
    """.trimIndent()
    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        SeekMailScreen(
            onBack = {},
            initialMailId = mailSummary.id,
            previewUiState = SeekMailUiState(
                hasConfiguredKey = true,
                apiKeyMaskedHint = "sk_mail_••••981c",
                mailboxes = SHOWCASE_MAILBOXES,
                mails = SHOWCASE_MAIL_ITEMS,
                openedMailId = mailSummary.id,
                openedPreviewSummary = mailSummary,
                openedDetail = SeekMailDetail(
                    summary = mailSummary,
                    bodyText = "Your Cloudflare verification code: 849206",
                    sanitizedBodyHtml = sanitizedHtml,
                    hasRemoteImages = true,
                    authTelemetry = SeekMailAuthTelemetry(
                        spf = "pass",
                        dkim = "pass",
                        dmarc = "pass",
                        fromMismatch = false,
                        authTotalScore = 100,
                        finalScore = 98,
                        decision = "PASS",
                    ),
                ),
                readerMode = SeekMailReaderMode.HTML,
                attachments = listOf(
                    SeekMailAttachment(
                        id = "att_01",
                        mailId = mailSummary.id,
                        fileName = "security-audit-log-2026-11.pdf",
                        contentType = "application/pdf",
                        sizeBytes = 128_640L,
                    ),
                ),
                allowRemoteImages = false,
                rateLimit = SeekMailRateLimit(limit = 60, remaining = 55, resetEpochSeconds = 0L),
            ),
        )
    }
}

// ── 19–20. Settings & Design System Gallery ─────────────────────────────────────────────

@Preview(device = PHONE_DEVICE_SPEC, showBackground = true)
@Composable
fun Preview19Settings() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        SettingsScreen(
            onBack = {},
            previewUiState = SettingsUiState(
                settings = SHOWCASE_SETTINGS,
                session = SHOWCASE_SESSION,
            ),
            previewRadarPollingSettings = RadarPollingSettings(
                isEnabled = true,
                foregroundIntervalMinutes = 2,
                backgroundIntervalMinutes = 5,
            ),
            previewRadarUnreadCount = 2,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(widthDp = 412, showBackground = true)
@Composable
fun Preview20DesignSystemComponents() {
    CompositionLocalProvider(LocalPreviewAvatarResResolver provides ::resolveShowcaseAvatarRes) {
        NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Anode · Design Tokens & Telemetry",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrumstickCheckInChip(checkedIn = false, isSignedIn = true, onClick = {})
                    DrumstickCheckInChip(checkedIn = true, todayGain = 12, isSignedIn = true, onClick = {})
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (lv in 0..6) {
                        RankMicroBadge(level = lv, showPersona = lv >= 5)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("tech", "review", "info", "dev", "trade", "carpool", "daily", "inside").forEach { slug ->
                        CategoryMicroTag(slug = slug)
                    }
                }

                SortSegmentedSwitcher(
                    options = listOf(
                        SortSegmentItem("reply", "最新回复"),
                        SortSegmentItem("newest", "最新发布"),
                        SortSegmentItem("hot", "热门精选"),
                        SortSegmentItem("curated", "推荐阅读"),
                    ),
                    selectedValue = "reply",
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FloatingPillDock(
                        selectedTab = PulseNavTab.EXPLORE,
                        onSelectTab = {},
                        unreadNotificationCount = 5,
                        modifier = Modifier.weight(1f),
                    )
                    CollapsingSquircleFab(onClick = {}, expanded = true)
                }

                PulseTopicCard(
                    topic = PulseTopicCardModel(
                        id = 884210L,
                        title = "【深度测评】东京 Tier-1 BGP 双栈小鸡首发实测：三网回程与 Geekbench 6",
                        categorySlug = "review",
                        categoryName = "测评",
                        authorId = 1024L,
                        authorName = "latency_hunter",
                        viewCount = 6840,
                        replyCount = 142,
                        lastActivityIso = "3m",
                        isPinned = false,
                        requiredViewLevel = null,
                        isRead = false,
                        lastReadFloor = null,
                        isBookmarked = true,
                        isHot = true,
                    ),
                    onClick = {},
                    onBookmarkToggle = {},
                    onAuthorClick = {},
                    onCategoryClick = {},
                )

                MarkdownRenderer(
                    markdown = """
                        ```ansi
                        ${'\u001B'}[1;36mYet-Another-Bench-Script (YABS)${'\u001B'}[0m
                        CPU         : AMD EPYC 9654 @ 3.70GHz
                        Geekbench 6 : ${'\u001B'}[1;32m2,418 Single${'\u001B'}[0m | ${'\u001B'}[1;35m9,860 Multi${'\u001B'}[0m
                        ```
                    """.trimIndent(),
                    onMentionClick = {},
                    onFloorClick = {},
                    onUrlClick = {},
                )
            }
        }
        }
    }
}

// ── 21–25. Material You Dynamic Color Wallpaper Showcase ────────────────────────────────

private val SHOWCASE_SEEK_MAIL_INBOX_STATE = SeekMailUiState(
    hasConfiguredKey = true,
    apiKeyMaskedHint = "sk_mail_••••981c",
    mailboxes = SHOWCASE_MAILBOXES,
    selectedMailboxId = null,
    filterMode = SeekMailFilterMode.ALL,
    tags = SHOWCASE_MAIL_TAGS,
    mails = SHOWCASE_MAIL_ITEMS,
    totalMailCount = 3,
    hasMoreMails = false,
    rateLimit = SeekMailRateLimit(limit = 60, remaining = 56, resetEpochSeconds = 0L),
)

@Preview(
    device = PHONE_DEVICE_SPEC,
    showBackground = true,
    wallpaper = Wallpapers.GREEN_DOMINATED_EXAMPLE,
)
@Composable
fun Preview21DynamicGreenExploreLight() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.PAPER_LIGHT,
        dynamicColor = true,
        bottomDockTab = PulseNavTab.EXPLORE,
        unreadCount = 3,
    ) {
        ExploreScreen(
            onOpenTopic = { _, _, _ -> },
            onOpenSearch = {},
            onOpenBoardsTab = {},
            previewUiState = SHOWCASE_EXPLORE_STATE,
        )
    }
}

@Preview(
    device = PHONE_DEVICE_SPEC,
    showBackground = true,
    wallpaper = Wallpapers.RED_DOMINATED_EXAMPLE,
)
@Composable
fun Preview22DynamicRedExploreDark() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.OBSIDIAN_DARK,
        dynamicColor = true,
        bottomDockTab = PulseNavTab.EXPLORE,
        unreadCount = 3,
    ) {
        ExploreScreen(
            onOpenTopic = { _, _, _ -> },
            onOpenSearch = {},
            onOpenBoardsTab = {},
            previewUiState = SHOWCASE_EXPLORE_STATE,
        )
    }
}

@Preview(
    device = PHONE_DEVICE_SPEC,
    showBackground = true,
    wallpaper = Wallpapers.GREEN_DOMINATED_EXAMPLE,
)
@Composable
fun Preview23DynamicGreenPulseHubLight() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.PAPER_LIGHT,
        dynamicColor = true,
        bottomDockTab = PulseNavTab.PULSE_HUB,
        showFab = false,
        unreadCount = 3,
    ) {
        PulseHubScreen(
            onOpenTopic = { _, _, _ -> },
            onOpenUserProfile = { _, _ -> },
            onOpenCheckInBoard = {},
            onOpenInbox = {},
            onOpenSettings = {},
            previewUiState = SHOWCASE_PULSE_HUB_STATE,
            previewRadarUnreadCount = 2,
            previewRadarRules = SHOWCASE_RADAR_RULES,
            previewRadarTrackedTopics = SHOWCASE_RADAR_TRACKED,
        )
    }
}

@Preview(
    device = PHONE_DEVICE_SPEC,
    showBackground = true,
    wallpaper = Wallpapers.YELLOW_DOMINATED_EXAMPLE,
)
@Composable
fun Preview24DynamicYellowPulseHubDark() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.OBSIDIAN_DARK,
        dynamicColor = true,
        bottomDockTab = PulseNavTab.PULSE_HUB,
        showFab = false,
        unreadCount = 3,
    ) {
        PulseHubScreen(
            onOpenTopic = { _, _, _ -> },
            onOpenUserProfile = { _, _ -> },
            onOpenCheckInBoard = {},
            onOpenInbox = {},
            onOpenSettings = {},
            previewUiState = SHOWCASE_PULSE_HUB_STATE,
            previewRadarUnreadCount = 2,
            previewRadarRules = SHOWCASE_RADAR_RULES,
            previewRadarTrackedTopics = SHOWCASE_RADAR_TRACKED,
        )
    }
}

@Preview(
    device = PHONE_DEVICE_SPEC,
    showBackground = true,
    wallpaper = Wallpapers.RED_DOMINATED_EXAMPLE,
)
@Composable
fun Preview25DynamicRedSeekMailLight() {
    PreviewPhoneFrame(
        themeMode = PulseThemeMode.PAPER_LIGHT,
        dynamicColor = true,
        bottomDockTab = PulseNavTab.SEEK_MAIL,
        showFab = false,
        unreadCount = 3,
    ) {
        SeekMailScreen(
            onBack = {},
            showBackButton = false,
            bottomContentPadding = 96.dp,
            previewUiState = SHOWCASE_SEEK_MAIL_INBOX_STATE,
        )
    }
}
