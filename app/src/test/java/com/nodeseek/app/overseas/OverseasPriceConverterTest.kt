package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRefreshOutcome
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasPriceConverter
import com.nodeseek.app.domain.overseas.OverseasPriceCycle
import com.nodeseek.app.domain.overseas.OverseasSource
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal

class OverseasPriceConverterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extractsAndConvertsMultiCurrencyMentionsWithBigDecimalHalfUp() {
        val fx = PreviewOverseasIntelRepository.buildDefaultFxState()
        val text = """
            Black Friday Deals:
            Plan A: ${'$'}14.99/yr in LA
            Plan B: ${'$'}3.50/mo NVMe
            Plan C: €15/year in Frankfurt
            Plan D: £4/m London KVM
            Plan E: CA${'$'}10/yr Toronto
            Plan F: AU${'$'}12/yr Sydney
            Plan G: S${'$'}8/mo Singapore
            Plan H: 15 USD/yr Dallas
        """.trimIndent()

        val tags = OverseasPriceConverter.extractAndConvert(
            title = "",
            body = text,
            fxRatesState = fx,
            maxResults = 10,
        )

        assertEquals(8, tags.size)

        // $14.99/yr * 7.2500 = 108.6775 -> 108.68
        val usdAnnual = tags[0]
        assertEquals("${'$'}14.99/yr", usdAnnual.rawText)
        assertEquals(ValuationCurrency.USD, usdAnnual.currency)
        assertEquals(OverseasPriceCycle.ANNUAL, usdAnnual.cycle)
        assertEquals(BigDecimal("108.68"), usdAnnual.cnyAmount)
        assertEquals("≈ ¥108.68/年", usdAnnual.formattedCny)
        assertEquals(BigDecimal("14.99"), usdAnnual.annualizedUsd)
        assertEquals(BigDecimal("108.68"), usdAnnual.annualizedCny)

        // $3.50/mo -> 3.50 * 7.25 = 25.375 -> 25.38; annualized = $42.00 / ¥304.50
        val usdMonthly = tags[1]
        assertEquals(ValuationCurrency.USD, usdMonthly.currency)
        assertEquals(OverseasPriceCycle.MONTHLY, usdMonthly.cycle)
        assertEquals(BigDecimal("25.38"), usdMonthly.cnyAmount)
        assertEquals("≈ ¥25.38/月", usdMonthly.formattedCny)
        assertEquals(BigDecimal("42.00"), usdMonthly.annualizedUsd)
        assertEquals(BigDecimal("304.50"), usdMonthly.annualizedCny)

        // €15/year * 7.85 = 117.75
        val eurAnnual = tags[2]
        assertEquals(ValuationCurrency.EUR, eurAnnual.currency)
        assertEquals(OverseasPriceCycle.ANNUAL, eurAnnual.cycle)
        assertEquals(BigDecimal("117.75"), eurAnnual.cnyAmount)
        assertEquals("≈ ¥117.75/年", eurAnnual.formattedCny)
        assertNotNull(eurAnnual.annualizedUsd)

        // £4/m * 9.15 = 36.60; annualized CNY = 4 * 12 * 9.15 = 439.20
        val gbpMonthly = tags[3]
        assertEquals(ValuationCurrency.GBP, gbpMonthly.currency)
        assertEquals(OverseasPriceCycle.MONTHLY, gbpMonthly.cycle)
        assertEquals(BigDecimal("36.60"), gbpMonthly.cnyAmount)
        assertEquals("≈ ¥36.60/月", gbpMonthly.formattedCny)
        assertEquals(BigDecimal("439.20"), gbpMonthly.annualizedCny)

        // CA$10/yr * 5.18 = 51.80 (must NOT be double-parsed as USD $10!)
        val cadAnnual = tags[4]
        assertEquals(ValuationCurrency.CAD, cadAnnual.currency)
        assertEquals(BigDecimal("51.80"), cadAnnual.cnyAmount)
        assertEquals("≈ ¥51.80/年", cadAnnual.formattedCny)

        // AU$12/yr * 4.72 = 56.64
        val audAnnual = tags[5]
        assertEquals(ValuationCurrency.AUD, audAnnual.currency)
        assertEquals(BigDecimal("56.64"), audAnnual.cnyAmount)

