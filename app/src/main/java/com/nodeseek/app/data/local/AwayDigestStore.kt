package com.nodeseek.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.nodeseek.app.domain.digest.AwaySession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedTopicDrumstickMeta(
    val topicId: Long,
    val drumstickCount: Int = 0,
    val viewCount: Int = 0,
    val replyCount: Int = 0,
    val isCuratedAward: Boolean = false,
    val createdAtIso: String? = null,
    val cachedAtMillis: Long = 0L,
)

/**
 * Persistence contract for tracking `lastActiveTimestampMillis`, the current [AwaySession]
 * (including whether the user dismissed it), and a bounded cache of enriched topic drumstick counts.
 */
interface AwayDigestStore {
    fun getLastActiveTimestampMillis(): Long
    fun setLastActiveTimestampMillis(timestampMillis: Long)

    fun wasAppClosed(): Boolean = false
    fun setAppClosed(closed: Boolean) = Unit

    fun getActiveSession(): AwaySession?
    fun setActiveSession(session: AwaySession?)

    fun getCachedTopicMeta(topicId: Long): CachedTopicDrumstickMeta?
    fun putCachedTopicMeta(meta: CachedTopicDrumstickMeta)
}

/**
 * Synchronous [SharedPreferences]-backed [AwayDigestStore] ensuring `lastActiveTimestampMillis`
 * and session dismissal persist reliably across backgrounding and cold-start process restarts.
 */
class SharedPreferencesAwayDigestStore(
    private val prefs: SharedPreferences,
) : AwayDigestStore {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val metaCache = object : LinkedHashMap<Long, CachedTopicDrumstickMeta>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedTopicDrumstickMeta>?): Boolean =
            size > MAX_CACHED_TOPIC_META
    }
    private var metaHydrated = false

    override fun getLastActiveTimestampMillis(): Long = synchronized(lock) {
        prefs.getLong(KEY_LAST_ACTIVE_MILLIS, 0L)
    }

    override fun setLastActiveTimestampMillis(timestampMillis: Long) {
        synchronized(lock) {
            prefs.edit().putLong(KEY_LAST_ACTIVE_MILLIS, timestampMillis).apply()
        }
    }

    override fun wasAppClosed(): Boolean = synchronized(lock) {
        prefs.getBoolean(KEY_WAS_APP_CLOSED, false)
    }

    override fun setAppClosed(closed: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean(KEY_WAS_APP_CLOSED, closed).apply()
        }
    }

    override fun getActiveSession(): AwaySession? = synchronized(lock) {
        val raw = prefs.getString(KEY_ACTIVE_SESSION_JSON, null)?.takeIf { it.isNotBlank() }
            ?: return null
        runCatching { json.decodeFromString<AwaySession>(raw) }.getOrNull()
    }

    override fun setActiveSession(session: AwaySession?) {
        synchronized(lock) {
            val editor = prefs.edit()
            if (session == null) {
                editor.remove(KEY_ACTIVE_SESSION_JSON)
            } else {
                editor.putString(KEY_ACTIVE_SESSION_JSON, json.encodeToString(session))
            }
            editor.apply()
        }
    }

    override fun getCachedTopicMeta(topicId: Long): CachedTopicDrumstickMeta? = synchronized(lock) {
        ensureMetaHydratedLocked()
        metaCache[topicId]
    }

    override fun putCachedTopicMeta(meta: CachedTopicDrumstickMeta) {
        synchronized(lock) {
            ensureMetaHydratedLocked()
            metaCache[meta.topicId] = meta
            val snapshot = metaCache.values.toList()
            prefs.edit()
                .putString(KEY_TOPIC_META_LIST_JSON, json.encodeToString(snapshot))
                .apply()
        }
    }

    private fun ensureMetaHydratedLocked() {
        if (metaHydrated) return
        metaHydrated = true
        val raw = prefs.getString(KEY_TOPIC_META_LIST_JSON, null)?.takeIf { it.isNotBlank() } ?: return
        val parsed = runCatching { json.decodeFromString<List<CachedTopicDrumstickMeta>>(raw) }.getOrNull()
            ?: return
        parsed.forEach { item ->
            if (item.topicId > 0L) {
                metaCache[item.topicId] = item
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "nodepulse_away_digest_prefs"
        private const val KEY_LAST_ACTIVE_MILLIS = "last_active_timestamp_millis"
        private const val KEY_WAS_APP_CLOSED = "was_app_closed"
        private const val KEY_ACTIVE_SESSION_JSON = "active_away_session_json"
        private const val KEY_TOPIC_META_LIST_JSON = "cached_topic_drumstick_meta_json"
        private const val MAX_CACHED_TOPIC_META = 120
    }
}

/**
 * Lightweight in-memory [AwayDigestStore] for unit tests and Compose previews.
 */
class InMemoryAwayDigestStore(
    initialLastActiveTimestampMillis: Long = 0L,
    initialSession: AwaySession? = null,
    initialWasAppClosed: Boolean = false,
) : AwayDigestStore {
    private val lock = Any()
    private var lastActiveMillis: Long = initialLastActiveTimestampMillis
    private var appClosed: Boolean = initialWasAppClosed
    private var activeSession: AwaySession? = initialSession
    private val metaMap = LinkedHashMap<Long, CachedTopicDrumstickMeta>()

    override fun getLastActiveTimestampMillis(): Long = synchronized(lock) { lastActiveMillis }

    override fun setLastActiveTimestampMillis(timestampMillis: Long) {
        synchronized(lock) {
            lastActiveMillis = timestampMillis
        }
    }

    override fun wasAppClosed(): Boolean = synchronized(lock) { appClosed }

    override fun setAppClosed(closed: Boolean) {
        synchronized(lock) {
            appClosed = closed
        }
    }

    override fun getActiveSession(): AwaySession? = synchronized(lock) { activeSession }

    override fun setActiveSession(session: AwaySession?) {
        synchronized(lock) {
            activeSession = session
        }
    }

    override fun getCachedTopicMeta(topicId: Long): CachedTopicDrumstickMeta? = synchronized(lock) {
        metaMap[topicId]
    }

    override fun putCachedTopicMeta(meta: CachedTopicDrumstickMeta) {
        synchronized(lock) {
            metaMap[meta.topicId] = meta
        }
    }
}
