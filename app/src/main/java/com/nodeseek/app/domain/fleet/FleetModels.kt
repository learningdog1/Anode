package com.nodeseek.app.domain.fleet

import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.FeePayer
import com.nodeseek.app.domain.valuation.ValuationCurrency
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * Supported server monitoring probe panel protocols.
 */
enum class ProbePanelType(
    val displayName: String,
    val shortLabel: String,
) {
    AUTO("自动识别", "自动"),
    NEZHA_V0("哪吒探针 v0", "Nezha v0"),
    NEZHA_V1("哪吒探针 v1", "Nezha v1"),
    KOMARI("Komari 探针", "Komari"),
}

/**
 * Configuration for a user-added probe panel (Nezha v0, Nezha v1, Komari, or Auto-discovery).
 */
data class ProbePanelConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val panelType: ProbePanelType = ProbePanelType.AUTO,
    val resolvedPanelType: ProbePanelType? = null,
    val apiToken: String? = null,
    val notifyOnOffline: Boolean = true,
    val notifyOnRecovery: Boolean = false,
    val enabled: Boolean = true,
    val createdAtEpochMillis: Long = 0L,
    val lastSyncedEpochMillis: Long = 0L,
    val lastError: String? = null,
    val tokenDecryptionFailed: Boolean = false,
) {
    /**
     * Effective panel protocol to use when polling: respects explicit choice, otherwise uses
     * the discovered protocol from the last successful connection test/sync.
     */
    val effectivePanelType: ProbePanelType
        get() = when {
            panelType != ProbePanelType.AUTO -> panelType
            resolvedPanelType != null && resolvedPanelType != ProbePanelType.AUTO -> resolvedPanelType
            else -> ProbePanelType.AUTO
        }
}

/**
 * Live telemetry snapshot of a single remote server reported by a probe panel.
 */
