package com.nodeseek.app.domain.activity

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ForumWatch 活动条目领域模型（预留结构）。
 */
@Immutable
data class ForumWatchActivityItem(
    val id: String,
    val title: String,
    val summary: String = "",
    val sourceUrl: String = "",
    val organizer: String = "",
    val startsAtEpochMillis: Long? = null,
    val endsAtEpochMillis: Long? = null,
)

/**
 * 活动流状态快照。
 */
@Immutable
data class ActivityFeedSnapshot(
    val activities: List<ForumWatchActivityItem> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
)

/**
 * ForumWatch 活动数据源接入接口。
 *
 * TODO(ForumWatch): 对接 ForumWatch 活动列表与详情 API，补充鉴权、分页与离线缓存策略。
 */
interface ActivityRepository {
    val snapshot: StateFlow<ActivityFeedSnapshot>

    suspend fun loadCachedActivities(): ActivityFeedSnapshot

    suspend fun refreshActivities(force: Boolean = false): Result<ActivityFeedSnapshot>
}

/**
 * ForumWatch 接入前的默认空状态实现。
 */
class EmptyActivityRepository : ActivityRepository {
    private val _snapshot = MutableStateFlow(ActivityFeedSnapshot())
    override val snapshot: StateFlow<ActivityFeedSnapshot> = _snapshot.asStateFlow()

    override suspend fun loadCachedActivities(): ActivityFeedSnapshot {
        // TODO(ForumWatch): 从本地磁盘缓存读取最近同步的 ForumWatch 活动列表
        return _snapshot.value
    }

    override suspend fun refreshActivities(force: Boolean): Result<ActivityFeedSnapshot> {
        // TODO(ForumWatch): 请求 ForumWatch API 拉取最新活动数据并写入本地缓存
        return Result.success(_snapshot.value)
    }
}
