package com.nodeseek.app.ui.topic

import androidx.lifecycle.viewModelScope
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
import com.nodeseek.app.domain.trade.TradePrivacyOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
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

    @Test
    fun `two rapid submitReply calls before dispatcher yield invoke postReply or editReply only once`() = runTest {
        val stdDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(stdDispatcher)

        val postGate = CompletableDeferred<Result<Unit>>()
        val editGate = CompletableDeferred<Result<Unit>>()
        val forum = FakeTopicForumRepository { _, p -> Result.success(page(pageNum = p, totalPages = 1)) }.apply {
            postReplyHandler = { _, _ -> postGate.await() }
            editReplyHandler = { _, _ -> editGate.await() }
        }
        val session = FakeTopicSessionRepository().apply {
            sessionState.value = AccountSession(uid = 1L, username = "me")
        }
        val vm = TopicDetailViewModel(
            container = buildContainer(
                forum,
                session,
                FakeTopicBookmarkRepository(),
                InMemoryDraftRepository(),
                FakeTopicUserRepository(),
            ),
            topicId = 100L,
        )
        val stateJob = launch(stdDispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()

        // 1. Rapid double-tap on new reply before dispatcher yield
        vm.openReplySheet()
        vm.onReplyDraftChanged("测试防连点回复")
        vm.submitReply()
        vm.submitReply()
        testScheduler.runCurrent()
        assertEquals("Rapid double-tap submitReply must invoke postReply only once", 1, forum.postReplyCount)

        // Fail first attempt and ensure retry works after isSubmitting resets
        postGate.complete(Result.failure(RuntimeException("network glitch")))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.replyComposer.isSubmitting)

        // 2. Rapid double-tap on edit reply before dispatcher yield
        val ownComment = FloorComment(
            commentId = 777L,
            floorNumber = 2,
            author = FloorAuthor(uid = 1L, username = "me"),
            createdAtIso = "2026-11-27T00:10:00Z",
            markdownBody = "旧内容",
        )
        vm.openEditReplySheet(ownComment)
        advanceUntilIdle()
        vm.onReplyDraftChanged("修改后的内容")
        vm.submitReply()
        vm.submitReply()
        testScheduler.runCurrent()
        assertEquals("Rapid double-tap submitReply in edit mode must invoke editReply only once", 1, forum.editReplyCount)
        editGate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertEquals(1, forum.editReplyCount)

        stateJob.cancel()
    }

    @Test
    fun `Reply 1 finally after syncTailPageAfterPostReply does not clear Reply 2 active isSubmitting guard`() = runTest {
        val stdDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(stdDispatcher)

        val reply1TailSyncGate = CompletableDeferred<Result<TopicDetailPage>>()
        val reply2PostGate = CompletableDeferred<Result<Unit>>()
        var detailCalls = 0
        var reply2Active = false

        val forum = FakeTopicForumRepository { _, p ->
            detailCalls++
            if (detailCalls == 1) {
                Result.success(page(pageNum = p, totalPages = 1))
            } else if (detailCalls == 2) {
                reply1TailSyncGate.await()
            } else {
                Result.success(page(pageNum = p, totalPages = 1))
            }
        }.apply {
            postReplyHandler = { _, _ ->
                if (reply2Active) {
                    reply2PostGate.await()
                } else {
                    Result.success(Unit)
                }
            }
        }
        val session = FakeTopicSessionRepository().apply {
            sessionState.value = AccountSession(uid = 1L, username = "me")
        }
        val vm = TopicDetailViewModel(
            container = buildContainer(
                forum,
                session,
                FakeTopicBookmarkRepository(),
                InMemoryDraftRepository(),
                FakeTopicUserRepository(),
            ),
            topicId = 100L,
        )
        val stateJob = launch(stdDispatcher) { vm.uiState.collect {} }
        testScheduler.runCurrent()

        // Submit Reply #1: postReply succeeds and suspends inside syncTailPageAfterPostReply
        vm.openReplySheet()
        testScheduler.runCurrent()
        vm.onReplyDraftChanged("第一条回复")
        vm.submitReply()
        testScheduler.runCurrent()
        assertEquals(1, forum.postReplyCount)

        // Submit Reply #2 while Reply #1 is still suspended in syncTailPageAfterPostReply
        reply2Active = true
        vm.openReplySheet()
        testScheduler.runCurrent()
        vm.onReplyDraftChanged("第二条回复")
        vm.submitReply()
        testScheduler.runCurrent()
        assertEquals(2, forum.postReplyCount)
        assertTrue(vm.uiState.value.replyComposer.isSubmitting)

        // Finish Reply #1's syncTailPageAfterPostReply so Reply #1's finally runs
        reply1TailSyncGate.complete(Result.success(page(pageNum = 1, totalPages = 1)))
        testScheduler.runCurrent()

        // Reply #2's isSubmitting guard must remain active and block a follow-up tap
        assertTrue("Reply #1 finally must not clear Reply #2 isSubmitting", vm.uiState.value.replyComposer.isSubmitting)
        vm.submitReply()
        testScheduler.runCurrent()
        assertEquals("Follow-up submitReply tap during in-flight Reply #2 must not trigger 3rd postReply", 2, forum.postReplyCount)

        reply2PostGate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertEquals(2, forum.postReplyCount)
        assertFalse(vm.uiState.value.replyComposer.isSubmitting)

        stateJob.cancel()
    }

    @Test
    fun `stale out-of-order syncTailPageAfterPostReply does not overwrite newer Reply 2 floors or pendingScrollFloor`() = runTest {
        val stdDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(stdDispatcher)

        val opAuthor = FloorAuthor(uid = 10L, username = "op_user", isTopicAuthor = true)
        val replyAuthor = FloorAuthor(uid = 1L, username = "me")
        val initialEmptyPage = TopicDetailPage(
            topicId = 100L,
            title = "测试主题",
            categorySlug = "tech",
            categoryName = "技术",
            author = opAuthor,
            currentPage = 1,
            totalPages = 1,
            openingFloor = FloorComment(
                commentId = 1000L,
                floorNumber = 0,
                author = opAuthor,
                createdAtIso = "2026-11-27T00:00:00Z",
                markdownBody = "OP",
            ),
            replies = emptyList(),
        )
        val reply1Comment = FloorComment(
            commentId = 3001L,
            floorNumber = 1,
            author = replyAuthor,
            createdAtIso = "2026-11-27T00:01:00Z",
            markdownBody = "第一条回复",
        )
        val reply2Comment = FloorComment(
            commentId = 3002L,
            floorNumber = 2,
            author = replyAuthor,
            createdAtIso = "2026-11-27T00:02:00Z",
            markdownBody = "第二条回复",
        )
        val pageAfterReply1 = initialEmptyPage.copy(replies = listOf(reply1Comment))
        val pageAfterReply2 = initialEmptyPage.copy(replies = listOf(reply1Comment, reply2Comment))

        val reply1TailGate = CompletableDeferred<Result<TopicDetailPage>>()
        var detailCalls = 0
        val forum = FakeTopicForumRepository { _, _ ->
            detailCalls++
            when (detailCalls) {
                1 -> Result.success(initialEmptyPage)
                2 -> reply1TailGate.await()
                else -> Result.success(pageAfterReply2)
            }
        }
        val session = FakeTopicSessionRepository().apply {
            sessionState.value = AccountSession(uid = 1L, username = "me")
        }
        val vm = TopicDetailViewModel(
            container = buildContainer(
                forum,
                session,
                FakeTopicBookmarkRepository(),
                InMemoryDraftRepository(),
                FakeTopicUserRepository(),
            ),
            topicId = 100L,
        )
        val stateJob = launch(stdDispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()

        // Submit Reply #1 -> suspends inside syncTailPageAfterPostReply
        vm.openReplySheet()
        advanceUntilIdle()
        vm.onReplyDraftChanged("第一条回复")
        vm.submitReply()
        testScheduler.runCurrent()

        // Submit Reply #2 -> finishes syncTailPageAfterPostReply first with [1, 2] and pendingScrollFloor = 2
        vm.openReplySheet()
        advanceUntilIdle()
        vm.onReplyDraftChanged("第二条回复")
        vm.submitReply()
        advanceUntilIdle()

        assertEquals(listOf(1, 2), vm.uiState.value.displayedReplies.map { it.floorNumber })
        assertEquals(2, vm.uiState.value.pendingScrollFloor)
        assertFalse(vm.uiState.value.isPageSwitching)

        // Complete stale Reply #1 tail sync with [1] -> must NOT overwrite [1, 2] or pendingScrollFloor = 2!
        reply1TailGate.complete(Result.success(pageAfterReply1))
        advanceUntilIdle()

        assertEquals(listOf(1, 2), vm.uiState.value.displayedReplies.map { it.floorNumber })
        assertEquals(2, vm.uiState.value.pendingScrollFloor)
        assertFalse(vm.uiState.value.isPageSwitching)

        stateJob.cancel()
    }

    @Test
    fun `init does not throw NPE when sessionState emits distinct UID concurrently during construction`() = runTest {
        val currentSession = MutableStateFlow<AccountSession?>(AccountSession(uid = 1L, username = "u1"))
        val burstySessionFlow = object : StateFlow<AccountSession?> {
            private var emittedBurst = false
            override val value: AccountSession? get() = currentSession.value
            override val replayCache: List<AccountSession?> get() = listOf(value)

            override suspend fun collect(collector: FlowCollector<AccountSession?>): Nothing {
                if (!emittedBurst) {
                    emittedBurst = true
                    val first = AccountSession(uid = 1L, username = "u1")
                    val second = AccountSession(uid = 2L, username = "u2")
                    currentSession.value = first
                    collector.emit(first)
                    // Simulate sessionState updating synchronously/concurrently before <init> finishes
                    currentSession.value = second
                    collector.emit(second)
                    awaitCancellation()
                }
                currentSession.collect(collector)
            }
        }
        val session = object : SessionRepository {
            override val sessionState: StateFlow<AccountSession?> = burstySessionFlow
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
        val forum = FakeTopicForumRepository { _, p -> Result.success(page(pageNum = p)) }

        val vm = TopicDetailViewModel(
            container = buildContainer(
                forum = forum,
                session = session,
                bookmarks = FakeTopicBookmarkRepository(),
                drafts = InMemoryDraftRepository(),
                users = FakeTopicUserRepository(),
            ),
            topicId = 100L,
        )
        assertEquals(2L, vm.nsAppWidgetController.currentViewerUid)

        val stateJob = launch(testDispatcher) { vm.uiState.collect {} }
        val loaded = vm.uiState.first { it.pageData != null }
        assertNotNull(loaded.pageData)

        stateJob.cancel()
    }

    @Test
    fun `nsAppWidgetController loads poll and reloads cleanly when session UID changes`() = runTest {
        val session = FakeTopicSessionRepository().apply {
            sessionState.value = AccountSession(uid = 1L, username = "u1")
        }
        var getVoteCalls = 0
        val pollRepo = object : PollAndStardustRepository {
            override suspend fun getVote(voteId: Long): Result<PollDetail> {
                getVoteCalls++
                val activeUid = session.sessionState.value?.uid ?: 0L
                return Result.success(
                    PollDetail(
                        id = voteId,
                        title = "Poll for uid=$activeUid",
                        creatorUid = 10L,
                        isPublic = true,
                        isLocked = false,
                        isMultiple = false,
                        options = listOf(PollOption(voteItemId = 1L, text = "Yes", count = getVoteCalls)),
                    ),
                )
            }

            override suspend fun createVote(title: String, multiple: Boolean, isPublic: Boolean, options: List<String>) =
                Result.failure<PollDetail>(UnsupportedOperationException())

            override suspend fun castVote(voteId: Long, voteItemIds: List<Long>) =
                Result.failure<PollDetail>(UnsupportedOperationException())

            override suspend fun lockVote(voteId: Long) =
                Result.failure<PollDetail>(UnsupportedOperationException())

            override suspend fun prepareStardustTransfer(receiverId: Long) =
                Result.failure<StardustTransferPreview>(UnsupportedOperationException())

            override suspend fun sendStardust(memberId: Long, amount: Int, refId: Long) =
                Result.failure<Unit>(UnsupportedOperationException())
        }
        val forum = FakeTopicForumRepository { _, p -> Result.success(page(pageNum = p)) }
        val vm = TopicDetailViewModel(
            container = buildContainer(
                forum = forum,
                session = session,
                bookmarks = FakeTopicBookmarkRepository(),
                drafts = InMemoryDraftRepository(),
                users = FakeTopicUserRepository(),
            ),
            topicId = 100L,
            pollAndStardustRepositoryOverride = pollRepo,
        )

        val pollFlow = vm.nsAppWidgetController.pollStateFor(42L)
        vm.nsAppWidgetController.ensureVoteLoaded(42L)
        assertEquals(1, getVoteCalls)
        assertEquals("Poll for uid=1", pollFlow.value.poll?.title)

        session.sessionState.value = AccountSession(uid = 2L, username = "u2")
        assertEquals(2, getVoteCalls)
        assertEquals("Poll for uid=2", pollFlow.value.poll?.title)
    }

    @Test
    fun `concurrent session transitions with refreshCurrentPage and ensureVoteLoaded converge cleanly across 25 rounds`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val rounds = 25
            for (round in 0 until rounds) {
                val session = FakeTopicSessionRepository().apply {
                    sessionState.value = AccountSession(uid = 101L, username = "u101")
                }
                val getVoteSeq = AtomicInteger(0)
                val pollRepo = object : PollAndStardustRepository {
                    override suspend fun getVote(voteId: Long): Result<PollDetail> {
                        val seq = getVoteSeq.incrementAndGet()
                        val uidAtStart = session.sessionState.value?.uid ?: 0L
                        if (seq % 2 == 0) {
                            delay(1)
                        }
                        val activeUid = session.sessionState.value?.uid ?: uidAtStart
                        return Result.success(
                            PollDetail(
                                id = voteId,
                                title = "vote_for_$activeUid",
                                creatorUid = activeUid,
                                isPublic = true,
                                isLocked = false,
                                isMultiple = false,
                                options = listOf(PollOption(voteItemId = 1L, text = "Yes", count = seq)),
                            ),
                        )
                    }

                    override suspend fun createVote(title: String, multiple: Boolean, isPublic: Boolean, options: List<String>) =
                        Result.failure<PollDetail>(UnsupportedOperationException())

                    override suspend fun castVote(voteId: Long, voteItemIds: List<Long>) =
                        Result.failure<PollDetail>(UnsupportedOperationException())

                    override suspend fun lockVote(voteId: Long) =
                        Result.failure<PollDetail>(UnsupportedOperationException())

                    override suspend fun prepareStardustTransfer(receiverId: Long) =
                        Result.failure<StardustTransferPreview>(UnsupportedOperationException())

                    override suspend fun sendStardust(memberId: Long, amount: Int, refId: Long) =
                        Result.failure<Unit>(UnsupportedOperationException())
                }
                val forum = FakeTopicForumRepository { _, p ->
                    if (p % 2 == 0) delay(1)
                    Result.success(page(topicId = 700L + round, pageNum = p, totalPages = 1))
                }
                val vm = TopicDetailViewModel(
                    container = buildContainer(
                        forum = forum,
                        session = session,
                        bookmarks = FakeTopicBookmarkRepository(),
                        drafts = InMemoryDraftRepository(),
                        users = FakeTopicUserRepository(),
                    ),
                    topicId = 700L + round,
                    pollAndStardustRepositoryOverride = pollRepo,
                )
                try {
                    val pollFlow = vm.nsAppWidgetController.pollStateFor(9L)
                    vm.nsAppWidgetController.ensureVoteLoaded(9L)
                    withTimeout(5_000L) {
                        pollFlow.first { !it.isLoading && it.poll?.creatorUid == 101L }
                    }

                    val startGate = CountDownLatch(1)
                    val thread2Done = CountDownLatch(1)
                    val sessionFlipDone = CountDownLatch(1)
                    val doneGate = CountDownLatch(6)
                    val workerError = AtomicReference<Throwable?>(null)
                    val finalUid = 303L + round

                    // Thread 1: rapidly alternate sessionState across 101 / null / 202, wait for Thread 2, then settle to distinct finalUid while Threads 3-6 still race
                    executor.submit {
                        try {
                            startGate.await()
                            for (step in 0 until 30) {
                                session.sessionState.value = when (step % 3) {
                                    0 -> AccountSession(uid = 202L, username = "u202")
                                    1 -> null
                                    else -> AccountSession(uid = 101L, username = "u101")
                                }
                                if (step % 4 == 0) Thread.yield()
                            }
                            thread2Done.await(5, TimeUnit.SECONDS)
                            session.sessionState.value = AccountSession(uid = finalUid, username = "u$finalUid")
                        } catch (t: Throwable) {
                            workerError.compareAndSet(null, t)
                        } finally {
                            sessionFlipDone.countDown()
                            doneGate.countDown()
                        }
                    }

                    // Thread 2: race sessionState transitions during storm, finishing before Thread 1 settles finalUid
                    executor.submit {
                        try {
                            startGate.await()
                            for (step in 0 until 20) {
                                session.sessionState.value = if (step % 2 == 0) {
                                    AccountSession(uid = 202L, username = "u202")
                                } else {
                                    AccountSession(uid = 101L, username = "u101")
                                }
                                Thread.yield()
                            }
                        } catch (t: Throwable) {
                            workerError.compareAndSet(null, t)
                        } finally {
                            thread2Done.countDown()
                            doneGate.countDown()
                        }
                    }

                    // Threads 3 & 4: concurrent refreshCurrentPage() -> syncPollSessionChangeIfNeeded() + stateLock updates
                    repeat(2) {
                        executor.submit {
                            try {
                                startGate.await()
                                var i = 0
                                while (sessionFlipDone.count > 0 || i < 10) {
                                    vm.refreshCurrentPage()
                                    Thread.yield()
                                    i++
                                }
                            } catch (t: Throwable) {
                                workerError.compareAndSet(null, t)
                            } finally {
                                doneGate.countDown()
                            }
                        }
                    }

                    // Threads 5 & 6: concurrent ensureVoteLoaded(9L) calls
                    repeat(2) {
                        executor.submit {
                            try {
                                startGate.await()
                                var i = 0
                                while (sessionFlipDone.count > 0 || i < 10) {
                                    vm.nsAppWidgetController.ensureVoteLoaded(9L)
                                    Thread.yield()
                                    i++
                                }
                            } catch (t: Throwable) {
                                workerError.compareAndSet(null, t)
                            } finally {
                                doneGate.countDown()
                            }
                        }
                    }

                    startGate.countDown()
                    assertTrue("Timed out waiting for storm workers in round $round", doneGate.await(15, TimeUnit.SECONDS))
                    workerError.get()?.let { throw AssertionError("Storm worker threw in round $round", it) }

                    // Assert pollStateFor(9L) converges cleanly to finalUid's PollDetail without any extra ensureVoteLoaded call
                    val converged = withTimeout(5_000L) {
                        pollFlow.first { !it.isLoading && it.poll?.creatorUid == finalUid }
                    }
                    assertNotNull("Round $round dropped poll reload", converged.poll)
                    assertEquals("vote_for_$finalUid", converged.poll?.title)
                    assertFalse(converged.isLoading)
                    assertEquals(finalUid, vm.nsAppWidgetController.currentViewerUid)
                } finally {
                    val scopeChildren = vm.viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty()
                    vm.viewModelScope.cancel()
                    withTimeout(2_000L) {
                        scopeChildren.forEach { it.join() }
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `closeTradeTopic clears isMutatingTopic before emitting failure events across failure exception and missing opening paths`() = runTest {
        val session = FakeTopicSessionRepository().apply {
            sessionState.value = AccountSession(uid = 10L, username = "op_user")
        }

        // Case 1: editTopic returns Result.failure
        val failureForum = FakeTopicForumRepository { _, p ->
            Result.success(
                page(pageNum = p).copy(
                    categorySlug = "trade",
                    categoryName = "交易",
                    title = "[出] DMIT Pro",
                ),
            )
        }.apply {
            editTopicHandler = { _, _, _, _ -> Result.failure(IllegalStateException("操作频繁")) }
        }
        val failureVm = TopicDetailViewModel(
            container = buildContainer(
                forum = failureForum,
                session = session,
                bookmarks = FakeTopicBookmarkRepository(),
                drafts = InMemoryDraftRepository(),
                users = FakeTopicUserRepository(),
            ),
            topicId = 901L,
        )
        val failureSnapshots = mutableListOf<Boolean>()
        val sJob1 = launch(testDispatcher) { failureVm.uiState.collect {} }
        val eJob1 = launch(testDispatcher) {
            failureVm.events.collect { ev ->
                if (ev is TopicDetailUiEvent.ShowMessage || ev is TopicDetailUiEvent.RequestWebViewAuth) {
                    failureSnapshots += failureVm.uiState.value.isMutatingTopic
                }
            }
        }
        failureVm.uiState.first { it.openingFloor != null }
        failureVm.closeTradeTopic(TradePrivacyOptions())
        assertTrue("Expected failure event to arrive", failureSnapshots.isNotEmpty())
        assertTrue("isMutatingTopic must be false when failure event arrives", failureSnapshots.all { !it })
        assertFalse(failureVm.uiState.value.isMutatingTopic)
        sJob1.cancel()
        eJob1.cancel()

        // Case 2: editTopic throws unexpected RuntimeException
        val throwForum = FakeTopicForumRepository { _, p ->
            Result.success(
                page(pageNum = p).copy(
                    categorySlug = "trade",
                    categoryName = "交易",
                    title = "[出] DMIT Pro",
                ),
            )
        }.apply {
            editTopicHandler = { _, _, _, _ -> throw RuntimeException("boom") }
        }
        val throwVm = TopicDetailViewModel(
            container = buildContainer(
                forum = throwForum,
                session = session,
                bookmarks = FakeTopicBookmarkRepository(),
                drafts = InMemoryDraftRepository(),
                users = FakeTopicUserRepository(),
            ),
            topicId = 902L,
        )
        val throwSnapshots = mutableListOf<Boolean>()
        val sJob2 = launch(testDispatcher) { throwVm.uiState.collect {} }
        val eJob2 = launch(testDispatcher) {
            throwVm.events.collect { ev ->
                if (ev is TopicDetailUiEvent.ShowMessage || ev is TopicDetailUiEvent.RequestWebViewAuth) {
                    throwSnapshots += throwVm.uiState.value.isMutatingTopic
                }
            }
        }
        throwVm.uiState.first { it.openingFloor != null }
        throwVm.closeTradeTopic(TradePrivacyOptions())
        assertTrue("Expected exception failure event to arrive", throwSnapshots.isNotEmpty())
        assertTrue("isMutatingTopic must be false when exception event arrives", throwSnapshots.all { !it })
        assertFalse(throwVm.uiState.value.isMutatingTopic)
        sJob2.cancel()
        eJob2.cancel()

        // Case 3: Page 1 openingFloor == null when launched from Page 2
        val missingOpeningForum = FakeTopicForumRepository { _, p ->
            Result.success(
                page(pageNum = p, totalPages = 2, includeOpening = false).copy(
                    categorySlug = "trade",
                    categoryName = "交易",
                    title = "[出] DMIT Pro",
                ),
            )
        }
        val missingOpeningVm = TopicDetailViewModel(
            container = buildContainer(
                forum = missingOpeningForum,
                session = session,
                bookmarks = FakeTopicBookmarkRepository(),
                drafts = InMemoryDraftRepository(),
                users = FakeTopicUserRepository(),
            ),
            topicId = 903L,
            initialFloor = 15,
        )
        val missingSnapshots = mutableListOf<Boolean>()
        val sJob3 = launch(testDispatcher) { missingOpeningVm.uiState.collect {} }
        val eJob3 = launch(testDispatcher) {
            missingOpeningVm.events.collect { ev ->
                if (ev is TopicDetailUiEvent.ShowMessage || ev is TopicDetailUiEvent.RequestWebViewAuth) {
                    missingSnapshots += missingOpeningVm.uiState.value.isMutatingTopic
                }
            }
        }
        missingOpeningVm.uiState.first { it.pageData != null }
        missingOpeningVm.closeTradeTopic(TradePrivacyOptions())
        assertTrue("Expected missing-opening failure event to arrive", missingSnapshots.isNotEmpty())
        assertTrue("isMutatingTopic must be false when missing-opening event arrives", missingSnapshots.all { !it })
        assertFalse(missingOpeningVm.uiState.value.isMutatingTopic)
        sJob3.cancel()
        eJob3.cancel()
    }

    @Test
    fun `updateCarpoolStatus clears isMutatingTopic before emitting failure events across failure exception and missing opening paths`() = runTest {
        val session = FakeTopicSessionRepository().apply {
            sessionState.value = AccountSession(uid = 10L, username = "op_user")
        }

        // Case 1: editTopic returns Result.failure
        val failureForum = FakeTopicForumRepository { _, p ->
            Result.success(
                page(pageNum = p).copy(
                    categorySlug = "carpool",
                    categoryName = "拼车",
                    title = "[3=1] Netflix 4K",
                ),
            )
        }.apply {
            editTopicHandler = { _, _, _, _ -> Result.failure(IllegalStateException("编辑频率过快")) }
        }
        val failureVm = TopicDetailViewModel(
            container = buildContainer(
                forum = failureForum,
                session = session,
                bookmarks = FakeTopicBookmarkRepository(),
                drafts = InMemoryDraftRepository(),
                users = FakeTopicUserRepository(),
            ),
            topicId = 911L,
        )
        val failureSnapshots = mutableListOf<Boolean>()
        val sJob1 = launch(testDispatcher) { failureVm.uiState.collect {} }
        val eJob1 = launch(testDispatcher) {
            failureVm.events.collect { ev ->
                if (ev is TopicDetailUiEvent.ShowMessage || ev is TopicDetailUiEvent.RequestWebViewAuth) {
                    failureSnapshots += failureVm.uiState.value.isMutatingTopic
                }
            }
        }
        failureVm.uiState.first { it.openingFloor != null }
        failureVm.updateCarpoolStatus("已满车")
        assertTrue("Expected failure event to arrive", failureSnapshots.isNotEmpty())
        assertTrue("isMutatingTopic must be false when failure event arrives", failureSnapshots.all { !it })
        assertFalse(failureVm.uiState.value.isMutatingTopic)
        sJob1.cancel()
        eJob1.cancel()

        // Case 2: editTopic throws unexpected RuntimeException
        val throwForum = FakeTopicForumRepository { _, p ->
            Result.success(
                page(pageNum = p).copy(
                    categorySlug = "carpool",
                    categoryName = "拼车",
                    title = "[3=1] Netflix 4K",
                ),
            )
        }.apply {
            editTopicHandler = { _, _, _, _ -> throw RuntimeException("boom") }
        }
        val throwVm = TopicDetailViewModel(
            container = buildContainer(
                forum = throwForum,
                session = session,
                bookmarks = FakeTopicBookmarkRepository(),
                drafts = InMemoryDraftRepository(),
                users = FakeTopicUserRepository(),
            ),
            topicId = 912L,
        )
        val throwSnapshots = mutableListOf<Boolean>()
        val sJob2 = launch(testDispatcher) { throwVm.uiState.collect {} }
        val eJob2 = launch(testDispatcher) {
            throwVm.events.collect { ev ->
                if (ev is TopicDetailUiEvent.ShowMessage || ev is TopicDetailUiEvent.RequestWebViewAuth) {
                    throwSnapshots += throwVm.uiState.value.isMutatingTopic
                }
            }
        }
        throwVm.uiState.first { it.openingFloor != null }
        throwVm.updateCarpoolStatus("已满车")
        assertTrue("Expected exception failure event to arrive", throwSnapshots.isNotEmpty())
        assertTrue("isMutatingTopic must be false when exception event arrives", throwSnapshots.all { !it })
        assertFalse(throwVm.uiState.value.isMutatingTopic)
        sJob2.cancel()
        eJob2.cancel()

        // Case 3: Page 1 openingFloor == null when launched from Page 2
        val missingOpeningForum = FakeTopicForumRepository { _, p ->
            Result.success(
                page(pageNum = p, totalPages = 2, includeOpening = false).copy(
                    categorySlug = "carpool",
                    categoryName = "拼车",
                    title = "[3=1] Netflix 4K",
                ),
            )
        }
        val missingOpeningVm = TopicDetailViewModel(
            container = buildContainer(
                forum = missingOpeningForum,
                session = session,
                bookmarks = FakeTopicBookmarkRepository(),
                drafts = InMemoryDraftRepository(),
                users = FakeTopicUserRepository(),
            ),
            topicId = 913L,
            initialFloor = 15,
        )
        val missingSnapshots = mutableListOf<Boolean>()
        val sJob3 = launch(testDispatcher) { missingOpeningVm.uiState.collect {} }
        val eJob3 = launch(testDispatcher) {
            missingOpeningVm.events.collect { ev ->
                if (ev is TopicDetailUiEvent.ShowMessage || ev is TopicDetailUiEvent.RequestWebViewAuth) {
                    missingSnapshots += missingOpeningVm.uiState.value.isMutatingTopic
                }
            }
        }
        missingOpeningVm.uiState.first { it.pageData != null }
        missingOpeningVm.updateCarpoolStatus("已满车")
        assertTrue("Expected missing-opening failure event to arrive", missingSnapshots.isNotEmpty())
        assertTrue("isMutatingTopic must be false when missing-opening event arrives", missingSnapshots.all { !it })
        assertFalse(missingOpeningVm.uiState.value.isMutatingTopic)
        sJob3.cancel()
        eJob3.cancel()
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
        var postReplyCount = 0
        var editReplyCount = 0
        var editTopicHandler: (suspend (Long, String, String, Int) -> Result<Unit>)? = null
        var postReplyHandler: (suspend (Long, String) -> Result<Unit>)? = null
        var editReplyHandler: (suspend (Long, String) -> Result<Unit>)? = null
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
        ): Result<Unit> = editTopicHandler?.invoke(topicId, title, markdownContent, requiredViewLevel) ?: Result.success(Unit)

        override suspend fun postReply(topicId: Long, markdownContent: String): Result<Unit> {
            postReplyCount++
            return postReplyHandler?.invoke(topicId, markdownContent) ?: Result.success(Unit)
        }

        override suspend fun editReply(commentId: Long, markdownContent: String): Result<Unit> {
            editReplyCount++
            return editReplyHandler?.invoke(commentId, markdownContent) ?: Result.success(Unit)
        }
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
