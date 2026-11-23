package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.countryCodeToFlagEmoji
import java.util.Locale
import java.util.UUID

/**
 * Normalized server record retrieved from a configured Nezha or Komari probe panel
 * for syncing into the local SSH Host Vault (`主机保险库`).
 */
@Immutable
data class ProbeSyncMachine(
    val panelId: String,
    val panelName: String,
    val panelType: ProbePanelType,
    val remoteServerId: String,
    val name: String,
    val ipv4: String = "",
    val ipv6: String = "",
    val countryCode: String = "",
    val flagEmoji: String = "",
    val groupName: String = "",
    val tags: List<String> = emptyList(),
    val isOnline: Boolean = false,
    val hasPanelToken: Boolean = false,
) {
    val probeKey: String
        get() = ProbeServerSnapshot.makeProbeKey(panelId, remoteServerId)

    val preferredHost: String
        get() = ipv4.trim().ifBlank { ipv6.trim() }

    val candidateHosts: List<String>
        get() = listOf(ipv4.trim(), ipv6.trim())
            .filter { it.isNotEmpty() }
            .distinctBy { SshProbeSyncMerger.normalizeHostForMatch(it) }

    val resolvedFlagEmoji: String
        get() = flagEmoji.trim().ifBlank { countryCodeToFlagEmoji(countryCode) }

    val regionLabel: String
        get() {
            val flag = resolvedFlagEmoji
            val cc = countryCode.trim().uppercase(Locale.US)
            return when {
                flag.isNotEmpty() && cc.isNotEmpty() -> "$flag $cc"
                flag.isNotEmpty() -> flag
                cc.isNotEmpty() -> cc
                else -> ""
            }
        }

    val supportsPanelTerminal: Boolean
        get() = hasPanelToken && (panelType == ProbePanelType.NEZHA_V1 || panelType == ProbePanelType.KOMARI)

    companion object {
        /**
         * Builds a fallback [ProbeSyncMachine] from a cached [ProbeServerSnapshot] + [ProbePanelConfig]
         * when live admin metadata fetch is unavailable (e.g. offline cache or public guest mode).
         */
        fun fromSnapshot(
            snapshot: ProbeServerSnapshot,
            panel: ProbePanelConfig,
        ): ProbeSyncMachine = ProbeSyncMachine(
            panelId = snapshot.panelId,
            panelName = panel.name.ifBlank { panel.baseUrl },
            panelType = panel.effectivePanelType,
            remoteServerId = snapshot.remoteServerId,
            name = snapshot.name,
            countryCode = snapshot.countryCode,
            flagEmoji = snapshot.resolvedFlagEmoji,
            isOnline = snapshot.isOnline,
            hasPanelToken = !panel.apiToken.isNullOrBlank(),
        )
    }
}

/**
 * Deterministic outcome of merging [ProbeSyncMachine] entries into existing [SshServerProfile] vault records.
 */
@Immutable
data class SshProbeSyncOutcome(
    val mergedServers: List<SshServerProfile>,
    val addedCount: Int = 0,
    val updatedCount: Int = 0,
    val linkedManualCount: Int = 0,
    val unchangedCount: Int = 0,
    val totalSyncedMachines: Int = 0,
) {
    val hasChanges: Boolean
        get() = addedCount > 0 || updatedCount > 0 || linkedManualCount > 0
}

/**
 * Pure domain merge & deduplication engine syncing Nezha / Komari probe machines into
 * the local encrypted SSH Host Vault (`主机保险库`).
 *
 * Guarantees:
 * - **Idempotent deduplication**: Re-syncing never duplicates machines; primary match is by
 *   `probeKey` (`panelId:remoteServerId`).
 * - **Manual host adoption**: If a manually-added unlinked vault entry already shares the same
 *   host IP (IPv4 or IPv6) as an incoming probe machine, it is linked in-place instead of
 *   creating a duplicate.
 * - **Strict credential preservation**: Existing `encryptedSecret`, `encryptedPassphrase`,
 *   `authMethod`, `username`, `port`, `jumpHostId`, `lastConnectedEpochMillis`, and
 *   `createdAtEpochMillis` are NEVER overwritten by probe sync.
 * - **Respects local customizations**: User-customized aliases, group tags, connection mode,
 *   and manually overridden host addresses are preserved across re-syncs; untouched probe-reported
 *   host/name fields track updates from the panel (resetting TOFU fingerprint if the IP migrated).
 */
object SshProbeSyncMerger {

