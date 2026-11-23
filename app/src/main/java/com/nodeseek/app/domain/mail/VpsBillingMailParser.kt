package com.nodeseek.app.domain.mail

import androidx.compose.runtime.Immutable
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Classification of WHMCS and common VPS provider lifecycle emails.
 */
enum class VpsMailType(
    val labelZh: String,
    val shortBadgeZh: String,
) {
    PROVISIONING("VPS 开通邮件", "VPS 开通邮件"),
    RENEWAL("续费确认", "续费确认"),
    INVOICE("账单邮件", "账单邮件"),
}

/**
 * Lightweight hint extracted from [SeekMailSummary] (`subject` + `snippet` + sender) for subtle
 * tagging in the mail list without fetching full email bodies.
 */
@Immutable
data class VpsMailSummaryHint(
    val mailType: VpsMailType,
    val providerName: String = "",
    val primaryIp: String? = null,
    val formattedPrice: String? = null,
)

/**
 * Structured VPS provisioning, renewal, or invoice telemetry parsed from plain-text and/or HTML mail.
 */
@Immutable
data class ParsedVpsBillingMail(
    val mailType: VpsMailType,
    val providerName: String = "",
    val productName: String = "",
    val hostname: String = "",
    val mainIpv4: String? = null,
    val mainIpv6: String? = null,
    val sshPort: Int = DEFAULT_SSH_PORT,
    val sshUsername: String = DEFAULT_SSH_USERNAME,
    val sshPassword: String? = null,
    val billingAmount: String? = null,
    val currency: ValuationCurrency = ValuationCurrency.USD,
    val billingCycle: BillingCyclePreset? = null,
    val nextDueDateIso: String? = null,
    val invoiceNumber: String? = null,
    val specsSummary: String = "",
    val monthlyTrafficQuotaGb: Double? = null,
    val region: String = "",
    val panelUrl: String = "",
) {
    val primaryHost: String?
        get() = mainIpv4
            ?.takeIf { it.isNotBlank() }
            ?: mainIpv6?.takeIf { it.isNotBlank() }
            ?: hostname.takeIf { it.isNotBlank() }

    val hasSshCredentials: Boolean
        get() = !primaryHost.isNullOrBlank() && !sshPassword.isNullOrBlank()

    val formattedPrice: String?
        get() {
            val amount = billingAmount?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return "${currency.symbol}$amount ${currency.code}"
        }

    val formattedPriceAndCycle: String?
        get() {
            val price = formattedPrice
            val cycleLabel = billingCycle?.shortLabel
            return when {
                price != null && cycleLabel != null -> "$price / $cycleLabel"
                price != null -> price
                cycleLabel != null -> cycleLabel
                else -> null
            }
        }

    companion object {
        const val DEFAULT_SSH_PORT: Int = 22
        const val DEFAULT_SSH_USERNAME: String = "root"
    }
}

/**
 * Pure-Kotlin WHMCS & common VPS provider email parser supporting both English and Chinese
 * provisioning (`PROVISIONING`), renewal/payment confirmation (`RENEWAL`), and unpaid invoice (`INVOICE`)
 * templates across plain-text and HTML (`<br>`, `<p>`, `<tr><td>`, `<li>`) bodies.
 */
object VpsBillingMailParser {

    private val BLOCK_NEWLINE_TAGS = setOf(
        "p", "div", "section", "article", "header", "footer", "aside", "main",
        "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "ul", "ol",
        "li", "tr", "table", "hr", "dd", "dt", "dl",
    )

    private val INLINE_SPACE_REGEX = Regex("""[ \t\u000B\u000C\u00A0]+""")
    private val MULTI_NEWLINE_REGEX = Regex("""\n{3,}""")
    private val HTML_TAG_HINT_REGEX = Regex("""<\s*(?:br|p|div|tr|td|th|li|table|html|body|span|strong|b)\b""", RegexOption.IGNORE_CASE)

    // ── Classification Patterns ──────────────────────────────────────────────

