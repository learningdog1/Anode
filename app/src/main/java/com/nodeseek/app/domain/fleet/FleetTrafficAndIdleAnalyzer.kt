package com.nodeseek.app.domain.fleet

import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * Pure domain analyzer for:
 * 1. Monthly traffic reset cycle calculation (with safe month-end clamping for days 29..31),
 *    burn-rate tracking, and exhaustion date prediction ("预计本月 X 号流量会用完").
 * 2. Rolling 30-day CPU utilization bucket aggregation and pruning.
 * 3. 30-day low-CPU + low-traffic idle server ("闲置") identification with cold-start protection.
 */
object FleetTrafficAndIdleAnalyzer {

    const val TRAFFIC_ALERT_THRESHOLD_RATIO: Double = 0.80
    const val TRAFFIC_ALERT_REARM_RATIO: Double = 0.70
    const val DEFAULT_MIN_ELAPSED_FOR_FORECAST_MILLIS: Long = 6L * 3_600_000L

    private const val MILLIS_PER_DAY: Double = 86_400_000.0
    private const val BYTES_PER_GIB: Double = 1024.0 * 1024.0 * 1024.0

    /**
     * Resolves effective monthly traffic limit in bytes, preferring the probe panel's reported
     * `trafficLimitBytes` and falling back to the linked ledger asset's `monthlyTrafficQuotaGb`.
     */
    fun resolveEffectiveTrafficLimitBytes(
        snapshot: ProbeServerSnapshot,
        linkedAsset: VpsAssetRecord? = null,
    ): Long? {
        snapshot.trafficLimitBytes?.takeIf { it > 0L }?.let { return it }
        val quotaGb = linkedAsset?.monthlyTrafficQuotaGb?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return (quotaGb * BYTES_PER_GIB).toLong().takeIf { it > 0L }
    }

    /**
     * Resolves a server's monthly traffic reset day (`1..31`):
     * 1. Explicit `linkedAsset.trafficResetDay` (`1..31`)
     * 2. Linked asset's `expiryDateIso` day-of-month (`1..31`)
     * 3. Default `1` (1st of month)
     */
    fun resolveTrafficResetDay(
        linkedAsset: VpsAssetRecord?,
    ): Pair<Int, TrafficResetDaySource> {
        val explicit = linkedAsset?.trafficResetDay?.takeIf { it in 1..31 }
        if (explicit != null) {
            return explicit to TrafficResetDaySource.EXPLICIT_ASSET_CONFIG
        }
        val expiryDay = linkedAsset?.expiryDateIso
            ?.let { FleetPortfolioCalculator.parseExpiryDate(it)?.dayOfMonth }
        if (expiryDay != null) {
            return expiryDay to TrafficResetDaySource.ASSET_EXPIRY_DAY_FALLBACK
        }
        return 1 to TrafficResetDaySource.FIRST_OF_MONTH_DEFAULT
    }

    /**
     * Clamps [desiredDay] (`1..31`) safely to the actual length of [yearMonth]
     * (e.g. reset day 31 -> Feb 28 in non-leap years, Feb 29 in leap years, Apr 30 in April).
     */
    fun clampResetDateInMonth(
        yearMonth: YearMonth,
        desiredDay: Int,
    ): LocalDate {
        val safeDay = desiredDay.coerceIn(1, 31).coerceAtMost(yearMonth.lengthOfMonth())
        return yearMonth.atDay(safeDay)
    }

    /**
     * Computes the invariant half-open billing/traffic cycle `[cycleStartDate, nextResetDate)`
     * containing [today] for [anchorResetDay] (`1..31`).
     */
    fun resolveTrafficCycleBounds(
        today: LocalDate,
        anchorResetDay: Int,
    ): Pair<LocalDate, LocalDate> {
        val safeDay = anchorResetDay.coerceIn(1, 31)
        val currentYm = YearMonth.from(today)
        val thisMonthReset = clampResetDateInMonth(currentYm, safeDay)
        return if (!today.isBefore(thisMonthReset)) {
            thisMonthReset to clampResetDateInMonth(currentYm.plusMonths(1), safeDay)
        } else {
            clampResetDateInMonth(currentYm.minusMonths(1), safeDay) to thisMonthReset
        }
    }

