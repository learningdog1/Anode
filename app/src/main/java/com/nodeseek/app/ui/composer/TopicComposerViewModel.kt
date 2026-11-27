package com.nodeseek.app.ui.composer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import com.nodeseek.app.domain.activity.LuckyDrawSpec
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.formatVoteWidgetMarkdown
import com.nodeseek.app.domain.repository.PollAndStardustRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class ComposerModeTab(val label: String) {
    WRITE("编辑"),
    PREVIEW("预览"),
}

@Immutable
data class ViewPermissionOption(
    val requiredLevel: Int,
    val shortLabel: String,
    val fullTitle: String,
    val subtitle: String,
) {
    companion object {
        val ALL_OPTIONS: List<ViewPermissionOption> = listOf(
            ViewPermissionOption(0, "公开 Lv.0", "公开可见", "所有访客与注册用户均可阅读"),
            ViewPermissionOption(1, "Lv.1+", "阅读权限 Lv.1 及以上", "需持有 ≥ 100 鸡腿方可阅读"),
            ViewPermissionOption(2, "Lv.2+", "阅读权限 Lv.2 及以上", "需持有 ≥ 400 鸡腿方可阅读"),
            ViewPermissionOption(3, "Lv.3+", "阅读权限 Lv.3 及以上", "需持有 ≥ 900 鸡腿方可阅读"),
            ViewPermissionOption(4, "Lv.4+", "阅读权限 Lv.4 及以上", "需持有 ≥ 1,600 鸡腿方可阅读"),
            ViewPermissionOption(5, "Lv.5+", "阅读权限 Lv.5 及以上", "需持有 ≥ 2,500 鸡腿方可阅读"),
            ViewPermissionOption(6, "Lv.6+", "阅读权限 Lv.6", "需持有 ≥ 3,600 鸡腿方可阅读"),
            ViewPermissionOption(255, "私有帖 Lv.255", "私有帖 (仅自己可见)", "仅作者本人可见（编辑已有帖改私有消耗 10 鸡腿）"),
        )

        fun forLevel(level: Int): ViewPermissionOption =
            ALL_OPTIONS.firstOrNull { it.requiredLevel == level }
                ?: ALL_OPTIONS.first()
    }
}

@Immutable
sealed interface PendingComposerCoinConfirm {
    val coinCost: Int

    data object NewInsideBoardTopic : PendingComposerCoinConfirm {
        override val coinCost: Int = 5
    }

    data object EditChangeToPrivate : PendingComposerCoinConfirm {
        override val coinCost: Int = 10
    }
}

sealed interface TopicComposerUiEvent {
    data class ShowMessage(val message: String) : TopicComposerUiEvent
    data class PublishedSuccess(
        val editedTopicId: Long?,
        val createdTopicId: Long? = null,
    ) : TopicComposerUiEvent
    data class RequestWebViewAuth(val url: String) : TopicComposerUiEvent
    data class InsertUploadedMarkdown(val snippet: String) : TopicComposerUiEvent
}

@Immutable
data class TopicComposerUiState(
    val editTopicId: Long? = null,
    val session: AccountSession? = null,
    val settings: AppSettings = AppSettings(),
    val isLoadingInitialTopic: Boolean = false,
    val loadError: String? = null,
    val availableBoards: List<Category> = emptyList(),
    val selectedCategorySlug: String = "daily",
    val selectedCategory: Category? = null,
    val requiredViewLevel: Int = 0,
    val originalRequiredViewLevel: Int = 0,
    val title: String = "",
    val markdownContent: String = "",
    val editorRevision: Int = 0,
    val activeTab: ComposerModeTab = ComposerModeTab.WRITE,
    val hasRestoredDraft: Boolean = false,
    val canResetToOriginalFloor: Boolean = false,
    val isSubmitting: Boolean = false,
    val hasPublished: Boolean = false,
    val isUploadingImage: Boolean = false,
    val showBoardSheet: Boolean = false,
    val showPermissionSheet: Boolean = false,
    val showStickerSheet: Boolean = false,
    val showPollSheet: Boolean = false,
    val isCreatingPoll: Boolean = false,
    val pollCreateError: String? = null,
    val pendingLuckyDrawSpec: LuckyDrawSpec? = null,
    val showApiKeyDialog: Boolean = false,
    val pendingPrivacyImage: PendingComposerImage? = null,
    val pendingCoinConfirm: PendingComposerCoinConfirm? = null,
) {
    val showPrivacyEditorSheet: Boolean
        get() = pendingPrivacyImage != null
    val isEditMode: Boolean
        get() = editTopicId != null

    val isInsideBoardSelected: Boolean
        get() = selectedCategorySlug.equals("inside", ignoreCase = true)

    val willIncurPrivateUpgradeFee: Boolean
        get() = isEditMode && originalRequiredViewLevel != 255 && requiredViewLevel == 255

    val canSubmit: Boolean
        get() = !isLoadingInitialTopic &&
            !isSubmitting &&
            !hasPublished &&
            title.trim().isNotEmpty() &&
            markdownContent.trim().isNotEmpty()
}

