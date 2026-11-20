package com.nodeseek.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.data.ssh.JSchSshTransport
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetPortfolioSummary
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.UpcomingRenewalItem
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3Progress
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.Iperf3Stage
import com.nodeseek.app.domain.netbench.Iperf3TestMode
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownOptions
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingProgress
import com.nodeseek.app.domain.netbench.TcpPingSample
import com.nodeseek.app.domain.netbench.TcpPingSummary
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.domain.restock.RestockEventRecord
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockRuntimeStatus
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.trust.ExposureHitTopic
import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.SellerTrustReport
import com.nodeseek.app.domain.trust.TrustRiskAssessment
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.domain.trust.TrustTag
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.activity.ActivityScreen
import com.nodeseek.app.ui.fleet.MyServersScreen
import com.nodeseek.app.ui.fleet.MyServersTab
import com.nodeseek.app.ui.fleet.MyServersUiState
import com.nodeseek.app.ui.fleet.MyServersViewModel
import com.nodeseek.app.ui.netbench.MobileNetBenchScreen
import com.nodeseek.app.ui.netbench.MobileNetBenchUiState
import com.nodeseek.app.ui.netbench.MobileNetBenchViewModel
import com.nodeseek.app.ui.netbench.NetBenchPhase
import com.nodeseek.app.ui.overseas.OverseasIntelScreen
import com.nodeseek.app.ui.restock.RestockMonitorScreen
import com.nodeseek.app.ui.restock.RestockMonitorUiState
import com.nodeseek.app.ui.ssh.LiteSshScreen
import com.nodeseek.app.ui.ssh.LiteSshUiState
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import com.nodeseek.app.ui.ssh.SshConnectionPhase
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.trust.SellerTrustCardSheetContent
import com.nodeseek.app.ui.trust.SellerTrustUiState
import java.math.BigDecimal

private const val TOOLBOX_PHONE_SPEC = "spec:width=412dp,height=915dp,dpi=320"
private const val TOOLBOX_TALL_SPEC = "spec:width=412dp,height=1080dp,dpi=320"

private const val SHOWCASE_NOW_MILLIS = 1_795_096_200_000L

// ── Feature 2: Activity Hub (ForumWatch Placeholder) ──────────────────────────────────────

@Preview(device = TOOLBOX_PHONE_SPEC, showBackground = true)
@Composable
fun Showcase01ActivityHub() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        val container = LocalAppContainer.current
        ActivityScreen(
            repository = container.activityRepository,
            onBack = {},
        )
    }
}

// ── Feature 1: VPS Restock & Flash-Sale Monitor ──────────────────────────────────────────

