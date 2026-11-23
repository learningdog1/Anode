package com.nodeseek.app.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetPortfolioSummary
import com.nodeseek.app.domain.fleet.IdleClassificationStatus
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.ServerIdleAssessment
import com.nodeseek.app.domain.fleet.ServerTrafficForecast
import com.nodeseek.app.domain.fleet.TrafficResetDaySource
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.fleet.MyServersScreen
import com.nodeseek.app.ui.fleet.MyServersTab
import com.nodeseek.app.ui.fleet.MyServersUiState
import com.nodeseek.app.ui.fleet.MyServersViewModel
import com.nodeseek.app.ui.fleet.ProbeViewMode
import com.nodeseek.app.ui.theme.PulseThemeMode
import java.math.BigDecimal
import java.time.LocalDate

private const val PROBE_PREVIEW_PHONE_SPEC = "spec:width=412dp,height=915dp,dpi=320"
private const val PROBE_PREVIEW_NOW_MILLIS = 1_795_096_200_000L

@Composable
private fun rememberProbeCompactPreviewState(
    denseSample: Boolean,
): MyServersUiState = remember(denseSample) {
    val gib = 1024L * 1024L * 1024L
    val mib = 1024L * 1024L
    val panels = listOf(
        ProbePanelConfig(
            id = "p-nezha",
            name = "Nezha v1",
            baseUrl = "https://status.anode.dev",
            panelType = ProbePanelType.NEZHA_V1,
            resolvedPanelType = ProbePanelType.NEZHA_V1,
        ),
        ProbePanelConfig(
            id = "p-komari",
            name = "Komari",
            baseUrl = "https://komari.anode.dev",
            panelType = ProbePanelType.KOMARI,
            resolvedPanelType = ProbePanelType.KOMARI,
        ),
    )

    val baseSnapshots = listOf(
        ProbeServerSnapshot(
            panelId = "p-nezha",
            remoteServerId = "101",
            name = "DMIT-TYO-Pro-Tiny",
            countryCode = "JP",
            os = "Debian 12.7",
            cpuCores = 2,
            cpuPercent = 24.6,
            memUsedBytes = 940L * mib,
            memTotalBytes = 2048L * mib,
            diskUsedBytes = 14L * gib,
            diskTotalBytes = 40L * gib,
            netInSpeedBps = 18_350_080L,
            netOutSpeedBps = 22_858_956L,
            monthlyTransferInBytes = 215L * gib,
            monthlyTransferOutBytes = 248L * gib,
            trafficLimitBytes = 1024L * gib,
            uptimeSeconds = 42L * 86_400L,
            load1 = 0.34,
            isOnline = true,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L,
        ),
        ProbeServerSnapshot(
            panelId = "p-komari",
            remoteServerId = "201",
            name = "Vultr-SGP-HighFreq",
            countryCode = "SG",
            os = "Ubuntu 24.04",
            cpuCores = 1,
            cpuPercent = 78.0,
            memUsedBytes = 932L * mib,
            memTotalBytes = 1024L * mib,
            diskUsedBytes = 20_937_965_568L,
            diskTotalBytes = 32L * gib,
            netInSpeedBps = 50_541_363L,
            netOutSpeedBps = 65_011_712L,
            monthlyTransferInBytes = 790L * gib,
            monthlyTransferOutBytes = 869L * gib,
            trafficLimitBytes = 2048L * gib,
            uptimeSeconds = 7L * 86_400L,
            load1 = 1.92,
            isOnline = true,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 10_000L,
        ),
        ProbeServerSnapshot(
            panelId = "p-komari",
            remoteServerId = "202",
            name = "ClawCloud-HK-Opt",
            countryCode = "HK",
            os = "Ubuntu 24.04",
            cpuCores = 2,
            cpuPercent = 11.2,
            memUsedBytes = 680L * mib,
            memTotalBytes = 2048L * mib,
            diskUsedBytes = 9L * gib,
            diskTotalBytes = 40L * gib,
            netInSpeedBps = 6_186_598L,
            netOutSpeedBps = 8_912_896L,
            monthlyTransferInBytes = 140L * gib,
            monthlyTransferOutBytes = 162L * gib,
            trafficLimitBytes = 1024L * gib,
            uptimeSeconds = 19L * 86_400L,
            load1 = 0.18,
            isOnline = true,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 15_000L,
        ),
        ProbeServerSnapshot(
            panelId = "p-nezha",
            remoteServerId = "104",
            name = "Hetzner-FSN-CX22",
            countryCode = "DE",
            os = "Debian 12",
            cpuCores = 2,
            cpuPercent = 1.2,
            memUsedBytes = 460L * mib,
            memTotalBytes = 4096L * mib,
            diskUsedBytes = 4L * gib,
            diskTotalBytes = 40L * gib,
            netInSpeedBps = 20_480L,
            netOutSpeedBps = 10_240L,
            monthlyTransferInBytes = 4L * gib,
            monthlyTransferOutBytes = 2L * gib,
            trafficLimitBytes = 20L * 1024L * gib,
            uptimeSeconds = 96L * 86_400L,
            load1 = 0.01,
            isOnline = true,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 14_000L,
        ),
    )

    val extraDenseSnapshots = listOf(
        ProbeServerSnapshot(
            panelId = "p-nezha",
            remoteServerId = "106",
            name = "BWG-CN2-GIA-E",
            countryCode = "US",
            os = "Debian 12",
            cpuCores = 2,
            cpuPercent = 6.0,
            memUsedBytes = 532L * mib,
            memTotalBytes = 1024L * mib,
            diskUsedBytes = 10L * gib,
            diskTotalBytes = 20L * gib,
            netInSpeedBps = 2_202_010L,
            netOutSpeedBps = 3_460_300L,
            monthlyTransferInBytes = 60L * gib,
            monthlyTransferOutBytes = 64L * gib,
            trafficLimitBytes = 1024L * gib,
            uptimeSeconds = 210L * 86_400L,
            isOnline = true,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L,
        ),
        ProbeServerSnapshot(
            panelId = "p-komari",
            remoteServerId = "206",
            name = "Oracle-ICN-ARM",
            countryCode = "KR",
            os = "Ubuntu 24.04",
            cpuCores = 4,
            cpuPercent = 18.0,
            memUsedBytes = 17_300L * mib,
            memTotalBytes = 24_576L * mib,
            diskUsedBytes = 40L * gib,
            diskTotalBytes = 100L * gib,
            netInSpeedBps = 1_258_291L,
            netOutSpeedBps = 838_860L,
            monthlyTransferInBytes = 200L * gib,
            monthlyTransferOutBytes = 200L * gib,
            trafficLimitBytes = 10L * 1024L * gib,
            uptimeSeconds = 88L * 86_400L,
            isOnline = true,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L,
        ),
        ProbeServerSnapshot(
            panelId = "p-nezha",
            remoteServerId = "105",
            name = "RackNerd-LAX-DC02",
            countryCode = "US",
            os = "Debian 12",
            isOnline = false,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L - 3L * 3_600_000L,
        ),
        ProbeServerSnapshot(
            panelId = "p-komari",
            remoteServerId = "207",
            name = "Akile-OSA-Lite",
            countryCode = "JP",
            os = "Debian 12",
            cpuCores = 1,
            cpuPercent = 9.0,
            memUsedBytes = 420L * mib,
            memTotalBytes = 1024L * mib,
            diskUsedBytes = 5_800L * mib,
            diskTotalBytes = 20L * gib,
            netInSpeedBps = 10_066_329L,
            netOutSpeedBps = 4_613_734L,
            monthlyTransferInBytes = 290L * gib,
            monthlyTransferOutBytes = 304L * gib,
            trafficLimitBytes = 1024L * gib,
            uptimeSeconds = 33L * 86_400L,
            isOnline = true,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L,
        ),
        ProbeServerSnapshot(
            panelId = "p-nezha",
            remoteServerId = "108",
            name = "Greencloud-AMS",
            countryCode = "NL",
            os = "Debian 12",
            cpuCores = 2,
            cpuPercent = 3.0,
            memUsedBytes = 573L * mib,
            memTotalBytes = 2048L * mib,
            diskUsedBytes = 35_200L * mib,
            diskTotalBytes = 40L * gib,
            netInSpeedBps = 419_430L,
            netOutSpeedBps = 314_572L,
            monthlyTransferInBytes = 110L * gib,
            monthlyTransferOutBytes = 105L * gib,
            trafficLimitBytes = 1024L * gib,
            uptimeSeconds = 61L * 86_400L,
            isOnline = true,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L,
        ),
        ProbeServerSnapshot(
            panelId = "p-komari",
            remoteServerId = "208",
            name = "HiNet-TPE-NAT",
            countryCode = "TW",
            os = "Debian 12",
            isOnline = false,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L - 48L * 3_600_000L,
        ),
        ProbeServerSnapshot(
            panelId = "p-komari",
            remoteServerId = "209",
            name = "Lisahost-HKT",
            countryCode = "HK",
            os = "Ubuntu 22.04",
            cpuCores = 2,
            cpuPercent = 14.0,
            memUsedBytes = 758L * mib,
            memTotalBytes = 2048L * mib,
            diskUsedBytes = 12L * gib,
            diskTotalBytes = 40L * gib,
            netInSpeedBps = 3_145_728L,
            netOutSpeedBps = 1_782_579L,
            monthlyTransferInBytes = 45L * gib,
            monthlyTransferOutBytes = 47L * gib,
            trafficLimitBytes = 1024L * gib,
            uptimeSeconds = 12L * 86_400L,
            isOnline = true,
            lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L,
        ),
    )

    val offlineCard = ProbeServerSnapshot(
        panelId = "p-nezha",
        remoteServerId = "105",
        name = "RackNerd-LAX-DC02",
        countryCode = "US",
        os = "Debian 12",
        isOnline = false,
        lastActiveEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L - 3L * 3_600_000L,
    )

    val snapshots = if (denseSample) baseSnapshots + extraDenseSnapshots else baseSnapshots + offlineCard

    val linkedAssets = listOf(
        VpsAssetRecord(
            id = "a-1",
            nickname = "DMIT 东京 Pro Tiny",
            providerName = "DMIT",
            specsSummary = "2C / 2G / 40G / 1TB",
            region = "东京",
            renewalAmount = "149.90",
            currency = ValuationCurrency.USD,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = "2026-11-28",
            linkedProbeKey = "p-nezha:101",
            monthlyTrafficQuotaGb = 1024.0,
        ),
        VpsAssetRecord(
            id = "a-2",
            nickname = "Vultr 新加坡高频",
            providerName = "Vultr",
            specsSummary = "1C / 1G / 32G / 2TB",
            region = "新加坡",
            renewalAmount = "24.00",
            currency = ValuationCurrency.USD,
            cyclePreset = BillingCyclePreset.MONTHLY,
            expiryDateIso = "2026-12-20",
            linkedProbeKey = "p-komari:201",
            monthlyTrafficQuotaGb = 2048.0,
        ),
        VpsAssetRecord(
            id = "a-3",
            nickname = "Hetzner 法兰克福 CX22",
            providerName = "Hetzner",
            specsSummary = "2C / 4G / 40G / 20TB",
            region = "法兰克福",
            renewalAmount = "4.59",
            currency = ValuationCurrency.EUR,
            cyclePreset = BillingCyclePreset.MONTHLY,
            expiryDateIso = "2027-01-02",
            linkedProbeKey = "p-nezha:104",
            monthlyTrafficQuotaGb = 20480.0,
        ),
    )

    val cycleStart = LocalDate.of(2026, 11, 1)
    val nextReset = LocalDate.of(2026, 12, 1)
    val forecasts = mapOf(
        "p-nezha:101" to ServerTrafficForecast(
            probeKey = "p-nezha:101",
            usedBytes = 463L * gib,
            limitBytes = 1024L * gib,
            usageRatio = 463.0 / 1024.0,
            anchorResetDay = 1,
            resetDaySource = TrafficResetDaySource.FIRST_OF_MONTH_DEFAULT,
            cycleStartDate = cycleStart,
            nextResetDate = nextReset,
            elapsedMillis = 18L * 86_400_000L,
            dailyBurnBytes = 25L * gib,
            projectedCycleUsageRatio = 0.78,
            isAtOrAboveAlertThreshold = false,
            isExhausted = false,
            willExhaustBeforeReset = false,
            predictedExhaustionDate = null,
            exhaustionForecastText = null,
        ),
        "p-komari:201" to ServerTrafficForecast(
            probeKey = "p-komari:201",
            usedBytes = 1659L * gib,
            limitBytes = 2048L * gib,
            usageRatio = 1659.0 / 2048.0,
            anchorResetDay = 1,
            resetDaySource = TrafficResetDaySource.FIRST_OF_MONTH_DEFAULT,
            cycleStartDate = cycleStart,
            nextResetDate = nextReset,
            elapsedMillis = 18L * 86_400_000L,
            dailyBurnBytes = 92L * gib,
            projectedCycleUsageRatio = 1.32,
            isAtOrAboveAlertThreshold = true,
            isExhausted = false,
            willExhaustBeforeReset = true,
            predictedExhaustionDate = LocalDate.of(2026, 11, 24),
            exhaustionForecastText = "预计本月 24 号流量会用完",
        ),
        "p-komari:202" to ServerTrafficForecast(
            probeKey = "p-komari:202",
            usedBytes = 302L * gib,
            limitBytes = 1024L * gib,
            usageRatio = 302.0 / 1024.0,
            anchorResetDay = 1,
            resetDaySource = TrafficResetDaySource.FIRST_OF_MONTH_DEFAULT,
            cycleStartDate = cycleStart,
            nextResetDate = nextReset,
            elapsedMillis = 18L * 86_400_000L,
            dailyBurnBytes = 17L * gib,
            projectedCycleUsageRatio = 0.52,
            isAtOrAboveAlertThreshold = false,
            isExhausted = false,
            willExhaustBeforeReset = false,
            predictedExhaustionDate = null,
            exhaustionForecastText = null,
        ),
        "p-nezha:104" to ServerTrafficForecast(
            probeKey = "p-nezha:104",
            usedBytes = 6L * gib,
            limitBytes = 20L * 1024L * gib,
            usageRatio = 6.0 / 20480.0,
            anchorResetDay = 1,
            resetDaySource = TrafficResetDaySource.FIRST_OF_MONTH_DEFAULT,
            cycleStartDate = cycleStart,
            nextResetDate = nextReset,
            elapsedMillis = 18L * 86_400_000L,
            dailyBurnBytes = 340L * mib,
            projectedCycleUsageRatio = 0.03,
            isAtOrAboveAlertThreshold = false,
            isExhausted = false,
            willExhaustBeforeReset = false,
            predictedExhaustionDate = null,
            exhaustionForecastText = null,
        ),
    )

    val idleAssessments = mapOf(
        "p-nezha:104" to ServerIdleAssessment(
            probeKey = "p-nezha:104",
            serverName = "Hetzner-FSN-CX22",
            linkedAsset = linkedAssets[2],
            status = IdleClassificationStatus.IDLE,
            avgCpu30dPercent = 1.2,
            recordedDays = 14,
            totalSamples = 140,
            trafficUsageRatio = 0.0003,
            projectedTrafficUsageRatio = 0.0005,
            monthlyTransferBytes = 6L * gib,
            trafficLimitBytes = 20L * 1024L * gib,
            recommendationText = "30天平均 CPU 1.2% · 流量仅用 0.0%，建议出掉回血",
        ),
    )

    MyServersUiState(
        selectedTab = MyServersTab.PROBES,
        panels = panels,
        snapshots = snapshots,
        filteredSnapshots = snapshots,
        assets = linkedAssets,
        trafficForecastsByProbeKey = forecasts,
        highTrafficForecasts = listOfNotNull(forecasts["p-komari:201"]),
        idleAssessmentsByProbeKey = idleAssessments,
        idleServers = idleAssessments.values.toList(),
        portfolioSummary = FleetPortfolioSummary(
            totalMachineCount = if (denseSample) 11 else 6,
            totalProbeNodeCount = snapshots.size,
            onlineProbeCount = snapshots.count { it.isOnline },
            offlineProbeCount = snapshots.count { !it.isOnline },
            totalMonthlyCostCny = if (denseSample) BigDecimal("386.20") else BigDecimal("142.50"),
            totalAnnualizedCostCny = if (denseSample) BigDecimal("4634.00") else BigDecimal("1710.00"),
            dueSoonRenewalCount = if (denseSample) 3 else 2,
        ),
        monitorSettings = FleetMonitorSettings(),
        lastSyncEpochMillis = PROBE_PREVIEW_NOW_MILLIS - 12_000L,
    )
}