private data class InternalComposerState(
    val isLoadingInitialTopic: Boolean = false,
    val loadError: String? = null,
    val selectedCategorySlug: String = "daily",
    val requiredViewLevel: Int = 0,
    val originalTitle: String = "",
    val originalMarkdown: String = "",
    val originalRequiredViewLevel: Int = 0,
    val title: String = "",
    val markdownContent: String = "",
    val editorRevision: Int = 0,
    val activeTab: ComposerModeTab = ComposerModeTab.WRITE,
    val hasRestoredDraft: Boolean = false,
    val isSubmitting: Boolean = false,
    val hasPublished: Boolean = false,
    val isUploadingImage: Boolean = false,
    val showBoardSheet: Boolean = false,
    val showPermissionSheet: Boolean = false,
    val showStickerSheet: Boolean = false,
    val showPollSheet: Boolean = false,
    val isCreatingPoll: Boolean = false,
    val pollCreateError: String? = null,
    val pendingLuckyDrawSpec: LuckyDrawSpec? = null,
    val showApiKeyDialog: Boolean = false,
    val pendingPrivacyImage: PendingComposerImage? = null,
    val pendingCoinConfirm: PendingComposerCoinConfirm? = null,
) {
    fun isInsideBoardSelected(): Boolean =
        selectedCategorySlug.equals("inside", ignoreCase = true)

    fun willIncurPrivateUpgradeFee(isEditMode: Boolean): Boolean =
        isEditMode && originalRequiredViewLevel != 255 && requiredViewLevel == 255
}

@Immutable
data class PendingComposerImage(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String? = null,
)

