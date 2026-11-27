package com.nodeseek.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.nodeseek.app.MainActivity
import com.nodeseek.app.data.fleet.FleetAlertNotifier
import com.nodeseek.app.data.radar.AppContainerHost
import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.formatBytesBinary
import com.nodeseek.app.domain.widget.ServerStatusWidgetSnapshot

class ServerStatusGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServerStatusGlanceWidget()
}

class ServerStatusGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = runCatching {
            val container = (context.applicationContext as? AppContainerHost)?.container
            container?.widgetSnapshotRepository?.loadServerStatusSnapshot()
        }.getOrNull() ?: ServerStatusWidgetSnapshot()

        val openProbesIntent = buildOpenFleetIntent(context, FleetAlertNotifier.EXTRA_TAB_PROBES)
        val openAssetsIntent = buildOpenFleetIntent(context, FleetAlertNotifier.EXTRA_TAB_ASSETS)

        provideContent {
            GlanceTheme(colors = AnodeGlanceTheme.colors) {
                ServerStatusWidgetContent(
                    snapshot = snapshot,
                    openProbesIntent = openProbesIntent,
                    openAssetsIntent = openAssetsIntent,
                )
            }
        }
    }

    private fun buildOpenFleetIntent(context: Context, tab: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = FleetAlertNotifier.ACTION_OPEN_FLEET
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(FleetAlertNotifier.EXTRA_FLEET_TAB, tab)
        }
}

class RefreshServerStatusActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val container = (context.applicationContext as? AppContainerHost)?.container ?: return
        runCatching {
            container.fleetRepository.loadPersistedState()
            container.fleetRepository.refreshAllPanels(silent = true)
        }
        GlanceWidgetUpdater.updateAll(context)
    }
}

@Composable
private fun ServerStatusWidgetContent(
    snapshot: ServerStatusWidgetSnapshot,
    openProbesIntent: Intent,
    openAssetsIntent: Intent,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(AnodeGlanceTokens.cardSurface)
            .appWidgetBackground()
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(openProbesIntent)),
        verticalAlignment = Alignment.Top,
    ) {
        // Header: title + online count badge + refresh button
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "服务器状态",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            ProbeStatusBadge(snapshot = snapshot)
            Spacer(modifier = GlanceModifier.width(6.dp))
            Box(
                modifier = GlanceModifier
                    .background(AnodeGlanceTokens.pillSurface)
                    .cornerRadius(10.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable(actionRunCallback<RefreshServerStatusActionCallback>()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "刷新",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = GlanceModifier.height(10.dp))

        // Primary machine traffic section
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(AnodeGlanceTokens.pillSurface)
                .cornerRadius(12.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (snapshot.primaryServerName != null) {
                val flagPrefix = snapshot.primaryServerFlag
                    .takeIf { it.isNotBlank() }
                    ?.let { "$it " }
                    .orEmpty()
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$flagPrefix${snapshot.primaryServerName}",
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    val usageText = if (snapshot.primaryTrafficLimitBytes != null && snapshot.primaryTrafficLimitBytes > 0L) {
                        "${formatBytesBinary(snapshot.primaryTrafficUsedBytes)} / ${formatBytesBinary(snapshot.primaryTrafficLimitBytes)}"
                    } else {
                        "已用 ${formatBytesBinary(snapshot.primaryTrafficUsedBytes)}"
                    }
                    Text(
                        text = usageText,
                        style = TextStyle(
                            color = AnodeGlanceTokens.mutedText,
                            fontSize = 11.sp,
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(modifier = GlanceModifier.height(6.dp))
                val ratio = snapshot.primaryTrafficRatio
                if (ratio != null) {
                    val barColor = when {
                        ratio >= 0.95f -> AnodeGlanceTokens.dangerFg
                        ratio >= 0.80f -> AnodeGlanceTokens.warningFg
                        else -> AnodeGlanceTokens.progressPrimary
                    }
                    LinearProgressIndicator(
                        progress = ratio.coerceIn(0f, 1f),
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .cornerRadius(3.dp),
                        color = barColor,
                        backgroundColor = AnodeGlanceTokens.trackBackground,
                    )
                } else {
                    Text(
                        text = "本月累计流量 · 未配置流量上限",
                        style = TextStyle(
                            color = AnodeGlanceTokens.mutedText,
                            fontSize = 10.sp,
                        ),
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = "暂无探针节点",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = "点击前往「我的服务器」添加探针面板",
                    style = TextStyle(
                        color = AnodeGlanceTokens.mutedText,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        // Nearest expiring VPS countdown
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity(openAssetsIntent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最近续费",
                style = TextStyle(
                    color = AnodeGlanceTokens.mutedText,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            if (snapshot.hasNearestVps) {
                Text(
                    text = snapshot.nearestVpsName.orEmpty(),
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                RenewalCountdownPill(
                    daysRemaining = snapshot.nearestVpsDaysRemaining ?: 0L,
                    urgency = snapshot.nearestVpsUrgency ?: AssetExpiryUrgency.NORMAL,
                )
            } else {
                Text(
                    text = "暂无临期 VPS",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = AnodeGlanceTokens.mutedText,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ProbeStatusBadge(snapshot: ServerStatusWidgetSnapshot) {
    val (bg, fg, label) = when {
        snapshot.totalProbeCount <= 0 -> Triple(
            AnodeGlanceTokens.pillSurface,
            AnodeGlanceTokens.mutedText,
            "未配置",
        )
        snapshot.onlineProbeCount == snapshot.totalProbeCount -> Triple(
            AnodeGlanceTokens.onlineBg,
            AnodeGlanceTokens.onlineFg,
            "${snapshot.onlineProbeCount}/${snapshot.totalProbeCount} 在线",
        )
        snapshot.onlineProbeCount == 0 -> Triple(
            AnodeGlanceTokens.dangerBg,
            AnodeGlanceTokens.dangerFg,
            "0/${snapshot.totalProbeCount} 在线",
        )
        else -> Triple(
            AnodeGlanceTokens.warningBg,
            AnodeGlanceTokens.warningFg,
            "${snapshot.onlineProbeCount}/${snapshot.totalProbeCount} 在线",
        )
    }
    Box(
        modifier = GlanceModifier
            .background(bg)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun RenewalCountdownPill(
    daysRemaining: Long,
    urgency: AssetExpiryUrgency,
) {
    val label = when {
        daysRemaining < 0L -> "已过期 ${-daysRemaining} 天"
        daysRemaining == 0L -> "今日到期"
        daysRemaining == 1L -> "明日到期"
        else -> "剩余 $daysRemaining 天"
    }
    val (bg, fg) = when (urgency) {
        AssetExpiryUrgency.EXPIRED,
        AssetExpiryUrgency.CRITICAL,
        -> AnodeGlanceTokens.dangerBg to AnodeGlanceTokens.dangerFg
        AssetExpiryUrgency.WARNING -> AnodeGlanceTokens.warningBg to AnodeGlanceTokens.warningFg
        AssetExpiryUrgency.NORMAL,
        AssetExpiryUrgency.UNKNOWN,
        -> AnodeGlanceTokens.accentBg to AnodeGlanceTokens.accentFg
    }
    Box(
        modifier = GlanceModifier
            .background(bg)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}
