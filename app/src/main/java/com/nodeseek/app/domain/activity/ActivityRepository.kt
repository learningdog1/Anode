package com.nodeseek.app.domain.activity

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class ActivityTimeStatus(
    val wireValue: String,
    val displayLabel: String,
) {
    @SerialName("ongoing")
    ONGOING(wireValue = "ongoing", displayLabel = "进行中"),

    @SerialName("upcoming")
    UPCOMING(wireValue = "upcoming", displayLabel = "即将开始"),

    @SerialName("ended")
    ENDED(wireValue = "ended", displayLabel = "已结束"),
    ;

    companion object {
        fun fromWire(raw: String?): ActivityTimeStatus =
            when (raw?.trim()?.lowercase(Locale.ROOT)) {
                "upcoming" -> UPCOMING
                "ended" -> ENDED
                else -> ONGOING
            }
    }
}

@Serializable
enum class ActivityKind(
    val wireValue: String,
    val displayLabel: String,
) {
    @SerialName("lottery")
    LOTTERY(wireValue = "lottery", displayLabel = "抽奖"),

    @SerialName("giveaway")
    GIVEAWAY(wireValue = "giveaway", displayLabel = "赠送福利"),

    @SerialName("promo")
    PROMO(wireValue = "promo", displayLabel = "限时特惠"),

    @SerialName("event")
    EVENT(wireValue = "event", displayLabel = "社区活动"),

    @SerialName("floor")
    FLOOR(wireValue = "floor", displayLabel = "抢楼互动"),

    @SerialName("other")
    OTHER(wireValue = "other", displayLabel = "其他专题"),
    ;

    companion object {
        fun fromWire(raw: String?): ActivityKind =
            when (raw?.trim()?.lowercase(Locale.ROOT)) {
                "lottery" -> LOTTERY
                "giveaway" -> GIVEAWAY
                "promo" -> PROMO
                "event" -> EVENT
                "floor" -> FLOOR
                "other" -> OTHER
                else -> EVENT
            }
    }
}

@Immutable
@Serializable
data class ActivityLabeledLink(
    val text: String = "",
    val url: String = "",
)

@Immutable
@Serializable
data class ActivityCounts(
    val all: Int = 0,
    val ongoing: Int = 0,
    val upcoming: Int = 0,
    val ended: Int = 0,
    val lottery: Int = ongoing + upcoming + ended,
    val other: Int = (all - (ongoing + upcoming + ended)).coerceAtLeast(0),
)

/**
 * ForumWatch 活动条目领域模型。
 */
