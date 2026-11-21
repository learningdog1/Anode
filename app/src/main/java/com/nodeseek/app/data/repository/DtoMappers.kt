package com.nodeseek.app.data.repository

import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import com.nodeseek.app.data.parser.CategoryDto
import com.nodeseek.app.data.parser.ConfigUserDto
import com.nodeseek.app.data.parser.FeedTopicDto
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.NodeSeekPostAccessException
import com.nodeseek.app.data.parser.ParsedThreadPageDto
import com.nodeseek.app.data.parser.PostCommentDto
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AuthorBadge
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import kotlinx.coroutines.CancellationException

internal val VALID_CATEGORY_SLUG_REGEX = Regex("^[a-zA-Z0-9_-]+$")

internal fun avatarUrlForUid(uid: Long?, explicitAvatar: String? = null): String {
    val raw = explicitAvatar?.trim()?.takeIf { it.isNotEmpty() }
        ?: uid?.takeIf { it > 0L }?.let { "/avatar/$it.png" }
        ?: return ""
    return NodeSeekUrlNormalizer.normalizeNodeSeekUrl(raw)
}

internal fun ConfigUserDto.toDomainSession(enableCustomedStyle: Boolean?): AccountSession {
    val canStyle = enableCustomedStyle == true &&
        (rank >= 2 || roles.any { it.name.equals("dev", ignoreCase = true) })
    return AccountSession(
        uid = memberId,
        username = memberName,
        avatarUrl = avatarUrlForUid(memberId),
        drumsticks = coin,
        stardust = stardust,
        topicCount = nPost,
        replyCount = nComment,
        bookmarkCount = collectionCount,
        permissionRank = rank,
        unreadNotificationCount = unViewedCount?.effectiveAll ?: 0,
        canCustomizeCss = canStyle,
        isTelegramBound = isTelegramBound,
    )
}

internal fun CategoryDto.toDomainCategoryOrNull(): Category? {
    val cleanSlug = key.trim()
    val cleanName = cnText.trim()
    if (cleanSlug.isEmpty() || cleanName.isEmpty() || !VALID_CATEGORY_SLUG_REGEX.matches(cleanSlug)) {
        return null
    }
    return Category(
        slug = cleanSlug,
        name = cleanName,
        isCustomizable = customizable ?: true,
        visibleOnHome = showInIndex ?: true,
        isAdminOnly = adminOnly ?: false,
        topicCoinCost = if (cleanSlug == "inside") 5 else 0,
        description = Category.defaultDescriptionFor(cleanSlug),
        supportsRss = cleanSlug != "inside",
    )
}

internal fun FeedTopicDto.toDomainSummary(
    readEntry: ReadHistoryEntry? = null,
    isBookmarked: Boolean = false,
): TopicSummary = TopicSummary(
    id = id,
    title = title,
    categorySlug = categoryKey,
    categoryName = categoryName,
    authorId = authorId,
    authorName = author,
    authorAvatarUrl = avatarUrl.ifBlank { avatarUrlForUid(authorId) },
    viewCount = views,
    replyCount = replies,
    lastActivityIso = activityDate,
    isPinned = pinned,
    requiredViewLevel = requiredRank,
    isRead = readEntry != null,
    lastReadFloor = readEntry?.lastReadFloor,
    isLocked = false,
    isBookmarked = isBookmarked,
)

internal fun PostCommentDto.toDomainFloorComment(topicOpUid: Long?): FloorComment {
    val posterUid = poster.resolvedUid
    val isOp = poster.isOp ?: (topicOpUid != null && topicOpUid > 0L && posterUid == topicOpUid)
    val rawSignatureCandidate = listOf(
        signatureMarkdown,
        signature,
        poster.signatureMarkdown,
        poster.signature,
    ).firstOrNull { !it.isNullOrBlank() }
    val resolvedSignature = NodeSeekHtmlParser.normalizeSignatureToMarkdown(rawSignatureCandidate)

    return FloorComment(
        commentId = commentId,
        floorNumber = floorIndex,
        author = FloorAuthor(
            uid = posterUid,
            username = poster.name,
            avatarUrl = avatarUrlForUid(posterUid, poster.avatar),
            isTopicAuthor = isOp,
            roleBadges = poster.roles.map { AuthorBadge(code = it.name, label = it.displayText) },
        ),
        createdAtIso = time?.createdDate.orEmpty(),
        editedAtIso = time?.editedDate?.takeIf { it.isNotBlank() },
        markdownBody = markdown,
        signatureMarkdown = resolvedSignature,
        upvoteCount = upvoteCount,
        drumstickCount = likeCount,
        dislikeCount = dislikeCount,
        hasUpvoted = upvoted == true,
        hasGivenDrumstick = liked == true,
        hasDisliked = disliked == true,
        isPinned = pined == true,
        isHot = hot == true,
        isBlocked = blocked,
    )
}

