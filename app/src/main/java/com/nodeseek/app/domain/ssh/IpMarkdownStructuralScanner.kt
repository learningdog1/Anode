package com.nodeseek.app.domain.ssh

/**
 * Shared, single-pass, O(N) line-scoped structural scanner for IP literals, already-masked IP
 * tokens, ANSI SGR escape sequences, and Markdown emphasis (`*` / `**` / `***`) spans.
 *
 * Eliminates ad-hoc backward `*` re-scanning by:
 * 1. Skipping ANSI SGR sequences (`\u001B[...m`) transparently while preserving them verbatim.
 * 2. Resetting line-scoped Markdown emphasis state on `'\n'`.
 * 3. Lexing already-masked IP tokens (`MaskedEmbeddedIpv6`, `MaskedIpv6`, `MaskedIpv4`) FIRST as
 *    atomic tokens — splitting any merged trailing Markdown `*` closers cleanly.
 * 4. Maintaining an incremental O(1) dynamic `EmphasisStack` (`totalOpen`) for pure `StarRun`s.
 * 5. Dispatching raw IPv4 and IPv6/IPv4-embedded IPv6 candidates with the exact live `totalOpen`.
 */
internal object IpMarkdownStructuralScanner {

    internal class TokenResult {
        var endIndex: Int = -1
        var replacement: String? = null
        var nextPrevPlainChar: Char = '\u0000'
        var skipRunOnMiss: Boolean = false

        fun reset() {
            endIndex = -1
            replacement = null
            nextPrevPlainChar = '\u0000'
            skipRunOnMiss = false
        }
    }

    internal fun interface MaskedIpTokenHandler {
        fun transform(text: String, start: Int, end: Int): String?
    }

    internal fun interface RawIpv4Handler {
        fun transform(text: String, start: Int, end: Int, out: TokenResult)
    }

    internal fun interface RawIpv6Handler {
        fun transform(text: String, start: Int, n: Int, totalOpen: Int, out: TokenResult)
    }

