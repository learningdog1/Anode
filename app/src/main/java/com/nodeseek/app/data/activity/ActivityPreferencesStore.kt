package com.nodeseek.app.data.activity

import android.content.Context
import com.nodeseek.app.domain.activity.ActivityMonitorSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class ActivityMonitorStoreSnapshot(
    val version: Int = 1,
    val settings: ActivityMonitorSettings = ActivityMonitorSettings(),
    val baselineInitialized: Boolean = false,
    val seenActivityIds: List<String> = emptyList(),
    val lastCheckedEpochMillis: Long = 0L,
    val lastNotifiedEpochMillis: Long = 0L,
)

/**
 * Thread-safe persistent JSON store (`filesDir/forumwatch_activity_monitor_prefs.json`) backing
 * [com.nodeseek.app.domain.activity.ActivityMonitorController]:
 * - Persists the opt-in notification toggle (`notificationsEnabled = false` by default),
 * - Tracks `baselineInitialized` so historical activities never spam notifications when first enabled,
 * - Persists a bounded MRU window (`maxSeenActivityIds`, default `500`) of seen activity IDs across
 *   app restarts and WorkManager runs.
 */
class ActivityPreferencesStore(
    private val storeFile: File,
    private val maxSeenActivityIds: Int = DEFAULT_MAX_SEEN_ACTIVITY_IDS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        maxSeenActivityIds: Int = DEFAULT_MAX_SEEN_ACTIVITY_IDS,
    ) : this(
        storeFile = File((context.applicationContext ?: context).filesDir, STORE_FILE_NAME),
        maxSeenActivityIds = maxSeenActivityIds,
    )

    private val boundedMaxSeenIds: Int =
        maxSeenActivityIds.coerceIn(MIN_SEEN_ACTIVITY_IDS, MAX_SEEN_ACTIVITY_IDS_CAP)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    private var state: ActivityMonitorStoreSnapshot = readSnapshotSync()

    private val _settingsFlow = MutableStateFlow(state.settings.normalized())
    val settingsFlow: StateFlow<ActivityMonitorSettings> = _settingsFlow.asStateFlow()

    val currentBaselineInitialized: Boolean
        get() = state.baselineInitialized

    val currentLastCheckedEpochMillis: Long
        get() = state.lastCheckedEpochMillis

    val currentLastNotifiedEpochMillis: Long
        get() = state.lastNotifiedEpochMillis

    suspend fun getSettings(): ActivityMonitorSettings =
        mutex.withLock { _settingsFlow.value }

    suspend fun isBaselineInitialized(): Boolean =
        mutex.withLock { state.baselineInitialized }

    suspend fun getSeenActivityIds(): Set<String> =
        mutex.withLock { LinkedHashSet(state.seenActivityIds) }

    suspend fun updateSettings(
        transform: (ActivityMonitorSettings) -> ActivityMonitorSettings,
    ): ActivityMonitorSettings = mutex.withLock {
        val nextSettings = transform(_settingsFlow.value).normalized()
        val nextSnapshot = state.copy(settings = nextSettings)
        persistSnapshotLocked(nextSnapshot)
        nextSettings
    }

    /**
     * Updates [ActivityMonitorSettings.notificationsEnabled].
     *
     * When turning notifications ON (`false -> true`) while [seedBaselineIds] are already loaded
     * on screen, merges those IDs into `seenActivityIds` and marks `baselineInitialized = true`
     * so currently displayed historical activities do not trigger notification spam.
     */
    suspend fun setNotificationsEnabled(
        enabled: Boolean,
        seedBaselineIds: Collection<String> = emptyList(),
    ): ActivityMonitorSettings = mutex.withLock {
        val previousEnabled = _settingsFlow.value.notificationsEnabled
        val nextSettings = _settingsFlow.value.copy(notificationsEnabled = enabled).normalized()
        val cleanedSeeds = sanitizeIds(seedBaselineIds)

        val mergedIds = if (enabled && !previousEnabled && cleanedSeeds.isNotEmpty()) {
            mergeAndTrimIds(state.seenActivityIds, cleanedSeeds)
        } else {
            state.seenActivityIds
        }
        val nextBaselineInitialized = if (enabled && !previousEnabled && cleanedSeeds.isNotEmpty()) {
            true
        } else {
            state.baselineInitialized
        }

        val nextSnapshot = state.copy(
            settings = nextSettings,
            baselineInitialized = nextBaselineInitialized,
            seenActivityIds = mergedIds,
        )
        persistSnapshotLocked(nextSnapshot)
        nextSettings
    }

    suspend fun initializeBaseline(
        activityIds: Collection<String>,
        checkedAtEpochMillis: Long,
    ) = mutex.withLock {
        val mergedIds = mergeAndTrimIds(state.seenActivityIds, sanitizeIds(activityIds))
        val nextSnapshot = state.copy(
            baselineInitialized = true,
            seenActivityIds = mergedIds,
            lastCheckedEpochMillis = checkedAtEpochMillis,
        )
        persistSnapshotLocked(nextSnapshot)
    }

    suspend fun recordPollOutcome(
        currentFeedIds: Collection<String>,
        newlyNotifiedIds: Collection<String>,
        checkedAtEpochMillis: Long,
    ) = mutex.withLock {
        val cleanCurrent = sanitizeIds(currentFeedIds)
        val cleanNotified = sanitizeIds(newlyNotifiedIds)
        val mergedIds = mergeAndTrimIds(state.seenActivityIds, cleanCurrent + cleanNotified)
        val nextSnapshot = state.copy(
            baselineInitialized = true,
            seenActivityIds = mergedIds,
            lastCheckedEpochMillis = checkedAtEpochMillis,
            lastNotifiedEpochMillis = if (cleanNotified.isNotEmpty()) {
                checkedAtEpochMillis
            } else {
                state.lastNotifiedEpochMillis
            },
        )
        persistSnapshotLocked(nextSnapshot)
    }

    private fun sanitizeIds(rawIds: Collection<String>): List<String> {
        if (rawIds.isEmpty()) return emptyList()
        val result = ArrayList<String>(rawIds.size)
        for (raw in rawIds) {
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) {
                result.add(trimmed)
            }
        }
        return result
    }

    private fun mergeAndTrimIds(
        existingIds: List<String>,
        touchedIds: List<String>,
    ): List<String> {
        val ordered = LinkedHashSet<String>(existingIds.size + touchedIds.size)
        for (id in existingIds) {
            val clean = id.trim()
            if (clean.isNotEmpty()) {
                ordered.add(clean)
            }
        }
        for (id in touchedIds) {
            val clean = id.trim()
            if (clean.isNotEmpty()) {
                ordered.remove(clean)
                ordered.add(clean)
            }
        }
        val list = ordered.toList()
        return if (list.size > boundedMaxSeenIds) {
            list.takeLast(boundedMaxSeenIds)
        } else {
            list
        }
    }

    private fun readSnapshotSync(): ActivityMonitorStoreSnapshot {
        if (!storeFile.exists()) return ActivityMonitorStoreSnapshot()
        return runCatching {
            val raw = storeFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) {
                ActivityMonitorStoreSnapshot()
            } else {
                val parsed = json.decodeFromString<ActivityMonitorStoreSnapshot>(raw)
                parsed.copy(
                    settings = parsed.settings.normalized(),
                    seenActivityIds = mergeAndTrimIds(emptyList(), parsed.seenActivityIds),
                )
            }
        }.getOrElse { ActivityMonitorStoreSnapshot() }
    }

    private suspend fun persistSnapshotLocked(nextSnapshot: ActivityMonitorStoreSnapshot) {
        state = nextSnapshot
        _settingsFlow.value = nextSnapshot.settings
        val encoded = json.encodeToString(nextSnapshot)
        withContext(ioDispatcher) {
            runCatching {
                storeFile.parentFile?.mkdirs()
                val tempFile = File(storeFile.parentFile, "${storeFile.name}.tmp")
                tempFile.writeText(encoded, Charsets.UTF_8)
                if (!tempFile.renameTo(storeFile)) {
                    storeFile.writeText(encoded, Charsets.UTF_8)
                    tempFile.delete()
                }
            }
        }
    }

    companion object {
        const val STORE_FILE_NAME = "forumwatch_activity_monitor_prefs.json"
        const val DEFAULT_MAX_SEEN_ACTIVITY_IDS = 500
        private const val MIN_SEEN_ACTIVITY_IDS = 10
        private const val MAX_SEEN_ACTIVITY_IDS_CAP = 1_500
    }
}
