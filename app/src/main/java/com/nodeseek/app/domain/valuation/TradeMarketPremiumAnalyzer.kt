package com.nodeseek.app.domain.valuation

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
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

    private data class MerchantOccurrence(
        val group: MerchantGroup,
        val start: Int,
        val end: Int,
        val isImplied: Boolean,
    )

    private enum class TokenRole(val sortOrder: Int) {
        PRIMARY_SKU(1),
        EDITION(2),
        LOCATION(3),
        LINE(4),
        PLAN(5),
        GENERATION(6),
    }

    private data class TaggedModelToken(
        val text: String,
        val role: TokenRole,
        val originalIndex: Int,
    )

    private data class ExtractedQueryCandidate(
        val query: String,
        val merchant: MerchantGroup?,
        val modelTokens: List<String>,
        val usedSpecFallback: Boolean,
        val isMultiVendorBundle: Boolean = false,
        val isSelfSufficientSku: Boolean = false,
    ) {
        val modelTokenCount: Int get() = modelTokens.size
    }

    private val MERCHANT_GROUPS: List<MerchantGroup> = listOf(
        MerchantGroup("DMIT", setOf("dmit", "大妈", "d家")),
        MerchantGroup(
            "BWG",
            setOf("bandwagonhost", "bandwagon host", "bandwagon", "banwagong", "bwg", "bwh", "搬瓦工", "瓦工"),
            impliedAliases = setOf("megabox", "megaboxpro", "biggerbox", "biggerboxpro", "minibox", "powerbox", "sakurabox"),
        ),
        MerchantGroup("RACKNERD", setOf("racknerd", "rn")),
        MerchantGroup("GREENCLOUD", setOf("greencloudvps", "greencloud vps", "greencloud", "绿云", "绿帽云")),
        MerchantGroup("SPARTAN", setOf("spartanhost", "spartan host", "spartan", "斯巴达")),
        MerchantGroup("CLAW", setOf("clawcloud", "claw cloud", "claw", "爪云", "阿里爪", "爪子")),
        MerchantGroup("CLOUDCONE", setOf("cloudcone", "cloud cone", "cc")),
        MerchantGroup("VIRMACH", setOf("virmach", "vir")),
        MerchantGroup("ORACLE", setOf("oracle cloud", "oracle", "oci", "甲骨文", "龟壳")),
        MerchantGroup("NETCUP", setOf("netcup", "nc")),
        MerchantGroup("HETZNER", setOf("hetzner", "hz")),
        MerchantGroup("ALICE", setOf("alice", "爱丽丝")),
        MerchantGroup("YXVM", setOf("yxvm")),
        MerchantGroup("V.PS", setOf("v.ps", "xtom")),
        MerchantGroup("AKILE", setOf("akilecloud", "akile cloud", "akile")),
        MerchantGroup("WAP", setOf("wapac", "wap")),
        MerchantGroup("ZGO", setOf("zgocloud", "zgo cloud", "zgo")),
        MerchantGroup("RFC", setOf("rfchost", "rfc host", "rfc")),
        MerchantGroup("MISAKA", setOf("misaka", "御坂")),
        MerchantGroup("SALTYFISH", setOf("saltyfishtech", "saltyfish tech", "saltyfish", "咸鱼云", "咸鱼")),
        MerchantGroup("KIRINO", setOf("kirinocloud", "kirino cloud", "kirino")),
        MerchantGroup("SHARON", setOf("sharonnetwork", "sharon network", "sharoncloud", "sharon cloud", "sharon")),
        MerchantGroup("HALO", setOf("halocloud", "halo cloud", "halo")),
        MerchantGroup("AKKO", setOf("akkocloud", "akko cloud", "akko")),
        MerchantGroup("CERA", setOf("ceranetworks", "cera networks", "cera")),
        MerchantGroup("VOLC", setOf("volcengine", "volc", "火山引擎", "火山云")),
        MerchantGroup("HOSTHATCH", setOf("hosthatch", "hh")),
        MerchantGroup("DEDIPATH", setOf("dedipath")),
        MerchantGroup("BUYVM", setOf("buyvm", "frantech"), impliedAliases = setOf("卢森堡")),
        MerchantGroup("OVH", setOf("ovhcloud", "ovh cloud", "ovh", "kimsufi", "soyoustart")),
        MerchantGroup("CONTABO", setOf("contabo")),
        MerchantGroup("KURUN", setOf("kurun", "库伦")),
        MerchantGroup("VMISS", setOf("vmiss")),
        MerchantGroup("BAGE", setOf("bagevm", "bage")),
        MerchantGroup("HOSTDARE", setOf("hostdare")),
        MerchantGroup("HYTRON", setOf("hytron")),
        MerchantGroup("ISIF", setOf("isifcloud", "isif cloud", "isif")),
        MerchantGroup("GIGSGC", setOf("gigsgigscloud", "ggc")),
        MerchantGroup("COLOCROSSING", setOf("colocrossing")),
        MerchantGroup("BYTEVIRT", setOf("bytevirt")),
        MerchantGroup("VULTR", setOf("vultr")),
        MerchantGroup("LINODE", setOf("linode")),
        MerchantGroup("DIGITALOCEAN", setOf("digitalocean", "digital ocean", "do")),
        MerchantGroup("ALIYUN", setOf("alibabacloud", "alibaba cloud", "alicloud", "alibaba", "aliyun", "阿里云", "阿里", "套路云")),
        MerchantGroup("TENCENT", setOf("tencentcloud", "tencent cloud", "tencent", "qcloud", "腾讯云", "腾讯", "良心云")),
        MerchantGroup("HUAWEI", setOf("huaweicloud", "huawei cloud", "华为云")),
        MerchantGroup("AWS", setOf("aws", "amazon", "亚马逊云", "亚马逊"), impliedAliases = setOf("lightsail", "ec2")),
        MerchantGroup("GCP", setOf("gcp", "googlecloud", "google cloud", "谷歌云")),
        MerchantGroup("AZURE", setOf("azure", "微软云"), impliedAliases = setOf("az100")),
        MerchantGroup("SCALEWAY", setOf("scaleway", "scw"), impliedAliases = setOf("星尘", "stardust")),
        MerchantGroup("KUXUEYUN", setOf("kuxueyun", "kxy", "酷雪云", "酷雪")),
        MerchantGroup("YECAOYUN", setOf("yecaoyun", "野草云", "野草")),
        MerchantGroup("SERVARICA", setOf("servarica")),
        MerchantGroup("CLOUDSILK", setOf("cloudsilk", "白丝")),
        MerchantGroup("DOGYUN", setOf("dogyun", "狗云")),
        MerchantGroup("COALCLOUD", setOf("coalcloud", "碳云")),
        MerchantGroup("RAINYUN", setOf("rainyun", "雨云")),
        MerchantGroup("WUYUYUN", setOf("wuyuyun", "物语云")),
        MerchantGroup("LAICAYUN", setOf("laicayun", "莱卡云")),
        MerchantGroup("YUNYOO", setOf("yunyoo", "云悠")),
        MerchantGroup("MOECLOUD", setOf("moecloud", "萌云")),
        MerchantGroup("LALA", setOf("lalacloud", "lala云", "拉拉云")),
        MerchantGroup("BESTVM", setOf("bestvm")),
        MerchantGroup("BOIL", setOf("boil")),
        MerchantGroup("ZOUTER", setOf("zouter")),
        MerchantGroup("PQS", setOf("pqs")),
        MerchantGroup("ZJI", setOf("zji")),
        MerchantGroup("LIGHTLAYER", setOf("lightlayer")),
        MerchantGroup("MEGALAYER", setOf("megalayer")),
        MerchantGroup("LAYERSTACK", setOf("layerstack")),
        MerchantGroup("VIRCS", setOf("vircs")),
        MerchantGroup("ALPHAVPS", setOf("alphavps")),
        MerchantGroup("HOSTEONS", setOf("hosteons")),
        MerchantGroup("JUSTHOST", setOf("justhost")),
        MerchantGroup("IONOS", setOf("ionos")),
        MerchantGroup("UPCLOUD", setOf("upcloud")),
        MerchantGroup("CRUNCHBITS", setOf("crunchbits")),
        MerchantGroup("SMARTHOST", setOf("smarthost")),
        MerchantGroup("WAWO", setOf("wawo")),
        MerchantGroup("NUBE", setOf("nubecloud", "nube")),
        MerchantGroup("DEDIROCK", setOf("dedirock")),
        MerchantGroup("EXTRAVM", setOf("extravm")),
        MerchantGroup("RARECLOUD", setOf("rarecloud")),
        MerchantGroup("KUROIT", setOf("kuroit")),
        MerchantGroup("FIBERSTATE", setOf("fiberstate")),
        MerchantGroup("HOSTUS", setOf("hostus")),
        MerchantGroup("LETBOX", setOf("letbox")),
        MerchantGroup("NEXUSBYTES", setOf("nexusbytes")),
        MerchantGroup("WEBHORIZON", setOf("webhorizon")),
        MerchantGroup("HOSTDZIRE", setOf("hostdzire")),
        MerchantGroup("HOSTCRAM", setOf("hostcram")),
        MerchantGroup("HOSTSAILOR", setOf("hostsailor")),
        MerchantGroup("IKOULA", setOf("ikoula")),
        MerchantGroup("ONEPROVIDER", setOf("oneprovider")),
        MerchantGroup("LEASEWEB", setOf("leaseweb")),
        MerchantGroup("INTERSERVER", setOf("interserver")),
        MerchantGroup("CHERRYSERVERS", setOf("cherryservers")),
        MerchantGroup("KAMATERA", setOf("kamatera")),
        MerchantGroup("JDCLOUD", setOf("jdcloud", "京东云")),
        MerchantGroup("UCLOUD", setOf("ucloud")),
    )

    private val MERCHANT_ALIAS_ENTRIES: List<Pair<MerchantGroup, String>> = buildList {
        MERCHANT_GROUPS.forEach { group ->
            group.aliases.forEach { alias -> add(group to alias) }
        }
    }.sortedByDescending { it.second.length }

    private val MERCHANT_IMPLIED_ENTRIES: List<Pair<MerchantGroup, String>> = buildList {
        MERCHANT_GROUPS.forEach { group ->
            group.impliedAliases.forEach { alias -> add(group to alias) }
        }
    }.sortedByDescending { it.second.length }

    private val MODEL_SYNONYM_GROUPS: List<Set<String>> = listOf(
        // Lines / routes
        setOf("softbank", "软银", "sb"),
        setOf("9929", "as9929"),
        setOf("4837", "as4837"),
        setOf("163", "as4134"),
        setOf("gia", "giae"),
        setOf("eb", "eyeball"),
        // Regions / cities / countries
        setOf("tokyo", "tyo", "nrt", "东京"),
        setOf("osaka", "kix", "大阪"),
        setOf("lax", "la", "losangeles", "美西", "洛杉矶"),
        setOf("sjc", "sanjose", "圣何塞"),
        setOf("sea", "seattle", "西雅图"),
        setOf("dal", "dallas", "达拉斯"),
        setOf("nyc", "ny", "newyork", "纽约"),
        setOf("chi", "chicago", "芝加哥"),
        setOf("mia", "miami", "迈阿密"),
        setOf("atl", "atlanta", "亚特兰大"),
        setOf("las", "lasvegas", "拉斯维加斯"),
        setOf("buf", "buffalo", "水牛城"),
        setOf("hk", "hkg", "香港"),
        setOf("sg", "sin", "sgp", "新加坡", "狮城", "坡县"),
        setOf("jp", "jpn", "日本"),
        setOf("us", "usa", "美国"),
        setOf("de", "deu", "德国"),
        setOf("fra", "frankfurt", "法兰克福", "法克"),
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
        setOf("au", "aus", "syd", "sydney", "澳洲", "澳大利亚", "悉尼"),
        setOf("stardust", "星尘"),
        setOf("lighthouse", "轻量"),
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
        setOf("1111", "双十一"),
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

    private val BWG_NAMED_PLAN_SKUS: Set<String> = BWG_SELF_SUFFICIENT_SKUS + setOf("plan", "theplan")

    // Positive allow-list partitioned by semantic role
    private val LOCATION_TOKENS_LOWER: Set<String> = setOf(
        "lax", "la", "losangeles", "美西", "洛杉矶",
        "sjc", "sanjose", "圣何塞",
        "sea", "seattle", "西雅图",
        "dal", "dallas", "达拉斯",
        "nyc", "ny", "newyork", "纽约",
        "chi", "chicago", "芝加哥",
        "mia", "miami", "迈阿密",
        "atl", "atlanta", "亚特兰大",
        "las", "lasvegas", "拉斯维加斯",
        "buf", "buffalo", "水牛城",
        "phx", "phoenix", "凤凰城",
        "iad", "ashburn", "美东",
        "hk", "hkg", "香港",
        "sg", "sin", "sgp", "新加坡", "狮城", "坡县",
        "jp", "jpn", "日本",
        "tokyo", "tyo", "nrt", "东京",
        "osaka", "kix", "大阪",
        "us", "usa", "美国",
        "de", "deu", "德国",
        "fra", "frankfurt", "法兰克福", "法克",
        "nbg", "纽伦堡", "fsn",
        "vie", "vienna", "维也纳",
        "uk", "gbr", "英国",
        "lon", "london", "伦敦",
        "nl", "nld", "荷兰",
        "ams", "amsterdam", "阿姆斯特丹",
        "fr", "paris", "法国", "巴黎",
        "kr", "kor", "韩国",
        "seoul", "icn", "首尔", "chuncheon", "春川",
        "tw", "tpe", "台湾", "台北",
        "lu", "lux", "luxembourg", "卢森堡",
        "fi", "fin", "hel", "helsinki", "芬兰",
        "ca", "can", "加拿大", "toronto", "多伦多", "vancouver", "温哥华", "montreal", "蒙特利尔", "bhs",
        "au", "aus", "syd", "sydney", "澳洲", "澳大利亚", "悉尼",
        "ch", "zurich", "瑞士",
        "pl", "波兰",
        "ru", "俄罗斯", "moscow", "莫斯科",
        "dubai", "迪拜",
        "my", "马来", "马来西亚",
        "th", "泰国",
        "vn", "越南",
        "硅谷", "弗吉尼亚",
    )

    private val LINE_ROUTE_TOKENS_LOWER: Set<String> = setOf(
        "pro", "eb", "eyeball", "t1",
        "cn2", "gia", "giae", "cmin2",
        "9929", "as9929", "4837", "as4837", "163", "as4134",
        "iij", "softbank", "软银", "sb", "bgp", "ntt", "bbtec", "kddi", "cmi",
        "ct", "cu", "cm", "cuvip", "cu2", "retn", "hkbn", "hkt", "hgc", "pccw", "hinet",
    )

    private val PRIMARY_SKU_TOKENS_LOWER: Set<String> = setOf(
        "megabox", "megaboxpro", "biggerbox", "biggerboxpro", "bigbox",
        "minibox", "powerbox", "sakurabox", "plan", "theplan", "chicken",
        "lightsail", "ec2", "cssd", "sc2", "ks", "le", "sys", "rise", "advance",
        "simplecloud", "stardust", "星尘",
    )

    private val PLAN_TIER_TOKENS_LOWER: Set<String> = setOf(
        "wee", "tiny", "malibu", "palmspring", "palmsprings", "victoria", "corona", "intro",
        "starter", "mini", "micro", "nano", "lite", "standard", "basic", "eco", "economy",
        "vol", "hyper", "co", "jinx", "free", "plus", "max", "ultra", "small", "medium", "large",
        "special", "box", "nat", "lxc", "轻量", "lighthouse", "ecs", "cvm", "slice", "slab",
        "2222", "2525", "1111", "3333", "9999",
    )

    private val EDITION_TOKENS_LOWER: Set<String> = setOf(
        "黑五", "bf", "blackfriday", "双十一", "圣诞", "周年",
    )

    // Pure non-ASCII CJK domain entities only (never mixed ASCII+CJK like `d家` so ASCII words like `amd家` are never split)
    private val KNOWN_CJK_DOMAIN_ENTITIES: Set<String> = buildSet {
        MERCHANT_GROUPS.forEach { group ->
            group.aliases.filter { it.all { ch -> ch.code >= 128 } }.forEach(::add)
            group.impliedAliases.filter { it.all { ch -> ch.code >= 128 } }.forEach(::add)
        }
        MODEL_SYNONYM_GROUPS.forEach { group ->
            group.filter { it.all { ch -> ch.code >= 128 } }.forEach(::add)
        }
        LOCATION_TOKENS_LOWER.filter { it.all { ch -> ch.code >= 128 } }.forEach(::add)
        EDITION_TOKENS_LOWER.filter { it.all { ch -> ch.code >= 128 } }.forEach(::add)
        add("轻量")
    }

    // Bounded fuzzy matching dictionaries (pure ASCII alphabetic words ONLY; never matches numeric codes like RS1000/DC6/G9)
    private val FUZZY_MERCHANT_ALPHA_ENTRIES: List<Pair<MerchantGroup, String>> = buildList {
        MERCHANT_GROUPS.forEach { group ->
            group.aliases.forEach { alias ->
                if (alias.length >= 4 && alias != "lala" && alias.all { it in 'a'..'z' }) {
                    add(group to alias)
                }
            }
        }
    }

    private data class FuzzySkuEntry(
        val lowerKey: String,
        val displayText: String,
        val role: TokenRole,
    )

    private val FUZZY_SKU_ENTRIES: List<FuzzySkuEntry> = listOf(
        FuzzySkuEntry("megabox", "MEGABOX", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("megaboxpro", "MEGABOX", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("biggerbox", "BIGGERBOX", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("biggerboxpro", "BIGGERBOX", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("minibox", "MINIBOX", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("powerbox", "POWERBOX", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("sakurabox", "SAKURABOX", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("lightsail", "Lightsail", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("simplecloud", "SimpleCloud", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("stardust", "Stardust", TokenRole.PRIMARY_SKU),
        FuzzySkuEntry("softbank", "Softbank", TokenRole.LINE),
        FuzzySkuEntry("eyeball", "EB", TokenRole.LINE),
        FuzzySkuEntry("malibu", "Malibu", TokenRole.PLAN),
        FuzzySkuEntry("corona", "CORONA", TokenRole.PLAN),
        FuzzySkuEntry("victoria", "Victoria", TokenRole.PLAN),
        FuzzySkuEntry("palmspring", "PalmSpring", TokenRole.PLAN),
        FuzzySkuEntry("palmsprings", "PalmSpring", TokenRole.PLAN),
        FuzzySkuEntry("starter", "Starter", TokenRole.PLAN),
        FuzzySkuEntry("standard", "Standard", TokenRole.PLAN),
        FuzzySkuEntry("lighthouse", "Lighthouse", TokenRole.PLAN),
        FuzzySkuEntry("economy", "Eco", TokenRole.PLAN),
        FuzzySkuEntry("tokyo", "Tokyo", TokenRole.LOCATION),
        FuzzySkuEntry("osaka", "Osaka", TokenRole.LOCATION),
        FuzzySkuEntry("seattle", "Seattle", TokenRole.LOCATION),
        FuzzySkuEntry("sanjose", "SJC", TokenRole.LOCATION),
        FuzzySkuEntry("dallas", "Dallas", TokenRole.LOCATION),
        FuzzySkuEntry("chicago", "Chicago", TokenRole.LOCATION),
        FuzzySkuEntry("miami", "Miami", TokenRole.LOCATION),
        FuzzySkuEntry("seoul", "Seoul", TokenRole.LOCATION),
        FuzzySkuEntry("london", "London", TokenRole.LOCATION),
        FuzzySkuEntry("paris", "Paris", TokenRole.LOCATION),
        FuzzySkuEntry("frankfurt", "FRA", TokenRole.LOCATION),
        FuzzySkuEntry("amsterdam", "AMS", TokenRole.LOCATION),
        FuzzySkuEntry("helsinki", "Helsinki", TokenRole.LOCATION),
        FuzzySkuEntry("luxembourg", "Luxembourg", TokenRole.LOCATION),
        FuzzySkuEntry("singapore", "SG", TokenRole.LOCATION),
        FuzzySkuEntry("losangeles", "LAX", TokenRole.LOCATION),
        FuzzySkuEntry("blackfriday", "BlackFriday", TokenRole.EDITION),
    )

    private val FUZZY_SKU_LOWER_SET: Set<String> = FUZZY_SKU_ENTRIES.mapTo(HashSet()) { it.lowerKey }

    private val FUZZY_ENGLISH_WORD_GUARD: Set<String> = setOf(
        "about", "above", "account", "active", "actual", "added", "admin", "after", "again",
        "agent", "agree", "ahead", "alarm", "album", "alert", "alike", "alive", "allow",
        "alone", "along", "alpha", "alter", "among", "amount", "android", "angle", "annual",
        "apple", "apply", "arena", "argue", "arise", "array", "aside", "asset", "audio",
        "audit", "avoid", "award", "aware", "backup", "balance", "basic", "batch", "beach",
        "begin", "being", "below", "bench", "berlin", "better", "bigger", "billing", "birth",
        "black", "blade", "blame", "blank", "blast", "blend", "bless", "blind", "block",
        "blood", "board", "boost", "booth", "bound", "brain", "brand", "brave", "bread",
        "break", "breed", "brief", "bring", "broad", "broke", "brown", "browser", "budget",
        "build", "built", "bundle", "buyer", "cable", "cache", "carry", "catch", "cause",
        "chain", "chair", "change", "channel", "chart", "cheap", "check", "chest", "chief",
        "child", "china", "chose", "chrome", "cisco", "civil", "claim", "class", "claude",
        "clean", "clear", "click", "client", "climb", "clock", "clone", "close", "cloud",
        "coach", "coast", "color", "comment", "common", "config", "connect", "contact", "control",
        "could", "count", "coupon", "court", "cover", "craft", "crash", "crazy", "create",
        "credit", "crime", "cross", "crowd", "crown", "curve", "cycle", "daily", "dance",
        "dated", "dealt", "death", "debian", "debug", "debut", "delay", "delete", "delta",
        "demos", "dense", "depth", "detail", "device", "direct", "dirty", "discount", "display",
        "docker", "doing", "domain", "double", "doubt", "dozen", "draft", "drain", "drama",
        "drawn", "dream", "dress", "drill", "drink", "drive", "drove", "dying", "eager",
        "early", "earth", "eight", "either", "elite", "email", "empty", "enable", "enemy",
        "enjoy", "enter", "entry", "equal", "error", "event", "every", "exact", "exist",
        "extra", "faith", "false", "faster", "fault", "fetch", "fiber", "field", "fifth",
        "fifty", "fight", "files", "final", "first", "fixed", "flash", "fleet", "floor",
        "fluid", "focus", "force", "forever", "forth", "forty", "forum", "found", "frame",
        "frank", "fraud", "fresh", "front", "fruit", "fully", "funny", "games", "ghost",
        "giant", "github", "given", "glass", "global", "gmail", "going", "google", "grace",
        "grade", "grand", "grant", "graph", "grass", "great", "green", "gross", "group",
        "grown", "guard", "guess", "guest", "guide", "happy", "harry", "heart", "heavy",
        "hello", "hence", "higher", "horse", "hosting", "hotel", "house", "human", "ideal",
        "image", "index", "inner", "input", "intel", "invoice", "issue", "items", "japan",
        "joins", "joint", "jones", "judge", "known", "kotlin", "label", "large", "laser",
        "later", "latency", "laugh", "layer", "learn", "lease", "least", "leave", "legal",
        "level", "light", "limit", "lines", "links", "linux", "lists", "local", "logic",
        "login", "longer", "loose", "lower", "lucky", "lunch", "lying", "machine", "magic",
        "major", "maker", "march", "maria", "match", "maybe", "mayor", "meant", "media",
        "memory", "merge", "message", "metal", "meter", "might", "minor", "minus", "mixed",
        "model", "money", "monitor", "month", "monthly", "moral", "motor", "mount", "mouse",
        "mouth", "movie", "music", "naive", "named", "names", "native", "needs", "network",
        "never", "newer", "newly", "nexus", "night", "noise", "normal", "north", "noted",
        "notes", "novel", "nurse", "occur", "ocean", "offer", "often", "older", "olive",
        "online", "openai", "opens", "opera", "order", "origin", "original", "other", "ought",
        "outer", "owned", "owner", "package", "paint", "panel", "paper", "party", "patch",
        "pause", "payment", "peace", "penny", "peter", "phase", "phone", "photo", "piano",
        "piece", "pilot", "pitch", "pixel", "place", "plain", "plane", "plant", "plate",
        "plays", "plaza", "point", "polar", "police", "ports", "posts", "pound", "power",
        "premium", "press", "price", "pride", "prime", "print", "prior", "private", "prize",
        "probe", "product", "profile", "promo", "proof", "proud", "prove", "proxy", "public",
        "pulse", "punch", "pupil", "python", "quarter", "queen", "query", "quest", "queue",
        "quick", "quiet", "quite", "quote", "radar", "radio", "raise", "random", "range",
        "rapid", "ratio", "reach", "react", "ready", "realm", "rebel", "refer", "region",
        "relay", "remove", "renew", "reply", "report", "reset", "resin", "result", "retry",
        "return", "reuse", "review", "rider", "ridge", "rifle", "right", "rigid", "risky",
        "rival", "river", "robin", "robot", "rocky", "rogue", "roman", "rooms", "roots",
        "rough", "round", "route", "router", "routing", "royal", "rural", "sadly", "saint",
        "salad", "sales", "salon", "sandy", "sauce", "saved", "scale", "scare", "scene",
        "scope", "score", "scout", "scrap", "screw", "script", "second", "seller", "sense",
        "serve", "server", "service", "setup", "seven", "shade", "shake", "shall", "shame",
        "shape", "share", "shark", "sharp", "sheep", "sheer", "sheet", "shelf", "shell",
        "shift", "shine", "shirt", "shock", "shoot", "shops", "short", "shout", "shown",
        "shows", "sides", "siege", "sight", "sigma", "signs", "silly", "simple", "since",
        "single", "sites", "sixth", "sixty", "sized", "sizes", "skill", "skirt", "skull",
        "slash", "slate", "slave", "sleep", "slice", "slide", "slope", "slots", "small",
        "smaller", "smart", "smell", "smile", "smith", "smoke", "smooth", "snake", "solar",
        "solid", "solve", "sonic", "sorry", "sorts", "sound", "source", "south", "space",
        "spare", "spark", "speak", "speed", "spell", "spend", "spent", "spice", "spike",
        "spill", "spine", "spite", "split", "spoke", "spoon", "sport", "spots", "spray",
        "squad", "stable", "stack", "staff", "stage", "stain", "stake", "stale", "stalk",
        "stall", "stamp", "stand", "stars", "start", "state", "stats", "status", "stays",
        "steak", "steal", "steam", "steel", "steep", "steer", "stems", "steps", "stern",
        "stick", "stiff", "still", "stock", "stole", "stone", "stood", "stool", "stops",
        "storage", "store", "storm", "story", "stove", "strap", "straw", "strip", "strong",
        "stuck", "student", "study", "stuff", "style", "sugar", "suite", "sunny", "super",
        "support", "surface", "surge", "swamp", "swarm", "swear", "sweat", "sweep", "sweet",
        "swell", "swept", "swift", "swing", "swiss", "sword", "swore", "sworn", "system",
        "table", "taken", "takes", "tales", "talks", "tanks", "tapes", "target", "tasks",
        "taste", "tasty", "taxes", "teach", "teams", "tears", "teens", "teeth", "tells",
        "tempo", "tends", "tenor", "tense", "tenth", "terms", "testing", "tests", "texas",
        "texts", "thank", "theft", "their", "theme", "there", "these", "thick", "thief",
        "thigh", "thing", "think", "third", "those", "three", "threw", "throw", "thumb",
        "tiger", "tight", "tiles", "timer", "times", "tired", "title", "toast", "today",
        "token", "tolls", "tombs", "tones", "tools", "tooth", "topic", "torch", "total",
        "touch", "tough", "tours", "towel", "tower", "towns", "toxic", "trace", "track",
        "tract", "trade", "traffic", "trail", "train", "trait", "transfer", "trash", "treat",
        "trees", "trend", "trial", "tribe", "trick", "tried", "tries", "triple", "troop",
        "trout", "truck", "truly", "trump", "trunk", "trust", "truth", "tubes", "tumor",
        "tuned", "tunes", "turbo", "turns", "tutor", "tweet", "twice", "twins", "twist",
        "typed", "types", "ubuntu", "ultra", "uncle", "under", "undue", "union", "unite",
        "units", "unity", "unlock", "until", "update", "upgrade", "upload", "upper", "upset",
        "urban", "urged", "usage", "users", "using", "usual", "valid", "value", "valve",
        "vapor", "vault", "venue", "verbs", "verge", "verse", "version", "video", "views",
        "vigor", "villa", "vinyl", "viral", "virus", "visit", "visor", "vista", "vital",
        "vivid", "vocal", "vodka", "vogue", "voice", "voted", "voter", "votes", "vowed",
        "vowel", "wages", "wagon", "waist", "waits", "walks", "walls", "wants", "wards",
        "wares", "warms", "warns", "waste", "watch", "water", "watts", "waves", "wears",
        "weary", "weave", "wechat", "wedge", "weeds", "weeks", "weigh", "weird", "wells",
        "welsh", "whales", "wheat", "wheel", "where", "which", "while", "whine", "white",
        "whole", "whose", "wider", "widow", "width", "windows", "winds", "wines", "wings",
        "wired", "wires", "witch", "wives", "woman", "women", "woods", "words", "works",
        "world", "worms", "worry", "worse", "worst", "worth", "would", "wound", "woven",
        "wraps", "wrath", "wreck", "wrist", "write", "wrong", "wrote", "yacht", "yards",
        "yearly", "years", "yeast", "yield", "young", "yours", "youth", "zones",
    )

    // Positive shape rules for alphanumeric VPS/cloud SKU tokens (rejects DDR5, E5, IPv4, 200M, 1C1G, etc.)
    private val DC_LOCATION_SHAPE_REGEX = Regex("""(?i)^(?:dc0?\d{1,2}|(?:hk|sg|jp|us|la|de|nl|uk|fr|tw|kr)\d{1,2})$""")
    private val SERVER_SKU_SHAPE_REGEX = Regex(
        """(?i)^(?:rs|vps|ax|ex|cx|cpx|cax|ccx|sx|px|dx|rx|sc|az)\d{1,4}(?:\.\d+)?[a-z]?$""",
    )
    private val TIER_CODE_SHAPE_REGEX = Regex("""(?i)^[stp]\d{1,2}$""")
    private val GENERATION_SHAPE_REGEX = Regex("""(?i)^(?:g\d{1,2}(?:\.\d+)?[a-z]?|v\d{1,2}(?:\.\d+)?)$""")
    private val AWS_QUOTA_SHAPE_REGEX = Regex("""(?i)^(?:4|8|16|32|64|128|256|512)v$""")

    // Broad region tokens -> specific cities/DCs that supersede them when both appear in the same title
    private val BROAD_REGION_SUPERSEDED_BY: Map<Set<String>, Set<String>> = mapOf(
        setOf("jp", "jpn", "日本") to setOf("tokyo", "tyo", "nrt", "东京", "osaka", "kix", "大阪"),
        setOf("us", "usa", "美国") to setOf(
            "lax", "la", "losangeles", "美西", "洛杉矶",
            "sjc", "sanjose", "圣何塞",
            "sea", "seattle", "西雅图",
            "dal", "dallas", "达拉斯",
            "nyc", "ny", "newyork", "纽约",
            "chi", "chicago", "芝加哥",
            "mia", "miami", "迈阿密",
            "atl", "atlanta", "亚特兰大",
            "las", "lasvegas", "拉斯维加斯",
            "硅谷", "弗吉尼亚",
            "美东", "dc2", "dc02", "dc6", "dc06", "dc9", "dc09",
        ),
        setOf("美西") to setOf("sjc", "sanjose", "圣何塞", "sea", "seattle", "西雅图", "硅谷"),
        setOf("de", "deu", "德国") to setOf("fra", "frankfurt", "法兰克福", "法克", "nbg", "纽伦堡", "fsn"),
        setOf("uk", "gbr", "英国") to setOf("lon", "london", "伦敦"),
        setOf("nl", "nld", "荷兰") to setOf("ams", "amsterdam", "阿姆斯特丹"),
        setOf("kr", "kor", "韩国") to setOf("seoul", "icn", "首尔", "chuncheon", "春川"),
        setOf("ca", "can", "加拿大") to setOf("toronto", "多伦多", "vancouver", "温哥华", "montreal", "蒙特利尔", "bhs"),
        setOf("ru", "俄罗斯") to setOf("moscow", "莫斯科"),
    )

    private val CJK_NOISE_PHRASES: List<String> = listOf(
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
        "走闲鱼", "包过户", "包push", "免税号", "升级号", "成品号", "学生号", "赠金号", "独享号",
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
        "不议价", "一口价", "包邮", "老号", "封贴", "详情", "表格", "包", "出", "收", "转",
    )

    private val QUERY_NOISE_TOKENS: Set<String> = buildSet {
        addAll(CJK_NOISE_PHRASES)
        addAll(
            listOf(
                "的", "了", "吧", "啊", "呢", "吗", "个", "只", "台", "套", "枚", "款", "版",
                "鸡", "卖", "买", "换", "带", "送", "给", "含", "无", "不", "可", "能", "有",
                "很", "超", "真", "纯", "全", "自", "原", "明", "急", "慢", "秒", "平", "亏",
                "低", "高", "小", "大", "多", "少", "加", "减", "按", "走", "求", "蹲", "诚",
                "想", "要", "用", "购", "年", "月", "天", "付", "元", "块", "刀", "折", "价",
                "值", "费", "邮", "油", "口", "房", "三网", "网", "线", "路", "码", "卡",
                "the", "for", "with", "and", "or", "a", "an", "of", "to", "in", "on", "at",
                "by", "from", "is", "are", "sale", "sell", "selling", "buy", "buying", "trade",
                "wts", "wtb", "wtt", "vps", "server", "machine", "vm", "pvm", "sold", "closed",
                "push", "email", "mail", "original", "renew", "renewal", "expire", "expires",
                "expiring", "expiry", "year", "years", "month", "months", "yr", "mo", "annual",
                "monthly", "quarterly", "biennial", "triennial", "yearly", "traffic", "bandwidth",
                "ram", "cpu", "vcpu", "disk", "ssd", "nvme", "hdd", "ipv4", "ipv6", "ip", "kvm",
                "pm", "tg", "dd", "usdt", "cny", "rmb", "usd", "eur", "hkd", "gbp", "jpy", "sgd",
                "cad", "aud", "host", "cloud", "tech", "network", "networks", "storage",
                "amd", "epyc", "intel", "xeon", "ddr4", "ddr5", "e5", "e3",
                "netflix", "nf", "chatgpt", "gpt", "openai", "disney", "tiktok", "tk", "youtube", "ytb",
                "gmail", "outlook", "alipay", "paypal", "wechat", "wx", "vx", "qq",
                "clean", "native", "unlock", "yabs", "nodequality", "nodeseek", "ns",
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

    // Scrubbers
    private val SCRUB_XIANYU_MARKETPLACE_REGEX = Regex("""(?:走|挂|拍|上|支持|可走)咸鱼|咸鱼(?:交易|担保|链接|中介|拍|口令|app)""", RegexOption.IGNORE_CASE)
    private val SCRUB_FREE_PUSH_REGEX = Regex("""(?i)\bfree\s+(?:push|transfer|mail|email)\b""")
    private val SCRUB_IPV4_CIDR_REGEX = Regex("""\b\d{1,3}(?:\.(?:\d{1,3}|\*)){3}(?:/\d{1,2})?\b""")
    private val SCRUB_URL_REGEX = Regex("""(?:https?|nsapp)://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val SCRUB_BARE_HOSTNAME_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9.])(?:[A-Za-z0-9-]+\.)+(?:com|net|org|io|cc|nc|xyz|top|me|info|biz|dev|app|site|online|shop|club|vip|icu|fun|live|store|wiki|tv|gg|ai|cn|edu|gov)(?![A-Za-z0-9.])""",
    )
    private val SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9.])(?:[A-Za-z0-9-]+\.)+(?:de|fr|uk|eu|ru|jp|hk|sg|nl|tw|ca|au)(?=\s*(?:测评|评测|脚本|教程|网站|官网|域名))""",
    )
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
        """(?<![0-9A-Za-z])(?:201\d|202\d|203\d)(?:\s*(?:年|到期|款))?(?![0-9A-Za-z])""",
    )
    private val SCRUB_PORT_REGEX = Regex("""(?<=[A-Za-z0-9\]])(?::\d{2,5})\b""")
    private val SCRUB_ASN_REGEX = Regex("""(?i)\bAS\d+\b""")
    private val SCRUB_LINE_MODEL_CODE_REGEX = Regex(
        """(?i)\b(?:cn2|9929|4837|163|cmin2|dc-?\d{1,2}|hk\d{2}|v\d+(?:\.\d+)?|g\d+(?:\.\d+)?)\b""",
    )
    private val SCRUB_PERCENT_REGEX = Regex("""\d+(?:\.\d+)?\s*%""")
    private val SCRUB_ZH_DISCOUNT_RATE_REGEX = Regex("""\d+(?:\.\d+)?\s*折(?![价])""")
    private val SCRUB_FOREIGN_CURRENCY_PREFIX_REGEX = Regex(
        """(?i)(?:us\$|hk\$|s\$|c\$|a\$|nt\$|\$|€|£)\s*\d+(?:\.\d+)?(?:\s*/\s*(?:yr|mo|year|month|y|m|q|年|季|月|半年|2年|3年|两年|三年))?""",
    )
    private val SCRUB_FOREIGN_CURRENCY_SUFFIX_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9.])\d+(?:\.\d+)?\s*(?:刀|美元|美金|欧元|欧|英镑|港币|港元|日元|新币|加元|澳元|(?:usd|eur|hkd|gbp|jpy|sgd|cad|aud|u)(?![a-z0-9]))(?:\s*/\s*(?:yr|mo|year|month|y|m|q|年|季|月|半年|2年|3年|两年|三年))?""",
    )
    private val SCRUB_CNY_PRICE_FOR_QUERY_REGEX = Regex(
        """(?i)(?:(?:[¥￥]|rmb|cny|明盘|一口价|底价|现价|售价|价格|自刀|降价|续费|原价|残值|剩余价值|净残值|预算)\s*[:：]?\s*\d+(?:\.\d+)?\s*(?:元|块|rmb|cny|r|[¥￥]|刀|u)?(?:\s*(?:出|收|转))?|(?<![A-Za-z0-9.])\d+(?:\.\d+)?\s*(?:元|块|(?:rmb|cny|r)(?![a-z0-9]))(?:\s*(?:出|收|转))?)""",
    )
    private val SCRUB_BARE_BUYOUT_FOR_QUERY_REGEX = Regex(
        """(?<![A-Za-z0-9+\-.])(\d{1,5}(?:\.\d{1,2})?)\s*(?:出|收|转)(?![现口发来错品席身生版差站墙狱])""",
    )
    private val SCRUB_BILLING_SLASH_REGEX = Regex(
        """(?i)/\s*(?:3yr|2yr|yr|year|mo|month|quarter|y|m|q|年|季|月|半年|两年|三年|2年|3年)(?![a-z0-9])""",
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

    // Product code compound normalizers (joiners run BEFORE year scrubbers; splitters unpack glued SKUs)
    private val NORM_PVM_PREFIX_REGEX = Regex("""(?i)(?<![A-Za-z0-9])pvm[.\-](?=[A-Za-z])""")
    private val NORM_DOUBLE_ELEVEN_REGEX = Regex("""双\s*11(?![0-9])""")
    private val NORM_DC_CODE_REGEX = Regex("""(?i)(?<![A-Za-z0-9])dc[- ](\d{1,2})(?![A-Za-z0-9])""")
    private val NORM_HYPHEN_MODEL_NUM_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(rs|ax|ex|cx|cpx|cax|ccx)[- ](\d{2,4})(?![A-Za-z0-9])""",
    )
    private val NORM_NETCUP_VPS_NUM_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(vps)[- ](200|250|500|1000|2000|3000|4000|6000|8000)(?![A-Za-z0-9])""",
    )
    private val NORM_GLUED_DC_ROUTE_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(dc\d{1,2})(cn2|gia|cmin2|9929|4837|softbank|iij)""",
    )
    private val NORM_GLUED_CN2_GIA_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(cn2)(gia(?:-?e)?)(?![A-Za-z0-9])""",
    )
    private val NORM_GLUED_ROUTE_NUM_SKU_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(softbank|iij|cmin2|bgp|ntt|cmi|sg|hk|hkg|jp|tyo|tokyo|osaka|lax)(2222|2525|1111|3333|9999)(?![A-Za-z0-9])""",
    )
    private val NORM_GLUED_DMIT_TRIPLE_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(lax|hkg|tyo)(pro|eb)(wee|tiny|malibu|corona|intro|mini|starter|victoria|palmspring)(?![A-Za-z0-9])""",
    )
    private val NORM_GLUED_DMIT_PAIR_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])(pro|eb)(wee|tiny|malibu|corona|intro|mini|starter|victoria|palmspring)(?![A-Za-z0-9])""",
    )
    private val NORM_GLUED_SERVER_GEN_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9])((?:rs|vps)\d{3,4})(g\d{1,2}(?:\.\d+)?[a-z]?)(?![A-Za-z0-9])""",
    )
    private val NORM_GIA_E_REGEX = Regex("""(?i)(?<![A-Za-z0-9])gia-e(?![A-Za-z0-9])""")
    private val NORM_MEGABOX_PRO_REGEX = Regex("""(?i)(?<![A-Za-z0-9])mega[- ]?box[- ]?pro(?![A-Za-z0-9])""")
    private val NORM_MEGABOX_SPACED_REGEX = Regex("""(?i)(?<![A-Za-z0-9])mega[- ]box(?![A-Za-z0-9])""")
    private val NORM_BIGGERBOX_PRO_REGEX = Regex("""(?i)(?<![A-Za-z0-9])bigger[- ]?box[- ]?pro(?![A-Za-z0-9])""")
    private val NORM_BIGGERBOX_SPACED_REGEX = Regex("""(?i)(?<![A-Za-z0-9])bigger[- ]box(?![A-Za-z0-9])""")
    private val NORM_OTHER_BOX_SPACED_REGEX = Regex("""(?i)(?<![A-Za-z0-9])(mini|power|sakura)[- ]box(?![A-Za-z0-9])""")
    private val NORM_THE_PLAN_REGEX = Regex("""(?i)(?<![A-Za-z0-9])the[- ]?plan(?![A-Za-z0-9])""")
    private val MINI_PRO_ADJACENT_REGEX = Regex("""(?i)(?<![a-z0-9])mini[\s.\-_]+pro(?![a-z0-9])""")

    // Markdown body cleanup & structured metadata extraction
    private val FENCED_CODE_BLOCK_REGEX = Regex("""```[\s\S]*?```""")
    private val MARKDOWN_IMAGE_REGEX = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val MARKDOWN_DECOR_REGEX = Regex("""[*_`#>]+""")
    private val LEADING_LIST_MARKER_REGEX = Regex(
        """^(?:[-+*•·–—]\s+|(?:\d{1,2}|[一二三四五六七八九十])[.)、）]\s*|[(\[（]\d{1,2}[)\]）]\s*)""",
    )
    private val SWAP_OR_COMPARE_LINE_START_REGEX = Regex(
        """^\s*(?:也可换|也可以换|或者换|或换|支持换|可换|想换|换机|求换|顺便换|顺便收|不换|不收|不要(?!了)|非(?!诚|常)|不是|别拿|勿拿|对比|比\S+稳|代替)""",
    )
    private val SWAP_OR_NEGATION_BRACKET_REGEX = Regex(
        """[（(\[【][^）)\]】]{0,60}(?:不换|不收|不要(?!了)|非(?!诚|常)|不是|别拿|勿拿|也可换|也可以换|或者换|或换|可换|想换|换机|求换|支持换|顺便换|顺便收|换[^）)\]】]{1,25}(?:也行|也可|都可以|亦可|就行)|对比|比\S+稳)[^）)\]】]*[）)\]】]""",
    )
    private val SWAP_OR_COMPARE_CLAUSE_REGEX = Regex(
        """(?:[，,。;；/|+\s]+)(?:不换|不收|不要(?!了)|非(?!诚|常)(?=[A-Za-z0-9\u4e00-\u9fa5])|不是|别拿|勿拿|也可换|也可以换|或者换|或换|可换|想换|换机|求换|支持换|顺便换|顺便收|换[^，,。;；]{1,25}(?:也行|也可|都可以|亦可|就行)|对比|比\S+稳)[^，,。;；]*""",
    )
    private val ENGLISH_DO_PREDECESSOR_REGEX = Regex("""(?i)(?:^|[^a-z0-9])(?:to|how|what|why|when|where|i|we|you|they|can|will|would|could|should|just|let|please|don't|dont)\s+$""")
    private val ENGLISH_DO_SUCCESSOR_REGEX = Regex("""(?i)^\s+(?:not|you|we|they|it|this|that|so|well|something|anything|everything|nothing|have|want|need|know|think|like)\b""")
    private val DMIT_LINE_TOKENS: Set<String> = setOf("pro", "eb", "t1")
    private val DMIT_TIER_TOKENS: Set<String> = setOf("wee", "tiny", "malibu", "corona", "victoria", "palmspring", "intro", "starter", "mini")
    private val FALLBACK_MERCHANT_LABEL_REGEX = Regex(
        """^(?:商家|厂商|服务商|vendor|provider)\s*[:：]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val FALLBACK_MODEL_LABEL_REGEX = Regex(
        """(?:^|[，,。\s])(?:套餐\s*/\s*机型|机型\s*/\s*套餐|型号|机型|套餐|产品|配置|机器|plan|model|product)\s*[:：]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val TABLE_MERCHANT_KEY_REGEX = Regex("""^(?:商家|厂商|服务商|vendor|provider)$""", RegexOption.IGNORE_CASE)
    private val TABLE_MODEL_KEY_REGEX = Regex(
        """^(?:套餐\s*/\s*机型|机型\s*/\s*套餐|机型名称|机器型号|机器名称|套餐名称|型号|机型|套餐|产品|机器|plan|model|product)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Compact Traditional -> Simplified Chinese character folding map covering common NodeSeek
     * VPS trade terms, vendors, locations, pricing/billing words, and forum phrasing.
     */
    private val TRADITIONAL_TO_SIMPLIFIED_MAP: Map<Char, Char> = mapOf(
        // Vendors & slang
        '媽' to '妈', '綠' to '绿', '雲' to '云', '亞' to '亚', '馬' to '马', '遜' to '逊',
        '騰' to '腾', '訊' to '讯', '華' to '华', '為' to '为', '軟' to '软', '穀' to '谷',
        '愛' to '爱', '麗' to '丽', '絲' to '丝', '鹹' to '咸', '魚' to '鱼', '閒' to '闲',
        '龜' to '龟', '殼' to '壳', '達' to '达', '庫' to '库', '倫' to '伦', '萊' to '莱',
        '語' to '语', '塵' to '尘', '東' to '东', '網' to '网',
        // Locations & routes
        '銀' to '银', '磯' to '矶', '聖' to '圣', '臺' to '台', '灣' to '湾', '國' to '国',
        '蘭' to '兰', '紐' to '纽', '約' to '约', '爾' to '尔', '韓' to '韩', '盧' to '卢',
        '歐' to '欧', '鳳' to '凤', '維' to '维', '納' to '纳', '蘇' to '苏', '溫' to '温',
        '聯' to '联', '電' to '电', '動' to '动', '線' to '线', '優' to '优', '輕' to '轻',
        '連' to '连', '寬' to '宽', '雙' to '双', '邁' to '迈', '魯' to '鲁', '區' to '区',
        // Pricing, spreads, billing & trade verbs
        '價' to '价', '殘' to '残', '淨' to '净', '餘' to '余', '額' to '额', '虧' to '亏',
        '讓' to '让', '轉' to '转', '貼' to '贴', '賣' to '卖', '買' to '买', '購' to '购',
        '續' to '续', '費' to '费', '幣' to '币', '圓' to '圆', '塊' to '块', '萬' to '万',
        '兩' to '两', '個' to '个', '隻' to '只', '雞' to '鸡', '機' to '机', '盤' to '盘',
        '帶' to '带', '郵' to '邮', '給' to '给', '無' to '无', '對' to '对', '換' to '换',
        '誠' to '诚', '擾' to '扰', '記' to '记', '議' to '议', '預' to '预', '實' to '实',
        '現' to '现', '總' to '总', '計' to '计', '稅' to '税', '號' to '号', '帳' to '账',
        '賬' to '账', '單' to '单', '過' to '过', '戶' to '户', '傳' to '传', '寶' to '宝',
        '詳' to '详', '見' to '见', '進' to '进', '來' to '来', '謝' to '谢', '絕' to '绝',
        '極' to '极', '穩' to '稳', '開' to '开', '關' to '关', '備' to '备', '繫' to '系',
        '學' to '学', '贈' to '赠', '獨' to '独', '產' to '产', '設' to '设', '內' to '内',
        '測' to '测', '評' to '评', '腳' to '脚', '數' to '数', '據' to '据', '點' to '点',
        '節' to '节', '體' to '体', '鎖' to '锁', '乾' to '干', '純' to '纯', '級' to '级',
        '誕' to '诞', '週' to '周', '標' to '标', '錯' to '错', '裡' to '里', '裏' to '里',
        '發' to '发', '後' to '后', '從' to '从', '還' to '还', '離' to '离', '經' to '经',
        '終' to '终', '條' to '条', '請' to '请', '詢' to '询', '談' to '谈', '論' to '论',
        '車' to '车', '靈' to '灵', '滿' to '满', '問' to '问', '間' to '间', '時' to '时',
        '長' to '长', '張' to '张', '幫' to '帮', '頂' to '顶', '僅' to '仅', '儲' to '储',
        '別' to '别', '員' to '员', '圖' to '图', '獎' to '奖', '寫' to '写', '將' to '将',
        '專' to '专', '幾' to '几', '廣' to '广', '應' to '应', '復' to '复', '複' to '复',
        '擔' to '担', '斷' to '断', '會' to '会', '樣' to '样', '檢' to '检', '權' to '权',
        '歲' to '岁', '歷' to '历', '沒' to '没', '準' to '准', '漲' to '涨', '熱' to '热',
        '營' to '营', '牆' to '墙', '畫' to '画', '碼' to '码', '確' to '确', '禮' to '礼',
        '種' to '种', '簽' to '签', '簡' to '简', '類' to '类', '紅' to '红', '組' to '组',
        '結' to '结', '絡' to '络', '統' to '统', '緊' to '紧', '緣' to '缘', '繼' to '继',
        '聲' to '声', '聽' to '听', '與' to '与', '舊' to '旧', '藍' to '蓝', '補' to '补',
        '裝' to '装', '規' to '规', '視' to '视', '親' to '亲', '覺' to '觉', '觀' to '观',
        '訂' to '订', '討' to '讨', '許' to '许', '訪' to '访', '証' to '证', '試' to '试',
        '話' to '话', '該' to '该', '認' to '认', '說' to '说', '誰' to '谁', '調' to '调',
        '講' to '讲', '證' to '证', '識' to '识', '護' to '护', '讀' to '读', '變' to '变',
        '負' to '负', '財' to '财', '貨' to '货', '責' to '责', '貴' to '贵', '貿' to '贸',
        '資' to '资', '賠' to '赔', '賺' to '赚', '贏' to '赢', '較' to '较', '載' to '载',
        '輸' to '输', '辦' to '办', '邊' to '边', '違' to '违', '遠' to '远', '適' to '适',
        '選' to '选', '銷' to '销', '錢' to '钱', '鍵' to '键', '錄' to '录', '門' to '门',
        '閃' to '闪', '閉' to '闭', '閱' to '阅', '際' to '际', '陸' to '陆', '隊' to '队',
        '隨' to '随', '雜' to '杂', '難' to '难', '響' to '响', '頁' to '页', '項' to '项',
        '順' to '顺', '須' to '须', '領' to '领', '頭' to '头', '題' to '题', '願' to '愿',
        '顧' to '顾', '顯' to '显', '風' to '风', '飛' to '飞', '驗' to '验', '騙' to '骗',
        '龍' to '龙', '齊' to '齐', '黨' to '党', '當' to '当',
    )

    /**
     * Folds common Traditional Chinese characters into their Simplified equivalents
     * while preserving all other characters verbatim. Reusable across extraction,
     * matching, and pricing parsers.
     */
    fun foldTraditionalToSimplified(raw: String): String {
        if (raw.isEmpty()) return ""
        var changed = false
        for (i in raw.indices) {
            if (raw[i] in TRADITIONAL_TO_SIMPLIFIED_MAP) {
                changed = true
                break
            }
        }
        if (!changed) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(TRADITIONAL_TO_SIMPLIFIED_MAP[ch] ?: ch)
        }
        return sb.toString()
    }

    fun normalizeWidthAndCase(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val mapped = TRADITIONAL_TO_SIMPLIFIED_MAP[ch] ?: ch
            val normalized = when (mapped) {
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
                in '０'..'９' -> ('0'.code + (mapped.code - '０'.code)).toChar()
                in 'Ａ'..'Ｚ' -> ('a'.code + (mapped.code - 'Ａ'.code)).toChar()
                in 'ａ'..'ｚ' -> ('a'.code + (mapped.code - 'ａ'.code)).toChar()
                else -> mapped.lowercaseChar()
            }
            sb.append(normalized)
        }
        return sb.toString()
    }

    private fun normalizeWidthPreservingCase(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val mapped = TRADITIONAL_TO_SIMPLIFIED_MAP[ch] ?: ch
            val normalized = when (mapped) {
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
                in '０'..'９' -> ('0'.code + (mapped.code - '０'.code)).toChar()
                in 'Ａ'..'Ｚ' -> ('A'.code + (mapped.code - 'Ａ'.code)).toChar()
                in 'ａ'..'ｚ' -> ('a'.code + (mapped.code - 'ａ'.code)).toChar()
                else -> mapped
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
        val titleCandidate = extractQueryFromText(title, requireMerchant = true)
        if (titleCandidate.isMultiVendorBundle) {
            return ""
        }

        // Complete or self-sufficient title queries do not need body enrichment
        val hasCompleteTitleQuery = titleCandidate.isSelfSufficientSku ||
            (titleCandidate.merchant != null && titleCandidate.modelTokenCount >= 3 && !titleCandidate.usedSpecFallback)
        if (hasCompleteTitleQuery || fallbackText.isNullOrBlank()) {
            return titleCandidate.query
        }

        val fallbackCandidate = extractQueryFromFallbackBody(
            fallbackText = fallbackText,
            preferredMerchant = titleCandidate.merchant,
        )
        if (fallbackCandidate.isMultiVendorBundle) {
            return if (titleCandidate.merchant == null) "" else titleCandidate.query
        }
        if (fallbackCandidate.query.isBlank()) {
            return titleCandidate.query
        }

        return when {
            titleCandidate.merchant == null && fallbackCandidate.merchant != null -> fallbackCandidate.query
            titleCandidate.merchant != null &&
                fallbackCandidate.merchant?.canonical == titleCandidate.merchant.canonical &&
                (titleCandidate.modelTokenCount == 0 || titleCandidate.usedSpecFallback) &&
                fallbackCandidate.modelTokenCount > 0 &&
                !fallbackCandidate.usedSpecFallback -> fallbackCandidate.query
            titleCandidate.merchant != null &&
                fallbackCandidate.merchant?.canonical == titleCandidate.merchant.canonical &&
                !fallbackCandidate.usedSpecFallback &&
                isStrictSemanticSuperset(fallbackCandidate.modelTokens, titleCandidate.modelTokens) -> fallbackCandidate.query
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
        val tokens = extractNonNoiseTokensPreservingCase(segmentKnownCjkWords(widthNorm))
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
            val nonLocation = modelTokens.filterNot { isLocationToken(it.lowercase(Locale.ROOT)) }
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
            val primary = modelTokens.firstOrNull { !isLocationToken(it.lowercase(Locale.ROOT)) }
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
        val preCleaned = normalized
            .replace(SUGGEST_STRIP_STATUS_BRACKETS_REGEX, " ")
            .replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " ")
            .replace(SCRUB_URL_REGEX, " ")
            .replace(SCRUB_BARE_HOSTNAME_REGEX, " ")
            .replace(SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX, " ")
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
        val productNormalized = normalizeProductCodes(preCleaned).lowercase(Locale.ROOT)
        val segmented = segmentKnownCjkWords(productNormalized)
        val rawTokens = splitTokens(segmented)
        val kept = ArrayList<String>(rawTokens.size)
        for (i in rawTokens.indices) {
            val tok = rawTokens[i]
            if (tok == "vps" && isSingleDigitToken(rawTokens.getOrNull(i + 1))) {
                kept += tok
                continue
            }
            if (isSingleDigitToken(tok) && rawTokens.getOrNull(i - 1) == "vps") {
                kept += tok
                continue
            }
            if (tok in QUERY_NOISE_TOKENS) continue
            kept += tok
        }
        return kept.distinct()
    }

    @JvmOverloads
    fun matchesQuery(
        title: String,
        query: String,
        bodySnippet: String? = null,
    ): Boolean {
        val normalizedRawTitle = normalizeWidthAndCase(title)
        val queryTokens = tokenizeQuery(query)
        if (normalizedRawTitle.isBlank() || queryTokens.isEmpty()) return false

        val cleanedTitle = stripSwapAndNegationClauses(normalizedRawTitle)
            .replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " ")
            .replace(SCRUB_URL_REGEX, " ")
            .replace(SCRUB_BARE_HOSTNAME_REGEX, " ")
            .replace(SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX, " ")
        val normalizedTitle = normalizeProductCodes(cleanedTitle).lowercase(Locale.ROOT)
        val segmentedTitle = segmentKnownCjkWords(normalizedTitle)
        val titleTokenList = splitTokens(segmentedTitle)
        val titleTokens = titleTokenList.toSet()

        val queryLower = normalizeProductCodes(
            normalizeWidthAndCase(query)
                .replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " ")
                .replace(SCRUB_URL_REGEX, " ")
                .replace(SCRUB_BARE_HOSTNAME_REGEX, " ")
                .replace(SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX, " "),
        ).lowercase(Locale.ROOT)
        val queryMerchant = detectMerchantGroup(segmentKnownCjkWords(queryLower))
        val remainingTokens = buildRemainingQueryTokens(queryTokens, queryMerchant)

        if (matchesNormalizedText(normalizedTitle, titleTokens, queryMerchant, remainingTokens)) {
            return true
        }

        if (bodySnippet.isNullOrBlank()) return false

        // Never let body text override a different explicit merchant or conflicting tier/line already present in title
        // (e.g. title "出瓦工 MEGABOX", body "可换 DMIT Pro Wee"; or title "出 DMIT LAX EB WEE", body "非 Pro").
        val titleExplicitMerchant = detectExplicitMerchantGroup(segmentedTitle)
        if (queryMerchant != null &&
            titleExplicitMerchant != null &&
            titleExplicitMerchant.canonical != queryMerchant.canonical
        ) {
            return false
        }
        if (hasConflictingTitleModelTokens(titleTokens, queryMerchant, remainingTokens, titleTokenList)) {
            return false
        }

        val primaryBody = extractPrimaryBodySnippetForMatching(bodySnippet)
        if (primaryBody.isBlank()) return false
        val combinedRaw = normalizeWidthAndCase("$cleanedTitle $primaryBody")
            .replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " ")
            .replace(SCRUB_URL_REGEX, " ")
            .replace(SCRUB_BARE_HOSTNAME_REGEX, " ")
            .replace(SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX, " ")
        val normalizedCombined = normalizeProductCodes(combinedRaw).lowercase(Locale.ROOT)
        val segmentedCombined = segmentKnownCjkWords(normalizedCombined)
        val combinedTokens = splitTokens(segmentedCombined).toSet()

        return matchesNormalizedText(normalizedCombined, combinedTokens, queryMerchant, remainingTokens)
    }

    /**
     * Relaxed title-only candidate matcher for selecting ultra-short peer posts (e.g. `出大妈wee`,
     * `大妈 wee 300`, `出个绿云 2222 带原油`, `出 Netcup RS1000 180r`) whose OP body should be fetched
     * and then confirmed via strict [matchesQuery]`(title, query, bodySnippet)`.
     *
     * Rules:
     * 1. If strict [matchesQuery]`(title, query)` already matches, returns `true`.
     * 2. Requires the same merchant (when query has one).
     * 3. Rejects any conflicting tier, line, SKU, generation, location, DC, or route in [title].
     * 4. Requires the query's most distinguishing SKU/tier/DC token(s) to match in [title]
     *    (tolerating omitted location/line/generation tokens), or when the query only has
     *    location + line/route tokens, requires at least one of those tokens to match.
     */
    fun matchesQueryLoosely(title: String, query: String): Boolean {
        val normalizedRawTitle = normalizeWidthAndCase(title)
        val queryTokens = tokenizeQuery(query)
        if (normalizedRawTitle.isBlank() || queryTokens.isEmpty()) return false

        val cleanedTitle = stripSwapAndNegationClauses(normalizedRawTitle)
            .replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " ")
            .replace(SCRUB_URL_REGEX, " ")
            .replace(SCRUB_BARE_HOSTNAME_REGEX, " ")
            .replace(SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX, " ")
        val normalizedTitle = normalizeProductCodes(cleanedTitle).lowercase(Locale.ROOT)
        val segmentedTitle = segmentKnownCjkWords(normalizedTitle)
        val titleTokenList = splitTokens(segmentedTitle)
        val titleTokens = titleTokenList.toSet()

        val queryLower = normalizeProductCodes(
            normalizeWidthAndCase(query)
                .replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " ")
                .replace(SCRUB_URL_REGEX, " ")
                .replace(SCRUB_BARE_HOSTNAME_REGEX, " ")
                .replace(SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX, " "),
        ).lowercase(Locale.ROOT)
        val queryMerchant = detectMerchantGroup(segmentKnownCjkWords(queryLower))
        val remainingTokens = buildRemainingQueryTokens(queryTokens, queryMerchant)

        // 1. Strict title match always passes
        if (matchesNormalizedText(normalizedTitle, titleTokens, queryMerchant, remainingTokens)) {
            return true
        }

        // 2. Merchant must match when query specifies a merchant
        if (queryMerchant != null) {
            if (!matchesMerchantGroup(segmentedTitle, titleTokens, queryMerchant)) {
                return false
            }
        }
        if (remainingTokens.isEmpty()) {
            return queryMerchant != null
        }

        // 3. Reject any conflicting tier, line, SKU, generation, DC, location, or route
        if (queryMerchant?.canonical == "BWG" &&
            remainingTokens.none { it in BWG_NAMED_PLAN_SKUS } &&
            titleTokens.any { it in BWG_SELF_SUFFICIENT_SKUS }
        ) {
            return false
        }
        if ("mini" in remainingTokens &&
            "pro" !in remainingTokens &&
            MINI_PRO_ADJACENT_REGEX.containsMatchIn(normalizedTitle)
        ) {
            return false
        }
        if (hasConflictingTitleModelTokens(titleTokens, queryMerchant, remainingTokens, titleTokenList)) {
            return false
        }

        // 4. Require the most distinguishing SKU/tier/DC token(s) to be present in title
        val distinguishingSkus = selectMostDistinguishingTokens(remainingTokens, queryMerchant)
        if (distinguishingSkus.isNotEmpty()) {
            return distinguishingSkus.all { tok -> matchesModelToken(segmentedTitle, titleTokens, tok) }
        }

        // 5. Fallback for queries with only location + line/route (e.g. "SPARTAN 西雅图 4837", "DMIT LAX Pro"):
        // require at least one non-empty model token to match (with zero conflicts from step 3).
        return remainingTokens.any { tok -> matchesModelToken(segmentedTitle, titleTokens, tok) }
    }

    private fun selectMostDistinguishingTokens(
        remainingTokens: List<String>,
        queryMerchant: MerchantGroup?,
    ): List<String> {
        // Priority 1: Primary SKU / Server SKU / Specific Plan Tier / Numeric SKU / AWS quota
        val primaryAndTier = remainingTokens.filter { tok ->
            tok in PRIMARY_SKU_TOKENS_LOWER ||
                SERVER_SKU_SHAPE_REGEX.matches(tok) ||
                tok in PLAN_TIER_TOKENS_LOWER ||
                TIER_CODE_SHAPE_REGEX.matches(tok) ||
                (queryMerchant?.canonical == "AWS" && AWS_QUOTA_SHAPE_REGEX.matches(tok))
        }
        if (primaryAndTier.isNotEmpty()) {
            // Keep OVH single-char suffixes (e.g. KS LE B, VPS 1) paired with their anchor
            val anchors = ArrayList<String>(primaryAndTier)
            for (i in remainingTokens.indices) {
                val tok = remainingTokens[i]
                val prev = remainingTokens.getOrNull(i - 1)
                if (tok.length == 1 && prev != null && (prev in SINGLE_CHAR_SUFFIX_PREDECESSORS || prev == "vps")) {
                    if (tok !in anchors) anchors += tok
                }
            }
            return anchors
        }

        // Priority 2: Specific DC/node code (e.g. DC6 in "BWG DC6 CN2 GIA", DC02 in "RACKNERD 黑五 DC02")
        val dcTokens = remainingTokens.filter { DC_LOCATION_SHAPE_REGEX.matches(it) }
        if (dcTokens.isNotEmpty()) {
            return dcTokens
        }

        // Otherwise (only location + line/route or single token): let caller check any-match + zero-conflict
        return emptyList()
    }

    private fun stripSwapAndNegationClauses(text: String): String {
        return text
            .replace(SWAP_OR_NEGATION_BRACKET_REGEX, " ")
            .replace(SWAP_OR_COMPARE_CLAUSE_REGEX, " ")
    }

    private fun hasConflictingTitleModelTokens(
        titleTokens: Set<String>,
        queryMerchant: MerchantGroup?,
        remainingQueryTokens: List<String>,
        titleTokenList: List<String> = emptyList(),
    ): Boolean {
        if (titleTokens.isEmpty() || remainingQueryTokens.isEmpty()) return false
        // 1. Conflicting BWG box SKU in title (e.g. title has MINIBOX while query wants MEGABOX)
        if (queryMerchant?.canonical == "BWG") {
            val querySkus = remainingQueryTokens.filter { it in BWG_NAMED_PLAN_SKUS }.map(::canonicalSynonymKey).toSet()
            val titleSkus = titleTokens.filter { it in BWG_NAMED_PLAN_SKUS }.map(::canonicalSynonymKey).toSet()
            if (querySkus.isNotEmpty() && titleSkus.isNotEmpty() && querySkus.none { it in titleSkus }) {
                return true
            }
        }
        // 2. Conflicting DMIT line (Pro vs EB vs T1) or tier (Wee vs Tiny vs Malibu ...) in title
        if (queryMerchant?.canonical == "DMIT") {
            val queryLines = remainingQueryTokens.filter { it in DMIT_LINE_TOKENS }.toSet()
            val titleLines = titleTokens.filter { it in DMIT_LINE_TOKENS }.toSet()
            if (queryLines.isNotEmpty() && titleLines.isNotEmpty() && queryLines.none { it in titleLines }) {
                return true
            }
            val queryTiers = remainingQueryTokens.filter { it in DMIT_TIER_TOKENS }.map(::canonicalSynonymKey).toSet()
            val titleTiers = titleTokens.filter { it in DMIT_TIER_TOKENS }.map(::canonicalSynonymKey).toSet()
            if (queryTiers.isNotEmpty() && titleTiers.isNotEmpty() && queryTiers.none { it in titleTiers }) {
                return true
            }
        }
        // 3. Conflicting generation token in title (e.g. G9 vs G9.5 vs G11, v1 vs v2)
        val queryGens = remainingQueryTokens.filter { GENERATION_SHAPE_REGEX.matches(it) }.toSet()
        val titleGens = titleTokens.filter { GENERATION_SHAPE_REGEX.matches(it) }.toSet()
        if (queryGens.isNotEmpty() && titleGens.isNotEmpty() && queryGens.none { it in titleGens }) {
            return true
        }
        // 4. Conflicting server SKU code in title (e.g. RS1000 vs RS2000, AX41 vs EX44)
        val queryServerSkus = remainingQueryTokens.filter { SERVER_SKU_SHAPE_REGEX.matches(it) }.toSet()
        val titleServerSkus = titleTokens.filter { SERVER_SKU_SHAPE_REGEX.matches(it) }.toSet()
        if (queryServerSkus.isNotEmpty() && titleServerSkus.isNotEmpty() && queryServerSkus.none { it in titleServerSkus }) {
            return true
        }
        // 5. Conflicting DC/node code in title (e.g. DC6 vs DC9, DC02 vs DC03, HK2 vs JP1)
        val queryDcs = remainingQueryTokens.filter { DC_LOCATION_SHAPE_REGEX.matches(it) }.map(::canonicalSynonymKey).toSet()
        val titleDcs = titleTokens.filter { DC_LOCATION_SHAPE_REGEX.matches(it) }.map(::canonicalSynonymKey).toSet()
        if (queryDcs.isNotEmpty() && titleDcs.isNotEmpty() && queryDcs.none { it in titleDcs }) {
            return true
        }
        // 6. Conflicting geographic location in title (e.g. HKG vs LAX, SG vs HK, Osaka vs Tokyo),
        // allowing broad-region <-> specific-city compatibility (e.g. US <-> LAX, JP <-> Tokyo).
        val queryGeos = dropSupersededBroadRegionStrings(remainingQueryTokens.filter { it in LOCATION_TOKENS_LOWER }.toSet())
        val titleGeos = dropSupersededBroadRegionStrings(titleTokens.filter { it in LOCATION_TOKENS_LOWER }.toSet())
        if (queryGeos.isNotEmpty() && titleGeos.isNotEmpty()) {
            val hasConflict = queryGeos.any { qGeo ->
                titleGeos.none { tGeo -> areGeoLocationsCompatible(qGeo, tGeo) }
            }
            if (hasConflict) return true
        }
        // 7. Conflicting exclusive Japan carrier routes (IIJ vs Softbank)
        val exclusiveRouteTokens = setOf("iij", "softbank", "软银", "sb")
        val queryRoutes = remainingQueryTokens.filter { it in exclusiveRouteTokens }.map(::canonicalSynonymKey).toSet()
        val titleRoutes = titleTokens.filter { it in exclusiveRouteTokens }.map(::canonicalSynonymKey).toSet()
        if (queryRoutes.isNotEmpty() && titleRoutes.isNotEmpty() && queryRoutes.none { it in titleRoutes }) {
            return true
        }
        // 8. Conflicting numeric plan SKUs (e.g. GreenCloud 2222 vs 2525)
        val numericPlanSkus = setOf("2222", "2525", "1111", "3333", "9999")
        val queryNumSkus = remainingQueryTokens.filter { it in numericPlanSkus }.toSet()
        val titleNumSkus = titleTokens.filter { it in numericPlanSkus }.toSet()
        if (queryNumSkus.isNotEmpty() && titleNumSkus.isNotEmpty() && queryNumSkus.none { it in titleNumSkus }) {
            return true
        }
        // 9. Conflicting exclusive plan-tier families (e.g. YxVM Vol/Eco/Hyper, Sharon Lite/Standard, V.PS Mini/Starter)
        val exclusiveTierFamilies = listOf(
            setOf("vol", "eco", "economy", "hyper"),
            setOf("lite", "standard", "basic"),
            setOf("mini", "starter", "micro", "nano"),
        )
        for (family in exclusiveTierFamilies) {
            val qFam = remainingQueryTokens.filter { it in family }.map(::canonicalSynonymKey).toSet()
            val tFam = titleTokens.filter { it in family }.map(::canonicalSynonymKey).toSet()
            if (qFam.isNotEmpty() && tFam.isNotEmpty() && qFam.none { it in tFam }) {
                return true
            }
        }
        // 10. Conflicting single-char SKU suffixes (e.g. OVH KS-LE-B vs KS-LE-C, OVH VPS-1 vs VPS-2)
        if (titleTokenList.size >= 2) {
            for (prefix in listOf("le", "vps")) {
                val qIdx = remainingQueryTokens.indexOf(prefix)
                val qSuffix = remainingQueryTokens.getOrNull(qIdx + 1)?.takeIf { it.length == 1 }
                val tIdx = titleTokenList.indexOf(prefix)
                val tSuffix = titleTokenList.getOrNull(tIdx + 1)?.takeIf { it.length == 1 }
                if (qIdx >= 0 && qSuffix != null && tIdx >= 0 && tSuffix != null && qSuffix != tSuffix) {
                    return true
                }
            }
        }
        return false
    }

    private fun dropSupersededBroadRegionStrings(lowerLocations: Set<String>): Set<String> {
        if (lowerLocations.size <= 1) return lowerLocations
        return lowerLocations.filterNotTo(LinkedHashSet()) { low ->
            BROAD_REGION_SUPERSEDED_BY.any { (broadSet, specifics) ->
                low in broadSet && lowerLocations.any { it in specifics }
            }
        }
    }

    private fun areGeoLocationsCompatible(a: String, b: String): Boolean {
        if (canonicalSynonymKey(a) == canonicalSynonymKey(b)) return true
        return BROAD_REGION_SUPERSEDED_BY.any { (broadSet, specifics) ->
            (a in broadSet && b in specifics) || (b in broadSet && a in specifics)
        }
    }

    private fun buildRemainingQueryTokens(
        queryTokens: List<String>,
        queryMerchant: MerchantGroup?,
    ): List<String> {
        val aliasWordTokens = queryMerchant?.aliases
            ?.flatMap { splitTokens(it) }
            ?.toSet()
            .orEmpty()
        var remainingTokens = if (queryMerchant != null) {
            queryTokens.filterNot { it in queryMerchant.aliases || it in aliasWordTokens }
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
        return remainingTokens
    }

    private fun matchesNormalizedText(
        normalizedText: String,
        tokens: Set<String>,
        queryMerchant: MerchantGroup?,
        remainingTokens: List<String>,
    ): Boolean {
        val segmentedText = segmentKnownCjkWords(normalizedText)
        if (queryMerchant != null) {
            if (!matchesMerchantGroup(segmentedText, tokens, queryMerchant)) {
                return false
            }
        }
        if (remainingTokens.isEmpty()) {
            return queryMerchant != null
        }

        // Strict BWG tier isolation: a generic DC/route query (e.g. "BWG DC6", "BWG DC6 CN2 GIA")
        // must NOT match a distinct named promo SKU (e.g. "BWG MEGABOX-PRO DC6 CN2 GIA-E").
        if (queryMerchant?.canonical == "BWG" &&
            remainingTokens.none { it in BWG_NAMED_PLAN_SKUS } &&
            tokens.any { it in BWG_SELF_SUFFICIENT_SKUS }
        ) {
            return false
        }

        // Strict tier isolation: "Mini" without "Pro" must NOT match "Mini Pro"
        if ("mini" in remainingTokens &&
            "pro" !in remainingTokens &&
            MINI_PRO_ADJACENT_REGEX.containsMatchIn(normalizedText)
        ) {
            return false
        }

        return remainingTokens.all { token ->
            matchesModelToken(segmentedText, tokens, token)
        }
    }

    private fun extractPrimaryBodySnippetForMatching(bodySnippet: String): String {
        val sanitized = bodySnippet
            .replace(FENCED_CODE_BLOCK_REGEX, "\n")
            .replace(MARKDOWN_IMAGE_REGEX, " ")
            .replace(MARKDOWN_LINK_REGEX, "$1")
            .take(800)
        return sanitized.lineSequence()
            .map { it.replace(Regex("""[*_`#>|]"""), " ").trim() }
            .filter { it.isNotEmpty() && !SWAP_OR_COMPARE_LINE_START_REGEX.containsMatchIn(it) }
            .map { stripSwapAndNegationClauses(it).trim() }
            .filter { it.isNotEmpty() }
            .take(8)
            .joinToString(" ")
    }

    fun extractSpread(title: String): ParsedTradeTitleSpread? {
        val rawTrimmed = foldTraditionalToSimplified(title.trim())
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

    private val SKU_RENEWAL_CONSENSUS_TOLERANCE = BigDecimal("0.50")

    @JvmOverloads
    fun analyze(
        query: String,
        candidates: List<TradeCandidateTopic>,
        includeBuyRequests: Boolean = false,
        fxToCny: (String) -> BigDecimal? = TradeListingPriceParser::defaultFxToCny,
        now: LocalDate = VpsValuationEngine.todayInShanghai(),
        excludeTopicId: Long? = null,
        referenceRenewal: ParsedRenewalInfo? = null,
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
                derivedSampleCount = 0,
                askSampleCount = 0,
                askMinCny = null,
                askP25Cny = null,
                askMedianCny = null,
                askP75Cny = null,
                askMaxCny = null,
                askObservations = emptyList(),
                matchedButUnparsedCount = 0,
                matchedUnparsedTopics = emptyList(),
                derivedAskSampleCount = 0,
            )
        }

        // Deduplicate by topicId preferring highest priority source while preserving non-blank bodySnippet.
        val deduped = LinkedHashMap<Long, TradeCandidateTopic>()
        for (candidate in candidates) {
            if (candidate.topicId <= 0L || candidate.title.isBlank()) continue
            if (excludeTopicId != null && candidate.topicId == excludeTopicId) continue
            val existing = deduped[candidate.topicId]
            if (existing == null) {
                deduped[candidate.topicId] = candidate
            } else {
                val candPrio = SOURCE_PRIORITY[candidate.source] ?: 0
                val existPrio = SOURCE_PRIORITY[existing.source] ?: 0
                if (candPrio > existPrio) {
                    val mergedBody = candidate.bodySnippet?.takeIf { it.isNotBlank() }
                        ?: existing.bodySnippet?.takeIf { it.isNotBlank() }
                    deduped[candidate.topicId] = candidate.copy(bodySnippet = mergedBody)
                } else if (existing.bodySnippet.isNullOrBlank() && !candidate.bodySnippet.isNullOrBlank()) {
                    deduped[candidate.topicId] = existing.copy(bodySnippet = candidate.bodySnippet)
                }
            }
        }

        val matchedCandidates = deduped.values.filter {
            matchesQuery(it.title, trimmedQuery, it.bodySnippet)
        }

        // Pass 1: parse matched candidates to filter BUY consistently and detect unambiguous SKU renewal consensus
        val parsedMatched = matchedCandidates.map { candidate ->
            candidate to TradeListingPriceParser.parse(
                title = candidate.title,
                bodySnippet = candidate.bodySnippet,
                fxToCny = fxToCny,
                now = now,
            )
        }
        val eligibleCandidates = parsedMatched.filter { (_, parsed) ->
            includeBuyRequests || parsed.intent != TradeListingIntent.BUY
        }
        val effectiveReferenceRenewal = if (isConcreteSkuQueryForRenewalConsensus(trimmedQuery, tokens)) {
            resolveSkuRenewalConsensus(
                callerReferenceRenewal = referenceRenewal,
                eligibleParsedDetails = eligibleCandidates.map { it.second },
            )
        } else {
            null
        }

        // Pass 2: infer bidirectional spread + total ask using own renewal or unambiguous SKU consensus
        val observations = ArrayList<TradePremiumObservation>()
        val askObservations = ArrayList<TradeAskPriceObservation>()
        val matchedUnparsedTopics = ArrayList<TradeCandidateTopic>()

        for ((candidate, _) in eligibleCandidates) {
            val inferred = TradeListingPriceParser.inferSpread(
                candidate = candidate,
                fxToCny = fxToCny,
                now = now,
                referenceRenewal = effectiveReferenceRenewal,
            )
            if (inferred == null) {
                matchedUnparsedTopics += candidate
                continue
            }
            if (!includeBuyRequests && inferred.intent == TradeListingIntent.BUY) continue

            if (inferred.hasSpread) {
                observations += TradePremiumObservation(
                    topicId = candidate.topicId,
                    title = candidate.title.trim(),
                    premiumDeltaCny = inferred.spreadDeltaCny!!,
                    spreadStatus = inferred.spreadStatus!!,
                    intent = inferred.intent,
                    isClosedSold = inferred.isClosedSold,
                    source = candidate.source,
                    matchedExpression = inferred.matchedExpression,
                    authorName = candidate.authorName,
                    activityHint = candidate.activityHint,
                    askPriceCny = inferred.askPriceCny,
                    residualCny = inferred.residualCny,
                    isDerived = inferred.isDerived,
                    confidence = inferred.confidence,
                    isAskDerived = inferred.isAskDerived,
                )
            }

            if (inferred.hasAskPrice) {
                val askExpr = if (inferred.isAskDerived) {
                    formatDerivedAskExpression(inferred.residualCny, inferred.spreadDeltaCny)
                        ?: inferred.matchedExpression
                } else {
                    inferred.parsedDetails.askPrice?.matchedExpression ?: inferred.matchedExpression
                }
                askObservations += TradeAskPriceObservation(
                    topicId = candidate.topicId,
                    title = candidate.title.trim(),
                    askPriceCny = inferred.askPriceCny!!,
                    intent = inferred.intent,
                    isClosedSold = inferred.isClosedSold,
                    source = candidate.source,
                    matchedExpression = askExpr,
                    authorName = candidate.authorName,
                    activityHint = candidate.activityHint,
                    residualCny = inferred.residualCny,
                    inferredSpreadCny = inferred.spreadDeltaCny,
                    isAskDerived = inferred.isAskDerived,
                )
            }

            if (!inferred.hasSpread && !inferred.hasAskPrice) {
                matchedUnparsedTopics += candidate
            }
        }

        val sortedValues = observations.map { it.premiumDeltaCny }.sorted()
        val sampleCount = sortedValues.size
        val localCount = observations.count { it.source != MarketDataSource.REMOTE_SEARCH }
        val remoteCount = observations.count { it.source == MarketDataSource.REMOTE_SEARCH }
        val derivedCount = observations.count { it.isDerived }

        val sortedAsks = askObservations.map { it.askPriceCny }.sorted()
        val derivedAskCount = askObservations.count { it.isAskDerived }
        val sortedUnparsed = matchedUnparsedTopics.sortedByDescending { it.topicId }

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
            derivedSampleCount = derivedCount,
            askSampleCount = sortedAsks.size,
            askMinCny = sortedAsks.firstOrNull(),
            askP25Cny = linearQuantile(sortedAsks, 0.25),
            askMedianCny = linearQuantile(sortedAsks, 0.50),
            askP75Cny = linearQuantile(sortedAsks, 0.75),
            askMaxCny = sortedAsks.lastOrNull(),
            askObservations = askObservations.sortedByDescending { it.topicId },
            matchedButUnparsedCount = sortedUnparsed.size,
            matchedUnparsedTopics = sortedUnparsed,
            derivedAskSampleCount = derivedAskCount,
        )
    }

    private fun isConcreteSkuQueryForRenewalConsensus(
        trimmedQuery: String,
        queryTokens: List<String>,
    ): Boolean {
        val queryLower = normalizeProductCodes(
            normalizeWidthAndCase(trimmedQuery)
                .replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " ")
                .replace(SCRUB_URL_REGEX, " ")
                .replace(SCRUB_BARE_HOSTNAME_REGEX, " ")
                .replace(SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX, " "),
        ).lowercase(Locale.ROOT)
        val queryMerchant = detectMerchantGroup(segmentKnownCjkWords(queryLower)) ?: return false
        val remainingTokens = buildRemainingQueryTokens(queryTokens, queryMerchant)
        if (remainingTokens.isEmpty()) return false
        return remainingTokens.size >= 2 || selectMostDistinguishingTokens(remainingTokens, queryMerchant).isNotEmpty()
    }

    private fun resolveSkuRenewalConsensus(
        callerReferenceRenewal: ParsedRenewalInfo?,
        eligibleParsedDetails: List<ParsedListingDetails>,
    ): ParsedRenewalInfo? {
        val explicitPeerRenewals = eligibleParsedDetails.mapNotNull { details ->
            details.renewal?.takeIf { it.cycle != null && it.cycleExplicit }
        }
        val validCallerRef = callerReferenceRenewal?.takeIf { it.cycle != null && it.cycleExplicit }

        if (validCallerRef != null) {
            if (explicitPeerRenewals.isEmpty()) return validCallerRef
            val agreeing = explicitPeerRenewals.count { peer ->
                peer.currency == validCallerRef.currency &&
                    peer.cycle == validCallerRef.cycle &&
                    peer.amount.subtract(validCallerRef.amount).abs() <= SKU_RENEWAL_CONSENSUS_TOLERANCE
            }
            // Accept caller reference renewal unless peer explicit renewals contradict it
            val agreedEnough = agreeing == explicitPeerRenewals.size ||
                (explicitPeerRenewals.size >= 3 && agreeing * 4 >= explicitPeerRenewals.size * 3)
            return if (agreedEnough) validCallerRef else null
        }

        if (explicitPeerRenewals.size < 2) return null

        val grouped = explicitPeerRenewals.groupBy { it.currency to it.cycle!! }
        val dominantEntry = grouped.entries.maxByOrNull { it.value.size } ?: return null
        val dominantList = dominantEntry.value
        if (dominantList.size < 2) return null

        // Require unanimity (or >=80% with no competing >=2-peer cluster)
        val hasCompetingCluster = grouped.entries.any { it.key != dominantEntry.key && it.value.size >= 2 }
        if (hasCompetingCluster) return null
        if (dominantList.size < explicitPeerRenewals.size &&
            !(explicitPeerRenewals.size >= 4 && dominantList.size * 5 >= explicitPeerRenewals.size * 4)
        ) {
            return null
        }

        val sortedAmounts = dominantList.map { it.amount }.sorted()
        val medianAmount = sortedAmounts[sortedAmounts.size / 2]
        val tightMatches = sortedAmounts.filter {
            it.subtract(medianAmount).abs() <= SKU_RENEWAL_CONSENSUS_TOLERANCE
        }
        val tightEnough = tightMatches.size >= 2 && (
            tightMatches.size == sortedAmounts.size ||
                (sortedAmounts.size >= 4 && tightMatches.size * 5 >= sortedAmounts.size * 4)
            )
        if (!tightEnough) return null

        return ParsedRenewalInfo(
            amount = medianAmount.setScale(2, RoundingMode.HALF_UP),
            currency = dominantEntry.key.first,
            cycle = dominantEntry.key.second,
            matchedExpression = "同款共识续费",
            cycleExplicit = true,
        )
    }

    private fun formatDerivedAskExpression(
        residualCny: BigDecimal?,
        spreadDeltaCny: BigDecimal?,
    ): String? {
        if (residualCny == null || spreadDeltaCny == null) return null
        val resText = formatCompactCny(residualCny)
        return when {
            spreadDeltaCny.compareTo(BigDecimal.ZERO) > 0 ->
                "推算总价: 残值¥$resText + 溢价¥${formatCompactCny(spreadDeltaCny)}"
            spreadDeltaCny.compareTo(BigDecimal.ZERO) < 0 ->
                "推算总价: 残值¥$resText − 折价¥${formatCompactCny(spreadDeltaCny.abs())}"
            else ->
                "推算总价: 残值¥$resText（平出）"
        }
    }

    private fun formatCompactCny(value: BigDecimal): String {
        val scaled = value.setScale(2, RoundingMode.HALF_UP)
        val stripped = scaled.stripTrailingZeros()
        return if (stripped.scale() <= 0) {
            stripped.setScale(0, RoundingMode.HALF_UP).toPlainString()
        } else {
            stripped.toPlainString()
        }
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

        val rawLines = sanitizedBody.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(18)
            .toList()

        if (rawLines.isEmpty()) {
            return ExtractedQueryCandidate("", null, emptyList(), false)
        }

        // Extract labeled merchant & model values from markdown tables (`| label | value |`) and `label: value` lines.
        var labeledMerchant: MerchantGroup? = null
        val labeledModelValues = ArrayList<String>()
        val cleanNonSwapLines = ArrayList<String>(rawLines.size)

        for (rawLine in rawLines) {
            val foldedLine = foldTraditionalToSimplified(rawLine)
            if (foldedLine.contains('|')) {
                val cells = foldedLine.split('|')
                    .map { it.replace(MARKDOWN_DECOR_REGEX, " ").trim() }
                    .filter { it.isNotEmpty() }
                val isDivider = cells.all { cell -> cell.all { ch -> ch == '-' || ch == ':' || ch == '=' } }
                if (!isDivider && cells.size >= 2) {
                    val key = cells[0]
                    val value = cells[1]
                    if (TABLE_MERCHANT_KEY_REGEX.matches(key) && labeledMerchant == null) {
                        labeledMerchant = detectMerchantFromRawText(value)
                    } else if (TABLE_MODEL_KEY_REGEX.matches(key)) {
                        labeledModelValues += value
                    }
                    cleanNonSwapLines += cells.joinToString(" ")
                }
                continue
            }

            val cleanedLine = foldedLine
                .replace(MARKDOWN_DECOR_REGEX, " ")
                .trim()
                .replace(LEADING_LIST_MARKER_REGEX, "")
                .trim()
            if (cleanedLine.isEmpty()) continue
            if (SWAP_OR_COMPARE_LINE_START_REGEX.containsMatchIn(cleanedLine)) continue
            val primaryLine = stripSwapAndNegationClauses(cleanedLine).trim()
            if (primaryLine.isEmpty()) continue
            cleanNonSwapLines += primaryLine

            FALLBACK_MERCHANT_LABEL_REGEX.find(primaryLine)?.let { m ->
                if (labeledMerchant == null) {
                    labeledMerchant = detectMerchantFromRawText(m.groupValues[1].trim())
                }
            }
            FALLBACK_MODEL_LABEL_REGEX.find(primaryLine)?.let { m ->
                val value = m.groupValues[1].trim()
                if (value.isNotEmpty()) {
                    labeledModelValues += value
                }
            }
        }

        val effectiveMerchant = preferredMerchant ?: labeledMerchant

        // Pass 1: Explicitly labeled model/plan lines & markdown table rows
        for (value in labeledModelValues) {
            val direct = extractQueryFromText(value, requireMerchant = true)
            if (direct.merchant != null && direct.modelTokenCount > 0 && !direct.isMultiVendorBundle) {
                return direct
            }
            if (effectiveMerchant != null && direct.merchant == null) {
                val withMerchant = extractQueryFromText(
                    rawText = "${effectiveMerchant.canonical} $value",
                    requireMerchant = true,
                )
                if (withMerchant.modelTokenCount > 0 && !withMerchant.isMultiVendorBundle) {
                    return withMerchant
                }
            }
        }

        // Detect multi-vendor inventory lists in body when title had no merchant (e.g. "清库存 出三台小鸡").
        if (effectiveMerchant == null && cleanNonSwapLines.size >= 2) {
            val distinctBodyMerchants = LinkedHashSet<String>()
            for (line in cleanNonSwapLines) {
                val occ = findExplicitMerchantOccurrences(
                    segmentKnownCjkWords(
                        normalizeProductCodes(
                            normalizeWidthAndCase(line).replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " "),
                        ).lowercase(Locale.ROOT),
                    ),
                )
                occ.forEach { distinctBodyMerchants += it.group.canonical }
            }
            if (distinctBodyMerchants.size >= 2) {
                return ExtractedQueryCandidate("", null, emptyList(), false, isMultiVendorBundle = true)
            }
        }

        // Pass 2: Lines mentioning an explicit merchant, preferring effectiveMerchant and richer model tokens
        var best: ExtractedQueryCandidate? = null
        for (line in cleanNonSwapLines) {
            val candidate = extractQueryFromText(line, requireMerchant = true)
            if (candidate.isMultiVendorBundle || candidate.query.isBlank() || candidate.merchant == null) continue
            val samePreferred = effectiveMerchant != null &&
                candidate.merchant.canonical == effectiveMerchant.canonical
            if (samePreferred && candidate.modelTokenCount > 0 && !candidate.usedSpecFallback) {
                return candidate
            }
            val currentBest = best
            if (currentBest == null || isBetterFallbackCandidate(candidate, currentBest, effectiveMerchant)) {
                best = candidate
            }
        }
        if (best != null && best.modelTokenCount > 0) {
            return best
        }

        // Pass 3: When merchant is known from title/label, enrich from leading unlabeled product lines
        if (effectiveMerchant != null) {
            for (line in cleanNonSwapLines.take(3)) {
                val lineExplicit = detectMerchantFromRawText(line)
                if (lineExplicit != null && lineExplicit.canonical != effectiveMerchant.canonical) continue
                val leadingClause = line.substringBefore('。').trim().ifEmpty { line }
                val candidate = extractQueryFromText(
                    rawText = "${effectiveMerchant.canonical} $leadingClause",
                    requireMerchant = true,
                )
                if (!candidate.isMultiVendorBundle && candidate.modelTokenCount > 0 && !candidate.usedSpecFallback) {
                    return candidate
                }
            }
        }

        return best ?: ExtractedQueryCandidate("", null, emptyList(), false)
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
            return ExtractedQueryCandidate("", null, emptyList(), false)
        }

        // Strip swap/negation/comparison clauses (e.g. "（不换EB）", "，或者换瓦工megabox") so only the primary product is parsed
        val primaryText = stripSwapAndNegationClauses(widthNorm)

        // Capture normalized CPU+RAM spec tiers before scrubbing (fallback when no specific model/region exists).
        val capturedSpecs = CPU_RAM_SPEC_CAPTURE_REGEX.findAll(primaryText)
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

        // Step 1: Scrub URLs, bare hostnames, emails, IPs, Xianyu marketplace phrases, specs, dates, and join product codes BEFORE year scrubbers.
        val preJoined = primaryText
            .replace(SUGGEST_STRIP_STATUS_BRACKETS_REGEX, " , ")
            .replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " , ")
            .replace(SCRUB_FREE_PUSH_REGEX, " , ")
            .replace(SCRUB_URL_REGEX, " , ")
            .replace(SCRUB_BARE_HOSTNAME_REGEX, " , ")
            .replace(SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX, " , ")
            .replace(SCRUB_EMAIL_REGEX, " , ")
            .replace(SCRUB_IPV4_CIDR_REGEX, " , ")
            .replace(SCRUB_CPU_RAM_REGEX, " , ")
            .replace(SCRUB_BANDWIDTH_REGEX, " , ")
            .replace(SCRUB_STORAGE_MEM_WITH_UNIT_REGEX, " , ")
            .replace(SCRUB_FULL_DATE_REGEX, " , ")
            .replace(SCRUB_MONTH_DAY_REGEX, " , ")
            .replace(SCRUB_MONTH_EXPIRY_REGEX, " , ")
            .replace(SCRUB_DOT_DATE_CONTEXT_REGEX, " , ")
            .replace(SCRUB_REMAINING_DAYS_REGEX, " , ")

        val productNormalized = normalizeProductCodes(preJoined)

        val scrubbed = productNormalized
            .replace(SCRUB_YEAR_TOKEN_FOR_QUERY_REGEX, " , ")
            .replace(SCRUB_PORT_REGEX, " , ")
            .replace(SCRUB_PERCENT_REGEX, " , ")
            .replace(SCRUB_ZH_DISCOUNT_RATE_REGEX, " , ")
            .replace(SCRUB_FOREIGN_CURRENCY_PREFIX_REGEX, " , ")
            .replace(SCRUB_FOREIGN_CURRENCY_SUFFIX_REGEX, " , ")
            .replace(SCRUB_TOTAL_ASK_AFTER_WORD_REGEX) { match ->
                val amount = match.groupValues[1].toBigDecimalOrNull()
                if (amount != null && amount > MAX_DIRECT_AFTER_CHU_SPREAD) " , " else match.value
            }
            .replace(EXPLICIT_PREMIUM_WORD_REGEX, " , ")
            .replace(EXPLICIT_DISCOUNT_WORD_REGEX, " , ")
            .replace(EXPLICIT_REMAINING_PLUS_REGEX, " , ")
            .replace(EXPLICIT_REMAINING_COLON_REGEX, " , ")
            .replace(EXPLICIT_REMAINING_MINUS_REGEX, " , ")
            .replace(STANDALONE_SIGNED_BEFORE_CHU_REGEX, " , ")
            .replace(EXPLICIT_PAR_REGEX, " , ")
            .replace(SCRUB_CNY_PRICE_FOR_QUERY_REGEX, " , ")
            .replace(SCRUB_BARE_BUYOUT_FOR_QUERY_REGEX) { match ->
                val num = match.groupValues[1]
                if (num in KNOWN_NUMERIC_MODEL_TOKENS) num else " , "
            }
            .replace(SCRUB_BILLING_SLASH_REGEX, " , ")

        // Step 2: Segment CJK entities and detect merchant(s) by earliest/longest span match (with bounded fuzzy fallback)
        val segmented = segmentKnownCjkWords(scrubbed)
        val lowerSegmented = segmented.lowercase(Locale.ROOT)
        val explicitOccurrences = findExplicitMerchantOccurrences(lowerSegmented)
        val distinctExplicitCanonicals = explicitOccurrences.map { it.group.canonical }.distinct()

        if (distinctExplicitCanonicals.size >= 2) {
            return ExtractedQueryCandidate("", null, emptyList(), false, isMultiVendorBundle = true)
        }

        val fuzzyMerchantOcc = if (explicitOccurrences.isEmpty()) {
            findFuzzyMerchantOccurrence(lowerSegmented)
        } else {
            null
        }

        val detectedMerchant: MerchantGroup? = explicitOccurrences.firstOrNull()?.group
            ?: fuzzyMerchantOcc?.group
            ?: findImpliedMerchantOccurrence(lowerSegmented)?.group

        if (requireMerchant && detectedMerchant == null) {
            return ExtractedQueryCandidate("", null, emptyList(), false)
        }

        // Remove explicit/fuzzy merchant spans (including multi-word suffixes like "Spartan Host", "Claw Cloud")
        val spansToMask = when {
            explicitOccurrences.isNotEmpty() -> explicitOccurrences
            fuzzyMerchantOcc != null -> listOf(fuzzyMerchantOcc)
            else -> emptyList()
        }
        val withoutMerchantSpans = if (spansToMask.isNotEmpty()) {
            maskSpansWithSpaces(segmented, spansToMask)
        } else {
            segmented
        }

        // Step 3: Positive allow-list + shape rules + bounded fuzzy SKU resolution
        val rawTokens = splitTokens(withoutMerchantSpans)
        val tagged = ArrayList<TaggedModelToken>(rawTokens.size)
        var prevCanonicalLow: String? = null
        for (i in rawTokens.indices) {
            val tok = rawTokens[i]
            val low = tok.lowercase(Locale.ROOT)
            val prevLow = prevCanonicalLow ?: rawTokens.getOrNull(i - 1)?.lowercase(Locale.ROOT)
            val nextTok = rawTokens.getOrNull(i + 1)

            // Special compound: OVH VPS-1 .. VPS-9
            if (low == "vps" && isSingleDigitToken(nextTok)) {
                tagged += TaggedModelToken("VPS", TokenRole.PRIMARY_SKU, i)
                prevCanonicalLow = "vps"
                continue
            }
            if (isSingleDigitToken(tok) && prevLow == "vps") {
                tagged += TaggedModelToken(tok, TokenRole.PRIMARY_SKU, i)
                prevCanonicalLow = tok
                continue
            }

            // Single-char suffix right after KS / LE / Tier / Gen / Plan (e.g. OVH KS-LE-B)
            if (tok.length == 1 && tok[0].isLetterOrDigit() && tok[0].code < 128) {
                if (prevLow in SINGLE_CHAR_SUFFIX_PREDECESSORS) {
                    tagged += TaggedModelToken(tok, TokenRole.PRIMARY_SKU, i)
                    prevCanonicalLow = low
                } else {
                    prevCanonicalLow = null
                }
                continue
            }

            if (low in QUERY_NOISE_TOKENS) {
                prevCanonicalLow = null
                continue
            }
            if (detectedMerchant != null && low in detectedMerchant.aliases) {
                prevCanonicalLow = null
                continue
            }

            val exactRole = classifyProductTokenRole(
                token = tok,
                lower = low,
                prevLower = prevLow,
                merchant = detectedMerchant,
            )
            if (exactRole != null) {
                tagged += TaggedModelToken(tok, exactRole, i)
                prevCanonicalLow = low
                continue
            }

            // Bounded fuzzy SKU fallback (pure ASCII alphabetic >= 5 chars, Damerau distance <= 1)
            val fuzzySku = resolveFuzzySkuEntry(low)
            if (fuzzySku != null) {
                tagged += TaggedModelToken(fuzzySku.displayText, fuzzySku.role, i)
                if (fuzzySku.lowerKey.endsWith("pro") && fuzzySku.lowerKey in BWG_PRO_IMPLIED_SKUS) {
                    tagged += TaggedModelToken("PRO", TokenRole.PRIMARY_SKU, i)
                    prevCanonicalLow = "pro"
                } else {
                    prevCanonicalLow = fuzzySku.displayText.lowercase(Locale.ROOT)
                }
            } else {
                prevCanonicalLow = null
            }
        }

        // Step 4: Deduplicate synonyms, drop superseded broad regions, and order cleanly
        val deduped = deduplicateTaggedTokens(tagged)
        val refined = dropSupersededBroadRegionTags(deduped)
        val ordered = refined.sortedWith(compareBy<TaggedModelToken> { it.role.sortOrder }.thenBy { it.originalIndex })

        val pickedModels = ArrayList<String>(4)
        var usedSpecFallback = false
        var isSelfSufficientSku = false

        // BandwagonHost self-sufficient named SKUs (e.g. MEGABOX-PRO, BIGGERBOX-PRO, MINIBOX, SAKURABOX)
        val bwgSkuIdx = if (detectedMerchant?.canonical == "BWG") {
            ordered.indexOfFirst { it.text.lowercase(Locale.ROOT) in BWG_SELF_SUFFICIENT_SKUS }
        } else {
            -1
        }

        if (bwgSkuIdx >= 0) {
            isSelfSufficientSku = true
            pickedModels += ordered[bwgSkuIdx].text
            val nextTok = ordered.getOrNull(bwgSkuIdx + 1)
            if (nextTok != null && nextTok.text.equals("pro", ignoreCase = true)) {
                pickedModels += nextTok.text
            }
        } else {
            val maxModelTokens = 4
            for (item in ordered) {
                if (pickedModels.none { it.equals(item.text, ignoreCase = true) }) {
                    pickedModels += item.text
                }
                if (pickedModels.size >= maxModelTokens) break
            }
        }

        // Fallback to vendor + hardware spec tier ONLY when a merchant is known and no product/region exists.
        if (detectedMerchant != null && pickedModels.isEmpty() && capturedSpecs.isNotEmpty()) {
            pickedModels += capturedSpecs.first()
            usedSpecFallback = true
        }

        val fullParts = buildList {
            if (detectedMerchant != null) add(detectedMerchant.canonical)
            addAll(pickedModels)
        }

        return ExtractedQueryCandidate(
            query = fullParts.joinToString(" ").trim(),
            merchant = detectedMerchant,
            modelTokens = if (usedSpecFallback) emptyList() else pickedModels,
            usedSpecFallback = usedSpecFallback,
            isSelfSufficientSku = isSelfSufficientSku,
        )
    }

    private fun classifyProductTokenRole(
        token: String,
        lower: String,
        prevLower: String?,
        merchant: MerchantGroup?,
    ): TokenRole? {
        // Keep "Mini Pro" glued in PLAN role and "MEGABOX PRO" / "BIGGERBOX PRO" in PRIMARY_SKU role
        if (lower == "pro") {
            if (prevLower in BWG_PRO_IMPLIED_SKUS) return TokenRole.PRIMARY_SKU
            if (prevLower == "mini") return TokenRole.PLAN
            return TokenRole.LINE
        }
        if (lower in PRIMARY_SKU_TOKENS_LOWER) return TokenRole.PRIMARY_SKU
        if (SERVER_SKU_SHAPE_REGEX.matches(token)) return TokenRole.PRIMARY_SKU
        if (merchant?.canonical == "AWS" && AWS_QUOTA_SHAPE_REGEX.matches(token)) return TokenRole.PRIMARY_SKU
        if (lower in EDITION_TOKENS_LOWER) return TokenRole.EDITION
        if (lower in LOCATION_TOKENS_LOWER || DC_LOCATION_SHAPE_REGEX.matches(token)) return TokenRole.LOCATION
        if (lower in LINE_ROUTE_TOKENS_LOWER) return TokenRole.LINE
        if (lower in PLAN_TIER_TOKENS_LOWER || TIER_CODE_SHAPE_REGEX.matches(token)) return TokenRole.PLAN
        if (GENERATION_SHAPE_REGEX.matches(token)) return TokenRole.GENERATION
        return null
    }

    private fun isLocationToken(lower: String): Boolean =
        lower in LOCATION_TOKENS_LOWER || DC_LOCATION_SHAPE_REGEX.matches(lower)

    private fun isSingleDigitToken(token: String?): Boolean =
        token != null && token.length == 1 && token[0] in '1'..'9'

    private fun deduplicateTaggedTokens(tokens: List<TaggedModelToken>): List<TaggedModelToken> {
        if (tokens.size <= 1) return tokens
        val result = ArrayList<TaggedModelToken>(tokens.size)
        for (item in tokens) {
            val low = item.text.lowercase(Locale.ROOT)
            val synGroup = MODEL_SYNONYM_GROUPS.firstOrNull { low in it }
            val existingIdx = result.indexOfFirst { existing ->
                val exLow = existing.text.lowercase(Locale.ROOT)
                exLow == low || (synGroup != null && exLow in synGroup)
            }
            if (existingIdx < 0) {
                result += item
            } else {
                // When both a CJK alias and an ASCII code for the exact same entity appear in one title
                // (e.g. "美西 LAX.Pro.WEE"), prefer the concise ASCII token.
                val existingIsAscii = result[existingIdx].text.all { it.code < 128 }
                val currentIsAscii = item.text.all { it.code < 128 }
                if (!existingIsAscii && currentIsAscii) {
                    result[existingIdx] = item.copy(originalIndex = result[existingIdx].originalIndex)
                }
            }
        }
        return result
    }

    private fun dropSupersededBroadRegionTags(tokens: List<TaggedModelToken>): List<TaggedModelToken> {
        if (tokens.size <= 1) return tokens
        val lowerSet = tokens.map { it.text.lowercase(Locale.ROOT) }.toSet()
        return tokens.filterNot { item ->
            val low = item.text.lowercase(Locale.ROOT)
            BROAD_REGION_SUPERSEDED_BY.any { (broadSet, specifics) ->
                low in broadSet && lowerSet.any { it in specifics }
            }
        }
    }

    private fun isStrictSemanticSuperset(
        candidateTokens: List<String>,
        baseTokens: List<String>,
    ): Boolean {
        if (baseTokens.isEmpty() || candidateTokens.isEmpty()) return false
        val candLow = candidateTokens.map { it.lowercase(Locale.ROOT) }
        val candCanon = candLow.map(::canonicalSynonymKey).toSet()
        val baseLow = baseTokens.map { it.lowercase(Locale.ROOT) }
        val baseCanon = baseLow.map(::canonicalSynonymKey).toSet()

        var refinedBroadRegion = false
        for (b in baseLow) {
            val bCanon = canonicalSynonymKey(b)
            if (bCanon in candCanon) continue
            val isRefined = candLow.any { c -> isBroadRegionRefinedBy(b, c) }
            if (!isRefined) return false
            refinedBroadRegion = true
        }
        return candCanon.size > baseCanon.size || refinedBroadRegion
    }

    private fun isBroadRegionRefinedBy(broadLower: String, specificLower: String): Boolean {
        return BROAD_REGION_SUPERSEDED_BY.any { (broadSet, specifics) ->
            broadLower in broadSet && specificLower in specifics
        }
    }

    private fun canonicalSynonymKey(token: String): String {
        val low = token.lowercase(Locale.ROOT)
        val synGroup = MODEL_SYNONYM_GROUPS.firstOrNull { low in it }
        return synGroup?.first() ?: low
    }

    private fun extractNonNoiseTokensPreservingCase(segmented: String): List<String> {
        val rawTokens = splitTokens(segmented)
        val result = ArrayList<String>(rawTokens.size)
        for (i in rawTokens.indices) {
            val tok = rawTokens[i]
            val low = tok.lowercase(Locale.ROOT)
            if (low == "vps" && isSingleDigitToken(rawTokens.getOrNull(i + 1))) {
                result += tok
                continue
            }
            if (isSingleDigitToken(tok) && rawTokens.getOrNull(i - 1)?.lowercase(Locale.ROOT) == "vps") {
                result += tok
                continue
            }
            if (low in QUERY_NOISE_TOKENS) continue
            result += tok
        }
        return result
    }

    private fun normalizeProductCodes(text: String): String {
        return text
            .replace(NORM_PVM_PREFIX_REGEX, "")
            .replace(NORM_DOUBLE_ELEVEN_REGEX, "双十一")
            .replace(NORM_DC_CODE_REGEX) { m -> "DC${m.groupValues[1]}" }
            .replace(NORM_HYPHEN_MODEL_NUM_REGEX) { m -> "${m.groupValues[1]}${m.groupValues[2]}" }
            .replace(NORM_NETCUP_VPS_NUM_REGEX) { m -> "${m.groupValues[1]}${m.groupValues[2]}" }
            .replace(NORM_GLUED_DC_ROUTE_REGEX) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
            .replace(NORM_GLUED_CN2_GIA_REGEX) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
            .replace(NORM_GLUED_ROUTE_NUM_SKU_REGEX) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
            .replace(NORM_GLUED_DMIT_TRIPLE_REGEX) { m -> "${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}" }
            .replace(NORM_GLUED_DMIT_PAIR_REGEX) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
            .replace(NORM_GLUED_SERVER_GEN_REGEX) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
            .replace(NORM_GIA_E_REGEX, "GIA")
            .replace(NORM_MEGABOX_PRO_REGEX, "MEGABOX PRO")
            .replace(NORM_MEGABOX_SPACED_REGEX, "MEGABOX")
            .replace(NORM_BIGGERBOX_PRO_REGEX, "BIGGERBOX PRO")
            .replace(NORM_BIGGERBOX_SPACED_REGEX, "BIGGERBOX")
            .replace(NORM_OTHER_BOX_SPACED_REGEX) { m -> "${m.groupValues[1].uppercase(Locale.ROOT)}BOX" }
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

    private fun detectMerchantFromRawText(rawText: String): MerchantGroup? {
        val norm = segmentKnownCjkWords(
            normalizeProductCodes(
                normalizeWidthAndCase(rawText)
                    .replace(SCRUB_XIANYU_MARKETPLACE_REGEX, " ")
                    .replace(SCRUB_URL_REGEX, " ")
                    .replace(SCRUB_BARE_HOSTNAME_REGEX, " ")
                    .replace(SCRUB_REVIEW_CCTLD_HOSTNAME_REGEX, " "),
            ).lowercase(Locale.ROOT),
        )
        return detectMerchantGroup(norm)
    }

    private fun detectMerchantGroup(normalizedLowerText: String): MerchantGroup? {
        findExplicitMerchantOccurrences(normalizedLowerText).firstOrNull()?.let { return it.group }
        findFuzzyMerchantOccurrence(normalizedLowerText)?.let { return it.group }
        return findImpliedMerchantOccurrence(normalizedLowerText)?.group
    }

    private fun detectExplicitMerchantGroup(normalizedLowerText: String): MerchantGroup? =
        findExplicitMerchantOccurrences(normalizedLowerText).firstOrNull()?.group
            ?: findFuzzyMerchantOccurrence(normalizedLowerText)?.group

    /**
     * Finds all non-overlapping explicit merchant occurrences ordered by earliest position in text,
     * resolving overlaps by preferring earliest start and longest alias span (e.g. `阿里爪` beats `阿里`,
     * `spartan host` beats `spartan`).
     */
    private fun findExplicitMerchantOccurrences(normalizedLowerText: String): List<MerchantOccurrence> {
        if (normalizedLowerText.isBlank()) return emptyList()
        val rawMatches = ArrayList<MerchantOccurrence>()
        for ((group, alias) in MERCHANT_ALIAS_ENTRIES) {
            findAliasSpans(normalizedLowerText, alias).forEach { (start, end) ->
                rawMatches += MerchantOccurrence(group, start, end, isImplied = false)
            }
        }
        if (rawMatches.isEmpty()) return emptyList()

        // Sort by start ASC, length DESC so longest match at the earliest index wins
        rawMatches.sortWith(compareBy<MerchantOccurrence> { it.start }.thenByDescending { it.end - it.start })
        val accepted = ArrayList<MerchantOccurrence>(rawMatches.size)
        for (cand in rawMatches) {
            val overlaps = accepted.any { existing -> cand.start < existing.end && cand.end > existing.start }
            if (!overlaps) {
                accepted += cand
            }
        }
        return accepted
    }

    /**
     * Bounded fuzzy merchant fallback when no exact explicit merchant alias matched.
     * Matches pure ASCII alphabetic words only (never digit-bearing codes):
     * - len >= 5 against alias len >= 5: Damerau-Levenshtein distance <= 1 with first-char/first-swap guard
     * - len == 4 against alias len == 4: adjacent transposition only (e.g. `dimt` -> `dmit`)
     * Rejects common English words, noise words, known SKU tokens, and ambiguous multi-merchant matches.
     */
    private fun findFuzzyMerchantOccurrence(normalizedLowerText: String): MerchantOccurrence? {
        if (normalizedLowerText.isBlank()) return null
        val len = normalizedLowerText.length
        var i = 0
        while (i < len) {
            if (normalizedLowerText[i] !in 'a'..'z') {
                i++
                continue
            }
            val start = i
            while (i < len && isAsciiIdentifierChar(normalizedLowerText[i])) {
                i++
            }
            val end = i
            // Skip tokens preceded/followed by '.' (hostnames/domains) or containing digits
            val leftChar = if (start > 0) normalizedLowerText[start - 1] else null
            val rightChar = if (end < len) normalizedLowerText[end] else null
            if (leftChar == '.' || rightChar == '.') continue

            val word = normalizedLowerText.substring(start, end)
            if (!isEligibleForFuzzyMatching(word, minLen = 4)) continue
            if (word in FUZZY_SKU_LOWER_SET) continue

            var matchedGroup: MerchantGroup? = null
            var ambiguous = false
            for ((group, alias) in FUZZY_MERCHANT_ALPHA_ENTRIES) {
                if (fuzzyMatchesWord(word, alias, allowFourCharTransposition = true)) {
                    if (matchedGroup == null) {
                        matchedGroup = group
                    } else if (matchedGroup.canonical != group.canonical) {
                        ambiguous = true
                        break
                    }
                }
            }
            if (matchedGroup != null && !ambiguous) {
                return MerchantOccurrence(matchedGroup, start, end, isImplied = false)
            }
        }
        return null
    }

    private fun findImpliedMerchantOccurrence(normalizedLowerText: String): MerchantOccurrence? {
        var best: MerchantOccurrence? = null
        for ((group, alias) in MERCHANT_IMPLIED_ENTRIES) {
            val span = findAliasSpans(normalizedLowerText, alias).firstOrNull() ?: continue
            val occ = MerchantOccurrence(group, span.first, span.second, isImplied = true)
            if (best == null || occ.start < best.start) {
                best = occ
            }
        }
        if (best != null) return best

        // Bounded fuzzy fallback for implied ASCII sub-brands/SKUs >= 5 chars (e.g. `megbox` -> BWG, `lightsal` -> AWS)
        val tokens = splitTokens(normalizedLowerText)
        for (tok in tokens) {
            if (!isEligibleForFuzzyMatching(tok, minLen = 5)) continue
            var matchedGroup: MerchantGroup? = null
            var ambiguous = false
            for ((group, alias) in MERCHANT_IMPLIED_ENTRIES) {
                if (alias.length >= 5 && alias.all { it in 'a'..'z' } &&
                    fuzzyMatchesWord(tok, alias, allowFourCharTransposition = false)
                ) {
                    if (matchedGroup == null) {
                        matchedGroup = group
                    } else if (matchedGroup.canonical != group.canonical) {
                        ambiguous = true
                        break
                    }
                }
            }
            if (matchedGroup != null && !ambiguous) {
                return MerchantOccurrence(matchedGroup, 0, 0, isImplied = true)
            }
        }
        return null
    }

    private fun resolveFuzzySkuEntry(lowerToken: String): FuzzySkuEntry? {
        if (!isEligibleForFuzzyMatching(lowerToken, minLen = 5)) return null
        var matched: FuzzySkuEntry? = null
        for (entry in FUZZY_SKU_ENTRIES) {
            if (fuzzyMatchesWord(lowerToken, entry.lowerKey, allowFourCharTransposition = false)) {
                if (matched == null) {
                    matched = entry
                } else if (canonicalSynonymKey(matched.displayText) != canonicalSynonymKey(entry.displayText)) {
                    return null // Ambiguous match across distinct SKUs
                }
            }
        }
        return matched
    }

    private fun isEligibleForFuzzyMatching(word: String, minLen: Int): Boolean {
        if (word.length < minLen) return false
        for (k in word.indices) {
            if (word[k] !in 'a'..'z') return false
        }
        if (word in QUERY_NOISE_TOKENS || word in FUZZY_ENGLISH_WORD_GUARD) return false
        if (word in LOCATION_TOKENS_LOWER || word in LINE_ROUTE_TOKENS_LOWER ||
            word in PRIMARY_SKU_TOKENS_LOWER || word in PLAN_TIER_TOKENS_LOWER ||
            word in EDITION_TOKENS_LOWER
        ) {
            return false
        }
        return true
    }

    private fun fuzzyMatchesWord(
        candidate: String,
        target: String,
        allowFourCharTransposition: Boolean,
    ): Boolean {
        if (candidate == target) return false
        val lc = candidate.length
        val lt = target.length
        if (lc == 4 && lt == 4) {
            return allowFourCharTransposition && isAdjacentTranspositionOnly(candidate, target)
        }
        // Allow 5-char doubled-letter typo of a 4-char vendor alias (e.g. `dmitt` -> `dmit`)
        if (allowFourCharTransposition && lc == 5 && lt == 4) {
            return isSingleDoubledLetterOf(candidate, target)
        }
        if (lc < 5 || lt < 5) return false
        // Require matching first letter OR adjacent transposition of the first two letters
        val firstOk = candidate[0] == target[0] ||
            (candidate[0] == target[1] && candidate[1] == target[0])
        if (!firstOk) return false
        return damerauDistanceAtMostOne(candidate, target)
    }

    private fun isSingleDoubledLetterOf(longer: String, shorter: String): Boolean {
        if (longer.length != shorter.length + 1) return false
        for (i in 0 until longer.length - 1) {
            if (longer[i] == longer[i + 1]) {
                var matches = true
                for (k in 0 until i) {
                    if (longer[k] != shorter[k]) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    for (k in i until shorter.length) {
                        if (longer[k + 1] != shorter[k]) {
                            matches = false
                            break
                        }
                    }
                }
                if (matches) return true
            }
        }
        return false
    }

    private fun isAdjacentTranspositionOnly(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var i = 0
        val n = a.length
        while (i < n && a[i] == b[i]) i++
        if (i >= n - 1) return false
        if (a[i] != b[i + 1] || a[i + 1] != b[i]) return false
        for (k in i + 2 until n) {
            if (a[k] != b[k]) return false
        }
        return true
    }

    /**
     * Zero-allocation Optimal String Alignment / Damerau-Levenshtein distance <= 1 check
     * (single insertion, deletion, substitution, or adjacent transposition).
     */
    private fun damerauDistanceAtMostOne(a: String, b: String): Boolean {
        val la = a.length
        val lb = b.length
        val diff = la - lb
        if (diff < -1 || diff > 1) return false
        var i = 0
        val minLen = minOf(la, lb)
        while (i < minLen && a[i] == b[i]) i++
        if (i == minLen) return true // trailing insert/delete or identical

        return when (diff) {
            0 -> {
                // Single substitution at i
                var subOk = true
                for (k in i + 1 until la) {
                    if (a[k] != b[k]) {
                        subOk = false
                        break
                    }
                }
                if (subOk) return true
                // Adjacent transposition at (i, i+1)
                if (i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i]) {
                    for (k in i + 2 until la) {
                        if (a[k] != b[k]) return false
                    }
                    return true
                }
                false
            }
            1 -> {
                // Single extra char in a at index i
                for (k in i until lb) {
                    if (a[k + 1] != b[k]) return false
                }
                true
            }
            else -> {
                // Single extra char in b at index i
                for (k in i until la) {
                    if (a[k] != b[k + 1]) return false
                }
                true
            }
        }
    }

    private fun findAliasSpans(normalizedLowerText: String, alias: String): List<Pair<Int, Int>> {
        if (alias.isEmpty()) return emptyList()
        val isAsciiOnly = alias.all { it.code < 128 }
        val spans = ArrayList<Pair<Int, Int>>(2)
        if (!isAsciiOnly) {
            val startsAsciiIdent = isAsciiIdentifierChar(alias.first())
            val endsAsciiIdent = isAsciiIdentifierChar(alias.last())
            var idx = normalizedLowerText.indexOf(alias)
            while (idx >= 0) {
                val end = idx + alias.length
                val leftOk = !startsAsciiIdent || idx == 0 || !isAsciiIdentifierChar(normalizedLowerText[idx - 1])
                val rightOk = !endsAsciiIdent || end >= normalizedLowerText.length || !isAsciiIdentifierChar(normalizedLowerText[end])
                if (leftOk && rightOk) {
                    spans += idx to end
                    idx = normalizedLowerText.indexOf(alias, end)
                } else {
                    idx = normalizedLowerText.indexOf(alias, idx + 1)
                }
            }
            return spans
        }

        val rejectPrecedingDot = alias.length <= 2 && alias.all { it in 'a'..'z' }
        val aliasParts = if (' ' in alias) alias.split(' ') else null
        if (aliasParts != null) {
            // Multi-word ASCII alias (e.g. "spartan host", "claw cloud"): tolerate multiple whitespace between words
            val pattern = Regex(
                aliasParts.joinToString("""\s+""") { Regex.escape(it) },
            )
            pattern.findAll(normalizedLowerText).forEach { m ->
                val start = m.range.first
                val end = m.range.last + 1
                val leftOk = start == 0 || !isAsciiIdentifierChar(normalizedLowerText[start - 1])
                val rightOk = end >= normalizedLowerText.length || !isAsciiIdentifierChar(normalizedLowerText[end])
                if (leftOk && rightOk) {
                    spans += start to end
                }
            }
            return spans
        }

        var startIndex = 0
        val needleStartsWithDigit = alias.first() in '0'..'9'
        val needleEndsWithDigit = alias.last() in '0'..'9'
        while (startIndex <= normalizedLowerText.length - alias.length) {
            val found = normalizedLowerText.indexOf(alias, startIndex = startIndex)
            if (found < 0) break
            val end = found + alias.length
            val leftChar = if (found > 0) normalizedLowerText[found - 1] else null
            var leftOk = leftChar == null || !isAsciiIdentifierChar(leftChar)
            if (leftOk && rejectPrecedingDot && leftChar == '.') {
                leftOk = false
            }
            if (leftOk && needleStartsWithDigit && leftChar == '.' && found >= 2 && isAsciiIdentifierChar(normalizedLowerText[found - 2])) {
                leftOk = false
            }
            val rightChar = if (end < normalizedLowerText.length) normalizedLowerText[end] else null
            var rightOk = rightChar == null || !isAsciiIdentifierChar(rightChar)
            if (rightOk && needleEndsWithDigit && rightChar == '.' && end + 1 < normalizedLowerText.length && normalizedLowerText[end + 1] in '0'..'9') {
                rightOk = false
            }
            if (leftOk && rightOk) {
                val isEnglishDo = alias == "do" && (
                    ENGLISH_DO_PREDECESSOR_REGEX.containsMatchIn(normalizedLowerText.substring(0, found)) ||
                        ENGLISH_DO_SUCCESSOR_REGEX.containsMatchIn(normalizedLowerText.substring(end))
                    )
                if (!isEnglishDo) {
                    spans += found to end
                    startIndex = end
                } else {
                    startIndex = found + 1
                }
            } else {
                startIndex = found + 1
            }
        }
        return spans
    }

    private fun maskSpansWithSpaces(text: String, spans: List<MerchantOccurrence>): String {
        val chars = text.toCharArray()
        for (span in spans) {
            val from = span.start.coerceIn(0, chars.size)
            val to = span.end.coerceIn(from, chars.size)
            for (i in from until to) {
                chars[i] = ' '
            }
        }
        return String(chars)
    }

    private fun matchesMerchantGroup(
        normalizedLowerText: String,
        splitTokens: Set<String>,
        group: MerchantGroup,
    ): Boolean {
        val explicitMatches = findExplicitMerchantOccurrences(normalizedLowerText)
        if (explicitMatches.any { it.group.canonical == group.canonical }) {
            return true
        }
        if (explicitMatches.isNotEmpty()) {
            return false
        }
        val fuzzyMatch = findFuzzyMerchantOccurrence(normalizedLowerText)
        if (fuzzyMatch != null) {
            return fuzzyMatch.group.canonical == group.canonical
        }
        if (group.impliedAliases.isEmpty()) return false
        return group.impliedAliases.any { alias ->
            matchesSingleAliasOrToken(normalizedLowerText, splitTokens, alias) ||
                (alias.length >= 5 && alias.all { it in 'a'..'z' } && matchesFuzzySkuToken(splitTokens, alias))
        }
    }

    private fun matchesModelToken(
        normalizedTitle: String,
        titleTokens: Set<String>,
        token: String,
    ): Boolean {
        val synonymSet = MODEL_SYNONYM_GROUPS.firstOrNull { token in it }
        if (synonymSet != null) {
            if (synonymSet.any { variant -> matchesSingleAliasOrToken(normalizedTitle, titleTokens, variant) }) {
                return true
            }
            return synonymSet.any { variant ->
                variant in FUZZY_SKU_LOWER_SET && matchesFuzzySkuToken(titleTokens, variant)
            }
        }
        if (matchesSingleAliasOrToken(normalizedTitle, titleTokens, token)) {
            return true
        }
        return token in FUZZY_SKU_LOWER_SET && matchesFuzzySkuToken(titleTokens, token)
    }

    private fun matchesFuzzySkuToken(titleTokens: Set<String>, targetLower: String): Boolean {
        if (targetLower.length < 5) return false
        for (cand in titleTokens) {
            if (isEligibleForFuzzyMatching(cand, minLen = 5) &&
                fuzzyMatchesWord(cand, targetLower, allowFourCharTransposition = false)
            ) {
                // Ensure candidate unambiguously resolves to this SKU/synonym family
                val resolved = resolveFuzzySkuEntry(cand)
                if (resolved != null && canonicalSynonymKey(resolved.displayText) == canonicalSynonymKey(targetLower)) {
                    return true
                }
            }
        }
        return false
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
