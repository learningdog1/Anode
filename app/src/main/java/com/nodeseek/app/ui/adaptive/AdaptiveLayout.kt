package com.nodeseek.app.ui.adaptive

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.nodeseek.app.ui.components.CollapsingSquircleFab
import com.nodeseek.app.ui.components.PulseNavTab
import com.nodeseek.app.ui.components.PulseNavTabIconWithBadge
import com.nodeseek.app.ui.components.PulseTopicCard
import com.nodeseek.app.ui.components.PulseTopicCardModel
import com.nodeseek.app.ui.components.pulseNavActiveIndicatorBrush
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.pulsePressScale
import kotlin.math.roundToInt

/**
 * Adaptive posture telemetry describing current window size classes and tablet/foldable
 * master-detail capabilities.
 */
@Immutable
data class PulseAdaptiveLayoutInfo(
    val widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    val heightSizeClass: WindowHeightSizeClass = WindowHeightSizeClass.Medium,
    val windowWidthDp: Dp = 412.dp,
) {
    /**
     * Enables dual-pane master-detail on unfolded book-style foldables (`Medium`, >= 600dp)
     * as well as tablets and landscape displays (`Expanded`, >= 840dp).
     */
    val isDualPane: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact && windowWidthDp >= 600.dp

    /**
     * Replaces the floating bottom pill dock with a start-edge vertical navigation rail
     * whenever the window supports wide/dual-pane navigation.
     */
    val useNavigationRail: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact && windowWidthDp >= 600.dp

    val isExpandedWidth: Boolean
        get() = isDualPane && (widthSizeClass == WindowWidthSizeClass.Expanded || windowWidthDp >= 840.dp)

    val listPaneWeight: Float
        get() = when {
            !isDualPane -> 1f
            isExpandedWidth -> 0.38f
            else -> 0.42f
        }

    val detailPaneWeight: Float
        get() = when {
            !isDualPane -> 1f
            isExpandedWidth -> 0.62f
            else -> 0.58f
        }
}

val LocalPulseAdaptiveLayout = compositionLocalOf { PulseAdaptiveLayoutInfo() }

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun calculatePulseAdaptiveLayoutInfo(
    windowWidthDp: Dp,
    windowHeightDp: Dp,
    explicitWindowSizeClass: WindowSizeClass? = null,
): PulseAdaptiveLayoutInfo {
    val safeWidth = windowWidthDp.coerceAtLeast(0.dp)
    val safeHeight = windowHeightDp.coerceAtLeast(0.dp)
    val resolvedSizeClass = explicitWindowSizeClass ?: WindowSizeClass.calculateFromSize(
        DpSize(width = safeWidth, height = safeHeight),
    )
    return PulseAdaptiveLayoutInfo(
        widthSizeClass = resolvedSizeClass.widthSizeClass,
        heightSizeClass = resolvedSizeClass.heightSizeClass,
        windowWidthDp = safeWidth,
    )
}

/**
 * Topic selection state for the right-hand reader pane in dual-pane mode.
 */
@Immutable
data class SelectedPaneTopic(
    val topicId: Long,
    val initialFloor: Int = 0,
    val titleHint: String = "",
) {
    companion object {
        val NullableSaver: Saver<SelectedPaneTopic?, Any> = listSaver(
            save = { topic ->
                if (topic == null) {
                    emptyList()
                } else {
                    listOf(topic.topicId, topic.initialFloor, topic.titleHint)
                }
            },
            restore = { saved ->
                if (saved.size < 3) {
                    null
                } else {
                    SelectedPaneTopic(
                        topicId = (saved[0] as Number).toLong(),
                        initialFloor = (saved[1] as Number).toInt(),
                        titleHint = saved[2] as String,
                    )
                }
            },
        )
    }
}

enum class DualPaneDestinationKind {
    EXPLORE_FEED,
    EXPLORE_OTHER_TAB,
    BOARDS_OR_SEARCH,
    HUB_COLLECTION,
    INBOX,
    PULSE_RADAR,
    TOPIC_DETAIL,
    OTHER,
}

