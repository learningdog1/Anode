package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

/**
 * Supported Linux distribution families for non-interactive `iperf3` package installation.
 */
enum class LinuxDistroFamily(
    val displayLabel: String,
    val packageManagerLabel: String,
) {
    DEBIAN_UBUNTU("Debian / Ubuntu", "apt"),
    CENTOS_RHEL("CentOS / RHEL / Rocky / Alma", "dnf / yum"),
    FEDORA("Fedora", "dnf"),
    ALPINE("Alpine Linux", "apk"),
    ARCH("Arch Linux", "pacman"),
    OPENSUSE("openSUSE / SLES", "zypper"),
    UNKNOWN("Linux (通用探测)", "auto"),
}

/**
 * Parsed `/etc/os-release` fields used to determine the remote VPS distribution.
 */
@Immutable
data class OsReleaseInfo(
    val id: String = "",
    val idLike: List<String> = emptyList(),
    val prettyName: String = "",
    val versionId: String = "",
) {
    val displayTitle: String
        get() = prettyName.trim().ifBlank {
            id.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                .ifBlank { "Linux VPS" }
        }
}

/**
 * Structured probe result emitted when `iperf3` is not yet installed on a remote VPS.
 */
@Immutable
data class Iperf3MissingProbeResult(
    val osRelease: OsReleaseInfo,
    val distroFamily: LinuxDistroFamily,
    val isRoot: Boolean,
    val hasSudo: Boolean,
    val hasDnf: Boolean,
    val installCommand: String,
)

/**
 * Firewall helper commands for allowing TCP port traffic on Linux VPS hosts.
 */
@Immutable
data class FirewallHintCommands(
    val port: Int,
    val ufwCommand: String,
    val firewalldCommand: String,
    val iptablesCommand: String,
)

/**
 * Verified identity of an `iperf3 -s -p <port>` daemon started by this app.
 *
 * Stopping a remote daemon strictly requires all three attributes ([pid], [startTime], and [port])
 * to match the live remote process so recycled PIDs and unrelated `iperf3`/benchmark tasks are
 * never terminated.
 */
@Immutable
data class Iperf3OwnedInstance(
    val pid: Long,
    val startTime: String,
    val port: Int,
) {
    init {
        require(pid > 1L) { "PID 必须大于 1" }
        require(startTime.isNotBlank()) { "startTime 不能为空" }
        require(Iperf3RemoteServerCommands.isValidPort(port)) {
            "端口必须在 ${Iperf3RemoteServerCommands.MIN_PORT} ~ ${Iperf3RemoteServerCommands.MAX_PORT} 之间"
        }
    }
}

/**
 * Ownership classification of an active `iperf3` endpoint attached in the app session.
 */
enum class Iperf3ServiceOwnership {
    /** Daemon was started by this app and has verified `(pid, startTime, port)` metadata. */
    APP_STARTED,

    /** Port was already served by an external `iperf3` process not started by this app. */
    EXTERNAL_REUSED,
}

/**
 * Structured result parsed from [Iperf3RemoteServerCommands.buildCheckAndStartScript] or
 * [Iperf3RemoteServerCommands.buildInstallAndStartScript] stdout.
 */
sealed interface Iperf3StartProbeResult {
    val port: Int

    @Immutable
    data class StartedByApp(
        val instance: Iperf3OwnedInstance,
    ) : Iperf3StartProbeResult {
        override val port: Int get() = instance.port
    }

    @Immutable
    data class AlreadyOwnedByApp(
        val instance: Iperf3OwnedInstance,
    ) : Iperf3StartProbeResult {
        override val port: Int get() = instance.port
    }

    @Immutable
    data class ReusedExternalService(
        override val port: Int,
    ) : Iperf3StartProbeResult
}

/**
 * Outcome codes emitted by [Iperf3RemoteServerCommands.buildStopScript].
 */
enum class Iperf3StopOutcome {
    STOPPED,
    ALREADY_STOPPED,
    EXTERNAL_UNTOUCHED,
    NOT_FOUND,
    STALE_PID_MISMATCH,
    TARGET_MISMATCH,
    METADATA_MISMATCH,
    KILL_FAILED,
}

/**
 * Structured result parsed from [Iperf3RemoteServerCommands.buildStopScript] stdout.
 */
@Immutable
data class Iperf3StopProbeResult(
    val outcome: Iperf3StopOutcome,
    val port: Int,
    val pid: Long? = null,
)

/**
 * Pure-Kotlin command builder and `/etc/os-release` classifier for 1-click remote VPS `iperf3`
 * server management (pre-flight check, distro-adapted installation, daemon startup, port-conflict
 * detection, firewall guidance/opening, and strictly scoped shutdown).
 */
object Iperf3RemoteServerCommands {

    const val DEFAULT_PORT: Int = 5201
    const val MIN_PORT: Int = 1
    const val MAX_PORT: Int = 65535

    const val PRESET_ID_START: String = "iperf3_server_start"
    const val PRESET_ID_INSTALL_AND_START: String = "iperf3_server_install_start"
    const val PRESET_ID_STOP: String = "iperf3_server_stop"

    const val EXIT_CODE_NOT_INSTALLED: Int = 42
    const val EXIT_CODE_PORT_CONFLICT: Int = 43

    internal const val MISSING_MARKER_PREFIX: String = "__ANODE_IPERF3_MISSING__|"
    internal const val STATE_MARKER_PREFIX: String = "__ANODE_IPERF3_STATE__|"
    internal const val STOP_MARKER_PREFIX: String = "__ANODE_IPERF3_STOP__|"

    private val DEBIAN_IDS = setOf(
        "debian", "ubuntu", "linuxmint", "kali", "raspbian", "pop", "elementary", "zorin", "armbian", "deepin",
    )
    private val CENTOS_RHEL_IDS = setOf(
        "centos", "rhel", "rocky", "almalinux", "alma", "ol", "oracle", "amzn",
        "cloudlinux", "alinux", "anolis", "tencentos", "opencloudos", "euleros", "openEuler".lowercase(),
    )
    private val ARCH_IDS = setOf("arch", "archarm", "manjaro", "endeavouros", "garuda", "artix")
    private val SUSE_IDS = setOf(
        "opensuse", "opensuse-leap", "opensuse-tumbleweed", "opensuse-microos", "sles", "sled", "suse",
    )

    fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

    fun validatePort(rawPort: String): Int? {
        val parsed = rawPort.trim().toIntOrNull() ?: return null
        return parsed.takeIf(::isValidPort)
    }

    internal fun isInternalMarkerLine(line: String): Boolean =
        line.contains(MISSING_MARKER_PREFIX) ||
            line.contains(STATE_MARKER_PREFIX) ||
            line.contains(STOP_MARKER_PREFIX)

