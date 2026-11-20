package com.nodeseek.app.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nodeseek.app.data.ssh.JSchSshTransport
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3ErrorKind
import com.nodeseek.app.domain.netbench.Iperf3Progress
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.Iperf3Stage
import com.nodeseek.app.domain.netbench.Iperf3TestMode
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownOptions
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingSummary
import com.nodeseek.app.domain.ssh.Iperf3MissingProbeResult
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.LinuxDistroFamily
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.OsReleaseInfo
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.ui.netbench.MobileNetBenchScreen
import com.nodeseek.app.ui.netbench.MobileNetBenchUiState
import com.nodeseek.app.ui.netbench.MobileNetBenchViewModel
import com.nodeseek.app.ui.netbench.NetBenchIperf3Card
import com.nodeseek.app.ui.netbench.NetBenchIperf3ServerPromptDialog
import com.nodeseek.app.ui.netbench.NetBenchMarkdownReportCard
import com.nodeseek.app.ui.netbench.NetBenchPhase
import com.nodeseek.app.ui.ssh.Iperf3InstallConfirmDialog
import com.nodeseek.app.ui.ssh.Iperf3ServerControlDialog
import com.nodeseek.app.ui.ssh.LiteSshScreen
import com.nodeseek.app.ui.ssh.LiteSshUiState
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import com.nodeseek.app.ui.ssh.PendingIperf3InstallPrompt
import com.nodeseek.app.ui.ssh.SshConnectionPhase
import com.nodeseek.app.ui.theme.PulseThemeMode

private const val PREVIEW_TALL_SPEC = "spec:width=412dp,height=1040dp,dpi=320"
private const val PREVIEW_DIALOG_SPEC = "spec:width=412dp,height=1080dp,dpi=320"

@Preview(device = PREVIEW_TALL_SPEC, showBackground = true)
@Composable
fun Preview01NetBenchFullTableReportLight() {
    PreviewNetBenchFullTableCardFrame(themeMode = PulseThemeMode.PAPER_LIGHT)
}

@Preview(device = PREVIEW_TALL_SPEC, showBackground = true)
@Composable
fun Preview02NetBenchFullTableReportDark() {
    PreviewNetBenchFullTableCardFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK)
}

@Composable
private fun PreviewNetBenchFullTableCardFrame(themeMode: PulseThemeMode) {
    val markdown = remember {
        val target = ResolvedTarget(
            displayHost = "tokyo.example.com",
            resolvedIp = "45.12.34.56",
            ipFamily = IpFamily.IPV4,
            dnsDurationMs = 12.4,
        )
        val report = NetBenchReport(
            targetInput = "tokyo.example.com",
            resolvedTarget = target,
            tcpPort = 443,
            clientNetwork = ClientNetworkInfo(
                carrier = ClientCarrier.CHINA_TELECOM,
                carrierDisplayName = "中国电信",
                ipFamily = IpFamily.IPV4,
                maskedIp = "114.240.*.*",
                rawIp = "114.240.88.123",
            ),
            tcpPingSummary = TcpPingSummary(
                resolvedTarget = target,
                port = 443,
                sent = 10,
                received = 10,
                lossPercent = 0.0,
                minMs = 35.1,
                avgMs = 38.4,
                maxMs = 44.2,
                jitterMdevMs = 2.8,
            ),
            httpLatencyResult = HttpLatencyResult(
                url = "https://tokyo.example.com/",
                statusCode = 200,
                totalMs = 118.6,
                ttfbMs = 82.4,
                tlsHandshakeMs = 39.2,
                protocol = "h2",
                success = true,
            ),
            iperfDownloadResult = Iperf3Result(
                direction = Iperf3Direction.DOWNLOAD,
                port = 5201,
                resolvedTarget = target,
                durationSec = 5.0,
                bytesTransferred = 270_000_000L,
                clientMbps = 432.0,
                peakMbps = 460.5,
                success = true,
            ),
            iperfUploadResult = Iperf3Result(
                direction = Iperf3Direction.UPLOAD,
                port = 5201,
                resolvedTarget = target,
                durationSec = 5.0,
                bytesTransferred = 55_000_000L,
                clientMbps = 88.0,
                peakMbps = 95.2,
                retransmits = 1L,
                success = true,
            ),
            ipIntelligence = IpIntelligenceReport(
                ip = "45.12.34.56",
                ipFamily = IpFamily.IPV4,
                countryCode = "JP",
                countryName = "Japan",
                regionName = "Tokyo",
                cityName = "Tokyo",
                asn = "AS20473",
                orgName = "The Constant Company, LLC dba Vultr Holdings Corporation",
                ispName = "Vultr",
                usageType = IpUsageType.HOSTING_DATACENTER,
                originType = IpOriginType.BROADCAST,
                rirCountryCode = "US",
                rirAuthority = "ARIN",
                originEvidence = "广播 IP (注册地 US -> 播放在 JP · ARIN)",
            ),
        )
        NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.FULL_TABLE,
                maskTargetIp = true,
                includeClientNetwork = true,
                timestampLabel = "2026-11-20 17:30",
            ),
        )
    }

    PreviewPhoneFrame(themeMode = themeMode) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetBenchMarkdownReportCard(
                markdown = markdown,
                selectedStyle = NetBenchMarkdownStyle.FULL_TABLE,
                maskTargetIp = true,
                includeClientNetwork = true,
                onSelectStyle = {},
                onToggleMaskTargetIp = {},
                onToggleIncludeClientNetwork = {},
                onCopyMarkdown = {},
                onInsertMarkdown = {},
                insertActionLabel = "一键发测评帖",
            )
        }
    }
}

