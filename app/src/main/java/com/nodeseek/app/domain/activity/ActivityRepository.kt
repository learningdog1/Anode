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
     * 基于当前设备时钟动态判定活动时间状态，使本地缓存条目在跨过开奖/截止时刻后即时变为「已结束」；
     * 同时尊重服务端 `forceEnded` / 锁帖 / 人工结案已标记的 `ENDED` 终态，绝不反向复活为进行中。
     */
    fun resolveLiveStatus(nowEpochMillis: Long = System.currentTimeMillis()): ActivityTimeStatus {
        if (status == ActivityTimeStatus.ENDED) {
            return ActivityTimeStatus.ENDED
        }
        val endsAt = endsAtEpochMillis?.takeIf { it > 0L }
        val startsAt = startsAtEpochMillis?.takeIf { it > 0L }
        if (endsAt != null && nowEpochMillis >= endsAt) {
            return ActivityTimeStatus.ENDED
        }
        if (startsAt != null && nowEpochMillis < startsAt) {
            return ActivityTimeStatus.UPCOMING
        }
        if (startsAt != null && (endsAt == null || nowEpochMillis < endsAt)) {
            return ActivityTimeStatus.ONGOING
        }
        return status
    }

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
    val errorMessage: String? = null,
    val loadedFromCache: Boolean = false,
    val lastSyncedEpochMillis: Long = 0L,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
) {
    fun liveCounts(nowEpochMillis: Long = System.currentTimeMillis()): ActivityCounts {
        if (activities.isEmpty()) return counts
        var ongoing = 0
        var upcoming = 0
        var ended = 0
        for (item in activities) {
            when (item.resolveLiveStatus(nowEpochMillis)) {
                ActivityTimeStatus.ONGOING -> ongoing++
                ActivityTimeStatus.UPCOMING -> upcoming++
                ActivityTimeStatus.ENDED -> ended++
            }
        }
        return ActivityCounts(
            all = activities.size,
            ongoing = ongoing,
            upcoming = upcoming,
            ended = ended,
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
