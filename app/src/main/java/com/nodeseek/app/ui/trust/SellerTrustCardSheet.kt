@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.trust

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.trust.ExposureHitTopic
import com.nodeseek.app.domain.trust.SellerTrustReport
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.trust.TrustFetchStatus
import com.nodeseek.app.domain.trust.TrustRiskAssessment
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.domain.trust.TrustTag
import com.nodeseek.app.ui.components.CategoryMicroTag
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.components.RelativeTimeText
import com.nodeseek.app.ui.components.formatCompactCount
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers

private val SELECTABLE_TRUST_TAGS: List<TrustTag> = listOf(
    TrustTag.TRUSTED,
    TrustTag.NEUTRAL,
    TrustTag.CAUTION,
    TrustTag.RISK_BLACKLIST,
)

private const val DEFAULT_VISIBLE_TRADE_TOPICS = 3

/**
 * Pre-trade Seller Reputation & Risk Check Card (`交易前"查一查" —— 卖家信誉卡`) rendered inside
 * an expressive Material 3 [ModalBottomSheet].
 *
 * Triggerable from `TopicDetailScreen`, `DirectMessageScreen`, `UserProfileScreen`, or anywhere
 * a seller `(uid, usernameHint)` is known.
 */
@Composable
fun SellerTrustCardSheet(
    uid: Long,
    usernameHint: String,
    avatarUrlHint: String = "",
    onOpenTopic: (Long) -> Unit,
    onOpenUserProfile: (Long, String) -> Unit,
    onStartDm: ((Long, String) -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    repository: SellerTrustRepository? = null,
    stateHolder: SellerTrustStateHolder = rememberSellerTrustState(
        uid = uid,
        usernameHint = usernameHint,
        avatarUrlHint = avatarUrlHint,
        repository = repository,
    ),
) {
    val uiState by stateHolder.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            )
        },
    ) {
        SellerTrustCardSheetContent(
            uiState = uiState,
            onRefresh = stateHolder::refresh,
            onToggleTag = stateHolder::toggleTag,
            onNoteTextChange = stateHolder::updateDraftNoteText,
            onSaveNote = stateHolder::saveNoteNow,
            onOpenTopic = { topicId ->
                onDismiss()
                onOpenTopic(topicId)
            },
            onOpenUserProfile = { targetUid, targetUsername ->
                onDismiss()
                onOpenUserProfile(targetUid, targetUsername)
            },
            onStartDm = onStartDm?.let { startDm ->
                { targetUid, targetUsername ->
                    onDismiss()
                    startDm(targetUid, targetUsername)
                }
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun SellerTrustCardSheetContent(
    uiState: SellerTrustUiState,
    onRefresh: () -> Unit,
    onToggleTag: (TrustTag) -> Unit,
    onNoteTextChange: (String) -> Unit,
    onSaveNote: () -> Unit,
    onOpenTopic: (Long) -> Unit,
    onOpenUserProfile: (Long, String) -> Unit,
    onStartDm: ((Long, String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val report = uiState.report
    val extraType = NodePulseThemeTokens.extraTypography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // ── Sheet Title Bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "交易前查一查 · 卖家信誉卡",
                    style = extraType.sectionHeader,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val headerSubtitle = rememberTrustHeaderSubtitle(uiState)
                Text(
                    text = headerSubtitle,
                    style = extraType.microLabel.withTabularNumbers(),
                    color = if (uiState.loadStatus == SellerTrustLoadStatus.RATE_LIMITED) {
                        TrustPalette.forRiskLevel(TrustRiskLevel.CAUTION_AMBER).accent
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭卖家信誉卡",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ── Scrollable Card Body ──
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Seller Identity Header
            SellerIdentityHeaderCard(uiState = uiState)

            when (val status = uiState.loadStatus) {
                SellerTrustLoadStatus.LOADING -> {
                    SellerTrustLoadingSkeleton()
                }

                SellerTrustLoadStatus.RATE_LIMITED,
                SellerTrustLoadStatus.FAILED_RETRYABLE -> {
                    // Always surface local blacklist / caution banner even when remote is rate-limited or offline.
                    if (report != null && uiState.localNote?.isMeaningful == true) {
                        RiskAssessmentBanner(
                            assessment = report.riskAssessment,
                            searchSucceeded = true,
                            searchFailureReason = null,
                            cooldownRemainingSeconds = uiState.cooldownRemainingSeconds,
                            onRetrySearch = onRefresh,
                        )
                    }

                    if (status == SellerTrustLoadStatus.RATE_LIMITED) {
                        SellerTrustRateLimitedCard(
                            message = report?.failureMessage
                                ?: uiState.errorMessage
                                ?: "请求过于频繁触发官网限流保护，请稍后重试",
                            cooldownRemainingSeconds = uiState.cooldownRemainingSeconds,
                            onRetry = onRefresh,
                        )
                    } else {
                        SellerTrustErrorCard(
                            message = report?.failureMessage
                                ?: uiState.errorMessage
                                ?: "信誉核验加载失败，请检查网络后手动重试",
                            isNegativeCacheHit = report?.isNegativeCache == true && report.isFromCache,
                            cooldownRemainingSeconds = uiState.cooldownRemainingSeconds,
                            onRetry = onRefresh,
                        )
                    }
                }

                SellerTrustLoadStatus.FROM_CACHE,
                SellerTrustLoadStatus.FRESH -> {
                    if (report != null) {
                        if (report.isFromCache || uiState.cooldownRemainingSeconds > 0 || !uiState.statusNoticeMessage.isNullOrBlank()) {
                            TrustCacheAndCooldownStatusStrip(
                                report = report,
                                cooldownRemainingSeconds = uiState.cooldownRemainingSeconds,
                                statusNoticeMessage = uiState.statusNoticeMessage,
                                isManualRefreshThrottled = uiState.isManualRefreshThrottled || report.isRefreshThrottled,
                            )
                        }

                        // 2. Prominent Risk Banner
                        RiskAssessmentBanner(
                            assessment = report.riskAssessment,
                            searchSucceeded = report.searchSucceeded,
                            searchFailureReason = report.searchFailureReason,
                            cooldownRemainingSeconds = uiState.cooldownRemainingSeconds,
                            onRetrySearch = onRefresh,
                        )

                        // 3. 4-Metric Bento Grid
                        SellerMetricsGrid(report = report)

                        // 4a. Exposure / Scam Hits (Red-tinted clickable cards)
                        if (report.exposureSearchHits.isNotEmpty()) {
                            ExposureHitsSection(
                                hits = report.exposureSearchHits,
                                onOpenTopic = onOpenTopic,
                            )
                        }

                        // 4b. Recent Trade Threads (up to 3 by default, expandable)
                        RecentTradeTopicsSection(
                            totalTradeCount = report.historicalTradeTopicCount,
                            recentTopics = report.recentTradeTopics,
                            topicsSucceeded = report.topicsSucceeded,
                            onOpenTopic = onOpenTopic,
                        )
                    }
                }
            }

            // 5. Local Private Note & 1-tap TrustTag Chips
            LocalSellerNoteCard(
                selectedTag = uiState.draftTag,
                noteText = uiState.draftNoteText,
                isSaving = uiState.isSavingNote,
                savedFeedback = uiState.noteSavedFeedback,
                hasUnsavedChanges = uiState.hasUnsavedNoteChanges,
                enabled = uiState.resolvedUid > 0L,
                onToggleTag = onToggleTag,
                onNoteTextChange = onNoteTextChange,
                onSaveNow = onSaveNote,
            )

            Spacer(modifier = Modifier.height(4.dp))
        }

        PulseGradientDivider(modifier = Modifier.padding(top = 8.dp))

        // 6. Bottom Quick Actions
        SellerTrustBottomActions(
            resolvedUid = uiState.resolvedUid,
            resolvedUsername = uiState.resolvedUsername,
            isRefreshing = uiState.isRefreshing || uiState.isLoading,
            cooldownRemainingSeconds = uiState.cooldownRemainingSeconds,
            hasUsableReport = uiState.hasUsableReport,
            onOpenUserProfile = onOpenUserProfile,
            onStartDm = onStartDm,
            onRefresh = onRefresh,
        )
    }
}

@Composable
private fun SellerIdentityHeaderCard(
    uiState: SellerTrustUiState,
) {
    val report = uiState.report
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val rankLevel = report?.rankLevel
    val activeTag = uiState.draftTag

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.ElevatedTile,
                elevation = PulseElevation.Subtle,
            )
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PulseAvatar(
                avatarUrl = uiState.resolvedAvatarUrl,
                username = uiState.resolvedUsername,
                size = 54.dp,
                rankLevel = rankLevel,
                ringWidth = 2.2.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "@${uiState.resolvedUsername}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (rankLevel != null) {
                        RankMicroBadge(
                            level = rankLevel,
                            showPersona = true,
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (uiState.resolvedUid > 0L) {
                        Box(
                            modifier = Modifier
                                .clip(PulseArchitecturalShapes.SmallChip)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(
                                        alpha = if (isDark) 0.56f else 0.82f,
                                    ),
                                )
                                .border(
                                    width = 0.8.dp,
                                    color = ext.cardBorder,
                                    shape = PulseArchitecturalShapes.SmallChip,
                                )
                                .padding(horizontal = 7.dp, vertical = 2.5.dp),
                        ) {
                            Text(
                                text = "UID #${uiState.resolvedUid}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                ).withTabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }

                    if (report != null) {
                        val tenureTone = if (report.isNewAccount) {
                            TrustPalette.forTag(TrustTag.CAUTION)
                        } else {
                            TrustPalette.forTag(TrustTag.NONE)
                        }
                        PulseAccentPill(
                            text = report.registrationDurationLabel,
                            icon = Icons.Rounded.Schedule,
                            containerColor = tenureTone.container,
                            contentColor = tenureTone.content,
                        )
                    }

                    if (activeTag.isTagged) {
                        val tagTone = TrustPalette.forTag(activeTag)
                        PulseAccentPill(
                            text = activeTag.shortLabel,
                            icon = tagTone.icon,
                            containerColor = tagTone.container,
                            contentColor = tagTone.content,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberTrustHeaderSubtitle(uiState: SellerTrustUiState): String {
    val report = uiState.report
    val cooldownSec = uiState.cooldownRemainingSeconds
    return when (uiState.loadStatus) {
        SellerTrustLoadStatus.LOADING -> "加载中 · 正在请求官网核验卖家信誉..."
        SellerTrustLoadStatus.FROM_CACHE -> {
            val updatedAt = formatTrustUpdatedAt(report?.fetchedAtEpochMillis ?: 0L)
            when {
                uiState.isRefreshing -> "正在刷新核验 · 当前缓存更新于 $updatedAt"
                report?.fetchStatus == TrustFetchStatus.STALE_CACHE_FALLBACK && !uiState.isManualRefreshThrottled -> {
                    if (cooldownSec > 0) {
                        "来自缓存（限流冷却 ${cooldownSec}s）· 更新于 $updatedAt"
                    } else {
                        "来自缓存（限流兜底）· 更新于 $updatedAt"
                    }
                }
                cooldownSec > 0 -> "来自缓存 · 更新于 $updatedAt · ${cooldownSec}s 后可再刷"
                else -> "来自缓存 · 更新于 $updatedAt"
            }
        }
        SellerTrustLoadStatus.FRESH -> {
            if (uiState.isRefreshing) {
                "正在向官网刷新核验..."
            } else {
                val updatedAt = formatTrustUpdatedAt(report?.fetchedAtEpochMillis ?: 0L)
                "实时核验 · 更新于 $updatedAt"
            }
        }
        SellerTrustLoadStatus.RATE_LIMITED -> {
            if (cooldownSec > 0) {
                "被限流稍后重试 · 冷却剩余 ${cooldownSec}s"
            } else {
                "限流冷却已结束 · 可点击下方手动重试"
            }
        }
        SellerTrustLoadStatus.FAILED_RETRYABLE -> "核验加载失败 · 可点击下方手动重试"
    }
}

@Composable
private fun TrustCacheAndCooldownStatusStrip(
    report: SellerTrustReport,
    cooldownRemainingSeconds: Int,
    statusNoticeMessage: String?,
    isManualRefreshThrottled: Boolean,
) {
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val updatedAt = formatTrustUpdatedAt(report.fetchedAtEpochMillis)
    val isRateLimitFallback =
        report.fetchStatus == TrustFetchStatus.STALE_CACHE_FALLBACK && !isManualRefreshThrottled

    val amberTone = TrustPalette.forRiskLevel(TrustRiskLevel.CAUTION_AMBER)
    val containerColor = if (isRateLimitFallback) {
        amberTone.container
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (isDark) 0.88f else 0.94f)
    }
    val borderColor = if (isRateLimitFallback) amberTone.border else ext.cardBorder
    val contentColor = if (isRateLimitFallback) amberTone.content else MaterialTheme.colorScheme.onSurfaceVariant
    val iconColor = if (isRateLimitFallback) amberTone.accent else MaterialTheme.colorScheme.primary

    val displayText = when {
        isRateLimitFallback -> buildString {
            append("官网触发限流保护，当前展示缓存数据（更新于 ")
            append(updatedAt)
            append("）")
            if (cooldownRemainingSeconds > 0) {
                append(" · ")
                append(cooldownRemainingSeconds)
                append("s 后可重试")
            }
        }
        isManualRefreshThrottled && cooldownRemainingSeconds > 0 -> {
            "手动刷新受节流保护，请 ${cooldownRemainingSeconds}s 后再试 · 缓存更新于 $updatedAt"
        }
        !statusNoticeMessage.isNullOrBlank() -> "$statusNoticeMessage · 更新于 $updatedAt"
        else -> "来自本地缓存 · 更新于 $updatedAt"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.MediumBadge)
            .background(containerColor)
            .border(width = 0.8.dp, color = borderColor, shape = PulseArchitecturalShapes.MediumBadge)
            .padding(horizontal = 11.dp, vertical = 7.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = if (isRateLimitFallback) Icons.Rounded.WarningAmber else Icons.Rounded.Schedule,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(14.5.dp),
        )
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RiskAssessmentBanner(
    assessment: TrustRiskAssessment,
    searchSucceeded: Boolean,
    searchFailureReason: String?,
    cooldownRemainingSeconds: Int = 0,
    onRetrySearch: () -> Unit,
) {
    val tone = TrustPalette.forRiskLevel(assessment.level)
    val isDark = NodePulseThemeTokens.isDark
    val shape = PulseArchitecturalShapes.EditorialCard

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.backgroundBrush)
            .border(
                width = if (assessment.level == TrustRiskLevel.HIGH_RISK_RED) 1.35.dp else 1.dp,
                color = tone.border,
                shape = shape,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(tone.accent.copy(alpha = if (isDark) 0.24f else 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tone.icon,
                    contentDescription = null,
                    tint = tone.accent,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val badgeText = when (assessment.level) {
                    TrustRiskLevel.HIGH_RISK_RED -> "高危预警"
                    TrustRiskLevel.CAUTION_AMBER -> "谨慎提示"
                    TrustRiskLevel.NORMAL_CLEAR -> "核验通过"
                }
                Text(
                    text = badgeText,
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = tone.accent,
                )
                Text(
                    text = assessment.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = tone.content,
                )
            }
        }

        if (assessment.reasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                assessment.reasons.forEach { reason ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = tone.accent,
                        )
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = tone.content.copy(alpha = 0.92f),
                        )
                    }
                }
            }
        }

        if (!searchSucceeded && !searchFailureReason.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.34f else 0.62f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = tone.accent,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = searchFailureReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = tone.content,
                    modifier = Modifier.weight(1f),
                )
                val canRetry = cooldownRemainingSeconds <= 0
                Text(
                    text = if (canRetry) "重试" else "${cooldownRemainingSeconds}s",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    fontWeight = FontWeight.Bold,
                    color = if (canRetry) tone.accent else tone.content.copy(alpha = 0.62f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = canRetry, role = Role.Button, onClick = onRetrySearch)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // Always show concise middleman safety tip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.SmallChip)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.28f else 0.56f))
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = tone.content.copy(alpha = 0.82f),
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = assessment.safetyTip,
                style = MaterialTheme.typography.labelSmall,
                color = tone.content.copy(alpha = 0.86f),
            )
        }
    }
}

@Composable
private fun SellerMetricsGrid(
    report: SellerTrustReport,
) {
    val exposureCount = report.exposureSearchHits.size

    val coinsText = report.coins?.let { formatCompactCount(it) } ?: "--"
    val rankSubtext = buildString {
        if (report.stardust != null) {
            append("星尘 ${formatCompactCount(report.stardust)}")
        }
        if (report.totalTopicsCount != null || report.totalCommentsCount != null) {
            if (isNotEmpty()) append(" · ")
            append("帖 ${report.totalTopicsCount ?: 0} / 评 ${report.totalCommentsCount ?: 0}")
        }
        if (isEmpty()) {
            append("社区活跃度指标")
        }
    }

    val tenureSubtext = when {
        report.createdAtText.isNotBlank() -> "注册于 ${report.createdAtText}"
        report.isNewAccount -> "未满 30 天新号"
        else -> "未公开注册日期"
    }

    val tradeSubtext = when {
        !report.topicsSucceeded -> "主题列表加载失败"
        report.historicalTradeTopicCount > 0 -> "近1页公开主题统计"
        else -> "近1页无交易相关发帖"
    }

    val exposureValue = when {
        exposureCount > 0 -> "$exposureCount 条"
        !report.searchSucceeded -> "待复核"
        else -> "0 条"
    }
    val exposureSubtext = when {
        exposureCount > 0 -> "命中站内曝光/骗子帖"
        !report.searchSucceeded -> "搜索未完整完成"
        else -> "站内检索未见曝光"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SellerMetricTile(
                label = "注册时长",
                value = report.registrationDurationLabel,
                subtitle = tenureSubtext,
                icon = Icons.Rounded.Schedule,
                modifier = Modifier.weight(1f),
            )
            SellerMetricTile(
                label = "会员等级 & 鸡腿",
                value = "${report.rankLabel} · $coinsText",
                subtitle = rankSubtext,
                icon = Icons.Rounded.Restaurant,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SellerMetricTile(
                label = "历史交易帖",
                value = if (report.topicsSucceeded) "${report.historicalTradeTopicCount} 篇" else "--",
                subtitle = tradeSubtext,
                icon = Icons.Rounded.Storefront,
                modifier = Modifier.weight(1f),
            )
            SellerMetricTile(
                label = "曝光·骗子命中",
                value = exposureValue,
                subtitle = exposureSubtext,
                icon = if (exposureCount > 0) Icons.Rounded.GppBad else Icons.Rounded.WarningAmber,
                highlightDanger = exposureCount > 0,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SellerMetricTile(
    label: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    highlightDanger: Boolean = false,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val iconTint = if (highlightDanger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(12.5.dp),
                )
            }
            Text(
                text = label,
                style = NodePulseThemeTokens.extraTypography.microLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
            fontWeight = FontWeight.Bold,
            color = if (highlightDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
private fun ExposureHitsSection(
    hits: List<ExposureHitTopic>,
    onOpenTopic: (Long) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val redTone = TrustPalette.forRiskLevel(TrustRiskLevel.HIGH_RISK_RED)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PulseSectionHeader(
            title = "曝光 / 骗子关联帖 (${hits.size})",
            subtitle = "点击条目直达原帖核实详情",
            icon = Icons.Rounded.GppBad,
            accentColor = redTone.accent,
            trailing = {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = redTone.accent,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = redTone.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            },
        )

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hits.forEach { hit ->
                    ExposureHitCard(
                        hit = hit,
                        onClick = { onOpenTopic(hit.topicId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExposureHitCard(
    hit: ExposureHitTopic,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val shape = PulseArchitecturalShapes.EditorialCard

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseBouncyClickable(role = Role.Button, onClick = onClick)
            .clip(shape)
            .background(ext.cardSurface)
            .border(width = 0.8.dp, color = ext.cardBorder, shape = shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 6.dp, vertical = 1.5.dp),
                ) {
                    Text(
                        text = hit.matchedTerm,
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                if (hit.categoryName.isNotBlank()) {
                    Text(
                        text = hit.categoryName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                if (hit.publishedAtText.isNotBlank()) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    RelativeTimeText(
                        isoTimestamp = hit.publishedAtText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = hit.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (hit.authorName.isNotBlank()) {
                Text(
                    text = "发帖人：${hit.authorName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = "查看原帖",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RecentTradeTopicsSection(
    totalTradeCount: Int,
    recentTopics: List<TopicSummary>,
    topicsSucceeded: Boolean,
    onOpenTopic: (Long) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visibleTopics = if (showAll) recentTopics else recentTopics.take(DEFAULT_VISIBLE_TRADE_TOPICS)
    val canExpandMore = recentTopics.size > DEFAULT_VISIBLE_TRADE_TOPICS

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PulseSectionHeader(
            title = "近期交易帖 ($totalTradeCount)",
            subtitle = "查看卖家过往出/收贴历史",
            icon = Icons.Rounded.Storefront,
            accentColor = MaterialTheme.colorScheme.primary,
            trailing = if (canExpandMore) {
                {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { showAll = !showAll }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = if (showAll) "收起" else "展开全部",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = if (showAll) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            } else {
                null
            },
        )

        if (!topicsSucceeded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(elevation = PulseElevation.None)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "卖家主题列表暂未加载成功，可点击下方「刷新核验」重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (visibleTopics.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(elevation = PulseElevation.None)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "近 1 页公开主题中未检索到交易帖记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                visibleTopics.forEach { topic ->
                    RecentTradeTopicRow(
                        topic = topic,
                        onClick = { onOpenTopic(topic.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentTradeTopicRow(
    topic: TopicSummary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseBouncyClickable(role = Role.Button, onClick = onClick)
            .pulseCard(elevation = PulseElevation.Subtle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CategoryMicroTag(
                    slug = topic.categorySlug.ifBlank { "trade" },
                    labelOverride = topic.categoryName.ifBlank { "交易" },
                    showIcon = false,
                    compact = true,
                )
                Text(
                    text = "${formatCompactCount(topic.replyCount)} 回复 · ${formatCompactCount(topic.viewCount)} 浏览",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (topic.lastActivityIso.isNotBlank()) {
                    RelativeTimeText(
                        isoTimestamp = topic.lastActivityIso,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = "打开交易帖",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun LocalSellerNoteCard(
    selectedTag: TrustTag,
    noteText: String,
    isSaving: Boolean,
    savedFeedback: Boolean,
    hasUnsavedChanges: Boolean,
    enabled: Boolean,
    onToggleTag: (TrustTag) -> Unit,
    onNoteTextChange: (String) -> Unit,
    onSaveNow: () -> Unit,
) {
    val extraType = NodePulseThemeTokens.extraTypography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseSectionHeader(
            title = "本地私人备注",
            subtitle = "仅保存在本机，随时标记靠谱或避雷",
            icon = Icons.Rounded.EditNote,
            accentColor = MaterialTheme.colorScheme.primary,
            trailing = {
                AnimatedVisibility(
                    visible = isSaving || savedFeedback,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.8.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "保存中",
                                style = extraType.microLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = TrustPalette.forTag(TrustTag.TRUSTED).accent,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = "已保存",
                                style = extraType.microLabel,
                                color = TrustPalette.forTag(TrustTag.TRUSTED).accent,
                            )
                        }
                    }
                }
            },
        )

        // 1-tap TrustTag Filter Chips (靠谱 / 观察 / 谨慎 / 避雷)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SELECTABLE_TRUST_TAGS.forEach { tag ->
                TrustTagFilterChip(
                    tag = tag,
                    selected = selectedTag == tag,
                    enabled = enabled,
                    onClick = { onToggleTag(tag) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Compact Note TextField + explicit Save Button
        OutlinedTextField(
            value = noteText,
            onValueChange = onNoteTextChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            minLines = 2,
            maxLines = 3,
            placeholder = {
                Text(
                    text = "记录交易经历、联系方式、走中介情况或避雷原因...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            },
            textStyle = MaterialTheme.typography.bodySmall,
            shape = PulseArchitecturalShapes.MediumBadge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${noteText.length}/${SellerTrustStateHolder.MAX_NOTE_LENGTH} · 自动保存",
                style = extraType.microLabel.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FilledTonalButton(
                onClick = onSaveNow,
                enabled = enabled && !isSaving && (hasUnsavedChanges || noteText.isNotBlank() || selectedTag.isTagged),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
                shape = PulseArchitecturalShapes.SmallChip,
            ) {
                Icon(
                    imageVector = if (savedFeedback && !hasUnsavedChanges) Icons.Rounded.Check else Icons.Rounded.Save,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "保存备注",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun TrustTagFilterChip(
    tag: TrustTag,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = TrustPalette.forTag(tag)
    val isDark = NodePulseThemeTokens.isDark
    val interactionSource = remember { MutableInteractionSource() }
    val shape = PulseArchitecturalShapes.MediumBadge

    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            tone.container
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (isDark) 0.90f else 0.75f)
        },
        label = "trustTagChipBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            tone.accent
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f)
        },
        label = "trustTagChipBorder",
    )
    val fgColor by animateColorAsState(
        targetValue = if (selected) tone.content else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "trustTagChipFg",
    )

    Row(
        modifier = modifier
            .heightIn(min = 34.dp)
            .pulsePressScale(
                interactionSource = interactionSource,
                pressedScale = NodePulseMotion.chipPressScale,
            )
            .clip(shape)
            .background(if (selected) tone.backgroundBrush else SolidColor(bgColor))
            .border(
                width = if (selected) 1.2.dp else 0.8.dp,
                color = borderColor,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tone.icon,
            contentDescription = null,
            tint = if (selected) tone.accent else fgColor,
            modifier = Modifier.size(13.dp),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = tag.shortLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fgColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SellerTrustBottomActions(
    resolvedUid: Long,
    resolvedUsername: String,
    isRefreshing: Boolean,
    cooldownRemainingSeconds: Int,
    hasUsableReport: Boolean,
    onOpenUserProfile: (Long, String) -> Unit,
    onStartDm: ((Long, String) -> Unit)?,
    onRefresh: () -> Unit,
) {
    val isCoolingDown = cooldownRemainingSeconds > 0
    val canClickRefresh = !isRefreshing && !isCoolingDown

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onOpenUserProfile(resolvedUid, resolvedUsername) },
            enabled = resolvedUid > 0L || resolvedUsername.isNotBlank(),
            modifier = Modifier.weight(1f),
            shape = PulseArchitecturalShapes.MediumBadge,
            border = BorderStroke(0.8.dp, NodePulseThemeTokens.extendedColors.cardBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "个人主页",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
            )
        }

        if (onStartDm != null && resolvedUid > 0L) {
            Button(
                onClick = { onStartDm(resolvedUid, resolvedUsername) },
                modifier = Modifier.weight(1f),
                shape = PulseArchitecturalShapes.MediumBadge,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mail,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "站内私信",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        FilledTonalButton(
            onClick = onRefresh,
            enabled = canClickRefresh,
            modifier = Modifier.weight(if (onStartDm != null && resolvedUid > 0L) 0.95f else 1f),
            shape = PulseArchitecturalShapes.MediumBadge,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
            when {
                isRefreshing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
                isCoolingDown -> {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(5.dp))
            val buttonLabel = when {
                isRefreshing -> "加载中"
                isCoolingDown -> "冷却 ${cooldownRemainingSeconds}s"
                !hasUsableReport -> "手动重试"
                else -> "刷新核验"
            }
            Text(
                text = buttonLabel,
                style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SellerTrustLoadingSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.SmallChip)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.8.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "正在从官网拉取卖家档案、近期交易帖与曝光检索...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PulseShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp),
            shape = PulseArchitecturalShapes.EditorialCard,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(74.dp),
                shape = PulseArchitecturalShapes.EditorialCard,
            )
            PulseShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(74.dp),
                shape = PulseArchitecturalShapes.EditorialCard,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(74.dp),
                shape = PulseArchitecturalShapes.EditorialCard,
            )
            PulseShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(74.dp),
                shape = PulseArchitecturalShapes.EditorialCard,
            )
        }
    }
}

@Composable
private fun SellerTrustRateLimitedCard(
    message: String,
    cooldownRemainingSeconds: Int,
    onRetry: () -> Unit,
) {
    val amberTone = TrustPalette.forRiskLevel(TrustRiskLevel.CAUTION_AMBER)
    val shape = PulseArchitecturalShapes.EditorialCard
    val isCoolingDown = cooldownRemainingSeconds > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(amberTone.backgroundBrush)
            .border(width = 1.dp, color = amberTone.border, shape = shape)
            .padding(14.dp),
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
                text = "触发官网限流 · 请稍后重试",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = amberTone.content,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = amberTone.content.copy(alpha = 0.90f),
        )
        Text(
            text = if (isCoolingDown) {
                "保护冷却期内已暂停继续请求官网，剩余约 ${cooldownRemainingSeconds} 秒后自动解锁重试。"
            } else {
                "限流冷却期已结束，您可以点击下方按钮手动重新核验。"
            },
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = amberTone.content.copy(alpha = 0.80f),
        )
        FilledTonalButton(
            onClick = onRetry,
            enabled = !isCoolingDown,
            shape = PulseArchitecturalShapes.SmallChip,
        ) {
            Icon(
                imageVector = if (isCoolingDown) Icons.Rounded.Schedule else Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (isCoolingDown) "稍后重试 (${cooldownRemainingSeconds}s)" else "手动重试",
                style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
            )
        }
    }
}

@Composable
private fun SellerTrustErrorCard(
    message: String,
    isNegativeCacheHit: Boolean,
    cooldownRemainingSeconds: Int,
    onRetry: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val shape = PulseArchitecturalShapes.EditorialCard
    val isThrottled = cooldownRemainingSeconds > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = shape,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .padding(14.dp),
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
                text = "信誉核验加载失败 · 可手动重试",
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
        if (isNegativeCacheHit) {
            Text(
                text = "为避免连续失败导致官网限流，自动查询已暂停片刻；您仍可随时点击下方手动重试。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f),
            )
        }
        FilledTonalButton(
            onClick = onRetry,
            enabled = !isThrottled,
            shape = PulseArchitecturalShapes.SmallChip,
        ) {
            Icon(
                imageVector = if (isThrottled) Icons.Rounded.Schedule else Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (isThrottled) "请稍候 (${cooldownRemainingSeconds}s)" else "手动重试",
                style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
            )
        }
    }
}
