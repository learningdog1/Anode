package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.domain.overseas.OverseasLabeledLink
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.domain.overseas.TranslationTier
import com.nodeseek.app.domain.overseas.VpsGlossaryTranslator
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

class OverseasTranslatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun googleGtxTranslationPolishesVpsJargonAndPersistsDiskCache() = runTest {
        val server = MockWebServer()
        val gtxJson = """
            [
              [
                ["闪光销售：洛杉矶 NVMe VPS ", "Flash Sale: Los Angeles NVMe VPS ", null, null, 10],
                ["未计量流量，反复出现 ${'$'}14.99/yr", "Unmetered traffic, Recurring ${'$'}14.99/yr", null, null, 10]
              ],
              null,
              "en"
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(gtxJson))
        server.start()

        val cacheFile = File(tempFolder.root, "overseas_translations.json")
        try {
            val translator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/translate_a/single").toString(),
            )
            val input = "Flash Sale: Los Angeles NVMe VPS Unmetered traffic, Recurring ${'$'}14.99/yr"
            val config = OverseasTranslationConfig(engineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY)

            val first = translator.translateText(input, config)
            assertEquals(TranslationTier.GOOGLE_GTX, first.tier)
            assertFalse(first.fromCache)
            assertTrue(first.translatedText.contains("限时闪购"))
            assertTrue(first.translatedText.contains("不限流量"))
            assertTrue(first.translatedText.contains("续费同价"))
            assertEquals(1, server.requestCount)
            assertTrue(cacheFile.exists())

            val reloadedTranslator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/translate_a/single").toString(),
            )
            val second = reloadedTranslator.translateText(input, config)
            assertTrue(second.fromCache)
            assertEquals(first.translatedText, second.translatedText)
            assertEquals(TranslationTier.GOOGLE_GTX, second.tier)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun segmentedMarkdownTranslationProtectsCodeFencesCouponsImagesAndLinkUrls() = runTest {
        val server = MockWebServer()
        // Block 1 (before code fence): heading + list item with inline link & bare coupon wrapped as code
        val block1Gtx = """
            [
              [
                ["独享 NVMe 套餐\n", "Dedicated NVMe Plans\n", null, null, 10],
                ["** 2 vCPU ** / 4GB 内存 — 【立即订购洛杉矶】 kph 0 kph\n", "**2 vCPU** / 4GB RAM — [Order LA]KPH0KPH\n", null, null, 10],
                ["使用优惠码：KPH1KPH 享受终身续费同价", "Use Promo Code: KPH1KPH for lifetime recurring", null, null, 10]
              ],
              null,
              "en"
            ]
        """.trimIndent()
        // Block 2 (after code fence & image): network looking glass line with bare URL
        val block2Gtx = """
            [
              [
                ["东京测试 IP 与 窥镜：KPH0KPH", "Tokyo Test IP & Looking Glass: KPH0KPH", null, null, 10]
              ],
              null,
              "en"
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(block1Gtx))
        server.enqueue(MockResponse().setResponseCode(200).setBody(block2Gtx))
        server.start()

        val cacheFile = File(tempFolder.root, "overseas_segmented_md.json")
        try {
            val translator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/translate_a/single").toString(),
            )
            val rawMd = """
                ### Dedicated NVMe Plans
                - **2 vCPU** / 4GB RAM — [Order LA](https://my.racknerd.com/cart.php?a=add&pid=901)
                - Use Promo Code: SPRING2026OFF for lifetime recurring

                ```bash
                wget -qO- bench.sh | bash
                # Promo code SPRING2026OFF must stay untouched
                ```

                ![Promo Banner](https://img.example.com/banner-2026.png)

                Tokyo Test IP &amp; Looking Glass: https://lg-tyo.example.com/100mb.bin
            """.trimIndent()

            val res = translator.translateMarkdown(
                markdown = rawMd,
                config = OverseasTranslationConfig(engineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY),
            )

