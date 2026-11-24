package com.nodeseek.app.ui.hub

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.backup.EncryptedBackupFileNaming
import com.nodeseek.app.domain.backup.EncryptedBackupHeader
import com.nodeseek.app.domain.backup.EncryptedBackupPreviewSummary
import com.nodeseek.app.domain.backup.EncryptedBackupRestoreSummary
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseDialogPrimaryButton
import com.nodeseek.app.ui.components.PulseDialogSecondaryButton
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_ENCRYPTED_BACKUP_BYTES = 32 * 1024 * 1024

internal data class PendingEncryptedBackupRestore(
    val rawEnvelope: String,
    val passphrase: String,
    val summary: EncryptedBackupPreviewSummary,
)

internal data class EncryptedBackupUiState(
    val isExporting: Boolean = false,
    val isReadingFile: Boolean = false,
    val isDecrypting: Boolean = false,
    val isRestoring: Boolean = false,
    val showExportPassphraseDialog: Boolean = false,
    val pendingUnlockRawEnvelope: String? = null,
    val unlockErrorMessage: String? = null,
    val pendingRestore: PendingEncryptedBackupRestore? = null,
    val selectedStrategy: RestoreStrategy = RestoreStrategy.MERGE,
) {
    val isBusy: Boolean
        get() = isExporting || isReadingFile || isDecrypting || isRestoring
}

internal sealed interface PassphraseValidationResult {
    data object Valid : PassphraseValidationResult
    data class Invalid(val message: String) : PassphraseValidationResult
}

internal fun validateExportPassphrase(
    passphrase: String,
    confirmPassphrase: String,
): PassphraseValidationResult = when {
    passphrase.length < EncryptedBackupHeader.MIN_PASSPHRASE_LENGTH || passphrase.isBlank() ->
        PassphraseValidationResult.Invalid(
            "口令长度至少需要 ${EncryptedBackupHeader.MIN_PASSPHRASE_LENGTH} 位且不能全为空白字符",
        )
    passphrase != confirmPassphrase ->
        PassphraseValidationResult.Invalid("两次输入的口令不一致，请重新核对")
    else -> PassphraseValidationResult.Valid
}

internal fun formatEncryptedExportSnackbar(
    panelCount: Int,
    assetCount: Int,
    sshServerCount: Int,
    restockWatchCount: Int,
    bookmarkCount: Int,
): String =
    "加密完整备份已导出（$assetCount 资产 · $panelCount 探针 · $sshServerCount SSH 主机 · $restockWatchCount 补货 · $bookmarkCount 收藏）"

internal fun formatEncryptedRestoreSnackbar(summary: EncryptedBackupRestoreSummary): String {
    val modeLabel = if (summary.strategy == RestoreStrategy.OVERWRITE) "覆盖恢复" else "合并导入"
    val base = buildString {
        append("加密完整备份 $modeLabel 完成：")
        append("${summary.fleetAssetsImported} 资产 · ")
        append("${summary.fleetPanelsImported} 探针 · ")
        append("${summary.sshServersImported} SSH · ")
        append("${summary.restockWatchesImported} 补货 · ")
        append("${summary.bookmarksImported} 收藏 · ")
        append("${summary.radarKeywordRulesImported} 雷达规则")
    }
    return if (summary.hasPendingRemoteBlockSync) {
        "$base（${summary.blockedUsersImported} 位屏蔽用户已导入本地，可在上方确认同步至在线账号）"
    } else {
        base
    }
}

/**
 * ViewModel owning encrypted full backup v2 export / unlock / inspect / restore state.
 * Passphrases are kept strictly in transient JVM memory and cleared immediately once an operation
 * finishes or is cancelled; they are never serialized into `SavedStateHandle`.
 */
