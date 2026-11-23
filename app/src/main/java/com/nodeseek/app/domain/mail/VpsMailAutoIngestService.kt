package com.nodeseek.app.domain.mail

import androidx.compose.runtime.Immutable
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

/**
 * Audit & UI feedback record produced when a `RENEWAL` email automatically advances an existing
 * [VpsAssetRecord]'s expiry date.
 */
@Immutable
@Serializable
data class RenewalAutoUpdateRecord(
    val mailDedupeKey: String,
    val mailId: Long = 0L,
    val assetId: String,
    val assetNickname: String,
    val providerName: String = "",
    val previousExpiryDateIso: String,
    val newExpiryDateIso: String,
    val usedExplicitMailDate: Boolean,
    val appliedCycleName: String? = null,
    val invoiceNumber: String? = null,
    val updatedAtEpochMillis: Long = 0L,
) {
    val appliedCycle: BillingCyclePreset?
        get() = appliedCycleName?.let { name ->
            BillingCyclePreset.entries.firstOrNull { it.name == name }
        }
}

/**
 * Live correlation between a parsed VPS email and existing records in [FleetRepository] and [SshVaultRepository].
 */
@Immutable
data class VpsMailMatchStatus(
    val matchedAssets: List<VpsAssetRecord> = emptyList(),
    val matchedSshProfile: SshServerProfile? = null,
    val renewalAutoUpdate: RenewalAutoUpdateRecord? = null,
) {
    val primaryMatchedAsset: VpsAssetRecord?
        get() = matchedAssets.firstOrNull()

    val isAssetMatched: Boolean
        get() = matchedAssets.isNotEmpty()

    val isSshMatched: Boolean
        get() = matchedSshProfile != null
}

/**
 * Outcome of a user-triggered one-click import from SeekMail into Fleet Ledger (+ SSH Vault when credentials exist).
 */
@Immutable
data class VpsMailIngestOutcome(
    val asset: VpsAssetRecord,
    val assetCreated: Boolean,
    val sshProfile: SshServerProfile? = null,
    val sshCreated: Boolean = false,
    val summaryMessage: String,
)

/**
 * Domain service coordinating:
 * 1. Idempotent `RENEWAL` mail expiry auto-updates against [com.nodeseek.app.domain.fleet.FleetRepository]
 *    across inbox refreshes, mail detail opens, and cold restarts.
 * 2. Duplicate-safe one-click ingestion into [com.nodeseek.app.domain.fleet.FleetRepository] and
 *    [com.nodeseek.app.domain.ssh.SshVaultRepository].
 */
interface VpsMailAutoIngestService {
    val assetsFlow: StateFlow<List<VpsAssetRecord>>
    val sshServersFlow: StateFlow<List<SshServerProfile>>
    val autoUpdatesByMailKeyFlow: StateFlow<Map<String, RenewalAutoUpdateRecord>>
    val renewalAutoUpdateEvents: Flow<RenewalAutoUpdateRecord>

    suspend fun ensureLoaded()

    suspend fun processIncomingSummaries(
        summaries: List<SeekMailSummary>,
    ): List<RenewalAutoUpdateRecord>

    suspend fun processMailDetail(
        detail: SeekMailDetail,
    ): List<RenewalAutoUpdateRecord>

    fun resolveMatchStatus(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
        assets: List<VpsAssetRecord> = assetsFlow.value,
        sshServers: List<SshServerProfile> = sshServersFlow.value,
        autoUpdatesByMailKey: Map<String, RenewalAutoUpdateRecord> = autoUpdatesByMailKeyFlow.value,
    ): VpsMailMatchStatus

    suspend fun ingestMail(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
        overwriteExistingSshCredentials: Boolean = true,
    ): Result<VpsMailIngestOutcome>
}

object NoOpVpsMailAutoIngestService : VpsMailAutoIngestService {
    private val emptyAssets = MutableStateFlow<List<VpsAssetRecord>>(emptyList()).asStateFlow()
    private val emptySsh = MutableStateFlow<List<SshServerProfile>>(emptyList()).asStateFlow()
    private val emptyUpdates = MutableStateFlow<Map<String, RenewalAutoUpdateRecord>>(emptyMap()).asStateFlow()

    override val assetsFlow: StateFlow<List<VpsAssetRecord>> = emptyAssets
    override val sshServersFlow: StateFlow<List<SshServerProfile>> = emptySsh
    override val autoUpdatesByMailKeyFlow: StateFlow<Map<String, RenewalAutoUpdateRecord>> = emptyUpdates
    override val renewalAutoUpdateEvents: Flow<RenewalAutoUpdateRecord> = emptyFlow()

    override suspend fun ensureLoaded() = Unit

    override suspend fun processIncomingSummaries(
        summaries: List<SeekMailSummary>,
    ): List<RenewalAutoUpdateRecord> = emptyList()

    override suspend fun processMailDetail(
        detail: SeekMailDetail,
    ): List<RenewalAutoUpdateRecord> = emptyList()

    override fun resolveMatchStatus(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
        assets: List<VpsAssetRecord>,
        sshServers: List<SshServerProfile>,
        autoUpdatesByMailKey: Map<String, RenewalAutoUpdateRecord>,
    ): VpsMailMatchStatus = VpsMailMatchStatus()

    override suspend fun ingestMail(
        summary: SeekMailSummary?,
        parsed: ParsedVpsBillingMail,
        overwriteExistingSshCredentials: Boolean,
    ): Result<VpsMailIngestOutcome> = Result.failure(
        UnsupportedOperationException("VPS 邮件入库服务未初始化"),
    )
}
