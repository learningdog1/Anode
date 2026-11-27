package com.nodeseek.app.data.fleet

import com.nodeseek.app.domain.fleet.DomainRdapParser
import com.nodeseek.app.domain.fleet.DomainRdapResult
import com.nodeseek.app.domain.fleet.DomainWhoisParser
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keyless domain RDAP client with multi-layer authoritative resolution:
 * 1. Direct authoritative RDAP endpoints for TLDs omitted from IANA's RDAP DNS bootstrap
 *    (e.g. DENIC `.de` at `https://rdap.denic.de/`, `.io`/`.me`/`.ai`/`.sh`/`.ac`, `.ch`/`.li`, `.us`).
 * 2. Primary RDAP aggregator (`https://rdap.org/domain/{domain}`) with 302 redirect & subdomain stepping.
 * 3. IANA RDAP DNS bootstrap (`https://data.iana.org/rdap/dns.json`) on any primary failure (including 404).
 * 4. Built-in fallback registry RDAP endpoints (e.g. CentralNic `.icu`, Verisign `.com`/`.net`, etc.)
 *    when both `rdap.org` and `data.iana.org` are unreachable or incomplete.
 * 5. Cookie-free RFC 3912 WHOIS port-43 fallback (with `whois.iana.org` referral) for TLDs without
 *    working RDAP or when RDAP fails transiently.
 *
 * SECURITY / COOKIE ISOLATION:
 * Always builds/sanitizes a dedicated cookie-free [OkHttpClient] (`CookieJar.NO_COOKIES`,
 * all NodeSeek interceptors stripped, `followRedirects = true` for `rdap.org` 302 redirects)
 * — NEVER shares `apiHttpClient` or WebView cookies.
 */
