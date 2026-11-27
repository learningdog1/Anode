@file:OptIn(ExperimentalUnsignedTypes::class)

package com.nodeseek.app.domain.activity

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.floor

/**
 * Pure-Kotlin deterministic engine for NodeSeek `/lucky` Drand verifiable draws.
 *
 * Implements:
 * - NodeSeek `/lucky` URL parsing, Markdown extraction, and canonical URL building
 * - League of Entropy Mainnet Drand round math (genesis `1595431050`, period `30s`)
 * - JS-identical `cyrb128` 128-bit string hash + `sfc32` PRNG
 * - Protocol winner selection & `@username` announcement Markdown formatting
 */
object DrandLuckyEngine {

    const val DRAND_CHAIN_HASH: String =
        "8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce"
    const val DRAND_GENESIS_EPOCH_SECONDS: Long = 1_595_431_050L
    const val DRAND_GENESIS_EPOCH_MILLIS: Long = DRAND_GENESIS_EPOCH_SECONDS * 1_000L
    const val DRAND_PERIOD_SECONDS: Double = 30.0

    private const val MIN_VALID_EPOCH_SECONDS = 1_000_000_000L
    private const val MAX_VALID_EPOCH_SECONDS = 9_999_999_999L
    private const val MIN_VALID_EPOCH_MILLIS = 1_000_000_000_000L

    private val PRIMARY_HOSTS = setOf(
        "nodeseek.com",
        "www.nodeseek.com",
        "m.nodeseek.com",
    )

    private val DEDICATED_LUCKY_HOST = "lucky.nodeseek.com"

    private val DRAW_PATH_REGEX = Regex("""^/(?:lucky/)?draw/(\d+)/?$""", RegexOption.IGNORE_CASE)

    private val DRAW_TIME_QUERY_KEYS = listOf(
        "time",
        "draw_time",
        "drawtime",
        "draw_at",
        "drawat",
        "end_time",
        "endtime",
        "end",
        "deadline",
    )

    private val POST_ID_QUERY_KEYS = listOf(
        "post",
        "postid",
        "post_id",
        "topic",
        "topicid",
        "topic_id",
    )

    private const val LUCKY_URL_BODY_PATTERN = """[\u0021-\u007E&&[^<>"'\]\[()`*~]]+"""

    private val LUCKY_LINK_CANDIDATE_REGEX = Regex(
        pattern = """(?:https?://$LUCKY_URL_BODY_PATTERN|(?:/jump\?$LUCKY_URL_BODY_PATTERN|/lucky\?$LUCKY_URL_BODY_PATTERN))""",
        option = RegexOption.IGNORE_CASE,
    )

