@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.hub

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import com.nodeseek.app.navigation.openNodeSeekOrExternalUrl
import com.nodeseek.app.ui.components.FloorNumberPill
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogPrimaryButton
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.PulseTopicCard
import com.nodeseek.app.ui.components.PulseTopicCardModel
import com.nodeseek.app.ui.components.PulseTopicCardSkeleton
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.components.RelativeTimeText
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.RankPalette
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers
import com.nodeseek.app.ui.trust.SellerTrustCardSheet
import com.nodeseek.app.ui.trust.SellerTrustQuickBadge

@Composable
fun UserProfileScreen(
    uid: Long,
    onBack: () -> Unit,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    modifier: Modifier = Modifier,
    usernameHint: String = "",
    onOpenDirectMessage: ((peerUid: Long, peerName: String) -> Unit)? = null,
    onOpenUserProfile: ((uid: Long, usernameHint: String) -> Unit)? = null,
    onOpenCategory: ((slug: String) -> Unit)? = null,
    onRequestWebViewAuth: ((url: String, requireSignedIn: Boolean) -> Unit)? = null,
    previewUiState: UserProfileUiState? = null,
    viewModel: UserProfileViewModel = viewModel(
        key = "profile_$uid",
        factory = UserProfileViewModel.factory(LocalAppContainer.current, uid, usernameHint),
    ),
) {
    val appContainer = LocalAppContainer.current
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    var showBlockConfirm by rememberSaveable { mutableStateOf(false) }
    var showSellerTrustSheet by rememberSaveable { mutableStateOf(false) }

    val handleMarkdownMentionClick: (String) -> Unit = remember(onOpenUserProfile) {
        { username ->
            onOpenUserProfile?.invoke(0L, username)
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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val profile = uiState.profile
    val rankSpec = profile?.let { NodePulseThemeTokens.rankSpec(it.level) }
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.pulseGlassSurface(
                    showTopBorder = false,
                    showBottomBorder = true,
                    tonalAccent = rankSpec?.glowColor,
                ),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = profile?.username ?: uiState.usernameHint.ifBlank { "用户主页 #$uid" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (profile != null && rankSpec != null) {
                            Text(
                                text = "UID #${profile.uid} · Lv.${profile.level} ${rankSpec.personaTitle}",
                                style = extraType.microLabel,
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
                    if (uiState.isOwnProfile && profile != null) {
                        IconButton(onClick = viewModel::openEditProfileDialog) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "编辑个人资料",
                            )
                        }
                    } else if (uiState.isSignedIn && profile != null) {
                        IconButton(onClick = { showBlockConfirm = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Block,
                                contentDescription = if (profile.isBlockedByViewer) {
                                    "解除屏蔽"
                                } else {
                                    "屏蔽此用户"
                                },
                                tint = if (profile.isBlockedByViewer) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 12.dp,
                    bottom = 36.dp,
                ),
            ) {
                if (profile != null) {
                    item(key = "profile_hero") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            PulseStaggeredEntrance(index = 0) {
                                UserProfileHeroBento(
                                    profile = profile,
                                    isOwnProfile = uiState.isOwnProfile,
                                    isSignedIn = uiState.isSignedIn,
                                    isUpdatingRelation = uiState.isUpdatingRelation,
                                    onEditProfile = viewModel::openEditProfileDialog,
                                    onToggleFollow = viewModel::toggleFollow,
                                    onSendMessage = if (onOpenDirectMessage != null) {
                                        { onOpenDirectMessage(profile.uid, profile.username) }
                                    } else {
                                        null
                                    },
                                    onInspectTrust = { showSellerTrustSheet = true },
                                    onOpenFollowing = {
                                        if (uiState.isOwnProfile) viewModel.showOwnConnections(following = true)
                                    },
                                    onOpenFollowers = {
                                        if (uiState.isOwnProfile) viewModel.showOwnConnections(following = false)
                                    },
                                    onMentionClick = handleMarkdownMentionClick,
                                    onUrlClick = handleMarkdownUrlClick,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    if (!profile.readmeMarkdown.isNullOrBlank() || !profile.signatureMarkdown.isNullOrBlank()) {
                        item(key = "profile_markdown_card") {
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                PulseStaggeredEntrance(index = 1) {
                                    UserMarkdownBioCard(
                                        readmeMarkdown = profile.readmeMarkdown,
                                        signatureMarkdown = profile.signatureMarkdown,
                                        onMentionClick = handleMarkdownMentionClick,
                                        onUrlClick = handleMarkdownUrlClick,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                } else if (uiState.isRefreshing) {
                    item(key = "profile_skeleton") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            UserProfileHeroSkeleton()
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                } else if (!uiState.isRefreshing && uiState.errorMessage != null) {
                    item(key = "profile_error") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            UserProfileLoadErrorCard(
                                message = uiState.errorMessage ?: "请稍后下拉刷新重试。",
                                isChallengeError = uiState.isChallengeError,
                                onRetry = viewModel::refresh,
                                onVerifySecurity = if (onRequestWebViewAuth != null) {
                                    {
                                        onRequestWebViewAuth(
                                            "${NodeSeekUrlNormalizer.ORIGIN}/space/$uid",
                                            false,
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }

                // ── Activity Tabs (发布的主题 | 参与的回复) ──
                item(key = "profile_activity_tabs") {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        PulseStaggeredEntrance(index = 2) {
                            ProfileActivityTabs(
                                selectedTab = uiState.selectedTab,
                                topicCount = profile?.topicCount ?: uiState.topics.size,
                                replyCount = profile?.replyCount ?: uiState.replies.size,
                                onSelectTab = viewModel::selectTab,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                when (uiState.selectedTab) {
                    ProfileActivityTab.TOPICS -> {
                        if (uiState.topics.isEmpty() && !uiState.isRefreshing) {
                            item(key = "topics_empty") {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    HubEmptyPlaceholderCard(
                                        icon = Icons.Rounded.Forum,
                                        title = "暂无发布的主题",
                                        subtitle = "该成员尚未发布公开讨论主题。",
                                    )
                                }
                            }
                        } else if (uiState.topics.isEmpty() && uiState.isRefreshing) {
                            items(count = 4, key = { "topics_skel_$it" }) {
                                PulseTopicCardSkeleton()
                            }
                        } else {
                            val fallbackAuthorName = profile?.username?.takeIf { it.isNotBlank() }
                                ?: uiState.usernameHint.ifBlank { "成员 #$uid" }
                            val fallbackAvatarUrl = profile?.avatarUrl?.takeIf { it.isNotBlank() }
                                ?: "${NodeSeekUrlNormalizer.ORIGIN}/avatar/$uid.png"

                            items(
                                items = uiState.topics,
                                key = { "u_topic_${it.id}" },
                            ) { summary ->
                                val cardModel = PulseTopicCardModel(
                                    id = summary.id,
                                    title = summary.title,
                                    categorySlug = summary.categorySlug,
                                    categoryName = summary.categoryName,
                                    authorId = summary.authorId ?: uid,
                                    authorName = summary.authorName.ifBlank { fallbackAuthorName },
                                    authorAvatarUrl = summary.authorAvatarUrl.ifBlank { fallbackAvatarUrl },
                                    authorLevel = summary.authorLevel ?: profile?.level,
                                    viewCount = summary.viewCount,
                                    replyCount = summary.replyCount,
                                    lastActivityIso = summary.lastActivityIso,
                                    isPinned = summary.isPinned,
                                    requiredViewLevel = summary.requiredViewLevel,
                                    isRead = summary.isRead,
                                    lastReadFloor = summary.lastReadFloor,
                                    isBookmarked = summary.isBookmarked,
                                    isHot = summary.isHot,
                                )
                                PulseTopicCard(
                                    topic = cardModel,
                                    showCategoryTag = true,
                                    onClick = {
                                        onOpenTopic(
                                            summary.id,
                                            summary.lastReadFloor ?: 0,
                                            summary.title,
                                        )
                                    },
                                    onBookmarkToggle = { viewModel.toggleBookmark(summary) },
                                    onCategoryClick = if (onOpenCategory != null && summary.categorySlug.isNotBlank()) {
                                        { slug -> onOpenCategory(slug) }
                                    } else {
                                        null
                                    },
                                )
                            }

                            if (uiState.hasMoreTopics || uiState.isLoadingMoreTopics) {
                                item(key = "topics_more") {
                                    LoadMoreFooter(
                                        isLoading = uiState.isLoadingMoreTopics,
                                        onLoadMore = viewModel::loadMoreTopics,
                                    )
                                }
                            }
                        }
                    }

                    ProfileActivityTab.REPLIES -> {
                        if (uiState.replies.isEmpty() && !uiState.isRefreshing) {
                            item(key = "replies_empty") {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    HubEmptyPlaceholderCard(
                                        icon = Icons.Rounded.ChatBubbleOutline,
                                        title = "暂无参与的回复",
                                        subtitle = "该成员近期的公开回帖记录将展示在此处。",
                                    )
                                }
                            }
                        } else if (uiState.replies.isEmpty() && uiState.isRefreshing) {
                            item(key = "replies_skeleton") {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    ProfileActivityListSkeleton()
                                }
                            }
                        } else {
                            items(
                                items = uiState.replies,
                                key = { "u_reply_${it.topicId}_${it.floorNumber}_${it.plainTextExcerpt.hashCode()}" },
                            ) { excerpt ->
                                UserReplyExcerptCard(
                                    excerpt = excerpt,
                                    onClick = {
                                        onOpenTopic(excerpt.topicId, excerpt.floorNumber, excerpt.topicTitle)
                                    },
                                )
                            }

                            if (uiState.hasMoreReplies || uiState.isLoadingMoreReplies) {
                                item(key = "replies_more") {
                                    LoadMoreFooter(
                                        isLoading = uiState.isLoadingMoreReplies,
                                        onLoadMore = viewModel::loadMoreReplies,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (uiState.showEditDialog && profile != null) {
        EditOwnProfileDialog(
            initialBio = profile.bio.orEmpty(),
            initialSignature = profile.signatureMarkdown.orEmpty(),
            initialReadme = profile.readmeMarkdown.orEmpty(),
            isSaving = uiState.isSavingProfile,
            onDismiss = viewModel::dismissEditProfileDialog,
            onSave = viewModel::saveOwnProfile,
        )
    }

    if (showBlockConfirm && profile != null) {
        val blocked = profile.isBlockedByViewer
        val name = profile.username
        PulseConfirmDialog(
            onDismissRequest = { showBlockConfirm = false },
            icon = if (blocked) Icons.Rounded.Visibility else Icons.Rounded.Block,
            tone = if (blocked) PulseDialogTone.PRIMARY else PulseDialogTone.DESTRUCTIVE,
            title = if (blocked) "解除屏蔽 $name" else "屏蔽 $name",
            message = if (blocked) {
                "解除屏蔽后，您将重新看到该成员发布的主题与楼层回复。"
            } else {
                "屏蔽后，默认将在信息流与帖子详情中隐藏该成员的内容。"
            },
            confirmText = if (blocked) "确认解除" else "确认屏蔽",
            dismissText = "取消",
            onConfirm = {
                showBlockConfirm = false
                viewModel.toggleBlock()
            },
        )
    }

    if (uiState.connectionsDialogTitle != null) {
        ConnectionsListDialog(
            title = uiState.connectionsDialogTitle.orEmpty(),
            users = uiState.connectionsList,
            isLoading = uiState.isLoadingConnections,
            onDismiss = viewModel::dismissConnectionsDialog,
            onSelectUser = { author ->
                viewModel.dismissConnectionsDialog()
                onOpenUserProfile?.invoke(author.uid, author.username)
            },
        )
    }

    if (showSellerTrustSheet && !uiState.isOwnProfile) {
        val targetUid = profile?.uid ?: uid
        val targetName = profile?.username?.takeIf { it.isNotBlank() } ?: uiState.usernameHint
        if (targetUid > 0L) {
            SellerTrustCardSheet(
                uid = targetUid,
                usernameHint = targetName,
                avatarUrlHint = profile?.avatarUrl.orEmpty(),
                onOpenTopic = { targetTopicId ->
                    showSellerTrustSheet = false
                    onOpenTopic(targetTopicId, 0, "")
                },
                onOpenUserProfile = { _, _ ->
                    showSellerTrustSheet = false
                },
                onStartDm = onOpenDirectMessage?.let { openDm ->
                    { dmUid, dmName ->
                        showSellerTrustSheet = false
                        openDm(dmUid, dmName)
                    }
                },
                onDismiss = { showSellerTrustSheet = false },
                repository = appContainer.sellerTrustRepository,
            )
        }
    }
}

@Composable
private fun UserProfileHeroBento(
    profile: UserProfile,
    isOwnProfile: Boolean,
    isSignedIn: Boolean,
    isUpdatingRelation: Boolean,
    onEditProfile: () -> Unit,
    onToggleFollow: () -> Unit,
    onSendMessage: (() -> Unit)?,
    onInspectTrust: () -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenFollowers: () -> Unit,
    onMentionClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography
    val rankSpec = NodePulseThemeTokens.rankSpec(profile.level)
    val rankProgress = remember(profile.drumsticks, isDark) {
        RankPalette.progressFromCoins(profile.drumsticks ?: 0, isDark = isDark)
    }
    val animatedRankProgress by animateFloatAsState(
        targetValue = rankProgress.progressFraction.coerceIn(0f, 1f),
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "profileRankProgress",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = rankSpec.glowColor,
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Hero,
            ),
    ) {
        // Subtle orbital telemetry ambient backdrop
        Canvas(modifier = Modifier.matchParentSize()) {
            val orbCenter = Offset(size.width * 0.90f, size.height * 0.14f)
            val orbRadius = size.minDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        rankSpec.glowColor.copy(alpha = if (isDark) 0.16f else 0.10f),
                        ext.brandGradientEnd.copy(alpha = if (isDark) 0.06f else 0.03f),
                        Color.Transparent,
                    ),
                    center = orbCenter,
                    radius = orbRadius,
                ),
                center = orbCenter,
                radius = orbRadius,
            )
            val ringAlpha = if (isDark) 0.08f else 0.06f
            drawCircle(
                color = rankSpec.glowColor.copy(alpha = ringAlpha),
                radius = size.minDimension * 0.34f,
                center = orbCenter,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = ext.brandGradientEnd.copy(alpha = ringAlpha),
                radius = size.minDimension * 0.50f,
                center = orbCenter,
                style = Stroke(width = 0.8.dp.toPx()),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Identity Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        rankSpec.glowColor.copy(alpha = if (isDark) 0.28f else 0.16f),
                                        Color.Transparent,
                                    ),
                                ),
                                shape = CircleShape,
                            ),
                    )
                    PulseAvatar(
                        avatarUrl = profile.avatarUrl,
                        username = profile.username,
                        size = 72.dp,
                        rankLevel = profile.level,
                        ringWidth = 2.8.dp,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = profile.username,
                            style = extraType.heroHeadline,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        RankMicroBadge(
                            level = profile.level,
                            showPersona = true,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(PulseArchitecturalShapes.SmallChip)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(
                                        alpha = if (isDark) 0.50f else 0.78f,
                                    ),
                                )
                                .border(
                                    width = 0.8.dp,
                                    color = ext.cardBorder,
                                    shape = PulseArchitecturalShapes.SmallChip,
                                )
                                .padding(horizontal = 7.dp, vertical = 2.5.dp),
                        ) {
                            Text(
                                text = "UID #${profile.uid}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                ).withTabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }

                        if (!profile.joinedAtIso.isNullOrBlank()) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "加入于 ${formatRelativeTimeChinese(profile.joinedAtIso)}",
                                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (profile.isBlockedByViewer) {
                        PulseAccentPill(
                            text = "已加入您的屏蔽名单",
                            icon = Icons.Rounded.Block,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // ── Editorial Bio Callout ──
            if (!profile.bio.isNullOrBlank()) {
                val quoteBarColor = rankSpec.glowColor
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            MaterialTheme.colorScheme.surface.copy(
                                alpha = if (isDark) 0.42f else 0.72f,
                            ),
                        )
                        .border(
                            width = 0.8.dp,
                            color = ext.cardBorder,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .drawWithContent {
                            drawContent()
                            val barW = 3.dp.toPx()
                            val vPad = 10.dp.toPx()
                            if (size.height > vPad * 2f) {
                                drawRoundRect(
                                    color = quoteBarColor,
                                    topLeft = Offset(0f, vPad),
                                    size = Size(barW, size.height - vPad * 2f),
                                    cornerRadius = CornerRadius(barW, barW),
                                )
                            }
                        }
                        .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    MarkdownRenderer(
                        markdown = profile.bio,
                        bodyStyle = MaterialTheme.typography.bodyMedium,
                        compactSignatureMode = true,
                        onMentionClick = onMentionClick,
                        onUrlClick = onUrlClick,
                    )
                }
            }

            // ── Rank Progression Telemetry Strip ──
            if (profile.drumsticks != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            MaterialTheme.colorScheme.surface.copy(
                                alpha = if (isDark) 0.36f else 0.66f,
                            ),
                        )
                        .border(
                            width = 0.8.dp,
                            color = rankSpec.glowColor.copy(alpha = if (isDark) 0.28f else 0.18f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (rankProgress.nextThresholdCoins != null) {
                                "Lv.${rankProgress.level} → Lv.${rankProgress.level + 1}"
                            } else {
                                "Lv.${rankProgress.level} · 已达最高等级"
                            },
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            fontWeight = FontWeight.Bold,
                            color = rankSpec.contentColor,
                        )
                        Text(
                            text = if (rankProgress.nextThresholdCoins != null) {
                                "还差 ${rankProgress.coinsToNextLevel} 鸡腿"
                            } else {
                                "${rankProgress.currentCoins} 鸡腿"
                            },
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedRankProgress.coerceIn(0.03f, 1f))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(Brush.horizontalGradient(rankSpec.gradientColors)),
                        )
                    }
                }
            }

            // ── Primary Action Buttons ──
            if (isOwnProfile) {
                val editInteraction = remember { MutableInteractionSource() }
                FilledTonalButton(
                    onClick = onEditProfile,
                    interactionSource = editInteraction,
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = if (isDark) 0.78f else 0.90f,
                        ),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .pulsePressScale(editInteraction),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "编辑个人简介、签名与 README",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else if (isSignedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val followed = profile.isFollowedByViewer == true
                    val followInteraction = remember { MutableInteractionSource() }
                    if (followed) {
                        FilledTonalButton(
                            onClick = onToggleFollow,
                            enabled = !isUpdatingRelation,
                            interactionSource = followInteraction,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .pulsePressScale(followInteraction),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PersonRemove,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("已关注", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        val ctaGradient = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                blendColor(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary,
                                    0.42f,
                                ),
                            ),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .pulsePressScale(followInteraction)
                                .clip(CircleShape)
                                .background(ctaGradient)
                                .clickable(
                                    interactionSource = followInteraction,
                                    indication = LocalIndication.current,
                                    enabled = !isUpdatingRelation,
                                    role = Role.Button,
                                    onClick = onToggleFollow,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (isUpdatingRelation) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.PersonAdd,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                                Text(
                                    text = "关注成员",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }

                    if (onSendMessage != null) {
                        val dmInteraction = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = onSendMessage,
                            interactionSource = dmInteraction,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .pulsePressScale(dmInteraction),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Mail,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("发私信", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    SellerTrustQuickBadge(
                        uid = profile.uid,
                        onClick = onInspectTrust,
                        modifier = Modifier.height(44.dp),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SellerTrustQuickBadge(
                        uid = profile.uid,
                        onClick = onInspectTrust,
                        modifier = Modifier.height(40.dp),
                    )
                }
            }

            PulseGradientDivider()

            // ── 2x2 Bento Stat Grid ──
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProfileMetricBentoTile(
                        value = (profile.drumsticks ?: 0).toString(),
                        label = "鸡腿",
                        accent = ext.onDrumstickContainer,
                        iconContainer = ext.drumstickContainer,
                        icon = Icons.Rounded.Restaurant,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    ProfileMetricBentoTile(
                        value = (profile.stardust ?: 0).toString(),
                        label = "星尘",
                        accent = ext.onStardustContainer,
                        iconContainer = ext.stardustContainer,
                        icon = Icons.Rounded.AutoAwesome,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProfileMetricBentoTile(
                        value = (profile.followingCount ?: 0).toString(),
                        label = "关注",
                        accent = MaterialTheme.colorScheme.primary,
                        iconContainer = MaterialTheme.colorScheme.primaryContainer,
                        icon = Icons.Rounded.Group,
                        onClick = if (isOwnProfile) onOpenFollowing else null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    ProfileMetricBentoTile(
                        value = (profile.followerCount ?: 0).toString(),
                        label = "粉丝",
                        accent = MaterialTheme.colorScheme.secondary,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        icon = Icons.Rounded.People,
                        onClick = if (isOwnProfile) onOpenFollowers else null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileMetricBentoTile(
    value: String,
    label: String,
    accent: Color,
    iconContainer: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography
    val shape = RoundedCornerShape(16.dp)
    val tileGradient = Brush.linearGradient(
        colors = listOf(
            accent.copy(alpha = if (isDark) 0.14f else 0.08f),
            MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.45f else 0.78f),
        ),
    )

    val tileInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.pulsePressScale(tileInteraction)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(tileGradient)
            .border(
                width = 0.8.dp,
                color = accent.copy(alpha = if (isDark) 0.28f else 0.18f),
                shape = shape,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = tileInteraction,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(iconContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = value,
            style = extraType.metricValue,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UserMarkdownBioCard(
    readmeMarkdown: String?,
    signatureMarkdown: String?,
    onMentionClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Card,
                accentColor = MaterialTheme.colorScheme.primary,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!readmeMarkdown.isNullOrBlank()) {
            PulseSectionHeader(
                title = "个人主页 README",
                subtitle = "成员自述与技术名片",
                icon = Icons.Rounded.Description,
                accentColor = MaterialTheme.colorScheme.primary,
                trailing = {
                    PulseAccentPill(
                        text = "README",
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = if (isDark) 0.72f else 0.85f,
                        ),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                },
            )
            MarkdownRenderer(
                markdown = readmeMarkdown,
                bodyStyle = MaterialTheme.typography.bodyMedium,
                onMentionClick = onMentionClick,
                onUrlClick = onUrlClick,
            )
        }

        if (!readmeMarkdown.isNullOrBlank() && !signatureMarkdown.isNullOrBlank()) {
            PulseGradientDivider()
        }

        if (!signatureMarkdown.isNullOrBlank()) {
            PulseSectionHeader(
                title = "个性签名",
                icon = Icons.Rounded.AutoAwesome,
                accentColor = MaterialTheme.colorScheme.secondary,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow.copy(
                            alpha = if (isDark) 0.65f else 0.85f,
                        ),
                    )
                    .border(
                        width = 0.8.dp,
                        color = ext.cardBorder,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                MarkdownRenderer(
                    markdown = signatureMarkdown,
                    bodyStyle = MaterialTheme.typography.bodySmall,
                    compactSignatureMode = true,
                    onMentionClick = onMentionClick,
                    onUrlClick = onUrlClick,
                )
            }
        }
    }
}

@Composable
private fun ProfileActivityTabs(
    selectedTab: ProfileActivityTab,
    topicCount: Int,
    replyCount: Int,
    onSelectTab: (ProfileActivityTab) -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val selectedIndex = if (selectedTab == ProfileActivityTab.TOPICS) 0 else 1

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                elevation = PulseElevation.Subtle,
            )
            .padding(4.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            val tabWidth = maxWidth / 2f
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * selectedIndex,
                animationSpec = NodePulseMotion.spatialDpSpring(),
                label = "profileTabOffset",
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .then(
                        if (!isDark) {
                            Modifier.shadow(
                                elevation = 2.dp,
                                shape = CircleShape,
                                clip = false,
                                ambientColor = ext.softShadowColor,
                                spotColor = ext.softShadowColor,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(CircleShape)
                    .background(
                        if (isDark) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        },
                    )
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(
                            alpha = if (isDark) 0.40f else 0.22f,
                        ),
                        shape = CircleShape,
                    ),
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileSegmentedTabItem(
                    label = "发布的主题",
                    count = topicCount,
                    isSelected = selectedTab == ProfileActivityTab.TOPICS,
                    onClick = { onSelectTab(ProfileActivityTab.TOPICS) },
                    modifier = Modifier
                        .width(tabWidth)
                        .fillMaxHeight(),
                )
                ProfileSegmentedTabItem(
                    label = "参与的回复",
                    count = replyCount,
                    isSelected = selectedTab == ProfileActivityTab.REPLIES,
                    onClick = { onSelectTab(ProfileActivityTab.REPLIES) },
                    modifier = Modifier
                        .width(tabWidth)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun ProfileSegmentedTabItem(
    label: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val textColor by animateColorAsState(
        targetValue = when {
            isSelected && isDark -> MaterialTheme.colorScheme.onPrimaryContainer
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "profileTabText",
    )
    val badgeBg by animateColorAsState(
        targetValue = when {
            isSelected && isDark -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "profileTabBadge",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .semantics { selected = isSelected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(badgeBg)
                .padding(horizontal = 6.dp, vertical = 1.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = count.toString(),
                style = NodePulseThemeTokens.extraTypography.microLabel,
                color = textColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun UserReplyExcerptCard(
    excerpt: UserReplyExcerpt,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val accentBarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val interactionSource = remember { MutableInteractionSource() }
    val cardShape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.5.dp)
            .pulsePressScale(interactionSource = interactionSource, pressedScale = 0.985f)
            .clip(cardShape)
            .border(
                width = 0.85.dp,
                color = ext.cardBorder,
                shape = cardShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(PulseBrushes.brandGradient()),
            )
            Text(
                text = excerpt.topicTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (excerpt.floorNumber > 0) {
                FloorNumberPill(floorNumber = excerpt.floorNumber)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val barW = 2.5.dp.toPx()
                    drawRoundRect(
                        color = accentBarColor,
                        topLeft = Offset(0f, 2.dp.toPx()),
                        size = Size(barW, (size.height - 4.dp.toPx()).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(barW, barW),
                    )
                    drawContent()
                }
                .padding(start = 10.dp),
        ) {
            Text(
                text = excerpt.plainTextExcerpt.ifBlank { "[图片或代码回复]" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (excerpt.createdAtIso.isNotBlank()) {
                RelativeTimeText(
                    isoTimestamp = excerpt.createdAtIso,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "定位楼层",
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun UserProfileHeroSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Card,
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PulseShimmerBox(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(22.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                PulseShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.40f)
                        .height(14.dp),
                    shape = RoundedCornerShape(6.dp),
                )
            }
        }
        PulseShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PulseShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(68.dp),
                shape = RoundedCornerShape(16.dp),
            )
            PulseShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(68.dp),
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

@Composable
private fun ProfileActivityListSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        elevation = PulseElevation.Card,
                    )
                    .padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulseShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(16.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                PulseShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadMoreFooter(
    isLoading: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
        } else {
            val interactionSource = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = onLoadMore,
                interactionSource = interactionSource,
                shape = CircleShape,
                modifier = Modifier.pulsePressScale(interactionSource),
            ) {
                Text(
                    text = "加载更多记录",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun EditOwnProfileDialog(
    initialBio: String,
    initialSignature: String,
    initialReadme: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (bio: String, signature: String, readme: String) -> Unit,
) {
    var bio by rememberSaveable { mutableStateOf(initialBio) }
    var signature by rememberSaveable { mutableStateOf(initialSignature) }
    var readme by rememberSaveable { mutableStateOf(initialReadme) }

    PulseAlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        icon = Icons.Rounded.Edit,
        title = "编辑个人主页与签名",
        confirmText = "保存更改",
        confirmLoading = isSaving,
        onConfirm = { onSave(bio, signature, readme) },
        dismissText = "取消",
        onDismiss = { if (!isSaving) onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("个人一句话简介 (Bio)") },
                shape = RoundedCornerShape(14.dp),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = signature,
                onValueChange = { signature = it },
                label = { Text("帖子底部签名 (支持 Markdown)") },
                shape = RoundedCornerShape(14.dp),
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = readme,
                onValueChange = { readme = it },
                label = { Text("主页 README (支持 Markdown)") },
                shape = RoundedCornerShape(14.dp),
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConnectionsListDialog(
    title: String,
    users: List<FloorAuthor>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelectUser: (FloorAuthor) -> Unit,
) {
    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.People,
        title = title,
        titleTrailing = {
            if (!isLoading) {
                PulseAccentPill(text = "${users.size}")
            }
        },
        buttons = {
            PulseDialogPrimaryButton(
                text = "关闭",
                onClick = onDismiss,
                useTonal = true,
            )
        },
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        } else if (users.isEmpty()) {
            Text(
                text = "列表为空",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(users, key = { it.uid }) { author ->
                    val itemInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulsePressScale(itemInteraction)
                            .pulseCard(
                                shape = PulseArchitecturalShapes.MediumBadge,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                elevation = PulseElevation.None,
                            )
                            .clickable(
                                interactionSource = itemInteraction,
                                indication = LocalIndication.current,
                                role = Role.Button,
                                onClick = { onSelectUser(author) },
                            )
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PulseAvatar(
                            avatarUrl = author.avatarUrl,
                            username = author.username,
                            size = 34.dp,
                            rankLevel = author.level,
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
                                    text = author.username,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (author.level != null) {
                                    RankMicroBadge(level = author.level)
                                }
                            }
                            Text(
                                text = "UID #${author.uid}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ).withTabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserProfileLoadErrorCard(
    message: String,
    isChallengeError: Boolean,
    onRetry: () -> Unit,
    onVerifySecurity: (() -> Unit)?,
) {
    val isDark = NodePulseThemeTokens.isDark
    val accent = if (isChallengeError) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                accentColor = accent,
            )
            .padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (isDark) 0.20f else 0.12f))
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = if (isDark) 0.34f else 0.22f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isChallengeError) Icons.Rounded.Shield else Icons.Rounded.Forum,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = if (isChallengeError) "需要完成浏览器安全验证" else "未能加载用户资料",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isChallengeError && onVerifySecurity != null) {
                Button(onClick = onVerifySecurity, shape = CircleShape) {
                    Text("前往安全验证", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(onClick = onRetry, shape = CircleShape) {
                    Text("重试")
                }
            } else {
                FilledTonalButton(onClick = onRetry, shape = CircleShape) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重新加载", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
