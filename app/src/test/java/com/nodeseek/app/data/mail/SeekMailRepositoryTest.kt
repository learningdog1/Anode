package com.nodeseek.app.data.mail

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailException
import com.nodeseek.app.domain.mail.SeekMailSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SeekMailRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var apiClient: SeekMailApiClient
    private lateinit var repoScope: CoroutineScope

    private val dummyKeyA: String = buildString {
        append("sk_")
        append("live_")
        append("a".repeat(64))
    }
    private val dummyKeyB: String = buildString {
        append("sk_")
        append("live_")
        append("b".repeat(64))
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = Files.createTempDirectory("seek_mail_repo_test").toFile()
        settingsRepository = FakeSettingsRepository()
        apiClient = SeekMailApiClient(baseUrl = server.url("/openapi/v1"))
        repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        repoScope.cancel()
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    private fun createRepository(scope: CoroutineScope = repoScope): SeekMailRepositoryImpl {
        val diskCache = SeekMailDiskCache(cacheDir = cacheDir, ioScope = scope)
        return SeekMailRepositoryImpl(
            apiClient = apiClient,
            settingsRepository = settingsRepository,
            diskCache = diskCache,
            externalScope = scope,
        )
    }

    @Test
    fun `missing API key fails fast with MISSING_API_KEY without hitting network`() = runBlocking {
        val repo = createRepository()

        val checks = listOf(
            repo.verifyApiKey(),
            repo.getMailboxes(),
            repo.getMails(),
            repo.getUnreadMails(),
            repo.getMailDetail(1L),
            repo.createMailbox("newbox@seek.li"),
            repo.getTags(),
            repo.getContacts(),
        )

        checks.forEach { result ->
            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull() as? SeekMailException
            assertNotNull(ex)
            assertEquals(SeekMailErrorKind.MISSING_API_KEY, ex!!.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `verifyApiKey tests candidateKey against server without overwriting stored settings`() = runBlocking {
        val repo = createRepository()
        assertEquals("", settingsRepository.settings.value.seekMailApiKey)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Ratelimit-Limit", "60")
                .addHeader("X-Ratelimit-Remaining", "59")
                .addHeader("X-Ratelimit-Reset", "1763251200")
                .setBody("""{"data":[{"id":1,"address":"box1@seek.li","status":1}]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":[{"id":77,"mailboxId":1,"fromAddress":"a@b.com","fromName":"A","toAddress":"box1@seek.li","subject":"Unread","bodyText":"Hi","bodyHtml":"","isRead":false}],"page":{"hasMore":false,"limit":50,"nextCursor":""}}""",
                ),
        )

        val status = repo.verifyApiKey("  $dummyKeyA  ").getOrThrow()
        assertEquals(1, status.mailboxes.size)
        assertEquals("box1@seek.li", status.mailboxes.single().address)
        assertEquals(1, status.unreadSampleCount)
        assertEquals("", settingsRepository.settings.value.seekMailApiKey)
        assertEquals(59, repo.latestRateLimit.value?.remaining)

        val req1 = server.takeRequest()
        assertEquals("/openapi/v1/mailboxes", req1.path)
        assertEquals("Bearer $dummyKeyA", req1.getHeader("Authorization"))
        val req2 = server.takeRequest()
        assertEquals("/openapi/v1/mails/unread?limit=50", req2.path)
        assertEquals("Bearer $dummyKeyA", req2.getHeader("Authorization"))

        // Rejected candidate key propagates UNAUTHORIZED cleanly.
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"无效 Key"}}"""),
        )
        val failed = repo.verifyApiKey(dummyKeyB)
        assertTrue(failed.isFailure)
        assertEquals(
            SeekMailErrorKind.UNAUTHORIZED,
            (failed.exceptionOrNull() as SeekMailException).kind,
        )
    }

    @Test
    fun `disk cache falls back when upstream fails and isolates namespaces across key switches and clears`() = runBlocking {
        val repo = createRepository()
        settingsRepository.setSeekMailApiKey(dummyKeyA)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":1,"address":"keya@seek.li","status":1}]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":[{"id":101,"mailboxId":1,"fromAddress":"sender@example.com","fromName":"Sender","toAddress":"keya@seek.li","subject":"Key A Mail","bodyText":"Code 123456","bodyHtml":"","isRead":false}],"pagination":{"limit":20,"offset":0,"total":1}}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":{"mail":{"id":101,"mailboxId":1,"fromAddress":"sender@example.com","fromName":"Sender","toAddress":"keya@seek.li","subject":"Key A Mail","bodyText":"Code 123456","bodyHtml":"<p>Code 123456</p>","isRead":false},"tags":[]}}""",
                ),
        )

        assertEquals("keya@seek.li", repo.getMailboxes(forceRefresh = true).getOrThrow().single().address)
        assertEquals("Key A Mail", repo.getMails(forceRefresh = true).getOrThrow().items.single().subject)
        assertTrue(repo.getMailDetail(101L, forceRefresh = true).isSuccess)
        assertTrue(101L in repo.locallyReadMailIds.value)
        val requestsAfterSeed = server.requestCount

        // Cold-start repository with same Key A:
        // 1) Fresh mailboxes disk cache serves immediately without hitting network.
        // 2) getMails hits upstream 500 -> falls back to cached page with locallyRead overlay.
        // 3) getMailDetail serves cached detail from disk without hitting network.
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream down"))
        val coldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val coldRepo = createRepository(coldScope)
            val cachedBoxes = coldRepo.getMailboxes(forceRefresh = false).getOrThrow()
            assertEquals("keya@seek.li", cachedBoxes.single().address)
            assertEquals(requestsAfterSeed, server.requestCount)

            val fallbackMails = coldRepo.getMails(forceRefresh = false).getOrThrow()
            assertEquals("Key A Mail", fallbackMails.items.single().subject)
            assertTrue(fallbackMails.items.single().isRead)
            assertEquals(requestsAfterSeed + 1, server.requestCount)

            val cachedDetail = coldRepo.getMailDetail(101L, forceRefresh = false).getOrThrow()
            assertEquals("Key A Mail", cachedDetail.summary.subject)
            assertTrue(cachedDetail.summary.isRead)
            assertEquals(requestsAfterSeed + 1, server.requestCount)
        } finally {
            coldScope.cancel()
        }

        // Verify stale mailboxes cache also falls back when TTL expires and upstream fails.
        val staleCache = SeekMailDiskCache(cacheDir = cacheDir, ioScope = repoScope)
        val existingBoxes = staleCache.getMailboxes(dummyKeyA)!!.mailboxes
        staleCache.saveMailboxes(dummyKeyA, existingBoxes, fetchedAtEpochMillis = 1_000L)
        val staleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            server.enqueue(MockResponse().setResponseCode(500).setBody("upstream down"))
            val staleRepo = createRepository(staleScope)
            val fallbackStaleBoxes = staleRepo.getMailboxes(forceRefresh = false).getOrThrow()
            assertEquals("keya@seek.li", fallbackStaleBoxes.single().address)
        } finally {
            staleScope.cancel()
        }

        // Switching to Key B must isolate read state & disk cache namespace from Key A.
        settingsRepository.setSeekMailApiKey(dummyKeyB)
        withTimeout(5_000L) {
            repo.locallyReadMailIds.first { it.isEmpty() }
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":2,"address":"keyb@seek.li","status":1}]}"""),
        )
        val keyBBoxes = repo.getMailboxes(forceRefresh = false).getOrThrow()
        assertEquals("keyb@seek.li", keyBBoxes.single().address)
        val latestAuth = server.takeRequest().let {
            var last = it
            while (true) {
                val next = server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                last = next
            }
            last.getHeader("Authorization")
        }
        assertEquals("Bearer $dummyKeyB", latestAuth)

        // Switching back to Key A restores Key A's isolated local read state before clearing.
        settingsRepository.setSeekMailApiKey(dummyKeyA)
        withTimeout(5_000L) {
            repo.locallyReadMailIds.first { 101L in it }
        }

        // Clearing API key resets active state, wipes Key A cache files, and fails fast on subsequent reads.
        settingsRepository.setSeekMailApiKey("")
        withTimeout(5_000L) {
            repo.locallyReadMailIds.first { it.isEmpty() }
        }
        val afterClear = repo.getMailboxes(forceRefresh = false)
        assertTrue(afterClear.isFailure)
        assertEquals(
            SeekMailErrorKind.MISSING_API_KEY,
            (afterClear.exceptionOrNull() as SeekMailException).kind,
        )
        val verifyDiskCacheAfterKeyClear = SeekMailDiskCache(cacheDir = cacheDir, ioScope = repoScope)
        assertNull(verifyDiskCacheAfterKeyClear.getMailboxes(dummyKeyA))

        // Explicit clearCache() removes remaining namespaces (e.g. Key B).
        repo.clearCache()
        val verifyDiskCacheAfterClearAll = SeekMailDiskCache(cacheDir = cacheDir, ioScope = repoScope)
        assertNull(verifyDiskCacheAfterClearAll.getMailboxes(dummyKeyB))
    }

    @Test
    fun `markMailReadLocally and getMailDetail overlay isRead true onto getMails and getUnreadMails summaries`() = runBlocking {
        val repo = createRepository()
        settingsRepository.setSeekMailApiKey(dummyKeyA)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": {
                        "mail": {
                          "id": 501,
                          "mailboxId": 1,
                          "fromAddress": "auth@nodeseek.com",
                          "fromName": "NodeSeek",
                          "toAddress": "me@seek.li",
                          "subject": "Detail Mail",
                          "bodyText": "Body",
                          "bodyHtml": "<p>Body</p>",
                          "isRead": false
                        },
                        "tags": []
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {"id": 501, "mailboxId": 1, "fromAddress": "auth@nodeseek.com", "fromName": "NodeSeek", "toAddress": "me@seek.li", "subject": "Detail Mail", "bodyText": "Body", "bodyHtml": "", "isRead": false},
                        {"id": 502, "mailboxId": 1, "fromAddress": "auth@nodeseek.com", "fromName": "NodeSeek", "toAddress": "me@seek.li", "subject": "Marked Locally", "bodyText": "Body", "bodyHtml": "", "isRead": false},
                        {"id": 503, "mailboxId": 1, "fromAddress": "auth@nodeseek.com", "fromName": "NodeSeek", "toAddress": "me@seek.li", "subject": "Still Unread", "bodyText": "Body", "bodyHtml": "", "isRead": false}
                      ],
                      "pagination": {"limit": 20, "offset": 0, "total": 3}
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {"id": 501, "mailboxId": 1, "fromAddress": "auth@nodeseek.com", "fromName": "NodeSeek", "toAddress": "me@seek.li", "subject": "Detail Mail", "bodyText": "Body", "bodyHtml": "", "isRead": false},
                        {"id": 502, "mailboxId": 1, "fromAddress": "auth@nodeseek.com", "fromName": "NodeSeek", "toAddress": "me@seek.li", "subject": "Marked Locally", "bodyText": "Body", "bodyHtml": "", "isRead": false},
                        {"id": 503, "mailboxId": 1, "fromAddress": "auth@nodeseek.com", "fromName": "NodeSeek", "toAddress": "me@seek.li", "subject": "Still Unread", "bodyText": "Body", "bodyHtml": "", "isRead": false}
                      ],
                      "page": {"hasMore": false, "limit": 50, "nextCursor": ""}
                    }
                    """.trimIndent(),
                ),
        )

        val detail = repo.getMailDetail(501L).getOrThrow()
        assertTrue(detail.summary.isRead)
        assertTrue(501L in repo.locallyReadMailIds.value)

        repo.markMailReadLocally(502L)
        assertTrue(502L in repo.locallyReadMailIds.value)

        val page = repo.getMails(forceRefresh = true).getOrThrow()
        assertEquals(mapOf(501L to true, 502L to true, 503L to false), page.items.associate { it.id to it.isRead })

        val unreadPage = repo.getUnreadMails().getOrThrow()
        assertEquals(mapOf(501L to true, 502L to true, 503L to false), unreadPage.items.associate { it.id to it.isRead })
    }

    @Test
    fun `local tags persist per mail id across restarts and stay isolated per API key`(): Unit = runBlocking {
        val repo = createRepository()
        settingsRepository.setSeekMailApiKey(dummyKeyA)

        val vpsTag = repo.createLocalTag(name = "VPS 账单", colorHex = "#2D637E").getOrThrow()
        val duplicateTag = repo.createLocalTag(name = "  vps 账单 ", colorHex = "#999999").getOrThrow()
        assertEquals(vpsTag.id, duplicateTag.id)

        val urgentTag = repo.createLocalTag(name = "重要", colorHex = "#D97706").getOrThrow()
        repo.toggleLocalTagForMail(mailId = 801L, tagId = vpsTag.id)
        repo.toggleLocalTagForMail(mailId = 801L, tagId = urgentTag.id)
        repo.toggleLocalTagForMail(mailId = 802L, tagId = vpsTag.id)

        assertEquals(
            listOf(vpsTag.id, urgentTag.id),
            repo.localTagStore.value.tagsForMail(801L).map { it.id },
        )
        assertEquals(2, repo.localTagStore.value.mailCountForTag(vpsTag.id))

        // Toggle off removes tag cleanly
        repo.toggleLocalTagForMail(mailId = 801L, tagId = urgentTag.id)
        assertEquals(listOf(vpsTag.id), repo.localTagStore.value.tagsForMail(801L).map { it.id })

        // Cold-start repo with same API key reloads persisted local tags
        val coldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val coldRepo = createRepository(coldScope)
            withTimeout(5_000L) {
                coldRepo.localTagStore.first { it.tags.size == 2 }
            }
            assertEquals(
                listOf("VPS 账单"),
                coldRepo.localTagStore.value.tagsForMail(801L).map { it.name },
            )
            assertEquals(
                listOf("VPS 账单"),
                coldRepo.localTagStore.value.tagsForMail(802L).map { it.name },
            )

            // Deleting a tag removes it from definitions and all mail assignments
            coldRepo.deleteLocalTag(vpsTag.id)
            assertTrue(coldRepo.localTagStore.value.tagsForMail(801L).isEmpty())
            assertTrue(coldRepo.localTagStore.value.tagsForMail(802L).isEmpty())
            assertEquals(listOf(urgentTag.id), coldRepo.localTagStore.value.tags.map { it.id })
        } finally {
            coldScope.cancel()
        }

        // Switching to Key B isolates local tag store
        settingsRepository.setSeekMailApiKey(dummyKeyB)
        withTimeout(5_000L) {
            repo.localTagStore.first { it.tags.isEmpty() && it.mailTagIds.isEmpty() }
        }
    }

    @Test
    fun `local archive and delete persist across restarts, support undo restore, and stay isolated per API key`(): Unit = runBlocking {
        val repo = createRepository()
        settingsRepository.setSeekMailApiKey(dummyKeyA)

        val mailA = SeekMailSummary(
            id = 901L,
            mailboxId = 10L,
            fromAddress = "billing@dmit.io",
            fromName = "DMIT",
            toAddress = "box1@seek.li",
            subject = "Invoice #901",
            snippet = "Paid invoice",
            receivedAt = "2026-11-23T08:00:00Z",
        )
        val mailB = SeekMailSummary(
            id = 902L,
            mailboxId = 20L,
            fromAddress = "alerts@nodeseek.com",
            fromName = "NodeSeek",
            toAddress = "box2@seek.li",
            subject = "Security Alert",
            snippet = "New login",
            receivedAt = "2026-11-23T09:00:00Z",
        )

        repo.archiveMailLocally(mailA)
        repo.archiveMailLocally(mailB)
        repo.deleteMailLocally(903L)

        assertTrue(repo.localArchiveStore.value.isArchived(901L))
        assertTrue(repo.localArchiveStore.value.isArchived(902L))
        assertTrue(repo.localArchiveStore.value.isDeleted(903L))
        assertEquals(listOf(902L, 901L), repo.localArchiveStore.value.orderedArchivedMails().map { it.id })
        assertEquals(listOf(901L), repo.localArchiveStore.value.orderedArchivedMails(mailboxId = 10L).map { it.id })

        // Cold-start repo reloads persisted archive & deleted state for Key A
        val coldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val coldRepo = createRepository(coldScope)
            withTimeout(5_000L) {
                coldRepo.localArchiveStore.first { it.archivedMails.size == 2 && 903L in it.deletedMailIds }
            }
            assertEquals(listOf(902L, 901L), coldRepo.localArchiveStore.value.orderedArchivedMails().map { it.id })

            // Deleting an archived mail removes it from archive and adds it to deletedMailIds
            coldRepo.deleteMailLocally(902L)
            assertTrue(coldRepo.localArchiveStore.value.isDeleted(902L))
            assertEquals(listOf(901L), coldRepo.localArchiveStore.value.orderedArchivedMails().map { it.id })

            // Restoring a deleted archived mail puts it back in archive
            coldRepo.restoreDeletedMailLocally(mailId = 902L, restoredArchiveSummary = mailB)
            assertTrue(coldRepo.localArchiveStore.value.isArchived(902L))
            assertTrue(!coldRepo.localArchiveStore.value.isDeleted(902L))

            // Unarchiving removes mail from archive without deleting it
            coldRepo.unarchiveMailLocally(901L)
            assertTrue(!coldRepo.localArchiveStore.value.isArchived(901L))
            assertTrue(!coldRepo.localArchiveStore.value.isDeleted(901L))
            assertEquals(listOf(902L), coldRepo.localArchiveStore.value.orderedArchivedMails().map { it.id })
        } finally {
            coldScope.cancel()
        }

        // Switching to Key B isolates local archive & delete store
        settingsRepository.setSeekMailApiKey(dummyKeyB)
        withTimeout(5_000L) {
            repo.localArchiveStore.first { it.archivedMails.isEmpty() && it.deletedMailIds.isEmpty() }
        }
    }

    @Test
    fun `batch read, tag, archive, delete, and restore operations persist atomically across restarts`(): Unit = runBlocking {
        val repo = createRepository()
        settingsRepository.setSeekMailApiKey(dummyKeyA)

        val m1 = SeekMailSummary(
            id = 1001L,
            mailboxId = 10L,
            fromAddress = "a@example.com",
            fromName = "A",
            toAddress = "box@seek.li",
            subject = "One",
            snippet = "S1",
            receivedAt = "2026-11-23T08:00:00Z",
        )
        val m2 = SeekMailSummary(
            id = 1002L,
            mailboxId = 10L,
            fromAddress = "b@example.com",
            fromName = "B",
            toAddress = "box@seek.li",
            subject = "Two",
            snippet = "S2",
            receivedAt = "2026-11-23T09:00:00Z",
        )
        val m3 = SeekMailSummary(
            id = 1003L,
            mailboxId = 10L,
            fromAddress = "c@example.com",
            fromName = "C",
            toAddress = "box@seek.li",
            subject = "Three",
            snippet = "S3",
            receivedAt = "2026-11-23T10:00:00Z",
        )

        val tag = repo.createLocalTag("批量", "#2563EB").getOrThrow()
        repo.addLocalTagToMails(listOf(1001L, 1002L, 1003L), tag.id)
        assertEquals(3, repo.localTagStore.value.mailCountForTag(tag.id))

        repo.removeLocalTagFromMails(listOf(1002L), tag.id)
        assertEquals(2, repo.localTagStore.value.mailCountForTag(tag.id))

        repo.markMailsReadLocally(listOf(1001L, 1002L))
        assertTrue(1001L in repo.locallyReadMailIds.value && 1002L in repo.locallyReadMailIds.value)

        repo.archiveMailsLocally(listOf(m1, m2))
        assertEquals(listOf(1002L, 1001L), repo.localArchiveStore.value.orderedArchivedMails().map { it.id })

        repo.deleteMailsLocally(listOf(1002L, 1003L))
        assertTrue(repo.localArchiveStore.value.isDeleted(1002L))
        assertTrue(repo.localArchiveStore.value.isDeleted(1003L))
        assertEquals(listOf(1001L), repo.localArchiveStore.value.orderedArchivedMails().map { it.id })

        repo.restoreDeletedMailsLocally(
            mailIds = listOf(1002L, 1003L),
            restoredArchiveSummaries = listOf(m2),
        )
        assertTrue(repo.localArchiveStore.value.isArchived(1002L))
        assertTrue(!repo.localArchiveStore.value.isArchived(1003L))
        assertTrue(!repo.localArchiveStore.value.isDeleted(1003L))

        val coldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val coldRepo = createRepository(coldScope)
            withTimeout(5_000L) {
                coldRepo.localArchiveStore.first { it.archivedMails.size == 2 }
            }
            assertEquals(setOf(1001L, 1002L), coldRepo.locallyReadMailIds.value)
            assertEquals(2, coldRepo.localTagStore.value.mailCountForTag(tag.id))

            coldRepo.clearLocalTagsForMails(listOf(1001L, 1003L))
            assertEquals(0, coldRepo.localTagStore.value.mailCountForTag(tag.id))
        } finally {
            coldScope.cancel()
        }
    }
}
