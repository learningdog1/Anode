package com.nodeseek.app.data.activity

import android.content.Context
import com.nodeseek.app.domain.activity.ActivityCounts
import com.nodeseek.app.domain.activity.ActivityFeedSnapshot
import com.nodeseek.app.domain.activity.ActivityKind
import com.nodeseek.app.domain.activity.ActivityLabeledLink
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.ActivityTimeStatus
import com.nodeseek.app.domain.activity.ForumWatchActivityDetail
import com.nodeseek.app.domain.activity.ForumWatchActivityItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

@Serializable
private data class CachedActivitiesListPayload(
    val version: Int = 1,
    val updatedAtEpochMillis: Long = 0L,
    val lastSyncedEpochMillis: Long = 0L,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val counts: ActivityCounts = ActivityCounts(),
    val etag: String? = null,
    val lastModified: String? = null,
    val items: List<ForumWatchActivityItem> = emptyList(),
)

@Serializable
private data class CachedActivityDetailEntry(
    val detail: ForumWatchActivityDetail,
    val etag: String? = null,
    val lastModified: String? = null,
    val cachedAtEpochMillis: Long = 0L,
)

@Serializable
private data class CachedActivityDetailsPayload(
    val version: Int = 1,
    val entries: List<CachedActivityDetailEntry> = emptyList(),
)

/**
 * Production [ActivityRepository] backed by [ForumWatchActivityApiClient] + atomic JSON disk cache:
 * - Persists `ETag` (`If-None-Match`) across restarts for zero-byte `304 Not Modified` polling,
 * - Bypasses `If-None-Match` on explicit manual pull-to-refresh (`force = true`) while preserving
 *   offline cache fallback if the network is unreachable,
 * - Coalesces concurrent list/detail requests via single-flight `Deferred` deduplication,
 * - Deduplicates identical NodeSeek topics/URLs across manual & auto-collected entries.
 */