    fun merge(
        existingServers: List<SshServerProfile>,
        incomingMachines: List<ProbeSyncMachine>,
        nowEpochMillis: Long = System.currentTimeMillis(),
        idGenerator: () -> String = { UUID.randomUUID().toString() },
    ): SshProbeSyncOutcome {
        val dedupedIncoming = deduplicateIncoming(incomingMachines)
        if (dedupedIncoming.isEmpty()) {
            return SshProbeSyncOutcome(
                mergedServers = existingServers,
                totalSyncedMachines = 0,
            )
        }

        val working = existingServers.toMutableList()
        val indexByProbeKey = HashMap<String, Int>(working.size)
        for (i in working.indices) {
            val key = working[i].probeKey?.lowercase(Locale.ROOT)
            if (key != null) {
                indexByProbeKey.putIfAbsent(key, i)
            }
        }

        val claimedExistingIndices = HashSet<Int>()
        val newlyAdded = ArrayList<SshServerProfile>()
        var addedCount = 0
        var updatedCount = 0
        var linkedManualCount = 0
        var unchangedCount = 0

        for (machine in dedupedIncoming) {
            val normalizedProbeKey = machine.probeKey.lowercase(Locale.ROOT)
            val existingByProbeIdx = indexByProbeKey[normalizedProbeKey]

            if (existingByProbeIdx != null) {
                val existing = working[existingByProbeIdx]
                claimedExistingIndices += existingByProbeIdx
                val merged = mergeIntoExisting(
                    existing = existing,
                    machine = machine,
                    isAdoptingManual = false,
                    nowEpochMillis = nowEpochMillis,
                )
                working[existingByProbeIdx] = merged
                if (hasMeaningfulMetadataChange(existing, merged)) {
                    updatedCount++
                } else {
                    unchangedCount++
                }
                continue
            }

            // Secondary match: adopt a unique unlinked manual vault entry matching the same IP
            val manualMatchIdx = findAdoptableManualIndex(
                working = working,
                machine = machine,
                claimedIndices = claimedExistingIndices,
            )
            if (manualMatchIdx != null) {
                val existing = working[manualMatchIdx]
                claimedExistingIndices += manualMatchIdx
                val merged = mergeIntoExisting(
                    existing = existing,
                    machine = machine,
                    isAdoptingManual = true,
                    nowEpochMillis = nowEpochMillis,
                )
                working[manualMatchIdx] = merged
                indexByProbeKey[normalizedProbeKey] = manualMatchIdx
                linkedManualCount++
                continue
            }

            // Brand-new probe-synced vault entry
            val created = createNewProfile(
                machine = machine,
                nowEpochMillis = nowEpochMillis,
                id = idGenerator(),
            )
            newlyAdded += created
            addedCount++
        }

        // Preserve existing order, appending newly discovered probe machines at the end
        val finalList = if (newlyAdded.isEmpty()) {
            working
        } else {
            working + newlyAdded
        }

        return SshProbeSyncOutcome(
            mergedServers = finalList,
            addedCount = addedCount,
            updatedCount = updatedCount,
            linkedManualCount = linkedManualCount,
            unchangedCount = unchangedCount,
            totalSyncedMachines = dedupedIncoming.size,
        )
    }

    internal fun normalizeHostForMatch(rawHost: String): String =
        rawHost.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .lowercase(Locale.ROOT)

    private fun deduplicateIncoming(machines: List<ProbeSyncMachine>): List<ProbeSyncMachine> {
        val byKey = LinkedHashMap<String, ProbeSyncMachine>(machines.size)
        for (machine in machines) {
            val panelId = machine.panelId.trim()
            val remoteId = machine.remoteServerId.trim()
            if (panelId.isEmpty() || remoteId.isEmpty()) continue
            val clean = machine.copy(
                panelId = panelId,
                panelName = machine.panelName.trim().ifBlank { panelId },
                remoteServerId = remoteId,
                name = machine.name.trim().ifBlank { "节点 #$remoteId" },
                ipv4 = machine.ipv4.trim(),
                ipv6 = machine.ipv6.trim(),
                countryCode = machine.countryCode.trim().uppercase(Locale.US),
                flagEmoji = machine.flagEmoji.trim(),
                groupName = machine.groupName.trim(),
                tags = normalizeTags(machine.tags),
            )
            val normKey = clean.probeKey.lowercase(Locale.ROOT)
            val prev = byKey[normKey]
            byKey[normKey] = if (prev == null) {
                clean
            } else {
                clean.copy(
                    ipv4 = clean.ipv4.ifBlank { prev.ipv4 },
                    ipv6 = clean.ipv6.ifBlank { prev.ipv6 },
                    countryCode = clean.countryCode.ifBlank { prev.countryCode },
                    flagEmoji = clean.flagEmoji.ifBlank { prev.flagEmoji },
                    groupName = clean.groupName.ifBlank { prev.groupName },
                    tags = normalizeTags(prev.tags + clean.tags),
                    hasPanelToken = clean.hasPanelToken || prev.hasPanelToken,
                    isOnline = clean.isOnline || prev.isOnline,
                )
            }
        }
        return byKey.values.toList()
    }

