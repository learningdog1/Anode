package com.nodeseek.app.data.trust

import com.nodeseek.app.data.network.CloudflareChallengeDetector
import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import com.nodeseek.app.data.network.SameOriginTransport
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.parser.ConfigPayloadDto
import com.nodeseek.app.data.parser.NodeSeekPostAccessException
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.UserRepository
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Lightweight remote data source dedicated to Seller Trust checks.
 */
internal interface SellerTrustRemoteDataSource {
    val siteHost: String

    suspend fun resolveUidByUsername(username: String): Long?

    suspend fun fetchSellerProfile(uid: Long): UserProfile

    suspend fun fetchSellerTopicsPage1(uid: Long): List<TopicSummary>

    suspend fun searchExposureTopicsPage1(query: String): FeedPageResult
}

/**
 * Adapter wrapping [UserRepository] and [ForumRepository] for backward compatibility and unit-test fakes.
 */
internal class RepositorySellerTrustRemoteDataSource(
    private val userRepository: UserRepository,
    private val forumRepository: ForumRepository,
    override val siteHost: String = SellerTrustRateLimiter.DEFAULT_SITE_HOST,
) : SellerTrustRemoteDataSource {

    override suspend fun resolveUidByUsername(username: String): Long? =
        userRepository.searchUsers(username)
            .getOrThrow()
            .firstOrNull { it.username.equals(username, ignoreCase = true) }
            ?.uid

    override suspend fun fetchSellerProfile(uid: Long): UserProfile =
        userRepository.getUserProfile(uid = uid, includeEditableSignature = false).getOrThrow()

    override suspend fun fetchSellerTopicsPage1(uid: Long): List<TopicSummary> =
        userRepository.getUserTopics(uid = uid, page = 1).getOrThrow().first

    override suspend fun searchExposureTopicsPage1(query: String): FeedPageResult =
        forumRepository.searchTopics(query = query, page = 1).getOrThrow()
}

/**
 * Production lightweight HTTP data source for Seller Trust:
 * - Uses dedicated bounded timeouts (`8s` connect, `10s` read/write, `15s` call).
 * - Guarantees realistic synchronized browser `User-Agent`, `Accept`, `Accept-Language`, `Referer`,
 *   and `X-Requested-With` headers, plus raw `429`/`503` + `Retry-After` detection.
 * - Replaces heavy calls with minimal JSON endpoints:
 *   - `GET /api/account/getInfo/{uid}` (`includeReadme = false`, skips `/api/block-list/list`).
 *   - `GET /api/content/list-discussions?uid={uid}&page=1` (JSON page-1 only).
 *   - `GET /api/account/find/{username}` (JSON user lookup).
 *   - `GET /search?q={query}&page=1&sortBy=replyTime` (page-1 HTML without Room DB overhead).
 */
