@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.CheckInMember
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckInBoardUiState(
    val currentUid: Long? = null,
    val currentUsername: String? = null,
    val preferRandomCheckIn: Boolean = false,
    val hasCheckedInToday: Boolean = false,
    val todayGain: Int? = null,
    val todayOrder: Int? = null,
    val totalCheckedInUsers: Int = 0,
    val members: List<CheckInMember> = emptyList(),
    val currentPage: Int = 1,
    val hasNextPage: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isCheckingIn: Boolean = false,
    val errorMessage: String? = null,
)

class CheckInBoardViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private data class BoardState(
        val hasCheckedInToday: Boolean = false,
        val todayGain: Int? = null,
        val todayOrder: Int? = null,
        val totalCheckedInUsers: Int = 0,
        val members: List<CheckInMember> = emptyList(),
        val currentPage: Int = 1,
        val hasNextPage: Boolean = false,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val isCheckingIn: Boolean = false,
        val errorMessage: String? = null,
    )

    private val boardState = MutableStateFlow(BoardState(isRefreshing = true))

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val uiState: StateFlow<CheckInBoardUiState> = combine(
        container.sessionRepository.sessionState,
        container.settingsRepository.settings,
        boardState,
    ) { session, settings, board ->
        CheckInBoardUiState(
            currentUid = session?.uid,
            currentUsername = session?.username,
            preferRandomCheckIn = settings.preferRandomCheckIn,
            hasCheckedInToday = board.hasCheckedInToday,
            todayGain = board.todayGain,
            todayOrder = board.todayOrder,
            totalCheckedInUsers = board.totalCheckedInUsers,
            members = board.members,
            currentPage = board.currentPage,
            hasNextPage = board.hasNextPage,
            isRefreshing = board.isRefreshing,
            isLoadingMore = board.isLoadingMore,
            isCheckingIn = board.isCheckingIn,
            errorMessage = board.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CheckInBoardUiState(isRefreshing = true),
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            boardState.update { it.copy(isRefreshing = true, errorMessage = null) }
            loadPage(page = 1, append = false, forceRefresh = true)
        }
    }

    fun loadNextPage() {
        val current = boardState.value
        if (current.isLoadingMore || current.isRefreshing || !current.hasNextPage) return
        viewModelScope.launch {
            boardState.update { it.copy(isLoadingMore = true) }
            loadPage(page = current.currentPage + 1, append = true, forceRefresh = false)
        }
    }

    private suspend fun loadPage(page: Int, append: Boolean, forceRefresh: Boolean) {
        container.checkInRepository.getCheckInBoard(
            page = page,
            forceRefresh = forceRefresh && !append,
        )
            .onSuccess { status ->
                boardState.update { state ->
                    val merged = if (append) {
                        (state.members + status.leaderboard).distinctBy { "${it.rank}_${it.uid}" }
                    } else {
                        status.leaderboard
                    }
                    state.copy(
                        hasCheckedInToday = if (append) {
                            state.hasCheckedInToday || status.hasCheckedInToday
                        } else {
                            status.hasCheckedInToday
                        },
                        todayGain = if (append) {
                            state.todayGain ?: status.todayGain
                        } else {
                            status.todayGain
                        },
                        todayOrder = if (append) {
                            state.todayOrder ?: status.todayOrder
                        } else {
                            status.todayOrder
                        },
                        totalCheckedInUsers = status.totalCheckedInUsers,
                        members = merged,
                        currentPage = page,
                        hasNextPage = status.hasNextPage,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = null,
                    )
                }
            }
            .onFailure { error ->
                boardState.update {
                    it.copy(
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = error.message ?: "无法加载签到排行榜",
                    )
                }
            }
    }

    fun checkIn(randomReward: Boolean) {
        if (boardState.value.isCheckingIn) return
        viewModelScope.launch {
            container.settingsRepository.setPreferRandomCheckIn(randomReward)
            boardState.update { it.copy(isCheckingIn = true) }
            container.checkInRepository.checkIn(randomReward = randomReward)
                .onSuccess { status ->
                    boardState.update { it.copy(isCheckingIn = false) }
                    val gainText = when (val gain = status.todayGain) {
                        null -> "签到完成"
                        0 -> "0 鸡腿"
                        else -> "+$gain 鸡腿"
                    }
                    _events.trySend(status.message?.takeIf { it.isNotBlank() } ?: "签到成功：$gainText")
                    container.sessionRepository.refreshSession()
                    loadPage(page = 1, append = false, forceRefresh = true)
                }
                .onFailure { error ->
                    boardState.update { it.copy(isCheckingIn = false) }
                    _events.trySend(error.message ?: "签到失败，请稍后重试")
                }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CheckInBoardViewModel(container) as T
                }
            }
    }
}