            assertEquals(TranslationTier.GOOGLE_GTX, res.tier)
            val out = res.translatedText
            // Heading & list markers preserved cleanly; broken bold spacing repaired
            assertTrue(out.contains("### 独享 NVMe 套餐"))
            assertTrue(out.contains("- **2 vCPU** / 4GB 内存 — [立即订购洛杉矶](https://my.racknerd.com/cart.php?a=add&pid=901)"))
            // Promo code SPRING2026OFF was protected as inline code and NOT translated into "春天2026"
            assertTrue(out.contains("`SPRING2026OFF`"))
            // Fenced code block and standalone image preserved 100% verbatim
            assertTrue(out.contains("```bash\nwget -qO- bench.sh | bash\n# Promo code SPRING2026OFF must stay untouched\n```"))
            assertTrue(out.contains("![Promo Banner](https://img.example.com/banner-2026.png)"))
            // Bare URL preserved byte-for-byte & "窥镜" polished to "Looking Glass 测速面板"
            assertTrue(out.contains("Looking Glass 测速面板"))
            assertTrue(out.contains("https://lg-tyo.example.com/100mb.bin"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun markdownOfflineFallbackPreservesLinksAndCodeSpansWhenGtxFails() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        server.start()

        val cacheFile = File(tempFolder.root, "overseas_md_offline.json")
        try {
            val translator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/translate_a/single").toString(),
            )
            val rawMd = """
                ## Dedicated NVMe VPS in Tokyo & Los Angeles
                - Double Bandwidth, Unmetered Recurring
                - Promo Code: `LET2026_RECURRING`
                - [Order Now](https://example.com/order?location=tokyo)
            """.trimIndent()

            val res = translator.translateMarkdown(
                markdown = rawMd,
                config = OverseasTranslationConfig(engineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY),
            )
            assertEquals(TranslationTier.OFFLINE_GLOSSARY, res.tier)
            assertTrue(res.translatedText.contains("东京"))
            assertTrue(res.translatedText.contains("洛杉矶"))
            assertTrue(res.translatedText.contains("双倍流量"))
            assertTrue(res.translatedText.contains("不限流量"))
            assertTrue(res.translatedText.contains("续费同价"))
            assertTrue(res.translatedText.contains("`LET2026_RECURRING`"))
            assertTrue(res.translatedText.contains("[立即下单](https://example.com/order?location=tokyo)"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun translateRepostContentTranslatesEnglishTitleBodyAndExtraLinks() = runTest {
        val server = MockWebServer()
        // 1st call: title translation
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[[["CloudCone 东京限时闪购 NVMe 套餐", "CloudCone Tokyo Flash Sale NVMe Plans", null, null, 10]], null, "en"]""",
            ),
        )
        // 2nd call: body translation
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[[["东京机房即时开通，支持 [立即下单] KPH0KPH", "Instant setup in Tokyo, [Order Here]KPH0KPH", null, null, 10]], null, "en"]""",
            ),
        )
        server.start()

        val cacheFile = File(tempFolder.root, "repost_translate.json")
        try {
            val translator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/translate_a/single").toString(),
            )
            val repost = OverseasRepostContent(
                id = "3001",
                title = "CloudCone Tokyo Flash Sale NVMe Plans",
                titleCn = "",
                merchant = "CloudCone",
                author = "cloudcone",
                sourceLink = "https://lowendtalk.com/discussion/3001/cloudcone",
                bodyMarkdown = "- Instant setup in Tokyo, [Order Here](https://app.cloudcone.com/vps/99)",
                links = listOf(
                    OverseasLabeledLink("Looking Glass", "https://lg.cloudcone.com"),
                ),
            )

            val translated = translator.translateRepostContent(repost)
            assertEquals("CloudCone 东京限时闪购 NVMe 套餐", translated.titleCn)
            assertTrue(translated.bodyMarkdown.contains("- 东京机房即时开通，支持 [立即下单](https://app.cloudcone.com/vps/99)"))
            assertEquals("Looking Glass 测速面板", translated.links.first().text)
            assertEquals("https://lg.cloudcone.com", translated.links.first().url)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun supportsCustomOpenAiCompatibleAndDeepLxProviders() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"【AI译】RackNerd 洛杉矶 2核4G NVMe 固态 续费同价 ${'$'}14.99/yr"}}]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":200,"data":"【DeepLX译】法兰克福大盘鸡不限流量 €15/yr"}"""),
        )
        server.start()

        val cacheFile = File(tempFolder.root, "overseas_custom_cache.json")
        try {
            val translator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/unused").toString(),
            )

            val openAiConfig = OverseasTranslationConfig(
                engineKind = TranslationEngineKind.OPENAI_COMPATIBLE,
                customEndpointUrl = server.url("/v1").toString(),
                customApiKey = "sk-test-123",
                customModel = "gpt-4o-mini",
            )
            val openAiOut = translator.translateText("RackNerd LA 2vCPU 4GB NVMe \$14.99/yr", openAiConfig)
            assertEquals(TranslationTier.CUSTOM_API, openAiOut.tier)
            assertTrue(openAiOut.translatedText.contains("RackNerd"))
            val openAiReq = server.takeRequest()
            assertEquals("/v1/chat/completions", openAiReq.path)
            assertEquals("Bearer sk-test-123", openAiReq.getHeader("Authorization"))

            val deepLxConfig = OverseasTranslationConfig(
                engineKind = TranslationEngineKind.DEEPLX,
                customEndpointUrl = server.url("/translate").toString(),
                customApiKey = "dlx-token",
            )
            val deepLxOut = translator.translateText("Frankfurt Storage VPS Unmetered €15/yr", deepLxConfig)
            assertEquals(TranslationTier.CUSTOM_API, deepLxOut.tier)
            assertTrue(deepLxOut.translatedText.contains("法兰克福"))
            val deepLxReq = server.takeRequest()
            assertEquals("/translate", deepLxReq.path)
            assertEquals("Bearer dlx-token", deepLxReq.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun offlineGlossaryDirectUnitRulesAreIdempotentAndTranslateCommonVpsTermsCleanly() {
        val once = VpsGlossaryTranslator.translateTitleOffline(
            "Special Offers: 50% off Storage VPS in Frankfurt & Amsterdam - Lifetime Recurring",
        )
        assertTrue(once.contains("特惠套餐"))
        assertTrue(once.contains("立减 50%"))
        assertTrue(once.contains("大盘鸡"))
        assertTrue(once.contains("法兰克福"))
        assertTrue(once.contains("阿姆斯特丹"))
        assertTrue(once.contains("续费同价"))

        // Idempotency check: running offline glossary a second time must not duplicate suffixes
        val twice = VpsGlossaryTranslator.translateTitleOffline(once)
        assertEquals(once, twice)
    }
}
