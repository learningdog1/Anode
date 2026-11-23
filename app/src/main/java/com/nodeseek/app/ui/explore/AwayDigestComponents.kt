@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.explore

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.digest.AwayDigestItem
import com.nodeseek.app.domain.digest.AwayDigestState
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.ui.components.CategoryMicroTag
import com.nodeseek.app.ui.components.NodeSeekDrumstickFilledIcon
import com.nodeseek.app.ui.components.NodeSeekDrumstickIcon
import com.nodeseek.app.ui.components.RelativeTimeText
import com.nodeseek.app.ui.components.formatCompactCount
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers

private const val COLLAPSED_PREVIEW_COUNT = 3

/**
 * Prominent editorial card rendered at the top of `ExploreScreen` when reopening the app
 * after an away session (`离开期间速览 · 你离开了 X 小时`).
 */
@Composable
fun AwayDigestHeroCard(
    digestState: AwayDigestState,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenFullSheet: () -> Unit,
    onDismiss: () -> Unit,
    onTopicClick: (TopicSummary) -> Unit,
    onSelectCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!digestState.isVisible) return

    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val accent = ext.drumstickGoldEnd

    val visibleItems = remember(digestState.items, isExpanded) {
        if (isExpanded) {
            digestState.items
        } else {
            digestState.items.take(COLLAPSED_PREVIEW_COUNT)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = accent,
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.CardElevated,
            )
            .animateContentSize()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── Header: "你离开了 X 小时" + Tag + Dismiss ("跳过") ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                ext.drumstickContainer,
                                blendColor(ext.drumstickContainer, ext.drumstickGoldStart, if (isDark) 0.24f else 0.18f),
                            ),
                        ),
                    )
                    .border(
                        width = 0.85.dp,
                        color = ext.drumstickGoldEnd.copy(alpha = if (isDark) 0.44f else 0.32f),
                        shape = RoundedCornerShape(11.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = ext.drumstickGoldEnd,
                    modifier = Modifier.size(19.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = digestState.headlineText,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium.withTabularNumbers(),
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(ext.drumstickContainer.copy(alpha = if (isDark) 0.72f else 0.90f))
                            .border(
                                width = 0.7.dp,
                                color = ext.drumstickGoldEnd.copy(alpha = 0.30f),
                                shape = CircleShape,
                            )
                            .padding(horizontal = 6.5.dp, vertical = 1.5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "离开期间速览",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = ext.onDrumstickContainer,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                if (digestState.subtitleText.isNotBlank()) {
                    Text(
                        text = digestState.subtitleText,
                        style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.78f else 0.85f))
                    .border(
                        width = 0.75.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        shape = CircleShape,
                    )
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClickLabel = "跳过离开期间速览",
                        onClick = onDismiss,
                    )
                    .padding(start = 8.5.dp, end = 6.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "跳过",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭速览",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.5.dp),
                )
            }
        }

        // ── Loading or Ranked Unread Items ──
        if (digestState.isLoading && digestState.items.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = ext.drumstickGoldEnd,
                )
                Text(
                    text = "正在扫描多页新帖并综合回复数、鸡腿数与热度排序...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                visibleItems.forEach { item ->
                    AwayDigestTopicRow(
                        item = item,
                        onClick = { onTopicClick(item.topic) },
                        onCategoryClick = { slug ->
                            if (slug.isNotBlank()) {
                                onSelectCategory(slug)
                            }
                        },
                    )
                }
            }

            // ── Footer Actions: Expand/Collapse or Open All-10 Sheet ──
            if (digestState.items.size > COLLAPSED_PREVIEW_COUNT) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.42f else 0.62f))
                            .border(
                                width = 0.75.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                                shape = CircleShape,
                            )
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Button,
                                onClick = onToggleExpanded,
                            )
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = if (isExpanded) {
                                "收起至前 $COLLAPSED_PREVIEW_COUNT 帖"
                            } else {
                                "展开全部 ${digestState.items.size} 帖"
                            },
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.80f else 0.86f))
                            .border(
                                width = 0.75.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                shape = CircleShape,
                            )
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Button,
                                onClickLabel = "查看全部 ${digestState.items.size} 帖弹窗",
                                onClick = onOpenFullSheet,
                            )
                            .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "查看全部 ${digestState.items.size} 帖",
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modal bottom sheet presenting all up to 10 unread highlights in a dedicated scrollable view.
 */
@Composable
fun AwayDigestBottomSheet(
    digestState: AwayDigestState,
    onDismissSheet: () -> Unit,
    onDismissDigestSession: () -> Unit,
    onTopicClick: (TopicSummary) -> Unit,
    onSelectCategory: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissSheet,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = ext.cardSurfaceElevated,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = digestState.headlineText.ifBlank { "离开期间速览" },
                        style = MaterialTheme.typography.titleLarge.withTabularNumbers(),
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (digestState.subtitleText.isNotBlank()) {
                        Text(
                            text = digestState.subtitleText,
                            style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        onDismissSheet()
                        onDismissDigestSession()
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "全部跳过",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = digestState.items,
                    key = { it.topic.id },
                ) { item ->
                    AwayDigestTopicRow(
                        item = item,
                        onClick = {
                            onTopicClick(item.topic)
                            onDismissSheet()
                        },
                        onCategoryClick = { slug ->
                            if (slug.isNotBlank()) {
                                onSelectCategory(slug)
                                onDismissSheet()
                            }
                        },
                    )
                }
            }

            FilledTonalButton(
                onClick = onDismissSheet,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "继续浏览首页")
            }
        }
    }
}