fun DualPaneDestinationKind.isDualPaneListHost(): Boolean = when (this) {
    DualPaneDestinationKind.EXPLORE_FEED,
    DualPaneDestinationKind.BOARDS_OR_SEARCH,
    DualPaneDestinationKind.HUB_COLLECTION,
    DualPaneDestinationKind.INBOX,
    DualPaneDestinationKind.PULSE_RADAR -> true

    DualPaneDestinationKind.EXPLORE_OTHER_TAB,
    DualPaneDestinationKind.TOPIC_DETAIL,
    DualPaneDestinationKind.OTHER -> false
}

sealed interface AdaptivePostureTransition {
    data object None : AdaptivePostureTransition
    data object ClearPaneState : AdaptivePostureTransition
    data class PushTopicDetailAndClearPane(val topic: SelectedPaneTopic) : AdaptivePostureTransition
    data class PopTopicDetailToDualPane(
        val topic: SelectedPaneTopic,
        val switchToExploreTab: Boolean = false,
    ) : AdaptivePostureTransition
}

/**
 * Resolves fold (`true -> false`) and unfold (`false -> true`) navigation transitions so active
 * topic reading state carries smoothly across phone, unfolded foldable, and tablet postures.
 */
fun resolveAdaptivePostureTransition(
    wasDualPane: Boolean,
    isDualPane: Boolean,
    currentDestination: DualPaneDestinationKind,
    previousDestination: DualPaneDestinationKind?,
    selectedPaneTopic: SelectedPaneTopic?,
    currentTopicDetailArgs: SelectedPaneTopic?,
): AdaptivePostureTransition {
    if (wasDualPane == isDualPane) return AdaptivePostureTransition.None

    // Folding / shrinking from dual-pane to compact phone width
    if (wasDualPane && !isDualPane) {
        return if (selectedPaneTopic != null && currentDestination.isDualPaneListHost()) {
            AdaptivePostureTransition.PushTopicDetailAndClearPane(selectedPaneTopic)
        } else {
            AdaptivePostureTransition.ClearPaneState
        }
    }

    // Unfolding / expanding from compact phone width to dual-pane
    if (currentDestination == DualPaneDestinationKind.TOPIC_DETAIL && currentTopicDetailArgs != null) {
        return when {
            previousDestination?.isDualPaneListHost() == true -> {
                AdaptivePostureTransition.PopTopicDetailToDualPane(
                    topic = currentTopicDetailArgs,
                    switchToExploreTab = false,
                )
            }

            previousDestination == DualPaneDestinationKind.EXPLORE_OTHER_TAB -> {
                AdaptivePostureTransition.PopTopicDetailToDualPane(
                    topic = currentTopicDetailArgs,
                    switchToExploreTab = true,
                )
            }

            else -> AdaptivePostureTransition.None
        }
    }

    return AdaptivePostureTransition.None
}

/**
 * Master-Detail split scaffold that keeps [listPane] mounted and measured at its normal width even
 * when [isDetailMaximized] is active, preserving LazyList/Pager scroll state and ViewModels across
 * maximize/restore toggles.
 */
