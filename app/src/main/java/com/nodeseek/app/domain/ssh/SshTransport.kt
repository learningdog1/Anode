package com.nodeseek.app.domain.ssh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

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
    data class HostKeyObserved(val verification: HostKeyVerificationResult) : SshStreamEvent
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
) : RuntimeException(
    "SSH host key changed for ${actualHostKey.host}:${actualHostKey.port}. " +
        "Expected $expectedFingerprint, got ${actualHostKey.fingerprintSha256}",
)

/**
 * Sink for writing automated or interactive responses directly to the remote PTY stdin stream.
 */
fun interface SshStdinWriter {
    fun write(input: String)
}

/**
 * Coroutine context element propagating [onStdinReady] into 2-arg [SshTransport.execute] implementations.
 */
class SshStdinCallbackContext(
    val onStdinReady: (SshStdinWriter) -> Unit,
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SshStdinCallbackContext>
}

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

    fun execute(
        request: SshConnectionRequest,
        command: String,
        onStdinReady: (SshStdinWriter) -> Unit,
    ): Flow<SshStreamEvent> = execute(
        request = request,
        command = command,
    ).flowOn(SshStdinCallbackContext(onStdinReady))
}
