@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.hub

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.SingletonImageLoader
import com.nodeseek.app.BuildConfig
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.update.AppUpdateState
import com.nodeseek.app.domain.update.UpdateCheckResult
import com.nodeseek.app.domain.update.UpdateDownloadPhase
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogPrimaryButton
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.ObsidianBackground
import com.nodeseek.app.ui.theme.ObsidianOutlineVariant
import com.nodeseek.app.ui.theme.ObsidianPrimary
import com.nodeseek.app.ui.theme.ObsidianSurfaceContainer
import com.nodeseek.app.ui.theme.PaperBackground
import com.nodeseek.app.ui.theme.PaperOutlineVariant
import com.nodeseek.app.ui.theme.PaperPrimary
import com.nodeseek.app.ui.theme.PaperSurfaceContainerLowest
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.PurePitchBlack
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers
import com.nodeseek.app.ui.update.formatUpdateByteSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SeekMailTestResult(
    val isSuccess: Boolean,
    val message: String,
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val session: AccountSession? = null,
    val blockedUsers: List<FloorAuthor> = emptyList(),
    val isLoadingBlockedUsers: Boolean = false,
    val showBlockedUsersDialog: Boolean = false,
    val isSigningOut: Boolean = false,
    val isTestingSeekMail: Boolean = false,
    val isSavingSeekMail: Boolean = false,
    val seekMailTestResult: SeekMailTestResult? = null,
)

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private data class TransientState(
        val blockedUsers: List<FloorAuthor> = emptyList(),
        val isLoadingBlockedUsers: Boolean = false,
        val showBlockedUsersDialog: Boolean = false,
        val isSigningOut: Boolean = false,
        val isTestingSeekMail: Boolean = false,
        val isSavingSeekMail: Boolean = false,
        val seekMailTestResult: SeekMailTestResult? = null,
    )

    private val transient = MutableStateFlow(TransientState())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        container.settingsRepository.settings,
        container.sessionRepository.sessionState,
        transient,
    ) { settings, session, local ->
        SettingsUiState(
            settings = settings,
            session = session,
            blockedUsers = local.blockedUsers,
            isLoadingBlockedUsers = local.isLoadingBlockedUsers,
            showBlockedUsersDialog = local.showBlockedUsersDialog,
            isSigningOut = local.isSigningOut,
            isTestingSeekMail = local.isTestingSeekMail,
            isSavingSeekMail = local.isSavingSeekMail,
            seekMailTestResult = local.seekMailTestResult,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.settingsRepository.setThemeMode(mode) }
    }

    fun setPurePitchAmoled(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setPurePitchAmoled(enabled) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setUseDynamicColor(enabled) }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch { container.settingsRepository.setFontScale(scale) }
    }

    fun setPreferRandomCheckIn(preferRandom: Boolean) {
        viewModelScope.launch { container.settingsRepository.setPreferRandomCheckIn(preferRandom) }
    }

    fun setIncludeBlockedTopics(include: Boolean) {
        viewModelScope.launch { container.settingsRepository.setIncludeBlockedTopics(include) }
    }

    fun setPreferRssFallback(preferRss: Boolean) {
        viewModelScope.launch { container.settingsRepository.setPreferRssFallback(preferRss) }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAutoCheckUpdates(enabled) }
    }

    fun setIncludePrereleaseUpdates(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setIncludePrereleaseUpdates(enabled) }
    }

    fun checkForUpdates() {
        if (container.appUpdateManager.state.value.isChecking) return
        viewModelScope.launch {
            when (val result = container.appUpdateManager.checkForUpdates(manual = true)) {
                is UpdateCheckResult.UpdateAvailable -> Unit
                is UpdateCheckResult.UpToDate -> {
                    _events.trySend("当前已是最新版本 (v${result.currentVersionName})")
                }
                is UpdateCheckResult.Error -> {
                    _events.trySend(result.message)
                }
            }
        }
    }

    fun saveNodeImageApiKey(apiKey: String) {
        viewModelScope.launch {
            container.settingsRepository.setNodeImageApiKey(apiKey)
            _events.trySend(if (apiKey.isBlank()) "已清除 NodeImage API Key" else "已保存 NodeImage API Key")
        }
    }

    fun clearSeekMailTestResult() {
        if (transient.value.seekMailTestResult != null) {
            transient.update { it.copy(seekMailTestResult = null) }
        }
    }

    fun testSeekMailConnection(candidateKey: String) {
        val trimmed = candidateKey.trim()
        if (trimmed.isEmpty()) {
            val msg = "请先输入 Seek 邮箱 API Key（sk_live_...）"
            transient.update {
                it.copy(seekMailTestResult = SeekMailTestResult(isSuccess = false, message = msg))
            }
            _events.trySend(msg)
            return
        }
        if (transient.value.isTestingSeekMail || transient.value.isSavingSeekMail) return
        viewModelScope.launch {
            transient.update { it.copy(isTestingSeekMail = true, seekMailTestResult = null) }
            container.seekMailRepository.verifyApiKey(trimmed)
                .onSuccess { status ->
                    val mailboxCount = status.mailboxes.size
                    val unreadLabel = if (status.hasMoreUnread) {
                        "${status.unreadSampleCount}+"
                    } else {
                        "${status.unreadSampleCount}"
                    }
                    val msg = "连接成功 · 已加载 $mailboxCount 个邮箱（未读 $unreadLabel 封）"
                    transient.update {
                        it.copy(
                            isTestingSeekMail = false,
                            seekMailTestResult = SeekMailTestResult(isSuccess = true, message = msg),
                        )
                    }
                    _events.trySend(msg)
                }
                .onFailure { error ->
                    val msg = error.message?.takeIf { it.isNotBlank() } ?: "连接测试失败，请检查 API Key 与网络"
                    transient.update {
                        it.copy(
                            isTestingSeekMail = false,
                            seekMailTestResult = SeekMailTestResult(isSuccess = false, message = msg),
                        )
                    }
                    _events.trySend(msg)
                }
        }
    }

    fun saveSeekMailApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (transient.value.isSavingSeekMail || transient.value.isTestingSeekMail) return
        viewModelScope.launch {
            transient.update { it.copy(isSavingSeekMail = true) }
            try {
                container.settingsRepository.setSeekMailApiKey(trimmed)
                if (trimmed.isEmpty()) {
                    container.seekMailRepository.clearCache()
                    transient.update {
                        it.copy(
                            isSavingSeekMail = false,
                            seekMailTestResult = null,
                        )
                    }
                    _events.trySend("已清除 Seek 邮箱 API Key")
                } else {
                    transient.update { it.copy(isSavingSeekMail = false) }
                    _events.trySend("已保存 Seek 邮箱 API Key")
                }
            } finally {
                transient.update { it.copy(isSavingSeekMail = false) }
            }
        }
    }

    fun openBlockedUsersManager() {
        transient.update { it.copy(showBlockedUsersDialog = true, isLoadingBlockedUsers = true) }
        viewModelScope.launch {
            container.userRepository.getBlockedUsers()
                .onSuccess { list ->
                    transient.update {
                        it.copy(blockedUsers = list, isLoadingBlockedUsers = false)
                    }
                }
                .onFailure { error ->
                    transient.update { it.copy(isLoadingBlockedUsers = false) }
                    _events.trySend(error.message ?: "无法加载屏蔽名单")
                }
        }
    }

    fun dismissBlockedUsersManager() {
        transient.update { it.copy(showBlockedUsersDialog = false) }
    }

    fun addBlockedUser(username: String) {
        val trimmed = username.trim().removePrefix("@")
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            container.userRepository.blockUserByName(trimmed)
                .onSuccess {
                    _events.trySend("已将 $trimmed 加入屏蔽名单")
                    container.userRepository.getBlockedUsers()
                        .onSuccess { list -> transient.update { it.copy(blockedUsers = list) } }
                }
                .onFailure { error ->
                    _events.trySend(error.message ?: "添加屏蔽用户失败")
                }
        }
    }

    fun unblockUser(user: FloorAuthor) {
        viewModelScope.launch {
            container.userRepository.unblockUserById(user.uid)
                .onSuccess {
                    transient.update { s ->
                        s.copy(blockedUsers = s.blockedUsers.filterNot { it.uid == user.uid })
                    }
                    _events.trySend("已解除对 ${user.username} 的屏蔽")
                }
                .onFailure { error ->
                    _events.trySend(error.message ?: "解除屏蔽失败")
                }
        }
    }

    fun clearReadHistory() {
        viewModelScope.launch {
            container.bookmarkAndHistoryRepository.clearReadHistory()
            _events.trySend("浏览记录已清空")
        }
    }

    fun notifyImageCacheCleared() {
        _events.trySend("图片内存与磁盘缓存已清理")
    }

    fun signOut(
        revokeRemoteSession: Boolean = true,
        onSignedOut: () -> Unit = {},
    ) {
        if (transient.value.isSigningOut) return
        viewModelScope.launch {
            transient.update { it.copy(isSigningOut = true) }
            val result = if (revokeRemoteSession) {
                container.sessionRepository.signOut()
            } else {
                container.sessionRepository.signOutLocalOnly()
            }
            result
                .onSuccess {
                    transient.update { it.copy(isSigningOut = false) }
                    _events.trySend(
                        if (revokeRemoteSession) {
                            "已安全退出当前账号"
                        } else {
                            "已仅退出本机登录状态，保留其他设备会话"
                        },
                    )
                    onSignedOut()
                }
                .onFailure { error ->
                    transient.update { it.copy(isSigningOut = false) }
                    _events.trySend(error.message ?: "退出账号时遇到问题")
                }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(container) as T
                }
            }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenUserProfile: ((uid: Long, usernameHint: String) -> Unit)? = null,
    onOpenRadar: (initialTab: Int) -> Unit = {},
    onOpenSeekMail: () -> Unit = {},
    previewUiState: SettingsUiState? = null,
    previewRadarPollingSettings: RadarPollingSettings? = null,
    previewRadarUnreadCount: Int? = null,
    previewUpdateState: AppUpdateState? = null,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(LocalAppContainer.current),
    ),
) {
    val appContainer = LocalAppContainer.current
    val observedRadarPollingSettings by appContainer.radarRepository.pollingSettingsFlow.collectAsStateWithLifecycle(
        initialValue = RadarPollingSettings(),
    )
    val radarPollingSettings = previewRadarPollingSettings ?: observedRadarPollingSettings
    val observedRadarUnreadCount by appContainer.radarRepository.unreadAlertCountFlow.collectAsStateWithLifecycle(
        initialValue = 0,
    )
    val radarUnreadCount = previewRadarUnreadCount ?: observedRadarUnreadCount
    val observedUpdateState by appContainer.appUpdateManager.state.collectAsStateWithLifecycle()
    val updateState = previewUpdateState ?: observedUpdateState
    val settingsScope = rememberCoroutineScope()
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val settings = uiState.settings
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }

    var apiKeyDraft by rememberSaveable(settings.nodeImageApiKey) {
        mutableStateOf(settings.nodeImageApiKey)
    }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var seekMailApiKeyDraft by rememberSaveable(settings.seekMailApiKey) {
        mutableStateOf(settings.seekMailApiKey)
    }
    var showSeekMailApiKey by rememberSaveable { mutableStateOf(false) }
    var showSignOutConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.pulseGlassSurface(
                    showTopBorder = false,
                    showBottomBorder = true,
                    tonalAccent = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "设置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "外观 · 排版 · 社区与本地存储",
                            style = extraType.microLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                    val activeModeLabel = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> "系统"
                        ThemeMode.PAPER_LIGHT -> "Paper"
                        ThemeMode.OBSIDIAN_DARK -> if (settings.purePitchAmoled) "AMOLED" else "Obsidian"
                    }
                    PulseAccentPill(
                        text = activeModeLabel,
                        icon = when (settings.themeMode) {
                            ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                            ThemeMode.PAPER_LIGHT -> Icons.Rounded.LightMode
                            ThemeMode.OBSIDIAN_DARK -> Icons.Rounded.DarkMode
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = if (isDark) 0.72f else 0.85f,
                        ),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 36.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── 1. Appearance & Theme Bento Card ──
            item(key = "settings_appearance") {
                PulseStaggeredEntrance(index = 0) {
                    SettingsBentoSection(
                        icon = Icons.Rounded.Palette,
                        title = "外观与主题",
                        subtitle = "浅色、深色模式与动态配色",
                        accentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            VisualThemeOptionCard(
                                selected = settings.themeMode == ThemeMode.SYSTEM,
                                label = "跟随系统",
                                previewMode = ThemeMode.SYSTEM,
                                isAmoledPreview = settings.purePitchAmoled,
                                onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                                modifier = Modifier.weight(1f),
                            )
                            VisualThemeOptionCard(
                                selected = settings.themeMode == ThemeMode.PAPER_LIGHT,
                                label = "浅色模式",
                                previewMode = ThemeMode.PAPER_LIGHT,
                                isAmoledPreview = false,
                                onClick = { viewModel.setThemeMode(ThemeMode.PAPER_LIGHT) },
                                modifier = Modifier.weight(1f),
                            )
                            VisualThemeOptionCard(
                                selected = settings.themeMode == ThemeMode.OBSIDIAN_DARK,
                                label = "深色模式",
                                previewMode = ThemeMode.OBSIDIAN_DARK,
                                isAmoledPreview = settings.purePitchAmoled,
                                onClick = { viewModel.setThemeMode(ThemeMode.OBSIDIAN_DARK) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        PulseGradientDivider()

                        SettingsToggleRow(
                            icon = Icons.Rounded.DarkMode,
                            accentColor = MaterialTheme.colorScheme.primary,
                            title = "纯黑背景 (AMOLED)",
                            subtitle = "夜间模式下使用纯黑背景以降低 OLED 屏幕功耗",
                            checked = settings.purePitchAmoled,
                            onCheckedChange = viewModel::setPurePitchAmoled,
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SettingsToggleRow(
                                icon = Icons.Rounded.AutoAwesome,
                                accentColor = MaterialTheme.colorScheme.secondary,
                                title = "Material You 壁纸动态取色",
                                subtitle = "根据系统壁纸派生色彩，并与 15 个版块色板进行 12% 语义调和",
                                checked = settings.useDynamicColor,
                                onCheckedChange = viewModel::setUseDynamicColor,
                            )
                        }
                    }
                }
            }

            // ── 2. Typography & Font Scale Card ──
            item(key = "settings_typography") {
                val pct = (settings.fontScale * 100f).roundToInt()
                PulseStaggeredEntrance(index = 1) {
                    SettingsBentoSection(
                        icon = Icons.Rounded.FormatSize,
                        title = "阅读字号与排版",
                        subtitle = "当前正文字号缩放：$pct%",
                        accentColor = MaterialTheme.colorScheme.secondary,
                        headerTrailing = {
                            PulseAccentPill(
                                text = "$pct%",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                    ) {
                        Slider(
                            value = settings.fontScale,
                            onValueChange = viewModel::setFontScale,
                            valueRange = 0.85f..1.30f,
                            steps = 8,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                0.85f to "85%",
                                1.00f to "100%",
                                1.15f to "115%",
                                1.30f to "130%",
                            ).forEach { (scale, label) ->
                                val isPresetActive = kotlin.math.abs(settings.fontScale - scale) < 0.03f
                                val chipBg by animateColorAsState(
                                    targetValue = if (isPresetActive) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    label = "fontPresetBg",
                                )
                                val chipBorder by animateColorAsState(
                                    targetValue = if (isPresetActive) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        ext.cardBorder
                                    },
                                    label = "fontPresetBorder",
                                )
                                val presetInteraction = remember { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .pulsePressScale(presetInteraction)
                                        .clip(CircleShape)
                                        .background(chipBg)
                                        .border(
                                            width = if (isPresetActive) 1.2.dp else 0.8.dp,
                                            color = chipBorder,
                                            shape = CircleShape,
                                        )
                                        .clickable(
                                            interactionSource = presetInteraction,
                                            indication = LocalIndication.current,
                                            role = Role.Button,
                                            onClick = { viewModel.setFontScale(scale) },
                                        )
                                        .padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                        fontWeight = if (isPresetActive) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isPresetActive) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseCard(
                                    shape = RoundedCornerShape(14.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    elevation = PulseElevation.None,
                                    accentColor = MaterialTheme.colorScheme.secondary,
                                )
                                .padding(13.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "排版预览",
                                    style = extraType.microLabel,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                                Text(
                                    text = "${(16f * settings.fontScale).roundToInt()}sp",
                                    style = extraType.microLabel,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "DMIT LAX Pro 晚高峰三网 CN2 GIA 回程延迟稳定在 138ms，零丢包。",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = (16f * settings.fontScale).sp,
                                    lineHeight = (26.5f * settings.fontScale).sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            // ── 3. Community, Check-In & RSS Fallback Card ──
            item(key = "settings_community") {
                PulseStaggeredEntrance(index = 2) {
                    SettingsBentoSection(
                        icon = Icons.Rounded.RssFeed,
                        title = "浏览与签到设置",
                        subtitle = "管理签到奖励模式、访客 RSS 降级与内容过滤",
                        accentColor = ext.drumstickGoldEnd,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "默认签到策略",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "在「我的」个人中心点击「立即签到」时使用，并与签到卡片模式保持同步",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val preferRandom = settings.preferRandomCheckIn
                            CheckInPreferenceCard(
                                selected = !preferRandom,
                                icon = Icons.Rounded.Shield,
                                title = "固定 +5 鸡腿",
                                subtitle = "每日稳健固定收益",
                                accentColor = ext.onDrumstickContainer,
                                selectedContainer = ext.drumstickContainer,
                                onClick = { viewModel.setPreferRandomCheckIn(false) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                            CheckInPreferenceCard(
                                selected = preferRandom,
                                icon = Icons.Rounded.Casino,
                                title = "🎲 试试手气",
                                subtitle = "随机鸡腿波动奖励",
                                accentColor = ext.stardustAccent,
                                selectedContainer = ext.stardustContainer,
                                onClick = { viewModel.setPreferRandomCheckIn(true) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }

                        PulseGradientDivider()

                        SettingsToggleRow(
                            icon = Icons.Rounded.VisibilityOff,
                            accentColor = MaterialTheme.colorScheme.tertiary,
                            title = "显示已屏蔽成员的帖子与楼层",
                            subtitle = "关闭时将在信息流与主题详情页自动折叠屏蔽用户内容",
                            checked = settings.includeBlockedTopics,
                            onCheckedChange = viewModel::setIncludeBlockedTopics,
                        )

                        SettingsToggleRow(
                            icon = Icons.Rounded.Bolt,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            title = "未登录时优先启用 RSS 极速访客流",
                            subtitle = "通过 rss.nodeseek.com 零验证快速拉取公开版块最新主题",
                            checked = settings.preferRssFallback,
                            onCheckedChange = viewModel::setPreferRssFallback,
                        )

                        if (uiState.session != null) {
                            val blocklistInteraction = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pulsePressScale(blocklistInteraction)
                                    .pulseCard(
                                        shape = RoundedCornerShape(14.dp),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                        elevation = PulseElevation.None,
                                    )
                                    .clickable(
                                        interactionSource = blocklistInteraction,
                                        indication = LocalIndication.current,
                                        role = Role.Button,
                                        onClick = viewModel::openBlockedUsersManager,
                                    )
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(PulseArchitecturalShapes.SmallChip)
                                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Block,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Text(
                                    text = "管理屏蔽用户名单",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
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
                }
            }

            // ── 3.5 Pulse Radar Polling & Notification Card ──
            item(key = "settings_radar_notifications") {
                PulseStaggeredEntrance(index = 3) {
                    SettingsBentoSection(
                        icon = Icons.Rounded.NotificationsActive,
                        title = "提醒轮询与手机通知",
                        subtitle = "控制本地关键词监测、追踪回复频率及私信通知行为",
                        accentColor = MaterialTheme.colorScheme.primary,
                        headerTrailing = {
                            PulseAccentPill(
                                text = if (radarPollingSettings.isEnabled) "运行中" else "已关闭",
                                containerColor = if (radarPollingSettings.isEnabled) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (radarPollingSettings.isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                    ) {
                        SettingsToggleRow(
                            icon = Icons.Rounded.Radar,
                            accentColor = MaterialTheme.colorScheme.primary,
                            title = "启用提醒轮询与脉搏雷达",
                            subtitle = "在前台与后台期间定时检查新帖命中与追踪楼层回复",
                            checked = radarPollingSettings.isEnabled,
                            onCheckedChange = { enabled ->
                                settingsScope.launch {
                                    appContainer.radarRepository.updatePollingSettings {
                                        it.copy(isEnabled = enabled)
                                    }
                                }
                            },
                        )

                        SettingsToggleRow(
                            icon = Icons.Rounded.Mail,
                            accentColor = MaterialTheme.colorScheme.tertiary,
                            title = "私信、回复与 @提及 系统推送",
                            subtitle = "轮询到新的未读站内互动或私信时发送原生手机通知",
                            checked = radarPollingSettings.inboxPushEnabled,
                            onCheckedChange = { enabled ->
                                settingsScope.launch {
                                    appContainer.radarRepository.updatePollingSettings {
                                        it.copy(inboxPushEnabled = enabled)
                                    }
                                }
                            },
                        )

                        PulseGradientDivider()

                        SettingsIntervalPresetRow(
                            title = "前台检查间隔",
                            optionsMinutes = listOf(1, 2, 5, 10),
                            selectedMinutes = radarPollingSettings.foregroundIntervalMinutes,
                            enabled = radarPollingSettings.isEnabled,
                            onSelect = { minutes ->
                                settingsScope.launch {
                                    appContainer.radarRepository.updatePollingSettings {
                                        it.copy(foregroundIntervalMinutes = minutes)
                                    }
                                }
                            },
                        )

                        SettingsIntervalPresetRow(
                            title = "后台检查间隔",
                            optionsMinutes = listOf(3, 5, 10, 15),
                            selectedMinutes = radarPollingSettings.backgroundIntervalMinutes,
                            enabled = radarPollingSettings.isEnabled,
                            onSelect = { minutes ->
                                settingsScope.launch {
                                    appContainer.radarRepository.updatePollingSettings {
                                        it.copy(backgroundIntervalMinutes = minutes)
                                    }
                                }
                            },
                        )

                        Text(
                            text = "说明：定时检查仅在客户端前台运行或退至手机后台期间工作，完全退出应用后自动停止；手机「勿扰模式」与「电池优化/后台省电策略」会影响通知到达及时性。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )

                        val radarEntryInteraction = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulsePressScale(radarEntryInteraction)
                                .pulseCard(
                                    shape = RoundedCornerShape(14.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    elevation = PulseElevation.None,
                                    accentColor = if (radarUnreadCount > 0) MaterialTheme.colorScheme.primary else null,
                                )
                                .clickable(
                                    interactionSource = radarEntryInteraction,
                                    indication = LocalIndication.current,
                                    role = Role.Button,
                                    onClick = { onOpenRadar(0) },
                                )
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(PulseArchitecturalShapes.SmallChip)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Radar,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = "打开脉搏雷达与本地提醒中心",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (radarUnreadCount > 0) {
                                PulseAccentPill(
                                    text = "$radarUnreadCount 未读",
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // ── 4. NodeImage Image Host API Key Card ──
            item(key = "settings_nodeimage") {
                val hasKey = settings.nodeImageApiKey.isNotBlank()
                PulseStaggeredEntrance(index = 3) {
                    SettingsBentoSection(
                        icon = Icons.Rounded.Image,
                        title = "NodeImage 官方图床配置",
                        subtitle = "配置 api.nodeimage.com 的 X-API-Key 以在编辑器一键上传配图",
                        accentColor = MaterialTheme.colorScheme.secondary,
                        headerTrailing = {
                            PulseAccentPill(
                                text = if (hasKey) "已配置" else "未配置",
                                containerColor = if (hasKey) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (hasKey) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                    ) {
                        OutlinedTextField(
                            value = apiKeyDraft,
                            onValueChange = { apiKeyDraft = it },
                            label = { Text("NodeImage X-API-Key") },
                            placeholder = { Text("粘贴您的图床 API Key…") },
                            shape = RoundedCornerShape(14.dp),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            visualTransformation = if (showApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        imageVector = if (showApiKey) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = if (showApiKey) "隐藏密钥" else "显示密钥",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            val saveInteraction = remember { MutableInteractionSource() }
                            FilledTonalButton(
                                onClick = { viewModel.saveNodeImageApiKey(apiKeyDraft) },
                                interactionSource = saveInteraction,
                                shape = CircleShape,
                                modifier = Modifier.pulsePressScale(saveInteraction),
                            ) {
                                Text(
                                    text = "保存 API Key",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            // ── 4.5. Seek Mail (seek.li) OpenAPI Key & Entry Card ──
            item(key = "settings_seekmail") {
                val hasSeekMailKey = settings.seekMailApiKey.isNotBlank()
                PulseStaggeredEntrance(index = 4) {
                    SettingsBentoSection(
                        icon = Icons.Rounded.AlternateEmail,
                        title = "Seek 邮箱配置",
                        subtitle = "配置 seek.li OpenAPI Key，支持收件阅读、验证码提取与别名管理",
                        accentColor = MaterialTheme.colorScheme.secondary,
                        headerTrailing = {
                            PulseAccentPill(
                                text = if (hasSeekMailKey) "已配置" else "未配置",
                                containerColor = if (hasSeekMailKey) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (hasSeekMailKey) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                    ) {
                        OutlinedTextField(
                            value = seekMailApiKeyDraft,
                            onValueChange = {
                                seekMailApiKeyDraft = it
                                viewModel.clearSeekMailTestResult()
                            },
                            label = { Text("Seek 邮箱 API Key") },
                            placeholder = { Text("粘贴您的 sk_live_... API Key…") },
                            shape = RoundedCornerShape(14.dp),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            visualTransformation = if (showSeekMailApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showSeekMailApiKey = !showSeekMailApiKey }) {
                                    Icon(
                                        imageVector = if (showSeekMailApiKey) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = if (showSeekMailApiKey) "隐藏密钥" else "显示密钥",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        AnimatedVisibility(visible = uiState.seekMailTestResult != null) {
                            uiState.seekMailTestResult?.let { testResult ->
                                val bannerContainer = if (testResult.isSuccess) {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (isDark) 0.55f else 0.72f)
                                } else {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = if (isDark) 0.55f else 0.75f)
                                }
                                val bannerContent = if (testResult.isSuccess) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(bannerContainer)
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = if (testResult.isSuccess) {
                                            Icons.Rounded.CheckCircle
                                        } else {
                                            Icons.Rounded.ErrorOutline
                                        },
                                        contentDescription = null,
                                        tint = bannerContent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = testResult.message,
                                        style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                                        color = bannerContent,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val testInteraction = remember { MutableInteractionSource() }
                            OutlinedButton(
                                onClick = { viewModel.testSeekMailConnection(seekMailApiKeyDraft) },
                                enabled = !uiState.isTestingSeekMail && !uiState.isSavingSeekMail,
                                interactionSource = testInteraction,
                                shape = CircleShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .pulsePressScale(testInteraction),
                            ) {
                                if (uiState.isTestingSeekMail) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Bolt,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = "测试连接",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }

                            val saveSeekMailInteraction = remember { MutableInteractionSource() }
                            FilledTonalButton(
                                onClick = {
                                    val trimmed = seekMailApiKeyDraft.trim()
                                    seekMailApiKeyDraft = trimmed
                                    viewModel.saveSeekMailApiKey(trimmed)
                                },
                                enabled = !uiState.isSavingSeekMail && !uiState.isTestingSeekMail,
                                interactionSource = saveSeekMailInteraction,
                                shape = CircleShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .pulsePressScale(saveSeekMailInteraction),
                            ) {
                                Text(
                                    text = "保存 API Key",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }

                        val docsInteraction = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulsePressScale(docsInteraction)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable(
                                    interactionSource = docsInteraction,
                                    indication = LocalIndication.current,
                                    role = Role.Button,
                                    onClick = {
                                        runCatching {
                                            uriHandler.openUri("https://seek.li/manage/developer/docs")
                                        }.onFailure {
                                            settingsScope.launch {
                                                snackbarHostState.showSnackbar("无法打开浏览器，请访问 https://seek.li/manage/developer/docs")
                                            }
                                        }
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                Text(
                                    text = "Seek 邮箱开发者文档与密钥管理",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "https://seek.li/manage/developer/docs",
                                    style = extraType.microLabel,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        val seekMailEntryInteraction = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulsePressScale(seekMailEntryInteraction)
                                .pulseCard(
                                    shape = RoundedCornerShape(14.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    elevation = PulseElevation.None,
                                    accentColor = if (hasSeekMailKey) MaterialTheme.colorScheme.secondary else null,
                                )
                                .clickable(
                                    interactionSource = seekMailEntryInteraction,
                                    indication = LocalIndication.current,
                                    role = Role.Button,
                                    onClick = onOpenSeekMail,
                                )
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(PulseArchitecturalShapes.SmallChip)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Mail,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = "打开 Seek 邮箱",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (hasSeekMailKey) {
                                PulseAccentPill(
                                    text = "已连接",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // ── 5. Cache & Account Session Card ──
            item(key = "settings_storage_account") {
                PulseStaggeredEntrance(index = 5) {
                    SettingsBentoSection(
                        icon = Icons.Rounded.CleaningServices,
                        title = "本地存储与会话安全",
                        subtitle = "管理图片缓存、浏览记录与当前登录会话",
                        accentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StorageActionTile(
                                icon = Icons.Rounded.Image,
                                title = "清理图片缓存",
                                subtitle = "内存与磁盘缓存",
                                accentColor = MaterialTheme.colorScheme.primary,
                                onClick = {
                                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                        runCatching {
                                            val loader = SingletonImageLoader.get(context)
                                            loader.memoryCache?.clear()
                                            loader.diskCache?.clear()
                                        }
                                    }
                                    viewModel.notifyImageCacheCleared()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )

                            StorageActionTile(
                                icon = Icons.Rounded.History,
                                title = "清空浏览记录",
                                subtitle = "本地阅读历史",
                                accentColor = MaterialTheme.colorScheme.secondary,
                                onClick = viewModel::clearReadHistory,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }

                        val session = uiState.session
                        if (session != null) {
                            val rankSpec = NodePulseThemeTokens.rankSpec(session.level)
                            val accountCardInteraction = remember { MutableInteractionSource() }
                            PulseGradientDivider()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (onOpenUserProfile != null) {
                                            Modifier.pulsePressScale(accountCardInteraction)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .pulseHeroCard(
                                        accentColor = rankSpec.glowColor,
                                        shape = PulseArchitecturalShapes.EditorialCard,
                                        elevation = PulseElevation.Subtle,
                                    )
                                    .then(
                                        if (onOpenUserProfile != null) {
                                            Modifier.clickable(
                                                interactionSource = accountCardInteraction,
                                                indication = LocalIndication.current,
                                                role = Role.Button,
                                                onClick = {
                                                    onOpenUserProfile(session.uid, session.username)
                                                },
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                PulseAvatar(
                                    avatarUrl = session.avatarUrl,
                                    username = session.username,
                                    size = 42.dp,
                                    rankLevel = session.level,
                                    ringWidth = 2.dp,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = "当前登录：${session.username}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        RankMicroBadge(level = session.level)
                                    }
                                    Text(
                                        text = "UID #${session.uid} · 退出将同步清理本地会话 Cookie",
                                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (onOpenUserProfile != null) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            val signOutInteraction = remember { MutableInteractionSource() }
                            Button(
                                onClick = { showSignOutConfirm = true },
                                enabled = !uiState.isSigningOut,
                                interactionSource = signOutInteraction,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .pulsePressScale(signOutInteraction),
                            ) {
                                if (uiState.isSigningOut) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = "退出当前账号",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            // ── 6. App Version & GitHub Release Update Card ──
            item(key = "settings_app_update") {
                val latestRelease = updateState.latestRelease
                val downloadPhase = updateState.downloadPhase
                val githubReleasesUrl = "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases"
                val statusPillText = when (downloadPhase) {
                    is UpdateDownloadPhase.Downloading -> {
                        val pct = downloadPhase.progressFraction?.let { (it * 100f).roundToInt().coerceIn(0, 100) }
                        if (pct != null) "下载中 $pct%" else "下载中"
                    }
                    is UpdateDownloadPhase.ReadyToInstall -> {
                        if (downloadPhase.requiresInstallPermission) "待授权安装" else "待安装"
                    }
                    else -> {
                        if (updateState.hasUpdate && latestRelease != null) {
                            "新版本 ${latestRelease.tagName}"
                        } else {
                            "v${updateState.currentVersionName}"
                        }
                    }
                }
                val hasHighlightedStatus = updateState.hasUpdate ||
                    downloadPhase is UpdateDownloadPhase.Downloading ||
                    downloadPhase is UpdateDownloadPhase.ReadyToInstall

                PulseStaggeredEntrance(index = 6) {
                    SettingsBentoSection(
                        icon = Icons.Rounded.SystemUpdate,
                        title = "应用版本与更新",
                        subtitle = "通过 GitHub Releases 检查并应用内升级客户端",
                        accentColor = if (hasHighlightedStatus) ext.drumstickGoldEnd else MaterialTheme.colorScheme.secondary,
                        headerTrailing = {
                            PulseAccentPill(
                                text = statusPillText,
                                containerColor = if (hasHighlightedStatus) {
                                    ext.drumstickContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = if (hasHighlightedStatus) {
                                    ext.onDrumstickContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            )
                        },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseCard(
                                    shape = RoundedCornerShape(15.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    elevation = PulseElevation.None,
                                    accentColor = if (updateState.hasUpdate) ext.drumstickGoldEnd else null,
                                )
                                .padding(13.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "Anode v${updateState.currentVersionName}",
                                        style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val checkSummary = when {
                                        updateState.isChecking -> "正在从 GitHub Releases 获取最新版本…"
                                        updateState.hasUpdate && latestRelease != null ->
                                            "发现新版本 ${latestRelease.tagName}" + (
                                                latestRelease.apkAsset?.sizeBytes
                                                    ?.takeIf { it > 0L }
                                                    ?.let { " · ${formatUpdateByteSize(it)}" }
                                                    .orEmpty()
                                                )
                                        !updateState.checkError.isNullOrBlank() -> updateState.checkError!!
                                        updateState.lastCheckedAtMillis > 0L -> "当前已是最新发布版本"
                                        else -> "Build ${updateState.currentVersionCode} · 更新源 GitHub Releases"
                                    }
                                    Text(
                                        text = checkSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (!updateState.checkError.isNullOrBlank() && !updateState.hasUpdate) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }

                            if (downloadPhase is UpdateDownloadPhase.Downloading) {
                                val fraction = downloadPhase.progressFraction
                                if (fraction != null) {
                                    LinearProgressIndicator(
                                        progress = { fraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(CircleShape),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(CircleShape),
                                    )
                                }
                            }

                            if (updateState.hasUpdate && latestRelease != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            when (downloadPhase) {
                                                is UpdateDownloadPhase.ReadyToInstall -> {
                                                    if (downloadPhase.requiresInstallPermission) {
                                                        if (!appContainer.appUpdateManager.openUnknownSourcesSettings()) {
                                                            settingsScope.launch {
                                                                snackbarHostState.showSnackbar("无法打开系统安装权限设置")
                                                            }
                                                        }
                                                    } else if (!appContainer.appUpdateManager.installReadyApk()) {
                                                        appContainer.appUpdateManager.openUpdateDialog()
                                                    }
                                                }
                                                else -> appContainer.appUpdateManager.openUpdateDialog()
                                            }
                                        },
                                        shape = CircleShape,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = when (downloadPhase) {
                                                is UpdateDownloadPhase.ReadyToInstall ->
                                                    if (downloadPhase.requiresInstallPermission) {
                                                        Icons.Rounded.Security
                                                    } else {
                                                        Icons.Rounded.InstallMobile
                                                    }
                                                is UpdateDownloadPhase.Downloading -> Icons.Rounded.Download
                                                else -> Icons.Rounded.NewReleases
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = when (downloadPhase) {
                                                is UpdateDownloadPhase.Downloading -> "查看下载进度"
                                                is UpdateDownloadPhase.ReadyToInstall ->
                                                    if (downloadPhase.requiresInstallPermission) "去开启安装权限" else "立即安装"
                                                is UpdateDownloadPhase.Failed -> "查看更新与重试"
                                                UpdateDownloadPhase.Idle -> "立即更新 / 查看日志"
                                            },
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        PulseGradientDivider()

                        SettingsToggleRow(
                            icon = Icons.Rounded.NewReleases,
                            accentColor = MaterialTheme.colorScheme.primary,
                            title = "启动时自动检查更新",
                            subtitle = "每次冷启动后在后台静默检查 GitHub Releases 新版本",
                            checked = settings.autoCheckUpdates,
                            onCheckedChange = viewModel::setAutoCheckUpdates,
                        )

                        SettingsToggleRow(
                            icon = Icons.Rounded.Science,
                            accentColor = ext.stardustAccent,
                            title = "接收预发布版本 (Pre-release)",
                            subtitle = "开启后将同时检查 beta / rc 等预览测试版本",
                            checked = settings.includePrereleaseUpdates,
                            onCheckedChange = viewModel::setIncludePrereleaseUpdates,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FilledTonalButton(
                                onClick = viewModel::checkForUpdates,
                                enabled = !updateState.isChecking,
                                shape = CircleShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                if (updateState.isChecking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = if (updateState.isChecking) "检查中…" else "检查更新",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        uriHandler.openUri(githubReleasesUrl)
                                    }.onFailure {
                                        settingsScope.launch {
                                            snackbarHostState.showSnackbar("无法打开浏览器，请访问 $githubReleasesUrl")
                                        }
                                    }
                                },
                                shape = CircleShape,
                                modifier = Modifier.height(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Releases",
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (uiState.showBlockedUsersDialog) {
        BlockedUsersManagerDialog(
            blockedUsers = uiState.blockedUsers,
            isLoading = uiState.isLoadingBlockedUsers,
            onAddUsername = viewModel::addBlockedUser,
            onUnblock = viewModel::unblockUser,
            onDismiss = viewModel::dismissBlockedUsersManager,
        )
    }

    if (showSignOutConfirm) {
        var localOnlySignOut by rememberSaveable { mutableStateOf(false) }
        PulseConfirmDialog(
            onDismissRequest = { showSignOutConfirm = false },
            icon = Icons.AutoMirrored.Rounded.Logout,
            tone = PulseDialogTone.DESTRUCTIVE,
            title = "确认退出当前账号？",
            message = "退出后将同步清除 WebView 与网络层的登录 Cookie，本地收藏快照与草稿仍将安全保留。",
            confirmText = "确认退出",
            dismissText = "取消",
            onConfirm = {
                showSignOutConfirm = false
                viewModel.signOut(revokeRemoteSession = !localOnlySignOut)
            },
            extraContent = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable(role = Role.Checkbox) { localOnlySignOut = !localOnlySignOut }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = localOnlySignOut,
                        onCheckedChange = { localOnlySignOut = it },
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = "仅清理本机 Cookie（不注销服务端会话）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "勾选后可避免影响电脑网页端或其他已登录设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingsBentoSection(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Card,
                accentColor = accentColor,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulseSectionHeader(
            title = title,
            subtitle = subtitle,
            icon = icon,
            accentColor = accentColor,
            trailing = if (headerTrailing != null) {
                { headerTrailing() }
            } else {
                null
            },
        )

        content()
    }
}

@Composable
private fun VisualThemeOptionCard(
    selected: Boolean,
    label: String,
    previewMode: ThemeMode,
    isAmoledPreview: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val containerBg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.42f else 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "themeCardBg",
    )
    val borderBrush = if (selected) {
        PulseBrushes.brandGradient()
    } else {
        SolidColor(ext.cardBorder)
    }
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .pulsePressScale(interactionSource)
            .clip(RoundedCornerShape(16.dp))
            .background(containerBg)
            .border(
                width = if (selected) 1.75.dp else 0.9.dp,
                brush = borderBrush,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = 0.75.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    } else {
                        ext.cardBorder
                    },
                    shape = RoundedCornerShape(10.dp),
                ),
        ) {
            when (previewMode) {
                ThemeMode.SYSTEM -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        ThemeWireframeHalf(
                            bg = PaperBackground,
                            card = PaperSurfaceContainerLowest,
                            accent = PaperPrimary,
                            line = PaperOutlineVariant,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        ThemeWireframeHalf(
                            bg = if (isAmoledPreview) PurePitchBlack else ObsidianBackground,
                            card = ObsidianSurfaceContainer,
                            accent = ObsidianPrimary,
                            line = ObsidianOutlineVariant,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f))
                            .border(0.6.dp, ext.cardBorder, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BrightnessAuto,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                ThemeMode.PAPER_LIGHT -> {
                    ThemeWireframeCanvas(
                        bg = PaperBackground,
                        card = PaperSurfaceContainerLowest,
                        accent = PaperPrimary,
                        line = PaperOutlineVariant,
                        icon = Icons.Rounded.LightMode,
                    )
                }

                ThemeMode.OBSIDIAN_DARK -> {
                    ThemeWireframeCanvas(
                        bg = if (isAmoledPreview) PurePitchBlack else ObsidianBackground,
                        card = ObsidianSurfaceContainer,
                        accent = ObsidianPrimary,
                        line = ObsidianOutlineVariant,
                        icon = Icons.Rounded.DarkMode,
                    )
                }
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThemeWireframeHalf(
    bg: Color,
    card: Color,
    accent: Color,
    line: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(bg)
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(4.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(5.dp))
                .background(card)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(line),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(line.copy(alpha = 0.7f)),
            )
        }
    }
}

@Composable
private fun ThemeWireframeCanvas(
    bg: Color,
    card: Color,
    accent: Color,
    line: Color,
    icon: ImageVector,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(6.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(11.dp),
                )
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(card)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(3.5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(3.5.dp)
                        .clip(CircleShape)
                        .background(line),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(3.5.dp)
                        .clip(CircleShape)
                        .background(line.copy(alpha = 0.65f)),
                )
            }
        }
    }
}

@Composable
private fun CheckInPreferenceCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    selectedContainer: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val shape = RoundedCornerShape(15.dp)
    val bgBrush = if (selected) {
        Brush.linearGradient(
            colors = listOf(
                selectedContainer,
                blendColor(selectedContainer, MaterialTheme.colorScheme.surface, 0.25f),
            ),
        )
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceContainerLow)
    }
    val borderColor by animateColorAsState(
        targetValue = if (selected) accentColor else ext.cardBorder,
        label = "checkInPrefBorder",
    )
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .pulsePressScale(interactionSource)
            .clip(shape)
            .background(bgBrush)
            .border(
                width = if (selected) 1.5.dp else 0.9.dp,
                color = borderColor,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(
                        if (selected) {
                            accentColor.copy(alpha = if (isDark) 0.22f else 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val isDark = NodePulseThemeTokens.isDark
    val rowBg by animateColorAsState(
        targetValue = if (checked) {
            accentColor.copy(alpha = if (isDark) 0.09f else 0.055f)
        } else {
            Color.Transparent
        },
        label = "toggleRowBg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(rowBg)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(
                        if (checked) {
                            accentColor.copy(alpha = if (isDark) 0.20f else 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            thumbContent = if (checked) {
                {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun StorageActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pulsePressScale(interactionSource)
            .pulseCard(
                shape = RoundedCornerShape(15.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                elevation = PulseElevation.None,
                accentColor = accentColor,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(PulseArchitecturalShapes.SmallChip)
                .background(accentColor.copy(alpha = if (isDark) 0.18f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BlockedUsersManagerDialog(
    blockedUsers: List<FloorAuthor>,
    isLoading: Boolean,
    onAddUsername: (String) -> Unit,
    onUnblock: (FloorAuthor) -> Unit,
    onDismiss: () -> Unit,
) {
    var newUsername by rememberSaveable { mutableStateOf("") }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Block,
        accentColor = MaterialTheme.colorScheme.error,
        title = "屏蔽用户名单",
        titleTrailing = {
            PulseAccentPill(
                text = "${blockedUsers.size}",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
        buttons = {
            PulseDialogPrimaryButton(
                text = "完成",
                onClick = onDismiss,
                useTonal = true,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newUsername,
                    onValueChange = { newUsername = it },
                    placeholder = { Text("输入要屏蔽的用户名…") },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = {
                        if (newUsername.isNotBlank()) {
                            onAddUsername(newUsername)
                            newUsername = ""
                        }
                    },
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("屏蔽")
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            } else if (blockedUsers.isEmpty()) {
                Text(
                    text = "当前屏蔽名单为空",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(blockedUsers, key = { it.uid }) { user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseCard(
                                    shape = PulseArchitecturalShapes.MediumBadge,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    elevation = PulseElevation.None,
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PulseAvatar(
                                avatarUrl = user.avatarUrl,
                                username = user.username,
                                size = 30.dp,
                                rankLevel = user.level,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                Text(
                                    text = user.username,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "UID #${user.uid}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ).withTabularNumbers(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { onUnblock(user) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "解除屏蔽",
                                    tint = MaterialTheme.colorScheme.error,
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

@Composable
private fun SettingsIntervalPresetRow(
    title: String,
    optionsMinutes: List<Int>,
    selectedMinutes: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            optionsMinutes.forEach { minutes ->
                val isSelected = selectedMinutes == minutes
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .pulsePressScale(interaction)
                        .clip(CircleShape)
                        .background(
                            if (!enabled) {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            } else if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
                            },
                        )
                        .border(
                            width = 0.8.dp,
                            color = if (enabled && isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            } else {
                                ext.cardBorder
                            },
                            shape = CircleShape,
                        )
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(minutes) },
                        )
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${minutes} 分钟",
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (!enabled) {
                            MaterialTheme.colorScheme.outline
                        } else if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
