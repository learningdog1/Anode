package com.nodeseek.app.ui.fleet

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nodeseek.app.domain.fleet.FleetTrafficAndIdleAnalyzer
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.ServerIdleAssessment
import com.nodeseek.app.domain.fleet.ServerTrafficForecast
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.fleet.formatBytesBinary
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.TABULAR_NUMS_FEATURE
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Display density mode for the Probes tab in My Servers.
 */
enum class ProbeViewMode {
    CARDS,
    DENSE_LIST,
}

internal enum class ProbeMetricHeat {
    NORMAL,
    WARNING,
    HIGH,
}

internal object ProbeViewModeCodec {
    fun encode(mode: ProbeViewMode): String = mode.name

    fun decode(raw: String?): ProbeViewMode = when (raw?.trim()?.uppercase(Locale.US)) {
        ProbeViewMode.DENSE_LIST.name -> ProbeViewMode.DENSE_LIST
        else -> ProbeViewMode.CARDS
    }
}

private val Context.fleetProbeUiDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nodepulse_fleet_probe_ui_prefs",
)

private val KEY_PROBE_VIEW_MODE = stringPreferencesKey("probe_view_mode")

@Composable
internal fun rememberPersistedProbeViewMode(
    overrideMode: ProbeViewMode? = null,
): Pair<ProbeViewMode, (ProbeViewMode) -> Unit> {
    val context = LocalContext.current
    val isInspectionMode = LocalInspectionMode.current
    val coroutineScope = rememberCoroutineScope()
    var viewMode by rememberSaveable(overrideMode) {
        mutableStateOf(overrideMode ?: ProbeViewMode.CARDS)
    }

    if (overrideMode == null && !isInspectionMode) {
        LaunchedEffect(context) {
            context.applicationContext.fleetProbeUiDataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .map { prefs -> ProbeViewModeCodec.decode(prefs[KEY_PROBE_VIEW_MODE]) }
                .collect { persisted ->
                    viewMode = persisted
                }
        }
    }

    val onSelectMode: (ProbeViewMode) -> Unit = { next ->
        if (next != viewMode) {
            viewMode = next
            if (overrideMode == null && !isInspectionMode) {
                coroutineScope.launch {
                    runCatching {
                        context.applicationContext.fleetProbeUiDataStore.edit { prefs ->
                            prefs[KEY_PROBE_VIEW_MODE] = ProbeViewModeCodec.encode(next)
                        }
                    }
                }
            }
        }
    }

    return viewMode to onSelectMode
}

// ── Single-Row Filter Bar (Status Chips + Panel Dropdown + View Mode Toggle) ───

@Composable
internal fun ProbeFilterAndViewBar(
    panelScopedSnapshots: List<ProbeServerSnapshot>,
    allSnapshots: List<ProbeServerSnapshot>,
    panels: List<ProbePanelConfig>,
    selectedFilter: ProbeOnlineFilter,
    selectedPanelIdFilter: String?,
    viewMode: ProbeViewMode,
    onSelectFilter: (ProbeOnlineFilter) -> Unit,
    onSelectPanelFilter: (String?) -> Unit,
    onOpenPanelManager: () -> Unit,
    onSelectViewMode: (ProbeViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val selectedPanel = remember(panels, selectedPanelIdFilter) {
        selectedPanelIdFilter?.let { id -> panels.firstOrNull { it.id == id } }
    }
    val countsByPanelId = remember(allSnapshots) {
        allSnapshots.groupingBy { it.panelId }.eachCount()
    }
    var panelMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ProbeOnlineFilter.entries.forEach { filter ->
                val count = when (filter) {
                    ProbeOnlineFilter.ALL -> panelScopedSnapshots.size
                    ProbeOnlineFilter.ONLINE -> panelScopedSnapshots.count { it.isOnline }
                    ProbeOnlineFilter.OFFLINE -> panelScopedSnapshots.count { !it.isOnline }
                }
                ProbeFilterChip(
                    label = "${filter.label} $count",
                    selected = selectedFilter == filter,
                    onClick = { onSelectFilter(filter) },
                )
            }

            Box {
                ProbeFilterChip(
                    label = selectedPanel?.name ?: "全部面板",
                    selected = selectedPanel != null,
                    trailingIcon = Icons.Rounded.KeyboardArrowDown,
                    maxLabelWidth = 96.dp,
                    onClick = { panelMenuExpanded = true },
                )
                DropdownMenu(
                    expanded = panelMenuExpanded,
                    onDismissRequest = { panelMenuExpanded = false },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "全部面板 (${allSnapshots.size})",
                                style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                                fontWeight = if (selectedPanelIdFilter == null) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedPanelIdFilter == null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        trailingIcon = if (selectedPanelIdFilter == null) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            panelMenuExpanded = false
                            onSelectPanelFilter(null)
                        },
                    )
                    panels.forEach { panel ->
                        val isSelected = selectedPanelIdFilter == panel.id
                        val nodeCount = countsByPanelId[panel.id] ?: 0
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${panel.name} ($nodeCount)",
                                    style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                panelMenuExpanded = false
                                onSelectPanelFilter(panel.id)
                            },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = ext.cardBorder.copy(alpha = 0.65f),
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp),
                            )
                        },
                        text = {
                            Text(
                                text = "管理面板",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            panelMenuExpanded = false
                            onOpenPanelManager()
                        },
                    )
                }
            }
        }

        ProbeViewModeToggle(
            viewMode = viewMode,
            onSelectViewMode = onSelectViewMode,
        )
    }
}

