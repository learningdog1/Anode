package com.nodeseek.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nodeseek.app.domain.backup.AppSettingsBackupDto
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

val Context.nodePulseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nodepulse_settings",
)

class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val secretCipher: SettingsSecretCipher = SettingsSecretCipherFactory.createDefault(),
) : SettingsRepository {

    constructor(
        context: Context,
        externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(
        dataStore = context.applicationContext.nodePulseDataStore,
        externalScope = externalScope,
        secretCipher = SettingsSecretCipherFactory.createDefault(context.applicationContext),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val safePreferencesFlow = dataStore.data.catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }

    private val _settings = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val initialSettingsLoaded = CompletableDeferred<AppSettings>()

    private val editMutex = Mutex()
    private val stateLock = Any()
    private var editSeq: Long = 0L
    private var boundAccountUid: Long? = null
    private var cachedOrdersByUid: Map<Long, List<String>> = emptyMap()

    override val recentSearchQueries: StateFlow<List<String>> = safePreferencesFlow
        .map { prefs ->
            prefs[Keys.RECENT_SEARCH_QUERIES]?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
            } ?: emptyList()
        }
        .stateIn(
            scope = externalScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    init {
        val flow = safePreferencesFlow
        val cipher = secretCipher
        externalScope.launch {
            try {
                flow.collect { prefs ->
                    val base = prefs.toBaseAppSettings()
                    val orders = decodeAllOrders(prefs)
                    val snapshotSeq = prefs[Keys.SETTINGS_EDIT_SEQ] ?: 0L
                    synchronized(stateLock) {
                        val effectiveUid = resolveEffectiveUidLocked(prefs)
                        val loaded = base.copy(homepageCategoryOrder = orders[effectiveUid].orEmpty())
                        if (snapshotSeq >= editSeq) {
                            cachedOrdersByUid = orders
                            if (snapshotSeq > editSeq) {
                                editSeq = snapshotSeq
                            }
                            _settings.value = loaded
                            initialSettingsLoaded.complete(loaded)
                        }
                    }
                }
            } finally {
                if (!initialSettingsLoaded.isCompleted) {
                    initialSettingsLoaded.complete(_settings.value)
                }
            }
        }
        externalScope.launch {
            migratePlaintextSecretsIfNeeded(flow, cipher)
        }
    }

    override suspend fun awaitLoadedSettings(): AppSettings {
        initialSettingsLoaded.await()
        return _settings.value
    }

    private fun resolveEffectiveUidLocked(prefs: Preferences): Long =
        boundAccountUid ?: (prefs[Keys.ACTIVE_HOMEPAGE_CATEGORY_UID] ?: 0L).coerceAtLeast(0L)

    private suspend fun editAndSyncSettings(
        transform: (MutablePreferences) -> Unit,
    ) {
        editMutex.withLock {
            var assignedSeq = 0L
            val updated = dataStore.edit { mutablePrefs ->
                transform(mutablePrefs)
                val diskSeq = mutablePrefs[Keys.SETTINGS_EDIT_SEQ] ?: 0L
                val baseSeq = synchronized(stateLock) { maxOf(diskSeq, editSeq) }
                assignedSeq = baseSeq + 1L
                mutablePrefs[Keys.SETTINGS_EDIT_SEQ] = assignedSeq
            }
            val base = updated.toBaseAppSettings()
            val orders = decodeAllOrders(updated)
            val mapped = synchronized(stateLock) {
                cachedOrdersByUid = orders
                val effectiveUid = resolveEffectiveUidLocked(updated)
                val result = base.copy(homepageCategoryOrder = orders[effectiveUid].orEmpty())
                if (assignedSeq > editSeq) {
                    editSeq = assignedSeq
                }
                _settings.value = result
                result
            }
            initialSettingsLoaded.complete(mapped)
        }
    }

    private suspend fun migratePlaintextSecretsIfNeeded(
        prefsFlow: kotlinx.coroutines.flow.Flow<Preferences>,
        cipher: SettingsSecretCipher,
    ) {
        try {
            val prefs = prefsFlow.first()
            val rawNodeImage = prefs[Keys.NODE_IMAGE_API_KEY].orEmpty().trim()
            val rawSeekMail = prefs[Keys.SEEK_MAIL_API_KEY].orEmpty().trim()
            val needsNodeImageMigration = rawNodeImage.isNotEmpty() && !cipher.isEncryptedEnvelope(rawNodeImage)
            val needsSeekMailMigration = rawSeekMail.isNotEmpty() && !cipher.isEncryptedEnvelope(rawSeekMail)
            if (!needsNodeImageMigration && !needsSeekMailMigration) return
            editAndSyncSettings { mutablePrefs ->
                val curNodeImage = mutablePrefs[Keys.NODE_IMAGE_API_KEY].orEmpty().trim()
                if (curNodeImage.isNotEmpty() && !cipher.isEncryptedEnvelope(curNodeImage)) {
                    mutablePrefs[Keys.NODE_IMAGE_API_KEY] = cipher.encrypt(curNodeImage)
                }
                val curSeekMail = mutablePrefs[Keys.SEEK_MAIL_API_KEY].orEmpty().trim()
                if (curSeekMail.isNotEmpty() && !cipher.isEncryptedEnvelope(curSeekMail)) {
                    mutablePrefs[Keys.SEEK_MAIL_API_KEY] = cipher.encrypt(curSeekMail)
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            // Best-effort legacy secret migration; plaintext remains readable until next save.
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        editAndSyncSettings { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    override suspend fun setPurePitchAmoled(enabled: Boolean) {
        editAndSyncSettings { prefs ->
            prefs[Keys.PURE_PITCH_AMOLED] = enabled
        }
    }

    override suspend fun setUseDynamicColor(enabled: Boolean) {
        editAndSyncSettings { prefs ->
            prefs[Keys.USE_DYNAMIC_COLOR] = enabled
        }
    }

    override suspend fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        editAndSyncSettings { prefs ->
            prefs[Keys.FONT_SCALE] = clamped
        }
    }

    override suspend fun setHomeCardScale(scale: Float) {
        val clamped = scale.coerceIn(MIN_HOME_CARD_SCALE, MAX_HOME_CARD_SCALE)
        editAndSyncSettings { prefs ->
            prefs[Keys.HOME_CARD_SCALE] = clamped
        }
    }

    override suspend fun setPreferRandomCheckIn(preferRandom: Boolean) {
        editAndSyncSettings { prefs ->
            prefs[Keys.PREFER_RANDOM_CHECK_IN] = preferRandom
        }
    }

    override suspend fun setPinnedCategorySlugs(slugs: Set<String>) {
        val cleaned = slugs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        editAndSyncSettings { prefs ->
            prefs[Keys.PINNED_CATEGORY_SLUGS] = json.encodeToString(cleaned)
        }
    }

    override suspend fun getHomepageCategoryOrder(accountUid: Long): List<String> {
        initialSettingsLoaded.await()
        val normalizedUid = accountUid.coerceAtLeast(0L)
        return synchronized(stateLock) {
            cachedOrdersByUid[normalizedUid].orEmpty()
        }
    }

    override suspend fun setHomepageCategoryOrder(slugs: List<String>) {
        val cleaned = slugs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        editAndSyncSettings { prefs ->
            val targetUid = synchronized(stateLock) { resolveEffectiveUidLocked(prefs) }
            val currentOrders = decodeAllOrders(prefs).toMutableMap()
            if (cleaned.isEmpty()) {
                currentOrders.remove(targetUid)
            } else {
                currentOrders[targetUid] = cleaned
            }
            writeOrdersMap(prefs, currentOrders)
            if (synchronized(stateLock) { boundAccountUid } != null) {
                prefs[Keys.ACTIVE_HOMEPAGE_CATEGORY_UID] = targetUid
            }
        }
    }

    override suspend fun setHomepageCategoryOrder(accountUid: Long, slugs: List<String>) {
        val normalizedUid = accountUid.coerceAtLeast(0L)
        val cleaned = slugs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        editAndSyncSettings { prefs ->
            val currentOrders = decodeAllOrders(prefs).toMutableMap()
            if (cleaned.isEmpty()) {
                currentOrders.remove(normalizedUid)
            } else {
                currentOrders[normalizedUid] = cleaned
            }
            writeOrdersMap(prefs, currentOrders)
            if (synchronized(stateLock) { boundAccountUid } == normalizedUid) {
                prefs[Keys.ACTIVE_HOMEPAGE_CATEGORY_UID] = normalizedUid
            }
        }
    }

    override suspend fun setActiveHomepageCategoryAccountUid(accountUid: Long) {
        val normalizedUid = accountUid.coerceAtLeast(0L)
        synchronized(stateLock) {
            boundAccountUid = normalizedUid
            if (initialSettingsLoaded.isCompleted) {
                _settings.value = _settings.value.copy(
                    homepageCategoryOrder = cachedOrdersByUid[normalizedUid].orEmpty(),
                )
            }
        }
        editAndSyncSettings { prefs ->
            prefs[Keys.ACTIVE_HOMEPAGE_CATEGORY_UID] = normalizedUid
        }
    }

    override fun onActiveAccountChanged(accountUid: Long) {
        val normalizedUid = accountUid.coerceAtLeast(0L)
        val shouldPersist = synchronized(stateLock) {
            val prev = boundAccountUid
            boundAccountUid = normalizedUid
            if (initialSettingsLoaded.isCompleted) {
                _settings.value = _settings.value.copy(
                    homepageCategoryOrder = cachedOrdersByUid[normalizedUid].orEmpty(),
                )
            }
            prev != normalizedUid
        }
        if (shouldPersist) {
            externalScope.launch {
                runCatching {
                    editAndSyncSettings { prefs ->
                        val latestUid = synchronized(stateLock) { boundAccountUid } ?: normalizedUid
                        prefs[Keys.ACTIVE_HOMEPAGE_CATEGORY_UID] = latestUid
                    }
                }
            }
        }
    }

    override suspend fun togglePinnedCategory(slug: String) {
        val normalized = slug.trim()
        if (normalized.isEmpty()) return
        editAndSyncSettings { prefs ->
            val current = decodePinnedSlugs(prefs[Keys.PINNED_CATEGORY_SLUGS]).toMutableSet()
            if (!current.remove(normalized)) {
                current.add(normalized)
            }
            prefs[Keys.PINNED_CATEGORY_SLUGS] = json.encodeToString(current.toList())
        }
    }

    override suspend fun setIncludeBlockedTopics(include: Boolean) {
        editAndSyncSettings { prefs ->
            prefs[Keys.INCLUDE_BLOCKED_TOPICS] = include
        }
    }

    override suspend fun setPreferRssFallback(preferRss: Boolean) {
        editAndSyncSettings { prefs ->
            prefs[Keys.PREFER_RSS_FALLBACK] = preferRss
        }
    }

    override suspend fun setNodeImageApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        editAndSyncSettings { prefs ->
            prefs[Keys.NODE_IMAGE_API_KEY] = if (trimmed.isEmpty()) "" else secretCipher.encrypt(trimmed)
        }
    }

    override suspend fun setSeekMailApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        editAndSyncSettings { prefs ->
            prefs[Keys.SEEK_MAIL_API_KEY] = if (trimmed.isEmpty()) "" else secretCipher.encrypt(trimmed)
        }
    }

    override suspend fun setAutoCheckUpdates(enabled: Boolean) {
        editAndSyncSettings { prefs ->
            prefs[Keys.AUTO_CHECK_UPDATES] = enabled
        }
    }

    override suspend fun setIncludePrereleaseUpdates(enabled: Boolean) {
        editAndSyncSettings { prefs ->
            prefs[Keys.INCLUDE_PRERELEASE_UPDATES] = enabled
        }
    }

    override suspend fun setIgnoredUpdateTag(tag: String) {
        editAndSyncSettings { prefs ->
            prefs[Keys.IGNORED_UPDATE_TAG] = tag.trim()
        }
    }

    override suspend fun setAwayDigestEnabled(enabled: Boolean) {
        editAndSyncSettings { prefs ->
            prefs[Keys.AWAY_DIGEST_ENABLED] = enabled
        }
    }

    override suspend fun setAwayDigestTriggerMode(mode: AwayDigestTriggerMode) {
        editAndSyncSettings { prefs ->
            prefs[Keys.AWAY_DIGEST_TRIGGER_MODE] = mode.name
        }
    }

    override suspend fun setAwayDigestThresholdMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(
            AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
            AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
        )
        editAndSyncSettings { prefs ->
            prefs[Keys.AWAY_DIGEST_THRESHOLD_MINUTES] = clamped
        }
    }

    override suspend fun addRecentSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val existing = prefs[Keys.RECENT_SEARCH_QUERIES]?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
            }.orEmpty()
            val updated = buildList {
                add(trimmed)
                addAll(existing.filterNot { it.equals(trimmed, ignoreCase = true) })
            }.take(MAX_RECENT_SEARCHES)
            prefs[Keys.RECENT_SEARCH_QUERIES] = json.encodeToString(updated)
        }
    }

    override suspend fun removeRecentSearchQuery(query: String) {
        dataStore.edit { prefs ->
            val existing = prefs[Keys.RECENT_SEARCH_QUERIES]?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
            }.orEmpty()
            val updated = existing.filterNot { it == query }
            prefs[Keys.RECENT_SEARCH_QUERIES] = json.encodeToString(updated)
        }
    }

    override suspend fun clearRecentSearchQueries() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.RECENT_SEARCH_QUERIES)
        }
    }

    override suspend fun exportSettingsForBackup(includeApiKeys: Boolean): AppSettingsBackupDto {
        val prefs = safePreferencesFlow.first()
        val current = prefs.toAppSettings()
        val queries = decodeRecentSearchQueries(prefs[Keys.RECENT_SEARCH_QUERIES])
        return AppSettingsBackupDto(
            themeMode = current.themeMode,
            purePitchAmoled = current.purePitchAmoled,
            useDynamicColor = current.useDynamicColor,
            fontScale = current.fontScale,
            homeCardScale = current.homeCardScale,
            preferRandomCheckIn = current.preferRandomCheckIn,
            pinnedCategorySlugs = current.pinnedCategorySlugs,
            homepageCategoryOrder = current.homepageCategoryOrder,
            includeBlockedTopics = current.includeBlockedTopics,
            preferRssFallback = current.preferRssFallback,
            nodeImageApiKey = if (includeApiKeys) current.nodeImageApiKey else "",
            seekMailApiKey = if (includeApiKeys) current.seekMailApiKey else "",
            autoCheckUpdates = current.autoCheckUpdates,
            includePrereleaseUpdates = current.includePrereleaseUpdates,
            ignoredUpdateTag = current.ignoredUpdateTag,
            awayDigestEnabled = current.awayDigestEnabled,
            awayDigestTriggerMode = current.awayDigestTriggerMode,
            awayDigestThresholdMinutes = current.awayDigestThresholdMinutes,
            recentSearchQueries = queries,
        )
    }

    override suspend fun restoreSettingsFromBackup(
        backup: AppSettingsBackupDto,
        merge: Boolean,
    ) {
        editAndSyncSettings { prefs ->
            val current = prefs.toAppSettings()
            val targetUid = synchronized(stateLock) { resolveEffectiveUidLocked(prefs) }
            val currentOrders = decodeAllOrders(prefs).toMutableMap()
            val currentOrderForTarget = currentOrders[targetUid].orEmpty()
            val existingQueries = decodeRecentSearchQueries(prefs[Keys.RECENT_SEARCH_QUERIES])

            val resolvedNodeImageKey = if (
                merge && backup.nodeImageApiKey.isBlank() && current.nodeImageApiKey.isNotBlank()
            ) {
                current.nodeImageApiKey
            } else {
                backup.nodeImageApiKey.trim()
            }

            val resolvedSeekMailKey = if (
                merge && backup.seekMailApiKey.isBlank() && current.seekMailApiKey.isNotBlank()
            ) {
                current.seekMailApiKey
            } else {
                backup.seekMailApiKey.trim()
            }

            val resolvedIgnoredTag = if (
                merge && backup.ignoredUpdateTag.isBlank() && current.ignoredUpdateTag.isNotBlank()
            ) {
                current.ignoredUpdateTag
            } else {
                backup.ignoredUpdateTag.trim()
            }

            val hasExplicitPinned = prefs[Keys.PINNED_CATEGORY_SLUGS] != null
            val cleanedPinned = (if (merge && hasExplicitPinned) {
                current.pinnedCategorySlugs + backup.pinnedCategorySlugs
            } else {
                backup.pinnedCategorySlugs
            })
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            val cleanedBackupOrder = backup.homepageCategoryOrder
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val resolvedCategoryOrder = if (merge && currentOrderForTarget.isNotEmpty()) {
                (currentOrderForTarget + cleanedBackupOrder).distinct()
            } else {
                cleanedBackupOrder
            }

            val resolvedQueries = if (merge) {
                (backup.recentSearchQueries + existingQueries)
            } else {
                backup.recentSearchQueries
            }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .take(MAX_RECENT_SEARCHES)

            prefs[Keys.THEME_MODE] = backup.themeMode.name
            prefs[Keys.PURE_PITCH_AMOLED] = backup.purePitchAmoled
            prefs[Keys.USE_DYNAMIC_COLOR] = backup.useDynamicColor
            prefs[Keys.FONT_SCALE] = backup.fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
            prefs[Keys.HOME_CARD_SCALE] = backup.homeCardScale.coerceIn(MIN_HOME_CARD_SCALE, MAX_HOME_CARD_SCALE)
            prefs[Keys.PREFER_RANDOM_CHECK_IN] = backup.preferRandomCheckIn
            prefs[Keys.PINNED_CATEGORY_SLUGS] = json.encodeToString(cleanedPinned)
            if (resolvedCategoryOrder.isEmpty()) {
                currentOrders.remove(targetUid)
            } else {
                currentOrders[targetUid] = resolvedCategoryOrder
            }
            writeOrdersMap(prefs, currentOrders)
            if (synchronized(stateLock) { boundAccountUid } != null) {
                prefs[Keys.ACTIVE_HOMEPAGE_CATEGORY_UID] = targetUid
            }
            prefs[Keys.INCLUDE_BLOCKED_TOPICS] = backup.includeBlockedTopics
            prefs[Keys.PREFER_RSS_FALLBACK] = backup.preferRssFallback
            prefs[Keys.NODE_IMAGE_API_KEY] = if (resolvedNodeImageKey.isEmpty()) "" else secretCipher.encrypt(resolvedNodeImageKey)
            prefs[Keys.SEEK_MAIL_API_KEY] = if (resolvedSeekMailKey.isEmpty()) "" else secretCipher.encrypt(resolvedSeekMailKey)
            prefs[Keys.AUTO_CHECK_UPDATES] = backup.autoCheckUpdates
            prefs[Keys.INCLUDE_PRERELEASE_UPDATES] = backup.includePrereleaseUpdates
            prefs[Keys.IGNORED_UPDATE_TAG] = resolvedIgnoredTag
            prefs[Keys.AWAY_DIGEST_ENABLED] = backup.awayDigestEnabled
            prefs[Keys.AWAY_DIGEST_TRIGGER_MODE] = backup.awayDigestTriggerMode.name
            prefs[Keys.AWAY_DIGEST_THRESHOLD_MINUTES] = backup.awayDigestThresholdMinutes.coerceIn(
                AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
                AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
            )
            if (resolvedQueries.isEmpty()) {
                prefs.remove(Keys.RECENT_SEARCH_QUERIES)
            } else {
                prefs[Keys.RECENT_SEARCH_QUERIES] = json.encodeToString(resolvedQueries)
            }
        }
    }

    private fun decodeRecentSearchQueries(raw: String?): List<String> =
        raw?.let { encoded ->
            runCatching { json.decodeFromString<List<String>>(encoded) }.getOrNull()
        } ?: emptyList()

    private fun Preferences.toAppSettings(): AppSettings {
        val base = toBaseAppSettings()
        val orders = decodeAllOrders(this)
        return synchronized(stateLock) {
            val effectiveUid = resolveEffectiveUidLocked(this)
            base.copy(homepageCategoryOrder = orders[effectiveUid].orEmpty())
        }
    }

    private fun Preferences.toBaseAppSettings(): AppSettings {
        val mode = this[Keys.THEME_MODE]?.let { raw ->
            runCatching { ThemeMode.valueOf(raw) }.getOrNull()
        } ?: ThemeMode.SYSTEM

        val awayTriggerMode = this[Keys.AWAY_DIGEST_TRIGGER_MODE]?.let { raw ->
            runCatching { AwayDigestTriggerMode.valueOf(raw) }.getOrNull()
        } ?: AwayDigestTriggerMode.CUSTOM_DURATION

        return AppSettings(
            themeMode = mode,
            purePitchAmoled = this[Keys.PURE_PITCH_AMOLED] ?: false,
            useDynamicColor = this[Keys.USE_DYNAMIC_COLOR] ?: false,
            fontScale = (this[Keys.FONT_SCALE] ?: AppSettings.DEFAULT_FONT_SCALE).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
            homeCardScale = (this[Keys.HOME_CARD_SCALE] ?: AppSettings.DEFAULT_HOME_CARD_SCALE).coerceIn(
                MIN_HOME_CARD_SCALE,
                MAX_HOME_CARD_SCALE,
            ),
            preferRandomCheckIn = this[Keys.PREFER_RANDOM_CHECK_IN] ?: false,
            pinnedCategorySlugs = decodePinnedSlugs(this[Keys.PINNED_CATEGORY_SLUGS]),
            homepageCategoryOrder = emptyList(),
            includeBlockedTopics = this[Keys.INCLUDE_BLOCKED_TOPICS] ?: false,
            preferRssFallback = this[Keys.PREFER_RSS_FALLBACK] ?: false,
            nodeImageApiKey = secretCipher.decrypt(this[Keys.NODE_IMAGE_API_KEY] ?: ""),
            seekMailApiKey = secretCipher.decrypt(this[Keys.SEEK_MAIL_API_KEY] ?: ""),
            autoCheckUpdates = this[Keys.AUTO_CHECK_UPDATES] ?: true,
            includePrereleaseUpdates = this[Keys.INCLUDE_PRERELEASE_UPDATES] ?: false,
            ignoredUpdateTag = this[Keys.IGNORED_UPDATE_TAG] ?: "",
            awayDigestEnabled = this[Keys.AWAY_DIGEST_ENABLED] ?: true,
            awayDigestTriggerMode = awayTriggerMode,
            awayDigestThresholdMinutes = (
                this[Keys.AWAY_DIGEST_THRESHOLD_MINUTES] ?: AppSettings.DEFAULT_AWAY_DIGEST_THRESHOLD_MINUTES
                ).coerceIn(
                AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
                AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
            ),
        )
    }

    private fun decodePinnedSlugs(raw: String?): Set<String> {
        if (raw == null) return AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS
        val decoded = runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
            ?: return AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS
        return LinkedHashSet(decoded)
    }

    private fun decodeHomepageCategoryOrder(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val decoded = runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
            ?: return emptyList()
        return decoded.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun decodeAllOrders(prefs: Preferences): Map<Long, List<String>> {
        val rawByUid = prefs[Keys.HOMEPAGE_CATEGORY_ORDER_BY_UID]
        if (rawByUid != null) {
            val decoded = runCatching {
                json.decodeFromString<Map<String, List<String>>>(rawByUid)
            }.getOrNull() ?: return emptyMap()
            val result = LinkedHashMap<Long, List<String>>()
            for ((uidKey, slugs) in decoded) {
                val uid = uidKey.toLongOrNull() ?: continue
                if (uid < 0L) continue
                val cleaned = slugs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (cleaned.isNotEmpty()) {
                    result[uid] = cleaned
                }
            }
            return result
        }
        val legacyGuestOrder = decodeHomepageCategoryOrder(prefs[Keys.HOMEPAGE_CATEGORY_ORDER])
        return if (legacyGuestOrder.isEmpty()) emptyMap() else mapOf(0L to legacyGuestOrder)
    }

    private fun writeOrdersMap(
        prefs: MutablePreferences,
        ordersByUid: Map<Long, List<String>>,
    ) {
        val normalized = LinkedHashMap<String, List<String>>()
        for ((uid, slugs) in ordersByUid) {
            if (uid < 0L) continue
            val cleaned = slugs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (cleaned.isNotEmpty()) {
                normalized[uid.toString()] = cleaned
            }
        }
        prefs[Keys.HOMEPAGE_CATEGORY_ORDER_BY_UID] = json.encodeToString(normalized)
        val guestOrder = normalized["0"].orEmpty()
        if (guestOrder.isEmpty()) {
            prefs.remove(Keys.HOMEPAGE_CATEGORY_ORDER)
        } else {
            prefs[Keys.HOMEPAGE_CATEGORY_ORDER] = json.encodeToString(guestOrder)
        }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PURE_PITCH_AMOLED = booleanPreferencesKey("pure_pitch_amoled")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val HOME_CARD_SCALE = floatPreferencesKey("home_card_scale")
        val PREFER_RANDOM_CHECK_IN = booleanPreferencesKey("prefer_random_check_in")
        val PINNED_CATEGORY_SLUGS = stringPreferencesKey("pinned_category_slugs")
        val HOMEPAGE_CATEGORY_ORDER = stringPreferencesKey("homepage_category_order")
        val HOMEPAGE_CATEGORY_ORDER_BY_UID = stringPreferencesKey("homepage_category_order_by_uid")
        val ACTIVE_HOMEPAGE_CATEGORY_UID = longPreferencesKey("active_homepage_category_uid")
        val INCLUDE_BLOCKED_TOPICS = booleanPreferencesKey("include_blocked_topics")
        val PREFER_RSS_FALLBACK = booleanPreferencesKey("prefer_rss_fallback")
        val NODE_IMAGE_API_KEY = stringPreferencesKey("node_image_api_key")
        val SEEK_MAIL_API_KEY = stringPreferencesKey("seek_mail_api_key")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val INCLUDE_PRERELEASE_UPDATES = booleanPreferencesKey("include_prerelease_updates")
        val IGNORED_UPDATE_TAG = stringPreferencesKey("ignored_update_tag")
        val AWAY_DIGEST_ENABLED = booleanPreferencesKey("away_digest_enabled")
        val AWAY_DIGEST_TRIGGER_MODE = stringPreferencesKey("away_digest_trigger_mode")
        val AWAY_DIGEST_THRESHOLD_MINUTES = intPreferencesKey("away_digest_threshold_minutes")
        val RECENT_SEARCH_QUERIES = stringPreferencesKey("recent_search_queries")
        val SETTINGS_EDIT_SEQ = longPreferencesKey("settings_edit_seq")
    }

    companion object {
        const val MIN_FONT_SCALE = AppSettings.MIN_FONT_SCALE
        const val MAX_FONT_SCALE = AppSettings.MAX_FONT_SCALE
        const val MIN_HOME_CARD_SCALE = AppSettings.MIN_HOME_CARD_SCALE
        const val MAX_HOME_CARD_SCALE = AppSettings.MAX_HOME_CARD_SCALE
        const val MAX_RECENT_SEARCHES = 12
    }
}
