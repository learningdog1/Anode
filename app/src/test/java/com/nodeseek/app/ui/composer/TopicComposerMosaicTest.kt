package com.nodeseek.app.ui.composer

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.ImageHostRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.ui.topic.TopicDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class TopicComposerMosaicTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val staticPngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
    ) + ByteArray(17)
    private val editedJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x42,
    )
    private val animatedGifBytes = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(8)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingImageHostRepository : ImageHostRepository {
        data class UploadCall(val bytes: ByteArray, val mimeType: String, val fileName: String?)
        val uploads = mutableListOf<UploadCall>()

        override suspend fun uploadImage(
            bytes: ByteArray,
            mimeType: String,
            deleteAfterDays: Int?,
            fileName: String?,
        ): Result<String> {
            uploads += UploadCall(bytes, mimeType, fileName)
            return Result.success("https://img.nodeimage.com/i/${uploads.size}.png")
        }
    }

    private fun buildComposerContainer(
        imageHost: ImageHostRepository,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): AppContainer {
        val forum = FakeForumRepository()
        val session = FakeSessionRepository().apply {
            sessionState.value = AccountSession(uid = 7L, username = "mosaic_user")
        }
        val drafts = InMemoryDraftRepository()
        return Proxy.newProxyInstance(
            AppContainer::class.java.classLoader,
            arrayOf(AppContainer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getForumRepository" -> forum
                "getSessionRepository" -> session
                "getDraftRepository" -> drafts
                "getSettingsRepository" -> settings
                "getImageHostRepository" -> imageHost
                else -> throw UnsupportedOperationException("Unexpected AppContainer call: ${method.name}")
            }
        } as AppContainer
    }

    private fun buildDetailContainer(
        forum: ForumRepository,
        imageHost: ImageHostRepository,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): AppContainer {
        val session = FakeSessionRepository().apply {
            sessionState.value = AccountSession(uid = 7L, username = "mosaic_user")
        }
        val drafts = InMemoryDraftRepository()
        val bookmarks = FakeBookmarkRepository()
        val users = FakeUserRepository()
        return Proxy.newProxyInstance(
            AppContainer::class.java.classLoader,
            arrayOf(AppContainer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getForumRepository" -> forum
                "getSessionRepository" -> session
                "getDraftRepository" -> drafts
                "getSettingsRepository" -> settings
                "getBookmarkAndHistoryRepository" -> bookmarks
                "getUserRepository" -> users
                "getImageHostRepository" -> imageHost
                "getRadarRepository" -> null
                else -> throw UnsupportedOperationException("Unexpected AppContainer call: ${method.name}")
            }
        } as AppContainer
    }

    @Test
    fun `composer routes static image through privacy editor for confirm or skip, and saves missing API key first`() = runTest {
        val imageHost = RecordingImageHostRepository()
        val settings = FakeSettingsRepository()
        val vm = TopicComposerViewModel(container = buildComposerContainer(imageHost, settings))
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        // 1. Missing API key -> opens key dialog without uploading or opening mosaic yet
        vm.onPhotoBytesSelected(staticPngBytes, "image/png", "secret_ip.png")
        assertTrue(vm.uiState.value.showApiKeyDialog)
        assertFalse(vm.uiState.value.showPrivacyEditorSheet)
        assertEquals(0, imageHost.uploads.size)

        // 2. Save API key -> opens privacy mosaic sheet instead of bypassing it
        vm.saveApiKeyAndUpload("0123456789abcdef0123456789abcdef")
        assertFalse(vm.uiState.value.showApiKeyDialog)
        assertTrue(vm.uiState.value.showPrivacyEditorSheet)
        assertEquals(0, imageHost.uploads.size)

        // 3. Confirm mosaic edit -> uploads edited bytes
        vm.confirmPrivacyImageUpload(editedJpegBytes, "image/jpeg", "secret_ip.jpg")
        assertFalse(vm.uiState.value.showPrivacyEditorSheet)
        assertEquals(1, imageHost.uploads.size)
        assertArrayEquals(editedJpegBytes, imageHost.uploads[0].bytes)
        assertEquals("image/jpeg", imageHost.uploads[0].mimeType)

        // 4. Skip mosaic -> uploads original bytes untouched
        vm.onPhotoBytesSelected(staticPngBytes, "image/png", "raw_shot.png")
        assertTrue(vm.uiState.value.showPrivacyEditorSheet)
        vm.skipPrivacyEditAndUpload()
        assertFalse(vm.uiState.value.showPrivacyEditorSheet)
        assertEquals(2, imageHost.uploads.size)
        assertArrayEquals(staticPngBytes, imageHost.uploads[1].bytes)

        // 5. Animated GIF -> bypasses privacy editor and uploads immediately
        vm.onPhotoBytesSelected(animatedGifBytes, "image/gif", "meme.gif")
        assertFalse(vm.uiState.value.showPrivacyEditorSheet)
        assertEquals(3, imageHost.uploads.size)
        assertArrayEquals(animatedGifBytes, imageHost.uploads[2].bytes)

        collectJob.cancel()
    }

    @Test
    fun `topic detail reply upload routes through privacy mosaic and resolveOpeningFloorForShare fetches page 1`() = runTest {
        val imageHost = RecordingImageHostRepository()
        val settings = FakeSettingsRepository()
        val opFloor = FloorComment(
            commentId = 100L,
            floorNumber = 0,
            author = FloorAuthor(uid = 10L, username = "op_author", isTopicAuthor = true),
            createdAtIso = "2026-11-26T00:00:00Z",
            markdownBody = "Opening post markdown",
        )
        val forum = object : FakeForumRepository() {
            override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> {
                return Result.success(
                    TopicDetailPage(
                        topicId = topicId,
                        title = "Share Test Topic",
                        categorySlug = "tech",
                        categoryName = "技术",
                        author = opFloor.author,
                        currentPage = page,
                        totalPages = 2,
                        openingFloor = if (page == 1) opFloor else null,
                        replies = emptyList(),
                    ),
                )
            }
        }
        val vm = TopicDetailViewModel(
            container = buildDetailContainer(forum, imageHost, settings),
            topicId = 42L,
            initialFloor = 15,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null }

        // imageFetchClient must return null safely on Proxy-based AppContainer without crashing
        assertNull(vm.imageFetchClient)

        // resolveOpeningFloorForShare returns page 1 opening floor
        val resolvedOp = vm.resolveOpeningFloorForShare()
        assertNotNull(resolvedOp)
        assertEquals("Opening post markdown", resolvedOp!!.markdownBody)

        // Missing API key -> key dialog -> save -> privacy editor -> confirm edited upload
        vm.onPhotoSelectedForReply(staticPngBytes, "image/png", "reply.png")
        assertTrue(vm.uiState.value.replyComposer.showApiKeyDialog)
        assertNull(vm.uiState.value.replyComposer.pendingPrivacyImage)

        vm.saveNodeImageApiKeyAndContinue("abcdef0123456789abcdef0123456789")
        assertFalse(vm.uiState.value.replyComposer.showApiKeyDialog)
        assertNotNull(vm.uiState.value.replyComposer.pendingPrivacyImage)

        vm.confirmReplyPrivacyImageUpload(editedJpegBytes, "image/jpeg", "reply.jpg")
        assertNull(vm.uiState.value.replyComposer.pendingPrivacyImage)
        assertEquals(1, imageHost.uploads.size)
        assertArrayEquals(editedJpegBytes, imageHost.uploads[0].bytes)

        // Skip mosaic uploads original
        vm.onPhotoSelectedForReply(staticPngBytes, "image/png", "reply_raw.png")
        assertNotNull(vm.uiState.value.replyComposer.pendingPrivacyImage)
        vm.skipReplyPrivacyEditAndUpload()
        assertEquals(2, imageHost.uploads.size)
        assertArrayEquals(staticPngBytes, imageHost.uploads[1].bytes)

        // Animated GIF bypasses privacy editor
        vm.onPhotoSelectedForReply(animatedGifBytes, "image/gif", "anim.gif")
        assertNull(vm.uiState.value.replyComposer.pendingPrivacyImage)
        assertEquals(3, imageHost.uploads.size)
        assertArrayEquals(animatedGifBytes, imageHost.uploads[2].bytes)

        collectJob.cancel()
    }

    private open class FakeForumRepository : ForumRepository {
        override fun observeCategories(): Flow<List<Category>> = flowOf(Category.DEFAULT_CATEGORIES)
        override suspend fun refreshCategories(force: Boolean): Result<List<Category>> =
            Result.success(Category.DEFAULT_CATEGORIES)
        override suspend fun getFeed(
            categorySlug: String,
            sort: FeedSort,
            page: Int,
            includeBlocked: Boolean,
            forceRefresh: Boolean,
        ): Result<FeedPageResult> = Result.success(FeedPageResult(topics = emptyList(), currentPage = page, hasNextPage = false))
        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(topics = emptyList(), currentPage = page, hasNextPage = false))
        override suspend fun searchTopics(
            query: String,
            categorySlug: String,
            sort: FeedSort,
            page: Int,
        ): Result<FeedPageResult> = Result.success(FeedPageResult(topics = emptyList(), currentPage = page, hasNextPage = false))
        override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> =
            Result.failure(UnsupportedOperationException())
        override suspend fun createTopic(categorySlug: String, title: String, markdownContent: String, requiredViewLevel: Int): Result<Unit> =
            Result.success(Unit)
        override suspend fun editTopic(topicId: Long, title: String, markdownContent: String, requiredViewLevel: Int): Result<Unit> =
            Result.success(Unit)
        override suspend fun postReply(topicId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun editReply(commentId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun setReplyPinned(commentId: Long, pinned: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getFreeDrumstickCost(): Result<Int> = Result.success(0)
        override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType): Result<ReactionResult> =
            Result.failure(UnsupportedOperationException())
    }

    private class FakeSessionRepository : SessionRepository {
        override val sessionState = MutableStateFlow<AccountSession?>(null)
        override val challengeRequired = MutableStateFlow(false)
        override val authVerifiedEpoch = MutableStateFlow(0L)
        override suspend fun refreshSession(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signIn(username: String, password: String, turnstileToken: String): Result<SignInStepResult> =
            Result.success(SignInStepResult.Authenticated)
        override suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit> = Result.success(Unit)
        override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signOut(): Result<Unit> = Result.success(Unit)
    }

    private class InMemoryDraftRepository : DraftRepository {
        override suspend fun getDraft(accountUid: Long, draftKey: String): EditorDraft? = null
        override suspend fun saveDraft(draft: EditorDraft) = Unit
        override suspend fun deleteDraft(accountUid: Long, draftKey: String) = Unit
        override fun observeDrafts(accountUid: Long): Flow<List<EditorDraft>> = flowOf(emptyList())
        override fun observeDraft(accountUid: Long, draftKey: String): Flow<EditorDraft?> = flowOf(null)
    }

    private class FakeBookmarkRepository : BookmarkAndHistoryRepository {
        override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
            Result.success(emptyList<TopicSummary>() to false)
        override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flowOf(emptyList())
        override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
        override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun saveOfflineBookmark(summary: TopicSummary, detailSnapshot: TopicDetailPage?, syncedWithServer: Boolean) = Unit
        override suspend fun removeLocalBookmark(topicId: Long) = Unit
        override suspend fun getOfflineBookmarkSnapshot(topicId: Long): TopicDetailPage? = null
        override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = flowOf(emptyList())
        override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> = flowOf(emptyMap())
        override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
        override suspend fun clearReadHistory() = Unit
    }

    private class FakeUserRepository : UserRepository {
        override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean): Result<UserProfile> =
            Result.failure(UnsupportedOperationException())
        override suspend fun updateOwnProfile(bio: String, signature: String, readme: String): Result<Unit> = Result.success(Unit)
        override suspend fun getUserTopics(uid: Long, page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
            Result.success(emptyList<TopicSummary>() to false)
        override suspend fun getUserReplies(uid: Long, page: Int): Result<Pair<List<UserReplyExcerpt>, Boolean>> =
            Result.success(emptyList<UserReplyExcerpt>() to false)
        override suspend fun setFollowed(targetUid: Long, follow: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getConnections(following: Boolean): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun getBlockedUsers(): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun blockUserByName(username: String): Result<Unit> = Result.success(Unit)
        override suspend fun unblockUserById(targetUid: Long): Result<Unit> = Result.success(Unit)
    }
}