class ForumWatchActivityRepositoryImpl(
    private val apiClient: ForumWatchActivityApiClient,
    private val listCacheFile: File,
    private val detailCacheFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    externalScope: CoroutineScope? = null,
) : ActivityRepository {

    constructor(
        context: Context,
        apiClient: ForumWatchActivityApiClient = ForumWatchActivityApiClient(),
        externalScope: CoroutineScope? = null,
    ) : this(
        apiClient = apiClient,
        listCacheFile = File((context.applicationContext ?: context).filesDir, LIST_CACHE_FILE_NAME),
        detailCacheFile = File((context.applicationContext ?: context).filesDir, DETAIL_CACHE_FILE_NAME),
        externalScope = externalScope,
    )

    private val repoScope: CoroutineScope = externalScope ?: CoroutineScope(SupervisorJob() + ioDispatcher)

    private val diskJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val cacheMutex = Mutex()
    private val inFlightMutex = Mutex()
    private val inFlightListCalls = HashMap<String, Deferred<Result<ActivityFeedSnapshot>>>()
    private val inFlightDetailCalls = HashMap<String, Deferred<Result<ForumWatchActivityDetail>>>()

    @Volatile
    private var cacheLoaded: Boolean = false

    @Volatile
    private var cachedListEtag: String? = null

    @Volatile
    private var cachedListLastModified: String? = null

    private val detailCacheMap = LinkedHashMap<String, CachedActivityDetailEntry>()

    private val _snapshot = MutableStateFlow(ActivityFeedSnapshot())
    override val snapshot: StateFlow<ActivityFeedSnapshot> = _snapshot.asStateFlow()

    override suspend fun loadCachedActivities(): ActivityFeedSnapshot {
        ensureDiskCacheLoaded()
        return _snapshot.value
    }

    override suspend fun refreshActivities(force: Boolean): Result<ActivityFeedSnapshot> {
        val flightKey = if (force) "list:force" else "list:cond"
        val deferred = inFlightMutex.withLock {
            inFlightListCalls[flightKey]?.takeIf { it.isActive } ?: repoScope.async {
                try {
                    performRefreshActivities(force = force)
                } finally {
                    inFlightMutex.withLock {
                        inFlightListCalls.remove(flightKey)
                    }
                }
            }.also { created ->
                inFlightListCalls[flightKey] = created
            }
        }
        return deferred.await()
    }

    override suspend fun getActivityDetail(
        id: String,
        forceRefresh: Boolean,
    ): Result<ForumWatchActivityDetail> {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Activity ID must not be blank"))
        }
        val flightKey = "$cleanId|force=$forceRefresh"
        val deferred = inFlightMutex.withLock {
            inFlightDetailCalls[flightKey]?.takeIf { it.isActive } ?: repoScope.async {
                try {
                    performGetActivityDetail(cleanId = cleanId, forceRefresh = forceRefresh)
                } finally {
                    inFlightMutex.withLock {
                        inFlightDetailCalls.remove(flightKey)
                    }
                }
            }.also { created ->
                inFlightDetailCalls[flightKey] = created
            }
        }
        return deferred.await()
    }

    private suspend fun ensureDiskCacheLoaded() {
        if (cacheLoaded) return
        cacheMutex.withLock {
            if (cacheLoaded) return@withLock
            val listPayload = withContext(ioDispatcher) {
                readJsonFileOrNull<CachedActivitiesListPayload>(listCacheFile)
            }
            val detailsPayload = withContext(ioDispatcher) {
                readJsonFileOrNull<CachedActivityDetailsPayload>(detailCacheFile)
            }

            if (detailsPayload != null) {
                detailCacheMap.clear()
                for (entry in detailsPayload.entries) {
                    val itemId = entry.detail.item.id.trim()
                    if (itemId.isNotEmpty()) {
                        detailCacheMap[itemId] = entry
                    }
                }
                trimDetailCacheLocked()
            }

            if (listPayload != null) {
                val now = clockMillis()
                val deduplicated = deduplicateAndSortActivities(listPayload.items, now)
                cachedListEtag = listPayload.etag?.takeIf { it.isNotBlank() }
                cachedListLastModified = listPayload.lastModified?.takeIf { it.isNotBlank() }
                _snapshot.update { current ->
                    val baseSnap = ActivityFeedSnapshot(
                        activities = deduplicated,
                        updatedAtEpochMillis = listPayload.updatedAtEpochMillis,
                        counts = listPayload.counts,
                        isRefreshing = current.isRefreshing,
                        errorMessage = null,
                        loadedFromCache = deduplicated.isNotEmpty() || listPayload.lastSyncedEpochMillis > 0L,
                        lastSyncedEpochMillis = listPayload.lastSyncedEpochMillis,
                        totalCount = listPayload.totalCount.coerceAtLeast(deduplicated.size),
                        hasMore = listPayload.hasMore,
                    )
                    baseSnap.copy(counts = baseSnap.liveCounts(now))
                }
            }

            cacheLoaded = true
        }
    }

    private suspend fun performRefreshActivities(force: Boolean): Result<ActivityFeedSnapshot> {
        ensureDiskCacheLoaded()

        _snapshot.update { it.copy(isRefreshing = true, errorMessage = null) }

        val sendEtag = if (!force) cachedListEtag else null
        val sendLastModified = if (!force && sendEtag == null) cachedListLastModified else null

        return try {
            val conditional = apiClient.fetchActivitiesPageConditional(
                page = 1,
                pageSize = ForumWatchActivityApiClient.DEFAULT_PAGE_SIZE,
                status = "all",
                ifNoneMatch = sendEtag,
                ifModifiedSince = sendLastModified,
            )
            val now = clockMillis()

            if (conditional.notModified) {
                val nextEtag = conditional.etag ?: cachedListEtag
                val nextLastMod = conditional.lastModified ?: cachedListLastModified
                cachedListEtag = nextEtag
                cachedListLastModified = nextLastMod

                val updatedSnapshot = _snapshot.updateAndGetCompat { prev ->
                    val refreshed = prev.copy(
                        isRefreshing = false,
                        errorMessage = null,
                        lastSyncedEpochMillis = now,
                    )
                    refreshed.copy(counts = refreshed.liveCounts(now))
                }

                persistListCache(
                    payload = CachedActivitiesListPayload(
                        updatedAtEpochMillis = updatedSnapshot.updatedAtEpochMillis,
                        lastSyncedEpochMillis = now,
                        totalCount = updatedSnapshot.totalCount,
                        hasMore = updatedSnapshot.hasMore,
                        counts = updatedSnapshot.counts,
                        etag = nextEtag,
                        lastModified = nextLastMod,
                        items = updatedSnapshot.activities,
                    ),
                )
                Result.success(updatedSnapshot)
            } else {
                val pageData = conditional.data
                    ?: throw IllegalStateException("Empty ForumWatch activities response body")
                val deduplicated = deduplicateAndSortActivities(pageData.items, now)
                cachedListEtag = conditional.etag
                cachedListLastModified = conditional.lastModified

                val resolvedUpdatedAt = pageData.updatedAtEpochMillis.takeIf { it > 0L } ?: now
                val baseSnapshot = ActivityFeedSnapshot(
                    activities = deduplicated,
                    updatedAtEpochMillis = resolvedUpdatedAt,
                    counts = pageData.counts,
                    isRefreshing = false,
                    errorMessage = null,
                    loadedFromCache = false,
                    lastSyncedEpochMillis = now,
                    totalCount = pageData.total.coerceAtLeast(deduplicated.size),
                    hasMore = pageData.hasMore,
                )
                val finalSnapshot = baseSnapshot.copy(counts = baseSnapshot.liveCounts(now))
                _snapshot.value = finalSnapshot

                persistListCache(
                    payload = CachedActivitiesListPayload(
                        updatedAtEpochMillis = resolvedUpdatedAt,
                        lastSyncedEpochMillis = now,
                        totalCount = finalSnapshot.totalCount,
                        hasMore = finalSnapshot.hasMore,
                        counts = finalSnapshot.counts,
                        etag = conditional.etag,
                        lastModified = conditional.lastModified,
                        items = deduplicated,
                    ),
                )
                Result.success(finalSnapshot)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val friendlyError = t.message?.takeIf { it.isNotBlank() } ?: "活动数据同步失败，请稍后重试"
            _snapshot.update { prev ->
                prev.copy(
                    isRefreshing = false,
                    errorMessage = friendlyError,
                )
            }
            Result.failure(t)
        }
    }

    private suspend fun performGetActivityDetail(
        cleanId: String,
        forceRefresh: Boolean,
    ): Result<ForumWatchActivityDetail> {
        ensureDiskCacheLoaded()
        val cachedEntry = cacheMutex.withLock { detailCacheMap[cleanId] }
        val sendEtag = if (!forceRefresh && cachedEntry != null) cachedEntry.etag else null
        val sendLastModified = if (!forceRefresh && cachedEntry != null && sendEtag == null) {
            cachedEntry.lastModified
        } else {
            null
        }

        return try {
            val conditional = apiClient.fetchActivityDetailConditional(
                activityId = cleanId,
                ifNoneMatch = sendEtag,
                ifModifiedSince = sendLastModified,
            )
            val now = clockMillis()

            if (conditional.notModified && cachedEntry != null) {
                val refreshedEntry = cachedEntry.copy(
                    etag = conditional.etag ?: cachedEntry.etag,
                    lastModified = conditional.lastModified ?: cachedEntry.lastModified,
                    cachedAtEpochMillis = now,
                )
                saveDetailEntry(cleanId, refreshedEntry)
                Result.success(refreshedEntry.detail)
            } else {
                val detail = conditional.data
                    ?: cachedEntry?.detail
                    ?: throw IllegalStateException("Empty ForumWatch activity detail response")
                val entry = CachedActivityDetailEntry(
                    detail = detail,
                    etag = conditional.etag,
                    lastModified = conditional.lastModified,
                    cachedAtEpochMillis = now,
                )
                saveDetailEntry(cleanId, entry)
                Result.success(detail)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (cachedEntry != null) {
                Result.success(cachedEntry.detail)
            } else {
                // Synthesize graceful fallback from list snapshot if offline and item is already in memory
                val listMatch = _snapshot.value.activities.firstOrNull { it.id == cleanId }
                if (listMatch != null && listMatch.summary.isNotBlank()) {
                    Result.success(
                        ForumWatchActivityDetail(
                            item = listMatch,
                            bodyMarkdown = listMatch.summary,
                            externalLinks = buildList {
                                listMatch.luckyUrl?.takeIf { it.isNotBlank() }?.let {
                                    add(ActivityLabeledLink(text = "开奖链接", url = it))
                                }
                            },
                        ),
                    )
                } else {
                    Result.failure(t)
                }
            }
        }
    }

    private suspend fun saveDetailEntry(id: String, entry: CachedActivityDetailEntry) {
        val snapshotEntries = cacheMutex.withLock {
            detailCacheMap.remove(id)
            detailCacheMap[id] = entry
            trimDetailCacheLocked()
            detailCacheMap.values.toList()
        }
        val encoded = diskJson.encodeToString(CachedActivityDetailsPayload(entries = snapshotEntries))
        withContext(ioDispatcher) {
            writeAtomicText(detailCacheFile, encoded)
        }
    }

    private fun trimDetailCacheLocked() {
        while (detailCacheMap.size > MAX_CACHED_DETAILS) {
            val eldestKey = detailCacheMap.entries.firstOrNull()?.key ?: break
            detailCacheMap.remove(eldestKey)
        }
    }

    private suspend fun persistListCache(payload: CachedActivitiesListPayload) {
        val encoded = cacheMutex.withLock {
            diskJson.encodeToString(payload)
        }
        withContext(ioDispatcher) {
            writeAtomicText(listCacheFile, encoded)
        }
    }

    private inline fun <reified T> readJsonFileOrNull(file: File): T? {
        if (!file.exists()) return null
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) null else diskJson.decodeFromString<T>(raw)
        }.getOrNull()
    }

    private fun writeAtomicText(targetFile: File, content: String) {
        runCatching {
            targetFile.parentFile?.mkdirs()
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            tempFile.writeText(content, Charsets.UTF_8)
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeText(content, Charsets.UTF_8)
                tempFile.delete()
            }
        }
    }

    private inline fun <T> MutableStateFlow<T>.updateAndGetCompat(transform: (T) -> T): T {
        while (true) {
            val prev = value
            val next = transform(prev)
            if (compareAndSet(prev, next)) {
                return next
            }
        }
    }

    companion object {
        const val LIST_CACHE_FILE_NAME = "forumwatch_activities_v1.json"
        const val DETAIL_CACHE_FILE_NAME = "forumwatch_activity_details_v1.json"
        private const val MAX_CACHED_DETAILS = 60

        /**
         * Deduplicates activities by primary `id` and secondary NodeSeek `topicId` / normalized `sourceUrl`
         * (preferring `pinned`, `"manual"` curated records, or newer `updatedAtEpochMillis`), then sorts
         * `pinned` first -> live status (`ONGOING` -> `UPCOMING` -> `ENDED`) -> newest timestamp first.
         */
        internal fun deduplicateAndSortActivities(
            items: List<ForumWatchActivityItem>,
            nowEpochMillis: Long,
        ): List<ForumWatchActivityItem> {
            if (items.isEmpty()) return emptyList()

            val byId = LinkedHashMap<String, ForumWatchActivityItem>(items.size)
            for (item in items) {
                val id = item.id.trim()
                if (id.isEmpty() || item.title.isBlank()) continue
                val existing = byId[id]
                if (existing == null || shouldPreferCandidate(candidate = item, existing = existing)) {
                    byId[id] = item
                }
            }

            val byCanonicalSource = LinkedHashMap<String, ForumWatchActivityItem>(byId.size)
            for (item in byId.values) {
                val canonicalKey = buildSecondaryDedupKey(item)
                val existing = byCanonicalSource[canonicalKey]
                if (existing == null || shouldPreferCandidate(candidate = item, existing = existing)) {
                    byCanonicalSource[canonicalKey] = item
                }
            }

            return byCanonicalSource.values.sortedWith(
                Comparator { a, b ->
                    if (a.pinned != b.pinned) {
                        return@Comparator if (a.pinned) -1 else 1
                    }
                    val statusOrderA = statusSortWeight(a.resolveLiveStatus(nowEpochMillis))
                    val statusOrderB = statusSortWeight(b.resolveLiveStatus(nowEpochMillis))
                    if (statusOrderA != statusOrderB) {
                        return@Comparator statusOrderA.compareTo(statusOrderB)
                    }
                    val timeA = maxOf(a.createdAtEpochMillis, a.updatedAtEpochMillis)
                    val timeB = maxOf(b.createdAtEpochMillis, b.updatedAtEpochMillis)
                    timeB.compareTo(timeA)
                },
            )
        }

        private fun statusSortWeight(status: ActivityTimeStatus): Int =
            when (status) {
                ActivityTimeStatus.ONGOING -> 0
                ActivityTimeStatus.UPCOMING -> 1
                ActivityTimeStatus.ENDED -> 2
            }

        private fun shouldPreferCandidate(
            candidate: ForumWatchActivityItem,
            existing: ForumWatchActivityItem,
        ): Boolean {
            if (candidate.pinned != existing.pinned) return candidate.pinned
            val candidateManual = candidate.sourceId.equals("manual", ignoreCase = true)
            val existingManual = existing.sourceId.equals("manual", ignoreCase = true)
            if (candidateManual != existingManual) return candidateManual
            return candidate.updatedAtEpochMillis >= existing.updatedAtEpochMillis
        }

        private fun buildSecondaryDedupKey(item: ForumWatchActivityItem): String {
            item.resolvedNodeSeekTopicId()?.let { topicId ->
                return "ns_topic:$topicId"
            }
            val cleanUrl = normalizeSourceUrl(item.sourceUrl)
            if (cleanUrl.isNotEmpty()) {
                return "url:$cleanUrl"
            }
            return "id:${item.id.trim()}"
        }

        private fun normalizeSourceUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return ""
            val withoutFragment = trimmed.substringBefore('#').trimEnd('/')
            return withoutFragment.lowercase(Locale.ROOT)
        }
    }
}

