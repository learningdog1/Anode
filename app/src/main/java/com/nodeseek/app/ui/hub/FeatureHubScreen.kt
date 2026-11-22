package com.nodeseek.app.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers

@Immutable
data class FeatureHubTelemetry(
    val seekMailConfigured: Boolean = false,
    val ongoingActivityCount: Int = 0,
    val totalActivityCount: Int = 0,
    val activeRestockWatchCount: Int = 0,
    val restockUnreadCount: Int = 0,
    val fleetServerCount: Int = 0,
    val sshServerCount: Int = 0,
)

private data class FeatureTileItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val statusText: String? = null,
    val isStatusHighlighted: Boolean = false,
    val badgeCount: Int = 0,
    val accentColor: Color? = null,
    val onClick: () -> Unit,
)

/**
 * Anode「功能栏」主入口页（网格与分组卡片风格，沿用 Cool Mist & Slate Steel 主题）。
 *
 * 汇聚邮箱、活动、海外情报、补货监控、我的服务器、手机端测机与轻量 SSH。
 */
@Composable
fun FeatureHubScreen(
    onOpenSeekMail: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenOverseasIntel: () -> Unit,
    onOpenRestockMonitor: () -> Unit,
    onOpenMyServers: () -> Unit,
    onOpenNetBench: () -> Unit,
    onOpenLiteSsh: () -> Unit,
    modifier: Modifier = Modifier,
    contentBottomPadding: Dp = 104.dp,
    scrollToTopTrigger: Int = 0,
    onScrollToTopConsumed: () -> Unit = {},
    topBannerContent: (@Composable () -> Unit)? = null,
    previewTelemetry: FeatureHubTelemetry? = null,
) {
    val resolvedTelemetry = if (previewTelemetry != null) {
        previewTelemetry
    } else {
        val appContainer = LocalAppContainer.current
        val settings by appContainer.settingsRepository.settings.collectAsStateWithLifecycle(
            initialValue = AppSettings(),
        )
        val activitySnapshot by appContainer.activityRepository.snapshot.collectAsStateWithLifecycle()
        val restockWatches by appContainer.restockRepository.watchItemsFlow.collectAsStateWithLifecycle()
        val restockEvents by appContainer.restockRepository.eventHistoryFlow.collectAsStateWithLifecycle()
        val fleetAssets by appContainer.fleetRepository.assetsFlow.collectAsStateWithLifecycle()
        val fleetSnapshots by appContainer.fleetRepository.snapshotsFlow.collectAsStateWithLifecycle()
        val sshServers by appContainer.sshVaultRepository.serversFlow.collectAsStateWithLifecycle()

        remember(
            settings.seekMailApiKey,
            activitySnapshot,
            restockWatches,
            restockEvents,
            fleetAssets,
            fleetSnapshots,
            sshServers,
        ) {
            val liveCounts = activitySnapshot.liveCounts()
            FeatureHubTelemetry(
                seekMailConfigured = settings.seekMailApiKey.isNotBlank(),
                ongoingActivityCount = liveCounts.ongoing,
                totalActivityCount = liveCounts.all,
                activeRestockWatchCount = restockWatches.count { it.isEnabled },
                restockUnreadCount = restockEvents.count { !it.isRead },
                fleetServerCount = maxOf(fleetAssets.size, fleetSnapshots.size),
                sshServerCount = sshServers.size,
            )
        }
    }

    val listState = rememberLazyListState()
    val latestOnConsumed by rememberUpdatedState(onScrollToTopConsumed)
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            latestOnConsumed()
            listState.animateScrollToItem(0)
        }
    }

    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary

    val communityTiles = listOf(
        FeatureTileItem(
            title = "邮箱",
            subtitle = "seek.li 收信与验证码提取",
            icon = Icons.Rounded.Mail,
            statusText = if (resolvedTelemetry.seekMailConfigured) "已配置" else "待配置",
            isStatusHighlighted = resolvedTelemetry.seekMailConfigured,
            accentColor = primary,
            onClick = onOpenSeekMail,
        ),
        FeatureTileItem(
            title = "活动",
            subtitle = "社区抽奖与福利专题聚合",
            icon = Icons.Rounded.Celebration,
            statusText = when {
                resolvedTelemetry.ongoingActivityCount > 0 -> "${resolvedTelemetry.ongoingActivityCount} 场进行中"
                resolvedTelemetry.totalActivityCount > 0 -> "${resolvedTelemetry.totalActivityCount} 场活动"
                else -> "实时聚合"
            },
            isStatusHighlighted = resolvedTelemetry.ongoingActivityCount > 0,
            accentColor = secondary,
            onClick = onOpenActivity,
        ),
    )

    val intelTiles = listOf(
        FeatureTileItem(
            title = "海外情报",
            subtitle = "海外促销监控与一键搬运",
            icon = Icons.Rounded.Public,
            statusText = "实时线报",
            isStatusHighlighted = true,
            accentColor = primary,
            onClick = onOpenOverseasIntel,
        ),
        FeatureTileItem(
            title = "补货监控",
            subtitle = "商家库存巡检与到货提醒",
            icon = Icons.Rounded.NotificationsActive,
            statusText = if (resolvedTelemetry.activeRestockWatchCount > 0) {
                "${resolvedTelemetry.activeRestockWatchCount} 项监控"
            } else {
                "库存巡检"
            },
            isStatusHighlighted = resolvedTelemetry.activeRestockWatchCount > 0,
            badgeCount = resolvedTelemetry.restockUnreadCount,
            accentColor = tertiary,
            onClick = onOpenRestockMonitor,
        ),
    )

    val infraTiles = listOf(
        FeatureTileItem(
            title = "我的服务器",
            subtitle = "探针监控与机器资产账本",
            icon = Icons.Rounded.Dns,
            statusText = if (resolvedTelemetry.fleetServerCount > 0) {
                "${resolvedTelemetry.fleetServerCount} 台"
            } else {
                "探针 · 账本"
            },
            isStatusHighlighted = resolvedTelemetry.fleetServerCount > 0,
            accentColor = primary,
            onClick = onOpenMyServers,
        ),
        FeatureTileItem(
            title = "手机端测机",
            subtitle = "TCPing · iperf3 · IP 分析",
            icon = Icons.Rounded.Speed,
            statusText = "网络诊断",
            isStatusHighlighted = false,
            accentColor = secondary,
            onClick = onOpenNetBench,
        ),
    )

    val sshHeroItem = FeatureTileItem(
        title = "轻量 SSH",
        subtitle = "加密主机保险库、一键测评脚本与结果发帖",
        icon = Icons.Rounded.Terminal,
        statusText = if (resolvedTelemetry.sshServerCount > 0) {
            "${resolvedTelemetry.sshServerCount} 台主机"
        } else {
            "一键测机"
        },
        isStatusHighlighted = resolvedTelemetry.sshServerCount > 0,
        accentColor = primary,
        onClick = onOpenLiteSsh,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
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
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = contentBottomPadding + navBottomInset + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "feature_hub_header") {
                    FeatureHubHeaderBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 10.dp, bottom = 2.dp),
                    )
                }

                if (topBannerContent != null) {
                    item(key = "feature_hub_top_banner") {
                        PulseStaggeredEntrance(index = 0) {
                            topBannerContent()
                        }
                    }
                }

                item(key = "feature_group_community") {
                    PulseStaggeredEntrance(index = 1) {
                        FeatureGridSection(
                            sectionTitle = "通信与活动",
                            tiles = communityTiles,
                        )
                    }
                }

                item(key = "feature_group_intel") {
                    PulseStaggeredEntrance(index = 2) {
                        FeatureGridSection(
                            sectionTitle = "情报与监控",
                            tiles = intelTiles,
                        )
                    }
                }

                item(key = "feature_group_infra") {
                    PulseStaggeredEntrance(index = 3) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeatureGridSection(
                                sectionTitle = "主机与测机",
                                tiles = infraTiles,
                            )
                            FeatureWideTerminalCard(item = sshHeroItem)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureHubHeaderBar(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "功能栏",
                style = NodePulseThemeTokens.extraTypography.heroHeadline,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Text(
                text = "邮箱收信、促销线报与主机运维工具集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FeatureGridSection(
    sectionTitle: String,
    tiles: List<FeatureTileItem>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = sectionTitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )

        tiles.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { tile ->
                    FeatureBentoTileCard(
                        item = tile,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FeatureBentoTileCard(
    item: FeatureTileItem,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val resolvedAccent = item.accentColor ?: MaterialTheme.colorScheme.primary
    val iconBadgeShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .pulseCard(
                shape = PulseArchitecturalShapes.ElevatedTile,
                elevation = PulseElevation.Card,
                accentColor = resolvedAccent,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClickLabel = "打开${item.title}",
                onClick = item.onClick,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(iconBadgeShape)
                    .background(resolvedAccent.copy(alpha = if (isDark) 0.18f else 0.12f))
                    .border(
                        width = 0.8.dp,
                        color = resolvedAccent.copy(alpha = if (isDark) 0.34f else 0.20f),
                        shape = iconBadgeShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = resolvedAccent,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (item.badgeCount > 0) {
                CompactCountBadge(
                    count = item.badgeCount,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    minHeight = 20.dp,
                )
            } else if (!item.statusText.isNullOrBlank()) {
                PulseAccentPill(
                    text = item.statusText,
                    containerColor = if (item.isStatusHighlighted) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.72f else 0.85f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (item.isStatusHighlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FeatureWideTerminalCard(
    item: FeatureTileItem,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val resolvedAccent = item.accentColor ?: MaterialTheme.colorScheme.primary
    val iconBadgeShape = RoundedCornerShape(13.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = resolvedAccent,
                shape = PulseArchitecturalShapes.ElevatedTile,
                elevation = PulseElevation.CardElevated,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClickLabel = "打开${item.title}",
                onClick = item.onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(iconBadgeShape)
                .background(resolvedAccent.copy(alpha = if (isDark) 0.20f else 0.13f))
                .border(
                    width = 0.8.dp,
                    color = resolvedAccent.copy(alpha = if (isDark) 0.36f else 0.22f),
                    shape = iconBadgeShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = resolvedAccent,
                modifier = Modifier.size(21.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!item.statusText.isNullOrBlank()) {
            PulseAccentPill(
                text = item.statusText,
                containerColor = if (item.isStatusHighlighted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (item.isStatusHighlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 840)
@Composable
private fun FeatureHubScreenLightPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT) {
        FeatureHubScreen(
            onOpenSeekMail = {},
            onOpenActivity = {},
            onOpenOverseasIntel = {},
            onOpenRestockMonitor = {},
            onOpenMyServers = {},
            onOpenNetBench = {},
            onOpenLiteSsh = {},
            previewTelemetry = FeatureHubTelemetry(
                seekMailConfigured = true,
                activeRestockWatchCount = 4,
                restockUnreadCount = 2,
                fleetServerCount = 6,
                sshServerCount = 5,
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 840)
@Composable
private fun FeatureHubScreenDarkPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        FeatureHubScreen(
            onOpenSeekMail = {},
            onOpenActivity = {},
            onOpenOverseasIntel = {},
            onOpenRestockMonitor = {},
            onOpenMyServers = {},
            onOpenNetBench = {},
            onOpenLiteSsh = {},
            previewTelemetry = FeatureHubTelemetry(
                seekMailConfigured = false,
                activeRestockWatchCount = 0,
                restockUnreadCount = 0,
                fleetServerCount = 3,
                sshServerCount = 2,
            ),
        )
    }
}