internal class EncryptedBackupViewModel(
    private val backupManager: BackupManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EncryptedBackupUiState())
    val uiState: StateFlow<EncryptedBackupUiState> = _uiState.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var stagedExportPassphrase: String? = null

    fun openExportDialog() {
        if (_uiState.value.isBusy) return
        stagedExportPassphrase = null
        _uiState.update { it.copy(showExportPassphraseDialog = true) }
    }

    fun dismissExportDialog() {
        stagedExportPassphrase = null
        _uiState.update { it.copy(showExportPassphraseDialog = false) }
    }

    fun stageExportPassphrase(passphrase: String) {
        stagedExportPassphrase = passphrase
        _uiState.update { it.copy(showExportPassphraseDialog = false) }
    }

    fun clearStagedExportPassphrase() {
        stagedExportPassphrase = null
    }

    fun exportToUri(
        appContext: Context,
        uri: Uri,
    ) {
        val passphrase = stagedExportPassphrase
        stagedExportPassphrase = null
        if (passphrase.isNullOrEmpty() || _uiState.value.isBusy) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            try {
                val payload = withContext(ioDispatcher) {
                    backupManager.exportEncryptedFullBackup(passphrase = passphrase)
                }.getOrElse { error ->
                    _events.trySend("导出加密备份失败：${error.message ?: "生成加密备份失败"}")
                    return@launch
                }
                withContext(ioDispatcher) {
                    writeEncryptedBackupTextToUri(appContext, uri, payload.rawEnvelopeJson)
                }
                val b = payload.bundle
                _events.trySend(
                    formatEncryptedExportSnackbar(
                        panelCount = b.fleet.panels.size,
                        assetCount = b.fleet.assets.size,
                        sshServerCount = b.sshVault.servers.size,
                        restockWatchCount = b.restock.watchItems.size,
                        bookmarkCount = b.bookmarks.size,
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _events.trySend("导出加密备份失败：${t.message ?: "写入文件失败"}")
            } finally {
                _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    fun readEncryptedFileFromUri(
        appContext: Context,
        uri: Uri,
    ) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isReadingFile = true) }
            try {
                val rawContent = withContext(ioDispatcher) {
                    readEncryptedBackupTextFromUri(appContext, uri)
                }
                openUnlockForRawEnvelope(rawContent)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _events.trySend("读取加密备份文件失败：${t.message ?: "文件读取异常"}")
            } finally {
                _uiState.update { it.copy(isReadingFile = false) }
            }
        }
    }

    internal fun openUnlockForRawEnvelope(rawContent: String): Boolean {
        if (!backupManager.isEncryptedBackupEnvelope(rawContent)) {
            _events.trySend("该文件不是加密完整备份（v2），若为明文 JSON 备份请使用上方「从备份文件恢复」")
            return false
        }
        _uiState.update {
            it.copy(
                pendingUnlockRawEnvelope = rawContent,
                unlockErrorMessage = null,
            )
        }
        return true
    }

    fun dismissUnlockDialog() {
        if (_uiState.value.isDecrypting) return
        _uiState.update {
            it.copy(
                pendingUnlockRawEnvelope = null,
                unlockErrorMessage = null,
            )
        }
    }

    fun unlockAndInspect(passphrase: String) {
        val rawEnvelope = _uiState.value.pendingUnlockRawEnvelope ?: return
        if (_uiState.value.isDecrypting) return
        if (passphrase.isEmpty()) {
            _uiState.update { it.copy(unlockErrorMessage = "请输入备份口令") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isDecrypting = true, unlockErrorMessage = null) }
            try {
                val inspectResult = withContext(ioDispatcher) {
                    backupManager.inspectEncryptedFullBackup(
                        rawContent = rawEnvelope,
                        passphrase = passphrase,
                    )
                }
                inspectResult
                    .onSuccess { summary ->
                        _uiState.update {
                            it.copy(
                                pendingUnlockRawEnvelope = null,
                                unlockErrorMessage = null,
                                selectedStrategy = RestoreStrategy.MERGE,
                                pendingRestore = PendingEncryptedBackupRestore(
                                    rawEnvelope = rawEnvelope,
                                    passphrase = passphrase,
                                    summary = summary,
                                ),
                            )
                        }
                    }
                    .onFailure { error ->
                        _uiState.update {
                            it.copy(
                                unlockErrorMessage = error.message ?: "口令错误或备份文件已被篡改，无法解密",
                            )
                        }
                    }
            } finally {
                _uiState.update { it.copy(isDecrypting = false) }
            }
        }
    }

    fun selectStrategy(strategy: RestoreStrategy) {
        if (_uiState.value.isRestoring) return
        _uiState.update { it.copy(selectedStrategy = strategy) }
    }

    fun dismissPendingRestore() {
        if (_uiState.value.isRestoring) return
        _uiState.update { it.copy(pendingRestore = null) }
    }

    fun confirmRestore() {
        val state = _uiState.value
        val pending = state.pendingRestore ?: return
        if (state.isRestoring) return
        val strategy = state.selectedStrategy

        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true) }
            try {
                val result = withContext(ioDispatcher) {
                    backupManager.restoreEncryptedFullBackup(
                        rawContent = pending.rawEnvelope,
                        passphrase = pending.passphrase,
                        strategy = strategy,
                    )
                }
                result
                    .onSuccess { outcome ->
                        _uiState.update { it.copy(pendingRestore = null) }
                        _events.trySend(formatEncryptedRestoreSnackbar(outcome))
                    }
                    .onFailure { error ->
                        _uiState.update { it.copy(pendingRestore = null) }
                        _events.trySend("恢复加密备份失败：${error.message ?: "本地数据已回滚"}")
                    }
            } finally {
                _uiState.update { it.copy(isRestoring = false) }
            }
        }
    }

    override fun onCleared() {
        stagedExportPassphrase = null
        super.onCleared()
    }

    companion object {
        fun factory(backupManager: BackupManager): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return EncryptedBackupViewModel(backupManager) as T
                }
            }
    }
}