@Composable
fun PulseDualPaneScaffold(
    adaptiveLayout: PulseAdaptiveLayoutInfo,
    hasDetailContent: Boolean,
    isDetailMaximized: Boolean,
    modifier: Modifier = Modifier,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    emptyDetailPane: @Composable () -> Unit = { PulseEmptyDetailReaderPlaceholder() },
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val dividerColor = ext.cardBorder.copy(alpha = if (isDark) 0.85f else 0.78f)
    val showMaximized = isDetailMaximized && hasDetailContent

    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            Box(modifier = Modifier.fillMaxSize()) {
                listPane()
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(dividerColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                if (hasDetailContent) {
                    detailPane()
                } else {
                    emptyDetailPane()
                }
            }
        },
    ) { measurables, constraints ->
        val totalWidth = constraints.maxWidth
        val totalHeight = constraints.maxHeight
        val listMeasurable = measurables[0]
        val dividerMeasurable = measurables[1]
        val detailMeasurable = measurables[2]

        val dividerWidthPx = 1.dp.roundToPx().coerceAtMost(totalWidth)
        val availableForPanes = (totalWidth - dividerWidthPx).coerceAtLeast(0)
        val normalListWidthPx = (availableForPanes * adaptiveLayout.listPaneWeight)
            .roundToInt()
            .coerceIn(0, availableForPanes)

        val listPlaceable = listMeasurable.measure(
            Constraints.fixed(width = normalListWidthPx, height = totalHeight),
        )

        if (showMaximized) {
            val detailPlaceable = detailMeasurable.measure(
                Constraints.fixed(width = totalWidth, height = totalHeight),
            )
            layout(totalWidth, totalHeight) {
                // Deliberately omit listPlaceable placement while keeping it measured so scroll & pager state stay alive
                detailPlaceable.placeRelative(0, 0)
            }
        } else {
            val dividerPlaceable = dividerMeasurable.measure(
                Constraints.fixed(width = dividerWidthPx, height = totalHeight),
            )
            val detailWidthPx = (availableForPanes - normalListWidthPx).coerceAtLeast(0)
            val detailPlaceable = detailMeasurable.measure(
                Constraints.fixed(width = detailWidthPx, height = totalHeight),
            )
            layout(totalWidth, totalHeight) {
                listPlaceable.placeRelative(0, 0)
                dividerPlaceable.placeRelative(normalListWidthPx, 0)
                detailPlaceable.placeRelative(normalListWidthPx + dividerWidthPx, 0)
            }
        }
    }
}

/**
 * Start-edge vertical Navigation Rail replacing the floating bottom pill dock on unfolded
 * foldables and tablets (`useNavigationRail == true`).
 */
