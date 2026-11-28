package com.nodeseek.app.domain.valuation

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Renewal fee, currency, and billing cycle extracted from a trade listing title/body.
 */
data class ParsedRenewalInfo(
    val amount: BigDecimal,
    val currency: ValuationCurrency,
    val cycle: BillingCyclePreset?,
    val matchedExpression: String,
    val cycleExplicit: Boolean = cycle != null,
)

/**
 * Service expiry date and remaining day count extracted from a trade listing title/body.
 */
data class ParsedExpiryInfo(
    val expiryDate: LocalDate,
    val remainingDays: Int,
    val matchedExpression: String,
    /** True for month-only or month/year relative estimates (e.g. `12月到期`, `剩余半年`). */
    val isApproximate: Boolean = false,
)

/**
 * Total asking price in CNY extracted from a trade listing title/body.
 */
data class ParsedAskPrice(
    val amountCny: BigDecimal,
    val matchedExpression: String,
    val confidence: PricingConfidence = PricingConfidence.HIGH,
)

/**
 * Raw structured pricing signals extracted from a listing title and optional OP body snippet.
 */
data class ParsedListingDetails(
    val intent: TradeListingIntent,
    val isClosedSold: Boolean,
    val explicitSpread: ParsedTradeTitleSpread?,
    val askPrice: ParsedAskPrice?,
    val renewal: ParsedRenewalInfo?,
    val expiry: ParsedExpiryInfo?,
    val statedResidualCny: BigDecimal? = null,
)

/**
 * Unified pricing outcome for a matched trade listing:
 * - explicit spread ([hasSpread] = true, [isDerived] = false),
 * - derived spread (`ask - residual`, [hasSpread] = true, [isDerived] = true), or
 * - ask-price-only fallback ([isAskPriceOnly] = true, [hasSpread] = false, [hasAskPrice] = true).
 */
data class InferredListingPricing(
    val intent: TradeListingIntent,
    val isClosedSold: Boolean,
    val spreadDeltaCny: BigDecimal?,
    val spreadStatus: DealSpreadStatus?,
    val askPriceCny: BigDecimal?,
    val residualCny: BigDecimal?,
    val isDerived: Boolean,
    val confidence: PricingConfidence,
    val matchedExpression: String,
    val parsedDetails: ParsedListingDetails,
    /** True when [askPriceCny] was derived from `residualCny + spreadDeltaCny` for an explicit-spread post. */
    val isAskDerived: Boolean = false,
) {
    val hasSpread: Boolean get() = spreadDeltaCny != null && spreadStatus != null
    val hasAskPrice: Boolean get() = askPriceCny != null
    val hasBothTotalAndSpread: Boolean get() = hasSpread && hasAskPrice
    val isAskPriceOnly: Boolean get() = !hasSpread && hasAskPrice
}

/**
 * Parses concise and full-form NodeSeek VPS trade listings (title + optional OP body snippet)
 * to extract asking prices, renewal pricing/cycles, expiry dates, explicit spreads, and
 * derived spreads (`ask - residual`, delegating pro-rated residual math to [VpsValuationEngine]).
 */
object TradeListingPriceParser {

    private val MAX_REASONABLE_PRICE_CNY = BigDecimal("50000")
    private val MIN_BARE_ASK_CNY = BigDecimal("10")
    private val MAX_BARE_ASK_CNY = BigDecimal("20000")
    private val MAX_DIRECT_AFTER_CHU_SPREAD = BigDecimal("100")
    private const val MAX_DERIVED_REMAINING_DAYS = 3650
    private const val MAX_BODY_CHARS = 2_000

    private val KNOWN_NUMERIC_MODEL_CODES: Set<String> = setOf(
        "9929", "4837", "163", "4134", "4809",
        "2222", "2525", "1111", "3333", "4444", "5555", "6666", "7777", "8888", "9999",
        "618", "1212",
    )

    private val CLOSED_SOLD_REGEX = Regex(
        """(?:[\[【《「『(（]\s*(?:已出|已售|已成交|已收|sold|closed)\s*[\]】》」』)）]|(?:^|\s)(?:已出|已售|已成交|已收|秒出|出了)(?=\s|$|[，,。！!]))""",
        RegexOption.IGNORE_CASE,
    )
    private val NON_TRADE_SOLD_FALSE_POSITIVE_REGEX = Regex("""(?:公告|通知|教程|脚本|测评|新闻)\s*已出""")

