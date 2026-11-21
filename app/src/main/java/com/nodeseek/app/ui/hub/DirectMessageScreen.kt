@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.hub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.DirectMessage
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.navigation.openNodeSeekOrExternalUrl
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.RelativeTimeText
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.trust.SellerTrustCardSheet
import com.nodeseek.app.ui.trust.SellerTrustQuickBadge
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DirectMessageUiState(
    val peerUid: Long,
    val peerName: String,
    val currentUid: Long = 0L,
    val messages: List<DirectMessage> = emptyList(),
    val draftText: String = "",
    val isMarkdownMode: Boolean = false,
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val isSystemThread: Boolean = peerUid <= 0L,
)

class DirectMessageViewModel(
    private val container: AppContainer,
    private val peerUid: Long,
    peerName: String,
) : ViewModel() {

    private val draftKey = "dm_$peerUid"
    private val initialPeerName = peerName.ifBlank {
        if (peerUid <= 0L) "系统通知" else "用户 #$peerUid"
    }

    private val _uiState = MutableStateFlow(
        DirectMessageUiState(
            peerUid = peerUid,
            peerName = initialPeerName,
            currentUid = container.sessionRepository.sessionState.value?.uid ?: 0L,
            isSystemThread = peerUid <= 0L,
        ),
    )
    val uiState: StateFlow<DirectMessageUiState> = _uiState.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var draftSaveJob: Job? = null

    init {
        viewModelScope.launch {
            container.sessionRepository.sessionState.collect { session ->
                val uid = session?.uid ?: 0L
                _uiState.update { it.copy(currentUid = uid) }
                if (peerUid > 0L) {
                    val existingDraft = container.draftRepository.getDraft(
                        accountUid = uid,
                        draftKey = draftKey,
                    )
                    if (existingDraft != null && _uiState.value.draftText.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                draftText = existingDraft.markdownContent,
                                isMarkdownMode = existingDraft.categorySlug == "markdown",
                            )
                        }
                    }
                }
            }
        }
        refreshConversation(silent = false)
    }

    fun onDraftChanged(text: String) {
        _uiState.update { it.copy(draftText = text) }
        scheduleDraftSave()
    }

    fun toggleMarkdownMode() {
        _uiState.update { it.copy(isMarkdownMode = !it.isMarkdownMode) }
        scheduleDraftSave()
    }

    private fun scheduleDraftSave() {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(300)
            val snapshot = _uiState.value
            if (snapshot.draftText.isBlank()) {
                container.draftRepository.deleteDraft(snapshot.currentUid, draftKey)
            } else {
                container.draftRepository.saveDraft(
                    EditorDraft(
                        draftKey = draftKey,
                        accountUid = snapshot.currentUid,
                        title = snapshot.peerName,
                        markdownContent = snapshot.draftText,
                        categorySlug = if (snapshot.isMarkdownMode) "markdown" else "plain",
                    ),
                )
            }
        }
    }

    fun refreshConversation(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }
            container.inboxRepository.getConversation(peerUid)
                .onSuccess { list ->
                    val sorted = list.sortedBy { it.id }
                    val detectedSystemThread = peerUid <= 0L ||
                        (sorted.isNotEmpty() && sorted.all { it.isSystem })
                    _uiState.update {
                        it.copy(
                            messages = sorted,
                            isLoading = false,
                            errorMessage = null,
                            isSystemThread = detectedSystemThread,
                            peerName = if (it.peerName.isBlank()) {
                                if (detectedSystemThread) "系统通知" else "用户 #$peerUid"
                            } else {
                                it.peerName
                            },
                        )
                    }
                    val currentUid = _uiState.value.currentUid
                    val unreadIncomingIds = sorted
                        .filter { msg ->
                            !msg.isRead && (currentUid == 0L || msg.senderUid != currentUid)
                        }
                        .map { it.id }
                    if (unreadIncomingIds.isNotEmpty()) {
                        container.inboxRepository.markMessagesRead(unreadIncomingIds)
                    } else if (container.inboxRepository.messageThreads.value.any {
                            it.peerUid == peerUid && !it.isRead
                        }
                    ) {
                        container.inboxRepository.markThreadRead(peerUid)
                    } else if (!silent) {
                        container.inboxRepository.refreshUnreadCount()
                    }
                }
                .onFailure { error ->
                    if (!silent) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = error.message ?: "无法加载私信对话",
                            )
                        }
                    }
                }
        }
    }

    fun sendMessage() {
        val snapshot = _uiState.value
        if (snapshot.isSystemThread || peerUid <= 0L) {
            _events.trySend("系统通知不支持直接回复")
            return
        }
        val content = snapshot.draftText.trim()
        if (content.isEmpty() || snapshot.isSending) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            container.inboxRepository.sendDirectMessage(
                receiverUid = peerUid,
                content = content,
                isMarkdown = snapshot.isMarkdownMode,
            ).onSuccess {
                draftSaveJob?.cancel()
                container.draftRepository.deleteDraft(snapshot.currentUid, draftKey)
                _uiState.update {
                    it.copy(
                        draftText = "",
                        isSending = false,
                    )
                }
                refreshConversation(silent = true)
            }.onFailure { error ->
                _uiState.update { it.copy(isSending = false) }
                _events.trySend(error.message ?: "发送私信失败")
            }
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            peerUid: Long,
            peerName: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DirectMessageViewModel(container, peerUid, peerName) as T
            }
        }
    }
}

