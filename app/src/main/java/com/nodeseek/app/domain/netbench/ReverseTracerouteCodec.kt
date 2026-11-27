package com.nodeseek.app.domain.netbench

import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Pure Kotlin codec for SSH-triggered VPS reverse traceroute (`VPS -> Client Real Public IP`):
 * - Strictly validates that the client egress IP is a public unicast IPv4/IPv6 literal (rejecting
 *   Fake-IP, loopback, private/CGNAT, domain names, and shell metacharacters).
 * - Builds a non-interactive POSIX shell waterfall trying `nexttrace` (ICMP -> TCP) ->
 *   `mtr` (ICMP -> TCP -> UDP) -> `besttrace` (ICMP -> TCP) ->
 *   `traceroute` (ICMP concurrent -> TCP -> ICMP -> UDP) -> `tracepath`,
 *   with per-attempt `timeout` bounds and automatic fallback whenever a tool fails or yields only `*`/`???`.
 * - Parses output from all five tools (including NextTrace multi-line, `--raw`, and ASN `*` placeholders)
 *   into a structured [TracerouteSummary].
 */
object ReverseTracerouteCodec {

    private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
    private val TOOL_MARKER_REGEX = Regex("""__ANODE_RTRACE_TOOL__=([a-zA-Z0-9_-]+)""")
    private val MTR_LINE_REGEX = Regex(
        """^\s*(\d+)\.\s*\|--\s+(?:AS\d+\s+)?(.+?)\s+([0-9]+(?:\.[0-9]+)?)%?\s+(\d+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s*$""",
    )
    private val MTR_LOSS_ONLY_REGEX = Regex(
        """^\s*(\d+)\.\s*\|--\s+(?:\?\?\?|\(waiting\s+for\s+reply\))(?:\s+([0-9]+(?:\.[0-9]+)?)%?\s+(\d+).*)?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val TRACEROUTE_HOP_PREFIX_REGEX = Regex("""^\s*(\d+)\s+(.*)$""")
    private val TRACEPATH_HOP_PREFIX_REGEX = Regex("""^\s*(\d+):\s+(.*)$""")
    private val NEXTTRACE_RAW_LINE_REGEX = Regex("""^\s*(\d+)\|([^|]*)\|([^|]*)\|(.*)$""")
    private val RTT_MS_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)\s*ms\b""", RegexOption.IGNORE_CASE)
    private val ASN_TOKEN_REGEX = Regex("""\bAS(\d+)\b""", RegexOption.IGNORE_CASE)
    private val PAREN_IP_REGEX = Regex("""\(([0-9a-fA-F:.]+)\)""")

    private val LOCATION_COUNTRY_HINTS: List<Pair<String, String>> = listOf(
        "香港" to "HK",
        "Hong Kong" to "HK",
        "台湾" to "TW",
        "台灣" to "TW",
        "Taiwan" to "TW",
        "澳门" to "MO",
        "澳門" to "MO",
        "中国" to "CN",
        "中國" to "CN",
        "China" to "CN",
        "美国" to "US",
        "美國" to "US",
        "United States" to "US",
        "日本" to "JP",
        "Japan" to "JP",
        "新加坡" to "SG",
        "Singapore" to "SG",
        "韩国" to "KR",
        "韓國" to "KR",
        "Korea" to "KR",
        "英国" to "GB",
        "英國" to "GB",
        "United Kingdom" to "GB",
        "德国" to "DE",
        "德國" to "DE",
        "Germany" to "DE",
        "荷兰" to "NL",
        "荷蘭" to "NL",
        "Netherlands" to "NL",
        "法国" to "FR",
        "法國" to "FR",
        "France" to "FR",
        "加拿大" to "CA",
        "Canada" to "CA",
        "澳大利亚" to "AU",
        "Australia" to "AU",
        "俄罗斯" to "RU",
        "Russia" to "RU",
    )

