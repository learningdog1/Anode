package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.RadarWatermarkStore
import com.nodeseek.app.data.local.RadarWatermarks
import com.nodeseek.app.data.network.ConversationResponseDto
import com.nodeseek.app.data.network.DirectMessageRowDto
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.domain.model.DirectMessage
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.radar.InAppMessageKind
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.domain.radar.InboxTargetResolver
import com.nodeseek.app.domain.repository.InboxRepository
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object SuppressInAppPopupsContext : CoroutineContext.Key<SuppressInAppPopupsElement>

internal object SuppressInAppPopupsElement : AbstractCoroutineContextElement(SuppressInAppPopupsContext)

/**
 * Concrete [InboxRepository] syncing unread counts from both HTML `__config__.user.unViewedCount.all`
 * and `/api/notification/unread-count`, plus reply/@mention notifications and 1-on-1 direct messages.
 *
 * Read mutations (`markNotificationsRead`, `markMessagesRead`, `markThreadRead`) update shared list
 * states and [unreadTotalCount] optimistically before network completion and protect them against
 * stale concurrent HTML piggybacks or lagging count endpoints via a monotonic guard window.
 */
class InboxRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val sessionRepository: SessionRepositoryImpl,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val watermarkStore: RadarWatermarkStore? = null,
) : InboxRepository {

    private enum class CountSource {
        PIGGYBACK,
        API,
    }

    private class NotificationCategoryLedger {
        var loaded: Boolean = false
        var rawItems: List<NotificationItem> = emptyList()
        val flow = MutableStateFlow<List<NotificationItem>>(emptyList())
        val locallyReadIds = mutableMapOf<Long, Long>()
        var allReadWatermark: Pair<Long, Long>? = null
        var popupBaselineInitialized: Boolean = false
        var popupWatermarkId: Long = 0L

        fun clear() {
            loaded = false
            rawItems = emptyList()
            locallyReadIds.clear()
            allReadWatermark = null
            popupBaselineInitialized = false
            popupWatermarkId = 0L
            flow.value = emptyList()
        }

        fun prune(now: Long) {
            val iter = locallyReadIds.entries.iterator()
            while (iter.hasNext()) {
                if (now - iter.next().value >= LOCAL_READ_RETENTION_MS) {
                    iter.remove()
                }
            }
            val watermark = allReadWatermark
            if (watermark != null && now - watermark.second >= LOCAL_READ_RETENTION_MS) {
                allReadWatermark = null
            }
        }

        fun isRead(item: NotificationItem, now: Long): Boolean {
            if (item.isRead) return true
            val idTimestamp = locallyReadIds[item.id]
            if (idTimestamp != null && now - idTimestamp < LOCAL_READ_RETENTION_MS) {
                return true
            }
            val watermark = allReadWatermark
            return watermark != null &&
                now - watermark.second < LOCAL_READ_RETENTION_MS &&
                item.id <= watermark.first
        }

        fun effectiveItems(now: Long): List<NotificationItem> =
            rawItems.map { item ->
                if (!item.isRead && isRead(item, now)) {
                    item.copy(isRead = true)
                } else {
                    item
                }
            }

        fun effectiveUnreadCount(now: Long): Int = rawItems.count { !isRead(it, now) }

        fun emit(now: Long) {
            flow.value = effectiveItems(now)
        }
    }

    private val stateLock = Any()
    private val foregroundSyncMutex = Mutex()
    private val repliesLedger = NotificationCategoryLedger()
    private val mentionsLedger = NotificationCategoryLedger()

    private var threadsLoaded: Boolean = false
    private var rawThreads: List<DirectMessageSummary> = emptyList()
    private val _messageThreads = MutableStateFlow<List<DirectMessageSummary>>(emptyList())
    override val messageThreads: StateFlow<List<DirectMessageSummary>> = _messageThreads.asStateFlow()

    private val locallyReadMessageIds = mutableMapOf<Long, Long>()
    private val locallyReadPeerWatermarks = mutableMapOf<Long, Pair<Long, Long>>()
    private var allMessagesReadWatermark: Pair<Long, Long>? = null
    private val serverUnreadMessageIdsByPeer = mutableMapOf<Long, MutableSet<Long>>()
    private val maxMessageIdByPeer = mutableMapOf<Long, Long>()
    private val messagePeerById = mutableMapOf<Long, Long>()
    private val cachedListMessagesByPeer = mutableMapOf<Long, List<DirectMessageRowDto>>()

    private var dmPopupBaselineInitialized: Boolean = false
    private val dmPopupWatermarkByPeer = mutableMapOf<Long, Long>()
    private val dmPopupSignatureByPeer = mutableMapOf<Long, String>()
    private val emittedInteractionFloorKeys = LinkedHashMap<Pair<Long, Int>, Long>()
    private val emittedPopupKeys = LinkedHashSet<String>()
    private var persistedBaselineCheckedForUid: Long = 0L
    private var lastForegroundCheckMs: Long = 0L
    private var lastFullListSyncMs: Long = 0L
    private var lastDmListSyncMs: Long = 0L
    private var foregroundRateLimitUntilMs: Long = 0L
    private var foregroundBackoffUntilMs: Long = 0L

    private var trackedUid: Long = sessionRepository.sessionState.value?.uid ?: 0L
    private var readMutationGeneration: Long = 0L
    private var inFlightMarkReadOps: Int = 0
    private var piggybackGuardUntilMs: Long = 0L
    private var stalePiggybackCeiling: Int = 0
    private var apiCountGuardUntilMs: Long = 0L
    private var staleApiCeiling: Int = 0
    private var apiCountConfirmed: Boolean = true

    private val _unreadTotalCount = MutableStateFlow(0)
    override val unreadTotalCount: StateFlow<Int> = _unreadTotalCount.asStateFlow()
    override val replyNotifications: StateFlow<List<NotificationItem>> = repliesLedger.flow.asStateFlow()
    override val mentionNotifications: StateFlow<List<NotificationItem>> = mentionsLedger.flow.asStateFlow()

    private val _inAppPopupEvents = MutableSharedFlow<InAppMessagePopup>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val inAppPopupEvents: Flow<InAppMessagePopup> = _inAppPopupEvents.asSharedFlow()

    private val _unreadIncreaseEvents = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val unreadIncreaseEvents: Flow<Int> = _unreadIncreaseEvents.asSharedFlow()

    fun onPiggybackedUnreadCount(count: Int) {
        val sanitized = count.coerceAtLeast(0)
        var shouldSignalIncrease = false
        synchronized(stateLock) {
            val now = clockMillis()
            val resetHandled = syncAccountLocked()
            if (resetHandled && sessionRepository.sessionState.value == null) {
                return
            }
            val previousTotal = _unreadTotalCount.value
            reconcileServerUnreadCountLocked(
                count = sanitized,
                now = now,
                source = CountSource.PIGGYBACK,
            )
            val updatedTotal = _unreadTotalCount.value
            val allBaselinesReady = repliesLedger.popupBaselineInitialized &&
                mentionsLedger.popupBaselineInitialized &&
                dmPopupBaselineInitialized
            shouldSignalIncrease = sessionRepository.sessionState.value != null &&
                updatedTotal > previousTotal &&
                updatedTotal > 0 &&
                allBaselinesReady
        }
        if (shouldSignalIncrease) {
            _unreadIncreaseEvents.tryEmit(sanitized)
        }
    }

    override suspend fun refreshUnreadCount(): Result<Int> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (sessionRepository.sessionState.value == null) {
                synchronized(stateLock) {
                    trackedUid = 0L
                    clearAllStateLocked(resetTotalToZero = true)
                }
                return@runDomainCatching 0
            }
            val genBeforeRequest = synchronized(stateLock) {
                syncAccountLocked()
                readMutationGeneration
            }
            val count = apiClientProvider().getUnreadNotificationCount().coerceAtLeast(0)
            synchronized(stateLock) {
                syncAccountLocked()
                if (sessionRepository.sessionState.value == null) {
                    clearAllStateLocked(resetTotalToZero = true)
                    return@synchronized 0
                }
                if (genBeforeRequest != readMutationGeneration) {
                    return@synchronized _unreadTotalCount.value
                }
                reconcileServerUnreadCountLocked(
                    count = count,
                    now = clockMillis(),
                    source = CountSource.API,
                )
                _unreadTotalCount.value
            }
        }

    override suspend fun syncForegroundInbox(forceListSync: Boolean): Result<Unit> =
        foregroundSyncMutex.withLock {
            runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
                val currentSession = sessionRepository.sessionState.value ?: return@runDomainCatching
                val viewerUid = currentSession.uid
                if (viewerUid <= 0L) return@runDomainCatching

                val now = clockMillis()
                val rateLimited = synchronized(stateLock) {
                    now < foregroundRateLimitUntilMs || (!forceListSync && now < foregroundBackoffUntilMs)
                }
                if (rateLimited) return@runDomainCatching

                ensurePersistedBaselineSeeded(viewerUid)

                val suppressPopups = currentCoroutineContext()[SuppressInAppPopupsContext] != null
                var pendingLoadedPopups: List<InAppMessagePopup> = emptyList()
                var pendingReplyPersist: Long? = null
                var pendingMentionPersist: Long? = null
                var pendingDmPersist: Map<Long, Long> = emptyMap()
                val (initReplies, initMentions, initDm) = synchronized(stateLock) {
                    syncAccountLocked()
                    val allowEmit = !suppressPopups && _inAppPopupEvents.subscriptionCount.value > 0
                    if (allowEmit) {
                        val flushResult = flushPendingPopupsFromLoadedStateLocked(now = now, viewerUid = viewerUid)
                        pendingLoadedPopups = flushResult.popups
                        pendingReplyPersist = flushResult.replyWatermark
                        pendingMentionPersist = flushResult.mentionWatermark
                        pendingDmPersist = flushResult.dmWatermarks
                    }
                    Triple(
                        !repliesLedger.popupBaselineInitialized,
                        !mentionsLedger.popupBaselineInitialized,
                        !dmPopupBaselineInitialized,
                    )
                }
                if (pendingLoadedPopups.isNotEmpty()) {
                    for (popup in pendingLoadedPopups) {
                        _inAppPopupEvents.tryEmit(popup)
                    }
                    persistWatermarksToStore(
                        viewerUid = viewerUid,
                        replyWatermark = pendingReplyPersist,
                        mentionWatermark = pendingMentionPersist,
                        dmInitialized = pendingDmPersist.isNotEmpty(),
                        dmWatermarks = pendingDmPersist,
                    )
                }
                val needsBaselineInit = initReplies || initMentions || initDm

                if (!forceListSync && !needsBaselineInit) {
                    val tooSoon = synchronized(stateLock) {
                        lastForegroundCheckMs > 0L && (now - lastForegroundCheckMs) < FOREGROUND_CHECK_MIN_GAP_MS
                    }
                    if (tooSoon) return@runDomainCatching
                }

                if (needsBaselineInit) {
                    val repliesRes = if (initReplies) getNotifications(NotificationKind.REPLIES) else null
                    val mentionsRes = if (initMentions) getNotifications(NotificationKind.MENTIONS) else null
                    val dmRes = if (initDm) getMessageThreads() else null

                    val firstError = repliesRes?.exceptionOrNull()
                        ?: mentionsRes?.exceptionOrNull()
                        ?: dmRes?.exceptionOrNull()

                    if (firstError != null) {
                        recordForegroundSyncFailure(firstError)
                        throw firstError
                    }

                    val baselineSnapshot = synchronized(stateLock) {
                        BaselinePersistSnapshot(
                            replyWatermark = if (repliesLedger.popupBaselineInitialized) {
                                repliesLedger.popupWatermarkId
                            } else {
                                null
                            },
                            mentionWatermark = if (mentionsLedger.popupBaselineInitialized) {
                                mentionsLedger.popupWatermarkId
                            } else {
                                null
                            },
                            dmInitialized = dmPopupBaselineInitialized,
                            dmWatermarks = dmPopupWatermarkByPeer.toMap(),
                        )
                    }
                    persistWatermarksToStore(
                        viewerUid = viewerUid,
                        replyWatermark = baselineSnapshot.replyWatermark,
                        mentionWatermark = baselineSnapshot.mentionWatermark,
                        dmInitialized = baselineSnapshot.dmInitialized,
                        dmWatermarks = baselineSnapshot.dmWatermarks,
                    )

                    if (initReplies && initMentions && initDm) {
                        try {
                            refreshUnreadCount()
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            // Non-fatal; list floor already populated.
                        }
                        synchronized(stateLock) {
                            val ts = clockMillis()
                            lastForegroundCheckMs = ts
                            lastFullListSyncMs = ts
                            lastDmListSyncMs = ts
                            foregroundBackoffUntilMs = 0L
                        }
                        return@runDomainCatching
                    }
                }

                val syncState = synchronized(stateLock) {
                    ForegroundSyncStateSnapshot(
                        countBeforeCheck = _unreadTotalCount.value,
                        knownLocalUnread = knownListFloorLocked(clockMillis()),
                        lastFullSync = lastFullListSyncMs,
                        lastDmSync = lastDmListSyncMs,
                        repliesLoaded = repliesLedger.loaded,
                        mentionsLoaded = mentionsLedger.loaded,
                        threadsLoaded = threadsLoaded,
                    )
                }

                val effectiveCount = try {
                    if (forceListSync) {
                        _unreadTotalCount.value
                    } else {
                        refreshUnreadCount().getOrThrow()
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    recordForegroundSyncFailure(t)
                    throw t
                }

                val checkTime = clockMillis()
                val shouldSyncAllLists = forceListSync ||
                    !syncState.repliesLoaded ||
                    !syncState.mentionsLoaded ||
                    effectiveCount > syncState.countBeforeCheck ||
                    effectiveCount > syncState.knownLocalUnread ||
                    (effectiveCount == 0 && syncState.knownLocalUnread > 0) ||
                    (effectiveCount > 0 && (checkTime - syncState.lastFullSync) >= PERIODIC_NONZERO_LIST_SYNC_MS)

                val shouldSyncDmOnly = !shouldSyncAllLists &&
                    (!syncState.threadsLoaded || (checkTime - syncState.lastDmSync) >= PERIODIC_NONZERO_LIST_SYNC_MS)

                synchronized(stateLock) {
                    lastForegroundCheckMs = checkTime
                    foregroundBackoffUntilMs = 0L
                }

                if (shouldSyncAllLists) {
                    val repliesRes = getNotifications(NotificationKind.REPLIES)
                    val mentionsRes = getNotifications(NotificationKind.MENTIONS)
                    val dmRes = getMessageThreads()
                    val finishedAt = clockMillis()
                    synchronized(stateLock) {
                        if (dmRes.isSuccess) {
                            lastDmListSyncMs = finishedAt
                        }
                        if (repliesRes.isSuccess && mentionsRes.isSuccess && dmRes.isSuccess) {
                            lastFullListSyncMs = finishedAt
                        }
                    }
                    val firstError = repliesRes.exceptionOrNull()
                        ?: mentionsRes.exceptionOrNull()
                        ?: dmRes.exceptionOrNull()
                    if (firstError != null) {
                        recordForegroundSyncFailure(firstError)
                        throw firstError
                    }
                } else if (shouldSyncDmOnly) {
                    val dmRes = getMessageThreads()
                    dmRes.onSuccess {
                        synchronized(stateLock) {
                            lastDmListSyncMs = clockMillis()
                        }
                    }.onFailure { err ->
                        recordForegroundSyncFailure(err)
                        throw err
                    }
                }
            }
        }

    override suspend fun getNotifications(kind: NotificationKind): Result<List<NotificationItem>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val currentUid = sessionRepository.sessionState.value?.uid ?: 0L
            if (currentUid > 0L) {
                ensurePersistedBaselineSeeded(currentUid)
            }
            val response = when (kind) {
                NotificationKind.REPLIES -> apiClientProvider().listReplyNotifications()
                NotificationKind.MENTIONS -> apiClientProvider().listMentionNotifications()
            }
            val raw = response.data.map { row ->
                NotificationItem(
                    id = row.id,
                    kind = kind,
                    topicId = row.postId,
                    topicTitle = row.title,
                    floorNumber = row.floorId,
                    senderUid = row.commenterId,
                    senderName = row.commenterName,
                    createdAtIso = row.createdAt,
                    isRead = row.viewed,
                    senderAvatarUrl = avatarUrlForUid(row.commenterId),
                )
            }
            val suppressPopups = currentCoroutineContext()[SuppressInAppPopupsContext] != null
            var popupsToEmit: List<InAppMessagePopup> = emptyList()
            var viewerUidForPersist = 0L
            var updatedWatermarkForPersist: Long? = null
            val effective = synchronized(stateLock) {
                syncAccountLocked()
                val now = clockMillis()
                val viewerUid = sessionRepository.sessionState.value?.uid ?: 0L
                viewerUidForPersist = viewerUid
                val allowEmit = !suppressPopups && _inAppPopupEvents.subscriptionCount.value > 0
                val ledger = ledgerFor(kind)
                ledger.prune(now)
                raw.forEach { item ->
                    if (item.isRead) {
                        ledger.locallyReadIds.remove(item.id)
                    }
                }
                ledger.loaded = true
                ledger.rawItems = raw
                val resolvedEffective = ledger.effectiveItems(now)
                ledger.flow.value = resolvedEffective
                raiseTotalToKnownFloorLocked(now)
                popupsToEmit = detectNotificationPopupsLocked(
                    kind = kind,
                    ledger = ledger,
                    effectiveItems = resolvedEffective,
                    viewerUid = viewerUid,
                    now = now,
                    allowEmit = allowEmit,
                )
                if (popupsToEmit.isNotEmpty()) {
                    updatedWatermarkForPersist = ledger.popupWatermarkId
                }
                resolvedEffective
            }
            if (popupsToEmit.isNotEmpty()) {
                for (popup in popupsToEmit) {
                    _inAppPopupEvents.tryEmit(popup)
                }
                persistWatermarksToStore(
                    viewerUid = viewerUidForPersist,
                    replyWatermark = if (kind == NotificationKind.REPLIES) updatedWatermarkForPersist else null,
                    mentionWatermark = if (kind == NotificationKind.MENTIONS) updatedWatermarkForPersist else null,
                )
            }
            effective
        }

    override suspend fun markNotificationsRead(
        kind: NotificationKind,
        ids: List<Long>?,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (ids != null && ids.isEmpty()) {
                return@runDomainCatching
            }
            val distinctIds = ids?.distinct()
            var opTimestamp = 0L
            var opGeneration = 0L
            var addedIds: List<Long> = emptyList()
            var previousWatermark: Pair<Long, Long>? = null
            var appliedDelta = 0

            var viewerUidForPersist = 0L
            var markAllWatermarkForPersist: Long? = null
            synchronized(stateLock) {
                syncAccountLocked()
                val now = clockMillis()
                viewerUidForPersist = sessionRepository.sessionState.value?.uid ?: 0L
                opTimestamp = now
                val ledger = ledgerFor(kind)
                ledger.prune(now)
                val oldTotal = _unreadTotalCount.value
                val unreadBefore = ledger.effectiveUnreadCount(now)

                if (distinctIds == null) {
                    previousWatermark = ledger.allReadWatermark
                    val maxKnownId = ledger.rawItems.maxOfOrNull { it.id } ?: 0L
                    ledger.allReadWatermark = maxKnownId to now
                    if (ledger.popupBaselineInitialized && maxKnownId > ledger.popupWatermarkId) {
                        ledger.popupWatermarkId = maxKnownId
                    }
                    markAllWatermarkForPersist = ledger.popupWatermarkId.takeIf { ledger.popupBaselineInitialized }
                    val newlyAdded = mutableListOf<Long>()
                    ledger.rawItems.forEach { item ->
                        if (!item.isRead && !ledger.locallyReadIds.containsKey(item.id)) {
                            ledger.locallyReadIds[item.id] = now
                            newlyAdded += item.id
                        }
                    }
                    addedIds = newlyAdded
                    appliedDelta = unreadBefore
                } else {
                    val newlyAdded = mutableListOf<Long>()
                    var countNew = 0
                    distinctIds.forEach { id ->
                        val existingItem = ledger.rawItems.firstOrNull { it.id == id }
                        val alreadyRead = if (existingItem != null) {
                            ledger.isRead(existingItem, now)
                        } else {
                            ledger.locallyReadIds.containsKey(id)
                        }
                        if (!alreadyRead) {
                            countNew += 1
                        }
                        if (!ledger.locallyReadIds.containsKey(id)) {
                            ledger.locallyReadIds[id] = now
                            newlyAdded += id
                        }
                    }
                    addedIds = newlyAdded
                    appliedDelta = countNew
                }

                ledger.emit(now)
                beginOptimisticMarkLocked(now = now, oldTotal = oldTotal)
                opGeneration = readMutationGeneration
                val floorAfter = knownListFloorLocked(now)
                _unreadTotalCount.value = maxOf(floorAfter, (oldTotal - appliedDelta).coerceAtLeast(0))
            }

            try {
                when (kind) {
                    NotificationKind.REPLIES -> apiClientProvider().markReplyNotificationsViewed(distinctIds)
                    NotificationKind.MENTIONS -> apiClientProvider().markMentionNotificationsViewed(distinctIds)
                }
            } catch (c: CancellationException) {
                synchronized(stateLock) {
                    inFlightMarkReadOps = (inFlightMarkReadOps - 1).coerceAtLeast(0)
                }
                throw c
            } catch (t: Throwable) {
                synchronized(stateLock) {
                    val now = clockMillis()
                    inFlightMarkReadOps = (inFlightMarkReadOps - 1).coerceAtLeast(0)
                    val ledger = ledgerFor(kind)
                    addedIds.forEach { id ->
                        if (ledger.locallyReadIds[id] == opTimestamp) {
                            ledger.locallyReadIds.remove(id)
                        }
                    }
                    if (distinctIds == null && ledger.allReadWatermark?.second == opTimestamp) {
                        ledger.allReadWatermark = previousWatermark
                    }
                    ledger.emit(now)
                    rollbackOptimisticTotalIfCurrentLocked(
                        now = now,
                        opGeneration = opGeneration,
                        appliedDelta = appliedDelta,
                    )
                }
                throw t
            }

            synchronized(stateLock) {
                finishMarkPostLocked(clockMillis())
            }
            if (markAllWatermarkForPersist != null && viewerUidForPersist > 0L) {
                persistWatermarksToStore(
                    viewerUid = viewerUidForPersist,
                    replyWatermark = if (kind == NotificationKind.REPLIES) markAllWatermarkForPersist else null,
                    mentionWatermark = if (kind == NotificationKind.MENTIONS) markAllWatermarkForPersist else null,
                )
            }
            refreshUnreadAfterMutation(opGeneration)
        }

    override suspend fun getMessageThreads(): Result<List<DirectMessageSummary>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val viewerUid = requireViewerUid()
            ensurePersistedBaselineSeeded(viewerUid)
            val response = apiClientProvider().listDirectMessages()
            val allRows = response.allMessages()
            val mappedThreads = response.groupThreadsForViewer(viewerUid).map { thread ->
                DirectMessageSummary(
                    peerUid = thread.peerUid,
                    peerName = thread.peerName,
                    peerAvatarUrl = avatarUrlForUid(thread.peerUid),
                    latestMessageId = thread.latestMessageId,
                    previewText = thread.preview,
                    isMarkdown = thread.isMarkdown,
                    updatedAtIso = thread.createdAt,
                    isRead = thread.viewed,
                    isSystem = thread.isSystem,
                    unreadCount = thread.unreadCount,
                )
            }

            val suppressPopups = currentCoroutineContext()[SuppressInAppPopupsContext] != null
            var popupsToEmit: List<InAppMessagePopup> = emptyList()
            var dmWatermarksForPersist: Map<Long, Long> = emptyMap()
            val effective = synchronized(stateLock) {
                syncAccountLocked()
                val now = clockMillis()
                pruneMessageReadStateLocked(now)

                val rebuiltUnreadByPeer = mutableMapOf<Long, MutableSet<Long>>()
                val rebuiltCachedByPeer = mutableMapOf<Long, MutableList<DirectMessageRowDto>>()
                for (row in allRows) {
                    val peerId = row.resolvedPeerUid(viewerUid)
                    rebuiltCachedByPeer.getOrPut(peerId) { mutableListOf() }.add(row)
                    messagePeerById[row.id] = peerId
                    maxMessageIdByPeer[peerId] = maxOf(maxMessageIdByPeer[peerId] ?: 0L, row.id)
                    if (row.senderId != viewerUid) {
                        if (row.viewed) {
                            locallyReadMessageIds.remove(row.id)
                        } else {
                            rebuiltUnreadByPeer.getOrPut(peerId) { mutableSetOf() }.add(row.id)
                        }
                    }
                }
                cachedListMessagesByPeer.clear()
                cachedListMessagesByPeer.putAll(rebuiltCachedByPeer)
                serverUnreadMessageIdsByPeer.clear()
                serverUnreadMessageIdsByPeer.putAll(rebuiltUnreadByPeer)

                threadsLoaded = true
                rawThreads = mappedThreads
                val resolvedEffective = effectiveThreadsLocked(now)
                _messageThreads.value = resolvedEffective
                raiseTotalToKnownFloorLocked(now)
                val allowEmit = !suppressPopups && _inAppPopupEvents.subscriptionCount.value > 0
                popupsToEmit = detectDmPopupsLocked(
                    effectiveThreads = resolvedEffective,
                    viewerUid = viewerUid,
                    now = now,
                    allowEmit = allowEmit,
                )
                if (popupsToEmit.isNotEmpty()) {
                    dmWatermarksForPersist = dmPopupWatermarkByPeer.toMap()
                }
                resolvedEffective
            }
            if (popupsToEmit.isNotEmpty()) {
                for (popup in popupsToEmit) {
                    _inAppPopupEvents.tryEmit(popup)
                }
                persistWatermarksToStore(
                    viewerUid = viewerUid,
                    dmInitialized = true,
                    dmWatermarks = dmWatermarksForPersist,
                )
            }
            effective
        }

    override suspend fun getConversation(peerUid: Long): Result<List<DirectMessage>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val viewerUid = requireViewerUid()
            val cachedRows = synchronized(stateLock) {
                cachedListMessagesByPeer[peerUid].orEmpty()
            }
            val response: ConversationResponseDto? = try {
                apiClientProvider().getConversation(peerUid)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val canFallbackToCached =
                    (peerUid <= 0L || cachedRows.any { it.isSystemMessage(viewerUid) }) &&
                        cachedRows.isNotEmpty()
                if (canFallbackToCached) {
                    null
                } else {
                    throw t
                }
            }
            val peer = response?.talkTo
            if (peerUid > 0L && peer != null && peer.memberId > 0L && peer.memberId != peerUid) {
                throw NodeSeekApiException("返回的会话用户不一致，请重新打开私聊。")
            }
            val remoteRows = response?.allMessages()
                ?.filter { msg -> msg.belongsToConversation(viewerUid = viewerUid, peerUid = peerUid) }
                .orEmpty()
            val fallbackRows = cachedRows.filter { row ->
                (peerUid <= 0L || row.isSystemMessage(viewerUid)) &&
                    row.belongsToConversation(viewerUid = viewerUid, peerUid = peerUid)
            }
            val mergedById = LinkedHashMap<Long, DirectMessageRowDto>()
            val unkeyedRows = mutableListOf<DirectMessageRowDto>()
            for (row in fallbackRows) {
                if (row.id > 0L) {
                    mergedById[row.id] = row
                } else {
                    unkeyedRows += row
                }
            }
            for (row in remoteRows) {
                if (row.id > 0L) {
                    mergedById[row.id] = row
                } else {
                    unkeyedRows += row
                }
            }
            val conversation = (mergedById.values.toList() + unkeyedRows)
                .sortedBy { it.id }
                .map { row ->
                    val effectiveBody = row.effectiveContent()
                    val isSys = peerUid <= 0L || row.isSystemMessage(viewerUid)
                    DirectMessage(
                        id = row.id,
                        senderUid = row.senderId,
                        receiverUid = row.receiverId,
                        body = effectiveBody,
                        isMarkdown = row.isMarkdown ||
                            (effectiveBody != row.content.trim() && effectiveBody.contains("](")),
                        createdAtIso = row.createdAt,
                        isRead = row.senderId == viewerUid || row.viewed,
                        isSystem = isSys,
                        noticeType = row.inferNoticeCategory(),
                    )
                }

            var dmWatermarkIncreasedForPersist: Long? = null
            synchronized(stateLock) {
                syncAccountLocked()
                val now = clockMillis()
                pruneMessageReadStateLocked(now)

                val oldTotal = _unreadTotalCount.value
                val hadUnreadBefore = hasEffectiveUnreadForPeerLocked(peerUid, now)
                val effectiveUnreadMsgsBefore = effectiveUnreadMessagesForPeerLocked(peerUid, now)
                val totalEffectiveDmMsgsBefore = effectiveUnreadDmMessagesCountLocked(now)
                val otherUnreadBefore =
                    repliesLedger.effectiveUnreadCount(now) + mentionsLedger.effectiveUnreadCount(now)

                val serverUnreadIds = mutableSetOf<Long>()
                for (msg in conversation) {
                    messagePeerById[msg.id] = peerUid
                    maxMessageIdByPeer[peerUid] = maxOf(maxMessageIdByPeer[peerUid] ?: 0L, msg.id)
                    if (msg.senderUid != viewerUid) {
                        if (msg.isRead) {
                            locallyReadMessageIds.remove(msg.id)
                            if (dmPopupBaselineInitialized) {
                                val prevWm = dmPopupWatermarkByPeer[peerUid] ?: 0L
                                if (msg.id > prevWm) {
                                    dmPopupWatermarkByPeer[peerUid] = msg.id
                                    dmWatermarkIncreasedForPersist = msg.id
                                }
                            }
                        } else {
                            serverUnreadIds.add(msg.id)
                        }
                    }
                }
                if (serverUnreadIds.isEmpty()) {
                    serverUnreadMessageIdsByPeer.remove(peerUid)
                    if (response != null && conversation.any { it.senderUid != viewerUid }) {
                        // Server confirmed all incoming messages in this conversation are viewed.
                        rawThreads = rawThreads.map { summary ->
                            if (summary.peerUid == peerUid && !summary.isRead) {
                                summary.copy(isRead = true)
                            } else {
                                summary
                            }
                        }
                        rawThreads.firstOrNull { it.peerUid == peerUid }?.let { summary ->
                            val sig = buildDmThreadSignature(summary.updatedAtIso, summary.previewText)
                            if (sig.isNotEmpty()) {
                                dmPopupSignatureByPeer[peerUid] = sig
                            }
                        }
                        if (hadUnreadBefore) {
                            val removedCount = computeThreadRemovalCountLocked(
                                oldTotal = oldTotal,
                                unreadMsgsForPeer = effectiveUnreadMsgsBefore,
                                totalEffectiveDmMsgs = totalEffectiveDmMsgsBefore,
                                otherUnread = otherUnreadBefore,
                                threadWasUnread = true,
                            )
                            if (removedCount > 0) {
                                _unreadTotalCount.value = maxOf(
                                    knownListFloorLocked(now),
                                    (oldTotal - removedCount).coerceAtLeast(0),
                                )
                            }
                        }
                    }
                } else {
                    serverUnreadMessageIdsByPeer[peerUid] = serverUnreadIds
                }
                emitThreadsLocked(now)
            }
            if (dmWatermarkIncreasedForPersist != null && viewerUid > 0L) {
                persistWatermarksToStore(
                    viewerUid = viewerUid,
                    dmInitialized = true,
                    dmWatermarks = mapOf(peerUid to dmWatermarkIncreasedForPersist!!),
                )
            }

            conversation
        }

    override suspend fun markThreadRead(peerUid: Long): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            var opTimestamp = 0L
            var opGeneration = 0L
            var idsToSend: List<Long> = emptyList()
            var addedMessageIds: List<Long> = emptyList()
            var previousPeerWatermark: Pair<Long, Long>? = null
            var appliedDelta = 0
            var viewerUidForPersist = 0L
            var watermarkForPersist: Long? = null

            synchronized(stateLock) {
                syncAccountLocked()
                val now = clockMillis()
                viewerUidForPersist = sessionRepository.sessionState.value?.uid ?: 0L
                opTimestamp = now
                pruneMessageReadStateLocked(now)

                val oldTotal = _unreadTotalCount.value
                val thread = rawThreads.firstOrNull { it.peerUid == peerUid }
                val threadWasEffectiveUnread = thread != null && !isThreadReadLocked(thread, now)
                val unreadIdsForPeer = serverUnreadMessageIdsByPeer[peerUid]
                    ?.filter { id -> !isMessageLocallyReadLocked(peerUid, id, now) }
                    ?.sorted()
                    .orEmpty()

                val effectiveMsgsBefore = if (unreadIdsForPeer.isNotEmpty()) {
                    unreadIdsForPeer.size
                } else if (threadWasEffectiveUnread) {
                    1
                } else {
                    0
                }
                val totalEffectiveDmMsgsBefore = effectiveUnreadDmMessagesCountLocked(now)
                val otherUnreadBefore =
                    repliesLedger.effectiveUnreadCount(now) + mentionsLedger.effectiveUnreadCount(now)

                val candidateIds = LinkedHashSet<Long>(unreadIdsForPeer)
                if (thread != null && !thread.isRead) {
                    thread.latestMessageId?.let { candidateIds.add(it) }
                }
                idsToSend = candidateIds.toList()

                if (unreadIdsForPeer.isEmpty() && !threadWasEffectiveUnread) {
                    return@runDomainCatching
                }

                previousPeerWatermark = locallyReadPeerWatermarks[peerUid]
                val watermarkMaxId = maxOf(
                    maxMessageIdByPeer[peerUid] ?: 0L,
                    thread?.latestMessageId ?: 0L,
                    idsToSend.maxOrNull() ?: 0L,
                )
                locallyReadPeerWatermarks[peerUid] = watermarkMaxId to now
                if (dmPopupBaselineInitialized && watermarkMaxId > 0L) {
                    dmPopupWatermarkByPeer[peerUid] = maxOf(
                        dmPopupWatermarkByPeer[peerUid] ?: 0L,
                        watermarkMaxId,
                    )
                    watermarkForPersist = dmPopupWatermarkByPeer[peerUid]
                }
                if (thread != null) {
                    val sig = buildDmThreadSignature(thread.updatedAtIso, thread.previewText)
                    if (sig.isNotEmpty()) {
                        dmPopupSignatureByPeer[peerUid] = sig
                    }
                }

                val newlyAdded = mutableListOf<Long>()
                idsToSend.forEach { msgId ->
                    messagePeerById[msgId] = peerUid
                    if (!locallyReadMessageIds.containsKey(msgId)) {
                        locallyReadMessageIds[msgId] = now
                        newlyAdded += msgId
                    }
                }
                addedMessageIds = newlyAdded

                appliedDelta = computeThreadRemovalCountLocked(
                    oldTotal = oldTotal,
                    unreadMsgsForPeer = effectiveMsgsBefore,
                    totalEffectiveDmMsgs = totalEffectiveDmMsgsBefore,
                    otherUnread = otherUnreadBefore,
                    threadWasUnread = threadWasEffectiveUnread,
                )

                emitThreadsLocked(now)
                beginOptimisticMarkLocked(now = now, oldTotal = oldTotal)
                opGeneration = readMutationGeneration
                val floorAfter = knownListFloorLocked(now)
                _unreadTotalCount.value = maxOf(floorAfter, (oldTotal - appliedDelta).coerceAtLeast(0))
            }

            try {
                if (idsToSend.isNotEmpty()) {
                    apiClientProvider().markDirectMessagesViewed(idsToSend)
                }
            } catch (c: CancellationException) {
                synchronized(stateLock) {
                    inFlightMarkReadOps = (inFlightMarkReadOps - 1).coerceAtLeast(0)
                }
                throw c
            } catch (t: Throwable) {
                synchronized(stateLock) {
                    val now = clockMillis()
                    inFlightMarkReadOps = (inFlightMarkReadOps - 1).coerceAtLeast(0)
                    addedMessageIds.forEach { msgId ->
                        if (locallyReadMessageIds[msgId] == opTimestamp) {
                            locallyReadMessageIds.remove(msgId)
                        }
                    }
                    if (locallyReadPeerWatermarks[peerUid]?.second == opTimestamp) {
                        if (previousPeerWatermark != null) {
                            locallyReadPeerWatermarks[peerUid] = previousPeerWatermark!!
                        } else {
                            locallyReadPeerWatermarks.remove(peerUid)
                        }
                    }
                    emitThreadsLocked(now)
                    rollbackOptimisticTotalIfCurrentLocked(
                        now = now,
                        opGeneration = opGeneration,
                        appliedDelta = appliedDelta,
                    )
                }
                throw t
            }

            synchronized(stateLock) {
                finishMarkPostLocked(clockMillis())
            }
            if (watermarkForPersist != null && viewerUidForPersist > 0L) {
                persistWatermarksToStore(
                    viewerUid = viewerUidForPersist,
                    dmInitialized = true,
                    dmWatermarks = mapOf(peerUid to watermarkForPersist!!),
                )
            }
            if (idsToSend.isNotEmpty()) {
                refreshUnreadAfterMutation(opGeneration)
            }
        }

    override suspend fun markMessagesRead(messageIds: List<Long>?): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (messageIds != null && messageIds.isEmpty()) {
                return@runDomainCatching
            }
            val distinctIds = messageIds?.distinct()
            var opTimestamp = 0L
            var opGeneration = 0L
            var addedMessageIds: List<Long> = emptyList()
            var previousAllWatermark: Pair<Long, Long>? = null
            var appliedDelta = 0
            var viewerUidForDmPersist = 0L
            var dmWatermarksForPersist: Map<Long, Long> = emptyMap()

            synchronized(stateLock) {
                syncAccountLocked()
                val now = clockMillis()
                viewerUidForDmPersist = sessionRepository.sessionState.value?.uid ?: 0L
                opTimestamp = now
                pruneMessageReadStateLocked(now)

                val oldTotal = _unreadTotalCount.value
                val totalEffectiveDmMsgsBefore = effectiveUnreadDmMessagesCountLocked(now)
                val otherUnreadBefore =
                    repliesLedger.effectiveUnreadCount(now) + mentionsLedger.effectiveUnreadCount(now)

                if (distinctIds == null) {
                    previousAllWatermark = allMessagesReadWatermark
                    val maxKnownMsgId = maxOf(
                        rawThreads.mapNotNull { it.latestMessageId }.maxOrNull() ?: 0L,
                        maxMessageIdByPeer.values.maxOrNull() ?: 0L,
                        serverUnreadMessageIdsByPeer.values.flatten().maxOrNull() ?: 0L,
                    )
                    allMessagesReadWatermark = maxKnownMsgId to now
                    if (dmPopupBaselineInitialized && maxKnownMsgId > 0L) {
                        for (thread in rawThreads) {
                            val peerMax = maxOf(
                                thread.latestMessageId ?: 0L,
                                maxMessageIdByPeer[thread.peerUid] ?: 0L,
                            )
                            if (peerMax > 0L) {
                                dmPopupWatermarkByPeer[thread.peerUid] = maxOf(
                                    dmPopupWatermarkByPeer[thread.peerUid] ?: 0L,
                                    peerMax,
                                )
                            }
                            val sig = buildDmThreadSignature(thread.updatedAtIso, thread.previewText)
                            if (sig.isNotEmpty()) {
                                dmPopupSignatureByPeer[thread.peerUid] = sig
                            }
                        }
                        dmWatermarksForPersist = dmPopupWatermarkByPeer.toMap()
                    }
                    val effectiveThreadsBefore = rawThreads.count { !isThreadReadLocked(it, now) }
                    val newlyAdded = mutableListOf<Long>()
                    serverUnreadMessageIdsByPeer.values.flatten().forEach { msgId ->
                        if (!locallyReadMessageIds.containsKey(msgId)) {
                            locallyReadMessageIds[msgId] = now
                            newlyAdded += msgId
                        }
                    }
                    addedMessageIds = newlyAdded
                    appliedDelta = if (totalEffectiveDmMsgsBefore > 0) {
                        if (oldTotal >= otherUnreadBefore + totalEffectiveDmMsgsBefore) {
                            totalEffectiveDmMsgsBefore
                        } else {
                            effectiveThreadsBefore.coerceAtLeast(1)
                        }
                    } else {
                        effectiveThreadsBefore
                    }
                } else {
                    val newlyMarkedIds = mutableListOf<Long>()
                    val touchedPeers = mutableSetOf<Long>()
                    var countNew = 0
                    distinctIds.forEach { msgId ->
                        val peerUid = messagePeerById[msgId]
                        if (peerUid != null) {
                            touchedPeers += peerUid
                            if (dmPopupBaselineInitialized && msgId > 0L) {
                                dmPopupWatermarkByPeer[peerUid] = maxOf(
                                    dmPopupWatermarkByPeer[peerUid] ?: 0L,
                                    msgId,
                                )
                            }
                        }
                        val alreadyLocallyRead = isMessageLocallyReadLocked(peerUid, msgId, now)
                        val knownReadFromList = if (peerUid != null) {
                            serverUnreadMessageIdsByPeer[peerUid]?.contains(msgId) == false &&
                                rawThreads.any { it.peerUid == peerUid && it.isRead }
                        } else {
                            false
                        }
                        if (!alreadyLocallyRead && !knownReadFromList) {
                            countNew += 1
                        }
                        if (!locallyReadMessageIds.containsKey(msgId)) {
                            locallyReadMessageIds[msgId] = now
                            newlyMarkedIds += msgId
                        }
                    }
                    addedMessageIds = newlyMarkedIds
                    if (dmPopupBaselineInitialized && touchedPeers.isNotEmpty()) {
                        dmWatermarksForPersist = touchedPeers.mapNotNull { peer ->
                            dmPopupWatermarkByPeer[peer]?.let { peer to it }
                        }.toMap()
                    }
                    val completedThreadsCount = touchedPeers.count { peerUid ->
                        val thread = rawThreads.firstOrNull { it.peerUid == peerUid }
                        val serverUnread = serverUnreadMessageIdsByPeer[peerUid].orEmpty()
                        thread != null && !thread.isRead && serverUnread.all { it in newlyMarkedIds || isMessageLocallyReadLocked(peerUid, it, now) }
                    }
                    appliedDelta = if (totalEffectiveDmMsgsBefore > 0 &&
                        oldTotal < otherUnreadBefore + totalEffectiveDmMsgsBefore &&
                        completedThreadsCount > 0
                    ) {
                        completedThreadsCount
                    } else {
                        countNew
                    }
                }

                emitThreadsLocked(now)
                beginOptimisticMarkLocked(now = now, oldTotal = oldTotal)
                opGeneration = readMutationGeneration
                val floorAfter = knownListFloorLocked(now)
                _unreadTotalCount.value = maxOf(floorAfter, (oldTotal - appliedDelta).coerceAtLeast(0))
            }

            val postIds = distinctIds?.filter { it > 0L }
            try {
                if (postIds == null || postIds.isNotEmpty()) {
                    apiClientProvider().markDirectMessagesViewed(postIds)
                }
            } catch (c: CancellationException) {
                synchronized(stateLock) {
                    inFlightMarkReadOps = (inFlightMarkReadOps - 1).coerceAtLeast(0)
                }
                throw c
            } catch (t: Throwable) {
                synchronized(stateLock) {
                    val now = clockMillis()
                    inFlightMarkReadOps = (inFlightMarkReadOps - 1).coerceAtLeast(0)
                    addedMessageIds.forEach { msgId ->
                        if (locallyReadMessageIds[msgId] == opTimestamp) {
                            locallyReadMessageIds.remove(msgId)
                        }
                    }
                    if (distinctIds == null && allMessagesReadWatermark?.second == opTimestamp) {
                        allMessagesReadWatermark = previousAllWatermark
                    }
                    emitThreadsLocked(now)
                    rollbackOptimisticTotalIfCurrentLocked(
                        now = now,
                        opGeneration = opGeneration,
                        appliedDelta = appliedDelta,
                    )
                }
                throw t
            }

            synchronized(stateLock) {
                finishMarkPostLocked(clockMillis())
            }
            if (dmWatermarksForPersist.isNotEmpty() && viewerUidForDmPersist > 0L) {
                persistWatermarksToStore(
                    viewerUid = viewerUidForDmPersist,
                    dmInitialized = true,
                    dmWatermarks = dmWatermarksForPersist,
                )
            }
            refreshUnreadAfterMutation(opGeneration)
        }

    override suspend fun sendDirectMessage(
        receiverUid: Long,
        content: String,
        isMarkdown: Boolean,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (receiverUid <= 0L) {
                throw NodeSeekApiException("系统通知不支持直接回复。")
            }
            apiClientProvider().sendDirectMessage(
                receiverUid = receiverUid,
                content = content,
                isMarkdown = isMarkdown,
            )
        }

    private suspend fun refreshUnreadAfterMutation(opGeneration: Long) {
        val shouldFetch = synchronized(stateLock) {
            opGeneration == readMutationGeneration && inFlightMarkReadOps == 0
        }
        if (!shouldFetch) return

        val count = try {
            apiClientProvider().getUnreadNotificationCount().coerceAtLeast(0)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            return
        }

        synchronized(stateLock) {
            syncAccountLocked()
            if (sessionRepository.sessionState.value == null) {
                clearAllStateLocked(resetTotalToZero = true)
                return
            }
            if (opGeneration != readMutationGeneration || inFlightMarkReadOps > 0) {
                return
            }
            reconcileServerUnreadCountLocked(
                count = count,
                now = clockMillis(),
                source = CountSource.API,
            )
        }
    }

    private fun ledgerFor(kind: NotificationKind): NotificationCategoryLedger = when (kind) {
        NotificationKind.REPLIES -> repliesLedger
        NotificationKind.MENTIONS -> mentionsLedger
    }

    /**
     * Synchronizes account-scoped caches whenever the active uid changes or signs out.
     * Returns `true` if a state reset occurred.
     */
    private fun syncAccountLocked(): Boolean {
        val currentUid = sessionRepository.sessionState.value?.uid ?: 0L
        if (currentUid == trackedUid) return false
        val previousUid = trackedUid
        trackedUid = currentUid
        return if (previousUid != 0L) {
            clearAllStateLocked(resetTotalToZero = true)
            true
        } else {
            // Fresh sign-in transition from signed-out state: clear stale read watermarks.
            clearLocalReadWatermarksLocked()
            true
        }
    }

    private fun clearAllStateLocked(resetTotalToZero: Boolean) {
        repliesLedger.clear()
        mentionsLedger.clear()
        threadsLoaded = false
        rawThreads = emptyList()
        _messageThreads.value = emptyList()
        clearLocalReadWatermarksLocked()
        if (resetTotalToZero) {
            _unreadTotalCount.value = 0
        }
    }

    private fun clearLocalReadWatermarksLocked() {
        locallyReadMessageIds.clear()
        locallyReadPeerWatermarks.clear()
        allMessagesReadWatermark = null
        serverUnreadMessageIdsByPeer.clear()
        maxMessageIdByPeer.clear()
        messagePeerById.clear()
        cachedListMessagesByPeer.clear()
        repliesLedger.locallyReadIds.clear()
        repliesLedger.allReadWatermark = null
        repliesLedger.popupBaselineInitialized = false
        repliesLedger.popupWatermarkId = 0L
        mentionsLedger.locallyReadIds.clear()
        mentionsLedger.allReadWatermark = null
        mentionsLedger.popupBaselineInitialized = false
        mentionsLedger.popupWatermarkId = 0L
        dmPopupBaselineInitialized = false
        dmPopupWatermarkByPeer.clear()
        dmPopupSignatureByPeer.clear()
        emittedInteractionFloorKeys.clear()
        emittedPopupKeys.clear()
        persistedBaselineCheckedForUid = 0L
        lastForegroundCheckMs = 0L
        lastFullListSyncMs = 0L
        lastDmListSyncMs = 0L
        foregroundRateLimitUntilMs = 0L
        foregroundBackoffUntilMs = 0L
        readMutationGeneration += 1L
        inFlightMarkReadOps = 0
        piggybackGuardUntilMs = 0L
        stalePiggybackCeiling = 0
        apiCountGuardUntilMs = 0L
        staleApiCeiling = 0
        apiCountConfirmed = true
    }

    private fun pruneMessageReadStateLocked(now: Long) {
        val idIter = locallyReadMessageIds.entries.iterator()
        while (idIter.hasNext()) {
            if (now - idIter.next().value >= LOCAL_READ_RETENTION_MS) {
                idIter.remove()
            }
        }
        val peerIter = locallyReadPeerWatermarks.entries.iterator()
        while (peerIter.hasNext()) {
            if (now - peerIter.next().value.second >= LOCAL_READ_RETENTION_MS) {
                peerIter.remove()
            }
        }
        val allWm = allMessagesReadWatermark
        if (allWm != null && now - allWm.second >= LOCAL_READ_RETENTION_MS) {
            allMessagesReadWatermark = null
        }
    }

    private fun isMessageLocallyReadLocked(peerUid: Long?, msgId: Long, now: Long): Boolean {
        val markedAt = locallyReadMessageIds[msgId]
        if (markedAt != null && now - markedAt < LOCAL_READ_RETENTION_MS) {
            return true
        }
        if (peerUid != null) {
            val peerWm = locallyReadPeerWatermarks[peerUid]
            if (peerWm != null && now - peerWm.second < LOCAL_READ_RETENTION_MS && msgId <= peerWm.first) {
                return true
            }
        }
        val allWm = allMessagesReadWatermark
        return allWm != null && now - allWm.second < LOCAL_READ_RETENTION_MS && msgId <= allWm.first
    }

    private fun isThreadReadLocked(thread: DirectMessageSummary, now: Long): Boolean {
        if (thread.isRead) return true
        val latestId = thread.latestMessageId
        val allWm = allMessagesReadWatermark
        if (allWm != null && now - allWm.second < LOCAL_READ_RETENTION_MS && (latestId == null || latestId <= allWm.first)) {
            return true
        }
        val peerWm = locallyReadPeerWatermarks[thread.peerUid]
        if (peerWm != null && now - peerWm.second < LOCAL_READ_RETENTION_MS && (latestId == null || latestId <= peerWm.first)) {
            return true
        }
        val peerUnreadIds = serverUnreadMessageIdsByPeer[thread.peerUid]
        if (peerUnreadIds != null && peerUnreadIds.isNotEmpty()) {
            return peerUnreadIds.all { msgId -> isMessageLocallyReadLocked(thread.peerUid, msgId, now) } &&
                (latestId == null || isMessageLocallyReadLocked(thread.peerUid, latestId, now))
        }
        return latestId != null && isMessageLocallyReadLocked(thread.peerUid, latestId, now)
    }

    private fun effectiveThreadsLocked(now: Long): List<DirectMessageSummary> =
        rawThreads.map { thread ->
            if (thread.isRead || isThreadReadLocked(thread, now)) {
                if (thread.isRead && thread.unreadCount == 0) {
                    thread
                } else {
                    thread.copy(isRead = true, unreadCount = 0)
                }
            } else {
                val remainingUnread = effectiveUnreadMessagesForPeerLocked(thread.peerUid, now)
                if (remainingUnread > 0 && remainingUnread != thread.unreadCount) {
                    thread.copy(unreadCount = remainingUnread)
                } else {
                    thread
                }
            }
        }

    private fun emitThreadsLocked(now: Long) {
        if (threadsLoaded || rawThreads.isNotEmpty()) {
            _messageThreads.value = effectiveThreadsLocked(now)
        }
    }

    private fun hasEffectiveUnreadForPeerLocked(peerUid: Long, now: Long): Boolean {
        val threadUnread = rawThreads.firstOrNull { it.peerUid == peerUid }?.let { !isThreadReadLocked(it, now) } == true
        return threadUnread || effectiveUnreadMessagesForPeerLocked(peerUid, now) > 0
    }

    private fun effectiveUnreadMessagesForPeerLocked(peerUid: Long, now: Long): Int =
        serverUnreadMessageIdsByPeer[peerUid]?.count { msgId ->
            !isMessageLocallyReadLocked(peerUid, msgId, now)
        } ?: 0

    private fun effectiveUnreadDmMessagesCountLocked(now: Long): Int =
        serverUnreadMessageIdsByPeer.entries.sumOf { (peerUid, ids) ->
            ids.count { msgId -> !isMessageLocallyReadLocked(peerUid, msgId, now) }
        }

    private fun computeThreadRemovalCountLocked(
        oldTotal: Int,
        unreadMsgsForPeer: Int,
        totalEffectiveDmMsgs: Int,
        otherUnread: Int,
        threadWasUnread: Boolean,
    ): Int {
        if (unreadMsgsForPeer <= 0) {
            return if (threadWasUnread) 1 else 0
        }
        return if (totalEffectiveDmMsgs > 0 && oldTotal < otherUnread + totalEffectiveDmMsgs) {
            if (threadWasUnread) 1 else unreadMsgsForPeer
        } else {
            unreadMsgsForPeer
        }
    }

    private fun knownListFloorLocked(now: Long): Int =
        repliesLedger.effectiveUnreadCount(now) +
            mentionsLedger.effectiveUnreadCount(now) +
            rawThreads.count { !isThreadReadLocked(it, now) }

    private fun raiseTotalToKnownFloorLocked(now: Long) {
        val floor = knownListFloorLocked(now)
        if (floor > _unreadTotalCount.value) {
            _unreadTotalCount.value = floor
        }
    }

    private fun beginOptimisticMarkLocked(now: Long, oldTotal: Int) {
        readMutationGeneration += 1L
        inFlightMarkReadOps += 1
        stalePiggybackCeiling = maxOf(stalePiggybackCeiling, oldTotal)
        staleApiCeiling = maxOf(staleApiCeiling, oldTotal)
        apiCountConfirmed = false
        piggybackGuardUntilMs = maxOf(piggybackGuardUntilMs, now + PIGGYBACK_STALE_GUARD_MS)
        apiCountGuardUntilMs = maxOf(apiCountGuardUntilMs, now + API_STALE_GUARD_MS)
    }

    private fun finishMarkPostLocked(now: Long) {
        inFlightMarkReadOps = (inFlightMarkReadOps - 1).coerceAtLeast(0)
        piggybackGuardUntilMs = maxOf(piggybackGuardUntilMs, now + PIGGYBACK_STALE_GUARD_MS)
        if (!apiCountConfirmed) {
            apiCountGuardUntilMs = maxOf(apiCountGuardUntilMs, now + API_STALE_GUARD_MS)
        }
    }

    private fun rollbackOptimisticTotalIfCurrentLocked(
        now: Long,
        opGeneration: Long,
        appliedDelta: Int,
    ) {
        if (opGeneration != readMutationGeneration) return
        _unreadTotalCount.value = maxOf(
            knownListFloorLocked(now),
            _unreadTotalCount.value + appliedDelta,
        )
        if (inFlightMarkReadOps == 0) {
            apiCountGuardUntilMs = 0L
            apiCountConfirmed = true
            staleApiCeiling = _unreadTotalCount.value
        }
    }

    private fun reconcileServerUnreadCountLocked(
        count: Int,
        now: Long,
        source: CountSource,
    ) {
        val current = _unreadTotalCount.value
        if (count <= current) {
            _unreadTotalCount.value = count
            if (source == CountSource.API && inFlightMarkReadOps == 0) {
                apiCountConfirmed = true
                staleApiCeiling = count
                apiCountGuardUntilMs = 0L
            }
            return
        }

        val isGuarded = when (source) {
            CountSource.PIGGYBACK -> inFlightMarkReadOps > 0 || now < piggybackGuardUntilMs
            CountSource.API -> inFlightMarkReadOps > 0 || (!apiCountConfirmed && now < apiCountGuardUntilMs)
        }
        val ceiling = when (source) {
            CountSource.PIGGYBACK -> stalePiggybackCeiling
            CountSource.API -> staleApiCeiling
        }

        if (!isGuarded) {
            _unreadTotalCount.value = count
            when (source) {
                CountSource.PIGGYBACK -> {
                    piggybackGuardUntilMs = 0L
                    stalePiggybackCeiling = 0
                }
                CountSource.API -> {
                    apiCountConfirmed = true
                    staleApiCeiling = count
                    apiCountGuardUntilMs = 0L
                }
            }
            return
        }

        if (count <= ceiling) {
            // Suppress stale non-zero count within the optimistic guard window.
            return
        }

        // Count strictly exceeds the highest pre-mark baseline: surface only the net new arrivals
        // unless the server has already confirmed the read mutation via JSON API.
        val updated = if (source == CountSource.PIGGYBACK && apiCountConfirmed) {
            count
        } else {
            val netNew = count - ceiling
            maxOf(knownListFloorLocked(now), current + netNew)
        }
        _unreadTotalCount.value = updated
        when (source) {
            CountSource.PIGGYBACK -> stalePiggybackCeiling = count
            CountSource.API -> {
                staleApiCeiling = count
                stalePiggybackCeiling = maxOf(stalePiggybackCeiling, count)
            }
        }
    }

    private fun detectNotificationPopupsLocked(
        kind: NotificationKind,
        ledger: NotificationCategoryLedger,
        effectiveItems: List<NotificationItem>,
        viewerUid: Long,
        now: Long,
        allowEmit: Boolean,
    ): List<InAppMessagePopup> {
        val maxIdInFeed = effectiveItems.maxOfOrNull { it.id } ?: 0L
        if (!ledger.popupBaselineInitialized) {
            ledger.popupBaselineInitialized = true
            ledger.popupWatermarkId = maxIdInFeed
            return emptyList()
        }

        if (!allowEmit) {
            val minUnreadNewId = effectiveItems
                .asSequence()
                .filter { item ->
                    !item.isRead &&
                        item.id > ledger.popupWatermarkId &&
                        (viewerUid <= 0L || item.senderUid != viewerUid)
                }
                .minOfOrNull { it.id }
            val safeMaxReadId = effectiveItems
                .asSequence()
                .filter { item ->
                    item.isRead || (viewerUid > 0L && item.senderUid == viewerUid)
                }
                .map { it.id }
                .filter { id -> minUnreadNewId == null || id < minUnreadNewId }
                .maxOrNull() ?: 0L
            if (safeMaxReadId > ledger.popupWatermarkId) {
                ledger.popupWatermarkId = safeMaxReadId
            }
            return emptyList()
        }

        val freshItems = effectiveItems
            .asSequence()
            .filter { item ->
                !item.isRead &&
                    item.id > ledger.popupWatermarkId &&
                    (viewerUid <= 0L || item.senderUid != viewerUid)
            }
            .sortedBy { it.id }
            .toList()

        if (maxIdInFeed > ledger.popupWatermarkId) {
            ledger.popupWatermarkId = maxIdInFeed
        }

        if (freshItems.isEmpty()) return emptyList()

        pruneInteractionFloorCacheLocked(now)
        val emitted = ArrayList<InAppMessagePopup>(freshItems.size.coerceAtMost(MAX_POPUPS_PER_BATCH))
        for (item in freshItems.takeLast(MAX_POPUPS_PER_BATCH)) {
            val resolved = InboxTargetResolver.resolveForNotificationItem(item) ?: continue
            val floorKey = if (resolved.topicId > 0L && resolved.floor > 0) {
                resolved.topicId to resolved.floor
            } else {
                null
            }
            if (kind == NotificationKind.MENTIONS &&
                floorKey != null &&
                emittedInteractionFloorKeys.containsKey(floorKey)
            ) {
                continue
            }
            val popupKind = when (kind) {
                NotificationKind.REPLIES -> InAppMessageKind.REPLY
                NotificationKind.MENTIONS -> InAppMessageKind.MENTION
            }
            val dedupKey = if (floorKey != null) {
                "interaction:${floorKey.first}:${floorKey.second}"
            } else {
                "${popupKind.name.lowercase()}:${item.id}"
            }
            if (!recordEmittedPopupKeyLocked(dedupKey)) {
                continue
            }
            if (kind == NotificationKind.REPLIES && floorKey != null) {
                emittedInteractionFloorKeys[floorKey] = now
            }
            val cleanExcerpt = item.excerpt
                ?.let(NodeSeekHtmlParser::stripMarkdownToPlainPreview)
                .orEmpty()
            emitted += InAppMessagePopup(
                dedupKey = dedupKey,
                kind = popupKind,
                itemId = item.id,
                senderUid = item.senderUid,
                senderName = item.senderName.ifBlank { "社区用户" },
                senderAvatarUrl = item.senderAvatarUrl,
                title = item.topicTitle.ifBlank { "帖子 #${resolved.topicId}" },
                previewText = cleanExcerpt,
                createdAtIso = item.createdAtIso,
                topicId = resolved.topicId,
                targetFloor = resolved.floor,
                targetPage = resolved.page,
                emittedAtEpochMillis = now,
            )
        }
        return emitted
    }

    private fun detectDmPopupsLocked(
        effectiveThreads: List<DirectMessageSummary>,
        viewerUid: Long,
        now: Long,
        allowEmit: Boolean,
    ): List<InAppMessagePopup> {
        if (!dmPopupBaselineInitialized) {
            dmPopupBaselineInitialized = true
            for (thread in effectiveThreads) {
                val peerRows = cachedListMessagesByPeer[thread.peerUid].orEmpty()
                val latestIncomingId = peerRows
                    .filter { it.senderId != viewerUid }
                    .maxOfOrNull { it.id } ?: 0L
                val baselineId = maxOf(
                    thread.latestMessageId ?: 0L,
                    latestIncomingId,
                    maxMessageIdByPeer[thread.peerUid] ?: 0L,
                )
                if (baselineId > 0L) {
                    dmPopupWatermarkByPeer[thread.peerUid] = maxOf(
                        dmPopupWatermarkByPeer[thread.peerUid] ?: 0L,
                        baselineId,
                    )
                }
                val baselineSignature = buildDmThreadSignature(thread.updatedAtIso, thread.previewText)
                if (baselineSignature.isNotEmpty()) {
                    dmPopupSignatureByPeer[thread.peerUid] = baselineSignature
                }
            }
            return emptyList()
        }

        if (!allowEmit) {
            for (thread in effectiveThreads) {
                if (thread.isSystem || thread.peerUid <= 0L) continue
                val peerRows = cachedListMessagesByPeer[thread.peerUid].orEmpty()
                val prevWatermark = dmPopupWatermarkByPeer[thread.peerUid] ?: 0L
                val unreadIncomingIds = peerRows
                    .asSequence()
                    .filter { row ->
                        row.senderId != viewerUid &&
                            !row.viewed &&
                            !isMessageLocallyReadLocked(thread.peerUid, row.id, now)
                    }
                    .map { it.id }
                    .filter { it > 0L }
                    .toList()
                val minUnreadId = unreadIncomingIds.minOrNull()
                if (thread.isRead && minUnreadId == null) {
                    val maxPeerMsgId = maxOf(
                        thread.latestMessageId ?: 0L,
                        peerRows.maxOfOrNull { it.id } ?: 0L,
                    )
                    if (maxPeerMsgId > prevWatermark) {
                        dmPopupWatermarkByPeer[thread.peerUid] = maxPeerMsgId
                    }
                    val sig = buildDmThreadSignature(thread.updatedAtIso, thread.previewText)
                    if (sig.isNotEmpty()) {
                        dmPopupSignatureByPeer[thread.peerUid] = sig
                    }
                } else {
                    val safeMaxReadId = peerRows
                        .asSequence()
                        .filter { row ->
                            row.senderId == viewerUid ||
                                row.viewed ||
                                isMessageLocallyReadLocked(thread.peerUid, row.id, now)
                        }
                        .map { it.id }
                        .filter { id -> id > 0L && (minUnreadId == null || id < minUnreadId) }
                        .maxOrNull() ?: 0L
                    if (safeMaxReadId > prevWatermark && (minUnreadId != null || peerRows.isNotEmpty())) {
                        dmPopupWatermarkByPeer[thread.peerUid] = safeMaxReadId
                    }
                }
            }
            return emptyList()
        }

        val candidates = ArrayList<InAppMessagePopup>()
        for (thread in effectiveThreads) {
            if (thread.isSystem || thread.peerUid <= 0L) continue
            val peerRows = cachedListMessagesByPeer[thread.peerUid].orEmpty()
            val prevWatermark = dmPopupWatermarkByPeer[thread.peerUid] ?: 0L
            val prevSignature = dmPopupSignatureByPeer[thread.peerUid]
            val maxPeerMsgId = maxOf(
                thread.latestMessageId ?: 0L,
                peerRows.maxOfOrNull { it.id } ?: 0L,
            )

            val newestIncomingUnread = peerRows
                .asSequence()
                .filter { row ->
                    row.senderId != viewerUid &&
                        !row.viewed &&
                        !isMessageLocallyReadLocked(thread.peerUid, row.id, now)
                }
                .maxByOrNull { it.id }

            val triggerMsgId: Long
            val rawPreview: String
            val createdAtIso: String
            val hasTriggerCandidate: Boolean
            if (newestIncomingUnread != null) {
                triggerMsgId = newestIncomingUnread.id
                rawPreview = newestIncomingUnread.effectiveContent()
                createdAtIso = newestIncomingUnread.createdAt.ifBlank { thread.updatedAtIso }
                hasTriggerCandidate = true
            } else if (!thread.isRead && peerRows.isEmpty()) {
                triggerMsgId = thread.latestMessageId ?: 0L
                rawPreview = thread.previewText
                createdAtIso = thread.updatedAtIso
                hasTriggerCandidate = true
            } else {
                triggerMsgId = 0L
                rawPreview = ""
                createdAtIso = ""
                hasTriggerCandidate = false
            }

            if (maxPeerMsgId > prevWatermark) {
                dmPopupWatermarkByPeer[thread.peerUid] = maxPeerMsgId
            }

            val currentSignature = if (hasTriggerCandidate) {
                buildDmThreadSignature(createdAtIso, rawPreview)
            } else {
                buildDmThreadSignature(thread.updatedAtIso, thread.previewText)
            }
            if (currentSignature.isNotEmpty()) {
                dmPopupSignatureByPeer[thread.peerUid] = currentSignature
            }

            val shouldTriggerById = !thread.isRead && triggerMsgId > prevWatermark
            val shouldTriggerBySignature = !thread.isRead &&
                hasTriggerCandidate &&
                triggerMsgId <= 0L &&
                currentSignature.isNotEmpty() &&
                currentSignature != prevSignature

            if (shouldTriggerById || shouldTriggerBySignature) {
                val dedupKey = if (shouldTriggerById) {
                    "dm:${thread.peerUid}:$triggerMsgId"
                } else {
                    "dm:${thread.peerUid}:sig:${currentSignature.hashCode()}"
                }
                if (recordEmittedPopupKeyLocked(dedupKey)) {
                    val senderName = thread.peerName.ifBlank {
                        newestIncomingUnread?.senderName?.takeIf { it.isNotBlank() } ?: "用户 #${thread.peerUid}"
                    }
                    val cleanPreview = NodeSeekHtmlParser.stripMarkdownToPlainPreview(rawPreview)
                        .ifBlank { "发来一条新私信" }
                    candidates += InAppMessagePopup(
                        dedupKey = dedupKey,
                        kind = InAppMessageKind.DIRECT_MESSAGE,
                        itemId = triggerMsgId,
                        senderUid = thread.peerUid,
                        senderName = senderName,
                        senderAvatarUrl = thread.peerAvatarUrl.ifBlank { avatarUrlForUid(thread.peerUid) },
                        title = senderName,
                        previewText = cleanPreview,
                        createdAtIso = createdAtIso,
                        peerUid = thread.peerUid,
                        peerName = senderName,
                        emittedAtEpochMillis = now,
                    )
                }
            }
        }
        return candidates
            .sortedBy { it.itemId }
            .takeLast(MAX_POPUPS_PER_BATCH)
    }

    private fun buildDmThreadSignature(updatedAtIso: String, preview: String): String {
        val cleanTime = updatedAtIso.trim()
        val cleanBody = preview.trim()
        return if (cleanTime.isEmpty() && cleanBody.isEmpty()) "" else "$cleanTime|$cleanBody"
    }

    private fun flushPendingPopupsFromLoadedStateLocked(
        now: Long,
        viewerUid: Long,
    ): FlushPopupsResult {
        val collected = ArrayList<InAppMessagePopup>()
        var replyPersist: Long? = null
        var mentionPersist: Long? = null
        var dmPersist: Map<Long, Long> = emptyMap()

        if (repliesLedger.loaded && repliesLedger.popupBaselineInitialized) {
            val emitted = detectNotificationPopupsLocked(
                kind = NotificationKind.REPLIES,
                ledger = repliesLedger,
                effectiveItems = repliesLedger.effectiveItems(now),
                viewerUid = viewerUid,
                now = now,
                allowEmit = true,
            )
            if (emitted.isNotEmpty()) {
                collected += emitted
                replyPersist = repliesLedger.popupWatermarkId
            }
        }
        if (mentionsLedger.loaded && mentionsLedger.popupBaselineInitialized) {
            val emitted = detectNotificationPopupsLocked(
                kind = NotificationKind.MENTIONS,
                ledger = mentionsLedger,
                effectiveItems = mentionsLedger.effectiveItems(now),
                viewerUid = viewerUid,
                now = now,
                allowEmit = true,
            )
            if (emitted.isNotEmpty()) {
                collected += emitted
                mentionPersist = mentionsLedger.popupWatermarkId
            }
        }
        if (threadsLoaded && dmPopupBaselineInitialized) {
            val emitted = detectDmPopupsLocked(
                effectiveThreads = effectiveThreadsLocked(now),
                viewerUid = viewerUid,
                now = now,
                allowEmit = true,
            )
            if (emitted.isNotEmpty()) {
                collected += emitted
                dmPersist = dmPopupWatermarkByPeer.toMap()
            }
        }
        return FlushPopupsResult(
            popups = collected,
            replyWatermark = replyPersist,
            mentionWatermark = mentionPersist,
            dmWatermarks = dmPersist,
        )
    }

    private suspend fun ensurePersistedBaselineSeeded(viewerUid: Long) {
        if (viewerUid <= 0L) return
        val alreadyChecked = synchronized(stateLock) {
            persistedBaselineCheckedForUid == viewerUid
        }
        if (alreadyChecked) return
        val store = watermarkStore
        val persisted: RadarWatermarks? = if (store != null) {
            runCatching { store.getWatermarks() }.getOrNull()
        } else {
            null
        }
        synchronized(stateLock) {
            syncAccountLocked()
            if (persistedBaselineCheckedForUid == viewerUid) return@synchronized
            persistedBaselineCheckedForUid = viewerUid
            if (persisted != null && persisted.inboxAccountUid == viewerUid) {
                if (!repliesLedger.popupBaselineInitialized && persisted.lastNotifiedReplyId >= 0L) {
                    repliesLedger.popupBaselineInitialized = true
                    repliesLedger.popupWatermarkId = maxOf(
                        repliesLedger.popupWatermarkId,
                        persisted.lastNotifiedReplyId,
                    )
                }
                if (!mentionsLedger.popupBaselineInitialized && persisted.lastNotifiedMentionId >= 0L) {
                    mentionsLedger.popupBaselineInitialized = true
                    mentionsLedger.popupWatermarkId = maxOf(
                        mentionsLedger.popupWatermarkId,
                        persisted.lastNotifiedMentionId,
                    )
                }
                if (!dmPopupBaselineInitialized && persisted.dmInitialized) {
                    dmPopupBaselineInitialized = true
                    for ((peerUid, msgId) in persisted.lastNotifiedDmMap) {
                        if (peerUid > 0L && msgId > 0L) {
                            dmPopupWatermarkByPeer[peerUid] = maxOf(
                                dmPopupWatermarkByPeer[peerUid] ?: 0L,
                                msgId,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun persistWatermarksToStore(
        viewerUid: Long,
        replyWatermark: Long? = null,
        mentionWatermark: Long? = null,
        dmInitialized: Boolean = false,
        dmWatermarks: Map<Long, Long> = emptyMap(),
    ) {
        val store = watermarkStore ?: return
        if (viewerUid <= 0L) return
        runCatching {
            store.updateWatermarks { current ->
                val base = if (current.inboxAccountUid != 0L && current.inboxAccountUid != viewerUid) {
                    current.copy(
                        inboxAccountUid = viewerUid,
                        lastNotifiedReplyId = -1L,
                        lastNotifiedMentionId = -1L,
                        dmInitialized = false,
                        lastNotifiedDmMap = emptyMap(),
                    )
                } else {
                    current.copy(inboxAccountUid = viewerUid)
                }
                val nextReply = if (replyWatermark != null) {
                    maxOf(base.lastNotifiedReplyId, replyWatermark)
                } else {
                    base.lastNotifiedReplyId
                }
                val nextMention = if (mentionWatermark != null) {
                    maxOf(base.lastNotifiedMentionId, mentionWatermark)
                } else {
                    base.lastNotifiedMentionId
                }
                val nextDmInit = base.dmInitialized || dmInitialized
                val nextDmMap = if (dmWatermarks.isEmpty()) {
                    base.lastNotifiedDmMap
                } else {
                    base.lastNotifiedDmMap.toMutableMap().apply {
                        for ((peerUid, msgId) in dmWatermarks) {
                            if (peerUid > 0L && msgId > (this[peerUid] ?: 0L)) {
                                this[peerUid] = msgId
                            }
                        }
                    }
                }
                base.copy(
                    lastNotifiedReplyId = nextReply,
                    lastNotifiedMentionId = nextMention,
                    dmInitialized = nextDmInit,
                    lastNotifiedDmMap = nextDmMap,
                )
            }
        }
    }

    private data class BaselinePersistSnapshot(
        val replyWatermark: Long?,
        val mentionWatermark: Long?,
        val dmInitialized: Boolean,
        val dmWatermarks: Map<Long, Long>,
    )

    private data class ForegroundSyncStateSnapshot(
        val countBeforeCheck: Int,
        val knownLocalUnread: Int,
        val lastFullSync: Long,
        val lastDmSync: Long,
        val repliesLoaded: Boolean,
        val mentionsLoaded: Boolean,
        val threadsLoaded: Boolean,
    )

    private data class FlushPopupsResult(
        val popups: List<InAppMessagePopup>,
        val replyWatermark: Long?,
        val mentionWatermark: Long?,
        val dmWatermarks: Map<Long, Long>,
    )

    private fun pruneInteractionFloorCacheLocked(now: Long) {
        val iter = emittedInteractionFloorKeys.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value >= INTERACTION_FLOOR_DEDUP_RETENTION_MS ||
                emittedInteractionFloorKeys.size > MAX_TRACKED_INTERACTION_FLOORS
            ) {
                iter.remove()
            }
        }
    }

    private fun recordEmittedPopupKeyLocked(dedupKey: String): Boolean {
        if (!emittedPopupKeys.add(dedupKey)) return false
        while (emittedPopupKeys.size > MAX_EMITTED_POPUP_KEYS) {
            val oldest = emittedPopupKeys.firstOrNull() ?: break
            emittedPopupKeys.remove(oldest)
        }
        return true
    }

    private fun recordForegroundSyncFailure(t: Throwable) {
        val now = clockMillis()
        synchronized(stateLock) {
            when (t) {
                is NodeSeekApiException -> if (t.statusCode == 429) {
                    val safeSeconds = (t.retryAfterSeconds ?: 60L).coerceIn(15L, 600L)
                    foregroundRateLimitUntilMs = now + safeSeconds * 1000L
                } else {
                    foregroundBackoffUntilMs = now + FOREGROUND_TRANSIENT_BACKOFF_MS
                }
                else -> foregroundBackoffUntilMs = now + FOREGROUND_TRANSIENT_BACKOFF_MS
            }
        }
    }

    private suspend fun requireViewerUid(): Long {
        sessionRepository.sessionState.value?.uid?.let { return it }
        val refreshed = sessionRepository.refreshSession().getOrNull()
        return refreshed?.uid ?: throw NodeSeekApiException("请先登录 NodeSeek 账号。")
    }

    private companion object {
        private const val LOCAL_READ_RETENTION_MS = 120_000L
        private const val PIGGYBACK_STALE_GUARD_MS = 15_000L
        private const val API_STALE_GUARD_MS = 10_000L
        private const val FOREGROUND_CHECK_MIN_GAP_MS = 12_000L
        private const val PERIODIC_NONZERO_LIST_SYNC_MS = 45_000L
        private const val FOREGROUND_TRANSIENT_BACKOFF_MS = 20_000L
        private const val INTERACTION_FLOOR_DEDUP_RETENTION_MS = 10 * 60_000L
        private const val MAX_TRACKED_INTERACTION_FLOORS = 200
        private const val MAX_EMITTED_POPUP_KEYS = 500
        private const val MAX_POPUPS_PER_BATCH = 5
    }
}
