@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.hub

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.SingletonImageLoader
import com.nodeseek.app.BuildConfig
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.digest.AwayDurationFormatter
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterEngine
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.trust.OfficialTradeLookup
import com.nodeseek.app.domain.update.AppUpdateState
import com.nodeseek.app.domain.update.UpdateCheckResult
import com.nodeseek.app.domain.update.UpdateDownloadPhase
import com.nodeseek.app.ui.overseas.openOverseasAppNotificationSettings
import com.nodeseek.app.ui.trust.OFFICIAL_TRADE_PORTAL_URL
import com.nodeseek.app.ui.trust.OfficialTradeUiSignalBus
import kotlinx.coroutines.CancellationException
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogPrimaryButton
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.theme.NodePulseMotion
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
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.PurePitchBlack
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
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
import kotlin.math.abs
import kotlin.math.roundToInt

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val session: AccountSession? = null,
    val blockedUsers: List<FloorAuthor> = emptyList(),
    val isLoadingBlockedUsers: Boolean = false,
    val showBlockedUsersDialog: Boolean = false,
    val isSigningOut: Boolean = false,
    val officialTradeConfigured: Boolean = false,
    val officialTradeMaskedKey: String? = null,
    val isSavingOfficialTradeKey: Boolean = false,
    val isVerifyingOfficialTradeKey: Boolean = false,
    val officialTradeFeedbackMessage: String? = null,
    val officialTradeFeedbackIsError: Boolean = false,
)

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private data class TransientState(
        val blockedUsers: List<FloorAuthor> = emptyList(),
        val isLoadingBlockedUsers: Boolean = false,
        val showBlockedUsersDialog: Boolean = false,
        val isSigningOut: Boolean = false,
        val officialKeyRevision: Long = 0L,
        val isSavingOfficialTradeKey: Boolean = false,
        val isVerifyingOfficialTradeKey: Boolean = false,
        val officialTradeFeedbackMessage: String? = null,
        val officialTradeFeedbackIsError: Boolean = false,
    )

    private val transient = MutableStateFlow(TransientState())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        container.settingsRepository.settings,
        container.sessionRepository.sessionState,
        container.officialTradeRepository.isConfigured,
        transient,
    ) { settings, session, officialConfigured, local ->
        val maskedOfficialKey = if (officialConfigured) {
            container.officialTradeRepository.maskedApiKey()
        } else {
            null
        }
        SettingsUiState(
            settings = settings,
            session = session,
            blockedUsers = local.blockedUsers,
            isLoadingBlockedUsers = local.isLoadingBlockedUsers,
            showBlockedUsersDialog = local.showBlockedUsersDialog,
            isSigningOut = local.isSigningOut,
            officialTradeConfigured = officialConfigured,
            officialTradeMaskedKey = maskedOfficialKey,
            isSavingOfficialTradeKey = local.isSavingOfficialTradeKey,
            isVerifyingOfficialTradeKey = local.isVerifyingOfficialTradeKey,
            officialTradeFeedbackMessage = local.officialTradeFeedbackMessage,
            officialTradeFeedbackIsError = local.officialTradeFeedbackIsError,
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

    fun setAwayDigestEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAwayDigestEnabled(enabled) }
    }

    fun setAwayDigestTriggerMode(mode: AwayDigestTriggerMode) {
        viewModelScope.launch { container.settingsRepository.setAwayDigestTriggerMode(mode) }
    }

    fun setAwayDigestThresholdMinutes(
        minutes: Int,
        showConfirmation: Boolean = false,
    ) {
        val clamped = minutes.coerceIn(
            AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
            AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
        )
        viewModelScope.launch {
            container.settingsRepository.setAwayDigestThresholdMinutes(clamped)
            if (showConfirmation) {
                _events.trySend("已将离开速览时长设为 ${AwayDurationFormatter.formatThresholdMinutes(clamped)}")
            }
        }
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

    fun clearOfficialTradeFeedback() {
        transient.update {
            if (it.officialTradeFeedbackMessage == null) {
                it
            } else {
                it.copy(
                    officialTradeFeedbackMessage = null,
                    officialTradeFeedbackIsError = false,
                )
            }
        }
    }

    fun saveOfficialTradeApiKey(
        rawKey: String,
        onSavedClearDraft: () -> Unit = {},
    ) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            transient.update {
                it.copy(
                    officialTradeFeedbackMessage = "请输入以 ntk_ 开头的官方交易平台 API Key",
                    officialTradeFeedbackIsError = true,
                )
            }
            return
        }
        if (transient.value.isSavingOfficialTradeKey || transient.value.isVerifyingOfficialTradeKey) return

        viewModelScope.launch {
            transient.update {
                it.copy(
                    isSavingOfficialTradeKey = true,
                    officialTradeFeedbackMessage = null,
                    officialTradeFeedbackIsError = false,
                )
            }
            try {
                container.officialTradeRepository.saveApiKey(trimmed)
                OfficialTradeUiSignalBus.notifyMutated()
                val masked = container.officialTradeRepository.maskedApiKey()
                val msg = if (!masked.isNullOrBlank()) {
                    "已加密保存至本机（$masked）"
                } else {
                    "已保存官方交易平台 API Key"
                }
                transient.update {
                    it.copy(
                        isSavingOfficialTradeKey = false,
                        officialKeyRevision = it.officialKeyRevision + 1L,
                        officialTradeFeedbackMessage = msg,
                        officialTradeFeedbackIsError = false,
                    )
                }
                onSavedClearDraft()
                _events.trySend("已保存官方交易平台 API Key")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val errMsg = t.message?.takeIf { it.isNotBlank() }
                    ?: "API Key 格式无效，必须以 ntk_ 开头"
                transient.update {
                    it.copy(
                        isSavingOfficialTradeKey = false,
                        officialTradeFeedbackMessage = errMsg,
                        officialTradeFeedbackIsError = true,
                    )
                }
                _events.trySend(errMsg)
            }
        }
    }

    fun verifyOfficialTradeApiKey(draftKey: String) {
        if (transient.value.isSavingOfficialTradeKey || transient.value.isVerifyingOfficialTradeKey) return
        val trimmed = draftKey.trim()

        viewModelScope.launch {
            transient.update {
                it.copy(
                    isVerifyingOfficialTradeKey = true,
                    officialTradeFeedbackMessage = null,
                    officialTradeFeedbackIsError = false,
                )
            }
            try {
                val lookupResult = when {
                    trimmed.isNotEmpty() -> container.officialTradeRepository.verifyApiKey(trimmed)
                    container.officialTradeRepository.isConfigured.value ->
                        container.officialTradeRepository.lookup(
                            username = OFFICIAL_VERIFY_SAVED_KEY_USERNAME,
                            forceRefresh = true,
                        )
                    else -> container.officialTradeRepository.verifyApiKey("")
                }

                val (message, isError) = when (lookupResult) {
                    is OfficialTradeLookup.Found,
                    OfficialTradeLookup.NotFound,
                    -> "连接测试通过：官方交易平台 API Key 有效" to false
                    OfficialTradeLookup.Unauthorized ->
                        "连接测试失败：API Key 无效或已失效 (401/403)" to true
                    is OfficialTradeLookup.RateLimited ->
                        "连接正常但触发平台限流，请 ${lookupResult.retryAfterSeconds ?: 60} 秒后再试" to true
                    OfficialTradeLookup.NotConfigured ->
                        "请先输入 API Key 后再测试连接" to true
                    is OfficialTradeLookup.Failed ->
                        lookupResult.message.ifBlank { "测试连接失败，请检查网络或密钥格式" } to true
                }

                transient.update {
                    it.copy(
                        isVerifyingOfficialTradeKey = false,
                        officialTradeFeedbackMessage = message,
                        officialTradeFeedbackIsError = isError,
                    )
                }
                _events.trySend(message)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val errMsg = t.message?.takeIf { it.isNotBlank() } ?: "测试连接失败，请稍后重试"
                transient.update {
                    it.copy(
                        isVerifyingOfficialTradeKey = false,
                        officialTradeFeedbackMessage = errMsg,
                        officialTradeFeedbackIsError = true,
                    )
                }
                _events.trySend(errMsg)
            }
        }
    }

    fun clearOfficialTradeApiKey(onClearedDraft: () -> Unit = {}) {
        if (transient.value.isSavingOfficialTradeKey || transient.value.isVerifyingOfficialTradeKey) return

        viewModelScope.launch {
            try {
                container.officialTradeRepository.clearApiKey()
                OfficialTradeUiSignalBus.notifyMutated()
                transient.update {
                    it.copy(
                        officialKeyRevision = it.officialKeyRevision + 1L,
                        officialTradeFeedbackMessage = "已清除本机保存的官方交易平台密钥",
                        officialTradeFeedbackIsError = false,
                    )
                }
                onClearedDraft()
                _events.trySend("已清除官方交易平台 API Key")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val errMsg = t.message?.takeIf { it.isNotBlank() } ?: "清除密钥失败"
                transient.update {
                    it.copy(
                        officialTradeFeedbackMessage = errMsg,
                        officialTradeFeedbackIsError = true,
                    )
                }
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
        private const val OFFICIAL_VERIFY_SAVED_KEY_USERNAME: String = "solaireh3"

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
    previewUiState: SettingsUiState? = null,
    previewRadarPollingSettings: RadarPollingSettings? = null,
    previewRadarUnreadCount: Int? = null,
    previewUpdateState: AppUpdateState? = null,
    previewContentFilterConfig: ContentFilterConfig? = null,
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
    val observedContentFilterConfig by appContainer.contentFilterRepository.config.collectAsStateWithLifecycle()
    val contentFilterConfig = previewContentFilterConfig ?: observedContentFilterConfig
    val settingsScope = rememberCoroutineScope()
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val settings = uiState.settings
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }

    val isPreviewMode = isInspection || previewUiState != null || previewRadarPollingSettings != null
    var hasNotificationPermission by remember(appContainer.notificationDispatcher, isPreviewMode) {
        mutableStateOf(
            if (isPreviewMode) true else appContainer.notificationDispatcher.hasPostNotificationsPermission(),
        )
    }

    DisposableEffect(lifecycleOwner, appContainer.notificationDispatcher, isPreviewMode) {
        if (isPreviewMode) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasNotificationPermission = appContainer.notificationDispatcher.hasPostNotificationsPermission()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        appContainer.notificationDispatcher.markPostNotificationsRequestedOnce()
        val nowPermitted = appContainer.notificationDispatcher.hasPostNotificationsPermission()
        hasNotificationPermission = nowPermitted
        if (granted && nowPermitted) {
            settingsScope.launch {
                snackbarHostState.showSnackbar("系统通知权限已开启")
            }
        } else {
            openOverseasAppNotificationSettings(context)
        }
    }

    val requestOrOpenNotificationPermission: () -> Unit = {
        if (!isPreviewMode) {
            val runtimeMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            if (runtimeMissing && !appContainer.notificationDispatcher.hasRequestedPostNotificationsOnce()) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }.onFailure {
                    openOverseasAppNotificationSettings(context)
                }
            } else {
                openOverseasAppNotificationSettings(context)
            }
        }
    }

    var showNodeImageKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showAwayDigestCustomDurationDialog by rememberSaveable { mutableStateOf(false) }
    var showClearHistoryConfirm by rememberSaveable { mutableStateOf(false) }
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Subtle atmospheric top halo matching "我的" (PulseHubScreen)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ext.heroTintStart.copy(alpha = if (isDark) 0.22f else 0.14f),
                                ext.heroTintEnd.copy(alpha = if (isDark) 0.08f else 0.05f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 36.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ── Clean Minimalist Top Header ──
                item(key = "settings_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Text(
                            text = "设置",
                            style = extraType.heroHeadline,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                        )
                    }
                }

                // ── Group 1: 外观与显示 ──
                item(key = "settings_appearance") {
                    PulseStaggeredEntrance(index = 0) {
                        SettingsGroupedSection(title = "外观与显示") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
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

                            SettingsGroupDivider()

                            SettingsSwitchRow(
                                icon = Icons.Rounded.DarkMode,
                                title = "纯黑背景 (AMOLED)",
                                subtitle = "深色模式下启用纯黑背景以降低 OLED 屏幕功耗",
                                checked = settings.purePitchAmoled,
                                onCheckedChange = viewModel::setPurePitchAmoled,
                            )

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                SettingsGroupDivider()
                                SettingsSwitchRow(
                                    icon = Icons.Rounded.AutoAwesome,
                                    title = "壁纸动态取色",
                                    subtitle = "跟随系统壁纸生成 Material You 配色方案",
                                    checked = settings.useDynamicColor,
                                    onCheckedChange = viewModel::setUseDynamicColor,
                                )
                            }
                        }
                    }
                }

                // ── Group 2: 字号与排版 ──
                item(key = "settings_typography") {
                    val pct = (settings.fontScale * 100f).roundToInt()
                    PulseStaggeredEntrance(index = 1) {
                        SettingsGroupedSection(title = "字号与排版") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    SettingsRowIconBadge(icon = Icons.Rounded.FormatSize)
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(1.dp),
                                    ) {
                                        Text(
                                            text = "正文字号与排版缩放",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "全局调整信息流与帖子正文的文字比例",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    PulseAccentPill(
                                        text = "$pct%",
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }

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
                                        0.90f to "90%",
                                        1.00f to "100%",
                                        1.15f to "115%",
                                    ).forEach { (scale, label) ->
                                        val isPresetActive = abs(settings.fontScale - scale) < 0.02f
                                        val chipBg by animateColorAsState(
                                            targetValue = if (isPresetActive) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerLow
                                            },
                                            label = "fontPresetBg",
                                        )
                                        val chipBorder by animateColorAsState(
                                            targetValue = if (isPresetActive) {
                                                MaterialTheme.colorScheme.primary
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
                                                    width = if (isPresetActive) 1.1.dp else 0.8.dp,
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
                                                    MaterialTheme.colorScheme.onPrimaryContainer
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
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .border(
                                            width = 0.75.dp,
                                            color = ext.cardBorder,
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "排版预览",
                                            style = extraType.microLabel,
                                            color = MaterialTheme.colorScheme.primary,
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
                                            lineHeight = (25f * settings.fontScale).sp,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Group 3: 社区与浏览 ──
                item(key = "settings_community") {
                    PulseStaggeredEntrance(index = 2) {
                        SettingsGroupedSection(title = "社区与浏览") {
                            SettingsCheckInStrategyRow(
                                preferRandom = settings.preferRandomCheckIn,
                                onSelect = viewModel::setPreferRandomCheckIn,
                            )

                            SettingsGroupDivider()

                            SettingsSwitchRow(
                                icon = Icons.Rounded.Bolt,
                                title = "未登录优先启用 RSS 访客流",
                                subtitle = "通过 rss.nodeseek.com 零验证快速拉取公开版块主题",
                                checked = settings.preferRssFallback,
                                onCheckedChange = viewModel::setPreferRssFallback,
                            )

                            SettingsGroupDivider()

                            SettingsSwitchRow(
                                icon = Icons.Rounded.VisibilityOff,
                                title = "显示已屏蔽成员内容",
                                subtitle = "关闭时将在信息流与主题详情页自动折叠屏蔽用户楼层",
                                checked = settings.includeBlockedTopics,
                                onCheckedChange = viewModel::setIncludeBlockedTopics,
                            )

                            if (uiState.session != null) {
                                SettingsGroupDivider()
                                SettingsNavRow(
                                    icon = Icons.Rounded.Block,
                                    title = "屏蔽用户名单",
                                    subtitle = "管理已屏蔽的社区成员",
                                    onClick = viewModel::openBlockedUsersManager,
                                )
                            }
                        }
                    }
                }

                // ── Group 3.2: 离开期间速览 ──
                item(key = "settings_away_digest") {
                    PulseStaggeredEntrance(index = 3) {
                        SettingsGroupedSection(title = "离开期间速览") {
                            AwayDigestSettingsCardContent(
                                enabled = settings.awayDigestEnabled,
                                triggerMode = settings.awayDigestTriggerMode,
                                thresholdMinutes = settings.awayDigestThresholdMinutes,
                                onEnabledChange = viewModel::setAwayDigestEnabled,
                                onTriggerModeSelect = viewModel::setAwayDigestTriggerMode,
                                onPresetMinutesSelect = { minutes ->
                                    viewModel.setAwayDigestThresholdMinutes(minutes)
                                },
                                onOpenCustomDurationDialog = {
                                    showAwayDigestCustomDurationDialog = true
                                },
                            )
                        }
                    }
                }

                // ── Group 3.5: 内容屏蔽 ──
                item(key = "settings_content_filter") {
                    PulseStaggeredEntrance(index = 3) {
                        ContentFilterSettingsSection(
                            config = contentFilterConfig,
                            includeBlockedTopics = settings.includeBlockedTopics,
                            onSetEnabled = { enabled ->
                                settingsScope.launch {
                                    appContainer.contentFilterRepository.setEnabled(enabled)
                                }
                            },
                            onTogglePreset = { preset ->
                                settingsScope.launch {
                                    val existing = ContentFilterEngine.findMatchingRuleForPreset(
                                        preset = preset,
                                        rules = contentFilterConfig.rules,
                                    )
                                    if (existing != null) {
                                        if (existing.isEnabled) {
                                            appContainer.contentFilterRepository.removeRule(existing.id)
                                        } else {
                                            appContainer.contentFilterRepository.setRuleEnabled(existing.id, true)
                                        }
                                    } else {
                                        appContainer.contentFilterRepository.upsertRule(preset.toRule())
                                    }
                                }
                            },
                            onUpsertRule = { rule ->
                                settingsScope.launch {
                                    appContainer.contentFilterRepository.upsertRule(rule)
                                }
                            },
                            onToggleRuleEnabled = { ruleId, enabled ->
                                settingsScope.launch {
                                    appContainer.contentFilterRepository.setRuleEnabled(ruleId, enabled)
                                }
                            },
                            onRemoveRule = { ruleId ->
                                settingsScope.launch {
                                    appContainer.contentFilterRepository.removeRule(ruleId)
                                }
                            },
                            onToggleMutedCategory = { slug ->
                                settingsScope.launch {
                                    appContainer.contentFilterRepository.toggleMutedCategory(slug)
                                }
                            },
                            onDisableIncludeBlockedTopics = {
                                viewModel.setIncludeBlockedTopics(false)
                            },
                        )
                    }
                }

                // ── Group 4: 通知与提醒 ──
                item(key = "settings_radar_notifications") {
                    PulseStaggeredEntrance(index = 3) {
                        SettingsGroupedSection(title = "通知与提醒") {
                            if (!hasNotificationPermission &&
                                (radarPollingSettings.isEnabled || radarPollingSettings.inboxPushEnabled)
                            ) {
                                SettingsNavRow(
                                    icon = Icons.Rounded.NotificationsOff,
                                    title = "系统通知权限未开启",
                                    subtitle = "当前仅前台显示应用内弹窗，退至后台无法收到手机消息通知",
                                    trailingText = "去开启",
                                    trailingTextColor = MaterialTheme.colorScheme.error,
                                    onClick = requestOrOpenNotificationPermission,
                                )
                                SettingsGroupDivider()
                            }

                            SettingsSwitchRow(
                                icon = Icons.Rounded.Radar,
                                title = "启用脉搏雷达监测",
                                subtitle = "总开关：控制后台私信/回复系统通知、关键词与追更楼层监测",
                                checked = radarPollingSettings.isEnabled,
                                onCheckedChange = { enabled ->
                                    settingsScope.launch {
                                        appContainer.radarRepository.updatePollingSettings {
                                            it.copy(isEnabled = enabled)
                                        }
                                    }
                                    if (enabled && !hasNotificationPermission) {
                                        requestOrOpenNotificationPermission()
                                    }
                                },
                            )

                            SettingsGroupDivider()

                            SettingsSwitchRow(
                                icon = Icons.Rounded.Mail,
                                title = "私信与互动系统推送",
                                subtitle = "收到新的未读回复、@提及或私信时发送原生通知",
                                checked = radarPollingSettings.inboxPushEnabled,
                                onCheckedChange = { enabled ->
                                    settingsScope.launch {
                                        appContainer.radarRepository.updatePollingSettings {
                                            it.copy(
                                                isEnabled = if (enabled) true else it.isEnabled,
                                                inboxPushEnabled = enabled,
                                            )
                                        }
                                    }
                                    if (enabled && !hasNotificationPermission) {
                                        requestOrOpenNotificationPermission()
                                    }
                                },
                            )

                            AnimatedVisibility(
                                visible = radarPollingSettings.isEnabled,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Column {
                                    SettingsGroupDivider()

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

                                    SettingsGroupDivider()

                                    SettingsIntervalPresetRow(
                                        title = "后台巡检间隔 (WorkManager)",
                                        optionsMinutes = listOf(15, 30, 60),
                                        selectedMinutes = radarPollingSettings.backgroundIntervalMinutes
                                            .coerceAtLeast(RadarPollingSettings.MIN_BACKGROUND_INTERVAL_MINUTES),
                                        enabled = radarPollingSettings.isEnabled,
                                        onSelect = { minutes ->
                                            settingsScope.launch {
                                                appContainer.radarRepository.updatePollingSettings {
                                                    it.copy(backgroundIntervalMinutes = minutes)
                                                }
                                            }
                                        },
                                    )

                                    SettingsGroupDivider()

                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.Wifi,
                                        title = "后台仅 Wi-Fi",
                                        subtitle = "仅在非计费网络下触发 WorkManager 后台巡检",
                                        checked = radarPollingSettings.wifiOnlyInBackground,
                                        onCheckedChange = { wifiOnly ->
                                            settingsScope.launch {
                                                appContainer.radarRepository.updatePollingSettings {
                                                    it.copy(wifiOnlyInBackground = wifiOnly)
                                                }
                                            }
                                        },
                                    )

                                    SettingsGroupDivider()

                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.BatteryChargingFull,
                                        title = "低电量暂停后台巡检",
                                        subtitle = "WorkManager 系统约束：低电量时延后，电量恢复后继续",
                                        checked = radarPollingSettings.requireBatteryNotLowInBackground,
                                        onCheckedChange = { requireBatteryNotLow ->
                                            settingsScope.launch {
                                                appContainer.radarRepository.updatePollingSettings {
                                                    it.copy(requireBatteryNotLowInBackground = requireBatteryNotLow)
                                                }
                                            }
                                        },
                                    )
                                }
                            }

                            SettingsGroupDivider()

                            SettingsNavRow(
                                icon = Icons.Rounded.Radar,
                                title = "脉搏雷达规则与提醒中心",
                                badgeCount = radarUnreadCount,
                                onClick = { onOpenRadar(0) },
                            )
                        }
                    }
                }

                // ── Group 4.8: 官方交易平台 ──
                item(key = "settings_official_trade") {
                    PulseStaggeredEntrance(index = 4) {
                        SettingsGroupedSection(title = "官方交易平台") {
                            OfficialTradeSettingsCardContent(
                                isConfigured = uiState.officialTradeConfigured,
                                maskedApiKey = uiState.officialTradeMaskedKey,
                                isSaving = uiState.isSavingOfficialTradeKey,
                                isVerifying = uiState.isVerifyingOfficialTradeKey,
                                feedbackMessage = uiState.officialTradeFeedbackMessage,
                                feedbackIsError = uiState.officialTradeFeedbackIsError,
                                onDraftEdited = viewModel::clearOfficialTradeFeedback,
                                onSaveKey = viewModel::saveOfficialTradeApiKey,
                                onVerifyKey = viewModel::verifyOfficialTradeApiKey,
                                onClearKey = viewModel::clearOfficialTradeApiKey,
                                onOpenPortal = {
                                    runCatching {
                                        uriHandler.openUri(OFFICIAL_TRADE_PORTAL_URL)
                                    }.onFailure {
                                        settingsScope.launch {
                                            snackbarHostState.showSnackbar("请访问 $OFFICIAL_TRADE_PORTAL_URL 申请 API Key")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                // ── Group 5: 服务与存储 ──
                item(key = "settings_storage") {
                    val hasNodeImageKey = settings.nodeImageApiKey.isNotBlank()
                    PulseStaggeredEntrance(index = 4) {
                        SettingsGroupedSection(title = "服务与存储") {
                            SettingsNavRow(
                                icon = Icons.Rounded.Image,
                                title = "NodeImage 官方图床",
                                subtitle = "配置 api.nodeimage.com 的上传密钥",
                                trailingText = if (hasNodeImageKey) "已配置" else "未配置",
                                trailingTextColor = if (hasNodeImageKey) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                onClick = { showNodeImageKeyDialog = true },
                            )

                            SettingsGroupDivider()

                            SettingsNavRow(
                                icon = Icons.Rounded.DeleteSweep,
                                title = "清理图片缓存",
                                trailingText = "内存与磁盘",
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
                            )

                            SettingsGroupDivider()

                            SettingsNavRow(
                                icon = Icons.Rounded.History,
                                title = "清空浏览记录",
                                trailingText = "本地足迹",
                                onClick = { showClearHistoryConfirm = true },
                            )
                        }
                    }
                }

                // ── Group 5b: 数据备份与恢复 ──
                item(key = "settings_backup_restore") {
                    PulseStaggeredEntrance(index = 4) {
                        BackupRestoreSettingsSection(
                            backupManager = appContainer.backupManager,
                            snackbarHostState = snackbarHostState,
                        )
                    }
                }

                // ── Group 6: 关于与更新 ──
                item(key = "settings_app_update") {
                    val latestRelease = updateState.latestRelease
                    val downloadPhase = updateState.downloadPhase
                    val githubReleasesUrl = "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases"

                    PulseStaggeredEntrance(index = 5) {
                        SettingsGroupedSection(title = "关于与更新") {
                            SettingsUpdateRow(
                                updateState = updateState,
                                onActionClick = {
                                    when {
                                        updateState.hasUpdate && latestRelease != null -> {
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
                                        }
                                        else -> viewModel.checkForUpdates()
                                    }
                                },
                            )

                            SettingsGroupDivider()

                            SettingsSwitchRow(
                                icon = Icons.Rounded.NewReleases,
                                title = "启动时自动检查更新",
                                subtitle = "冷启动后静默检查 GitHub Releases 新版本",
                                checked = settings.autoCheckUpdates,
                                onCheckedChange = viewModel::setAutoCheckUpdates,
                            )

                            SettingsGroupDivider()

                            SettingsSwitchRow(
                                icon = Icons.Rounded.Science,
                                title = "接收预发布版本 (Pre-release)",
                                subtitle = "开启后同时检查 beta / rc 等预览测试版本",
                                checked = settings.includePrereleaseUpdates,
                                onCheckedChange = viewModel::setIncludePrereleaseUpdates,
                            )

                            SettingsGroupDivider()

                            SettingsNavRow(
                                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                title = "GitHub Releases",
                                trailingText = "查看发行版",
                                onClick = {
                                    runCatching {
                                        uriHandler.openUri(githubReleasesUrl)
                                    }.onFailure {
                                        settingsScope.launch {
                                            snackbarHostState.showSnackbar("无法打开浏览器，请访问 $githubReleasesUrl")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                // ── Group 7: 账号 ──
                val session = uiState.session
                if (session != null) {
                    item(key = "settings_account") {
                        PulseStaggeredEntrance(index = 6) {
                            SettingsGroupedSection(title = "账号") {
                                SettingsAccountProfileRow(
                                    session = session,
                                    onClick = if (onOpenUserProfile != null) {
                                        { onOpenUserProfile(session.uid, session.username) }
                                    } else {
                                        null
                                    },
                                )

                                SettingsGroupDivider()

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            enabled = !uiState.isSigningOut,
                                            role = Role.Button,
                                            onClick = { showSignOutConfirm = true },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SettingsRowIconBadge(
                                        icon = Icons.AutoMirrored.Rounded.Logout,
                                        tint = MaterialTheme.colorScheme.error,
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                                    )
                                    Text(
                                        text = if (uiState.isSigningOut) "正在退出账号…" else "退出当前账号",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (uiState.isSigningOut) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.error,
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

    if (showNodeImageKeyDialog) {
        NodeImageApiKeyDialog(
            initialApiKey = settings.nodeImageApiKey,
            onSave = { newKey ->
                showNodeImageKeyDialog = false
                viewModel.saveNodeImageApiKey(newKey)
            },
            onDismiss = { showNodeImageKeyDialog = false },
        )
    }

    if (showAwayDigestCustomDurationDialog) {
        AwayDigestCustomDurationDialog(
            initialThresholdMinutes = settings.awayDigestThresholdMinutes,
            onSave = { customMinutes ->
                showAwayDigestCustomDurationDialog = false
                viewModel.setAwayDigestThresholdMinutes(
                    minutes = customMinutes,
                    showConfirmation = true,
                )
            },
            onDismiss = { showAwayDigestCustomDurationDialog = false },
        )
    }

    if (showClearHistoryConfirm) {
        PulseConfirmDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            icon = Icons.Rounded.History,
            tone = PulseDialogTone.DESTRUCTIVE,
            title = "清空浏览记录",
            message = "确定要清空保存在本机的全部浏览历史与续读进度吗？此操作无法撤销。",
            confirmText = "确认清空",
            dismissText = "取消",
            onConfirm = {
                showClearHistoryConfirm = false
                viewModel.clearReadHistory()
            },
        )
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
private fun SettingsGroupedSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(
                    shape = PulseArchitecturalShapes.ElevatedTile,
                    elevation = PulseElevation.Card,
                ),
            content = content,
        )
    }
}

@Composable
private fun SettingsGroupDivider() {
    val ext = NodePulseThemeTokens.extendedColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.6.dp)
            .background(ext.cardBorder.copy(alpha = 0.65f)),
    )
}

@Composable
private fun SettingsRowIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f),
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            SettingsRowIconBadge(
                icon = icon,
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                containerColor = if (checked) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
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
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailingText: String? = null,
    trailingTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    badgeCount: Int = 0,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            SettingsRowIconBadge(icon = icon)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (badgeCount > 0) {
            CompactCountBadge(
                count = badgeCount,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                minHeight = 20.dp,
            )
        } else if (!trailingText.isNullOrBlank()) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                color = trailingTextColor,
                maxLines = 1,
            )
        }

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingsCheckInStrategyRow(
    preferRandom: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsRowIconBadge(icon = Icons.Rounded.Restaurant)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "默认签到策略",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "与「我的」签到卡片模式同步",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(0.8.dp, ext.cardBorder, CircleShape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                false to "固定 +5",
                true to "试试手气",
            ).forEach { (isRandomOption, label) ->
                val active = preferRandom == isRandomOption
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        )
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.RadioButton,
                            onClick = { onSelect(isRandomOption) },
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsUpdateRow(
    updateState: AppUpdateState,
    onActionClick: () -> Unit,
) {
    val latestRelease = updateState.latestRelease
    val downloadPhase = updateState.downloadPhase
    val ext = NodePulseThemeTokens.extendedColors

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
        else -> "Build ${updateState.currentVersionCode} · GitHub Releases"
    }

    val actionLabel = when {
        updateState.isChecking -> "检查中"
        downloadPhase is UpdateDownloadPhase.Downloading -> {
            val pct = downloadPhase.progressFraction?.let { (it * 100f).roundToInt().coerceIn(0, 100) }
            if (pct != null) "下载 $pct%" else "下载中"
        }
        downloadPhase is UpdateDownloadPhase.ReadyToInstall ->
            if (downloadPhase.requiresInstallPermission) "授权安装" else "立即安装"
        updateState.hasUpdate && latestRelease != null -> "立即更新"
        else -> "检查更新"
    }

    val isHighlighted = updateState.hasUpdate ||
        downloadPhase is UpdateDownloadPhase.Downloading ||
        downloadPhase is UpdateDownloadPhase.ReadyToInstall

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !updateState.isChecking, role = Role.Button, onClick = onActionClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsRowIconBadge(
                icon = Icons.Rounded.SystemUpdate,
                tint = if (isHighlighted) ext.onDrumstickContainer else MaterialTheme.colorScheme.primary,
                containerColor = if (isHighlighted) {
                    ext.drumstickContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Anode v${updateState.currentVersionName}",
                    style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = checkSummary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Normal,
                    color = if (!updateState.checkError.isNullOrBlank() && !updateState.hasUpdate) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isHighlighted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (updateState.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.8.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = if (isHighlighted) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    softWrap = false,
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
                        .height(4.dp)
                        .clip(CircleShape),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}

@Composable
private fun SettingsAccountProfileRow(
    session: AccountSession,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulseAvatar(
            avatarUrl = session.avatarUrl,
            username = session.username,
            size = 40.dp,
            rankLevel = session.level,
            ringWidth = 1.8.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = session.username,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                RankMicroBadge(level = session.level, compact = true)
            }
            Text(
                text = "UID #${session.uid} · 点击查看个人主页",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(18.dp),
            )
        }
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
            .clip(RoundedCornerShape(14.dp))
            .background(containerBg)
            .border(
                width = if (selected) 1.5.dp else 0.85.dp,
                brush = borderBrush,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(9.dp))
                .border(
                    width = 0.75.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    } else {
                        ext.cardBorder
                    },
                    shape = RoundedCornerShape(9.dp),
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
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f))
                            .border(0.6.dp, ext.cardBorder, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BrightnessAuto,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
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
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(9.dp),
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
private fun NodeImageApiKeyDialog(
    initialApiKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var apiKeyDraft by rememberSaveable(initialApiKey) { mutableStateOf(initialApiKey) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Image,
        title = "NodeImage 官方图床",
        message = "配置 api.nodeimage.com 的 X-API-Key，可在发帖与回复编辑器中一键上传配图。",
        buttons = {
            if (initialApiKey.isNotBlank()) {
                TextButton(onClick = { onSave("") }) {
                    Text(
                        text = "清除密钥",
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("取消", maxLines = 1, softWrap = false)
            }
            Button(
                onClick = { onSave(apiKeyDraft.trim()) },
                shape = CircleShape,
            ) {
                Text("保存", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        },
    ) {
        PulseTextField(
            value = apiKeyDraft,
            onValueChange = { apiKeyDraft = it },
            label = "NodeImage X-API-Key",
            placeholder = "粘贴您的图床 API Key…",
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            visualTransformation = if (showApiKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = clipboard?.primaryClip
                            val pasted = if (clip != null && clip.itemCount > 0) {
                                clip.getItemAt(0)?.coerceToText(context)?.toString()
                            } else {
                                null
                            }
                            if (!pasted.isNullOrBlank()) {
                                apiKeyDraft = pasted.trim()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("粘贴", style = MaterialTheme.typography.labelMedium)
                    }
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
                }
            },
            modifier = Modifier.fillMaxWidth(),
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
                PulseTextField(
                    value = newUsername,
                    onValueChange = { newUsername = it },
                    placeholder = "输入要屏蔽的用户名…",
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
                    Text("屏蔽", maxLines = 1, softWrap = false)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsRowIconBadge(icon = Icons.Rounded.Schedule)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
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
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        )
                        .border(
                            width = 0.8.dp,
                            color = if (enabled && isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
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
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${minutes} 分钟",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
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

@Composable
private fun OfficialTradeSettingsCardContent(
    isConfigured: Boolean,
    maskedApiKey: String?,
    isSaving: Boolean,
    isVerifying: Boolean,
    feedbackMessage: String?,
    feedbackIsError: Boolean,
    onDraftEdited: () -> Unit,
    onSaveKey: (rawKey: String, onSavedClearDraft: () -> Unit) -> Unit,
    onVerifyKey: (draftKey: String) -> Unit,
    onClearKey: (onClearedDraft: () -> Unit) -> Unit,
    onOpenPortal: () -> Unit,
) {
    val context = LocalContext.current
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }

    val isBusy = isSaving || isVerifying
    val canSave = !isBusy && keyDraft.isNotBlank()
    val canVerify = !isBusy && (keyDraft.isNotBlank() || isConfigured)
    val canClear = !isBusy && (isConfigured || keyDraft.isNotBlank())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsRowIconBadge(
                icon = Icons.Rounded.VerifiedUser,
                tint = if (isConfigured) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                containerColor = if (isConfigured) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "NodeSeek 官方交易平台",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "在 agent.nodeseek.org 申请 Key，仅保存在本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PulseAccentPill(
                text = if (isConfigured) "已配置" else "未配置",
                containerColor = if (isConfigured) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (isConfigured) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(0.75.dp, ext.cardBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Key,
                contentDescription = null,
                tint = if (isConfigured) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(15.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = if (isConfigured && !maskedApiKey.isNullOrBlank()) {
                        "当前密钥：$maskedApiKey"
                    } else {
                        "尚未保存 API Key"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = if (isConfigured) FontFamily.Monospace else FontFamily.Default,
                        fontWeight = FontWeight.SemiBold,
                    ).withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Android Keystore 加密存储 · 不随 JSON 备份导出",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onOpenPortal)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "申请 Key",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        OutlinedTextField(
            value = keyDraft,
            onValueChange = {
                keyDraft = it
                onDraftEdited()
            },
            enabled = !isBusy,
            label = { Text("官方交易平台 API Key") },
            placeholder = {
                Text(
                    text = if (!maskedApiKey.isNullOrBlank()) {
                        "已存 $maskedApiKey（输入新 ntk_ 密钥可更新）"
                    } else {
                        "粘贴 ntk_ 开头的 API Key…"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            visualTransformation = if (showKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = clipboard?.primaryClip
                            val pasted = if (clip != null && clip.itemCount > 0) {
                                clip.getItemAt(0)?.coerceToText(context)?.toString()
                            } else {
                                null
                            }
                            if (!pasted.isNullOrBlank()) {
                                keyDraft = pasted.trim()
                                onDraftEdited()
                            }
                        },
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("粘贴", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(
                        onClick = { showKey = !showKey },
                        enabled = !isBusy,
                    ) {
                        Icon(
                            imageVector = if (showKey) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = if (showKey) "隐藏密钥" else "显示密钥",
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = !feedbackMessage.isNullOrBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val containerBg = if (feedbackIsError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = if (isDark) 0.42f else 0.58f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.40f else 0.62f)
            }
            val contentFg = if (feedbackIsError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(containerBg)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = if (feedbackIsError) {
                        Icons.Rounded.WarningAmber
                    } else {
                        Icons.Rounded.CheckCircle
                    },
                    contentDescription = null,
                    tint = if (feedbackIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = feedbackMessage.orEmpty(),
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = contentFg,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    onSaveKey(keyDraft) { keyDraft = "" }
                },
                enabled = canSave,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                }
                Text(
                    text = if (isSaving) "保存中" else "保存",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            FilledTonalButton(
                onClick = { onVerifyKey(keyDraft) },
                enabled = canVerify,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                modifier = Modifier.weight(1.1f),
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                }
                Text(
                    text = if (isVerifying) "测试中" else "测试连接",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            OutlinedButton(
                onClick = {
                    if (isConfigured) {
                        onClearKey { keyDraft = "" }
                    } else {
                        keyDraft = ""
                        onDraftEdited()
                    }
                },
                enabled = canClear,
                shape = CircleShape,
                border = BorderStroke(0.8.dp, ext.cardBorder),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    text = "清除",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canClear) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun AwayDigestSettingsCardContent(
    enabled: Boolean,
    triggerMode: AwayDigestTriggerMode,
    thresholdMinutes: Int,
    onEnabledChange: (Boolean) -> Unit,
    onTriggerModeSelect: (AwayDigestTriggerMode) -> Unit,
    onPresetMinutesSelect: (Int) -> Unit,
    onOpenCustomDurationDialog: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val formattedThreshold = remember(thresholdMinutes) {
        AwayDurationFormatter.formatThresholdMinutes(thresholdMinutes)
    }
    val isCustomDuration = remember(thresholdMinutes) {
        thresholdMinutes !in AppSettings.PRESET_AWAY_DIGEST_MINUTES
    }

    val modePillLabel = when (triggerMode) {
        AwayDigestTriggerMode.CUSTOM_DURATION -> "满 $formattedThreshold"
        AwayDigestTriggerMode.ON_APP_REOPEN -> "关闭重进"
        AwayDigestTriggerMode.DURATION_OR_REOPEN -> "重进 / $formattedThreshold"
    }

    val modeHelperDescription = when (triggerMode) {
        AwayDigestTriggerMode.CUSTOM_DURATION ->
            "后台切回或冷启动进入时，离开达到 $formattedThreshold 即在首页展示未读好帖速览。"
        AwayDigestTriggerMode.ON_APP_REOPEN ->
            "退出或划掉关闭 App 后重新进入时立即展示离开期间未读好帖，日常普通切后台不打扰。"
        AwayDigestTriggerMode.DURATION_OR_REOPEN ->
            "关闭重进 App 时立即展示；未关闭但后台离开满 $formattedThreshold 后切回也展示。"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSwitchRow(
            icon = Icons.Rounded.Schedule,
            title = "启用离开期间速览",
            subtitle = "重返首页时按鸡腿数、回复数与热度精选最多 10 篇未读好帖",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )

        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsGroupDivider()

                // ── Trigger Mode Selector ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SettingsRowIconBadge(icon = Icons.Rounded.AutoAwesome)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text = "触发时机",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "自定义按离开时长或关闭 App 后重进展示",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PulseAccentPill(
                            text = modePillLabel,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            AwayDigestTriggerMode.CUSTOM_DURATION to "离开满设定时长",
                            AwayDigestTriggerMode.ON_APP_REOPEN to "关闭 App 后进入时展示",
                            AwayDigestTriggerMode.DURATION_OR_REOPEN to "两者皆可（关闭重进或满时长）",
                        ).forEach { (optionMode, label) ->
                            val selected = triggerMode == optionMode
                            FilterChip(
                                selected = selected,
                                onClick = { onTriggerModeSelect(optionMode) },
                                shape = CircleShape,
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    }
                                } else {
                                    null
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(0.75.dp, ext.cardBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = modeHelperDescription,
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ── Duration Selector (visible when mode uses duration threshold) ──
                AnimatedVisibility(
                    visible = triggerMode.usesDurationThreshold,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingsGroupDivider()

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SettingsRowIconBadge(icon = Icons.Rounded.Schedule)
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    Text(
                                        text = "离开时长阈值",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "支持快捷档位或自定义任意分钟/小时",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                PulseAccentPill(
                                    text = formattedThreshold,
                                    containerColor = ext.drumstickContainer,
                                    contentColor = ext.onDrumstickContainer,
                                )
                            }

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AppSettings.PRESET_AWAY_DIGEST_MINUTES.forEach { presetMinutes ->
                                    val selected = thresholdMinutes == presetMinutes
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onPresetMinutesSelect(presetMinutes) },
                                        shape = CircleShape,
                                        leadingIcon = if (selected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                        label = {
                                            Text(
                                                text = AwayDurationFormatter.formatThresholdMinutes(presetMinutes),
                                                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 1,
                                            )
                                        },
                                    )
                                }

                                FilterChip(
                                    selected = isCustomDuration,
                                    onClick = onOpenCustomDurationDialog,
                                    shape = CircleShape,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isCustomDuration) Icons.Rounded.Check else Icons.Rounded.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = if (isCustomDuration) {
                                                "自定义 · $formattedThreshold"
                                            } else {
                                                "自定义时长"
                                            },
                                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                            fontWeight = if (isCustomDuration) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                        )
                                    },
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
private fun AwayDigestCustomDurationDialog(
    initialThresholdMinutes: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val safeInitial = initialThresholdMinutes.coerceIn(
        AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
        AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
    )
    var hoursDraft by rememberSaveable(safeInitial) {
        mutableStateOf((safeInitial / 60).takeIf { it > 0 }?.toString().orEmpty())
    }
    var minutesDraft by rememberSaveable(safeInitial) {
        mutableStateOf((safeInitial % 60).takeIf { it > 0 || safeInitial < 60 }?.toString().orEmpty())
    }

    val parsedMinutes = remember(hoursDraft, minutesDraft) {
        AwayDurationFormatter.parseCustomThresholdMinutes(
            hoursInput = hoursDraft,
            minutesInput = minutesDraft,
        )
    }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Schedule,
        title = "自定义离开时长",
        message = "设置触发「离开期间速览」所需的离开时长（范围：5 分钟 ～ 72 小时）。",
        buttons = {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("取消", maxLines = 1, softWrap = false)
            }
            Button(
                onClick = { parsedMinutes?.let(onSave) },
                enabled = parsedMinutes != null,
                shape = CircleShape,
            ) {
                Text("确定", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseTextField(
                    value = hoursDraft,
                    onValueChange = { raw ->
                        hoursDraft = raw.filter { it.isDigit() }.take(2)
                    },
                    label = "小时",
                    placeholder = "0",
                    suffix = { Text("小时") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )

                PulseTextField(
                    value = minutesDraft,
                    onValueChange = { raw ->
                        minutesDraft = raw.filter { it.isDigit() }.take(4)
                    },
                    label = "分钟",
                    placeholder = "0",
                    suffix = { Text("分钟") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (parsedMinutes != null) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (parsedMinutes != null) {
                        "当前设定：离开满 ${AwayDurationFormatter.formatThresholdMinutes(parsedMinutes)} 后展示未读速览"
                    } else {
                        "请输入 5 分钟 ～ 72 小时（4320 分钟）之间的有效时长"
                    },
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = if (parsedMinutes != null) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
            }
        }
    }
}
