package com.nodeseek.app.data.repository

import com.nodeseek.app.data.network.AttendanceBoardResponseDto
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.domain.model.CheckInMember
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.repository.CheckInRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate

/**
 * Concrete [CheckInRepository] managing NodeSeek daily drumstick check-ins (fixed +5 vs random),
 * 50-per-page attendance leaderboards, and daily bonus quota progress.
 */
class CheckInRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val sessionRepository: SessionRepositoryImpl,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : CheckInRepository {

    private data class CachedPageOne(
        val uid: Long,
        val beijingDate: LocalDate,
        val fetchedAtMillis: Long,
        val status: CheckInStatus,
    )

    private val boardMutex = Mutex()
    private var cachedPageOne: CachedPageOne? = null
    private var inFlightPageOne: CompletableDeferred<Result<CheckInStatus>>? = null

    private val _checkInStatus = MutableStateFlow<CheckInStatus?>(null)
    override val checkInStatus: StateFlow<CheckInStatus?> = _checkInStatus.asStateFlow()

    override fun clearCachedStatus() {
        cachedPageOne = null
        _checkInStatus.value = null
    }

    private fun currentBeijingDate(nowMillis: Long = clockMillis()): LocalDate =
        Instant.ofEpochMilli(nowMillis)
            .atZone(AttendanceBoardResponseDto.BEIJING_ZONE)
            .toLocalDate()

