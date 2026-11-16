package com.nodeseek.app.domain.mail

import kotlinx.coroutines.flow.StateFlow

/**
 * Domain repository for Seek Mail (`seek.li`) OpenAPI operations, local read tracking,
 * and client-side response caching.
 */
interface SeekMailRepository {
    val locallyReadMailIds: StateFlow<Set<Long>>
    val latestRateLimit: StateFlow<SeekMailRateLimit?>

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

    suspend fun clearCache()
}
