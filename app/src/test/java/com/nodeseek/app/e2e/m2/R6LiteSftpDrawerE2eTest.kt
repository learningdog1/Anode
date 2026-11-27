package com.nodeseek.app.e2e.m2

import com.nodeseek.app.data.ssh.JSchSshTransport
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshJumpConnectionStage
import com.nodeseek.app.domain.ssh.SshJumpHostConnectionException
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshSftpClient
import com.nodeseek.app.domain.ssh.SshSftpErrorCode
import com.nodeseek.app.domain.ssh.SshSftpQuickShortcuts
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eSftpClient
import com.nodeseek.app.e2e.E2eSftpTransport
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class R6LiteSftpDrawerE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T1-R6-1 opens SFTP drawer on direct SSH host, lists directories-first entries, navigates into subdir and up`() = runTest {
        E2eTestHarness().use { harness ->
            val server = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "HK Direct",
                    host = "198.51.100.10",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pass-123",
                ),
            )
            val sftpClient = E2eSftpClient(
                homeDirectory = "/root",
                initialFiles = mapOf(
                    "/root/docker-compose.yml" to "services:\n  app:\n    image: nginx:alpine\n".toByteArray(),
                    "/root/notes.txt" to "hello\n".toByteArray(),
                    "/etc/ssh/sshd_config" to "Port 22\nPermitRootLogin yes\n".toByteArray(),
                    "/etc/caddy/Caddyfile" to ":80 {\n  respond \"ok\"\n}\n".toByteArray(),
                ),
                initialDirs = setOf("/root/scripts", "/etc/ssh", "/etc/caddy"),
            )
            val sftpTransport = E2eSftpTransport(clientFactory = { sftpClient })
            val manager = harness.createSshSessionManager(sftpTransport = sftpTransport)
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == server.id } }
                }
            }

            vm.openSftpDrawer(server.id)
            val connected = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.sftpDrawer.isConnected && !it.sftpDrawer.isConnecting }
                }
            }
            assertTrue(connected.sftpDrawer.isOpen)
            assertEquals("/root", connected.sftpDrawer.homePath)
            assertEquals("/root", connected.sftpDrawer.currentPath)
            assertTrue(connected.sftpDrawer.entries.first().isDirectory)
            assertEquals("scripts", connected.sftpDrawer.entries.first().name)
            assertTrue(connected.sftpDrawer.entries.any { !it.isDirectory && it.name == "docker-compose.yml" })

            vm.sftpListDir("/etc/ssh")
            val inEtcSsh = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isLoadingDir && it.sftpDrawer.currentPath == "/etc/ssh" }
                }
            }
            assertEquals(listOf("sshd_config"), inEtcSsh.sftpDrawer.entries.map { it.name })

            vm.sftpNavigateUp()
            val inEtc = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isLoadingDir && it.sftpDrawer.currentPath == "/etc" }
                }
            }
            assertTrue(inEtc.sftpDrawer.entries.any { it.isDirectory && it.name == "ssh" })

            vm.sftpRefreshCurrentDir()
            val refreshed = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isLoadingDir && it.sftpDrawer.currentPath == "/etc" }
                }
            }
            assertNull(refreshed.sftpDrawer.errorMessage)
        }
    }

    @Test
    fun `E2E-T1-R6-2 opens sshd_config, Caddyfile, and auth_log via quick shortcuts with edit vs read preview modes`() = runTest {
        E2eTestHarness().use { harness ->
            val server = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Config Node",
                    host = "198.51.100.11",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )
            val sshdText = "Port 22\nPasswordAuthentication yes\n"
            val caddyText = "example.com {\n  reverse_proxy 127.0.0.1:8080\n}\n"
            val authLogText = "Nov 25 21:00:00 vps sshd[101]: Accepted publickey for root\n"

            val sftpClient = E2eSftpClient(
                homeDirectory = "/root",
                initialFiles = mapOf(
                    "/etc/ssh/sshd_config" to sshdText.toByteArray(),
                    "/etc/caddy/Caddyfile" to caddyText.toByteArray(),
                    "/var/log/auth.log" to authLogText.toByteArray(),
                ),
            )
            val manager = harness.createSshSessionManager(sftpTransport = E2eSftpTransport(clientFactory = { sftpClient }))
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == server.id } }
                }
            }

            vm.openSftpDrawer(server.id)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.isConnected } }
            }

            val sshdShortcut = SshSftpQuickShortcuts.presets.first { it.id == "sshd_config" }
            vm.sftpOpenQuickShortcut(sshdShortcut)
            val sshdState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.remotePath == "/etc/ssh/sshd_config" }
                }
            }
            assertEquals(sshdText, sshdState.sftpDrawer.openFile?.originalText)
            assertTrue(sshdState.sftpDrawer.openFile?.isEditMode == true)
            assertTrue(sshdState.sftpDrawer.openFile?.canEdit == true)

            val caddyShortcut = SshSftpQuickShortcuts.presets.first { it.id == "caddyfile" }
            vm.sftpOpenQuickShortcut(caddyShortcut)
            val caddyState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.remotePath == "/etc/caddy/Caddyfile" }
                }
            }
            assertEquals(caddyText, caddyState.sftpDrawer.openFile?.originalText)
            assertTrue(caddyState.sftpDrawer.openFile?.isEditMode == true)

            val logShortcut = SshSftpQuickShortcuts.presets.first { it.id == "auth_log" }
            vm.sftpOpenQuickShortcut(logShortcut)
            val logState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.remotePath == "/var/log/auth.log" }
                }
            }
            assertEquals(authLogText, logState.sftpDrawer.openFile?.originalText)
            assertFalse(logState.sftpDrawer.openFile?.isEditMode == true)
            assertTrue(logState.sftpDrawer.openFile?.canEdit == true)
        }
    }

    @Test
    fun `E2E-T1-R6-3 saves docker-compose and sing-box config with automatic bak backup while new file omits bak`() = runTest {
        E2eTestHarness().use { harness ->
            val server = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "SingBox Node",
                    host = "198.51.100.12",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )
            val originalCompose = "services:\n  web:\n    image: caddy:2.7\n"
            val originalSingBox = """{"log":{"level":"info"}}"""
            val sftpClient = E2eSftpClient(
                homeDirectory = "/root",
                initialFiles = mapOf(
                    "/root/docker-compose.yml" to originalCompose.toByteArray(),
                    "/etc/sing-box/config.json" to originalSingBox.toByteArray(),
                ),
            )
            val manager = harness.createSshSessionManager(sftpTransport = E2eSftpTransport(clientFactory = { sftpClient }))
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == server.id } }
                }
            }

            vm.openSftpDrawer(server.id)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.isConnected } }
            }

            // 1. Open docker-compose.yml in read mode, enter edit mode, modify, save -> .bak created
            vm.sftpOpenFile("/root/docker-compose.yml", preferEditMode = false)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.openFile?.remotePath == "/root/docker-compose.yml" } }
            }
            vm.sftpEnterEditMode()
            val updatedCompose = "services:\n  web:\n    image: caddy:2.8\n"
            vm.sftpUpdateEditorContent(updatedCompose)
            assertTrue(vm.uiState.value.sftpDrawer.openFile?.isDirty == true)

            vm.sftpSaveFileWithBak()
            val savedComposeState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val openFile = it.sftpDrawer.openFile
                        !it.sftpDrawer.isOperating && openFile?.isSaving == false && !openFile.isDirty
                    }
                }
            }
            assertEquals(originalCompose, sftpClient.readRawFileBytes("/root/docker-compose.yml.bak")?.toString(Charsets.UTF_8))
            assertEquals(updatedCompose, sftpClient.readRawFileBytes("/root/docker-compose.yml")?.toString(Charsets.UTF_8))
            assertTrue(savedComposeState.sftpDrawer.lastBackupBanner?.contains(".bak") == true)
            assertTrue(savedComposeState.statusMessage?.contains(".bak") == true)

            // 2. Edit /etc/sing-box/config.json -> .bak created
            vm.sftpOpenQuickShortcut(SshSftpQuickShortcuts.presets.first { it.id == "sing_box" })
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.openFile?.remotePath == "/etc/sing-box/config.json" } }
            }
            val updatedSingBox = """{"log":{"level":"warn"}}"""
            vm.sftpUpdateEditorContent(updatedSingBox)
            vm.sftpSaveFileWithBak()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.isDirty == false }
                }
            }
            assertEquals(originalSingBox, sftpClient.readRawFileBytes("/etc/sing-box/config.json.bak")?.toString(Charsets.UTF_8))
            assertEquals(updatedSingBox, sftpClient.readRawFileBytes("/etc/sing-box/config.json")?.toString(Charsets.UTF_8))

            // 3. Brand-new file directly via SshSftpClient saves without creating .bak
            val newResult = sftpClient.writeWithBakBackup(
                remotePath = "/root/brand_new.conf",
                content = "enabled=true\n".toByteArray(),
                createBackupIfExists = true,
            )
            assertFalse(newResult.backupCreated)
            assertNull(newResult.backupPath)
            assertNull(sftpClient.readRawFileBytes("/root/brand_new.conf.bak"))
        }
    }

    @Test
    fun `E2E-T1-R6-4 uploads cert and script streams, creates directories, and deletes entries`() = runTest {
        E2eTestHarness().use { harness ->
            val server = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Upload Node",
                    host = "198.51.100.13",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )
            val sftpClient = E2eSftpClient(homeDirectory = "/root")
            val manager = harness.createSshSessionManager(sftpTransport = E2eSftpTransport(clientFactory = { sftpClient }))
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == server.id } }
                }
            }

            vm.openSftpDrawer(server.id)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.isConnected } }
            }

            vm.sftpCreateDirectory("certs")
            val afterMkdir = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.entries.any { e -> e.isDirectory && e.name == "certs" } }
                }
            }
            val certsEntry = afterMkdir.sftpDrawer.entries.first { it.name == "certs" }
            assertTrue(certsEntry.isDirectory)

            val certBytes = "-----BEGIN CERTIFICATE-----\nMIIE...\n-----END CERTIFICATE-----\n".toByteArray()
            val scriptBytes = "#!/usr/bin/env bash\necho deployed\n".toByteArray()

            vm.sftpUploadStream("cert.pem", ByteArrayInputStream(certBytes))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.entries.any { e -> e.name == "cert.pem" } }
                }
            }
            assertArrayEquals(certBytes, sftpClient.readRawFileBytes("/root/cert.pem"))

            vm.sftpUploadStream("deploy.sh", ByteArrayInputStream(scriptBytes))
            val afterScript = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.entries.any { e -> e.name == "deploy.sh" } }
                }
            }
            assertArrayEquals(scriptBytes, sftpClient.readRawFileBytes("/root/deploy.sh"))

            val deployEntry = afterScript.sftpDrawer.entries.first { it.name == "deploy.sh" }
            vm.sftpDeleteEntry(deployEntry)
            val afterDelete = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.entries.none { e -> e.name == "deploy.sh" } }
                }
            }
            assertNull(sftpClient.readRawFileBytes("/root/deploy.sh"))
            assertNull(afterDelete.sftpDrawer.errorMessage)
        }
    }

    @Test
    fun `E2E-T1-R6-5 SFTP via ProxyJump persists independent TOFU keys and real JSchSshTransport detects unreachable jump host`() = runTest {
        E2eTestHarness().use { harness ->
            val bastion = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Bastion",
                    host = "198.51.100.200",
                    port = 22,
                    username = "jump",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "jump-pass",
                ),
            )
            val target = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Private Target",
                    host = "10.20.0.5",
                    port = 22,
                    username = "ubuntu",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "target-pass",
                    jumpHostId = bastion.id,
                ),
            )

            val bastionKey = HostKeyInfo(
                host = "198.51.100.200",
                port = 22,
                algorithm = "ssh-ed25519",
                fingerprintSha256 = HostKeyTrustPolicy.computeSha256Fingerprint("bastion-key".toByteArray()),
            )
            val targetKey = HostKeyInfo(
                host = "10.20.0.5",
                port = 22,
                algorithm = "ssh-ed25519",
                fingerprintSha256 = HostKeyTrustPolicy.computeSha256Fingerprint("target-key".toByteArray()),
            )
            val sftpTransport = E2eSftpTransport(
                hostKeyByServerOrHost = mapOf(
                    bastion.id to bastionKey,
                    target.id to targetKey,
                ),
                clientFactory = { E2eSftpClient(homeDirectory = "/home/ubuntu") },
            )
            val manager = harness.createSshSessionManager(sftpTransport = sftpTransport)
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == target.id } }
                }
            }

            vm.openSftpDrawer(target.id)
            val connected = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.sftpDrawer.isConnected && it.sftpDrawer.serverId == target.id }
                }
            }
            assertEquals("/home/ubuntu", connected.sftpDrawer.homePath)
            assertEquals(1, sftpTransport.openedRequests.size)
            val req = sftpTransport.openedRequests.single()
            assertEquals(1, req.jumpHosts.size)
            assertEquals(bastion.id, req.jumpHosts.single().serverId)
            assertEquals("198.51.100.200", req.jumpHosts.single().host)

            val persistedBastion = harness.sshVaultRepository.serversFlow.value.first { it.id == bastion.id }
            val persistedTarget = harness.sshVaultRepository.serversFlow.value.first { it.id == target.id }
            assertEquals(bastionKey.fingerprintSha256, persistedBastion.knownHostKeyFingerprint)
            assertEquals(targetKey.fingerprintSha256, persistedTarget.knownHostKeyFingerprint)

            // Verify real JSchSshTransport public openSftpSession fails at JUMP_HOST stage on closed loopback port
            val closedPort = ServerSocket(0).use { it.localPort }
            val unreachableBastion = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Closed Bastion",
                    host = "127.0.0.1",
                    port = closedPort,
                    username = "jump",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "jump-pass",
                ),
            )
            val unreachableTarget = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Behind Closed Bastion",
                    host = "10.20.0.99",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "target-pass",
                    jumpHostId = unreachableBastion.id,
                ),
            )
            val realTransport = JSchSshTransport(vaultRepository = harness.sshVaultRepository)
            try {
                realTransport.openSftpSession(unreachableTarget)
                fail("Expected SshJumpHostConnectionException for closed bastion port")
            } catch (ex: SshJumpHostConnectionException) {
                assertEquals(SshJumpConnectionStage.JUMP_HOST, ex.stage)
                assertEquals(unreachableBastion.id, ex.hopServerId)
            }
        }
    }

    @Test
    fun `E2E-T2-R6-1 aborts SFTP on TOFU host key mismatch before listing and resumes after explicit trust`() = runTest {
        E2eTestHarness().use { harness ->
            val oldFp = HostKeyTrustPolicy.computeSha256Fingerprint("old-sftp-key".toByteArray())
            val newKey = HostKeyInfo(
                host = "198.51.100.30",
                port = 22,
                algorithm = "ssh-ed25519",
                fingerprintSha256 = HostKeyTrustPolicy.computeSha256Fingerprint("new-sftp-key".toByteArray()),
            )
            val saved = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Tofu Node",
                    host = "198.51.100.30",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )
            harness.sshVaultRepository.updateHostKeyFingerprint(saved.id, oldFp, "ssh-ed25519")

            val sftpClient = E2eSftpClient(homeDirectory = "/root")
            val sftpTransport = E2eSftpTransport(
                hostKeyByServerOrHost = mapOf(saved.id to newKey),
                clientFactory = { sftpClient },
            )
            val manager = harness.createSshSessionManager(sftpTransport = sftpTransport)
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == saved.id } }
                }
            }

            vm.openSftpDrawer(saved.id)
            val mismatchState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isConnecting && it.pendingHostKeyMismatch != null }
                }
            }
            assertFalse(mismatchState.sftpDrawer.isConnected)
            assertTrue(mismatchState.pendingHostKeyMismatch?.isSftpRetry == true)
            assertEquals(oldFp, mismatchState.pendingHostKeyMismatch?.expectedFingerprint)
            assertTrue(sftpClient.listDirCalls.isEmpty())

            vm.confirmTrustChangedHostKey()
            val recoveredState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.sftpDrawer.isConnected && it.pendingHostKeyMismatch == null }
                }
            }
            assertTrue(recoveredState.sftpDrawer.isConnected)
            assertEquals(
                newKey.fingerprintSha256,
                harness.sshVaultRepository.serversFlow.value.single().knownHostKeyFingerprint,
            )
            assertTrue(sftpClient.listDirCalls.isNotEmpty())
        }
    }

    @Test
    fun `E2E-T2-R6-2 aborts overwrite when bak backup fails with PERMISSION_DENIED`() = runTest {
        E2eTestHarness().use { harness ->
            val server = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Readonly Etc",
                    host = "198.51.100.31",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )
            val originalConfig = "Port 22\n"
            val sftpClient = E2eSftpClient(
                homeDirectory = "/root",
                initialFiles = mapOf("/etc/ssh/sshd_config" to originalConfig.toByteArray()),
                failBackupWith = SshSftpErrorCode.PERMISSION_DENIED,
            )
            val manager = harness.createSshSessionManager(sftpTransport = E2eSftpTransport(clientFactory = { sftpClient }))
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == server.id } }
                }
            }

            vm.openSftpDrawer(server.id)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.isConnected } }
            }
            vm.sftpOpenFile("/etc/ssh/sshd_config", preferEditMode = true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.openFile?.isEditMode == true } }
            }
            vm.sftpUpdateEditorContent("Port 2222\n")
            vm.sftpSaveFileWithBak()

            val failedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.errorMessage != null }
                }
            }
            assertEquals(originalConfig, sftpClient.readRawFileBytes("/etc/ssh/sshd_config")?.toString(Charsets.UTF_8))
            assertNull(sftpClient.readRawFileBytes("/etc/ssh/sshd_config.bak"))
            assertFalse(failedState.sftpDrawer.openFile?.isSaving == true)
            assertFalse(failedState.sftpDrawer.isOperating)
        }
    }

    @Test
    fun `E2E-T2-R6-3 rejects traversal or invalid names and blocks PANEL_TERMINAL-only hosts without SSH creds`() = runTest {
        E2eTestHarness().use { harness ->
            val panelOnly = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Panel Only Node",
                    host = "198.51.100.40",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "",
                    connectionMode = SshConnectionMode.PANEL_TERMINAL,
                    probePanelId = "panel-1",
                    probeServerId = "srv-1",
                ),
            )
            val direct = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Direct Node",
                    host = "198.51.100.41",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )
            val sftpClient = E2eSftpClient(homeDirectory = "/root")
            val sftpTransport = E2eSftpTransport(clientFactory = { sftpClient })
            val manager = harness.createSshSessionManager(sftpTransport = sftpTransport)
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        it.servers.any { s -> s.id == panelOnly.id } &&
                            it.servers.any { s -> s.id == direct.id }
                    }
                }
            }

            // 1. PANEL_TERMINAL-only host without SSH creds -> needsDirectSshCredentials == true, 0 opens
            vm.openSftpDrawer(panelOnly.id)
            val panelState = vm.uiState.value.sftpDrawer
            assertTrue(panelState.isOpen)
            assertTrue(panelState.needsDirectSshCredentials)
            assertFalse(panelState.isConnected)
            assertEquals(0, sftpTransport.openedRequests.size)

            // 2. Open direct host & test invalid mkdir / upload names
            vm.openSftpDrawer(direct.id)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.isConnected } }
            }

            for (badDir in listOf("", ".", "..", "nested/dir")) {
                vm.sftpClearError()
                vm.sftpCreateDirectory(badDir)
                assertNotNull("Expected error for badDir=$badDir", vm.uiState.value.sftpDrawer.errorMessage)
            }

            // Invalid dot upload names rejected cleanly
            for (badFile in listOf(".", "..", "")) {
                vm.sftpClearError()
                val closedFlag = AtomicBoolean(false)
                val stream = object : InputStream() {
                    override fun read(): Int = -1
                    override fun close() { closedFlag.set(true) }
                }
                vm.sftpUploadStream(badFile, stream)
                assertNotNull("Expected error for badFile=$badFile", vm.uiState.value.sftpDrawer.errorMessage)
                assertTrue(closedFlag.get())
            }

            // Traversal upload name `../evil` is flattened (`.._evil`) inside currentDir and never escapes /root
            vm.sftpClearError()
            vm.sftpUploadStream("../evil.txt", ByteArrayInputStream("safe".toByteArray()))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.entries.any { e -> e.name == ".._evil.txt" } }
                }
            }
            assertNull(sftpClient.readRawFileBytes("/evil.txt"))
            assertEquals("safe", sftpClient.readRawFileBytes("/root/.._evil.txt")?.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `E2E-T2-R6-4 opens oversized truncated logs and binary files read-only and blocks edit or overwrite`() = runTest {
        E2eTestHarness().use { harness ->
            val server = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "BigLog Node",
                    host = "198.51.100.42",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )
            val header = "HEAD_MARKER\n"
            val filler = "x".repeat((SshSftpClient.DEFAULT_PREVIEW_MAX_BYTES + 4096).toInt())
            val tail = "\nTAIL_MARKER\n"
            val bigLogBytes = (header + filler + tail).toByteArray(Charsets.UTF_8)
            val binaryBytes = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01, 0x02, 0x03)

            val sftpClient = E2eSftpClient(
                homeDirectory = "/root",
                initialFiles = mapOf(
                    "/var/log/syslog" to bigLogBytes,
                    "/usr/local/bin/tool" to binaryBytes,
                ),
            )
            val manager = harness.createSshSessionManager(sftpTransport = E2eSftpTransport(clientFactory = { sftpClient }))
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == server.id } }
                }
            }

            vm.openSftpDrawer(server.id)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.isConnected } }
            }

            // 1. Oversized log tails cleanly with isTruncatedFromHead = true, canEdit = false
            vm.sftpOpenFile("/var/log/syslog", preferEditMode = false)
            val bigLogState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.remotePath == "/var/log/syslog" }
                }
            }
            val logFile = bigLogState.sftpDrawer.openFile!!
            assertTrue(logFile.isTruncatedFromHead)
            assertFalse(logFile.canEdit)
            assertFalse(logFile.isEditMode)
            assertTrue(logFile.originalText.contains("TAIL_MARKER"))
            assertFalse(logFile.originalText.contains("HEAD_MARKER"))

            vm.sftpEnterEditMode()
            assertFalse(vm.uiState.value.sftpDrawer.openFile?.isEditMode == true)
            vm.sftpSaveFileWithBak()
            assertNotNull(vm.uiState.value.sftpDrawer.errorMessage)
            assertArrayEquals(bigLogBytes, sftpClient.readRawFileBytes("/var/log/syslog"))

            // 2. Binary file detected safely, canEdit = false, overwrite refused
            vm.sftpOpenFile("/usr/local/bin/tool", preferEditMode = true)
            val binState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.remotePath == "/usr/local/bin/tool" }
                }
            }
            val binFile = binState.sftpDrawer.openFile!!
            assertTrue(binFile.isBinaryDetected)
            assertFalse(binFile.canEdit)
            assertFalse(binFile.isEditMode)

            vm.sftpSaveFileWithBak()
            assertNotNull(vm.uiState.value.sftpDrawer.errorMessage)
            assertArrayEquals(binaryBytes, sftpClient.readRawFileBytes("/usr/local/bin/tool"))
        }
    }

    @Test
    fun `E2E-T2-R6-5 closes SFTP client on dismiss and prevents stale mid-connect from overwriting switched server`() = runTest {
        E2eTestHarness().use { harness ->
            val slowHost = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Slow Host",
                    host = "198.51.100.51",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )
            val fastHost = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Fast Host",
                    host = "198.51.100.52",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )

            val slowClient = E2eSftpClient(homeDirectory = "/slow", uploadChunkDelayMillis = 150L)
            val fastClient = E2eSftpClient(
                homeDirectory = "/fast",
                initialFiles = mapOf("/fast/hello.txt" to "fast".toByteArray()),
            )
            val sftpTransport = E2eSftpTransport(
                connectDelayByServerId = ConcurrentHashMap(mapOf(slowHost.id to 250L)),
                clientFactory = { req ->
                    if (req.serverId == slowHost.id) slowClient else fastClient
                },
            )
            val manager = harness.createSshSessionManager(sftpTransport = sftpTransport)
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        it.servers.any { s -> s.id == slowHost.id } &&
                            it.servers.any { s -> s.id == fastHost.id }
                    }
                }
            }

            // Start connecting to slowHost (with deterministic per-server delay) then immediately switch to fastHost
            vm.openSftpDrawer(slowHost.id)
            vm.openSftpDrawer(fastHost.id)

            val switched = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        it.sftpDrawer.isConnected &&
                            !it.sftpDrawer.isConnecting &&
                            it.sftpDrawer.serverId == fastHost.id
                    }
                }
            }
            assertEquals(fastHost.id, switched.sftpDrawer.serverId)
            assertEquals("/fast", switched.sftpDrawer.currentPath)
            assertTrue(switched.sftpDrawer.entries.any { it.name == "hello.txt" })

            // Trigger slow upload and dismiss drawer -> stream closed & client closed cleanly
            val streamClosed = AtomicBoolean(false)
            val blockingStream = object : InputStream() {
                private var count = 0
                override fun read(): Int {
                    if (count++ >= 10) return -1
                    return 'a'.code
                }

                override fun close() {
                    streamClosed.set(true)
                }
            }
            fastClient.uploadChunkDelayMillis = 150L
            vm.sftpUploadStream("big.bin", blockingStream)
            vm.dismissSftpDrawer()

            val dismissed = vm.uiState.value.sftpDrawer
            assertFalse(dismissed.isOpen)
            assertFalse(dismissed.isConnected)
            assertNull(dismissed.serverId)

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (fastClient.closeCount.get() == 0 || !streamClosed.get()) {
                        kotlinx.coroutines.delay(20L)
                    }
                }
            }
            assertTrue(fastClient.closeCount.get() >= 1)
            assertTrue(streamClosed.get())
        }
    }
}
