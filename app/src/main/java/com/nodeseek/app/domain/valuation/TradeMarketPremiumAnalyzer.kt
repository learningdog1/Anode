package com.nodeseek.app.domain.valuation

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object TradeMarketPremiumAnalyzer {

    private data class MerchantGroup(
        val canonical: String,
        val aliases: Set<String>,
        /**
         * Secondary aliases (e.g. a signature region like `卢森堡` for BuyVM) that imply this merchant
         * only when no explicit merchant alias is present, and are preserved in model tokens.
         */
        val impliedAliases: Set<String> = emptySet(),
    )

    private data class ExtractedQueryCandidate(
        val query: String,
        val merchant: MerchantGroup?,
        val modelTokenCount: Int,
        val usedSpecFallback: Boolean,
    )

    private val MERCHANT_GROUPS: List<MerchantGroup> = listOf(
        MerchantGroup("DMIT", setOf("dmit", "大妈")),
        MerchantGroup("BWG", setOf("bandwagonhost", "bandwagon", "bwg", "bwh", "瓦工", "搬瓦工")),
        MerchantGroup("RACKNERD", setOf("racknerd", "rn")),
        MerchantGroup("GREENCLOUD", setOf("greencloud", "greencloudvps", "绿云")),
        MerchantGroup("SPARTAN", setOf("spartan", "spartanhost", "斯巴达")),
        MerchantGroup("CLAW", setOf("claw", "clawcloud", "爪云", "阿里爪")),
        MerchantGroup("CLOUDCONE", setOf("cloudcone", "cc")),
        MerchantGroup("VIRMACH", setOf("virmach", "vir")),
        MerchantGroup("ORACLE", setOf("oracle", "甲骨文", "龟壳")),
        MerchantGroup("NETCUP", setOf("netcup", "nc")),
        MerchantGroup("HETZNER", setOf("hetzner", "hz")),
        MerchantGroup("ALICE", setOf("alice", "爱丽丝")),
        MerchantGroup("YXVM", setOf("yxvm")),
        MerchantGroup("V.PS", setOf("v.ps", "xtom")),
        MerchantGroup("AKILE", setOf("akile", "akilecloud")),
        MerchantGroup("WAP", setOf("wap", "wapac")),
        MerchantGroup("ZGO", setOf("zgo", "zgocloud")),
        MerchantGroup("RFC", setOf("rfc", "rfchost")),
        MerchantGroup("MISAKA", setOf("misaka", "御坂")),
        MerchantGroup("SALTYFISH", setOf("saltyfish", "saltyfishtech", "咸鱼云")),
        MerchantGroup("KIRINO", setOf("kirino", "kirinocloud")),
        MerchantGroup("SHARON", setOf("sharon", "sharoncloud", "sharonnetwork")),
        MerchantGroup("HALO", setOf("halo", "halocloud")),
        MerchantGroup("AKKO", setOf("akko", "akkocloud")),
        MerchantGroup("CERA", setOf("cera", "ceranetworks")),
        MerchantGroup("VOLC", setOf("volc", "volcengine", "火山引擎", "火山云")),
        MerchantGroup("HOSTHATCH", setOf("hosthatch", "hh")),
        MerchantGroup("DEDIPATH", setOf("dedipath")),
        MerchantGroup("BUYVM", setOf("buyvm", "frantech"), impliedAliases = setOf("卢森堡")),
        MerchantGroup("OVH", setOf("ovh", "ovhcloud", "kimsufi", "soyoustart")),
        MerchantGroup("CONTABO", setOf("contabo")),
        MerchantGroup("KURUN", setOf("kurun", "库伦")),
        MerchantGroup("VMISS", setOf("vmiss")),
        MerchantGroup("BAGE", setOf("bage", "bagevm")),
        MerchantGroup("HOSTDARE", setOf("hostdare")),
        MerchantGroup("HYTRON", setOf("hytron")),
        MerchantGroup("ISIF", setOf("isif", "isifcloud")),
        MerchantGroup("GIGSGC", setOf("gigsgigscloud", "ggc")),
        MerchantGroup("COLOCROSSING", setOf("colocrossing")),
        MerchantGroup("BYTEVIRT", setOf("bytevirt")),
        MerchantGroup("VULTR", setOf("vultr")),
        MerchantGroup("LINODE", setOf("linode")),
        MerchantGroup("DIGITALOCEAN", setOf("digitalocean")),
    )

    private val MODEL_SYNONYM_GROUPS: List<Set<String>> = listOf(
        // Lines / routes
        setOf("softbank", "软银", "sb"),
        setOf("9929", "as9929"),
        setOf("4837", "as4837"),
        setOf("163", "as4134"),
        setOf("gia", "giae"),
        setOf("eb", "eyeball"),
        // Regions / cities / countries
        setOf("tokyo", "tyo", "东京"),
        setOf("osaka", "kix", "大阪"),
        setOf("lax", "la", "losangeles", "美西", "洛杉矶"),
        setOf("sjc", "sanjose", "圣何塞"),
        setOf("sea", "seattle", "西雅图"),
        setOf("dal", "dallas", "达拉斯"),
        setOf("nyc", "newyork", "纽约"),
        setOf("chi", "chicago", "芝加哥"),
        setOf("mia", "miami", "迈阿密"),
        setOf("hk", "hkg", "香港"),
        setOf("sg", "sin", "sgp", "新加坡"),
        setOf("jp", "jpn", "日本"),
        setOf("us", "usa", "美国"),
        setOf("de", "deu", "德国"),
        setOf("fra", "frankfurt", "法兰克福"),
        setOf("uk", "gbr", "英国"),
        setOf("lon", "london", "伦敦"),
        setOf("nl", "nld", "荷兰"),
        setOf("ams", "amsterdam", "阿姆斯特丹"),
        setOf("fr", "paris", "法国", "巴黎"),
        setOf("kr", "kor", "韩国"),
        setOf("seoul", "icn", "首尔"),
        setOf("tw", "tpe", "台湾", "台北"),
        setOf("lu", "lux", "luxembourg", "卢森堡"),
        setOf("fi", "fin", "hel", "helsinki", "芬兰"),
        setOf("ca", "can", "加拿大"),
        setOf("au", "aus", "澳洲", "澳大利亚"),
        // Data centers (zero-padded and unpadded)
        setOf("dc1", "dc01"),
        setOf("dc2", "dc02"),
        setOf("dc3", "dc03"),
        setOf("dc4", "dc04"),
        setOf("dc5", "dc05"),
        setOf("dc6", "dc06"),
        setOf("dc7", "dc07"),
        setOf("dc8", "dc08"),
        setOf("dc9", "dc09"),
        // Series / plan synonyms
        setOf("biggerbox", "biggerboxpro", "bigbox"),
        setOf("megabox", "megaboxpro"),
        setOf("theplan", "plan"),
        setOf("blackfriday", "bf", "黑五"),
        setOf("palmspring", "palmsprings"),
    )

    private val KNOWN_NUMERIC_MODEL_TOKENS: Set<String> = setOf(
        "9929", "4837", "163", "2222", "2525", "1111", "3333", "9999",
    )

    private val BWG_SELF_SUFFICIENT_SKUS: Set<String> = setOf(
        "megabox", "megaboxpro", "biggerbox", "biggerboxpro", "bigbox",
        "minibox", "powerbox", "sakurabox", "chicken",
    )

    private val BWG_PRO_IMPLIED_SKUS: Set<String> = setOf(
        "megabox", "megaboxpro", "biggerbox", "biggerboxpro", "bigbox",
    )

    private val KNOWN_CJK_DOMAIN_ENTITIES: Set<String> = buildSet {
        MERCHANT_GROUPS.forEach { group ->
            group.aliases.filter { it.any { ch -> ch.code >= 128 } }.forEach(::add)
            group.impliedAliases.filter { it.any { ch -> ch.code >= 128 } }.forEach(::add)
        }
        MODEL_SYNONYM_GROUPS.forEach { group ->
            group.filter { it.any { ch -> ch.code >= 128 } }.forEach(::add)
        }
        addAll(
            listOf(
                "美东", "俄罗斯", "双十一", "圣诞", "周年", "闪购",
                "直连", "精品网", "三网优化", "轻量",
            ),
        )
    }

    private val KNOWN_DOMAIN_TOKENS_LOWER: Set<String> = buildSet {
        MODEL_SYNONYM_GROUPS.forEach { addAll(it) }
        addAll(KNOWN_NUMERIC_MODEL_TOKENS)
        addAll(KNOWN_CJK_DOMAIN_ENTITIES)
        addAll(
            listOf(
                "pro", "eb", "t1", "wee", "tiny", "starter", "mini", "micro", "nano",
                "malibu", "palmspring", "palmsprings", "victoria", "corona", "intro",
                "lite", "standard", "basic", "eco", "economy", "vol", "hyper", "co", "free",
                "plus", "max", "ultra", "small", "medium", "large", "special", "box",
                "megabox", "biggerbox", "minibox", "powerbox", "sakurabox", "plan", "chicken",
                "cn2", "gia", "cmin2", "iij", "bgp", "ntt", "bbtec", "cmi", "ct", "cu", "cm", "nat",
                "ks", "le", "s1", "s2", "s3", "v1", "v2", "v3",
                "dc1", "dc01", "dc2", "dc02", "dc3", "dc03", "dc4", "dc04",
                "dc5", "dc05", "dc6", "dc06", "dc7", "dc07", "dc8", "dc08", "dc9", "dc09",
                "dc10", "dc11", "dc12",
            ),
        )
    }

    private val LOCATION_TOKENS_LOWER: Set<String> = setOf(
        "lax", "la", "losangeles", "美西", "洛杉矶",
        "sjc", "sanjose", "圣何塞",
        "sea", "seattle", "西雅图",
        "dal", "dallas", "达拉斯",
        "nyc", "newyork", "纽约",
        "chi", "chicago", "芝加哥",
        "mia", "miami", "迈阿密",
        "美东",
        "hk", "hkg", "香港",
        "sg", "sin", "sgp", "新加坡",
        "jp", "jpn", "日本",
        "tokyo", "tyo", "东京",
        "osaka", "kix", "大阪",
        "us", "usa", "美国",
        "de", "deu", "德国",
        "fra", "frankfurt", "法兰克福",
        "uk", "gbr", "英国",
        "lon", "london", "伦敦",
        "nl", "nld", "荷兰",
        "ams", "amsterdam", "阿姆斯特丹",
        "fr", "paris", "法国", "巴黎",
        "kr", "kor", "韩国",
        "seoul", "icn", "首尔",
        "tw", "tpe", "台湾", "台北",
        "lu", "lux", "luxembourg", "卢森堡",
        "fi", "fin", "hel", "helsinki", "芬兰",
        "ca", "can", "加拿大",
        "au", "aus", "澳洲", "澳大利亚",
        "俄罗斯",
    )

    // Broad region tokens -> specific cities/DCs that supersede them when both appear in the same title
    private val BROAD_REGION_SUPERSEDED_BY: Map<Set<String>, Set<String>> = mapOf(
        setOf("jp", "jpn", "日本") to setOf("tokyo", "tyo", "东京", "osaka", "kix", "大阪"),
        setOf("us", "usa", "美国") to setOf(
            "lax", "la", "losangeles", "美西", "洛杉矶",
            "sjc", "sanjose", "圣何塞",
            "sea", "seattle", "西雅图",
            "dal", "dallas", "达拉斯",
            "nyc", "newyork", "纽约",
            "chi", "chicago", "芝加哥",
            "mia", "miami", "迈阿密",
            "美东", "dc2", "dc02", "dc6", "dc06", "dc9", "dc09",
        ),
        setOf("美西") to setOf("sjc", "sanjose", "圣何塞", "sea", "seattle", "西雅图"),
        setOf("de", "deu", "德国") to setOf("fra", "frankfurt", "法兰克福"),
        setOf("uk", "gbr", "英国") to setOf("lon", "london", "伦敦"),
        setOf("nl", "nld", "荷兰") to setOf("ams", "amsterdam", "阿姆斯特丹"),
        setOf("kr", "kor", "韩国") to setOf("seoul", "icn", "首尔"),
    )

    private val CJK_NOISE_PHRASES: List<String> = listOf(
        // Multi-char phrases first (sorted by descending length in regex builder)
        "按剩余价值出", "按净残值出", "按残值出", "剩余价值平出", "剩余价值出", "剩余价值转",
        "净残值出", "净残值转", "残值出", "残值转", "剩价出", "原价转让", "原价出", "原价转",
        "平价出", "平价转", "无溢价出", "不溢价出", "溢价出", "折价出", "亏本出", "血亏出",
        "割肉出", "低价出", "便宜出", "随缘出", "佛系出", "诚心出", "诚意出", "打包出",
        "溢价收", "原价收", "折价收", "高价收", "诚心收", "诚意收", "求购一台",
        "中介费自理", "可走中介", "中介自理", "包中介", "走中介", "可中介",
        "记者勿扰", "刀客勿扰", "大刀勿扰", "鸽子勿扰", "非诚勿扰", "明盘不刀",
        "工单改邮", "工单push", "免费push", "自备邮箱", "原始邮箱", "带原油出", "带原邮出",
        "移动快乐", "联通快乐", "电信快乐", "续费同价", "循环优惠", "永久优惠", "高性价比",
        "无限流量", "原生ip", "干净ip", "已实测",
        "剩余价值", "净残值", "残值", "剩价", "原价", "溢价", "折价", "亏本", "血亏", "让利", "倒贴",
        "传家宝", "性价比", "优惠码", "流媒体", "全解锁", "支付宝", "站内信", "清库存", "用不上",
        "急用钱", "顶一下", "出一台", "出一个", "出一只", "转一台", "收一台", "收一个", "蹲一台",
        "带原油", "带原邮", "给原油", "给原邮", "含原油", "送原油", "包原油", "带邮箱", "改邮箱",
        "出掉了", "已出掉", "已成交", "出了吧", "收到了", "可小刀", "半年付", "两年付", "二年付",
        "三年付", "四年付", "五年付", "一次性", "到期日", "续费日", "有效期", "月流量", "年流量",
        "快乐鸡", "大盘鸡", "落地鸡", "线路鸡", "优化鸡", "亚太鸡", "美西鸡", "限量版", "限量款",
        "走闲鱼", "包过户", "包push",
        "平出", "平转", "秒出", "急出", "慢出", "自刀出", "单出", "合出", "出个", "出只", "出鸡", "转个",
        "诚收", "急收", "慢收", "收个", "收只", "求购", "求收", "想收", "蹲个", "已出", "已售",
        "出了", "已收", "出售", "出让", "转让", "过户", "明盘", "底价", "现价", "售价", "出价",
        "预算", "自刀", "小刀", "大刀", "可刀", "不刀", "勿扰", "原油", "原邮", "带邮", "改邮",
        "邮箱", "账号", "整号", "连号", "工单", "中介", "先款", "先机", "闲鱼", "口令", "微信",
        "私信", "私聊", "联系", "签名", "滴滴", "极品", "神鸡", "好鸡", "小鸡", "母鸡", "灵车",
        "吃灰", "回血", "退坑", "闲置", "出清", "打包", "全新", "刚买", "新购", "绝版", "限量",
        "稀有", "原生", "干净", "免费", "解锁", "自用", "稳定", "一手", "福利", "超值", "实惠", "便宜", "帮顶", "实测",
        "降级", "测评", "鸡腿", "到期", "续费", "年付", "月付", "季付", "永久", "终身", "按年",
        "按月", "每年", "每月", "一年", "两年", "三年", "周期", "流量", "双向", "单向", "硬盘",
        "磁盘", "内存", "核心", "带宽", "口子", "端口", "杜甫", "独服", "机器", "配置", "型号",
        "套餐", "机型", "产品", "商家", "还有", "剩余", "明年", "今年", "下月", "月底", "年底",
        "不议价", "一口价", "包邮", "包", "出", "收", "转",
    )

    private val QUERY_NOISE_TOKENS: Set<String> = buildSet {
        addAll(CJK_NOISE_PHRASES)
        addAll(
            listOf(
                // Standalone Chinese particles / stray trade characters (only filtered when standalone)
                "的", "了", "吧", "啊", "呢", "吗", "个", "只", "台", "套", "枚", "款", "版",
                "鸡", "卖", "买", "换", "带", "送", "给", "含", "无", "不", "可", "能", "有",
                "很", "超", "真", "纯", "全", "自", "原", "明", "急", "慢", "秒", "平", "亏",
                "低", "高", "小", "大", "多", "少", "加", "减", "按", "走", "求", "蹲", "诚",
                "想", "要", "用", "购", "年", "月", "天", "付", "元", "块", "刀", "折", "价",
                "值", "费", "邮", "油", "口", "房", "三网", "网", "线", "路",
                // English trade / billing / hardware / generic stopwords
                "the", "for", "with", "and", "or", "a", "an", "of", "to", "in", "on", "at",
                "by", "from", "is", "are", "sale", "sell", "selling", "buy", "buying", "trade",
                "wts", "wtb", "wtt", "vps", "server", "machine", "vm", "pvm", "sold", "closed",
                "push", "email", "mail", "original", "renew", "renewal", "expire", "expires",
                "expiring", "expiry", "year", "years", "month", "months", "yr", "mo", "annual",
                "monthly", "quarterly", "biennial", "triennial", "yearly", "traffic", "bandwidth",
                "ram", "cpu", "vcpu", "disk", "ssd", "nvme", "hdd", "ipv4", "ipv6", "ip", "kvm",
                "pm", "tg", "dd", "usdt", "cny", "rmb", "usd", "eur", "hkd", "gbp", "jpy", "sgd",
                "cad", "aud",
            ),
        )
    }

    private val CJK_ENTITY_SEGMENT_REGEX: Regex = Regex(
        KNOWN_CJK_DOMAIN_ENTITIES
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) },
    )

    private val CJK_NOISE_SEGMENT_REGEX: Regex = Regex(
        CJK_NOISE_PHRASES
            .filter { it.length >= 2 }
            .distinct()
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    )

    private val SINGLE_CHAR_SUFFIX_PREDECESSORS: Set<String> = setOf(
        "le", "ks", "tier", "gen", "type", "rev", "edition", "plan",
    )

    // Intent & sold detection
    private val CLOSED_SOLD_REGEX = Regex(
        """(?:\[(?:已出|已售|已成交|sold|closed)]|【(?:已出|已售|已成交|sold|closed)】|(?:^|\s)(?:已出|已售|秒出|出了)(?=\s|$|[，,。！!]))""",
        RegexOption.IGNORE_CASE,
    )
    private val NON_TRADE_SOLD_FALSE_POSITIVE_REGEX = Regex("""(?:公告|通知|教程|脚本|测评|新闻)\s*已出""")

    private val BUY_INTENT_REGEX = Regex(
        """(?:^\s*(?:\[(?:收|求购)]|【(?:收|求购)】|诚收|急收|慢收|收一台|收个|收\s)|(?:原价收|溢价收|折价收))""",
        RegexOption.IGNORE_CASE,
    )
    private val SELL_INTENT_REGEX = Regex(
        """(?:^\s*(?:\[(?:出|转|已出|已售|售)]|【(?:出|转|已出|已售|售)】|出\s|转\s|出一台|出个|出一个|急出|慢出|自刀出)|(?:溢价出|折价出|平出|原价出|原价转|剩余价值|净残值|残值出|明盘|秒出|包\s*push))""",
        RegexOption.IGNORE_CASE,
    )

    // Stage 1 scrubbers (order matters!)
    private val SCRUB_IPV4_CIDR_REGEX = Regex("""\b\d{1,3}(?:\.(?:\d{1,3}|\*)){3}(?:/\d{1,2})?\b""")
    private val SCRUB_URL_REGEX = Regex("""(?:https?|nsapp)://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val SCRUB_EMAIL_REGEX = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
    private val CPU_RAM_SPEC_CAPTURE_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(\d+(?:\.\d+)?)\s*(?:v?cpu|[ch核])\s*[/\-+]?\s*(\d+(?:\.\d+)?)\s*(gb|mb|g|m)(?![A-Za-z0-9])""",
    )
    private val SCRUB_CPU_RAM_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:v?cpu|[ch核])\s*[/\-+]?\s*\d+(?:\.\d+)?\s*(?:gb|mb|g|m)(?:\s*[/\-+]?\s*\d+(?:\.\d+)?\s*(?:gb|mb|tb|g|m|t))*(?![A-Za-z0-9])""",
    )
    private val SCRUB_BANDWIDTH_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:[gm]bps|[gm]口子?)(?![A-Za-z0-9])""",
    )
    private val SCRUB_STORAGE_MEM_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:gb|mb|tb|kb|g|m|t)(?![a-z0-9])""",
    )
    private val SCRUB_STORAGE_MEM_WITH_UNIT_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:gb|mb|tb|kb|g|m|t)(?![a-z0-9])(?:\s*(?:月流量|年流量|流量|双向|单向|硬盘|内存|磁盘|带宽|口子?))?""",
    )
    private val SCRUB_FULL_DATE_REGEX = Regex(
        """(?<![0-9A-Za-z])(?:19|20)\d{2}[-/.年]\d{1,2}(?:[-/.月]\d{1,2}日?|月)?(?:\s*(?:到期|续费|截止))?(?![0-9A-Za-z])""",
    )
    private val SCRUB_MONTH_DAY_REGEX = Regex(
        """(?<![0-9A-Za-z])(?:0?[1-9]|1[0-2])[-/月](?:0?[1-9]|[12]\d|3[01])日?(?:\s*(?:到期|续费))?(?![0-9A-Za-z.])""",
    )
    private val SCRUB_MONTH_EXPIRY_REGEX = Regex(
        """(?<![0-9A-Za-z])(?:0?[1-9]|1[0-2])月\s*(?:到期|续费)""",
    )
    private val SCRUB_DOT_DATE_CONTEXT_REGEX = Regex(
        """(?:(?:到期|续费|时间|日期|截止|购买|买于|购于)\s*[:：]?\s*(?:0?[1-9]|1[0-2])\.(?:0?[1-9]|[12]\d|3[01])日?(?![0-9A-Za-z.])|(?<![0-9A-Za-z.+＋\-－])(?:0?[1-9]|1[0-2])\.(?:0?[1-9]|[12]\d|3[01])\s*(?:到期|续费|号|日)(?![0-9A-Za-z.]))""",
    )
    private val SCRUB_REMAINING_DAYS_REGEX = Regex("""(?:还有|剩余|剩)\s*\d{1,4}\s*天""")
    private val SCRUB_YEAR_TOKEN_FOR_SPREAD_REGEX = Regex(
        """(?<![0-9A-Za-z])(?:19|20)\d{2}(?:\s*(?:年|到期|款|黑五|双十一))?(?![0-9A-Za-z])""",
    )
    private val SCRUB_YEAR_TOKEN_FOR_QUERY_REGEX = Regex(
        """(?<![0-9A-Za-z])(?:19|20)\d{2}(?:\s*(?:年|到期|款))?(?![0-9A-Za-z])""",
    )
    private val SCRUB_PORT_REGEX = Regex("""(?<=[A-Za-z0-9\]])(?::\d{2,5})\b""")
    private val SCRUB_ASN_REGEX = Regex("""(?i)\bAS\d+\b""")
    private val SCRUB_LINE_MODEL_CODE_REGEX = Regex(
        """(?i)\b(?:cn2|9929|4837|163|cmin2|dc-?\d{1,2}|hk\d{2}|v\d+(?:\.\d+)?|g\d+(?:\.\d+)?)\b""",
    )
    private val SCRUB_PERCENT_REGEX = Regex("""\d+(?:\.\d+)?\s*%""")
    private val SCRUB_ZH_DISCOUNT_RATE_REGEX = Regex("""\d+(?:\.\d+)?\s*折(?![价])""")
    private val SCRUB_FOREIGN_CURRENCY_PREFIX_REGEX = Regex(
        """(?i)(?:us\$|hk\$|s\$|c\$|a\$|nt\$|\$|€|£)\s*\d+(?:\.\d+)?(?:\s*/\s*(?:yr|mo|year|month|y|m|q|年|季|月))?""",
    )
    private val SCRUB_FOREIGN_CURRENCY_SUFFIX_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:刀|美元|美金|欧元|欧|英镑|港币|港元|日元|新币|加元|澳元|(?:usd|eur|hkd|gbp|jpy|sgd|cad|aud|u)(?![a-z0-9]))(?:\s*/\s*(?:yr|mo|year|month|y|m|q|年|季|月))?""",
    )
    private val SCRUB_CNY_PRICE_FOR_QUERY_REGEX = Regex(
        """(?i)(?:(?:[¥￥]|rmb|cny|明盘|一口价|底价|现价|售价|价格|自刀|降价|续费|原价|残值|剩余价值|净残值)\s*[:：]?\s*\d+(?:\.\d+)?\s*(?:元|块|rmb|cny|r|[¥￥]|刀|u)?(?:\s*(?:出|收|转))?|(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:元|块|(?:rmb|cny|r)(?![a-z0-9]))(?:\s*(?:出|收|转))?)""",
    )
    private val SCRUB_BARE_BUYOUT_FOR_QUERY_REGEX = Regex(
        """(?<![A-Za-z0-9+\-])(\d{1,5}(?:\.\d{1,2})?)\s*(?:出|收|转)(?![现口发来错品席身生版差站墙狱])""",
    )
    private val SCRUB_BILLING_SLASH_REGEX = Regex(
        """(?i)/\s*(?:yr|year|mo|month|quarter|y|m|q|年|季|月|半年|两年|三年)(?![a-z0-9])""",
    )
    private val SCRUB_TOTAL_ASK_AFTER_WORD_REGEX = Regex(
        """(?:溢价出|折价出|亏本出|血亏出|亏出)\s*[:：]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|r|R|cny|CNY|¥|￥)?""",
    )
    private val MAX_DIRECT_AFTER_CHU_SPREAD = BigDecimal("100")

    // Stage 2 spread extraction rules
    private val EXPLICIT_PREMIUM_WORD_REGEX = Regex(
        """(?:小|微)?(?:溢价|加价)(?:出)?\s*[+＋:：]?\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|r|R|cny|CNY|¥|￥)?(?![%折])""",
    )
    private val EXPLICIT_REMAINING_PLUS_REGEX = Regex(
        """(?:剩余价值|净残值|剩价|残值|原价)\s*[+＋加]\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|r|R|cny|CNY|¥|￥)?""",
    )
    private val EXPLICIT_REMAINING_COLON_REGEX = Regex(
        """(?:剩余价值|净残值|剩价|残值|原价)\s*[:：]\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|r|R|cny|CNY|¥|￥)?(?![%折])""",
    )
    private val EXPLICIT_DISCOUNT_WORD_REGEX = Regex(
        """(?:(?:折价|亏本|让利)(?:出)?|血?亏了?|倒贴)\s*[-－:：]?\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|r|R|cny|CNY|¥|￥)?(?:\s*出)?(?![%折])""",
    )
    private val EXPLICIT_REMAINING_MINUS_REGEX = Regex(
        """(?:剩余价值|净残值|剩价|残值|原价)\s*[-－减]\s*[¥￥]?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|r|R|cny|CNY|¥|￥)?""",
    )
    private val STANDALONE_SIGNED_BEFORE_CHU_REGEX = Regex(
        """(?<![A-Za-z0-9\-])([+\-＋－])(\d{1,5}(?:\.\d{1,2})?)\s*(?:元|块|r|R|cny|CNY|¥|￥)?\s*(?:出(?![现口发来错品席身生版差站墙狱])|收(?![费入益藏到录])|包\s*push|带原[油邮]|原[油邮])""",
        RegexOption.IGNORE_CASE,
    )
    private val EXPLICIT_PAR_REGEX = Regex(
        """(?:平出|平价出|平转|平价转|原价出|原价转|原价转让|无溢价出|不溢价出|按(?:剩余价值|净残值|残值)(?:出|转)|(?:剩余价值|净残值|残值|剩价)\s*平?(?:出|转|转让))(?!\s*[+＋\-－加减]\s*\d)""",
    )

    private val MAX_REASONABLE_SPREAD = BigDecimal("50000")

    private val SOURCE_PRIORITY: Map<MarketDataSource, Int> = mapOf(
        MarketDataSource.FEED_CACHE to 4,
        MarketDataSource.REMOTE_SEARCH to 3,
        MarketDataSource.BOOKMARK to 2,
        MarketDataSource.READ_HISTORY to 1,
    )

    private val SUGGEST_STRIP_STATUS_BRACKETS_REGEX = Regex(
        """[\[【]\s*(?:出|收|求购|转|转让|合租|拼车|已出|已售|已出掉|出掉了|已成交|秒出|急出|慢出|自刀|降价|明盘|溢价|折价|平出|原价出|包\s*push|带原[油邮]|原[油邮]|改邮|交易|出鸡|收鸡|sold|closed|已满车|车已满|满车|已发车|\d{1,2}\s*=\s*\d{1,2})\s*[\]】]""",
        RegexOption.IGNORE_CASE,
    )

    // Product code compound normalizers
    private val NORM_DC_CODE_REGEX = Regex("""(?i)(?<![A-Za-z0-9])dc[- ](\d{1,2})(?![A-Za-z0-9])""")
    private val NORM_HYPHEN_MODEL_NUM_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(rs|ax|ex|cx|cpx|cax|ccx)[- ](\d{2,4})(?![A-Za-z0-9])""",
    )
    private val NORM_NETCUP_VPS_NUM_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(vps)[- ](200|250|500|1000|2000|3000|4000|6000|8000)(?![A-Za-z0-9])""",
    )
    private val NORM_GIA_E_REGEX = Regex("""(?i)(?<![A-Za-z0-9])gia-e(?![A-Za-z0-9])""")
    private val NORM_MEGABOX_PRO_REGEX = Regex("""(?i)(?<![A-Za-z0-9])megabox[- ]?pro(?![A-Za-z0-9])""")
    private val NORM_BIGGERBOX_PRO_REGEX = Regex("""(?i)(?<![A-Za-z0-9])biggerbox[- ]?pro(?![A-Za-z0-9])""")
    private val NORM_THE_PLAN_REGEX = Regex("""(?i)(?<![A-Za-z0-9])the[- ]?plan(?![A-Za-z0-9])""")
    private val NORM_PVM_PREFIX_REGEX = Regex("""(?i)(?<![A-Za-z0-9])pvm[.\-](?=[A-Za-z])""")
    private val NORM_DOUBLE_ELEVEN_REGEX = Regex("""双\s*11(?![0-9])""")

    // Markdown body cleanup for fallbackText extraction
    private val FENCED_CODE_BLOCK_REGEX = Regex("""```[\s\S]*?```""")
    private val MARKDOWN_IMAGE_REGEX = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val FALLBACK_LABELED_LINE_REGEX = Regex(
        """(?:^|[，,。\s])(?:型号|机型|套餐|产品|配置|机器|商家|plan|model|product)\s*[:：]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    fun normalizeWidthAndCase(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
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
                in 'Ａ'..'Ｚ' -> ('a'.code + (ch.code - 'Ａ'.code)).toChar()
                in 'ａ'..'ｚ' -> ch
                else -> ch.lowercaseChar()
            }
            sb.append(normalized)
        }
        return sb.toString()
    }

    private fun normalizeWidthPreservingCase(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
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

    @JvmOverloads
    fun extractSuggestedQuery(
        title: String,
        fallbackText: String? = null,
    ): String {
        val titleCandidate = extractQueryFromText(title, requireMerchant = false)
        val hasStrongTitleQuery = titleCandidate.merchant != null &&
            titleCandidate.modelTokenCount > 0 &&
            !titleCandidate.usedSpecFallback
        if (hasStrongTitleQuery || fallbackText.isNullOrBlank()) {
            return titleCandidate.query
        }

        val fallbackCandidate = extractQueryFromFallbackBody(
            fallbackText = fallbackText,
            preferredMerchant = titleCandidate.merchant,
        )
        if (fallbackCandidate.query.isBlank()) {
            return titleCandidate.query
        }

        return when {
            titleCandidate.query.isBlank() -> fallbackCandidate.query
            titleCandidate.merchant == null && fallbackCandidate.merchant != null -> fallbackCandidate.query
            titleCandidate.merchant != null &&
                fallbackCandidate.merchant?.canonical == titleCandidate.merchant.canonical &&
                fallbackCandidate.modelTokenCount > titleCandidate.modelTokenCount -> fallbackCandidate.query
            titleCandidate.merchant != null &&
                titleCandidate.modelTokenCount == 0 &&
                !titleCandidate.usedSpecFallback &&
                fallbackCandidate.modelTokenCount > 0 -> fallbackCandidate.query
            else -> titleCandidate.query
        }
    }

    /**
     * Builds progressively simpler, punctuation-safe fallback queries for remote forum search
     * when a multi-token model query returns HTTP 404 or zero remote hits.
     */
    fun buildSearchFallbackQueries(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val widthNorm = normalizeProductCodes(normalizeWidthPreservingCase(trimmed))
        val tokens = splitTokens(segmentKnownCjkWords(widthNorm))
            .filterNot { it.lowercase(Locale.ROOT) in QUERY_NOISE_TOKENS }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (tokens.size <= 1) return emptyList()

        val merchant = detectMerchantGroup(tokens.joinToString(" ").lowercase(Locale.ROOT))
        val merchantCanon = merchant?.canonical
        val modelTokens = if (merchant != null) {
            tokens.filterNot { it.lowercase(Locale.ROOT) in merchant.aliases }
        } else {
            tokens
        }

        val fallbacks = LinkedHashSet<String>()
        val originalLower = trimmed.lowercase(Locale.ROOT)

        fun addCandidate(parts: List<String>) {
            val joined = parts.joinToString(" ").trim()
            if (joined.isNotEmpty() && joined.lowercase(Locale.ROOT) != originalLower) {
                fallbacks += joined
            }
        }

        // 1. If BWG MEGABOX PRO / BIGGERBOX PRO -> drop trailing PRO first
        if (merchantCanon == "BWG" &&
            modelTokens.size >= 2 &&
            modelTokens.any { it.lowercase(Locale.ROOT) in BWG_PRO_IMPLIED_SKUS }
        ) {
            val sku = modelTokens.first { it.lowercase(Locale.ROOT) in BWG_PRO_IMPLIED_SKUS }
            addCandidate(listOf("BWG", sku))
        }

        // 2. If merchant + >=3 model tokens with a location token -> drop location token while keeping exact tier/plan
        if (merchantCanon != null && modelTokens.size >= 3) {
            val nonLocation = modelTokens.filterNot { it.lowercase(Locale.ROOT) in LOCATION_TOKENS_LOWER }
            if (nonLocation.isNotEmpty() && nonLocation.size < modelTokens.size) {
                addCandidate(listOf(merchantCanon) + nonLocation)
            }
        }

        // 3. Merchant + first 2 model tokens (if >2)
        if (merchantCanon != null && modelTokens.size > 2) {
            addCandidate(listOf(merchantCanon) + modelTokens.take(2))
        }

        // 4. Merchant + first non-location model token (or first model token)
        if (merchantCanon != null && modelTokens.size >= 2) {
            val primary = modelTokens.firstOrNull { it.lowercase(Locale.ROOT) !in LOCATION_TOKENS_LOWER }
                ?: modelTokens.first()
            addCandidate(listOf(merchantCanon, primary))
        }

        // 5. Bare merchant as broadest search pool
        if (merchantCanon != null && modelTokens.isNotEmpty()) {
            addCandidate(listOf(merchantCanon))
        } else if (merchantCanon == null && tokens.size > 2) {
            addCandidate(tokens.take(2))
        }

        return fallbacks.toList()
    }

    fun tokenizeQuery(query: String): List<String> {
        val normalized = normalizeWidthAndCase(query)
        if (normalized.isBlank()) return emptyList()
        val cleaned = normalized
            .replace(SUGGEST_STRIP_STATUS_BRACKETS_REGEX, " ")
            .replace(SCRUB_URL_REGEX, " ")
            .replace(SCRUB_IPV4_CIDR_REGEX, " ")
            .replace(SCRUB_EMAIL_REGEX, " ")
            .replace(SCRUB_FULL_DATE_REGEX, " ")
            .replace(SCRUB_MONTH_DAY_REGEX, " ")
            .replace(SCRUB_MONTH_EXPIRY_REGEX, " ")
            .replace(SCRUB_DOT_DATE_CONTEXT_REGEX, " ")
            .replace(SCRUB_REMAINING_DAYS_REGEX, " ")
            .replace(SCRUB_FOREIGN_CURRENCY_PREFIX_REGEX, " ")
            .replace(SCRUB_FOREIGN_CURRENCY_SUFFIX_REGEX, " ")
            .replace(SCRUB_BILLING_SLASH_REGEX, " ")
        val productNormalized = normalizeProductCodes(cleaned).lowercase(Locale.ROOT)
        val segmented = segmentKnownCjkWords(productNormalized)
        return splitTokens(segmented)
            .filterNot { it in QUERY_NOISE_TOKENS }
            .distinct()
    }

    fun matchesQuery(title: String, query: String): Boolean {
        val normalizedRawTitle = normalizeWidthAndCase(title)
        val queryTokens = tokenizeQuery(query)
        if (normalizedRawTitle.isBlank() || queryTokens.isEmpty()) return false

        val normalizedTitle = normalizeProductCodes(normalizedRawTitle).lowercase(Locale.ROOT)
        val segmentedTitle = segmentKnownCjkWords(normalizedTitle)
        val titleTokens = splitTokens(segmentedTitle).toSet()

        val queryJoined = queryTokens.joinToString(" ")
        val queryMerchant = detectMerchantGroup(queryJoined)
        if (queryMerchant != null) {
            if (!matchesMerchantGroup(normalizedTitle, titleTokens, queryMerchant)) {
                return false
            }
        }

        var remainingTokens = if (queryMerchant != null) {
            queryTokens.filterNot { it in queryMerchant.aliases }
        } else {
            queryTokens
        }

        // For BandwagonHost unique "-PRO" SKUs (MEGABOX-PRO / BIGGERBOX-PRO), "pro" is implied
        // by the SKU name so "BWG MEGABOX PRO" still matches "BWG MegaBox" without matching BIGGERBOX/MINIBOX.
        if (queryMerchant?.canonical == "BWG" &&
            remainingTokens.any { it in BWG_PRO_IMPLIED_SKUS }
        ) {
            remainingTokens = remainingTokens.filterNot { it == "pro" }
        }

        if (remainingTokens.isEmpty()) {
            return queryMerchant != null
        }

        return remainingTokens.all { token ->
            matchesModelToken(normalizedTitle, titleTokens, token)
        }
    }

    fun extractSpread(title: String): ParsedTradeTitleSpread? {
        val rawTrimmed = title.trim()
        if (rawTrimmed.isEmpty()) return null

        val isClosedSold = CLOSED_SOLD_REGEX.containsMatchIn(rawTrimmed) &&
            !NON_TRADE_SOLD_FALSE_POSITIVE_REGEX.containsMatchIn(rawTrimmed)

        val intent = detectIntent(rawTrimmed)
        val scrubbed = scrubNonSpreadTokens(normalizeWidthAndCase(rawTrimmed))

        // 1. Explicit 溢价 +XX / 溢价 XX / 加价 XX
        EXPLICIT_PREMIUM_WORD_REGEX.find(scrubbed)?.let { m ->
            val rawNum = m.groupValues[1].toBigDecimalOrNull() ?: return@let
            val scaled = rawNum.setScale(2, RoundingMode.HALF_UP)
            if (scaled <= MAX_REASONABLE_SPREAD) {
                return ParsedTradeTitleSpread(
                    deltaCny = scaled,
                    spreadStatus = if (scaled.compareTo(BigDecimal.ZERO) == 0) DealSpreadStatus.PAR else DealSpreadStatus.PREMIUM,
                    intent = intent,
                    isClosedSold = isClosedSold,
                    matchedExpression = m.value.trim(),
                )
            }
        }

        // 2. Explicit 剩余价值+XX / 净残值+XX / 残值+XX
        EXPLICIT_REMAINING_PLUS_REGEX.find(scrubbed)?.let { m ->
            val rawNum = m.groupValues[1].toBigDecimalOrNull() ?: return@let
            val scaled = rawNum.setScale(2, RoundingMode.HALF_UP)
            if (scaled <= MAX_REASONABLE_SPREAD) {
                return ParsedTradeTitleSpread(
                    deltaCny = scaled,
                    spreadStatus = if (scaled.compareTo(BigDecimal.ZERO) == 0) DealSpreadStatus.PAR else DealSpreadStatus.PREMIUM,
                    intent = intent,
                    isClosedSold = isClosedSold,
                    matchedExpression = m.value.trim(),
                )
            }
        }

        // 3. Explicit 折价 -XX / 折价 XX / 亏XX出 / 血亏XX出
        EXPLICIT_DISCOUNT_WORD_REGEX.find(scrubbed)?.let { m ->
            val rawNum = m.groupValues[1].toBigDecimalOrNull() ?: return@let
            val scaled = rawNum.negate().setScale(2, RoundingMode.HALF_UP)
            if (rawNum <= MAX_REASONABLE_SPREAD) {
                return ParsedTradeTitleSpread(
                    deltaCny = scaled,
                    spreadStatus = if (rawNum.compareTo(BigDecimal.ZERO) == 0) DealSpreadStatus.PAR else DealSpreadStatus.DISCOUNT,
                    intent = intent,
                    isClosedSold = isClosedSold,
                    matchedExpression = m.value.trim(),
                )
            }
        }

        // 4. Explicit 剩余价值-XX / 净残值-XX / 残值-XX
        EXPLICIT_REMAINING_MINUS_REGEX.find(scrubbed)?.let { m ->
            val rawNum = m.groupValues[1].toBigDecimalOrNull() ?: return@let
            val scaled = rawNum.negate().setScale(2, RoundingMode.HALF_UP)
            if (rawNum <= MAX_REASONABLE_SPREAD) {
                return ParsedTradeTitleSpread(
                    deltaCny = scaled,
                    spreadStatus = if (rawNum.compareTo(BigDecimal.ZERO) == 0) DealSpreadStatus.PAR else DealSpreadStatus.DISCOUNT,
                    intent = intent,
                    isClosedSold = isClosedSold,
                    matchedExpression = m.value.trim(),
                )
            }
        }

        // 5. Standalone +XX出 / -XX出 / +XX包push
        STANDALONE_SIGNED_BEFORE_CHU_REGEX.find(scrubbed)?.let { m ->
            val sign = m.groupValues[1]
            val rawNum = m.groupValues[2].toBigDecimalOrNull() ?: return@let
            if (rawNum <= MAX_REASONABLE_SPREAD) {
                val signed = if (sign == "-" || sign == "－") rawNum.negate() else rawNum
                val scaled = signed.setScale(2, RoundingMode.HALF_UP)
                val status = when {
                    scaled > BigDecimal.ZERO -> DealSpreadStatus.PREMIUM
                    scaled < BigDecimal.ZERO -> DealSpreadStatus.DISCOUNT
                    else -> DealSpreadStatus.PAR
                }
                return ParsedTradeTitleSpread(
                    deltaCny = scaled,
                    spreadStatus = status,
                    intent = intent,
                    isClosedSold = isClosedSold,
                    matchedExpression = m.value.trim(),
                )
            }
        }

        // 6. Explicit 平出 / 原价出 / 剩余价值出 / 残值出
        EXPLICIT_PAR_REGEX.find(scrubbed)?.let { m ->
            return ParsedTradeTitleSpread(
                deltaCny = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                spreadStatus = DealSpreadStatus.PAR,
                intent = intent,
                isClosedSold = isClosedSold,
                matchedExpression = m.value.trim(),
            )
        }

        // 7. Explicit colon-delimited remaining value / original value amount (e.g. 剩余价值:100)
        EXPLICIT_REMAINING_COLON_REGEX.find(scrubbed)?.let { m ->
            val rawNum = m.groupValues[1].toBigDecimalOrNull() ?: return@let
            val scaled = rawNum.setScale(2, RoundingMode.HALF_UP)
            if (scaled <= MAX_REASONABLE_SPREAD) {
                return ParsedTradeTitleSpread(
                    deltaCny = scaled,
                    spreadStatus = if (scaled.compareTo(BigDecimal.ZERO) == 0) DealSpreadStatus.PAR else DealSpreadStatus.PREMIUM,
                    intent = intent,
                    isClosedSold = isClosedSold,
                    matchedExpression = m.value.trim(),
                )
            }
        }

        return null
    }

    fun analyze(
        query: String,
        candidates: List<TradeCandidateTopic>,
        includeBuyRequests: Boolean = false,
    ): TradePremiumDistribution {
        val trimmedQuery = query.trim()
        val tokens = tokenizeQuery(trimmedQuery)
        if (tokens.isEmpty() || candidates.isEmpty()) {
            return TradePremiumDistribution(
                query = trimmedQuery,
                normalizedTokens = tokens,
                sampleCount = 0,
                matchedTopicCount = 0,
                premiumCount = 0,
                parCount = 0,
                discountCount = 0,
                minCny = null,
                p25Cny = null,
                medianCny = null,
                p75Cny = null,
                maxCny = null,
                observations = emptyList(),
                localSampleCount = 0,
                remoteSampleCount = 0,
            )
        }

        // Deduplicate by topicId preferring highest priority source.
        val deduped = LinkedHashMap<Long, TradeCandidateTopic>()
        for (candidate in candidates) {
            if (candidate.topicId <= 0L || candidate.title.isBlank()) continue
            val existing = deduped[candidate.topicId]
            if (existing == null ||
                (SOURCE_PRIORITY[candidate.source] ?: 0) > (SOURCE_PRIORITY[existing.source] ?: 0)
            ) {
                deduped[candidate.topicId] = candidate
            }
        }

        val matchedCandidates = deduped.values.filter { matchesQuery(it.title, trimmedQuery) }
        val observations = ArrayList<TradePremiumObservation>()
        for (candidate in matchedCandidates) {
            val parsed = extractSpread(candidate.title) ?: continue
            if (!includeBuyRequests && parsed.intent == TradeListingIntent.BUY) continue
            observations += TradePremiumObservation(
                topicId = candidate.topicId,
                title = candidate.title.trim(),
                premiumDeltaCny = parsed.deltaCny,
                spreadStatus = parsed.spreadStatus,
                intent = parsed.intent,
                isClosedSold = parsed.isClosedSold,
                source = candidate.source,
                matchedExpression = parsed.matchedExpression,
                authorName = candidate.authorName,
                activityHint = candidate.activityHint,
            )
        }

        val sortedValues = observations.map { it.premiumDeltaCny }.sorted()
        val sampleCount = sortedValues.size
        val localCount = observations.count { it.source != MarketDataSource.REMOTE_SEARCH }
        val remoteCount = observations.count { it.source == MarketDataSource.REMOTE_SEARCH }

        return TradePremiumDistribution(
            query = trimmedQuery,
            normalizedTokens = tokens,
            sampleCount = sampleCount,
            matchedTopicCount = matchedCandidates.size,
            premiumCount = observations.count { it.spreadStatus == DealSpreadStatus.PREMIUM },
            parCount = observations.count { it.spreadStatus == DealSpreadStatus.PAR },
            discountCount = observations.count { it.spreadStatus == DealSpreadStatus.DISCOUNT },
            minCny = sortedValues.firstOrNull(),
            p25Cny = linearQuantile(sortedValues, 0.25),
            medianCny = linearQuantile(sortedValues, 0.50),
            p75Cny = linearQuantile(sortedValues, 0.75),
            maxCny = sortedValues.lastOrNull(),
            observations = observations.sortedByDescending { it.topicId },
            localSampleCount = localCount,
            remoteSampleCount = remoteCount,
        )
    }

    internal fun linearQuantile(sortedValues: List<BigDecimal>, p: Double): BigDecimal? {
        val n = sortedValues.size
        if (n == 0) return null
        if (n == 1) return sortedValues[0].setScale(2, RoundingMode.HALF_UP)

        val pos = p * (n - 1)
        val lo = kotlin.math.floor(pos).toInt().coerceIn(0, n - 1)
        val hi = kotlin.math.ceil(pos).toInt().coerceIn(0, n - 1)
        if (lo == hi) return sortedValues[lo].setScale(2, RoundingMode.HALF_UP)
        val frac = BigDecimal.valueOf(pos - lo)
        val diff = sortedValues[hi].subtract(sortedValues[lo])
        return sortedValues[lo].add(diff.multiply(frac)).setScale(2, RoundingMode.HALF_UP)
    }

    private fun extractQueryFromFallbackBody(
        fallbackText: String,
        preferredMerchant: MerchantGroup?,
    ): ExtractedQueryCandidate {
        val sanitizedBody = fallbackText
            .replace(FENCED_CODE_BLOCK_REGEX, "\n")
            .replace(MARKDOWN_IMAGE_REGEX, " ")
            .replace(MARKDOWN_LINK_REGEX, "$1")
            .take(1_200)

        val lines = sanitizedBody.lineSequence()
            .map { line ->
                line.replace(Regex("""[*_`#>|]"""), " ").trim()
            }
            .filter { it.isNotEmpty() }
            .take(15)
            .toList()

        if (lines.isEmpty()) {
            return ExtractedQueryCandidate("", null, 0, false)
        }

        // Pass 1: Explicitly labeled model/plan lines (e.g. "型号：DMIT LAX.Pro.WEE" or "套餐：LAX.Pro.WEE")
        for (line in lines) {
            val labeled = FALLBACK_LABELED_LINE_REGEX.find(line) ?: continue
            val value = labeled.groupValues[1].trim()
            if (value.isEmpty()) continue
            val direct = extractQueryFromText(value, requireMerchant = preferredMerchant == null)
            if (direct.merchant != null && direct.modelTokenCount > 0) {
                return direct
            }
            if (preferredMerchant != null && direct.merchant == null) {
                val withPreferred = extractQueryFromText(
                    rawText = "${preferredMerchant.canonical} $value",
                    requireMerchant = true,
                )
                if (withPreferred.modelTokenCount > 0) {
                    return withPreferred
                }
            }
        }

        // Pass 2: First lines mentioning a known merchant, preferring same merchant and richer model tokens
        var best: ExtractedQueryCandidate? = null
        for (line in lines) {
            val candidate = extractQueryFromText(line, requireMerchant = true)
            if (candidate.query.isBlank() || candidate.merchant == null) continue
            val samePreferred = preferredMerchant != null &&
                candidate.merchant.canonical == preferredMerchant.canonical
            if (samePreferred && candidate.modelTokenCount > 0 && !candidate.usedSpecFallback) {
                return candidate
            }
            val currentBest = best
            if (currentBest == null || isBetterFallbackCandidate(candidate, currentBest, preferredMerchant)) {
                best = candidate
            }
        }

        return best ?: ExtractedQueryCandidate("", null, 0, false)
    }

    private fun isBetterFallbackCandidate(
        candidate: ExtractedQueryCandidate,
        currentBest: ExtractedQueryCandidate,
        preferredMerchant: MerchantGroup?,
    ): Boolean {
        val candPref = preferredMerchant != null && candidate.merchant?.canonical == preferredMerchant.canonical
        val bestPref = preferredMerchant != null && currentBest.merchant?.canonical == preferredMerchant.canonical
        if (candPref != bestPref) return candPref
        if (candidate.usedSpecFallback != currentBest.usedSpecFallback) return !candidate.usedSpecFallback
        return candidate.modelTokenCount > currentBest.modelTokenCount
    }

    private fun extractQueryFromText(
        rawText: String,
        requireMerchant: Boolean,
    ): ExtractedQueryCandidate {
        val widthNorm = normalizeWidthPreservingCase(rawText)
        if (widthNorm.isBlank()) {
            return ExtractedQueryCandidate("", null, 0, false)
        }

        // Capture normalized CPU+RAM spec tiers before scrubbing (fallback when no specific model/region exists).
        val capturedSpecs = CPU_RAM_SPEC_CAPTURE_REGEX.findAll(widthNorm)
            .map { m ->
                val cpu = m.groupValues[1]
                val mem = m.groupValues[2]
                val unit = m.groupValues[3].uppercase(Locale.ROOT).let { u ->
                    when (u) {
                        "GB" -> "G"
                        "MB" -> "M"
                        else -> u
                    }
                }
                "${cpu}C${mem}${unit}"
            }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()

        val scrubbed = widthNorm
            .replace(SUGGEST_STRIP_STATUS_BRACKETS_REGEX, " ")
            .replace(SCRUB_URL_REGEX, " ")
            .replace(SCRUB_EMAIL_REGEX, " ")
            .replace(SCRUB_IPV4_CIDR_REGEX, " ")
            .replace(SCRUB_CPU_RAM_REGEX, " ")
            .replace(SCRUB_BANDWIDTH_REGEX, " ")
            .replace(SCRUB_STORAGE_MEM_WITH_UNIT_REGEX, " ")
            .replace(SCRUB_FULL_DATE_REGEX, " ")
            .replace(SCRUB_MONTH_DAY_REGEX, " ")
            .replace(SCRUB_MONTH_EXPIRY_REGEX, " ")
            .replace(SCRUB_DOT_DATE_CONTEXT_REGEX, " ")
            .replace(SCRUB_REMAINING_DAYS_REGEX, " ")
            .replace(SCRUB_YEAR_TOKEN_FOR_QUERY_REGEX, " ")
            .replace(SCRUB_PORT_REGEX, " ")
            .replace(SCRUB_PERCENT_REGEX, " ")
            .replace(SCRUB_ZH_DISCOUNT_RATE_REGEX, " ")
            .replace(SCRUB_FOREIGN_CURRENCY_PREFIX_REGEX, " ")
            .replace(SCRUB_FOREIGN_CURRENCY_SUFFIX_REGEX, " ")
            .replace(SCRUB_TOTAL_ASK_AFTER_WORD_REGEX) { match ->
                val amount = match.groupValues[1].toBigDecimalOrNull()
                if (amount != null && amount > MAX_DIRECT_AFTER_CHU_SPREAD) " " else match.value
            }
            .replace(EXPLICIT_PREMIUM_WORD_REGEX, " ")
            .replace(EXPLICIT_DISCOUNT_WORD_REGEX, " ")
            .replace(EXPLICIT_REMAINING_PLUS_REGEX, " ")
            .replace(EXPLICIT_REMAINING_COLON_REGEX, " ")
            .replace(EXPLICIT_REMAINING_MINUS_REGEX, " ")
            .replace(STANDALONE_SIGNED_BEFORE_CHU_REGEX, " ")
            .replace(EXPLICIT_PAR_REGEX, " ")
            .replace(SCRUB_CNY_PRICE_FOR_QUERY_REGEX, " ")
            .replace(SCRUB_BARE_BUYOUT_FOR_QUERY_REGEX) { match ->
                val num = match.groupValues[1]
                if (num in KNOWN_NUMERIC_MODEL_TOKENS) num else " "
            }
            .replace(SCRUB_BILLING_SLASH_REGEX, " ")

        val productNormalized = normalizeProductCodes(scrubbed)
        val segmented = segmentKnownCjkWords(productNormalized)
        val rawTokens = splitTokens(segmented)
        val lowerForDetect = segmented.lowercase(Locale.ROOT)
        val detectedMerchant = detectMerchantGroup(lowerForDetect)

        if (requireMerchant && detectedMerchant == null) {
            return ExtractedQueryCandidate("", null, 0, false)
        }

        val filtered = ArrayList<String>(rawTokens.size)
        for (i in rawTokens.indices) {
            val tok = rawTokens[i]
            val low = tok.lowercase(Locale.ROOT)
            if (low in QUERY_NOISE_TOKENS) continue
            if (detectedMerchant != null && low in detectedMerchant.aliases) continue
            val isPureNumberLike = tok.all { ch -> ch.isDigit() || ch == '.' || ch == '+' || ch == '-' }
            if (isPureNumberLike && low !in KNOWN_NUMERIC_MODEL_TOKENS) continue
            val isSingleAscii = tok.length <= 1 && tok.all { it.code < 128 }
            if (isSingleAscii) {
                val prevLower = rawTokens.getOrNull(i - 1)?.lowercase(Locale.ROOT)
                val keepSingleSuffix = tok.length == 1 &&
                    prevLower in SINGLE_CHAR_SUFFIX_PREDECESSORS &&
                    tok[0].isLetterOrDigit()
                if (!keepSingleSuffix) continue
            }
            filtered += tok
        }

        val deduped = deduplicateModelTokens(filtered)
        val refined = dropSupersededBroadRegions(deduped)

        // When a known merchant is detected, prefer recognized domain tokens and ASCII product tokens
        // over free-form CJK trade chatter that wasn't in our noise lexicon.
        val prioritizedModels = if (detectedMerchant != null) {
            val strongTokens = refined.filter { tok ->
                val low = tok.lowercase(Locale.ROOT)
                low in KNOWN_DOMAIN_TOKENS_LOWER || tok.all { it.code < 128 }
            }
            if (strongTokens.isNotEmpty()) strongTokens else refined
        } else {
            refined
        }

        val picked = ArrayList<String>(5)
        var usedSpecFallback = false
        var modelCount = 0

        if (detectedMerchant != null) {
            picked += detectedMerchant.canonical
        }

        // Special handling for BandwagonHost self-sufficient named SKUs (e.g. MEGABOX-PRO, BIGGERBOX-PRO):
        // avoid tacking on redundant DC6 / CN2 / GIA suffixes that over-constrain trade search.
        val bwgSkuIndex = if (detectedMerchant?.canonical == "BWG") {
            prioritizedModels.indexOfFirst { it.lowercase(Locale.ROOT) in BWG_SELF_SUFFICIENT_SKUS }
        } else {
            -1
        }

        if (bwgSkuIndex >= 0) {
            picked += prioritizedModels[bwgSkuIndex]
            modelCount++
            val nextTok = prioritizedModels.getOrNull(bwgSkuIndex + 1)
            if (nextTok != null && nextTok.equals("pro", ignoreCase = true)) {
                picked += nextTok
                modelCount++
            }
        } else {
            val allRecognized = prioritizedModels.isNotEmpty() && prioritizedModels.all { tok ->
                val low = tok.lowercase(Locale.ROOT)
                low in KNOWN_DOMAIN_TOKENS_LOWER || isAlphanumericModelCode(tok)
            }
            val maxModelTokens = when {
                detectedMerchant == null -> 3
                allRecognized -> 4
                else -> 3
            }
            for (tok in prioritizedModels) {
                if (picked.none { it.equals(tok, ignoreCase = true) }) {
                    picked += tok
                    modelCount++
                }
                if (modelCount >= maxModelTokens) break
            }
        }

        // Fallback to vendor + hardware spec tier ONLY when a merchant is known and no specific model/region exists.
        if (detectedMerchant != null && modelCount == 0 && capturedSpecs.isNotEmpty()) {
            picked += capturedSpecs.first()
            usedSpecFallback = true
        }

        return ExtractedQueryCandidate(
            query = picked.joinToString(" ").trim(),
            merchant = detectedMerchant,
            modelTokenCount = modelCount,
            usedSpecFallback = usedSpecFallback,
        )
    }

    private fun deduplicateModelTokens(tokens: List<String>): List<String> {
        if (tokens.size <= 1) return tokens
        val result = ArrayList<String>(tokens.size)
        for (tok in tokens) {
            val low = tok.lowercase(Locale.ROOT)
            val synGroup = MODEL_SYNONYM_GROUPS.firstOrNull { low in it }
            val existingIdx = result.indexOfFirst { existing ->
                val exLow = existing.lowercase(Locale.ROOT)
                exLow == low || (synGroup != null && exLow in synGroup)
            }
            if (existingIdx < 0) {
                result += tok
            } else {
                // If both a CJK alias and an ASCII code for the exact same entity appear in the same title
                // (e.g. "美西 LAX.Pro.WEE"), prefer the concise ASCII token.
                val existingIsAscii = result[existingIdx].all { it.code < 128 }
                val currentIsAscii = tok.all { it.code < 128 }
                if (!existingIsAscii && currentIsAscii) {
                    result[existingIdx] = tok
                }
            }
        }
        return result
    }

    private fun dropSupersededBroadRegions(tokens: List<String>): List<String> {
        if (tokens.size <= 1) return tokens
        val lowerSet = tokens.map { it.lowercase(Locale.ROOT) }.toSet()
        return tokens.filterNot { tok ->
            val low = tok.lowercase(Locale.ROOT)
            BROAD_REGION_SUPERSEDED_BY.any { (broadSet, specifics) ->
                low in broadSet && lowerSet.any { it in specifics }
            }
        }
    }

    private fun isAlphanumericModelCode(token: String): Boolean {
        val hasLetter = token.any { it in 'a'..'z' || it in 'A'..'Z' }
        val hasDigit = token.any { it in '0'..'9' }
        return hasLetter && hasDigit && token.all { isAsciiIdentifierChar(it) || it == '.' }
    }

    private fun normalizeProductCodes(text: String): String {
        return text
            .replace(NORM_PVM_PREFIX_REGEX, "")
            .replace(NORM_DOUBLE_ELEVEN_REGEX, "双十一")
            .replace(NORM_DC_CODE_REGEX) { m -> "DC${m.groupValues[1]}" }
            .replace(NORM_HYPHEN_MODEL_NUM_REGEX) { m -> "${m.groupValues[1]}${m.groupValues[2]}" }
            .replace(NORM_NETCUP_VPS_NUM_REGEX) { m -> "${m.groupValues[1]}${m.groupValues[2]}" }
            .replace(NORM_GIA_E_REGEX, "GIA")
            .replace(NORM_MEGABOX_PRO_REGEX, "MEGABOX PRO")
            .replace(NORM_BIGGERBOX_PRO_REGEX, "BIGGERBOX PRO")
            .replace(NORM_THE_PLAN_REGEX, "PLAN")
    }

    private fun segmentKnownCjkWords(text: String): String {
        if (text.isEmpty() || text.all { it.code < 128 }) return text
        // Pass 1: isolate known CJK merchant/region/route/series entities first so noise regexes never split them.
        val entitiesIsolated = CJK_ENTITY_SEGMENT_REGEX.replace(text) { m -> " ${m.value} " }
        // Pass 2: segment multi-char CJK trade noise phrases inside non-entity chunks.
        val sb = StringBuilder(entitiesIsolated.length + 16)
        var i = 0
        while (i < entitiesIsolated.length) {
            if (entitiesIsolated[i].isWhitespace()) {
                sb.append(' ')
                i++
                continue
            }
            var end = i
            while (end < entitiesIsolated.length && !entitiesIsolated[end].isWhitespace()) {
                end++
            }
            val chunk = entitiesIsolated.substring(i, end)
            val lowChunk = chunk.lowercase(Locale.ROOT)
            if (lowChunk in KNOWN_CJK_DOMAIN_ENTITIES || chunk.all { it.code < 128 }) {
                sb.append(chunk)
            } else {
                sb.append(CJK_NOISE_SEGMENT_REGEX.replace(chunk) { m -> " ${m.value} " })
            }
            i = end
        }
        return sb.toString()
    }

    private fun detectIntent(rawTitle: String): TradeListingIntent {
        val isBuy = BUY_INTENT_REGEX.containsMatchIn(rawTitle)
        val isSell = SELL_INTENT_REGEX.containsMatchIn(rawTitle)
        return when {
            isBuy && !isSell -> TradeListingIntent.BUY
            isSell -> TradeListingIntent.SELL
            else -> TradeListingIntent.UNKNOWN
        }
    }

    private fun scrubNonSpreadTokens(normalizedText: String): String {
        return normalizedText
            .replace(SCRUB_URL_REGEX, " ")
            .replace(SCRUB_EMAIL_REGEX, " ")
            .replace(SCRUB_IPV4_CIDR_REGEX, " ")
            .replace(SCRUB_CPU_RAM_REGEX, " ")
            .replace(SCRUB_BANDWIDTH_REGEX, " ")
            .replace(SCRUB_STORAGE_MEM_REGEX, " ")
            .replace(SCRUB_FULL_DATE_REGEX, " ")
            .replace(SCRUB_MONTH_DAY_REGEX, " ")
            .replace(SCRUB_MONTH_EXPIRY_REGEX, " ")
            .replace(SCRUB_DOT_DATE_CONTEXT_REGEX, " ")
            .replace(SCRUB_REMAINING_DAYS_REGEX, " ")
            .replace(SCRUB_YEAR_TOKEN_FOR_SPREAD_REGEX, " ")
            .replace(SCRUB_PORT_REGEX, " ")
            .replace(SCRUB_ASN_REGEX, " ")
            .replace(SCRUB_LINE_MODEL_CODE_REGEX, " ")
            .replace(SCRUB_PERCENT_REGEX, " ")
            .replace(SCRUB_ZH_DISCOUNT_RATE_REGEX, " ")
            .replace(SCRUB_FOREIGN_CURRENCY_PREFIX_REGEX, " ")
            .replace(SCRUB_FOREIGN_CURRENCY_SUFFIX_REGEX, " ")
            .replace(SCRUB_TOTAL_ASK_AFTER_WORD_REGEX) { match ->
                val amount = match.groupValues[1].toBigDecimalOrNull()
                if (amount != null && amount > MAX_DIRECT_AFTER_CHU_SPREAD) " " else match.value
            }
    }

    private fun splitTokens(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<String>()
        var start = -1
        var prevIsAscii = false
        for (i in text.indices) {
            val ch = text[i]
            val keepProtectedDot = ch == '.' &&
                start >= 0 &&
                prevIsAscii &&
                isProtectedDotInToken(text, start, i)
            val isTokenChar = ch.isLetterOrDigit() || keepProtectedDot
            if (!isTokenChar) {
                if (start >= 0) {
                    tokens += text.substring(start, i)
                    start = -1
                }
            } else {
                val isAscii = isAsciiIdentifierChar(ch) || keepProtectedDot
                if (start < 0) {
                    start = i
                    prevIsAscii = isAscii
                } else if (isAscii != prevIsAscii) {
                    tokens += text.substring(start, i)
                    start = i
                    prevIsAscii = isAscii
                }
            }
        }
        if (start >= 0) {
            tokens += text.substring(start)
        }
        return tokens
    }

    /**
     * Keeps dots inside brand `V.PS` and letter-bearing version/spec tokens (`G9.5`, `v2.5`, `2C2.5G`),
     * while letting dotted product paths (`LAX.Pro.WEE`, `HKG.EB.CORONA`) split into clean space-separated tokens.
     */
    private fun isProtectedDotInToken(text: String, tokenStart: Int, dotIndex: Int): Boolean {
        if (dotIndex <= tokenStart || dotIndex + 1 >= text.length) return false

        // 1. Brand token `V.PS` (exact 1-char "v" + "." + "ps" with non-ASCII boundary after "ps")
        if (dotIndex - tokenStart == 1 &&
            (text[tokenStart] == 'v' || text[tokenStart] == 'V') &&
            dotIndex + 2 < text.length &&
            (text[dotIndex + 1] == 'p' || text[dotIndex + 1] == 'P') &&
            (text[dotIndex + 2] == 's' || text[dotIndex + 2] == 'S') &&
            (dotIndex + 3 >= text.length || !isAsciiIdentifierChar(text[dotIndex + 3]))
        ) {
            return true
        }

        // 2. Decimal dot inside an alphanumeric model/spec code like `G9.5`, `v2.5`, `2C2.5G`
        val prevCh = text[dotIndex - 1]
        val nextCh = text[dotIndex + 1]
        if (prevCh in '0'..'9' && nextCh in '0'..'9') {
            var hasAsciiLetter = false
            for (k in tokenStart until dotIndex) {
                val c = text[k]
                if (c in 'a'..'z' || c in 'A'..'Z') {
                    hasAsciiLetter = true
                    break
                }
            }
            if (hasAsciiLetter) return true
        }

        return false
    }

    private fun detectMerchantGroup(normalizedText: String): MerchantGroup? {
        val tokens = splitTokens(normalizedText).toSet()
        MERCHANT_GROUPS.firstOrNull { group ->
            group.aliases.any { alias ->
                matchesMerchantAlias(normalizedText, tokens, alias)
            }
        }?.let { return it }

        return MERCHANT_GROUPS.firstOrNull { group ->
            group.impliedAliases.any { alias ->
                matchesSingleAliasOrToken(normalizedText, tokens, alias)
            }
        }
    }

    private fun matchesMerchantGroup(
        normalizedText: String,
        splitTokens: Set<String>,
        group: MerchantGroup,
    ): Boolean {
        if (group.aliases.any { alias -> matchesMerchantAlias(normalizedText, splitTokens, alias) }) {
            return true
        }
        if (group.impliedAliases.isEmpty()) return false
        val hasDifferentExplicitMerchant = MERCHANT_GROUPS.any { other ->
            other.canonical != group.canonical &&
                other.aliases.any { alias -> matchesMerchantAlias(normalizedText, splitTokens, alias) }
        }
        if (hasDifferentExplicitMerchant) return false
        return group.impliedAliases.any { alias ->
            matchesSingleAliasOrToken(normalizedText, splitTokens, alias)
        }
    }

    private fun matchesMerchantAlias(
        normalizedText: String,
        splitTokens: Set<String>,
        alias: String,
    ): Boolean {
        val rejectPrecedingDot = alias.length <= 2 && alias.all { it in 'a'..'z' }
        if (rejectPrecedingDot) {
            return containsWithAsciiBoundary(normalizedText, alias, rejectPrecedingDot = true)
        }
        return matchesSingleAliasOrToken(normalizedText, splitTokens, alias)
    }

    private fun matchesModelToken(
        normalizedTitle: String,
        titleTokens: Set<String>,
        token: String,
    ): Boolean {
        val synonymSet = MODEL_SYNONYM_GROUPS.firstOrNull { token in it }
        if (synonymSet != null) {
            return synonymSet.any { variant -> matchesSingleAliasOrToken(normalizedTitle, titleTokens, variant) }
        }
        return matchesSingleAliasOrToken(normalizedTitle, titleTokens, token)
    }

    private fun matchesSingleAliasOrToken(
        normalizedText: String,
        splitTokens: Set<String>,
        alias: String,
    ): Boolean {
        if (alias.isEmpty()) return false
        if (alias in splitTokens) return true

        val isAsciiOnly = alias.all { it.code < 128 }
        if (!isAsciiOnly) {
            return normalizedText.contains(alias)
        }

        // For ASCII tokens (especially short aliases <= 3 chars like rn, cc, vir, pro),
        // enforce ASCII identifier boundaries so `rn` doesn't match `return`, `pro` doesn't match `promo`,
        // and `g9` doesn't match `g9.5`.
        return containsWithAsciiBoundary(normalizedText, alias, rejectPrecedingDot = false)
    }

    private fun containsWithAsciiBoundary(
        text: String,
        needle: String,
        rejectPrecedingDot: Boolean = false,
    ): Boolean {
        if (needle.isEmpty()) return false
        var startIndex = 0
        val needleStartsWithDigit = needle.first() in '0'..'9'
        val needleEndsWithDigit = needle.last() in '0'..'9'
        while (startIndex <= text.length - needle.length) {
            val found = text.indexOf(needle, startIndex = startIndex)
            if (found < 0) return false
            val leftChar = if (found > 0) text[found - 1] else null
            var leftOk = leftChar == null || !isAsciiIdentifierChar(leftChar)
            if (leftOk && rejectPrecedingDot && leftChar == '.') {
                leftOk = false
            }
            if (leftOk && needleStartsWithDigit && leftChar == '.' && found >= 2 && isAsciiIdentifierChar(text[found - 2])) {
                leftOk = false
            }
            val rightIdx = found + needle.length
            val rightChar = if (rightIdx < text.length) text[rightIdx] else null
            var rightOk = rightChar == null || !isAsciiIdentifierChar(rightChar)
            if (rightOk && needleEndsWithDigit && rightChar == '.' && rightIdx + 1 < text.length && text[rightIdx + 1] in '0'..'9') {
                rightOk = false
            }
            if (leftOk && rightOk) return true
            startIndex = found + 1
        }
        return false
    }

    private fun isAsciiIdentifierChar(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
}
