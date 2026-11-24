package com.nodeseek.app.data.mail

import com.nodeseek.app.domain.backup.SeekMailBackupDto
import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailConnectionStatus
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailException
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
import com.nodeseek.app.domain.mail.VpsBillingMailParser
import com.nodeseek.app.domain.mail.VpsMailAutoIngestService
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
import java.io.OutputStream
import java.util.UUID

/**
 * Concrete [SeekMailRepository] orchestrating [SeekMailApiClient], key-isolated [SeekMailDiskCache],
 * local read-state overlay, and reactive API key switching via [SettingsRepository].
 */
class SeekMailRepositoryImpl(
    private val apiClient: SeekMailApiClient,
    private val settingsRepository: SettingsRepository,
    private val diskCache: SeekMailDiskCache? = null,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val vpsMailAutoIngestService: VpsMailAutoIngestService? = null,
) : SeekMailRepository {

    private val stateMutex = Mutex()
    private var activeKey: String = settingsRepository.settings.value.seekMailApiKey.trim()
    private var loadedReadIdsForKey: String? = null

    private val _locallyReadMailIds = MutableStateFlow<Set<Long>>(emptySet())
    override val locallyReadMailIds: StateFlow<Set<Long>> = _locallyReadMailIds.asStateFlow()

    private val _localTagStore = MutableStateFlow(SeekMailLocalTagStore())
    override val localTagStore: StateFlow<SeekMailLocalTagStore> = _localTagStore.asStateFlow()

    private val _localArchiveStore = MutableStateFlow(SeekMailLocalArchiveStore())
    override val localArchiveStore: StateFlow<SeekMailLocalArchiveStore> = _localArchiveStore.asStateFlow()

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
            val overlaid = if (readIds.isEmpty()) {
                page
            } else {
                page.copy(items = page.items.map { overlayReadState(it, readIds) })
            }
            notifyAutoIngestSummaries(overlaid.items)
            overlaid
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
            val overlaid = if (readIds.isEmpty()) {
                page
            } else {
                page.copy(items = page.items.map { overlayReadState(it, readIds) })
            }
            notifyAutoIngestSummaries(overlaid.items)
            overlaid
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
                    val resolvedCached = cached.detail.copy(
                        summary = cached.detail.summary.copy(isRead = true),
                    )
                    notifyAutoIngestDetail(resolvedCached)
                    return@runMailCatching resolvedCached
                }
            }

            val detail = try {
                val fresh = apiClient.getMailDetail(apiKey = key, mailId = mailId)
                val containsSensitiveSshPassword = !VpsBillingMailParser.parseDetail(fresh)?.sshPassword.isNullOrBlank()
                diskCache?.saveMailDetail(
                    apiKey = key,
                    mailId = mailId,
                    detail = fresh,
                    persistToDisk = !containsSensitiveSshPassword,
                )
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
            val resolvedDetail = detail.copy(summary = detail.summary.copy(isRead = true))
            notifyAutoIngestDetail(resolvedDetail)
            resolvedDetail
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

    override suspend fun downloadAttachmentToStream(
        mailId: Long,
        attachmentId: String,
        outputStream: OutputStream,
    ): Result<Long> =
        runMailCatching {
            val key = requireApiKey()
            apiClient.downloadAttachmentToStream(
                apiKey = key,
                mailId = mailId,
                attachmentId = attachmentId,
                outputStream = outputStream,
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

    override suspend fun markMailsReadLocally(mailIds: Collection<Long>) {
        val validIds = mailIds.filter { it > 0L }
        if (validIds.isEmpty()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _locallyReadMailIds.value
            val updated = current + validIds
            if (updated == current) return
            if (diskCache == null || diskCache.saveLocallyReadIds(key, updated)) {
                _locallyReadMailIds.value = updated
            }
        }
    }

    override suspend fun createLocalTag(
        name: String,
        colorHex: String,
    ): Result<SeekMailLocalTag> = runMailCatching {
        val trimmedName = name.trim().take(MAX_LOCAL_TAG_NAME_LENGTH)
        if (trimmedName.isEmpty()) {
            throw SeekMailException(
                kind = SeekMailErrorKind.UNKNOWN,
                message = "标签名称不能为空",
            )
        }
        val key = requireApiKey()
        val safeHex = normalizeLocalTagHex(colorHex)
        stateMutex.withLock {
            if (activeKey != key) {
                throw SeekMailException(
                    kind = SeekMailErrorKind.MISSING_API_KEY,
                    message = "请先配置 Seek 邮箱 API Key",
                )
            }
            val current = _localTagStore.value
            val existing = current.tags.firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }
            if (existing != null) {
                return@withLock existing
            }
            val created = SeekMailLocalTag(
                id = "ltag_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                name = trimmedName,
                colorHex = safeHex,
            )
            val updated = current.copy(tags = current.tags + created)
            if (diskCache != null && !diskCache.saveLocalTagStore(key, updated)) {
                throw SeekMailException(
                    kind = SeekMailErrorKind.UNKNOWN,
                    message = "本地标签保存失败，请检查存储空间",
                )
            }
            _localTagStore.value = updated
            created
        }
    }

    override suspend fun deleteLocalTag(tagId: String) {
        val cleanId = tagId.trim()
        if (cleanId.isEmpty()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localTagStore.value
            val nextTags = current.tags.filterNot { it.id == cleanId }
            val nextMailTags = current.mailTagIds.mapNotNull { (mailId, ids) ->
                val filtered = ids.filterNot { it == cleanId }
                if (filtered.isEmpty()) null else mailId to filtered
            }.toMap()
            val nextMailboxIds = current.mailMailboxIds.filterKeys { it in nextMailTags }
            val updated = SeekMailLocalTagStore(
                tags = nextTags,
                mailTagIds = nextMailTags,
                mailMailboxIds = nextMailboxIds,
            )
            if (updated == current) return
            if (diskCache == null || diskCache.saveLocalTagStore(key, updated)) {
                _localTagStore.value = updated
            }
        }
    }

    override suspend fun toggleLocalTagForMail(mailId: Long, tagId: String, mailboxId: Long?) {
        if (mailId <= 0L || tagId.isBlank()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localTagStore.value
            if (current.tags.none { it.id == tagId }) return
            val existingIds = current.mailTagIds[mailId].orEmpty()
            val nextIds = if (tagId in existingIds) {
                existingIds.filterNot { it == tagId }
            } else {
                existingIds + tagId
            }
            val nextMap = current.mailTagIds.toMutableMap()
            val nextMailboxMap = current.mailMailboxIds.toMutableMap()
            if (nextIds.isEmpty()) {
                nextMap.remove(mailId)
                nextMailboxMap.remove(mailId)
            } else {
                nextMap[mailId] = nextIds
                if (mailboxId != null && mailboxId > 0L) {
                    nextMailboxMap[mailId] = mailboxId
                }
            }
            val updated = current.copy(
                mailTagIds = nextMap,
                mailMailboxIds = nextMailboxMap,
            )
            if (diskCache == null || diskCache.saveLocalTagStore(key, updated)) {
                _localTagStore.value = updated
            }
        }
    }

    override suspend fun setLocalTagsForMail(mailId: Long, tagIds: List<String>, mailboxId: Long?) {
        if (mailId <= 0L) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localTagStore.value
            val knownIds = current.tags.mapTo(HashSet()) { it.id }
            val sanitized = tagIds.filter { it in knownIds }.distinct()
            val nextMap = current.mailTagIds.toMutableMap()
            val nextMailboxMap = current.mailMailboxIds.toMutableMap()
            if (sanitized.isEmpty()) {
                nextMap.remove(mailId)
                nextMailboxMap.remove(mailId)
            } else {
                nextMap[mailId] = sanitized
                if (mailboxId != null && mailboxId > 0L) {
                    nextMailboxMap[mailId] = mailboxId
                }
            }
            val updated = current.copy(
                mailTagIds = nextMap,
                mailMailboxIds = nextMailboxMap,
            )
            if (updated == current) return
            if (diskCache == null || diskCache.saveLocalTagStore(key, updated)) {
                _localTagStore.value = updated
            }
        }
    }

    override suspend fun addLocalTagToMails(
        mailIds: Collection<Long>,
        tagId: String,
        mailboxIdsByMailId: Map<Long, Long>,
    ) {
        val validIds = mailIds.filter { it > 0L }
        if (validIds.isEmpty() || tagId.isBlank()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localTagStore.value
            if (current.tags.none { it.id == tagId }) return
            val nextMap = current.mailTagIds.toMutableMap()
            val nextMailboxMap = current.mailMailboxIds.toMutableMap()
            var changed = false
            validIds.forEach { mailId ->
                val existing = nextMap[mailId].orEmpty()
                if (tagId !in existing) {
                    nextMap[mailId] = existing + tagId
                    changed = true
                }
                val mbId = mailboxIdsByMailId[mailId]
                if (mbId != null && mbId > 0L && nextMailboxMap[mailId] != mbId) {
                    nextMailboxMap[mailId] = mbId
                    changed = true
                }
            }
            if (!changed) return
            val updated = current.copy(
                mailTagIds = nextMap,
                mailMailboxIds = nextMailboxMap,
            )
            if (diskCache == null || diskCache.saveLocalTagStore(key, updated)) {
                _localTagStore.value = updated
            }
        }
    }

    override suspend fun removeLocalTagFromMails(mailIds: Collection<Long>, tagId: String) {
        val validIds = mailIds.filter { it > 0L }
        if (validIds.isEmpty() || tagId.isBlank()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localTagStore.value
            val nextMap = current.mailTagIds.toMutableMap()
            val nextMailboxMap = current.mailMailboxIds.toMutableMap()
            var changed = false
            validIds.forEach { mailId ->
                val existing = nextMap[mailId].orEmpty()
                if (tagId in existing) {
                    val remaining = existing.filterNot { it == tagId }
                    if (remaining.isEmpty()) {
                        nextMap.remove(mailId)
                        nextMailboxMap.remove(mailId)
                    } else {
                        nextMap[mailId] = remaining
                    }
                    changed = true
                }
            }
            if (!changed) return
            val updated = current.copy(
                mailTagIds = nextMap,
                mailMailboxIds = nextMailboxMap,
            )
            if (diskCache == null || diskCache.saveLocalTagStore(key, updated)) {
                _localTagStore.value = updated
            }
        }
    }

    override suspend fun clearLocalTagsForMails(mailIds: Collection<Long>) {
        val validIds = mailIds.filter { it > 0L }
        if (validIds.isEmpty()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localTagStore.value
            val nextMap = current.mailTagIds.toMutableMap()
            val nextMailboxMap = current.mailMailboxIds.toMutableMap()
            var changed = false
            validIds.forEach { mailId ->
                if (nextMap.remove(mailId) != null) {
                    nextMailboxMap.remove(mailId)
                    changed = true
                }
            }
            if (!changed) return
            val updated = current.copy(
                mailTagIds = nextMap,
                mailMailboxIds = nextMailboxMap,
            )
            if (diskCache == null || diskCache.saveLocalTagStore(key, updated)) {
                _localTagStore.value = updated
            }
        }
    }

    override suspend fun archiveMailLocally(summary: SeekMailSummary) {
        archiveMailsLocally(listOf(summary))
    }

    override suspend fun archiveMailsLocally(summaries: Collection<SeekMailSummary>) {
        val validSummaries = summaries.filter { it.id > 0L }
        if (validSummaries.isEmpty()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localArchiveStore.value
            val readIds = _locallyReadMailIds.value
            val now = System.currentTimeMillis()
            val nextArchived = current.archivedMails.toMutableMap()
            val nextArchivedAt = current.archivedAtMillis.toMutableMap()
            val nextDeleted = current.deletedMailIds.toMutableSet()
            validSummaries.forEach { summary ->
                nextArchived[summary.id] = overlayReadState(summary, readIds)
                nextArchivedAt[summary.id] = now
                nextDeleted.remove(summary.id)
            }
            val updated = current.copy(
                archivedMails = nextArchived,
                archivedAtMillis = nextArchivedAt,
                deletedMailIds = nextDeleted,
            )
            if (diskCache == null || diskCache.saveLocalArchiveStore(key, updated)) {
                _localArchiveStore.value = updated
            }
        }
    }

    override suspend fun unarchiveMailLocally(mailId: Long) {
        unarchiveMailsLocally(listOf(mailId))
    }

    override suspend fun unarchiveMailsLocally(mailIds: Collection<Long>) {
        val validIds = mailIds.filter { it > 0L }
        if (validIds.isEmpty()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localArchiveStore.value
            val nextArchived = current.archivedMails - validIds.toSet()
            val nextArchivedAt = current.archivedAtMillis - validIds.toSet()
            if (nextArchived == current.archivedMails && nextArchivedAt == current.archivedAtMillis) return
            val updated = current.copy(
                archivedMails = nextArchived,
                archivedAtMillis = nextArchivedAt,
            )
            if (diskCache == null || diskCache.saveLocalArchiveStore(key, updated)) {
                _localArchiveStore.value = updated
            }
        }
    }

    override suspend fun deleteMailLocally(mailId: Long) {
        deleteMailsLocally(listOf(mailId))
    }

    override suspend fun deleteMailsLocally(mailIds: Collection<Long>) {
        val validIds = mailIds.filter { it > 0L }.toSet()
        if (validIds.isEmpty()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localArchiveStore.value
            val updated = current.copy(
                archivedMails = current.archivedMails - validIds,
                archivedAtMillis = current.archivedAtMillis - validIds,
                deletedMailIds = current.deletedMailIds + validIds,
            )
            if (updated == current) return
            if (diskCache == null || diskCache.saveLocalArchiveStore(key, updated)) {
                _localArchiveStore.value = updated
                diskCache?.deleteMailDetails(key, validIds)
            }
        }
    }

    override suspend fun restoreDeletedMailLocally(
        mailId: Long,
        restoredArchiveSummary: SeekMailSummary?,
    ) {
        restoreDeletedMailsLocally(
            mailIds = listOf(mailId),
            restoredArchiveSummaries = listOfNotNull(restoredArchiveSummary),
        )
    }

    override suspend fun restoreDeletedMailsLocally(
        mailIds: Collection<Long>,
        restoredArchiveSummaries: Collection<SeekMailSummary>,
    ) {
        val validIds = mailIds.filter { it > 0L }.toSet()
        if (validIds.isEmpty()) return
        val key = reconcileActiveKey()
        if (key.isEmpty()) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _localArchiveStore.value
            val readIds = _locallyReadMailIds.value
            val now = System.currentTimeMillis()
            val nextArchived = current.archivedMails.toMutableMap()
            val nextArchivedAt = current.archivedAtMillis.toMutableMap()
            restoredArchiveSummaries.forEach { summary ->
                if (summary.id in validIds) {
                    nextArchived[summary.id] = overlayReadState(summary, readIds)
                    nextArchivedAt[summary.id] = now
                }
            }
            val updated = current.copy(
                archivedMails = nextArchived,
                archivedAtMillis = nextArchivedAt,
                deletedMailIds = current.deletedMailIds - validIds,
            )
            if (updated == current) return
            if (diskCache == null || diskCache.saveLocalArchiveStore(key, updated)) {
                _localArchiveStore.value = updated
            }
        }
    }

    override suspend fun clearCacheForCurrentKey() {
        stateMutex.withLock {
            val keyToClear = activeKey.ifEmpty { settingsRepository.settings.value.seekMailApiKey.trim() }
            if (keyToClear.isNotEmpty()) {
                diskCache?.clearForKey(keyToClear)
            }
            _locallyReadMailIds.value = emptySet()
            _localTagStore.value = SeekMailLocalTagStore()
            _localArchiveStore.value = SeekMailLocalArchiveStore()
            loadedReadIdsForKey = null
        }
    }

    override suspend fun clearCache() {
        stateMutex.withLock {
            _locallyReadMailIds.value = emptySet()
            _localTagStore.value = SeekMailLocalTagStore()
            _localArchiveStore.value = SeekMailLocalArchiveStore()
            loadedReadIdsForKey = null
            diskCache?.clearAll()
        }
    }

    override suspend fun exportForBackup(): SeekMailBackupDto {
        val key = reconcileActiveKey()
        return stateMutex.withLock {
            if (key.isEmpty()) {
                SeekMailBackupDto()
            } else {
                SeekMailBackupDto(
                    localTagStore = _localTagStore.value,
                    localArchiveStore = _localArchiveStore.value,
                    locallyReadMailIds = _locallyReadMailIds.value,
                )
            }
        }
    }

    override suspend fun restoreFromBackup(
        backup: SeekMailBackupDto,
        replaceExisting: Boolean,
    ) {
        val key = reconcileActiveKey()
        stateMutex.withLock {
            if (key.isEmpty()) {
                if (replaceExisting) {
                    _locallyReadMailIds.value = emptySet()
                    _localTagStore.value = SeekMailLocalTagStore()
                    _localArchiveStore.value = SeekMailLocalArchiveStore()
                }
                return
            }

            val incomingTags = backup.localTagStore.tags
                .mapNotNull { tag ->
                    val cleanId = tag.id.trim()
                    val cleanName = tag.name.trim().take(MAX_LOCAL_TAG_NAME_LENGTH)
                    if (cleanId.isEmpty() || cleanName.isEmpty()) {
                        null
                    } else {
                        SeekMailLocalTag(
                            id = cleanId,
                            name = cleanName,
                            colorHex = normalizeLocalTagHex(tag.colorHex),
                        )
                    }
                }
                .distinctBy { it.id }

            val mergedTagStore = if (replaceExisting) {
                val validTagIds = incomingTags.map { it.id }.toSet()
                val cleanMailTagIds = backup.localTagStore.mailTagIds
                    .filterKeys { it > 0L }
                    .mapValues { (_, ids) -> ids.filter { it in validTagIds }.distinct() }
                    .filterValues { it.isNotEmpty() }
                val cleanMailboxIds = backup.localTagStore.mailMailboxIds
                    .filterKeys { it in cleanMailTagIds }
                SeekMailLocalTagStore(
                    tags = incomingTags,
                    mailTagIds = cleanMailTagIds,
                    mailMailboxIds = cleanMailboxIds,
                )
            } else {
                val current = _localTagStore.value
                val tagsById = LinkedHashMap<String, SeekMailLocalTag>()
                current.tags.forEach { tagsById[it.id] = it }
                incomingTags.forEach { tagsById[it.id] = it }
                val validTagIds = tagsById.keys
                val nextMailTagIds = LinkedHashMap<Long, List<String>>()
                current.mailTagIds.forEach { (mailId, ids) ->
                    if (mailId > 0L) {
                        nextMailTagIds[mailId] = ids.filter { it in validTagIds }.distinct()
                    }
                }
                backup.localTagStore.mailTagIds.forEach { (mailId, ids) ->
                    if (mailId > 0L) {
                        val combined = (nextMailTagIds[mailId].orEmpty() + ids)
                            .filter { it in validTagIds }
                            .distinct()
                        if (combined.isNotEmpty()) {
                            nextMailTagIds[mailId] = combined
                        }
                    }
                }
                val nextMailboxIds = LinkedHashMap<Long, Long>(current.mailMailboxIds)
                backup.localTagStore.mailMailboxIds.forEach { (mailId, mailboxId) ->
                    if (mailId > 0L && mailboxId > 0L) {
                        nextMailboxIds[mailId] = mailboxId
                    }
                }
                SeekMailLocalTagStore(
                    tags = tagsById.values.toList(),
                    mailTagIds = nextMailTagIds.filterValues { it.isNotEmpty() },
                    mailMailboxIds = nextMailboxIds.filterKeys { it in nextMailTagIds },
                )
            }

            val incomingArchive = backup.localArchiveStore
            val mergedArchiveStore = if (replaceExisting) {
                val cleanDeleted = incomingArchive.deletedMailIds.filter { it > 0L }.toSet()
                val cleanArchived = incomingArchive.archivedMails
                    .filterKeys { it > 0L && it !in cleanDeleted }
                val cleanArchivedAt = incomingArchive.archivedAtMillis
                    .filterKeys { it in cleanArchived }
                SeekMailLocalArchiveStore(
                    archivedMails = cleanArchived,
                    archivedAtMillis = cleanArchivedAt,
                    deletedMailIds = cleanDeleted,
                )
            } else {
                val current = _localArchiveStore.value
                val nextArchived = LinkedHashMap(current.archivedMails)
                incomingArchive.archivedMails.forEach { (id, summary) ->
                    if (id > 0L) nextArchived[id] = summary
                }
                val nextArchivedAt = LinkedHashMap(current.archivedAtMillis)
                incomingArchive.archivedAtMillis.forEach { (id, ts) ->
                    if (id > 0L) nextArchivedAt[id] = ts
                }
                val nextDeleted = (current.deletedMailIds + incomingArchive.deletedMailIds)
                    .filter { it > 0L && it !in nextArchived }
                    .toSet()
                SeekMailLocalArchiveStore(
                    archivedMails = nextArchived,
                    archivedAtMillis = nextArchivedAt.filterKeys { it in nextArchived },
                    deletedMailIds = nextDeleted,
                )
            }

            val cleanIncomingReadIds = backup.locallyReadMailIds.filter { it > 0L }.toSet()
            val mergedReadIds = if (replaceExisting) {
                cleanIncomingReadIds
            } else {
                _locallyReadMailIds.value + cleanIncomingReadIds
            }

            if (diskCache == null ||
                (
                    diskCache.saveLocalTagStore(key, mergedTagStore) &&
                        diskCache.saveLocalArchiveStore(key, mergedArchiveStore) &&
                        diskCache.saveLocallyReadIds(key, mergedReadIds)
                    )
            ) {
                _localTagStore.value = mergedTagStore
                _localArchiveStore.value = mergedArchiveStore
                _locallyReadMailIds.value = mergedReadIds
                loadedReadIdsForKey = key
            } else {
                throw IOException("恢复 Seek 邮箱本地标签与归档状态失败")
            }
        }
    }

    private suspend fun markMailReadLocallyForKey(key: String, mailId: Long) {
        if (mailId <= 0L) return
        stateMutex.withLock {
            if (activeKey != key) return
            val current = _locallyReadMailIds.value
            if (mailId in current) return
            val updated = current + mailId
            if (diskCache == null || diskCache.saveLocallyReadIds(key, updated)) {
                _locallyReadMailIds.value = updated
            }
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
                if (_localTagStore.value.tags.isNotEmpty() || _localTagStore.value.mailTagIds.isNotEmpty()) {
                    _localTagStore.value = SeekMailLocalTagStore()
                }
                if (
                    _localArchiveStore.value.archivedMails.isNotEmpty() ||
                    _localArchiveStore.value.deletedMailIds.isNotEmpty()
                ) {
                    _localArchiveStore.value = SeekMailLocalArchiveStore()
                }
                loadedReadIdsForKey = ""
            } else if (loadedReadIdsForKey != newKey) {
                _locallyReadMailIds.value = diskCache?.getLocallyReadIds(newKey).orEmpty()
                _localTagStore.value = diskCache?.getLocalTagStore(newKey) ?: SeekMailLocalTagStore()
                _localArchiveStore.value = diskCache?.getLocalArchiveStore(newKey) ?: SeekMailLocalArchiveStore()
                loadedReadIdsForKey = newKey
            }
        }
    }

    private fun overlayReadState(summary: SeekMailSummary, readIds: Set<Long>): SeekMailSummary =
        if (summary.isRead || summary.id !in readIds) summary else summary.copy(isRead = true)

    private fun notifyAutoIngestSummaries(items: List<SeekMailSummary>) {
        val service = vpsMailAutoIngestService ?: return
        val deletedIds = _localArchiveStore.value.deletedMailIds
        val activeItems = if (deletedIds.isEmpty()) {
            items
        } else {
            items.filterNot { it.id in deletedIds }
        }
        if (activeItems.isEmpty()) return
        // Launch asynchronously so inbox list loading never blocks on background detail peeks.
        externalScope.launch {
            try {
                service.processIncomingSummaries(activeItems)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // Auto-ingest is best-effort and must never fail inbox loading.
            }
        }
    }

    private suspend fun notifyAutoIngestDetail(detail: SeekMailDetail) {
        val service = vpsMailAutoIngestService ?: return
        try {
            service.processMailDetail(detail)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Auto-ingest is best-effort and must never fail detail loading.
        }
    }

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
        private const val MAX_LOCAL_TAG_NAME_LENGTH = 20
        private const val DEFAULT_LOCAL_TAG_HEX = "#2D637E"
        private val HEX_COLOR_REGEX = Regex("""^#[0-9A-Fa-f]{6}$""")

        private fun normalizeLocalTagHex(raw: String): String {
            val trimmed = raw.trim()
            val candidate = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
            return if (HEX_COLOR_REGEX.matches(candidate)) {
                candidate.uppercase()
            } else {
                DEFAULT_LOCAL_TAG_HEX
            }
        }
    }
}
