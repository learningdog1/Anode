package com.nodeseek.app.data.backup

import com.nodeseek.app.domain.backup.ContentFilterBackupBridge
import com.nodeseek.app.domain.backup.ContentFilterBackupDto
import com.nodeseek.app.domain.backup.ContentFilterRuleBackupDto
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.radar.KeywordMatchMode

/**
 * Bridges [ContentFilterRepository] with [BackupManagerImpl] for JSON backup export and restore.
 */
class ContentFilterRepositoryBackupBridge(
    private val contentFilterRepository: ContentFilterRepository,
) : ContentFilterBackupBridge {

    override suspend fun export(): ContentFilterBackupDto {
        val current = contentFilterRepository.getConfigSnapshot()
        return ContentFilterBackupDto(
            isEnabled = current.isEnabled,
            mutedCategorySlugs = current.mutedCategorySlugs,
            rules = current.rules.map { rule ->
                ContentFilterRuleBackupDto(
                    id = rule.id,
                    pattern = rule.pattern,
                    matchMode = rule.matchMode.name,
                    categorySlug = rule.categorySlug,
                    isEnabled = rule.isEnabled,
                    note = rule.note,
                    createdAtEpochMillis = rule.createdAtEpochMillis,
                )
            },
        )
    }

    override suspend fun restore(
        dto: ContentFilterBackupDto,
        merge: Boolean,
    ) {
        val mapped = ContentFilterConfig(
            isEnabled = dto.isEnabled,
            mutedCategorySlugs = dto.mutedCategorySlugs,
            rules = dto.rules.mapNotNull { ruleDto ->
                val cleanPattern = ruleDto.pattern.trim()
                if (cleanPattern.isEmpty()) return@mapNotNull null
                val resolvedMode = runCatching {
                    KeywordMatchMode.valueOf(ruleDto.matchMode.trim().uppercase())
                }.getOrDefault(KeywordMatchMode.CONTAINS)
                ContentBlockRule(
                    id = ruleDto.id,
                    pattern = cleanPattern,
                    matchMode = resolvedMode,
                    categorySlug = ruleDto.categorySlug,
                    isEnabled = ruleDto.isEnabled,
                    note = ruleDto.note,
                    createdAtEpochMillis = ruleDto.createdAtEpochMillis,
                )
            },
        )
        if (merge) {
            contentFilterRepository.mergeConfig(mapped)
        } else {
            contentFilterRepository.replaceConfig(mapped)
        }
    }
}
