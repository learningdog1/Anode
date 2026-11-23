package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.BlockedUsersCache
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import com.nodeseek.app.domain.repository.UserRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Concrete [UserRepository] backing public/self user profiles, user topics & HTML-cleaned reply
 * excerpts, user search, follow/unfollow, and block list management.
 */
class UserRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val sessionRepository: SessionRepositoryImpl,
    private val blockedUsersCache: BlockedUsersCache? = null,
    private val knownTopicsResolver: (suspend (Collection<Long>) -> Map<Long, TopicSummary>)? = null,
) : UserRepository {

    override suspend fun getUserProfile(
        uid: Long,
        includeEditableSignature: Boolean,
    ): Result<UserProfile> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (uid <= 0L) {
                throw NodeSeekApiException("没有找到这位用户，请检查用户编号。")
            }
            val activeSession = sessionRepository.sessionState.value
            val isSelfSession = activeSession?.uid == uid
            // NodeSeek restricts `?signature=1` on `/api/account/getInfo/{uid}` strictly to the
            // authenticated owner's own UID (`isPublic = false`); passing `signature=1` for any
            // other member or while signed out causes NodeSeek to return `"USER NOT FOUND"`.
            val requestEditableSignature = includeEditableSignature && isSelfSession

            coroutineScope {
                val blockedIdsDeferred = async {
                    if (activeSession != null && !isSelfSession) {
                        runSuspendCatching {
                            apiClientProvider().listBlockedUsers().data.map { it.blockMemberId }.toSet()
                        }.getOrDefault(emptySet())
                    } else {
                        emptySet()
                    }
                }

                val (response, loadedEditableSignature) = if (requestEditableSignature) {
                    try {
                        apiClientProvider().getUserProfile(
                            uid = uid,
                            includeReadme = true,
                            includeEditableSignature = true,
                        ) to true
                    } catch (_: NodeSeekApiException) {
                        // Graceful fallback to public profile endpoint if server session expired
                        apiClientProvider().getUserProfile(
                            uid = uid,
                            includeReadme = true,
                            includeEditableSignature = false,
                        ) to false
                    }
                } else {
                    apiClientProvider().getUserProfile(
                        uid = uid,
                        includeReadme = true,
                        includeEditableSignature = false,
                    ) to false
                }

                val detail = response.resolvedDetail
                    ?: throw NodeSeekApiException("没有找到这位用户。")
                val resolvedUid = detail.memberId?.takeIf { it > 0L } ?: uid
                if (resolvedUid != uid) {
                    throw NodeSeekApiException("返回的用户编号不一致，请重试。")
                }
                val blockedIds = blockedIdsDeferred.await()

                val resolvedSignature = if (loadedEditableSignature) {
                    detail.signatureMarkdown?.takeIf { it.isNotBlank() }?.trim()
                        ?: NodeSeekHtmlParser.normalizeSignatureToMarkdown(detail.signature)
                } else {
                    NodeSeekHtmlParser.normalizeSignatureToMarkdown(
                        detail.signatureMarkdown?.takeIf { it.isNotBlank() } ?: detail.signature,
                    )
                }

                val selfFallback = activeSession?.takeIf { isSelfSession }
                val resolvedUsername = detail.memberName.trim()
                    .ifEmpty { selfFallback?.username.orEmpty() }

                UserProfile(
                    uid = resolvedUid,
                    username = resolvedUsername,
                    avatarUrl = avatarUrlForUid(resolvedUid, detail.avatar),
                    bio = detail.bio,
                    readmeMarkdown = detail.readme,
                    signatureMarkdown = resolvedSignature,
                    joinedAtIso = detail.createdAt,
                    drumsticks = detail.coin ?: selfFallback?.drumsticks,
                    stardust = detail.stardust ?: selfFallback?.stardust,
                    topicCount = detail.nPost ?: selfFallback?.topicCount,
                    replyCount = detail.nComment ?: selfFallback?.replyCount,
                    followingCount = detail.follows,
                    followerCount = detail.fans,
                    isFollowedByViewer = if (isSelfSession) null else detail.followed,
                    isBlockedByViewer = uid in blockedIds,
                )
            }
        }

    override suspend fun updateOwnProfile(
        bio: String,
        signature: String,
        readme: String,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            apiClientProvider().updateIntroduction(
                bio = bio,
                signature = signature,
                readme = readme,
            )
        }

    override suspend fun getUserTopics(
        uid: Long,
        page: Int,
    ): Result<Pair<List<TopicSummary>, Boolean>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val safePage = page.coerceAtLeast(1)
            val response = apiClientProvider().listUserDiscussions(uid = uid, page = safePage)
            val rows = response.resolvedDiscussions
            val resolver = knownTopicsResolver
            val knownById = if (rows.isNotEmpty() && resolver != null) {
                runSuspendCatching { resolver.invoke(rows.map { it.postId }) }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            val topics = rows.map { row ->
                val known = knownById[row.postId]
                val catSlug = row.categorySlug?.trim()?.takeIf { it.isNotEmpty() }
                    ?: known?.categorySlug?.trim().orEmpty()
                val catName = row.categoryTitle?.trim()?.takeIf { it.isNotEmpty() }
                    ?: known?.categoryName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: catSlug.takeIf { it.isNotEmpty() }?.let { slug ->
                        Category.DEFAULT_CATEGORIES.firstOrNull { it.slug == slug }?.name
                    }.orEmpty()
                TopicSummary(
                    id = row.postId,
                    title = row.title,
                    categorySlug = catSlug,
                    categoryName = catName,
                    authorId = uid,
                    authorName = row.authorName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: known?.authorName.orEmpty(),
                    authorAvatarUrl = avatarUrlForUid(uid),
                    viewCount = row.viewsCount ?: known?.viewCount ?: 0,
                    replyCount = row.commentsCount ?: known?.replyCount ?: 0,
                    lastActivityIso = row.createdAt?.trim()?.takeIf { it.isNotEmpty() }
                        ?: known?.lastActivityIso.orEmpty(),
                    requiredViewLevel = row.rank?.takeIf { it > 0 },
                )
            }
            Pair(topics, response.hasMorePages(currentPage = safePage, defaultPageSize = USER_CONTENT_PAGE_SIZE))
        }

    override suspend fun getUserReplies(
        uid: Long,
        page: Int,
    ): Result<Pair<List<UserReplyExcerpt>, Boolean>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val safePage = page.coerceAtLeast(1)
            val response = apiClientProvider().listUserComments(uid = uid, page = safePage)
            val rows = response.resolvedComments
            val replies = rows.map { row ->
                UserReplyExcerpt(
                    topicId = row.postId,
                    topicTitle = row.title,
                    floorNumber = row.floorId ?: 0,
                    plainTextExcerpt = row.plainTextExcerpt.orEmpty(),
                    createdAtIso = row.createdAt?.trim().orEmpty(),
                )
            }
            Pair(replies, response.hasMorePages(currentPage = safePage, defaultPageSize = USER_CONTENT_PAGE_SIZE))
        }

    override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val trimmed = keyword.trim().removePrefix("@").trim()
            if (trimmed.isEmpty()) return@runDomainCatching emptyList()
            apiClientProvider().searchUsers(trimmed).memberList.map { member ->
                FloorAuthor(
                    uid = member.memberId,
                    username = member.memberName,
                    avatarUrl = avatarUrlForUid(member.memberId, member.avatar),
                )
            }
        }

    override suspend fun setFollowed(targetUid: Long, follow: Boolean): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            apiClientProvider().setFollow(targetUid = targetUid, follow = follow)
        }

    override suspend fun getConnections(following: Boolean): Result<List<FloorAuthor>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            apiClientProvider().listConnections(following = following).memberList.map { member ->
                FloorAuthor(
                    uid = member.memberId,
                    username = member.memberName,
                    avatarUrl = avatarUrlForUid(member.memberId, member.avatar),
                )
            }
        }

    override suspend fun getBlockedUsers(): Result<List<FloorAuthor>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val users = apiClientProvider().listBlockedUsers().data.map { blocked ->
                FloorAuthor(
                    uid = blocked.blockMemberId,
                    username = blocked.blockMemberName,
                    avatarUrl = avatarUrlForUid(blocked.blockMemberId),
                )
            }
            blockedUsersCache?.replaceAll(users)
            users
        }

    override suspend fun blockUserByName(username: String): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            apiClientProvider().blockUserByName(username)
            blockedUsersCache?.recordBlockedByName(username)
        }

    override suspend fun unblockUserById(targetUid: Long): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            apiClientProvider().unblockUserById(targetUid)
            blockedUsersCache?.removeByUid(targetUid)
        }

    companion object {
        private const val USER_CONTENT_PAGE_SIZE = 15
    }
}