internal class HttpSellerTrustRemoteDataSource(
    baseHttpClient: OkHttpClient,
    userAgentProvider: UserAgentProvider = UserAgentProvider(),
    baseOrigin: HttpUrl = NodeSeekUrlNormalizer.ORIGIN.toHttpUrl(),
    sameOriginTransport: SameOriginTransport? = null,
    onConfigObserved: ((ConfigPayloadDto) -> Unit)? = null,
    clockMillis: () -> Long = System::currentTimeMillis,
    connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
    readTimeoutSeconds: Long = DEFAULT_READ_TIMEOUT_SECONDS,
    writeTimeoutSeconds: Long = DEFAULT_WRITE_TIMEOUT_SECONDS,
    callTimeoutSeconds: Long = DEFAULT_CALL_TIMEOUT_SECONDS,
) : SellerTrustRemoteDataSource {

    override val siteHost: String = baseOrigin.host.lowercase()

    private val trustHttpClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .addInterceptor(
            TrustHttpGovernanceInterceptor(
                userAgentProvider = userAgentProvider,
                originReferer = "${baseOrigin.scheme}://${baseOrigin.host}/",
                clockMillis = clockMillis,
            ),
        )
        .build()

    private val apiClient = NodeSeekApiClient(
        httpClient = trustHttpClient,
        baseOrigin = baseOrigin,
        sameOriginTransport = sameOriginTransport,
        onConfigObserved = onConfigObserved,
    )

    override suspend fun resolveUidByUsername(username: String): Long? = mapTrustRemoteExceptions {
        val clean = username.trim()
        if (clean.isEmpty()) return@mapTrustRemoteExceptions null
        apiClient.searchUsers(clean)
            .memberList
            .firstOrNull { it.memberName.equals(clean, ignoreCase = true) }
            ?.memberId
            ?.takeIf { it > 0L }
    }

    override suspend fun fetchSellerProfile(uid: Long): UserProfile = mapTrustRemoteExceptions {
        if (uid <= 0L) {
            throw NodeSeekApiException("缺少有效的卖家 UID", statusCode = 400)
        }
        // Lightweight JSON request: omit ?readme=1 and skip /api/block-list/list.
        val response = apiClient.getUserProfile(
            uid = uid,
            includeReadme = false,
            includeEditableSignature = false,
        )
        val detail = response.resolvedDetail
            ?: throw NodeSeekApiException("没有找到这位用户。", statusCode = 404, serverMessage = "USER NOT FOUND")
        val resolvedUid = detail.memberId?.takeIf { it > 0L } ?: uid
        if (resolvedUid != uid) {
            throw NodeSeekApiException("返回的用户编号不一致，请重试。")
        }
        val avatarRaw = detail.avatar?.trim()?.takeIf { it.isNotEmpty() } ?: "/avatar/$resolvedUid.png"
        UserProfile(
            uid = resolvedUid,
            username = detail.memberName.trim(),
            avatarUrl = NodeSeekUrlNormalizer.normalizeNodeSeekUrl(avatarRaw),
            joinedAtIso = detail.createdAt,
            drumsticks = detail.coin,
            stardust = detail.stardust,
            topicCount = detail.nPost,
            replyCount = detail.nComment,
            followingCount = detail.follows,
            followerCount = detail.fans,
        )
    }

    override suspend fun fetchSellerTopicsPage1(uid: Long): List<TopicSummary> = mapTrustRemoteExceptions {
        val response = apiClient.listUserDiscussions(uid = uid, page = 1)
        response.resolvedDiscussions.map { row ->
            val catSlug = row.categorySlug?.trim().orEmpty()
            val catName = row.categoryTitle?.trim()?.takeIf { it.isNotEmpty() }
                ?: Category.DEFAULT_CATEGORIES.firstOrNull { it.slug == catSlug }?.name.orEmpty()
            TopicSummary(
                id = row.postId,
                title = row.title,
                categorySlug = catSlug,
                categoryName = catName,
                authorId = uid,
                authorName = row.authorName?.trim().orEmpty(),
                authorAvatarUrl = NodeSeekUrlNormalizer.normalizeNodeSeekUrl("/avatar/$uid.png"),
                viewCount = row.viewsCount ?: 0,
                replyCount = row.commentsCount ?: 0,
                lastActivityIso = row.createdAt?.trim().orEmpty(),
                requiredViewLevel = row.rank?.takeIf { it > 0 },
            )
        }
    }

    override suspend fun searchExposureTopicsPage1(query: String): FeedPageResult = mapTrustRemoteExceptions {
        val parsed = apiClient.searchTopicsPage(
            query = query,
            sortBy = "replyTime",
            page = 1,
            includeBlocked = false,
        )
        fun com.nodeseek.app.data.parser.FeedTopicDto.toSummary(): TopicSummary = TopicSummary(
            id = id,
            title = title,
            categorySlug = categoryKey,
            categoryName = categoryName,
            authorId = authorId,
            authorName = author,
            authorAvatarUrl = avatarUrl.ifBlank {
                authorId?.takeIf { it > 0L }?.let { NodeSeekUrlNormalizer.normalizeNodeSeekUrl("/avatar/$it.png") }.orEmpty()
            },
            viewCount = views,
            replyCount = replies,
            lastActivityIso = activityDate,
            isPinned = pinned,
            requiredViewLevel = requiredRank,
        )
        FeedPageResult(
            pinnedTopics = parsed.pinnedTopics.map { it.toSummary() },
            topics = parsed.topics.map { it.toSummary() },
            currentPage = parsed.currentPage,
            hasNextPage = parsed.hasNextPage,
            isFromRssFallback = parsed.isFromRssFallback,
        )
    }

    private suspend inline fun <T> mapTrustRemoteExceptions(
        crossinline block: suspend () -> T,
    ): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (challenge: CloudflareChallengeException) {
        throw TopicAccessException.CloudflareChallengeRequired
    } catch (signIn: NodeSeekPostAccessException.SignInRequired) {
        throw TopicAccessException.SignInRequired
    } catch (privatePost: NodeSeekPostAccessException.PrivatePost) {
        throw TopicAccessException.PrivateTopic
    } catch (levelReq: NodeSeekPostAccessException.LevelRequired) {
        throw TopicAccessException.LevelRequired(levelReq.minimumLevel)
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS: Long = 8L
        const val DEFAULT_READ_TIMEOUT_SECONDS: Long = 10L
        const val DEFAULT_WRITE_TIMEOUT_SECONDS: Long = 10L
        const val DEFAULT_CALL_TIMEOUT_SECONDS: Long = 15L
    }
}

