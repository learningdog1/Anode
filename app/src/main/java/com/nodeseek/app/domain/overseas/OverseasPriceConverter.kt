package com.nodeseek.app.domain.overseas

import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.valuation.ValuationCurrency
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Extracts foreign currency VPS prices from English/multilingual offer text and converts them to
 * CNY (and annualized USD / CNY) with [BigDecimal] (`RoundingMode.HALF_UP`, scale 2) backed by
 * [FxRatesState] from `ExchangeRateRepository`.
 */
object OverseasPriceConverter {

    private val MIN_REASONABLE_AMOUNT = BigDecimal("0.10")
    private val MAX_REASONABLE_AMOUNT = BigDecimal("50000")
    private val TWO = BigDecimal("2")
    private val THREE = BigDecimal("3")
    private val FOUR = BigDecimal("4")
    private val TWELVE = BigDecimal("12")

    // Cycle tail captured optionally after a price amount.
    // Single-letter /m or /y only matches after explicit slash (`/`) to avoid matching words like "more" / "you".
    private const val CYCLE_SUFFIX =
        """(?:\s*(?:(?:/|per\s+|every\s+)\s*(?:3\s*(?:years?|yrs?|y)|2\s*(?:years?|yrs?|y)|6\s*(?:months?|mos?|m)|3\s*(?:months?|mos?|m)|triennial(?:ly)?|biennial(?:ly)?|semi[\-\s]?annual(?:ly)?|half[\-\s]?year|quarter(?:ly)?|qtr|annual(?:ly)?|yearly|years?|yrs?|yr|monthly|months?|mos?|mo|y|m)|(?:triennial(?:ly)?|biennial(?:ly)?|semi[\-\s]?annual(?:ly)?|quarterly|annually|yearly|monthly|one[\-\s]?time|lifetime))(?![A-Za-z]))?"""

    private const val AMOUNT_PATTERN = """(\d{1,5}(?:,\d{3})*(?:\.\d{1,2})?)"""

