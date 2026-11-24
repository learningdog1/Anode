package com.nodeseek.app.ui.filter

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterEngine
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
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.ui.boards.BoardsViewModel
import com.nodeseek.app.ui.explore.ExploreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedContentFilterViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun topic(
        id: Long,
        title: String,
        slug: String,
        name: String,
    ) = TopicSummary(
        id = id,
        title = title,
        categorySlug = slug,
        categoryName = name,
        authorName = "author_$id",
    )

    @Test
    fun `ExploreViewModel reactively filters pinned and regular topics per board slice`() = runTest {
        val pinnedAll = listOf(
            topic(1L, "[置顶] 欢迎使用 Anode", "daily", "日常"),
            topic(2L, "[置顶] 商家推广活动汇总", "promotion", "推广"),
        )
        val allTopics = listOf(
            topic(10L, "[求购] RackNerd 圣何塞小鸡", "trade", "交易"),
            topic(11L, "DMIT 三网 BGP 测评", "review", "测评"),
            topic(12L, "某主机商五折优惠", "promotion", "推广"),
            topic(13L, "限时促销附带 AFF 返利", "promotion", "推广"),
        )
        val promoTopics = listOf(
            topic(12L, "某主机商五折优惠", "promotion", "推广"),
            topic(13L, "限时促销附带 AFF 返利", "promotion", "推广"),
        )

        val forumRepo = FakeForumRepository(
            feedsByCategory = mapOf(
                "" to FeedPageResult(pinnedTopics = pinnedAll, topics = allTopics),
                "promotion" to FeedPageResult(pinnedTopics = emptyList(), topics = promoTopics),
            ),
        )
        val settingsRepo = FakeSettingsRepository()
        val filterRepo = FakeContentFilterRepository(
            ContentFilterConfig(
                isEnabled = true,
                mutedCategorySlugs = setOf("promotion"),
                rules = listOf(
                    ContentFilterEngine.CURATED_PRESETS[0].toRule(), // 收 / 求购
                    ContentFilterEngine.CURATED_PRESETS[1].toRule(), // AFF / 推广
                ),
            ),
        )

        val viewModel = ExploreViewModel(
            forumRepository = forumRepo,
            sessionRepository = FakeSessionRepository(),
            bookmarkAndHistoryRepository = FakeBookmarkAndHistoryRepository(),
            settingsRepository = settingsRepo,
            contentFilterRepository = filterRepo,
        )

        val collectJob = launch(testDispatcher) {
            viewModel.uiState.collect {}
        }

        // On "全部" ("") tab: muted "promotion" (2, 12, 13) AND "求购" (10) are hidden
        val allState = viewModel.uiState.first { !it.isInitialLoading }
        assertEquals(listOf(1L), allState.pinnedTopics.map { it.id })
        assertEquals(listOf(11L), allState.topics.map { it.id })

        // Explicitly navigating into "promotion" tab shows non-keyword-blocked promo (12),
        // while still blocking AFF post (13)
        viewModel.selectCategory("promotion")
        val promoState = viewModel.uiState.first { it.selectedCategorySlug == "promotion" && !it.isInitialLoading }
        assertEquals(listOf(12L), promoState.topics.map { it.id })

        // Removing AFF rule immediately reveals post 13 reactively
        filterRepo.removeRule(ContentFilterEngine.CURATED_PRESETS[1].id)
        assertEquals(listOf(12L, 13L), viewModel.uiState.value.topics.map { it.id })

        // Switching back to "全部" and turning includeBlockedTopics = true disables content filter bypass
        viewModel.selectCategory("")
        assertEquals(listOf(11L), viewModel.uiState.value.topics.map { it.id })

        settingsRepo.setIncludeBlockedTopics(true)
        val bypassedState = viewModel.uiState.first { it.topics.size == 4 }
        assertEquals(listOf(10L, 11L, 12L, 13L), bypassedState.topics.map { it.id })
        assertEquals(listOf(1L, 2L), bypassedState.pinnedTopics.map { it.id })

        collectJob.cancel()
    }

    @Test
    fun `BoardsViewModel reactively filters search results`() = runTest {
        val searchTopics = listOf(
            topic(100L, "[求购] Claw JP", "trade", "交易"),
            topic(101L, "Claw JP 晚高峰测评", "review", "测评"),
            topic(102L, "Claw 商家新年促销", "promotion", "推广"),
            topic(103L, "Claw 优惠附带 AFF", "promotion", "推广"),
        )

        val forumRepo = FakeForumRepository(
            searchResults = FeedPageResult(topics = searchTopics),
        )
        val settingsRepo = FakeSettingsRepository()
        val filterRepo = FakeContentFilterRepository(
            ContentFilterConfig(
                isEnabled = true,
                mutedCategorySlugs = setOf("promotion"),
                rules = listOf(
                    ContentBlockRule(
                        id = "buy",
                        pattern = "求购",
                        matchMode = KeywordMatchMode.CONTAINS,
                    ),
                    ContentBlockRule(
                        id = "aff",
                        pattern = """\baff\b""",
                        matchMode = KeywordMatchMode.REGEX,
                    ),
                ),
            ),
        )

        val viewModel = BoardsViewModel(
            forumRepository = forumRepo,
            userRepository = FakeUserRepository(),
            sessionRepository = FakeSessionRepository(),
            bookmarkAndHistoryRepository = FakeBookmarkAndHistoryRepository(),
            settingsRepository = settingsRepo,
            contentFilterRepository = filterRepo,
        )

        val collectJob = launch(testDispatcher) {
            viewModel.uiState.collect {}
        }

        // Global search hides muted "promotion" (102, 103) AND "求购" (100)
        viewModel.submitSearch("Claw")
        val searchState = viewModel.uiState.first { it.submittedQuery == "Claw" && !it.isSearching }
        assertEquals(listOf(101L), searchState.searchTopics.map { it.id })

        // Disabling content filter reactively restores all search results
        filterRepo.setEnabled(false)
        assertEquals(listOf(100L, 101L, 102L, 103L), viewModel.uiState.value.searchTopics.map { it.id })

        // Re-enabling filter and turning on includeBlockedTopics bypasses filter reactively
        filterRepo.setEnabled(true)
        assertEquals(listOf(101L), viewModel.uiState.first { it.searchTopics.size == 1 }.searchTopics.map { it.id })
        settingsRepo.setIncludeBlockedTopics(true)
        assertEquals(listOf(100L, 101L, 102L, 103L), viewModel.uiState.first { it.searchTopics.size == 4 }.searchTopics.map { it.id })

        collectJob.cancel()
    }
}

