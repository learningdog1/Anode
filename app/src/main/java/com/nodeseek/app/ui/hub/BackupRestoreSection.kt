package com.nodeseek.app.ui.hub

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.backup.BackupFileNaming
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.backup.BackupPreviewSummary
import com.nodeseek.app.domain.backup.BackupRestoreSummary
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseDialogPrimaryButton
import com.nodeseek.app.ui.components.PulseDialogSecondaryButton
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_BACKUP_FILE_BYTES = 32 * 1024 * 1024

private data class PendingBackupRestore(
    val rawJson: String,
    val summary: BackupPreviewSummary,
)

@Composable
fun BackupRestoreSettingsSection(
    backupManager: BackupManager,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ext = NodePulseThemeTokens.extendedColors

    var includeApiKeys by rememberSaveable { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }
    var isInspecting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<PendingBackupRestore?>(null) }
    var selectedStrategy by rememberSaveable { mutableStateOf(RestoreStrategy.MERGE) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isExporting = true
            try {
                val payload = withContext(Dispatchers.IO) {
                    backupManager.exportBackupPayload(includeApiKeys = includeApiKeys)
                }.getOrElse { error ->
                    snackbarHostState.showSnackbar("导出备份失败：${error.message ?: "生成备份失败"}")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    writeTextToUri(context, uri, payload.rawJson)
                }
                val bundle = payload.bundle
                val keySuffix = if (includeApiKeys) "含 API 密钥" else "已脱敏排除密钥"
                snackbarHostState.showSnackbar(
                    "备份已导出（${bundle.bookmarks.size} 收藏 · ${bundle.drafts.size} 草稿 · ${bundle.radar.keywordRules.size} 雷达规则 · $keySuffix）",
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar("导出备份失败：${t.message ?: "写入文件失败"}")
            } finally {
                isExporting = false
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isInspecting = true
            try {
                val rawJson = withContext(Dispatchers.IO) {
                    readTextFromUri(context, uri)
                }
                val previewResult = withContext(Dispatchers.IO) {
                    backupManager.inspectBackup(rawJson)
                }
                previewResult
                    .onSuccess { summary ->
                        selectedStrategy = RestoreStrategy.MERGE
                        pendingRestore = PendingBackupRestore(
                            rawJson = rawJson,
                            summary = summary,
                        )
                    }
                    .onFailure { error ->
                        snackbarHostState.showSnackbar(error.message ?: "无法识别该备份文件")
                    }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar("读取备份文件失败：${t.message ?: "文件读取异常"}")
            } finally {
                isInspecting = false
            }
        }
    }

    val isBusy = isExporting || isInspecting || isRestoring

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "数据备份与恢复",
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
        ) {
            BackupActionRow(
                icon = Icons.Rounded.CloudUpload,
                title = "导出完整备份",
                subtitle = "导出雷达规则、屏蔽列表、本地收藏、草稿与全部设置",
                actionLabel = if (isExporting) "导出中" else "导出 JSON",
                loading = isExporting,
                enabled = !isBusy,
                isPrimaryAccent = true,
                onClick = {
                    createDocumentLauncher.launch(BackupFileNaming.createDefaultFileName())
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.6.dp)
                    .background(ext.cardBorder.copy(alpha = 0.65f)),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isBusy, role = Role.Switch) {
                        includeApiKeys = !includeApiKeys
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BackupRowIconBadge(
                    icon = Icons.Rounded.Key,
                    tint = if (includeApiKeys) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    containerColor = if (includeApiKeys) {
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
                        text = "包含 API 密钥 (图床 / SeekMail)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (includeApiKeys) {
                            "导出的 JSON 将包含当前保存的 NodeImage 与 SeekMail 密钥"
                        } else {
                            "已脱敏：导出文件中密钥字段将自动留空"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = includeApiKeys,
                    onCheckedChange = { if (!isBusy) includeApiKeys = it },
                    enabled = !isBusy,
                    thumbContent = if (includeApiKeys) {
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.6.dp)
                    .background(ext.cardBorder.copy(alpha = 0.65f)),
            )

            BackupActionRow(
                icon = Icons.Rounded.Restore,
                title = "从备份文件恢复",
                subtitle = "选择 JSON 备份文件，导入前可预览计数并选择合并或覆盖",
                actionLabel = if (isInspecting) "解析中" else "导入恢复",
                loading = isInspecting,
                enabled = !isBusy,
                isPrimaryAccent = false,
                onClick = {
                    openDocumentLauncher.launch(arrayOf("application/json", "*/*"))
                },
            )
        }
    }

    val currentPending = pendingRestore
    if (currentPending != null) {
        BackupRestoreConfirmDialog(
            summary = currentPending.summary,
            selectedStrategy = selectedStrategy,
            isRestoring = isRestoring,
            onSelectStrategy = { selectedStrategy = it },
            onConfirm = {
                scope.launch {
                    isRestoring = true
                    try {
                        val result = withContext(Dispatchers.IO) {
                            backupManager.restoreBackup(
                                rawJson = currentPending.rawJson,
                                strategy = selectedStrategy,
                            )
                        }
                        result
                            .onSuccess { outcome ->
                                pendingRestore = null
                                snackbarHostState.showSnackbar(formatRestoreOutcomeSnackbar(outcome))
                            }
                            .onFailure { error ->
                                pendingRestore = null
                                snackbarHostState.showSnackbar("恢复失败：${error.message ?: "未知错误"}")
                            }
                    } finally {
                        isRestoring = false
                    }
                }
            },
            onDismiss = {
                if (!isRestoring) {
                    pendingRestore = null
                }
            },
        )
    }
}

