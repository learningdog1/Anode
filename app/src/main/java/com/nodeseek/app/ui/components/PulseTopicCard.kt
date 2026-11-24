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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.ui.theme.DEFAULT_HOME_CARD_FONT_SCALE
import com.nodeseek.app.ui.theme.DEFAULT_HOME_CARD_SCALE
import com.nodeseek.app.ui.theme.MAX_HOME_CARD_FONT_SCALE
import com.nodeseek.app.ui.theme.MAX_HOME_CARD_SCALE
import com.nodeseek.app.ui.theme.MIN_HOME_CARD_FONT_SCALE
import com.nodeseek.app.ui.theme.MIN_HOME_CARD_SCALE
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
 * Dual-axis geometry & typography metrics for [PulseTopicCard] driven independently by:
 * - [fontScale] (`0.60f..1.30f`, default `0.90f`): controls card text sizes & inline glyph caps
 * - [cardScale] (`0.60f..1.30f`, default `0.90f`): controls card paddings, inter-card spacing,
 *   row gaps, corner radius, and container compactness
 *
 * Cross-scale safety guards ensure extreme combinations (such as `60%` compact card + `130%` large
 * font) never clip text, overlap lines, or misalign the top-end bookmark icon.
 */
@Immutable
data class PulseTopicCardMetrics(
    val fontScale: Float,
    val cardScale: Float,
    val outerHorizontalPadding: Dp,
    val outerVerticalPadding: Dp,
    val cardCornerRadius: Dp,
    val innerHorizontalPadding: Dp,
    val innerVerticalPadding: Dp,
    val selectedBarVerticalPad: Dp,
    val headerToTitleSpacing: Dp,
    val titleToFooterSpacing: Dp,
    val avatarSize: Dp,
    val avatarRankRingWidth: Dp,
    val avatarNeutralRingWidth: Dp,
    val avatarToAuthorSpacing: Dp,
    val authorRowMinHeight: Dp,
    val authorFontSizeSp: Float,
    val authorLineHeightSp: Float,
    val metaSeparatorSpacing: Dp,
    val timeFontSizeSp: Float,
    val timeLineHeightSp: Float,
    val bookmarkIconSize: Dp,
    val bookmarkTouchWidth: Dp,
    val bookmarkTouchHeight: Dp,
    val bookmarkEndClearance: Dp,
    val titleFontSizeSp: Float,
    val titleLineHeightSp: Float,
    val footerPillMinHeight: Dp,
    val footerPillHorizontalPad: Dp,
    val footerPillVerticalPad: Dp,
    val footerPillIconGap: Dp,
    val footerBadgeCornerRadius: Dp,
    val footerHorizontalGap: Dp,
    val footerVerticalGap: Dp,
    val footerIconSize: Dp,
    val viewIconSize: Dp,
    val categoryDotSize: Dp,
    val footerMetricFontSizeSp: Float,
    val footerMetricLineHeightSp: Float,
    val footerPillFontSizeSp: Float,
    val footerPillLineHeightSp: Float,
) {
    val outerContentPadding: PaddingValues
        get() = PaddingValues(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding)

    /**
     * Approximate total vertical footprint of a single card (including outer vertical padding)
     * at standard system font scale (`1.0f`) for layout density checks and unit tests.
     */
    fun estimatedHeightDp(titleLines: Int = 2): Float {
        val lines = titleLines.coerceIn(1, 2)
        return outerVerticalPadding.value * 2f +
            innerVerticalPadding.value * 2f +
            authorRowMinHeight.value +
            headerToTitleSpacing.value +
            titleLineHeightSp * lines +
            titleToFooterSpacing.value +
            footerPillMinHeight.value
    }
}

/**
 * Resolves independent typography ([fontScale]) and card compaction ([cardScale]) metrics for
 * [PulseTopicCard] (`0.60f..1.30f`, default `0.90f` for both).
 */
