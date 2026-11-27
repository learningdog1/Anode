@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.InviteCodeItem
import com.nodeseek.app.domain.model.InviteConfig
import com.nodeseek.app.domain.model.InvitePaymentMethod
import com.nodeseek.app.domain.model.InvitePaymentOption
import com.nodeseek.app.domain.model.ModerationRuling
import com.nodeseek.app.domain.repository.CommunityToolsRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.ui.components.NodeSeekDrumstickIcon
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.components.WebViewSessionSheet
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InviteAndRulingsUiState(
    val selectedTab: Int = 0,
    val session: AccountSession? = null,
    // Invite management
    val isLoadingInvites: Boolean = false,
    val isLoadingMoreInvites: Boolean = false,
    val inviteConfig: InviteConfig? = null,
    val inviteItems: List<InviteCodeItem> = emptyList(),
    val inviteTotalCount: Int = 0,
    val invitePage: Int = 1,
    val inviteHasNextPage: Boolean = false,
    val inviteError: String? = null,
    val isBuyingInvite: Boolean = false,
    val lastPurchasedCode: String? = null,
    val requiresInvitePageVerification: Boolean = false,
    // Rulings search & detail
    val rulingSearchQuery: String = "",
    val activeRulingSearch: String = "",
    val isLoadingRulings: Boolean = false,
    val isLoadingMoreRulings: Boolean = false,
    val rulings: List<ModerationRuling> = emptyList(),
    val rulingsTotalCount: Int = 0,
    val rulingsPage: Int = 1,
    val rulingsHasNextPage: Boolean = false,
    val rulingsError: String? = null,
    val isLoadingRulingDetail: Boolean = false,
    val selectedRulingDetail: ModerationRuling? = null,
    val rulingDetailError: String? = null,
)