    private fun findAdoptableManualIndex(
        working: List<SshServerProfile>,
        machine: ProbeSyncMachine,
        claimedIndices: Set<Int>,
    ): Int? {
        val candidateHosts = machine.candidateHosts
            .map(::normalizeHostForMatch)
            .filter { it.isNotEmpty() }
            .toSet()
        if (candidateHosts.isEmpty()) return null

        val matchedIndices = ArrayList<Int>(2)
        for (i in working.indices) {
            if (i in claimedIndices) continue
            val existing = working[i]
            if (existing.isProbeSynced) continue
            val existingHost = normalizeHostForMatch(existing.host)
            if (existingHost.isNotEmpty() && existingHost in candidateHosts) {
                matchedIndices += i
            }
        }
        // Only auto-adopt if unambiguous (single unlinked manual host with that IP)
        return matchedIndices.singleOrNull()
    }

    private fun createNewProfile(
        machine: ProbeSyncMachine,
        nowEpochMillis: Long,
        id: String,
    ): SshServerProfile {
        val resolvedGroup = resolveInitialGroupTag(machine)
        val encodedAlias = SshVaultCatalogFilter.encodeAliasWithGroup(
            alias = machine.name,
            groupTag = resolvedGroup,
            fallbackHost = machine.preferredHost,
        )
        val initialMode = if (machine.supportsPanelTerminal) {
            SshConnectionMode.PANEL_TERMINAL
        } else {
            SshConnectionMode.DIRECT_SSH
        }
        return SshServerProfile(
            id = id,
            alias = encodedAlias,
            host = machine.preferredHost,
            port = SshServerProfile.DEFAULT_PORT,
            username = SshServerProfile.DEFAULT_USERNAME,
            authMethod = SshAuthMethod.PASSWORD,
            encryptedSecret = "",
            encryptedPassphrase = null,
            knownHostKeyFingerprint = null,
            knownHostKeyAlgorithm = null,
            lastConnectedEpochMillis = null,
            createdAtEpochMillis = nowEpochMillis,
            jumpHostId = null,
            groupTag = resolvedGroup,
            probePanelId = machine.panelId,
            probePanelName = machine.panelName,
            probePanelType = machine.panelType.name,
            probeServerId = machine.remoteServerId,
            probeRegion = machine.regionLabel,
            probeTags = buildStoredTags(machine),
            probeSyncedAtEpochMillis = nowEpochMillis,
            probeReportedHost = machine.preferredHost,
            probeReportedName = machine.name,
            connectionMode = initialMode,
        )
    }