fun resolvePulseTopicCardMetrics(
    fontScale: Float = DEFAULT_HOME_CARD_FONT_SCALE,
    cardScale: Float = DEFAULT_HOME_CARD_SCALE,
): PulseTopicCardMetrics {
    val f = fontScale.coerceIn(MIN_HOME_CARD_FONT_SCALE, MAX_HOME_CARD_FONT_SCALE)
    val c = cardScale.coerceIn(MIN_HOME_CARD_SCALE, MAX_HOME_CARD_SCALE)
    val inlineGlyphScale = (0.80f * f + 0.20f * c).coerceIn(MIN_HOME_CARD_FONT_SCALE, MAX_HOME_CARD_FONT_SCALE)
    val bookmarkVisualScale = (0.35f * f + 0.65f * c).coerceIn(MIN_HOME_CARD_SCALE, MAX_HOME_CARD_SCALE)

    // Typography axis (driven purely by fontScale)
    val titleFontSizeSp = interpolateCardMetric(f, at60 = 10.0f, at75 = 12.2f, at90 = 14.0f, at100 = 15.5f, at130 = 20.1f)
    val titleLineHeightSp = interpolateCardMetric(f, at60 = 13.5f, at75 = 16.4f, at90 = 19.3f, at100 = 21.5f, at130 = 27.9f)
    val authorFontSizeSp = interpolateCardMetric(f, at60 = 9.2f, at75 = 10.8f, at90 = 12.0f, at100 = 13.0f, at130 = 16.0f)
    val authorLineHeightSp = interpolateCardMetric(f, at60 = 12.0f, at75 = 14.0f, at90 = 15.6f, at100 = 17.0f, at130 = 21.0f)
    val timeFontSizeSp = interpolateCardMetric(f, at60 = 8.6f, at75 = 10.0f, at90 = 11.2f, at100 = 12.0f, at130 = 14.8f)
    val timeLineHeightSp = interpolateCardMetric(f, at60 = 11.2f, at75 = 13.0f, at90 = 14.6f, at100 = 16.0f, at130 = 19.6f)
    val footerMetricFontSizeSp = interpolateCardMetric(f, at60 = 8.4f, at75 = 9.5f, at90 = 10.7f, at100 = 11.5f, at130 = 14.0f)
    val footerMetricLineHeightSp = interpolateCardMetric(f, at60 = 10.8f, at75 = 12.0f, at90 = 13.5f, at100 = 14.5f, at130 = 17.8f)
    val footerPillFontSizeSp = interpolateCardMetric(f, at60 = 8.2f, at75 = 9.2f, at90 = 10.4f, at100 = 11.0f, at130 = 13.5f)
    val footerPillLineHeightSp = interpolateCardMetric(f, at60 = 10.5f, at75 = 11.6f, at90 = 13.1f, at100 = 14.0f, at130 = 17.2f)

    // Card size / compaction axis (driven by cardScale with cross-scale floor guards)
    val outerVertical = interpolateCardMetric(c, at60 = 1.0f, at75 = 2.0f, at90 = 3.0f, at100 = 3.5f, at130 = 4.5f).dp
    val cardCorner = interpolateCardMetric(c, at60 = 8.5f, at75 = 11.0f, at90 = 13.0f, at100 = 14.0f, at130 = 16.0f).dp
    val innerHorizontal = interpolateCardMetric(c, at60 = 8.5f, at75 = 10.5f, at90 = 12.0f, at100 = 13.0f, at130 = 15.0f).dp
    val innerVertical = interpolateCardMetric(c, at60 = 3.5f, at75 = 6.0f, at90 = 8.5f, at100 = 10.0f, at130 = 13.0f).dp

    val baseAvatarDp = interpolateCardMetric(c, at60 = 15.0f, at75 = 18.0f, at90 = 21.5f, at100 = 24.0f, at130 = 28.0f)
    val resolvedAvatarDp = maxOf(baseAvatarDp, authorFontSizeSp * 1.15f)
    val baseAuthorRowMinHeightDp = interpolateCardMetric(c, at60 = 15.5f, at75 = 19.0f, at90 = 22.5f, at100 = 25.0f, at130 = 30.0f)
    val authorRowMinHeight = maxOf(baseAuthorRowMinHeightDp, resolvedAvatarDp, authorLineHeightSp + 1.5f).dp

    val bookmarkIconSize = interpolateCardMetric(bookmarkVisualScale, at60 = 13.5f, at75 = 15.0f, at90 = 17.0f, at100 = 18.0f, at130 = 21.0f).dp
    val footerPillVerticalPadDp = interpolateCardMetric(c, at60 = 0.5f, at75 = 1.0f, at90 = 1.5f, at100 = 2.0f, at130 = 3.0f)
    val baseFooterPillMinHeightDp = interpolateCardMetric(c, at60 = 14.0f, at75 = 16.5f, at90 = 19.0f, at100 = 20.5f, at130 = 25.0f)
    val footerPillMinHeight = maxOf(
        baseFooterPillMinHeightDp,
        footerPillLineHeightSp + footerPillVerticalPadDp * 2f + 1.5f,
        footerMetricLineHeightSp + footerPillVerticalPadDp * 2f + 1.0f,
    ).dp

    return PulseTopicCardMetrics(
        fontScale = f,
        cardScale = c,
        outerHorizontalPadding = 14.dp,
        outerVerticalPadding = outerVertical,
        cardCornerRadius = cardCorner,
        innerHorizontalPadding = innerHorizontal,
        innerVerticalPadding = innerVertical,
        selectedBarVerticalPad = interpolateCardMetric(c, at60 = 5.0f, at75 = 7.5f, at90 = 9.75f, at100 = 11.0f, at130 = 14.0f).dp,
        headerToTitleSpacing = interpolateCardMetric(c, at60 = 1.5f, at75 = 3.0f, at90 = 4.5f, at100 = 6.0f, at130 = 8.0f).dp,
        titleToFooterSpacing = interpolateCardMetric(c, at60 = 2.0f, at75 = 4.0f, at90 = 5.5f, at100 = 7.0f, at130 = 9.5f).dp,
        avatarSize = resolvedAvatarDp.dp,
        avatarRankRingWidth = interpolateCardMetric(c, at60 = 0.95f, at75 = 1.1f, at90 = 1.3f, at100 = 1.4f, at130 = 1.6f).dp,
        avatarNeutralRingWidth = interpolateCardMetric(c, at60 = 0.7f, at75 = 0.75f, at90 = 0.8f, at100 = 0.85f, at130 = 0.95f).dp,
        avatarToAuthorSpacing = interpolateCardMetric(c, at60 = 4.5f, at75 = 5.5f, at90 = 6.5f, at100 = 7.0f, at130 = 8.5f).dp,
        authorRowMinHeight = authorRowMinHeight,
        authorFontSizeSp = authorFontSizeSp,
        authorLineHeightSp = authorLineHeightSp,
        metaSeparatorSpacing = interpolateCardMetric(c, at60 = 3.5f, at75 = 4.0f, at90 = 4.5f, at100 = 5.0f, at130 = 6.0f).dp,
        timeFontSizeSp = timeFontSizeSp,
        timeLineHeightSp = timeLineHeightSp,
        bookmarkIconSize = bookmarkIconSize,
        // Symmetrical width/height so the bookmark icon sits at the exact vertical center of Row 1
        // and matches the avatar's horizontal edge inset for any (fontScale, cardScale) pair.
        bookmarkTouchWidth = innerHorizontal * 2 + bookmarkIconSize,
        bookmarkTouchHeight = innerVertical * 2 + authorRowMinHeight,
        bookmarkEndClearance = bookmarkIconSize + 8.dp,
        titleFontSizeSp = titleFontSizeSp,
        titleLineHeightSp = titleLineHeightSp,
        footerPillMinHeight = footerPillMinHeight,
        footerPillHorizontalPad = interpolateCardMetric(c, at60 = 4.5f, at75 = 5.5f, at90 = 6.5f, at100 = 7.0f, at130 = 9.0f).dp,
        footerPillVerticalPad = footerPillVerticalPadDp.dp,
        footerPillIconGap = interpolateCardMetric(c, at60 = 2.0f, at75 = 2.5f, at90 = 3.0f, at100 = 3.0f, at130 = 4.0f).dp,
        footerBadgeCornerRadius = interpolateCardMetric(c, at60 = 4.5f, at75 = 5.0f, at90 = 5.75f, at100 = 6.0f, at130 = 7.0f).dp,
        footerHorizontalGap = interpolateCardMetric(c, at60 = 4.0f, at75 = 5.0f, at90 = 6.0f, at100 = 6.5f, at130 = 8.5f).dp,
        footerVerticalGap = interpolateCardMetric(c, at60 = 2.5f, at75 = 3.0f, at90 = 4.0f, at100 = 4.5f, at130 = 5.5f).dp,
        footerIconSize = interpolateCardMetric(inlineGlyphScale, at60 = 8.5f, at75 = 9.5f, at90 = 11.0f, at100 = 11.5f, at130 = 14.0f).dp,
        viewIconSize = interpolateCardMetric(inlineGlyphScale, at60 = 9.5f, at75 = 10.5f, at90 = 12.0f, at100 = 13.0f, at130 = 15.5f).dp,
        categoryDotSize = interpolateCardMetric(inlineGlyphScale, at60 = 3.5f, at75 = 4.0f, at90 = 4.5f, at100 = 5.0f, at130 = 6.0f).dp,
        footerMetricFontSizeSp = footerMetricFontSizeSp,
        footerMetricLineHeightSp = footerMetricLineHeightSp,
        footerPillFontSizeSp = footerPillFontSizeSp,
        footerPillLineHeightSp = footerPillLineHeightSp,
    )
}

