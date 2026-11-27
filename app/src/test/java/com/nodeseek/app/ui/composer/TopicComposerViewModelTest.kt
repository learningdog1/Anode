package com.nodeseek.app.ui.composer

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.PollDetail
import com.nodeseek.app.domain.model.PollOption
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.StardustTransferPreview
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.PollAndStardustRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Test
    fun `onPublishClicked succeeds without active uiState collector including inside coin confirm`() = runTest {
        val forum = FakeComposerForumRepository()
        val session = FakeComposerSessionRepository().apply {
            sessionState.value = AccountSession(uid = 7L, username = "poster")
        }
        val drafts = InMemoryComposerDraftRepository()

        // 1. Standard board without any uiState collector
        val vm = TopicComposerViewModel(
            container = buildContainer(forum, session, drafts),
            initialCategorySlug = "daily",
            initialTitle = "无 collector 发帖标题",
            initialMarkdown = "无 collector 发帖正文",
        )
        vm.onPublishClicked()
        assertEquals(1, forum.createCount)

        // 2. Inside board (costs 5 coins) without any uiState collector -> prompts coin confirm, then publishes on confirm
        val insideVm = TopicComposerViewModel(
            container = buildContainer(forum, session, drafts),
            initialCategorySlug = "inside",
            initialTitle = "内版帖子标题",
            initialMarkdown = "内版帖子正文",
        )
        insideVm.onPublishClicked()
        assertEquals("Inside board must still trigger coin confirmation first", 1, forum.createCount)
        insideVm.confirmCoinSpendAndPublish()
        assertEquals(2, forum.createCount)
    }

    @Test
    fun `double-tapping onPublishClicked or confirmCoinSpendAndPublish before dispatcher yield triggers only one write`() = runTest {
        val stdDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(stdDispatcher)

        val createGate = CompletableDeferred<Result<Unit>>()
        val editGate = CompletableDeferred<Result<Unit>>()
        val forum = FakeComposerForumRepository().apply {
            createHandler = { createGate.await() }
            editHandler = { editGate.await() }
            detailHandler = { id, _ ->
                Result.success(
                    TopicDetailPage(
                        topicId = id,
                        title = "原帖标题",
                        categorySlug = "tech",
                        categoryName = "技术",
                        author = FloorAuthor(uid = 7L, username = "poster"),
                        currentPage = 1,
                        totalPages = 1,
                        requiredViewLevel = 0,
                        openingFloor = FloorComment(
                            commentId = 1L,
                            floorNumber = 0,
                            author = FloorAuthor(uid = 7L, username = "poster"),
                            createdAtIso = "2026-11-27T00:00:00Z",
                            markdownBody = "原帖正文",
                        ),
                    ),
                )
            }
        }
        val session = FakeComposerSessionRepository().apply {
            sessionState.value = AccountSession(uid = 7L, username = "poster")
        }
        val drafts = InMemoryComposerDraftRepository()

        // 1. New topic double-tap before yield
        val createVm = TopicComposerViewModel(
            container = buildContainer(forum, session, drafts),
            initialCategorySlug = "daily",
            initialTitle = "防连点标题",
            initialMarkdown = "防连点正文",
        )
        advanceUntilIdle()
        createVm.onPublishClicked()
        createVm.onPublishClicked()
        testScheduler.runCurrent()
        assertEquals("Rapid double-tap onPublishClicked must invoke createTopic only once", 1, forum.createCount)
        createGate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertEquals(1, forum.createCount)

        // 2. Edit topic confirmCoinSpendAndPublish double-tap before yield
        val editVm = TopicComposerViewModel(
            container = buildContainer(forum, session, drafts),
            editTopicId = 55L,
        )
        advanceUntilIdle()
        editVm.selectRequiredViewLevel(255)
        editVm.onPublishClicked()
        editVm.confirmCoinSpendAndPublish()
        editVm.confirmCoinSpendAndPublish()
        testScheduler.runCurrent()
        assertEquals("Rapid double-tap confirmCoinSpendAndPublish must invoke editTopic only once", 1, forum.editCount)
        editGate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertEquals(1, forum.editCount)
    }

    @Test
    fun `double-tapping createPollAndInsert before dispatcher yield triggers only one createVote and resets on failure`() = runTest {
        val stdDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(stdDispatcher)

        val pollGate = CompletableDeferred<Result<PollDetail>>()
        var createVoteCount = 0
        var shouldFail = true
        val pollRepo = object : PollAndStardustRepository {
            override suspend fun getVote(voteId: Long): Result<PollDetail> = Result.failure(UnsupportedOperationException())
            override suspend fun createVote(
                title: String,
                multiple: Boolean,
                isPublic: Boolean,
                options: List<String>,
            ): Result<PollDetail> {
                createVoteCount++
                return if (createVoteCount == 1) {
                    pollGate.await()
                } else if (shouldFail) {
                    Result.failure(RuntimeException("fail"))
                } else {
                    Result.success(
                        PollDetail(
                            id = 777L,
                            title = title,
                            creatorUid = 7L,
                            isPublic = isPublic,
                            isLocked = false,
                            isMultiple = multiple,
                            options = options.mapIndexed { idx, opt ->
                                PollOption(voteItemId = idx.toLong(), text = opt, count = 0)
                            },
                        ),
                    )
                }
            }
            override suspend fun castVote(voteId: Long, voteItemIds: List<Long>): Result<PollDetail> = Result.failure(UnsupportedOperationException())
            override suspend fun lockVote(voteId: Long): Result<PollDetail> = Result.failure(UnsupportedOperationException())
            override suspend fun prepareStardustTransfer(receiverId: Long): Result<StardustTransferPreview> = Result.failure(UnsupportedOperationException())
            override suspend fun sendStardust(memberId: Long, amount: Int, refId: Long): Result<Unit> = Result.failure(UnsupportedOperationException())
        }

        val session = FakeComposerSessionRepository().apply {
            sessionState.value = AccountSession(uid = 7L, username = "poster")
        }
        val vm = TopicComposerViewModel(
            container = buildContainer(FakeComposerForumRepository(), session, InMemoryComposerDraftRepository()),
            pollAndStardustRepositoryOverride = pollRepo,
        )
        advanceUntilIdle()

        vm.createPollAndInsert("选哪个", multiple = false, isPublic = true, options = listOf("A", "B"))
        vm.createPollAndInsert("选哪个", multiple = false, isPublic = true, options = listOf("A", "B"))
        testScheduler.runCurrent()
        assertEquals("Rapid double-tap createPollAndInsert must invoke createVote only once", 1, createVoteCount)

        // Fail first attempt, ensure guard resets so retry works
        pollGate.complete(Result.failure(RuntimeException("temporary error")))
        advanceUntilIdle()
        shouldFail = false
        vm.createPollAndInsert("选哪个", multiple = false, isPublic = true, options = listOf("A", "B"))
        advanceUntilIdle()
        assertEquals(2, createVoteCount)
    }

    @Test
    fun `selectCategory rejects isAdminOnly category and onPublishClicked does not publish to admin board without uiState collector`() = runTest {
        val adminCat = Category(slug = "notice", name = "公告", isAdminOnly = true)
        val forum = FakeComposerForumRepository().apply {
            categoriesFlow.value = Category.DEFAULT_CATEGORIES + adminCat
        }
        val session = FakeComposerSessionRepository().apply {
            sessionState.value = AccountSession(uid = 7L, username = "poster")
        }
        val drafts = InMemoryComposerDraftRepository()

        // 1. Headless composer (zero uiState collectors): selectCategory("notice") must be rejected
        val vm = TopicComposerViewModel(
            container = buildContainer(forum, session, drafts),
            initialCategorySlug = "daily",
            initialTitle = "常规发帖",
            initialMarkdown = "正文内容",
        )
        vm.selectCategory("notice")
        vm.onPublishClicked()
        assertEquals(1, forum.createCount)
        assertEquals("daily", forum.lastCreatedCategorySlug)

        // 2. Even if initialCategorySlug targets an admin-only board headlessly, publishing is blocked
        val directAdminVm = TopicComposerViewModel(
            container = buildContainer(forum, session, drafts),
            initialCategorySlug = "notice",
            initialTitle = "越权公告",
            initialMarkdown = "越权正文",
        )
        directAdminVm.onPublishClicked()
        assertEquals("Publishing directly to an isAdminOnly category must be blocked", 1, forum.createCount)
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
        var lastCreatedCategorySlug: String? = null
        var editCount = 0
        val categoriesFlow = MutableStateFlow(Category.DEFAULT_CATEGORIES)
        var createHandler: (suspend () -> Result<Unit>)? = null
        var editHandler: (suspend () -> Result<Unit>)? = null
        var detailHandler: (suspend (Long, Int) -> Result<TopicDetailPage>)? = null
        override fun observeCategories(): Flow<List<Category>> = categoriesFlow
        override suspend fun refreshCategories(force: Boolean): Result<List<Category>> =
            Result.success(categoriesFlow.value)

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
            detailHandler?.invoke(topicId, page) ?: Result.failure(UnsupportedOperationException())

        override suspend fun createTopic(
            categorySlug: String,
            title: String,
            markdownContent: String,
            requiredViewLevel: Int,
        ): Result<Unit> {
            createCount++
            lastCreatedCategorySlug = categorySlug
            return createHandler?.invoke() ?: Result.success(Unit)
        }

        override suspend fun editTopic(
            topicId: Long,
            title: String,
            markdownContent: String,
            requiredViewLevel: Int,
        ): Result<Unit> {
            editCount++
            return editHandler?.invoke() ?: Result.success(Unit)
        }

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
