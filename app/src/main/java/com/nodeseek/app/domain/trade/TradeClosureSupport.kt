package com.nodeseek.app.domain.trade

import com.nodeseek.app.domain.fleet.FleetDeliveryMethod
import com.nodeseek.app.domain.ssh.IpPrivacyMasker

data class TradePrivacyOptions(
    val maskIpAddresses: Boolean = true,
    val maskEmails: Boolean = true,
    val maskCredentials: Boolean = true,
    val maskOrderAndContactIds: Boolean = true,
    val appendClosureNote: Boolean = true,
    val knownHostOrIp: String? = null,
)

data class TradeSanitizeResult(
    val sanitizedMarkdown: String,
    val redactionCount: Int,
    val ipRedactions: Int = 0,
    val emailRedactions: Int = 0,
    val credentialRedactions: Int = 0,
    val orderAndContactRedactions: Int = 0,
    val closureNoteAppended: Boolean = false,
) {
    val changed: Boolean
        get() = redactionCount > 0 || closureNoteAppended
}

data class TradeChecklistStep(
    val id: String,
    val title: String,
    val description: String,
    val isCritical: Boolean = false,
)

object TradeTitleFormatter {
    const val SOLD_PREFIX = "[已出]"

    private val SOLD_PREFIX_REGEX = Regex(
        """^\s*(?:\[(?:已出|已出掉|出掉了|已成交|sold|closed)]|【(?:已出|已出掉|出掉了|已成交|sold|closed)】)\s*""",
        RegexOption.IGNORE_CASE,
    )

    private val INLINE_SOLD_MARKER_REGEX = Regex(
        """(?:\[(?:已出|已出掉|已成交|sold|closed)]|【(?:已出|已出掉|已成交|sold|closed)】)""",
        RegexOption.IGNORE_CASE,
    )

    fun isMarkedSold(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        return SOLD_PREFIX_REGEX.containsMatchIn(trimmed) ||
            INLINE_SOLD_MARKER_REGEX.containsMatchIn(trimmed)
    }

    fun applySoldPrefix(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return SOLD_PREFIX
        if (isMarkedSold(trimmed)) return trimmed
        return "$SOLD_PREFIX $trimmed"
    }

    fun removeSoldPrefix(title: String): String =
        title.trim().replace(SOLD_PREFIX_REGEX, "").trim()
}

object TradePrivacySanitizer {
    const val CLOSURE_NOTE_MARKER = "已出结帖"
    const val CLOSURE_NOTE_LINE = "> 📦 **已出结帖**：敏感信息已脱敏处理。"

    // Matches standard email literals outside protected URLs.
    private val EMAIL_REGEX = Regex(
        """(?<![A-Za-z0-9._%+\-/])([A-Za-z0-9._%+\-]{1,64})@([A-Za-z0-9.\-]+\.[A-Za-z]{2,24})(?![A-Za-z0-9.\-])""",
    )

    private val CREDENTIAL_KEY_PREFIXES = arrayOf(
        "root", "ssh", "vps", "vnc", "ipmi", "panel", "admin", "test", "temp", "backup",
        "new", "login", "init", "default", "console", "server", "host", "node", "user",
        "mysql", "sftp", "ftp", "db", "api",
        "控制台", "面板", "登录", "初始", "后台", "测试", "临时", "备用", "新", "原", "旧",
        "小鸡", "机器", "管理", "主机", "节点", "账户", "账号", "邮箱", "原油", "宝塔",
        "探针", "控制", "开机", "系统",
    )
    private val CREDENTIAL_BASE_KEYS = arrayOf(
        "password",
        "secret_key",
        "access_key",
        "api_key",
        "passwd",
        "apikey",
        "secret",
        "token",
        "pass",
        "uuid",
        "pwd",
        "密码",
        "密钥",
        "秘钥",
    )

    private val CREDENTIAL_ASCII_START_CHARS = BooleanArray(128).apply {
        for (key in CREDENTIAL_KEY_PREFIXES) {
            val c = key[0]
            if (c.code < 128) this[c.lowercaseChar().code] = true
        }
        for (key in CREDENTIAL_BASE_KEYS) {
            val c = key[0]
            if (c.code < 128) this[c.lowercaseChar().code] = true
        }
    }

    private val CREDENTIAL_NON_ASCII_START_CHARS: Set<Char> = buildSet {
        for (key in CREDENTIAL_KEY_PREFIXES) {
            val c = key[0]
            if (c.code >= 128) add(c)
        }
        for (key in CREDENTIAL_BASE_KEYS) {
            val c = key[0]
            if (c.code >= 128) add(c)
        }
    }

    private val ORDER_OR_CONTACT_SIMPLE_KEYS = arrayOf(
        "telegram",
        "order_id",
        "wechat",
        "订单号",
        "账单号",
        "交易号",
        "流水号",
        "微信",
        "tg",
        "vx",
        "qq",
    )

    private val ORDER_CONTACT_VALUE_REGEX = Regex(
        """(?:(?:t\.me|telegram\.me)/[A-Za-z0-9_.*\-]+|@?[A-Za-z0-9_.*\-]{4,})""",
        RegexOption.IGNORE_CASE,
    )

    private val MASKED_IP_FRAGMENT_REGEX = Regex("""(?:\d{1,3}\.\d{1,3}\.\*\.\*|[0-9a-fA-F]{1,4}:[0-9a-fA-F]{0,4}:\*:\*)""")

