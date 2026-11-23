package com.nodeseek.app.data.mail

import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.ParsedVpsBillingMail
import com.nodeseek.app.domain.mail.RenewalAutoUpdateRecord
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.VpsBillingMailParser
import com.nodeseek.app.domain.mail.VpsMailAutoIngestService
import com.nodeseek.app.domain.mail.VpsMailIngestOutcome
import com.nodeseek.app.domain.mail.VpsMailMatchStatus
import com.nodeseek.app.domain.mail.VpsMailType
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultCatalogFilter
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

@Serializable
private data class StoredVpsMailIngestState(
    val version: Int = 1,
    val processedRenewalKeys: Set<String> = emptySet(),
    val records: List<RenewalAutoUpdateRecord> = emptyList(),
)

/**
 * Thread-safe, disk-persisted implementation of [VpsMailAutoIngestService].
 *
 * Guarantees idempotent renewal expiry advancement across repeated list refreshes, mail detail opens,
 * manual import taps, and cold app restarts via atomic persistence to [stateFile].
 */
class VpsMailAutoIngestServiceImpl(
    private val fleetRepository: FleetRepository,
    private val sshVaultRepository: SshVaultRepository,
    private val stateFile: File,
    private val mailDetailPeeker: (suspend (Long) -> SeekMailDetail?)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val todayProvider: () -> LocalDate = {
        VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID))
    },
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : VpsMailAutoIngestService {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private var diskLoaded = false
    private var processedRenewalKeys: MutableSet<String> = LinkedHashSet()
    private var storedRecords: MutableList<RenewalAutoUpdateRecord> = mutableListOf()
    // Session-scoped set of unmatched renewal summary IDs already peeked, avoiding redundant network peeks on refresh.
    private val peekedUnmatchedSummaryIds = HashSet<Long>()

    private val _autoUpdatesByMailKeyFlow = MutableStateFlow<Map<String, RenewalAutoUpdateRecord>>(emptyMap())
    override val autoUpdatesByMailKeyFlow: StateFlow<Map<String, RenewalAutoUpdateRecord>> =
        _autoUpdatesByMailKeyFlow.asStateFlow()

    private val _renewalAutoUpdateEvents = MutableSharedFlow<RenewalAutoUpdateRecord>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val renewalAutoUpdateEvents: Flow<RenewalAutoUpdateRecord> =
        _renewalAutoUpdateEvents.asSharedFlow()

    override val assetsFlow: StateFlow<List<VpsAssetRecord>>
        get() = fleetRepository.assetsFlow

    override val sshServersFlow: StateFlow<List<SshServerProfile>>
        get() = sshVaultRepository.serversFlow

    override suspend fun ensureLoaded() {
        ensureFleetStateLoaded()
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureDiskLoadedLocked()
            }
        }
    }

    override suspend fun processIncomingSummaries(
        summaries: List<SeekMailSummary>,
    ): List<RenewalAutoUpdateRecord> {
        if (summaries.isEmpty()) return emptyList()

        // Fast in-memory check before acquiring disk locks: skip pages without renewal candidates.
        val renewalCandidates = summaries.mapNotNull { summary ->
            val parsedFromSummary = VpsBillingMailParser.parse(
                subject = summary.subject,
                bodyText = summary.snippet,
                fromName = summary.fromName,
                fromAddress = summary.fromAddress,
            )
            if (parsedFromSummary?.mailType == VpsMailType.RENEWAL) {
                summary to parsedFromSummary
            } else {
                null
            }
        }
        if (renewalCandidates.isEmpty()) return emptyList()

        ensureFleetStateLoaded()

        val allApplied = mutableListOf<RenewalAutoUpdateRecord>()
        var detailPeeksInBatch = 0
        for ((summary, summaryParsed) in renewalCandidates) {
            val alreadyProcessed = withContext(ioDispatcher) {
                mutex.withLock {
                    ensureDiskLoadedLocked()
                    val checkKeys = buildMailLookupKeys(summary, summaryParsed)
                    checkKeys.any { it in processedRenewalKeys }
                }
            }
            if (alreadyProcessed) continue

            // Decide whether we should peek full mail detail to get IP / product / explicit due date
            // that might lie beyond the 160-char snippet.
            val currentAssets = fleetRepository.assetsFlow.value
            val currentSsh = sshVaultRepository.serversFlow.value
            val summaryMatches = matchAssetsForMail(summaryParsed, currentAssets, currentSsh)
            val needsFullDetail = (summaryMatches.isEmpty() || summaryParsed.nextDueDateIso == null) &&
                mailDetailPeeker != null &&
                summary.id > 0L

            var effectiveSummary = summary
            var effectiveParsed = summaryParsed
            var skippedPeekDueToBatchCap = false

            if (needsFullDetail) {
                val shouldPeek = mutex.withLock { summary.id !in peekedUnmatchedSummaryIds }
                if (shouldPeek) {
                    if (detailPeeksInBatch >= MAX_DETAIL_PEEKS_PER_BATCH) {
                        skippedPeekDueToBatchCap = true
                    } else {
                        detailPeeksInBatch++
                        val peeked = try {
                            mailDetailPeeker?.invoke(summary.id)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            null
                        }
                        if (peeked != null) {
                            val detailParsed = VpsBillingMailParser.parseDetail(peeked)
                            if (detailParsed?.mailType == VpsMailType.RENEWAL) {
                                effectiveSummary = peeked.summary
                                effectiveParsed = detailParsed
                            } else {
                                // Full body disproved RENEWAL classification; do not fall back to snippet.
                                mutex.withLock { peekedUnmatchedSummaryIds.add(summary.id) }
                                continue
                            }
                        }
                    }
                }
            }

            val applied = applyRenewalAutoUpdate(
                summary = effectiveSummary,
                parsed = effectiveParsed,
            )
            if (applied.isEmpty() && summary.id > 0L && !skippedPeekDueToBatchCap) {
                mutex.withLock { peekedUnmatchedSummaryIds.add(summary.id) }
            }
            allApplied += applied
        }
        return allApplied
    }

    override suspend fun processMailDetail(
        detail: SeekMailDetail,
    ): List<RenewalAutoUpdateRecord> {
        val parsed = VpsBillingMailParser.parseDetail(detail) ?: return emptyList()
        if (parsed.mailType != VpsMailType.RENEWAL) return emptyList()
        ensureFleetStateLoaded()
        return applyRenewalAutoUpdate(
            summary = detail.summary,
            parsed = parsed,
        )
    }

    override fun resolveMatchStatus(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
        assets: List<VpsAssetRecord>,
        sshServers: List<SshServerProfile>,
        autoUpdatesByMailKey: Map<String, RenewalAutoUpdateRecord>,
    ): VpsMailMatchStatus {
        val matchedAssets = matchAssetsForMail(parsed, assets, sshServers)
        val matchedSsh = matchSshProfileForMail(parsed, sshServers)
        val lookupKeys = buildMailLookupKeys(summary, parsed)
        val autoUpdate = lookupKeys.firstNotNullOfOrNull { key -> autoUpdatesByMailKey[key] }

        return VpsMailMatchStatus(
            matchedAssets = matchedAssets,
            matchedSshProfile = matchedSsh,
            renewalAutoUpdate = autoUpdate,
        )
    }

    override suspend fun ingestMail(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
        overwriteExistingSshCredentials: Boolean,
    ): Result<VpsMailIngestOutcome> = runCatching {
        ensureFleetStateLoaded()

        withContext(ioDispatcher) {
            mutex.withLock {
                ensureDiskLoadedLocked()
                val assets = fleetRepository.assetsFlow.value
                val sshServers = sshVaultRepository.serversFlow.value

                val existingAsset = matchAssetsForMail(parsed, assets, sshServers).firstOrNull()
                val assetCreated = existingAsset == null
                val effectiveCycle = parsed.billingCycle
                    ?: existingAsset?.cyclePreset
                    ?: BillingCyclePreset.ANNUAL

                val mailKeys = buildMailLookupKeys(summary, parsed)
                val dedupeKeysToCheck = if (existingAsset != null) {
                    buildAllDedupeKeysForAsset(summary, parsed, existingAsset.id)
                } else {
                    mailKeys
                }
                val alreadyProcessedRenewal = dedupeKeysToCheck.any { it in processedRenewalKeys }

                val resolvedExpiryIso = resolveIngestExpiryDateIso(
                    summary = summary,
                    parsed = parsed,
                    existingAsset = existingAsset,
                    effectiveCycle = effectiveCycle,
                    alreadyProcessedRenewal = alreadyProcessedRenewal,
                )

                val mergedNotes = mergeManagedMetadataIntoNotes(
                    existingNotes = existingAsset?.notes.orEmpty(),
                    parsed = parsed,
                )

                val existingTags = existingAsset?.resolvedTags.orEmpty()
                val assetToSave = VpsAssetRecord(
                    id = existingAsset?.id ?: idGenerator(),
                    nickname = existingAsset?.nickname?.takeIf { it.isNotBlank() }
                        ?: buildSuggestedNickname(parsed),
                    providerName = parsed.providerName.ifBlank { existingAsset?.providerName.orEmpty() },
                    specsSummary = parsed.specsSummary.ifBlank { existingAsset?.specsSummary.orEmpty() },
                    region = parsed.region.ifBlank { existingAsset?.region.orEmpty() },
                    routeTag = VpsAssetRecord.formatRouteTag(existingTags),
                    renewalAmount = parsed.billingAmount?.takeIf { it.isNotBlank() }
                        ?: existingAsset?.renewalAmount.orEmpty(),
                    currency = if (!parsed.billingAmount.isNullOrBlank()) {
                        parsed.currency
                    } else {
                        existingAsset?.currency ?: parsed.currency
                    },
                    cyclePreset = effectiveCycle,
                    customCycleDays = existingAsset?.customCycleDays,
                    expiryDateIso = resolvedExpiryIso,
                    purchaseUrl = existingAsset?.purchaseUrl.orEmpty(),
                    panelManageUrl = parsed.panelUrl.ifBlank { existingAsset?.panelManageUrl.orEmpty() },
                    linkedProbeKey = existingAsset?.linkedProbeKey,
                    monthlyTrafficQuotaGb = parsed.monthlyTrafficQuotaGb
                        ?: existingAsset?.monthlyTrafficQuotaGb,
                    trafficResetDay = existingAsset?.trafficResetDay,
                    remindDaysBeforeExpiry = existingAsset?.remindDaysBeforeExpiry
                        ?: VpsAssetRecord.DEFAULT_REMIND_DAYS_BEFORE_EXPIRY,
                    autoRenewNote = existingAsset?.autoRenewNote.orEmpty(),
                    notes = mergedNotes,
                    createdAtEpochMillis = existingAsset?.createdAtEpochMillis ?: 0L,
                    updatedAtEpochMillis = nowMillisProvider(),
                    tags = existingTags,
                    autoRenew = existingAsset?.autoRenew == true,
                )

                val savedAsset = fleetRepository.upsertAsset(assetToSave)

                var savedSshProfile: SshServerProfile? = null
                var sshCreated = false
                var sshPreservedExisting = false

                if (parsed.hasSshCredentials) {
                    val targetHost = parsed.primaryHost!!.trim()
                    val targetUser = parsed.sshUsername.trim()
                        .ifBlank { SshServerProfile.DEFAULT_SSH_USERNAME_FALLBACK }
                    val existingSsh = matchSshProfileForMail(parsed, sshServers)

                    if (existingSsh != null && !overwriteExistingSshCredentials) {
                        savedSshProfile = existingSsh
                        sshCreated = false
                        sshPreservedExisting = true
                    } else {
                        sshCreated = existingSsh == null
                        val suggestedGroup = existingSsh?.resolvedGroup
                            ?: mapRegionToSuggestedSshGroup(parsed.region)
                        val draft = SshVaultCatalogFilter.normalizeDraftForPersistence(
                            SshServerDraft(
                                id = existingSsh?.id,
                                alias = existingSsh?.cleanAlias?.takeIf { it.isNotBlank() }
                                    ?: buildSuggestedSshAlias(parsed, savedAsset.nickname),
                                host = targetHost,
                                port = parsed.sshPort.coerceIn(1, 65535),
                                username = targetUser,
                                authMethod = SshAuthMethod.PASSWORD,
                                secret = parsed.sshPassword.orEmpty(),
                                keepExistingSecret = false,
                                jumpHostId = existingSsh?.jumpHostId,
                                groupTag = suggestedGroup,
                            ),
                        )
                        savedSshProfile = try {
                            sshVaultRepository.saveServer(draft)
                        } catch (t: Throwable) {
                            // Roll back the Fleet write so both stores remain consistent.
                            runCatching {
                                if (existingAsset != null) {
                                    fleetRepository.upsertAsset(existingAsset)
                                } else {
                                    fleetRepository.deleteAsset(savedAsset.id)
                                }
                            }
                            throw t
                        }
                    }
                }

                // Mark renewal dedupe keys only after both Fleet and SSH writes have succeeded.
                if (parsed.mailType == VpsMailType.RENEWAL && !alreadyProcessedRenewal) {
                    val allDedupeKeys = buildAllDedupeKeysForAsset(summary, parsed, savedAsset.id)
                    processedRenewalKeys.addAll(allDedupeKeys)
                    if (existingAsset != null && existingAsset.expiryDateIso != savedAsset.expiryDateIso) {
                        val record = RenewalAutoUpdateRecord(
                            mailDedupeKey = primaryMailKey(summary, parsed),
                            mailId = summary?.id ?: 0L,
                            assetId = savedAsset.id,
                            assetNickname = savedAsset.nickname,
                            providerName = savedAsset.providerName,
                            previousExpiryDateIso = existingAsset.expiryDateIso,
                            newExpiryDateIso = savedAsset.expiryDateIso,
                            usedExplicitMailDate = parsed.nextDueDateIso != null,
                            appliedCycleName = effectiveCycle.name,
                            invoiceNumber = parsed.invoiceNumber,
                            updatedAtEpochMillis = nowMillisProvider(),
                        )
                        recordAutoUpdateLocked(record, mailKeys)
                    } else {
                        persistStateLocked()
                    }
                }

                val summaryMessage = buildIngestFeedbackMessage(
                    asset = savedAsset,
                    assetCreated = assetCreated,
                    sshProfile = savedSshProfile,
                    sshCreated = sshCreated,
                    sshPreservedExisting = sshPreservedExisting,
                )

                VpsMailIngestOutcome(
                    asset = savedAsset,
                    assetCreated = assetCreated,
                    sshProfile = savedSshProfile,
                    sshCreated = sshCreated,
                    summaryMessage = summaryMessage,
                )
            }
        }
    }.onFailure { if (it is CancellationException) throw it }

    // ── Renewal Auto-Update Engine ───────────────────────────────────────────

    private suspend fun applyRenewalAutoUpdate(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
    ): List<RenewalAutoUpdateRecord> = withContext(ioDispatcher) {
        val emitted = mutableListOf<RenewalAutoUpdateRecord>()

        mutex.withLock {
            ensureDiskLoadedLocked()
            val mailLookupKeys = buildMailLookupKeys(summary, parsed)
            if (mailLookupKeys.any { it in processedRenewalKeys }) {
                return@withLock
            }

            val currentAssets = fleetRepository.assetsFlow.value
            val currentSsh = sshVaultRepository.serversFlow.value
            val matchedAssets = matchAssetsForMail(parsed, currentAssets, currentSsh)
            // Only auto-advance when a single unambiguous asset is matched.
            if (matchedAssets.size != 1) {
                return@withLock
            }

            var stateMutated = false
            val primaryKey = primaryMailKey(summary, parsed)
            val now = nowMillisProvider()

            for (asset in matchedAssets) {
                val assetSpecificKeys = buildAllDedupeKeysForAsset(summary, parsed, asset.id)
                if (assetSpecificKeys.any { it in processedRenewalKeys }) {
                    continue
                }

                val currentExpiry = FleetPortfolioCalculator.parseExpiryDate(asset.expiryDateIso)
                val explicitNewDate = parsed.nextDueDateIso?.let { FleetPortfolioCalculator.parseExpiryDate(it) }
                val effectiveCycle = parsed.billingCycle ?: asset.cyclePreset

                val newExpiryDate: LocalDate
                val usedExplicitDate: Boolean

                if (explicitNewDate != null) {
                    if (currentExpiry != null && explicitNewDate.isBefore(currentExpiry)) {
                        // Historical renewal mail older than current asset expiry: mark processed without regressing!
                        processedRenewalKeys.addAll(assetSpecificKeys)
                        stateMutated = true
                        continue
                    }
                    newExpiryDate = explicitNewDate
                    usedExplicitDate = true
                } else {
                    val baseDate = currentExpiry
                        ?: parseMailReceivedDate(summary)
                        ?: todayProvider()
                    newExpiryDate = advanceDateByCycle(
                        baseDate = baseDate,
                        cycle = effectiveCycle,
                        customCycleDays = asset.customCycleDays,
                    )
                    usedExplicitDate = false
                }

                val newExpiryIso = newExpiryDate.toString()
                val previousIso = asset.expiryDateIso

                if (newExpiryIso != previousIso) {
                    val updatedAsset = asset.copy(
                        expiryDateIso = newExpiryIso,
                        renewalAmount = asset.renewalAmount.ifBlank { parsed.billingAmount.orEmpty() },
                        currency = if (asset.renewalAmount.isBlank() && !parsed.billingAmount.isNullOrBlank()) {
                            parsed.currency
                        } else {
                            asset.currency
                        },
                        updatedAtEpochMillis = now,
                    )
                    fleetRepository.upsertAsset(updatedAsset)
                }

                processedRenewalKeys.addAll(assetSpecificKeys)
                val record = RenewalAutoUpdateRecord(
                    mailDedupeKey = primaryKey,
                    mailId = summary?.id ?: 0L,
                    assetId = asset.id,
                    assetNickname = asset.nickname,
                    providerName = asset.providerName.ifBlank { parsed.providerName },
                    previousExpiryDateIso = previousIso,
                    newExpiryDateIso = newExpiryIso,
                    usedExplicitMailDate = usedExplicitDate,
                    appliedCycleName = effectiveCycle.name,
                    invoiceNumber = parsed.invoiceNumber,
                    updatedAtEpochMillis = now,
                )
                recordAutoUpdateLocked(record, mailLookupKeys, persistImmediately = false)
                stateMutated = true
                if (newExpiryIso != previousIso) {
                    emitted += record
                }
            }

            if (stateMutated) {
                persistStateLocked()
            }
        }

        emitted.forEach { record ->
            _renewalAutoUpdateEvents.tryEmit(record)
        }
        emitted
    }

    private suspend fun ensureFleetStateLoaded() {
        if (!fleetRepository.isPersistedStateLoadedFlow.value) {
            fleetRepository.loadPersistedState()
        }
    }

    // ── Matching & Correlation Helpers ───────────────────────────────────────

    internal fun matchAssetsForMail(
        parsed: ParsedVpsBillingMail,
        assets: List<VpsAssetRecord>,
        sshServers: List<SshServerProfile>,
    ): List<VpsAssetRecord> {
        if (assets.isEmpty()) return emptyList()

        // 1. Exact IPv4 or IPv6 match
        val ipv4 = parsed.mainIpv4?.trim()?.takeIf { it.isNotEmpty() }
        val ipv6Canon = parsed.mainIpv6?.let { canonicalizeIpv6(it) }

        if (ipv4 != null || ipv6Canon != null) {
            val sshAliasesMatchingIp = sshServers
                .filter { profile ->
                    (ipv4 != null && profile.host.equals(ipv4, ignoreCase = true)) ||
                        (ipv6Canon != null && canonicalizeIpv6(profile.host) == ipv6Canon)
                }
                .mapNotNull { it.cleanAlias.trim().takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT) }
                .toSet()

            val byIp = assets.filter { asset ->
                (ipv4 != null && assetContainsIpv4(asset, ipv4)) ||
                    (ipv6Canon != null && assetContainsIpv6(asset, ipv6Canon)) ||
                    (asset.nickname.trim().lowercase(Locale.ROOT) in sshAliasesMatchingIp)
            }
            if (byIp.isNotEmpty()) return byIp
        }

        // 2. Hostname match
        val cleanHost = parsed.hostname.trim()
        if (cleanHost.length >= 4) {
            val byHost = assets.filter { asset ->
                assetContainsHostname(asset, cleanHost)
            }
            if (byHost.isNotEmpty()) return byHost
        }

        // 3. Provider + Product / Server Name match
        val providerNorm = normalizeIdentity(parsed.providerName)
        val productNorm = normalizeIdentity(parsed.productName)

        if (providerNorm.isNotEmpty() && productNorm.length >= 3) {
            val byProviderAndProduct = assets.filter { asset ->
                providerMatches(asset, providerNorm) && productMatches(asset, productNorm, parsed.productName)
            }
            if (byProviderAndProduct.isNotEmpty()) return byProviderAndProduct
        }

        // Fallback when user left providerName blank on the asset, but nickname matches distinct productName exactly
        if (productNorm.length >= 5) {
            val byExactNickname = assets.filter { asset ->
                normalizeIdentity(asset.nickname) == productNorm
            }
            if (byExactNickname.size == 1) return byExactNickname
        }

        return emptyList()
    }

    internal fun matchSshProfileForMail(
        parsed: ParsedVpsBillingMail,
        sshServers: List<SshServerProfile>,
    ): SshServerProfile? {
        if (sshServers.isEmpty()) return null
        val ipv4 = parsed.mainIpv4?.trim()?.takeIf { it.isNotEmpty() }
        val ipv6Canon = parsed.mainIpv6?.let { canonicalizeIpv6(it) }
        val hostname = parsed.hostname.trim().takeIf { it.isNotEmpty() }

        if (ipv4 != null) {
            val exactPortMatch = sshServers.firstOrNull {
                it.host.equals(ipv4, ignoreCase = true) && it.port == parsed.sshPort
            }
            if (exactPortMatch != null) return exactPortMatch
            sshServers.firstOrNull { it.host.equals(ipv4, ignoreCase = true) }?.let { return it }
        }

        if (ipv6Canon != null) {
            sshServers.firstOrNull { canonicalizeIpv6(it.host) == ipv6Canon }?.let { return it }
        }

        if (hostname != null) {
            sshServers.firstOrNull { it.host.equals(hostname, ignoreCase = true) }?.let { return it }
        }

        return null
    }

    private fun assetContainsIpv4(asset: VpsAssetRecord, ipv4: String): Boolean {
        val pattern = Regex("""(?<![0-9.])${Regex.escape(ipv4)}(?![0-9.])""")
        return pattern.containsMatchIn(asset.notes) ||
            pattern.containsMatchIn(asset.nickname) ||
            pattern.containsMatchIn(asset.specsSummary) ||
            pattern.containsMatchIn(asset.autoRenewNote) ||
            pattern.containsMatchIn(asset.panelManageUrl)
    }

    private fun assetContainsIpv6(asset: VpsAssetRecord, targetCanon: String): Boolean {
        val haystacks = listOf(asset.notes, asset.nickname, asset.specsSummary, asset.autoRenewNote)
        val tokenRegex = Regex("""([0-9a-fA-F:]{3,45})""")
        for (text in haystacks) {
            if (!text.contains(':')) continue
            for (m in tokenRegex.findAll(text)) {
                val cand = m.groupValues[1]
                if (cand.count { it == ':' } >= 2 && canonicalizeIpv6(cand) == targetCanon) {
                    return true
                }
            }
        }
        return false
    }

    private fun assetContainsHostname(asset: VpsAssetRecord, hostname: String): Boolean {
        val pattern = Regex("""(?<![A-Za-z0-9.-])${Regex.escape(hostname)}(?![A-Za-z0-9.-])""", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(asset.nickname) ||
            pattern.containsMatchIn(asset.notes) ||
            pattern.containsMatchIn(asset.autoRenewNote) ||
            pattern.containsMatchIn(asset.panelManageUrl)
    }

    private fun providerMatches(asset: VpsAssetRecord, targetProviderNorm: String): Boolean {
        val assetProv = normalizeIdentity(asset.providerName)
        val assetNick = normalizeIdentity(asset.nickname)
        if (assetProv.isNotEmpty() && (
                assetProv == targetProviderNorm ||
                    (assetProv.length >= 3 && targetProviderNorm.length >= 3 &&
                        (assetProv.contains(targetProviderNorm) || targetProviderNorm.contains(assetProv)))
            )
        ) {
            return true
        }
        return targetProviderNorm.length >= 3 && assetNick.contains(targetProviderNorm)
    }

    private fun productMatches(
        asset: VpsAssetRecord,
        targetProductNorm: String,
        rawProduct: String,
    ): Boolean {
        val nickNorm = normalizeIdentity(asset.nickname)
        val specsNorm = normalizeIdentity(asset.specsSummary)
        val notesNorm = normalizeIdentity(asset.notes)

        if (nickNorm.isNotEmpty() && (
                nickNorm == targetProductNorm ||
                    nickNorm.contains(targetProductNorm) ||
                    (nickNorm.length >= 4 && targetProductNorm.contains(nickNorm))
                )
        ) {
            return true
        }
        if (specsNorm.isNotEmpty() && specsNorm.contains(targetProductNorm)) return true
        if (notesNorm.isNotEmpty() && notesNorm.contains(targetProductNorm)) return true
        return asset.notes.contains(rawProduct.trim(), ignoreCase = true)
    }

    private fun normalizeIdentity(raw: String): String =
        raw.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s\-_./|·:()\[\]【】]+"""), "")

    /**
     * Pure-Kotlin RFC 4291 IPv6 canonicalizer. Never triggers DNS resolution and strictly
     * rejects non-IPv6 inputs (such as 6-group MAC addresses, timestamps, or hostnames).
     */
    internal fun canonicalizeIpv6(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        val slashIndex = trimmed.indexOf('/')
        val rawAddrPart = if (slashIndex >= 0) {
            val prefixStr = trimmed.substring(slashIndex + 1).trim()
            val prefix = prefixStr.toIntOrNull()
            if (prefix == null || prefix !in 0..128) return null
            trimmed.substring(0, slashIndex)
        } else {
            trimmed
        }
        val addrPart = rawAddrPart.trim().trim('[', ']')
        if (addrPart.length < 2 || !addrPart.contains(':')) return null
        if (addrPart.any { it.isWhitespace() }) return null

        // Handle optional IPv4-mapped suffix (e.g. ::ffff:192.0.2.128)
        val normalizedInput = if (addrPart.contains('.')) {
            val lastColon = addrPart.lastIndexOf(':')
            if (lastColon < 0) return null
            val ipv4Part = addrPart.substring(lastColon + 1)
            val octets = ipv4Part.split('.')
            if (octets.size != 4) return null
            val parsedOctets = octets.map { part ->
                if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return null
                part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            }
            val high = (parsedOctets[0] shl 8) or parsedOctets[1]
            val low = (parsedOctets[2] shl 8) or parsedOctets[3]
            addrPart.substring(0, lastColon + 1) +
                String.format(Locale.US, "%x:%x", high, low)
        } else {
            addrPart
        }

        val doubleColonIdx = normalizedInput.indexOf("::")
        val groups: List<Int> = if (doubleColonIdx >= 0) {
            // At most one "::" allowed
            if (normalizedInput.indexOf("::", doubleColonIdx + 2) >= 0) return null
            val leftStr = normalizedInput.substring(0, doubleColonIdx)
            val rightStr = normalizedInput.substring(doubleColonIdx + 2)
            if (leftStr.startsWith(':') || leftStr.endsWith(':') ||
                rightStr.startsWith(':') || rightStr.endsWith(':')
            ) {
                return null
            }
            val leftGroups = if (leftStr.isEmpty()) emptyList() else parseHexGroups(leftStr) ?: return null
            val rightGroups = if (rightStr.isEmpty()) emptyList() else parseHexGroups(rightStr) ?: return null
            val missing = 8 - (leftGroups.size + rightGroups.size)
            if (missing < 1) return null
            leftGroups + List(missing) { 0 } + rightGroups
        } else {
            if (normalizedInput.startsWith(':') || normalizedInput.endsWith(':')) return null
            val parsedGroups = parseHexGroups(normalizedInput) ?: return null
            if (parsedGroups.size != 8) return null
            parsedGroups
        }

        return groups.joinToString(":") { value ->
            String.format(Locale.US, "%04x", value)
        }
    }

    private fun parseHexGroups(segment: String): List<Int>? {
        val parts = segment.split(':')
        val result = ArrayList<Int>(parts.size)
        for (part in parts) {
            if (part.isEmpty() || part.length > 4) return null
            var value = 0
            for (ch in part) {
                val digit = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> return null
                }
                value = (value shl 4) or digit
            }
            result.add(value)
        }
        return result
    }

    // ── Expiry Date & Notes Formatting ───────────────────────────────────────

    private fun resolveIngestExpiryDateIso(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
        existingAsset: VpsAssetRecord?,
        effectiveCycle: BillingCyclePreset,
        alreadyProcessedRenewal: Boolean,
    ): String {
        val explicitDate = parsed.nextDueDateIso?.let { FleetPortfolioCalculator.parseExpiryDate(it) }
        val existingDate = existingAsset?.expiryDateIso?.let { FleetPortfolioCalculator.parseExpiryDate(it) }

        if (explicitDate != null) {
            if (parsed.mailType == VpsMailType.RENEWAL && existingDate != null && explicitDate.isBefore(existingDate)) {
                return existingDate.toString()
            }
            return explicitDate.toString()
        }

        if (parsed.mailType == VpsMailType.RENEWAL && existingDate != null) {
            return if (alreadyProcessedRenewal) {
                existingDate.toString()
            } else {
                advanceDateByCycle(existingDate, effectiveCycle, existingAsset.customCycleDays).toString()
            }
        }

        if (existingDate != null) {
            return existingDate.toString()
        }

        val baseDate = parseMailReceivedDate(summary) ?: todayProvider()
        return advanceDateByCycle(baseDate, effectiveCycle, existingAsset?.customCycleDays).toString()
    }

    internal fun mergeManagedMetadataIntoNotes(
        existingNotes: String,
        parsed: ParsedVpsBillingMail,
    ): String {
        val existingManagedFields = LinkedHashMap<String, String>()
        val keptUserLines = mutableListOf<String>()

        for (rawLine in existingNotes.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(MANAGED_NOTE_PREFIX)) {
                val body = line.removePrefix(MANAGED_NOTE_PREFIX).trim()
                for (segment in body.split("|")) {
                    val token = segment.trim()
                    val colonIdx = token.indexOf(':')
                    if (colonIdx > 0) {
                        val key = token.substring(0, colonIdx).trim()
                        val value = token.substring(colonIdx + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            existingManagedFields[key] = value
                        }
                    }
                }
            } else {
                keptUserLines += line
            }
        }

        // Merge newly parsed non-blank fields over prior managed metadata so renewal/invoice
        // emails never erase IP/IPv6/Hostname/SSH saved from an earlier welcome email.
        parsed.mainIpv4?.takeIf { it.isNotBlank() }?.let { existingManagedFields["IP"] = it }
        parsed.mainIpv6?.takeIf { it.isNotBlank() }?.let { existingManagedFields["IPv6"] = it }
        parsed.hostname.takeIf { it.isNotBlank() }?.let { existingManagedFields["Hostname"] = it }
        if (parsed.hasSshCredentials || parsed.sshPort != ParsedVpsBillingMail.DEFAULT_SSH_PORT) {
            existingManagedFields["SSH"] = "${parsed.sshUsername}@${parsed.primaryHost ?: "-"}:${parsed.sshPort}"
        }
        parsed.productName.takeIf { it.isNotBlank() }?.let { existingManagedFields["套餐"] = it }
        parsed.invoiceNumber?.takeIf { it.isNotBlank() }?.let { existingManagedFields["账单"] = "#$it" }

        val orderedKeys = listOf("IP", "IPv6", "Hostname", "SSH", "套餐", "账单")
        val managedTokens = buildList {
            for (key in orderedKeys) {
                existingManagedFields[key]?.let { value -> add("$key: $value") }
            }
            for ((key, value) in existingManagedFields) {
                if (key !in orderedKeys) add("$key: $value")
            }
        }

        val managedLine = if (managedTokens.isNotEmpty()) {
            "$MANAGED_NOTE_PREFIX ${managedTokens.joinToString(" | ")}"
        } else {
            ""
        }

        return listOfNotNull(
            managedLine.takeIf { it.isNotEmpty() },
            keptUserLines.joinToString("\n").takeIf { it.isNotEmpty() },
        ).joinToString("\n")
    }

    private fun buildSuggestedNickname(parsed: ParsedVpsBillingMail): String {
        val provider = parsed.providerName.trim()
        val product = parsed.productName.trim()
        val host = parsed.hostname.trim()
        val ip = parsed.mainIpv4 ?: parsed.mainIpv6.orEmpty()

        return when {
            provider.isNotEmpty() && product.isNotEmpty() -> {
                if (product.contains(provider, ignoreCase = true)) product else "$provider $product"
            }
            product.isNotEmpty() -> product
            provider.isNotEmpty() && host.isNotEmpty() -> "$provider ($host)"
            host.isNotEmpty() -> host
            provider.isNotEmpty() && ip.isNotEmpty() -> "$provider $ip"
            provider.isNotEmpty() -> "$provider VPS"
            ip.isNotEmpty() -> "VPS $ip"
            else -> "新购 VPS"
        }
    }

    private fun buildSuggestedSshAlias(
        parsed: ParsedVpsBillingMail,
        fallbackNickname: String,
    ): String {
        return fallbackNickname.ifBlank {
            parsed.hostname.ifBlank { parsed.mainIpv4 ?: parsed.mainIpv6 ?: "VPS" }
        }
    }

    private fun mapRegionToSuggestedSshGroup(region: String): String {
        return when (region.trim()) {
            "香港" -> "香港"
            "日本", "东京", "大阪" -> "日本"
            "新加坡" -> "新加坡"
            "美西", "洛杉矶", "圣何塞", "西雅图", "美国", "纽约" -> "美西"
            "德国", "法兰克福", "荷兰", "英国" -> "欧洲"
            else -> ""
        }
    }

    private fun buildIngestFeedbackMessage(
        asset: VpsAssetRecord,
        assetCreated: Boolean,
        sshProfile: SshServerProfile?,
        sshCreated: Boolean,
        sshPreservedExisting: Boolean = false,
    ): String {
        return when {
            sshPreservedExisting ->
                "已同步账本：「${asset.nickname}」（保留现有 SSH 认证配置）"
            sshProfile != null && assetCreated && sshCreated ->
                "已入库账本与 SSH：「${asset.nickname}」(${sshProfile.displayAddress})"
            sshProfile != null && !assetCreated && !sshCreated ->
                "已更新账本与 SSH 配置：「${asset.nickname}」（无重复记录）"
            sshProfile != null ->
                "已同步账本与 SSH 配置：「${asset.nickname}」(${sshProfile.displayAddress})"
            assetCreated ->
                "已入库服务器账本：「${asset.nickname}」"
            else ->
                "已更新服务器账本：「${asset.nickname}」（到期日 ${asset.expiryDateIso}）"
        }
    }

    private fun parseMailReceivedDate(summary: SeekMailSummary?): LocalDate? {
        val raw = summary?.receivedAt?.ifBlank { summary.createdAt }?.trim().orEmpty()
        if (raw.isEmpty()) return null
        runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(VALUATION_ZONE_ID).toLocalDate() }
            .getOrNull()
            ?.let { return it }
        runCatching { Instant.parse(raw).atZone(VALUATION_ZONE_ID).toLocalDate() }
            .getOrNull()
            ?.let { return it }
        return VpsBillingMailParser.parseFlexibleDateIso(raw)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
    }

    // ── Deduplication Key Generation & Disk Persistence ──────────────────────

    private fun primaryMailKey(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
    ): String {
        val msgId = summary?.messageId?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (msgId.isNotEmpty()) return "msgid:$msgId"
        if (summary != null && summary.id > 0L) {
            return "mail:${summary.mailboxId}:${summary.id}"
        }
        return contentFingerprintKey(summary, parsed)
    }

    private fun buildMailLookupKeys(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
    ): List<String> = buildList {
        val msgId = summary?.messageId?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (msgId.isNotEmpty()) add("msgid:$msgId")
        if (summary != null && summary.id > 0L) {
            add("mail:${summary.mailboxId}:${summary.id}")
            add("mailid:${summary.id}")
        }
        if (isEmpty()) {
            add(contentFingerprintKey(summary, parsed))
        }
    }

    private fun buildAllDedupeKeysForAsset(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
        assetId: String,
    ): List<String> = buildList {
        addAll(buildMailLookupKeys(summary, parsed))
        val inv = parsed.invoiceNumber?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (inv.isNotEmpty()) {
            val prov = normalizeIdentity(parsed.providerName).ifEmpty { "any" }
            add("inv:$prov:$inv:$assetId")
        }
    }

    private fun contentFingerprintKey(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
    ): String {
        val payload = listOf(
            summary?.fromAddress?.trim()?.lowercase(Locale.ROOT).orEmpty(),
            summary?.subject?.trim().orEmpty(),
            summary?.receivedAt?.trim().orEmpty(),
            parsed.providerName.lowercase(Locale.ROOT),
            parsed.invoiceNumber.orEmpty(),
            parsed.mainIpv4.orEmpty(),
            parsed.nextDueDateIso.orEmpty(),
            parsed.billingAmount.orEmpty(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.take(12).joinToString("") { b -> String.format(Locale.US, "%02x", b.toInt() and 0xFF) }
        return "fp:$hex"
    }

    private fun recordAutoUpdateLocked(
        record: RenewalAutoUpdateRecord,
        lookupKeys: List<String>,
        persistImmediately: Boolean = true,
    ) {
        storedRecords.removeAll { it.mailDedupeKey == record.mailDedupeKey && it.assetId == record.assetId }
        storedRecords.add(0, record)
        if (storedRecords.size > MAX_STORED_RECORDS) {
            storedRecords = storedRecords.take(MAX_STORED_RECORDS).toMutableList()
        }
        rebuildAutoUpdatesMapLocked(extraAliases = lookupKeys to record)
        if (persistImmediately) {
            persistStateLocked()
        }
    }

    private fun rebuildAutoUpdatesMapLocked(
        extraAliases: Pair<List<String>, RenewalAutoUpdateRecord>? = null,
    ) {
        val map = LinkedHashMap<String, RenewalAutoUpdateRecord>()
        // Oldest first so newest wins if multiple updates ever shared an alias
        for (rec in storedRecords.asReversed()) {
            map[rec.mailDedupeKey] = rec
            if (rec.mailId > 0L) {
                map["mailid:${rec.mailId}"] = rec
            }
        }
        if (extraAliases != null) {
            for (k in extraAliases.first) {
                map[k] = extraAliases.second
            }
        }
        _autoUpdatesByMailKeyFlow.value = map
    }

    private fun ensureDiskLoadedLocked() {
        if (diskLoaded) return
        diskLoaded = true
        if (!stateFile.isFile) return

        val loaded = runCatching {
            val raw = stateFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) StoredVpsMailIngestState()
            else json.decodeFromString(StoredVpsMailIngestState.serializer(), raw)
        }.getOrDefault(StoredVpsMailIngestState())

        processedRenewalKeys.addAll(loaded.processedRenewalKeys)
        storedRecords = loaded.records.take(MAX_STORED_RECORDS).toMutableList()
        rebuildAutoUpdatesMapLocked()
    }

    private fun persistStateLocked() {
        if (processedRenewalKeys.size > MAX_PROCESSED_KEYS) {
            val keep = processedRenewalKeys.toList().takeLast(MAX_PROCESSED_KEYS)
            processedRenewalKeys.clear()
            processedRenewalKeys.addAll(keep)
        }
        val payload = StoredVpsMailIngestState(
            processedRenewalKeys = processedRenewalKeys.toSet(),
            records = storedRecords.toList(),
        )
        runCatching {
            stateFile.parentFile?.mkdirs()
            val encoded = json.encodeToString(StoredVpsMailIngestState.serializer(), payload)
            val tempFile = File(stateFile.parentFile, "${stateFile.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                fos.write(encoded.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            if (!tempFile.renameTo(stateFile)) {
                FileOutputStream(stateFile).use { fos ->
                    fos.write(encoded.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    runCatching { fos.fd.sync() }
                }
                tempFile.delete()
            }
        }
    }

    companion object {
        const val STATE_FILE_NAME: String = "vps_mail_ingest_state.json"
        private const val MANAGED_NOTE_PREFIX: String = "[SeekMail]"
        private const val MAX_STORED_RECORDS: Int = 200
        private const val MAX_PROCESSED_KEYS: Int = 2_000
        private const val MAX_DETAIL_PEEKS_PER_BATCH: Int = 3

        fun advanceDateByCycle(
            baseDate: LocalDate,
            cycle: BillingCyclePreset,
            customCycleDays: Int? = null,
        ): LocalDate = when (cycle) {
            BillingCyclePreset.MONTHLY -> baseDate.plusMonths(1)
            BillingCyclePreset.QUARTERLY -> baseDate.plusMonths(3)
            BillingCyclePreset.SEMI_ANNUAL -> baseDate.plusMonths(6)
            BillingCyclePreset.ANNUAL,
            BillingCyclePreset.CALENDAR_NATURAL,
            -> baseDate.plusYears(1)
            BillingCyclePreset.BIENNIAL -> baseDate.plusYears(2)
            BillingCyclePreset.TRIENNIAL -> baseDate.plusYears(3)
            BillingCyclePreset.CUSTOM -> baseDate.plusDays((customCycleDays ?: 365).coerceAtLeast(1).toLong())
        }
    }
}

private val SshServerProfile.Companion.DEFAULT_SSH_USERNAME_FALLBACK: String
    get() = DEFAULT_USERNAME
