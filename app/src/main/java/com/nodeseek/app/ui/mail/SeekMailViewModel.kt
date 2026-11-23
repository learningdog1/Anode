package com.nodeseek.app.ui.mail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.mail.NoOpVpsMailAutoIngestService
import com.nodeseek.app.domain.mail.ParsedVpsBillingMail
import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailConnectionStatus
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailException
import com.nodeseek.app.domain.mail.SeekMailLocalTag
import com.nodeseek.app.domain.mail.SeekMailPage
import com.nodeseek.app.domain.mail.SeekMailRateLimit
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailUnreadPage
import com.nodeseek.app.domain.mail.SeekMailbox
import com.nodeseek.app.domain.mail.VpsBillingMailParser
import com.nodeseek.app.domain.mail.VpsMailAutoIngestService
import com.nodeseek.app.domain.mail.VpsMailMatchStatus
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

enum class SeekMailFilterMode(val label: String) {
    ALL("全部邮件"),
    UNREAD("未读邮件"),
    ARCHIVED("已存档"),
}

enum class SeekMailReaderMode(val label: String) {
    HTML("HTML 视图"),
    TEXT("纯文本"),
}

@Immutable
data class SeekMailSnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

@Immutable
sealed interface PendingExportTarget {
    val suggestedFileName: String
    val exportKey: String

    data class Attachment(
        val mailId: Long,
        val attachment: SeekMailAttachment,
    ) : PendingExportTarget {
        override val suggestedFileName: String
            get() = sanitizeExportFileName(
                attachment.fileName.ifBlank { "attachment_${attachment.id}" },
            )

        override val exportKey: String
            get() = "att:${mailId}:${attachment.id}"
    }
}

@Immutable
data class VpsMailInsightUiState(
    val parsed: ParsedVpsBillingMail,
    val matchStatus: VpsMailMatchStatus = VpsMailMatchStatus(),
    val isPasswordRevealed: Boolean = false,
    val isIngesting: Boolean = false,
) {
    val actionButtonLabel: String
        get() = when {
            parsed.hasSshCredentials && matchStatus.isAssetMatched && matchStatus.isSshMatched ->
                "更新账本 + SSH"
            parsed.hasSshCredentials ->
                "一键入库账本 + SSH"
            matchStatus.isAssetMatched ->
                "更新服务器账本"
            else ->
                "入库服务器账本"
        }

    val matchedAssetStatusText: String
        get() {
            val matchedAsset = matchStatus.primaryMatchedAsset
            val matchedSsh = matchStatus.matchedSshProfile
            return when {
                matchedAsset != null && matchedSsh != null ->
                    "已匹配账本「${matchedAsset.nickname}」· SSH 已同步"
                matchedAsset != null -> {
                    val exp = matchedAsset.expiryDateIso.ifBlank { "待补" }
                    "已匹配账本「${matchedAsset.nickname}」（到期 $exp）"
                }
                matchedSsh != null ->
                    "SSH 已存在「${matchedSsh.displayName}」· 账本未录入"
                else ->
                    "尚未录入服务器账本"
            }
        }
}

@Immutable
data class SeekMailUiState(
    val hasConfiguredKey: Boolean = false,
    val apiKeyMaskedHint: String = "",
    val isVerifyingApiKey: Boolean = false,
    val apiKeyErrorMessage: String? = null,
    val mailboxes: List<SeekMailbox> = emptyList(),
    val isLoadingMailboxes: Boolean = false,
    val mailboxErrorMessage: String? = null,
    val selectedMailboxId: Long? = null,
    val showCreateMailboxSheet: Boolean = false,
    val availableDomains: List<String> = DEFAULT_DOMAINS,
    val createLocalPart: String = "",
    val createDomain: String = DEFAULT_DOMAINS.first(),
    val useCustomDomain: Boolean = false,
    val customDomain: String = "",
    val isCheckingAvailability: Boolean = false,
    val availabilityResult: Boolean? = null,
    val availabilityCheckedAddress: String? = null,
    val isCreatingMailbox: Boolean = false,
    val createMailboxError: String? = null,
    val filterMode: SeekMailFilterMode = SeekMailFilterMode.ALL,
    val tags: List<SeekMailTag> = emptyList(),
    val selectedTagId: Long? = null,
    val localTags: List<SeekMailLocalTag> = emptyList(),
    val localTagsByMailId: Map<Long, List<SeekMailLocalTag>> = emptyMap(),
    val localTagCounts: Map<String, Int> = emptyMap(),
    val selectedLocalTagId: String? = null,
    val taggingTargetMail: SeekMailSummary? = null,
    val archivedMailIds: Set<Long> = emptySet(),
    val archivedMailCount: Int = 0,
    val actionTargetMail: SeekMailSummary? = null,
    val pendingDeleteMail: SeekMailSummary? = null,
    val isSelectionMode: Boolean = false,
    val selectedMailIds: Set<Long> = emptySet(),
    val pendingBatchDeleteMails: List<SeekMailSummary> = emptyList(),
    val batchTaggingMails: List<SeekMailSummary> = emptyList(),
    val activeInboxUnreadCount: Int? = null,
    val isSearchExpanded: Boolean = false,
    val searchDraft: String = "",
    val activeSearchQuery: String = "",
    val mails: List<SeekMailSummary> = emptyList(),
    val totalMailCount: Int = 0,
    val hasMoreMails: Boolean = false,
    val isLoadingMails: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val mailsErrorMessage: String? = null,
    val mailsErrorKind: SeekMailErrorKind? = null,
    val showContactsSheet: Boolean = false,
    val contacts: List<SeekMailContact> = emptyList(),
    val isLoadingContacts: Boolean = false,
    val contactsErrorMessage: String? = null,
    val openedMailId: Long? = null,
    val openedPreviewSummary: SeekMailSummary? = null,
    val openedDetail: SeekMailDetail? = null,
    val isLoadingDetail: Boolean = false,
    val detailErrorMessage: String? = null,
    val readerMode: SeekMailReaderMode = SeekMailReaderMode.HTML,
    val attachments: List<SeekMailAttachment> = emptyList(),
    val isLoadingAttachments: Boolean = false,
    val attachmentsErrorMessage: String? = null,
    val allowRemoteImages: Boolean = false,
    val openedVpsInsight: VpsMailInsightUiState? = null,
    val exportingKey: String? = null,
    val rateLimit: SeekMailRateLimit? = null,
    val fontScale: Float = AppSettings.DEFAULT_FONT_SCALE,
    val purePitchAmoled: Boolean = false,
) {
    val selectedMailbox: SeekMailbox?
        get() = selectedMailboxId?.let { id -> mailboxes.firstOrNull { it.id == id } }

    val selectedTag: SeekMailTag?
        get() = selectedTagId?.let { id -> tags.firstOrNull { it.id == id } }

    val selectedLocalTag: SeekMailLocalTag?
        get() = selectedLocalTagId?.let { id -> localTags.firstOrNull { it.id == id } }

    val unreadLoadedCount: Int
        get() = activeInboxUnreadCount ?: mails.count { !it.isRead && it.id !in archivedMailIds }

    val selectedMailCount: Int
        get() = selectedMailIds.size

    val isAllVisibleSelected: Boolean
        get() = mails.isNotEmpty() && selectedMailIds.size == mails.size

    val allSelectedAreArchived: Boolean
        get() = selectedMailIds.isNotEmpty() && selectedMailIds.all { it in archivedMailIds }

    val hasUnreadSelected: Boolean
        get() = mails.any { it.id in selectedMailIds && !it.isRead }

    companion object {
        val DEFAULT_DOMAINS: List<String> = listOf("seek.li", "nodeseek.org")
    }
}

