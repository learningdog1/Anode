@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.fleet

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private val ROUTE_TAG_PRESETS = listOf("CN2 GIA", "9929", "CMIN2", "BGP", "优化直连", "落地")
private val REMIND_DAY_PRESETS = listOf(0 to "不提醒", 3 to "提前 3 天", 7 to "提前 7 天", 15 to "提前 15 天", 30 to "提前 30 天")

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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    val ext = NodePulseThemeTokens.extendedColors

    var nickname by remember(draft) { mutableStateOf(draft.nickname) }
    var providerName by remember(draft) { mutableStateOf(draft.providerName) }
    var region by remember(draft) { mutableStateOf(draft.region) }
    var routeTag by remember(draft) { mutableStateOf(draft.routeTag) }
    var specsSummary by remember(draft) { mutableStateOf(draft.specsSummary) }
    var renewalAmount by remember(draft) { mutableStateOf(draft.renewalAmount) }
    var currency by remember(draft) { mutableStateOf(draft.currency) }
    var cyclePreset by remember(draft) { mutableStateOf(draft.cyclePreset) }
    var customCycleDays by remember(draft) { mutableStateOf(draft.customCycleDays) }
    var expiryDateIso by remember(draft) { mutableStateOf(draft.expiryDateIso) }
    var remindDays by remember(draft) { mutableIntStateOf(draft.remindDaysBeforeExpiry) }
    var monthlyTrafficQuotaGb by remember(draft) { mutableStateOf(draft.monthlyTrafficQuotaGb) }
    var linkedProbeKey by remember(draft) { mutableStateOf(draft.linkedProbeKey) }
    var purchaseUrl by remember(draft) { mutableStateOf(draft.purchaseUrl) }
    var panelManageUrl by remember(draft) { mutableStateOf(draft.panelManageUrl) }
    var autoRenewNote by remember(draft) { mutableStateOf(draft.autoRenewNote) }
    var notes by remember(draft) { mutableStateOf(draft.notes) }

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
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("资产备注名称 *") },
                    placeholder = { Text("例如：瓦工 HK85 主力鸡") },
                    singleLine = true,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = providerName,
                        onValueChange = { providerName = it },
                        label = { Text("商家名称") },
                        placeholder = { Text("Bandwagon / DMIT") },
                        singleLine = true,
                        shape = PulseArchitecturalShapes.MediumBadge,
                        colors = fleetTextFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = region,
                        onValueChange = { region = it },
                        label = { Text("机房 / 地区") },
                        placeholder = { Text("洛杉矶 / 东京 / 香港") },
                        singleLine = true,
                        shape = PulseArchitecturalShapes.MediumBadge,
                        colors = fleetTextFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = specsSummary,
                    onValueChange = { specsSummary = it },
                    label = { Text("硬件配置摘要") },
                    placeholder = { Text("1C / 1G / 20G NVMe / 1TB") },
                    singleLine = true,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = routeTag,
                    onValueChange = { routeTag = it },
                    label = { Text("线路标签（选填）") },
                    placeholder = { Text("CN2 GIA / 9929 / CMIN2") },
                    singleLine = true,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ROUTE_TAG_PRESETS.forEach { preset ->
                        FleetChoiceChip(
                            label = preset,
                            selected = routeTag.equals(preset, ignoreCase = true),
                            onClick = { routeTag = if (routeTag == preset) "" else preset },
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
                    OutlinedTextField(
                        value = renewalAmount,
                        onValueChange = { renewalAmount = sanitizeAssetDecimal(it) },
                        label = { Text("周期续费金额 *") },
                        placeholder = { Text("10.98") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = PulseArchitecturalShapes.MediumBadge,
                        colors = fleetTextFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    BillingCyclePreset.entries.forEach { preset ->
                        FleetChoiceChip(
                            label = preset.shortLabel,
                            selected = cyclePreset == preset,
                            onClick = { cyclePreset = preset },
                        )
                    }
                }

                AnimatedVisibility(visible = cyclePreset == BillingCyclePreset.CUSTOM) {
                    OutlinedTextField(
                        value = customCycleDays,
                        onValueChange = { customCycleDays = it.filter(Char::isDigit).take(4) },
                        label = { Text("自定义周期天数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = PulseArchitecturalShapes.MediumBadge,
                        colors = fleetTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Expiry Date + Quick Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = expiryDateIso,
                        onValueChange = { expiryDateIso = it },
                        label = { Text("到期日期 (yyyy-MM-dd) *") },
                        placeholder = { Text("2027-11-19") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Rounded.CalendarMonth, contentDescription = "选择日期")
                            }
                        },
                        shape = PulseArchitecturalShapes.MediumBadge,
                        colors = fleetTextFieldColors(),
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
                PulseSectionHeader(title = "配额、链接与备注", icon = Icons.Rounded.Link)
                OutlinedTextField(
                    value = monthlyTrafficQuotaGb,
                    onValueChange = { monthlyTrafficQuotaGb = sanitizeAssetDecimal(it) },
                    label = { Text("月流量配额 (GB，选填)") },
                    placeholder = { Text("例如：1000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = purchaseUrl,
                    onValueChange = { purchaseUrl = it },
                    label = { Text("购买页 / 订单链接（选填）") },
                    singleLine = true,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = panelManageUrl,
                    onValueChange = { panelManageUrl = it },
                    label = { Text("控制面板链接（选填）") },
                    singleLine = true,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = autoRenewNote,
                    onValueChange = { autoRenewNote = it },
                    label = { Text("续费备注 / 优惠码说明（选填）") },
                    placeholder = { Text("例如：终身循环 7 折 / PayPal 自动扣费") },
                    singleLine = true,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("备注（选填）") },
                    minLines = 2,
                    maxLines = 4,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                validationError?.let { err ->
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
                    modifier = Modifier.weight(0.4f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val parsedAmount = renewalAmount.trim().toBigDecimalOrNull()
                        val parsedDate = FleetPortfolioCalculator.parseExpiryDate(expiryDateIso)
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
                            else -> {
                                validationError = null
                                onSave(
                                    VpsAssetRecord(
                                        id = draft.editingId ?: UUID.randomUUID().toString(),
                                        nickname = nickname.trim().ifBlank { providerName.trim() },
                                        providerName = providerName.trim(),
                                        specsSummary = specsSummary.trim(),
                                        region = region.trim(),
                                        routeTag = routeTag.trim(),
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
                                        remindDaysBeforeExpiry = remindDays,
                                        autoRenewNote = autoRenewNote.trim(),
                                        notes = notes.trim(),
                                    ),
                                )
                            }
                        }
                    },
                    shape = CircleShape,
                    modifier = Modifier.weight(0.6f),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (draft.editingId == null) "加入资产账本" else "保存修改")
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
