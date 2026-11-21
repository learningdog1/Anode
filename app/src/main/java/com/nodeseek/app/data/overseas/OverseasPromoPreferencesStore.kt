package com.nodeseek.app.data.overseas

import android.content.Context
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorSettings
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
internal data class OverseasPromoStoreSnapshot(
    val version: Int = 1,
    val settings: OverseasPromoMonitorSettings = OverseasPromoMonitorSettings(),
    val baselineInitialized: Boolean = false,
    val seenOfferIds: List<String> = emptyList(),
    val lastCheckedEpochMillis: Long = 0L,
    val lastNotifiedEpochMillis: Long = 0L,
)

/**
 * Thread-safe persistent JSON store (`filesDir/overseas_promo_monitor_prefs.json`) backing
 * [com.nodeseek.app.domain.overseas.OverseasPromoMonitorController]:
 * - Persists user notification toggle and foreground/background polling settings,
 * - Tracks whether initial historical baseline has been established (`baselineInitialized`) so old
 *   historical offers never trigger notification floods on first enable / cold install,
 * - Persists a bounded MRU window (`maxSeenOfferIds`, default `400`) of seen/notified offer IDs so
 *   no promotion is ever pushed more than once across app restarts or WorkManager runs.
 */
class OverseasPromoPreferencesStore(
    private val storeFile: File,
    private val maxSeenOfferIds: Int = DEFAULT_MAX_SEEN_OFFER_IDS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        maxSeenOfferIds: Int = DEFAULT_MAX_SEEN_OFFER_IDS,
    ) : this(
        storeFile = File((context.applicationContext ?: context).filesDir, STORE_FILE_NAME),
        maxSeenOfferIds = maxSeenOfferIds,
    )

    private val boundedMaxSeenIds: Int = maxSeenOfferIds.coerceIn(MIN_SEEN_OFFER_IDS, MAX_SEEN_OFFER_IDS_CAP)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    private var state: OverseasPromoStoreSnapshot = readSnapshotSync()

    private val _settingsFlow = MutableStateFlow(state.settings.normalized())
    val settingsFlow: StateFlow<OverseasPromoMonitorSettings> = _settingsFlow.asStateFlow()

    val currentBaselineInitialized: Boolean
        get() = state.baselineInitialized

    val currentLastCheckedEpochMillis: Long
        get() = state.lastCheckedEpochMillis

    val currentLastNotifiedEpochMillis: Long
        get() = state.lastNotifiedEpochMillis

    suspend fun getSettings(): OverseasPromoMonitorSettings =
        mutex.withLock { _settingsFlow.value }

    suspend fun isBaselineInitialized(): Boolean =
        mutex.withLock { state.baselineInitialized }

    suspend fun getSeenOfferIds(): Set<String> =
        mutex.withLock { LinkedHashSet(state.seenOfferIds) }

    suspend fun updateSettings(
        transform: (OverseasPromoMonitorSettings) -> OverseasPromoMonitorSettings,
    ): OverseasPromoMonitorSettings = mutex.withLock {
        val nextSettings = transform(_settingsFlow.value).normalized()
        val nextSnapshot = state.copy(settings = nextSettings)
        persistSnapshotLocked(nextSnapshot)
        nextSettings
    }

    /**
     * Updates [OverseasPromoMonitorSettings.notificationsEnabled].
     *
     * When re-enabling notifications (`false -> true`) while [seedBaselineIds] are already loaded
     * in memory (for example, offers currently displayed in the Overseas Intel screen), those IDs
     * are merged into `seenOfferIds` and mark the baseline initialized so existing offers on screen
     * are not immediately pushed as new alerts.
     */
    suspend fun setNotificationsEnabled(
        enabled: Boolean,
        seedBaselineIds: Collection<String> = emptyList(),
    ): OverseasPromoMonitorSettings = mutex.withLock {
        val previousEnabled = _settingsFlow.value.notificationsEnabled
        val nextSettings = _settingsFlow.value.copy(notificationsEnabled = enabled).normalized()
        val cleanedSeeds = sanitizeIds(seedBaselineIds)

        val mergedIds = if (enabled && !previousEnabled && cleanedSeeds.isNotEmpty()) {
            mergeAndTrimIds(state.seenOfferIds, cleanedSeeds)
        } else {
            state.seenOfferIds
        }
        val nextBaselineInitialized = if (enabled && !previousEnabled && cleanedSeeds.isNotEmpty()) {
            true
        } else {
            state.baselineInitialized
        }

        val nextSnapshot = state.copy(
            settings = nextSettings,
            baselineInitialized = nextBaselineInitialized,
            seenOfferIds = mergedIds,
        )
        persistSnapshotLocked(nextSnapshot)
        nextSettings
    }

    /**
     * Records the initial historical baseline of offer IDs without marking any notification sent.
     */
    suspend fun initializeBaseline(
        offerIds: Collection<String>,
        checkedAtEpochMillis: Long,
    ) = mutex.withLock {
        val mergedIds = mergeAndTrimIds(state.seenOfferIds, sanitizeIds(offerIds))
        val nextSnapshot = state.copy(
            baselineInitialized = true,
            seenOfferIds = mergedIds,
            lastCheckedEpochMillis = checkedAtEpochMillis,
        )
        persistSnapshotLocked(nextSnapshot)
    }

    /**
     * Records a completed poll pass: promotes [currentFeedIds] and [newlyNotifiedIds] to the most
     * recent end of the bounded `seenOfferIds` list so active page-1 items never age out while
     * still visible on page 1, and updates [OverseasPromoStoreSnapshot.lastNotifiedEpochMillis]
     * when at least one new offer was notified.
     */
    suspend fun recordPollOutcome(
        currentFeedIds: Collection<String>,
        newlyNotifiedIds: Collection<String>,
        checkedAtEpochMillis: Long,
    ) = mutex.withLock {
        val cleanCurrent = sanitizeIds(currentFeedIds)
        val cleanNotified = sanitizeIds(newlyNotifiedIds)
        val mergedIds = mergeAndTrimIds(state.seenOfferIds, cleanCurrent + cleanNotified)
        val nextSnapshot = state.copy(
            baselineInitialized = true,
            seenOfferIds = mergedIds,
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

    private fun readSnapshotSync(): OverseasPromoStoreSnapshot {
        if (!storeFile.exists()) return OverseasPromoStoreSnapshot()
        return runCatching {
            val raw = storeFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) {
                OverseasPromoStoreSnapshot()
            } else {
                val parsed = json.decodeFromString<OverseasPromoStoreSnapshot>(raw)
                parsed.copy(
                    settings = parsed.settings.normalized(),
                    seenOfferIds = mergeAndTrimIds(emptyList(), parsed.seenOfferIds),
                )
            }
        }.getOrElse { OverseasPromoStoreSnapshot() }
    }

    private suspend fun persistSnapshotLocked(nextSnapshot: OverseasPromoStoreSnapshot) {
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
        const val STORE_FILE_NAME = "overseas_promo_monitor_prefs.json"
        const val DEFAULT_MAX_SEEN_OFFER_IDS = 400
        private const val MIN_SEEN_OFFER_IDS = 10
        private const val MAX_SEEN_OFFER_IDS_CAP = 1_000
    }
}
