package com.nodeseek.app.ui.composer

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class TopicComposerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildContainer(
        forum: ForumRepository,
        session: SessionRepository,
        drafts: DraftRepository,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): AppContainer = Proxy.newProxyInstance(
        AppContainer::class.java.classLoader,
        arrayOf(AppContainer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getForumRepository" -> forum
            "getSessionRepository" -> session
            "getDraftRepository" -> drafts
            "getSettingsRepository" -> settings
            else -> throw UnsupportedOperationException("Unexpected AppContainer call: ${method.name}")
        }
    } as AppContainer

    @Test
    fun `explicit prefill persists to draft repository and restores after process death`() = runTest {
        val drafts = InMemoryComposerDraftRepository()
        val session = FakeComposerSessionRepository().apply {
            sessionState.value = AccountSession(uid = 7L, username = "bench_user")
        }
        val container = buildContainer(FakeComposerForumRepository(), session, drafts)

        // 1. First launch with prefill payload from ComposerPrefillStore
        val firstVm = TopicComposerViewModel(
            container = container,
            initialCategorySlug = "review",
            initialTitle = "[测评] 东京小鸡 YABS",
            initialMarkdown = "```ansi\nGeekbench 6\n```",
            prefillKey = "token-abc",
        )
        val job1 = launch(testDispatcher) { firstVm.uiState.collect {} }
        assertEquals("[测评] 东京小鸡 YABS", firstVm.uiState.value.title)
        job1.cancel()

        // 2. Simulated process death: ComposerPrefillStore is empty so title/markdown are blank,
        //    but prefillKey is restored from SavedState / route!
        val restoredVm = TopicComposerViewModel(
            container = container,
            initialCategorySlug = "review",
            initialTitle = "",
            initialMarkdown = "",
            prefillKey = "token-abc",
        )
        val job2 = launch(testDispatcher) { restoredVm.uiState.collect {} }
        val state = restoredVm.uiState.first { it.title.isNotEmpty() }
        assertEquals("[测评] 东京小鸡 YABS", state.title)
        assertEquals("```ansi\nGeekbench 6\n```", state.markdownContent)
        job2.cancel()
    }

    @Test
    fun `successful publish locks canSubmit and prevents duplicate topic creation`() = runTest {
        val forum = FakeComposerForumRepository()
        val session = FakeComposerSessionRepository().apply {
            sessionState.value = AccountSession(uid = 7L, username = "poster")
        }
        val drafts = InMemoryComposerDraftRepository()
        val vm = TopicComposerViewModel(
            container = buildContainer(forum, session, drafts),
            initialCategorySlug = "daily",
            initialTitle = "新帖子标题",
            initialMarkdown = "正文内容",
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.canSubmit }

        vm.onPublishClicked()
        val published = vm.uiState.first { it.hasPublished }
        assertFalse(published.canSubmit)
        assertEquals(1, forum.createCount)

        // Second tap during exit transition must be a no-op
        vm.onPublishClicked()
        assertEquals(1, forum.createCount)

        collectJob.cancel()
    }

    private class InMemoryComposerDraftRepository : DraftRepository {
        private val map = linkedMapOf<Pair<Long, String>, EditorDraft>()
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

    private class FakeComposerForumRepository : ForumRepository {
        var createCount = 0
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

        override suspend fun createTopic(
            categorySlug: String,
            title: String,
            markdownContent: String,
            requiredViewLevel: Int,
        ): Result<Unit> {
            createCount++
            return Result.success(Unit)
        }

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

    private class FakeComposerSessionRepository : SessionRepository {
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
}
