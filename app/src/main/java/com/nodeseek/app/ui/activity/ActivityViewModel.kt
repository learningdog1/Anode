package com.nodeseek.app.ui.activity

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.domain.activity.ActivityCounts
import com.nodeseek.app.domain.activity.ActivityFeedSnapshot
import com.nodeseek.app.domain.activity.ActivityLotteryClassifier
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.ActivityTimeStatus
import com.nodeseek.app.domain.activity.ForumWatchActivityDetail
import com.nodeseek.app.domain.activity.ForumWatchActivityItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 一级活动分类筛选：明确区分「全部」、「抽奖」（仅包含明确抽奖链接）与「其他活动」（福利/特惠/社区活动等），
 * 避免非抽奖活动被误解为「已结束」或混入抽奖状态筛选。
 */
enum class ActivityCategoryFilter(
    val label: String,
) {
    ALL("全部"),
    LOTTERY("抽奖"),
    OTHER("其他活动"),
}

/**
 * 二级抽奖开奖状态筛选：仅在选中 [ActivityCategoryFilter.LOTTERY] 时展示并生效。
 */
enum class LotteryStatusFilter(
    val label: String,
) {
    ALL("全部"),
    ONGOING("进行中"),
    UPCOMING("即将开始"),
    ENDED("已结束"),
    ;

    fun matches(status: ActivityTimeStatus?): Boolean {
        if (status == null) return false
        return when (this) {
            ALL -> true
            ONGOING -> status == ActivityTimeStatus.ONGOING
            UPCOMING -> status == ActivityTimeStatus.UPCOMING
            ENDED -> status == ActivityTimeStatus.ENDED
        }
    }
}

@Immutable
data class ActivityDetailSheetState(
    val item: ForumWatchActivityItem,
    val isLoading: Boolean = false,
    val detail: ForumWatchActivityDetail? = null,
    val errorMessage: String? = null,
)

@Immutable
data class ActivityUiState(
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val activities: List<ForumWatchActivityItem> = emptyList(),
    val filteredActivities: List<ForumWatchActivityItem> = emptyList(),
    val selectedCategory: ActivityCategoryFilter = ActivityCategoryFilter.ALL,
    val selectedLotteryStatus: LotteryStatusFilter = LotteryStatusFilter.ALL,
    val counts: ActivityCounts = ActivityCounts(),
    val lastSyncedEpochMillis: Long = 0L,
    val errorMessage: String? = null,
    val nowEpochMillis: Long = System.currentTimeMillis(),
    val detailSheet: ActivityDetailSheetState? = null,
) {
    val showLotteryStatusFilters: Boolean
        get() = selectedCategory == ActivityCategoryFilter.LOTTERY
}