internal fun ParsedThreadPageDto.toDomainDetailPage(): TopicDetailPage {
    val opReplyPoster = replies.firstOrNull { it.poster.isOp == true }?.poster
    val opUid = postData.op?.resolvedUid?.takeIf { it > 0L }
        ?: openingComment?.poster?.resolvedUid?.takeIf { it > 0L }
        ?: opReplyPoster?.resolvedUid?.takeIf { it > 0L }
        ?: 0L
    val opName = postData.op?.name?.takeIf { it.isNotBlank() }
        ?: openingComment?.poster?.name?.takeIf { it.isNotBlank() }
        ?: opReplyPoster?.name.orEmpty()
    val opAvatar = avatarUrlForUid(
        uid = opUid,
        explicitAvatar = postData.op?.avatar ?: openingComment?.poster?.avatar ?: opReplyPoster?.avatar,
    )
    val opBadges = (openingComment?.poster?.roles ?: opReplyPoster?.roles)
        ?.map { AuthorBadge(code = it.name, label = it.displayText) }
        .orEmpty()

    val resolvedCategorySlug = postData.category.trim().takeIf { it.isNotEmpty() }
        ?: postData.categoryLink?.substringAfterLast('/', missingDelimiterValue = "")?.trim()?.takeIf { it.isNotEmpty() }
        ?: postData.categoryWord.trim().takeIf { it.isNotEmpty() }?.let { word ->
            Category.DEFAULT_CATEGORIES.firstOrNull { it.slug.isNotEmpty() && it.name.equals(word, ignoreCase = true) }?.slug
        }.orEmpty()
    val resolvedCategoryName = postData.categoryWord.trim().ifBlank {
        resolvedCategorySlug.takeIf { it.isNotEmpty() }?.let { slug ->
            Category.DEFAULT_CATEGORIES.firstOrNull { it.slug == slug }?.name
        }.orEmpty()
    }

    val domainOpening = openingComment?.toDomainFloorComment(opUid)
    val domainReplies = replies.map { it.toDomainFloorComment(opUid) }

    return TopicDetailPage(
        topicId = postData.postId,
        title = postData.title,
        categorySlug = resolvedCategorySlug,
        categoryName = resolvedCategoryName,
        author = FloorAuthor(
            uid = opUid,
            username = opName,
            avatarUrl = opAvatar,
            isTopicAuthor = true,
            roleBadges = opBadges,
        ),
        viewCount = postData.views,
        bookmarkCount = postData.collectionCount,
        currentPage = postData.postPage.coerceAtLeast(1),
        totalPages = postData.postPageCount.coerceAtLeast(1),
        requiredViewLevel = postData.rank,
        isLocked = postData.locked == true,
        isBookmarked = postData.collected,
        isCuratedAward = postData.award == true,
        openingFloor = domainOpening,
        replies = domainReplies,
        createdAtIso = domainOpening?.createdAtIso?.takeIf { it.isNotBlank() },
        isOfflineSnapshot = false,
    )
}

internal fun Throwable.mapToDomainThrowable(onChallengeDetected: () -> Unit): Throwable = when (this) {
    is CancellationException -> throw this
    is CloudflareChallengeException -> {
        onChallengeDetected()
        TopicAccessException.CloudflareChallengeRequired
    }
    is NodeSeekPostAccessException.SignInRequired -> TopicAccessException.SignInRequired
    is NodeSeekPostAccessException.PrivatePost -> TopicAccessException.PrivateTopic
    is NodeSeekPostAccessException.LevelRequired -> TopicAccessException.LevelRequired(minimumLevel)
    else -> this
}

internal suspend inline fun <T> runDomainCatching(
    crossinline onChallengeDetected: () -> Unit,
    crossinline block: suspend () -> T,
): Result<T> {
    return try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t.mapToDomainThrowable { onChallengeDetected() })
    }
}
