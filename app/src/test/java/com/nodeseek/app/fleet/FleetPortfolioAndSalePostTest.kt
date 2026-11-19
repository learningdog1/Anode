package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.valuation.CurrencyRateQuote
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.FleetDeliveryMethod
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.FleetSalePostBuilder
import com.nodeseek.app.domain.fleet.FleetSalePostOutcome
import com.nodeseek.app.domain.fleet.FleetSalePostRequest
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.PushFeeBearer
import com.nodeseek.app.domain.fleet.RestockAssetPrefill
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.DealSpreadStatus
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.ValuationMarkdownStyle
import com.nodeseek.app.ui.fleet.MyServersTab
import com.nodeseek.app.ui.fleet.MyServersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class FleetPortfolioAndSalePostTest {

    private lateinit var tempDir: File
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("fleet_portfolio_test").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `portfolio calculator aggregates CNY monthly and annualized costs accurately and sorts renewals`() {
        val fxRates = FxRatesState(
            quotes = mapOf(
                ValuationCurrency.USD to CurrencyRateQuote(
                    currency = ValuationCurrency.USD,
                    rateToCny = BigDecimal("7.2000"),
                    source = FxRateSource.ONLINE_FRESH,
                ),
                ValuationCurrency.EUR to CurrencyRateQuote(
                    currency = ValuationCurrency.EUR,
                    rateToCny = BigDecimal("8.0000"),
                    source = FxRateSource.ONLINE_FRESH,
                ),
                ValuationCurrency.CNY to CurrencyRateQuote(
                    currency = ValuationCurrency.CNY,
                    rateToCny = BigDecimal("1.0000"),
                    source = FxRateSource.OFFLINE_DEFAULT,
                ),
            ),
        )

        val today = LocalDate.of(2026, 11, 19)
        val assets = listOf(
            // $10.00/mo -> ¥72.00/mo, ¥864.00/yr, expires in 40 days
            VpsAssetRecord(
                id = "a1",
                nickname = "Monthly Box",
                providerName = "DMIT",
                specsSummary = "2C / 2G",
                region = "香港",
                renewalAmount = "10.00",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.MONTHLY,
                expiryDateIso = "2026-12-29",
                remindDaysBeforeExpiry = 7,
            ),
            // €15.00/yr -> ¥120.00/yr, ¥10.00/mo, expires in 3 days (CRITICAL)
            VpsAssetRecord(
                id = "a2",
                nickname = "Annual DE",
                providerName = "Netcup",
                specsSummary = "2C / 4G",
                region = "德国",
                renewalAmount = "15.00",
                currency = ValuationCurrency.EUR,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2026-11-22",
                remindDaysBeforeExpiry = 7,
            ),
            // ¥60.00/quarter -> ¥240.00/yr, ¥20.00/mo, expired 2 days ago (EXPIRED)
            VpsAssetRecord(
                id = "a3",
                nickname = "Quarter CN",
                providerName = "Claw",
                specsSummary = "1C / 1G",
                region = "新加坡",
                renewalAmount = "60.00",
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.QUARTERLY,
                expiryDateIso = "2026-11-17",
                remindDaysBeforeExpiry = 7,
            ),
        )
        val snapshots = listOf(
            ProbeServerSnapshot(panelId = "p1", remoteServerId = "1", name = "Node1", isOnline = true),
            ProbeServerSnapshot(panelId = "p1", remoteServerId = "2", name = "Node2", isOnline = true),
            ProbeServerSnapshot(panelId = "p1", remoteServerId = "3", name = "Node3", isOnline = false),
        )

        val summary = FleetPortfolioCalculator.summarize(
            assets = assets,
            snapshots = snapshots,
            fxRatesState = fxRates,
            today = today,
        )

        assertEquals(3, summary.totalMachineCount)
        assertEquals(3, summary.totalProbeNodeCount)
        assertEquals(2, summary.onlineProbeCount)
        assertEquals(1, summary.offlineProbeCount)

        // Monthly: 72 + 10 + 20 = 102.00; Annual: 864 + 120 + 240 = 1224.00
        assertEquals(BigDecimal("102.00"), summary.totalMonthlyCostCny)
        assertEquals(BigDecimal("1224.00"), summary.totalAnnualizedCostCny)

        // Sorted ascending by daysRemaining: -2, 3, 40
        assertEquals(listOf("a3", "a2", "a1"), summary.upcomingRenewals.map { it.asset.id })
        assertEquals(AssetExpiryUrgency.EXPIRED, summary.upcomingRenewals[0].urgency)
        assertEquals(AssetExpiryUrgency.CRITICAL, summary.upcomingRenewals[1].urgency)
        assertEquals(AssetExpiryUrgency.NORMAL, summary.upcomingRenewals[2].urgency)
        assertEquals(2, summary.dueSoonRenewalCount)
    }

    @Test
    fun `FleetSalePostBuilder integrates with VpsValuationEngine to produce par sale post title and markdown`() {
        val asset = VpsAssetRecord(
            id = "sale-1",
            nickname = "RN DC02",
            providerName = "RackNerd",
            specsSummary = "1C / 1G / 20G NVMe / 1TB",
            region = "洛杉矶 DC02",
            routeTag = "优化直连",
            renewalAmount = "10.00",
            currency = ValuationCurrency.USD,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = "2027-05-20", // exactly 182 days from 2026-11-19
            monthlyTrafficQuotaGb = 1000.0,
            notes = "IP 干净，支持站内私信秒回",
        )
        val probe = ProbeServerSnapshot(
            panelId = "p1",
            remoteServerId = "9",
            name = "RN-LA-DC02",
            countryCode = "US",
            os = "Debian 12",
            cpuPercent = 8.4,
            memUsedBytes = 268435456L,
            memTotalBytes = 1073741824L,
            diskUsedBytes = 4294967296L,
            diskTotalBytes = 21474836480L,
            monthlyTransferInBytes = 10737418240L,
            monthlyTransferOutBytes = 21474836480L,
            uptimeSeconds = 864000L,
            isOnline = true,
        )

        val valuationDate = LocalDateTime.of(2026, 11, 19, 0, 0)
        val fxRate = BigDecimal("7.3000") // 10 USD * 7.30 = 73.00 CNY / 365 days = 0.20 CNY/day -> 182 days = ¥36.40

        val outcome = FleetSalePostBuilder.build(
            FleetSalePostRequest(
                asset = asset,
                probeSnapshot = probe,
                fxRateToCny = fxRate,
                fxSourceNote = "在线汇率 2026-11-19",
                askingPriceCny = null, // defaults to net remaining value -> par
                pushFeeAmount = BigDecimal.ZERO,
                pushFeeBearer = PushFeeBearer.FREE,
                deliveryMethod = FleetDeliveryMethod.PUSH,
                includeProbeTelemetry = true,
                markdownStyle = ValuationMarkdownStyle.THREE_COLUMN_TABLE,
                valuationDateTime = valuationDate,
            ),
        )

        assertTrue(outcome is FleetSalePostOutcome.Success)
        val result = (outcome as FleetSalePostOutcome.Success).result
        assertEquals(BigDecimal("36.40"), result.valuationResult.remainingValueCny)
        assertEquals(BigDecimal("36.40"), result.effectiveAskingPriceCny)
        assertEquals(DealSpreadStatus.PAR, result.valuationResult.transferBreakdown?.spreadStatus)

        assertEquals(
            "[出] RackNerd 洛杉矶 DC02 1C / 1G / 20G NVMe / 1TB 剩余价值 ¥36.40 出 (到期 2027-05-20)",
            result.title,
        )
        assertTrue(result.markdownBody.contains("RackNerd"))
        assertTrue(result.markdownBody.contains("1C / 1G / 20G NVMe / 1TB"))
        assertTrue(result.markdownBody.contains("¥36.40"))
        assertTrue(result.markdownBody.contains("| **净剩余价值** |"))
        assertTrue(result.markdownBody.contains("探针实时运行参考"))
        assertTrue(result.markdownBody.contains("Debian 12"))
        assertTrue(result.markdownBody.contains("Push 过户"))
    }

    @Test
    fun `FleetSalePostBuilder reflects premium asking price and split Push fee`() {
        val asset = VpsAssetRecord(
            id = "sale-2",
            nickname = "DMIT HK",
            providerName = "DMIT",
            specsSummary = "1C / 1G / 20G",
            region = "香港",
            renewalAmount = "365.00",
            currency = ValuationCurrency.CNY,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = "2027-02-27", // 100 days from 2026-11-19 -> ¥100.00 remaining value
        )
        val outcome = FleetSalePostBuilder.build(
            FleetSalePostRequest(
                asset = asset,
                askingPriceCny = BigDecimal("120.00"),
                pushFeeAmount = BigDecimal("10.00"),
                pushFeeCurrency = ValuationCurrency.CNY,
                pushFeeBearer = PushFeeBearer.SPLIT,
                deliveryMethod = FleetDeliveryMethod.PUSH,
                valuationDateTime = LocalDateTime.of(2026, 11, 19, 0, 0),
            ),
        )

        assertTrue(outcome is FleetSalePostOutcome.Success)
        val res = (outcome as FleetSalePostOutcome.Success).result
        val deal = res.valuationResult.transferBreakdown
        assertNotNull(deal)
        assertEquals(BigDecimal("100.00"), res.valuationResult.remainingValueCny)
        assertEquals(BigDecimal("125.00"), deal!!.buyerTotalCostCny) // 120 + 5 buyer push share
        assertEquals(BigDecimal("115.00"), deal.sellerNetProceedsCny) // 120 - 5 seller push share
        assertEquals(BigDecimal("25.00"), deal.buyerPremiumCny)
        assertEquals(DealSpreadStatus.PREMIUM, deal.spreadStatus)
        assertTrue(res.markdownBody.contains("双方平摊"))
        assertTrue(res.markdownBody.contains("+¥25.00"))
    }

    @Test
    fun `FleetSalePostBuilder returns Invalid for malformed renewal amount or expiry date`() {
        val broken = VpsAssetRecord(
            id = "bad",
            nickname = "Bad Asset",
            providerName = "Test",
            specsSummary = "",
            region = "",
            renewalAmount = "-5",
            expiryDateIso = "not-a-date",
        )
        val outcome = FleetSalePostBuilder.build(FleetSalePostRequest(asset = broken))
        assertTrue(outcome is FleetSalePostOutcome.Invalid)
        assertEquals(2, (outcome as FleetSalePostOutcome.Invalid).errors.size)
    }

    @Test
    fun `prefillAssetFromRestock parses price hint and switches ViewModel to Assets tab with draft open`() = runTest(testDispatcher) {
        val parsed = FleetPortfolioCalculator.parseRestockPrefill(
            RestockAssetPrefill(
                name = "BandwagonHost 洛杉矶 2C 2G 40G NVMe",
                priceHint = "$49.90/yr",
                purchaseUrl = "https://bwh81.net/cart.php?a=add&pid=105",
            ),
        )
        assertEquals(ValuationCurrency.USD, parsed.currency)
        assertEquals("49.90", parsed.renewalAmount)
        assertEquals(BillingCyclePreset.ANNUAL, parsed.cyclePreset)
        assertEquals("洛杉矶", parsed.region)
        assertEquals("https://bwh81.net/cart.php?a=add&pid=105", parsed.purchaseUrl)

        val fleetRepo = FleetRepositoryImpl(
            storeFile = File(tempDir, "vm_fleet_store.json"),
            tokenCipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey()),
            ioDispatcher = testDispatcher,
        )
        val fxRepo = ExchangeRateRepository(
            cacheFile = File(tempDir, "vm_fx_cache.json"),
            ioDispatcher = testDispatcher,
        )
        val vm = MyServersViewModel(
            fleetRepository = fleetRepo,
            exchangeRateRepository = fxRepo,
            todayProvider = { LocalDate.of(2026, 11, 19) },
        )

        vm.prefillAssetFromRestock(
            name = "Claw 新加坡 1C 1G",
            priceHint = "€7.50/mo",
            purchaseUrl = "https://claw.cloud/order/1",
        )
        advanceUntilIdle()

        val state = vm.uiState.first { it.assetEditorDraft != null }
        assertEquals(MyServersTab.ASSETS, state.selectedTab)
        val draft = state.assetEditorDraft!!
        assertEquals("Claw 新加坡 1C 1G", draft.nickname)
        assertEquals(ValuationCurrency.EUR, draft.currency)
        assertEquals("7.50", draft.renewalAmount)
        assertEquals(BillingCyclePreset.MONTHLY, draft.cyclePreset)
        assertEquals("2026-12-19", draft.expiryDateIso)
        assertEquals("https://claw.cloud/order/1", draft.purchaseUrl)
        assertFalse(draft.notes.isBlank())
    }
}
