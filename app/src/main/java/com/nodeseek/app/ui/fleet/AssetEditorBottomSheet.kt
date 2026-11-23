@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.fleet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val ROUTE_TAG_PRESETS = VpsAssetRecord.DEFAULT_TAG_PRESETS
private val REMIND_DAY_PRESETS = listOf(0 to "不提醒", 3 to "提前 3 天", 7 to "提前 7 天", 15 to "提前 15 天", 30 to "提前 30 天")
private val TRAFFIC_RESET_DAY_PRESETS = listOf(
    null to "跟随到期日",
    1 to "每月 1 号",
    10 to "每月 10 号",
    15 to "每月 15 号",
    20 to "每月 20 号",
    31 to "月末 31 号",
)

/**
 * Add / Edit VPS Asset Ledger record modal bottom sheet.
 * Also serves as the prefilled target for `prefillAssetFromRestock(...)` and Probe -> Ledger import.
 */
@Composable
internal fun AssetEditorBottomSheet(
    draft: AssetEditorDraft,
    availableProbes: List<ProbeServerSnapshot>,
    onSave: (VpsAssetRecord) -> Unit,
    onDismiss: () -> Unit,
    saveError: String? = null,
    onClearSaveError: () -> Unit = {},
    existingTags: List<String> = emptyList(),
    initialTags: List<String>? = null,
    initialAutoRenew: Boolean? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    val ext = NodePulseThemeTokens.extendedColors

    val stringListSaver = remember {
        listSaver<List<String>, String>(
            save = { it.toList() },
            restore = { it.toList() },
        )
    }
    val draftKey = draft.editingId ?: "new:${draft.linkedProbeKey.orEmpty()}:${draft.purchaseUrl}"
    var nickname by rememberSaveable(draftKey, draft) { mutableStateOf(draft.nickname) }
    var providerName by rememberSaveable(draftKey, draft) { mutableStateOf(draft.providerName) }
    var region by rememberSaveable(draftKey, draft) { mutableStateOf(draft.region) }
    var selectedTags by rememberSaveable(draftKey, draft, initialTags, stateSaver = stringListSaver) {
        mutableStateOf(
            VpsAssetRecord.normalizeTags(
                initialTags ?: VpsAssetRecord.parseLegacyTags(draft.routeTag),
            ),
        )
    }
    var customTagInput by rememberSaveable(draftKey, draft) { mutableStateOf("") }
    var specsSummary by rememberSaveable(draftKey, draft) { mutableStateOf(draft.specsSummary) }
    var renewalAmount by rememberSaveable(draftKey, draft) { mutableStateOf(draft.renewalAmount) }
    var currency by rememberSaveable(draftKey, draft) { mutableStateOf(draft.currency) }
    var cyclePreset by rememberSaveable(draftKey, draft) { mutableStateOf(draft.cyclePreset) }
    var customCycleDays by rememberSaveable(draftKey, draft) { mutableStateOf(draft.customCycleDays) }
    var expiryDateIso by rememberSaveable(draftKey, draft) { mutableStateOf(draft.expiryDateIso) }
    var remindDays by rememberSaveable(draftKey, draft) { mutableIntStateOf(draft.remindDaysBeforeExpiry) }
    var autoRenew by rememberSaveable(draftKey, draft, initialAutoRenew) { mutableStateOf(initialAutoRenew ?: false) }
    var monthlyTrafficQuotaGb by rememberSaveable(draftKey, draft) { mutableStateOf(draft.monthlyTrafficQuotaGb) }
    var trafficResetDayText by rememberSaveable(draftKey, draft) { mutableStateOf(draft.trafficResetDay?.toString().orEmpty()) }
    var linkedProbeKey by rememberSaveable(draftKey, draft) { mutableStateOf(draft.linkedProbeKey) }
    var purchaseUrl by rememberSaveable(draftKey, draft) { mutableStateOf(draft.purchaseUrl) }
    var panelManageUrl by rememberSaveable(draftKey, draft) { mutableStateOf(draft.panelManageUrl) }
    var autoRenewNote by rememberSaveable(draftKey, draft) { mutableStateOf(draft.autoRenewNote) }
    var notes by rememberSaveable(draftKey, draft) { mutableStateOf(draft.notes) }

    val candidateTags = remember(existingTags) {
        VpsAssetRecord.mergeTags(ROUTE_TAG_PRESETS, existingTags)
    }
    val selectedTagKeys = remember(selectedTags) {
        selectedTags.map { it.lowercase(Locale.ROOT) }.toSet()
    }
    fun commitCustomTag(raw: String = customTagInput) {
        val updated = commitCustomTagInput(
            selectedTags = selectedTags,
            rawInput = raw,
            candidateTags = candidateTags,
        )
        selectedTags = updated
        customTagInput = ""
    }

    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

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
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (draft.editingId == null) "录入 VPS 资产" else "编辑 VPS 资产",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. Machine Profile
                PulseSectionHeader(title = "机器与线路信息", icon = Icons.Rounded.Memory)
                PulseTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = "资产备注名称 *",
                    placeholder = "例如：瓦工 HK85 主力鸡",
                    singleLine = true,
                    containerColor = ext.cardSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PulseTextField(
                        value = providerName,
                        onValueChange = { providerName = it },
                        label = "商家名称",
                        placeholder = "Bandwagon / DMIT",
                        singleLine = true,
                        containerColor = ext.cardSurface,
                        modifier = Modifier.weight(1f),
                    )
                    PulseTextField(
                        value = region,
                        onValueChange = { region = it },
                        label = "机房 / 地区",
                        placeholder = "洛杉矶 / 东京 / 香港",
                        singleLine = true,
                        containerColor = ext.cardSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                PulseTextField(
                    value = specsSummary,
                    onValueChange = { specsSummary = it },
                    label = "硬件配置摘要",
                    placeholder = "1C / 1G / 20G NVMe / 1TB",
                    singleLine = true,
                    containerColor = ext.cardSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "线路与资产标签（可多选）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selectedTags.isNotEmpty()) {
                        Text(
                            text = "已选 ${selectedTags.size} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (selectedTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        selectedTags.forEach { tag ->
                            SelectedAssetTagChip(
                                label = tag,
                                onRemove = { selectedTags = toggleAssetTag(selectedTags, tag) },
                            )
                        }
                    }
                }
                PulseTextField(
                    value = customTagInput,
                    onValueChange = { incoming ->
                        if (incoming.contains('\n') || incoming.contains('\r')) {
                            commitCustomTag(incoming)
                        } else {
                            customTagInput = incoming
                        }
                    },
                    label = "自定义标签（按回车添加）",
                    placeholder = "输入自定义标签后按回车创建",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { commitCustomTag() },
                    ),
                    trailingIcon = if (customTagInput.isNotBlank()) {
                        {
                            IconButton(onClick = { commitCustomTag() }) {
                                Icon(Icons.Rounded.Add, contentDescription = "添加标签")
                            }
                        }
                    } else {
                        null
                    },
                    containerColor = ext.cardSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) {
                                if (keyEvent.type == KeyEventType.KeyUp) {
                                    commitCustomTag()
                                }
                                true
                            } else {
                                false
                            }
                        },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    candidateTags.forEach { tag ->
                        AssetTagToggleChip(
                            label = tag,
                            selected = tag.lowercase(Locale.ROOT) in selectedTagKeys,
                            onClick = { selectedTags = toggleAssetTag(selectedTags, tag) },
                        )
                    }
                }

                PulseGradientDivider()

                // 2. Billing & Expiry
                PulseSectionHeader(title = "续费价格与到期周期", icon = Icons.Rounded.CurrencyExchange)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(PulseArchitecturalShapes.MediumBadge)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                PulseArchitecturalShapes.MediumBadge,
                            )
                            .clickable { showCurrencyPicker = true }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${currency.symbol} ${currency.code}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = "选择币种",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    PulseTextField(
                        value = renewalAmount,
                        onValueChange = { renewalAmount = sanitizeAssetDecimal(it) },
                        label = "周期续费金额 *",
                        placeholder = "请输入金额",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        containerColor = ext.cardSurface,
                        modifier = Modifier.weight(1f),
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val visibleCyclePresets = remember(cyclePreset) {
                        BillingCyclePreset.entries.filter { preset ->
                            preset != BillingCyclePreset.CALENDAR_NATURAL || cyclePreset == BillingCyclePreset.CALENDAR_NATURAL
                        }
                    }
                    visibleCyclePresets.forEach { preset ->
                        FleetChoiceChip(
                            label = preset.shortLabel,
                            selected = cyclePreset == preset,
                            onClick = { cyclePreset = preset },
                        )
                    }
                }

                AnimatedVisibility(visible = cyclePreset == BillingCyclePreset.CUSTOM) {
                    PulseTextField(
                        value = customCycleDays,
                        onValueChange = { customCycleDays = it.filter(Char::isDigit).take(4) },
                        label = "自定义周期天数",
                        placeholder = "例如：365",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        containerColor = ext.cardSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Expiry Date + Quick Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseTextField(
                        value = expiryDateIso,
                        onValueChange = { expiryDateIso = it },
                        label = "到期日期 (yyyy-MM-dd) *",
                        placeholder = "例如：2027-11-19",
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Rounded.CalendarMonth, contentDescription = "选择日期")
                            }
                        },
                        containerColor = ext.cardSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                val today = remember { VpsValuationEngine.todayInShanghai() }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "+30天" to today.plusDays(30),
                        "+90天" to today.plusDays(90),
                        "+180天" to today.plusDays(180),
                        "+1年" to today.plusYears(1),
                    ).forEach { (label, date) ->
                        FleetChoiceChip(
                            label = label,
                            selected = false,
                            onClick = { expiryDateIso = DateTimeFormatter.ISO_LOCAL_DATE.format(date) },
                        )
                    }
                }

                Text(
                    text = "到期提醒窗口",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    REMIND_DAY_PRESETS.forEach { (days, label) ->
                        FleetChoiceChip(
                            label = label,
                            selected = remindDays == days,
                            onClick = { remindDays = days },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(ext.cardSurface)
                        .border(0.8.dp, ext.cardBorder, PulseArchitecturalShapes.MediumBadge)
                        .toggleable(
                            value = autoRenew,
                            role = Role.Switch,
                            onValueChange = { autoRenew = it },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "自动续费",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "到期后自动按计费周期顺延到期日",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoRenew,
                        onCheckedChange = null,
                    )
                }

                PulseGradientDivider()

                // 3. Bind to Probe Selector
                if (availableProbes.isNotEmpty()) {
                    PulseSectionHeader(title = "关联探针节点（选填）", icon = Icons.Rounded.Dns)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FleetChoiceChip(
                            label = "不绑定",
                            selected = linkedProbeKey == null,
                            onClick = { linkedProbeKey = null },
                        )
                        availableProbes.forEach { snap ->
                            val label = buildString {
                                if (snap.resolvedFlagEmoji.isNotBlank()) append(snap.resolvedFlagEmoji).append(" ")
                                append(snap.name)
                            }
                            FleetChoiceChip(
                                label = label,
                                selected = linkedProbeKey == snap.probeKey,
                                onClick = { linkedProbeKey = snap.probeKey },
                            )
                        }
                    }
                }

                // 4. Optional Links & Notes
                PulseSectionHeader(title = "配额、重置日与备注", icon = Icons.Rounded.Link)
                PulseTextField(
                    value = monthlyTrafficQuotaGb,
                    onValueChange = { monthlyTrafficQuotaGb = sanitizeAssetDecimal(it) },
                    label = "月流量配额 (GB，选填)",
                    placeholder = "例如：1000",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    containerColor = ext.cardSurface,
                    modifier = Modifier.fillMaxWidth(),
                )

                val expiryFallbackDay = remember(expiryDateIso) {
                    FleetPortfolioCalculator.parseExpiryDate(expiryDateIso)?.dayOfMonth
                }
                val currentResetDayInt = trafficResetDayText.trim().toIntOrNull()
                val resetDaySupportingText = when {
                    currentResetDayInt != null && currentResetDayInt in 1..31 ->
                        "流量重置日：每月 $currentResetDayInt 号（短月自动对齐月末，用于流量耗尽预测与 80% 预警）"
                    expiryFallbackDay != null ->
                        "未单独指定 · 默认跟随到期日（每月 $expiryFallbackDay 号）"
                    else ->
                        "未单独指定 · 默认每月 1 号"
                }
                PulseTextField(
                    value = trafficResetDayText,
                    onValueChange = { trafficResetDayText = it.filter(Char::isDigit).take(2) },
                    label = "流量重置日：每月 X 号（1-31）",
                    placeholder = expiryFallbackDay?.let { "留空默认每月 $it 号" } ?: "留空默认每月 1 号",
                    supportingText = resetDaySupportingText,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    containerColor = ext.cardSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TRAFFIC_RESET_DAY_PRESETS.forEach { (presetDay, label) ->
                        val isSelected = if (presetDay == null) {
                            trafficResetDayText.isBlank()
                        } else {
                            currentResetDayInt == presetDay
                        }
                        FleetChoiceChip(
                            label = label,
                            selected = isSelected,
                            onClick = {
                                trafficResetDayText = presetDay?.toString().orEmpty()
                            },
                        )
                    }
                }
                PulseTextField(
                    value = purchaseUrl,
                    onValueChange = { purchaseUrl = it },
                    label = "购买页 / 订单链接（选填）",
                    placeholder = "https://...",
                    singleLine = true,
                    containerColor = ext.cardSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                PulseTextField(
                    value = panelManageUrl,
                    onValueChange = { panelManageUrl = it },
                    label = "控制面板链接（选填）",
                    placeholder = "https://...",
                    singleLine = true,
                    containerColor = ext.cardSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                PulseTextField(
                    value = autoRenewNote,
                    onValueChange = { autoRenewNote = it },
                    label = "续费备注 / 优惠码说明（选填）",
                    placeholder = "例如：终身循环 7 折 / PayPal 自动扣费",
                    singleLine = true,
                    containerColor = ext.cardSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                PulseTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = "备注（选填）",
                    placeholder = "记录账号邮箱、IP 解锁或其他备忘…",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    containerColor = ext.cardSurface,
                    modifier = Modifier.fillMaxWidth(),
                )

                (validationError ?: saveError)?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
            }

            // Sticky Save Button Row
            PulseGradientDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.weight(0.4f),
                ) {
                    Text(
                        text = "取消",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = {
                        val parsedAmount = renewalAmount.trim().toBigDecimalOrNull()
                        val parsedDate = FleetPortfolioCalculator.parseExpiryDate(expiryDateIso)
                        val trimmedResetDay = trafficResetDayText.trim()
                        val parsedResetDay = trimmedResetDay.takeIf { it.isNotEmpty() }?.toIntOrNull()
                        val hasInvalidResetDay = trimmedResetDay.isNotEmpty() &&
                            (parsedResetDay == null || parsedResetDay !in 1..31)
                        when {
                            nickname.isBlank() && providerName.isBlank() -> {
                                validationError = "请填写资产备注名称或商家名称"
                            }
                            parsedAmount == null || parsedAmount.signum() <= 0 -> {
                                validationError = "请填写大于 0 的续费金额"
                            }
                            parsedDate == null -> {
                                validationError = "到期日期格式须为 yyyy-MM-dd"
                            }
                            hasInvalidResetDay -> {
                                validationError = "流量重置日须填写 1～31 之间的日期"
                            }
                            else -> {
                                validationError = null
                                onClearSaveError()
                                val finalTags = commitCustomTagInput(
                                    selectedTags = selectedTags,
                                    rawInput = customTagInput,
                                    candidateTags = candidateTags,
                                )
                                selectedTags = finalTags
                                customTagInput = ""
                                onSave(
                                    VpsAssetRecord(
                                        id = draft.editingId ?: UUID.randomUUID().toString(),
                                        nickname = nickname.trim().ifBlank { providerName.trim() },
                                        providerName = providerName.trim(),
                                        specsSummary = specsSummary.trim(),
                                        region = region.trim(),
                                        routeTag = VpsAssetRecord.formatRouteTag(finalTags),
                                        renewalAmount = renewalAmount.trim(),
                                        currency = currency,
                                        cyclePreset = cyclePreset,
                                        customCycleDays = if (cyclePreset == BillingCyclePreset.CUSTOM) {
                                            customCycleDays.toIntOrNull()?.coerceIn(1, 3660) ?: 365
                                        } else {
                                            null
                                        },
                                        expiryDateIso = DateTimeFormatter.ISO_LOCAL_DATE.format(parsedDate),
                                        purchaseUrl = purchaseUrl.trim(),
                                        panelManageUrl = panelManageUrl.trim(),
                                        linkedProbeKey = linkedProbeKey,
                                        monthlyTrafficQuotaGb = monthlyTrafficQuotaGb.trim().toDoubleOrNull()?.takeIf { it > 0.0 },
                                        trafficResetDay = parsedResetDay,
                                        remindDaysBeforeExpiry = remindDays,
                                        autoRenewNote = autoRenewNote.trim(),
                                        notes = notes.trim(),
                                        tags = finalTags,
                                        autoRenew = autoRenew,
                                    ),
                                )
                            }
                        }
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.weight(0.6f),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            draft.openSalePostAfterSave -> "保存并生成出售帖"
                            draft.editingId == null -> "加入资产账本"
                            else -> "保存修改"
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // Currency Picker Dialog
    if (showCurrencyPicker) {
        PulseAlertDialog(
            onDismissRequest = { showCurrencyPicker = false },
            title = "选择续费币种",
            confirmText = "关闭",
            onConfirm = { showCurrencyPicker = false },
            dismissText = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ValuationCurrency.entries.forEach { item ->
                    val isSelected = item == currency
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else ext.cardSurface,
                            )
                            .clickable {
                                currency = item
                                showCurrencyPicker = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${item.code} (${item.symbol}) · ${item.displayNameZh}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // Expiry Date Picker Dialog
    if (showDatePicker) {
        val initialMillis = remember(expiryDateIso) {
            val date = FleetPortfolioCalculator.parseExpiryDate(expiryDateIso)
                ?: VpsValuationEngine.todayInShanghai()
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            expiryDateIso = DateTimeFormatter.ISO_LOCAL_DATE.format(picked)
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("取消")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun SelectedAssetTagChip(
    label: String,
    onRemove: () -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.55f else 0.40f)
    Row(
        modifier = Modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, borderColor, PulseArchitecturalShapes.SmallChip)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClick = onRemove,
            )
            .padding(start = 10.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "移除标签 $label",
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun AssetTagToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.55f else 0.40f)
    } else {
        ext.cardBorder
    }

    Row(
        modifier = Modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(bg)
            .border(if (selected) 1.dp else 0.8.dp, borderColor, PulseArchitecturalShapes.SmallChip)
            .semantics { toggleableState = ToggleableState(selected) }
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Checkbox,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun commitCustomTagInput(
    selectedTags: List<String>,
    rawInput: String,
    candidateTags: List<String> = VpsAssetRecord.DEFAULT_TAG_PRESETS,
): List<String> {
    val parsed = VpsAssetRecord.parseCustomTagInput(rawInput)
    if (parsed.isEmpty()) return selectedTags
    val canonicalByLower = candidateTags.associateBy { it.lowercase(Locale.ROOT) }
    val canonicalized = parsed.map { token ->
        canonicalByLower[token.lowercase(Locale.ROOT)] ?: token
    }
    return VpsAssetRecord.mergeTags(selectedTags, canonicalized)
}

internal fun toggleAssetTag(
    selectedTags: List<String>,
    tag: String,
): List<String> {
    val clean = VpsAssetRecord.normalizeTag(tag)
    if (clean.isEmpty()) return selectedTags
    val exists = selectedTags.any { it.equals(clean, ignoreCase = true) }
    return if (exists) {
        selectedTags.filterNot { it.equals(clean, ignoreCase = true) }
    } else {
        VpsAssetRecord.mergeTags(selectedTags, listOf(clean))
    }
}

internal fun sanitizeAssetDecimal(raw: String): String {
    val sb = StringBuilder()
    var seenDot = false
    for (c in raw) {
        when {
            c.isDigit() -> sb.append(c)
            c == '.' && !seenDot -> {
                if (sb.isEmpty()) sb.append('0')
                sb.append('.')
                seenDot = true
            }
        }
    }
    return sb.toString().take(14)
}
