package com.nodeseek.app.domain.ssh

import kotlinx.coroutines.flow.Flow

/**
 * Decrypted connection & TOFU parameters for a single bastion / ProxyJump hop
 * traversed in order before connecting to the target [SshConnectionRequest].
 */
data class SshJumpHop(
    val serverId: String? = null,
    val alias: String = "",
    val host: String,
    val port: Int = SshServerProfile.DEFAULT_PORT,
    val username: String = SshServerProfile.DEFAULT_USERNAME,
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val secret: String = "",
    val passphrase: String? = null,
    val expectedHostKeyFingerprint: String? = null,
    val allowHostKeyMismatchOverride: Boolean = false,
) {
    val displayLabel: String
        get() = alias.trim().ifBlank {
            if (port == SshServerProfile.DEFAULT_PORT) host.trim() else "${host.trim()}:$port"
        }
}

/**
 * Immutable connection & PTY execution parameters passed to an [SshTransport].
 */
data class SshConnectionRequest(
    val host: String,
    val port: Int = SshServerProfile.DEFAULT_PORT,
    val username: String = SshServerProfile.DEFAULT_USERNAME,
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val secret: String = "",
    val passphrase: String? = null,
    val expectedHostKeyFingerprint: String? = null,
    val allowHostKeyMismatchOverride: Boolean = false,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val ptyType: String = DEFAULT_PTY_TYPE,
    val ptyCols: Int = DEFAULT_PTY_COLS,
    val ptyRows: Int = DEFAULT_PTY_ROWS,
    val serverId: String? = null,
    val alias: String = "",
    val jumpHostId: String? = null,
    val jumpHosts: List<SshJumpHop> = emptyList(),
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 15_000
        const val DEFAULT_PTY_TYPE: String = "xterm-256color"
        const val DEFAULT_PTY_COLS: Int = 100
        const val DEFAULT_PTY_ROWS: Int = 32
    }
}

/**
 * Low-level streaming events emitted by an [SshTransport] while running a remote command.
 */
sealed interface SshStreamEvent {
    data class HostKeyObserved(
        val verification: HostKeyVerificationResult,
        val serverId: String? = null,
    ) : SshStreamEvent
    data object Connected : SshStreamEvent
    data class OutputChunk(val rawText: String) : SshStreamEvent
    data class Completed(val exitCode: Int) : SshStreamEvent
}

/**
 * Thrown before authentication whenever the remote SSH server's SHA-256 host key differs from
 * the previously trusted TOFU fingerprint stored in the vault.
 */
class SshHostKeyMismatchException(
    val expectedFingerprint: String,
    val actualHostKey: HostKeyInfo,
    val serverId: String? = null,
    val serverAlias: String? = null,
) : RuntimeException(
    "SSH host key changed for ${actualHostKey.host}:${actualHostKey.port}. " +
        "Expected $expectedFingerprint, got ${actualHostKey.fingerprintSha256}",
)

/**
 * Pluggable SSH execution transport.
 *
 * Production uses `JSchSshTransport` (`ChannelExec` with `xterm-256color` PTY);
 * JVM unit tests provide deterministic mock implementations.
 */
interface SshTransport {
    fun execute(
        request: SshConnectionRequest,
        command: String,
    ): Flow<SshStreamEvent>
}
