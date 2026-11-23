package com.nodeseek.app.domain.filter

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
 * Feed and search content filtering engine with bounded compiled-regex LRU caching
 * and nested-quantifier ReDoS protection.
 */
object ContentFilterEngine {

    private const val MAX_COMPILED_REGEX_CACHE = 64
    private const val MAX_REGEX_PATTERN_LENGTH = 256
    private const val MAX_MATCH_TITLE_LENGTH = 512

    private val compiledRegexCacheLock = Any()
    private val compiledRegexCache = object : LinkedHashMap<String, Regex?>(MAX_COMPILED_REGEX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>?): Boolean =
            size > MAX_COMPILED_REGEX_CACHE
    }

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
     * Validates a regular expression pattern without throwing, rejecting excessively long
     * or nested-quantifier patterns prone to catastrophic backtracking (ReDoS).
     *
     * @return `null` when [pattern] compiles cleanly and safely, or a localized error message otherwise.
     */
    fun validateRegex(pattern: String): String? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            return "正则表达式不能为空"
        }
        if (trimmed.length > MAX_REGEX_PATTERN_LENGTH) {
            return "正则表达式过长（最多 $MAX_REGEX_PATTERN_LENGTH 字符）"
        }
        if (isPotentiallyCatastrophicRegex(trimmed)) {
            return "正则表达式包含嵌套重复量词，存在性能回溯风险，请简化写法"
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
        return matchesPattern(sampleTitle, pattern, matchMode)
    }

    internal fun matchesPattern(
        title: String,
        pattern: String,
        matchMode: KeywordMatchMode,
    ): Boolean {
        val cleanPattern = pattern.trim()
        if (cleanPattern.isEmpty() || title.isBlank()) return false
        return when (matchMode) {
            KeywordMatchMode.CONTAINS -> title.contains(cleanPattern, ignoreCase = true)
            KeywordMatchMode.REGEX -> {
                val compiled = getOrCompileRegex(cleanPattern) ?: return false
                val bounded = if (title.length > MAX_MATCH_TITLE_LENGTH) {
                    title.substring(0, MAX_MATCH_TITLE_LENGTH)
                } else {
                    title
                }
                compiled.containsMatchIn(bounded)
            }
        }
    }

    internal fun getOrCompileRegex(pattern: String): Regex? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_REGEX_PATTERN_LENGTH || isPotentiallyCatastrophicRegex(trimmed)) {
            return null
        }
        synchronized(compiledRegexCacheLock) {
            if (compiledRegexCache.containsKey(trimmed)) {
                return compiledRegexCache[trimmed]
            }
        }
        val compiled = runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.getOrNull()
        synchronized(compiledRegexCacheLock) {
            compiledRegexCache[trimmed] = compiled
        }
        return compiled
    }

    /**
     * Detects nested unbounded quantifiers such as `(a+)+`, `(a*)*`, `(\w+){2,}` that can
     * cause exponential backtracking on adversarial or long topic titles.
     */
    internal fun isPotentiallyCatastrophicRegex(pattern: String): Boolean {
        val groupHasUnboundedQuantifier = ArrayDeque<Boolean>()
        var inCharClass = false
        var escaped = false
        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            if (escaped) {
                escaped = false
                i++
                continue
            }
            if (ch == '\\') {
                escaped = true
                i++
                continue
            }
            if (inCharClass) {
                if (ch == ']') {
                    inCharClass = false
                }
                i++
                continue
            }
            when (ch) {
                '[' -> inCharClass = true
                '(' -> groupHasUnboundedQuantifier.addLast(false)
                ')' -> {
                    val innerHadUnbounded = if (groupHasUnboundedQuantifier.isNotEmpty()) {
                        groupHasUnboundedQuantifier.removeLast()
                    } else {
                        false
                    }
                    val nextQuantifier = parseQuantifierAt(pattern, i + 1)
                    if (nextQuantifier != null) {
                        if (innerHadUnbounded && nextQuantifier.isRepeating) {
                            return true
                        }
                        if (nextQuantifier.isUnbounded && groupHasUnboundedQuantifier.isNotEmpty()) {
                            groupHasUnboundedQuantifier[groupHasUnboundedQuantifier.lastIndex] = true
                        }
                        i = nextQuantifier.endIndex
                        continue
                    } else if (innerHadUnbounded && groupHasUnboundedQuantifier.isNotEmpty()) {
                        groupHasUnboundedQuantifier[groupHasUnboundedQuantifier.lastIndex] = true
                    }
                }
                '+', '*', '{' -> {
                    val q = parseQuantifierAt(pattern, i)
                    if (q != null) {
                        if (q.isUnbounded && groupHasUnboundedQuantifier.isNotEmpty()) {
                            groupHasUnboundedQuantifier[groupHasUnboundedQuantifier.lastIndex] = true
                        }
                        i = q.endIndex
                        continue
                    }
                }
            }
            i++
        }
        return false
    }

    private data class QuantifierToken(
        val endIndex: Int,
        val isRepeating: Boolean,
        val isUnbounded: Boolean,
    )

    private fun parseQuantifierAt(pattern: String, start: Int): QuantifierToken? {
        if (start >= pattern.length) return null
        return when (pattern[start]) {
            '+', '*' -> {
                var end = start + 1
                if (end < pattern.length && (pattern[end] == '?' || pattern[end] == '+')) end++
                QuantifierToken(endIndex = end, isRepeating = true, isUnbounded = true)
            }
            '{' -> {
                val close = pattern.indexOf('}', start + 1)
                if (close <= start + 1) return null
                val body = pattern.substring(start + 1, close)
                val parts = body.split(',')
                if (parts.size > 2) return null
                val min = parts[0].trim().toIntOrNull() ?: return null
                val max = if (parts.size == 1) min else parts[1].trim().let { if (it.isEmpty()) null else it.toIntOrNull() ?: return null }
                var end = close + 1
                if (end < pattern.length && (pattern[end] == '?' || pattern[end] == '+')) end++
                val isRepeating = max == null || max >= 2
                val isUnbounded = max == null || max >= 10
                QuantifierToken(endIndex = end, isRepeating = isRepeating, isUnbounded = isUnbounded)
            }
            else -> null
        }
    }

    private data class CompiledRule(
        val rule: ContentBlockRule,
        val cleanCategorySlug: String,
        val cleanPattern: String,
        val compiledRegex: Regex?,
    )

    private fun compileEnabledRules(rules: List<ContentBlockRule>): List<CompiledRule> =
        rules.mapNotNull { rule ->
            if (!rule.isEnabled) return@mapNotNull null
            val cleanPattern = rule.pattern.trim()
            if (cleanPattern.isEmpty()) return@mapNotNull null
            val regex = if (rule.matchMode == KeywordMatchMode.REGEX) {
                getOrCompileRegex(cleanPattern) ?: return@mapNotNull null
            } else {
                null
            }
            CompiledRule(
                rule = rule,
                cleanCategorySlug = rule.categorySlug.trim(),
                cleanPattern = cleanPattern,
                compiledRegex = regex,
            )
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
                if (matchesPattern(topic.title, rule.pattern, rule.matchMode)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Filters [topics] using [shouldHideTopic], returning the original list instance on fast-path no-ops
     * and pre-resolving muted categories & compiled regex rules once per batch.
     */
    fun filterTopics(
        topics: List<TopicSummary>,
        config: ContentFilterConfig,
        includeBlockedTopics: Boolean,
        activeBoardSlug: String = "",
    ): List<TopicSummary> {
        if (topics.isEmpty() || !config.isEnabled || includeBlockedTopics) return topics
        val mutedSet = if (config.mutedCategorySlugs.isEmpty()) {
            emptySet()
        } else {
            config.mutedCategorySlugs.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.toSet()
        }
        val compiledRules = compileEnabledRules(config.rules)
        if (mutedSet.isEmpty() && compiledRules.isEmpty()) return topics

        val activeSlugLower = activeBoardSlug.trim().lowercase()
        return topics.filterNot { topic ->
            val topicSlugLower = topic.categorySlug.trim().lowercase()
            if (topicSlugLower.isNotEmpty() && topicSlugLower in mutedSet && activeSlugLower != topicSlugLower) {
                return@filterNot true
            }
            if (topic.title.isBlank() || compiledRules.isEmpty()) {
                return@filterNot false
            }
            val boundedTitle = if (topic.title.length > MAX_MATCH_TITLE_LENGTH) {
                topic.title.substring(0, MAX_MATCH_TITLE_LENGTH)
            } else {
                topic.title
            }
            compiledRules.any { compiled ->
                matchesRuleCategory(topic, compiled.cleanCategorySlug) &&
                    when (compiled.rule.matchMode) {
                        KeywordMatchMode.CONTAINS -> topic.title.contains(compiled.cleanPattern, ignoreCase = true)
                        KeywordMatchMode.REGEX -> compiled.compiledRegex?.containsMatchIn(boundedTitle) == true
                    }
            }
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
