package com.nodeseek.app.ui.explore

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreFeedPaginationAndRssTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun topic(id: Long, slug: String, title: String = "主题 #$id") = TopicSummary(
        id = id,
        title = title,
        categorySlug = slug,
        categoryName = slug,
        authorName = "author_$id",
    )

    @Test
    fun `authVerifiedEpoch bump clears all RSS slices across tabs and reloads active board with HTML`() = runTest {
        val fakeForum = RecordingForumRepository { slug, _, page, _ ->
            if (page == 1 && slug == "review") {
                Result.success(
                    FeedPageResult(
                        topics = listOf(topic(10L, "review", "RSS 测评贴")),
                        currentPage = 1,
                        hasNextPage = false,
                        isFromRssFallback = true,
                    ),
                )
            } else {
                Result.success(
                    FeedPageResult(
                        topics = listOf(topic(1L, "", "RSS 首页贴")),
                        currentPage = 1,
                        hasNextPage = false,
                        isFromRssFallback = true,
                    ),
                )
            }
        }
        val fakeSession = MutableFakeSessionRepository().apply {
            challengeRequired.value = true
        }
        val viewModel = ExploreViewModel(
            forumRepository = fakeForum,
            sessionRepository = fakeSession,
            bookmarkAndHistoryRepository = StubBookmarkAndHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = StubContentFilterRepository(),
        )
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect {} }

        // Initial "" tab receives RSS fallback
        val initial = viewModel.uiState.first { !it.isInitialLoading }
        assertTrue(initial.isFromRssFallback)

        // Switch to "review" tab which also receives RSS fallback
        viewModel.selectCategory("review")
        val reviewRss = viewModel.uiState.first { it.selectedCategorySlug == "review" && !it.isInitialLoading }
        assertTrue(reviewRss.isFromRssFallback)
        assertEquals(listOf(10L), reviewRss.topics.map { it.id })

        // Switch repository to serve full HTML after verification
        fakeForum.handler = { slug, _, page, _ ->
            Result.success(
                FeedPageResult(
                    topics = listOf(
                        topic(if (slug == "review") 200L + page else 100L + page, slug, "完整网页贴-$slug-$page"),
                    ),
                    currentPage = page,
                    hasNextPage = true,
                    isFromRssFallback = false,
                ),
            )
        }
        fakeSession.challengeRequired.value = false
        fakeSession.authVerifiedEpoch.value = 1_000L

        val recovered = viewModel.uiState.first {
            it.selectedCategorySlug == "review" && !it.isFromRssFallback && it.hasNextPage
        }
        assertEquals(listOf(201L), recovered.topics.map { it.id })
        assertTrue(fakeForum.clearRssCacheCalls >= 1)

        // Switching back to "" must NOT reuse stale RSS cache; it must load full HTML
        viewModel.selectCategory("")
        val allRecovered = viewModel.uiState.first {
            it.selectedCategorySlug == "" && !it.isFromRssFallback && it.hasNextPage
        }
        assertEquals(listOf(101L), allRecovered.topics.map { it.id })

        collectJob.cancel()
    }

    @Test
    fun `category preview slice is cleared and surfaces error when category page 1 fetch fails`() = runTest {
        val fakeForum = RecordingForumRepository { slug, _, _, _ ->
            when (slug) {
                "" -> Result.success(
                    FeedPageResult(
                        topics = listOf(
                            topic(1L, "review", "首页里的测评预览 1"),
                            topic(2L, "review", "首页里的测评预览 2"),
                            topic(3L, "tech", "技术贴"),
                        ),
                        currentPage = 1,
                        hasNextPage = true,
                        isFromRssFallback = false,
                    ),
                )
                "review" -> Result.failure(TopicAccessException.CloudflareChallengeRequired)
                else -> Result.success(FeedPageResult(topics = emptyList()))
            }
        }
        val viewModel = ExploreViewModel(
            forumRepository = fakeForum,
            sessionRepository = MutableFakeSessionRepository(),
            bookmarkAndHistoryRepository = StubBookmarkAndHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = StubContentFilterRepository(),
        )
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect {} }

        viewModel.uiState.first { !it.isInitialLoading && it.topics.size == 3 }

        // Selecting "review" creates temporary preview from "", then real fetch fails
        viewModel.selectCategory("review")
        val failedState = viewModel.uiState.first {
            it.selectedCategorySlug == "review" && !it.isRefreshing && it.errorMessage != null
        }
        assertTrue(failedState.topics.isEmpty())
        assertTrue(failedState.isChallengeOrAuthRequired)
        assertFalse(failedState.sliceFor("review").isPreviewFromAllFeed)
        assertFalse(failedState.hasNextPage)

        collectJob.cancel()
    }

    @Test
    fun `disablePreferRssAndRefresh turns off setting, clears RSS cache, and reloads full HTML`() = runTest {
        val settingsRepo = FakeSettingsRepository()
        settingsRepo.setPreferRssFallback(true)

        val fakeForum = RecordingForumRepository { _, _, _, _ ->
            if (settingsRepo.settings.value.preferRssFallback) {
                Result.success(
                    FeedPageResult(
                        topics = listOf(topic(501L, "tech", "RSS 极速贴")),
                        currentPage = 1,
                        hasNextPage = false,
                        isFromRssFallback = true,
                    ),
                )
            } else {
                Result.success(
                    FeedPageResult(
                        topics = listOf(topic(601L, "tech", "完整网页贴 1"), topic(602L, "tech", "完整网页贴 2")),
                        currentPage = 1,
                        hasNextPage = true,
                        isFromRssFallback = false,
                    ),
                )
            }
        }

        val viewModel = ExploreViewModel(
            forumRepository = fakeForum,
            sessionRepository = MutableFakeSessionRepository(),
            bookmarkAndHistoryRepository = StubBookmarkAndHistoryRepository(),
            settingsRepository = settingsRepo,
            contentFilterRepository = StubContentFilterRepository(),
        )
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect {} }

        val rssState = viewModel.uiState.first { !it.isInitialLoading }
        assertTrue(rssState.isFromRssFallback)
        assertTrue(rssState.preferRssFallback)

        viewModel.disablePreferRssAndRefresh()

        val webState = viewModel.uiState.first { !it.isFromRssFallback && it.hasNextPage }
        assertFalse(settingsRepo.settings.value.preferRssFallback)
        assertFalse(webState.preferRssFallback)
        assertEquals(listOf(601L, 602L), webState.topics.map { it.id })
        assertTrue(fakeForum.clearRssCacheCalls >= 1)

        collectJob.cancel()
    }

    @Test
    fun `loadNextPage appends page 2 on HTML feed and blocks pagination on RSS fallback`() = runTest {
        val fakeForum = RecordingForumRepository { slug, _, page, _ ->
            when (slug) {
                "" -> Result.success(
                    FeedPageResult(
                        topics = listOf(topic(page * 10L + 1L, "tech"), topic(page * 10L + 2L, "daily")),
                        currentPage = page,
                        hasNextPage = page < 2,
                        isFromRssFallback = false,
                    ),
                )
                "review" -> Result.success(
                    FeedPageResult(
                        topics = listOf(topic(900L, "review")),
                        currentPage = 1,
                        hasNextPage = true, // Even if somehow flagged true on RSS, ViewModel blocks pagination
                        isFromRssFallback = true,
                    ),
                )
                else -> Result.success(FeedPageResult(topics = emptyList()))
            }
        }

        val viewModel = ExploreViewModel(
            forumRepository = fakeForum,
            sessionRepository = MutableFakeSessionRepository(),
            bookmarkAndHistoryRepository = StubBookmarkAndHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = StubContentFilterRepository(),
        )
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect {} }

        val page1 = viewModel.uiState.first { !it.isInitialLoading }
        assertEquals(listOf(11L, 12L), page1.topics.map { it.id })
        assertTrue(page1.hasNextPage)

        viewModel.loadNextPage("")
        val page2 = viewModel.uiState.first { it.currentPage == 2 && !it.isLoadingNextPage }
        assertEquals(listOf(11L, 12L, 21L, 22L), page2.topics.map { it.id })
        assertFalse(page2.hasNextPage)
        assertNull(page2.nextPageError)

        // RSS fallback slice refuses pagination
        viewModel.selectCategory("review")
        viewModel.uiState.first { it.selectedCategorySlug == "review" && !it.isInitialLoading }
        val requestsBefore = fakeForum.requestedPages.size
        viewModel.loadNextPage("review")
        assertEquals(requestsBefore, fakeForum.requestedPages.size)

        collectJob.cancel()
    }

    @Test
    fun `cold start session hydration avoids redundant reload while subsequent sign-out purges and reloads`() = runTest {
        val fakeSession = MutableFakeSessionRepository()
        val fakeForum = RecordingForumRepository { _, _, _, _ ->
            delay(10)
            if (fakeSession.sessionState.value == null) {
                fakeSession.sessionState.value = AccountSession(uid = 101L, username = "HydratedUser")
                Result.success(
                    FeedPageResult(
                        topics = listOf(topic(701L, "tech", "已登录首屏贴")),
                        currentPage = 1,
                        hasNextPage = true,
                        isFromRssFallback = false,
                    ),
                )
            } else {
                Result.success(
                    FeedPageResult(
                        topics = listOf(topic(702L, "tech", "重复请求贴")),
                        currentPage = 1,
                        hasNextPage = true,
                        isFromRssFallback = false,
                    ),
                )
            }
        }

        val viewModel = ExploreViewModel(
            forumRepository = fakeForum,
            sessionRepository = fakeSession,
            bookmarkAndHistoryRepository = StubBookmarkAndHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = StubContentFilterRepository(),
        )
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect {} }

        val hydrated = viewModel.uiState.first { !it.isInitialLoading }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(701L), hydrated.topics.map { it.id })
        assertEquals(1, fakeForum.requestedPages.count { it.first == "" })

        // Subsequent sign-out (101 -> null) clears cached slices and reloads active board
        fakeForum.handler = { _, _, _, _ ->
            Result.success(
                FeedPageResult(
                    topics = listOf(topic(801L, "daily", "退出登录后访客贴")),
                    currentPage = 1,
                    hasNextPage = true,
                    isFromRssFallback = false,
                ),
            )
        }
        fakeSession.sessionState.value = null
        testScheduler.advanceUntilIdle()

        val signedOut = viewModel.uiState.first { it.topics.firstOrNull()?.id == 801L }
        assertEquals(listOf(801L), signedOut.topics.map { it.id })
        assertEquals(2, fakeForum.requestedPages.count { it.first == "" })

        collectJob.cancel()
    }

    @Test
    fun `challengeRequired flow change reactively updates uiState and duplicate topic IDs are deduplicated on reset`() = runTest {
        val fakeSession = MutableFakeSessionRepository()
        val fakeForum = RecordingForumRepository { _, _, _, _ ->
            Result.success(
                FeedPageResult(
                    pinnedTopics = listOf(topic(1L, "", "置顶 A"), topic(1L, "", "置顶重复")),
                    topics = listOf(topic(1L, "", "与置顶重复"), topic(2L, "", "正常帖"), topic(2L, "", "正常帖重复")),
                    currentPage = 1,
                    hasNextPage = false,
                ),
            )
        }

        val viewModel = ExploreViewModel(
            forumRepository = fakeForum,
            sessionRepository = fakeSession,
            bookmarkAndHistoryRepository = StubBookmarkAndHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = StubContentFilterRepository(),
        )
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect {} }

        val loaded = viewModel.uiState.first { !it.isInitialLoading && it.topics.isNotEmpty() }
        assertEquals(listOf(1L), loaded.pinnedTopics.map { it.id })
        assertEquals(listOf(2L), loaded.topics.map { it.id })
        assertFalse(loaded.isChallengeOrAuthRequired)

        // Flip challengeRequired reactively without touching streamState
        fakeSession.challengeRequired.value = true
        val challenged = viewModel.uiState.first { it.isChallengeOrAuthRequired }
        assertTrue(challenged.isChallengeOrAuthRequired)

        collectJob.cancel()
    }
}

