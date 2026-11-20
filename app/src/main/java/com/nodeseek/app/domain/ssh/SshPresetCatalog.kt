package com.nodeseek.app.domain.ssh

/**
 * Curated catalog of non-interactive VPS benchmark & diagnostic scripts tailored for
 * headless PTY execution on NodeSeek-popular Linux servers.
 *
 * Every built-in preset has been audited against its official upstream repository to ensure
 * zero blocking interactive prompts (`read` / TUI menus / region pickers) and line-buffered
 * streaming where supported.
 */
object SshPresetCatalog {
    const val HEADLESS_ENV_PREFIX: String = SshCommandRewriter.HEADLESS_ENV_PREFIX

    const val PRESET_QUICK_CHECK = "quick_check"
    const val PRESET_ECS = "ecs"
    const val PRESET_YABS = "yabs"
    const val PRESET_BENCH = "bench_sh"
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
            tabTitle = "系统快检",
            category = SshPresetCategory.DIAGNOSTIC,
            description = "3 秒极速采集 CPU / 内存 / 磁盘 / 内核 / BBR / 虚拟化 / IPv4 & IPv6 概览，适合首次连通测试",
            estimatedDurationLabel = "~3 秒",
            commandTemplate = QUICK_CHECK_SCRIPT,
        ),
        SshScriptPreset(
            id = PRESET_ECS,
            name = "融合怪 (GoECS) 全能体检",
            tabTitle = "融合怪测评",
            category = SshPresetCategory.BENCHMARK,
            description = "oneclickvirt/ecs 全套硬件、IP 安全、流媒体、邮件端口、三网回程与测速无人值守综合测评",
            estimatedDurationLabel = "~4-8 分钟",
            // Upstream oneclickvirt/ecs: noninteractive=true skips goecs.sh mirror/env prompts;
            // -menu=false is required by goecs.go to bypass the interactive TUI menu.
            // Uses if/elif/else so a non-zero exit code from goecs never triggers a duplicate run.
            commandTemplate = "export noninteractive=true && (curl -fsSL https://cdn.spiritlhl.net/https://raw.githubusercontent.com/oneclickvirt/ecs/master/goecs.sh -o goecs.sh || curl -fsSL https://raw.githubusercontent.com/oneclickvirt/ecs/master/goecs.sh -o goecs.sh) && chmod +x goecs.sh && ./goecs.sh install && if command -v goecs >/dev/null 2>&1; then goecs -menu=false ${SshScriptPreset.FLAGS_PLACEHOLDER}; elif [ -x ./goecs ]; then ./goecs -menu=false ${SshScriptPreset.FLAGS_PLACEHOLDER}; else /root/goecs -menu=false ${SshScriptPreset.FLAGS_PLACEHOLDER}; fi",
            flags = listOf(
                SshPresetFlag(
                    id = "ecs_no_speed",
                    label = "跳过三网测速 (-speed=false)",
                    flag = "-speed=false",
                    description = "跳过上下行带宽压测，节省流量并缩短耗时",
                    enabledByDefault = false,
                ),
                SshPresetFlag(
                    id = "ecs_no_unlock",
                    label = "跳过流媒体 (-ut=false)",
                    flag = "-ut=false",
                    description = "跳过跨国流媒体平台解锁探测",
                    enabledByDefault = false,
                ),
                SshPresetFlag(
                    id = "ecs_no_nt3",
                    label = "跳过三网路由 (-nt3=false)",
                    flag = "-nt3=false",
                    description = "跳过详细三网回程跳点追踪（保留简要回程线路）",
                    enabledByDefault = false,
                ),
                SshPresetFlag(
                    id = "ecs_disk_dd",
                    label = "轻量 DD 磁盘 (-diskm dd)",
                    flag = "-diskm dd",
                    description = "使用 DD 代替 fio 测试磁盘，减轻小鸡 IO 负担",
                    enabledByDefault = false,
                ),
                SshPresetFlag(
                    id = "ecs_lang_en",
                    label = "英文输出 (-l en)",
                    flag = "-l en",
                    description = "切换英文报告输出",
                    enabledByDefault = false,
                ),
            ),
        ),
        SshScriptPreset(
            id = PRESET_YABS,
            name = "YABS 综合跑分",
            tabTitle = "YABS 跑分",
            category = SshPresetCategory.BENCHMARK,
            description = "Yet-Another-Bench-Script 经典硬盘 fio、iperf3 网络与 Geekbench CPU 综合测试",
            estimatedDurationLabel = "~2-5 分钟",
            // Uses stdbuf -oL -eL when available to reduce stdio block buffering during piped execution
            commandTemplate = "curl -sL https://yabs.sh | ${SshCommandRewriter.STDBUF_INLINE_HELPER} bash -s -- ${SshScriptPreset.FLAGS_PLACEHOLDER}",
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
                SshPresetFlag(
                    id = "yabs_reduce_net",
                    label = "精简测速节点 (-r)",
                    flag = "-r",
                    description = "仅测试 3 个核心 iperf3 节点，减少耗时与流量消耗",
                    enabledByDefault = false,
                ),
            ),
        ),
        SshScriptPreset(
            id = PRESET_BENCH,
            name = "Bench.sh 磁盘与全球测速",
            tabTitle = "Bench.sh",
            category = SshPresetCategory.BENCHMARK,
            description = "秋水逸冰经典脚本，自动采集系统硬件参数、三次 DD 顺序磁盘读写与全球多节点带宽",
            estimatedDurationLabel = "~2-4 分钟",
            commandTemplate = "curl -Lso- https://bench.sh | ${SshCommandRewriter.STDBUF_INLINE_HELPER} bash",
        ),
        SshScriptPreset(
            id = PRESET_NODE_QUALITY,
            name = "NodeQuality (NQ) 质量体检",
            tabTitle = "NodeQuality",
            category = SshPresetCategory.BENCHMARK,
            description = "NodeSeek 社区主流无痕沙箱体检脚本，一键检测基础硬件、IP 质量、流媒体与三网回程",
            estimatedDurationLabel = "~3-6 分钟",
            // Upstream LloydAsp/NodeQuality: getopts only accepts "D:d:46Ee", while ask_question()
            // reads 4 stdin lines: HQ [y/f/v/n], IQ [y/n], NQ [y/l/n], BT [y/n].
            commandTemplate = "NQ_HQ=y; NQ_NQ=y; NQ_ARGS=''; for a in ${SshScriptPreset.FLAGS_PLACEHOLDER}; do case \"${'$'}a\" in -f) NQ_HQ=f;; -l) NQ_NQ=l;; -4|-6|-E) NQ_ARGS=\"${'$'}NQ_ARGS ${'$'}a\";; esac; done; printf '%s\\ny\\n%s\\ny\\n' \"${'$'}NQ_HQ\" \"${'$'}NQ_NQ\" | bash <(curl -sL https://run.NodeQuality.com) ${'$'}NQ_ARGS",
            flags = listOf(
                SshPresetFlag(
                    id = "nq_low_load",
                    label = "低流量模式 (-l)",
                    flag = "-l",
                    description = "网络质量阶段自动应答 l（低流量测速模式），节省 VPS 流量",
                    enabledByDefault = false,
                ),
                SshPresetFlag(
                    id = "nq_fast_hw",
                    label = "快速硬件模式 (-f)",
                    flag = "-f",
                    description = "硬件测试阶段自动应答 f（快速模式），缩短跑分等待",
                    enabledByDefault = false,
                ),
                SshPresetFlag(
                    id = "nq_ipv4",
                    label = "仅 IPv4 (-4)",
                    flag = "-4",
                    description = "仅通过 IPv4 执行 IP 质量与回程检测",
                    enabledByDefault = false,
                    exclusiveGroup = "nq_ip",
                ),
                SshPresetFlag(
                    id = "nq_ipv6",
                    label = "仅 IPv6 (-6)",
                    flag = "-6",
                    description = "仅通过 IPv6 执行 IP 质量与回程检测",
                    enabledByDefault = false,
                    exclusiveGroup = "nq_ip",
                ),
            ),
        ),
        SshScriptPreset(
            id = PRESET_NEXT_TRACE,
            name = "NextTrace 三网回程路由",
            tabTitle = "NextTrace 回程",
            category = SshPresetCategory.NETWORK,
            description = "自动检测或无交互安装 NextTrace，快速追踪电信 / 联通 / 移动三网回程路由与节点 ASN",
            estimatedDurationLabel = "~20-45 秒",
            // Upstream nxtrace/NTrace-core: --fast-trace prompts twice on stdin (1=IPv4/2=IPv6, 1..8=ISP scope)
            commandTemplate = "export PATH=\"${'$'}PATH:/usr/local/bin:${'$'}HOME/.local/bin\"; (command -v nexttrace >/dev/null 2>&1 || curl -fsSL https://nxtrace.org/nt | bash) && printf '%s\\n1\\n' \"$(case \" ${SshScriptPreset.FLAGS_PLACEHOLDER} \" in *\" --ipv6 \"*) echo 2;; *) echo 1;; esac)\" | nexttrace --fast-trace ${SshScriptPreset.FLAGS_PLACEHOLDER}",
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
                    description = "执行 IPv4 三网回程追踪",
                    enabledByDefault = false,
                    exclusiveGroup = "nt_ip",
                ),
                SshPresetFlag(
                    id = "nt_ipv6",
                    label = "仅 IPv6 (--ipv6)",
                    flag = "--ipv6",
                    description = "执行 IPv6 三网回程追踪",
                    enabledByDefault = false,
                    exclusiveGroup = "nt_ip",
                ),
            ),
        ),
        SshScriptPreset(
            id = PRESET_MEDIA_UNLOCK,
            name = "流媒体 & AI 解锁检测",
            tabTitle = "流媒体解锁",
            category = SshPresetCategory.UNLOCK,
            description = "非交互快速检测 Netflix / Disney+ / YouTube / ChatGPT / Claude / TikTok 等地区解锁状态",
            estimatedDurationLabel = "~30-60 秒",
            // Upstream lmc999/RegionRestrictionCheck: -R <region> skips interactive inputOptions();
            // printf '\n' provides an extra stdin guard if all region flags are deselected.
            commandTemplate = "printf '\\n' | bash <(curl -L -s https://raw.githubusercontent.com/lmc999/RegionRestrictionCheck/main/check.sh) ${SshScriptPreset.FLAGS_PLACEHOLDER}",
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
                SshPresetFlag(
                    id = "media_region_global",
                    label = "跨国平台 + AI (-R 0)",
                    flag = "-R 0",
                    description = "直测 Netflix / Disney+ / YouTube / TikTok / ChatGPT / Claude 等跨国平台，速度最快",
                    enabledByDefault = true,
                    exclusiveGroup = "media_region",
                ),
                SshPresetFlag(
                    id = "media_region_all",
                    label = "全区域全部平台 (-R 66)",
                    flag = "-R 66",
                    description = "覆盖台港澳日韩欧美等全部地区流媒体，耗时较长",
                    enabledByDefault = false,
                    exclusiveGroup = "media_region",
                ),
                SshPresetFlag(
                    id = "media_lang_en",
                    label = "英文报告 (-E en)",
                    flag = "-E en",
                    description = "使用英文界面输出检测结果",
                    enabledByDefault = false,
                ),
            ),
        ),
    )

    fun findBuiltIn(id: String): SshScriptPreset? = builtInPresets.firstOrNull { it.id == id }

    fun allPresets(customPresets: List<CustomSshPreset>): List<SshScriptPreset> =
        builtInPresets + customPresets.map { it.toScriptPreset() }

    /**
     * Ensures headless non-interactive environment variables and known-script non-interactive
     * flags/stdin pipes are applied before executing any built-in or custom command.
     */
    fun wrapHeadlessCommand(rawCommand: String): String =
        SshCommandRewriter.rewriteToNonInteractive(rawCommand)
}