@Composable
private fun ProbeFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    maxLabelWidth: Dp = Dp.Unspecified,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .then(
                if (!selected) {
                    Modifier.border(0.9.dp, ext.cardBorder, CircleShape)
                } else {
                    Modifier
                },
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(
                start = 9.dp,
                end = if (trailingIcon != null) 6.dp else 9.dp,
                top = 5.dp,
                bottom = 5.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontFeatureSettings = TABULAR_NUMS_FEATURE,
            ),
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = if (maxLabelWidth != Dp.Unspecified) {
                Modifier.widthIn(max = maxLabelWidth)
            } else {
                Modifier
            },
        )
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ProbeViewModeToggle(
    viewMode: ProbeViewMode,
    onSelectViewMode: (ProbeViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .border(0.9.dp, ext.cardBorder, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProbeViewModeSegment(
            icon = Icons.Rounded.ViewAgenda,
            contentDescription = "卡片模式",
            selected = viewMode == ProbeViewMode.CARDS,
            onClick = { onSelectViewMode(ProbeViewMode.CARDS) },
        )
        Box(
            modifier = Modifier
                .width(0.8.dp)
                .height(26.dp)
                .background(ext.cardBorder.copy(alpha = 0.7f)),
        )
        ProbeViewModeSegment(
            icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
            contentDescription = "紧凑列表模式",
            selected = viewMode == ProbeViewMode.DENSE_LIST,
            onClick = { onSelectViewMode(ProbeViewMode.DENSE_LIST) },
        )
    }
}

@Composable
private fun ProbeViewModeSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(30.dp)
            .height(26.dp)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(
                role = Role.RadioButton,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Compact Probe Server Card (~134dp online, ~54dp collapsed offline) ─────────

@Composable
internal fun CompactProbeServerCard(
    snapshot: ProbeServerSnapshot,
    panel: ProbePanelConfig?,
    linkedAsset: VpsAssetRecord?,
    trafficForecast: ServerTrafficForecast?,
    idleAssessment: ServerIdleAssessment?,
    referenceNowEpochMillis: Long,
    onAddAssetFromProbe: (ProbeServerSnapshot) -> Unit,
    onOpenIdleSalePost: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenLinkedAsset: ((VpsAssetRecord) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val isOnline = snapshot.isOnline
    val isIdle = isOnline && idleAssessment?.isIdle == true

    val targetAccent = when {
        !isOnline -> MaterialTheme.colorScheme.error
        isIdle -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val accentColor by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "compactProbeAccent",
    )
    val cardBorderColor by animateColorAsState(
        targetValue = when {
            !isOnline -> blendColor(ext.cardBorder, MaterialTheme.colorScheme.error, if (isDark) 0.36f else 0.34f)
            isIdle -> blendColor(ext.cardBorder, ext.onDrumstickContainer, if (isDark) 0.24f else 0.22f)
            else -> ext.cardBorder
        },
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "compactProbeBorder",
    )

    val cardShape = PulseArchitecturalShapes.EditorialCard
    val topWash = blendColor(
        source = ext.cardSurface,
        target = accentColor,
        fraction = if (isDark) 0.08f else 0.055f,
    )
    val surfaceBrush = Brush.verticalGradient(
        0.0f to topWash,
        0.55f to ext.cardSurface,
        1.0f to ext.cardSurface,
    )

    val targetCpu = if (isOnline) snapshot.cpuPercent.coerceIn(0.0, 100.0).toFloat() else 0f
    val targetMemRatio = if (isOnline) snapshot.memUsageRatio.coerceIn(0f, 1f) else 0f
    val targetDiskRatio = if (isOnline) snapshot.diskUsageRatio.coerceIn(0f, 1f) else 0f

    val animatedCpu by animateFloatAsState(
        targetValue = targetCpu,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "compactCpu",
    )
    val animatedMemPct by animateFloatAsState(
        targetValue = targetMemRatio * 100f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "compactMem",
    )
    val animatedDiskPct by animateFloatAsState(
        targetValue = targetDiskRatio * 100f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "compactDisk",
    )

    val hasDiskTelemetry = snapshot.diskTotalBytes > 0L || snapshot.diskUsedBytes > 0L
    val trafficDisplay = remember(snapshot, linkedAsset, trafficForecast) {
        resolveTrafficDisplayState(snapshot, linkedAsset, trafficForecast)
    }

    var expanded by remember(snapshot.probeKey) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (!isDark) {
                    Modifier.shadow(
                        elevation = PulseElevation.Card,
                        shape = cardShape,
                        clip = false,
                        ambientColor = ext.softShadowColor,
                        spotColor = ext.softShadowColor,
                    )
                } else {
                    Modifier
                },
            )
            .clip(cardShape)
            .background(surfaceBrush)
            .border(width = 1.dp, color = cardBorderColor, shape = cardShape)
            .drawWithContent {
                drawContent()
                val railWidthPx = 3.dp.toPx()
                val insetPx = 10.dp.toPx().coerceAtMost(size.height * 0.22f)
                val railTop = insetPx
                val railBottom = (size.height - insetPx).coerceAtLeast(railTop + 4.dp.toPx())
                val railRadius = 3.dp.toPx()
                if (isDark) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(accentColor.copy(alpha = 0.22f), Color.Transparent),
                            startX = 0f,
                            endX = 12.dp.toPx(),
                        ),
                        topLeft = Offset(0f, railTop),
                        size = Size(12.dp.toPx(), railBottom - railTop),
                    )
                }
                val railPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(0f, railTop, railWidthPx, railBottom),
                            topLeft = CornerRadius.Zero,
                            bottomLeft = CornerRadius.Zero,
                            topRight = CornerRadius(railRadius, railRadius),
                            bottomRight = CornerRadius(railRadius, railRadius),
                        ),
                    )
                }
                drawPath(path = railPath, color = accentColor)
            }
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "收起节点详情" else "展开节点详情",
                onClick = { expanded = !expanded },
            )
            .padding(start = 15.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // ── Header Row ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                ProbeCountryBadge(
                    countryCode = snapshot.countryCode,
                    accentColor = accentColor,
                    ringSurfaceColor = ext.cardSurface,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = snapshot.name,
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isIdle) {
                            val idleDays = idleAssessment?.recordedDays ?: 0
                            val idleLabel = if (idleDays > 0) {
                                "闲置 $idleDays 天"
                            } else {
                                "闲置"
                            }
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(ext.drumstickContainer)
                                    .padding(horizontal = 6.dp, vertical = 1.5.dp),
                            ) {
                                Text(
                                    text = idleLabel,
                                    style = TextStyle(
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 9.5.sp,
                                        lineHeight = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFeatureSettings = TABULAR_NUMS_FEATURE,
                                    ),
                                    color = ext.onDrumstickContainer,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }

                    val metaText = remember(
                        isOnline,
                        panel?.name,
                        panel?.effectivePanelType,
                        snapshot.os,
                        snapshot.uptimeSeconds,
                        snapshot.lastActiveEpochMillis,
                        referenceNowEpochMillis,
                    ) {
                        buildProbeCardMetaLine(
                            snapshot = snapshot,
                            panel = panel,
                            referenceNowEpochMillis = referenceNowEpochMillis,
                        )
                    }
                    Text(
                        text = metaText,
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Normal,
                            fontFeatureSettings = TABULAR_NUMS_FEATURE,
                        ),
                        color = if (isOnline) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.90f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isOnline) {
                    ProbeStackedLiveSpeed(
                        netInSpeedBps = snapshot.netInSpeedBps,
                        netOutSpeedBps = snapshot.netOutSpeedBps,
                    )
                } else {
                    ProbeOfflinePill()
                }
            }

            // ── Online Telemetry (3 Ring Gauges + Traffic Bar Row) ────────────
            if (isOnline) {
                val cpuSub = remember(snapshot.cpuCores, snapshot.load1) {
                    buildList {
                        if (snapshot.cpuCores > 0) add("${snapshot.cpuCores}核")
                        snapshot.load1?.let { add(String.format(Locale.US, "L%.2f", it)) }
                    }.joinToString(" ").ifBlank { "实时负载" }
                }
                val memSub = remember(snapshot.memUsedBytes, snapshot.memTotalBytes) {
                    if (snapshot.memTotalBytes > 0L) {
                        "${formatProbeBytesTight(snapshot.memUsedBytes)}/${formatProbeBytesTight(snapshot.memTotalBytes)}"
                    } else {
                        formatProbeBytesTight(snapshot.memUsedBytes)
                    }
                }
                val diskSub = remember(hasDiskTelemetry, snapshot.diskUsedBytes, snapshot.diskTotalBytes) {
                    when {
                        !hasDiskTelemetry -> "未上报"
                        snapshot.diskTotalBytes > 0L ->
                            "${formatProbeBytesTight(snapshot.diskUsedBytes)}/${formatProbeBytesTight(snapshot.diskTotalBytes)}"
                        else -> formatProbeBytesTight(snapshot.diskUsedBytes)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProbeRingGaugeColumn(
                        percent = animatedCpu,
                        label = "CPU",
                        subText = cpuSub,
                        modifier = Modifier.weight(1f),
                    )
                    ProbeRingGaugeColumn(
                        percent = animatedMemPct,
                        label = "内存",
                        subText = memSub,
                        modifier = Modifier.weight(1f),
                    )
                    ProbeRingGaugeColumn(
                        percent = if (hasDiskTelemetry) animatedDiskPct else 0f,
                        label = "硬盘",
                        subText = diskSub,
                        noData = !hasDiskTelemetry,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ── Traffic Forecast Bar + Trailing Action Chip ───────────────
                ProbeCardTrafficRow(
                    trafficDisplay = trafficDisplay,
                    isIdle = isIdle,
                    linkedAsset = linkedAsset,
                    onOpenLinkedAsset = onOpenLinkedAsset,
                    onAddAsset = { onAddAssetFromProbe(snapshot) },
                    onOpenIdleSalePost = { onOpenIdleSalePost(snapshot.probeKey) },
                )
            }

            // ── Optional Tap-to-Expand Detail Drawer (preserves full telemetry) ─
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(200)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
            ) {
                ProbeExpandedDetailStrip(
                    snapshot = snapshot,
                    linkedAsset = linkedAsset,
                    trafficForecast = trafficForecast,
                    idleAssessment = idleAssessment,
                    onAddAssetFromProbe = onAddAssetFromProbe,
                    onOpenIdleSalePost = onOpenIdleSalePost,
                    onOpenLinkedAsset = onOpenLinkedAsset,
                    showActionChips = !isOnline,
                )
            }
        }
    }
}