class InviteAndRulingsViewModel(
    private val repository: CommunityToolsRepository,
    private val sessionRepository: SessionRepository,
    initialTab: Int = 0,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        InviteAndRulingsUiState(
            selectedTab = initialTab.coerceIn(0, 1),
            session = sessionRepository.sessionState.value,
        ),
    )
    val uiState: StateFlow<InviteAndRulingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var invitesLoadedOnce = false
    private var rulingsLoadedOnce = false
    private var inviteGeneration = 0L
    private var inviteLoadJob: Job? = null
    private var inviteMoreJob: Job? = null
    private var inviteBuyJob: Job? = null

    init {
        viewModelScope.launch {
            sessionRepository.sessionState.collect { session ->
                val prevUid = _uiState.value.session?.uid
                val newUid = session?.uid
                val uidChanged = prevUid != newUid
                if (uidChanged) {
                    inviteGeneration += 1L
                    inviteLoadJob?.cancel()
                    inviteLoadJob = null
                    inviteMoreJob?.cancel()
                    inviteMoreJob = null
                    inviteBuyJob?.cancel()
                    inviteBuyJob = null
                    invitesLoadedOnce = false
                    _uiState.update { cur ->
                        cur.copy(
                            session = session,
                            isLoadingInvites = false,
                            isLoadingMoreInvites = false,
                            inviteConfig = null,
                            inviteItems = emptyList(),
                            inviteTotalCount = 0,
                            invitePage = 1,
                            inviteHasNextPage = false,
                            inviteError = null,
                            isBuyingInvite = false,
                            lastPurchasedCode = null,
                            requiresInvitePageVerification = false,
                        )
                    }
                    if (session != null && _uiState.value.selectedTab == 0) {
                        loadInvitesInitial(force = true)
                    }
                } else {
                    _uiState.update { it.copy(session = session) }
                    if (session != null && !invitesLoadedOnce && _uiState.value.selectedTab == 0) {
                        loadInvitesInitial(force = true)
                    }
                }
            }
        }
        if (_uiState.value.selectedTab == 0) {
            if (sessionRepository.sessionState.value != null) {
                loadInvitesInitial(force = true)
            }
        } else {
            loadRulingsInitial(force = true)
        }
    }

    fun selectTab(tabIndex: Int) {
        val clamped = tabIndex.coerceIn(0, 1)
        _uiState.update { it.copy(selectedTab = clamped) }
        if (clamped == 0) {
            if (!invitesLoadedOnce && _uiState.value.session != null) {
                loadInvitesInitial(force = false)
            }
        } else {
            if (!rulingsLoadedOnce) {
                loadRulingsInitial(force = false)
            }
        }
    }

    fun refreshActiveTab() {
        if (_uiState.value.selectedTab == 0) {
            loadInvitesInitial(force = true)
        } else {
            loadRulingsInitial(force = true)
        }
    }

    fun loadInvitesInitial(force: Boolean = true) {
        val currentSession = _uiState.value.session
        if (currentSession == null) {
            _uiState.update {
                it.copy(
                    isLoadingInvites = false,
                    inviteError = null,
                )
            }
            return
        }
        if (!force && (invitesLoadedOnce || _uiState.value.isLoadingInvites)) return

        val expectedUid = currentSession.uid
        val gen = inviteGeneration
        inviteLoadJob?.cancel()
        inviteLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingInvites = true, inviteError = null) }
            try {
                val configResult = repository.getInviteConfig()
                val listResult = repository.listInvites(page = 1)
                if (gen != inviteGeneration || sessionRepository.sessionState.value?.uid != expectedUid) {
                    return@launch
                }

                invitesLoadedOnce = true
                val errorMsg = listResult.exceptionOrNull()?.message
                    ?: configResult.exceptionOrNull()?.message

                _uiState.update { cur ->
                    if (gen != inviteGeneration || cur.session?.uid != expectedUid) {
                        cur
                    } else {
                        val listPage = listResult.getOrNull()
                        cur.copy(
                            isLoadingInvites = false,
                            inviteConfig = configResult.getOrNull() ?: cur.inviteConfig,
                            inviteItems = listPage?.items ?: cur.inviteItems,
                            inviteTotalCount = listPage?.totalCount ?: cur.inviteTotalCount,
                            invitePage = listPage?.currentPage ?: 1,
                            inviteHasNextPage = listPage?.hasNextPage ?: false,
                            inviteError = if (listPage == null && configResult.isFailure) {
                                errorMsg?.takeIf { it.isNotBlank() } ?: "获取邀请码信息失败"
                            } else {
                                null
                            },
                        )
                    }
                }
                if (errorMsg != null && (listResult.isSuccess || configResult.isSuccess)) {
                    _events.tryEmit(errorMsg)
                }
            } finally {
                if (gen == inviteGeneration) {
                    _uiState.update { cur ->
                        if (cur.isLoadingInvites) cur.copy(isLoadingInvites = false) else cur
                    }
                }
            }
        }
    }

    fun loadMoreInvites() {
        val state = _uiState.value
        val currentSession = state.session
        if (currentSession == null || state.isLoadingInvites || state.isLoadingMoreInvites || !state.inviteHasNextPage) {
            return
        }
        val expectedUid = currentSession.uid
        val gen = inviteGeneration
        val nextPage = state.invitePage + 1
        inviteMoreJob?.cancel()
        inviteMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreInvites = true) }
            try {
                repository.listInvites(page = nextPage).fold(
                    onSuccess = { page ->
                        if (gen != inviteGeneration || sessionRepository.sessionState.value?.uid != expectedUid) {
                            return@fold
                        }
                        _uiState.update { cur ->
                            if (gen != inviteGeneration || cur.session?.uid != expectedUid) {
                                cur
                            } else {
                                cur.copy(
                                    isLoadingMoreInvites = false,
                                    inviteItems = (cur.inviteItems + page.items).distinctBy { it.code },
                                    inviteTotalCount = maxOf(cur.inviteTotalCount, page.totalCount),
                                    invitePage = page.currentPage,
                                    inviteHasNextPage = page.hasNextPage,
                                )
                            }
                        }
                    },
                    onFailure = { err ->
                        if (gen != inviteGeneration || sessionRepository.sessionState.value?.uid != expectedUid) {
                            return@fold
                        }
                        _uiState.update { it.copy(isLoadingMoreInvites = false) }
                        _events.tryEmit(err.message?.takeIf { it.isNotBlank() } ?: "加载更多邀请码失败")
                    },
                )
            } finally {
                if (gen == inviteGeneration) {
                    _uiState.update { cur ->
                        if (cur.isLoadingMoreInvites) cur.copy(isLoadingMoreInvites = false) else cur
                    }
                }
            }
        }
    }

    fun buyInviteCode(method: InvitePaymentMethod) {
        val state = _uiState.value
        val currentSession = state.session
        if (currentSession == null || state.isBuyingInvite) return
        val expectedUid = currentSession.uid
        val gen = inviteGeneration

        inviteBuyJob?.cancel()
        inviteBuyJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBuyingInvite = true,
                    requiresInvitePageVerification = false,
                )
            }
            try {
                repository.buyInviteCode(method).fold(
                    onSuccess = { purchased ->
                        if (gen != inviteGeneration || sessionRepository.sessionState.value?.uid != expectedUid) {
                            return@fold
                        }
                        _uiState.update { cur ->
                            if (gen != inviteGeneration || cur.session?.uid != expectedUid) {
                                cur
                            } else {
                                val newItems = if (purchased.code.isNotBlank()) {
                                    val item = InviteCodeItem(
                                        code = purchased.code,
                                        applied = false,
                                        createdAtRaw = "刚刚购买",
                                        createdAtEpochMillis = System.currentTimeMillis(),
                                    )
                                    listOf(item) + cur.inviteItems.filterNot { it.code == purchased.code }
                                } else {
                                    cur.inviteItems
                                }
                                cur.copy(
                                    isBuyingInvite = false,
                                    lastPurchasedCode = purchased.code.takeIf { it.isNotBlank() },
                                    inviteItems = newItems,
                                    inviteTotalCount = maxOf(cur.inviteTotalCount, newItems.size),
                                    requiresInvitePageVerification = false,
                                )
                            }
                        }
                        _events.tryEmit(purchased.message ?: "邀请码购买成功")
                        loadInvitesInitial(force = true)
                    },
                    onFailure = { err ->
                        if (gen != inviteGeneration || sessionRepository.sessionState.value?.uid != expectedUid) {
                            return@fold
                        }
                        val message = err.message?.takeIf { it.isNotBlank() } ?: "购买邀请码失败"
                        val needsVerification = message.contains("邀请页面") ||
                            message.contains("csrf", ignoreCase = true)
                        _uiState.update {
                            it.copy(
                                isBuyingInvite = false,
                                requiresInvitePageVerification = needsVerification,
                            )
                        }
                        _events.tryEmit(message)
                    },
                )
            } finally {
                if (gen == inviteGeneration) {
                    _uiState.update { cur ->
                        if (cur.isBuyingInvite) cur.copy(isBuyingInvite = false) else cur
                    }
                }
            }
        }
    }

    fun dismissInviteVerificationBanner() {
        _uiState.update { it.copy(requiresInvitePageVerification = false) }
    }

    fun refreshAfterInvitePageVerification() {
        val expectedUid = _uiState.value.session?.uid
        val gen = inviteGeneration
        viewModelScope.launch {
            repository.warmInviteCsrf()
            if (gen != inviteGeneration || sessionRepository.sessionState.value?.uid != expectedUid) {
                return@launch
            }
            _uiState.update { it.copy(requiresInvitePageVerification = false) }
            loadInvitesInitial(force = true)
        }
    }

    fun updateRulingSearchQuery(query: String) {
        _uiState.update { it.copy(rulingSearchQuery = query) }
    }

    fun submitRulingSearch() {
        val query = _uiState.value.rulingSearchQuery.trim()
        _uiState.update { it.copy(activeRulingSearch = query) }
        loadRulingsInitial(force = true)
    }

    fun clearRulingSearch() {
        _uiState.update { it.copy(rulingSearchQuery = "", activeRulingSearch = "") }
        loadRulingsInitial(force = true)
    }

    fun loadRulingsInitial(force: Boolean = true) {
        if (!force && (rulingsLoadedOnce || _uiState.value.isLoadingRulings)) return
        val search = _uiState.value.activeRulingSearch.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRulings = true, rulingsError = null) }
            repository.listModerationRulings(page = 1, search = search).fold(
                onSuccess = { page ->
                    rulingsLoadedOnce = true
                    _uiState.update { cur ->
                        cur.copy(
                            isLoadingRulings = false,
                            rulings = page.items,
                            rulingsTotalCount = page.totalCount,
                            rulingsPage = page.currentPage,
                            rulingsHasNextPage = page.hasNextPage,
                            rulingsError = null,
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { cur ->
                        cur.copy(
                            isLoadingRulings = false,
                            rulingsError = err.message?.takeIf { it.isNotBlank() } ?: "获取小黑屋裁决失败",
                        )
                    }
                },
            )
        }
    }

    fun loadMoreRulings() {
        val state = _uiState.value
        if (state.isLoadingRulings || state.isLoadingMoreRulings || !state.rulingsHasNextPage) return
        val nextPage = state.rulingsPage + 1
        val search = state.activeRulingSearch.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreRulings = true) }
            repository.listModerationRulings(page = nextPage, search = search).fold(
                onSuccess = { page ->
                    _uiState.update { cur ->
                        cur.copy(
                            isLoadingMoreRulings = false,
                            rulings = (cur.rulings + page.items).distinctBy { it.id },
                            rulingsTotalCount = maxOf(cur.rulingsTotalCount, page.totalCount),
                            rulingsPage = page.currentPage,
                            rulingsHasNextPage = page.hasNextPage,
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoadingMoreRulings = false) }
                    _events.tryEmit(err.message?.takeIf { it.isNotBlank() } ?: "加载更多裁决记录失败")
                },
            )
        }
    }

    fun openRulingDetail(ruling: ModerationRuling) {
        _uiState.update {
            it.copy(
                selectedRulingDetail = ruling,
                isLoadingRulingDetail = true,
                rulingDetailError = null,
            )
        }
        viewModelScope.launch {
            repository.getModerationRulingDetail(ruling.id).fold(
                onSuccess = { detail ->
                    _uiState.update {
                        it.copy(
                            selectedRulingDetail = detail,
                            isLoadingRulingDetail = false,
                            rulingDetailError = null,
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingRulingDetail = false,
                            rulingDetailError = err.message?.takeIf { it.isNotBlank() } ?: "加载裁决详情失败",
                        )
                    }
                },
            )
        }
    }

    fun dismissRulingDetail() {
        _uiState.update {
            it.copy(
                selectedRulingDetail = null,
                isLoadingRulingDetail = false,
                rulingDetailError = null,
            )
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            initialTab: Int,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InviteAndRulingsViewModel(
                    repository = container.communityToolsRepository,
                    sessionRepository = container.sessionRepository,
                    initialTab = initialTab,
                )
            }
        }
    }
}

