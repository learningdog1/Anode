package com.nodeseek.app.domain.ssh

/**
 * Curated catalog of non-interactive VPS benchmark & diagnostic scripts tailored for
 * headless PTY execution on NodeSeek-popular Linux servers.
 */
object SshPresetCatalog {
    const val HEADLESS_ENV_PREFIX: String =
        "export TERM=xterm-256color DEBIAN_FRONTEND=noninteractive"

    const val PRESET_QUICK_CHECK = "quick_check"
    const val PRESET_YABS = "yabs"
    const val PRESET_NODE_QUALITY = "nodequality"
    const val PRESET_NEXT_TRACE = "nexttrace"
    const val PRESET_MEDIA_UNLOCK = "media_unlock"

    private val QUICK_CHECK_SCRIPT: String = listOf(
        """printf '\033[1;36m╭────────────────────────────────────────────────────╮\033[0m\n'""",
        """printf '\033[1;36m│\033[0m  \033[1;37mAnode · VPS 系统与线路快检\033[0m                        \033[1;36m│\033[0m\n'""",
        """printf '\033[1;36m╰────────────────────────────────────────────────────╯\033[0m\n'""",
        """OS_NAME=$(. /etc/os-release 2>/dev/null && echo "${'$'}PRETTY_NAME" || uname -s)""",
        """CPU_MODEL=$(awk -F: '/model name|Model/ {gsub(/^[ \t]+/,"",${'$'}2); print ${'$'}2; exit}' /proc/cpuinfo 2>/dev/null)""",
        """[ -z "${'$'}CPU_MODEL" ] && CPU_MODEL=$(uname -m)""",
        """CPU_CORES=$(nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo 2>/dev/null || echo 1)""",
        """MEM_LINE=$(free -h 2>/dev/null | awk '/^Mem:/ {print ${'$'}3" / "${'$'}2}')""",
        """SWAP_LINE=$(free -h 2>/dev/null | awk '/^Swap:/ {print ${'$'}3" / "${'$'}2}')""",
        """DISK_LINE=$(df -h / 2>/dev/null | awk 'NR==2 {print ${'$'}3" / "${'$'}2" ("${'$'}5")"}')""",
        """KERNEL=$(uname -r)""",
        """ARCH=$(uname -m)""",
        """VIRT=$(command -v systemd-detect-virt >/dev/null 2>&1 && systemd-detect-virt 2>/dev/null || echo unknown)""",
        """TCP_CC=$(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null || echo unknown)""",
        """UPTIME_STR=$(uptime -p 2>/dev/null || uptime | awk -F'up ' '{print ${'$'}2}' | cut -d',' -f1)""",
        """LOAD_STR=$(awk '{print ${'$'}1", "${'$'}2", "${'$'}3}' /proc/loadavg 2>/dev/null)""",
        """IPV4=$(curl -s4m 3 https://api.ip.sb/ip 2>/dev/null || curl -s4m 3 https://ifconfig.me 2>/dev/null || echo N/A)""",
        """IPV6=$(curl -s6m 3 https://api.ip.sb/ip 2>/dev/null || echo N/A)""",
        """printf '\033[33m%-12s\033[0m %s\n' '主机名:' "$(hostname)" '系统版本:' "${'$'}OS_NAME" '内核架构:' "${'$'}KERNEL (${'$'}ARCH)" '虚拟化:' "${'$'}VIRT" '处理器:' "${'$'}CPU_MODEL (${'$'}CPU_CORES 核)" '内存占用:' "${'$'}MEM_LINE (Swap: ${'$'}SWAP_LINE)" '根分区:' "${'$'}DISK_LINE" '拥塞控制:' "${'$'}TCP_CC" '系统负载:' "${'$'}LOAD_STR" '运行时长:' "${'$'}UPTIME_STR" 'IPv4:' "${'$'}IPV4" 'IPv6:' "${'$'}IPV6" """,
        """printf '\033[32m✓ 快检完成\033[0m\n'""",
    ).joinToString("; ")