class TopicComposerViewModel(
    private val container: AppContainer,
    val editTopicId: Long? = null,
    private val initialCategorySlug: String = "daily",
    private val initialTitle: String = "",
    private val initialMarkdown: String = "",
    private val prefillKey: String = "",
    private val pollAndStardustRepositoryOverride: PollAndStardustRepository? = null,
) : ViewModel() {

    private val sanitizedInitialCategory = initialCategorySlug
        .trim()
        .lowercase()
        .takeIf { it.isNotEmpty() }
        ?: "daily"

    private val sanitizedPrefillKey = prefillKey.trim()

    private val hasExplicitPrefill = editTopicId == null &&
        (initialTitle.isNotBlank() || initialMarkdown.isNotBlank())

    private val internalState = MutableStateFlow(
        InternalComposerState(
            isLoadingInitialTopic = editTopicId != null,
            selectedCategorySlug = sanitizedInitialCategory,
            title = if (hasExplicitPrefill) initialTitle else "",
            markdownContent = if (hasExplicitPrefill) initialMarkdown else "",
        )
    )

    private val _events = Channel<TopicComposerUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var draftSaveJob: Job? = null
    @Volatile
    private var hasPendingDraftFlush: Boolean = false
    private var pendingImage: PendingComposerImage? = null
    private val isSubmittingGuard = AtomicBoolean(false)
    private val isCreatingPollGuard = AtomicBoolean(false)
    private val cachedCategories = MutableStateFlow(
        (container.forumRepository.observeCategories() as? StateFlow<List<Category>>)?.value.orEmpty()
    )

    private fun currentRawCategories(): List<Category> =
        cachedCategories.value.ifEmpty { Category.DEFAULT_CATEGORIES }

    private fun isAdminOnlyCategorySlug(slug: String): Boolean {
        val normalized = slug.trim()
        if (normalized.isEmpty()) return false
        return currentRawCategories().firstOrNull {
            it.slug.equals(normalized, ignoreCase = true)
        }?.isAdminOnly == true
    }

    val uiState: StateFlow<TopicComposerUiState> = combine(
        internalState,
        container.sessionRepository.sessionState,
        container.settingsRepository.settings,
        cachedCategories,
    ) { state, session, settings, observedCategories ->
        val rawCats = observedCategories.ifEmpty { Category.DEFAULT_CATEGORIES }
        // Exclude synthetic "all" ("") and exclude adminOnly categories from New Topic picker
        val selectableBoards = rawCats.filter { cat ->
            cat.slug.isNotBlank() && !cat.isAdminOnly
        }
        val activeCategory = if (editTopicId != null) {
            rawCats.firstOrNull {
                it.slug.equals(state.selectedCategorySlug, ignoreCase = true)
            }
        } else {
            selectableBoards.firstOrNull {
                it.slug.equals(state.selectedCategorySlug, ignoreCase = true)
            }
        } ?: selectableBoards.firstOrNull { it.slug == "daily" } ?: selectableBoards.firstOrNull()

        val effectiveSlug = if (editTopicId != null) {
            state.selectedCategorySlug
        } else {
            activeCategory?.slug ?: state.selectedCategorySlug
        }

        val canResetOriginal = editTopicId != null &&
            (state.title != state.originalTitle || state.markdownContent != state.originalMarkdown) &&
            (state.originalTitle.isNotEmpty() || state.originalMarkdown.isNotEmpty())

        TopicComposerUiState(
            editTopicId = editTopicId,
            session = session,
            settings = settings,
            isLoadingInitialTopic = state.isLoadingInitialTopic,
            loadError = state.loadError,
            availableBoards = selectableBoards,
            selectedCategorySlug = effectiveSlug,
            selectedCategory = activeCategory,
            requiredViewLevel = state.requiredViewLevel,
            originalRequiredViewLevel = state.originalRequiredViewLevel,
            title = state.title,
            markdownContent = state.markdownContent,
            editorRevision = state.editorRevision,
            activeTab = state.activeTab,
            hasRestoredDraft = state.hasRestoredDraft,
            canResetToOriginalFloor = canResetOriginal,
            isSubmitting = state.isSubmitting,
            hasPublished = state.hasPublished,
            isUploadingImage = state.isUploadingImage,
            showBoardSheet = state.showBoardSheet,
            showPermissionSheet = state.showPermissionSheet,
            showStickerSheet = state.showStickerSheet,
            showPollSheet = state.showPollSheet,
            isCreatingPoll = state.isCreatingPoll,
            pollCreateError = state.pollCreateError,
            pendingLuckyDrawSpec = state.pendingLuckyDrawSpec,
            showApiKeyDialog = state.showApiKeyDialog,
            pendingPrivacyImage = state.pendingPrivacyImage,
            pendingCoinConfirm = state.pendingCoinConfirm,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TopicComposerUiState(
            editTopicId = editTopicId,
            isLoadingInitialTopic = editTopicId != null,
            selectedCategorySlug = sanitizedInitialCategory,
            title = if (hasExplicitPrefill) initialTitle else "",
            markdownContent = if (hasExplicitPrefill) initialMarkdown else "",
        ),
    )

    init {
        viewModelScope.launch {
            container.forumRepository.observeCategories().collect { categories ->
                cachedCategories.value = categories
            }
        }
        viewModelScope.launch {
            runCatching { container.forumRepository.refreshCategories(force = false) }
        }
        if (editTopicId != null) {
            loadTopicForEditing(editTopicId)
        } else if (hasExplicitPrefill) {
            viewModelScope.launch {
                persistCurrentDraft(internalState.value)
            }
        } else if (sanitizedPrefillKey.isNotBlank()) {
            restoreDraftForKey(draftKey())
        } else {
            restoreNewTopicDraft()
        }
    }

    private fun draftKey(): String = when {
        editTopicId != null -> EditorDraft.editTopicKey(editTopicId)
        sanitizedPrefillKey.isNotBlank() -> "composer_prefill_$sanitizedPrefillKey"
        else -> EditorDraft.NEW_TOPIC_KEY
    }

    private fun restoreNewTopicDraft() {
        restoreDraftForKey(EditorDraft.NEW_TOPIC_KEY)
    }

    private fun restoreDraftForKey(key: String) {
        viewModelScope.launch {
            val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
            val draft = container.draftRepository.getDraft(uid, key) ?: return@launch
            if (draft.title.isBlank() && draft.markdownContent.isBlank()) return@launch

            val useDraftBoard = initialCategorySlug.isBlank() || initialCategorySlug == "daily"
            val restoredBoard = if (useDraftBoard && draft.categorySlug.isNotBlank()) {
                draft.categorySlug
            } else {
                sanitizedInitialCategory
            }

            internalState.update { state ->
                state.copy(
                    title = draft.title,
                    markdownContent = draft.markdownContent,
                    selectedCategorySlug = restoredBoard,
                    hasRestoredDraft = true,
                    editorRevision = state.editorRevision + 1,
                )
            }
        }
    }

    fun loadTopicForEditing(targetTopicId: Long? = editTopicId) {
        val resolvedTopicId = targetTopicId ?: return
        viewModelScope.launch {
            internalState.update {
                it.copy(isLoadingInitialTopic = true, loadError = null)
            }

            val result = container.forumRepository.getTopicDetail(topicId = resolvedTopicId, page = 1)
            result.fold(
                onSuccess = { detail ->
                    val floorZeroBody = detail.openingFloor?.markdownBody.orEmpty()
                    val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
                    val localDraft = container.draftRepository.getDraft(
                        uid,
                        EditorDraft.editTopicKey(resolvedTopicId),
                    )

                    val hasLocalOverride = localDraft != null && (
                        localDraft.title.isNotBlank() && localDraft.title != detail.title ||
                            localDraft.markdownContent.isNotBlank() && localDraft.markdownContent != floorZeroBody
                        )

                    val activeTitle = if (hasLocalOverride && localDraft!!.title.isNotBlank()) {
                        localDraft.title
                    } else {
                        detail.title
                    }
                    val activeMarkdown = if (hasLocalOverride && localDraft!!.markdownContent.isNotBlank()) {
                        localDraft.markdownContent
                    } else {
                        floorZeroBody
                    }

                    internalState.update { state ->
                        state.copy(
                            isLoadingInitialTopic = false,
                            loadError = null,
                            selectedCategorySlug = detail.categorySlug.ifBlank { sanitizedInitialCategory },
                            requiredViewLevel = detail.requiredViewLevel,
                            originalRequiredViewLevel = detail.requiredViewLevel,
                            originalTitle = detail.title,
                            originalMarkdown = floorZeroBody,
                            title = activeTitle,
                            markdownContent = activeMarkdown,
                            hasRestoredDraft = hasLocalOverride,
                            editorRevision = state.editorRevision + 1,
                        )
                    }
                },
                onFailure = { error ->
                    val errMsg = error.message?.takeIf { it.isNotBlank() } ?: "加载原帖内容失败，请检查网络或登录状态"
                    internalState.update {
                        it.copy(
                            isLoadingInitialTopic = false,
                            loadError = errMsg,
                        )
                    }
                    maybeEmitAuthRequest(error)
                },
            )
        }
    }

    fun onWebViewAuthCompleted() {
        viewModelScope.launch {
            container.sessionRepository.onWebViewAuthCompleted()
            if (editTopicId != null && internalState.value.loadError != null) {
                loadTopicForEditing(editTopicId)
            }
        }
    }

    fun onTitleChanged(newTitle: String) {
        internalState.update { it.copy(title = newTitle) }
        scheduleAutoSaveDraft()
    }

    fun onMarkdownChanged(newMarkdown: String) {
        internalState.update { it.copy(markdownContent = newMarkdown) }
        scheduleAutoSaveDraft()
    }

    fun selectCategory(slug: String) {
        // Protocol rule (#8): editTopic does NOT change category
        if (editTopicId != null) return
        val normalized = slug.trim().lowercase()
        if (normalized.isEmpty()) return

        if (isAdminOnlyCategorySlug(normalized)) return

        internalState.update {
            it.copy(
                selectedCategorySlug = normalized,
                showBoardSheet = false,
            )
        }
        scheduleAutoSaveDraft()
    }

    fun selectRequiredViewLevel(level: Int) {
        internalState.update {
            it.copy(
                requiredViewLevel = level,
                showPermissionSheet = false,
            )
        }
    }

    fun setActiveTab(tab: ComposerModeTab) {
        internalState.update {
            it.copy(
                activeTab = tab,
                showStickerSheet = if (tab == ComposerModeTab.PREVIEW) false else it.showStickerSheet,
            )
        }
    }

    fun setBoardSheetVisible(visible: Boolean) {
        if (editTopicId != null && visible) {
            _events.trySend(TopicComposerUiEvent.ShowMessage("NodeSeek 编辑已有主题不支持修改所属版块"))
            return
        }
        internalState.update { it.copy(showBoardSheet = visible) }
    }

    fun setPermissionSheetVisible(visible: Boolean) {
        internalState.update { it.copy(showPermissionSheet = visible) }
    }

    fun setStickerSheetVisible(visible: Boolean) {
        internalState.update { it.copy(showStickerSheet = visible) }
    }

    fun clearDraftOrReset() {
        draftSaveJob?.cancel()
        hasPendingDraftFlush = false
        viewModelScope.launch {
            val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
            runCatching { container.draftRepository.deleteDraft(uid, draftKey()) }

            internalState.update { state ->
                if (editTopicId != null) {
                    state.copy(
                        title = state.originalTitle,
                        markdownContent = state.originalMarkdown,
                        requiredViewLevel = state.originalRequiredViewLevel,
                        hasRestoredDraft = false,
                        editorRevision = state.editorRevision + 1,
                    )
                } else {
                    state.copy(
                        title = "",
                        markdownContent = "",
                        hasRestoredDraft = false,
                        editorRevision = state.editorRevision + 1,
                    )
                }
            }
            _events.trySend(
                TopicComposerUiEvent.ShowMessage(
                    if (editTopicId != null) "已还原为原帖内容" else "本地草稿已清空"
                )
            )
        }
    }

    fun flushDraftNow() {
        val state = internalState.value
        if (state.isLoadingInitialTopic || state.hasPublished || !hasPendingDraftFlush) return
        draftSaveJob?.cancel()
        hasPendingDraftFlush = false
        nonCancellableDraftScope.launch {
            runCatching { persistCurrentDraft(state) }
        }
    }

    override fun onCleared() {
        flushDraftNow()
        super.onCleared()
    }

    private fun scheduleAutoSaveDraft() {
        if (internalState.value.isLoadingInitialTopic || internalState.value.hasPublished) return
        hasPendingDraftFlush = true
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(450)
            val state = internalState.value
            if (state.hasPublished) return@launch
            hasPendingDraftFlush = false
            persistCurrentDraft(state)
        }
    }

    private suspend fun persistCurrentDraft(state: InternalComposerState) {
        val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
        val key = draftKey()

        if (state.title.isBlank() && state.markdownContent.isBlank()) {
            container.draftRepository.deleteDraft(uid, key)
            return
        }
        if (editTopicId != null &&
            state.title == state.originalTitle &&
            state.markdownContent == state.originalMarkdown
        ) {
            container.draftRepository.deleteDraft(uid, key)
            return
        }

        container.draftRepository.saveDraft(
            EditorDraft(
                draftKey = key,
                accountUid = uid,
                title = state.title,
                markdownContent = state.markdownContent,
                categorySlug = state.selectedCategorySlug,
                updatedAtMillis = System.currentTimeMillis(),
            )
        )
    }

    // ── Publish & Edit Submission (with Coin Confirmations) ───────────────────

    fun onPublishClicked() {
        val currentSession = container.sessionRepository.sessionState.value
        if (currentSession == null) {
            _events.trySend(TopicComposerUiEvent.ShowMessage("发帖前请先登录 NodeSeek 账号"))
            _events.trySend(TopicComposerUiEvent.RequestWebViewAuth("https://www.nodeseek.com/signIn.html"))
            return
        }

        val state = internalState.value
        if (state.isSubmitting || state.hasPublished || isSubmittingGuard.get()) return
        if (state.title.trim().isEmpty()) {
            _events.trySend(TopicComposerUiEvent.ShowMessage("请输入帖子标题"))
            return
        }
        if (state.markdownContent.trim().isEmpty()) {
            _events.trySend(TopicComposerUiEvent.ShowMessage("请输入帖子正文内容"))
            return
        }

        // Check coin fees before executing network write:
        // 1. Editing an existing non-private topic into Private (`requiredViewLevel = 255`) costs 10 coins.
        val isEditMode = editTopicId != null
        if (!isEditMode && isAdminOnlyCategorySlug(state.selectedCategorySlug)) {
            _events.trySend(TopicComposerUiEvent.ShowMessage("该版块仅限管理员发帖"))
            return
        }
        if (state.willIncurPrivateUpgradeFee(isEditMode)) {
            internalState.update {
                it.copy(pendingCoinConfirm = PendingComposerCoinConfirm.EditChangeToPrivate)
            }
            return
        }

        // 2. Creating a new topic in `inside` (`内版`) costs 5 coins.
        if (!isEditMode && state.isInsideBoardSelected()) {
            internalState.update {
                it.copy(pendingCoinConfirm = PendingComposerCoinConfirm.NewInsideBoardTopic)
            }
            return
        }

        executePublishOrEdit()
    }

    fun dismissCoinConfirm() {
        internalState.update { it.copy(pendingCoinConfirm = null) }
    }

    fun confirmCoinSpendAndPublish() {
        val state = internalState.value
        if (state.isSubmitting || state.hasPublished || isSubmittingGuard.get()) return
        internalState.update { it.copy(pendingCoinConfirm = null) }
        executePublishOrEdit()
    }

    private fun executePublishOrEdit() {
        val precheck = internalState.value
        if (precheck.isSubmitting || precheck.hasPublished) return
        if (!isSubmittingGuard.compareAndSet(false, true)) return

        var launched = false
        try {
            val state = internalState.value
            if (state.isSubmitting || state.hasPublished) return
            val trimmedTitle = state.title.trim()
            val trimmedMarkdown = state.markdownContent.trim()
            if (trimmedTitle.isEmpty() || trimmedMarkdown.isEmpty()) return
            if (editTopicId == null && isAdminOnlyCategorySlug(state.selectedCategorySlug)) {
                _events.trySend(TopicComposerUiEvent.ShowMessage("该版块仅限管理员发帖"))
                return
            }

            draftSaveJob?.cancel()
            hasPendingDraftFlush = false
            internalState.update { it.copy(isSubmitting = true) }

            viewModelScope.launch {
                try {
                    if (editTopicId != null) {
                        // Protocol rule (#8): editTopic does NOT send/change category!
                        val result = container.forumRepository.editTopic(
                            topicId = editTopicId,
                            title = trimmedTitle,
                            markdownContent = trimmedMarkdown,
                            requiredViewLevel = state.requiredViewLevel,
                        )
                        result.fold(
                            onSuccess = {
                                val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
                                runCatching { container.draftRepository.deleteDraft(uid, draftKey()) }

                                internalState.update {
                                    it.copy(
                                        isSubmitting = false,
                                        hasPublished = true,
                                        hasRestoredDraft = false,
                                    )
                                }
                                _events.trySend(TopicComposerUiEvent.ShowMessage("主题修改已保存"))
                                _events.trySend(TopicComposerUiEvent.PublishedSuccess(editTopicId))
                            },
                            onFailure = { error ->
                                internalState.update { it.copy(isSubmitting = false) }
                                if (!maybeEmitAuthRequest(error)) {
                                    _events.trySend(
                                        TopicComposerUiEvent.ShowMessage(
                                            error.message?.takeIf { it.isNotBlank() } ?: "保存主题失败"
                                        )
                                    )
                                }
                            },
                        )
                    } else {
                        val result = container.forumRepository.createTopicWithResult(
                            categorySlug = state.selectedCategorySlug,
                            title = trimmedTitle,
                            markdownContent = trimmedMarkdown,
                            requiredViewLevel = state.requiredViewLevel,
                        )
                        result.fold(
                            onSuccess = { createdTopicId ->
                                val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
                                runCatching { container.draftRepository.deleteDraft(uid, draftKey()) }

                                val pendingLucky = state.pendingLuckyDrawSpec
                                var luckyAppended = false
                                if (pendingLucky != null && createdTopicId != null && createdTopicId > 0L) {
                                    val finalSpec = pendingLucky.copy(postId = createdTopicId)
                                    val luckyUrl = DrandLuckyEngine.buildLuckyUrl(finalSpec)
                                    val updatedMd = "$trimmedMarkdown\n\n[🎲 参与官方抽奖]($luckyUrl)"
                                    luckyAppended = container.forumRepository.editTopic(
                                        topicId = createdTopicId,
                                        title = trimmedTitle,
                                        markdownContent = updatedMd,
                                        requiredViewLevel = state.requiredViewLevel,
                                    ).isSuccess
                                }

                                internalState.update {
                                    it.copy(
                                        isSubmitting = false,
                                        hasPublished = true,
                                        hasRestoredDraft = false,
                                    )
                                }
                                val successMsg = when {
                                    pendingLucky != null && luckyAppended -> "新主题已发布，已自动附带 /lucky 抽奖链接"
                                    pendingLucky != null -> "新主题已发布，可在帖子右上角「生成 / 管理抽奖」补全链接"
                                    else -> "新主题已成功发布"
                                }
                                _events.trySend(TopicComposerUiEvent.ShowMessage(successMsg))
                                _events.trySend(
                                    TopicComposerUiEvent.PublishedSuccess(
                                        editedTopicId = null,
                                        createdTopicId = createdTopicId,
                                    ),
                                )
                            },
                            onFailure = { error ->
                                internalState.update { it.copy(isSubmitting = false) }
                                if (!maybeEmitAuthRequest(error)) {
                                    _events.trySend(
                                        TopicComposerUiEvent.ShowMessage(
                                            error.message?.takeIf { it.isNotBlank() } ?: "发布主题失败"
                                        )
                                    )
                                }
                            },
                        )
                    }
                } finally {
                    isSubmittingGuard.set(false)
                    internalState.update { cur ->
                        if (cur.isSubmitting) cur.copy(isSubmitting = false) else cur
                    }
                }
            }
            launched = true
        } finally {
            if (!launched) {
                isSubmittingGuard.set(false)
                internalState.update { cur ->
                    if (cur.isSubmitting) cur.copy(isSubmitting = false) else cur
                }
            }
        }
    }

    // ── Poll & Stardust Widget Creation ───────────────────────────────────────

    fun setPollSheetVisible(visible: Boolean) {
        internalState.update {
            it.copy(
                showPollSheet = visible,
                pollCreateError = if (!visible) null else it.pollCreateError,
            )
        }
    }

    fun setPendingLuckyDrawSpec(spec: LuckyDrawSpec?) {
        internalState.update { it.copy(pendingLuckyDrawSpec = spec) }
        if (spec != null) {
            _events.trySend(
                TopicComposerUiEvent.ShowMessage("已保存抽奖配置，发帖后将自动生成 /lucky 链接"),
            )
        }
    }

    fun createPollAndInsert(
        title: String,
        multiple: Boolean,
        isPublic: Boolean,
        options: List<String>,
    ) {
        val session = container.sessionRepository.sessionState.value
        if (session == null) {
            _events.trySend(TopicComposerUiEvent.ShowMessage("请先登录 NodeSeek 账号"))
            _events.trySend(TopicComposerUiEvent.RequestWebViewAuth("https://www.nodeseek.com/signIn.html"))
            return
        }
        if (internalState.value.isCreatingPoll || !isCreatingPollGuard.compareAndSet(false, true)) return

        var launched = false
        try {
            internalState.update { it.copy(isCreatingPoll = true, pollCreateError = null) }
            viewModelScope.launch {
                try {
                    val repo = pollAndStardustRepositoryOverride ?: container.pollAndStardustRepository
                    repo.createVote(
                        title = title,
                        multiple = multiple,
                        isPublic = isPublic,
                        options = options,
                    ).fold(
                        onSuccess = { createdPoll ->
                            val snippet = formatVoteWidgetMarkdown(
                                voteId = createdPoll.id,
                                title = createdPoll.title.ifBlank { title },
                            )
                            internalState.update {
                                it.copy(
                                    isCreatingPoll = false,
                                    showPollSheet = false,
                                    pollCreateError = null,
                                )
                            }
                            _events.trySend(TopicComposerUiEvent.InsertUploadedMarkdown("\n$snippet\n"))
                            _events.trySend(TopicComposerUiEvent.ShowMessage("投票已创建并插入正文"))
                        },
                        onFailure = { err ->
                            internalState.update {
                                it.copy(
                                    isCreatingPoll = false,
                                    pollCreateError = err.message?.takeIf { m -> m.isNotBlank() } ?: "创建投票失败",
                                )
                            }
                            maybeEmitAuthRequest(err)
                        },
                    )
                } finally {
                    isCreatingPollGuard.set(false)
                    internalState.update { cur ->
                        if (cur.isCreatingPoll) cur.copy(isCreatingPoll = false) else cur
                    }
                }
            }
            launched = true
        } finally {
            if (!launched) {
                isCreatingPollGuard.set(false)
                internalState.update { cur ->
                    if (cur.isCreatingPoll) cur.copy(isCreatingPoll = false) else cur
                }
            }
        }
    }

    // ── NodeImage Photo Upload ────────────────────────────────────────────────

    fun onPhotoBytesSelected(
        bytes: ByteArray,
        mimeType: String,
        fileName: String? = null,
    ) {
        val key = container.settingsRepository.settings.value.nodeImageApiKey.trim()
        if (key.isEmpty()) {
            pendingImage = PendingComposerImage(
                bytes = bytes,
                mimeType = mimeType,
                fileName = fileName,
            )
            internalState.update { it.copy(showApiKeyDialog = true) }
            return
        }
        routeImageThroughPrivacyEditor(bytes = bytes, mimeType = mimeType, fileName = fileName)
    }

    fun reportPhotoReadError(message: String) {
        _events.trySend(TopicComposerUiEvent.ShowMessage(message))
    }

    fun openApiKeyDialog() {
        internalState.update { it.copy(showApiKeyDialog = true) }
    }

    fun dismissApiKeyDialog() {
        pendingImage = null
        internalState.update { it.copy(showApiKeyDialog = false) }
    }

    fun saveApiKeyAndUpload(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            _events.trySend(TopicComposerUiEvent.ShowMessage("请输入有效的 NodeImage API Key"))
            return
        }
        viewModelScope.launch {
            container.settingsRepository.setNodeImageApiKey(trimmed)
            internalState.update { it.copy(showApiKeyDialog = false) }
            val queued = pendingImage
            pendingImage = null
            if (queued != null) {
                routeImageThroughPrivacyEditor(
                    bytes = queued.bytes,
                    mimeType = queued.mimeType,
                    fileName = queued.fileName,
                )
            }
        }
    }

    private fun routeImageThroughPrivacyEditor(
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
    ) {
        when (val support = ImagePrivacyBitmapCodec.inspectMosaicSupport(bytes, mimeType, fileName)) {
            is MosaicSupportStatus.SupportedStatic -> {
                internalState.update {
                    it.copy(
                        pendingPrivacyImage = PendingComposerImage(
                            bytes = bytes,
                            mimeType = mimeType,
                            fileName = fileName,
                        ),
                    )
                }
            }
            is MosaicSupportStatus.UnsupportedAnimatedOrVector -> {
                _events.trySend(TopicComposerUiEvent.ShowMessage(support.reason))
                uploadSelectedImage(bytes = bytes, mimeType = mimeType, fileName = fileName)
            }
            is MosaicSupportStatus.Unreadable -> {
                _events.trySend(TopicComposerUiEvent.ShowMessage(support.reason))
                uploadSelectedImage(bytes = bytes, mimeType = mimeType, fileName = fileName)
            }
        }
    }

    fun confirmPrivacyImageUpload(
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
    ) {
        internalState.update { it.copy(pendingPrivacyImage = null) }
        uploadSelectedImage(bytes = bytes, mimeType = mimeType, fileName = fileName)
    }

    fun skipPrivacyEditAndUpload() {
        val pending = internalState.value.pendingPrivacyImage ?: return
        internalState.update { it.copy(pendingPrivacyImage = null) }
        uploadSelectedImage(
            bytes = pending.bytes,
            mimeType = pending.mimeType,
            fileName = pending.fileName,
        )
    }

    fun dismissPrivacyEditor() {
        internalState.update { it.copy(pendingPrivacyImage = null) }
    }

    private fun uploadSelectedImage(
        bytes: ByteArray,
        mimeType: String,
        fileName: String? = null,
    ) {
        viewModelScope.launch {
            internalState.update { it.copy(isUploadingImage = true) }
            val result = container.imageHostRepository.uploadImage(
                bytes = bytes,
                mimeType = mimeType,
                fileName = fileName,
            )
            internalState.update { it.copy(isUploadingImage = false) }

            result.fold(
                onSuccess = { url ->
                    _events.trySend(TopicComposerUiEvent.InsertUploadedMarkdown("\n![image]($url)\n"))
                    _events.trySend(TopicComposerUiEvent.ShowMessage("图片已上传并插入正文"))
                },
                onFailure = { err ->
                    _events.trySend(
                        TopicComposerUiEvent.ShowMessage(
                            err.message ?: "图片上传失败，请检查 NodeImage API Key"
                        )
                    )
                },
            )
        }
    }

    private fun maybeEmitAuthRequest(error: Throwable): Boolean {
        val chain = listOfNotNull(error, error.cause)
        for (t in chain) {
            if (t is TopicAccessException.SignInRequired ||
                t::class.simpleName.orEmpty().contains("SignInRequired", ignoreCase = true)
            ) {
                _events.trySend(TopicComposerUiEvent.ShowMessage("登录状态已过期，请重新登录"))
                _events.trySend(TopicComposerUiEvent.RequestWebViewAuth("https://www.nodeseek.com/signIn.html"))
                return true
            }
            if (t is TopicAccessException.CloudflareChallengeRequired ||
                t::class.simpleName.orEmpty().contains("CloudflareChallenge", ignoreCase = true)
            ) {
                _events.trySend(TopicComposerUiEvent.ShowMessage("需要完成浏览器安全验证"))
                _events.trySend(TopicComposerUiEvent.RequestWebViewAuth("https://www.nodeseek.com/"))
                return true
            }
        }
        return false
    }

    companion object {
        private val nonCancellableDraftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun factory(
            container: AppContainer,
            editTopicId: Long? = null,
            initialCategorySlug: String = "daily",
            initialTitle: String = "",
            initialMarkdown: String = "",
            prefillKey: String = "",
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TopicComposerViewModel(
                    container = container,
                    editTopicId = editTopicId,
                    initialCategorySlug = initialCategorySlug,
                    initialTitle = initialTitle,
                    initialMarkdown = initialMarkdown,
                    prefillKey = prefillKey,
                    pollAndStardustRepositoryOverride = container.pollAndStardustRepository,
                ) as T
            }
        }
    }
}
