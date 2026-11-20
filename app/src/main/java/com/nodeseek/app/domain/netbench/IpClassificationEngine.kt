package com.nodeseek.app.domain.netbench

import java.util.Locale

/**
 * Pure Kotlin classification logic for IP Native-vs-Broadcast adjudication, usage attributes,
 * and local Chinese/overseas client carrier detection.
 */
object IpClassificationEngine {

    data class OriginClassification(
        val originType: IpOriginType,
        val evidence: String,
    )

    private val CHINA_TELECOM_ASNS = setOf(
        "AS4134", "AS4809", "AS4812", "AS4813", "AS4816",
        "AS23764", "AS36678", "AS140061", "AS141998",
    )

    private val CHINA_UNICOM_ASNS = setOf(
        "AS4837", "AS9929", "AS10099", "AS17621", "AS17622",
        "AS17623", "AS17816", "AS4808", "AS134542", "AS134543",
    )

    private val CHINA_MOBILE_ASNS = setOf(
        "AS9808", "AS24400", "AS56040", "AS56041", "AS56042",
        "AS56044", "AS56046", "AS56047", "AS56048", "AS58453",
        "AS58807", "AS139080",
    )

    private val CHINA_BROADNET_ASNS = setOf(
        "AS7641", "AS139774", "AS141739", "AS24139",
    )

    private val CHINA_EDU_ASNS = setOf(
        "AS4538", "AS7497", "AS23910", "AS24348",
    )

    private val HOSTING_KEYWORDS = listOf(
        "amazon", "aws", "google cloud", "gcp", "microsoft", "azure",
        "digitalocean", "vultr", "choopa", "constant company", "linode",
        "akamai", "hetzner", "ovh", "oracle", "alibaba", "aliyun",
        "tencent", "huawei cloud", "dmit", "bandwagon", "it7", "racknerd",
        "cloudcone", "greencloud", "frantech", "buyvm", "misaka", "xtom",
        "datacamp", "m247", "leaseweb", "cogent", "zenlayer", "cloudflare",
        "fastly", "scaleway", "contabo", "netcup", "ionos", "kamatera",
        "upcloud", "hosthatch", "claw", "colocrossing", "psychz", "quadranet",
        "sharktech", "gcore", "cdn77", "equinix", "packet", "hosting",
        "datacenter", "data center", "server", "vps", "cloud", "colocation",
    )

    private val ISP_KEYWORDS = listOf(
        "china telecom", "chinanet", "china unicom", "china169", "china mobile",
        "cmnet", "broadnet", "comcast", "xfinity", "at&t", "verizon", "charter",
        "spectrum", "cox communications", "t-mobile", "ntt communications", "ocn",
        "kddi", "softbank", "nuro", "so-net", "hkt", "pccw", "hkbn", "hgc",
        "chunghwa", "hinet", "taiwan fixed", "far eastone", "singtel", "starhub",
        "m1 limited", "korea telecom", "sk broadband", "lg uplus", "deutsche telekom",
        "vodafone", "orange", "telefonica", "british telecommunications", "bt ",
        "virgin media", "sky uk", "free sas", "sfr", "rogers", "bell canada",
        "telus", "telstra", "optus", "broadband", "residential", "telecom",
        "communications", "mobile", "fiber", "fibre", "cable",
    )

    private val COUNTRY_NAMES_ZH: Map<String, String> = mapOf(
        "CN" to "中国大陆",
        "HK" to "中国香港",
        "TW" to "中国台湾",
        "MO" to "中国澳门",
        "JP" to "日本",
        "SG" to "新加坡",
        "KR" to "韩国",
        "US" to "美国",
        "CA" to "加拿大",
        "GB" to "英国",
        "DE" to "德国",
        "FR" to "法国",
        "NL" to "荷兰",
        "RU" to "俄罗斯",
        "AU" to "澳大利亚",
        "NZ" to "新西兰",
        "MY" to "马来西亚",
        "TH" to "泰国",
        "VN" to "越南",
        "PH" to "菲律宾",
        "ID" to "印度尼西亚",
        "IN" to "印度",
        "SE" to "瑞典",
        "NO" to "挪威",
        "FI" to "芬兰",
        "DK" to "丹麦",
        "CH" to "瑞士",
        "AT" to "奥地利",
        "PL" to "波兰",
        "IT" to "意大利",
        "ES" to "西班牙",
        "PT" to "葡萄牙",
        "IE" to "爱尔兰",
        "LU" to "卢森堡",
        "BE" to "比利时",
        "CZ" to "捷克",
        "TR" to "土耳其",
        "AE" to "阿联酋",
        "IL" to "以色列",
        "BR" to "巴西",
        "AR" to "阿根廷",
        "CL" to "智利",
        "MX" to "墨西哥",
        "ZA" to "南非",
    )

    fun normalizeCountryCode(raw: String?): String? {
        val code = raw?.trim()?.uppercase(Locale.US) ?: return null
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null
        if (code == "XX" || code == "ZZ") return null
        return if (code == "UK") "GB" else code
    }