    val builtInPresets: List<SshScriptPreset> = listOf(
        SshScriptPreset(
            id = PRESET_QUICK_CHECK,
            name = "系统与线路快检",
            tabTitle = "⚡ 系统快检",
            category = SshPresetCategory.DIAGNOSTIC,
            description = "3 秒极速采集 CPU / 内存 / 磁盘 / 内核 / BBR / 虚拟化 / IPv4 & IPv6 概览，适合首次连通测试",
            estimatedDurationLabel = "~3 秒",
            commandTemplate = QUICK_CHECK_SCRIPT,
        ),
        SshScriptPreset(
            id = PRESET_YABS,
            name = "YABS 综合跑分",
            tabTitle = "💻 YABS 综合跑分",
            category = SshPresetCategory.BENCHMARK,
            description = "Yet-Another-Bench-Script 经典硬盘 fio、iperf3 网络与 Geekbench CPU 综合测试",
            estimatedDurationLabel = "~2-5 分钟",
            commandTemplate = "curl -sL https://yabs.sh | bash -s -- ${SshScriptPreset.FLAGS_PLACEHOLDER}",
            flags = listOf(
                SshPresetFlag(
                    id = "yabs_gb5",
                    label = "Geekbench 5 (-5)",
                    flag = "-5",
                    description = "使用轻量 Geekbench 5，内存占用更低",
                    enabledByDefault = true,
                    exclusiveGroup = "yabs_gb",
                ),
                SshPresetFlag(
                    id = "yabs_skip_gb",
                    label = "跳过 Geekbench (-g)",
                    flag = "-g",
                    description = "跳过 CPU 跑分，大幅缩短耗时",
                    enabledByDefault = false,
                    exclusiveGroup = "yabs_gb",
                ),
                SshPresetFlag(
                    id = "yabs_skip_fio",
                    label = "跳过磁盘 fio (-f)",
                    flag = "-f",
                    description = "不跑磁盘随机读写，保护小鸡 IO",
                    enabledByDefault = false,
                ),
                SshPresetFlag(
                    id = "yabs_skip_iperf",
                    label = "跳过 iperf3 (-i)",
                    flag = "-i",
                    description = "跳过国际网络测速，节省流量",
                    enabledByDefault = false,
                ),
            ),
        ),
        SshScriptPreset(
            id = PRESET_NODE_QUALITY,
            name = "NodeQuality (NQ) 质量体检",
            tabTitle = "🔬 NodeQuality 体检",
            category = SshPresetCategory.BENCHMARK,
            description = "NodeSeek 社区主流 VPS 体检脚本，一键检测基础硬件、IP 质量、流媒体与三网回程",
            estimatedDurationLabel = "~3-6 分钟",
            commandTemplate = "bash <(curl -sL https://run.NodeQuality.com) -n ${SshScriptPreset.FLAGS_PLACEHOLDER}",
            flags = listOf(
                SshPresetFlag(
                    id = "nq_low_load",
                    label = "低负载模式 (-l)",
                    flag = "-l",
                    description = "适合小内存 / 低配 VPS，减少并发压力",
                    enabledByDefault = false,
                ),
            ),
        ),
        SshScriptPreset(
            id = PRESET_NEXT_TRACE,
            name = "NextTrace 三网回程路由",
            tabTitle = "📍 NextTrace 三网回程",
            category = SshPresetCategory.NETWORK,
            description = "自动检测或安装 NextTrace，快速追踪电信 / 联通 / 移动三网回程路由与节点 ASN",
            estimatedDurationLabel = "~20-45 秒",
            commandTemplate = "(command -v nexttrace >/dev/null 2>&1 || curl -sL nxtrace.org/nt | bash) && nexttrace --fast-trace ${SshScriptPreset.FLAGS_PLACEHOLDER}",
            flags = listOf(
                SshPresetFlag(
                    id = "nt_tcp",
                    label = "TCP 路由 (--tcp)",
                    flag = "--tcp",
                    description = "使用 TCP SYN 探测，避免部分节点禁 ICMP",
                    enabledByDefault = false,
                ),
                SshPresetFlag(
                    id = "nt_ipv4",
                    label = "仅 IPv4 (--ipv4)",
                    flag = "--ipv4",
                    description = "仅执行 IPv4 三网回程追踪",
                    enabledByDefault = false,
                    exclusiveGroup = "nt_ip",
                ),
                SshPresetFlag(
                    id = "nt_ipv6",
                    label = "仅 IPv6 (--ipv6)",
                    flag = "--ipv6",
                    description = "仅执行 IPv6 三网回程追踪",
                    enabledByDefault = false,
                    exclusiveGroup = "nt_ip",
                ),
            ),
        ),
        SshScriptPreset(
            id = PRESET_MEDIA_UNLOCK,
            name = "流媒体 & AI 解锁检测",
            tabTitle = "🎬 流媒体 & AI 解锁",
            category = SshPresetCategory.UNLOCK,
            description = "非交互快速检测 Netflix / Disney+ / YouTube / ChatGPT / Claude / TikTok 等地区解锁状态",
            estimatedDurationLabel = "~30-60 秒",
            commandTemplate = "bash <(curl -L -s https://raw.githubusercontent.com/lmc999/RegionRestrictionCheck/main/check.sh) ${SshScriptPreset.FLAGS_PLACEHOLDER}",
            flags = listOf(
                SshPresetFlag(
                    id = "media_v4",
                    label = "IPv4 检测 (-M 4)",
                    flag = "-M 4",
                    description = "优先通过 IPv4 出口检测解锁状态",
                    enabledByDefault = true,
                    exclusiveGroup = "media_ip",
                ),
                SshPresetFlag(
                    id = "media_v6",
                    label = "IPv6 检测 (-M 6)",
                    flag = "-M 6",
                    description = "通过 IPv6 出口检测解锁状态",
                    enabledByDefault = false,
                    exclusiveGroup = "media_ip",
                ),
            ),
        ),
    )

    fun findBuiltIn(id: String): SshScriptPreset? = builtInPresets.firstOrNull { it.id == id }

    fun allPresets(customPresets: List<CustomSshPreset>): List<SshScriptPreset> =
        builtInPresets + customPresets.map { it.toScriptPreset() }

    /**
     * Ensures headless non-interactive environment variables (`TERM=xterm-256color`,
     * `DEBIAN_FRONTEND=noninteractive`) are exported before executing any command.
     */
    fun wrapHeadlessCommand(rawCommand: String): String {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.contains("TERM=xterm-256color") &&
            trimmed.contains("DEBIAN_FRONTEND=noninteractive")
        ) {
            trimmed
        } else {
            "$HEADLESS_ENV_PREFIX; $trimmed"
        }
    }
}
