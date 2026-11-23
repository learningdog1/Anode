package com.nodeseek.app.domain.valuation

import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRefreshOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class VpsValuationEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fixedCyclePresets_computeExpectedRemainingValueAndDailyCost() {
        val valuation = LocalDateTime.of(2026, 6, 1, 0, 0)
        val cases = listOf(
            BillingCyclePreset.MONTHLY to 15L to Pair("150.00", "10.00"),
            BillingCyclePreset.QUARTERLY to 45L to Pair("150.00", "3.33"),
            BillingCyclePreset.SEMI_ANNUAL to 90L to Pair("150.00", "1.67"),
            BillingCyclePreset.ANNUAL to 182L to Pair("149.59", "0.82"),
            BillingCyclePreset.BIENNIAL to 365L to Pair("150.00", "0.41"),
            BillingCyclePreset.TRIENNIAL to 547L to Pair("149.86", "0.27"),
        )

        cases.forEach { (presetAndDays, expected) ->
            val (preset, remainingDays) = presetAndDays
            val (expectedRemainingCny, expectedDailyCny) = expected
            val outcome = VpsValuationEngine.calculate(
                VpsValuationInput(
                    renewalAmount = BigDecimal("300.00"),
                    currency = ValuationCurrency.CNY,
                    cyclePreset = preset,
                    valuationDateTime = valuation,
                    expiryDateTime = valuation.plusDays(remainingDays),
                )
            )
            assertTrue("Expected success for $preset", outcome is VpsValuationOutcome.Success)
            val result = (outcome as VpsValuationOutcome.Success).result
            assertEquals(BigDecimal(preset.fixedDays!!), result.effectiveCycleDays)
            assertEquals(BigDecimal(expectedRemainingCny), result.remainingValueCny)
            assertEquals(BigDecimal(expectedDailyCny), result.dailyOfficialCostCny)
            assertFalse(result.isExpired)
        }
    }

    @Test
    fun customCyclePreset_validatesBoundsAndComputesProRata() {
        val valuation = LocalDateTime.of(2026, 1, 1, 0, 0)

        val validOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("100.00"),
                currency = ValuationCurrency.USD,
                fxRateToCny = BigDecimal("7.2000"),
                cyclePreset = BillingCyclePreset.CUSTOM,
                customCycleDays = 120,
                valuationDateTime = valuation,
                expiryDateTime = valuation.plusDays(30),
            )
        )
        assertTrue(validOutcome is VpsValuationOutcome.Success)
        val result = (validOutcome as VpsValuationOutcome.Success).result
        // 100 * 7.2 = 720 CNY / 120 days = 6.00 CNY/day; 30 days left = 180.00 CNY (25.00 USD)
        assertEquals(BigDecimal("720.00"), result.cycleRenewalCny)
        assertEquals(BigDecimal("6.00"), result.dailyOfficialCostCny)
        assertEquals(BigDecimal("180.00"), result.remainingValueCny)
        assertEquals(BigDecimal("25.00"), result.remainingValueOriginal)

        val zeroDaysOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("100"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.CUSTOM,
                customCycleDays = 0,
                valuationDateTime = valuation,
                expiryDateTime = valuation.plusDays(10),
            )
        )
        assertTrue(zeroDaysOutcome is VpsValuationOutcome.Invalid)

        val overLimitOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("100"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.CUSTOM,
                customCycleDays = 3661,
                valuationDateTime = valuation,
                expiryDateTime = valuation.plusDays(10),
            )
        )
        assertTrue(overLimitOutcome is VpsValuationOutcome.Invalid)
    }

    @Test
    fun invalidInputs_returnDetailedValidationErrors() {
        val now = LocalDateTime.of(2026, 5, 1, 0, 0)
        val outcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                mode = ValuationMode.TRANSFER_DEAL,
                renewalAmount = BigDecimal("-10"),
                currency = ValuationCurrency.USD,
                fxRateToCny = BigDecimal.ZERO,
                cyclePreset = BillingCyclePreset.ANNUAL,
                valuationDateTime = now,
                expiryDateTime = now.plusDays(30),
                askingPriceCny = BigDecimal("-5"),
                pushFeeAmount = BigDecimal("5"),
                pushFeeCurrency = ValuationCurrency.EUR,
                pushFeeToCnyRate = BigDecimal.ZERO,
                middlemanEnabled = true,
                middlemanFeeMode = MiddlemanFeeMode.PERCENT,
                middlemanFeeValue = BigDecimal("105"),
            )
        )
        assertTrue(outcome is VpsValuationOutcome.Invalid)
        val errors = (outcome as VpsValuationOutcome.Invalid).errors
        assertTrue(errors.size >= 5)
    }

    @Test
    fun calendarNaturalCycle_handlesLeapYear366DaysAndExplicitStart() {
        // 2023-03-01 to 2024-03-01 crosses Feb 29, 2024 => 366 days
        val leapSpanOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("366.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.CALENDAR_NATURAL,
                naturalCycleSpan = NaturalCycleSpan.YEAR,
                valuationDateTime = LocalDateTime.of(2023, 9, 1, 0, 0), // 182 days left to 2024-03-01
                expiryDateTime = LocalDateTime.of(2024, 3, 1, 0, 0),
            )
        )
        assertTrue(leapSpanOutcome is VpsValuationOutcome.Success)
        val leapResult = (leapSpanOutcome as VpsValuationOutcome.Success).result
        assertEquals(0, leapResult.effectiveCycleDays.compareTo(BigDecimal("366")))
        assertEquals(LocalDateTime.of(2023, 3, 1, 0, 0), leapResult.resolvedCycleStartDateTime)
        assertEquals(BigDecimal("1.00"), leapResult.dailyOfficialCostCny)
        assertEquals(BigDecimal("182.00"), leapResult.remainingValueCny)

        // Expiry on leap day itself: 2024-02-29 minus 12 months => 2023-02-28 => 366 days
        val leapDayOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("366.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.CALENDAR_NATURAL,
                naturalCycleSpan = NaturalCycleSpan.YEAR,
                valuationDateTime = LocalDateTime.of(2024, 2, 19, 0, 0), // 10 days before 2024-02-29
                expiryDateTime = LocalDateTime.of(2024, 2, 29, 0, 0),
            )
        )
        assertTrue(leapDayOutcome is VpsValuationOutcome.Success)
        val leapDayResult = (leapDayOutcome as VpsValuationOutcome.Success).result
        assertEquals(0, leapDayResult.effectiveCycleDays.compareTo(BigDecimal("366")))
        assertEquals(BigDecimal("10.00"), leapDayResult.remainingValueCny)

        // Following non-leap year 2024-03-01 to 2025-03-01 => 365 days
        val nonLeapOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("365.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.CALENDAR_NATURAL,
                naturalCycleSpan = NaturalCycleSpan.YEAR,
                valuationDateTime = LocalDateTime.of(2025, 1, 1, 0, 0), // 59 days to 2025-03-01
                expiryDateTime = LocalDateTime.of(2025, 3, 1, 0, 0),
            )
        )
        assertTrue(nonLeapOutcome is VpsValuationOutcome.Success)
        val nonLeapResult = (nonLeapOutcome as VpsValuationOutcome.Success).result
        assertEquals(0, nonLeapResult.effectiveCycleDays.compareTo(BigDecimal("365")))
        assertEquals(BigDecimal("59.00"), nonLeapResult.remainingValueCny)

        // Explicit previousRenewalDateTime >= expiryDateTime is rejected
        val invalidPrevOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("100.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.CALENDAR_NATURAL,
                previousRenewalDateTime = LocalDateTime.of(2025, 3, 1, 0, 0),
                valuationDateTime = LocalDateTime.of(2025, 1, 1, 0, 0),
                expiryDateTime = LocalDateTime.of(2025, 3, 1, 0, 0),
            )
        )
        assertTrue(invalidPrevOutcome is VpsValuationOutcome.Invalid)
    }

    @Test
    fun minutePrecisionAndShanghaiClock_computeExactFractionalDays() {
        // UTC 2026-05-01T16:30:00Z == Asia/Shanghai 2026-05-02T00:30:00
        val clock = Clock.fixed(Instant.parse("2026-05-01T16:30:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 5, 2), VpsValuationEngine.todayInShanghai(clock))
        assertEquals(
            LocalDateTime.of(2026, 5, 2, 0, 0),
            VpsValuationEngine.defaultValuationDateTime(preciseToMinute = false, clock = clock),
        )
        assertEquals(
            LocalDateTime.of(2026, 5, 2, 0, 30),
            VpsValuationEngine.defaultValuationDateTime(preciseToMinute = true, clock = clock),
        )

        val valuationDt = LocalDateTime.of(2026, 5, 1, 12, 0)
        val expiryDt = LocalDateTime.of(2026, 5, 11, 0, 0)

        val minuteOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("300.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.MONTHLY,
                valuationDateTime = valuationDt,
                expiryDateTime = expiryDt,
                preciseToMinute = true,
            )
        ) as VpsValuationOutcome.Success
        // Exactly 9.5 days of 30 => 95.00 CNY
        assertEquals(0, minuteOutcome.result.remainingDays.compareTo(BigDecimal("9.5")))
        assertEquals(BigDecimal("95.00"), minuteOutcome.result.remainingValueCny)

        val dateOnlyOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("300.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.MONTHLY,
                valuationDateTime = valuationDt,
                expiryDateTime = expiryDt,
                preciseToMinute = false,
            )
        ) as VpsValuationOutcome.Success
        // Normalized to midnight: 2026-05-01 00:00 -> 2026-05-11 00:00 = 10 days => 100.00 CNY
        assertEquals(0, dateOnlyOutcome.result.remainingDays.compareTo(BigDecimal("10")))
        assertEquals(BigDecimal("100.00"), dateOnlyOutcome.result.remainingValueCny)
    }

    @Test
    fun expiredAndOverCycleEdgeCases_clampSafelyWithoutDivisionByZero() {
        val valuation = LocalDateTime.of(2026, 5, 10, 0, 0)

        val expiredOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                mode = ValuationMode.TRANSFER_DEAL,
                renewalAmount = BigDecimal("100.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.ANNUAL,
                valuationDateTime = valuation,
                expiryDateTime = valuation.minusDays(2),
                askingPriceCny = BigDecimal("20.00"),
            )
        ) as VpsValuationOutcome.Success

        assertTrue(expiredOutcome.result.isExpired)
        assertEquals(0L, expiredOutcome.result.remainingSeconds)
        assertEquals(0, expiredOutcome.result.remainingDays.compareTo(BigDecimal.ZERO))
        assertEquals(0f, expiredOutcome.result.remainingRatio, 0.0001f)
        assertEquals(BigDecimal("0.00"), expiredOutcome.result.remainingValueCny)
        assertEquals(BigDecimal("0.00"), expiredOutcome.result.remainingValueOriginal)
        val expiredDeal = expiredOutcome.result.transferBreakdown!!
        assertEquals(DealSpreadStatus.PREMIUM, expiredDeal.spreadStatus)
        assertEquals(BigDecimal("20.00"), expiredDeal.buyerPremiumCny)
        assertNull(expiredDeal.buyerPremiumPercent)
        assertNull(expiredDeal.buyerEffectiveDailyCostCny)

        val exactBoundary = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("100.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.MONTHLY,
                valuationDateTime = valuation,
                expiryDateTime = valuation,
            )
        ) as VpsValuationOutcome.Success
        assertTrue(exactBoundary.result.isExpired)
        assertEquals(BigDecimal("0.00"), exactBoundary.result.remainingValueCny)

        // Multi-cycle prepaid expiry (e.g. 60 days left on a 30-day cycle)
        val prepaidOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                renewalAmount = BigDecimal("30.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.MONTHLY,
                valuationDateTime = valuation,
                expiryDateTime = valuation.plusDays(60),
            )
        ) as VpsValuationOutcome.Success
        assertEquals(1f, prepaidOutcome.result.remainingRatio, 0.0001f)
        assertEquals(BigDecimal("60.00"), prepaidOutcome.result.remainingValueCny)
    }

    @Test
    fun allocateFee_guaranteesExactSumOnOddCentSplits() {
        val (b1, s1) = VpsValuationEngine.allocateFee(BigDecimal("0.01"), FeePayer.SPLIT)
        assertEquals(BigDecimal("0.01"), b1)
        assertEquals(BigDecimal("0.00"), s1)
        assertEquals(BigDecimal("0.01"), b1.add(s1))

        val (b2, s2) = VpsValuationEngine.allocateFee(BigDecimal("15.55"), FeePayer.SPLIT)
        assertEquals(BigDecimal("7.78"), b2)
        assertEquals(BigDecimal("7.77"), s2)
        assertEquals(BigDecimal("15.55"), b2.add(s2))

        val (bBuyer, sBuyer) = VpsValuationEngine.allocateFee(BigDecimal("25.00"), FeePayer.BUYER)
        assertEquals(BigDecimal("25.00"), bBuyer)
        assertEquals(BigDecimal("0.00"), sBuyer)

        val (bSeller, sSeller) = VpsValuationEngine.allocateFee(BigDecimal("25.00"), FeePayer.SELLER)
        assertEquals(BigDecimal("0.00"), bSeller)
        assertEquals(BigDecimal("25.00"), sSeller)
    }

    @Test
    fun transferDeal_computesFeesDiscountParAndPremiumAccurately() {
        val valuation = LocalDateTime.of(2026, 1, 1, 0, 0)
        // Renewal = 365 CNY / 365d, 100 days remaining => net remaining value = 100.00 CNY
        val expiry = valuation.plusDays(100)

        // 1. Discount with USD push fee split (5.55 USD * 1.00 = 5.55 -> buyer 2.78, seller 2.77)
        // + 3% middleman on 80.00 asking (= 2.40) paid by seller
        val discountOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                mode = ValuationMode.TRANSFER_DEAL,
                renewalAmount = BigDecimal("365.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.ANNUAL,
                valuationDateTime = valuation,
                expiryDateTime = expiry,
                askingPriceCny = BigDecimal("80.00"),
                pushFeeAmount = BigDecimal("5.55"),
                pushFeeCurrency = ValuationCurrency.USD,
                pushFeeToCnyRate = BigDecimal("1.0000"),
                pushFeePayer = FeePayer.SPLIT,
                middlemanEnabled = true,
                middlemanFeeMode = MiddlemanFeeMode.PERCENT,
                middlemanFeeValue = BigDecimal("3"),
                middlemanFeePayer = FeePayer.SELLER,
            )
        ) as VpsValuationOutcome.Success

        val discountDeal = discountOutcome.result.transferBreakdown!!
        assertEquals(BigDecimal("2.78"), discountDeal.buyerPushFeeCny)
        assertEquals(BigDecimal("2.77"), discountDeal.sellerPushFeeCny)
        assertEquals(BigDecimal("2.40"), discountDeal.middlemanFeeTotalCny)
        assertEquals(BigDecimal("0.00"), discountDeal.buyerMiddlemanFeeCny)
        assertEquals(BigDecimal("2.40"), discountDeal.sellerMiddlemanFeeCny)
        assertEquals(BigDecimal("82.78"), discountDeal.buyerTotalCostCny)
        // Seller receives 80.00 - 2.77 - 2.40 = 74.83
        assertEquals(BigDecimal("74.83"), discountDeal.sellerNetProceedsCny)
        // Net remaining 100.00, buyer cost 82.78 => -17.22 (-17.22%)
        assertEquals(DealSpreadStatus.DISCOUNT, discountDeal.spreadStatus)
        assertEquals(BigDecimal("-17.22"), discountDeal.buyerPremiumCny)
        assertEquals(BigDecimal("-17.22"), discountDeal.buyerPremiumPercent)
        // 82.78 / 100 = 0.83 CNY/day
        assertEquals(BigDecimal("0.83"), discountDeal.buyerEffectiveDailyCostCny)

        // 2. Par (asking 95 + fixed middleman 5 buyer = 100 == remaining 100)
        val parOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                mode = ValuationMode.TRANSFER_DEAL,
                renewalAmount = BigDecimal("365.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.ANNUAL,
                valuationDateTime = valuation,
                expiryDateTime = expiry,
                askingPriceCny = BigDecimal("95.00"),
                middlemanEnabled = true,
                middlemanFeeMode = MiddlemanFeeMode.FIXED_CNY,
                middlemanFeeValue = BigDecimal("5.00"),
                middlemanFeePayer = FeePayer.BUYER,
            )
        ) as VpsValuationOutcome.Success

        val parDeal = parOutcome.result.transferBreakdown!!
        assertEquals(DealSpreadStatus.PAR, parDeal.spreadStatus)
        assertEquals(BigDecimal("0.00"), parDeal.buyerPremiumCny)
        assertEquals(BigDecimal("0.00"), parDeal.buyerPremiumPercent)

        // 3. Premium (asking 130 + 10 push buyer = 140 => +40.00, +40.00%)
        val premiumOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                mode = ValuationMode.TRANSFER_DEAL,
                renewalAmount = BigDecimal("365.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.ANNUAL,
                valuationDateTime = valuation,
                expiryDateTime = expiry,
                askingPriceCny = BigDecimal("130.00"),
                pushFeeAmount = BigDecimal("10.00"),
                pushFeeCurrency = ValuationCurrency.CNY,
                pushFeePayer = FeePayer.BUYER,
            )
        ) as VpsValuationOutcome.Success

        val premiumDeal = premiumOutcome.result.transferBreakdown!!
        assertEquals(DealSpreadStatus.PREMIUM, premiumDeal.spreadStatus)
        assertEquals(BigDecimal("40.00"), premiumDeal.buyerPremiumCny)
        assertEquals(BigDecimal("40.00"), premiumDeal.buyerPremiumPercent)
    }

    @Test
    fun markdownTemplates_produceDistinctOriginalFormatsAndEscapePipes() {
        val valuation = LocalDateTime.of(2026, 4, 1, 0, 0)
        val result = (
            VpsValuationEngine.calculate(
                VpsValuationInput(
                    machineLabel = "DMIT LAX | Pro",
                    mode = ValuationMode.TRANSFER_DEAL,
                    renewalAmount = BigDecimal("36.90"),
                    currency = ValuationCurrency.USD,
                    fxRateToCny = BigDecimal("7.2500"),
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    valuationDateTime = valuation,
                    expiryDateTime = valuation.plusDays(180),
                    askingPriceCny = BigDecimal("180.00"),
                    pushFeeAmount = BigDecimal("8.00"),
                    pushFeeCurrency = ValuationCurrency.CNY,
                    pushFeePayer = FeePayer.SPLIT,
                )
            ) as VpsValuationOutcome.Success
            ).result

        val summaryMd = VpsValuationEngine.formatMarkdown(
            result = result,
            style = ValuationMarkdownStyle.SUMMARY_CARD,
            fxSourceNote = "在线同步 2026-04-01",
        )
        assertTrue(summaryMd.startsWith("> **【VPS 估值折算】** DMIT LAX | Pro"))
        assertTrue(summaryMd.contains("- **净剩余价值**：**¥"))
        assertTrue(summaryMd.contains("- **交易核算**：出让 ¥180.00"))
        assertFalse(summaryMd.contains("剩余价值计算器"))
        assertFalse(summaryMd.contains("🧮"))
        assertFalse(summaryMd.contains("| 核算项 |"))

        val tableMd = VpsValuationEngine.formatMarkdown(
            result = result,
            style = ValuationMarkdownStyle.THREE_COLUMN_TABLE,
            fxSourceNote = "在线同步 2026-04-01",
        )
        assertTrue(tableMd.contains("| 核算项 | 折算数值 | 口径说明 |"))
        assertTrue(tableMd.contains("| :--- | ---: | :--- |"))
        // Machine label pipe is escaped so GFM table row does not split into extra columns
        assertTrue(tableMd.contains("DMIT LAX \\| Pro"))
        assertTrue(tableMd.contains("| **净剩余价值** |"))
        assertTrue(tableMd.contains("| **差价评估** |"))
        assertTrue(tableMd.contains("> 估值基准：2026-04-01"))
        assertFalse(tableMd.contains("剩余价值计算器"))
        assertFalse(tableMd.contains("🧮"))
    }

    @Test
    fun exchangeRateRepository_invertsFrankfurterBaseCnyRespectsTtlAndManualOverrides() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            var nowMillis = 1_700_000_000_000L
            val cacheFile = File(tempFolder.newFolder("fx"), ExchangeRateRepository.CACHE_FILE_NAME)
            val endpoint = server.url("/v1/latest?base=CNY").toString()

            val firstRepo = ExchangeRateRepository(
                cacheFile = cacheFile,
                endpointUrl = endpoint,
                ioDispatcher = Dispatchers.IO,
                nowMillisProvider = { nowMillis },
            )

            // 1. Fresh fetch from mock Frankfurter (1 CNY = 0.125 USD => 1 USD = 8.000000 CNY; 1 CNY = 20 JPY => 0.050000 CNY)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"amount":1.0,"base":"CNY","date":"2026-05-10","rates":{"USD":0.125,"JPY":20.0}}""")
            )

            val firstFetch = firstRepo.ensureFreshRates()
            assertTrue(firstFetch is FxRefreshOutcome.Success)
            val firstSuccess = firstFetch as FxRefreshOutcome.Success
            assertFalse(firstSuccess.fromCache)
            assertEquals("2026-05-10", firstSuccess.state.providerDate)
            assertEquals(0, firstSuccess.state.rateFor(ValuationCurrency.USD).compareTo(BigDecimal("8")))
            assertEquals(0, firstSuccess.state.rateFor(ValuationCurrency.JPY).compareTo(BigDecimal("0.05")))
            assertEquals(FxRateSource.ONLINE_FRESH, firstSuccess.state.quoteFor(ValuationCurrency.USD).source)
            // Currencies omitted from ECB feed keep offline defaults cleanly
            assertEquals(FxRateSource.OFFLINE_DEFAULT, firstSuccess.state.quoteFor(ValuationCurrency.TWD).source)
            assertEquals(1, server.requestCount)

            // 2. Within 24h TTL -> served from cache without hitting network
            nowMillis += 2 * 60 * 60 * 1000L
            val cachedFetch = firstRepo.ensureFreshRates()
            assertTrue(cachedFetch is FxRefreshOutcome.Success)
            assertTrue((cachedFetch as FxRefreshOutcome.Success).fromCache)
            assertEquals(1, server.requestCount)

            // 3. Past 24h TTL -> HTTP 500 falls back cleanly while retaining online rates
            nowMillis += ExchangeRateRepository.CACHE_TTL_MILLIS + 1000L
            server.enqueue(MockResponse().setResponseCode(500).setBody("upstream error"))
            val fallbackOutcome = firstRepo.ensureFreshRates()
            assertTrue(fallbackOutcome is FxRefreshOutcome.Fallback)
            val fallback = fallbackOutcome as FxRefreshOutcome.Fallback
            assertNotNull(fallback.state.lastSyncError)
            assertEquals(0, fallback.state.rateFor(ValuationCurrency.USD).compareTo(BigDecimal("8")))
            assertEquals(FxRateSource.ONLINE_CACHED, fallback.state.quoteFor(ValuationCurrency.USD).source)
            assertEquals(2, server.requestCount)

            // 4. Manual override persists across fresh repository instance & clears back to cached online rate
            val overridden = firstRepo.setManualOverride(ValuationCurrency.USD, BigDecimal("7.1234"))
            assertEquals(0, overridden.rateFor(ValuationCurrency.USD).compareTo(BigDecimal("7.1234")))
            assertEquals(FxRateSource.MANUAL_OVERRIDE, overridden.quoteFor(ValuationCurrency.USD).source)
            assertTrue(overridden.quoteFor(ValuationCurrency.USD).hasManualOverride)

            val reloadedRepo = ExchangeRateRepository(
                cacheFile = cacheFile,
                endpointUrl = endpoint,
                ioDispatcher = Dispatchers.IO,
                nowMillisProvider = { nowMillis },
            )
            val reloadedState = reloadedRepo.loadPersistedState()
            assertEquals(0, reloadedState.rateFor(ValuationCurrency.USD).compareTo(BigDecimal("7.1234")))
            assertEquals(FxRateSource.MANUAL_OVERRIDE, reloadedState.quoteFor(ValuationCurrency.USD).source)

            val clearedState = reloadedRepo.clearManualOverride(ValuationCurrency.USD)
            assertFalse(clearedState.quoteFor(ValuationCurrency.USD).hasManualOverride)
            assertEquals(0, clearedState.rateFor(ValuationCurrency.USD).compareTo(BigDecimal("8")))
            assertEquals(FxRateSource.ONLINE_CACHED, clearedState.quoteFor(ValuationCurrency.USD).source)
            assertTrue(clearedState.isCacheExpired)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun currenciesIncludeTryAndBrlWithOnlineSupportAndMarkTwdRubOffline() = runTest {
        assertTrue(ValuationCurrency.entries.contains(ValuationCurrency.TRY))
        assertTrue(ValuationCurrency.entries.contains(ValuationCurrency.BRL))
        assertTrue(ValuationCurrency.TRY.supportsOnlineSync)
        assertTrue(ValuationCurrency.BRL.supportsOnlineSync)
        assertFalse(ValuationCurrency.TWD.supportsOnlineSync)
        assertFalse(ValuationCurrency.RUB.supportsOnlineSync)

        val server = MockWebServer()
        server.start()
        try {
            val cacheFile = File(tempFolder.newFolder("fx_try_brl"), ExchangeRateRepository.CACHE_FILE_NAME)
            val repo = ExchangeRateRepository(
                cacheFile = cacheFile,
                endpointUrl = server.url("/v1/latest?base=CNY").toString(),
                ioDispatcher = Dispatchers.IO,
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"amount":1.0,"base":"CNY","date":"2026-06-01","rates":{"TRY":5.0,"BRL":0.8}}"""),
            )
            val outcome = repo.ensureFreshRates() as FxRefreshOutcome.Success
            assertEquals(0, outcome.state.rateFor(ValuationCurrency.TRY).compareTo(BigDecimal("0.2")))
            assertEquals(0, outcome.state.rateFor(ValuationCurrency.BRL).compareTo(BigDecimal("1.25")))
            assertEquals(FxRateSource.ONLINE_FRESH, outcome.state.quoteFor(ValuationCurrency.TRY).source)
            assertEquals(FxRateSource.ONLINE_FRESH, outcome.state.quoteFor(ValuationCurrency.BRL).source)
            assertFalse(outcome.state.isCacheExpired)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun overCyclePreRenewalPreservesTrueRemainingRatioPercentWhileClampingProgressBar() {
        val valuation = LocalDateTime.of(2026, 6, 1, 0, 0)
        val outcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                machineLabel = "Pre-renewed Annual VPS",
                renewalAmount = BigDecimal("365.00"),
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.ANNUAL,
                valuationDateTime = valuation,
                expiryDateTime = valuation.plusDays(500),
            ),
        ) as VpsValuationOutcome.Success

        val result = outcome.result
        assertEquals(1.0f, result.remainingRatio, 0.0001f)
        assertEquals(BigDecimal("137.0"), result.remainingRatioPercent)
        assertEquals(BigDecimal("500.00"), result.remainingValueCny)

        val markdown = VpsValuationEngine.formatMarkdown(result)
        assertTrue("Expected true 137.0% in Markdown, got:\n$markdown", markdown.contains("137.0%"))
        assertFalse(markdown.contains("100.0%"))
    }

    @Test
    fun exchangeRateRepository_resetsIsRefreshingOnCancellationAndCoalescesConcurrentRefreshes() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val cacheFile = File(tempFolder.newFolder("fx_cancel"), ExchangeRateRepository.CACHE_FILE_NAME)
            val repo = ExchangeRateRepository(
                cacheFile = cacheFile,
                endpointUrl = server.url("/v1/latest?base=CNY").toString(),
                ioDispatcher = Dispatchers.IO,
            )

            // 1. P1-62: Cancelling refresh mid-flight must reset isRefreshing back to false
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeadersDelay(5, TimeUnit.SECONDS)
                    .setBody("""{"amount":1.0,"base":"CNY","date":"2026-06-01","rates":{"USD":0.14}}"""),
            )
            val job = launch(Dispatchers.Default) {
                repo.refreshOnline()
            }
            withContext(Dispatchers.Default) {
                var waitedMs = 0L
                while (!repo.state.value.isRefreshing && waitedMs < 2_000L) {
                    delay(10L)
                    waitedMs += 10L
                }
            }
            assertTrue(repo.state.value.isRefreshing)
            job.cancelAndJoin()
            assertFalse("Expected isRefreshing to reset to false after cancellation", repo.state.value.isRefreshing)

            // 2. P3-37: Concurrent ensureFreshRates() calls coalesce into a single HTTP request
            val countBeforeConcurrent = server.requestCount
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeadersDelay(150, TimeUnit.MILLISECONDS)
                    .setBody("""{"amount":1.0,"base":"CNY","date":"2026-06-02","rates":{"USD":0.125}}"""),
            )
            val outcomes = withContext(Dispatchers.Default) {
                listOf(
                    async { repo.ensureFreshRates(force = true) },
                    async { repo.ensureFreshRates(force = true) },
                    async { repo.ensureFreshRates(force = true) },
                ).awaitAll()
            }
            assertTrue(outcomes.all { it is FxRefreshOutcome.Success })
            assertEquals(countBeforeConcurrent + 1, server.requestCount)
            assertFalse(repo.state.value.isRefreshing)
        } finally {
            server.shutdown()
        }
    }
}
