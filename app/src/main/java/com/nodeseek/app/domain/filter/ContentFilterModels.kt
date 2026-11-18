package com.nodeseek.app.domain.filter

import com.nodeseek.app.domain.radar.KeywordMatchMode
import kotlinx.serialization.Serializable

/**
 * Single user-defined or preset content-blocking rule evaluated against topic titles.
 */
@Serializable
data class ContentBlockRule(
    val id: String,
    val pattern: String,
    val matchMode: KeywordMatchMode = KeywordMatchMode.CONTAINS,
    val categorySlug: String = "",
    val isEnabled: Boolean = true,
    val note: String = "",
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

/**
 * Persistent configuration for feed & search content filtering.
 */
@Serializable
data class ContentFilterConfig(
    val isEnabled: Boolean = true,
    val mutedCategorySlugs: Set<String> = emptySet(),
    val rules: List<ContentBlockRule> = emptyList(),
)
