package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

/**
 * Live execution telemetry and inferred stage metadata surfaced while an SSH benchmark runs,
 * especially during long silent intervals (such as YABS `fio` / `iperf3` / `Geekbench` or
 * `bench.sh` per-node Speedtest runs where sub-processes buffer stdout until completion).
 */
@Immutable
data class SshLiveProgressStatus(
    val elapsedMillis: Long = 0L,
    val lastOutputEpochMillis: Long? = null,
    val silenceMillis: Long = 0L,
    val isSilentLong: Boolean = false,
    val currentStageLabel: String = "准备执行",
    val stageDetailHint: String = "",
) {
    /**
     * Formatted warning/reassurance message displayed whenever [isSilentLong] is true.
     */
    val silentRunningBannerText: String
        get() = "脚本仍在运行（${currentStageLabel}可能需要数分钟）"
}

/**
 * Infers the active benchmark phase from recent terminal output and calculates how long the
 * remote PTY stream has been silent so users never mistake CPU/disk/network sub-process buffering
 * for a frozen SSH connection.
 */
object SshStageDetector {
    const val DEFAULT_SILENCE_THRESHOLD_MILLIS: Long = 8_000L
    internal const val TAIL_SCAN_CHARS: Int = 3_500

    private data class StageRule(
        val keywords: List<String>,
        val stageLabel: String,
        val detailHint: String,
    )