@Composable
private fun BackupActionRow(
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
        BackupRowIconBadge(icon = icon)
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
                maxLines = 2,
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
private fun BackupRowIconBadge(
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
private fun BackupRestoreConfirmDialog(
    summary: BackupPreviewSummary,
    selectedStrategy: RestoreStrategy,
    isRestoring: Boolean,
    onSelectStrategy: (RestoreStrategy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val formattedTimestamp = remember(summary.exportedAtEpochMillis) {
        formatBackupTimestamp(summary.exportedAtEpochMillis)
    }
    val scrollState = rememberScrollState()

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Restore,
        tone = if (selectedStrategy == RestoreStrategy.OVERWRITE) {
            PulseDialogTone.DESTRUCTIVE
        } else {
            PulseDialogTone.PRIMARY
        },
        title = "确认恢复备份",
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
                onClick = onDismiss,
                enabled = !isRestoring,
            )
            PulseDialogPrimaryButton(
                text = if (selectedStrategy == RestoreStrategy.OVERWRITE) "覆盖恢复" else "合并导入",
                onClick = onConfirm,
                tone = if (selectedStrategy == RestoreStrategy.OVERWRITE) {
                    PulseDialogTone.DESTRUCTIVE
                } else {
                    PulseDialogTone.PRIMARY
                },
                loading = isRestoring,
                enabled = !isRestoring,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Backup metadata banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.75.dp, ext.cardBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "导出于 $formattedTimestamp",
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "应用版本 v${summary.appVersion}",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PulseAccentPill(
                    text = if (summary.containsApiKeys) "含 API 密钥" else "不含密钥",
                    containerColor = if (summary.containsApiKeys) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (summary.containsApiKeys) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // Item counts across all 5 categories
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.75.dp, ext.cardBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackupPreviewCategoryRow(
                    icon = Icons.Rounded.Tune,
                    label = "偏好设置",
                    detail = buildString {
                        append("外观、排版与社区偏好")
                        if (summary.recentSearchQueryCount > 0) {
                            append(" · ${summary.recentSearchQueryCount} 条搜索记录")
                        }
                    },
                    countBadge = if (summary.containsApiKeys) "含密钥" else "已包含",
                )
                BackupPreviewCategoryRow(
                    icon = Icons.Rounded.Radar,
                    label = "脉搏雷达",
                    detail = "关键词规则 ${summary.radarKeywordRuleCount} 条 · 追踪主题 ${summary.radarTrackedTopicCount} 个",
                    countBadge = "${summary.radarKeywordRuleCount + summary.radarTrackedTopicCount}",
                )
                BackupPreviewCategoryRow(
                    icon = Icons.Rounded.FilterAlt,
                    label = "屏蔽列表",
                    detail = buildString {
                        append("屏蔽用户 ${summary.blockedUserCount} 人 · 内容规则 ${summary.contentFilterRuleCount} 条")
                        if (summary.mutedCategoryCount > 0) {
                            append(" · 版块 ${summary.mutedCategoryCount} 个")
                        }
                    },
                    countBadge = "${summary.blockedUserCount + summary.contentFilterRuleCount}",
                )
                BackupPreviewCategoryRow(
                    icon = Icons.Rounded.Bookmark,
                    label = "本地收藏",
                    detail = "收藏摘要与离线详情快照",
                    countBadge = "${summary.bookmarkCount}",
                )
                BackupPreviewCategoryRow(
                    icon = Icons.Rounded.EditNote,
                    label = "本地草稿",
                    detail = "发帖与回复编辑草稿",
                    countBadge = "${summary.draftCount}",
                )
            }

            // Restore strategy selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "选择恢复方式",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RestoreStrategyOptionCard(
                    selected = selectedStrategy == RestoreStrategy.MERGE,
                    title = "合并导入 (推荐)",
                    description = "保留现有本地数据并去重追加，空白密钥不会覆盖本地已保存的 API 密钥",
                    isDestructive = false,
                    enabled = !isRestoring,
                    onClick = { onSelectStrategy(RestoreStrategy.MERGE) },
                )

                RestoreStrategyOptionCard(
                    selected = selectedStrategy == RestoreStrategy.OVERWRITE,
                    title = "覆盖恢复",
                    description = "替换本地现有雷达规则、追踪主题、内容过滤、收藏、草稿与偏好设置",
                    isDestructive = true,
                    enabled = !isRestoring,
                    onClick = { onSelectStrategy(RestoreStrategy.OVERWRITE) },
                )
            }
        }
    }
}

