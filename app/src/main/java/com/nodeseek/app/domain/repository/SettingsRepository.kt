package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    val recentSearchQueries: StateFlow<List<String>>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setPurePitchAmoled(enabled: Boolean)
    suspend fun setUseDynamicColor(enabled: Boolean)
    suspend fun setFontScale(scale: Float)
    suspend fun setPreferRandomCheckIn(preferRandom: Boolean)
    suspend fun setPinnedCategorySlugs(slugs: Set<String>)
    suspend fun togglePinnedCategory(slug: String)
    suspend fun setIncludeBlockedTopics(include: Boolean)
    suspend fun setPreferRssFallback(preferRss: Boolean)
    suspend fun setNodeImageApiKey(apiKey: String)
    suspend fun setSeekMailApiKey(apiKey: String)

    suspend fun addRecentSearchQuery(query: String)
    suspend fun removeRecentSearchQuery(query: String)
    suspend fun clearRecentSearchQueries()
}
