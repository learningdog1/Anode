package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

/**
 * User-configurable policy for automatic PTY interactive prompt answering during headless SSH runs.
 */
@Immutable
data class SshAutoRespondConfig(
    val enabled: Boolean = true,
    val maxResponsesPerRun: Int = DEFAULT_MAX_RESPONSES_PER_RUN,
) {
    companion object {
        const val DEFAULT_MAX_RESPONSES_PER_RUN: Int = 16
    }
}

/**
 * Describes a single automated reply written to the remote PTY stdin when an interactive prompt
 * was detected in the output stream.
 */
@Immutable
data class SshAutoRespondDecision(
    val response: String,
    val matchedPromptLine: String,
    val responseNumber: Int,
)

/**
 * Stateful streaming detector that watches raw PTY chunks for common interactive confirmation
 * prompts (`[y/N]`, `(Y/n)`, `[y/f/v/n]`, `[y/l/n]`, `Press Enter`, `按回车`, `请选择选项：`) and
 * emits safe, bounded automatic replies as a fallback when a benchmark script still prompts.
 *
 * Anti-misfire guarantees:
 * - Evaluates ONLY the active trailing line at the very end of the stream (never historical lines).
 * - Requires seeing a newline after each auto-reply before firing again, preventing duplicate
 *   answers when a prompt arrives across multiple TCP/PTY chunks.
 * - Strictly denylists credential prompts (`password`, `passphrase`, `otp`, `sudo`), SSH host-key
 *   prompts (`continue connecting`, `fingerprint`), help/flag documentation lines, and destructive
 *   operations (`rm -rf`, `reboot`, `shutdown`, `mkfs`, `format`, `uninstall`).
 * - Enforces [SshAutoRespondConfig.maxResponsesPerRun] to prevent infinite loops.
 */