private class FakeContentFilterRepository(
    initial: ContentFilterConfig = ContentFilterConfig(),
) : ContentFilterRepository {
    private val _config = MutableStateFlow(ContentFilterEngine.normalizeConfig(initial))
    override val config: StateFlow<ContentFilterConfig> = _config.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        _config.update { it.copy(isEnabled = enabled) }
    }

    override suspend fun upsertRule(rule: ContentBlockRule) {
        _config.update { ContentFilterEngine.upsertRule(it, rule) }
    }

    override suspend fun removeRule(ruleId: String) {
        _config.update { ContentFilterEngine.removeRule(it, ruleId) }
    }

    override suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        _config.update { ContentFilterEngine.setRuleEnabled(it, ruleId, enabled) }
    }

    override suspend fun toggleMutedCategory(categorySlug: String) {
        _config.update { ContentFilterEngine.toggleMutedCategory(it, categorySlug) }
    }

    override suspend fun setMutedCategories(categorySlugs: Set<String>) {
        _config.update { ContentFilterEngine.setMutedCategories(it, categorySlugs) }
    }

    override suspend fun replaceConfig(config: ContentFilterConfig) {
        _config.value = ContentFilterEngine.normalizeConfig(config)
    }

    override suspend fun mergeConfig(config: ContentFilterConfig) {
        _config.update { ContentFilterEngine.mergeConfigs(it, config) }
    }
}

private class FakeForumRepository(
    private val feedsByCategory: Map<String, FeedPageResult> = emptyMap(),
    private val searchResults: FeedPageResult = FeedPageResult(topics = emptyList()),
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
    ): Result<FeedPageResult> = Result.success(
        feedsByCategory[categorySlug] ?: FeedPageResult(topics = emptyList()),
    )

    override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
        Result.success(feedsByCategory[""] ?: FeedPageResult(topics = emptyList()))

    override suspend fun searchTopics(
        query: String,
        categorySlug: String,
        sort: FeedSort,
        page: Int,
    ): Result<FeedPageResult> = Result.success(searchResults)

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

private class FakeSessionRepository : SessionRepository {
    override val sessionState: StateFlow<AccountSession?> = MutableStateFlow(null)
    override val challengeRequired: StateFlow<Boolean> = MutableStateFlow(false)
    override val authVerifiedEpoch: StateFlow<Long> = MutableStateFlow(0L)
    override suspend fun refreshSession(): Result<AccountSession?> = Result.success(null)
    override suspend fun signIn(username: String, password: String, turnstileToken: String): Result<SignInStepResult> =
        Result.success(SignInStepResult.Authenticated)
    override suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit> = Result.success(Unit)
    override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(null)
    override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(null)
    override suspend fun signOut(): Result<Unit> = Result.success(Unit)
}

private class FakeBookmarkAndHistoryRepository : BookmarkAndHistoryRepository {
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

private class FakeUserRepository : UserRepository {
    override suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean): Result<UserProfile> =
        Result.failure(UnsupportedOperationException())
    override suspend fun updateOwnProfile(bio: String, signature: String, readme: String): Result<Unit> =
        Result.success(Unit)
    override suspend fun getUserTopics(uid: Long, page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
        Result.success(emptyList<TopicSummary>() to false)
    override suspend fun getUserReplies(uid: Long, page: Int): Result<Pair<List<UserReplyExcerpt>, Boolean>> =
        Result.success(emptyList<UserReplyExcerpt>() to false)
    override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> = Result.success(emptyList())
    override suspend fun setFollowed(targetUid: Long, follow: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun getConnections(following: Boolean): Result<List<FloorAuthor>> = Result.success(emptyList())
    override suspend fun getBlockedUsers(): Result<List<FloorAuthor>> = Result.success(emptyList())
    override suspend fun blockUserByName(username: String): Result<Unit> = Result.success(Unit)
    override suspend fun unblockUserById(targetUid: Long): Result<Unit> = Result.success(Unit)
}