    /**
     * Formats the human-readable exhaustion forecast relative to [today]:
     * - Same calendar month: `"预计本月 X 号流量会用完"`
     * - Next calendar month: `"预计下月 X 号流量会用完"`
     * - Other calendar month: `"预计 M 月 D 号流量会用完"`
     */
    fun formatExhaustionForecast(
        today: LocalDate,
        predictedExhaustionDate: LocalDate,
    ): String {
        val safeDate = if (predictedExhaustionDate.isBefore(today)) today else predictedExhaustionDate
        val todayYm = YearMonth.from(today)
        val targetYm = YearMonth.from(safeDate)
        return when (targetYm) {
            todayYm -> "预计本月 ${safeDate.dayOfMonth} 号流量会用完"
            todayYm.plusMonths(1) -> "预计下月 ${safeDate.dayOfMonth} 号流量会用完"
            else -> "预计 ${safeDate.monthValue} 月 ${safeDate.dayOfMonth} 号流量会用完"
        }
    }

    /**
     * Evaluates current cycle traffic usage, average burn rate, and exhaustion forecast.
     */
    fun analyzeTraffic(
        snapshot: ProbeServerSnapshot,
        linkedAsset: VpsAssetRecord? = null,
        nowEpochMillis: Long,
        zoneId: ZoneId = VALUATION_ZONE_ID,
        alertThresholdRatio: Double = TRAFFIC_ALERT_THRESHOLD_RATIO,
        minElapsedMillisForForecast: Long = DEFAULT_MIN_ELAPSED_FOR_FORECAST_MILLIS,
    ): ServerTrafficForecast {
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
        val (anchorResetDay, resetSource) = resolveTrafficResetDay(linkedAsset)
        val (cycleStartDate, nextResetDate) = resolveTrafficCycleBounds(today, anchorResetDay)

        val cycleStartMillis = cycleStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val nextResetMillis = nextResetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val elapsedMillis = (nowEpochMillis - cycleStartMillis).coerceAtLeast(0L)
        val remainingCycleMillis = (nextResetMillis - nowEpochMillis).coerceAtLeast(0L)

        val usedBytes = snapshot.totalMonthlyTransferBytes.coerceAtLeast(0L)
        val limitBytes = resolveEffectiveTrafficLimitBytes(snapshot, linkedAsset)
        val usageRatio = limitBytes?.let { limit ->
            (usedBytes.toDouble() / limit.toDouble()).coerceAtLeast(0.0)
        }

        val isExhausted = limitBytes != null && usedBytes >= limitBytes
        val isAtOrAboveThreshold = usageRatio != null && usageRatio >= alertThresholdRatio

        val canProjectBurn = elapsedMillis >= minElapsedMillisForForecast && elapsedMillis > 0L
        val bytesPerMillis = if (canProjectBurn && usedBytes > 0L) {
            usedBytes.toDouble() / elapsedMillis.toDouble()
        } else {
            null
        }
        val dailyBurnBytes = when {
            !canProjectBurn -> null
            usedBytes == 0L -> 0L
            bytesPerMillis != null -> (bytesPerMillis * MILLIS_PER_DAY).toLong().coerceAtLeast(0L)
            else -> null
        }

        val projectedTotalBytes = when {
            !canProjectBurn -> null
            usedBytes == 0L -> 0.0
            bytesPerMillis != null -> usedBytes.toDouble() + bytesPerMillis * remainingCycleMillis.toDouble()
            else -> null
        }
        val projectedCycleRatio = if (limitBytes != null && projectedTotalBytes != null) {
            (projectedTotalBytes / limitBytes.toDouble()).coerceAtLeast(0.0)
        } else {
            null
        }

        var willExhaustBeforeReset = false
        var predictedExhaustionDate: LocalDate? = null
        var exhaustionForecastText: String? = null

        if (limitBytes != null) {
            if (isExhausted) {
                willExhaustBeforeReset = true
                predictedExhaustionDate = today
                exhaustionForecastText = "本周期流量已用完"
            } else if (bytesPerMillis != null && bytesPerMillis > 0.0 && remainingCycleMillis > 0L) {
                val remainingBytes = (limitBytes - usedBytes).coerceAtLeast(0L)
                val millisToExhaustionDouble = remainingBytes.toDouble() / bytesPerMillis
                if (millisToExhaustionDouble.isFinite() && millisToExhaustionDouble < remainingCycleMillis.toDouble()) {
                    val predictedMillis = nowEpochMillis + millisToExhaustionDouble.toLong().coerceAtLeast(0L)
                    if (predictedMillis < nextResetMillis) {
                        val rawDate = Instant.ofEpochMilli(predictedMillis).atZone(zoneId).toLocalDate()
                        val clampedDate = when {
                            rawDate.isBefore(today) -> today
                            !rawDate.isBefore(nextResetDate) -> nextResetDate.minusDays(1).coerceAtLeast(today)
                            else -> rawDate
                        }
                        willExhaustBeforeReset = true
                        predictedExhaustionDate = clampedDate
                        exhaustionForecastText = formatExhaustionForecast(today, clampedDate)
                    }
                }
            }
        }

        return ServerTrafficForecast(
            probeKey = snapshot.probeKey,
            usedBytes = usedBytes,
            limitBytes = limitBytes,
            usageRatio = usageRatio,
            anchorResetDay = anchorResetDay,
            resetDaySource = resetSource,
            cycleStartDate = cycleStartDate,
            nextResetDate = nextResetDate,
            elapsedMillis = elapsedMillis,
            dailyBurnBytes = dailyBurnBytes,
            projectedCycleUsageRatio = projectedCycleRatio,
            isAtOrAboveAlertThreshold = isAtOrAboveThreshold,
            isExhausted = isExhausted,
            willExhaustBeforeReset = willExhaustBeforeReset,
            predictedExhaustionDate = predictedExhaustionDate,
            exhaustionForecastText = exhaustionForecastText,
        )
    }