@Composable
private fun BackupPreviewCategoryRow(
    icon: ImageVector,
    label: String,
    detail: String,
    countBadge: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = countBadge,
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RestoreStrategyOptionCard(
    selected: Boolean,
    title: String,
    description: String,
    isDestructive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val accentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val bgColor by animateColorAsState(
        targetValue = when {
            selected && isDestructive -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "strategyBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) accentColor else ext.cardBorder,
        label = "strategyBorder",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(bgColor)
            .border(
                width = if (selected) 1.25.dp else 0.75.dp,
                color = borderColor,
                shape = RoundedCornerShape(13.dp),
            )
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (selected) {
                Icons.Rounded.RadioButtonChecked
            } else {
                Icons.Rounded.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun formatBackupTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "未知时间"
    return runCatching {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }.getOrDefault("未知时间")
}

private fun formatRestoreOutcomeSnackbar(outcome: BackupRestoreSummary): String {
    val modeLabel = if (outcome.strategy == RestoreStrategy.MERGE) "合并导入" else "覆盖恢复"
    val base = buildString {
        append("$modeLabel 完成：")
        append("雷达规则 ${outcome.radarKeywordRulesImported} 条")
        append(" · 追踪 ${outcome.radarTrackedTopicsImported} 帖")
        append(" · 屏蔽 ${outcome.blockedUsersImported} 人")
        if (outcome.contentFilterRulesImported > 0) {
            append(" · 过滤 ${outcome.contentFilterRulesImported} 条")
        }
        append(" · 收藏 ${outcome.bookmarksImported} 条")
        append(" · 草稿 ${outcome.draftsImported} 篇")
    }
    return if (outcome.blockedUsersServerSyncSkippedOrFailed > 0) {
        "$base（${outcome.blockedUsersServerSyncSkippedOrFailed} 位屏蔽用户仅保留本地，登录在线后可同步）"
    } else {
        base
    }
}

private fun writeTextToUri(
    context: Context,
    uri: Uri,
    text: String,
) {
    val stream = context.contentResolver.openOutputStream(uri, "wt")
        ?: context.contentResolver.openOutputStream(uri)
        ?: throw IOException("无法打开导出目标文件")
    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(text)
        writer.flush()
    }
}

private fun readTextFromUri(
    context: Context,
    uri: Uri,
): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("无法打开选中的备份文件")
    return stream.use { input ->
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BACKUP_FILE_BYTES) {
                throw IOException("备份文件过大，已超过 32MB 限制")
            }
            out.write(buffer, 0, read)
        }
        out.toString(Charsets.UTF_8.name())
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewBackupRestoreConfirmDialog() {
    NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT, dynamicColor = false) {
        BackupRestoreConfirmDialog(
            summary = BackupPreviewSummary(
                schemaVersion = 1,
                appVersion = "2.0.1",
                exportedAtEpochMillis = 1_763_480_000_000L,
                radarKeywordRuleCount = 4,
                radarTrackedTopicCount = 3,
                blockedUserCount = 5,
                contentFilterRuleCount = 6,
                mutedCategoryCount = 2,
                bookmarkCount = 18,
                draftCount = 2,
                recentSearchQueryCount = 7,
                containsApiKeys = true,
            ),
            selectedStrategy = RestoreStrategy.MERGE,
            isRestoring = false,
            onSelectStrategy = {},
            onConfirm = {},
            onDismiss = {},
        )
    }
}
