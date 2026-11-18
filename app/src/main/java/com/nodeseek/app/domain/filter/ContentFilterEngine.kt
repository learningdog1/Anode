package com.nodeseek.app.domain.filter

import com.nodeseek.app.data.radar.RadarKeywordMatcher
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.radar.KeywordMatchMode
import java.util.UUID

/**
 * Curated one-tap rule template for common noisy NodeSeek post categories.
 */
data class ContentFilterPreset(
    val id: String,
    val label: String,
    val description: String,
    val pattern: String,
    val matchMode: KeywordMatchMode,
    val categorySlug: String = "",
    val note: String = label,
) {
    fun toRule(createdAtEpochMillis: Long = System.currentTimeMillis()): ContentBlockRule =
        ContentBlockRule(
            id = id,
            pattern = pattern,
            matchMode = matchMode,
            categorySlug = categorySlug,
            isEnabled = true,
            note = note,
            createdAtEpochMillis = createdAtEpochMillis,
        )
}

/**
 * Feed and search content filtering engine reusing [RadarKeywordMatcher.matchesKeyword].
 */
object ContentFilterEngine {

    val CURATED_PRESETS: List<ContentFilterPreset> = listOf(
        ContentFilterPreset(
            id = "preset_buy_request",
            label = "收 / 求购",
            description = "屏蔽标题以「收」「求购」等求收贴",
            pattern = """(^|[\s【\[（(])(求购|求租|(?:高价|诚心?|长期)?收(?![集藏纳到益费尾]))""",
            matchMode = KeywordMatchMode.REGEX,
            note = "收 / 求购",
        ),
        ContentFilterPreset(
            id = "preset_aff_promo",
            label = "AFF / 推广",
            description = "屏蔽包含 AFF、返利、推广链接的帖文",
            pattern = """(?i)(\baff\b|affman|返利|推广链接)""",
            matchMode = KeywordMatchMode.REGEX,
            note = "AFF / 推广",
        ),
        ContentFilterPreset(
            id = "preset_sell_premium",
            label = "出 / 溢价",
            description = "屏蔽「[出]」「溢价」「明盘」类出机贴",
            pattern = """(^|[\s【\[（(])(溢价|明盘|出(?![版现发错席路口品租]))""",
            matchMode = KeywordMatchMode.REGEX,
            note = "出 / 溢价",
        ),
        ContentFilterPreset(
            id = "preset_carpool",
            label = "拼车 / 合租",
            description = "屏蔽合租车队、车位招募类帖文",
            pattern = """拼车|合租|车位""",
            matchMode = KeywordMatchMode.REGEX,
            note = "拼车 / 合租",
        ),
        ContentFilterPreset(
            id = "preset_lottery",
            label = "抽奖 / 水贴",
            description = "屏蔽抽奖开奖与日常灌水贴",
            pattern = """(^|[\s【\[（(])(抽奖|开奖|水一贴|每日一水)""",
            matchMode = KeywordMatchMode.REGEX,
            note = "抽奖 / 水贴",
        ),
    )