@Preview(device = PROBE_PREVIEW_PHONE_SPEC, showBackground = true)
@Composable
fun ProbeCompactDenseListDarkPreview() {
    val state = rememberProbeCompactPreviewState(denseSample = true)
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
            previewUiState = state,
            previewProbeViewMode = ProbeViewMode.DENSE_LIST,
        )
    }
}

@Preview(device = PROBE_PREVIEW_PHONE_SPEC, showBackground = true)
@Composable
fun ProbeCompactCardsLightPreview() {
    val state = rememberProbeCompactPreviewState(denseSample = false)
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
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
            previewUiState = state,
            previewProbeViewMode = ProbeViewMode.CARDS,
        )
    }
}

@Preview(device = PROBE_PREVIEW_PHONE_SPEC, showBackground = true)
@Composable
fun ProbeCompactCardsAmoledPreview() {
    val state = rememberProbeCompactPreviewState(denseSample = false)
    PreviewPhoneFrame(themeMode = PulseThemeMode.OBSIDIAN_DARK, purePitchAmoled = true) {
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
            previewUiState = state,
            previewProbeViewMode = ProbeViewMode.CARDS,
        )
    }
}

@Preview(device = PROBE_PREVIEW_PHONE_SPEC, showBackground = true)
@Composable
fun ProbeCompactDenseListLightPreview() {
    val state = rememberProbeCompactPreviewState(denseSample = true)
    PreviewPhoneFrame(themeMode = PulseThemeMode.PAPER_LIGHT) {
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
            previewUiState = state,
            previewProbeViewMode = ProbeViewMode.DENSE_LIST,
        )
    }
}