    /**
     * Validates that [rawIp] is a genuine public unicast IPv4 or IPv6 literal safe to pass as a
     * single-quoted traceroute target on a remote VPS.
     */
    fun validateClientPublicIp(rawIp: String?): Result<String> {
        val trimmed = rawIp?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("未获取到本机真实公网出口 IP（可能受 VPN 拦截），请关闭 VPN 或刷新出口 IP 后重试"),
            )
        }
        val hasOpenBracket = trimmed.startsWith('[')
        val hasCloseBracket = trimmed.endsWith(']')
        if (hasOpenBracket != hasCloseBracket) {
            return Result.failure(
                IllegalArgumentException("本机出口地址不是有效的公网 IPv4/IPv6 字面量，无法发起反向回程追踪"),
            )
        }
        val clean = if (hasOpenBracket) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        // Strictly reject zone IDs (%), whitespace, quotes, nested brackets, and any non-IP characters
        // BEFORE calling NetBenchTargetParser / BackboneRouteTagger (which strip %<zone>).
        if (clean.isEmpty() || !clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' }) {
            return Result.failure(
                IllegalArgumentException("本机出口地址不是有效的公网 IPv4/IPv6 字面量，无法发起反向回程追踪"),
            )
        }
        if (hasOpenBracket && ':' !in clean) {
            return Result.failure(
                IllegalArgumentException("本机出口地址不是有效的公网 IPv4/IPv6 字面量，无法发起反向回程追踪"),
            )
        }
        val family = NetBenchTargetParser.detectIpFamily(clean)
        if (family == IpFamily.UNKNOWN) {
            return Result.failure(
                IllegalArgumentException("本机出口地址不是有效的公网 IPv4/IPv6 字面量，无法发起反向回程追踪"),
            )
        }
        if (hasOpenBracket && family != IpFamily.IPV6) {
            return Result.failure(
                IllegalArgumentException("本机出口地址不是有效的公网 IPv4/IPv6 字面量，无法发起反向回程追踪"),
            )
        }
        val canonical = when (family) {
            IpFamily.IPV4 -> {
                val octets = NetBenchTargetParser.parseIpv4Octets(clean)
                    ?: return Result.failure(
                        IllegalArgumentException("本机出口地址不是有效的公网 IPv4/IPv6 字面量，无法发起反向回程追踪"),
                    )
                "${octets[0]}.${octets[1]}.${octets[2]}.${octets[3]}"
            }
            IpFamily.IPV6 -> {
                val hextets = NetBenchTargetParser.parseIpv6Hextets(clean)
                    ?: return Result.failure(
                        IllegalArgumentException("本机出口地址不是有效的公网 IPv4/IPv6 字面量，无法发起反向回程追踪"),
                    )
                val isV4Mapped = hextets[0] == 0 &&
                    hextets[1] == 0 &&
                    hextets[2] == 0 &&
                    hextets[3] == 0 &&
                    hextets[4] == 0 &&
                    hextets[5] == 0xFFFF
                if (isV4Mapped) {
                    val b0 = (hextets[6] ushr 8) and 0xFF
                    val b1 = hextets[6] and 0xFF
                    val b2 = (hextets[7] ushr 8) and 0xFF
                    val b3 = hextets[7] and 0xFF
                    "::ffff:$b0.$b1.$b2.$b3"
                } else {
                    NetBenchTargetParser.formatCanonicalIpv6Compressed(hextets)
                }
            }
            IpFamily.UNKNOWN -> {
                return Result.failure(
                    IllegalArgumentException("本机出口地址不是有效的公网 IPv4/IPv6 字面量，无法发起反向回程追踪"),
                )
            }
        }
        // Verify canonical literal via InetAddress (guaranteed pure numeric IP, never triggers DNS)
        val inetParsed = runCatching { java.net.InetAddress.getByName(canonical) }.getOrNull()
        if (inetParsed == null || !canonical.all { it in '0'..'9' || it in 'a'..'f' || it == '.' || it == ':' }) {
            return Result.failure(
                IllegalArgumentException("本机出口地址不是有效的公网 IPv4/IPv6 字面量，无法发起反向回程追踪"),
            )
        }
        val suspect = NetBenchTargetParser.classifySuspectIp(
            ip = canonical,
            targetHost = null,
            checkLoopbackForPublicHost = true,
        )
        if (suspect != SuspectResolvedIpKind.NONE) {
            return Result.failure(
                IllegalArgumentException("检测到本机出口属于${suspect.reasonLabel}，无法从远端 VPS 追踪回程"),
            )
        }
        if (BackboneRouteTagger.isNonPublicIp(canonical)) {
            return Result.failure(
                IllegalArgumentException("本机出口 IP ($canonical) 为内网或 CGNAT 保留地址，远端 VPS 无法反向追踪"),
            )
        }
        return Result.success(canonical)
    }

    /**
     * Builds a safe, non-interactive POSIX shell waterfall trying:
     * `nexttrace` (ICMP -> TCP SYN) -> `mtr` (ICMP -> TCP -> UDP) -> `besttrace` (IPv4) ->
     * `traceroute` (concurrent ICMP -> TCP SYN -> ICMP -> UDP) -> `tracepath`,
     * validating that each step actually produced responding hop IPs before accepting it.
     */
    fun buildShellCommand(clientPublicIp: String): String {
        val validated = validateClientPublicIp(clientPublicIp).getOrThrow()
        val isV6 = NetBenchTargetParser.detectIpFamily(validated) == IpFamily.IPV6
        val v6Flag = if (isV6) " -6" else ""
        val hopIpRegex = if (isV6) {
            """(^|[[:space:]|(]|\[)(([0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F:]*|::[0-9a-fA-F:]+)([[:space:]|)]|\]|$)"""
        } else {
            """(^|[[:space:]|(]|\[)([0-9]{1,3}\.){3}[0-9]{1,3}([[:space:]|)]|\]|$)"""
        }
        val besttraceBlock = if (isV6) {
            ""
        } else {
            """
            if command -v besttrace >/dev/null 2>&1; then
              FOUND_ANY=1
              OUT=$(run_t 10 besttrace -q 2 -w 1 -m 25 "${'$'}TARGET"); RC=${'$'}?
              if ! has_hops "${'$'}OUT"; then
                OUT=$(run_t 10 besttrace -T -q 2 -w 1 -m 25 "${'$'}TARGET"); RC=${'$'}?
              fi
              LAST_TOOL="besttrace"; LAST_OUT="${'$'}OUT"
              if has_hops "${'$'}OUT"; then
                echo "__ANODE_RTRACE_TOOL__=besttrace"
                printf '%s\n' "${'$'}OUT"
                exit 0
              fi
            fi
            """.trimIndent()
        }
        val trBlock = if (isV6) {
            """
            if command -v traceroute6 >/dev/null 2>&1 || command -v traceroute >/dev/null 2>&1; then
              FOUND_ANY=1
              TR_BIN="traceroute"; TR_V6="-6"
              if command -v traceroute6 >/dev/null 2>&1; then TR_BIN="traceroute6"; TR_V6=""; fi
              OUT=$(run_t 10 ${'$'}TR_BIN ${'$'}TR_V6 -I -n -w 1 -q 2 -m 25 -N 16 "${'$'}TARGET"); RC=${'$'}?
              if ! has_hops "${'$'}OUT" && ! is_tmo "${'$'}RC"; then
                OUT=$(run_t 10 ${'$'}TR_BIN ${'$'}TR_V6 -I -n -w 1 -q 2 -m 25 "${'$'}TARGET"); RC=${'$'}?
              fi
              if ! has_hops "${'$'}OUT"; then
                OUT=$(run_t 10 ${'$'}TR_BIN ${'$'}TR_V6 -T -p 443 -n -w 1 -q 2 -m 25 -N 16 "${'$'}TARGET"); RC=${'$'}?
              fi
              if ! has_hops "${'$'}OUT"; then
                OUT=$(run_t 8 ${'$'}TR_BIN ${'$'}TR_V6 -n -w 1 -q 2 -m 25 "${'$'}TARGET"); RC=${'$'}?
              fi
              LAST_TOOL="traceroute"; LAST_OUT="${'$'}OUT"
              if has_hops "${'$'}OUT"; then
                echo "__ANODE_RTRACE_TOOL__=traceroute"
                printf '%s\n' "${'$'}OUT"
                exit 0
              fi
            fi
            """.trimIndent()
        } else {
            """
            if command -v traceroute >/dev/null 2>&1; then
              FOUND_ANY=1
              OUT=$(run_t 10 traceroute -I -n -w 1 -q 2 -m 25 -N 16 "${'$'}TARGET"); RC=${'$'}?
              if ! has_hops "${'$'}OUT" && ! is_tmo "${'$'}RC"; then
                OUT=$(run_t 10 traceroute -I -n -w 1 -q 2 -m 25 "${'$'}TARGET"); RC=${'$'}?
              fi
              if ! has_hops "${'$'}OUT"; then
                OUT=$(run_t 10 traceroute -T -p 443 -n -w 1 -q 2 -m 25 -N 16 "${'$'}TARGET"); RC=${'$'}?
              fi
              if ! has_hops "${'$'}OUT"; then
                OUT=$(run_t 8 traceroute -n -w 1 -q 2 -m 25 "${'$'}TARGET"); RC=${'$'}?
              fi
              LAST_TOOL="traceroute"; LAST_OUT="${'$'}OUT"
              if has_hops "${'$'}OUT"; then
                echo "__ANODE_RTRACE_TOOL__=traceroute"
                printf '%s\n' "${'$'}OUT"
                exit 0
              fi
            fi
            """.trimIndent()
        }
        val tpBlock = if (isV6) {
            """
            if command -v tracepath6 >/dev/null 2>&1 || command -v tracepath >/dev/null 2>&1; then
              FOUND_ANY=1
              TP_BIN="tracepath"; TP_V6="-6"
              if command -v tracepath6 >/dev/null 2>&1; then TP_BIN="tracepath6"; TP_V6=""; fi
              OUT=$(run_t 10 ${'$'}TP_BIN ${'$'}TP_V6 -n -m 25 "${'$'}TARGET"); RC=${'$'}?
              if ! has_hops "${'$'}OUT" && ! is_tmo "${'$'}RC"; then
                OUT=$(run_t 10 ${'$'}TP_BIN ${'$'}TP_V6 -n "${'$'}TARGET"); RC=${'$'}?
              fi
              LAST_TOOL="tracepath"; LAST_OUT="${'$'}OUT"
              if has_hops "${'$'}OUT"; then
                echo "__ANODE_RTRACE_TOOL__=tracepath"
                printf '%s\n' "${'$'}OUT"
                exit 0
              fi
            fi
            """.trimIndent()
        } else {
            """
            if command -v tracepath >/dev/null 2>&1; then
              FOUND_ANY=1
              OUT=$(run_t 10 tracepath -n -m 25 "${'$'}TARGET"); RC=${'$'}?
              if ! has_hops "${'$'}OUT" && ! is_tmo "${'$'}RC"; then
                OUT=$(run_t 10 tracepath -n "${'$'}TARGET"); RC=${'$'}?
              fi
              LAST_TOOL="tracepath"; LAST_OUT="${'$'}OUT"
              if has_hops "${'$'}OUT"; then
                echo "__ANODE_RTRACE_TOOL__=tracepath"
                printf '%s\n' "${'$'}OUT"
                exit 0
              fi
            fi
            """.trimIndent()
        }

        return buildString {
            appendLine("""export PATH="${'$'}PATH:/usr/local/bin:/usr/sbin:/sbin:/usr/bin:/bin:${'$'}HOME/.local/bin"""")
            appendLine("export NO_COLOR=1")
            appendLine("TARGET='$validated'")
            appendLine(
                """
                TIMEOUT_MODE="none"
                if command -v timeout >/dev/null 2>&1; then
                  if timeout 1 true >/dev/null 2>&1; then
                    TIMEOUT_MODE="gnu"
                  elif timeout -t 1 true >/dev/null 2>&1; then
                    TIMEOUT_MODE="busybox"
                  fi
                fi
                run_t() {
                  sec="${'$'}1"; shift
                  if [ "${'$'}TIMEOUT_MODE" = "gnu" ]; then
                    timeout "${'$'}sec" "${'$'}@" 2>&1
                  elif [ "${'$'}TIMEOUT_MODE" = "busybox" ]; then
                    timeout -t "${'$'}sec" "${'$'}@" 2>&1
                  else
                    "${'$'}@" 2>&1
                  fi
                }
                is_tmo() {
                  [ "${'$'}1" -eq 124 ] || [ "${'$'}1" -eq 137 ] || [ "${'$'}1" -eq 143 ]
                }
                has_hops() {
                  printf '%s\n' "${'$'}1" | grep -E '^[[:space:]]*[1-9][0-9]?(\.[[:space:]]*\||:[[:space:]]|\||[[:space:]]+)' | grep -Eq '$hopIpRegex'
                }
                FOUND_ANY=0
                LAST_TOOL=""
                LAST_OUT=""
                if command -v nexttrace >/dev/null 2>&1; then
                  FOUND_ANY=1
                  OUT=$(run_t 12 nexttrace -M --no-color -n -q 2 -m 25 "${'$'}TARGET"); RC=${'$'}?
                  if ! has_hops "${'$'}OUT" && ! is_tmo "${'$'}RC"; then
                    OUT=$(run_t 12 nexttrace -n -q 2 -m 25 "${'$'}TARGET"); RC=${'$'}?
                  fi
                  if ! has_hops "${'$'}OUT"; then
                    OUT=$(run_t 12 nexttrace -T -M --no-color -n -q 2 -m 25 "${'$'}TARGET"); RC=${'$'}?
                    if ! has_hops "${'$'}OUT" && ! is_tmo "${'$'}RC"; then
                      OUT=$(run_t 12 nexttrace -T -n -q 2 -m 25 "${'$'}TARGET"); RC=${'$'}?
                    fi
                  fi
                  LAST_TOOL="nexttrace"; LAST_OUT="${'$'}OUT"
                  if has_hops "${'$'}OUT"; then
                    echo "__ANODE_RTRACE_TOOL__=nexttrace"
                    printf '%s\n' "${'$'}OUT"
                    exit 0
                  fi
                fi
                if command -v mtr >/dev/null 2>&1; then
                  FOUND_ANY=1
                  OUT=$(run_t 12 mtr$v6Flag -n -r -c 3 -w -m 25 -G 2 -i 0.2 "${'$'}TARGET"); RC=${'$'}?
                  if ! has_hops "${'$'}OUT" && ! is_tmo "${'$'}RC"; then
                    OUT=$(run_t 12 mtr$v6Flag -n -r -c 3 -w -m 25 "${'$'}TARGET"); RC=${'$'}?
                  fi
                  if ! has_hops "${'$'}OUT"; then
                    OUT=$(run_t 10 mtr$v6Flag -T -P 443 -n -r -c 3 -w -m 25 -G 2 "${'$'}TARGET"); RC=${'$'}?
                    if ! has_hops "${'$'}OUT" && ! is_tmo "${'$'}RC"; then
                      OUT=$(run_t 10 mtr$v6Flag -T -P 443 -n -r -c 3 -w -m 25 "${'$'}TARGET"); RC=${'$'}?
                    fi
                  fi
                  if ! has_hops "${'$'}OUT"; then
                    OUT=$(run_t 8 mtr$v6Flag -u -n -r -c 2 -w -m 25 -G 2 "${'$'}TARGET"); RC=${'$'}?
                    if ! has_hops "${'$'}OUT" && ! is_tmo "${'$'}RC"; then
                      OUT=$(run_t 8 mtr$v6Flag -u -n -r -c 2 -w -m 25 "${'$'}TARGET"); RC=${'$'}?
                    fi
                  fi
                  LAST_TOOL="mtr"; LAST_OUT="${'$'}OUT"
                  if has_hops "${'$'}OUT"; then
                    echo "__ANODE_RTRACE_TOOL__=mtr"
                    printf '%s\n' "${'$'}OUT"
                    exit 0
                  fi
                fi
                """.trimIndent(),
            )
            if (besttraceBlock.isNotEmpty()) {
                appendLine(besttraceBlock)
            }
            appendLine(trBlock)
            appendLine(tpBlock)
            append(
                """
                if [ "${'$'}FOUND_ANY" -eq 0 ]; then
                  echo "__ANODE_RTRACE_TOOL__=none"
                  exit 127
                fi
                echo "__ANODE_RTRACE_TOOL__=${'$'}{LAST_TOOL:-traceroute}"
                printf '%s\n' "${'$'}LAST_OUT"
                """.trimIndent(),
            )
        }
    }

    /**
     * Parses stdout from `nexttrace`, `mtr`, `besttrace`, `traceroute`, or `tracepath` into a structured [TracerouteSummary].
     */
    fun parseOutput(
        plainOutput: String,
        targetIp: String,
        sourceLabel: String = "",
        testedAtEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
    ): TracerouteSummary {
        val strippedOutput = plainOutput.replace(ANSI_ESCAPE_REGEX, "")
        val cleanIp = targetIp.trim()
        val ipFamily = NetBenchTargetParser.detectIpFamily(cleanIp)
        val peakWindow = BackboneRouteTagger.detectPeakWindow(testedAtEpochMillis, zoneId)
        val toolMarker = TOOL_MARKER_REGEX.find(strippedOutput)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)

        if (toolMarker == "none") {
            return TracerouteSummary(
                direction = TracerouteDirection.REVERSE,
                targetInput = cleanIp,
                targetIp = cleanIp,
                ipFamily = ipFamily,
                sourceLabel = sourceLabel,
                toolName = "none",
                isPeakHourWindow = peakWindow.isPeakHourWindow,
                timeWindowLabel = peakWindow.label,
                testedAtEpochMillis = testedAtEpochMillis,
                rawOutputSnippet = strippedOutput.take(2000),
                errorMessage = "远端 VPS 未安装 nexttrace / mtr / besttrace / traceroute / tracepath，请先安装任意路由追踪工具",
            )
        }

        val lines = strippedOutput.lineSequence()
            .map { it.trimEnd() }
            .filter { !it.contains("__ANODE_RTRACE_TOOL__=") }
            .toList()

        val detectedTool = when {
            toolMarker in setOf("nexttrace", "mtr", "besttrace", "traceroute", "tracepath") -> toolMarker!!
            lines.any { MTR_LINE_REGEX.matches(it) || MTR_LOSS_ONLY_REGEX.matches(it) } -> "mtr"
            lines.any { TRACEPATH_HOP_PREFIX_REGEX.matches(it) } -> "tracepath"
            lines.any {
                it.contains("NextTrace", ignoreCase = true) ||
                    NEXTTRACE_RAW_LINE_REGEX.matches(it) ||
                    ASN_TOKEN_REGEX.containsMatchIn(it)
            } -> "nexttrace"
            else -> "traceroute"
        }

        val rawParsedHops = when (detectedTool) {
            "mtr" -> parseMtrLines(lines, cleanIp)
            "nexttrace", "besttrace" -> parseNextTraceLines(lines, cleanIp)
            "tracepath" -> parseTracepathLines(lines, cleanIp)
            else -> parseTracerouteLines(lines, cleanIp)
        }

        // Trim trailing consecutive timeout-only hops when at least one valid hop was discovered,
        // keeping all intermediate timeout hops intact so CGNAT/firewall tails don't flood the table.
        val lastValidIndex = rawParsedHops.indexOfLast { !it.isTimeoutOnly }
        val parsedHops = if (lastValidIndex >= 0 && lastValidIndex < rawParsedHops.lastIndex) {
            rawParsedHops.subList(0, lastValidIndex + 1)
        } else {
            rawParsedHops
        }

        val taggedHops = parsedHops.map { hop ->
            val priv = BackboneRouteTagger.isPrivateOrCgnat(hop.ip)
            val tags = BackboneRouteTagger.classifyHop(
                ip = hop.ip,
                asn = hop.asn,
                rdns = hop.hostname,
                orgName = hop.orgName,
                ispName = hop.ispName,
            )
            hop.copy(
                isPrivateOrCgnat = priv,
                backboneTags = tags,
            )
        }

        val lossClassified = BackboneRouteTagger.classifyHopLosses(taggedHops)
        val pathClass = BackboneRouteTagger.classifyPath(lossClassified, TracerouteDirection.REVERSE)
        val reached = lossClassified.any { it.isTargetReached }
        val terminalAvg = lossClassified.lastOrNull { it.isTargetReached }?.avgMs
            ?: lossClassified.lastOrNull { !it.isTimeoutOnly }?.avgMs
        val peakHop = lossClassified.filter { it.sent > 0 && it.received > 0 }
            .maxByOrNull { it.lossPercent }
        val hasRealLoss = lossClassified.any { it.lossClassification == HopLossClassification.REAL_PACKET_LOSS }
        val hasRateLimitOnly = !hasRealLoss &&
            lossClassified.any { it.lossClassification == HopLossClassification.INTERMEDIATE_RATE_LIMIT }

        val errorMsg = when {
            lossClassified.isEmpty() -> {
                val joinedLower = lines.joinToString(" ").lowercase(Locale.US)
                when {
                    "permission denied" in joinedLower || "operation not permitted" in joinedLower ->
                        "远端 VPS 权限受限（无 CAP_NET_RAW / Root 权限），未能执行回程路由探测"
                    "network is unreachable" in joinedLower || "no route to host" in joinedLower ->
                        "远端 VPS 到本机出口网络不可达"
                    else -> "未能解析到有效回程跳点数据"
                }
            }
            lossClassified.none { !it.isTimeoutOnly } ->
                "远端回程探测全部超时（本机出口可能位于 NAT/防火墙后或过滤了入站探测包）"
            else -> null
        }

        return TracerouteSummary(
            direction = TracerouteDirection.REVERSE,
            targetInput = cleanIp,
            targetIp = cleanIp,
            ipFamily = ipFamily,
            sourceLabel = sourceLabel,
            toolName = detectedTool,
            hops = lossClassified,
            reachedTarget = reached,
            totalHops = lossClassified.size,
            terminalAvgMs = terminalAvg,
            peakHopLossPercent = peakHop?.lossPercent ?: 0.0,
            peakLossHopNumber = peakHop?.takeIf { it.lossPercent > 0.0 }?.hopNumber,
            hasRealPacketLoss = hasRealLoss,
            hasIntermediateRateLimitOnly = hasRateLimitOnly,
            dominantBackboneTags = pathClass.dominantTags,
            routeSignature = pathClass.routeSignature,
            isPeakHourWindow = peakWindow.isPeakHourWindow,
            timeWindowLabel = peakWindow.label,
            testedAtEpochMillis = testedAtEpochMillis,
            rawOutputSnippet = lines.joinToString("\n").take(3000),
            errorMessage = errorMsg,
        )
    }

    private fun parseMtrLines(lines: List<String>, targetIp: String): List<TracerouteHop> {
        val hops = ArrayList<TracerouteHop>()
        for (line in lines) {
            val match = MTR_LINE_REGEX.matchEntire(line)
            if (match != null) {
                val hopNum = match.groupValues[1].toIntOrNull() ?: continue
                val hostField = match.groupValues[2].trim()
                val lossPct = match.groupValues[3].toDoubleOrNull() ?: 0.0
                val sent = match.groupValues[4].toIntOrNull() ?: 0
                val avg = match.groupValues[6].toDoubleOrNull()
                val best = match.groupValues[7].toDoubleOrNull()
                val worst = match.groupValues[8].toDoubleOrNull()
                val stdev = match.groupValues[9].toDoubleOrNull()
                val isUnknown = hostField == "???" ||
                    hostField == "*" ||
                    hostField.equals("(waiting for reply)", ignoreCase = true)

                val parenIp = PAREN_IP_REGEX.find(hostField)?.groupValues?.getOrNull(1)
                    ?.takeIf { NetBenchTargetParser.detectIpFamily(it) != IpFamily.UNKNOWN }
                val firstToken = hostField.substringBefore(' ').trim()
                val ip = when {
                    isUnknown -> null
                    NetBenchTargetParser.detectIpFamily(hostField) != IpFamily.UNKNOWN -> hostField
                    parenIp != null -> parenIp
                    NetBenchTargetParser.detectIpFamily(firstToken) != IpFamily.UNKNOWN -> firstToken
                    else -> null
                }
                val hostname = when {
                    isUnknown -> null
                    ip == null -> hostField
                    parenIp != null && firstToken != parenIp -> firstToken
                    else -> null
                }
                val received = if (isUnknown || lossPct >= 100.0) {
                    0
                } else {
                    (sent - kotlin.math.round(sent * lossPct / 100.0).toInt()).coerceIn(0, sent)
                }
                hops += TracerouteHop(
                    hopNumber = hopNum,
                    ip = ip,
                    hostname = hostname,
                    sent = sent,
                    received = received,
                    lossPercent = lossPct.coerceIn(0.0, 100.0),
                    minMs = if (received > 0) best else null,
                    avgMs = if (received > 0) avg else null,
                    maxMs = if (received > 0) worst else null,
                    p95Ms = if (received > 0) worst else null,
                    jitterMdevMs = if (received > 0) stdev else null,
                    isTargetReached = ip != null && ipsMatch(ip, targetIp),
                )
                continue
            }
            val lossMatch = MTR_LOSS_ONLY_REGEX.matchEntire(line)
            if (lossMatch != null) {
                val hopNum = lossMatch.groupValues[1].toIntOrNull() ?: continue
                val lossPct = lossMatch.groupValues[2].toDoubleOrNull() ?: 100.0
                val sent = lossMatch.groupValues[3].toIntOrNull() ?: 0
                hops += TracerouteHop(
                    hopNumber = hopNum,
                    ip = null,
                    sent = sent,
                    received = 0,
                    lossPercent = lossPct.coerceIn(0.0, 100.0),
                )
            }
        }
        return hops.sortedBy { it.hopNumber }
    }

    private fun parseNextTraceLines(lines: List<String>, targetIp: String): List<TracerouteHop> {
        val byHop = linkedMapOf<Int, MutableHopAccumulator>()
        var currentHopNum: Int? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("NextTrace", ignoreCase = true) ||
                trimmed.startsWith("[NextTrace", ignoreCase = true) ||
                trimmed.startsWith("traceroute", ignoreCase = true) ||
                trimmed.startsWith("MapTrace", ignoreCase = true) ||
                trimmed.startsWith("BestTrace", ignoreCase = true)
            ) {
                continue
            }
            val rawMatch = NEXTTRACE_RAW_LINE_REGEX.matchEntire(trimmed)
            if (rawMatch != null) {
                val hopNum = rawMatch.groupValues[1].toIntOrNull() ?: continue
                if (hopNum !in 1..64) continue
                currentHopNum = hopNum
                val acc = byHop.getOrPut(hopNum) { MutableHopAccumulator(hopNum) }
                consumeNextTraceRawLine(acc, trimmed.split('|'), targetIp)
                continue
            }
            val hopPrefix = TRACEROUTE_HOP_PREFIX_REGEX.matchEntire(line)
            if (hopPrefix != null) {
                val hopNum = hopPrefix.groupValues[1].toIntOrNull() ?: continue
                if (hopNum !in 1..64) continue
                currentHopNum = hopNum
                val acc = byHop.getOrPut(hopNum) { MutableHopAccumulator(hopNum) }
                consumeNextTraceSegment(acc, hopPrefix.groupValues[2], targetIp)
            } else if (currentHopNum != null && looksLikeNextTraceContinuation(line)) {
                val acc = byHop.getValue(currentHopNum)
                consumeNextTraceSegment(acc, line, targetIp)
            }
        }
        return byHop.values.map { it.toHop(targetIp) }.sortedBy { it.hopNumber }
    }

    private fun consumeNextTraceRawLine(
        acc: MutableHopAccumulator,
        fields: List<String>,
        targetIp: String,
    ) {
        // Format: hop|ip|rtt|hostname|asn|country|prov|city|district|owner
        val ipField = fields.getOrNull(1)?.trim().orEmpty()
        if (ipField.isEmpty() || ipField == "*") {
            acc.timeouts += 1
            return
        }
        if (NetBenchTargetParser.detectIpFamily(ipField) != IpFamily.UNKNOWN && acc.ip == null) {
            acc.ip = ipField
            if (ipsMatch(ipField, targetIp)) acc.reached = true
        }
        val rttRaw = fields.getOrNull(2)?.trim()?.removeSuffix("ms")?.trim()?.toDoubleOrNull()
        if (rttRaw != null && rttRaw.isFinite() && rttRaw >= 0.0) {
            acc.rtts += rttRaw
        }
        val hostField = fields.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() && it != "*" }
        if (hostField != null && acc.hostname == null) {
            acc.hostname = hostField
        }
        val asnField = fields.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() && it != "*" }
        if (asnField != null && acc.asn == null) {
            acc.asn = if (asnField.startsWith("AS", ignoreCase = true)) asnField.uppercase(Locale.US) else "AS$asnField"
        }
        val countryField = fields.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() && it != "*" }
        if (countryField != null && acc.countryCode == null) {
            acc.countryCode = inferCountryCode(countryField)
            acc.countryName = countryField
        }
        val provField = fields.getOrNull(6)?.trim()?.takeIf { it.isNotEmpty() && it != "*" }
        if (provField != null && acc.regionName == null) acc.regionName = provField
        val cityField = fields.getOrNull(7)?.trim()?.takeIf { it.isNotEmpty() && it != "*" }
        if (cityField != null && acc.cityName == null) acc.cityName = cityField
        val ownerField = fields.getOrNull(9)?.trim()?.takeIf { it.isNotEmpty() && it != "*" }
        if (ownerField != null && acc.orgHint == null) acc.orgHint = ownerField
    }

    private fun looksLikeNextTraceContinuation(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "*" || trimmed.startsWith("* ") || trimmed.startsWith("*/")) return true
        val tokens = trimmed.split(Regex("""\s+"""))
        return tokens.firstOrNull()?.let { NetBenchTargetParser.detectIpFamily(it) != IpFamily.UNKNOWN } == true ||
            RTT_MS_REGEX.containsMatchIn(trimmed)
    }

    private fun consumeNextTraceSegment(acc: MutableHopAccumulator, segment: String, targetIp: String) {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return
        val tokens = trimmed.split(Regex("""\s+"""))
        if (tokens.all { it == "*" || it == "/" }) {
            val starCount = tokens.count { it == "*" }.coerceAtLeast(1)
            acc.timeouts += starCount
            return
        }
        var ipFound: String? = null
        var ipTokenIndex = -1
        var asnFound: String? = null
        val orgParts = mutableListOf<String>()
        val hasRttOrSlash = RTT_MS_REGEX.containsMatchIn(trimmed) || tokens.any { it == "/" }

        tokens.forEachIndexed { idx, tok ->
            val cleanTok = tok.trim(',', '(', ')', '[', ']')
            if (cleanTok.isEmpty()) return@forEachIndexed
            if (ipFound == null && NetBenchTargetParser.detectIpFamily(cleanTok) != IpFamily.UNKNOWN) {
                ipFound = cleanTok
                ipTokenIndex = idx
                return@forEachIndexed
            }
            if (asnFound == null) {
                val asnMatch = ASN_TOKEN_REGEX.matchEntire(cleanTok)
                if (asnMatch != null) {
                    asnFound = "AS${asnMatch.groupValues[1]}"
                    return@forEachIndexed
                }
            }
            if (cleanTok == "*" ||
                cleanTok == "/" ||
                cleanTok.equals("ms", ignoreCase = true) ||
                cleanTok.toDoubleOrNull() != null ||
                RTT_MS_REGEX.matches(cleanTok)
            ) {
                return@forEachIndexed
            }
            if (!cleanTok.startsWith("[") && !cleanTok.equals("RFC1918", ignoreCase = true)) {
                orgParts += cleanTok
            }
        }

        if (ipFound != null && acc.ip == null) {
            acc.ip = ipFound
        }
        if (asnFound != null && acc.asn == null) {
            acc.asn = asnFound
        }
        if (orgParts.isNotEmpty() && acc.orgHint == null) {
            val joined = orgParts.joinToString(" ").trim()
            if (joined.isNotEmpty() && !joined.equals("ms", ignoreCase = true)) {
                acc.orgHint = joined
            }
        }
        if (acc.countryCode == null) {
            inferCountryCode(trimmed)?.let { cc -> acc.countryCode = cc }
        }

        val rtts = RTT_MS_REGEX.findAll(trimmed).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        acc.rtts += rtts

        // NextTrace prints `*` in the ASN column for private / no-ASN hops (e.g. `1  10.0.0.1  *  RFC1918`).
        // Count `*` as a packet timeout ONLY when it appears in a probe/RTT context (has `ms` or `/`),
        // or appears BEFORE the hop IP (e.g. `2  * * 59.43.138.1`), not as a lone post-IP ASN placeholder.
        val starTimeouts = tokens.withIndex().count { (idx, tok) ->
            if (tok != "*") return@count false
            when {
                hasRttOrSlash -> true
                ipTokenIndex < 0 -> true
                idx < ipTokenIndex -> true
                else -> false
            }
        }
        acc.timeouts += starTimeouts
        if (ipFound != null && ipsMatch(ipFound, targetIp)) {
            acc.reached = true
        }
    }

    private fun parseTracerouteLines(lines: List<String>, targetIp: String): List<TracerouteHop> {
        val byHop = linkedMapOf<Int, MutableHopAccumulator>()
        var currentHopNum: Int? = null
        for (line in lines) {
            if (line.trimStart().startsWith("traceroute", ignoreCase = true)) continue
            val hopPrefix = TRACEROUTE_HOP_PREFIX_REGEX.matchEntire(line)
            if (hopPrefix != null) {
                val hopNum = hopPrefix.groupValues[1].toIntOrNull() ?: continue
                if (hopNum !in 1..64) continue
                currentHopNum = hopNum
                val acc = byHop.getOrPut(hopNum) { MutableHopAccumulator(hopNum) }
                consumeTracerouteSegment(acc, hopPrefix.groupValues[2], targetIp)
            } else if (currentHopNum != null && line.isNotBlank()) {
                val firstToken = line.trim().split(Regex("""\s+""")).firstOrNull()?.trim('(', ')').orEmpty()
                if (firstToken == "*" ||
                    NetBenchTargetParser.detectIpFamily(firstToken) != IpFamily.UNKNOWN ||
                    PAREN_IP_REGEX.containsMatchIn(line)
                ) {
                    consumeTracerouteSegment(byHop.getValue(currentHopNum), line, targetIp)
                }
            }
        }
        return byHop.values.map { it.toHop(targetIp) }.sortedBy { it.hopNumber }
    }

    private fun consumeTracerouteSegment(acc: MutableHopAccumulator, segment: String, targetIp: String) {
        val tokens = segment.trim().split(Regex("""\s+"""))
        for (tok in tokens) {
            val clean = tok.trim('(', ')', '[', ']', ',')
            if (clean == "*") {
                acc.timeouts++
            } else if (acc.ip == null && NetBenchTargetParser.detectIpFamily(clean) != IpFamily.UNKNOWN) {
                acc.ip = clean
                if (ipsMatch(clean, targetIp)) acc.reached = true
            }
        }
        val rtts = RTT_MS_REGEX.findAll(segment).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        acc.rtts += rtts
    }

    private fun parseTracepathLines(lines: List<String>, targetIp: String): List<TracerouteHop> {
        val byHop = linkedMapOf<Int, MutableHopAccumulator>()
        for (line in lines) {
            val match = TRACEPATH_HOP_PREFIX_REGEX.matchEntire(line) ?: continue
            val hopNum = match.groupValues[1].toIntOrNull() ?: continue
            if (hopNum !in 1..64) continue
            val rest = match.groupValues[2].trim()
            val acc = byHop.getOrPut(hopNum) { MutableHopAccumulator(hopNum) }
            if (rest.equals("no reply", ignoreCase = true)) {
                acc.timeouts++
                continue
            }
            val tokens = rest.split(Regex("""\s+"""))
            var extractedIp: String? = null
            for (tok in tokens) {
                val clean = tok.trim('(', ')', '[', ']', ',')
                if (clean.isEmpty()) continue
                if (NetBenchTargetParser.detectIpFamily(clean) != IpFamily.UNKNOWN) {
                    extractedIp = clean
                    break
                }
            }
            if (extractedIp != null) {
                if (acc.ip == null) acc.ip = extractedIp
                if (ipsMatch(extractedIp, targetIp)) acc.reached = true
            }
            val firstClean = tokens.firstOrNull()?.trim('(', ')', '[', ']', ',').orEmpty()
            if (firstClean.isNotEmpty() &&
                firstClean != extractedIp &&
                !RTT_MS_REGEX.matches(firstClean) &&
                !firstClean.equals("pmtu", ignoreCase = true) &&
                !firstClean.equals("reached", ignoreCase = true) &&
                !firstClean.equals("asymm", ignoreCase = true) &&
                !firstClean.equals("no", ignoreCase = true) &&
                (extractedIp != null || NetBenchTargetParser.isValidHostname(firstClean))
            ) {
                if (acc.hostname == null) acc.hostname = firstClean
            }
            val rtts = RTT_MS_REGEX.findAll(rest).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
            acc.rtts += rtts
        }
        return byHop.values.map { it.toHop(targetIp) }.sortedBy { it.hopNumber }
    }

    private fun inferCountryCode(text: String): String? {
        for ((needle, cc) in LOCATION_COUNTRY_HINTS) {
            if (text.contains(needle, ignoreCase = true)) return cc
        }
        return null
    }

    private class MutableHopAccumulator(val hopNumber: Int) {
        var ip: String? = null
        var hostname: String? = null
        var asn: String? = null
        var orgHint: String? = null
        var countryCode: String? = null
        var countryName: String? = null
        var regionName: String? = null
        var cityName: String? = null
        val rtts = mutableListOf<Double>()
        var timeouts: Int = 0
        var reached: Boolean = false

        fun toHop(targetIp: String): TracerouteHop {
            val received = if (rtts.isNotEmpty()) rtts.size else if (ip != null && timeouts == 0) 1 else 0
            val sent = (received + timeouts).let { total ->
                if (total == 0 && ip == null) 3 else total.coerceAtLeast(received)
            }
            val lossPct = if (sent > 0) ((sent - received).toDouble() / sent.toDouble()) * 100.0 else 100.0
            val min = rtts.minOrNull()
            val max = rtts.maxOrNull()
            val avg = if (rtts.isNotEmpty()) rtts.sum() / rtts.size else null
            val p95 = computePercentile(rtts, 95.0)
            val mdev = computeStdDev(rtts)
            val isTarget = reached || (ip != null && ipsMatch(ip!!, targetIp))
            return TracerouteHop(
                hopNumber = hopNumber,
                ip = ip,
                hostname = hostname,
                asn = asn,
                orgName = orgHint,
                countryCode = countryCode,
                countryName = countryName,
                regionName = regionName,
                cityName = cityName,
                sent = sent,
                received = received,
                lossPercent = lossPct.coerceIn(0.0, 100.0),
                minMs = min,
                avgMs = avg,
                maxMs = max,
                p95Ms = p95,
                jitterMdevMs = mdev,
                isTargetReached = isTarget,
            )
        }
    }

    internal fun computePercentile(values: List<Double>, percentile: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (ceil((percentile / 100.0) * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    internal fun computeStdDev(values: List<Double>): Double? {
        if (values.size < 2) return if (values.size == 1) 0.0 else null
        val mean = values.sum() / values.size.toDouble()
        val variance = values.sumOf { v ->
            val d = v - mean
            d * d
        } / values.size.toDouble()
        return sqrt(variance.coerceAtLeast(0.0))
    }

    private fun ipsMatch(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val formsA = NetBenchTargetParser.allCanonicalIpForms(a)
        val formsB = NetBenchTargetParser.allCanonicalIpForms(b)
        return formsA.any { it in formsB }
    }
}