    /**
     * Records fresh online CPU utilization samples into per-server daily buckets and prunes
     * buckets older than [rollingWindowDays] (default 30 days inclusive).
     */
    fun recordCpuSamplesAndPrune(
        existingByProbeKey: Map<String, List<ProbeDailyCpuBucket>>,
        freshSnapshots: Collection<ProbeServerSnapshot>,
        nowEpochMillis: Long,
        zoneId: ZoneId = VALUATION_ZONE_ID,
        rollingWindowDays: Int = FleetIdleThresholdConfig.ROLLING_WINDOW_DAYS,
    ): Map<String, List<ProbeDailyCpuBucket>> {
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
        val todayIso = today.toString()
        val cutoffDate = today.minusDays((rollingWindowDays - 1).coerceAtLeast(0).toLong())

        val result = LinkedHashMap<String, MutableList<ProbeDailyCpuBucket>>()

        for ((probeKey, buckets) in existingByProbeKey) {
            val retained = buckets.filterTo(ArrayList()) { bucket ->
                val date = FleetPortfolioCalculator.parseExpiryDate(bucket.dateIso)
                date != null && !date.isBefore(cutoffDate) && !date.isAfter(today.plusDays(1)) && bucket.sampleCount > 0
            }
            if (retained.isNotEmpty()) {
                retained.sortBy { it.dateIso }
                result[probeKey] = retained
            }
        }

        for (snapshot in freshSnapshots) {
            if (!snapshot.isOnline) continue
            val cpu = snapshot.cpuPercent.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: continue
            val key = snapshot.probeKey
            val list = result.getOrPut(key) { ArrayList() }
            val existingIdx = list.indexOfFirst { it.dateIso == todayIso }
            if (existingIdx >= 0) {
                val prev = list[existingIdx]
                val nextCount = prev.sampleCount.coerceAtLeast(0) + 1
                val nextAvg = prev.avgCpuPercent + (cpu - prev.avgCpuPercent) / nextCount.toDouble()
                val nextMax = maxOf(prev.maxCpuPercent, cpu)
                list[existingIdx] = ProbeDailyCpuBucket(
                    dateIso = todayIso,
                    avgCpuPercent = nextAvg.coerceIn(0.0, 100.0),
                    sampleCount = nextCount,
                    maxCpuPercent = nextMax.coerceIn(0.0, 100.0),
                    lastSampleEpochMillis = nowEpochMillis,
                )
            } else {
                list += ProbeDailyCpuBucket(
                    dateIso = todayIso,
                    avgCpuPercent = cpu,
                    sampleCount = 1,
                    maxCpuPercent = cpu,
                    lastSampleEpochMillis = nowEpochMillis,
                )
                list.sortBy { it.dateIso }
            }
        }

        return result.mapValues { (_, list) -> list.toList() }
    }

