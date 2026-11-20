package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.OverseasLabeledLink
import com.nodeseek.app.domain.overseas.OverseasMediaImage
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.ui.overseas.OverseasIntelViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverseasForumPostFormatterAndViewModelTest {

    @Test
    fun formatsNodeSeekPostFromMainPostRepostEndpointWithoutComments() {
        val fallbackOffer = OverseasDealOffer(
            id = "223558",
            title = "RackNerd 多机房 KVM 促销方案",
            originalTitle = "RackNerd - KVM VPS Special Offers in Los Angeles & Tokyo",
            summary = "RackNerd 推出洛杉矶与东京节点常规特惠方案。",
            merchant = "RackNerd",
            sourceLink = "https://lowendtalk.com/discussion/223558/racknerd-special",
        )
        val repost = OverseasRepostContent(
            id = "223558",
            title = "RackNerd - KVM VPS Special Offers in Los Angeles & Tokyo",
            titleCn = "RackNerd 洛杉矶与东京多机房 KVM 特惠",
            merchant = "RackNerd",
            author = "dustinc",
            publishedAtIso = "2026-11-20T01:15:00+00:00",
            sourceLink = "https://lowendtalk.com/discussion/223558/racknerd-special",
            bodyMarkdown = """
                ### KVM Special Plans

                ![Promo Banner](https://img.example.com/rn-banner.png)

                - **2 vCPU** / 4GB RAM / 45GB NVMe — **${'$'}18.99/yr** — [Order LA](https://my.racknerd.com/cart.php?a=add&pid=901)
            """.trimIndent(),
            images = listOf(
                OverseasMediaImage("Promo Banner", "https://img.example.com/rn-banner.png"),
                OverseasMediaImage("Extra Spec Sheet", "https://img.example.com/rn-specs.png"),
            ),
            links = listOf(
                OverseasLabeledLink("Order LA", "https://my.racknerd.com/cart.php?a=add&pid=901"),
                OverseasLabeledLink("Looking Glass", "https://lg-lax02.racknerd.com"),
            ),
            suggestedCategorySlug = "info",
        )

        val post = OverseasForumPostFormatter.format(repost = repost, fallbackOffer = fallbackOffer)

        assertEquals("info", post.suggestedCategorySlug)
        assertTrue(post.title.startsWith("[海外情报/LET]"))
        assertTrue(post.title.contains("RackNerd 洛杉矶与东京多机房 KVM 特惠"))

        val md = post.markdownBody
        assertTrue(md.contains("RackNerd - KVM VPS Special Offers in Los Angeles & Tokyo"))
        assertTrue(md.contains("### KVM Special Plans"))
        assertTrue(md.contains("![Promo Banner](https://img.example.com/rn-banner.png)"))
        // Extra image & link not yet in bodyMarkdown are appended cleanly without duplicating existing ones
        assertEquals(1, Regex("rn-banner\\.png").findAll(md).count())
        assertTrue(md.contains("![Extra Spec Sheet](https://img.example.com/rn-specs.png)"))
        assertTrue(md.contains("[Looking Glass](https://lg-lax02.racknerd.com)"))
        assertTrue(md.contains("[LowEndTalk 原帖](https://lowendtalk.com/discussion/223558/racknerd-special)"))
        assertTrue(md.contains("主楼发布内容（不含评论）"))
        assertFalse(md.contains("黑五"))
    }

    @Test
    fun viewModelFiltersByMultiTokenQueryAndEmitsRepostDraft() = runTest {
        val repo = PreviewOverseasIntelRepository()
        val offers = repo.snapshot.value.offers

        assertEquals(2, OverseasIntelViewModel.filterOffers(offers, "").size)

        val byCity = OverseasIntelViewModel.filterOffers(offers, "东京 CloudCone")
        assertEquals(1, byCity.size)
        assertEquals("223512", byCity.first().id)

        val vm = OverseasIntelViewModel(repository = repo, autoLoadOnInit = false)
        val draftDeferred = async { vm.repostDraftEvents.first() }
        vm.prepareRepostToForum(offers.first())
        val draft = draftDeferred.await()

        assertEquals("info", draft.suggestedCategorySlug)
        assertTrue(draft.title.startsWith("[海外情报/LET]"))
        assertTrue(draft.markdownBody.contains("RackNerd"))
    }
}
