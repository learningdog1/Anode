package com.nodeseek.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers

/**
 * Top-level destinations in Anode's 3-tab Bottom Navigation Bar.
 */
enum class PulseNavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    EXPLORE(
        label = "首页",
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    FEATURE_HUB(
        label = "功能栏",
        selectedIcon = Icons.Rounded.GridView,
        unselectedIcon = Icons.Outlined.GridView,
    ),
    PULSE_HUB(
        label = "我的",
        selectedIcon = Icons.Rounded.Person,
        unselectedIcon = Icons.Outlined.Person,
    ),
}

/**
 * Shared tonal gradient brush for active top-level navigation indicators in both
 * [FloatingPillDock] and `PulseNavigationRail`.
 */
@Composable
fun pulseNavActiveIndicatorBrush(isDark: Boolean = NodePulseThemeTokens.isDark): Brush =
    Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.82f else 0.96f),
            blendColor(
                source = MaterialTheme.colorScheme.primaryContainer,
                target = MaterialTheme.colorScheme.secondaryContainer,
                fraction = 0.38f,
            ).copy(alpha = if (isDark) 0.78f else 0.92f),
        ),
    )

/**
 * Shared icon + unread badge / check-in dot rendering for top-level [PulseNavTab] items.
 */
@Composable
fun PulseNavTabIconWithBadge(
    tab: PulseNavTab,
    isSelected: Boolean,
    contentColor: Color,
    cutoutBorderColor: Color,
    unreadNotificationCount: Int = 0,
    hasPendingCheckIn: Boolean = false,
    iconSize: Dp = 20.dp,
    containerSize: Dp = 20.dp,
    iconScale: Float = 1f,
    iconScaleProvider: (() -> Float)? = null,
    includeIconContentDescription: Boolean = true,
) {
    val badgeBrush = if (tab == PulseNavTab.PULSE_HUB) {
        Brush.horizontalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.error,
                blendColor(
                    source = MaterialTheme.colorScheme.error,
                    target = MaterialTheme.colorScheme.tertiary,
                    fraction = 0.28f,
                ),
            ),
        )
    } else {
        null
    }
    CornerCountBadgeBox(
        count = if (tab == PulseNavTab.PULSE_HUB) unreadNotificationCount else 0,
        containerSize = containerSize,
        badgeOffset = DpOffset(x = 4.dp, y = (-7).dp),
        containerBrush = badgeBrush,
        contentColor = MaterialTheme.colorScheme.onError,
        cutoutBorderColor = cutoutBorderColor,
        cutoutBorderWidth = 1.dp,
        badgeMinHeight = 18.dp,
        showIndicatorDotWhenZero = tab == PulseNavTab.PULSE_HUB && hasPendingCheckIn,
        indicatorDotBrush = PulseBrushes.drumstickGoldGradient(),
        indicatorDotSize = 8.dp,
        indicatorDotOffset = DpOffset(x = 3.dp, y = (-2).dp),
    ) {
        Icon(
            imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
            contentDescription = if (includeIconContentDescription) tab.label else null,
            tint = contentColor,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    val resolvedScale = iconScaleProvider?.invoke() ?: iconScale
                    scaleX = resolvedScale
                    scaleY = resolvedScale
                },
        )
    }
}

/**
 * Edge-to-edge Floating Glass Pill Dock (`首页` / `功能栏` / `我的`).
 * Features a translucent beveled glass capsule, spring-driven gradient pill indicator,
 * subtle icon bounce, and crisp unread / check-in telemetry badges.
 */
