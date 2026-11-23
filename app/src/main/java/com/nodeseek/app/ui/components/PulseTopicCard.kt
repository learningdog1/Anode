package com.nodeseek.app.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.ProvideSharedPulseShimmer
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers

/**
 * Decoupled UI model for `PulseTopicCard` so `ui/components` stays independent of `domain`.
 */
@Immutable
data class PulseTopicCardModel(
    val id: Long,
    val title: String,
    val categorySlug: String,
    val categoryName: String,
    val authorId: Long? = null,
    val authorName: String,
    val authorAvatarUrl: String = "",
    val authorLevel: Int? = null,
    val viewCount: Int = 0,
    val replyCount: Int = 0,
    val lastActivityIso: String = "",
    val isPinned: Boolean = false,
    val requiredViewLevel: Int? = null,
    val isRead: Boolean = false,
    val lastReadFloor: Int? = null,
    val isBookmarked: Boolean = false,
    val isHot: Boolean = false,
)

/**
 * Native Compact Outlined Feed Card (`PulseTopicCard`):
 * - Transparent outlined container (`14.dp` corner radius, hairline border, zero shadow/fill)
 * - Single-pass measurement with zero `IntrinsicSize.Min` or scroll-time `animate*AsState`
 * - Crisp read/unread title hierarchy + bottom-right backgroundless semantic category indicator
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PulseTopicCard(
    topic: PulseTopicCardModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCategoryTag: Boolean = true,
    showEngagementMetrics: Boolean = true,
    resumeBadgeTextOverride: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 3.5.dp),
    selectedTopicId: Long? = null,
    isSelected: Boolean = selectedTopicId != null && selectedTopicId == topic.id,
    onBookmarkToggle: (() -> Unit)? = null,
    onAuthorClick: ((Long) -> Unit)? = null,
    onCategoryClick: ((String) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography
    val fontScale = NodePulseThemeTokens.fontScale
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val cardShape = RoundedCornerShape(14.dp)
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectedFillColor = MaterialTheme.colorScheme.primaryContainer.copy(
        alpha = if (isDark) 0.28f else 0.36f,
    )
    val cardBorderColor = if (isSelected) {
        primaryColor.copy(alpha = if (isDark) 0.72f else 0.56f)
    } else {
        ext.cardBorder
    }

    val titleColor = if (topic.isRead && !isSelected) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ext.readTitleAlpha)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val titleStyle = (if (topic.isRead && !isSelected) extraType.titleMediumRead else extraType.titleMediumUnread).copy(
        fontSize = (15.5f * fontScale).sp,
        lineHeight = (21.5f * fontScale).sp,
        fontWeight = if (topic.isRead && !isSelected) FontWeight.Normal else FontWeight.SemiBold,
    )
    val hasCategoryTag = showCategoryTag &&
        (topic.categorySlug.isNotBlank() || topic.categoryName.isNotBlank())
    val resumeBadgeText = resumeBadgeTextOverride?.takeIf { it.isNotBlank() }
        ?: topic.lastReadFloor?.takeIf { it > 0 }?.let { "读至 #$it 楼" }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulsePressScale(interactionSource = interactionSource, pressedScale = 0.985f)
                .clip(cardShape)
                .then(
                    if (isSelected) {
                        Modifier.background(selectedFillColor)
                    } else {
                        Modifier
                    },
                )
                .border(
                    width = if (isSelected) 1.4.dp else 0.85.dp,
                    color = cardBorderColor,
                    shape = cardShape,
                )
                .drawWithContent {
                    drawContent()
                    if (isSelected) {
                        val barW = 3.dp.toPx()
                        val vPad = 11.dp.toPx()
                        if (size.height > vPad * 2f) {
                            val r = 2.dp.toPx()
                            drawRoundRect(
                                color = primaryColor,
                                topLeft = Offset(0f, vPad),
                                size = Size(barW, size.height - vPad * 2f),
                                cornerRadius = CornerRadius(r, r),
                            )
                        }
                    }
                }
                .semantics(mergeDescendants = true) {
                    selected = isSelected
                    stateDescription = if (topic.isRead) "已读" else "未读"
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            // ── Row 1: Compact Author + Time (Bookmark rendered as 48dp sibling at TopEnd) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canClickAuthor = onAuthorClick != null &&
                    ((topic.authorId != null && topic.authorId > 0L) || topic.authorName.isNotBlank())
                val resolvedAuthorId = topic.authorId ?: 0L

                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(
                            if (canClickAuthor) {
                                Modifier
                                    .heightIn(min = 32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "查看用户主页",
                                        onClick = { onAuthorClick?.invoke(resolvedAuthorId) },
                                    )
                                    .padding(end = 4.dp)
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseAvatar(
                        avatarUrl = topic.authorAvatarUrl,
                        username = topic.authorName,
                        size = 24.dp,
                        rankLevel = topic.authorLevel,
                        ringWidth = if (topic.authorLevel != null) 1.4.dp else 0.85.dp,
                        onClick = null,
                    )

                    Spacer(modifier = Modifier.width(7.dp))

                    Text(
                        text = topic.authorName.ifBlank { "匿名用户" },
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = (13f * fontScale).sp,
                            lineHeight = (17f * fontScale).sp,
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (topic.lastActivityIso.isNotBlank()) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    RelativeTimeText(isoTimestamp = topic.lastActivityIso)
                }

                Spacer(modifier = Modifier.weight(1f))

                if (onBookmarkToggle != null) {
                    // Reserve horizontal clearance for the 48dp sibling IconButton at TopEnd
                    Spacer(modifier = Modifier.width(34.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Row 2: Editorial Title (Unread SemiBold Ink vs Read Normal Muted) ──
            Text(
                text = topic.title,
                style = titleStyle,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(7.dp))

            // ── Row 3: Telemetry Footer + Floor Resume + Plain Category Tag ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (showEngagementMetrics) {
                        HeatCommentPill(
                            replyCount = topic.replyCount,
                            viewCount = topic.viewCount,
                            isHotOverride = topic.isHot,
                            compact = true,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )

                        if (topic.viewCount > 0) {
                            Row(
                                modifier = Modifier.align(Alignment.CenterVertically),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = formatCompactCount(topic.viewCount),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = (11.5f * fontScale).sp,
                                        fontWeight = FontWeight.Medium,
                                    ).withTabularNumbers(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else if (hasCategoryTag) {
                        PulseCardCategoryTag(
                            categorySlug = topic.categorySlug,
                            categoryName = topic.categoryName,
                            isRead = topic.isRead,
                            onCategoryClick = onCategoryClick,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }

                    if (topic.isPinned) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .heightIn(min = 20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(10.5.dp),
                            )
                            Text(
                                text = "置顶",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if ((topic.requiredViewLevel ?: 0) > 0) {
                        AccessLockChip(
                            requiredLevel = topic.requiredViewLevel ?: 0,
                            compact = true,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                val showRightCategoryTag = showEngagementMetrics && hasCategoryTag
                if (resumeBadgeText != null || showRightCategoryTag) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (resumeBadgeText != null) {
                            val resumeShape = RoundedCornerShape(6.dp)
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 20.dp)
                                    .clip(resumeShape)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(
                                            alpha = if (isDark) 0.40f else 0.58f,
                                        ),
                                    )
                                    .border(
                                        width = 0.7.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        shape = resumeShape,
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = resumeBadgeText,
                                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (showRightCategoryTag) {
                            PulseCardCategoryTag(
                                categorySlug = topic.categorySlug,
                                categoryName = topic.categoryName,
                                isRead = topic.isRead,
                                onCategoryClick = onCategoryClick,
                            )
                        }
                    }
                }
            }
        }

        if (onBookmarkToggle != null) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onBookmarkToggle()
                },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = if (topic.isBookmarked) {
                        Icons.Rounded.Bookmark
                    } else {
                        Icons.Rounded.BookmarkBorder
                    },
                    contentDescription = if (topic.isBookmarked) "取消收藏" else "收藏帖子",
                    tint = if (topic.isBookmarked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PulseCardCategoryTag(
    categorySlug: String,
    categoryName: String,
    isRead: Boolean,
    onCategoryClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fontScale = NodePulseThemeTokens.fontScale
    val categorySpec = NodePulseThemeTokens.categorySpec(categorySlug)
    val categoryLabel = categoryName.ifBlank { categorySpec.defaultTitle }
    val categoryShape = RoundedCornerShape(6.dp)
    val isClickable = onCategoryClick != null && categorySlug.isNotBlank()

    Row(
        modifier = modifier
            .then(if (isClickable) Modifier.heightIn(min = 28.dp) else Modifier)
            .clip(categoryShape)
            .then(
                if (isClickable) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "筛选$categoryLabel",
                        onClick = { onCategoryClick?.invoke(categorySlug) },
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (isClickable) 4.dp else 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(
                    categorySpec.spineColor.copy(
                        alpha = if (isRead) 0.55f else 0.88f,
                    ),
                ),
        )
        Text(
            text = categoryLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = (11.5f * fontScale).sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
            ),
            color = blendColor(
                source = MaterialTheme.colorScheme.onSurfaceVariant,
                target = categorySpec.contentColor,
                fraction = if (isRead) 0.25f else 0.55f,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Diagonal shimmer skeleton placeholder matching compact outlined `PulseTopicCard` geometry.
 */
