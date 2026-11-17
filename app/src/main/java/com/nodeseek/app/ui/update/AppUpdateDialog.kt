package com.nodeseek.app.ui.update

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nodeseek.app.domain.update.AppUpdateState
import com.nodeseek.app.domain.update.UpdateDownloadPhase
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseDialogPrimaryButton
import com.nodeseek.app.ui.components.PulseDialogSecondaryButton
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onIgnoreVersion: (tagName: String) -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallReadyApk: () -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onOpenBrowserUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val release = state.latestRelease ?: return
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography
    val phase = state.downloadPhase
    val isDownloading = phase is UpdateDownloadPhase.Downloading
    val apkAsset = release.apkAsset

    PulseAlertDialog(
        onDismissRequest = {
            if (!isDownloading) {
                onDismiss()
            }
        },
        modifier = modifier,
        title = "发现新版本 ${release.tagName}",
        icon = Icons.Rounded.SystemUpdate,
        tone = PulseDialogTone.PRIMARY,
        titleTrailing = if (release.isPrerelease) {
            {
                PulseAccentPill(
                    text = "Pre-release",
                    containerColor = ext.drumstickContainer,
                    contentColor = ext.onDrumstickContainer,
                )
            }
        } else {
            null
        },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading,
        ),
        buttons = {
            when (phase) {
                UpdateDownloadPhase.Idle -> {
                    TextButton(
                        onClick = { onIgnoreVersion(release.tagName) },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "忽略此版本",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    PulseDialogSecondaryButton(
                        text = "稍后提醒",
                        onClick = onDismiss,
                    )
                    if (apkAsset != null) {
                        PulseDialogPrimaryButton(
                            text = "立即更新",
                            leadingIcon = Icons.Rounded.Download,
                            onClick = onStartDownload,
                        )
                    } else {
                        PulseDialogPrimaryButton(
                            text = "前往下载",
                            leadingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { onOpenBrowserUrl(release.htmlUrl) },
                        )
                    }
                }

                is UpdateDownloadPhase.Downloading -> {
                    Spacer(modifier = Modifier.weight(1f))
                    PulseDialogSecondaryButton(
                        text = "取消下载",
                        onClick = onCancelDownload,
                    )
                    PulseDialogPrimaryButton(
                        text = "后台下载",
                        useTonal = true,
                        onClick = onDismiss,
                    )
                }

                is UpdateDownloadPhase.ReadyToInstall -> {
                    Spacer(modifier = Modifier.weight(1f))
                    PulseDialogSecondaryButton(
                        text = if (phase.requiresInstallPermission) "稍后" else "关闭",
                        onClick = onDismiss,
                    )
                    if (phase.requiresInstallPermission) {
                        PulseDialogPrimaryButton(
                            text = "去开启权限",
                            tone = PulseDialogTone.WARM,
                            leadingIcon = Icons.Rounded.Security,
                            onClick = onOpenUnknownSourcesSettings,
                        )
                    } else {
                        PulseDialogPrimaryButton(
                            text = "立即安装",
                            leadingIcon = Icons.Rounded.InstallMobile,
                            onClick = onInstallReadyApk,
                        )
                    }
                }

                is UpdateDownloadPhase.Failed -> {
                    val fallbackUrl = apkAsset?.downloadUrl?.takeIf { it.isNotBlank() } ?: release.htmlUrl
                    TextButton(
                        onClick = { onOpenBrowserUrl(fallbackUrl) },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "浏览器下载",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    PulseDialogSecondaryButton(
                        text = "关闭",
                        onClick = onDismiss,
                    )
                    if (apkAsset != null) {
                        PulseDialogPrimaryButton(
                            text = "重试下载",
                            onClick = onStartDownload,
                        )
                    }
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Version comparison & asset telemetry pills
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseAccentPill(
                    text = "v${state.currentVersionName} → v${release.versionName}",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (apkAsset != null && apkAsset.sizeBytes > 0L) {
                    PulseAccentPill(
                        text = formatUpdateByteSize(apkAsset.sizeBytes),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (apkAsset?.sha256 != null) {
                    PulseAccentPill(
                        text = "SHA-256 校验",
                        icon = Icons.Rounded.VerifiedUser,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (release.publishedAtIso.isNotBlank()) {
                    PulseAccentPill(
                        text = formatRelativeTimeChinese(release.publishedAtIso),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Scrollable GitHub Release Changelog card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        elevation = PulseElevation.None,
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (release.title.isNotBlank() && release.title != release.tagName) {
                            release.title
                        } else {
                            "更新内容"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onOpenBrowserUrl(release.htmlUrl) },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "在浏览器打开 GitHub Release",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 230.dp)
                        .padding(end = 6.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownRenderer(
                        markdown = release.changelogMarkdown.ifBlank { "本次发布包含性能改进与稳定性修复。" },
                        bodyStyle = MaterialTheme.typography.bodySmall,
                        onUrlClick = onOpenBrowserUrl,
                    )
                }
            }

            // Phase-specific status / progress / permission banner
            when (phase) {
                UpdateDownloadPhase.Idle -> {
                    if (apkAsset == null) {
                        Text(
                            text = "当前 Release 未附带 .apk 安装包资源，可前往 GitHub 页面手动查看下载。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is UpdateDownloadPhase.Downloading -> {
                    val rawFraction = phase.progressFraction
                    val animatedProgress by animateFloatAsState(
                        targetValue = rawFraction ?: 0f,
                        label = "updateDownloadProgress",
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = if (isDark) 0.28f else 0.45f,
                                ),
                            )
                            .border(
                                width = 0.8.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                shape = RoundedCornerShape(14.dp),
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "正在下载安装包…",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val statsLabel = if (phase.totalBytes > 0L) {
                                val pct = ((rawFraction ?: 0f) * 100f).roundToInt().coerceIn(0, 100)
                                "${formatUpdateByteSize(phase.downloadedBytes)} / ${formatUpdateByteSize(phase.totalBytes)} · $pct%"
                            } else {
                                formatUpdateByteSize(phase.downloadedBytes)
                            }
                            Text(
                                text = statsLabel,
                                style = extraType.tabularBadge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                        if (rawFraction != null) {
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                            )
                        }
                    }
                }

                is UpdateDownloadPhase.ReadyToInstall -> {
                    val containerColor = if (phase.requiresInstallPermission) {
                        ext.drumstickContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                    val contentColor = if (phase.requiresInstallPermission) {
                        ext.onDrumstickContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(containerColor)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (phase.requiresInstallPermission) {
                                Icons.Rounded.Security
                            } else {
                                Icons.Rounded.CheckCircle
                            },
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (phase.requiresInstallPermission) {
                                "安装包已校验完成。请先在系统设置中允许 Anode「安装未知应用」，返回后将自动继续安装。"
                            } else {
                                "安装包已完成校验，点击「立即安装」即可开始升级。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is UpdateDownloadPhase.Failed -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = phase.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

internal fun formatUpdateByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        return String.format(Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
    }
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}
