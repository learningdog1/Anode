package com.nodeseek.app.domain.restock

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Supported VPS / merchant storefront panel types detected automatically or selected by user.
 */
@Serializable
enum class MerchantPanelType(val displayName: String) {
    WHMCS("WHMCS"),
    SOLUSVM("SolusVM"),
    HOSTBILL("HostBill"),
    VIRTFUSION("VirtFusion"),
    GENERIC("通用商城"),
}

/**
 * Current stock availability state for a monitored VPS / flash-sale product.
 */
@Serializable
enum class RestockStockStatus(val displayName: String) {
    UNKNOWN("待检测"),
    OUT_OF_STOCK("缺货蹲守中"),
    IN_STOCK("有货可抢"),
    CHECK_ERROR("检测异常"),
}

/**
 * A monitored VPS product or flash-sale order URL.
 */
@Immutable
@Serializable
data class RestockWatchItem(
    val id: String,
    val title: String,
    val rawUrl: String,
    val resolvedOrderUrl: String,
    val panelType: MerchantPanelType = MerchantPanelType.GENERIC,
    val priceHint: String = "",
    val customInStockKeyword: String = "",
    val customOutOfStockKeyword: String = "",
    val pollIntervalMinutes: Int = DEFAULT_POLL_INTERVAL_MINUTES,
    val isEnabled: Boolean = true,
    val stockStatus: RestockStockStatus = RestockStockStatus.UNKNOWN,
    val lastCheckedEpochMillis: Long = 0L,
    val lastRestockedEpochMillis: Long = 0L,
    val consecutiveErrors: Int = 0,
    val lastErrorMessage: String? = null,
    val lastEvidenceSummary: String = "",
    val sourceTopicId: Long? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val alertWaveArmed: Boolean = (stockStatus == RestockStockStatus.OUT_OF_STOCK),
) {
    val name: String
        get() = title

    companion object {
        const val MIN_POLL_INTERVAL_MINUTES = 1
        const val MAX_POLL_INTERVAL_MINUTES = 60
        const val DEFAULT_POLL_INTERVAL_MINUTES = 2

        fun clampPollInterval(minutes: Int): Int =
            minutes.coerceIn(MIN_POLL_INTERVAL_MINUTES, MAX_POLL_INTERVAL_MINUTES)
    }
}

/**
 * History entry recording a deduplicated `OUT_OF_STOCK -> IN_STOCK` restock wave transition.
 */
@Immutable
@Serializable
data class RestockEventRecord(
    val id: String,
    val watchItemId: String,
    val watchTitle: String,
    val resolvedOrderUrl: String,
    val panelType: MerchantPanelType = MerchantPanelType.GENERIC,
    val priceHint: String = "",
    val previousStatus: RestockStockStatus = RestockStockStatus.OUT_OF_STOCK,
    val detectedStatus: RestockStockStatus = RestockStockStatus.IN_STOCK,
    val evidenceSummary: String = "",
    val detectedAtEpochMillis: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val sourceTopicId: Long? = null,
)

/**
 * Self-hosted FlareSolverr endpoint used to auto-solve Cloudflare challenges on
 * monitored merchant pages. The app acts as a pure client: solved HTML comes back
 * from the service, so `cf_clearance` UA/IP binding never applies on-device.
 */
