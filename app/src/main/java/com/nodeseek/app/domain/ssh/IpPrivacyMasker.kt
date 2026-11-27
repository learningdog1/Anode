package com.nodeseek.app.domain.ssh

/**
 * Privacy masker for VPS benchmark terminal logs.
 *
 * Masks public IPv4 (`203.0.113.42` -> `203.0.*.*`) and public IPv6
 * (`2a01:4f8:c012:1234::1` -> `2a01:4f8:*:*`) addresses while:
 * - Preserving all ANSI SGR escape sequences (`\u001B[...m`) completely intact, even when an IP
 *   is immediately wrapped by color codes.
 * - Keeping routing prefixes (`/16` IPv4 & `/32` IPv6) readable for NodeSeek network reviews
 *   (e.g. `59.43.*.*` CN2 GIA remains identifiable without exposing exact host IPs).
 * - Preserving loopback, RFC1918 private addresses, and well-known DNS resolvers (`1.1.1.1`,
 *   `8.8.8.8`, `8.8.4.4`, `9.9.9.9`) unless explicitly equal to [knownServerHost].
 *
 * Performance guarantee:
 * - Uses [IpMarkdownStructuralScanner] (single-pass, zero-regex scanner with lazy `StringBuilder`
 *   allocation) so multi-megabyte ANSI benchmark transcripts with >150,000 SGR sequences complete
 *   in a few milliseconds without `Matcher`, `substring`, or `split` churn.
 */
object IpPrivacyMasker {
    private val WELL_KNOWN_DNS_IPV4: Set<String> = setOf(
        "1.1.1.1",
        "1.0.0.1",
        "8.8.8.8",
        "8.8.4.4",
        "9.9.9.9",
        "114.114.114.114",
        "223.5.5.5",
        "223.6.6.6",
    )

    /**
     * Masks public IPv4 and IPv6 addresses inside [text].
     *
     * If [knownServerHost] is provided and is an IPv4/IPv6 literal, that exact address is always
     * masked even if it falls in a private or reserved range.
     */
    fun mask(
        text: String,
        knownServerHost: String? = null,
    ): String {
        val normalizedHost = knownServerHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return IpMarkdownStructuralScanner.scanAndTransform(
            text = text,
            skipAnsiSgr = true,
            onMaskedIpToken = null,
            onRawIpv4 = { s, start, end, out ->
                out.replacement = evaluateIpv4Candidate(s, start, end, normalizedHost)
            },
            onRawIpv6 = { s, start, n, totalOpen, out ->
                evaluateIpv6Candidate(s, start, n, totalOpen, normalizedHost, out)
            },
        )
    }