@Composable
fun PulseNavigationRail(
    selectedTab: PulseNavTab,
    onSelectTab: (PulseNavTab) -> Unit,
    onFabClick: () -> Unit,
    modifier: Modifier = Modifier,
    showFab: Boolean = selectedTab == PulseNavTab.EXPLORE,
    unreadNotificationCount: Int = 0,
    hasPendingCheckIn: Boolean = false,
    onReselectTab: ((PulseNavTab) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val haptics = LocalHapticFeedback.current

    val railSurface = if (isDark) {
        ext.glassSurface.copy(alpha = 0.94f)
    } else {
        ext.cardSurfaceElevated.copy(alpha = 0.96f)
    }
    val dividerColor = ext.cardBorder.copy(alpha = if (isDark) 0.85f else 0.78f)
    val indicatorBrush = pulseNavActiveIndicatorBrush(isDark = isDark)

    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(railSurface)
            .background(PulseBrushes.glassHighlightBrush(isDark)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                )
                .width(82.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Fixed-height header slot keeps tab positions steady when toggling FAB visibility
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showFab,
                    enter = scaleIn(
                        initialScale = 0.8f,
                        animationSpec = NodePulseMotion.microBounceSpring(),
                    ) + fadeIn(),
                    exit = scaleOut(targetScale = 0.8f) + fadeOut(),
                ) {
                    CollapsingSquircleFab(
                        onClick = onFabClick,
                        expanded = false,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulseNavTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    val interactionSource = remember(tab) { MutableInteractionSource() }
                    val itemShape = RoundedCornerShape(16.dp)
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        label = "railTabColor_${tab.name}",
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.07f else 1.0f,
                        animationSpec = NodePulseMotion.microBounceSpring(),
                        label = "railTabScale_${tab.name}",
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulsePressScale(
                                interactionSource = interactionSource,
                                pressedScale = NodePulseMotion.chipPressScale,
                            )
                            .then(
                                if (isSelected && !isDark) {
                                    Modifier.shadow(
                                        elevation = PulseElevation.Subtle,
                                        shape = itemShape,
                                        clip = false,
                                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clip(itemShape)
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .background(indicatorBrush)
                                        .border(
                                            width = 0.8.dp,
                                            color = MaterialTheme.colorScheme.primary.copy(
                                                alpha = if (isDark) 0.36f else 0.18f,
                                            ),
                                            shape = itemShape,
                                        )
                                } else {
                                    Modifier
                                },
                            )
                            .semantics { selected = isSelected }
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
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PulseNavTabIconWithBadge(
                            tab = tab,
                            isSelected = isSelected,
                            contentColor = contentColor,
                            cutoutBorderColor = railSurface,
                            unreadNotificationCount = unreadNotificationCount,
                            hasPendingCheckIn = hasPendingCheckIn,
                            iconSize = 21.dp,
                            containerSize = 22.dp,
                            iconScale = iconScale,
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(dividerColor),
        )
    }
}

/**
 * Editorial reader placeholder displayed in the right detail pane when no topic is selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PulseEmptyDetailReaderPlaceholder(
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .pulseHeroCard(
                    accentColor = primary,
                    shape = PulseArchitecturalShapes.HeroBento,
                    elevation = PulseElevation.CardElevated,
                )
                .padding(horizontal = 26.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(PulseBrushes.brandSoftWash(alpha = if (isDark) 0.24f else 0.14f))
                    .border(
                        width = 1.dp,
                        color = primary.copy(alpha = if (isDark) 0.36f else 0.22f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(30.dp),
                )
            }

            Text(
                text = "选择左侧主题开始阅读",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "点击列表中的任意帖子即可在此分栏沉浸阅读，左侧浏览位置保持不变，也可随时切换全屏展开模式。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseAccentPill(
                    text = "双栏同步浏览",
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                PulseAccentPill(
                    text = "支持全屏展开",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                PulseAccentPill(
                    text = "折叠无缝续读",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(
    name = "Unfolded Foldable · 673dp Dual-Pane",
    showBackground = true,
    widthDp = 673,
    heightDp = 841,
)
@Composable
private fun PulseAdaptiveDualPaneFoldablePreview() {
    AdaptiveShellPreviewContent(themeMode = PulseThemeMode.PAPER_LIGHT)
}

@Preview(
    name = "Tablet Landscape · 1024dp Dual-Pane",
    showBackground = true,
    widthDp = 1024,
    heightDp = 640,
)
@Composable
private fun PulseAdaptiveDualPaneTabletPreview() {
    AdaptiveShellPreviewContent(themeMode = PulseThemeMode.OBSIDIAN_DARK)
}

@Composable
private fun AdaptiveShellPreviewContent(themeMode: PulseThemeMode) {
    val sampleTopic = PulseTopicCardModel(
        id = 884210L,
        title = "【深度测评】东京 Tier-1 BGP 双栈小鸡首发实测：三网回程延迟与 Geekbench 6",
        categorySlug = "review",
        categoryName = "测评",
        authorId = 1024L,
        authorName = "latency_hunter",
        viewCount = 6840,
        replyCount = 142,
        lastActivityIso = "3m",
        isBookmarked = true,
        isHot = true,
    )
    NodePulseTheme(themeMode = themeMode) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val layout = calculatePulseAdaptiveLayoutInfo(maxWidth, maxHeight)
            Row(modifier = Modifier.fillMaxSize()) {
                if (layout.useNavigationRail) {
                    PulseNavigationRail(
                        selectedTab = PulseNavTab.EXPLORE,
                        onSelectTab = {},
                        onFabClick = {},
                        unreadNotificationCount = 3,
                        hasPendingCheckIn = true,
                    )
                }
                PulseDualPaneScaffold(
                    adaptiveLayout = layout,
                    hasDetailContent = false,
                    isDetailMaximized = false,
                    modifier = Modifier.weight(1f),
                    listPane = {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 12.dp),
                        ) {
                            PulseTopicCard(
                                topic = sampleTopic,
                                selectedTopicId = sampleTopic.id,
                                onClick = {},
                            )
                        }
                    },
                    detailPane = {},
                )
            }
        }
    }
}

