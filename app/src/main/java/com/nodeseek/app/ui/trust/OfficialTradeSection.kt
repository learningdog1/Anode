@file:OptIn(ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.trust

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.trust.OfficialTradeLookup
import com.nodeseek.app.domain.trust.OfficialTradeRepository
import com.nodeseek.app.domain.trust.OfficialTradeRoleStat
import com.nodeseek.app.domain.trust.OfficialTradeUserStats
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.formatCompactCount
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal const val OFFICIAL_TRADE_PORTAL_URL: String = "https://agent.nodeseek.org"

private val CNY_DECIMAL_FORMATTER_LOCAL = ThreadLocal.withInitial {
    DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
}

/**
 * Formats an official trade CNY decimal string (e.g. `"1688.00"`) with `¥` prefix and grouping separators.
 */
internal fun formatOfficialTradeAmount(rawAmount: String): String {
    val cleaned = rawAmount.trim().removePrefix("¥").removePrefix("￥").replace(",", "").trim()
    if (cleaned.isEmpty()) return "¥0.00"
    val parsed = cleaned.toBigDecimalOrNull() ?: return "¥$cleaned"
    val scaled = if (parsed.scale() > 2) {
        parsed.setScale(2, RoundingMode.HALF_UP)
    } else {
        parsed.setScale(2, RoundingMode.UNNECESSARY)
    }
    if (scaled.compareTo(BigDecimal.ZERO) == 0) return "¥0.00"
    return "¥${CNY_DECIMAL_FORMATTER_LOCAL.get()!!.format(scaled)}"
}

/**
 * Formats `lastCompletedAt` ISO-8601 string into concise Chinese relative time, or `"暂无成交"` when absent.
 */
internal fun formatOfficialTradeCompletedRelative(
    lastCompletedAt: String?,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String {
    val trimmed = lastCompletedAt?.trim().orEmpty()
    if (trimmed.isEmpty()) return "暂无成交"
    val formatted = formatRelativeTimeChinese(trimmed, nowEpochMillis = nowEpochMillis)
    return formatted.ifBlank { "暂无成交" }
}

/**
 * Formats how long ago [fetchedAtMillis] occurred as `"X 秒前"` / `"X 分钟前"` / `"X 小时前"` / `"X 天前"`.
 */
internal fun formatOfficialCacheDurationAgo(
    fetchedAtMillis: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String {
    if (fetchedAtMillis <= 0L) return "未知时间"
    val diffSeconds = ((nowEpochMillis - fetchedAtMillis) / 1_000L).coerceAtLeast(1L)
    return when {
        diffSeconds < 60L -> "$diffSeconds 秒前"
        diffSeconds < 3_600L -> "${diffSeconds / 60L} 分钟前"
        diffSeconds < 86_400L -> "${diffSeconds / 3_600L} 小时前"
        else -> "${diffSeconds / 86_400L} 天前"
    }
}

/**
 * Builds the required source & cache attribution line:
 * `"数据来自 NodeSeek 官方交易平台 · 缓存于 X 前"`.
 */
internal fun formatOfficialTradeSourceAttribution(
    fetchedAtMillis: Long,
    fromCache: Boolean,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String {
    if (fetchedAtMillis <= 0L) {
        return "数据来自 NodeSeek 官方交易平台"
    }
    val ago = formatOfficialCacheDurationAgo(fetchedAtMillis, nowEpochMillis)
    return if (fromCache) {
        "数据来自 NodeSeek 官方交易平台 · 缓存于 $ago"
    } else {
        val diffSeconds = ((nowEpochMillis - fetchedAtMillis) / 1_000L).coerceAtLeast(0L)
        if (diffSeconds < 10L) {
            "数据来自 NodeSeek 官方交易平台 · 实时同步（缓存于 $ago）"
        } else {
            "数据来自 NodeSeek 官方交易平台 · 缓存于 $ago"
        }
    }
}

/**
 * Normalizes [byRole] so `买家 (buyer)`, `卖家 (seller)`, and `中介 (agent)` are always presented in
 * deterministic order, followed by any unexpected custom role returned by the API.
 */
internal fun buildNormalizedRoleBreakdown(
    byRole: List<OfficialTradeRoleStat>,
): List<OfficialTradeRoleStat> {
    val grouped = LinkedHashMap<String, OfficialTradeRoleStat>()
    for (entry in byRole) {
        val canonicalRole = when (entry.role.trim().lowercase()) {
            OfficialTradeRoleStat.ROLE_BUYER -> OfficialTradeRoleStat.ROLE_BUYER
            OfficialTradeRoleStat.ROLE_SELLER -> OfficialTradeRoleStat.ROLE_SELLER
            OfficialTradeRoleStat.ROLE_AGENT, "middleman", "escrow", "mediator", "中介" ->
                OfficialTradeRoleStat.ROLE_AGENT
            else -> entry.role.trim().lowercase().ifEmpty { "other" }
        }
        val prev = grouped[canonicalRole]
        if (prev == null) {
            grouped[canonicalRole] = entry.copy(role = canonicalRole)
        } else {
            val prevDec = prev.amount.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
            val addDec = entry.amount.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
            grouped[canonicalRole] = OfficialTradeRoleStat(
                role = canonicalRole,
                trades = prev.trades + entry.trades,
                amount = prevDec.add(addDec).setScale(2, RoundingMode.HALF_UP).toPlainString(),
            )
        }
    }

    val canonicalOrder = listOf(
        OfficialTradeRoleStat.ROLE_BUYER,
        OfficialTradeRoleStat.ROLE_SELLER,
        OfficialTradeRoleStat.ROLE_AGENT,
    )
    val result = ArrayList<OfficialTradeRoleStat>(maxOf(3, grouped.size))
    for (role in canonicalOrder) {
        result += grouped.remove(role)
            ?: OfficialTradeRoleStat(role = role, trades = 0, amount = "0.00")
    }
    result.addAll(grouped.values)
    return result
}

/**
 * Computes the compact inline marker for [SellerTrustQuickBadge] from cached-only stats.
 * Returns `null` when uncached so badges without cached official data remain visually unchanged.
 */
internal fun formatOfficialQuickBadgeMarker(
    found: OfficialTradeLookup.Found?,
    compact: Boolean,
): String? {
    if (found == null || found.matches.isEmpty()) return null
    if (found.matches.size > 1) {
        return if (compact) "官·重名" else "官·同名${found.matches.size}"
    }
    val trades = found.matches.first().trades.coerceAtLeast(0)
    return if (compact) {
        "官·${formatCompactCount(trades)}"
    } else {
        "官 ${formatCompactCount(trades)}场"
    }
}

/**
 * Dedicated "官方交易平台" section inside [SellerTrustCardSheet], displaying completed trade stats
 * from `agent.nodeseek.org` across all states (加载中 / 未配置 / 平台无记录 / Key 无效 / 限流 / 失败 / 重名多条).
 */
@Composable
internal fun OfficialTradeSectionCard(
    uiState: SellerTrustUiState,
    onRefreshOfficialTrade: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    officialTradeRepository: OfficialTradeRepository? = null,
    modifier: Modifier = Modifier,
) {
    val status = uiState.officialTradeStatus
    if (status == OfficialTradeLoadStatus.IDLE) return

    val uriHandler = LocalUriHandler.current
    val resolvedRepo = rememberOfficialTradeRepository(override = officialTradeRepository)
    var showQuickKeyDialog by rememberSaveable { mutableStateOf(false) }

    val queriedName = uiState.officialTradeQueriedUsername
        .ifBlank { uiState.effectiveOfficialUsername }
        .ifBlank { uiState.resolvedUsername }
    val isBusy = uiState.isOfficialTradeLoading || uiState.isOfficialTradeRefreshing
    val cooldownSec = uiState.officialTradeCooldownSeconds
    val canManualRefresh = !isBusy && cooldownSec <= 0 &&
        status != OfficialTradeLoadStatus.NOT_CONFIGURED

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseSectionHeader(
            title = "官方交易平台",
            subtitle = "NodeSeek 担保与实名中介成交记录",
            icon = Icons.Rounded.VerifiedUser,
            accentColor = MaterialTheme.colorScheme.primary,
            trailing = if (status != OfficialTradeLoadStatus.NOT_CONFIGURED) {
                {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(
                                enabled = canManualRefresh,
                                role = Role.Button,
                                onClick = onRefreshOfficialTrade,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when {
                            isBusy -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.8.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            cooldownSec > 0 -> {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "手动刷新官方交易数据",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        val refreshLabel = when {
                            isBusy -> "刷新中"
                            cooldownSec > 0 -> "${cooldownSec}s"
                            else -> "刷新"
                        }
                        Text(
                            text = refreshLabel,
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            fontWeight = FontWeight.SemiBold,
                            color = if (canManualRefresh) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            } else {
                null
            },
        )

        when (status) {
            OfficialTradeLoadStatus.IDLE -> Unit

            OfficialTradeLoadStatus.LOADING -> {
                OfficialTradeLoadingCard()
            }

            OfficialTradeLoadStatus.NOT_CONFIGURED -> {
                OfficialTradeNotConfiguredCard(
                    onConfigureClick = {
                        if (onOpenSettings != null) {
                            onOpenSettings()
                        } else {
                            showQuickKeyDialog = true
                        }
                    },
                    hasDirectSettingsNav = onOpenSettings != null,
                    onOpenPortal = {
                        runCatching { uriHandler.openUri(OFFICIAL_TRADE_PORTAL_URL) }
                    },
                )
            }

            OfficialTradeLoadStatus.UNAUTHORIZED -> {
                OfficialTradeUnauthorizedCard(
                    onUpdateKey = {
                        if (onOpenSettings != null) {
                            onOpenSettings()
                        } else {
                            showQuickKeyDialog = true
                        }
                    },
                    hasDirectSettingsNav = onOpenSettings != null,
                    canRetry = canManualRefresh,
                    onRetry = onRefreshOfficialTrade,
                )
            }

            OfficialTradeLoadStatus.RATE_LIMITED -> {
                val lookupRateSec = (uiState.officialTradeLookup as? OfficialTradeLookup.RateLimited)
                    ?.retryAfterSeconds
                    ?.toInt()
                val effectiveSeconds = if (cooldownSec > 0) cooldownSec else (lookupRateSec ?: 0)
                OfficialTradeRateLimitedCard(
                    remainingSeconds = effectiveSeconds,
                    isRefreshing = isBusy,
                    onRetry = onRefreshOfficialTrade,
                )
            }

            OfficialTradeLoadStatus.FAILED -> {
                val message = (uiState.officialTradeLookup as? OfficialTradeLookup.Failed)
                    ?.message
                    ?.takeIf { it.isNotBlank() }
                    ?: "查询官方交易数据失败，请检查网络后重试"
                OfficialTradeFailedCard(
                    message = message,
                    isRefreshing = isBusy,
                    onRetry = onRefreshOfficialTrade,
                )
            }

            OfficialTradeLoadStatus.NOT_FOUND -> {
                OfficialTradeNotFoundCard(
                    username = queriedName,
                    isRefreshing = isBusy,
                    canRefresh = canManualRefresh,
                    onRefresh = onRefreshOfficialTrade,
                )
            }

            OfficialTradeLoadStatus.FOUND -> {
                val found = uiState.officialTradeFound
                if (found != null) {
                    OfficialTradeFoundCard(
                        found = found,
                        queriedUsername = queriedName,
                        isRefreshing = isBusy,
                        cooldownSeconds = cooldownSec,
                        onRefresh = onRefreshOfficialTrade,
                    )
                }
            }
        }
    }

    if (showQuickKeyDialog) {
        OfficialTradeQuickKeyDialog(
            repository = resolvedRepo,
            onSaved = {
                showQuickKeyDialog = false
                OfficialTradeUiSignalBus.notifyMutated()
                onRefreshOfficialTrade()
            },
            onDismiss = { showQuickKeyDialog = false },
        )
    }
}

@Composable
private fun OfficialTradeLoadingCard() {
    val ext = NodePulseThemeTokens.extendedColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.8.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "正在查询 NodeSeek 官方交易平台成交记录…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(3) {
                PulseShimmerBox(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = PulseArchitecturalShapes.MediumBadge,
                )
            }
        }
    }
}

@Composable
private fun OfficialTradeNotConfiguredCard(
    onConfigureClick: () -> Unit,
    hasDirectSettingsNav: Boolean,
    onOpenPortal: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = "尚未配置官方交易平台 API Key",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = "前往「我的 → 设置 → 官方交易平台」填写 API Key 后，即可自动查询卖家的成功交易场次、累计成交金额与角色明细。在 agent.nodeseek.org 申请 Key，仅加密保存在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onConfigureClick,
                shape = PulseArchitecturalShapes.SmallChip,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (hasDirectSettingsNav) "前往设置配置" else "立即填入 Key",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            OutlinedButton(
                onClick = onOpenPortal,
                shape = PulseArchitecturalShapes.SmallChip,
                border = BorderStroke(0.8.dp, ext.cardBorder),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "申请 API Key",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun OfficialTradeUnauthorizedCard(
    onUpdateKey: () -> Unit,
    hasDirectSettingsNav: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    val redTone = TrustPalette.forRiskLevel(TrustRiskLevel.HIGH_RISK_RED)
    val shape = PulseArchitecturalShapes.EditorialCard

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(redTone.backgroundBrush)
            .border(width = 1.dp, color = redTone.border, shape = shape)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.GppBad,
                contentDescription = null,
                tint = redTone.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "API Key 无效或已失效",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = redTone.content,
            )
        }

        Text(
            text = "官方交易平台返回 401/403 鉴权失败，请前往「我的 → 设置 → 官方交易平台」更新或重新测试您的 ntk_ 密钥。",
            style = MaterialTheme.typography.bodySmall,
            color = redTone.content.copy(alpha = 0.90f),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onUpdateKey,
                shape = PulseArchitecturalShapes.SmallChip,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (hasDirectSettingsNav) "前往设置修改" else "更换 API Key",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            OutlinedButton(
                onClick = onRetry,
                enabled = canRetry,
                shape = PulseArchitecturalShapes.SmallChip,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun OfficialTradeRateLimitedCard(
    remainingSeconds: Int,
    isRefreshing: Boolean,
    onRetry: () -> Unit,
) {
    val amberTone = TrustPalette.forRiskLevel(TrustRiskLevel.CAUTION_AMBER)
    val shape = PulseArchitecturalShapes.EditorialCard
    val isCoolingDown = remainingSeconds > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(amberTone.backgroundBrush)
            .border(width = 1.dp, color = amberTone.border, shape = shape)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                contentDescription = null,
                tint = amberTone.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (isCoolingDown) {
                    "触发官方平台限流 · ${remainingSeconds} 秒后可重试"
                } else {
                    "限流冷却已结束 · 可手动重试"
                },
                style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
                color = amberTone.content,
            )
        }

        Text(
            text = if (isCoolingDown) {
                "官方交易平台开放接口限额 60 次/分钟，当前冷却剩余约 ${remainingSeconds} 秒。"
            } else {
                "冷却时间已过，点击下方按钮可重新查询卖家官方交易记录。"
            },
            style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
            color = amberTone.content.copy(alpha = 0.88f),
        )

        FilledTonalButton(
            onClick = onRetry,
            enabled = !isCoolingDown && !isRefreshing,
            shape = PulseArchitecturalShapes.SmallChip,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = if (isCoolingDown) Icons.Rounded.Schedule else Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (isCoolingDown) "${remainingSeconds}s 后可重试" else "手动重试",
                style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun OfficialTradeFailedCard(
    message: String,
    isRefreshing: Boolean,
    onRetry: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val shape = PulseArchitecturalShapes.EditorialCard

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = shape,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "官方交易数据加载失败",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FilledTonalButton(
            onClick = onRetry,
            enabled = !isRefreshing,
            shape = PulseArchitecturalShapes.SmallChip,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (isRefreshing) "正在重试…" else "手动重试",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun OfficialTradeNotFoundCard(
    username: String,
    isRefreshing: Boolean,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Storefront,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = "平台无记录",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = "NodeSeek 官方交易平台暂未找到用户名 @$username 的注册或成交记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "数据来自 NodeSeek 官方交易平台",
                style = extraType.microLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (isRefreshing) "刷新中…" else "手动刷新",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (canRefresh) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canRefresh, role = Role.Button, onClick = onRefresh)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun OfficialTradeFoundCard(
    found: OfficialTradeLookup.Found,
    queriedUsername: String,
    isRefreshing: Boolean,
    cooldownSeconds: Int,
    onRefresh: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography
    val isHomonymMultiple = found.matches.size > 1
    val canRefresh = !isRefreshing && cooldownSeconds <= 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isHomonymMultiple) {
            val amberTone = TrustPalette.forRiskLevel(TrustRiskLevel.CAUTION_AMBER)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.MediumBadge)
                    .background(amberTone.container)
                    .border(
                        width = 0.8.dp,
                        color = amberTone.border,
                        shape = PulseArchitecturalShapes.MediumBadge,
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = amberTone.accent,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "检测到 ${found.matches.size} 个同名账号 @$queriedUsername，请仔细核对下方平台用户 ID",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = amberTone.content,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        found.matches.forEachIndexed { index, userStats ->
            if (index > 0) {
                PulseGradientDivider()
            }
            OfficialTradeUserStatsBlock(
                stats = userStats,
                emphasizeUserId = isHomonymMultiple,
                inSubContainer = isHomonymMultiple,
                isDark = isDark,
            )
        }

        PulseGradientDivider()

        val attribution = formatOfficialTradeSourceAttribution(
            fetchedAtMillis = found.fetchedAtMillis,
            fromCache = found.fromCache,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = attribution,
                style = extraType.microLabel.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val footerRefreshText = when {
                isRefreshing -> "刷新中…"
                cooldownSeconds > 0 -> "${cooldownSeconds}s"
                else -> "手动刷新"
            }
            Text(
                text = footerRefreshText,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.SemiBold,
                color = if (canRefresh) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canRefresh, role = Role.Button, onClick = onRefresh)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun OfficialTradeUserStatsBlock(
    stats: OfficialTradeUserStats,
    emphasizeUserId: Boolean,
    inSubContainer: Boolean,
    isDark: Boolean,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography
    val breakdown = remember(stats.byRole) { buildNormalizedRoleBreakdown(stats.byRole) }
    val completedRelative = remember(stats.lastCompletedAt) {
        formatOfficialTradeCompletedRelative(stats.lastCompletedAt)
    }
    val formattedTotalAmount = remember(stats.amount) {
        formatOfficialTradeAmount(stats.amount)
    }

    val containerModifier = if (inSubContainer) {
        Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.MediumBadge)
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow.copy(
                    alpha = if (isDark) 0.72f else 0.82f,
                ),
            )
            .border(
                width = 0.8.dp,
                color = ext.cardBorder,
                shape = PulseArchitecturalShapes.MediumBadge,
            )
            .padding(10.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    Column(
        modifier = containerModifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // User identity & platform ID row
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "@${stats.username}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterVertically),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(
                        if (emphasizeUserId) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .padding(horizontal = 7.dp, vertical = 2.5.dp),
            ) {
                Text(
                    text = "平台用户 ID #${stats.userId}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ).withTabularNumbers(),
                    color = if (emphasizeUserId) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }

            if (stats.isOfficialAgent) {
                PulseAccentPill(
                    text = "官方认证中介",
                    icon = Icons.Rounded.CheckCircle,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }

        // 3-Column Primary KPI Row: 成功交易场次 / 累计金额(¥) / 最近成交时间(相对时间)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            OfficialKpiTile(
                label = "成功交易",
                value = "${stats.trades} 场",
                highlight = stats.trades > 0,
                modifier = Modifier.weight(1f),
            )
            OfficialKpiTile(
                label = "累计金额",
                value = formattedTotalAmount,
                highlight = stats.trades > 0,
                modifier = Modifier.weight(1f),
            )
            OfficialKpiTile(
                label = "最近成交",
                value = completedRelative,
                highlight = false,
                modifier = Modifier.weight(1f),
            )
        }

        // Per-role Breakdown: 买家 / 卖家 / 中介
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = "分角色成交明细（买家 / 卖家 / 中介）",
                style = extraType.microLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                breakdown.forEach { roleStat ->
                    OfficialRoleStatChip(
                        stat = roleStat,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun OfficialKpiTile(
    label: String,
    value: String,
    highlight: Boolean,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography

    Column(
        modifier = modifier
            .clip(PulseArchitecturalShapes.MediumBadge)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 0.75.dp,
                color = ext.cardBorder,
                shape = PulseArchitecturalShapes.MediumBadge,
            )
            .padding(horizontal = 9.dp, vertical = 7.5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = extraType.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
            fontWeight = FontWeight.Bold,
            color = if (highlight) {
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
private fun OfficialRoleStatChip(
    stat: OfficialTradeRoleStat,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography
    val formattedAmount = remember(stat.amount) { formatOfficialTradeAmount(stat.amount) }

    Column(
        modifier = modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 0.7.dp,
                color = ext.cardBorder.copy(alpha = 0.8f),
                shape = PulseArchitecturalShapes.SmallChip,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stat.roleDisplayLabel,
                style = extraType.microLabel,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = "${stat.trades} 场",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
                color = if (stat.trades > 0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
        Text(
            text = formattedAmount,
            style = extraType.microLabel.withTabularNumbers(),
            color = if (stat.trades > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OfficialTradeQuickKeyDialog(
    repository: OfficialTradeRepository,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isErrorFeedback by remember { mutableStateOf(false) }

    val isBusy = isSaving || isVerifying

    PulseAlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        icon = Icons.Rounded.VerifiedUser,
        title = "官方交易平台 API Key",
        message = "在 agent.nodeseek.org 申请 Key，仅加密保存在本机（也可在「设置 → 官方交易平台」中管理）。",
        buttons = {
            FilledTonalButton(
                onClick = {
                    val trimmed = keyDraft.trim()
                    scope.launch {
                        isVerifying = true
                        feedbackMessage = null
                        try {
                            when (val res = repository.verifyApiKey(trimmed)) {
                                is OfficialTradeLookup.Found,
                                OfficialTradeLookup.NotFound,
                                -> {
                                    isErrorFeedback = false
                                    feedbackMessage = "连接正常，API Key 验证有效"
                                }
                                OfficialTradeLookup.Unauthorized -> {
                                    isErrorFeedback = true
                                    feedbackMessage = "API Key 无效或已失效 (401/403)"
                                }
                                is OfficialTradeLookup.RateLimited -> {
                                    isErrorFeedback = true
                                    feedbackMessage = "触发限流，请 ${res.retryAfterSeconds ?: 60} 秒后再试"
                                }
                                OfficialTradeLookup.NotConfigured -> {
                                    isErrorFeedback = true
                                    feedbackMessage = "请先输入 ntk_ 开头的 API Key"
                                }
                                is OfficialTradeLookup.Failed -> {
                                    isErrorFeedback = true
                                    feedbackMessage = res.message
                                }
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            isErrorFeedback = true
                            feedbackMessage = t.message ?: "测试连接失败"
                        } finally {
                            isVerifying = false
                        }
                    }
                },
                enabled = !isBusy && keyDraft.isNotBlank(),
                shape = CircleShape,
            ) {
                Text(
                    text = if (isVerifying) "测试中…" else "测试连接",
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDismiss,
                enabled = !isBusy,
            ) {
                Text("取消", maxLines = 1, softWrap = false)
            }
            Button(
                onClick = {
                    val trimmed = keyDraft.trim()
                    scope.launch {
                        isSaving = true
                        feedbackMessage = null
                        try {
                            repository.saveApiKey(trimmed)
                            onSaved()
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            isErrorFeedback = true
                            feedbackMessage = t.message ?: "API Key 格式无效，必须以 ntk_ 开头"
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isBusy && keyDraft.isNotBlank(),
                shape = CircleShape,
            ) {
                Text(
                    text = if (isSaving) "保存中…" else "保存",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = keyDraft,
                onValueChange = {
                    keyDraft = it
                    feedbackMessage = null
                },
                label = { Text("API Key (ntk_...)") },
                placeholder = { Text("粘贴您的 ntk_ 密钥…") },
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
                                    feedbackMessage = null
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("粘贴", style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { showKey = !showKey }) {
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

            if (!feedbackMessage.isNullOrBlank()) {
                Text(
                    text = feedbackMessage!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isErrorFeedback) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}