@Preview(device = PREVIEW_TALL_SPEC, showBackground = true)
@Composable
fun Preview03LiteSshIperf3EntryAndConsole() {
    val sshState = remember {
        val server = SshServerProfile(
            id = "srv-lax",
            alias = "瓦工 DC6 CN2 GIA",
            host = "198.51.100.88",
            port = 22,
            username = "root",
            authMethod = SshAuthMethod.PRIVATE_KEY,
            knownHostKeyFingerprint = "SHA256:9xK3mP2vL8qR5tW1zB4cN7yF0aD6eH2jU8sV3kM9pQ",
            knownHostKeyAlgorithm = "ssh-ed25519",
        )
        val output = buildString {
            appendLine("\u001B[1;36m▶ 正在后台启动 iperf3 服务端（端口 5201）…\u001B[0m")
            appendLine("\u001B[1;32m✓ iperf3 服务端已在后台守护运行（TCP/UDP 端口 5201）\u001B[0m")
            appendLine("\u001B[1;33m⚠ 检测到 ufw 防火墙已启用，若手机端连不上请放行：ufw allow 5201/tcp\u001B[0m")
            appendLine("\u001B[0;37mℹ 若 VPS 位于阿里云/腾讯云/AWS/Oracle 等平台，请同时确认云控制台「安全组」已放行 TCP 5201 端口。\u001B[0m")
        }
        LiteSshUiState(
            servers = listOf(server),
            selectedServerId = server.id,
            selectedPresetId = SshPresetCatalog.PRESET_QUICK_CHECK,
            connectionPhase = SshConnectionPhase.COMPLETED,
            activeServerAlias = server.alias,
            activeServerHost = server.host,
            activePresetName = "开启 iperf3 服务端 (5201)",
            liveAnsiOutput = output,
            elapsedMillis = 1_400L,
            exitCode = 0,
            iperf3ServerPortText = "5201",
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        val container = LocalAppContainer.current
        val vm = remember(container) {
            LiteSshViewModel(
                vaultRepository = container.sshVaultRepository,
                executor = LiteSshExecutor(JSchSshTransport()),
                enableElapsedTicker = false,
            )
        }
        LiteSshScreen(
            onBack = {},
            onPublishToComposer = { _, _ -> },
            previewUiState = sshState,
            viewModel = vm,
        )
    }
}

@Preview(device = PREVIEW_TALL_SPEC, showBackground = true)
@Composable
fun Preview04MobileNetBenchIperf3ConfigAndError() {
    val state = remember {
        val failedDl = Iperf3Progress(
            direction = Iperf3Direction.DOWNLOAD,
            stage = Iperf3Stage.FAILED,
            elapsedSec = 0.0,
            targetDurationSec = 5,
            bytesTransferred = 0L,
            instantMbps = 0.0,
            averageMbps = 0.0,
            peakMbps = 0.0,
            errorKind = Iperf3ErrorKind.CONNECTION_REFUSED,
            errorMessage = "无法连接 5201 端口（连接被拒绝）：对应 VPS 尚未开启 iperf3 服务端或监听端口不一致，请先开启 iperf3 -s -p 5201",
        )
        MobileNetBenchUiState(
            targetInput = "tokyo.example.com",
            tcpPortText = "443",
            enableTcpPing = true,
            enableIpCheck = true,
            enableIperf3 = true,
            iperfPortText = "5201",
            iperfTestMode = Iperf3TestMode.BOTH,
            activePhase = NetBenchPhase.COMPLETED,
            iperfDownloadProgress = failedDl,
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        val container = LocalAppContainer.current
        val vm = remember(container) {
            MobileNetBenchViewModel(historyStore = container.netBenchHistoryStore)
        }
        MobileNetBenchScreen(
            onBack = {},
            onInsertMarkdown = {},
            initialListItemIndex = 1,
            previewUiState = state,
            viewModel = vm,
        )
    }
}

@Preview(device = PREVIEW_DIALOG_SPEC, showBackground = true)
@Composable
fun Preview05Iperf3ServerControlDialog() {
    val server = remember {
        SshServerProfile(
            id = "srv-tyo",
            alias = "DMIT-TYO-Pro",
            host = "198.51.100.24",
            port = 22,
            username = "ubuntu",
            authMethod = SshAuthMethod.PRIVATE_KEY,
        )
    }
    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        Iperf3ServerControlDialog(
            servers = listOf(server),
            selectedServer = server,
            portText = "5201",
            autoOpenFirewall = true,
            isRunning = false,
            onSelectServer = {},
            onAddServer = {},
            onPortTextChange = {},
            onToggleAutoOpenFirewall = {},
            onStartServer = {},
            onStopServer = {},
            onCopyCommand = {},
            onDismiss = {},
        )
    }
}

@Preview(device = PREVIEW_DIALOG_SPEC, showBackground = true)
@Composable
fun Preview06Iperf3InstallPromptDialog() {
    val prompt = remember {
        val osRelease = OsReleaseInfo(
            id = "rocky",
            idLike = listOf("rhel", "centos", "fedora"),
            prettyName = "Rocky Linux 9.4 (Blue Onyx)",
            versionId = "9.4",
        )
        PendingIperf3InstallPrompt(
            serverId = "srv-rocky",
            serverAlias = "Rocky9 Node",
            port = 5201,
            autoOpenFirewall = true,
            probe = Iperf3MissingProbeResult(
                osRelease = osRelease,
                distroFamily = LinuxDistroFamily.CENTOS_RHEL,
                isRoot = false,
                hasSudo = true,
                hasDnf = true,
                installCommand = Iperf3RemoteServerCommands.buildInstallCommand(
                    distro = LinuxDistroFamily.CENTOS_RHEL,
                    isRoot = false,
                    hasDnf = true,
                ),
            ),
        )
    }
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        Iperf3InstallConfirmDialog(
            prompt = prompt,
            onConfirmInstallAndStart = {},
            onDismiss = {},
        )
    }
}

@Preview(device = PREVIEW_DIALOG_SPEC, showBackground = true)
@Composable
fun Preview07NetBenchIperf3PrepDialog() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        NetBenchIperf3ServerPromptDialog(
            targetInput = "tokyo.example.com",
            iperfPortText = "5201",
            onPortTextChange = {},
            onInvokeOneClickSshStart = {},
            onJumpToLiteSsh = {},
            onCopyManualCommand = {},
            onConfirmReady = {},
            onDisableIperf3 = {},
        )
    }
}
