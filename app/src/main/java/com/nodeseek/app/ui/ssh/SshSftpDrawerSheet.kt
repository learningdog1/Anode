@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.ssh

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.ssh.SshSftpEntry
import com.nodeseek.app.domain.ssh.SshSftpQuickShortcut
import com.nodeseek.app.domain.ssh.SshSftpQuickShortcuts
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.ObsidianPrimary
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SshSftpDrawerSheet(
    drawer: SshSftpDrawerState,
    onDismiss: () -> Unit,
    onOpenServerEditor: () -> Unit,
    onReconnect: () -> Unit,
    onRefreshDir: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (remotePath: String, preferEditMode: Boolean) -> Unit,
    onQuickShortcut: (SshSftpQuickShortcut) -> Unit,
    onEnterEditMode: () -> Unit,
    onUpdateEditorContent: (String) -> Unit,
    onSaveFileWithBak: () -> Unit,
    onCloseFileViewer: () -> Unit,
    onUploadStream: (fileName: String, inputStream: InputStream) -> Unit,
    onCreateDirectory: (folderName: String) -> Unit,
    onDeleteEntry: (SshSftpEntry) -> Unit,
    onClearError: () -> Unit,
    onReportError: (String) -> Unit = {},
    onCopyText: (text: String, toastMessage: String) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.90f).dp
    var showMkdirDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteEntry by remember { mutableStateOf<SshSftpEntry?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = resolveDocumentDisplayName(context, uri)
            val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            if (stream != null) {
                onUploadStream(fileName, stream)
            } else {
                onReportError("无法读取所选文件（可能权限受限或文件不可用）")
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = drawer.serverAlias.ifBlank { "SFTP 文件抽屉" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            PulseAccentPill(
                                text = "SFTP · JSch",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (drawer.serverHost.isNotBlank()) {
                            Text(
                                text = drawer.serverHost,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (drawer.isConnected && drawer.openFile == null) {
                        IconButton(
                            onClick = onRefreshDir,
                            enabled = !drawer.isLoadingDir && !drawer.isOperating,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新目录",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭 SFTP 抽屉",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // 2. Credential guidance for PANEL_TERMINAL-only hosts
            if (drawer.needsDirectSshCredentials) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "需要直连 SSH 凭据才能开启 SFTP",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "「${drawer.serverAlias}」当前仅关联了探针网页终端，SFTP 文件传输与配置微调需要通过直连 SSH (ChannelSftp) 完成。请补充 IP 地址及密码/私钥后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onOpenServerEditor,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("补充直连 SSH 凭据")
                    }
                }
                return@Column
            }

            // 3. Error & backup notices
            drawer.errorMessage?.let { err ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    if (!drawer.isConnected && !drawer.isConnecting) {
                        TextButton(
                            onClick = onReconnect,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("重连", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        IconButton(
                            onClick = onClearError,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清除提示",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            drawer.lastBackupBanner?.let { banner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (drawer.isConnecting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在建立 JSch ChannelSftp 加密通道…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@Column
            }

            // 4. Quick Config & Log Shortcuts
            if (drawer.isConnected) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(SshSftpQuickShortcuts.presets, key = { it.id }) { shortcut ->
                        val isActive = drawer.openFile?.remotePath == shortcut.path ||
                            (shortcut.isDirectory && drawer.openFile == null && drawer.currentPath == shortcut.path)
                        FilterChip(
                            selected = isActive,
                            enabled = !drawer.isOperating && !drawer.isLoadingDir,
                            onClick = { onQuickShortcut(shortcut) },
                            label = {
                                Text(
                                    text = shortcut.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }

            val openFile = drawer.openFile
            if (openFile != null) {
                // 5a. File Viewer & Inline Config Editor
                SftpFileViewerAndEditor(
                    file = openFile,
                    isOperating = drawer.isOperating,
                    onCloseViewer = onCloseFileViewer,
                    onEnterEditMode = onEnterEditMode,
                    onUpdateEditorContent = onUpdateEditorContent,
                    onSaveWithBak = onSaveFileWithBak,
                    onCopyText = {
                        onCopyText(
                            if (openFile.isEditMode) openFile.editedText else openFile.originalText,
                            "已复制 ${openFile.fileName} 内容",
                        )
                    },
                )
            } else if (drawer.isConnected) {
                // 5b. Directory Path & Actions Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(
                        onClick = onNavigateUp,
                        enabled = drawer.currentPath != "/" && !drawer.isLoadingDir && !drawer.isOperating,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "返回上级目录",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = drawer.currentPath,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { showMkdirDialog = true },
                        enabled = !drawer.isLoadingDir && !drawer.isOperating,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CreateNewFolder,
                            contentDescription = "新建目录",
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    FilledTonalButton(
                        onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                        enabled = !drawer.isLoadingDir && !drawer.isOperating,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上传", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                }

                // 5c. Entries List
                if (drawer.isLoadingDir || drawer.isOperating) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (drawer.isOperating) "正在传输…" else "正在读取目录…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (drawer.entries.isEmpty() && !drawer.isLoadingDir) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseCard()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "当前目录为空",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(min = 180.dp, max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(drawer.entries, key = { it.fullPath }) { entry ->
                            SftpDirectoryEntryRow(
                                entry = entry,
                                enabled = !drawer.isLoadingDir && !drawer.isOperating,
                                onClick = {
                                    if (entry.isDirectory) {
                                        onOpenDirectory(entry.fullPath)
                                    } else {
                                        val isConfig = looksLikeEditableConfigName(entry.name)
                                        onOpenFile(entry.fullPath, isConfig)
                                    }
                                },
                                onDelete = { pendingDeleteEntry = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showMkdirDialog) {
        var folderName by rememberSaveable { mutableStateOf("") }
        val valid = folderName.trim().let { it.isNotEmpty() && !it.contains('/') && it != "." && it != ".." }
        PulseAlertDialog(
            onDismissRequest = { showMkdirDialog = false },
            title = "新建远程目录",
            message = "将在 ${drawer.currentPath} 下创建目录",
            icon = Icons.Rounded.CreateNewFolder,
            confirmText = "创建",
            confirmEnabled = valid,
            onConfirm = {
                onCreateDirectory(folderName.trim())
                showMkdirDialog = false
            },
            dismissText = "取消",
            onDismiss = { showMkdirDialog = false },
        ) {
            PulseTextField(
                value = folderName,
                onValueChange = { folderName = it },
                singleLine = true,
                label = "目录名",
                placeholder = "例如 certs 或 scripts",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    pendingDeleteEntry?.let { target ->
        PulseConfirmDialog(
            onDismissRequest = { pendingDeleteEntry = null },
            title = if (target.isDirectory) "删除远程目录？" else "删除远程文件？",
            message = "确定删除 ${target.fullPath} 吗？此操作不可撤销。",
            icon = Icons.Rounded.DeleteOutline,
            tone = PulseDialogTone.DESTRUCTIVE,
            confirmText = "确认删除",
            onConfirm = {
                onDeleteEntry(target)
                pendingDeleteEntry = null
            },
        )
    }
}

@Composable
private fun SftpDirectoryEntryRow(
    entry: SshSftpEntry,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.isSymlink) {
                    PulseAccentPill(
                        text = "软链",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val meta = buildList {
                if (!entry.isDirectory) add(formatSftpSize(entry.sizeBytes))
                if (entry.permissionsString.isNotBlank()) add(entry.permissionsString)
                if (entry.modifiedEpochSeconds > 0L) add(formatSftpMtime(entry.modifiedEpochSeconds))
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SftpFileViewerAndEditor(
    file: SshSftpOpenFileState,
    isOperating: Boolean,
    onCloseViewer: () -> Unit,
    onEnterEditMode: () -> Unit,
    onUpdateEditorContent: (String) -> Unit,
    onSaveWithBak: () -> Unit,
    onCopyText: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val codeTypography = NodePulseThemeTokens.extraTypography

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onCloseViewer,
                enabled = !file.isSaving && !isOperating,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("目录", style = MaterialTheme.typography.labelSmall)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${file.remotePath} · ${formatSftpSize(file.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!file.isBinaryDetected) {
                IconButton(
                    onClick = onCopyText,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "复制内容",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (file.canEdit && !file.isEditMode) {
                FilledTonalButton(
                    onClick = onEnterEditMode,
                    enabled = !isOperating,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (file.isTruncatedFromHead) {
            PulseAccentPill(
                text = "日志较大，已自动截取末尾 ${formatSftpSize(512L * 1024L)}（只读预览）",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else if (file.isEditMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "自动备份 .bak：保存时将先备份原配置为 ${file.fileName}.bak",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        if (file.isBinaryDetected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard()
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "检测到二进制文件内容，不支持文本预览或在线编辑",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (file.isEditMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 340.dp)
                    .clip(PulseArchitecturalShapes.CodeBlock)
                    .background(ext.codeSurface)
                    .border(0.8.dp, ext.codeBorder, PulseArchitecturalShapes.CodeBlock)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = file.editedText,
                    onValueChange = onUpdateEditorContent,
                    textStyle = codeTypography.codeBody.copy(
                        color = ext.onCodeSurface,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                    cursorBrush = SolidColor(ObsidianPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PulseGradientDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (file.isDirty) "已修改 · 待保存" else "未修改",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (file.isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onSaveWithBak,
                    enabled = !file.isSaving && !isOperating,
                ) {
                    if (file.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (file.isSaving) "正在备份并保存…" else "保存（自动备份 .bak）")
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp)
                    .clip(PulseArchitecturalShapes.CodeBlock)
                    .background(ext.codeSurface)
                    .border(0.8.dp, ext.codeBorder, PulseArchitecturalShapes.CodeBlock)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = file.originalText.ifEmpty { "(空文件)" },
                    style = codeTypography.codeBody.copy(
                        color = ext.onCodeSurface,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                )
            }
        }
    }
}

private fun looksLikeEditableConfigName(name: String): Boolean {
    val lower = name.lowercase(Locale.US)
    return lower == "sshd_config" ||
        lower == "caddyfile" ||
        lower.endsWith(".yml") ||
        lower.endsWith(".yaml") ||
        lower.endsWith(".json") ||
        lower.endsWith(".conf") ||
        lower.endsWith(".cfg") ||
        lower.endsWith(".ini") ||
        lower.endsWith(".toml") ||
        lower.endsWith(".sh") ||
        lower.endsWith(".env")
}

private fun resolveDocumentDisplayName(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
    }.getOrNull()
    val candidate = queried?.trim()?.takeIf { it.isNotEmpty() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
        ?: "uploaded_file_${System.currentTimeMillis()}"
    return candidate.replace('/', '_').replace('\\', '_')
}

private fun formatSftpSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}

private val SFTP_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)

private fun formatSftpMtime(epochSeconds: Long): String = runCatching {
    Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(SFTP_TIME_FORMATTER)
}.getOrDefault("")
