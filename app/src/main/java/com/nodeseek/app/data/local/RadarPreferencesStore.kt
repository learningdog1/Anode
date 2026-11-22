package com.nodeseek.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nodeseek.app.domain.radar.RadarPollingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

val Context.nodePulseRadarDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nodepulse_radar_prefs",
)

data class RadarWatermarks(
    val lastSeenMaxTopicId: Long = 0L,
    val inboxAccountUid: Long = 0L,
    val lastNotifiedReplyId: Long = -1L,
    val lastNotifiedMentionId: Long = -1L,
    val dmInitialized: Boolean = false,
    val lastNotifiedDmMap: Map<Long, Long> = emptyMap(),
) {
    companion object {
        fun buildDmSignature(updatedAtIso: String, previewText: String): String {
            val cleanTime = updatedAtIso.trim()
            val cleanBody = previewText.trim()
            return if (cleanTime.isEmpty() && cleanBody.isEmpty()) "" else "$cleanTime|$cleanBody"
        }

        fun encodeSignatureHashWatermark(signature: String): Long {
            if (signature.isEmpty()) return 0L
            val hash = signature.hashCode().toLong() and 0x7FFF_FFFFL
            return -(1L + hash)
        }

        fun encodeSyntheticDmWatermark(updatedAtIso: String, previewText: String): Long =
            encodeSignatureHashWatermark(buildDmSignature(updatedAtIso, previewText))
    }
}

interface RadarWatermarkStore {
    suspend fun getWatermarks(): RadarWatermarks
    suspend fun updateWatermarks(transform: (RadarWatermarks) -> RadarWatermarks): RadarWatermarks
}

class RadarPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : RadarWatermarkStore {

    constructor(context: Context) : this(context.applicationContext.nodePulseRadarDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val safePreferencesFlow: Flow<Preferences> = dataStore.data.catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }

    val pollingSettingsFlow: Flow<RadarPollingSettings> = safePreferencesFlow.map { prefs ->
        prefs.toPollingSettings()
    }

    suspend fun getPollingSettings(): RadarPollingSettings =
        safePreferencesFlow.first().toPollingSettings()

    suspend fun updatePollingSettings(transform: (RadarPollingSettings) -> RadarPollingSettings) {
        dataStore.edit { prefs ->
            val current = prefs.toPollingSettings()
            val updated = transform(current).normalized()
            prefs[Keys.IS_ENABLED] = updated.isEnabled
            prefs[Keys.KEYWORD_ALERTS_ENABLED] = updated.keywordAlertsEnabled
            prefs[Keys.REPLY_TRACKING_ENABLED] = updated.replyTrackingEnabled
            prefs[Keys.INBOX_PUSH_ENABLED] = updated.inboxPushEnabled
            prefs[Keys.FOREGROUND_INTERVAL_MINUTES] = updated.foregroundIntervalMinutes
            prefs[Keys.BACKGROUND_INTERVAL_MINUTES] = updated.backgroundIntervalMinutes
            prefs[Keys.WIFI_ONLY_IN_BACKGROUND] = updated.wifiOnlyInBackground
            prefs[Keys.REQUIRE_BATTERY_NOT_LOW_IN_BACKGROUND] = updated.requireBatteryNotLowInBackground
            prefs[Keys.QUIET_HOURS_ENABLED] = updated.quietHoursEnabled
            prefs[Keys.QUIET_START_MINUTE] = updated.quietStartMinuteOfDay
            prefs[Keys.QUIET_END_MINUTE] = updated.quietEndMinuteOfDay
        }
    }

    override suspend fun getWatermarks(): RadarWatermarks =
        safePreferencesFlow.first().toWatermarks()