    fun normalizeAsn(raw: String?): String? {
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = Regex("(?:AS)?(\\d+)", RegexOption.IGNORE_CASE).find(clean) ?: return null
        return "AS${match.groupValues[1]}"
    }

    /**
     * Classifies an IP as NATIVE / BROADCAST / ANYCAST / UNKNOWN by comparing RIR/WHOIS
     * registration country with GeoIP actual announcement country.
     */
    fun classifyOrigin(
        geoCountryCode: String?,
        rirCountryCode: String?,
        rirAuthority: String? = null,
        isAnycast: Boolean = false,
    ): OriginClassification {
        val geo = normalizeCountryCode(geoCountryCode)
        val rir = normalizeCountryCode(rirCountryCode)
        val authorityTag = rirAuthority?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }

        if (isAnycast) {
            val detail = buildString {
                append("Anycast 任播网络")
                if (geo != null) {
                    append(" (当前接入 ")
                    append(geo)
                    if (rir != null && rir != geo) {
                        append(" · 注册地 ")
                        append(rir)
                    }
                    append(")")
                } else if (rir != null) {
                    append(" (注册地 ")
                    append(rir)
                    append(")")
                }
            }
            return OriginClassification(IpOriginType.ANYCAST, detail)
        }

        if (geo != null && rir != null) {
            if (rir == "EU") {
                return OriginClassification(
                    originType = IpOriginType.UNKNOWN,
                    evidence = "RIR 注册为泛欧区域 (EU)，GeoIP 归属 $geo",
                )
            }
            return if (geo == rir) {
                val suffix = if (authorityTag != null) " · $authorityTag" else ""
                OriginClassification(
                    originType = IpOriginType.NATIVE,
                    evidence = "原生 IP (注册地与归属地一致 $geo$suffix)",
                )
            } else {
                val suffix = if (authorityTag != null) " · $authorityTag" else ""
                OriginClassification(
                    originType = IpOriginType.BROADCAST,
                    evidence = "广播 IP (注册地 $rir -> 播放在 $geo$suffix)",
                )
            }
        }

