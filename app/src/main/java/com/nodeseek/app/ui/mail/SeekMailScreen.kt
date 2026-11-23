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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
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
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Unarchive
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailAuthTelemetry
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailLocalTag
import com.nodeseek.app.domain.mail.SeekMailRateLimit
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailbox
import com.nodeseek.app.domain.mail.VpsBillingMailParser
import com.nodeseek.app.domain.mail.VpsMailSummaryHint
import com.nodeseek.app.domain.mail.VpsMailType
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSearchField
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.PulseTextField
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
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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

@Suppress("UNUSED_PARAMETER")
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
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    val openApiKeyDialog: () -> Unit = {
        viewModel.clearApiKeyError()
        showApiKeyDialog = true
    }

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
        viewModel.snackbarEvents.collectLatest { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = if (event.actionLabel != null) {
                    SnackbarDuration.Long
                } else {
                    SnackbarDuration.Short
                },
            )
            if (result == SnackbarResult.ActionPerformed) {
                event.onAction?.invoke()
            }
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
                    onManageApiKey = openApiKeyDialog,
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
                        localTags = uiState.localTags,
                        localTagCounts = uiState.localTagCounts,
                        selectedLocalTagId = uiState.selectedLocalTagId,
                        totalMailCount = uiState.totalMailCount,
                        unreadCount = uiState.unreadLoadedCount,
                        archivedCount = uiState.archivedMailCount,
                        onSelectFilterMode = viewModel::selectFilterMode,
                        onToggleTag = viewModel::toggleTagFilter,
                        onToggleLocalTag = viewModel::toggleLocalTagFilter,
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
                        onManageApiKey = openApiKeyDialog,
                        onOpenMail = viewModel::openMail,
                        onLongClickMail = viewModel::openMailActionSheet,
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
                onRetryAttachments = viewModel::retryAttachments,
                onToggleRemoteImages = viewModel::toggleAllowRemoteImages,
                onToggleVpsPasswordVisibility = viewModel::toggleVpsPasswordVisibility,
                onIngestVpsMail = viewModel::ingestOpenedVpsMail,
                onManageMailTags = { summary -> viewModel.openMailTagSheet(summary) },
                onToggleArchive = { summary ->
                    if (summary.id in uiState.archivedMailIds) {
                        viewModel.unarchiveMail(summary)
                    } else {
                        viewModel.archiveMail(summary)
                    }
                },
                onDeleteMail = { summary -> viewModel.requestDeleteMail(summary) },
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

    uiState.actionTargetMail?.let { targetMail ->
        val isArchived = targetMail.id in uiState.archivedMailIds
        val localTagsForTarget = uiState.localTagsByMailId[targetMail.id].orEmpty()
        SeekMailActionBottomSheet(
            mail = targetMail,
            isArchived = isArchived,
            localTags = localTagsForTarget,
            onToggleArchive = {
                if (isArchived) {
                    viewModel.unarchiveMail(targetMail)
                } else {
                    viewModel.archiveMail(targetMail)
                }
            },
            onManageTags = viewModel::openTagSheetFromActionSheet,
            onRequestDelete = { viewModel.requestDeleteMail(targetMail) },
            onDismiss = viewModel::dismissMailActionSheet,
        )
    }

    uiState.pendingDeleteMail?.let { deleteTarget ->
        val subjectPreview = deleteTarget.subject.ifBlank {
            deleteTarget.fromName.ifBlank { deleteTarget.fromAddress }.ifBlank { "这封邮件" }
        }
        PulseConfirmDialog(
            onDismissRequest = viewModel::dismissDeleteMailDialog,
            icon = Icons.Rounded.DeleteOutline,
            tone = PulseDialogTone.DESTRUCTIVE,
            title = "删除这封邮件？",
            message = "「$subjectPreview」将从本机列表中移除（不影响 seek.li 服务器原件），删除后可在提示栏点击撤销。",
            confirmText = "确认删除",
            dismissText = "取消",
            onConfirm = viewModel::confirmDeleteMail,
        )
    }

    uiState.taggingTargetMail?.let { targetMail ->
        val assignedIds = remember(uiState.localTagsByMailId, targetMail.id) {
            uiState.localTagsByMailId[targetMail.id].orEmpty().mapTo(HashSet()) { it.id }
        }
        SeekMailTagBottomSheet(
            mail = targetMail,
            allLocalTags = uiState.localTags,
            assignedTagIds = assignedIds,
            onToggleTag = { tagId -> viewModel.toggleLocalTagForMail(targetMail.id, tagId) },
            onCreateAndAttachTag = { name, colorHex ->
                viewModel.createAndAttachLocalTag(targetMail.id, name, colorHex)
            },
            onClearMailTags = { viewModel.clearLocalTagsForMail(targetMail.id) },
            onDeleteTagDefinition = viewModel::deleteLocalTag,
            onDismiss = viewModel::dismissMailTagSheet,
        )
    }

    if (showApiKeyDialog && uiState.hasConfiguredKey) {
        SeekMailApiKeyDialog(
            maskedKeyHint = uiState.apiKeyMaskedHint,
            isVerifying = uiState.isVerifyingApiKey,
            errorMessage = uiState.apiKeyErrorMessage,
            onClearError = viewModel::clearApiKeyError,
            onVerifyAndSave = viewModel::verifyAndSaveApiKey,
            onDisconnect = {
                showApiKeyDialog = false
                viewModel.disconnectApiKey()
            },
            onDismiss = {
                if (!uiState.isVerifyingApiKey) {
                    viewModel.clearApiKeyError()
                    showApiKeyDialog = false
                }
            },
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
    onManageApiKey: () -> Unit,
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
            MailTopCircleButton(
                icon = Icons.Rounded.Key,
                contentDescription = "管理 Seek 邮箱 API Key",
                onClick = onManageApiKey,
            )
        }
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
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
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

            PulseTextField(
                value = draftKey,
                onValueChange = {
                    draftKey = it
                    onClearError()
                },
                label = "Seek 邮箱 API Key",
                placeholder = "粘贴 sk_live_... 密钥",
                singleLine = true,
                isError = !errorMessage.isNullOrBlank(),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "粘贴",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button) {
                                    val pasted = readClipboardText(context)
                                    if (!pasted.isNullOrBlank()) {
                                        draftKey = pasted.trim()
                                        onClearError()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        IconButton(
                            onClick = { showKey = !showKey },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = if (showKey) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (showKey) "隐藏密钥" else "显示密钥",
                                modifier = Modifier.size(19.dp),
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

            Button(
                onClick = { onVerifyAndSave(draftKey) },
                enabled = !isVerifying && draftKey.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable(role = Role.Button) {
                        runCatching {
                            uriHandler.openUri("https://seek.li/manage/developer/docs")
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "获取或管理 Seek 邮箱 API Key（seek.li）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SeekMailApiKeyDialog(
    maskedKeyHint: String,
    isVerifying: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onVerifyAndSave: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var draftKey by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var submittedReplacement by remember { mutableStateOf(false) }

    LaunchedEffect(isVerifying, errorMessage) {
        if (submittedReplacement && !isVerifying) {
            if (errorMessage == null) {
                submittedReplacement = false
                onDismiss()
            } else {
                submittedReplacement = false
            }
        }
    }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Key,
        title = "管理 Seek 邮箱密钥",
        message = "当前已连接：$maskedKeyHint",
        buttons = {
            TextButton(
                onClick = onDisconnect,
                enabled = !isVerifying,
            ) {
                Text(
                    text = "断开密钥",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDismiss,
                enabled = !isVerifying,
            ) {
                Text("取消", maxLines = 1, softWrap = false)
            }
            Button(
                onClick = {
                    submittedReplacement = true
                    onVerifyAndSave(draftKey)
                },
                enabled = !isVerifying && draftKey.isNotBlank(),
                shape = CircleShape,
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("验证中", maxLines = 1, softWrap = false)
                } else {
                    Text(
                        text = "更换并验证",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PulseTextField(
                value = draftKey,
                onValueChange = {
                    draftKey = it
                    onClearError()
                },
                label = "输入新的 API Key",
                placeholder = "粘贴 sk_live_... 密钥",
                singleLine = true,
                isError = !errorMessage.isNullOrBlank(),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "粘贴",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button) {
                                    val pasted = readClipboardText(context)
                                    if (!pasted.isNullOrBlank()) {
                                        draftKey = pasted.trim()
                                        onClearError()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        IconButton(
                            onClick = { showKey = !showKey },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = if (showKey) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (showKey) "隐藏密钥" else "显示密钥",
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!isVerifying && draftKey.isNotBlank()) {
                            submittedReplacement = true
                            onVerifyAndSave(draftKey)
                        }
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!errorMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable(role = Role.Button) {
                        runCatching {
                            uriHandler.openUri("https://seek.li/manage/developer/docs")
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "Seek 邮箱开发者文档与密钥管理",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
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
        PulseSearchField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = "搜索发件人、主题或收件邮箱…",
            showClearButton = draft.isNotEmpty() || activeQuery.isNotEmpty(),
            onClear = onClear,
            onSearch = onSubmit,
            modifier = Modifier.weight(1f),
        )

        FilledTonalButton(
            onClick = onSubmit,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.height(44.dp),
        ) {
            Text(
                text = "搜索",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
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
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
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
    localTags: List<SeekMailLocalTag>,
    localTagCounts: Map<String, Int>,
    selectedLocalTagId: String?,
    totalMailCount: Int,
    unreadCount: Int,
    archivedCount: Int,
    onSelectFilterMode: (SeekMailFilterMode) -> Unit,
    onToggleTag: (Long) -> Unit,
    onToggleLocalTag: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "filter_all") {
            val selected = filterMode == SeekMailFilterMode.ALL &&
                selectedTagId == null &&
                selectedLocalTagId == null
            val label = if (selected && totalMailCount > 0) "全部 · $totalMailCount" else "全部"
            CompactMailFilterPill(
                label = label,
                selected = selected,
                onClick = { onSelectFilterMode(SeekMailFilterMode.ALL) },
            )
        }

        item(key = "filter_unread") {
            val selected = filterMode == SeekMailFilterMode.UNREAD && selectedLocalTagId == null
            val label = if (selected && unreadCount > 0) "未读 · $unreadCount" else "未读"
            CompactMailFilterPill(
                label = label,
                selected = selected,
                icon = Icons.Rounded.MarkEmailUnread,
                onClick = { onSelectFilterMode(SeekMailFilterMode.UNREAD) },
            )
        }

        item(key = "filter_archived") {
            val selected = filterMode == SeekMailFilterMode.ARCHIVED && selectedLocalTagId == null
            val label = if (archivedCount > 0) "已存档 · $archivedCount" else "已存档"
            CompactMailFilterPill(
                label = label,
                selected = selected,
                icon = Icons.Rounded.Archive,
                onClick = { onSelectFilterMode(SeekMailFilterMode.ARCHIVED) },
            )
        }

        items(localTags, key = { "local_${it.id}" }) { localTag ->
            val selected = selectedLocalTagId == localTag.id
            val count = localTagCounts[localTag.id] ?: 0
            val dotColor = remember(localTag.colorHex) { parseMailHexColorOrNull(localTag.colorHex) }
                ?: MaterialTheme.colorScheme.primary
            CompactMailFilterPill(
                label = if (count > 0) "${localTag.name} · $count" else localTag.name,
                selected = selected,
                dotColor = dotColor,
                onClick = { onToggleLocalTag(localTag.id) },
            )
        }

        items(tags, key = { "remote_${it.id}" }) { tag ->
            val selected = selectedTagId == tag.id
            val dotColor = remember(tag.color) { parseMailHexColorOrNull(tag.color) }
                ?: MaterialTheme.colorScheme.secondary
            CompactMailFilterPill(
                label = tag.name,
                selected = selected,
                dotColor = dotColor,
                onClick = { onToggleTag(tag.id) },
            )
        }
    }
}

@Composable
private fun CompactMailFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    dotColor: Color? = null,
) {
    val isDark = NodePulseThemeTokens.isDark
    val accent = dotColor ?: MaterialTheme.colorScheme.primary
    val bgColor = if (selected) {
        if (dotColor != null) {
            dotColor.copy(alpha = if (isDark) 0.24f else 0.14f)
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (selected) {
        accent.copy(alpha = if (isDark) 0.62f else 0.44f)
    } else {
        NodePulseThemeTokens.extendedColors.cardBorder
    }
    val contentColor = if (selected) {
        if (dotColor != null) {
            if (isDark) blendColor(dotColor, Color.White, 0.30f) else blendColor(dotColor, Color.Black, 0.26f)
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .height(29.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = if (selected) 1.dp else 0.8.dp,
                color = borderColor,
                shape = CircleShape,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.5.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
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
    onManageApiKey: () -> Unit,
    onOpenMail: (SeekMailSummary) -> Unit,
    onLongClickMail: (SeekMailSummary) -> Unit,
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
                        start = 14.dp,
                        end = 14.dp,
                        top = 6.dp,
                        bottom = 28.dp + bottomContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(8, key = { "mail_skel_$it" }) {
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
                    actionLabel = if (isUnauthorized) "更换 API Key" else "重新加载",
                    actionIcon = if (isUnauthorized) Icons.Rounded.Key else Icons.Rounded.Refresh,
                    onAction = if (isUnauthorized) onManageApiKey else onRetry,
                    secondaryActionLabel = if (isUnauthorized) "重新加载" else null,
                    onSecondaryAction = if (isUnauthorized) onRetry else null,
                    modifier = Modifier
                        .then(if (bottomContentPadding == 0.dp) Modifier.navigationBarsPadding() else Modifier)
                        .padding(bottom = bottomContentPadding),
                )
            }

            uiState.mails.isEmpty() -> {
                val isArchivedFilter = uiState.filterMode == SeekMailFilterMode.ARCHIVED
                val hasFilters = uiState.selectedMailboxId != null ||
                    uiState.selectedTagId != null ||
                    uiState.selectedLocalTagId != null ||
                    uiState.activeSearchQuery.isNotBlank() ||
                    uiState.filterMode == SeekMailFilterMode.UNREAD ||
                    isArchivedFilter
                val title = when {
                    isArchivedFilter && uiState.activeSearchQuery.isNotBlank() -> "未找到匹配的已存档邮件"
                    isArchivedFilter -> "暂无已存档邮件"
                    uiState.activeSearchQuery.isNotBlank() -> "未找到匹配邮件"
                    uiState.filterMode == SeekMailFilterMode.UNREAD -> "太棒了，暂无未读邮件"
                    uiState.selectedLocalTag != null -> "「${uiState.selectedLocalTag?.name}」标签下暂无邮件"
                    uiState.selectedTag != null -> "「${uiState.selectedTag?.name}」标签下暂无邮件"
                    uiState.selectedMailbox != null -> "该邮箱暂无收件记录"
                    else -> "收件箱暂无邮件"
                }
                val subtitle = when {
                    isArchivedFilter && uiState.activeSearchQuery.isNotBlank() ->
                        "当前已存档邮件中暂无包含该关键词的记录，可清除搜索或切换回全部邮件。"
                    isArchivedFilter ->
                        "长按任意邮件选择「存档邮件」，即可将重要邮件收纳至此，随时查阅或取消存档。"
                    uiState.selectedLocalTag != null && uiState.hasMoreMails ->
                        "当前已加载页面中没有该标签的邮件，您可以加载更多历史邮件或清除筛选。"
                    uiState.selectedLocalTag != null ->
                        "长按任意邮件可快速管理标签、存档或删除。"
                    uiState.hasMoreMails ->
                        "当前页邮件已处理完毕，可继续加载更多历史邮件或下拉刷新。"
                    hasFilters -> "您可以清除当前筛选条件，或下拉刷新同步最新来信。"
                    else -> "您可以向任意自有域名别名发送一封测试邮件，或创建一个新的临时别名。"
                }
                SeekMailEmptyStateCard(
                    icon = when {
                        uiState.activeSearchQuery.isNotBlank() -> Icons.Rounded.SearchOff
                        isArchivedFilter -> Icons.Rounded.Archive
                        uiState.selectedLocalTag != null -> Icons.AutoMirrored.Rounded.Label
                        else -> Icons.Rounded.Mail
                    },
                    title = title,
                    subtitle = subtitle,
                    actionLabel = when {
                        isArchivedFilter -> "返回收件箱"
                        hasFilters -> "查看全部邮件"
                        else -> "新建邮箱别名"
                    },
                    actionIcon = if (hasFilters) Icons.Rounded.Refresh else Icons.Rounded.Add,
                    onAction = if (hasFilters) onClearFilters else onOpenCreateMailbox,
                    secondaryActionLabel = if (uiState.hasMoreMails) {
                        "加载更多邮件"
                    } else {
                        null
                    },
                    onSecondaryAction = if (uiState.hasMoreMails) {
                        onLoadMore
                    } else {
                        null
                    },
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
                        start = 14.dp,
                        end = 14.dp,
                        top = 6.dp,
                        bottom = 28.dp + bottomContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (uiState.mailsErrorMessage != null) {
                        val isUnauthorized = uiState.mailsErrorKind == SeekMailErrorKind.UNAUTHORIZED
                        item(key = "list_error_banner") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                        onClick = onManageApiKey,
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        Text("更换 Key", maxLines = 1, softWrap = false)
                                    }
                                }
                                TextButton(
                                    onClick = onRetry,
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text("重试", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }

                    itemsIndexed(
                        items = uiState.mails,
                        key = { _, mail -> mail.id },
                    ) { index, mail ->
                        val localTags = uiState.localTagsByMailId[mail.id].orEmpty()
                        val showArchivedBadge = uiState.filterMode != SeekMailFilterMode.ARCHIVED &&
                            mail.id in uiState.archivedMailIds
                        PulseStaggeredEntrance(index = index.coerceAtMost(6)) {
                            SeekMailSummaryCard(
                                mail = mail,
                                localTags = localTags,
                                showArchivedBadge = showArchivedBadge,
                                onClick = { onOpenMail(mail) },
                                onLongClick = { onLongClickMail(mail) },
                                onCopyCode = { code -> onCopyCode(code) },
                            )
                        }
                    }

                    item(key = "mail_list_footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (bottomContentPadding == 0.dp) Modifier.navigationBarsPadding() else Modifier)
                                .padding(vertical = 10.dp),
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
                                    val footerHint = if (uiState.filterMode == SeekMailFilterMode.ARCHIVED) {
                                        "已显示全部 ${uiState.mails.size} 封已存档邮件 · 长按可取消存档、打标签或删除"
                                    } else {
                                        "已显示全部 ${uiState.mails.size} 封邮件 · 长按可存档、打标签或删除"
                                    }
                                    Text(
                                        text = footerHint,
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SeekMailSummaryCard(
    mail: SeekMailSummary,
    localTags: List<SeekMailLocalTag>,
    showArchivedBadge: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val isUnread = !mail.isRead
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val fontScale = NodePulseThemeTokens.fontScale
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val primaryColor = MaterialTheme.colorScheme.primary
    val cardShape = RoundedCornerShape(12.dp)

    val cardBgColor = if (isUnread) {
        blendColor(
            source = ext.cardSurface,
            target = primaryColor,
            fraction = if (isDark) 0.08f else 0.035f,
        )
    } else {
        ext.cardSurface
    }
    val cardBorderColor = if (isUnread) {
        blendColor(
            source = ext.cardBorder,
            target = primaryColor,
            fraction = if (isDark) 0.44f else 0.30f,
        )
    } else {
        ext.cardBorder
    }

    val senderLabel = mail.fromName.ifBlank { mail.fromAddress }.ifBlank { "未知发件人" }
    val compactSnippet = remember(mail.id, mail.snippet) {
        mail.snippet.replace('\r', ' ').replace('\n', ' ').replace(Regex("""\s{2,}"""), " ").trim()
    }
    val otpCode = mail.verificationCode?.trim()?.takeIf { it.isNotEmpty() }
    val vpsHint = remember(mail.id, mail.subject, mail.snippet, mail.fromName, mail.fromAddress) {
        VpsBillingMailParser.parseSummaryHint(mail)
    }
    val hasBadgeRow = showArchivedBadge || otpCode != null || vpsHint != null || mail.tags.isNotEmpty() || localTags.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulsePressScale(interactionSource = interactionSource, pressedScale = 0.985f)
            .clip(cardShape)
            .background(cardBgColor)
            .border(
                width = if (isUnread) 1.dp else 0.8.dp,
                color = cardBorderColor,
                shape = cardShape,
            )
            .drawWithContent {
                drawContent()
                if (isUnread) {
                    val barW = 3.dp.toPx()
                    val vPad = 9.dp.toPx()
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
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onLongClickLabel = "邮件操作（存档、标签、删除）",
            )
            .padding(start = 11.dp, end = 11.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MailSenderAvatar(
            senderName = senderLabel,
            senderAddress = mail.fromAddress,
            isUnread = isUnread,
            size = 30.dp,
            modifier = Modifier.padding(top = 1.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // ── 1. Sender + Attachment Indicator + Relative Time ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = senderLabel,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = (13.5f * fontScale).sp,
                        lineHeight = (17.5f * fontScale).sp,
                        fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (isUnread) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = ext.readTitleAlpha + 0.16f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (mail.hasAttachments) {
                        Icon(
                            imageVector = Icons.Rounded.AttachFile,
                            contentDescription = "包含附件",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(12.5.dp),
                        )
                    }
                    RelativeTimeText(
                        isoTimestamp = mail.receivedAt.ifBlank { mail.createdAt },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = (10.5f * fontScale).sp,
                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (isUnread) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // ── 2. Subject (1 Crisp Line) ──
            Text(
                text = mail.subject.ifBlank { "(无主题)" },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (14f * fontScale).sp,
                    lineHeight = (19f * fontScale).sp,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (isUnread) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = ext.readTitleAlpha + 0.12f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // ── 3. Single-Line Snippet ──
            if (compactSnippet.isNotBlank()) {
                Text(
                    text = compactSnippet,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = (12f * fontScale).sp,
                        lineHeight = (16f * fontScale).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ── 4. Compact OTP / VPS / Archived / Tag Badges Row (only when present) ──
            if (hasBadgeRow) {
                FlowRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (showArchivedBadge) {
                        ArchivedMailMicroBadge()
                    }
                    if (otpCode != null) {
                        OtpQuickCopyPill(
                            code = otpCode,
                            onCopy = { onCopyCode(otpCode) },
                        )
                    }
                    if (vpsHint != null) {
                        VpsMailSummaryMicroBadge(hint = vpsHint)
                    }
                    localTags.forEach { localTag ->
                        SeekMailCompactTagChip(
                            name = localTag.name,
                            colorHex = localTag.colorHex,
                        )
                    }
                    mail.tags.forEach { tag ->
                        SeekMailCompactTagChip(
                            name = tag.name,
                            colorHex = tag.color,
                        )
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
    val fontScale = NodePulseThemeTokens.fontScale
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
            .border(width = 0.85.dp, brush = borderBrush, shape = CircleShape)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = "复制验证码 $code",
                onClick = onCopy,
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Key,
            contentDescription = null,
            tint = ext.drumstickGoldEnd,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = "验证码 $code",
            style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                fontSize = (10.5f * fontScale).sp,
                lineHeight = (13.5f * fontScale).sp,
                fontWeight = FontWeight.Bold,
            ).withTabularNumbers(),
            color = ext.onDrumstickContainer,
            maxLines = 1,
            softWrap = false,
        )
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = ext.onDrumstickContainer,
            modifier = Modifier.size(10.5.dp),
        )
    }
}

@Composable
private fun ArchivedMailMicroBadge(
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val fontScale = NodePulseThemeTokens.fontScale
    val accentColor = MaterialTheme.colorScheme.secondary
    val shape = RoundedCornerShape(6.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(accentColor.copy(alpha = if (isDark) 0.18f else 0.10f))
            .border(
                width = 0.7.dp,
                color = accentColor.copy(alpha = if (isDark) 0.42f else 0.25f),
                shape = shape,
            )
            .padding(horizontal = 5.5.dp, vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Archive,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(10.5.dp),
        )
        Text(
            text = "已存档",
            style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                fontSize = (10f * fontScale).sp,
                lineHeight = (13f * fontScale).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = accentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun MailSenderAvatar(
    senderName: String,
    senderAddress: String,
    isUnread: Boolean,
    size: Dp = 30.dp,
    modifier: Modifier = Modifier,
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

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(accent.copy(alpha = if (isDark) 0.22f else 0.13f))
                .border(
                    width = 0.85.dp,
                    color = accent.copy(alpha = if (isDark) 0.44f else 0.26f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = accent,
            )
        }
        if (isUnread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(7.5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(
                        width = 1.2.dp,
                        color = MaterialTheme.colorScheme.background,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun SeekMailSkeletonCard() {
    val ext = NodePulseThemeTokens.extendedColors
    val cardShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(ext.cardSurface)
            .border(width = 0.8.dp, color = ext.cardBorder, shape = cardShape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PulseShimmerBox(modifier = Modifier.size(30.dp), shape = CircleShape)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.42f).height(12.dp))
                PulseShimmerBox(modifier = Modifier.width(38.dp).height(10.dp))
            }
            PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.84f).height(13.dp))
            PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.68f).height(11.dp))
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
    onRetryAttachments: () -> Unit,
    onToggleRemoteImages: () -> Unit,
    onToggleVpsPasswordVisibility: () -> Unit,
    onIngestVpsMail: () -> Unit,
    onManageMailTags: (SeekMailSummary) -> Unit,
    onToggleArchive: (SeekMailSummary) -> Unit,
    onDeleteMail: (SeekMailSummary) -> Unit,
    onCopyCode: (String) -> Unit,
    onCopyText: (label: String, text: String, toast: String) -> Unit,
    onDownloadAttachment: (SeekMailAttachment) -> Unit,
    onExternalUrlClick: (String) -> Unit,
) {
    val detail = uiState.openedDetail
    val summary = detail?.summary ?: uiState.openedPreviewSummary
    val isOpenedMailArchived = summary != null && summary.id in uiState.archivedMailIds
    val localTagsForOpened = remember(uiState.localTagsByMailId, summary?.id) {
        summary?.id?.let { uiState.localTagsByMailId[it] }.orEmpty()
    }
    var headersExpanded by rememberSaveable(uiState.openedMailId) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Reader Glass TopBar + 2-Way Segmented Mode Switcher ──
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (summary != null) {
                            MailTopCircleButton(
                                icon = if (isOpenedMailArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                                contentDescription = if (isOpenedMailArchived) "取消存档" else "存档邮件",
                                onClick = { onToggleArchive(summary) },
                                tint = if (isOpenedMailArchived) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            MailTopCircleButton(
                                icon = Icons.AutoMirrored.Rounded.Label,
                                contentDescription = "管理邮件标签",
                                onClick = { onManageMailTags(summary) },
                                tint = if (localTagsForOpened.isNotEmpty()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            MailTopCircleButton(
                                icon = Icons.Rounded.DeleteOutline,
                                contentDescription = "删除邮件",
                                onClick = { onDeleteMail(summary) },
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        MailTopCircleButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = "刷新邮件详情",
                            onClick = onRetryDetail,
                        )
                    }
                }

                ReaderModeSegmentedBar(
                    selectedMode = uiState.readerMode,
                    onSelectMode = onSelectReaderMode,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // ── Compact Metadata, VPS Insight Card, OTP Card, Security Telemetry & Attachments ──
            val maxHeaderPaneHeight = if (uiState.openedVpsInsight != null) 340.dp else 260.dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeaderPaneHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.openedVpsInsight?.let { vpsInsight ->
                    DetailVpsMailInsightCard(
                        insight = vpsInsight,
                        onTogglePasswordVisibility = onToggleVpsPasswordVisibility,
                        onIngest = onIngestVpsMail,
                        onCopyText = onCopyText,
                    )
                }

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
                        localTags = localTagsForOpened,
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
                                            "您可以切换至「纯文本」查看完整内容。"
                                        } else {
                                            "该邮件正文为空，暂无可展示的内容。"
                                        },
                                        actionLabel = if (detail.bodyText.isNotBlank()) "切换至纯文本" else null,
                                        actionIcon = null,
                                        onAction = {
                                            if (detail.bodyText.isNotBlank()) {
                                                onSelectReaderMode(SeekMailReaderMode.TEXT)
                                            }
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

@Composable
private fun VpsMailSummaryMicroBadge(
    hint: VpsMailSummaryHint,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val fontScale = NodePulseThemeTokens.fontScale
    val (accentColor, icon) = when (hint.mailType) {
        VpsMailType.PROVISIONING -> MaterialTheme.colorScheme.primary to Icons.Rounded.Dns
        VpsMailType.RENEWAL -> MaterialTheme.colorScheme.tertiary to Icons.Rounded.CloudDone
        VpsMailType.INVOICE -> MaterialTheme.colorScheme.secondary to Icons.Rounded.AccountBalanceWallet
    }
    val bgColor = accentColor.copy(alpha = if (isDark) 0.18f else 0.10f)
    val borderColor = accentColor.copy(alpha = if (isDark) 0.42f else 0.25f)
    val detailSuffix = hint.primaryIp ?: hint.formattedPrice

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(width = 0.7.dp, color = borderColor, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 5.5.dp, vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = buildString {
                append(hint.mailType.shortBadgeZh)
                if (!detailSuffix.isNullOrBlank()) {
                    append(" · ")
                    append(detailSuffix)
                }
            },
            style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                fontSize = (10f * fontScale).sp,
                lineHeight = (13f * fontScale).sp,
                fontWeight = FontWeight.SemiBold,
            ).withTabularNumbers(),
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailVpsMailInsightCard(
    insight: VpsMailInsightUiState,
    onTogglePasswordVisibility: () -> Unit,
    onIngest: () -> Unit,
    onCopyText: (label: String, text: String, toast: String) -> Unit,
) {
    val parsed = insight.parsed
    val matchStatus = insight.matchStatus
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography

    val (accentColor, typeIcon) = when (parsed.mailType) {
        VpsMailType.PROVISIONING -> MaterialTheme.colorScheme.primary to Icons.Rounded.Dns
        VpsMailType.RENEWAL -> MaterialTheme.colorScheme.tertiary to Icons.Rounded.CloudDone
        VpsMailType.INVOICE -> MaterialTheme.colorScheme.secondary to Icons.Rounded.AccountBalanceWallet
    }

    val titleText = buildString {
        if (parsed.providerName.isNotBlank()) append(parsed.providerName)
        val itemLabel = parsed.productName.ifBlank { parsed.hostname }
        if (itemLabel.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(itemLabel)
        }
        if (isEmpty()) append(parsed.mailType.labelZh)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                accentColor = accentColor,
                elevation = PulseElevation.None,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // ── 1. Type Pill + Provider/Product Title + Invoice Badge ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = if (isDark) 0.22f else 0.12f))
                    .border(
                        width = 0.8.dp,
                        color = accentColor.copy(alpha = if (isDark) 0.45f else 0.28f),
                        shape = CircleShape,
                    )
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = parsed.mailType.labelZh,
                    style = extraType.microLabel,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Text(
                text = titleText,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            parsed.invoiceNumber?.takeIf { it.isNotBlank() }?.let { invNo ->
                Text(
                    text = "#$invNo",
                    style = extraType.microLabel.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }

        // ── 2. Matched Existing Asset Status & Auto-Renewal Banner ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (matchStatus.isAssetMatched) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.34f else 0.48f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
                    },
                )
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (matchStatus.isAssetMatched) Icons.Rounded.CheckCircle else Icons.Rounded.Storage,
                contentDescription = null,
                tint = if (matchStatus.isAssetMatched) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = insight.matchedAssetStatusText,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = if (matchStatus.isAssetMatched) FontWeight.SemiBold else FontWeight.Normal,
                color = if (matchStatus.isAssetMatched) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        matchStatus.renewalAutoUpdate?.let { autoRec ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (isDark) 0.38f else 0.55f))
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "已自动更新到期日：${autoRec.previousExpiryDateIso.ifBlank { "原日期" }} → ${autoRec.newExpiryDateIso}",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ── 3. Extracted Telemetry Chips (IP / Port / Username / Price / Expiry) ──
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            parsed.mainIpv4?.takeIf { it.isNotBlank() }?.let { ipv4 ->
                val endpointDisplay = if (parsed.sshPort != 22 || parsed.hasSshCredentials) {
                    "$ipv4:${parsed.sshPort}"
                } else {
                    ipv4
                }
                VpsInsightFieldChip(
                    label = "IPv4",
                    value = endpointDisplay,
                    monospace = true,
                    onClick = { onCopyText("服务器 IPv4", ipv4, "已复制 IP：$ipv4") },
                )
            }

            parsed.mainIpv6?.takeIf { it.isNotBlank() }?.let { ipv6 ->
                VpsInsightFieldChip(
                    label = "IPv6",
                    value = ipv6,
                    monospace = true,
                    onClick = { onCopyText("服务器 IPv6", ipv6, "已复制 IPv6：$ipv6") },
                )
            }

            if (parsed.hasSshCredentials || parsed.mailType == VpsMailType.PROVISIONING) {
                VpsInsightFieldChip(
                    label = "SSH 账号",
                    value = "${parsed.sshUsername} · 端口 ${parsed.sshPort}",
                    monospace = true,
                    onClick = {
                        onCopyText("SSH 用户名", parsed.sshUsername, "已复制用户名：${parsed.sshUsername}")
                    },
                )
            }

            parsed.formattedPriceAndCycle?.let { priceCycle ->
                VpsInsightFieldChip(
                    label = "账单周期",
                    value = priceCycle,
                )
            }

            parsed.nextDueDateIso?.takeIf { it.isNotBlank() }?.let { dueIso ->
                val dateLabel = if (parsed.mailType == VpsMailType.INVOICE) "账单到期" else "到期日"
                VpsInsightFieldChip(
                    label = dateLabel,
                    value = dueIso,
                    monospace = true,
                )
            }
        }

        // ── 4. Masked Root / SSH Password Row (with Reveal Toggle & Copy) ──
        parsed.sshPassword?.takeIf { it.isNotBlank() }?.let { password ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 0.8.dp,
                        color = NodePulseThemeTokens.extendedColors.cardBorder,
                        shape = RoundedCornerShape(9.dp),
                    )
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Password,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "SSH 密码",
                    style = extraType.microLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (insight.isPasswordRevealed) password else "••••••••••••",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onTogglePasswordVisibility,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (insight.isPasswordRevealed) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = if (insight.isPasswordRevealed) "隐藏密码" else "显示密码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(
                    onClick = { onCopyText("SSH 密码", password, "已复制 SSH 密码") },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "复制密码",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        // ── 5. One-Click Import / Update Action Button ──
        Button(
            onClick = onIngest,
            enabled = !insight.isIngesting,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (insight.isIngesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "正在入库…",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            } else {
                Icon(
                    imageVector = if (parsed.hasSshCredentials) Icons.Rounded.Terminal else Icons.Rounded.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = insight.actionButtonLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun VpsInsightFieldChip(
    label: String,
    value: String,
    monospace: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val baseModifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f))
        .border(
            width = 0.75.dp,
            color = NodePulseThemeTokens.extendedColors.cardBorder,
            shape = RoundedCornerShape(8.dp),
        )
        .let { mod ->
            if (onClick != null) mod.clickable(onClick = onClick) else mod
        }
        .padding(horizontal = 8.dp, vertical = 4.dp)

    Row(
        modifier = baseModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
            ).withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpandableMailHeaderCard(
    summary: SeekMailSummary,
    localTags: List<SeekMailLocalTag>,
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

        if (localTags.isNotEmpty() || summary.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                localTags.forEach { localTag ->
                    SeekMailCompactTagChip(name = localTag.name, colorHex = localTag.colorHex)
                }
                summary.tags.forEach { tag ->
                    SeekMailCompactTagChip(name = tag.name, colorHex = tag.color)
                }
            }
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
                        Text(
                            text = "重试",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                        )
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
            subtitle = "该邮件未包含纯文本部分，可切换至 HTML 视图查看。",
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

            PulseTextField(
                value = uiState.createLocalPart,
                onValueChange = onLocalPartChange,
                label = "邮箱前缀",
                placeholder = "例如：nodeseek-vip",
                isError = !uiState.createMailboxError.isNullOrBlank(),
                suffix = {
                    if (activeDomain.isNotBlank()) {
                        Text(
                            text = "@$activeDomain",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                },
                singleLine = true,
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
                PulseTextField(
                    value = uiState.customDomain,
                    onValueChange = onCustomDomainChange,
                    label = "自定义域名",
                    placeholder = "例如：mail.example.com",
                    singleLine = true,
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (uiState.isCheckingAvailability) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("检测可用性", maxLines = 1, softWrap = false)
                }

                Button(
                    onClick = onCreateMailbox,
                    enabled = !uiState.isCreatingMailbox && !uiState.isCheckingAvailability,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
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
                    Text("创建邮箱", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
