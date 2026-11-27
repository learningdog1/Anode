package com.nodeseek.app.domain.netbench

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Pure Kotlin backbone classifier, CN2 GIA vs GT path adjudicator, hop packet-loss classifier,
 * and Asia/Shanghai peak-hour window detector.
 */
object BackboneRouteTagger {

    data class PeakWindowInfo(
        val isPeakHourWindow: Boolean,
        val label: String,
    )

    data class PathRouteClassification(
        val dominantTags: List<BackboneRouteTag>,
        val routeSignature: String,
        val downgradedCn2ToGt: Boolean = false,
    )

    private val CMIN2_IPV4_CIDRS: List<Pair<Int, Int>> = listOf(
        cidr("223.119.26.0", 23),
        cidr("223.119.28.0", 23),
        cidr("223.119.74.0", 23),
        cidr("223.119.88.0", 23),
        cidr("223.118.32.0", 24),
        cidr("223.119.8.0", 22),
        cidr("223.119.12.0", 22),
    )

    private val PRIVATE_AND_CGNAT_V4_CIDRS: List<Pair<Int, Int>> = listOf(
        cidr("10.0.0.0", 8),
        cidr("172.16.0.0", 12),
        cidr("192.168.0.0", 16),
        cidr("100.64.0.0", 10),
        cidr("127.0.0.0", 8),
        cidr("169.254.0.0", 16),
        cidr("0.0.0.0", 8),
    )

    private val NON_ROUTABLE_SPECIAL_V4_CIDRS: List<Pair<Int, Int>> = listOf(
        cidr("198.18.0.0", 15),
        cidr("224.0.0.0", 4),
        cidr("240.0.0.0", 4),
    )

    fun isPrivateOrCgnat(ip: String?): Boolean {
        val clean = ip?.trim()?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%') ?: return false
        if (clean.isEmpty()) return false
        val v4 = parseIpv4ToIntOrNull(clean)
        if (v4 != null) {
            return PRIVATE_AND_CGNAT_V4_CIDRS.any { (net, mask) -> (v4 and mask) == net }
        }
        val hextets = NetBenchTargetParser.parseIpv6Hextets(clean) ?: return false
        unwrapIpv4MappedToInt(hextets)?.let { mappedV4 ->
            return PRIVATE_AND_CGNAT_V4_CIDRS.any { (net, mask) -> (mappedV4 and mask) == net }
        }
        val first = hextets[0]
        // fe80::/10 link-local
        if ((first and 0xFFC0) == 0xFE80) return true
        // fc00::/7 ULA
        if ((first and 0xFE00) == 0xFC00) return true
        // ::1 loopback or :: unspecified
        if (hextets.dropLast(1).all { it == 0 } && (hextets[7] == 0 || hextets[7] == 1)) return true
        return false
    }

    fun isNonPublicIp(ip: String?): Boolean {
        if (isPrivateOrCgnat(ip)) return true
        val clean = ip?.trim()?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%') ?: return true
        val v4 = parseIpv4ToIntOrNull(clean)
        if (v4 != null) {
            return isNonPublicIpv4Int(v4)
        }
        val hextets = NetBenchTargetParser.parseIpv6Hextets(clean) ?: return true
        unwrapIpv4MappedToInt(hextets)?.let { mappedV4 ->
            return isNonPublicIpv4Int(mappedV4)
        }
        // ff00::/8 multicast
        if ((hextets[0] and 0xFF00) == 0xFF00) return true
        // Deprecated IPv4-compatible ::0.0.0.0/96 (also covers :: and ::1)
        if (hextets[0] == 0 && hextets[1] == 0 && hextets[2] == 0 &&
            hextets[3] == 0 && hextets[4] == 0 && hextets[5] == 0
        ) {
            return true
        }
        // RFC 6052 NAT64 Well-Known Prefix 64:ff9b::/96
        if (hextets[0] == 0x0064 && hextets[1] == 0xFF9B &&
            hextets[2] == 0 && hextets[3] == 0 && hextets[4] == 0 && hextets[5] == 0
        ) {
            return true
        }
        // RFC 8215 NAT64 Local-Use Prefix 64:ff9b:1::/48
        if (hextets[0] == 0x0064 && hextets[1] == 0xFF9B && hextets[2] == 0x0001) {
            return true
        }
        // RFC 2765 / RFC 6890 SIIT IPv4-translated ::ffff:0:0:0/96
        if (hextets[0] == 0 && hextets[1] == 0 && hextets[2] == 0 &&
            hextets[3] == 0 && hextets[4] == 0xFFFF && hextets[5] == 0
        ) {
            return true
        }
        // RFC 6666 Discard-Only 100::/64
        if (hextets[0] == 0x0100 && hextets[1] == 0 && hextets[2] == 0 && hextets[3] == 0) {
            return true
        }
        // RFC 3849 Documentation 2001:db8::/32
        if (hextets[0] == 0x2001 && hextets[1] == 0x0DB8) return true
        // Teredo 2001::/32
        if (hextets[0] == 0x2001 && hextets[1] == 0x0000) return true
        // Benchmarking 2001:2::/48
        if (hextets[0] == 0x2001 && hextets[1] == 0x0002 && hextets[2] == 0x0000) return true
        // ORCHID (2001:10::/28) and ORCHIDv2 (2001:20::/28)
        if (hextets[0] == 0x2001 &&
            ((hextets[1] and 0xFFF0) == 0x0010 || (hextets[1] and 0xFFF0) == 0x0020)
        ) {
            return true
        }
        // 6to4 2002::/16 with private or non-public embedded IPv4 (or deprecated 192.88.99.0/24 relay)
        if (hextets[0] == 0x2002) {
            val embeddedV4 = (hextets[1] shl 16) or hextets[2]
            if (isNonPublicIpv4Int(embeddedV4) || matchesCidr(embeddedV4, "192.88.99.0", 24)) {
                return true
            }
        }
        return false
    }