    private val PROVISIONING_SUBJECT_STRONG_REGEX = Regex(
        pattern = buildString {
            append("(?:")
            append("new\\s+(?:virtual\\s+|cloud\\s+|dedicated\\s+|vps\\s+)?server\\s+information")
            append("|(?:vps|virtual\\s+server|dedicated\\s+server|cloud\\s+server|dedicated/cloud\\s+server|vps/server|server\\s+account)\\s+information")
            append("|new\\s+(?:vps|account|instance|service)\\s+information")
            append("|your\\s+(?:new\\s+)?(?:vps|server|virtual\\s+server|cloud\\s+server|instance)\\s+(?:is\\s+ready|has\\s+been\\s+(?:created|deployed|provisioned|activated|set\\s*up))")
            append("|(?:service|server|vps)\\s+(?:activation|activated|provisioned|deployment)")
            append("|产品开通|服务器开通|服务器开通通知|云主机开通|云服务器开通|vps\\s*开通|实例开通|机器已开通|服务已开通|新服务器信息|产品激活通知|主机开通通知")
            append(")")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val PROVISIONING_BODY_SIGNAL_REGEX = Regex(
        pattern = buildString {
            append("(?:")
            append("new\\s+(?:account|server|vps)\\s+information")
            append("|your\\s+(?:new\\s+)?(?:virtual\\s+|cloud\\s+)?server\\s+has\\s+(?:now\\s+)?been\\s+(?:set\\s*up|created|deployed|provisioned|activated)")
            append("|(?:root|ssh|initial|system)\\s*password")
            append("|control\\s+panel\\s+url|solusvm|virtualizor|virtfusion|proxmox")
            append("|您的(?:产品|服务器|云主机|实例|机器|服务)已(?:成功)?(?:开通|激活|创建|部署)")
            append("|(?:root|ssh|初始|系统|远程|管理员)\\s*密码")
            append(")")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val RENEWAL_STRONG_REGEX = Regex(
        pattern = buildString {
            append("(?:")
            append("invoice\\s+payment\\s+confirmation")
            append("|payment\\s+confirmation")
            append("|payment\\s+receipt")
            append("|this\\s+is\\s+a\\s+payment\\s+receipt\\s+for\\s+invoice")
            append("|invoice\\s+(?:#?\\s*[A-Za-z0-9-]+\\s+)?(?:has\\s+been\\s+)?paid")
            append("|receipt\\s+for\\s+your\\s+payment")
            append("|service\\s+renewal\\s+confirmation|renewal\\s+confirmation|has\\s+been\\s+renewed\\s+successfully")
            append("|续费成功|已成功续费|续费确认|账单已支付|账单支付成功|付款确认|支付成功|收款确认|订单已支付")
            append(")")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val PAID_STATUS_LINE_REGEX = Regex(
        """(?:^|\n)\s*(?:invoice\s+status|payment\s+status|status|账单状态|支付状态|付款状态)\s*[:：]\s*(?:paid|completed|已支付|已付款|支付成功)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val UNPAID_INVOICE_SIGNAL_REGEX = Regex(
        pattern = buildString {
            append("(?:")
            append("unpaid\\s+invoice|invoice\\s+generated|customer\\s+invoice|new\\s+invoice")
            append("|invoice\\s+payment\\s+reminder|invoice\\s+overdue|overdue\\s+notice")
            append("|an\\s+invoice\\s+has\\s+been\\s+generated")
            append("|upcoming\\s+renewal|renewal\\s+reminder|renewal\\s+invoice|expiring\\s+soon")
            append("|(?:invoice\\s+status|status)\\s*[:：]\\s*unpaid")
            append("|账单通知|新账单|待支付账单|待付账单|未付账单|未支付账单|账单已生成|续费账单|续费提醒|到期提醒|即将到期|请及时续费")
            append("|(?:账单状态|支付状态)\\s*[:：]\\s*(?:未支付|待支付|未付款)")
            append(")")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val VPS_DOMAIN_OR_BODY_CONTEXT_REGEX = Regex(
        pattern = buildString {
            append("(?:")
            append("\\bvps\\b|\\bkvm\\b|\\blxc\\b|\\bopenvz\\b|\\bvm\\b|\\bdedicated\\b|\\bserver\\b|\\bcloud\\b|\\binstance\\b|\\bnode\\b")
            append("|\\bwhmcs\\b|\\bsolusvm\\b|\\bvirtualizor\\b|\\bvirtfusion\\b|\\bproxmox\\b|\\bhostbill\\b|\\bblesta\\b")
            append("|\\bssh\\b|\\bipv4\\b|\\bipv6\\b|\\bhostname\\b|\\broot\\s+password\\b|\\bmain\\s+ip\\b")
            append("|\\bvcpu\\b|\\bnvme\\b|\\bbandwidth\\b|\\bbgp\\b|\\bcn2\\b|\\bcera\\b")
            append("|服务器|云主机|云服务器|轻量云|独立服务器|实例|节点|机房|带宽|流量|续费|账单|主机名|公网\\s*ip")
            append(")")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val PURE_OTP_SUBJECT_REGEX = Regex(
        """^(?:your\s+)?(?:verification|security|login|sign[\s-]?in|confirmation|one[-\s]?time)\s+code\b|验证码|登录确认码|动态口令""",
        RegexOption.IGNORE_CASE,
    )

    // ── Known Providers & Ignored Generic Domains ────────────────────────────

    private val KNOWN_PROVIDER_DOMAINS: Map<String, String> = mapOf(
        "racknerd.com" to "RackNerd",
        "dmit.io" to "DMIT",
        "bwh81.net" to "BandwagonHost",
        "bwh88.net" to "BandwagonHost",
        "bwh89.net" to "BandwagonHost",
        "64clouds.com" to "BandwagonHost",
        "bandwagonhost.com" to "BandwagonHost",
        "claw.cloud" to "ClawCloud",
        "clawcloud.com" to "ClawCloud",
        "greencloudvps.com" to "GreenCloud",
        "v.ps" to "V.PS",
        "xtom.com" to "xTOM",
        "cloudcone.com" to "CloudCone",
        "cloudcone.email" to "CloudCone",
        "bagevm.com" to "BageVM",
        "yxvm.com" to "YxVM",
        "akile.io" to "AkileCloud",
        "akile.cn" to "AkileCloud",
        "misaka.io" to "Misaka",
        "hosthatch.com" to "HostHatch",
        "buyvm.net" to "BuyVM",
        "frantech.ca" to "BuyVM",
        "spartanhost.net" to "SpartanHost",
        "spartanhost.org" to "SpartanHost",
        "virtono.com" to "Virtono",
        "hetzner.com" to "Hetzner",
        "netcup.de" to "Netcup",
        "ovhcloud.com" to "OVHcloud",
        "vultr.com" to "Vultr",
        "linode.com" to "Linode",
        "digitalocean.com" to "DigitalOcean",
        "contabo.com" to "Contabo",
        "rn-mail.com" to "RackNerd",
        "alice.ws" to "Alice Networks",
        "sharon.io" to "Sharon",
        "wawo.wiki" to "Wawo",
    )

    private val GENERIC_MAIL_DOMAINS = setOf(
        "seek.li", "nodeseek.org", "nodeseek.com", "gmail.com", "googlemail.com",
        "outlook.com", "hotmail.com", "live.com", "yahoo.com", "icloud.com",
        "qq.com", "163.com", "126.com", "foxmail.com", "proton.me", "protonmail.com",
        "amazonses.com", "sendgrid.net", "mailgun.org", "postmarkapp.com", "example.com", "example.org",
    )

    private val GENERIC_SENDER_NAMES = setOf(
        "billing", "support", "sales", "admin", "administrator", "noreply", "no-reply",
        "whmcs", "customer service", "client area", "notification", "notifications", "mailer",
        "财务", "财务部", "财务中心", "客户中心", "客服", "系统通知", "邮件通知", "账单中心", "未知发件人",
    )

    private val SENDER_ROLE_SUFFIX_REGEX = Regex(
        """(?:\s*[-–—|·]\s*|\s+)(?:billing|support|sales|team|cloud\s+billing|client\s+area|customer\s+care|notifications?|财务中心|财务部|财务|客服中心|客户中心|技术支持|账单中心|官方团队|团队)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    // ── Field Extraction Patterns ────────────────────────────────────────────

    private val EXCLUDED_IP_LINE_LABEL_REGEX = Regex(
        """(?:gateway|netmask|subnet\s*mask|broadcast|nameserver|name\s*server|\bns\d*\b|\bdns\d*\b|resolver|网关|子网掩码|掩码|广播地址|域名服务器)""",
        RegexOption.IGNORE_CASE,
    )

    private val LABELED_IPV4_REGEX = Regex(
        pattern = buildString {
            append("(?:^|\\n)\\s*[•*\\-]?\\s*")
            append("(?:main\\s+ip(?:\\s+address)?|primary\\s+ip(?:\\s+address)?|ip\\s+address|ipv4(?:\\s+address)?|server\\s+ip|dedicated\\s+ip|public\\s+ip|ssh\\s+ip|host\\s+ip")
            append("|主\\s*ip(?:\\s*地址)?|公网\\s*ip(?:\\s*地址)?|服务器\\s*ip|独立\\s*ip|管理\\s*ip|ipv4\\s*地址|ip\\s*地址|\\bip\\b)")
            append("\\s*[:：=\\t]\\s*([0-9]{1,3}(?:\\.[0-9]{1,3}){3})(?:/\\d{1,2})?(?::(\\d{2,5}))?")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val ANY_IPV4_CANDIDATE_REGEX = Regex(
        """(?<![0-9.])([0-9]{1,3}(?:\.[0-9]{1,3}){3})(?:/(\d{1,2}))?(?::(\d{2,5}))?(?![0-9.])""",
    )

    private val LABELED_IPV6_REGEX = Regex(
        pattern = buildString {
            append("(?:^|\\n)\\s*[•*\\-]?\\s*")
            append("(?:main\\s+ipv6|primary\\s+ipv6|ipv6\\s+address|ipv6\\s+subnet|assigned\\s+ipv6|ipv6\\s*地址|ipv6)")
            append("\\s*[:：=\\t]\\s*\\[?([0-9a-fA-F:]{3,45})]?\\s*(?:/\\d{1,3})?")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val ANY_IPV6_CANDIDATE_REGEX = Regex(
        """(?<![0-9a-fA-F:])((?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}|(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4})(?:/\d{1,3})?(?![0-9a-fA-F:])""",
    )

    private val EXPLICIT_SSH_PORT_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:ssh\s*port|remote\s*port|ssh\s*端口|远程端口|连接端口|登录端口)\s*[:：=\t]\s*(\d{2,5})\b""",
        RegexOption.IGNORE_CASE,
    )

    private val GENERIC_PORT_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:port|端口|端口号)\s*[:：=\t]\s*(\d{2,5})\b""",
        RegexOption.IGNORE_CASE,
    )

    private val SSH_CLI_COMMAND_REGEX = Regex(
        """\bssh\s+(?:-p\s*(\d{2,5})\s+)?([a-zA-Z_][a-zA-Z0-9._-]{0,30})@([A-Za-z0-9.:-]+)(?:\s+-p\s*(\d{2,5}))?""",
        RegexOption.IGNORE_CASE,
    )

    private val PANEL_CONTEXT_PREFIX_REGEX = Regex(
        """(?:control\s*panel|client\s*area|solusvm|virtualizor|virtfusion|proxmox|vnc|ipmi|billing\s*panel|面板|控制面板|客户中心|财务面板|后台)""",
        RegexOption.IGNORE_CASE,
    )

    private val EXPLICIT_ROOT_OR_SSH_PASSWORD_REGEX = Regex(
        pattern = buildString {
            append("(?:^|\\n)\\s*[•*\\-]?\\s*")
            append("(?:root\\s*(?:/\\s*admin\\s*)?password|ssh\\s*password|server\\s*password|system\\s*password|initial\\s*password|login\\s*password|admin(?:istrator)?\\s*password")
            append("|root\\s*密码|ssh\\s*密码|服务器密码|系统密码|初始密码|远程密码|登录密码|管理员密码|默认密码)")
            append("\\s*[:：=\\t]\\s*([^\\r\\n]+)")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val GENERIC_PASSWORD_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*([^\r\n:：=\t]{0,32}?(?:password|密码))\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val EXPLICIT_SSH_USERNAME_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:ssh\s*user(?:name)?|system\s*user(?:name)?|login\s*user(?:name)?|root\s*user(?:name)?|ssh\s*用户名|系统账号|系统用户名|登录账号|登录用户名|远程账号|默认账号)\s*[:：=\t]\s*([A-Za-z_][A-Za-z0-9._-]{0,31})\b""",
        RegexOption.IGNORE_CASE,
    )

    private val GENERIC_USERNAME_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*([^\r\n:：=\t]{0,32}?(?:username|user\s*name|user|用户名|登录名|账号))\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val PRODUCT_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:product\s*/\s*service|product|package|plan|service\s*plan|vps\s*plan|server\s*plan|套餐名称|套餐|产品/服务|产品名称|服务名称|商品名称|实例规格|配置方案|产品)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val HOSTNAME_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:server\s*hostname|host\s*name|hostname|server\s*name|node\s*name|主机名|服务器名称|实例名称)\s*[:：=\t]\s*([A-Za-z0-9][A-Za-z0-9._-]{1,120})""",
        RegexOption.IGNORE_CASE,
    )

    private val PANEL_URL_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:control\s*panel(?:\s*url)?|management\s*url|panel\s*url|控制面板(?:地址|链接)?|管理面板(?:地址|链接)?|面板地址)\s*[:：=\t]\s*(https?://\S+)""",
        RegexOption.IGNORE_CASE,
    )

    private val INVOICE_NUMBER_REGEX = Regex(
        pattern = buildString {
            append("(?:")
            append("invoice\\s*(?:#|no\\.?|number|id)?\\s*[:：#]?\\s*#?([A-Za-z0-9][A-Za-z0-9-]{1,24})")
            append("|账单(?:编号|号|ID)?\\s*[:：#]?\\s*#?([A-Za-z0-9][A-Za-z0-9-]{1,24})")
            append(")")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private val BLOCKED_INVOICE_TOKENS = setOf(
        "PAYMENT", "CONFIRMATION", "GENERATED", "REMINDER", "NOTICE", "OVERDUE",
        "PAID", "UNPAID", "STATUS", "DATE", "DUE", "TOTAL", "ITEMS", "SUMMARY",
        "DETAILS", "RECEIPT", "FOR", "HAS", "BEEN", "IS", "THE",
    )

    private val RECURRING_AMOUNT_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:recurring(?:\s+amount)?|renewal\s+(?:amount|price)|续费(?:金额|价格)|循环(?:金额|价格)|周期(?:金额|价格))\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val GENERAL_AMOUNT_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:first\s+payment\s+amount|total\s+paid|amount\s+paid|amount\s+due|total\s+due|invoice\s+total|order\s+total|sub\s*total|total|amount|price|实付金额|已付金额|应付金额|账单金额|订单金额|付款金额|支付金额|总计|合计|价格|费用|金额)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val BILLING_CYCLE_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:billing\s*cycle|payment\s*cycle|renewal\s*cycle|cycle|计费周期|付款周期|账单周期|续费周期|周期)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val EXPLICIT_EXPIRY_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:next\s+due\s+date|new\s+(?:due|expiry|expiration)\s+date|expiry\s+date|expiration\s+date|service\s+(?:end|expiry)\s+date|valid\s+until|renewal\s+date|下次到期(?:日期|时间|日)?|下次(?:付款|账单)日期|新到期(?:日期|时间|日)?|到期(?:日期|时间|日)|有效期至|服务到期(?:时间|日)?)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val GENERIC_DUE_DATE_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:due\s+date|payment\s+due(?:\s+date)?|应付日期|账单到期(?:日|日期|时间)?|付款截止(?:日|日期)?)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    // Matches WHMCS invoice item lines like:
    // "2GB KVM VPS - Special (vps1.example.com) (03/15/2026 - 03/15/2027) $10.99 USD"
    // or "LAX.Pro.MALIBU (lax.example.org - 154.17.22.88) (2026-03-15 - 2027-03-15) $49.90 USD"
    private val WHMCS_PERIOD_RANGE_REGEX = Regex(
        """[(（]\s*([0-9A-Za-z/\-.,\s年月日至]{6,24}?)\s*(?:[-–—~]|至|\bto\b)\s*([0-9A-Za-z/\-.,\s年月日]{6,24}?)\s*[)）]""",
        RegexOption.IGNORE_CASE,
    )

    private val LOCATION_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:location|datacenter|data\s*center|region|node\s*location|机房|数据中心|地区|地域|节点位置)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val CPU_SPEC_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:v?cpu(?:\s*cores?)?|cores?|processor|处理器|核心数?|CPU)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val RAM_SPEC_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:memory|ram|guaranteed\s*ram|内存)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val DISK_SPEC_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:disk(?:\s*space)?|storage|ssd|nvme|硬盘|磁盘|存储)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAFFIC_SPEC_LINE_REGEX = Regex(
        """(?:^|\n)\s*[•*\-]?\s*(?:bandwidth|monthly\s*traffic|data\s*transfer|traffic|月流量|流量(?:配额)?|带宽流量)\s*[:：=\t]\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val INLINE_SPEC_TOKEN_REGEX = Regex(
        """\b(\d+(?:\.\d+)?)\s*(v?CPU|Cores?|C|GB\s*RAM|MB\s*RAM|GB\s*NVMe|GB\s*SSD|TB\s*BW|TB|GB|MB)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val TRAFFIC_AMOUNT_REGEX = Regex(
        """(\d+(?:\.\d+)?)\s*(TB|TiB|GB|GiB)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val SUBJECT_BRACKET_PROVIDER_REGEX = Regex(
        """^\s*(?:\[([^\[\]]{2,32})]|【([^【】]{2,32})】)\s*""",
    )
    private val SUBJECT_PREFIX_PROVIDER_REGEX = Regex(
        """^\s*([A-Za-z0-9][A-Za-z0-9._\s]{1,26}?)\s+[-–—:]\s+(?:new\s+server|vps|dedicated|cloud|invoice|payment|customer\s+invoice|service|产品|服务器|账单|续费)""",
        RegexOption.IGNORE_CASE,
    )
    private val SIGNATURE_PROVIDER_REGEX = Regex(
        """(?:regards|sincerely|thank\s+you\s+for\s+choosing|best\s+regards|此致|感谢选择)\s*[,\n:：]?\s*\n?\s*([A-Za-z0-9\u4e00-\u9fa5][A-Za-z0-9\u4e00-\u9fa5._\-\s]{1,30})""",
        RegexOption.IGNORE_CASE,
    )

    private val REGION_KEYWORDS: List<Pair<String, String>> = listOf(
        "香港" to "香港",
        "Hong Kong" to "香港",
        "HKG" to "香港",
        "HK" to "香港",
        "东京" to "东京",
        "Tokyo" to "东京",
        "NRT" to "东京",
        "TYO" to "东京",
        "大阪" to "大阪",
        "Osaka" to "大阪",
        "KIX" to "大阪",
        "日本" to "日本",
        "Japan" to "日本",
        "JP" to "日本",
        "新加坡" to "新加坡",
        "Singapore" to "新加坡",
        "SGP" to "新加坡",
        "SIN" to "新加坡",
        "SG" to "新加坡",
        "洛杉矶" to "洛杉矶",
        "Los Angeles" to "洛杉矶",
        "LAX" to "洛杉矶",
        "圣何塞" to "圣何塞",
        "San Jose" to "圣何塞",
        "SJC" to "圣何塞",
        "西雅图" to "西雅图",
        "Seattle" to "西雅图",
        "纽约" to "纽约",
        "New York" to "纽约",
        "美西" to "美西",
        "美国" to "美国",
        "USA" to "美国",
        "法兰克福" to "法兰克福",
        "Frankfurt" to "法兰克福",
        "FRA" to "法兰克福",
        "德国" to "德国",
        "Germany" to "德国",
        "阿姆斯特丹" to "荷兰",
        "Amsterdam" to "荷兰",
        "AMS" to "荷兰",
        "荷兰" to "荷兰",
        "伦敦" to "英国",
        "London" to "英国",
        "英国" to "英国",
        "首尔" to "韩国",
        "Seoul" to "韩国",
        "韩国" to "韩国",
        "台北" to "台湾",
        "Taipei" to "台湾",
        "台湾" to "台湾",
    )

    private val ENGLISH_MONTHS: Map<String, Month> = mapOf(
        "jan" to Month.JANUARY, "january" to Month.JANUARY,
        "feb" to Month.FEBRUARY, "february" to Month.FEBRUARY,
        "mar" to Month.MARCH, "march" to Month.MARCH,
        "apr" to Month.APRIL, "april" to Month.APRIL,
        "may" to Month.MAY,
        "jun" to Month.JUNE, "june" to Month.JUNE,
        "jul" to Month.JULY, "july" to Month.JULY,
        "aug" to Month.AUGUST, "august" to Month.AUGUST,
        "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER, "september" to Month.SEPTEMBER,
        "oct" to Month.OCTOBER, "october" to Month.OCTOBER,
        "nov" to Month.NOVEMBER, "november" to Month.NOVEMBER,
        "dec" to Month.DECEMBER, "december" to Month.DECEMBER,
    )

    // ── Public Entry Points ──────────────────────────────────────────────────

    /**
     * Converts HTML email content into clean line-oriented text, preserving line breaks from
     * `<br>`, `<p>`, `<div>`, `<tr>`, `<li>`, headings, and joining `<td>`/`<th>` key-value table
     * cells with `: ` when appropriate so adjacent fields never glue together.
     */
    fun htmlToStructuredLines(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val document = Jsoup.parse(rawHtml)
        document.select("script, style, head, noscript").remove()
        val body = document.body()

        val sb = StringBuilder(rawHtml.length.coerceAtMost(16_384))
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is TextNode -> {
                            val normalized = node.wholeText
                                .replace("\r\n", "\n")
                                .replace('\r', '\n')
                            if (normalized.contains('\n')) {
                                val parts = normalized.split('\n')
                                parts.forEachIndexed { idx, part ->
                                    val clean = part.replace(INLINE_SPACE_REGEX, " ")
                                    if (clean.isNotBlank()) {
                                        sb.append(clean)
                                    }
                                    if (idx < parts.lastIndex) {
                                        ensureNewline(sb)
                                    }
                                }
                            } else {
                                val collapsed = normalized.replace(INLINE_SPACE_REGEX, " ")
                                if (collapsed.isNotBlank()) {
                                    sb.append(collapsed)
                                } else if (sb.isNotEmpty() && !sb.last().isWhitespace()) {
                                    sb.append(' ')
                                }
                            }
                        }

                        is Element -> {
                            val tag = node.tagName().lowercase(Locale.ROOT)
                            when {
                                tag == "br" -> sb.append('\n')
                                tag == "li" -> {
                                    ensureNewline(sb)
                                    sb.append("• ")
                                }
                                tag in BLOCK_NEWLINE_TAGS -> ensureNewline(sb)
                            }
                        }
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node !is Element) return
                    val tag = node.tagName().lowercase(Locale.ROOT)
                    when {
                        tag == "td" || tag == "th" -> {
                            val nextCell = node.nextElementSibling()
                            if (nextCell != null && (
                                    nextCell.tagName().equals("td", ignoreCase = true) ||
                                        nextCell.tagName().equals("th", ignoreCase = true)
                                )
                            ) {
                                trimTrailingInlineSpaces(sb)
                                val currentLineLen = currentLineLength(sb)
                                val lastChar = sb.lastOrNull()
                                if (lastChar != null && lastChar != '\n') {
                                    if (lastChar == ':' || lastChar == '：') {
                                        sb.append(' ')
                                    } else if (currentLineLen in 1..44 && !currentLineContainsSeparator(sb)) {
                                        sb.append(": ")
                                    } else {
                                        sb.append("  ")
                                    }
                                }
                            }
                        }

                        tag in BLOCK_NEWLINE_TAGS -> ensureNewline(sb)
                    }
                }
            },
            body,
        )

        return sb.toString()
            .lines()
            .joinToString("\n") { it.replace(INLINE_SPACE_REGEX, " ").trim() }
            .replace(MULTI_NEWLINE_REGEX, "\n\n")
            .trim()
    }

    /**
     * Parses a [SeekMailDetail] into [ParsedVpsBillingMail], returning `null` if the email is not
     * a recognizable VPS provisioning, renewal, or invoice message.
     */
    fun parseDetail(detail: SeekMailDetail): ParsedVpsBillingMail? {
        val htmlSource = detail.bodyHtml.ifBlank { detail.sanitizedBodyHtml }
        return parse(
            subject = detail.summary.subject,
            bodyText = detail.bodyText,
            bodyHtml = htmlSource,
            fromName = detail.summary.fromName,
            fromAddress = detail.summary.fromAddress,
        )
    }

    /**
     * Fast summary-level recognizer for displaying subtle VPS mail badges in the inbox list.
     */
    fun parseSummaryHint(summary: SeekMailSummary): VpsMailSummaryHint? {
        val parsed = parse(
            subject = summary.subject,
            bodyText = summary.snippet,
            bodyHtml = "",
            fromName = summary.fromName,
            fromAddress = summary.fromAddress,
        ) ?: return null

        return VpsMailSummaryHint(
            mailType = parsed.mailType,
            providerName = parsed.providerName,
            primaryIp = parsed.mainIpv4 ?: parsed.mainIpv6,
            formattedPrice = parsed.formattedPrice,
        )
    }

    /**
     * Full parser combining plain-text and HTML email representations so neither line-broken HTML
     * nor plain-text-only messages lose field boundaries.
     */
    fun parse(
        subject: String,
        bodyText: String = "",
        bodyHtml: String = "",
        fromName: String = "",
        fromAddress: String = "",
    ): ParsedVpsBillingMail? {
        val cleanSubject = subject.replace(INLINE_SPACE_REGEX, " ").trim()
        val normalizedPlain = normalizeRawText(bodyText)
        val normalizedHtml = if (bodyHtml.isNotBlank()) htmlToStructuredLines(bodyHtml) else ""

        // Prefer structured HTML lines when available if bodyText lost line breaks or is blank,
        // and keep the other representation as a fallback source for any missing fields.
        val primaryBody = when {
            normalizedHtml.isNotBlank() && normalizedPlain.isBlank() -> normalizedHtml
            normalizedHtml.isNotBlank() && normalizedPlain.lines().size <= 2 && normalizedHtml.lines().size > 2 ->
                normalizedHtml
            normalizedPlain.isNotBlank() -> normalizedPlain
            else -> normalizedHtml
        }
        val secondaryBody = if (primaryBody === normalizedHtml) normalizedPlain else normalizedHtml
        val combinedBody = if (secondaryBody.isNotBlank() && secondaryBody != primaryBody) {
            "$primaryBody\n\n$secondaryBody"
        } else {
            primaryBody
        }

        if (cleanSubject.isEmpty() && combinedBody.isEmpty()) return null

        val preferDayFirst = inferDayFirstPreference(
            subject = cleanSubject,
            body = combinedBody,
            fromAddress = fromAddress,
        )

        val ipv4Result = extractIpv4AndInlinePort(primaryBody)
            ?: extractIpv4AndInlinePort(secondaryBody)
        val mainIpv4 = ipv4Result?.first
        val mainIpv6 = extractIpv6(primaryBody) ?: extractIpv6(secondaryBody)

        val sshCliMatch = SSH_CLI_COMMAND_REGEX.find(combinedBody)
        val sshPassword = extractSshPassword(primaryBody) ?: extractSshPassword(secondaryBody)
        val sshUsername = extractSshUsername(
            primaryBody = primaryBody,
            secondaryBody = secondaryBody,
            sshCliMatch = sshCliMatch,
            hasSshPassword = !sshPassword.isNullOrBlank(),
        )
        val sshPort = extractSshPort(
            primaryBody = primaryBody,
            secondaryBody = secondaryBody,
            inlineIpPort = ipv4Result?.second,
            sshCliMatch = sshCliMatch,
        )

        val invoiceNumber = extractInvoiceNumber(cleanSubject)
            ?: extractInvoiceNumber(primaryBody)
            ?: extractInvoiceNumber(secondaryBody)

        val priceResult = extractMoneyAmountAndCurrency(primaryBody, cleanSubject)
            ?: extractMoneyAmountAndCurrency(secondaryBody, cleanSubject)

        val periodInfo = extractWhmcsLineItemInfo(primaryBody, preferDayFirst)
            ?: extractWhmcsLineItemInfo(secondaryBody, preferDayFirst)

        val mailType = classifyMailType(
            subject = cleanSubject,
            body = combinedBody,
            fromAddress = fromAddress,
            hasServerIp = mainIpv4 != null || mainIpv6 != null,
            hasSshPassword = !sshPassword.isNullOrBlank(),
            hasInvoiceNumber = invoiceNumber != null,
            hasBillingAmount = priceResult != null,
            hasPeriodRange = periodInfo != null,
        ) ?: return null

        val explicitCycle = extractBillingCycle(primaryBody)
            ?: extractBillingCycle(secondaryBody)
            ?: extractBillingCycle(cleanSubject)
        val billingCycle = explicitCycle ?: periodInfo?.inferredCycle

        val explicitExpiryIso = extractLabeledDateIso(EXPLICIT_EXPIRY_LINE_REGEX, primaryBody, preferDayFirst)
            ?: extractLabeledDateIso(EXPLICIT_EXPIRY_LINE_REGEX, secondaryBody, preferDayFirst)
        val genericDueIso = extractLabeledDateIso(GENERIC_DUE_DATE_LINE_REGEX, primaryBody, preferDayFirst)
            ?: extractLabeledDateIso(GENERIC_DUE_DATE_LINE_REGEX, secondaryBody, preferDayFirst)

        // Critical WHMCS distinction:
        // - In RENEWAL mails, "Invoice Due Date" is the invoice's own payment due date (today),
        //   NOT the server's new expiry date; only explicit Next Due / Expiry Date or service
        //   period end date `(start - end)` represents the new server expiry!
        // - In INVOICE (unpaid) mails, `Due Date` is when payment is due.
        // - In PROVISIONING mails, `Next Due Date` / `Due Date` is the server's next due date.
        val nextDueDateIso = when (mailType) {
            VpsMailType.PROVISIONING -> explicitExpiryIso ?: periodInfo?.endDateIso ?: genericDueIso
            VpsMailType.RENEWAL -> explicitExpiryIso ?: periodInfo?.endDateIso
            VpsMailType.INVOICE -> genericDueIso ?: explicitExpiryIso ?: periodInfo?.startDateIso
        }

        val providerName = extractProviderName(
            fromName = fromName,
            fromAddress = fromAddress,
            subject = cleanSubject,
            body = primaryBody,
        )

        val explicitProduct = extractSingleLineField(PRODUCT_LINE_REGEX, primaryBody)
            ?: extractSingleLineField(PRODUCT_LINE_REGEX, secondaryBody)
        val productName = cleanProductValue(explicitProduct ?: periodInfo?.productTitle.orEmpty())

        val explicitHostname = extractSingleLineField(HOSTNAME_LINE_REGEX, primaryBody)
            ?: extractSingleLineField(HOSTNAME_LINE_REGEX, secondaryBody)
        val fallbackInlineHostname = if (explicitHostname == null && periodInfo?.hostname.isNullOrBlank()) {
            extractInlineServerFqdn(combinedBody, fromAddress)
        } else {
            null
        }
        val hostname = cleanHostnameValue(
            candidate = explicitHostname ?: periodInfo?.hostname?.takeIf { it.isNotBlank() } ?: fallbackInlineHostname.orEmpty(),
            ipv4 = mainIpv4,
        )

        val panelUrl = extractSingleLineField(PANEL_URL_LINE_REGEX, primaryBody)
            ?: extractSingleLineField(PANEL_URL_LINE_REGEX, secondaryBody)
            ?: ""

        val specsSummary = extractSpecsSummary(primaryBody, productName)
        val trafficQuotaGb = extractMonthlyTrafficQuotaGb(primaryBody)
            ?: extractMonthlyTrafficQuotaGb(secondaryBody)
        val region = extractRegion(
            primaryBody = primaryBody,
            productName = productName,
            hostname = hostname,
            subject = cleanSubject,
        )

        val finalIpv4 = mainIpv4 ?: periodInfo?.inlineIpv4

        return ParsedVpsBillingMail(
            mailType = mailType,
            providerName = providerName,
            productName = productName,
            hostname = hostname,
            mainIpv4 = finalIpv4,
            mainIpv6 = mainIpv6,
            sshPort = sshPort,
            sshUsername = sshUsername,
            sshPassword = sshPassword,
            billingAmount = priceResult?.first,
            currency = priceResult?.second ?: inferDefaultCurrency(combinedBody, fromAddress),
            billingCycle = billingCycle,
            nextDueDateIso = nextDueDateIso,
            invoiceNumber = invoiceNumber,
            specsSummary = specsSummary,
            monthlyTrafficQuotaGb = trafficQuotaGb,
            region = region,
            panelUrl = panelUrl,
        )
    }

    /**
     * Parses common WHMCS / international / Chinese date representations into canonical `yyyy-MM-dd`:
     * - `YYYY-MM-DD`, `YYYY/MM/DD`, `YYYY.MM.DD`
     * - `DD/MM/YYYY`, `MM/DD/YYYY` (and `-` / `.` variants)
     * - `Month DD, YYYY`, `DD Month YYYY`, `Month DDth, YYYY`
     * - `YYYY年MM月DD日`
     */
    fun parseFlexibleDateIso(
        raw: String,
        preferDayFirst: Boolean = false,
    ): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 1. YYYY年MM月DD日
        Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日?""").find(text)?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            buildValidIsoDate(y, mo, d)?.let { return it }
        }

        // 2. YYYY-MM-DD or YYYY/MM/DD or YYYY.MM.DD
        Regex("""\b(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\b""").find(text)?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            buildValidIsoDate(y, mo, d)?.let { return it }
        }

