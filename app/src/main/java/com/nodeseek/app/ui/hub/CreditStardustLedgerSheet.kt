@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.hub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.nodeseek.app.domain.model.CreditAnalytics30d
import com.nodeseek.app.domain.model.CreditEntryCategory
import com.nodeseek.app.domain.model.CreditLedgerAnalytics
import com.nodeseek.app.domain.model.CreditLedgerEntry
import com.nodeseek.app.domain.model.DailyCoinBucket
import com.nodeseek.app.domain.model.LevelMilestoneEstimate
import com.nodeseek.app.domain.model.StardustLedgerEntry
import com.nodeseek.app.domain.model.UpgradeEtaStatus
import com.nodeseek.app.domain.repository.CommunityToolsRepository
import com.nodeseek.app.ui.components.NodeSeekDrumstickIcon
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class EconomyLedgerTab(val label: String) {
    DRUMSTICKS("鸡腿账簿"),
    STARDUST("星尘流水"),
}

data class CreditStardustLedgerUiState(
    val selectedTab: EconomyLedgerTab = EconomyLedgerTab.DRUMSTICKS,
    val targetUid: Long = 0L,
    val currentCoins: Int = 0,
    val currentStardust: Int = 0,
    val canAccessDrumsticks: Boolean = true,
    // Drumstick ledger + 30d analytics
    val isLoadingCredit: Boolean = false,
    val isLoadingMoreCredit: Boolean = false,
    val creditEntries: List<CreditLedgerEntry> = emptyList(),
    val creditTotalCount: Int = 0,
    val creditPage: Int = 1,
    val creditHasNextPage: Boolean = false,
    val creditAnalytics: CreditAnalytics30d? = null,
    val creditError: String? = null,
    // Stardust ledger
    val stardustTypeFilter: String? = null,
    val isLoadingStardust: Boolean = false,
    val isLoadingMoreStardust: Boolean = false,
    val stardustRecords: List<StardustLedgerEntry> = emptyList(),
    val stardustNextCursor: Long? = null,
    val stardustHasMore: Boolean = false,
    val stardustError: String? = null,
)

