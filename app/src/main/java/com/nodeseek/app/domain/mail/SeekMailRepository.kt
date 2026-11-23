package com.nodeseek.app.domain.mail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val EMPTY_LOCAL_TAG_STORE_FLOW: StateFlow<SeekMailLocalTagStore> =
    MutableStateFlow(SeekMailLocalTagStore()).asStateFlow()

private val EMPTY_LOCAL_ARCHIVE_STORE_FLOW: StateFlow<SeekMailLocalArchiveStore> =
    MutableStateFlow(SeekMailLocalArchiveStore()).asStateFlow()

/**
 * Domain repository for Seek Mail (`seek.li`) OpenAPI operations, local read/tag/archive tracking,
 * and client-side response caching.
 */
interface SeekMailRepository {
    val locallyReadMailIds: StateFlow<Set<Long>>
    val latestRateLimit: StateFlow<SeekMailRateLimit?>
    val localTagStore: StateFlow<SeekMailLocalTagStore>
        get() = EMPTY_LOCAL_TAG_STORE_FLOW
    val localArchiveStore: StateFlow<SeekMailLocalArchiveStore>
        get() = EMPTY_LOCAL_ARCHIVE_STORE_FLOW

    suspend fun verifyApiKey(candidateKey: String? = null): Result<SeekMailConnectionStatus>

    suspend fun getMailboxes(forceRefresh: Boolean = false): Result<List<SeekMailbox>>

    suspend fun getMailbox(id: Long): Result<SeekMailbox>

    suspend fun checkMailboxAvailable(address: String): Result<Boolean>

    suspend fun createMailbox(address: String): Result<SeekMailbox>

    suspend fun getMails(
        mailboxId: Long? = null,
        search: String? = null,
        tagId: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
        forceRefresh: Boolean = false,
    ): Result<SeekMailPage>

    suspend fun getUnreadMails(
        mailboxId: Long? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): Result<SeekMailUnreadPage>

    suspend fun getMailDetail(
        mailId: Long,
        forceRefresh: Boolean = false,
    ): Result<SeekMailDetail>

    suspend fun getRawMailEml(mailId: Long): Result<String>

    suspend fun getAttachments(mailId: Long): Result<List<SeekMailAttachment>>

    suspend fun downloadAttachmentBytes(
        mailId: Long,
        attachmentId: String,
    ): Result<ByteArray>

    suspend fun getTags(forceRefresh: Boolean = false): Result<List<SeekMailTag>>

    suspend fun getContacts(
        limit: Int = 50,
        forceRefresh: Boolean = false,
    ): Result<List<SeekMailContact>>

    suspend fun markMailReadLocally(mailId: Long)

    suspend fun createLocalTag(
        name: String,
        colorHex: String,
    ): Result<SeekMailLocalTag> = Result.failure(UnsupportedOperationException("Local mail tags are not supported"))

    suspend fun deleteLocalTag(tagId: String) = Unit

    suspend fun toggleLocalTagForMail(mailId: Long, tagId: String) = Unit

    suspend fun setLocalTagsForMail(mailId: Long, tagIds: List<String>) = Unit

    suspend fun archiveMailLocally(summary: SeekMailSummary) = Unit

    suspend fun unarchiveMailLocally(mailId: Long) = Unit

    suspend fun deleteMailLocally(mailId: Long) = Unit

    suspend fun restoreDeletedMailLocally(
        mailId: Long,
        restoredArchiveSummary: SeekMailSummary? = null,
    ) = Unit

    suspend fun clearCache()
}