    private val LEADING_BUY_REGEX = Regex(
        """^\s*(?:[\[【《「『(（]\s*(?:收|已收|求购|求|wtb)\s*[\]】》」』)）]|已收|\bwtb\b|求(?:大佬|老哥|好心人|各位)?(?:割爱|转|让|出)|(?:想|要|打算|准备)买(?:一台|一个|一只|个|只|台)?|买(?:一台|一个|一只|个|只)|求(?:一台|一个|一只|个|只|鸡)|(?:诚[心意]?|急|慢|高价|原价|溢价|折价|平价|长期|在线)?(?:收|求购|求收|想收|要收|蹲)(?:一台|一个|一只|个|只|台|\s|$|(?=[^\s费入益藏到录])))""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_SELL_REGEX = Regex(
        """^\s*(?:[\[【《「『(（]\s*(?:出|转|转让|已出|已售|已成交|售|wts|sold|closed)\s*[\]】》」』)）]|已出|已售|已成交|出了|\bwts\b|(?:诚[心意]?|急|慢|秒|自刀|打包|单|合|原价|平价|平|溢价|折价|低价|便宜|随缘|佛系|明盘|忍痛|割肉|退坑|脱坑|回血|清仓|吃灰)?(?:出|转|售|甩卖)(?:一台|一个|一只|个|只|台|鸡|\s|$|(?=[^\s现口发来错品席身生版差站墙狱发换移入])))""",
        RegexOption.IGNORE_CASE,
    )
    private val MULTI_BUNDLE_KEYWORD_REGEX = Regex(
        """(?:打包出|合出|打包\s*[:：]?\s*[¥￥]?\d+|清库存|全套出|整套出)""",
    )
    private val MULTI_BUNDLE_PLURAL_COUNT_REGEX = Regex(
        """(?:^|[出转收\s,，/|(\[【])[两二三四五六七八九十2-9]\s*(?:台|只)(?!\s*(?:单出|每台|各))""",
    )
    private val MULTI_BUNDLE_TOGETHER_PRICE_REGEX = Regex(
        """(?i)(?:一起|一共|合计|总计|统共|共)\s*[:：]?\s*[¥￥]?\s*\d+(?:\.\d+)?\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?""",
    )
    private val MULTI_BUNDLE_TOGETHER_SELL_REGEX = Regex("""一起出""")
    private val SINGLE_ITEM_TOGETHER_PREFIX_REGEX = Regex(
        """(?i)(?:原[油邮]|邮箱|账号|账户|余额|push\s*费?|过户费?)\s*$""",
    )
    private val STRONG_INLINE_BUY_REGEX = Regex(
        """(?:\bwtb\b|求购|求收|想收|要收|(?:想|要|打算|准备)买(?:一台|一个|一只|个|只|台)?|求(?:一台|一个|一只|个|只|鸡)|求(?:大佬|老哥|好心人|各位)?(?:割爱|转|让|出)|(?:诚[心意]?|长期|在线|急|慢)\s*蹲|蹲(?:一?[台个只枚]|个|只|台)|(?:原价|溢价|折价|高价|诚[心意]?|急|慢|平价|明盘\s*\d+(?:\.\d+)?\s*(?:元|块|r)?)\s*收(?![费入益藏到录])|收(?:一台|一个|一只|个|只)|预算\s*[:：约]?\s*[¥￥]?\s*\d+|(?:[¥￥]\s*\d+|\d+(?:\.\d+)?\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9])))\s*以内|有没有(?:大佬|老哥)?出|有人出.{0,12}吗|(?<![a-z0-9+\-])\d+(?:\.\d+)?\s*(?:元|块|r|cny|rmb)?\s*收(?![费入益藏到录]))""",
        RegexOption.IGNORE_CASE,
    )
    private val EXPLICIT_SOLD_SELL_REGEX = Regex(
        """(?:[\[【《「『(（]\s*(?:已出|已售|已成交|sold|closed)\s*[\]】》」』)）]|(?:^|\s)(?:已出|已售|已成交|出了)(?=\s|$|[，,。！!]))""",
        RegexOption.IGNORE_CASE,
    )
    private val INLINE_SELL_REGEX = Regex(
        """(?:\bwts\b|溢价出|折价出|平出|原价出|原价转|剩余价值|净残值|残值出|明盘|一口价|秒出|急出|慢出|已出|已售|已成交|出了|\[sold]|\[closed]|包\s*push|带原[油邮]|给原[油邮]|出让|转让|出售|甩卖|清仓|割肉|忍痛|退坑|脱坑|回血|(?:溢价|折价|加价|亏本?|血亏|让利|倒贴|低价|平价?)\s*[+\-＋－]?\s*\d*(?:\.\d+)?\s*(?:元|块|r|cny|rmb)?\s*(?:出(?![现口发来错品席身生版差站墙狱])|转(?![发换移入出])|卖|售|甩卖)|(?:清库存|退坑|吃灰|回血|闲置)?\s*出(?:[一两二三四五六七八九\d]+[台个只]|个|只|台)|(?<![a-z0-9+\-])\d+(?:\.\d+)?\s*(?:元|块|r|cny|rmb)?\s*(?:出(?![现口发来错品席身生版差站墙狱])|转(?![发换移入出])|甩卖))""",
        RegexOption.IGNORE_CASE,
    )
    private val TRADE_CONTEXT_HINT_REGEX = Regex(
        """(?i)(?:出|收|转|售|明盘|一口价|原油|原邮|改邮|push|续费|到期|传家宝|小鸡|vps|dmit|大妈|bwg|bwh|瓦工|搬瓦工|racknerd|rn|greencloud|绿云|spartan|斯巴达|claw|爪云|cloudcone|cc|netcup|nc|hetzner|hz|virmach|oracle|甲骨文|alice|爱丽丝|yxvm|v\.ps|akile|wap|zgo|rfc|misaka|御坂|saltyfish|咸鱼|kirino|sharon|halo|akko|cera|volc|火山|hosthatch|buyvm|卢森堡|ovh|kimsufi|contabo|vmiss|bage|hostdare|hytron|isif|ggc|vultr|linode|digitalocean|aliyun|阿里云|腾讯云|华为云|aws|gcp|azure)""",
    )

    // Markdown cleanup for body snippets
    private val FENCED_CODE_BLOCK_REGEX = Regex("""```[\s\S]*?```""")
    private val MARKDOWN_IMAGE_REGEX = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val INLINE_CODE_REGEX = Regex("""`([^`\n]+)`""")
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val MD_DECOR_REGEX = Regex("""[*_~>#]+""")

    // Common non-price scrubbers
    private val URL_REGEX = Regex("""(?:https?|nsapp)://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val EMAIL_REGEX = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
    private val IPV4_CIDR_REGEX = Regex("""\b\d{1,3}(?:\.(?:\d{1,3}|\*)){3}(?:/\d{1,2})?\b""")
    private val PORT_REGEX = Regex("""(?<=[A-Za-z0-9\]])(?::\d{2,5})\b""")
    private val ASN_REGEX = Regex("""(?i)\bAS\d+\b""")
    private val CPU_RAM_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:v?cpu|[ch核])\s*[/\-+]?\s*\d+(?:\.\d+)?\s*(?:gb|mb|g|m)(?:\s*[/\-+]?\s*\d+(?:\.\d+)?\s*(?:gb|mb|tb|g|m|t))*(?![A-Za-z0-9])""",
    )
    private val BANDWIDTH_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:[gm]bps|[gm]口子?)(?![A-Za-z0-9])""",
    )
    private val STORAGE_MEM_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:gb|mb|tb|kb|g|m|t)(?![a-z0-9])(?:\s*(?:月流量|年流量|流量|双向|单向|硬盘|内存|磁盘|带宽|口子?))?""",
    )
    private val PERCENT_REGEX = Regex("""\d+(?:\.\d+)?\s*%""")
    private val ZH_DISCOUNT_RATE_REGEX = Regex("""\d+(?:\.\d+)?\s*折(?![价])""")
    private val CARPOOL_RATIO_REGEX = Regex("""(?<![A-Za-z0-9])\d{1,2}\s*=\s*\d{1,2}(?![A-Za-z0-9])""")
    private val MODEL_CODE_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(?:(?:rs|vps|ax|ex|cx|cpx|cax|ccx|dc|ks|le|hk|sg|jp|us|s|g|v|t|p|c|e)[- ]?\d{1,4}(?:\.\d+)?|cn2|cmin2|ipv[46]|e[35]-\d{4}[a-z0-9]*|g\d+(?:\.\d+)?)(?![A-Za-z0-9])""",
    )
    private val DOUBLE_SHOPPING_DAY_REGEX = Regex("""双\s*(?:11|12|十一|十二)""")

    // Push fee, balance, middleman, daily cost, setup fee scrubbers
    // Note: "push 120r" (transfer method + CNY ask) must NOT be scrubbed; require explicit "费/费用" for CNY
    // or a foreign currency immediately after "push" (e.g. "push $8", "push费 30r", "过户费 20元").
    private val FEE_OR_BALANCE_PREFIX_REGEX = Regex(
        """(?i)(?:(?:(?:push|过户|转移|改邮|中介|初装|设置|安装|手续)\s*费用?|(?:带|含|送|账号|账户|自带)?\s*余额|持仓日均|日均|优惠券?|立减|再减)\s*(?:约|大概|为)?\s*[:：]?\s*(?:(?:us\$|hk\$|s\$|c\$|a\$|nt\$|r\$|\$|€|£|¥|￥|₽|₺)\s*\d+(?:\.\d+)?|\d+(?:\.\d+)?\s*(?:美刀|美元|美金|刀|欧元|欧(?![洲盟])|英镑|镑|港[币元]|日元|新币|加元|澳元|元|块|(?:usd|eur|gbp|hkd|jpy|sgd|cad|aud|cny|rmb|r|u)(?![a-z0-9]))?)|push\s*[:：]?\s*(?:(?:us\$|hk\$|s\$|c\$|a\$|nt\$|r\$|\$|€|£|₽|₺)\s*\d+(?:\.\d+)?|\d+(?:\.\d+)?\s*(?:美刀|美元|美金|刀|欧元|欧(?![洲盟])|英镑|镑|港[币元]|(?:usd|eur|gbp|hkd)(?![a-z0-9]))))""",
    )
    private val FEE_OR_BALANCE_SUFFIX_REGEX = Regex(
        """(?i)(?<![a-z0-9.])(?:(?:us\$|hk\$|s\$|c\$|a\$|nt\$|r\$|\$|€|£|¥|￥|₽|₺)\s*\d+(?:\.\d+)?|\d+(?:\.\d+)?\s*(?:美刀|美元|美金|刀|欧元|欧(?![洲盟])|英镑|镑|港[币元]|日元|新币|加元|澳元|元|块|(?:usd|eur|gbp|hkd|jpy|sgd|cad|aud|cny|rmb|r|u)(?![a-z0-9])))\s*(?:(?:push|过户|转移|中介|初装|设置|手续)\s*费用?|余额)""",
    )
    private val PRICE_CUT_DELTA_REGEX = Regex(
        """(?i)(?:自刀|降价|砍价?|小刀|大刀|再降|直降)\s*\d{1,5}(?:\.\d{1,2})?\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?(?!\s*(?:出|转))""",
    )
    private val ACCOUNT_CREDIT_SCRUB_REGEX = Regex(
        """(?i)(?:(?:us\$|\$|€|£)\s*\d+(?:\.\d+)?|\d+(?:\.\d+)?\s*(?:美刀|美元|美金|刀|欧元?|usd|eur))\s*(?:账号|赠金号|学生号|老号|试用号|体验号|成品号|码|体验金|赠金|额度|代金券|优惠券|credits?)""",
    )

    // Date scrubbers (for ask-price / renewal isolation)
    private val FULL_DATE_SCRUB_REGEX = Regex(
        """(?<![0-9A-Za-z])(?:(?:19|20)?\d{2})[-/.年]\d{1,2}[-/.月]\d{1,2}[日号]?(?:\s*(?:到期|续费|截止|购买|开通|注册))?(?![0-9A-Za-z])""",
    )
    private val YEAR_MONTH_SCRUB_REGEX = Regex(
        """(?<![0-9A-Za-z$€£¥￥])(?:(?:19|20)\d{2}[-/.年](?:0?[1-9]|1[0-2])(?:月份?)?[初中底]?(?:\s*(?:到期|续费|截止))?|2[4-9](?:年(?:0?[1-9]|1[0-2])(?:月份?)?[初中底]?(?:\s*(?:到期|续费|截止))?|[-/.](?:0?[1-9]|1[0-2])(?:月份?)?[初中底]?\s*(?:到期|续费|截止)))(?![0-9A-Za-z])""",
    )
    private val MONTH_DAY_SCRUB_REGEX = Regex(
        """(?<![0-9A-Za-z])(?:0?[1-9]|1[0-2])[-/月](?:0?[1-9]|[12]\d|3[01])[日号]?(?:\s*(?:到期|续费))?(?![0-9A-Za-z.])""",
    )
    private val DOT_DATE_CONTEXT_SCRUB_REGEX = Regex(
        """(?:(?:到期|续费|时间|日期|截止|购买|买于|购于)\s*[:：]?\s*(?:0?[1-9]|1[0-2])\.(?:0?[1-9]|[12]\d|3[01])[日号]?(?![0-9A-Za-z.])|(?<![0-9A-Za-z.+＋\-－])(?:0?[1-9]|1[0-2])\.(?:0?[1-9]|[12]\d|3[01])\s*(?:到期|续费|号|日)(?![0-9A-Za-z.]))""",
    )
    private val MONTH_EXPIRY_SCRUB_REGEX = Regex(
        """(?<![0-9A-Za-z])(?:今年|明年|后年)?(?:0?[1-9]|1[0-2])月份?[初中底]?\s*(?:到期|续费)""",
    )
    private val REMAINING_TIME_SCRUB_REGEX = Regex(
        """(?:还有|还剩|剩余|剩|余|距到期|离到期)\s*(?:\d{1,4}\s*(?:天|日|个?月|年)|半年|一年|两年|三年)""",
    )
    private val YEAR_ONLY_SCRUB_REGEX = Regex(
        """(?<![0-9A-Za-z])(?:20[1-3]\d)\s*(?:年|到期|款|黑五|双十一|双11|圣诞|周年)?(?![0-9A-Za-z])""",
    )
    private val COUNT_UNIT_SCRUB_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:台|个|只|枚|套|款|版|天|日|号|周|个?月|年|核|口|分|级|楼|次|倍|ipv?[46]|ip|岁|小时|分钟)(?![a-z0-9])""",
    )

    // Spread & residual expressions (for scrubbing before ask-price matching)
    private val EXPLICIT_REMAINING_COLON_ONLY_REGEX = Regex(
        """(?i)^(?:剩余价值|净残值|剩价|残值|原价)\s*[:：]\s*[¥￥]?\s*\d{1,5}(?:\.\d{1,2})?\s*(?:元|块|r|cny|rmb|¥|￥)?$""",
    )
    private val EXPLICIT_PAR_BUY_REGEX = Regex(
        """(?:平收|平价收|原价收|无溢价收|不溢价收|按(?:剩余价值|净残值|残值)收|(?:剩余价值|净残值|残值|剩价)\s*平?收)(?!\s*[+＋\-－加减]\s*\d)""",
    )
    private val STATED_RESIDUAL_WITH_SPREAD_REGEX = Regex(
        """(?i)(?:净?剩余价值|净残值|残值|剩价)\s*(?:约|大概|大约|为|是)?\s*[:：]?\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?\s*([+＋\-－加减])\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?(?:\s*(?:出(?![现口发来错品席身生版差站墙狱])|转(?![发换移入出])|收(?![费入益藏到录])))?(?![%折])""",
    )
    private val STATED_RESIDUAL_PAR_SELL_REGEX = Regex(
        """(?i)(?:净?剩余价值|净残值|残值|剩价)\s*[:：]?\s*(?:[¥￥]\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?|(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9])))\s*平?(?:出(?![现口发来错品席身生版差站墙狱])|转(?![发换移入出])|转让)""",
    )
    private val STATED_RESIDUAL_REGEX = Regex(
        """(?i)(?:净?剩余价值|净残值|残值|剩价)\s*(?:约|大概|大约|为|是)?\s*[:：]?\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?(?!\s*[+＋\-－加减]\s*\d)""",
    )
    private val STATED_ORIG_PRICE_SCRUB_REGEX = Regex(
        """(?i)(?:原价|官网价?|官方价?)\s*(?:约|为)?\s*[:：]?\s*[¥￥]?\s*\d{1,5}(?:\.\d{1,2})?\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?(?!\s*[+＋\-－加减]\s*\d)""",
    )
    private val COLLOQUIAL_PREMIUM_SPREAD_REGEX = Regex(
        """(?i)(?:(?:(?:小|微|略|轻|稍)溢(?:价)?|加价)(出)?\s*[+＋:：]?\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?(?![%折])|(?<!新)加\s*[+＋]?\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?\s*(?:出(?![现口发来错品席身生版差站墙狱])|收(?![费入益藏到录])|转(?![发换移入出])|包\s*push|带原[油邮]|原[油邮]))""",
    )
    private val COLLOQUIAL_DISCOUNT_SPREAD_REGEX = Regex(
        """(?i)(?:(?:小|微|略|轻|稍)(?:折|亏)(出)?\s*[-－:：]?\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?(?:\s*(?:出|转))?(?![%折])|(?<![立满再])减\s*[-－]?\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?\s*(?:出(?![现口发来错品席身生版差站墙狱])|收(?![费入益藏到录])|转(?![发换移入出])|包\s*push|带原[油邮]|原[油邮]))""",
    )
    private val SCRUB_SPREAD_FOR_ASK_REGEX = Regex(
        """(?:(?:(?:小|微|略|轻|稍)溢价?|溢价|加价)\s*[+＋:：]?\s*[¥￥]?\s*\d{1,5}(?:\.\d{1,2})?\s*(?:元|块|r|R|cny|CNY|¥|￥)?|(?:剩余价值|净残值|剩价|残值|原价)\s*[+＋\-－加减]\s*[¥￥]?\s*\d{1,5}(?:\.\d{1,2})?\s*(?:元|块|r|R|cny|CNY|¥|￥)?|(?:(?:小|微|略|轻|稍)(?:折价?|亏本?)|折价|亏本?|让利|血?亏了?|倒贴)\s*[-－:：]?\s*[¥￥]?\s*\d{1,5}(?:\.\d{1,2})?\s*(?:元|块|r|R|cny|CNY|¥|￥)?(?:\s*出)?|(?:(?<![A-Za-z0-9\-])[+\-＋－]|(?:(?<!新)加|(?<![立满再])减)\s*)\d{1,5}(?:\.\d{1,2})?\s*(?:元|块|r|R|cny|CNY|¥|￥)?\s*(?:出(?![现口发来错品席身生版差站墙狱])|收(?![费入益藏到录])|转(?![发换移入出])|包\s*push|带原[油邮]|原[油邮]))""",
        RegexOption.IGNORE_CASE,
    )
    private val SCRUB_SMALL_AFTER_WORD_CHU_REGEX = Regex(
        """(?:溢价出|折价出|亏本出|血亏出|亏出)\s*[:：]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|r|R|cny|CNY|¥|￥)?""",
    )

    // Renewal regexes
    private const val CURRENCY_PREFIX_GROUP =
        """(us\$|hk\$|s\$|c\$|a\$|nt\$|r\$|\$|€|£|¥|￥|₽|₺|chf)"""
    private const val FOREIGN_CURRENCY_PREFIX_GROUP =
        """(us\$|hk\$|s\$|c\$|a\$|nt\$|r\$|\$|€|£|₽|₺|chf)"""
    private const val CURRENCY_SUFFIX_GROUP =
        """(美刀|美元|美金|刀|欧元|欧(?![洲盟])|英镑|镑|港币|港元|港刀|日元|円|新币|新加坡元|加元|加币|澳元|澳币|新台币|台币|瑞郎|卢布|里拉|元|块|(?:usd|eur|gbp|hkd|jpy|sgd|cad|aud|twd|chf|rub|try|brl|cny|rmb|r|u)(?![a-z0-9]))"""
    private const val FOREIGN_CURRENCY_SUFFIX_GROUP =
        """(美刀|美元|美金|刀|欧元|欧(?![洲盟])|英镑|镑|港币|港元|港刀|日元|円|新币|新加坡元|加元|加币|澳元|澳币|新台币|台币|瑞郎|卢布|里拉|(?:usd|eur|gbp|hkd|jpy|sgd|cad|aud|twd|chf|rub|try|brl)(?![a-z0-9]))"""
    // ASCII cycle tokens guard against trailing letters only (so "$36.9/yr300r" and "€5/mo80r" match),
    // and CJK cycle tokens allow directly glued prices (so "4刀/月75r" and "9.9刀/年120r" match).
    private const val SLASH_CYCLE_GROUP =
        """((?:3\s*y(?:ea)?rs?|2\s*y(?:ea)?rs?|y(?:ea)?r?s?|m(?:o(?:nth)?s?)?|q(?:tr|uarter)?)(?![a-z])|三年|两年|二年|3年|2年|半年|年|季(?:度)?|月)"""
    private const val WORD_CYCLE_GROUP =
        """(三年付|3年付|两年付|二年付|2年付|半年付|一年付|1年付|年付|季付|月付|每三年|每两年|每半年|每年|每季|每月|按年|按季|按月|triennial|biennial|semiannual|semi-annual|annually|annual|yearly|quarterly|monthly)"""

    // R1a: Prefix symbol + amount + cycle (e.g. $36.9/yr, €5/月, ¥99/年, $36.9 年付)
    private val RENEWAL_PREFIX_WITH_CYCLE_REGEX = Regex(
        """(?i)$CURRENCY_PREFIX_GROUP\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:/\s*$SLASH_CYCLE_GROUP|\s*$WORD_CYCLE_GROUP)""",
    )
    // R1b: Amount + suffix currency + cycle (e.g. 36.9刀/年, 36.9刀年付, 99元/年, 10u/月, 4刀/月75r)
    private val RENEWAL_SUFFIX_WITH_CYCLE_REGEX = Regex(
        """(?i)(?<![0-9.])(\d{1,5}(?:\.\d{1,2})?)\s*$CURRENCY_SUFFIX_GROUP\s*(?:/\s*$SLASH_CYCLE_GROUP|\s*$WORD_CYCLE_GROUP)""",
    )
    // R1c: Cycle prefix + currency + amount (e.g. 年付$36.9, 年付 36.9刀, 三年付25刀, 月付¥15)
    // Suffix variant restricts to foreign currencies so "三年付 330r" / "半年付 180r" is kept as a CNY ask price
    // (CNY renewals with cycles like "99元/年" or "¥59.9/年" are handled by R1a/R1b).
    private val RENEWAL_CYCLE_FIRST_PREFIX_CUR_REGEX = Regex(
        """(?i)$WORD_CYCLE_GROUP\s*[:：]?\s*$CURRENCY_PREFIX_GROUP\s*(\d{1,5}(?:\.\d{1,2})?)""",
    )
    private val RENEWAL_CYCLE_FIRST_SUFFIX_CUR_REGEX = Regex(
        """(?i)$WORD_CYCLE_GROUP\s*[:：]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*$FOREIGN_CURRENCY_SUFFIX_GROUP""",
    )
    // R1d: Labeled renewal (e.g. 续费：$36.9, 续费 99元, 续费价格: 36.9刀, 续费标准：36.90 USD)
    // Exclude "刚续费 400r" / "已续费 300r" / "未续费" / "可续费" where 续费 is a status verb, not a price label.
    private val RENEWAL_LABELED_PREFIX_CUR_REGEX = Regex(
        """(?i)(?:(?<![刚已未可])续费(?:价格|金额|标准|费用|成本)?|循环续费|周期续费|recurring|renewal(?:\s*(?:price|amount|fee))?)\s*(?:为|是|约)?\s*[:：]?\s*$CURRENCY_PREFIX_GROUP\s*(\d{1,5}(?:\.\d{1,2})?)(?:\s*(?:/\s*$SLASH_CYCLE_GROUP|\s*$WORD_CYCLE_GROUP))?""",
    )
    private val RENEWAL_LABELED_SUFFIX_CUR_REGEX = Regex(
        """(?i)(?:(?<![刚已未可])续费(?:价格|金额|标准|费用|成本)?|循环续费|周期续费|recurring|renewal(?:\s*(?:price|amount|fee))?)\s*(?:为|是|约)?\s*[:：]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*$CURRENCY_SUFFIX_GROUP(?:\s*(?:/\s*$SLASH_CYCLE_GROUP|\s*$WORD_CYCLE_GROUP))?""",
    )
    // R2: Uncycled foreign currency (e.g. $36.9, 36.9刀, 24美元, €5, 10欧)
    private val RENEWAL_FOREIGN_PREFIX_REGEX = Regex(
        """(?i)$FOREIGN_CURRENCY_PREFIX_GROUP\s*(\d{1,5}(?:\.\d{1,2})?)(?![a-z0-9.])""",
    )
    private val RENEWAL_FOREIGN_SUFFIX_REGEX = Regex(
        """(?i)(?<![a-z0-9.])(\d{1,5}(?:\.\d{1,2})?)\s*$FOREIGN_CURRENCY_SUFFIX_GROUP""",
    )
    // Standalone cycle keyword anywhere in text
    private val STANDALONE_CYCLE_REGEX = Regex(
        """(?i)(?:/\s*$SLASH_CYCLE_GROUP|$WORD_CYCLE_GROUP)""",
    )

    // Foreign-currency explicit ask price (checked before scrubbing foreign currencies!)
    private val FOREIGN_ASK_LABELED_PREFIX_REGEX = Regex(
        """(?i)(?:明盘|一口价|底价|现价|售价|价格|出价|要价)\s*[:：]?\s*$FOREIGN_CURRENCY_PREFIX_GROUP\s*(\d{1,5}(?:\.\d{1,2})?)(?!\s*(?:/\s*(?:yr|year|mo|month|y|m|q|年|月|季|半年)|年付|月付|季付|半年付|两年付|三年付))""",
    )
    private val FOREIGN_ASK_LABELED_SUFFIX_REGEX = Regex(
        """(?i)(?:明盘|一口价|底价|现价|售价|价格|出价|要价)\s*[:：]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(usdt|usd|u|刀|美刀|美元|美金|eur|欧元?|gbp|英镑|hkd|港[币元])(?![a-z0-9])(?!\s*(?:/\s*(?:yr|year|mo|month|y|m|q|年|月|季|半年)|年付|月付|季付|半年付|两年付|三年付))""",
    )
    private val USDT_VERB_ASK_REGEX = Regex(
        """(?i)(?<![a-z0-9+\-.])(\d{1,5}(?:\.\d{1,2})?)\s*(usdt|u)(?![a-z0-9])\s*(?:(?:带原[油邮]|原[油邮]|包\s*push|不刀|可小?刀)?\s*(?:出(?![现口发来错品席身生版差站墙狱])|收(?![费入益藏到录])|转(?![发换移入出])))""",
    )
    private val FOREIGN_VERB_ASK_FOR_RENEWAL_SCRUB_REGEX = Regex(
        """(?i)(?:$FOREIGN_CURRENCY_PREFIX_GROUP\s*\d{1,5}(?:\.\d{1,2})?|(?<![a-z0-9.])\d{1,5}(?:\.\d{1,2})?\s*(?:usdt|u|usd|刀|美刀|美元|eur|欧)(?![a-z0-9]))\s*(?:(?:带原[油邮]|原[油邮]|改邮|包\s*push|不刀|可小?刀)?\s*(?:出(?![现口发来错品席身生版差站墙狱])|转(?![发换移入出])|收(?![费入益藏到录]))|(?:带原[油邮]|包\s*push))""",
    )

    // CNY ask price regexes
    private val ASK_LABELED_CNY_REGEX = Regex(
        """(?i)(?:明盘|一口价|底价|现价|售价|价格|出价|要价|心理价|打包价|出让价|转让价|总价|预算|出让挂牌价|出让|买家实付|自刀[至到后]|降价[至到]|降至|改价[至到]?)\s*(?:不刀|可小?刀|包\s*push|带原[油邮]|原[油邮]|包邮)?\s*[:：为是]?\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:cny|rmb|r)(?![a-z0-9]))?(?![%折/])""",
    )
    private val ASK_CNY_SYMBOL_REGEX = Regex(
        """(?<![+＋\-－])[¥￥]\s*(\d{1,5}(?:\.\d{1,2})?)(?![%折/])""",
    )
    private val ASK_CNY_SUFFIX_REGEX = Regex(
        """(?i)(?<![a-z0-9+\-＋－.])(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|(?:rmb|cny|r)(?![a-z0-9]))(?![%折/])""",
    )
    private val ASK_NUMBER_WITH_VERB_REGEX = Regex(
        """(?i)(?<![a-z0-9+\-＋－.])(\d{1,5}(?:\.\d{1,2})?)\s*(?:(?:带原[油邮]|给原[油邮]|含原[油邮]|原[油邮]|改邮|包\s*push|包邮|不刀|可小?刀|打包|诚心?|急|慢|秒|单)?\s*(?:出(?![现口发来错品席身生版差站墙狱])|转(?![发换移入出])|收(?![费入益藏到录]))|(?:带原[油邮]|包\s*push))""",
    )
    // Bare unitless ask prices in concise titles are integers (e.g. "出 DMIT LAX Pro Wee 300"),
    // avoiding decimal plan-tier shorthands like "DMIT HKG Pro 36.9 已出".
    private val ASK_BARE_NUMBER_REGEX = Regex(
        """(?i)(?<![a-z0-9+\-＋－.])(\d{2,5})(?![a-z0-9.%折/])""",
    )
    private val BARE_PRECEDING_MODEL_PREFIX_REGEX = Regex(
        """(?i)(?:^|[\s,，/|(\[【])(?:rs|vps|ax|ex|cx|cpx|cax|ccx|dc|as|ks|le|gen|tier|plan|lxc|kvm|ip|v|g|s|t|p|c|e|双|第)\s*[-_]?\s*$""",
    )
    private val BARE_FOLLOWING_UNIT_REGEX = Regex(
        """(?i)^\s*(?:台|个|只|枚|套|款|版|年|月|天|日|号|周|核|口|折|分|级|楼|次|倍|刀|美元|美金|欧|英镑|港|日元|ip|gb|mb|tb|kb|g|m|t|c|h|u(?![a-z]))""",
    )

    // Expiry regexes
    private const val EXPIRY_LABEL_PREFIX =
        """(?:(?:下次|服务|账单|续费)?到期(?:时间|日期|日)?|有效期至?|下次(?:续费|付款|账单)(?:时间|日期|日)?|续费(?:时间|日期|日)|截止(?:时间|日期|日)?|next\s+due\s+date|expir(?:y|ation|es)(?:\s*(?:date|on))?|due\s+date)"""
    private val EXPIRY_LABELED_FULL_DATE_REGEX = Regex(
        """(?i)$EXPIRY_LABEL_PREFIX\s*[:：是为至到]?\s*((?:20)?\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})[日号]?""",
    )
    private val EXPIRY_LABELED_YEAR_MONTH_REGEX = Regex(
        """(?i)$EXPIRY_LABEL_PREFIX\s*[:：是为至到]?\s*((?:20)?\d{2})\s*[-/.年]\s*(\d{1,2})(?:月份?)?([初中底])?(?![0-9\-/.])""",
    )
    private val EXPIRY_LABELED_MONTH_DAY_REGEX = Regex(
        """(?i)$EXPIRY_LABEL_PREFIX\s*[:：是为至到]?\s*(今年|明年|后年)?\s*(0?[1-9]|1[0-2])\s*[-/.月]\s*(0?[1-9]|[12]\d|3[01])[日号]?(?![0-9.])""",
    )
    private val EXPIRY_LABELED_MONTH_ONLY_REGEX = Regex(
        """(?i)$EXPIRY_LABEL_PREFIX\s*[:：是为至到]?\s*(今年|明年|后年)?\s*(0?[1-9]|1[0-2])月份?([初中底])?(?![0-9])""",
    )
    private val EXPIRY_SUFFIX_FULL_DATE_REGEX = Regex(
        """(?<![0-9A-Za-z])((?:20)?\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})[日号]?\s*(?:到期|截止|续费)""",
    )
    private val EXPIRY_SUFFIX_YEAR_MONTH_REGEX = Regex(
        """(?<![0-9A-Za-z])(20\d{2}|2[4-9])\s*[-/.年]\s*(0?[1-9]|1[0-2])(?:月份?)?([初中底])?\s*(?:到期|截止|续费)""",
    )
    private val EXPIRY_SUFFIX_MONTH_DAY_REGEX = Regex(
        """(?<![0-9A-Za-z.+＋\-－])(今年|明年|后年)?\s*(0?[1-9]|1[0-2])\s*[-/.月]\s*(0?[1-9]|[12]\d|3[01])[日号]?\s*(?:到期|截止|续费)""",
    )
    private val EXPIRY_SUFFIX_MONTH_ONLY_REGEX = Regex(
        """(?<![0-9A-Za-z])(今年|明年|后年)?\s*(0?[1-9]|1[0-2])月份?([初中底])?\s*(?:到期|截止|续费)""",
    )
    private val EXPIRY_DATE_RANGE_REGEX = Regex(
        """(?:20\d{2})[-/.]\d{1,2}[-/.]\d{1,2}\s*(?:→|->|至|到|~)\s*(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})""",
    )
    private val EXPIRY_REMAINING_DAYS_REGEX = Regex(
        """(?:还有|还剩|剩余|剩|余|距到期|离到期)\s*(?:约|大概)?\s*(\d{1,4})\s*[天日]""",
    )
    private val EXPIRY_REMAINING_MONTHS_REGEX = Regex(
        """(?:还有|还剩|剩余|剩|余)\s*(?:约|大概)?\s*(\d{1,2})\s*个?月""",
    )
    private val EXPIRY_REMAINING_HALF_YEAR_REGEX = Regex(
        """(?:还有|还剩|剩余|剩|余)\s*(?:约|大概)?\s*半年""",
    )
    private val EXPIRY_REMAINING_YEARS_REGEX = Regex(
        """(?:还有|还剩|剩余|剩|余)\s*(?:约|大概)?\s*(\d|一|两|二|三)\s*年""",
    )
    private val EXPIRY_RELATIVE_SPECIAL_REGEX = Regex(
        """(下个?月|本?月底|年底)\s*(?:到期|续费)""",
    )
    private val EXPIRY_FRESH_FULL_YEAR_REGEX = Regex(
        """(?:满一年|满年|整一年|整年|刚+续费?了一?年|新开一?年|刚开一?年|全新一?年)""",
    )
    private val EXPIRY_FRESH_CYCLE_REGEX = Regex(
        """(?<![0-9日号月年])(?:新开|刚开|刚+续费|全新刚开)""",
    )
    private val PRECEDING_DATE_BEFORE_FRESH_REGEX = Regex(
        """(?:(?:20)?\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}[日号]?|(?:0?[1-9]|1[0-2])[-/.月](?:0?[1-9]|[12]\d|3[01])[日号]?)\s*$""",
    )
    private val ANNUAL_PROMO_MIN_USD = BigDecimal("9.00")
    private val ANNUAL_PROMO_MAX_USD = BigDecimal("11.99")
    private val ANNUAL_CLEAR_MIN_MAJOR = BigDecimal("12.00")
    private val ANNUAL_CLEAR_MIN_MID = BigDecimal("16.00")
    private val ANNUAL_CLEAR_MIN_CNY_HKD = BigDecimal("80.00")
    private val BARE_FULL_DATE_REGEX = Regex(
        """(?<![0-9A-Za-z])(20[2-3]\d)\s*[-/.年]\s*(0?[1-9]|1[0-2])\s*[-/.月]\s*(0?[1-9]|[12]\d|3[01])[日号]?(?![0-9A-Za-z])""",
    )
    private val NON_EXPIRY_DATE_BEFORE_REGEX = Regex(
        """(?:购买|买于|购于|开通|注册|创建|发布|更新|基准|估值|测试|实测|测于|下单|成交)\s*[:：于]?\s*$""",
    )
    private val NON_EXPIRY_DATE_AFTER_REGEX = Regex(
        """^\s*(?:购买|买|购|开通|注册|创建|发布|下单|实测|已续费|刚+续费|续过费)""",
    )
    private val ALREADY_RENEWED_BEFORE_REGEX = Regex("""(?:已|刚|刚刚|提前)\s*$""")

    /**
     * Offline default FX lookup delegating to [ValuationCurrency.defaultCnyRate].
     */
    fun defaultFxToCny(currencyCode: String): BigDecimal? =
        ValuationCurrency.fromCode(currencyCode)?.defaultCnyRate

    /**
     * Fast check for callers (such as repository body-fetch gating) indicating whether [title]
     * alone already carries an explicit spread or asking price without needing an OP body fetch.
     */
    fun hasTitlePricingSignal(title: String): Boolean {
        if (title.isBlank()) return false
        val parsed = parse(title = title, bodySnippet = null)
        return parsed.explicitSpread != null || parsed.askPrice != null
    }

    /**
     * Parses all available pricing fields from [title] and optional [bodySnippet].
     */
    @JvmOverloads
    fun parse(
        title: String,
        bodySnippet: String? = null,
        fxToCny: (String) -> BigDecimal? = ::defaultFxToCny,
        now: LocalDate = VpsValuationEngine.todayInShanghai(),
    ): ParsedListingDetails {
        val rawTitle = title.trim()
        val normTitle = normalizeWidth(rawTitle)
        val cleanedBody = bodySnippet?.let(::cleanBodyText)?.takeIf { it.isNotEmpty() }

        val isClosedSold = CLOSED_SOLD_REGEX.containsMatchIn(normTitle) &&
            !NON_TRADE_SOLD_FALSE_POSITIVE_REGEX.containsMatchIn(normTitle)
        val intent = detectIntent(normTitle, cleanedBody?.let(::normalizeWidth))
        val isBundle = isMultiMachineBundle(normTitle)

        val residualWithSpread = parseResidualWithSpread(rawTitle)
            ?: cleanedBody?.let(::parseResidualWithSpread)

        val residualParSell = if (residualWithSpread == null) {
            parseResidualParSell(rawTitle) ?: cleanedBody?.let(::parseResidualParSell)
        } else {
            null
        }

        val statedResidual = residualWithSpread?.residualCny
            ?: residualParSell?.first
            ?: parseStatedResidual(rawTitle)
            ?: cleanedBody?.let(::parseStatedResidual)

        val renewal = parseRenewalInternal(rawTitle, cleanedBody)
        val expiry = parseExpiryInternal(rawTitle, cleanedBody, now)
        val askPrice = if (isBundle) {
            null
        } else {
            parseAskPriceInternal(rawTitle, cleanedBody, fxToCny)
                ?: residualParSell?.let { (amt, expr) ->
                    ParsedAskPrice(
                        amountCny = amt,
                        matchedExpression = expr,
                        confidence = PricingConfidence.HIGH,
                    )
                }
        }

        val explicitSpread = extractExplicitSpread(
            rawTitle = rawTitle,
            cleanedBody = cleanedBody,
            hasAskWithStatedResidual = askPrice != null && statedResidual != null,
            residualWithSpread = residualWithSpread,
            residualParSell = residualParSell,
            fallbackIntent = intent,
            fallbackClosedSold = isClosedSold,
        )

        val resolvedIntent = when {
            intent != TradeListingIntent.UNKNOWN -> intent
            explicitSpread != null && explicitSpread.intent != TradeListingIntent.UNKNOWN -> explicitSpread.intent
            askPrice != null -> TradeListingIntent.SELL
            else -> TradeListingIntent.UNKNOWN
        }

        return ParsedListingDetails(
            intent = resolvedIntent,
            isClosedSold = isClosedSold || (explicitSpread?.isClosedSold == true),
            explicitSpread = explicitSpread,
            askPrice = askPrice,
            renewal = renewal,
            expiry = expiry,
            statedResidualCny = statedResidual,
        )
    }

    /**
     * Extracts the total asking price in CNY from [title] and optional [bodySnippet].
     */
    @JvmOverloads
    fun parseAskPrice(
        title: String,
        bodySnippet: String? = null,
        fxToCny: (String) -> BigDecimal? = ::defaultFxToCny,
    ): ParsedAskPrice? {
        val rawTitle = title.trim()
        val cleanedBody = bodySnippet?.let(::cleanBodyText)?.takeIf { it.isNotEmpty() }
        return parseAskPriceInternal(rawTitle, cleanedBody, fxToCny)
    }

    /**
     * Extracts renewal amount, currency, and billing cycle from [title] and optional [bodySnippet].
     */
    @JvmOverloads
    fun parseRenewal(
        title: String,
        bodySnippet: String? = null,
    ): ParsedRenewalInfo? {
        val rawTitle = title.trim()
        val cleanedBody = bodySnippet?.let(::cleanBodyText)?.takeIf { it.isNotEmpty() }
        return parseRenewalInternal(rawTitle, cleanedBody)
    }

    /**
     * Extracts service expiry date and remaining days from [title] and optional [bodySnippet].
     */
    @JvmOverloads
    fun parseExpiry(
        title: String,
        bodySnippet: String? = null,
        now: LocalDate = VpsValuationEngine.todayInShanghai(),
    ): ParsedExpiryInfo? {
        val rawTitle = title.trim()
        val cleanedBody = bodySnippet?.let(::cleanBodyText)?.takeIf { it.isNotEmpty() }
        return parseExpiryInternal(rawTitle, cleanedBody, now)
    }

    /**
     * Infers spread (explicit or derived `ask - residual`) and total asking price (explicit or
     * derived `residual + spread`), or falls back to an ask-price-only pricing result for a
     * [TradeCandidateTopic]. Returns `null` if neither spread nor ask price can be extracted.
     */
    @JvmOverloads
    fun inferSpread(
        candidate: TradeCandidateTopic,
        fxToCny: (String) -> BigDecimal? = ::defaultFxToCny,
        now: LocalDate = VpsValuationEngine.todayInShanghai(),
        referenceRenewal: ParsedRenewalInfo? = null,
    ): InferredListingPricing? = inferSpread(
        title = candidate.title,
        bodySnippet = candidate.bodySnippet,
        fxToCny = fxToCny,
        now = now,
        referenceRenewal = referenceRenewal,
    )

    /**
     * Infers spread (explicit or derived `ask - residual`) and total asking price (explicit or
     * derived `residual + spread`), or falls back to an ask-price-only pricing result from
     * [title] and optional [bodySnippet].
     *
     * Priority:
     * 1. Explicit spread phrase (title, then OP body) -> `isDerived = false`, `confidence = HIGH`.
     *    When net residual can be computed (from own renewal, [referenceRenewal], or stated residual)
     *    and no explicit total ask was written, derives `askPriceCny = (residual + spread).coerceAtLeast(0)`
     *    with `isAskDerived = true`.
     * 2. Derived spread via [VpsValuationEngine.calculate] when ask price + `(renewal ?: referenceRenewal)`
     *    + future expiry (with resolvable FX) are known, or when ask price + stated residual are known
     *    -> `isDerived = true`, `isAskDerived = false`, `confidence = MEDIUM/LOW`.
     * 3. Ask-price-only fallback (`spreadDeltaCny = null`, `askPriceCny != null`).
     * 4. `null` when neither spread nor ask price is present.
     */
    @JvmOverloads
    fun inferSpread(
        title: String,
        bodySnippet: String? = null,
        fxToCny: (String) -> BigDecimal? = ::defaultFxToCny,
        now: LocalDate = VpsValuationEngine.todayInShanghai(),
        referenceRenewal: ParsedRenewalInfo? = null,
    ): InferredListingPricing? {
        val details = parse(title = title, bodySnippet = bodySnippet, fxToCny = fxToCny, now = now)
        val effectiveRenewal = details.renewal ?: referenceRenewal
        val effectiveExpiry = resolveEffectiveExpiry(details, effectiveRenewal, now)
        val residualComp = computeResidualCny(
            details = details,
            effectiveRenewal = effectiveRenewal,
            usedReferenceRenewal = details.renewal == null && referenceRenewal != null,
            effectiveExpiry = effectiveExpiry,
            fxToCny = fxToCny,
            now = now,
        )

        // 1. Explicit spread takes precedence; also derive total ask = residual + spread when possible
        details.explicitSpread?.let { explicit ->
            val isLegacyColonResidual = EXPLICIT_REMAINING_COLON_ONLY_REGEX.containsMatchIn(explicit.matchedExpression)
            val residualCny = residualComp
                ?.takeUnless { isLegacyColonResidual && it.fromStatedResidual }
                ?.residualCny
            val explicitAsk = details.askPrice?.amountCny
            val derivedAsk = if (explicitAsk == null && residualCny != null) {
                residualCny.add(explicit.deltaCny)
                    .coerceAtLeast(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP)
                    .takeIf { it <= MAX_REASONABLE_PRICE_CNY }
            } else {
                null
            }
            val finalAsk = explicitAsk ?: derivedAsk
            val isAskDerived = explicitAsk == null && derivedAsk != null
            return InferredListingPricing(
                intent = details.intent,
                isClosedSold = details.isClosedSold,
                spreadDeltaCny = explicit.deltaCny,
                spreadStatus = explicit.spreadStatus,
                askPriceCny = finalAsk,
                residualCny = residualCny,
                isDerived = false,
                confidence = PricingConfidence.HIGH,
                matchedExpression = explicit.matchedExpression,
                parsedDetails = details,
                isAskDerived = isAskDerived,
            )
        }

        val ask = details.askPrice ?: return null

        // 2. Derived spread from ask - residual (via VpsValuationEngine or stated residual)
        if (residualComp != null) {
            val residualCny = residualComp.residualCny
            val spread = ask.amountCny.subtract(residualCny).setScale(2, RoundingMode.HALF_UP)
            if (spread.abs() <= MAX_REASONABLE_PRICE_CNY) {
                val status = when {
                    spread > BigDecimal.ZERO -> DealSpreadStatus.PREMIUM
                    spread < BigDecimal.ZERO -> DealSpreadStatus.DISCOUNT
                    else -> DealSpreadStatus.PAR
                }
                val derivedConfidence = if (residualComp.isHighConfidence &&
                    ask.confidence != PricingConfidence.LOW
                ) {
                    PricingConfidence.MEDIUM
                } else {
                    PricingConfidence.LOW
                }
                return InferredListingPricing(
                    intent = details.intent,
                    isClosedSold = details.isClosedSold,
                    spreadDeltaCny = spread,
                    spreadStatus = status,
                    askPriceCny = ask.amountCny,
                    residualCny = residualCny,
                    isDerived = true,
                    confidence = derivedConfidence,
                    matchedExpression = formatDerivedExpression(ask.amountCny, residualCny),
                    parsedDetails = details,
                    isAskDerived = false,
                )
            }
        }

        // 3. Ask-price-only fallback (no spread)
        return InferredListingPricing(
            intent = details.intent,
            isClosedSold = details.isClosedSold,
            spreadDeltaCny = null,
            spreadStatus = null,
            askPriceCny = ask.amountCny,
            residualCny = null,
            isDerived = false,
            confidence = ask.confidence,
            matchedExpression = ask.matchedExpression,
            parsedDetails = details,
            isAskDerived = false,
        )
    }

    private data class ResidualComputation(
        val residualCny: BigDecimal,
        val isHighConfidence: Boolean,
        val fromStatedResidual: Boolean,
    )

    private fun resolveEffectiveExpiry(
        details: ParsedListingDetails,
        effectiveRenewal: ParsedRenewalInfo?,
        now: LocalDate,
    ): ParsedExpiryInfo? {
        val expiry = details.expiry ?: return null
        // When fresh-cycle wording ("新开"/"刚续费") defaulted to 1 year without a cycle in text,
        // adapt it to an injected referenceRenewal cycle (e.g. monthly $4/mo -> 30d).
        val refCycleDays = effectiveRenewal?.cycle?.fixedDays
        if (details.renewal == null &&
            refCycleDays != null &&
            expiry.isApproximate &&
            EXPIRY_FRESH_CYCLE_REGEX.matches(expiry.matchedExpression) &&
            !EXPIRY_FRESH_FULL_YEAR_REGEX.containsMatchIn(expiry.matchedExpression)
        ) {
            return ParsedExpiryInfo(
                expiryDate = now.plusDays(refCycleDays.toLong()),
                remainingDays = refCycleDays,
                matchedExpression = expiry.matchedExpression,
                isApproximate = true,
            )
        }
        return expiry
    }

    private fun computeResidualCny(
        details: ParsedListingDetails,
        effectiveRenewal: ParsedRenewalInfo?,
        usedReferenceRenewal: Boolean,
        effectiveExpiry: ParsedExpiryInfo?,
        fxToCny: (String) -> BigDecimal?,
        now: LocalDate,
    ): ResidualComputation? {
        if (effectiveRenewal != null &&
            effectiveExpiry != null &&
            effectiveExpiry.remainingDays in 1..MAX_DERIVED_REMAINING_DAYS
        ) {
            val resolvedCycle = resolveCycleForValuation(effectiveRenewal, effectiveExpiry)
            val fxRate = if (effectiveRenewal.currency == ValuationCurrency.CNY) {
                BigDecimal.ONE
            } else {
                fxToCny(effectiveRenewal.currency.code)?.takeIf { it > BigDecimal.ZERO }
            }
            if (resolvedCycle != null && fxRate != null) {
                val outcome = VpsValuationEngine.calculate(
                    VpsValuationInput(
                        mode = ValuationMode.NET_VALUE_ONLY,
                        renewalAmount = effectiveRenewal.amount,
                        currency = effectiveRenewal.currency,
                        fxRateToCny = fxRate,
                        cyclePreset = resolvedCycle,
                        valuationDateTime = now.atStartOfDay(),
                        expiryDateTime = effectiveExpiry.expiryDate.atStartOfDay(),
                        preciseToMinute = false,
                    ),
                )
                if (outcome is VpsValuationOutcome.Success && !outcome.result.isExpired) {
                    val residual = outcome.result.remainingValueCny
                    if (residual > BigDecimal.ZERO && residual <= MAX_REASONABLE_PRICE_CNY) {
                        val highConf = !effectiveExpiry.isApproximate &&
                            effectiveRenewal.cycleExplicit &&
                            !usedReferenceRenewal
                        return ResidualComputation(
                            residualCny = residual,
                            isHighConfidence = highConf,
                            fromStatedResidual = false,
                        )
                    }
                }
            }
        }

        val statedResidual = details.statedResidualCny
        if (statedResidual != null && statedResidual > BigDecimal.ZERO) {
            return ResidualComputation(
                residualCny = statedResidual,
                isHighConfidence = true,
                fromStatedResidual = true,
            )
        }
        return null
    }

    private fun resolveCycleForValuation(
        renewal: ParsedRenewalInfo,
        expiry: ParsedExpiryInfo,
    ): BillingCyclePreset? {
        if (renewal.cycle != null) return renewal.cycle
        val days = expiry.remainingDays
        if (days in 95..380) return BillingCyclePreset.ANNUAL
        if (days in 1..94) {
            val amt = renewal.amount
            val isClearlyAnnual = when (renewal.currency) {
                ValuationCurrency.USD,
                ValuationCurrency.EUR,
                ValuationCurrency.GBP,
                ValuationCurrency.CHF,
                -> amt >= ANNUAL_CLEAR_MIN_MAJOR ||
                    (days > 35 && amt >= ANNUAL_PROMO_MIN_USD && amt <= ANNUAL_PROMO_MAX_USD)
                ValuationCurrency.CAD,
                ValuationCurrency.AUD,
                ValuationCurrency.SGD,
                -> amt >= ANNUAL_CLEAR_MIN_MID
                ValuationCurrency.HKD,
                ValuationCurrency.CNY,
                -> amt >= ANNUAL_CLEAR_MIN_CNY_HKD
                else -> false
            }
            if (isClearlyAnnual) return BillingCyclePreset.ANNUAL
        }
        return null
    }

    private data class ResidualWithSpreadMatch(
        val residualCny: BigDecimal,
        val spreadDeltaCny: BigDecimal,
        val spreadStatus: DealSpreadStatus,
        val matchedExpression: String,
    )

    private fun parseResidualWithSpread(rawText: String): ResidualWithSpreadMatch? {
        if (rawText.isBlank()) return null
        val norm = normalizeWidth(rawText)
        val m = STATED_RESIDUAL_WITH_SPREAD_REGEX.find(norm) ?: return null
        val residual = m.groupValues[1].toBigDecimalOrNull() ?: return null
        val op = m.groupValues[2]
        val rawSpread = m.groupValues[3].toBigDecimalOrNull() ?: return null
        if (residual <= BigDecimal.ZERO || residual > MAX_REASONABLE_PRICE_CNY) return null
        if (rawSpread > MAX_REASONABLE_PRICE_CNY) return null
        val isNegative = op == "-" || op == "－" || op == "减"
        val signedSpread = (if (isNegative) rawSpread.negate() else rawSpread).setScale(2, RoundingMode.HALF_UP)
        val status = when {
            signedSpread > BigDecimal.ZERO -> DealSpreadStatus.PREMIUM
            signedSpread < BigDecimal.ZERO -> DealSpreadStatus.DISCOUNT
            else -> DealSpreadStatus.PAR
        }
        return ResidualWithSpreadMatch(
            residualCny = residual.setScale(2, RoundingMode.HALF_UP),
            spreadDeltaCny = signedSpread,
            spreadStatus = status,
            matchedExpression = m.value.trim(),
        )
    }

    private fun extractExplicitSpread(
        rawTitle: String,
        cleanedBody: String?,
        hasAskWithStatedResidual: Boolean,
        residualWithSpread: ResidualWithSpreadMatch?,
        residualParSell: Pair<BigDecimal, String>?,
        fallbackIntent: TradeListingIntent,
        fallbackClosedSold: Boolean,
    ): ParsedTradeTitleSpread? {
        val normTitle = normalizeWidth(rawTitle)

        // 0. Inline stated residual + spread (e.g. "剩余价值180+50出", "残值180-20出", "剩余价值:180+50")
        residualWithSpread?.let { matched ->
            return ParsedTradeTitleSpread(
                deltaCny = matched.spreadDeltaCny,
                spreadStatus = matched.spreadStatus,
                intent = if (fallbackIntent != TradeListingIntent.UNKNOWN) fallbackIntent else TradeListingIntent.SELL,
                isClosedSold = fallbackClosedSold,
                matchedExpression = matched.matchedExpression,
            )
        }

        // 1. Delegate to TradeMarketPremiumAnalyzer.extractSpread on title
        val titleSpread = TradeMarketPremiumAnalyzer.extractSpread(normTitle)
        if (titleSpread != null) {
            val isColonResidualWithAsk = hasAskWithStatedResidual &&
                EXPLICIT_REMAINING_COLON_ONLY_REGEX.containsMatchIn(titleSpread.matchedExpression)
            if (!isColonResidualWithAsk) {
                return titleSpread
            }
        }

        // 1b. Handle "剩余价值 ¥36.40 出" (par sale stating exact residual ask) and buy-side par ("原价收")
        residualParSell?.let { (_, expr) ->
            return ParsedTradeTitleSpread(
                deltaCny = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                spreadStatus = DealSpreadStatus.PAR,
                intent = if (fallbackIntent != TradeListingIntent.UNKNOWN) fallbackIntent else TradeListingIntent.SELL,
                isClosedSold = fallbackClosedSold,
                matchedExpression = expr,
            )
        }
        EXPLICIT_PAR_BUY_REGEX.find(normTitle)?.let { m ->
            return ParsedTradeTitleSpread(
                deltaCny = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                spreadStatus = DealSpreadStatus.PAR,
                intent = TradeListingIntent.BUY,
                isClosedSold = fallbackClosedSold,
                matchedExpression = m.value.trim(),
            )
        }

        // 1c. Colloquial spread phrasings on title ("加50出", "微溢30", "小溢20", "减20出", "微折20", "小亏30")
        extractColloquialSpread(normTitle, fallbackIntent, fallbackClosedSold)?.let { return it }

        // 2. Try OP body line-by-line, ignoring "剩余价值: X" / "原价: X" which denote residual/retail in bodies
        if (cleanedBody.isNullOrBlank()) return null
        val lines = cleanedBody.lineSequence()
            .map { normalizeWidth(it.trim()) }
            .filter { it.isNotEmpty() }
            .take(25)
            .toList()
        for (line in lines) {
            val bodySpread = TradeMarketPremiumAnalyzer.extractSpread(line)
                ?: extractColloquialSpread(line, fallbackIntent, fallbackClosedSold)
                ?: continue
            if (EXPLICIT_REMAINING_COLON_ONLY_REGEX.containsMatchIn(bodySpread.matchedExpression)) {
                continue
            }
            return bodySpread.copy(
                intent = if (fallbackIntent != TradeListingIntent.UNKNOWN) fallbackIntent else bodySpread.intent,
                isClosedSold = fallbackClosedSold || bodySpread.isClosedSold,
            )
        }
        return null
    }

    private fun extractColloquialSpread(
        normText: String,
        fallbackIntent: TradeListingIntent,
        fallbackClosedSold: Boolean,
    ): ParsedTradeTitleSpread? {
        if (normText.isBlank()) return null
        val scrubbed = scrubNonAskCommon(normText)
            .replace(RENEWAL_PREFIX_WITH_CYCLE_REGEX, " ")
            .replace(RENEWAL_SUFFIX_WITH_CYCLE_REGEX, " ")
            .replace(RENEWAL_FOREIGN_PREFIX_REGEX, " ")
            .replace(RENEWAL_FOREIGN_SUFFIX_REGEX, " ")

        COLLOQUIAL_PREMIUM_SPREAD_REGEX.find(scrubbed)?.let { m ->
            val hadChuBeforeNum = m.groupValues[1].isNotEmpty()
            val numStr = m.groupValues[2].ifBlank { m.groupValues[3] }
            val rawNum = numStr.toBigDecimalOrNull() ?: return@let
            if (hadChuBeforeNum && rawNum > MAX_DIRECT_AFTER_CHU_SPREAD) return@let
            if (rawNum <= MAX_REASONABLE_PRICE_CNY) {
                val scaled = rawNum.setScale(2, RoundingMode.HALF_UP)
                return ParsedTradeTitleSpread(
                    deltaCny = scaled,
                    spreadStatus = if (scaled.compareTo(BigDecimal.ZERO) == 0) DealSpreadStatus.PAR else DealSpreadStatus.PREMIUM,
                    intent = fallbackIntent,
                    isClosedSold = fallbackClosedSold,
                    matchedExpression = m.value.trim(),
                )
            }
        }

        COLLOQUIAL_DISCOUNT_SPREAD_REGEX.find(scrubbed)?.let { m ->
            val hadChuBeforeNum = m.groupValues[1].isNotEmpty()
            val numStr = m.groupValues[2].ifBlank { m.groupValues[3] }
            val rawNum = numStr.toBigDecimalOrNull() ?: return@let
            if (hadChuBeforeNum && rawNum > MAX_DIRECT_AFTER_CHU_SPREAD) return@let
            if (rawNum <= MAX_REASONABLE_PRICE_CNY) {
                val scaled = rawNum.negate().setScale(2, RoundingMode.HALF_UP)
                return ParsedTradeTitleSpread(
                    deltaCny = scaled,
                    spreadStatus = if (rawNum.compareTo(BigDecimal.ZERO) == 0) DealSpreadStatus.PAR else DealSpreadStatus.DISCOUNT,
                    intent = fallbackIntent,
                    isClosedSold = fallbackClosedSold,
                    matchedExpression = m.value.trim(),
                )
            }
        }

        return null
    }

    private fun parseResidualParSell(rawText: String): Pair<BigDecimal, String>? {
        if (rawText.isBlank()) return null
        val norm = normalizeWidth(rawText)
        val match = STATED_RESIDUAL_PAR_SELL_REGEX.find(norm) ?: return null
        val numStr = match.groupValues[1].ifBlank { match.groupValues[2] }
        val num = numStr.toBigDecimalOrNull() ?: return null
        if (num <= BigDecimal.ZERO || num > MAX_REASONABLE_PRICE_CNY) return null
        return num.setScale(2, RoundingMode.HALF_UP) to match.value.trim()
    }

    private fun isMultiMachineBundle(rawTitle: String): Boolean {
        val norm = normalizeWidth(rawTitle)
        if (MULTI_BUNDLE_KEYWORD_REGEX.containsMatchIn(norm)) return true
        if (MULTI_BUNDLE_PLURAL_COUNT_REGEX.containsMatchIn(norm)) return true
        for (m in MULTI_BUNDLE_TOGETHER_PRICE_REGEX.findAll(norm)) {
            val prefix = norm.substring(maxOf(0, m.range.first - 10), m.range.first)
            if (!SINGLE_ITEM_TOGETHER_PREFIX_REGEX.containsMatchIn(prefix)) return true
        }
        for (m in MULTI_BUNDLE_TOGETHER_SELL_REGEX.findAll(norm)) {
            val prefix = norm.substring(maxOf(0, m.range.first - 10), m.range.first)
            if (!SINGLE_ITEM_TOGETHER_PREFIX_REGEX.containsMatchIn(prefix)) return true
        }
        return false
    }

    private fun parseStatedResidual(rawText: String): BigDecimal? {
        if (rawText.isBlank()) return null
        val normalized = normalizeWidth(rawText)
        val match = STATED_RESIDUAL_REGEX.find(normalized) ?: return null
        val num = match.groupValues[1].toBigDecimalOrNull() ?: return null
        if (num <= BigDecimal.ZERO || num > MAX_REASONABLE_PRICE_CNY) return null
        return num.setScale(2, RoundingMode.HALF_UP)
    }

    private fun parseRenewalInternal(rawTitle: String, cleanedBody: String?): ParsedRenewalInfo? {
        val fromTitle = parseRenewalFromSingleText(rawTitle)
        if (fromTitle != null) {
            // If title had renewal amount+currency without cycle, try to fill cycle from body
            if (fromTitle.cycle == null && !cleanedBody.isNullOrBlank()) {
                val bodyCycle = findStandaloneCycle(normalizeWidth(cleanedBody))
                if (bodyCycle != null) {
                    return fromTitle.copy(cycle = bodyCycle, cycleExplicit = true)
                }
            }
            return fromTitle
        }
        if (!cleanedBody.isNullOrBlank()) {
            return parseRenewalFromSingleText(cleanedBody)
        }
        return null
    }

    private fun parseRenewalFromSingleText(rawText: String): ParsedRenewalInfo? {
        if (rawText.isBlank()) return null
        val norm = normalizeWidth(rawText)
        val scrubbed = scrubNonRenewalNoise(norm)

        // 1. Explicit prefix currency + amount + cycle ($36.9/yr, €5/月, ¥99/年)
        RENEWAL_PREFIX_WITH_CYCLE_REGEX.find(scrubbed)?.let { m ->
            val currency = parseCurrencyToken(m.groupValues[1])
            val amount = m.groupValues[2].toBigDecimalOrNull()
            val cycle = parseCycleToken(m.groupValues[3].ifBlank { m.groupValues[4] })
            if (currency != null && amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY && cycle != null) {
                return ParsedRenewalInfo(
                    amount = amount.setScale(2, RoundingMode.HALF_UP),
                    currency = currency,
                    cycle = cycle,
                    matchedExpression = m.value.trim(),
                    cycleExplicit = true,
                )
            }
        }

        // 2. Explicit amount + suffix currency + cycle (36.9刀/年, 36.9刀年付, 99元/年, 10u/月)
        RENEWAL_SUFFIX_WITH_CYCLE_REGEX.find(scrubbed)?.let { m ->
            val amount = m.groupValues[1].toBigDecimalOrNull()
            val currency = parseCurrencyToken(m.groupValues[2])
            val cycle = parseCycleToken(m.groupValues[3].ifBlank { m.groupValues[4] })
            if (currency != null && amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY && cycle != null) {
                return ParsedRenewalInfo(
                    amount = amount.setScale(2, RoundingMode.HALF_UP),
                    currency = currency,
                    cycle = cycle,
                    matchedExpression = m.value.trim(),
                    cycleExplicit = true,
                )
            }
        }

        // 3. Cycle prefix + currency + amount (年付$36.9, 年付 36.9刀, 三年付25刀, 月付¥15, 年付99元)
        RENEWAL_CYCLE_FIRST_PREFIX_CUR_REGEX.find(scrubbed)?.let { m ->
            val cycle = parseCycleToken(m.groupValues[1])
            val currency = parseCurrencyToken(m.groupValues[2])
            val amount = m.groupValues[3].toBigDecimalOrNull()
            if (currency != null && amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY && cycle != null) {
                return ParsedRenewalInfo(
                    amount = amount.setScale(2, RoundingMode.HALF_UP),
                    currency = currency,
                    cycle = cycle,
                    matchedExpression = m.value.trim(),
                    cycleExplicit = true,
                )
            }
        }
        RENEWAL_CYCLE_FIRST_SUFFIX_CUR_REGEX.find(scrubbed)?.let { m ->
            val cycle = parseCycleToken(m.groupValues[1])
            val amount = m.groupValues[2].toBigDecimalOrNull()
            val currency = parseCurrencyToken(m.groupValues[3])
            if (currency != null && amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY && cycle != null) {
                return ParsedRenewalInfo(
                    amount = amount.setScale(2, RoundingMode.HALF_UP),
                    currency = currency,
                    cycle = cycle,
                    matchedExpression = m.value.trim(),
                    cycleExplicit = true,
                )
            }
        }

        // 4. Labeled renewal (续费：$36.9, 续费 36.9刀, 续费：99元)
        RENEWAL_LABELED_PREFIX_CUR_REGEX.find(scrubbed)?.let { m ->
            val currency = parseCurrencyToken(m.groupValues[1])
            val amount = m.groupValues[2].toBigDecimalOrNull()
            val inlineCycle = parseCycleToken(m.groupValues[3].ifBlank { m.groupValues[4] })
            val cycle = inlineCycle ?: findStandaloneCycle(scrubbed)
            if (currency != null && amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY) {
                return ParsedRenewalInfo(
                    amount = amount.setScale(2, RoundingMode.HALF_UP),
                    currency = currency,
                    cycle = cycle,
                    matchedExpression = m.value.trim(),
                    cycleExplicit = cycle != null,
                )
            }
        }
        RENEWAL_LABELED_SUFFIX_CUR_REGEX.find(scrubbed)?.let { m ->
            val amount = m.groupValues[1].toBigDecimalOrNull()
            val currency = parseCurrencyToken(m.groupValues[2])
            val inlineCycle = parseCycleToken(m.groupValues[3].ifBlank { m.groupValues[4] })
            val cycle = inlineCycle ?: findStandaloneCycle(scrubbed)
            if (currency != null && amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY) {
                return ParsedRenewalInfo(
                    amount = amount.setScale(2, RoundingMode.HALF_UP),
                    currency = currency,
                    cycle = cycle,
                    matchedExpression = m.value.trim(),
                    cycleExplicit = cycle != null,
                )
            }
        }

        // 5. Foreign currency amount without adjacent cycle (e.g. "出 绿云 2222 25刀 三年付", "斯巴达 24刀")
        val withoutForeignAsks = scrubbed
            .replace(FOREIGN_ASK_LABELED_PREFIX_REGEX, " ")
            .replace(FOREIGN_ASK_LABELED_SUFFIX_REGEX, " ")
            .replace(USDT_VERB_ASK_REGEX, " ")
            .replace(FOREIGN_VERB_ASK_FOR_RENEWAL_SCRUB_REGEX, " ")
        val contextCycle = findStandaloneCycle(withoutForeignAsks)

        RENEWAL_FOREIGN_PREFIX_REGEX.find(withoutForeignAsks)?.let { m ->
            val currency = parseCurrencyToken(m.groupValues[1])
            val amount = m.groupValues[2].toBigDecimalOrNull()
            if (currency != null && amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY) {
                return ParsedRenewalInfo(
                    amount = amount.setScale(2, RoundingMode.HALF_UP),
                    currency = currency,
                    cycle = contextCycle,
                    matchedExpression = m.value.trim(),
                    cycleExplicit = contextCycle != null,
                )
            }
        }
        RENEWAL_FOREIGN_SUFFIX_REGEX.find(withoutForeignAsks)?.let { m ->
            val amount = m.groupValues[1].toBigDecimalOrNull()
            val currency = parseCurrencyToken(m.groupValues[2])
            if (currency != null && amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY) {
                return ParsedRenewalInfo(
                    amount = amount.setScale(2, RoundingMode.HALF_UP),
                    currency = currency,
                    cycle = contextCycle,
                    matchedExpression = m.value.trim(),
                    cycleExplicit = contextCycle != null,
                )
            }
        }

        return null
    }

    private fun parseExpiryInternal(
        rawTitle: String,
        cleanedBody: String?,
        now: LocalDate,
    ): ParsedExpiryInfo? {
        val fromTitle = parseExpiryFromSingleText(rawTitle, now)
        if (fromTitle != null && !fromTitle.isApproximate) {
            return fromTitle
        }
        val fromBody = cleanedBody?.let { parseExpiryFromSingleText(it, now) }
        return when {
            fromTitle == null -> fromBody
            fromBody != null && !fromBody.isApproximate -> fromBody
            else -> fromTitle
        }
    }

    private fun parseExpiryFromSingleText(rawText: String, now: LocalDate): ParsedExpiryInfo? {
        if (rawText.isBlank()) return null
        val norm = normalizeWidth(rawText)
            .replace(URL_REGEX, " ")
            .replace(EMAIL_REGEX, " ")
            .replace(IPV4_CIDR_REGEX, " ")

        // 1. Date range (e.g. "2026-11-28 → 2026-12-31")
        EXPIRY_DATE_RANGE_REGEX.find(norm)?.let { m ->
            safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { d ->
                return buildExpiry(d, now, m.value.trim(), isApproximate = false)
            }
        }

        // 2. Explicitly labeled expiry date (到期时间：2026-12-01, Expiry: 2026-12-01)
        EXPIRY_LABELED_FULL_DATE_REGEX.find(norm)?.let { m ->
            val year = expandYear(m.groupValues[1].toInt())
            safeDate(year, m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { d ->
                return buildExpiry(d, now, m.value.trim(), isApproximate = false)
            }
        }
        EXPIRY_LABELED_YEAR_MONTH_REGEX.find(norm)?.let { m ->
            val year = expandYear(m.groupValues[1].toInt())
            val month = m.groupValues[2].toInt()
            val mod = m.groupValues[3]
            resolveYearMonthDate(year, month, mod, now)?.let { (d, approx) ->
                return buildExpiry(d, now, m.value.trim(), isApproximate = approx)
            }
        }
        EXPIRY_LABELED_MONTH_DAY_REGEX.find(norm)?.let { m ->
            val yearWord = m.groupValues[1]
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            resolveMonthDayDate(month, day, yearWord, now)?.let { d ->
                return buildExpiry(d, now, m.value.trim(), isApproximate = false)
            }
        }
        EXPIRY_LABELED_MONTH_ONLY_REGEX.find(norm)?.let { m ->
            val yearWord = m.groupValues[1]
            val month = m.groupValues[2].toInt()
            val mod = m.groupValues[3]
            resolveMonthOnlyDate(month, yearWord, mod, now)?.let { (d, approx) ->
                return buildExpiry(d, now, m.value.trim(), isApproximate = approx)
            }
        }

        // 3. Date followed by 到期 / 截止 / 续费
        EXPIRY_SUFFIX_FULL_DATE_REGEX.find(norm)?.let { m ->
            if (!isAlreadyRenewedPrefix(norm, m.range.first, m.value)) {
                val year = expandYear(m.groupValues[1].toInt())
                safeDate(year, m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { d ->
                    return buildExpiry(d, now, m.value.trim(), isApproximate = false)
                }
            }
        }
        EXPIRY_SUFFIX_YEAR_MONTH_REGEX.find(norm)?.let { m ->
            if (!isAlreadyRenewedPrefix(norm, m.range.first, m.value)) {
                val year = expandYear(m.groupValues[1].toInt())
                val month = m.groupValues[2].toInt()
                val mod = m.groupValues[3]
                resolveYearMonthDate(year, month, mod, now)?.let { (d, approx) ->
                    return buildExpiry(d, now, m.value.trim(), isApproximate = approx)
                }
            }
        }
        EXPIRY_SUFFIX_MONTH_DAY_REGEX.find(norm)?.let { m ->
            if (!isAlreadyRenewedPrefix(norm, m.range.first, m.value)) {
                val yearWord = m.groupValues[1]
                val month = m.groupValues[2].toInt()
                val day = m.groupValues[3].toInt()
                resolveMonthDayDate(month, day, yearWord, now)?.let { d ->
                    return buildExpiry(d, now, m.value.trim(), isApproximate = false)
                }
            }
        }
        EXPIRY_SUFFIX_MONTH_ONLY_REGEX.find(norm)?.let { m ->
            if (!isAlreadyRenewedPrefix(norm, m.range.first, m.value)) {
                val yearWord = m.groupValues[1]
                val month = m.groupValues[2].toInt()
                val mod = m.groupValues[3]
                resolveMonthOnlyDate(month, yearWord, mod, now)?.let { (d, approx) ->
                    return buildExpiry(d, now, m.value.trim(), isApproximate = approx)
                }
            }
        }

        // 4. Relative remaining days / months / half-year / years
        EXPIRY_REMAINING_DAYS_REGEX.find(norm)?.let { m ->
            val days = m.groupValues[1].toIntOrNull()
            if (days != null && days in 1..MAX_DERIVED_REMAINING_DAYS) {
                return ParsedExpiryInfo(
                    expiryDate = now.plusDays(days.toLong()),
                    remainingDays = days,
                    matchedExpression = m.value.trim(),
                    isApproximate = false,
                )
            }
        }
        EXPIRY_REMAINING_MONTHS_REGEX.find(norm)?.let { m ->
            val months = m.groupValues[1].toIntOrNull()
            if (months != null && months in 1..60) {
                val target = now.plusMonths(months.toLong())
                return buildExpiry(target, now, m.value.trim(), isApproximate = true)
            }
        }
        EXPIRY_REMAINING_HALF_YEAR_REGEX.find(norm)?.let { m ->
            val target = now.plusMonths(6L)
            return buildExpiry(target, now, m.value.trim(), isApproximate = true)
        }
        EXPIRY_REMAINING_YEARS_REGEX.find(norm)?.let { m ->
            val years = when (m.groupValues[1]) {
                "一", "1" -> 1
                "两", "二", "2" -> 2
                "三", "3" -> 3
                else -> m.groupValues[1].toIntOrNull()
            }
            if (years != null && years in 1..10) {
                val target = now.plusYears(years.toLong())
                return buildExpiry(target, now, m.value.trim(), isApproximate = true)
            }
        }
        EXPIRY_RELATIVE_SPECIAL_REGEX.find(norm)?.let { m ->
            val target = when {
                m.groupValues[1].contains("下") -> now.plusMonths(1L)
                m.groupValues[1].contains("月底") -> YearMonth.from(now).atEndOfMonth()
                else -> LocalDate.of(now.year, 12, 31)
            }
            return buildExpiry(target, now, m.value.trim(), isApproximate = true)
        }

        // 5. Bare full YYYY-MM-DD date not preceded/followed by purchase/creation context
        for (m in BARE_FULL_DATE_REGEX.findAll(norm)) {
            val prefix = norm.substring(maxOf(0, m.range.first - 12), m.range.first)
            val suffix = norm.substring(m.range.last + 1, minOf(norm.length, m.range.last + 8))
            if (NON_EXPIRY_DATE_BEFORE_REGEX.containsMatchIn(prefix)) continue
            if (NON_EXPIRY_DATE_AFTER_REGEX.containsMatchIn(suffix)) continue
            safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { d ->
                return buildExpiry(d, now, m.value.trim(), isApproximate = false)
            }
        }

        // 6. Fresh-cycle expressions ("满年", "整年", "新开", "刚开", "刚续费") as approximate full-cycle expiry
        EXPIRY_FRESH_FULL_YEAR_REGEX.find(norm)?.let { m ->
            val target = now.plusYears(1L)
            return buildExpiry(target, now, m.value.trim(), isApproximate = true)
        }
        EXPIRY_FRESH_CYCLE_REGEX.find(norm)?.let { m ->
            val prefix = norm.substring(maxOf(0, m.range.first - 14), m.range.first)
            if (!PRECEDING_DATE_BEFORE_FRESH_REGEX.containsMatchIn(prefix)) {
                val cycle = findStandaloneCycle(norm)
                val target = when (cycle) {
                    BillingCyclePreset.MONTHLY -> now.plusDays(30L)
                    BillingCyclePreset.QUARTERLY -> now.plusDays(90L)
                    BillingCyclePreset.SEMI_ANNUAL -> now.plusDays(180L)
                    BillingCyclePreset.BIENNIAL -> now.plusYears(2L)
                    BillingCyclePreset.TRIENNIAL -> now.plusYears(3L)
                    else -> now.plusYears(1L)
                }
                return buildExpiry(target, now, m.value.trim(), isApproximate = true)
            }
        }

        return null
    }

    private fun parseAskPriceInternal(
        rawTitle: String,
        cleanedBody: String?,
        fxToCny: (String) -> BigDecimal?,
    ): ParsedAskPrice? {
        if (isMultiMachineBundle(rawTitle)) return null
        // Priority: Title explicit (Tier 1/2) -> Body explicit (Tier 1/2) -> Title bare trailing (Tier 3)
        parseExplicitAskPrice(rawTitle, fxToCny)?.let { return it }
        if (!cleanedBody.isNullOrBlank()) {
            parseExplicitAskPrice(cleanedBody, fxToCny)?.let { return it }
        }
        parseBareTitleAskPrice(rawTitle)?.let { return it }
        parseResidualParSell(rawTitle)?.let { (amt, expr) ->
            return ParsedAskPrice(amt, expr, PricingConfidence.HIGH)
        }
        return null
    }

    private fun parseExplicitAskPrice(
        rawText: String,
        fxToCny: (String) -> BigDecimal?,
    ): ParsedAskPrice? {
        if (rawText.isBlank()) return null
        val norm = normalizeWidth(rawText)

        // Check foreign-currency explicit asks BEFORE scrubbing foreign currency tokens
        val preScrubbed = scrubNonAskCommon(norm)
        FOREIGN_ASK_LABELED_PREFIX_REGEX.find(preScrubbed)?.let { m ->
            val currency = parseCurrencyToken(m.groupValues[1])
            val amount = m.groupValues[2].toBigDecimalOrNull()
            convertForeignAskToCny(amount, currency, fxToCny, m.value.trim())?.let { return it }
        }
        FOREIGN_ASK_LABELED_SUFFIX_REGEX.find(preScrubbed)?.let { m ->
            val amount = m.groupValues[1].toBigDecimalOrNull()
            val currency = parseCurrencyToken(m.groupValues[2])
            convertForeignAskToCny(amount, currency, fxToCny, m.value.trim())?.let { return it }
        }
        USDT_VERB_ASK_REGEX.find(preScrubbed)?.let { m ->
            val amount = m.groupValues[1].toBigDecimalOrNull()
            convertForeignAskToCny(amount, ValuationCurrency.USD, fxToCny, m.value.trim())?.let { return it }
        }

        val scrubbed = scrubNonAskAll(norm)

        // Tier 1a: Explicit ask keyword in CNY (明盘300, 一口价88, 价格: 88, 自刀至200)
        ASK_LABELED_CNY_REGEX.find(scrubbed)?.let { m ->
            val amount = m.groupValues[1].toBigDecimalOrNull()
            if (amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY) {
                return ParsedAskPrice(
                    amountCny = amount.setScale(2, RoundingMode.HALF_UP),
                    matchedExpression = m.value.trim(),
                    confidence = PricingConfidence.HIGH,
                )
            }
        }

        // Tier 1c: Explicit CNY symbol (¥88) or suffix (120r, 300元, 88块, 200rmb)
        ASK_CNY_SYMBOL_REGEX.find(scrubbed)?.let { m ->
            val amount = m.groupValues[1].toBigDecimalOrNull()
            if (amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY) {
                return ParsedAskPrice(
                    amountCny = amount.setScale(2, RoundingMode.HALF_UP),
                    matchedExpression = m.value.trim(),
                    confidence = PricingConfidence.HIGH,
                )
            }
        }
        ASK_CNY_SUFFIX_REGEX.find(scrubbed)?.let { m ->
            val rawNumStr = m.groupValues[1]
            val amount = rawNumStr.toBigDecimalOrNull()
            if (amount != null && amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_PRICE_CNY) {
                return ParsedAskPrice(
                    amountCny = amount.setScale(2, RoundingMode.HALF_UP),
                    matchedExpression = m.value.trim(),
                    confidence = PricingConfidence.HIGH,
                )
            }
        }

        // Tier 2: Number directly followed by trade verb (88出, 300出, 260带原油出, 120转)
        for (m in ASK_NUMBER_WITH_VERB_REGEX.findAll(scrubbed)) {
            val rawNumStr = m.groupValues[1]
            if (rawNumStr in KNOWN_NUMERIC_MODEL_CODES) continue
            val amount = rawNumStr.toBigDecimalOrNull() ?: continue
            if (amount <= BigDecimal.ZERO || amount > MAX_REASONABLE_PRICE_CNY) continue
            val prefix = scrubbed.substring(maxOf(0, m.range.first - 12), m.range.first)
            if (BARE_PRECEDING_MODEL_PREFIX_REGEX.containsMatchIn(prefix)) continue
            return ParsedAskPrice(
                amountCny = amount.setScale(2, RoundingMode.HALF_UP),
                matchedExpression = m.value.trim(),
                confidence = PricingConfidence.HIGH,
            )
        }

        return null
    }

    private fun parseBareTitleAskPrice(rawTitle: String): ParsedAskPrice? {
        if (rawTitle.isBlank()) return null
        val norm = normalizeWidth(rawTitle)
        if (!TRADE_CONTEXT_HINT_REGEX.containsMatchIn(norm)) return null

        val scrubbed = scrubNonAskAll(norm)
            .replace(MODEL_CODE_REGEX, " ")
            .replace(DOUBLE_SHOPPING_DAY_REGEX, " ")
            .replace(COUNT_UNIT_SCRUB_REGEX, " ")

        val candidates = mutableListOf<Pair<BigDecimal, IntRange>>()
        for (m in ASK_BARE_NUMBER_REGEX.findAll(scrubbed)) {
            val rawNumStr = m.groupValues[1]
            if (rawNumStr in KNOWN_NUMERIC_MODEL_CODES) continue
            val intPart = rawNumStr.substringBefore('.').toIntOrNull()
            if (intPart != null && intPart in 2018..2035) continue
            val amount = rawNumStr.toBigDecimalOrNull() ?: continue
            if (amount < MIN_BARE_ASK_CNY || amount > MAX_BARE_ASK_CNY) continue

            val prefix = scrubbed.substring(maxOf(0, m.range.first - 14), m.range.first)
            if (BARE_PRECEDING_MODEL_PREFIX_REGEX.containsMatchIn(prefix)) continue

            val suffix = scrubbed.substring(m.range.last + 1, minOf(scrubbed.length, m.range.last + 10))
            if (BARE_FOLLOWING_UNIT_REGEX.containsMatchIn(suffix)) continue

            candidates += amount to m.range
        }

        val chosen = candidates.lastOrNull() ?: return null
        val scaled = chosen.first.setScale(2, RoundingMode.HALF_UP)
        val isTrailing = scrubbed.substring(chosen.second.last + 1).trim().length <= 12
        val confidence = if (candidates.size == 1 && isTrailing) {
            PricingConfidence.MEDIUM
        } else {
            PricingConfidence.LOW
        }
        return ParsedAskPrice(
            amountCny = scaled,
            matchedExpression = "挂牌¥${formatCompactCny(scaled)}",
            confidence = confidence,
        )
    }

    private fun convertForeignAskToCny(
        amount: BigDecimal?,
        currency: ValuationCurrency?,
        fxToCny: (String) -> BigDecimal?,
        matchedExpression: String,
    ): ParsedAskPrice? {
        if (amount == null || currency == null || amount <= BigDecimal.ZERO) return null
        val rate = if (currency == ValuationCurrency.CNY) {
            BigDecimal.ONE
        } else {
            fxToCny(currency.code)?.takeIf { it > BigDecimal.ZERO } ?: return null
        }
        val cny = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP)
        if (cny <= BigDecimal.ZERO || cny > MAX_REASONABLE_PRICE_CNY) return null
        return ParsedAskPrice(
            amountCny = cny,
            matchedExpression = matchedExpression,
            confidence = PricingConfidence.HIGH,
        )
    }

    private fun scrubNonRenewalNoise(norm: String): String = norm
        .replace(URL_REGEX, " ")
        .replace(EMAIL_REGEX, " ")
        .replace(IPV4_CIDR_REGEX, " ")
        .replace(PORT_REGEX, " ")
        .replace(ASN_REGEX, " ")
        .replace(CPU_RAM_REGEX, " ")
        .replace(BANDWIDTH_REGEX, " ")
        .replace(STORAGE_MEM_REGEX, " ")
        .replace(FULL_DATE_SCRUB_REGEX, " ")
        .replace(YEAR_MONTH_SCRUB_REGEX, " ")
        .replace(MONTH_DAY_SCRUB_REGEX, " ")
        .replace(DOT_DATE_CONTEXT_SCRUB_REGEX, " ")
        .replace(ACCOUNT_CREDIT_SCRUB_REGEX, " ")
        .replace(FEE_OR_BALANCE_PREFIX_REGEX, " ")
        .replace(FEE_OR_BALANCE_SUFFIX_REGEX, " ")

    private fun scrubNonAskCommon(norm: String): String = norm
        .replace(URL_REGEX, " ")
        .replace(EMAIL_REGEX, " ")
        .replace(IPV4_CIDR_REGEX, " ")
        .replace(PORT_REGEX, " ")
        .replace(ASN_REGEX, " ")
        .replace(CPU_RAM_REGEX, " ")
        .replace(BANDWIDTH_REGEX, " ")
        .replace(STORAGE_MEM_REGEX, " ")
        .replace(PERCENT_REGEX, " ")
        .replace(ZH_DISCOUNT_RATE_REGEX, " ")
        .replace(CARPOOL_RATIO_REGEX, " ")
        .replace(FULL_DATE_SCRUB_REGEX, " ")
        .replace(YEAR_MONTH_SCRUB_REGEX, " ")
        .replace(MONTH_DAY_SCRUB_REGEX, " ")
        .replace(DOT_DATE_CONTEXT_SCRUB_REGEX, " ")
        .replace(MONTH_EXPIRY_SCRUB_REGEX, " ")
        .replace(REMAINING_TIME_SCRUB_REGEX, " ")
        .replace(YEAR_ONLY_SCRUB_REGEX, " ")
        .replace(ACCOUNT_CREDIT_SCRUB_REGEX, " ")
        .replace(FEE_OR_BALANCE_PREFIX_REGEX, " ")
        .replace(FEE_OR_BALANCE_SUFFIX_REGEX, " ")
        .replace(PRICE_CUT_DELTA_REGEX, " ")

    private fun scrubNonAskAll(norm: String): String = scrubNonAskCommon(norm)
        .replace(RENEWAL_PREFIX_WITH_CYCLE_REGEX, " ")
        .replace(RENEWAL_SUFFIX_WITH_CYCLE_REGEX, " ")
        .replace(RENEWAL_CYCLE_FIRST_PREFIX_CUR_REGEX, " ")
        .replace(RENEWAL_CYCLE_FIRST_SUFFIX_CUR_REGEX, " ")
        .replace(RENEWAL_LABELED_PREFIX_CUR_REGEX, " ")
        .replace(RENEWAL_LABELED_SUFFIX_CUR_REGEX, " ")
        .replace(RENEWAL_FOREIGN_PREFIX_REGEX, " ")
        .replace(RENEWAL_FOREIGN_SUFFIX_REGEX, " ")
        .replace(SCRUB_SMALL_AFTER_WORD_CHU_REGEX) { m ->
            val amount = m.groupValues[1].toBigDecimalOrNull()
            if (amount != null && amount <= MAX_DIRECT_AFTER_CHU_SPREAD) " " else m.value
        }
        .replace(STATED_RESIDUAL_WITH_SPREAD_REGEX, " ")
        .replace(SCRUB_SPREAD_FOR_ASK_REGEX, " ")
        .replace(STATED_RESIDUAL_REGEX, " ")
        .replace(STATED_ORIG_PRICE_SCRUB_REGEX, " ")

    private fun detectIntent(rawTitle: String, cleanedBody: String?): TradeListingIntent {
        val titleIntent = detectIntentSingle(rawTitle)
        if (titleIntent != TradeListingIntent.UNKNOWN || cleanedBody.isNullOrBlank()) {
            return titleIntent
        }
        val leadBody = cleanedBody.lineSequence().take(3).joinToString(" ")
        return detectIntentSingle(leadBody)
    }

    private fun detectIntentSingle(text: String): TradeListingIntent {
        if (text.isBlank()) return TradeListingIntent.UNKNOWN
        val clean = stripLeadingDecorSymbols(text)
        if (clean.isEmpty()) return TradeListingIntent.UNKNOWN

        val leadBuy = LEADING_BUY_REGEX.containsMatchIn(clean)
        val leadSell = LEADING_SELL_REGEX.containsMatchIn(clean)
        if (leadBuy && !leadSell) return TradeListingIntent.BUY
        if (leadSell && !leadBuy) return TradeListingIntent.SELL

        val strongBuy = STRONG_INLINE_BUY_REGEX.containsMatchIn(clean)
        if (strongBuy && !EXPLICIT_SOLD_SELL_REGEX.containsMatchIn(clean)) {
            return TradeListingIntent.BUY
        }
        if (INLINE_SELL_REGEX.containsMatchIn(clean)) {
            return TradeListingIntent.SELL
        }
        return TradeListingIntent.UNKNOWN
    }

    private fun stripLeadingDecorSymbols(text: String): String {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            val isSkip = Character.isWhitespace(cp) ||
                type == Character.OTHER_SYMBOL.toInt() ||
                type == Character.MODIFIER_SYMBOL.toInt() ||
                type == Character.FORMAT.toInt() ||
                type == Character.NON_SPACING_MARK.toInt()
            if (!isSkip) break
            i += Character.charCount(cp)
        }
        return text.substring(i)
    }

    private fun findStandaloneCycle(text: String): BillingCyclePreset? {
        val match = STANDALONE_CYCLE_REGEX.find(text) ?: return null
        val token = match.groupValues[1].ifBlank { match.groupValues[2] }
        return parseCycleToken(token)
    }

    private fun parseCurrencyToken(raw: String): ValuationCurrency? = when (raw.trim().lowercase(Locale.ROOT)) {
        "$", "us$", "usd", "刀", "美刀", "美元", "美金", "u", "usdt" -> ValuationCurrency.USD
        "€", "eur", "欧", "欧元" -> ValuationCurrency.EUR
        "£", "gbp", "镑", "英镑" -> ValuationCurrency.GBP
        "hk$", "hkd", "港币", "港元", "港刀" -> ValuationCurrency.HKD
        "¥", "￥", "cny", "rmb", "元", "块", "r" -> ValuationCurrency.CNY
        "jpy", "日元", "円" -> ValuationCurrency.JPY
        "s$", "sgd", "新币", "新加坡元" -> ValuationCurrency.SGD
        "c$", "cad", "加元", "加币" -> ValuationCurrency.CAD
        "a$", "aud", "澳元", "澳币" -> ValuationCurrency.AUD
        "nt$", "twd", "台币", "新台币" -> ValuationCurrency.TWD
        "chf", "瑞郎" -> ValuationCurrency.CHF
        "₽", "rub", "卢布" -> ValuationCurrency.RUB
        "₺", "try", "里拉" -> ValuationCurrency.TRY
        "r$", "brl" -> ValuationCurrency.BRL
        else -> ValuationCurrency.fromCode(raw)
    }

    private fun parseCycleToken(raw: String): BillingCyclePreset? {
        val clean = raw.trim().lowercase(Locale.ROOT).replace(" ", "")
        return when (clean) {
            "m", "mo", "mos", "mon", "month", "months", "月", "月付", "每月", "按月", "monthly" ->
                BillingCyclePreset.MONTHLY
            "q", "qtr", "quarter", "季", "季度", "季付", "每季", "按季", "quarterly" ->
                BillingCyclePreset.QUARTERLY
            "半年", "半年付", "每半年", "semiannual", "semi-annual" ->
                BillingCyclePreset.SEMI_ANNUAL
            "y", "yr", "yrs", "year", "years", "年", "年付", "一年付", "1年付", "每年", "按年", "annual", "annually", "yearly" ->
                BillingCyclePreset.ANNUAL
            "2y", "2yr", "2yrs", "2year", "2years", "2年", "两年", "二年", "两年付", "二年付", "2年付", "每两年", "biennial" ->
                BillingCyclePreset.BIENNIAL
            "3y", "3yr", "3yrs", "3year", "3years", "3年", "三年", "三年付", "3年付", "每三年", "triennial" ->
                BillingCyclePreset.TRIENNIAL
            else -> null
        }
    }

    private fun expandYear(rawYear: Int): Int = if (rawYear < 100) 2000 + rawYear else rawYear

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? {
        if (year !in 2020..2040 || month !in 1..12 || day !in 1..31) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun resolveYearMonthDate(
        year: Int,
        month: Int,
        modifier: String,
        now: LocalDate,
    ): Pair<LocalDate, Boolean>? {
        if (year !in 2020..2040 || month !in 1..12) return null
        val ym = YearMonth.of(year, month)
        return when (modifier) {
            "初" -> ym.atDay(1) to true
            "底" -> ym.atEndOfMonth() to true
            else -> {
                val mid = ym.atDay(15)
                val adjusted = if (year == now.year && month == now.monthValue && mid < now) {
                    ym.atEndOfMonth()
                } else {
                    mid
                }
                adjusted to true
            }
        }
    }

    private fun resolveMonthDayDate(
        month: Int,
        day: Int,
        yearWord: String,
        now: LocalDate,
    ): LocalDate? {
        val explicitYear = when (yearWord) {
            "今年" -> now.year
            "明年" -> now.year + 1
            "后年" -> now.year + 2
            else -> null
        }
        if (explicitYear != null) {
            return safeDate(explicitYear, month, day)
        }
        val thisYear = safeDate(now.year, month, day)
        if (thisYear != null && !thisYear.isBefore(now)) {
            return thisYear
        }
        return safeDate(now.year + 1, month, day)
            ?: if (month == 2 && day == 29) safeDate(now.year + 1, 2, 28) else null
    }

    private fun resolveMonthOnlyDate(
        month: Int,
        yearWord: String,
        modifier: String,
        now: LocalDate,
    ): Pair<LocalDate, Boolean>? {
        if (month !in 1..12) return null
        val year = when (yearWord) {
            "今年" -> now.year
            "明年" -> now.year + 1
            "后年" -> now.year + 2
            else -> if (month < now.monthValue) now.year + 1 else now.year
        }
        return resolveYearMonthDate(year, month, modifier, now)
    }

    private fun isAlreadyRenewedPrefix(text: String, matchStart: Int, matchValue: String): Boolean {
        if (!matchValue.endsWith("续费")) return false
        val before = text.substring(maxOf(0, matchStart - 6), matchStart)
        return ALREADY_RENEWED_BEFORE_REGEX.containsMatchIn(before)
    }

    private fun buildExpiry(
        date: LocalDate,
        now: LocalDate,
        matchedExpression: String,
        isApproximate: Boolean,
    ): ParsedExpiryInfo {
        val remaining = ChronoUnit.DAYS.between(now, date).toInt()
        return ParsedExpiryInfo(
            expiryDate = date,
            remainingDays = remaining,
            matchedExpression = matchedExpression,
            isApproximate = isApproximate,
        )
    }

    private fun cleanBodyText(markdown: String): String = markdown
        .replace(FENCED_CODE_BLOCK_REGEX, "\n")
        .replace(MARKDOWN_IMAGE_REGEX, " ")
        .replace(MARKDOWN_LINK_REGEX, "$1")
        .replace(INLINE_CODE_REGEX, "$1")
        .replace(HTML_TAG_REGEX, " ")
        .replace(MD_DECOR_REGEX, " ")
        .replace('|', ' ')
        .trim()
        .take(MAX_BODY_CHARS)

    private fun normalizeWidth(raw: String): String {
        if (raw.isEmpty()) return ""
        val folded = TradeMarketPremiumAnalyzer.foldTraditionalToSimplified(raw)
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            val normalized = when (ch) {
                '＋' -> '+'
                '－' -> '-'
                '．' -> '.'
                '：' -> ':'
                '／' -> '/'
                '（' -> '('
                '）' -> ')'
                '【' -> '['
                '】' -> ']'
                '　' -> ' '
                in '０'..'９' -> ('0'.code + (ch.code - '０'.code)).toChar()
                in 'Ａ'..'Ｚ' -> ('A'.code + (ch.code - 'Ａ'.code)).toChar()
                in 'ａ'..'ｚ' -> ('a'.code + (ch.code - 'ａ'.code)).toChar()
                else -> ch
            }
            sb.append(normalized)
        }
        return sb.toString()
    }

    private fun formatDerivedExpression(askCny: BigDecimal, residualCny: BigDecimal): String =
        "推算: 挂牌¥${formatCompactCny(askCny)} − 残值¥${formatCompactCny(residualCny)}"

    private fun formatCompactCny(value: BigDecimal): String {
        val stripped = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (stripped.scale() <= 0) {
            stripped.setScale(0, RoundingMode.HALF_UP).toPlainString()
        } else {
            stripped.toPlainString()
        }
    }
}
