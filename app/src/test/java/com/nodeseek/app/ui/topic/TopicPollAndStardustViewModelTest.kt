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
import com.nodeseek.app.domain.model.PollDetail
import com.nodeseek.app.domain.model.PollOption
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.StardustTipTarget
import com.nodeseek.app.domain.model.StardustTransferPreview
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.PollAndStardustRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.ui.composer.TopicComposerUiEvent
import com.nodeseek.app.ui.composer.TopicComposerViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class TopicPollAndStardustViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildTopicContainer(
        forum: ForumRepository,
        session: SessionRepository,
        bookmarks: BookmarkAndHistoryRepository,
        drafts: DraftRepository,
        users: UserRepository,
        pollRepo: PollAndStardustRepository,
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
            "getSettingsRepository" -> FakeSettingsRepository()
            "getRadarRepository" -> null
            "getPollAndStardustRepository" -> pollRepo
            else -> throw UnsupportedOperationException("Unexpected AppContainer call: ${method.name}")
        }
    } as AppContainer

    private fun samplePoll(
        multiple: Boolean = false,
        creatorUid: Long = 10L,
        voted: Boolean = false,
        locked: Boolean = false,
    ): PollDetail = PollDetail(
        id = 42L,
        title = "投票测试",
        creatorUid = creatorUid,
        isPublic = true,
        isLocked = locked,
        isMultiple = multiple,
        options = listOf(
            PollOption(voteItemId = 101L, text = "选项 A", count = if (voted) 1 else null, voted = voted),
            PollOption(voteItemId = 102L, text = "选项 B", count = if (voted) 0 else null, voted = false),
        ),
    )

    @Test
    fun `poll single vs multi select, submitVote refresh, and creator-only lockVote`() = runTest {
        val session = FakeSessionRepo().apply {
            sessionState.value = AccountSession(uid = 10L, username = "creator", stardust = 100)
        }
        val pollRepo = FakePollRepo(initialPoll = samplePoll(multiple = false, creatorUid = 10L))
        val vm = TopicDetailViewModel(
            container = buildTopicContainer(
                FakeForumRepo(),
                session,
                FakeBookmarkRepo(),
                FakeDraftRepo(),
                FakeUserRepo(),
                pollRepo,
            ),
            topicId = 500L,
        )

        val controller = vm.nsAppWidgetController
        controller.ensureVoteLoaded(42L)
        assertEquals(42L, controller.pollStateFor(42L).value.poll?.id)

        // Single select toggles mutually exclusively
        controller.togglePollOption(42L, 101L, isMultiple = false)
        assertEquals(setOf(101L), controller.pollStateFor(42L).value.selectedOptionIds)
        controller.togglePollOption(42L, 102L, isMultiple = false)
        assertEquals(setOf(102L), controller.pollStateFor(42L).value.selectedOptionIds)

        // Submit vote updates counts and voted flag
        controller.submitVote(42L)
        val afterVote = controller.pollStateFor(42L).value
        assertTrue(afterVote.poll?.hasCurrentUserVoted == true)
        assertTrue(afterVote.selectedOptionIds.isEmpty())
        assertEquals(listOf(102L), pollRepo.lastCastIds)

        // Creator locks vote
        controller.lockVote(42L)
        assertTrue(controller.pollStateFor(42L).value.poll?.isLocked == true)
    }

    @Test
    fun `stardust tip refreshes session and marks widget tipped, unauthenticated requests auth`() = runTest {
        val session = FakeSessionRepo()
        val pollRepo = FakePollRepo(initialPoll = samplePoll())
        val vm = TopicDetailViewModel(
            container = buildTopicContainer(
                FakeForumRepo(),
                session,
                FakeBookmarkRepo(),
                FakeDraftRepo(),
                FakeUserRepo(),
                pollRepo,
            ),
            topicId = 500L,
        )

        // Unauthenticated emits RequestWebViewAuth
        val events = mutableListOf<TopicDetailUiEvent>()
        val eventJob = launch(testDispatcher) {
            vm.events.collect { events += it }
        }
        vm.nsAppWidgetController.openStardustTip(
            StardustTipTarget(receiverUid = 202L, suggestedAmount = 15, widgetKey = "nsapp://stardust-receive?uid=202"),
        )
        assertTrue(events.any { it is TopicDetailUiEvent.RequestWebViewAuth })

        // Authenticated flow
        session.sessionState.value = AccountSession(uid = 1L, username = "tipper", stardust = 50)
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        vm.nsAppWidgetController.openStardustTip(
            StardustTipTarget(
                receiverUid = 202L,
                suggestedAmount = 15,
                refId = 500L,
                widgetKey = "nsapp://stardust-receive?uid=202",
            ),
        )
        val dialog = vm.uiState.value.stardustTipDialog!!
        assertFalse(dialog.isPreparing)
        assertEquals("ReceiverBob", dialog.resolvedReceiverName)
        assertEquals("15", dialog.amountInput)

        vm.submitStardustTip()
        assertNull(vm.uiState.value.stardustTipDialog)
        assertEquals(1, session.refreshCalls)
        assertEquals(15, pollRepo.lastSentAmount)
        assertTrue(vm.nsAppWidgetController.stardustStateFor("nsapp://stardust-receive?uid=202").value.isTipped)
        assertEquals(15, vm.nsAppWidgetController.stardustStateFor("nsapp://stardust-receive?uid=202").value.lastTippedAmount)

        collectJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun `TopicComposerViewModel createPollAndInsert creates vote and emits formatted widget markdown`() = runTest {
        val session = FakeSessionRepo().apply {
            sessionState.value = AccountSession(uid = 7L, username = "author")
        }
        val pollRepo = FakePollRepo(initialPoll = samplePoll())
        val composerVm = TopicComposerViewModel(
            container = buildTopicContainer(
                FakeForumRepo(),
                session,
                FakeBookmarkRepo(),
                FakeDraftRepo(),
                FakeUserRepo(),
                pollRepo,
            ),
            pollAndStardustRepositoryOverride = pollRepo,
        )

        val events = mutableListOf<TopicComposerUiEvent>()
        val eventJob = launch(testDispatcher) { composerVm.events.collect { events += it } }
        val collectJob = launch(testDispatcher) { composerVm.uiState.collect {} }

        composerVm.setPollSheetVisible(true)
        assertTrue(composerVm.uiState.value.showPollSheet)

        composerVm.createPollAndInsert(
            title = "[选择] 地区",
            multiple = true,
            isPublic = true,
            options = listOf("HK", "JP"),
        )

        assertFalse(composerVm.uiState.value.showPollSheet)
        val insertEvent = events.filterIsInstance<TopicComposerUiEvent.InsertUploadedMarkdown>().single()
        assertTrue(insertEvent.snippet.contains("[【选择】 地区](nsapp://vote?id=77)"))

        collectJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun `submitStardustTip_doubleTapBeforeDispatch_executesSingleTransfer`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var sendCalls = 0
        val delegate = FakePollRepo(samplePoll())
        val pollRepo = object : PollAndStardustRepository by delegate {
            override suspend fun sendStardust(memberId: Long, amount: Int, refId: Long): Result<Unit> {
                sendCalls += 1
                gate.await()
                return Result.success(Unit)
            }
        }
        val sessionRepo = FakeSessionRepo().apply {
            sessionState.value = AccountSession(uid = 101L, username = "Alice", drumsticks = 50, stardust = 200)
        }
        val vm = TopicDetailViewModel(
            container = buildTopicContainer(
                forum = FakeForumRepo(),
                session = sessionRepo,
                bookmarks = FakeBookmarkRepo(),
                drafts = FakeDraftRepo(),
                users = FakeUserRepo(),
                pollRepo = pollRepo,
            ),
            topicId = 500L,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }

        vm.nsAppWidgetController.openStardustTip(
            StardustTipTarget(
                receiverUid = 202L,
                suggestedAmount = 15,
                refId = 500L,
                widgetKey = "nsapp://stardust-receive?uid=202",
            ),
        )
        vm.submitStardustTip()
        vm.submitStardustTip()

        assertEquals(1, sendCalls)
        assertTrue(vm.uiState.value.stardustTipDialog?.isSending == true)

        gate.complete(Unit)
        assertNull(vm.uiState.value.stardustTipDialog)
        assertEquals(1, sendCalls)

        collectJob.cancel()
    }

    @Test
    fun `submitVote_doubleTapAndToggleWhileSubmitting_blocksDuplicateAndMutation`() = runTest {
        val castGate = CompletableDeferred<Unit>()
        var castCalls = 0
        val basePoll = samplePoll(multiple = true)
        val delegate = FakePollRepo(basePoll)
        val pollRepo = object : PollAndStardustRepository by delegate {
            override suspend fun castVote(voteId: Long, voteItemIds: List<Long>): Result<PollDetail> {
                castCalls += 1
                castGate.await()
                return Result.success(basePoll)
            }
        }
        val sessionRepo = FakeSessionRepo().apply {
            sessionState.value = AccountSession(uid = 101L, username = "Alice")
        }
        val vm = TopicDetailViewModel(
            container = buildTopicContainer(
                forum = FakeForumRepo(),
                session = sessionRepo,
                bookmarks = FakeBookmarkRepo(),
                drafts = FakeDraftRepo(),
                users = FakeUserRepo(),
                pollRepo = pollRepo,
            ),
            topicId = 500L,
        )
        val controller = vm.nsAppWidgetController
        controller.ensureVoteLoaded(42L)
        controller.togglePollOption(42L, 101L, isMultiple = true)
        assertEquals(setOf(101L), controller.pollStateFor(42L).value.selectedOptionIds)

        controller.submitVote(42L)
        controller.submitVote(42L)
        controller.togglePollOption(42L, 102L, isMultiple = true)

        assertEquals(1, castCalls)
        assertEquals(setOf(101L), controller.pollStateFor(42L).value.selectedOptionIds)
        assertTrue(controller.pollStateFor(42L).value.isSubmitting)

        castGate.complete(Unit)
        assertEquals(1, castCalls)
        assertFalse(controller.pollStateFor(42L).value.isSubmitting)
    }

    @Test
    fun `pollStateFor_accountSwitch_invalidatesPriorAccountVoteState`() = runTest {
        val pollByUid = mapOf(
            101L to samplePoll(voted = true),
            202L to samplePoll(voted = false),
        )
        val sessionRepo = FakeSessionRepo().apply {
            sessionState.value = AccountSession(uid = 101L, username = "Alice")
        }
        val delegate = FakePollRepo(samplePoll())
        val pollRepo = object : PollAndStardustRepository by delegate {
            override suspend fun getVote(voteId: Long): Result<PollDetail> {
                val uid = sessionRepo.sessionState.value?.uid ?: 0L
                return Result.success(pollByUid[uid] ?: samplePoll(voted = false))
            }
        }
        val vm = TopicDetailViewModel(
            container = buildTopicContainer(
                forum = FakeForumRepo(),
                session = sessionRepo,
                bookmarks = FakeBookmarkRepo(),
                drafts = FakeDraftRepo(),
                users = FakeUserRepo(),
                pollRepo = pollRepo,
            ),
            topicId = 500L,
        )
        val controller = vm.nsAppWidgetController
        controller.ensureVoteLoaded(42L)
        assertTrue(controller.pollStateFor(42L).value.poll?.hasCurrentUserVoted == true)

        sessionRepo.sessionState.value = AccountSession(uid = 202L, username = "Bob")
        assertFalse(controller.pollStateFor(42L).value.poll?.hasCurrentUserVoted == true)
    }

    private class FakePollRepo(initialPoll: PollDetail) : PollAndStardustRepository {
        var poll = initialPoll
        var lastCastIds: List<Long> = emptyList()
        var lastSentAmount: Int? = null

        override suspend fun getVote(voteId: Long): Result<PollDetail> = Result.success(poll)

        override suspend fun createVote(
            title: String,
            multiple: Boolean,
            isPublic: Boolean,
            options: List<String>,
        ): Result<PollDetail> = Result.success(
            PollDetail(
                id = 77L,
                title = title,
                creatorUid = 7L,
                isPublic = isPublic,
                isLocked = false,
                isMultiple = multiple,
                options = options.mapIndexed { idx, s -> PollOption(idx + 1L, s) },
            ),
        )

        override suspend fun castVote(voteId: Long, voteItemIds: List<Long>): Result<PollDetail> {
            lastCastIds = voteItemIds
            poll = poll.copy(
                options = poll.options.map { opt ->
                    if (opt.voteItemId in voteItemIds) {
                        opt.copy(count = (opt.count ?: 0) + 1, voted = true)
                    } else {
                        opt.copy(count = opt.count ?: 0, voted = false)
                    }
                },
            )
            return Result.success(poll)
        }

        override suspend fun lockVote(voteId: Long): Result<PollDetail> {
            poll = poll.copy(isLocked = true)
            return Result.success(poll)
        }

        override suspend fun prepareStardustTransfer(receiverId: Long): Result<StardustTransferPreview> =
            Result.success(StardustTransferPreview(receiverUid = receiverId, receiverName = "ReceiverBob"))

        override suspend fun sendStardust(memberId: Long, amount: Int, refId: Long): Result<Unit> {
            lastSentAmount = amount
            return Result.success(Unit)
        }
    }

    private class FakeSessionRepo : SessionRepository {
        override val sessionState = MutableStateFlow<AccountSession?>(null)
        override val challengeRequired = MutableStateFlow(false)
        override val authVerifiedEpoch = MutableStateFlow(0L)
        var refreshCalls = 0
        override suspend fun refreshSession(): Result<AccountSession?> {
            refreshCalls += 1
            return Result.success(sessionState.value)
        }
        override suspend fun signIn(username: String, password: String, turnstileToken: String): Result<SignInStepResult> =
            Result.success(SignInStepResult.Authenticated)
        override suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit> = Result.success(Unit)
        override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signOut(): Result<Unit> = Result.success(Unit)
    }

    private class FakeForumRepo : ForumRepository {
        private val op = FloorAuthor(uid = 10L, username = "creator", isTopicAuthor = true)
        override fun observeCategories(): Flow<List<Category>> = flowOf(Category.DEFAULT_CATEGORIES)
        override suspend fun refreshCategories(force: Boolean): Result<List<Category>> = Result.success(Category.DEFAULT_CATEGORIES)
        override suspend fun getFeed(categorySlug: String, sort: FeedSort, page: Int, includeBlocked: Boolean, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(emptyList(), emptyList(), 1, false))
        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(emptyList(), emptyList(), 1, false))
        override suspend fun searchTopics(query: String, categorySlug: String, sort: FeedSort, page: Int): Result<FeedPageResult> =
            Result.success(FeedPageResult(emptyList(), emptyList(), 1, false))
        override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> =
            Result.success(
                TopicDetailPage(
                    topicId = topicId,
                    title = "测试",
                    categorySlug = "tech",
                    categoryName = "技术",
                    author = op,
                    openingFloor = FloorComment(
                        commentId = 1L,
                        floorNumber = 0,
                        author = op,
                        createdAtIso = "2026-11-25T00:00:00Z",
                        markdownBody = "Body",
                    ),
                    replies = emptyList(),
                ),
            )
        override suspend fun createTopic(categorySlug: String, title: String, markdownContent: String, requiredViewLevel: Int): Result<Unit> = Result.success(Unit)
        override suspend fun editTopic(topicId: Long, title: String, markdownContent: String, requiredViewLevel: Int): Result<Unit> = Result.success(Unit)
        override suspend fun postReply(topicId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun editReply(commentId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun setReplyPinned(commentId: Long, pinned: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getFreeDrumstickCost(): Result<Int> = Result.success(0)
        override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType): Result<ReactionResult> =
            Result.failure(UnsupportedOperationException())
    }

    private class FakeDraftRepo : DraftRepository {
        override suspend fun getDraft(accountUid: Long, draftKey: String): EditorDraft? = null
        override suspend fun saveDraft(draft: EditorDraft) = Unit
        override suspend fun deleteDraft(accountUid: Long, draftKey: String) = Unit
        override fun observeDrafts(accountUid: Long): Flow<List<EditorDraft>> = flowOf(emptyList())
        override fun observeDraft(accountUid: Long, draftKey: String): Flow<EditorDraft?> = flowOf(null)
    }

    private class FakeBookmarkRepo : BookmarkAndHistoryRepository {
        override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> = Result.success(emptyList<TopicSummary>() to false)
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

    private class FakeUserRepo : UserRepository {
        override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean): Result<UserProfile> = Result.failure(UnsupportedOperationException())
        override suspend fun updateOwnProfile(bio: String, signature: String, readme: String): Result<Unit> = Result.success(Unit)
        override suspend fun getUserTopics(uid: Long, page: Int): Result<Pair<List<TopicSummary>, Boolean>> = Result.success(emptyList<TopicSummary>() to false)
        override suspend fun getUserReplies(uid: Long, page: Int): Result<Pair<List<UserReplyExcerpt>, Boolean>> = Result.success(emptyList<UserReplyExcerpt>() to false)
        override suspend fun setFollowed(targetUid: Long, follow: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getConnections(following: Boolean): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun getBlockedUsers(): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun blockUserByName(username: String): Result<Unit> = Result.success(Unit)
        override suspend fun unblockUserById(targetUid: Long): Result<Unit> = Result.success(Unit)
    }
}
