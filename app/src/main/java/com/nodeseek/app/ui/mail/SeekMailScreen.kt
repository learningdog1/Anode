package com.nodeseek.app.ui.mail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ForwardToInbox
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailAuthTelemetry
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailRateLimit
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailbox
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.RelativeTimeText
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val FULL_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeekMailScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    initialMailboxId: Long = 0L,
    initialMailId: Long = 0L,
    showBackButton: Boolean = true,
    bottomContentPadding: Dp = 0.dp,
    reselectTrigger: Int = 0,
    onMailDetailOpenChanged: ((Boolean) -> Unit)? = null,
    container: AppContainer = LocalAppContainer.current,
    previewUiState: SeekMailUiState? = null,
) {
    val viewModel: SeekMailViewModel = viewModel(
        key = "seek_mail_${initialMailboxId}_${initialMailId}",
        factory = SeekMailViewModel.factory(
            container = container,
            initialMailboxId = initialMailboxId,
            initialMailId = initialMailId,
        ),
    )
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingExportTarget by remember { mutableStateOf<PendingExportTarget?>(null) }
    var pendingExternalUrl by rememberSaveable { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { destinationUri ->
        val target = pendingExportTarget
        pendingExportTarget = null
        if (destinationUri != null && target != null) {
            coroutineScope.launch {
                val bytesResult = viewModel.fetchExportBytes(target)
                bytesResult.onSuccess { bytes ->
                    val writeResult = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                                output.write(bytes)
                                output.flush()
                            } ?: error("无法打开文件输出流")
                        }
                    }
                    writeResult.onSuccess {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        snackbarHostState.showSnackbar("已保存：${target.suggestedFileName}")
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar("保存失败：${err.message ?: "写入异常"}")
                    }
                }.onFailure { err ->
                    snackbarHostState.showSnackbar("下载失败：${err.message ?: "请稍后重试"}")
                }
            }
        }
    }

    val startExport: (PendingExportTarget) -> Unit = { target ->
        if (uiState.exportingKey == null) {
            pendingExportTarget = target
            createDocumentLauncher.launch(target.suggestedFileName)
        }
    }

    val copyWithFeedback: (label: String, value: String, message: String) -> Unit = { label, value, message ->
        copyTextToClipboard(context, label, value)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(uiState.openedMailId != null) {
        onMailDetailOpenChanged?.invoke(uiState.openedMailId != null)
    }
    DisposableEffect(Unit) {
        onDispose { onMailDetailOpenChanged?.invoke(false) }
    }
    LaunchedEffect(reselectTrigger) {
        if (reselectTrigger > 0) viewModel.refresh()
    }

    BackHandler(enabled = uiState.openedMailId != null) {
        viewModel.closeMailDetail()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                SeekMailTopBar(
                    uiState = uiState,
                    onBack = onBack,
                    onToggleSearch = viewModel::toggleSearchBar,
                    onOpenContacts = viewModel::openContactsSheet,
                    onOpenCreateMailbox = viewModel::openCreateMailboxSheet,
                    onOpenSettings = onOpenSettings,
                    showBackButton = showBackButton,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (!uiState.hasConfiguredKey) {
                    SeekMailOnboardingView(
                        isVerifying = uiState.isVerifyingApiKey,
                        errorMessage = uiState.apiKeyErrorMessage,
                        onClearError = viewModel::clearApiKeyError,
                        onVerifyAndSave = viewModel::verifyAndSaveApiKey,
                        onOpenSettings = onOpenSettings,
                        bottomContentPadding = bottomContentPadding,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (bottomContentPadding == 0.dp) Modifier.navigationBarsPadding() else Modifier),
                    )
                } else {
                    AnimatedVisibility(
                        visible = uiState.isSearchExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        SeekMailSearchBar(
                            draft = uiState.searchDraft,
                            activeQuery = uiState.activeSearchQuery,
                            onDraftChange = viewModel::updateSearchDraft,
                            onSubmit = {
                                keyboardController?.hide()
                                viewModel.submitSearch()
                            },
                            onClear = viewModel::clearSearch,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    SeekMailboxRail(
                        mailboxes = uiState.mailboxes,
                        selectedMailboxId = uiState.selectedMailboxId,
                        isLoading = uiState.isLoadingMailboxes,
                        onSelectMailbox = viewModel::selectMailbox,
                        onCopyAddress = { address ->
                            copyWithFeedback("Seek 邮箱地址", address, "已复制邮箱：$address")
                        },
                        onCreateMailbox = viewModel::openCreateMailboxSheet,
                    )

                    uiState.selectedMailbox?.let { selected ->
                        SeekMailboxMetadataStrip(
                            mailbox = selected,
                            onCopyAddress = {
                                copyWithFeedback("Seek 邮箱地址", selected.address, "已复制邮箱：${selected.address}")
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    SeekMailFilterAndTagRail(
                        filterMode = uiState.filterMode,
                        tags = uiState.tags,
                        selectedTagId = uiState.selectedTagId,
                        totalMailCount = uiState.totalMailCount,
                        unreadCount = uiState.unreadLoadedCount,
                        onSelectFilterMode = viewModel::selectFilterMode,
                        onToggleTag = viewModel::toggleTagFilter,
                    )

                    if (uiState.activeSearchQuery.isNotBlank()) {
                        ActiveSearchPillBanner(
                            query = uiState.activeSearchQuery,
                            onClear = viewModel::clearSearch,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    SeekMailListPane(
                        uiState = uiState,
                        onRefresh = viewModel::refresh,
                        onRetry = viewModel::retryLoadMails,
                        onLoadMore = viewModel::loadMoreMails,
                        onClearFilters = viewModel::clearAllFilters,
                        onOpenCreateMailbox = viewModel::openCreateMailboxSheet,
                        onOpenSettings = onOpenSettings,
                        onOpenMail = viewModel::openMail,
                        onCopyCode = { code ->
                            copyWithFeedback("验证码", code, "已复制验证码：$code")
                        },
                        bottomContentPadding = bottomContentPadding,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = uiState.openedMailId != null,
            enter = slideInHorizontally(initialOffsetX = { it / 3 }) + fadeIn(
                animationSpec = NodePulseMotion.entranceFadeTween(durationMillis = 220),
            ),
            exit = slideOutHorizontally(targetOffsetX = { it / 3 }) + fadeOut(
                animationSpec = NodePulseMotion.subtleTween(durationMillis = 180),
            ),
        ) {
            SeekMailDetailReaderPane(
                uiState = uiState,
                onBack = viewModel::closeMailDetail,
                onRetryDetail = viewModel::retryMailDetail,
                onSelectReaderMode = viewModel::selectReaderMode,
                onRetryRawEml = viewModel::retryRawEml,
                onRetryAttachments = viewModel::retryAttachments,
                onToggleRemoteImages = viewModel::toggleAllowRemoteImages,
                onCopyCode = { code ->
                    copyWithFeedback("验证码", code, "已复制验证码：$code")
                },
                onCopyText = { label, text, toast ->
                    copyWithFeedback(label, text, toast)
                },
                onDownloadAttachment = { attachment ->
                    val mailId = uiState.openedMailId ?: return@SeekMailDetailReaderPane
                    startExport(PendingExportTarget.Attachment(mailId = mailId, attachment = attachment))
                },
                onSaveRawEml = {
                    val mailId = uiState.openedMailId ?: return@SeekMailDetailReaderPane
                    val subject = uiState.openedDetail?.summary?.subject
                        ?: uiState.openedPreviewSummary?.subject.orEmpty()
                    startExport(PendingExportTarget.RawEml(mailId = mailId, subject = subject))
                },
                onExternalUrlClick = { url -> pendingExternalUrl = url },
            )
        }

        PulseSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (bottomContentPadding == 0.dp || uiState.openedMailId != null) {
                        Modifier.navigationBarsPadding()
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (uiState.openedMailId != null) 0.dp else bottomContentPadding,
                ),
        )
    }

    if (uiState.showCreateMailboxSheet) {
        CreateMailboxBottomSheet(
            uiState = uiState,
            onDismiss = viewModel::dismissCreateMailboxSheet,
            onLocalPartChange = viewModel::updateCreateLocalPart,
            onSelectDomain = viewModel::selectCreateDomain,
            onEnableCustomDomain = viewModel::enableCustomDomain,
            onCustomDomainChange = viewModel::updateCustomDomain,
            onCheckAvailability = viewModel::checkMailboxAvailability,
            onCreateMailbox = viewModel::createMailbox,
        )
    }

    if (uiState.showContactsSheet) {
        ContactsDirectoryBottomSheet(
            contacts = uiState.contacts,
            isLoading = uiState.isLoadingContacts,
            errorMessage = uiState.contactsErrorMessage,
            onRefresh = viewModel::refreshContacts,
            onSelectContact = viewModel::filterMailsByContact,
            onCopyEmail = { email ->
                copyWithFeedback("联系人邮箱", email, "已复制邮箱：$email")
            },
            onDismiss = viewModel::dismissContactsSheet,
        )
    }

    pendingExternalUrl?.let { targetUrl ->
        PulseConfirmDialog(
            onDismissRequest = { pendingExternalUrl = null },
            icon = Icons.Rounded.Link,
            tone = PulseDialogTone.PRIMARY,
            title = "打开外部链接？",
            message = "邮件中的链接可能包含追踪参数或跳转重定向，请确认来源可信后再访问。",
            confirmText = "在浏览器打开",
            dismissText = "取消",
            onConfirm = {
                pendingExternalUrl = null
                runCatching { uriHandler.openUri(targetUrl) }
                    .onFailure {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("无法打开该链接，可先尝试复制地址")
                        }
                    }
            },
            extraContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = targetUrl,
                        style = NodePulseThemeTokens.extraTypography.codeBody,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = 0.8.dp,
                                color = NodePulseThemeTokens.extendedColors.cardBorder,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(10.dp),
                    )
                    FilledTonalButton(
                        onClick = {
                            copyWithFeedback("外部链接", targetUrl, "已复制链接地址")
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("仅复制链接", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
        )
    }
}

@Composable
private fun SeekMailTopBar(
    uiState: SeekMailUiState,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenCreateMailbox: () -> Unit,
    onOpenSettings: () -> Unit,
    showBackButton: Boolean = true,
) {
    val activeMailboxAddress = uiState.selectedMailbox?.address
    val subtitle = when {
        !uiState.hasConfiguredKey -> "独立临时邮箱 · 支持验证码一键复制"
        activeMailboxAddress != null -> buildString {
            append(activeMailboxAddress)
            uiState.rateLimit?.let { rl ->
                append(" · 配额 ${rl.remaining}/${rl.limit}")
            }
        }
        else -> buildString {
            append(
                if (uiState.mailboxes.isNotEmpty()) {
                    "${uiState.mailboxes.size} 个邮箱"
                } else {
                    "seek.li OpenAPI"
                },
            )
            uiState.rateLimit?.let { rl ->
                append(" · 配额 ${rl.remaining}/${rl.limit}")
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = false,
                showBottomBorder = true,
                tonalAccent = MaterialTheme.colorScheme.primary,
            )
            .statusBarsPadding()
            .padding(
                start = if (showBackButton) 6.dp else 16.dp,
                end = 12.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showBackButton) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = "Seek 邮箱",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (uiState.hasConfiguredKey && uiState.unreadLoadedCount > 0) {
                    PulseAccentPill(
                        text = "${uiState.unreadLoadedCount} 未读",
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (uiState.hasConfiguredKey) {
            MailTopCircleButton(
                icon = if (uiState.isSearchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = if (uiState.isSearchExpanded) "收起搜索" else "搜索邮件",
                onClick = onToggleSearch,
                tint = if (uiState.isSearchExpanded || uiState.activeSearchQuery.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            MailTopCircleButton(
                icon = Icons.Rounded.Contacts,
                contentDescription = "通讯录",
                onClick = onOpenContacts,
            )
            MailTopCircleButton(
                icon = Icons.Rounded.Add,
                contentDescription = "新建邮箱别名",
                onClick = onOpenCreateMailbox,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.26f),
            )
        }

        MailTopCircleButton(
            icon = Icons.Rounded.Settings,
            contentDescription = "打开设置",
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun MailTopCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
        alpha = if (NodePulseThemeTokens.isDark) 0.78f else 0.90f,
    ),
    borderColor: Color = NodePulseThemeTokens.extendedColors.glassBorder,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = contentDescription,
                showRipple = false,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(containerColor)
                .border(
                    width = 0.8.dp,
                    color = borderColor,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeekMailOnboardingView(
    isVerifying: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onVerifyAndSave: (String) -> Unit,
    onOpenSettings: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var draftKey by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 20.dp,
                bottom = 20.dp + bottomContentPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseHeroCard(accentColor = MaterialTheme.colorScheme.primary)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mail,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "连接 Seek 邮箱 (seek.li)",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    )
                    Text(
                        text = "独立于论坛登录会话 · 全本地隔离存储",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = "粘贴您的 Seek 邮箱 OpenAPI Bearer 密钥即可直接收信、管理多域名别名、自动提取一次性验证码，并默认拦截远程追踪图片。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseAccentPill(
                    text = "OTP 验证码一键复制",
                    icon = Icons.Rounded.Key,
                    containerColor = NodePulseThemeTokens.extendedColors.drumstickContainer,
                    contentColor = NodePulseThemeTokens.extendedColors.onDrumstickContainer,
                )
                PulseAccentPill(
                    text = "SPF / DKIM / DMARC 遥测",
                    icon = Icons.Rounded.VerifiedUser,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                PulseAccentPill(
                    text = "零 JS 隔离渲染",
                    icon = Icons.Rounded.Shield,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            PulseGradientDivider()

            OutlinedTextField(
                value = draftKey,
                onValueChange = {
                    draftKey = it
                    onClearError()
                },
                label = { Text("Seek 邮箱 API Key") },
                placeholder = { Text("粘贴 sk_live_... 密钥") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                val pasted = readClipboardText(context)
                                if (!pasted.isNullOrBlank()) {
                                    draftKey = pasted.trim()
                                    onClearError()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("粘贴", style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (showKey) "隐藏密钥" else "显示密钥",
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onVerifyAndSave(draftKey) },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!errorMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("设置中心")
                }

                Button(
                    onClick = { onVerifyAndSave(draftKey) },
                    enabled = !isVerifying && draftKey.isNotBlank(),
                    shape = CircleShape,
                    modifier = Modifier.weight(1.7f),
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在验证密钥…")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("验证并连接", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekMailSearchBar(
    draft: String,
    activeQuery: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("搜索发件人、主题或收件邮箱…") },
            singleLine = true,
            shape = CircleShape,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                if (draft.isNotEmpty() || activeQuery.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清除搜索",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f),
        )

        FilledTonalButton(
            onClick = onSubmit,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("搜索", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SeekMailboxRail(
    mailboxes: List<SeekMailbox>,
    selectedMailboxId: Long?,
    isLoading: Boolean,
    onSelectMailbox: (Long?) -> Unit,
    onCopyAddress: (String) -> Unit,
    onCreateMailbox: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "all_mailboxes") {
            val selected = selectedMailboxId == null
            MailboxRailPill(
                label = "全部邮箱",
                count = mailboxes.size.takeIf { it > 0 },
                selected = selected,
                isPinned = false,
                isLocked = false,
                doNotDisturb = false,
                onClick = { onSelectMailbox(null) },
                onCopy = null,
            )
        }

        if (isLoading && mailboxes.isEmpty()) {
            items(3, key = { "mb_shimmer_$it" }) {
                PulseShimmerBox(
                    modifier = Modifier
                        .width(136.dp)
                        .height(34.dp),
                    shape = CircleShape,
                )
            }
        } else {
            items(mailboxes, key = { it.id }) { mailbox ->
                MailboxRailPill(
                    label = mailbox.alias?.takeIf { it.isNotBlank() } ?: mailbox.address,
                    count = null,
                    selected = selectedMailboxId == mailbox.id,
                    isPinned = mailbox.isPinned,
                    isLocked = mailbox.isLocked,
                    doNotDisturb = mailbox.doNotDisturb,
                    onClick = { onSelectMailbox(mailbox.id) },
                    onCopy = { onCopyAddress(mailbox.address) },
                )
            }
        }

        item(key = "add_mailbox_pill") {
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 0.8.dp,
                        color = NodePulseThemeTokens.extendedColors.cardBorder,
                        shape = CircleShape,
                    )
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        onClick = onCreateMailbox,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "新建别名",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MailboxRailPill(
    label: String,
    count: Int?,
    selected: Boolean,
    isPinned: Boolean,
    isLocked: Boolean,
    doNotDisturb: Boolean,
    onClick: () -> Unit,
    onCopy: (() -> Unit)?,
) {
    val isDark = NodePulseThemeTokens.isDark
    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.72f else 0.86f)
        },
        label = "mailboxPillBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.55f else 0.35f)
        } else {
            NodePulseThemeTokens.extendedColors.cardBorder
        },
        label = "mailboxPillBorder",
    )
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(width = if (selected) 1.dp else 0.8.dp, color = borderColor, shape = CircleShape)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                onClick = onClick,
            )
            .padding(start = 11.dp, end = if (onCopy != null) 5.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (isPinned) {
            Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = "置顶邮箱",
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(13.dp),
            )
        } else if (isLocked) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = "已锁定",
                tint = contentColor.copy(alpha = 0.8f),
                modifier = Modifier.size(12.dp),
            )
        } else if (doNotDisturb) {
            Icon(
                imageVector = Icons.Rounded.DoNotDisturbOn,
                contentDescription = "免打扰",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(12.dp),
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )

        if (count != null && count > 0) {
            CompactCountBadge(
                count = count,
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                minHeight = 16.dp,
            )
        }

        if (onCopy != null) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
                        },
                    )
                    .clickable(onClick = onCopy),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "复制邮箱地址",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeekMailboxMetadataStrip(
    mailbox: SeekMailbox,
    onCopyAddress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.MediumBadge,
                elevation = PulseElevation.None,
                accentColor = MaterialTheme.colorScheme.secondary,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.AlternateEmail,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = mailbox.address,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(onClick = onCopyAddress)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "复制地址",
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (mailbox.isPinned) {
                MailboxMetaChip(label = "置顶邮箱", icon = Icons.Rounded.PushPin)
            }
            if (mailbox.doNotDisturb) {
                MailboxMetaChip(label = "免打扰", icon = Icons.Rounded.DoNotDisturbOn)
            }
            if (mailbox.isLocked) {
                MailboxMetaChip(label = "已锁定", icon = Icons.Rounded.Lock)
            }
            if (mailbox.sendDisabled) {
                MailboxMetaChip(label = "仅收信模式")
            }
            if (mailbox.forwardTo.isNotBlank()) {
                MailboxMetaChip(
                    label = "自动转发 → ${mailbox.forwardTo}",
                    icon = Icons.AutoMirrored.Rounded.ForwardToInbox,
                )
            }
            if (mailbox.archiveLimit > 0) {
                MailboxMetaChip(
                    label = "归档 ${mailbox.archiveCount}/${mailbox.archiveLimit}",
                )
            }
        }
    }
}

@Composable
private fun MailboxMetaChip(
    label: String,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(11.dp),
            )
        }
        Text(
            text = label,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SeekMailFilterAndTagRail(
    filterMode: SeekMailFilterMode,
    tags: List<SeekMailTag>,
    selectedTagId: Long?,
    totalMailCount: Int,
    unreadCount: Int,
    onSelectFilterMode: (SeekMailFilterMode) -> Unit,
    onToggleTag: (Long) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "filter_all") {
            val selected = filterMode == SeekMailFilterMode.ALL && selectedTagId == null
            FilterChip(
                selected = selected,
                onClick = { onSelectFilterMode(SeekMailFilterMode.ALL) },
                label = {
                    val label = if (selected && totalMailCount > 0) {
                        "全部 · $totalMailCount"
                    } else {
                        "全部"
                    }
                    Text(label, maxLines = 1, softWrap = false)
                },
            )
        }

        item(key = "filter_unread") {
            val selected = filterMode == SeekMailFilterMode.UNREAD
            FilterChip(
                selected = selected,
                onClick = { onSelectFilterMode(SeekMailFilterMode.UNREAD) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.MarkEmailUnread,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                },
                label = {
                    val label = if (selected && unreadCount > 0) {
                        "未读 · $unreadCount"
                    } else {
                        "未读"
                    }
                    Text(label, maxLines = 1, softWrap = false)
                },
            )
        }

        items(tags, key = { it.id }) { tag ->
            val selected = selectedTagId == tag.id
            val parsedColor = remember(tag.color) { parseHexColorOrNull(tag.color) }
            val dotColor = parsedColor ?: MaterialTheme.colorScheme.secondary
            FilterChip(
                selected = selected,
                onClick = { onToggleTag(tag.id) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                },
                label = {
                    Text(tag.name, maxLines = 1, softWrap = false)
                },
            )
        }
    }
}

@Composable
private fun ActiveSearchPillBanner(
    query: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "正在筛选：「$query」",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "清除",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClear)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekMailListPane(
    uiState: SeekMailUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onClearFilters: () -> Unit,
    onOpenCreateMailbox: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMail: (SeekMailSummary) -> Unit,
    onCopyCode: (String) -> Unit,
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 4
        }
    }

    LaunchedEffect(
        shouldLoadMore,
        uiState.hasMoreMails,
        uiState.isLoadingMore,
        uiState.isLoadingMails,
        uiState.isRefreshing,
        uiState.mailsErrorMessage,
    ) {
        if (
            shouldLoadMore &&
            uiState.hasMoreMails &&
            !uiState.isLoadingMore &&
            !uiState.isLoadingMails &&
            !uiState.isRefreshing &&
            uiState.mailsErrorMessage == null
        ) {
            onLoadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        when {
            uiState.isLoadingMails && uiState.mails.isEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 28.dp + bottomContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(5, key = { "mail_skel_$it" }) {
                        SeekMailSkeletonCard()
                    }
                }
            }

            uiState.mailsErrorMessage != null && uiState.mails.isEmpty() -> {
                val isUnauthorized = uiState.mailsErrorKind == SeekMailErrorKind.UNAUTHORIZED
                SeekMailEmptyStateCard(
                    icon = if (isUnauthorized) Icons.Rounded.Key else Icons.Rounded.ErrorOutline,
                    title = if (isUnauthorized) "API Key 无效或已失效" else "加载邮件失败",
                    subtitle = uiState.mailsErrorMessage,
                    actionLabel = if (isUnauthorized) "前往设置更换 Key" else "重新加载",
                    actionIcon = if (isUnauthorized) Icons.Rounded.Settings else Icons.Rounded.Refresh,
                    onAction = if (isUnauthorized) onOpenSettings else onRetry,
                    secondaryActionLabel = if (isUnauthorized) "重新加载" else null,
                    onSecondaryAction = if (isUnauthorized) onRetry else null,
                    modifier = Modifier
                        .then(if (bottomContentPadding == 0.dp) Modifier.navigationBarsPadding() else Modifier)
                        .padding(bottom = bottomContentPadding),
                )
            }

            uiState.mails.isEmpty() -> {
                val hasFilters = uiState.selectedMailboxId != null ||
                    uiState.selectedTagId != null ||
                    uiState.activeSearchQuery.isNotBlank() ||
                    uiState.filterMode == SeekMailFilterMode.UNREAD
                val title = when {
                    uiState.activeSearchQuery.isNotBlank() -> "未找到匹配邮件"
                    uiState.filterMode == SeekMailFilterMode.UNREAD -> "太棒了，暂无未读邮件"
                    uiState.selectedTag != null -> "「${uiState.selectedTag?.name}」标签下暂无邮件"
                    uiState.selectedMailbox != null -> "该邮箱暂无收件记录"
                    else -> "收件箱暂无邮件"
                }
                val subtitle = when {
                    hasFilters -> "您可以清除当前筛选条件，或下拉刷新同步最新来信。"
                    else -> "您可以向任意自有域名别名发送一封测试邮件，或创建一个新的临时别名。"
                }
                SeekMailEmptyStateCard(
                    icon = if (uiState.activeSearchQuery.isNotBlank()) {
                        Icons.Rounded.SearchOff
                    } else {
                        Icons.Rounded.Mail
                    },
                    title = title,
                    subtitle = subtitle,
                    actionLabel = if (hasFilters) "查看全部邮件" else "新建邮箱别名",
                    actionIcon = if (hasFilters) Icons.Rounded.Refresh else Icons.Rounded.Add,
                    onAction = if (hasFilters) onClearFilters else onOpenCreateMailbox,
                    modifier = Modifier
                        .then(if (bottomContentPadding == 0.dp) Modifier.navigationBarsPadding() else Modifier)
                        .padding(bottom = bottomContentPadding),
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 32.dp + bottomContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (uiState.mailsErrorMessage != null) {
                        val isUnauthorized = uiState.mailsErrorKind == SeekMailErrorKind.UNAUTHORIZED
                        item(key = "list_error_banner") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = uiState.mailsErrorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isUnauthorized) {
                                    TextButton(
                                        onClick = onOpenSettings,
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        Text("设置")
                                    }
                                }
                                TextButton(
                                    onClick = onRetry,
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text("重试")
                                }
                            }
                        }
                    }

                    itemsIndexed(
                        items = uiState.mails,
                        key = { _, mail -> mail.id },
                    ) { index, mail ->
                        PulseStaggeredEntrance(index = index.coerceAtMost(6)) {
                            SeekMailSummaryCard(
                                mail = mail,
                                onClick = { onOpenMail(mail) },
                                onCopyCode = { code -> onCopyCode(code) },
                            )
                        }
                    }

                    item(key = "mail_list_footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (bottomContentPadding == 0.dp) Modifier.navigationBarsPadding() else Modifier)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                uiState.isLoadingMore -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = "正在加载更多邮件…",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                uiState.hasMoreMails -> {
                                    FilledTonalButton(
                                        onClick = onLoadMore,
                                        shape = CircleShape,
                                    ) {
                                        Text("加载更多邮件")
                                    }
                                }

                                else -> {
                                    Text(
                                        text = "已显示全部 ${uiState.mails.size} 封邮件",
                                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeekMailSummaryCard(
    mail: SeekMailSummary,
    onClick: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val isUnread = !mail.isRead
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography
    val unreadAccent = MaterialTheme.colorScheme.primary
    val senderLabel = mail.fromName.ifBlank { mail.fromAddress }.ifBlank { "未知发件人" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                accentColor = if (isUnread) unreadAccent else null,
            )
            .pulseBouncyClickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isUnread) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(54.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(unreadAccent, MaterialTheme.colorScheme.secondary),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 1. Sender & Timestamp Row ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MailSenderAvatar(
                    senderName = senderLabel,
                    senderAddress = mail.fromAddress,
                    isUnread = isUnread,
                    size = 36.dp,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = senderLabel,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (isUnread) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = ext.readTitleAlpha + 0.14f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (mail.fromName.isNotBlank() && mail.fromAddress.isNotBlank()) {
                        Text(
                            text = mail.fromAddress,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (mail.hasAttachments) {
                        Icon(
                            imageVector = Icons.Rounded.AttachFile,
                            contentDescription = "包含附件",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    RelativeTimeText(
                        isoTimestamp = mail.receivedAt.ifBlank { mail.createdAt },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUnread) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // ── 2. Subject & Snippet ──
            Text(
                text = mail.subject.ifBlank { "(无主题)" },
                style = if (isUnread) extraType.titleMediumUnread else extraType.titleMediumRead,
                color = if (isUnread) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = ext.readTitleAlpha + 0.16f)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (mail.snippet.isNotBlank()) {
                Text(
                    text = mail.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ── 3. Prominent 1-Tap OTP Verification Code Pill ──
            val otpCode = mail.verificationCode?.trim()?.takeIf { it.isNotEmpty() }
            if (otpCode != null) {
                OtpQuickCopyPill(
                    code = otpCode,
                    onCopy = { onCopyCode(otpCode) },
                )
            }

            // ── 4. Target Mailbox + Tag Pills ──
            if (mail.toAddress.isNotBlank() || mail.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (mail.toAddress.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AlternateEmail,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = mail.toAddress,
                                style = extraType.microLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    mail.tags.forEach { tag ->
                        SeekMailTagMicroPill(tag = tag)
                    }
                }
            }
        }
    }
}

@Composable
private fun OtpQuickCopyPill(
    code: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val borderBrush = PulseBrushes.drumstickGoldGradient()

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        ext.drumstickContainer,
                        blendColor(ext.drumstickContainer, ext.drumstickGoldStart, if (isDark) 0.16f else 0.18f),
                    ),
                ),
            )
            .border(width = 1.dp, brush = borderBrush, shape = CircleShape)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = "复制验证码 $code",
                onClick = onCopy,
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Key,
            contentDescription = null,
            tint = ext.drumstickGoldEnd,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "验证码 $code · 复制",
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            fontWeight = FontWeight.Bold,
            color = ext.onDrumstickContainer,
            maxLines = 1,
            softWrap = false,
        )
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = ext.onDrumstickContainer,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun SeekMailTagMicroPill(
    tag: SeekMailTag,
) {
    val isDark = NodePulseThemeTokens.isDark
    val parsedColor = remember(tag.color) { parseHexColorOrNull(tag.color) }
        ?: MaterialTheme.colorScheme.secondary
    val bgColor = parsedColor.copy(alpha = if (isDark) 0.20f else 0.12f)
    val borderColor = parsedColor.copy(alpha = if (isDark) 0.42f else 0.26f)
    val textColor = if (isDark) {
        blendColor(parsedColor, Color.White, 0.30f)
    } else {
        blendColor(parsedColor, Color.Black, 0.28f)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bgColor)
            .border(width = 0.75.dp, color = borderColor, shape = RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(parsedColor),
        )
        Text(
            text = tag.name,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = textColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun MailSenderAvatar(
    senderName: String,
    senderAddress: String,
    isUnread: Boolean,
    size: Dp = 36.dp,
) {
    val isDark = NodePulseThemeTokens.isDark
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        NodePulseThemeTokens.extendedColors.stardustAccent,
    )
    val seed = abs((senderAddress.ifBlank { senderName }).hashCode())
    val accent = palette[seed % palette.size]
    val initial = senderName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"

    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(accent.copy(alpha = if (isDark) 0.24f else 0.14f))
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = if (isDark) 0.46f else 0.28f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = accent,
            )
        }
        if (isUnread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.background,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun SeekMailSkeletonCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PulseShimmerBox(modifier = Modifier.size(36.dp), shape = CircleShape)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.45f).height(14.dp))
                PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.62f).height(10.dp))
            }
            PulseShimmerBox(modifier = Modifier.width(44.dp).height(12.dp))
        }
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.88f).height(16.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.72f).height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseShimmerBox(modifier = Modifier.width(120.dp).height(20.dp), shape = CircleShape)
            PulseShimmerBox(modifier = Modifier.width(56.dp).height(20.dp), shape = CircleShape)
        }
    }
}

@Composable
private fun SeekMailEmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String?,
    actionIcon: ImageVector?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!actionLabel.isNullOrBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null) {
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(secondaryActionLabel, maxLines = 1, softWrap = false)
                        }
                    }
                    FilledTonalButton(
                        onClick = onAction,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (actionIcon != null) {
                            Icon(
                                imageVector = actionIcon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(actionLabel, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

// ── Full-Screen Animated Mail Detail Reader ───────────────────────────────────

@Composable
private fun SeekMailDetailReaderPane(
    uiState: SeekMailUiState,
    onBack: () -> Unit,
    onRetryDetail: () -> Unit,
    onSelectReaderMode: (SeekMailReaderMode) -> Unit,
    onRetryRawEml: () -> Unit,
    onRetryAttachments: () -> Unit,
    onToggleRemoteImages: () -> Unit,
    onCopyCode: (String) -> Unit,
    onCopyText: (label: String, text: String, toast: String) -> Unit,
    onDownloadAttachment: (SeekMailAttachment) -> Unit,
    onSaveRawEml: () -> Unit,
    onExternalUrlClick: (String) -> Unit,
) {
    val detail = uiState.openedDetail
    val summary = detail?.summary ?: uiState.openedPreviewSummary
    var headersExpanded by rememberSaveable(uiState.openedMailId) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Reader Glass TopBar + 3-Way Segmented Mode Switcher ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseGlassSurface(
                        showTopBorder = false,
                        showBottomBorder = true,
                        tonalAccent = MaterialTheme.colorScheme.primary,
                    )
                    .statusBarsPadding()
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回邮件列表",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = summary?.subject?.ifBlank { "(无主题)" } ?: "邮件详情",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (summary != null) {
                            Text(
                                text = summary.toAddress.ifBlank { "收件箱 #${summary.mailboxId}" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    MailTopCircleButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "刷新邮件详情",
                        onClick = onRetryDetail,
                    )
                }

                ReaderModeSegmentedBar(
                    selectedMode = uiState.readerMode,
                    onSelectMode = onSelectReaderMode,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // ── Compact Metadata, OTP Card, Security Telemetry & Attachments ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val otpCode = summary?.verificationCode?.trim()?.takeIf { it.isNotEmpty() }
                if (otpCode != null) {
                    DetailOtpHeroCard(
                        code = otpCode,
                        onCopy = { onCopyCode(otpCode) },
                    )
                }

                if (summary != null) {
                    ExpandableMailHeaderCard(
                        summary = summary,
                        authTelemetry = detail?.authTelemetry,
                        expanded = headersExpanded,
                        onToggleExpanded = { headersExpanded = !headersExpanded },
                        onCopyAddress = { addr ->
                            onCopyText("邮箱地址", addr, "已复制地址：$addr")
                        },
                    )
                }

                if (detail != null && detail.hasRemoteImages && uiState.readerMode == SeekMailReaderMode.HTML) {
                    RemoteImagePrivacyBanner(
                        allowRemoteImages = uiState.allowRemoteImages,
                        onToggle = onToggleRemoteImages,
                    )
                }

                if (
                    uiState.isLoadingAttachments ||
                    uiState.attachments.isNotEmpty() ||
                    uiState.attachmentsErrorMessage != null
                ) {
                    AttachmentsRailSection(
                        attachments = uiState.attachments,
                        isLoading = uiState.isLoadingAttachments,
                        errorMessage = uiState.attachmentsErrorMessage,
                        exportingKey = uiState.exportingKey,
                        mailId = uiState.openedMailId ?: 0L,
                        onRetry = onRetryAttachments,
                        onDownload = onDownloadAttachment,
                    )
                }
            }

            PulseGradientDivider()

            // ── Body Viewport (fills remaining space cleanly for isolated WebView) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding(),
            ) {
                when {
                    uiState.isLoadingDetail && detail == null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PulseShimmerBox(modifier = Modifier.fillMaxWidth().height(18.dp))
                            PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.92f).height(16.dp))
                            PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.84f).height(16.dp))
                            PulseShimmerBox(modifier = Modifier.fillMaxWidth().height(160.dp))
                        }
                    }

                    uiState.detailErrorMessage != null && detail == null -> {
                        SeekMailEmptyStateCard(
                            icon = Icons.Rounded.ErrorOutline,
                            title = "无法加载邮件正文",
                            subtitle = uiState.detailErrorMessage,
                            actionLabel = "重试加载",
                            actionIcon = Icons.Rounded.Refresh,
                            onAction = onRetryDetail,
                        )
                    }

                    detail != null -> {
                        when (uiState.readerMode) {
                            SeekMailReaderMode.HTML -> {
                                if (detail.sanitizedBodyHtml.isBlank()) {
                                    SeekMailEmptyStateCard(
                                        icon = Icons.Rounded.Code,
                                        title = "此邮件不包含 HTML 富文本正文",
                                        subtitle = if (detail.bodyText.isNotBlank()) {
                                            "您可以切换至「纯文本」或「原始源码 (.eml)」查看完整内容。"
                                        } else {
                                            "邮件正文为空，您可以查看原始 MIME 源码确认详情。"
                                        },
                                        actionLabel = if (detail.bodyText.isNotBlank()) "切换至纯文本" else "查看原始源码",
                                        actionIcon = null,
                                        onAction = {
                                            onSelectReaderMode(
                                                if (detail.bodyText.isNotBlank()) {
                                                    SeekMailReaderMode.TEXT
                                                } else {
                                                    SeekMailReaderMode.RAW_EML
                                                },
                                            )
                                        },
                                    )
                                } else {
                                    SeekMailWebView(
                                        sanitizedBodyHtml = detail.sanitizedBodyHtml,
                                        allowRemoteImages = uiState.allowRemoteImages,
                                        purePitchAmoled = uiState.purePitchAmoled,
                                        fontScale = uiState.fontScale,
                                        onExternalUrlClick = onExternalUrlClick,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }

                            SeekMailReaderMode.TEXT -> {
                                PlainTextReaderView(
                                    bodyText = detail.bodyText,
                                    onCopyPlainText = {
                                        onCopyText("邮件正文", detail.bodyText, "已复制纯文本正文")
                                    },
                                )
                            }

                            SeekMailReaderMode.RAW_EML -> {
                                RawEmlReaderView(
                                    rawEml = uiState.rawEml,
                                    isLoading = uiState.isLoadingRawEml,
                                    errorMessage = uiState.rawEmlErrorMessage,
                                    isExporting = uiState.exportingKey == "eml:${detail.summary.id}",
                                    onRetry = onRetryRawEml,
                                    onCopy = { eml ->
                                        onCopyText("原始 EML 源码", eml, "已复制原始 .eml 源码")
                                    },
                                    onSave = onSaveRawEml,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderModeSegmentedBar(
    selectedMode: SeekMailReaderMode,
    onSelectMode: (SeekMailReaderMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
            .border(
                width = 0.8.dp,
                color = NodePulseThemeTokens.extendedColors.cardBorder,
                shape = CircleShape,
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SeekMailReaderMode.entries.forEach { mode ->
            val selected = selectedMode == mode
            val bg by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                label = "readerModeBg",
            )
            val fg by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "readerModeFg",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelectMode(mode) }
                    .padding(vertical = 7.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DetailOtpHeroCard(
    code: String,
    onCopy: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.EditorialCard)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        ext.drumstickContainer,
                        blendColor(ext.drumstickContainer, ext.drumstickGoldStart, if (isDark) 0.14f else 0.16f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = PulseBrushes.drumstickGoldGradient(),
                shape = PulseArchitecturalShapes.EditorialCard,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.cardPressScale,
                onClick = onCopy,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(ext.drumstickGoldStart.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Key,
                contentDescription = null,
                tint = ext.drumstickGoldEnd,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = "检测到一次性验证码",
                style = NodePulseThemeTokens.extraTypography.microLabel,
                color = ext.onDrumstickContainer.copy(alpha = 0.82f),
            )
            Text(
                text = code,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                ).withTabularNumbers(),
                color = ext.onDrumstickContainer,
            )
        }
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(ext.drumstickGoldEnd)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpandableMailHeaderCard(
    summary: SeekMailSummary,
    authTelemetry: SeekMailAuthTelemetry?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCopyAddress: (String) -> Unit,
) {
    val senderLabel = summary.fromName.ifBlank { summary.fromAddress }.ifBlank { "未知发件人" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.None,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MailSenderAvatar(
                senderName = senderLabel,
                senderAddress = summary.fromAddress,
                isUnread = false,
                size = 34.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = senderLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    RelativeTimeText(
                        isoTimestamp = summary.receivedAt.ifBlank { summary.createdAt },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                AuthTelemetrySummaryPill(
                    authTelemetry = authTelemetry,
                    authScore = summary.authScore,
                    spamScore = summary.spamScore,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起详情" else "展开详情",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                PulseGradientDivider()
                DetailMetaRow(
                    label = "发件人",
                    value = if (summary.fromName.isNotBlank() && summary.fromAddress.isNotBlank()) {
                        "${summary.fromName} <${summary.fromAddress}>"
                    } else {
                        summary.fromAddress.ifBlank { senderLabel }
                    },
                    onCopy = summary.fromAddress.takeIf { it.isNotBlank() }?.let { addr ->
                        { onCopyAddress(addr) }
                    },
                )
                if (summary.toAddress.isNotBlank()) {
                    DetailMetaRow(
                        label = "收件人",
                        value = summary.toAddress,
                        onCopy = { onCopyAddress(summary.toAddress) },
                    )
                }
                DetailMetaRow(
                    label = "时间",
                    value = formatFullTimestamp(summary.receivedAt.ifBlank { summary.createdAt }),
                )
                if (summary.sizeBytes > 0L) {
                    DetailMetaRow(
                        label = "大小",
                        value = formatByteSize(summary.sizeBytes),
                    )
                }
                if (authTelemetry != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        AuthMechanismBadge("SPF", authTelemetry.spf)
                        AuthMechanismBadge("DKIM", authTelemetry.dkim)
                        AuthMechanismBadge("DMARC", authTelemetry.dmarc)
                        if (authTelemetry.fromMismatch) {
                            PulseAccentPill(
                                text = "发件域名不匹配",
                                icon = Icons.Rounded.WarningAmber,
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        if (authTelemetry.decision.isNotBlank()) {
                            PulseAccentPill(
                                text = "判定 ${authTelemetry.decision} · 综合分 ${authTelemetry.finalScore}",
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (summary.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        summary.tags.forEach { tag ->
                            SeekMailTagMicroPill(tag = tag)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthTelemetrySummaryPill(
    authTelemetry: SeekMailAuthTelemetry?,
    authScore: Int,
    spamScore: Int,
) {
    if (authTelemetry == null && authScore <= 0 && spamScore <= 0) return

    val allPass = authTelemetry != null &&
        authTelemetry.spf.equals("pass", ignoreCase = true) &&
        authTelemetry.dkim.equals("pass", ignoreCase = true) &&
        authTelemetry.dmarc.equals("pass", ignoreCase = true) &&
        !authTelemetry.fromMismatch

    val hasWarning = authTelemetry?.fromMismatch == true ||
        authTelemetry?.spf.equals("fail", ignoreCase = true) ||
        authTelemetry?.dkim.equals("fail", ignoreCase = true) ||
        authTelemetry?.dmarc.equals("fail", ignoreCase = true) ||
        spamScore >= 50

    val text = when {
        authTelemetry != null -> {
            val spf = authSymbol(authTelemetry.spf)
            val dkim = authSymbol(authTelemetry.dkim)
            val dmarc = authSymbol(authTelemetry.dmarc)
            val mismatchHint = if (authTelemetry.fromMismatch) " · 发件域名异常" else ""
            "SPF $spf · DKIM $dkim · DMARC $dmarc$mismatchHint"
        }
        else -> "安全分 $authScore · 风险分 $spamScore"
    }

    val bg = when {
        hasWarning -> NodePulseThemeTokens.extendedColors.heatContainer
        allPass -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        hasWarning -> NodePulseThemeTokens.extendedColors.onHeatContainer
        allPass -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (hasWarning) Icons.Rounded.WarningAmber else Icons.Rounded.VerifiedUser,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = text,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AuthMechanismBadge(
    mechanism: String,
    result: String,
) {
    val normalized = result.trim().ifBlank { "none" }
    val isPass = normalized.equals("pass", ignoreCase = true)
    val isFail = normalized.equals("fail", ignoreCase = true) || normalized.equals("softfail", ignoreCase = true)
    val bg = when {
        isPass -> MaterialTheme.colorScheme.secondaryContainer
        isFail -> NodePulseThemeTokens.extendedColors.heatContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        isPass -> MaterialTheme.colorScheme.onSecondaryContainer
        isFail -> NodePulseThemeTokens.extendedColors.onHeatContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    PulseAccentPill(
        text = "$mechanism: $normalized",
        containerColor = bg,
        contentColor = fg,
    )
}

@Composable
private fun DetailMetaRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(26.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "复制 $label",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun RemoteImagePrivacyBanner(
    allowRemoteImages: Boolean,
    onToggle: () -> Unit,
) {
    val bg = if (allowRemoteImages) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f)
    }
    val fg = if (allowRemoteImages) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (allowRemoteImages) Icons.Rounded.Image else Icons.Rounded.HideImage,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = if (allowRemoteImages) {
                "已允许加载此邮件中的远程图片"
            } else {
                "已默认拦截远程追踪图片"
            },
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                    shape = CircleShape,
                )
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (allowRemoteImages) "重新拦截" else "显示图片",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AttachmentsRailSection(
    attachments: List<SeekMailAttachment>,
    isLoading: Boolean,
    errorMessage: String?,
    exportingKey: String?,
    mailId: Long,
    onRetry: () -> Unit,
    onDownload: (SeekMailAttachment) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            isLoading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = "正在加载附件列表…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            errorMessage != null -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "附件列表加载失败：$errorMessage",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("重试", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            attachments.isNotEmpty() -> {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachments, key = { it.id }) { attachment ->
                        val isDownloading = exportingKey == "att:${mailId}:${attachment.id}"
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(
                                    width = 0.8.dp,
                                    color = NodePulseThemeTokens.extendedColors.cardBorder,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .pulseBouncyClickable(
                                    enabled = exportingKey == null,
                                    pressedScale = NodePulseMotion.chipPressScale,
                                    onClick = { onDownload(attachment) },
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AttachFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Column {
                                Text(
                                    text = attachment.fileName.ifBlank { "未命名附件" },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (attachment.sizeBytes > 0L) {
                                    Text(
                                        text = formatByteSize(attachment.sizeBytes),
                                        style = NodePulseThemeTokens.extraTypography.microLabel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "下载附件",
                                    tint = MaterialTheme.colorScheme.primary,
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

@Composable
private fun PlainTextReaderView(
    bodyText: String,
    onCopyPlainText: () -> Unit,
) {
    if (bodyText.isBlank()) {
        SeekMailEmptyStateCard(
            icon = Icons.Rounded.Mail,
            title = "无纯文本内容",
            subtitle = "该邮件未包含纯文本部分，可切换至 HTML 视图或原始源码查看。",
            actionLabel = null,
            actionIcon = null,
            onAction = {},
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            FilledTonalButton(
                onClick = onCopyPlainText,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("复制纯文本", style = MaterialTheme.typography.labelMedium)
            }
        }
        SelectionContainer {
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RawEmlReaderView(
    rawEml: String?,
    isLoading: Boolean,
    errorMessage: String?,
    isExporting: Boolean,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onSave: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography

    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "正在获取原始 MIME 源码…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        errorMessage != null && rawEml == null -> {
            SeekMailEmptyStateCard(
                icon = Icons.Rounded.ErrorOutline,
                title = "无法加载原始源码",
                subtitle = errorMessage,
                actionLabel = "重新获取",
                actionIcon = Icons.Rounded.Refresh,
                onAction = onRetry,
            )
        }

        rawEml != null -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    FilledTonalButton(
                        onClick = { onCopy(rawEml) },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("复制源码", style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = onSave,
                        enabled = !isExporting,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("保存 .eml", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(PulseArchitecturalShapes.CodeBlock)
                        .background(ext.codeSurface)
                        .border(width = 1.dp, color = ext.codeBorder, shape = PulseArchitecturalShapes.CodeBlock)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = rawEml,
                            style = extraType.codeBody,
                            color = ext.onCodeSurface,
                        )
                    }
                }
            }
        }
    }
}

// ── Bottom Sheets: Create Mailbox & Contacts Directory ────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateMailboxBottomSheet(
    uiState: SeekMailUiState,
    onDismiss: () -> Unit,
    onLocalPartChange: (String) -> Unit,
    onSelectDomain: (String) -> Unit,
    onEnableCustomDomain: () -> Unit,
    onCustomDomainChange: (String) -> Unit,
    onCheckAvailability: () -> Unit,
    onCreateMailbox: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeDomain = (if (uiState.useCustomDomain) uiState.customDomain else uiState.createDomain)
        .trim()
        .lowercase(Locale.US)
    val previewAddress = if (uiState.createLocalPart.isNotBlank() && activeDomain.isNotBlank()) {
        "${uiState.createLocalPart.trim().lowercase(Locale.US)}@$activeDomain"
    } else {
        ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "新建邮箱 / 别名",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            )

            OutlinedTextField(
                value = uiState.createLocalPart,
                onValueChange = onLocalPartChange,
                label = { Text("邮箱前缀") },
                placeholder = { Text("例如：nodeseek-vip") },
                suffix = {
                    if (activeDomain.isNotBlank()) {
                        Text(
                            text = "@$activeDomain",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "选择邮箱域名",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.availableDomains.forEach { domain ->
                        FilterChip(
                            selected = !uiState.useCustomDomain && uiState.createDomain == domain,
                            onClick = { onSelectDomain(domain) },
                            label = { Text(domain) },
                        )
                    }
                    FilterChip(
                        selected = uiState.useCustomDomain,
                        onClick = onEnableCustomDomain,
                        label = { Text("自定义域名") },
                    )
                }
            }

            AnimatedVisibility(visible = uiState.useCustomDomain) {
                OutlinedTextField(
                    value = uiState.customDomain,
                    onValueChange = onCustomDomainChange,
                    label = { Text("自定义域名") },
                    placeholder = { Text("例如：mail.example.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (previewAddress.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AlternateEmail,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = previewAddress,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            uiState.availabilityResult?.let { available ->
                val checkedAddr = uiState.availabilityCheckedAddress.orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (available) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                NodePulseThemeTokens.extendedColors.heatContainer
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (available) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (available) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            NodePulseThemeTokens.extendedColors.onHeatContainer
                        },
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = if (available) {
                            "可用：$checkedAddr 尚未被占用"
                        } else {
                            "不可用：$checkedAddr 已被占用"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (available) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            NodePulseThemeTokens.extendedColors.onHeatContainer
                        },
                    )
                }
            }

            if (!uiState.createMailboxError.isNullOrBlank()) {
                Text(
                    text = uiState.createMailboxError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onCheckAvailability,
                    enabled = !uiState.isCheckingAvailability && !uiState.isCreatingMailbox,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    if (uiState.isCheckingAvailability) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("检测可用性")
                }

                Button(
                    onClick = onCreateMailbox,
                    enabled = !uiState.isCreatingMailbox && !uiState.isCheckingAvailability,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    if (uiState.isCreatingMailbox) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("创建邮箱", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactsDirectoryBottomSheet(
    contacts: List<SeekMailContact>,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSelectContact: (SeekMailContact) -> Unit,
    onCopyEmail: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "通讯录联系人",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    )
                    Text(
                        text = "点击联系人按往来邮箱筛选邮件，或一键复制地址",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新联系人",
                    )
                }
            }

            PulseGradientDivider()

            when {
                isLoading && contacts.isEmpty() -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(4) {
                            PulseShimmerBox(
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                shape = PulseArchitecturalShapes.EditorialCard,
                            )
                        }
                    }
                }

                errorMessage != null && contacts.isEmpty() -> {
                    SeekMailEmptyStateCard(
                        icon = Icons.Rounded.ErrorOutline,
                        title = "加载通讯录失败",
                        subtitle = errorMessage,
                        actionLabel = "重试",
                        actionIcon = Icons.Rounded.Refresh,
                        onAction = onRefresh,
                    )
                }

                contacts.isEmpty() -> {
                    SeekMailEmptyStateCard(
                        icon = Icons.Rounded.Contacts,
                        title = "暂无通讯录联系人",
                        subtitle = "收到邮件后，往来发件人会自动记录在 Seek 通讯录中。",
                        actionLabel = "刷新",
                        actionIcon = Icons.Rounded.Refresh,
                        onAction = onRefresh,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(contacts, key = { it.id }) { contact ->
                            ContactDirectoryCard(
                                contact = contact,
                                onSelect = { onSelectContact(contact) },
                                onCopy = { onCopyEmail(contact.email) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactDirectoryCard(
    contact: SeekMailContact,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
) {
    val displayName = contact.name.ifBlank { contact.email }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(elevation = PulseElevation.None)
            .pulseBouncyClickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MailSenderAvatar(
            senderName = displayName,
            senderAddress = contact.email,
            isUnread = false,
            size = 36.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (contact.source.isNotBlank()) {
                    PulseAccentPill(
                        text = contact.source,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = contact.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val contactTime = contact.lastContactAt.ifBlank { contact.createdAt }
            if (contactTime.isNotBlank()) {
                RelativeTimeText(
                    isoTimestamp = contactTime,
                    prefix = "最近往来 ",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = "复制联系人邮箱",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── Formatting & System Helpers ───────────────────────────────────────────────

private fun copyTextToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun parseHexColorOrNull(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.isEmpty()) return null
    val normalized = when (hex.length) {
        3 -> buildString {
            append("FF")
            hex.forEach { c ->
                append(c)
                append(c)
            }
        }
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    val argb = normalized.toLongOrNull(16) ?: return null
    return Color(argb.toInt())
}

private fun formatByteSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe < 1024L -> "$safe B"
        safe < 1024L * 1024L -> "${safe / 1024L} KB"
        else -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
    }
}

private fun formatFullTimestamp(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "-"
    val instant = runCatching { Instant.parse(trimmed) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(trimmed).toInstant() }.getOrNull()
        ?: return trimmed
    return FULL_TIMESTAMP_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))
}

private fun authSymbol(value: String): String {
    return when {
        value.equals("pass", ignoreCase = true) -> "✓"
        value.equals("fail", ignoreCase = true) -> "✕"
        value.equals("softfail", ignoreCase = true) -> "~"
        value.isBlank() -> "-"
        else -> value
    }
}
