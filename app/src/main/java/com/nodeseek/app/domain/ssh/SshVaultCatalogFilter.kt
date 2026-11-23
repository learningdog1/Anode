package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

/**
 * Group filter option derived from [SshServerProfile] entries in the SSH vault.
 */
@Immutable
data class SshServerGroupOption(
    val key: String,
    val label: String,
    val count: Int,
) {
    companion object {
        const val KEY_ALL: String = ""
        const val KEY_UNGROUPED: String = "__UNGROUPED__"
    }
}

/**
 * Category / source filter tabs for built-in and user-defined SSH benchmark scripts.
 */
enum class SshPresetFilter(val label: String) {
    ALL("全部"),
    CUSTOM("自定义脚本"),
    BENCHMARK("综合测评"),
    NETWORK("网络 / 回程"),
    UNLOCK("流媒体 / IP 解锁"),
    DIAGNOSTIC("系统 / 硬件"),
}

/**
 * Filter tab option paired with its live preset count.
 */
@Immutable
data class SshPresetFilterOption(
    val filter: SshPresetFilter,
    val count: Int,
) {
    val label: String
        get() = filter.label
}

/**
 * Collapsible section bucket for displaying grouped VPS servers inside the SSH Host Vault.
 */
@Immutable
data class SshVaultServerGroup(
    val key: String,
    val label: String,
    val servers: List<SshServerProfile>,
    val isProbeSourceGroup: Boolean = false,
)

/**
 * Parsed group tag and clean server remark extracted from an [SshServerProfile].
 */
@Immutable
data class ParsedSshServerGroup(
    val group: String?,
    val cleanAlias: String,
)

/**
 * Pure domain search, category/group filtering, selected-item pinning, and collapsed slicing
 * helpers for high-capacity SSH Server Vaults (50+ VPS) and large custom script catalogs.
 */
object SshVaultCatalogFilter {
    const val DEFAULT_COLLAPSED_SERVER_COUNT: Int = 4
    const val DEFAULT_COLLAPSED_PRESET_COUNT: Int = 4

    val SUGGESTED_SERVER_GROUPS: List<String> = listOf(
        "香港",
        "日本",
        "新加坡",
        "美西",
        "欧洲",
        "主力",
    )

    private val BRACKET_GROUP_REGEX = Regex("""^(?:\[([^\[\]]{1,24})]|\u3010([^\u3010\u3011]{1,24})\u3011)\s*(.*)$""")
    private val WHITESPACE_SPLIT_REGEX = Regex("""\s+""")
    private val COMPACT_STRIP_REGEX = Regex("""[\s\-_./|·:()\[\]\u3010\u3011]+""")

    /**
     * Extracts an optional group tag and clean display alias from [alias] and [explicitGroupTag].
     *
     * Supports both explicit [explicitGroupTag] values and bracket-prefixed aliases such as
     * `"[香港] ClawCloud 优化"` or `"【美西】瓦工 DC6"`, allowing group tags to round-trip cleanly
     * through `lite_ssh_vault.json` without changing the encrypted vault file schema.
     */
    fun parseAliasGroup(
        alias: String,
        explicitGroupTag: String = "",
    ): ParsedSshServerGroup {
        val trimmedAlias = alias.trim()
        val explicit = explicitGroupTag.trim()
        val match = BRACKET_GROUP_REGEX.matchEntire(trimmedAlias)

        if (match != null) {
            val bracketGroup = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
            val remainder = match.groupValues[3].trim()
            val resolvedGroup = explicit.ifEmpty { bracketGroup }.takeIf { it.isNotEmpty() }
            val cleanAlias = remainder.ifEmpty { trimmedAlias }
            return ParsedSshServerGroup(
                group = resolvedGroup,
                cleanAlias = cleanAlias,
            )
        }

        return ParsedSshServerGroup(
            group = explicit.takeIf { it.isNotEmpty() },
            cleanAlias = trimmedAlias,
        )
    }

