package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasPriceConverter
import com.nodeseek.app.domain.overseas.OverseasSource
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
        // Simulated GTX response with literal awkward translations ("反复出现", "未计量", "闪光销售")
        val gtxJson = """
            [
              [
                ["黑色星期五闪光销售：洛杉矶 NVMe VPS ", "Black Friday Flash Sale: Los Angeles NVMe VPS ", null, null, 10],
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
                rateLimitDelayMillis = 0L,
            )
            val input = "Black Friday Flash Sale: Los Angeles NVMe VPS Unmetered traffic, Recurring ${'$'}14.99/yr"
            val config = OverseasTranslationConfig(engineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY)

            val first = translator.translateText(input, config)
            assertEquals(TranslationTier.GOOGLE_GTX, first.tier)
            assertFalse(first.fromCache)
            assertTrue(first.translatedText.contains("黑五"))
            assertTrue(first.translatedText.contains("限时闪购"))
            assertTrue(first.translatedText.contains("不限流量"))
            assertTrue(first.translatedText.contains("续费同价"))
            assertEquals(1, server.requestCount)
            assertTrue(cacheFile.exists())

            // Second call with a brand-new translator instance must hit disk cache with ZERO network calls!
            val reloadedTranslator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/translate_a/single").toString(),
                rateLimitDelayMillis = 0L,
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
    fun gracefullyFallsBackToOfflineVpsGlossaryWhenGtxIsBlockedOrFails() = runTest {
        val server = MockWebServer()
        // Simulate Google GTX blocked / HTTP 429 or 503
        repeat(6) {
            server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        }
        server.start()

        val cacheFile = File(tempFolder.root, "overseas_translations_offline.json")
        try {
            val translator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/translate_a/single").toString(),
                rateLimitDelayMillis = 0L,
            )
            val fx = PreviewOverseasIntelRepository.buildDefaultFxState()
            val offer = OverseasDealOffer(
                id = "let-offline-1",
                source = OverseasSource.LET,
                originalTitle = "Black Friday Flash Sale: Dedicated NVMe VPS in Tokyo & Los Angeles - Unmetered Recurring",
                originalSummary = "Double Bandwidth, Coupon Code: BF2026, Out of Stock soon!",
                authorOrProvider = "MegaHost",
                sourceLink = "https://lowendtalk.com/discussion/999",
                couponCodes = listOf("BF2026"),
                priceMatches = OverseasPriceConverter.extractAndConvert("${'$'}12.99/yr", fxRatesState = fx),
                specHighlights = listOf("2 vCPU", "4GB RAM", "NVMe", "东京", "洛杉矶"),
            )

            val translated = translator.translateOffer(
                offer = offer,
                config = OverseasTranslationConfig(engineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY),
            )

            assertEquals(TranslationTier.OFFLINE_GLOSSARY, translated.translationTier)
            val zhTitle = translated.translatedTitle.orEmpty()
            assertTrue("Expected 黑五 in: $zhTitle", zhTitle.contains("黑五"))
            assertTrue("Expected 限时闪购 in: $zhTitle", zhTitle.contains("限时闪购"))
            assertTrue("Expected 独享 in: $zhTitle", zhTitle.contains("独享"))
            assertTrue("Expected NVMe 固态 in: $zhTitle", zhTitle.contains("NVMe 固态"))
            assertTrue("Expected 东京 in: $zhTitle", zhTitle.contains("东京"))
            assertTrue("Expected 洛杉矶 in: $zhTitle", zhTitle.contains("洛杉矶"))
            assertTrue("Expected 不限流量 in: $zhTitle", zhTitle.contains("不限流量"))
            assertTrue("Expected 续费同价 in: $zhTitle", zhTitle.contains("续费同价"))

            val zhSummary = translated.translatedSummary.orEmpty()
            assertTrue(zhSummary.contains("【配置/机房】"))
            assertTrue(zhSummary.contains("【折算参考】"))
            assertTrue(zhSummary.contains("【优惠码】BF2026"))
            assertTrue(zhSummary.contains("双倍流量"))
            assertTrue(zhSummary.contains("已缺货"))
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
                rateLimitDelayMillis = 0L,
            )

            // Test OpenAI endpoint normalization (passing base `/v1` should resolve `/v1/chat/completions`)
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

            // Test DeepLX provider
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
    fun offlineGlossaryDirectUnitRulesTranslateCommonVpsTermsCleanly() {
        val out = VpsGlossaryTranslator.translateTitleOffline(
            "Cyber Monday: 50% off Storage VPS in Frankfurt & Amsterdam - Lifetime Recurring",
        )
        assertTrue(out.contains("网一大促"))
        assertTrue(out.contains("立减 50%"))
        assertTrue(out.contains("大盘鸡"))
        assertTrue(out.contains("法兰克福"))
        assertTrue(out.contains("阿姆斯特丹"))
        assertTrue(out.contains("续费同价"))
    }

    @Test
    fun batchTranslationCircuitBreakerAvoidsRepeatedGtxTimeoutsAndClearsCache() = runTest {
        val server = MockWebServer()
        // Enough failing responses if circuit breaker didn't exist
        repeat(10) {
            server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
        }
        server.start()

        val cacheFile = File(tempFolder.root, "overseas_batch_cache.json")
        try {
            val translator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/translate_a/single").toString(),
                rateLimitDelayMillis = 0L,
            )
            val offers = (1..5).map { idx ->
                OverseasDealOffer(
                    id = "let-batch-$idx",
                    source = OverseasSource.LET,
                    originalTitle = "Black Friday VPS #$idx in Tokyo Recurring",
                    originalSummary = "",
                    authorOrProvider = "Host$idx",
                    sourceLink = "https://lowendtalk.com/discussion/$idx",
                )
            }

            val translated = translator.translateBatch(
                offers = offers,
                config = OverseasTranslationConfig(engineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY),
            )

            assertEquals(5, translated.size)
            assertTrue(translated.all { it.translationTier == TranslationTier.OFFLINE_GLOSSARY })
            assertTrue(translated.all { it.translatedTitle.orEmpty().contains("黑五特惠") })
            // Circuit breaker triggers after 2 consecutive failures -> only 2 HTTP calls instead of 5!
            assertEquals(2, server.requestCount)
            assertEquals(5, translator.cacheSize())

            // Test clearCache
            translator.clearCache()
            assertEquals(0, translator.cacheSize())
            assertFalse(cacheFile.exists())

            // Test testConnection graceful fallback message when GTX fails
            val testResult = translator.testConnection(
                OverseasTranslationConfig(engineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY),
            )
            assertTrue(testResult.isSuccess)
            assertTrue(testResult.getOrThrow().contains("离线 VPS 术语引擎"))
        } finally {
            server.shutdown()
        }
    }
}
