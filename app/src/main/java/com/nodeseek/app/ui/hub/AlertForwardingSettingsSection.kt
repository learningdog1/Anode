@file:OptIn(ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.hub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.domain.alerts.AlertEventCategory
import com.nodeseek.app.domain.alerts.AlertForwarder
import com.nodeseek.app.domain.alerts.AlertForwardingConfig
import com.nodeseek.app.domain.alerts.BarkForwardConfig
import com.nodeseek.app.domain.alerts.ChannelDeliveryResult
import com.nodeseek.app.domain.alerts.TelegramForwardConfig
import com.nodeseek.app.domain.alerts.WebhookForwardConfig
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import kotlinx.coroutines.launch

@Composable
fun AlertForwardingSettingsSection(
    alertForwarder: AlertForwarder,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val config by alertForwarder.configFlow.collectAsStateWithLifecycle(
        initialValue = AlertForwardingConfig(),
    )

    var isSendingTest by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<List<ChannelDeliveryResult>>(emptyList()) }

    var telegramExpanded by rememberSaveable { mutableStateOf(false) }
    var barkExpanded by rememberSaveable { mutableStateOf(false) }
    var webhookExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "监控告警外部推送转发",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(
                    shape = PulseArchitecturalShapes.ElevatedTile,
                    elevation = PulseElevation.Card,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "启用外部推送转发",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "同步转发探针掉线、流量超标、补货与雷达命中事件（Cookie 隔离请求）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            alertForwarder.updateConfig { it.copy(enabled = enabled) }
                        }
                    },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "转发事件类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AlertEventCategory.entries.forEach { category ->
                        val selected = category in config.enabledEvents
                        FilterChip(
                            selected = selected,
                            onClick = {
                                scope.launch {
                                    alertForwarder.updateConfig { current ->
                                        val nextEvents = if (category in current.enabledEvents) {
                                            current.enabledEvents - category
                                        } else {
                                            current.enabledEvents + category
                                        }
                                        current.copy(enabledEvents = nextEvents)
                                    }
                                }
                            },
                            label = { Text(category.displayName) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            TelegramChannelEditor(
                config = config.telegram,
                expanded = telegramExpanded,
                onToggleExpanded = { telegramExpanded = !telegramExpanded },
                onSave = { updated ->
                    scope.launch {
                        alertForwarder.updateConfig { it.copy(telegram = updated) }
                        snackbarHostState.showSnackbar("已保存 Telegram Bot 转发配置")
                    }
                },
            )

            BarkChannelEditor(
                config = config.bark,
                expanded = barkExpanded,
                onToggleExpanded = { barkExpanded = !barkExpanded },
                onSave = { updated ->
                    scope.launch {
                        alertForwarder.updateConfig { it.copy(bark = updated) }
                        snackbarHostState.showSnackbar("已保存 Bark 转发配置")
                    }
                },
            )

            WebhookChannelEditor(
                config = config.webhook,
                expanded = webhookExpanded,
                onToggleExpanded = { webhookExpanded = !webhookExpanded },
                onSave = { updated ->
                    scope.launch {
                        alertForwarder.updateConfig { it.copy(webhook = updated) }
                        snackbarHostState.showSnackbar("已保存 Webhook 转发配置")
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        if (isSendingTest) return@FilledTonalButton
                        scope.launch {
                            isSendingTest = true
                            testResults = emptyList()
                            try {
                                val results = alertForwarder.sendTestAlert()
                                testResults = results
                                val summary = if (results.isEmpty()) {
                                    "请先开启至少一个推送通道（Telegram / Bark / Webhook）"
                                } else {
                                    val okCount = results.count { it.success }
                                    "测试告警已完成：$okCount/${results.size} 个通道成功"
                                }
                                snackbarHostState.showSnackbar(summary)
                            } finally {
                                isSendingTest = false
                            }
                        }
                    },
                    enabled = !isSendingTest,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    if (isSendingTest) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("发送中…")
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("发送测试告警")
                    }
                }
            }

            if (testResults.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PulseArchitecturalShapes.CodeBlock)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    testResults.forEach { result ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (result.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = if (result.success) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = buildString {
                                    append(result.channelName)
                                    append("：")
                                    if (result.success) {
                                        append("发送成功")
                                        result.httpStatus?.let { append(" (HTTP $it)") }
                                    } else {
                                        append(result.errorMessage ?: "发送失败")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelAccordionHeader(
    title: String,
    subtitle: String,
    enabled: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.CodeBlock)
            .clickable(onClick = onToggleExpanded)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun TelegramChannelEditor(
    config: TelegramForwardConfig,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSave: (TelegramForwardConfig) -> Unit,
) {
    var botToken by remember { mutableStateOf(config.botToken) }
    var chatId by remember { mutableStateOf(config.chatId) }
    var silent by remember { mutableStateOf(config.disableNotification) }
    var showToken by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        botToken = config.botToken
        chatId = config.chatId
        silent = config.disableNotification
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.CodeBlock)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                PulseArchitecturalShapes.CodeBlock,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ChannelAccordionHeader(
            title = "Telegram Bot",
            subtitle = if (config.chatId.isNotBlank()) "Chat ID: ${config.chatId}" else "未配置 Bot Token / Chat ID",
            enabled = config.enabled,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            onEnabledChange = { enabled ->
                onSave(config.copy(enabled = enabled, botToken = botToken.trim(), chatId = chatId.trim(), disableNotification = silent))
            },
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = botToken,
                    onValueChange = { botToken = it },
                    label = { Text("Bot Token") },
                    placeholder = { Text("123456:ABC-DEF...") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showToken) "隐藏 Token" else "显示 Token",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("Chat ID / 频道用户名") },
                    placeholder = { Text("例如 123456789 或 @my_channel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("静默推送 (disable_notification)", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = silent, onCheckedChange = { silent = it })
                }
                OutlinedButton(
                    onClick = {
                        onSave(
                            config.copy(
                                botToken = botToken.trim(),
                                chatId = chatId.trim(),
                                disableNotification = silent,
                            ),
                        )
                    },
                ) {
                    Text("保存 Telegram 配置")
                }
            }
        }
    }
}

@Composable
private fun BarkChannelEditor(
    config: BarkForwardConfig,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSave: (BarkForwardConfig) -> Unit,
) {
    var serverUrl by remember { mutableStateOf(config.serverUrl) }
    var deviceKey by remember { mutableStateOf(config.deviceKey) }
    var group by remember { mutableStateOf(config.group) }
    var showKey by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        serverUrl = config.serverUrl
        deviceKey = config.deviceKey
        group = config.group
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.CodeBlock)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                PulseArchitecturalShapes.CodeBlock,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ChannelAccordionHeader(
            title = "Bark (iOS / macOS)",
            subtitle = if (config.deviceKey.isNotBlank()) serverUrl.ifBlank { BarkForwardConfig.DEFAULT_BARK_SERVER_URL } else "未配置 Device Key",
            enabled = config.enabled,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            onEnabledChange = { enabled ->
                onSave(
                    config.copy(
                        enabled = enabled,
                        serverUrl = serverUrl.trim().ifBlank { BarkForwardConfig.DEFAULT_BARK_SERVER_URL },
                        deviceKey = deviceKey.trim(),
                        group = group.trim().ifBlank { "Anode" },
                    ),
                )
            },
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Bark 服务端地址") },
                    placeholder = { Text(BarkForwardConfig.DEFAULT_BARK_SERVER_URL) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deviceKey,
                    onValueChange = { deviceKey = it },
                    label = { Text("Device Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showKey) "隐藏 Key" else "显示 Key",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("通知分组 (Group)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        onSave(
                            config.copy(
                                serverUrl = serverUrl.trim().ifBlank { BarkForwardConfig.DEFAULT_BARK_SERVER_URL },
                                deviceKey = deviceKey.trim(),
                                group = group.trim().ifBlank { "Anode" },
                            ),
                        )
                    },
                ) {
                    Text("保存 Bark 配置")
                }
            }
        }
    }
}

