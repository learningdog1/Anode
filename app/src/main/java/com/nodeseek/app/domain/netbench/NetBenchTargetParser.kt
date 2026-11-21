package com.nodeseek.app.domain.netbench

/**
 * Classification of Fake-IP / sinkhole / hijacked IP addresses commonly injected by
 * transparent TUN/router proxies (Clash, Mihomo, Sing-box, Surge) or DNS blockers.
 */
enum class SuspectResolvedIpKind(val reasonLabel: String) {
    NONE("正常地址"),

    /** RFC 2544 `198.18.0.0/15` (`198.18.0.0`–`198.19.255.255`) Fake-IP pool used by Clash / Sing-box / Surge. */
    PROXY_FAKE_IP_V4("代理 Fake-IP (198.18.0.0/15)"),

    /** `fc00::/18` (`fc00::`–`fc3f:ffff:...`) IPv6 Fake-IP pool used by Sing-box / Mihomo / Clash. */
    PROXY_FAKE_IP_V6("代理 Fake-IPv6 (fc00::/18)"),

    /** `0.0.0.0/8` or IPv6 `::` unspecified / DNS sinkhole response. */
    UNSPECIFIED_SINKHOLE("未指定/黑洞拦截地址"),

    /** `127.0.0.0/8` or IPv6 `::1` loopback returned when resolving a non-localhost public hostname. */
    LOOPBACK_HIJACK("本地回环劫持地址"),
    ;

    val isSuspect: Boolean
        get() = this != NONE