    fun sanitize(
        markdown: String,
        options: TradePrivacyOptions = TradePrivacyOptions(),
    ): TradeSanitizeResult {
        if (markdown.isEmpty()) {
            return TradeSanitizeResult(
                sanitizedMarkdown = markdown,
                redactionCount = 0,
            )
        }

        var working = markdown
        var ipRedactions = 0
        var emailRedactions = 0
        var credentialRedactions = 0
        var orderAndContactRedactions = 0

        if (options.maskIpAddresses) {
            val (ipSanitized, count) = transformOutsideProtectedSpans(working) { segment ->
                val masked = IpPrivacyMasker.mask(segment, knownServerHost = options.knownHostOrIp)
                val maskedWithBracketedV6 = maskBracketedIpv6InTradeSegment(masked)
                if (maskedWithBracketedV6 != segment) {
                    val beforeCount = MASKED_IP_FRAGMENT_REGEX.findAll(segment).count()
                    val afterCount = MASKED_IP_FRAGMENT_REGEX.findAll(maskedWithBracketedV6).count()
                    maskedWithBracketedV6 to (afterCount - beforeCount).coerceAtLeast(1)
                } else {
                    segment to 0
                }
            }
            working = ipSanitized
            ipRedactions = count
        }

        if (options.maskCredentials) {
            val (credSanitized, count) = transformOutsideProtectedSpans(working) { segment ->
                replaceCredentialKeyValues(segment)
            }
            working = credSanitized
            credentialRedactions = count
        }

        if (options.maskEmails) {
            val (emailSanitized, count) = transformOutsideProtectedSpans(working) { segment ->
                var localCount = 0
                val replaced = EMAIL_REGEX.replace(segment) { match ->
                    val local = match.groupValues[1]
                    val domain = match.groupValues[2]
                    if (local.contains("***") || local == "*") {
                        match.value
                    } else {
                        localCount++
                        val prefix = local.take(1)
                        "$prefix***@$domain"
                    }
                }
                replaced to localCount
            }
            working = emailSanitized
            emailRedactions = count
        }

        if (options.maskOrderAndContactIds) {
            val (orderSanitized, count) = transformOutsideProtectedSpans(working) { segment ->
                replaceOrderOrContactKeyValues(segment)
            }
            working = orderSanitized
            orderAndContactRedactions = count
        }

        val totalRedactions = ipRedactions + emailRedactions + credentialRedactions + orderAndContactRedactions
        var closureNoteAppended = false
        if (options.appendClosureNote && !working.contains(CLOSURE_NOTE_MARKER)) {
            working = working.trimEnd()
            working = if (working.isEmpty()) {
                CLOSURE_NOTE_LINE
            } else {
                "$working\n\n$CLOSURE_NOTE_LINE"
            }
            closureNoteAppended = true
        }

        return TradeSanitizeResult(
            sanitizedMarkdown = working,
            redactionCount = totalRedactions,
            ipRedactions = ipRedactions,
            emailRedactions = emailRedactions,
            credentialRedactions = credentialRedactions,
            orderAndContactRedactions = orderAndContactRedactions,
            closureNoteAppended = closureNoteAppended,
        )
    }

    private fun replaceCredentialKeyValues(segment: String): Pair<String, Int> =
        replaceKeyValues(
            segment = segment,
            quickStartCheck = ::canStartCredentialKey,
            matchKeyEnd = ::matchCredentialKeyEnd,
            isSeparator = { ch -> ch == ':' || ch == '=' || ch == '：' },
            matchBareValueEnd = { seg, valStart -> scanBareCredentialEnd(seg, valStart) },
            formatMask = { rawValue ->
                if (isAlreadyMaskedValue(rawValue)) null else "******"
            },
            allowHanBeforeHanKey = true,
        )

    private fun replaceOrderOrContactKeyValues(segment: String): Pair<String, Int> =
        replaceKeyValues(
            segment = segment,
            quickStartCheck = ::canStartOrderOrContactKey,
            matchKeyEnd = ::matchOrderOrContactKeyEnd,
            isSeparator = { ch -> ch == ':' || ch == '=' || ch == '：' || ch == '#' },
            matchBareValueEnd = { seg, valStart ->
                val match = ORDER_CONTACT_VALUE_REGEX.matchAt(seg, valStart)
                if (match != null) match.range.last + 1 else -1
            },
            formatMask = { rawValue ->
                if (isAlreadyMaskedValue(rawValue) ||
                    rawValue.startsWith("http://", ignoreCase = true) ||
                    rawValue.startsWith("https://", ignoreCase = true)
                ) {
                    null
                } else {
                    maskMiddleIdentifier(rawValue)
                }
            },
            validateQuotedValue = { seg, valContentStart, valContentEnd ->
                val match = ORDER_CONTACT_VALUE_REGEX.matchAt(seg, valContentStart)
                match != null && match.range.last + 1 == valContentEnd
            },
        )