    /**
     * Evaluates 30-day rolling CPU utilization + cycle traffic usage to classify whether a server
     * is `"闲置"` (Idle) and ready for `"卖掉回血"` via [FleetSalePostBuilder].
     */
    fun assessIdle(
        snapshot: ProbeServerSnapshot,
        linkedAsset: VpsAssetRecord? = null,
        cpuBuckets: List<ProbeDailyCpuBucket>,
        trafficForecast: ServerTrafficForecast,
        nowEpochMillis: Long,
        zoneId: ZoneId = VALUATION_ZONE_ID,
        config: FleetIdleThresholdConfig = FleetIdleThresholdConfig(),
    ): ServerIdleAssessment {
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
        val cutoffDate = today.minusDays((FleetIdleThresholdConfig.ROLLING_WINDOW_DAYS - 1).coerceAtLeast(0).toLong())
        val validBuckets = cpuBuckets.filter { bucket ->
            val d = FleetPortfolioCalculator.parseExpiryDate(bucket.dateIso)
            d != null && !d.isBefore(cutoffDate) && !d.isAfter(today.plusDays(1)) && bucket.sampleCount > 0
        }

        val recordedDays = validBuckets.size
        val totalSamples = validBuckets.sumOf { it.sampleCount }
        val avgCpu30d = if (validBuckets.isNotEmpty()) {
            validBuckets.map { it.avgCpuPercent.coerceIn(0.0, 100.0) }.average()
        } else {
            null
        }
        val serverName = linkedAsset?.nickname?.takeIf { it.isNotBlank() } ?: snapshot.name

        if (validBuckets.isEmpty() && !snapshot.isOnline) {
            return ServerIdleAssessment(
                probeKey = snapshot.probeKey,
                serverName = serverName,
                linkedAsset = linkedAsset,
                status = IdleClassificationStatus.OFFLINE_OR_NO_DATA,
                avgCpu30dPercent = null,
                recordedDays = 0,
                totalSamples = 0,
                trafficUsageRatio = trafficForecast.usageRatio,
                projectedTrafficUsageRatio = trafficForecast.projectedCycleUsageRatio,
                monthlyTransferBytes = trafficForecast.usedBytes,
                trafficLimitBytes = trafficForecast.limitBytes,
            )
        }

        if (recordedDays < config.minRecordedDays || totalSamples < config.minTotalSamples || avgCpu30d == null) {
            return ServerIdleAssessment(
                probeKey = snapshot.probeKey,
                serverName = serverName,
                linkedAsset = linkedAsset,
                status = IdleClassificationStatus.COLD_START_OBSERVING,
                avgCpu30dPercent = avgCpu30d,
                recordedDays = recordedDays,
                totalSamples = totalSamples,
                trafficUsageRatio = trafficForecast.usageRatio,
                projectedTrafficUsageRatio = trafficForecast.projectedCycleUsageRatio,
                monthlyTransferBytes = trafficForecast.usedBytes,
                trafficLimitBytes = trafficForecast.limitBytes,
            )
        }

        val isAssetAlreadyExpired = linkedAsset?.expiryDateIso
            ?.let { FleetPortfolioCalculator.parseExpiryDate(it)?.isBefore(today) } == true

        val isCpuLow = avgCpu30d <= config.maxAvgCpuPercent
        val isTrafficLow = if (trafficForecast.limitBytes != null && trafficForecast.usageRatio != null) {
            val effectiveRatio = maxOf(
                trafficForecast.usageRatio,
                trafficForecast.projectedCycleUsageRatio ?: trafficForecast.usageRatio,
            )
            effectiveRatio <= config.maxTrafficUsageRatio
        } else {
            val projected30dBytes = trafficForecast.dailyBurnBytes?.let { it * 30L } ?: trafficForecast.usedBytes
            maxOf(trafficForecast.usedBytes, projected30dBytes) <= config.maxUnlimitedMonthlyTransferBytes
        }

        val isIdle = !isAssetAlreadyExpired && isCpuLow && isTrafficLow
        val recommendation = if (isIdle) {
            if (trafficForecast.usageRatio != null) {
                String.format(
                    Locale.US,
                    "30天平均 CPU %.1f%% · 流量仅用 %.1f%%，建议出掉回血",
                    avgCpu30d,
                    trafficForecast.usageRatio * 100.0,
                )
            } else {
                String.format(
                    Locale.US,
                    "30天平均 CPU %.1f%% · 周期流量仅 %s，建议出掉回血",
                    avgCpu30d,
                    formatBytesBinary(trafficForecast.usedBytes),
                )
            }
        } else {
            null
        }

        return ServerIdleAssessment(
            probeKey = snapshot.probeKey,
            serverName = serverName,
            linkedAsset = linkedAsset,
            status = if (isIdle) IdleClassificationStatus.IDLE else IdleClassificationStatus.ACTIVE,
            avgCpu30dPercent = avgCpu30d,
            recordedDays = recordedDays,
            totalSamples = totalSamples,
            trafficUsageRatio = trafficForecast.usageRatio,
            projectedTrafficUsageRatio = trafficForecast.projectedCycleUsageRatio,
            monthlyTransferBytes = trafficForecast.usedBytes,
            trafficLimitBytes = trafficForecast.limitBytes,
            recommendationText = recommendation,
        )
    }

    /**
     * Resolves an evaluation timestamp aligned with [today] when callers (such as unit tests)
     * supply a synthetic `nowEpochMillis` on a different calendar date than [today].
     */
    fun alignEpochMillisWithDate(
        today: LocalDate,
        nowEpochMillis: Long,
        zoneId: ZoneId = VALUATION_ZONE_ID,
    ): Long {
        val dateFromMillis = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
        return if (dateFromMillis == today) {
            nowEpochMillis
        } else {
            today.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        }
    }
}
