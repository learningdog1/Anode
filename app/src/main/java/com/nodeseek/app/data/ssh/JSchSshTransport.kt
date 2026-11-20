package com.nodeseek.app.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshJumpConnectionStage
import com.nodeseek.app.domain.ssh.SshJumpHop
import com.nodeseek.app.domain.ssh.SshJumpHostConnectionException
import com.nodeseek.app.domain.ssh.SshJumpHostResolver
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.domain.ssh.SshVaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Parameters for establishing a single SSH session (either a jump host or the final target).
 *
 * [logicalHost] and [logicalPort] identify the true remote machine for TOFU host key verification,
 * while [connectHost] and [connectPort] specify the TCP socket endpoint (`127.0.0.1:<forwardedPort>`
 * when tunneling through a preceding jump session).
 */
internal data class SshHopEndpointSpec(
    val serverId: String? = null,
    val displayLabel: String,
    val logicalHost: String,
    val logicalPort: Int,
    val connectHost: String,
    val connectPort: Int,
    val username: String,
    val authMethod: SshAuthMethod,
    val secret: String,
    val passphrase: String? = null,
    val expectedHostKeyFingerprint: String? = null,
    val allowHostKeyMismatchOverride: Boolean = false,
)

internal interface ManagedExecChannel {
    val inputStream: InputStream
    val outputStream: OutputStream
        get() = ByteArrayOutputStream()
    val isClosed: Boolean
    val exitStatus: Int
    fun setCommand(command: String) = Unit
    fun setInputStream(inputStream: InputStream?) = Unit
    fun setPty(enable: Boolean) = Unit
    fun setPtyType(ptyType: String, col: Int, row: Int, wp: Int, hp: Int) = Unit
    fun connect(connectTimeoutMillis: Int)
    fun disconnect()
}

internal interface ManagedSshSession {
    val lastTunnelError: String?
    fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult?
    fun setPortForwardingL(
        bindAddress: String,
        localPort: Int,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMillis: Int,
    ): Int
    fun delPortForwardingL(bindAddress: String, localPort: Int)
    fun openExecChannel(): ManagedExecChannel
    fun disconnect()
}

internal fun interface SshSessionConnector {
    fun createSession(spec: SshHopEndpointSpec): ManagedSshSession
}

/**
 * Pure-Java [SshTransport] implementation powered by `com.github.mwiede:jsch`.
 *
 * Key design guarantees:
 * - Supports optional single-hop and multi-hop SSH jump hosts (`ProxyJump` via JSch local loopback
 *   port forwarding `setPortForwardingL("127.0.0.1", 0, ...)`), authenticating each hop
 *   independently with either password or PEM/OpenSSH private key (+ optional passphrase).
 * - Enforces Trust-On-First-Use (TOFU) host key verification inside [HostKeyRepository.check]
 *   on *every* hop against its logical `host:port` *before* sending credentials, aborting with
 *   [SshHostKeyMismatchException] if a known host's SHA-256 fingerprint changes.
 * - Distinguishes jump-host connection failures ("跳板机连接失败") from tunneled target connection
 *   failures ("目标经跳板连接失败") via [SshJumpHostConnectionException].
 * - Tears down resources strictly in reverse establishment order (`execChannel` -> `targetSession`
 *   -> `delPortForwardingL` -> `jumpSession`) upon completion, failure, or cancellation.
 */
