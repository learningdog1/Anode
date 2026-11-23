package com.nodeseek.app.ui.update

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.nodeseek.app.domain.update.AppReleaseInfo
import com.nodeseek.app.domain.update.AppUpdateState
import com.nodeseek.app.domain.update.UpdateDownloadPhase
import com.nodeseek.app.ui.components.PulseDialogPrimaryButton
import com.nodeseek.app.ui.components.PulseDialogSecondaryButton
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val phase = state.downloadPhase
    val isDownloading = phase is UpdateDownloadPhase.Downloading
    val apkAsset = release.apkAsset
    val primaryColor = MaterialTheme.colorScheme.primary
    val dialogShape = PulseArchitecturalShapes.SheetContainer

    val topWashBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to primaryColor.copy(alpha = if (isDark) 0.14f else 0.065f),
            0.34f to primaryColor.copy(alpha = if (isDark) 0.035f else 0.015f),
            1.0f to Color.Transparent,
        ),
    )
    val borderBrush = PulseBrushes.cardBorderBrush(
        accentColor = primaryColor,
        isDark = isDark,
    )

    BasicAlertDialog(
        onDismissRequest = {
            if (!isDownloading) {
                onDismiss()
            }
        },
        modifier = modifier,
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(min = 290.dp, max = 424.dp),
            shape = dialogShape,
            color = ext.cardSurfaceElevated,
            tonalElevation = 0.dp,
            shadowElevation = if (isDark) PulseElevation.FloatingBar else 14.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(dialogShape)
                    .background(topWashBrush)
                    .border(width = 1.dp, brush = borderBrush, shape = dialogShape)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. Hierarchical Header: Icon badge + Title + Version transition + External link
                UpdateDialogHeader(
                    currentVersionName = state.currentVersionName,
                    release = release,
                    onOpenReleasePage = { onOpenBrowserUrl(release.htmlUrl) },
                )

                // 2. Clean Release Metadata Strip (size · publish time · SHA-256 verification)
                val publishedLabel = remember(release.publishedAtIso) {
                    formatUpdatePublishedLabel(release.publishedAtIso)
                }
                val hasMeta = (apkAsset != null && apkAsset.sizeBytes > 0L) ||
                    publishedLabel.isNotBlank() ||
                    apkAsset?.sha256 != null

                if (hasMeta) {
                    UpdateMetaStrip(
                        sizeText = apkAsset?.takeIf { it.sizeBytes > 0L }?.let { formatUpdateByteSize(it.sizeBytes) },
                        publishedText = publishedLabel.takeIf { it.isNotBlank() },
                        hasSha256 = apkAsset?.sha256 != null,
                    )
                }

                // 3. Scrollable Editorial Release Changelog
                UpdateChangelogCard(
                    release = release,
                    onOpenBrowserUrl = onOpenBrowserUrl,
                )

                // 4. Phase-specific Download Progress / Ready / Error Banner
                UpdatePhaseBanner(
                    phase = phase,
                    hasApkAsset = apkAsset != null,
                )

                // 5. Balanced Action Buttons
                UpdateActionFooter(
                    phase = phase,
                    release = release,
                    hasApkAsset = apkAsset != null,
                    onDismiss = onDismiss,
                    onIgnoreVersion = onIgnoreVersion,
                    onStartDownload = onStartDownload,
                    onCancelDownload = onCancelDownload,
                    onInstallReadyApk = onInstallReadyApk,
                    onOpenUnknownSourcesSettings = onOpenUnknownSourcesSettings,
                    onOpenBrowserUrl = onOpenBrowserUrl,
                )
            }
        }
    }
}