    fun scanAndTransform(
        text: String,
        skipAnsiSgr: Boolean = true,
        onMaskedIpToken: MaskedIpTokenHandler? = null,
        onRawIpv4: RawIpv4Handler? = null,
        onRawIpv6: RawIpv6Handler? = null,
    ): String {
        val n = text.length
        if (n < 2 || !hasAnyIpDelimiter(text)) return text

        var out: StringBuilder? = null
        var flushCursor = 0
        // Immediately preceding visible char for IP left-boundary checks; '\u0000' after SGR/start.
        var prevPlainChar = '\u0000'
        // Immediately preceding non-SGR char on the current line for Markdown emphasis open/close.
        var prevNonSgrChar = '\u0000'

        var openStack = IntArray(32)
        var stackSize = 0
        var totalOpen = 0

        val tokenResult = if (onRawIpv4 != null || onRawIpv6 != null) TokenResult() else null

        var i = 0
        while (i < n) {
            val ch = text[i]

            // Priority 1: ANSI SGR escape sequence (\u001B[[0-9;:]*m) — transparent skip + verbatim keep
            if (skipAnsiSgr && ch == '\u001B' && i + 1 < n && text[i + 1] == '[') {
                val sgrEnd = findSgrEndIndex(text, i)
                if (sgrEnd >= 0) {
                    prevPlainChar = '\u0000'
                    i = sgrEnd + 1
                    continue
                }
            }

            // Priority 2: Newline resets line-scoped Markdown emphasis state
            if (ch == '\n') {
                stackSize = 0
                totalOpen = 0
                prevPlainChar = '\n'
                prevNonSgrChar = '\n'
                i++
                continue
            }

            val canStartIpv6 = (
                isHexChar(ch) ||
                    (ch == ':' && i + 1 < n && text[i + 1] == ':' && (i + 2 >= n || text[i + 2] != ':'))
                ) && !isIpv6LeftBoundaryBlocked(text, i, prevPlainChar)

            // Priority 3: MaskedEmbeddedIpv6 (atomic IPv4-embedded masked IPv6, e.g. `::ffff:203.*.*.*`)
            if (canStartIpv6) {
                val embeddedEnd = scanMaskedEmbeddedIpv6At(text, i, n, totalOpen)
                if (embeddedEnd > i) {
                    val replacement = onMaskedIpToken?.transform(text, i, embeddedEnd)
                    if (replacement != null) {
                        val sb = out ?: StringBuilder(n).also { out = it }
                        if (i > flushCursor) sb.append(text, flushCursor, i)
                        sb.append(replacement)
                        flushCursor = embeddedEnd
                        val tailChar = if (replacement.isNotEmpty()) replacement[replacement.length - 1] else text[embeddedEnd - 1]
                        prevPlainChar = tailChar
                        prevNonSgrChar = tailChar
                    } else {
                        val tailChar = text[embeddedEnd - 1]
                        prevPlainChar = tailChar
                        prevNonSgrChar = tailChar
                    }
                    i = embeddedEnd
                    continue
                }
            }

            // Priority 4: MaskedIpv6 (atomic masked pure IPv6, splitting merged trailing Markdown '*')
            val canStartMaskedIpv6 = canStartIpv6 ||
                (ch == '*' && prevNonSgrChar != '*' && !isIpv6LeftBoundaryBlocked(text, i, prevPlainChar) && text.startsWith("****::", i))
            if (canStartMaskedIpv6) {
                val maskedV6End = scanMaskedIpv6At(text, i, n, totalOpen)
                if (maskedV6End > i) {
                    val replacement = onMaskedIpToken?.transform(text, i, maskedV6End)
                    if (replacement != null) {
                        val sb = out ?: StringBuilder(n).also { out = it }
                        if (i > flushCursor) sb.append(text, flushCursor, i)
                        sb.append(replacement)
                        flushCursor = maskedV6End
                        val tailChar = if (replacement.isNotEmpty()) replacement[replacement.length - 1] else text[maskedV6End - 1]
                        prevPlainChar = tailChar
                        prevNonSgrChar = tailChar
                    } else {
                        val tailChar = text[maskedV6End - 1]
                        prevPlainChar = tailChar
                        prevNonSgrChar = tailChar
                    }
                    i = maskedV6End
                    continue
                }
            }

            // Priority 5: MaskedIpv4 (atomic /8, /16, /24, interior `*`, or `***.***.***.***`)
            if ((
                    ch in '0'..'9' ||
                        (
                            ch == '*' &&
                                prevNonSgrChar != '*' &&
                                (
                                    (i + 1 < n && text[i + 1] == '.') ||
                                        text.startsWith("***.", i)
                                    )
                            )
                    ) && !isIpv4BoundaryBlocked(prevPlainChar)
            ) {
                val maskedV4End = scanMaskedIpv4At(text, i, n, totalOpen)
                if (maskedV4End > i) {
                    val replacement = onMaskedIpToken?.transform(text, i, maskedV4End)
                    if (replacement != null) {
                        val sb = out ?: StringBuilder(n).also { out = it }
                        if (i > flushCursor) sb.append(text, flushCursor, i)
                        sb.append(replacement)
                        flushCursor = maskedV4End
                        val tailChar = if (replacement.isNotEmpty()) replacement[replacement.length - 1] else text[maskedV4End - 1]
                        prevPlainChar = tailChar
                        prevNonSgrChar = tailChar
                    } else {
                        val tailChar = text[maskedV4End - 1]
                        prevPlainChar = tailChar
                        prevNonSgrChar = tailChar
                    }
                    i = maskedV4End
                    continue
                }
            }

            // Priority 6: StarRun (pure Markdown emphasis open/close stack — zero IP-prefix heuristics)
            if (ch == '*') {
                var starEnd = i + 1
                while (starEnd < n && text[starEnd] == '*') {
                    starEnd++
                }
                val len = starEnd - i
                val prev = prevNonSgrChar
                val nextIdx = if (skipAnsiSgr) skipSgrSequences(text, starEnd, n) else starEnd
                val next = if (nextIdx < n) text[nextIdx] else '\u0000'
                val isDoubleColonStart = next == ':' && nextIdx + 1 < n && text[nextIdx + 1] == ':'

                val afterNext = if ((prev == '.' || prev == ':' || prev == '*') && (next == '.' || next == ':')) {
                    val afterNextIdx = if (skipAnsiSgr) skipSgrSequences(text, nextIdx + 1, n) else nextIdx + 1
                    if (afterNextIdx < n) text[afterNextIdx] else '\u0000'
                } else {
                    '\u0000'
                }
                val isMaskedSegmentStarCloserBlocked = (prev == '.' || prev == ':' || prev == '*') && (next == '.' || next == ':') && (
                    (prev == ':' && next == ':' && (len == 1 || len >= 4)) ||
                        (prev == '.' && next == '.' && len == 1) ||
                        afterNext.isLetterOrDigit() ||
                        afterNext == '*' ||
                        (next == ':' && afterNext == ':')
                    )

                val canClose = stackSize > 0 &&
                    prev != '\n' &&
                    prev != '\u0000' &&
                    !prev.isWhitespace() &&
                    !(prev in '0'..'9' && next in '0'..'9') &&
                    !((prev == '.' || prev == ':') && (isItalicOpeningAfterStar(next) || (len >= 2 && next in '0'..'9'))) &&
                    !isMaskedSegmentStarCloserBlocked

                if (canClose) {
                    var rem = len
                    while (stackSize > 0 && rem > 0) {
                        val top = openStack[stackSize - 1]
                        if (rem >= top) {
                            rem -= top
                            totalOpen -= top
                            stackSize--
                        } else {
                            var matchIdx = -1
                            for (k in stackSize - 1 downTo 0) {
                                if (openStack[k] == rem) {
                                    matchIdx = k
                                    break
                                }
                            }
                            if (matchIdx >= 0) {
                                for (k in matchIdx until stackSize - 1) {
                                    openStack[k] = openStack[k + 1]
                                }
                                stackSize--
                                totalOpen -= rem
                            } else {
                                openStack[stackSize - 1] = top - rem
                                totalOpen -= rem
                            }
                            rem = 0
                        }
                    }
                } else {
                    val canOpen = len in 1..3 &&
                        next != '\u0000' &&
                        next != '\n' &&
                        !next.isWhitespace() &&
                        (!isMarkdownClosingOrSepPunct(next) || isDoubleColonStart) &&
                        !(prev in '0'..'9' && next in '0'..'9') &&
                        !((prev == '.' || prev == ':') && len == 1 && !isItalicOpeningAfterStar(next))

                    if (canOpen) {
                        if (stackSize == openStack.size) {
                            openStack = openStack.copyOf(openStack.size * 2)
                        }
                        openStack[stackSize++] = len
                        totalOpen += len
                    }
                }

                prevPlainChar = '*'
                prevNonSgrChar = '*'
                i = starEnd
                continue
            }

            // Priority 7a: Raw IPv4 candidate
            if (onRawIpv4 != null && ch in '0'..'9' && !isIpv4BoundaryBlocked(prevPlainChar)) {
                val ipv4End = tryScanIpv4End(text, i, n)
                if (ipv4End > i && isRawIpv4RightBoundaryAllowed(text, ipv4End, totalOpen, n, skipAnsiSgr)) {
                    val result = tokenResult!!
                    result.reset()
                    onRawIpv4.transform(text, i, ipv4End, result)
                    val replacement = result.replacement
                    if (replacement != null) {
                        val sb = out ?: StringBuilder(n).also { out = it }
                        if (i > flushCursor) sb.append(text, flushCursor, i)
                        sb.append(replacement)
                        flushCursor = ipv4End
                    }
                    val tailChar = if (result.nextPrevPlainChar != '\u0000') result.nextPrevPlainChar else text[ipv4End - 1]
                    prevPlainChar = tailChar
                    prevNonSgrChar = tailChar
                    i = ipv4End
                    continue
                }
            }

            // Priority 7b: Raw IPv6 / IPv4-embedded IPv6 candidate
            if (onRawIpv6 != null && canStartIpv6) {
                val result = tokenResult!!
                result.reset()
                onRawIpv6.transform(text, i, n, totalOpen, result)
                val v6End = result.endIndex
                if (v6End > i) {
                    val replacement = result.replacement
                    if (replacement != null) {
                        val sb = out ?: StringBuilder(n).also { out = it }
                        if (i > flushCursor) sb.append(text, flushCursor, i)
                        sb.append(replacement)
                        flushCursor = v6End
                    }
                    val tailChar = if (result.nextPrevPlainChar != '\u0000') result.nextPrevPlainChar else text[v6End - 1]
                    prevPlainChar = tailChar
                    prevNonSgrChar = tailChar
                    i = v6End
                    continue
                }
                if (result.skipRunOnMiss) {
                    val runEnd = scanHexColonRunSkipEnd(text, i, n)
                    if (runEnd - i > 1) {
                        val tailChar = text[runEnd - 1]
                        prevPlainChar = tailChar
                        prevNonSgrChar = tailChar
                        i = runEnd
                        continue
                    }
                }
            }

            prevPlainChar = ch
            prevNonSgrChar = ch
            i++
        }

        val sb = out ?: return text
        if (flushCursor < n) {
            sb.append(text, flushCursor, n)
        }
        return sb.toString()
    }

