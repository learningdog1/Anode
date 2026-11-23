@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.netbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.ssh.SshServerGroupOption
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultCatalogFilter
import com.nodeseek.app.ui.components.PulseSearchField
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.util.Locale

/**
 * Resolved host/IP and port extracted from an [SshServerProfile] for Mobile Network Tester prefill.
 */
internal data class VaultHostSelection(
    val host: String,
    val port: Int,
    val displayName: String,
)

/**
 * Returns the best available host/IP for benchmarking from a vaulted [SshServerProfile].
 * Prefers the user-configured [SshServerProfile.host] and falls back to [SshServerProfile.probeReportedHost].
 */
internal fun SshServerProfile.resolvableNetBenchHost(): String =
    host.trim().ifBlank { probeReportedHost.trim() }

/**
 * Extracts normalized target host and TCP port from a vaulted [SshServerProfile], returning `null`
 * if the profile has no host or IP address (e.g., probe-terminal-only node without exposed IP).
 */
internal fun SshServerProfile.toVaultHostSelection(): VaultHostSelection? {
    val rawHost = resolvableNetBenchHost()
    if (rawHost.isEmpty()) return null
    val parsed = NetBenchTargetParser.parse(rawHost)
    val cleanHost = parsed?.host ?: rawHost
    val effectivePort = parsed?.explicitPort
        ?: port.takeIf { it in 1..65535 }
        ?: SshServerProfile.DEFAULT_PORT
    return VaultHostSelection(
        host = cleanHost,
        port = effectivePort,
        displayName = displayName,
    )
}

internal fun normalizeHostForVaultMatch(rawInput: String): String {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return ""
    val extracted = NetBenchTargetParser.parse(trimmed)?.host ?: trimmed
    return extracted
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .lowercase(Locale.ROOT)
}

internal fun formatVaultEndpointForFeedback(host: String, port: Int): String =
    if (NetBenchTargetParser.detectIpFamily(host) == IpFamily.IPV6) {
        "[$host]:$port"
    } else {
        "$host:$port"
    }

internal fun filterVaultServersForNetBench(
    servers: List<SshServerProfile>,
    currentTargetInput: String,
    searchQuery: String,
    selectedGroupKey: String,
): List<SshServerProfile> {
    if (servers.isEmpty()) return emptyList()
    val currentHostNorm = normalizeHostForVaultMatch(currentTargetInput)
    val matchedServerId = if (currentHostNorm.isNotEmpty()) {
        servers.firstOrNull { server ->
            val candidate = server.resolvableNetBenchHost()
            candidate.isNotEmpty() && normalizeHostForVaultMatch(candidate) == currentHostNorm
        }?.id
    } else {
        null
    }
    val filtered = SshVaultCatalogFilter.filterAndSortServers(
        servers = servers,
        selectedServerId = matchedServerId,
        searchQuery = searchQuery,
        selectedGroupKey = selectedGroupKey,
        pinSelectedFirst = true,
    )
    // Keep selectable hosts (with IP/hostname) ahead of probe-only entries missing an address
    val (selectable, missingHost) = filtered.partition { it.resolvableNetBenchHost().isNotEmpty() }
    return if (missingHost.isEmpty() || selectable.isEmpty()) {
        filtered
    } else {
        selectable + missingHost
    }
}

/**
 * Compact trailing actions for target IP/host text fields:
 * optional clear button + primary-tinted "pick from Host Vault" icon button.
 */
