package com.nodeseek.app.data.deals

import com.nodeseek.app.domain.deals.DealSource
import com.nodeseek.app.domain.deals.DealsFeedSnapshot
import com.nodeseek.app.domain.deals.PromoDealCardModel
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal

@Serializable
private data class CachedDealCardDto(
    val id: String,
    val source: String,
    val topicId: Long? = null,
    val sourceUrl: String,
    val orderUrl: String? = null,
    val merchantName: String = "",
    val title: String,
    val translatedSummary: String = "",
    val rawPriceText: String? = null,
    val priceAmount: String? = null,
    val currencyCode: String? = null,
    val annualizedUsd: String? = null,
    val annualizedCny: String? = null,
    val billingCycleLabel: String = "",
    val cpuCores: Int? = null,
    val ramText: String? = null,
    val storageText: String? = null,
    val trafficText: String? = null,
    val portSpeedText: String? = null,
    val regionTags: List<String> = emptyList(),
    val routeTags: List<String> = emptyList(),
    val promoCode: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val publishedAtEpochMillis: Long = 0L,
    val replyCount: Int = 0,
    val isOutOfStockHint: Boolean = false,
    val authorName: String = "",
    val categorySlug: String = "",
    val viewCount: Int = 0,
    val isDetailEnriched: Boolean = false,
)

@Serializable
private data class DealsHubCachePayload(
    val updatedAtEpochMillis: Long = 0L,
    val fxUsdToCnyRate: String = ValuationCurrency.USD.defaultCnyRate.toPlainString(),
    val fxProviderDate: String? = null,
    val deals: List<CachedDealCardDto> = emptyList(),
)

/**
 * Atomic JSON disk cache stored in `filesDir/deals_hub_cache.json` for instant cold-start
 * Black Friday Deals Hub rendering and offline/guest resilience.
 */
internal class DealsDiskCache(
    private val cacheFile: File,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun read(): DealsFeedSnapshot? {
        if (!cacheFile.exists()) return null
        val payload = runCatching {
            val content = cacheFile.readText(Charsets.UTF_8)
            if (content.isBlank()) null else json.decodeFromString<DealsHubCachePayload>(content)
        }.getOrNull() ?: return null

        val deals = payload.deals.mapNotNull { dto -> dto.toDomainOrNull() }
        val fxUsdRate = payload.fxUsdToCnyRate.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?: ValuationCurrency.USD.defaultCnyRate

        return DealsFeedSnapshot(
            deals = deals,
            updatedAtEpochMillis = payload.updatedAtEpochMillis,
            fxUsdToCnyRate = fxUsdRate,
            fxProviderDate = payload.fxProviderDate,
            isFromCache = true,
            warningMessage = null,
        )
    }

    fun write(snapshot: DealsFeedSnapshot) {
        val payload = DealsHubCachePayload(
            updatedAtEpochMillis = snapshot.updatedAtEpochMillis,
            fxUsdToCnyRate = snapshot.fxUsdToCnyRate.toPlainString(),
            fxProviderDate = snapshot.fxProviderDate,
            deals = snapshot.deals.map { it.toDto() },
        )
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val encoded = json.encodeToString(payload)
            val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmpFile.writeText(encoded, Charsets.UTF_8)
            if (!tmpFile.renameTo(cacheFile)) {
                cacheFile.writeText(encoded, Charsets.UTF_8)
                tmpFile.delete()
            }
        }
    }

    private fun PromoDealCardModel.toDto(): CachedDealCardDto = CachedDealCardDto(
        id = id,
        source = source.name,
        topicId = topicId,
        sourceUrl = sourceUrl,
        orderUrl = orderUrl,
        merchantName = merchantName,
        title = title,
        translatedSummary = translatedSummary,
        rawPriceText = rawPriceText,
        priceAmount = priceAmount?.toPlainString(),
        currencyCode = currency?.code,
        annualizedUsd = annualizedUsd?.toPlainString(),
        annualizedCny = annualizedCny?.toPlainString(),
        billingCycleLabel = billingCycleLabel,
        cpuCores = cpuCores,
        ramText = ramText,
        storageText = storageText,
        trafficText = trafficText,
        portSpeedText = portSpeedText,
        regionTags = regionTags,
        routeTags = routeTags,
        promoCode = promoCode,
        expiresAtEpochMillis = expiresAtEpochMillis,
        publishedAtEpochMillis = publishedAtEpochMillis,
        replyCount = replyCount,
        isOutOfStockHint = isOutOfStockHint,
        authorName = authorName,
        categorySlug = categorySlug,
        viewCount = viewCount,
        isDetailEnriched = isDetailEnriched,
    )

    private fun CachedDealCardDto.toDomainOrNull(): PromoDealCardModel? {
        val resolvedSource = runCatching { DealSource.valueOf(source) }.getOrNull() ?: return null
        return PromoDealCardModel(
            id = id,
            source = resolvedSource,
            topicId = topicId,
            sourceUrl = sourceUrl,
            orderUrl = orderUrl,
            merchantName = merchantName,
            title = title,
            translatedSummary = translatedSummary,
            rawPriceText = rawPriceText,
            priceAmount = priceAmount?.toBigDecimalOrNull(),
            currency = ValuationCurrency.fromCode(currencyCode),
            annualizedUsd = annualizedUsd?.toBigDecimalOrNull(),
            annualizedCny = annualizedCny?.toBigDecimalOrNull(),
            billingCycleLabel = billingCycleLabel,
            cpuCores = cpuCores,
            ramText = ramText,
            storageText = storageText,
            trafficText = trafficText,
            portSpeedText = portSpeedText,
            regionTags = regionTags,
            routeTags = routeTags,
            promoCode = promoCode,
            expiresAtEpochMillis = expiresAtEpochMillis,
            publishedAtEpochMillis = publishedAtEpochMillis,
            replyCount = replyCount,
            isOutOfStockHint = isOutOfStockHint,
            authorName = authorName,
            categorySlug = categorySlug,
            viewCount = viewCount,
            isDetailEnriched = isDetailEnriched,
        )
    }

    companion object {
        const val CACHE_FILE_NAME = "deals_hub_cache.json"
    }
}
