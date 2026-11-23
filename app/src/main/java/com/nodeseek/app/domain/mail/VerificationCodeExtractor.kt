package com.nodeseek.app.domain.mail

/**
 * Pure-Kotlin verification/OTP extractor that prefers server-extracted codes and falls back to
 * proximity-based scanning around Chinese and English verification keywords.
 */
object VerificationCodeExtractor {

    private const val FORWARD_WINDOW_CHARS = 120
    private const val BACKWARD_WINDOW_CHARS = 64
    private const val DIRECT_LABEL_PREFIX_CHARS = 40
    private const val DIRECT_LABEL_SUFFIX_CHARS = 48
    private const val BRIDGED_BODY_HEAD_CHARS = 24

    private val URL_REGEX = Regex("""(?:https?|ftp)://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val HTML_ENTITY_REGEX = Regex("""&#x?[0-9A-Fa-f]+;""")
    private val HEX_COLOR_REGEX = Regex("""#[0-9A-Fa-f]{3,8}\b""")
    private val UUID_REGEX = Regex(
        """\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b""",
    )
    private val DATE_REGEX = Regex(
        """\b(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/.]\d{1,2}[-/.]\d{4})\b""",
    )
    private val TIME_REGEX = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")
    private val PHONE_LIKE_REGEX = Regex("""(?<!\w)(?:\+\d{1,3}[-\s])?\d{3,4}-\d{4}(?!\w)""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    private val KEYWORD_REGEX = Regex(
        pattern = buildString {
            append("(?:")
            append("验证码|校验码|动态码|安全码|登录码|确认码|一次性密码|认证码")
            append("|(?:verification|security|login|sign[\\s-]?in|auth|authentication|confirmation|confirm|verify|activation|reset|access)\\s+code")
            append("|pass\\s?code")
            append("|one[-\\s]?time(?:\\s+(?:password|passcode|code|pin))?")
            append("|\\botp\\b|\\b2fa\\b|\\bpin\\b")
            append("|\\bcode\\s+is\\b|\\bcode\\s*[:：=]")
            append(")")
        },
        option = RegexOption.IGNORE_CASE,
    )

    // Group 1 captures the 4-8 char numeric or uppercase-alphanumeric candidate token.
    private val CANDIDATE_TOKEN_REGEX = Regex(
        """(?:^|[^A-Za-z0-9#])([A-Z0-9]{4,8})(?=$|[^A-Za-z0-9])""",
    )

    private val DIRECT_PREFIX_LABEL_REGEX = Regex(
        pattern = buildString {
            append("(?:")
            append("验证码|校验码|动态码|安全码|登录码|确认码|一次性密码|认证码")
            append("|(?:verification|security|login|sign[\\s-]?in|auth|authentication|confirmation|confirm|verify|activation|reset|access)\\s+code")
            append("|pass\\s?code")
            append("|one[-\\s]?time(?:\\s+(?:password|passcode|code|pin))?")
            append("|\\botp\\b|\\b2fa\\b|\\bpin\\b|\\bcode")
            append(")")
            append("(?:\\s*(?:is|are|为|是|如下|[:：=]))*\\s*$")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val DIRECT_SUFFIX_LABEL_REGEX = Regex(
        pattern = buildString {
            append("^\\s*(?:")
            append("(?:is|are)\\s+(?:your|the)(?:\\s+(?:one[-\\s]?time|verification|security|login|confirmation|2fa|otp))?\\s*(?:code|pass\\s?code|otp|pin)")
            append("|(?:是|为)(?:您的|你的)?\\s*(?:验证码|校验码|动态码|安全码|登录码|确认码|一次性密码|认证码)")
            append("|[(（【\\[]\\s*(?:验证码|code|otp|2fa|pin)\\s*[)）】\\]]")
            append(")")
        },
        option = RegexOption.IGNORE_CASE,
    )

    // Keep \b only on ASCII unit words since CJK characters and '%' are non-word chars (\W).
    private val UNIT_SUFFIX_REGEX = Regex(
        """^\s*(?:秒|分钟|小时|天|年|月|日|%|(?:px|pt|em|rem|ms|kb|mb|gb|tb|seconds?|minutes?|hours?|days?)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val COPYRIGHT_YEAR_PREFIX_REGEX = Regex(
        """(?:©|copyright)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val LEADING_BODY_CONNECTOR_REGEX = Regex(
        """^(?:[:：=\-\s]|is\s+|为\s*|是\s*)*([A-Z0-9]{4,8})(?=$|[^A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    // Segmented OTP formats commonly used by Google, GitHub, Cloudflare, etc.:
    // e.g. "482-910", "482 910", "4829-1038", "G-123456".
    private val SEGMENTED_OTP_REGEX = Regex(
        """(?:^|[^A-Za-z0-9#-])(?:[A-Z]-(\d{5,6})|(\d{3})[-\s](\d{3})|(\d{4})-(\d{4}))(?=$|[^A-Za-z0-9-])""",
    )

    private val BLOCKED_TOKENS = setOf(
        "UTF8",
        "UTF16",
        "UTF32",
        "BASE64",
        "SHA1",
        "SHA256",
        "SHA384",
        "SHA512",
        "MD5",
        "HTTP2",
        "HTTP3",
        "HTML4",
        "HTML5",
        "CSS3",
        "IPV4",
        "IPV6",
        "TLS1",
        "TLS12",
        "TLS13",
        "POP3",
        "IMAP4",
        "RFC822",
        "X509",
        "PKCS7",
        "PKCS8",
        "WIN32",
        "WIN64",
        "ARM64",
        "AMD64",
    )

    fun resolveCode(
        serverCode: String?,
        subject: String,
        bodyText: String,
    ): String? {
        serverCode?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val cleanSubject = sanitizeForOtpScan(subject)
        val cleanBody = sanitizeForOtpScan(bodyText)

        val subjectBest = extractBestCandidate(cleanSubject)
        if (subjectBest != null && subjectBest.hasDirectLabel) return subjectBest.code
        extractDirectSegmentedCode(subject)?.let { return it }
        if (subjectBest != null) return subjectBest.code

        val bodyBest = extractBestCandidate(cleanBody)
        if (bodyBest != null && bodyBest.hasDirectLabel) return bodyBest.code
        extractDirectSegmentedCode(bodyText)?.let { return it }
        if (bodyBest != null) return bodyBest.code

        // Handle messages whose subject states the OTP label and body begins directly with the code.
        if (cleanSubject.isNotEmpty() && cleanBody.isNotEmpty() && KEYWORD_REGEX.containsMatchIn(cleanSubject)) {
            val head = cleanBody.take(BRIDGED_BODY_HEAD_CHARS)
            if (LEADING_BODY_CONNECTOR_REGEX.containsMatchIn(head)) {
                extractBestCandidate("$cleanSubject $head")?.let { return it.code }
            }
            val rawHead = sanitizeForOtpScan(bodyText, stripPhoneLike = false).take(BRIDGED_BODY_HEAD_CHARS)
            extractDirectSegmentedCode("$cleanSubject $rawHead")?.let { return it }
        }

        return null
    }

    private fun sanitizeForOtpScan(
        raw: String,
        stripPhoneLike: Boolean = true,
    ): String {
        if (raw.isBlank()) return ""
        var cleaned = raw
            .replace(URL_REGEX, " ")
            .replace(EMAIL_REGEX, " ")
            .replace(HTML_ENTITY_REGEX, " ")
            .replace(HEX_COLOR_REGEX, " ")
            .replace(UUID_REGEX, " ")
            .replace(DATE_REGEX, " ")
            .replace(TIME_REGEX, " ")
        if (stripPhoneLike) {
            cleaned = cleaned.replace(PHONE_LIKE_REGEX, " ")
        }
        return cleaned
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun extractDirectSegmentedCode(raw: String): String? {
        val text = sanitizeForOtpScan(raw, stripPhoneLike = false)
        if (text.isEmpty() || !KEYWORD_REGEX.containsMatchIn(text)) return null

        for (match in SEGMENTED_OTP_REGEX.findAll(text)) {
            val (code, start, end) = when {
                match.groups[1] != null -> {
                    val g = match.groups[1]!!
                    Triple(g.value, (g.range.first - 2).coerceAtLeast(0), g.range.last + 1)
                }
                match.groups[2] != null && match.groups[3] != null -> {
                    val g2 = match.groups[2]!!
                    val g3 = match.groups[3]!!
                    Triple("${g2.value}${g3.value}", g2.range.first, g3.range.last + 1)
                }
                match.groups[4] != null && match.groups[5] != null -> {
                    val g4 = match.groups[4]!!
                    val g5 = match.groups[5]!!
                    Triple("${g4.value}${g5.value}", g4.range.first, g5.range.last + 1)
                }
                else -> continue
            }

            val beforeSlice = text.substring((start - DIRECT_LABEL_PREFIX_CHARS).coerceAtLeast(0), start)
            val afterSlice = text.substring(end, (end + DIRECT_LABEL_SUFFIX_CHARS).coerceAtMost(text.length))
            val hasDirectLabel = DIRECT_PREFIX_LABEL_REGEX.containsMatchIn(beforeSlice) ||
                DIRECT_SUFFIX_LABEL_REGEX.containsMatchIn(afterSlice)
            if (!hasDirectLabel) continue

            val afterShort = text.substring(end, (end + 16).coerceAtMost(text.length))
            if (UNIT_SUFFIX_REGEX.containsMatchIn(afterShort)) continue
            return code
        }
        return null
    }

    private data class ScoredCandidate(
        val code: String,
        val score: Int,
        val index: Int,
        val hasDirectLabel: Boolean,
    )

    private fun extractBestCandidate(text: String): ScoredCandidate? {
        if (text.isEmpty()) return null
        val keywordMatches = KEYWORD_REGEX.findAll(text).toList()
        if (keywordMatches.isEmpty()) return null

        var best: ScoredCandidate? = null
        for (match in CANDIDATE_TOKEN_REGEX.findAll(text)) {
            val group = match.groups[1] ?: continue
            val token = group.value
            val start = group.range.first
            val end = group.range.last + 1

            if (!isEligibleToken(token, text, start, end)) continue

            val beforeSlice = text.substring((start - DIRECT_LABEL_PREFIX_CHARS).coerceAtLeast(0), start)
            val afterSlice = text.substring(end, (end + DIRECT_LABEL_SUFFIX_CHARS).coerceAtMost(text.length))
            val hasDirectLabel = DIRECT_PREFIX_LABEL_REGEX.containsMatchIn(beforeSlice) ||
                DIRECT_SUFFIX_LABEL_REGEX.containsMatchIn(afterSlice)

            if (isCalendarYear(token) && !hasDirectLabel) continue

            val minDistance = minKeywordDistance(start, end, keywordMatches) ?: continue
            val score = computeScore(token, minDistance, hasDirectLabel)

            if (best == null || score > best.score || (score == best.score && start < best.index)) {
                best = ScoredCandidate(
                    code = token,
                    score = score,
                    index = start,
                    hasDirectLabel = hasDirectLabel,
                )
            }
        }
        return best
    }

    private fun isEligibleToken(
        token: String,
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        if (token.length !in 4..8) return false
        // Require at least one digit so pure alphabetic words (CODE, HTML, HTTP, SMTP, MIME) never match.
        if (!token.any { it.isDigit() }) return false
        if (token in BLOCKED_TOKENS) return false

        val after = text.substring(end, (end + 16).coerceAtMost(text.length))
        if (token.all { it.isDigit() } && UNIT_SUFFIX_REGEX.containsMatchIn(after)) {
            return false
        }

        if (isCalendarYear(token)) {
            val prefix = text.substring((start - 16).coerceAtLeast(0), start)
            if (COPYRIGHT_YEAR_PREFIX_REGEX.containsMatchIn(prefix)) return false
        }

        if (start > 0 && text[start - 1] == '#') return false
        return true
    }

    private fun isCalendarYear(token: String): Boolean {
        if (token.length != 4 || !token.all { it.isDigit() }) return false
        val numeric = token.toIntOrNull() ?: return false
        return numeric in 1990..2039
    }

    private fun minKeywordDistance(
        tokenStart: Int,
        tokenEnd: Int,
        keywordMatches: List<MatchResult>,
    ): Int? {
        var minDistance: Int? = null
        for (kw in keywordMatches) {
            val kwStart = kw.range.first
            val kwEnd = kw.range.last + 1
            val distance = when {
                tokenStart >= kwEnd -> {
                    val d = tokenStart - kwEnd
                    if (d <= FORWARD_WINDOW_CHARS) d else null
                }
                kwStart >= tokenEnd -> {
                    val d = kwStart - tokenEnd
                    if (d <= BACKWARD_WINDOW_CHARS) d else null
                }
                else -> 0
            }
            if (distance != null && (minDistance == null || distance < minDistance)) {
                minDistance = distance
            }
        }
        return minDistance
    }

    private fun computeScore(
        token: String,
        distance: Int,
        hasDirectLabel: Boolean,
    ): Int {
        var score = 0
        if (hasDirectLabel) score += 100
        score += (60 - distance / 2).coerceAtLeast(0)
        score += when {
            token.all { it.isDigit() } && token.length == 6 -> 20
            token.all { it.isDigit() } && token.length in 5..8 -> 12
            token.all { it.isDigit() } -> 8
            else -> 5
        }
        return score
    }
}