/**
 * Ensures realistic browser headers on all Seller Trust HTTP requests and captures upstream
 * `429` / `503` responses with full RFC 9110 `Retry-After` parsing (seconds or HTTP-date).
 */
internal class TrustHttpGovernanceInterceptor(
    private val userAgentProvider: UserAgentProvider,
    private val originReferer: String = "${NodeSeekUrlNormalizer.ORIGIN}/",
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url
        val isApiPath = url.encodedPath.startsWith("/api/")

        val reqBuilder = original.newBuilder()
        val currentUa = original.header("User-Agent")?.trim().orEmpty()
        if (currentUa.isEmpty() || currentUa.startsWith("okhttp/", ignoreCase = true)) {
            reqBuilder.header("User-Agent", userAgentProvider.getUserAgent())
        }
        if (original.header("Accept-Language").isNullOrBlank()) {
            reqBuilder.header("Accept-Language", userAgentProvider.getAcceptLanguage())
        }
        if (original.header("Accept").isNullOrBlank()) {
            reqBuilder.header(
                "Accept",
                if (isApiPath) {
                    "application/json, text/plain, */*"
                } else {
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                },
            )
        }
        if (original.header("Referer").isNullOrBlank()) {
            reqBuilder.header("Referer", originReferer)
        }
        if (isApiPath && original.header("X-Requested-With").isNullOrBlank()) {
            reqBuilder.header("X-Requested-With", "XMLHttpRequest")
        }

        val response = chain.proceed(reqBuilder.build())
        val code = response.code
        if (code == 429 || code == 503) {
            val isCfChallengeHeader = CloudflareChallengeDetector.hasChallengeMitigationHeader(response.headers)
            val peeked = runCatching { response.peekBody(8_192L).string() }.getOrDefault("")
            val isCfChallenge503 = code == 503 && (
                peeked.contains("cf-browser-verification", ignoreCase = true) ||
                    peeked.contains("challenges.cloudflare.com", ignoreCase = true) ||
                    peeked.contains("Just a moment...", ignoreCase = true)
                )
            if (!isCfChallengeHeader && !isCfChallenge503) {
                val retryAfterMs = parseRetryAfterMillis(
                    rawValue = response.header("Retry-After"),
                    nowEpochMillis = clockMillis(),
                )
                response.close()
                throw SellerTrustHttpStatusException(
                    statusCode = code,
                    host = url.host,
                    retryAfterMillis = retryAfterMs,
                )
            }
        }
        return response
    }
}