/**
 * Rich in-memory sample [ActivityRepository] for Compose `@Preview`s and `PreviewAppContainer`.
 */
class PreviewForumWatchActivityRepository(
    nowEpochMillis: Long = 1_763_820_000_000L,
) : ActivityRepository {

    private val sampleItems = listOf(
        ForumWatchActivityItem(
            id = "nodeseek-618001",
            title = "【周年福利】DMIT 东京 Pro.Tiny 年付免单抽奖（共 3 台）",
            summary = "评论区回复任意测机口令即可参与，开奖工具自动抽取 3 位幸运坛友赠送年付机器。",
            sourceUrl = "https://www.nodeseek.com/post-618001-1",
            organizer = "dmit_official",
            startsAtEpochMillis = nowEpochMillis - 3_600_000L * 6,
            endsAtEpochMillis = nowEpochMillis + 3_600_000L * 18,
            status = ActivityTimeStatus.ONGOING,
            kind = ActivityKind.LOTTERY,
            pinned = true,
            sourceId = "nodeseek",
            topicId = "618001",
            category = "日常",
            categorySlug = "daily",
            luckyUrl = "https://lucky.nodeseek.com/?post=618001",
            drawFloor = 200,
            prize = "DMIT TYO.Pro.Tiny 年付 × 3",
            participation = "回复本帖任意内容（每人限一次）",
            tags = listOf("抽奖", "DMIT", "免单"),
            createdAtEpochMillis = nowEpochMillis - 3_600_000L * 6,
            updatedAtEpochMillis = nowEpochMillis - 1_800_000L,
        ),
        ForumWatchActivityItem(
            id = "nodeseek-617890",
            title = "【周末赠鸡】50 张 NodeImage 10GB 专业图床流量包免费领",
            summary = "回帖留下 UID 自动发放兑换码，先到先得送完即止。",
            sourceUrl = "https://www.nodeseek.com/post-617890-1",
            organizer = "compose_craft",
            startsAtEpochMillis = nowEpochMillis - 3_600_000L * 12,
            endsAtEpochMillis = nowEpochMillis + 3_600_000L * 36,
            status = ActivityTimeStatus.ONGOING,
            kind = ActivityKind.GIVEAWAY,
            pinned = false,
            sourceId = "nodeseek",
            topicId = "617890",
            category = "情报",
            categorySlug = "info",
            prize = "NodeImage 10GB 流量兑换码 × 50",
            participation = "本帖回复留言即可领取",
            tags = listOf("福利", "图床"),
            createdAtEpochMillis = nowEpochMillis - 3_600_000L * 12,
            updatedAtEpochMillis = nowEpochMillis - 3_600_000L,
        ),
        ForumWatchActivityItem(
            id = "nodeseek-616500",
            title = "【已开奖】BGP.Tools 社区节点联合测速挑战赛",
            summary = "提交真实 YABS 与回程路由报告参与评选，获奖名单已在主贴更新。",
            sourceUrl = "https://www.nodeseek.com/post-616500-1",
            organizer = "latency_hunter",
            startsAtEpochMillis = nowEpochMillis - 86_400_000L * 4,
            endsAtEpochMillis = nowEpochMillis - 3_600_000L * 5,
            status = ActivityTimeStatus.ENDED,
            kind = ActivityKind.EVENT,
            pinned = false,
            sourceId = "nodeseek",
            topicId = "616500",
            category = "技术",
            categorySlug = "tech",
            prize = "社区定制周边与鸡腿奖励",
            tags = listOf("测评", "BGP"),
            createdAtEpochMillis = nowEpochMillis - 86_400_000L * 4,
            updatedAtEpochMillis = nowEpochMillis - 3_600_000L * 5,
        ),
    )

    private val initialSnapshot = ActivityFeedSnapshot(
        activities = sampleItems,
        updatedAtEpochMillis = nowEpochMillis - 120_000L,
        counts = ActivityCounts(all = 3, ongoing = 2, upcoming = 0, ended = 1),
        lastSyncedEpochMillis = nowEpochMillis - 60_000L,
        totalCount = 3,
        hasMore = false,
    )

    private val _snapshot = MutableStateFlow(initialSnapshot)
    override val snapshot: StateFlow<ActivityFeedSnapshot> = _snapshot.asStateFlow()

    override suspend fun loadCachedActivities(): ActivityFeedSnapshot = _snapshot.value

    override suspend fun refreshActivities(force: Boolean): Result<ActivityFeedSnapshot> =
        Result.success(_snapshot.value)

    override suspend fun getActivityDetail(
        id: String,
        forceRefresh: Boolean,
    ): Result<ForumWatchActivityDetail> {
        val found = sampleItems.firstOrNull { it.id == id } ?: sampleItems.first()
        return Result.success(
            ForumWatchActivityDetail(
                item = found,
                bodyMarkdown = buildString {
                    appendLine("### 活动说明")
                    appendLine(found.summary)
                    appendLine()
                    found.prize?.let { appendLine("- **活动奖品**：$it") }
                    found.participation?.let { appendLine("- **参与方式**：$it") }
                    found.drawFloor?.let { appendLine("- **开奖楼层**：#$it 楼") }
                }.trim(),
                externalLinks = buildList {
                    found.luckyUrl?.let { add(ActivityLabeledLink("官方开奖页面", it)) }
                },
            ),
        )
    }
}
