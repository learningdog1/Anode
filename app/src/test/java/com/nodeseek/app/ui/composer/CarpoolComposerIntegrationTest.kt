package com.nodeseek.app.ui.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.carpool.CarpoolBillingCycle
import com.nodeseek.app.domain.carpool.CarpoolCalculationInput
import com.nodeseek.app.domain.carpool.CarpoolCalculationOutcome
import com.nodeseek.app.domain.carpool.CarpoolCalculator
import com.nodeseek.app.domain.carpool.CarpoolMarkdownGenerator
import com.nodeseek.app.domain.carpool.CarpoolStatusUpdater
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
import com.nodeseek.app.ui.valuation.insertMarkdownBlockAtCursor
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class CarpoolComposerIntegrationTest {

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
    fun `inserting carpool table and updating title status syncs cleanly into TopicComposerViewModel`() = runTest {
        val drafts = InMemoryDraftRepo()
        val session = FakeSessionRepo(AccountSession(uid = 7L, username = "carpool_host"))
        val forum = FakeForumRepo()
        val vm = TopicComposerViewModel(
            container = buildContainer(forum, session, drafts),
            initialCategorySlug = "carpool",
            initialTitle = "Netflix 4K 土区家庭车",
            initialMarkdown = "欢迎稳定长期车友上车。",
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.selectedCategorySlug == "carpool" }

        val calc = (CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = "Netflix 4K",
                totalSeats = 4,
                occupiedSeats = 3,
                totalCycleCost = BigDecimal("240"),
                billingCycle = CarpoolBillingCycle.QUARTERLY,
            ),
        ) as CarpoolCalculationOutcome.Success).result

        val block = CarpoolMarkdownGenerator.generateMarkdown(calc)
        val before = TextFieldValue(
            text = vm.uiState.value.markdownContent,
            selection = TextRange(vm.uiState.value.markdownContent.length),
        )
        val after = insertMarkdownBlockAtCursor(before, block)

        vm.onMarkdownChanged(after.text)
        vm.onTitleChanged(CarpoolStatusUpdater.updateTitle(vm.uiState.value.title, calc.statusTag))
        vm.flushDraftNow()

        val state = vm.uiState.first { it.markdownContent.contains("车位") && it.title.contains("[3=1]") }
        assertTrue(state.markdownContent.startsWith("欢迎稳定长期车友上车。"))
        assertTrue(state.markdownContent.contains("| 车位 | 成员 / 状态 | 费用 / 备注 |"))
        assertEquals("[3=1] Netflix 4K 土区家庭车", state.title)
        assertTrue(state.canSubmit)

        vm.onPublishClicked()
        vm.uiState.first { it.hasPublished }
        assertEquals("carpool", forum.lastCreatedCategory)
        assertEquals("[3=1] Netflix 4K 土区家庭车", forum.lastCreatedTitle)
        assertTrue(forum.lastCreatedBody.contains("| #1 |"))
        collectJob.cancel()
    }

    private class InMemoryDraftRepo : DraftRepository {
        private val map = linkedMapOf<Pair<Long, String>, EditorDraft>()
        override suspend fun getDraft(accountUid: Long, draftKey: String): EditorDraft? = map[accountUid to draftKey]
        override suspend fun saveDraft(draft: EditorDraft) {
            map[draft.accountUid to draft.draftKey] = draft
        }

        override suspend fun deleteDraft(accountUid: Long, draftKey: String) {
            map.remove(accountUid to draftKey)
        }

        override fun observeDrafts(accountUid: Long): Flow<List<EditorDraft>> = flowOf(emptyList())
        override fun observeDraft(accountUid: Long, draftKey: String): Flow<EditorDraft?> = flowOf(null)
    }

    private class FakeForumRepo : ForumRepository {
        var lastCreatedCategory = ""
        var lastCreatedTitle = ""
        var lastCreatedBody = ""
        override fun observeCategories(): Flow<List<Category>> = flowOf(Category.DEFAULT_CATEGORIES)
        override suspend fun refreshCategories(force: Boolean): Result<List<Category>> =
            Result.success(Category.DEFAULT_CATEGORIES)

        override suspend fun getFeed(
            categorySlug: String,
            sort: FeedSort,
            page: Int,
            includeBlocked: Boolean,
            forceRefresh: Boolean,
        ): Result<FeedPageResult> = Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(topics = emptyList()))

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
        ): Result<Unit> {
            lastCreatedCategory = categorySlug
            lastCreatedTitle = title
            lastCreatedBody = markdownContent
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

    private class FakeSessionRepo(initial: AccountSession?) : SessionRepository {
        override val sessionState = MutableStateFlow(initial)
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
}