    /**
     * Parses `/etc/os-release` key-value lines into [OsReleaseInfo].
     */
    fun parseOsRelease(content: String): OsReleaseInfo {
        val fields = mutableMapOf<String, String>()
        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx <= 0) return@forEach
            val key = trimmed.substring(0, eqIdx).trim().uppercase()
            val rawVal = trimmed.substring(eqIdx + 1).trim()
            val unquoted = when {
                rawVal.length >= 2 && rawVal.startsWith('"') && rawVal.endsWith('"') ->
                    rawVal.substring(1, rawVal.length - 1)
                rawVal.length >= 2 && rawVal.startsWith('\'') && rawVal.endsWith('\'') ->
                    rawVal.substring(1, rawVal.length - 1)
                else -> rawVal
            }
            fields[key] = unquoted.trim()
        }

        val id = fields["ID"].orEmpty().lowercase()
        val idLike = fields["ID_LIKE"].orEmpty()
            .lowercase()
            .split(Regex("""[\s,]+"""))
            .filter { it.isNotEmpty() }
        val prettyName = fields["PRETTY_NAME"].orEmpty().ifBlank { fields["NAME"].orEmpty() }
        val versionId = fields["VERSION_ID"].orEmpty()

        return OsReleaseInfo(
            id = id,
            idLike = idLike,
            prettyName = prettyName,
            versionId = versionId,
        )
    }

    /**
     * Classifies [osRelease] into a [LinuxDistroFamily].
     *
     * Note: Checks primary `ID` before `ID_LIKE`, and checks `CENTOS_RHEL` before `FEDORA`
     * because Rocky/Alma/CentOS/RHEL include `fedora` inside `ID_LIKE="rhel centos fedora"`.
     */
    fun classifyDistro(osRelease: OsReleaseInfo): LinuxDistroFamily {
        val id = osRelease.id.lowercase().trim()
        val likes = osRelease.idLike.map { it.lowercase().trim() }

        return when {
            id in DEBIAN_IDS -> LinuxDistroFamily.DEBIAN_UBUNTU
            id in CENTOS_RHEL_IDS -> LinuxDistroFamily.CENTOS_RHEL
            id == "fedora" -> LinuxDistroFamily.FEDORA
            id == "alpine" -> LinuxDistroFamily.ALPINE
            id in ARCH_IDS -> LinuxDistroFamily.ARCH
            id in SUSE_IDS || id.startsWith("opensuse") -> LinuxDistroFamily.OPENSUSE
            likes.any { it in DEBIAN_IDS } -> LinuxDistroFamily.DEBIAN_UBUNTU
            likes.any { it in CENTOS_RHEL_IDS } -> LinuxDistroFamily.CENTOS_RHEL
            likes.any { it == "fedora" } -> LinuxDistroFamily.FEDORA
            likes.any { it == "alpine" } -> LinuxDistroFamily.ALPINE
            likes.any { it in ARCH_IDS } -> LinuxDistroFamily.ARCH
            likes.any { it in SUSE_IDS || it.contains("suse") } -> LinuxDistroFamily.OPENSUSE
            else -> LinuxDistroFamily.UNKNOWN
        }
    }

    /**
     * Builds the non-interactive `iperf3` installation command for [distro], automatically
     * prepending `sudo` when [isRoot] is `false`.
     */
    fun buildInstallCommand(
        distro: LinuxDistroFamily,
        isRoot: Boolean,
        hasDnf: Boolean = true,
    ): String {
        val sudo = if (isRoot) "" else "sudo "
        return when (distro) {
            LinuxDistroFamily.DEBIAN_UBUNTU -> {
                val aptPrefix = if (isRoot) {
                    "DEBIAN_FRONTEND=noninteractive apt-get"
                } else {
                    "sudo env DEBIAN_FRONTEND=noninteractive apt-get"
                }
                "$aptPrefix update -y && $aptPrefix install -y iperf3"
            }

            LinuxDistroFamily.CENTOS_RHEL -> {
                val pkg = if (hasDnf) "dnf" else "yum"
                "${sudo}$pkg install -y iperf3 || (${sudo}$pkg install -y epel-release && ${sudo}$pkg install -y iperf3)"
            }

            LinuxDistroFamily.FEDORA -> {
                "${sudo}dnf install -y iperf3"
            }

            LinuxDistroFamily.ALPINE -> {
                "${sudo}apk update && ${sudo}apk add --no-cache iperf3"
            }

            LinuxDistroFamily.ARCH -> {
                "${sudo}pacman -Sy --noconfirm --needed iperf3"
            }

            LinuxDistroFamily.OPENSUSE -> {
                "${sudo}zypper --non-interactive install -y iperf3"
            }

            LinuxDistroFamily.UNKNOWN -> {
                val aptCmd = if (isRoot) {
                    "DEBIAN_FRONTEND=noninteractive apt-get update -y && DEBIAN_FRONTEND=noninteractive apt-get install -y iperf3"
                } else {
                    "sudo env DEBIAN_FRONTEND=noninteractive apt-get update -y && sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y iperf3"
                }
                "if command -v apt-get >/dev/null 2>&1; then $aptCmd; " +
                    "elif command -v dnf >/dev/null 2>&1; then ${sudo}dnf install -y iperf3 || (${sudo}dnf install -y epel-release && ${sudo}dnf install -y iperf3); " +
                    "elif command -v yum >/dev/null 2>&1; then ${sudo}yum install -y iperf3 || (${sudo}yum install -y epel-release && ${sudo}yum install -y iperf3); " +
                    "elif command -v apk >/dev/null 2>&1; then ${sudo}apk update && ${sudo}apk add --no-cache iperf3; " +
                    "elif command -v pacman >/dev/null 2>&1; then ${sudo}pacman -Sy --noconfirm --needed iperf3; " +
                    "elif command -v zypper >/dev/null 2>&1; then ${sudo}zypper --non-interactive install -y iperf3; " +
                    "else echo '未识别到受支持的包管理器 (apt/dnf/yum/apk/pacman/zypper)' >&2; exit 1; fi"
            }
        }
    }

    /**
     * Generates copy-ready firewall commands (`ufw`, `firewalld`, `iptables`) for [port].
     */
    fun buildFirewallHintCommands(port: Int, isRoot: Boolean = false): FirewallHintCommands {
        val validPort = port.coerceIn(MIN_PORT, MAX_PORT)
        val sudo = if (isRoot) "" else "sudo "
        return FirewallHintCommands(
            port = validPort,
            ufwCommand = "${sudo}ufw allow $validPort/tcp",
            firewalldCommand = "${sudo}firewall-cmd --add-port=$validPort/tcp",
            iptablesCommand = "${sudo}iptables -I INPUT -p tcp --dport $validPort -j ACCEPT",
        )
    }

    /**
     * Builds the SSH shell script that:
     * 1. Checks if `iperf3` is installed; if missing, prints `/etc/os-release` diagnostics +
     *    structured marker and exits with [EXIT_CODE_NOT_INSTALLED] (`42`) so the client prompts
     *    for 1-click installation.
     * 2. Checks TCP [port] occupancy (`ss` / `netstat` / `lsof`):
     *    - If [port] already hosts our verified app-started instance (`pid` + `start_time` + `port`),
     *      emits `mode=owned_running`.
     *    - If [port] hosts an external `iperf3` service not started by this app, reuses it without
     *      claiming ownership and emits `mode=external_reused`.
     *    - If another non-`iperf3` process holds [port], exits with [EXIT_CODE_PORT_CONFLICT] (`43`).
     * 3. Always passes explicit `-s -p <port>` (including on default port `5201`), verifies the
     *    launched daemon's `pid`, `start_time`, `cmdline`, and listening socket, and persists
     *    structured metadata (`pid`, `start_time`, `port`) atomically.
     * 4. Inspects `ufw` / `firewalld` / `iptables` (optionally opening TCP [port] when
     *    [autoOpenFirewall] is `true`) and prints firewall & cloud security-group guidance.
     */
    fun buildCheckAndStartScript(
        port: Int = DEFAULT_PORT,
        autoOpenFirewall: Boolean = false,
    ): String {
        require(isValidPort(port)) { "端口必须在 $MIN_PORT ~ $MAX_PORT 之间" }
        val script = listOf(
            """export PATH="${'$'}PATH:/usr/local/bin:/usr/sbin:/sbin"""",
            """PORT=$port""",
            """IS_ROOT=0; [ "$(id -u 2>/dev/null || echo 1)" -eq 0 ] && IS_ROOT=1""",
            """HAS_SUDO=0; command -v sudo >/dev/null 2>&1 && HAS_SUDO=1""",
            """HAS_DNF=0; command -v dnf >/dev/null 2>&1 && HAS_DNF=1""",
            """SUDO=""; [ "${'$'}IS_ROOT" -eq 0 ] && [ "${'$'}HAS_SUDO" -eq 1 ] && SUDO="sudo """",
            """if ! command -v iperf3 >/dev/null 2>&1; then """ +
                """OS_ID=""; OS_LIKE=""; OS_PRETTY=""; """ +
                """if [ -r /etc/os-release ]; then . /etc/os-release 2>/dev/null; OS_ID="${'$'}ID"; OS_LIKE="${'$'}ID_LIKE"; OS_PRETTY="${'$'}PRETTY_NAME"; fi; """ +
                """CLEAN_PRETTY=$(printf '%s' "${'$'}OS_PRETTY" | tr '|\r\n' '   '); """ +
                """printf '\033[1;33m⚠ 未检测到 iperf3 命令（系统：%s）\033[0m\n' "${'$'}{CLEAN_PRETTY:-Linux}"; """ +
                """printf '%s\n' "${MISSING_MARKER_PREFIX}id=${'$'}OS_ID|id_like=${'$'}OS_LIKE|pretty=${'$'}CLEAN_PRETTY|is_root=${'$'}IS_ROOT|has_dnf=${'$'}HAS_DNF|has_sudo=${'$'}HAS_SUDO"; """ +
                """exit $EXIT_CODE_NOT_INSTALLED; """ +
                """fi""",
            buildStartAndFirewallBody(port = port, autoOpenFirewall = autoOpenFirewall),
        ).joinToString("; ")
        return SshPresetCatalog.wrapHeadlessCommand(script)
    }

    /**
     * Builds the SSH shell script executed after the user confirms 1-click `iperf3` installation:
     * installs `iperf3` using the distro-specific command and immediately starts the daemon with
     * explicit `-p <port>` and structured metadata tracking.
     */
    fun buildInstallAndStartScript(
        distro: LinuxDistroFamily,
        isRoot: Boolean,
        hasDnf: Boolean = true,
        port: Int = DEFAULT_PORT,
        autoOpenFirewall: Boolean = false,
    ): String {
        require(isValidPort(port)) { "端口必须在 $MIN_PORT ~ $MAX_PORT 之间" }
        val installCmd = buildInstallCommand(distro = distro, isRoot = isRoot, hasDnf = hasDnf)
        val script = listOf(
            """export PATH="${'$'}PATH:/usr/local/bin:/usr/sbin:/sbin"""",
            """PORT=$port""",
            """IS_ROOT=0; [ "$(id -u 2>/dev/null || echo 1)" -eq 0 ] && IS_ROOT=1""",
            """HAS_SUDO=0; command -v sudo >/dev/null 2>&1 && HAS_SUDO=1""",
            """SUDO=""; [ "${'$'}IS_ROOT" -eq 0 ] && [ "${'$'}HAS_SUDO" -eq 1 ] && SUDO="sudo """",
            """printf '\033[1;36m▶ 正在安装 iperf3（%s）…\033[0m\n' '${distro.displayLabel}'""",
            """($installCmd)""",
            """if ! command -v iperf3 >/dev/null 2>&1; then printf '\033[1;31m✗ iperf3 安装失败，请检查软件源或 sudo 权限\033[0m\n'; exit 1; fi""",
            """printf '\033[1;32m✓ iperf3 安装完成：%s\033[0m\n' "$(iperf3 --version 2>&1 | head -n 1)"""",
            buildStartAndFirewallBody(port = port, autoOpenFirewall = autoOpenFirewall),
        ).joinToString("; ")
        return SshPresetCatalog.wrapHeadlessCommand(script)
    }

    /**
     * Builds the SSH shell script to cleanly stop ONLY an app-started `iperf3 -s -p <port>` daemon
     * whose `PID`, process `start_time`, AND configured/listening `port` all match.
     *
     * Never executes broad `pkill`, `killall`, or name-only `pgrep` fallbacks. Once the verified
     * PID exits (or if no matching app-owned metadata exists), exits cleanly without touching any
     * other `iperf3` or benchmark process on the VPS.
     */
    fun buildStopScript(
        port: Int = DEFAULT_PORT,
        expectedPid: Long? = null,
        expectedStartTime: String? = null,
    ): String {
        require(isValidPort(port)) { "端口必须在 $MIN_PORT ~ $MAX_PORT 之间" }
        if (expectedPid != null) {
            require(expectedPid > 1L) { "期望的 PID 必须大于 1" }
        }
        val cleanExpectedStart = expectedStartTime
            ?.replace(Regex("""[|\r\n\t]+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        require((expectedPid == null) == (cleanExpectedStart == null)) {
            "expectedPid 与 expectedStartTime 必须同时提供或同时为空"
        }

        val clientPidLiteral = expectedPid?.toString().orEmpty()
        val clientStartQuoted = shellSingleQuote(cleanExpectedStart.orEmpty())

        val script = listOf(
            """export PATH="${'$'}PATH:/usr/local/bin:/usr/sbin:/sbin"""",
            """PORT=$port""",
            """IS_ROOT=0; [ "$(id -u 2>/dev/null || echo 1)" -eq 0 ] && IS_ROOT=1""",
            """HAS_SUDO=0; command -v sudo >/dev/null 2>&1 && HAS_SUDO=1""",
            """SUDO=""; [ "${'$'}IS_ROOT" -eq 0 ] && [ "${'$'}HAS_SUDO" -eq 1 ] && SUDO="sudo """",
            buildLifecycleHelpersShell(),
            """CLIENT_PID="$clientPidLiteral"""",
            """CLIENT_START=$clientStartQuoted""",
            """META_PID=""; META_START=""; META_PORT=""; HAS_META=0""",
            """if [ -f "${'$'}META_FILE" ] && [ ! -L "${'$'}META_FILE" ]; then """ +
                """HAS_META=1; """ +
                """META_PID=$(sed -n 's/^pid=//p' "${'$'}META_FILE" 2>/dev/null | head -n 1 | tr -dc '0-9'); """ +
                """META_START=$(sed -n 's/^start_time=//p' "${'$'}META_FILE" 2>/dev/null | head -n 1 | tr -d '\r\n'); """ +
                """META_PORT=$(sed -n 's/^port=//p' "${'$'}META_FILE" 2>/dev/null | head -n 1 | tr -dc '0-9'); """ +
                """fi""",
            """TARGET_PID=""; TARGET_START=""""",
            """if [ -n "${'$'}CLIENT_PID" ] && [ -n "${'$'}CLIENT_START" ]; then """ +
                """if [ "${'$'}HAS_META" -eq 1 ] && { [ "${'$'}META_PORT" != "${'$'}PORT" ] || [ "${'$'}META_PID" != "${'$'}CLIENT_PID" ] || [ "${'$'}META_START" != "${'$'}CLIENT_START" ]; }; then """ +
                """printf '%s\n' "${STOP_MARKER_PREFIX}result=metadata_mismatch|port=${'$'}PORT"; """ +
                """printf '\033[1;33m⚠ 端口 %s 的远程实例元数据与本地记录不一致，已拒绝终止以防误杀其他进程\033[0m\n' "${'$'}PORT"; """ +
                """exit 0; """ +
                """fi; """ +
                """TARGET_PID="${'$'}CLIENT_PID"; TARGET_START="${'$'}CLIENT_START"; """ +
                """elif [ "${'$'}HAS_META" -eq 1 ]; then """ +
                """if [ -z "${'$'}META_PID" ] || [ -z "${'$'}META_START" ] || [ "${'$'}META_PORT" != "${'$'}PORT" ]; then """ +
                """rm -f "${'$'}META_FILE" "${'$'}PID_FILE" 2>/dev/null; """ +
                """printf '%s\n' "${STOP_MARKER_PREFIX}result=metadata_mismatch|port=${'$'}PORT"; """ +
                """printf '\033[1;33m⚠ 端口 %s 的实例状态文件无效或端口不匹配，已拒绝终止并清理无效记录\033[0m\n' "${'$'}PORT"; """ +
                """exit 0; """ +
                """fi; """ +
                """TARGET_PID="${'$'}META_PID"; TARGET_START="${'$'}META_START"; """ +
                """else """ +
                """rm -f "${'$'}PID_FILE" 2>/dev/null; """ +
                """EXT_INFO=$(anode_port_info "${'$'}PORT"); """ +
                """if [ -n "${'$'}EXT_INFO" ] && printf '%s' "${'$'}EXT_INFO" | grep -qi "iperf3"; then """ +
                """printf '%s\n' "${STOP_MARKER_PREFIX}result=external_untouched|port=${'$'}PORT"; """ +
                """printf '\033[1;33mℹ 端口 %s 上的 iperf3 服务端并非由本应用启动，未远程终止\033[0m\n' "${'$'}PORT"; """ +
                """else """ +
                """printf '%s\n' "${STOP_MARKER_PREFIX}result=not_found|port=${'$'}PORT"; """ +
                """printf '\033[1;33mℹ 当前端口 %s 未发现由本应用启动的 iperf3 服务端进程\033[0m\n' "${'$'}PORT"; """ +
                """fi; """ +
                """exit 0; """ +
                """fi""",
            """if ! anode_pid_alive "${'$'}TARGET_PID"; then """ +
                """rm -f "${'$'}META_FILE" "${'$'}PID_FILE" 2>/dev/null; """ +
                """printf '%s\n' "${STOP_MARKER_PREFIX}result=already_stopped|port=${'$'}PORT|pid=${'$'}TARGET_PID"; """ +
                """printf '\033[1;33mℹ 端口 %s 的本应用 iperf3 服务端（PID %s）已退出，已清理状态记录\033[0m\n' "${'$'}PORT" "${'$'}TARGET_PID"; """ +
                """exit 0; """ +
                """fi""",
            """ACTUAL_START=$(anode_proc_start "${'$'}TARGET_PID" 2>/dev/null || true)""",
            """if [ -z "${'$'}ACTUAL_START" ] || [ "${'$'}ACTUAL_START" != "${'$'}TARGET_START" ]; then """ +
                """rm -f "${'$'}META_FILE" "${'$'}PID_FILE" 2>/dev/null; """ +
                """printf '%s\n' "${STOP_MARKER_PREFIX}result=stale_mismatch|port=${'$'}PORT|pid=${'$'}TARGET_PID"; """ +
                """printf '\033[1;33m⚠ 检测到 PID %s 的进程启动时间与记录不一致（PID 已复用），已拒绝终止并清理旧记录\033[0m\n' "${'$'}TARGET_PID"; """ +
                """exit 0; """ +
                """fi""",
            """if ! anode_verify_target "${'$'}TARGET_PID" "${'$'}PORT"; then """ +
                """rm -f "${'$'}META_FILE" "${'$'}PID_FILE" 2>/dev/null; """ +
                """printf '%s\n' "${STOP_MARKER_PREFIX}result=target_mismatch|port=${'$'}PORT|pid=${'$'}TARGET_PID"; """ +
                """printf '\033[1;33m⚠ 检测到 PID %s 的命令行或监听端口与 iperf3 -s -p %s 不匹配，已拒绝终止\033[0m\n' "${'$'}TARGET_PID" "${'$'}PORT"; """ +
                """exit 0; """ +
                """fi""",
            """(kill -TERM "${'$'}TARGET_PID" 2>/dev/null || { [ -n "${'$'}SUDO" ] && ${'$'}SUDO -n kill -TERM "${'$'}TARGET_PID" 2>/dev/null; }) || true""",
            """_w=0; while [ "${'$'}_w" -lt 5 ]; do if ! anode_pid_alive "${'$'}TARGET_PID"; then break; fi; sleep 0.2 2>/dev/null || sleep 1; _w=$((_w + 1)); done""",
            """if anode_pid_alive "${'$'}TARGET_PID"; then """ +
                """_recheck_start=$(anode_proc_start "${'$'}TARGET_PID" 2>/dev/null || true); """ +
                """if [ -n "${'$'}_recheck_start" ] && [ "${'$'}_recheck_start" = "${'$'}TARGET_START" ] && anode_verify_cmdline "${'$'}TARGET_PID" "${'$'}PORT"; then """ +
                """(kill -KILL "${'$'}TARGET_PID" 2>/dev/null || { [ -n "${'$'}SUDO" ] && ${'$'}SUDO -n kill -KILL "${'$'}TARGET_PID" 2>/dev/null; }) || true; """ +
                """sleep 0.1 2>/dev/null || sleep 1; """ +
                """fi; """ +
                """fi""",
            """if ! anode_pid_alive "${'$'}TARGET_PID"; then """ +
                """rm -f "${'$'}META_FILE" "${'$'}PID_FILE" 2>/dev/null; """ +
                """printf '%s\n' "${STOP_MARKER_PREFIX}result=stopped|port=${'$'}PORT|pid=${'$'}TARGET_PID"; """ +
                """printf '\033[1;32m✓ 已停止 VPS 上的 iperf3 服务端（本应用实例，端口 %s，PID %s）\033[0m\n' "${'$'}PORT" "${'$'}TARGET_PID"; """ +
                """exit 0; """ +
                """else """ +
                """printf '%s\n' "${STOP_MARKER_PREFIX}result=kill_failed|port=${'$'}PORT|pid=${'$'}TARGET_PID"; """ +
                """printf '\033[1;31m✗ 未能停止 iperf3 服务端进程（端口 %s，PID %s），请检查权限\033[0m\n' "${'$'}PORT" "${'$'}TARGET_PID"; """ +
                """exit 1; """ +
                """fi""",
        ).joinToString("; ")
        return SshPresetCatalog.wrapHeadlessCommand(script)
    }

    /**
     * Parses [MISSING_MARKER_PREFIX] from sanitized SSH output when exit code is [EXIT_CODE_NOT_INSTALLED].
     */
    fun parseMissingProbeOutput(sanitizedOutput: String): Iperf3MissingProbeResult? {
        val kv = parseMarkerKeyValues(sanitizedOutput, MISSING_MARKER_PREFIX) ?: return null

        val osRelease = OsReleaseInfo(
            id = kv["id"].orEmpty().lowercase(),
            idLike = kv["id_like"].orEmpty().lowercase().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() },
            prettyName = kv["pretty"].orEmpty(),
        )
        val distro = classifyDistro(osRelease)
        val isRoot = kv["is_root"] == "1"
        val hasDnf = kv["has_dnf"] != "0"
        val hasSudo = kv["has_sudo"] == "1"
        val installCommand = buildInstallCommand(
            distro = distro,
            isRoot = isRoot,
            hasDnf = hasDnf,
        )
        return Iperf3MissingProbeResult(
            osRelease = osRelease,
            distroFamily = distro,
            isRoot = isRoot,
            hasSudo = hasSudo,
            hasDnf = hasDnf,
            installCommand = installCommand,
        )
    }

    /**
     * Parses [STATE_MARKER_PREFIX] from sanitized SSH output of start / install-and-start scripts.
     */
    fun parseStartResultOutput(
        sanitizedOutput: String,
        fallbackPort: Int = DEFAULT_PORT,
    ): Iperf3StartProbeResult? {
        val kv = parseMarkerKeyValues(sanitizedOutput, STATE_MARKER_PREFIX, pickLast = true) ?: return null
        val port = kv["port"]?.toIntOrNull()?.takeIf(::isValidPort)
            ?: fallbackPort.takeIf(::isValidPort)
            ?: return null

        return when (kv["mode"]) {
            "started" -> {
                val pid = kv["pid"]?.toLongOrNull()?.takeIf { it > 1L } ?: return null
                val startTime = kv["start_time"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                Iperf3StartProbeResult.StartedByApp(
                    instance = Iperf3OwnedInstance(pid = pid, startTime = startTime, port = port),
                )
            }
            "owned_running" -> {
                val pid = kv["pid"]?.toLongOrNull()?.takeIf { it > 1L } ?: return null
                val startTime = kv["start_time"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                Iperf3StartProbeResult.AlreadyOwnedByApp(
                    instance = Iperf3OwnedInstance(pid = pid, startTime = startTime, port = port),
                )
            }
            "external_reused" -> Iperf3StartProbeResult.ReusedExternalService(port = port)
            else -> null
        }
    }

    /**
     * Parses [STOP_MARKER_PREFIX] from sanitized SSH output of [buildStopScript].
     */
    fun parseStopResultOutput(
        sanitizedOutput: String,
        fallbackPort: Int = DEFAULT_PORT,
    ): Iperf3StopProbeResult? {
        val kv = parseMarkerKeyValues(sanitizedOutput, STOP_MARKER_PREFIX, pickLast = true) ?: return null
        val port = kv["port"]?.toIntOrNull()?.takeIf(::isValidPort)
            ?: fallbackPort.takeIf(::isValidPort)
            ?: return null
        val pid = kv["pid"]?.toLongOrNull()?.takeIf { it > 1L }
        val outcome = when (kv["result"]) {
            "stopped" -> Iperf3StopOutcome.STOPPED
            "already_stopped" -> Iperf3StopOutcome.ALREADY_STOPPED
            "external_untouched" -> Iperf3StopOutcome.EXTERNAL_UNTOUCHED
            "not_found" -> Iperf3StopOutcome.NOT_FOUND
            "stale_mismatch" -> Iperf3StopOutcome.STALE_PID_MISMATCH
            "target_mismatch" -> Iperf3StopOutcome.TARGET_MISMATCH
            "metadata_mismatch" -> Iperf3StopOutcome.METADATA_MISMATCH
            "kill_failed" -> Iperf3StopOutcome.KILL_FAILED
            else -> return null
        }
        return Iperf3StopProbeResult(
            outcome = outcome,
            port = port,
            pid = pid,
        )
    }

    private fun parseMarkerKeyValues(
        sanitizedOutput: String,
        prefix: String,
        pickLast: Boolean = false,
    ): Map<String, String>? {
        val lines = sanitizedOutput
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains(prefix) }
        val markerLine = (if (pickLast) lines.lastOrNull() else lines.firstOrNull()) ?: return null
        val payload = markerLine.substringAfter(prefix)
        val kv = mutableMapOf<String, String>()
        payload.split('|').forEach { segment ->
            val idx = segment.indexOf('=')
            if (idx > 0) {
                kv[segment.substring(0, idx).trim()] = segment.substring(idx + 1).trim()
            }
        }
        return kv
    }

    private fun shellSingleQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun buildLifecycleHelpersShell(): String = listOf(
        """PROC_ROOT="${'$'}{ANODE_PROC_ROOT:-/proc}"""",
        """UID_TAG=$(id -u 2>/dev/null || echo 0)""",
        """STATE_DIR="${'$'}{ANODE_STATE_DIR:-/tmp/anode_iperf3_${'$'}UID_TAG}"""",
        """PID_FILE="${'$'}STATE_DIR/anode_iperf3_${'$'}PORT.pid"""",
        """META_FILE="${'$'}STATE_DIR/anode_iperf3_${'$'}PORT.meta"""",
        """LOG_FILE="${'$'}STATE_DIR/anode_iperf3_${'$'}PORT.log"""",
        """anode_ensure_state_dir() { mkdir -p "${'$'}STATE_DIR" 2>/dev/null && chmod 700 "${'$'}STATE_DIR" 2>/dev/null || true; }""",
        """anode_proc_start() { """ +
            """_p="${'$'}1"; [ -n "${'$'}_p" ] || return 1; _st=""; """ +
            """if [ -r "${'$'}PROC_ROOT/${'$'}_p/stat" ]; then """ +
            """_raw=$(cat "${'$'}PROC_ROOT/${'$'}_p/stat" 2>/dev/null); """ +
            """if [ -n "${'$'}_raw" ]; then _rest="${'$'}{_raw##*) }"; _st=$(printf '%s\n' "${'$'}_rest" | awk '{print ${'$'}20}'); fi; """ +
            """fi; """ +
            """if [ -z "${'$'}_st" ] && [ "${'$'}PROC_ROOT" = "/proc" ] && command -v ps >/dev/null 2>&1; then """ +
            """_st=$(ps -p "${'$'}_p" -o lstart= 2>/dev/null | tr '|\r\n\t' '    ' | awk '{${'$'}1=${'$'}1; print}'); """ +
            """fi; """ +
            """[ -n "${'$'}_st" ] || return 1; printf '%s' "${'$'}_st"; """ +
            """}""",
        """anode_pid_alive() { """ +
            """_p="${'$'}1"; case "${'$'}_p" in ''|*[!0-9]*) return 1 ;; esac; """ +
            """[ "${'$'}_p" -gt 1 ] 2>/dev/null || return 1; """ +
            """if kill -0 "${'$'}_p" 2>/dev/null || { [ -n "${'$'}SUDO" ] && ${'$'}SUDO -n kill -0 "${'$'}_p" 2>/dev/null; }; then """ +
            """if [ -r "${'$'}PROC_ROOT/${'$'}_p/stat" ]; then """ +
            """_raw=$(cat "${'$'}PROC_ROOT/${'$'}_p/stat" 2>/dev/null); """ +
            """if [ -n "${'$'}_raw" ]; then _sc=$(printf '%s\n' "${'$'}{_raw##*) }" | awk '{print ${'$'}1}'); case "${'$'}_sc" in Z*|X*|x*) return 1 ;; esac; fi; """ +
            """elif command -v ps >/dev/null 2>&1; then """ +
            """_ps=$(ps -p "${'$'}_p" -o stat= 2>/dev/null | awk '{print ${'$'}1}'); case "${'$'}_ps" in Z*|X*) return 1 ;; esac; """ +
            """fi; """ +
            """return 0; """ +
            """fi; """ +
            """return 1; """ +
            """}""",
        """anode_verify_cmdline() { """ +
            """_p="${'$'}1"; _pt="${'$'}2"; _cmd=""; """ +
            """if [ -r "${'$'}PROC_ROOT/${'$'}_p/cmdline" ]; then _cmd=$(tr '\000' ' ' < "${'$'}PROC_ROOT/${'$'}_p/cmdline" 2>/dev/null); fi; """ +
            """if [ -z "$(printf '%s' "${'$'}_cmd" | tr -d ' \t\r\n')" ] && [ "${'$'}PROC_ROOT" = "/proc" ] && command -v ps >/dev/null 2>&1; then """ +
            """_cmd=$(ps -ww -p "${'$'}_p" -o args= 2>/dev/null || ps -p "${'$'}_p" -o args= 2>/dev/null); """ +
            """fi; """ +
            """[ -n "${'$'}_cmd" ] || return 1; """ +
            """printf '%s\n' "${'$'}_cmd" | awk -v tp="${'$'}_pt" 'BEGIN{hb=0;hs=0;hp=0;hc=0} {for(i=1;i<=NF;i++){t=${'$'}i; if(i==1 && (t=="iperf3"||t~/\/iperf3$/)) hb=1; else if(i==2 && ${'$'}1~/(^|\/)(sh|bash|dash|ash|nohup)$/ && (t=="iperf3"||t~/\/iperf3$/)) hb=1; if(t=="-s"||t=="--server") hs=1; if(t=="-c"||t=="--client") hc=1; if((t=="-p"||t=="--port") && i<NF && $(i+1)==tp) hp=1; if(t==("-p" tp)||t==("--port=" tp)) hp=1}} END{exit !(hb && hs && !hc && hp)}'; """ +
            """}""",
        """anode_has_socket_tool() { """ +
            """if command -v ss >/dev/null 2>&1; then return 0; """ +
            """elif command -v netstat >/dev/null 2>&1 && netstat -tln >/dev/null 2>&1; then return 0; """ +
            """elif command -v lsof >/dev/null 2>&1; then return 0; """ +
            """else return 1; fi; """ +
            """}""",
        """anode_port_info() { """ +
            """_pt="${'$'}1"; _out=""; """ +
            """if command -v ss >/dev/null 2>&1; then """ +
            """if [ -n "${'$'}SUDO" ]; then _out=$(${'$'}SUDO -n ss -tlnp 2>/dev/null | awk -v p=":${'$'}_pt" 'NR>1{loc=(${'$'}1=="LISTEN")?${'$'}4:${'$'}3; if(length(loc)>=length(p) && substr(loc,length(loc)-length(p)+1)==p) print ${'$'}0}'); fi; """ +
            """if [ -z "${'$'}_out" ]; then _out=$(ss -tlnp 2>/dev/null | awk -v p=":${'$'}_pt" 'NR>1{loc=(${'$'}1=="LISTEN")?${'$'}4:${'$'}3; if(length(loc)>=length(p) && substr(loc,length(loc)-length(p)+1)==p) print ${'$'}0}'); fi; """ +
            """if [ -z "${'$'}_out" ]; then _out=$(ss -tln 2>/dev/null | awk -v p=":${'$'}_pt" 'NR>1{loc=(${'$'}1=="LISTEN")?${'$'}4:${'$'}3; if(length(loc)>=length(p) && substr(loc,length(loc)-length(p)+1)==p) print ${'$'}0}'); fi; """ +
            """elif command -v netstat >/dev/null 2>&1 && netstat -tln >/dev/null 2>&1; then """ +
            """if [ -n "${'$'}SUDO" ]; then _out=$(${'$'}SUDO -n netstat -tlnp 2>/dev/null | awk -v p=":${'$'}_pt" '${'$'}6=="LISTEN" && length(${'$'}4)>=length(p) && substr(${'$'}4,length(${'$'}4)-length(p)+1)==p {print ${'$'}0}'); fi; """ +
            """if [ -z "${'$'}_out" ]; then _out=$(netstat -tlnp 2>/dev/null | awk -v p=":${'$'}_pt" '${'$'}6=="LISTEN" && length(${'$'}4)>=length(p) && substr(${'$'}4,length(${'$'}4)-length(p)+1)==p {print ${'$'}0}'); fi; """ +
            """if [ -z "${'$'}_out" ]; then _out=$(netstat -tln 2>/dev/null | awk -v p=":${'$'}_pt" '${'$'}6=="LISTEN" && length(${'$'}4)>=length(p) && substr(${'$'}4,length(${'$'}4)-length(p)+1)==p {print ${'$'}0}'); fi; """ +
            """elif command -v lsof >/dev/null 2>&1; then """ +
            """if [ -n "${'$'}SUDO" ]; then _out=$(${'$'}SUDO -n lsof -nP -iTCP:"${'$'}_pt" -sTCP:LISTEN 2>/dev/null | tail -n +2); fi; """ +
            """if [ -z "${'$'}_out" ]; then _out=$(lsof -nP -iTCP:"${'$'}_pt" -sTCP:LISTEN 2>/dev/null | tail -n +2); fi; """ +
            """fi; """ +
            """printf '%s' "${'$'}_out"; """ +
            """}""",
        """anode_extract_pid() { """ +
            """printf '%s\n' "${'$'}1" | awk 'match(${'$'}0,/pid=[0-9]+/){print substr(${'$'}0,RSTART+4,RLENGTH-4); exit} match(${'$'}0,/[0-9]+\/iperf3/){print substr(${'$'}0,RSTART,RLENGTH-7); exit} tolower(${'$'}1)~/^iperf3/ && ${'$'}2~/^[0-9]+$/{print ${'$'}2; exit}'; """ +
            """}""",
        """anode_verify_socket_port() { """ +
            """_p="${'$'}1"; _pt="${'$'}2"; """ +
            """if ! anode_has_socket_tool; then return 0; fi; """ +
            """_sinfo=$(anode_port_info "${'$'}_pt"); """ +
            """[ -n "${'$'}_sinfo" ] || return 1; """ +
            """printf '%s\n' "${'$'}_sinfo" | awk -v tp="${'$'}_p" 'BEGIN{has_owner=0; matched=0} { if (${'$'}0 ~ /pid=[0-9]+/) { has_owner=1; s=${'$'}0; while (match(s, /pid=[0-9]+/)) { v=substr(s, RSTART+4, RLENGTH-4); if (v == tp) matched=1; s=substr(s, RSTART+RLENGTH) } } else if (${'$'}0 ~ /(^|[[:space:]])[0-9]+\//) { has_owner=1; s=${'$'}0; while (match(s, /[0-9]+\//)) { v=substr(s, RSTART, RLENGTH-1); if (v == tp) matched=1; s=substr(s, RSTART+RLENGTH) } } else if (NF >= 2 && ${'$'}2 ~ /^[0-9]+$/ && ${'$'}1 !~ /^(LISTEN|tcp)/) { has_owner=1; if (${'$'}2 == tp) matched=1 } } END{ if (has_owner && !matched) exit 1; exit 0 }'; """ +
            """}""",
        """anode_verify_target() { """ +
            """anode_pid_alive "${'$'}1" && anode_verify_cmdline "${'$'}1" "${'$'}2" && anode_verify_socket_port "${'$'}1" "${'$'}2"; """ +
            """}""",
    ).joinToString("; ")

    private fun buildStartAndFirewallBody(
        port: Int,
        autoOpenFirewall: Boolean,
    ): String {
        val openFwFlag = if (autoOpenFirewall) "1" else "0"
        return listOf(
            buildLifecycleHelpersShell(),
            """anode_ensure_state_dir""",
            """PORT_INFO=$(anode_port_info "${'$'}PORT")""",
            """if [ -n "${'$'}PORT_INFO" ]; then """ +
                """OWNED_MATCH=0; META_PID=""; META_START=""; META_PORT=""; """ +
                """if [ -f "${'$'}META_FILE" ] && [ ! -L "${'$'}META_FILE" ]; then """ +
                """META_PID=$(sed -n 's/^pid=//p' "${'$'}META_FILE" 2>/dev/null | head -n 1 | tr -dc '0-9'); """ +
                """META_START=$(sed -n 's/^start_time=//p' "${'$'}META_FILE" 2>/dev/null | head -n 1 | tr -d '\r\n'); """ +
                """META_PORT=$(sed -n 's/^port=//p' "${'$'}META_FILE" 2>/dev/null | head -n 1 | tr -dc '0-9'); """ +
                """if [ "${'$'}META_PORT" = "${'$'}PORT" ] && [ -n "${'$'}META_PID" ] && [ -n "${'$'}META_START" ] && anode_verify_target "${'$'}META_PID" "${'$'}PORT"; then """ +
                """CUR_START=$(anode_proc_start "${'$'}META_PID" 2>/dev/null || true); """ +
                """if [ -n "${'$'}CUR_START" ] && [ "${'$'}CUR_START" = "${'$'}META_START" ]; then OWNED_MATCH=1; fi; """ +
                """fi; """ +
                """fi; """ +
                """if [ "${'$'}OWNED_MATCH" -eq 1 ]; then """ +
                """printf '%s\n' "${STATE_MARKER_PREFIX}mode=owned_running|port=${'$'}PORT|pid=${'$'}META_PID|start_time=${'$'}META_START"; """ +
                """printf '\033[1;32m✓ 端口 %s 已有本应用启动的 iperf3 服务端正在后台运行（PID %s），可直接开始测速\033[0m\n' "${'$'}PORT" "${'$'}META_PID"; """ +
                """elif printf '%s' "${'$'}PORT_INFO" | grep -qi "iperf3"; then """ +
                """rm -f "${'$'}META_FILE" "${'$'}PID_FILE" 2>/dev/null; """ +
                """printf '%s\n' "${STATE_MARKER_PREFIX}mode=external_reused|port=${'$'}PORT"; """ +
                """printf '\033[1;32m✓ 检测到端口 %s 已有外部 iperf3 服务端正在运行，已直接复用（非本应用启动，后续仅支持断开本地连接）\033[0m\n' "${'$'}PORT"; """ +
                """else """ +
                """printf '\033[1;31m✗ 端口 %s 已被其他进程占用，请更换端口后重试：\033[0m\n%s\n' "${'$'}PORT" "${'$'}PORT_INFO"; """ +
                """exit $EXIT_CODE_PORT_CONFLICT; """ +
                """fi; """ +
                """else """ +
                """rm -f "${'$'}PID_FILE" "${'$'}META_FILE" "${'$'}{META_FILE}.tmp" 2>/dev/null; """ +
                """printf '\033[1;36m▶ 正在后台启动 iperf3 服务端（端口 %s）…\033[0m\n' "${'$'}PORT"; """ +
                """(iperf3 -s -p "${'$'}PORT" -D -I "${'$'}PID_FILE" --logfile "${'$'}LOG_FILE" >/dev/null 2>&1 || iperf3 -s -p "${'$'}PORT" -D -I "${'$'}PID_FILE" >/dev/null 2>&1 || { nohup iperf3 -s -p "${'$'}PORT" >"${'$'}LOG_FILE" 2>&1 </dev/null & printf '%s\n' "${'$'}!" >"${'$'}PID_FILE"; }); """ +
                """STARTED_OK=0; LAUNCHED_PID=""; LAUNCHED_START=""; _try=0; """ +
                """while [ "${'$'}_try" -lt 10 ]; do """ +
                """if [ -f "${'$'}PID_FILE" ] && [ ! -L "${'$'}PID_FILE" ]; then LAUNCHED_PID=$(tr -dc '0-9' < "${'$'}PID_FILE" 2>/dev/null); fi; """ +
                """if [ -z "${'$'}LAUNCHED_PID" ]; then _pnow=$(anode_port_info "${'$'}PORT"); [ -n "${'$'}_pnow" ] && LAUNCHED_PID=$(anode_extract_pid "${'$'}_pnow"); fi; """ +
                """if [ -n "${'$'}LAUNCHED_PID" ] && anode_verify_target "${'$'}LAUNCHED_PID" "${'$'}PORT"; then """ +
                """LAUNCHED_START=$(anode_proc_start "${'$'}LAUNCHED_PID" 2>/dev/null || true); """ +
                """if [ -n "${'$'}LAUNCHED_START" ]; then STARTED_OK=1; break; fi; """ +
                """fi; """ +
                """sleep 0.2 2>/dev/null || sleep 1; _try=$((_try + 1)); """ +
                """done; """ +
                """if [ "${'$'}STARTED_OK" -eq 1 ]; then """ +
                """if printf 'pid=%s\nstart_time=%s\nport=%s\n' "${'$'}LAUNCHED_PID" "${'$'}LAUNCHED_START" "${'$'}PORT" > "${'$'}{META_FILE}.tmp" && mv -f "${'$'}{META_FILE}.tmp" "${'$'}META_FILE"; then """ +
                """chmod 600 "${'$'}META_FILE" 2>/dev/null || true; """ +
                """printf '%s\n' "${STATE_MARKER_PREFIX}mode=started|port=${'$'}PORT|pid=${'$'}LAUNCHED_PID|start_time=${'$'}LAUNCHED_START"; """ +
                """printf '\033[1;32m✓ iperf3 服务端已在后台守护运行（TCP/UDP 端口 %s，PID %s）\033[0m\n' "${'$'}PORT" "${'$'}LAUNCHED_PID"; """ +
                """else """ +
                """rm -f "${'$'}{META_FILE}.tmp" "${'$'}META_FILE" "${'$'}PID_FILE" 2>/dev/null; """ +
                """kill -TERM "${'$'}LAUNCHED_PID" 2>/dev/null || true; """ +
                """printf '\033[1;31m✗ 无法写入 iperf3 实例状态文件，已取消后台启动\033[0m\n'; exit 1; """ +
                """fi; """ +
                """else """ +
                """rm -f "${'$'}{META_FILE}.tmp" "${'$'}META_FILE" "${'$'}PID_FILE" 2>/dev/null; """ +
                """printf '\033[1;31m✗ iperf3 启动失败，请检查日志：%s\033[0m\n' "$(tail -n 5 "${'$'}LOG_FILE" 2>/dev/null || echo '无日志输出')"; exit 1; """ +
                """fi; """ +
                """fi""",
            // Firewall inspection & optional automatic rule opening (strictly non-interactive via sudo -n)
            """OPEN_FW=$openFwFlag""",
            """CAN_FW_PRIV=0; [ "${'$'}IS_ROOT" -eq 1 ] && CAN_FW_PRIV=1; [ "${'$'}IS_ROOT" -eq 0 ] && [ -n "${'$'}SUDO" ] && sudo -n true >/dev/null 2>&1 && CAN_FW_PRIV=1; FW_PREFIX=""; [ "${'$'}IS_ROOT" -eq 0 ] && [ "${'$'}CAN_FW_PRIV" -eq 1 ] && FW_PREFIX="sudo -n """",
            """if [ "${'$'}CAN_FW_PRIV" -eq 1 ] && command -v ufw >/dev/null 2>&1 && ${'$'}FW_PREFIX ufw status 2>/dev/null | grep -qi "Status: active"; then """ +
                """if [ "${'$'}OPEN_FW" -eq 1 ]; then ${'$'}FW_PREFIX ufw allow "${'$'}PORT/tcp" >/dev/null 2>&1 && printf '\033[1;32m✓ 已通过 ufw 自动放行端口 %s/tcp\033[0m\n' "${'$'}PORT"; """ +
                """else printf '\033[1;33m⚠ 检测到 ufw 防火墙已启用，若手机端连不上请放行：%sufw allow %s/tcp\033[0m\n' "${'$'}SUDO" "${'$'}PORT"; fi; """ +
                """elif [ "${'$'}CAN_FW_PRIV" -eq 1 ] && command -v firewall-cmd >/dev/null 2>&1 && ${'$'}FW_PREFIX firewall-cmd --state >/dev/null 2>&1; then """ +
                """if [ "${'$'}OPEN_FW" -eq 1 ]; then ${'$'}FW_PREFIX firewall-cmd --add-port="${'$'}PORT/tcp" >/dev/null 2>&1 && printf '\033[1;32m✓ 已通过 firewalld 自动放行端口 %s/tcp\033[0m\n' "${'$'}PORT"; """ +
                """else printf '\033[1;33m⚠ 检测到 firewalld 已启用，若手机端连不上请放行：%sfirewall-cmd --add-port=%s/tcp\033[0m\n' "${'$'}SUDO" "${'$'}PORT"; fi; """ +
                """elif [ "${'$'}CAN_FW_PRIV" -eq 1 ] && command -v iptables >/dev/null 2>&1 && ${'$'}FW_PREFIX iptables -S INPUT 2>/dev/null | grep -qE '^-P INPUT DROP|REJECT|DROP'; then """ +
                """if [ "${'$'}OPEN_FW" -eq 1 ]; then ${'$'}FW_PREFIX iptables -I INPUT -p tcp --dport "${'$'}PORT" -j ACCEPT >/dev/null 2>&1 && printf '\033[1;32m✓ 已通过 iptables 临时放行端口 %s/tcp\033[0m\n' "${'$'}PORT"; """ +
                """else printf '\033[1;33m⚠ 检测到 iptables 存在拦截规则，若连不上请放行：%siptables -I INPUT -p tcp --dport %s -j ACCEPT\033[0m\n' "${'$'}SUDO" "${'$'}PORT"; fi; """ +
                """elif [ "${'$'}CAN_FW_PRIV" -eq 0 ] && [ "${'$'}OPEN_FW" -eq 1 ]; then """ +
                """printf '\033[1;33m⚠ 当前非 root 账户未配置免密 sudo，已跳过自动防火墙操作；请手动放行：%sufw allow %s/tcp ｜ %sfirewall-cmd --add-port=%s/tcp ｜ %siptables -I INPUT -p tcp --dport %s -j ACCEPT\033[0m\n' "${'$'}SUDO" "$port" "${'$'}SUDO" "$port" "${'$'}SUDO" "$port"; """ +
                """else printf '\033[0;36mℹ 防火墙参考放行命令：ufw allow %s/tcp ｜ firewall-cmd --add-port=%s/tcp ｜ iptables -I INPUT -p tcp --dport %s -j ACCEPT\033[0m\n' "$port" "$port" "$port"; fi""",
            """printf '\033[0;37mℹ 若 VPS 位于阿里云/腾讯云/AWS/Oracle 等平台，请同时确认云控制台「安全组」已放行 TCP %s 端口。\033[0m\n' "${'$'}PORT"""",
        ).joinToString("; ")
    }
}
