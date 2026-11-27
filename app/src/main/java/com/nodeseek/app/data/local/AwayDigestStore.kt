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

    fun clearForAccount(uid: Long) = Unit
}

/**
 * Synchronous [SharedPreferences]-backed [AwayDigestStore] ensuring `lastActiveTimestampMillis`
 * and session dismissal persist reliably across backgrounding and cold-start process restarts.
 */
class SharedPreferencesAwayDigestStore(
    private val prefs: SharedPreferences,
    private val accountUidProvider: () -> Long = { 0L },
) : AwayDigestStore {

    constructor(
        context: Context,
        accountUidProvider: () -> Long = { 0L },
    ) : this(
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        accountUidProvider = accountUidProvider,
    )

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val metaCachesByUid = HashMap<Long, LinkedHashMap<Long, CachedTopicDrumstickMeta>>()

    private fun currentUid(): Long = accountUidProvider().coerceAtLeast(0L)

    private fun scopedKey(baseKey: String, uid: Long = currentUid()): String =
        if (uid == 0L) baseKey else "${baseKey}_u$uid"

    override fun getLastActiveTimestampMillis(): Long = synchronized(lock) {
        prefs.getLong(scopedKey(KEY_LAST_ACTIVE_MILLIS), 0L)
    }

    override fun setLastActiveTimestampMillis(timestampMillis: Long) {
        synchronized(lock) {
            prefs.edit().putLong(scopedKey(KEY_LAST_ACTIVE_MILLIS), timestampMillis).apply()
        }
    }

    override fun wasAppClosed(): Boolean = synchronized(lock) {
        prefs.getBoolean(scopedKey(KEY_WAS_APP_CLOSED), false)
    }

    override fun setAppClosed(closed: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean(scopedKey(KEY_WAS_APP_CLOSED), closed).apply()
        }
    }

    override fun getActiveSession(): AwaySession? = synchronized(lock) {
        val raw = prefs.getString(scopedKey(KEY_ACTIVE_SESSION_JSON), null)?.takeIf { it.isNotBlank() }
            ?: return null
        runCatching { json.decodeFromString<AwaySession>(raw) }.getOrNull()
    }

    override fun setActiveSession(session: AwaySession?) {
        synchronized(lock) {
            val key = scopedKey(KEY_ACTIVE_SESSION_JSON)
            val editor = prefs.edit()
            if (session == null) {
                editor.remove(key)
            } else {
                editor.putString(key, json.encodeToString(session))
            }
            editor.apply()
        }
    }

    override fun getCachedTopicMeta(topicId: Long): CachedTopicDrumstickMeta? = synchronized(lock) {
        val uid = currentUid()
        ensureMetaCacheForUidLocked(uid)[topicId]
    }

    override fun putCachedTopicMeta(meta: CachedTopicDrumstickMeta) {
        synchronized(lock) {
            val uid = currentUid()
            val cache = ensureMetaCacheForUidLocked(uid)
            cache[meta.topicId] = meta
            val snapshot = cache.values.toList()
            prefs.edit()
                .putString(scopedKey(KEY_TOPIC_META_LIST_JSON, uid), json.encodeToString(snapshot))
                .apply()
        }
    }

    override fun clearForAccount(uid: Long) {
        val targetUid = uid.coerceAtLeast(0L)
        synchronized(lock) {
            metaCachesByUid.remove(targetUid)
            prefs.edit()
                .remove(scopedKey(KEY_LAST_ACTIVE_MILLIS, targetUid))
                .remove(scopedKey(KEY_WAS_APP_CLOSED, targetUid))
                .remove(scopedKey(KEY_ACTIVE_SESSION_JSON, targetUid))
                .remove(scopedKey(KEY_TOPIC_META_LIST_JSON, targetUid))
                .apply()
        }
    }

    private fun ensureMetaCacheForUidLocked(uid: Long): LinkedHashMap<Long, CachedTopicDrumstickMeta> {
        metaCachesByUid[uid]?.let { return it }
        val cache = object : LinkedHashMap<Long, CachedTopicDrumstickMeta>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedTopicDrumstickMeta>?): Boolean =
                size > MAX_CACHED_TOPIC_META
        }
        val raw = prefs.getString(scopedKey(KEY_TOPIC_META_LIST_JSON, uid), null)?.takeIf { it.isNotBlank() }
        if (raw != null) {
            val parsed = runCatching { json.decodeFromString<List<CachedTopicDrumstickMeta>>(raw) }.getOrNull()
            parsed?.forEach { item ->
                if (item.topicId > 0L) {
                    cache[item.topicId] = item
                }
            }
        }
        metaCachesByUid[uid] = cache
        return cache
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
    private val accountUidProvider: () -> Long = { 0L },
) : AwayDigestStore {
    private val lock = Any()
    private val initialUid = accountUidProvider().coerceAtLeast(0L)
    private val lastActiveByUid = HashMap<Long, Long>().apply {
        if (initialLastActiveTimestampMillis != 0L) put(initialUid, initialLastActiveTimestampMillis)
    }
    private val appClosedByUid = HashMap<Long, Boolean>().apply {
        if (initialWasAppClosed) put(initialUid, true)
    }
    private val activeSessionByUid = HashMap<Long, AwaySession?>().apply {
        if (initialSession != null) put(initialUid, initialSession)
    }
    private val metaByUid = HashMap<Long, LinkedHashMap<Long, CachedTopicDrumstickMeta>>()

    private fun currentUid(): Long = accountUidProvider().coerceAtLeast(0L)

    override fun getLastActiveTimestampMillis(): Long = synchronized(lock) {
        lastActiveByUid[currentUid()] ?: 0L
    }

    override fun setLastActiveTimestampMillis(timestampMillis: Long) {
        synchronized(lock) {
            lastActiveByUid[currentUid()] = timestampMillis
        }
    }

    override fun wasAppClosed(): Boolean = synchronized(lock) {
        appClosedByUid[currentUid()] ?: false
    }

    override fun setAppClosed(closed: Boolean) {
        synchronized(lock) {
            appClosedByUid[currentUid()] = closed
        }
    }

    override fun getActiveSession(): AwaySession? = synchronized(lock) {
        activeSessionByUid[currentUid()]
    }

    override fun setActiveSession(session: AwaySession?) {
        synchronized(lock) {
            val uid = currentUid()
            if (session == null) {
                activeSessionByUid.remove(uid)
            } else {
                activeSessionByUid[uid] = session
            }
        }
    }

    override fun getCachedTopicMeta(topicId: Long): CachedTopicDrumstickMeta? = synchronized(lock) {
        metaByUid[currentUid()]?.get(topicId)
    }

    override fun putCachedTopicMeta(meta: CachedTopicDrumstickMeta) {
        synchronized(lock) {
            val map = metaByUid.getOrPut(currentUid()) { LinkedHashMap() }
            map[meta.topicId] = meta
        }
    }

    override fun clearForAccount(uid: Long) {
        val targetUid = uid.coerceAtLeast(0L)
        synchronized(lock) {
            lastActiveByUid.remove(targetUid)
            appClosedByUid.remove(targetUid)
            activeSessionByUid.remove(targetUid)
            metaByUid.remove(targetUid)
        }
    }
}