@Preview(device = TOOLBOX_PHONE_SPEC, showBackground = true)
@Composable
fun Showcase02RestockMonitor() {
    val watches = remember {
        listOf(
            RestockWatchItem(
                id = "rw-1",
                title = "DMIT LAX.Pro.WEE (CN2 GIA 限量款)",
                rawUrl = "https://www.dmit.io/cart.php?a=add&pid=186",
                resolvedOrderUrl = "https://www.dmit.io/cart.php?a=add&pid=186",
                panelType = MerchantPanelType.WHMCS,
                priceHint = "\$36.90/yr",
                pollIntervalMinutes = 1,
                isEnabled = true,
                stockStatus = RestockStockStatus.IN_STOCK,
                lastCheckedEpochMillis = SHOWCASE_NOW_MILLIS - 22_000L,
                lastRestockedEpochMillis = SHOWCASE_NOW_MILLIS - 90_000L,
                lastEvidenceSummary = "检测到 WHMCS Configure 表单与 Order Now 按钮",
                sourceTopicId = 412088L,
                createdAtEpochMillis = SHOWCASE_NOW_MILLIS - 12L * 3_600_000L,
            ),
            RestockWatchItem(
                id = "rw-2",
                title = "BandwagonHOST MEGA BOX Pro (DC1 CN2 GIA)",
                rawUrl = "https://bwh81.net/cart.php?a=add&pid=158",
                resolvedOrderUrl = "https://bwh81.net/cart.php?a=add&pid=158",
                panelType = MerchantPanelType.WHMCS,
                priceHint = "\$45.68/yr",
                pollIntervalMinutes = 2,
                isEnabled = true,
                stockStatus = RestockStockStatus.OUT_OF_STOCK,
                lastCheckedEpochMillis = SHOWCASE_NOW_MILLIS - 38_000L,
                lastEvidenceSummary = "页面提示 Out of Stock (0 Available)",
                sourceTopicId = 411902L,
                createdAtEpochMillis = SHOWCASE_NOW_MILLIS - 36L * 3_600_000L,
            ),
            RestockWatchItem(
                id = "rw-3",
                title = "ClawCloud HK 4USD 优化月付补货",
                rawUrl = "https://claw.cloud/cart.php?a=add&pid=88",
                resolvedOrderUrl = "https://claw.cloud/cart.php?a=add&pid=88",
                panelType = MerchantPanelType.SOLUSVM,
                priceHint = "\$4.00/mo",
                pollIntervalMinutes = 2,
                isEnabled = true,
                stockStatus = RestockStockStatus.OUT_OF_STOCK,
                lastCheckedEpochMillis = SHOWCASE_NOW_MILLIS - 54_000L,
                lastEvidenceSummary = "命中缺货信号：售罄 / Sold Out",
                createdAtEpochMillis = SHOWCASE_NOW_MILLIS - 48L * 3_600_000L,
            ),
        )
    }

    val events = remember {
        listOf(
            RestockEventRecord(
                id = "ev-1",
                watchItemId = "rw-1",
                watchTitle = "DMIT LAX.Pro.WEE (CN2 GIA 限量款)",
                resolvedOrderUrl = "https://www.dmit.io/cart.php?a=add&pid=186",
                panelType = MerchantPanelType.WHMCS,
                priceHint = "\$36.90/yr",
                evidenceSummary = "缺货 → 有货（WHMCS Configure 页面可用）",
                detectedAtEpochMillis = SHOWCASE_NOW_MILLIS - 90_000L,
                isRead = false,
                sourceTopicId = 412088L,
            ),
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        val container = LocalAppContainer.current
        RestockMonitorScreen(
            repository = container.restockRepository,
            monitorController = container.restockMonitorController,
            notificationHelper = container.restockNotificationHelper,
            flareSolverrClient = container.flareSolverrClient,
            onBack = {},
            onOpenTopic = {},
            onImportToServerLedger = {},
            previewUiState = RestockMonitorUiState(
                watchItems = watches,
                eventHistory = events,
                settings = RestockMonitorSettings(
                    isMonitorEnabled = true,
                    defaultPollIntervalMinutes = 2,
                    backgroundIntervalMinutes = 15,
                    urgentNotificationsEnabled = true,
                ),
                runtimeStatus = RestockRuntimeStatus(
                    isRunningInForeground = true,
                    isCheckingNow = false,
                    lastCheckEpochMillis = SHOWCASE_NOW_MILLIS - 22_000L,
                ),
                hasNotificationPermission = true,
                inStockCount = 1,
                outOfStockCount = 2,
                errorCount = 0,
                unreadEventCount = 1,
                quickUrlInput = "https://bwh81.net/cart.php?a=add&pid=162",
            ),
        )
    }
}

// ── Feature 3: Seller Trust & Risk Check Card ────────────────────────────────────────────

@Preview(device = TOOLBOX_PHONE_SPEC, showBackground = true)
@Composable
fun Showcase03SellerTrustCard() {
    val trustUiState = remember {
        val hits = listOf(
            ExposureHitTopic(
                topicId = 409821L,
                title = "【曝光骗子】警惕 fast_vps_reseller 私下收款后不给 Push 失联",
                categoryName = "日常",
                authorName = "victim_ops",
                publishedAtText = "3 天前",
                matchedTerm = "曝光",
                categorySlug = "daily",
            ),
            ExposureHitTopic(
                topicId = 410412L,
                title = "避雷 fast_vps_reseller，原油被找回疑似二道贩子跑路",
                categoryName = "交易",
                authorName = "hk_buyer",
                publishedAtText = "昨天",
                matchedTerm = "骗子",
                categorySlug = "trade",
            ),
        )
        val tradeTopics = listOf(
            TopicSummary(
                id = 412090L,
                title = "[出] 搬瓦工 THE PLAN v2 剩余 260 天急出支持先款优惠",
                categorySlug = "trade",
                categoryName = "交易",
                authorId = 88901L,
                authorName = "fast_vps_reseller",
                replyCount = 14,
                viewCount = 520,
                lastActivityIso = "2026-11-19T12:10:00Z",
            ),
            TopicSummary(
                id = 411203L,
                title = "[出] DMIT HKG.Pro.Tiny 原油明盘 ¥980 急转",
                categorySlug = "trade",
                categoryName = "交易",
                authorId = 88901L,
                authorName = "fast_vps_reseller",
                replyCount = 9,
                viewCount = 410,
                lastActivityIso = "2026-11-16T08:40:00Z",
            ),
        )
        val localNote = LocalSellerNote(
            uid = 88901L,
            username = "fast_vps_reseller",
            tag = TrustTag.RISK_BLACKLIST,
            noteText = "坚持不走官方中介，群友反馈有找回历史，直接拉黑避雷。",
            updatedAtEpochMillis = SHOWCASE_NOW_MILLIS - 300_000L,
        )
        val report = SellerTrustReport(
            uid = 88901L,
            username = "fast_vps_reseller",
            avatarUrl = "",
            rankLevel = 1,
            coins = 145,
            stardust = 0,
            createdAtText = "2026-10-31",
            registrationDays = 19,
            registrationDurationLabel = "19 天 · 新号",
            totalTopicsCount = 5,
            totalCommentsCount = 12,
            historicalTradeTopicCount = 4,
            recentTradeTopics = tradeTopics,
            exposureSearchHits = hits,
            searchSucceeded = true,
            localNote = localNote,
            riskAssessment = TrustRiskAssessment(
                level = TrustRiskLevel.HIGH_RISK_RED,
                headline = "命中 2 条站内曝光/骗子关联帖，请高度警惕！",
                reasons = listOf(
                    "站内检索命中 2 条「曝光 / 骗子」关联帖",
                    "注册仅 19 天且等级为 Lv.1，属于高风险交易新号",
                    "你已将 TA 标记为「高危避雷」",
                ),
            ),
            fetchedAtEpochMillis = SHOWCASE_NOW_MILLIS,
        )
        SellerTrustUiState(
            uid = 88901L,
            usernameHint = "fast_vps_reseller",
            isLoading = false,
            report = report,
            localNote = localNote,
            draftTag = TrustTag.RISK_BLACKLIST,
            draftNoteText = localNote.noteText,
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 42.dp),
                shape = PulseArchitecturalShapes.BottomSheetTop,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Spacer(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                        )
                    }
                    SellerTrustCardSheetContent(
                        uiState = trustUiState,
                        onRefresh = {},
                        onToggleTag = {},
                        onNoteTextChange = {},
                        onSaveNote = {},
                        onOpenTopic = {},
                        onOpenUserProfile = { _, _ -> },
                        onStartDm = { _, _ -> },
                        onDismiss = {},
                    )
                }
            }
        }
    }
}