    private fun isNonPublicIpv4Int(v4: Int): Boolean =
        PRIVATE_AND_CGNAT_V4_CIDRS.any { (net, mask) -> (v4 and mask) == net } ||
            NON_ROUTABLE_SPECIAL_V4_CIDRS.any { (net, mask) -> (v4 and mask) == net }

    private fun unwrapIpv4MappedToInt(hextets: IntArray): Int? {
        if (hextets.size != 8) return null
        val isV4Mapped = hextets[0] == 0 &&
            hextets[1] == 0 &&
            hextets[2] == 0 &&
            hextets[3] == 0 &&
            hextets[4] == 0 &&
            hextets[5] == 0xFFFF
        if (!isV4Mapped) return null
        return (hextets[6] shl 16) or hextets[7]
    }

    fun classifyHop(
        ip: String?,
        asn: String? = null,
        rdns: String? = null,
        orgName: String? = null,
        ispName: String? = null,
    ): List<BackboneRouteTag> {
        if (isPrivateOrCgnat(ip)) return emptyList()

        val cleanIp = ip?.trim()?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%').orEmpty()
        val normAsn = IpClassificationEngine.normalizeAsn(asn)
        val rdnsLower = rdns?.trim()?.lowercase(Locale.US).orEmpty()
        val orgLower = listOfNotNull(orgName, ispName).joinToString(" ").lowercase(Locale.US)
        val metaText = "$rdnsLower $orgLower".trim()
        val v4 = parseIpv4ToIntOrNull(cleanIp)

        val tags = LinkedHashSet<BackboneRouteTag>()

        // 1. China Telecom CN2 (GIA / GT)
        val explicitCn2Gt = "cn2 gt" in metaText || "cn2-gt" in metaText
        val is5943 = v4 != null && matchesCidr(v4, "59.43.0.0", 16)
        val isCn2Meta = "59.43." in metaText || "ctgnet" in metaText ||
            "china telecom next" in metaText ||
            (Regex("""\bcn2\b""").containsMatchIn(metaText) && !explicitCn2Gt)
        if (explicitCn2Gt) {
            tags += BackboneRouteTag.CN2_GT
        } else if (normAsn == "AS4809" || normAsn == "AS23764" || is5943 || isCn2Meta) {
            tags += BackboneRouteTag.CN2_GIA
        }

        // 2. China Unicom AS9929 (CUII)
        val is9929Prefix = v4 != null && (
            matchesCidr(v4, "218.105.0.0", 16) ||
                matchesCidr(v4, "210.51.0.0", 16)
            )
        val is9929Meta = "as9929" in metaText || "9929" in rdnsLower || "cuii" in metaText ||
            "cncnet" in metaText || "unicom-a" in metaText || "china unicom industrial" in metaText
        val is10099As9929 = normAsn == "AS10099" && (is9929Prefix || is9929Meta)
        if (normAsn == "AS9929" || is10099As9929 || is9929Prefix || is9929Meta) {
            tags += BackboneRouteTag.CU_9929
        }

        // 3. China Mobile CMIN2 (AS58807)
        val isCmin2Prefix = v4 != null && CMIN2_IPV4_CIDRS.any { (net, mask) -> (v4 and mask) == net }
        val isCmin2Meta = "cmin2" in metaText || "as58807" in metaText ||
            ("n2." in rdnsLower && ("cmi" in rdnsLower || "chinamobile" in rdnsLower))
        if (normAsn == "AS58807" || isCmin2Prefix || isCmin2Meta) {
            tags += BackboneRouteTag.CMIN2
        }

        // 4. China Unicom 4837
        val is4837Prefix = v4 != null && matchesCidr(v4, "219.158.0.0", 16)
        val is4837Meta = "as4837" in metaText || "china169" in metaText || "china unicom backbone" in metaText
        if (normAsn == "AS4837" || is4837Prefix || is4837Meta) {
            tags += BackboneRouteTag.CU_4837
        }

        // 5. IIJ (Japan)
        val isIijPrefix = v4 != null && (
            matchesCidr(v4, "210.130.0.0", 16) ||
                matchesCidr(v4, "210.138.0.0", 16) ||
                matchesCidr(v4, "202.232.0.0", 16) ||
                matchesCidr(v4, "58.138.0.0", 16)
            )
        val isIijMeta = ".iij.net" in rdnsLower || "internet initiative japan" in orgLower ||
            Regex("""\biij\b""").containsMatchIn(orgLower)
        if (normAsn == "AS2497" || isIijPrefix || isIijMeta) {
            tags += BackboneRouteTag.IIJ
        }

        // 6. SoftBank (Japan)
        val isSbPrefix = v4 != null && (
            matchesCidr(v4, "210.175.0.0", 16) ||
                matchesCidr(v4, "219.111.0.0", 16) ||
                matchesCidr(v4, "202.238.0.0", 16)
            )
        val isSbMeta = ".softbank.net" in rdnsLower || ".bbtec.net" in rdnsLower ||
            ".softbank.ne.jp" in rdnsLower || "softbank" in orgLower
        if (normAsn == "AS17676" || isSbPrefix || isSbMeta) {
            tags += BackboneRouteTag.SOFTBANK
        }

        // 7. China Telecom 163 Backbone
        val is163Prefix = v4 != null && matchesCidr(v4, "202.97.0.0", 16)
        val is163Meta = "202.97." in metaText || "chinanet" in metaText || "china163" in metaText
        if (normAsn == "AS4134" || is163Prefix || is163Meta) {
            if (BackboneRouteTag.CN2_GIA !in tags && BackboneRouteTag.CN2_GT !in tags) {
                tags += BackboneRouteTag.CT_163
            }
        }

        // 8. China Mobile CMI (AS58453) - only if not CMIN2
        val isCmiPrefix = v4 != null && (
            matchesCidr(v4, "223.120.0.0", 16) ||
                matchesCidr(v4, "221.183.0.0", 16)
            )
        val isCmiMeta = ".cmi.chinamobile" in rdnsLower || "china mobile international" in orgLower
        if (BackboneRouteTag.CMIN2 !in tags && (normAsn == "AS58453" || isCmiPrefix || isCmiMeta)) {
            tags += BackboneRouteTag.CMI
        }

        // 9. China Mobile CMNET (AS9808)
        val isCmnetPrefix = v4 != null && matchesCidr(v4, "221.176.0.0", 13)
        val isCmnetMeta = "cmnet" in metaText || "china mobile communications" in orgLower
        if (BackboneRouteTag.CMIN2 !in tags && (normAsn == "AS9808" || isCmnetPrefix || isCmnetMeta)) {
            tags += BackboneRouteTag.CMNET
        }

        // 10. Global Tier-1 / CDN
        if (normAsn == "AS2914" || ".gin.ntt.net" in rdnsLower || "ntt communications" in orgLower || "ntt america" in orgLower) {
            tags += BackboneRouteTag.NTT
        }
        if (normAsn == "AS3491" || ".pccwbtn.net" in rdnsLower || "pccw global" in orgLower) {
            tags += BackboneRouteTag.PCCW
        }
        if (normAsn == "AS3356" || ".level3.net" in rdnsLower || ".lumen." in rdnsLower || "lumen" in orgLower || "level 3" in orgLower) {
            tags += BackboneRouteTag.LUMEN
        }
        if (normAsn == "AS1299" || ".twelve99.net" in rdnsLower || ".telia." in rdnsLower || "arelion" in orgLower || "telia" in orgLower) {
            tags += BackboneRouteTag.TELIA
        }
        if (normAsn == "AS174" || ".cogentco.com" in rdnsLower || "cogent" in orgLower) {
            tags += BackboneRouteTag.COGENT
        }
        if (normAsn == "AS6939" || ".he.net" in rdnsLower || "hurricane electric" in orgLower) {
            tags += BackboneRouteTag.HE
        }
        if (normAsn == "AS13335" || "cloudflare" in metaText) {
            tags += BackboneRouteTag.CLOUDFLARE
        }

        return tags.toList()
    }

