package com.nodeseek.app.data.netbench

import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.IpClassificationEngine
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Endpoint URL templates for [IpIntelligenceClient]. Uses `{ip}` placeholder for target queries.
 * Customizable in unit tests to point cleanly at `MockWebServer`.
 */
data class IpIntelligenceEndpoints(
    val ipWhoIsTargetTemplate: String = "https://ipwho.is/{ip}",
    val ipInfoTargetTemplate: String = "https://ipinfo.io/widget/demo/{ip}",
    val ipApiTargetTemplate: String = "https://ipapi.co/{ip}/json/",
    val ripeStatWhoisTemplate: String = "https://stat.ripe.net/data/whois/data.json?resource={ip}",
    val rdapTemplates: List<String> = listOf(
        "https://rdap.db.ripe.net/ip/{ip}",
        "https://rdap.arin.net/registry/ip/{ip}",
        "https://rdap.apnic.net/ip/{ip}",
    ),
    val clientSelfUrls: List<Pair<String, String>> = listOf(
        "ipwho.is" to "https://ipwho.is/",
        "ipinfo.io" to "https://ipinfo.io/json",
        "ipapi.co" to "https://ipapi.co/json/",
    ),
)

/**
 * Multi-source IP intelligence & Native-vs-Broadcast classifier client.
 *
 * - Target GeoIP & ASN fallback chain: `ipwho.is` -> `ipinfo.io/widget` -> `ipapi.co`.
 * - Target RIR registration country fallback chain: RIPEstat WHOIS -> RDAP (RIPE/ARIN/APNIC).
 * - Local client egress carrier detection: `电信` / `联通` / `移动` / `广电` / `海外网络`.
 */
class IpIntelligenceClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val endpoints: IpIntelligenceEndpoints = IpIntelligenceEndpoints(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private data class GeoAsnSnapshot(
        val ip: String?,
        val countryCode: String?,
        val countryName: String?,
        val regionName: String?,
        val cityName: String?,
        val asn: String?,
        val orgName: String?,
        val ispName: String?,
        val asnType: String? = null,
        val companyType: String? = null,
        val isHostingHint: Boolean = false,
        val isAnycast: Boolean = false,
        val source: String,
    )

    private data class WhoisRirSnapshot(
        val rirCountryCode: String?,
        val rirAuthority: String?,
        val orgFallback: String?,
        val isAnycastHint: Boolean = false,
        val source: String,
    )

    /**
     * Queries target IP GeoIP/ASN + RIR WHOIS/RDAP concurrently and classifies Native vs Broadcast.
     */
    suspend fun lookupTargetIp(rawIp: String): IpIntelligenceReport = withContext(ioDispatcher) {
        val cleanIp = rawIp.trim().removePrefix("[").removeSuffix("]")
        val family = NetBenchTargetParser.detectIpFamily(cleanIp)

        if (cleanIp.isEmpty()) {
            return@withContext IpIntelligenceReport(
                ip = rawIp,
                ipFamily = family,
                countryCode = null,
                countryName = null,
                regionName = null,
                cityName = null,
                asn = null,
                orgName = null,
                ispName = null,
                usageType = IpUsageType.UNKNOWN,
                originType = IpOriginType.UNKNOWN,
                rirCountryCode = null,
                rirAuthority = null,
                originEvidence = "IP 地址为空，无法查询归属信息",
                errorMessage = "IP 地址无效",
            )
        }

        coroutineScope {
            val geoDeferred = async { fetchTargetGeoWithFallback(cleanIp) }
            val whoisDeferred = async { fetchTargetWhoisWithFallback(cleanIp) }

            val geo = geoDeferred.await()
            val whois = whoisDeferred.await()

            val isAnycast = (geo?.isAnycast == true) || (whois?.isAnycastHint == true)
            val resolvedOrg = geo?.orgName?.takeIf { it.isNotBlank() }
                ?: whois?.orgFallback?.takeIf { it.isNotBlank() }
                ?: geo?.ispName?.takeIf { it.isNotBlank() }

            val usageType = IpClassificationEngine.classifyUsage(
                orgName = resolvedOrg,
                ispName = geo?.ispName,
                asnType = geo?.asnType,
                companyType = geo?.companyType,
                isHostingHint = geo?.isHostingHint == true,
                isAnycast = isAnycast,
            )

            val effectiveAnycast = isAnycast || usageType == IpUsageType.ANYCAST
            val originResult = IpClassificationEngine.classifyOrigin(
                geoCountryCode = geo?.countryCode,
                rirCountryCode = whois?.rirCountryCode,
                rirAuthority = whois?.rirAuthority,
                isAnycast = effectiveAnycast,
            )

            val errorMsg = if (geo == null && whois == null) {
                "无法连接 IP 归属与 WHOIS 查询服务，请稍后重试"
            } else {
                null
            }

            IpIntelligenceReport(
                ip = cleanIp,
                ipFamily = family,
                countryCode = IpClassificationEngine.normalizeCountryCode(geo?.countryCode),
                countryName = geo?.countryName,
                regionName = geo?.regionName,
                cityName = geo?.cityName,
                asn = IpClassificationEngine.normalizeAsn(geo?.asn),
                orgName = resolvedOrg,
                ispName = geo?.ispName,
                usageType = usageType,
                originType = originResult.originType,
                rirCountryCode = IpClassificationEngine.normalizeCountryCode(whois?.rirCountryCode),
                rirAuthority = whois?.rirAuthority,
                originEvidence = originResult.evidence,
                isAnycast = effectiveAnycast,
                geoSource = geo?.source,
                whoisSource = whois?.source,
                errorMessage = errorMsg,
            )
        }
    }

    /**
     * Queries the user's own local egress carrier (电信/联通/移动/广电/海外网络) with fallback.
     * Note: [ClientNetworkInfo.rawIp] is kept only for local masked display and never exported.
     */
    suspend fun lookupLocalClientNetwork(): ClientNetworkInfo = withContext(ioDispatcher) {
        for ((sourceName, url) in endpoints.clientSelfUrls) {
            val body = fetchJsonText(url) ?: continue
            val parsed = when (sourceName) {
                "ipwho.is" -> parseIpWhoIs(body, sourceName)
                "ipinfo.io" -> parseIpInfo(body, sourceName)
                "ipapi.co" -> parseIpApi(body, sourceName)
                else -> parseIpWhoIs(body, sourceName) ?: parseIpInfo(body, sourceName) ?: parseIpApi(body, sourceName)
            }
            if (parsed != null) {
                val (carrier, displayName) = IpClassificationEngine.classifyClientCarrier(
                    asn = parsed.asn,
                    ispName = parsed.ispName,
                    orgName = parsed.orgName,
                    countryCode = parsed.countryCode,
                )
                val ipFamily = NetBenchTargetParser.detectIpFamily(parsed.ip)
                return@withContext ClientNetworkInfo(
                    carrier = carrier,
                    carrierDisplayName = displayName,
                    ipFamily = ipFamily,
                    countryCode = IpClassificationEngine.normalizeCountryCode(parsed.countryCode),
                    regionName = parsed.regionName,
                    asn = IpClassificationEngine.normalizeAsn(parsed.asn),
                    ispName = parsed.ispName ?: parsed.orgName,
                    maskedIp = NetBenchTargetParser.maskClientIpForDisplay(parsed.ip),
                    rawIp = parsed.ip,
                    source = parsed.source,
                )
            }
        }

        ClientNetworkInfo(
            carrier = ClientCarrier.UNKNOWN,
            carrierDisplayName = ClientCarrier.UNKNOWN.label,
        )
    }

    private suspend fun fetchTargetGeoWithFallback(ip: String): GeoAsnSnapshot? {
        val encoded = encodePathSegment(ip)

        // 1. ipwho.is
        val whoIsUrl = endpoints.ipWhoIsTargetTemplate.replace("{ip}", encoded)
        fetchJsonText(whoIsUrl)?.let { body ->
            parseIpWhoIs(body, "ipwho.is")?.let { return it }
        }

        // 2. ipinfo.io widget / json
        val ipInfoUrl = endpoints.ipInfoTargetTemplate.replace("{ip}", encoded)
        fetchJsonText(ipInfoUrl)?.let { body ->
            parseIpInfo(body, "ipinfo.io")?.let { return it }
        }

        // 3. ipapi.co
        val ipApiUrl = endpoints.ipApiTargetTemplate.replace("{ip}", encoded)
        fetchJsonText(ipApiUrl)?.let { body ->
            parseIpApi(body, "ipapi.co")?.let { return it }
        }

        return null
    }

    private suspend fun fetchTargetWhoisWithFallback(ip: String): WhoisRirSnapshot? {
        val encoded = encodePathSegment(ip)

        // 1. RIPEstat WHOIS
        val ripeStatUrl = endpoints.ripeStatWhoisTemplate.replace("{ip}", encoded)
        fetchJsonText(ripeStatUrl)?.let { body ->
            parseRipeStatWhois(body)?.let { snapshot ->
                if (snapshot.rirCountryCode != null) return snapshot
            }
        }

        // 2. RDAP fallback endpoints (OkHttp follows redirects to authoritative RIR)
        for (template in endpoints.rdapTemplates) {
            val rdapUrl = template.replace("{ip}", encoded)
            fetchJsonText(rdapUrl)?.let { body ->
                parseRdapResponse(body)?.let { snapshot ->
                    if (snapshot.rirCountryCode != null) return snapshot
                }
            }
        }

        return null
    }

    private fun parseIpWhoIs(rawJson: String, source: String): GeoAsnSnapshot? {
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val success = root["success"]?.jsonPrimitive?.booleanOrNull
            if (success == false) return null

            val countryCode = root.str("country_code")
            val countryName = root.str("country")
            val regionName = root.str("region")
            val cityName = root.str("city")
            val ip = root.str("ip")

            val conn = root["connection"] as? JsonObject
            val asn = conn?.str("asn")?.let { IpClassificationEngine.normalizeAsn(it) }
            val org = conn?.str("org")
            val isp = conn?.str("isp")

            if (countryCode == null && asn == null && org == null && isp == null) return null

            GeoAsnSnapshot(
                ip = ip,
                countryCode = countryCode,
                countryName = countryName,
                regionName = regionName,
                cityName = cityName,
                asn = asn,
                orgName = org ?: isp,
                ispName = isp ?: org,
                source = source,
            )
        }.getOrNull()
    }

    private fun parseIpInfo(rawJson: String, source: String): GeoAsnSnapshot? {
        return runCatching {
            val outer = json.parseToJsonElement(rawJson).jsonObject
            val root = (outer["data"] as? JsonObject) ?: outer
            if (root["error"] != null || root["bogon"]?.jsonPrimitive?.booleanOrNull == true) {
                return null
            }

            val ip = root.str("ip")
            val countryCode = root.str("country")
            val regionName = root.str("region")
            val cityName = root.str("city")
            val anycast = root["anycast"]?.jsonPrimitive?.booleanOrNull == true

            val asnObj = root["asn"] as? JsonObject
            val companyObj = root["company"] as? JsonObject
            val privacyObj = root["privacy"] as? JsonObject

            val rawOrgLine = root.str("org")
            val asnFromOrgLine = rawOrgLine?.let {
                Regex("^(AS\\d+)\\b", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
            }
            val orgNameFromOrgLine = rawOrgLine?.replace(Regex("^AS\\d+\\s*", RegexOption.IGNORE_CASE), "")
                ?.trim()?.takeIf { it.isNotEmpty() }

            val asn = IpClassificationEngine.normalizeAsn(asnObj?.str("asn") ?: asnFromOrgLine)
            val orgName = companyObj?.str("name") ?: asnObj?.str("name") ?: orgNameFromOrgLine
            val ispName = asnObj?.str("name") ?: companyObj?.str("name") ?: orgNameFromOrgLine
            val asnType = asnObj?.str("type")
            val companyType = companyObj?.str("type")
            val isHosting = privacyObj?.get("hosting")?.jsonPrimitive?.booleanOrNull == true

            if (countryCode == null && asn == null && orgName == null) return null

            GeoAsnSnapshot(
                ip = ip,
                countryCode = countryCode,
                countryName = IpClassificationEngine.countryNameZh(countryCode),
                regionName = regionName,
                cityName = cityName,
                asn = asn,
                orgName = orgName,
                ispName = ispName,
                asnType = asnType,
                companyType = companyType,
                isHostingHint = isHosting,
                isAnycast = anycast,
                source = source,
            )
        }.getOrNull()
    }

    private fun parseIpApi(rawJson: String, source: String): GeoAsnSnapshot? {
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            if (root["error"]?.jsonPrimitive?.booleanOrNull == true) return null

            val ip = root.str("ip")
            val countryCode = root.str("country_code") ?: root.str("country")
            val countryName = root.str("country_name")
            val regionName = root.str("region")
            val cityName = root.str("city")
            val asn = IpClassificationEngine.normalizeAsn(root.str("asn"))
            val org = root.str("org")

            if (countryCode == null && asn == null && org == null) return null

            GeoAsnSnapshot(
                ip = ip,
                countryCode = countryCode,
                countryName = countryName,
                regionName = regionName,
                cityName = cityName,
                asn = asn,
                orgName = org,
                ispName = org,
                source = source,
            )
        }.getOrNull()
    }

    private fun parseRipeStatWhois(rawJson: String): WhoisRirSnapshot? {
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val data = root["data"] as? JsonObject ?: return null

            val authority = data["authorities"]?.jsonArray
                ?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?.trim()?.uppercase(Locale.US)

            val records = data["records"]?.jsonArray ?: return null
            var specificCountry: String? = null
            var euFallbackCountry: String? = null
            var orgFallback: String? = null
            var anycastHint = false

            for (recordGroupEl in records) {
                val group = recordGroupEl.jsonArray
                for (itemEl in group) {
                    val item = itemEl.jsonObject
                    val key = item.str("key")?.lowercase(Locale.US) ?: continue
                    val value = item.str("value") ?: continue

                    when (key) {
                        "country" -> {
                            val norm = IpClassificationEngine.normalizeCountryCode(value)
                            if (norm != null) {
                                if (norm == "EU") {
                                    if (euFallbackCountry == null) euFallbackCountry = norm
                                } else if (specificCountry == null) {
                                    specificCountry = norm
                                }
                            }
                        }
                        "org-name", "orgname", "descr", "netname" -> {
                            if (value.contains("anycast", ignoreCase = true)) {
                                anycastHint = true
                            }
                            if (orgFallback == null && key != "netname") {
                                orgFallback = value
                            }
                        }
                    }
                }
            }

            val resolvedCountry = specificCountry ?: euFallbackCountry
            WhoisRirSnapshot(
                rirCountryCode = resolvedCountry,
                rirAuthority = authority,
                orgFallback = orgFallback,
                isAnycastHint = anycastHint,
                source = "RIPEstat",
            )
        }.getOrNull()
    }

    private fun parseRdapResponse(rawJson: String): WhoisRirSnapshot? {
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val country = IpClassificationEngine.normalizeCountryCode(root.str("country"))
            val name = root.str("name")
            val type = root.str("type")
            val port43 = root.str("port43")?.lowercase(Locale.US).orEmpty()

            val authority = when {
                "arin" in port43 -> "ARIN"
                "apnic" in port43 -> "APNIC"
                "ripe" in port43 -> "RIPE"
                "lacnic" in port43 -> "LACNIC"
                "afrinic" in port43 -> "AFRINIC"
                else -> null
            }

            val anycastHint = (name?.contains("anycast", ignoreCase = true) == true) ||
                (type?.contains("anycast", ignoreCase = true) == true)

            WhoisRirSnapshot(
                rirCountryCode = country,
                rirAuthority = authority,
                orgFallback = name,
                isAnycastHint = anycastHint,
                source = "RDAP",
            )
        }.getOrNull()
    }

    private suspend fun fetchJsonText(url: String): String? {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Anode-NetBench/1.0")
        if (url.contains("ipinfo.io/widget", ignoreCase = true)) {
            builder.header("Referer", "https://ipinfo.io/")
        }
        val request = builder.build()

        return try {
            executeCancellable(request)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun executeCancellable(request: Request): String? =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) {
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!cont.isActive) return
                            if (!res.isSuccessful) {
                                cont.resume(null)
                                return
                            }
                            val body = runCatching { res.body?.string() }.getOrNull()
                            cont.resume(body?.takeIf { it.isNotBlank() })
                        }
                    }
                },
            )
        }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun encodePathSegment(raw: String): String =
        URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