/**
 * Single ranked unread item (`#1`..`#10`) displaying rank badge, category chip, title,
 * author/time, **鸡腿数** (`NodeSeekDrumstickIcon`), **回复数**, and **热度** indicator.
 */
@Composable
fun AwayDigestTopicRow(
    item: AwayDigestItem,
    onClick: () -> Unit,
    onCategoryClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val fontScale = NodePulseThemeTokens.fontScale
    val interactionSource = remember { MutableInteractionSource() }
    val rowShape = RoundedCornerShape(13.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulsePressScale(interactionSource = interactionSource, pressedScale = 0.985f)
            .clip(rowShape)
            .background(
                ext.cardSurface.copy(alpha = if (isDark) 0.72f else 0.86f),
            )
            .border(
                width = 0.8.dp,
                color = if (item.rank == 1) {
                    ext.drumstickGoldEnd.copy(alpha = if (isDark) 0.36f else 0.28f)
                } else {
                    ext.cardBorder
                },
                shape = rowShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // ── Top Row: Rank Badge (#1..#10) + Category Chip + Deal/Award Tag + Author/Time ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AwayRankBadge(rank = item.rank)

            if (item.topic.categorySlug.isNotBlank() || item.topic.categoryName.isNotBlank()) {
                CategoryMicroTag(
                    slug = item.topic.categorySlug,
                    labelOverride = item.topic.categoryName,
                    showIcon = false,
                    compact = true,
                    onClick = if (onCategoryClick != null && item.topic.categorySlug.isNotBlank()) {
                        { onCategoryClick(item.topic.categorySlug) }
                    } else {
                        null
                    },
                )
            }

            if (item.isDealOrPromo) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ext.heatContainer.copy(alpha = if (isDark) 0.75f else 0.85f))
                        .padding(horizontal = 5.5.dp, vertical = 1.5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "好价/线报",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = ext.onHeatContainer,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            } else if (item.isCuratedAward) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f))
                        .padding(horizontal = 5.5.dp, vertical = 1.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = ext.stardustAccent,
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        text = "精选",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.topic.authorName.ifBlank { "匿名" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.publishedAtIso.isNotBlank()) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                    RelativeTimeText(
                        isoTimestamp = item.publishedAtIso,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Title ──
        Text(
            text = item.topic.title,
            style = NodePulseThemeTokens.extraTypography.titleMediumUnread.copy(
                fontSize = (14.5f * fontScale).sp,
                lineHeight = (20f * fontScale).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // ── Metrics Row: 鸡腿数 + 回复数 + 热度 ──
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 1. 鸡腿数 (Drumsticks) using NodeSeekDrumstickIcon
            val hasDrumsticks = item.drumstickCount > 0
            Row(
                modifier = Modifier
                    .heightIn(min = 20.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasDrumsticks) {
                            ext.drumstickContainer.copy(alpha = if (isDark) 0.85f else 0.92f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
                        },
                    )
                    .border(
                        width = 0.7.dp,
                        color = if (hasDrumsticks) {
                            ext.drumstickGoldEnd.copy(alpha = 0.36f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        },
                        shape = CircleShape,
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.5.dp),
            ) {
                Icon(
                    imageVector = if (hasDrumsticks) NodeSeekDrumstickFilledIcon else NodeSeekDrumstickIcon,
                    contentDescription = "鸡腿数",
                    tint = if (hasDrumsticks) ext.drumstickGoldEnd else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "${formatCompactCount(item.drumstickCount)} 鸡腿",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    fontWeight = if (hasDrumsticks) FontWeight.Bold else FontWeight.Medium,
                    color = if (hasDrumsticks) ext.onDrumstickContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            // 2. 回复数 (Replies)
            Row(
                modifier = Modifier
                    .heightIn(min = 20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.82f else 0.76f))
                    .border(
                        width = 0.7.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                        shape = CircleShape,
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.5.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChatBubbleOutline,
                    contentDescription = "回复数",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = "${formatCompactCount(item.replyCount)} 回复",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            // 3. 热度 (Composite Hotness Indicator)
            Row(
                modifier = Modifier
                    .heightIn(min = 20.dp)
                    .clip(CircleShape)
                    .background(ext.heatContainer.copy(alpha = if (isDark) 0.72f else 0.82f))
                    .border(
                        width = 0.7.dp,
                        color = ext.onHeatContainer.copy(alpha = if (isDark) 0.30f else 0.20f),
                        shape = CircleShape,
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocalFireDepartment,
                    contentDescription = "热度",
                    tint = ext.onHeatContainer,
                    modifier = Modifier.size(11.5.dp),
                )
                Text(
                    text = "热度 ${item.hotnessIndex}",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = ext.onHeatContainer,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun AwayRankBadge(
    rank: Int,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val shape = RoundedCornerShape(6.5.dp)

    val (bgBrush, fgColor, borderColor) = when (rank) {
        1 -> Triple(
            PulseBrushes.drumstickGoldGradient(),
            Color(0xFF231600),
            ext.drumstickGoldEnd.copy(alpha = 0.55f),
        )
        2 -> Triple(
            PulseBrushes.heatFlameGradient(),
            Color.White,
            ext.onHeatContainer.copy(alpha = 0.45f),
        )
        3 -> Triple(
            SolidColor(MaterialTheme.colorScheme.primaryContainer),
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.42f else 0.26f),
        )
        else -> Triple(
            SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest),
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        )
    }

    Box(
        modifier = modifier
            .height(20.dp)
            .clip(shape)
            .background(bgBrush)
            .border(width = 0.75.dp, color = borderColor, shape = shape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "#$rank",
            style = NodePulseThemeTokens.extraTypography.tabularBadge,
            fontWeight = FontWeight.ExtraBold,
            color = fgColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}