@Composable
fun EncryptedBackupSettingsSection(
    backupManager: BackupManager,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext ?: context }
    val ext = NodePulseThemeTokens.extendedColors
    val viewModel: EncryptedBackupViewModel = viewModel(
        factory = EncryptedBackupViewModel.factory(backupManager),
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportToUri(appContext = appContext, uri = uri)
        } else {
            viewModel.clearStagedExportPassphrase()
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.readEncryptedFileFromUri(appContext = appContext, uri = uri)
        }
    }

    val isBusy = uiState.isBusy

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "加密完整备份 (v2)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PulseAccentPill(
                text = "AES-256-GCM",
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(
                    shape = PulseArchitecturalShapes.ElevatedTile,
                    elevation = PulseElevation.Card,
                ),
        ) {
            EncryptedBackupActionRow(
                icon = Icons.Rounded.EnhancedEncryption,
                title = "导出加密完整备份",
                subtitle = "口令加密导出全部数据：含 v1 核心数据、VPS 账本与探针 Token、补货规则、SSH 保险库（含私钥/密码）、邮箱设置、海外情报/活动偏好与卖家备注",
                actionLabel = if (uiState.isExporting) "加密中" else "导出加密包",
                loading = uiState.isExporting,
                enabled = !isBusy,
                isPrimaryAccent = true,
                onClick = viewModel::openExportDialog,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.6.dp)
                    .background(ext.cardBorder.copy(alpha = 0.65f)),
            )

            EncryptedBackupActionRow(
                icon = Icons.Rounded.LockOpen,
                title = "导入加密完整备份",
                subtitle = "输入口令解密并校验 GCM 防篡改标签，预览完整计数后选择合并或覆盖恢复",
                actionLabel = if (uiState.isReadingFile) "读取中" else "导入加密包",
                loading = uiState.isReadingFile,
                enabled = !isBusy,
                isPrimaryAccent = false,
                onClick = {
                    openDocumentLauncher.launch(
                        arrayOf("application/octet-stream", "application/json", "*/*"),
                    )
                },
            )
        }
    }

    if (uiState.showExportPassphraseDialog) {
        EncryptedBackupExportPassphraseDialog(
            onConfirmPassphrase = { passphrase ->
                viewModel.stageExportPassphrase(passphrase)
                createDocumentLauncher.launch(EncryptedBackupFileNaming.createDefaultFileName())
            },
            onDismiss = viewModel::dismissExportDialog,
        )
    }

    if (uiState.pendingUnlockRawEnvelope != null) {
        EncryptedBackupUnlockDialog(
            isDecrypting = uiState.isDecrypting,
            errorMessage = uiState.unlockErrorMessage,
            onUnlock = viewModel::unlockAndInspect,
            onDismiss = viewModel::dismissUnlockDialog,
        )
    }

    val currentPending = uiState.pendingRestore
    if (currentPending != null) {
        EncryptedBackupRestoreConfirmDialog(
            summary = currentPending.summary,
            selectedStrategy = uiState.selectedStrategy,
            isRestoring = uiState.isRestoring,
            onSelectStrategy = viewModel::selectStrategy,
            onConfirm = viewModel::confirmRestore,
            onDismiss = viewModel::dismissPendingRestore,
        )
    }
}

