@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import com.nodeseek.app.domain.activity.LuckyDrawOutcome
import com.nodeseek.app.domain.activity.LuckyDrawRepository
import com.nodeseek.app.domain.activity.LuckyDrawSpec
import com.nodeseek.app.domain.activity.LuckyFloorEntry
import com.nodeseek.app.domain.activity.LuckyViewerStatus
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LuckyDrawSheetMode(val label: String) {
    VERIFY("开奖与验奖"),
    GENERATOR("生成抽奖链接"),
}

private val LUCKY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

private val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/**
 * Compact native inline banner rendered in `TopicDetailScreen` when the opening floor contains a `/lucky` link.
 */
@Composable
fun LuckyDrawInlineBannerCard(
    spec: LuckyDrawSpec,
    onOpenLuckySheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    var nowMillis by remember(spec.drawTimeEpochMillis) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(spec.drawTimeEpochMillis) {
        val drawMs = spec.drawTimeEpochMillis ?: return@LaunchedEffect
        while (isActive && nowMillis < drawMs) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val drawMs = spec.drawTimeEpochMillis
    val isDrawn = drawMs != null && nowMillis >= drawMs
    val accent = if (isDrawn) ext.drumstickGoldEnd else MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = accent,
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .clickable(onClick = onOpenLuckySheet)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(PulseArchitecturalShapes.SmallChip)
                .background(accent.copy(alpha = if (isDark) 0.22f else 0.14f))
                .border(
                    width = 0.8.dp,
                    color = accent.copy(alpha = if (isDark) 0.42f else 0.25f),
                    shape = PulseArchitecturalShapes.SmallChip,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Casino,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
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
                    text = "Drand 链上可验证抽奖",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                PulseAccentPill(
                    text = if (isDrawn) "已开奖" else "倒计时中",
                    containerColor = if (isDrawn) ext.drumstickContainer else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (isDrawn) ext.onDrumstickContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            val summary = buildString {
                if (drawMs != null) {
                    if (isDrawn) {
                        append("开奖时间 ${formatEpochShanghai(drawMs)}")
                    } else {
                        append("距开奖 ${formatCountdown(drawMs - nowMillis)}")
                    }
                } else {
                    append("点击查看开奖详情")
                }
                append(" · 抽 ${spec.winnerCount} 名 · #${spec.firstFloor}楼起")
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = onOpenLuckySheet,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (isDrawn) "查中奖" else "验资格",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun LuckyDrawSheet(
    spec: LuckyDrawSpec?,
    initialMode: LuckyDrawSheetMode = if (spec != null) LuckyDrawSheetMode.VERIFY else LuckyDrawSheetMode.GENERATOR,
    allowGeneratorMode: Boolean = false,
    isViewerTopicAuthor: Boolean = false,
    viewerUid: Long? = null,
    luckyDrawRepository: LuckyDrawRepository,
    forumRepository: ForumRepository,
    onDismiss: () -> Unit,
    onFeedbackMessage: (String) -> Unit = {},
    onOpenTopicFloor: ((topicId: Long, floor: Int) -> Unit)? = null,
    onOpenUser: ((uid: Long, username: String) -> Unit)? = null,
    onWinnerReplyPosted: (() -> Unit)? = null,
    onInsertGeneratedLuckyMarkdown: ((markdown: String, spec: LuckyDrawSpec) -> Unit)? = null,
    onAppendLuckyToTopicOp: ((markdown: String, spec: LuckyDrawSpec) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp.coerceAtLeast(340.dp)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val ext = NodePulseThemeTokens.extendedColors

    var mode by remember(spec, initialMode) {
        mutableStateOf(if (spec == null) LuckyDrawSheetMode.GENERATOR else initialMode)
    }

    var activeSpec by remember(spec) { mutableStateOf(spec) }
    var nowEpochMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isLoading by remember { mutableStateOf(false) }
    var outcome by remember(activeSpec) { mutableStateOf<LuckyDrawOutcome?>(null) }
    var errorMessage by remember(activeSpec) { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    var showReplyPreview by remember(activeSpec) { mutableStateOf(false) }
    var replyDraftMarkdown by remember(activeSpec) { mutableStateOf("") }
    var isPostingReply by remember { mutableStateOf(false) }

    // Generator fields
    val defaultGenTimeMs = remember(spec) {
        spec?.drawTimeEpochMillis ?: (System.currentTimeMillis() + 3_600_000L)
    }
    var genPostId by remember(spec) { mutableLongStateOf(spec?.postId ?: 0L) }
    var genDrawTimeText by remember(defaultGenTimeMs) {
        mutableStateOf(formatEpochShanghai(defaultGenTimeMs))
    }
    var genWinnerCountText by remember(spec) {
        mutableStateOf((spec?.winnerCount ?: 1).toString())
    }
    var genFirstFloorText by remember(spec) {
        mutableStateOf((spec?.firstFloor ?: 1).toString())
    }
    var genRemoveDuplicates by remember(spec) {
        mutableStateOf(spec?.removeDuplicates ?: true)
    }

    // Live 1s countdown ticker while pre-draw
    LaunchedEffect(mode, activeSpec?.drawTimeEpochMillis, outcome?.isDrawn, outcome?.beaconPendingMessage) {
        if (mode != LuckyDrawSheetMode.VERIFY) return@LaunchedEffect
        val targetDrawMs = activeSpec?.drawTimeEpochMillis ?: return@LaunchedEffect
        while (isActive) {
            val current = System.currentTimeMillis()
            nowEpochMillis = current
            if (current >= targetDrawMs && outcome != null && !outcome!!.isDrawn && outcome!!.beaconPendingMessage == null) {
                reloadNonce += 1
                break
            }
            delay(1_000L)
        }
    }

    // Resolve lucky draw outcome
    LaunchedEffect(mode, activeSpec, viewerUid, reloadNonce) {
        if (mode != LuckyDrawSheetMode.VERIFY) return@LaunchedEffect
        val target = activeSpec ?: return@LaunchedEffect
        if (!target.hasValidDrawTime) {
            outcome = null
            errorMessage = "抽奖链接缺少有效的开奖时间参数 (time)"
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null
        val result = luckyDrawRepository.resolveLuckyDraw(
            spec = target,
            viewerUid = viewerUid,
            nowEpochMillis = System.currentTimeMillis(),
        )
        isLoading = false
        result.fold(
            onSuccess = { resolved ->
                outcome = resolved
                if (resolved.isDrawn && replyDraftMarkdown.isBlank()) {
                    replyDraftMarkdown = DrandLuckyEngine.buildWinnerReplyMarkdown(resolved)
                }
            },
            onFailure = { err ->
                errorMessage = err.message?.takeIf { it.isNotBlank() } ?: "加载开奖数据失败"
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isPostingReply) onDismiss() },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Casino,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Drand 链上可验证开奖器",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    activeSpec?.let { current ->
                        if (current.postId > 0L) {
                            Text(
                                text = "帖子 #${current.postId} · 抽 ${current.winnerCount} 名 · #${current.firstFloor}楼起",
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (mode == LuckyDrawSheetMode.VERIFY && activeSpec != null) {
                    IconButton(
                        onClick = { reloadNonce += 1 },
                        enabled = !isLoading,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新开奖状态")
                    }
                }
                IconButton(
                    onClick = { if (!isPostingReply) onDismiss() },
                    enabled = !isPostingReply,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            if (allowGeneratorMode && activeSpec != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LuckyDrawSheetMode.entries.forEach { tab ->
                        FilterChip(
                            selected = mode == tab,
                            onClick = { mode = tab },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }

            PulseGradientDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (mode == LuckyDrawSheetMode.VERIFY && activeSpec != null) {
                    val currentSpec = activeSpec!!
                    LuckyDrawVerifierBody(
                        spec = currentSpec,
                        nowEpochMillis = nowEpochMillis,
                        isLoading = isLoading,
                        outcome = outcome,
                        errorMessage = errorMessage,
                        isViewerTopicAuthor = isViewerTopicAuthor,
                        showReplyPreview = showReplyPreview,
                        replyDraftMarkdown = replyDraftMarkdown,
                        isPostingReply = isPostingReply,
                        onRetry = { reloadNonce += 1 },
                        onCopyText = { label, text ->
                            copyToClipboard(context, label, text)
                            onFeedbackMessage("$label 已复制")
                        },
                        onToggleReplyPreview = {
                            if (outcome != null && replyDraftMarkdown.isBlank()) {
                                replyDraftMarkdown = DrandLuckyEngine.buildWinnerReplyMarkdown(outcome!!)
                            }
                            showReplyPreview = !showReplyPreview
                        },
                        onReplyDraftChanged = { replyDraftMarkdown = it },
                        onSubmitWinnerReply = {
                            val postId = currentSpec.postId
                            val body = replyDraftMarkdown.trim()
                            if (postId <= 0L || body.isEmpty()) return@LuckyDrawVerifierBody
                            scope.launch {
                                isPostingReply = true
                                val res = forumRepository.postReply(postId, body)
                                isPostingReply = false
                                res.fold(
                                    onSuccess = {
                                        showReplyPreview = false
                                        onFeedbackMessage("中奖名单已回帖公布")
                                        onWinnerReplyPosted?.invoke()
                                    },
                                    onFailure = { err ->
                                        onFeedbackMessage(err.message ?: "回帖公布失败")
                                    },
                                )
                            }
                        },
                        onOpenTopicFloor = onOpenTopicFloor,
                        onOpenUser = onOpenUser,
                        onOpenInBrowser = {
                            val url = currentSpec.rawUrl.ifBlank { DrandLuckyEngine.buildLuckyUrl(currentSpec) }
                            runCatching { uriHandler.openUri(url) }
                        },
                    )
                } else {
                    val parsedDrawMs = parseShanghaiDateTime(genDrawTimeText)
                    val parsedCount = genWinnerCountText.toIntOrNull()?.coerceIn(1, 100) ?: 1
                    val parsedFirstFloor = genFirstFloorText.toIntOrNull()?.coerceAtLeast(0) ?: 1
                    val generatedSpec = LuckyDrawSpec(
                        postId = genPostId,
                        drawTimeEpochMillis = parsedDrawMs,
                        winnerCount = parsedCount,
                        firstFloor = parsedFirstFloor,
                        removeDuplicates = genRemoveDuplicates,
                    )
                    val generatedUrl = if (genPostId > 0L && parsedDrawMs != null) {
                        DrandLuckyEngine.buildLuckyUrl(generatedSpec)
                    } else {
                        null
                    }

                    LuckyDrawGeneratorBody(
                        postId = genPostId,
                        drawTimeText = genDrawTimeText,
                        parsedDrawTimeMs = parsedDrawMs,
                        winnerCountText = genWinnerCountText,
                        firstFloorText = genFirstFloorText,
                        removeDuplicates = genRemoveDuplicates,
                        generatedUrl = generatedUrl,
                        onDrawTimeTextChange = { genDrawTimeText = it },
                        onPickPresetOffsetMillis = { deltaMs ->
                            genDrawTimeText = formatEpochShanghai(System.currentTimeMillis() + deltaMs)
                        },
                        onWinnerCountChange = { genWinnerCountText = it.filter(Char::isDigit).take(3) },
                        onFirstFloorChange = { genFirstFloorText = it.filter(Char::isDigit).take(4) },
                        onRemoveDuplicatesChange = { genRemoveDuplicates = it },
                        onCopyUrl = { url ->
                            copyToClipboard(context, "抽奖链接", url)
                            onFeedbackMessage("抽奖链接已复制")
                        },
                        onInsertIntoComposer = onInsertGeneratedLuckyMarkdown?.let { insert ->
                            {
                                val md = if (generatedUrl != null) {
                                    "[🎲 参与官方抽奖]($generatedUrl)"
                                } else {
                                    ""
                                }
                                insert(md, generatedSpec)
                            }
                        },
                        onAppendToOp = if (generatedUrl != null && onAppendLuckyToTopicOp != null) {
                            {
                                val md = "[🎲 参与官方抽奖]($generatedUrl)"
                                onAppendLuckyToTopicOp(md, generatedSpec)
                            }
                        } else {
                            null
                        },
                        onVerifyGenerated = if (generatedUrl != null && parsedDrawMs != null && genPostId > 0L) {
                            {
                                activeSpec = generatedSpec.copy(rawUrl = generatedUrl)
                                mode = LuckyDrawSheetMode.VERIFY
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun LuckyDrawVerifierBody(
    spec: LuckyDrawSpec,
    nowEpochMillis: Long,
    isLoading: Boolean,
    outcome: LuckyDrawOutcome?,
    errorMessage: String?,
    isViewerTopicAuthor: Boolean,
    showReplyPreview: Boolean,
    replyDraftMarkdown: String,
    isPostingReply: Boolean,
    onRetry: () -> Unit,
    onCopyText: (label: String, value: String) -> Unit,
    onToggleReplyPreview: () -> Unit,
    onReplyDraftChanged: (String) -> Unit,
    onSubmitWinnerReply: () -> Unit,
    onOpenTopicFloor: ((topicId: Long, floor: Int) -> Unit)?,
    onOpenUser: ((uid: Long, username: String) -> Unit)?,
    onOpenInBrowser: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val drawMs = spec.drawTimeEpochMillis

    // 1. Countdown / Drand Beacon Status Card
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Subtle,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (outcome?.isDrawn == true) Icons.Rounded.Verified else Icons.Rounded.Schedule,
                contentDescription = null,
                tint = if (outcome?.isDrawn == true) ext.drumstickGoldEnd else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = when {
                    drawMs == null -> "未指定开奖时间"
                    nowEpochMillis < drawMs -> "距开奖还剩 ${formatCountdown(drawMs - nowEpochMillis)}"
                    outcome?.beaconPendingMessage != null -> "等待 Drand 随机信标上链"
                    outcome?.isDrawn == true -> "已完成 Drand 链上开奖"
                    else -> "已到开奖时间，正在获取 Drand 信标"
                },
                style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
            )
        }

        if (drawMs != null) {
            Text(
                text = "开奖时间：${formatEpochShanghai(drawMs)} (Asia/Shanghai)",
                style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PulseAccentPill(text = "中奖人数 ${spec.winnerCount}")
            PulseAccentPill(text = "起始楼层 #${spec.firstFloor}")
            PulseAccentPill(text = if (spec.removeDuplicates) "同一用户限中 1 次" else "允许重复中奖")
            outcome?.drandRound?.let { round ->
                PulseAccentPill(
                    text = "Drand #$round",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        outcome?.beacon?.let { beacon ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onCopyText("Drand Randomness", beacon.randomness) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Randomness: ${beacon.randomness}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "复制 Randomness",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        outcome?.beaconPendingMessage?.let { pendingMsg ->
            Text(
                text = pendingMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (isLoading && outcome == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "正在校验参与楼层与 Drand 链上信标…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (!errorMessage.isNullOrBlank()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) {
                Text("重试加载")
            }
        }
    }

    if (outcome != null) {
        // 2. Highlighted "我是否中奖" / Eligibility Banner
        ViewerLuckyStatusBanner(outcome = outcome)

        // 3. Participant Count Summary
        Text(
            text = "已拉取 ${outcome.floors.size} 条楼层 · 符合规则（≥ #${spec.firstFloor}）共 ${outcome.eligibleFloorCount} 个有效名额",
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 4. Winners List
        if (outcome.isDrawn) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        elevation = PulseElevation.Subtle,
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.EmojiEvents,
                        contentDescription = null,
                        tint = ext.drumstickGoldEnd,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "中奖名单 (${outcome.winners.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (outcome.winners.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                onCopyText(
                                    "@中奖名单",
                                    DrandLuckyEngine.buildWinnerReplyMarkdown(outcome),
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("复制 @中奖名单")
                        }
                    }
                }

                if (outcome.winners.isEmpty()) {
                    Text(
                        text = "暂无符合条件的中奖楼层。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    outcome.winners.forEachIndexed { idx, winner ->
                        LuckyWinnerRow(
                            rank = idx + 1,
                            winner = winner,
                            isDark = isDark,
                            onFloorClick = onOpenTopicFloor?.let { open -> { open(spec.postId, winner.floorId) } },
                            onUserClick = onOpenUser?.let { open -> { open(winner.memberId, winner.memberName) } },
                        )
                    }
                }
            }
        }

        // 5. OP One-Tap Winner Reply Publisher
        if (isViewerTopicAuthor && outcome.isDrawn && spec.postId > 0L) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        elevation = PulseElevation.Subtle,
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "楼主发奖助手",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onToggleReplyPreview) {
                        Text(if (showReplyPreview) "收起编辑" else "预览/编辑回帖")
                    }
                }

                if (showReplyPreview) {
                    PulseTextField(
                        value = replyDraftMarkdown,
                        onValueChange = onReplyDraftChanged,
                        label = "中奖公示回帖内容 (Markdown)",
                        singleLine = false,
                        minLines = 4,
                        maxLines = 8,
                        enabled = !isPostingReply,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Button(
                    onClick = onSubmitWinnerReply,
                    enabled = !isPostingReply && outcome.winners.isNotEmpty(),
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 11.dp),
                ) {
                    if (isPostingReply) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (isPostingReply) "正在回帖公布…" else "一键回帖公布 @中奖名单",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    // 6. Footer Navigation Actions
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onOpenTopicFloor != null && spec.postId > 0L) {
            OutlinedButton(
                onClick = { onOpenTopicFloor(spec.postId, 0) },
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("前往原帖")
            }
        }
        OutlinedButton(
            onClick = onOpenInBrowser,
            shape = CircleShape,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text("浏览器打开")
        }
    }
}

@Composable
private fun ViewerLuckyStatusBanner(outcome: LuckyDrawOutcome) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    val (title, subtitle, bgColor, fgColor) = when (outcome.viewerStatus) {
        LuckyViewerStatus.MY_WIN -> {
            val winFloors = outcome.viewerWinningFloors.joinToString(", ") { "#${it.floorId}楼" }
            Quad(
                "🎉 恭喜！你已中奖！",
                "中奖楼层：$winFloors",
                ext.drumstickContainer,
                ext.onDrumstickContainer,
            )
        }
        LuckyViewerStatus.PARTICIPATED_NOT_WON -> {
            val myFloors = outcome.viewerFloors.joinToString(", ") { "#${it.floorId}" }
            Quad(
                "本次未中奖",
                "你在有效楼层参与了抽奖 ($myFloors)，感谢参与！",
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.onSurface,
            )
        }
        LuckyViewerStatus.PARTICIPATED_PENDING -> {
            val myFloors = outcome.viewerFloors
                .filter { it.floorId >= outcome.spec.firstFloor }
                .joinToString(", ") { "#${it.floorId}" }
            Quad(
                "✅ 已具备抽奖资格",
                "你的有效参与楼层：$myFloors · 等待 Drand 开奖",
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.72f else 0.85f),
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        LuckyViewerStatus.INELIGIBLE_FLOOR -> {
            val myFloors = outcome.viewerFloors.joinToString(", ") { "#${it.floorId}" }
            Quad(
                "楼层未满足起始要求",
                "你的楼层 ($myFloors) 低于起始楼层 #${outcome.spec.firstFloor}",
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        LuckyViewerStatus.NOT_PARTICIPATED -> {
            Quad(
                if (outcome.isDrawn) "未参与本次抽奖" else "尚未回复参与",
                if (outcome.isDrawn) "未在截止时间前找到你的回帖楼层" else "回复帖子到 #${outcome.spec.firstFloor} 楼及以后即可参与",
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LuckyViewerStatus.NOT_LOGGED_IN -> {
            Quad(
                "登录后可校验「我是否中奖」",
                "当前未登录 NodeSeek 账号，仍可查看公开中奖结果",
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.EditorialCard)
            .background(bgColor)
            .border(
                width = 0.9.dp,
                color = fgColor.copy(alpha = 0.25f),
                shape = PulseArchitecturalShapes.EditorialCard,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (outcome.viewerStatus == LuckyViewerStatus.MY_WIN) {
                Icons.Rounded.EmojiEvents
            } else {
                Icons.Rounded.CheckCircle
            },
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = fgColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = fgColor.copy(alpha = 0.9f),
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun LuckyWinnerRow(
    rank: Int,
    winner: LuckyFloorEntry,
    isDark: Boolean,
    onFloorClick: (() -> Unit)?,
    onUserClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (isDark) 0.7f else 0.9f))
            .clickable(enabled = onFloorClick != null || onUserClick != null) {
                onFloorClick?.invoke() ?: onUserClick?.invoke()
            }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseAccentPill(
            text = "#$rank",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = "@${winner.memberName}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .then(if (onUserClick != null) Modifier.clickable(onClick = onUserClick) else Modifier),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PulseAccentPill(
            text = "#${winner.floorId}楼",
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LuckyDrawGeneratorBody(
    postId: Long,
    drawTimeText: String,
    parsedDrawTimeMs: Long?,
    winnerCountText: String,
    firstFloorText: String,
    removeDuplicates: Boolean,
    generatedUrl: String?,
    onDrawTimeTextChange: (String) -> Unit,
    onPickPresetOffsetMillis: (Long) -> Unit,
    onWinnerCountChange: (String) -> Unit,
    onFirstFloorChange: (String) -> Unit,
    onRemoveDuplicatesChange: (Boolean) -> Unit,
    onCopyUrl: (String) -> Unit,
    onInsertIntoComposer: (() -> Unit)?,
    onAppendToOp: (() -> Unit)?,
    onVerifyGenerated: (() -> Unit)?,
) {
    Text(
        text = if (postId > 0L) {
            "配置帖子 #$postId 的 Drand 可验证抽奖参数，自动生成官方 `/lucky` 开奖链接。"
        } else {
            "设置开奖时间、中奖人数与起始楼层；发帖成功后将为您生成带真实 Post ID 的 `/lucky` 开奖链接。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "+10 分钟" to 10 * 60_000L,
            "+1 小时" to 3_600_000L,
            "+6 小时" to 6 * 3_600_000L,
            "+24 小时" to 24 * 3_600_000L,
        ).forEach { (label, offsetMs) ->
            FilterChip(
                selected = false,
                onClick = { onPickPresetOffsetMillis(offsetMs) },
                label = { Text(label) },
            )
        }
    }

    PulseTextField(
        value = drawTimeText,
        onValueChange = onDrawTimeTextChange,
        label = "开奖时间 (Asia/Shanghai: yyyy-MM-dd HH:mm)",
        placeholder = "2026-11-26 20:00",
        singleLine = true,
        isError = parsedDrawTimeMs == null,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseTextField(
            value = winnerCountText,
            onValueChange = onWinnerCountChange,
            label = "中奖人数",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        PulseTextField(
            value = firstFloorText,
            onValueChange = onFirstFloorChange,
            label = "起始楼层",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onRemoveDuplicatesChange(!removeDuplicates) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "每人最多中奖 1 次（去重）",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "对应官方参数 duplicate=${!removeDuplicates}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = removeDuplicates,
            onCheckedChange = onRemoveDuplicatesChange,
        )
    }

    if (generatedUrl != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "生成的 `/lucky` 链接",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = generatedUrl,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (onInsertIntoComposer != null) {
        Button(
            onClick = onInsertIntoComposer,
            enabled = parsedDrawTimeMs != null,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (postId > 0L) "插入抽奖链接到正文" else "保存抽奖配置（发帖后自动生成链接）",
                fontWeight = FontWeight.Bold,
            )
        }
    }

    if (onAppendToOp != null && generatedUrl != null) {
        Button(
            onClick = onAppendToOp,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("追加抽奖链接到主楼正文", fontWeight = FontWeight.Bold)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (generatedUrl != null) {
            OutlinedButton(
                onClick = { onCopyUrl(generatedUrl) },
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("复制链接")
            }
        }
        if (onVerifyGenerated != null) {
            OutlinedButton(
                onClick = onVerifyGenerated,
                shape = CircleShape,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(),
            ) {
                Text("立即校验开奖")
            }
        }
    }
}

private fun formatEpochShanghai(epochMillis: Long): String =
    LUCKY_DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(SHANGHAI_ZONE))

private fun parseShanghaiDateTime(input: String): Long? =
    runCatching {
        LocalDateTime.parse(input.trim(), LUCKY_DATE_FORMATTER)
            .atZone(SHANGHAI_ZONE)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()?.takeIf { it >= DrandLuckyEngine.DRAND_GENESIS_EPOCH_MILLIS }

private fun formatCountdown(remainingMillis: Long): String {
    val totalSec = (remainingMillis / 1_000L).coerceAtLeast(0L)
    val days = totalSec / 86_400L
    val hours = (totalSec % 86_400L) / 3_600L
    val mins = (totalSec % 3_600L) / 60L
    val secs = totalSec % 60L
    return when {
        days > 0L -> "${days}天 ${hours}小时 ${mins}分"
        hours > 0L -> "%02d:%02d:%02d".format(hours, mins, secs)
        else -> "%02d:%02d".format(mins, secs)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