        // 3. Month DD, YYYY (e.g. "March 15, 2027", "Nov 22nd, 2026")
        Regex(
            """\b([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val month = ENGLISH_MONTHS[m.groupValues[1].lowercase(Locale.ROOT)]
            val day = m.groupValues[2].toIntOrNull()
            val year = m.groupValues[3].toIntOrNull()
            if (month != null) {
                buildValidIsoDate(year, month.value, day)?.let { return it }
            }
        }

        // 4. DD Month YYYY (e.g. "15 March 2027", "15th Mar, 2027")
        Regex(
            """\b(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})\.?,?\s+(\d{4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val day = m.groupValues[1].toIntOrNull()
            val month = ENGLISH_MONTHS[m.groupValues[2].lowercase(Locale.ROOT)]
            val year = m.groupValues[3].toIntOrNull()
            if (month != null) {
                buildValidIsoDate(year, month.value, day)?.let { return it }
            }
        }

        // 5. DD/MM/YYYY or MM/DD/YYYY (and '-' / '.' variants)
        Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})\b""").find(text)?.let { m ->
            val first = m.groupValues[1].toIntOrNull() ?: return null
            val second = m.groupValues[2].toIntOrNull() ?: return null
            val year = m.groupValues[3].toIntOrNull() ?: return null

            val (month, day) = when {
                first > 12 && second in 1..12 -> second to first
                second > 12 && first in 1..12 -> first to second
                preferDayFirst -> second to first
                else -> first to second
            }
            buildValidIsoDate(year, month, day)?.let { return it }
        }

        return null
    }

    // ── Internal Classification & Extraction Helpers ─────────────────────────

    private fun normalizeRawText(raw: String): String {
        if (raw.isBlank()) return ""
        val source = if (HTML_TAG_HINT_REGEX.containsMatchIn(raw)) {
            htmlToStructuredLines(raw)
        } else {
            raw
        }
        return source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.replace(INLINE_SPACE_REGEX, " ").trim() }
            .replace(MULTI_NEWLINE_REGEX, "\n\n")
            .trim()
    }

    private fun classifyMailType(
        subject: String,
        body: String,
        fromAddress: String,
        hasServerIp: Boolean,
        hasSshPassword: Boolean,
        hasInvoiceNumber: Boolean,
        hasBillingAmount: Boolean,
        hasPeriodRange: Boolean,
    ): VpsMailType? {
        val combined = "$subject\n$body"
        if (PURE_OTP_SUBJECT_REGEX.containsMatchIn(subject) && !hasSshPassword && !hasInvoiceNumber) {
            return null
        }

        val senderDomain = fromAddress.substringAfter('@', "").lowercase(Locale.ROOT)
        val isKnownVpsProvider = KNOWN_PROVIDER_DOMAINS.keys.any { domain ->
            senderDomain == domain || senderDomain.endsWith(".$domain")
        }
        val hasVpsContext = isKnownVpsProvider ||
            hasServerIp ||
            hasSshPassword ||
            VPS_DOMAIN_OR_BODY_CONTEXT_REGEX.containsMatchIn(combined)

        // 1. Provisioning check
        val hasProvisioningSubject = PROVISIONING_SUBJECT_STRONG_REGEX.containsMatchIn(subject)
        val hasProvisioningBody = PROVISIONING_BODY_SIGNAL_REGEX.containsMatchIn(body)
        if (hasProvisioningSubject && (hasServerIp || hasSshPassword || hasVpsContext || body.isBlank())) {
            return VpsMailType.PROVISIONING
        }
        if (hasServerIp && hasSshPassword && (hasProvisioningBody || hasVpsContext)) {
            return VpsMailType.PROVISIONING
        }
        if (hasProvisioningBody && hasServerIp) {
            return VpsMailType.PROVISIONING
        }

        // 2. Renewal / Payment Confirmation check (must precede generic "Invoice" check because
        //    "Invoice Payment Confirmation" contains the word "Invoice"!)
        val hasRenewalSubject = RENEWAL_STRONG_REGEX.containsMatchIn(subject)
        val hasRenewalBody = RENEWAL_STRONG_REGEX.containsMatchIn(body) || PAID_STATUS_LINE_REGEX.containsMatchIn(body)
        val hasExplicitUnpaidSubject = Regex(
            """unpaid|overdue|payment\s+reminder|upcoming\s+renewal|renewal\s+reminder|未付|待付|逾期|续费提醒|即将到期""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(subject)

        if (!hasExplicitUnpaidSubject && (hasRenewalSubject || hasRenewalBody)) {
            if (hasInvoiceNumber || hasBillingAmount || hasPeriodRange || hasVpsContext || body.isBlank()) {
                return VpsMailType.RENEWAL
            }
        }

        // 3. Unpaid / Generated Invoice check
        val hasInvoiceSignal = UNPAID_INVOICE_SIGNAL_REGEX.containsMatchIn(combined) ||
            (Regex("""\binvoice\s*#?\s*\d+|\b账单\s*#?\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(subject))
        if (hasInvoiceSignal && (hasInvoiceNumber || hasBillingAmount || hasPeriodRange || hasVpsContext || body.isBlank())) {
            return VpsMailType.INVOICE
        }

        return null
    }

    private fun extractProviderName(
        fromName: String,
        fromAddress: String,
        subject: String,
        body: String,
    ): String {
        val domain = fromAddress.trim().substringAfter('@', "").lowercase(Locale.ROOT)
        val knownDomainBrand = KNOWN_PROVIDER_DOMAINS.entries
            .firstOrNull { (k, _) -> domain == k || domain.endsWith(".$k") }
            ?.value

        // 1. Bracket prefix in subject, e.g. "[RackNerd] New Server Information"
        SUBJECT_BRACKET_PROVIDER_REGEX.find(subject)?.let { m ->
            val candidate = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            val cleaned = cleanProviderCandidate(candidate)
            if (cleaned.isNotEmpty() && !isGenericProviderToken(cleaned)) {
                return cleaned
            }
        }

        // 2. Clean sender display name (`fromName`)
        val cleanedFromName = cleanProviderCandidate(fromName)
        if (cleanedFromName.isNotEmpty() && !isGenericProviderToken(cleanedFromName)) {
            return cleanedFromName
        }

        // 3. Known provider domain brand
        if (!knownDomainBrand.isNullOrBlank()) {
            return knownDomainBrand
        }

        // 4. Subject prefix before dash/colon, e.g. "DMIT.io - Invoice Payment Confirmation"
        SUBJECT_PREFIX_PROVIDER_REGEX.find(subject)?.let { m ->
            val cleaned = cleanProviderCandidate(m.groupValues[1])
            if (cleaned.isNotEmpty() && !isGenericProviderToken(cleaned)) {
                return cleaned
            }
        }

        // 5. Signature at bottom of email body
        SIGNATURE_PROVIDER_REGEX.find(body)?.let { m ->
            val cleaned = cleanProviderCandidate(m.groupValues[1].lines().firstOrNull().orEmpty())
            if (cleaned.isNotEmpty() && !isGenericProviderToken(cleaned)) {
                return cleaned
            }
        }

        // 6. Fallback to non-generic sender domain root
        if (domain.isNotEmpty() && domain !in GENERIC_MAIL_DOMAINS &&
            GENERIC_MAIL_DOMAINS.none { domain.endsWith(".$it") }
        ) {
            val stripped = domain
                .removePrefix("mail.")
                .removePrefix("billing.")
                .removePrefix("my.")
                .removePrefix("portal.")
                .removePrefix("clients.")
                .removePrefix("support.")
            val rootLabel = stripped.substringBefore('.')
            if (rootLabel.length >= 2 && rootLabel.lowercase(Locale.ROOT) !in GENERIC_SENDER_NAMES) {
                return rootLabel.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
            }
        }

        return ""
    }

    private fun cleanProviderCandidate(raw: String): String {
        var text = raw.trim()
            .trim('"', '\'', '“', '”', '[', ']', '【', '】')
            .substringBefore('<')
            .trim()
        if (text.contains('@')) return ""
        text = text.replace(SENDER_ROLE_SUFFIX_REGEX, "").trim()
        text = text.replace(Regex("""\s+(?:LLC|Inc\.?|Ltd\.?|Co\.,?\s*Ltd\.?|Limited)\s*$""", RegexOption.IGNORE_CASE), "").trim()
        return text
    }

    private fun isGenericProviderToken(candidate: String): Boolean {
        val lower = candidate.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return true
        if (lower in GENERIC_SENDER_NAMES) return true
        if (lower.startsWith("invoice") || lower.startsWith("new server") || lower.startsWith("customer")) return true
        return false
    }

    private fun extractIpv4AndInlinePort(text: String): Pair<String, Int?>? {
        if (text.isBlank()) return null

        // 1. Labeled IPv4 lines (ignoring Gateway / Netmask / Nameserver lines)
        val labeledCandidates = mutableListOf<Pair<String, Int?>>()
        for (match in LABELED_IPV4_REGEX.findAll(text)) {
            val fullLine = match.value
            if (EXCLUDED_IP_LINE_LABEL_REGEX.containsMatchIn(fullLine)) continue
            val ip = match.groupValues[1]
            if (!isValidHostIpv4(ip)) continue
            val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 }
            if (isPublicIpv4(ip)) {
                return ip to port
            }
            labeledCandidates += ip to port
        }
        if (labeledCandidates.isNotEmpty()) {
            return labeledCandidates.first()
        }

        // 2. SSH CLI command host (`ssh root@198.51.100.42 -p 2222`)
        SSH_CLI_COMMAND_REGEX.find(text)?.let { sshMatch ->
            val host = sshMatch.groupValues[3]
            if (isValidHostIpv4(host)) {
                val port = (sshMatch.groupValues[1].ifBlank { sshMatch.groupValues[4] })
                    .toIntOrNull()
                    ?.takeIf { it in 1..65535 }
                return host to port
            }
        }

        // 3. Unlabeled public IPv4 on non-excluded lines
        for (line in text.lines()) {
            if (EXCLUDED_IP_LINE_LABEL_REGEX.containsMatchIn(line)) continue
            if (line.contains("http://", ignoreCase = true) || line.contains("https://", ignoreCase = true)) {
                continue
            }
            for (match in ANY_IPV4_CANDIDATE_REGEX.findAll(line)) {
                val ip = match.groupValues[1]
                if (isValidHostIpv4(ip) && isPublicIpv4(ip)) {
                    val port = match.groupValues[3].toIntOrNull()?.takeIf { it in 1..65535 }
                    return ip to port
                }
            }
        }
        return null
    }

    private fun isValidHostIpv4(candidate: String): Boolean {
        val parts = candidate.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return false
            val value = part.toIntOrNull() ?: return false
            if (value !in 0..255) return false
            octets[i] = value
        }
        val first = octets[0]
        val second = octets[1]
        if (first == 0 || first == 127 || first >= 224) return false
        if (first == 169 && second == 254) return false
        if (octets[0] == 255 || (octets[2] == 255 && octets[3] == 255)) return false
        return true
    }

    private fun isPublicIpv4(ip: String): Boolean {
        val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        val (a, b, _, _) = octets
        if (a == 10) return false
        if (a == 192 && b == 168) return false
        if (a == 172 && b in 16..31) return false
        if (a == 100 && b in 64..127) return false
        if (ip == "8.8.8.8" || ip == "8.8.4.4" || ip == "1.1.1.1" || ip == "1.0.0.1") return false
        return true
    }

    private fun extractIpv6(text: String): String? {
        if (text.isBlank()) return null

        for (match in LABELED_IPV6_REGEX.findAll(text)) {
            val cleaned = cleanIpv6Candidate(match.groupValues[1])
            if (cleaned != null) return cleaned
        }

        for (line in text.lines()) {
            if (EXCLUDED_IP_LINE_LABEL_REGEX.containsMatchIn(line)) continue
            for (match in ANY_IPV6_CANDIDATE_REGEX.findAll(line)) {
                val cleaned = cleanIpv6Candidate(match.groupValues[1])
                if (cleaned != null) return cleaned
            }
        }
        return null
    }

    private fun cleanIpv6Candidate(raw: String): String? {
        val trimmed = raw.trim().trim('[', ']').substringBefore('/').trim()
        if (trimmed.count { it == ':' } < 2) return null
        if (trimmed == "::" || trimmed == "::1") return null
        // Reject HH:MM:SS clock strings (3 decimal groups of 1-2 digits without '::' and no hex a-f)
        val groups = trimmed.split(':')
        if (!trimmed.contains("::") && groups.size == 3 && groups.all { it.length in 1..2 && it.all(Char::isDigit) }) {
            return null
        }
        val nonEmptyGroups = groups.filter { it.isNotEmpty() }
        if (nonEmptyGroups.isEmpty() || nonEmptyGroups.size > 8) return null
        if (!nonEmptyGroups.all { group -> group.length in 1..4 && group.all { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' } }) {
            return null
        }
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("fe80:")) return null
        // If WHMCS gave a /64 subnet ending in "::" (e.g. "2607:f130:0:ef::"), keep valid address or append '1'?
        // Wait: preserve exact address reported by provider (only disallowing bare "::").
        return lower
    }

    private fun extractSshPort(
        primaryBody: String,
        secondaryBody: String,
        inlineIpPort: Int?,
        sshCliMatch: MatchResult?,
    ): Int {
        for (source in listOf(primaryBody, secondaryBody)) {
            EXPLICIT_SSH_PORT_REGEX.find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?.let { return it }
        }

        sshCliMatch?.let { m ->
            val cliPort = (m.groupValues[1].ifBlank { m.groupValues[4] }).toIntOrNull()
            if (cliPort != null && cliPort in 1..65535) return cliPort
        }

        if (inlineIpPort != null && inlineIpPort in 1..65535) {
            return inlineIpPort
        }

        for (source in listOf(primaryBody, secondaryBody)) {
            for (match in GENERIC_PORT_LINE_REGEX.findAll(source)) {
                val lineIndex = match.range.first
                val contextBefore = source.substring((lineIndex - 80).coerceAtLeast(0), lineIndex)
                if (PANEL_CONTEXT_PREFIX_REGEX.containsMatchIn(contextBefore)) continue
                val port = match.groupValues[1].toIntOrNull()
                if (port != null && port in 1..65535) return port
            }
        }

        return ParsedVpsBillingMail.DEFAULT_SSH_PORT
    }

    private fun extractSshPassword(text: String): String? {
        if (text.isBlank()) return null

        EXPLICIT_ROOT_OR_SSH_PASSWORD_REGEX.find(text)?.let { m ->
            cleanPasswordValue(m.groupValues[1])?.let { return it }
        }

        for (match in GENERIC_PASSWORD_LINE_REGEX.findAll(text)) {
            val labelPart = match.groupValues[1]
            if (PANEL_CONTEXT_PREFIX_REGEX.containsMatchIn(labelPart)) continue
            val start = match.range.first
            val precedingLine = text.substring((start - 72).coerceAtLeast(0), start)
                .lines()
                .lastOrNull { it.isNotBlank() }
                .orEmpty()
            if (PANEL_CONTEXT_PREFIX_REGEX.containsMatchIn(precedingLine) &&
                !Regex("""ssh|root|server\s+information""", RegexOption.IGNORE_CASE).containsMatchIn(precedingLine)
            ) {
                continue
            }
            cleanPasswordValue(match.groupValues[2])?.let { return it }
        }

        return null
    }

    private fun cleanPasswordValue(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower in setOf(
                "none", "n/a", "null", "ssh key", "ssh-key", "public key",
                "configured ssh key", "(same as account)", "无", "密钥登录", "已隐藏",
            )
        ) {
            return null
        }
        return trimmed
    }

    private fun extractSshUsername(
        primaryBody: String,
        secondaryBody: String,
        sshCliMatch: MatchResult?,
        hasSshPassword: Boolean,
    ): String {
        for (source in listOf(primaryBody, secondaryBody)) {
            EXPLICIT_SSH_USERNAME_REGEX.find(source)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.takeIf { isValidLinuxUsername(it) }
                ?.let { return it }
        }

        sshCliMatch?.groupValues?.getOrNull(2)?.trim()
            ?.takeIf { isValidLinuxUsername(it) }
            ?.let { return it }

        val combined = "$primaryBody\n$secondaryBody"
        val mentionsExplicitRootPassword = Regex(
            """root\s*(?:/\s*admin\s*)?(?:password|密码)""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(combined)

        for (source in listOf(primaryBody, secondaryBody)) {
            for (match in GENERIC_USERNAME_LINE_REGEX.findAll(source)) {
                val label = match.groupValues[1]
                if (PANEL_CONTEXT_PREFIX_REGEX.containsMatchIn(label)) continue
                val start = match.range.first
                val precedingLine = source.substring((start - 72).coerceAtLeast(0), start)
                    .lines()
                    .lastOrNull { it.isNotBlank() }
                    .orEmpty()
                if (PANEL_CONTEXT_PREFIX_REGEX.containsMatchIn(precedingLine)) continue

                val valueToken = match.groupValues[2].trim().split(Regex("""\s+""")).firstOrNull().orEmpty()
                if (isValidLinuxUsername(valueToken)) {
                    return valueToken
                }
            }
        }

        if (mentionsExplicitRootPassword || hasSshPassword) {
            return ParsedVpsBillingMail.DEFAULT_SSH_USERNAME
        }
        return ParsedVpsBillingMail.DEFAULT_SSH_USERNAME
    }

    private fun isValidLinuxUsername(candidate: String): Boolean {
        if (candidate.isEmpty() || candidate.length > 32) return false
        if (candidate.contains('@') || candidate.contains('/') || candidate.contains(':')) return false
        return candidate[0].let { it.isLetter() || it == '_' } &&
            candidate.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }

    private fun extractInvoiceNumber(text: String): String? {
        if (text.isBlank()) return null
        for (match in INVOICE_NUMBER_REGEX.findAll(text)) {
            val raw = (match.groupValues[1].ifBlank { match.groupValues[2] }).trim().trimStart('#')
            if (raw.isEmpty()) continue
            if (raw.uppercase(Locale.ROOT) in BLOCKED_INVOICE_TOKENS) continue
            // Real invoice numbers contain at least one digit (e.g. "849201", "INV-2026-0089").
            if (!raw.any { it.isDigit() }) continue
            return raw
        }
        return null
    }

    private fun extractMoneyAmountAndCurrency(
        body: String,
        subject: String,
    ): Pair<String, ValuationCurrency>? {
        if (body.isNotBlank()) {
            for (match in RECURRING_AMOUNT_LINE_REGEX.findAll(body)) {
                parseMoneyToken(match.groupValues[1])?.let { (amt, cur) ->
                    if ((amt.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO) {
                        return amt to cur
                    }
                }
            }

            var bestPositive: Pair<String, ValuationCurrency>? = null
            for (match in GENERAL_AMOUNT_LINE_REGEX.findAll(body)) {
                val parsed = parseMoneyToken(match.groupValues[1]) ?: continue
                val numeric = parsed.first.toBigDecimalOrNull() ?: continue
                if (numeric > BigDecimal.ZERO) {
                    return parsed
                }
                if (bestPositive == null) bestPositive = parsed
            }
            if (bestPositive != null) return bestPositive

            parseInlineMoneyFromText(body)?.let { return it }
        }

        if (subject.isNotBlank()) {
            parseInlineMoneyFromText(subject)?.let { return it }
        }
        return null
    }

    private val MONEY_NUMBER_PATTERN =
        """(?:[0-9]{1,3}(?:,[0-9]{3})+\.[0-9]{1,2}|[0-9]+,[0-9]{2}(?![0-9])|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+(?:\.[0-9]{1,2})?)"""

    private val MONEY_TOKEN_REGEX = Regex(
        pattern = buildString {
            append("(?:")
            // Symbol or ISO code before number, optional ISO code / unit after number:
            // e.g. "$10.99 USD", "HK$58.00 HKD", "€4,50", "¥99.00 CNY", "USD 10.99"
            append("(HK\\$|CA\\$|AU\\$|NT\\$|S\\$|C\\$|A\\$|US\\$|[$€£¥￥₩₽]|USD|CNY|RMB|EUR|GBP|HKD|JPY|SGD|CAD|AUD|TWD|KRW|CHF|RUB)\\s*($MONEY_NUMBER_PATTERN)\\s*(USD|CNY|RMB|EUR|GBP|HKD|JPY|SGD|CAD|AUD|TWD|KRW|CHF|RUB|日元|円|元|刀)?")
            // Or number followed by ISO code / symbol: e.g. "10.99 USD", "1,200 JPY", "99.00元"
            append("|($MONEY_NUMBER_PATTERN)\\s*(HK\\$|CA\\$|AU\\$|NT\\$|S\\$|C\\$|A\\$|US\\$|USD|CNY|RMB|EUR|GBP|HKD|JPY|SGD|CAD|AUD|TWD|KRW|CHF|RUB|[€£₩₽]|日元|円|元)")
            append(")")
        },
        option = RegexOption.IGNORE_CASE,
    )

    private fun parseMoneyToken(rawLine: String): Pair<String, ValuationCurrency>? {
        val match = MONEY_TOKEN_REGEX.find(rawLine) ?: return null
        val prefixCur = match.groupValues[1]
        val numA = match.groupValues[2]
        val suffixCurA = match.groupValues[3]
        val numB = match.groupValues[4]
        val suffixCurB = match.groupValues[5]

        val rawNumber = numA.ifBlank { numB }
        val normalizedNumber = normalizeDecimalNumber(rawNumber) ?: return null

        // Resolve currency preferring specific ISO code / multi-char symbol over bare '$' or '¥'!
        val explicitSuffix = resolveCurrencyToken(suffixCurA) ?: resolveCurrencyToken(suffixCurB)
        val rawPrefix = resolveCurrencyToken(prefixCur)
        val currency = when {
            explicitSuffix != null -> explicitSuffix
            (prefixCur == "¥" || prefixCur == "￥") &&
                Regex("""\bJPY\b|日元|円""", RegexOption.IGNORE_CASE).containsMatchIn(rawLine) ->
                ValuationCurrency.JPY
            rawPrefix != null -> rawPrefix
            else -> inferDefaultCurrency(rawLine, "")
        }

        return normalizedNumber to currency
    }

    private fun parseInlineMoneyFromText(text: String): Pair<String, ValuationCurrency>? {
        for (match in MONEY_TOKEN_REGEX.findAll(text)) {
            val parsed = parseMoneyToken(match.value) ?: continue
            val bd = parsed.first.toBigDecimalOrNull() ?: continue
            if (bd > BigDecimal.ZERO) return parsed
        }
        return null
    }

    private fun normalizeDecimalNumber(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val cleaned = when {
            trimmed.contains(',') && trimmed.contains('.') -> trimmed.replace(",", "")
            Regex("""^\d+,\d{2}$""").matches(trimmed) -> trimmed.replace(',', '.')
            else -> trimmed.replace(",", "")
        }
        val bd = cleaned.toBigDecimalOrNull() ?: return null
        if (bd < BigDecimal.ZERO) return null
        return cleaned
    }

    private fun resolveCurrencyToken(token: String): ValuationCurrency? {
        val upper = token.trim().uppercase(Locale.ROOT)
        if (upper.isEmpty()) return null
        return when (upper) {
            "USD", "US$", "$", "刀" -> ValuationCurrency.USD
            "CNY", "RMB", "元" -> ValuationCurrency.CNY
            "JPY", "日元", "円" -> ValuationCurrency.JPY
            "¥", "￥" -> ValuationCurrency.CNY
            "EUR", "€" -> ValuationCurrency.EUR
            "GBP", "£" -> ValuationCurrency.GBP
            "HKD", "HK$" -> ValuationCurrency.HKD
            "SGD", "S$" -> ValuationCurrency.SGD
            "CAD", "CA$", "C$" -> ValuationCurrency.CAD
            "AUD", "AU$", "A$" -> ValuationCurrency.AUD
            "TWD", "NT$" -> ValuationCurrency.TWD
            "KRW", "₩" -> ValuationCurrency.KRW
            "CHF" -> ValuationCurrency.CHF
            "RUB", "₽" -> ValuationCurrency.RUB
            else -> ValuationCurrency.fromCode(upper)
        }
    }

    private fun inferDefaultCurrency(text: String, fromAddress: String): ValuationCurrency {
        val upper = text.uppercase(Locale.ROOT)
        return when {
            upper.contains("HKD") || upper.contains("HK$") -> ValuationCurrency.HKD
            upper.contains("JPY") || text.contains("日元") || text.contains("円") -> ValuationCurrency.JPY
            upper.contains("EUR") || text.contains("€") -> ValuationCurrency.EUR
            upper.contains("GBP") || text.contains("£") -> ValuationCurrency.GBP
            upper.contains("CNY") || upper.contains("RMB") || text.contains("¥") || text.contains("￥") ->
                ValuationCurrency.CNY
            fromAddress.endsWith(".cn", ignoreCase = true) && !text.contains("$") -> ValuationCurrency.CNY
            else -> ValuationCurrency.USD
        }
    }

    private fun extractBillingCycle(text: String): BillingCyclePreset? {
        if (text.isBlank()) return null

        BILLING_CYCLE_LINE_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { lineVal ->
            matchCycleKeywords(lineVal)?.let { return it }
        }
        return matchCycleKeywords(text)
    }

    private fun matchCycleKeywords(text: String): BillingCyclePreset? {
        val s = text.lowercase(Locale.ROOT)
        return when {
            Regex("""triennial(?:ly)?|3\s*years?|36\s*months?|/\s*3\s*yr|三年付?|3\s*年付?|36\s*个月""").containsMatchIn(s) ->
                BillingCyclePreset.TRIENNIAL
            Regex("""biennial(?:ly)?|2\s*years?|24\s*months?|/\s*2\s*yr|两年付?|二年付?|2\s*年付?|24\s*个月""").containsMatchIn(s) ->
                BillingCyclePreset.BIENNIAL
            Regex("""semi[-\s]?annual(?:ly)?|half[-\s]?year(?:ly)?|6\s*months?|半年付?|6\s*个月|六个月""").containsMatchIn(s) ->
                BillingCyclePreset.SEMI_ANNUAL
            Regex("""quarterly|every\s*3\s*months?|3\s*months?|/\s*qtr|/\s*季|季付|每季|按季|3\s*个月|三个月""").containsMatchIn(s) ->
                BillingCyclePreset.QUARTERLY
            Regex("""\bmonthly\b|1\s*month\b|per\s*month|/\s*mo(?:nth)?\b|/\s*月|月付|每月|按月|1\s*个月|一个月""").containsMatchIn(s) ->
                BillingCyclePreset.MONTHLY
            Regex("""\bannual(?:ly)?\b|\byearly\b|1\s*year\b|12\s*months?\b|/\s*yr\b|/\s*year\b|/\s*年|年付|每年|按年|1\s*年|一年|12\s*个月""").containsMatchIn(s) ->
                BillingCyclePreset.ANNUAL
            else -> null
        }
    }

    private data class WhmcsLineItemInfo(
        val productTitle: String,
        val hostname: String,
        val inlineIpv4: String?,
        val startDateIso: String,
        val endDateIso: String,
        val inferredCycle: BillingCyclePreset?,
    )

    private fun extractWhmcsLineItemInfo(
        body: String,
        preferDayFirst: Boolean,
    ): WhmcsLineItemInfo? {
        if (body.isBlank()) return null

        for (line in body.lines()) {
            for (rangeMatch in WHMCS_PERIOD_RANGE_REGEX.findAll(line)) {
                val startIso = parseFlexibleDateIso(rangeMatch.groupValues[1], preferDayFirst) ?: continue
                val endIso = parseFlexibleDateIso(rangeMatch.groupValues[2], preferDayFirst) ?: continue

                val startDate = runCatching { LocalDate.parse(startIso) }.getOrNull() ?: continue
                val endDate = runCatching { LocalDate.parse(endIso) }.getOrNull() ?: continue
                val spanDays = ChronoUnit.DAYS.between(startDate, endDate)
                if (spanDays <= 0L) continue

                val inferredCycle = inferCycleFromSpanDays(spanDays)
                val beforeRange = line.substring(0, rangeMatch.range.first).trim()

                var extractedHostname = ""
                var extractedIp: String? = null
                var cleanTitle = beforeRange

                // Check trailing parenthesized `(hostname)` or `(hostname - 1.2.3.4)` before date range
                val parenMatch = Regex("""^(.*?)\s*[(（]([^()（）]{2,100})[)）]\s*$""").find(beforeRange)
                if (parenMatch != null) {
                    cleanTitle = parenMatch.groupValues[1].trim()
                    val inside = parenMatch.groupValues[2].trim()
                    ANY_IPV4_CANDIDATE_REGEX.find(inside)?.groupValues?.getOrNull(1)
                        ?.takeIf { isValidHostIpv4(it) && isPublicIpv4(it) }
                        ?.let { extractedIp = it }
                    val hostToken = inside
                        .replace(ANY_IPV4_CANDIDATE_REGEX, "")
                        .trim(' ', '-', '–', '—', '|', ',')
                    if (looksLikeHostname(hostToken)) {
                        extractedHostname = hostToken
                    }
                } else if (beforeRange.contains(" - ")) {
                    val tail = beforeRange.substringAfterLast(" - ").trim()
                    if (looksLikeHostname(tail)) {
                        extractedHostname = tail
                        cleanTitle = beforeRange.substringBeforeLast(" - ").trim()
                    }
                }

                cleanTitle = cleanTitle
                    .removePrefix("•")
                    .removePrefix("-")
                    .replace(Regex("""^(?:description|item|product|产品|项目)\s*[:：]\s*""", RegexOption.IGNORE_CASE), "")
                    .trim()

                return WhmcsLineItemInfo(
                    productTitle = cleanTitle,
                    hostname = extractedHostname,
                    inlineIpv4 = extractedIp,
                    startDateIso = startIso,
                    endDateIso = endIso,
                    inferredCycle = inferredCycle,
                )
            }
        }
        return null
    }

    private fun inferCycleFromSpanDays(days: Long): BillingCyclePreset? = when (days) {
        in 27L..33L -> BillingCyclePreset.MONTHLY
        in 85L..96L -> BillingCyclePreset.QUARTERLY
        in 175L..190L -> BillingCyclePreset.SEMI_ANNUAL
        in 358L..372L -> BillingCyclePreset.ANNUAL
        in 720L..740L -> BillingCyclePreset.BIENNIAL
        in 1085L..1105L -> BillingCyclePreset.TRIENNIAL
        else -> null
    }

    private fun extractLabeledDateIso(
        lineRegex: Regex,
        text: String,
        preferDayFirst: Boolean,
    ): String? {
        if (text.isBlank()) return null
        for (match in lineRegex.findAll(text)) {
            parseFlexibleDateIso(match.groupValues[1], preferDayFirst)?.let { return it }
        }
        return null
    }

    private fun inferDayFirstPreference(
        subject: String,
        body: String,
        fromAddress: String,
    ): Boolean {
        val combined = "$subject\n$body"
        // Scan all numeric slash/dash dates in the email: if any has first > 12, the email uses DD/MM/YYYY;
        // if any has second > 12, the email uses MM/DD/YYYY.
        for (m in Regex("""\b(\d{1,2})[-/](\d{1,2})[-/](\d{4})\b""").findAll(combined)) {
            val a = m.groupValues[1].toIntOrNull() ?: continue
            val b = m.groupValues[2].toIntOrNull() ?: continue
            if (a in 13..31 && b in 1..12) return true
            if (b in 13..31 && a in 1..12) return false
        }
        val domain = fromAddress.substringAfter('@', "").lowercase(Locale.ROOT)
        if (domain.endsWith(".uk") || domain.endsWith(".de") || domain.endsWith(".eu") ||
            domain.endsWith(".fr") || domain.endsWith(".nl") || domain.endsWith(".au") ||
            domain.endsWith(".sg") || domain.endsWith(".hk")
        ) {
            return true
        }
        if (combined.contains("£") || combined.contains("€") ||
            Regex("""\b(?:GBP|EUR|AUD|SGD)\b""").containsMatchIn(combined)
        ) {
            return true
        }
        return false
    }

    private fun buildValidIsoDate(year: Int?, month: Int?, day: Int?): String? {
        val y = year ?: return null
        val m = month ?: return null
        val d = day ?: return null
        if (y !in 2000..2100 || m !in 1..12 || d !in 1..31) return null
        return runCatching { LocalDate.of(y, m, d).toString() }.getOrNull()
    }

    private fun extractSingleLineField(regex: Regex, text: String): String? {
        if (text.isBlank()) return null
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun cleanProductValue(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        var cleaned = trimmed
            .replace(WHMCS_PERIOD_RANGE_REGEX, "")
            .trim()
        // Strip trailing inline money token if Product line came from an invoice table row
        val trailingMoney = Regex(
            """\s+(?:HK\$|CA\$|AU\$|NT\$|S\$|US\$|[$€£¥￥])\s*\d+(?:[.,]\d+)?(?:\s*[A-Z]{3})?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        cleaned = cleaned.replace(trailingMoney, "").trim()
        // Strip trailing parenthesized IP or hostname if present
        val trailingParen = Regex("""\s*[(（]([^()（）]{3,80})[)）]\s*$""").find(cleaned)
        if (trailingParen != null) {
            val inside = trailingParen.groupValues[1].trim()
            if (isValidHostIpv4(inside) || looksLikeHostname(inside)) {
                cleaned = cleaned.substring(0, trailingParen.range.first).trim()
            }
        }
        return cleaned.trim(' ', '-', '–', '—', '|')
    }

    private fun cleanHostnameValue(candidate: String, ipv4: String?): String {
        val trimmed = candidate.trim().trimEnd('.')
        if (trimmed.isEmpty()) return ""
        if (trimmed == ipv4) return ""
        if (trimmed.equals("none", ignoreCase = true) || trimmed.equals("n/a", ignoreCase = true)) return ""
        return trimmed
    }

    private val HOSTNAME_TLDS = setOf(
        "com", "net", "org", "io", "cloud", "local", "host", "me", "xyz", "cc",
        "cn", "hk", "jp", "sg", "uk", "de", "eu", "us", "ca", "au", "nl", "fr",
        "info", "top", "dev", "app", "site", "online", "ps", "ws", "wiki", "lan",
    )

    private val INLINE_FQDN_REGEX = Regex(
        """(?<![@/A-Za-z0-9_-])([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?){2,})(?![A-Za-z0-9_-])""",
        RegexOption.IGNORE_CASE,
    )

    private fun extractInlineServerFqdn(text: String, fromAddress: String): String? {
        if (text.isBlank()) return null
        val senderDomain = fromAddress.substringAfter('@', "").lowercase(Locale.ROOT)
        for (match in INLINE_FQDN_REGEX.findAll(text)) {
            val start = match.range.first
            val prefix = text.substring((start - 10).coerceAtLeast(0), start).lowercase(Locale.ROOT)
            if (prefix.endsWith("://") || prefix.endsWith("@")) continue

            val candidate = match.groupValues[1].trimEnd('.')
            val lower = candidate.lowercase(Locale.ROOT)
            if (isValidHostIpv4(lower)) continue
            val firstLabel = lower.substringBefore('.')
            if (firstLabel in setOf("www", "my", "billing", "portal", "clients", "support", "mail", "ns1", "ns2")) {
                continue
            }
            if (senderDomain.isNotEmpty() && lower == senderDomain) continue
            val tld = lower.substringAfterLast('.')
            if (tld !in HOSTNAME_TLDS) continue
            return candidate
        }
        return null
    }

    private fun looksLikeHostname(candidate: String): Boolean {
        val clean = candidate.trim()
        if (clean.length !in 3..120 || clean.contains(' ')) return false
        if (isValidHostIpv4(clean)) return false
        return Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$""")
            .matches(clean) ||
            Regex("""^[A-Za-z][A-Za-z0-9-]{2,32}\d+$""").matches(clean)
    }

    private fun extractSpecsSummary(body: String, productName: String): String {
        val parts = mutableListOf<String>()
        extractSingleLineField(CPU_SPEC_LINE_REGEX, body)?.let { v ->
            parts += if (v.any { it.isLetter() }) v else "${v}C"
        }
        extractSingleLineField(RAM_SPEC_LINE_REGEX, body)?.let { parts += it }
        extractSingleLineField(DISK_SPEC_LINE_REGEX, body)?.let { parts += it }
        if (parts.isNotEmpty()) {
            return parts.joinToString(" / ")
        }

        val inlineMatches = INLINE_SPEC_TOKEN_REGEX.findAll("$productName $body")
            .map { it.value.trim() }
            .distinct()
            .take(4)
            .toList()
        return if (inlineMatches.size >= 2) inlineMatches.joinToString(" / ") else ""
    }

    private fun extractMonthlyTrafficQuotaGb(body: String): Double? {
        val line = extractSingleLineField(TRAFFIC_SPEC_LINE_REGEX, body) ?: return null
        val match = TRAFFIC_AMOUNT_REGEX.find(line) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase(Locale.ROOT)
        return when {
            unit.startsWith("T") -> value * 1024.0
            else -> value
        }
    }

    private fun extractRegion(
        primaryBody: String,
        productName: String,
        hostname: String,
        subject: String,
    ): String {
        extractSingleLineField(LOCATION_LINE_REGEX, primaryBody)?.let { loc ->
            matchRegionKeyword(loc)?.let { return it }
            if (loc.length <= 24) return loc
        }
        val combined = "$productName $hostname $subject"
        return matchRegionKeyword(combined).orEmpty()
    }

    private fun matchRegionKeyword(text: String): String? {
        if (text.isBlank()) return null
        for ((needle, normalized) in REGION_KEYWORDS) {
            if (needle.all { it in 'A'..'Z' } && needle.length <= 3) {
                if (Regex("""(?<![A-Za-z])$needle(?![A-Za-z])""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    return normalized
                }
            } else if (text.contains(needle, ignoreCase = true)) {
                return normalized
            }
        }
        return null
    }

    private fun ensureNewline(sb: StringBuilder) {
        trimTrailingInlineSpaces(sb)
        if (sb.isNotEmpty() && sb.last() != '\n') {
            sb.append('\n')
        }
    }

    private fun trimTrailingInlineSpaces(sb: StringBuilder) {
        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) {
            sb.setLength(sb.length - 1)
        }
    }

    private fun currentLineLength(sb: StringBuilder): Int {
        val lastNl = sb.lastIndexOf('\n')
        return if (lastNl < 0) sb.length else sb.length - lastNl - 1
    }

    private fun currentLineContainsSeparator(sb: StringBuilder): Boolean {
        val start = (sb.lastIndexOf('\n') + 1).coerceAtLeast(0)
        for (i in start until sb.length) {
            val ch = sb[i]
            if (ch == ':' || ch == '：') return true
        }
        return false
    }
}