@Composable
internal fun NetBenchTargetTrailingActions(
    hasText: Boolean,
    enabled: Boolean,
    onClear: () -> Unit,
    onOpenVaultPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (hasText && enabled) {
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Clear,
                    contentDescription = "清空目标",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(
            onClick = onOpenVaultPicker,
            enabled = enabled,
            modifier = Modifier.size(36.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(
                        if (enabled) {
                            MaterialTheme.colorScheme.primaryContainer.copy(
                                alpha = if (NodePulseThemeTokens.isDark) 0.72f else 0.85f,
                            )
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Dns,
                    contentDescription = "从主机保险库选择",
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    },
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/**
 * Theme-aligned bottom sheet allowing users to search, group-filter, and pick a saved VPS host
 * from the read-only SSH Host Vault (`主机保险库`) to prefill Mobile Network Tester target fields.
 */
@Composable
internal fun VaultHostPickerSheet(
    servers: List<SshServerProfile>,
    currentTargetInput: String,
    onSelectServer: (SshServerProfile) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToVault: (() -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val keyboardController = LocalSoftwareKeyboardController.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroupKey by rememberSaveable { mutableStateOf(SshServerGroupOption.KEY_ALL) }

    val groupOptions = remember(servers) {
        SshVaultCatalogFilter.buildServerGroupOptions(servers)
    }
    val effectiveGroupKey = remember(selectedGroupKey, groupOptions) {
        SshVaultCatalogFilter.resolveEffectiveServerGroupKey(selectedGroupKey, groupOptions)
    }
    val currentHostNorm = remember(currentTargetInput) {
        normalizeHostForVaultMatch(currentTargetInput)
    }
    val filteredServers = remember(servers, currentTargetInput, searchQuery, effectiveGroupKey) {
        filterVaultServersForNetBench(
            servers = servers,
            currentTargetInput = currentTargetInput,
            searchQuery = searchQuery,
            selectedGroupKey = effectiveGroupKey,
        )
    }
    val selectableTotalCount = remember(servers) {
        servers.count { it.resolvableNetBenchHost().isNotEmpty() }
    }
    val isFiltering = searchQuery.isNotBlank() || effectiveGroupKey != SshServerGroupOption.KEY_ALL

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = ext.cardSurfaceElevated,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Sheet Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(PulseArchitecturalShapes.SmallChip)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = if (isDark) 0.18f else 0.12f,
                            ),
                        )
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.primary.copy(
                                alpha = if (isDark) 0.34f else 0.22f,
                            ),
                            shape = PulseArchitecturalShapes.SmallChip,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "从主机保险库选择",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (servers.isNotEmpty()) {
                            PulseAccentPill(
                                text = "${servers.size} 台",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Text(
                        text = when {
                            servers.isEmpty() -> "读取「轻量 SSH」主机保险库中的已保存 VPS"
                            isFiltering -> "命中 ${filteredServers.size} / ${servers.size} 台 · 点击填入 IP/域名与端口"
                            else -> "选择已保存主机，自动填入目标地址与端口"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PulseGradientDivider(modifier = Modifier.padding(top = 6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (servers.isEmpty()) {
                    VaultHostPickerEmptyState(
                        onNavigateToVault = onNavigateToVault?.let { nav ->
                            {
                                onDismiss()
                                nav()
                            }
                        },
                        onDismiss = onDismiss,
                    )
                } else {
                    // Search bar
                    PulseSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "搜索主机名称 / IP / 域名 / 分组 / 标签…",
                        elevated = false,
                        onClear = { searchQuery = "" },
                        onSearch = { keyboardController?.hide() },
                        trailingContent = if (isFiltering) {
                            {
                                PulseAccentPill(
                                    text = "${filteredServers.size}/${servers.size}",
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            null
                        },
                    )

                    // Group filter chips
                    if (groupOptions.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(vertical = 2.dp),
                        ) {
                            items(groupOptions, key = { "vault_grp_${it.key}" }) { option ->
                                val active = option.key.equals(effectiveGroupKey, ignoreCase = true)
                                NetBenchSelectableChip(
                                    label = "${option.label} (${option.count})",
                                    selected = active,
                                    onClick = {
                                        selectedGroupKey = if (active && option.key != SshServerGroupOption.KEY_ALL) {
                                            SshServerGroupOption.KEY_ALL
                                        } else {
                                            option.key
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // Helpful banner if all vaulted entries lack an IP/hostname
                    if (selectableTotalCount == 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(PulseArchitecturalShapes.MediumBadge)
                                .background(
                                    ext.drumstickContainer.copy(alpha = if (isDark) 0.25f else 0.5f),
                                )
                                .border(
                                    width = 0.8.dp,
                                    color = ext.onDrumstickContainer.copy(alpha = 0.3f),
                                    shape = PulseArchitecturalShapes.MediumBadge,
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = ext.onDrumstickContainer,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "当前保险库主机均未记录 IP/域名，请先在「轻量 SSH · 主机保险库」中补充地址或配置探针 Token 后重新同步。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    if (filteredServers.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
                                .padding(horizontal = 16.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "未找到匹配的保险库主机",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "请尝试缩短关键词或重置分组筛选",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = {
                                    searchQuery = ""
                                    selectedGroupKey = SshServerGroupOption.KEY_ALL
                                },
                            ) {
                                Text("重置搜索与分组")
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = 440.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 2.dp, bottom = 10.dp),
                        ) {
                            items(filteredServers, key = { "netbench_vault_${it.id}" }) { server ->
                                VaultHostPickerRow(
                                    server = server,
                                    currentHostNorm = currentHostNorm,
                                    onSelect = {
                                        onSelectServer(server)
                                        onDismiss()
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
private fun VaultHostPickerEmptyState(
    onNavigateToVault: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }

        Text(
            text = "主机保险库暂无已保存主机",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = "添加 VPS 主机或同步哪吒 / Komari 探针后，即可在此一键选取目标 IP 与端口进行测机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.MediumBadge)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    shape = PulseArchitecturalShapes.MediumBadge,
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "添加路径：功能中心 → 轻量 SSH → 主机保险库",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        if (onNavigateToVault != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = onNavigateToVault,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("前往保险库")
                }
            }
        } else {
            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("知道了")
            }
        }
    }
}

@Composable
private fun VaultHostPickerRow(
    server: SshServerProfile,
    currentHostNorm: String,
    onSelect: () -> Unit,
) {
    val selection = remember(server) { server.toVaultHostSelection() }
    val canSelect = selection != null
    val isCurrentTarget = remember(selection, currentHostNorm) {
        selection != null &&
            currentHostNorm.isNotEmpty() &&
            normalizeHostForVaultMatch(selection.host) == currentHostNorm
    }
    val extraTags = remember(server.probeTags, server.resolvedGroup) {
        val groupNorm = server.resolvedGroup?.trim()?.lowercase(Locale.ROOT)
        server.probeTags
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) != groupNorm }
            .take(3)
            .toList()
    }

    val cardModifier = Modifier
        .fillMaxWidth()
        .pulseCard(
            shape = PulseArchitecturalShapes.EditorialCard,
            borderColor = if (isCurrentTarget) MaterialTheme.colorScheme.primary else null,
            borderWidth = if (isCurrentTarget) 1.3.dp else 0.9.dp,
        )
        .then(
            if (canSelect) {
                Modifier.pulseBouncyClickable(onClick = onSelect)
            } else {
                Modifier.alpha(0.62f)
            },
        )
        .padding(horizontal = 12.dp, vertical = 10.dp)

    Row(
        modifier = cardModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (isCurrentTarget) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isCurrentTarget) Icons.Rounded.Check else Icons.Rounded.Dns,
                contentDescription = null,
                tint = if (isCurrentTarget) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = server.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isCurrentTarget) {
                    PulseAccentPill(text = "当前目标")
                }
                server.resolvedGroup?.let { grp ->
                    PulseAccentPill(
                        text = grp,
                        icon = Icons.Rounded.LocalOffer,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Text(
                text = selection?.host ?: "未配置 IP / 域名（仅探针终端）",
                style = if (canSelect) {
                    NodePulseThemeTokens.extraTypography.codeLabel.withTabularNumbers()
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (selection != null) {
                    PulseAccentPill(
                        text = "端口 ${selection.port}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (server.probeRegion.isNotBlank()) {
                    PulseAccentPill(
                        text = server.probeRegion,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (server.isProbeSynced) {
                    PulseAccentPill(
                        text = server.probePanelShortLabel ?: "探针",
                        icon = Icons.Rounded.CloudSync,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                extraTags.forEach { tag ->
                    PulseAccentPill(
                        text = tag,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (canSelect) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "选择此主机",
                tint = if (isCurrentTarget) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
                modifier = Modifier.size(18.dp),
            )
        } else {
            PulseAccentPill(
                text = "无地址",
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