@Composable
fun CheckInBoardScreen(
    onBack: () -> Unit,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit,
    modifier: Modifier = Modifier,
    previewUiState: CheckInBoardUiState? = null,
    viewModel: CheckInBoardViewModel = viewModel(
        factory = CheckInBoardViewModel.factory(LocalAppContainer.current),
    ),
) {
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val ext = NodePulseThemeTokens.extendedColors

    LaunchedEffect(viewModel) {
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val hasTopThreePodium = remember(uiState.members) {
        uiState.members.size >= 3 &&
            uiState.members[0].rank == 1 &&
            uiState.members[1].rank == 2 &&
            uiState.members[2].rank == 3
    }
    val remainingMembers = remember(uiState.members, hasTopThreePodium) {
        if (hasTopThreePodium) uiState.members.drop(3) else uiState.members
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "签到榜",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        if (uiState.totalCheckedInUsers > 0) {
                            Text(
                                text = "今日已有 ${uiState.totalCheckedInUsers} 人签到",
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                color = ext.drumstickGoldEnd,
                            )
                        }
                    }
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
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新签到榜",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                modifier = Modifier.pulseGlassSurface(
                    showTopBorder = false,
                    showBottomBorder = true,
                    tonalAccent = ext.drumstickGoldStart,
                ),
            )
        },
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 36.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "board_hero") {
                    PulseStaggeredEntrance(index = 0) {
                        CheckInSummaryBanner(
                            uiState = uiState,
                            onCheckInFixed = { viewModel.checkIn(randomReward = false) },
                            onCheckInRandom = { viewModel.checkIn(randomReward = true) },
                        )
                    }
                }

                if (uiState.isRefreshing && uiState.members.isEmpty()) {
                    items(count = 5, key = { "board_skel_$it" }) {
                        CheckInMemberSkeletonRow()
                    }
                } else if (uiState.errorMessage != null && uiState.members.isEmpty()) {
                    item(key = "board_error") {
                        HubEmptyPlaceholderCard(
                            icon = Icons.Rounded.EmojiEvents,
                            title = "未能加载签到榜",
                            subtitle = uiState.errorMessage ?: "请检查登录状态或网络连接后下拉刷新。",
                        )
                    }
                } else {
                    if (hasTopThreePodium) {
                        item(key = "board_podium_header") {
                            PulseStaggeredEntrance(index = 1) {
                                PulseSectionHeader(
                                    title = "今日前三甲",
                                    subtitle = "手气与手速并存的社区先锋",
                                    icon = Icons.Rounded.EmojiEvents,
                                    accentColor = ext.drumstickGoldEnd,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                                )
                            }
                        }

                        item(key = "board_podium") {
                            PulseStaggeredEntrance(index = 2) {
                                CheckInTopThreePodium(
                                    first = uiState.members[0],
                                    second = uiState.members[1],
                                    third = uiState.members[2],
                                    currentUid = uiState.currentUid,
                                    onOpenUserProfile = onOpenUserProfile,
                                )
                            }
                        }
                    }

                    if (remainingMembers.isNotEmpty()) {
                        item(key = "board_list_header") {
                            PulseSectionHeader(
                                title = if (hasTopThreePodium) "更多签到排行" else "签到榜单",
                                subtitle = "按今日签到先后顺序实时更新",
                                icon = Icons.Rounded.Leaderboard,
                                accentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                            )
                        }

                        itemsIndexed(
                            items = remainingMembers,
                            key = { _, member -> "${member.rank}_${member.uid}" },
                        ) { index, member ->
                            val isViewer = uiState.currentUid != null && member.uid == uiState.currentUid
                            PulseStaggeredEntrance(
                                index = index,
                                enabled = index < 8,
                            ) {
                                CheckInMemberRow(
                                    member = member,
                                    isViewer = isViewer,
                                    onClick = { onOpenUserProfile(member.uid, member.username) },
                                )
                            }
                        }
                    }

                    if (uiState.hasNextPage || uiState.isLoadingMore) {
                        item(key = "board_next_page") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uiState.isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(ext.cardSurfaceElevated)
                                            .border(1.dp, ext.cardBorder, CircleShape)
                                            .pulseBouncyClickable(
                                                pressedScale = NodePulseMotion.chipPressScale,
                                                role = Role.Button,
                                                onClick = viewModel::loadNextPage,
                                            )
                                            .padding(horizontal = 18.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = "加载下一页（第 ${uiState.currentPage + 1} 页）",
                                            style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.ExpandMore,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
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
}

@Composable
private fun CheckInSummaryBanner(
    uiState: CheckInBoardUiState,
    onCheckInFixed: () -> Unit,
    onCheckInRandom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(accentColor = ext.drumstickGoldStart)
            .drawBehind {
                val center = Offset(size.width * 0.90f, size.height * 0.18f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ext.drumstickGoldStart.copy(alpha = if (isDark) 0.20f else 0.14f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = 135.dp.toPx(),
                    ),
                    radius = 135.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = ext.drumstickGoldStart.copy(alpha = if (isDark) 0.15f else 0.10f),
                    radius = 48.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (uiState.hasCheckedInToday) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(PulseBrushes.drumstickGoldGradient())
                        .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF1F0E00),
                        modifier = Modifier.size(26.dp),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val gain = uiState.todayGain
                    val heroTitle = when {
                        gain == null -> "今日已签到"
                        gain > 0 -> "今日已签到 · 获得 +$gain 鸡腿"
                        else -> "今日已签到 · 获得 0 鸡腿"
                    }
                    Text(
                        text = heroTitle,
                        style = MaterialTheme.typography.titleMedium.withTabularNumbers(),
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (uiState.todayOrder != null) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(ext.drumstickContainer)
                                    .border(
                                        0.8.dp,
                                        ext.drumstickGoldStart.copy(alpha = 0.35f),
                                        CircleShape,
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "今日第 #${uiState.todayOrder} 名",
                                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                    fontWeight = FontWeight.Bold,
                                    color = ext.onDrumstickContainer,
                                )
                            }
                        }
                        if (uiState.totalCheckedInUsers > 0) {
                            Text(
                                text = "全站共 ${uiState.totalCheckedInUsers} 人参与",
                                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(ext.drumstickContainer)
                        .border(
                            0.8.dp,
                            ext.drumstickGoldStart.copy(alpha = 0.35f),
                            PulseArchitecturalShapes.MediumBadge,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Restaurant,
                        contentDescription = null,
                        tint = ext.drumstickGoldEnd,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "每日鸡腿补给",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (uiState.currentUid != null) {
                            "今日尚未领取鸡腿，选择一种签到策略："
                        } else {
                            "登录后即可参与每日鸡腿签到并查看您的今日排名"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (uiState.currentUid != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(CircleShape)
                            .background(ext.cardSurfaceElevated)
                            .border(1.dp, ext.cardBorder, CircleShape)
                            .pulseBouncyClickable(
                                enabled = !uiState.isCheckingIn,
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Button,
                                onClick = onCheckInFixed,
                            )
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "固定 +5 鸡腿",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(CircleShape)
                            .background(PulseBrushes.drumstickGoldGradient())
                            .pulseBouncyClickable(
                                enabled = !uiState.isCheckingIn,
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Button,
                                onClick = onCheckInRandom,
                            )
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (uiState.isCheckingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF1F0E00),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Casino,
                                contentDescription = null,
                                tint = Color(0xFF1F0E00),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "试试手气",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1F0E00),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private data class CheckInMedalSpec(
    val gradientColors: List<Color>,
    val accentColor: Color,
    val badgeInkColor: Color,
    val pedestalTextColor: Color,
)

private fun medalSpecForRank(rank: Int, isDark: Boolean): CheckInMedalSpec = when (rank) {
    1 -> CheckInMedalSpec(
        gradientColors = listOf(Color(0xFFFBBF24), Color(0xFFD97706)),
        accentColor = Color(0xFFF59E0B),
        badgeInkColor = Color(0xFF1F0E00),
        pedestalTextColor = if (isDark) Color(0xFFFCD34D) else Color(0xFF78350F),
    )
    2 -> CheckInMedalSpec(
        gradientColors = listOf(Color(0xFFE2E8F0), Color(0xFF94A3B8)),
        accentColor = Color(0xFF94A3B8),
        badgeInkColor = Color(0xFF0F172A),
        pedestalTextColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155),
    )
    else -> CheckInMedalSpec(
        gradientColors = listOf(Color(0xFFF0AD70), Color(0xFFD17A34)),
        accentColor = Color(0xFFD97706),
        badgeInkColor = Color(0xFF1F0E00),
        pedestalTextColor = if (isDark) Color(0xFFFDBA74) else Color(0xFF7C2D12),
    )
}

@Composable
private fun CheckInTopThreePodium(
    first: CheckInMember,
    second: CheckInMember,
    third: CheckInMember,
    currentUid: Long?,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.CardElevated,
                accentColor = ext.drumstickGoldStart,
            )
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        PodiumColumn(
            member = second,
            isViewer = currentUid != null && second.uid == currentUid,
            pedestalHeight = 32.dp,
            avatarSize = 44.dp,
            medalSpec = medalSpecForRank(rank = 2, isDark = isDark),
            onClick = { onOpenUserProfile(second.uid, second.username) },
            modifier = Modifier.weight(1f),
        )
        PodiumColumn(
            member = first,
            isViewer = currentUid != null && first.uid == currentUid,
            pedestalHeight = 46.dp,
            avatarSize = 54.dp,
            medalSpec = medalSpecForRank(rank = 1, isDark = isDark),
            onClick = { onOpenUserProfile(first.uid, first.username) },
            modifier = Modifier.weight(1.08f),
        )
        PodiumColumn(
            member = third,
            isViewer = currentUid != null && third.uid == currentUid,
            pedestalHeight = 24.dp,
            avatarSize = 42.dp,
            medalSpec = medalSpecForRank(rank = 3, isDark = isDark),
            onClick = { onOpenUserProfile(third.uid, third.username) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PodiumColumn(
    member: CheckInMember,
    isViewer: Boolean,
    pedestalHeight: Dp,
    avatarSize: Dp,
    medalSpec: CheckInMedalSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val medalBrush = Brush.linearGradient(medalSpec.gradientColors)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.cardPressScale,
                role = Role.Button,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(medalBrush)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = "NO.${member.rank}",
                style = NodePulseThemeTokens.extraTypography.microLabel,
                fontWeight = FontWeight.ExtraBold,
                color = medalSpec.badgeInkColor,
            )
        }

        Box(
            modifier = Modifier
                .border(
                    width = 2.dp,
                    brush = medalBrush,
                    shape = CircleShape,
                )
                .padding(3.dp),
        ) {
            PulseAvatar(
                avatarUrl = member.avatarUrl,
                username = member.username,
                size = avatarSize,
                ringWidth = 0.dp,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Text(
                text = member.username,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isViewer) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "我",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(ext.drumstickContainer)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Restaurant,
                contentDescription = null,
                tint = ext.drumstickGoldEnd,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = "+${member.gain}",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
                color = ext.onDrumstickContainer,
            )
        }

        // Pedestal plinth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(pedestalHeight)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            medalSpec.accentColor.copy(alpha = if (isDark) 0.28f else 0.20f),
                            medalSpec.accentColor.copy(alpha = if (isDark) 0.08f else 0.05f),
                        ),
                    ),
                )
                .border(
                    width = 0.8.dp,
                    color = medalSpec.accentColor.copy(alpha = if (isDark) 0.35f else 0.25f),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "#${member.rank}",
                style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                fontWeight = FontWeight.ExtraBold,
                color = medalSpec.pedestalTextColor,
            )
        }
    }
}

@Composable
private fun CheckInMemberRow(
    member: CheckInMember,
    isViewer: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    val containerColor = if (isViewer) {
        blendColor(
            source = ext.cardSurfaceElevated,
            target = MaterialTheme.colorScheme.primaryContainer,
            fraction = if (isDark) 0.35f else 0.45f,
        )
    } else {
        ext.cardSurface
    }
    val borderColor = if (isViewer) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
    } else {
        null
    }

    val medalSpec = if (member.rank in 1..3) medalSpecForRank(member.rank, isDark) else null
    val rankPillBrush: Brush = if (medalSpec != null) {
        Brush.linearGradient(medalSpec.gradientColors)
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    val rankTextColor = medalSpec?.badgeInkColor ?: MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = containerColor,
                borderColor = borderColor,
                borderWidth = if (isViewer) 1.4.dp else 1.dp,
                elevation = if (isViewer) PulseElevation.CardElevated else PulseElevation.Card,
                accentColor = if (isViewer) MaterialTheme.colorScheme.primary else null,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 42.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(rankPillBrush)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "#${member.rank}",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
                color = rankTextColor,
                maxLines = 1,
                softWrap = false,
            )
        }

        PulseAvatar(
            avatarUrl = member.avatarUrl,
            username = member.username,
            size = 36.dp,
        )

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = member.username,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isViewer) FontWeight.Bold else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isViewer) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 1.5.dp),
                ) {
                    Text(
                        text = "我",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(ext.drumstickContainer)
                .border(
                    width = 0.8.dp,
                    color = ext.drumstickGoldStart.copy(alpha = 0.28f),
                    shape = CircleShape,
                )
                .padding(horizontal = 10.dp, vertical = 4.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Restaurant,
                contentDescription = null,
                tint = ext.drumstickGoldEnd,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "+${member.gain}",
                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
                color = ext.onDrumstickContainer,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun CheckInMemberSkeletonRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseShimmerBox(
            modifier = Modifier
                .width(42.dp)
                .height(26.dp),
            shape = RoundedCornerShape(8.dp),
        )
        PulseShimmerBox(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
        )
        PulseShimmerBox(
            modifier = Modifier
                .weight(1f)
                .height(15.dp),
            shape = RoundedCornerShape(5.dp),
        )
        PulseShimmerBox(
            modifier = Modifier
                .width(54.dp)
                .height(24.dp),
            shape = CircleShape,
        )
    }
}
