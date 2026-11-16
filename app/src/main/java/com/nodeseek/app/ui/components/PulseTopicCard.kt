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
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.pulseCard
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
 * Native Layered Feed Card (`PulseTopicCard`):
 * - Floating editorial sheet with tonal tint, subtle light-mode ambient shadow, and top-lit hairline border
 * - Single-pass measurement with zero `IntrinsicSize.Min` or scroll-time `animate*AsState`
 * - Unread gradient category spine drawn via `drawWithContent` + subtle press scale feedback
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PulseTopicCard(
    topic: PulseTopicCardModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCategoryTag: Boolean = true,
    onBookmarkToggle: (() -> Unit)? = null,
    onAuthorClick: ((Long) -> Unit)? = null,
    onCategoryClick: ((String) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography
    val categorySpec = NodePulseThemeTokens.categorySpec(topic.categorySlug)
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    val titleColor = if (topic.isRead) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ext.readTitleAlpha)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val cardContainerColor = if (topic.isRead) {
        if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f)
    } else {
        ext.cardSurface
    }
    val spineBrush = remember(categorySpec.spineColor, categorySpec.gradientEndColor) {
        Brush.verticalGradient(
            colors = listOf(categorySpec.spineColor, categorySpec.gradientEndColor),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulsePressScale(interactionSource = interactionSource, pressedScale = 0.985f)
                .pulseCard(
                    shape = PulseArchitecturalShapes.EditorialCard,
                    containerColor = cardContainerColor,
                    elevation = if (topic.isRead) PulseElevation.Subtle else PulseElevation.Card,
                    accentColor = if (!topic.isRead) categorySpec.spineColor else null,
                )
                .semantics {
                    stateDescription = if (topic.isRead) "已读" else "未读"
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .drawWithContent {
                    drawContent()
                    if (!topic.isRead) {
                        val barW = 3.5.dp.toPx()
                        val vPad = 14.dp.toPx()
                        if (size.height > vPad * 2f) {
                            val r = 2.dp.toPx()
                            drawRoundRect(
                                brush = spineBrush,
                                topLeft = Offset(0f, vPad),
                                size = Size(barW, size.height - vPad * 2f),
                                cornerRadius = CornerRadius(r, r),
                            )
                        }
                    }
                }
                .padding(start = 16.dp, end = 14.dp, top = 13.dp, bottom = 12.dp),
        ) {
            // ── Row 1: Author + Lv Ring + Time + Category Tag + 1-Tap Bookmark ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canClickAuthor = onAuthorClick != null &&
                    ((topic.authorId != null && topic.authorId > 0L) || topic.authorName.isNotBlank())
                val resolvedAuthorId = topic.authorId ?: 0L

                PulseAvatar(
                    avatarUrl = topic.authorAvatarUrl,
                    username = topic.authorName,
                    size = 34.dp,
                    rankLevel = topic.authorLevel,
                    ringWidth = 1.6.dp,
                    onClick = if (canClickAuthor) {
                        { onAuthorClick?.invoke(resolvedAuthorId) }
                    } else {
                        null
                    },
                )

                Spacer(modifier = Modifier.width(9.dp))

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = topic.authorName.ifBlank { "匿名用户" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (canClickAuthor) {
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = "查看用户主页",
                                            onClick = { onAuthorClick?.invoke(resolvedAuthorId) },
                                        )
                                } else {
                                    Modifier
                                },
                            ),
                    )
                    if (topic.authorLevel != null) {
                        RankMicroBadge(level = topic.authorLevel)
                    }
                    if (topic.lastActivityIso.isNotBlank()) {
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        RelativeTimeText(isoTimestamp = topic.lastActivityIso)
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                if (showCategoryTag && (topic.categorySlug.isNotBlank() || topic.categoryName.isNotBlank())) {
                    CategoryMicroTag(
                        slug = topic.categorySlug,
                        labelOverride = topic.categoryName,
                        compact = true,
                        onClick = if (onCategoryClick != null) {
                            { onCategoryClick(topic.categorySlug) }
                        } else {
                            null
                        },
                    )
                }

                if (onBookmarkToggle != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (topic.isBookmarked) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.55f else 0.72f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.50f)
                                },
                            )
                            .clickable(
                                role = Role.Button,
                                onClickLabel = if (topic.isBookmarked) "取消收藏" else "收藏帖子",
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onBookmarkToggle()
                                },
                            ),
                        contentAlignment = Alignment.Center,
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
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(9.dp))

            // ── Row 2: Editorial Title (Unread SemiBold Ink vs Read Normal Muted) ──
            Text(
                text = topic.title,
                style = if (topic.isRead) extraType.titleMediumRead else extraType.titleMediumUnread,
                color = titleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── Row 3: Telemetry Footer (Heat/Comments, Views, Pin, Lock, Floor Resume) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HeatCommentPill(
                        replyCount = topic.replyCount,
                        viewCount = topic.viewCount,
                        isHotOverride = topic.isHot,
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = formatCompactCount(topic.viewCount),
                                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (topic.isPinned) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .height(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f))
                                .padding(horizontal = 6.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(11.dp),
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
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                if ((topic.lastReadFloor ?: 0) > 0) {
                    val resumeShape = RoundedCornerShape(8.dp)
                    Row(
                        modifier = Modifier
                            .clip(resumeShape)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = if (isDark) 0.45f else 0.65f,
                                ),
                            )
                            .border(
                                width = 0.75.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                shape = resumeShape,
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "读至 #${topic.lastReadFloor} 楼",
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Diagonal shimmer skeleton placeholder matching layered `PulseTopicCard` geometry.
 */
@Composable
fun PulseTopicCardSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(
                    shape = PulseArchitecturalShapes.EditorialCard,
                    elevation = PulseElevation.Subtle,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                PulseShimmerBox(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                )
                PulseShimmerBox(
                    modifier = Modifier
                        .width(96.dp)
                        .height(13.dp),
                    shape = RoundedCornerShape(4.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                PulseShimmerBox(
                    modifier = Modifier
                        .width(52.dp)
                        .height(22.dp),
                    shape = RoundedCornerShape(8.dp),
                )
            }
            PulseShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(17.dp),
                shape = RoundedCornerShape(5.dp),
            )
            PulseShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.64f)
                    .height(17.dp),
                shape = RoundedCornerShape(5.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PulseShimmerBox(
                    modifier = Modifier
                        .width(56.dp)
                        .height(22.dp),
                    shape = CircleShape,
                )
                PulseShimmerBox(
                    modifier = Modifier
                        .width(46.dp)
                        .height(22.dp),
                    shape = CircleShape,
                )
            }
        }
    }
}
