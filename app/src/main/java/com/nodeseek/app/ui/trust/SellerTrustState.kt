package com.nodeseek.app.ui.trust

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.trust.SellerTrustRepositoryImpl
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.SellerTrustReport
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.trust.TrustTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Optional CompositionLocal for providing a shared [SellerTrustRepository] (e.g. from `AppContainer`).
 * When not explicitly provided, [rememberSellerTrustRepository] falls back to a process-scoped
 * [SellerTrustRepositoryImpl.forContext] backed by `LocalAppContainer.current`.
 */
val LocalSellerTrustRepository = staticCompositionLocalOf<SellerTrustRepository?> { null }

/**
 * Resolves the active [SellerTrustRepository] from an explicit override, [LocalSellerTrustRepository],
 * or process-scoped [SellerTrustRepositoryImpl.forContext] using [LocalAppContainer].
 */
@Composable
fun rememberSellerTrustRepository(
    override: SellerTrustRepository? = null,
): SellerTrustRepository {
    if (override != null) return override
    LocalSellerTrustRepository.current?.let { return it }
    val context = LocalContext.current
    val container = LocalAppContainer.current
    return remember(context, container) {
        SellerTrustRepositoryImpl.forContext(
            context = context,
            userRepository = container.userRepository,
            forumRepository = container.forumRepository,
        )
    }
}

/**
 * Immutable UI state for [SellerTrustCardSheet].
 */
@Immutable
data class SellerTrustUiState(
    val uid: Long,
    val usernameHint: String,
    val avatarUrlHint: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val report: SellerTrustReport? = null,
    val localNote: LocalSellerNote? = null,
    val draftTag: TrustTag = TrustTag.NONE,
    val draftNoteText: String = "",
    val isSavingNote: Boolean = false,
    val noteSavedFeedback: Boolean = false,
    val errorMessage: String? = null,
) {
    val resolvedUid: Long
        get() = report?.uid?.takeIf { it > 0L } ?: uid

    val resolvedUsername: String
        get() = report?.username?.takeIf { it.isNotBlank() }
            ?: usernameHint.trim().ifEmpty { if (uid > 0L) "用户 #$uid" else "未知卖家" }

    val resolvedAvatarUrl: String
        get() = report?.avatarUrl?.takeIf { it.isNotBlank() }
            ?: avatarUrlHint.trim().ifEmpty {
                val id = resolvedUid
                if (id > 0L) "https://www.nodeseek.com/avatar/$id.png" else ""
            }

    val hasUnsavedNoteChanges: Boolean
        get() {
            val savedTag = localNote?.tag ?: TrustTag.NONE
            val savedText = localNote?.noteText.orEmpty().trim()
            return draftTag != savedTag || draftNoteText.trim() != savedText
        }
}

/**
 * State holder managing inspection loading, refresh, reactive local note observation,
 * 1-tap tag persistence, and debounced/manual local note saving for [SellerTrustCardSheet].
 */
