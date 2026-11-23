package com.nodeseek.app.domain.overseas

/**
 * Always-available, zero-network offline VPS-domain English-to-Chinese terminology engine
 * and post-processor for online machine translation output, used by one-click forum reposting.
 */
object VpsGlossaryTranslator {

    private data class TermRule(
        val regex: Regex,
        val replacement: String,
    )

    // Ordered from multi-word / specific phrases down to shorter terms to avoid partial clobbering.
    // Lookaheads keep rules idempotent if invoked on partially translated text.
    private val OFFLINE_PHRASE_RULES: List<TermRule> = listOf(
        // Common OP section headers & CTAs
        rule("""\bnetwork\s+information\b""", "网络与测速信息"),
        rule("""\bnetwork\s+info\b""", "网络信息"),
        rule("""\blooking\s+glass\b(?!\s*测速)""", "Looking Glass 测速面板"),
        rule("""\btest\s+ipv4\b""", "测试 IPv4"),
        rule("""\btest\s+ipv6\b""", "测试 IPv6"),
        rule("""\btest\s+ips?\b""", "测试 IP"),
        rule("""\btest\s+files?\b""", "测速文件"),
        rule("""\bspeed\s*test\b""", "测速"),
        rule("""\bkey\s+features\b""", "核心特性"),
        rule("""\bmain\s+features\b""", "主要特性"),
        rule("""\bfeatures\b""", "核心特性"),
        rule("""\bspecifications\b""", "配置规格"),
        rule("""\bhardware\s+specs?\b""", "硬件规格"),
        rule("""\bterms\s+of\s+service\b""", "服务条款"),
        rule("""\bacceptable\s+use\s+policy\b""", "合理使用政策"),
        rule("""\bpayment\s+methods?\b""", "支付方式"),
        rule("""\bavailable\s+locations?\b""", "可选机房"),
        rule("""\bdatacenter\s+locations?\b""", "数据中心机房"),
        rule("""\bmoney[\-\s]+back\s+guarantee\b""", "退款保证"),
        rule("""\bclick\s+here\s+to\s+order\b""", "点击立即下单"),
        rule("""\border\s+now\b""", "立即下单"),
        rule("""\border\s+here\b""", "点此下单"),
        rule("""\bbuy\s+now\b""", "立即购买"),
        rule("""\border\s+link\b""", "下单链接"),
        rule("""\bclick\s+here\b""", "点击直达"),

        // Promo & Stock phrases
        rule("""\bblack\s+friday\b""", "黑五"),
        rule("""\bcyber\s+monday\b""", "网一大促"),
        rule("""\bflash\s+sales?\b""", "限时闪购"),
        rule("""\bflash\s+deals?\b""", "限时闪购"),
        rule("""\bdouble\s+bandwidth\b""", "双倍流量"),
        rule("""\b2x\s+bandwidth\b""", "双倍流量"),
        rule("""\bdouble\s+ram\b""", "双倍内存"),
        rule("""\bdouble\s+disk\b""", "双倍硬盘"),
        rule("""\bdouble\s+storage\b""", "双倍硬盘"),
        rule("""\bout\s+of\s+stock\b""", "已缺货"),
        rule("""\bsold\s+out\b""", "已售罄"),
        rule("""\bback\s+in\s+stock\b""", "已补货"),
        rule("""\brestocked\b""", "已补货"),
        rule("""\bin\s+stock\b""", "有货"),
        rule("""\blimited\s+stock\b""", "限量发售"),
        rule("""\blimited\s+quantity\b""", "限量发售"),
        rule("""\blimited\s+time\s+offers?\b""", "限时特惠"),
        rule("""\blimited\s+time\b""", "限时"),
        rule("""\bspecial\s+plans?\b""", "特惠套餐"),
        rule("""\bspecial\s+offers?\b""", "特惠套餐"),
        rule("""\bexclusive\s+offers?\b""", "专属特惠"),
        rule("""\bexclusive\s+deals?\b""", "专属优惠"),
        rule("""\bpromo(?:tion)?\s+codes?\b""", "优惠码"),
        rule("""\bcoupon\s+codes?\b""", "优惠码"),
        rule("""\bdiscount\s+codes?\b""", "优惠码"),
        rule("""\bvoucher\s+codes?\b""", "优惠码"),
        rule("""\bcoupons?\b""", "优惠码"),
        rule("""\buse\s+code\b""", "使用优惠码"),
        rule("""\brecurring\s+discount\b""", "续费同价折扣"),
        rule("""\brecurring\b""", "续费同价"),
        rule("""\blifetime\s+discount\b""", "永久循环折扣"),
        rule("""\blifetime\b""", "永久"),
        rule("""\bstarting\s+(?:at|from)\b""", "低至"),
        rule("""\bfrom\s+(?=[$€£¥])""", "低至 "),
        rule("""\bup\s+to\s+(\d+%)\s+off\b""", "最高立减 $1"),
        rule("""\b(\d+%)\s+off\b""", "立减 $1"),
        rule("""\bcomment\s+(?:your\s+)?order\s+(?:number|id|#)\b""", "回帖附订单号"),
        rule("""\bopen\s+a\s+ticket\b""", "提交工单"),
        rule("""\binstant\s+setup\b""", "即时开通"),
        rule("""\binstant\s+deployment\b""", "即时部署"),
        rule("""\bauto[\-\s]?backups?\b""", "自动备份"),
        rule("""\bfree\s+backups?\b""", "免费备份"),
        rule("""\bddos\s+protection\b(?!\s*防护)""", "DDoS 防护"),
        rule("""\bbgp\s+sessions?\b(?!\s*会话)""", "BGP 会话"),
        rule("""\bdedicated\s+servers?\b""", "独立服务器"),
        rule("""\bbare\s+metal\b""", "裸金属独服"),
        rule("""\bstorage\s+vps\b(?!\s*\()""", "大盘鸡 (存储型 VPS)"),
        rule("""\bnvme\s+vps\b""", "NVMe 固态 VPS"),
        rule("""\bkvm\s+vps\b""", "KVM 架构 VPS"),
        rule("""\bcloud\s+vps\b""", "云主机 VPS"),
        rule("""\bdedicated\s+cpu\b""", "独享 CPU"),
        rule("""\bshared\s+cpu\b""", "共享 CPU"),
        rule("""\bdedicated\b""", "独享"),
        rule("""\bunmetered\s+(?:bandwidth|traffic|transfer)\b""", "不限流量"),
        rule("""\bunlimited\s+(?:bandwidth|traffic|transfer)\b""", "不限流量"),
        rule("""\bunmetered\b""", "不限流量"),
        rule("""\bfair\s+share\b""", "合理使用"),
        rule("""([$€£¥]\s*\d+(?:\.\d+)?)\s*/\s*(?:yr|year)\b""", "$1/年"),
        rule("""([$€£¥]\s*\d+(?:\.\d+)?)\s*/\s*(?:mo|month)\b""", "$1/月"),
        rule("""([$€£¥]\s*\d+(?:\.\d+)?)\s*/\s*(?:qtr|quarter)\b""", "$1/季"),
        rule("""\b(\d+(?:\.\d+)?)\s*(GB|MB)\s+RAM\b""", "$1$2 内存"),
        rule("""\bbandwidth\b""", "流量"),
        rule("""\btraffic\b""", "流量"),
        rule("""\btransfer\b""", "流量"),
        rule("""\bmemory\b""", "内存"),
        rule("""\bdisk\s+space\b""", "硬盘空间"),
        rule("""\bstorage\b""", "存储"),
        rule("""\bnvme\s+ssd\b(?!\s*固态)""", "NVMe 固态"),
        rule("""\bnvme\b(?!\s*固态)""", "NVMe 固态"),
        rule("""\braid[\-\s]?10\b(?!\s*阵列)""", "RAID-10 阵列"),
        rule("""\bamd\s+ryzen\b""", "AMD 锐龙 Ryzen"),
        rule("""\bamd\s+epyc\b""", "AMD 霄龙 EPYC"),
        rule("""\bintel\s+xeon\b""", "Intel 至强 Xeon"),
        rule("""\bport\s+speed\b""", "端口速率"),
        rule("""\bnetwork\s+port\b""", "网络端口"),
        rule("""\bmultiple\s+locations\b""", "多机房可选"),
        rule("""\blocations?\b""", "机房"),
        rule("""\bdatacenter\b""", "数据中心"),
        rule("""\bdata\s+center\b""", "数据中心"),
        rule("""\bmonthly\b""", "月付"),
        rule("""\bquarterly\b""", "季付"),
        rule("""\bsemi[\-\s]?annually\b""", "半年付"),
        rule("""\bannually\b""", "年付"),
        rule("""\byearly\b""", "年付"),
        rule("""\bbiennial(?:ly)?\b""", "两年付"),
        rule("""\btriennial(?:ly)?\b""", "三年付"),
        rule("""\bper\s+year\b""", "每年"),
        rule("""\bper\s+month\b""", "每月"),
        rule("""\bfree\b""", "免费"),
        rule("""\bdeals?\b""", "优惠"),
        rule("""\boffers?\b""", "套餐"),
        rule("""\bsales?\b""", "促销"),
        rule("""\bplans?\b""", "套餐"),
        rule("""\bservers?\b""", "服务器"),

        // Datacenter cities & regions
        rule("""\blos\s+angeles\b""", "洛杉矶"),
        rule("""\bsan\s+jose\b""", "圣何塞"),
        rule("""\bnew\s+york\b""", "纽约"),
        rule("""\bnew\s+jersey\b""", "新泽西"),
        rule("""\bseattle\b""", "西雅图"),
        rule("""\bdallas\b""", "达拉斯"),
        rule("""\bmiami\b""", "迈阿密"),
        rule("""\bchicago\b""", "芝加哥"),
        rule("""\bashburn\b""", "阿什本"),
        rule("""\batlanta\b""", "亚特兰大"),
        rule("""\bphoenix\b""", "凤凰城"),
        rule("""\bsalt\s+lake\s+city\b""", "盐湖城"),
        rule("""\blas\s+vegas\b""", "拉斯维加斯"),
        rule("""\bhong\s+kong\b""", "香港"),
        rule("""\bsingapore\b""", "新加坡"),
        rule("""\btokyo\b""", "东京"),
        rule("""\bosaka\b""", "大阪"),
        rule("""\bseoul\b""", "首尔"),
        rule("""\btaipei\b""", "台北"),
        rule("""\bfrankfurt\b""", "法兰克福"),
        rule("""\bamsterdam\b""", "阿姆斯特丹"),
        rule("""\blondon\b""", "伦敦"),
        rule("""\bzurich\b""", "苏黎世"),
        rule("""\bvienna\b""", "维也纳"),
        rule("""\bparis\b""", "巴黎"),
        rule("""\bstockholm\b""", "斯德哥尔摩"),
        rule("""\bwarsaw\b""", "华沙"),
        rule("""\bfalkenstein\b""", "法尔肯施泰因"),
        rule("""\bnuremberg\b""", "纽伦堡"),
        rule("""\bhelsinki\b""", "赫尔辛基"),
        rule("""\bsydney\b""", "悉尼"),
        rule("""\bmelbourne\b""", "墨尔本"),
        rule("""\btoronto\b""", "多伦多"),
        rule("""\bmontreal\b""", "蒙特利尔"),
        rule("""\bvancouver\b""", "温哥华"),
        rule("""\bunited\s+states\b""", "美国"),
        rule("""\bunited\s+kingdom\b""", "英国"),
        rule("""\bnetherlands\b""", "荷兰"),
        rule("""\bgermany\b""", "德国"),
        rule("""\bjapan\b""", "日本"),
        rule("""\bcanada\b""", "加拿大"),
        rule("""\baustralia\b""", "澳大利亚"),
        rule("""\bswitzerland\b""", "瑞士"),
        rule("""\bpoland\b""", "波兰"),
        rule("""\bfrance\b""", "法国"),
        rule("""\beurope\b""", "欧洲"),
        rule("""\bsoftbank\b""", "软银线路"),
        exactCaseRule("""\bUSA\b""", "美国"),
        exactCaseRule("""\bUS\b""", "美国"),
        exactCaseRule("""\bUK\b""", "英国"),
        exactCaseRule("""\bEU\b""", "欧洲"),
        exactCaseRule("""\bNL\b""", "荷兰"),
        exactCaseRule("""\bDE\b""", "德国"),
        exactCaseRule("""\bJP\b""", "日本"),
        exactCaseRule("""\bSG\b""", "新加坡"),
        exactCaseRule("""\bHK\b""", "香港"),
        exactCaseRule("""\bLAX\b""", "洛杉矶"),
        exactCaseRule("""\bSJC\b""", "圣何塞"),
        exactCaseRule("""\bFRA\b""", "法兰克福"),
        exactCaseRule("""\bAMS\b""", "阿姆斯特丹"),
        exactCaseRule("""\bNYC\b""", "纽约"),

        // Remaining order action prefix (e.g. "Order LA" / "Order洛杉矶" -> "立即下单 洛杉矶")
        rule("""\border\s+(?=[A-Za-z\u4E00-\u9FFF])""", "立即下单 "),
    )