@Composable
fun InviteAndRulingsScreen(
    initialTab: Int = 0,
    onBack: () -> Unit,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onRequestWebViewAuth: (() -> Unit)? = null,
    viewModel: InviteAndRulingsViewModel = viewModel(
        key = "invite_rulings_$initialTab",
        factory = InviteAndRulingsViewModel.factory(LocalAppContainer.current, initialTab),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var pendingPurchaseOption by remember { mutableStateOf<InvitePaymentOption?>(null) }
    var showInviteVerifyWebView by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val isRefreshing = if (uiState.selectedTab == 0) uiState.isLoadingInvites else uiState.isLoadingRulings

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseGlassSurface(showTopBorder = false, showBottomBorder = true),
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = "邀请码与小黑屋公示",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::refreshActiveTab) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )

                PrimaryTabRow(
                    selectedTabIndex = uiState.selectedTab,
                    containerColor = Color.Transparent,
                ) {
                    Tab(
                        selected = uiState.selectedTab == 0,
                        onClick = { viewModel.selectTab(0) },
                        text = { Text("邀请码") },
                        icon = { Icon(Icons.Rounded.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    Tab(
                        selected = uiState.selectedTab == 1,
                        onClick = { viewModel.selectTab(1) },
                        text = { Text("小黑屋公示") },
                        icon = { Icon(Icons.Rounded.Gavel, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refreshActiveTab,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState.selectedTab) {
                0 -> InviteManagementTabContent(
                    uiState = uiState,
                    onRequestWebViewAuth = onRequestWebViewAuth,
                    onOpenInviteVerifyWebView = { showInviteVerifyWebView = true },
                    onDismissVerificationBanner = viewModel::dismissInviteVerificationBanner,
                    onSelectPurchaseOption = { pendingPurchaseOption = it },
                    onCopyCode = { code ->
                        clipboardManager.setText(AnnotatedString(code))
                        scope.launch {
                            snackbarHostState.showSnackbar("已复制邀请码 $code")
                        }
                    },
                    onLoadMore = viewModel::loadMoreInvites,
                    onRetry = { viewModel.loadInvitesInitial(force = true) },
                )

                1 -> ModerationRulingsTabContent(
                    uiState = uiState,
                    onSearchQueryChange = viewModel::updateRulingSearchQuery,
                    onSubmitSearch = viewModel::submitRulingSearch,
                    onClearSearch = viewModel::clearRulingSearch,
                    onOpenDetail = viewModel::openRulingDetail,
                    onOpenMemberProfile = onOpenUserProfile,
                    onLoadMore = viewModel::loadMoreRulings,
                    onRetry = { viewModel.loadRulingsInitial(force = true) },
                )
            }
        }
    }

    val confirmOption = pendingPurchaseOption
    if (confirmOption != null) {
        val unitLabel = confirmOption.method.label
        PulseConfirmDialog(
            onDismissRequest = { pendingPurchaseOption = null },
            title = "确认购买邀请码",
            message = "将消耗 ${confirmOption.cost} $unitLabel 购买 1 枚 NodeSeek 邀请码，是否继续？",
            confirmText = "确认购买",
            icon = Icons.Rounded.ConfirmationNumber,
            tone = PulseDialogTone.WARM,
            confirmLoading = uiState.isBuyingInvite,
            onConfirm = {
                pendingPurchaseOption = null
                viewModel.buyInviteCode(confirmOption.method)
            },
        )
    }

    if (showInviteVerifyWebView) {
        WebViewSessionSheet(
            initialUrl = "https://www.nodeseek.com/invite#manual",
            requireSignedInUser = true,
            autoDismissWhenVerified = false,
            onDismiss = {
                showInviteVerifyWebView = false
                viewModel.refreshAfterInvitePageVerification()
            },
            onSessionVerified = {
                showInviteVerifyWebView = false
                viewModel.refreshAfterInvitePageVerification()
            },
        )
    }

    val selectedDetail = uiState.selectedRulingDetail
    if (selectedDetail != null) {
        RulingDetailBottomSheet(
            ruling = selectedDetail,
            isLoading = uiState.isLoadingRulingDetail,
            errorMessage = uiState.rulingDetailError,
            onDismiss = viewModel::dismissRulingDetail,
            onOpenMemberProfile = { uid, name ->
                viewModel.dismissRulingDetail()
                onOpenUserProfile(uid, name)
            },
            onOpenTopic = { topicId ->
                viewModel.dismissRulingDetail()
                onOpenTopic(topicId, 0, "")
            },
        )
    }
}

@Composable
private fun InviteManagementTabContent(
    uiState: InviteAndRulingsUiState,
    onRequestWebViewAuth: (() -> Unit)?,
    onOpenInviteVerifyWebView: () -> Unit,
    onDismissVerificationBanner: () -> Unit,
    onSelectPurchaseOption: (InvitePaymentOption) -> Unit,
    onCopyCode: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val session = uiState.session

    if (session == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = RoundedCornerShape(18.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ConfirmationNumber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = "登录后查看与购买邀请码",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "邀请码购买及已购记录需要登录 NodeSeek 账号后方可使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onRequestWebViewAuth != null) {
                    Button(onClick = onRequestWebViewAuth) {
                        Text("前往登录")
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 14.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "invite_balance_strip") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = RoundedCornerShape(16.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = NodeSeekDrumstickIcon,
                        contentDescription = null,
                        tint = ext.onDrumstickContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text("可用鸡腿", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${session.drumsticks}",
                            style = MaterialTheme.typography.titleMedium.withTabularNumbers(),
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = ext.onStardustContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text("可用星尘", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${session.stardust}",
                            style = MaterialTheme.typography.titleMedium.withTabularNumbers(),
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }

        if (uiState.requiresInvitePageVerification) {
            item(key = "invite_csrf_banner") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = RoundedCornerShape(16.dp),
                            accentColor = MaterialTheme.colorScheme.error,
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "需要先完成官网邀请页 CSRF 验证",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "购买邀请码接口需要官网邀请页签发的 x-csrf-token。点击下方按钮打开官网邀请页，加载完成后返回即可继续购买。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onOpenInviteVerifyWebView) {
                            Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("前往官网邀请页验证")
                        }
                        TextButton(onClick = onDismissVerificationBanner) {
                            Text("忽略")
                        }
                    }
                }
            }
        }

        val purchasedCode = uiState.lastPurchasedCode
        if (!purchasedCode.isNullOrBlank()) {
            item(key = "invite_new_code_banner") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = RoundedCornerShape(16.dp),
                            accentColor = MaterialTheme.colorScheme.primary,
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "新购入邀请码",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = purchasedCode,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    FilledTonalButton(onClick = { onCopyCode(purchasedCode) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制")
                    }
                }
            }
        }

        val config = uiState.inviteConfig
        if (config != null) {
            item(key = "invite_purchase_options") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val coinOpt = config.coinOption
                    InvitePurchaseOptionCard(
                        title = "鸡腿兑换",
                        option = coinOpt,
                        balance = session.drumsticks,
                        isBusy = uiState.isBuyingInvite,
                        accentColor = ext.onDrumstickContainer,
                        onClick = { if (coinOpt != null) onSelectPurchaseOption(coinOpt) },
                        modifier = Modifier.weight(1f),
                    )
                    val stardustOpt = config.stardustOption
                    InvitePurchaseOptionCard(
                        title = "星尘兑换",
                        option = stardustOpt,
                        balance = session.stardust,
                        isBusy = uiState.isBuyingInvite,
                        accentColor = ext.onStardustContainer,
                        onClick = { if (stardustOpt != null) onSelectPurchaseOption(stardustOpt) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (uiState.inviteError != null && uiState.inviteItems.isEmpty()) {
            item(key = "invite_error") {
                LedgerErrorCard(
                    message = uiState.inviteError,
                    onRetry = onRetry,
                )
            }
        } else {
            item(key = "invite_list_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "我的邀请码",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "共 ${uiState.inviteTotalCount} 枚",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!uiState.isLoadingInvites && uiState.inviteItems.isEmpty()) {
                item(key = "invite_empty") {
                    LedgerEmptyCard("暂无已购买的邀请码")
                }
            } else {
                items(
                    items = uiState.inviteItems,
                    key = { it.code },
                ) { item ->
                    InviteCodeRowCard(
                        item = item,
                        onCopy = { onCopyCode(item.code) },
                    )
                }

                if (uiState.inviteHasNextPage || uiState.isLoadingMoreInvites) {
                    item(key = "invite_load_more") {
                        LoadMorePaginationRow(
                            isLoading = uiState.isLoadingMoreInvites,
                            onClick = onLoadMore,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InvitePurchaseOptionCard(
    title: String,
    option: InvitePaymentOption?,
    balance: Int,
    isBusy: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = option != null && option.enabled
    val affordable = option != null && balance >= option.cost
    Column(
        modifier = modifier
            .pulseCard(
                shape = RoundedCornerShape(16.dp),
                accentColor = accentColor,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (option != null) "${option.cost} ${option.method.label}" else "暂未开放",
            style = MaterialTheme.typography.titleMedium.withTabularNumbers(),
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(
            onClick = onClick,
            enabled = enabled && affordable && !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = when {
                        !enabled -> "未开放"
                        !affordable -> "余额不足"
                        else -> "购买"
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun InviteCodeRowCard(
    item: InviteCodeItem,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.code,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PulseAccentPill(
                    text = if (item.applied) "已使用" else "未使用",
                    containerColor = if (item.applied) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (item.applied) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
            }
            Text(
                text = formatLedgerTimeDisplay(item.createdAtEpochMillis, item.createdAtRaw),
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = "复制邀请码",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ModerationRulingsTabContent(
    uiState: InviteAndRulingsUiState,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onOpenDetail: (ModerationRuling) -> Unit,
    onOpenMemberProfile: (uid: Long, usernameHint: String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseTextField(
                value = uiState.rulingSearchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = "搜索用户名 / 违规原因…",
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = if (uiState.rulingSearchQuery.isNotEmpty() || uiState.activeRulingSearch.isNotEmpty()) {
                    {
                        IconButton(onClick = onClearSearch) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空搜索", modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onSubmitSearch) {
                Text("搜索")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (uiState.rulingsError != null && uiState.rulings.isEmpty()) {
                item(key = "rulings_error") {
                    LedgerErrorCard(
                        message = uiState.rulingsError,
                        onRetry = onRetry,
                    )
                }
            } else if (!uiState.isLoadingRulings && uiState.rulings.isEmpty()) {
                item(key = "rulings_empty") {
                    LedgerEmptyCard("未检索到符合条件的小黑屋裁决记录")
                }
            } else {
                items(
                    items = uiState.rulings,
                    key = { it.id },
                ) { ruling ->
                    ModerationRulingRowCard(
                        ruling = ruling,
                        onClick = { onOpenDetail(ruling) },
                        onOpenMemberProfile = onOpenMemberProfile,
                    )
                }

                if (uiState.rulingsHasNextPage || uiState.isLoadingMoreRulings) {
                    item(key = "rulings_load_more") {
                        LoadMorePaginationRow(
                            isLoading = uiState.isLoadingMoreRulings,
                            onClick = onLoadMore,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModerationRulingRowCard(
    ruling: ModerationRuling,
    onClick: () -> Unit,
    onOpenMemberProfile: (uid: Long, usernameHint: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val memberLabel = ruling.memberName.ifBlank {
                ruling.memberId?.let { "UID #$it" } ?: "成员 #${ruling.id}"
            }
            Text(
                text = memberLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (ruling.memberId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .let { mod ->
                        val uid = ruling.memberId
                        if (uid != null) {
                            mod
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(role = Role.Button) {
                                    onOpenMemberProfile(uid, ruling.memberName)
                                }
                        } else {
                            mod
                        }
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (ruling.action.isNotBlank()) {
                PulseAccentPill(
                    text = ruling.action,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Text(
            text = ruling.reason.ifBlank { "社区管理裁决" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "裁决 #${ruling.id}",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatLedgerTimeDisplay(ruling.createdAtEpochMillis, ruling.createdAtRaw),
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RulingDetailBottomSheet(
    ruling: ModerationRuling,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onOpenMemberProfile: (uid: Long, usernameHint: String) -> Unit,
    onOpenTopic: (topicId: Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "裁决详情 #${ruling.id}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
                if (ruling.action.isNotBlank()) {
                    PulseAccentPill(
                        text = ruling.action,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在拉取完整证据记录…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            PulseGradientDivider()

            RulingDetailField("涉事成员", ruling.memberName.ifBlank { ruling.memberId?.let { "UID #$it" } ?: "未知" })
            RulingDetailField("裁决原因", ruling.reason.ifBlank { "暂无说明" })
            RulingDetailField("裁决证据 / 详情", ruling.evidence?.ifBlank { "无附加证据" } ?: "无附加证据")
            if (!ruling.operatorName.isNullOrBlank()) {
                RulingDetailField("处理人", ruling.operatorName)
            }
            RulingDetailField("公示时间", formatLedgerTimeDisplay(ruling.createdAtEpochMillis, ruling.createdAtRaw))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val uid = ruling.memberId
                if (uid != null) {
                    OutlinedButton(
                        onClick = { onOpenMemberProfile(uid, ruling.memberName) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("查看成员主页")
                    }
                }
                val postId = ruling.postId
                if (postId != null) {
                    FilledTonalButton(
                        onClick = { onOpenTopic(postId) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("查看关联主题")
                    }
                }
            }
        }
    }
}

@Composable
private fun RulingDetailField(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LedgerErrorCard(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = RoundedCornerShape(14.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun LedgerEmptyCard(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = RoundedCornerShape(14.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadMorePaginationRow(
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedButton(onClick = onClick, enabled = !isLoading) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isLoading) "加载中…" else "加载更多")
        }
    }
}