data class ProbeServerSnapshot(
    val panelId: String,
    val remoteServerId: String,
    val name: String,
    val countryCode: String = "",
    val flagEmoji: String = "",
    val os: String = "",
    val cpuModel: String = "",
    val cpuCores: Int = 0,
    val cpuPercent: Double = 0.0,
    val memUsedBytes: Long = 0L,
    val memTotalBytes: Long = 0L,
    val diskUsedBytes: Long = 0L,
    val diskTotalBytes: Long = 0L,
    val netInSpeedBps: Long = 0L,
    val netOutSpeedBps: Long = 0L,
    val monthlyTransferInBytes: Long = 0L,
    val monthlyTransferOutBytes: Long = 0L,
    val trafficLimitBytes: Long? = null,
    val uptimeSeconds: Long = 0L,
    val load1: Double? = null,
    val isOnline: Boolean = false,
    val lastActiveEpochMillis: Long = 0L,
) {
    val probeKey: String
        get() = makeProbeKey(panelId, remoteServerId)

    val resolvedFlagEmoji: String
        get() = flagEmoji.ifBlank { countryCodeToFlagEmoji(countryCode) }

    val memUsageRatio: Float
        get() = if (memTotalBytes > 0L) {
            (memUsedBytes.toDouble() / memTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val diskUsageRatio: Float
        get() = if (diskTotalBytes > 0L) {
            (diskUsedBytes.toDouble() / diskTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val totalMonthlyTransferBytes: Long
        get() = (monthlyTransferInBytes.coerceAtLeast(0L) + monthlyTransferOutBytes.coerceAtLeast(0L))

    val trafficUsageRatio: Float?
        get() {
            val limit = trafficLimitBytes?.takeIf { it > 0L } ?: return null
            return (totalMonthlyTransferBytes.toDouble() / limit.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    companion object {
        fun makeProbeKey(panelId: String, remoteServerId: String): String =
            "${panelId.trim()}:${remoteServerId.trim()}"
    }
}

/**
 * Connectivity test & protocol auto-discovery result for a [ProbePanelConfig].
 */
data class ProbeConnectivityResult(
    val success: Boolean,
    val detectedPanelType: ProbePanelType? = null,
    val serverCount: Int = 0,
    val onlineCount: Int = 0,
    val sampleServers: List<ProbeServerSnapshot> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Result of refreshing all configured probe panels.
 */
data class FleetRefreshSummary(
    val refreshedPanelCount: Int,
    val succeededPanelCount: Int,
    val failedPanelErrors: Map<String, String>,
    val snapshots: List<ProbeServerSnapshot>,
    val syncedAtEpochMillis: Long,
)

/**
 * Persistent VPS asset ledger record tracking specs, billing cycle, expiry, probe binding, and notes.
 */
data class VpsAssetRecord(
    val id: String,
    val nickname: String,
    val providerName: String,
    val specsSummary: String,
    val region: String,
    val routeTag: String = "",
    val renewalAmount: String,
    val currency: ValuationCurrency = ValuationCurrency.USD,
    val cyclePreset: BillingCyclePreset = BillingCyclePreset.ANNUAL,
    val customCycleDays: Int? = null,
    val expiryDateIso: String,
    val purchaseUrl: String = "",
    val panelManageUrl: String = "",
    val linkedProbeKey: String? = null,
    val monthlyTrafficQuotaGb: Double? = null,
    val trafficResetDay: Int? = null,
    val remindDaysBeforeExpiry: Int = DEFAULT_REMIND_DAYS_BEFORE_EXPIRY,
    val autoRenewNote: String = "",
    val notes: String = "",
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val tags: List<String> = emptyList(),
    val autoRenew: Boolean = false,
    val billingAnchorDay: Int? = null,
) {
    val parsedRenewalAmount: BigDecimal?
        get() = renewalAmount.trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }

    /**
     * Canonical deduplicated list of asset tags.
     *
     * Prefers explicit [tags] when populated; otherwise migrates legacy single-string [routeTag]
     * values into a normalized tag list.
     */
    val resolvedTags: List<String>
        get() = if (tags.isNotEmpty()) {
            normalizeTags(tags)
        } else {
            parseLegacyTags(routeTag)
        }

    /**
     * Compact single-line summary of [resolvedTags] for legacy single-string consumers.
     */
    val resolvedRouteTagSummary: String
        get() = formatRouteTag(resolvedTags)

    companion object {
        const val DEFAULT_REMIND_DAYS_BEFORE_EXPIRY = 7
        const val ROUTE_TAG_DISPLAY_SEPARATOR = " · "
        private const val MAX_TAG_LENGTH = 32
        private val CONTROL_WHITESPACE_REGEX = Regex("""[\r\n\t]+""")
        private val LEGACY_TAG_SPLIT_REGEX = Regex("""[,，、/|·;；]+""")

        val DEFAULT_TAG_PRESETS: List<String> = listOf(
            "CN2 GIA",
            "9929",
            "CMIN2",
            "BGP",
            "优化直连",
            "落地",
        )

        /**
         * Normalizes a single user-entered tag token, preserving internal spaces (e.g. `"CN2 GIA"`).
         */
        fun normalizeTag(raw: String): String =
            raw.replace(CONTROL_WHITESPACE_REGEX, " ")
                .trim()
                .take(MAX_TAG_LENGTH)
                .trim()

        /**
         * Normalizes and deduplicates a tag collection case-insensitively while preserving order.
         */
        fun normalizeTags(rawTags: Iterable<String>): List<String> {
            val seen = LinkedHashSet<String>()
            val result = ArrayList<String>()
            for (raw in rawTags) {
                val clean = normalizeTag(raw)
                if (clean.isEmpty()) continue
                if (seen.add(clean.lowercase(Locale.ROOT))) {
                    result.add(clean)
                }
            }
            return result
        }

        /**
         * Migrates a legacy single-string `routeTag` (e.g. `"CN2 GIA"` or `"CN2 GIA / 9929"`)
         * into a normalized multi-tag list without splitting internal spaces.
         */
        fun parseLegacyTags(rawRouteTag: String?): List<String> {
            val trimmed = rawRouteTag?.trim().orEmpty()
            if (trimmed.isEmpty()) return emptyList()
            return normalizeTags(trimmed.split(LEGACY_TAG_SPLIT_REGEX))
        }

        /**
         * Parses user input from the custom tag entry field (splits only on newlines so tags like
         * `"CN2 GIA"` or `"IPv4/IPv6"` stay intact).
         */
        fun parseCustomTagInput(rawInput: String): List<String> {
            if (rawInput.isBlank()) return emptyList()
            return normalizeTags(rawInput.split('\n', '\r'))
        }

        /**
         * Merges [incoming] tags into [existing], deduplicating case-insensitively while preserving
         * the existing order and casing.
         */
        fun mergeTags(existing: List<String>, incoming: Iterable<String>): List<String> =
            normalizeTags(existing + incoming)

        /**
         * Collects all distinct tags currently used across [assets], preserving first-seen order.
         */
        fun collectDistinctTags(assets: Iterable<VpsAssetRecord>): List<String> =
            normalizeTags(assets.flatMap { it.resolvedTags })

        /**
         * Formats [tags] into a single-string representation compatible with legacy `routeTag`.
         */
        fun formatRouteTag(tags: Iterable<String>): String =
            normalizeTags(tags).joinToString(ROUTE_TAG_DISPLAY_SEPARATOR)
    }
}

/**
 * Outcome of confirming a renewal or auto-rolling an expired auto-renew [VpsAssetRecord].
 */
data class AssetRenewalResult(
    val updatedAsset: VpsAssetRecord,
    val previousExpiryDateIso: String,
    val newExpiryDateIso: String,
    val cyclesAdvanced: Int,
    val isAutoRolled: Boolean = false,
)

/**
 * Semantic urgency classification for VPS expiry countdown badges.
 */
enum class AssetExpiryUrgency(val label: String) {
    EXPIRED("已到期"),
    CRITICAL("即将到期"),
    WARNING("近期续费"),
    NORMAL("正常"),
    UNKNOWN("日期待补"),
}

/**
 * Single asset entry in the sorted upcoming renewals list.
 */
data class UpcomingRenewalItem(
    val asset: VpsAssetRecord,
    val daysRemaining: Long,
    val urgency: AssetExpiryUrgency,
    val renewalCny: BigDecimal,
    val monthlyCostCny: BigDecimal,
    val annualizedCostCny: BigDecimal,
    val isWithinReminderWindow: Boolean,
    val nextExpiryAfterRenewalIso: String? = null,
    val cyclesToAdvanceOnRenewal: Int = 1,
) {
    /**
     * Whether this asset should surface an in-app renewal-due reminder / prompt.
     */
    val isRenewalDue: Boolean
        get() = isWithinReminderWindow || urgency == AssetExpiryUrgency.EXPIRED
}

/**
 * Portfolio-level KPI summary across all registered VPS assets and synced probe nodes.
 */
data class FleetPortfolioSummary(
    val totalMachineCount: Int = 0,
    val totalProbeNodeCount: Int = 0,
    val onlineProbeCount: Int = 0,
    val offlineProbeCount: Int = 0,
    val totalMonthlyCostCny: BigDecimal = BigDecimal.ZERO.setScale(2),
    val totalAnnualizedCostCny: BigDecimal = BigDecimal.ZERO.setScale(2),
    val upcomingRenewals: List<UpcomingRenewalItem> = emptyList(),
    val dueSoonRenewalCount: Int = 0,
    val dueRenewals: List<UpcomingRenewalItem> = emptyList(),
    val expiredRenewalCount: Int = 0,
)

/**
 * Background monitor & alert preferences for My Servers.
 */
data class FleetMonitorSettings(
    val backgroundMonitorEnabled: Boolean = true,
    val pollIntervalMinutes: Int = 15,
    val wifiOnlyInBackground: Boolean = false,
    val requireBatteryNotLowInBackground: Boolean = false,
    val notifyOnRecovery: Boolean = false,
)

/**
 * Push / transfer fee bearer option for second-hand VPS sale post generation.
 */
enum class PushFeeBearer(
    val label: String,
    val feePayer: FeePayer,
) {
    BUYER("买家承担", FeePayer.BUYER),
    SELLER("卖家承担", FeePayer.SELLER),
    SPLIT("双方平摊", FeePayer.SPLIT),
    FREE("免 Push 费", FeePayer.BUYER),
}

/**
 * Common NodeSeek trade-board delivery methods.
 */
enum class FleetDeliveryMethod(
    val label: String,
    val instructionText: String,
) {
    PUSH("Push 过户", "支持站内 Push 过户，收到款项后配合快速转移。"),
    CHANGE_EMAIL("改邮出", "支持更改账户邮箱交付，或改密交接。"),
    ORIGINAL_EMAIL("带原油出", "带原始注册邮箱一并交付（原油可改密/换绑）。"),
    EITHER("Push / 改邮均可", "支持站内 Push 或改邮交付，具体私信协商。"),
}

/**
 * Cross-feature handoff payload from Feature 1 (Restock Monitor) -> Feature 4 (Asset Ledger).
 */
data class RestockAssetPrefill(
    val name: String,
    val priceHint: String = "",
    val purchaseUrl: String = "",
)

/**
 * Parsed hints derived from [RestockAssetPrefill] for prefilling the add-asset form.
 */
data class ParsedRestockAssetDraft(
    val nickname: String,
    val providerName: String,
    val specsSummary: String,
    val region: String,
    val renewalAmount: String,
    val currency: ValuationCurrency,
    val cyclePreset: BillingCyclePreset,
    val purchaseUrl: String,
    val notes: String,
)

/**
 * Rolling daily CPU utilization bucket recorded per probe node (`probeKey` + `yyyy-MM-dd`).
 */
data class ProbeDailyCpuBucket(
    val dateIso: String,
    val avgCpuPercent: Double,
    val sampleCount: Int,
    val maxCpuPercent: Double = avgCpuPercent,
    val lastSampleEpochMillis: Long = 0L,
)

/**
 * Indicates where a server's monthly traffic reset day (`1..31`) was resolved from.
 */
enum class TrafficResetDaySource {
    EXPLICIT_ASSET_CONFIG,
    ASSET_EXPIRY_DAY_FALLBACK,
    FIRST_OF_MONTH_DEFAULT,
}

/**
 * Traffic cycle window, usage ratio, burn-rate calculation, and exhaustion forecast for a probe server.
 */
data class ServerTrafficForecast(
    val probeKey: String,
    val usedBytes: Long,
    val limitBytes: Long?,
    val usageRatio: Double?,
    val anchorResetDay: Int,
    val resetDaySource: TrafficResetDaySource,
    val cycleStartDate: LocalDate,
    val nextResetDate: LocalDate,
    val elapsedMillis: Long,
    val dailyBurnBytes: Long?,
    val projectedCycleUsageRatio: Double?,
    val isAtOrAboveAlertThreshold: Boolean,
    val isExhausted: Boolean,
    val willExhaustBeforeReset: Boolean,
    val predictedExhaustionDate: LocalDate?,
    val exhaustionForecastText: String?,
)

/**
 * Configurable thresholds for 30-day idle server ("闲置") classification.
 */
data class FleetIdleThresholdConfig(
    val maxAvgCpuPercent: Double = DEFAULT_IDLE_MAX_AVG_CPU_PERCENT,
    val maxTrafficUsageRatio: Double = DEFAULT_IDLE_MAX_TRAFFIC_RATIO,
    val maxUnlimitedMonthlyTransferBytes: Long = DEFAULT_IDLE_MAX_UNLIMITED_TRANSFER_BYTES,
    val minRecordedDays: Int = DEFAULT_IDLE_MIN_RECORDED_DAYS,
    val minTotalSamples: Int = DEFAULT_IDLE_MIN_TOTAL_SAMPLES,
) {
    companion object {
        const val ROLLING_WINDOW_DAYS: Int = 30
        const val DEFAULT_IDLE_MAX_AVG_CPU_PERCENT: Double = 8.0
        const val DEFAULT_IDLE_MAX_TRAFFIC_RATIO: Double = 0.15
        const val DEFAULT_IDLE_MAX_UNLIMITED_TRANSFER_BYTES: Long = 20L * 1024L * 1024L * 1024L
        const val DEFAULT_IDLE_MIN_RECORDED_DAYS: Int = 7
        const val DEFAULT_IDLE_MIN_TOTAL_SAMPLES: Int = 7
    }
}

/**
 * 30-day utilization classification status for a server.
 */
enum class IdleClassificationStatus {
    IDLE,
    ACTIVE,
    COLD_START_OBSERVING,
    OFFLINE_OR_NO_DATA,
}

/**
 * 30-day CPU + traffic idle evaluation result for a probe server (and its optional linked ledger asset).
 */
data class ServerIdleAssessment(
    val probeKey: String,
    val serverName: String,
    val linkedAsset: VpsAssetRecord? = null,
    val status: IdleClassificationStatus,
    val avgCpu30dPercent: Double?,
    val recordedDays: Int,
    val totalSamples: Int,
    val trafficUsageRatio: Double?,
    val projectedTrafficUsageRatio: Double?,
    val monthlyTransferBytes: Long,
    val trafficLimitBytes: Long?,
    val recommendationText: String? = null,
) {
    val isIdle: Boolean
        get() = status == IdleClassificationStatus.IDLE
}

/**
 * Persistent deduplication state for probe outage alerts, renewal reminders, and per-cycle traffic alerts.
 */
data class FleetAlertTrackerState(
    val lastKnownOnlineByProbeKey: Map<String, Boolean> = emptyMap(),
    val activeOutageNotifiedKeys: Set<String> = emptySet(),
    val notifiedRenewalKeys: Set<String> = emptySet(),
    val notifiedTrafficCycleKeys: Set<String> = emptySet(),
)

/**
 * Discrete alert events produced during a background monitor evaluation pass.
 */
sealed interface FleetAlertTransition {
    data class OfflineDetected(
        val panelId: String,
        val panelName: String,
        val server: ProbeServerSnapshot,
    ) : FleetAlertTransition

    data class RecoveredOnline(
        val panelId: String,
        val panelName: String,
        val server: ProbeServerSnapshot,
    ) : FleetAlertTransition

    data class RenewalReminderDue(
        val asset: VpsAssetRecord,
        val daysRemaining: Long,
        val renewalCny: BigDecimal,
    ) : FleetAlertTransition

    data class TrafficThresholdReached(
        val panelId: String,
        val panelName: String,
        val server: ProbeServerSnapshot,
        val linkedAsset: VpsAssetRecord? = null,
        val usedBytes: Long,
        val limitBytes: Long,
        val usageRatio: Double,
        val cycleStartDateIso: String,
        val nextResetDateIso: String,
        val predictedExhaustionDateIso: String? = null,
        val exhaustionForecastText: String? = null,
    ) : FleetAlertTransition
}

/**
 * Output of a single background monitor check pass.
 */
data class FleetMonitorCheckReport(
    val checkedPanels: Int = 0,
    val failedPanels: Int = 0,
    val totalNodes: Int = 0,
    val onlineNodes: Int = 0,
    val offlineAlerts: List<FleetAlertTransition.OfflineDetected> = emptyList(),
    val recoveryAlerts: List<FleetAlertTransition.RecoveredOnline> = emptyList(),
    val renewalReminders: List<FleetAlertTransition.RenewalReminderDue> = emptyList(),
    val trafficAlerts: List<FleetAlertTransition.TrafficThresholdReached> = emptyList(),
    val checkedAtEpochMillis: Long = 0L,
    val autoRolledRenewals: List<AssetRenewalResult> = emptyList(),
)

/**
 * Converts a 2-letter ISO-3166-1 alpha-2 country code (e.g. `"JP"`, `"us"`) to its regional
 * indicator flag emoji (e.g. `🇯🇵`, `🇺🇸`).
 */
fun countryCodeToFlagEmoji(rawCode: String?): String {
    val clean = rawCode?.trim()?.uppercase(Locale.US).orEmpty()
    if (clean.length != 2 || clean.any { it !in 'A'..'Z' }) return ""
    val first = 0x1F1E6 + (clean[0] - 'A')
    val second = 0x1F1E6 + (clean[1] - 'A')
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

/**
 * Extracts a 2-letter ISO country code from a regional indicator flag emoji (e.g. `🇸🇬` -> `"SG"`),
 * or normalizes a 2-letter code directly.
 */
fun extractCountryCode(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length == 2 && trimmed.all { it.isLetter() }) {
        return trimmed.uppercase(Locale.US)
    }
    val codePoints = trimmed.codePoints().toArray()
    val indicators = codePoints.filter { it in 0x1F1E6..0x1F1FF }
    if (indicators.size >= 2) {
        val c1 = ('A'.code + (indicators[0] - 0x1F1E6)).toChar()
        val c2 = ('A'.code + (indicators[1] - 0x1F1E6)).toChar()
        return "$c1$c2"
    }
    return ""
}

/**
 * Formats a byte count into human-readable binary IEC units (`B`, `KB`, `MB`, `GB`, `TB`).
 */
fun formatBytesBinary(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024L) return "$safe B"
    val kb = safe / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    if (gb < 1024.0) return String.format(Locale.US, "%.2f GB", gb)
    val tb = gb / 1024.0
    return String.format(Locale.US, "%.2f TB", tb)
}

/**
 * Formats network speed in bytes/sec into readable throughput (`B/s`, `KB/s`, `MB/s`, `GB/s`).
 */
fun formatSpeedBps(bytesPerSecond: Long): String = "${formatBytesBinary(bytesPerSecond)}/s"

/**
 * Formats uptime seconds into concise Chinese duration (`12天 4时`, `3时 25分`, `18分`).
 */
fun formatUptimeChinese(uptimeSeconds: Long): String {
    val safe = uptimeSeconds.coerceAtLeast(0L)
    if (safe == 0L) return "0分"
    val days = safe / 86_400L
    val hours = (safe % 86_400L) / 3_600L
    val minutes = (safe % 3_600L) / 60L
    return when {
        days > 0L && hours > 0L -> "${days}天 ${hours}时"
        days > 0L -> "${days}天"
        hours > 0L && minutes > 0L -> "${hours}时 ${minutes}分"
        hours > 0L -> "${hours}时"
        else -> "${minutes.coerceAtLeast(1L)}分"
    }
}