    override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean): Result<CheckInStatus> {
        val safePage = page.coerceAtLeast(1)
        val uid = sessionRepository.sessionState.value?.uid ?: 0L
        val now = clockMillis()
        val todayBeijing = currentBeijingDate(now)

        if (safePage != 1) {
            return runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
                val board = apiClientProvider().getAttendanceBoard(safePage)
                board.toDomainStatus(
                    page = safePage,
                    currentUid = uid,
                    todayBeijing = todayBeijing,
                    actionMessage = null,
                )
            }
        }

        val deferred: CompletableDeferred<Result<CheckInStatus>>
        val isOwner: Boolean
        boardMutex.withLock {
            val existingCache = cachedPageOne
            if (existingCache != null &&
                (existingCache.uid != uid || existingCache.beijingDate != todayBeijing)
            ) {
                cachedPageOne = null
                _checkInStatus.value = null
            }
            if (uid <= 0L) {
                _checkInStatus.value = null
            }

            val validCache = cachedPageOne
            if (!forceRefresh &&
                validCache != null &&
                validCache.uid == uid &&
                validCache.beijingDate == todayBeijing &&
                (now - validCache.fetchedAtMillis) in 0L until PAGE_ONE_CACHE_TTL_MS
            ) {
                if (uid > 0L) {
                    _checkInStatus.value = validCache.status
                }
                return Result.success(validCache.status)
            }

            val existingInFlight = inFlightPageOne
            if (existingInFlight != null) {
                deferred = existingInFlight
                isOwner = false
            } else {
                deferred = CompletableDeferred()
                inFlightPageOne = deferred
                isOwner = true
            }
        }

        if (!isOwner) {
            return deferred.await()
        }

        return try {
            val result = runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
                val board = apiClientProvider().getAttendanceBoard(1)
                val fetchedAt = clockMillis()
                val resolvedUid = sessionRepository.sessionState.value?.uid ?: uid
                val resolvedDate = currentBeijingDate(fetchedAt)
                board.toDomainStatus(
                    page = 1,
                    currentUid = resolvedUid,
                    todayBeijing = resolvedDate,
                    actionMessage = null,
                ).also { status ->
                    boardMutex.withLock {
                        cachedPageOne = CachedPageOne(
                            uid = resolvedUid,
                            beijingDate = resolvedDate,
                            fetchedAtMillis = fetchedAt,
                            status = status,
                        )
                        _checkInStatus.value = if (resolvedUid > 0L) status else null
                    }
                }
            }
            deferred.complete(result)
            result
        } catch (c: CancellationException) {
            deferred.cancel(c)
            throw c
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            boardMutex.withLock {
                if (inFlightPageOne === deferred) {
                    inFlightPageOne = null
                }
            }
        }
    }

    override suspend fun checkIn(randomReward: Boolean): Result<CheckInStatus> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            boardMutex.withLock {
                cachedPageOne = null
            }
            val uid = sessionRepository.sessionState.value?.uid ?: 0L
            val todayBeijing = currentBeijingDate()

            val actionResp = try {
                apiClientProvider().checkIn(random = randomReward)
            } catch (apiErr: NodeSeekApiException) {
                if (isAlreadyCheckedInMessage(apiErr.serverMessage) ||
                    isAlreadyCheckedInMessage(apiErr.message)
                ) {
                    val syncedBoard = apiClientProvider().getAttendanceBoard(page = 1)
                    val fetchedAt = clockMillis()
                    val resolvedDate = currentBeijingDate(fetchedAt)
                    val syncedStatus = syncedBoard.toDomainStatus(
                        page = 1,
                        currentUid = uid,
                        todayBeijing = resolvedDate,
                        actionMessage = apiErr.serverMessage ?: apiErr.message,
                        fallbackGain = extractGainFromMessage(apiErr.serverMessage ?: apiErr.message),
                        assumeCheckedIn = true,
                    )
                    boardMutex.withLock {
                        cachedPageOne = CachedPageOne(
                            uid = uid,
                            beijingDate = resolvedDate,
                            fetchedAtMillis = fetchedAt,
                            status = syncedStatus,
                        )
                        if (uid > 0L) {
                            _checkInStatus.value = syncedStatus
                        }
                    }
                    return@runDomainCatching syncedStatus
                }
                throw apiErr
            }

            actionResp.current?.let { updatedBalance ->
                sessionRepository.updateCoinBalance(updatedBalance)
            }
            val refreshedBoard = runCatching {
                apiClientProvider().getAttendanceBoard(page = 1)
            }.getOrNull()

            val fallbackGain = actionResp.gain ?: extractGainFromMessage(actionResp.message)
            val fetchedAt = clockMillis()
            val resolvedDate = currentBeijingDate(fetchedAt)
            val resolvedUid = sessionRepository.sessionState.value?.uid ?: uid

            val resultStatus = if (refreshedBoard != null) {
                refreshedBoard.toDomainStatus(
                    page = 1,
                    currentUid = resolvedUid,
                    todayBeijing = resolvedDate,
                    actionMessage = actionResp.message,
                    fallbackGain = fallbackGain,
                    assumeCheckedIn = true,
                )
            } else {
                CheckInStatus(
                    hasCheckedInToday = true,
                    todayGain = fallbackGain,
                    todayOrder = null,
                    totalCheckedInUsers = 0,
                    leaderboard = emptyList(),
                    hasNextPage = false,
                    message = actionResp.message,
                )
            }
            boardMutex.withLock {
                cachedPageOne = CachedPageOne(
                    uid = resolvedUid,
                    beijingDate = resolvedDate,
                    fetchedAtMillis = fetchedAt,
                    status = resultStatus,
                )
                if (resolvedUid > 0L || resultStatus.hasCheckedInToday) {
                    _checkInStatus.value = resultStatus
                }
            }
            resultStatus
        }

    override suspend fun getDailyProgress(): Result<DailyQuotaProgress> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val dto = apiClientProvider().getDailyProgress()
            DailyQuotaProgress(
                postBonusEarned = dto.postBonusCount,
                maxPostBonus = dto.maxPostBonusCount,
                replyBonusEarned = dto.commentBonusCount,
                maxReplyBonus = dto.maxCommentBonusCount,
                freeDrumsticksUsed = dto.freeLikeUsed,
                maxFreeDrumsticks = dto.maxFreeLike,
            )
        }

    private fun AttendanceBoardResponseDto.toDomainStatus(
        page: Int,
        currentUid: Long,
        todayBeijing: LocalDate,
        actionMessage: String?,
        fallbackGain: Int? = null,
        assumeCheckedIn: Boolean = false,
    ): CheckInStatus {
        val baseRank = (page - 1) * PAGE_SIZE
        val members = list.mapIndexed { index, item ->
            CheckInMember(
                rank = baseRank + index + 1,
                uid = item.memberId,
                username = item.memberName,
                gain = item.gain,
                avatarUrl = avatarUrlForUid(item.memberId),
            )
        }

        if (currentUid <= 0L && !assumeCheckedIn) {
            return CheckInStatus(
                hasCheckedInToday = false,
                todayGain = null,
                todayOrder = null,
                totalCheckedInUsers = total,
                leaderboard = members,
                hasNextPage = page * PAGE_SIZE < total,
                message = actionMessage ?: message,
            )
        }

        val memberMatches = record?.memberId == null ||
            record.memberId <= 0L ||
            currentUid <= 0L ||
            record.memberId == currentUid
        val recordDate = AttendanceBoardResponseDto.parseBeijingLocalDate(record?.createdAt)
        val dateMatches = recordDate == null || recordDate == todayBeijing
        val validBoardRecord = hasCheckedInToday && memberMatches && dateMatches

        val selfInFirstPage = if (page == 1 && currentUid > 0L) {
            members.firstOrNull { it.uid == currentUid }
        } else {
            null
        }

        val resolvedCheckedIn = assumeCheckedIn || validBoardRecord || (selfInFirstPage != null)
        val resolvedGain = if (resolvedCheckedIn) {
            (if (validBoardRecord) record?.gain else null)
                ?: selfInFirstPage?.gain
                ?: fallbackGain
        } else {
            null
        }
        val resolvedOrder = if (resolvedCheckedIn) {
            (if (validBoardRecord) order else null)
                ?: selfInFirstPage?.rank
                ?: (if (assumeCheckedIn) order else null)
        } else {
            null
        }

        return CheckInStatus(
            hasCheckedInToday = resolvedCheckedIn,
            todayGain = resolvedGain,
            todayOrder = resolvedOrder,
            totalCheckedInUsers = total,
            leaderboard = members,
            hasNextPage = page * PAGE_SIZE < total,
            message = actionMessage ?: message,
        )
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val PAGE_ONE_CACHE_TTL_MS = 15_000L
        private val GAIN_MESSAGE_REGEX = Regex("""(?:获得|\+)\s*(\d+)\s*个?鸡腿""")

        internal fun isAlreadyCheckedInMessage(msg: String?): Boolean {
            if (msg.isNullOrBlank()) return false
            return msg.contains("已完成签到") ||
                msg.contains("已签到") ||
                msg.contains("重复签到") ||
                (msg.contains("签到") && msg.contains("重复"))
        }

        internal fun extractGainFromMessage(msg: String?): Int? {
            if (msg.isNullOrBlank()) return null
            return GAIN_MESSAGE_REGEX.find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }
}
