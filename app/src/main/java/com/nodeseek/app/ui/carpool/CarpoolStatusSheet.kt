package com.nodeseek.app.ui.carpool

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.carpool.CarpoolStatusUpdater
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers

private val QUICK_CARPOOL_TAGS = listOf("3=1", "2=1", "2=2", "4=1", CarpoolStatusUpdater.FULL_CARPOOL_LABEL)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CarpoolStatusSheet(
    topicTitle: String,
    isMutating: Boolean,
    onUpdateStatus: (statusToken: String, occupiedSeats: Int?, totalSeats: Int?, updateBody: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val carpoolSpec = NodePulseThemeTokens.categorySpec("carpool")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val parsedInitial = remember(topicTitle) { CarpoolStatusUpdater.parseStatusToken(topicTitle) }
    val initialOccupied = parsedInitial?.occupiedSeats ?: 3
    val initialTotal = if (parsedInitial?.occupiedSeats != null && parsedInitial.remainingSeats != null) {
        (parsedInitial.occupiedSeats + parsedInitial.remainingSeats).coerceIn(2, 32)
    } else {
        4
    }

    var totalSeats by rememberSaveable(topicTitle) { mutableIntStateOf(initialTotal) }
    var occupiedSeats by rememberSaveable(topicTitle) {
        mutableIntStateOf(initialOccupied.coerceIn(1, initialTotal))
    }
    var customTagOverride by rememberSaveable(topicTitle) { mutableStateOf<String?>(null) }
    var alsoUpdateBody by rememberSaveable { mutableStateOf(true) }

    val activeTag = customTagOverride ?: CarpoolStatusUpdater.formatStatusTag(occupiedSeats, totalSeats)
    val previewTitle = remember(topicTitle, activeTag) {
        CarpoolStatusUpdater.updateTitle(topicTitle, activeTag)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(carpoolSpec.spineColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsCar,
                        contentDescription = null,
                        tint = carpoolSpec.spineColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "一键更新拼车状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "自动保留权限等级，仅更新标题与正文车位标记",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PulseAccentPill(
                    text = activeTag,
                    containerColor = carpoolSpec.spineColor.copy(alpha = 0.16f),
                    contentColor = carpoolSpec.spineColor,
                )
            }

            // Title Preview
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        containerColor = ext.cardSurface,
                        elevation = PulseElevation.None,
                        accentColor = carpoolSpec.spineColor,
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "更新后标题预览",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = carpoolSpec.spineColor,
                )
                Text(
                    text = previewTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Quick 1-tap Presets
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        containerColor = ext.cardSurface,
                        elevation = PulseElevation.None,
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "快捷车位标签",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QUICK_CARPOOL_TAGS.forEach { tag ->
                        FilterChip(
                            selected = activeTag == tag,
                            onClick = {
                                customTagOverride = tag
                                val parsed = CarpoolStatusUpdater.parseStatusToken(tag)
                                if (parsed?.occupiedSeats != null && parsed.remainingSeats != null) {
                                    occupiedSeats = parsed.occupiedSeats
                                    totalSeats = (parsed.occupiedSeats + parsed.remainingSeats).coerceIn(2, 32)
                                } else if (parsed?.isFull == true) {
                                    occupiedSeats = totalSeats
                                }
                            },
                            label = {
                                Text(
                                    text = tag,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusSeatStepper(
                        label = "已上车",
                        value = occupiedSeats,
                        onDec = {
                            customTagOverride = null
                            if (occupiedSeats > 1) occupiedSeats--
                        },
                        onInc = {
                            customTagOverride = null
                            if (occupiedSeats < totalSeats) occupiedSeats++
                        },
                        modifier = Modifier.weight(1f),
                    )
                    StatusSeatStepper(
                        label = "总车位",
                        value = totalSeats,
                        onDec = {
                            customTagOverride = null
                            if (totalSeats > 2) {
                                totalSeats--
                                if (occupiedSeats > totalSeats) occupiedSeats = totalSeats
                            }
                        },
                        onInc = {
                            customTagOverride = null
                            if (totalSeats < 32) totalSeats++
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = alsoUpdateBody,
                        onCheckedChange = { alsoUpdateBody = it },
                    )
                    Text(
                        text = "同步更新主楼正文车位状态",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onUpdateStatus(
                            CarpoolStatusUpdater.FULL_CARPOOL_LABEL,
                            totalSeats,
                            totalSeats,
                            alsoUpdateBody,
                        )
                    },
                    enabled = !isMutating,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "一键已满车",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = {
                        val occ = if (activeTag == CarpoolStatusUpdater.FULL_CARPOOL_LABEL) {
                            totalSeats
                        } else {
                            occupiedSeats
                        }
                        onUpdateStatus(activeTag, occ, totalSeats, alsoUpdateBody)
                    },
                    enabled = !isMutating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = carpoolSpec.spineColor,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 11.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (isMutating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    Text(
                        text = if (isMutating) "提交中…" else "更新为 [$activeTag]",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarpoolQuickStatusBar(
    topicTitle: String,
    isMutating: Boolean,
    onQuickSelectTag: (String) -> Unit,
    onOpenStatusSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val carpoolSpec = NodePulseThemeTokens.categorySpec("carpool")
    val currentStatus = remember(topicTitle) { CarpoolStatusUpdater.parseStatusToken(topicTitle) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                elevation = PulseElevation.Subtle,
                accentColor = carpoolSpec.spineColor,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Groups,
                contentDescription = null,
                tint = carpoolSpec.spineColor,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = "车主快捷车位管理",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PulseAccentPill(
                text = currentStatus?.rawToken ?: "拼车中",
                containerColor = carpoolSpec.spineColor.copy(alpha = 0.14f),
                contentColor = carpoolSpec.spineColor,
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("3=1", "2=1", CarpoolStatusUpdater.FULL_CARPOOL_LABEL).forEach { tag ->
                FilterChip(
                    selected = currentStatus?.rawToken == tag,
                    enabled = !isMutating,
                    onClick = { onQuickSelectTag(tag) },
                    label = {
                        Text(
                            text = tag,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
            OutlinedButton(
                onClick = onOpenStatusSheet,
                enabled = !isMutating,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "自定义车位",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusSeatStepper(
    label: String,
    value: Int,
    onDec: () -> Unit,
    onInc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDec, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Remove, contentDescription = "减少", modifier = Modifier.size(15.dp))
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onInc, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = "增加", modifier = Modifier.size(15.dp))
            }
        }
    }
}
