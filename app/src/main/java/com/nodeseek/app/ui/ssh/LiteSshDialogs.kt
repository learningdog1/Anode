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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseDialogTone
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
) {
    val isEditing = existing != null
    var alias by rememberSaveable(existing?.id) { mutableStateOf(existing?.alias.orEmpty()) }
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
    var secret by rememberSaveable(existing?.id) { mutableStateOf("") }
    var passphrase by rememberSaveable(existing?.id) { mutableStateOf("") }
    var showPassword by rememberSaveable(existing?.id) { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val hasSavedSecret = existing?.encryptedSecret?.isNotEmpty() == true
    val hasSavedPassphrase = !existing?.encryptedPassphrase.isNullOrEmpty()
    val parsedPort = portText.trim().toIntOrNull()
    val canSubmit = host.trim().isNotEmpty() &&
        parsedPort != null &&
        parsedPort in 1..65535 &&
        (secret.isNotEmpty() || (isEditing && hasSavedSecret && authMethod == existing?.authMethod))

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = if (isEditing) "编辑 VPS 主机" else "添加 VPS 主机",
        icon = Icons.Rounded.Dns,
        tone = PulseDialogTone.PRIMARY,
        confirmText = if (isEditing) "保存修改" else "加密保存",
        confirmEnabled = canSubmit,
        onConfirm = {
            val port = portText.trim().toIntOrNull()
            when {
                host.trim().isEmpty() -> validationError = "请输入服务器 IP 或域名"
                port == null || port !in 1..65535 -> validationError = "端口必须在 1 ~ 65535 之间"
                secret.isEmpty() && !(isEditing && hasSavedSecret && authMethod == existing?.authMethod) ->
                    validationError = if (authMethod == SshAuthMethod.PASSWORD) "请输入 SSH 密码" else "请粘贴 PEM / OpenSSH 私钥"
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
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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

            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it; validationError = null },
                label = { Text("备注名称（如 瓦工 DC6 / 日本软银）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; validationError = null },
                    label = { Text("主机地址 / IP") },
                    placeholder = { Text("203.0.113.10") },
                    singleLine = true,
                    modifier = Modifier.weight(1.7f),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { input ->
                        portText = input.filter { it.isDigit() }.take(5)
                        validationError = null
                    },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f),
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                placeholder = { Text("root") },
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
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it; validationError = null },
                    label = {
                        Text(
                            if (isEditing && hasSavedSecret && existing?.authMethod == SshAuthMethod.PASSWORD) {
                                "SSH 密码（留空保留原密码）"
                            } else {
                                "SSH 密码"
                            },
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
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
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it; validationError = null },
                    label = {
                        Text(
                            if (isEditing && hasSavedSecret && existing?.authMethod == SshAuthMethod.PRIVATE_KEY) {
                                "PEM / OpenSSH 私钥（留空保留原私钥）"
                            } else {
                                "PEM / OpenSSH 私钥内容"
                            },
                        )
                    },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 3,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = {
                        Text(
                            if (isEditing && hasSavedPassphrase) {
                                "私钥口令 Passphrase（可选，留空保留）"
                            } else {
                                "私钥口令 Passphrase（可选）"
                            },
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
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
    onSave: (id: String?, name: String, tabTitle: String, description: String, command: String) -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var tabTitle by rememberSaveable(existing?.id) { mutableStateOf(existing?.tabTitle.orEmpty()) }
    var description by rememberSaveable(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    var command by rememberSaveable(existing?.id) { mutableStateOf(existing?.command.orEmpty()) }

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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("脚本名称") },
                placeholder = { Text("例如：融合怪极简版") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tabTitle,
                onValueChange = { tabTitle = it },
                label = { Text("测评 Tab 标题（可选）") },
                placeholder = { Text("留空则默认使用脚本名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("简短说明（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Shell 命令（将自动注入 TERM=xterm-256color）") },
                placeholder = { Text("curl -sL ... | bash") },
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
 * Modal bottom sheet rendering a live NodeSeek Markdown preview (including interactive `:::: tabs`
 * and `ansi` terminal rendering) with 1-click Copy, Insert to Reply, and Publish to Review Composer.
 */
@Composable
internal fun SshExportPreviewBottomSheet(
    formattedPost: SshFormattedPost,
    maskServerIp: Boolean,
    onToggleMaskIp: (Boolean) -> Unit,
    onCopyMarkdown: (String) -> Unit,
    onPublishToComposer: (suggestedTitle: String, markdownContent: String) -> Unit,
    onInsertToReply: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors

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
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "测评排版实时预览",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (formattedPost.usesTabsLayout) {
                            "已自动组合为 NodeSeek 多标签页（:::: tabs · ${formattedPost.runCount} 项）"
                        } else {
                            "单脚本 ANSI 高保真代码块（```ansi）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PulseAccentPill(
                    text = "review 测评",
                    icon = Icons.Rounded.PostAdd,
                )
            }

            // Title + IP privacy toggle card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = if (maskServerIp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = "自动打码服务器 IP",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = "发布前自动隐藏公网 IPv4 / IPv6 后段",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = maskServerIp,
                    onCheckedChange = onToggleMaskIp,
                )
            }

            Text(
                text = "建议标题：${formattedPost.suggestedTitle}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            PulseGradientDivider()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 180.dp, max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                MarkdownRenderer(
                    markdown = formattedPost.markdownContent,
                    enableTextSelection = true,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onCopyMarkdown(formattedPost.markdownContent) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("复制 MD")
                }

                if (onInsertToReply != null) {
                    FilledTonalButton(
                        onClick = {
                            onInsertToReply(formattedPost.markdownContent)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("插入回复")
                    }
                }

                Button(
                    onClick = {
                        onPublishToComposer(formattedPost.suggestedTitle, formattedPost.markdownContent)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.25f),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("一键发测评")
                }
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
                                    )
                                }
                                if (group.runCount >= 2 && onLoadGroupToSession != null) {
                                    FilledTonalButton(
                                        onClick = { onLoadGroupToSession(group.runs) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text("整组载入", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text(
                                    text = if (isExpanded) "收起" else "展开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
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
