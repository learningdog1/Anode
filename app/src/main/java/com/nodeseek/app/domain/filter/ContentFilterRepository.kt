package com.nodeseek.app.domain.filter

import kotlinx.coroutines.flow.StateFlow

/**
 * Persistent source of truth for keyword/regex content blocking and muted board preferences.
 */
interface ContentFilterRepository {
    val config: StateFlow<ContentFilterConfig>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun upsertRule(rule: ContentBlockRule)

    suspend fun removeRule(ruleId: String)

    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean)

    suspend fun toggleMutedCategory(categorySlug: String)

    suspend fun setMutedCategories(categorySlugs: Set<String>)

    suspend fun replaceConfig(config: ContentFilterConfig)

    suspend fun mergeConfig(config: ContentFilterConfig)
}
