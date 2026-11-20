package com.nodeseek.app.domain.ssh

/**
 * Rewrites raw or user-customized VPS benchmark shell commands into headless, non-interactive
 * invocations using upstream-verified CLI flags, environment variables, stdin answers, and
 * `stdbuf` line-buffering helpers.
 *
 * Verified upstream script behaviors:
 * - **YABS** (`masonr/yet-another-bench-script`): `getopts 'bfdignhr45796jw:s:p:'` — non-interactive
 *   by default; wrapped with `stdbuf -oL -eL` (when available) to minimize pipe buffering.
 * - **NodeQuality** (`LloydAsp/NodeQuality`, `run.NodeQuality.com`): `getopts "D:d:46Ee"` (note: `-n`
 *   and `-l` are NOT valid CLI flags and emit parameter errors; instead `ask_question()` reads 4
 *   lines from stdin for HardwareQuality `[y/f/v/n]`, IPQuality `[y/n]`, NetQuality `[y/l/n]`, and
 *   Backroute `[y/n]`).
 * - **RegionRestrictionCheck** (`lmc999/RegionRestrictionCheck`, `check.unlock.media`): `process()`
 *   accepts `-M 4|6`, `-R 0|1..11|66|88|99`, `-E en|zh`; omitting `-R` triggers interactive
 *   `inputOptions()` (`read -p`).
 * - **NextTrace** (`nxtrace/NTrace-core`, `nxtrace.org/nt`): `--fast-trace` (`-F`) always prompts
 *   twice on stdin (`1. IPv4 / 2. IPv6` then `1..8` ISP target) via `promptFastTraceChoice`.
 * - **ECS / 融合怪** (`oneclickvirt/ecs` `goecs` & legacy `spiritLHLS/ecs` `ecs.sh`): `goecs.sh`
 *   reads `noninteractive=true` to skip mirror/package prompts, while `goecs` binary requires
 *   explicit `-menu=false` to bypass the interactive TUI menu (`ecs.sh` uses `-m 1`).
 * - **Bench.sh** (`teddysun/across`): passes `--accept-license --accept-gdpr` internally; wrapped
 *   with optional `stdbuf -oL -eL` for line-buffered output.
 */
object SshCommandRewriter {
    const val HEADLESS_ENV_PREFIX: String =
        "export TERM=xterm-256color DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a noninteractive=true"

    const val STDBUF_INLINE_HELPER: String =
        "\$(command -v stdbuf >/dev/null 2>&1 && echo \"stdbuf -oL -eL\")"

    /**
     * Rewrites [rawCommand] into a headless non-interactive command while keeping already-adapted
     * commands idempotent.
     */
    fun rewriteToNonInteractive(rawCommand: String): String {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) return ""

        val bodyWithoutEnv = stripLeadingHeadlessEnv(trimmed)
        var rewritten = bodyWithoutEnv

        rewritten = rewriteNodeQualityIfNeeded(rewritten)
        rewritten = rewriteRegionRestrictionCheckIfNeeded(rewritten)
        rewritten = rewriteNextTraceFastTraceIfNeeded(rewritten)
        rewritten = rewriteGoEcsIfNeeded(rewritten)
        rewritten = rewriteLegacyEcsShellIfNeeded(rewritten)
        rewritten = rewritePackageManagersIfNeeded(rewritten)
        rewritten = rewritePipedBenchLineBufferingIfNeeded(rewritten)