    /**
     * Encodes [groupTag] and [alias] into a persisted alias string (`"[group] alias"`).
     */
    fun encodeAliasWithGroup(
        alias: String,
        groupTag: String,
        fallbackHost: String = "",
    ): String {
        val cleanGroup = groupTag.trim()
            .replace("[", "")
            .replace("]", "")
            .replace("【", "")
            .replace("】", "")
            .trim()
        val trimmedAlias = alias.trim()
        val bracketMatch = BRACKET_GROUP_REGEX.matchEntire(trimmedAlias)
        val strippedAlias = if (bracketMatch != null) {
            bracketMatch.groupValues[3].trim()
        } else {
            trimmedAlias
        }

        val baseName = strippedAlias.ifBlank { fallbackHost.trim() }
        return if (cleanGroup.isEmpty()) {
            baseName
        } else if (baseName.isEmpty()) {
            "[$cleanGroup]"
        } else {
            "[$cleanGroup] $baseName"
        }
    }

    /**
     * Normalizes an [SshServerDraft] before persisting to [SshVaultRepository]:
     * - If `draft.groupEdited` is true (submitted from the server editor UI), encodes or clears
     *   the `[group]` prefix according to `draft.groupTag`.
     * - If `draft.groupTag` is non-blank even when `groupEdited` is false (e.g. programmatic/test
     *   drafts), encodes `[groupTag] alias`.
     * - Otherwise preserves `draft.alias` verbatim for backwards compatibility.
     */
    fun normalizeDraftForPersistence(draft: SshServerDraft): SshServerDraft {
        if (!draft.groupEdited && draft.groupTag.isBlank()) {
            return draft
        }
        val encodedAlias = encodeAliasWithGroup(
            alias = draft.alias,
            groupTag = draft.groupTag,
            fallbackHost = draft.host,
        )
        return draft.copy(alias = encodedAlias)
    }

    /**
     * Builds group filter chips (`全部 + 各分组 + 可选未分组`) with per-group counts.
     * Returns [emptyList] when none of [servers] carries a group tag.
     */
    fun buildServerGroupOptions(servers: List<SshServerProfile>): List<SshServerGroupOption> {
        if (servers.isEmpty()) return emptyList()

        val countsByNormalizedKey = LinkedHashMap<String, Int>()
        val labelByNormalizedKey = LinkedHashMap<String, String>()
        var ungroupedCount = 0

        for (server in servers) {
            val group = server.resolvedGroup
            if (group.isNullOrBlank()) {
                ungroupedCount++
            } else {
                val normKey = group.lowercase()
                labelByNormalizedKey.putIfAbsent(normKey, group)
                countsByNormalizedKey[normKey] = (countsByNormalizedKey[normKey] ?: 0) + 1
            }
        }

        if (countsByNormalizedKey.isEmpty()) {
            return emptyList()
        }

        val namedOptions = countsByNormalizedKey.entries
            .sortedByDescending { it.value }
            .map { (normKey, count) ->
                val displayLabel = labelByNormalizedKey[normKey] ?: normKey
                SshServerGroupOption(
                    key = normKey,
                    label = displayLabel,
                    count = count,
                )
            }

        return buildList(namedOptions.size + 2) {
            add(
                SshServerGroupOption(
                    key = SshServerGroupOption.KEY_ALL,
                    label = "全部",
                    count = servers.size,
                ),
            )
            addAll(namedOptions)
            if (ungroupedCount > 0) {
                add(
                    SshServerGroupOption(
                        key = SshServerGroupOption.KEY_UNGROUPED,
                        label = "未分组",
                        count = ungroupedCount,
                    ),
                )
            }
        }
    }

    /**
     * Returns [selectedGroupKey] if still valid in [options], or [SshServerGroupOption.KEY_ALL]
     * when the selected group no longer exists.
     */
    fun resolveEffectiveServerGroupKey(
        selectedGroupKey: String,
        options: List<SshServerGroupOption>,
    ): String {
        if (selectedGroupKey == SshServerGroupOption.KEY_ALL || options.isEmpty()) {
            return SshServerGroupOption.KEY_ALL
        }
        val matched = options.firstOrNull { it.key.equals(selectedGroupKey, ignoreCase = true) }
        return matched?.key ?: SshServerGroupOption.KEY_ALL
    }

