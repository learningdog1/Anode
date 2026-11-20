package com.nodeseek.app.data.overseas

import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasIntelSnapshot
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationTier
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

    private val sampleOffers = listOf(
        OverseasDealOffer(
            id = "223566",
            title = "RackNerd 北美多机房 KVM 入门与常规套餐促销",
            originalTitle = "RackNerd - Los Angeles & San Jose KVM VPS Special Offers",
            summary = "RackNerd 推出洛杉矶、圣何塞及芝加哥多机房 KVM 虚拟服务器常规特惠方案，涵盖轻量建站与开发测试规格。",
            merchant = "RackNerd",
            sourceLink = "https://lowendtalk.com/discussion/223566/racknerd-special-offers",
            publishedAtEpochMillis = nowEpochMillis - 25L * 60_000L,
            publishedAtIso = "2026-11-20T04:10:00+00:00",
        ),
        OverseasDealOffer(
            id = "223512",
            title = "CloudCone 东京与法兰克福 NVMe 云主机限时活动",
            originalTitle = "CloudCone Limited Time Tokyo & Frankfurt NVMe Cloud Deals",
            summary = "CloudCone 开放日本东京与德国法兰克福节点 NVMe 固态云主机方案，支持按需弹性部署与快照备份。",
            merchant = "CloudCone",
            sourceLink = "https://lowendtalk.com/discussion/223512/cloudcone-nvme-deals",
            publishedAtEpochMillis = nowEpochMillis - 2L * 3_600_000L,
            publishedAtIso = "2026-11-20T02:30:00+00:00",
        ),
    )

    private val _snapshot = MutableStateFlow(
        OverseasIntelSnapshot(
            offers = sampleOffers,
            translationConfig = OverseasTranslationConfig(),
            syncStatus = OverseasSyncStatus(
                lastSyncedEpochMillis = nowEpochMillis - 120_000L,
                currentPage = 1,
                totalCount = sampleOffers.size,
                hasMore = false,
                loadedFromCache = true,
            ),
        ),
    )
    override val snapshot: StateFlow<OverseasIntelSnapshot> = _snapshot.asStateFlow()

    override suspend fun loadFromCache(): List<OverseasDealOffer> = _snapshot.value.offers

    override suspend fun refresh(query: String): Result<List<OverseasDealOffer>> =
        Result.success(_snapshot.value.offers)

    override suspend fun loadNextPage(query: String): Result<List<OverseasDealOffer>> =
        Result.success(_snapshot.value.offers)

    override suspend fun getOfferDetail(
        offerId: String,
        forceRefresh: Boolean,
    ): Result<OverseasOfferDetail> {
        val item = sampleOffers.firstOrNull { it.id == offerId } ?: sampleOffers.first()
        return Result.success(
            OverseasOfferDetail(
                id = item.id,
                title = "${item.merchant} 限时促销详情",
                originalTitle = item.originalTitle,
                merchant = item.merchant,
                author = item.merchant,
                publishedAtEpochMillis = item.publishedAtEpochMillis,
                publishedAtIso = item.publishedAtIso,
                sourceLink = item.sourceLink,
                tgMarkdown = """
                    ## ${item.title}

                    ### VPS 套餐
                    - 2 vCPU / 4GB RAM / 45GB NVMe | 4TB@1Gbps | 1 IPv4 → ${'$'}18.99/年

                    ### 购买链接
                    - [直达下单](${item.sourceLink})

                    🏷 **优惠码**：`LET2026`

                    - 🟢 **优点**：机房选择丰富，续费同价
                    - 🔴 **缺点**：热门节点库存有限
                    - 🔵 **适合**：日常建站与开发测试
                    - 💡 **AI锐评**：综合性价比稳健，按需选购即可

                    ---
                    🔗 [查看原帖](${item.sourceLink})
                """.trimIndent(),
            ),
        )
    }

    override suspend fun translateOfferDetail(
        offerId: String,
        forceRefresh: Boolean,
    ): Result<OverseasOfferDetail> = getOfferDetail(offerId, false).map {
        it.copy(
            translatedMarkdown = it.tgMarkdown,
            translationTier = TranslationTier.OFFLINE_GLOSSARY,
        )
    }

    override suspend fun getRepostContent(
        offerId: String,
        forceRefresh: Boolean,
    ): Result<OverseasRepostContent> {
        val item = sampleOffers.firstOrNull { it.id == offerId } ?: sampleOffers.first()
        return Result.success(
            OverseasRepostContent(
                id = item.id,
                title = item.originalTitle,
                titleCn = item.title,
                merchant = item.merchant,
                author = item.merchant,
                publishedAtEpochMillis = item.publishedAtEpochMillis,
                publishedAtIso = item.publishedAtIso,
                sourceLink = item.sourceLink,
                bodyMarkdown = "### ${item.originalTitle}\n\n- **2 vCPU** / 4GB RAM / 45GB NVMe — **\$18.99/yr**\n- [Order Link](${item.sourceLink})",
                suggestedCategorySlug = "info",
            ),
        )
    }

    override suspend fun updateTranslationConfig(config: OverseasTranslationConfig) {
        _snapshot.update { it.copy(translationConfig = config) }
    }

    override suspend fun updateDisplayMode(mode: OverseasDisplayMode) {
        _snapshot.update { it.copy(translationConfig = it.translationConfig.copy(displayMode = mode)) }
    }

    override suspend fun testTranslationConfig(config: OverseasTranslationConfig): Result<String> =
        Result.success("预览模式测试通过")

    override suspend fun clearTranslationCache() = Unit
}
