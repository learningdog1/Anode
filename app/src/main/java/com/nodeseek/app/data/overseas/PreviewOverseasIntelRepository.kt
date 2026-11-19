package com.nodeseek.app.data.overseas

import com.nodeseek.app.data.valuation.CurrencyRateQuote
import com.nodeseek.app.data.valuation.FxRefreshOutcome
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasIntelSnapshot
import com.nodeseek.app.domain.overseas.OverseasPriceConverter
import com.nodeseek.app.domain.overseas.OverseasSource
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationTier
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lightweight in-memory [OverseasIntelRepository] suitable for Compose `@Preview` and `PreviewAppContainer`.
 */
class PreviewOverseasIntelRepository(
    nowEpochMillis: Long = System.currentTimeMillis(),
) : OverseasIntelRepository {

    private val defaultFx = buildDefaultFxState()

    private val sampleOffers = listOf(
        OverseasDealOffer(
            id = "let-209901",
            source = OverseasSource.LET,
            originalTitle = "[Black Friday] RackNerd 2 vCPU 4GB RAM 45GB NVMe KVM VPS in Los Angeles / San Jose - \$14.99/yr Recurring",
            translatedTitle = "[黑五特惠] RackNerd 2核 4GB 内存 45GB NVMe 固态 KVM VPS（洛杉矶 / 圣何塞）— \$14.99/yr 续费同价",
            originalSummary = "2 vCPU | 4GB RAM | 45GB NVMe SSD | 4TB Bandwidth @ 1Gbps | Los Angeles & San Jose | Coupon: LETBF2026",
            translatedSummary = "2 vCPU · 4GB 内存 · 45GB NVMe 固态 · 4TB 流量 @ 1Gbps，可选洛杉矶 / 圣何塞机房，续费同价。",
            authorOrProvider = "RackNerd",
            sourceLink = "https://lowendtalk.com/discussion/209901/black-friday-racknerd",
            orderLinks = listOf("https://my.racknerd.com/cart.php?a=add&pid=901"),
            couponCodes = listOf("LETBF2026"),
            priceMatches = OverseasPriceConverter.extractAndConvert(
                title = "\$14.99/yr",
                body = "\$24.99/yr",
                fxRatesState = defaultFx,
            ),
            specHighlights = listOf("2 vCPU", "4GB RAM", "45GB NVMe", "4TB 流量", "1Gbps", "洛杉矶", "圣何塞"),
            publishedAtEpochMillis = nowEpochMillis - 35L * 60_000L,
            isFlashOrBlackFriday = true,
            translationTier = TranslationTier.GOOGLE_GTX,
        ),
        OverseasDealOffer(
            id = "leb-88412",
            source = OverseasSource.LEB,
            originalTitle = "CloudCone Flash Sale – 2GB RAM Tokyo / Frankfurt NVMe VPS for €12/year",
            translatedTitle = "CloudCone 限时闪购 — 2GB 内存 东京 / 法兰克福 NVMe 固态 VPS 仅 €12/year",
            originalSummary = "1 vCPU | 2GB RAM | 30GB NVMe | 2TB Traffic | Tokyo & Frankfurt | Direct order with promo code LEBFLASH",
            translatedSummary = "1 vCPU · 2GB 内存 · 30GB NVMe 固态 · 2TB 流量，支持东京与法兰克福机房，使用优惠码 LEBFLASH 直达下单。",
            authorOrProvider = "CloudCone",
            sourceLink = "https://lowendbox.com/blog/cloudcone-flash-sale/",
            orderLinks = listOf("https://billing.cloudcone.com/order?pid=412&promocode=LEBFLASH"),
            couponCodes = listOf("LEBFLASH"),
            priceMatches = OverseasPriceConverter.extractAndConvert(
                title = "€12/year",
                body = "",
                fxRatesState = defaultFx,
            ),
            specHighlights = listOf("1 vCPU", "2GB RAM", "30GB NVMe", "2TB 流量", "东京", "法兰克福"),
            publishedAtEpochMillis = nowEpochMillis - 3L * 3_600_000L,
            isFlashOrBlackFriday = true,
            translationTier = TranslationTier.OFFLINE_GLOSSARY,
        ),
    )

    private val _snapshot = MutableStateFlow(
        OverseasIntelSnapshot(
            offers = sampleOffers,
            fxRatesState = defaultFx,
            translationConfig = OverseasTranslationConfig(),
            syncStatus = OverseasSyncStatus(
                lastSyncedEpochMillis = nowEpochMillis - 120_000L,
                loadedFromCache = true,
            ),
        ),
    )
    override val snapshot: StateFlow<OverseasIntelSnapshot> = _snapshot.asStateFlow()

    override suspend fun loadFromCache(): List<OverseasDealOffer> = _snapshot.value.offers

    override suspend fun refresh(forceTranslate: Boolean): Result<List<OverseasDealOffer>> =
        Result.success(_snapshot.value.offers)

    override suspend fun translateOffer(offerId: String, forceRefresh: Boolean): OverseasDealOffer? =
        _snapshot.value.offers.firstOrNull { it.id == offerId }

    override suspend fun translateAllLoaded(forceRefresh: Boolean) = Unit

    override suspend fun updateTranslationConfig(config: OverseasTranslationConfig) {
        _snapshot.update { it.copy(translationConfig = config) }
    }

    override suspend fun updateDisplayMode(mode: OverseasDisplayMode) {
        _snapshot.update { it.copy(translationConfig = it.translationConfig.copy(displayMode = mode)) }
    }

    override suspend fun refreshExchangeRates(): FxRefreshOutcome =
        FxRefreshOutcome.Success(state = _snapshot.value.fxRatesState, fromCache = true)

    override suspend fun testTranslationConfig(config: OverseasTranslationConfig): Result<String> =
        Result.success("预览模式测试通过")

    override suspend fun clearTranslationCache() = Unit

    companion object {
        fun buildDefaultFxState(): FxRatesState = FxRatesState(
            quotes = ValuationCurrency.entries.associateWith { currency ->
                CurrencyRateQuote(
                    currency = currency,
                    rateToCny = currency.defaultCnyRate,
                    source = FxRateSource.OFFLINE_DEFAULT,
                )
            },
            providerDate = "参考汇率",
        )
    }
}
