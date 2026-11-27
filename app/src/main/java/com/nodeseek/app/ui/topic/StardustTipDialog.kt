@file:OptIn(ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.model.StardustTipTarget
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.withTabularNumbers

@Immutable
data class StardustTipDialogUiState(
    val target: StardustTipTarget,
    val isPreparing: Boolean = true,
    val isSending: Boolean = false,
    val resolvedReceiverName: String = target.receiverNameHint,
    val amountInput: String = target.suggestedAmount?.toString() ?: "10",
    val prepareError: String? = null,
    val sendError: String? = null,
)

private val QUICK_STARDUST_AMOUNTS = listOf(1, 5, 10, 20, 50)

@Composable
fun StardustTipDialog(
    state: StardustTipDialogUiState,
    viewerUid: Long?,
    viewerBalance: Int?,
    onAmountChanged: (String) -> Unit,
    onRetryPrepare: () -> Unit,
    onConfirmSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val parsedAmount = state.amountInput.trim().toIntOrNull()
    val isSelfTip = viewerUid != null && viewerUid == state.target.receiverUid
    val exceedsBalance = viewerBalance != null && parsedAmount != null && parsedAmount > viewerBalance
    val validAmount = parsedAmount != null && parsedAmount > 0

    val canSubmit = !state.isPreparing &&
        !state.isSending &&
        state.prepareError == null &&
        !isSelfTip &&
        validAmount &&
        !exceedsBalance

    val displayReceiver = state.resolvedReceiverName.trim().ifBlank {
        "UID ${state.target.receiverUid}"
    }

    PulseAlertDialog(
        onDismissRequest = { if (!state.isSending) onDismiss() },
        title = "投喂星尘 ✨",
        icon = Icons.Rounded.AutoAwesome,
        tone = PulseDialogTone.WARM,
        accentColor = ext.stardustAccent,
        confirmText = if (state.isSending) "转账中…" else "确认投喂",
        onConfirm = onConfirmSend,
        confirmEnabled = canSubmit,
        confirmLoading = state.isSending,
        dismissText = "取消",
        onDismiss = { if (!state.isSending) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ext.stardustContainer.copy(alpha = 0.52f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "收款人：@$displayReceiver",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "UID ${state.target.receiverUid}",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (viewerBalance != null) {
                    PulseAccentPill(
                        text = "可用 $viewerBalance",
                        containerColor = ext.stardustContainer,
                        contentColor = ext.onStardustContainer,
                    )
                }
            }

            if (state.target.note.isNotBlank()) {
                Text(
                    text = "附言：${state.target.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isPreparing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "正在核验接收者账号…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (!state.prepareError.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.prepareError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetryPrepare) {
                        Text("重试")
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QUICK_STARDUST_AMOUNTS.forEach { preset ->
                    val selected = parsedAmount == preset
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (selected) {
                                    ext.stardustAccent.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            )
                            .border(
                                width = if (selected) 1.1.dp else 0.75.dp,
                                color = if (selected) {
                                    ext.stardustAccent
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                },
                                shape = CircleShape,
                            )
                            .clickable(enabled = !state.isSending) {
                                onAmountChanged(preset.toString())
                            }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "$preset 星尘",
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) ext.stardustAccent else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            PulseTextField(
                value = state.amountInput,
                onValueChange = { raw ->
                    onAmountChanged(raw.filter { it.isDigit() }.take(6))
                },
                label = "投喂数量（星尘）",
                placeholder = "请输入正整数",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !state.isSending,
                modifier = Modifier.fillMaxWidth(),
            )

            when {
                isSelfTip -> {
                    Text(
                        text = "不能给自己投喂星尘",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                !validAmount && state.amountInput.isNotBlank() -> {
                    Text(
                        text = "请输入大于 0 的星尘数量",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                exceedsBalance -> {
                    Text(
                        text = "星尘余额不足（当前可用 $viewerBalance）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                !state.sendError.isNullOrBlank() -> {
                    Text(
                        text = state.sendError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
