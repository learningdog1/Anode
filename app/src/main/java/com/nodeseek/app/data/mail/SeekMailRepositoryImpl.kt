package com.nodeseek.app.data.mail

import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailConnectionStatus
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailException
import com.nodeseek.app.domain.mail.SeekMailPage
import com.nodeseek.app.domain.mail.SeekMailRateLimit
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailUnreadPage
import com.nodeseek.app.domain.mail.SeekMailbox
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Concrete [SeekMailRepository] orchestrating [SeekMailApiClient], key-isolated [SeekMailDiskCache],
 * local read-state overlay, and reactive API key switching via [SettingsRepository].
 */
class SeekMailRepositoryImpl(
    private val apiClient: SeekMailApiClient,
    private val settingsRepository: SettingsRepository,
    private val diskCache: SeekMailDiskCache? = null,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SeekMailRepository {

    private val stateMutex = Mutex()
    private var activeKey: String = settingsRepository.settings.value.seekMailApiKey.trim()
    private var loadedReadIdsForKey: String? = null

    private val _locallyReadMailIds = MutableStateFlow<Set<Long>>(emptySet())
    override val locallyReadMailIds: StateFlow<Set<Long>> = _locallyReadMailIds.asStateFlow()

    override val latestRateLimit: StateFlow<SeekMailRateLimit?>
        get() = apiClient.latestRateLimit

    init {
        externalScope.launch {
            settingsRepository.settings
                .map { it.seekMailApiKey.trim() }
                .distinctUntilChanged()
                .collect { observedKey ->
                    reconcileKeyChange(observedKey)
                }
        }
    }

    override suspend fun verifyApiKey(candidateKey: String?): Result<SeekMailConnectionStatus> =
        runMailCatching {
            val savedKey = reconcileActiveKey()
            val keyToTest = (candidateKey ?: savedKey).trim()
            if (keyToTest.isEmpty()) {
                throw SeekMailException(
                    kind = SeekMailErrorKind.MISSING_API_KEY,
                    message = "请先配置 Seek 邮箱 API Key",
                )
            }
            val mailboxes = apiClient.getMailboxes(keyToTest)
            val unreadPage = try {
                apiClient.getUnreadMails(apiKey = keyToTest, limit = 50)
            } catch (c: CancellationException) {
                throw c
            } catch (e: SeekMailException) {
                if (e.kind == SeekMailErrorKind.FORBIDDEN || e.kind == SeekMailErrorKind.NOT_FOUND) {
                    null
                } else {
                    throw e
                }
            }
            SeekMailConnectionStatus(
                mailboxes = mailboxes,
                unreadSampleCount = unreadPage?.items?.size ?: 0,
                hasMoreUnread = unreadPage?.hasMore ?: false,
                rateLimit = apiClient.latestRateLimit.value,
            )
        }

    override suspend fun getMailboxes(forceRefresh: Boolean): Result<List<SeekMailbox>> =
        runMailCatching {
            val key = requireApiKey()
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                val cached = diskCache?.getMailboxes(key)
                if (cached != null && isFresh(cached.fetchedAtEpochMillis, now, METADATA_CACHE_TTL_MS)) {
                    return@runMailCatching cached.mailboxes
                }
            }
            try {
                val fresh = apiClient.getMailboxes(key)
                diskCache?.saveMailboxes(key, fresh, now)
                fresh
            } catch (c: CancellationException) {
                throw c
            } catch (e: SeekMailException) {
                if (!forceRefresh && shouldFallbackToCache(e.kind)) {
                    diskCache?.getMailboxes(key)?.let { return@runMailCatching it.mailboxes }
                }
                throw e
            }
        }

    override suspend fun getMailbox(id: Long): Result<SeekMailbox> =
        runMailCatching {
            val key = requireApiKey()
            apiClient.getMailbox(apiKey = key, id = id)
        }

    override suspend fun checkMailboxAvailable(address: String): Result<Boolean> =
        runMailCatching {
            val key = requireApiKey()
            apiClient.checkMailboxAvailable(apiKey = key, address = address)
        }

    override suspend fun createMailbox(address: String): Result<SeekMailbox> =
        runMailCatching {
            val key = requireApiKey()
            val created = apiClient.createMailbox(apiKey = key, address = address)
            diskCache?.invalidateMailboxes(key)
            created
        }

    override suspend fun getMails(
        mailboxId: Long?,
        search: String?,
        tagId: Long?,
        limit: Int,
        offset: Int,
        forceRefresh: Boolean,
    ): Result<SeekMailPage> =
        runMailCatching {
            val key = requireApiKey()
            val cleanSearch = search?.trim()?.takeIf { it.isNotEmpty() }
            val cleanMailboxId = mailboxId?.takeIf { it > 0L }
            val cleanTagId = tagId?.takeIf { it > 0L }
            val isDefaultFirstPage = cleanSearch == null && cleanTagId == null && offset == 0

            val page = try {
                val fresh = apiClient.getMails(
                    apiKey = key,
                    mailboxId = cleanMailboxId,
                    search = cleanSearch,
                    tagId = cleanTagId,
                    limit = limit,
                    offset = offset,
                )
                if (isDefaultFirstPage) {
                    diskCache?.saveDefaultMailsPage(
                        apiKey = key,
                        mailboxId = cleanMailboxId,
                        page = fresh,
                    )
                }
                fresh
            } catch (c: CancellationException) {
                throw c
            } catch (e: SeekMailException) {
                if (!forceRefresh && isDefaultFirstPage && shouldFallbackToCache(e.kind)) {
                    val cached = diskCache?.getDefaultMailsPage(key, cleanMailboxId)
                    if (cached != null && cached.page.limit == limit) {
                        cached.page
                    } else {
                        throw e
                    }
                } else {
                    throw e
                }
            }

            val readIds = _locallyReadMailIds.value
            if (readIds.isEmpty()) {
                page
            } else {
                page.copy(items = page.items.map { overlayReadState(it, readIds) })
            }
        }

    override suspend fun getUnreadMails(
        mailboxId: Long?,
        limit: Int,
        cursor: String?,
    ): Result<SeekMailUnreadPage> =
        runMailCatching {
            val key = requireApiKey()
            val page = apiClient.getUnreadMails(
                apiKey = key,
                mailboxId = mailboxId?.takeIf { it > 0L },
                limit = limit,
                cursor = cursor,
            )
            val readIds = _locallyReadMailIds.value
            if (readIds.isEmpty()) {
                page
            } else {
                page.copy(items = page.items.map { overlayReadState(it, readIds) })
            }
        }

    override suspend fun getMailDetail(
        mailId: Long,
        forceRefresh: Boolean,
    ): Result<SeekMailDetail> =
        runMailCatching {
            val key = requireApiKey()
            if (!forceRefresh) {
                val cached = diskCache?.getMailDetail(key, mailId)
                if (cached != null) {
                    markMailReadLocallyForKey(key, mailId)
                    return@runMailCatching cached.detail.copy(
                        summary = cached.detail.summary.copy(isRead = true),
                    )
                }
            }

            val detail = try {
                val fresh = apiClient.getMailDetail(apiKey = key, mailId = mailId)
                diskCache?.saveMailDetail(key, mailId, fresh)
                fresh
            } catch (c: CancellationException) {
                throw c
            } catch (e: SeekMailException) {
                if (!forceRefresh && shouldFallbackToCache(e.kind)) {
                    diskCache?.getMailDetail(key, mailId)?.detail ?: throw e
                } else {
                    throw e
                }
            }

            markMailReadLocallyForKey(key, mailId)
            detail.copy(summary = detail.summary.copy(isRead = true))
        }

    override suspend fun getRawMailEml(mailId: Long): Result<String> =
        runMailCatching {
            val key = requireApiKey()
            apiClient.getRawMailEml(apiKey = key, mailId = mailId)
        }

    override suspend fun getAttachments(mailId: Long): Result<List<SeekMailAttachment>> =
        runMailCatching {
            val key = requireApiKey()
            apiClient.getAttachments(apiKey = key, mailId = mailId)
        }

    override suspend fun downloadAttachmentBytes(
        mailId: Long,
        attachmentId: String,
    ): Result<ByteArray> =
        runMailCatching {
            val key = requireApiKey()
            apiClient.downloadAttachmentBytes(
                apiKey = key,
                mailId = mailId,
                attachmentId = attachmentId,
            )
        }

    override suspend fun getTags(forceRefresh: Boolean): Result<List<SeekMailTag>> =
        runMailCatching {
            val key = requireApiKey()
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                val cached = diskCache?.getTags(key)
                if (cached != null && isFresh(cached.fetchedAtEpochMillis, now, TAGS_CACHE_TTL_MS)) {
                    return@runMailCatching cached.tags
                }
            }
            try {
                val fresh = apiClient.getTags(key)
                diskCache?.saveTags(key, fresh, now)
                fresh
            } catch (c: CancellationException) {
                throw c
            } catch (e: SeekMailException) {
                if (!forceRefresh && shouldFallbackToCache(e.kind)) {
                    diskCache?.getTags(key)?.let { return@runMailCatching it.tags }
                }
                throw e
            }
        }

    override suspend fun getContacts(
        limit: Int,
        forceRefresh: Boolean,
    ): Result<List<SeekMailContact>> =
        runMailCatching {
            val key = requireApiKey()
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                val cached = diskCache?.getContacts(key)
                if (cached != null &&
                    cached.limit == limit &&
                    isFresh(cached.fetchedAtEpochMillis, now, METADATA_CACHE_TTL_MS)
                ) {
                    return@runMailCatching cached.contacts
                }
            }
            try {
                val fresh = apiClient.getContacts(apiKey = key, limit = limit)
                diskCache?.saveContacts(key, limit, fresh, now)
                fresh
            } catch (c: CancellationException) {
                throw c
            } catch (e: SeekMailException) {
                if (!forceRefresh && shouldFallbackToCache(e.kind)) {
                    val cached = diskCache?.getContacts(key)
                    if (cached != null && cached.limit == limit) {
                        return@runMailCatching cached.contacts
                    }
                }
                throw e
            }
        }

    override suspend fun markMailReadLocally(mailId: Long) {
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        markMailReadLocallyForKey(key, mailId)
    }

    override suspend fun clearCache() {
        stateMutex.withLock {
            _locallyReadMailIds.value = emptySet()
            loadedReadIdsForKey = null
            diskCache?.clearAll()
        }
    }

    private suspend fun markMailReadLocallyForKey(key: String, mailId: Long) {
        if (mailId <= 0L) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _locallyReadMailIds.value
            if (mailId in current) return
            val updated = current + mailId
            _locallyReadMailIds.value = updated
            diskCache?.saveLocallyReadIds(key, updated)
        }
    }

    private suspend fun requireApiKey(): String {
        val key = reconcileActiveKey()
        if (key.isEmpty()) {
            throw SeekMailException(
                kind = SeekMailErrorKind.MISSING_API_KEY,
                message = "请先配置 Seek 邮箱 API Key",
            )
        }
        return key
    }

    private suspend fun reconcileActiveKey(): String {
        val latestKey = settingsRepository.settings.value.seekMailApiKey.trim()
        reconcileKeyChange(latestKey)
        return latestKey
    }

    private suspend fun reconcileKeyChange(newKey: String) {
        stateMutex.withLock {
            val previous = activeKey
            if (newKey != previous) {
                if (previous.isNotEmpty() && newKey.isEmpty()) {
                    diskCache?.clearForKey(previous)
                }
                activeKey = newKey
                loadedReadIdsForKey = null
            }

            if (newKey.isEmpty()) {
                if (_locallyReadMailIds.value.isNotEmpty()) {
                    _locallyReadMailIds.value = emptySet()
                }
                loadedReadIdsForKey = ""
            } else if (loadedReadIdsForKey != newKey) {
                _locallyReadMailIds.value = diskCache?.getLocallyReadIds(newKey).orEmpty()
                loadedReadIdsForKey = newKey
            }
        }
    }

    private fun overlayReadState(summary: SeekMailSummary, readIds: Set<Long>): SeekMailSummary =
        if (summary.isRead || summary.id !in readIds) summary else summary.copy(isRead = true)

    private fun shouldFallbackToCache(kind: SeekMailErrorKind): Boolean =
        kind == SeekMailErrorKind.NETWORK ||
            kind == SeekMailErrorKind.SERVER ||
            kind == SeekMailErrorKind.RATE_LIMITED

    private fun isFresh(fetchedAtMillis: Long, nowMillis: Long, ttlMillis: Long): Boolean {
        val age = nowMillis - fetchedAtMillis
        return age in 0L until ttlMillis
    }

    private suspend inline fun <T> runMailCatching(
        crossinline block: suspend () -> T,
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (s: SeekMailException) {
            Result.failure(s)
        } catch (io: IOException) {
            Result.failure(
                SeekMailException(
                    kind = SeekMailErrorKind.NETWORK,
                    message = "网络连接失败，请检查网络后重试",
                    cause = io,
                ),
            )
        } catch (t: Throwable) {
            Result.failure(
                SeekMailException(
                    kind = SeekMailErrorKind.UNKNOWN,
                    message = t.message?.takeIf { it.isNotBlank() } ?: "Seek 邮箱请求发生未知错误",
                    cause = t,
                ),
            )
        }
    }

    companion object {
        private const val METADATA_CACHE_TTL_MS = 60_000L
        private const val TAGS_CACHE_TTL_MS = 300_000L
    }
}