@Stable
class SellerTrustStateHolder(
    private val repository: SellerTrustRepository,
    private val scope: CoroutineScope,
    uid: Long,
    usernameHint: String,
    avatarUrlHint: String = "",
) {
    private val _uiState = MutableStateFlow(
        SellerTrustUiState(
            uid = uid,
            usernameHint = usernameHint,
            avatarUrlHint = avatarUrlHint,
        ),
    )
    val uiState: StateFlow<SellerTrustUiState> = _uiState.asStateFlow()

    private var inspectJob: Job? = null
    private var observeNoteJob: Job? = null
    private var autoSaveJob: Job? = null
    private var feedbackJob: Job? = null
    private var noteEditSequence: Long = 0L
    private var tagEditSequence: Long = 0L
    private var saveSequence: Long = 0L
    private var userEditedNoteText: Boolean = false
    private var userEditedTag: Boolean = false

    init {
        if (uid > 0L) {
            startObservingNote(uid)
        }
    }

    fun load(forceRefresh: Boolean = false) {
        val snapshot = _uiState.value
        inspectJob?.cancel()
        inspectJob = scope.launch {
            _uiState.update { current ->
                current.copy(
                    isLoading = current.report == null,
                    isRefreshing = forceRefresh || current.report != null,
                    errorMessage = null,
                )
            }
            try {
                val report = repository.inspectSeller(
                    uid = snapshot.uid,
                    usernameHint = snapshot.usernameHint,
                    forceRefresh = forceRefresh,
                )
                if (report.uid > 0L && report.uid != snapshot.uid) {
                    startObservingNote(report.uid)
                }
                _uiState.update { current ->
                    val note = current.localNote ?: report.localNote
                    current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        report = if (note != report.localNote) {
                            report.copy(
                                localNote = note,
                                riskAssessment = TrustRiskEvaluator.evaluate(
                                    rankLevel = report.rankLevel,
                                    registrationDays = report.registrationDays,
                                    historicalTradeTopicCount = report.historicalTradeTopicCount,
                                    exposureSearchHits = report.exposureSearchHits,
                                    searchSucceeded = report.searchSucceeded,
                                    localNote = note,
                                    profileSucceeded = report.profileSucceeded,
                                    registrationDurationLabel = report.registrationDurationLabel,
                                ),
                            )
                        } else {
                            report
                        },
                        localNote = note,
                        draftTag = if (userEditedTag) current.draftTag else (note?.tag ?: TrustTag.NONE),
                        draftNoteText = if (userEditedNoteText) current.draftNoteText else note?.noteText.orEmpty(),
                        errorMessage = null,
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "信誉核验加载失败，请重试",
                    )
                }
            }
        }
    }

    fun refresh() {
        load(forceRefresh = true)
    }

    /**
     * Toggles [tag]: tapping an unselected tag activates it; tapping the already-selected tag resets to [TrustTag.NONE].
     * Immediately persists to local storage and re-evaluates the displayed risk banner.
     */
    fun toggleTag(tag: TrustTag) {
        val current = _uiState.value
        val nextTag = if (current.draftTag == tag) TrustTag.NONE else tag
        userEditedTag = true
        tagEditSequence++
        _uiState.update { it.copy(draftTag = nextTag) }
        autoSaveJob?.cancel()
        persistNote(tag = nextTag, noteText = _uiState.value.draftNoteText)
    }

    /**
     * Updates the note text field and triggers debounced auto-save.
     */
    fun updateDraftNoteText(text: String) {
        userEditedNoteText = true
        noteEditSequence++
        val clamped = if (text.length > MAX_NOTE_LENGTH) text.take(MAX_NOTE_LENGTH) else text
        _uiState.update {
            it.copy(
                draftNoteText = clamped,
                noteSavedFeedback = false,
            )
        }
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            delay(AUTO_SAVE_DEBOUNCE_MILLIS)
            val latest = _uiState.value
            persistNote(tag = latest.draftTag, noteText = latest.draftNoteText)
        }
    }

    /**
     * Persists the current draft tag & note text immediately.
     */
    fun saveNoteNow() {
        autoSaveJob?.cancel()
        val latest = _uiState.value
        persistNote(tag = latest.draftTag, noteText = latest.draftNoteText)
    }

    private fun startObservingNote(targetUid: Long) {
        if (targetUid <= 0L) return
        observeNoteJob?.cancel()
        observeNoteJob = scope.launch {
            repository.observeLocalNote(targetUid).collect { observedNote ->
                _uiState.update { current ->
                    val updatedReport = current.report?.let { existing ->
                        if (existing.localNote == observedNote) {
                            existing
                        } else {
                            existing.copy(
                                localNote = observedNote,
                                riskAssessment = TrustRiskEvaluator.evaluate(
                                    rankLevel = existing.rankLevel,
                                    registrationDays = existing.registrationDays,
                                    historicalTradeTopicCount = existing.historicalTradeTopicCount,
                                    exposureSearchHits = existing.exposureSearchHits,
                                    searchSucceeded = existing.searchSucceeded,
                                    localNote = observedNote,
                                    profileSucceeded = existing.profileSucceeded,
                                    registrationDurationLabel = existing.registrationDurationLabel,
                                ),
                            )
                        }
                    }
                    current.copy(
                        localNote = observedNote,
                        report = updatedReport,
                        draftTag = if (userEditedTag) {
                            current.draftTag
                        } else {
                            observedNote?.tag ?: TrustTag.NONE
                        },
                        draftNoteText = if (userEditedNoteText) {
                            current.draftNoteText
                        } else {
                            observedNote?.noteText.orEmpty()
                        },
                    )
                }
            }
        }
    }

    private fun persistNote(tag: TrustTag, noteText: String) {
        val current = _uiState.value
        val targetUid = current.resolvedUid
        if (targetUid <= 0L) return
        val targetUsername = current.resolvedUsername
        val capturedNoteSeq = noteEditSequence
        val capturedTagSeq = tagEditSequence
        val currentSaveSeq = ++saveSequence

        scope.launch {
            _uiState.update { it.copy(isSavingNote = true) }
            try {
                repository.saveLocalNote(
                    uid = targetUid,
                    username = targetUsername,
                    tag = tag,
                    noteText = noteText,
                )
                if (noteEditSequence == capturedNoteSeq) {
                    userEditedNoteText = false
                }
                if (tagEditSequence == capturedTagSeq) {
                    userEditedTag = false
                }
                if (saveSequence == currentSaveSeq) {
                    _uiState.update { it.copy(isSavingNote = false, noteSavedFeedback = true) }
                    feedbackJob?.cancel()
                    feedbackJob = scope.launch {
                        delay(SAVED_FEEDBACK_DURATION_MILLIS)
                        _uiState.update { it.copy(noteSavedFeedback = false) }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                if (saveSequence == currentSaveSeq) {
                    _uiState.update { it.copy(isSavingNote = false) }
                }
            }
        }
    }

    companion object {
        const val MAX_NOTE_LENGTH: Int = 240
        private const val AUTO_SAVE_DEBOUNCE_MILLIS: Long = 700L
        private const val SAVED_FEEDBACK_DURATION_MILLIS: Long = 2200L
    }
}

/**
 * Remembers a [SellerTrustStateHolder] scoped to `(uid, usernameHint, repository)` and
 * triggers the initial inspection automatically.
 */
@Composable
fun rememberSellerTrustState(
    uid: Long,
    usernameHint: String,
    avatarUrlHint: String = "",
    repository: SellerTrustRepository? = null,
): SellerTrustStateHolder {
    val resolvedRepo = rememberSellerTrustRepository(override = repository)
    val scope = rememberCoroutineScope()
    val holder = remember(resolvedRepo, uid, usernameHint, avatarUrlHint) {
        SellerTrustStateHolder(
            repository = resolvedRepo,
            scope = scope,
            uid = uid,
            usernameHint = usernameHint,
            avatarUrlHint = avatarUrlHint,
        )
    }
    LaunchedEffect(holder) {
        holder.load(forceRefresh = false)
    }
    return holder
}

/**
 * Optional lifecycle-scoped [ViewModel] wrapper around [SellerTrustStateHolder] for callers
 * that prefer `viewModel(...)` retention across configuration changes.
 */
class SellerTrustViewModel(
    repository: SellerTrustRepository,
    uid: Long,
    usernameHint: String,
    avatarUrlHint: String = "",
) : ViewModel() {

    val stateHolder: SellerTrustStateHolder = SellerTrustStateHolder(
        repository = repository,
        scope = viewModelScope,
        uid = uid,
        usernameHint = usernameHint,
        avatarUrlHint = avatarUrlHint,
    )

    val uiState: StateFlow<SellerTrustUiState> = stateHolder.uiState

    init {
        stateHolder.load(forceRefresh = false)
    }

    fun refresh() = stateHolder.refresh()
    fun toggleTag(tag: TrustTag) = stateHolder.toggleTag(tag)
    fun updateDraftNoteText(text: String) = stateHolder.updateDraftNoteText(text)
    fun saveNoteNow() = stateHolder.saveNoteNow()

    companion object {
        fun factory(
            repository: SellerTrustRepository,
            uid: Long,
            usernameHint: String,
            avatarUrlHint: String = "",
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SellerTrustViewModel(
                    repository = repository,
                    uid = uid,
                    usernameHint = usernameHint,
                    avatarUrlHint = avatarUrlHint,
                ) as T
            }
        }
    }
}