    // 1. Prefix currency symbols: CA$, C$, AU$, A$, SG$, S$, HK$, NT$, US$, $, €, £, ¥, ₹/₽, CHF
    private val PREFIX_SYMBOL_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(CAD\$|CA\$|C\$|AUD\$|AU\$|A\$|SGD\$|SG\$|S\$|HKD\$|HK\$|TWD\$|NT\$|USD\$|US\$|CHF\s*|\$|€|£|¥|₽)\s*$AMOUNT_PATTERN$CYCLE_SUFFIX""",
        option = RegexOption.IGNORE_CASE,
    )

    // 2. Prefix 3-letter ISO code: USD 14.99/yr, EUR 12/mo, GBP 4/m, CAD 10/yr
    private val PREFIX_CODE_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9])(USD|EUR|GBP|CAD|AUD|SGD|HKD|JPY|TWD|KRW|CHF|RUB|CNY|RMB)\s+$AMOUNT_PATTERN$CYCLE_SUFFIX""",
        option = RegexOption.IGNORE_CASE,
    )

    // 3. Suffix currency code or European symbol: 15 USD/yr, 12.50 EUR/mo, 15€/year, 9.99£/m
    private val SUFFIX_CURRENCY_REGEX = Regex(
        pattern = """(?<![A-Za-z0-9$€£¥.])$AMOUNT_PATTERN\s*(USD|EUR|GBP|CAD|AUD|SGD|HKD|JPY|TWD|KRW|CHF|RUB|CNY|RMB|€|£)$CYCLE_SUFFIX""",
        option = RegexOption.IGNORE_CASE,
    )

    private data class RawPriceHit(
        val startIndex: Int,
        val endIndex: Int,
        val tag: ConvertedPriceTag,
    )

    /**
     * Extracts all currency price mentions across [title] (prioritized first) and [body],
     * converting each to CNY using [fxRatesState].
     */
    fun extractAndConvert(
        title: String,
        body: String = "",
        fxRatesState: FxRatesState,
        maxResults: Int = 6,
    ): List<ConvertedPriceTag> {
        val titleHits = extractFromSingleText(title, fxRatesState)
        val bodyHits = if (body.isNotBlank()) extractFromSingleText(body, fxRatesState) else emptyList()

        val seenKeys = LinkedHashSet<String>()
        val results = ArrayList<ConvertedPriceTag>()

        for (tag in titleHits + bodyHits) {
            val dedupeKey = "${tag.currency.code}|${tag.originalAmount.stripTrailingZeros().toPlainString()}|${tag.cycle.name}"
            if (seenKeys.add(dedupeKey)) {
                results += tag
                if (results.size >= maxResults) break
            }
        }
        return results
    }

    /**
     * Converts a known [amount] + [currency] + [cycle] into a [ConvertedPriceTag].
     */
    fun convertSingle(
        rawText: String,
        amount: BigDecimal,
        currency: ValuationCurrency,
        cycle: OverseasPriceCycle,
        fxRatesState: FxRatesState,
    ): ConvertedPriceTag {
        val normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP)
        val rateToCny = fxRatesState.rateFor(currency)
        val cnyAmount = amount.multiply(rateToCny).setScale(2, RoundingMode.HALF_UP)

        val annualizedOriginal: BigDecimal? = when (cycle) {
            OverseasPriceCycle.MONTHLY -> amount.multiply(TWELVE)
            OverseasPriceCycle.QUARTERLY -> amount.multiply(FOUR)
            OverseasPriceCycle.SEMI_ANNUAL -> amount.multiply(TWO)
            OverseasPriceCycle.ANNUAL -> amount
            OverseasPriceCycle.BIENNIAL -> amount.divide(TWO, 6, RoundingMode.HALF_UP)
            OverseasPriceCycle.TRIENNIAL -> amount.divide(THREE, 6, RoundingMode.HALF_UP)
            OverseasPriceCycle.ONE_TIME,
            OverseasPriceCycle.UNKNOWN,
            -> null
        }

        val annualizedCny: BigDecimal? = annualizedOriginal
            ?.multiply(rateToCny)
            ?.setScale(2, RoundingMode.HALF_UP)

        val usdRate = fxRatesState.rateFor(ValuationCurrency.USD)
        val annualizedUsd: BigDecimal? = annualizedOriginal?.let { annualOrig ->
            if (currency == ValuationCurrency.USD) {
                annualOrig.setScale(2, RoundingMode.HALF_UP)
            } else if (usdRate > BigDecimal.ZERO) {
                annualOrig.multiply(rateToCny).divide(usdRate, 2, RoundingMode.HALF_UP)
            } else {
                null
            }
        }

        val formattedCny = formatCnyLabel(cnyAmount = cnyAmount, cycle = cycle)
        return ConvertedPriceTag(
            rawText = normalizeRawPriceDisplay(rawText),
            originalAmount = normalizedAmount,
            currency = currency,
            cycle = cycle,
            cnyAmount = cnyAmount,
            formattedCny = formattedCny,
            annualizedUsd = annualizedUsd,
            annualizedCny = annualizedCny,
        )
    }

    /**
     * Recalculates CNY & annualized USD values on existing [ConvertedPriceTag]s after an FX rate refresh.
     */
    fun reconvertTag(tag: ConvertedPriceTag, fxRatesState: FxRatesState): ConvertedPriceTag =
        convertSingle(
            rawText = tag.rawText,
            amount = tag.originalAmount,
            currency = tag.currency,
            cycle = tag.cycle,
            fxRatesState = fxRatesState,
        )

    /**
     * Re-evaluates all price tags on [offers] against the latest [fxRatesState].
     */
    fun reconvertOffers(
        offers: List<OverseasDealOffer>,
        fxRatesState: FxRatesState,
    ): List<OverseasDealOffer> = offers.map { offer ->
        if (offer.priceMatches.isEmpty()) {
            offer
        } else {
            offer.copy(priceMatches = offer.priceMatches.map { reconvertTag(it, fxRatesState) })
        }
    }

    internal fun formatCnyLabel(cnyAmount: BigDecimal, cycle: OverseasPriceCycle): String {
        val scaled = cnyAmount.setScale(2, RoundingMode.HALF_UP).toPlainString()
        return when (cycle) {
            OverseasPriceCycle.UNKNOWN -> "≈ ¥$scaled"
            OverseasPriceCycle.ONE_TIME -> "≈ ¥$scaled/一次性"
            else -> "≈ ¥$scaled/${cycle.labelZh}"
        }
    }

    private fun extractFromSingleText(
        text: String,
        fxRatesState: FxRatesState,
    ): List<ConvertedPriceTag> {
        if (text.isBlank()) return emptyList()
        val hits = ArrayList<RawPriceHit>()

        fun collectMatches(
            regex: Regex,
            tokenGroupIndex: Int,
            amountGroupIndex: Int,
        ) {
            regex.findAll(text).forEach { match ->
                val range = match.range
                // Avoid double-capturing overlapping spans (e.g. `CA$10` matched again as `$10`)
                if (hits.any { existing -> range.first < existing.endIndex && range.last + 1 > existing.startIndex }) {
                    return@forEach
                }

                val rawToken = match.groupValues.getOrNull(tokenGroupIndex).orEmpty()
                val rawAmount = match.groupValues.getOrNull(amountGroupIndex).orEmpty()
                val fullMatched = match.value
                val amount = rawAmount.replace(",", "").toBigDecimalOrNull() ?: return@forEach
                if (amount < MIN_REASONABLE_AMOUNT || amount > MAX_REASONABLE_AMOUNT) return@forEach

                val currency = resolveCurrencyToken(rawToken, amount, text) ?: return@forEach

                val tailStart = match.groups[amountGroupIndex]?.range?.last?.plus(1)
                    ?.coerceAtMost(match.range.last + 1)
                    ?: (match.range.last + 1)
                val suffixText = if (tokenGroupIndex > amountGroupIndex) {
                    val tokenEnd = match.groups[tokenGroupIndex]?.range?.last?.plus(1) ?: tailStart
                    text.substring(tokenEnd.coerceAtMost(match.range.last + 1), match.range.last + 1)
                } else {
                    text.substring(tailStart, match.range.last + 1)
                }

                val cycle = parseCycle(suffixText)
                val tag = convertSingle(
                    rawText = fullMatched,
                    amount = amount,
                    currency = currency,
                    cycle = cycle,
                    fxRatesState = fxRatesState,
                )
                hits += RawPriceHit(
                    startIndex = range.first,
                    endIndex = range.last + 1,
                    tag = tag,
                )
            }
        }

        collectMatches(PREFIX_SYMBOL_REGEX, tokenGroupIndex = 1, amountGroupIndex = 2)
        collectMatches(PREFIX_CODE_REGEX, tokenGroupIndex = 1, amountGroupIndex = 2)
        collectMatches(SUFFIX_CURRENCY_REGEX, tokenGroupIndex = 2, amountGroupIndex = 1)

        return hits
            .sortedBy { it.startIndex }
            .map { it.tag }
    }

    private fun resolveCurrencyToken(
        rawToken: String,
        amount: BigDecimal,
        fullText: String,
    ): ValuationCurrency? {
        val token = rawToken.trim().uppercase(Locale.ROOT)
        return when (token) {
            "$", "US$", "USD$", "USD" -> ValuationCurrency.USD
            "€", "EUR" -> ValuationCurrency.EUR
            "£", "GBP" -> ValuationCurrency.GBP
            "CA$", "C$", "CAD$", "CAD" -> ValuationCurrency.CAD
            "AU$", "A$", "AUD$", "AUD" -> ValuationCurrency.AUD
            "S$", "SG$", "SGD$", "SGD" -> ValuationCurrency.SGD
            "HK$", "HKD$", "HKD" -> ValuationCurrency.HKD
            "NT$", "TWD$", "TWD" -> ValuationCurrency.TWD
            "CHF" -> ValuationCurrency.CHF
            "₽", "RUB" -> ValuationCurrency.RUB
            "JPY" -> ValuationCurrency.JPY
            "KRW" -> ValuationCurrency.KRW
            "CNY", "RMB" -> ValuationCurrency.CNY
            "¥" -> {
                // In English overseas VPS posts, ¥ with large values or Japan context denotes JPY; otherwise CNY.
                val hasJapaneseContext = fullText.contains("JPY", ignoreCase = true) ||
                    fullText.contains("yen", ignoreCase = true) ||
                    fullText.contains("Tokyo", ignoreCase = true) ||
                    fullText.contains("Osaka", ignoreCase = true) ||
                    fullText.contains("Japan", ignoreCase = true)
                if (hasJapaneseContext && amount >= BigDecimal("300")) {
                    ValuationCurrency.JPY
                } else {
                    ValuationCurrency.CNY
                }
            }
            else -> ValuationCurrency.fromCode(token)
        }
    }

    internal fun parseCycle(rawSuffix: String): OverseasPriceCycle {
        val cleaned = rawSuffix
            .trim()
            .lowercase(Locale.ROOT)
            .removePrefix("/")
            .removePrefix("per ")
            .removePrefix("every ")
            .trim()
        if (cleaned.isEmpty()) return OverseasPriceCycle.UNKNOWN

        return when {
            cleaned.contains("triennial") || Regex("""^3\s*(?:years?|yrs?|y)$""").matches(cleaned) ->
                OverseasPriceCycle.TRIENNIAL
            cleaned.contains("biennial") || Regex("""^2\s*(?:years?|yrs?|y)$""").matches(cleaned) ->
                OverseasPriceCycle.BIENNIAL
            cleaned.contains("semi") || cleaned.contains("half") || Regex("""^6\s*(?:months?|mos?|m)$""").matches(cleaned) ->
                OverseasPriceCycle.SEMI_ANNUAL
            cleaned.contains("quarter") || cleaned == "qtr" || Regex("""^3\s*(?:months?|mos?|m)$""").matches(cleaned) ->
                OverseasPriceCycle.QUARTERLY
            cleaned.contains("annual") || cleaned.contains("year") || cleaned == "yr" || cleaned == "yrs" || cleaned == "y" ->
                OverseasPriceCycle.ANNUAL
            cleaned.contains("month") || cleaned == "mo" || cleaned == "mos" || cleaned == "m" ->
                OverseasPriceCycle.MONTHLY
            cleaned.contains("one-time") || cleaned.contains("one time") || cleaned.contains("onetime") || cleaned.contains("lifetime") ->
                OverseasPriceCycle.ONE_TIME
            else -> OverseasPriceCycle.UNKNOWN
        }
    }

    private fun normalizeRawPriceDisplay(rawText: String): String =
        rawText.trim()
            .replace(Regex("""\s*/\s*"""), "/")
            .replace(Regex("""\s+"""), " ")
}
