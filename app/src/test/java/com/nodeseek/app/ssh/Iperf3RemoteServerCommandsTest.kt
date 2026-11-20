package com.nodeseek.app.ssh

import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.LinuxDistroFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iperf3RemoteServerCommandsTest {

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
        // 1. Debian / Ubuntu (apt)
        val debianRoot = Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.DEBIAN_UBUNTU, isRoot = true)
        assertTrue(debianRoot.contains("apt-get update -y"))
        assertTrue(debianRoot.contains("apt-get install -y iperf3"))
        assertFalse(debianRoot.contains("sudo"))

        val ubuntuSudo = Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.DEBIAN_UBUNTU, isRoot = false)
        assertTrue(ubuntuSudo.contains("sudo env DEBIAN_FRONTEND=noninteractive apt-get update -y"))
        assertTrue(ubuntuSudo.contains("sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y iperf3"))

        // 2. CentOS / RHEL / Rocky / Alma (dnf or yum + epel-release fallback)
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

        // 3. Fedora (dnf)
        assertEquals(
            "dnf install -y iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.FEDORA, isRoot = true),
        )
        assertEquals(
            "sudo dnf install -y iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.FEDORA, isRoot = false),
        )

        // 4. Alpine (apk)
        assertEquals(
            "apk update && apk add --no-cache iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.ALPINE, isRoot = true),
        )
        assertEquals(
            "sudo apk update && sudo apk add --no-cache iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.ALPINE, isRoot = false),
        )

        // 5. Arch (pacman)
        assertEquals(
            "pacman -Sy --noconfirm --needed iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.ARCH, isRoot = true),
        )
        assertEquals(
            "sudo pacman -Sy --noconfirm --needed iperf3",
            Iperf3RemoteServerCommands.buildInstallCommand(LinuxDistroFamily.ARCH, isRoot = false),
        )

        // 6. openSUSE (zypper)
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
    fun `start install and stop scripts include daemon flags port check firewall hints and custom port`() {
        val startScript = Iperf3RemoteServerCommands.buildCheckAndStartScript(port = 15201, autoOpenFirewall = true)
        assertTrue(startScript.contains("PORT=15201"))
        assertTrue(startScript.contains("iperf3 -s -p"))
        assertTrue(startScript.contains("-D"))
        assertTrue(startScript.contains("nohup iperf3 -s -p"))
        assertTrue(startScript.contains("ufw allow"))
        assertTrue(startScript.contains("firewall-cmd --add-port"))
        assertTrue(startScript.contains("iptables -I INPUT -p tcp --dport"))
        assertTrue(startScript.contains("OPEN_FW=1"))

        val stopScript = Iperf3RemoteServerCommands.buildStopScript(port = 15201)
        assertTrue(stopScript.contains("PORT=15201"))
        assertTrue(stopScript.contains("pkill"))

        val fwHints = Iperf3RemoteServerCommands.buildFirewallHintCommands(port = 15201, isRoot = false)
        assertEquals("sudo ufw allow 15201/tcp", fwHints.ufwCommand)
        assertEquals("sudo firewall-cmd --add-port=15201/tcp", fwHints.firewalldCommand)
        assertEquals("sudo iptables -I INPUT -p tcp --dport 15201 -j ACCEPT", fwHints.iptablesCommand)
    }
}
