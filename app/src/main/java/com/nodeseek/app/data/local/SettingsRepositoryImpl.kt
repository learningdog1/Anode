package com.nodeseek.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nodeseek.app.domain.backup.AppSettingsBackupDto
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

val Context.nodePulseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nodepulse_settings",
)

class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SettingsRepository {

    constructor(
        context: Context,
        externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(context.applicationContext.nodePulseDataStore, externalScope)

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

    override val settings: StateFlow<AppSettings> = safePreferencesFlow
        .map { prefs -> prefs.toAppSettings() }
        .stateIn(
            scope = externalScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings(),
        )

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

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    override suspend fun setPurePitchAmoled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.PURE_PITCH_AMOLED] = enabled
        }
    }

    override suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.USE_DYNAMIC_COLOR] = enabled
        }
    }

    override suspend fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        dataStore.edit { prefs ->
            prefs[Keys.FONT_SCALE] = clamped
        }
    }

    override suspend fun setPreferRandomCheckIn(preferRandom: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.PREFER_RANDOM_CHECK_IN] = preferRandom
        }
    }

    override suspend fun setPinnedCategorySlugs(slugs: Set<String>) {
        val cleaned = slugs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        dataStore.edit { prefs ->
            prefs[Keys.PINNED_CATEGORY_SLUGS] = json.encodeToString(cleaned)
        }
    }

    override suspend fun togglePinnedCategory(slug: String) {
        val normalized = slug.trim()
        if (normalized.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodePinnedSlugs(prefs[Keys.PINNED_CATEGORY_SLUGS]).toMutableSet()
            if (!current.remove(normalized)) {
                current.add(normalized)
            }
            prefs[Keys.PINNED_CATEGORY_SLUGS] = json.encodeToString(current.toList())
        }
    }

    override suspend fun setIncludeBlockedTopics(include: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.INCLUDE_BLOCKED_TOPICS] = include
        }
    }

    override suspend fun setPreferRssFallback(preferRss: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.PREFER_RSS_FALLBACK] = preferRss
        }
    }

    override suspend fun setNodeImageApiKey(apiKey: String) {
        dataStore.edit { prefs ->
            prefs[Keys.NODE_IMAGE_API_KEY] = apiKey.trim()
        }
    }

    override suspend fun setSeekMailApiKey(apiKey: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SEEK_MAIL_API_KEY] = apiKey.trim()
        }
    }

    override suspend fun setAutoCheckUpdates(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_CHECK_UPDATES] = enabled
        }
    }

    override suspend fun setIncludePrereleaseUpdates(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.INCLUDE_PRERELEASE_UPDATES] = enabled
        }
    }

    override suspend fun setIgnoredUpdateTag(tag: String) {
        dataStore.edit { prefs ->
            prefs[Keys.IGNORED_UPDATE_TAG] = tag.trim()
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
            preferRandomCheckIn = current.preferRandomCheckIn,
            pinnedCategorySlugs = current.pinnedCategorySlugs,
            includeBlockedTopics = current.includeBlockedTopics,
            preferRssFallback = current.preferRssFallback,
            nodeImageApiKey = if (includeApiKeys) current.nodeImageApiKey else "",
            seekMailApiKey = if (includeApiKeys) current.seekMailApiKey else "",
            autoCheckUpdates = current.autoCheckUpdates,
            includePrereleaseUpdates = current.includePrereleaseUpdates,
            ignoredUpdateTag = current.ignoredUpdateTag,
            recentSearchQueries = queries,
        )
    }

    override suspend fun restoreSettingsFromBackup(
        backup: AppSettingsBackupDto,
        merge: Boolean,
    ) {
        dataStore.edit { prefs ->
            val current = prefs.toAppSettings()
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

            val cleanedPinned = backup.pinnedCategorySlugs
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

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
            prefs[Keys.PREFER_RANDOM_CHECK_IN] = backup.preferRandomCheckIn
            prefs[Keys.PINNED_CATEGORY_SLUGS] = json.encodeToString(cleanedPinned)
            prefs[Keys.INCLUDE_BLOCKED_TOPICS] = backup.includeBlockedTopics
            prefs[Keys.PREFER_RSS_FALLBACK] = backup.preferRssFallback
            prefs[Keys.NODE_IMAGE_API_KEY] = resolvedNodeImageKey
            prefs[Keys.SEEK_MAIL_API_KEY] = resolvedSeekMailKey
            prefs[Keys.AUTO_CHECK_UPDATES] = backup.autoCheckUpdates
            prefs[Keys.INCLUDE_PRERELEASE_UPDATES] = backup.includePrereleaseUpdates
            prefs[Keys.IGNORED_UPDATE_TAG] = resolvedIgnoredTag
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
        val mode = this[Keys.THEME_MODE]?.let { raw ->
            runCatching { ThemeMode.valueOf(raw) }.getOrNull()
        } ?: ThemeMode.SYSTEM

        return AppSettings(
            themeMode = mode,
            purePitchAmoled = this[Keys.PURE_PITCH_AMOLED] ?: false,
            useDynamicColor = this[Keys.USE_DYNAMIC_COLOR] ?: false,
            fontScale = (this[Keys.FONT_SCALE] ?: AppSettings.DEFAULT_FONT_SCALE).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
            preferRandomCheckIn = this[Keys.PREFER_RANDOM_CHECK_IN] ?: false,
            pinnedCategorySlugs = decodePinnedSlugs(this[Keys.PINNED_CATEGORY_SLUGS]),
            includeBlockedTopics = this[Keys.INCLUDE_BLOCKED_TOPICS] ?: false,
            preferRssFallback = this[Keys.PREFER_RSS_FALLBACK] ?: false,
            nodeImageApiKey = this[Keys.NODE_IMAGE_API_KEY] ?: "",
            seekMailApiKey = this[Keys.SEEK_MAIL_API_KEY] ?: "",
            autoCheckUpdates = this[Keys.AUTO_CHECK_UPDATES] ?: true,
            includePrereleaseUpdates = this[Keys.INCLUDE_PRERELEASE_UPDATES] ?: false,
            ignoredUpdateTag = this[Keys.IGNORED_UPDATE_TAG] ?: "",
        )
    }

    private fun decodePinnedSlugs(raw: String?): Set<String> {
        if (raw == null) return AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS
        val decoded = runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
            ?: return AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS
        return LinkedHashSet(decoded)
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PURE_PITCH_AMOLED = booleanPreferencesKey("pure_pitch_amoled")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val PREFER_RANDOM_CHECK_IN = booleanPreferencesKey("prefer_random_check_in")
        val PINNED_CATEGORY_SLUGS = stringPreferencesKey("pinned_category_slugs")
        val INCLUDE_BLOCKED_TOPICS = booleanPreferencesKey("include_blocked_topics")
        val PREFER_RSS_FALLBACK = booleanPreferencesKey("prefer_rss_fallback")
        val NODE_IMAGE_API_KEY = stringPreferencesKey("node_image_api_key")
        val SEEK_MAIL_API_KEY = stringPreferencesKey("seek_mail_api_key")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val INCLUDE_PRERELEASE_UPDATES = booleanPreferencesKey("include_prerelease_updates")
        val IGNORED_UPDATE_TAG = stringPreferencesKey("ignored_update_tag")
        val RECENT_SEARCH_QUERIES = stringPreferencesKey("recent_search_queries")
    }

    companion object {
        const val MIN_FONT_SCALE = 0.85f
        const val MAX_FONT_SCALE = 1.35f
        const val MAX_RECENT_SEARCHES = 12
    }
}
