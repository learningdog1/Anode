package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.OverseasPriceConverter
import com.nodeseek.app.domain.overseas.OverseasSource
import com.nodeseek.app.domain.overseas.OverseasSourceFilter
import com.nodeseek.app.ui.overseas.OverseasIntelViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverseasForumPostFormatterAndViewModelTest {

    @Test
    fun formatsNodeSeekMarkdownIntelPostWithChineseTitleSpecsCnyCouponsAndSourceCredit() {
        val fx = PreviewOverseasIntelRepository.buildDefaultFxState()
        val offer = OverseasDealOffer(
            id = "let-209901",
            source = OverseasSource.LET,
            originalTitle = "RackNerd Black Friday 2 vCPU 4GB RAM NVMe Los Angeles - ${'$'}14.99/yr",
            translatedTitle = "RackNerd 黑五特惠 2核 4GB 内存 NVMe 固态 洛杉矶 — ${'$'}14.99/yr",
            originalSummary = "2 vCPU | 4GB RAM | 45GB NVMe | 4TB Bandwidth",
            translatedSummary = "2核4G、45GB NVMe 固态、洛杉矶机房，续费同价。",
            authorOrProvider = "RackNerd",
            sourceLink = "https://lowendtalk.com/discussion/209901/racknerd-bf",
            orderLinks = listOf("https://my.racknerd.com/cart.php?a=add&pid=901"),
            couponCodes = listOf("LETBF2026"),
            priceMatches = OverseasPriceConverter.extractAndConvert("${'$'}14.99/yr", fxRatesState = fx),
            specHighlights = listOf("2 vCPU", "4GB RAM", "45GB NVMe", "洛杉矶"),
            isFlashOrBlackFriday = true,
        )

        val post = OverseasForumPostFormatter.format(offer, fx)

        assertEquals("info", post.suggestedCategorySlug)
        assertTrue(post.title.contains("[黑五/海外情报/LET]"))
        assertTrue(post.title.contains("RackNerd 黑五特惠"))
        assertTrue(post.title.contains("${'$'}14.99/yr"))
        assertTrue(post.title.contains("≈ ¥108.68/年"))

        val md = post.markdownBody
        assertTrue(md.contains("中文译题"))
        assertTrue(md.contains("英文原题"))
        assertTrue(md.contains("| **情报来源** | LowEndTalk (`LET`) |"))
        assertTrue(md.contains("| **商家 / 作者** | RackNerd |"))
        assertTrue(md.contains("2 vCPU · 4GB RAM · 45GB NVMe · 洛杉矶"))
        assertTrue(md.contains("**${'$'}14.99/yr** (≈ ¥108.68/年)"))
        assertTrue(md.contains("`LETBF2026`"))
        assertTrue(md.contains("[直达下单链接 1](https://my.racknerd.com/cart.php?a=add&pid=901)"))
        assertTrue(md.contains("[LowEndTalk 原帖](https://lowendtalk.com/discussion/209901/racknerd-bf)"))
        assertTrue(md.contains("1 USD ≈ ¥7.25"))
    }

    @Test
    fun viewModelFiltersBySourceBlackFridayAndMultiTokenQuery() {
        val repo = PreviewOverseasIntelRepository()
        val offers = repo.snapshot.value.offers

        assertEquals(2, OverseasIntelViewModel.filterOffers(offers, OverseasSourceFilter.ALL, "").size)
        assertEquals(1, OverseasIntelViewModel.filterOffers(offers, OverseasSourceFilter.LET, "").size)
        assertEquals(1, OverseasIntelViewModel.filterOffers(offers, OverseasSourceFilter.LEB, "").size)
        assertEquals(2, OverseasIntelViewModel.filterOffers(offers, OverseasSourceFilter.BLACK_FRIDAY_FLASH, "").size)

        val byCity = OverseasIntelViewModel.filterOffers(offers, OverseasSourceFilter.ALL, "东京 CloudCone")
        assertEquals(1, byCity.size)
        assertEquals("leb-88412", byCity.first().id)

        val byCoupon = OverseasIntelViewModel.filterOffers(offers, OverseasSourceFilter.ALL, "LETBF2026")
        assertEquals(1, byCoupon.size)
        assertEquals("let-209901", byCoupon.first().id)
    }
}