@Composable
fun DirectMessageScreen(
    peerUid: Long,
    peerName: String,
    onBack: () -> Unit,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenTopic: ((topicId: Long, initialFloor: Int, titleHint: String) -> Unit)? = null,
    onOpenCategory: ((slug: String) -> Unit)? = null,
    previewUiState: DirectMessageUiState? = null,
    viewModel: DirectMessageViewModel = viewModel(
        key = "dm_$peerUid",
        factory = DirectMessageViewModel.factory(LocalAppContainer.current, peerUid, peerName),
    ),
) {
    val appContainer = LocalAppContainer.current
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val uriHandler = LocalUriHandler.current
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val isSystemThread = uiState.isSystemThread || peerUid <= 0L
    val displayPeerName = uiState.peerName.ifBlank {
        if (isSystemThread) "系统通知" else "用户 #$peerUid"
    }
    var showSellerTrustSheet by rememberSaveable { mutableStateOf(false) }

    val handleMarkdownMentionClick: (String) -> Unit = remember(onOpenUserProfile) {
        { username ->
            onOpenUserProfile(0L, username)
        }
    }

    val handleMarkdownUrlClick: (String) -> Unit = remember(
        onOpenTopic,
        onOpenUserProfile,
        onOpenCategory,
        uriHandler,
    ) {
        { url ->
            openNodeSeekOrExternalUrl(
                rawUrl = url,
                uriHandler = uriHandler,
                onOpenTopic = onOpenTopic,
                onOpenUser = onOpenUserProfile,
                onOpenCategory = onOpenCategory,
            )
        }
    }

    // Foreground 10s auto-refresh while visible
    LaunchedEffect(lifecycleOwner, peerUid) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(10_000L)
                viewModel.refreshConversation(silent = true)
            }
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex + 1)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (!isSystemThread && peerUid > 0L) {
                                    Modifier.pulseBouncyClickable(
                                        pressedScale = NodePulseMotion.cardPressScale,
                                        role = Role.Button,
                                        onClick = { onOpenUserProfile(peerUid, displayPeerName) },
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(end = 6.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Box {
                            if (isSystemThread) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.NotificationsActive,
                                        contentDescription = displayPeerName,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            } else {
                                PulseAvatar(
                                    avatarUrl = null,
                                    username = displayPeerName,
                                    size = 36.dp,
                                    ringWidth = 1.6.dp,
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondary)
                                        .border(1.8.dp, MaterialTheme.colorScheme.background, CircleShape),
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = displayPeerName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (isSystemThread) {
                                    "站内系统通知 · 前台每 10 秒同步"
                                } else {
                                    "UID #$peerUid · 前台每 10 秒同步"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (!isSystemThread && peerUid > 0L) {
                        SellerTrustQuickBadge(
                            uid = peerUid,
                            compact = true,
                            onClick = { showSellerTrustSheet = true },
                            repository = appContainer.sellerTrustRepository,
                            modifier = Modifier.padding(end = 2.dp),
                        )
                        IconButton(onClick = { onOpenUserProfile(peerUid, displayPeerName) }) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = "查看对方资料",
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refreshConversation(silent = false) }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "立即刷新",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                modifier = Modifier.pulseGlassSurface(
                    showTopBorder = false,
                    showBottomBorder = true,
                    tonalAccent = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        bottomBar = {
            if (isSystemThread) {
                DirectMessageSystemReadOnlyBar()
            } else {
                DirectMessageComposerBar(
                    text = uiState.draftText,
                    isMarkdownMode = uiState.isMarkdownMode,
                    isSending = uiState.isSending,
                    onTextChange = viewModel::onDraftChanged,
                    onToggleMarkdown = viewModel::toggleMarkdownMode,
                    onSend = viewModel::sendMessage,
                )
            }
        },
        snackbarHost = {
            PulseSnackbarHost(hostState = snackbarHostState)
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Subtle atmospheric gradient wash behind conversation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ext.heroTintStart.copy(alpha = if (isDark) 0.14f else 0.08f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            if (uiState.isLoading && uiState.messages.isEmpty()) {
                DirectMessageSkeletonList(
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (uiState.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HubEmptyPlaceholderCard(
                        icon = if (isSystemThread) Icons.Rounded.NotificationsActive else Icons.Rounded.Mail,
                        title = when {
                            uiState.errorMessage != null && isSystemThread -> "未能加载系统通知"
                            uiState.errorMessage != null -> "未能加载私信对话"
                            isSystemThread -> "暂无系统通知"
                            else -> "与 $displayPeerName 开始对话"
                        },
                        subtitle = uiState.errorMessage ?: if (isSystemThread) {
                            "收到点赞、鸡腿、星辰等站内互动通知时会显示在这里。"
                        } else {
                            "支持纯文本与 Markdown 排版，未发送内容会自动保存为本地草稿。"
                        },
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                ) {
                    item(key = "dm_conv_header") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f))
                                    .border(0.8.dp, ext.cardBorder, CircleShape)
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = if (isSystemThread) Icons.Rounded.NotificationsActive else Icons.Rounded.Forum,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = if (isSystemThread) {
                                        "$displayPeerName · 点赞 / 鸡腿 / 星辰等系统消息"
                                    } else {
                                        "与 $displayPeerName 的站内私信 · UID #$peerUid"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    itemsIndexed(
                        items = uiState.messages,
                        key = { index, message ->
                            if (message.id > 0L) message.id else "dm_fallback_$index"
                        },
                    ) { index, message ->
                        val isOutgoing = uiState.currentUid != 0L && message.senderUid == uiState.currentUid
                        val prevMessage = uiState.messages.getOrNull(index - 1)
                        val nextMessage = uiState.messages.getOrNull(index + 1)
                        val isFirstInGroup = prevMessage == null || prevMessage.senderUid != message.senderUid
                        val isLastInGroup = nextMessage == null || nextMessage.senderUid != message.senderUid
                        val showTimestamp = isLastInGroup ||
                            formatRelativeTimeChinese(message.createdAtIso) !=
                            formatRelativeTimeChinese(nextMessage?.createdAtIso.orEmpty())

                        Column(
                            modifier = Modifier.padding(
                                top = if (index > 0 && isFirstInGroup) 10.dp else 3.dp,
                            ),
                        ) {
                            DirectMessageBubble(
                                message = message,
                                peerName = displayPeerName,
                                isSystemThread = isSystemThread,
                                isOutgoing = isOutgoing,
                                isFirstInGroup = isFirstInGroup,
                                isLastInGroup = isLastInGroup,
                                showTimestamp = showTimestamp,
                                onMentionClick = handleMarkdownMentionClick,
                                onUrlClick = handleMarkdownUrlClick,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSellerTrustSheet && !isSystemThread && peerUid > 0L) {
        SellerTrustCardSheet(
            uid = peerUid,
            usernameHint = displayPeerName,
            onOpenTopic = { targetTopicId ->
                showSellerTrustSheet = false
                onOpenTopic?.invoke(targetTopicId, 0, "")
            },
            onOpenUserProfile = { targetUid, targetName ->
                showSellerTrustSheet = false
                onOpenUserProfile(targetUid, targetName)
            },
            onStartDm = null,
            onDismiss = { showSellerTrustSheet = false },
            repository = appContainer.sellerTrustRepository,
        )
    }
}

@Composable
private fun DirectMessageBubble(
    message: DirectMessage,
    peerName: String,
    isSystemThread: Boolean,
    isOutgoing: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    showTimestamp: Boolean,
    onMentionClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val isSystemMessage = !isOutgoing && (message.isSystem || isSystemThread)

    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(
            topStart = 20.dp,
            topEnd = if (isFirstInGroup) 20.dp else 7.dp,
            bottomStart = 20.dp,
            bottomEnd = if (isLastInGroup) 4.dp else 7.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstInGroup) 20.dp else 7.dp,
            topEnd = 20.dp,
            bottomStart = if (isLastInGroup) 4.dp else 7.dp,
            bottomEnd = 20.dp,
        )
    }

    val bubbleBrush: Brush = if (isOutgoing) {
        if (isDark) {
            Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    blendColor(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer,
                        0.36f,
                    ),
                ),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary,
                    blendColor(
                        MaterialTheme.colorScheme.primary,
                        ext.brandGradientEnd,
                        0.24f,
                    ),
                ),
            )
        }
    } else {
        SolidColor(ext.cardSurfaceElevated)
    }

    val bubbleBorderColor: Color? = when {
        isOutgoing && isDark -> MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
        !isOutgoing -> ext.cardBorder
        else -> null
    }

    val bubbleTextColor = if (isOutgoing) {
        if (isDark) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bubbleLinkColor = if (isOutgoing) {
        if (isDark) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val renderedBody = remember(message.body) {
        normalizeDirectMessageBody(message.body)
    }
    val systemCategoryTag = remember(isSystemMessage, message.noticeType, renderedBody) {
        if (!isSystemMessage) {
            ""
        } else {
            message.noticeType.ifBlank { inferSystemBubbleTag(renderedBody) }
        }
    }
    val shouldRenderMarkdown = remember(message.isMarkdown, isSystemMessage, renderedBody, message.body) {
        message.isMarkdown ||
            isSystemMessage ||
            renderedBody != message.body.trim() ||
            containsDirectMessageMarkdownOrLinks(renderedBody)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isOutgoing) {
            if (isLastInGroup) {
                if (isSystemMessage) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsActive,
                            contentDescription = peerName,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                } else {
                    PulseAvatar(
                        avatarUrl = null,
                        username = peerName,
                        size = 28.dp,
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(28.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 304.dp),
        ) {
            Box(
                modifier = Modifier
                    .then(
                        if (!isDark) {
                            Modifier.shadow(
                                elevation = PulseElevation.Subtle,
                                shape = bubbleShape,
                                clip = false,
                                ambientColor = ext.softShadowColor,
                                spotColor = ext.softShadowColor,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(bubbleShape)
                    .background(bubbleBrush)
                    .then(
                        if (bubbleBorderColor != null) {
                            Modifier.border(0.9.dp, bubbleBorderColor, bubbleShape)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (systemCategoryTag.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = systemCategoryTag,
                                style = NodePulseThemeTokens.extraTypography.microLabel,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    if (shouldRenderMarkdown) {
                        MarkdownRenderer(
                            markdown = renderedBody,
                            bodyStyle = MaterialTheme.typography.bodyMedium,
                            textColor = bubbleTextColor,
                            linkColor = bubbleLinkColor,
                            compactSignatureMode = true,
                            onMentionClick = onMentionClick,
                            onUrlClick = onUrlClick,
                        )
                    } else {
                        Text(
                            text = renderedBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = bubbleTextColor,
                        )
                    }
                }
            }

            if (showTimestamp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                ) {
                    RelativeTimeText(
                        isoTimestamp = message.createdAtIso,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (message.isMarkdown) {
                        Text(
                            text = "· MD",
                            style = NodePulseThemeTokens.extraTypography.microLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
}

private val RELATIVE_MARKDOWN_LINK_REGEX =
    Regex("""\]\((/(?:post-\d+-\d+[^)\s]*|space/\d+[^)\s]*|categories/[^)\s]*|board[^)\s]*|notification[^)\s]*))\)""")

private fun normalizeDirectMessageBody(raw: String): String {
    val converted = NodeSeekHtmlParser.htmlFragmentToMarkdown(raw)
    if (converted.isBlank()) return ""
    return RELATIVE_MARKDOWN_LINK_REGEX.replace(converted) { match ->
        val relative = match.groupValues[1]
        "](${NodeSeekUrlNormalizer.normalizeNodeSeekUrl(relative)})"
    }
}

private fun inferSystemBubbleTag(body: String): String {
    val lower = body.lowercase()
    return when {
        "鸡腿" in lower || "coin" in lower -> "鸡腿通知"
        "星辰" in lower || "stardust" in lower -> "星辰通知"
        "点赞" in lower || "赞了" in lower || "upvote" in lower -> "点赞通知"
        else -> "系统通知"
    }
}

private fun containsDirectMessageMarkdownOrLinks(body: String): Boolean {
    if (body.isBlank()) return false
    return body.contains("https://", ignoreCase = true) ||
        body.contains("http://", ignoreCase = true) ||
        body.contains("](") ||
        body.contains("**") ||
        body.contains("`") ||
        body.contains("@") ||
        body.contains(":")
}

@Composable
private fun DirectMessageSystemReadOnlyBar() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = true,
                showBottomBorder = false,
                tonalAccent = MaterialTheme.colorScheme.primary,
            )
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "系统通知为单向提醒，暂不支持回复 · 点击消息内链接可直达对应内容",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DirectMessageSkeletonList(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Incoming bubble skeleton
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseShimmerBox(modifier = Modifier.size(28.dp), shape = CircleShape)
            PulseShimmerBox(
                modifier = Modifier
                    .width(200.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(18.dp),
            )
        }
        // Outgoing bubble skeleton
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            PulseShimmerBox(
                modifier = Modifier
                    .width(176.dp)
                    .height(44.dp),
                shape = RoundedCornerShape(18.dp),
            )
        }
        // Incoming bubble skeleton 2
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseShimmerBox(modifier = Modifier.size(28.dp), shape = CircleShape)
            PulseShimmerBox(
                modifier = Modifier
                    .width(236.dp)
                    .height(64.dp),
                shape = RoundedCornerShape(18.dp),
            )
        }
    }
}

@Composable
private fun DirectMessageComposerBar(
    text: String,
    isMarkdownMode: Boolean,
    isSending: Boolean,
    onTextChange: (String) -> Unit,
    onToggleMarkdown: () -> Unit,
    onSend: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val canSend = text.isNotBlank() && !isSending

    val modeBg by animateColorAsState(
        targetValue = if (isMarkdownMode) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "dmModeBg",
    )
    val modeFg by animateColorAsState(
        targetValue = if (isMarkdownMode) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "dmModeFg",
    )
    val modeBorder by animateColorAsState(
        targetValue = if (isMarkdownMode) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        } else {
            ext.cardBorder
        },
        label = "dmModeBorder",
    )

    val sendButtonBrush: Brush = if (canSend) {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                blendColor(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.secondary,
                    0.35f,
                ),
            ),
        )
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .pulseGlassSurface(
                showTopBorder = true,
                showBottomBorder = false,
                tonalAccent = MaterialTheme.colorScheme.primary,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(modeBg)
                        .border(0.8.dp, modeBorder, CircleShape)
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Switch,
                            onClick = onToggleMarkdown,
                        )
                        .padding(horizontal = 10.dp, vertical = 4.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Code,
                        contentDescription = null,
                        tint = modeFg,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (isMarkdownMode) "Markdown 已开启" else "纯文本模式",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isMarkdownMode) FontWeight.Bold else FontWeight.Medium,
                        color = modeFg,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                AnimatedVisibility(
                    visible = text.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "草稿已自动保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(if (isMarkdownMode) "输入 Markdown 私信…" else "输入私信内容…")
                    },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ext.cardSurface,
                        unfocusedContainerColor = if (isDark) ext.cardSurface else ext.cardSurfaceElevated,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = ext.cardBorder,
                    ),
                    modifier = Modifier.weight(1f),
                )

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .then(
                            if (canSend && !isDark) {
                                Modifier.shadow(
                                    elevation = PulseElevation.CardElevated,
                                    shape = CircleShape,
                                    clip = false,
                                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clip(CircleShape)
                        .background(sendButtonBrush)
                        .pulseBouncyClickable(
                            enabled = canSend,
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClickLabel = "发送私信",
                            onClick = onSend,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "发送私信",
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