    // ── Shared Atomic Masked-Token Scanners ──────────────────────────────────────────────────────

    private fun scanMaskedEmbeddedIpv6At(
        text: String,
        start: Int,
        n: Int,
        totalOpen: Int,
    ): Int {
        val prefixEnd = scanIpv6CandidateEnd(text, start, n, maxLen = 41)
        if (prefixEnd - start < 3 || prefixEnd >= n || text[prefixEnd] != '.') return -1
        val lastColon = findLastColon(text, start, prefixEnd)
        if (lastColon < start || lastColon + 1 >= prefixEnd) return -1
        if (!isValidIpv6PrefixForEmbeddedIpv4(text, start, lastColon)) return -1
        return scanMaskedIpv4At(text, lastColon + 1, n, totalOpen)
    }

    private fun scanMaskedIpv4At(
        text: String,
        start: Int,
        n: Int,
        totalOpen: Int,
    ): Int {
        var pos = start
        var starSegments = 0
        var allTripleStar = true

        for (seg in 0 until 4) {
            if (seg > 0) {
                if (pos >= n || text[pos] != '.') return -1
                pos++
            }
            if (pos >= n) return -1

            if (text[pos] == '*') {
                var starEnd = pos + 1
                while (starEnd < n && text[starEnd] == '*') {
                    starEnd++
                }
                val starLen = starEnd - pos
                starSegments++

                if (seg < 3) {
                    if (starLen == 3 && (seg == 0 || allTripleStar)) {
                        // Allow `***.***.***.***` only when every segment so far is `***`
                        pos = starEnd
                    } else if (starLen == 1) {
                        allTripleStar = false
                        pos = starEnd
                    } else {
                        return -1
                    }
                } else {
                    // Terminal segment S3
                    if (allTripleStar && starSegments == 4) {
                        if (starLen < 3) return -1
                        val tokenEnd = pos + 3
                        val afterAllStars = peekNextNonSgrChar(text, starEnd, n)
                        if (!isMaskedIpv4RightBoundaryAllowed(text, starEnd, starLen - 2, afterAllStars, totalOpen, n)) {
                            return -1
                        }
                        return tokenEnd
                    }
                    // Standard masked IPv4 consumes exact 1 '*' for S3, leaving (starLen - 1) as StarRun
                    val tokenEnd = pos + 1
                    val afterAllStars = peekNextNonSgrChar(text, starEnd, n)
                    if (!isMaskedIpv4RightBoundaryAllowed(text, starEnd, starLen, afterAllStars, totalOpen, n)) {
                        return -1
                    }
                    return tokenEnd
                }
            } else if (text[pos] in '0'..'9') {
                allTripleStar = false
                val octStart = pos
                var value = 0
                while (pos < n && pos - octStart < 3 && text[pos] in '0'..'9') {
                    value = value * 10 + (text[pos] - '0')
                    pos++
                }
                val octLen = pos - octStart
                if (octLen == 0 || (pos < n && text[pos] in '0'..'9')) return -1
                if (octLen > 1 && text[octStart] == '0') return -1
                if (value !in 0..255) return -1
            } else {
                return -1
            }
        }

        if (starSegments == 0) return -1
        // S3 was numeric (e.g. `192.168.*.1`)
        return if (isRawIpv4RightBoundaryAllowed(text, pos, totalOpen, n, skipAnsiSgr = true)) pos else -1
    }