class CreditStardustLedgerViewModel(
    private val repository: CommunityToolsRepository,
    initialTab: EconomyLedgerTab = EconomyLedgerTab.DRUMSTICKS,
    targetUid: Long = 0L,
    currentCoins: Int = 0,
    currentStardust: Int = 0,
    canAccessDrumsticks: Boolean = true,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val effectiveInitialTab = if (canAccessDrumsticks) initialTab else EconomyLedgerTab.STARDUST

    private val _uiState = MutableStateFlow(
        CreditStardustLedgerUiState(
            selectedTab = effectiveInitialTab,
            targetUid = targetUid,
            currentCoins = currentCoins,
            currentStardust = currentStardust,
            canAccessDrumsticks = canAccessDrumsticks,
        ),
    )
    val uiState: StateFlow<CreditStardustLedgerUiState> = _uiState.asStateFlow()

    private var creditLoadedOnce = false
    private var stardustLoadedOnce = false
    private var ledgerGeneration = 0L
    private var creditLoadJob: Job? = null
    private var creditMoreJob: Job? = null
    private var stardustLoadJob: Job? = null
    private var stardustMoreJob: Job? = null

    init {
        if (effectiveInitialTab == EconomyLedgerTab.DRUMSTICKS && canAccessDrumsticks) {
            loadCreditInitial(force = true)
        } else if (targetUid > 0L) {
            loadStardustInitial(force = true)
        }
    }

    fun syncHostSnapshot(
        targetUid: Long,
        currentCoins: Int,
        currentStardust: Int,
        canAccessDrumsticks: Boolean,
        requestedTab: EconomyLedgerTab? = null,
    ) {
        val prev = _uiState.value
        val uidChanged = prev.targetUid != targetUid
        val coinsChanged = prev.currentCoins != currentCoins
        val nextTab = when {
            !canAccessDrumsticks -> EconomyLedgerTab.STARDUST
            requestedTab != null -> requestedTab
            else -> prev.selectedTab
        }
        if (uidChanged) {
            ledgerGeneration += 1L
            creditLoadJob?.cancel()
            creditLoadJob = null
            creditMoreJob?.cancel()
            creditMoreJob = null
            stardustLoadJob?.cancel()
            stardustLoadJob = null
            stardustMoreJob?.cancel()
            stardustMoreJob = null
            creditLoadedOnce = false
            stardustLoadedOnce = false
        }
        _uiState.update { state ->
            if (uidChanged) {
                state.copy(
                    targetUid = targetUid,
                    currentCoins = currentCoins,
                    currentStardust = currentStardust,
                    canAccessDrumsticks = canAccessDrumsticks,
                    selectedTab = nextTab,
                    isLoadingCredit = false,
                    isLoadingMoreCredit = false,
                    creditEntries = emptyList(),
                    creditTotalCount = 0,
                    creditPage = 1,
                    creditHasNextPage = false,
                    creditAnalytics = null,
                    creditError = null,
                    isLoadingStardust = false,
                    isLoadingMoreStardust = false,
                    stardustRecords = emptyList(),
                    stardustHasMore = false,
                    stardustNextCursor = null,
                    stardustError = null,
                    stardustTypeFilter = null,
                )
            } else {
                state.copy(
                    targetUid = targetUid,
                    currentCoins = currentCoins,
                    currentStardust = currentStardust,
                    canAccessDrumsticks = canAccessDrumsticks,
                    selectedTab = nextTab,
                    creditAnalytics = if (coinsChanged && state.creditEntries.isNotEmpty()) {
                        CreditLedgerAnalytics.analyze(
                            entries = state.fetchedOrExistingEntriesForReanalysis(state.creditEntries),
                            currentCoins = currentCoins,
                            nowEpochMillis = clockMillis(),
                        )
                    } else {
                        state.creditAnalytics
                    },
                )
            }
        }
        if (nextTab == EconomyLedgerTab.DRUMSTICKS && canAccessDrumsticks && !creditLoadedOnce) {
            loadCreditInitial(force = true)
        } else if (nextTab == EconomyLedgerTab.STARDUST && targetUid > 0L && !stardustLoadedOnce) {
            loadStardustInitial(force = true)
        }
    }

    private fun CreditStardustLedgerUiState.fetchedOrExistingEntriesForReanalysis(
        entries: List<CreditLedgerEntry>,
    ): List<CreditLedgerEntry> = entries

    fun selectTab(tab: EconomyLedgerTab) {
        if (tab == EconomyLedgerTab.DRUMSTICKS && !_uiState.value.canAccessDrumsticks) return
        _uiState.update { it.copy(selectedTab = tab) }
        when (tab) {
            EconomyLedgerTab.DRUMSTICKS -> if (!creditLoadedOnce) loadCreditInitial(force = false)
            EconomyLedgerTab.STARDUST -> if (!stardustLoadedOnce && _uiState.value.targetUid > 0L) {
                loadStardustInitial(force = false)
            }
        }
    }

    fun refreshActiveTab() {
        when (_uiState.value.selectedTab) {
            EconomyLedgerTab.DRUMSTICKS -> loadCreditInitial(force = true)
            EconomyLedgerTab.STARDUST -> loadStardustInitial(force = true)
        }
    }

    fun loadCreditInitial(force: Boolean = true) {
        val state = _uiState.value
        if (!state.canAccessDrumsticks) return
        if (!force && (state.isLoadingCredit || creditLoadedOnce)) return

        val expectedUid = state.targetUid
        val gen = ledgerGeneration
        creditLoadJob?.cancel()
        creditLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCredit = true, creditError = null) }
            try {
                val result = repository.getRecentCreditAnalytics(
                    currentCoins = _uiState.value.currentCoins,
                    windowDays = CreditLedgerAnalytics.DEFAULT_WINDOW_DAYS,
                    maxPagesToFetch = 5,
                    nowEpochMillis = clockMillis(),
                )
                if (gen != ledgerGeneration || _uiState.value.targetUid != expectedUid) {
                    return@launch
                }
                result.fold(
                    onSuccess = { loaded ->
                        creditLoadedOnce = true
                        // Preserve deduplicated fetched entries up to firstPage + additional pages
                        val dedupEntries = loaded.fetchedEntries.ifEmpty { loaded.firstPage.entries }
                        val lastFetchedPage = ((dedupEntries.size - 1).coerceAtLeast(0) / 20) + 1
                        val hasMoreAfterFetched = if (dedupEntries.size > loaded.firstPage.entries.size) {
                            dedupEntries.size < loaded.firstPage.totalCount && lastFetchedPage < 100
                        } else {
                            loaded.firstPage.hasNextPage
                        }
                        _uiState.update { cur ->
                            if (gen != ledgerGeneration || cur.targetUid != expectedUid) {
                                cur
                            } else {
                                cur.copy(
                                    isLoadingCredit = false,
                                    creditEntries = dedupEntries,
                                    creditTotalCount = loaded.firstPage.totalCount,
                                    creditPage = lastFetchedPage,
                                    creditHasNextPage = hasMoreAfterFetched,
                                    creditAnalytics = loaded.analytics,
                                    creditError = null,
                                )
                            }
                        }
                    },
                    onFailure = { err ->
                        _uiState.update { cur ->
                            if (gen != ledgerGeneration || cur.targetUid != expectedUid) {
                                cur
                            } else {
                                cur.copy(
                                    isLoadingCredit = false,
                                    creditError = err.message?.takeIf { it.isNotBlank() } ?: "无法读取鸡腿账簿",
                                )
                            }
                        }
                    },
                )
            } finally {
                if (gen == ledgerGeneration) {
                    _uiState.update { cur ->
                        if (cur.isLoadingCredit) cur.copy(isLoadingCredit = false) else cur
                    }
                }
            }
        }
    }

    fun loadMoreCredit() {
        val state = _uiState.value
        if (!state.canAccessDrumsticks || state.isLoadingCredit || state.isLoadingMoreCredit || !state.creditHasNextPage) {
            return
        }
        val expectedUid = state.targetUid
        val gen = ledgerGeneration
        val nextPage = state.creditPage + 1
        creditMoreJob?.cancel()
        creditMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreCredit = true) }
            try {
                repository.getCreditLedger(page = nextPage).fold(
                    onSuccess = { pageResult ->
                        if (gen != ledgerGeneration || _uiState.value.targetUid != expectedUid) {
                            return@fold
                        }
                        _uiState.update { cur ->
                            if (gen != ledgerGeneration || cur.targetUid != expectedUid) {
                                cur
                            } else {
                                val merged = cur.creditEntries + pageResult.entries
                                cur.copy(
                                    isLoadingMoreCredit = false,
                                    creditEntries = merged,
                                    creditPage = pageResult.currentPage,
                                    creditTotalCount = maxOf(cur.creditTotalCount, pageResult.totalCount),
                                    creditHasNextPage = pageResult.hasNextPage,
                                    creditError = null,
                                )
                            }
                        }
                    },
                    onFailure = { err ->
                        if (gen != ledgerGeneration || _uiState.value.targetUid != expectedUid) {
                            return@fold
                        }
                        _uiState.update { cur ->
                            cur.copy(
                                isLoadingMoreCredit = false,
                                creditError = err.message?.takeIf { it.isNotBlank() } ?: "加载更多鸡腿记录失败",
                            )
                        }
                    },
                )
            } finally {
                if (gen == ledgerGeneration) {
                    _uiState.update { cur ->
                        if (cur.isLoadingMoreCredit) cur.copy(isLoadingMoreCredit = false) else cur
                    }
                }
            }
        }
    }

    fun selectStardustTypeFilter(type: String?) {
        val normalized = type?.trim()?.takeIf { it.isNotEmpty() }
        if (_uiState.value.stardustTypeFilter == normalized && stardustLoadedOnce) return
        _uiState.update { it.copy(stardustTypeFilter = normalized) }
        loadStardustInitial(force = true)
    }

    fun loadStardustInitial(force: Boolean = true) {
        val state = _uiState.value
        val uid = state.targetUid
        if (uid <= 0L) {
            _uiState.update {
                it.copy(
                    isLoadingStardust = false,
                    stardustError = "无效的用户 UID",
                )
            }
            return
        }
        if (!force && (state.isLoadingStardust || stardustLoadedOnce)) return

        val gen = ledgerGeneration
        val activeType = state.stardustTypeFilter
        stardustLoadJob?.cancel()
        stardustLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingStardust = true, stardustError = null) }
            try {
                repository.getStardustLedger(
                    memberId = uid,
                    count = 15,
                    beforeId = null,
                    type = activeType,
                ).fold(
                    onSuccess = { page ->
                        if (gen != ledgerGeneration || _uiState.value.targetUid != uid) {
                            return@fold
                        }
                        stardustLoadedOnce = true
                        _uiState.update { cur ->
                            if (gen != ledgerGeneration || cur.targetUid != uid) {
                                cur
                            } else {
                                cur.copy(
                                    isLoadingStardust = false,
                                    stardustRecords = page.records,
                                    stardustHasMore = page.hasMore,
                                    stardustNextCursor = page.nextCursor,
                                    stardustError = null,
                                )
                            }
                        }
                    },
                    onFailure = { err ->
                        if (gen != ledgerGeneration || _uiState.value.targetUid != uid) {
                            return@fold
                        }
                        _uiState.update { cur ->
                            cur.copy(
                                isLoadingStardust = false,
                                stardustError = err.message?.takeIf { it.isNotBlank() } ?: "无法读取星尘流水",
                            )
                        }
                    },
                )
            } finally {
                if (gen == ledgerGeneration) {
                    _uiState.update { cur ->
                        if (cur.isLoadingStardust) cur.copy(isLoadingStardust = false) else cur
                    }
                }
            }
        }
    }

    fun loadMoreStardust() {
        val state = _uiState.value
        val uid = state.targetUid
        val cursor = state.stardustNextCursor
        if (uid <= 0L || cursor == null || !state.stardustHasMore || state.isLoadingStardust || state.isLoadingMoreStardust) {
            return
        }
        val gen = ledgerGeneration
        stardustMoreJob?.cancel()
        stardustMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreStardust = true) }
            try {
                repository.getStardustLedger(
                    memberId = uid,
                    count = 15,
                    beforeId = cursor,
                    type = state.stardustTypeFilter,
                ).fold(
                    onSuccess = { page ->
                        if (gen != ledgerGeneration || _uiState.value.targetUid != uid) {
                            return@fold
                        }
                        _uiState.update { cur ->
                            if (gen != ledgerGeneration || cur.targetUid != uid) {
                                cur
                            } else {
                                val merged = (cur.stardustRecords + page.records).distinctBy { it.id }
                                cur.copy(
                                    isLoadingMoreStardust = false,
                                    stardustRecords = merged,
                                    stardustHasMore = page.hasMore,
                                    stardustNextCursor = page.nextCursor,
                                    stardustError = null,
                                )
                            }
                        }
                    },
                    onFailure = { err ->
                        if (gen != ledgerGeneration || _uiState.value.targetUid != uid) {
                            return@fold
                        }
                        _uiState.update { cur ->
                            cur.copy(
                                isLoadingMoreStardust = false,
                                stardustError = err.message?.takeIf { it.isNotBlank() } ?: "加载更多星尘记录失败",
                            )
                        }
                    },
                )
            } finally {
                if (gen == ledgerGeneration) {
                    _uiState.update { cur ->
                        if (cur.isLoadingMoreStardust) cur.copy(isLoadingMoreStardust = false) else cur
                    }
                }
            }
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            initialTab: EconomyLedgerTab,
            targetUid: Long,
            currentCoins: Int,
            currentStardust: Int,
            canAccessDrumsticks: Boolean,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CreditStardustLedgerViewModel(
                    repository = container.communityToolsRepository,
                    initialTab = initialTab,
                    targetUid = targetUid,
                    currentCoins = currentCoins,
                    currentStardust = currentStardust,
                    canAccessDrumsticks = canAccessDrumsticks,
                )
            }
        }
    }
}

