package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.backup.AppSettingsBackupDto
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    val recentSearchQueries: StateFlow<List<String>>

    suspend fun awaitLoadedSettings(): AppSettings = settings.value

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setPurePitchAmoled(enabled: Boolean)
    suspend fun setUseDynamicColor(enabled: Boolean)
    suspend fun setFontScale(scale: Float)
    suspend fun setHomeCardScale(scale: Float) {}
    suspend fun setPreferRandomCheckIn(preferRandom: Boolean)
    suspend fun setPinnedCategorySlugs(slugs: Set<String>)
    suspend fun setHomepageCategoryOrder(slugs: List<String>) {}
    suspend fun getHomepageCategoryOrder(accountUid: Long): List<String> =
        settings.value.homepageCategoryOrder
    suspend fun setHomepageCategoryOrder(accountUid: Long, slugs: List<String>) {
        setHomepageCategoryOrder(slugs)
    }
    suspend fun setActiveHomepageCategoryAccountUid(accountUid: Long) {}
    fun onActiveAccountChanged(accountUid: Long) {}
    suspend fun togglePinnedCategory(slug: String)
    suspend fun setIncludeBlockedTopics(include: Boolean)
    suspend fun setPreferRssFallback(preferRss: Boolean)
    suspend fun setNodeImageApiKey(apiKey: String)
    suspend fun setSeekMailApiKey(apiKey: String)
    suspend fun setAutoCheckUpdates(enabled: Boolean)
    suspend fun setIncludePrereleaseUpdates(enabled: Boolean)
    suspend fun setIgnoredUpdateTag(tag: String)
    suspend fun setAwayDigestEnabled(enabled: Boolean) {}
    suspend fun setAwayDigestTriggerMode(mode: AwayDigestTriggerMode) {}
    suspend fun setAwayDigestThresholdMinutes(minutes: Int) {}

    suspend fun addRecentSearchQuery(query: String)
    suspend fun removeRecentSearchQuery(query: String)
    suspend fun clearRecentSearchQueries()

    suspend fun exportSettingsForBackup(includeApiKeys: Boolean = true): AppSettingsBackupDto
    suspend fun restoreSettingsFromBackup(backup: AppSettingsBackupDto, merge: Boolean)
}