    private fun isMaskedIpv4RightBoundaryAllowed(
        text: String,
        starEnd: Int,
        k: Int,
        afterAllStars: Char,
        totalOpen: Int,
        n: Int,
    ): Boolean {
        if (afterAllStars == '\u0000') return true
        // If an SGR immediately follows the token without another '*' before text, treat as unblocked
        if (text[starEnd] == '\u001B' && findSgrEndIndex(text, starEnd) >= 0 && afterAllStars != '*') {
            return true
        }
        if (afterAllStars == '.') {
            val dotIdx = skipSgrSequences(text, starEnd, n)
            val afterDotIdx = skipSgrSequences(text, dotIdx + 1, n)
            val afterDot = if (afterDotIdx < n) text[afterDotIdx] else '\u0000'
            if (afterDot in '0'..'9' || afterDot in 'a'..'z' || afterDot in 'A'..'Z' || afterDot == '.') {
                return false
            }
            if (afterDot == '*') {
                val afterStar2 = peekNextNonSgrChar(text, afterDotIdx + 1, n)
                if (afterStar2 == '.' || afterStar2 in '0'..'9') return false
                // For `203.0.*.*.*` (`k == 1`), `.*` is allowed when closing or italic opening
                return true
            }
            return true
        }
        if (isIpv4BoundaryBlocked(afterAllStars)) return false
        if (k == 1 && isItalicOpeningAfterStar(afterAllStars) && totalOpen == 0) {
            return false
        }
        return true
    }

    private enum class V6PartKind { NONE, HEX, WILDCARD_1, WILDCARD_4 }