@Composable
fun CreditStardustLedgerSheet(
    uid: Long,
    currentCoins: Int,
    currentStardust: Int,
    initialTab: EconomyLedgerTab,
    onDismiss: () -> Unit,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit,
    canAccessDrumsticks: Boolean = true,
    onOpenInviteAndRulings: ((initialTab: Int) -> Unit)? = null,
    viewModel: CreditStardustLedgerViewModel = viewModel(
        key = "economy_ledger_${uid}_$canAccessDrumsticks",
        factory = CreditStardustLedgerViewModel.factory(
            container = LocalAppContainer.current,
            initialTab = initialTab,
            targetUid = uid,
            currentCoins = currentCoins,
            currentStardust = currentStardust,
            canAccessDrumsticks = canAccessDrumsticks,
        ),
    ),
) {
    LaunchedEffect(uid, currentCoins, currentStardust, canAccessDrumsticks, initialTab) {
        viewModel.syncHostSnapshot(
            targetUid = uid,
            currentCoins = currentCoins,
            currentStardust = currentStardust,
            canAccessDrumsticks = canAccessDrumsticks,
            requestedTab = initialTab,
        )
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

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
                .padding(horizontal = 16.dp),
        ) {
            // ── Header Bar ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.selectedTab == EconomyLedgerTab.DRUMSTICKS) "鸡腿账簿与升级预测" else "星尘收支流水",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val level = AccountSession.calculateLevel(uiState.currentCoins)
                    Text(
                        text = "Lv.$level · 鸡腿 ${uiState.currentCoins} · 星尘 ${uiState.currentStardust}",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = viewModel::refreshActiveTab) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新流水",
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                    )
                }
            }

            if (uiState.canAccessDrumsticks) {
                Spacer(modifier = Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    EconomyLedgerTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = uiState.selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = EconomyLedgerTab.entries.size,
                            ),
                        ) {
                            Text(text = tab.label, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 560.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (uiState.selectedTab) {
                    EconomyLedgerTab.DRUMSTICKS -> {
                        if (uiState.isLoadingCredit && uiState.creditEntries.isEmpty()) {
                            item(key = "credit_loading") {
                                LedgerLoadingBlock("正在统计近 30 天鸡腿流水与升级速度…")
                            }
                        } else if (uiState.creditError != null && uiState.creditEntries.isEmpty()) {
                            item(key = "credit_error") {
                                LedgerErrorBlock(
                                    message = uiState.creditError.orEmpty(),
                                    onRetry = { viewModel.loadCreditInitial(force = true) },
                                )
                            }
                        } else {
                            val analytics = uiState.creditAnalytics
                            if (analytics != null) {
                                item(key = "credit_analytics_bento") {
                                    CreditAnalyticsOverviewCard(
                                        analytics = analytics,
                                        isDark = isDark,
                                    )
                                }
                                item(key = "credit_upgrade_calc") {
                                    LevelUpgradeCalculatorCard(
                                        analytics = analytics,
                                        isDark = isDark,
                                    )
                                }
                            }

                            if (onOpenInviteAndRulings != null) {
                                item(key = "credit_tools_shortcut") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pulseCard(
                                                shape = RoundedCornerShape(14.dp),
                                                accentColor = MaterialTheme.colorScheme.primary,
                                            )
                                            .pulseBouncyClickable(
                                                role = Role.Button,
                                                onClickLabel = "打开邀请码与小黑屋公示",
                                                onClick = {
                                                    onDismiss()
                                                    onOpenInviteAndRulings(0)
                                                },
                                            )
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ConfirmationNumber,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = "用鸡腿 / 星尘购买邀请码 · 小黑屋公示",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }

                            item(key = "credit_entries_header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, start = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "收支明细",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "共 ${uiState.creditTotalCount} 条",
                                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (uiState.creditEntries.isEmpty()) {
                                item(key = "credit_empty") {
                                    LedgerEmptyBlock("暂无鸡腿账簿流水记录")
                                }
                            } else {
                                itemsIndexed(
                                    items = uiState.creditEntries,
                                    key = { index, entry ->
                                        "credit_${index}_${entry.createdAtRaw}_${entry.change}_${entry.balance}"
                                    },
                                ) { _, entry ->
                                    CreditLedgerEntryRow(entry = entry)
                                }

                                if (uiState.creditHasNextPage || uiState.isLoadingMoreCredit) {
                                    item(key = "credit_load_more") {
                                        LedgerLoadMoreButton(
                                            isLoading = uiState.isLoadingMoreCredit,
                                            onClick = viewModel::loadMoreCredit,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    EconomyLedgerTab.STARDUST -> {
                        item(key = "stardust_filters") {
                            StardustFilterChipRow(
                                activeType = uiState.stardustTypeFilter,
                                onSelectType = viewModel::selectStardustTypeFilter,
                            )
                        }

                        if (uiState.isLoadingStardust && uiState.stardustRecords.isEmpty()) {
                            item(key = "stardust_loading") {
                                LedgerLoadingBlock("正在读取星尘流水…")
                            }
                        } else if (uiState.stardustError != null && uiState.stardustRecords.isEmpty()) {
                            item(key = "stardust_error") {
                                LedgerErrorBlock(
                                    message = uiState.stardustError.orEmpty(),
                                    onRetry = { viewModel.loadStardustInitial(force = true) },
                                )
                            }
                        } else if (uiState.stardustRecords.isEmpty()) {
                            item(key = "stardust_empty") {
                                LedgerEmptyBlock("当前筛选条件下暂无星尘流水")
                            }
                        } else {
                            itemsIndexed(
                                items = uiState.stardustRecords,
                                key = { index, record -> "stardust_${index}_${record.id}" },
                            ) { _, record ->
                                StardustLedgerEntryRow(
                                    entry = record,
                                    onOpenPeerProfile = { peerUid ->
                                        onDismiss()
                                        onOpenUserProfile(peerUid, "")
                                    },
                                )
                            }

                            if (uiState.stardustHasMore || uiState.isLoadingMoreStardust) {
                                item(key = "stardust_load_more") {
                                    LedgerLoadMoreButton(
                                        isLoading = uiState.isLoadingMoreStardust,
                                        onClick = viewModel::loadMoreStardust,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditAnalyticsOverviewCard(
    analytics: CreditAnalytics30d,
    isDark: Boolean,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val avgLabel = analytics.checkInAverageCoins?.let { avg ->
        String.format(Locale.US, "%.1f 鸡腿/次", avg)
    } ?: "暂无签到"
    val ratioPercent = (analytics.drumstickExpenseRatio * 100.0).roundToInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = RoundedCornerShape(16.dp),
                accentColor = ext.drumstickGoldEnd,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = NodeSeekDrumstickIcon,
                    contentDescription = null,
                    tint = ext.onDrumstickContainer,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "近 ${analytics.windowDays} 天账簿统计",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            PulseAccentPill(
                text = if (analytics.netCoinChange30d >= 0) {
                    "净 +${analytics.netCoinChange30d}"
                } else {
                    "净 ${analytics.netCoinChange30d}"
                },
                containerColor = ext.drumstickContainer,
                contentColor = ext.onDrumstickContainer,
            )
        }

        // 2-column Bento metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ext.drumstickContainer.copy(alpha = if (isDark) 0.45f else 0.55f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "签到手气均值",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.onDrumstickContainer,
                )
                Text(
                    text = avgLabel,
                    style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = "${analytics.checkInCount} 次 · 共 +${analytics.checkInTotalCoins}",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "加腿消耗占比",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$ratioPercent%",
                    style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = "${analytics.drumstickGiftExpenseCoins} / ${analytics.totalExpenseCoins} 鸡腿",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        if (analytics.dailyTrendBuckets.isNotEmpty()) {
            DailyTrendBarChart(
                buckets = analytics.dailyTrendBuckets.takeLast(14),
                positiveColor = ext.drumstickGoldEnd,
                negativeColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DailyTrendBarChart(
    buckets: List<DailyCoinBucket>,
    positiveColor: Color,
    negativeColor: Color,
) {
    val maxMagnitude = remember(buckets) {
        max(1, buckets.maxOfOrNull { kotlin.math.abs(it.netCoins) } ?: 1)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "每日净收支趋势（最近 ${buckets.size} 个有流水日）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "±$maxMagnitude",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val baselineColor = MaterialTheme.colorScheme.outlineVariant
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            val midY = size.height / 2f
            drawLine(
                color = baselineColor,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1.dp.toPx(),
            )
            val count = buckets.size.coerceAtLeast(1)
            val slotWidth = size.width / count.toFloat()
            val barWidth = (slotWidth * 0.56f).coerceIn(4.dp.toPx(), 18.dp.toPx())
            buckets.forEachIndexed { index, bucket ->
                val ratio = (bucket.netCoins.toFloat() / maxMagnitude.toFloat()).coerceIn(-1f, 1f)
                val barHeight = (kotlin.math.abs(ratio) * (size.height * 0.44f)).coerceAtLeast(2.dp.toPx())
                val x = index * slotWidth + (slotWidth - barWidth) / 2f
                val topY = if (bucket.netCoins >= 0) midY - barHeight else midY
                drawRoundRect(
                    color = if (bucket.netCoins >= 0) positiveColor else negativeColor,
                    topLeft = Offset(x, topY),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun LevelUpgradeCalculatorCard(
    analytics: CreditAnalytics30d,
    isDark: Boolean,
) {
    val eta = analytics.upgradeEta
    val rankSpec = NodePulseThemeTokens.rankSpec(eta.currentLevel)
    val currentThreshold = 100 * eta.currentLevel * eta.currentLevel
    val progressFraction = if (eta.nextLevelThreshold == null) {
        1f
    } else {
        val span = (eta.nextLevelThreshold - currentThreshold).coerceAtLeast(1)
        ((eta.currentCoins - currentThreshold).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "ledgerUpgradeProgress",
    )

    val etaHeadline = when (eta.status) {
        UpgradeEtaStatus.MAX_LEVEL -> "已达成最高等级 Lv.6"
        UpgradeEtaStatus.ESTIMATED -> {
            val days = eta.estimatedDaysToNextLevel ?: 0
            if (days == 0) "已满足 Lv.${eta.nextLevel} 门槛"
            else "预计约 $days 天升至 Lv.${eta.nextLevel}"
        }
        UpgradeEtaStatus.STAGNANT_OR_DECLINING -> "近 ${analytics.windowDays} 天净增长为 ${String.format(Locale.US, "%.2f", eta.netCoinsPerDay30d)}/天，暂无法预计"
        UpgradeEtaStatus.INSUFFICIENT_DATA -> "近 ${analytics.windowDays} 天流水不足，暂无法估算"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = RoundedCornerShape(16.dp),
                accentColor = rankSpec.glowColor,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.TrendingUp,
                    contentDescription = null,
                    tint = rankSpec.contentColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Lv.1–Lv.6 升级计算器",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = if (eta.nextLevelThreshold != null) {
                    "差 ${eta.coinsRemaining} 鸡腿"
                } else {
                    "已满级"
                },
                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
                color = rankSpec.contentColor,
            )
        }

        Text(
            text = etaHeadline,
            style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress.coerceIn(0.04f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(rankSpec.ringBrush),
            )
        }

        Text(
            text = "近 ${analytics.windowDays} 天日均净增 ${String.format(Locale.US, "%+.2f", eta.netCoinsPerDay30d)} 鸡腿/天 · 公式 100 × 等级²",
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PulseGradientDivider()

        // Lv.1..Lv.6 ladder
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            analytics.levelMilestones.forEach { milestone ->
                MilestonePill(milestone = milestone, isDark = isDark)
            }
        }
    }
}

@Composable
private fun MilestonePill(
    milestone: LevelMilestoneEstimate,
    isDark: Boolean,
) {
    val bgColor = if (milestone.unlocked) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.55f else 0.75f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (milestone.unlocked) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (milestone.unlocked) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = if (milestone.unlocked) {
                "Lv.${milestone.level} (${milestone.requiredCoins})"
            } else if (milestone.estimatedDays != null) {
                "Lv.${milestone.level} · 约${milestone.estimatedDays}天"
            } else {
                "Lv.${milestone.level} · 差${milestone.coinsNeeded}"
            },
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            fontWeight = if (milestone.unlocked) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun CreditLedgerEntryRow(entry: CreditLedgerEntry) {
    val ext = NodePulseThemeTokens.extendedColors
    val isPositive = entry.change >= 0
    val deltaColor = when {
        entry.change > 0 -> ext.onDrumstickContainer
        entry.change < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseAccentPill(
                    text = entry.category.label,
                    containerColor = when (entry.category) {
                        CreditEntryCategory.CHECK_IN -> ext.drumstickContainer
                        CreditEntryCategory.DRUMSTICK_GIFT_EXPENSE -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = when (entry.category) {
                        CreditEntryCategory.CHECK_IN -> ext.onDrumstickContainer
                        CreditEntryCategory.DRUMSTICK_GIFT_EXPENSE -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = entry.reason.ifBlank { entry.category.label },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatLedgerTimeDisplay(entry.createdAtEpochMillis, entry.createdAtRaw),
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (isPositive) "+${entry.change}" else "${entry.change}",
                style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                fontWeight = FontWeight.ExtraBold,
                color = deltaColor,
            )
            Text(
                text = "余 ${entry.balance}",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StardustFilterChipRow(
    activeType: String?,
    onSelectType: (String?) -> Unit,
) {
    val filters = listOf(
        null to "全部",
        "transfer" to "转账",
        "upvote" to "点赞",
        "system" to "系统",
        "admin" to "管理",
        "buyCode" to "购买邀请码",
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        filters.forEach { (key, label) ->
            FilterChip(
                selected = activeType == key,
                onClick = { onSelectType(key) },
                label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun StardustLedgerEntryRow(
    entry: StardustLedgerEntry,
    onOpenPeerProfile: (Long) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isPositive = entry.diff >= 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(ext.stardustContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = ext.onStardustContainer,
                modifier = Modifier.size(16.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseAccentPill(
                    text = entry.typeLabel,
                    containerColor = ext.stardustContainer,
                    contentColor = ext.onStardustContainer,
                )
                val peerUid = entry.peerUid
                if (peerUid != null) {
                    Text(
                        text = "对象 UID #$peerUid",
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { onOpenPeerProfile(peerUid) }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = formatLedgerTimeDisplay(entry.createdAtEpochMillis, entry.createdAtRaw),
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (isPositive) "+${entry.diff}" else "${entry.diff}",
                style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                fontWeight = FontWeight.ExtraBold,
                color = if (isPositive) ext.onStardustContainer else MaterialTheme.colorScheme.error,
            )
            Text(
                text = "余 ${entry.balanceAfter}",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LedgerLoadingBlock(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.6.dp)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LedgerErrorBlock(
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
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = onRetry) {
            Text("重试")
        }
    }
}

@Composable
private fun LedgerEmptyBlock(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = RoundedCornerShape(14.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LedgerLoadMoreButton(
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = !isLoading,
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = if (isLoading) "加载中…" else "加载更多")
        }
    }
}

private val LEDGER_DISPLAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

internal fun formatLedgerTimeDisplay(epochMillis: Long?, raw: String): String {
    if (epochMillis != null) {
        return runCatching { LEDGER_DISPLAY_FORMATTER.format(Instant.ofEpochMilli(epochMillis)) }
            .getOrElse { raw }
    }
    return raw.ifBlank { "未知时间" }
}
