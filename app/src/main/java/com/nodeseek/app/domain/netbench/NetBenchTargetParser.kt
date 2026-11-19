package com.nodeseek.app.domain.netbench

/**
 * Pure Kotlin parser & IP formatting utility for Mobile Network Tester inputs.
 *
 * Supports:
 * - IPv4 (`1.1.1.1`, `1.1.1.1:443`)
 * - Bracketed IPv6 (`[2001:db8::1]`, `[2001:db8::1]:5201`)
 * - Raw IPv6 (`2001:db8::1`, `::1`, `2606:4700:4700::1111`)
 * - Domain names (`lg.example.com`, `lg.example.com:8443`)
 * - Pasted HTTP/HTTPS/TCP URLs (`https://lg.example.com:8443/path`)
 */
object NetBenchTargetParser {

    private val SCHEME_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.*)$")
    private val IPV4_REGEX = Regex(
        "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$",
    )
    private val DOMAIN_REGEX = Regex(
        "^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$",
    )

    /**
     * Parses [rawInput] into a structured [ParsedTarget], returning `null` if blank or invalid.
     */
    fun parse(rawInput: String): ParsedTarget? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        var working = trimmed
        var scheme: String? = null

        val schemeMatch = SCHEME_REGEX.matchEntire(working)
        if (schemeMatch != null) {
            scheme = schemeMatch.groupValues[1].lowercase()
            working = schemeMatch.groupValues[2]
        }

        // Strip userinfo if present (user:pass@host)
        val atIndex = working.indexOf('@')
        if (atIndex >= 0 && working.substring(0, atIndex).none { it == '/' || it == '?' || it == '#' }) {
            working = working.substring(atIndex + 1)
        }

        // Strip path, query, fragment
        val pathCut = working.indexOfAny(charArrayOf('/', '?', '#'))
        if (pathCut >= 0) {
            working = working.substring(0, pathCut)
        }

        working = working.trim()
        if (working.isEmpty()) return null

        // Case 1: Bracketed IPv6 e.g. [2001:db8::1] or [2001:db8::1]:443
        if (working.startsWith('[')) {
            val closeIdx = working.indexOf(']')
            if (closeIdx <= 1) return null
            val rawIpv6 = stripIpv6Zone(working.substring(1, closeIdx))
            if (!isIpv6Literal(rawIpv6)) return null
            val remainder = working.substring(closeIdx + 1)
            val port = when {
                remainder.isEmpty() -> inferDefaultPortFromScheme(scheme)
                remainder.startsWith(':') -> parseValidPort(remainder.substring(1)) ?: return null
                else -> return null
            }
            return ParsedTarget(
                rawInput = trimmed,
                host = rawIpv6,
                explicitPort = port,
                isLiteralIp = true,
                literalFamily = IpFamily.IPV6,
                suggestedHttpScheme = normalizeHttpScheme(scheme),
            )
        }

        // Case 2: Raw IPv6 (contains >= 2 colons)
        val colonCount = working.count { it == ':' }
        if (colonCount >= 2) {
            val rawIpv6 = stripIpv6Zone(working)
            if (!isIpv6Literal(rawIpv6)) return null
            return ParsedTarget(
                rawInput = trimmed,
                host = rawIpv6,
                explicitPort = inferDefaultPortFromScheme(scheme),
                isLiteralIp = true,
                literalFamily = IpFamily.IPV6,
                suggestedHttpScheme = normalizeHttpScheme(scheme),
            )
        }

        // Case 3: host or host:port (IPv4 or domain)
        val hostPart: String
        val explicitPort: Int?
        if (colonCount == 1) {
            val idx = working.indexOf(':')
            hostPart = working.substring(0, idx).trim()
            val portStr = working.substring(idx + 1).trim()
            if (hostPart.isEmpty() || portStr.isEmpty()) return null
            explicitPort = parseValidPort(portStr) ?: return null
        } else {
            hostPart = working.trimEnd('.')
            explicitPort = inferDefaultPortFromScheme(scheme)
        }

        if (hostPart.isEmpty()) return null

        return when {
            isIpv4Literal(hostPart) -> ParsedTarget(
                rawInput = trimmed,
                host = hostPart,
                explicitPort = explicitPort,
                isLiteralIp = true,
                literalFamily = IpFamily.IPV4,
                suggestedHttpScheme = normalizeHttpScheme(scheme),
            )
            isValidHostname(hostPart) -> ParsedTarget(
                rawInput = trimmed,
                host = hostPart,
                explicitPort = explicitPort,
                isLiteralIp = false,
                literalFamily = IpFamily.UNKNOWN,
                suggestedHttpScheme = normalizeHttpScheme(scheme),
            )
            else -> null
        }
    }

    fun isIpv4Literal(candidate: String): Boolean = IPV4_REGEX.matches(candidate.trim())

    /**
     * Pure Kotlin RFC 4291 / 5952 IPv6 literal validator (handles compressed `::` and embedded IPv4).
     */
    fun isIpv6Literal(candidate: String): Boolean {
        val raw = stripIpv6Zone(candidate.trim())
        if (raw.isEmpty() || !raw.contains(':')) return false
        if (raw.contains(":::")) return false

        val doubleColonParts = raw.split("::")
        if (doubleColonParts.size > 2) return false

        val hasCompression = doubleColonParts.size == 2
        val leftGroups = if (doubleColonParts[0].isEmpty()) emptyList() else doubleColonParts[0].split(':')
        val rightGroups = if (hasCompression && doubleColonParts[1].isNotEmpty()) {
            doubleColonParts[1].split(':')
        } else {
            emptyList()
        }

        fun countUnits(groups: List<String>, allowTrailingIpv4: Boolean): Int? {
            if (groups.isEmpty()) return 0
            var count = 0
            for (i in groups.indices) {
                val part = groups[i]
                if (part.isEmpty()) return null
                if (allowTrailingIpv4 && i == groups.lastIndex && part.contains('.')) {
                    if (!isIpv4Literal(part)) return null
                    count += 2
                } else {
                    if (part.length !in 1..4) return null
                    if (part.any { !isHexChar(it) }) return null
                    count += 1
                }
            }
            return count
        }

        val leftCount = countUnits(leftGroups, allowTrailingIpv4 = !hasCompression) ?: return false
        val rightCount = countUnits(rightGroups, allowTrailingIpv4 = true) ?: return false
        val total = leftCount + rightCount

        return if (hasCompression) total < 8 else total == 8
    }

    fun isValidHostname(candidate: String): Boolean {
        val trimmed = candidate.trim().trimEnd('.')
        if (trimmed.isEmpty() || trimmed.length > 253) return false
        if (trimmed.equals("localhost", ignoreCase = true)) return true
        // Reject all-numeric dotted strings that failed IPv4 check (e.g. 999.999.999.999)
        if (trimmed.all { it.isDigit() || it == '.' }) return false
        return DOMAIN_REGEX.matches(trimmed)
    }

    fun detectIpFamily(ip: String?): IpFamily {
        val clean = ip?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return IpFamily.UNKNOWN
        return when {
            isIpv4Literal(clean) -> IpFamily.IPV4
            isIpv6Literal(clean) -> IpFamily.IPV6
            else -> IpFamily.UNKNOWN
        }
    }

    /**
     * Masks an IP address for local privacy preview (e.g., `114.240.88.123` -> `114.240.*.*`).
     */
    fun maskClientIpForDisplay(ip: String?): String? {
        val clean = ip?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (detectIpFamily(clean)) {
            IpFamily.IPV4 -> {
                val parts = clean.split('.')
                if (parts.size == 4) "${parts[0]}.${parts[1]}.*.*" else "***.***.***.***"
            }
            IpFamily.IPV6 -> {
                val parts = clean.split(':').filter { it.isNotEmpty() }
                if (parts.size >= 2) "${parts[0]}:${parts[1]}::****" else "****::****"
            }
            IpFamily.UNKNOWN -> "****"
        }
    }

    /**
     * Optionally masks the trailing host segment of a target VPS IP when the user enables
     * "隐藏目标 IP 末段" in exported Markdown reports.
     */
    fun maskTargetIpLastSegment(ip: String): String {
        val clean = ip.trim()
        return when (detectIpFamily(clean)) {
            IpFamily.IPV4 -> {
                val parts = clean.split('.')
                if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.*" else clean
            }
            IpFamily.IPV6 -> {
                val lastColon = clean.lastIndexOf(':')
                if (lastColon >= 0) "${clean.substring(0, lastColon + 1)}****" else clean
            }
            IpFamily.UNKNOWN -> clean
        }
    }

    private fun stripIpv6Zone(raw: String): String {
        val pct = raw.indexOf('%')
        return if (pct >= 0) raw.substring(0, pct) else raw
    }

    private fun parseValidPort(rawPort: String): Int? {
        val parsed = rawPort.toIntOrNull() ?: return null
        return parsed.takeIf { it in 1..65535 }
    }

    private fun inferDefaultPortFromScheme(scheme: String?): Int? = when (scheme) {
        "https" -> 443
        "http" -> 80
        "ssh" -> 22
        else -> null
    }

    private fun normalizeHttpScheme(scheme: String?): String? = when (scheme) {
        "http", "https" -> scheme
        else -> null
    }

    private fun isHexChar(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
}