    private fun mergeIntoExisting(
        existing: SshServerProfile,
        machine: ProbeSyncMachine,
        isAdoptingManual: Boolean,
        nowEpochMillis: Long,
    ): SshServerProfile {
        val incomingHost = machine.preferredHost
        val existingHostNorm = normalizeHostForMatch(existing.host)
        val prevReportedHostNorm = normalizeHostForMatch(existing.probeReportedHost)
        val incomingHostNorm = normalizeHostForMatch(incomingHost)

        // Update host only when blank OR still tracking the previous probe-reported IP (not manually overridden)
        val shouldUpdateHost = incomingHost.isNotEmpty() && (
            existingHostNorm.isEmpty() ||
                (!isAdoptingManual && prevReportedHostNorm.isNotEmpty() && existingHostNorm == prevReportedHostNorm)
            )
        val nextHost = if (shouldUpdateHost) incomingHost else existing.host
        val hostEndpointChanged = nextHost.isNotEmpty() &&
            existingHostNorm.isNotEmpty() &&
            normalizeHostForMatch(nextHost) != existingHostNorm

        val incomingGroup = resolveInitialGroupTag(machine)
        val existingGroup = existing.resolvedGroup.orEmpty()
        val nextGroup = when {
            existingGroup.isNotBlank() -> existingGroup
            incomingGroup.isNotBlank() -> incomingGroup
            else -> ""
        }

        // Track panel-side rename only if the user hasn't customized the local alias
        val userCustomizedAlias = isAdoptingManual || (
            existing.probeReportedName.isNotBlank() &&
                !existing.cleanAlias.equals(existing.probeReportedName, ignoreCase = true)
            )
        val nextAlias = when {
            userCustomizedAlias -> {
                if (existingGroup.isBlank() && nextGroup.isNotBlank()) {
                    SshVaultCatalogFilter.encodeAliasWithGroup(
                        alias = existing.cleanAlias.ifBlank { existing.alias },
                        groupTag = nextGroup,
                        fallbackHost = nextHost,
                    )
                } else {
                    existing.alias
                }
            }
            else -> SshVaultCatalogFilter.encodeAliasWithGroup(
                alias = machine.name,
                groupTag = nextGroup,
                fallbackHost = nextHost,
            )
        }

        // Preserve connectionMode; upgrade unconfigured entries to PANEL_TERMINAL when panel supports it
        val nextConnectionMode = when {
            isAdoptingManual && existing.hasDirectSshCredentials -> existing.connectionMode
            existing.connectionMode == SshConnectionMode.PANEL_TERMINAL -> SshConnectionMode.PANEL_TERMINAL
            !existing.hasDirectSshCredentials && machine.supportsPanelTerminal && !existing.hasDirectSshHost ->
                SshConnectionMode.PANEL_TERMINAL
            else -> existing.connectionMode
        }

        val nextReportedHost = incomingHost.ifBlank { existing.probeReportedHost }
        val nextRegion = machine.regionLabel.ifBlank { existing.probeRegion }
        val incomingTags = buildStoredTags(machine)
        val nextTags = if (incomingTags.isNotEmpty()) incomingTags else existing.probeTags

        return existing.copy(
            alias = nextAlias,
            host = nextHost,
            // Strict TOFU safety: if synced IP migrated, clear stale host key fingerprint
            knownHostKeyFingerprint = if (hostEndpointChanged) null else existing.knownHostKeyFingerprint,
            knownHostKeyAlgorithm = if (hostEndpointChanged) null else existing.knownHostKeyAlgorithm,
            groupTag = nextGroup,
            probePanelId = machine.panelId,
            probePanelName = machine.panelName,
            probePanelType = machine.panelType.name,
            probeServerId = machine.remoteServerId,
            probeRegion = nextRegion,
            probeTags = nextTags,
            probeSyncedAtEpochMillis = nowEpochMillis,
            probeReportedHost = nextReportedHost,
            probeReportedName = machine.name,
            connectionMode = nextConnectionMode,
        )
    }

    private fun hasMeaningfulMetadataChange(
        before: SshServerProfile,
        after: SshServerProfile,
    ): Boolean = before.alias != after.alias ||
        before.host != after.host ||
        before.groupTag != after.groupTag ||
        before.probePanelId != after.probePanelId ||
        before.probePanelName != after.probePanelName ||
        before.probePanelType != after.probePanelType ||
        before.probeServerId != after.probeServerId ||
        before.probeRegion != after.probeRegion ||
        before.probeTags != after.probeTags ||
        before.connectionMode != after.connectionMode

    private fun resolveInitialGroupTag(machine: ProbeSyncMachine): String {
        if (machine.groupName.isNotBlank()) return machine.groupName.take(20)
        val firstTag = machine.tags.firstOrNull { it.isNotBlank() }
        if (!firstTag.isNullOrBlank()) return firstTag.take(20)
        val regionGroup = countryCodeToGroupLabel(machine.countryCode)
        if (regionGroup.isNotBlank()) return regionGroup
        return machine.panelName.trim().take(20)
    }

    private fun buildStoredTags(machine: ProbeSyncMachine): List<String> {
        val combined = buildList {
            if (machine.groupName.isNotBlank()) add(machine.groupName.trim())
            addAll(machine.tags)
        }
        return normalizeTags(combined).take(8)
    }

    private fun normalizeTags(raw: List<String>): List<String> =
        raw.asSequence()
            .flatMap { it.split(';', ',', '，').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()

    internal fun countryCodeToGroupLabel(countryCode: String): String =
        when (countryCode.trim().uppercase(Locale.US)) {
            "HK" -> "香港"
            "JP" -> "日本"
            "SG" -> "新加坡"
            "US" -> "美国"
            "TW" -> "台湾"
            "KR" -> "韩国"
            "CN" -> "中国"
            "GB", "UK" -> "英国"
            "DE" -> "德国"
            "NL" -> "荷兰"
            "FR" -> "法国"
            "CA" -> "加拿大"
            "AU" -> "澳大利亚"
            "RU" -> "俄罗斯"
            else -> countryCode.trim().uppercase(Locale.US)
        }
}
