package com.nodeseek.app.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.nodeseek.app.data.ssh.JSchSshTransport
import com.nodeseek.app.data.ssh.JschSftpClient
import com.nodeseek.app.data.ssh.ManagedExecChannel
import com.nodeseek.app.data.ssh.ManagedSftpChannel
import com.nodeseek.app.data.ssh.ManagedSshSession
import com.nodeseek.app.data.ssh.SftpAttrs
import com.nodeseek.app.data.ssh.SftpLsEntry
import com.nodeseek.app.data.ssh.SshHopEndpointSpec
import com.nodeseek.app.data.ssh.SshSessionConnector
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshSftpClient
import com.nodeseek.app.domain.ssh.SshSftpEntry
import com.nodeseek.app.domain.ssh.SshSftpErrorCode
import com.nodeseek.app.domain.ssh.SshSftpException
import com.nodeseek.app.domain.ssh.SshSftpQuickShortcuts
import com.nodeseek.app.domain.ssh.SshSftpTextContent
import com.nodeseek.app.domain.ssh.SshSftpTransport
import com.nodeseek.app.domain.ssh.SshSftpWriteResult
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class JschSftpClientAndDrawerTest {

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

    private class FakeManagedSftpChannel(
        private val home: String = "/root",
    ) : ManagedSftpChannel {
        val dirs = mutableSetOf("/", "/root", "/etc", "/etc/ssh", "/var", "/var/log")
        val files = linkedMapOf<String, ByteArray>()
        val symlinks = linkedMapOf<String, String>()
        val opLog = mutableListOf<String>()
        var failPutOnBak = false
        var closed = false

        override val isClosed: Boolean get() = closed

        override fun connect(connectTimeoutMillis: Int) {
            opLog += "connect"
        }

        override fun pwd(): String = home
        override fun getHome(): String = home

        override fun ls(path: String): List<SftpLsEntry> {
            opLog += "ls:$path"
            val result = mutableListOf(
                SftpLsEntry(".", isDir = true),
                SftpLsEntry("..", isDir = true),
            )
            val prefix = if (path == "/") "/" else "$path/"
            val seen = mutableSetOf<String>()
            for (d in dirs) {
                if (d != path && d.startsWith(prefix)) {
                    val rest = d.removePrefix(prefix)
                    if ('/' !in rest && rest.isNotEmpty() && seen.add(rest)) {
                        result += SftpLsEntry(rest, isDir = true, permissionsString = "drwxr-xr-x", modifiedEpochSeconds = 1000L)
                    }
                }
            }
            for ((f, bytes) in files) {
                if (f.startsWith(prefix)) {
                    val rest = f.removePrefix(prefix)
                    if ('/' !in rest && rest.isNotEmpty() && seen.add(rest)) {
                        result += SftpLsEntry(rest, isDir = false, sizeBytes = bytes.size.toLong(), permissionsString = "-rw-r--r--", modifiedEpochSeconds = 2000L)
                    }
                }
            }
            for ((s, _) in symlinks) {
                if (s.startsWith(prefix)) {
                    val rest = s.removePrefix(prefix)
                    if ('/' !in rest && rest.isNotEmpty() && seen.add(rest)) {
                        result += SftpLsEntry(rest, isDir = false, isLink = true, permissionsString = "lrwxrwxrwx")
                    }
                }
            }
            return result
        }

        override fun stat(path: String): SftpAttrs? {
            opLog += "stat:$path"
            val target = symlinks[path] ?: path
            if (target in dirs) return SftpAttrs(isDir = true, permissionsString = "drwxr-xr-x")
            val bytes = files[target] ?: return null
            return SftpAttrs(isDir = false, sizeBytes = bytes.size.toLong(), permissionsString = "-rw-r--r--")
        }

        override fun lstat(path: String): SftpAttrs? = stat(path)

        override fun get(path: String): InputStream {
            opLog += "get:$path"
            val bytes = files[path] ?: throw SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "No such file")
            return ByteArrayInputStream(bytes)
        }

        override fun get(path: String, skipOffsetBytes: Long): InputStream {
            opLog += "get:$path@$skipOffsetBytes"
            val bytes = files[path] ?: throw SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "No such file")
            val start = skipOffsetBytes.toInt().coerceIn(0, bytes.size)
            return ByteArrayInputStream(bytes.copyOfRange(start, bytes.size))
        }

        override fun put(inputStream: InputStream, path: String, overwrite: Boolean) {
            opLog += "put:$path"
            if (failPutOnBak && path.endsWith(".bak")) {
                throw SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied")
            }
            files[path] = inputStream.readBytes()
        }

        override fun mkdir(path: String) {
            opLog += "mkdir:$path"
            dirs += path
        }

        override fun rm(path: String) {
            opLog += "rm:$path"
            files.remove(path) ?: throw SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "missing")
        }

        override fun rmdir(path: String) {
            opLog += "rmdir:$path"
            dirs.remove(path)
        }

        override fun disconnect() {
            opLog += "disconnect"
            closed = true
        }
    }

    @Test
    fun `listDir filters dot entries resolves symlink dirs and sorts dirs first`() = runBlocking {
        val ch = FakeManagedSftpChannel()
        ch.dirs += "/root/zeta_dir"
        ch.dirs += "/root/alpha_dir"
        ch.files["/root/beta.conf"] = "ok".toByteArray()
        ch.symlinks["/root/link_etc"] = "/etc"

        val client = JschSftpClient(ch, homeDirectory = "/root", ioDispatcher = Dispatchers.Unconfined)
        val entries = client.listDir(".")

        assertFalse(entries.any { it.name == "." || it.name == ".." })
        assertEquals(listOf("alpha_dir", "link_etc", "zeta_dir", "beta.conf"), entries.map { it.name })
        assertTrue(entries.first { it.name == "link_etc" }.isDirectory)
        assertTrue(entries.first { it.name == "link_etc" }.isSymlink)
    }

    @Test
    fun `readTextPreview tails large logs detects binary and guards oversized edits`() = runBlocking {
        val ch = FakeManagedSftpChannel()
        val bigLog = ("A".repeat(100) + "TAIL_MARKER_END").toByteArray(StandardCharsets.UTF_8)
        ch.files["/var/log/auth.log"] = bigLog
        ch.files["/root/bin.dat"] = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01)

        val client = JschSftpClient(ch, homeDirectory = "/root", ioDispatcher = Dispatchers.Unconfined)

        val logPreview = client.readTextPreview("/var/log/auth.log", maxBytes = 20L, tailIfLarger = true)
        assertTrue(logPreview.isTruncatedFromHead)
        assertFalse(logPreview.canEdit)
        assertTrue(logPreview.text.endsWith("TAIL_MARKER_END"))

        val binaryPreview = client.readTextPreview("/root/bin.dat", maxBytes = 1024L, tailIfLarger = false)
        assertTrue(binaryPreview.isBinaryDetected)
        assertFalse(binaryPreview.canEdit)

        var threwTooLarge = false
        try {
            client.readTextPreview("/var/log/auth.log", maxBytes = 20L, tailIfLarger = false)
        } catch (e: SshSftpException) {
            threwTooLarge = e.code == SshSftpErrorCode.FILE_TOO_LARGE
        }
        assertTrue(threwTooLarge)
    }

    @Test
    fun `writeWithBakBackup creates verified bak before overwriting and aborts on bak failure`() = runBlocking {
        val ch = FakeManagedSftpChannel()
        ch.files["/etc/ssh/sshd_config"] = "Port 22\n".toByteArray(StandardCharsets.UTF_8)

        val client = JschSftpClient(ch, homeDirectory = "/root", ioDispatcher = Dispatchers.Unconfined)
        val writeRes = client.writeWithBakBackup(
            remotePath = "/etc/ssh/sshd_config",
            content = "Port 2222\n".toByteArray(StandardCharsets.UTF_8),
        )
        assertTrue(writeRes.backupCreated)
        assertEquals("/etc/ssh/sshd_config.bak", writeRes.backupPath)
        assertEquals("Port 22\n", String(ch.files["/etc/ssh/sshd_config.bak"]!!))
        assertEquals("Port 2222\n", String(ch.files["/etc/ssh/sshd_config"]!!))
        assertEquals(
            listOf(
                "stat:/etc/ssh/sshd_config",
                "get:/etc/ssh/sshd_config",
                "put:/etc/ssh/sshd_config.bak",
                "stat:/etc/ssh/sshd_config.bak",
                "put:/etc/ssh/sshd_config",
            ),
            ch.opLog,
        )

        // Backup write failure MUST abort before overwriting target
        ch.opLog.clear()
        ch.failPutOnBak = true
        var threwPerm = false
        try {
            client.writeWithBakBackup(
                remotePath = "/etc/ssh/sshd_config",
                content = "Port 9999\n".toByteArray(StandardCharsets.UTF_8),
            )
        } catch (e: SshSftpException) {
            threwPerm = e.code == SshSftpErrorCode.PERMISSION_DENIED
        }
        assertTrue(threwPerm)
        assertEquals("Port 2222\n", String(ch.files["/etc/ssh/sshd_config"]!!))

        // New file writes without .bak
        ch.failPutOnBak = false
        val newRes = client.writeWithBakBackup("/etc/caddy/Caddyfile", ":80 {\n}\n".toByteArray())
        assertFalse(newRes.backupCreated)
        assertNull(newRes.backupPath)
    }

    @Test
    fun `writeWithBakBackup_mismatchedLargerBackupSize_abortsOverwrite`() = runBlocking {
        val delegate = FakeManagedSftpChannel()
        delegate.files["/etc/ssh/sshd_config"] = "Port 22\n".toByteArray(StandardCharsets.UTF_8)
        val inflatedStatChannel = object : ManagedSftpChannel by delegate {
            override fun stat(path: String): SftpAttrs? {
                val base = delegate.stat(path) ?: return null
                return if (path.endsWith(".bak")) {
                    base.copy(sizeBytes = base.sizeBytes + 4L)
                } else {
                    base
                }
            }
        }
        val client = JschSftpClient(inflatedStatChannel, homeDirectory = "/root", ioDispatcher = Dispatchers.Unconfined)
        var threw = false
        try {
            client.writeWithBakBackup(
                remotePath = "/etc/ssh/sshd_config",
                content = "Port 2222\n".toByteArray(StandardCharsets.UTF_8),
            )
        } catch (e: SshSftpException) {
            threw = e.code == SshSftpErrorCode.UNKNOWN && e.message?.contains("大小") == true
        }
        assertTrue(threw)
        assertEquals("Port 22\n", String(delegate.files["/etc/ssh/sshd_config"]!!))
    }

    @Test
    fun `sftpOps_nullByteOrNewlineInPath_rejected`() = runBlocking {
        val ch = FakeManagedSftpChannel()
        val client = JschSftpClient(ch, homeDirectory = "/root", ioDispatcher = Dispatchers.Unconfined)
        val badPaths = listOf(
            "/etc/dir\u0000evil",
            "/etc/dir\nevil",
            "/etc/dir\revil",
        )
        for (bad in badPaths) {
            var rejectedList = false
            try {
                client.listDir(bad)
            } catch (_: SshSftpException) {
                rejectedList = true
            }
            assertTrue("Expected listDir rejection for control chars", rejectedList)

            var rejectedWrite = false
            try {
                client.writeWithBakBackup(bad, "test".toByteArray())
            } catch (_: SshSftpException) {
                rejectedWrite = true
            }
            assertTrue("Expected writeWithBakBackup rejection for control chars", rejectedWrite)
        }
        assertTrue("Channel must not be invoked for invalid control-char paths", ch.opLog.isEmpty())
    }

    @Test
    fun `uploadBytes mkdir and delete operate cleanly on ManagedSftpChannel`() = runBlocking {
        val ch = FakeManagedSftpChannel()
        val client = JschSftpClient(ch, homeDirectory = "/root", ioDispatcher = Dispatchers.Unconfined)

        val written = client.uploadBytes("/root/deploy.sh", "#!/bin/sh\necho ok\n".toByteArray())
        assertEquals(18L, written)
        client.mkdir("/root/certs")
        assertTrue("/root/certs" in ch.dirs)
        client.delete("/root/deploy.sh", isDirectory = false)
        assertFalse("/root/deploy.sh" in ch.files)
        client.delete("/root/certs", isDirectory = true)
        assertFalse("/root/certs" in ch.dirs)
    }

    @Test
    fun `JSchSshTransport openSftpSession supports ProxyJump TOFU and reverse teardown`() = runBlocking {
        val events = mutableListOf<String>()
        val sftpChannel = FakeManagedSftpChannel("/home/ops")
        val connector = SshSessionConnector { spec: SshHopEndpointSpec ->
            object : ManagedSshSession {
                override val lastTunnelError: String? = null
                override fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult? {
                    events += "connect:${spec.logicalHost}:${spec.connectHost}:${spec.connectPort}"
                    if (spec.expectedHostKeyFingerprint != null &&
                        spec.expectedHostKeyFingerprint != "SHA256:good" &&
                        !spec.allowHostKeyMismatchOverride
                    ) {
                        throw SshHostKeyMismatchException(
                            expectedFingerprint = spec.expectedHostKeyFingerprint,
                            actualHostKey = HostKeyInfo(spec.logicalHost, spec.logicalPort, "ssh-ed25519", "SHA256:good"),
                            serverId = spec.serverId,
                            serverAlias = spec.displayLabel,
                        )
                    }
                    return HostKeyVerificationResult.FirstUseTrust(
                        HostKeyInfo(spec.logicalHost, spec.logicalPort, "ssh-ed25519", "SHA256:good"),
                    )
                }

                override fun setPortForwardingL(bindAddress: String, localPort: Int, targetHost: String, targetPort: Int, connectTimeoutMillis: Int): Int {
                    events += "fwd:${spec.logicalHost}->$targetHost:$targetPort"
                    return 45678
                }

                override fun delPortForwardingL(bindAddress: String, localPort: Int) {
                    events += "delfwd:${spec.logicalHost}:$localPort"
                }

                override fun openExecChannel(): ManagedExecChannel = error("unused")
                override fun openSftpChannel(): ManagedSftpChannel {
                    events += "openSftp:${spec.logicalHost}"
                    return sftpChannel
                }

                override fun disconnect() {
                    events += "disconnect:${spec.logicalHost}"
                }
            }
        }

        val transport = JSchSshTransport(
            ioDispatcher = Dispatchers.Unconfined,
            vaultRepository = null,
            sessionConnector = connector,
        )
        val req = SshConnectionRequest(
            serverId = "target",
            alias = "Target",
            host = "10.10.0.5",
            port = 22,
            username = "ops",
            secret = "pw",
            jumpHosts = listOf(
                com.nodeseek.app.domain.ssh.SshJumpHop(
                    serverId = "bastion",
                    alias = "Bastion",
                    host = "bastion.example.com",
                    port = 22,
                    username = "jump",
                    secret = "pw",
                ),
            ),
        )

        val observed = mutableListOf<String?>()
        val client = transport.openSftpSession(req) { _, id -> observed += id }
        assertEquals("/home/ops", client.homeDirectory)
        assertEquals(listOf("bastion", "target"), observed)
        client.close()

        // Reverse order: sftpChannel disconnect -> target disconnect -> jump delPortForwardingL -> jump disconnect
        val dTargetIdx = events.indexOf("disconnect:10.10.0.5")
        val delFwdIdx = events.indexOf("delfwd:bastion.example.com:45678")
        val dJumpIdx = events.indexOf("disconnect:bastion.example.com")
        assertTrue(dTargetIdx in 0 until delFwdIdx)
        assertTrue(delFwdIdx < dJumpIdx)

        // TOFU mismatch closes jump session cleanly before opening SFTP channel
        events.clear()
        val badReq = req.copy(expectedHostKeyFingerprint = "SHA256:wrong")
        var threwMismatch = false
        try {
            transport.openSftpSession(badReq)
        } catch (_: SshHostKeyMismatchException) {
            threwMismatch = true
        }
        assertTrue(threwMismatch)
        assertTrue("delfwd:bastion.example.com:45678" in events)
        assertTrue("disconnect:bastion.example.com" in events)
        assertFalse(events.any { it.startsWith("openSftp:") })
    }

    @Test
    fun `LiteSshViewModel SFTP drawer guides PANEL_TERMINAL credentials edits with bak and uploads`() = runTest {
        val vault = SshServerVault(
            vaultFile = File(tempFolder.root, "sftp_drawer_vault_${System.nanoTime()}.json"),
            cipher = InMemoryTestCipher(),
            ioDispatcher = testDispatcher,
        )
        val probeOnly = vault.saveServer(
            SshServerDraft(
                alias = "ProbeOnly",
                host = "",
                username = "root",
                connectionMode = SshConnectionMode.PANEL_TERMINAL,
                probePanelId = "p1",
                probeServerId = "1",
            ),
        )
        val direct = vault.saveServer(
            SshServerDraft(
                alias = "DirectBox",
                host = "198.51.100.44",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )

        var sftpConnectCount = 0
        val remoteFiles = linkedMapOf("/etc/ssh/sshd_config" to "Port 22\n".toByteArray())
        val fakeClient = object : SshSftpClient {
            override val homeDirectory: String = "/root"
            override val isConnected: Boolean = true
            override suspend fun listDir(path: String): List<SshSftpEntry> {
                val base = if (path == ".") "/root" else path
                return remoteFiles.keys
                    .filter { it.startsWith(if (base == "/") "/" else "$base/") }
                    .map { full ->
                        SshSftpEntry(
                            name = full.substringAfterLast('/'),
                            fullPath = full,
                            isDirectory = false,
                            sizeBytes = remoteFiles[full]?.size?.toLong() ?: 0L,
                        )
                    }
            }
            override suspend fun readTextPreview(remotePath: String, maxBytes: Long, tailIfLarger: Boolean): SshSftpTextContent {
                val data = remoteFiles[remotePath] ?: error("missing")
                return SshSftpTextContent(remotePath, String(data), data.size.toLong(), data.size.toLong(), canEdit = true)
            }
            override suspend fun writeWithBakBackup(remotePath: String, content: ByteArray, createBackupIfExists: Boolean): SshSftpWriteResult {
                val orig = remoteFiles[remotePath]
                val bakPath = "$remotePath.bak"
                if (orig != null) remoteFiles[bakPath] = orig
                remoteFiles[remotePath] = content
                return SshSftpWriteResult(remotePath, if (orig != null) bakPath else null, content.size.toLong(), orig != null)
            }
            override suspend fun uploadStream(remotePath: String, inputStream: InputStream, overwrite: Boolean): Long {
                val bytes = inputStream.readBytes()
                remoteFiles[remotePath] = bytes
                return bytes.size.toLong()
            }
            override suspend fun mkdir(remotePath: String) = Unit
            override suspend fun delete(remotePath: String, isDirectory: Boolean) {
                remoteFiles.remove(remotePath)
            }
            override fun close() = Unit
        }
        val fakeSftpTransport = object : SshSftpTransport {
            override suspend fun openSftpSession(request: SshConnectionRequest, onHostKeyObserved: (HostKeyVerificationResult, String?) -> Unit): SshSftpClient {
                sftpConnectCount++
                return fakeClient
            }
        }
        val noopTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Completed(0))
            }
        }
        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = noopTransport),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            enableElapsedTicker = false,
            sftpTransportOverride = fakeSftpTransport,
        )
        val vm = LiteSshViewModel(sessionManager = manager, ownsSessionManagerScope = true)

        // 1. Probe-only server without direct SSH credentials shows credential guidance without calling transport
        vm.openSftpDrawer(probeOnly.id)
        assertTrue(vm.uiState.value.sftpDrawer.isOpen)
        assertTrue(vm.uiState.value.sftpDrawer.needsDirectSshCredentials)
        assertEquals(0, sftpConnectCount)

        // 2. Direct SSH server opens SFTP, opens sshd_config shortcut, edits & saves with .bak
        vm.openSftpDrawer(direct.id)
        assertEquals(1, sftpConnectCount)
        assertTrue(vm.uiState.value.sftpDrawer.isConnected)

        val sshdShortcut = SshSftpQuickShortcuts.presets.first { it.id == "sshd_config" }
        vm.sftpOpenQuickShortcut(sshdShortcut)
        assertNotNull(vm.uiState.value.sftpDrawer.openFile)
        assertTrue(vm.uiState.value.sftpDrawer.openFile!!.isEditMode)

        vm.sftpUpdateEditorContent("Port 2222\n")
        vm.sftpSaveFileWithBak()
        assertEquals("Port 22\n", String(remoteFiles["/etc/ssh/sshd_config.bak"]!!))
        assertEquals("Port 2222\n", String(remoteFiles["/etc/ssh/sshd_config"]!!))
        assertTrue(vm.uiState.value.sftpDrawer.lastBackupBanner!!.contains(".bak"))

        // 3. Upload stream places file in currentPath and refreshes
        vm.sftpCloseFileViewer()
        vm.sftpUploadStream("cert.pem", ByteArrayInputStream("CERT".toByteArray()))
        assertEquals("CERT", String(remoteFiles["/root/cert.pem"]!!))
        assertTrue(vm.uiState.value.sftpDrawer.entries.any { it.name == "cert.pem" })

        vm.dismissSftpDrawer()
        assertFalse(vm.uiState.value.sftpDrawer.isOpen)
    }

    @Test
    fun `readTextPreview clamps Long MAX_VALUE and path helpers canonicalize dotdot segments`() = runBlocking {
        val ch = FakeManagedSftpChannel()
        ch.files["/etc/ssh/sshd_config"] = "Port 22\n".toByteArray(StandardCharsets.UTF_8)

        val client = JschSftpClient(ch, homeDirectory = "/root", ioDispatcher = Dispatchers.Unconfined)
        val preview = client.readTextPreview("/etc/ssh/sshd_config", maxBytes = Long.MAX_VALUE, tailIfLarger = true)
        assertEquals("Port 22\n", preview.text)
        assertEquals(8L, preview.readBytes)

        assertEquals("/etc/ssh/sshd_config", JschSftpClient.resolveFilePath("/root/../etc/ssh/sshd_config", "/root"))
        assertEquals("/etc/ssh/sshd_config", JschSftpClient.resolveFilePath("../etc/ssh/sshd_config", "/root"))
        assertEquals("/", JschSftpClient.parentDirectory("/root/.."))
        assertEquals("/etc", JschSftpClient.parentDirectory("/root/../etc/ssh"))
        assertEquals("/", JschSftpClient.normalizeRemoteDirPath("/../../..", "/root"))
    }

    @Test
    fun `switching to PANEL_TERMINAL mid-connect and cancelling save or upload behave safely`() = runTest {
        val vault = SshServerVault(
            vaultFile = File(tempFolder.root, "sftp_race_vault_${System.nanoTime()}.json"),
            cipher = InMemoryTestCipher(),
            ioDispatcher = testDispatcher,
        )
        val directA = vault.saveServer(
            SshServerDraft(
                alias = "DirectA",
                host = "198.51.100.10",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )
        val probeB = vault.saveServer(
            SshServerDraft(
                alias = "ProbeB",
                host = "",
                username = "root",
                connectionMode = SshConnectionMode.PANEL_TERMINAL,
                probePanelId = "p1",
                probeServerId = "2",
            ),
        )

        val connectGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val saveGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var staleClosedCount = 0
        var allowDirectConnectImmediately = false

        val fakeClientA = object : SshSftpClient {
            override val homeDirectory: String = "/root"
            override val isConnected: Boolean = true
            override suspend fun listDir(path: String): List<SshSftpEntry> = emptyList()
            override suspend fun readTextPreview(remotePath: String, maxBytes: Long, tailIfLarger: Boolean): SshSftpTextContent =
                SshSftpTextContent(remotePath, "Port 22\n", 8L, 8L, canEdit = true)
            override suspend fun writeWithBakBackup(remotePath: String, content: ByteArray, createBackupIfExists: Boolean): SshSftpWriteResult {
                saveGate.await()
                return SshSftpWriteResult(remotePath, "$remotePath.bak", content.size.toLong(), true)
            }
            override suspend fun uploadStream(remotePath: String, inputStream: InputStream, overwrite: Boolean): Long = 0L
            override suspend fun mkdir(remotePath: String) = Unit
            override suspend fun delete(remotePath: String, isDirectory: Boolean) = Unit
            override fun close() {
                staleClosedCount++
            }
        }
        val fakeSftpTransport = object : SshSftpTransport {
            override suspend fun openSftpSession(request: SshConnectionRequest, onHostKeyObserved: (HostKeyVerificationResult, String?) -> Unit): SshSftpClient {
                if (!allowDirectConnectImmediately) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        connectGate.await()
                    }
                }
                return fakeClientA
            }
        }
        val noopTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Completed(0))
            }
        }
        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = noopTransport),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            enableElapsedTicker = false,
            sftpTransportOverride = fakeSftpTransport,
        )

        try {
            // 1. Mid-connect switch to PANEL_TERMINAL must NOT let DirectA overwrite ProbeB drawer
            manager.openSftpDrawer(directA.id)
            assertTrue(manager.uiState.value.sftpDrawer.isConnecting)
            manager.openSftpDrawer(probeB.id)
            connectGate.complete(Unit)

            val afterRace = manager.uiState.value.sftpDrawer
            assertTrue(afterRace.isOpen)
            assertEquals(probeB.id, afterRace.serverId)
            assertTrue(afterRace.needsDirectSshCredentials)
            assertFalse(afterRace.isConnected)
            assertFalse(afterRace.isConnecting)
            assertEquals(1, staleClosedCount)

            // 2. Cancelling in-flight sftpSaveFileWithBak via sftpRefreshCurrentDir resets isSaving=false and isOperating=false
            allowDirectConnectImmediately = true
            manager.openSftpDrawer(directA.id)
            assertTrue(manager.uiState.value.sftpDrawer.isConnected)

            manager.sftpOpenFile("/etc/ssh/sshd_config", preferEditMode = true)
            assertNotNull(manager.uiState.value.sftpDrawer.openFile)
            manager.sftpUpdateEditorContent("Port 2222\n")
            manager.sftpSaveFileWithBak()
            assertTrue(manager.uiState.value.sftpDrawer.openFile!!.isSaving)

            manager.sftpRefreshCurrentDir()
            assertFalse(manager.uiState.value.sftpDrawer.openFile!!.isSaving)
            assertFalse(manager.uiState.value.sftpDrawer.isOperating)

            // 3. Cancelling queued sftpUploadStream closes the InputStream via invokeOnCompletion
            var streamClosed = false
            val trackedStream = object : ByteArrayInputStream("DATA".toByteArray()) {
                override fun close() {
                    streamClosed = true
                    super.close()
                }
            }
            val queuedManager = LiteSshSessionManager(
                vaultRepository = vault,
                executor = LiteSshExecutor(transport = noopTransport),
                externalScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)),
                enableElapsedTicker = false,
                sftpTransportOverride = fakeSftpTransport,
            )
            try {
                // Connect synchronously enough by advancing scheduler
                queuedManager.openSftpDrawer(directA.id)
                testScheduler.advanceUntilIdle()
                queuedManager.sftpUploadStream("test.crt", trackedStream)
                // Immediately cancel before scheduled coroutine runs
                queuedManager.dismissSftpDrawer()
                testScheduler.advanceUntilIdle()
                assertTrue(streamClosed)
            } finally {
                queuedManager.disposeOwnedScopeForTests()
            }
        } finally {
            manager.disposeOwnedScopeForTests()
        }
    }

    @Test
    fun `immediate sftpOpenFile triggered on isConnected emission never closes active SftpClient`() = runBlocking {
        repeat(20) { iteration ->
            val vault = SshServerVault(
                vaultFile = File(tempFolder.root, "sftp_connect_race_${iteration}_${System.nanoTime()}.json"),
                cipher = InMemoryTestCipher(),
                ioDispatcher = Dispatchers.Unconfined,
            )
            val server = vault.saveServer(
                SshServerDraft(
                    alias = "RaceServer",
                    host = "198.51.100.77",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pw",
                ),
            )

            var closed = false
            val client = object : SshSftpClient {
                override val homeDirectory: String = "/root"
                override val isConnected: Boolean get() = !closed
                override suspend fun listDir(path: String): List<SshSftpEntry> = emptyList()
                override suspend fun readTextPreview(remotePath: String, maxBytes: Long, tailIfLarger: Boolean): SshSftpTextContent {
                    if (closed) throw IllegalStateException("SFTP client closed prematurely!")
                    return SshSftpTextContent(remotePath, "Port 22\n", 8L, 8L, canEdit = true)
                }
                override suspend fun writeWithBakBackup(remotePath: String, content: ByteArray, createBackupIfExists: Boolean): SshSftpWriteResult =
                    SshSftpWriteResult(remotePath, "$remotePath.bak", content.size.toLong(), true)
                override suspend fun uploadStream(remotePath: String, inputStream: InputStream, overwrite: Boolean): Long = 0L
                override suspend fun mkdir(remotePath: String) = Unit
                override suspend fun delete(remotePath: String, isDirectory: Boolean) = Unit
                override fun close() {
                    closed = true
                }
            }
            val transport = object : SshSftpTransport {
                override suspend fun openSftpSession(
                    request: SshConnectionRequest,
                    onHostKeyObserved: (HostKeyVerificationResult, String?) -> Unit,
                ): SshSftpClient = client
            }
            val noopTransport = object : SshTransport {
                override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                    emit(SshStreamEvent.Completed(0))
                }
            }
            val manager = LiteSshSessionManager(
                vaultRepository = vault,
                executor = LiteSshExecutor(transport = noopTransport),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                enableElapsedTicker = false,
                sftpTransportOverride = transport,
            )

            val triggered = java.util.concurrent.atomic.AtomicBoolean(false)
            val collectorJob = launch(Dispatchers.Default) {
                manager.uiState.collect { state ->
                    if (state.sftpDrawer.isConnected && triggered.compareAndSet(false, true)) {
                        manager.sftpOpenFile("/etc/ssh/sshd_config", preferEditMode = true)
                    }
                }
            }
            try {
                kotlinx.coroutines.withTimeout(3_000L) {
                    manager.uiState.first { state -> state.servers.any { it.id == server.id } }
                }
                manager.openSftpDrawer(server.id)
                val finalState = kotlinx.coroutines.withTimeout(3_000L) {
                    manager.uiState.first {
                        it.sftpDrawer.openFile?.remotePath == "/etc/ssh/sshd_config" || it.sftpDrawer.errorMessage != null
                    }
                }
                assertFalse("Client closed prematurely on iter=$iteration", closed)
                assertNull("Unexpected error on iter=$iteration: ${finalState.sftpDrawer.errorMessage}", finalState.sftpDrawer.errorMessage)
                assertEquals("/etc/ssh/sshd_config", finalState.sftpDrawer.openFile?.remotePath)
                assertTrue(finalState.sftpDrawer.isConnected)
            } finally {
                collectorJob.cancel()
                manager.disposeOwnedScopeForTests()
            }
        }
    }
}
