package com.nodeseek.app.ssh

import com.nodeseek.app.data.ssh.JSchSshTransport
import com.nodeseek.app.data.ssh.ManagedExecChannel
import com.nodeseek.app.data.ssh.ManagedSshSession
import com.nodeseek.app.data.ssh.SshHopEndpointSpec
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.data.ssh.SshSessionConnector
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshJumpConnectionStage
import com.nodeseek.app.domain.ssh.SshJumpHostConnectionException
import com.nodeseek.app.domain.ssh.SshJumpHostCycleException
import com.nodeseek.app.domain.ssh.SshJumpHostResolver
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import com.nodeseek.app.ui.ssh.SshConnectionPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class SshJumpHostTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVault(
        vaultFile: File = File(tempFolder.root, "vault_${System.nanoTime()}.json"),
        cipher: InMemoryTestCipher = InMemoryTestCipher(),
    ): SshServerVault = SshServerVault(
        vaultFile = vaultFile,
        cipher = cipher,
        ioDispatcher = testDispatcher,
        clock = { 1_750_000_000_000L },
    )

    @Test
    fun `legacy vault json without jumpHostId deserializes as direct connection and round-trips with jumpHostId`() = runTest {
        val vaultFile = File(tempFolder.root, SshServerVault.VAULT_FILE_NAME)
        val cipher = InMemoryTestCipher()
        val encryptedPw = cipher.encrypt("legacy-root-pw")

        // Simulate a v1 vault file written before jumpHostId existed (plus blank jumpHostId edge case)
        val legacyJson = """
            {
              "schemaVersion": 1,
              "servers": [
                {
                  "id": "legacy-hk",
                  "alias": "HK CMI",
                  "host": "198.51.100.10",
                  "port": 22,
                  "username": "root",
                  "authMethod": "PASSWORD",
                  "encryptedSecret": "$encryptedPw",
                  "createdAtEpochMillis": 1700000000000
                },
                {
                  "id": "legacy-blank-jump",
                  "alias": "SG Direct",
                  "host": "198.51.100.11",
                  "port": 2222,
                  "username": "debian",
                  "authMethod": "PASSWORD",
                  "encryptedSecret": "$encryptedPw",
                  "jumpHostId": "   "
                }
              ],
              "customPresets": [],
              "recentRuns": []
            }
        """.trimIndent()
        vaultFile.writeText(legacyJson, StandardCharsets.UTF_8)

        val vault = createVault(vaultFile = vaultFile, cipher = cipher)
        assertEquals(2, vault.serversFlow.value.size)

        val hk = vault.serversFlow.value.first { it.id == "legacy-hk" }
        assertNull(hk.jumpHostId)
        assertFalse(hk.usesJumpHost)
        assertEquals("legacy-root-pw", vault.decryptCredentials(hk).secret)

        val sg = vault.serversFlow.value.first { it.id == "legacy-blank-jump" }
        assertNull(sg.jumpHostId)
        assertFalse(sg.usesJumpHost)

        // Add a new US server using HK as jump host and verify disk persistence round-trip
        val us = vault.saveServer(
            SshServerDraft(
                alias = "US San Jose",
                host = "203.0.113.50",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "us-pw",
                jumpHostId = hk.id,
            ),
        )
        assertEquals(hk.id, us.jumpHostId)
        assertTrue(us.usesJumpHost)

        val reloaded = createVault(vaultFile = vaultFile, cipher = cipher)
        val reloadedUs = reloaded.serversFlow.value.first { it.id == us.id }
        assertEquals(hk.id, reloadedUs.jumpHostId)
        assertTrue(reloadedUs.usesJumpHost)

        // Deleting jump host resets dependent server's jumpHostId back to null (direct)
        reloaded.deleteServer(hk.id)
        val afterDeleteUs = reloaded.serversFlow.value.first { it.id == us.id }
        assertNull(afterDeleteUs.jumpHostId)
        assertFalse(afterDeleteUs.usesJumpHost)
    }

    @Test
    fun `saveServer and resolver prohibit self-reference and detect direct and multi-hop cycles`() = runTest {
        val vault = createVault()

        val hostA = vault.saveServer(
            SshServerDraft(
                id = "srv-a",
                alias = "Host-A",
                host = "198.51.100.1",
                secret = "pw-a",
            ),
        )
        val hostB = vault.saveServer(
            SshServerDraft(
                id = "srv-b",
                alias = "Host-B",
                host = "198.51.100.2",
                secret = "pw-b",
                jumpHostId = hostA.id, // B jumps via A
            ),
        )
        val hostC = vault.saveServer(
            SshServerDraft(
                id = "srv-c",
                alias = "Host-C",
                host = "198.51.100.3",
                secret = "pw-c",
                jumpHostId = hostB.id, // C jumps via B -> A (2-hop chain)
            ),
        )

        // Valid 2-hop chain resolves outermost-first: [Host-A, Host-B]
        val resolvedChain = SshJumpHostResolver.resolveJumpChain(hostC, vault.serversFlow.value)
        assertEquals(listOf("srv-a", "srv-b"), resolvedChain.map { it.id })

        // 1. Self-reference prohibited (A -> A)
        try {
            vault.saveServer(
                SshServerDraft(
                    id = hostA.id,
                    alias = hostA.alias,
                    host = hostA.host,
                    secret = "",
                    keepExistingSecret = true,
                    jumpHostId = hostA.id,
                ),
            )
            fail("Expected SshJumpHostCycleException for self-reference")
        } catch (e: SshJumpHostCycleException) {
            assertTrue(e.message!!.contains("自身"))
        }

        // 2. Direct 2-node cycle prohibited (B -> A already exists, now setting A -> B)
        assertTrue(SshJumpHostResolver.wouldCreateCycle(hostA.id, hostB.id, vault.serversFlow.value))
        try {
            vault.saveServer(
                SshServerDraft(
                    id = hostA.id,
                    alias = hostA.alias,
                    host = hostA.host,
                    secret = "",
                    keepExistingSecret = true,
                    jumpHostId = hostB.id,
                ),
            )
            fail("Expected SshJumpHostCycleException for 2-node cycle A -> B -> A")
        } catch (e: SshJumpHostCycleException) {
            assertTrue(e.message!!.contains("循环引用"))
            assertEquals(listOf("srv-a", "srv-b", "srv-a"), e.cycleServerIds)
        }

        // 3. Transitive 3-node cycle prohibited (C -> B -> A already exists, now setting A -> C)
        assertTrue(SshJumpHostResolver.wouldCreateCycle(hostA.id, hostC.id, vault.serversFlow.value))
        try {
            vault.saveServer(
                SshServerDraft(
                    id = hostA.id,
                    alias = hostA.alias,
                    host = hostA.host,
                    secret = "",
                    keepExistingSecret = true,
                    jumpHostId = hostC.id,
                ),
            )
            fail("Expected SshJumpHostCycleException for 3-node cycle A -> C -> B -> A")
        } catch (e: SshJumpHostCycleException) {
            assertTrue(e.message!!.contains("Host-A -> Host-C -> Host-B -> Host-A"))
        }

        // 4. Non-existent jumpHostId rejected on save
        try {
            vault.saveServer(
                SshServerDraft(
                    alias = "Host-D",
                    host = "198.51.100.4",
                    secret = "pw-d",
                    jumpHostId = "missing-id",
                ),
            )
            fail("Expected IllegalArgumentException for missing jumpHostId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不存在"))
        }
    }

    @Test
    fun `JSchSshTransport connects jump host first, forwards port with dual auth credentials, and closes sessions in reverse order`() = runTest {
        val vault = createVault()
        val jumpPrivateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nJUMP_KEY_DATA\n-----END OPENSSH PRIVATE KEY-----"
        val jumpHost = vault.saveServer(
            SshServerDraft(
                id = "jump-hk",
                alias = "香港跳板",
                host = "198.51.100.88",
                port = 22022,
                username = "jumper",
                authMethod = SshAuthMethod.PRIVATE_KEY,
                secret = jumpPrivateKey,
                passphrase = "jump-key-passphrase",
            ),
        )
        val targetHost = vault.saveServer(
            SshServerDraft(
                id = "target-us",
                alias = "美西目标机",
                host = "203.0.113.99",
                port = 2222,
                username = "ubuntu",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "target-password-123",
                jumpHostId = jumpHost.id,
            ),
        )

        val lifecycleLog = Collections.synchronizedList(mutableListOf<String>())
        val capturedSpecs = Collections.synchronizedList(mutableListOf<SshHopEndpointSpec>())
        val jumpFp = HostKeyTrustPolicy.computeSha256Fingerprint("jump-host-key".toByteArray())
        val targetFp = HostKeyTrustPolicy.computeSha256Fingerprint("target-host-key".toByteArray())

        val fakeConnector = SshSessionConnector { spec ->
            capturedSpecs.add(spec)
            object : ManagedSshSession {
                override val lastTunnelError: String? = null

                override fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult {
                    lifecycleLog.add("connect:${spec.serverId}@${spec.connectHost}:${spec.connectPort}(timeout=$connectTimeoutMillis)")
                    val fp = if (spec.serverId == jumpHost.id) jumpFp else targetFp
                    return HostKeyVerificationResult.FirstUseTrust(
                        HostKeyInfo(spec.logicalHost, spec.logicalPort, "ssh-ed25519", fp),
                    )
                }

                override fun setPortForwardingL(
                    bindAddress: String,
                    localPort: Int,
                    targetHost: String,
                    targetPort: Int,
                    connectTimeoutMillis: Int,
                ): Int {
                    lifecycleLog.add("forward:${spec.serverId}:$bindAddress:$localPort->$targetHost:$targetPort(timeout=$connectTimeoutMillis)")
                    return 45123
                }

                override fun delPortForwardingL(bindAddress: String, localPort: Int) {
                    lifecycleLog.add("delForward:${spec.serverId}:$bindAddress:$localPort")
                }

                override fun openExecChannel(): ManagedExecChannel {
                    return object : ManagedExecChannel {
                        override val inputStream: InputStream =
                            ByteArrayInputStream("benchmark-ok\n".toByteArray(StandardCharsets.UTF_8))
                        override val isClosed: Boolean = true
                        override val exitStatus: Int = 0

                        override fun setCommand(command: String) {
                            lifecycleLog.add("openExec:${spec.serverId}:$command")
                        }

                        override fun connect(connectTimeoutMillis: Int) {
                            lifecycleLog.add("connectExec:${spec.serverId}(timeout=$connectTimeoutMillis)")
                        }

                        override fun disconnect() {
                            lifecycleLog.add("disconnectExec:${spec.serverId}")
                        }
                    }
                }

                override fun disconnect() {
                    lifecycleLog.add("disconnectSession:${spec.serverId}")
                }
            }
        }

        val transport = JSchSshTransport(
            ioDispatcher = testDispatcher,
            vaultRepository = vault,
            sessionConnector = fakeConnector,
        )
        val request = SshJumpHostResolver.buildConnectionRequest(vault, targetHost)
            .copy(connectTimeoutMillis = 9_000)

        val events = transport.execute(request, "uname -a").toList()

        // Verify decrypted credentials and endpoints on both hops
        assertEquals(2, capturedSpecs.size)
        val jumpSpec = capturedSpecs[0]
        assertEquals("jump-hk", jumpSpec.serverId)
        assertEquals("198.51.100.88", jumpSpec.connectHost)
        assertEquals(22022, jumpSpec.connectPort)
        assertEquals(SshAuthMethod.PRIVATE_KEY, jumpSpec.authMethod)
        assertEquals(jumpPrivateKey, jumpSpec.secret)
        assertEquals("jump-key-passphrase", jumpSpec.passphrase)

        val targetSpec = capturedSpecs[1]
        assertEquals("target-us", targetSpec.serverId)
        assertEquals("203.0.113.99", targetSpec.logicalHost)
        assertEquals(2222, targetSpec.logicalPort)
        assertEquals("127.0.0.1", targetSpec.connectHost)
        assertEquals(45123, targetSpec.connectPort)
        assertEquals(SshAuthMethod.PASSWORD, targetSpec.authMethod)
        assertEquals("target-password-123", targetSpec.secret)

        // Verify exact connect, port-forward, exec, and reverse disconnect order
        assertEquals(
            listOf(
                "connect:jump-hk@198.51.100.88:22022(timeout=9000)",
                "forward:jump-hk:127.0.0.1:0->203.0.113.99:2222(timeout=9000)",
                "connect:target-us@127.0.0.1:45123(timeout=9000)",
                "openExec:target-us:uname -a",
                "connectExec:target-us(timeout=9000)",
                "disconnectExec:target-us",
                "disconnectSession:target-us",
                "delForward:jump-hk:127.0.0.1:45123",
                "disconnectSession:jump-hk",
            ),
            lifecycleLog,
        )

        val output = events.filterIsInstance<SshStreamEvent.OutputChunk>().joinToString("") { it.rawText }
        assertEquals("benchmark-ok\n", output)
        val hostKeyEvents = events.filterIsInstance<SshStreamEvent.HostKeyObserved>()
        assertEquals(listOf("jump-hk", "target-us"), hostKeyEvents.map { it.serverId })
    }

    @Test
    fun `error messages clearly distinguish jump host failure from target-via-jump failure and still close opened sessions`() = runTest {
        val vault = createVault()
        val jump = vault.saveServer(
            SshServerDraft(
                id = "jump-1",
                alias = "日本软银跳板",
                host = "198.51.100.20",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "jump-pw",
            ),
        )
        val target = vault.saveServer(
            SshServerDraft(
                id = "target-1",
                alias = "欧洲内网机",
                host = "203.0.113.77",
                authMethod = SshAuthMethod.PRIVATE_KEY,
                secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nKEY\n-----END OPENSSH PRIVATE KEY-----",
                jumpHostId = jump.id,
            ),
        )

        // Case 1: Jump host authentication fails -> "跳板机连接失败", target never touched
        val case1Events = Collections.synchronizedList(mutableListOf<String>())
        val failOnJumpTransport = JSchSshTransport(
            ioDispatcher = testDispatcher,
            vaultRepository = vault,
            sessionConnector = { spec ->
                object : ManagedSshSession {
                    override val lastTunnelError: String? = null
                    override fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult? {
                        case1Events.add("connect:${spec.serverId}")
                        throw RuntimeException("Auth fail")
                    }
                    override fun setPortForwardingL(bindAddress: String, localPort: Int, targetHost: String, targetPort: Int, connectTimeoutMillis: Int): Int = 40001
                    override fun delPortForwardingL(bindAddress: String, localPort: Int) = Unit
                    override fun openExecChannel(): ManagedExecChannel = error("unreachable")
                    override fun disconnect() {
                        case1Events.add("disconnect:${spec.serverId}")
                    }
                }
            },
        )

        val viewModel1 = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(failOnJumpTransport),
            enableElapsedTicker = false,
        )
        viewModel1.selectServer(target.id)
        viewModel1.runSelectedPreset()

        assertEquals(SshConnectionPhase.FAILED, viewModel1.uiState.value.connectionPhase)
        val jumpErrMsg = viewModel1.uiState.value.errorMessage.orEmpty()
        assertTrue("Expected jump failure prefix, got: $jumpErrMsg", jumpErrMsg.startsWith("跳板机连接失败"))
        assertTrue(jumpErrMsg.contains("日本软银跳板"))
        assertFalse(jumpErrMsg.startsWith("目标经跳板连接失败"))
        assertEquals(listOf("connect:jump-1", "disconnect:jump-1"), case1Events)

        // Case 2: Jump host succeeds, target connection via tunnel fails -> "目标经跳板连接失败", reverse close order
        val case2Events = Collections.synchronizedList(mutableListOf<String>())
        val failOnTargetTransport = JSchSshTransport(
            ioDispatcher = testDispatcher,
            vaultRepository = vault,
            sessionConnector = { spec ->
                object : ManagedSshSession {
                    override val lastTunnelError: String? = if (spec.serverId == jump.id) {
                        "PortWatcher: connect failed: Connection refused"
                    } else {
                        null
                    }

                    override fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult? {
                        case2Events.add("connect:${spec.serverId}")
                        if (spec.serverId == target.id) {
                            throw RuntimeException("Session.connect: java.io.IOException: End of IO Stream Read")
                        }
                        return null
                    }

                    override fun setPortForwardingL(
                        bindAddress: String,
                        localPort: Int,
                        targetHost: String,
                        targetPort: Int,
                        connectTimeoutMillis: Int,
                    ): Int {
                        case2Events.add("forward:${spec.serverId}")
                        return 49001
                    }

                    override fun delPortForwardingL(bindAddress: String, localPort: Int) {
                        case2Events.add("delForward:${spec.serverId}:$localPort")
                    }

                    override fun openExecChannel(): ManagedExecChannel = error("unreachable")

                    override fun disconnect() {
                        case2Events.add("disconnect:${spec.serverId}")
                    }
                }
            },
        )

        val viewModel2 = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(failOnTargetTransport),
            enableElapsedTicker = false,
        )
        viewModel2.selectServer(target.id)
        viewModel2.runSelectedPreset()

        assertEquals(SshConnectionPhase.FAILED, viewModel2.uiState.value.connectionPhase)
        val targetViaJumpMsg = viewModel2.uiState.value.errorMessage.orEmpty()
        assertTrue("Expected target-via-jump failure prefix, got: $targetViaJumpMsg", targetViaJumpMsg.startsWith("目标经跳板连接失败"))
        assertTrue(targetViaJumpMsg.contains("日本软银跳板"))
        assertTrue(targetViaJumpMsg.contains("欧洲内网机"))
        assertEquals(
            listOf(
                "connect:jump-1",
                "forward:jump-1",
                "connect:target-1",
                "disconnect:target-1",
                "delForward:jump-1:49001",
                "disconnect:jump-1",
            ),
            case2Events,
        )
    }

    @Test
    fun `real JSchSshTransport against closed local port wraps jump failure as SshJumpHostConnectionException`() = runTest {
        val vault = createVault()
        // Obtain an unused local loopback port by opening and immediately closing a ServerSocket
        val closedPort = ServerSocket(0).use { it.localPort }

        val jump = vault.saveServer(
            SshServerDraft(
                id = "local-dead-jump",
                alias = "DeadJump",
                host = "127.0.0.1",
                port = closedPort,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )
        val target = vault.saveServer(
            SshServerDraft(
                id = "target-behind-dead-jump",
                alias = "TargetNode",
                host = "203.0.113.10",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
                jumpHostId = jump.id,
            ),
        )

        val realTransport = JSchSshTransport(ioDispatcher = testDispatcher, vaultRepository = vault)
        val request = SshJumpHostResolver.buildConnectionRequest(vault, target)
            .copy(connectTimeoutMillis = 1_500)

        try {
            realTransport.execute(request, "echo hi").toList()
            fail("Expected SshJumpHostConnectionException when jump port is closed")
        } catch (e: SshJumpHostConnectionException) {
            assertEquals(SshJumpConnectionStage.JUMP_HOST, e.stage)
            assertTrue(e.message!!.startsWith("跳板机连接失败"))
            assertTrue(e.message!!.contains("DeadJump"))
        }
    }

    @Test
    fun `LiteSshViewModel records separate TOFU fingerprints for jump host and target host and handles jump key mismatch`() = runTest {
        val vault = createVault()
        val oldJumpFp = HostKeyTrustPolicy.computeSha256Fingerprint("jump-old-key".toByteArray())
        val newJumpFp = HostKeyTrustPolicy.computeSha256Fingerprint("jump-new-key".toByteArray())
        val targetFp = HostKeyTrustPolicy.computeSha256Fingerprint("target-first-key".toByteArray())

        val jump = vault.saveServer(
            SshServerDraft(
                id = "jump-sg",
                alias = "SG Jump",
                host = "198.51.100.30",
                secret = "jump-pw",
            ),
        )
        vault.updateHostKeyFingerprint(jump.id, oldJumpFp, "ssh-ed25519")

        val target = vault.saveServer(
            SshServerDraft(
                id = "target-de",
                alias = "DE Target",
                host = "203.0.113.60",
                secret = "target-pw",
                jumpHostId = jump.id,
            ),
        )

        val connector = SshSessionConnector { spec ->
            object : ManagedSshSession {
                override val lastTunnelError: String? = null
                override fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult {
                    val presentedFp = if (spec.serverId == jump.id) newJumpFp else targetFp
                    val info = HostKeyInfo(spec.logicalHost, spec.logicalPort, "ssh-ed25519", presentedFp)
                    val verification = HostKeyTrustPolicy.verify(spec.expectedHostKeyFingerprint, info)
                    if (verification is HostKeyVerificationResult.KeyChangedWarning && !spec.allowHostKeyMismatchOverride) {
                        throw SshHostKeyMismatchException(
                            expectedFingerprint = verification.expectedFingerprint,
                            actualHostKey = verification.actualHostKey,
                            serverId = spec.serverId,
                            serverAlias = spec.displayLabel,
                        )
                    }
                    return verification
                }
                override fun setPortForwardingL(bindAddress: String, localPort: Int, targetHost: String, targetPort: Int, connectTimeoutMillis: Int): Int = 46000
                override fun delPortForwardingL(bindAddress: String, localPort: Int) = Unit
                override fun openExecChannel(): ManagedExecChannel {
                    return object : ManagedExecChannel {
                        override val inputStream: InputStream = ByteArrayInputStream("OK\n".toByteArray())
                        override val isClosed: Boolean = true
                        override val exitStatus: Int = 0
                        override fun connect(connectTimeoutMillis: Int) = Unit
                        override fun disconnect() = Unit
                    }
                }
                override fun disconnect() = Unit
            }
        }

        val viewModel = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(JSchSshTransport(testDispatcher, vault, connector)),
            enableElapsedTicker = false,
        )
        viewModel.selectServer(target.id)
        viewModel.runSelectedPreset()

        // First attempt triggers TOFU mismatch specifically pointing to SG Jump (NOT DE Target)
        val prompt = viewModel.uiState.value.pendingHostKeyMismatch
        assertNotNull(prompt)
        assertEquals(target.id, prompt!!.serverId)
        assertEquals(jump.id, prompt.mismatchServerId)
        assertEquals("SG Jump", prompt.serverAlias)

        // Confirming trust updates SG Jump's fingerprint and connects through to DE Target, recording DE Target's own fingerprint
        viewModel.confirmTrustChangedHostKey()

        assertEquals(SshConnectionPhase.COMPLETED, viewModel.uiState.value.connectionPhase)
        val updatedJump = vault.serversFlow.value.first { it.id == jump.id }
        val updatedTarget = vault.serversFlow.value.first { it.id == target.id }
        assertEquals(newJumpFp, updatedJump.knownHostKeyFingerprint)
        assertEquals(targetFp, updatedTarget.knownHostKeyFingerprint)
    }

    @Test
    fun `multi-hop 2-level jump chain tunnels sequentially and closes all three sessions in strict reverse order`() = runTest {
        val vault = createVault()
        val hop1 = vault.saveServer(
            SshServerDraft(
                id = "hop-1-hk",
                alias = "HK Entry",
                host = "198.51.100.1",
                port = 22,
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw-1",
            ),
        )
        val hop2 = vault.saveServer(
            SshServerDraft(
                id = "hop-2-jp",
                alias = "JP Relay",
                host = "198.51.100.2",
                port = 2202,
                authMethod = SshAuthMethod.PRIVATE_KEY,
                secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nJP_KEY\n-----END OPENSSH PRIVATE KEY-----",
                jumpHostId = hop1.id,
            ),
        )
        val target = vault.saveServer(
            SshServerDraft(
                id = "target-int",
                alias = "Internal Node",
                host = "10.0.10.5",
                port = 22,
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw-target",
                jumpHostId = hop2.id,
            ),
        )

        val log = Collections.synchronizedList(mutableListOf<String>())
        var nextLocalPort = 51000
        val connector = SshSessionConnector { spec ->
            object : ManagedSshSession {
                override val lastTunnelError: String? = null
                override fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult? {
                    log.add("connect:${spec.serverId}@${spec.connectHost}:${spec.connectPort}")
                    return null
                }
                override fun setPortForwardingL(
                    bindAddress: String,
                    localPort: Int,
                    targetHost: String,
                    targetPort: Int,
                    connectTimeoutMillis: Int,
                ): Int {
                    val assigned = ++nextLocalPort
                    log.add("forward:${spec.serverId}->$targetHost:$targetPort@$assigned")
                    return assigned
                }
                override fun delPortForwardingL(bindAddress: String, localPort: Int) {
                    log.add("delForward:${spec.serverId}@$localPort")
                }
                override fun openExecChannel(): ManagedExecChannel = object : ManagedExecChannel {
                    override val inputStream: InputStream = ByteArrayInputStream("hop-chain-ok\n".toByteArray())
                    override val isClosed: Boolean = true
                    override val exitStatus: Int = 0
                    override fun connect(connectTimeoutMillis: Int) {
                        log.add("execConnect:${spec.serverId}")
                    }
                    override fun disconnect() {
                        log.add("execDisconnect:${spec.serverId}")
                    }
                }
                override fun disconnect() {
                    log.add("disconnect:${spec.serverId}")
                }
            }
        }

        val transport = JSchSshTransport(testDispatcher, vault, connector)
        val request = SshJumpHostResolver.buildConnectionRequest(vault, target)
        val events = transport.execute(request, "hostname").toList()

        assertEquals(
            listOf(
                "connect:hop-1-hk@198.51.100.1:22",
                "forward:hop-1-hk->198.51.100.2:2202@51001",
                "connect:hop-2-jp@127.0.0.1:51001",
                "forward:hop-2-jp->10.0.10.5:22@51002",
                "connect:target-int@127.0.0.1:51002",
                "execConnect:target-int",
                "execDisconnect:target-int",
                "disconnect:target-int",
                "delForward:hop-2-jp@51002",
                "disconnect:hop-2-jp",
                "delForward:hop-1-hk@51001",
                "disconnect:hop-1-hk",
            ),
            log,
        )
        assertTrue(events.any { it is SshStreamEvent.Completed && it.exitCode == 0 })
    }

    @Test
    fun `lazy jumpHostId resolution does not propagate target allowHostKeyMismatchOverride to jump hops`() = runTest {
        val vault = createVault()
        val bastion = vault.saveServer(
            SshServerDraft(
                id = "hop-bastion",
                alias = "Bastion",
                host = "198.51.100.1",
                port = 22,
                username = "root",
                secret = "pw-hop",
            ),
        )
        vault.updateHostKeyFingerprint(bastion.id, "SHA256:bastionExpectedFp", "ssh-ed25519")

        val observedSpecs = Collections.synchronizedList(mutableListOf<SshHopEndpointSpec>())
        val connector = SshSessionConnector { spec ->
            observedSpecs += spec
            object : ManagedSshSession {
                override val lastTunnelError: String? = null
                override fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult? = null
                override fun setPortForwardingL(
                    bindAddress: String,
                    localPort: Int,
                    targetHost: String,
                    targetPort: Int,
                    connectTimeoutMillis: Int,
                ): Int = 52001
                override fun delPortForwardingL(bindAddress: String, localPort: Int) = Unit
                override fun openExecChannel(): ManagedExecChannel = object : ManagedExecChannel {
                    override val inputStream: InputStream = ByteArrayInputStream("ok\n".toByteArray())
                    override val isClosed: Boolean = true
                    override val exitStatus: Int = 0
                    override fun connect(connectTimeoutMillis: Int) = Unit
                    override fun disconnect() = Unit
                }
                override fun disconnect() = Unit
            }
        }

        val transport = JSchSshTransport(testDispatcher, vault, connector)
        val lazyRequest = com.nodeseek.app.domain.ssh.SshConnectionRequest(
            host = "10.0.0.9",
            port = 22,
            username = "root",
            authMethod = SshAuthMethod.PASSWORD,
            secret = "pw-target",
            allowHostKeyMismatchOverride = true,
            serverId = "target-only",
            alias = "Target",
            jumpHostId = bastion.id,
            jumpHosts = emptyList(),
        )

        transport.execute(lazyRequest, "hostname").toList()

        val hopSpec = observedSpecs.first { it.serverId == bastion.id }
        val targetSpec = observedSpecs.first { it.serverId == "target-only" }
        assertFalse(
            "Target allowHostKeyMismatchOverride must NEVER leak to lazily resolved jump hop",
            hopSpec.allowHostKeyMismatchOverride,
        )
        assertTrue(targetSpec.allowHostKeyMismatchOverride)
    }

    @Test
    fun `PasswordAndInteractiveUserInfo answers single hidden password prompt and refuses echo multi-prompt and OTP`() {
        val userInfo = JSchSshTransport.PasswordAndInteractiveUserInfo("s3cr3t!")

        // Standard single hidden password prompt succeeds
        val okResp = userInfo.promptKeyboardInteractive(
            destination = "root@host",
            name = "",
            instruction = "",
            prompt = arrayOf("Password: "),
            echo = booleanArrayOf(false),
        )
        assertNotNull(okResp)
        assertEquals(listOf("s3cr3t!"), okResp!!.toList())

        // Empty prompt list returns empty array
        val emptyResp = userInfo.promptKeyboardInteractive("root@host", "", "", emptyArray(), BooleanArray(0))
        assertNotNull(emptyResp)
        assertEquals(0, emptyResp!!.size)

        // Echoed prompt must be refused (null) so password is never echoed
        assertNull(
            userInfo.promptKeyboardInteractive(
                destination = "root@host",
                name = "",
                instruction = "",
                prompt = arrayOf("Password: "),
                echo = booleanArrayOf(true),
            ),
        )

        // Multi-prompt challenge must be refused (null)
        assertNull(
            userInfo.promptKeyboardInteractive(
                destination = "root@host",
                name = "",
                instruction = "",
                prompt = arrayOf("Password: ", "Verification code: "),
                echo = booleanArrayOf(false, false),
            ),
        )

        // 2FA / OTP / Verification prompts must be refused even when echo=false
        assertNull(
            userInfo.promptKeyboardInteractive(
                destination = "root@host",
                name = "",
                instruction = "",
                prompt = arrayOf("Verification code: "),
                echo = booleanArrayOf(false),
            ),
        )
        assertNull(
            userInfo.promptKeyboardInteractive(
                destination = "root@host",
                name = "",
                instruction = "",
                prompt = arrayOf("Google Authenticator OTP: "),
                echo = booleanArrayOf(false),
            ),
        )
        assertNull(
            userInfo.promptKeyboardInteractive(
                destination = "root@host",
                name = "",
                instruction = "",
                prompt = arrayOf("请输入动态验证码: "),
                echo = booleanArrayOf(false),
            ),
        )
    }
}