    private fun evaluateIpv6Candidate(
        text: String,
        start: Int,
        n: Int,
        totalOpen: Int,
        normalizedHost: String?,
        out: IpMarkdownStructuralScanner.TokenResult,
    ) {
        val ipv6End = IpMarkdownStructuralScanner.scanIpv6CandidateEnd(text, start, n, maxLen = 41)
        val tokenLen = ipv6End - start

        // Check IPv4-embedded IPv6 (e.g. `::ffff:203.0.113.42`, `0:0:0:0:0:ffff:203.0.113.42`)
        if (ipv6End < n && text[ipv6End] == '.' && tokenLen >= 3) {
            val lastColon = IpMarkdownStructuralScanner.findLastColon(text, start, ipv6End)
            if (lastColon >= start && lastColon + 1 < ipv6End) {
                val embeddedV4End = IpMarkdownStructuralScanner.tryScanIpv4End(text, lastColon + 1, n)
                if (embeddedV4End > lastColon + 1 &&
                    IpMarkdownStructuralScanner.isRawIpv4RightBoundaryAllowed(
                        text = text,
                        ipv4End = embeddedV4End,
                        totalOpen = totalOpen,
                        n = n,
                        skipAnsiSgr = true,
                    ) &&
                    IpMarkdownStructuralScanner.isValidIpv6PrefixForEmbeddedIpv4(text, start, lastColon)
                ) {
                    val fullLower = text.substring(start, embeddedV4End).lowercase()
                    val isExplicitServerHost = normalizedHost != null && fullLower == normalizedHost
                    val embeddedHostOverride = if (isExplicitServerHost) {
                        text.substring(lastColon + 1, embeddedV4End)
                    } else {
                        normalizedHost
                    }
                    val maskedEmbeddedV4 = evaluateIpv4Candidate(
                        text = text,
                        start = lastColon + 1,
                        end = embeddedV4End,
                        normalizedHost = embeddedHostOverride,
                    )
                    if (maskedEmbeddedV4 != null) {
                        out.replacement = text.substring(start, lastColon + 1) + maskedEmbeddedV4
                    }
                    out.endIndex = embeddedV4End
                    return
                }
            }
        }

        var effectiveEnd = ipv6End
        var hasTrailingColonBeforeStar = false
        if (effectiveEnd - start >= 3 &&
            text[effectiveEnd - 1] == ':' &&
            text[effectiveEnd - 2] != ':' &&
            IpMarkdownStructuralScanner.peekNextNonSgrChar(text, effectiveEnd, n) == '*'
        ) {
            effectiveEnd -= 1
            hasTrailingColonBeforeStar = true
        }
        val effectiveLen = effectiveEnd - start
        if (effectiveLen in 2..39) {
            val nextPlainChar = if (hasTrailingColonBeforeStar) {
                '*'
            } else {
                IpMarkdownStructuralScanner.peekNextNonSgrCharForIpRightBoundary(text, effectiveEnd, n)
            }
            val dotFollowsDigitOrStar = if (!hasTrailingColonBeforeStar && nextPlainChar == '.') {
                val afterDotIdx = IpMarkdownStructuralScanner.skipSgrSequences(text, effectiveEnd + 1, n)
                val afterDot = if (afterDotIdx < n) text[afterDotIdx] else '\u0000'
                afterDot in '0'..'9' || (
                    afterDot == '*' && (
                        hasDotInRange(text, start, effectiveEnd) ||
                            IpMarkdownStructuralScanner.peekNextNonSgrChar(text, afterDotIdx + 1, n) == '.'
                        )
                    )
            } else {
                false
            }
            val trailingStarAfterColon = if (!hasTrailingColonBeforeStar &&
                nextPlainChar == '*' &&
                text[effectiveEnd - 1] == ':'
            ) {
                val starStartIdx = IpMarkdownStructuralScanner.skipSgrSequences(text, effectiveEnd, n)
                var starEndIdx = starStartIdx
                while (starEndIdx < n && text[starEndIdx] == '*') {
                    starEndIdx++
                }
                val starRunLen = starEndIdx - starStartIdx
                val afterStars = IpMarkdownStructuralScanner.peekNextNonSgrChar(text, starEndIdx, n)
                val afterColonAfterStar = if (afterStars == ':') {
                    val colonIdx = IpMarkdownStructuralScanner.skipSgrSequences(text, starEndIdx, n)
                    IpMarkdownStructuralScanner.peekNextNonSgrChar(text, colonIdx + 1, n)
                } else {
                    '\u0000'
                }
                val isColonWildcardAfterStar = afterStars == ':' &&
                    afterColonAfterStar != '\u0000' &&
                    !afterColonAfterStar.isWhitespace()
                isColonWildcardAfterStar ||
                    !(starRunLen in 1..3 && (totalOpen >= starRunLen || IpMarkdownStructuralScanner.isItalicOpeningAfterStar(afterStars)))
            } else {
                false
            }
            if (!dotFollowsDigitOrStar &&
                !trailingStarAfterColon &&
                (hasTrailingColonBeforeStar || !IpMarkdownStructuralScanner.isIpv6BoundaryBlocked(nextPlainChar)) &&
                IpMarkdownStructuralScanner.hasAtLeastTwoColons(text, start, effectiveEnd)
            ) {
                val candidate = text.substring(start, effectiveEnd)
                if (isValidIpv6Address(candidate)) {
                    val lower = candidate.lowercase()
                    val isExplicitServerHost = normalizedHost != null && lower == normalizedHost
                    if (isExplicitServerHost || !shouldPreserveIpv6(lower)) {
                        out.replacement = maskIpv6Literal(candidate)
                    }
                    out.endIndex = effectiveEnd
                    return
                }
            }
        }

        // Advance past non-matching hex/colon run safely without ever truncating mid-octet before '.'
        if (IpMarkdownStructuralScanner.scanHexColonRunSkipEnd(text, start, n) - start > 1) {
            out.skipRunOnMiss = true
        }
    }