class ActivityViewModel(
    private val repository: ActivityRepository,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    enableClockTicker: Boolean = true,
) : ViewModel() {

    private data class LocalControls(
        val hasBootstrapped: Boolean = false,
        val selectedCategory: ActivityCategoryFilter = ActivityCategoryFilter.ALL,
        val selectedLotteryStatus: LotteryStatusFilter = LotteryStatusFilter.ALL,
        val nowEpochMillis: Long = 0L,
        val detailSheet: ActivityDetailSheetState? = null,
    )

    private val localControls = MutableStateFlow(LocalControls(nowEpochMillis = clockMillis()))
    private var detailLoadJob: Job? = null

    val uiState: StateFlow<ActivityUiState> = combine(
        repository.snapshot,
        localControls,
    ) { snap, controls ->
        buildUiState(snap = snap, controls = controls)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = buildUiState(
            snap = repository.snapshot.value,
            controls = localControls.value,
        ),
    )

    init {
        viewModelScope.launch {
            val cached = repository.loadCachedActivities()
            val now = clockMillis()
            localControls.update { it.copy(hasBootstrapped = true, nowEpochMillis = now) }
            val shouldRefresh = cached.activities.isEmpty() ||
                cached.lastSyncedEpochMillis <= 0L ||
                (now - cached.lastSyncedEpochMillis) > AUTO_REFRESH_STALE_MS
            if (shouldRefresh) {
                repository.refreshActivities(force = false)
            }
        }

        if (enableClockTicker) {
            viewModelScope.launch {
                while (isActive) {
                    delay(LIVE_CLOCK_TICK_MS)
                    localControls.update { it.copy(nowEpochMillis = clockMillis()) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            localControls.update { it.copy(nowEpochMillis = clockMillis()) }
            repository.refreshActivities(force = true)
        }
    }

    fun selectCategory(category: ActivityCategoryFilter) {
        localControls.update { current ->
            val nextLotteryStatus = if (category == current.selectedCategory) {
                current.selectedLotteryStatus
            } else {
                LotteryStatusFilter.ALL
            }
            current.copy(
                selectedCategory = category,
                selectedLotteryStatus = nextLotteryStatus,
                nowEpochMillis = clockMillis(),
            )
        }
    }

    fun selectLotteryStatus(status: LotteryStatusFilter) {
        localControls.update { current ->
            current.copy(
                selectedCategory = ActivityCategoryFilter.LOTTERY,
                selectedLotteryStatus = status,
                nowEpochMillis = clockMillis(),
            )
        }
    }

    fun resetFilters() {
        localControls.update { current ->
            current.copy(
                selectedCategory = ActivityCategoryFilter.ALL,
                selectedLotteryStatus = LotteryStatusFilter.ALL,
                nowEpochMillis = clockMillis(),
            )
        }
    }

    fun openDetailSheet(item: ForumWatchActivityItem) {
        detailLoadJob?.cancel()
        val normalizedItem = ActivityLotteryClassifier.normalizeItem(item)
        localControls.update {
            it.copy(
                detailSheet = ActivityDetailSheetState(
                    item = normalizedItem,
                    isLoading = true,
                    detail = null,
                    errorMessage = null,
                ),
            )
        }
        detailLoadJob = viewModelScope.launch {
            val result = repository.getActivityDetail(id = normalizedItem.id, forceRefresh = false)
            result.fold(
                onSuccess = { loaded ->
                    val normalizedLoaded = ActivityLotteryClassifier.normalizeDetail(loaded)
                    localControls.update { current ->
                        val active = current.detailSheet ?: return@update current
                        if (active.item.id != normalizedItem.id) return@update current
                        current.copy(
                            detailSheet = active.copy(
                                item = normalizedLoaded.item,
                                isLoading = false,
                                detail = normalizedLoaded,
                                errorMessage = null,
                            ),
                        )
                    }
                },
                onFailure = { err ->
                    localControls.update { current ->
                        val active = current.detailSheet ?: return@update current
                        if (active.item.id != normalizedItem.id) return@update current
                        current.copy(
                            detailSheet = active.copy(
                                isLoading = false,
                                errorMessage = err.message?.takeIf { m -> m.isNotBlank() } ?: "加载活动详情失败",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun retryDetailSheet() {
        val currentTarget = localControls.value.detailSheet?.item ?: return
        detailLoadJob?.cancel()
        localControls.update { current ->
            val active = current.detailSheet ?: return@update current
            current.copy(
                detailSheet = active.copy(isLoading = true, errorMessage = null),
            )
        }
        detailLoadJob = viewModelScope.launch {
            val result = repository.getActivityDetail(id = currentTarget.id, forceRefresh = true)
            result.fold(
                onSuccess = { loaded ->
                    val normalizedLoaded = ActivityLotteryClassifier.normalizeDetail(loaded)
                    localControls.update { current ->
                        val active = current.detailSheet ?: return@update current
                        if (active.item.id != currentTarget.id) return@update current
                        current.copy(
                            detailSheet = active.copy(
                                item = normalizedLoaded.item,
                                isLoading = false,
                                detail = normalizedLoaded,
                                errorMessage = null,
                            ),
                        )
                    }
                },
                onFailure = { err ->
                    localControls.update { current ->
                        val active = current.detailSheet ?: return@update current
                        if (active.item.id != currentTarget.id) return@update current
                        current.copy(
                            detailSheet = active.copy(
                                isLoading = false,
                                errorMessage = err.message?.takeIf { m -> m.isNotBlank() } ?: "加载活动详情失败",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun dismissDetailSheet() {
        detailLoadJob?.cancel()
        detailLoadJob = null
        localControls.update { it.copy(detailSheet = null) }
    }

    private fun buildUiState(
        snap: ActivityFeedSnapshot,
        controls: LocalControls,
    ): ActivityUiState {
        val now = controls.nowEpochMillis
        val normalizedActivities = snap.activities.map(ActivityLotteryClassifier::normalizeItem)
        val liveCounts = snap.copy(activities = normalizedActivities).liveCounts(now)
        val filtered = when (controls.selectedCategory) {
            ActivityCategoryFilter.ALL -> normalizedActivities
            ActivityCategoryFilter.OTHER -> normalizedActivities.filter { !it.isLottery }
            ActivityCategoryFilter.LOTTERY -> normalizedActivities.filter { item ->
                controls.selectedLotteryStatus.matches(item.resolveLotteryStatusOrNull(now))
            }
        }
        val initialLoading = (!controls.hasBootstrapped || snap.isRefreshing) &&
            normalizedActivities.isEmpty() &&
            snap.errorMessage == null &&
            snap.lastSyncedEpochMillis == 0L

        return ActivityUiState(
            isInitialLoading = initialLoading,
            isRefreshing = snap.isRefreshing,
            activities = normalizedActivities,
            filteredActivities = filtered,
            selectedCategory = controls.selectedCategory,
            selectedLotteryStatus = controls.selectedLotteryStatus,
            counts = liveCounts,
            lastSyncedEpochMillis = snap.lastSyncedEpochMillis,
            errorMessage = snap.errorMessage,
            nowEpochMillis = now,
            detailSheet = controls.detailSheet,
        )
    }

    companion object {
        private const val AUTO_REFRESH_STALE_MS = 120_000L
        private const val LIVE_CLOCK_TICK_MS = 30_000L

        fun factory(repository: ActivityRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ActivityViewModel(repository = repository) as T
                }
            }
    }
}