        return when {
            geo != null -> OriginClassification(
                originType = IpOriginType.UNKNOWN,
                evidence = "仅获取到归属地 $geo，未能查询到 RIR 注册地",
            )
            rir != null -> OriginClassification(
                originType = IpOriginType.UNKNOWN,
                evidence = "仅获取到 RIR 注册地 $rir，未能查询到 GeoIP 归属地",
            )
            else -> OriginClassification(
                originType = IpOriginType.UNKNOWN,
                evidence = "信息不足，无法判定原生或广播属性",
            )
        }
    }

    /**
     * Classifies IP usage into `Anycast`, `Hosting 机房`, `ISP 家宽/商宽`, or `未知属性`.
     */
    fun classifyUsage(
        orgName: String?,
        ispName: String?,
        asnType: String? = null,
        companyType: String? = null,
        isHostingHint: Boolean = false,
        isAnycast: Boolean = false,
    ): IpUsageType {
        if (isAnycast) return IpUsageType.ANYCAST

        val combined = listOfNotNull(orgName, ispName)
            .joinToString(" ")
            .lowercase(Locale.US)

        if ("anycast" in combined) return IpUsageType.ANYCAST

        val normalizedAsnType = asnType?.trim()?.lowercase(Locale.US)
        val normalizedCompanyType = companyType?.trim()?.lowercase(Locale.US)

        if (isHostingHint ||
            normalizedAsnType == "hosting" ||
            normalizedCompanyType == "hosting"
        ) {
            return IpUsageType.HOSTING_DATACENTER
        }

        if (normalizedAsnType == "isp" || normalizedCompanyType == "isp") {
            return IpUsageType.ISP_RESIDENTIAL_OR_BUSINESS
        }

        if (HOSTING_KEYWORDS.any { it in combined }) {
            return IpUsageType.HOSTING_DATACENTER
        }

        if (ISP_KEYWORDS.any { it in combined }) {
            return IpUsageType.ISP_RESIDENTIAL_OR_BUSINESS
        }

        if (normalizedAsnType == "business" || normalizedCompanyType == "business") {
            return IpUsageType.ISP_RESIDENTIAL_OR_BUSINESS
        }

        return IpUsageType.UNKNOWN
    }

    /**
     * Maps the local client's egress ASN/ISP/country into a concise Chinese carrier label.
     */
    fun classifyClientCarrier(
        asn: String?,
        ispName: String?,
        orgName: String?,
        countryCode: String?,
    ): Pair<ClientCarrier, String> {
        val normAsn = normalizeAsn(asn)
        val normCountry = normalizeCountryCode(countryCode)
        val combined = listOfNotNull(ispName, orgName).joinToString(" ").lowercase(Locale.US)

        // Match domestic Chinese carriers by ASN first (most accurate)
        when {
            normAsn in CHINA_TELECOM_ASNS -> return ClientCarrier.CHINA_TELECOM to ClientCarrier.CHINA_TELECOM.label
            normAsn in CHINA_UNICOM_ASNS -> return ClientCarrier.CHINA_UNICOM to ClientCarrier.CHINA_UNICOM.label
            normAsn in CHINA_MOBILE_ASNS -> return ClientCarrier.CHINA_MOBILE to ClientCarrier.CHINA_MOBILE.label
            normAsn in CHINA_BROADNET_ASNS -> return ClientCarrier.CHINA_BROADNET to ClientCarrier.CHINA_BROADNET.label
            normAsn in CHINA_EDU_ASNS -> return ClientCarrier.CHINA_EDUCATION to ClientCarrier.CHINA_EDUCATION.label
        }

        // Fallback keyword matching for Chinese carriers
        when {
            "china telecom" in combined || "chinanet" in combined || "电信" in combined || "chinatelecom" in combined || "ctnet" in combined ->
                return ClientCarrier.CHINA_TELECOM to ClientCarrier.CHINA_TELECOM.label
            "china unicom" in combined || "china169" in combined || "cncgroup" in combined || "联通" in combined || "chinaunicom" in combined ->
                return ClientCarrier.CHINA_UNICOM to ClientCarrier.CHINA_UNICOM.label
            "china mobile" in combined || "cmnet" in combined || "cmcc" in combined || "移动" in combined || "chinamobile" in combined ->
                return ClientCarrier.CHINA_MOBILE to ClientCarrier.CHINA_MOBILE.label
            "china broadcasting" in combined || "broadnet" in combined || "广电" in combined || "chinabroadnet" in combined ->
                return ClientCarrier.CHINA_BROADNET to ClientCarrier.CHINA_BROADNET.label
            "cernet" in combined || "教育网" in combined ->
                return ClientCarrier.CHINA_EDUCATION to ClientCarrier.CHINA_EDUCATION.label
        }

        if (normCountry != null && normCountry != "CN") {
            val label = "海外网络 (${countryNameZh(normCountry) ?: normCountry})"
            return ClientCarrier.OVERSEAS to label
        }

        if (normCountry == "CN") {
            return ClientCarrier.UNKNOWN to "中国大陆网络"
        }

        return ClientCarrier.UNKNOWN to ClientCarrier.UNKNOWN.label
    }

    fun countryNameZh(countryCode: String?): String? {
        val norm = normalizeCountryCode(countryCode) ?: return null
        return COUNTRY_NAMES_ZH[norm]
    }

    /**
     * Resolves a two-letter ISO country code or Chinese/English country name (e.g. `"中国"`, `"香港"`, `"日本"`)
     * into a normalized ISO-3166 alpha-2 code.
     */
    fun countryCodeFromZhOrName(raw: String?, regionHint: String? = null): String? {
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val region = regionHint?.trim().orEmpty()
        if (clean == "中国" || clean.equals("China", ignoreCase = true)) {
            return when {
                region.contains("香港") || region.equals("Hong Kong", ignoreCase = true) -> "HK"
                region.contains("台湾") || region.equals("Taiwan", ignoreCase = true) -> "TW"
                region.contains("澳门") || region.equals("Macau", ignoreCase = true) || region.equals("Macao", ignoreCase = true) -> "MO"
                else -> "CN"
            }
        }
        if (clean.contains("香港")) return "HK"
        if (clean.contains("台湾")) return "TW"
        if (clean.contains("澳门")) return "MO"
        normalizeCountryCode(clean)?.let { return it }
        COUNTRY_NAMES_ZH.entries.firstOrNull { (_, zh) ->
            zh.equals(clean, ignoreCase = true)
        }?.let { return it.key }
        return null
    }

    fun countryFlagEmoji(countryCode: String?): String {
        val norm = normalizeCountryCode(countryCode) ?: return "🌐"
        val first = Character.toChars(0x1F1E6 + (norm[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (norm[1] - 'A'))
        return String(first) + String(second)
    }

    fun formatLocationSummary(
        countryCode: String?,
        countryName: String?,
        regionName: String?,
        cityName: String?,
    ): String {
        val normCode = normalizeCountryCode(countryCode)
        val primaryCountry = countryNameZh(normCode)
            ?: countryName?.trim()?.takeIf { it.isNotEmpty() }
            ?: normCode
            ?: "未知地区"
        val locality = listOfNotNull(
            regionName?.trim()?.takeIf { it.isNotEmpty() && !it.equals(primaryCountry, ignoreCase = true) },
            cityName?.trim()?.takeIf {
                it.isNotEmpty() &&
                    !it.equals(regionName, ignoreCase = true) &&
                    !it.equals(primaryCountry, ignoreCase = true)
            },
        ).distinct().joinToString(" ")
        return if (locality.isNotEmpty()) "$primaryCountry $locality" else primaryCountry
    }
}