private class RecordingForumRepository(
    var handler: suspend (slug: String, sort: FeedSort, page: Int, forceRefresh: Boolean) -> Result<FeedPageResult>,
) : ForumRepository {
    var clearRssCacheCalls: Int = 0
    val requestedPages = mutableListOf<Pair<String, Int>>()

    override fun observeCategories(): Flow<List<Category>> = flowOf(Category.DEFAULT_CATEGORIES)

    override suspend fun refreshCategories(force: Boolean): Result<List<Category>> =
        Result.success(Category.DEFAULT_CATEGORIES)

    override fun clearRssFallbackCache() {
        clearRssCacheCalls += 1
    }

    override suspend fun getFeed(
        categorySlug: String,
        sort: FeedSort,
        page: Int,
        includeBlocked: Boolean,
        forceRefresh: Boolean,
    ): Result<FeedPageResult> {
        requestedPages += categorySlug to page
        return handler(categorySlug, sort, page, forceRefresh)
    }

    override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
        handler("", FeedSort.CURATED, page, forceRefresh)

    override suspend fun searchTopics(
        query: String,
        categorySlug: String,
        sort: FeedSort,
        page: Int,
    ): Result<FeedPageResult> = Result.success(FeedPageResult(topics = emptyList()))

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
    override suspend fun reactToComment(
        topicId: Long,
        commentId: Long,
        reaction: ReactionType,
    ): Result<ReactionResult> = Result.success(ReactionResult(updatedCount = 1))
}

private class MutableFakeSessionRepository : SessionRepository {
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

private class StubBookmarkAndHistoryRepository : BookmarkAndHistoryRepository {
    override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
        Result.success(emptyList<TopicSummary>() to false)
    override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
    override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = flowOf(emptyList())
    override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> =
        flowOf(emptyMap())
    override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
    override suspend fun clearReadHistory() = Unit
}

private class StubContentFilterRepository : ContentFilterRepository {
    private val _config = MutableStateFlow(ContentFilterConfig())
    override val config: StateFlow<ContentFilterConfig> = _config.asStateFlow()
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun upsertRule(rule: ContentBlockRule) = Unit
    override suspend fun removeRule(ruleId: String) = Unit
    override suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) = Unit
    override suspend fun toggleMutedCategory(categorySlug: String) = Unit
    override suspend fun setMutedCategories(categorySlugs: Set<String>) = Unit
    override suspend fun replaceConfig(config: ContentFilterConfig) = Unit
    override suspend fun mergeConfig(config: ContentFilterConfig) = Unit
}