    private inline fun replaceKeyValues(
        segment: String,
        quickStartCheck: (Char) -> Boolean,
        matchKeyEnd: (String, Int) -> Int,
        isSeparator: (Char) -> Boolean,
        matchBareValueEnd: (String, Int) -> Int,
        formatMask: (String) -> String?,
        validateQuotedValue: (String, Int, Int) -> Boolean = { _, _, _ -> true },
        allowHanBeforeHanKey: Boolean = false,
    ): Pair<String, Int> {
        val n = segment.length
        if (n < 3) return segment to 0

        var out: StringBuilder? = null
        var flushCursor = 0
        var localCount = 0
        // Memoize quote scans by quote kind: 0:", 1:', 2:`, 3:“..”, 4:‘..’
        val noCloseUntilByKind = IntArray(5) { -1 }
        val nextCloseQuoteByKind = IntArray(5) { -1 }

        var i = 0
        while (i < n) {
            val ch = segment[i]
            if (!quickStartCheck(ch)) {
                i++
                continue
            }
            if (i > 0 && !isAllowedLeadingBoundaryAt(segment, i, allowHanBeforeHanKey)) {
                i++
                continue
            }

            val keyEnd = matchKeyEnd(segment, i)
            if (keyEnd <= i) {
                i++
                continue
            }

            val sepIdx = skipHorizontalSpacing(segment, keyEnd)
            if (sepIdx >= n || !isSeparator(segment[sepIdx])) {
                i = keyEnd
                continue
            }

            val valStart = skipHorizontalSpacing(segment, sepIdx + 1)
            if (valStart >= n) {
                break
            }

            val firstValChar = segment[valStart]
            val closingQuoteChar = when (firstValChar) {
                '"', '\'', '`' -> firstValChar
                '“' -> '”'
                '‘' -> '’'
                else -> '\u0000'
            }
            if (closingQuoteChar != '\u0000') {
                val closeQuote = findClosingQuoteOnSameLine(
                    segment = segment,
                    openQuoteIdx = valStart,
                    openQuoteChar = firstValChar,
                    closeQuoteChar = closingQuoteChar,
                    noCloseUntilByKind = noCloseUntilByKind,
                    nextCloseQuoteByKind = nextCloseQuoteByKind,
                )
                if (closeQuote <= valStart + 1) {
                    i = valStart + 1
                    continue
                }
                if (!validateQuotedValue(segment, valStart + 1, closeQuote)) {
                    i = valStart + 1
                    continue
                }
                val rawValue = segment.substring(valStart + 1, closeQuote)
                val masked = formatMask(rawValue)
                if (masked == null) {
                    i = closeQuote + 1
                    continue
                }
                val sb = out ?: StringBuilder(n).also { out = it }
                sb.append(segment, flushCursor, valStart + 1)
                sb.append(masked)
                sb.append(closingQuoteChar)
                flushCursor = closeQuote + 1
                localCount++
                i = closeQuote + 1
            } else {
                val valEnd = matchBareValueEnd(segment, valStart)
                if (valEnd <= valStart) {
                    i = valStart
                    continue
                }
                val rawValue = segment.substring(valStart, valEnd)
                val masked = formatMask(rawValue)
                if (masked == null) {
                    i = valEnd
                    continue
                }
                val sb = out ?: StringBuilder(n).also { out = it }
                sb.append(segment, flushCursor, valStart)
                sb.append(masked)
                flushCursor = valEnd
                localCount++
                i = valEnd
            }
        }

        val sb = out ?: return segment to 0
        if (flushCursor < n) {
            sb.append(segment, flushCursor, n)
        }
        return sb.toString() to localCount
    }

    private val CREDENTIAL_PREFIXES_BY_FIRST_CHAR: Map<Char, Array<String>> =
        CREDENTIAL_KEY_PREFIXES
            .groupBy { it[0].lowercaseChar() }
            .mapValues { (_, list) -> list.toTypedArray() }

    private val CREDENTIAL_BASE_KEYS_BY_FIRST_CHAR: Map<Char, Array<String>> =
        CREDENTIAL_BASE_KEYS
            .groupBy { it[0].lowercaseChar() }
            .mapValues { (_, list) -> list.toTypedArray() }

    private fun canStartCredentialKey(ch: Char): Boolean {
        if (ch.code < 128) {
            val lcCode = if (ch in 'A'..'Z') ch.code + 32 else ch.code
            return CREDENTIAL_ASCII_START_CHARS[lcCode]
        }
        return ch in CREDENTIAL_NON_ASCII_START_CHARS
    }

    private fun canStartOrderOrContactKey(ch: Char): Boolean {
        val lc = if (ch in 'A'..'Z') (ch.code + 32).toChar() else ch
        return lc == 'o' || lc == 'i' || lc == 't' || lc == 'v' || lc == 'q' || lc == 'w' ||
            lc == '订' || lc == '账' || lc == '交' || lc == '流' || lc == '微'
    }

    private fun matchCredentialKeyEnd(segment: String, start: Int): Int {
        if (start >= segment.length) return -1
        val baseBucket = CREDENTIAL_BASE_KEYS_BY_FIRST_CHAR[segment[start].lowercaseChar()]
        if (baseBucket != null) {
            val baseDirect = matchAnyLiteral(segment, start, baseBucket)
            if (baseDirect > start) return baseDirect
        }

        val firstBucket = CREDENTIAL_PREFIXES_BY_FIRST_CHAR[segment[start].lowercaseChar()] ?: return -1
        val firstPrefixEnd = matchAnyLiteral(segment, start, firstBucket)
        if (firstPrefixEnd > start) {
            val afterFirst = skipKeyCompoundSeparator(segment, firstPrefixEnd)
            if (afterFirst < segment.length) {
                val oneBucket = CREDENTIAL_BASE_KEYS_BY_FIRST_CHAR[segment[afterFirst].lowercaseChar()]
                if (oneBucket != null) {
                    val onePrefixBase = matchAnyLiteral(segment, afterFirst, oneBucket)
                    if (onePrefixBase > afterFirst) return onePrefixBase
                }

                val secondBucket = CREDENTIAL_PREFIXES_BY_FIRST_CHAR[segment[afterFirst].lowercaseChar()]
                if (secondBucket != null) {
                    val secondPrefixEnd = matchAnyLiteral(segment, afterFirst, secondBucket)
                    if (secondPrefixEnd > afterFirst) {
                        val afterSecond = skipKeyCompoundSeparator(segment, secondPrefixEnd)
                        if (afterSecond < segment.length) {
                            val twoBucket = CREDENTIAL_BASE_KEYS_BY_FIRST_CHAR[segment[afterSecond].lowercaseChar()]
                            if (twoBucket != null) {
                                val twoPrefixBase = matchAnyLiteral(segment, afterSecond, twoBucket)
                                if (twoPrefixBase > afterSecond) return twoPrefixBase
                            }
                        }
                    }
                }
            }
        }
        return -1
    }

