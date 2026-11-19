package com.nodeseek.app.data.trust

import android.content.Context
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.domain.trust.ExposureCandidate
import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.SellerTrustReport
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.trust.TrustTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete [SellerTrustRepository] aggregating seller profile, recent user topics,
 * station exposure searches (`"曝光 <username>"` & `"骗子 <username>"`), and persistent
 * device-local seller notes (`filesDir/seller_trust_notes.json`).
 *
 * All remote requests run concurrently via `coroutineScope { async ... }` and degrade gracefully
 * on partial failures (e.g. unauthenticated or rate-limited search while profile succeeds).
 * Results are cached in memory for 5 minutes unless `forceRefresh = true`.
 */
class SellerTrustRepositoryImpl(
    private val userRepository: UserRepository,
    private val forumRepository: ForumRepository,
    notesFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
) : SellerTrustRepository {

    constructor(
        context: Context,
        userRepository: UserRepository,
        forumRepository: ForumRepository,
    ) : this(
        userRepository = userRepository,
        forumRepository = forumRepository,
        notesFile = File((context.applicationContext ?: context).filesDir, NOTES_FILE_NAME),
    )

    private data class CachedReport(
        val cachedAtEpochMillis: Long,
        val report: SellerTrustReport,
    )

    private val noteStore = SellerTrustNoteStore(
        notesFile = notesFile,
        ioDispatcher = ioDispatcher,
        clockMillis = clockMillis,
    )

    private val cacheMutex = Mutex()
    private val reportCache = LinkedHashMap<String, CachedReport>(32, 0.75f, true)

    override suspend fun inspectSeller(
        uid: Long,
        usernameHint: String,
        forceRefresh: Boolean,
    ): SellerTrustReport = withContext(ioDispatcher) {
        val cleanHint = sanitizeUsernameHint(usernameHint)
        val initialCacheKey = buildCacheKey(uid = uid, username = cleanHint)

        if (!forceRefresh) {
            val cached = getFreshCachedReport(initialCacheKey)
            if (cached != null) {
                val latestNote = noteStore.getNote(cached.uid)
                val synced = if (latestNote != cached.localNote) {
                    reEvaluateWithLocalNote(cached, latestNote)
                } else {
                    cached
                }
                return@withContext synced.copy(isFromCache = true)
            }
        }

        // Resolve UID if caller only had a username hint (e.g. from @mention)
        val targetUid = if (uid > 0L) {
            uid
        } else if (cleanHint.isNotEmpty()) {
            runSafeResult { userRepository.searchUsers(cleanHint) }
                .getOrNull()
                ?.firstOrNull { it.username.equals(cleanHint, ignoreCase = true) }
                ?.uid
                ?: 0L
        } else {
            0L
        }

        val aggregated = coroutineScope {
            val profileDeferred: Deferred<Result<UserProfile>> = async {
                if (targetUid > 0L) {
                    runSafeResult { userRepository.getUserProfile(targetUid) }
                } else {
                    Result.failure(IllegalArgumentException("缺少有效的卖家 UID"))
                }
            }

            val topicsDeferred: Deferred<Result<Pair<List<TopicSummary>, Boolean>>> = async {
                if (targetUid > 0L) {
                    runSafeResult { userRepository.getUserTopics(targetUid, page = 1) }
                } else {
                    Result.success(emptyList<TopicSummary>() to false)
                }
            }

            val localNoteDeferred: Deferred<LocalSellerNote?> = async {
                noteStore.getNote(targetUid)
            }

            // Launch station exposure searches concurrently right away when usernameHint is usable
            val hintExposeDeferred: Deferred<Result<FeedPageResult>>? =
                if (cleanHint.isNotEmpty()) {
                    async {
                        runSafeResult {
                            forumRepository.searchTopics(
                                query = "${TrustRiskEvaluator.TERM_EXPOSE} $cleanHint",
                                page = 1,
                            )
                        }
                    }
                } else {
                    null
                }

            val hintScamDeferred: Deferred<Result<FeedPageResult>>? =
                if (cleanHint.isNotEmpty()) {
                    async {
                        runSafeResult {
                            forumRepository.searchTopics(
                                query = "${TrustRiskEvaluator.TERM_SCAMMER} $cleanHint",
                                page = 1,
                            )
                        }
                    }
                } else {
                    null
                }

            val profileResult = profileDeferred.await()
            val topicsResult = topicsDeferred.await()
            val localNote = localNoteDeferred.await()

            val profile = profileResult.getOrNull()
            val canonicalUsername = profile?.username?.trim()?.takeIf { it.isNotEmpty() }
                ?: cleanHint.ifEmpty { localNote?.username?.trim().orEmpty() }

            // If hint was blank/placeholder or differs from canonical username, run exposure searches for canonical name
            val needCanonicalSearch = canonicalUsername.isNotEmpty() &&
                !canonicalUsername.equals(cleanHint, ignoreCase = true)

            val exposeResult: Result<FeedPageResult>?
            val scamResult: Result<FeedPageResult>?

            if (needCanonicalSearch) {
                val canonicalExpose = async {
                    runSafeResult {
                        forumRepository.searchTopics(
                            query = "${TrustRiskEvaluator.TERM_EXPOSE} $canonicalUsername",
                            page = 1,
                        )
                    }
                }
                val canonicalScam = async {
                    runSafeResult {
                        forumRepository.searchTopics(
                            query = "${TrustRiskEvaluator.TERM_SCAMMER} $canonicalUsername",
                            page = 1,
                        )
                    }
                }
                // Still await hint searches if launched so coroutineScope stays clean
                hintExposeDeferred?.await()
                hintScamDeferred?.await()
                exposeResult = canonicalExpose.await()
                scamResult = canonicalScam.await()
            } else {
                exposeResult = hintExposeDeferred?.await()
                scamResult = hintScamDeferred?.await()
            }

            AggregationResult(
                targetUid = profile?.uid?.takeIf { it > 0L } ?: targetUid,
                canonicalUsername = canonicalUsername,
                profileResult = profileResult,
                topicsResult = topicsResult,
                exposeSearchResult = exposeResult,
                scamSearchResult = scamResult,
                localNote = localNote,
            )
        }

        val now = clockMillis()
        val report = buildReport(
            aggregated = aggregated,
            fallbackUsername = usernameHint.trim().ifEmpty {
                if (aggregated.targetUid > 0L) "用户 #${aggregated.targetUid}" else "未知卖家"
            },
            nowEpochMillis = now,
        )

        val anyRemoteSucceeded = report.profileSucceeded || report.topicsSucceeded || report.searchSucceeded
        if (anyRemoteSucceeded) {
            val resolvedKey = buildCacheKey(uid = report.uid, username = report.username)
            putCachedReport(key = resolvedKey, report = report, cachedAtEpochMillis = now)
            if (resolvedKey != initialCacheKey) {
                putCachedReport(key = initialCacheKey, report = report, cachedAtEpochMillis = now)
            }
        }

        report
    }

    override fun observeLocalNote(uid: Long): Flow<LocalSellerNote?> =
        noteStore.observeNote(uid)

    override suspend fun saveLocalNote(
        uid: Long,
        username: String,
        tag: TrustTag,
        noteText: String,
    ) {
        val savedNote = noteStore.saveNote(
            uid = uid,
            username = username,
            tag = tag,
            noteText = noteText,
        )
        // Immediately synchronize any cached report for this seller so UI & cached reads reflect the new tag
        cacheMutex.withLock {
            val matchingKeys = reportCache.entries
                .filter { it.value.report.uid == uid }
                .map { it.key }
            for (key in matchingKeys) {
                val entry = reportCache[key] ?: continue
                reportCache[key] = entry.copy(
                    report = reEvaluateWithLocalNote(entry.report, savedNote),
                )
            }
        }
    }

    override suspend fun getAllNotes(): List<LocalSellerNote> =
        noteStore.getAllNotes()

    private fun buildReport(
        aggregated: AggregationResult,
        fallbackUsername: String,
        nowEpochMillis: Long,
    ): SellerTrustReport {
        val profile = aggregated.profileResult.getOrNull()
        val profileSucceeded = aggregated.profileResult.isSuccess && profile != null
        val resolvedUid = aggregated.targetUid
        val resolvedUsername = aggregated.canonicalUsername.ifBlank { fallbackUsername }

        val topicsSucceeded = aggregated.topicsResult.isSuccess
        val allUserTopics = aggregated.topicsResult.getOrNull()?.first.orEmpty()
        val tradeTopics = allUserTopics.filter(TrustRiskEvaluator::isTradeTopic)

        val candidates = mutableListOf<ExposureCandidate>()
        aggregated.exposeSearchResult?.getOrNull()?.let { page ->
            (page.pinnedTopics + page.topics).forEach { topic ->
                candidates += ExposureCandidate(
                    topic = topic,
                    queryTermHint = TrustRiskEvaluator.TERM_EXPOSE,
                )
            }
        }
        aggregated.scamSearchResult?.getOrNull()?.let { page ->
            (page.pinnedTopics + page.topics).forEach { topic ->
                candidates += ExposureCandidate(
                    topic = topic,
                    queryTermHint = TrustRiskEvaluator.TERM_SCAMMER,
                )
            }
        }

        val exposeSucceeded = aggregated.exposeSearchResult?.isSuccess == true
        val scamSucceeded = aggregated.scamSearchResult?.isSuccess == true
        val searchSucceeded = aggregated.canonicalUsername.isNotBlank() && exposeSucceeded && scamSucceeded

        val searchFailureReason = when {
            searchSucceeded -> null
            aggregated.canonicalUsername.isBlank() -> "未获取到卖家用户名，无法执行站内曝光检索"
            else -> {
                val failure = aggregated.exposeSearchResult?.exceptionOrNull()
                    ?: aggregated.scamSearchResult?.exceptionOrNull()
                formatSearchFailureReason(failure)
            }
        }

        val exposureHits = if (aggregated.canonicalUsername.isNotBlank()) {
            TrustRiskEvaluator.filterExposureHits(
                candidates = candidates,
                targetUid = resolvedUid,
                targetUsername = aggregated.canonicalUsername,
            )
        } else {
            emptyList()
        }

        val tenure = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = profile?.joinedAtIso,
            nowEpochMillis = nowEpochMillis,
            zoneId = zoneId,
        )

        val rankLevel = profile?.level ?: 0
        val resolvedAvatar = profile?.avatarUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (resolvedUid > 0L) "$NODESEEK_ORIGIN/avatar/$resolvedUid.png" else ""

        val riskAssessment = TrustRiskEvaluator.evaluate(
            rankLevel = rankLevel,
            registrationDays = tenure.registrationDays,
            historicalTradeTopicCount = tradeTopics.size,
            exposureSearchHits = exposureHits,
            searchSucceeded = searchSucceeded,
            localNote = aggregated.localNote,
            profileSucceeded = profileSucceeded,
            registrationDurationLabel = tenure.durationLabel,
        )

        return SellerTrustReport(
            uid = resolvedUid,
            username = resolvedUsername,
            avatarUrl = resolvedAvatar,
            rankLevel = rankLevel,
            coins = profile?.drumsticks,
            stardust = profile?.stardust,
            createdAtText = tenure.createdAtText,
            registrationDays = tenure.registrationDays,
            registrationDurationLabel = tenure.durationLabel,
            totalTopicsCount = profile?.topicCount,
            totalCommentsCount = profile?.replyCount,
            historicalTradeTopicCount = tradeTopics.size,
            recentTradeTopics = tradeTopics.take(MAX_RECENT_TRADE_TOPICS),
            exposureSearchHits = exposureHits,
            searchSucceeded = searchSucceeded,
            localNote = aggregated.localNote,
            riskAssessment = riskAssessment,
            profileSucceeded = profileSucceeded,
            topicsSucceeded = topicsSucceeded,
            searchFailureReason = searchFailureReason,
            fetchedAtEpochMillis = nowEpochMillis,
            isFromCache = false,
        )
    }

    private fun reEvaluateWithLocalNote(
        report: SellerTrustReport,
        note: LocalSellerNote?,
    ): SellerTrustReport {
        val nextAssessment = TrustRiskEvaluator.evaluate(
            rankLevel = report.rankLevel,
            registrationDays = report.registrationDays,
            historicalTradeTopicCount = report.historicalTradeTopicCount,
            exposureSearchHits = report.exposureSearchHits,
            searchSucceeded = report.searchSucceeded,
            localNote = note,
            profileSucceeded = report.profileSucceeded,
            registrationDurationLabel = report.registrationDurationLabel,
        )
        return report.copy(
            localNote = note,
            riskAssessment = nextAssessment,
        )
    }

    private suspend fun getFreshCachedReport(key: String): SellerTrustReport? {
        if (key.isEmpty()) return null
        val now = clockMillis()
        return cacheMutex.withLock {
            val entry = reportCache[key] ?: return@withLock null
            val age = now - entry.cachedAtEpochMillis
            if (age in 0L until cacheTtlMillis) {
                entry.report
            } else {
                reportCache.remove(key)
                null
            }
        }
    }

    private suspend fun putCachedReport(
        key: String,
        report: SellerTrustReport,
        cachedAtEpochMillis: Long,
    ) {
        if (key.isEmpty()) return
        cacheMutex.withLock {
            reportCache[key] = CachedReport(
                cachedAtEpochMillis = cachedAtEpochMillis,
                report = report,
            )
            while (reportCache.size > MAX_CACHED_REPORTS) {
                val eldestKey = reportCache.entries.firstOrNull()?.key ?: break
                reportCache.remove(eldestKey)
            }
        }
    }

    private suspend inline fun <T> runSafeResult(
        crossinline block: suspend () -> Result<T>,
    ): Result<T> = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private fun formatSearchFailureReason(failure: Throwable?): String = when (failure) {
        is TopicAccessException.SignInRequired -> "需要登录后才可使用站内曝光搜索"
        is TopicAccessException.CloudflareChallengeRequired -> "触发安全验证，站内曝光检索暂不可用"
        is NodeSeekApiException -> when (failure.statusCode) {
            429 -> "站内搜索请求过于频繁（限流），请稍后刷新核验"
            401, 403 -> "当前未登录或无搜索权限，站内曝光检索未完成"
            else -> failure.message?.takeIf { it.isNotBlank() } ?: "站内曝光搜索暂时不可用"
        }
        else -> failure?.message?.takeIf { it.isNotBlank() } ?: "站内曝光搜索暂时不可用"
    }

    private fun sanitizeUsernameHint(raw: String): String {
        val trimmed = raw.trim().removePrefix("@")
        if (trimmed.isEmpty()) return ""
        if (PLACEHOLDER_USER_REGEX.matches(trimmed)) return ""
        return trimmed
    }

    private fun buildCacheKey(uid: Long, username: String): String = when {
        uid > 0L -> "uid:$uid"
        username.isNotBlank() -> "name:${username.lowercase()}"
        else -> ""
    }

    private data class AggregationResult(
        val targetUid: Long,
        val canonicalUsername: String,
        val profileResult: Result<UserProfile>,
        val topicsResult: Result<Pair<List<TopicSummary>, Boolean>>,
        val exposeSearchResult: Result<FeedPageResult>?,
        val scamSearchResult: Result<FeedPageResult>?,
        val localNote: LocalSellerNote?,
    )

    companion object {
        const val NOTES_FILE_NAME: String = "seller_trust_notes.json"
        const val DEFAULT_CACHE_TTL_MILLIS: Long = 5L * 60L * 1000L
        const val MAX_RECENT_TRADE_TOPICS: Int = 5
        private const val MAX_CACHED_REPORTS: Int = 100
        private const val NODESEEK_ORIGIN: String = "https://www.nodeseek.com"
        private val PLACEHOLDER_USER_REGEX = Regex("^(?:用户|成员|user)\\s*#?\\d+$", RegexOption.IGNORE_CASE)

        private val sharedInstances = ConcurrentHashMap<String, SellerTrustRepositoryImpl>()

        /**
         * Returns a process-scoped [SellerTrustRepositoryImpl] backed by `filesDir/seller_trust_notes.json`,
         * allowing Compose screens and `@integrator` wiring to share both the 5-minute TTL cache and
         * reactive local note state effortlessly.
         */
        fun forContext(
            context: Context,
            userRepository: UserRepository,
            forumRepository: ForumRepository,
        ): SellerTrustRepositoryImpl {
            val appCtx = context.applicationContext ?: context
            val file = File(appCtx.filesDir, NOTES_FILE_NAME)
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            return sharedInstances.getOrPut(key) {
                SellerTrustRepositoryImpl(
                    userRepository = userRepository,
                    forumRepository = forumRepository,
                    notesFile = file,
                )
            }
        }
    }
}