@Composable
private fun EncryptedBackupActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    loading: Boolean,
    enabled: Boolean,
    isPrimaryAccent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EncryptedBackupIconBadge(icon = icon)
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
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (isPrimaryAccent) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = if (isPrimaryAccent) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isPrimaryAccent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun EncryptedBackupIconBadge(
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
private fun EncryptedBackupExportPassphraseDialog(
    onConfirmPassphrase: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Strictly transient in-memory state (never rememberSaveable) so passphrase never touches Bundle.
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var inlineError by remember { mutableStateOf<String?>(null) }

    val validation = validateExportPassphrase(passphrase, confirmPassphrase)

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Lock,
        tone = PulseDialogTone.PRIMARY,
        title = "设置加密备份口令",
        titleTrailing = {
            PulseAccentPill(
                text = "v2",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        buttons = {
            Spacer(modifier = Modifier.weight(1f))
            PulseDialogSecondaryButton(
                text = "取消",
                onClick = onDismiss,
            )
            PulseDialogPrimaryButton(
                text = "下一步",
                enabled = validation is PassphraseValidationResult.Valid,
                onClick = {
                    when (val result = validateExportPassphrase(passphrase, confirmPassphrase)) {
                        PassphraseValidationResult.Valid -> onConfirmPassphrase(passphrase)
                        is PassphraseValidationResult.Invalid -> inlineError = result.message
                    }
                },
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "备份包将使用 PBKDF2-HMAC-SHA256（310,000 次迭代）+ AES-256-GCM 加密，包含 SSH 私钥/密码、探针 Token 与全部 API 密钥。请妥善牢记口令，遗忘后无法恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    inlineError = null
                },
                label = { Text("备份口令（至少 ${EncryptedBackupHeader.MIN_PASSPHRASE_LENGTH} 位）") },
                singleLine = true,
                visualTransformation = if (showPassphrase) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                trailingIcon = {
                    IconButton(onClick = { showPassphrase = !showPassphrase }) {
                        Icon(
                            imageVector = if (showPassphrase) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showPassphrase) "隐藏口令" else "显示口令",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = confirmPassphrase,
                onValueChange = {
                    confirmPassphrase = it
                    inlineError = null
                },
                label = { Text("再次确认口令") },
                singleLine = true,
                visualTransformation = if (showPassphrase) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                isError = confirmPassphrase.isNotEmpty() && passphrase != confirmPassphrase,
                modifier = Modifier.fillMaxWidth(),
            )

            val displayError = inlineError ?: when {
                passphrase.isNotEmpty() && passphrase.length < EncryptedBackupHeader.MIN_PASSPHRASE_LENGTH ->
                    "还差 ${EncryptedBackupHeader.MIN_PASSPHRASE_LENGTH - passphrase.length} 位（至少 ${EncryptedBackupHeader.MIN_PASSPHRASE_LENGTH} 位）"
                confirmPassphrase.isNotEmpty() && passphrase != confirmPassphrase ->
                    "两次输入的口令不一致"
                else -> null
            }
            if (displayError != null) {
                Text(
                    text = displayError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EncryptedBackupUnlockDialog(
    isDecrypting: Boolean,
    errorMessage: String?,
    onUnlock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.LockOpen,
        tone = PulseDialogTone.PRIMARY,
        title = "解密加密完整备份",
        buttons = {
            Spacer(modifier = Modifier.weight(1f))
            PulseDialogSecondaryButton(
                text = "取消",
                enabled = !isDecrypting,
                onClick = onDismiss,
            )
            PulseDialogPrimaryButton(
                text = if (isDecrypting) "解密中" else "解密预览",
                loading = isDecrypting,
                enabled = !isDecrypting && passphrase.isNotEmpty(),
                onClick = { onUnlock(passphrase) },
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "请输入导出时设置的口令。验证通过后将展示全部数据计数，确认前不会修改任何本地数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("解密口令") },
                singleLine = true,
                enabled = !isDecrypting,
                isError = !errorMessage.isNullOrBlank(),
                visualTransformation = if (showPassphrase) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                trailingIcon = {
                    IconButton(
                        onClick = { showPassphrase = !showPassphrase },
                        enabled = !isDecrypting,
                    ) {
                        Icon(
                            imageVector = if (showPassphrase) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showPassphrase) "隐藏口令" else "显示口令",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EncryptedBackupRestoreConfirmDialog(
    summary: EncryptedBackupPreviewSummary,
    selectedStrategy: RestoreStrategy,
    isRestoring: Boolean,
    onSelectStrategy: (RestoreStrategy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val formattedTimestamp = remember(summary.exportedAtEpochMillis) {
        formatEncryptedBackupTimestamp(summary.exportedAtEpochMillis)
    }
    val scrollState = rememberScrollState()

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.EnhancedEncryption,
        tone = if (selectedStrategy == RestoreStrategy.OVERWRITE) {
            PulseDialogTone.DESTRUCTIVE
        } else {
            PulseDialogTone.PRIMARY
        },
        title = "确认恢复加密完整备份",
        titleTrailing = {
            PulseAccentPill(
                text = "v${summary.schemaVersion}",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        buttons = {
            Spacer(modifier = Modifier.weight(1f))
            PulseDialogSecondaryButton(
                text = "取消",
                enabled = !isRestoring,
                onClick = onDismiss,
            )
            PulseDialogPrimaryButton(
                text = if (selectedStrategy == RestoreStrategy.OVERWRITE) "覆盖恢复" else "合并导入",
                tone = if (selectedStrategy == RestoreStrategy.OVERWRITE) {
                    PulseDialogTone.DESTRUCTIVE
                } else {
                    PulseDialogTone.PRIMARY
                },
                loading = isRestoring,
                enabled = !isRestoring,
                onClick = onConfirm,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Metadata & crypto verification banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.75.dp, ext.cardBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "导出于 $formattedTimestamp · 应用版本 v${summary.appVersion}",
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${summary.kdfAlgorithm} (${summary.kdfIterations / 1000}k) · ${summary.cipherAlgorithm} · 完整性已校验",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Category counts breakdown
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.75.dp, ext.cardBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EncryptedPreviewRow(
                    icon = Icons.Rounded.Tune,
                    label = "核心数据 (v1)",
                    detail = "${summary.bookmarkCount} 收藏 · ${summary.draftCount} 草稿 · ${summary.radarKeywordRuleCount} 雷达规则 · ${summary.contentFilterRuleCount} 过滤 · ${summary.blockedUserCount} 屏蔽",
                )
                EncryptedPreviewRow(
                    icon = Icons.Rounded.Dns,
                    label = "VPS 账本与探针",
                    detail = "${summary.fleetAssetCount} 项资产 · ${summary.fleetPanelCount} 个探针面板（含 Token）",
                )
                EncryptedPreviewRow(
                    icon = Icons.Rounded.Terminal,
                    label = "SSH 主机保险库",
                    detail = "${summary.sshServerCount} 台主机（含凭据） · ${summary.sshCustomPresetCount} 个自定义脚本",
                )
                EncryptedPreviewRow(
                    icon = Icons.Rounded.NotificationsActive,
                    label = "补货监控",
                    detail = "${summary.restockWatchCount} 条监控规则与轮询配置",
                )
                EncryptedPreviewRow(
                    icon = Icons.Rounded.Mail,
                    label = "Seek 邮箱设置",
                    detail = "${summary.seekMailTagCount} 个本地标签 · ${summary.seekMailArchivedCount} 封本地归档",
                )
                EncryptedPreviewRow(
                    icon = Icons.Rounded.Public,
                    label = "海外情报与活动",
                    detail = "${summary.overseasBlockedMerchantCount} 屏蔽商家 · ${summary.overseasBlockedKeywordCount} 屏蔽词 · 通知偏好",
                )
                EncryptedPreviewRow(
                    icon = Icons.Rounded.Shield,
                    label = "卖家备注与密钥",
                    detail = buildString {
                        append("${summary.sellerNoteCount} 条卖家备注")
                        if (summary.hasOfficialTradeApiKey) append(" · 含官方交易密钥")
                        if (summary.containsApiKeys) append(" · 含 API 密钥")
                        if (summary.manualFxOverrideCount > 0) append(" · ${summary.manualFxOverrideCount} 项手动汇率")
                    },
                )
            }

            // Restore strategy selection
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "选择恢复模式",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                EncryptedStrategyOptionCard(
                    title = "合并导入（推荐）",
                    description = "保留本地已有资产、主机与规则，按 ID 合并备份中的新增和更新项",
                    selected = selectedStrategy == RestoreStrategy.MERGE,
                    enabled = !isRestoring,
                    onClick = { onSelectStrategy(RestoreStrategy.MERGE) },
                )
                EncryptedStrategyOptionCard(
                    title = "覆盖恢复",
                    description = "用该加密备份替换本地全部核心数据、VPS 账本、SSH 保险库与各模块配置",
                    selected = selectedStrategy == RestoreStrategy.OVERWRITE,
                    enabled = !isRestoring,
                    isDestructive = true,
                    onClick = { onSelectStrategy(RestoreStrategy.OVERWRITE) },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "全程受事务日志保护：若中途异常将自动回滚，绝不会部分写入本地数据。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EncryptedPreviewRow(
    icon: ImageVector,
    label: String,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(15.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EncryptedStrategyOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val borderColor = when {
        selected && isDestructive -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> ext.cardBorder
    }
    val containerColor = when {
        selected && isDestructive -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
                selected && isDestructive -> MaterialTheme.colorScheme.error
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected && isDestructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ENCRYPTED_BACKUP_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatEncryptedBackupTimestamp(epochMillis: Long): String =
    runCatching {
        ENCRYPTED_BACKUP_TIME_FORMATTER.format(
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
        )
    }.getOrDefault("未知时间")

private fun writeEncryptedBackupTextToUri(
    context: Context,
    uri: Uri,
    content: String,
) {
    val output = context.contentResolver.openOutputStream(uri, "wt")
        ?: context.contentResolver.openOutputStream(uri)
        ?: throw IOException("无法打开目标备份文件进行写入")
    output.use { stream ->
        stream.write(content.toByteArray(Charsets.UTF_8))
        stream.flush()
    }
}

private fun readEncryptedBackupTextFromUri(
    context: Context,
    uri: Uri,
): String {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("无法打开所选备份文件")
    input.use { stream ->
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ENCRYPTED_BACKUP_BYTES) {
                throw IOException("备份文件超过 32 MB 限制")
            }
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