@Composable
private fun UpdateDialogHeader(
    currentVersionName: String,
    release: AppReleaseInfo,
    onOpenReleasePage: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val primaryColor = MaterialTheme.colorScheme.primary
    val iconShape = RoundedCornerShape(13.dp)
    val iconBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            blendColor(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.secondaryContainer,
                0.32f,
            ),
        ),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(iconShape)
                .background(iconBrush)
                .border(
                    width = 0.9.dp,
                    color = primaryColor.copy(alpha = if (isDark) 0.42f else 0.24f),
                    shape = iconShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = "发现新版本",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (release.isPrerelease) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ext.drumstickContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "预发布",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = ext.onDrumstickContainer,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = release.tagName.ifBlank { "v${release.versionName}" },
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = "当前 v$currentVersionName",
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                        alpha = if (isDark) 0.72f else 0.78f,
                    ),
                )
                .clickable(
                    role = Role.Button,
                    onClickLabel = "在浏览器查看 GitHub Release",
                    onClick = onOpenReleasePage,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = "在浏览器打开 GitHub Release",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateMetaStrip(
    sizeText: String?,
    publishedText: String?,
    hasSha256: Boolean,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val stripShape = RoundedCornerShape(12.dp)

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clip(stripShape)
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow.copy(
                    alpha = if (isDark) 0.85f else 0.92f,
                ),
            )
            .border(
                width = 0.75.dp,
                color = ext.cardBorder.copy(alpha = if (isDark) 0.65f else 0.75f),
                shape = stripShape,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!sizeText.isNullOrBlank()) {
            UpdateMetaItem(
                icon = Icons.Rounded.Inventory2,
                text = sizeText,
            )
        }
        if (!publishedText.isNullOrBlank()) {
            UpdateMetaItem(
                icon = Icons.Rounded.Schedule,
                text = publishedText,
            )
        }
        if (hasSha256) {
            UpdateMetaItem(
                icon = Icons.Rounded.VerifiedUser,
                text = "SHA-256 校验",
                accent = true,
            )
        }
    }
}

