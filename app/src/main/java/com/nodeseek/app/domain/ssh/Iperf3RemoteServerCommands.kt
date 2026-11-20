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
 * Pure-Kotlin command builder and `/etc/os-release` classifier for 1-click remote VPS `iperf3`
 * server management (pre-flight check, distro-adapted installation, daemon startup, port-conflict
 * detection, firewall guidance/opening, and clean shutdown).
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
     * 2. Checks TCP [port] occupancy (`ss` / `netstat` / `lsof`); reuses an already-running
     *    `iperf3` daemon on [port] or aborts with [EXIT_CODE_PORT_CONFLICT] (`43`) if another
     *    process holds the port.
     * 3. Starts `iperf3 -s -p <port> -D` with `nohup` fallback and verifies the daemon is alive.
     * 4. Inspects `ufw` / `firewalld` / `iptables` (optionally opening TCP [port] when
     *    [autoOpenFirewall] is `true`) and prints clear firewall & cloud security-group guidance.
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
     * installs `iperf3` using the distro-specific command and immediately starts the daemon.
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
     * Builds the SSH shell script to cleanly stop running `iperf3 -s` daemon(s) on [port].
     */
    fun buildStopScript(port: Int = DEFAULT_PORT): String {
        require(isValidPort(port)) { "端口必须在 $MIN_PORT ~ $MAX_PORT 之间" }
        val matchExpr = if (port == DEFAULT_PORT) {
            "iperf3[[:space:]]+-s"
        } else {
            "iperf3.*-p[[:space:]]*$port([[:space:]]|$)|iperf3.*--port[=[:space:]]*$port([[:space:]]|$)"
        }
        val script = listOf(
            """export PATH="${'$'}PATH:/usr/local/bin:/usr/sbin:/sbin"""",
            """PORT=$port""",
            """IS_ROOT=0; [ "$(id -u 2>/dev/null || echo 1)" -eq 0 ] && IS_ROOT=1""",
            """HAS_SUDO=0; command -v sudo >/dev/null 2>&1 && HAS_SUDO=1""",
            """SUDO=""; [ "${'$'}IS_ROOT" -eq 0 ] && [ "${'$'}HAS_SUDO" -eq 1 ] && SUDO="sudo """",
            """PID_FILE="/tmp/anode_iperf3_${'$'}PORT.pid"""",
            """STOPPED=0""",
            """if [ -f "${'$'}PID_FILE" ]; then PID_VAL=$(cat "${'$'}PID_FILE" 2>/dev/null); if [ -n "${'$'}PID_VAL" ]; then (${'$'}SUDO kill "${'$'}PID_VAL" 2>/dev/null || kill "${'$'}PID_VAL" 2>/dev/null) && STOPPED=1; fi; rm -f "${'$'}PID_FILE" 2>/dev/null; fi""",
            """if command -v pkill >/dev/null 2>&1; then (${'$'}SUDO pkill -f '$matchExpr' 2>/dev/null || pkill -f '$matchExpr' 2>/dev/null) && STOPPED=1; elif command -v killall >/dev/null 2>&1; then (${'$'}SUDO killall iperf3 2>/dev/null || killall iperf3 2>/dev/null) && STOPPED=1; fi""",
            """sleep 1""",
            """STILL_UP="" ; if command -v ss >/dev/null 2>&1; then STILL_UP=$(${'$'}SUDO ss -tlnp "sport = :${'$'}PORT" 2>/dev/null | grep iperf3 || ss -tln "sport = :${'$'}PORT" 2>/dev/null | tail -n +2); fi""",
            """if [ -n "${'$'}STILL_UP" ] && command -v pkill >/dev/null 2>&1; then ${'$'}SUDO pkill -9 -f '$matchExpr' 2>/dev/null || pkill -9 -f '$matchExpr' 2>/dev/null || true; fi""",
            """if [ "${'$'}STOPPED" -eq 1 ]; then printf '\033[1;32m✓ 已停止 VPS 上的 iperf3 服务端（端口 %s）\033[0m\n' "${'$'}PORT"; else printf '\033[1;33mℹ 当前端口 %s 未发现正在运行的 iperf3 服务端进程\033[0m\n' "${'$'}PORT"; fi""",
        ).joinToString("; ")
        return SshPresetCatalog.wrapHeadlessCommand(script)
    }

    /**
     * Parses [MISSING_MARKER_PREFIX] from sanitized SSH output when exit code is [EXIT_CODE_NOT_INSTALLED].
     */
    fun parseMissingProbeOutput(sanitizedOutput: String): Iperf3MissingProbeResult? {
        val markerLine = sanitizedOutput
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains(MISSING_MARKER_PREFIX) }
            ?: return null

        val payload = markerLine.substringAfter(MISSING_MARKER_PREFIX)
        val kv = mutableMapOf<String, String>()
        payload.split('|').forEach { segment ->
            val idx = segment.indexOf('=')
            if (idx > 0) {
                kv[segment.substring(0, idx).trim()] = segment.substring(idx + 1).trim()
            }
        }

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

    private fun buildStartAndFirewallBody(
        port: Int,
        autoOpenFirewall: Boolean,
    ): String {
        val openFwFlag = if (autoOpenFirewall) "1" else "0"
        return listOf(
            """PID_FILE="/tmp/anode_iperf3_${'$'}PORT.pid"""",
            """LOG_FILE="/tmp/anode_iperf3_${'$'}PORT.log"""",
            // Check port occupancy with ss / netstat / lsof
            """PORT_INFO="" ; """ +
                """if command -v ss >/dev/null 2>&1; then PORT_INFO=$(${'$'}SUDO ss -tlnp 2>/dev/null | awk -v p=":${'$'}PORT" '${'$'}4 ~ p"$" {print ${'$'}0}' || ss -tlnp 2>/dev/null | awk -v p=":${'$'}PORT" '${'$'}4 ~ p"$" {print ${'$'}0}'); """ +
                """elif command -v netstat >/dev/null 2>&1; then PORT_INFO=$(${'$'}SUDO netstat -tlnp 2>/dev/null | awk -v p=":${'$'}PORT" '${'$'}4 ~ p"$" && ${'$'}6 == "LISTEN" {print ${'$'}0}'); """ +
                """elif command -v lsof >/dev/null 2>&1; then PORT_INFO=$(${'$'}SUDO lsof -nP -iTCP:"${'$'}PORT" -sTCP:LISTEN 2>/dev/null | tail -n +2); fi""",
            """if [ -n "${'$'}PORT_INFO" ]; then """ +
                """if printf '%s' "${'$'}PORT_INFO" | grep -qi "iperf3"; then """ +
                """printf '\033[1;32m✓ 端口 %s 已有 iperf3 服务端正在后台运行，可直接开始测速\033[0m\n' "${'$'}PORT"; """ +
                """else """ +
                """printf '\033[1;31m✗ 端口 %s 已被其他进程占用，请更换端口后重试：\033[0m\n%s\n' "${'$'}PORT" "${'$'}PORT_INFO"; """ +
                """exit $EXIT_CODE_PORT_CONFLICT; """ +
                """fi; """ +
                """else """ +
                """printf '\033[1;36m▶ 正在后台启动 iperf3 服务端（端口 %s）…\033[0m\n' "${'$'}PORT"; """ +
                """(iperf3 -s -p "${'$'}PORT" -D -I "${'$'}PID_FILE" --logfile "${'$'}LOG_FILE" >/dev/null 2>&1 || iperf3 -s -p "${'$'}PORT" -D >/dev/null 2>&1 || nohup iperf3 -s -p "${'$'}PORT" >"${'$'}LOG_FILE" 2>&1 </dev/null &); """ +
                """sleep 1; """ +
                """STARTED_OK=0; """ +
                """if command -v ss >/dev/null 2>&1 && ss -tln 2>/dev/null | awk -v p=":${'$'}PORT" '${'$'}4 ~ p"$" {found=1} END{exit !found}'; then STARTED_OK=1; """ +
                """elif command -v pgrep >/dev/null 2>&1 && pgrep -f "iperf3.*-s" >/dev/null 2>&1; then STARTED_OK=1; """ +
                """elif ps ax 2>/dev/null | grep -v grep | grep -q "iperf3.*-s"; then STARTED_OK=1; fi; """ +
                """if [ "${'$'}STARTED_OK" -eq 1 ]; then """ +
                """printf '\033[1;32m✓ iperf3 服务端已在后台守护运行（TCP/UDP 端口 %s）\033[0m\n' "${'$'}PORT"; """ +
                """else """ +
                """printf '\033[1;31m✗ iperf3 启动失败，请检查日志：%s\033[0m\n' "$(tail -n 5 "${'$'}LOG_FILE" 2>/dev/null || echo '无日志输出')"; exit 1; """ +
                """fi; """ +
                """fi""",
            // Firewall inspection & optional automatic rule opening
            """OPEN_FW=$openFwFlag""",
            """if command -v ufw >/dev/null 2>&1 && ${'$'}SUDO ufw status 2>/dev/null | grep -qi "Status: active"; then """ +
                """if [ "${'$'}OPEN_FW" -eq 1 ]; then ${'$'}SUDO ufw allow "${'$'}PORT/tcp" >/dev/null 2>&1 && printf '\033[1;32m✓ 已通过 ufw 自动放行端口 %s/tcp\033[0m\n' "${'$'}PORT"; """ +
                """else printf '\033[1;33m⚠ 检测到 ufw 防火墙已启用，若手机端连不上请放行：%sufw allow %s/tcp\033[0m\n' "${'$'}SUDO" "${'$'}PORT"; fi; """ +
                """elif command -v firewall-cmd >/dev/null 2>&1 && ${'$'}SUDO firewall-cmd --state >/dev/null 2>&1; then """ +
                """if [ "${'$'}OPEN_FW" -eq 1 ]; then ${'$'}SUDO firewall-cmd --add-port="${'$'}PORT/tcp" >/dev/null 2>&1 && printf '\033[1;32m✓ 已通过 firewalld 自动放行端口 %s/tcp\033[0m\n' "${'$'}PORT"; """ +
                """else printf '\033[1;33m⚠ 检测到 firewalld 已启用，若手机端连不上请放行：%sfirewall-cmd --add-port=%s/tcp\033[0m\n' "${'$'}SUDO" "${'$'}PORT"; fi; """ +
                """elif command -v iptables >/dev/null 2>&1 && ${'$'}SUDO iptables -S INPUT 2>/dev/null | grep -qE '^-P INPUT DROP|REJECT|DROP'; then """ +
                """if [ "${'$'}OPEN_FW" -eq 1 ]; then ${'$'}SUDO iptables -I INPUT -p tcp --dport "${'$'}PORT" -j ACCEPT >/dev/null 2>&1 && printf '\033[1;32m✓ 已通过 iptables 临时放行端口 %s/tcp\033[0m\n' "${'$'}PORT"; """ +
                """else printf '\033[1;33m⚠ 检测到 iptables 存在拦截规则，若连不上请放行：%siptables -I INPUT -p tcp --dport %s -j ACCEPT\033[0m\n' "${'$'}SUDO" "${'$'}PORT"; fi; """ +
                """else printf '\033[0;36mℹ 防火墙参考放行命令：ufw allow %s/tcp ｜ firewall-cmd --add-port=%s/tcp ｜ iptables -I INPUT -p tcp --dport %s -j ACCEPT\033[0m\n' "$port" "$port" "$port"; fi""",
            """printf '\033[0;37mℹ 若 VPS 位于阿里云/腾讯云/AWS/Oracle 等平台，请同时确认云控制台「安全组」已放行 TCP %s 端口。\033[0m\n' "${'$'}PORT"""",
        ).joinToString("; ")
    }
}