@Composable
private fun ProbeCountryBadge(
    countryCode: String,
    accentColor: Color,
    ringSurfaceColor: Color,
) {
    val isDark = NodePulseThemeTokens.isDark
    val codeText = countryCode.trim().uppercase(Locale.US).ifBlank { "VPS" }.take(3)
    val badgeShape = RoundedCornerShape(9.dp)

    Box(modifier = Modifier.size(30.dp)) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(badgeShape)
                .background(accentColor.copy(alpha = if (isDark) 0.16f else 0.12f))
                .border(
                    width = 0.8.dp,
                    color = accentColor.copy(alpha = if (isDark) 0.35f else 0.28f),
                    shape = badgeShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = codeText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (codeText.length > 2) 9.5.sp else 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.3.sp,
                ),
                color = accentColor,
                maxLines = 1,
                softWrap = false,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(ringSurfaceColor)
                .padding(1.5.dp)
                .clip(CircleShape)
                .background(accentColor),
        )
    }
}

@Composable
private fun ProbeStackedLiveSpeed(
    netInSpeedBps: Long,
    netOutSpeedBps: Long,
) {
    val (inVal, inUnit) = remember(netInSpeedBps) { formatSpeedParts(netInSpeedBps) }
    val (outVal, outUnit) = remember(netOutSpeedBps) { formatSpeedParts(netOutSpeedBps) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val mutedColor = MaterialTheme.colorScheme.outline

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = primaryColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                ) {
                    append("↓$inVal")
                }
                withStyle(
                    SpanStyle(
                        color = mutedColor,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                    ),
                ) {
                    append(" $inUnit")
                }
            },
            style = TextStyle(
                lineHeight = 13.5.sp,
                fontFeatureSettings = TABULAR_NUMS_FEATURE,
            ),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = tertiaryColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                ) {
                    append("↑$outVal")
                }
                withStyle(
                    SpanStyle(
                        color = mutedColor,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                    ),
                ) {
                    append(" $outUnit")
                }
            },
            style = TextStyle(
                lineHeight = 13.5.sp,
                fontFeatureSettings = TABULAR_NUMS_FEATURE,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ProbeOfflinePill() {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 8.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Text(
            text = "离线",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ProbeRingGaugeColumn(
    percent: Float,
    label: String,
    subText: String,
    modifier: Modifier = Modifier,
    noData: Boolean = false,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val clampedPct = percent.coerceIn(0f, 100f)
    val heat = classifyProbeMetricHeat(clampedPct)
    val targetRingColor = when {
        noData -> MaterialTheme.colorScheme.outlineVariant
        heat == ProbeMetricHeat.HIGH -> MaterialTheme.colorScheme.error
        heat == ProbeMetricHeat.WARNING -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val ringColor by animateColorAsState(
        targetValue = targetRingColor,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "probeRingColor",
    )
    val trackColor = probeTrackColor(isDark)
    val mutedColor = MaterialTheme.colorScheme.outline
    val valueColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(36.dp)) {
                val strokeWidthPx = 3.6.dp.toPx()
                val arcInset = strokeWidthPx / 2f
                val arcSize = Size(size.width - strokeWidthPx, size.height - strokeWidthPx)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(arcInset, arcInset),
                    size = arcSize,
                    style = Stroke(width = strokeWidthPx),
                )
                if (!noData && clampedPct > 0.05f) {
                    val sweep = (clampedPct / 100f * 360f).coerceAtLeast(3.6f)
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(arcInset, arcInset),
                        size = arcSize,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Butt),
                    )
                }
            }

            if (noData) {
                Text(
                    text = "--",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = mutedColor,
                    maxLines = 1,
                )
            } else {
                val rounded = clampedPct.roundToInt()
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = if (rounded >= 100) 9.5.sp else 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.3).sp,
                                color = valueColor,
                            ),
                        ) {
                            append(rounded.toString())
                        }
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 6.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = mutedColor,
                                baselineShift = BaselineShift(0.32f),
                            ),
                        ) {
                            append("%")
                        }
                    },
                    style = TextStyle(
                        lineHeight = 12.sp,
                        fontFeatureSettings = TABULAR_NUMS_FEATURE,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = subText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.2).sp,
                    fontFeatureSettings = TABULAR_NUMS_FEATURE,
                ),
                color = mutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProbeCardTrafficRow(
    trafficDisplay: ProbeTrafficDisplayState,
    isIdle: Boolean,
    linkedAsset: VpsAssetRecord?,
    onOpenLinkedAsset: ((VpsAssetRecord) -> Unit)?,
    onAddAsset: () -> Unit,
    onOpenIdleSalePost: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val trackColor = probeTrackColor(isDark)
    val primaryColor = MaterialTheme.colorScheme.primary
    val goldColor = ext.onDrumstickContainer
    val barGoldColor = if (isDark) ext.onDrumstickContainer else ext.drumstickGoldStart
    val errorColor = MaterialTheme.colorScheme.error
    val tickNormalColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)

    val animatedProgress by animateFloatAsState(
        targetValue = (trafficDisplay.currentFraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "probeTrafficBar",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "流量",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            softWrap = false,
        )

        if (trafficDisplay.hasQuota) {
            Spacer(
                modifier = Modifier
                    .weight(1f)
                    .height(11.dp)
                    .drawBehind {
                        val barHeight = 5.dp.toPx()
                        val barTop = (size.height - barHeight) / 2f
                        val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset(0f, barTop),
                            size = Size(size.width, barHeight),
                            cornerRadius = radius,
                        )

                        val fillFrac = animatedProgress.coerceIn(0f, 1f)
                        if (fillFrac > 0.001f) {
                            val fillWidth = (size.width * fillFrac)
                                .coerceAtLeast(barHeight)
                                .coerceAtMost(size.width)
                            val fillBrush = when {
                                trafficDisplay.isForecastOver -> Brush.horizontalGradient(
                                    colors = listOf(barGoldColor, errorColor),
                                    startX = 0f,
                                    endX = fillWidth,
                                )
                                trafficDisplay.isHighWarning -> Brush.horizontalGradient(
                                    colors = listOf(barGoldColor.copy(alpha = 0.65f), barGoldColor),
                                    startX = 0f,
                                    endX = fillWidth,
                                )
                                else -> Brush.horizontalGradient(
                                    colors = listOf(primaryColor.copy(alpha = 0.55f), primaryColor),
                                    startX = 0f,
                                    endX = fillWidth,
                                )
                            }
                            drawRoundRect(
                                brush = fillBrush,
                                topLeft = Offset(0f, barTop),
                                size = Size(fillWidth, barHeight),
                                cornerRadius = radius,
                            )
                        }

                        val tickFrac = trafficDisplay.forecastTickFraction
                        if (tickFrac != null) {
                            val tickWidth = 2.dp.toPx()
                            val tickX = if (trafficDisplay.isForecastOver) {
                                (size.width - tickWidth).coerceAtLeast(0f)
                            } else {
                                (size.width * tickFrac.coerceIn(0f, 1f) - tickWidth / 2f)
                                    .coerceIn(0f, (size.width - tickWidth).coerceAtLeast(0f))
                            }
                            drawRoundRect(
                                color = if (trafficDisplay.isForecastOver) errorColor else tickNormalColor,
                                topLeft = Offset(tickX, 0f),
                                size = Size(tickWidth, size.height),
                                cornerRadius = CornerRadius(tickWidth / 2f, tickWidth / 2f),
                            )
                        }
                    },
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        val trafficTextColor = when {
            trafficDisplay.isForecastOver -> errorColor
            trafficDisplay.isHighWarning -> goldColor
            !trafficDisplay.hasQuota && trafficDisplay.usedBytes == 0L -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.onSurface
        }
        Text(
            text = trafficDisplay.cardText,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = TABULAR_NUMS_FEATURE,
            ),
            color = trafficTextColor,
            maxLines = 1,
            softWrap = false,
        )

        ProbeTrailingActionChip(
            isIdle = isIdle,
            linkedAsset = linkedAsset,
            onOpenLinkedAsset = onOpenLinkedAsset,
            onAddAsset = onAddAsset,
            onOpenIdleSalePost = onOpenIdleSalePost,
        )
    }
}

