package com.nodeseek.app.ssh

import com.nodeseek.app.domain.ssh.Iperf3OwnedInstance
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.Iperf3StartProbeResult
import com.nodeseek.app.domain.ssh.Iperf3StopOutcome
import com.nodeseek.app.domain.ssh.LinuxDistroFamily
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class Iperf3RemoteServerCommandsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val spawnedProcesses = mutableListOf<Process>()

    @After
    fun tearDown() {
        spawnedProcesses.forEach { proc ->
            runCatching {
                if (proc.isAlive) {
                    proc.destroyForcibly()
                    proc.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
        spawnedProcesses.clear()
    }

    @Test
    fun `port validation accepts 1 to 65535 and rejects invalid values`() {
        assertEquals(5201, Iperf3RemoteServerCommands.validatePort("5201"))
        assertEquals(1, Iperf3RemoteServerCommands.validatePort("1"))
        assertEquals(65535, Iperf3RemoteServerCommands.validatePort(" 65535 "))
        assertNull(Iperf3RemoteServerCommands.validatePort("0"))
        assertNull(Iperf3RemoteServerCommands.validatePort("-5201"))
        assertNull(Iperf3RemoteServerCommands.validatePort("65536"))
        assertNull(Iperf3RemoteServerCommands.validatePort("abc"))
        assertNull(Iperf3RemoteServerCommands.validatePort(""))
    }

    @Test
    fun `os-release parser and classifier cover Debian Ubuntu CentOS RHEL Rocky Alma Fedora Alpine Arch openSUSE`() {
        val cases = listOf(
            """
            PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
            ID=debian
            VERSION_ID="12"
            """.trimIndent() to LinuxDistroFamily.DEBIAN_UBUNTU,

            """
            NAME="Ubuntu"
            VERSION_ID="24.04"
            ID=ubuntu
            ID_LIKE=debian
            PRETTY_NAME="Ubuntu 24.04.1 LTS"
            """.trimIndent() to LinuxDistroFamily.DEBIAN_UBUNTU,

            """
            NAME="CentOS Linux"
            VERSION="7 (Core)"
            ID="centos"
            ID_LIKE="rhel fedora"
            PRETTY_NAME="CentOS Linux 7 (Core)"
            """.trimIndent() to LinuxDistroFamily.CENTOS_RHEL,

            """
            NAME="Red Hat Enterprise Linux"
            ID="rhel"
            ID_LIKE="fedora"
            VERSION_ID="9.4"
            """.trimIndent() to LinuxDistroFamily.CENTOS_RHEL,

            """
            NAME="Rocky Linux"
            ID="rocky"
            ID_LIKE="rhel centos fedora"
            PRETTY_NAME="Rocky Linux 9.4 (Blue Onyx)"
            """.trimIndent() to LinuxDistroFamily.CENTOS_RHEL,

            """
            NAME="AlmaLinux"
            ID="almalinux"
            ID_LIKE="rhel centos fedora"
            PRETTY_NAME="AlmaLinux 9.4 (Seafoam Ocelot)"
            """.trimIndent() to LinuxDistroFamily.CENTOS_RHEL,

            """
            NAME="Fedora Linux"
            VERSION_ID="41"
            ID=fedora
            PRETTY_NAME="Fedora Linux 41 (Server Edition)"
            """.trimIndent() to LinuxDistroFamily.FEDORA,

            """
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=3.20.3
            PRETTY_NAME="Alpine Linux v3.20"
            """.trimIndent() to LinuxDistroFamily.ALPINE,

            """
            NAME="Arch Linux"
            PRETTY_NAME="Arch Linux"
            ID=arch
            BUILD_ID=rolling
            """.trimIndent() to LinuxDistroFamily.ARCH,

            """
            NAME="openSUSE Leap"
            ID="opensuse-leap"
            ID_LIKE="suse opensuse"
            VERSION_ID="15.6"
            """.trimIndent() to LinuxDistroFamily.OPENSUSE,

            """
            NAME="openSUSE Tumbleweed"
            ID="opensuse-tumbleweed"
            ID_LIKE="opensuse suse"
            """.trimIndent() to LinuxDistroFamily.OPENSUSE,
        )

        for ((osReleaseText, expectedFamily) in cases) {
            val parsed = Iperf3RemoteServerCommands.parseOsRelease(osReleaseText)
            val actualFamily = Iperf3RemoteServerCommands.classifyDistro(parsed)
            assertEquals("Unexpected distro classification for:\n$osReleaseText", expectedFamily, actualFamily)
        }
    }

    @Test
    fun `buildInstallCommand generates correct package manager commands for root and sudo across all distros`() {
        val debianRoot = Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.DEBIAN_UBUNTU, isRoot = true)
        assertTrue(debianRoot.contains("apt-get update -y"))
        assertTrue(debianRoot.contains("apt-get install -y iperf3"))
        assertFalse(debianRoot.contains("sudo"))

        val ubuntuSudo = Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.DEBIAN_UBUNTU, isRoot = false)
        assertTrue(ubuntuSudo.contains("sudo env DEBIAN_FRONTEND=noninteractive apt-get update -y"))
        assertTrue(ubuntuSudo.contains("sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y iperf3"))

        val rockyDnfRoot = Iperf3RemoteServerCommands.buildInstallCommand(
            LinuxDistroFamily.CENTOS_RHEL,
            isRoot = true,
            hasDnf = true,
        )
        assertEquals(
            "dnf install -y iperf3 || (dnf install -y epel-release && dnf install -y iperf3)",
            rockyDnfRoot,
        )

        val centos7YumSudo = Iperf3RemoteServerCommands.buildInstallCommand(
            LinuxDistroFamily.CENTOS_RHEL,
            isRoot = false,
            hasDnf = false,
        )
        assertEquals(
            "sudo yum install -y iperf3 || (sudo yum install -y epel-release && sudo yum install -y iperf3)",
            centos7YumSudo,
        )

        assertEquals(
            "dnf install -y iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.FEDORA, isRoot = true),
        )
        assertEquals(
            "sudo dnf install -y iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.FEDORA, isRoot = false),
        )

        assertEquals(
            "apk update && apk add --no-cache iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.ALPINE, isRoot = true),
        )
        assertEquals(
            "sudo apk update && sudo apk add --no-cache iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.ALPINE, isRoot = false),
        )

        assertEquals(
            "pacman -Sy --noconfirm --needed iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.ARCH, isRoot = true),
        )
        assertEquals(
            "sudo pacman -Sy --noconfirm --needed iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.ARCH, isRoot = false),
        )

        assertEquals(
            "zypper --non-interactive install -y iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.OPENSUSE, isRoot = true),
        )
        assertEquals(
            "sudo zypper --non-interactive install -y iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.OPENSUSE, isRoot = false),
        )
    }

    @Test
    fun `parseMissingProbeOutput extracts OS info privilege and distro install command`() {
        val sampleOutput = """
            ⚠ 未检测到 iperf3 命令（系统：AlmaLinux 9.4 (Seafoam Ocelot)）
            __ANODE_IPERF3_MISSING__|id=almalinux|id_like=rhel centos fedora|pretty=AlmaLinux 9.4 (Seafoam Ocelot)|is_root=0|has_dnf=1|has_sudo=1
        """.trimIndent()

        val parsed = Iperf3RemoteServerCommands.parseMissingProbeOutput(sampleOutput)
        assertNotNull(parsed)
        assertEquals(LinuxDistroFamily.CENTOS_RHEL, parsed!!.distroFamily)
        assertEquals("AlmaLinux 9.4 (Seafoam Ocelot)", parsed.osRelease.displayTitle)
        assertFalse(parsed.isRoot)
        assertTrue(parsed.hasDnf)
        assertTrue(parsed.hasSudo)
        assertTrue(parsed.installCommand.contains("sudo dnf install -y iperf3"))
        assertTrue(parsed.installCommand.contains("epel-release"))
    }

    @Test
    fun `start install and stop scripts always pass explicit port including 5201 and never use broad pkill killall or pgrep`() {
        for (port in listOf(Iperf3RemoteServerCommands.DEFAULT_PORT, 15201)) {
            val startScript = Iperf3RemoteServerCommands.buildCheckAndStartScript(
                port = port,
                autoOpenFirewall = true,
            )
            assertTrue(startScript.contains("PORT=$port"))
            assertTrue(startScript.contains("""iperf3 -s -p "${'$'}PORT" -D -I"""))
            assertTrue(startScript.contains("""nohup iperf3 -s -p "${'$'}PORT""""))
            assertTrue(startScript.contains("ufw allow"))
            assertTrue(startScript.contains("firewall-cmd --add-port"))
            assertTrue(startScript.contains("iptables -I INPUT -p tcp --dport"))
            assertTrue(startScript.contains("OPEN_FW=1"))
            assertFalse("Start script must never use pkill", startScript.contains("pkill"))
            assertFalse("Start script must never use killall", startScript.contains("killall"))
            assertFalse("Start script must never use pgrep", startScript.contains("pgrep"))

            val installAndStartScript = Iperf3RemoteServerCommands.buildInstallAndStartScript(
                distro = LinuxDistroFamily.DEBIAN_UBUNTU,
                isRoot = true,
                port = port,
                autoOpenFirewall = false,
            )
            assertTrue(installAndStartScript.contains("PORT=$port"))
            assertTrue(installAndStartScript.contains("""iperf3 -s -p "${'$'}PORT" -D -I"""))
            assertFalse(installAndStartScript.contains("pkill"))
            assertFalse(installAndStartScript.contains("killall"))
            assertFalse(installAndStartScript.contains("pgrep"))

            val stopScriptFromMeta = Iperf3RemoteServerCommands.buildStopScript(port = port)
            assertTrue(stopScriptFromMeta.contains("PORT=$port"))
            assertFalse("Stop script must never use pkill", stopScriptFromMeta.contains("pkill"))
            assertFalse("Stop script must never use killall", stopScriptFromMeta.contains("killall"))
            assertFalse("Stop script must never use pgrep", stopScriptFromMeta.contains("pgrep"))
            assertFalse(
                "Default port 5201 must never match portless 'iperf3 -s'",
                stopScriptFromMeta.contains("iperf3[[:space:]]+-s"),
            )

            val stopScriptWithIdentity = Iperf3RemoteServerCommands.buildStopScript(
                port = port,
                expectedPid = 24680L,
                expectedStartTime = "987654321",
            )
            assertTrue(stopScriptWithIdentity.contains("PORT=$port"))
            assertTrue(stopScriptWithIdentity.contains("""CLIENT_PID="24680""""))
            assertTrue(stopScriptWithIdentity.contains("CLIENT_START='987654321'"))
            assertFalse(stopScriptWithIdentity.contains("pkill"))
            assertFalse(stopScriptWithIdentity.contains("killall"))
            assertFalse(stopScriptWithIdentity.contains("pgrep"))
        }

        val fwHints = Iperf3RemoteServerCommands.buildFirewallHintCommands(port = 15201, isRoot = false)
        assertEquals("sudo ufw allow 15201/tcp", fwHints.ufwCommand)
        assertEquals("sudo firewall-cmd --add-port=15201/tcp", fwHints.firewalldCommand)
        assertEquals("sudo iptables -I INPUT -p tcp --dport 15201 -j ACCEPT", fwHints.iptablesCommand)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildStopScript rejects pid without startTime`() {
        Iperf3RemoteServerCommands.buildStopScript(port = 5201, expectedPid = 1234L, expectedStartTime = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildStopScript rejects startTime without pid`() {
        Iperf3RemoteServerCommands.buildStopScript(port = 5201, expectedPid = null, expectedStartTime = "123456")
    }

    @Test
    fun `shell execution on default port 5201 records pid start_time port and stops ONLY app-owned PID leaving other iperf3 tasks alive`() {
        val harness = createMockRemoteHarness()

        // Spawn 3 unrelated iperf3 processes on the same host BEFORE starting our port 5201 server:
        // 1) An external portless `iperf3 -s` (e.g. started manually without -p)
        val bystanderPortlessServer = harness.spawnMockProcess(
            cmdlineTokens = listOf("iperf3", "-s"),
            startTimeTicks = "500001",
            listenPort = null,
        )
        // 2) Another `iperf3 -s -p 5202` server on a different port
        val bystanderPort5202Server = harness.spawnMockProcess(
            cmdlineTokens = listOf("iperf3", "-s", "-p", "5202", "-D"),
            startTimeTicks = "500002",
            listenPort = 5202,
        )
        // 3) A running YABS / benchmark client `iperf3 -c 203.0.113.9 -p 5201`
        val bystanderBenchmarkClient = harness.spawnMockProcess(
            cmdlineTokens = listOf("iperf3", "-c", "203.0.113.9", "-p", "5201", "-P", "8"),
            startTimeTicks = "500003",
            listenPort = null,
        )

        // Start app-owned iperf3 on default port 5201
        val startScript = Iperf3RemoteServerCommands.buildCheckAndStartScript(port = 5201)
        val startRun = harness.runShell(startScript)
        assertEquals("Start script failed:\n${startRun.output}", 0, startRun.exitCode)

        val startParsed = Iperf3RemoteServerCommands.parseStartResultOutput(startRun.output, fallbackPort = 5201)
        assertTrue("Expected StartedByApp but got $startParsed\nOutput:\n${startRun.output}", startParsed is Iperf3StartProbeResult.StartedByApp)
        val owned: Iperf3OwnedInstance = (startParsed as Iperf3StartProbeResult.StartedByApp).instance
        assertEquals(5201, owned.port)
        assertEquals("7775201", owned.startTime)

        // Verify mock iperf3 received explicit `-s -p 5201` arguments and metadata file was persisted
        val loggedArgs = harness.lastIperf3ArgsFile.readText().trim()
        assertTrue("Expected explicit '-s -p 5201' in launched args: $loggedArgs", loggedArgs.contains("-s -p 5201"))
        val metaFile = File(harness.stateDir, "anode_iperf3_5201.meta")
        assertTrue("Expected metadata file to exist", metaFile.isFile)
        val metaText = metaFile.readText()
        assertTrue(metaText.contains("pid=${owned.pid}\n"))
        assertTrue(metaText.contains("start_time=7775201\n"))
        assertTrue(metaText.contains("port=5201\n"))

        // Re-running start while owned daemon is still alive reports AlreadyOwnedByApp with same PID + start_time
        val secondStartRun = harness.runShell(startScript)
        assertEquals(0, secondStartRun.exitCode)
        val secondParsed = Iperf3RemoteServerCommands.parseStartResultOutput(secondStartRun.output, fallbackPort = 5201)
        assertEquals(Iperf3StartProbeResult.AlreadyOwnedByApp(owned), secondParsed)

        // Stop port 5201 using recorded (pid, start_time, port)
        val stopScript = Iperf3RemoteServerCommands.buildStopScript(
            port = 5201,
            expectedPid = owned.pid,
            expectedStartTime = owned.startTime,
        )
        val stopRun = harness.runShell(stopScript)
        assertEquals("Stop script failed:\n${stopRun.output}", 0, stopRun.exitCode)

        val stopParsed = Iperf3RemoteServerCommands.parseStopResultOutput(stopRun.output, fallbackPort = 5201)
        assertNotNull(stopParsed)
        assertEquals(Iperf3StopOutcome.STOPPED, stopParsed!!.outcome)
        assertEquals(owned.pid, stopParsed.pid)
        assertFalse("App-owned daemon PID ${owned.pid} must be terminated", harness.isPidAlive(owned.pid))
        assertFalse("Metadata file should be cleaned up on stop", metaFile.exists())

        // Every unrelated iperf3 server/client on the VPS MUST still be alive!
        assertTrue("Portless external iperf3 -s must remain untouched", bystanderPortlessServer.isAlive())
        assertTrue("Port 5202 iperf3 server must remain untouched", bystanderPort5202Server.isAlive())
        assertTrue("Benchmark iperf3 -c client must remain untouched", bystanderBenchmarkClient.isAlive())
    }

    @Test
    fun `stop script refuses to kill when PID was recycled with different start_time even if cmdline and port match`() {
        val harness = createMockRemoteHarness()

        // Simulate a new process that reused PID `recycledProc.pid`, even running `iperf3 -s -p 5201`,
        // but with /proc/<pid>/stat field 22 starttime = 99999999 instead of recorded 11111111.
        val recycledProc = harness.spawnMockProcess(
            cmdlineTokens = listOf("/usr/bin/iperf3", "-s", "-p", "5201", "-D"),
            startTimeTicks = "99999999",
            listenPort = 5201,
            commName = "iperf3 (wrk)",
        )
        val recycledPid = recycledProc.pid
        val metaFile = File(harness.stateDir, "anode_iperf3_5201.meta")
        metaFile.writeText("pid=$recycledPid\nstart_time=11111111\nport=5201\n")

        val stopScript = Iperf3RemoteServerCommands.buildStopScript(
            port = 5201,
            expectedPid = recycledPid,
            expectedStartTime = "11111111",
        )
        val stopRun = harness.runShell(stopScript)
        assertEquals(0, stopRun.exitCode)

        val stopParsed = Iperf3RemoteServerCommands.parseStopResultOutput(stopRun.output, fallbackPort = 5201)
        assertNotNull(stopParsed)
        assertEquals(Iperf3StopOutcome.STALE_PID_MISMATCH, stopParsed!!.outcome)
        assertTrue("Recycled PID must NEVER be killed when start_time mismatches", recycledProc.isAlive())
        assertFalse("Stale metadata file should be cleaned up", metaFile.exists())
    }

    @Test
    fun `stop script refuses to kill when PID belongs to different port or client command or portless server`() {
        val harness = createMockRemoteHarness()

        // Case 1: Same PID & start_time, but running on port 5202 instead of 5201
        val wrongPortProc = harness.spawnMockProcess(
            cmdlineTokens = listOf("iperf3", "-s", "-p", "5202"),
            startTimeTicks = "222222",
            listenPort = 5202,
        )
        val wrongPortPid = wrongPortProc.pid
        File(harness.stateDir, "anode_iperf3_5201.meta")
            .writeText("pid=$wrongPortPid\nstart_time=222222\nport=5201\n")

        val wrongPortStop = harness.runShell(
            Iperf3RemoteServerCommands.buildStopScript(
                port = 5201,
                expectedPid = wrongPortPid,
                expectedStartTime = "222222",
            ),
        )
        assertEquals(0, wrongPortStop.exitCode)
        assertEquals(
            Iperf3StopOutcome.TARGET_MISMATCH,
            Iperf3RemoteServerCommands.parseStopResultOutput(wrongPortStop.output)?.outcome,
        )
        assertTrue("Process on port 5202 must not be killed", wrongPortProc.isAlive())

        // Case 2: Same PID & start_time, listening on 5201, but portless `iperf3 -s` (not started with explicit -p 5201)
        val portlessProc = harness.spawnMockProcess(
            cmdlineTokens = listOf("iperf3", "-s"),
            startTimeTicks = "333333",
            listenPort = 5201,
        )
        val portlessPid = portlessProc.pid
        File(harness.stateDir, "anode_iperf3_5201.meta")
            .writeText("pid=$portlessPid\nstart_time=333333\nport=5201\n")

        val portlessStop = harness.runShell(
            Iperf3RemoteServerCommands.buildStopScript(
                port = 5201,
                expectedPid = portlessPid,
                expectedStartTime = "333333",
            ),
        )
        assertEquals(0, portlessStop.exitCode)
        assertEquals(
            Iperf3StopOutcome.TARGET_MISMATCH,
            Iperf3RemoteServerCommands.parseStopResultOutput(portlessStop.output)?.outcome,
        )
        assertTrue("Portless iperf3 -s must not be killed", portlessProc.isAlive())

        // Case 3: Same PID & start_time, but `iperf3 -c` benchmark client
        val clientProc = harness.spawnMockProcess(
            cmdlineTokens = listOf("iperf3", "-c", "198.51.100.1", "-p", "5201"),
            startTimeTicks = "444444",
            listenPort = null,
        )
        val clientPid = clientProc.pid
        File(harness.stateDir, "anode_iperf3_5201.meta")
            .writeText("pid=$clientPid\nstart_time=444444\nport=5201\n")

        val clientStop = harness.runShell(
            Iperf3RemoteServerCommands.buildStopScript(
                port = 5201,
                expectedPid = clientPid,
                expectedStartTime = "444444",
            ),
        )
        assertEquals(0, clientStop.exitCode)
        assertEquals(
            Iperf3StopOutcome.TARGET_MISMATCH,
            Iperf3RemoteServerCommands.parseStopResultOutput(clientStop.output)?.outcome,
        )
        assertTrue("iperf3 -c benchmark client must not be killed", clientProc.isAlive())
    }

    @Test
    fun `existing external iperf3 listener is reported as external_reused without claiming metadata and never killed by stop script`() {
        val harness = createMockRemoteHarness()

        val externalServer = harness.spawnMockProcess(
            cmdlineTokens = listOf("/usr/bin/iperf3", "-s", "-p", "5201"),
            startTimeTicks = "654321",
            listenPort = 5201,
        )

        // 1. Start script detects existing external iperf3 on 5201 -> emits external_reused, writes NO metadata file
        val startRun = harness.runShell(Iperf3RemoteServerCommands.buildCheckAndStartScript(port = 5201))
        assertEquals(0, startRun.exitCode)
        val startParsed = Iperf3RemoteServerCommands.parseStartResultOutput(startRun.output, fallbackPort = 5201)
        assertEquals(Iperf3StartProbeResult.ReusedExternalService(port = 5201), startParsed)
        assertFalse(
            "External service must never write an app-owned metadata file",
            File(harness.stateDir, "anode_iperf3_5201.meta").exists(),
        )

        // 2. Even if buildStopScript(port = 5201) is executed on the host, external server is untouched
        val stopRun = harness.runShell(Iperf3RemoteServerCommands.buildStopScript(port = 5201))
        assertEquals(0, stopRun.exitCode)
        val stopParsed = Iperf3RemoteServerCommands.parseStopResultOutput(stopRun.output, fallbackPort = 5201)
        assertNotNull(stopParsed)
        assertEquals(Iperf3StopOutcome.EXTERNAL_UNTOUCHED, stopParsed!!.outcome)
        assertTrue("External iperf3 server must NEVER be killed", externalServer.isAlive())
    }

    @Test
    fun `remote metadata file alone stops verified app-owned instance after app restart and rejects corrupted port metadata`() {
        val harness = createMockRemoteHarness()

        val startRun = harness.runShell(Iperf3RemoteServerCommands.buildCheckAndStartScript(port = 15201))
        assertEquals(0, startRun.exitCode)
        val started = (Iperf3RemoteServerCommands.parseStartResultOutput(startRun.output, fallbackPort = 15201) as Iperf3StartProbeResult.StartedByApp).instance
        assertTrue(harness.isPidAlive(started.pid))

        // Simulate corrupted/tampered port inside metadata file -> stop refuses to kill
        val metaFile = File(harness.stateDir, "anode_iperf3_15201.meta")
        metaFile.writeText("pid=${started.pid}\nstart_time=${started.startTime}\nport=5201\n")
        val mismatchStop = harness.runShell(Iperf3RemoteServerCommands.buildStopScript(port = 15201))
        assertEquals(0, mismatchStop.exitCode)
        assertEquals(
            Iperf3StopOutcome.METADATA_MISMATCH,
            Iperf3RemoteServerCommands.parseStopResultOutput(mismatchStop.output, fallbackPort = 15201)?.outcome,
        )
        assertTrue("Mismatched port metadata must not kill daemon", harness.isPidAlive(started.pid))

        // Restore valid remote metadata and invoke buildStopScript(port = 15201) without client args (simulating cold app restart)
        metaFile.writeText("pid=${started.pid}\nstart_time=${started.startTime}\nport=15201\n")
        val validStop = harness.runShell(Iperf3RemoteServerCommands.buildStopScript(port = 15201))
        assertEquals(0, validStop.exitCode)
        assertEquals(
            Iperf3StopOutcome.STOPPED,
            Iperf3RemoteServerCommands.parseStopResultOutput(validStop.output, fallbackPort = 15201)?.outcome,
        )
        assertFalse("Verified daemon should be stopped via remote metadata", harness.isPidAlive(started.pid))
    }

    @Test
    fun `nohup launch fallback and real ps lstart fallback record metadata and stop strictly the launched PID`() {
        val root = tempFolder.newFolder("ps_fallback_${System.nanoTime()}")
        val binDir = File(root, "bin").apply { mkdirs() }
        val stateDir = File(root, "state").apply { mkdirs() }
        val socketsFile = File(root, "sockets.txt").apply { writeText("") }

        File(binDir, "ss").apply {
            writeText(
                """
                #!/bin/sh
                printf 'State Recv-Q Send-Q Local Address:Port Peer Address:Port Process\n'
                if [ -f "${socketsFile.absolutePath}" ]; then
                  while IFS= read -r line; do
                    [ -n "${'$'}line" ] || continue
                    pid_val=$(printf '%s\n' "${'$'}line" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p')
                    if [ -z "${'$'}pid_val" ] || kill -0 "${'$'}pid_val" 2>/dev/null; then
                      printf '%s\n' "${'$'}line"
                    fi
                  done < "${socketsFile.absolutePath}"
                fi
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }

        // Mock `iperf3` that rejects `-D` (forcing the `nohup iperf3 -s -p "$PORT" ... &` branch)
        // and runs in the foreground so real OS `ps -o lstart=` and `ps -ww -o args=` inspect it!
        File(binDir, "iperf3").apply {
            writeText(
                """
                #!/bin/sh
                PORT_VAL=""
                prev=""
                for arg in "${'$'}@"; do
                  if [ "${'$'}arg" = "-D" ]; then exit 1; fi
                  if [ "${'$'}prev" = "-p" ]; then PORT_VAL="${'$'}arg"; fi
                  prev="${'$'}arg"
                done
                printf 'LISTEN 0 128 0.0.0.0:%s 0.0.0.0:* users:(("iperf3",pid=%s,fd=3))\n' "${'$'}PORT_VAL" "${'$'}${'$'}" >> "${socketsFile.absolutePath}"
                trap 'exit 0' TERM INT
                while :; do sleep 1; done
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }

        fun runNativeShell(cmd: String): ShellResult {
            val pb = ProcessBuilder("/bin/sh", "-c", cmd)
            val env = pb.environment()
            env["PATH"] = "${binDir.absolutePath}:${env["PATH"].orEmpty()}"
            env["ANODE_STATE_DIR"] = stateDir.absolutePath
            // Intentionally leave ANODE_PROC_ROOT unset ("/proc") to exercise real host /proc or ps lstart= fallback!
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertTrue("Shell timed out:\n$out", proc.waitFor(10, TimeUnit.SECONDS))
            return ShellResult(proc.exitValue(), out)
        }

        val startRes = runNativeShell(Iperf3RemoteServerCommands.buildCheckAndStartScript(port = 5201))
        assertEquals("nohup start failed:\n${startRes.output}", 0, startRes.exitCode)
        val parsedStart = Iperf3RemoteServerCommands.parseStartResultOutput(startRes.output, fallbackPort = 5201)
        assertTrue("Expected StartedByApp, got $parsedStart\n${startRes.output}", parsedStart is Iperf3StartProbeResult.StartedByApp)
        val instance = (parsedStart as Iperf3StartProbeResult.StartedByApp).instance

        try {
            assertEquals(5201, instance.port)
            assertTrue(instance.startTime.isNotBlank())

            // Mismatched startTime must refuse to kill
            val mismatchRes = runNativeShell(
                Iperf3RemoteServerCommands.buildStopScript(
                    port = 5201,
                    expectedPid = instance.pid,
                    expectedStartTime = "${instance.startTime}_different",
                ),
            )
            // Note: because meta file on disk still has the real startTime while CLIENT_START differs,
            // buildStopScript catches the metadata mismatch before signaling anything!
            assertEquals(
                Iperf3StopOutcome.METADATA_MISMATCH,
                Iperf3RemoteServerCommands.parseStopResultOutput(mismatchRes.output, fallbackPort = 5201)?.outcome,
            )

            // Matching (pid, startTime, port) stops it cleanly
            val stopRes = runNativeShell(
                Iperf3RemoteServerCommands.buildStopScript(
                    port = 5201,
                    expectedPid = instance.pid,
                    expectedStartTime = instance.startTime,
                ),
            )
            assertEquals("Stop failed:\n${stopRes.output}", 0, stopRes.exitCode)
            assertEquals(
                Iperf3StopOutcome.STOPPED,
                Iperf3RemoteServerCommands.parseStopResultOutput(stopRes.output, fallbackPort = 5201)?.outcome,
            )
        } finally {
            ProcessBuilder("/bin/sh", "-c", "kill -9 ${instance.pid} 2>/dev/null || true").start().waitFor(2, TimeUnit.SECONDS)
        }
    }

    private data class ShellResult(
        val exitCode: Int,
        val output: String,
    )

    private data class SpawnedMockProcess(
        val pid: Long,
        val process: Process,
    ) {
        fun isAlive(): Boolean = process.isAlive
    }

    private inner class MockRemoteHarness(
        val rootDir: File,
        val binDir: File,
        val procDir: File,
        val stateDir: File,
        val socketsFile: File,
        val lastIperf3ArgsFile: File,
    ) {
        fun spawnMockProcess(
            cmdlineTokens: List<String>,
            startTimeTicks: String,
            listenPort: Int?,
            commName: String = "iperf3",
        ): SpawnedMockProcess {
            val sleeperScript = File(rootDir, "proc_sleeper_${System.nanoTime()}.sh").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"$$\"\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n")
                setExecutable(true)
            }
            val process = ProcessBuilder("/bin/sh", sleeperScript.absolutePath).start()
            spawnedProcesses += process
            val pidLine = process.inputStream.bufferedReader(Charsets.UTF_8).readLine()?.trim()
                ?: throw AssertionError("Failed to read mock process PID")
            val pid = pidLine.toLong()
            writeFakeProcEntry(
                pid = pid,
                commName = commName,
                cmdlineTokens = cmdlineTokens,
                startTimeTicks = startTimeTicks,
            )
            if (listenPort != null) {
                socketsFile.appendText("LISTEN 0 128 0.0.0.0:$listenPort 0.0.0.0:* users:((\"iperf3\",pid=$pid,fd=3))\n")
            }
            return SpawnedMockProcess(pid = pid, process = process)
        }

        fun writeFakeProcEntry(
            pid: Long,
            commName: String,
            cmdlineTokens: List<String>,
            startTimeTicks: String,
        ) {
            val pidDir = File(procDir, pid.toString()).apply { mkdirs() }
            // /proc/<pid>/stat: pid (comm) state(3) ppid(4) ... itrealvalue(21) starttime(22) vsize(23) rss(24)
            // Fields 3..21 = 19 fields ('S' followed by eighteen zeros), then field 22 = startTimeTicks.
            val fields3To21 = (listOf("S") + List(18) { "0" }).joinToString(" ")
            File(pidDir, "stat").writeText("$pid ($commName) $fields3To21 $startTimeTicks 0 0\n")
            val cmdlineBytes = cmdlineTokens.joinToString("\u0000", postfix = "\u0000").toByteArray(Charsets.UTF_8)
            File(pidDir, "cmdline").writeBytes(cmdlineBytes)
        }

        fun isPidAlive(pid: Long): Boolean {
            val check = ProcessBuilder("/bin/sh", "-c", "kill -0 $pid 2>/dev/null").start()
            check.waitFor(3, TimeUnit.SECONDS)
            return check.exitValue() == 0
        }

        fun runShell(command: String): ShellResult {
            val pb = ProcessBuilder("/bin/sh", "-c", command)
            val env = pb.environment()
            env["PATH"] = "${binDir.absolutePath}:${env["PATH"].orEmpty()}"
            env["ANODE_PROC_ROOT"] = procDir.absolutePath
            env["ANODE_STATE_DIR"] = stateDir.absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                throw AssertionError("Shell command timed out:\n$output")
            }
            return ShellResult(exitCode = proc.exitValue(), output = output)
        }
    }

    private fun createMockRemoteHarness(): MockRemoteHarness {
        val root = tempFolder.newFolder("remote_vps_${System.nanoTime()}")
        val binDir = File(root, "bin").apply { mkdirs() }
        val procDir = File(root, "proc").apply { mkdirs() }
        val stateDir = File(root, "state").apply { mkdirs() }
        val socketsFile = File(root, "sockets.txt").apply { writeText("") }
        val lastArgsFile = File(root, "last_iperf3_args.txt").apply { writeText("") }

        // Mock `ss` reading active entries from socketsFile whose owning PID is still alive
        File(binDir, "ss").apply {
            writeText(
                """
                #!/bin/sh
                printf 'State Recv-Q Send-Q Local Address:Port Peer Address:Port Process\n'
                if [ -f "${socketsFile.absolutePath}" ]; then
                  while IFS= read -r line; do
                    [ -n "${'$'}line" ] || continue
                    pid_val=$(printf '%s\n' "${'$'}line" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p')
                    if [ -z "${'$'}pid_val" ] || kill -0 "${'$'}pid_val" 2>/dev/null; then
                      printf '%s\n' "${'$'}line"
                    fi
                  done < "${socketsFile.absolutePath}"
                fi
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }

        // Mock `iperf3` supporting `--version` and `-s -p <port> -D -I <pidfile>`
        val daemonLoopScript = File(root, "iperf3_daemon_loop.sh").apply {
            writeText("#!/bin/sh\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n")
            setExecutable(true)
        }
        File(binDir, "iperf3").apply {
            writeText(
                """
                #!/bin/sh
                if [ "${'$'}1" = "--version" ]; then
                  echo "iperf 3.16"
                  exit 0
                fi
                printf '%s\n' "${'$'}*" > "${lastArgsFile.absolutePath}"
                PORT_VAL=""
                PID_OUT=""
                prev=""
                for arg in "${'$'}@"; do
                  if [ "${'$'}prev" = "-p" ] || [ "${'$'}prev" = "--port" ]; then PORT_VAL="${'$'}arg"; fi
                  if [ "${'$'}prev" = "-I" ]; then PID_OUT="${'$'}arg"; fi
                  prev="${'$'}arg"
                done
                /bin/sh "${daemonLoopScript.absolutePath}" >/dev/null 2>&1 </dev/null &
                CHILD_PID=${'$'}!
                mkdir -p "${procDir.absolutePath}/${'$'}CHILD_PID"
                printf '%s (iperf3) S 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 777%s 0 0\n' "${'$'}CHILD_PID" "${'$'}{PORT_VAL:-0}" > "${procDir.absolutePath}/${'$'}CHILD_PID/stat"
                {
                  printf 'iperf3\000'
                  for arg in "${'$'}@"; do
                    printf '%s\000' "${'$'}arg"
                  done
                } > "${procDir.absolutePath}/${'$'}CHILD_PID/cmdline"
                if [ -n "${'$'}PORT_VAL" ]; then
                  printf 'LISTEN 0 128 0.0.0.0:%s 0.0.0.0:* users:(("iperf3",pid=%s,fd=3))\n' "${'$'}PORT_VAL" "${'$'}CHILD_PID" >> "${socketsFile.absolutePath}"
                fi
                if [ -n "${'$'}PID_OUT" ]; then
                  printf '%s\n' "${'$'}CHILD_PID" > "${'$'}PID_OUT"
                fi
                exit 0
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }

        return MockRemoteHarness(
            rootDir = root,
            binDir = binDir,
            procDir = procDir,
            stateDir = stateDir,
            socketsFile = socketsFile,
            lastIperf3ArgsFile = lastArgsFile,
        )
    }
}
