package com.nodeseek.app.data.restock

import com.nodeseek.app.domain.restock.DetectedMerchantLink
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.ui.components.unwrapNodeSeekJumpUrl
import org.jsoup.Jsoup
import java.net.URI

/**
 * Extracts and classifies candidate VPS merchant / billing / cart / affiliate order links
 * from NodeSeek post Markdown or HTML, automatically unwrapping `nodeseek.com/jump?to=...` links.
 */
object MerchantLinkDetector {

    private val MARKDOWN_LINK_REGEX = Regex(
        pattern = """(?<!!)\[([^\]]{0,160})\]\(([^)\s]+(?:\([^)]*\)[^)\s]*)*)\)""",
    )

    private val BARE_URL_REGEX = Regex(
        pattern = """https?://[^\s<>()\[\]"'，。；、！？”’]+""",
        option = RegexOption.IGNORE_CASE,
    )

    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'', '，', '。', '；')

    private val EXCLUDED_HOST_SUFFIXES = setOf(
        "nodeseek.com",
        "nodeimage.com",
        "github.com",
        "githubusercontent.com",
        "gitlab.com",
        "imgur.com",
        "postimg.cc",
        "sm.ms",
        "s2.loli.net",
        "t.me",
        "telegram.me",
        "youtube.com",
        "youtu.be",
        "bilibili.com",
        "twitter.com",
        "x.com",
        "google.com",
        "gstatic.com",
        "cloudflare.com",
        "speedtest.net",
        "bgp.tools",
        "ping.pe",
        "ipinfo.io",
        "ip.sb",
        "wikipedia.org",
        "zhihu.com",
        "v2ex.com",
        "reddit.com",
    )

    private val MEDIA_EXTENSIONS = setOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp",
        ".mp4", ".webm", ".mp3", ".wav", ".pdf", ".zip", ".tar", ".gz",
    )

    private val KNOWN_VPS_MERCHANT_DOMAINS = setOf(
        "dmit.io",
        "bandwagonhost.com",
        "bwh81.net",
        "bwh88.net",
        "bwh89.net",
        "racknerd.com",
        "cloudcone.com",
        "greencloudvps.com",
        "claw.cloud",
        "virmach.com",
        "spartanhost.org",
        "buyvm.net",
        "frantech.ca",
        "hetzner.com",
        "netcup.de",
        "netcup.com",
        "ovhcloud.com",
        "vultr.com",
        "linode.com",
        "digitalocean.com",
        "hosthatch.com",
        "v.ps",
        "xtom.com",
        "wawo.wiki",
        "alice.ws",
        "akile.io",
        "halo.do",
        "yxvm.com",
        "kurun.com",
        "wap.ac",
        "colocrossing.com",
        "hostdzire.com",
        "crunchbits.com",
        "servarica.com",
        "hostbrr.com",
        "webhorizon.net",
        "smarthost.net",
        "desivps.com",
        "justhost.asia",
        "justhost.ru",
        "gcore.com",
        "misaka.io",
        "nube.sh",
        "boil.network",
        "rfc.host",
        "rfchost.com",
        "coalcloud.net",
        "bagevm.com",
        "acck.io",
        "bytevirt.com",
        "legendvps.com",
        "vmiss.com",
        "dogyun.com",
        "hostdare.com",
        "rn.com",
        "ccs.com",
        "servarica.com",
        "layer.ae",
        "hytron.io",
    )

    private val MERCHANT_SUBDOMAIN_PREFIXES = listOf(
        "billing.", "my.", "portal.", "client.", "clients.", "cp.", "panel.",
        "console.", "order.", "shop.", "store.", "cart.", "vps.", "cloud.",
        "manage.", "account.", "members.", "myaccount.", "clientarea.", "app.",
    )

    private val MERCHANT_PATH_MARKERS = listOf(
        "/cart.php", "/aff.php", "/clientarea.php", "/order.php",
        "/cart", "/order", "/store", "/shop", "/product", "/products",
        "/checkout", "/buy", "/vps", "/aff", "/ref/", "/deploy",
        "/configure", "/billing", "/plans", "/pricing", "/kvm",
        "/dedicated", "/server", "/servers", "/goods", "/package",
    )

    private val MERCHANT_QUERY_KEYS = listOf(
        "pid=", "gid=", "aff=", "affid=", "ref=", "plan=", "product=",
        "promocode=", "coupon=", "cmd=cart", "a=add", "a=confproduct", "rp=/store",
    )

    /**
     * Unwraps NodeSeek redirect URLs (`/jump?to=...`) and normalizes whitespace/scheme.
     */
    fun resolveOrderUrl(rawUrl: String): String {
        val cleaned = cleanRawUrl(rawUrl)
        if (cleaned.isEmpty()) return ""
        val withScheme = ensureHttpScheme(cleaned)
        val unwrapped = unwrapNodeSeekJumpUrl(withScheme).trim()
        // Handle rare double-wrapped jump links safely (at most 2 passes)
        val secondPass = if (unwrapped != withScheme && unwrapped.contains("/jump?", ignoreCase = true)) {
            unwrapNodeSeekJumpUrl(unwrapped).trim()
        } else {
            unwrapped
        }
        return cleanRawUrl(secondPass)
    }

    /**
     * Infers the merchant billing panel type purely from URL patterns.
     */
    fun inferPanelTypeFromUrl(url: String): MerchantPanelType {
        val resolved = resolveOrderUrl(url)
        val lower = resolved.lowercase()
        return when {
            lower.contains("cart.php") ||
                lower.contains("aff.php") ||
                lower.contains("clientarea.php") ||
                lower.contains("rp=/store") ||
                lower.contains("a=confproduct") ||
                lower.contains("whmcs") ||
                (lower.contains("order.php") && lower.contains("pid=")) -> MerchantPanelType.WHMCS

            lower.contains("cmd=cart") ||
                lower.contains("index.php?/cart") ||
                lower.contains("hostbill") -> MerchantPanelType.HOSTBILL

            lower.contains("virtfusion") ||
                lower.contains("/order/package/") -> MerchantPanelType.VIRTFUSION

            lower.contains("solusvm") ||
                lower.contains("solus.io") ||
                lower.contains(":5656") -> MerchantPanelType.SOLUSVM

            else -> MerchantPanelType.GENERIC
        }
    }

    /**
     * Returns `true` when [rawUrl] resolves to an external VPS merchant / billing / cart / aff link.
     */
    fun isLikelyMerchantLink(rawUrl: String): Boolean =
        scoreCandidate(rawUrl = rawUrl, anchorText = "") != null

    /**
     * Extracts deduplicated candidate merchant order links from NodeSeek post Markdown or HTML.
     */
    fun extractMerchantLinks(content: String): List<DetectedMerchantLink> {
        if (content.isBlank()) return emptyList()

        val candidatesByResolved = LinkedHashMap<String, DetectedMerchantLink>()

        fun consider(rawUrl: String, anchorText: String) {
            val detected = scoreCandidate(rawUrl = rawUrl, anchorText = anchorText) ?: return
            val key = canonicalDedupKey(detected.resolvedUrl)
            val existing = candidatesByResolved[key]
            if (existing == null) {
                candidatesByResolved[key] = detected
            } else {
                val bestAnchor = when {
                    existing.anchorText.isNotBlank() -> existing.anchorText
                    else -> detected.anchorText
                }
                val bestPanel = if (existing.panelType != MerchantPanelType.GENERIC) {
                    existing.panelType
                } else {
                    detected.panelType
                }
                candidatesByResolved[key] = existing.copy(
                    anchorText = bestAnchor,
                    panelType = bestPanel,
                    confidence = maxOf(existing.confidence, detected.confidence),
                )
            }
        }

        // 1. Markdown links: [text](url)
        for (match in MARKDOWN_LINK_REGEX.findAll(content)) {
            val anchor = match.groupValues[1].trim()
            val url = match.groupValues[2].trim()
            consider(rawUrl = url, anchorText = anchor)
        }

        // 2. HTML <a href="...">text</a>
        if (content.contains("<a", ignoreCase = true) && content.contains("href", ignoreCase = true)) {
            runCatching {
                val doc = Jsoup.parseBodyFragment(content)
                for (anchorEl in doc.select("a[href]")) {
                    val href = anchorEl.attr("href").trim()
                    val text = anchorEl.text().trim()
                    if (href.startsWith("http://", ignoreCase = true) ||
                        href.startsWith("https://", ignoreCase = true) ||
                        href.startsWith("/jump?", ignoreCase = true)
                    ) {
                        val normalizedHref = if (href.startsWith("/jump?", ignoreCase = true)) {
                            "https://www.nodeseek.com$href"
                        } else {
                            href
                        }
                        consider(rawUrl = normalizedHref, anchorText = text)
                    }
                }
            }
        }

        // 3. Bare URLs
        for (match in BARE_URL_REGEX.findAll(content)) {
            consider(rawUrl = match.value, anchorText = "")
        }

        return candidatesByResolved.values.sortedByDescending { it.confidence }
    }

    private fun scoreCandidate(rawUrl: String, anchorText: String): DetectedMerchantLink? {
        val cleanedRaw = cleanRawUrl(rawUrl)
        if (cleanedRaw.isEmpty()) return null

        val resolved = resolveOrderUrl(cleanedRaw)
        if (!resolved.startsWith("http://", ignoreCase = true) &&
            !resolved.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        val uri = runCatching { URI(resolved) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.trim()?.removeSuffix(".") ?: return null
        if (host.isEmpty() || !host.contains('.')) return null

        if (isExcludedHost(host)) return null

        val path = uri.rawPath.orEmpty().lowercase()
        val query = uri.rawQuery.orEmpty().lowercase()
        val fullLower = resolved.lowercase()

        if (MEDIA_EXTENSIONS.any { path.endsWith(it) }) return null

        val panelType = inferPanelTypeFromUrl(resolved)
        var score = 0

        if (panelType != MerchantPanelType.GENERIC) {
            score += 55
        }
        if (path.contains("cart.php") && (query.contains("pid=") || query.contains("a=add") || query.contains("gid="))) {
            score += 35
        }
        if (MERCHANT_PATH_MARKERS.any { path.contains(it) }) {
            score += 25
        }
        if (MERCHANT_QUERY_KEYS.any { query.contains(it) || fullLower.contains(it) }) {
            score += 25
        }
        if (MERCHANT_SUBDOMAIN_PREFIXES.any { host.startsWith(it) }) {
            score += 20
        }
        if (isKnownVpsMerchant(host)) {
            score += 35
        }

        val anchorLower = anchorText.lowercase()
        if (anchorLower.isNotBlank() && (
                anchorLower.contains("购买") ||
                    anchorLower.contains("下单") ||
                    anchorLower.contains("直达") ||
                    anchorLower.contains("抢购") ||
                    anchorLower.contains("aff") ||
                    anchorLower.contains("order") ||
                    anchorLower.contains("buy") ||
                    anchorLower.contains("cart") ||
                    anchorLower.contains("vps") ||
                    anchorLower.contains("补货")
                )
        ) {
            score += 18
        }

        if (score < 20) return null

        return DetectedMerchantLink(
            rawUrl = cleanedRaw,
            resolvedUrl = resolved,
            anchorText = anchorText.trim(),
            panelType = panelType,
            host = host,
            confidence = score.coerceAtMost(100),
        )
    }

    private fun isExcludedHost(host: String): Boolean =
        EXCLUDED_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }

    private fun isKnownVpsMerchant(host: String): Boolean =
        KNOWN_VPS_MERCHANT_DOMAINS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }

    private fun cleanRawUrl(raw: String): String {
        var s = raw.trim()
        while (s.isNotEmpty() && s.last() in TRAILING_PUNCTUATION) {
            // Preserve balanced closing parenthesis inside URLs like /foo(bar)
            if (s.last() == ')' && s.count { it == '(' } >= s.count { it == ')' }) {
                break
            }
            s = s.dropLast(1)
        }
        return s.trim()
    }

    private fun ensureHttpScheme(url: String): String {
        if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            return url
        }
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/jump?", ignoreCase = true)) {
            return "https://www.nodeseek.com$url"
        }
        // Handle bare domains pasted by user like "my.racknerd.com/cart.php?a=add&pid=1"
        if (url.contains('.') && !url.contains(' ')) {
            return "https://$url"
        }
        return url
    }

    private fun canonicalDedupKey(url: String): String =
        url.trim().trimEnd('/').lowercase()
}