    /**
     * Filters [servers] by [selectedGroupKey] and multi-keyword fuzzy [searchQuery] (matching
     * alias, host IP/domain, port, username, group tag, and jump-host name), and pins the
     * currently selected server ([selectedServerId]) to the top when [pinSelectedFirst] is true.
     */
    fun filterAndSortServers(
        servers: List<SshServerProfile>,
        selectedServerId: String?,
        searchQuery: String = "",
        selectedGroupKey: String = SshServerGroupOption.KEY_ALL,
        pinSelectedFirst: Boolean = true,
    ): List<SshServerProfile> {
        if (servers.isEmpty()) return emptyList()

        val serversById = servers.associateBy { it.id }
        val normalizedGroupKey = selectedGroupKey.trim()
        val queryTokens = tokenizeQuery(searchQuery)

        val matched = servers.filter { server ->
            val groupMatches = when (normalizedGroupKey) {
                SshServerGroupOption.KEY_ALL -> true
                SshServerGroupOption.KEY_UNGROUPED -> server.resolvedGroup.isNullOrBlank()
                else -> server.resolvedGroup?.equals(normalizedGroupKey, ignoreCase = true) == true
            }
            if (!groupMatches) return@filter false
            if (queryTokens.isEmpty()) return@filter true

            val jumpName = server.jumpHostId?.let { serversById[it]?.displayName }.orEmpty()
            matchesServerQueryTokens(
                server = server,
                jumpHostDisplayName = jumpName,
                tokens = queryTokens,
            )
        }

        if (!pinSelectedFirst || selectedServerId.isNullOrBlank() || matched.size <= 1) {
            return matched
        }

        val selectedIndex = matched.indexOfFirst { it.id == selectedServerId }
        if (selectedIndex <= 0) return matched

        return buildList(matched.size) {
            add(matched[selectedIndex])
            for (i in matched.indices) {
                if (i != selectedIndex) add(matched[i])
            }
        }
    }

    /**
     * Groups [filteredServers] into collapsible sections for the SSH Host Vault UI.
     *
     * - When any server carries a group tag, partitions by group (keeping the selected server's
     *   group first) and places ungrouped entries under `"未分组"`.
     * - When no server carries a group tag but both probe-synced and manual entries exist,
     *   partitions into `"探针同步"` and `"手动添加"` sections.
     * - Otherwise returns a single collapsible `"全部主机"` section.
     */
    fun groupServersForCollapsibleVault(
        filteredServers: List<SshServerProfile>,
        selectedServerId: String? = null,
    ): List<SshVaultServerGroup> {
        if (filteredServers.isEmpty()) return emptyList()

        val hasAnyNamedGroup = filteredServers.any { !it.resolvedGroup.isNullOrBlank() }
        if (hasAnyNamedGroup) {
            val grouped = LinkedHashMap<String, MutableList<SshServerProfile>>()
            val labels = LinkedHashMap<String, String>()
            val ungrouped = ArrayList<SshServerProfile>()

            for (server in filteredServers) {
                val grp = server.resolvedGroup?.trim()?.takeIf { it.isNotEmpty() }
                if (grp == null) {
                    ungrouped += server
                } else {
                    val normKey = grp.lowercase()
                    labels.putIfAbsent(normKey, grp)
                    grouped.getOrPut(normKey) { ArrayList() }.add(server)
                }
            }

            val sections = buildList {
                grouped.forEach { (key, list) ->
                    add(
                        SshVaultServerGroup(
                            key = key,
                            label = labels[key] ?: key,
                            servers = list,
                            isProbeSourceGroup = list.all { it.isProbeSynced },
                        ),
                    )
                }
                if (ungrouped.isNotEmpty()) {
                    add(
                        SshVaultServerGroup(
                            key = SshServerGroupOption.KEY_UNGROUPED.lowercase(),
                            label = "未分组",
                            servers = ungrouped,
                            isProbeSourceGroup = ungrouped.all { it.isProbeSynced },
                        ),
                    )
                }
            }
            return pinSelectedGroupFirst(sections, selectedServerId)
        }

        val probeSynced = filteredServers.filter { it.isProbeSynced }
        val manual = filteredServers.filterNot { it.isProbeSynced }
        if (probeSynced.isNotEmpty() && manual.isNotEmpty()) {
            val sections = listOf(
                SshVaultServerGroup(
                    key = "__source_probe__",
                    label = "探针同步主机",
                    servers = probeSynced,
                    isProbeSourceGroup = true,
                ),
                SshVaultServerGroup(
                    key = "__source_manual__",
                    label = "手动添加主机",
                    servers = manual,
                    isProbeSourceGroup = false,
                ),
            )
            return pinSelectedGroupFirst(sections, selectedServerId)
        }

        return listOf(
            SshVaultServerGroup(
                key = "__all_servers__",
                label = if (probeSynced.isNotEmpty()) "探针同步主机" else "全部主机",
                servers = filteredServers,
                isProbeSourceGroup = probeSynced.isNotEmpty(),
            ),
        )
    }