    private fun hasDotInRange(text: String, start: Int, end: Int): Boolean {
        for (idx in start until end) {
            if (text[idx] == '.') return true
        }
        return false
    }

    private fun evaluateIpv4Candidate(
        text: String,
        start: Int,
        end: Int,
        normalizedHost: String?,
    ): String? {
        var pos = start
        var a = 0
        var b = 0
        var c = 0
        for (octetIdx in 0 until 4) {
            if (octetIdx > 0) pos++ // skip '.'
            val octStart = pos
            var value = 0
            while (pos < end && text[pos] != '.') {
                value = value * 10 + (text[pos] - '0')
                pos++
            }
            val octLen = pos - octStart
            // Reject leading-zero tokens like "01.02.03.04" that are usually version numbers or dates
            if (octLen > 1 && text[octStart] == '0') return null
            if (value !in 0..255) return null
            when (octetIdx) {
                0 -> a = value
                1 -> b = value
                2 -> c = value
            }
        }
        val literal = text.substring(start, end)
        val isExplicitServerHost = normalizedHost != null && literal == normalizedHost
        if (!isExplicitServerHost && shouldPreserveIpv4(literal, a, b, c)) {
            return null
        }
        return "$a.$b.*.*"
    }

    private fun shouldPreserveIpv4(literal: String, a: Int, b: Int, c: Int): Boolean {
        if (literal in WELL_KNOWN_DNS_IPV4) return true
        if (a == 0 || a == 10 || a == 127) return true
        if (a == 169 && b == 254) return true
        if (a == 172 && b in 16..31) return true
        if (a == 192 && b == 168) return true
        if (a == 255 && b == 255 && c == 255) return true
        return false
    }

    private fun shouldPreserveIpv6(lower: String): Boolean {
        if (lower == "::" || lower == "::1") return true
        if (lower.startsWith("fe80:") || lower.startsWith("fc") || lower.startsWith("fd")) return true
        if (lower.startsWith("ff")) return true // multicast
        if (lower == "2606:4700:4700::1111" || lower == "2606:4700:4700::1001" ||
            lower == "2001:4860:4860::8888" || lower == "2001:4860:4860::8844"
        ) {
            return true
        }
        return false
    }

    internal fun isValidIpv6Address(token: String): Boolean {
        if (!token.contains(':')) return false
        if (token.contains(":::")) return false

        val doubleColonIndex = token.indexOf("::")
        if (doubleColonIndex >= 0) {
            // At most one "::" allowed
            if (token.indexOf("::", startIndex = doubleColonIndex + 2) >= 0) return false
            val leftGroups = IpMarkdownStructuralScanner.countAndValidateHextetGroups(token, 0, doubleColonIndex)
            if (leftGroups < 0) return false
            val rightGroups = IpMarkdownStructuralScanner.countAndValidateHextetGroups(token, doubleColonIndex + 2, token.length)
            if (rightGroups < 0) return false
            val totalGroups = leftGroups + rightGroups
            return totalGroups in 1..7
        } else {
            // Uncompressed IPv6 must have exactly 8 colon-separated hex groups (excludes HH:MM:SS & MACs)
            return IpMarkdownStructuralScanner.countAndValidateHextetGroups(token, 0, token.length) == 8
        }
    }

    private fun maskIpv6Literal(address: String): String {
        if (address.startsWith("::")) {
            return "0:0:*:*"
        }
        var g1Start = -1
        var g1End = -1
        var g2Start = -1
        var g2End = -1
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
                }
                segStart = idx + 1
            }
        }
        return when {
            g2Start >= 0 -> "${address.substring(g1Start, g1End)}:${address.substring(g2Start, g2End)}:*:*"
            g1Start >= 0 -> "${address.substring(g1Start, g1End)}:*:*"
            else -> "0:0:*:*"
        }
    }
}