@Immutable
@Serializable
data class FlareSolverrConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val maxTimeoutMillis: Int = DEFAULT_MAX_TIMEOUT_MILLIS,
) {
    val isConfigured: Boolean
        get() = enabled && normalizedBaseUrl.isNotBlank()

    /** Trims and guarantees an http(s) scheme so [java.net.URI] parsing always works. */
    val normalizedBaseUrl: String
        get() {
            val trimmed = baseUrl.trim()
            if (trimmed.isBlank()) return ""
            val withScheme = if (trimmed.startsWith("http://", true) ||
                trimmed.startsWith("https://", true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return withScheme.trimEnd('/')
        }

    fun normalized(): FlareSolverrConfig = copy(
        baseUrl = normalizedBaseUrl,
        maxTimeoutMillis = maxTimeoutMillis.coerceIn(MIN_MAX_TIMEOUT_MILLIS, MAX_MAX_TIMEOUT_MILLIS),
    )

    companion object {
        const val MIN_MAX_TIMEOUT_MILLIS = 15_000
        const val MAX_MAX_TIMEOUT_MILLIS = 120_000
        const val DEFAULT_MAX_TIMEOUT_MILLIS = 60_000
    }
}

/**
 * Global monitor and WorkManager background polling configuration.
 */
@Immutable
@Serializable
data class RestockMonitorSettings(
    val isMonitorEnabled: Boolean = true,
    val defaultPollIntervalMinutes: Int = RestockWatchItem.DEFAULT_POLL_INTERVAL_MINUTES,
    val backgroundIntervalMinutes: Int = MIN_BACKGROUND_INTERVAL_MINUTES,
    val wifiOnlyInBackground: Boolean = false,
    val requireBatteryNotLowInBackground: Boolean = false,
    val urgentNotificationsEnabled: Boolean = true,
    val flareSolverr: FlareSolverrConfig = FlareSolverrConfig(),
) {
    fun normalized(): RestockMonitorSettings = copy(
        defaultPollIntervalMinutes = RestockWatchItem.clampPollInterval(defaultPollIntervalMinutes),
        backgroundIntervalMinutes = backgroundIntervalMinutes.coerceIn(
            MIN_BACKGROUND_INTERVAL_MINUTES,
            MAX_BACKGROUND_INTERVAL_MINUTES,
        ),
        flareSolverr = flareSolverr.normalized(),
    )

    companion object {
        const val MIN_BACKGROUND_INTERVAL_MINUTES = 15
        const val MAX_BACKGROUND_INTERVAL_MINUTES = 180
    }
}

/**
 * Result of probing and analyzing a single product/order page.
 */
@Immutable
data class StockAnalysisResult(
    val rawUrl: String,
    val resolvedOrderUrl: String,
    val finalUrl: String,
    val httpStatusCode: Int = 0,
    val panelType: MerchantPanelType = MerchantPanelType.GENERIC,
    val stockStatus: RestockStockStatus = RestockStockStatus.UNKNOWN,
    val detectedTitle: String = "",
    val detectedPriceHint: String = "",
    val matchedSignal: String = "",
    val errorMessage: String? = null,
    val isTransientNetworkError: Boolean = false,
    val usedFlareSolverr: Boolean = false,
    val checkedAtEpochMillis: Long = System.currentTimeMillis(),
)

/**
 * Candidate VPS merchant / cart / affiliate link discovered inside a NodeSeek post or comment.
 */
@Immutable
data class DetectedMerchantLink(
    val rawUrl: String,
    val resolvedUrl: String,
    val anchorText: String = "",
    val panelType: MerchantPanelType = MerchantPanelType.GENERIC,
    val host: String = "",
    val confidence: Int = 0,
)

/**
 * Draft payload submitted by UI forms (quick-add bar or reusable bottom sheet).
 */
@Immutable
data class RestockWatchDraft(
    val rawUrl: String,
    val title: String = "",
    val panelType: MerchantPanelType? = null,
    val priceHint: String = "",
    val customInStockKeyword: String = "",
    val customOutOfStockKeyword: String = "",
    val pollIntervalMinutes: Int = RestockWatchItem.DEFAULT_POLL_INTERVAL_MINUTES,
    val isEnabled: Boolean = true,
    val sourceTopicId: Long? = null,
    val initialStockStatus: RestockStockStatus = RestockStockStatus.UNKNOWN,
    val initialEvidenceSummary: String = "",
    val editingId: String? = null,
)

/**
 * Live runtime status of the foreground/background restock monitor loop.
 */
@Immutable
data class RestockRuntimeStatus(
    val isRunningInForeground: Boolean = true,
    val isCheckingNow: Boolean = false,
    val checkingItemIds: Set<String> = emptySet(),
    val lastCheckEpochMillis: Long = 0L,
    val lastErrorSummary: String? = null,
)

/**
 * Outcome returned by [RestockMonitorController.runBackgroundPollPass] for WorkManager mapping.
 */
sealed interface RestockBackgroundPollOutcome {
    data class Completed(
        val checkedCount: Int = 0,
        val restockedCount: Int = 0,
        val errorCount: Int = 0,
        val errorSummary: String? = null,
    ) : RestockBackgroundPollOutcome

    data class Retry(
        val reason: String,
    ) : RestockBackgroundPollOutcome

    data class Skipped(
        val reason: SkipReason,
    ) : RestockBackgroundPollOutcome {
        enum class SkipReason {
            DISABLED,
            NO_ENABLED_WATCHES,
            UNMETERED_NETWORK_REQUIRED,
        }
    }
}