@Composable
private fun UpdateMetaItem(
    icon: ImageVector,
    text: String,
    accent: Boolean = false,
) {
    val contentColor = if (accent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UpdateChangelogCard(
    release: AppReleaseInfo,
    onOpenBrowserUrl: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val cardShape = PulseArchitecturalShapes.EditorialCard
    val cardBg = MaterialTheme.colorScheme.surfaceContainerLow
    val scrollState = rememberScrollState()

    val normalizedMarkdown = remember(release.changelogMarkdown) {
        release.changelogMarkdown.trim().ifBlank { "本次发布包含性能改进与稳定性修复。" }
    }
    val blocks = remember(normalizedMarkdown) {
        parseReleaseChangelogBlocks(normalizedMarkdown)
    }

    val headerLabel = remember(release.title, release.tagName, release.versionName) {
        val cleanTitle = release.title.trim()
        val isRedundantVersionTitle = cleanTitle.isBlank() ||
            cleanTitle.equals(release.tagName.trim(), ignoreCase = true) ||
            cleanTitle.equals("v${release.versionName.trim()}", ignoreCase = true) ||
            cleanTitle.equals(release.versionName.trim(), ignoreCase = true)
        if (isRedundantVersionTitle) "更新日志" else cleanTitle
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(cardBg)
            .border(
                width = 0.8.dp,
                color = ext.cardBorder.copy(alpha = if (isDark) 0.72f else 0.82f),
                shape = cardShape,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = headerLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Release Notes",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = 1,
                softWrap = false,
            )
        }

        PulseGradientDivider(color = ext.cardBorder)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp, max = 252.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ReleaseChangelogBlocksView(
                    blocks = blocks,
                    onUrlClick = onOpenBrowserUrl,
                )
            }

            if (scrollState.canScrollBackward) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(cardBg, Color.Transparent),
                            ),
                        ),
                )
            }
            if (scrollState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, cardBg),
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun UpdatePhaseBanner(
    phase: UpdateDownloadPhase,
    hasApkAsset: Boolean,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val bannerShape = RoundedCornerShape(14.dp)

    when (phase) {
        UpdateDownloadPhase.Idle -> {
            if (!hasApkAsset) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(bannerShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = "当前 Release 未附带 .apk 安装包，可前往 GitHub 页面手动下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is UpdateDownloadPhase.Downloading -> {
            val rawFraction = phase.progressFraction
            val animatedProgress by animateFloatAsState(
                targetValue = rawFraction ?: 0f,
                label = "updateDownloadProgress",
            )
            val pctText = rawFraction?.let { "${(it * 100f).roundToInt().coerceIn(0, 100)}%" }
            val bytesText = if (phase.totalBytes > 0L) {
                "${formatUpdateByteSize(phase.downloadedBytes)} / ${formatUpdateByteSize(phase.totalBytes)}"
            } else {
                formatUpdateByteSize(phase.downloadedBytes)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(bannerShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = if (isDark) 0.24f else 0.36f,
                        ),
                    )
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.36f else 0.24f),
                        shape = bannerShape,
                    )
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
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
                    Text(
                        text = pctText ?: bytesText,
                        style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }

                val trackColor = MaterialTheme.colorScheme.primary.copy(
                    alpha = if (isDark) 0.22f else 0.16f,
                )
                if (rawFraction != null) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = trackColor,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = trackColor,
                        strokeCap = StrokeCap.Round,
                    )
                }

                if (pctText != null) {
                    Text(
                        text = bytesText,
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        is UpdateDownloadPhase.ReadyToInstall -> {
            val containerColor = if (phase.requiresInstallPermission) {
                ext.drumstickContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.32f else 0.48f)
            }
            val borderColor = if (phase.requiresInstallPermission) {
                ext.drumstickGoldEnd.copy(alpha = if (isDark) 0.36f else 0.26f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.36f else 0.24f)
            }
            val contentColor = if (phase.requiresInstallPermission) {
                ext.onDrumstickContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(bannerShape)
                    .background(containerColor)
                    .border(width = 0.8.dp, color = borderColor, shape = bannerShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                    modifier = Modifier.size(19.dp),
                )
                Text(
                    text = if (phase.requiresInstallPermission) {
                        "安装包已校验完成，请先在系统设置中允许 Anode「安装未知应用」，返回后自动继续。"
                    } else {
                        "安装包已通过完整性校验，点击下方「立即安装」开始升级。"
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
                    .clip(bannerShape)
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(
                            alpha = if (isDark) 0.42f else 0.68f,
                        ),
                    )
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = if (isDark) 0.38f else 0.25f),
                        shape = bannerShape,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(19.dp),
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

@Composable
private fun UpdateActionFooter(
    phase: UpdateDownloadPhase,
    release: AppReleaseInfo,
    hasApkAsset: Boolean,
    onDismiss: () -> Unit,
    onIgnoreVersion: (tagName: String) -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallReadyApk: () -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onOpenBrowserUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (phase) {
            UpdateDownloadPhase.Idle -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseDialogSecondaryButton(
                        text = "稍后提醒",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasApkAsset) {
                        PulseDialogPrimaryButton(
                            text = "立即更新",
                            leadingIcon = Icons.Rounded.Download,
                            onClick = onStartDownload,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        PulseDialogPrimaryButton(
                            text = "前往下载",
                            leadingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { onOpenBrowserUrl(release.htmlUrl) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                TextButton(
                    onClick = { onIgnoreVersion(release.tagName) },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 32.dp),
                ) {
                    Text(
                        text = "忽略此版本",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            is UpdateDownloadPhase.Downloading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseDialogSecondaryButton(
                        text = "取消下载",
                        onClick = onCancelDownload,
                        modifier = Modifier.weight(1f),
                    )
                    PulseDialogPrimaryButton(
                        text = "后台下载",
                        useTonal = true,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            is UpdateDownloadPhase.ReadyToInstall -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseDialogSecondaryButton(
                        text = if (phase.requiresInstallPermission) "稍后" else "关闭",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    if (phase.requiresInstallPermission) {
                        PulseDialogPrimaryButton(
                            text = "去开启权限",
                            tone = PulseDialogTone.WARM,
                            leadingIcon = Icons.Rounded.Security,
                            onClick = onOpenUnknownSourcesSettings,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        PulseDialogPrimaryButton(
                            text = "立即安装",
                            leadingIcon = Icons.Rounded.InstallMobile,
                            onClick = onInstallReadyApk,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            is UpdateDownloadPhase.Failed -> {
                val fallbackUrl = release.apkAsset?.downloadUrl?.takeIf { it.isNotBlank() } ?: release.htmlUrl
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseDialogSecondaryButton(
                        text = "关闭",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasApkAsset) {
                        PulseDialogPrimaryButton(
                            text = "重试下载",
                            leadingIcon = Icons.Rounded.Refresh,
                            onClick = onStartDownload,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        PulseDialogPrimaryButton(
                            text = "浏览器下载",
                            leadingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { onOpenBrowserUrl(fallbackUrl) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (hasApkAsset) {
                    TextButton(
                        onClick = { onOpenBrowserUrl(fallbackUrl) },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 32.dp),
                    ) {
                        Text(
                            text = "改用浏览器下载",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ── Clean, Proportional Release Notes Markdown Renderer ──────────────────────

@Immutable
internal sealed interface ReleaseChangelogBlock {
    @Immutable
    data class Heading(val level: Int, val text: String) : ReleaseChangelogBlock

    @Immutable
    data class Paragraph(val text: String) : ReleaseChangelogBlock

    @Immutable
    data class ListBlock(val items: List<ReleaseListItem>) : ReleaseChangelogBlock

    @Immutable
    data class Quote(val text: String) : ReleaseChangelogBlock

    @Immutable
    data class CodeBlock(val text: String) : ReleaseChangelogBlock

    @Immutable
    data object Divider : ReleaseChangelogBlock
}

@Immutable
internal data class ReleaseListItem(
    val text: String,
    val indentLevel: Int = 0,
    val orderNumber: Int? = null,
    val checked: Boolean? = null,
)

@Composable
private fun ReleaseChangelogBlocksView(
    blocks: List<ReleaseChangelogBlock>,
    onUrlClick: (String) -> Unit,
) {
    val scale = NodePulseThemeTokens.fontScale
    val isDark = NodePulseThemeTokens.isDark
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val inlineCodeBg = MaterialTheme.colorScheme.primaryContainer.copy(
        alpha = if (isDark) 0.34f else 0.46f,
    )
    val latestOnUrlClick by rememberUpdatedState(onUrlClick)
    val dispatchUrlClick: (String) -> Unit = remember {
        { url -> latestOnUrlClick(url) }
    }

    val bodyTextColor = onSurface.copy(alpha = if (isDark) 0.88f else 0.86f)
    val bodyStyle = remember(scale) {
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = (14.2f * scale).sp,
            lineHeight = (22.0f * scale).sp,
            letterSpacing = 0.15.sp,
        )
    }
    val headingPrimaryStyle = remember(scale) {
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (14.8f * scale).sp,
            lineHeight = (21.0f * scale).sp,
            letterSpacing = 0.05.sp,
        )
    }
    val headingSubStyle = remember(scale) {
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = (13.8f * scale).sp,
            lineHeight = (20.0f * scale).sp,
            letterSpacing = 0.1.sp,
        )
    }
    val codeBoxStyle = remember(scale) {
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = (12.6f * scale).sp,
            lineHeight = (19.0f * scale).sp,
            letterSpacing = 0.1.sp,
        )
    }

    blocks.forEachIndexed { index, block ->
        when (block) {
            is ReleaseChangelogBlock.Heading -> {
                val topPad = if (index == 0) 0.dp else 4.dp
                val isTopHeading = block.level <= 2
                val annotated = remember(block.text, onSurface, onSurfaceVariant, primary, inlineCodeBg) {
                    buildReleaseInlineAnnotatedString(
                        raw = block.text,
                        strongColor = onSurface,
                        mutedColor = onSurfaceVariant,
                        linkColor = primary,
                        inlineCodeBg = inlineCodeBg,
                        onUrlClick = dispatchUrlClick,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topPad),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (isTopHeading) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(13.dp)
                                .clip(CircleShape)
                                .background(primary),
                        )
                    }
                    Text(
                        text = annotated,
                        style = if (isTopHeading) headingPrimaryStyle else headingSubStyle,
                        color = if (isTopHeading) onSurface else primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            is ReleaseChangelogBlock.Paragraph -> {
                val annotated = remember(block.text, onSurface, onSurfaceVariant, primary, inlineCodeBg) {
                    buildReleaseInlineAnnotatedString(
                        raw = block.text,
                        strongColor = onSurface,
                        mutedColor = onSurfaceVariant,
                        linkColor = primary,
                        inlineCodeBg = inlineCodeBg,
                        onUrlClick = dispatchUrlClick,
                    )
                }
                Text(
                    text = annotated,
                    style = bodyStyle,
                    color = bodyTextColor,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is ReleaseChangelogBlock.ListBlock -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    block.items.forEach { item ->
                        val annotated = remember(item.text, onSurface, onSurfaceVariant, primary, inlineCodeBg) {
                            buildReleaseInlineAnnotatedString(
                                raw = item.text,
                                strongColor = onSurface,
                                mutedColor = onSurfaceVariant,
                                linkColor = primary,
                                inlineCodeBg = inlineCodeBg,
                                onUrlClick = dispatchUrlClick,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (item.indentLevel.coerceAtMost(2) * 14).dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when {
                                item.checked != null -> {
                                    Icon(
                                        imageVector = if (item.checked) {
                                            Icons.Rounded.CheckCircle
                                        } else {
                                            Icons.Rounded.RadioButtonUnchecked
                                        },
                                        contentDescription = null,
                                        tint = if (item.checked) primary else onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(top = 3.dp)
                                            .size(14.dp),
                                    )
                                }

                                item.orderNumber != null -> {
                                    Text(
                                        text = "${item.orderNumber}.",
                                        style = bodyStyle.withTabularNumbers(),
                                        fontWeight = FontWeight.SemiBold,
                                        color = primary,
                                        modifier = Modifier.widthIn(min = 18.dp),
                                    )
                                }

                                item.indentLevel == 0 -> {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(primary.copy(alpha = 0.85f)),
                                    )
                                }

                                else -> {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .size(4.5.dp)
                                            .border(
                                                width = 1.dp,
                                                color = onSurfaceVariant.copy(alpha = 0.7f),
                                                shape = CircleShape,
                                            ),
                                    )
                                }
                            }

                            Text(
                                text = annotated,
                                style = bodyStyle,
                                color = bodyTextColor,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            is ReleaseChangelogBlock.Quote -> {
                val annotated = remember(block.text, onSurface, onSurfaceVariant, primary, inlineCodeBg) {
                    buildReleaseInlineAnnotatedString(
                        raw = block.text,
                        strongColor = onSurface,
                        mutedColor = onSurfaceVariant,
                        linkColor = primary,
                        inlineCodeBg = inlineCodeBg,
                        onUrlClick = dispatchUrlClick,
                    )
                }
                val quoteBarColor = primary.copy(alpha = 0.48f)
                Text(
                    text = annotated,
                    style = bodyStyle,
                    color = onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val barW = 2.5.dp.toPx()
                            val insetY = 3.dp.toPx()
                            val barH = (size.height - insetY * 2f).coerceAtLeast(8.dp.toPx())
                            drawRoundRect(
                                color = quoteBarColor,
                                topLeft = Offset(0f, insetY),
                                size = Size(barW, barH),
                                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                            )
                        }
                        .padding(start = 11.dp),
                )
            }

            is ReleaseChangelogBlock.CodeBlock -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                alpha = if (isDark) 0.72f else 0.78f,
                            ),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = block.text,
                        style = codeBoxStyle,
                        color = onSurfaceVariant,
                    )
                }
            }

            ReleaseChangelogBlock.Divider -> {
                PulseGradientDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = NodePulseThemeTokens.extendedColors.cardBorder,
                )
            }
        }
    }
}

private val HTML_COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
private val HTML_BR_REGEX = Regex("(?i)<br\\s*/?>")
private val ATX_HEADING_REGEX = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
private val HR_REGEX = Regex("^([-*_])(?:\\s*\\1){2,}\\s*$")
private val TASK_ITEM_REGEX = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$")
private val BULLET_ITEM_REGEX = Regex("^(\\s*)(?:[-*+•])\\s+(.*)$")
private val ORDERED_ITEM_REGEX = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val INLINE_TOKEN_REGEX = Regex(
    """\[([^\]\n]+)]\((https?://[^)\s]+)\)|`([^`\n]+)`|\*\*([^*\n]+)\*\*|__([^_\n]+)__|~~([^~\n]+)~~|\*([^*\n]+)\*|(https?://[^\s<>()]+)"""
)

internal fun parseReleaseChangelogBlocks(rawMarkdown: String): List<ReleaseChangelogBlock> {
    val cleaned = rawMarkdown
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(HTML_COMMENT_REGEX, "")
        .replace(HTML_BR_REGEX, "\n")
        .trim()
    if (cleaned.isEmpty()) return emptyList()

    val lines = cleaned.lines()
    val result = mutableListOf<ReleaseChangelogBlock>()
    var i = 0

    while (i < lines.size) {
        val rawLine = lines[i]
        val trimmed = rawLine.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Fenced code block
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = trimmed.take(3)
            i++
            val codeLines = mutableListOf<String>()
            while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                codeLines += lines[i]
                i++
            }
            if (i < lines.size) i++
            val codeText = codeLines.joinToString("\n").trim()
            if (codeText.isNotEmpty()) {
                result += ReleaseChangelogBlock.CodeBlock(codeText)
            }
            continue
        }

        // Horizontal rule
        if (HR_REGEX.matches(trimmed)) {
            result += ReleaseChangelogBlock.Divider
            i++
            continue
        }

        // ATX heading
        val headingMatch = ATX_HEADING_REGEX.matchEntire(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val headingText = stripSimpleHtmlTags(headingMatch.groupValues[2].trim())
            if (headingText.isNotEmpty()) {
                result += ReleaseChangelogBlock.Heading(level = level, text = headingText)
            }
            i++
            continue
        }

        // Blockquote
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                val q = lines[i].trim().removePrefix(">").trim()
                if (q.isNotEmpty()) quoteLines += stripSimpleHtmlTags(q)
                i++
            }
            if (quoteLines.isNotEmpty()) {
                result += ReleaseChangelogBlock.Quote(quoteLines.joinToString("\n"))
            }
            continue
        }

        // List (task / bullet / ordered)
        if (parseListLine(rawLine) != null) {
            val listItems = mutableListOf<ReleaseListItem>()
            while (i < lines.size) {
                val parsed = parseListLine(lines[i]) ?: break
                listItems += parsed
                i++
            }
            if (listItems.isNotEmpty()) {
                result += ReleaseChangelogBlock.ListBlock(listItems)
            }
            continue
        }

        // Paragraph lines (preserve intentional line breaks in release notes)
        val paragraphLines = mutableListOf<String>()
        while (i < lines.size) {
            val curRaw = lines[i]
            val curTrimmed = curRaw.trim()
            if (curTrimmed.isEmpty() ||
                curTrimmed.startsWith("```") ||
                curTrimmed.startsWith("~~~") ||
                curTrimmed.startsWith(">") ||
                HR_REGEX.matches(curTrimmed) ||
                ATX_HEADING_REGEX.matches(curTrimmed) ||
                parseListLine(curRaw) != null
            ) {
                break
            }
            val cleanedLine = stripSimpleHtmlTags(curTrimmed)
            if (cleanedLine.isNotEmpty()) {
                paragraphLines += cleanedLine
            }
            i++
        }
        if (paragraphLines.isNotEmpty()) {
            result += ReleaseChangelogBlock.Paragraph(paragraphLines.joinToString("\n"))
        }
    }

    return result
}

private fun parseListLine(rawLine: String): ReleaseListItem? {
    TASK_ITEM_REGEX.matchEntire(rawLine)?.let { match ->
        val indent = match.groupValues[1].replace("\t", "  ").length / 2
        val checked = match.groupValues[2].equals("x", ignoreCase = true)
        val text = stripSimpleHtmlTags(match.groupValues[3].trim())
        return ReleaseListItem(text = text, indentLevel = indent, checked = checked)
    }
    ORDERED_ITEM_REGEX.matchEntire(rawLine)?.let { match ->
        val indent = match.groupValues[1].replace("\t", "  ").length / 2
        val order = match.groupValues[2].toIntOrNull()
        val text = stripSimpleHtmlTags(match.groupValues[3].trim())
        return ReleaseListItem(text = text, indentLevel = indent, orderNumber = order)
    }
    BULLET_ITEM_REGEX.matchEntire(rawLine)?.let { match ->
        val indent = match.groupValues[1].replace("\t", "  ").length / 2
        val text = stripSimpleHtmlTags(match.groupValues[2].trim())
        return ReleaseListItem(text = text, indentLevel = indent)
    }
    return null
}

private fun stripSimpleHtmlTags(input: String): String = input
    .replace(Regex("(?i)</?(?:strong|b)>"), "**")
    .replace(Regex("(?i)</?code>"), "`")
    .replace(Regex("(?i)</?(?:details|summary|p|div|span)[^>]*>"), "")
    .trim()

private fun buildReleaseInlineAnnotatedString(
    raw: String,
    strongColor: Color,
    mutedColor: Color,
    linkColor: Color,
    inlineCodeBg: Color,
    onUrlClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            fontWeight = FontWeight.Medium,
        ),
    )

    var cursor = 0
    for (match in INLINE_TOKEN_REGEX.findAll(raw)) {
        if (match.range.first > cursor) {
            append(raw.substring(cursor, match.range.first))
        }

        val linkLabel = match.groups[1]?.value
        val linkUrl = match.groups[2]?.value
        val inlineCode = match.groups[3]?.value
        val boldStar = match.groups[4]?.value
        val boldUnder = match.groups[5]?.value
        val strike = match.groups[6]?.value
        val italic = match.groups[7]?.value
        val bareUrl = match.groups[8]?.value

        when {
            linkLabel != null && linkUrl != null -> {
                val start = length
                append(linkLabel)
                addLink(
                    clickable = LinkAnnotation.Clickable(
                        tag = "release_url:$linkUrl",
                        styles = linkStyles,
                        linkInteractionListener = { onUrlClick(linkUrl) },
                    ),
                    start = start,
                    end = length,
                )
            }

            inlineCode != null -> {
                val start = length
                append(" $inlineCode ")
                addStyle(
                    style = SpanStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        color = strongColor,
                        background = inlineCodeBg,
                    ),
                    start = start,
                    end = length,
                )
            }

            boldStar != null || boldUnder != null -> {
                val content = boldStar ?: boldUnder.orEmpty()
                val start = length
                append(content)
                addStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.SemiBold,
                        color = strongColor,
                    ),
                    start = start,
                    end = length,
                )
            }

            strike != null -> {
                val start = length
                append(strike)
                addStyle(
                    style = SpanStyle(
                        color = mutedColor,
                        textDecoration = TextDecoration.LineThrough,
                    ),
                    start = start,
                    end = length,
                )
            }

            italic != null -> {
                val start = length
                append(italic)
                addStyle(
                    style = SpanStyle(
                        fontStyle = FontStyle.Italic,
                    ),
                    start = start,
                    end = length,
                )
            }

            bareUrl != null -> {
                val trimmedUrl = bareUrl.trimEnd('.', ',', ';', ')', ']', '。', '，', '、', '！', '？')
                val trailingPunct = bareUrl.substring(trimmedUrl.length)
                val start = length
                append(trimmedUrl)
                addLink(
                    clickable = LinkAnnotation.Clickable(
                        tag = "release_url:$trimmedUrl",
                        styles = linkStyles,
                        linkInteractionListener = { onUrlClick(trimmedUrl) },
                    ),
                    start = start,
                    end = length,
                )
                if (trailingPunct.isNotEmpty()) {
                    append(trailingPunct)
                }
            }

            else -> append(match.value)
        }

        cursor = match.range.last + 1
    }

    if (cursor < raw.length) {
        append(raw.substring(cursor))
    }
}

internal fun formatUpdatePublishedLabel(publishedAtIso: String): String {
    if (publishedAtIso.isBlank()) return ""
    val relative = formatRelativeTimeChinese(publishedAtIso).trim()
    if (relative.isEmpty()) return ""
    return when {
        relative == "刚刚" || relative == "昨天" || relative.endsWith("前") -> "${relative}发布"
        else -> "发布于 $relative"
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