        return applyHeadlessEnvPrefix(rewritten)
    }

    private fun stripLeadingHeadlessEnv(command: String): String {
        return when {
            command.startsWith("$HEADLESS_ENV_PREFIX;") ->
                command.removePrefix("$HEADLESS_ENV_PREFIX;").trim()
            command.startsWith("$LEGACY_HEADLESS_ENV_PREFIX;") ->
                command.removePrefix("$LEGACY_HEADLESS_ENV_PREFIX;").trim()
            else -> command
        }
    }

    private fun applyHeadlessEnvPrefix(command: String): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.contains("TERM=xterm-256color") &&
            trimmed.contains("DEBIAN_FRONTEND=noninteractive") &&
            trimmed.contains("noninteractive=true")
        ) {
            trimmed
        } else {
            "$HEADLESS_ENV_PREFIX; $trimmed"
        }
    }

    /**
     * Rewrites direct `bash <(curl ... run.NodeQuality.com) [-n] [-l] [-4|-6|-E]` calls into
     * 4-line stdin answers (`hq`, `iq`, `nq`, `bt`) + valid `getopts "D:d:46Ee"` flags only.
     */
    private fun rewriteNodeQualityIfNeeded(command: String): String {
        if (!command.contains("NodeQuality", ignoreCase = true)) return command
        // Already adapted with explicit stdin pipe or NQ_HQ variable wrapper
        if (command.contains("NQ_HQ=") || PIPE_BEFORE_NODEQUALITY_REGEX.containsMatchIn(command)) {
            return command
        }

        return NODEQUALITY_DIRECT_REGEX.replace(command) { match ->
            val bashInvocation = match.groupValues[1]
            val rawTailArgs = match.groupValues[2].trim()
            var hqAnswer = "y"
            var nqAnswer = "y"
            val validCliFlags = mutableListOf<String>()

            if (rawTailArgs.isNotEmpty()) {
                val tokens = rawTailArgs.split(Regex("""\s+""")).filter { it.isNotEmpty() }
                var idx = 0
                while (idx < tokens.size) {
                    when (val token = tokens[idx]) {
                        "-n" -> {
                            // Strip invalid -n flag (not in getopts "D:d:46Ee")
                        }
                        "-l", "--low-data" -> {
                            nqAnswer = "l"
                        }
                        "-f" -> {
                            hqAnswer = "f"
                        }
                        "-4", "-6", "-E", "-e" -> {
                            validCliFlags += token
                        }
                        "-D", "-d" -> {
                            validCliFlags += token
                            if (idx + 1 < tokens.size) {
                                validCliFlags += tokens[idx + 1]
                                idx += 1
                            }
                        }
                        else -> validCliFlags += token
                    }
                    idx += 1
                }
            }

            val suffix = if (validCliFlags.isEmpty()) "" else " ${validCliFlags.joinToString(" ")}"
            "printf '$hqAnswer\\ny\\n$nqAnswer\\ny\\n' | $bashInvocation$suffix"
        }
    }

    /**
     * Ensures `lmc999/RegionRestrictionCheck` (`check.sh` / `check.unlock.media`) includes `-R`
     * so `inputOptions()` never blocks waiting for interactive region number selection.
     */
    private fun rewriteRegionRestrictionCheckIfNeeded(command: String): String {
        if (!command.contains("RegionRestrictionCheck", ignoreCase = true) &&
            !command.contains("check.unlock.media", ignoreCase = true)
        ) {
            return command
        }
        if (REGION_FLAG_REGEX.containsMatchIn(command)) {
            return command
        }
        return REGION_CHECK_INVOCATION_REGEX.replace(command) { match ->
            "${match.value} -R 0"
        }
    }

    /**
     * Ensures `nexttrace --fast-trace` / `nexttrace -F` receives its 2 required stdin lines
     * (`1=IPv4 / 2=IPv6`, `1=北京三网快速测试`) when not already piped.
     */
    private fun rewriteNextTraceFastTraceIfNeeded(command: String): String {
        if (!command.contains("nexttrace", ignoreCase = true)) return command
        if (!NEXTTRACE_FAST_FLAG_REGEX.containsMatchIn(command)) return command
        if (PIPE_BEFORE_NEXTTRACE_REGEX.containsMatchIn(command)) return command

        val ipChoice = if (NEXTTRACE_IPV6_FLAG_REGEX.containsMatchIn(command)) "2" else "1"
        val match = NEXTTRACE_CMD_START_REGEX.find(command) ?: return command
        val prefix = match.groupValues[1]
        return command.replaceRange(match.range, "${prefix}printf '$ipChoice\\n1\\n' | nexttrace")
    }

    /**
     * Appends `-menu=false` to standalone `goecs` binary invocations that omit `-menu`,
     * since `goecs` defaults to `-menu=true` even when `noninteractive=true` is exported.
     */
    private fun rewriteGoEcsIfNeeded(command: String): String {
        if (!command.contains("goecs")) return command
        return GOECS_BIN_INVOCATION_REGEX.replace(command) { match ->
            val full = match.value
            val args = match.groupValues[2]
            if (args.contains("-menu") ||
                args.contains("-h") ||
                args.contains("-v") ||
                args.trimStart().startsWith("install") ||
                args.trimStart().startsWith("env") ||
                args.trimStart().startsWith("upgrade") ||
                args.trimStart().startsWith("uninstall")
            ) {
                full
            } else {
                val trimmedFull = full.trimEnd()
                val trailingSpace = full.substring(trimmedFull.length)
                "$trimmedFull -menu=false$trailingSpace"
            }
        }
    }

    /**
     * Appends `-m 1` (if no `-m` option was supplied) and pipes `printf '\n'` (when not already
     * piped) to legacy `spiritLHLS/ecs` (`bash ecs.sh` / `./ecs.sh`) so its optional BBR prompt
     * (`Enter to skip`) is safely skipped without modifying `/etc/sysctl.conf`.
     */
    private fun rewriteLegacyEcsShellIfNeeded(command: String): String {
        if (!command.contains("ecs.sh") || command.contains("goecs.sh")) return command
        val alreadyPiped = PIPE_BEFORE_LEGACY_ECS_REGEX.containsMatchIn(command)
        return LEGACY_ECS_SH_REGEX.replace(command) { match ->
            val prefix = match.groupValues[1]
            val invoke = match.groupValues[2]
            val args = match.groupValues[3]
            val pipePrefix = if (alreadyPiped) "" else "printf '\\n' | "
            val withMenuFlag = if (args.contains("-m")) {
                "$invoke$args"
            } else {
                val trimmed = "$invoke$args".trimEnd()
                val trailingSpace = "$invoke$args".substring(trimmed.length)
                "$trimmed -m 1$trailingSpace"
            }
            "$prefix$pipePrefix$withMenuFlag"
        }
    }

    /**
     * Adds `-y` to `apt-get install`, `apt install`, `yum install`, or `dnf install` segments
     * that do not already pass `-y` / `--yes` / `--assume-yes`.
     */
    private fun rewritePackageManagersIfNeeded(command: String): String {
        if (!PKG_INSTALL_HINT_REGEX.containsMatchIn(command)) return command
        return PKG_INSTALL_SEGMENT_REGEX.replace(command) { match ->
            val full = match.value
            if (PKG_YES_FLAG_REGEX.containsMatchIn(full)) {
                full
            } else {
                val tool = match.groupValues[1]
                val rest = match.groupValues[2]
                "$tool -y install$rest"
            }
        }
    }

    /**
     * Injects conditional `stdbuf -oL -eL` before `bash` on piped `yabs.sh` or `bench.sh` commands
     * so piped sub-command output flushes line-by-line where GNU coreutils `stdbuf` is present.
     */
    private fun rewritePipedBenchLineBufferingIfNeeded(command: String): String {
        if (command.contains("stdbuf")) return command
        if (!command.contains("yabs.sh", ignoreCase = true) &&
            !command.contains("bench.sh", ignoreCase = true)
        ) {
            return command
        }
        val match = PIPED_BASH_REGEX.find(command) ?: return command
        val argsAfterBash = match.groupValues[1]
        return command.replaceRange(match.range, "| $STDBUF_INLINE_HELPER bash$argsAfterBash")
    }

    private const val LEGACY_HEADLESS_ENV_PREFIX: String =
        "export TERM=xterm-256color DEBIAN_FRONTEND=noninteractive"

    private val PIPE_BEFORE_NODEQUALITY_REGEX = Regex(
        """\|\s*(?:sudo\s+)?bash\s+<\([^)]*NodeQuality[^)]*\)""",
        RegexOption.IGNORE_CASE,
    )

    private val NODEQUALITY_DIRECT_REGEX = Regex(
        """((?:sudo\s+)?bash\s+<\([^)]*NodeQuality\.com[^)]*\))([^\n;&|]*)""",
        RegexOption.IGNORE_CASE,
    )

    private val REGION_FLAG_REGEX = Regex("""(?:^|\s)(?:-R|--region)(?:\s|$)""")

    private val REGION_CHECK_INVOCATION_REGEX = Regex(
        """(?:sudo\s+)?bash\s+<\([^)]*(?:RegionRestrictionCheck/main/check\.sh|check\.unlock\.media)[^)]*\)[^\n;&|]*""",
        RegexOption.IGNORE_CASE,
    )

    private val NEXTTRACE_FAST_FLAG_REGEX = Regex("""(?:^|\s)(?:--fast-trace|-F)(?:\s|$)""")
    private val NEXTTRACE_IPV6_FLAG_REGEX = Regex("""(?:^|\s)(?:--ipv6|-6)(?:\s|$)""")
    private val PIPE_BEFORE_NEXTTRACE_REGEX = Regex("""\|\s*(?:\S*/)?nexttrace\b""", RegexOption.IGNORE_CASE)
    private val NEXTTRACE_CMD_START_REGEX = Regex("""(^|[;&]\s*|&&\s*|\|\|\s*)nexttrace\b""")

    private val GOECS_BIN_INVOCATION_REGEX = Regex(
        """(?:^|[;&|]\s*|&&\s*|\|\|\s*)((?:\./|/usr/bin/|/usr/local/bin/)?goecs)(?!\.sh)\b([^\n;&|]*)""",
    )

    private val PIPE_BEFORE_LEGACY_ECS_REGEX = Regex(
        """\|\s*(?:bash\s+|sh\s+|\./)ecs\.sh\b""",
    )

    private val LEGACY_ECS_SH_REGEX = Regex(
        """(^|[;&]\s*|&&\s*|\|\|\s*)((?:bash\s+|sh\s+|\./)ecs\.sh)\b([^\n;&|]*)""",
    )

    private val PKG_INSTALL_HINT_REGEX = Regex("""\b(?:apt-get|apt|yum|dnf)\s+install\b""")
    private val PKG_INSTALL_SEGMENT_REGEX = Regex(
        """\b((?:sudo\s+)?(?:apt-get|apt|yum|dnf))\s+install(\s+[^\n;&|]+)""",
    )
    private val PKG_YES_FLAG_REGEX = Regex("""(?:^|\s)(?:-y|--yes|--assume-yes)(?:\s|$)""")

    private val PIPED_BASH_REGEX = Regex("""\|\s*bash(\b[^\n;&|]*)""")
}
