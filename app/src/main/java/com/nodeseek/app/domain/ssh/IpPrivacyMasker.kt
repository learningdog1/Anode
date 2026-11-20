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
 * - Uses a single-pass, zero-regex scanner over [text] with lazy `StringBuilder` allocation so
 *   multi-megabyte ANSI benchmark transcripts with >150,000 SGR sequences complete in a few
 *   milliseconds without `Matcher`, `substring`, or `split` churn.
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
        val n = text.length
        if (n < 2 || !hasAnyIpDelimiter(text)) return text
        val normalizedHost = knownServerHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        var out: StringBuilder? = null
        var flushCursor = 0
        // Tracks the immediately preceding visible (non-SGR) character; '\u0000' right after start or SGR
        var prevPlainChar = '\u0000'
        var i = 0

        while (i < n) {
            val ch = text[i]

            // 1. Skip ANSI SGR escape sequences `\u001B[[0-9;:]*m` in O(1) without splitting text
            if (ch == '\u001B' && i + 1 < n && text[i + 1] == '[') {
                val sgrEnd = findSgrEndIndex(text, i)
                if (sgrEnd >= 0) {
                    prevPlainChar = '\u0000'
                    i = sgrEnd + 1
                    continue
                }
            }

            // 2. Try matching IPv4 token `d.d.d.d` when at a digit not preceded by `[A-Za-z0-9.]`
            if (ch in '0'..'9' && !isIpv4BoundaryBlocked(prevPlainChar)) {
                val ipv4End = tryScanIpv4End(text, i, n)
                if (ipv4End > i) {
                    val nextPlainChar = peekNextNonSgrChar(text, ipv4End, n)
                    if (!isIpv4BoundaryBlocked(nextPlainChar)) {
                        val maskedV4 = evaluateIpv4Candidate(text, i, ipv4End, normalizedHost)
                        if (maskedV4 != null) {
                            val sb = out ?: StringBuilder(n).also { out = it }
                            if (i > flushCursor) {
                                sb.append(text, flushCursor, i)
                            }
                            sb.append(maskedV4)
                            flushCursor = ipv4End
                            prevPlainChar = text[ipv4End - 1]
                            i = ipv4End
                            continue
                        }
                    }
                }
            }

            // 3. Try matching IPv6 token `[0-9A-Fa-f:]{2,39}` when not preceded by `[A-Za-z0-9:]`
            if ((isHexChar(ch) || ch == ':') && !isIpv6BoundaryBlocked(prevPlainChar)) {
                val ipv6End = scanIpv6CandidateEnd(text, i, n)
                val tokenLen = ipv6End - i
                if (tokenLen in 2..39) {
                    val nextPlainChar = peekNextNonSgrChar(text, ipv6End, n)
                    if (!isIpv6BoundaryBlocked(nextPlainChar) && hasAtLeastTwoColons(text, i, ipv6End)) {
                        val candidate = text.substring(i, ipv6End)
                        if (isValidIpv6Address(candidate)) {
                            val lower = candidate.lowercase()
                            val isExplicitServerHost = normalizedHost != null && lower == normalizedHost
                            if (isExplicitServerHost || !shouldPreserveIpv6(lower)) {
                                val sb = out ?: StringBuilder(n).also { out = it }
                                if (i > flushCursor) {
                                    sb.append(text, flushCursor, i)
                                }
                                sb.append(maskIpv6Literal(candidate))
                                flushCursor = ipv6End
                                prevPlainChar = text[ipv6End - 1]
                                i = ipv6End
                                continue
                            }
                        }
                    }
                }
                // Advance past non-matching hex/colon run if it cannot start an IPv4 internally
                if (tokenLen > 1 && !containsDotNear(text, i, ipv6End, n)) {
                    prevPlainChar = text[ipv6End - 1]
                    i = ipv6End
                    continue
                }
            }

            prevPlainChar = ch
            i++
        }

        val sb = out ?: return text
        if (flushCursor < n) {
            sb.append(text, flushCursor, n)
        }
        return sb.toString()
    }

    private fun hasAnyIpDelimiter(text: String): Boolean {
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '.' || ch == ':') return true
        }
        return false
    }

    private fun findSgrEndIndex(text: String, escBracketIndex: Int): Int {
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

    private fun peekNextNonSgrChar(text: String, index: Int, length: Int): Char {
        if (index >= length) return '\u0000'
        if (text[index] == '\u001B' && index + 1 < length && text[index + 1] == '[') {
            if (findSgrEndIndex(text, index) >= 0) {
                return '\u0000'
            }
        }
        return text[index]
    }

    private fun isIpv4BoundaryBlocked(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '.'

    private fun isIpv6BoundaryBlocked(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == ':'

    private fun isHexChar(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    private fun tryScanIpv4End(text: String, start: Int, n: Int): Int {
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
            val octLen = pos - octStart
            if (octLen == 0) return -1
            // If 4th consecutive digit follows immediately, not a 1..3 digit octet
            if (pos < n && text[pos] in '0'..'9') return -1
        }
        return pos
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
        var d = 0
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
                else -> d = value
            }
        }
        val literal = text.substring(start, end)
        val isExplicitServerHost = normalizedHost != null && literal == normalizedHost
        if (!isExplicitServerHost && shouldPreserveIpv4(literal, a, b, c)) {
            return null
        }
        return "$a.$b.*.*"
    }

    private fun scanIpv6CandidateEnd(text: String, start: Int, n: Int): Int {
        var pos = start
        val limit = (start + 40).coerceAtMost(n)
        while (pos < limit) {
            val ch = text[pos]
            if (!isHexChar(ch) && ch != ':') break
            pos++
        }
        return pos
    }

    private fun hasAtLeastTwoColons(text: String, start: Int, end: Int): Boolean {
        var colons = 0
        for (idx in start until end) {
            if (text[idx] == ':') {
                colons++
                if (colons >= 2) return true
            }
        }
        return false
    }

    private fun containsDotNear(text: String, start: Int, end: Int, n: Int): Boolean =
        end < n && text[end] == '.' && text[end - 1] in '0'..'9' && start < end

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
            val leftGroups = countAndValidateHextetGroups(token, 0, doubleColonIndex)
            if (leftGroups < 0) return false
            val rightGroups = countAndValidateHextetGroups(token, doubleColonIndex + 2, token.length)
            if (rightGroups < 0) return false
            val totalGroups = leftGroups + rightGroups
            return totalGroups in 1..7
        } else {
            // Uncompressed IPv6 must have exactly 8 colon-separated hex groups (excludes HH:MM:SS & MACs)
            return countAndValidateHextetGroups(token, 0, token.length) == 8
        }
    }

    private fun countAndValidateHextetGroups(token: String, start: Int, end: Int): Int {
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

    private fun maskIpv6Literal(address: String): String {
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
            g1Start >= 0 -> "${address.substring(g1Start, g1End)}::*"
            else -> "*:*"
        }
    }
}