    private fun matchOrderOrContactKeyEnd(segment: String, start: Int): Int {
        if (segment.regionMatches(start, "order", 0, 5, ignoreCase = true)) {
            val afterOrder = skipKeyCompoundSeparator(segment, start + 5)
            if (segment.regionMatches(afterOrder, "id", 0, 2, ignoreCase = true)) {
                return afterOrder + 2
            }
        }
        if (segment.regionMatches(start, "invoice", 0, 7, ignoreCase = true)) {
            val afterInvoice = skipKeyCompoundSeparator(segment, start + 7)
            if (segment.regionMatches(afterInvoice, "id", 0, 2, ignoreCase = true)) {
                return afterInvoice + 2
            }
            return start + 7
        }
        return matchAnyLiteral(segment, start, ORDER_OR_CONTACT_SIMPLE_KEYS)
    }

    private fun matchAnyLiteral(segment: String, start: Int, candidates: Array<String>): Int {
        for (candidate in candidates) {
            if (segment.regionMatches(start, candidate, 0, candidate.length, ignoreCase = true)) {
                return start + candidate.length
            }
        }
        return -1
    }

    private fun skipHorizontalSpacing(segment: String, fromIndex: Int): Int {
        val n = segment.length
        var p = fromIndex
        while (p < n) {
            val cp = Character.codePointAt(segment, p)
            if (!isHorizontalSpacing(cp)) break
            p += Character.charCount(cp)
        }
        return p
    }

    private fun skipKeyCompoundSeparator(segment: String, fromIndex: Int): Int {
        val afterWs = skipHorizontalSpacing(segment, fromIndex)
        if (afterWs < segment.length && (segment[afterWs] == '_' || segment[afterWs] == '-')) {
            return skipHorizontalSpacing(segment, afterWs + 1)
        }
        return afterWs
    }

    private fun maskBracketedIpv6InTradeSegment(segment: String): String {
        if ('[' !in segment || ':' !in segment) return segment
        return BRACKETED_IPV6_TRADE_REGEX.replace(segment) { match ->
            val rawInner = match.groupValues[1]
            if (rawInner.contains('*') || !IpPrivacyMasker.isValidIpv6Address(rawInner)) {
                match.value
            } else {
                "[${maskIpv6ForTradeBracket(rawInner)}]"
            }
        }
    }

    private fun maskIpv6ForTradeBracket(address: String): String {
        if (address.startsWith("::")) {
            return "0:0:*:*"
        }
        var g1Start = -1
        var g1End = -1
        var g2Start = -1
        var g2End = -1
        var sawDoubleColonBeforeG2 = false
        var segStart = 0
        for (idx in 0..address.length) {
            if (idx == address.length || address[idx] == ':') {
                if (idx > segStart) {
                    if (g1Start < 0) {
                        g1Start = segStart
                        g1End = idx
                    } else if (g2Start < 0) {
                        g2Start = segStart
                        g2End = idx
                        break
                    }
                } else if (g1Start >= 0 && idx < address.length) {
                    sawDoubleColonBeforeG2 = true
                    break
                }
                segStart = idx + 1
            }
        }
        return when {
            sawDoubleColonBeforeG2 && g1Start >= 0 -> "${address.substring(g1Start, g1End)}:0:*:*"
            g2Start >= 0 -> "${address.substring(g1Start, g1End)}:${address.substring(g2Start, g2End)}:*:*"
            g1Start >= 0 -> "${address.substring(g1Start, g1End)}:*:*"
            else -> "0:0:*:*"
        }
    }

    private val BRACKETED_IPV6_TRADE_REGEX = Regex("""\[([0-9A-Fa-f:]+)(?:%[^\]]+)?\]""")

    private fun findClosingQuoteOnSameLine(
        segment: String,
        openQuoteIdx: Int,
        openQuoteChar: Char,
        closeQuoteChar: Char,
        noCloseUntilByKind: IntArray,
        nextCloseQuoteByKind: IntArray,
    ): Int {
        val kind = when (openQuoteChar) {
            '"' -> 0
            '\'' -> 1
            '`' -> 2
            '“' -> 3
            else -> 4
        }
        val n = segment.length
        val start = openQuoteIdx + 1
        if (start <= noCloseUntilByKind[kind]) {
            return -1
        }
        if (start <= nextCloseQuoteByKind[kind]) {
            return nextCloseQuoteByKind[kind]
        }
        var q = start
        while (q < n) {
            val ch = segment[q]
            if (ch == '\r' || ch == '\n' || ch == '\u2028' || ch == '\u2029') {
                noCloseUntilByKind[kind] = q
                return -1
            }
            if (ch == closeQuoteChar) {
                nextCloseQuoteByKind[kind] = q
                return q
            }
            q++
        }
        noCloseUntilByKind[kind] = n
        return -1
    }