class SeekMailViewModel(
    private val seekMailRepository: SeekMailRepository,
    private val settingsRepository: SettingsRepository,
    private val vpsMailAutoIngestService: VpsMailAutoIngestService = NoOpVpsMailAutoIngestService,
    private val initialMailboxId: Long = 0L,
    initialMailId: Long = 0L,
) : ViewModel() {

    private data class InternalState(
        val isVerifyingApiKey: Boolean = false,
        val apiKeyErrorMessage: String? = null,
        val mailboxes: List<SeekMailbox> = emptyList(),
        val isLoadingMailboxes: Boolean = false,
        val mailboxErrorMessage: String? = null,
        val selectedMailboxId: Long? = null,
        val showCreateMailboxSheet: Boolean = false,
        val createLocalPart: String = "",
        val createDomain: String = SeekMailUiState.DEFAULT_DOMAINS.first(),
        val useCustomDomain: Boolean = false,
        val customDomain: String = "",
        val isCheckingAvailability: Boolean = false,
        val availabilityResult: Boolean? = null,
        val availabilityCheckedAddress: String? = null,
        val isCreatingMailbox: Boolean = false,
        val createMailboxError: String? = null,
        val filterMode: SeekMailFilterMode = SeekMailFilterMode.ALL,
        val loadedServerFilterMode: SeekMailFilterMode = SeekMailFilterMode.ALL,
        val loadedServerMailboxId: Long? = null,
        val loadedServerTagId: Long? = null,
        val loadedServerSearchQuery: String = "",
        val hasLoadedServerPage: Boolean = false,
        val tags: List<SeekMailTag> = emptyList(),
        val selectedTagId: Long? = null,
        val selectedLocalTagId: String? = null,
        val taggingTargetMail: SeekMailSummary? = null,
        val actionTargetMail: SeekMailSummary? = null,
        val pendingDeleteMail: SeekMailSummary? = null,
        val isSelectionMode: Boolean = false,
        val selectedMailIds: Set<Long> = emptySet(),
        val pendingBatchDeleteMails: List<SeekMailSummary> = emptyList(),
        val batchTaggingMails: List<SeekMailSummary> = emptyList(),
        val isSearchExpanded: Boolean = false,
        val searchDraft: String = "",
        val activeSearchQuery: String = "",
        val mails: List<SeekMailSummary> = emptyList(),
        val totalMailCount: Int = 0,
        val nextOffset: Int = 0,
        val nextUnreadCursor: String = "",
        val hasMoreMails: Boolean = false,
        val isLoadingMails: Boolean = false,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val mailsErrorMessage: String? = null,
        val mailsErrorKind: SeekMailErrorKind? = null,
        val showContactsSheet: Boolean = false,
        val contacts: List<SeekMailContact> = emptyList(),
        val isLoadingContacts: Boolean = false,
        val contactsErrorMessage: String? = null,
        val openedMailId: Long? = null,
        val openedPreviewSummary: SeekMailSummary? = null,
        val openedDetail: SeekMailDetail? = null,
        val isLoadingDetail: Boolean = false,
        val detailErrorMessage: String? = null,
        val readerMode: SeekMailReaderMode = SeekMailReaderMode.HTML,
        val attachments: List<SeekMailAttachment> = emptyList(),
        val isLoadingAttachments: Boolean = false,
        val attachmentsErrorMessage: String? = null,
        val allowedRemoteImageMailIds: Set<Long> = emptySet(),
        val isVpsPasswordRevealed: Boolean = false,
        val isIngestingVpsMail: Boolean = false,
        val exportingKey: String? = null,
    )

    private val _internalState = MutableStateFlow(
        InternalState(selectedMailboxId = initialMailboxId.takeIf { it > 0L }),
    )
    private val _snackbarChannel = Channel<SeekMailSnackbarEvent>(Channel.BUFFERED)
    val snackbarEvents: Flow<SeekMailSnackbarEvent> = _snackbarChannel.receiveAsFlow()

    private fun emitSnackbar(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        _snackbarChannel.trySend(
            SeekMailSnackbarEvent(
                message = message,
                actionLabel = actionLabel,
                onAction = onAction,
            ),
        )
    }

    private var lastHandledApiKey: String? = null
    private var pendingInitialMailId: Long? = initialMailId.takeIf { it > 0L }

    private var workspaceLoadJob: Job? = null
    private var mailLoadJob: Job? = null
    private var mailLoadGeneration: Int = 0
    private var detailLoadJob: Job? = null
    private var attachmentsLoadJob: Job? = null
    private var contactsLoadJob: Job? = null

    private val localOverlayFlow = combine(
        seekMailRepository.locallyReadMailIds,
        seekMailRepository.localTagStore,
        seekMailRepository.localArchiveStore,
        ::Triple,
    )

    private val vpsSyncContextFlow = combine(
        vpsMailAutoIngestService.assetsFlow,
        vpsMailAutoIngestService.sshServersFlow,
        vpsMailAutoIngestService.autoUpdatesByMailKeyFlow,
    ) { assets, sshServers, autoUpdates ->
        Triple(assets, sshServers, autoUpdates)
    }

    val uiState: StateFlow<SeekMailUiState> = combine(
        _internalState,
        settingsRepository.settings,
        localOverlayFlow,
        seekMailRepository.latestRateLimit,
        vpsSyncContextFlow,
    ) { state, settings, (readIds, localTagStore, archiveStore), rateLimit, (assets, sshServers, autoUpdates) ->
        val trimmedKey = settings.seekMailApiKey.trim()
        val deletedIds = archiveStore.deletedMailIds
        val archivedIds = archiveStore.archivedMailIds - deletedIds
        val overlaidMails = if (readIds.isEmpty()) {
            state.mails
        } else {
            state.mails.map { mail ->
                if (!mail.isRead && mail.id in readIds) mail.copy(isRead = true) else mail
            }
        }
        val activeInboxMails = overlaidMails.filter { mail ->
            mail.id !in deletedIds && mail.id !in archivedIds
        }
        val archivedMailsForMailbox = archiveStore
            .orderedArchivedMails(mailboxId = state.selectedMailboxId)
            .let { list ->
                if (readIds.isEmpty()) {
                    list
                } else {
                    list.map { mail ->
                        if (!mail.isRead && mail.id in readIds) mail.copy(isRead = true) else mail
                    }
                }
            }

        val localTagById = localTagStore.tags.associateBy { it.id }
        val localTagsByMailId = if (localTagStore.mailTagIds.isEmpty() || localTagById.isEmpty()) {
            emptyMap()
        } else {
            localTagStore.mailTagIds.mapNotNull { (mailId, ids) ->
                if (mailId in deletedIds) return@mapNotNull null
                val resolved = ids.mapNotNull { localTagById[it] }
                if (resolved.isEmpty()) null else mailId to resolved
            }.toMap()
        }
        val localTagCounts = if (localTagStore.tags.isEmpty() || localTagStore.mailTagIds.isEmpty()) {
            emptyMap()
        } else {
            val counts = HashMap<String, Int>(localTagStore.tags.size)
            localTagStore.mailTagIds.forEach { (mailId, ids) ->
                if (mailId in deletedIds) return@forEach
                ids.forEach { tagId ->
                    if (tagId in localTagById) {
                        counts[tagId] = (counts[tagId] ?: 0) + 1
                    }
                }
            }
            counts
        }
        val effectiveSelectedLocalTagId = state.selectedLocalTagId?.takeIf { it in localTagById }
        val searchNeedle = state.activeSearchQuery.trim().lowercase(Locale.US)
        val visibleMails = when {
            state.filterMode == SeekMailFilterMode.ARCHIVED -> {
                if (searchNeedle.isEmpty()) {
                    archivedMailsForMailbox
                } else {
                    archivedMailsForMailbox.filter { mail -> matchesSearchNeedle(mail, searchNeedle) }
                }
            }
            effectiveSelectedLocalTagId != null -> {
                val mergedPool = buildList {
                    val seen = HashSet<Long>(overlaidMails.size + archivedMailsForMailbox.size)
                    overlaidMails.forEach { mail ->
                        if (mail.id !in deletedIds && seen.add(mail.id)) add(mail)
                    }
                    archivedMailsForMailbox.forEach { mail ->
                        if (mail.id !in deletedIds && seen.add(mail.id)) {
                            if (searchNeedle.isEmpty() || matchesSearchNeedle(mail, searchNeedle)) {
                                add(mail)
                            }
                        }
                    }
                }
                mergedPool.filter { mail ->
                    effectiveSelectedLocalTagId in localTagStore.mailTagIds[mail.id].orEmpty()
                }
            }
            else -> activeInboxMails
        }
        val isArchivedMode = state.filterMode == SeekMailFilterMode.ARCHIVED
        val hiddenLoadedCount = state.mails.count { it.id in deletedIds || it.id in archivedIds }
        val effectiveTotalMailCount = when {
            isArchivedMode || effectiveSelectedLocalTagId != null -> visibleMails.size
            !state.hasMoreMails -> visibleMails.size
            else -> (state.totalMailCount - hiddenLoadedCount).coerceAtLeast(visibleMails.size)
        }
        val effectiveHasMoreMails = if (isArchivedMode) false else state.hasMoreMails
        val effectiveIsLoadingMails = if (isArchivedMode) false else state.isLoadingMails
        val effectiveMailsErrorMessage = if (isArchivedMode) null else state.mailsErrorMessage
        val effectiveMailsErrorKind = if (isArchivedMode) null else state.mailsErrorKind
        val activeInboxUnreadCount = activeInboxMails.count { !it.isRead }
        val effectiveSelectedMailIds = if (!state.isSelectionMode || state.selectedMailIds.isEmpty()) {
            emptySet()
        } else {
            val visibleIds = visibleMails.mapTo(HashSet(visibleMails.size)) { it.id }
            state.selectedMailIds.filterTo(LinkedHashSet()) { it in visibleIds }
        }

        val overlaidPreview = state.openedPreviewSummary?.let { preview ->
            if (!preview.isRead && preview.id in readIds) preview.copy(isRead = true) else preview
        }
        val overlaidDetail = state.openedDetail?.let { detail ->
            if (!detail.summary.isRead && detail.summary.id in readIds) {
                detail.copy(summary = detail.summary.copy(isRead = true))
            } else {
                detail
            }
        }
        val domains = buildAvailableDomains(state.mailboxes)
        val effectiveDomain = if (state.createDomain in domains) {
            state.createDomain
        } else {
            domains.firstOrNull() ?: SeekMailUiState.DEFAULT_DOMAINS.first()
        }

        val activeSummary = overlaidDetail?.summary ?: overlaidPreview
        val parsedVpsMail = when {
            overlaidDetail != null -> VpsBillingMailParser.parseDetail(overlaidDetail)
            overlaidPreview != null -> VpsBillingMailParser.parse(
                subject = overlaidPreview.subject,
                bodyText = overlaidPreview.snippet,
                fromName = overlaidPreview.fromName,
                fromAddress = overlaidPreview.fromAddress,
            )
            else -> null
        }
        val openedVpsInsight = parsedVpsMail?.let { parsed ->
            val matchStatus = vpsMailAutoIngestService.resolveMatchStatus(
                summary = activeSummary,
                parsed = parsed,
                assets = assets,
                sshServers = sshServers,
                autoUpdatesByMailKey = autoUpdates,
            )
            VpsMailInsightUiState(
                parsed = parsed,
                matchStatus = matchStatus,
                isPasswordRevealed = state.isVpsPasswordRevealed,
                isIngesting = state.isIngestingVpsMail,
            )
        }

        SeekMailUiState(
            hasConfiguredKey = trimmedKey.isNotEmpty(),
            apiKeyMaskedHint = maskApiKeyHint(trimmedKey),
            isVerifyingApiKey = state.isVerifyingApiKey,
            apiKeyErrorMessage = state.apiKeyErrorMessage,
            mailboxes = state.mailboxes,
            isLoadingMailboxes = state.isLoadingMailboxes,
            mailboxErrorMessage = state.mailboxErrorMessage,
            selectedMailboxId = state.selectedMailboxId,
            showCreateMailboxSheet = state.showCreateMailboxSheet,
            availableDomains = domains,
            createLocalPart = state.createLocalPart,
            createDomain = effectiveDomain,
            useCustomDomain = state.useCustomDomain,
            customDomain = state.customDomain,
            isCheckingAvailability = state.isCheckingAvailability,
            availabilityResult = state.availabilityResult,
            availabilityCheckedAddress = state.availabilityCheckedAddress,
            isCreatingMailbox = state.isCreatingMailbox,
            createMailboxError = state.createMailboxError,
            filterMode = state.filterMode,
            tags = state.tags,
            selectedTagId = state.selectedTagId,
            localTags = localTagStore.tags,
            localTagsByMailId = localTagsByMailId,
            localTagCounts = localTagCounts,
            selectedLocalTagId = effectiveSelectedLocalTagId,
            taggingTargetMail = state.taggingTargetMail,
            archivedMailIds = archivedIds,
            archivedMailCount = archivedMailsForMailbox.size,
            actionTargetMail = state.actionTargetMail,
            pendingDeleteMail = state.pendingDeleteMail,
            isSelectionMode = state.isSelectionMode,
            selectedMailIds = effectiveSelectedMailIds,
            pendingBatchDeleteMails = state.pendingBatchDeleteMails,
            batchTaggingMails = state.batchTaggingMails,
            activeInboxUnreadCount = activeInboxUnreadCount,
            isSearchExpanded = state.isSearchExpanded,
            searchDraft = state.searchDraft,
            activeSearchQuery = state.activeSearchQuery,
            mails = visibleMails,
            totalMailCount = effectiveTotalMailCount,
            hasMoreMails = effectiveHasMoreMails,
            isLoadingMails = effectiveIsLoadingMails,
            isRefreshing = state.isRefreshing,
            isLoadingMore = if (isArchivedMode) false else state.isLoadingMore,
            mailsErrorMessage = effectiveMailsErrorMessage,
            mailsErrorKind = effectiveMailsErrorKind,
            showContactsSheet = state.showContactsSheet,
            contacts = state.contacts,
            isLoadingContacts = state.isLoadingContacts,
            contactsErrorMessage = state.contactsErrorMessage,
            openedMailId = state.openedMailId,
            openedPreviewSummary = overlaidPreview,
            openedDetail = overlaidDetail,
            isLoadingDetail = state.isLoadingDetail,
            detailErrorMessage = state.detailErrorMessage,
            readerMode = state.readerMode,
            attachments = state.attachments,
            isLoadingAttachments = state.isLoadingAttachments,
            attachmentsErrorMessage = state.attachmentsErrorMessage,
            allowRemoteImages = state.openedMailId != null && state.openedMailId in state.allowedRemoteImageMailIds,
            openedVpsInsight = openedVpsInsight,
            exportingKey = state.exportingKey,
            rateLimit = rateLimit,
            fontScale = settings.fontScale,
            purePitchAmoled = settings.purePitchAmoled,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SeekMailUiState(
            hasConfiguredKey = settingsRepository.settings.value.seekMailApiKey.trim().isNotEmpty(),
            apiKeyMaskedHint = maskApiKeyHint(settingsRepository.settings.value.seekMailApiKey.trim()),
            selectedMailboxId = initialMailboxId.takeIf { it > 0L },
            fontScale = settingsRepository.settings.value.fontScale,
            purePitchAmoled = settingsRepository.settings.value.purePitchAmoled,
        ),
    )

    init {
        viewModelScope.launch {
            runCatching { vpsMailAutoIngestService.ensureLoaded() }
        }
        viewModelScope.launch {
            vpsMailAutoIngestService.renewalAutoUpdateEvents.collect { record ->
                emitSnackbar(
                    "续费邮件已自动将「${record.assetNickname}」到期日更新至 ${record.newExpiryDateIso}",
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.seekMailApiKey.trim() }
                .distinctUntilChanged()
                .collect { apiKey ->
                    handleApiKeyChange(apiKey)
                }
        }
    }

    private fun handleApiKeyChange(apiKey: String) {
        if (lastHandledApiKey == apiKey) return
        val previousKey = lastHandledApiKey
        val isFirstObservation = previousKey == null
        lastHandledApiKey = apiKey

        cancelActiveNetworkJobs()

        if (apiKey.isEmpty()) {
            _internalState.update { current ->
                InternalState(
                    selectedMailboxId = if (isFirstObservation) current.selectedMailboxId else null,
                )
            }
            return
        }

        if (!isFirstObservation) {
            val preserveInitialMailbox = previousKey.isNullOrEmpty() && initialMailboxId > 0L
            _internalState.update {
                InternalState(
                    selectedMailboxId = initialMailboxId.takeIf { preserveInitialMailbox },
                )
            }
        }

        loadWorkspace(forceRefresh = false, skipMailboxesFetch = false)
        consumePendingInitialMail()
    }

    private fun cancelActiveNetworkJobs() {
        workspaceLoadJob?.cancel()
        mailLoadJob?.cancel()
        detailLoadJob?.cancel()
        attachmentsLoadJob?.cancel()
        contactsLoadJob?.cancel()
        mailLoadGeneration++
    }

    private fun consumePendingInitialMail() {
        val mailIdToOpen = pendingInitialMailId ?: return
        pendingInitialMailId = null
        openMailById(mailIdToOpen)
    }

    fun verifyAndSaveApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            _internalState.update { it.copy(apiKeyErrorMessage = "请先输入 Seek 邮箱 API Key") }
            return
        }
        if (_internalState.value.isVerifyingApiKey) return

        viewModelScope.launch {
            _internalState.update {
                it.copy(isVerifyingApiKey = true, apiKeyErrorMessage = null)
            }
            val result = seekMailRepository.verifyApiKey(trimmed)
            result.onSuccess { status ->
                onVerificationSucceeded(trimmed, status)
            }.onFailure { err ->
                _internalState.update {
                    it.copy(
                        isVerifyingApiKey = false,
                        apiKeyErrorMessage = mapSeekMailError(err),
                    )
                }
            }
        }
    }

    private suspend fun onVerificationSucceeded(
        verifiedKey: String,
        status: SeekMailConnectionStatus,
    ) {
        lastHandledApiKey = verifiedKey
        settingsRepository.setSeekMailApiKey(verifiedKey)
        withTimeoutOrNull(2_000L) {
            settingsRepository.settings.first { it.seekMailApiKey.trim() == verifiedKey }
        }

        val sortedMailboxes = sortMailboxes(status.mailboxes)
        val domains = buildAvailableDomains(sortedMailboxes)
        _internalState.update { current ->
            val keepSelected = current.selectedMailboxId?.takeIf { id ->
                sortedMailboxes.any { it.id == id }
            }
            current.copy(
                isVerifyingApiKey = false,
                apiKeyErrorMessage = null,
                mailboxes = sortedMailboxes,
                isLoadingMailboxes = false,
                mailboxErrorMessage = null,
                selectedMailboxId = keepSelected,
                createDomain = domains.firstOrNull() ?: SeekMailUiState.DEFAULT_DOMAINS.first(),
            )
        }
        emitSnackbar("Seek 邮箱连接成功（共 ${sortedMailboxes.size} 个邮箱）")
        loadWorkspace(forceRefresh = false, skipMailboxesFetch = true)
        consumePendingInitialMail()
    }

    fun clearApiKeyError() {
        _internalState.update { it.copy(apiKeyErrorMessage = null) }
    }

    fun disconnectApiKey() {
        if (_internalState.value.isVerifyingApiKey) return
        viewModelScope.launch {
            settingsRepository.setSeekMailApiKey("")
            seekMailRepository.clearCache()
            emitSnackbar("已断开 Seek 邮箱密钥")
        }
    }

    private fun loadWorkspace(
        forceRefresh: Boolean,
        skipMailboxesFetch: Boolean,
    ) {
        workspaceLoadJob?.cancel()
        workspaceLoadJob = viewModelScope.launch {
            if (!skipMailboxesFetch) {
                launch { fetchMailboxes(forceRefresh = forceRefresh) }
            }
            launch { fetchTags(forceRefresh = forceRefresh) }
            loadMailPage(reset = true, isRefresh = forceRefresh, forceRefresh = forceRefresh)
        }
    }

    private suspend fun fetchMailboxes(forceRefresh: Boolean) {
        _internalState.update { it.copy(isLoadingMailboxes = true, mailboxErrorMessage = null) }
        seekMailRepository.getMailboxes(forceRefresh = forceRefresh)
            .onSuccess { list ->
                val sorted = sortMailboxes(list)
                val domains = buildAvailableDomains(sorted)
                _internalState.update { current ->
                    current.copy(
                        mailboxes = sorted,
                        isLoadingMailboxes = false,
                        mailboxErrorMessage = null,
                        createDomain = if (current.createDomain in domains) {
                            current.createDomain
                        } else {
                            domains.firstOrNull() ?: SeekMailUiState.DEFAULT_DOMAINS.first()
                        },
                    )
                }
            }
            .onFailure { err ->
                _internalState.update {
                    it.copy(
                        isLoadingMailboxes = false,
                        mailboxErrorMessage = mapSeekMailError(err),
                    )
                }
            }
    }

    private suspend fun fetchTags(forceRefresh: Boolean) {
        seekMailRepository.getTags(forceRefresh = forceRefresh)
            .onSuccess { list ->
                _internalState.update { it.copy(tags = list) }
            }
    }

    fun refresh() {
        if (!uiState.value.hasConfiguredKey) return
        loadWorkspace(forceRefresh = true, skipMailboxesFetch = false)
    }

    fun retryLoadMails() {
        if (!uiState.value.hasConfiguredKey) return
        if (_internalState.value.mailboxes.isEmpty()) {
            loadWorkspace(forceRefresh = true, skipMailboxesFetch = false)
        } else {
            loadMailPage(reset = true, isRefresh = false, forceRefresh = true)
        }
    }

    fun selectMailbox(mailboxId: Long?) {
        val current = _internalState.value
        if (current.selectedMailboxId == mailboxId && current.mailsErrorMessage == null) return
        _internalState.update { it.withoutSelection().copy(selectedMailboxId = mailboxId) }
        if (current.filterMode != SeekMailFilterMode.ARCHIVED) {
            loadMailPage(reset = true, isRefresh = false, forceRefresh = false)
        }
    }

    fun selectFilterMode(mode: SeekMailFilterMode) {
        val current = _internalState.value
        val hadTagFilter = current.selectedTagId != null || current.selectedLocalTagId != null
        if (current.filterMode == mode && !hadTagFilter && current.mailsErrorMessage == null) {
            return
        }
        _internalState.update {
            it.withoutSelection().copy(
                filterMode = mode,
                selectedTagId = null,
                selectedLocalTagId = null,
            )
        }
        if (mode == SeekMailFilterMode.ARCHIVED) return
        val needsReload = !current.hasLoadedServerPage ||
            current.loadedServerFilterMode != mode ||
            current.loadedServerMailboxId != current.selectedMailboxId ||
            current.loadedServerTagId != null ||
            current.loadedServerSearchQuery != current.activeSearchQuery.trim() ||
            current.mailsErrorMessage != null
        if (needsReload) {
            loadMailPage(reset = true, isRefresh = false, forceRefresh = false)
        }
    }

    fun toggleTagFilter(tagId: Long) {
        val current = _internalState.value
        val nextTagId = if (current.selectedTagId == tagId) null else tagId
        _internalState.update {
            it.withoutSelection().copy(
                filterMode = SeekMailFilterMode.ALL,
                selectedTagId = nextTagId,
                selectedLocalTagId = null,
            )
        }
        loadMailPage(reset = true, isRefresh = false, forceRefresh = false)
    }

    fun toggleLocalTagFilter(localTagId: String) {
        val current = _internalState.value
        val nextLocalTagId = if (current.selectedLocalTagId == localTagId) null else localTagId
        val nextFilterMode = if (nextLocalTagId != null) SeekMailFilterMode.ALL else current.filterMode
        val needsReload = nextFilterMode != SeekMailFilterMode.ARCHIVED && (
            !current.hasLoadedServerPage ||
                current.loadedServerFilterMode != nextFilterMode ||
                current.loadedServerMailboxId != current.selectedMailboxId ||
                current.loadedServerTagId != null ||
                current.loadedServerSearchQuery != current.activeSearchQuery.trim() ||
                current.mailsErrorMessage != null
            )
        _internalState.update {
            it.withoutSelection().copy(
                filterMode = nextFilterMode,
                selectedTagId = null,
                selectedLocalTagId = nextLocalTagId,
            )
        }
        if (needsReload) {
            loadMailPage(reset = true, isRefresh = false, forceRefresh = false)
        }
    }

    // ── Multi-Select & Batch Operations ──────────────────────────────────────

    fun enterSelectionMode(initialMailId: Long? = null) {
        _internalState.update { current ->
            current.copy(
                isSelectionMode = true,
                selectedMailIds = if (initialMailId != null) setOf(initialMailId) else emptySet(),
                actionTargetMail = null,
                taggingTargetMail = null,
                pendingDeleteMail = null,
                pendingBatchDeleteMails = emptyList(),
                batchTaggingMails = emptyList(),
            )
        }
    }

    fun enterSelectionModeFromActionSheet() {
        val targetId = _internalState.value.actionTargetMail?.id
        enterSelectionMode(initialMailId = targetId)
    }

    fun exitSelectionMode() {
        _internalState.update { it.withoutSelection() }
    }

    fun toggleMailSelection(mailId: Long) {
        if (mailId <= 0L) return
        _internalState.update { current ->
            if (!current.isSelectionMode) {
                current.copy(
                    isSelectionMode = true,
                    selectedMailIds = setOf(mailId),
                    actionTargetMail = null,
                    taggingTargetMail = null,
                    pendingDeleteMail = null,
                )
            } else {
                val next = if (mailId in current.selectedMailIds) {
                    current.selectedMailIds - mailId
                } else {
                    current.selectedMailIds + mailId
                }
                current.copy(selectedMailIds = next)
            }
        }
    }

    fun toggleSelectAllVisible() {
        val visibleIds = uiState.value.mails.map { it.id }
        if (visibleIds.isEmpty()) return
        _internalState.update { current ->
            val visibleSet = visibleIds.toSet()
            val allSelected = visibleSet.all { it in current.selectedMailIds }
            current.copy(
                isSelectionMode = true,
                selectedMailIds = if (allSelected) emptySet() else visibleSet,
            )
        }
    }

    private fun resolveSelectedVisibleMails(): List<SeekMailSummary> {
        val selectedIds = _internalState.value.selectedMailIds
        if (selectedIds.isEmpty()) return emptyList()
        return uiState.value.mails.filter { it.id in selectedIds }
    }

    fun archiveSelectedMails() {
        val targets = resolveSelectedVisibleMails()
        if (targets.isEmpty()) return
        archiveMailsBatch(targets = targets, showUndoSnackbar = true)
    }

    fun unarchiveSelectedMails() {
        val targets = resolveSelectedVisibleMails()
        if (targets.isEmpty()) return
        unarchiveMailsBatch(targets = targets, showUndoSnackbar = true)
    }

    private fun archiveMailsBatch(
        targets: List<SeekMailSummary>,
        showUndoSnackbar: Boolean,
        feedbackMessage: String? = null,
    ) {
        if (targets.isEmpty()) return
        val targetIds = targets.mapTo(HashSet(targets.size)) { it.id }
        val shouldCloseDetail = _internalState.value.openedMailId in targetIds
        _internalState.update { current ->
            current.withoutSelection().copy(
                actionTargetMail = if (current.actionTargetMail?.id in targetIds) null else current.actionTargetMail,
                taggingTargetMail = if (current.taggingTargetMail?.id in targetIds) null else current.taggingTargetMail,
            )
        }
        if (shouldCloseDetail) {
            closeMailDetail()
        }
        viewModelScope.launch {
            seekMailRepository.archiveMailsLocally(targets)
            if (showUndoSnackbar) {
                emitSnackbar(
                    message = "已存档 ${targets.size} 封邮件",
                    actionLabel = "撤销",
                    onAction = {
                        unarchiveMailsBatch(
                            targets = targets,
                            showUndoSnackbar = false,
                            feedbackMessage = "已撤销存档",
                        )
                    },
                )
            } else if (!feedbackMessage.isNullOrBlank()) {
                emitSnackbar(feedbackMessage)
            }
        }
    }

    private fun unarchiveMailsBatch(
        targets: List<SeekMailSummary>,
        showUndoSnackbar: Boolean,
        feedbackMessage: String? = null,
    ) {
        if (targets.isEmpty()) return
        val targetIds = targets.mapTo(HashSet(targets.size)) { it.id }
        val stateSnapshot = _internalState.value
        val shouldCloseDetail = stateSnapshot.openedMailId in targetIds &&
            stateSnapshot.filterMode == SeekMailFilterMode.ARCHIVED
        _internalState.update { current ->
            current.withoutSelection().copy(
                actionTargetMail = if (current.actionTargetMail?.id in targetIds) null else current.actionTargetMail,
            )
        }
        if (shouldCloseDetail) {
            closeMailDetail()
        }
        viewModelScope.launch {
            seekMailRepository.unarchiveMailsLocally(targetIds)
            targets.forEach { mergeMailBackIntoInboxIfCompatible(it) }
            if (showUndoSnackbar) {
                emitSnackbar(
                    message = "已取消存档 ${targets.size} 封邮件",
                    actionLabel = "撤销",
                    onAction = {
                        archiveMailsBatch(
                            targets = targets,
                            showUndoSnackbar = false,
                            feedbackMessage = "已重新存档",
                        )
                    },
                )
            } else if (!feedbackMessage.isNullOrBlank()) {
                emitSnackbar(feedbackMessage)
            }
        }
    }

    fun markSelectedMailsRead() {
        val targets = resolveSelectedVisibleMails().filter { !it.isRead }
        _internalState.update { it.withoutSelection() }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            seekMailRepository.markMailsReadLocally(targets.map { it.id })
            emitSnackbar("已将 ${targets.size} 封邮件标为已读")
        }
    }

    fun requestDeleteSelectedMails() {
        val targets = resolveSelectedVisibleMails()
        if (targets.isEmpty()) return
        _internalState.update { it.copy(pendingBatchDeleteMails = targets) }
    }

    fun dismissBatchDeleteDialog() {
        _internalState.update { it.copy(pendingBatchDeleteMails = emptyList()) }
    }

    fun confirmDeleteSelectedMails() {
        val targets = _internalState.value.pendingBatchDeleteMails
        if (targets.isEmpty()) return
        val targetIds = targets.mapTo(HashSet(targets.size)) { it.id }
        val archiveStore = seekMailRepository.localArchiveStore.value
        val previouslyArchived = targets.filter { archiveStore.isArchived(it.id) }
        val shouldCloseDetail = _internalState.value.openedMailId in targetIds
        _internalState.update { current ->
            current.withoutSelection().copy(
                actionTargetMail = if (current.actionTargetMail?.id in targetIds) null else current.actionTargetMail,
                pendingDeleteMail = if (current.pendingDeleteMail?.id in targetIds) null else current.pendingDeleteMail,
                taggingTargetMail = if (current.taggingTargetMail?.id in targetIds) null else current.taggingTargetMail,
            )
        }
        if (shouldCloseDetail) {
            closeMailDetail()
        }
        viewModelScope.launch {
            seekMailRepository.deleteMailsLocally(targetIds)
            emitSnackbar(
                message = "已从本地删除 ${targets.size} 封邮件",
                actionLabel = "撤销",
                onAction = {
                    restoreDeletedMailsBatch(
                        targets = targets,
                        previouslyArchived = previouslyArchived,
                    )
                },
            )
        }
    }

    private fun restoreDeletedMailsBatch(
        targets: List<SeekMailSummary>,
        previouslyArchived: List<SeekMailSummary>,
    ) {
        if (targets.isEmpty()) return
        val archivedIds = previouslyArchived.mapTo(HashSet(previouslyArchived.size)) { it.id }
        viewModelScope.launch {
            seekMailRepository.restoreDeletedMailsLocally(
                mailIds = targets.map { it.id },
                restoredArchiveSummaries = previouslyArchived,
            )
            targets.forEach { summary ->
                if (summary.id !in archivedIds) {
                    mergeMailBackIntoInboxIfCompatible(summary)
                }
            }
            emitSnackbar("已恢复 ${targets.size} 封邮件")
        }
    }

    fun openBatchTagSheet() {
        val targets = resolveSelectedVisibleMails()
        if (targets.isEmpty()) return
        _internalState.update { it.copy(batchTaggingMails = targets) }
    }

    fun dismissBatchTagSheet() {
        _internalState.update { it.copy(batchTaggingMails = emptyList()) }
    }

    fun toggleLocalTagForBatch(tagId: String) {
        val targets = _internalState.value.batchTaggingMails
        if (targets.isEmpty() || tagId.isBlank()) return
        val targetIds = targets.map { it.id }
        viewModelScope.launch {
            val tagMap = seekMailRepository.localTagStore.value.mailTagIds
            val allHaveTag = targetIds.all { mailId -> tagId in tagMap[mailId].orEmpty() }
            if (allHaveTag) {
                seekMailRepository.removeLocalTagFromMails(targetIds, tagId)
            } else {
                seekMailRepository.addLocalTagToMails(targetIds, tagId)
            }
        }
    }

    fun createAndAttachLocalTagForBatch(name: String, colorHex: String) {
        val trimmed = name.trim()
        val targets = _internalState.value.batchTaggingMails
        if (trimmed.isEmpty() || targets.isEmpty()) return
        val targetIds = targets.map { it.id }
        viewModelScope.launch {
            seekMailRepository.createLocalTag(name = trimmed, colorHex = colorHex)
                .onSuccess { created ->
                    seekMailRepository.addLocalTagToMails(targetIds, created.id)
                    emitSnackbar("已为 ${targets.size} 封邮件打上标签「${created.name}」")
                }
                .onFailure { err ->
                    emitSnackbar(
                        err.message?.takeIf { it.isNotBlank() } ?: "创建标签失败",
                    )
                }
        }
    }

    fun clearLocalTagsForBatch() {
        val targets = _internalState.value.batchTaggingMails
        if (targets.isEmpty()) return
        viewModelScope.launch {
            seekMailRepository.clearLocalTagsForMails(targets.map { it.id })
            emitSnackbar("已清空 ${targets.size} 封邮件的本地标签")
        }
    }

    private fun InternalState.withoutSelection(): InternalState = copy(
        isSelectionMode = false,
        selectedMailIds = emptySet(),
        pendingBatchDeleteMails = emptyList(),
        batchTaggingMails = emptyList(),
    )

    fun openMailActionSheet(summary: SeekMailSummary) {
        _internalState.update { it.copy(actionTargetMail = summary) }
    }

    fun dismissMailActionSheet() {
        _internalState.update { it.copy(actionTargetMail = null) }
    }

    fun openTagSheetFromActionSheet() {
        _internalState.update { current ->
            val target = current.actionTargetMail ?: return@update current
            current.copy(
                actionTargetMail = null,
                taggingTargetMail = target,
            )
        }
    }

    fun requestDeleteMail(summary: SeekMailSummary) {
        _internalState.update {
            it.copy(
                actionTargetMail = null,
                pendingDeleteMail = summary,
            )
        }
    }

    fun dismissDeleteMailDialog() {
        _internalState.update { it.copy(pendingDeleteMail = null) }
    }

    fun confirmDeleteMail() {
        val target = _internalState.value.pendingDeleteMail ?: return
        deleteMail(target)
    }

    fun archiveMail(
        summary: SeekMailSummary,
        showUndoSnackbar: Boolean = true,
        feedbackMessage: String? = null,
    ) {
        val shouldCloseDetail = _internalState.value.openedMailId == summary.id
        _internalState.update { current ->
            current.copy(
                actionTargetMail = if (current.actionTargetMail?.id == summary.id) null else current.actionTargetMail,
                taggingTargetMail = if (current.taggingTargetMail?.id == summary.id) null else current.taggingTargetMail,
            )
        }
        if (shouldCloseDetail) {
            closeMailDetail()
        }
        viewModelScope.launch {
            seekMailRepository.archiveMailLocally(summary)
            if (showUndoSnackbar) {
                emitSnackbar(
                    message = "已存档邮件",
                    actionLabel = "撤销",
                    onAction = {
                        unarchiveMail(
                            summary = summary,
                            showUndoSnackbar = false,
                            feedbackMessage = "已撤销存档",
                        )
                    },
                )
            } else if (!feedbackMessage.isNullOrBlank()) {
                emitSnackbar(feedbackMessage)
            }
        }
    }

    fun unarchiveMail(
        summary: SeekMailSummary,
        showUndoSnackbar: Boolean = true,
        feedbackMessage: String? = null,
    ) {
        val stateSnapshot = _internalState.value
        val shouldCloseDetail = stateSnapshot.openedMailId == summary.id &&
            stateSnapshot.filterMode == SeekMailFilterMode.ARCHIVED
        _internalState.update { current ->
            current.copy(
                actionTargetMail = if (current.actionTargetMail?.id == summary.id) null else current.actionTargetMail,
            )
        }
        if (shouldCloseDetail) {
            closeMailDetail()
        }
        viewModelScope.launch {
            seekMailRepository.unarchiveMailLocally(summary.id)
            mergeMailBackIntoInboxIfCompatible(summary)
            if (showUndoSnackbar) {
                emitSnackbar(
                    message = "已取消存档，移回收件箱",
                    actionLabel = "撤销",
                    onAction = {
                        archiveMail(
                            summary = summary,
                            showUndoSnackbar = false,
                            feedbackMessage = "已重新存档",
                        )
                    },
                )
            } else if (!feedbackMessage.isNullOrBlank()) {
                emitSnackbar(feedbackMessage)
            }
        }
    }

    fun deleteMail(summary: SeekMailSummary) {
        val wasArchived = seekMailRepository.localArchiveStore.value.isArchived(summary.id)
        val shouldCloseDetail = _internalState.value.openedMailId == summary.id
        _internalState.update { current ->
            current.copy(
                actionTargetMail = if (current.actionTargetMail?.id == summary.id) null else current.actionTargetMail,
                pendingDeleteMail = if (current.pendingDeleteMail?.id == summary.id) null else current.pendingDeleteMail,
                taggingTargetMail = if (current.taggingTargetMail?.id == summary.id) null else current.taggingTargetMail,
            )
        }
        if (shouldCloseDetail) {
            closeMailDetail()
        }
        viewModelScope.launch {
            seekMailRepository.deleteMailLocally(summary.id)
            emitSnackbar(
                message = "已从本地删除该邮件",
                actionLabel = "撤销",
                onAction = {
                    restoreDeletedMail(summary = summary, wasArchived = wasArchived)
                },
            )
        }
    }

    private fun restoreDeletedMail(
        summary: SeekMailSummary,
        wasArchived: Boolean,
    ) {
        viewModelScope.launch {
            seekMailRepository.restoreDeletedMailLocally(
                mailId = summary.id,
                restoredArchiveSummary = if (wasArchived) summary else null,
            )
            if (!wasArchived) {
                mergeMailBackIntoInboxIfCompatible(summary)
            }
            emitSnackbar("已恢复该邮件")
        }
    }

    private fun mergeMailBackIntoInboxIfCompatible(summary: SeekMailSummary) {
        _internalState.update { current ->
            if (!current.hasLoadedServerPage) return@update current
            if (current.mails.any { it.id == summary.id }) return@update current
            val mailboxMatches = current.loadedServerMailboxId == null ||
                current.loadedServerMailboxId == summary.mailboxId
            val modeMatches = current.loadedServerFilterMode == SeekMailFilterMode.ALL ||
                (current.loadedServerFilterMode == SeekMailFilterMode.UNREAD && !summary.isRead)
            val tagMatches = current.loadedServerTagId == null ||
                summary.tags.any { it.id == current.loadedServerTagId }
            val queryMatches = current.loadedServerSearchQuery.isBlank() ||
                matchesSearchNeedle(summary, current.loadedServerSearchQuery.lowercase(Locale.US))
            if (!mailboxMatches || !modeMatches || !tagMatches || !queryMatches) {
                return@update current
            }
            val merged = (current.mails + summary)
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<SeekMailSummary> { it.receivedAt.ifBlank { it.createdAt } }
                        .thenByDescending { it.id },
                )
            current.copy(mails = merged)
        }
    }

    fun openMailTagSheet(summary: SeekMailSummary) {
        _internalState.update { it.copy(taggingTargetMail = summary) }
    }

    fun dismissMailTagSheet() {
        _internalState.update { it.copy(taggingTargetMail = null) }
    }

    fun toggleLocalTagForMail(mailId: Long, tagId: String) {
        viewModelScope.launch {
            seekMailRepository.toggleLocalTagForMail(mailId = mailId, tagId = tagId)
        }
    }

    fun createAndAttachLocalTag(mailId: Long, name: String, colorHex: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            seekMailRepository.createLocalTag(name = trimmed, colorHex = colorHex)
                .onSuccess { created ->
                    val currentIds = seekMailRepository.localTagStore.value.mailTagIds[mailId].orEmpty()
                    if (created.id !in currentIds) {
                        seekMailRepository.toggleLocalTagForMail(mailId = mailId, tagId = created.id)
                    }
                    emitSnackbar("已打上标签「${created.name}」")
                }
                .onFailure { err ->
                    emitSnackbar(
                        err.message?.takeIf { it.isNotBlank() } ?: "创建标签失败",
                    )
                }
        }
    }

    fun clearLocalTagsForMail(mailId: Long) {
        viewModelScope.launch {
            seekMailRepository.setLocalTagsForMail(mailId = mailId, tagIds = emptyList())
            emitSnackbar("已清空该邮件的本地标签")
        }
    }

    fun deleteLocalTag(tagId: String) {
        viewModelScope.launch {
            val tagName = seekMailRepository.localTagStore.value.tags
                .firstOrNull { it.id == tagId }
                ?.name
            seekMailRepository.deleteLocalTag(tagId)
            if (_internalState.value.selectedLocalTagId == tagId) {
                _internalState.update { it.copy(selectedLocalTagId = null) }
            }
            if (!tagName.isNullOrBlank()) {
                emitSnackbar("已删除标签「$tagName」")
            }
        }
    }

    fun toggleSearchBar() {
        val current = _internalState.value
        if (current.isSearchExpanded) {
            val hadActiveSearch = current.activeSearchQuery.isNotBlank()
            _internalState.update {
                it.copy(
                    isSearchExpanded = false,
                    searchDraft = "",
                    activeSearchQuery = "",
                )
            }
            if (hadActiveSearch && current.filterMode != SeekMailFilterMode.ARCHIVED) {
                loadMailPage(reset = true, isRefresh = false, forceRefresh = false)
            }
        } else {
            _internalState.update { it.copy(isSearchExpanded = true) }
        }
    }

    fun updateSearchDraft(query: String) {
        _internalState.update { it.copy(searchDraft = query) }
    }

    fun submitSearch(explicitQuery: String? = null) {
        val query = (explicitQuery ?: _internalState.value.searchDraft).trim()
        val current = _internalState.value
        if (query == current.activeSearchQuery && query == current.searchDraft && current.mailsErrorMessage == null) {
            return
        }
        val staysArchived = current.filterMode == SeekMailFilterMode.ARCHIVED
        _internalState.update {
            it.withoutSelection().copy(
                searchDraft = query,
                activeSearchQuery = query,
                filterMode = if (query.isNotEmpty() && !staysArchived) SeekMailFilterMode.ALL else it.filterMode,
            )
        }
        if (!staysArchived) {
            loadMailPage(reset = true, isRefresh = false, forceRefresh = false)
        }
    }

    fun clearSearch() {
        val current = _internalState.value
        val hadActiveSearch = current.activeSearchQuery.isNotBlank()
        _internalState.update {
            it.withoutSelection().copy(
                searchDraft = "",
                activeSearchQuery = "",
            )
        }
        if (hadActiveSearch && current.filterMode != SeekMailFilterMode.ARCHIVED) {
            loadMailPage(reset = true, isRefresh = false, forceRefresh = false)
        }
    }

    fun clearAllFilters() {
        _internalState.update {
            it.withoutSelection().copy(
                selectedMailboxId = null,
                filterMode = SeekMailFilterMode.ALL,
                selectedTagId = null,
                selectedLocalTagId = null,
                searchDraft = "",
                activeSearchQuery = "",
            )
        }
        loadMailPage(reset = true, isRefresh = false, forceRefresh = false)
    }

    fun loadMoreMails() {
        val current = _internalState.value
        if (
            current.filterMode == SeekMailFilterMode.ARCHIVED ||
            !current.hasMoreMails ||
            current.isLoadingMails ||
            current.isRefreshing ||
            current.isLoadingMore
        ) {
            return
        }
        loadMailPage(reset = false, isRefresh = false, forceRefresh = false)
    }

    private fun loadMailPage(
        reset: Boolean,
        isRefresh: Boolean,
        forceRefresh: Boolean,
    ) {
        mailLoadJob?.cancel()
        val generation = ++mailLoadGeneration

        val snapshot = _internalState.value
        val mailboxId = snapshot.selectedMailboxId
        val requestedServerMode = if (snapshot.filterMode == SeekMailFilterMode.UNREAD) {
            SeekMailFilterMode.UNREAD
        } else {
            SeekMailFilterMode.ALL
        }
        val tagId = if (snapshot.filterMode == SeekMailFilterMode.ARCHIVED) null else snapshot.selectedTagId
        val searchQuery = if (snapshot.filterMode == SeekMailFilterMode.ARCHIVED) {
            null
        } else {
            snapshot.activeSearchQuery.trim().takeIf { it.isNotEmpty() }
        }

        _internalState.update {
            if (reset) {
                it.copy(
                    isLoadingMails = !isRefresh && it.mails.isEmpty(),
                    isRefreshing = isRefresh,
                    isLoadingMore = false,
                    mailsErrorMessage = null,
                    mailsErrorKind = null,
                )
            } else {
                it.copy(
                    isLoadingMore = true,
                    mailsErrorMessage = null,
                    mailsErrorKind = null,
                )
            }
        }

        mailLoadJob = viewModelScope.launch {
            if (requestedServerMode == SeekMailFilterMode.UNREAD) {
                val cursor = if (reset) null else snapshot.nextUnreadCursor.takeIf { it.isNotBlank() }
                seekMailRepository.getUnreadMails(
                    mailboxId = mailboxId,
                    limit = UNREAD_PAGE_SIZE,
                    cursor = cursor,
                ).onSuccess { page ->
                    if (generation != mailLoadGeneration) return@onSuccess
                    applyUnreadPage(
                        page = page,
                        reset = reset,
                        mailboxId = mailboxId,
                        searchQuery = searchQuery,
                    )
                }.onFailure { err ->
                    if (generation != mailLoadGeneration) return@onFailure
                    applyMailLoadError(err)
                }
            } else {
                val offset = if (reset) 0 else snapshot.nextOffset
                seekMailRepository.getMails(
                    mailboxId = mailboxId,
                    search = searchQuery,
                    tagId = tagId,
                    limit = MAIL_PAGE_SIZE,
                    offset = offset,
                    forceRefresh = forceRefresh || !reset,
                ).onSuccess { page ->
                    if (generation != mailLoadGeneration) return@onSuccess
                    applyAllMailsPage(
                        page = page,
                        reset = reset,
                        mailboxId = mailboxId,
                        tagId = tagId,
                        searchQuery = searchQuery.orEmpty(),
                    )
                }.onFailure { err ->
                    if (generation != mailLoadGeneration) return@onFailure
                    applyMailLoadError(err)
                }
            }
        }
    }

    private fun applyAllMailsPage(
        page: SeekMailPage,
        reset: Boolean,
        mailboxId: Long?,
        tagId: Long?,
        searchQuery: String,
    ) {
        _internalState.update { current ->
            val merged = if (reset) {
                page.items
            } else {
                (current.mails + page.items).distinctBy { it.id }
            }
            current.copy(
                mails = merged,
                loadedServerFilterMode = SeekMailFilterMode.ALL,
                loadedServerMailboxId = mailboxId,
                loadedServerTagId = tagId,
                loadedServerSearchQuery = searchQuery,
                hasLoadedServerPage = true,
                totalMailCount = page.total,
                nextOffset = page.offset + page.items.size,
                nextUnreadCursor = "",
                hasMoreMails = page.hasMore,
                isLoadingMails = false,
                isRefreshing = false,
                isLoadingMore = false,
                mailsErrorMessage = null,
                mailsErrorKind = null,
            )
        }
        viewModelScope.launch {
            runCatching { vpsMailAutoIngestService.processIncomingSummaries(page.items) }
        }
    }

    private fun applyUnreadPage(
        page: SeekMailUnreadPage,
        reset: Boolean,
        mailboxId: Long?,
        searchQuery: String?,
    ) {
        val filteredItems = if (searchQuery.isNullOrBlank()) {
            page.items
        } else {
            val needle = searchQuery.lowercase(Locale.US)
            page.items.filter { mail -> matchesSearchNeedle(mail, needle) }
        }
        _internalState.update { current ->
            val merged = if (reset) {
                filteredItems
            } else {
                (current.mails + filteredItems).distinctBy { it.id }
            }
            current.copy(
                mails = merged,
                loadedServerFilterMode = SeekMailFilterMode.UNREAD,
                loadedServerMailboxId = mailboxId,
                loadedServerTagId = null,
                loadedServerSearchQuery = searchQuery.orEmpty(),
                hasLoadedServerPage = true,
                totalMailCount = merged.size,
                nextOffset = 0,
                nextUnreadCursor = page.nextCursor,
                hasMoreMails = page.hasMore && page.nextCursor.isNotBlank(),
                isLoadingMails = false,
                isRefreshing = false,
                isLoadingMore = false,
                mailsErrorMessage = null,
                mailsErrorKind = null,
            )
        }
        viewModelScope.launch {
            runCatching { vpsMailAutoIngestService.processIncomingSummaries(page.items) }
        }
    }

    private fun applyMailLoadError(error: Throwable) {
        val kind = (error as? SeekMailException)?.kind ?: SeekMailErrorKind.UNKNOWN
        val message = mapSeekMailError(error)
        _internalState.update { current ->
            current.copy(
                isLoadingMails = false,
                isRefreshing = false,
                isLoadingMore = false,
                mailsErrorMessage = message,
                mailsErrorKind = kind,
            )
        }
        if (_internalState.value.mails.isNotEmpty() && _internalState.value.filterMode != SeekMailFilterMode.ARCHIVED) {
            emitSnackbar(message)
        }
    }

    // ── Create Mailbox / Alias Sheet ─────────────────────────────────────────

    fun openCreateMailboxSheet() {
        val domains = buildAvailableDomains(_internalState.value.mailboxes)
        _internalState.update { current ->
            current.copy(
                showCreateMailboxSheet = true,
                createDomain = if (current.createDomain in domains) {
                    current.createDomain
                } else {
                    domains.firstOrNull() ?: SeekMailUiState.DEFAULT_DOMAINS.first()
                },
                availabilityResult = null,
                availabilityCheckedAddress = null,
                createMailboxError = null,
            )
        }
    }

    fun dismissCreateMailboxSheet() {
        _internalState.update {
            it.copy(
                showCreateMailboxSheet = false,
                createMailboxError = null,
                isCheckingAvailability = false,
                isCreatingMailbox = false,
            )
        }
    }

    fun updateCreateLocalPart(localPart: String) {
        _internalState.update {
            it.copy(
                createLocalPart = localPart,
                availabilityResult = null,
                availabilityCheckedAddress = null,
                createMailboxError = null,
            )
        }
    }

    fun selectCreateDomain(domain: String) {
        _internalState.update {
            it.copy(
                createDomain = domain,
                useCustomDomain = false,
                availabilityResult = null,
                availabilityCheckedAddress = null,
                createMailboxError = null,
            )
        }
    }

    fun enableCustomDomain() {
        _internalState.update {
            it.copy(
                useCustomDomain = true,
                availabilityResult = null,
                availabilityCheckedAddress = null,
                createMailboxError = null,
            )
        }
    }

    fun updateCustomDomain(domain: String) {
        _internalState.update {
            it.copy(
                customDomain = domain,
                availabilityResult = null,
                availabilityCheckedAddress = null,
                createMailboxError = null,
            )
        }
    }

    fun checkMailboxAvailability() {
        val address = validateDraftMailboxAddress() ?: return
        if (_internalState.value.isCheckingAvailability || _internalState.value.isCreatingMailbox) return

        viewModelScope.launch {
            _internalState.update {
                it.copy(
                    isCheckingAvailability = true,
                    createMailboxError = null,
                )
            }
            seekMailRepository.checkMailboxAvailable(address)
                .onSuccess { available ->
                    _internalState.update {
                        it.copy(
                            isCheckingAvailability = false,
                            availabilityResult = available,
                            availabilityCheckedAddress = address,
                            createMailboxError = null,
                        )
                    }
                }
                .onFailure { err ->
                    _internalState.update {
                        it.copy(
                            isCheckingAvailability = false,
                            createMailboxError = mapCreateMailboxError(err),
                        )
                    }
                }
        }
    }

    fun createMailbox() {
        val address = validateDraftMailboxAddress() ?: return
        if (_internalState.value.isCreatingMailbox) return

        viewModelScope.launch {
            _internalState.update {
                it.copy(
                    isCreatingMailbox = true,
                    createMailboxError = null,
                )
            }
            seekMailRepository.createMailbox(address)
                .onSuccess { created ->
                    _internalState.update { current ->
                        val updatedMailboxes = sortMailboxes(
                            (listOf(created) + current.mailboxes).distinctBy { it.id },
                        )
                        current.copy(
                            mailboxes = updatedMailboxes,
                            selectedMailboxId = created.id,
                            filterMode = SeekMailFilterMode.ALL,
                            showCreateMailboxSheet = false,
                            createLocalPart = "",
                            useCustomDomain = false,
                            customDomain = "",
                            isCreatingMailbox = false,
                            availabilityResult = null,
                            availabilityCheckedAddress = null,
                            createMailboxError = null,
                        )
                    }
                    emitSnackbar("已创建邮箱：${created.address}")
                    loadMailPage(reset = true, isRefresh = false, forceRefresh = true)
                }
                .onFailure { err ->
                    _internalState.update {
                        it.copy(
                            isCreatingMailbox = false,
                            createMailboxError = mapCreateMailboxError(err),
                        )
                    }
                }
        }
    }

    private fun validateDraftMailboxAddress(): String? {
        val state = _internalState.value
        val local = state.createLocalPart.trim().lowercase(Locale.US)
        if (local.isEmpty()) {
            _internalState.update { it.copy(createMailboxError = "请输入邮箱前缀（@ 之前的用户名）") }
            return null
        }
        if (local.contains('@') || local.any(Char::isWhitespace)) {
            _internalState.update { it.copy(createMailboxError = "邮箱前缀不能包含 @ 或空格") }
            return null
        }
        if (!LOCAL_PART_REGEX.matches(local)) {
            _internalState.update {
                it.copy(createMailboxError = "前缀仅支持字母、数字及 . _ - + 字符，且不能以点号开头或结尾")
            }
            return null
        }

        val domain = (if (state.useCustomDomain) state.customDomain else state.createDomain)
            .trim()
            .lowercase(Locale.US)
        if (domain.isEmpty() || !DOMAIN_REGEX.matches(domain)) {
            _internalState.update { it.copy(createMailboxError = "请输入有效的邮箱域名，例如 seek.li") }
            return null
        }

        return "$local@$domain"
    }

    // ── Contacts Sheet ───────────────────────────────────────────────────────

    fun openContactsSheet() {
        _internalState.update { it.copy(showContactsSheet = true) }
        val current = _internalState.value
        if (current.contacts.isEmpty() && !current.isLoadingContacts) {
            loadContacts(forceRefresh = false)
        }
    }

    fun dismissContactsSheet() {
        _internalState.update { it.copy(showContactsSheet = false) }
    }

    fun refreshContacts() {
        loadContacts(forceRefresh = true)
    }

    private fun loadContacts(forceRefresh: Boolean) {
        contactsLoadJob?.cancel()
        contactsLoadJob = viewModelScope.launch {
            _internalState.update { it.copy(isLoadingContacts = true, contactsErrorMessage = null) }
            seekMailRepository.getContacts(limit = CONTACTS_LIMIT, forceRefresh = forceRefresh)
                .onSuccess { list ->
                    _internalState.update {
                        it.copy(
                            contacts = list,
                            isLoadingContacts = false,
                            contactsErrorMessage = null,
                        )
                    }
                }
                .onFailure { err ->
                    _internalState.update {
                        it.copy(
                            isLoadingContacts = false,
                            contactsErrorMessage = mapSeekMailError(err),
                        )
                    }
                }
        }
    }

    fun filterMailsByContact(contact: SeekMailContact) {
        val email = contact.email.trim()
        _internalState.update {
            it.withoutSelection().copy(
                showContactsSheet = false,
                isSearchExpanded = true,
                searchDraft = email,
                activeSearchQuery = email,
                filterMode = SeekMailFilterMode.ALL,
                selectedTagId = null,
                selectedLocalTagId = null,
            )
        }
        loadMailPage(reset = true, isRefresh = false, forceRefresh = false)
    }

    // ── Mail Detail Reader ───────────────────────────────────────────────────

    fun openMail(summary: SeekMailSummary) {
        openMailInternal(mailId = summary.id, previewSummary = summary, forceRefresh = false)
    }

    fun openMailById(mailId: Long) {
        if (mailId <= 0L) return
        val existingPreview = _internalState.value.mails.firstOrNull { it.id == mailId }
            ?: seekMailRepository.localArchiveStore.value.archivedMails[mailId]
        openMailInternal(mailId = mailId, previewSummary = existingPreview, forceRefresh = false)
    }

    fun retryMailDetail() {
        val mailId = _internalState.value.openedMailId ?: return
        val preview = _internalState.value.openedPreviewSummary
        openMailInternal(mailId = mailId, previewSummary = preview, forceRefresh = true)
    }

    private fun openMailInternal(
        mailId: Long,
        previewSummary: SeekMailSummary?,
        forceRefresh: Boolean,
    ) {
        detailLoadJob?.cancel()
        attachmentsLoadJob?.cancel()

        _internalState.update {
            it.copy(
                openedMailId = mailId,
                openedPreviewSummary = previewSummary,
                openedDetail = null,
                isLoadingDetail = true,
                detailErrorMessage = null,
                readerMode = SeekMailReaderMode.HTML,
                attachments = emptyList(),
                isLoadingAttachments = previewSummary?.hasAttachments == true,
                attachmentsErrorMessage = null,
                isVpsPasswordRevealed = false,
                isIngestingVpsMail = false,
            )
        }

        detailLoadJob = viewModelScope.launch {
            seekMailRepository.getMailDetail(mailId = mailId, forceRefresh = forceRefresh)
                .onSuccess { detail ->
                    if (_internalState.value.openedMailId != mailId) return@onSuccess
                    runCatching { vpsMailAutoIngestService.processMailDetail(detail) }
                    val preferredMode = if (
                        detail.sanitizedBodyHtml.isBlank() && detail.bodyText.isNotBlank()
                    ) {
                        SeekMailReaderMode.TEXT
                    } else {
                        SeekMailReaderMode.HTML
                    }
                    _internalState.update { current ->
                        val updatedMails = current.mails.map { item ->
                            if (item.id == mailId) detail.summary else item
                        }
                        current.copy(
                            mails = updatedMails,
                            openedPreviewSummary = detail.summary,
                            openedDetail = detail,
                            isLoadingDetail = false,
                            detailErrorMessage = null,
                            readerMode = preferredMode,
                        )
                    }
                    if (detail.summary.hasAttachments || previewSummary?.hasAttachments == true) {
                        loadAttachments(mailId)
                    } else {
                        _internalState.update { it.copy(isLoadingAttachments = false) }
                    }
                }
                .onFailure { err ->
                    if (_internalState.value.openedMailId != mailId) return@onFailure
                    _internalState.update {
                        it.copy(
                            isLoadingDetail = false,
                            isLoadingAttachments = false,
                            detailErrorMessage = mapSeekMailError(err),
                        )
                    }
                }
        }
    }

    private fun loadAttachments(mailId: Long) {
        attachmentsLoadJob?.cancel()
        attachmentsLoadJob = viewModelScope.launch {
            _internalState.update {
                it.copy(isLoadingAttachments = true, attachmentsErrorMessage = null)
            }
            seekMailRepository.getAttachments(mailId)
                .onSuccess { list ->
                    if (_internalState.value.openedMailId != mailId) return@onSuccess
                    _internalState.update {
                        it.copy(
                            attachments = list,
                            isLoadingAttachments = false,
                            attachmentsErrorMessage = null,
                        )
                    }
                }
                .onFailure { err ->
                    if (_internalState.value.openedMailId != mailId) return@onFailure
                    _internalState.update {
                        it.copy(
                            isLoadingAttachments = false,
                            attachmentsErrorMessage = mapSeekMailError(err),
                        )
                    }
                }
        }
    }

    fun retryAttachments() {
        val mailId = _internalState.value.openedMailId ?: return
        loadAttachments(mailId)
    }

    fun selectReaderMode(mode: SeekMailReaderMode) {
        _internalState.update { it.copy(readerMode = mode) }
    }

    fun toggleAllowRemoteImages() {
        val mailId = _internalState.value.openedMailId ?: return
        _internalState.update { current ->
            val next = if (mailId in current.allowedRemoteImageMailIds) {
                current.allowedRemoteImageMailIds - mailId
            } else {
                current.allowedRemoteImageMailIds + mailId
            }
            current.copy(allowedRemoteImageMailIds = next)
        }
    }

    fun closeMailDetail() {
        detailLoadJob?.cancel()
        attachmentsLoadJob?.cancel()
        _internalState.update {
            it.copy(
                openedMailId = null,
                openedPreviewSummary = null,
                openedDetail = null,
                isLoadingDetail = false,
                detailErrorMessage = null,
                attachments = emptyList(),
                isLoadingAttachments = false,
                attachmentsErrorMessage = null,
                isVpsPasswordRevealed = false,
                isIngestingVpsMail = false,
            )
        }
    }

    fun toggleVpsPasswordVisibility() {
        _internalState.update { it.copy(isVpsPasswordRevealed = !it.isVpsPasswordRevealed) }
    }

    fun ingestOpenedVpsMail() {
        val current = _internalState.value
        if (current.isIngestingVpsMail) return

        val detail = current.openedDetail
        val summary = detail?.summary ?: current.openedPreviewSummary
        val parsed = when {
            detail != null -> VpsBillingMailParser.parseDetail(detail)
            summary != null -> VpsBillingMailParser.parse(
                subject = summary.subject,
                bodyText = summary.snippet,
                fromName = summary.fromName,
                fromAddress = summary.fromAddress,
            )
            else -> null
        } ?: return

        viewModelScope.launch {
            _internalState.update { it.copy(isIngestingVpsMail = true) }
            try {
                vpsMailAutoIngestService.ingestMail(summary = summary, parsed = parsed)
                    .onSuccess { outcome ->
                        emitSnackbar(outcome.summaryMessage)
                    }
                    .onFailure { err ->
                        emitSnackbar(
                            "入库失败：${err.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"}",
                        )
                    }
            } finally {
                _internalState.update { it.copy(isIngestingVpsMail = false) }
            }
        }
    }

    suspend fun fetchExportBytes(target: PendingExportTarget): Result<ByteArray> {
        _internalState.update { it.copy(exportingKey = target.exportKey) }
        return try {
            when (target) {
                is PendingExportTarget.Attachment -> {
                    seekMailRepository.downloadAttachmentBytes(
                        mailId = target.mailId,
                        attachmentId = target.attachment.id,
                    )
                }
            }
        } finally {
            _internalState.update { current ->
                if (current.exportingKey == target.exportKey) {
                    current.copy(exportingKey = null)
                } else {
                    current
                }
            }
        }
    }

    private fun sortMailboxes(mailboxes: List<SeekMailbox>): List<SeekMailbox> {
        return mailboxes.sortedWith(
            compareByDescending<SeekMailbox> { it.isPinned }
                .thenBy { it.sortOrder }
                .thenBy { it.id },
        )
    }

    private fun buildAvailableDomains(mailboxes: List<SeekMailbox>): List<String> {
        val fromMailboxes = mailboxes
            .mapNotNull { it.domain.trim().lowercase(Locale.US).takeIf(String::isNotEmpty) }
        return (fromMailboxes + SeekMailUiState.DEFAULT_DOMAINS).distinct()
    }

    private fun mapCreateMailboxError(error: Throwable): String {
        val seekError = error as? SeekMailException ?: return mapSeekMailError(error)
        return when (seekError.kind) {
            SeekMailErrorKind.ADDRESS_UNAVAILABLE ->
                "该邮箱地址已被占用或不可用，请更换前缀或域名"
            SeekMailErrorKind.FORBIDDEN ->
                "权限不足：创建或检测邮箱需要拥有 mailbox:write 权限的 API Key"
            else -> mapSeekMailError(seekError)
        }
    }

    private fun mapSeekMailError(error: Throwable): String {
        val seekError = error as? SeekMailException
            ?: return error.message?.takeIf { it.isNotBlank() } ?: "请求 Seek 邮箱失败，请稍后重试"
        val raw = seekError.message.takeIf { it.isNotBlank() }
        return when (seekError.kind) {
            SeekMailErrorKind.MISSING_API_KEY -> raw ?: "请先配置 Seek 邮箱 API Key"
            SeekMailErrorKind.INVALID_API_KEY_FORMAT ->
                raw ?: "API Key 格式无效，请粘贴完整的 sk_live_... 密钥"
            SeekMailErrorKind.UNAUTHORIZED ->
                raw ?: "API Key 认证失败或已失效，请检查密钥"
            SeekMailErrorKind.FORBIDDEN ->
                raw ?: "当前 API Key 无权限执行此操作"
            SeekMailErrorKind.NOT_FOUND ->
                raw ?: "请求的邮箱、邮件或附件不存在"
            SeekMailErrorKind.ADDRESS_UNAVAILABLE ->
                raw ?: "该邮箱地址已被占用"
            SeekMailErrorKind.RATE_LIMITED ->
                raw ?: "请求过于频繁（限 60 次/分钟），请稍后再试"
            SeekMailErrorKind.NETWORK ->
                raw ?: "网络连接异常，请检查网络后重试"
            SeekMailErrorKind.SERVER ->
                raw ?: "Seek 邮箱服务端暂时响应异常，请稍后再试"
            SeekMailErrorKind.UNKNOWN ->
                raw ?: "操作失败，请稍后重试"
        }
    }

    companion object {
        private const val MAIL_PAGE_SIZE = 20
        private const val UNREAD_PAGE_SIZE = 50
        private const val CONTACTS_LIMIT = 50

        private val LOCAL_PART_REGEX = Regex("""^[a-z0-9](?:[a-z0-9._+-]*[a-z0-9])?$""")
        private val DOMAIN_REGEX = Regex("""^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$""")

        fun factory(
            container: AppContainer,
            initialMailboxId: Long = 0L,
            initialMailId: Long = 0L,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SeekMailViewModel(
                    seekMailRepository = container.seekMailRepository,
                    settingsRepository = container.settingsRepository,
                    vpsMailAutoIngestService = container.vpsMailAutoIngestService,
                    initialMailboxId = initialMailboxId,
                    initialMailId = initialMailId,
                ) as T
            }
        }
    }
}

private fun maskApiKeyHint(apiKey: String): String {
    if (apiKey.isBlank()) return ""
    if (apiKey.length <= 12) return "已配置"
    val prefix = apiKey.take(8)
    val suffix = apiKey.takeLast(4)
    return "$prefix••••$suffix"
}

private fun matchesSearchNeedle(mail: SeekMailSummary, lowerNeedle: String): Boolean {
    return mail.subject.lowercase(Locale.US).contains(lowerNeedle) ||
        mail.fromAddress.lowercase(Locale.US).contains(lowerNeedle) ||
        mail.fromName.lowercase(Locale.US).contains(lowerNeedle) ||
        mail.toAddress.lowercase(Locale.US).contains(lowerNeedle) ||
        mail.snippet.lowercase(Locale.US).contains(lowerNeedle)
}

private fun sanitizeExportFileName(raw: String): String {
    return raw
        .replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_")
        .trim()
        .ifBlank { "seek_mail_export" }
}
