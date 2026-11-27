package com.nodeseek.app.domain.alerts

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class AlertEventCategory(val displayName: String) {
    PROBE_OFFLINE("探针掉线"),
    PROBE_RECOVERED("恢复在线"),
    TRAFFIC_THRESHOLD("流量预警"),
    RESTOCK_AVAILABLE("补货有货"),
    RADAR_HIT("脉搏雷达命中"),
}

sealed interface ForwardableAlertEvent {
    val category: AlertEventCategory
    val occurredAtEpochMillis: Long

    data class ProbeOffline(
        val panelName: String,
        val serverName: String,
        val probeKey: String,
        val flagEmoji: String = "",
        override val occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ) : ForwardableAlertEvent {
        override val category: AlertEventCategory = AlertEventCategory.PROBE_OFFLINE
    }

    data class ProbeRecovered(
        val panelName: String,
        val serverName: String,
        val probeKey: String,
        val flagEmoji: String = "",
        override val occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ) : ForwardableAlertEvent {
        override val category: AlertEventCategory = AlertEventCategory.PROBE_RECOVERED
    }

    data class TrafficThreshold(
        val panelName: String,
        val serverName: String,
        val probeKey: String,
        val flagEmoji: String = "",
        val usedBytes: Long,
        val limitBytes: Long,
        val usageRatio: Double,
        val nextResetDateIso: String = "",
        val exhaustionForecastText: String? = null,
        override val occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ) : ForwardableAlertEvent {
        override val category: AlertEventCategory = AlertEventCategory.TRAFFIC_THRESHOLD
    }

    data class RestockAvailable(
        val watchId: String,
        val watchTitle: String,
        val resolvedOrderUrl: String,
        val priceHint: String = "",
        val panelTypeLabel: String = "",
        val evidenceSummary: String = "",
        override val occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ) : ForwardableAlertEvent {
        override val category: AlertEventCategory = AlertEventCategory.RESTOCK_AVAILABLE
    }

    data class RadarKeywordHit(
        val topicId: Long,
        val topicTitle: String,
        val matchedKeyword: String,
        val authorName: String,
        val categoryName: String = "",
        val summarySnippet: String = "",
        val topicUrl: String = "https://www.nodeseek.com/post-$topicId-1",
        override val occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ) : ForwardableAlertEvent {
        override val category: AlertEventCategory = AlertEventCategory.RADAR_HIT
    }

    data class RadarTrackedReply(
        val topicId: Long,
        val topicTitle: String,
        val targetFloor: Int,
        val newReplyCount: Int,
        val actorName: String,
        val categoryName: String = "",
        val summarySnippet: String = "",
        val topicUrl: String = "https://www.nodeseek.com/post-$topicId-$targetFloor",
        override val occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ) : ForwardableAlertEvent {
        override val category: AlertEventCategory = AlertEventCategory.RADAR_HIT
    }
}

@Immutable
@Serializable
data class TelegramForwardConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val chatId: String = "",
    val disableNotification: Boolean = false,
)

@Immutable
@Serializable
data class BarkForwardConfig(
    val enabled: Boolean = false,
    val serverUrl: String = DEFAULT_BARK_SERVER_URL,
    val deviceKey: String = "",
    val sound: String = "",
    val group: String = "Anode",
    val isArchive: Boolean = true,
) {
    companion object {
        const val DEFAULT_BARK_SERVER_URL = "https://api.day.app"
    }
}

@Immutable
@Serializable
data class WebhookForwardConfig(
    val enabled: Boolean = false,
    val url: String = "",
    val secretHeaderName: String = DEFAULT_SECRET_HEADER,
    val secretValue: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        const val DEFAULT_SECRET_HEADER = "X-Anode-Secret"
    }
}

@Immutable
@Serializable
data class AlertForwardingConfig(
    val enabled: Boolean = false,
    val enabledEvents: Set<AlertEventCategory> = AlertEventCategory.entries.toSet(),
    val telegram: TelegramForwardConfig = TelegramForwardConfig(),
    val bark: BarkForwardConfig = BarkForwardConfig(),
    val webhook: WebhookForwardConfig = WebhookForwardConfig(),
)

@Immutable
data class ChannelDeliveryResult(
    val channelName: String,
    val success: Boolean,
    val httpStatus: Int? = null,
    val errorMessage: String? = null,
)
