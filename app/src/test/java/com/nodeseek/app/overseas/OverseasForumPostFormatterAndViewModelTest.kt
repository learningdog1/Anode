package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.OverseasTranslator
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OverseasForumPostFormatterAndViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun formatsNodeSeekPostWithCleanBriefingCardChineseBodyHtmlCleanupAndNoDuplicateAssets() {
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
            title = "RackNerd - KVM VPS Special Offers in Los Angeles &amp; Tokyo",
            titleCn = "RackNerd 洛杉矶与东京多机房 KVM 特惠",
            merchant = "RackNerd",
            author = "dustinc",
            publishedAtIso = "2026-11-20T01:15:00+00:00",
            sourceLink = "https://lowendtalk.com/discussion/223558/racknerd-special",
            bodyMarkdown = """
                ### KVM Special Plans

                ![Promo Banner](https://img.example.com/rn-banner.png)

                - **2 vCPU** / 4GB RAM / 45GB NVMe&nbsp;&mdash; **${'$'}18.99/yr** — Promo Code: RN2026VIP — [Order LA](https://my.racknerd.com/cart.php?a=add&pid=901)



                - Double Bandwidth in Los Angeles &amp; Tokyo
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
        assertEquals("[海外情报] RackNerd 洛杉矶与东京多机房 KVM 特惠", post.title)

        val md = post.markdownBody
        // Top briefing card fields
        assertTrue(md.contains("> **商家 / 作者**：RackNerd（@dustinc）"))
        assertTrue(md.contains("> **发布时间**：2026-11-20 01:15 UTC"))
        assertTrue(md.contains("> **原帖标题**：RackNerd - KVM VPS Special Offers in Los Angeles & Tokyo"))
        assertTrue(md.contains("> **直达原帖**：[LowEndTalk 原帖主楼](https://lowendtalk.com/discussion/223558/racknerd-special)"))

        // HTML entities cleaned, coupon code highlighted in inline backticks, English terms translated
        assertFalse(md.contains("&nbsp;"))
        assertFalse(md.contains("&amp;"))
        assertFalse(md.contains("&mdash;"))
        assertFalse(md.contains("\n\n\n"))
        assertTrue(md.contains("`RN2026VIP`"))
        assertTrue(md.contains("![Promo Banner](https://img.example.com/rn-banner.png)"))
        assertTrue(md.contains("双倍流量"))

        // Extra image & link not yet in bodyMarkdown are categorized cleanly without duplicating existing ones
        assertEquals(1, Regex("rn-banner\\.png").findAll(md).count())
        assertTrue(md.contains("### 相关链接"))
        assertTrue(md.contains("[Looking Glass 测速面板](https://lg-lax02.racknerd.com)"))
        assertTrue(md.contains("### 活动配图"))
        assertTrue(md.contains("![Extra Spec Sheet](https://img.example.com/rn-specs.png)"))

        // Clean source attribution footer
        assertTrue(md.contains("[LowEndTalk 原帖](https://lowendtalk.com/discussion/223558/racknerd-special)"))
        assertTrue(md.contains("主楼发布内容（不含评论）"))
        assertFalse(md.contains("telegram", ignoreCase = true))
    }

    @Test
    fun viewModelFiltersOffersAndAutoTranslatesRepostViaTranslator() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  [
                    ["RackNerd 洛杉矶与圣何塞 KVM 特惠套餐\n", "RackNerd - Los Angeles & San Jose KVM VPS Special Offers\n", null, null, 10],
                    ["**2 vCPU** / 4GB 内存 / 45GB NVMe 固态 — **${'$'}18.99/年**\n", "**2 vCPU** / 4GB RAM / 45GB NVMe — **${'$'}18.99/yr**\n", null, null, 10],
                    ["[立即下单] KPH0KPH", "[Order Link]KPH0KPH", null, null, 10]
                  ],
                  null,
                  "en"
                ]
                """.trimIndent(),
            ),
        )
        server.start()

        try {
            val repo = PreviewOverseasIntelRepository()
            val offers = repo.snapshot.value.offers

            assertEquals(2, OverseasIntelViewModel.filterOffers(offers, "").size)

            val byCity = OverseasIntelViewModel.filterOffers(offers, "东京 CloudCone")
            assertEquals(1, byCity.size)
            assertEquals("223512", byCity.first().id)

            val translator = OverseasTranslator(
                cacheFile = File(tempFolder.root, "vm_repost_trans.json"),
                gtxBaseUrl = server.url("/translate_a/single").toString(),
            )
            val vm = OverseasIntelViewModel(
                repository = repo,
                autoLoadOnInit = false,
                translator = translator,
            )
            val draftDeferred = async { vm.repostDraftEvents.first() }
            vm.prepareRepostToForum(offers.first())
            val draft = draftDeferred.await()

            assertEquals("info", draft.suggestedCategorySlug)
            assertTrue(draft.title.startsWith("[海外情报] "))
            assertTrue(draft.title.contains("RackNerd"))
            assertTrue(draft.markdownBody.contains("4GB 内存"))
            assertTrue(draft.markdownBody.contains("[立即下单](https://lowendtalk.com/discussion/223566/racknerd-special-offers)"))
            assertFalse(draft.markdownBody.contains("telegram", ignoreCase = true))
        } finally {
            server.shutdown()
        }
    }
}