    private fun scanBareCredentialEnd(segment: String, valStart: Int): Int {
        val n = segment.length
        var p = valStart
        while (p < n) {
            val cp = Character.codePointAt(segment, p)
            if (cp == 0x200D) {
                if (p == valStart) break
                p += Character.charCount(cp)
                continue
            }
            if (isNonAsciiSymbolOrEmoji(cp) || keycapClusterEnd(segment, p) > p) {
                val afterCluster = skipEmojiCluster(segment, p)
                if (startsRecognizedKeyValue(segment, afterCluster)) {
                    if (p == valStart) {
                        p = afterCluster
                    }
                    break
                }
                p = afterCluster
                continue
            }
            if (isBareCredentialStopChar(cp)) break
            p += Character.charCount(cp)
        }
        return p
    }

    private fun isNonAsciiSymbolOrEmoji(cp: Int): Boolean {
        if (cp <= 0x7F) return false
        return when (Character.getType(cp).toByte()) {
            Character.OTHER_SYMBOL,
            Character.MODIFIER_SYMBOL,
            -> true
            else -> false
        }
    }

    private fun keycapClusterEnd(segment: String, start: Int): Int {
        val n = segment.length
        if (start >= n) return -1
        val base = segment[start]
        if (base !in '0'..'9' && base != '*' && base != '#') return -1
        var next = start + 1
        if (next < n && segment[next] == '\uFE0F') {
            next++
        }
        if (next < n && segment[next] == '\u20E3') {
            return next + 1
        }
        return -1
    }

    private fun skipEmojiCluster(segment: String, start: Int): Int {
        val n = segment.length
        var p = start
        while (p < n) {
            val keycapEnd = keycapClusterEnd(segment, p)
            if (keycapEnd > p) {
                p = keycapEnd
                continue
            }
            val cp = Character.codePointAt(segment, p)
            val type = Character.getType(cp).toByte()
            val isClusterChar = cp == 0x200D ||
                cp == 0x20E3 ||
                cp in 0xFE00..0xFE0F ||
                cp in 0xE0100..0xE01EF ||
                type == Character.OTHER_SYMBOL ||
                type == Character.MODIFIER_SYMBOL ||
                type == Character.ENCLOSING_MARK
            if (!isClusterChar) break
            p += Character.charCount(cp)
        }
        return p
    }

    private fun startsRecognizedKeyValue(segment: String, pos: Int): Boolean {
        val n = segment.length
        if (pos >= n) return false
        val ch = segment[pos]
        if (canStartCredentialKey(ch)) {
            val keyEnd = matchCredentialKeyEnd(segment, pos)
            if (keyEnd > pos) {
                val sepIdx = skipHorizontalSpacing(segment, keyEnd)
                if (sepIdx < n && (segment[sepIdx] == ':' || segment[sepIdx] == '=' || segment[sepIdx] == '：')) {
                    if (skipHorizontalSpacing(segment, sepIdx + 1) < n) return true
                }
            }
        }
        if (canStartOrderOrContactKey(ch)) {
            val keyEnd = matchOrderOrContactKeyEnd(segment, pos)
            if (keyEnd > pos) {
                val sepIdx = skipHorizontalSpacing(segment, keyEnd)
                if (sepIdx < n && (segment[sepIdx] == ':' || segment[sepIdx] == '=' || segment[sepIdx] == '：' || segment[sepIdx] == '#')) {
                    if (skipHorizontalSpacing(segment, sepIdx + 1) < n) return true
                }
            }
        }
        return false
    }

    private fun isHorizontalSpacing(cp: Int): Boolean {
        if (cp == '\t'.code) return true
        return when (Character.getType(cp).toByte()) {
            Character.SPACE_SEPARATOR,
            Character.FORMAT,
            -> true
            else -> false
        }
    }

    private fun isAllowedLeadingBoundaryAt(
        segment: String,
        index: Int,
        allowHanBeforeHanKey: Boolean = false,
    ): Boolean {
        val keyStartsWithHan = allowHanBeforeHanKey &&
            index < segment.length &&
            (segment[index] == '密' || segment[index] == '秘')
        var pos = index
        while (pos > 0) {
            val cp = Character.codePointBefore(segment, pos)
            if (cp == 0xFE0F || Character.getType(cp).toByte() == Character.ENCLOSING_MARK) {
                return true
            }
            if (Character.getType(cp).toByte() == Character.NON_SPACING_MARK ||
                cp in 0xFE00..0xFE0E ||
                cp in 0xE0100..0xE01EF
            ) {
                pos -= Character.charCount(cp)
                continue
            }
            if (keyStartsWithHan && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                return true
            }
            return isAllowedLeadingBoundary(cp)
        }
        return true
    }

    private fun isAllowedLeadingBoundary(cp: Int): Boolean {
        if (cp <= 0x7F) {
            return when (cp) {
                ','.code, ';'.code, '!'.code, '?'.code, '|'.code,
                '"'.code, '\''.code, '`'.code, '<'.code, '>'.code,
                '*'.code, '~'.code,
                -> true
                else -> when (Character.getType(cp).toByte()) {
                    Character.SPACE_SEPARATOR,
                    Character.CONTROL,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    -> true
                    else -> false
                }
            }
        }
        return when (Character.getType(cp).toByte()) {
            Character.SPACE_SEPARATOR,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
            Character.CONTROL,
            Character.FORMAT,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.MATH_SYMBOL,
            Character.OTHER_SYMBOL,
            Character.MODIFIER_SYMBOL,
            Character.CURRENCY_SYMBOL,
            -> true
            else -> Character.isWhitespace(cp)
        }
    }

