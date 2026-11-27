package com.nodeseek.app.ui.explore

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreCategoryReorderTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `buildOrderedTabs respects custom order including permutation of defaults and hides non-visible boards`() {
        val categories = listOf(
            Category(slug = "", name = "全部", isCustomizable = false, visibleOnHome = true),
            Category(slug = "daily", name = "日常", isCustomizable = true, visibleOnHome = true),
            Category(slug = "tech", name = "技术", isCustomizable = true, visibleOnHome = true),
            Category(slug = "info", name = "情报", isCustomizable = true, visibleOnHome = false),
            Category(slug = "review", name = "测评", isCustomizable = true, visibleOnHome = true),
            Category(slug = "trade", name = "交易", isCustomizable = true, visibleOnHome = true),
        )

        // Reordered permutation of default 5 slugs + info hidden
        val customOrder = listOf("trade", "review", "daily", "tech", "info")
        val tabs = ExploreViewModel.buildOrderedTabs(
            categories = categories,
            homepageCategoryOrder = customOrder,
            pinnedCategorySlugs = AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS,
            selectedCategorySlug = "",
        )

        assertEquals(listOf("", "trade", "review", "daily", "tech"), tabs.map { it.slug })

        // Externally selected hidden board remains temporarily reachable while active
        val withSelectedHidden = ExploreViewModel.buildOrderedTabs(
            categories = categories,
            homepageCategoryOrder = customOrder,
            pinnedCategorySlugs = AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS,
            selectedCategorySlug = "info",
        )
        assertEquals(listOf("", "trade", "review", "daily", "tech", "info"), withSelectedHidden.map { it.slug })
    }

    @Test
    fun `saveCategoryPreferences updates tabs, resets hidden active tab to All, and handles guest vs signed-in`() = runTest {
        val settingsRepo = FakeSettingsRepository()
        val forumRepo = ReorderFakeForumRepository(settingsRepo)
        val sessionRepo = ReorderFakeSessionRepository()

        val vm = ExploreViewModel(
            forumRepository = forumRepo,
            sessionRepository = sessionRepo,
            bookmarkAndHistoryRepository = EmptyBookmarkRepo(),
            settingsRepository = settingsRepo,
            contentFilterRepository = EmptyFilterRepo(),
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        // Select "trade", then hide "trade" and move "review" first
        vm.selectCategory("trade")
        val reordered = listOf(
            Category(slug = "review", name = "测评", isCustomizable = true, visibleOnHome = true),
            Category(slug = "tech", name = "技术", isCustomizable = true, visibleOnHome = true),
            Category(slug = "trade", name = "交易", isCustomizable = true, visibleOnHome = false),
        )

        // Guest save -> syncToServer = false
        vm.saveCategoryPreferences(reordered)
        val afterGuestSave = vm.uiState.first {
            it.orderedBoardTabs.getOrNull(1)?.slug == "review" && it.selectedCategorySlug == ""
        }
        assertEquals("", afterGuestSave.selectedCategorySlug)
        assertFalse(afterGuestSave.orderedBoardTabs.any { it.slug == "trade" })
        assertEquals(listOf(false), forumRepo.syncToServerCalls)

        // Signed-in save with remote failure still preserves local order and sets snackbar
        sessionRepo.sessionState.value = AccountSession(uid = 101L, username = "Alice")
        forumRepo.nextSaveResult = Result.failure(IllegalStateException("network timeout"))
        vm.saveCategoryPreferences(
            listOf(
                Category(slug = "tech", name = "技术", isCustomizable = true, visibleOnHome = true),
                Category(slug = "review", name = "测评", isCustomizable = true, visibleOnHome = true),
            ),
        )
        val afterFailedSync = vm.uiState.first {
            it.snackbarMessage?.contains("已保存本机顺序") == true
        }
        assertEquals("tech", afterFailedSync.orderedBoardTabs[1].slug)
        assertEquals(listOf(false, true), forumRepo.syncToServerCalls)

        // Reset restores default order and visibility
        forumRepo.nextSaveResult = Result.success(Unit)
        vm.resetCategoryPreferences()
        val afterReset = vm.uiState.first {
            it.homepageCategoryOrder.isEmpty() && it.orderedBoardTabs[1].slug == "tech"
        }
        assertTrue(afterReset.orderedBoardTabs.any { it.slug == "trade" })

        collectJob.cancel()
    }

    @Test
    fun `buildOrderedTabs excludes unknown slugs in homepageCategoryOrder and resetCategoryPreferences clears legacy pinnedCategorySlugs`() = runTest {
        val categories = listOf(
            Category(slug = "", name = "全部", isCustomizable = false, visibleOnHome = true),
            Category(slug = "daily", name = "日常", isCustomizable = true, visibleOnHome = true),
            Category(slug = "tech", name = "技术", isCustomizable = true, visibleOnHome = true),
            Category(slug = "info", name = "情报", isCustomizable = true, visibleOnHome = true),
            Category(slug = "review", name = "测评", isCustomizable = true, visibleOnHome = true),
            Category(slug = "trade", name = "交易", isCustomizable = true, visibleOnHome = true),
            Category(slug = "dev", name = "Dev", isCustomizable = true, visibleOnHome = true),
        )
        val dirtyOrder = listOf("", "   ", " tech ", "tech", "unknown_bogus_slug", "review", "  review ")
        val tabs = ExploreViewModel.buildOrderedTabs(
            categories = categories,
            homepageCategoryOrder = dirtyOrder,
            pinnedCategorySlugs = AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS,
            selectedCategorySlug = "",
        )
        val reorderList = ExploreViewModel.buildCustomizableCategoriesForReorder(
            categories = categories,
            homepageCategoryOrder = dirtyOrder,
            pinnedCategorySlugs = AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS,
        )
        assertEquals(listOf("", "tech", "review", "daily", "info", "trade", "dev"), tabs.map { it.slug })
        assertFalse(tabs.any { it.slug == "unknown_bogus_slug" })
        assertEquals(listOf("tech", "review", "daily", "info", "trade", "dev"), reorderList.map { it.slug })

        // Legacy pinnedCategorySlugs from backup is also reset when resetCategoryPreferences() is called
        val settingsRepo = FakeSettingsRepository().apply {
            setPinnedCategorySlugs(linkedSetOf("trade", "daily"))
        }
        val forumRepo = ReorderFakeForumRepository(settingsRepo)
        val sessionRepo = ReorderFakeSessionRepository()
        val vm = ExploreViewModel(
            forumRepository = forumRepo,
            sessionRepository = sessionRepo,
            bookmarkAndHistoryRepository = EmptyBookmarkRepo(),
            settingsRepository = settingsRepo,
            contentFilterRepository = EmptyFilterRepo(),
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        vm.resetCategoryPreferences()
        val afterReset = vm.uiState.first {
            it.homepageCategoryOrder.isEmpty() && it.pinnedCategorySlugs == AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS
        }
        assertEquals("tech", afterReset.orderedBoardTabs[1].slug)
        collectJob.cancel()
    }

    @Test
    fun `syncHomepageCategories_midFlightAccountSwitch_triggersNewAccountSync`() = runTest {
        val settingsRepo = FakeSettingsRepository()
        val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val syncedUids = mutableListOf<Long?>()
        val sessionRepo = ReorderFakeSessionRepository().apply {
            sessionState.value = AccountSession(uid = 101L, username = "Alice")
        }
        val forumRepo = object : ForumRepository by ReorderFakeForumRepository(settingsRepo) {
            override suspend fun syncHomepageCategories(): Result<List<Category>> {
                val uid = sessionRepo.sessionState.value?.uid
                syncedUids += uid
                if (uid == 101L && syncedUids.count { it == 101L } == 1) {
                    firstGate.await()
                    return Result.failure(IllegalStateException("账号会话已切换"))
                }
                return Result.success(Category.DEFAULT_CATEGORIES)
            }
        }
        val vm = ExploreViewModel(
            forumRepository = forumRepo,
            sessionRepository = sessionRepo,
            bookmarkAndHistoryRepository = EmptyBookmarkRepo(),
            settingsRepository = settingsRepo,
            contentFilterRepository = EmptyFilterRepo(),
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        vm.syncHomepageCategories()
        assertTrue(vm.uiState.value.isSyncingCategories)

        // Mid-flight account switch to uid=202 while uid=101 sync is still suspended
        sessionRepo.sessionState.value = AccountSession(uid = 202L, username = "Bob")
        firstGate.complete(Unit)

        assertTrue(202L in syncedUids)
        assertFalse(vm.uiState.value.isSyncingCategories)

        collectJob.cancel()
    }

    @Test
    fun `resetCategoryPreferences_failedServerSync_handlesLoadingFlagSafely`() = runTest {
        val settingsRepo = FakeSettingsRepository().apply {
            setHomepageCategoryOrder(listOf("trade", "tech"))
        }
        val sessionRepo = ReorderFakeSessionRepository().apply {
            sessionState.value = AccountSession(uid = 101L, username = "Alice")
        }
        val forumRepo = object : ForumRepository by ReorderFakeForumRepository(settingsRepo) {
            override suspend fun saveHomepageCategories(
                orderedCategories: List<Category>,
                syncToServer: Boolean,
            ): Result<Unit> = Result.failure(RuntimeException("network timeout"))
        }
        val vm = ExploreViewModel(
            forumRepository = forumRepo,
            sessionRepository = sessionRepo,
            bookmarkAndHistoryRepository = EmptyBookmarkRepo(),
            settingsRepository = settingsRepo,
            contentFilterRepository = EmptyFilterRepo(),
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        vm.resetCategoryPreferences()
        assertFalse(vm.uiState.value.isSavingCategories)

        collectJob.cancel()
    }

    @Test
    fun `resetCategoryPreferences_headlessWithoutUiStateCollector_retainsCustomServerCategories`() = runTest(testDispatcher) {
        val customCategories = Category.DEFAULT_CATEGORIES + Category(
            slug = "custom_board",
            name = "自定义版块",
            description = "Server synced board",
            isCustomizable = true,
            visibleOnHome = true,
        )
        val settingsRepo = FakeSettingsRepository().apply {
            setHomepageCategoryOrder(listOf("trade", "tech"))
        }
        val sessionRepo = ReorderFakeSessionRepository()
        val savedBatches = mutableListOf<List<Category>>()
        val delayedCategoriesFlow = flow {
            delay(50L)
            emit(customCategories)
        }
        val forumRepo = object : ForumRepository by ReorderFakeForumRepository(settingsRepo) {
            override fun observeCategories(): Flow<List<Category>> = delayedCategoriesFlow

            override suspend fun saveHomepageCategories(
                orderedCategories: List<Category>,
                syncToServer: Boolean,
            ): Result<Unit> {
                savedBatches += orderedCategories
                return Result.success(Unit)
            }
        }

        // Zero uiState collectors; resetCategoryPreferences() called immediately before 50ms cold Flow emits.
        val vm = ExploreViewModel(
            forumRepository = forumRepo,
            sessionRepository = sessionRepo,
            bookmarkAndHistoryRepository = EmptyBookmarkRepo(),
            settingsRepository = settingsRepo,
            contentFilterRepository = EmptyFilterRepo(),
        )

        vm.resetCategoryPreferences()
        advanceUntilIdle()

        assertEquals(1, savedBatches.size)
        assertTrue(
            "resetCategoryPreferences() must retain custom/server categories from observeCategories()",
            savedBatches.single().any { it.slug == "custom_board" },
        )
    }

    @Test
    fun `toggleBookmark_headlessWithoutUiStateCollector_removesExistingBookmarkFromStateFlowAndDelayedColdFlow`() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository()
        val sessionRepo = ReorderFakeSessionRepository()
        val forumRepo = ReorderFakeForumRepository(settingsRepo)

        // 1. StateFlow-backed bookmark repo with zero uiState collectors
        val savedIdsStateFlow = mutableListOf<Long>()
        val removedIdsStateFlow = mutableListOf<Long>()
        val stateFlowBookmarkRepo = object : BookmarkAndHistoryRepository by EmptyBookmarkRepo() {
            private val ids = MutableStateFlow(setOf(777L))
            override fun observeBookmarkedIds(): Flow<Set<Long>> = ids.asStateFlow()
            override suspend fun saveOfflineBookmark(
                summary: TopicSummary,
                detailSnapshot: TopicDetailPage?,
                syncedWithServer: Boolean,
            ) {
                savedIdsStateFlow += summary.id
                ids.value = ids.value + summary.id
            }

            override suspend fun removeLocalBookmark(topicId: Long) {
                removedIdsStateFlow += topicId
                ids.value = ids.value - topicId
            }
        }

        val vmStateFlow = ExploreViewModel(
            forumRepository = forumRepo,
            sessionRepository = sessionRepo,
            bookmarkAndHistoryRepository = stateFlowBookmarkRepo,
            settingsRepository = settingsRepo,
            contentFilterRepository = EmptyFilterRepo(),
        )
        vmStateFlow.toggleBookmark(
            TopicSummary(
                id = 777L,
                title = "Bookmarked topic",
                categorySlug = "tech",
                categoryName = "技术",
                authorName = "alice",
                isBookmarked = false,
            ),
        )
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), savedIdsStateFlow)
        assertEquals(listOf(777L), removedIdsStateFlow)

        // 2. Delayed cold Room Flow (zero uiState collectors, toggled before 50ms initial emission)
        val savedIdsColdFlow = mutableListOf<Long>()
        val removedIdsColdFlow = mutableListOf<Long>()
        val delayedColdBookmarkRepo = object : BookmarkAndHistoryRepository by EmptyBookmarkRepo() {
            override fun observeBookmarkedIds(): Flow<Set<Long>> = flow {
                delay(50L)
                emit(setOf(888L))
            }

            override suspend fun saveOfflineBookmark(
                summary: TopicSummary,
                detailSnapshot: TopicDetailPage?,
                syncedWithServer: Boolean,
            ) {
                savedIdsColdFlow += summary.id
            }

            override suspend fun removeLocalBookmark(topicId: Long) {
                removedIdsColdFlow += topicId
            }
        }

        val vmColdFlow = ExploreViewModel(
            forumRepository = forumRepo,
            sessionRepository = sessionRepo,
            bookmarkAndHistoryRepository = delayedColdBookmarkRepo,
            settingsRepository = settingsRepo,
            contentFilterRepository = EmptyFilterRepo(),
        )
        vmColdFlow.toggleBookmark(
            TopicSummary(
                id = 888L,
                title = "Delayed cold Room bookmark",
                categorySlug = "tech",
                categoryName = "技术",
                authorName = "bob",
                isBookmarked = false,
            ),
        )
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), savedIdsColdFlow)
        assertEquals(listOf(888L), removedIdsColdFlow)
    }

    private class ReorderFakeForumRepository(
        private val settingsRepo: FakeSettingsRepository,
    ) : ForumRepository {
        private val categoriesFlow = MutableStateFlow(Category.DEFAULT_CATEGORIES)
        val syncToServerCalls = mutableListOf<Boolean>()
        var nextSaveResult: Result<Unit> = Result.success(Unit)

        override fun observeCategories(): Flow<List<Category>> = categoriesFlow

        override suspend fun refreshCategories(force: Boolean): Result<List<Category>> =
            Result.success(categoriesFlow.value)

        override suspend fun syncHomepageCategories(): Result<List<Category>> =
            Result.success(categoriesFlow.value)

        override suspend fun saveHomepageCategories(
            orderedCategories: List<Category>,
            syncToServer: Boolean,
        ): Result<Unit> {
            syncToServerCalls += syncToServer
            settingsRepo.setHomepageCategoryOrder(orderedCategories.map { it.slug })
            val bySlug = orderedCategories.associateBy { it.slug }
            categoriesFlow.value = categoriesFlow.value.map { existing ->
                val updated = bySlug[existing.slug]
                if (updated != null) existing.copy(visibleOnHome = updated.visibleOnHome) else existing
            }
            return nextSaveResult
        }

        override suspend fun getFeed(
            categorySlug: String,
            sort: FeedSort,
            page: Int,
            includeBlocked: Boolean,
            forceRefresh: Boolean,
        ): Result<FeedPageResult> = Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun searchTopics(query: String, categorySlug: String, sort: FeedSort, page: Int) =
            Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> =
            Result.failure(UnsupportedOperationException())

        override suspend fun createTopic(categorySlug: String, title: String, markdownContent: String, requiredViewLevel: Int) =
            Result.success(Unit)

        override suspend fun editTopic(topicId: Long, title: String, markdownContent: String, requiredViewLevel: Int) =
            Result.success(Unit)

        override suspend fun postReply(topicId: Long, markdownContent: String) = Result.success(Unit)
        override suspend fun editReply(commentId: Long, markdownContent: String) = Result.success(Unit)
        override suspend fun setReplyPinned(commentId: Long, pinned: Boolean) = Result.success(Unit)
        override suspend fun getFreeDrumstickCost() = Result.success(0)
        override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType) =
            Result.success(ReactionResult(1))
    }

    private class ReorderFakeSessionRepository : SessionRepository {
        override val sessionState = MutableStateFlow<AccountSession?>(null)
        override val challengeRequired = MutableStateFlow(false)
        override val authVerifiedEpoch = MutableStateFlow(0L)
        override suspend fun refreshSession(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signIn(username: String, password: String, turnstileToken: String) =
            Result.success(SignInStepResult.Authenticated)
        override suspend fun verifyTwoFactor(otpCode: String, otpSession: String) = Result.success(Unit)
        override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signOut(): Result<Unit> = Result.success(Unit)
    }

    private class EmptyBookmarkRepo : BookmarkAndHistoryRepository {
        override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean) = Result.success(Unit)
        override suspend fun getServerBookmarks(page: Int) = Result.success(emptyList<TopicSummary>() to false)
        override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flowOf(emptyList())
        override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
        override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = flowOf(emptyList())
        override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> = flowOf(emptyMap())
        override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
        override suspend fun clearReadHistory() = Unit
    }

    private class EmptyFilterRepo : ContentFilterRepository {
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
}