private fun interpolateCardMetric(
    scale: Float,
    at60: Float,
    at75: Float,
    at90: Float,
    at100: Float,
    at130: Float,
): Float = when {
    scale <= 0.75f -> {
        val t = ((scale - MIN_HOME_CARD_SCALE) / (0.75f - MIN_HOME_CARD_SCALE)).coerceIn(0f, 1f)
        at60 + (at75 - at60) * t
    }
    scale <= 0.90f -> {
        val t = ((scale - 0.75f) / (0.90f - 0.75f)).coerceIn(0f, 1f)
        at75 + (at90 - at75) * t
    }
    scale <= 1.00f -> {
        val t = ((scale - 0.90f) / (1.00f - 0.90f)).coerceIn(0f, 1f)
        at90 + (at100 - at90) * t
    }
    else -> {
        val t = ((scale - 1.00f) / (MAX_HOME_CARD_SCALE - 1.00f)).coerceIn(0f, 1f)
        at100 + (at130 - at100) * t
    }
}

/**
 * Native Compact Outlined Feed Card (`PulseTopicCard`):
 * - Transparent outlined container (proportionally scaled corner radius, hairline border, zero shadow/fill)
 * - Single-pass measurement with zero `IntrinsicSize.Min` or scroll-time `animate*AsState`
 * - Crisp read/unread title hierarchy + bottom-right backgroundless semantic category indicator
 * - Supports independent [fontScale] (`0.60f..1.30f`) and [cardScale] (`0.60f..1.30f`) for homepage
 *   cards while defaulting strictly to `0.90f` on all other screens
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
    contentPadding: PaddingValues? = null,
    fontScale: Float = DEFAULT_HOME_CARD_FONT_SCALE,
    cardScale: Float = DEFAULT_HOME_CARD_SCALE,
    selectedTopicId: Long? = null,
    isSelected: Boolean = selectedTopicId != null && selectedTopicId == topic.id,
    onBookmarkToggle: (() -> Unit)? = null,
    onAuthorClick: ((Long) -> Unit)? = null,
    onCategoryClick: ((String) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography
    val metrics = remember(fontScale, cardScale) {
        resolvePulseTopicCardMetrics(fontScale = fontScale, cardScale = cardScale)
    }
    val resolvedContentPadding = contentPadding ?: metrics.outerContentPadding
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val cardShape = RoundedCornerShape(metrics.cardCornerRadius)
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
        fontSize = metrics.titleFontSizeSp.sp,
        lineHeight = metrics.titleLineHeightSp.sp,
        fontWeight = if (topic.isRead && !isSelected) FontWeight.Normal else FontWeight.SemiBold,
    )
    val authorTextStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = metrics.authorFontSizeSp.sp,
        lineHeight = metrics.authorLineHeightSp.sp,
    )
    val timeTextStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = metrics.timeFontSizeSp.sp,
        lineHeight = metrics.timeLineHeightSp.sp,
    )
    val footerPillTextStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = metrics.footerPillFontSizeSp.sp,
        lineHeight = metrics.footerPillLineHeightSp.sp,
    )
    val footerMetricTextStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = metrics.footerMetricFontSizeSp.sp,
        lineHeight = metrics.footerMetricLineHeightSp.sp,
        fontWeight = FontWeight.Medium,
    )

    val hasCategoryTag = showCategoryTag &&
        (topic.categorySlug.isNotBlank() || topic.categoryName.isNotBlank())
    val resumeBadgeText = resumeBadgeTextOverride?.takeIf { it.isNotBlank() }
        ?: topic.lastReadFloor?.takeIf { it > 0 }?.let { "读至 #$it 楼" }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(resolvedContentPadding),
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
                        val vPad = metrics.selectedBarVerticalPad.toPx()
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
                .padding(
                    horizontal = metrics.innerHorizontalPadding,
                    vertical = metrics.innerVerticalPadding,
                ),
        ) {
            // ── Row 1: Compact Author + Time (Bookmark rendered as aligned sibling at TopEnd) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = metrics.authorRowMinHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canClickAuthor = onAuthorClick != null &&
                    ((topic.authorId != null && topic.authorId > 0L) || topic.authorName.isNotBlank())
                val resolvedAuthorId = topic.authorId ?: 0L

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (canClickAuthor) {
                                    Modifier
                                        .heightIn(min = metrics.authorRowMinHeight)
                                        .clip(RoundedCornerShape(metrics.footerBadgeCornerRadius))
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = "查看用户主页",
                                            onClick = { onAuthorClick(resolvedAuthorId) },
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
                            size = metrics.avatarSize,
                            rankLevel = topic.authorLevel,
                            ringWidth = if (topic.authorLevel != null) {
                                metrics.avatarRankRingWidth
                            } else {
                                metrics.avatarNeutralRingWidth
                            },
                            onClick = null,
                        )

                        Spacer(modifier = Modifier.width(metrics.avatarToAuthorSpacing))

                        Text(
                            text = topic.authorName.ifBlank { "匿名用户" },
                            style = authorTextStyle,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (topic.lastActivityIso.isNotBlank()) {
                        Spacer(modifier = Modifier.width(metrics.metaSeparatorSpacing))
                        Text(
                            text = "·",
                            style = timeTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.width(metrics.metaSeparatorSpacing))
                        RelativeTimeText(
                            isoTimestamp = topic.lastActivityIso,
                            style = timeTextStyle,
                        )
                    }
                }

                if (onBookmarkToggle != null) {
                    Spacer(modifier = Modifier.width(metrics.bookmarkEndClearance))
                }
            }

            Spacer(modifier = Modifier.height(metrics.headerToTitleSpacing))

            // ── Row 2: Editorial Title (Unread SemiBold Ink vs Read Normal Muted) ──
            Text(
                text = topic.title,
                style = titleStyle,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(metrics.titleToFooterSpacing))

            // ── Row 3: Telemetry Footer + Floor Resume + Plain Category Tag ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(metrics.footerHorizontalGap),
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(metrics.footerHorizontalGap),
                    verticalArrangement = Arrangement.spacedBy(metrics.footerVerticalGap),
                ) {
                    if (showEngagementMetrics) {
                        HeatCommentPill(
                            replyCount = topic.replyCount,
                            viewCount = topic.viewCount,
                            isHotOverride = topic.isHot,
                            compact = true,
                            minHeight = metrics.footerPillMinHeight,
                            horizontalPadding = metrics.footerPillHorizontalPad,
                            verticalPadding = metrics.footerPillVerticalPad,
                            iconSize = metrics.footerIconSize,
                            iconTextGap = metrics.footerPillIconGap,
                            textStyle = footerPillTextStyle.withTabularNumbers(),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )

                        if (topic.viewCount > 0) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .heightIn(min = metrics.footerPillMinHeight),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(metrics.footerPillIconGap),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    modifier = Modifier.size(metrics.viewIconSize),
                                )
                                Text(
                                    text = formatCompactCount(topic.viewCount),
                                    style = footerMetricTextStyle.withTabularNumbers(),
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
                            metrics = metrics,
                            onCategoryClick = onCategoryClick,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }

                    if (topic.isPinned) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .heightIn(min = metrics.footerPillMinHeight)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f))
                                .padding(
                                    horizontal = metrics.footerPillHorizontalPad,
                                    vertical = metrics.footerPillVerticalPad,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(metrics.footerPillIconGap),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(metrics.footerIconSize),
                            )
                            Text(
                                text = "置顶",
                                style = footerPillTextStyle,
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
                            minHeight = metrics.footerPillMinHeight,
                            horizontalPadding = metrics.footerPillHorizontalPad,
                            verticalPadding = metrics.footerPillVerticalPad,
                            cornerRadius = metrics.footerBadgeCornerRadius,
                            iconSize = metrics.footerIconSize,
                            textStyle = footerPillTextStyle.withTabularNumbers(),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                val showRightCategoryTag = showEngagementMetrics && hasCategoryTag
                if (resumeBadgeText != null || showRightCategoryTag) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(metrics.footerHorizontalGap),
                    ) {
                        if (resumeBadgeText != null) {
                            val resumeShape = RoundedCornerShape(metrics.footerBadgeCornerRadius)
                            Row(
                                modifier = Modifier
                                    .heightIn(min = metrics.footerPillMinHeight)
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
                                    .padding(
                                        horizontal = metrics.footerPillHorizontalPad,
                                        vertical = metrics.footerPillVerticalPad,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = resumeBadgeText,
                                    style = footerPillTextStyle.withTabularNumbers(),
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
                                metrics = metrics,
                                onCategoryClick = onCategoryClick,
                            )
                        }
                    }
                }
            }
        }

        if (onBookmarkToggle != null) {
            val bookmarkInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(
                        width = metrics.bookmarkTouchWidth,
                        height = metrics.bookmarkTouchHeight,
                    )
                    .pulsePressScale(interactionSource = bookmarkInteraction, pressedScale = 0.88f)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = bookmarkInteraction,
                        indication = LocalIndication.current,
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
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    },
                    modifier = Modifier.size(metrics.bookmarkIconSize),
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
    metrics: PulseTopicCardMetrics,
    onCategoryClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val categorySpec = NodePulseThemeTokens.categorySpec(categorySlug)
    val categoryLabel = categoryName.ifBlank { categorySpec.defaultTitle }
    val categoryShape = RoundedCornerShape(metrics.footerBadgeCornerRadius)
    val isClickable = onCategoryClick != null && categorySlug.isNotBlank()

    Row(
        modifier = modifier
            .heightIn(min = metrics.footerPillMinHeight)
            .clip(categoryShape)
            .then(
                if (isClickable) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "筛选$categoryLabel",
                        onClick = { onCategoryClick(categorySlug) },
                    )
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = if (isClickable) 4.dp else 2.dp,
                vertical = metrics.footerPillVerticalPad,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.footerPillIconGap + 1.dp),
    ) {
        Box(
            modifier = Modifier
                .size(metrics.categoryDotSize)
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
                fontSize = metrics.footerMetricFontSizeSp.sp,
                lineHeight = metrics.footerMetricLineHeightSp.sp,
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
 * Diagonal shimmer skeleton placeholder matching compact outlined `PulseTopicCard` geometry
 * at the active [fontScale] and [cardScale].
 */
