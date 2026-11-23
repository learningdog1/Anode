package com.nodeseek.app.ui.topic

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
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import com.nodeseek.app.domain.repository.UserRepository
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class TopicDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun page(
        topicId: Long = 100L,
        pageNum: Int = 1,
        totalPages: Int = 2,
        includeOpening: Boolean = pageNum == 1,
    ): TopicDetailPage {
        val opAuthor = FloorAuthor(uid = 10L, username = "op_user", isTopicAuthor = true)
        val opening = if (includeOpening) {
            FloorComment(
                commentId = 1000L,
                floorNumber = 0,
                author = opAuthor,
                createdAtIso = "2026-11-23T00:00:00Z",
                markdownBody = "OP Body",
            )
        } else {
            null
        }
        val reply = FloorComment(
            commentId = 2000L + pageNum,
            floorNumber = (pageNum - 1) * 10 + 1,
            author = FloorAuthor(uid = 20L + pageNum, username = "reply_user_$pageNum"),
            createdAtIso = "2026-11-23T00:05:00Z",
            markdownBody = "Reply on page $pageNum",
        )
        return TopicDetailPage(
            topicId = topicId,
            title = "测试主题",
            categorySlug = "tech",
            categoryName = "技术",
            author = opAuthor,
            currentPage = pageNum,
            totalPages = totalPages,
            openingFloor = opening,
            replies = listOf(reply),
        )
    }

    private fun buildContainer(
        forum: ForumRepository,
        session: SessionRepository,
        bookmarks: BookmarkAndHistoryRepository,
        drafts: DraftRepository,
        users: UserRepository,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): AppContainer = Proxy.newProxyInstance(
        AppContainer::class.java.classLoader,
        arrayOf(AppContainer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getForumRepository" -> forum
            "getSessionRepository" -> session
            "getBookmarkAndHistoryRepository" -> bookmarks
            "getDraftRepository" -> drafts
            "getUserRepository" -> users
            "getSettingsRepository" -> settings
            "getRadarRepository" -> null
            else -> throw UnsupportedOperationException("Unexpected AppContainer call: ${method.name}")
        }
    } as AppContainer

    @Test
    fun `closeReplySheet after edit mode clears edit state and restores saved reply draft`() = runTest {
        val drafts = InMemoryDraftRepository().apply {
            saveImmediate(
                EditorDraft(
                    draftKey = EditorDraft.replyKey(100L),
                    accountUid = 1L,
                    markdownContent = "我未发完的普通回复草稿",
                ),
            )
        }
        val session = FakeTopicSessionRepository().apply {
            sessionState.value = AccountSession(uid = 1L, username = "me")
        }
        val forum = FakeTopicForumRepository { _, p -> Result.success(page(pageNum = p)) }
        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session, FakeTopicBookmarkRepository(), drafts, FakeTopicUserRepository()),
            topicId = 100L,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null }

        val targetComment = FloorComment(
            commentId = 555L,
            floorNumber = 3,
            author = FloorAuthor(uid = 1L, username = "me"),
            createdAtIso = "2026-11-23T00:10:00Z",
            markdownBody = "原评论内容",
        )
        vm.openEditReplySheet(targetComment)
        val editState = vm.uiState.first { it.replyComposer.isEditMode }
        assertEquals("原评论内容", editState.replyComposer.draftMarkdown)

        vm.closeReplySheet()
        val restored = vm.uiState.first { !it.replyComposer.isSheetOpen && !it.replyComposer.isEditMode }
        assertEquals("我未发完的普通回复草稿", restored.replyComposer.draftMarkdown)

        collectJob.cancel()
    }

    @Test
    fun `resolveAndNavigateToUser does not fuzzy jump when only non-exact user returned`() = runTest {
        val users = FakeTopicUserRepository(
            results = listOf(FloorAuthor(uid = 999L, username = "alice_other")),
        )
        val forum = FakeTopicForumRepository { _, p -> Result.success(page(pageNum = p)) }
        val vm = TopicDetailViewModel(
            container = buildContainer(
                forum,
                FakeTopicSessionRepository(),
                FakeTopicBookmarkRepository(),
                InMemoryDraftRepository(),
                users,
            ),
            topicId = 100L,
        )
        val events = mutableListOf<TopicDetailUiEvent>()
        val eventJob = launch(testDispatcher) { vm.events.collect { events += it } }
        val stateJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null }

        vm.resolveAndNavigateToUser("@alice")
        assertTrue(events.none { it is TopicDetailUiEvent.NavigateToUser })
        assertTrue(events.any { it is TopicDetailUiEvent.ShowMessage && it.message.contains("未找到用户 @alice") })

        eventJob.cancel()
        stateJob.cancel()
    }

    @Test
    fun `unbookmark failure emits only error snackbar without contradictory success toast`() = runTest {
        val session = FakeTopicSessionRepository().apply {
            sessionState.value = AccountSession(uid = 1L, username = "me")
        }
        val bookmarks = FakeTopicBookmarkRepository(
            initialIds = setOf(100L),
            unbookmarkFailure = RuntimeException("网络断开"),
        )
        val forum = FakeTopicForumRepository { _, p -> Result.success(page(pageNum = p)) }
        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session, bookmarks, InMemoryDraftRepository(), FakeTopicUserRepository()),
            topicId = 100L,
        )
        val events = mutableListOf<TopicDetailUiEvent>()
        val eventJob = launch(testDispatcher) { vm.events.collect { events += it } }
        val stateJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null && it.isBookmarked }

        vm.toggleBookmark()
        val messages = events.filterIsInstance<TopicDetailUiEvent.ShowMessage>().map { it.message }
        assertEquals(listOf("网络断开"), messages)

        eventJob.cancel()
        stateJob.cancel()
    }

    @Test
    fun `appending page 2 does not overwrite page 1 offline bookmark snapshot`() = runTest {
        val bookmarks = FakeTopicBookmarkRepository(initialIds = setOf(100L))
        val forum = FakeTopicForumRepository { _, p -> Result.success(page(pageNum = p, totalPages = 2)) }
        val vm = TopicDetailViewModel(
            container = buildContainer(
                forum,
                FakeTopicSessionRepository(),
                bookmarks,
                InMemoryDraftRepository(),
                FakeTopicUserRepository(),
            ),
            topicId = 100L,
        )
        val stateJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null && it.isBookmarked }
        val savesAfterPage1 = bookmarks.savedSnapshots.size
        assertTrue(bookmarks.savedSnapshots.all { it.currentPage == 1 })

        vm.appendNextPage()
        vm.uiState.first { it.loadedEndPage == 2 }
        assertEquals(
            "Subpage append must not overwrite page 1 offline snapshot",
            savesAfterPage1,
            bookmarks.savedSnapshots.size,
        )

        stateJob.cancel()
    }

    @Test
    fun `refreshCurrentPage discards stale in-flight appendNextPage response`() = runTest {
        val page2Gate = CompletableDeferred<Result<TopicDetailPage>>()
        var page1Loads = 0
        val forum = FakeTopicForumRepository { _, p ->
            if (p == 1) {
                page1Loads++
                Result.success(page(pageNum = 1, totalPages = 2))
            } else {
                page2Gate.await()
            }
        }
        val vm = TopicDetailViewModel(
            container = buildContainer(
                forum,
                FakeTopicSessionRepository(),
                FakeTopicBookmarkRepository(),
                InMemoryDraftRepository(),
                FakeTopicUserRepository(),
            ),
            topicId = 100L,
        )
        val stateJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null }

        vm.appendNextPage()
        assertTrue(vm.uiState.first { it.isAppendingNextPage }.isAppendingNextPage)

        // Pull-to-refresh while page 2 is still in flight
        vm.refreshCurrentPage()
        val refreshed = vm.uiState.first { !it.isRefreshing && page1Loads >= 2 }
        assertEquals(1, refreshed.loadedEndPage)
        assertFalse(refreshed.isAppendingNextPage)

        // Now complete the stale page 2 response: must be ignored!
        page2Gate.complete(Result.success(page(pageNum = 2, totalPages = 2)))
        assertEquals(1, vm.uiState.value.loadedEndPage)

        stateJob.cancel()
    }

    private class InMemoryDraftRepository : DraftRepository {
        private val map = linkedMapOf<Pair<Long, String>, EditorDraft>()
        fun saveImmediate(draft: EditorDraft) {
            map[draft.accountUid to draft.draftKey] = draft
        }

        override suspend fun getDraft(accountUid: Long, draftKey: String): EditorDraft? =
            map[accountUid to draftKey]

        override suspend fun saveDraft(draft: EditorDraft) {
            map[draft.accountUid to draft.draftKey] = draft
        }

        override suspend fun deleteDraft(accountUid: Long, draftKey: String) {
            map.remove(accountUid to draftKey)
        }

        override fun observeDrafts(accountUid: Long): Flow<List<EditorDraft>> = flowOf(emptyList())
        override fun observeDraft(accountUid: Long, draftKey: String): Flow<EditorDraft?> = flowOf(null)
    }

    private class FakeTopicForumRepository(
        private val detailHandler: suspend (Long, Int) -> Result<TopicDetailPage>,
    ) : ForumRepository {
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
            detailHandler(topicId, page)

        override suspend fun createTopic(
            categorySlug: String,
            title: String,
            markdownContent: String,
            requiredViewLevel: Int,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun editTopic(
            topicId: Long,
            title: String,
            markdownContent: String,
            requiredViewLevel: Int,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun postReply(topicId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun editReply(commentId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun setReplyPinned(commentId: Long, pinned: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getFreeDrumstickCost(): Result<Int> = Result.success(0)
        override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType): Result<ReactionResult> =
            Result.failure(UnsupportedOperationException())
    }

    private class FakeTopicUserRepository(
        private val results: List<FloorAuthor> = emptyList(),
    ) : UserRepository {
        override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> = Result.success(results)
        override suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean): Result<UserProfile> =
            Result.failure(UnsupportedOperationException())

        override suspend fun updateOwnProfile(bio: String, signature: String, readme: String): Result<Unit> =
            Result.success(Unit)

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

    private class FakeTopicSessionRepository : SessionRepository {
        override val sessionState = MutableStateFlow<AccountSession?>(null)
        override val challengeRequired = MutableStateFlow(false)
        override val authVerifiedEpoch = MutableStateFlow(0L)
        override suspend fun refreshSession(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signIn(username: String, password: String, turnstileToken: String): Result<SignInStepResult> =
            Result.success(SignInStepResult.Authenticated)

        override suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit> = Result.success(Unit)
        override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signOut(): Result<Unit> {
            sessionState.value = null
            return Result.success(Unit)
        }
    }

    private class FakeTopicBookmarkRepository(
        initialIds: Set<Long> = emptySet(),
        private val unbookmarkFailure: Throwable? = null,
    ) : BookmarkAndHistoryRepository {
        private val ids = MutableStateFlow(initialIds)
        val savedSnapshots = mutableListOf<TopicDetailPage>()
        override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
            Result.success(emptyList<TopicSummary>() to false)

        override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flowOf(emptyList())
        override fun observeBookmarkedIds(): Flow<Set<Long>> = ids.asStateFlow()
        override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> =
            if (!bookmarked && unbookmarkFailure != null) Result.failure(unbookmarkFailure) else Result.success(Unit)

        override suspend fun saveOfflineBookmark(
            summary: TopicSummary,
            detailSnapshot: TopicDetailPage?,
            syncedWithServer: Boolean,
        ) {
            ids.value = ids.value + summary.id
            if (detailSnapshot != null) savedSnapshots += detailSnapshot
        }

        override suspend fun removeLocalBookmark(topicId: Long) {
            ids.value = ids.value - topicId
        }

        override suspend fun getOfflineBookmarkSnapshot(topicId: Long): TopicDetailPage? = null
        override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = flowOf(emptyList())
        override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> =
            flowOf(emptyMap())

        override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
        override suspend fun clearReadHistory() = Unit
    }
}