// ── Feature 4a: My Servers — Nezha / Komari Probe Monitor ───────────────────────────────

@Preview(device = TOOLBOX_PHONE_SPEC, showBackground = true)
@Composable
fun Showcase04MyServersProbes() {
    val panels = remember {
        listOf(
            ProbePanelConfig(
                id = "p-nezha",
                name = "主探针 · Nezha v1",
                baseUrl = "https://status.anode.dev",
                panelType = ProbePanelType.NEZHA_V1,
                resolvedPanelType = ProbePanelType.NEZHA_V1,
                notifyOnOffline = true,
                lastSyncedEpochMillis = SHOWCASE_NOW_MILLIS - 15_000L,
            ),
            ProbePanelConfig(
                id = "p-komari",
                name = "亚太节点 · Komari",
                baseUrl = "https://komari.anode.dev",
                panelType = ProbePanelType.KOMARI,
                resolvedPanelType = ProbePanelType.KOMARI,
                notifyOnOffline = true,
                lastSyncedEpochMillis = SHOWCASE_NOW_MILLIS - 18_000L,
            ),
        )
    }

    val snapshots = remember {
        listOf(
            ProbeServerSnapshot(
                panelId = "p-nezha",
                remoteServerId = "101",
                name = "DMIT-TYO-Pro-Tiny",
                countryCode = "JP",
                os = "Debian 12.7",
                cpuCores = 2,
                cpuPercent = 24.6,
                memUsedBytes = 940L * 1024 * 1024,
                memTotalBytes = 2048L * 1024 * 1024,
                diskUsedBytes = 14L * 1024 * 1024 * 1024,
                diskTotalBytes = 40L * 1024 * 1024 * 1024,
                netInSpeedBps = 18_400_000L,
                netOutSpeedBps = 22_900_000L,
                monthlyTransferInBytes = 215L * 1024 * 1024 * 1024,
                monthlyTransferOutBytes = 248L * 1024 * 1024 * 1024,
                trafficLimitBytes = 1024L * 1024 * 1024 * 1024,
                uptimeSeconds = 42L * 86_400L + 5L * 3600L,
                load1 = 0.34,
                isOnline = true,
                lastActiveEpochMillis = SHOWCASE_NOW_MILLIS - 12_000L,
            ),
            ProbeServerSnapshot(
                panelId = "p-komari",
                remoteServerId = "202",
                name = "ClawCloud-HK-Opt",
                countryCode = "HK",
                os = "Ubuntu 24.04",
                cpuCores = 2,
                cpuPercent = 11.2,
                memUsedBytes = 680L * 1024 * 1024,
                memTotalBytes = 2048L * 1024 * 1024,
                diskUsedBytes = 9L * 1024 * 1024 * 1024,
                diskTotalBytes = 40L * 1024 * 1024 * 1024,
                netInSpeedBps = 6_200_000L,
                netOutSpeedBps = 8_900_000L,
                monthlyTransferInBytes = 140L * 1024 * 1024 * 1024,
                monthlyTransferOutBytes = 162L * 1024 * 1024 * 1024,
                trafficLimitBytes = 1024L * 1024 * 1024 * 1024,
                uptimeSeconds = 19L * 86_400L,
                load1 = 0.18,
                isOnline = true,
                lastActiveEpochMillis = SHOWCASE_NOW_MILLIS - 15_000L,
            ),
            ProbeServerSnapshot(
                panelId = "p-nezha",
                remoteServerId = "105",
                name = "RackNerd-LAX-DC02",
                countryCode = "US",
                os = "Debian 12",
                cpuCores = 2,
                cpuPercent = 0.0,
                memUsedBytes = 0L,
                memTotalBytes = 2560L * 1024 * 1024,
                diskUsedBytes = 12L * 1024 * 1024 * 1024,
                diskTotalBytes = 45L * 1024 * 1024 * 1024,
                uptimeSeconds = 0L,
                isOnline = false,
                lastActiveEpochMillis = SHOWCASE_NOW_MILLIS - 720_000L,
            ),
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        val container = LocalAppContainer.current
        val vm = remember(container) {
            MyServersViewModel(
                fleetRepository = container.fleetRepository,
                exchangeRateRepository = container.exchangeRateRepository,
                workScheduler = null,
                initialTab = MyServersTab.PROBES,
            )
        }
        MyServersScreen(
            onBack = {},
            onCreateSalePost = { _, _ -> },
            fleetRepository = container.fleetRepository,
            exchangeRateRepository = container.exchangeRateRepository,
            fleetWorkScheduler = null,
            viewModel = vm,
            previewUiState = MyServersUiState(
                selectedTab = MyServersTab.PROBES,
                panels = panels,
                snapshots = snapshots,
                filteredSnapshots = snapshots,
                portfolioSummary = FleetPortfolioSummary(
                    totalMachineCount = 4,
                    totalProbeNodeCount = 3,
                    onlineProbeCount = 2,
                    offlineProbeCount = 1,
                    totalMonthlyCostCny = BigDecimal("142.50"),
                    totalAnnualizedCostCny = BigDecimal("1710.00"),
                    dueSoonRenewalCount = 1,
                ),
                monitorSettings = FleetMonitorSettings(),
                lastSyncEpochMillis = SHOWCASE_NOW_MILLIS - 12_000L,
            ),
        )
    }
}

// ── Feature 4b: My Servers — Asset Ledger & One-Click Valuation Sale Post ────────────────

@Preview(device = TOOLBOX_PHONE_SPEC, showBackground = true)
@Composable
fun Showcase05MyServersAssetLedger() {
    val asset1 = remember {
        VpsAssetRecord(
            id = "asset-1",
            nickname = "DMIT 东京 Pro Tiny",
            providerName = "DMIT",
            specsSummary = "2C / 2G / 40G NVMe / 1TB",
            region = "东京",
            routeTag = "CN2 GIA",
            renewalAmount = "149.90",
            currency = ValuationCurrency.USD,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = "2026-11-24",
            linkedProbeKey = "p-nezha:101",
            monthlyTrafficQuotaGb = 1000.0,
            remindDaysBeforeExpiry = 7,
        )
    }
    val asset2 = remember {
        VpsAssetRecord(
            id = "asset-2",
            nickname = "ClawCloud 香港优化年付",
            providerName = "ClawCloud",
            specsSummary = "2C / 2G / 40G NVMe / 1TB",
            region = "香港",
            routeTag = "阿里云 BGP",
            renewalAmount = "18.00",
            currency = ValuationCurrency.USD,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = "2027-08-15",
            linkedProbeKey = "p-komari:202",
            monthlyTrafficQuotaGb = 1000.0,
            remindDaysBeforeExpiry = 7,
        )
    }

    val renewals = remember(asset1, asset2) {
        listOf(
            UpcomingRenewalItem(
                asset = asset1,
                daysRemaining = 5L,
                urgency = AssetExpiryUrgency.CRITICAL,
                renewalCny = BigDecimal("1085.28"),
                monthlyCostCny = BigDecimal("90.44"),
                annualizedCostCny = BigDecimal("1085.28"),
                isWithinReminderWindow = true,
            ),
            UpcomingRenewalItem(
                asset = asset2,
                daysRemaining = 269L,
                urgency = AssetExpiryUrgency.NORMAL,
                renewalCny = BigDecimal("130.32"),
                monthlyCostCny = BigDecimal("10.86"),
                annualizedCostCny = BigDecimal("130.32"),
                isWithinReminderWindow = false,
            ),
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        val container = LocalAppContainer.current
        val vm = remember(container) {
            MyServersViewModel(
                fleetRepository = container.fleetRepository,
                exchangeRateRepository = container.exchangeRateRepository,
                workScheduler = null,
                initialTab = MyServersTab.ASSETS,
            )
        }
        MyServersScreen(
            onBack = {},
            onCreateSalePost = { _, _ -> },
            fleetRepository = container.fleetRepository,
            exchangeRateRepository = container.exchangeRateRepository,
            fleetWorkScheduler = null,
            initialTab = MyServersTab.ASSETS,
            viewModel = vm,
            previewUiState = MyServersUiState(
                selectedTab = MyServersTab.ASSETS,
                assets = listOf(asset1, asset2),
                portfolioSummary = FleetPortfolioSummary(
                    totalMachineCount = 2,
                    totalProbeNodeCount = 2,
                    onlineProbeCount = 2,
                    offlineProbeCount = 0,
                    totalMonthlyCostCny = BigDecimal("101.30"),
                    totalAnnualizedCostCny = BigDecimal("1215.60"),
                    upcomingRenewals = renewals,
                    dueSoonRenewalCount = 1,
                ),
                lastSyncEpochMillis = SHOWCASE_NOW_MILLIS - 12_000L,
            ),
        )
    }
}

// ── Feature 5: Mobile NetBench (TCPing + Native iperf3 + Native/Broadcast IP) ────────────

@Preview(device = TOOLBOX_TALL_SPEC, showBackground = true)
@Composable
fun Showcase06MobileNetBench() {
    val netBenchState = remember {
        val resolved = ResolvedTarget(
            displayHost = "hk-gia.example.net",
            resolvedIp = "203.0.113.42",
            ipFamily = IpFamily.IPV4,
            dnsDurationMs = 14.2,
        )
        val samples = listOf(33.4, 34.1, 32.8, 35.6, 33.9, 34.5, 36.2, 33.1, 34.0, 34.4).mapIndexed { i, ms ->
            TcpPingSample(seq = i + 1, success = true, rttMs = ms, timestampEpochMs = SHOWCASE_NOW_MILLIS)
        }
        val tcpSummary = TcpPingSummary(
            resolvedTarget = resolved,
            port = 443,
            sent = 10,
            received = 10,
            lossPercent = 0.0,
            minMs = 32.8,
            avgMs = 34.2,
            maxMs = 36.2,
            jitterMdevMs = 1.0,
            samples = samples,
        )
        val ipIntel = IpIntelligenceReport(
            ip = "203.0.113.42",
            ipFamily = IpFamily.IPV4,
            countryCode = "HK",
            countryName = "香港",
            regionName = "Hong Kong",
            cityName = "Central",
            asn = "AS4809",
            orgName = "China Telecom CN2 / DMIT HK",
            ispName = "DMIT Cloud Services",
            usageType = IpUsageType.HOSTING_DATACENTER,
            originType = IpOriginType.NATIVE,
            rirCountryCode = "HK",
            rirAuthority = "APNIC",
            originEvidence = "RIR 注册国 HK 与 GeoIP 落地国 HK 一致，判定为香港原生 IP",
            geoSource = "ipwho.is",
            whoisSource = "RIPEstat",
        )
        val dlResult = Iperf3Result(
            direction = Iperf3Direction.DOWNLOAD,
            port = 5201,
            resolvedTarget = resolved,
            durationSec = 5.0,
            bytesTransferred = 267_875_000L,
            clientMbps = 428.6,
            serverMbps = 432.1,
            peakMbps = 465.2,
            success = true,
        )
        val clientNet = ClientNetworkInfo(
            carrier = ClientCarrier.CHINA_TELECOM,
            carrierDisplayName = "中国电信",
            ipFamily = IpFamily.IPV4,
            regionName = "上海",
            maskedIp = "180.168.*.*",
        )
        val report = NetBenchReport(
            targetInput = "hk-gia.example.net",
            resolvedTarget = resolved,
            tcpPort = 443,
            clientNetwork = clientNet,
            tcpPingSummary = tcpSummary,
            ipIntelligence = ipIntel,
            iperfDownloadResult = dlResult,
        )
        val markdown = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.REPLY_CARD),
        )
        MobileNetBenchUiState(
            targetInput = "hk-gia.example.net",
            tcpPortText = "443",
            enableTcpPing = true,
            enableIpCheck = true,
            enableIperf3 = true,
            iperfTestMode = Iperf3TestMode.DOWNLOAD,
            isRunning = false,
            activePhase = NetBenchPhase.COMPLETED,
            resolvedTarget = resolved,
            clientNetwork = clientNet,
            tcpPingProgress = TcpPingProgress(
                resolvedTarget = resolved,
                port = 443,
                totalPlanned = 10,
                latestSample = samples.last(),
                samples = samples,
                summary = tcpSummary,
                isFinished = true,
            ),
            ipIntelligence = ipIntel,
            iperfDownloadProgress = Iperf3Progress(
                direction = Iperf3Direction.DOWNLOAD,
                stage = Iperf3Stage.COMPLETED,
                elapsedSec = 5.0,
                targetDurationSec = 5,
                bytesTransferred = 267_875_000L,
                instantMbps = 434.0,
                averageMbps = 428.6,
                peakMbps = 465.2,
                throughputHistoryMbps = listOf(380.0, 412.0, 445.0, 465.2, 434.0),
                finalResult = dlResult,
            ),
            generatedMarkdown = markdown,
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        val container = LocalAppContainer.current
        val vm = remember(container) {
            MobileNetBenchViewModel(historyStore = container.netBenchHistoryStore)
        }
        MobileNetBenchScreen(
            onBack = {},
            onInsertMarkdown = {},
            initialListItemIndex = 3,
            previewUiState = netBenchState,
            viewModel = vm,
        )
    }
}

// ── Feature 6: Lite SSH — Preset Script Runner + High-Fidelity ANSI Console ──────────────

@Preview(device = TOOLBOX_TALL_SPEC, showBackground = true)
@Composable
fun Showcase07LiteSshAnsiRunner() {
    val sshUiState = remember {
        val server = SshServerProfile(
            id = "srv-tyo",
            alias = "DMIT-TYO-Pro",
            host = "198.51.100.24",
            port = 22,
            username = "root",
            authMethod = SshAuthMethod.PRIVATE_KEY,
            knownHostKeyFingerprint = "SHA256:9xK3mP2vL8qR5tW1zB4cN7yF0aD6eH2jU8sV3kM9pQ",
            knownHostKeyAlgorithm = "ssh-ed25519",
            lastConnectedEpochMillis = SHOWCASE_NOW_MILLIS - 120_000L,
        )
        val ansiOutput = buildString {
            appendLine("\u001B[1;36m===================================================\u001B[0m")
            appendLine("\u001B[1;32m  NextTrace v1.3.4 · 三网回程路由与流媒体快检\u001B[0m")
            appendLine("\u001B[1;36m===================================================\u001B[0m")
            appendLine("\u001B[33mHost:\u001B[0m 198.51.100.24  \u001B[33mKernel:\u001B[0m 6.1.0-26-amd64 (BBR)")
            appendLine("\u001B[33mCPU :\u001B[0m AMD EPYC 7443P (2 vCPU) · \u001B[32mGB6: 1482 / 2710\u001B[0m")
            appendLine("\u001B[33mDisk:\u001B[0m NVMe 4K Rand \u001B[1;32m342 MB/s\u001B[0m · 1M Seq \u001B[1;32m3.84 GB/s\u001B[0m")
            appendLine()
            appendLine("\u001B[1;37m[三网回程路由]\u001B[0m")
            appendLine("  上海电信  \u001B[32mAS4809 CN2 GIA\u001B[0m   avg \u001B[1;32m36.4 ms\u001B[0m  (直连)")
            appendLine("  北京联通  \u001B[36mAS9929 A网优化\u001B[0m   avg \u001B[1;36m44.8 ms\u001B[0m  (直连)")
            appendLine("  广州移动  \u001B[32mAS58807 CMIN2\u001B[0m    avg \u001B[1;32m41.2 ms\u001B[0m  (直连)")
            appendLine()
            appendLine("\u001B[1;37m[流媒体 & AI 解锁]\u001B[0m")
            appendLine("  Netflix       : \u001B[1;32mYes (Region: JP 原生全解锁)\u001B[0m")
            appendLine("  Disney+       : \u001B[1;32mYes (Region: JP)\u001B[0m")
            appendLine("  ChatGPT / Web : \u001B[1;32mYes (JP 支持 App & Web)\u001B[0m")
            appendLine("  TikTok        : \u001B[1;32mYes (Region: JP)\u001B[0m")
        }
        val runRecord = SshExecutionRecord(
            id = "run-01",
            serverId = server.id,
            serverAlias = server.alias,
            serverHost = server.host,
            presetId = SshPresetCatalog.PRESET_NEXT_TRACE,
            presetName = "NextTrace 三网回程",
            tabTitle = "三网回程",
            command = "nexttrace --fast-trace",
            sanitizedAnsiOutput = ansiOutput,
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = SHOWCASE_NOW_MILLIS - 45_000L,
            durationMillis = 18_400L,
        )
        val runYabs = SshExecutionRecord(
            id = "run-02",
            serverId = server.id,
            serverAlias = server.alias,
            serverHost = server.host,
            presetId = SshPresetCatalog.PRESET_YABS,
            presetName = "YABS 综合跑分",
            tabTitle = "YABS 跑分",
            command = "curl -sL yabs.sh | bash -s -- -5",
            sanitizedAnsiOutput = ansiOutput,
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = SHOWCASE_NOW_MILLIS - 180_000L,
            durationMillis = 92_000L,
        )
        LiteSshUiState(
            servers = listOf(server),
            selectedServerId = server.id,
            selectedPresetId = SshPresetCatalog.PRESET_NEXT_TRACE,
            isRunning = false,
            connectionPhase = SshConnectionPhase.COMPLETED,
            activeServerAlias = server.alias,
            activeServerHost = server.host,
            activePresetName = "NextTrace 三网回程",
            liveAnsiOutput = ansiOutput,
            elapsedMillis = 18_400L,
            exitCode = 0,
            sessionRuns = listOf(runRecord, runYabs),
            selectedRunIdsForExport = setOf(runRecord.id, runYabs.id),
            viewingRunId = runRecord.id,
            recentRuns = listOf(runRecord, runYabs),
            maskServerIp = true,
        )
    }

    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK, purePitchAmoled = true) {
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
            onInsertToReply = {},
            previewUiState = sshUiState,
            viewModel = vm,
        )
    }
}

// ── Feature 7: Overseas Intelligence Aggregator (LET / LEB + Translation + CNY) ─────────

@Preview(device = TOOLBOX_PHONE_SPEC, showBackground = true)
@Composable
fun Showcase08OverseasIntelHub() {
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
        val repo = remember { PreviewOverseasIntelRepository(nowEpochMillis = SHOWCASE_NOW_MILLIS) }
        OverseasIntelScreen(
            onBack = {},
            repository = repo,
            onShareDealToForum = { _, _ -> },
        )
    }
}