@Composable
private fun WebhookChannelEditor(
    config: WebhookForwardConfig,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSave: (WebhookForwardConfig) -> Unit,
) {
    var url by remember { mutableStateOf(config.url) }
    var secretHeaderName by remember { mutableStateOf(config.secretHeaderName) }
    var secretValue by remember { mutableStateOf(config.secretValue) }
    var showSecret by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        url = config.url
        secretHeaderName = config.secretHeaderName
        secretValue = config.secretValue
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.CodeBlock)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                PulseArchitecturalShapes.CodeBlock,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ChannelAccordionHeader(
            title = "自定义 Webhook",
            subtitle = if (config.url.isNotBlank()) "已配置 HTTPS/HTTP JSON 回调" else "未配置回调 URL",
            enabled = config.enabled,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            onEnabledChange = { enabled ->
                onSave(
                    config.copy(
                        enabled = enabled,
                        url = url.trim(),
                        secretHeaderName = secretHeaderName.trim().ifBlank { WebhookForwardConfig.DEFAULT_SECRET_HEADER },
                        secretValue = secretValue.trim(),
                    ),
                )
            },
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Webhook URL") },
                    placeholder = { Text("https://example.com/hooks/anode") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secretHeaderName,
                    onValueChange = { secretHeaderName = it },
                    label = { Text("密钥 Header 名称（可选）") },
                    placeholder = { Text(WebhookForwardConfig.DEFAULT_SECRET_HEADER) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secretValue,
                    onValueChange = { secretValue = it },
                    label = { Text("密钥 / HMAC Secret（可选）") },
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                imageVector = if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showSecret) "隐藏密钥" else "显示密钥",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        onSave(
                            config.copy(
                                url = url.trim(),
                                secretHeaderName = secretHeaderName.trim()
                                    .ifBlank { WebhookForwardConfig.DEFAULT_SECRET_HEADER },
                                secretValue = secretValue.trim(),
                            ),
                        )
                    },
                ) {
                    Text("保存 Webhook 配置")
                }
            }
        }
    }
}