        // S$8/mo * 5.40 = 43.20
        val sgdMonthly = tags[6]
        assertEquals(ValuationCurrency.SGD, sgdMonthly.currency)
        assertEquals(BigDecimal("43.20"), sgdMonthly.cnyAmount)

        // 15 USD/yr * 7.25 = 108.75
        val suffixUsd = tags[7]
        assertEquals(ValuationCurrency.USD, suffixUsd.currency)
        assertEquals(OverseasPriceCycle.ANNUAL, suffixUsd.cycle)
        assertEquals(BigDecimal("108.75"), suffixUsd.cnyAmount)
    }

    @Test
    fun avoidsFalseCycleMatchesAndHandlesBiennialAndUnknownCycles() {
        val fx = PreviewOverseasIntelRepository.buildDefaultFxState()
        val tags = OverseasPriceConverter.extractAndConvert(
            title = "Pay ${'$'}10 more for upgrade, or ${'$'}24/2yr special!",
            body = "",
            fxRatesState = fx,
        )

        assertEquals(2, tags.size)
        // "$10 more" must NOT treat "more" as "/m" monthly
        assertEquals(OverseasPriceCycle.UNKNOWN, tags[0].cycle)
        assertNull(tags[0].annualizedCny)
        assertEquals("≈ ¥72.50", tags[0].formattedCny)

        // "$24/2yr" -> biennial, annualized = $12.00 / ¥87.00
        assertEquals(OverseasPriceCycle.BIENNIAL, tags[1].cycle)
        assertEquals(BigDecimal("12.00"), tags[1].annualizedUsd)
        assertEquals(BigDecimal("87.00"), tags[1].annualizedCny)
        assertEquals("≈ ¥174.00/两年", tags[1].formattedCny)
    }

    @Test
    fun integratesWithLiveExchangeRateRepositoryAndReconversion() = runTest {
        val server = MockWebServer()
        // Frankfurter base=CNY -> 1 CNY = 0.125 USD => 1 USD = 8.000000 CNY; 1 CNY = 0.10 EUR => 1 EUR = 10.000000 CNY
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"amount":1.0,"base":"CNY","date":"2026-11-19","rates":{"USD":0.125,"EUR":0.10,"GBP":0.10}}"""),
        )
        server.start()
        try {
            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempFolder.root, "fx_cache.json"),
                endpointUrl = server.url("/v1/latest?base=CNY").toString(),
            )
            val outcome = fxRepo.refreshOnline()
            assertTrue(outcome is FxRefreshOutcome.Success)
            val liveFx = (outcome as FxRefreshOutcome.Success).state

            val offer = OverseasDealOffer(
                id = "let-1",
                source = OverseasSource.LET,
                originalTitle = "KVM Deal ${'$'}10/yr",
                originalSummary = "",
                authorOrProvider = "TestHost",
                sourceLink = "https://lowendtalk.com/discussion/1",
                priceMatches = OverseasPriceConverter.extractAndConvert(
                    title = "KVM Deal ${'$'}10/yr and €5/mo",
                    fxRatesState = PreviewOverseasIntelRepository.buildDefaultFxState(),
                ),
            )

            // Reconvert with live 1 USD = 8.00 CNY and 1 EUR = 10.00 CNY
            val updated = OverseasPriceConverter.reconvertOffers(listOf(offer), liveFx).first()
            assertEquals(BigDecimal("80.00"), updated.priceMatches[0].cnyAmount)
            assertEquals("≈ ¥80.00/年", updated.priceMatches[0].formattedCny)
            assertEquals(BigDecimal("50.00"), updated.priceMatches[1].cnyAmount)
            assertEquals("≈ ¥50.00/月", updated.priceMatches[1].formattedCny)

            // Apply manual override to USD = 7.00
            val overriddenFx = fxRepo.setManualOverride(ValuationCurrency.USD, BigDecimal("7.00"))
            val afterOverride = OverseasPriceConverter.reconvertOffers(listOf(updated), overriddenFx).first()
            assertEquals(BigDecimal("70.00"), afterOverride.priceMatches[0].cnyAmount)
        } finally {
            server.shutdown()
        }
    }
}
