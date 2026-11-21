package com.nodeseek.app.data.network

import com.nodeseek.app.data.parser.ConfigPayloadDto
import com.nodeseek.app.data.parser.DirectoryItemDto
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.ParsedFeedPageDto
import com.nodeseek.app.data.parser.ParsedThreadPageDto
import com.nodeseek.app.data.parser.RssFeedParser
import com.nodeseek.app.data.parser.TempScriptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Complete protocol client for NodeSeek HTML documents, `/api/` JSON endpoints, RSS fallback,
 * and `api.nodeimage.com` image hosting.
 */
class NodeSeekApiClient(
    private val httpClient: OkHttpClient,
    private val baseOrigin: HttpUrl = NodeSeekUrlNormalizer.ORIGIN.toHttpUrl(),
    private val rssUrl: HttpUrl = NodeSeekUrlNormalizer.RSS_URL.toHttpUrl(),
    private val nodeImageOrigin: HttpUrl = NodeSeekUrlNormalizer.NODE_IMAGE_ORIGIN.toHttpUrl(),
    private val json: Json = TempScriptParser.json,
    private val sameOriginTransport: SameOriginTransport? = null,
    private val onConfigObserved: ((ConfigPayloadDto) -> Unit)? = null,
) {

    // Forum transport with automatic redirects disabled so custom CSRF / captcha / dynamic-sign
    // headers and session cookies are only ever forwarded after strict same-origin verification.
    private val forumHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // Cookie-less RSS fallback client with automatic redirects disabled.
    private val rssHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(45L, TimeUnit.SECONDS)
            .writeTimeout(45L, TimeUnit.SECONDS)
            .apply {
                interceptors().removeAll { it !is NodeSeekRequestInterceptor }
                networkInterceptors().clear()
            }
            .build()
    }

    // Isolated cookie-less, interceptor-free client for third-party `api.nodeimage.com` uploads
    // (mirrors SeekMailApiClient's credential boundary isolation).
    private val nodeImageHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(45L, TimeUnit.SECONDS)
            .writeTimeout(45L, TimeUnit.SECONDS)
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .build()
    }

    // =========================================================================
    // 1. HTML Document Endpoints (Feed, Curated, Search, Thread, Session, Directory)
    // =========================================================================

    suspend fun fetchSessionConfig(): ConfigPayloadDto {
        val url = NodeSeekUrlNormalizer.buildUrl(path = "/", baseOrigin = baseOrigin)
        val html = executeHtmlGet(url = url, checkingPostAccess = false, requireTempScript = true)
        val config = withContext(Dispatchers.Default) {
            TempScriptParser.extractFromHtml(html, baseUri = url.toString(), requireAllCategory = true)
        } ?: throw CloudflareChallengeException(url = url.toString())
        onConfigObserved?.invoke(config)
        return config
    }

    suspend fun fetchFeedPage(
        categorySlug: String = "",
        sortBy: String = "replyTime",
        page: Int = 1,
        includeBlocked: Boolean = false,
        bypassHttpCache: Boolean = true,
    ): ParsedFeedPageDto {
        val safePage = page.coerceAtLeast(1)
        val slug = categorySlug.trim()
        val path = when {
            slug.isEmpty() -> if (safePage == 1) "/" else "/page-$safePage"
            else -> if (safePage == 1) "/categories/$slug" else "/categories/$slug/page-$safePage"
        }
        val url = NodeSeekUrlNormalizer.buildUrl(
            path = path,
            queryParams = listOf("sortBy" to sortBy),
            baseOrigin = baseOrigin,
        )
        val html = executeHtmlGet(
            url = url,
            checkingPostAccess = false,
            requireTempScript = true,
            bypassHttpCache = bypassHttpCache,
        )
        return withContext(Dispatchers.Default) {
            NodeSeekHtmlParser.parseFeedPage(
                html = html,
                pageUrl = url.toString(),
                includeBlocked = includeBlocked,
            )
        }.also { parsed ->
            parsed.config?.let { onConfigObserved?.invoke(it) }
        }
    }

    suspend fun fetchCuratedFeedPage(
        page: Int = 1,
        includeBlocked: Boolean = false,
    ): ParsedFeedPageDto {
        val safePage = page.coerceAtLeast(1)
        val path = if (safePage == 1) "/award" else "/award/page-$safePage"
        val url = NodeSeekUrlNormalizer.buildUrl(path = path, baseOrigin = baseOrigin)
        val html = executeHtmlGet(url = url, checkingPostAccess = false, requireTempScript = true)
        return withContext(Dispatchers.Default) {
            NodeSeekHtmlParser.parseFeedPage(
                html = html,
                pageUrl = url.toString(),
                includeBlocked = includeBlocked,
            )
        }.also { parsed ->
            parsed.config?.let { onConfigObserved?.invoke(it) }
        }
    }

    suspend fun searchTopicsPage(
        query: String,
        categorySlug: String = "",
        sortBy: String = "replyTime",
        page: Int = 1,
        includeBlocked: Boolean = false,
    ): ParsedFeedPageDto {
        val safePage = page.coerceAtLeast(1)
        val params = buildList {
            add("q" to query)
            add("page" to safePage.toString())
            add("sortBy" to sortBy)
            if (categorySlug.isNotBlank()) {
                add("category" to categorySlug.trim())
            }
        }
        val url = NodeSeekUrlNormalizer.buildUrl(
            path = "/search",
            queryParams = params,
            baseOrigin = baseOrigin,
        )
        val html = executeHtmlGet(url = url, checkingPostAccess = false, requireTempScript = true)
        return withContext(Dispatchers.Default) {
            NodeSeekHtmlParser.parseFeedPage(
                html = html,
                pageUrl = url.toString(),
                includeBlocked = includeBlocked,
            )
        }.also { parsed ->
            parsed.config?.let { onConfigObserved?.invoke(it) }
        }
    }

    suspend fun fetchThreadPage(
        postId: Long,
        page: Int = 1,
        includeBlocked: Boolean = false,
    ): ParsedThreadPageDto {
        val safePage = page.coerceAtLeast(1)
        val url = NodeSeekUrlNormalizer.buildUrl(
            path = "/post-$postId-$safePage",
            baseOrigin = baseOrigin,
        )
        val html = executeHtmlGet(url = url, checkingPostAccess = true, requireTempScript = true)
        return withContext(Dispatchers.Default) {
            NodeSeekHtmlParser.parseThreadPage(
                html = html,
                pageUrl = url.toString(),
                includeBlocked = includeBlocked,
            )
        }.also { parsed ->
            onConfigObserved?.invoke(parsed.config)
        }
    }

    suspend fun fetchRssFeed(): ParsedFeedPageDto {
        val xmlBody = executeRssGetWithRedirects(rssUrl)
        return withContext(Dispatchers.Default) {
            RssFeedParser.parse(xmlBody)
        }
    }

    suspend fun fetchDirectory(providers: Boolean): List<DirectoryItemDto> {
        val path = if (providers) "/providers" else "/friends"
        val url = NodeSeekUrlNormalizer.buildUrl(path = path, baseOrigin = baseOrigin)
        val html = executeHtmlGet(url = url, checkingPostAccess = false, requireTempScript = false)
        return withContext(Dispatchers.Default) {
            NodeSeekHtmlParser.parseDirectoryPage(html = html, pageUrl = url.toString())
        }
    }

    suspend fun fetchStaticArticle(path: String): String {
        val url = NodeSeekUrlNormalizer.buildUrl(path = path, baseOrigin = baseOrigin)
        val html = executeHtmlGet(url = url, checkingPostAccess = false, requireTempScript = false)
        return withContext(Dispatchers.Default) {
            NodeSeekHtmlParser.parseStaticArticle(html = html, pageUrl = url.toString())
        }
    }

    // =========================================================================
    // 2. Authentication, 2FA & Account Security
    // =========================================================================

    suspend fun signIn(
        username: String,
        password: String,
        turnstileToken: String,
    ): SignInResponseDto {
        val headers = buildMap {
            put("Referer", "${NodeSeekUrlNormalizer.ORIGIN}/signIn.html")
            if (turnstileToken.isNotBlank()) {
                put("x-captcha-token", turnstileToken)
                put("x-captcha-source", "turnstile")
            }
        }
        return executeJsonPost(
            path = "/api/account/signIn",
            requestBodyDto = SignInRequestDto(username = username, password = password),
            requestSerializer = SignInRequestDto.serializer(),
            responseSerializer = SignInResponseDto.serializer(),
            extraHeaders = headers,
            requireSuccessFlag = false,
        )
    }

    suspend fun verifyTwoFactor(
        otpCode: String,
        otpSession: String,
    ): SignInResponseDto = executeJsonPost(
        path = "/api/account/signIn",
        requestBodyDto = TwoFactorSignInRequestDto(otpCode = otpCode, otpSession = otpSession),
        requestSerializer = TwoFactorSignInRequestDto.serializer(),
        responseSerializer = SignInResponseDto.serializer(),
        extraHeaders = mapOf("Referer" to "${NodeSeekUrlNormalizer.ORIGIN}/signIn.html"),
        requireSuccessFlag = false,
    )

    fun clearTransportSessionContext() {
        sameOriginTransport?.clearSessionContext()
    }

    suspend fun signOut() {
        val url = NodeSeekUrlNormalizer.buildUrl(path = "/api/account/signOut", baseOrigin = baseOrigin)
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .header("Accept", "application/json")
            .header("x-csrf-challenge", "simple-token")
            .build()
        try {
            try {
                executeForumCall(request) { status, headers, body ->
                    CloudflareChallengeDetector.inspectApiResponse(status, headers, body, url = url.toString())
                    validateSignOutResponse(status, body)
                }
            } catch (err: IOException) {
                val fallback = sameOriginTransport
                val shouldFallback = fallback != null &&
                    fallback.canAttemptWithExistingClearance() &&
                    (err is CloudflareChallengeException ||
                        (err is NodeSeekApiException && (err.statusCode == 403 || err.isHighRiskAction)))
                if (!shouldFallback) {
                    throw err
                }
                val fallbackHeaders = buildSameOriginPostHeaders(
                    path = "/api/account/signOut",
                    extraHeaders = emptyMap(),
                    hasJsonBody = false,
                )
                val resp = fallback!!.executeApiRequest(
                    method = "POST",
                    url = url.toString(),
                    refererUrl = "${NodeSeekUrlNormalizer.ORIGIN}/",
                    headers = fallbackHeaders,
                    body = null,
                )
                CloudflareChallengeDetector.inspectApiResponse(
                    statusCode = resp.statusCode,
                    headers = resp.headers,
                    rawBody = resp.body,
                    contentType = resp.contentType,
                    url = url.toString(),
                )
                validateSignOutResponse(resp.statusCode, resp.body)
            }
        } finally {
            sameOriginTransport?.clearSessionContext()
        }
    }

    private fun validateSignOutResponse(status: Int, body: String) {
        val trimmed = body.trim()
        val jsonObj = if (trimmed.isNotEmpty()) {
            runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        } else {
            null
        }
        val serverMessage = (jsonObj?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val successElement = jsonObj?.get("success") as? JsonPrimitive
        val isExplicitFailure = successElement != null && (
            successElement.booleanOrNull == false ||
                successElement.contentOrNull == "0" ||
                successElement.contentOrNull?.equals("false", ignoreCase = true) == true
            )
        if (status !in 200..299 || isExplicitFailure) {
            throw NodeSeekApiException(
                message = serverMessage ?: "退出登录失败 ($status)",
                statusCode = status,
                serverMessage = serverMessage,
            )
        }
    }

    suspend fun getOtpStatus(): OtpStatusResponseDto =
        executeJsonGet("/api/account/otp-status", OtpStatusResponseDto.serializer())

    suspend fun updateOtp(enable: Boolean, password: String): OtpActionResponseDto =
        executeJsonPost(
            path = "/api/account/otp",
            requestBodyDto = OtpActionRequestDto(action = if (enable) "create" else "remove", password = password),
            requestSerializer = OtpActionRequestDto.serializer(),
            responseSerializer = OtpActionResponseDto.serializer(),
        )

    suspend fun getSignInHistory(): SignInHistoryResponseDto =
        executeJsonGet("/api/account/signIn-history", SignInHistoryResponseDto.serializer())

    suspend fun changePassword(oldPassword: String, newPassword: String): BasicApiResponseDto =
        executeJsonPost(
            path = "/api/account/changePassword",
            requestBodyDto = ChangePasswordRequestDto(oldPassword = oldPassword, password = newPassword),
            requestSerializer = ChangePasswordRequestDto.serializer(),
            responseSerializer = BasicApiResponseDto.serializer(),
        )

    suspend fun uploadAvatar(pngBytes: ByteArray): BasicApiResponseDto {
        val url = NodeSeekUrlNormalizer.buildUrl(path = "/api/avatar/upload", baseOrigin = baseOrigin)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("token", "123456798")
            .addFormDataPart("name", "avatar")
            .addFormDataPart("img", "img.png", pngBytes.toRequestBody("image/png".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(url)
            .post(multipart)
            .header("x-csrf-challenge", "simple-token")
            .build()
        val raw = executeForumCall(request) { status, headers, body ->
            CloudflareChallengeDetector.inspectApiResponse(status, headers, body, url = url.toString())
            ensureApiSuccess(status, body)
        }
        return json.decodeFromString(BasicApiResponseDto.serializer(), raw)
    }

    suspend fun unbindTelegram(): BasicApiResponseDto =
        executeEmptyPost("/api/account/unbind-telegram", BasicApiResponseDto.serializer())

    // =========================================================================
    // 3. Categories & Homepage Preferences
    // =========================================================================

    suspend fun listCategories(): CategoryListResponseDto =
        executeJsonGet("/api/content/list-categories", CategoryListResponseDto.serializer())

    suspend fun getHomepageCategories(): HomepageCategoryResponseDto =
        executeJsonGet("/api/homepage", HomepageCategoryResponseDto.serializer())

    suspend fun saveHomepageCategories(items: List<HomepageCategoryPrefDto>): BasicApiResponseDto =
        executeJsonPost(
            path = "/api/homepage",
            requestBodyDto = SaveHomepageRequestDto(data = items),
            requestSerializer = SaveHomepageRequestDto.serializer(),
            responseSerializer = BasicApiResponseDto.serializer(),
        )

    // =========================================================================
    // 4. Daily Check-In (鸡腿) & Daily Quota Progress
    // =========================================================================

    suspend fun getAttendanceBoard(page: Int = 1): AttendanceBoardResponseDto =
        executeJsonGet(
            path = "/api/attendance/board",
            responseSerializer = AttendanceBoardResponseDto.serializer(),
            queryParams = listOf("page" to page.coerceAtLeast(1).toString()),
            extraHeaders = mapOf(
                "Referer" to "${NodeSeekUrlNormalizer.ORIGIN}/board",
                "Pragma" to "no-cache",
            ),
        )

    suspend fun checkIn(random: Boolean): AttendanceActionResponseDto =
        executeEmptyPost(
            path = "/api/attendance",
            responseSerializer = AttendanceActionResponseDto.serializer(),
            queryParams = listOf("random" to random.toString()),
            extraHeaders = mapOf("Referer" to "${NodeSeekUrlNormalizer.ORIGIN}/board"),
        )

    suspend fun getDailyProgress(): DailyProgressResponseDto =
        executeJsonGet("/api/progress/today", DailyProgressResponseDto.serializer())

    suspend fun getFreeLikeQuota(): FreeLikeQuotaResponseDto =
        executeJsonGet(
            path = "/api/progress/today",
            responseSerializer = FreeLikeQuotaResponseDto.serializer(),
            queryParams = listOf("scope" to "freelike"),
        )

    // =========================================================================
    // 5. Comment Reactions & Collections (Bookmarks)
    // =========================================================================

    /**
     * @param reactionSegment One of `"upvote"` (点赞), `"like"` (加鸡腿), or `"dislike"` (反对).
     */
    suspend fun reactToComment(
        commentId: Long,
        reactionSegment: String,
        postId: Long? = null,
    ): ReactionActionResponseDto {
        require(reactionSegment in VALID_REACTIONS) { "Invalid reaction segment: $reactionSegment" }
        return executeJsonPost(
            path = "/api/statistics/$reactionSegment",
            requestBodyDto = CommentReactionRequestDto(commentId = commentId, action = "add"),
            requestSerializer = CommentReactionRequestDto.serializer(),
            responseSerializer = ReactionActionResponseDto.serializer(),
            extraHeaders = postRefererHeaders(postId),
        )
    }

    suspend fun setCollection(postId: Long, collected: Boolean): BasicApiResponseDto =
        executeJsonPost(
            path = "/api/statistics/collection",
            requestBodyDto = CollectionActionRequestDto(
                postId = postId,
                action = if (collected) "add" else "remove",
            ),
            requestSerializer = CollectionActionRequestDto.serializer(),
            responseSerializer = BasicApiResponseDto.serializer(),
            extraHeaders = postRefererHeaders(postId),
        )

    suspend fun listCollections(page: Int = 1): CollectionListResponseDto =
        executeJsonGet(
            path = "/api/statistics/list-collection",
            responseSerializer = CollectionListResponseDto.serializer(),
            queryParams = listOf("page" to page.coerceAtLeast(1).toString()),
        )

    // =========================================================================
    // 6. Publishing, Editing & Pinning Topics / Comments
    // =========================================================================

    suspend fun createDiscussion(
        title: String,
        markdownContent: String,
        categorySlug: String,
        requiredRank: Int = 0,
    ): BasicApiResponseDto = executeJsonPost(
        path = "/api/content/new-discussion",
        requestBodyDto = NewDiscussionRequestDto(
            title = title,
            content = markdownContent,
            category = categorySlug,
            rank = requiredRank,
        ),
        requestSerializer = NewDiscussionRequestDto.serializer(),
        responseSerializer = BasicApiResponseDto.serializer(),
        extraHeaders = mapOf("Referer" to "${NodeSeekUrlNormalizer.ORIGIN}/new-discussion"),
    )

    /**
     * Note: NodeSeek's `edit-discussion` endpoint omits `category`.
     */
    suspend fun editDiscussion(
        postId: Long,
        title: String,
        markdownContent: String,
        requiredRank: Int = 0,
    ): BasicApiResponseDto = executeJsonPost(
        path = "/api/content/edit-discussion",
        requestBodyDto = EditDiscussionRequestDto(
            postId = postId,
            title = title,
            content = markdownContent,
            rank = requiredRank,
        ),
        requestSerializer = EditDiscussionRequestDto.serializer(),
        responseSerializer = BasicApiResponseDto.serializer(),
        extraHeaders = postRefererHeaders(postId),
    )

    suspend fun createComment(
        postId: Long,
        markdownContent: String,
    ): ReactionActionResponseDto = executeJsonPost(
        path = "/api/content/new-comment",
        requestBodyDto = NewCommentRequestDto(postId = postId, content = markdownContent),
        requestSerializer = NewCommentRequestDto.serializer(),
        responseSerializer = ReactionActionResponseDto.serializer(),
        extraHeaders = postRefererHeaders(postId),
    )

    suspend fun editComment(
        commentId: Long,
        markdownContent: String,
        postId: Long? = null,
    ): BasicApiResponseDto = executeJsonPost(
        path = "/api/content/edit-comment",
        requestBodyDto = EditCommentRequestDto(commentId = commentId, content = markdownContent),
        requestSerializer = EditCommentRequestDto.serializer(),
        responseSerializer = BasicApiResponseDto.serializer(),
        extraHeaders = postRefererHeaders(postId),
    )

    suspend fun setCommentPinned(
        commentId: Long,
        pinned: Boolean,
        postId: Long? = null,
    ): BasicApiResponseDto = executeJsonPost(
        path = "/api/content/pin-comment",
        requestBodyDto = PinCommentRequestDto(
            commentId = commentId,
            action = if (pinned) "add" else "remove",
        ),
        requestSerializer = PinCommentRequestDto.serializer(),
        responseSerializer = BasicApiResponseDto.serializer(),
        extraHeaders = postRefererHeaders(postId),
    )

    // =========================================================================
    // 7. Notifications & Direct Messages
    // =========================================================================

    suspend fun getUnreadNotificationCount(): Int =
        executeJsonGet(
            path = "/api/notification/unread-count",
            responseSerializer = UnreadNotificationCountResponseDto.serializer(),
        ).unreadCount.effectiveAll()

    suspend fun listReplyNotifications(): NoticeListResponseDto =
        executeJsonGet("/api/notification/reply-to-me/list", NoticeListResponseDto.serializer())

    suspend fun listMentionNotifications(): NoticeListResponseDto =
        executeJsonGet("/api/notification/at-me/list", NoticeListResponseDto.serializer())

    /**
     * Marks reply notifications read. When [ids] is `null`, appends `?all=true` with an empty body.
     * Otherwise posts `{"replys": [...]}` (exact NodeSeek spelling `replys`).
     */
    suspend fun markReplyNotificationsViewed(ids: List<Long>? = null): BasicApiResponseDto =
        markViewedInternal(
            path = "/api/notification/reply-to-me/markViewed",
            fieldKey = "replys",
            ids = ids,
        )

    /**
     * Marks @mention notifications read (`?all=true` when [ids] is `null`, else `{"atMe": [...]}`).
     */
    suspend fun markMentionNotificationsViewed(ids: List<Long>? = null): BasicApiResponseDto =
        markViewedInternal(
            path = "/api/notification/at-me/markViewed",
            fieldKey = "atMe",
            ids = ids,
        )

    suspend fun listDirectMessages(): MessageListResponseDto =
        executeJsonGet("/api/notification/message/list", MessageListResponseDto.serializer())

    suspend fun getConversation(peerUid: Long): ConversationResponseDto =
        executeJsonGet("/api/notification/message/with/$peerUid", ConversationResponseDto.serializer())

    /**
     * Marks direct messages read (`?all=true` when [ids] is `null`, else `{"messages": [...]}`).
     */
    suspend fun markDirectMessagesViewed(ids: List<Long>? = null): BasicApiResponseDto =
        markViewedInternal(
            path = "/api/notification/message/markViewed",
            fieldKey = "messages",
            ids = ids,
        )

    suspend fun sendDirectMessage(
        receiverUid: Long,
        content: String,
        isMarkdown: Boolean = false,
    ): BasicApiResponseDto = executeJsonPost(
        path = "/api/notification/message/send",
        requestBodyDto = SendDirectMessageRequestDto(
            receiverUid = receiverUid,
            content = content,
            markdown = isMarkdown,
        ),
        requestSerializer = SendDirectMessageRequestDto.serializer(),
        responseSerializer = BasicApiResponseDto.serializer(),
    )

    private suspend fun markViewedInternal(
        path: String,
        fieldKey: String,
        ids: List<Long>?,
    ): BasicApiResponseDto {
        return if (ids == null) {
            executeEmptyPost(
                path = path,
                responseSerializer = BasicApiResponseDto.serializer(),
                queryParams = listOf("all" to "true"),
            )
        } else {
            val mapSerializer = MapSerializer(String.serializer(), ListSerializer(Long.serializer()))
            executeJsonPost(
                path = path,
                requestBodyDto = mapOf(fieldKey to ids),
                requestSerializer = mapSerializer,
                responseSerializer = BasicApiResponseDto.serializer(),
            )
        }
    }

    // =========================================================================
    // 8. User Profiles, User Topics/Comments, Search, Follows, Blocks, Economy & Polls
    // =========================================================================

    suspend fun getUserProfile(
        uid: Long,
        includeReadme: Boolean = true,
        includeEditableSignature: Boolean = false,
    ): UserProfileResponseDto {
        val params = buildList {
            if (includeReadme) add("readme" to "1")
            if (includeEditableSignature) add("signature" to "1")
        }
        val referer = if (includeEditableSignature) {
            "${NodeSeekUrlNormalizer.ORIGIN}/setting"
        } else {
            "${NodeSeekUrlNormalizer.ORIGIN}/space/$uid"
        }
        return executeJsonGet(
            path = "/api/account/getInfo/$uid",
            responseSerializer = UserProfileResponseDto.serializer(),
            queryParams = params,
            extraHeaders = mapOf("Referer" to referer),
        )
    }

    suspend fun updateIntroduction(
        bio: String,
        signature: String,
        readme: String,
    ): BasicApiResponseDto = executeJsonPost(
        path = "/api/account/introduction",
        requestBodyDto = UpdateIntroductionRequestDto(bio = bio, signature = signature, readme = readme),
        requestSerializer = UpdateIntroductionRequestDto.serializer(),
        responseSerializer = BasicApiResponseDto.serializer(),
    )

    suspend fun listUserDiscussions(uid: Long, page: Int = 1): UserDiscussionsResponseDto =
        executeJsonGet(
            path = "/api/content/list-discussions",
            responseSerializer = UserDiscussionsResponseDto.serializer(),
            queryParams = listOf(
                "uid" to uid.toString(),
                "page" to page.coerceAtLeast(1).toString(),
            ),
            extraHeaders = mapOf("Referer" to "${NodeSeekUrlNormalizer.ORIGIN}/space/$uid#/discussions"),
        )

    suspend fun listUserComments(uid: Long, page: Int = 1): UserCommentsResponseDto =
        executeJsonGet(
            path = "/api/content/list-comments",
            responseSerializer = UserCommentsResponseDto.serializer(),
            queryParams = listOf(
                "uid" to uid.toString(),
                "page" to page.coerceAtLeast(1).toString(),
            ),
            extraHeaders = mapOf("Referer" to "${NodeSeekUrlNormalizer.ORIGIN}/space/$uid#/comments"),
        )

    suspend fun searchUsers(keyword: String): MemberListResponseDto {
        val encodedSegment = URLEncoder.encode(keyword.trim(), Charsets.UTF_8.name())
            .replace("+", "%20")
        return executeJsonGet(
            path = "/api/account/find/$encodedSegment",
            responseSerializer = MemberListResponseDto.serializer(),
        )
    }

    suspend fun setFollow(targetUid: Long, follow: Boolean): BasicApiResponseDto =
        executeJsonPost(
            path = if (follow) "/api/fans/add" else "/api/fans/del",
            requestBodyDto = FollowActionRequestDto(followedMemberId = targetUid),
            requestSerializer = FollowActionRequestDto.serializer(),
            responseSerializer = BasicApiResponseDto.serializer(),
        )

    /**
     * Note: NodeSeek uses singular `/api/fans/follow` for following and plural `/api/fans/fans` for followers.
     */
    suspend fun listConnections(following: Boolean): MemberListResponseDto =
        executeJsonGet(
            path = if (following) "/api/fans/follow" else "/api/fans/fans",
            responseSerializer = MemberListResponseDto.serializer(),
        )

    suspend fun listBlockedUsers(): BlockListResponseDto =
        executeJsonGet("/api/block-list/list", BlockListResponseDto.serializer())

    suspend fun blockUserByName(username: String): BasicApiResponseDto =
        executeJsonPost(
            path = "/api/block-list/add",
            requestBodyDto = BlockByNameRequestDto(blockMemberName = username.trim()),
            requestSerializer = BlockByNameRequestDto.serializer(),
            responseSerializer = BasicApiResponseDto.serializer(),
        )

    suspend fun unblockUserById(memberId: Long): BasicApiResponseDto =
        executeJsonPost(
            path = "/api/block-list/del",
            requestBodyDto = UnblockByIdRequestDto(blockMemberId = memberId),
            requestSerializer = UnblockByIdRequestDto.serializer(),
            responseSerializer = BasicApiResponseDto.serializer(),
        )

    suspend fun getCreditLedger(page: Int = 1): CreditLedgerResponseDto =
        executeJsonGet(
            path = "/api/account/credit/page-${page.coerceAtLeast(1)}",
            responseSerializer = CreditLedgerResponseDto.serializer(),
        )

    suspend fun listStardust(
        memberId: Long,
        count: Int = 10,
        beforeId: Long? = null,
        type: String? = null,
        refId: String? = null,
        peerId: String? = null,
    ): StardustListResponseDto {
        val params = buildList {
            add("member_id" to memberId.toString())
            add("count" to count.toString())
            if (!type.isNullOrBlank()) add("type" to type)
            if (!refId.isNullOrBlank()) add("ref_id" to refId)
            if (!peerId.isNullOrBlank()) add("peer_id" to peerId)
            if (beforeId != null) add("before_id" to beforeId.toString())
        }
        return executeJsonGet(
            path = "/api/stardust/list",
            responseSerializer = StardustListResponseDto.serializer(),
            queryParams = params,
        )
    }

    suspend fun prepareStardustTransfer(receiverId: Long): StardustPrepareResponseDto =
        executeJsonPost(
            path = "/api/stardust/payment-prepare",
            requestBodyDto = StardustPrepareRequestDto(receiverId = receiverId),
            requestSerializer = StardustPrepareRequestDto.serializer(),
            responseSerializer = StardustPrepareResponseDto.serializer(),
        )

    suspend fun sendStardust(memberId: Long, amount: Int, refId: Long = 0L): BasicApiResponseDto =
        executeJsonPost(
            path = "/api/stardust/send",
            requestBodyDto = StardustSendRequestDto(memberId = memberId, diff = amount, refId = refId),
            requestSerializer = StardustSendRequestDto.serializer(),
            responseSerializer = BasicApiResponseDto.serializer(),
        )

    suspend fun getVote(voteId: Long): VoteResponseDto =
        executeJsonGet("/api/vote/info/$voteId", VoteResponseDto.serializer())

    suspend fun createVote(
        title: String,
        multiple: Boolean,
        isPublic: Boolean,
        options: List<String>,
    ): VoteResponseDto = executeJsonPost(
        path = "/api/vote/info",
        requestBodyDto = CreateVoteRequestDto(
            title = title,
            multiple = multiple,
            isPublic = isPublic,
            items = options,
        ),
        requestSerializer = CreateVoteRequestDto.serializer(),
        responseSerializer = VoteResponseDto.serializer(),
    )

    suspend fun castVote(voteItemIds: List<Long>): BasicApiResponseDto =
        executeJsonPost(
            path = "/api/vote/voteforitem",
            requestBodyDto = CastVoteRequestDto(ids = voteItemIds),
            requestSerializer = CastVoteRequestDto.serializer(),
            responseSerializer = BasicApiResponseDto.serializer(),
        )

    suspend fun lockVote(voteId: Long): BasicApiResponseDto =
        executeJsonPost(
            path = "/api/vote/lock/$voteId",
            requestBodyDto = LockVoteRequestDto(locked = true),
            requestSerializer = LockVoteRequestDto.serializer(),
            responseSerializer = BasicApiResponseDto.serializer(),
        )

    // =========================================================================
    // 9. NodeImage Official Image Hosting (`https://api.nodeimage.com`)
    // =========================================================================

    suspend fun uploadImageToNodeImage(
        imageBytes: ByteArray,
        apiKey: String,
        mimeType: String = "image/png",
        deleteAfterDays: Int? = null,
        fileName: String? = null,
    ): String {
        if (imageBytes.isEmpty()) {
            throw NodeSeekApiException("所选图片内容为空，请重新选择。")
        }
        val trimmedKey = apiKey.trim()
        if (!NODE_IMAGE_KEY_REGEX.matches(trimmedKey)) {
            throw NodeSeekApiException("NodeImage API Key 格式无效，请检查设置中的 32–128 位十六进制密钥。")
        }
        if (hasUserInfo(nodeImageOrigin) || !isAllowedCredentialOrigin(nodeImageOrigin)) {
            throw NodeSeekApiException(
                "安全校验失败：NodeImage 图床上传必须使用 HTTPS 加密连接，拒绝通过明文 HTTP 发送 API Key。",
            )
        }

        val uploadUrl = nodeImageOrigin.resolve("/api/upload")
            ?: throw NodeSeekApiException("安全校验失败：NodeImage 图床地址无效。")
        if (hasUserInfo(uploadUrl) || !isAllowedCredentialOrigin(uploadUrl) || !isSameOrigin(nodeImageOrigin, uploadUrl)) {
            throw NodeSeekApiException(
                "安全校验失败：禁止向非预期源站发送 NodeImage API Key。",
            )
        }

        val (normalizedMime, canonicalExt) = resolveUploadMimeAndExtension(mimeType = mimeType, fileName = fileName)
        val resolvedFileName = buildSafeUploadFileName(rawFileName = fileName, extension = canonicalExt)

        fun buildMultipartBody(): MultipartBody {
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", resolvedFileName, imageBytes.toRequestBody(normalizedMime.toMediaType()))
            if (deleteAfterDays != null && deleteAfterDays in 1..365) {
                multipartBuilder.addFormDataPart("deleteDays", deleteAfterDays.toString())
            }
            return multipartBuilder.build()
        }

        val rawBody = executeNodeImagePostWithRedirects(
            initialUrl = uploadUrl,
            expectedOrigin = nodeImageOrigin,
            apiKey = trimmedKey,
            bodyFactory = ::buildMultipartBody,
        )

        val parsed = json.decodeFromString(NodeImageUploadResponseDto.serializer(), rawBody)
        val rawDirectUrl = parsed.resolvedDirectUrl
            ?: throw NodeSeekApiException("图床未返回可用的直链地址。")
        return normalizeUploadedImageUrl(rawDirectUrl)
    }

    private suspend fun executeNodeImagePostWithRedirects(
        initialUrl: HttpUrl,
        expectedOrigin: HttpUrl,
        apiKey: String,
        bodyFactory: () -> RequestBody,
    ): String {
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECT_HOPS) {
            if (hasUserInfo(currentUrl) ||
                !isAllowedCredentialOrigin(currentUrl) ||
                !isSameOrigin(expectedOrigin, currentUrl)
            ) {
                throw NodeSeekApiException(
                    "安全校验失败：禁止向非同源或非加密地址发送 NodeImage API Key。",
                )
            }

            val request = Request.Builder()
                .url(currentUrl)
                .post(bodyFactory())
                .header("Accept", "application/json")
                .header("X-API-Key", apiKey)
                .build()

            val raw = nodeImageHttpClient.newCall(request).awaitRawResponse()
            val status = raw.code

            if (status in REDIRECT_STATUS_CODES) {
                if (hop >= MAX_REDIRECT_HOPS) {
                    throw NodeSeekApiException(
                        message = "安全校验失败：图床重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）。",
                        statusCode = status,
                    )
                }
                val rawLocation = raw.headers["Location"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw NodeSeekApiException(
                        message = "安全校验失败：图床重定向响应缺少 Location 头 ($status)。",
                        statusCode = status,
                    )
                val nextUrl = currentUrl.resolve(rawLocation)
                    ?: throw NodeSeekApiException(
                        message = "安全校验失败：图床重定向目标地址无效或协议不受支持 ($status)。",
                        statusCode = status,
                    )
                if (hasUserInfo(nextUrl)) {
                    throw NodeSeekApiException(
                        message = "安全校验失败：拒绝重定向至包含内嵌用户信息的图床地址 ($status)。",
                        statusCode = status,
                    )
                }
                if (currentUrl.scheme.equals("https", ignoreCase = true) &&
                    !nextUrl.scheme.equals("https", ignoreCase = true)
                ) {
                    throw NodeSeekApiException(
                        message = "安全校验失败：拒绝将图床上传请求从 HTTPS 降级重定向至明文 HTTP ($status)。",
                        statusCode = status,
                    )
                }
                if (!isAllowedCredentialOrigin(nextUrl) || !isSameOrigin(expectedOrigin, nextUrl)) {
                    throw NodeSeekApiException(
                        message = "安全校验失败：拒绝跨源重定向图床上传请求，已阻止泄露 API Key ($status)。",
                        statusCode = status,
                    )
                }
                if (status != 307 && status != 308) {
                    throw NodeSeekApiException(
                        message = "图床上传遇到改变请求方法的重定向 ($status)，仅允许同源 307/308 重定向。",
                        statusCode = status,
                    )
                }
                currentUrl = nextUrl
                continue
            }

            if (status in 300..399) {
                throw NodeSeekApiException(
                    message = "安全校验失败：图床返回不受支持的重定向状态 ($status)。",
                    statusCode = status,
                )
            }

            val body = raw.body
            val parsedError = runCatching {
                json.decodeFromString(NodeImageUploadResponseDto.serializer(), body)
            }.getOrNull()
            if (status !in 200..299 || parsedError?.success == false) {
                val msg = parsedError?.message?.takeIf { it.isNotBlank() }
                    ?: if (status == 401 || status == 403) {
                        "图床密钥无效或没有权限，请检查 API Key。"
                    } else {
                        parsedError?.error?.takeIf { it.isNotBlank() } ?: "图床请求失败 ($status)。"
                    }
                throw NodeSeekApiException(msg, statusCode = status, serverMessage = msg)
            }
            return body
        }
        throw NodeSeekApiException("安全校验失败：图床重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）。")
    }

    private fun normalizeUploadedImageUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val absUrl = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> nodeImageOrigin.resolve(trimmed)?.toString() ?: trimmed
            !trimmed.contains("://") -> nodeImageOrigin.resolve("/$trimmed")?.toString() ?: trimmed
            else -> trimmed
        }
        val parsedUrl = absUrl.toHttpUrlOrNull() ?: return absUrl
        val hostLower = parsedUrl.host.lowercase()
        val isLocalTestHost = hostLower == "localhost" || hostLower == "127.0.0.1" || hostLower == "::1"
        return if (parsedUrl.scheme.equals("http", ignoreCase = true) && !isLocalTestHost) {
            parsedUrl.newBuilder().scheme("https").build().toString()
        } else {
            absUrl
        }
    }

    private fun resolveUploadMimeAndExtension(
        mimeType: String,
        fileName: String?,
    ): Pair<String, String> {
        val lowerMime = mimeType.substringBefore(';').trim().lowercase()
        val rawExt = fileName?.substringAfterLast('.', "")?.trim()?.lowercase().orEmpty()
        return when {
            lowerMime == "image/jpeg" || lowerMime == "image/jpg" || lowerMime == "image/pjpeg" ->
                "image/jpeg" to "jpg"
            lowerMime == "image/png" ->
                "image/png" to "png"
            lowerMime == "image/webp" ->
                "image/webp" to "webp"
            lowerMime == "image/gif" ->
                "image/gif" to "gif"
            lowerMime == "image/bmp" || lowerMime == "image/x-ms-bmp" ->
                "image/bmp" to "bmp"
            lowerMime == "image/avif" ->
                "image/avif" to "avif"
            lowerMime == "image/heic" || lowerMime == "image/heic-sequence" ->
                "image/heic" to "heic"
            lowerMime == "image/heif" || lowerMime == "image/heif-sequence" ->
                "image/heif" to "heif"
            lowerMime == "image/svg+xml" ->
                "image/svg+xml" to "svg"
            rawExt == "jpg" || rawExt == "jpeg" -> "image/jpeg" to "jpg"
            rawExt == "webp" -> "image/webp" to "webp"
            rawExt == "gif" -> "image/gif" to "gif"
            rawExt == "bmp" -> "image/bmp" to "bmp"
            rawExt == "avif" -> "image/avif" to "avif"
            rawExt == "heic" -> "image/heic" to "heic"
            rawExt == "heif" -> "image/heif" to "heif"
            rawExt == "svg" -> "image/svg+xml" to "svg"
            else -> "image/png" to "png"
        }
    }

    private fun buildSafeUploadFileName(rawFileName: String?, extension: String): String {
        val leaf = rawFileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        val stemRaw = if (leaf.contains('.')) leaf.substringBeforeLast('.') else leaf
        val sanitizedStem = stemRaw
            .replace(Regex("""[^a-zA-Z0-9_-]+"""), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "image" }
        return "$sanitizedStem.$extension"
    }

    // =========================================================================
    // Internal Transport Helpers
    // =========================================================================

    private fun postRefererHeaders(postId: Long?): Map<String, String> =
        if (postId != null && postId > 0L) {
            mapOf("Referer" to "${NodeSeekUrlNormalizer.ORIGIN}/post-$postId-1")
        } else {
            emptyMap()
        }

    private fun validateHtmlResponse(
        status: Int,
        headers: okhttp3.Headers,
        body: String,
        urlString: String,
        checkingPostAccess: Boolean,
        requireTempScript: Boolean,
    ) {
        if (status == 429 && !CloudflareChallengeDetector.hasChallengeMitigationHeader(headers)) {
            throw NodeSeekApiException(
                message = "请求过于频繁，请稍后再试",
                statusCode = 429,
                retryAfterSeconds = parseRetryAfterSeconds(headers),
            )
        }
        CloudflareChallengeDetector.inspectHtmlResponse(
            statusCode = status,
            headers = headers,
            htmlBody = body,
            url = urlString,
            checkingPostAccess = checkingPostAccess,
            requireTempScript = requireTempScript,
        )
        if (status !in 200..299 && !checkingPostAccess) {
            throw NodeSeekApiException("官网请求失败 ($status)", statusCode = status)
        }
    }

    private suspend fun executeHtmlGet(
        url: HttpUrl,
        checkingPostAccess: Boolean,
        requireTempScript: Boolean,
        bypassHttpCache: Boolean = true,
    ): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        if (bypassHttpCache) {
            reqBuilder
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .header("Pragma", "no-cache")
        }
        val request = reqBuilder.build()

        return try {
            executeForumCall(request) { status, headers, body ->
                validateHtmlResponse(
                    status = status,
                    headers = headers,
                    body = body,
                    urlString = url.toString(),
                    checkingPostAccess = checkingPostAccess,
                    requireTempScript = requireTempScript,
                )
            }
        } catch (challengeError: CloudflareChallengeException) {
            val fallback = sameOriginTransport
            if (fallback == null || !fallback.canAttemptWithExistingClearance()) {
                throw challengeError
            }
            val resp = fallback.fetchHtml(url.toString())
            validateHtmlResponse(
                status = resp.statusCode,
                headers = resp.headers,
                body = resp.body,
                urlString = url.toString(),
                checkingPostAccess = checkingPostAccess,
                requireTempScript = requireTempScript,
            )
            resp.body
        }
    }

    private suspend fun <R> executeJsonGet(
        path: String,
        responseSerializer: KSerializer<R>,
        queryParams: List<Pair<String, String?>> = emptyList(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): R {
        val url = NodeSeekUrlNormalizer.buildUrl(
            path = path,
            queryParams = queryParams,
            baseOrigin = baseOrigin,
        )
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
        for ((k, v) in extraHeaders) {
            builder.header(k, v)
        }
        val request = builder.build()

        val raw = try {
            executeForumCall(request) { status, headers, body ->
                CloudflareChallengeDetector.inspectApiResponse(status, headers, body, url = url.toString())
                ensureApiSuccess(status, body, retryAfterSeconds = parseRetryAfterSeconds(headers))
            }
        } catch (challengeError: CloudflareChallengeException) {
            val fallback = sameOriginTransport
            if (fallback == null || !fallback.canAttemptWithExistingClearance()) {
                throw challengeError
            }
            val refererUrl = extraHeaders["Referer"] ?: "${NodeSeekUrlNormalizer.ORIGIN}/"
            val resp = fallback.executeApiRequest(
                method = "GET",
                url = url.toString(),
                refererUrl = refererUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
            )
            CloudflareChallengeDetector.inspectApiResponse(
                statusCode = resp.statusCode,
                headers = resp.headers,
                rawBody = resp.body,
                contentType = resp.contentType,
                url = url.toString(),
            )
            ensureApiSuccess(resp.statusCode, resp.body, retryAfterSeconds = parseRetryAfterSeconds(resp.headers))
            resp.body
        }
        return withContext(Dispatchers.Default) {
            json.decodeFromString(responseSerializer, raw)
        }
    }

    private suspend fun <T, R> executeJsonPost(
        path: String,
        requestBodyDto: T,
        requestSerializer: KSerializer<T>,
        responseSerializer: KSerializer<R>,
        queryParams: List<Pair<String, String?>> = emptyList(),
        extraHeaders: Map<String, String> = emptyMap(),
        requireSuccessFlag: Boolean = true,
    ): R {
        val url = NodeSeekUrlNormalizer.buildUrl(
            path = path,
            queryParams = queryParams,
            baseOrigin = baseOrigin,
        )
        val encodedJson = withContext(Dispatchers.Default) {
            json.encodeToString(requestSerializer, requestBodyDto)
        }
        val builder = Request.Builder()
            .url(url)
            .post(encodedJson.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")

        for ((k, v) in extraHeaders) {
            builder.header(k, v)
        }

        val builtRequest = builder.build()
        val raw = try {
            executeForumCall(builtRequest) { status, headers, body ->
                CloudflareChallengeDetector.inspectApiResponse(status, headers, body, url = url.toString())
                ensureApiSuccess(
                    statusCode = status,
                    rawBody = body,
                    requireSuccessFlag = requireSuccessFlag,
                    retryAfterSeconds = parseRetryAfterSeconds(headers),
                )
            }
        } catch (err: IOException) {
            val fallback = sameOriginTransport
            val isSignInRoute = path == "/api/account/signIn"
            val shouldFallbackForHighRisk = err is NodeSeekApiException &&
                err.isHighRiskAction &&
                (requireSuccessFlag || isSignInRoute)
            val shouldFallbackForOpaque403OnSignIn = isSignInRoute &&
                err is NodeSeekApiException &&
                err.statusCode == 403 &&
                err.serverMessage.isNullOrBlank()
            val shouldFallbackForChallenge = (err is CloudflareChallengeException || shouldFallbackForOpaque403OnSignIn) &&
                fallback != null &&
                (fallback.canAttemptWithExistingClearance() || isSignInRoute)

            if (fallback == null || (!shouldFallbackForHighRisk && !shouldFallbackForChallenge)) {
                throw err
            }

            val fallbackHeaders = buildSameOriginPostHeaders(
                path = path,
                extraHeaders = extraHeaders,
                hasJsonBody = true,
            )
            val refererUrl = extraHeaders["Referer"] ?: "${NodeSeekUrlNormalizer.ORIGIN}/"
            val resp = fallback.executeApiRequest(
                method = "POST",
                url = url.toString(),
                refererUrl = refererUrl,
                headers = fallbackHeaders,
                body = encodedJson,
            )
            CloudflareChallengeDetector.inspectApiResponse(
                statusCode = resp.statusCode,
                headers = resp.headers,
                rawBody = resp.body,
                contentType = resp.contentType,
                url = url.toString(),
            )
            ensureApiSuccess(
                statusCode = resp.statusCode,
                rawBody = resp.body,
                requireSuccessFlag = requireSuccessFlag,
                retryAfterSeconds = parseRetryAfterSeconds(resp.headers),
            )
            resp.body
        }
        return withContext(Dispatchers.Default) {
            json.decodeFromString(responseSerializer, raw)
        }
    }

    private suspend fun <R> executeEmptyPost(
        path: String,
        responseSerializer: KSerializer<R>,
        queryParams: List<Pair<String, String?>> = emptyList(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): R {
        val url = NodeSeekUrlNormalizer.buildUrl(
            path = path,
            queryParams = queryParams,
            baseOrigin = baseOrigin,
        )
        val builder = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .header("Accept", "application/json")

        for ((k, v) in extraHeaders) {
            builder.header(k, v)
        }

        val raw = try {
            executeForumCall(builder.build()) { status, headers, body ->
                CloudflareChallengeDetector.inspectApiResponse(status, headers, body, url = url.toString())
                ensureApiSuccess(status, body, retryAfterSeconds = parseRetryAfterSeconds(headers))
            }
        } catch (err: IOException) {
            val fallback = sameOriginTransport
            val shouldFallbackForHighRisk = err is NodeSeekApiException && err.isHighRiskAction
            val shouldFallbackForChallenge = err is CloudflareChallengeException &&
                fallback?.canAttemptWithExistingClearance() == true

            if (fallback == null || (!shouldFallbackForHighRisk && !shouldFallbackForChallenge)) {
                throw err
            }

            val fallbackHeaders = buildSameOriginPostHeaders(
                path = path,
                extraHeaders = extraHeaders,
                hasJsonBody = false,
            )
            val refererUrl = extraHeaders["Referer"] ?: "${NodeSeekUrlNormalizer.ORIGIN}/"
            val resp = fallback.executeApiRequest(
                method = "POST",
                url = url.toString(),
                refererUrl = refererUrl,
                headers = fallbackHeaders,
                body = null,
            )
            CloudflareChallengeDetector.inspectApiResponse(
                statusCode = resp.statusCode,
                headers = resp.headers,
                rawBody = resp.body,
                contentType = resp.contentType,
                url = url.toString(),
            )
            ensureApiSuccess(resp.statusCode, resp.body, retryAfterSeconds = parseRetryAfterSeconds(resp.headers))
            resp.body
        }
        return withContext(Dispatchers.Default) {
            json.decodeFromString(responseSerializer, raw)
        }
    }

    private fun buildSameOriginPostHeaders(
        path: String,
        extraHeaders: Map<String, String>,
        hasJsonBody: Boolean,
    ): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Requested-With", "XMLHttpRequest")
        if (hasJsonBody) {
            put("Content-Type", "application/json")
        }
        if (path in EDITOR_CSRF_PATHS) {
            put("csrf-token", generateEditorCsrfToken())
        }
        if (path in SIMPLE_CSRF_CHALLENGE_PATHS) {
            put("x-csrf-challenge", "simple-token")
        }
        for ((k, v) in extraHeaders) {
            if (!k.equals("Referer", ignoreCase = true) && !k.equals("Origin", ignoreCase = true)) {
                put(k, v)
            }
        }
    }

    private fun parseRetryAfterSeconds(headers: okhttp3.Headers): Long? =
        headers["Retry-After"]?.trim()?.toLongOrNull()?.coerceAtLeast(1L)

    private fun ensureApiSuccess(
        statusCode: Int,
        rawBody: String,
        requireSuccessFlag: Boolean = true,
        retryAfterSeconds: Long? = null,
    ) {
        if (statusCode == 429) {
            throw NodeSeekApiException(
                message = "请求过于频繁，请稍后再试",
                statusCode = 429,
                retryAfterSeconds = retryAfterSeconds,
            )
        }
        val jsonObj = runCatching {
            json.parseToJsonElement(rawBody) as? JsonObject
        }.getOrElse {
            if (statusCode == 403) {
                throw CloudflareChallengeException(statusCode = statusCode)
            }
            throw NodeSeekApiException(
                message = "官网未返回可读取的数据 ($statusCode)",
                statusCode = statusCode,
            )
        } ?: run {
            if (statusCode == 403) {
                throw CloudflareChallengeException(statusCode = statusCode)
            }
            throw NodeSeekApiException(
                message = "官网未返回可读取的数据 ($statusCode)",
                statusCode = statusCode,
            )
        }

        val serverMessage = (jsonObj["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val successElement = jsonObj["success"] as? JsonPrimitive
        val isExplicitFailure = successElement != null && (
            successElement.booleanOrNull == false ||
                successElement.contentOrNull == "0" ||
                successElement.contentOrNull?.equals("false", ignoreCase = true) == true
            )
        val has2faChallenge = !requireSuccessFlag && hasTwoFactorPayload(jsonObj)

        if ((statusCode !in 200..299 && !has2faChallenge) || (requireSuccessFlag && isExplicitFailure)) {
            val friendlyMessage = when {
                serverMessage == "USER NOT FOUND" -> "没有找到这位用户，请检查用户编号。"
                serverMessage?.contains("high risk", ignoreCase = true) == true ->
                    "当前操作触发了社区高风险风控保护，请降低频率或在网页端完成操作"
                serverMessage?.contains("OTP_EXPIRED_OR_NOT_EXIST", ignoreCase = true) == true ->
                    "两步验证会话已过期，请返回重新完成人机验证并登录"
                else -> serverMessage ?: "请求失败 ($statusCode)"
            }
            throw NodeSeekApiException(
                message = friendlyMessage,
                statusCode = statusCode,
                serverMessage = serverMessage,
                retryAfterSeconds = retryAfterSeconds,
            )
        }
    }

    private fun hasTwoFactorPayload(root: JsonObject): Boolean {
        val candidates = listOfNotNull(root, root["data"] as? JsonObject)
        return candidates.any { obj ->
            val flagPrim = (obj["need2FA"] ?: obj["need_2fa"] ?: obj["need2fa"] ?: obj["require2FA"] ?: obj["twoFactor"]) as? JsonPrimitive
            val flagTrue = flagPrim != null && (
                flagPrim.booleanOrNull == true ||
                    flagPrim.contentOrNull == "1" ||
                    flagPrim.contentOrNull?.equals("true", ignoreCase = true) == true
                )
            val otpPrim = (obj["otpSession"] ?: obj["otp_session"]) as? JsonPrimitive
            flagTrue && otpPrim?.contentOrNull?.isNotBlank() == true
        }
    }

    private suspend fun executeForumCall(
        initialRequest: Request,
        validator: (statusCode: Int, headers: Headers, body: String) -> Unit,
    ): String {
        val expectedOrigin = initialRequest.url
        if (hasUserInfo(expectedOrigin) || !isAllowedCredentialOrigin(expectedOrigin)) {
            throw NodeSeekApiException(
                "安全校验失败：社区请求必须使用 HTTPS 加密连接，拒绝通过明文 HTTP 发送凭证。",
            )
        }

        var currentRequest = initialRequest
        for (hop in 0..MAX_REDIRECT_HOPS) {
            val currentUrl = currentRequest.url
            if (hasUserInfo(currentUrl) ||
                !isAllowedCredentialOrigin(currentUrl) ||
                !isSameOrigin(expectedOrigin, currentUrl)
            ) {
                throw NodeSeekApiException(
                    "安全校验失败：禁止向非同源或非加密地址发送社区凭证。",
                )
            }

            val raw = forumHttpClient.newCall(currentRequest).awaitRawResponse()
            val status = raw.code

            if (status in REDIRECT_STATUS_CODES) {
                if (hop >= MAX_REDIRECT_HOPS) {
                    throw NodeSeekApiException(
                        message = "安全校验失败：请求重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）。",
                        statusCode = status,
                    )
                }
                val rawLocation = raw.headers["Location"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw NodeSeekApiException(
                        message = "安全校验失败：重定向响应缺少 Location 头 ($status)。",
                        statusCode = status,
                    )
                val nextUrl = currentUrl.resolve(rawLocation)
                    ?: throw NodeSeekApiException(
                        message = "安全校验失败：重定向目标地址无效或协议不受支持 ($status)。",
                        statusCode = status,
                    )
                if (hasUserInfo(nextUrl)) {
                    throw NodeSeekApiException(
                        message = "安全校验失败：拒绝重定向至包含内嵌用户信息的地址 ($status)。",
                        statusCode = status,
                    )
                }
                if (currentUrl.scheme.equals("https", ignoreCase = true) &&
                    !nextUrl.scheme.equals("https", ignoreCase = true)
                ) {
                    throw NodeSeekApiException(
                        message = "安全校验失败：拒绝从 HTTPS 降级重定向至明文 HTTP ($status)。",
                        statusCode = status,
                    )
                }
                if (!isAllowedCredentialOrigin(nextUrl) || !isSameOrigin(expectedOrigin, nextUrl)) {
                    throw NodeSeekApiException(
                        message = "安全校验失败：拒绝跨源重定向请求，已阻止泄露凭证 ($status)。",
                        statusCode = status,
                    )
                }

                currentRequest = if (
                    currentRequest.method == "GET" ||
                    currentRequest.method == "HEAD" ||
                    status == 307 ||
                    status == 308
                ) {
                    currentRequest.newBuilder().url(nextUrl).build()
                } else {
                    val nextBuilder = currentRequest.newBuilder()
                        .url(nextUrl)
                        .get()
                    POST_REDIRECT_STRIP_HEADERS.forEach { nextBuilder.removeHeader(it) }
                    nextBuilder.build()
                }
                continue
            }

            if (status in 300..399) {
                throw NodeSeekApiException(
                    message = "安全校验失败：返回不受支持的重定向状态 ($status)。",
                    statusCode = status,
                )
            }

            validator(status, raw.headers, raw.body)
            return raw.body
        }
        throw NodeSeekApiException("安全校验失败：请求重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）。")
    }

    private suspend fun executeRssGetWithRedirects(initialUrl: HttpUrl): String {
        if (hasUserInfo(initialUrl)) {
            throw NodeSeekApiException("安全校验失败：RSS 地址包含非法的内嵌用户信息。")
        }
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECT_HOPS) {
            val request = Request.Builder()
                .url(currentUrl)
                .get()
                .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                .build()
            val raw = rssHttpClient.newCall(request).awaitRawResponse()
            val status = raw.code

            if (status in REDIRECT_STATUS_CODES) {
                if (hop >= MAX_REDIRECT_HOPS) {
                    throw NodeSeekApiException("RSS 重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）", statusCode = status)
                }
                val rawLocation = raw.headers["Location"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw NodeSeekApiException("RSS 重定向缺少 Location 头 ($status)", statusCode = status)
                val nextUrl = currentUrl.resolve(rawLocation)
                    ?: throw NodeSeekApiException("RSS 重定向目标地址无效 ($status)", statusCode = status)
                if (hasUserInfo(nextUrl)) {
                    throw NodeSeekApiException("安全校验失败：拒绝重定向至包含内嵌用户信息的 RSS 地址 ($status)", statusCode = status)
                }
                if (currentUrl.scheme.equals("https", ignoreCase = true) &&
                    !nextUrl.scheme.equals("https", ignoreCase = true)
                ) {
                    throw NodeSeekApiException("安全校验失败：拒绝 RSS 从 HTTPS 降级重定向至明文 HTTP ($status)", statusCode = status)
                }
                currentUrl = nextUrl
                continue
            }

            if (status == 429) {
                throw NodeSeekApiException(
                    message = "请求过于频繁，请稍后再试",
                    statusCode = 429,
                    retryAfterSeconds = raw.headers["Retry-After"]?.trim()?.toLongOrNull(),
                )
            }
            if (status !in 200..299) {
                throw NodeSeekApiException("RSS 请求失败 ($status)", statusCode = status, serverMessage = raw.body.take(200))
            }
            return raw.body
        }
        throw NodeSeekApiException("RSS 重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）")
    }

    companion object {
        internal const val MAX_REDIRECT_HOPS = 5
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
        private val POST_REDIRECT_STRIP_HEADERS = listOf(
            "Content-Type",
            "Content-Length",
            "Transfer-Encoding",
            "Origin",
            "Authorization",
            "X-API-Key",
            "x-captcha-token",
            "x-captcha-source",
            "csrf-token",
            "x-csrf-token",
            "x-csrf-challenge",
            "x-dynamic-sign",
        )
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val VALID_REACTIONS = setOf("upvote", "like", "dislike")
        private val NODE_IMAGE_KEY_REGEX = Regex("""^[a-fA-F0-9]{32,128}$""")
        private val EDITOR_CSRF_PATHS = setOf(
            "/api/content/new-discussion",
            "/api/content/edit-discussion",
            "/api/content/new-comment",
            "/api/content/edit-comment",
        )
        private val SIMPLE_CSRF_CHALLENGE_PATHS = setOf(
            "/api/account/signOut",
            "/api/avatar/upload",
            "/api/invite/buyOne",
        )

        private fun normalizeHost(host: String): String =
            host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")

        internal fun isLoopbackHost(host: String): Boolean =
            normalizeHost(host) in LOOPBACK_HOSTS

        internal fun isAllowedCredentialOrigin(url: HttpUrl): Boolean =
            url.scheme.equals("https", ignoreCase = true) ||
                (url.scheme.equals("http", ignoreCase = true) && isLoopbackHost(url.host))

        internal fun isSameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                normalizeHost(a.host) == normalizeHost(b.host) &&
                a.port == b.port

        private fun hasUserInfo(url: HttpUrl): Boolean =
            url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()

        fun createDefaultHttpClient(
            cookieBridge: WebViewCookieBridge,
            userAgentProvider: UserAgentProvider,
            csrfTokenStore: CsrfTokenStore = CsrfTokenStore(),
            additionalTargetHost: String? = null,
            connectTimeoutSeconds: Long = 15L,
            readTimeoutSeconds: Long = 15L,
        ): OkHttpClient {
            return OkHttpClient.Builder()
                .cookieJar(cookieBridge)
                .addInterceptor(
                    NodeSeekRequestInterceptor(
                        userAgentProvider = userAgentProvider,
                        csrfTokenStore = csrfTokenStore,
                        additionalTargetHost = additionalTargetHost,
                    ),
                )
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }
}

private data class RawHttpPayload(
    val code: Int,
    val headers: Headers,
    val body: String,
)

private suspend fun Call.awaitRawResponse(): RawHttpPayload = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCompleted) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        val bodyString = if (resp.code in 300..399) {
                            ""
                        } else {
                            resp.body?.string().orEmpty()
                        }
                        if (!cont.isCompleted) {
                            cont.resume(
                                RawHttpPayload(
                                    code = resp.code,
                                    headers = resp.headers,
                                    body = bodyString,
                                ),
                            )
                        }
                    } catch (t: Throwable) {
                        if (!cont.isCompleted) {
                            cont.resumeWithException(t)
                        }
                    }
                }
            }
        },
    )
}