    private fun scanMaskedIpv6At(
        text: String,
        start: Int,
        n: Int,
        totalOpen: Int,
    ): Int {
        var pos = start
        var groupCount = 0
        var hexGroups = 0
        var wildcardGroups = 0
        var seenDoubleColon = false
        var colonCount = 0
        var lastColonWasDouble = false
        var lastTokenKind = V6PartKind.NONE
        var lastValidEnd = -1

        if (pos + 1 < n && text[pos] == ':' && text[pos + 1] == ':') {
            if (pos + 2 < n && text[pos + 2] == ':') return -1
            seenDoubleColon = true
            colonCount = 2
            lastColonWasDouble = true
            pos += 2
        }

        val limit = (start + 45).coerceAtMost(n)
        while (pos < limit) {
            val ch = text[pos]
            if (ch == '*') {
                // Already completed `:*:*` or `:****` — do not extend into trailing `:**`
                if (wildcardGroups >= 2 || lastTokenKind == V6PartKind.WILDCARD_4) {
                    return lastValidEnd
                }
                var starEnd = pos + 1
                while (starEnd < n && text[starEnd] == '*') {
                    starEnd++
                }
                val k = starEnd - pos

                // Interior wildcard check (`:*:` or `****::` or `:****:`)
                if (starEnd < n && text[starEnd] == ':' && (k == 1 || k == 4)) {
                    if (groupCount == 0) {
                        // Only `****::...` is valid at groupCount == 0
                        if (k != 4 || starEnd + 1 >= n || text[starEnd + 1] != ':') return -1
                        if (starEnd + 2 < n && text[starEnd + 2] == ':') return -1
                        groupCount = 1
                        wildcardGroups = 1
                        seenDoubleColon = true
                        colonCount += 2
                        lastColonWasDouble = true
                        lastTokenKind = V6PartKind.WILDCARD_4
                        pos = starEnd + 2
                        continue
                    }

                    // If this `k == 1` completes `prefix:*:*` and is followed by `:*` (e.g. `240e:390:*:*:**`),
                    // stop right after `:*:*` so the remaining `:**` is parsed as Markdown closer.
                    if (k == 1 && lastTokenKind == V6PartKind.WILDCARD_1 && !lastColonWasDouble && hexGroups >= 1) {
                        val afterColon = peekNextNonSgrChar(text, starEnd + 1, n)
                        if (afterColon == '*' || (!isHexChar(afterColon) && afterColon != ':')) {
                            return starEnd
                        }
                    }

                    // If `k == 4`, check if `[start, starEnd)` is already a valid terminal `::****` / `:****`
                    if (k == 4) {
                        val validAsTerminal = evaluateTerminalStarMaskedV6(
                            text = text,
                            starPos = pos,
                            starEnd = starEnd,
                            k = k,
                            n = n,
                            totalOpen = totalOpen,
                            groupCount = groupCount,
                            hexGroups = hexGroups,
                            wildcardGroups = wildcardGroups,
                            seenDoubleColon = seenDoubleColon,
                            lastColonWasDouble = lastColonWasDouble,
                            lastTokenKind = lastTokenKind,
                        )
                        if (validAsTerminal > 0) {
                            lastValidEnd = validAsTerminal
                        }
                        val nextAfterColon = if (starEnd + 1 < n) text[starEnd + 1] else '\u0000'
                        if (!isHexChar(nextAfterColon) && nextAfterColon != ':') {
                            return lastValidEnd
                        }
                    }

                    val nextColonIsDouble = starEnd + 1 < n && text[starEnd + 1] == ':'
                    if (nextColonIsDouble) {
                        if (seenDoubleColon || (starEnd + 2 < n && text[starEnd + 2] == ':')) {
                            return lastValidEnd
                        }
                    }

                    groupCount++
                    wildcardGroups++
                    lastTokenKind = if (k == 1) V6PartKind.WILDCARD_1 else V6PartKind.WILDCARD_4
                    if (nextColonIsDouble) {
                        seenDoubleColon = true
                        colonCount += 2
                        lastColonWasDouble = true
                        pos = starEnd + 2
                    } else {
                        colonCount += 1
                        lastColonWasDouble = false
                        pos = starEnd + 1
                    }
                    continue
                }

                // Terminal `*` run
                val terminalEnd = evaluateTerminalStarMaskedV6(
                    text = text,
                    starPos = pos,
                    starEnd = starEnd,
                    k = k,
                    n = n,
                    totalOpen = totalOpen,
                    groupCount = groupCount,
                    hexGroups = hexGroups,
                    wildcardGroups = wildcardGroups,
                    seenDoubleColon = seenDoubleColon,
                    lastColonWasDouble = lastColonWasDouble,
                    lastTokenKind = lastTokenKind,
                )
                return if (terminalEnd > 0) terminalEnd else lastValidEnd
            } else if (isHexChar(ch)) {
                if (wildcardGroups >= 2) {
                    return lastValidEnd
                }
                var hexEnd = pos + 1
                while (hexEnd < limit && hexEnd - pos < 4 && isHexChar(text[hexEnd])) {
                    hexEnd++
                }
                if (hexEnd < n && isHexChar(text[hexEnd])) {
                    return lastValidEnd
                }
                // Do not swallow the first octet of a trailing IPv4 / MaskedIpv4 after `:`.
                if (hexEnd < n && text[hexEnd] == '.' &&
                    (
                        (hexEnd + 1 < n && text[hexEnd + 1] in '0'..'9') ||
                            scanMaskedIpv4At(text, pos, n, totalOpen) > pos
                        )
                ) {
                    return lastValidEnd
                }
                groupCount++
                hexGroups++
                lastTokenKind = V6PartKind.HEX
                lastColonWasDouble = false

                if (wildcardGroups >= 1 && colonCount >= 2) {
                    val groupOk = (!seenDoubleColon && groupCount in 3..8) ||
                        (seenDoubleColon && groupCount in 2..7)
                    if (groupOk) {
                        val nextPlain = peekNextNonSgrCharForIpRightBoundary(text, hexEnd, n)
                        val allowed = !isIpv6BoundaryBlocked(nextPlain) ||
                            (
                                hexEnd < n &&
                                    text[hexEnd] == ':' &&
                                    peekNextNonSgrChar(text, hexEnd + 1, n) == '*'
                                )
                        if (allowed) {
                            lastValidEnd = hexEnd
                        }
                    }
                }

                if (hexEnd >= n || text[hexEnd] != ':') {
                    return lastValidEnd
                }
                if (hexEnd + 1 < n && text[hexEnd + 1] == ':') {
                    if (seenDoubleColon || (hexEnd + 2 < n && text[hexEnd + 2] == ':')) {
                        return lastValidEnd
                    }
                    seenDoubleColon = true
                    colonCount += 2
                    lastColonWasDouble = true
                    pos = hexEnd + 2
                } else {
                    colonCount += 1
                    lastColonWasDouble = false
                    pos = hexEnd + 1
                }
            } else {
                return lastValidEnd
            }
        }
        return lastValidEnd
    }