@Immutable
@Serializable
data class ForumWatchActivityItem(
    val id: String,
    val title: String,
    val summary: String = "",
    val sourceUrl: String = "",
    val organizer: String = "",
    val startsAtEpochMillis: Long? = null,
    val endsAtEpochMillis: Long? = null,
    val organizerUrl: String? = null,
    val status: ActivityTimeStatus = ActivityTimeStatus.ONGOING,
    val kind: ActivityKind = ActivityKind.EVENT,
    val pinned: Boolean = false,
    val sourceId: String = "",
    val topicId: String? = null,
    val category: String = "",
    val categorySlug: String = "",
    val luckyUrl: String? = null,
    val drawFloor: Int? = null,
    val prize: String? = null,
    val participation: String? = null,
    val tags: List<String> = emptyList(),
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
) {
    /**
     * 已校验的明确抽奖/开奖链接（仅当条目包含明确的抽奖工具或开奖地址时非空）。
     */
    val resolvedLotteryUrl: String?
        get() = ActivityLotteryClassifier.findExplicitLotteryUrl(
            luckyUrl = luckyUrl,
            summary = summary,
            sourceUrl = sourceUrl,
        )

    /**
     * 仅当条目包含明确抽奖链接时才归类为「抽奖」。
     */
    val isLottery: Boolean
        get() = resolvedLotteryUrl != null

    /**
     * 规范化后的活动分类：带明确抽奖链接的条目统一为 [ActivityKind.LOTTERY]；
     * 无明确抽奖链接但上游按标题误标为 `LOTTERY` 的条目会降级为非抽奖分类，避免在列表/通知中误导。
     */
    val effectiveKind: ActivityKind
        get() = if (isLottery) {
            ActivityKind.LOTTERY
        } else {
            ActivityLotteryClassifier.resolveNonLotteryFallbackKind(this)
        }

    /**
     * 解析抽奖开奖时间戳（优先取 `endsAtEpochMillis`，缺失时从明确抽奖链接的 `time` 等参数提取）。
     */
    fun resolveDrawTimeEpochMillis(): Long? =
        endsAtEpochMillis?.takeIf { it > 0L }
            ?: ActivityLotteryClassifier.extractDrawTimeEpochMillis(resolvedLotteryUrl)

    /**
     * 仅针对包含明确抽奖链接的「抽奖」返回时间状态（进行中 / 即将开始 / 已结束）；
     * 非抽奖类活动始终返回 `null`，绝不归入「已结束」等抽奖状态桶。
     */
    fun resolveLotteryStatusOrNull(nowEpochMillis: Long = System.currentTimeMillis()): ActivityTimeStatus? =
        ActivityLotteryClassifier.resolveLotteryStatusOrNull(this, nowEpochMillis)

    /**
     * 基于当前设备时钟与开奖/起止时间动态判定时间状态，使本地缓存条目在跨过开奖时刻后即时变为「已结束」；
     * 同时尊重服务端 `forceEnded` / 锁帖 / 人工结案已标记的 `ENDED` 终态，绝不反向复活为进行中。
     *
     * 当缺少开奖时间（`drawAt == null`）且未处于终态或未到未来开始时间时，默认回退为 `ONGOING`
     * （若 `startsAt == null` 且服务端明确标记 `UPCOMING` 则保持 `UPCOMING`）。
     */
    fun resolveLiveStatus(nowEpochMillis: Long = System.currentTimeMillis()): ActivityTimeStatus =
        ActivityLotteryClassifier.resolveTimeStatusFromBounds(
            serverStatus = status,
            startsAtEpochMillis = startsAtEpochMillis,
            drawAtEpochMillis = resolveDrawTimeEpochMillis(),
            nowEpochMillis = nowEpochMillis,
        )

    /**
     * 若当前活动来源于 NodeSeek 社区帖子，提取对应的原生帖子 ID 以支持直跳客户端帖子阅读器。
     */
    fun resolvedNodeSeekTopicId(): Long? {
        val cleanSource = sourceId.trim().lowercase(Locale.ROOT)
        val urlLooksNodeSeek = sourceUrl.contains("nodeseek.com", ignoreCase = true)
        if (cleanSource == "nodeseek" || urlLooksNodeSeek) {
            topicId?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        }
        if (urlLooksNodeSeek) {
            NODESEEK_POST_ID_REGEX.find(sourceUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        return null
    }

    /**
     * Canonical source deduplication key shared by repository merging and notification dedup so
     * that upgrading an auto-collected item (`nodeseek-123`) to a manually curated item (`manual-1`)
     * pointing to the same NodeSeek topic or URL never triggers duplicate notifications.
     */
    fun canonicalDedupKey(): String {
        resolvedNodeSeekTopicId()?.let { resolvedTopicId ->
            return "ns_topic:$resolvedTopicId"
        }
        val trimmedUrl = sourceUrl.trim()
        if (trimmedUrl.isNotEmpty()) {
            val normalizedUrl = trimmedUrl.substringBefore('#').trimEnd('/').lowercase(Locale.ROOT)
            if (normalizedUrl.isNotEmpty()) {
                return "url:$normalizedUrl"
            }
        }
        return "id:${id.trim()}"
    }

    companion object {
        private val NODESEEK_POST_ID_REGEX = Regex(
            pattern = """nodeseek\.com/post-(\d+)""",
            option = RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * ForumWatch 单条活动详情（包含清洗后的 1 楼 Markdown 正文与外部链接）。
 */
@Immutable
@Serializable
data class ForumWatchActivityDetail(
    val item: ForumWatchActivityItem,
    val bodyMarkdown: String = "",
    val externalLinks: List<ActivityLabeledLink> = emptyList(),
)

/**
 * 活动流状态快照。
 */
@Immutable
data class ActivityFeedSnapshot(
    val activities: List<ForumWatchActivityItem> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
    val counts: ActivityCounts = ActivityCounts(),
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val loadedFromCache: Boolean = false,
    val lastSyncedEpochMillis: Long = 0L,
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
) {
    fun liveCounts(nowEpochMillis: Long = System.currentTimeMillis()): ActivityCounts {
        if (activities.isEmpty()) return counts
        var ongoing = 0
        var upcoming = 0
        var ended = 0
        var other = 0
        for (item in activities) {
            when (item.resolveLotteryStatusOrNull(nowEpochMillis)) {
                ActivityTimeStatus.ONGOING -> ongoing++
                ActivityTimeStatus.UPCOMING -> upcoming++
                ActivityTimeStatus.ENDED -> ended++
                null -> other++
            }
        }
        val lotteryTotal = ongoing + upcoming + ended
        return ActivityCounts(
            all = activities.size,
            ongoing = ongoing,
            upcoming = upcoming,
            ended = ended,
            lottery = lotteryTotal,
            other = other,
        )
    }
}

/**
 * ForumWatch 活动数据源接口。
 */
interface ActivityRepository {
    val snapshot: StateFlow<ActivityFeedSnapshot>

    suspend fun loadCachedActivities(): ActivityFeedSnapshot

    suspend fun refreshActivities(force: Boolean = false): Result<ActivityFeedSnapshot>

    suspend fun loadNextPage(): Result<ActivityFeedSnapshot> = Result.success(snapshot.value)

    suspend fun getActivityDetail(
        id: String,
        forceRefresh: Boolean = false,
    ): Result<ForumWatchActivityDetail>
}

/**
 * 空状态兜底实现（用于测试与轻量预览）。
 */
class EmptyActivityRepository : ActivityRepository {
    private val _snapshot = MutableStateFlow(ActivityFeedSnapshot())
    override val snapshot: StateFlow<ActivityFeedSnapshot> = _snapshot.asStateFlow()

    override suspend fun loadCachedActivities(): ActivityFeedSnapshot = _snapshot.value

    override suspend fun refreshActivities(force: Boolean): Result<ActivityFeedSnapshot> =
        Result.success(_snapshot.value)

    override suspend fun getActivityDetail(
        id: String,
        forceRefresh: Boolean,
    ): Result<ForumWatchActivityDetail> =
        Result.failure(NoSuchElementException("Activity detail unavailable: $id"))
}
