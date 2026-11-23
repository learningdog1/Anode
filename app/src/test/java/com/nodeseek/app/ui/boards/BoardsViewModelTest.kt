package com.nodeseek.app.ui.boards

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import com.nodeseek.app.domain.repository.UserRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun topic(id: Long, title: String = "主题 #$id") = TopicSummary(
        id = id,
        title = title,
        categorySlug = "tech",
        categoryName = "技术",
        authorName = "tester",
    )

    @Test
    fun `next page search failure preserves page 1 results and populates searchNextPageError`() = runTest {
        val forum = FakeBoardsForumRepository { _, _, _, page ->
            if (page == 1) {
                Result.success(
                    FeedPageResult(
                        topics = listOf(topic(1L), topic(1L), topic(2L)),
                        currentPage = 1,
                        hasNextPage = true,
                    ),
                )
            } else {
                Result.failure(RuntimeException("下一页超时"))
            }
        }
        val vm = BoardsViewModel(
            forumRepository = forum,
            userRepository = FakeBoardsUserRepository(),
            sessionRepository = FakeBoardsSessionRepository(),
            bookmarkAndHistoryRepository = FakeBoardsBookmarkRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeBoardsFilterRepository(),
            autoFocusSearch = true,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        vm.submitSearch("vps")
        val page1 = vm.uiState.first { !it.isSearching && it.searchTopics.isNotEmpty() }
        // Deduplicated on reset=true
        assertEquals(listOf(1L, 2L), page1.searchTopics.map { it.id })
        assertNull(page1.searchErrorMessage)
        assertNull(page1.searchNextPageError)

        vm.loadNextSearchPage()
        val afterFail = vm.uiState.first { !it.isSearchingNextPage && it.searchNextPageError != null }
        // Page 1 results must remain intact!
        assertEquals(listOf(1L, 2L), afterFail.searchTopics.map { it.id })
        assertNull(afterFail.searchErrorMessage)
        assertEquals("下一页超时", afterFail.searchNextPageError)

        collectJob.cancel()
    }

    @Test
    fun `user search strips leading at sign before querying repository`() = runTest {
        val userRepo = FakeBoardsUserRepository()
        val vm = BoardsViewModel(
            forumRepository = FakeBoardsForumRepository(),
            userRepository = userRepo,
            sessionRepository = FakeBoardsSessionRepository(),
            bookmarkAndHistoryRepository = FakeBoardsBookmarkRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeBoardsFilterRepository(),
            autoFocusSearch = true,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        vm.selectSearchScope(SearchScope.USERS)
        vm.submitSearch("@alice")

        val state = vm.uiState.first { !it.isSearching && it.searchUsers.isNotEmpty() }
        assertEquals(listOf("alice"), userRepo.queriedKeywords)
        assertEquals(listOf(42L), state.searchUsers.map { it.uid })

        collectJob.cancel()
    }

    @Test
    fun `setSearchModeActive false cancels in-flight search and clears loading flag`() = runTest {
        val gate = CompletableDeferred<Result<FeedPageResult>>()
        val forum = FakeBoardsForumRepository { _, _, _, _ -> gate.await() }
        val vm = BoardsViewModel(
            forumRepository = forum,
            userRepository = FakeBoardsUserRepository(),
            sessionRepository = FakeBoardsSessionRepository(),
            bookmarkAndHistoryRepository = FakeBoardsBookmarkRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeBoardsFilterRepository(),
            autoFocusSearch = true,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        vm.submitSearch("slow-query")
        assertTrue(vm.uiState.first { it.isSearching }.isSearching)

        vm.setSearchModeActive(false)
        val cleared = vm.uiState.first { !it.isSearchModeActive }
        assertFalse(cleared.isSearching)

        gate.complete(Result.success(FeedPageResult(topics = listOf(topic(99L)), currentPage = 1, hasNextPage = false)))
        assertTrue("Cancelled search must not repopulate searchTopics", vm.uiState.value.searchTopics.isEmpty())

        collectJob.cancel()
    }

    @Test
    fun `toggleBookmark unbookmark failure reports accurate removal message`() = runTest {
        val session = FakeBoardsSessionRepository().apply {
            sessionState.value = AccountSession(uid = 1L, username = "me")
        }
        val bookmarkRepo = FakeBoardsBookmarkRepository(
            initialBookmarked = setOf(77L),
            remoteFailure = RuntimeException(""),
        )
        val vm = BoardsViewModel(
            forumRepository = FakeBoardsForumRepository(),
            userRepository = FakeBoardsUserRepository(),
            sessionRepository = session,
            bookmarkAndHistoryRepository = bookmarkRepo,
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeBoardsFilterRepository(),
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        vm.toggleBookmark(topic(77L).copy(isBookmarked = true))
        val msg = vm.uiState.first { it.snackbarMessage != null }.snackbarMessage
        assertNotNull(msg)
        assertTrue(msg!!.contains("移除") || msg.isEmpty())

        collectJob.cancel()
    }

    private class FakeBoardsForumRepository(
        private val searchHandler: suspend (String, String, FeedSort, Int) -> Result<FeedPageResult> = { _, _, _, _ ->
            Result.success(FeedPageResult(topics = emptyList(), currentPage = 1, hasNextPage = false))
        },
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
        ): Result<FeedPageResult> = searchHandler(query, categorySlug, sort, page)

        override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> =
            Result.failure(UnsupportedOperationException())

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

    private class FakeBoardsUserRepository : UserRepository {
        val queriedKeywords = mutableListOf<String>()
        override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> {
            queriedKeywords += keyword
            return Result.success(
                listOf(
                    FloorAuthor(uid = 42L, username = keyword),
                    FloorAuthor(uid = 42L, username = keyword),
                ),
            )
        }

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

    private class FakeBoardsSessionRepository : SessionRepository {
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

    private class FakeBoardsBookmarkRepository(
        initialBookmarked: Set<Long> = emptySet(),
        private val remoteFailure: Throwable? = null,
    ) : BookmarkAndHistoryRepository {
        private val ids = MutableStateFlow(initialBookmarked)
        override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
            Result.success(emptyList<TopicSummary>() to false)

        override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flowOf(emptyList())
        override fun observeBookmarkedIds(): Flow<Set<Long>> = ids.asStateFlow()
        override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> =
            if (remoteFailure != null) Result.failure(remoteFailure) else Result.success(Unit)

        override suspend fun saveOfflineBookmark(
            summary: TopicSummary,
            detailSnapshot: TopicDetailPage?,
            syncedWithServer: Boolean,
        ) {
            ids.value = ids.value + summary.id
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

    private class FakeBoardsFilterRepository : ContentFilterRepository {
        override val config: StateFlow<ContentFilterConfig> = MutableStateFlow(ContentFilterConfig()).asStateFlow()
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun upsertRule(rule: ContentBlockRule) = Unit
        override suspend fun removeRule(ruleId: String) = Unit
        override suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) = Unit
        override suspend fun toggleMutedCategory(categorySlug: String) = Unit
        override suspend fun setMutedCategories(categorySlugs: Set<String>) = Unit
        override suspend fun replaceConfig(config: ContentFilterConfig) = Unit
        override suspend fun mergeConfig(config: ContentFilterConfig) = Unit
    }
}
