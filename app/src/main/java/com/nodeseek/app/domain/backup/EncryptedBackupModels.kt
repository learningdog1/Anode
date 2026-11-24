package com.nodeseek.app.domain.backup

import com.nodeseek.app.domain.activity.ActivityMonitorSettings
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.SeekMailLocalArchiveStore
import com.nodeseek.app.domain.mail.SeekMailLocalTagStore
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.overseas.OverseasOfferFilterConfig
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorSettings
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshVaultCollapseState
import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Structured error categories for encrypted full backup v2 operations.
 */
enum class EncryptedBackupErrorKind {
    WEAK_PASSPHRASE,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    WRONG_PASSPHRASE_OR_TAMPERED,
    IO_ERROR,
}

/**
 * Domain exception raised when encrypting, parsing, verifying, or decrypting a v2 backup envelope.
 */
class EncryptedBackupException(
    val kind: EncryptedBackupErrorKind,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Cleartext header embedded in an encrypted backup v2 envelope.
 *
 * Every field in [EncryptedBackupEnvelope] and [EncryptedBackupHeader] is cryptographically bound
 * to the AES-256-GCM authentication tag via Associated Authenticated Data (AAD), so tampering with
 * any header parameter is detected during decryption.
 */
@Serializable
data class EncryptedBackupHeader(
    val kdf: String = KDF_PBKDF2_HMAC_SHA256,
    val iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    val keyLengthBits: Int = KEY_LENGTH_BITS,
    val saltBase64: String,
    val cipher: String = CIPHER_AES_256_GCM,
    val nonceBase64: String,
    val tagLengthBits: Int = GCM_TAG_LENGTH_BITS,
) {
    companion object {
        const val KDF_PBKDF2_HMAC_SHA256: String = "PBKDF2-HMAC-SHA256"
        const val CIPHER_AES_256_GCM: String = "AES-256-GCM"
        const val DEFAULT_PBKDF2_ITERATIONS: Int = 310_000
        const val MIN_PBKDF2_ITERATIONS: Int = 310_000
        const val MAX_PBKDF2_ITERATIONS: Int = 2_000_000
        const val KEY_LENGTH_BITS: Int = 256
        const val GCM_TAG_LENGTH_BITS: Int = 128
        const val SALT_BYTES: Int = 32
        const val NONCE_BYTES: Int = 12
        const val MIN_PASSPHRASE_LENGTH: Int = 8
    }
}

/**
 * Top-level file format written to disk for Encrypted Full Backup v2.
 */
@Serializable
data class EncryptedBackupEnvelope(
    val format: String = FORMAT_MAGIC,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val header: EncryptedBackupHeader,
    val ciphertextBase64: String,
) {
    companion object {
        const val FORMAT_MAGIC: String = "ANODE_ENCRYPTED_BACKUP"
        const val CURRENT_FORMAT_VERSION: Int = 2
    }
}

/**
 * Full-app backup payload (schema v2) serialized strictly inside an AES-256-GCM encrypted
 * [EncryptedBackupEnvelope]. Never exported in plaintext.
 */
@Serializable
data class NodePulseFullBackupBundle(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String,
    val exportedAtEpochMillis: Long,
    // ── Core v1 categories (always includes plaintext API keys inside encrypted v2) ──
    val settings: AppSettingsBackupDto = AppSettingsBackupDto(),
    val radar: RadarBackupDto = RadarBackupDto(),
    val blocklist: BlocklistBackupDto = BlocklistBackupDto(),
    val bookmarks: List<BookmarkedTopic> = emptyList(),
    val drafts: List<EditorDraft> = emptyList(),
    // ── Extended v2 full-app user data ──
    val fleet: FleetBackupDto = FleetBackupDto(),
    val restock: RestockBackupDto = RestockBackupDto(),
    val sshVault: SshVaultBackupDto = SshVaultBackupDto(),
    val seekMail: SeekMailBackupDto = SeekMailBackupDto(),
    val overseasIntel: OverseasIntelBackupDto = OverseasIntelBackupDto(),
    val activity: ActivityBackupDto = ActivityBackupDto(),
    val trust: TrustBackupDto = TrustBackupDto(),
    val netBench: NetBenchBackupDto = NetBenchBackupDto(),
    val valuation: ValuationBackupDto = ValuationBackupDto(),
) {
    fun toCoreV1Bundle(): NodePulseBackupBundle = NodePulseBackupBundle(
        schemaVersion = NodePulseBackupBundle.CURRENT_SCHEMA_VERSION,
        appVersion = appVersion,
        exportedAtEpochMillis = exportedAtEpochMillis,
        settings = settings,
        radar = radar,
        blocklist = blocklist,
        bookmarks = bookmarks,
        drafts = drafts,
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}

@Serializable
data class FleetBackupDto(
    val panels: List<ProbePanelBackupDto> = emptyList(),
    val assets: List<VpsAssetBackupDto> = emptyList(),
    val monitorSettings: FleetMonitorSettingsBackupDto = FleetMonitorSettingsBackupDto(),
)

@Serializable
data class ProbePanelBackupDto(
    val id: String,
    val name: String,
    val baseUrl: String,
    val panelType: String = ProbePanelType.AUTO.name,
    val resolvedPanelType: String? = null,
    val apiToken: String? = null,
    val notifyOnOffline: Boolean = true,
    val notifyOnRecovery: Boolean = false,
    val enabled: Boolean = true,
    val createdAtEpochMillis: Long = 0L,
) {
    fun toDomain(): ProbePanelConfig = ProbePanelConfig(
        id = id.trim(),
        name = name.trim(),
        baseUrl = baseUrl.trim(),
        panelType = runCatching { ProbePanelType.valueOf(panelType.trim().uppercase()) }
            .getOrDefault(ProbePanelType.AUTO),
        resolvedPanelType = resolvedPanelType?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { ProbePanelType.valueOf(raw.uppercase()) }.getOrNull()
        },
        apiToken = apiToken?.trim()?.takeIf { it.isNotEmpty() },
        notifyOnOffline = notifyOnOffline,
        notifyOnRecovery = notifyOnRecovery,
        enabled = enabled,
        createdAtEpochMillis = createdAtEpochMillis.coerceAtLeast(0L),
    )
}

fun ProbePanelConfig.toBackupDto(): ProbePanelBackupDto = ProbePanelBackupDto(
    id = id,
    name = name,
    baseUrl = baseUrl,
    panelType = panelType.name,
    resolvedPanelType = resolvedPanelType?.name,
    apiToken = apiToken?.trim()?.takeIf { it.isNotEmpty() },
    notifyOnOffline = notifyOnOffline,
    notifyOnRecovery = notifyOnRecovery,
    enabled = enabled,
    createdAtEpochMillis = createdAtEpochMillis,
)

@Serializable
data class VpsAssetBackupDto(
    val id: String,
    val nickname: String,
    val providerName: String = "",
    val specsSummary: String = "",
    val region: String = "",
    val routeTag: String = "",
    val tags: List<String> = emptyList(),
    val renewalAmount: String = "",
    val currency: String = ValuationCurrency.USD.name,
    val cyclePreset: String = BillingCyclePreset.ANNUAL.name,
    val customCycleDays: Int? = null,
    val expiryDateIso: String = "",
    val purchaseUrl: String = "",
    val panelManageUrl: String = "",
    val linkedProbeKey: String? = null,
    val monthlyTrafficQuotaGb: Double? = null,
    val trafficResetDay: Int? = null,
    val remindDaysBeforeExpiry: Int = VpsAssetRecord.DEFAULT_REMIND_DAYS_BEFORE_EXPIRY,
    val autoRenew: Boolean = false,
    val autoRenewNote: String = "",
    val notes: String = "",
    val billingAnchorDay: Int? = null,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
) {
    fun toDomain(): VpsAssetRecord {
        val resolvedTags = if (tags.isNotEmpty()) {
            VpsAssetRecord.normalizeTags(tags)
        } else {
            VpsAssetRecord.parseLegacyTags(routeTag)
        }
        return VpsAssetRecord(
            id = id.trim(),
            nickname = nickname.trim(),
            providerName = providerName.trim(),
            specsSummary = specsSummary.trim(),
            region = region.trim(),
            routeTag = VpsAssetRecord.formatRouteTag(resolvedTags),
            renewalAmount = renewalAmount.trim(),
            currency = runCatching { ValuationCurrency.valueOf(currency.trim().uppercase()) }
                .getOrDefault(ValuationCurrency.USD),
            cyclePreset = runCatching { BillingCyclePreset.valueOf(cyclePreset.trim().uppercase()) }
                .getOrDefault(BillingCyclePreset.ANNUAL),
            customCycleDays = customCycleDays?.takeIf { it > 0 },
            expiryDateIso = expiryDateIso.trim(),
            purchaseUrl = purchaseUrl.trim(),
            panelManageUrl = panelManageUrl.trim(),
            linkedProbeKey = linkedProbeKey?.trim()?.takeIf { it.isNotEmpty() },
            monthlyTrafficQuotaGb = monthlyTrafficQuotaGb?.takeIf { it.isFinite() && it > 0.0 },
            trafficResetDay = trafficResetDay?.takeIf { it in 1..31 },
            remindDaysBeforeExpiry = remindDaysBeforeExpiry.coerceIn(0, 365),
            autoRenewNote = autoRenewNote.trim(),
            notes = notes,
            createdAtEpochMillis = createdAtEpochMillis.coerceAtLeast(0L),
            updatedAtEpochMillis = updatedAtEpochMillis.coerceAtLeast(0L),
            tags = resolvedTags,
            autoRenew = autoRenew,
            billingAnchorDay = billingAnchorDay?.takeIf { it in 1..31 },
        )
    }
}

fun VpsAssetRecord.toBackupDto(): VpsAssetBackupDto {
    val normalizedTags = resolvedTags
    return VpsAssetBackupDto(
        id = id,
        nickname = nickname,
        providerName = providerName,
        specsSummary = specsSummary,
        region = region,
        routeTag = VpsAssetRecord.formatRouteTag(normalizedTags),
        tags = normalizedTags,
        renewalAmount = renewalAmount,
        currency = currency.name,
        cyclePreset = cyclePreset.name,
        customCycleDays = customCycleDays,
        expiryDateIso = expiryDateIso,
        purchaseUrl = purchaseUrl,
        panelManageUrl = panelManageUrl,
        linkedProbeKey = linkedProbeKey,
        monthlyTrafficQuotaGb = monthlyTrafficQuotaGb,
        trafficResetDay = trafficResetDay,
        remindDaysBeforeExpiry = remindDaysBeforeExpiry,
        autoRenew = autoRenew,
        autoRenewNote = autoRenewNote,
        notes = notes,
        billingAnchorDay = billingAnchorDay,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

@Serializable
data class FleetMonitorSettingsBackupDto(
    val backgroundMonitorEnabled: Boolean = true,
    val pollIntervalMinutes: Int = 15,
    val wifiOnlyInBackground: Boolean = false,
    val requireBatteryNotLowInBackground: Boolean = false,
    val notifyOnRecovery: Boolean = false,
) {
    fun toDomain(): FleetMonitorSettings = FleetMonitorSettings(
        backgroundMonitorEnabled = backgroundMonitorEnabled,
        pollIntervalMinutes = pollIntervalMinutes.coerceIn(15, 360),
        wifiOnlyInBackground = wifiOnlyInBackground,
        requireBatteryNotLowInBackground = requireBatteryNotLowInBackground,
        notifyOnRecovery = notifyOnRecovery,
    )
}

fun FleetMonitorSettings.toBackupDto(): FleetMonitorSettingsBackupDto = FleetMonitorSettingsBackupDto(
    backgroundMonitorEnabled = backgroundMonitorEnabled,
    pollIntervalMinutes = pollIntervalMinutes,
    wifiOnlyInBackground = wifiOnlyInBackground,
    requireBatteryNotLowInBackground = requireBatteryNotLowInBackground,
    notifyOnRecovery = notifyOnRecovery,
)

@Serializable
data class RestockBackupDto(
    val watchItems: List<RestockWatchItem> = emptyList(),
    val settings: RestockMonitorSettings = RestockMonitorSettings(),
)

@Serializable
data class SshVaultBackupDto(
    val servers: List<SshServerBackupDto> = emptyList(),
    val customPresets: List<CustomSshPreset> = emptyList(),
    val collapseState: SshVaultCollapseState = SshVaultCollapseState(),
)

/**
 * Portable SSH host entry stored inside an encrypted v2 backup bundle.
 *
 * [secret] (password or PEM private key) and optional [passphrase] are stored in plaintext ONLY
 * inside the AES-256-GCM encrypted backup payload, then re-encrypted using the target device's
 * Keystore cipher on import.
 */
@Serializable
data class SshServerBackupDto(
    val id: String,
    val alias: String,
    val host: String,
    val port: Int = 22,
    val username: String = "root",
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val secret: String = "",
    val passphrase: String? = null,
    val knownHostKeyFingerprint: String? = null,
    val knownHostKeyAlgorithm: String? = null,
    val lastConnectedEpochMillis: Long? = null,
    val createdAtEpochMillis: Long = 0L,
    val jumpHostId: String? = null,
    val groupTag: String = "",
    val probePanelId: String? = null,
    val probePanelName: String? = null,
    val probePanelType: String? = null,
    val probeServerId: String? = null,
    val probeRegion: String = "",
    val probeTags: List<String> = emptyList(),
    val probeSyncedAtEpochMillis: Long? = null,
    val probeReportedHost: String = "",
    val probeReportedName: String = "",
    val connectionMode: SshConnectionMode = SshConnectionMode.DIRECT_SSH,
)

@Serializable
data class SeekMailBackupDto(
    val localTagStore: SeekMailLocalTagStore = SeekMailLocalTagStore(),
    val localArchiveStore: SeekMailLocalArchiveStore = SeekMailLocalArchiveStore(),
    val locallyReadMailIds: Set<Long> = emptySet(),
)

@Serializable
data class OverseasOfferFilterConfigBackupDto(
    val enabled: Boolean = true,
    val excludeGenericMarketingBuzz: Boolean = true,
    val excludeNonServerServices: Boolean = true,
    val requireMinimumHostingSignal: Boolean = true,
    val blockedMerchants: Set<String> = emptySet(),
    val extraBlockedKeywords: Set<String> = emptySet(),
) {
    fun toDomain(): OverseasOfferFilterConfig = OverseasOfferFilterConfig(
        enabled = enabled,
        excludeGenericMarketingBuzz = excludeGenericMarketingBuzz,
        excludeNonServerServices = excludeNonServerServices,
        requireMinimumHostingSignal = requireMinimumHostingSignal,
        blockedMerchants = blockedMerchants.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        extraBlockedKeywords = extraBlockedKeywords.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    )
}

fun OverseasOfferFilterConfig.toBackupDto(): OverseasOfferFilterConfigBackupDto =
    OverseasOfferFilterConfigBackupDto(
        enabled = enabled,
        excludeGenericMarketingBuzz = excludeGenericMarketingBuzz,
        excludeNonServerServices = excludeNonServerServices,
        requireMinimumHostingSignal = requireMinimumHostingSignal,
        blockedMerchants = blockedMerchants,
        extraBlockedKeywords = extraBlockedKeywords,
    )

@Serializable
data class OverseasIntelBackupDto(
    val filterConfig: OverseasOfferFilterConfigBackupDto = OverseasOfferFilterConfigBackupDto(),
    val promoMonitorSettings: OverseasPromoMonitorSettings = OverseasPromoMonitorSettings(),
)

@Serializable
data class ActivityBackupDto(
    val monitorSettings: ActivityMonitorSettings = ActivityMonitorSettings(),
)

@Serializable
data class TrustBackupDto(
    val sellerNotes: List<LocalSellerNote> = emptyList(),
    val officialTradeApiKey: String = "",
)

@Serializable
data class NetBenchBackupDto(
    val maskTargetIp: Boolean = true,
    val includeClientNetwork: Boolean = true,
    val markdownStyle: NetBenchMarkdownStyle = NetBenchMarkdownStyle.FULL_TABLE,
    val includeReportImages: Boolean = false,
)

@Serializable
data class ValuationBackupDto(
    val manualFxOverridesToCny: Map<String, String> = emptyMap(),
)

data class EncryptedBackupExportPayload(
    val bundle: NodePulseFullBackupBundle,
    val envelope: EncryptedBackupEnvelope,
    val rawEnvelopeJson: String,
)

data class EncryptedBackupPreviewSummary(
    val formatVersion: Int,
    val schemaVersion: Int,
    val appVersion: String,
    val exportedAtEpochMillis: Long,
    val kdfAlgorithm: String,
    val kdfIterations: Int,
    val cipherAlgorithm: String,
    // Core v1 counts
    val radarKeywordRuleCount: Int,
    val radarTrackedTopicCount: Int,
    val blockedUserCount: Int,
    val contentFilterRuleCount: Int,
    val mutedCategoryCount: Int,
    val bookmarkCount: Int,
    val draftCount: Int,
    val recentSearchQueryCount: Int,
    val containsApiKeys: Boolean,
    // Extended v2 counts
    val fleetPanelCount: Int,
    val fleetAssetCount: Int,
    val restockWatchCount: Int,
    val sshServerCount: Int,
    val sshCustomPresetCount: Int,
    val seekMailTagCount: Int,
    val seekMailArchivedCount: Int,
    val sellerNoteCount: Int,
    val hasOfficialTradeApiKey: Boolean,
    val overseasBlockedMerchantCount: Int,
    val overseasBlockedKeywordCount: Int,
    val overseasNotificationsEnabled: Boolean,
    val activityNotificationsEnabled: Boolean,
    val manualFxOverrideCount: Int,
    val targetSyncAccount: BackupSyncTargetAccount? = null,
)

data class EncryptedBackupRestoreSummary(
    val strategy: RestoreStrategy,
    // Core v1 counts
    val radarKeywordRulesImported: Int,
    val radarTrackedTopicsImported: Int,
    val blockedUsersImported: Int,
    val pendingRemoteBlockUsers: List<FloorAuthor> = emptyList(),
    val targetSyncAccount: BackupSyncTargetAccount? = null,
    val contentFilterRulesImported: Int,
    val mutedCategoriesCount: Int,
    val bookmarksImported: Int,
    val draftsImported: Int,
    val recentSearchQueriesCount: Int,
    // Extended v2 counts
    val fleetPanelsImported: Int,
    val fleetAssetsImported: Int,
    val restockWatchesImported: Int,
    val sshServersImported: Int,
    val sshCustomPresetsImported: Int,
    val seekMailTagsImported: Int,
    val seekMailArchivedImported: Int,
    val sellerNotesImported: Int,
    val officialTradeApiKeyImported: Boolean,
    val manualFxOverridesImported: Int,
) {
    val hasPendingRemoteBlockSync: Boolean
        get() = pendingRemoteBlockUsers.isNotEmpty()
}

object EncryptedBackupFileNaming {
    private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    fun createDefaultFileName(
        epochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val stamp = FILE_TIMESTAMP_FORMATTER.format(
            Instant.ofEpochMilli(epochMillis).atZone(zoneId),
        )
        return "anode-full-backup-$stamp.anodebackup"
    }
}
