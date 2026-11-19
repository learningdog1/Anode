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
 */
object IpPrivacyMasker {
    private val ANSI_SGR_REGEX = Regex("""\u001B\[[0-9;]*m""")

    private val IPV4_CANDIDATE_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![A-Za-z0-9.])""",
    )

    // Matches hex+colon tokens that may represent RFC 4291 IPv6 addresses; strictly validated in code
    // to prevent false positives on timestamps (`12:34:56`) or MAC addresses (`aa:bb:cc:dd:ee:ff`).
    private val IPV6_CANDIDATE_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9:])([0-9A-Fa-f:]{4,39})(?![A-Za-z0-9:])""",
    )

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
        if (text.isEmpty()) return text
        val normalizedHost = knownServerHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val sb = StringBuilder(text.length)
        var cursor = 0
        for (ansiMatch in ANSI_SGR_REGEX.findAll(text)) {
            if (ansiMatch.range.first > cursor) {
                sb.append(maskPlainSegment(text.substring(cursor, ansiMatch.range.first), normalizedHost))
            }
            sb.append(ansiMatch.value)
            cursor = ansiMatch.range.last + 1
        }
        if (cursor < text.length) {
            sb.append(maskPlainSegment(text.substring(cursor), normalizedHost))
        }
        return sb.toString()
    }

    private fun maskPlainSegment(segment: String, normalizedHost: String?): String {
        val afterV4 = IPV4_CANDIDATE_REGEX.replace(segment) { match ->
            val a = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val b = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val c = match.groupValues[3].toIntOrNull() ?: return@replace match.value
            val d = match.groupValues[4].toIntOrNull() ?: return@replace match.value
            if (a !in 0..255 || b !in 0..255 || c !in 0..255 || d !in 0..255) {
                return@replace match.value
            }
            // Reject leading-zero tokens like "01.02.03.04" that are usually version numbers or dates
            if (hasDisallowedLeadingZero(match.groupValues[1]) ||
                hasDisallowedLeadingZero(match.groupValues[2]) ||
                hasDisallowedLeadingZero(match.groupValues[3]) ||
                hasDisallowedLeadingZero(match.groupValues[4])
            ) {
                return@replace match.value
            }
            val literal = match.value
            val isExplicitServerHost = normalizedHost != null && literal == normalizedHost
            if (!isExplicitServerHost && shouldPreserveIpv4(literal, a, b, c)) {
                return@replace literal
            }
            "$a.$b.*.*"
        }

        return IPV6_CANDIDATE_REGEX.replace(afterV4) { match ->
            val candidate = match.value
            if (!isValidIpv6Address(candidate)) return@replace candidate
            val lower = candidate.lowercase()
            val isExplicitServerHost = normalizedHost != null && lower == normalizedHost
            if (!isExplicitServerHost && shouldPreserveIpv6(lower)) {
                return@replace candidate
            }
            maskIpv6Literal(candidate)
        }
    }

    private fun hasDisallowedLeadingZero(octetText: String): Boolean =
        octetText.length > 1 && octetText[0] == '0'

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
            val leftPart = token.substring(0, doubleColonIndex)
            val rightPart = token.substring(doubleColonIndex + 2)
            val leftGroups = if (leftPart.isEmpty()) emptyList() else leftPart.split(':')
            val rightGroups = if (rightPart.isEmpty()) emptyList() else rightPart.split(':')
            val totalGroups = leftGroups.size + rightGroups.size
            if (totalGroups !in 1..7) return false
            return leftGroups.all(::isHexHextet) && rightGroups.all(::isHexHextet)
        } else {
            // Uncompressed IPv6 must have exactly 8 colon-separated hex groups (excludes HH:MM:SS & MACs)
            val groups = token.split(':')
            if (groups.size != 8) return false
            return groups.all(::isHexHextet)
        }
    }

    private fun isHexHextet(group: String): Boolean {
        if (group.length !in 1..4) return false
        for (ch in group) {
            val isHex = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
            if (!isHex) return false
        }
        return true
    }

    private fun maskIpv6Literal(address: String): String {
        val groups = address.split(':').filter { it.isNotEmpty() }
        return when {
            groups.size >= 2 -> "${groups[0]}:${groups[1]}:*:*"
            groups.size == 1 -> "${groups[0]}::*"
            else -> "*:*"
        }
    }
}
