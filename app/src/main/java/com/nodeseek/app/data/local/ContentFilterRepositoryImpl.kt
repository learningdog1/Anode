package com.nodeseek.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterEngine
import com.nodeseek.app.domain.filter.ContentFilterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

val Context.nodePulseContentFilterDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nodepulse_content_filter_prefs",
)

class ContentFilterRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ContentFilterRepository {

    constructor(
        context: Context,
        externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(context.applicationContext.nodePulseContentFilterDataStore, externalScope)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val writeMutex = Mutex()
    private var lastAppliedDiskRevision: Long = 0L

    private val _config = MutableStateFlow(ContentFilterConfig())
    override val config: StateFlow<ContentFilterConfig> = _config.asStateFlow()

    init {
        externalScope.launch {
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .collect { prefs ->
                    writeMutex.withLock {
                        val diskRevision = prefs[Keys.REVISION] ?: 0L
                        if (diskRevision >= lastAppliedDiskRevision) {
                            lastAppliedDiskRevision = diskRevision
                            _config.value = prefs.toContentFilterConfig()
                        }
                    }
                }
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        mutate { current ->
            if (current.isEnabled == enabled) current else current.copy(isEnabled = enabled)
        }
    }

    override suspend fun upsertRule(rule: ContentBlockRule) {
        mutate { current ->
            ContentFilterEngine.upsertRule(current, rule)
        }
    }

    override suspend fun removeRule(ruleId: String) {
        mutate { current ->
            ContentFilterEngine.removeRule(current, ruleId)
        }
    }

    override suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        mutate { current ->
            ContentFilterEngine.setRuleEnabled(current, ruleId, enabled)
        }
    }

    override suspend fun toggleMutedCategory(categorySlug: String) {
        mutate { current ->
            ContentFilterEngine.toggleMutedCategory(current, categorySlug)
        }
    }

    override suspend fun setMutedCategories(categorySlugs: Set<String>) {
        mutate { current ->
            ContentFilterEngine.setMutedCategories(current, categorySlugs)
        }
    }

    override suspend fun replaceConfig(config: ContentFilterConfig) {
        mutate { _ ->
            ContentFilterEngine.normalizeConfig(config)
        }
    }

    override suspend fun mergeConfig(config: ContentFilterConfig) {
        mutate { current ->
            ContentFilterEngine.mergeConfigs(current, config)
        }
    }

    private suspend fun mutate(transform: (ContentFilterConfig) -> ContentFilterConfig) {
        writeMutex.withLock {
            var updated = ContentFilterConfig()
            var nextRevision = 0L
            dataStore.edit { prefs ->
                val diskRevision = prefs[Keys.REVISION] ?: 0L
                nextRevision = maxOf(diskRevision, lastAppliedDiskRevision) + 1L
                val current = prefs.toContentFilterConfig()
                updated = ContentFilterEngine.normalizeConfig(transform(current))
                prefs[Keys.CONFIG_JSON] = json.encodeToString(updated)
                prefs[Keys.REVISION] = nextRevision
            }
            lastAppliedDiskRevision = nextRevision
            _config.value = updated
        }
    }

    private fun Preferences.toContentFilterConfig(): ContentFilterConfig {
        val raw = this[Keys.CONFIG_JSON] ?: return ContentFilterConfig()
        val decoded = runCatching {
            json.decodeFromString<ContentFilterConfig>(raw)
        }.getOrNull() ?: return ContentFilterConfig()
        return ContentFilterEngine.normalizeConfig(decoded)
    }

    private object Keys {
        val CONFIG_JSON = stringPreferencesKey("content_filter_config_json")
        val REVISION = longPreferencesKey("content_filter_revision")
    }
}