    // Fixes literal machine-translation awkwardness produced by Google GTX on VPS hosting text.
    private val GTX_POST_PROCESS_RULES: List<TermRule> = listOf(
        rule("""反复出现|经常性|循环计费|定期折扣""", "续费同价"),
        rule("""未计量(?:带宽|流量)?""", "不限流量"),
        rule("""闪光销售|闪卖|闪电销售""", "限时闪购"),
        rule("""网络星期一""", "网一"),
        rule("""双带宽""", "双倍流量"),
        rule("""存储\s*VPS(?!\s*\))""", "大盘鸡 (存储型 VPS)"),
        rule("""虚拟专用服务器""", "VPS"),
        rule("""优惠券代码|促销代码|折扣代码""", "优惠码"),
        rule("""缺货了|无库存""", "已缺货"),
        rule("""打开(?:一张)?(?:门票|票据|车票)""", "提交工单"),
        rule("""(?:发表)?评论(?:您的)?订单号""", "回帖附订单号"),
        rule("""专门的\s*CPU|专用\s*CPU""", "独享 CPU"),
        rule("""窥镜""", "Looking Glass 测速面板"),
    )

    private val PROTECTED_MARKDOWN_SPAN_REGEX = Regex(
        """!\[[^\]]*\]\([^\)\s]+\)|`[^`\n]+`|\]\([^\)\s]+\)|https?://[^\s<>)\]]+""",
    )

