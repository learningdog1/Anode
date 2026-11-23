package com.nodeseek.app.domain.fleet

import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Pure domain calculator for VPS portfolio CNY cost aggregation, renewal countdowns,
 * and intelligent prefill parsing from Restock monitor items.
 */
object FleetPortfolioCalculator {

    private const val INTERMEDIATE_SCALE = 16
    private val MONTHS_PER_YEAR = BigDecimal("12")
    private val DAYS_PER_YEAR = BigDecimal("365")
    private val ZERO_2 = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)

    /**
     * Computes monthly and annualized CNY cost equivalents for a single [VpsAssetRecord].
     *
     * Returns `(cycleRenewalCny, monthlyCostCnyRaw, annualizedCostCnyRaw)`.
     */
    fun computeAssetCnyCosts(
        asset: VpsAssetRecord,
        rateToCny: BigDecimal,
    ): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val renewalOriginal = asset.parsedRenewalAmount ?: return Triple(ZERO_2, BigDecimal.ZERO, BigDecimal.ZERO)
        val effectiveRate = if (asset.currency == ValuationCurrency.CNY) BigDecimal.ONE else rateToCny
        if (effectiveRate <= BigDecimal.ZERO) return Triple(ZERO_2, BigDecimal.ZERO, BigDecimal.ZERO)

        val cycleCnyRaw = renewalOriginal.multiply(effectiveRate)
        val cycleCny = cycleCnyRaw.setScale(2, RoundingMode.HALF_UP)

        val annualizedRaw = when (asset.cyclePreset) {
            BillingCyclePreset.MONTHLY -> cycleCnyRaw.multiply(BigDecimal("12"))
            BillingCyclePreset.QUARTERLY -> cycleCnyRaw.multiply(BigDecimal("4"))
            BillingCyclePreset.SEMI_ANNUAL -> cycleCnyRaw.multiply(BigDecimal("2"))
            BillingCyclePreset.ANNUAL,
            BillingCyclePreset.CALENDAR_NATURAL,
            -> cycleCnyRaw
            BillingCyclePreset.BIENNIAL -> cycleCnyRaw.divide(BigDecimal("2"), INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
            BillingCyclePreset.TRIENNIAL -> cycleCnyRaw.divide(BigDecimal("3"), INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
            BillingCyclePreset.CUSTOM -> {
                val days = (asset.customCycleDays ?: 365).coerceAtLeast(1)
                cycleCnyRaw.multiply(DAYS_PER_YEAR)
                    .divide(BigDecimal(days), INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
            }
        }
        val monthlyRaw = annualizedRaw.divide(MONTHS_PER_YEAR, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
        return Triple(cycleCny, monthlyRaw, annualizedRaw)
    }

    /**
     * Parses `yyyy-MM-dd` ISO expiry string safely.
     */
    fun parseExpiryDate(expiryDateIso: String): LocalDate? {
        val trimmed = expiryDateIso.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { LocalDate.parse(trimmed) }.getOrNull()
    }

    /**
     * Advances [baseDate] by [cycleCount] billing cycles according to [cyclePreset] and optional
     * [customCycleDays].
     *
     * Calendar-month cycles (`MONTHLY`, `QUARTERLY`, `SEMI_ANNUAL`, `ANNUAL`, `BIENNIAL`,
     * `TRIENNIAL`, `CALENDAR_NATURAL`) advance directly from [baseDate] in whole months so
     * month-end dates (e.g. Jan 31 -> Feb 28/29 -> Mar 31 -> Apr 30) clamp cleanly without
     * cumulative day-of-month drift. `CUSTOM` advances by `customCycleDays` days (defaulting to
     * 365 and floored at 1).
     */
    fun advanceDateByCycles(
        baseDate: LocalDate,
        cyclePreset: BillingCyclePreset,
        customCycleDays: Int? = null,
        cycleCount: Int = 1,
        anchorDayOfMonth: Int? = null,
    ): LocalDate {
        val safeCycles = cycleCount.coerceAtLeast(1).toLong()
        val monthsPerCycle = monthsForCyclePreset(cyclePreset)
        return if (monthsPerCycle != null) {
            addMonthsPreservingAnchor(
                baseDate = baseDate,
                totalMonths = monthsPerCycle.toLong() * safeCycles,
                anchorDayOfMonth = anchorDayOfMonth,
            )
        } else {
            val stepDays = (customCycleDays ?: 365).coerceAtLeast(1).toLong()
            baseDate.plusDays(stepDays * safeCycles)
        }
    }

    /**
     * Computes the next expiry date and number of billing cycles advanced when confirming renewal
     * on [today].
     *
     * - If [currentExpiryDate] is after [today], advances by exactly 1 cycle.
     * - If [currentExpiryDate] is on or before [today], rolls forward the minimum number of cycles
     *   (`>= 1`) so the new expiry date is strictly after [today], preserving the original anchor
     *   day-of-month across month-end clamping.
     * - If [currentExpiryDate] is `null`, starts from [today] and advances 1 cycle.
     */
    fun computeNextRenewalDate(
        currentExpiryDate: LocalDate?,
        cyclePreset: BillingCyclePreset,
        customCycleDays: Int? = null,
        today: LocalDate = VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID)),
        anchorDayOfMonth: Int? = null,
    ): Pair<LocalDate, Int> {
        if (currentExpiryDate == null) {
            return advanceDateByCycles(today, cyclePreset, customCycleDays, 1, anchorDayOfMonth) to 1
        }
        val effectiveAnchor = anchorDayOfMonth?.takeIf { it in 1..31 } ?: currentExpiryDate.dayOfMonth
        if (currentExpiryDate.isAfter(today)) {
            return advanceDateByCycles(
                baseDate = currentExpiryDate,
                cyclePreset = cyclePreset,
                customCycleDays = customCycleDays,
                cycleCount = 1,
                anchorDayOfMonth = effectiveAnchor,
            ) to 1
        }

        val monthsPerCycle = monthsForCyclePreset(cyclePreset)
        if (monthsPerCycle != null) {
            val approxMonths = ((today.year - currentExpiryDate.year).toLong() * 12L) +
                (today.monthValue - currentExpiryDate.monthValue).toLong()
            var cycles = (approxMonths / monthsPerCycle.toLong()).coerceAtLeast(1L)
            while (
                cycles > 1L &&
                addMonthsPreservingAnchor(currentExpiryDate, (cycles - 1L) * monthsPerCycle, effectiveAnchor).isAfter(today)
            ) {
                cycles--
            }
            while (!addMonthsPreservingAnchor(currentExpiryDate, cycles * monthsPerCycle, effectiveAnchor).isAfter(today)) {
                cycles++
            }
            val nextDate = addMonthsPreservingAnchor(currentExpiryDate, cycles * monthsPerCycle, effectiveAnchor)
            return nextDate to cycles.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        val stepDays = (customCycleDays ?: 365).coerceAtLeast(1).toLong()
        val overdueDays = ChronoUnit.DAYS.between(currentExpiryDate, today).coerceAtLeast(0L)
        val cycles = (overdueDays / stepDays) + 1L
        val nextDate = currentExpiryDate.plusDays(cycles * stepDays)
        return nextDate to cycles.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Produces an [AssetRenewalResult] with [asset]'s expiry date advanced for a confirmed renewal.
     */
    fun applyAssetRenewal(
        asset: VpsAssetRecord,
        today: LocalDate = VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID)),
        nowEpochMillis: Long = System.currentTimeMillis(),
        isAutoRolled: Boolean = false,
    ): AssetRenewalResult {
        val currentExpiry = parseExpiryDate(asset.expiryDateIso)
        val effectiveAnchor = asset.billingAnchorDay?.takeIf { it in 1..31 }
            ?: currentExpiry?.dayOfMonth
            ?: today.dayOfMonth
        val (nextExpiry, cyclesAdvanced) = computeNextRenewalDate(
            currentExpiryDate = currentExpiry,
            cyclePreset = asset.cyclePreset,
            customCycleDays = asset.customCycleDays,
            today = today,
            anchorDayOfMonth = effectiveAnchor,
        )
        val nextExpiryIso = DateTimeFormatter.ISO_LOCAL_DATE.format(nextExpiry)
        val nextAnchorDay = if (monthsForCyclePreset(asset.cyclePreset) != null) effectiveAnchor else null
        val updated = asset.copy(
            expiryDateIso = nextExpiryIso,
            billingAnchorDay = nextAnchorDay,
            updatedAtEpochMillis = nowEpochMillis,
        )
        return AssetRenewalResult(
            updatedAsset = updated,
            previousExpiryDateIso = asset.expiryDateIso.trim(),
            newExpiryDateIso = nextExpiryIso,
            cyclesAdvanced = cyclesAdvanced,
            isAutoRolled = isAutoRolled,
        )
    }

    private fun addMonthsPreservingAnchor(
        baseDate: LocalDate,
        totalMonths: Long,
        anchorDayOfMonth: Int?,
    ): LocalDate {
        val shifted = baseDate.plusMonths(totalMonths)
        val anchor = anchorDayOfMonth?.takeIf { it in 1..31 } ?: return shifted
        return shifted.withDayOfMonth(minOf(anchor, shifted.lengthOfMonth()))
    }

    /**
     * Auto-rolls [asset] iff [VpsAssetRecord.autoRenew] is enabled and its expiry date is strictly
     * before [today]. Returns `null` when no auto-roll is due.
     */
    fun autoRollIfExpired(
        asset: VpsAssetRecord,
        today: LocalDate = VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID)),
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): AssetRenewalResult? {
        if (!asset.autoRenew) return null
        val expiryDate = parseExpiryDate(asset.expiryDateIso) ?: return null
        if (!expiryDate.isBefore(today)) return null
        return applyAssetRenewal(
            asset = asset,
            today = today,
            nowEpochMillis = nowEpochMillis,
            isAutoRolled = true,
        )
    }

    private fun monthsForCyclePreset(cyclePreset: BillingCyclePreset): Int? = when (cyclePreset) {
        BillingCyclePreset.MONTHLY -> 1
        BillingCyclePreset.QUARTERLY -> 3
        BillingCyclePreset.SEMI_ANNUAL -> 6
        BillingCyclePreset.ANNUAL,
        BillingCyclePreset.CALENDAR_NATURAL,
        -> 12
        BillingCyclePreset.BIENNIAL -> 24
        BillingCyclePreset.TRIENNIAL -> 36
        BillingCyclePreset.CUSTOM -> null
    }

    /**
     * Classifies expiry urgency given [daysRemaining] and the asset's configured reminder window.
     */
    fun classifyUrgency(
        daysRemaining: Long,
        remindDaysBeforeExpiry: Int,
    ): AssetExpiryUrgency {
        val reminderWindow = remindDaysBeforeExpiry.coerceAtLeast(1).toLong()
        return when {
            daysRemaining < 0L -> AssetExpiryUrgency.EXPIRED
            daysRemaining <= 3L -> AssetExpiryUrgency.CRITICAL
            daysRemaining <= reminderWindow -> AssetExpiryUrgency.WARNING
            else -> AssetExpiryUrgency.NORMAL
        }
    }

    /**
     * Builds the aggregated [FleetPortfolioSummary] from current assets, synced probe snapshots,
     * and live/cached [FxRatesState].
     */
    fun summarize(
        assets: List<VpsAssetRecord>,
        snapshots: List<ProbeServerSnapshot>,
        fxRatesState: FxRatesState? = null,
        today: LocalDate = VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID)),
    ): FleetPortfolioSummary {
        var totalMonthlyRaw = BigDecimal.ZERO
        var totalAnnualRaw = BigDecimal.ZERO
        val renewals = mutableListOf<UpcomingRenewalItem>()

        for (asset in assets) {
            val rate = fxRatesState?.rateFor(asset.currency) ?: asset.currency.defaultCnyRate
            val (cycleCny, monthlyRaw, annualRaw) = computeAssetCnyCosts(asset, rate)
            totalMonthlyRaw = totalMonthlyRaw.add(monthlyRaw)
            totalAnnualRaw = totalAnnualRaw.add(annualRaw)

            val expiryDate = parseExpiryDate(asset.expiryDateIso)
            if (expiryDate != null) {
                val daysRemaining = ChronoUnit.DAYS.between(today, expiryDate)
                val urgency = classifyUrgency(daysRemaining, asset.remindDaysBeforeExpiry)
                val withinWindow = asset.remindDaysBeforeExpiry > 0 &&
                    daysRemaining <= asset.remindDaysBeforeExpiry.toLong()
                val (nextExpiryDate, cyclesToAdvance) = computeNextRenewalDate(
                    currentExpiryDate = expiryDate,
                    cyclePreset = asset.cyclePreset,
                    customCycleDays = asset.customCycleDays,
                    today = today,
                    anchorDayOfMonth = asset.billingAnchorDay,
                )
                renewals += UpcomingRenewalItem(
                    asset = asset,
                    daysRemaining = daysRemaining,
                    urgency = urgency,
                    renewalCny = cycleCny,
                    monthlyCostCny = monthlyRaw.setScale(2, RoundingMode.HALF_UP),
                    annualizedCostCny = annualRaw.setScale(2, RoundingMode.HALF_UP),
                    isWithinReminderWindow = withinWindow,
                    nextExpiryAfterRenewalIso = DateTimeFormatter.ISO_LOCAL_DATE.format(nextExpiryDate),
                    cyclesToAdvanceOnRenewal = cyclesToAdvance,
                )
            }
        }

        val sortedRenewals = renewals.sortedWith(
            compareBy<UpcomingRenewalItem> { it.daysRemaining }
                .thenBy { it.asset.nickname.lowercase() },
        )

        val onlineCount = snapshots.count { it.isOnline }
        val offlineCount = (snapshots.size - onlineCount).coerceAtLeast(0)
        val dueRenewals = sortedRenewals.filter { it.isRenewalDue }
        val expiredCount = sortedRenewals.count { it.urgency == AssetExpiryUrgency.EXPIRED }
        val dueSoonCount = dueRenewals.size

        return FleetPortfolioSummary(
            totalMachineCount = assets.size,
            totalProbeNodeCount = snapshots.size,
            onlineProbeCount = onlineCount,
            offlineProbeCount = offlineCount,
            totalMonthlyCostCny = totalMonthlyRaw.setScale(2, RoundingMode.HALF_UP),
            totalAnnualizedCostCny = totalAnnualRaw.setScale(2, RoundingMode.HALF_UP),
            upcomingRenewals = sortedRenewals,
            dueSoonRenewalCount = dueSoonCount,
            dueRenewals = dueRenewals,
            expiredRenewalCount = expiredCount,
        )
    }

    /**
     * Parses a Restock watch item's title, price hint, and purchase URL into an intelligent
     * asset form draft for one-click ledger import.
     */
    fun parseRestockPrefill(prefill: RestockAssetPrefill): ParsedRestockAssetDraft {
        val cleanName = prefill.name.trim()
        val cleanHint = prefill.priceHint.trim()
        val combined = "$cleanName $cleanHint".trim()

        val currency = detectCurrency(cleanHint.ifBlank { combined })
        val amount = extractDecimalAmount(cleanHint) ?: extractDecimalAmount(cleanName) ?: ""
        val cycle = detectBillingCycle(cleanHint.ifBlank { combined })
        val provider = extractProviderHint(cleanName, prefill.purchaseUrl)
        val region = extractRegionHint(cleanName)
        val specs = extractSpecsHint(cleanName)

        return ParsedRestockAssetDraft(
            nickname = cleanName.ifBlank { "新购 VPS" },
            providerName = provider,
            specsSummary = specs,
            region = region,
            renewalAmount = amount,
            currency = currency,
            cyclePreset = cycle,
            purchaseUrl = prefill.purchaseUrl.trim(),
            notes = if (cleanHint.isNotBlank()) "补货监控导入（参考价：$cleanHint）" else "由补货监控导入",
        )
    }

    private fun detectCurrency(text: String): ValuationCurrency {
        val upper = text.uppercase()
        return when {
            upper.contains("HK$") || upper.contains("HKD") -> ValuationCurrency.HKD
            upper.contains("S$") || upper.contains("SGD") -> ValuationCurrency.SGD
            upper.contains("C$") || upper.contains("CA$") || upper.contains("CAD") -> ValuationCurrency.CAD
            upper.contains("A$") || upper.contains("AU$") || upper.contains("AUD") -> ValuationCurrency.AUD
            upper.contains("NT$") || upper.contains("TWD") || upper.contains("新台币") -> ValuationCurrency.TWD
            upper.contains("₩") || upper.contains("KRW") || upper.contains("韩元") -> ValuationCurrency.KRW
            upper.contains("CHF") || upper.contains("瑞郎") -> ValuationCurrency.CHF
            upper.contains("₽") || upper.contains("RUB") || upper.contains("卢布") -> ValuationCurrency.RUB
            upper.contains("€") || upper.contains("EUR") -> ValuationCurrency.EUR
            upper.contains("£") || upper.contains("GBP") -> ValuationCurrency.GBP
            upper.contains("JPY") || upper.contains("日元") -> ValuationCurrency.JPY
            upper.contains("¥") || upper.contains("￥") || upper.contains("CNY") || upper.contains("RMB") || upper.contains("元") ->
                ValuationCurrency.CNY
            upper.contains("$") || upper.contains("USD") || upper.contains("刀") -> ValuationCurrency.USD
            else -> ValuationCurrency.USD
        }
    }

    private fun detectBillingCycle(text: String): BillingCyclePreset {
        val lower = text.lowercase()
        return when {
            lower.contains("3yr") || lower.contains("3 year") || lower.contains("三年") -> BillingCyclePreset.TRIENNIAL
            lower.contains("2yr") || lower.contains("2 year") || lower.contains("两年") -> BillingCyclePreset.BIENNIAL
            lower.contains("/mo") || lower.contains("/m") || lower.contains("month") || lower.contains("月付") || lower.contains("/月") ->
                BillingCyclePreset.MONTHLY
            lower.contains("/q") || lower.contains("quarter") || lower.contains("季付") || lower.contains("/季") ->
                BillingCyclePreset.QUARTERLY
            lower.contains("semi") || lower.contains("half") || lower.contains("半年") ->
                BillingCyclePreset.SEMI_ANNUAL
            else -> BillingCyclePreset.ANNUAL
        }
    }

    private val PRICE_AMOUNT_REGEX = Regex("""(?:HK\$|CA\$|AU\$|NT\$|S\$|C\$|A\$|[$€£¥￥₩₽]|USD|EUR|GBP|CNY|RMB|TWD|KRW|CHF|RUB)\s*(\d+(?:\.\d{1,2})?)|(\d+(?:\.\d{1,2})?)\s*(?:USD|EUR|GBP|CNY|RMB|HKD|SGD|CAD|AUD|JPY|TWD|KRW|CHF|RUB|刀|元|韩元|瑞郎|卢布)""", RegexOption.IGNORE_CASE)
    private val GENERIC_NUMBER_REGEX = Regex("""\b(\d+(?:\.\d{1,2})?)\b""")

    private fun extractDecimalAmount(text: String): String? {
        if (text.isBlank()) return null
        val match = PRICE_AMOUNT_REGEX.find(text)
        if (match != null) {
            return match.groupValues[1].ifBlank { match.groupValues[2] }.takeIf { it.isNotBlank() }
        }
        return GENERIC_NUMBER_REGEX.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractProviderHint(name: String, purchaseUrl: String): String {
        val firstToken = name.split(Regex("""[\s\-_/|]+""")).firstOrNull { it.length >= 2 }
        if (!firstToken.isNullOrBlank()) return firstToken
        val host = runCatching { java.net.URI(purchaseUrl).host }.getOrNull().orEmpty()
        return host.removePrefix("www.").removePrefix("my.").removePrefix("billing.").substringBefore('.')
    }

    private val REGION_KEYWORDS = listOf(
        "香港" to "香港", "HK" to "香港", "Hong Kong" to "香港",
        "日本" to "日本", "东京" to "东京", "大阪" to "大阪", "JP" to "日本", "Tokyo" to "东京", "Osaka" to "大阪",
        "新加坡" to "新加坡", "SG" to "新加坡", "Singapore" to "新加坡",
        "洛杉矶" to "洛杉矶", "LA" to "洛杉矶", "Los Angeles" to "洛杉矶",
        "圣何塞" to "圣何塞", "San Jose" to "圣何塞",
        "美国" to "美国", "美西" to "美西", "US" to "美国",
        "德国" to "德国", "法兰克福" to "法兰克福", "DE" to "德国", "Frankfurt" to "德国",
        "荷兰" to "荷兰", "NL" to "荷兰", "英国" to "英国", "UK" to "英国",
        "韩国" to "韩国", "KR" to "韩国", "台湾" to "台湾", "TW" to "台湾",
    )

    private fun extractRegionHint(name: String): String {
        for ((needle, normalized) in REGION_KEYWORDS) {
            if (needle.length == 2 && needle.all { it.isUpperCase() }) {
                if (Regex("""\b$needle\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
                    return normalized
                }
            } else if (name.contains(needle, ignoreCase = true)) {
                return normalized
            }
        }
        return ""
    }

    private val SPEC_TOKEN_REGEX = Regex(
        """\b\d+\s*(?:v?CPU|C|Core|Cores|G|GB|MB|TB|NVMe|SSD)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun extractSpecsHint(name: String): String {
        val matches = SPEC_TOKEN_REGEX.findAll(name).map { it.value.trim() }.distinct().toList()
        return if (matches.size >= 2) matches.joinToString(" / ") else ""
    }
}