    override suspend fun updateWatermarks(
        transform: (RadarWatermarks) -> RadarWatermarks,
    ): RadarWatermarks {
        var result = RadarWatermarks()
        dataStore.edit { prefs ->
            val current = prefs.toWatermarks()
            val next = transform(current)
            val trimmedDmMap = if (next.lastNotifiedDmMap.size > MAX_TRACKED_DM_PEERS) {
                next.lastNotifiedDmMap.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<Long, Long>> { it.value > 0L }
                            .thenByDescending { if (it.value > 0L) it.value else 0L },
                    )
                    .take(MAX_TRACKED_DM_PEERS)
                    .associate { it.key to it.value }
            } else {
                next.lastNotifiedDmMap
            }
            val finalState = next.copy(lastNotifiedDmMap = trimmedDmMap)
            prefs[Keys.LAST_SEEN_MAX_TOPIC_ID] = finalState.lastSeenMaxTopicId
            prefs[Keys.INBOX_ACCOUNT_UID] = finalState.inboxAccountUid
            prefs[Keys.LAST_NOTIFIED_REPLY_ID] = finalState.lastNotifiedReplyId
            prefs[Keys.LAST_NOTIFIED_MENTION_ID] = finalState.lastNotifiedMentionId
            prefs[Keys.DM_INITIALIZED] = finalState.dmInitialized
            prefs[Keys.LAST_NOTIFIED_DM_MAP_JSON] = encodeDmMap(finalState.lastNotifiedDmMap)
            result = finalState
        }
        return result
    }

    private fun Preferences.toPollingSettings(): RadarPollingSettings = RadarPollingSettings(
        isEnabled = this[Keys.IS_ENABLED] ?: true,
        keywordAlertsEnabled = this[Keys.KEYWORD_ALERTS_ENABLED] ?: true,
        replyTrackingEnabled = this[Keys.REPLY_TRACKING_ENABLED] ?: true,
        inboxPushEnabled = this[Keys.INBOX_PUSH_ENABLED] ?: true,
        foregroundIntervalMinutes = (this[Keys.FOREGROUND_INTERVAL_MINUTES] ?: 2).coerceIn(
            RadarPollingSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
            RadarPollingSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
        ),
        backgroundIntervalMinutes = (
            this[Keys.BACKGROUND_INTERVAL_MINUTES] ?: RadarPollingSettings.MIN_BACKGROUND_INTERVAL_MINUTES
            ).coerceIn(
            RadarPollingSettings.MIN_BACKGROUND_INTERVAL_MINUTES,
            RadarPollingSettings.MAX_BACKGROUND_INTERVAL_MINUTES,
        ),
        wifiOnlyInBackground = this[Keys.WIFI_ONLY_IN_BACKGROUND] ?: false,
        requireBatteryNotLowInBackground = this[Keys.REQUIRE_BATTERY_NOT_LOW_IN_BACKGROUND] ?: false,
        quietHoursEnabled = this[Keys.QUIET_HOURS_ENABLED] ?: false,
        quietStartMinuteOfDay = (this[Keys.QUIET_START_MINUTE] ?: (23 * 60)).coerceIn(0, 1439),
        quietEndMinuteOfDay = (this[Keys.QUIET_END_MINUTE] ?: (7 * 60)).coerceIn(0, 1439),
    )

    private fun RadarPollingSettings.normalized(): RadarPollingSettings = copy(
        foregroundIntervalMinutes = foregroundIntervalMinutes.coerceIn(
            RadarPollingSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
            RadarPollingSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
        ),
        backgroundIntervalMinutes = backgroundIntervalMinutes.coerceIn(
            RadarPollingSettings.MIN_BACKGROUND_INTERVAL_MINUTES,
            RadarPollingSettings.MAX_BACKGROUND_INTERVAL_MINUTES,
        ),
        quietStartMinuteOfDay = quietStartMinuteOfDay.coerceIn(0, 1439),
        quietEndMinuteOfDay = quietEndMinuteOfDay.coerceIn(0, 1439),
    )

    private fun Preferences.toWatermarks(): RadarWatermarks {
        val dmRaw = this[Keys.LAST_NOTIFIED_DM_MAP_JSON]
        val decodedDmMap = decodeDmMap(dmRaw)
        return RadarWatermarks(
            lastSeenMaxTopicId = this[Keys.LAST_SEEN_MAX_TOPIC_ID] ?: 0L,
            inboxAccountUid = this[Keys.INBOX_ACCOUNT_UID] ?: 0L,
            lastNotifiedReplyId = this[Keys.LAST_NOTIFIED_REPLY_ID] ?: -1L,
            lastNotifiedMentionId = this[Keys.LAST_NOTIFIED_MENTION_ID] ?: -1L,
            dmInitialized = this[Keys.DM_INITIALIZED] ?: false,
            lastNotifiedDmMap = decodedDmMap,
        )
    }

    private fun encodeDmMap(map: Map<Long, Long>): String {
        val stringMap = map.entries.associate { (k, v) -> k.toString() to v }
        return json.encodeToString(stringMap)
    }

    private fun decodeDmMap(raw: String?): Map<Long, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = runCatching { json.decodeFromString<Map<String, Long>>(raw) }.getOrNull()
            ?: return emptyMap()
        val result = LinkedHashMap<Long, Long>(parsed.size)
        for ((k, v) in parsed) {
            val uid = k.toLongOrNull() ?: continue
            result[uid] = v
        }
        return result
    }

    private object Keys {
        val IS_ENABLED = booleanPreferencesKey("radar_is_enabled")
        val KEYWORD_ALERTS_ENABLED = booleanPreferencesKey("radar_keyword_alerts_enabled")
        val REPLY_TRACKING_ENABLED = booleanPreferencesKey("radar_reply_tracking_enabled")
        val INBOX_PUSH_ENABLED = booleanPreferencesKey("radar_inbox_push_enabled")
        val FOREGROUND_INTERVAL_MINUTES = intPreferencesKey("radar_fg_interval_minutes")
        val BACKGROUND_INTERVAL_MINUTES = intPreferencesKey("radar_bg_interval_minutes")
        val WIFI_ONLY_IN_BACKGROUND = booleanPreferencesKey("radar_wifi_only_bg")
        val REQUIRE_BATTERY_NOT_LOW_IN_BACKGROUND = booleanPreferencesKey("radar_require_battery_not_low_bg")
        val QUIET_HOURS_ENABLED = booleanPreferencesKey("radar_quiet_hours_enabled")
        val QUIET_START_MINUTE = intPreferencesKey("radar_quiet_start_minute")
        val QUIET_END_MINUTE = intPreferencesKey("radar_quiet_end_minute")

        val LAST_SEEN_MAX_TOPIC_ID = longPreferencesKey("radar_last_seen_max_topic_id")
        val INBOX_ACCOUNT_UID = longPreferencesKey("radar_inbox_account_uid")
        val LAST_NOTIFIED_REPLY_ID = longPreferencesKey("radar_last_notified_reply_id")
        val LAST_NOTIFIED_MENTION_ID = longPreferencesKey("radar_last_notified_mention_id")
        val DM_INITIALIZED = booleanPreferencesKey("radar_dm_initialized")
        val LAST_NOTIFIED_DM_MAP_JSON = stringPreferencesKey("radar_last_notified_dm_map_json")
    }

    companion object {
        private const val MAX_TRACKED_DM_PEERS = 200
    }
}
