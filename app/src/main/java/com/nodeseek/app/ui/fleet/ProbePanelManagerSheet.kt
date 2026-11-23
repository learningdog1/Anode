@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.fleet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Bottom sheet for adding/editing multiple Nezha v0, Nezha v1, and Komari probe panels,
 * running protocol auto-discovery connectivity tests, and configuring background monitoring.
 */
@Composable
internal fun ProbePanelManagerSheet(
    panels: List<ProbePanelConfig>,
    monitorSettings: FleetMonitorSettings,
    testState: PanelTestUiState,
    onTestConnection: (ProbePanelConfig) -> Unit,
    onClearTestState: () -> Unit,
    onSavePanel: (ProbePanelConfig) -> Unit,
    onDeletePanel: (String) -> Unit,
    onToggleOfflineAlert: (ProbePanelConfig, Boolean) -> Unit,
    onUpdateMonitorSettings: (FleetMonitorSettings) -> Unit,
    onDismiss: () -> Unit,
    saveError: String? = null,
    onClearSaveError: () -> Unit = {},
    initialSection: PanelManagerSection = PanelManagerSection.PANELS,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.90f).dp
    val ext = NodePulseThemeTokens.extendedColors
    val scrollState = rememberScrollState()

    var editingPanelId by remember { mutableStateOf<String?>(null) }
    var showForm by remember(panels.isEmpty(), initialSection) {
        mutableStateOf(panels.isEmpty() && initialSection == PanelManagerSection.PANELS)
    }
    var pendingSavedPanelId by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var panelType by remember { mutableStateOf(ProbePanelType.AUTO) }
    var tokenInput by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var notifyOnOffline by remember { mutableStateOf(true) }
    var notifyOnRecovery by remember { mutableStateOf(false) }

    var hasAutoScrolledToMonitor by remember(initialSection) { mutableStateOf(false) }

    LaunchedEffect(initialSection) {
        if (initialSection == PanelManagerSection.MONITOR_SETTINGS && !hasAutoScrolledToMonitor) {
            val targetMax = snapshotFlow { scrollState.maxValue }
                .first { it != Int.MAX_VALUE }
            hasAutoScrolledToMonitor = true
            if (targetMax > 0) {
                scrollState.animateScrollTo(targetMax)
            }
        }
    }

    LaunchedEffect(panels, saveError, pendingSavedPanelId) {
        val targetId = pendingSavedPanelId ?: return@LaunchedEffect
        if (saveError != null) {
            pendingSavedPanelId = null
            showForm = true
        } else if (panels.any { it.id == targetId }) {
            pendingSavedPanelId = null
            showForm = false
            onClearTestState()
        }
    }

    fun resetFormForNew() {
        editingPanelId = null
        pendingSavedPanelId = null
        nameInput = ""
        urlInput = ""
        panelType = ProbePanelType.AUTO
        tokenInput = ""
        showToken = false
        notifyOnOffline = true
        notifyOnRecovery = false
        onClearTestState()
        onClearSaveError()
        showForm = true
    }

    fun loadPanelIntoForm(panel: ProbePanelConfig) {
        editingPanelId = panel.id
        pendingSavedPanelId = null
        nameInput = panel.name
        urlInput = panel.baseUrl
        panelType = panel.panelType
        tokenInput = panel.apiToken.orEmpty()
        showToken = false
        notifyOnOffline = panel.notifyOnOffline
        notifyOnRecovery = panel.notifyOnRecovery
        onClearTestState()
        onClearSaveError()
        showForm = true
    }

    fun buildCandidateConfig(): ProbePanelConfig {
        val detectedType = (testState as? PanelTestUiState.Completed)
            ?.result
            ?.takeIf { it.success }
            ?.detectedPanelType
        return ProbePanelConfig(
            id = editingPanelId ?: UUID.randomUUID().toString(),
            name = nameInput.trim().ifBlank { urlInput.trim() },
            baseUrl = urlInput.trim(),
            panelType = panelType,
            resolvedPanelType = detectedType,
            apiToken = tokenInput.trim().takeIf { it.isNotEmpty() },
            notifyOnOffline = notifyOnOffline,
            notifyOnRecovery = notifyOnRecovery,
            enabled = true,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "探针面板与巡检管理",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "支持哪吒 v0 / 哪吒 v1 / Komari · Token Keystore 加密存储",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Existing panels list
                if (panels.isNotEmpty() || !showForm) {
                    PulseSectionHeader(
                        title = "已接入面板 (${panels.size})",
                        icon = Icons.Rounded.Dns,
                        trailing = {
                            if (!showForm) {
                                FilledTonalButton(
                                    onClick = { resetFormForNew() },
                                    shape = CircleShape,
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "新增面板",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        },
                    )
                    panels.forEach { panel ->
                        PanelListCard(
                            panel = panel,
                            onEdit = { loadPanelIntoForm(panel) },
                            onDelete = { onDeletePanel(panel.id) },
                            onToggleOfflineAlert = { enabled -> onToggleOfflineAlert(panel, enabled) },
                        )
                    }
                }

                // Add / Edit Panel form
                AnimatedVisibility(visible = showForm) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseCard(
                                shape = PulseArchitecturalShapes.EditorialCard,
                                containerColor = ext.cardSurface,
                                elevation = PulseElevation.Subtle,
                                accentColor = MaterialTheme.colorScheme.primary,
                            )
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (editingPanelId == null) "添加探针面板" else "编辑探针面板",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            if (panels.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        showForm = false
                                        onClearTestState()
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "收起表单", modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("面板名称") },
                            placeholder = { Text("例如：主力哪吒 / Komari 亚太") },
                            singleLine = true,
                            shape = PulseArchitecturalShapes.MediumBadge,
                            colors = fleetTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = {
                                urlInput = it
                                onClearTestState()
                            },
                            label = { Text("面板地址 (URL)") },
                            placeholder = { Text("https://probe.example.com") },
                            singleLine = true,
                            shape = PulseArchitecturalShapes.MediumBadge,
                            colors = fleetTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Panel Type selector
                        Text(
                            text = "面板协议类型",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ProbePanelType.entries.forEach { type ->
                                FleetChoiceChip(
                                    label = type.displayName,
                                    selected = panelType == type,
                                    onClick = {
                                        panelType = type
                                        onClearTestState()
                                    },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = {
                                tokenInput = it
                                onClearTestState()
                            },
                            label = { Text("API Token / Cookie（公开面板可留空）") },
                            placeholder = { Text("哪吒 API Token / JWT / Komari Key") },
                            singleLine = true,
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(
                                        imageVector = if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showToken) "隐藏" else "显示",
                                    )
                                }
                            },
                            supportingText = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text("令牌经 Android Keystore AES-GCM 加密保存在本地")
                                }
                            },
                            shape = PulseArchitecturalShapes.MediumBadge,
                            colors = fleetTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "节点离线高优告警",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "当在线节点掉线时推送通知（同一次断线自动去重）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = notifyOnOffline, onCheckedChange = { notifyOnOffline = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "恢复在线通知",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "掉线节点重新上线后发送恢复提醒",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = notifyOnRecovery, onCheckedChange = { notifyOnRecovery = it })
                        }

                        // Connectivity test banner
                        when (testState) {
                            PanelTestUiState.Idle -> Unit
                            PanelTestUiState.Testing -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(PulseArchitecturalShapes.MediumBadge)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        text = "正在探测面板接口与协议类型…",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            is PanelTestUiState.Completed -> {
                                val res = testState.result
                                val bg = if (res.success) {
                                    FleetSemanticColors.onlineContainer()
                                } else {
                                    FleetSemanticColors.offlineContainer()
                                }
                                val fg = if (res.success) {
                                    FleetSemanticColors.onlineOnContainer()
                                } else {
                                    FleetSemanticColors.offlineOnContainer()
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(PulseArchitecturalShapes.MediumBadge)
                                        .background(bg)
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = if (res.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = fg,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = if (res.success) {
                                            "连接成功 · 识别为 ${res.detectedPanelType?.displayName ?: panelType.displayName}" +
                                                " · 在线 ${res.onlineCount}/${res.serverCount} 台"
                                        } else {
                                            res.errorMessage ?: "连接失败"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = fg,
                                    )
                                }
                            }
                        }

                        saveError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onTestConnection(buildCandidateConfig()) },
                                enabled = urlInput.isNotBlank() && testState != PanelTestUiState.Testing,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "测试连接",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = {
                                    onClearSaveError()
                                    val candidate = buildCandidateConfig()
                                    pendingSavedPanelId = candidate.id
                                    onSavePanel(candidate)
                                },
                                enabled = urlInput.isNotBlank(),
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = if (editingPanelId == null) "保存并同步" else "更新面板",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Background Monitor Settings Section
                PulseGradientDivider()
                PulseSectionHeader(
                    title = "后台巡检与到期提醒",
                    subtitle = "WorkManager 定时检测离线节点与临近续费资产",
                    icon = Icons.Rounded.NotificationsActive,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = PulseArchitecturalShapes.EditorialCard,
                            containerColor = ext.cardSurface,
                            elevation = PulseElevation.None,
                            accentColor = if (initialSection == PanelManagerSection.MONITOR_SETTINGS) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                null
                            },
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用后台定时巡检",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "离线告警渠道：高优先级 · 续费提醒渠道：常规优先级",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = monitorSettings.backgroundMonitorEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateMonitorSettings(monitorSettings.copy(backgroundMonitorEnabled = enabled))
                            },
                        )
                    }

                    AnimatedVisibility(visible = monitorSettings.backgroundMonitorEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "后台巡检周期",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(15 to "15 分钟", 30 to "30 分钟", 60 to "1 小时", 180 to "3 小时").forEach { (mins, label) ->
                                    FleetChoiceChip(
                                        label = label,
                                        selected = monitorSettings.pollIntervalMinutes == mins,
                                        onClick = {
                                            onUpdateMonitorSettings(monitorSettings.copy(pollIntervalMinutes = mins))
                                        },
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("仅 Wi-Fi 环境下后台巡检", style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = monitorSettings.wifiOnlyInBackground,
                                    onCheckedChange = { wifiOnly ->
                                        onUpdateMonitorSettings(monitorSettings.copy(wifiOnlyInBackground = wifiOnly))
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PanelListCard(
    panel: ProbePanelConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleOfflineAlert: (Boolean) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val typeLabel = when {
        panel.panelType == ProbePanelType.AUTO && panel.resolvedPanelType != null ->
            "自动 (${panel.resolvedPanelType.shortLabel})"
        else -> panel.panelType.shortLabel
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                elevation = PulseElevation.None,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = panel.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PulseAccentPill(text = typeLabel)
                }
                Text(
                    text = panel.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (!panel.lastError.isNullOrBlank()) {
            Text(
                text = "上次异常：${panel.lastError}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (!panel.apiToken.isNullOrBlank()) "已加密保存 Token" else "公开模式（无 Token）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "离线告警",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                Switch(
                    checked = panel.notifyOnOffline,
                    onCheckedChange = onToggleOfflineAlert,
                )
            }
        }
    }
}

@Composable
internal fun FleetChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.55f else 0.40f)
    } else {
        ext.cardBorder
    }

    Box(
        modifier = modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(bg)
            .border(width = if (selected) 1.dp else 0.8.dp, color = borderColor, shape = PulseArchitecturalShapes.SmallChip)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
internal fun fleetTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = NodePulseThemeTokens.extendedColors.cardSurface,
    unfocusedContainerColor = NodePulseThemeTokens.extendedColors.cardSurface,
    unfocusedBorderColor = NodePulseThemeTokens.extendedColors.cardBorder,
)

internal object FleetSemanticColors {
    @Composable
    fun onlineAccent(): Color = MaterialTheme.colorScheme.primary

    @Composable
    fun onlineContainer(): Color = MaterialTheme.colorScheme.primaryContainer

    @Composable
    fun onlineOnContainer(): Color = MaterialTheme.colorScheme.onPrimaryContainer

    @Composable
    fun warningAccent(): Color = NodePulseThemeTokens.extendedColors.onDrumstickContainer

    @Composable
    fun warningContainer(): Color = NodePulseThemeTokens.extendedColors.drumstickContainer

    @Composable
    fun offlineAccent(): Color = MaterialTheme.colorScheme.error

    @Composable
    fun offlineContainer(): Color = MaterialTheme.colorScheme.errorContainer

    @Composable
    fun offlineOnContainer(): Color = MaterialTheme.colorScheme.onErrorContainer
}
