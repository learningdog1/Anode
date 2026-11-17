package com.nodeseek.app.domain.update

import java.math.BigInteger

/**
 * SemVer 2.0.0 compatible version parser & comparator supporting common GitHub tag formats
 * such as `v1.0.0`, `1.0.1`, `Anode-v1.2.0`, `1.10.0`, and pre-release tags like `v1.1.0-rc.1`.
 */
object AppVersionComparator {

    private data class ParsedVersion(
        val core: List<Long>,
        val prerelease: List<String>,
    )

    fun normalizeVersionString(raw: String): String {
        var cleaned = raw.trim()
        while (true) {
            val next = when {
                cleaned.startsWith("anode-", ignoreCase = true) -> cleaned.substring(6)
                cleaned.startsWith("release-", ignoreCase = true) -> cleaned.substring(8)
                cleaned.startsWith("v", ignoreCase = true) &&
                    cleaned.length > 1 &&
                    cleaned[1].isDigit() -> cleaned.substring(1)
                else -> cleaned
            }.trimStart()
            if (next == cleaned) break
            cleaned = next
        }
        return cleaned
    }

    fun isNewer(candidate: String, current: String): Boolean {
        return compare(candidate, current) > 0
    }

    fun compare(v1: String, v2: String): Int {
        val parsed1 = parseOrNull(v1)
        val parsed2 = parseOrNull(v2)
        if (parsed1 == null && parsed2 == null) {
            return normalizeVersionString(v1).compareTo(normalizeVersionString(v2), ignoreCase = true)
        }
        if (parsed1 == null) return -1
        if (parsed2 == null) return 1

        val maxCoreLen = maxOf(parsed1.core.size, parsed2.core.size)
        for (i in 0 until maxCoreLen) {
            val part1 = parsed1.core.getOrElse(i) { 0L }
            val part2 = parsed2.core.getOrElse(i) { 0L }
            val cmp = part1.compareTo(part2)
            if (cmp != 0) return cmp
        }

        val pre1 = parsed1.prerelease
        val pre2 = parsed2.prerelease
        if (pre1.isEmpty() && pre2.isEmpty()) return 0
        // Normal version has higher precedence than a pre-release version.
        if (pre1.isEmpty()) return 1
        if (pre2.isEmpty()) return -1

        val minPreLen = minOf(pre1.size, pre2.size)
        for (i in 0 until minPreLen) {
            val cmp = comparePrereleaseIdentifier(pre1[i], pre2[i])
            if (cmp != 0) return cmp
        }
        return pre1.size.compareTo(pre2.size)
    }

    private fun parseOrNull(raw: String): ParsedVersion? {
        val normalized = normalizeVersionString(raw)
        if (normalized.isEmpty()) return null

        val withoutBuild = normalized.substringBefore('+').trim()
        if (withoutBuild.isEmpty()) return null

        val dashIndex = withoutBuild.indexOf('-')
        val coreStr = if (dashIndex >= 0) withoutBuild.substring(0, dashIndex) else withoutBuild
        val preStr = if (dashIndex >= 0) withoutBuild.substring(dashIndex + 1) else ""

        if (coreStr.isEmpty()) return null
        val coreSegments = coreStr.split('.')
        if (coreSegments.any { it.isEmpty() || !it.all(Char::isDigit) }) {
            return null
        }
        val coreNumbers = coreSegments.map { segment ->
            segment.toLongOrNull() ?: return null
        }

        val prereleaseSegments = if (preStr.isEmpty()) {
            emptyList()
        } else {
            preStr.split('.').filter { it.isNotEmpty() }
        }
        return ParsedVersion(
            core = coreNumbers,
            prerelease = prereleaseSegments,
        )
    }

    private fun comparePrereleaseIdentifier(id1: String, id2: String): Int {
        val isNum1 = id1.isNotEmpty() && id1.all(Char::isDigit)
        val isNum2 = id2.isNotEmpty() && id2.all(Char::isDigit)

        return when {
            isNum1 && isNum2 -> {
                val n1 = id1.toBigIntegerOrNull() ?: BigInteger.ZERO
                val n2 = id2.toBigIntegerOrNull() ?: BigInteger.ZERO
                n1.compareTo(n2)
            }
            // Numeric identifiers always have lower precedence than non-numeric identifiers.
            isNum1 && !isNum2 -> -1
            !isNum1 && isNum2 -> 1
            else -> id1.compareTo(id2)
        }
    }
}