    /**
     * Adjudicates path-level backbone tags, distinguishing true `CN2 GIA` (no overseas `202.97.*`/AS4134
     * transit) from `CN2 GT` (overseas `202.97.*` transit co-occurring with `59.43.*`).
     */
    fun classifyPath(
        hops: List<TracerouteHop>,
        direction: TracerouteDirection,
    ): PathRouteClassification {
        val firstCn2GiaIdx = hops.indexOfFirst { BackboneRouteTag.CN2_GIA in it.backboneTags }
        val lastCn2GiaIdx = hops.indexOfLast { BackboneRouteTag.CN2_GIA in it.backboneTags }

        var downgradeCn2ToGt = false
        if (firstCn2GiaIdx >= 0) {
            for (idx in hops.indices) {
                val hop = hops[idx]
                if (BackboneRouteTag.CT_163 !in hop.backboneTags) continue
                val normCc = IpClassificationEngine.normalizeCountryCode(hop.countryCode)
                val isOverseas163 = normCc != null && normCc != "CN"
                val crossesBorderByOrder = when (direction) {
                    TracerouteDirection.FORWARD -> idx > firstCn2GiaIdx
                    TracerouteDirection.REVERSE -> idx < lastCn2GiaIdx
                }
                if (isOverseas163 || crossesBorderByOrder) {
                    downgradeCn2ToGt = true
                    break
                }
            }
        }
        if (hops.any { BackboneRouteTag.CN2_GT in it.backboneTags }) {
            downgradeCn2ToGt = true
        }

        val orderedDistinct = LinkedHashSet<BackboneRouteTag>()
        for (hop in hops) {
            for (rawTag in hop.backboneTags) {
                val effective = if (rawTag == BackboneRouteTag.CN2_GIA && downgradeCn2ToGt) {
                    BackboneRouteTag.CN2_GT
                } else {
                    rawTag
                }
                orderedDistinct += effective
            }
        }

        // Remove redundant CT_163 if it only represented CN2 GT downgrade or domestic access alongside CN2_GT
        val dominant = orderedDistinct.toList()
        val signature = if (dominant.isEmpty()) {
            "常规直连 / 未命中特定骨干"
        } else {
            dominant.joinToString(" -> ") { it.displayLabel }
        }
        return PathRouteClassification(
            dominantTags = dominant,
            routeSignature = signature,
            downgradedCn2ToGt = downgradeCn2ToGt,
        )
    }