@Composable
fun PulseTopicCardSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    fontScale: Float = DEFAULT_HOME_CARD_FONT_SCALE,
    cardScale: Float = DEFAULT_HOME_CARD_SCALE,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val metrics = remember(fontScale, cardScale) {
        resolvePulseTopicCardMetrics(fontScale = fontScale, cardScale = cardScale)
    }
    val resolvedContentPadding = contentPadding ?: metrics.outerContentPadding
    val cardShape = RoundedCornerShape(metrics.cardCornerRadius)
    val titleBarHeight = (metrics.titleFontSizeSp * 0.96f).dp
    val titleLineGap = (metrics.titleLineHeightSp - metrics.titleFontSizeSp).coerceAtLeast(3.0f).dp

    ProvideSharedPulseShimmer {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(resolvedContentPadding),
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
                    .padding(
                        horizontal = metrics.innerHorizontalPadding,
                        vertical = metrics.innerVerticalPadding,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = metrics.authorRowMinHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseShimmerBox(
                        modifier = Modifier.size(metrics.avatarSize),
                        shape = CircleShape,
                    )
                    Spacer(modifier = Modifier.width(metrics.avatarToAuthorSpacing))
                    PulseShimmerBox(
                        modifier = Modifier
                            .width((84f * metrics.fontScale).dp)
                            .height(metrics.authorFontSizeSp.dp),
                        shape = RoundedCornerShape(4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    PulseShimmerBox(
                        modifier = Modifier.size(metrics.bookmarkIconSize),
                        shape = RoundedCornerShape(4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(metrics.headerToTitleSpacing))
                PulseShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(titleBarHeight),
                    shape = RoundedCornerShape(4.dp),
                )
                Spacer(modifier = Modifier.height(titleLineGap))
                PulseShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .height(titleBarHeight),
                    shape = RoundedCornerShape(4.dp),
                )
                Spacer(modifier = Modifier.height(metrics.titleToFooterSpacing))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = metrics.footerPillMinHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metrics.footerHorizontalGap),
                ) {
                    PulseShimmerBox(
                        modifier = Modifier
                            .width((46f * metrics.fontScale).dp)
                            .height(metrics.footerPillMinHeight),
                        shape = CircleShape,
                    )
                    PulseShimmerBox(
                        modifier = Modifier
                            .width((38f * metrics.fontScale).dp)
                            .height(metrics.footerMetricLineHeightSp.dp),
                        shape = RoundedCornerShape(4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    PulseShimmerBox(
                        modifier = Modifier
                            .width((36f * metrics.fontScale).dp)
                            .height(metrics.footerMetricLineHeightSp.dp),
                        shape = RoundedCornerShape(4.dp),
                    )
                }
            }
        }
    }
}