    private fun rule(pattern: String, replacement: String): TermRule =
        TermRule(Regex(pattern, RegexOption.IGNORE_CASE), replacement)

    private fun exactCaseRule(pattern: String, replacement: String): TermRule =
        TermRule(Regex(pattern), replacement)

    fun containsCjk(text: String): Boolean {
        for (i in text.indices) {
            val ch = text[i]
            if (ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF') {
                return true
            }
        }
        return false
    }

    fun containsAsciiWords(text: String): Boolean =
        Regex("""[A-Za-z]{3,}""").containsMatchIn(text)

    fun translateTitleOffline(englishTitle: String): String {
        val trimmed = englishTitle.trim()
        if (trimmed.isEmpty()) return ""
        return applyGlossaryRules(trimmed)
    }

    /**
     * Offline glossary translation for Markdown text while preserving fenced code blocks,
     * inline code spans, Markdown images, and URL targets.
     */
    fun translateMarkdownOffline(markdown: String): String {
        if (markdown.isBlank()) return ""
        val lines = markdown.lines()
        val out = ArrayList<String>(lines.size)
        var insideFence = false

        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                insideFence = !insideFence
                out += line
                continue
            }
            if (insideFence || line.isBlank()) {
                out += line
                continue
            }
            out += translateLinePreservingSpans(line)
        }
        return out.joinToString("\n")
    }

    fun enhanceMachineTranslation(rawChinese: String): String {
        var text = rawChinese.trim()
        if (text.isEmpty()) return ""
        GTX_POST_PROCESS_RULES.forEach { rule ->
            text = rule.regex.replace(text, rule.replacement)
        }
        text = text.replace(Regex("""\*\*\s+([^*\n]+?)\s+\*\*"""), "**$1**")
        return text.replace(Regex("""[ \t]{2,}"""), " ").trim()
    }

    private fun translateLinePreservingSpans(line: String): String {
        val placeholders = ArrayList<String>()
        val masked = PROTECTED_MARKDOWN_SPAN_REGEX.replace(line) { match ->
            val idx = placeholders.size
            placeholders += match.value
            "⟪$idx⟫"
        }
        var translated = applyGlossaryRules(masked)
        placeholders.forEachIndexed { idx, original ->
            translated = translated.replace("⟪$idx⟫", original)
        }
        return translated
    }

    private fun applyGlossaryRules(input: String): String {
        var current = input
        OFFLINE_PHRASE_RULES.forEach { rule ->
            current = rule.regex.replace(current, rule.replacement)
        }
        return current
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