    fun detect(
        presetId: String?,
        command: String,
        sanitizedOutput: String,
        isConnecting: Boolean,
        startedAtEpochMillis: Long,
        lastOutputEpochMillis: Long?,
        nowEpochMillis: Long,
        silenceThresholdMillis: Long = DEFAULT_SILENCE_THRESHOLD_MILLIS,
    ): SshLiveProgressStatus {
        val elapsed = if (startedAtEpochMillis > 0L) {
            (nowEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
        } else {
            0L
        }
        val anchorMillis = lastOutputEpochMillis ?: startedAtEpochMillis
        val silence = if (anchorMillis > 0L) {
            (nowEpochMillis - anchorMillis).coerceAtLeast(0L)
        } else {
            0L
        }

        if (isConnecting) {
            return SshLiveProgressStatus(
                elapsedMillis = elapsed,
                lastOutputEpochMillis = lastOutputEpochMillis,
                silenceMillis = silence,
                isSilentLong = silence >= silenceThresholdMillis,
                currentStageLabel = "SSH 握手与加密通道建立阶段",
                stageDetailHint = "正在协商密钥并分配远程 xterm-256color 终端",
            )
        }

        val tail = stripCsiEscapesFast(sanitizedOutput.takeLast(TAIL_SCAN_CHARS))

        val (stageLabel, detailHint) = inferStage(
            presetId = presetId.orEmpty(),
            command = command,
            plainTail = tail,
        )

        return SshLiveProgressStatus(
            elapsedMillis = elapsed,
            lastOutputEpochMillis = lastOutputEpochMillis,
            silenceMillis = silence,
            isSilentLong = silence >= silenceThresholdMillis,
            currentStageLabel = stageLabel,
            stageDetailHint = detailHint,
        )
    }

    /**
     * Zero-full-string overload that inspects only the trailing window of [bufferSnapshot].
     */
    fun detectFromBuffer(
        presetId: String?,
        command: String,
        bufferSnapshot: SanitizedBufferSnapshot,
        fallbackOutput: String = "",
        isConnecting: Boolean,
        startedAtEpochMillis: Long,
        lastOutputEpochMillis: Long?,
        nowEpochMillis: Long,
        silenceThresholdMillis: Long = DEFAULT_SILENCE_THRESHOLD_MILLIS,
    ): SshLiveProgressStatus {
        val rawTail = if (!bufferSnapshot.isEmpty) {
            bufferSnapshot.tailText(TAIL_SCAN_CHARS)
        } else {
            fallbackOutput.takeLast(TAIL_SCAN_CHARS)
        }
        return detect(
            presetId = presetId,
            command = command,
            sanitizedOutput = rawTail,
            isConnecting = isConnecting,
            startedAtEpochMillis = startedAtEpochMillis,
            lastOutputEpochMillis = lastOutputEpochMillis,
            nowEpochMillis = nowEpochMillis,
            silenceThresholdMillis = silenceThresholdMillis,
        )
    }

    private fun stripCsiEscapesFast(text: String): String {
        val firstEsc = text.indexOf("\u001B[")
        if (firstEsc < 0) return text
        val sb = StringBuilder(text.length)
        var cursor = 0
        var searchFrom = firstEsc
        while (searchFrom >= 0 && searchFrom < text.length) {
            var i = searchFrom + 2
            var csiEnd = -1
            while (i < text.length) {
                val ch = text[i]
                if (ch in 'A'..'Z' || ch in 'a'..'z') {
                    csiEnd = i
                    break
                }
                if (ch !in '0'..'9' && ch != ';' && ch != ':' && ch != '?') {
                    break
                }
                i++
            }
            if (csiEnd >= 0) {
                if (searchFrom > cursor) {
                    sb.append(text, cursor, searchFrom)
                }
                cursor = csiEnd + 1
                searchFrom = text.indexOf("\u001B[", startIndex = cursor)
            } else {
                searchFrom = text.indexOf("\u001B[", startIndex = searchFrom + 2)
            }
        }
        if (cursor == 0) return text
        if (cursor < text.length) {
            sb.append(text, cursor, text.length)
        }
        return sb.toString()
    }

    private fun inferStage(
        presetId: String,
        command: String,
        plainTail: String,
    ): Pair<String, String> {
        val lowerCmd = command.lowercase()
        val isYabs = presetId == SshPresetCatalog.PRESET_YABS || lowerCmd.contains("yabs.sh")
        val isNodeQuality = presetId == SshPresetCatalog.PRESET_NODE_QUALITY || lowerCmd.contains("nodequality")
        val isEcs = presetId == SshPresetCatalog.PRESET_ECS || lowerCmd.contains("goecs") || lowerCmd.contains("ecs.sh")
        val isBench = presetId == SshPresetCatalog.PRESET_BENCH || lowerCmd.contains("bench.sh")
        val isNextTrace = presetId == SshPresetCatalog.PRESET_NEXT_TRACE || lowerCmd.contains("nexttrace") || lowerCmd.contains("backtrace")
        val isMedia = presetId == SshPresetCatalog.PRESET_MEDIA_UNLOCK ||
            lowerCmd.contains("regionrestrictioncheck") ||
            lowerCmd.contains("check.unlock.media")

        return when {
            isYabs -> inferYabsStage(plainTail)
            isNodeQuality -> inferNodeQualityStage(plainTail)
            isEcs -> inferEcsStage(plainTail)
            isBench -> inferBenchStage(plainTail)
            isNextTrace -> inferNextTraceStage(plainTail)
            isMedia -> inferMediaUnlockStage(plainTail)
            else -> inferGenericStage(plainTail)
        }
    }

    /**
     * YABS runs sequentially: System Info -> fio Disk -> iperf3 Network -> Geekbench CPU.
     * Note that YABS prints `fio Disk Speed Tests` AFTER `Preparing system for disk tests...` and
     * captures `fio`, `iperf3`, and `geekbench` stdout into bash variables, causing 30s-300s of
     * complete silence during each sub-test!
     */
    private fun inferYabsStage(plainTail: String): Pair<String, String> {
        if (plainTail.isBlank()) {
            return "YABS 脚本下载与环境检测阶段" to "正在拉取 yabs.sh 并探测连通性与架构信息"
        }
        val lower = plainTail.lowercase()
        val lastGeekbenchIdx = maxOf(
            lower.lastIndexOf("geekbench"),
            lower.lastIndexOf("performing geekbench"),
            lower.lastIndexOf("downloading geekbench"),
        )
        val lastIperfIdx = maxOf(
            lower.lastIndexOf("iperf3 network speed"),
            lower.lastIndexOf("iperf3"),
            lower.lastIndexOf("performing iperf3"),
        )
        val lastFioResultIdx = lower.lastIndexOf("fio disk speed tests")
        val lastFioPrepIdx = maxOf(
            lower.lastIndexOf("preparing system for disk tests"),
            lower.lastIndexOf("generating fio test file"),
            lower.lastIndexOf("running fio"),
        )

        // When YABS finishes iperf3 table (or skips iperf3 after fio table), it immediately starts
        // downloading & running Geekbench silently before printing "Geekbench X Benchmark Test:"!
        val hasFinishedIperfTable = lastIperfIdx >= 0 && (
            lower.indexOf("busy", startIndex = lastIperfIdx) >= 0 ||
                countPipeLinesFrom(lower, lastIperfIdx, minRequired = 3)
            )
        val hasFinishedFioTable = lastFioResultIdx >= 0 && lower.indexOf("total", startIndex = lastFioResultIdx) >= 0

        return when {
            lastGeekbenchIdx > lastIperfIdx && lastGeekbenchIdx > lastFioResultIdx ->
                "Geekbench CPU 跑分阶段" to "Geekbench 正在后台跑单核与多核计算测试，全过程无增量输出，通常需 2~5 分钟"
            hasFinishedIperfTable ->
                "Geekbench CPU 跑分阶段" to "iperf3 已结束，正在下载并运行 Geekbench CPU 基准测试（通常需 2~5 分钟）"
            lastIperfIdx > lastFioResultIdx && lastIperfIdx >= 0 ->
                "iperf3 网络测速阶段" to "正在逐节点执行国际上下行多线程测速，单节点静默约 15~30 秒"
            hasFinishedFioTable ->
                "iperf3 / Geekbench 测速跑分阶段" to "磁盘测试已完成，正在后台执行网络吞吐或 Geekbench CPU 压测"
            lastFioPrepIdx >= 0 || lower.contains("basic system information") ->
                "fio 磁盘读写测试阶段" to "fio 正在后台执行 4K / 64K / 512K / 1M 随机读写压测，期间静默约 1~2 分钟"
            else ->
                "YABS 系统信息与依赖准备阶段" to "正在采集硬件信息并准备预编译测试二进制文件"
        }
    }

    private fun inferNodeQualityStage(plainTail: String): Pair<String, String> {
        if (plainTail.isBlank()) {
            return "NodeQuality 启动与沙箱拉取阶段" to "正在连接 NodeQuality 并初始化无痕测试目录"
        }
        val rules = listOf(
            StageRule(
                keywords = listOf("安装后清理", "Clean Up after", "清理中"),
                stageLabel = "报告上传与沙箱清理阶段",
                detailHint = "正在汇总测评结果并卸载临时无痕环境",
            ),
            StageRule(
                keywords = listOf("正在运行回程路由", "Running Backroute", "回程路由追踪"),
                stageLabel = "三网回程路由追踪阶段",
                detailHint = "正在探测电信 / 联通 / 移动回程跳点与线路类型",
            ),
            StageRule(
                keywords = listOf("正在运行网络质量", "Running Network Quality", "BGP", "三网"),
                stageLabel = "网络质量与三网测速阶段",
                detailHint = "正在测试海内外节点连接速率与延迟，可能持续 1~3 分钟",
            ),
            StageRule(
                keywords = listOf("正在运行 IP 质量", "Running IP Quality", "欺诈", "原生"),
                stageLabel = "IP 质量与流媒体解锁阶段",
                detailHint = "正在并发查询多库欺诈评分、原生属性及流媒体/AI 解锁状态",
            ),
            StageRule(
                keywords = listOf("正在运行硬件质量", "Running Hardware Quality", "硬件信息", "Hardware Info"),
                stageLabel = "硬件性能基准测试阶段",
                detailHint = "正在沙箱中测试 CPU 算力、内存带宽与磁盘 IOPS",
            ),
            StageRule(
                keywords = listOf("加载 BenchOs", "Load BenchOs", "安装前清理", "Clean Up before"),
                stageLabel = "无痕沙箱镜像下载与挂载阶段",
                detailHint = "正在下载并解压 BenchOs 临时系统镜像，海外或慢速线路可能需 1~2 分钟",
            ),
        )
        return matchLatestRule(plainTail, rules)
            ?: ("NodeQuality 综合体检阶段" to "正在无痕沙箱内执行硬件、IP 与线路质量测试")
    }

    private fun inferEcsStage(plainTail: String): Pair<String, String> {
        if (plainTail.isBlank()) {
            return "融合怪引擎下载与初始化阶段" to "正在获取 goecs 二进制组件并校验运行环境"
        }
        val rules = listOf(
            StageRule(
                keywords = listOf("测速", "Speed Test", "上行", "下行", "Mbps"),
                stageLabel = "三网与国际节点测速阶段",
                detailHint = "正在执行多运营商上下行带宽测试，单节点静默约 15~30 秒",
            ),
            StageRule(
                keywords = listOf("三网回程", "回程路由", "traceroute", "AS4809", "AS9929", "CMIN2"),
                stageLabel = "三网回程与路由追踪阶段",
                detailHint = "正在并发探测北上广成三网回程线路及跳点",
            ),
            StageRule(
                keywords = listOf("邮件端口", "Email Port", "SMTP", "25"),
                stageLabel = "邮件端口连通性检测阶段",
                detailHint = "正在探测常用邮件服务端口封锁状态",
            ),
            StageRule(
                keywords = listOf("流媒体", "Streaming", "Netflix", "Disney", "ChatGPT", "TikTok"),
                stageLabel = "流媒体与 AI 解锁检测阶段",
                detailHint = "正在并发探测各大跨国流媒体与 AI 平台解锁地区",
            ),
            StageRule(
                keywords = listOf("IP 质量", "安全", "Security", "欺诈", "黑名单"),
                stageLabel = "IP 安全信誉多库检测阶段",
                detailHint = "正在请求多源安全与数据库评估 IP 纯净度",
            ),
            StageRule(
                keywords = listOf("磁盘", "Disk", "fio", "4K", "1M"),
                stageLabel = "磁盘 I/O 读写压测阶段",
                detailHint = "正在测试随机与顺序磁盘吞吐性能，可能静默 30~90 秒",
            ),
            StageRule(
                keywords = listOf("内存", "Memory", "STREAM", "sysbench"),
                stageLabel = "CPU 与内存基准压测阶段",
                detailHint = "正在执行多核 CPU 算力与内存读写带宽压测",
            ),
            StageRule(
                keywords = listOf("starting in 5 seconds", "Installing", "CDN", "Detecting IP"),
                stageLabel = "融合怪组件安装准备阶段",
                detailHint = "正在自动选择镜像源并部署无人值守测试二进制",
            ),
        )
        return matchLatestRule(plainTail, rules)
            ?: ("融合怪综合测评阶段" to "正在执行硬件、IP、解锁与三网回程全量测试")
    }

    private fun inferBenchStage(plainTail: String): Pair<String, String> {
        if (plainTail.isBlank()) {
            return "Bench.sh 启动与系统采集阶段" to "正在获取 bench.sh 并读取基础硬件与网络信息"
        }
        val lower = plainTail.lowercase()
        return when {
            lower.contains("node name") || lower.contains("speedtest") || lower.contains("upload speed") ->
                "全球 Speedtest 节点测速阶段" to "正在逐个节点执行上下行测速（每节点输出前会静默约 15~30 秒）"
            lower.contains("i/o speed") ->
                "三次 DD 磁盘顺序读写阶段" to "正在执行大文件顺序写入同步压测，请稍候"
            else ->
                "系统硬件与网络环境采集阶段" to "正在采集 CPU、内存、磁盘及公网网络概览"
        }
    }

    private fun inferNextTraceStage(plainTail: String): Pair<String, String> {
        val lower = plainTail.lowercase()
        return when {
            lower.contains("traceroute to") || plainTail.contains("电信") || plainTail.contains("联通") || plainTail.contains("移动") ->
                "三网回程跳点追踪阶段" to "正在逐跳探测 ICMP/TCP 延迟与运营商骨干网 ASN"
            lower.contains("install") || plainTail.contains("==>") ->
                "NextTrace 组件自动安装阶段" to "首次运行正在从官方镜像安装 NextTrace 二进制文件"
            else ->
                "回程路由初始化阶段" to "正在准备三网测试目标节点与 GeoIP 数据通道"
        }
    }

    private fun inferMediaUnlockStage(plainTail: String): Pair<String, String> {
        val lower = plainTail.lowercase()
        return when {
            lower.contains("netflix") || lower.contains("disney") || lower.contains("youtube") ||
                lower.contains("chatgpt") || plainTail.contains("正在测试") || lower.contains("checking results") ->
                "流媒体与 AI 平台逐项探测阶段" to "正在依次请求各流媒体与 AI 服务端点，部分超时节点需等待 10 秒重试"
            else ->
                "解锁规则加载与连通性预检阶段" to "正在下载地区映射表并验证 IPv4 / IPv6 出口连通性"
        }
    }

    private fun inferGenericStage(plainTail: String): Pair<String, String> {
        val lower = plainTail.lowercase()
        return when {
            lower.contains("geekbench") ->
                "Geekbench CPU 跑分阶段" to "CPU 基准计算进行中，完成前通常无增量输出"
            lower.contains("fio") ->
                "fio 磁盘压测阶段" to "磁盘随机读写测试进行中"
            lower.contains("iperf") || lower.contains("speedtest") ->
                "网络带宽测速阶段" to "节点吞吐测速进行中"
            lower.contains("traceroute") || lower.contains("nexttrace") ->
                "路由追踪阶段" to "逐跳网络路径探测进行中"
            else ->
                "远程脚本执行阶段" to "远程进程正在后台运行并等待下一批终端输出"
        }
    }

    private fun matchLatestRule(plainTail: String, rules: List<StageRule>): Pair<String, String>? {
        var bestIdx = -1
        var bestPair: Pair<String, String>? = null
        for (rule in rules) {
            for (kw in rule.keywords) {
                val idx = plainTail.lastIndexOf(kw, ignoreCase = true)
                if (idx > bestIdx) {
                    bestIdx = idx
                    bestPair = rule.stageLabel to rule.detailHint
                }
            }
        }
        return bestPair
    }

    private fun countPipeLinesFrom(text: String, startIndex: Int, minRequired: Int): Boolean {
        var pipeLines = 0
        var lineHasPipe = false
        var i = startIndex
        while (i < text.length) {
            when (text[i]) {
                '|' -> lineHasPipe = true
                '\n' -> {
                    if (lineHasPipe) {
                        pipeLines++
                        if (pipeLines >= minRequired) return true
                        lineHasPipe = false
                    }
                }
            }
            i++
        }
        if (lineHasPipe) pipeLines++
        return pipeLines >= minRequired
    }
}