@Composable
fun PulseTopicCardSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 3.5.dp),
) {
    val ext = NodePulseThemeTokens.extendedColors
    val cardShape = RoundedCornerShape(14.dp)

    ProvideSharedPulseShimmer {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(contentPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .border(
                        width = 0.85.dp,
                        color = ext.cardBorder,
                        shape = cardShape,
                    )
                    .padding(horizontal = 13.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseShimmerBox(
                        modifier = Modifier.size(24.dp),
                        shape = CircleShape,
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    PulseShimmerBox(
                        modifier = Modifier
                            .width(88.dp)
                            .height(12.dp),
                        shape = RoundedCornerShape(4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    PulseShimmerBox(
                        modifier = Modifier.size(16.dp),
                        shape = RoundedCornerShape(4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                PulseShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(15.dp),
                    shape = RoundedCornerShape(4.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                PulseShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .height(15.dp),
                    shape = RoundedCornerShape(4.dp),
                )
                Spacer(modifier = Modifier.height(7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PulseShimmerBox(
                        modifier = Modifier
                            .width(48.dp)
                            .height(20.dp),
                        shape = CircleShape,
                    )
                    PulseShimmerBox(
                        modifier = Modifier
                            .width(40.dp)
                            .height(14.dp),
                        shape = RoundedCornerShape(4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    PulseShimmerBox(
                        modifier = Modifier
                            .width(38.dp)
                            .height(13.dp),
                        shape = RoundedCornerShape(4.dp),
                    )
                }
            }
        }
    }
}