    val isFakeIp: Boolean
        get() = this == PROXY_FAKE_IP_V4 || this == PROXY_FAKE_IP_V6
}

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
     * Optionally masks the host portion of a target VPS IP when the user enables
     * "隐藏目标 IP 末段" in exported Markdown reports and rendered card images.
     *
     * - IPv4 (`203.0.113.42` -> `203.0.113.*`)
     * - IPv4-mapped IPv6 (`::ffff:203.0.113.42` -> `::ffff:203.0.113.*`)
     * - IPv6 (`2001:db8:85a3::8a2e:370:7334` / `2001:db8:0:0:0:0:0:1` -> `2001:db8:85a3::****` / `2001:db8::****`)
     */
    fun maskTargetIpLastSegment(ip: String): String {
        val clean = stripIpv6Zone(ip.trim().removePrefix("[").removeSuffix("]"))
        return when (detectIpFamily(clean)) {
            IpFamily.IPV4 -> {
                val parts = clean.split('.')
                if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.*" else clean
            }
            IpFamily.IPV6 -> {
                val hextets = parseIpv6Hextets(clean)
                if (hextets != null) {
                    val isV4Mapped = hextets[0] == 0 &&
                        hextets[1] == 0 &&
                        hextets[2] == 0 &&
                        hextets[3] == 0 &&
                        hextets[4] == 0 &&
                        hextets[5] == 0xFFFF
                    if (isV4Mapped) {
                        val a = (hextets[6] ushr 8) and 0xFF
                        val b = hextets[6] and 0xFF
                        val c = (hextets[7] ushr 8) and 0xFF
                        return "::ffff:$a.$b.$c.*"
                    }
                    // Retain up to the first 3 routing-prefix hextets (at most /48, trimming trailing zero groups)
                    var prefixEnd = 3
                    while (prefixEnd > 0 && hextets[prefixEnd - 1] == 0) {
                        prefixEnd--
                    }
                    return if (prefixEnd == 0) {
                        "::****"
                    } else {
                        val prefix = (0 until prefixEnd).joinToString(":") { idx ->
                            hextets[idx].toString(16)
                        }
                        "$prefix::****"
                    }
                }
                val lastColon = clean.lastIndexOf(':')
                if (lastColon >= 0) "${clean.substring(0, lastColon + 1)}****" else clean
            }
            IpFamily.UNKNOWN -> clean
        }
    }

    /**
     * Returns all canonical textual representations of [ip] (for IPv6: input literal, RFC 5952
     * compressed, Java `Inet6Address.getHostAddress()` short uncompressed, and full zero-padded
     * uncompressed; for IPv4: clean dotted quad) to guarantee complete scrubbing across libraries.
     */
    fun allCanonicalIpForms(ip: String): Set<String> {
        val clean = stripIpv6Zone(ip.trim().removePrefix("[").removeSuffix("]"))
        if (clean.isEmpty()) return emptySet()
        return when (detectIpFamily(clean)) {
            IpFamily.IPV4 -> setOf(clean)
            IpFamily.IPV6 -> {
                val hextets = parseIpv6Hextets(clean) ?: return setOf(clean)
                buildSet {
                    add(clean)
                    add(formatCanonicalIpv6Compressed(hextets))
                    add(hextets.joinToString(":") { it.toString(16) })
                    add(hextets.joinToString(":") { it.toString(16).padStart(4, '0') })
                    val isV4Mapped = hextets[0] == 0 &&
                        hextets[1] == 0 &&
                        hextets[2] == 0 &&
                        hextets[3] == 0 &&
                        hextets[4] == 0 &&
                        hextets[5] == 0xFFFF
                    if (isV4Mapped) {
                        val v4 = "${(hextets[6] ushr 8) and 0xFF}.${hextets[6] and 0xFF}." +
                            "${(hextets[7] ushr 8) and 0xFF}.${hextets[7] and 0xFF}"
                        add(v4)
                        add("::ffff:$v4")
                        add("0:0:0:0:0:ffff:$v4")
                    }
                }
            }
            IpFamily.UNKNOWN -> emptySet()
        }
    }

    /**
     * Formats 8 IPv6 16-bit hextets according to RFC 5952 canonical compression rules.
     */
    internal fun formatCanonicalIpv6Compressed(hextets: IntArray): String {
        require(hextets.size == 8) { "Expected 8 IPv6 hextets" }
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        var curLen = 0
        for (i in 0..7) {
            if (hextets[i] == 0) {
                if (curStart < 0) {
                    curStart = i
                    curLen = 1
                } else {
                    curLen++
                }
                if (curLen >= 2 && curLen > bestLen) {
                    bestStart = curStart
                    bestLen = curLen
                }
            } else {
                curStart = -1
                curLen = 0
            }
        }
        if (bestStart < 0) {
            return hextets.joinToString(":") { it.toString(16) }
        }
        val left = (0 until bestStart).joinToString(":") { hextets[it].toString(16) }
        val right = ((bestStart + bestLen)..7).joinToString(":") { hextets[it].toString(16) }
        return "$left::$right"
    }

    /**
     * Returns `true` when [hostname] explicitly names the local loopback host (`localhost`, `*.localhost`, etc.).
     */
    fun isLocalhostHostname(hostname: String?): Boolean {
        val clean = hostname?.trim()?.trimEnd('.')?.lowercase() ?: return false
        return clean == "localhost" ||
            clean.endsWith(".localhost") ||
            clean == "ip6-localhost" ||
            clean == "ip6-loopback"
    }

    /**
     * Returns `true` when [ip] falls inside a proxy Fake-IP virtual pool (`198.18.0.0/15` or `fc00::/18`).
     */
    fun isFakeIpLiteral(ip: String?): Boolean =
        classifySuspectIp(ip = ip, targetHost = null, checkLoopbackForPublicHost = false).isFakeIp

    /**
     * Classifies whether [ip] is a proxy Fake-IP (`198.18.0.0/15`, `fc00::/18`), an unspecified
     * DNS sinkhole (`0.0.0.0/8`, `::`), or a loopback hijack (`127.0.0.0/8`, `::1` for non-localhost).
     */
    fun classifySuspectIp(
        ip: String?,
        targetHost: String? = null,
        checkLoopbackForPublicHost: Boolean = true,
    ): SuspectResolvedIpKind {
        val clean = stripIpv6Zone(ip?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return SuspectResolvedIpKind.NONE)
        if (clean.isEmpty()) return SuspectResolvedIpKind.NONE
        val allowLoopback = !checkLoopbackForPublicHost || isLocalhostHostname(targetHost)

        parseIpv4Octets(clean)?.let { octets ->
            return classifyIpv4Octets(octets, allowLoopback = allowLoopback)
        }

        parseIpv6Hextets(clean)?.let { hextets ->
            // IPv4-mapped IPv6 ::ffff:a.b.c.d
            val isV4Mapped = hextets[0] == 0 &&
                hextets[1] == 0 &&
                hextets[2] == 0 &&
                hextets[3] == 0 &&
                hextets[4] == 0 &&
                hextets[5] == 0xFFFF
            if (isV4Mapped) {
                val mappedOctets = intArrayOf(
                    (hextets[6] ushr 8) and 0xFF,
                    hextets[6] and 0xFF,
                    (hextets[7] ushr 8) and 0xFF,
                    hextets[7] and 0xFF,
                )
                return classifyIpv4Octets(mappedOctets, allowLoopback = allowLoopback)
            }

            val allZeroPrefix = (0..6).all { hextets[it] == 0 }
            if (allZeroPrefix && hextets[7] == 0) {
                return SuspectResolvedIpKind.UNSPECIFIED_SINKHOLE
            }
            if (allZeroPrefix && hextets[7] == 1) {
                return if (allowLoopback) SuspectResolvedIpKind.NONE else SuspectResolvedIpKind.LOOPBACK_HIJACK
            }
            // Sing-box / Mihomo / Clash IPv6 Fake-IP default range: fc00::/18 (fc00:: .. fc3f:ffff:...)
            if ((hextets[0] and 0xFFC0) == 0xFC00) {
                return SuspectResolvedIpKind.PROXY_FAKE_IP_V6
            }
        }

        return SuspectResolvedIpKind.NONE
    }

    private fun classifyIpv4Octets(octets: IntArray, allowLoopback: Boolean): SuspectResolvedIpKind {
        val b0 = octets[0]
        val b1 = octets[1]
        return when {
            // RFC 2544 198.18.0.0/15 (198.18.0.0 - 198.19.255.255): standard Clash / Sing-box / Surge Fake-IP range
            b0 == 198 && (b1 == 18 || b1 == 19) -> SuspectResolvedIpKind.PROXY_FAKE_IP_V4
            // 0.0.0.0/8 ("this network" / DNS blocker sinkhole)
            b0 == 0 -> SuspectResolvedIpKind.UNSPECIFIED_SINKHOLE
            // 127.0.0.0/8 loopback hijack on public domain
            b0 == 127 && !allowLoopback -> SuspectResolvedIpKind.LOOPBACK_HIJACK
            else -> SuspectResolvedIpKind.NONE
        }
    }

    internal fun parseIpv4Octets(candidate: String): IntArray? {
        if (!isIpv4Literal(candidate)) return null
        val parts = candidate.trim().split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }

    internal fun parseIpv6Hextets(candidate: String): IntArray? {
        val raw = stripIpv6Zone(candidate.trim())
        if (!isIpv6Literal(raw)) return null
        val doubleColonParts = raw.split("::")
        val hasCompression = doubleColonParts.size == 2
        val leftGroups = if (doubleColonParts[0].isEmpty()) emptyList() else doubleColonParts[0].split(':')
        val rightGroups = if (hasCompression && doubleColonParts[1].isNotEmpty()) {
            doubleColonParts[1].split(':')
        } else {
            emptyList()
        }

        fun expandGroups(groups: List<String>): List<Int>? {
            if (groups.isEmpty()) return emptyList()
            val result = ArrayList<Int>(groups.size + 1)
            for (i in groups.indices) {
                val part = groups[i]
                if (i == groups.lastIndex && part.contains('.')) {
                    val v4 = parseIpv4Octets(part) ?: return null
                    result += ((v4[0] shl 8) or v4[1])
                    result += ((v4[2] shl 8) or v4[3])
                } else {
                    val v = part.toIntOrNull(16) ?: return null
                    if (v !in 0..0xFFFF) return null
                    result += v
                }
            }
            return result
        }

        val left = expandGroups(leftGroups) ?: return null
        val right = expandGroups(rightGroups) ?: return null
        val zeroFill = 8 - left.size - right.size
        if (zeroFill < 0 || (!hasCompression && zeroFill != 0)) return null

        val out = IntArray(8)
        var idx = 0
        for (v in left) out[idx++] = v
        repeat(zeroFill) { out[idx++] = 0 }
        for (v in right) out[idx++] = v
        return out
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
