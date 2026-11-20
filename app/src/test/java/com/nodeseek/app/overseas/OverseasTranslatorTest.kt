package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.OverseasTranslator
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
            // Inline code and URL target must stay untouched!
            assertTrue(res.translatedText.contains("`LET2026_RECURRING`"))
            assertTrue(res.translatedText.contains("](https://example.com/order?location=tokyo)"))
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
    fun offlineGlossaryDirectUnitRulesTranslateCommonVpsTermsCleanly() {
        val out = VpsGlossaryTranslator.translateTitleOffline(
            "Special Offers: 50% off Storage VPS in Frankfurt & Amsterdam - Lifetime Recurring",
        )
        assertTrue(out.contains("特惠套餐"))
        assertTrue(out.contains("立减 50%"))
        assertTrue(out.contains("大盘鸡"))
        assertTrue(out.contains("法兰克福"))
        assertTrue(out.contains("阿姆斯特丹"))
        assertTrue(out.contains("续费同价"))
    }
}
