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
import java.lang.ref.WeakReference

internal class OverseasPromoSettingsLoadGate(
    val isLoaded: () -> Boolean,
    val awaitLoaded: suspend () -> Unit,
)

internal object OverseasPromoSettingsLoadRegistry {
    private val lock = Any()
    private val entries = ArrayList<Pair<WeakReference<StateFlow<OverseasPromoMonitorSettings>>, OverseasPromoSettingsLoadGate>>()

    fun register(
        flow: StateFlow<OverseasPromoMonitorSettings>,
        gate: OverseasPromoSettingsLoadGate,
    ) {
        synchronized(lock) {
            entries.removeAll { it.first.get() == null || it.first.get() === flow }
            entries.add(WeakReference(flow) to gate)
        }
    }

    fun find(flow: Any): OverseasPromoSettingsLoadGate? {
        synchronized(lock) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val (ref, gate) = iterator.next()
                val target = ref.get()
                if (target == null) {
                    iterator.remove()
                } else if (target === flow) {
                    return gate
                }
            }
            return null
        }
    }
}

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

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var state: OverseasPromoStoreSnapshot = OverseasPromoStoreSnapshot()

    private val _settingsFlow = MutableStateFlow(state.settings.normalized())
    val settingsFlow: StateFlow<OverseasPromoMonitorSettings> = _settingsFlow.asStateFlow().also { flow ->
        OverseasPromoSettingsLoadRegistry.register(
            flow = flow,
            gate = OverseasPromoSettingsLoadGate(
                isLoaded = { loaded },
                awaitLoaded = { ensureLoaded() },
            ),
        )
    }

    val currentBaselineInitialized: Boolean
        get() = state.baselineInitialized

    val currentLastCheckedEpochMillis: Long
        get() = state.lastCheckedEpochMillis

    val currentLastNotifiedEpochMillis: Long
        get() = state.lastNotifiedEpochMillis

    suspend fun ensureLoaded() {
        mutex.withLock {
            ensureLoadedLocked()
        }
    }

    suspend fun getSettings(): OverseasPromoMonitorSettings =
        mutex.withLock {
            ensureLoadedLocked()
            _settingsFlow.value
        }

    suspend fun isBaselineInitialized(): Boolean =
        mutex.withLock {
            ensureLoadedLocked()
            state.baselineInitialized
        }

    suspend fun getSeenOfferIds(): Set<String> =
        mutex.withLock {
            ensureLoadedLocked()
            LinkedHashSet(state.seenOfferIds)
        }

    suspend fun updateSettings(
        transform: (OverseasPromoMonitorSettings) -> OverseasPromoMonitorSettings,
    ): OverseasPromoMonitorSettings = mutex.withLock {
        ensureLoadedLocked()
        val nextSettings = transform(_settingsFlow.value).normalized()
        val nextSnapshot = state.copy(settings = nextSettings)
        persistSnapshotLocked(nextSnapshot)
        nextSettings
    }

    suspend fun exportSettingsForBackup(): OverseasPromoMonitorSettings = getSettings()

    suspend fun restoreSettingsFromBackup(settings: OverseasPromoMonitorSettings) {
        mutex.withLock {
            ensureLoadedLocked()
            val normalized = settings.normalized()
            val nextBaselineInitialized = if (normalized.notificationsEnabled) {
                state.baselineInitialized
            } else {
                false
            }
            val nextSnapshot = state.copy(
                settings = normalized,
                baselineInitialized = nextBaselineInitialized,
            )
            persistSnapshotLocked(nextSnapshot)
        }
    }

    /**
     * Updates [OverseasPromoMonitorSettings.notificationsEnabled].
     *
     * Disabling notifications clears [OverseasPromoStoreSnapshot.baselineInitialized] so that when
     * re-enabled later, historical promotions accumulated while paused never flood notifications.
     * When re-enabling (`false -> true`) with fresh online [seedBaselineIds] already displayed on
     * screen, those IDs are merged into `seenOfferIds` and re-establish the baseline immediately;
     * otherwise the next online poll silently re-establishes the baseline.
     */
    suspend fun setNotificationsEnabled(
        enabled: Boolean,
        seedBaselineIds: Collection<String> = emptyList(),
    ): OverseasPromoMonitorSettings = mutex.withLock {
        ensureLoadedLocked()
        val previousEnabled = _settingsFlow.value.notificationsEnabled
        val nextSettings = _settingsFlow.value.copy(notificationsEnabled = enabled).normalized()
        val cleanedSeeds = sanitizeIds(seedBaselineIds)

        val mergedIds = if (enabled && !previousEnabled && cleanedSeeds.isNotEmpty()) {
            mergeAndTrimIds(state.seenOfferIds, cleanedSeeds)
        } else {
            state.seenOfferIds
        }
        val nextBaselineInitialized = when {
            !enabled -> false
            !previousEnabled -> cleanedSeeds.isNotEmpty()
            else -> state.baselineInitialized
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
        ensureLoadedLocked()
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
        ensureLoadedLocked()
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

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val diskSnapshot = withContext(ioDispatcher) { readSnapshotSync() }
        loaded = true
        state = diskSnapshot
        _settingsFlow.value = diskSnapshot.settings.normalized()
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