    private fun isBareCredentialStopChar(cp: Int): Boolean {
        if (cp <= 0x7F) {
            return when (cp) {
                '"'.code, '\''.code, '`'.code, '<'.code, '>'.code,
                ','.code, ';'.code, '|'.code,
                -> true
                else -> when (Character.getType(cp).toByte()) {
                    Character.SPACE_SEPARATOR,
                    Character.CONTROL,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    -> true
                    else -> false
                }
            }
        }
        return when (Character.getType(cp).toByte()) {
            Character.SPACE_SEPARATOR,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
            Character.CONTROL,
            Character.FORMAT,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.MATH_SYMBOL,
            Character.CURRENCY_SYMBOL,
            -> true
            else -> Character.isWhitespace(cp)
        }
    }

    private inline fun transformOutsideProtectedSpans(
        input: String,
        transform: (String) -> Pair<String, Int>,
    ): Pair<String, Int> {
        val protectedRanges = findProtectedSpanRanges(input)
        if (protectedRanges.isEmpty()) {
            return transform(input)
        }
        val sb = StringBuilder(input.length)
        var totalCount = 0
        var cursor = 0
        for (range in protectedRanges) {
            if (range.first > cursor) {
                val (transformed, count) = transform(input.substring(cursor, range.first))
                sb.append(transformed)
                totalCount += count
            }
            sb.append(input, range.first, range.last + 1)
            cursor = range.last + 1
        }
        if (cursor < input.length) {
            val (transformed, count) = transform(input.substring(cursor))
            sb.append(transformed)
            totalCount += count
        }
        return sb.toString() to totalCount
    }