@Composable
private fun ProbeTrailingActionChip(
    isIdle: Boolean,
    linkedAsset: VpsAssetRecord?,
    onOpenLinkedAsset: ((VpsAssetRecord) -> Unit)?,
    onAddAsset: () -> Unit,
    onOpenIdleSalePost: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val primaryColor = MaterialTheme.colorScheme.primary

    when {
        isIdle -> {
            Row(
                modifier = Modifier
                    .height(22.dp)
                    .clip(CircleShape)
                    .background(ext.drumstickContainer)
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClickLabel = "出掉回血",
                        onClick = onOpenIdleSalePost,
                    )
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocalOffer,
                    contentDescription = null,
                    tint = ext.onDrumstickContainer,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "出掉回血",
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = ext.onDrumstickContainer,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        linkedAsset != null -> {
            val clickableModifier = if (onOpenLinkedAsset != null) {
                Modifier.pulseBouncyClickable(
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClickLabel = "查看关联账本",
                    onClick = { onOpenLinkedAsset(linkedAsset) },
                )
            } else {
                Modifier
            }
            Row(
                modifier = Modifier
                    .height(22.dp)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = if (isDark) 0.16f else 0.12f))
                    .then(clickableModifier)
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "账本",
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = primaryColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        else -> {
            val dashColor = primaryColor.copy(alpha = if (isDark) 0.52f else 0.48f)
            Row(
                modifier = Modifier
                    .height(22.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        val strokePx = 1.dp.toPx()
                        val half = strokePx / 2f
                        drawRoundRect(
                            color = dashColor,
                            topLeft = Offset(half, half),
                            size = Size(size.width - strokePx, size.height - strokePx),
                            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                            style = Stroke(
                                width = strokePx,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                                    0f,
                                ),
                            ),
                        )
                    }
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClickLabel = "录入账本",
                        onClick = onAddAsset,
                    )
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "录入",
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = primaryColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun ProbeExpandedDetailStrip(
    snapshot: ProbeServerSnapshot,
    linkedAsset: VpsAssetRecord?,
    trafficForecast: ServerTrafficForecast?,
    idleAssessment: ServerIdleAssessment?,
    onAddAssetFromProbe: (ProbeServerSnapshot) -> Unit,
    onOpenIdleSalePost: (String) -> Unit,
    onOpenLinkedAsset: ((VpsAssetRecord) -> Unit)?,
    showActionChips: Boolean,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isIdle = snapshot.isOnline && idleAssessment?.isIdle == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        HorizontalDivider(
            thickness = 0.75.dp,
            color = ext.cardBorder.copy(alpha = 0.55f),
        )

        if (snapshot.totalMonthlyTransferBytes > 0L || trafficForecast?.limitBytes != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "月流量 ↓${formatBytesBinary(snapshot.monthlyTransferInBytes)} · ↑${formatBytesBinary(snapshot.monthlyTransferOutBytes)}",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (trafficForecast != null && trafficForecast.limitBytes != null) {
                    Text(
                        text = "每月 ${trafficForecast.anchorResetDay} 号重置",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        val forecastNote = trafficForecast?.exhaustionForecastText
        if (!forecastNote.isNullOrBlank()) {
            Text(
                text = forecastNote,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.SemiBold,
                color = if (trafficForecast.isExhausted || trafficForecast.isAtOrAboveAlertThreshold) {
                    MaterialTheme.colorScheme.error
                } else {
                    ext.onDrumstickContainer
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isIdle && !idleAssessment?.recommendationText.isNullOrBlank()) {
            Text(
                text = idleAssessment.recommendationText!!,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.Medium,
                color = ext.onDrumstickContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val assetInfo = if (linkedAsset != null) {
                buildString {
                    append("账本：${linkedAsset.nickname}")
                    if (linkedAsset.expiryDateIso.isNotBlank()) {
                        append(" · 到期 ${linkedAsset.expiryDateIso}")
                    }
                }
            } else {
                "未关联资产账本"
            }
            Text(
                text = assetInfo,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            if (showActionChips) {
                ProbeTrailingActionChip(
                    isIdle = isIdle,
                    linkedAsset = linkedAsset,
                    onOpenLinkedAsset = onOpenLinkedAsset,
                    onAddAsset = { onAddAssetFromProbe(snapshot) },
                    onOpenIdleSalePost = { onOpenIdleSalePost(snapshot.probeKey) },
                )
            }
        }
    }
}

// ── Dense List Mode (virtualized header + rows for LazyColumn) ───────────────

@Composable
internal fun ProbeDenseListHeaderRow(
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val topShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(topShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(width = 0.9.dp, color = ext.cardBorder, shape = topShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val headerStyle = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 9.5.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            )
            val headerColor = MaterialTheme.colorScheme.outline

            Text(
                text = "节点",
                style = headerStyle,
                color = headerColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "CPU",
                style = headerStyle,
                color = headerColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(34.dp),
            )
            Text(
                text = "内存",
                style = headerStyle,
                color = headerColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(34.dp),
            )
            Text(
                text = "硬盘",
                style = headerStyle,
                color = headerColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(34.dp),
            )
            Text(
                text = "网速",
                style = headerStyle,
                color = headerColor,
                textAlign = TextAlign.End,
                modifier = Modifier.width(60.dp),
            )
        }
    }
}

@Composable
internal fun ProbeDenseListVirtualizedItem(
    snapshot: ProbeServerSnapshot,
    panel: ProbePanelConfig?,
    linkedAsset: VpsAssetRecord?,
    trafficForecast: ServerTrafficForecast?,
    idleAssessment: ServerIdleAssessment?,
    referenceNowEpochMillis: Long,
    expanded: Boolean,
    isLast: Boolean,
    onToggleExpand: () -> Unit,
    onAddAssetFromProbe: (ProbeServerSnapshot) -> Unit,
    onOpenIdleSalePost: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenLinkedAsset: ((VpsAssetRecord) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val itemShape = if (isLast) {
        RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(0.dp)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(itemShape)
            .background(ext.cardSurface)
            .border(width = 0.75.dp, color = ext.cardBorder.copy(alpha = 0.85f), shape = itemShape),
    ) {
        ProbeDenseListRow(
            snapshot = snapshot,
            panel = panel,
            linkedAsset = linkedAsset,
            trafficForecast = trafficForecast,
            idleAssessment = idleAssessment,
            referenceNowEpochMillis = referenceNowEpochMillis,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            onAddAssetFromProbe = onAddAssetFromProbe,
            onOpenIdleSalePost = onOpenIdleSalePost,
            onOpenLinkedAsset = onOpenLinkedAsset,
        )
    }
}

@Composable
internal fun ProbeDenseListCard(
    snapshots: List<ProbeServerSnapshot>,
    panelsById: Map<String, ProbePanelConfig>,
    assetsByProbeKey: Map<String, VpsAssetRecord>,
    trafficForecastsByProbeKey: Map<String, ServerTrafficForecast>,
    idleAssessmentsByProbeKey: Map<String, ServerIdleAssessment>,
    referenceNowEpochMillis: Long,
    onAddAssetFromProbe: (ProbeServerSnapshot) -> Unit,
    onOpenIdleSalePost: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenLinkedAsset: ((VpsAssetRecord) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    var expandedProbeKey by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Card,
            ),
    ) {
        ProbeDenseListHeaderRow()
        snapshots.forEachIndexed { index, snapshot ->
            val panel = panelsById[snapshot.panelId]
            val linkedAsset = assetsByProbeKey[snapshot.probeKey]
            val trafficForecast = trafficForecastsByProbeKey[snapshot.probeKey]
            val idleAssessment = idleAssessmentsByProbeKey[snapshot.probeKey]
            val isExpanded = expandedProbeKey == snapshot.probeKey

            ProbeDenseListRow(
                snapshot = snapshot,
                panel = panel,
                linkedAsset = linkedAsset,
                trafficForecast = trafficForecast,
                idleAssessment = idleAssessment,
                referenceNowEpochMillis = referenceNowEpochMillis,
                expanded = isExpanded,
                onToggleExpand = {
                    expandedProbeKey = if (isExpanded) null else snapshot.probeKey
                },
                onAddAssetFromProbe = onAddAssetFromProbe,
                onOpenIdleSalePost = onOpenIdleSalePost,
                onOpenLinkedAsset = onOpenLinkedAsset,
            )

            if (index < snapshots.lastIndex) {
                HorizontalDivider(
                    thickness = 0.75.dp,
                    color = ext.cardBorder.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun ProbeDenseListRow(
    snapshot: ProbeServerSnapshot,
    panel: ProbePanelConfig?,
    linkedAsset: VpsAssetRecord?,
    trafficForecast: ServerTrafficForecast?,
    idleAssessment: ServerIdleAssessment?,
    referenceNowEpochMillis: Long,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAddAssetFromProbe: (ProbeServerSnapshot) -> Unit,
    onOpenIdleSalePost: (String) -> Unit,
    onOpenLinkedAsset: ((VpsAssetRecord) -> Unit)?,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val isOnline = snapshot.isOnline
    val isIdle = isOnline && idleAssessment?.isIdle == true

    val dotColor = when {
        !isOnline -> MaterialTheme.colorScheme.error
        isIdle -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val rowBg = if (!isOnline) {
        MaterialTheme.colorScheme.error.copy(alpha = if (isDark) 0.06f else 0.05f)
    } else {
        Color.Transparent
    }

    val trafficDisplay = remember(snapshot, linkedAsset, trafficForecast) {
        resolveTrafficDisplayState(snapshot, linkedAsset, trafficForecast)
    }
    val subLine = remember(isOnline, isIdle, idleAssessment, trafficDisplay, snapshot.uptimeSeconds, panel?.name) {
        buildDenseSubLine(
            snapshot = snapshot,
            panel = panel,
            isIdle = isIdle,
            idleAssessment = idleAssessment,
            trafficDisplay = trafficDisplay,
        )
    }
    val subLineColor = when (subLine.tone) {
        DenseSubLineTone.ERROR -> MaterialTheme.colorScheme.error
        DenseSubLineTone.GOLD -> ext.onDrumstickContainer
        DenseSubLineTone.MUTED -> MaterialTheme.colorScheme.outline
    }

    val targetCpu = if (isOnline) snapshot.cpuPercent.coerceIn(0.0, 100.0).toFloat() else 0f
    val targetMemPct = if (isOnline) snapshot.memUsageRatio.coerceIn(0f, 1f) * 100f else 0f
    val targetDiskPct = if (isOnline) snapshot.diskUsageRatio.coerceIn(0f, 1f) * 100f else 0f
    val hasDiskTelemetry = snapshot.diskTotalBytes > 0L || snapshot.diskUsedBytes > 0L

    val animatedCpu by animateFloatAsState(
        targetValue = targetCpu,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "denseCpu",
    )
    val animatedMem by animateFloatAsState(
        targetValue = targetMemPct,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "denseMem",
    )
    val animatedDisk by animateFloatAsState(
        targetValue = targetDiskPct,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "denseDisk",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "收起节点操作" else "展开节点操作",
                onClick = onToggleExpand,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Node identity column (dot + country code + name/sub-line)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                // Status dot with subtle halo
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = 0.21f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }

                val cc = snapshot.countryCode.trim().uppercase(Locale.US).ifBlank { "--" }.take(2)
                Text(
                    text = cc,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(17.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = snapshot.name,
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 12.5.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subLine.text,
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 9.5.sp,
                            lineHeight = 12.5.sp,
                            fontWeight = if (subLine.tone == DenseSubLineTone.MUTED) FontWeight.Normal else FontWeight.Medium,
                            fontFeatureSettings = TABULAR_NUMS_FEATURE,
                        ),
                        color = subLineColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isOnline) {
                ProbeDenseMetricCell(
                    percent = animatedCpu,
                    modifier = Modifier.width(34.dp),
                )
                ProbeDenseMetricCell(
                    percent = animatedMem,
                    modifier = Modifier.width(34.dp),
                )
                ProbeDenseMetricCell(
                    percent = if (hasDiskTelemetry) animatedDisk else 0f,
                    noData = !hasDiskTelemetry,
                    modifier = Modifier.width(34.dp),
                )
                Column(
                    modifier = Modifier.width(60.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = "↓${formatSpeedDense(snapshot.netInSpeedBps)}",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = TABULAR_NUMS_FEATURE,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = "↑${formatSpeedDense(snapshot.netOutSpeedBps)}",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = TABULAR_NUMS_FEATURE,
                        ),
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            } else {
                val offlineLabel = remember(snapshot.lastActiveEpochMillis, referenceNowEpochMillis) {
                    buildDenseOfflineSpanLabel(
                        lastActiveEpochMillis = snapshot.lastActiveEpochMillis,
                        referenceNowEpochMillis = referenceNowEpochMillis,
                    )
                }
                Text(
                    text = offlineLabel,
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = TABULAR_NUMS_FEATURE,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(174.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
        ) {
            Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                ProbeExpandedDetailStrip(
                    snapshot = snapshot,
                    linkedAsset = linkedAsset,
                    trafficForecast = trafficForecast,
                    idleAssessment = idleAssessment,
                    onAddAssetFromProbe = onAddAssetFromProbe,
                    onOpenIdleSalePost = onOpenIdleSalePost,
                    onOpenLinkedAsset = onOpenLinkedAsset,
                    showActionChips = true,
                )
            }
        }
    }
}

@Composable
private fun ProbeDenseMetricCell(
    percent: Float,
    modifier: Modifier = Modifier,
    noData: Boolean = false,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val clamped = percent.coerceIn(0f, 100f)
    val heat = classifyProbeMetricHeat(clamped)
    val barColor = when {
        noData -> MaterialTheme.colorScheme.outlineVariant
        heat == ProbeMetricHeat.HIGH -> MaterialTheme.colorScheme.error
        heat == ProbeMetricHeat.WARNING -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val numColor = when {
        noData -> MaterialTheme.colorScheme.outline
        heat == ProbeMetricHeat.HIGH -> MaterialTheme.colorScheme.error
        heat == ProbeMetricHeat.WARNING -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val trackColor = probeTrackColor(isDark)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = if (noData) "--" else clamped.roundToInt().toString(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = TABULAR_NUMS_FEATURE,
            ),
            color = numColor,
            maxLines = 1,
            softWrap = false,
        )
        Box(
            modifier = Modifier
                .width(30.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(trackColor),
        ) {
            if (!noData && clamped > 0.1f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((clamped / 100f).coerceIn(0.06f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor),
                )
            }
        }
    }
}

// ── Pure Display & Formatting Helpers ──────────────────────────────────────────

@Composable
private fun probeTrackColor(isDark: Boolean): Color =
    if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.085f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

internal fun classifyProbeMetricHeat(percent: Float): ProbeMetricHeat = when {
    percent > 85f -> ProbeMetricHeat.HIGH
    percent >= 60f -> ProbeMetricHeat.WARNING
    else -> ProbeMetricHeat.NORMAL
}

internal data class ProbeTrafficDisplayState(
    val usedBytes: Long,
    val limitBytes: Long?,
    val currentFraction: Float?,
    val forecastTickFraction: Float?,
    val isForecastOver: Boolean,
    val isHighWarning: Boolean,
    val isExhausted: Boolean,
    val cardText: String,
) {
    val hasQuota: Boolean
        get() = limitBytes != null && limitBytes > 0L
}

internal fun resolveTrafficDisplayState(
    snapshot: ProbeServerSnapshot,
    linkedAsset: VpsAssetRecord?,
    trafficForecast: ServerTrafficForecast?,
): ProbeTrafficDisplayState {
    val used = snapshot.totalMonthlyTransferBytes.coerceAtLeast(0L)
    val limit = trafficForecast?.limitBytes?.takeIf { it > 0L }
        ?: FleetTrafficAndIdleAnalyzer.resolveEffectiveTrafficLimitBytes(snapshot, linkedAsset)
    val ratio = limit?.let { (used.toDouble() / it.toDouble()).toFloat().coerceAtLeast(0f) }

    val isExhausted = trafficForecast?.isExhausted == true || (limit != null && used >= limit)
    val projectedRatio = trafficForecast?.projectedCycleUsageRatio
    val isForecastOver = limit != null && (
        isExhausted ||
            trafficForecast?.willExhaustBeforeReset == true ||
            (projectedRatio != null && projectedRatio > 1.0)
        )
    val isHighWarning = !isForecastOver && limit != null && (
        trafficForecast?.isAtOrAboveAlertThreshold == true ||
            (ratio != null && ratio >= FleetTrafficAndIdleAnalyzer.TRAFFIC_ALERT_THRESHOLD_RATIO.toFloat())
        )

    val forecastTickFraction = when {
        limit == null -> null
        isForecastOver -> 1f
        projectedRatio != null -> projectedRatio.toFloat().coerceIn(0f, 1f)
        else -> null
    }

    val cardText = if (limit != null) {
        val base = "${formatProbeBytesTight(used)}/${formatProbeBytesTight(limit)}"
        when {
            isExhausted -> "$base 已用完"
            isForecastOver -> "$base 预计超"
            isHighWarning -> "$base 预警"
            else -> base
        }
    } else if (used > 0L) {
        formatProbeBytesTight(used)
    } else {
        "无流量限额"
    }

    return ProbeTrafficDisplayState(
        usedBytes = used,
        limitBytes = limit,
        currentFraction = ratio?.coerceIn(0f, 1f),
        forecastTickFraction = forecastTickFraction,
        isForecastOver = isForecastOver,
        isHighWarning = isHighWarning,
        isExhausted = isExhausted,
        cardText = cardText,
    )
}

private enum class DenseSubLineTone {
    MUTED,
    GOLD,
    ERROR,
}

private data class DenseSubLine(
    val text: String,
    val tone: DenseSubLineTone,
)

private fun buildDenseSubLine(
    snapshot: ProbeServerSnapshot,
    panel: ProbePanelConfig?,
    isIdle: Boolean,
    idleAssessment: ServerIdleAssessment?,
    trafficDisplay: ProbeTrafficDisplayState,
): DenseSubLine {
    if (!snapshot.isOnline) {
        val panelLabel = panel?.name?.takeIf { it.isNotBlank() }
            ?: snapshot.os.takeIf { it.isNotBlank() }
            ?: "探针节点"
        return DenseSubLine(panelLabel, DenseSubLineTone.MUTED)
    }
    if (isIdle && idleAssessment != null) {
        val label = if (idleAssessment.recordedDays > 0) {
            "闲置 ${idleAssessment.recordedDays} 天 · 可出机"
        } else {
            "闲置 · 可出机"
        }
        return DenseSubLine(label, DenseSubLineTone.GOLD)
    }
    val pct = trafficDisplay.currentFraction?.let { (it * 100f).roundToInt() }
    if (trafficDisplay.isForecastOver && pct != null) {
        val suffix = if (trafficDisplay.isExhausted) "已用完" else "预计超额"
        return DenseSubLine("流量 $pct% · $suffix", DenseSubLineTone.ERROR)
    }
    if (trafficDisplay.isHighWarning && pct != null) {
        return DenseSubLine("流量 $pct% · 流量预警", DenseSubLineTone.GOLD)
    }

    val parts = buildList {
        if (pct != null) {
            add("流量 $pct%")
        } else if (trafficDisplay.usedBytes > 0L) {
            add("流量 ${formatProbeBytesTight(trafficDisplay.usedBytes)}")
        }
        if (snapshot.uptimeSeconds > 0L) {
            add(formatUptimeCompact(snapshot.uptimeSeconds))
        }
    }
    val text = parts.joinToString(" · ").ifBlank {
        panel?.name?.takeIf { it.isNotBlank() } ?: "在线"
    }
    return DenseSubLine(text, DenseSubLineTone.MUTED)
}

internal fun buildProbeCardMetaLine(
    snapshot: ProbeServerSnapshot,
    panel: ProbePanelConfig?,
    referenceNowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val panelLabel = panel?.name?.takeIf { it.isNotBlank() }
    if (snapshot.isOnline) {
        return buildList {
            panelLabel?.let { add(it) }
            snapshot.os.takeIf { it.isNotBlank() }?.let { add(it) }
            if (snapshot.uptimeSeconds > 0L) {
                add("运行 ${formatUptimeCompact(snapshot.uptimeSeconds)}")
            }
        }.joinToString(" · ").ifBlank { "探针节点" }
    }

    return buildList {
        panelLabel?.let { add(it) }
        if (snapshot.lastActiveEpochMillis > 0L) {
            val effectiveNow = referenceNowEpochMillis
                .takeIf { it > 0L && it >= snapshot.lastActiveEpochMillis }
                ?: System.currentTimeMillis()
            val elapsedMs = (effectiveNow - snapshot.lastActiveEpochMillis).coerceAtLeast(0L)
            if (elapsedMs >= 60_000L) {
                add("失联 ${formatOfflineDurationCompact(elapsedMs)}")
            } else {
                add("刚刚失联")
            }
            add("最后 ${formatHourMinute(snapshot.lastActiveEpochMillis, zoneId)}")
        } else {
            add("节点未响应")
        }
    }.joinToString(" · ").ifBlank { "节点未响应" }
}

internal fun buildDenseOfflineSpanLabel(
    lastActiveEpochMillis: Long,
    referenceNowEpochMillis: Long,
): String {
    if (lastActiveEpochMillis <= 0L) return "离线"
    val effectiveNow = referenceNowEpochMillis
        .takeIf { it > 0L && it >= lastActiveEpochMillis }
        ?: System.currentTimeMillis()
    val elapsedMs = (effectiveNow - lastActiveEpochMillis).coerceAtLeast(0L)
    if (elapsedMs < 60_000L) return "离线"
    return "离线 ${formatOfflineDurationCompact(elapsedMs)}"
}

internal fun formatProbeBytesTight(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024L) return "${safe}B"
    val kb = safe / 1024.0
    if (kb < 1024.0) return "${kb.roundToInt()}K"
    val mb = kb / 1024.0
    if (mb < 1024.0) {
        return if (mb >= 100.0 || isNearlyInteger(mb)) {
            "${mb.roundToInt()}M"
        } else {
            String.format(Locale.US, "%.1fM", mb)
        }
    }
    val gb = mb / 1024.0
    if (gb < 1024.0) {
        return if (gb >= 10.0 || isNearlyInteger(gb)) {
            "${gb.roundToInt()}G"
        } else {
            String.format(Locale.US, "%.1fG", gb)
        }
    }
    val tb = gb / 1024.0
    return when {
        isNearlyInteger(tb) -> "${tb.roundToInt()}T"
        tb >= 10.0 -> String.format(Locale.US, "%.1fT", tb)
        else -> String.format(Locale.US, "%.2fT", tb)
    }
}

internal fun formatSpeedParts(bytesPerSecond: Long): Pair<String, String> {
    val safe = bytesPerSecond.coerceAtLeast(0L)
    if (safe < 1024L) return safe.toString() to "B/s"
    val kb = safe / 1024.0
    if (kb < 1024.0) {
        val num = if (kb >= 100.0) {
            kb.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", kb)
        }
        return num to "KB/s"
    }
    val mb = kb / 1024.0
    if (mb < 1024.0) {
        val num = when {
            mb >= 100.0 -> mb.roundToInt().toString()
            mb >= 1.0 -> String.format(Locale.US, "%.1f", mb)
            else -> String.format(Locale.US, "%.2f", mb)
        }
        return num to "MB/s"
    }
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f", gb) to "GB/s"
}

internal fun formatSpeedDense(bytesPerSecond: Long): String {
    val safe = bytesPerSecond.coerceAtLeast(0L)
    if (safe < 1024L) return "${safe}B"
    val kb = safe / 1024.0
    if (kb < 1024.0) {
        return if (kb >= 10.0) {
            "${kb.roundToInt()}K"
        } else {
            String.format(Locale.US, "%.1fK", kb)
        }
    }
    val mb = kb / 1024.0
    if (mb < 1024.0) {
        return if (mb >= 100.0) {
            "${mb.roundToInt()}M"
        } else {
            String.format(Locale.US, "%.1fM", mb)
        }
    }
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.1fG", gb)
}

internal fun formatUptimeCompact(uptimeSeconds: Long): String {
    val safe = uptimeSeconds.coerceAtLeast(0L)
    if (safe == 0L) return "0分"
    val days = safe / 86_400L
    val hours = (safe % 86_400L) / 3_600L
    val minutes = (safe % 3_600L) / 60L
    return when {
        days > 0L -> "${days}天"
        hours > 0L -> "${hours}时"
        else -> "${minutes.coerceAtLeast(1L)}分"
    }
}

internal fun formatOfflineDurationCompact(elapsedMillis: Long): String {
    val safe = elapsedMillis.coerceAtLeast(0L)
    val minutes = (safe + 30_000L) / 60_000L
    if (minutes < 60L) return "${minutes.coerceAtLeast(1L)} 分钟"
    val hours = minutes / 60L
    if (hours < 24L) return "$hours 小时"
    val days = hours / 24L
    return "$days 天"
}

private val HOUR_MINUTE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

internal fun formatHourMinute(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(HOUR_MINUTE_FORMATTER)

private fun isNearlyInteger(value: Double): Boolean = abs(value - value.roundToInt()) < 0.05
