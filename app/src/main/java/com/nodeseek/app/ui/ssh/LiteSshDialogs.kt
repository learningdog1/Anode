@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.ssh

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshJumpHostResolver
import com.nodeseek.app.domain.ssh.SshPresetCategory
import com.nodeseek.app.domain.ssh.SshPresetFilter
import com.nodeseek.app.domain.ssh.SshPresetFilterOption
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshScriptPreset
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerGroupOption
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultCatalogFilter
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseDropdownMenu
import com.nodeseek.app.ui.components.PulseDropdownMenuItem
import com.nodeseek.app.ui.components.PulseSearchField
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog for creating or editing a locally encrypted SSH server profile.
 */
@Composable
internal fun SshServerEditorDialog(
    existing: SshServerProfile?,
    onDismiss: () -> Unit,
    onSave: (SshServerDraft) -> Unit,
    servers: List<SshServerProfile> = emptyList(),
) {
    val isEditing = existing != null
    val isProbeSynced = existing?.isProbeSynced == true
    var alias by rememberSaveable(existing?.id) { mutableStateOf(existing?.cleanAlias.orEmpty()) }
    var groupTag by rememberSaveable(existing?.id) { mutableStateOf(existing?.resolvedGroup.orEmpty()) }
    var host by rememberSaveable(existing?.id) { mutableStateOf(existing?.host.orEmpty()) }
    var portText by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.port ?: SshServerProfile.DEFAULT_PORT).toString())
    }
    var username by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.username ?: SshServerProfile.DEFAULT_USERNAME)
    }
    var authMethod by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.authMethod ?: SshAuthMethod.PASSWORD)
    }
    var connectionMode by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.effectiveConnectionMode ?: SshConnectionMode.DIRECT_SSH)
    }
    var secret by remember(existing?.id) { mutableStateOf("") }
    var passphrase by remember(existing?.id) { mutableStateOf("") }
    var showPassword by remember(existing?.id) { mutableStateOf(false) }
    var selectedJumpHostId by rememberSaveable(existing?.id) {
        mutableStateOf(
            SshJumpHostResolver.normalizeJumpHostId(existing?.jumpHostId)
                ?.takeIf { jumpId -> jumpId != existing?.id && (servers.isEmpty() || servers.any { it.id == jumpId }) },
        )
    }
    var jumpMenuExpanded by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val otherServers = remember(servers, existing?.id) {
        servers.filter { it.id != existing?.id }
    }
    val selectedJumpHost = remember(otherServers, selectedJumpHostId) {
        selectedJumpHostId?.let { jumpId -> otherServers.firstOrNull { it.id == jumpId } }
    }

    val hasSavedSecret = existing?.encryptedSecret?.isNotEmpty() == true
    val hasSavedPassphrase = !existing?.encryptedPassphrase.isNullOrEmpty()
    val parsedPort = portText.trim().toIntOrNull()
    val panelTerminalModeSelected = isProbeSynced && connectionMode == SshConnectionMode.PANEL_TERMINAL
    val canSubmit = parsedPort != null &&
        parsedPort in 1..65535 &&
        (
            panelTerminalModeSelected || (
                host.trim().isNotEmpty() &&
                    (secret.isNotEmpty() || (isEditing && hasSavedSecret && authMethod == existing?.authMethod))
                )
            )

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = if (isEditing) "编辑 VPS 主机" else "添加 VPS 主机",
        icon = Icons.Rounded.Dns,
        tone = PulseDialogTone.PRIMARY,
        confirmText = if (isEditing) "保存修改" else "加密保存",
        confirmEnabled = canSubmit,
        onConfirm = {
            val port = portText.trim().toIntOrNull()
            val jumpCheckError = if (servers.isNotEmpty() || selectedJumpHostId != null) {
                runCatching {
                    SshJumpHostResolver.validateJumpHostSelection(
                        serverId = existing?.id,
                        jumpHostId = selectedJumpHostId,
                        servers = servers,
                    )
                }.exceptionOrNull()?.message
            } else {
                null
            }
            when {
                !panelTerminalModeSelected && host.trim().isEmpty() -> validationError = "请输入服务器 IP 或域名"
                port == null || port !in 1..65535 -> validationError = "端口必须在 1 ~ 65535 之间"
                !panelTerminalModeSelected &&
                    secret.isEmpty() &&
                    !(isEditing && hasSavedSecret && authMethod == existing?.authMethod) ->
                    validationError = if (authMethod == SshAuthMethod.PASSWORD) "请输入 SSH 密码" else "请粘贴 PEM / OpenSSH 私钥"
                jumpCheckError != null -> validationError = jumpCheckError
                else -> {
                    onSave(
                        SshServerDraft(
                            id = existing?.id,
                            alias = alias.trim(),
                            host = host.trim(),
                            port = port,
                            username = username.trim().ifBlank { SshServerProfile.DEFAULT_USERNAME },
                            authMethod = authMethod,
                            secret = secret,
                            passphrase = passphrase.takeIf { it.isNotEmpty() },
                            keepExistingSecret = isEditing && secret.isEmpty() && authMethod == existing?.authMethod,
                            keepExistingPassphrase = isEditing && passphrase.isEmpty() && hasSavedPassphrase && authMethod == existing?.authMethod,
                            jumpHostId = selectedJumpHostId,
                            groupTag = groupTag.trim(),
                            groupEdited = true,
                            connectionMode = if (isProbeSynced) connectionMode else SshConnectionMode.DIRECT_SSH,
                            preserveProbeLink = true,
                        ),
                    )
                }
            }
        },
        dismissText = "取消",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (existing != null && existing.isProbeSynced) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "已关联探针：${existing.probePanelName ?: existing.probePanelShortLabel ?: "探针面板"} · 节点 #${existing.probeServerId}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (existing.probeRegion.isNotBlank() || existing.probeTags.isNotEmpty()) {
                        val meta = buildList {
                            if (existing.probeRegion.isNotBlank()) add(existing.probeRegion)
                            addAll(existing.probeTags)
                        }.joinToString(" · ")
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AuthMethodChoicePill(
                            label = "探针终端",
                            icon = Icons.Rounded.Terminal,
                            selected = connectionMode == SshConnectionMode.PANEL_TERMINAL,
                            onClick = {
                                connectionMode = SshConnectionMode.PANEL_TERMINAL
                                validationError = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        AuthMethodChoicePill(
                            label = "直连 SSH",
                            icon = Icons.Rounded.Dns,
                            selected = connectionMode == SshConnectionMode.DIRECT_SSH,
                            onClick = {
                                connectionMode = SshConnectionMode.DIRECT_SSH
                                validationError = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = if (panelTerminalModeSelected) {
                            if (existing.supportsPanelTerminalProtocol) {
                                "探针终端复用面板令牌经 WebSocket 连接（无需填写 SSH 密码；也可补充下方 IP/凭据作为直连备用）"
                            } else {
                                "提示：哪吒 v0 仅支持只读 API，无法使用探针终端，请切换为「直连 SSH」并填写凭据"
                            }
                        } else {
                            "直连 SSH 通过 JSch 直接连接目标主机，需填写主机地址与密码/私钥（重新同步探针时会自动保留你的凭据）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "密码与私钥仅通过 Android Keystore AES-256-GCM 加密保存在本地设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PulseTextField(
                value = alias,
                onValueChange = { alias = it; validationError = null },
                label = "备注名称",
                placeholder = "如 瓦工 DC6 / 日本软银",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val suggestedGroups = remember(servers) {
                val existingGroups = servers.mapNotNull { it.resolvedGroup?.trim()?.takeIf(String::isNotEmpty) }
                (existingGroups + SshVaultCatalogFilter.SUGGESTED_SERVER_GROUPS)
                    .distinctBy { it.lowercase() }
                    .take(8)
            }
            PulseTextField(
                value = groupTag,
                onValueChange = { groupTag = it.take(20); validationError = null },
                label = "分组标签（可选）",
                placeholder = "如 香港 / 美西 / 主力",
                singleLine = true,
                trailingIcon = if (groupTag.isNotEmpty()) {
                    {
                        IconButton(onClick = { groupTag = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清空分组",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (suggestedGroups.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    suggestedGroups.forEach { candidateGroup ->
                        val isGroupSelected = groupTag.trim().equals(candidateGroup, ignoreCase = true)
                        FilterChip(
                            selected = isGroupSelected,
                            onClick = {
                                groupTag = if (isGroupSelected) "" else candidateGroup
                            },
                            label = {
                                Text(
                                    text = candidateGroup,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseTextField(
                    value = host,
                    onValueChange = { host = it; validationError = null },
                    label = "主机地址 / IP",
                    placeholder = "203.0.113.10",
                    singleLine = true,
                    modifier = Modifier.weight(1.7f),
                )
                PulseTextField(
                    value = portText,
                    onValueChange = { input ->
                        portText = input.filter { it.isDigit() }.take(5)
                        validationError = null
                    },
                    label = "端口",
                    placeholder = "22",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f),
                )
            }

            PulseTextField(
                value = username,
                onValueChange = { username = it },
                label = "用户名",
                placeholder = "root",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Auth method switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AuthMethodChoicePill(
                    label = "密码认证",
                    icon = Icons.Rounded.Password,
                    selected = authMethod == SshAuthMethod.PASSWORD,
                    onClick = {
                        authMethod = SshAuthMethod.PASSWORD
                        validationError = null
                    },
                    modifier = Modifier.weight(1f),
                )
                AuthMethodChoicePill(
                    label = "私钥认证",
                    icon = Icons.Rounded.Key,
                    selected = authMethod == SshAuthMethod.PRIVATE_KEY,
                    onClick = {
                        authMethod = SshAuthMethod.PRIVATE_KEY
                        validationError = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (authMethod == SshAuthMethod.PASSWORD) {
                PulseTextField(
                    value = secret,
                    onValueChange = { secret = it; validationError = null },
                    label = "SSH 密码",
                    placeholder = if (isEditing && hasSavedSecret && existing?.authMethod == SshAuthMethod.PASSWORD) {
                        "留空保留原密码"
                    } else {
                        "输入 SSH 登录密码"
                    },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PulseTextField(
                    value = secret,
                    onValueChange = { secret = it; validationError = null },
                    label = if (isEditing && hasSavedSecret && existing?.authMethod == SshAuthMethod.PRIVATE_KEY) {
                        "PEM / OpenSSH 私钥（留空保留）"
                    } else {
                        "PEM / OpenSSH 私钥内容"
                    },
                    placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                    ),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                PulseTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = "私钥口令 (Passphrase)",
                    placeholder = if (isEditing && hasSavedPassphrase) {
                        "可选，留空保留原口令"
                    } else {
                        "无口令可留空"
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Jump host (ProxyJump) selector
            Box(modifier = Modifier.fillMaxWidth()) {
                val selectorShape = RoundedCornerShape(12.dp)
                val isJumpActive = selectedJumpHost != null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(selectorShape)
                        .background(
                            if (isJumpActive) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        )
                        .border(
                            width = 1.dp,
                            color = if (isJumpActive) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            },
                            shape = selectorShape,
                        )
                        .clickable { jumpMenuExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.AltRoute,
                        contentDescription = null,
                        tint = if (isJumpActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "跳板机（ProxyJump）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when {
                                selectedJumpHost != null ->
                                    "${selectedJumpHost.displayName} (${selectedJumpHost.displayAddress})"
                                otherServers.isEmpty() -> "直连（保险库暂无其他主机）"
                                else -> "直连"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isJumpActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = if (jumpMenuExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "选择跳板机",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }

                PulseDropdownMenu(
                    expanded = jumpMenuExpanded,
                    onDismissRequest = { jumpMenuExpanded = false },
                    minWidth = 240.dp,
                    maxWidth = 310.dp,
                ) {
                    PulseDropdownMenuItem(
                        text = "直连",
                        subtitle = "直接连接当前目标主机",
                        icon = Icons.Rounded.Dns,
                        selected = selectedJumpHostId == null,
                        onClick = {
                            selectedJumpHostId = null
                            validationError = null
                            jumpMenuExpanded = false
                        },
                    )
                    otherServers.forEach { candidate ->
                        val createsCycle = SshJumpHostResolver.wouldCreateCycle(
                            serverId = existing?.id,
                            candidateJumpHostId = candidate.id,
                            servers = servers,
                        )
                        PulseDropdownMenuItem(
                            text = candidate.displayName,
                            subtitle = if (createsCycle) {
                                "${candidate.displayAddress} · 会产生循环引用"
                            } else {
                                candidate.displayAddress
                            },
                            icon = Icons.AutoMirrored.Rounded.AltRoute,
                            selected = selectedJumpHostId == candidate.id,
                            enabled = !createsCycle,
                            badgeText = when {
                                createsCycle -> "循环"
                                candidate.usesJumpHost -> "链式"
                                else -> null
                            },
                            onClick = {
                                selectedJumpHostId = candidate.id
                                validationError = null
                                jumpMenuExpanded = false
                            },
                        )
                    }
                }
            }

            validationError?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AuthMethodChoicePill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * Dialog for creating or editing a user-saved custom shell script preset.
 */
@Composable
internal fun CustomSshPresetEditorDialog(
    existing: CustomSshPreset?,
    onDismiss: () -> Unit,
    onSave: (
        id: String?,
        name: String,
        tabTitle: String,
        description: String,
        command: String,
        category: SshPresetCategory,
    ) -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var tabTitle by rememberSaveable(existing?.id) { mutableStateOf(existing?.tabTitle.orEmpty()) }
    var description by rememberSaveable(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    var command by rememberSaveable(existing?.id) { mutableStateOf(existing?.command.orEmpty()) }
    var category by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.category ?: SshPresetCategory.CUSTOM)
    }

    val canSave = name.trim().isNotEmpty() && command.trim().isNotEmpty()

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = if (existing != null) "编辑自定义脚本" else "新建自定义预设脚本",
        icon = Icons.Rounded.Code,
        tone = PulseDialogTone.PRIMARY,
        confirmText = "保存脚本",
        confirmEnabled = canSave,
        onConfirm = {
            onSave(
                existing?.id,
                name.trim(),
                tabTitle.trim(),
                description.trim(),
                command.trim(),
                category,
            )
        },
        dismissText = "取消",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PulseTextField(
                value = name,
                onValueChange = { name = it },
                label = "脚本名称",
                placeholder = "例如：融合怪极简版",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "脚本分类（便于多脚本快速筛选）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        SshPresetCategory.CUSTOM to "自定义脚本",
                        SshPresetCategory.BENCHMARK to "综合测评",
                        SshPresetCategory.NETWORK to "网络 / 回程",
                        SshPresetCategory.UNLOCK to "流媒体 / 解锁",
                        SshPresetCategory.DIAGNOSTIC to "系统 / 硬件",
                    ).forEach { (cat, catLabel) ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = {
                                Text(
                                    text = catLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
            PulseTextField(
                value = tabTitle,
                onValueChange = { tabTitle = it },
                label = "测评 Tab 标题（可选）",
                placeholder = "留空则默认使用脚本名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PulseTextField(
                value = description,
                onValueChange = { description = it },
                label = "简短说明（可选）",
                placeholder = "补充脚本用途或注意事项",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PulseTextField(
                value = command,
                onValueChange = { command = it },
                label = "Shell 命令（自动注入 TERM）",
                placeholder = "curl -sL ... | bash",
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * High-contrast TOFU security warning dialog displayed when a known SSH server's SHA-256 host key
 * differs from the fingerprint recorded on first connection.
 */
@Composable
internal fun HostKeyMismatchWarningDialog(
    prompt: PendingHostKeyMismatchPrompt,
    onDismiss: () -> Unit,
    onTrustAndContinue: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = "主机密钥指纹已变更",
        message = "连接「${prompt.serverAlias}」(${prompt.actualHostKey.host}:${prompt.actualHostKey.port}) 时检测到服务器主机密钥与本地记录不一致。如果你刚刚重装过系统可选择信任新指纹；否则可能存在中间人攻击风险，请立即取消。",
        icon = Icons.Rounded.WarningAmber,
        tone = PulseDialogTone.DESTRUCTIVE,
        confirmText = "信任新指纹并继续",
        onConfirm = onTrustAndContinue,
        dismissText = "取消连接",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.CodeBlock)
                .background(ext.codeSurface)
                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), PulseArchitecturalShapes.CodeBlock)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "算法类型：${prompt.actualHostKey.algorithm}",
                style = MaterialTheme.typography.labelSmall,
                color = ext.onCodeMuted,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "已记录指纹 (TOFU)",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.onCodeSurface,
                )
                Text(
                    text = prompt.expectedFingerprint,
                    style = NodePulseThemeTokens.extraTypography.codeLabel.copy(fontSize = 11.sp),
                    color = ext.onCodeSurface,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "当前服务器指纹",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = prompt.actualHostKey.fingerprintSha256,
                    style = NodePulseThemeTokens.extraTypography.codeLabel.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Bottom sheet showing recent persisted SSH runs grouped by machine remark name (`备注名称`),
 * allowing users to reload single runs or an entire machine's run group into the active export session.
 */
@Composable
internal fun SshHistoryBottomSheet(
    recentRuns: List<SshExecutionRecord>,
    onLoadToSession: (SshExecutionRecord) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onDismiss: () -> Unit,
    servers: List<SshServerProfile> = emptyList(),
    onLoadGroupToSession: ((List<SshExecutionRecord>) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors
    val groupedRuns = remember(recentRuns, servers) {
        com.nodeseek.app.domain.ssh.SshHistoryGrouping.groupRunsByServerRemark(
            runs = recentRuns,
            servers = servers,
        )
    }
    var collapsedGroupKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = ext.cardSurfaceElevated,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "最近本地测试记录（${recentRuns.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (groupedRuns.isNotEmpty()) {
                    PulseAccentPill(
                        text = "${groupedRuns.size} 台主机",
                        icon = Icons.Rounded.Dns,
                    )
                }
            }

            if (groupedRuns.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无历史测试记录，运行任意脚本后将自动按机器备注分组保存",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(groupedRuns, key = { "grp_${it.groupKey}" }) { group ->
                        val isExpanded = group.groupKey !in collapsedGroupKeys
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseCard(shape = RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        collapsedGroupKeys = if (isExpanded) {
                                            collapsedGroupKeys + group.groupKey
                                        } else {
                                            collapsedGroupKeys - group.groupKey
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Dns,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = group.serverAlias,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        PulseAccentPill(
                                            text = "${group.runCount} 条",
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                    Text(
                                        text = "最近测试：${formatRunTimestamp(group.latestStartedAtEpochMillis)}",
                                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (group.runCount >= 2 && onLoadGroupToSession != null) {
                                    FilledTonalButton(
                                        onClick = { onLoadGroupToSession(group.runs) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "整组载入",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                                Text(
                                    text = if (isExpanded) "收起" else "展开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }

                            if (isExpanded) {
                                PulseGradientDivider()
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    group.runs.forEach { run ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(11.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f))
                                                .clickable { onLoadToSession(run) }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Terminal,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                Text(
                                                    text = run.presetName,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "${formatRunTimestamp(run.startedAtEpochMillis)} · 耗时 ${formatDurationShort(run.durationMillis)} · ${runStatusLabel(run.status, run.exitCode)}",
                                                    style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            IconButton(
                                                onClick = { onDeleteRecord(run.id) },
                                                modifier = Modifier.size(30.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DeleteOutline,
                                                    contentDescription = "删除记录",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(17.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun formatDurationShort(durationMillis: Long): String {
    val totalSec = (durationMillis / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return if (mins > 0) {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    } else {
        "${secs}s"
    }
}

internal fun formatElapsedClock(durationMillis: Long): String {
    val totalSec = (durationMillis / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

internal fun formatRunTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "刚刚"
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epochMillis))
    }.getOrDefault("")
}

internal fun runStatusLabel(status: SshRunStatus, exitCode: Int?): String = when (status) {
    SshRunStatus.RUNNING -> "运行中"
    SshRunStatus.SUCCEEDED -> "成功 (0)"
    SshRunStatus.FAILED -> if (exitCode != null) "退出码 $exitCode" else "失败"
    SshRunStatus.CANCELLED -> "已中断"
}

/**
 * 1-click VPS `iperf3` server management dialog (`开启 iperf3 服务端` / `停止 iperf3` / 防火墙指引):
 * - Custom port input (default `5201`, validated `1..65535`)
 * - Server selector across saved SSH Vault servers (or quick jump to add a new server)
 * - Optional automatic firewall rule opening (`ufw` / `firewalld` / `iptables`) + copyable command reference
 */
@Composable
fun Iperf3ServerControlDialog(
    servers: List<SshServerProfile>,
    selectedServer: SshServerProfile?,
    portText: String,
    autoOpenFirewall: Boolean,
    isRunning: Boolean,
    onSelectServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onPortTextChange: (String) -> Unit,
    onToggleAutoOpenFirewall: (Boolean) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    currentAttachment: Iperf3ServerAttachment? = null,
    onDisconnectExternalServer: (() -> Unit)? = null,
    onCopyCommand: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val validPort = Iperf3RemoteServerCommands.validatePort(portText)
    val portInvalid = portText.isNotBlank() && validPort == null
    val effectivePortForPreview = validPort ?: Iperf3RemoteServerCommands.DEFAULT_PORT
    val activeAttachment = currentAttachment?.takeIf {
        selectedServer != null && it.serverId == selectedServer.id && it.port == validPort
    }
    val isSelectedRoot = selectedServer?.username?.trim()?.equals("root", ignoreCase = true) == true
    val firewallHints = remember(effectivePortForPreview, isSelectedRoot) {
        Iperf3RemoteServerCommands.buildFirewallHintCommands(
            port = effectivePortForPreview,
            isRoot = isSelectedRoot,
        )
    }
    var serverMenuExpanded by remember { mutableStateOf(false) }
    var showFirewallCommands by rememberSaveable { mutableStateOf(false) }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = "VPS iperf3 服务端管理",
        message = "执行前自动检测 iperf3 是否安装及端口占用，仅管理本应用启动的实例（校验 PID / 启动时间 / 端口）；若复用已有外部服务，仅提供断开本地连接。",
        icon = Icons.Rounded.Speed,
        tone = PulseDialogTone.PRIMARY,
        confirmText = if (selectedServer == null) "先添加 VPS 主机" else "检测并开启服务端",
        confirmEnabled = !isRunning && (selectedServer == null || validPort != null),
        onConfirm = {
            if (selectedServer == null) {
                onAddServer()
            } else if (validPort != null) {
                onStartServer()
            }
        },
        dismissText = "关闭",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Target VPS selector
            if (servers.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                        .clickable(onClick = onAddServer)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "本地保险库暂无 SSH 主机",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "点击添加目标 VPS 的 SSH 信息即可一键安装/开启 iperf3",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth()) {
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), shape)
                            .clickable(enabled = servers.size > 1) { serverMenuExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "目标 VPS 主机",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = selectedServer?.let { "${it.displayName} (${it.displayAddress})" } ?: "请选择主机",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (servers.size > 1) {
                            Icon(
                                imageVector = if (serverMenuExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = "切换主机",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    PulseDropdownMenu(
                        expanded = serverMenuExpanded,
                        onDismissRequest = { serverMenuExpanded = false },
                        minWidth = 240.dp,
                        maxWidth = 310.dp,
                    ) {
                        servers.forEach { srv ->
                            PulseDropdownMenuItem(
                                text = srv.displayName,
                                subtitle = srv.displayAddress,
                                icon = Icons.Rounded.Dns,
                                selected = srv.id == selectedServer?.id,
                                onClick = {
                                    onSelectServer(srv.id)
                                    serverMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Custom Port input + quick presets
            PulseTextField(
                value = portText,
                onValueChange = onPortTextChange,
                label = "iperf3 监听端口（1 ~ 65535）",
                placeholder = "默认 5201",
                isError = portInvalid || portText.isBlank(),
                supportingText = if (portInvalid || portText.isBlank()) {
                    "请输入 1 ~ 65535 之间的有效端口号"
                } else {
                    "后台启动命令：iperf3 -s -p $validPort -D"
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(5201 to "5201 默认", 5202 to "5202", 15201 to "15201").forEach { (presetPort, label) ->
                    FilterChip(
                        selected = validPort == presetPort,
                        onClick = { onPortTextChange(presetPort.toString()) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium.withTabularNumbers()) },
                    )
                }
            }

            // Firewall optional auto-open toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = if (autoOpenFirewall) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = "自动放行系统防火墙",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = "启用 ufw / firewalld / iptables 时自动放行 TCP 端口",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = autoOpenFirewall,
                    onCheckedChange = onToggleAutoOpenFirewall,
                )
            }

            // Collapsible firewall command reference
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showFirewallCommands = !showFirewallCommands }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "查看防火墙放行命令（ufw / firewalld / iptables）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = if (showFirewallCommands) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (showFirewallCommands) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PulseArchitecturalShapes.CodeBlock)
                        .background(ext.codeSurface)
                        .border(0.8.dp, ext.codeBorder, PulseArchitecturalShapes.CodeBlock)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "UFW (Debian/Ubuntu)" to firewallHints.ufwCommand,
                        "Firewalld (CentOS/RHEL/Fedora)" to firewallHints.firewalldCommand,
                        "iptables (通用)" to firewallHints.iptablesCommand,
                    ).forEach { (title, cmd) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ext.onCodeMuted,
                                )
                                Text(
                                    text = cmd,
                                    style = NodePulseThemeTokens.extraTypography.codeLabel.copy(fontSize = 11.sp),
                                    color = ext.onCodeSurface,
                                )
                            }
                            if (onCopyCommand != null) {
                                IconButton(
                                    onClick = { onCopyCommand(cmd) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = "复制放行命令",
                                        tint = ext.onCodeSurface,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "提示：阿里云 / 腾讯云 / AWS / Oracle Cloud 等还需在云控制台「安全组」放行 TCP $effectivePortForPreview。",
                        style = MaterialTheme.typography.labelSmall,
                        color = ext.onCodeMuted,
                    )
                }
            }

            // Ownership status & Stop / Disconnect action
            if (selectedServer != null) {
                if (activeAttachment?.isExternalReused == true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "端口 ${activeAttachment.port} 当前复用的是 VPS 上已有的外部 iperf3 服务（非本应用启动）。为避免误杀其他任务，仅可断开本地连接，不会远程终止该进程。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    OutlinedButton(
                        onClick = { (onDisconnectExternalServer ?: onStopServer)() },
                        enabled = !isRunning && validPort != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("断开与「${selectedServer.displayName}」外部 iperf3 服务的本地连接")
                    }
                } else {
                    activeAttachment?.ownedInstance?.let { owned ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "已绑定本应用启动的实例（端口 ${owned.port} · PID ${owned.pid}），停止时将严格校验 PID、启动时间与端口。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onStopServer,
                        enabled = !isRunning && validPort != null,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        val stopLabel = activeAttachment?.ownedInstance?.let { owned ->
                            "停止本应用在「${selectedServer.displayName}」启动的 iperf3 (PID ${owned.pid})"
                        } ?: "停止本应用在「${selectedServer.displayName}」启动的 iperf3 服务端"
                        Text(stopLabel)
                    }
                }
            }
        }
    }
}

/**
 * Confirmation dialog presented when `iperf3` is not yet installed on the target VPS, showing
 * the detected `/etc/os-release` distribution and exact package-manager command (`root` or `sudo`).
 */
@Composable
fun Iperf3InstallConfirmDialog(
    prompt: PendingIperf3InstallPrompt,
    onConfirmInstallAndStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val privLabel = when {
        prompt.probe.isRoot -> "root 账户直装"
        prompt.probe.hasSudo -> "非 root 账户（自动通过 sudo 提权）"
        else -> "非 root 账户（未检测到 sudo，可能因权限不足失败）"
    }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = "未安装 iperf3，是否一键安装？",
        message = "检测到「${prompt.serverAlias}」尚未安装 iperf3。已根据 /etc/os-release 自动适配安装脚本，安装完成后将立即在后台启动端口 ${prompt.port}。",
        icon = Icons.Rounded.Download,
        tone = PulseDialogTone.PRIMARY,
        confirmText = "一键安装并启动",
        onConfirm = onConfirmInstallAndStart,
        dismissText = "取消",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "识别发行版：${prompt.probe.osRelease.displayTitle}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "包管理器：${prompt.probe.distroFamily.displayLabel} (${prompt.probe.distroFamily.packageManagerLabel}) · $privLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!prompt.probe.isRoot && !prompt.probe.hasSudo) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "当前非 root 且未检测到 sudo，建议改用 root 账号连接后再安装。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.CodeBlock)
                    .background(ext.codeSurface)
                    .border(0.8.dp, ext.codeBorder, PulseArchitecturalShapes.CodeBlock)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "将执行的安装命令",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.onCodeMuted,
                )
                Text(
                    text = prompt.probe.installCommand,
                    style = NodePulseThemeTokens.extraTypography.codeLabel.copy(fontSize = 11.sp),
                    color = ext.onCodeSurface,
                )
            }
        }
    }
}

/**
 * Compact architectural search input shared by the inline SSH Server Vault, Preset Catalog,
 * Quick Picker sheets, and Quick Insert sheet.
 */
@Composable
internal fun SshCompactSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    matchCountSummary: String? = null,
    onClear: () -> Unit = { onValueChange("") },
) {
    PulseSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        elevated = false,
        minHeight = 40.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onClear = onClear,
        trailingContent = if (!matchCountSummary.isNullOrBlank()) {
            {
                Text(
                    text = matchCountSummary,
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        } else {
            null
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Full-capacity modal bottom sheet for searching, group-filtering, and switching among 50+ VPS
 * entries in 1 tap without scrolling past the benchmark console.
 */
@Composable
internal fun SshServerQuickPickerSheet(
    servers: List<SshServerProfile>,
    filteredServers: List<SshServerProfile>,
    selectedServerId: String?,
    searchQuery: String,
    groupOptions: List<SshServerGroupOption>,
    selectedGroupKey: String,
    isRunning: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectGroup: (String) -> Unit,
    onSelectServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (SshServerProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors
    val serversById = remember(servers) { servers.associateBy { it.id } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = ext.cardSurfaceElevated,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = "快速检索主机（共 ${servers.size} 台）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "支持别名 / IP / 端口 / 用户名 / 分组标签即时匹配",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FilledTonalButton(
                    onClick = {
                        onDismiss()
                        onAddServer()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加主机", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }

            SshCompactSearchBar(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                onClear = onClearSearch,
                placeholder = "搜索主机别名 / IP / 用户名 / 分组…",
                matchCountSummary = if (searchQuery.isNotBlank() || selectedGroupKey != SshServerGroupOption.KEY_ALL) {
                    "${filteredServers.size}/${servers.size}"
                } else {
                    null
                },
            )

            if (groupOptions.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(groupOptions, key = { "sheet_grp_${it.key}" }) { option ->
                        val active = option.key.equals(selectedGroupKey, ignoreCase = true)
                        FilterChip(
                            selected = active,
                            onClick = {
                                onSelectGroup(
                                    if (active && option.key != SshServerGroupOption.KEY_ALL) {
                                        SshServerGroupOption.KEY_ALL
                                    } else {
                                        option.key
                                    },
                                )
                            },
                            label = {
                                Text(
                                    text = "${option.label} (${option.count})",
                                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                )
                            },
                        )
                    }
                }
            }

            if (filteredServers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(shape = RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "没有找到匹配的 VPS 主机",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "请尝试缩短关键词或重置分组筛选",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            onClearSearch()
                            onSelectGroup(SshServerGroupOption.KEY_ALL)
                        },
                    ) {
                        Text("重置搜索与分组")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(filteredServers, key = { "picker_srv_${it.id}" }) { server ->
                        val isSelected = server.id == selectedServerId
                        val jumpName = server.jumpHostId?.let { serversById[it]?.displayName }
                        val shape = RoundedCornerShape(14.dp)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseCard(
                                    shape = shape,
                                    borderColor = if (isSelected) MaterialTheme.colorScheme.primary else null,
                                    borderWidth = if (isSelected) 1.3.dp else 0.9.dp,
                                )
                                .clickable(enabled = !isRunning) {
                                    onSelectServer(server.id)
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.Dns,
                                    contentDescription = null,
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(15.dp),
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = server.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (isSelected) {
                                        PulseAccentPill(text = "当前目标")
                                    }
                                    server.resolvedGroup?.let { grp ->
                                        PulseAccentPill(
                                            text = grp,
                                            icon = Icons.Rounded.LocalOffer,
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                                Text(
                                    text = server.displayAddress,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (server.isProbeSynced) {
                                        PulseAccentPill(
                                            text = server.probePanelShortLabel ?: "探针",
                                            icon = Icons.Rounded.CloudSync,
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                        PulseAccentPill(
                                            text = server.effectiveConnectionMode.label,
                                            icon = if (server.effectiveConnectionMode == SshConnectionMode.PANEL_TERMINAL) {
                                                Icons.Rounded.Terminal
                                            } else {
                                                Icons.Rounded.Dns
                                            },
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                    if (server.hasDirectSshCredentials) {
                                        PulseAccentPill(
                                            text = if (server.authMethod == SshAuthMethod.PRIVATE_KEY) "私钥" else "密码",
                                            icon = if (server.authMethod == SshAuthMethod.PRIVATE_KEY) Icons.Rounded.Key else Icons.Rounded.Password,
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else if (server.isProbeSynced) {
                                        PulseAccentPill(
                                            text = "待补 SSH 凭据",
                                            icon = Icons.Rounded.Lock,
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (server.hasTrustedHostKey) {
                                        PulseAccentPill(
                                            text = "TOFU 已校验",
                                            icon = Icons.Rounded.VerifiedUser,
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                    if (server.usesJumpHost) {
                                        PulseAccentPill(
                                            text = if (!jumpName.isNullOrBlank()) "跳板 · $jumpName" else "跳板",
                                            icon = Icons.AutoMirrored.Rounded.AltRoute,
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = {
                                    onDismiss()
                                    onEditServer(server)
                                },
                                enabled = !isRunning,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "编辑主机",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-capacity modal bottom sheet for searching, category-filtering, and picking among built-in
 * and large collections of user-defined custom benchmark scripts.
 */
@Composable
internal fun SshPresetQuickPickerSheet(
    allPresets: List<SshScriptPreset>,
    filteredPresets: List<SshScriptPreset>,
    customPresets: List<CustomSshPreset>,
    selectedPresetId: String,
    searchQuery: String,
    filterOptions: List<SshPresetFilterOption>,
    selectedFilter: SshPresetFilter,
    isRunning: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectFilter: (SshPresetFilter) -> Unit,
    onSelectPreset: (String) -> Unit,
    onAddCustomPreset: () -> Unit,
    onEditCustomPreset: (CustomSshPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors
    val customById = remember(customPresets) { customPresets.associateBy { it.id } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = ext.cardSurfaceElevated,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = "快速选择测评脚本（共 ${allPresets.size} 个）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "按名称 / 功能描述 / 命令关键字即时检索",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FilledTonalButton(
                    onClick = {
                        onDismiss()
                        onAddCustomPreset()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("自定义", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }

            SshCompactSearchBar(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                onClear = onClearSearch,
                placeholder = "搜索脚本名称 / 描述 / 命令关键字（如 fio、goecs、nexttrace）…",
                matchCountSummary = if (searchQuery.isNotBlank() || selectedFilter != SshPresetFilter.ALL) {
                    "${filteredPresets.size}/${allPresets.size}"
                } else {
                    null
                },
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(filterOptions, key = { "sheet_preset_flt_${it.filter.name}" }) { option ->
                    val active = option.filter == selectedFilter
                    FilterChip(
                        selected = active,
                        onClick = {
                            onSelectFilter(
                                if (active && option.filter != SshPresetFilter.ALL) {
                                    SshPresetFilter.ALL
                                } else {
                                    option.filter
                                },
                            )
                        },
                        label = {
                            Text(
                                text = "${option.label} (${option.count})",
                                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            )
                        },
                    )
                }
            }

            if (filteredPresets.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(shape = RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (selectedFilter == SshPresetFilter.CUSTOM && customPresets.isEmpty()) {
                            "暂未添加自定义脚本"
                        } else {
                            "没有找到匹配的测评脚本"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (selectedFilter == SshPresetFilter.CUSTOM && customPresets.isEmpty()) {
                            "点击下方按钮即可保存你常用的私有测机命令，一键无人值守执行"
                        } else {
                            "请尝试更换关键词或切换回「全部」分类"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selectedFilter == SshPresetFilter.CUSTOM && customPresets.isEmpty()) {
                        Button(
                            onClick = {
                                onDismiss()
                                onAddCustomPreset()
                            },
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建自定义脚本")
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                onClearSearch()
                                onSelectFilter(SshPresetFilter.ALL)
                            },
                        ) {
                            Text("重置搜索与分类")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(filteredPresets, key = { "picker_preset_${it.id}" }) { preset ->
                        val isSelected = preset.id == selectedPresetId
                        val customObj = if (preset.isCustom) customById[preset.id] else null
                        val shape = RoundedCornerShape(14.dp)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseCard(
                                    shape = shape,
                                    borderColor = if (isSelected) MaterialTheme.colorScheme.primary else null,
                                    borderWidth = if (isSelected) 1.3.dp else 0.9.dp,
                                )
                                .clickable(enabled = !isRunning) {
                                    onSelectPreset(preset.id)
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = when {
                                        isSelected -> Icons.Rounded.Check
                                        preset.isCustom -> Icons.Rounded.Code
                                        else -> Icons.Rounded.Terminal
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(15.dp),
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (isSelected) {
                                        PulseAccentPill(text = "当前选中")
                                    }
                                    PulseAccentPill(
                                        text = if (preset.isCustom && preset.category == SshPresetCategory.CUSTOM) {
                                            "自定义"
                                        } else if (preset.isCustom) {
                                            "自定义 · ${preset.category.label}"
                                        } else {
                                            preset.category.label
                                        },
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    PulseAccentPill(
                                        text = preset.estimatedDurationLabel,
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                                Text(
                                    text = preset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = preset.commandTemplate.lineSequence().firstOrNull().orEmpty(),
                                    style = NodePulseThemeTokens.extraTypography.microLabel.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (customObj != null) {
                                IconButton(
                                    onClick = {
                                        onDismiss()
                                        onEditCustomPreset(customObj)
                                    },
                                    enabled = !isRunning,
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "编辑脚本",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