    /**
     * Validates a regular expression pattern without throwing.
     *
     * @return `null` when [pattern] compiles cleanly, or a localized error message otherwise.
     */
    fun validateRegex(pattern: String): String? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            return "正则表达式不能为空"
        }
        return runCatching {
            Regex(trimmed, RegexOption.IGNORE_CASE)
            null
        }.getOrElse { error ->
            val reason = error.message
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (reason != null) {
                "正则表达式无效：$reason"
            } else {
                "正则表达式语法无效，请检查括号或转义符"
            }
        }
    }

    /**
     * Validates either a plain keyword or a regex pattern depending on [matchMode].
     */
    fun validateRulePattern(pattern: String, matchMode: KeywordMatchMode): String? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            return if (matchMode == KeywordMatchMode.REGEX) {
                "请输入正则表达式"
            } else {
                "请输入屏蔽关键词"
            }
        }
        return when (matchMode) {
            KeywordMatchMode.CONTAINS -> null
            KeywordMatchMode.REGEX -> validateRegex(trimmed)
        }
    }

    /**
     * Evaluates a single candidate rule against [sampleTitle] for live preview in Settings.
     */
    fun testRuleMatch(
        sampleTitle: String,
        pattern: String,
        matchMode: KeywordMatchMode,
    ): Boolean {
        if (sampleTitle.isBlank()) return false
        return RadarKeywordMatcher.matchesKeyword(sampleTitle, pattern, matchMode)
    }

    /**
     * Returns `true` if [topic] should be hidden from the caller's feed or search stream.
     */
    fun shouldHideTopic(
        topic: TopicSummary,
        config: ContentFilterConfig,
        includeBlockedTopics: Boolean,
        activeBoardSlug: String = "",
    ): Boolean {
        if (!config.isEnabled || includeBlockedTopics) return false

        val topicSlug = topic.categorySlug.trim()
        val activeSlug = activeBoardSlug.trim()
        if (topicSlug.isNotEmpty() && config.mutedCategorySlugs.isNotEmpty()) {
            val isCategoryMuted = config.mutedCategorySlugs.any { muted ->
                val cleanMuted = muted.trim()
                cleanMuted.isNotEmpty() && cleanMuted.equals(topicSlug, ignoreCase = true)
            }
            val isExplicitlyViewingMutedBoard = activeSlug.isNotEmpty() &&
                activeSlug.equals(topicSlug, ignoreCase = true)
            if (isCategoryMuted && !isExplicitlyViewingMutedBoard) {
                return true
            }
        }

        if (topic.title.isNotBlank() && config.rules.isNotEmpty()) {
            for (rule in config.rules) {
                if (!rule.isEnabled) continue
                if (!matchesRuleCategory(topic, rule.categorySlug)) continue
                if (RadarKeywordMatcher.matchesKeyword(topic.title, rule.pattern, rule.matchMode)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Filters [topics] using [shouldHideTopic], returning the original list instance on fast-path no-ops.
     */
    fun filterTopics(
        topics: List<TopicSummary>,
        config: ContentFilterConfig,
        includeBlockedTopics: Boolean,
        activeBoardSlug: String = "",
    ): List<TopicSummary> {
        if (topics.isEmpty() || !config.isEnabled || includeBlockedTopics) return topics
        if (config.mutedCategorySlugs.isEmpty() && config.rules.none { it.isEnabled }) return topics
        return topics.filterNot { topic ->
            shouldHideTopic(
                topic = topic,
                config = config,
                includeBlockedTopics = false,
                activeBoardSlug = activeBoardSlug,
            )
        }
    }

    /**
     * Finds the existing rule matching [preset] by ID or normalized `(pattern, matchMode, categorySlug)`.
     */
    fun findMatchingRuleForPreset(
        preset: ContentFilterPreset,
        rules: List<ContentBlockRule>,
    ): ContentBlockRule? {
        val presetSlug = preset.categorySlug.trim()
        return rules.firstOrNull { rule ->
            rule.id == preset.id ||
                (rule.matchMode == preset.matchMode &&
                    rule.categorySlug.trim().equals(presetSlug, ignoreCase = true) &&
                    rule.pattern.trim() == preset.pattern.trim())
        }
    }

    fun isPresetActive(
        preset: ContentFilterPreset,
        config: ContentFilterConfig,
    ): Boolean {
        val rule = findMatchingRuleForPreset(preset, config.rules) ?: return false
        return rule.isEnabled
    }

    private fun matchesRuleCategory(topic: TopicSummary, requiredSlug: String): Boolean {
        val cleanSlug = requiredSlug.trim()
        if (cleanSlug.isEmpty()) return true
        return topic.categorySlug.equals(cleanSlug, ignoreCase = true) ||
            topic.categoryName.equals(cleanSlug, ignoreCase = true)
    }

    private data class RuleDedupKey(
        val patternKey: String,
        val matchMode: KeywordMatchMode,
        val categorySlug: String,
    )

    private fun ContentBlockRule.normalizedOrNull(): ContentBlockRule? {
        val cleanPattern = pattern.trim()
        if (cleanPattern.isEmpty()) return null
        val cleanId = id.trim().ifEmpty { UUID.randomUUID().toString() }
        return copy(
            id = cleanId,
            pattern = cleanPattern,
            categorySlug = categorySlug.trim().lowercase(),
            note = note.trim(),
        )
    }

    private fun ContentBlockRule.dedupKey(): RuleDedupKey = RuleDedupKey(
        patternKey = if (matchMode == KeywordMatchMode.CONTAINS) {
            pattern.trim().lowercase()
        } else {
            pattern.trim()
        },
        matchMode = matchMode,
        categorySlug = categorySlug.trim().lowercase(),
    )

    fun normalizeConfig(config: ContentFilterConfig): ContentFilterConfig {
        val cleanMuted = LinkedHashSet<String>()
        config.mutedCategorySlugs.forEach { raw ->
            val clean = raw.trim().lowercase()
            if (clean.isNotEmpty()) {
                cleanMuted.add(clean)
            }
        }
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<RuleDedupKey>()
        val cleanRules = buildList {
            for (rawRule in config.rules) {
                val normalized = rawRule.normalizedOrNull() ?: continue
                val dedup = normalized.dedupKey()
                if (!seenKeys.add(dedup)) continue
                val uniqueId = if (seenIds.add(normalized.id)) {
                    normalized.id
                } else {
                    UUID.randomUUID().toString().also { seenIds.add(it) }
                }
                add(if (uniqueId == normalized.id) normalized else normalized.copy(id = uniqueId))
            }
        }
        return ContentFilterConfig(
            isEnabled = config.isEnabled,
            mutedCategorySlugs = cleanMuted,
            rules = cleanRules,
        )
    }

    fun upsertRule(
        current: ContentFilterConfig,
        rule: ContentBlockRule,
    ): ContentFilterConfig {
        val normalized = rule.normalizedOrNull() ?: return current
        val base = normalizeConfig(current)
        val existingByIdIndex = base.rules.indexOfFirst { it.id == normalized.id }
        val targetKey = normalized.dedupKey()
        val updatedRules = if (existingByIdIndex >= 0) {
            base.rules.mapIndexedNotNull { idx, item ->
                when {
                    idx == existingByIdIndex -> normalized
                    item.dedupKey() == targetKey -> null
                    else -> item
                }
            }
        } else {
            val existingByKeyIndex = base.rules.indexOfFirst { it.dedupKey() == targetKey }
            if (existingByKeyIndex >= 0) {
                base.rules.mapIndexed { idx, item ->
                    if (idx == existingByKeyIndex) {
                        normalized.copy(id = item.id)
                    } else {
                        item
                    }
                }
            } else {
                base.rules + normalized
            }
        }
        return base.copy(rules = updatedRules)
    }

    fun removeRule(
        current: ContentFilterConfig,
        ruleId: String,
    ): ContentFilterConfig {
        val targetId = ruleId.trim()
        if (targetId.isEmpty()) return current
        val nextRules = current.rules.filterNot { it.id == targetId }
        return if (nextRules.size == current.rules.size) current else current.copy(rules = nextRules)
    }

    fun setRuleEnabled(
        current: ContentFilterConfig,
        ruleId: String,
        enabled: Boolean,
    ): ContentFilterConfig {
        val targetId = ruleId.trim()
        if (targetId.isEmpty()) return current
        var changed = false
        val nextRules = current.rules.map { rule ->
            if (rule.id == targetId && rule.isEnabled != enabled) {
                changed = true
                rule.copy(isEnabled = enabled)
            } else {
                rule
            }
        }
        return if (changed) current.copy(rules = nextRules) else current
    }

    fun toggleMutedCategory(
        current: ContentFilterConfig,
        categorySlug: String,
    ): ContentFilterConfig {
        val normalized = categorySlug.trim().lowercase()
        if (normalized.isEmpty()) return current
        val base = normalizeConfig(current)
        val updated = LinkedHashSet(base.mutedCategorySlugs)
        if (!updated.remove(normalized)) {
            updated.add(normalized)
        }
        return base.copy(mutedCategorySlugs = updated)
    }

    fun setMutedCategories(
        current: ContentFilterConfig,
        categorySlugs: Set<String>,
    ): ContentFilterConfig {
        val cleaned = LinkedHashSet<String>()
        categorySlugs.forEach { raw ->
            val slug = raw.trim().lowercase()
            if (slug.isNotEmpty()) {
                cleaned.add(slug)
            }
        }
        return normalizeConfig(current).copy(mutedCategorySlugs = cleaned)
    }

    fun mergeConfigs(
        current: ContentFilterConfig,
        incoming: ContentFilterConfig,
    ): ContentFilterConfig {
        val base = normalizeConfig(current)
        val normalizedIncoming = normalizeConfig(incoming)
        val mergedMuted = LinkedHashSet(base.mutedCategorySlugs).apply {
            addAll(normalizedIncoming.mutedCategorySlugs)
        }
        val existingIds = base.rules.mapTo(HashSet()) { it.id }
        val existingKeys = base.rules.mapTo(HashSet()) { it.dedupKey() }
        val mergedRules = buildList {
            addAll(base.rules)
            for (rule in normalizedIncoming.rules) {
                val dedup = rule.dedupKey()
                if (!existingKeys.add(dedup)) continue
                val resolvedId = if (existingIds.add(rule.id)) {
                    rule.id
                } else {
                    UUID.randomUUID().toString().also { existingIds.add(it) }
                }
                add(if (resolvedId == rule.id) rule else rule.copy(id = resolvedId))
            }
        }
        val resolvedEnabled = if (base.rules.isEmpty() && base.mutedCategorySlugs.isEmpty()) {
            normalizedIncoming.isEnabled
        } else {
            base.isEnabled || normalizedIncoming.isEnabled
        }
        return ContentFilterConfig(
            isEnabled = resolvedEnabled,
            mutedCategorySlugs = mergedMuted,
            rules = mergedRules,
        )
    }
}