    private fun evaluateTerminalStarMaskedV6(
        text: String,
        starPos: Int,
        starEnd: Int,
        k: Int,
        n: Int,
        totalOpen: Int,
        groupCount: Int,
        hexGroups: Int,
        wildcardGroups: Int,
        seenDoubleColon: Boolean,
        lastColonWasDouble: Boolean,
        lastTokenKind: V6PartKind,
    ): Int {
        val afterStars = peekNextNonSgrChar(text, starEnd, n)
        if (afterStars != '\u0000' &&
            (afterStars.isLetterOrDigit() || afterStars == '_' || (k in 1..3 && isItalicOpeningAfterStar(afterStars)))
        ) {
            return -1
        }

        // Subcase 4a: `prefix::` + k stars
        if (lastColonWasDouble) {
            if (groupCount > 7) return -1
            if (hexGroups == 0 && k < 4) return -1
            return if (k >= 4) {
                starPos + 4
            } else if (totalOpen >= k) {
                -1
            } else {
                starPos + k
            }
        }

        // Subcase 4b: `prefix:*:` + k stars
        if (wildcardGroups >= 1 && lastTokenKind == V6PartKind.WILDCARD_1) {
            if (hexGroups < 1 || (!seenDoubleColon && groupCount > 7)) return -1
            return when {
                k in 1..3 -> starPos + 1
                k == 4 -> if (totalOpen >= 3) starPos + 1 else starPos + 4
                else -> starPos + 4
            }
        }

        // Subcase 4c: single-colon `prefix:****...`
        if (lastTokenKind == V6PartKind.HEX) {
            if (k < 4) return -1
            val validPrefix = (seenDoubleColon && groupCount <= 7) ||
                (!seenDoubleColon && (groupCount == 7 || (wildcardGroups >= 1 && groupCount in 3..7)))
            if (!validPrefix) return -1
            return starPos + 4
        }

        return -1
    }

    // ── Shared Boundary, SGR & IP Helpers ────────────────────────────────────────────────────────

