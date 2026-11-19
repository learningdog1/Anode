package com.nodeseek.app.domain.deals

import com.nodeseek.app.data.valuation.CurrencyRateQuote
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.components.unwrapNodeSeekJumpUrl
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Structured fields extracted from a forum promo thread or overseas VPS offer.
 */
data class ExtractedDealFields(
    val merchantName: String = "",
    val rawPriceText: String? = null,
    val priceAmount: BigDecimal? = null,
    val currency: ValuationCurrency? = null,
    val billingCycle: DealBillingCycle = DealBillingCycle.UNKNOWN,
    val annualizedUsd: BigDecimal? = null,
    val annualizedCny: BigDecimal? = null,
    val cpuCores: Int? = null,
    val ramText: String? = null,
    val storageText: String? = null,
    val trafficText: String? = null,
    val portSpeedText: String? = null,
    val regionTags: List<String> = emptyList(),
    val routeTags: List<String> = emptyList(),
    val promoCode: String? = null,
    val orderUrl: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val isOutOfStockHint: Boolean = false,
    val cleanSummary: String = "",
)

/**
 * High-precision regex & heuristic extractor for NodeSeek Chinese promo threads and
 * LowEndTalk / LowEndBox English VPS deals.
 */
object DealPostExtractor {

    private val SHANGHAI_ZONE: ZoneId = VALUATION_ZONE_ID