    /**
     * Single-pass O(N) structural scanner for protected Markdown image/link spans
     * (`![alt](url)`, `[label](url)`) and bare `http(s)://...` URLs.
     *
     * Bounds link/image labels to a single line without unescaped nested `[`, enforces
     * same-line `<...>` or whitespace-free balanced-paren destinations (with optional
     * quoted title), and tracks balanced parentheses in bare URLs so malformed inputs
     * never swallow secrets or degrade into quadratic backtracking.
     */
    private fun findProtectedSpanRanges(input: String): List<IntRange> {
        val n = input.length
        if (n < 4) return emptyList()

        val ranges = ArrayList<IntRange>()
        var parenMatch: IntArray? = null
        var parenWsDepth1: IntArray? = null

        fun ensureParenTables() {
            if (parenMatch != null) return
            val matches = IntArray(n) { -1 }
            val wsDepth1 = IntArray(n) { -1 }
            var stack = IntArray(64)
            var top = 0
            var k = 0
            while (k < n) {
                val c = input[k]
                if (c == '\\' && k + 1 < n) {
                    val next = input[k + 1]
                    if (next != '\r' && next != '\n' && next != ' ' && next != '\t') {
                        k += 2
                        continue
                    }
                }
                when (c) {
                    ' ', '\t' -> {
                        if (top > 0) {
                            wsDepth1[stack[top - 1]] = k
                        }
                        top = 0
                    }
                    '\r', '\n' -> top = 0
                    '(' -> {
                        if (top == stack.size) {
                            stack = stack.copyOf(stack.size * 2)
                        }
                        stack[top++] = k
                    }
                    ')' -> {
                        if (top > 0) {
                            val openIdx = stack[--top]
                            matches[openIdx] = k
                        }
                    }
                }
                k++
            }
            parenMatch = matches
            parenWsDepth1 = wsDepth1
        }

        fun findCloseBracketInLabel(fromIndex: Int): Int {
            var p = fromIndex
            while (p < n) {
                val ch = input[p]
                if (ch == '\\' && p + 1 < n) {
                    val next = input[p + 1]
                    if (next != '\r' && next != '\n') {
                        p += 2
                        continue
                    }
                }
                if (ch == '\r' || ch == '\n' || ch == '[') {
                    return -1
                }
                if (ch == ']') {
                    return p
                }
                p++
            }
            return -1
        }

        fun skipHorizontalWhitespace(fromIndex: Int): Int {
            var q = fromIndex
            while (q < n && (input[q] == ' ' || input[q] == '\t')) {
                q++
            }
            return q
        }

        fun scanQuotedTitle(quoteStart: Int): Int {
            val quoteChar = input[quoteStart]
            var q = quoteStart + 1
            while (q < n) {
                val ch = input[q]
                if (ch == '\r' || ch == '\n') return -1
                if (ch == '\\' && q + 1 < n) {
                    val next = input[q + 1]
                    if (next != '\r' && next != '\n') {
                        q += 2
                        continue
                    }
                }
                if (ch == quoteChar) {
                    return q + 1
                }
                q++
            }
            return -1
        }

        fun tryMatchOptionalTitleAndCloseParen(fromIndex: Int): Int {
            var q = skipHorizontalWhitespace(fromIndex)
            if (q < n && (input[q] == '"' || input[q] == '\'')) {
                val afterTitle = scanQuotedTitle(q)
                if (afterTitle < 0) return -1
                q = skipHorizontalWhitespace(afterTitle)
            }
            return if (q < n && input[q] == ')') q else -1
        }

        fun scanAngleDestinationCloseParen(angleStart: Int): Int {
            var q = angleStart + 1
            while (q < n) {
                val ch = input[q]
                if (ch == '\\' && q + 1 < n) {
                    val next = input[q + 1]
                    if (next != '\r' && next != '\n') {
                        q += 2
                        continue
                    }
                }
                if (ch == '\r' || ch == '\n' || ch == '<') {
                    return -1
                }
                if (ch == '>') {
                    if (q == angleStart + 1) return -1
                    return tryMatchOptionalTitleAndCloseParen(q + 1)
                }
                q++
            }
            return -1
        }

        fun findDestinationCloseParen(parenOpen: Int): Int {
            val firstNonWs = skipHorizontalWhitespace(parenOpen + 1)
            if (firstNonWs < n && input[firstNonWs] == '<') {
                return scanAngleDestinationCloseParen(firstNonWs)
            }
            ensureParenTables()
            val directClose = parenMatch!![parenOpen]
            if (directClose > parenOpen + 1) {
                return directClose
            }
            val wsPos = parenWsDepth1!![parenOpen]
            if (wsPos > parenOpen + 1) {
                return tryMatchOptionalTitleAndCloseParen(wsPos)
            }
            return -1
        }

        fun tryMatchMarkdownSpan(labelStart: Int, allowEmptyLabel: Boolean): Int {
            val closeBracket = findCloseBracketInLabel(labelStart)
            if (closeBracket < 0) return -1
            if (!allowEmptyLabel && closeBracket == labelStart) return -1
            if (closeBracket + 1 >= n || input[closeBracket + 1] != '(') return -1
            val parenOpen = closeBracket + 1
            val closeParen = findDestinationCloseParen(parenOpen)
            return if (closeParen > parenOpen + 1) closeParen else -1
        }

        var i = 0
        while (i < n) {
            val ch = input[i]
            if (ch == '!' && i + 1 < n && input[i + 1] == '[') {
                val closeParen = tryMatchMarkdownSpan(labelStart = i + 2, allowEmptyLabel = true)
                if (closeParen >= 0) {
                    ranges += i..closeParen
                    i = closeParen + 1
                    continue
                }
                i += 2
                continue
            } else if (ch == '[') {
                val closeBracket = findCloseBracketInLabel(i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < n && input[closeBracket + 1] == '(') {
                    val parenOpen = closeBracket + 1
                    val closeParen = findDestinationCloseParen(parenOpen)
                    if (closeParen > parenOpen + 1) {
                        val destPreview = input.substring(parenOpen + 1, closeParen)
                            .trimStart()
                            .removePrefix("<")
                            .trimStart()
                        if (!destPreview.startsWith("mailto:", ignoreCase = true)) {
                            ranges += i..closeParen
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            } else if (ch == 'h' || ch == 'H') {
                val prefixLen = when {
                    input.startsWith("https://", startIndex = i, ignoreCase = true) -> 8
                    input.startsWith("http://", startIndex = i, ignoreCase = true) -> 7
                    else -> 0
                }
                if (prefixLen > 0) {
                    var parenDepth = 0
                    var end = i + prefixLen
                    while (end < n) {
                        val c = input[end]
                        if (c.isWhitespace() || c.isISOControl() || c.code < 0x20 || c.code == 0x7F || c.code >= 0x80 || c == '<' || c == '>' || c == ']' || c == '|' || c == '"' || c == '`') break
                        if (c == '(') {
                            parenDepth++
                        } else if (c == ')') {
                            if (parenDepth == 0) break
                            parenDepth--
                        }
                        end++
                    }
                    if (end > i + prefixLen) {
                        ranges += i until end
                        i = end
                        continue
                    }
                }
            }
            i++
        }
        return ranges
    }

    private fun isAlreadyMaskedValue(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isEmpty() ||
            trimmed.contains("***") ||
            trimmed.contains("[已隐藏]") ||
            trimmed.equals("已隐藏", ignoreCase = true) ||
            trimmed.all { it == '*' }
    }

    private fun maskMiddleIdentifier(value: String): String {
        val clean = value.trim()
        val slashIdx = clean.indexOf('/')
        if (slashIdx > 0 && (
                clean.startsWith("t.me/", ignoreCase = true) ||
                    clean.startsWith("telegram.me/", ignoreCase = true)
                )
        ) {
            val hostPrefix = clean.substring(0, slashIdx + 1)
            val handle = clean.substring(slashIdx + 1)
            return hostPrefix + maskPlainIdentifier(handle)
        }
        return maskPlainIdentifier(clean)
    }

    private fun maskPlainIdentifier(clean: String): String {
        if (clean.length <= 4) return "****"
        val prefixLen = 2
        val suffixLen = if (clean.length >= 8) 2 else 1
        return clean.take(prefixLen) + "****" + clean.takeLast(suffixLen)
    }
}

object TradeDeliveryChecklistEngine {

    fun inferDeliveryMethod(
        title: String,
        markdown: String,
    ): FleetDeliveryMethod {
        val combined = "$title\n$markdown".lowercase()
        val hasOriginal = combined.contains("原油") || combined.contains("原始邮箱") || combined.contains("带邮")
        val hasChangeEmail = combined.contains("改邮")
        val hasPush = combined.contains("push")
        return when {
            hasOriginal -> FleetDeliveryMethod.ORIGINAL_EMAIL
            hasPush && hasChangeEmail -> FleetDeliveryMethod.EITHER
            hasChangeEmail -> FleetDeliveryMethod.CHANGE_EMAIL
            hasPush -> FleetDeliveryMethod.PUSH
            else -> FleetDeliveryMethod.EITHER
        }
    }

    fun checklistFor(method: FleetDeliveryMethod): List<TradeChecklistStep> {
        val commonPrefix = listOf(
            TradeChecklistStep(
                id = "common_buyer_verify",
                title = "核对买家 ID 与款项到账",
                description = "确认买家论坛 UID、交易方式（先款/中介）与实际到账金额，并留存收款凭证。",
                isCritical = true,
            ),
            TradeChecklistStep(
                id = "common_privacy_scrub",
                title = "脱敏主楼 IP / 密钥 / 订单号",
                description = "结帖前检查主楼跑分 IP、测试密码、账单截图与个人联系方式是否已打码隐藏。",
                isCritical = true,
            ),
        )

        val methodSteps = when (method) {
            FleetDeliveryMethod.PUSH -> listOf(
                TradeChecklistStep(
                    id = "push_fee_check",
                    title = "确认商家 Push 权限与手续费",
                    description = "核实当前商家支持站内 Push（及是否有冷却期/手续费承担方）。",
                    isCritical = true,
                ),
                TradeChecklistStep(
                    id = "push_target_uid",
                    title = "核对买家接收邮箱 / 账户 UID",
                    description = "向买家二次确认商家后台接收账号与邮箱拼写无误，避免 Push 到错误账户。",
                    isCritical = true,
                ),
                TradeChecklistStep(
                    id = "push_execute_receipt",
                    title = "发起站内 Push 并留存回执",
                    description = "完成 Push 后截图过户记录，提醒买家登录后台核验到期日、续费价格及流量。",
                ),
            )

            FleetDeliveryMethod.CHANGE_EMAIL -> listOf(
                TradeChecklistStep(
                    id = "change_email_bind",
                    title = "更换账户邮箱为买家指定邮箱",
                    description = "在商家控制台提交改邮并完成邮箱验证，确认原邮箱已不再接收账单通知。",
                    isCritical = true,
                ),
                TradeChecklistStep(
                    id = "change_email_cleanup",
                    title = "重置密码、清理公钥与探针 Agent",
                    description = "退出所有活跃会话，移除旧 SSH 公钥（authorized_keys）、个人探针及备份密钥。",
                    isCritical = true,
                ),
                TradeChecklistStep(
                    id = "change_email_2fa",
                    title = "交接 2FA 状态并提醒买家改密",
                    description = "确认已关闭原主 2FA（或交接重置），并提示买家登录后立即开启新的 2FA。",
                ),
            )

            FleetDeliveryMethod.ORIGINAL_EMAIL -> listOf(
                TradeChecklistStep(
                    id = "orig_unbind_recovery",
                    title = "解绑原油手机号、备用邮箱与 OAuth",
                    description = "清理原始邮箱绑定的个人手机、备用安全邮箱及第三方登录授权。",
                    isCritical = true,
                ),
                TradeChecklistStep(
                    id = "orig_cleanup_inbox",
                    title = "清理个人隐私邮件（保留商家账单）",
                    description = "删除与本机器无关的个人往来邮件，保留商家注册与续费账单邮件。",
                ),
                TradeChecklistStep(
                    id = "orig_handover_secrets",
                    title = "同步交接原油密码、面板密码与恢复码",
                    description = "关闭或移交 2FA 恢复码，提示买家收货后第一时间更改原油及 VPS 面板密码。",
                    isCritical = true,
                ),
            )

            FleetDeliveryMethod.EITHER -> listOf(
                TradeChecklistStep(
                    id = "either_confirm_path",
                    title = "与买家确认最终过户方式（Push 或改邮）",
                    description = "明确是否需要 Push 费以及由哪方承担，避免交割途中产生分歧。",
                    isCritical = true,
                ),
                TradeChecklistStep(
                    id = "either_clean_credentials",
                    title = "清理个人 SSH 公钥、探针与面板会话",
                    description = "无论 Push 还是改邮，交割前建议重装系统或清理 authorized_keys 与监控端。",
                ),
                TradeChecklistStep(
                    id = "either_buyer_verify",
                    title = "买家验收机器配置、IP 状态与到期时间",
                    description = "交付后请买家核验实例 IP、配置与续费账单周期，双方确认结帖。",
                ),
            )
        }

        val commonSuffix = listOf(
            TradeChecklistStep(
                id = "common_mark_sold",
                title = "标题追加 [已出] 完成结帖",
                description = "更新原帖标题为 [已出] 前缀，减少后续无效私信打扰。",
            ),
        )

        return commonPrefix + methodSteps + commonSuffix
    }

    fun buildHandoverMessage(
        method: FleetDeliveryMethod,
        topicTitle: String,
    ): String {
        val cleanTitle = TradeTitleFormatter.removeSoldPrefix(topicTitle).ifBlank { "交易机器" }
        return when (method) {
            FleetDeliveryMethod.PUSH ->
                "【交割确认 · Push 过户】《$cleanTitle》已完成站内 Push 转移，请在商家后台核验实例配置、到期时间与流量配额，确认无误后帖子已标记 [已出]。"
            FleetDeliveryMethod.CHANGE_EMAIL ->
                "【交割确认 · 改邮出】《$cleanTitle》已更换为您指定的账户邮箱，并清理了原主会话与公钥；请尽快登录修改密码并开启 2FA。"
            FleetDeliveryMethod.ORIGINAL_EMAIL ->
                "【交割确认 · 带原油出】《$cleanTitle》面板账号及原始邮箱均已整理交接并解绑原主手机/备用邮箱，请收货后第一时间更改原油与面板密码及安全密保。"
            FleetDeliveryMethod.EITHER ->
                "【交割确认】《$cleanTitle》已进入交割流程（${method.label}），请核验机器配置、IP 与续费周期；确认无误后将完成 [已出] 结帖。"
        }
    }
}
