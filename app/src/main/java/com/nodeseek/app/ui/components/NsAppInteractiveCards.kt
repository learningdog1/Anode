@file:OptIn(ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.model.NsAppWidgetTarget
import com.nodeseek.app.domain.model.PollDetail
import com.nodeseek.app.domain.model.PollOption
import com.nodeseek.app.domain.model.StardustTipTarget
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class PollCardUiState(
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val isLocking: Boolean = false,
    val poll: PollDetail? = null,
    val selectedOptionIds: Set<Long> = emptySet(),
    val errorMessage: String? = null,
)

@Immutable
data class StardustCardUiState(
    val lastTippedAmount: Int? = null,
    val isTipped: Boolean = false,
)

interface NsAppWidgetController {
    val currentViewerUid: Long?
        get() = null

    fun pollStateFor(voteId: Long): StateFlow<PollCardUiState>
    fun stardustStateFor(widgetKey: String): StateFlow<StardustCardUiState>
    fun ensureVoteLoaded(voteId: Long)
    fun reloadVote(voteId: Long)
    fun togglePollOption(voteId: Long, optionId: Long, isMultiple: Boolean)
    fun submitVote(voteId: Long)
    fun lockVote(voteId: Long)
    fun openStardustTip(target: StardustTipTarget)
}

object NoOpNsAppWidgetController : NsAppWidgetController {
    private val emptyPollState = MutableStateFlow(PollCardUiState())
    private val emptyStardustState = MutableStateFlow(StardustCardUiState())

    override fun pollStateFor(voteId: Long): StateFlow<PollCardUiState> = emptyPollState
    override fun stardustStateFor(widgetKey: String): StateFlow<StardustCardUiState> = emptyStardustState
    override fun ensureVoteLoaded(voteId: Long) = Unit
    override fun reloadVote(voteId: Long) = Unit
    override fun togglePollOption(voteId: Long, optionId: Long, isMultiple: Boolean) = Unit
    override fun submitVote(voteId: Long) = Unit
    override fun lockVote(voteId: Long) = Unit
    override fun openStardustTip(target: StardustTipTarget) = Unit
}

@Composable
internal fun InteractiveVoteWidgetCard(
    target: NsAppWidgetTarget.Vote,
    fallbackTitle: String,
    controller: NsAppWidgetController,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(target.voteId) {
        controller.ensureVoteLoaded(target.voteId)
    }

    val stateFlow = remember(controller, target.voteId) {
        controller.pollStateFor(target.voteId)
    }
    val state by stateFlow.collectAsState()
    val poll = state.poll
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val accent = MaterialTheme.colorScheme.primary
    var confirmLockExpanded by remember(target.voteId) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = accent,
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(accent.copy(alpha = if (isDark) 0.22f else 0.13f))
                    .border(
                        width = 0.8.dp,
                        color = accent.copy(alpha = if (isDark) 0.42f else 0.25f),
                        shape = PulseArchitecturalShapes.SmallChip,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.HowToVote,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = poll?.title?.ifBlank { fallbackTitle } ?: fallbackTitle.ifBlank { "互动投票 #${target.voteId}" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (poll != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PulseAccentPill(
                            text = if (poll.isMultiple) "多选" else "单选",
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        PulseAccentPill(
                            text = if (poll.isPublic) "公开" else "匿名",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        if (poll.isLocked) {
                            PulseAccentPill(
                                text = "已锁定",
                                icon = Icons.Rounded.Lock,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (poll.hasVisibleCounts) {
                            PulseAccentPill(
                                text = "${poll.totalVotes} 票",
                                containerColor = ext.drumstickContainer.copy(alpha = 0.78f),
                                contentColor = ext.onDrumstickContainer,
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = { controller.reloadVote(target.voteId) },
                enabled = !state.isLoading && !state.isSubmitting && !state.isLocking,
                modifier = Modifier.size(32.dp),
            ) {
                if (state.isLoading && poll != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新投票",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        when {
            poll == null && state.isLoading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "正在加载实时投票选项…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            poll == null && !state.errorMessage.isNullOrBlank() -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { controller.reloadVote(target.voteId) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("重试")
                    }
                }
            }

            poll != null -> {
                val canVote = !poll.hasCurrentUserVoted && !poll.isLocked
                val showResults = poll.hasCurrentUserVoted || poll.isLocked || poll.hasVisibleCounts

                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    poll.options.forEach { option ->
                        PollOptionRow(
                            poll = poll,
                            option = option,
                            isSelected = option.voteItemId in state.selectedOptionIds,
                            canVote = canVote,
                            showResults = showResults,
                            onClick = {
                                if (canVote) {
                                    controller.togglePollOption(
                                        voteId = poll.id,
                                        optionId = option.voteItemId,
                                        isMultiple = poll.isMultiple,
                                    )
                                }
                            },
                        )
                    }
                }

                if (!poll.hasVisibleCounts && canVote) {
                    Text(
                        text = "投票后查看各选项实时票数与占比",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    Text(
                        text = state.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                val isCreator = controller.currentViewerUid != null &&
                    controller.currentViewerUid == poll.creatorUid &&
                    !poll.isLocked

                if (canVote || isCreator) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (canVote) {
                            Button(
                                onClick = { controller.submitVote(poll.id) },
                                enabled = state.selectedOptionIds.isNotEmpty() && !state.isSubmitting,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                if (state.isSubmitting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = if (state.isSubmitting) "提交中…" else "提交投票",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else if (poll.hasCurrentUserVoted) {
                            PulseAccentPill(
                                text = "已参与投票",
                                icon = Icons.Rounded.CheckCircle,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (isCreator) {
                            if (confirmLockExpanded) {
                                TextButton(
                                    onClick = { confirmLockExpanded = false },
                                    enabled = !state.isLocking,
                                ) {
                                    Text("取消")
                                }
                                OutlinedButton(
                                    onClick = {
                                        confirmLockExpanded = false
                                        controller.lockVote(poll.id)
                                    },
                                    enabled = !state.isLocking,
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = if (state.isLocking) "锁定中…" else "确认锁定（不可逆）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            } else {
                                TextButton(
                                    onClick = { confirmLockExpanded = true },
                                    enabled = !state.isLocking,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("锁定投票")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PollOptionRow(
    poll: PollDetail,
    option: PollOption,
    isSelected: Boolean,
    canVote: Boolean,
    showResults: Boolean,
    onClick: () -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val targetFraction = if (showResults && option.count != null) poll.shareFraction(option) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "pollOptionFraction",
    )
    val highlighted = isSelected || option.voted
    val borderColor by animateColorAsState(
        targetValue = when {
            highlighted -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        label = "pollOptionBorder",
    )
    val barColor = if (option.voted) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.28f else 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (isDark) 0.55f else 0.80f)
    }
    val shape = RoundedCornerShape(11.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (isDark) 0.55f else 0.75f))
            .border(width = if (highlighted) 1.2.dp else 0.8.dp, color = borderColor, shape = shape)
            .then(
                if (canVote) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        if (animatedFraction > 0f) {
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction)
                        .fillMaxHeight()
                        .background(barColor),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canVote) {
                    val icon = when {
                        poll.isMultiple && isSelected -> Icons.Rounded.CheckBox
                        poll.isMultiple -> Icons.Rounded.CheckBoxOutlineBlank
                        isSelected -> Icons.Rounded.RadioButtonChecked
                        else -> Icons.Rounded.RadioButtonUnchecked
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                } else if (option.voted) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                }

                Text(
                    text = option.text.ifBlank { "选项 #${option.voteItemId}" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )

                if (option.voted) {
                    PulseAccentPill(
                        text = "已投",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                if (showResults && option.count != null) {
                    Text(
                        text = "${option.count} 票 (${poll.sharePercent(option)}%)",
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                        color = if (option.voted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (poll.isPublic && option.voters.isNotEmpty()) {
                val previewUids = option.voters.take(6).joinToString(", ") { "UID $it" }
                val moreSuffix = if (option.voters.size > 6) " 等 ${option.voters.size} 人" else ""
                Text(
                    text = "公开投票: $previewUids$moreSuffix",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun InteractiveStardustWidgetCard(
    target: NsAppWidgetTarget.StardustReceive,
    fallbackTitle: String,
    controller: NsAppWidgetController,
    modifier: Modifier = Modifier,
) {
    val widgetKey = remember(target.rawUrl) { target.rawUrl }
    val stateFlow = remember(controller, widgetKey) {
        controller.stardustStateFor(widgetKey)
    }
    val cardState by stateFlow.collectAsState()
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val accent = ext.stardustAccent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = accent,
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.cardPressScale,
                onClick = {
                    controller.openStardustTip(
                        StardustTipTarget(
                            receiverUid = target.receiverUid,
                            suggestedAmount = target.suggestedAmount,
                            refId = target.refId,
                            once = target.once,
                            note = target.note,
                            widgetKey = widgetKey,
                        ),
                    )
                },
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(PulseArchitecturalShapes.SmallChip)
                .background(accent.copy(alpha = if (isDark) 0.22f else 0.13f))
                .border(
                    width = 0.8.dp,
                    color = accent.copy(alpha = if (isDark) 0.42f else 0.25f),
                    shape = PulseArchitecturalShapes.SmallChip,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = fallbackTitle.ifBlank { "星尘打赏收款" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (target.note.isNotBlank()) {
                Text(
                    text = target.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "收款人 UID ${target.receiverUid}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ext.onStardustContainer.copy(alpha = 0.88f),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                target.suggestedAmount?.let { amount ->
                    PulseAccentPill(
                        text = "建议 $amount 星尘",
                        containerColor = ext.stardustContainer,
                        contentColor = ext.onStardustContainer,
                    )
                }
                if (target.once) {
                    PulseAccentPill(
                        text = "一次性打赏",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (cardState.isTipped) {
                    PulseAccentPill(
                        text = cardState.lastTippedAmount?.let { "已投喂 $it 星尘" } ?: "已投喂",
                        icon = Icons.Rounded.CheckCircle,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        Button(
            onClick = {
                controller.openStardustTip(
                    StardustTipTarget(
                        receiverUid = target.receiverUid,
                        suggestedAmount = target.suggestedAmount,
                        refId = target.refId,
                        once = target.once,
                        note = target.note,
                        widgetKey = widgetKey,
                    ),
                )
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
        ) {
            Text(
                text = if (cardState.isTipped) "再投喂" else "投喂",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