    // ── URLs ──────────────────────────────────────────────────────────────────
    private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]{0,120})]\((https?://[^)\s]+)\)""")
    private val HTML_LINK_REGEX = Regex("""<a\s+[^>]*href=["'](https?://[^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BARE_URL_REGEX = Regex("""https?://[^\s<>)\]"'，。；！？`]+""")

    private val NON_ORDER_HOST_KEYWORDS = listOf(
        "nodeseek.com",
        "nodeimage.com",
        "imgur.com",
        "i.imgur.com",
        "img.chkaja.com",
        "sm.ms",
        "s2.loli.net",
        "postimg.cc",
        "imgbb.com",
        "github.com",
        "raw.githubusercontent.com",
        "gist.github.com",
        "t.me",
        "telegram.me",
        "youtube.com",
        "youtu.be",
        "bilibili.com",
        "twitter.com",
        "x.com",
        "speedtest.net",
        "bench.sh",
        "yabs.sh",
        "paste.ubuntu.com",
        "lowendtalk.com",
        "lowendbox.com",
        "ping.pe",
        "bgp.he.net",
        "bgp.tools",
    )

    private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".avif", ".bmp")

    private val ORDER_URL_STRONG_HINTS = listOf(
        "cart.php",
        "aff.php",
        "billing",
        "clientarea",
        "checkout",
        "order",
        "store/",
        "blackfriday",
        "black-friday",
        "cybermonday",
        "flash",
        "special",
        "promo",
        "deals",
        "pid=",
        "rp=/store",
        "index.php?rp=",
        "vps",
    )

    private val ORDER_ANCHOR_HINTS = listOf(
        "购买", "抢购", "直达", "下单", "订购", "官网", "链接", "活动", "套餐",
        "order", "buy", "grab", "shop", "deal", "link", "aff", "promo", "store",
    )

    // ── Price Patterns ────────────────────────────────────────────────────────
    private val CYCLE_SUFFIX_PATTERN =
        """(?:\s*(?:(?:/|per\s+)\s*(3\s*(?:yrs?|years?|y|年)|三年付?|2\s*(?:yrs?|years?|y|年)|两年付?|半年付?|semi[\s-]?annual(?:ly)?|6\s*(?:mo|months?|个月)|quarter(?:ly)?|qtr|季付?|3\s*(?:mo|months?|个月)|yrs?|years?|yearly|annual(?:ly)?|年付?|y(?![a-zA-Z])|months?|monthly|mo|月付?|m(?![a-zA-Z])|triennial(?:ly)?|biennial(?:ly)?|年|月|季)|(3\s*(?:yrs?|years?|年)|三年付?|2\s*(?:yrs?|years?|年)|两年付?|半年付?|semi[\s-]?annual(?:ly)?|quarter(?:ly)?|季付|yrs?|years?|yearly|annual(?:ly)?|年付|months?|monthly|月付|triennial(?:ly)?|biennial(?:ly)?)))"""

    private val CYCLE_PREFIX_PATTERN =
        """(三年付|3年付|两年付|2年付|半年付|年付|季付|月付|annual(?:ly)?|yearly|monthly|quarterly|biennial(?:ly)?|triennial(?:ly)?)"""

    // 1) Prefix currency symbol/code + amount + optional cycle: $10.99/yr, €12/year, ¥99/年, HK$88/yr, USD 15/yr
    private val PREFIX_CURRENCY_PRICE_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(US\$|USD\$|HK\$|SG\$|S\$|CA\$|C\$|AU\$|A\$|NT\$|USD|EUR|GBP|CNY|RMB|HKD|JPY|SGD|CAD|AUD|[$€£¥￥])\s*(\d{1,5}(?:\.\d{1,2})?)(?:$CYCLE_SUFFIX_PATTERN)?""",
        option = RegexOption.IGNORE_CASE,
    )

    // 2) Amount + suffix currency word/code + optional cycle: 19.9刀/年, 99元/年, 12欧/年, 15 USD/yr
    private val SUFFIX_CURRENCY_PRICE_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9.])(\d{1,5}(?:\.\d{1,2})?)\s*(美刀|美元|刀|欧元|欧|英镑|镑|港币|港元|日元|円|人民币|RMB|CNY|USD|EUR|GBP|HKD|JPY|SGD|CAD|AUD|元|块)(?:$CYCLE_SUFFIX_PATTERN)?""",
        option = RegexOption.IGNORE_CASE,
    )

    // 3) Chinese forum slang `99r/年`, `68r年付`, `12.5u/年` (requires strict cycle adjacent to avoid false positives)
    private val SLANG_CURRENCY_PRICE_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9.])(\d{1,5}(?:\.\d{1,2})?)\s*([rRuU])$CYCLE_SUFFIX_PATTERN""",
    )

    // 4) Cycle-before-price: 年付 $14.99, 年付19.9刀, 三年付29.9刀, 年付¥99, 年付99r, 月付 2.5刀
    private val CYCLE_FIRST_PRICE_REGEX = Regex(
        pattern = """$CYCLE_PREFIX_PATTERN\s*[:：约仅需]?\s*(?:(US\$|USD\$|HK\$|SG\$|S\$|CA\$|C\$|AU\$|A\$|NT\$|USD|EUR|GBP|CNY|RMB|HKD|JPY|SGD|CAD|AUD|[$€£¥￥])\s*(\d{1,5}(?:\.\d{1,2})?)|(\d{1,5}(?:\.\d{1,2})?)\s*(美刀|美元|刀|欧元|欧|英镑|镑|港币|港元|日元|円|人民币|RMB|CNY|USD|EUR|GBP|HKD|JPY|SGD|CAD|AUD|元|块|[rRuU](?![A-Za-z0-9])))""",
        option = RegexOption.IGNORE_CASE,
    )

    // ── Specs Patterns ────────────────────────────────────────────────────────
    // Compact Chinese VPS shorthand: 1C1G, 2C4G, 1C/1G/20G/1T, 2c2g40g, 1H1G, 2核4G, 1C 512M
    private val COMPACT_SPEC_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(\d{1,2})\s*(?:C|H|核|vCPU)\s*[/\-·\s]?\s*(\d{1,4}(?:\.\d)?)\s*(GB|G|MB|M)(?:\s*[/\-·\s]\s*(\d{1,4})\s*(GB|G)(?:\s*(NVMe|SSD|HDD))?(?!\s*(?:@|流量|月流量|Traffic|Bandwidth|Transfer|BW|/\s*mo|/\s*月))(?:\s*[/\-·]\s*(\d{1,4}(?:\.\d)?)\s*(TB|T|GB|G))?)?(?![A-Za-z0-9])""",
        option = RegexOption.IGNORE_CASE,
    )

    private val CPU_EXPLICIT_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(\d{1,3})\s*(?:x\s*)?(?:vCPUs?|vCores?|CPU\s*Cores?|Cores?|核心|核(?![算价实]))(?![A-Za-z])""",
        option = RegexOption.IGNORE_CASE,
    )
    private val CPU_LABEL_FIRST_REGEX = Regex(
        pattern = """(?:vCPU|CPU|核心)\s*[:：]\s*(\d{1,3})(?:\s*(?:vCPU|Core|Cores|核|C))?""",
        option = RegexOption.IGNORE_CASE,
    )

    private val RAM_EXPLICIT_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(\d{1,5}(?:\.\d)?)\s*(GB|GiB|G|MB|MiB|M)\s*(?:DDR[345]\s*)?(?:ECC\s*)?(?:RAM|Memory|内存)(?![A-Za-z])""",
        option = RegexOption.IGNORE_CASE,
    )
    private val RAM_LABEL_FIRST_REGEX = Regex(
        pattern = """(?:RAM|Memory|内存)\s*[:：]\s*(\d{1,5}(?:\.\d)?)\s*(GB|GiB|G|MB|MiB|M)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val STORAGE_EXPLICIT_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(\d{1,5}(?:\.\d)?)\s*(TB|TiB|T|GB|GiB|G)\s*(?:RAID[\-\s]?\d+\s*)?(NVMe(?:\s*SSD)?|SSD|HDD|SAS|SATA|硬盘|磁盘|存储)(?![A-Za-z])""",
        option = RegexOption.IGNORE_CASE,
    )
    private val STORAGE_LABEL_FIRST_REGEX = Regex(
        pattern = """(?:硬盘|磁盘|存储|Disk|Storage)\s*[:：]\s*(\d{1,5}(?:\.\d)?)\s*(TB|TiB|T|GB|GiB|G)(?:\s*(NVMe|SSD|HDD))?""",
        option = RegexOption.IGNORE_CASE,
    )

    private val TRAFFIC_AT_PORT_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(\d{1,5}(?:\.\d)?)\s*(TB|TiB|T|GB|GiB|G)\s*@\s*(\d{1,4}(?:\.\d)?)\s*(Gbps|G|Mbps|M)\b""",
        option = RegexOption.IGNORE_CASE,
    )
    private val TRAFFIC_EXPLICIT_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(\d{1,5}(?:\.\d)?)\s*(TB|TiB|T|GB|GiB|G)\s*(?:月)?(?:双向|单向)?\s*(?:流量|月流量|Traffic|Bandwidth|Transfer|BW|/\s*mo(?:nth)?|/\s*月)(?![A-Za-z])""",
        option = RegexOption.IGNORE_CASE,
    )
    private val TRAFFIC_LABEL_FIRST_REGEX = Regex(
        pattern = """(?:流量|月流量|Traffic|Bandwidth|Transfer)\s*[:：]\s*(?:(不限|无限|Unmetered|Unlimited)|(\d{1,5}(?:\.\d)?)\s*(TB|TiB|T|GB|GiB|G))""",
        option = RegexOption.IGNORE_CASE,
    )
    private val UNMETERED_TRAFFIC_REGEX = Regex(
        pattern = """(?:无限流量|不限流量|unmetered\s*(?:traffic|bandwidth|transfer)?|unlimited\s*(?:traffic|bandwidth))""",
        option = RegexOption.IGNORE_CASE,
    )

    private val PORT_SPEED_EXPLICIT_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(\d{1,4}(?:\.\d)?)\s*(Gbps|Mbps|G口|M口|G带宽|M带宽|G\s*Port|M\s*Port)(?![A-Za-z])""",
        option = RegexOption.IGNORE_CASE,
    )
    private val PORT_LABEL_FIRST_REGEX = Regex(
        pattern = """(?:带宽|端口|口子|Port|Uplink)\s*[:：]\s*(\d{1,4}(?:\.\d)?)\s*(Gbps|G|Mbps|M)""",
        option = RegexOption.IGNORE_CASE,
    )

    // ── Promo Code Patterns ───────────────────────────────────────────────────
    private val PROMO_CODE_ZH_REGEX = Regex(
        pattern = """(?:循环优惠码|黑五优惠码|优惠码|折扣码|促销码|黑五码|专属码)\s*[:：是为]?\s*[*`"“'【\[]?([A-Za-z0-9][A-Za-z0-9_-]{2,31})[*`"”'】\]]?""",
        option = RegexOption.IGNORE_CASE,
    )
    private val PROMO_CODE_EN_REGEX = Regex(
        pattern = """(?:promo(?:tion)?\s*code|coupon(?:\s*code)?|voucher(?:\s*code)?|discount\s*code|\bcode)\s*[:：=]?\s*[*`"“'\[]?([A-Za-z0-9][A-Za-z0-9_-]{2,31})[*`"”'\]]?""",
        option = RegexOption.IGNORE_CASE,
    )

    private val PROMO_CODE_STOP_WORDS = setOf(
        "none", "null", "auto", "automatic", "applied", "needed", "required",
        "no", "not", "off", "here", "link", "for", "the", "with", "and",
        "from", "this", "that", "your", "only", "code", "promo", "coupon",
        "discount", "http", "https", "www", "com", "net", "org", "php",
        "html", "block", "snippet", "area", "click", "order", "black",
    )

    // ── Deadline Patterns ─────────────────────────────────────────────────────
    private val DEADLINE_FULL_DATE_REGEX = Regex(
        pattern = """(?:截止日期|截止时间|截止至|截止|结束时间|结束于|活动至|有效期至|到期时间|到期|ends?\s*(?:on)?|expires?\s*(?:on)?|valid\s*(?:until|thru|through)|until)\s*[:：至到]?\s*(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})日?(?:[\sT]+(\d{1,2}):(\d{2}))?""",
        option = RegexOption.IGNORE_CASE,
    )
    private val DEADLINE_MD_DATE_REGEX = Regex(
        pattern = """(?:截止日期|截止时间|截止至|截止|结束于|活动至|有效期至|限至|有效至)\s*[:：至到]?\s*(\d{1,2})[-/.月](\d{1,2})日?(?:\s+(\d{1,2}):(\d{2}))?""",
        option = RegexOption.IGNORE_CASE,
    )
    private val DEADLINE_EN_MONTH_REGEX = Regex(
        pattern = """(?:ends?|expires?|until|valid\s*(?:until|thru|through))\s*(?:on\s*)?(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s*(20\d{2}))?""",
        option = RegexOption.IGNORE_CASE,
    )
    private val DEADLINE_RELATIVE_REGEX = Regex(
        pattern = """(?:(?:限时|仅限|闪购|倒计时)\s*(\d{1,3})\s*(小时|天|h|hr|hrs|hours?|days?)|(\d{1,3})\s*(hours?|hrs?|h|days?)\s*(?:flash\s*sale|only|left|remaining))""",
        option = RegexOption.IGNORE_CASE,
    )

    // ── Out-of-Stock Patterns ─────────────────────────────────────────────────
    private val OOS_STRONG_REGEX = Regex(
        pattern = """(已售罄|售罄|已抢光|抢光了|无货|没货了|已结束|活动已结束|已过期|已失效|sold\s*out|out\s*of\s*stock|(?<![A-Za-z])OOS(?![A-Za-z]))""",
        option = RegexOption.IGNORE_CASE,
    )
    private val IN_STOCK_RESTOCK_REGEX = Regex(
        pattern = """(补货了|补货啦|已补货|有货|可抢|back\s*in\s*stock|restocked|in\s*stock)""",
        option = RegexOption.IGNORE_CASE,
    )

    // ── Known Merchants ───────────────────────────────────────────────────────
    private data class MerchantAlias(
        val canonical: String,
        val pattern: Regex,
    )

    private val KNOWN_MERCHANTS: List<MerchantAlias> = listOf(
        MerchantAlias("RackNerd", Regex("""(?<![A-Za-z])(?:RackNerd|RN(?=\s*(?:黑五|BF|闪购|年付|特价|促销|\$|美西|DC)))""", RegexOption.IGNORE_CASE)),
        MerchantAlias("搬瓦工 (BWH)", Regex("""搬瓦工|BandwagonHost|Bandwagon|(?<![A-Za-z])BWH(?![A-Za-z])""", RegexOption.IGNORE_CASE)),
        MerchantAlias("DMIT", Regex("""(?<![A-Za-z])DMIT(?![A-Za-z])|大妈""", RegexOption.IGNORE_CASE)),
        MerchantAlias("CloudCone", Regex("""(?<![A-Za-z])(?:CloudCone|CC(?=\s*(?:黑五|BF|闪购|年付|特价|促销|\$|美西|LAX)))""", RegexOption.IGNORE_CASE)),
        MerchantAlias("GreenCloud 绿云", Regex("""GreenCloud|绿云""", RegexOption.IGNORE_CASE)),
        MerchantAlias("ClawCloud", Regex("""ClawCloud|Claw\s*Cloud|爪云""", RegexOption.IGNORE_CASE)),
        MerchantAlias("SpartanHost 斯巴达", Regex("""SpartanHost|Spartan\s*Host|斯巴达""", RegexOption.IGNORE_CASE)),
        MerchantAlias("HostDare", Regex("""HostDare""", RegexOption.IGNORE_CASE)),
        MerchantAlias("ColoCrossing", Regex("""ColoCrossing""", RegexOption.IGNORE_CASE)),
        MerchantAlias("VirMach", Regex("""VirMach""", RegexOption.IGNORE_CASE)),
        MerchantAlias("BuyVM", Regex("""BuyVM|FranTech""", RegexOption.IGNORE_CASE)),
        MerchantAlias("V.PS", Regex("""(?<![A-Za-z0-9])V\.PS(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)),
        MerchantAlias("AkkoCloud", Regex("""AkkoCloud""", RegexOption.IGNORE_CASE)),
        MerchantAlias("BageVM", Regex("""BageVM|Bage""", RegexOption.IGNORE_CASE)),
        MerchantAlias("YxVM", Regex("""YxVM""", RegexOption.IGNORE_CASE)),
        MerchantAlias("Misaka", Regex("""Misaka""", RegexOption.IGNORE_CASE)),
        MerchantAlias("ISIF", Regex("""(?<![A-Za-z])ISIF(?![A-Za-z])""", RegexOption.IGNORE_CASE)),
        MerchantAlias("RFC", Regex("""(?<![A-Za-z])RFC(?=\s*(?:HK|JP|SG|香港|日本|新加坡|CO|JINX|T1|黑五|年付|月付|VPS))""", RegexOption.IGNORE_CASE)),
        MerchantAlias("Alice", Regex("""Alice(?:\s*Networks?)?(?=\s*(?:HK|JP|SG|香港|日本|免费|黑五|VPS|年付|月付))""", RegexOption.IGNORE_CASE)),
        MerchantAlias("Vmiss", Regex("""Vmiss""", RegexOption.IGNORE_CASE)),
        MerchantAlias("ByteVirt", Regex("""ByteVirt""", RegexOption.IGNORE_CASE)),
        MerchantAlias("Akile", Regex("""Akile(?:Cloud)?""", RegexOption.IGNORE_CASE)),
        MerchantAlias("ZgoVPS", Regex("""ZgoVPS|ZgoCloud|\bZGO\b""", RegexOption.IGNORE_CASE)),
        MerchantAlias("CloudSilk", Regex("""CloudSilk|白丝""", RegexOption.IGNORE_CASE)),
        MerchantAlias("Netcup", Regex("""Netcup""", RegexOption.IGNORE_CASE)),
        MerchantAlias("Hetzner", Regex("""Hetzner""", RegexOption.IGNORE_CASE)),
        MerchantAlias("OVH", Regex("""(?<![A-Za-z])OVH(?:cloud)?(?![A-Za-z])""", RegexOption.IGNORE_CASE)),
        MerchantAlias("HostHatch", Regex("""HostHatch""", RegexOption.IGNORE_CASE)),
        MerchantAlias("ServaRICA", Regex("""ServaRICA""", RegexOption.IGNORE_CASE)),
        MerchantAlias("RareCloud", Regex("""RareCloud""", RegexOption.IGNORE_CASE)),
        MerchantAlias("Crunchbits", Regex("""Crunchbits""", RegexOption.IGNORE_CASE)),
        MerchantAlias("AlphaVPS", Regex("""AlphaVPS""", RegexOption.IGNORE_CASE)),
        MerchantAlias("WebHorizon", Regex("""WebHorizon""", RegexOption.IGNORE_CASE)),
        MerchantAlias("LayerStack", Regex("""LayerStack""", RegexOption.IGNORE_CASE)),
        MerchantAlias("Vultr", Regex("""Vultr""", RegexOption.IGNORE_CASE)),
        MerchantAlias("DigitalOcean", Regex("""DigitalOcean""", RegexOption.IGNORE_CASE)),
        MerchantAlias("腾讯云", Regex("""腾讯云|Tencent\s*Cloud""", RegexOption.IGNORE_CASE)),
        MerchantAlias("阿里云", Regex("""阿里云|Alibaba\s*Cloud|Aliyun""", RegexOption.IGNORE_CASE)),
        MerchantAlias("华为云", Regex("""华为云|Huawei\s*Cloud""", RegexOption.IGNORE_CASE)),
        MerchantAlias("雨云", Regex("""雨云|RainYun""", RegexOption.IGNORE_CASE)),
        MerchantAlias("狗云", Regex("""狗云|Dogyun""", RegexOption.IGNORE_CASE)),
        MerchantAlias("Kurun", Regex("""Kurun""", RegexOption.IGNORE_CASE)),
        MerchantAlias("JustG", Regex("""JustG""", RegexOption.IGNORE_CASE)),
        MerchantAlias("WAP", Regex("""(?<![A-Za-z])WAP(?:\.ac)?(?=\s*(?:HK|JP|US|香港|日本|黑五|年付|月付|VPS|鸡))""", RegexOption.IGNORE_CASE)),
        MerchantAlias("LegendVPS", Regex("""LegendVPS|Legend""", RegexOption.IGNORE_CASE)),
    )

    private val BRACKET_PREFIX_REGEX = Regex("""^[\[【(]([^\]】)]{2,24})[\]】)]""")
    private val GENERIC_BRACKET_TAGS = setOf(
        "黑五", "促销", "优惠", "特惠", "特价", "情报", "推广", "闪购", "补货", "抢购",
        "出", "收", "溢价出", "平出", "折价出", "aff", "无aff", "带aff", "直达", "限时",
        "bf", "bf2026", "black friday", "flash", "deal", "offer", "promo", "vps", "分享", "搬运",
    )

    // ── Promo Relevance Keywords ──────────────────────────────────────────────
    private val PROMO_STRONG_KEYWORDS = listOf(
        "黑五", "black friday", "blackfriday", "cyber monday", "网一", "网络星期一",
        "大促", "促销", "优惠", "特惠", "特价", "闪购", "flash", "折扣", "优惠码",
        "promo", "coupon", "deal", "offer", "补货", "抢购", "年付", "循环折扣",
        "白嫖", "骨折", "史低", "双十一", "双11", "bf20", "bf ", "[bf]", "【bf】",
        "续费同价", "recurring", "限量", "special",
    )

    private val NON_DEAL_NOISE_PREFIXES = listOf(
        "求助", "求推荐", "请问", "问一下", "怎么买", "值得买吗", "吐槽", "避坑",
    )

    /**
     * Default offline [FxRatesState] backed by [ValuationCurrency.defaultCnyRate] values.
     */
    fun defaultFxRatesState(): FxRatesState {
        val quotes = ValuationCurrency.entries.associateWith { currency ->
            CurrencyRateQuote(
                currency = currency,
                rateToCny = currency.defaultCnyRate,
                source = FxRateSource.OFFLINE_DEFAULT,
            )
        }
        return FxRatesState(quotes = quotes)
    }

    /**
     * Evaluates whether a forum topic is likely a promo/deal/flash-sale post worth showing
     * in the Black Friday Deals Hub.
     */
    fun isLikelyPromoDeal(
        title: String,
        body: String = "",
        categorySlug: String = "",
        extracted: ExtractedDealFields? = null,
    ): Boolean {
        val lowerTitle = title.lowercase(Locale.ROOT)
        val lowerCombined = "$title\n$body".lowercase(Locale.ROOT)

        val hasStrongKeyword = PROMO_STRONG_KEYWORDS.any { lowerCombined.contains(it) }
        val hasTitlePromoKeyword = PROMO_STRONG_KEYWORDS.any { lowerTitle.contains(it) }
        val hasStructuredPrice = extracted?.priceAmount != null
        val hasPromoCode = !extracted?.promoCode.isNullOrBlank()
        val hasOrderUrl = !extracted?.orderUrl.isNullOrBlank()
        val hasSpecs = extracted != null && (extracted.cpuCores != null || extracted.ramText != null)

        // Exclude pure help/question threads unless they explicitly include an actual promo code or order link
        val looksLikeQuestion = NON_DEAL_NOISE_PREFIXES.any { lowerTitle.contains(it) } &&
            !hasPromoCode && !hasOrderUrl && !hasStructuredPrice
        if (looksLikeQuestion) return false

        // In trade category, keep posts that look like Black Friday / promo / voucher / special deals
        if (categorySlug.equals("trade", ignoreCase = true)) {
            val isSecondhandWantToBuy = lowerTitle.startsWith("[收]") || lowerTitle.startsWith("【收】") || lowerTitle.startsWith("收 ")
            if (isSecondhandWantToBuy) return false
            return hasTitlePromoKeyword || hasPromoCode || (hasStrongKeyword && hasStructuredPrice)
        }

        // In promotion or info categories, be inclusive for any deal with promo keyword or price+specs/order
        if (categorySlug.equals("promotion", ignoreCase = true) || categorySlug.equals("info", ignoreCase = true)) {
            return hasStrongKeyword || hasPromoCode || (hasStructuredPrice && (hasSpecs || hasOrderUrl))
        }

        return hasStrongKeyword || hasPromoCode || (hasStructuredPrice && hasOrderUrl)
    }

    /**
     * Extracts all structured VPS deal fields from [title] and [body].
     */
    fun extract(
        title: String,
        body: String = "",
        fxRates: FxRatesState = defaultFxRatesState(),
        publishedAtEpochMillis: Long = System.currentTimeMillis(),
        fallbackUrl: String? = null,
    ): ExtractedDealFields {
        val normalizedTitle = title.trim()
        val normalizedBody = body.trim()
        val rawCombined = if (normalizedBody.isBlank()) normalizedTitle else "$normalizedTitle\n$normalizedBody"

        // 1. Extract order URLs before stripping URLs from prose
        val orderUrl = extractBestOrderUrl(rawCombined, fallbackUrl)

        // 2. Strip URLs from text for noise-free regex parsing of prices, specs, regions, routes
        val titleNoUrls = stripUrlsAndMarkdownSyntax(normalizedTitle)
        val bodyNoUrls = stripUrlsAndMarkdownSyntax(normalizedBody)
        val combinedNoUrls = if (bodyNoUrls.isBlank()) titleNoUrls else "$titleNoUrls\n$bodyNoUrls"

        // 3. Price + Billing Cycle + Annualization
        val priceCandidate = extractBestPrice(
            titleText = titleNoUrls,
            bodyText = bodyNoUrls,
            combinedText = combinedNoUrls,
        )
        val (annualUsd, annualCny) = if (priceCandidate != null) {
            annualizePrice(
                amount = priceCandidate.amount,
                currency = priceCandidate.currency,
                cycle = priceCandidate.cycle,
                fxRates = fxRates,
            )
        } else {
            null to null
        }

        // 4. VPS Hardware & Bandwidth Specs
        val specs = extractSpecs(titleNoUrls, bodyNoUrls)

        // 5. Region & Route Tags
        val regionTags = extractRegionTags(combinedNoUrls)
        val routeTags = extractRouteTags(combinedNoUrls)

        // 6. Promo Code (scan raw text so backticks/quotes around codes are parsed cleanly)
        val promoCode = extractPromoCode(normalizedTitle) ?: extractPromoCode(normalizedBody)

        // 7. Merchant Name
        val merchantName = extractMerchantName(
            title = titleNoUrls,
            body = bodyNoUrls,
            orderUrl = orderUrl,
        )

        // 8. Expiry / Flash-Sale Deadline
        val expiresAt = extractExpiryEpochMillis(
            text = combinedNoUrls,
            referenceEpochMillis = publishedAtEpochMillis,
        )

        // 9. Out-of-stock hint
        val isOutOfStock = detectOutOfStockHint(titleNoUrls, bodyNoUrls)

        // 10. Clean summary from body
        val cleanSummary = buildCleanSummary(normalizedBody, normalizedTitle)

        return ExtractedDealFields(
            merchantName = merchantName,
            rawPriceText = priceCandidate?.rawText,
            priceAmount = priceCandidate?.amount,
            currency = priceCandidate?.currency,
            billingCycle = priceCandidate?.cycle ?: DealBillingCycle.UNKNOWN,
            annualizedUsd = annualUsd,
            annualizedCny = annualCny,
            cpuCores = specs.cpuCores,
            ramText = specs.ramText,
            storageText = specs.storageText,
            trafficText = specs.trafficText,
            portSpeedText = specs.portSpeedText,
            regionTags = regionTags,
            routeTags = routeTags,
            promoCode = promoCode,
            orderUrl = orderUrl,
            expiresAtEpochMillis = expiresAt,
            isOutOfStockHint = isOutOfStock,
            cleanSummary = cleanSummary,
        )
    }

    /**
     * Convenience helper that transforms a NodeSeek [TopicSummary] + optional opening floor body
     * into a ready-to-render [PromoDealCardModel].
     */
    fun fromNodeSeekTopic(
        topic: TopicSummary,
        openingFloorMarkdown: String = "",
        fxRates: FxRatesState = defaultFxRatesState(),
        nowEpochMillis: Long = System.currentTimeMillis(),
        isDetailEnriched: Boolean = openingFloorMarkdown.isNotBlank(),
    ): PromoDealCardModel {
        val publishedMillis = parseFlexibleTimestampMillis(topic.lastActivityIso) ?: nowEpochMillis
        val extracted = extract(
            title = topic.title,
            body = openingFloorMarkdown,
            fxRates = fxRates,
            publishedAtEpochMillis = publishedMillis,
        )
        return PromoDealCardModel(
            id = "nodeseek_${topic.id}",
            source = DealSource.NODESEEK,
            topicId = topic.id,
            sourceUrl = "https://www.nodeseek.com/post-${topic.id}-1",
            orderUrl = extracted.orderUrl,
            merchantName = extracted.merchantName,
            title = topic.title.trim(),
            translatedSummary = extracted.cleanSummary,
            rawPriceText = extracted.rawPriceText,
            priceAmount = extracted.priceAmount,
            currency = extracted.currency,
            annualizedUsd = extracted.annualizedUsd,
            annualizedCny = extracted.annualizedCny,
            billingCycleLabel = extracted.billingCycle.label,
            cpuCores = extracted.cpuCores,
            ramText = extracted.ramText,
            storageText = extracted.storageText,
            trafficText = extracted.trafficText,
            portSpeedText = extracted.portSpeedText,
            regionTags = extracted.regionTags,
            routeTags = extracted.routeTags,
            promoCode = extracted.promoCode,
            expiresAtEpochMillis = extracted.expiresAtEpochMillis,
            publishedAtEpochMillis = publishedMillis,
            replyCount = topic.replyCount,
            isOutOfStockHint = extracted.isOutOfStockHint,
            authorName = topic.authorName,
            categorySlug = topic.categorySlug,
            viewCount = topic.viewCount,
            isDetailEnriched = isDetailEnriched,
        )
    }

    /**
     * Re-computes annualized USD & CNY for an external or cached [PromoDealCardModel] whenever
     * live FX rates update or an external bridge supplies raw price fields without annualization.
     */
    fun normalizeWithFxRates(
        deal: PromoDealCardModel,
        fxRates: FxRatesState,
    ): PromoDealCardModel {
        val amount = deal.priceAmount
        val currency = deal.currency
        val cycle = DealBillingCycle.fromLabel(deal.billingCycleLabel)
        if (amount != null && currency != null && cycle != DealBillingCycle.UNKNOWN && cycle != DealBillingCycle.ONE_TIME) {
            val (usd, cny) = annualizePrice(amount, currency, cycle, fxRates)
            return deal.copy(
                annualizedUsd = usd ?: deal.annualizedUsd,
                annualizedCny = cny ?: deal.annualizedCny,
            )
        }
        // If only rawPriceText/title is present on an external card without parsed fields, enrich missing parts
        if (deal.annualizedUsd == null || deal.regionTags.isEmpty() || deal.routeTags.isEmpty()) {
            val extracted = extract(
                title = deal.title,
                body = deal.translatedSummary,
                fxRates = fxRates,
                publishedAtEpochMillis = deal.publishedAtEpochMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
                fallbackUrl = deal.orderUrl,
            )
            return deal.copy(
                merchantName = deal.merchantName.ifBlank { extracted.merchantName },
                orderUrl = deal.orderUrl ?: extracted.orderUrl,
                rawPriceText = deal.rawPriceText ?: extracted.rawPriceText,
                priceAmount = deal.priceAmount ?: extracted.priceAmount,
                currency = deal.currency ?: extracted.currency,
                annualizedUsd = deal.annualizedUsd ?: extracted.annualizedUsd,
                annualizedCny = deal.annualizedCny ?: extracted.annualizedCny,
                billingCycleLabel = deal.billingCycleLabel.ifBlank { extracted.billingCycle.label },
                cpuCores = deal.cpuCores ?: extracted.cpuCores,
                ramText = deal.ramText ?: extracted.ramText,
                storageText = deal.storageText ?: extracted.storageText,
                trafficText = deal.trafficText ?: extracted.trafficText,
                portSpeedText = deal.portSpeedText ?: extracted.portSpeedText,
                regionTags = deal.regionTags.ifEmpty { extracted.regionTags },
                routeTags = deal.routeTags.ifEmpty { extracted.routeTags },
                promoCode = deal.promoCode ?: extracted.promoCode,
                expiresAtEpochMillis = deal.expiresAtEpochMillis ?: extracted.expiresAtEpochMillis,
            )
        }
        return deal
    }

    /**
     * Converts `(amount, currency, cycle)` to `(annualizedUsd, annualizedCny)` using [fxRates].
     */
    fun annualizePrice(
        amount: BigDecimal,
        currency: ValuationCurrency,
        cycle: DealBillingCycle,
        fxRates: FxRatesState = defaultFxRatesState(),
    ): Pair<BigDecimal?, BigDecimal?> {
        val annualOriginal = cycle.annualize(amount) ?: return null to null
        val rateToCny = fxRates.rateFor(currency)
        val usdToCny = fxRates.rateFor(ValuationCurrency.USD)
        if (rateToCny <= BigDecimal.ZERO || usdToCny <= BigDecimal.ZERO) return null to null

        val annualCnyPrecise = annualOriginal.multiply(rateToCny)
        val annualCny = annualCnyPrecise.setScale(2, RoundingMode.HALF_UP)
        val annualUsd = if (currency == ValuationCurrency.USD) {
            annualOriginal.setScale(2, RoundingMode.HALF_UP)
        } else {
            annualCnyPrecise.divide(usdToCny, 2, RoundingMode.HALF_UP)
        }
        return annualUsd to annualCny
    }

    // ── Internal Price Extraction ─────────────────────────────────────────────

    private data class PriceCandidate(
        val rawText: String,
        val amount: BigDecimal,
        val currency: ValuationCurrency,
        val cycle: DealBillingCycle,
        val score: Int,
    )

    private fun extractBestPrice(
        titleText: String,
        bodyText: String,
        combinedText: String,
    ): PriceCandidate? {
        val candidates = mutableListOf<PriceCandidate>()
        collectPriceCandidates(titleText, baseScore = 40, out = candidates)
        if (bodyText.isNotBlank()) {
            collectPriceCandidates(bodyText, baseScore = 10, out = candidates)
        }
        if (candidates.isEmpty()) return null

        val inferredFallbackCycle = inferContextBillingCycle(combinedText)
        val resolved = candidates.map { candidate ->
            if (candidate.cycle == DealBillingCycle.UNKNOWN && inferredFallbackCycle != DealBillingCycle.UNKNOWN) {
                candidate.copy(
                    cycle = inferredFallbackCycle,
                    score = candidate.score + 5,
                )
            } else {
                candidate
            }
        }

        return resolved
            .sortedWith(
                compareByDescending<PriceCandidate> { it.score }
                    .thenBy { it.amount },
            )
            .firstOrNull()
    }

    private fun collectPriceCandidates(
        text: String,
        baseScore: Int,
        out: MutableList<PriceCandidate>,
    ) {
        if (text.isBlank()) return

        // Pattern 4 first: Cycle-before-price (`年付 $14.99`, `年付19.9刀`, `三年付29.9刀`)
        CYCLE_FIRST_PRICE_REGEX.findAll(text).forEach { match ->
            val cycle = parseCycleToken(match.groupValues[1])
            val prefixCur = match.groupValues[2]
            val prefixAmt = match.groupValues[3]
            val suffixAmt = match.groupValues[4]
            val suffixCur = match.groupValues[5]

            val rawAmt = prefixAmt.ifBlank { suffixAmt }
            val rawCur = prefixCur.ifBlank { suffixCur }
            val amount = rawAmt.toBigDecimalOrNull() ?: return@forEach
            val currency = resolveCurrency(rawCur, text, match.range) ?: return@forEach
            if (!isPlausibleVpsPrice(amount, currency)) return@forEach

            val score = baseScore + 30 + if (cycle.isAnnualOrLonger) 6 else 2
            out += PriceCandidate(
                rawText = match.value.trim(),
                amount = amount,
                currency = currency,
                cycle = cycle,
                score = score,
            )
        }

        // Pattern 1: Prefix currency + amount + optional cycle (`$10.99/yr`, `€12/year`, `¥99/年`)
        PREFIX_CURRENCY_PRICE_REGEX.findAll(text).forEach { match ->
            val rawCur = match.groupValues[1]
            val rawAmt = match.groupValues[2]
            val rawCycle = match.groupValues[3].ifBlank { match.groupValues[4] }
            val amount = rawAmt.toBigDecimalOrNull() ?: return@forEach
            val currency = resolveCurrency(rawCur, text, match.range) ?: return@forEach
            if (!isPlausibleVpsPrice(amount, currency)) return@forEach

            val cycle = parseCycleToken(rawCycle)
            val score = baseScore + (if (cycle != DealBillingCycle.UNKNOWN) 28 else 8) +
                (if (cycle.isAnnualOrLonger) 6 else 0)
            out += PriceCandidate(
                rawText = match.value.trim(),
                amount = amount,
                currency = currency,
                cycle = cycle,
                score = score,
            )
        }

        // Pattern 2: Amount + suffix currency (`19.9刀/年`, `99元/年`, `12欧/年`, `15 USD/yr`)
        SUFFIX_CURRENCY_PRICE_REGEX.findAll(text).forEach { match ->
            val rawAmt = match.groupValues[1]
            val rawCur = match.groupValues[2]
            val rawCycle = match.groupValues[3].ifBlank { match.groupValues[4] }
            val amount = rawAmt.toBigDecimalOrNull() ?: return@forEach
            val currency = resolveCurrency(rawCur, text, match.range) ?: return@forEach
            if (!isPlausibleVpsPrice(amount, currency)) return@forEach

            val cycle = parseCycleToken(rawCycle)
            val score = baseScore + (if (cycle != DealBillingCycle.UNKNOWN) 26 else 6) +
                (if (cycle.isAnnualOrLonger) 6 else 0)
            out += PriceCandidate(
                rawText = match.value.trim(),
                amount = amount,
                currency = currency,
                cycle = cycle,
                score = score,
            )
        }

        // Pattern 3: Slang (`99r/年`, `19.9u/年`)
        SLANG_CURRENCY_PRICE_REGEX.findAll(text).forEach { match ->
            val rawAmt = match.groupValues[1]
            val rawCur = match.groupValues[2]
            val rawCycle = match.groupValues[3].ifBlank { match.groupValues[4] }
            val amount = rawAmt.toBigDecimalOrNull() ?: return@forEach
            val currency = resolveCurrency(rawCur, text, match.range) ?: return@forEach
            if (!isPlausibleVpsPrice(amount, currency)) return@forEach

            val cycle = parseCycleToken(rawCycle)
            val score = baseScore + (if (cycle != DealBillingCycle.UNKNOWN) 24 else 4)
            out += PriceCandidate(
                rawText = match.value.trim(),
                amount = amount,
                currency = currency,
                cycle = cycle,
                score = score,
            )
        }
    }

    private fun resolveCurrency(
        token: String,
        fullText: String,
        range: IntRange,
    ): ValuationCurrency? {
        return when (token.trim().uppercase(Locale.ROOT)) {
            "$", "US$", "USD$", "USD", "刀", "美刀", "美元", "U" -> ValuationCurrency.USD
            "€", "EUR", "欧", "欧元" -> ValuationCurrency.EUR
            "£", "GBP", "镑", "英镑" -> ValuationCurrency.GBP
            "HK$", "HKD", "港币", "港元" -> ValuationCurrency.HKD
            "S$", "SG$", "SGD" -> ValuationCurrency.SGD
            "CA$", "C$", "CAD" -> ValuationCurrency.CAD
            "AU$", "A$", "AUD" -> ValuationCurrency.AUD
            "NT$", "TWD" -> ValuationCurrency.TWD
            "JPY", "日元", "円" -> ValuationCurrency.JPY
            "CNY", "RMB", "人民币", "元", "块", "R" -> ValuationCurrency.CNY
            "¥", "￥" -> {
                val windowStart = (range.first - 16).coerceAtLeast(0)
                val windowEnd = (range.last + 20).coerceAtMost(fullText.length)
                val window = fullText.substring(windowStart, windowEnd)
                if (window.contains("JPY", ignoreCase = true) ||
                    window.contains("日元") ||
                    window.contains("日币") ||
                    window.contains("円")
                ) {
                    ValuationCurrency.JPY
                } else {
                    ValuationCurrency.CNY
                }
            }
            else -> null
        }
    }

    private fun parseCycleToken(token: String?): DealBillingCycle {
        val t = token?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (t.isBlank()) return DealBillingCycle.UNKNOWN
        return when {
            t.contains("triennial") || t.contains("三年") || Regex("""^3\s*(?:yr|year|y|年)""").containsMatchIn(t) ->
                DealBillingCycle.TRIENNIAL
            t.contains("biennial") || t.contains("两年") || Regex("""^2\s*(?:yr|year|y|年)""").containsMatchIn(t) ->
                DealBillingCycle.BIENNIAL
            t.contains("semi") || t.contains("半年") || t.contains("6") ->
                DealBillingCycle.SEMI_ANNUAL
            t.contains("quarter") || t.contains("qtr") || t.contains("季") || t.contains("3个") || t.contains("3mo") ->
                DealBillingCycle.QUARTERLY
            t.contains("yr") || t.contains("year") || t.contains("annual") || t.contains("年") || t == "y" ->
                DealBillingCycle.ANNUAL
            t.contains("mo") || t.contains("month") || t.contains("月") || t == "m" ->
                DealBillingCycle.MONTHLY
            else -> DealBillingCycle.UNKNOWN
        }
    }

    private fun inferContextBillingCycle(text: String): DealBillingCycle {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("三年付") || lower.contains("3年付") || lower.contains("triennial") -> DealBillingCycle.TRIENNIAL
            lower.contains("两年付") || lower.contains("2年付") || lower.contains("biennial") -> DealBillingCycle.BIENNIAL
            lower.contains("半年付") || lower.contains("semi-annual") || lower.contains("semiannual") -> DealBillingCycle.SEMI_ANNUAL
            lower.contains("年付") || lower.contains("annual") || lower.contains("yearly") -> DealBillingCycle.ANNUAL
            lower.contains("季付") || lower.contains("quarterly") -> DealBillingCycle.QUARTERLY
            lower.contains("月付") || lower.contains("monthly") -> DealBillingCycle.MONTHLY
            else -> DealBillingCycle.UNKNOWN
        }
    }

    private fun isPlausibleVpsPrice(amount: BigDecimal, currency: ValuationCurrency): Boolean {
        if (amount <= BigDecimal("0.09")) return false
        val maxReasonable = when (currency) {
            ValuationCurrency.JPY, ValuationCurrency.KRW -> BigDecimal("500000")
            ValuationCurrency.CNY, ValuationCurrency.HKD, ValuationCurrency.TWD -> BigDecimal("50000")
            else -> BigDecimal("5000")
        }
        return amount <= maxReasonable
    }

    // ── Internal Specs Extraction ─────────────────────────────────────────────

    private data class ExtractedSpecs(
        val cpuCores: Int? = null,
        val ramText: String? = null,
        val storageText: String? = null,
        val trafficText: String? = null,
        val portSpeedText: String? = null,
    )

    private fun extractSpecs(titleText: String, bodyText: String): ExtractedSpecs {
        val combined = if (bodyText.isBlank()) titleText else "$titleText\n$bodyText"

        var cpuCores: Int? = null
        var ramText: String? = null
        var storageText: String? = null
        var trafficText: String? = null
        var portSpeedText: String? = null

        // 1. Compact combo (`1C1G`, `2C4G`, `1C/1G/20G NVMe`, `2核4G`)
        val compactMatch = COMPACT_SPEC_REGEX.find(titleText) ?: COMPACT_SPEC_REGEX.find(bodyText)
        if (compactMatch != null) {
            val c = compactMatch.groupValues[1].toIntOrNull()
            val ramVal = compactMatch.groupValues[2]
            val ramUnit = compactMatch.groupValues[3]
            if (c != null && c in 1..128) {
                cpuCores = c
            }
            ramText = formatSizeCompact(ramVal, ramUnit)

            val diskVal = compactMatch.groupValues[4]
            val diskUnit = compactMatch.groupValues[5]
            val diskType = compactMatch.groupValues[6]
            if (diskVal.isNotBlank() && diskUnit.isNotBlank()) {
                val baseDisk = formatSizeCompact(diskVal, diskUnit)
                storageText = if (diskType.isNotBlank()) "$baseDisk ${normalizeDiskType(diskType)}" else baseDisk
            }
            val compactTrafficVal = compactMatch.groupValues[7]
            val compactTrafficUnit = compactMatch.groupValues[8]
            if (compactTrafficVal.isNotBlank() && compactTrafficUnit.isNotBlank()) {
                trafficText = formatSizeCompact(compactTrafficVal, compactTrafficUnit)
            }
        }

        // 2. Explicit CPU
        if (cpuCores == null) {
            val m = CPU_EXPLICIT_REGEX.find(titleText)
                ?: CPU_LABEL_FIRST_REGEX.find(titleText)
                ?: CPU_EXPLICIT_REGEX.find(bodyText)
                ?: CPU_LABEL_FIRST_REGEX.find(bodyText)
            val parsed = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (parsed != null && parsed in 1..256) {
                cpuCores = parsed
            }
        }

        // 3. Explicit RAM
        if (ramText == null) {
            val m = RAM_EXPLICIT_REGEX.find(titleText)
                ?: RAM_LABEL_FIRST_REGEX.find(titleText)
                ?: RAM_EXPLICIT_REGEX.find(bodyText)
                ?: RAM_LABEL_FIRST_REGEX.find(bodyText)
            if (m != null) {
                ramText = formatSizeCompact(m.groupValues[1], m.groupValues[2])
            }
        }

        // 4. Explicit Storage (upgrade compact storage if explicit has NVMe/SSD type)
        val explicitStorageMatch = STORAGE_EXPLICIT_REGEX.find(titleText)
            ?: STORAGE_LABEL_FIRST_REGEX.find(titleText)
            ?: STORAGE_EXPLICIT_REGEX.find(bodyText)
            ?: STORAGE_LABEL_FIRST_REGEX.find(bodyText)
        if (explicitStorageMatch != null) {
            val size = formatSizeCompact(explicitStorageMatch.groupValues[1], explicitStorageMatch.groupValues[2])
            val kind = explicitStorageMatch.groupValues.getOrNull(3).orEmpty()
            val normalizedKind = normalizeDiskType(kind)
            val formatted = if (normalizedKind.isNotBlank()) "$size $normalizedKind" else size
            if (storageText == null || (!storageText.contains("NVMe") && !storageText.contains("SSD") && normalizedKind.isNotBlank())) {
                storageText = formatted
            }
        }

        // 5. Traffic @ Port combo (`1TB@1Gbps`, `2T@10G`)
        val trafficAtPort = TRAFFIC_AT_PORT_REGEX.find(titleText) ?: TRAFFIC_AT_PORT_REGEX.find(bodyText)
        if (trafficAtPort != null) {
            trafficText = formatSizeCompact(trafficAtPort.groupValues[1], trafficAtPort.groupValues[2])
            portSpeedText = formatPortSpeed(trafficAtPort.groupValues[3], trafficAtPort.groupValues[4])
        }

        // 6. Explicit Traffic
        if (trafficText == null) {
            val labelMatch = TRAFFIC_LABEL_FIRST_REGEX.find(combined)
            if (labelMatch != null) {
                trafficText = if (labelMatch.groupValues[1].isNotBlank()) {
                    "不限流量"
                } else {
                    formatSizeCompact(labelMatch.groupValues[2], labelMatch.groupValues[3])
                }
            } else {
                val explicitTraffic = TRAFFIC_EXPLICIT_REGEX.find(titleText) ?: TRAFFIC_EXPLICIT_REGEX.find(bodyText)
                if (explicitTraffic != null) {
                    trafficText = formatSizeCompact(explicitTraffic.groupValues[1], explicitTraffic.groupValues[2])
                } else if (UNMETERED_TRAFFIC_REGEX.containsMatchIn(combined)) {
                    trafficText = "不限流量"
                }
            }
        }

        // 7. Explicit Port Speed
        if (portSpeedText == null) {
            val portMatch = PORT_SPEED_EXPLICIT_REGEX.find(titleText)
                ?: PORT_LABEL_FIRST_REGEX.find(titleText)
                ?: PORT_SPEED_EXPLICIT_REGEX.find(bodyText)
                ?: PORT_LABEL_FIRST_REGEX.find(bodyText)
            if (portMatch != null) {
                portSpeedText = formatPortSpeed(portMatch.groupValues[1], portMatch.groupValues[2])
            }
        }

        return ExtractedSpecs(
            cpuCores = cpuCores,
            ramText = ramText,
            storageText = storageText,
            trafficText = trafficText,
            portSpeedText = portSpeedText,
        )
    }

    private fun formatSizeCompact(numStr: String, unitRaw: String): String {
        val num = numStr.toDoubleOrNull() ?: return "$numStr${unitRaw.uppercase(Locale.ROOT)}"
        val unitUpper = unitRaw.trim().uppercase(Locale.ROOT)
        return when {
            unitUpper.startsWith("T") -> "${formatCleanNumber(num)}T"
            unitUpper.startsWith("G") -> "${formatCleanNumber(num)}G"
            unitUpper.startsWith("M") -> {
                if (num >= 1024.0 && num % 1024.0 == 0.0) {
                    "${(num / 1024.0).toInt()}G"
                } else {
                    "${formatCleanNumber(num)}M"
                }
            }
            else -> "${formatCleanNumber(num)}$unitUpper"
        }
    }

    private fun normalizeDiskType(raw: String): String {
        val upper = raw.trim().uppercase(Locale.ROOT)
        return when {
            upper.contains("NVME") -> "NVMe"
            upper.contains("SSD") -> "SSD"
            upper.contains("HDD") || upper.contains("SATA") || upper.contains("SAS") -> "HDD"
            else -> ""
        }
    }

    private fun formatPortSpeed(numStr: String, unitRaw: String): String {
        val num = numStr.toDoubleOrNull() ?: return "$numStr$unitRaw"
        val u = unitRaw.trim().uppercase(Locale.ROOT)
        return when {
            u.startsWith("G") -> "${formatCleanNumber(num)}Gbps"
            u.startsWith("M") -> {
                if (num >= 1000.0 && num % 1000.0 == 0.0) {
                    "${(num / 1000.0).toInt()}Gbps"
                } else {
                    "${formatCleanNumber(num)}Mbps"
                }
            }
            else -> "${formatCleanNumber(num)}$unitRaw"
        }
    }

    private fun formatCleanNumber(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        }
    }

    // ── Internal Region & Route Extraction ────────────────────────────────────

    private val REGION_PATTERNS: List<Pair<String, Regex>> = listOf(
        DealTags.REGION_HK to Regex(
            """香港|港鸡|Hong\s*Kong|Equinix\s*HK|Tseung\s*Kwan\s*O|(?<![A-Za-z0-9])(?:HKG|TKO)(?![A-Za-z0-9])|(?<![A-Za-z0-9])HK(?![A-Za-z0-9])""",
        ),
        DealTags.REGION_HK to Regex("""hong\s*kong|(?<![a-z0-9])hkg(?![a-z0-9])""", RegexOption.IGNORE_CASE),
        DealTags.REGION_JP to Regex(
            """日本|东京|大阪|日鸡|Tokyo|Osaka|Japan|(?<![A-Za-z0-9])(?:TYO|NRT|KIX|OSA|JP)(?![A-Za-z0-9])""",
        ),
        DealTags.REGION_JP to Regex("""tokyo|osaka|japan|(?<![a-z0-9])(?:tyo|nrt|kix)(?![a-z0-9])""", RegexOption.IGNORE_CASE),
        DealTags.REGION_SG to Regex(
            """新加坡|狮城|坡鸡|Singapore|(?<![A-Za-z0-9])(?:SGP|SIN|SG)(?![A-Za-z0-9])""",
        ),
        DealTags.REGION_SG to Regex("""singapore|(?<![a-z0-9])(?:sgp|sin)(?![a-z0-9])""", RegexOption.IGNORE_CASE),
        DealTags.REGION_US_WEST to Regex(
            """美西|洛杉矶|圣何塞|西雅图|旧金山|硅谷|波特兰|凤凰城|拉斯维加斯|加州|弗里蒙特|Los\s*Angeles|San\s*Jose|Seattle|San\s*Francisco|Silicon\s*Valley|Fremont|Portland|Phoenix|Las\s*Vegas|California|US[\s\-]?West|MegaBox|(?<![A-Za-z0-9])(?:LAX|SJC|SEA|SFO|PDX|PHX|LAS|DC6|DC9)(?![A-Za-z0-9])""",
            RegexOption.IGNORE_CASE,
        ),
        DealTags.REGION_US_EAST to Regex(
            """美东|纽约|新泽西|迈阿密|亚特兰大|芝加哥|达拉斯|阿什本|水牛城|New\s*York|New\s*Jersey|Miami|Atlanta|Chicago|Dallas|Ashburn|Buffalo|Virginia|US[\s\-]?East|(?<![A-Za-z0-9])(?:NYC|EWR|JFK|MIA|ATL|ORD|DFW|IAD|BUF)(?![A-Za-z0-9])""",
            RegexOption.IGNORE_CASE,
        ),
        DealTags.REGION_EU to Regex(
            """欧洲|德国|法兰克福|荷兰|阿姆斯特丹|英国|伦敦|法国|巴黎|卢森堡|瑞士|苏黎世|芬兰|赫尔辛基|波兰|瑞典|奥地利|罗马尼亚|Germany|Frankfurt|Netherlands|Amsterdam|United\s*Kingdom|London|France|Paris|Luxembourg|Zurich|Finland|Helsinki|Falkenstein|Nuremberg|Europe|(?<![A-Za-z0-9])(?:FRA|AMS|LON|LHR|CDG|LUX|ZRH|HEL)(?![A-Za-z0-9])""",
            RegexOption.IGNORE_CASE,
        ),
        // Case-sensitive 2-letter EU ISO codes so lowercase English words like "de"/"fr"/"eu" never false-match
        DealTags.REGION_EU to Regex("""(?<![A-Za-z0-9])(?:DE|NL|UK|FR|EU)(?![A-Za-z0-9])"""),
        DealTags.REGION_OTHER to Regex(
            """韩国|首尔|台湾|台北|澳大利亚|悉尼|加拿大|多伦多|温哥华|马来西亚|吉隆坡|泰国|印度|越南|俄罗斯|迪拜|巴西|Seoul|Korea|Taiwan|Taipei|Sydney|Australia|Canada|Toronto|Vancouver|Malaysia|India|Dubai|Brazil""",
            RegexOption.IGNORE_CASE,
        ),
    )

    private fun extractRegionTags(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val matched = LinkedHashSet<String>()
        for ((tag, regex) in REGION_PATTERNS) {
            if (regex.containsMatchIn(text)) {
                matched += tag
            }
        }
        return DealTags.ALL_REGIONS.filter { it in matched }
    }

    private val ROUTE_PATTERNS: List<Pair<String, Regex>> = listOf(
        DealTags.ROUTE_CN2_GIA to Regex(
            """CN2[\s\-]*GIA|CN2GIA|AS4809|(?:三网|回程|电信|精品)\s*GIA""",
            RegexOption.IGNORE_CASE,
        ),
        DealTags.ROUTE_9929 to Regex(
            """(?<![0-9])9929(?![0-9])|AS9929|(?<![A-Za-z])CUII(?![A-Za-z])|CU9929""",
            RegexOption.IGNORE_CASE,
        ),
        DealTags.ROUTE_CMIN2 to Regex(
            """CMIN2|AS58807""",
            RegexOption.IGNORE_CASE,
        ),
        DealTags.ROUTE_IIJ to Regex(
            """(?<![A-Za-z0-9])IIJ(?![A-Za-z0-9])|AS2497""",
            RegexOption.IGNORE_CASE,
        ),
        DealTags.ROUTE_SOFTBANK to Regex(
            """软银|SoftBank|BBTEC|AS17676""",
            RegexOption.IGNORE_CASE,
        ),
        DealTags.ROUTE_BGP to Regex(
            """(?<![A-Za-z0-9])BGP(?![A-Za-z0-9])""",
            RegexOption.IGNORE_CASE,
        ),
    )

    private fun extractRouteTags(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val matched = LinkedHashSet<String>()
        for ((tag, regex) in ROUTE_PATTERNS) {
            if (regex.containsMatchIn(text)) {
                matched += tag
            }
        }
        return DealTags.ALL_ROUTES.filter { it in matched }
    }

    // ── Internal Promo Code Extraction ────────────────────────────────────────

    private fun extractPromoCode(text: String): String? {
        if (text.isBlank()) return null
        val matches = sequence {
            yieldAll(PROMO_CODE_ZH_REGEX.findAll(text))
            yieldAll(PROMO_CODE_EN_REGEX.findAll(text))
        }
        for (match in matches) {
            val candidate = match.groupValues[1].trim().trim('*', '`', '"', '\'', '.', ',', ';')
            if (isValidPromoCode(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun isValidPromoCode(code: String): Boolean {
        if (code.length !in 3..32) return false
        val lower = code.lowercase(Locale.ROOT)
        if (lower in PROMO_CODE_STOP_WORDS) return false
        if (code.all { it.isDigit() } && code.length < 4) return false
        // Require at least one letter or digit, and not pure lowercase common English word unless >=6 chars or has digits/dash
        val hasDigitOrUpperOrSymbol = code.any { it.isDigit() || it.isUpperCase() || it == '-' || it == '_' }
        return hasDigitOrUpperOrSymbol || code.length >= 6
    }

    // ── Internal Order URL Extraction ─────────────────────────────────────────

    private data class UrlCandidate(
        val url: String,
        val score: Int,
    )

    fun extractBestOrderUrl(text: String, fallbackUrl: String? = null): String? {
        val candidates = mutableListOf<UrlCandidate>()
        val seen = HashSet<String>()

        fun consider(rawUrl: String, anchorText: String) {
            val cleaned = cleanUrl(rawUrl) ?: return
            val unwrapped = cleanUrl(unwrapNodeSeekJumpUrl(cleaned)) ?: cleaned
            if (!seen.add(unwrapped)) return
            val score = scoreOrderUrl(unwrapped, anchorText)
            if (score > 0) {
                candidates += UrlCandidate(url = unwrapped, score = score)
            }
        }

        MARKDOWN_LINK_REGEX.findAll(text).forEach { match ->
            consider(rawUrl = match.groupValues[2], anchorText = match.groupValues[1])
        }
        HTML_LINK_REGEX.findAll(text).forEach { match ->
            consider(rawUrl = match.groupValues[1], anchorText = match.groupValues[2])
        }
        BARE_URL_REGEX.findAll(text).forEach { match ->
            consider(rawUrl = match.value, anchorText = "")
        }

        val best = candidates.maxByOrNull { it.score }?.url
        if (best != null) return best
        return fallbackUrl?.let(::cleanUrl)?.let(::unwrapNodeSeekJumpUrl)
    }

    private fun cleanUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('.', ',', ';', ')', ']', '>', '"', '\'', '。', '，', '；', '`')
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return trimmed
    }

    private fun scoreOrderUrl(url: String, anchorText: String): Int {
        val lowerUrl = url.lowercase(Locale.ROOT)
        val pathWithoutQuery = lowerUrl.substringBefore('?').substringBefore('#')
        if (IMAGE_EXTENSIONS.any { pathWithoutQuery.endsWith(it) }) return 0

        val host = lowerUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isBlank()) return 0
        if (NON_ORDER_HOST_KEYWORDS.any { host == it || host.endsWith(".$it") }) return 0

        var score = 2
        if (ORDER_URL_STRONG_HINTS.any { lowerUrl.contains(it) }) {
            score += 10
        }
        val lowerAnchor = anchorText.lowercase(Locale.ROOT)
        if (lowerAnchor.isNotBlank() && ORDER_ANCHOR_HINTS.any { lowerAnchor.contains(it) }) {
            score += 5
        }
        return score
    }

    // ── Internal Merchant Name Extraction ─────────────────────────────────────

    private fun extractMerchantName(
        title: String,
        body: String,
        orderUrl: String?,
    ): String {
        for (alias in KNOWN_MERCHANTS) {
            if (alias.pattern.containsMatchIn(title)) {
                return alias.canonical
            }
        }
        if (body.isNotBlank()) {
            for (alias in KNOWN_MERCHANTS) {
                if (alias.pattern.containsMatchIn(body)) {
                    return alias.canonical
                }
            }
        }
        val bracketMatch = BRACKET_PREFIX_REGEX.find(title)
        if (bracketMatch != null) {
            val tag = bracketMatch.groupValues[1].trim()
            if (tag.lowercase(Locale.ROOT) !in GENERIC_BRACKET_TAGS && !tag.any { it == '$' || it == '¥' || it == '€' }) {
                return tag
            }
        }
        if (!orderUrl.isNullOrBlank()) {
            val host = orderUrl.lowercase(Locale.ROOT)
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')
            val parts = host.split('.').filter { it.isNotBlank() }
            val domainCore = when {
                parts.size >= 3 && parts[0] in setOf("www", "my", "billing", "client", "clients", "portal", "panel", "shop", "store", "order", "manage", "app", "console") ->
                    parts[1]
                parts.size >= 2 -> parts[parts.size - 2]
                else -> parts.firstOrNull().orEmpty()
            }
            if (domainCore.length >= 2 && domainCore !in setOf("co", "com", "net", "org", "gov", "edu")) {
                return domainCore.replaceFirstChar { it.uppercaseChar() }
            }
        }
        return ""
    }

    // ── Internal Expiry / Deadline Extraction ─────────────────────────────────

    private fun extractExpiryEpochMillis(
        text: String,
        referenceEpochMillis: Long,
    ): Long? {
        if (text.isBlank()) return null
        val refZdt = Instant.ofEpochMilli(referenceEpochMillis).atZone(SHANGHAI_ZONE)

        // 1. Full YYYY-MM-DD (optional HH:mm)
        DEADLINE_FULL_DATE_REGEX.find(text)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return@let
            val month = m.groupValues[2].toIntOrNull() ?: return@let
            val day = m.groupValues[3].toIntOrNull() ?: return@let
            val hour = m.groupValues[4].toIntOrNull() ?: 23
            val minute = m.groupValues[5].toIntOrNull() ?: 59
            val second = if (m.groupValues[4].isBlank()) 59 else 0
            return buildSafeZonedMillis(year, month, day, hour, minute, second)
        }

        // 2. English month name (`until Nov 30`, `expires December 2, 2026`)
        DEADLINE_EN_MONTH_REGEX.find(text)?.let { m ->
            val month = parseEnglishMonth(m.groupValues[1]) ?: return@let
            val day = m.groupValues[2].toIntOrNull() ?: return@let
            val explicitYear = m.groupValues[3].toIntOrNull()
            val resolvedYear = explicitYear ?: inferYearForMonthDay(refZdt.toLocalDate(), month, day)
            return buildSafeZonedMillis(resolvedYear, month, day, 23, 59, 59)
        }

        // 3. Short MM-DD / MM月DD日 with deadline keyword
        DEADLINE_MD_DATE_REGEX.find(text)?.let { m ->
            val month = m.groupValues[1].toIntOrNull() ?: return@let
            val day = m.groupValues[2].toIntOrNull() ?: return@let
            val hour = m.groupValues[3].toIntOrNull() ?: 23
            val minute = m.groupValues[4].toIntOrNull() ?: 59
            val second = if (m.groupValues[3].isBlank()) 59 else 0
            val resolvedYear = inferYearForMonthDay(refZdt.toLocalDate(), month, day)
            return buildSafeZonedMillis(resolvedYear, month, day, hour, minute, second)
        }

        // 4. Relative duration (`限时 24 小时`, `48h flash sale`)
        DEADLINE_RELATIVE_REGEX.find(text)?.let { m ->
            val rawQty = m.groupValues[1].ifBlank { m.groupValues[3] }
            val rawUnit = m.groupValues[2].ifBlank { m.groupValues[4] }.lowercase(Locale.ROOT)
            val qty = rawQty.toLongOrNull()?.takeIf { it in 1L..365L } ?: return@let
            val millis = when {
                rawUnit.startsWith("天") || rawUnit.startsWith("day") -> qty * 24L * 3_600_000L
                else -> qty * 3_600_000L
            }
            return referenceEpochMillis + millis
        }

        return null
    }

    private fun parseEnglishMonth(token: String): Int? = when (token.lowercase(Locale.ROOT).take(3)) {
        "jan" -> Month.JANUARY.value
        "feb" -> Month.FEBRUARY.value
        "mar" -> Month.MARCH.value
        "apr" -> Month.APRIL.value
        "may" -> Month.MAY.value
        "jun" -> Month.JUNE.value
        "jul" -> Month.JULY.value
        "aug" -> Month.AUGUST.value
        "sep" -> Month.SEPTEMBER.value
        "oct" -> Month.OCTOBER.value
        "nov" -> Month.NOVEMBER.value
        "dec" -> Month.DECEMBER.value
        else -> null
    }

    private fun inferYearForMonthDay(refDate: LocalDate, month: Int, day: Int): Int {
        if (month !in 1..12 || day !in 1..31) return refDate.year
        // Handle December -> January wraparound gracefully
        return if (refDate.monthValue >= 10 && month <= 2) {
            refDate.year + 1
        } else {
            refDate.year
        }
    }

    private fun buildSafeZonedMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long? = runCatching {
        val date = LocalDate.of(year, month, day)
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59), second.coerceIn(0, 59))
        ZonedDateTime.of(date, time, SHANGHAI_ZONE).toInstant().toEpochMilli()
    }.getOrNull()

    // ── Internal Out-of-Stock & Text Cleanup ──────────────────────────────────

    private fun detectOutOfStockHint(title: String, body: String): Boolean {
        if (OOS_STRONG_REGEX.containsMatchIn(title)) {
            return true
        }
        if (IN_STOCK_RESTOCK_REGEX.containsMatchIn(title)) {
            return false
        }
        // Check first 320 chars of body for explicit OOS/ended notices
        val head = body.take(320)
        return OOS_STRONG_REGEX.containsMatchIn(head) && !IN_STOCK_RESTOCK_REGEX.containsMatchIn(head)
    }

    private fun stripUrlsAndMarkdownSyntax(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("""!\[[^\]]*]\([^)]+\)"""), " ")
            .replace(MARKDOWN_LINK_REGEX) { it.groupValues[1] }
            .replace(HTML_LINK_REGEX) { it.groupValues[2] }
            .replace(BARE_URL_REGEX, " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""[*_~`#>|]+"""), " ")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
    }

    private fun buildCleanSummary(body: String, title: String): String {
        if (body.isBlank()) return ""
        val cleaned = stripUrlsAndMarkdownSyntax(body)
            .lines()
            .map { it.trim() }
            .filter { it.length >= 4 && !it.equals(title.trim(), ignoreCase = true) }
            .take(3)
            .joinToString(" · ")
        return if (cleaned.length > 140) {
            cleaned.take(139).trimEnd() + "…"
        } else {
            cleaned
        }
    }

    /**
     * Parses ISO-8601 / RFC / SQL timestamps commonly emitted by NodeSeek RSS & HTML feeds.
     */
    fun parseFlexibleTimestampMillis(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        runCatching { return Instant.parse(text).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        runCatching {
            val ldt = LocalDateTime.parse(text.replace(' ', 'T'))
            return ldt.atZone(SHANGHAI_ZONE).toInstant().toEpochMilli()
        }
        return null
    }
}