    fun hasAnyIpDelimiter(text: String): Boolean {
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '.' || ch == ':') return true
        }
        return false
    }

    fun findSgrEndIndex(text: String, escBracketIndex: Int): Int {
        var i = escBracketIndex + 2
        val limit = (escBracketIndex + 66).coerceAtMost(text.length)
        while (i < limit) {
            val ch = text[i]
            if (ch == 'm') return i
            if (ch !in '0'..'9' && ch != ';' && ch != ':') return -1
            i++
        }
        return -1
    }

    fun skipSgrSequences(text: String, index: Int, length: Int): Int {
        var pos = index
        while (pos + 1 < length && text[pos] == '\u001B' && text[pos + 1] == '[') {
            val sgrEnd = findSgrEndIndex(text, pos)
            if (sgrEnd < 0) break
            pos = sgrEnd + 1
        }
        return pos
    }

    fun peekNextNonSgrChar(text: String, index: Int, length: Int): Char {
        val pos = skipSgrSequences(text, index, length)
        return if (pos < length) text[pos] else '\u0000'
    }

    /**
     * For IP right-boundary checks: if an SGR sequence immediately follows an IP token, treat it
     * as a neutral `'\u0000'` separator UNLESS the next non-SGR char is `'*'` (which may be a
     * closing Markdown emphasis run or colon-star boundary).
     */
    fun peekNextNonSgrCharForIpRightBoundary(text: String, index: Int, length: Int): Char {
        if (index >= length) return '\u0000'
        if (index + 1 < length && text[index] == '\u001B' && text[index + 1] == '[') {
            if (findSgrEndIndex(text, index) >= 0) {
                val afterSgr = peekNextNonSgrChar(text, index, length)
                return if (afterSgr == '*') '*' else '\u0000'
            }
        }
        return text[index]
    }

    fun isIpv4BoundaryBlocked(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '.'

    fun isRawIpv4RightBoundaryAllowed(
        text: String,
        ipv4End: Int,
        totalOpen: Int,
        n: Int,
        skipAnsiSgr: Boolean = true,
    ): Boolean {
        if (ipv4End >= n) return true
        val next = if (skipAnsiSgr) {
            peekNextNonSgrCharForIpRightBoundary(text, ipv4End, n)
        } else {
            text[ipv4End]
        }
        if (next != '.') {
            return !isIpv4BoundaryBlocked(next)
        }
        val afterDotIdx = if (skipAnsiSgr) skipSgrSequences(text, ipv4End + 1, n) else ipv4End + 1
        val afterDot = if (afterDotIdx < n) text[afterDotIdx] else '\u0000'
        if (afterDot in '0'..'9' || afterDot in 'a'..'z' || afterDot in 'A'..'Z' || afterDot == '.') {
            return false
        }
        if (afterDot == '*') {
            var afterStarsIdx = afterDotIdx + 1
            while (afterStarsIdx < n && text[afterStarsIdx] == '*') {
                afterStarsIdx++
            }
            val starRunLen = afterStarsIdx - afterDotIdx
            val afterStars = if (skipAnsiSgr) {
                peekNextNonSgrChar(text, afterStarsIdx, n)
            } else if (afterStarsIdx < n) {
                text[afterStarsIdx]
            } else {
                '\u0000'
            }
            if (afterStars == '.' || afterStars in '0'..'9') return false
            if (starRunLen == 1) {
                return totalOpen >= 1 || isItalicOpeningAfterStar(afterStars)
            }
            return true
        }
        return true
    }

    fun isIpv6BoundaryBlocked(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == ':'

    fun isIpv6LeftBoundaryBlocked(text: String, idx: Int, prevPlainChar: Char): Boolean {
        if (!isIpv6BoundaryBlocked(prevPlainChar)) return false
        return prevPlainChar != ':' || !isLabelColonBoundary(text, idx)
    }

    private fun isLabelClosingWrapper(ch: Char): Boolean =
        when (ch) {
            '*', '`', ']', ')', '"', '\'', '”', '】', '）' -> true
            else -> false
        }

    fun isLabelColonBoundary(text: String, idx: Int): Boolean {
        if (idx < 2 || text[idx - 1] != ':') return false
        var k = idx - 2
        // Skip optional ANSI SGR right before ':' (e.g. `\u001B[1mIPv6\u001B[0m:240e:...`)
        while (k >= 2 && text[k] == 'm') {
            var esc = k - 1
            while (esc >= 0 && k - esc <= 66 && (text[esc] in '0'..'9' || text[esc] == ';' || text[esc] == ':')) {
                esc--
            }
            if (esc >= 1 && text[esc] == '[' && text[esc - 1] == '\u001B') {
                k = esc - 2
            } else {
                break
            }
        }
        var skippedWrapper = false
        while (k >= 0 && isLabelClosingWrapper(text[k])) {
            skippedWrapper = true
            k--
        }
        if (k < 0 || text[k] == ':') return false
        var wordLen = 0
        var hasNonHex = false
        while (k >= 0) {
            val c = text[k]
            val isWordChar = c in '0'..'9' ||
                c in 'a'..'z' ||
                c in 'A'..'Z' ||
                c == '_' ||
                c == '-' ||
                Character.isLetter(c)
            if (!isWordChar) break
            wordLen++
            if (!isHexChar(c)) {
                hasNonHex = true
            }
            k--
        }
        return wordLen > 0 && (hasNonHex || skippedWrapper)
    }

    fun isItalicOpeningAfterStar(ch: Char): Boolean =
        ch.isLetter() ||
            when (ch) {
                '(', '[', '{', '"', '\'', '`', '“', '‘', '（', '【', '《', '_' -> true
                else -> false
            }

    private fun isMarkdownClosingOrSepPunct(ch: Char): Boolean =
        when (ch) {
            '.', ',', ';', ':', '!', '?', ')', ']', '}', '”', '’', '）', '】', '》', '>', '、', '。', '，', '；', '：', '！', '？' -> true
            else -> false
        }

    fun isHexChar(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    fun tryScanIpv4End(text: String, start: Int, n: Int): Int {
        var pos = start
        for (octet in 0 until 4) {
            if (octet > 0) {
                if (pos >= n || text[pos] != '.') return -1
                pos++
            }
            val octStart = pos
            while (pos < n && pos - octStart < 3 && text[pos] in '0'..'9') {
                pos++
            }
            if (pos == octStart) return -1
            if (pos < n && text[pos] in '0'..'9') return -1
        }
        return pos
    }

    fun isStrictIpv4Range(text: String, start: Int, end: Int): Boolean {
        var pos = start
        for (octet in 0 until 4) {
            if (octet > 0) {
                if (pos >= end || text[pos] != '.') return false
                pos++
            }
            val octStart = pos
            var value = 0
            while (pos < end && text[pos] != '.') {
                val c = text[pos]
                if (c !in '0'..'9') return false
                value = value * 10 + (c - '0')
                pos++
            }
            val len = pos - octStart
            if (len !in 1..3) return false
            if (len > 1 && text[octStart] == '0') return false
            if (value !in 0..255) return false
        }
        return pos == end
    }

    fun scanIpv6CandidateEnd(text: String, start: Int, n: Int, maxLen: Int = 41): Int {
        var pos = start
        val limit = (start + maxLen).coerceAtMost(n)
        while (pos < limit) {
            val ch = text[pos]
            if (!isHexChar(ch) && ch != ':') break
            pos++
        }
        return pos
    }

    /**
     * Scans the entire contiguous `[isHexChar || ':']` run starting at [start] (with no fixed
     * length cap, preserving O(N) total time across arbitrarily long hex/colon blobs) and returns a
     * safe skip target index. If the full run ends immediately before `'.'` and its final
     * colon-delimited segment is digits (`:<digits>.`), stops right before `<digits>` (leaving
     * `':'` at `skipEnd - 1`) so trailing IPv4 / MaskedIpv4 tokens are never jumped past or split
     * mid-octet.
     */
    fun scanHexColonRunSkipEnd(text: String, start: Int, n: Int): Int {
        var fullEnd = start
        while (fullEnd < n && (isHexChar(text[fullEnd]) || text[fullEnd] == ':')) {
            fullEnd++
        }
        if (fullEnd < n && text[fullEnd] == '.') {
            var segStart = fullEnd
            while (segStart > start && text[segStart - 1] in '0'..'9') {
                segStart--
            }
            if (segStart < fullEnd && segStart > start && text[segStart - 1] == ':') {
                return segStart
            }
        }
        return fullEnd
    }

    fun findLastColon(text: String, start: Int, end: Int): Int {
        for (idx in end - 1 downTo start) {
            if (text[idx] == ':') return idx
        }
        return -1
    }

    fun hasAtLeastTwoColons(text: String, start: Int, end: Int): Boolean {
        var colons = 0
        for (idx in start until end) {
            if (text[idx] == ':') {
                colons++
                if (colons >= 2) return true
            }
        }
        return false
    }

    fun containsDotNear(text: String, start: Int, end: Int, n: Int): Boolean =
        end < n && text[end] == '.' && start < end && text[end - 1] in '0'..'9'

    fun isValidIpv6PrefixForEmbeddedIpv4(text: String, start: Int, lastColon: Int): Boolean {
        val len = lastColon + 1 - start
        if (len < 2) return false
        for (k in start..lastColon - 2) {
            if (text[k] == ':' && text[k + 1] == ':' && text[k + 2] == ':') return false
        }
        if (text[lastColon - 1] == ':' && text[lastColon] == ':') {
            val leftEnd = lastColon - 1
            for (k in start until leftEnd - 1) {
                if (text[k] == ':' && text[k + 1] == ':') return false
            }
            val leftGroups = countAndValidateHextetGroups(text, start, leftEnd)
            return leftGroups in 0..5
        }
        var doubleColonIndex = -1
        for (k in start until lastColon - 1) {
            if (text[k] == ':' && text[k + 1] == ':') {
                if (doubleColonIndex >= 0) return false
                doubleColonIndex = k
            }
        }
        return if (doubleColonIndex >= 0) {
            val leftGroups = countAndValidateHextetGroups(text, start, doubleColonIndex)
            if (leftGroups < 0) return false
            val rightGroups = countAndValidateHextetGroups(text, doubleColonIndex + 2, lastColon)
            if (rightGroups < 0) return false
            (leftGroups + rightGroups) in 1..5
        } else {
            countAndValidateHextetGroups(text, start, lastColon) == 6
        }
    }

    fun countAndValidateHextetGroups(token: String, start: Int, end: Int): Int {
        if (start == end) return 0
        var groups = 0
        var groupStart = start
        for (idx in start..end) {
            if (idx == end || token[idx] == ':') {
                val len = idx - groupStart
                if (len !in 1..4) return -1
                for (k in groupStart until idx) {
                    if (!isHexChar(token[k])) return -1
                }
                groups++
                groupStart = idx + 1
            }
        }
        return groups
    }
}