    private val TRAILING_ASCII_PUNCT = setOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'',
    )

    private fun trimLuckyUrlCandidate(raw: String): String =
        raw.trim().dropLastWhile { ch ->
            ch.isWhitespace() || ch.code !in 0x21..0x7E || ch in TRAILING_ASCII_PUNCT
        }

    /**
     * Parses any supported `/lucky` URL (absolute, protocol-relative, relative `/lucky?...`,
     * HTML `&amp;`-escaped, or `/jump?to=...` wrapped) into a [LuckyDrawSpec], or returns `null`
     * if the URL is not a concrete draw link (e.g. bare `https://www.nodeseek.com/lucky`).
     */
    fun parseLuckyUrl(
        rawUrl: String?,
        fallbackPostId: Long? = null,
        maxRedirectDepth: Int = 2,
    ): LuckyDrawSpec? {
        if (rawUrl.isNullOrBlank() || maxRedirectDepth < 0) return null
        val cleaned = trimLuckyUrlCandidate(rawUrl)
            .replace("&amp;", "&", ignoreCase = true)
            .let { candidate ->
                when {
                    candidate.startsWith("//") -> "https:$candidate"
                    candidate.startsWith("/") -> "https://www.nodeseek.com$candidate"
                    else -> candidate
                }
            }
        val uri = runCatching { URI(cleaned) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        if (host !in PRIMARY_HOSTS && host != DEDICATED_LUCKY_HOST) return null

        val path = uri.rawPath?.ifBlank { "/" } ?: "/"
        val queryParams = parseQueryParams(uri.rawQuery)

        if (path.equals("/jump", ignoreCase = true)) {
            val redirectTarget = queryParams["to"] ?: return null
            return parseLuckyUrl(
                rawUrl = redirectTarget,
                fallbackPostId = fallbackPostId,
                maxRedirectDepth = maxRedirectDepth - 1,
            )
        }

        val normalizedPath = if (path.length > 1) path.trimEnd('/') else path
        var pathPostId: Long? = null
        val isLuckyEndpoint = when {
            host in PRIMARY_HOSTS -> {
                normalizedPath.equals("/lucky", ignoreCase = true) ||
                    DRAW_PATH_REGEX.matchEntire(normalizedPath)?.also { match ->
                        pathPostId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
                    } != null
            }
            host == DEDICATED_LUCKY_HOST -> {
                normalizedPath == "/" ||
                    normalizedPath.equals("/lucky", ignoreCase = true) ||
                    DRAW_PATH_REGEX.matchEntire(normalizedPath)?.also { match ->
                        pathPostId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
                    } != null
            }
            else -> false
        }
        if (!isLuckyEndpoint) return null

        val queryPostId = POST_ID_QUERY_KEYS.firstNotNullOfOrNull { key ->
            queryParams[key]?.toLongOrNull()?.takeIf { it > 0L }
        }
        val drawTimeMs = DRAW_TIME_QUERY_KEYS.firstNotNullOfOrNull { key ->
            parseTimestampEpochMillis(queryParams[key])
        }

        val resolvedPostId = queryPostId
            ?: pathPostId
            ?: fallbackPostId?.takeIf { it > 0L && drawTimeMs != null }
            ?: return null

        val winnerCount = (queryParams["count"] ?: queryParams["winners"])
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 1
        val firstFloor = (queryParams["start"] ?: queryParams["firstfloor"] ?: queryParams["first_floor"])
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 1

        // Protocol note: `duplicate=false` (and default when omitted) means removeDuplicates = true.
        val duplicateRaw = queryParams["duplicate"]?.trim()?.lowercase(Locale.ROOT)
        val removeDuplicates = when (duplicateRaw) {
            "true", "1", "yes" -> false
            else -> true
        }

        return LuckyDrawSpec(
            postId = resolvedPostId,
            drawTimeEpochMillis = drawTimeMs,
            winnerCount = winnerCount,
            firstFloor = firstFloor,
            removeDuplicates = removeDuplicates,
            rawUrl = cleaned,
        )
    }

    /**
     * Scans Markdown or plain text for the first valid `/lucky` draw URL.
     * Prefers candidates that specify a valid `drawTimeEpochMillis`, falling back to
     * the first parsed spec without a draw time only if no timed draw link exists.
     */
    fun extractFirstLuckySpec(
        markdown: String,
        fallbackPostId: Long? = null,
    ): LuckyDrawSpec? {
        if (markdown.isBlank()) return null
        var firstParsedWithoutTime: LuckyDrawSpec? = null
        for (match in LUCKY_LINK_CANDIDATE_REGEX.findAll(markdown)) {
            val candidate = trimLuckyUrlCandidate(match.value)
            val parsed = parseLuckyUrl(candidate, fallbackPostId = fallbackPostId) ?: continue
            if (parsed.hasValidDrawTime) return parsed
            if (firstParsedWithoutTime == null) {
                firstParsedWithoutTime = parsed
            }
        }
        return firstParsedWithoutTime
    }

    fun buildLuckyUrl(spec: LuckyDrawSpec): String {
        val timeSegment = spec.drawTimeEpochMillis?.let { "&time=$it" }.orEmpty()
        return "https://www.nodeseek.com/lucky" +
            "?post=${spec.postId}" +
            timeSegment +
            "&count=${spec.winnerCount.coerceAtLeast(1)}" +
            "&start=${spec.firstFloor.coerceAtLeast(0)}" +
            "&duplicate=${!spec.removeDuplicates}" +
            "&mode=view"
    }

    fun computeDrandRound(drawTimeEpochMillis: Long): Long {
        require(drawTimeEpochMillis >= DRAND_GENESIS_EPOCH_MILLIS) {
            "开奖时间早于 Drand 主网创世时间 ($DRAND_GENESIS_EPOCH_MILLIS ms)"
        }
        val revealEpochSeconds = drawTimeEpochMillis.toDouble() / 1000.0
        return floor((revealEpochSeconds - DRAND_GENESIS_EPOCH_SECONDS.toDouble()) / DRAND_PERIOD_SECONDS)
            .toLong()
            .coerceAtLeast(1L)
    }

    /**
     * Canonical bryc `cyrb128` 128-bit string hash producing four unsigned 32-bit seed words.
     */
    internal fun cyrb128(seed: String): UIntArray {
        var h1 = 1779033703 // 0x6a09e667
        var h2 = 3144134277.toInt() // 0xbb67ae85
        var h3 = 1013904242 // 0x3c6ef372
        var h4 = 2773480762.toInt() // 0xa54ff53a
        for (i in seed.indices) {
            val k = seed[i].code
            h1 = h2 xor ((h1 xor k) * 597399067)
            h2 = h3 xor ((h2 xor k) * 2869860233.toInt())
            h3 = h4 xor ((h3 xor k) * 951274213)
            h4 = h1 xor ((h4 xor k) * 2716044179.toInt())
        }
        h1 = (h3 xor (h1 ushr 18)) * 597399067
        h2 = (h4 xor (h2 ushr 22)) * 2869860233.toInt()
        h3 = (h1 xor (h3 ushr 17)) * 951274213
        h4 = (h2 xor (h4 ushr 19)) * 2716044179.toInt()
        h1 = h1 xor (h2 xor h3 xor h4)
        h2 = h2 xor h1
        h3 = h3 xor h1
        h4 = h4 xor h1
        return uintArrayOf(h1.toUInt(), h2.toUInt(), h3.toUInt(), h4.toUInt())
    }

    /**
     * Canonical `sfc32` 32-bit PRNG matching JavaScript `(t >>> 0)` semantics.
     */
    internal class Sfc32(seed: UIntArray) {
        private var a: Int = seed[0].toInt()
        private var b: Int = seed[1].toInt()
        private var c: Int = seed[2].toInt()
        private var d: Int = seed[3].toInt()

        fun nextUInt32(): UInt {
            val t = (a + b) + d
            d = d + 1
            a = b xor (b ushr 9)
            b = c + (c shl 3)
            c = ((c shl 21) or (c ushr 11)) + t
            return t.toUInt()
        }
    }

    private data class ScoredFloor(
        val originalIndex: Int,
        val entry: LuckyFloorEntry,
        val randomValue: UInt,
    )

    /**
     * Deterministic NodeSeek `/lucky` winner selection:
     * 1. Seed `cyrb128` + `sfc32` with `randomnessHex`.
     * 2. Consume one `UInt32` for EVERY floor `0..<floors.size` BEFORE filtering `firstFloor`.
     * 3. Sort scored floors by `randomValue` ascending (stable tie-break by `originalIndex`).
     * 4. Filter `floor.floorId >= spec.firstFloor` FIRST.
     * 5. If `spec.removeDuplicates`, deduplicate by `memberId`.
     * 6. Take `spec.winnerCount` and sort final winners by `floorId` ascending.
     */
    fun drawWinners(
        floors: List<LuckyFloorEntry>,
        spec: LuckyDrawSpec,
        randomnessHex: String,
    ): List<LuckyFloorEntry> {
        val cleanRandomness = randomnessHex.trim()
        if (floors.isEmpty() || cleanRandomness.isEmpty()) return emptyList()

        val prng = Sfc32(cyrb128(cleanRandomness))
        val scored = ArrayList<ScoredFloor>(floors.size)
        for (i in floors.indices) {
            scored += ScoredFloor(
                originalIndex = i,
                entry = floors[i],
                randomValue = prng.nextUInt32(),
            )
        }

        scored.sortWith(compareBy<ScoredFloor>({ it.randomValue }, { it.originalIndex }))

        val seenMembers = if (spec.removeDuplicates) HashSet<Long>() else null
        val targetCount = spec.winnerCount.coerceAtLeast(1)
        val winners = ArrayList<LuckyFloorEntry>(minOf(targetCount, floors.size))

        for (item in scored) {
            val floor = item.entry
            if (floor.floorId < spec.firstFloor) continue
            if (seenMembers != null && !seenMembers.add(floor.memberId)) continue
            winners += floor
            if (winners.size >= targetCount) break
        }

        return winners.sortedBy { it.floorId }
    }

    fun evaluateViewerStatus(
        spec: LuckyDrawSpec,
        floors: List<LuckyFloorEntry>,
        winners: List<LuckyFloorEntry>,
        viewerUid: Long?,
        isDrawn: Boolean,
    ): Triple<LuckyViewerStatus, List<LuckyFloorEntry>, List<LuckyFloorEntry>> {
        if (viewerUid == null || viewerUid <= 0L) {
            return Triple(LuckyViewerStatus.NOT_LOGGED_IN, emptyList(), emptyList())
        }
        val viewerFloors = floors.filter { it.memberId == viewerUid }.sortedBy { it.floorId }
        val viewerWinningFloors = winners.filter { it.memberId == viewerUid }.sortedBy { it.floorId }

        val status = when {
            isDrawn && viewerWinningFloors.isNotEmpty() -> LuckyViewerStatus.MY_WIN
            viewerFloors.isEmpty() -> LuckyViewerStatus.NOT_PARTICIPATED
            viewerFloors.none { it.floorId >= spec.firstFloor } -> LuckyViewerStatus.INELIGIBLE_FLOOR
            isDrawn -> LuckyViewerStatus.PARTICIPATED_NOT_WON
            else -> LuckyViewerStatus.PARTICIPATED_PENDING
        }
        return Triple(status, viewerFloors, viewerWinningFloors)
    }

    fun buildWinnerReplyMarkdown(outcome: LuckyDrawOutcome): String {
        val verifyUrl = buildLuckyUrl(outcome.spec)
        if (outcome.winners.isEmpty()) {
            return buildString {
                appendLine("### 🎲 本次抽奖结果公示")
                appendLine()
                appendLine("暂无符合条件的有效中奖楼层。")
                outcome.drandRound?.let { round ->
                    appendLine("- Drand Round: `$round`")
                }
                append("- 验奖链接：[查看官方开奖校验]($verifyUrl)")
            }
        }

        val uniqueMentions = outcome.winners
            .map { it.memberName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ") { "@$it" }

        return buildString {
            appendLine("### 🎉 开奖结果公示")
            appendLine()
            if (uniqueMentions.isNotEmpty()) {
                appendLine("恭喜以下中奖用户：$uniqueMentions")
                appendLine()
            }
            outcome.winners.forEach { winner ->
                val cleanName = winner.memberName.trim()
                if (cleanName.isNotEmpty()) {
                    appendLine("- #${winner.floorId}楼 @$cleanName (UID: ${winner.memberId})")
                } else {
                    appendLine("- #${winner.floorId}楼 UID ${winner.memberId}")
                }
            }
            appendLine()
            outcome.drandRound?.let { round ->
                appendLine("- Drand Round: `$round`")
            }
            outcome.beacon?.randomness?.takeIf { it.isNotBlank() }?.let { rand ->
                appendLine("- Randomness: `$rand`")
            }
            append("- 链上可验证链接：[查看 Drand 开奖详情]($verifyUrl)")
        }
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val eqIdx = pair.indexOf('=')
            val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
            val rawValue = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
            val key = decodeQueryComponent(rawKey).trim().lowercase(Locale.ROOT)
            if (key.isNotEmpty() && !result.containsKey(key)) {
                result[key] = decodeQueryComponent(rawValue).trim()
            }
        }
        return result
    }

    private fun decodeQueryComponent(raw: String): String {
        if (raw.isEmpty()) return ""
        return runCatching {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        }.getOrDefault(raw)
    }

    private fun parseTimestampEpochMillis(raw: String?): Long? {
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val numeric = clean.toLongOrNull()
        if (numeric != null) {
            return when {
                numeric >= MIN_VALID_EPOCH_MILLIS -> numeric
                numeric in MIN_VALID_EPOCH_SECONDS..MAX_VALID_EPOCH_SECONDS -> numeric * 1_000L
                else -> null
            }
        }
        return runCatching { Instant.parse(clean).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(clean).toInstant().toEpochMilli() }
            .getOrNull()
    }
}