    private fun pinSelectedGroupFirst(
        sections: List<SshVaultServerGroup>,
        selectedServerId: String?,
    ): List<SshVaultServerGroup> {
        if (selectedServerId.isNullOrBlank() || sections.size <= 1) return sections
        val idx = sections.indexOfFirst { section -> section.servers.any { it.id == selectedServerId } }
        if (idx <= 0) return sections
        return buildList(sections.size) {
            add(sections[idx])
            for (i in sections.indices) {
                if (i != idx) add(sections[i])
            }
        }
    }

    /**
     * Computes the visible slice of [filteredServers] in inline collapsed vs expanded state.
     */
    fun visibleServersForInlineSection(
        filteredServers: List<SshServerProfile>,
        expanded: Boolean,
        searchQuery: String = "",
        collapsedLimit: Int = DEFAULT_COLLAPSED_SERVER_COUNT,
    ): List<SshServerProfile> {
        val safeLimit = collapsedLimit.coerceAtLeast(1)
        return if (expanded || searchQuery.isNotBlank() || filteredServers.size <= safeLimit) {
            filteredServers
        } else {
            filteredServers.take(safeLimit)
        }
    }

    /**
     * Computes category / source filter options with live counts across built-in and custom presets.
     */
    fun buildPresetFilterOptions(presets: List<SshScriptPreset>): List<SshPresetFilterOption> {
        var customCount = 0
        var benchmarkCount = 0
        var networkCount = 0
        var unlockCount = 0
        var diagnosticCount = 0

        for (preset in presets) {
            if (preset.isCustom || preset.category == SshPresetCategory.CUSTOM) {
                customCount++
            }
            when (preset.category) {
                SshPresetCategory.BENCHMARK -> benchmarkCount++
                SshPresetCategory.NETWORK -> networkCount++
                SshPresetCategory.UNLOCK -> unlockCount++
                SshPresetCategory.DIAGNOSTIC -> diagnosticCount++
                SshPresetCategory.CUSTOM -> Unit
            }
        }

        return listOf(
            SshPresetFilterOption(SshPresetFilter.ALL, presets.size),
            SshPresetFilterOption(SshPresetFilter.CUSTOM, customCount),
            SshPresetFilterOption(SshPresetFilter.BENCHMARK, benchmarkCount),
            SshPresetFilterOption(SshPresetFilter.NETWORK, networkCount),
            SshPresetFilterOption(SshPresetFilter.UNLOCK, unlockCount),
            SshPresetFilterOption(SshPresetFilter.DIAGNOSTIC, diagnosticCount),
        )
    }

    /**
     * Filters [presets] by [filter] category/source and instant [searchQuery] (matching script
     * name, tab title, description, shell command keywords, category label, and CLI flags),
     * keeping [selectedPresetId] pinned first when [pinSelectedFirst] is true.
     */
    fun filterAndSortPresets(
        presets: List<SshScriptPreset>,
        selectedPresetId: String?,
        searchQuery: String = "",
        filter: SshPresetFilter = SshPresetFilter.ALL,
        pinSelectedFirst: Boolean = true,
    ): List<SshScriptPreset> {
        if (presets.isEmpty()) return emptyList()

        val queryTokens = tokenizeQuery(searchQuery)
        val matched = presets.filter { preset ->
            val categoryMatches = when (filter) {
                SshPresetFilter.ALL -> true
                SshPresetFilter.CUSTOM -> preset.isCustom || preset.category == SshPresetCategory.CUSTOM
                SshPresetFilter.BENCHMARK -> preset.category == SshPresetCategory.BENCHMARK
                SshPresetFilter.NETWORK -> preset.category == SshPresetCategory.NETWORK
                SshPresetFilter.UNLOCK -> preset.category == SshPresetCategory.UNLOCK
                SshPresetFilter.DIAGNOSTIC -> preset.category == SshPresetCategory.DIAGNOSTIC
            }
            if (!categoryMatches) return@filter false
            if (queryTokens.isEmpty()) return@filter true

            matchesPresetQueryTokens(preset = preset, tokens = queryTokens)
        }

        if (!pinSelectedFirst || selectedPresetId.isNullOrBlank() || matched.size <= 1) {
            return matched
        }

        val selectedIndex = matched.indexOfFirst { it.id == selectedPresetId }
        if (selectedIndex <= 0) return matched

        return buildList(matched.size) {
            add(matched[selectedIndex])
            for (i in matched.indices) {
                if (i != selectedIndex) add(matched[i])
            }
        }
    }

