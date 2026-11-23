package com.nodeseek.app.ui.mail

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailConnectionStatus
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailLocalArchiveStore
import com.nodeseek.app.domain.mail.SeekMailLocalTag
import com.nodeseek.app.domain.mail.SeekMailLocalTagStore
import com.nodeseek.app.domain.mail.SeekMailPage
import com.nodeseek.app.domain.mail.SeekMailRateLimit
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailUnreadPage
import com.nodeseek.app.domain.mail.SeekMailbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
class SeekMailArchiveDeleteViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleMail(
        id: Long,
        mailboxId: Long = 1L,
        subject: String = "Mail #$id",
        fromName: String = "Sender $id",
        isRead: Boolean = false,
        receivedAt: String = "2026-11-23T10:0$id:00Z",
    ) = SeekMailSummary(
        id = id,
        mailboxId = mailboxId,
        fromAddress = "sender$id@example.com",
        fromName = fromName,
        toAddress = "box$mailboxId@seek.li",
        subject = subject,
        snippet = "Body snippet for $subject",
        isRead = isRead,
        receivedAt = receivedAt,
    )

    @Test
    fun `long-press archive, archived filter, mailbox scoping, search, unarchive, and undo work seamlessly`() = runTest {
        val mail1 = sampleMail(id = 1L, mailboxId = 10L, subject = "DMIT Renewal", isRead = false, receivedAt = "2026-11-23T10:01:00Z")
        val mail2 = sampleMail(id = 2L, mailboxId = 20L, subject = "GitHub OTP", isRead = false, receivedAt = "2026-11-23T10:02:00Z")
        val mail3 = sampleMail(id = 3L, mailboxId = 10L, subject = "Newsletter", isRead = true, receivedAt = "2026-11-23T10:03:00Z")

        val repo = FakeSeekMailRepository(
            mailboxes = listOf(
                SeekMailbox(id = 10L, address = "vps@seek.li"),
                SeekMailbox(id = 20L, address = "dev@seek.li"),
            ),
            allMails = listOf(mail3, mail2, mail1),
        )
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_test_key_1234567890")
        }

        val vm = SeekMailViewModel(
            seekMailRepository = repo,
            settingsRepository = settings,
        )
        val snackbars = mutableListOf<SeekMailSnackbarEvent>()
        val stateJob = launch(testDispatcher) { vm.uiState.collect {} }
        val snackbarJob = launch(testDispatcher) { vm.snackbarEvents.collect { snackbars += it } }

        try {
            assertEquals(listOf(3L, 2L, 1L), vm.uiState.value.mails.map { it.id })
            assertEquals(2, vm.uiState.value.unreadLoadedCount)
            assertEquals(0, vm.uiState.value.archivedMailCount)

            // Long-press opens action sheet, and can switch to tag sheet
            vm.openMailActionSheet(mail1)
            assertEquals(1L, vm.uiState.value.actionTargetMail?.id)
            vm.openTagSheetFromActionSheet()
            assertNull(vm.uiState.value.actionTargetMail)
            assertEquals(1L, vm.uiState.value.taggingTargetMail?.id)
            vm.dismissMailTagSheet()

            // Archive mail1 and mail2
            vm.openMailActionSheet(mail1)
            vm.archiveMail(mail1)
            assertNull(vm.uiState.value.actionTargetMail)
            assertEquals(listOf(3L, 2L), vm.uiState.value.mails.map { it.id })
            assertEquals(1, vm.uiState.value.unreadLoadedCount)
            assertEquals(1, vm.uiState.value.archivedMailCount)
            assertEquals("已存档邮件", snackbars.last().message)
            assertEquals("撤销", snackbars.last().actionLabel)

            // Undo archive restores mail1 to inbox
            snackbars.last().onAction!!.invoke()
            assertEquals(listOf(3L, 2L, 1L), vm.uiState.value.mails.map { it.id })
            assertEquals(0, vm.uiState.value.archivedMailCount)

            // Re-archive mail1 and mail2, then switch to ARCHIVED filter
            vm.archiveMail(mail1)
            vm.archiveMail(mail2)
            val getMailsCallsBeforeArchived = repo.getMailsCallCount
            vm.selectFilterMode(SeekMailFilterMode.ARCHIVED)

            assertEquals(SeekMailFilterMode.ARCHIVED, vm.uiState.value.filterMode)
            assertEquals(listOf(2L, 1L), vm.uiState.value.mails.map { it.id })
            assertEquals(getMailsCallsBeforeArchived, repo.getMailsCallCount)

            // Search within archived mails locally without extra network calls
            vm.submitSearch("DMIT")
            assertEquals(SeekMailFilterMode.ARCHIVED, vm.uiState.value.filterMode)
            assertEquals(listOf(1L), vm.uiState.value.mails.map { it.id })
            assertEquals(getMailsCallsBeforeArchived, repo.getMailsCallCount)
            vm.clearSearch()

            // Select mailbox 10 while in ARCHIVED scopes archived list to mailbox 10
            vm.selectMailbox(10L)
            assertEquals(listOf(1L), vm.uiState.value.mails.map { it.id })
            assertEquals(1, vm.uiState.value.archivedMailCount)

            // Unarchive mail1 and switch back to ALL
            vm.unarchiveMail(mail1)
            assertTrue(vm.uiState.value.mails.isEmpty())
            vm.selectFilterMode(SeekMailFilterMode.ALL)
            assertEquals(listOf(3L, 1L), vm.uiState.value.mails.map { it.id })
        } finally {
            stateJob.cancel()
            snackbarJob.cancel()
        }
    }

    @Test
    fun `delete mail hides from inbox and tag counts, closes opened detail, and undo restores archive and tags`() = runTest {
        val mail1 = sampleMail(id = 11L, mailboxId = 10L, subject = "VPS Invoice", isRead = false)
        val mail2 = sampleMail(id = 12L, mailboxId = 10L, subject = "Promo", isRead = true)

        val repo = FakeSeekMailRepository(
            mailboxes = listOf(SeekMailbox(id = 10L, address = "vps@seek.li")),
            allMails = listOf(mail1, mail2),
        )
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_test_key_1234567890")
        }

        val vm = SeekMailViewModel(
            seekMailRepository = repo,
            settingsRepository = settings,
        )
        val snackbars = mutableListOf<SeekMailSnackbarEvent>()
        val stateJob = launch(testDispatcher) { vm.uiState.collect {} }
        val snackbarJob = launch(testDispatcher) { vm.snackbarEvents.collect { snackbars += it } }

        try {
            // Tag mail1 and archive it; local tag filter should still surface tagged archived mail
            vm.createAndAttachLocalTag(mailId = 11L, name = "账单", colorHex = "#0F766E")
            val tagId = vm.uiState.value.localTags.single().id
            assertEquals(1, vm.uiState.value.localTagCounts[tagId])

            vm.archiveMail(mail1)
            vm.toggleLocalTagFilter(tagId)
            assertEquals(listOf(11L), vm.uiState.value.mails.map { it.id })
            assertTrue(11L in vm.uiState.value.archivedMailIds)

            // Open mail1 detail, request delete, confirm delete -> closes detail & hides from tags/archive
            vm.openMail(mail1)
            assertEquals(11L, vm.uiState.value.openedMailId)

            vm.requestDeleteMail(mail1)
            assertEquals(11L, vm.uiState.value.pendingDeleteMail?.id)

            vm.confirmDeleteMail()
            assertNull(vm.uiState.value.pendingDeleteMail)
            assertNull(vm.uiState.value.openedMailId)
            assertTrue(vm.uiState.value.mails.isEmpty())
            assertEquals(0, vm.uiState.value.archivedMailCount)
            assertNull(vm.uiState.value.localTagCounts[tagId])
            assertEquals("已从本地删除该邮件", snackbars.last().message)
            assertEquals("撤销", snackbars.last().actionLabel)

            // Undo delete restores mail1 into archived state with its local tag intact
            snackbars.last().onAction!!.invoke()
            assertEquals(1, vm.uiState.value.archivedMailCount)
            assertTrue(11L in vm.uiState.value.archivedMailIds)
            assertEquals(1, vm.uiState.value.localTagCounts[tagId])
            assertEquals(listOf(11L), vm.uiState.value.mails.map { it.id })
            assertFalse(repo.localArchiveStore.value.isDeleted(11L))
        } finally {
            stateJob.cancel()
            snackbarJob.cancel()
        }
    }

    @Test
    fun `multi-select supports avatar toggle, select all, batch archive, batch read, batch tag, and batch delete with undo`() = runTest {
        val mail1 = sampleMail(id = 21L, mailboxId = 10L, subject = "Batch One", isRead = false, receivedAt = "2026-11-23T10:01:00Z")
        val mail2 = sampleMail(id = 22L, mailboxId = 10L, subject = "Batch Two", isRead = false, receivedAt = "2026-11-23T10:02:00Z")
        val mail3 = sampleMail(id = 23L, mailboxId = 10L, subject = "Batch Three", isRead = true, receivedAt = "2026-11-23T10:03:00Z")

        val repo = FakeSeekMailRepository(
            mailboxes = listOf(SeekMailbox(id = 10L, address = "ops@seek.li")),
            allMails = listOf(mail3, mail2, mail1),
        )
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_test_key_1234567890")
        }

        val vm = SeekMailViewModel(
            seekMailRepository = repo,
            settingsRepository = settings,
        )
        val snackbars = mutableListOf<SeekMailSnackbarEvent>()
        val stateJob = launch(testDispatcher) { vm.uiState.collect {} }
        val snackbarJob = launch(testDispatcher) { vm.snackbarEvents.collect { snackbars += it } }

        try {
            // Tapping avatar (toggleMailSelection) enters selection mode directly
            vm.toggleMailSelection(21L)
            assertTrue(vm.uiState.value.isSelectionMode)
            assertEquals(setOf(21L), vm.uiState.value.selectedMailIds)
            assertFalse(vm.uiState.value.isAllVisibleSelected)
            assertTrue(vm.uiState.value.hasUnreadSelected)

            // Select all visible & toggle off
            vm.toggleSelectAllVisible()
            assertEquals(setOf(23L, 22L, 21L), vm.uiState.value.selectedMailIds)
            assertTrue(vm.uiState.value.isAllVisibleSelected)

            // Batch tag all 3 selected mails
            vm.openBatchTagSheet()
            assertEquals(3, vm.uiState.value.batchTaggingMails.size)
            vm.createAndAttachLocalTagForBatch(name = "批量处理", colorHex = "#2563EB")
            val tagId = vm.uiState.value.localTags.single().id
            assertEquals(3, vm.uiState.value.localTagCounts[tagId])
            // Toggling a tag already on all 3 removes it from all 3
            vm.toggleLocalTagForBatch(tagId)
            assertNull(vm.uiState.value.localTagCounts[tagId])
            // Toggling again adds it back to all 3
            vm.toggleLocalTagForBatch(tagId)
            assertEquals(3, vm.uiState.value.localTagCounts[tagId])
            vm.dismissBatchTagSheet()

            // Batch mark read marks the 2 unread mails read & exits selection mode
            vm.markSelectedMailsRead()
            assertFalse(vm.uiState.value.isSelectionMode)
            assertEquals(0, vm.uiState.value.unreadLoadedCount)
            assertEquals("已将 2 封邮件标为已读", snackbars.last().message)

            // Enter selection mode from long-press action sheet and select a second mail
            vm.openMailActionSheet(mail1)
            vm.enterSelectionModeFromActionSheet()
            assertTrue(vm.uiState.value.isSelectionMode)
            assertEquals(setOf(21L), vm.uiState.value.selectedMailIds)
            vm.toggleMailSelection(22L)
            assertEquals(setOf(21L, 22L), vm.uiState.value.selectedMailIds)

            // Batch archive 2 mails + undo
            vm.archiveSelectedMails()
            assertFalse(vm.uiState.value.isSelectionMode)
            assertEquals(listOf(23L), vm.uiState.value.mails.map { it.id })
            assertEquals(2, vm.uiState.value.archivedMailCount)
            assertEquals("已存档 2 封邮件", snackbars.last().message)
            assertEquals("撤销", snackbars.last().actionLabel)

            snackbars.last().onAction!!.invoke()
            assertEquals(listOf(23L, 22L, 21L), vm.uiState.value.mails.map { it.id })
            assertEquals(0, vm.uiState.value.archivedMailCount)

            // Archive mail1 & mail2 again, switch to ARCHIVED, select both and batch delete + undo
            vm.toggleMailSelection(21L)
            vm.toggleMailSelection(22L)
            vm.archiveSelectedMails()
            vm.selectFilterMode(SeekMailFilterMode.ARCHIVED)
            assertEquals(listOf(22L, 21L), vm.uiState.value.mails.map { it.id })

            vm.enterSelectionMode()
            vm.toggleSelectAllVisible()
            assertTrue(vm.uiState.value.allSelectedAreArchived)

            // Switching filter clears selection mode cleanly
            vm.selectFilterMode(SeekMailFilterMode.ALL)
            assertFalse(vm.uiState.value.isSelectionMode)
            assertTrue(vm.uiState.value.selectedMailIds.isEmpty())

            // Back to ARCHIVED, select both and batch delete with confirmation + undo
            vm.selectFilterMode(SeekMailFilterMode.ARCHIVED)
            vm.toggleSelectAllVisible()
            vm.requestDeleteSelectedMails()
            assertEquals(2, vm.uiState.value.pendingBatchDeleteMails.size)

            vm.confirmDeleteSelectedMails()
            assertFalse(vm.uiState.value.isSelectionMode)
            assertTrue(vm.uiState.value.pendingBatchDeleteMails.isEmpty())
            assertTrue(vm.uiState.value.mails.isEmpty())
            assertEquals(0, vm.uiState.value.archivedMailCount)
            assertEquals(1, vm.uiState.value.localTagCounts[tagId])
            assertEquals("已从本地删除 2 封邮件", snackbars.last().message)
            assertEquals("撤销", snackbars.last().actionLabel)

            // Undo restores both archived mails & their local tag counts
            snackbars.last().onAction!!.invoke()
            assertEquals(2, vm.uiState.value.archivedMailCount)
            assertEquals(listOf(22L, 21L), vm.uiState.value.mails.map { it.id })
            assertEquals(3, vm.uiState.value.localTagCounts[tagId])
        } finally {
            stateJob.cancel()
            snackbarJob.cancel()
        }
    }

    private class FakeSeekMailRepository(
        private val mailboxes: List<SeekMailbox>,
        private val allMails: List<SeekMailSummary>,
    ) : SeekMailRepository {
        private val _locallyReadMailIds = MutableStateFlow<Set<Long>>(emptySet())
        override val locallyReadMailIds: StateFlow<Set<Long>> = _locallyReadMailIds.asStateFlow()

        private val _latestRateLimit = MutableStateFlow<SeekMailRateLimit?>(null)
        override val latestRateLimit: StateFlow<SeekMailRateLimit?> = _latestRateLimit.asStateFlow()

        private val _localTagStore = MutableStateFlow(SeekMailLocalTagStore())
        override val localTagStore: StateFlow<SeekMailLocalTagStore> = _localTagStore.asStateFlow()

        private val _localArchiveStore = MutableStateFlow(SeekMailLocalArchiveStore())
        override val localArchiveStore: StateFlow<SeekMailLocalArchiveStore> = _localArchiveStore.asStateFlow()

        var getMailsCallCount: Int = 0
            private set

        override suspend fun verifyApiKey(candidateKey: String?): Result<SeekMailConnectionStatus> =
            Result.success(SeekMailConnectionStatus(mailboxes = mailboxes))

        override suspend fun getMailboxes(forceRefresh: Boolean): Result<List<SeekMailbox>> =
            Result.success(mailboxes)

        override suspend fun getMailbox(id: Long): Result<SeekMailbox> =
            Result.success(mailboxes.first { it.id == id })

        override suspend fun checkMailboxAvailable(address: String): Result<Boolean> =
            Result.success(true)

        override suspend fun createMailbox(address: String): Result<SeekMailbox> =
            Result.success(SeekMailbox(id = 999L, address = address))

        override suspend fun getMails(
            mailboxId: Long?,
            search: String?,
            tagId: Long?,
            limit: Int,
            offset: Int,
            forceRefresh: Boolean,
        ): Result<SeekMailPage> {
            getMailsCallCount++
            val filtered = allMails.filter { mail ->
                (mailboxId == null || mail.mailboxId == mailboxId) &&
                    (search.isNullOrBlank() || mail.subject.contains(search, ignoreCase = true))
            }
            return Result.success(
                SeekMailPage(
                    items = filtered,
                    limit = limit,
                    offset = offset,
                    total = filtered.size,
                    hasMore = false,
                ),
            )
        }

        override suspend fun getUnreadMails(
            mailboxId: Long?,
            limit: Int,
            cursor: String?,
        ): Result<SeekMailUnreadPage> {
            val unread = allMails.filter { !it.isRead && (mailboxId == null || it.mailboxId == mailboxId) }
            return Result.success(
                SeekMailUnreadPage(items = unread, limit = limit, hasMore = false),
            )
        }

        override suspend fun getMailDetail(mailId: Long, forceRefresh: Boolean): Result<SeekMailDetail> {
            val summary = allMails.first { it.id == mailId }
            markMailReadLocally(mailId)
            return Result.success(
                SeekMailDetail(
                    summary = summary.copy(isRead = true),
                    bodyText = summary.snippet,
                    bodyHtml = "<p>${summary.snippet}</p>",
                    sanitizedBodyHtml = "<p>${summary.snippet}</p>",
                ),
            )
        }

        override suspend fun getRawMailEml(mailId: Long): Result<String> = Result.success("")

        override suspend fun getAttachments(mailId: Long): Result<List<SeekMailAttachment>> =
            Result.success(emptyList())

        override suspend fun downloadAttachmentBytes(mailId: Long, attachmentId: String): Result<ByteArray> =
            Result.success(ByteArray(0))

        override suspend fun getTags(forceRefresh: Boolean): Result<List<SeekMailTag>> =
            Result.success(emptyList())

        override suspend fun getContacts(limit: Int, forceRefresh: Boolean): Result<List<SeekMailContact>> =
            Result.success(emptyList())

        override suspend fun markMailReadLocally(mailId: Long) {
            _locallyReadMailIds.update { it + mailId }
        }

        override suspend fun createLocalTag(name: String, colorHex: String): Result<SeekMailLocalTag> {
            val created = SeekMailLocalTag(id = "tag_${name.hashCode()}", name = name.trim(), colorHex = colorHex)
            _localTagStore.update { current ->
                current.copy(tags = (current.tags + created).distinctBy { it.id })
            }
            return Result.success(created)
        }

        override suspend fun toggleLocalTagForMail(mailId: Long, tagId: String) {
            _localTagStore.update { current ->
                val ids = current.mailTagIds[mailId].orEmpty()
                val next = if (tagId in ids) ids - tagId else ids + tagId
                val nextMap = current.mailTagIds.toMutableMap()
                if (next.isEmpty()) nextMap.remove(mailId) else nextMap[mailId] = next
                current.copy(mailTagIds = nextMap)
            }
        }

        override suspend fun archiveMailLocally(summary: SeekMailSummary) {
            _localArchiveStore.update { current ->
                current.copy(
                    archivedMails = current.archivedMails + (summary.id to summary),
                    archivedAtMillis = current.archivedAtMillis + (summary.id to System.currentTimeMillis()),
                    deletedMailIds = current.deletedMailIds - summary.id,
                )
            }
        }

        override suspend fun unarchiveMailLocally(mailId: Long) {
            _localArchiveStore.update { current ->
                current.copy(
                    archivedMails = current.archivedMails - mailId,
                    archivedAtMillis = current.archivedAtMillis - mailId,
                )
            }
        }

        override suspend fun deleteMailLocally(mailId: Long) {
            _localArchiveStore.update { current ->
                current.copy(
                    archivedMails = current.archivedMails - mailId,
                    archivedAtMillis = current.archivedAtMillis - mailId,
                    deletedMailIds = current.deletedMailIds + mailId,
                )
            }
        }

        override suspend fun restoreDeletedMailLocally(
            mailId: Long,
            restoredArchiveSummary: SeekMailSummary?,
        ) {
            _localArchiveStore.update { current ->
                current.copy(
                    archivedMails = if (restoredArchiveSummary != null) {
                        current.archivedMails + (mailId to restoredArchiveSummary)
                    } else {
                        current.archivedMails
                    },
                    archivedAtMillis = if (restoredArchiveSummary != null) {
                        current.archivedAtMillis + (mailId to System.currentTimeMillis())
                    } else {
                        current.archivedAtMillis
                    },
                    deletedMailIds = current.deletedMailIds - mailId,
                )
            }
        }

        override suspend fun clearCache() {
            _locallyReadMailIds.value = emptySet()
            _localTagStore.value = SeekMailLocalTagStore()
            _localArchiveStore.value = SeekMailLocalArchiveStore()
        }
    }
}