    /**
     * Classifies each hop's packet loss:
     * - `received == 0` -> `TIMEOUT_NO_REPLY`
     * - `lossPercent == 0.0` -> `ZERO_LOSS`
     * - `lossPercent > 0.0` with ANY downstream hop having strictly lower `lossPercent` (and `received > 0`)
     *   -> `INTERMEDIATE_RATE_LIMIT` (benign control-plane ICMP rate limiting)
     * - Otherwise -> `REAL_PACKET_LOSS`
     */
    fun classifyHopLosses(hops: List<TracerouteHop>): List<TracerouteHop> {
        if (hops.isEmpty()) return emptyList()
        return hops.mapIndexed { idx, hop ->
            val lossClass = when {
                hop.sent <= 0 || hop.received <= 0 -> HopLossClassification.TIMEOUT_NO_REPLY
                hop.lossPercent <= 0.0 -> HopLossClassification.ZERO_LOSS
                else -> {
                    val hasCleanerDownstream = hops.drop(idx + 1).any { downstream ->
                        downstream.received > 0 && downstream.lossPercent < hop.lossPercent
                    }
                    if (hasCleanerDownstream) {
                        HopLossClassification.INTERMEDIATE_RATE_LIMIT
                    } else {
                        HopLossClassification.REAL_PACKET_LOSS
                    }
                }
            }
            hop.copy(lossClassification = lossClass)
        }
    }

    fun detectPeakWindow(
        epochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = SHANGHAI_ZONE,
    ): PeakWindowInfo {
        val hour = Instant.ofEpochMilli(epochMillis).atZone(zoneId).hour
        val isPeak = hour in 20..23
        return PeakWindowInfo(
            isPeakHourWindow = isPeak,
            label = if (isPeak) "晚高峰 (20:00-24:00)" else "非高峰时段",
        )
    }

    private fun cidr(ip: String, prefixLen: Int): Pair<Int, Int> {
        val addr = parseIpv4ToIntOrNull(ip) ?: error("Invalid IPv4 CIDR base: $ip")
        val mask = if (prefixLen == 0) 0 else (-1 shl (32 - prefixLen))
        return (addr and mask) to mask
    }

    private fun matchesCidr(ipInt: Int, networkIp: String, prefixLen: Int): Boolean {
        val (net, mask) = cidr(networkIp, prefixLen)
        return (ipInt and mask) == net
    }

    internal fun parseIpv4ToIntOrNull(ip: String): Int? {
        if (!NetBenchTargetParser.isIpv4Literal(ip)) return null
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var acc = 0
        for (p in parts) {
            val octet = p.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            acc = (acc shl 8) or octet
        }
        return acc
    }

    private val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
}