@Composable
fun FloatingPillDock(
    selectedTab: PulseNavTab,
    onSelectTab: (PulseNavTab) -> Unit,
    modifier: Modifier = Modifier,
    unreadNotificationCount: Int = 0,
    hasPendingCheckIn: Boolean = false,
    onReselectTab: ((PulseNavTab) -> Unit)? = null,
) {
    val tabs = PulseNavTab.entries
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val haptics = LocalHapticFeedback.current

    val glassFill = if (isDark) {
        ext.glassSurface.copy(alpha = 0.92f)
    } else {
        ext.cardSurfaceElevated.copy(alpha = 0.94f)
    }
    val rimBorderBrush = Brush.verticalGradient(
        colors = listOf(
            ext.cardHighlightRim.copy(alpha = if (isDark) 0.22f else 0.95f),
            ext.glassBorder,
        ),
    )
    val indicatorBrush = pulseNavActiveIndicatorBrush(isDark = isDark)

    Box(
        modifier = modifier
            .height(62.dp)
            .then(
                if (!isDark) {
                    Modifier.shadow(
                        elevation = PulseElevation.FloatingBar,
                        shape = PulseArchitecturalShapes.Pill,
                        clip = false,
                        ambientColor = ext.softShadowColor,
                        spotColor = ext.softShadowColor,
                    )
                } else {
                    Modifier.shadow(
                        elevation = PulseElevation.CardElevated,
                        shape = PulseArchitecturalShapes.Pill,
                        clip = false,
                        ambientColor = ext.softShadowColor,
                        spotColor = ext.softShadowColor,
                    )
                },
            )
            .clip(PulseArchitecturalShapes.Pill)
            .background(glassFill)
            .background(PulseBrushes.glassHighlightBrush(isDark))
            .border(
                width = 1.dp,
                brush = rimBorderBrush,
                shape = PulseArchitecturalShapes.Pill,
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .padding(horizontal = 5.dp, vertical = 5.dp)
                .fillMaxHeight()
                .fillMaxWidth(),
        ) {
            val tabWidth = maxWidth / tabs.size
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * selectedIndex,
                animationSpec = NodePulseMotion.spatialDpSpring(),
                label = "navPillOffset",
            )

            // Sliding tonal-gradient active pill indicator
            Box(
                modifier = Modifier
                    .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .then(
                        if (!isDark) {
                            Modifier.shadow(
                                elevation = PulseElevation.Subtle,
                                shape = PulseArchitecturalShapes.Pill,
                                clip = false,
                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(PulseArchitecturalShapes.Pill)
                    .background(indicatorBrush)
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.36f else 0.18f),
                        shape = PulseArchitecturalShapes.Pill,
                    ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val isSelected = tab == selectedTab
                    val interactionSource = remember(tab) { MutableInteractionSource() }
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        label = "navTabColor_${tab.name}",
                    )
                    val iconScaleState = animateFloatAsState(
                        targetValue = if (isSelected) 1.07f else 1.0f,
                        animationSpec = NodePulseMotion.microBounceSpring(),
                        label = "navTabScale_${tab.name}",
                    )
                    val tabDescription = when {
                        tab == PulseNavTab.PULSE_HUB && unreadNotificationCount > 0 ->
                            "${tab.label}，${unreadNotificationCount} 条未读消息"
                        tab == PulseNavTab.PULSE_HUB && hasPendingCheckIn ->
                            "${tab.label}，今日待签到"
                        else -> tab.label
                    }

                    Box(
                        modifier = Modifier
                            .width(tabWidth)
                            .fillMaxHeight()
                            .pulsePressScale(
                                interactionSource = interactionSource,
                                pressedScale = NodePulseMotion.chipPressScale,
                            )
                            .clip(PulseArchitecturalShapes.Pill)
                            .semantics(mergeDescendants = true) {
                                selected = isSelected
                                contentDescription = tabDescription
                            }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (isSelected) {
                                        onReselectTab?.invoke(tab)
                                    } else {
                                        onSelectTab(tab)
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PulseNavTabIconWithBadge(
                                tab = tab,
                                isSelected = isSelected,
                                contentColor = contentColor,
                                cutoutBorderColor = glassFill,
                                unreadNotificationCount = unreadNotificationCount,
                                hasPendingCheckIn = hasPendingCheckIn,
                                iconSize = 20.dp,
                                containerSize = 20.dp,
                                iconScaleProvider = { iconScaleState.value },
                                includeIconContentDescription = false,
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = contentColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Contextual Gradient Collapsing Squircle FAB (`发帖`) paired alongside the Floating Glass Pill Dock.
 */
@Composable
fun CollapsingSquircleFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    label: String = "发帖",
    icon: ImageVector = Icons.Rounded.Edit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val rimBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (isDark) 0.34f else 0.44f),
            Color.White.copy(alpha = 0.08f),
        ),
    )

    Box(
        modifier = modifier
            .height(56.dp)
            .defaultMinSize(minWidth = 56.dp)
            .pulsePressScale(
                interactionSource = interactionSource,
                pressedScale = NodePulseMotion.chipPressScale,
            )
            .shadow(
                elevation = if (isDark) 6.dp else 10.dp,
                shape = PulseArchitecturalShapes.SquircleFab,
                clip = false,
                ambientColor = ext.brandGradientStart.copy(alpha = if (isDark) 0.36f else 0.42f),
                spotColor = ext.brandGradientStart.copy(alpha = if (isDark) 0.36f else 0.42f),
            )
            .clip(PulseArchitecturalShapes.SquircleFab)
            .background(PulseBrushes.brandGradient(horizontal = false))
            .background(PulseBrushes.glassHighlightBrush(isDark))
            .border(
                width = 1.dp,
                brush = rimBrush,
                shape = PulseArchitecturalShapes.SquircleFab,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .padding(horizontal = if (expanded) 16.dp else 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = if (expanded) null else label,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * Floating Glass Pill Dock + Contextual Collapsing Squircle FAB overlaid above a soft edge fade scrim.
 */
@Composable
fun PulseBottomDockOverlay(
    selectedTab: PulseNavTab,
    onSelectTab: (PulseNavTab) -> Unit,
    onFabClick: () -> Unit,
    modifier: Modifier = Modifier,
    showFab: Boolean = selectedTab == PulseNavTab.EXPLORE,
    fabExpanded: Boolean = true,
    unreadNotificationCount: Int = 0,
    hasPendingCheckIn: Boolean = false,
    onReselectTab: ((PulseNavTab) -> Unit)? = null,
) {
    val bgColor = MaterialTheme.colorScheme.background
    val bottomEdgeFade = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.42f to bgColor.copy(alpha = 0.68f),
            1.0f to bgColor.copy(alpha = 0.94f),
        ),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bottomEdgeFade),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FloatingPillDock(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                unreadNotificationCount = unreadNotificationCount,
                hasPendingCheckIn = hasPendingCheckIn,
                onReselectTab = onReselectTab,
                modifier = Modifier.weight(1f),
            )

            AnimatedVisibility(
                visible = showFab,
                enter = scaleIn(
                    initialScale = 0.8f,
                    animationSpec = NodePulseMotion.microBounceSpring(),
                ) + expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = scaleOut(targetScale = 0.8f) +
                    shrinkHorizontally(shrinkTowards = Alignment.Start) +
                    fadeOut(),
            ) {
                CollapsingSquircleFab(
                    onClick = onFabClick,
                    expanded = fabExpanded,
                )
            }
        }
    }
}

@Immutable
data class SortSegmentItem<T>(
    val value: T,
    val label: String,
)

/**
 * Animated Sort Segmented Switcher with layered track, spring indicator, and subtle press scale.
 */
@Composable
fun <T> SortSegmentedSwitcher(
    options: List<SortSegmentItem<T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val selectedIndex = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors

    BoxWithConstraints(
        modifier = modifier
            .height(44.dp)
            .clip(PulseArchitecturalShapes.Pill)
            .background(
                if (isDark) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .border(
                width = 1.dp,
                color = ext.cardBorder,
                shape = PulseArchitecturalShapes.Pill,
            )
            .padding(3.dp),
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = NodePulseMotion.spatialDpSpring(),
            label = "sortIndicatorOffset",
        )

        val indicatorBrush = if (isDark) {
            Brush.horizontalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    blendColor(
                        source = MaterialTheme.colorScheme.primaryContainer,
                        target = MaterialTheme.colorScheme.secondaryContainer,
                        fraction = 0.35f,
                    ).copy(alpha = 0.82f),
                ),
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    ext.cardSurfaceElevated,
                    blendColor(ext.cardSurfaceElevated, MaterialTheme.colorScheme.primaryContainer, 0.14f),
                ),
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .width(segmentWidth)
                .fillMaxHeight()
                .then(
                    if (!isDark) {
                        Modifier.shadow(
                            elevation = PulseElevation.Card,
                            shape = PulseArchitecturalShapes.Pill,
                            clip = false,
                            ambientColor = ext.softShadowColor,
                            spotColor = ext.softShadowColor,
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(PulseArchitecturalShapes.Pill)
                .background(indicatorBrush)
                .border(
                    width = 0.8.dp,
                    color = if (isDark) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                    } else {
                        ext.cardBorder.copy(alpha = 0.75f)
                    },
                    shape = PulseArchitecturalShapes.Pill,
                ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { item ->
                val isSelected = item.value == selectedValue
                val interactionSource = remember(item.value) { MutableInteractionSource() }
                val textColor by animateColorAsState(
                    targetValue = when {
                        isSelected && isDark -> MaterialTheme.colorScheme.onPrimaryContainer
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                    label = "sortSegmentTextColor",
                )
                Box(
                    modifier = Modifier
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .pulsePressScale(
                            interactionSource = interactionSource,
                            pressedScale = NodePulseMotion.chipPressScale,
                        )
                        .clip(PulseArchitecturalShapes.Pill)
                        .semantics { selected = isSelected }
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(item.value) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Immutable
data class PinnedTickerTopic(
    val id: Long,
    val title: String,
    val categorySlug: String = "",
    val categoryName: String = "",
)

/**
 * Layered Collapsible Pinned Ticker (`📌 置顶 · N 条公告`) with soft tonal accent surface,
 * gradient pin badge, and smooth expandable drawer.
 */
@Composable
fun CollapsiblePinnedTicker(
    pinnedTopics: List<PinnedTickerTopic>,
    onTopicClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pinnedTopics.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "pinnedChevronRotation",
    )
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val shape = RoundedCornerShape(13.dp)

    val pinBadgeBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.24f else 0.13f),
            MaterialTheme.colorScheme.secondary.copy(alpha = if (isDark) 0.18f else 0.09f),
        ),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = shape,
                containerColor = Color.Transparent,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.None,
                accentColor = null,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .clickable(
                    role = Role.Button,
                    onClick = {
                        if (pinnedTopics.size == 1) {
                            onTopicClick(pinnedTopics.first().id)
                        } else {
                            expanded = !expanded
                        }
                    },
                )
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(pinBadgeBrush)
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.34f else 0.20f),
                        shape = PulseArchitecturalShapes.SmallChip,
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "置顶",
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = pinnedTopics.first().title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (pinnedTopics.size > 1) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
                        .padding(start = 7.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "${pinnedTopics.size} 条",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起置顶" else "展开置顶",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = chevronRotation },
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded && pinnedTopics.size > 1,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                pinnedTopics.forEach { pinned ->
                    PulseGradientDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 44.dp)
                            .clickable { onTopicClick(pinned.id) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (pinned.categorySlug.isNotBlank() || pinned.categoryName.isNotBlank()) {
                            CategoryMicroTag(
                                slug = pinned.categorySlug,
                                labelOverride = pinned.categoryName,
                                showIcon = false,
                                compact = true,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                            )
                        }
                        Text(
                            text = pinned.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shared floating capsule snackbar matching Anode's layered glass surfaces across shells.
 */
@Composable
fun PulseSnackbarPill(
    data: SnackbarData,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .border(
                width = 1.dp,
                color = if (isDark) ext.glassBorder else Color.White.copy(alpha = 0.14f),
                shape = PulseArchitecturalShapes.GlassBar,
            ),
        shape = PulseArchitecturalShapes.GlassBar,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = PulseElevation.FloatingBar,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false),
            )
            val actionLabel = data.visuals.actionLabel
            if (actionLabel != null) {
                TextButton(
                    onClick = { data.performAction() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            }
        }
    }
}