open class DomainRdapClient(
    baseHttpClient: OkHttpClient? = null,
    private val primaryEndpointTemplate: String = DEFAULT_PRIMARY_ENDPOINT_TEMPLATE,
    private val ianaBootstrapUrl: String? = DEFAULT_IANA_BOOTSTRAP_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val directTldRdapEndpoints: Map<String, String> = if (primaryEndpointTemplate == DEFAULT_PRIMARY_ENDPOINT_TEMPLATE) {
        DEFAULT_NON_IANA_RDAP_ENDPOINTS
    } else {
        emptyMap()
    },
    private val fallbackTldRdapEndpoints: Map<String, String> = if (primaryEndpointTemplate == DEFAULT_PRIMARY_ENDPOINT_TEMPLATE) {
        DEFAULT_FALLBACK_RDAP_ENDPOINTS
    } else {
        emptyMap()
    },
    private val whoisLookup: (suspend (String) -> Result<DomainRdapResult>)? = if (primaryEndpointTemplate == DEFAULT_PRIMARY_ENDPOINT_TEMPLATE) {
        { domain -> WhoisFallbackClient.lookup(domain = domain, clockMillis = clockMillis) }
    } else {
        null
    },
) {
    private val httpClient: OkHttpClient = sanitizeCookieFreeClient(baseHttpClient)

    @Volatile
    private var cachedBootstrapJson: String? = null

    open suspend fun lookupDomain(rawDomain: String): Result<DomainRdapResult> {
        val normalized = VpsAssetRecord.normalizeDomainName(rawDomain)
            ?: return Result.failure(IllegalArgumentException("请输入有效的域名，例如 example.com"))

        return withContext(ioDispatcher) {
            try {
                val labels = normalized.split('.')
                val tld = labels.last()
                val attemptedUrls = LinkedHashSet<String>()
                var bestFailure: Result<DomainRdapResult>? = null

                // Stage 1: Direct authoritative RDAP for TLDs omitted from IANA dns.json / rdap.org
                // (e.g., .de -> https://rdap.denic.de/domain/{domain})
                val directBase = directTldRdapEndpoints[tld]
                if (directBase != null) {
                    val directResult = executeWithSubdomainFallback(
                        normalized = normalized,
                        labels = labels,
                        urlBuilder = { candidate -> buildRdapUrl(directBase, candidate) },
                        attemptedUrls = attemptedUrls,
                    )
                    if (directResult.isSuccess) {
                        return@withContext directResult
                    }
                    bestFailure = directResult
                }

                // Stage 2: Primary RDAP endpoint (default https://rdap.org/domain/{domain})
                // with right-to-left parent-label stepping when a subdomain returns 404.
                val primaryResult = executeWithSubdomainFallback(
                    normalized = normalized,
                    labels = labels,
                    urlBuilder = { candidate -> buildRdapUrl(primaryEndpointTemplate, candidate) },
                    attemptedUrls = attemptedUrls,
                )
                if (primaryResult.isSuccess) {
                    return@withContext primaryResult
                }
                val primaryErr = primaryResult.exceptionOrNull()
                if (bestFailure == null || !isNoRdapServiceError(primaryErr)) {
                    bestFailure = primaryResult
                }

                // Stage 3: IANA RDAP DNS bootstrap for the TLD on ANY primary failure
                // (including 404 when rdap.org lacks/mismaps a TLD, 403/429, 5xx, timeout, or parse error).
                if (ianaBootstrapUrl != null) {
                    val bootstrapBase = resolveIanaBootstrapBase(tld)
                    if (bootstrapBase != null) {
                        val bootstrapResult = executeWithSubdomainFallback(
                            normalized = normalized,
                            labels = labels,
                            urlBuilder = { candidate -> buildRdapUrl(bootstrapBase, candidate) },
                            attemptedUrls = attemptedUrls,
                        )
                        if (bootstrapResult.isSuccess) {
                            return@withContext bootstrapResult
                        }
                        val bootstrapErr = bootstrapResult.exceptionOrNull()
                        if (bootstrapErr !is RdapSkippedException && isNoRdapServiceError(bestFailure?.exceptionOrNull())) {
                            bestFailure = bootstrapResult
                        }
                    }
                }

                // Stage 4: Built-in registry RDAP endpoints (e.g. CentralNic .icu, Verisign .com/.net)
                // when both rdap.org and data.iana.org are unreachable or returned errors.
                val fallbackBase = fallbackTldRdapEndpoints[tld]
                if (fallbackBase != null) {
                    val fallbackResult = executeWithSubdomainFallback(
                        normalized = normalized,
                        labels = labels,
                        urlBuilder = { candidate -> buildRdapUrl(fallbackBase, candidate) },
                        attemptedUrls = attemptedUrls,
                    )
                    if (fallbackResult.isSuccess) {
                        return@withContext fallbackResult
                    }
                    val fallbackErr = fallbackResult.exceptionOrNull()
                    if (fallbackErr !is RdapSkippedException && isNoRdapServiceError(bestFailure?.exceptionOrNull())) {
                        bestFailure = fallbackResult
                    }
                }

                // Stage 5: RFC 3912 WHOIS port-43 fallback (supports ccTLDs without RDAP and resilient recovery)
                if (whoisLookup != null) {
                    val candidates = buildDomainCandidates(normalized, labels)
                    for (candidate in candidates) {
                        val whoisRes = runCatching { whoisLookup.invoke(candidate) }.getOrElse { Result.failure(it) }
                        if (whoisRes.isSuccess) {
                            return@withContext whoisRes
                        }
                        if (whoisRes.exceptionOrNull() !is NoSuchElementException) {
                            break
                        }
                    }
                }

                val finalFailure = bestFailure ?: primaryResult
                val finalErr = finalFailure.exceptionOrNull()
                if (finalErr is RdapHttpException && finalErr.noRdapServiceForTld) {
                    Result.failure(
                        RdapHttpException(
                            statusCode = finalErr.statusCode,
                            message = "未查询到「$normalized」的 RDAP/WHOIS 记录（该后缀暂未提供公开查询或域名未注册）",
                            noRdapServiceForTld = true,
                        ),
                    )
                } else {
                    finalFailure
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    private fun buildDomainCandidates(
        normalized: String,
        labels: List<String>,
    ): List<String> {
        if (labels.size <= 2) return listOf(normalized)
        return buildList(labels.size - 1) {
            add(normalized)
            for (startIdx in 1..(labels.size - 2)) {
                add(labels.subList(startIdx, labels.size).joinToString("."))
            }
        }
    }

    private fun executeWithSubdomainFallback(
        normalized: String,
        labels: List<String>,
        urlBuilder: (String) -> String,
        attemptedUrls: MutableSet<String>,
    ): Result<DomainRdapResult> {
        val firstUrl = urlBuilder(normalized)
        if (!attemptedUrls.add(firstUrl)) {
            return Result.failure(RdapSkippedException("已尝试过该 RDAP 节点"))
        }
        val firstResult = executeRdapRequest(firstUrl, normalized, attemptedUrls)
        if (firstResult.isSuccess) {
            return firstResult
        }

        val firstErr = firstResult.exceptionOrNull()
        // If a subdomain returned 404 (domain not found, not "no RDAP service for TLD"),
        // step through intermediate parent labels right-to-left (e.g. a.b.example.co.uk -> b.example.co.uk -> example.co.uk).
        if (labels.size > 2 &&
            firstErr is RdapHttpException &&
            firstErr.statusCode == 404 &&
            !firstErr.noRdapServiceForTld
        ) {
            for (startIdx in 1..(labels.size - 2)) {
                val parentCandidate = labels.subList(startIdx, labels.size).joinToString(".")
                val parentUrl = urlBuilder(parentCandidate)
                if (!attemptedUrls.add(parentUrl)) continue
                val parentResult = executeRdapRequest(parentUrl, parentCandidate, attemptedUrls)
                if (parentResult.isSuccess) {
                    return parentResult
                }
                val parentErr = parentResult.exceptionOrNull()
                if (parentErr !is RdapHttpException || parentErr.statusCode != 404) {
                    break
                }
            }
        }
        return firstResult
    }

    private fun executeRdapRequest(
        url: String,
        domain: String,
        attemptedUrls: MutableSet<String>,
    ): Result<DomainRdapResult> {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/rdap+json, application/json;q=0.9")
            .header("User-Agent", "Anode-RDAP/1.0")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                attemptedUrls.add(finalUrl)
                val bodyStr = response.body.string()
                if (!response.isSuccessful) {
                    val noService = response.code == 404 && looksLikeNoRdapServiceBody(bodyStr)
                    val msg = when {
                        noService -> "公共 RDAP 聚合暂不支持「$domain」所属后缀"
                        response.code == 404 -> "未查询到「$domain」的 RDAP 记录，请确认输入根域名"
                        response.code == 429 -> "RDAP 查询过于频繁，请稍后再试"
                        else -> "RDAP 服务响应异常 (HTTP ${response.code})"
                    }
                    return Result.failure(RdapHttpException(response.code, msg, noRdapServiceForTld = noService))
                }
                if (bodyStr.isBlank()) {
                    return Result.failure(IOException("RDAP 返回内容为空"))
                }
                DomainRdapParser.parse(
                    rawJson = bodyStr,
                    queriedDomain = domain,
                    sourceUrl = finalUrl,
                    fetchedAtEpochMillis = clockMillis(),
                ).recoverCatching { parseErr ->
                    throw IOException("RDAP 响应解析失败：${parseErr.message.orEmpty()}", parseErr)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (io: IOException) {
            Result.failure(IOException("RDAP 网络请求失败：${io.message.orEmpty()}", io))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun looksLikeNoRdapServiceBody(body: String): Boolean {
        if (body.isBlank()) return false
        val lower = body.lowercase(Locale.ROOT)
        return lower.contains("no rdap service") ||
            lower.contains("rdap service is not available") ||
            lower.contains("unsupported tld")
    }

    private fun isNoRdapServiceError(err: Throwable?): Boolean =
        err is RdapSkippedException || (err is RdapHttpException && err.noRdapServiceForTld)

    private fun resolveIanaBootstrapBase(tld: String): String? {
        val url = ianaBootstrapUrl ?: return null
        val jsonBody = cachedBootstrapJson ?: runCatching {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", "Anode-RDAP/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body.string().takeIf { it.isNotBlank() }
            }
        }.getOrNull()?.also { cachedBootstrapJson = it } ?: return null

        return DomainRdapParser.extractBootstrapUrlForTld(jsonBody, tld)
    }

    private fun buildRdapUrl(template: String, domain: String): String {
        val lowerDomain = domain.lowercase(Locale.ROOT)
        return when {
            template.contains("{domain}") -> template.replace("{domain}", lowerDomain)
            template.contains("%7Bdomain%7D", ignoreCase = true) ->
                template.replace("%7Bdomain%7D", lowerDomain, ignoreCase = true)
            else -> {
                val base = if (template.endsWith('/')) template else "$template/"
                "${base}domain/$lowerDomain"
            }
        }
    }

    private class RdapHttpException(
        val statusCode: Int,
        message: String,
        val noRdapServiceForTld: Boolean = false,
    ) : IOException(message)

    private class RdapSkippedException(message: String) : IOException(message)

    companion object {
        const val DEFAULT_PRIMARY_ENDPOINT_TEMPLATE = "https://rdap.org/domain/{domain}"
        const val DEFAULT_IANA_BOOTSTRAP_URL = "https://data.iana.org/rdap/dns.json"

        /**
         * Authoritative RDAP base URLs for TLDs that operate public RDAP servers but are absent
         * from IANA's `dns.json` bootstrap (and therefore return 404 from `rdap.org`).
         */
        val DEFAULT_NON_IANA_RDAP_ENDPOINTS: Map<String, String> = mapOf(
            "de" to "https://rdap.denic.de/",
            "ch" to "https://rdap.nic.ch/",
            "li" to "https://rdap.nic.li/",
            "io" to "https://rdap.identitydigital.services/rdap/",
            "me" to "https://rdap.identitydigital.services/rdap/",
            "sh" to "https://rdap.identitydigital.services/rdap/",
            "ac" to "https://rdap.identitydigital.services/rdap/",
            "ai" to "https://rdap.identitydigital.services/rdap/",
            "us" to "https://rdap.nic.us/",
            "cc" to "https://tld-rdap.verisign.com/cc/v1/",
            "tv" to "https://rdap.nic.tv/",
        )

        /**
         * Direct registry RDAP base URLs used as resilience fallback if both `rdap.org` and
         * `data.iana.org/rdap/dns.json` fail or are unreachable.
         */
        val DEFAULT_FALLBACK_RDAP_ENDPOINTS: Map<String, String> = mapOf(
            "icu" to "https://rdap.centralnic.com/icu/",
            "xyz" to "https://rdap.centralnic.com/xyz/",
            "cyou" to "https://rdap.centralnic.com/cyou/",
            "cfd" to "https://rdap.centralnic.com/cfd/",
            "sbs" to "https://rdap.centralnic.com/sbs/",
            "bond" to "https://rdap.centralnic.com/bond/",
            "site" to "https://rdap.centralnic.com/site/",
            "online" to "https://rdap.centralnic.com/online/",
            "store" to "https://rdap.centralnic.com/store/",
            "tech" to "https://rdap.centralnic.com/tech/",
            "fun" to "https://rdap.centralnic.com/fun/",
            "space" to "https://rdap.centralnic.com/space/",
            "com" to "https://rdap.verisign.com/com/v1/",
            "net" to "https://rdap.verisign.com/net/v1/",
            "org" to "https://rdap.publicinterestregistry.org/rdap/",
            "uk" to "https://rdap.nominet.uk/uk/",
            "dev" to "https://pubapi.registry.google/rdap/",
            "app" to "https://pubapi.registry.google/rdap/",
            "page" to "https://pubapi.registry.google/rdap/",
            "top" to "https://rdap.zdnsgtld.com/top/",
            "vip" to "https://rdap.nic.vip/",
            "cloud" to "https://rdap.registry.cloud/rdap/",
            "de" to "https://rdap.denic.de/",
        )

        val sharedDefault: DomainRdapClient by lazy { DomainRdapClient() }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        internal fun sanitizeCookieFreeClient(baseHttpClient: OkHttpClient?): OkHttpClient {
            if (baseHttpClient == null) return defaultHttpClient()
            val builder = baseHttpClient.newBuilder()
                .cookieJar(CookieJar.NO_COOKIES)
                .followRedirects(true)
                .followSslRedirects(true)
            builder.interceptors().clear()
            builder.networkInterceptors().clear()
            return builder.build()
        }
    }
}

/**
 * Minimal, cookie-free RFC 3912 TCP/43 WHOIS fallback client used only when RDAP endpoints fail
 * or a TLD does not publish an RDAP endpoint.
 */
internal object WhoisFallbackClient {
    private const val WHOIS_PORT = 43
    private const val IANA_WHOIS_HOST = "whois.iana.org"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 6_000
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    private val KNOWN_TLD_WHOIS_SERVERS: Map<String, String> = mapOf(
        "de" to "whois.denic.de",
        "icu" to "whois.nic.icu",
        "com" to "whois.verisign-grs.com",
        "net" to "whois.verisign-grs.com",
        "org" to "whois.pir.org",
        "io" to "whois.nic.io",
        "me" to "whois.nic.me",
        "co" to "whois.registry.co",
        "ai" to "whois.nic.ai",
        "cn" to "whois.cnnic.cn",
        "uk" to "whois.nic.uk",
        "jp" to "whois.jprs.jp",
        "eu" to "whois.eu",
        "ru" to "whois.tcinet.ru",
        "hk" to "whois.hkirc.hk",
        "tw" to "whois.twnic.net.tw",
        "sg" to "whois.sgnic.sg",
        "ch" to "whois.nic.ch",
        "li" to "whois.nic.li",
        "nl" to "whois.domain-registry.nl",
        "fr" to "whois.nic.fr",
        "us" to "whois.nic.us",
        "cc" to "ccwhois.verisign-grs.com",
        "tv" to "whois.nic.tv",
        "xyz" to "whois.nic.xyz",
        "top" to "whois.nic.top",
        "dev" to "whois.nic.google",
        "app" to "whois.nic.google",
        "gg" to "whois.gg",
        "sh" to "whois.nic.sh",
        "ac" to "whois.nic.ac",
    )

    fun lookup(
        domain: String,
        clockMillis: () -> Long = { System.currentTimeMillis() },
        queryTransport: (host: String, query: String) -> String = ::executeTcpWhoisQuery,
    ): Result<DomainRdapResult> {
        val normalized = VpsAssetRecord.normalizeDomainName(domain)
            ?: return Result.failure(IllegalArgumentException("请输入有效的域名"))
        val tld = normalized.substringAfterLast('.')
        return try {
            val authoritativeHost = KNOWN_TLD_WHOIS_SERVERS[tld] ?: run {
                val ianaBody = queryTransport(IANA_WHOIS_HOST, normalized)
                DomainWhoisParser.extractReferralServer(ianaBody)
            } ?: return Result.failure(IOException("未找到 .$tld 的 WHOIS 服务器"))

            val primaryRaw = queryTransport(authoritativeHost, formatWhoisQuery(authoritativeHost, normalized))
            val primaryParsed = DomainWhoisParser.parse(
                rawWhois = primaryRaw,
                queriedDomain = normalized,
                sourceUrl = "whois://$authoritativeHost/$normalized",
                fetchedAtEpochMillis = clockMillis(),
            )
            if (primaryParsed.isSuccess) {
                val parsed = primaryParsed.getOrThrow()
                // Thin registries (e.g. Verisign .com/.net) may refer to a registrar WHOIS server
                // with richer data; follow once only when key fields are still missing.
                if (parsed.expiryDateIso == null || parsed.registrarName.isNullOrBlank()) {
                    val referredHost = DomainWhoisParser.extractReferralServer(primaryRaw)
                    if (referredHost != null && !referredHost.equals(authoritativeHost, ignoreCase = true)) {
                        val referredParsed = runCatching {
                            val referredRaw = queryTransport(referredHost, formatWhoisQuery(referredHost, normalized))
                            DomainWhoisParser.parse(
                                rawWhois = referredRaw,
                                queriedDomain = normalized,
                                sourceUrl = "whois://$referredHost/$normalized",
                                fetchedAtEpochMillis = clockMillis(),
                            ).getOrNull()
                        }.getOrNull()
                        if (referredParsed != null) {
                            return Result.success(
                                parsed.copy(
                                    registrarName = parsed.registrarName ?: referredParsed.registrarName,
                                    registrarIanaId = parsed.registrarIanaId ?: referredParsed.registrarIanaId,
                                    expiryDateIso = parsed.expiryDateIso ?: referredParsed.expiryDateIso,
                                    expiryInstantIso = parsed.expiryInstantIso ?: referredParsed.expiryInstantIso,
                                    registrationDateIso = parsed.registrationDateIso ?: referredParsed.registrationDateIso,
                                    lastChangedDateIso = parsed.lastChangedDateIso ?: referredParsed.lastChangedDateIso,
                                    statuses = (parsed.statuses + referredParsed.statuses).distinct(),
                                    nameservers = (parsed.nameservers + referredParsed.nameservers).distinct(),
                                ),
                            )
                        }
                    }
                }
                return primaryParsed
            }
            primaryParsed
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    internal fun formatWhoisQuery(host: String, domain: String): String = when {
        host.equals("whois.denic.de", ignoreCase = true) -> "-T dn,ace $domain"
        host.equals("whois.jprs.jp", ignoreCase = true) -> "$domain/e"
        else -> domain
    }

    private fun executeTcpWhoisQuery(host: String, query: String): String {
        val safeHost = VpsAssetRecord.normalizeDomainName(host)
            ?: throw IOException("无效的 WHOIS 服务器地址：$host")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(safeHost, WHOIS_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = socket.getOutputStream()
            out.write("$query\r\n".toByteArray(Charsets.UTF_8))
            out.flush()

            val input = socket.getInputStream()
            val buffer = ByteArray(4096)
            val baos = ByteArrayOutputStream()
            while (baos.size() < MAX_RESPONSE_BYTES) {
                val read = input.read(buffer)
                if (read <= 0) break
                val remaining = MAX_RESPONSE_BYTES - baos.size()
                baos.write(buffer, 0, minOf(read, remaining))
            }
            return baos.toString(Charsets.UTF_8.name())
        }
    }
}