class JSchSshTransport internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val vaultRepository: SshVaultRepository?,
    private val sessionConnector: SshSessionConnector,
) : SshTransport {

    constructor(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        vaultRepository: SshVaultRepository? = null,
    ) : this(
        ioDispatcher = ioDispatcher,
        vaultRepository = vaultRepository,
        sessionConnector = DefaultJschSessionConnector,
    )

    private class OpenedHopEntry(
        val session: ManagedSshSession,
        val forwardedLocalPort: AtomicInteger = AtomicInteger(-1),
    )

    override fun execute(
        request: SshConnectionRequest,
        command: String,
    ): Flow<SshStreamEvent> = callbackFlow {
        val closeLock = Any()
        val openedHops = ArrayList<OpenedHopEntry>(2)
        val channelRef = AtomicReference<ManagedExecChannel?>(null)
        val closedFlag = AtomicBoolean(false)

        fun registerHopSession(session: ManagedSshSession): OpenedHopEntry {
            val entry = OpenedHopEntry(session)
            val alreadyClosed = synchronized(closeLock) {
                if (closedFlag.get()) {
                    true
                } else {
                    openedHops.add(entry)
                    false
                }
            }
            if (alreadyClosed) {
                runCatching { session.disconnect() }
                throw CancellationException("SSH 会话已取消")
            }
            return entry
        }

        fun disconnectQuietly() {
            if (!closedFlag.compareAndSet(false, true)) return
            // 1. Close remote command channel on target first
            runCatching { channelRef.getAndSet(null)?.disconnect() }

            // 2. Close SSH sessions strictly in reverse order (target session first, then inner-to-outer jump hosts)
            val snapshot = synchronized(closeLock) {
                openedHops.toList().also { openedHops.clear() }
            }
            for (index in snapshot.indices.reversed()) {
                val entry = snapshot[index]
                val forwardedPort = entry.forwardedLocalPort.getAndSet(-1)
                if (forwardedPort > 0) {
                    runCatching { entry.session.delPortForwardingL(LOOPBACK_BIND_ADDRESS, forwardedPort) }
                }
                runCatching { entry.session.disconnect() }
            }
        }

        val workerJob = launch(ioDispatcher) {
            try {
                val jumpHops = resolveEffectiveJumpHops(request)
                val targetLabel = request.alias.trim().ifBlank {
                    formatEndpoint(request.host, request.port)
                }

                var nextConnectHost = request.host
                var nextConnectPort = request.port
                var upstreamHopEntry: OpenedHopEntry? = null

                if (jumpHops.isNotEmpty()) {
                    nextConnectHost = jumpHops.first().host
                    nextConnectPort = jumpHops.first().port

                    for (index in jumpHops.indices) {
                        val hop = jumpHops[index]
                        val downstreamHost = if (index + 1 < jumpHops.size) {
                            jumpHops[index + 1].host
                        } else {
                            request.host
                        }
                        val downstreamPort = if (index + 1 < jumpHops.size) {
                            jumpHops[index + 1].port
                        } else {
                            request.port
                        }

                        val hopSpec = SshHopEndpointSpec(
                            serverId = hop.serverId,
                            displayLabel = hop.displayLabel,
                            logicalHost = hop.host,
                            logicalPort = hop.port,
                            connectHost = nextConnectHost,
                            connectPort = nextConnectPort,
                            username = hop.username,
                            authMethod = hop.authMethod,
                            secret = hop.secret,
                            passphrase = hop.passphrase,
                            expectedHostKeyFingerprint = hop.expectedHostKeyFingerprint,
                            allowHostKeyMismatchOverride = hop.allowHostKeyMismatchOverride,
                        )

                        val hopEntry: OpenedHopEntry
                        val hopVerification: HostKeyVerificationResult?
                        try {
                            val hopSession = sessionConnector.createSession(hopSpec)
                            hopEntry = registerHopSession(hopSession)
                            hopVerification = hopSession.connect(request.connectTimeoutMillis)
                            val forwardedPort = hopSession.setPortForwardingL(
                                bindAddress = LOOPBACK_BIND_ADDRESS,
                                localPort = 0,
                                targetHost = downstreamHost,
                                targetPort = downstreamPort,
                                connectTimeoutMillis = request.connectTimeoutMillis,
                            )
                            hopEntry.forwardedLocalPort.set(forwardedPort)
                            nextConnectHost = LOOPBACK_BIND_ADDRESS
                            nextConnectPort = forwardedPort
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (mismatch: SshHostKeyMismatchException) {
                            throw mismatch
                        } catch (jumpEx: SshJumpHostConnectionException) {
                            throw jumpEx
                        } catch (t: Throwable) {
                            val causeDetail = describeConnectionFailureCause(
                                throwable = t,
                                tunnelDiagnostic = upstreamHopEntry?.session?.lastTunnelError,
                                isViaJumpTunnel = index > 0,
                                targetEndpoint = formatEndpoint(hop.host, hop.port),
                            )
                            val hopEndpoint = formatEndpoint(hop.host, hop.port)
                            val prefix = if (index == 0) {
                                "跳板机连接失败（${hop.displayLabel} · $hopEndpoint）"
                            } else {
                                val prevLabel = jumpHops[index - 1].displayLabel
                                "跳板机连接失败（经 $prevLabel 连接跳板 ${hop.displayLabel} · $hopEndpoint）"
                            }
                            throw SshJumpHostConnectionException(
                                stage = SshJumpConnectionStage.JUMP_HOST,
                                hopServerId = hop.serverId,
                                hopDisplayName = hop.displayLabel,
                                hopEndpoint = hopEndpoint,
                                viaDisplayName = if (index > 0) jumpHops[index - 1].displayLabel else null,
                                message = "$prefix：$causeDetail",
                                cause = t,
                            )
                        }

                        if (hopVerification != null) {
                            trySend(
                                SshStreamEvent.HostKeyObserved(
                                    verification = hopVerification,
                                    serverId = hop.serverId,
                                ),
                            )
                        }
                        upstreamHopEntry = hopEntry
                    }
                }

                val targetSpec = SshHopEndpointSpec(
                    serverId = request.serverId,
                    displayLabel = targetLabel,
                    logicalHost = request.host,
                    logicalPort = request.port,
                    connectHost = nextConnectHost,
                    connectPort = nextConnectPort,
                    username = request.username,
                    authMethod = request.authMethod,
                    secret = request.secret,
                    passphrase = request.passphrase,
                    expectedHostKeyFingerprint = request.expectedHostKeyFingerprint,
                    allowHostKeyMismatchOverride = request.allowHostKeyMismatchOverride,
                )

                val targetEntry: OpenedHopEntry
                val targetVerification: HostKeyVerificationResult?
                try {
                    val targetSession = sessionConnector.createSession(targetSpec)
                    targetEntry = registerHopSession(targetSession)
                    targetVerification = targetSession.connect(request.connectTimeoutMillis)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (mismatch: SshHostKeyMismatchException) {
                    throw mismatch
                } catch (jumpEx: SshJumpHostConnectionException) {
                    throw jumpEx
                } catch (t: Throwable) {
                    if (jumpHops.isEmpty()) {
                        throw t
                    }
                    val jumpChainSummary = jumpHops.joinToString(" -> ") { it.displayLabel }
                    val targetEndpoint = formatEndpoint(request.host, request.port)
                    val causeDetail = describeConnectionFailureCause(
                        throwable = t,
                        tunnelDiagnostic = upstreamHopEntry?.session?.lastTunnelError,
                        isViaJumpTunnel = true,
                        targetEndpoint = targetEndpoint,
                    )
                    throw SshJumpHostConnectionException(
                        stage = SshJumpConnectionStage.TARGET_VIA_JUMP,
                        hopServerId = request.serverId,
                        hopDisplayName = targetLabel,
                        hopEndpoint = targetEndpoint,
                        viaDisplayName = jumpChainSummary,
                        message = "目标经跳板连接失败（经跳板「$jumpChainSummary」连接目标「$targetLabel」$targetEndpoint）：$causeDetail",
                        cause = t,
                    )
                }

                if (targetVerification != null) {
                    trySend(
                        SshStreamEvent.HostKeyObserved(
                            verification = targetVerification,
                            serverId = request.serverId,
                        ),
                    )
                }
                trySend(SshStreamEvent.Connected)

                if (closedFlag.get()) {
                    throw CancellationException("SSH 会话已取消")
                }

                val channel = targetEntry.session.openExecChannel()
                channelRef.set(channel)
                channel.setCommand(command)
                channel.setInputStream(null)
                channel.setPty(true)
                channel.setPtyType(request.ptyType, request.ptyCols, request.ptyRows, 0, 0)

                val reader = InputStreamReader(channel.inputStream, StandardCharsets.UTF_8)
                channel.connect(request.connectTimeoutMillis)

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

    private fun normalizePrivateKey(rawKey: String): String = Companion.normalizePrivateKey(rawKey)

    private suspend fun resolveEffectiveJumpHops(request: SshConnectionRequest): List<SshJumpHop> {
        if (request.jumpHosts.isNotEmpty()) {
            return request.jumpHosts
        }
        val rawJumpId = SshJumpHostResolver.normalizeJumpHostId(request.jumpHostId) ?: return emptyList()
        val vault = vaultRepository ?: throw SshJumpHostConnectionException(
            stage = SshJumpConnectionStage.JUMP_HOST,
            hopServerId = rawJumpId,
            hopDisplayName = rawJumpId,
            hopEndpoint = rawJumpId,
            message = "跳板机连接失败：未提供跳板机解密凭据",
        )
        val servers = vault.serversFlow.value
        val matchedTarget = request.serverId?.let { id -> servers.firstOrNull { it.id == id } }
        val targetProfile = (matchedTarget ?: SshServerProfile(
            id = request.serverId ?: "__transient_target__",
            alias = request.alias,
            host = request.host,
            port = request.port,
            username = request.username,
            authMethod = request.authMethod,
            jumpHostId = rawJumpId,
        )).copy(jumpHostId = rawJumpId)

        return SshJumpHostResolver.buildConnectionRequest(
            vaultRepository = vault,
            server = targetProfile,
            allowHostKeyMismatchOverride = request.allowHostKeyMismatchOverride,
        ).jumpHosts
    }

    private object DefaultJschSessionConnector : SshSessionConnector {
        override fun createSession(spec: SshHopEndpointSpec): ManagedSshSession {
            val observedHostKeyRef = AtomicReference<HostKeyVerificationResult?>(null)
            val mismatchErrorRef = AtomicReference<SshHostKeyMismatchException?>(null)
            val tunnelDiagnosticRef = AtomicReference<String?>(null)

            val jsch = JSch()
            jsch.instanceLogger = HopDiagnosticLogger(tunnelDiagnosticRef)
            jsch.hostKeyRepository = TofuHostKeyRepository(
                spec = spec,
                observedRef = observedHostKeyRef,
                mismatchRef = mismatchErrorRef,
            )

            if (spec.authMethod == SshAuthMethod.PRIVATE_KEY) {
                val normalizedKey = normalizePrivateKey(spec.secret)
                require(normalizedKey.isNotEmpty()) { "私钥内容不能为空" }
                val keyBytes = normalizedKey.toByteArray(StandardCharsets.UTF_8)
                val passphraseBytes = spec.passphrase
                    ?.takeIf { it.isNotEmpty() }
                    ?.toByteArray(StandardCharsets.UTF_8)
                jsch.addIdentity(
                    "anode-${spec.username}-${spec.logicalHost}",
                    keyBytes,
                    null,
                    passphraseBytes,
                )
            }

            val session = jsch.getSession(spec.username, spec.connectHost, spec.connectPort)
            val config = Properties().apply {
                put("StrictHostKeyChecking", "yes")
                put(
                    "PreferredAuthentications",
                    when (spec.authMethod) {
                        SshAuthMethod.PRIVATE_KEY -> "publickey"
                        SshAuthMethod.PASSWORD -> "password,keyboard-interactive"
                    },
                )
            }
            session.setConfig(config)
            session.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
            session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX

            if (spec.authMethod == SshAuthMethod.PASSWORD) {
                session.setPassword(spec.secret)
                session.userInfo = PasswordAndInteractiveUserInfo(spec.secret)
            }

            return JschManagedSshSession(
                session = session,
                observedHostKeyRef = observedHostKeyRef,
                mismatchErrorRef = mismatchErrorRef,
                tunnelDiagnosticRef = tunnelDiagnosticRef,
            )
        }
    }

    private class JschManagedSshSession(
        private val session: Session,
        private val observedHostKeyRef: AtomicReference<HostKeyVerificationResult?>,
        private val mismatchErrorRef: AtomicReference<SshHostKeyMismatchException?>,
        private val tunnelDiagnosticRef: AtomicReference<String?>,
    ) : ManagedSshSession {

        override val lastTunnelError: String?
            get() = tunnelDiagnosticRef.get()

        override fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult? {
            try {
                session.connect(connectTimeoutMillis)
            } catch (t: Throwable) {
                mismatchErrorRef.get()?.let { mismatch ->
                    throw mismatch
                }
                throw t
            }
            return observedHostKeyRef.get()
        }

        override fun setPortForwardingL(
            bindAddress: String,
            localPort: Int,
            targetHost: String,
            targetPort: Int,
            connectTimeoutMillis: Int,
        ): Int = session.setPortForwardingL(
            bindAddress,
            localPort,
            targetHost,
            targetPort,
            null,
            connectTimeoutMillis,
        )

        override fun delPortForwardingL(bindAddress: String, localPort: Int) {
            session.delPortForwardingL(bindAddress, localPort)
        }

        override fun openExecChannel(): ManagedExecChannel {
            val channel = session.openChannel("exec") as ChannelExec
            return JschManagedExecChannel(channel)
        }

        override fun disconnect() {
            session.disconnect()
        }
    }

    private class JschManagedExecChannel(
        private val channel: ChannelExec,
    ) : ManagedExecChannel {
        override val inputStream: InputStream
            get() = channel.inputStream
        override val outputStream: OutputStream
            get() = channel.outputStream
        override val isClosed: Boolean
            get() = channel.isClosed
        override val exitStatus: Int
            get() = channel.exitStatus

        override fun setCommand(command: String) {
            channel.setCommand(command)
        }

        override fun setInputStream(inputStream: InputStream?) {
            channel.setInputStream(inputStream)
        }

        override fun setPty(enable: Boolean) {
            channel.setPty(enable)
        }

        override fun setPtyType(ptyType: String, col: Int, row: Int, wp: Int, hp: Int) {
            channel.setPtyType(ptyType, col, row, wp, hp)
        }

        override fun connect(connectTimeoutMillis: Int) {
            channel.connect(connectTimeoutMillis)
        }

        override fun disconnect() {
            channel.disconnect()
        }
    }

    private class HopDiagnosticLogger(
        private val diagnosticRef: AtomicReference<String?>,
    ) : Logger {
        override fun isEnabled(level: Int): Boolean = level >= Logger.WARN

        override fun log(level: Int, message: String?) {
            val clean = message?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (level >= Logger.WARN && (
                    clean.contains("DirectTCPIP", ignoreCase = true) ||
                        clean.contains("PortForwarding", ignoreCase = true) ||
                        clean.contains("connect failed", ignoreCase = true) ||
                        clean.contains("prohibited", ignoreCase = true) ||
                        clean.contains("refused", ignoreCase = true)
                    )
            ) {
                diagnosticRef.set(clean)
            }
        }
    }

    private class TofuHostKeyRepository(
        private val spec: SshHopEndpointSpec,
        private val observedRef: AtomicReference<HostKeyVerificationResult?>,
        private val mismatchRef: AtomicReference<SshHostKeyMismatchException?>,
    ) : HostKeyRepository {

        override fun check(host: String, key: ByteArray): Int {
            val fingerprint = HostKeyTrustPolicy.computeSha256Fingerprint(key)
            val algorithm = runCatching { HostKey(spec.logicalHost, key).type }.getOrDefault("unknown")
            val hostKeyInfo = HostKeyInfo(
                host = spec.logicalHost,
                port = spec.logicalPort,
                algorithm = algorithm,
                fingerprintSha256 = fingerprint,
            )
            val verification = HostKeyTrustPolicy.verify(
                expectedFingerprint = spec.expectedHostKeyFingerprint,
                actual = hostKeyInfo,
            )
            observedRef.set(verification)

            return when (verification) {
                is HostKeyVerificationResult.Trusted,
                is HostKeyVerificationResult.FirstUseTrust,
                -> HostKeyRepository.OK

                is HostKeyVerificationResult.KeyChangedWarning -> {
                    if (spec.allowHostKeyMismatchOverride) {
                        HostKeyRepository.OK
                    } else {
                        mismatchRef.set(
                            SshHostKeyMismatchException(
                                expectedFingerprint = verification.expectedFingerprint,
                                actualHostKey = verification.actualHostKey,
                                serverId = spec.serverId,
                                serverAlias = spec.displayLabel,
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
        internal const val LOOPBACK_BIND_ADDRESS: String = "127.0.0.1"
        private const val STREAM_READ_BUFFER_CHARS: Int = 4096
        private const val SERVER_ALIVE_INTERVAL_MS: Int = 15_000
        private const val SERVER_ALIVE_COUNT_MAX: Int = 4
        private const val EXIT_STATUS_POLL_MS: Long = 50L
        private const val EXIT_STATUS_POLL_ATTEMPTS: Int = 40

        internal fun normalizePrivateKey(rawKey: String): String {
            val cleaned = rawKey
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            if (cleaned.isEmpty()) return ""
            return if (cleaned.endsWith('\n')) cleaned else "$cleaned\n"
        }

        private fun formatEndpoint(host: String, port: Int): String =
            if (port == SshServerProfile.DEFAULT_PORT) host.trim() else "${host.trim()}:$port"

        internal fun describeConnectionFailureCause(
            throwable: Throwable,
            tunnelDiagnostic: String? = null,
            isViaJumpTunnel: Boolean = false,
            targetEndpoint: String = "",
        ): String {
            val raw = throwable.message?.trim().orEmpty()
            val diag = tunnelDiagnostic?.trim().orEmpty()
            return when {
                raw.contains("Auth fail", ignoreCase = true) ||
                    raw.contains("Auth cancel", ignoreCase = true) ->
                    "SSH 认证失败，请检查用户名、密码或私钥（及私钥口令）"

                raw.contains("私钥内容不能为空") ->
                    "私钥内容不能为空"

                raw.contains("invalid privatekey", ignoreCase = true) ||
                    raw.contains("decrypt", ignoreCase = true) ->
                    "私钥格式无效或私钥口令错误"

                diag.contains("administratively prohibited", ignoreCase = true) ->
                    "跳板机拒绝 TCP 端口转发（请确认跳板机 sshd_config 已启用 AllowTcpForwarding yes）"

                raw.contains("Connection refused", ignoreCase = true) ||
                    diag.contains("Connection refused", ignoreCase = true) ->
                    "连接被拒绝，请确认 SSH 服务端口已开放"

                raw.contains("timeout", ignoreCase = true) ||
                    raw.contains("timed out", ignoreCase = true) ||
                    diag.contains("timeout", ignoreCase = true) ||
                    diag.contains("timed out", ignoreCase = true) ->
                    "连接超时，请检查主机地址、端口或防火墙策略"

                raw.contains("UnknownHostException", ignoreCase = true) ||
                    raw.contains("Unable to resolve host", ignoreCase = true) ->
                    "无法解析主机域名，请检查地址拼写或 DNS"

                isViaJumpTunnel && (
                    raw.contains("End of IO Stream Read", ignoreCase = true) ||
                        raw.contains("connection is closed by foreign host", ignoreCase = true) ||
                        diag.isNotEmpty()
                    ) -> {
                    if (diag.isNotEmpty()) {
                        "经跳板建立端口转发通道失败（$diag）"
                    } else {
                        val endpointHint = if (targetEndpoint.isNotEmpty()) " $targetEndpoint" else ""
                        "经跳板建立转发通道中断，请确认跳板机可访问目标主机$endpointHint 且允许 TCP 转发"
                    }
                }

                raw.isNotEmpty() -> raw
                else -> throwable.javaClass.simpleName
            }
        }
    }
}
