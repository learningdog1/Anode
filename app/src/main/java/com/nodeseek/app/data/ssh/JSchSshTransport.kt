package com.nodeseek.app.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-Java [SshTransport] implementation powered by `com.github.mwiede:jsch`.
 *
 * Key design guarantees:
 * - Enforces Trust-On-First-Use (TOFU) host key verification inside [HostKeyRepository.check]
 *   *before* sending user credentials, aborting with [SshHostKeyMismatchException] if a known
 *   host's SHA-256 fingerprint changes without explicit user confirmation.
 * - Allocates an `xterm-256color` PTY (`100x32`) on `ChannelExec` so benchmark scripts (YABS,
 *   NodeQuality, NextTrace, RegionRestrictionCheck) emit full ANSI colors.
 * - Streams decoded UTF-8 chunks cleanly across multi-byte CJK boundaries.
 * - Immediately disconnects the JSch channel and session upon coroutine cancellation.
 */
class JSchSshTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SshTransport {

    override fun execute(
        request: SshConnectionRequest,
        command: String,
    ): Flow<SshStreamEvent> = callbackFlow {
        val sessionRef = AtomicReference<Session?>(null)
        val channelRef = AtomicReference<ChannelExec?>(null)
        val closedFlag = AtomicBoolean(false)

        fun disconnectQuietly() {
            if (!closedFlag.compareAndSet(false, true)) return
            runCatching { channelRef.getAndSet(null)?.disconnect() }
            runCatching { sessionRef.getAndSet(null)?.disconnect() }
        }

        val workerJob = launch(ioDispatcher) {
            val observedHostKeyRef = AtomicReference<HostKeyVerificationResult?>(null)
            val mismatchErrorRef = AtomicReference<SshHostKeyMismatchException?>(null)

            try {
                val jsch = JSch()
                jsch.hostKeyRepository = TofuHostKeyRepository(
                    request = request,
                    observedRef = observedHostKeyRef,
                    mismatchRef = mismatchErrorRef,
                )

                if (request.authMethod == SshAuthMethod.PRIVATE_KEY) {
                    val normalizedKey = normalizePrivateKey(request.secret)
                    require(normalizedKey.isNotEmpty()) { "私钥内容不能为空" }
                    val keyBytes = normalizedKey.toByteArray(StandardCharsets.UTF_8)
                    val passphraseBytes = request.passphrase
                        ?.takeIf { it.isNotEmpty() }
                        ?.toByteArray(StandardCharsets.UTF_8)
                    jsch.addIdentity(
                        "anode-${request.username}",
                        keyBytes,
                        null,
                        passphraseBytes,
                    )
                }

                val session = jsch.getSession(request.username, request.host, request.port)
                sessionRef.set(session)

                val config = Properties().apply {
                    put("StrictHostKeyChecking", "yes")
                    put(
                        "PreferredAuthentications",
                        when (request.authMethod) {
                            SshAuthMethod.PRIVATE_KEY -> "publickey"
                            SshAuthMethod.PASSWORD -> "password,keyboard-interactive"
                        },
                    )
                }
                session.setConfig(config)
                session.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
                session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX

                if (request.authMethod == SshAuthMethod.PASSWORD) {
                    session.setPassword(request.secret)
                    session.userInfo = PasswordAndInteractiveUserInfo(request.secret)
                }

                try {
                    session.connect(request.connectTimeoutMillis)
                } catch (t: Throwable) {
                    mismatchErrorRef.get()?.let { mismatch ->
                        throw mismatch
                    }
                    throw t
                }

                observedHostKeyRef.get()?.let { verification ->
                    trySend(SshStreamEvent.HostKeyObserved(verification))
                }
                trySend(SshStreamEvent.Connected)

                val channel = session.openChannel("exec") as ChannelExec
                channelRef.set(channel)
                channel.setCommand(command)
                channel.setInputStream(null)
                channel.setPty(true)
                channel.setPtyType(request.ptyType, request.ptyCols, request.ptyRows, 0, 0)

                val remoteStdin = channel.outputStream
                val reader = InputStreamReader(channel.inputStream, StandardCharsets.UTF_8)
                channel.connect(request.connectTimeoutMillis)
                coroutineContext[com.nodeseek.app.domain.ssh.SshStdinCallbackContext]?.onStdinReady?.invoke(
                    com.nodeseek.app.domain.ssh.SshStdinWriter { input ->
                        if (!closedFlag.get() && input.isNotEmpty()) {
                            runCatching {
                                synchronized(remoteStdin) {
                                    remoteStdin.write(input.toByteArray(StandardCharsets.UTF_8))
                                    remoteStdin.flush()
                                }
                            }
                        }
                    },
                )

                val buffer = CharArray(STREAM_READ_BUFFER_CHARS)
                while (!closedFlag.get()) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val chunk = String(buffer, 0, count)
                        send(SshStreamEvent.OutputChunk(chunk))
                    }
                }

                // Wait briefly for remote exit status if the stream closed right before status packet
                var waitAttempts = 0
                while (!channel.isClosed && !closedFlag.get() && waitAttempts < EXIT_STATUS_POLL_ATTEMPTS) {
                    Thread.sleep(EXIT_STATUS_POLL_MS)
                    waitAttempts++
                }

                if (!closedFlag.get()) {
                    val exitCode = channel.exitStatus
                    send(SshStreamEvent.Completed(exitCode = exitCode))
                }
                close()
            } catch (ce: CancellationException) {
                disconnectQuietly()
                throw ce
            } catch (t: Throwable) {
                if (closedFlag.get()) {
                    close()
                } else {
                    close(t)
                }
            } finally {
                disconnectQuietly()
            }
        }

        awaitClose {
            disconnectQuietly()
            workerJob.cancel()
        }
    }

    private fun normalizePrivateKey(rawKey: String): String {
        val cleaned = rawKey
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (cleaned.isEmpty()) return ""
        return if (cleaned.endsWith('\n')) cleaned else "$cleaned\n"
    }

    private class TofuHostKeyRepository(
        private val request: SshConnectionRequest,
        private val observedRef: AtomicReference<HostKeyVerificationResult?>,
        private val mismatchRef: AtomicReference<SshHostKeyMismatchException?>,
    ) : HostKeyRepository {

        override fun check(host: String, key: ByteArray): Int {
            val fingerprint = HostKeyTrustPolicy.computeSha256Fingerprint(key)
            val algorithm = runCatching { HostKey(host, key).type }.getOrDefault("unknown")
            val hostKeyInfo = HostKeyInfo(
                host = request.host,
                port = request.port,
                algorithm = algorithm,
                fingerprintSha256 = fingerprint,
            )
            val verification = HostKeyTrustPolicy.verify(
                expectedFingerprint = request.expectedHostKeyFingerprint,
                actual = hostKeyInfo,
            )
            observedRef.set(verification)

            return when (verification) {
                is HostKeyVerificationResult.Trusted,
                is HostKeyVerificationResult.FirstUseTrust,
                -> HostKeyRepository.OK

                is HostKeyVerificationResult.KeyChangedWarning -> {
                    if (request.allowHostKeyMismatchOverride) {
                        HostKeyRepository.OK
                    } else {
                        mismatchRef.set(
                            SshHostKeyMismatchException(
                                expectedFingerprint = verification.expectedFingerprint,
                                actualHostKey = verification.actualHostKey,
                            ),
                        )
                        HostKeyRepository.CHANGED
                    }
                }
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "anode-tofu-memory-repo"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private class PasswordAndInteractiveUserInfo(
        private val password: String,
    ) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            val count = prompt?.size ?: return emptyArray()
            return Array(count) { password }
        }
    }

    companion object {
        private const val STREAM_READ_BUFFER_CHARS: Int = 4096
        private const val SERVER_ALIVE_INTERVAL_MS: Int = 15_000
        private const val SERVER_ALIVE_COUNT_MAX: Int = 4
        private const val EXIT_STATUS_POLL_MS: Long = 50L
        private const val EXIT_STATUS_POLL_ATTEMPTS: Int = 40
    }
}
