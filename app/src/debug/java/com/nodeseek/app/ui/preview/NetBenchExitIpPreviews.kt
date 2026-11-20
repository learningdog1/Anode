package com.nodeseek.app.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientEgressSnapshot
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.DirectBindFailureReason
import com.nodeseek.app.domain.netbench.EgressRouteKind
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.ui.netbench.MobileNetBenchScreen
import com.nodeseek.app.ui.netbench.MobileNetBenchUiState
import com.nodeseek.app.ui.netbench.MobileNetBenchViewModel
import com.nodeseek.app.ui.theme.PulseThemeMode

private const val EXITIP_PREVIEW_SPEC = "spec:width=412dp,height=980dp,dpi=320"

@Preview(device = EXITIP_PREVIEW_SPEC, showBackground = true)
@Composable
fun NetBenchDualExitIpLightPreview() {
    val state = remember {
        val directNet = ClientNetworkInfo(
            carrier = ClientCarrier.CHINA_TELECOM,
            carrierDisplayName = "中国电信",
            ipFamily = IpFamily.IPV4,
            countryCode = "CN",
            regionName = "上海",
            asn = "AS4134",
            maskedIp = "180.168.*.*",
            routeKind = EgressRouteKind.DIRECT,
            transportLabel = "Wi-Fi",
        )
        val proxyNet = ClientNetworkInfo(
            carrier = ClientCarrier.OVERSEAS,
            carrierDisplayName = "海外网络 (日本)",
            ipFamily = IpFamily.IPV4,
            countryCode = "JP",
            regionName = "Tokyo",
            asn = "AS20473",
            maskedIp = "45.76.*.*",
            routeKind = EgressRouteKind.PROXY,
            transportLabel = "VPN 代理",
        )
        MobileNetBenchUiState(
            targetInput = "hk-gia.example.net",
            clientNetwork = directNet,
            clientEgress = ClientEgressSnapshot(
                directNetwork = directNet,
                proxyNetwork = proxyNet,
                proxyDetected = true,
                isVpnActive = true,
                activeTransportLabel = "VPN 代理",
                directTransportLabel = "Wi-Fi",
            ),
            testViaProxy = false,
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        val container = LocalAppContainer.current
        val vm = remember(container) {
            MobileNetBenchViewModel(historyStore = container.netBenchHistoryStore)
        }
        MobileNetBenchScreen(
            onBack = {},
            initialListItemIndex = 0,
            previewUiState = state,
            viewModel = vm,
        )
    }
}

@Preview(device = EXITIP_PREVIEW_SPEC, showBackground = true)
@Composable
fun NetBenchProxyFallbackDarkPreview() {
    val state = remember {
        val fallbackNet = ClientNetworkInfo(
            carrier = ClientCarrier.OVERSEAS,
            carrierDisplayName = "海外网络 (新加坡)",
            ipFamily = IpFamily.IPV4,
            countryCode = "SG",
            regionName = "Singapore",
            asn = "AS13335",
            maskedIp = "104.28.*.*",
            routeKind = EgressRouteKind.PROXY_FALLBACK,
            transportLabel = "VPN 代理",
        )
        MobileNetBenchUiState(
            targetInput = "sg.example.com:443",
            clientNetwork = fallbackNet,
            clientEgress = ClientEgressSnapshot(
                directNetwork = null,
                proxyNetwork = fallbackNet,
                proxyDetected = true,
                isVpnActive = true,
                directBindFailed = true,
                directBindFailureReason = DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED,
                activeTransportLabel = "VPN 代理",
            ),
            testViaProxy = false,
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        val container = LocalAppContainer.current
        val vm = remember(container) {
            MobileNetBenchViewModel(historyStore = container.netBenchHistoryStore)
        }
        MobileNetBenchScreen(
            onBack = {},
            initialListItemIndex = 0,
            previewUiState = state,
            viewModel = vm,
        )
    }
}