    /**
     * Computes the visible slice of [filteredPresets] in inline collapsed vs expanded state.
     */
    fun visiblePresetsForInlineSection(
        filteredPresets: List<SshScriptPreset>,
        expanded: Boolean,
        searchQuery: String = "",
        collapsedLimit: Int = DEFAULT_COLLAPSED_PRESET_COUNT,
    ): List<SshScriptPreset> {
        val safeLimit = collapsedLimit.coerceAtLeast(1)
        return if (expanded || searchQuery.isNotBlank() || filteredPresets.size <= safeLimit) {
            filteredPresets
        } else {
            filteredPresets.take(safeLimit)
        }
    }

    private fun tokenizeQuery(rawQuery: String): List<String> {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed
            .lowercase()
            .split(WHITESPACE_SPLIT_REGEX)
            .filter { it.isNotEmpty() }
    }

    private fun matchesServerQueryTokens(
        server: SshServerProfile,
        jumpHostDisplayName: String,
        tokens: List<String>,
    ): Boolean {
        val displayNameLower = server.displayName.lowercase()
        val rawAliasLower = server.alias.lowercase()
        val hostLower = server.host.lowercase()
        val portStr = server.port.toString()
        val userLower = server.username.lowercase()
        val addressLower = server.displayAddress.lowercase()
        val groupLower = server.resolvedGroup?.lowercase().orEmpty()
        val jumpLower = jumpHostDisplayName.lowercase()
        val probePanelLower = server.probePanelName?.lowercase().orEmpty()
        val probeTypeLower = server.probePanelShortLabel?.lowercase().orEmpty()
        val probeTypeRawLower = server.probePanelType?.lowercase().orEmpty()
        val probeRegionLower = server.probeRegion.lowercase()
        val probeServerIdLower = server.probeServerId?.lowercase().orEmpty()
        val probeTagsLower = if (server.probeTags.isEmpty()) {
            ""
        } else {
            server.probeTags.joinToString(" ").lowercase()
        }
        val probeSourceMarker = if (server.isProbeSynced) "探针 probe synced" else "手动 manual"

        val compactIdentity = COMPACT_STRIP_REGEX.replace(
            "$displayNameLower$groupLower$hostLower$userLower$probePanelLower$probeRegionLower$probeTagsLower",
            "",
        )

        return tokens.all { token ->
            displayNameLower.contains(token) ||
                rawAliasLower.contains(token) ||
                hostLower.contains(token) ||
                portStr.contains(token) ||
                userLower.contains(token) ||
                addressLower.contains(token) ||
                groupLower.contains(token) ||
                jumpLower.contains(token) ||
                probePanelLower.contains(token) ||
                probeTypeLower.contains(token) ||
                probeTypeRawLower.contains(token) ||
                probeRegionLower.contains(token) ||
                probeServerIdLower.contains(token) ||
                probeTagsLower.contains(token) ||
                probeSourceMarker.contains(token) ||
                (token.length >= 2 && compactIdentity.contains(COMPACT_STRIP_REGEX.replace(token, "")))
        }
    }

    private fun matchesPresetQueryTokens(
        preset: SshScriptPreset,
        tokens: List<String>,
    ): Boolean {
        val nameLower = preset.name.lowercase()
        val tabLower = preset.tabTitle.lowercase()
        val descLower = preset.description.lowercase()
        val cmdLower = preset.commandTemplate.lowercase()
        val categoryLower = preset.category.label.lowercase()
        val sourceLabel = if (preset.isCustom) "自定义 custom" else "内置 builtin"
        val flagsText = if (preset.flags.isEmpty()) {
            ""
        } else {
            preset.flags.joinToString(" ") { "${it.label} ${it.flag} ${it.description}" }.lowercase()
        }
        val compactName = COMPACT_STRIP_REGEX.replace("$nameLower$tabLower$categoryLower", "")

        return tokens.all { token ->
            nameLower.contains(token) ||
                tabLower.contains(token) ||
                descLower.contains(token) ||
                cmdLower.contains(token) ||
                categoryLower.contains(token) ||
                sourceLabel.contains(token) ||
                flagsText.contains(token) ||
                (token.length >= 2 && compactName.contains(COMPACT_STRIP_REGEX.replace(token, "")))
        }
    }
}