class SshPromptAutoResponder(
    private val config: SshAutoRespondConfig = SshAutoRespondConfig(),
) {
    private val plainWindow = StringBuilder()
    private var escState: Int = ESC_NONE
    private var awaitingNewlineSinceLastReply: Boolean = false

    var responseCount: Int = 0
        private set

    /**
     * Feeds a newly arrived raw PTY chunk and returns an [SshAutoRespondDecision] if and only if
     * the stream is currently parked at a safe, recognized interactive prompt.
     */
    fun onOutputChunk(rawChunk: String): SshAutoRespondDecision? {
        if (rawChunk.isEmpty()) return null
        appendPlainStripped(rawChunk)

        if (!config.enabled || responseCount >= config.maxResponsesPerRun) {
            return null
        }
        if (awaitingNewlineSinceLastReply) {
            return null
        }

        val snapshot = plainWindow.toString()
        if (snapshot.isBlank()) return null

        val endsWithNewline = snapshot.endsWith('\n')
        val candidateLine = extractActiveTrailingLine(snapshot, endsWithNewline)
            .trim()
        if (candidateLine.isEmpty() || candidateLine.length > MAX_PROMPT_LINE_CHARS) {
            return null
        }

        if (isExcludedLine(candidateLine)) {
            return null
        }

        val reply = matchPromptReply(candidateLine, endsWithNewline) ?: return null
        responseCount += 1
        awaitingNewlineSinceLastReply = true
        return SshAutoRespondDecision(
            response = reply,
            matchedPromptLine = candidateLine,
            responseNumber = responseCount,
        )
    }

    fun reset() {
        plainWindow.clear()
        escState = ESC_NONE
        awaitingNewlineSinceLastReply = false
        responseCount = 0
    }

    private fun extractActiveTrailingLine(text: String, endsWithNewline: Boolean): String {
        if (!endsWithNewline) {
            val lastNl = text.lastIndexOf('\n')
            val afterNl = if (lastNl >= 0) text.substring(lastNl + 1) else text
            val lastCr = afterNl.lastIndexOf('\r')
            return if (lastCr >= 0) afterNl.substring(lastCr + 1) else afterNl
        }
        // Stream ended with '\n' (or '\r\n' on PTY): inspect the single line preceding trailing newline
        val trimmedTrailingNl = text.trimEnd(' ', '\t')
        if (!trimmedTrailingNl.endsWith('\n')) return ""
        val withoutFinalNl = trimmedTrailingNl.dropLast(1).removeSuffix("\r")
        // If there are multiple trailing blank lines, script is printing spacing, not prompting
        if (withoutFinalNl.endsWith('\n')) return ""
        val prevNl = withoutFinalNl.lastIndexOf('\n')
        val line = (if (prevNl >= 0) withoutFinalNl.substring(prevNl + 1) else withoutFinalNl)
            .trimEnd('\r')
        val lastCr = line.lastIndexOf('\r')
        return if (lastCr >= 0) line.substring(lastCr + 1) else line
    }

    private fun isExcludedLine(line: String): Boolean {
        val trimmed = line.trimStart()
        // Never trigger on CLI flag documentation, shell comments, or command previews
        if (trimmed.startsWith("-") ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("Usage:", ignoreCase = true) ||
            trimmed.startsWith("Example:", ignoreCase = true) ||
            trimmed.startsWith("Options:", ignoreCase = true) ||
            trimmed.startsWith("Flags:", ignoreCase = true)
        ) {
            return true
        }
        return EXCLUDED_PROMPT_REGEX.containsMatchIn(trimmed)
    }

    private fun matchPromptReply(line: String, endsWithNewline: Boolean): String? {
        // 1. Explicit [y/N], (Y/n), [y/n], ([y]/n), [y/f/v/n], [y/l/n] confirmation prompts
        if (YES_NO_BRACKET_PROMPT_REGEX.containsMatchIn(line)) {
            // If terminated by newline, require question/colon ending so prose lines never match
            if (!endsWithNewline || PROMPT_PUNCTUATION_END_REGEX.containsMatchIn(line)) {
                return "y\n"
            }
        }

        // 2. Press Enter / Return / 按回车 / 直接按回车 prompts
        if (!endsWithNewline && ENTER_PROMPT_INLINE_REGEX.containsMatchIn(line)) {
            return "\n"
        }
        if (endsWithNewline && ENTER_PROMPT_STANDALONE_LINE_REGEX.matches(line)) {
            return "\n"
        }

        // 3. Trailing menu default-selection prompts (e.g. NextTrace "请选择选项：")
        if (!endsWithNewline && MENU_DEFAULT_CHOICE_PROMPT_REGEX.containsMatchIn(line)) {
            return "\n"
        }

        return null
    }

    private fun appendPlainStripped(rawChunk: String) {
        for (ch in rawChunk) {
            when (escState) {
                ESC_NONE -> {
                    when (ch) {
                        '\u001B' -> escState = ESC_SEEN
                        '\n' -> {
                            plainWindow.append('\n')
                            awaitingNewlineSinceLastReply = false
                        }
                        '\r' -> plainWindow.append('\r')
                        '\b' -> {
                            if (plainWindow.isNotEmpty() &&
                                plainWindow.last() != '\n' &&
                                plainWindow.last() != '\r'
                            ) {
                                plainWindow.deleteCharAt(plainWindow.length - 1)
                            }
                        }
                        '\t' -> plainWindow.append(' ')
                        else -> if (ch.code >= 0x20 && ch.code != 0x7F) {
                            plainWindow.append(ch)
                        }
                    }
                }
                ESC_SEEN -> {
                    escState = when (ch) {
                        '[' -> ESC_CSI
                        ']', 'P', 'X', '^', '_' -> ESC_OSC
                        '(', ')', '*', '+', '#', ' ', '%' -> ESC_SINGLE_ARG
                        else -> ESC_NONE
                    }
                }
                ESC_SINGLE_ARG -> escState = ESC_NONE
                ESC_CSI -> {
                    if (ch.code in 0x40..0x7E) {
                        escState = ESC_NONE
                    } else if (ch.code !in 0x20..0x3F) {
                        escState = ESC_NONE
                    }
                }
                ESC_OSC -> {
                    when (ch) {
                        '\u0007' -> escState = ESC_NONE
                        '\u001B' -> escState = ESC_OSC_ESC
                    }
                }
                ESC_OSC_ESC -> {
                    escState = if (ch == '\\') ESC_NONE else ESC_OSC
                }
            }
        }

        if (plainWindow.length > MAX_ROLLING_WINDOW_CHARS) {
            plainWindow.delete(0, plainWindow.length - TRIMMED_WINDOW_CHARS)
        }
    }

    companion object {
        private const val ESC_NONE = 0
        private const val ESC_SEEN = 1
        private const val ESC_SINGLE_ARG = 2
        private const val ESC_CSI = 3
        private const val ESC_OSC = 4
        private const val ESC_OSC_ESC = 5

        private const val MAX_PROMPT_LINE_CHARS = 160
        private const val MAX_ROLLING_WINDOW_CHARS = 1_024
        private const val TRIMMED_WINDOW_CHARS = 512

        // Matches trailing [y/N], (Y/n), [y/n], ([y]/n), [y/f/v/n], [y/l/n], (yes/no)
        private val YES_NO_BRACKET_PROMPT_REGEX = Regex(
            """(?:\[\s*\[?y\]?\s*(?:/\s*[a-zA-Z])*\s*/\s*\[?n\]?\s*]|\(\s*\[?y\]?\s*/\s*\[?n\]?\s*\)|\(\s*yes\s*/\s*no\s*\))\s*[?:：>]*\s*$""",
            RegexOption.IGNORE_CASE,
        )

        private val PROMPT_PUNCTUATION_END_REGEX = Regex("""[?？:：]\s*$""")

        // Matches inline prompts waiting for Enter (no trailing newline)
        private val ENTER_PROMPT_INLINE_REGEX = Regex(
            """(?:press\s+(?:enter|return|any\s+key)|hit\s+enter|按回车|直接按回车|直接回车|回车继续|按任意键继续)[^\n]*$""",
            RegexOption.IGNORE_CASE,
        )

        // Matches standalone echo-before-read Enter prompt lines (terminated by newline)
        private val ENTER_PROMPT_STANDALONE_LINE_REGEX = Regex(
            """^(?:please\s+)?(?:press|hit)\s+(?:enter|return|any\s+key)(?:\s+to\s+(?:continue|start|proceed|exit|test[\w\s]*))?[.!:：…\s]*$|^(?:请)?(?:按回车|直接按回车|直接回车|按任意键)(?:键)?(?:继续|开始|退出|将进行[^\n]{0,20})?[.!。！:：…\s]*$""",
            RegexOption.IGNORE_CASE,
        )

        // Matches trailing interactive default choice prompts (e.g. NextTrace "请选择选项：")
        private val MENU_DEFAULT_CHOICE_PROMPT_REGEX = Regex(
            """(?:请选择选项|请输入选项|请输入正确数字或直接按回车|Please Input the Correct Number or Press ENTER)\s*[:：]\s*$""",
            RegexOption.IGNORE_CASE,
        )

        // Denylist for credentials, host keys, sudo, and destructive operations
        private val EXCLUDED_PROMPT_REGEX = Regex(
            """\b(?:password|passphrase|sudo|otp|2fa|token|continue\s+connecting|fingerprint|reboot|shutdown|poweroff|format|parted|mkfs|fdisk|wipefs|wipe|erase|overwrite|destroy|uninstall)\b|rm\s+-rf|密码|口令|验证码|重启|关机|格式化|卸载|删除""",
            RegexOption.IGNORE_CASE,
        )
    }
}
