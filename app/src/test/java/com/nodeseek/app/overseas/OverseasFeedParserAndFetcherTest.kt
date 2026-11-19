package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.LebFeedParser
import com.nodeseek.app.data.overseas.LetFeedParser
import com.nodeseek.app.data.overseas.OverseasFeedFetcher
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasSource
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.domain.overseas.TranslationTier
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal

class OverseasFeedParserAndFetcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleLetRss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel>
            <title>Offers - LowEndTalk</title>
            <item>
              <title><![CDATA[[Black Friday] RackNerd 2 vCPU 4GB RAM 45GB NVMe Los Angeles - ${'$'}14.99/yr Recurring]]></title>
              <link>https://lowendtalk.com/discussion/209901/black-friday-racknerd</link>
              <guid>https://lowendtalk.com/discussion/209901</guid>
              <dc:creator>dustinc</dc:creator>
              <pubDate>Thu, 19 Nov 2026 10:30:00 +0000</pubDate>
              <description><![CDATA[
                <p>Black Friday KVM VPS Special!</p>
                <ul>
                  <li>2 vCPU Ryzen</li>
                  <li>4GB RAM</li>
                  <li>45GB NVMe SSD</li>
                  <li>4TB Bandwidth @ 1Gbps</li>
                  <li>Location: Los Angeles (CN2 GIA optional)</li>
                </ul>
                <p>Promo code: <strong>LETBF2026</strong></p>
                <p><a href="https://my.racknerd.com/cart.php?a=add&amp;pid=901&amp;promocode=LETBF2026">Order Now</a></p>
              ]]></description>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private val sampleLetVanillaJson = """
        {
          "Discussions": [
            {
              "DiscussionID": 210555,
              "Name": "GreenCloud Tokyo EPYC Storage VPS - ${'$'}25/yr Unmetered",
              "Url": "https://lowendtalk.com/discussion/210555/greencloud-tokyo",
              "InsertName": "GreenCloudVPS",
              "DateInserted": "2026-11-19 12:00:00",
              "Body": "<p>2 vCPU EPYC, 2GB RAM, 500GB HDD, Unmetered @ 10Gbps in Tokyo!</p><p>Use code GCJP2026 at <a href=\"https://billing.greencloudvps.com/index.php?/cart/tokyo-vps/\">Order Here</a></p>"
            }
          ]
        }
    """.trimIndent()

    private val sampleLebRss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel>
            <title>LowEndBox</title>
            <item>
              <title><![CDATA[HostHatch – Flash Sale 4GB NVMe VPS in Amsterdam for €15/year]]></title>
              <link>https://lowendbox.com/blog/hosthatch-flash-sale-amsterdam/</link>
              <guid isPermaLink="false">https://lowendbox.com/?p=45123</guid>
              <dc:creator>raindog308</dc:creator>
              <category><![CDATA[Offers]]></category>
              <category><![CDATA[Black Friday]]></category>
              <pubDate>Thu, 19 Nov 2026 11:15:00 +0000</pubDate>
              <description><![CDATA[<p>Great European NVMe VPS flash deal starting at €15/year.</p>]]></description>
              <content:encoded><![CDATA[
                <table>
                  <tr><th>Plan</th><th>Specs</th><th>Price</th></tr>
                  <tr><td>NVMe-4G</td><td>2 vCPU / 4GB RAM / 50GB NVMe / 5TB Traffic</td><td>€15/year</td></tr>
                </table>
                <p>Coupon Code: HHFLASH26</p>
                <p><a href="https://cloud.hosthatch.com/order/nvme-4g">Buy Now</a></p>
                <p><a href="https://lowendbox.com/tag/amsterdam/">More Amsterdam deals</a></p>
              ]]></content:encoded>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun parsesLetRssWithOrderLinksCouponsSpecsAndCnyPrices() {
        val fx = PreviewOverseasIntelRepository.buildDefaultFxState()
        val offers = LetFeedParser.parseRss(sampleLetRss, fx)

        assertEquals(1, offers.size)
        val offer = offers.first()
        assertEquals("let-209901", offer.id)
        assertEquals(OverseasSource.LET, offer.source)
        assertEquals("dustinc", offer.authorOrProvider)
        assertTrue(offer.isFlashOrBlackFriday)
        assertFalse(offer.isOutOfStockHint)
        assertEquals(listOf("https://my.racknerd.com/cart.php?a=add&pid=901&promocode=LETBF2026"), offer.orderLinks)
        assertEquals(listOf("LETBF2026"), offer.couponCodes)
        assertTrue(offer.specHighlights.contains("2 vCPU"))
        assertTrue(offer.specHighlights.contains("4GB RAM"))
        assertTrue(offer.specHighlights.contains("45GB NVMe"))
        assertTrue(offer.specHighlights.contains("洛杉矶"))
        assertTrue(offer.specHighlights.contains("CN2 GIA"))
        assertEquals(BigDecimal("108.68"), offer.primaryPrice?.cnyAmount)
        assertTrue(offer.publishedAtEpochMillis > 0L)
    }

    @Test
    fun parsesLebRssSpecTablesDirectOrderLinksAndFiltersSelfLinks() {
        val fx = PreviewOverseasIntelRepository.buildDefaultFxState()
        val offers = LebFeedParser.parseRss(sampleLebRss, fx)

        assertEquals(1, offers.size)
        val offer = offers.first()
        assertEquals("leb-45123", offer.id)
        assertEquals(OverseasSource.LEB, offer.source)
        assertEquals("HostHatch", offer.authorOrProvider)
        assertTrue(offer.isFlashOrBlackFriday)
        assertEquals(listOf("https://cloud.hosthatch.com/order/nvme-4g"), offer.orderLinks)
        assertEquals(listOf("HHFLASH26"), offer.couponCodes)
        assertTrue(offer.originalSummary.contains("NVMe-4G | 2 vCPU / 4GB RAM / 50GB NVMe / 5TB Traffic | €15/year"))
        assertEquals(ValuationCurrency.EUR, offer.primaryPrice?.currency)
        assertEquals(BigDecimal("117.75"), offer.primaryPrice?.cnyAmount)
        assertTrue(offer.specHighlights.contains("阿姆斯特丹"))
    }

    @Test
    fun fetcherFallsBackFromLetRssFailureToVanillaJsonAndCombinesLeb() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/let/feed.rss" -> MockResponse().setResponseCode(503).setBody("Cloudflare challenge")
                "/let/offers.json" -> MockResponse().setResponseCode(200).setBody(sampleLetVanillaJson)
                "/leb/feed/" -> MockResponse().setResponseCode(200).setBody(sampleLebRss)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val fetcher = OverseasFeedFetcher(
                letRssUrl = server.url("/let/feed.rss").toString(),
                letJsonFallbackUrl = server.url("/let/offers.json").toString(),
                lebRssUrl = server.url("/leb/feed/").toString(),
            )
            val result = fetcher.fetchAll(PreviewOverseasIntelRepository.buildDefaultFxState())

            assertTrue(result.usedLetJsonFallback)
            assertTrue(result.sourceErrors.isEmpty())
            assertEquals(2, result.offers.size)

            val letOffer = result.offers.first { it.source == OverseasSource.LET }
            assertEquals("let-210555", letOffer.id)
            assertEquals("GreenCloudVPS", letOffer.authorOrProvider)
            assertEquals(listOf("GCJP2026"), letOffer.couponCodes)
            assertTrue(letOffer.orderLinks.any { it.contains("billing.greencloudvps.com") })
            assertTrue(letOffer.specHighlights.contains("东京"))
            assertTrue(letOffer.specHighlights.contains("不限流量"))

            val lebOffer = result.offers.first { it.source == OverseasSource.LEB }
            assertEquals("leb-45123", lebOffer.id)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun repositoryPersistsDiskCacheAndRestoresOnColdStartWithoutNetwork() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/let/feed.rss" -> MockResponse().setResponseCode(200).setBody(sampleLetRss)
                "/leb/feed/" -> MockResponse().setResponseCode(200).setBody(sampleLebRss)
                "/fx" -> MockResponse().setResponseCode(200)
                    .setBody("""{"amount":1.0,"base":"CNY","date":"2026-11-19","rates":{"USD":0.138,"EUR":0.128}}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()

        val cacheFile = File(tempFolder.root, "overseas_intel_cache.json")
        val prefsFile = File(tempFolder.root, "overseas_translator_prefs.json")
        val transFile = File(tempFolder.root, "overseas_translations.json")
        val fxFile = File(tempFolder.root, "valuation_fx_cache.json")

        try {
            val fxRepo = ExchangeRateRepository(
                cacheFile = fxFile,
                endpointUrl = server.url("/fx").toString(),
            )
            val fetcher = OverseasFeedFetcher(
                letRssUrl = server.url("/let/feed.rss").toString(),
                letJsonFallbackUrl = server.url("/let/offers.json").toString(),
                lebRssUrl = server.url("/leb/feed/").toString(),
            )
            val translator = OverseasTranslator(
                cacheFile = transFile,
                gtxBaseUrl = server.url("/gtx_down").toString(),
                rateLimitDelayMillis = 0L,
            )

            val repo = OverseasIntelRepositoryImpl(
                cacheFile = cacheFile,
                prefsFile = prefsFile,
                exchangeRateRepository = fxRepo,
                feedFetcher = fetcher,
                translator = translator,
            )

            // Save custom display mode & offline engine config
            repo.updateTranslationConfig(
                OverseasTranslationConfig(
                    engineKind = TranslationEngineKind.OFFLINE_GLOSSARY_ONLY,
                    displayMode = OverseasDisplayMode.CHINESE,
                ),
            )

            val refreshed = repo.refresh(forceTranslate = false).getOrThrow()
            assertEquals(2, refreshed.size)
            assertTrue(cacheFile.exists())
            assertTrue(prefsFile.exists())
            assertTrue(refreshed.all { !it.translatedTitle.isNullOrBlank() })
            assertEquals(TranslationTier.OFFLINE_GLOSSARY, refreshed.first().translationTier)
        } finally {
            server.shutdown()
        }

        // Cold start in a fresh repository instance with NO network server running!
        val coldFxRepo = ExchangeRateRepository(cacheFile = fxFile, endpointUrl = "http://127.0.0.1:1/unreachable")
        val coldRepo = OverseasIntelRepositoryImpl(
            cacheFile = cacheFile,
            prefsFile = prefsFile,
            exchangeRateRepository = coldFxRepo,
            feedFetcher = OverseasFeedFetcher(
                letRssUrl = "http://127.0.0.1:1/unreachable",
                letJsonFallbackUrl = "http://127.0.0.1:1/unreachable",
                lebRssUrl = "http://127.0.0.1:1/unreachable",
            ),
            translator = OverseasTranslator(cacheFile = transFile, gtxBaseUrl = "http://127.0.0.1:1/unreachable"),
        )

        val restored = coldRepo.loadFromCache()
        assertEquals(2, restored.size)
        assertTrue(coldRepo.snapshot.value.syncStatus.loadedFromCache)
        assertEquals(OverseasDisplayMode.CHINESE, coldRepo.snapshot.value.translationConfig.displayMode)
        assertEquals(TranslationEngineKind.OFFLINE_GLOSSARY_ONLY, coldRepo.snapshot.value.translationConfig.engineKind)
        assertNotNull(restored.first().primaryPrice?.cnyAmount)
    }

    @Test
    fun parsesLetVanillaApiV2ArrayAndPreservesHealthySourceOnPartialFailure() = runTest {
        val vanillaV2Array = """
            [
              {
                "discussionID": 211001,
                "name": "Dedipath Revival 2C4G KVM - ${'$'}11/yr",
                "url": "/discussion/211001/revival-deal",
                "insertUser": { "name": "RevivalHost" },
                "dateInserted": "2026-11-19T13:45:00Z",
                "body": "<p>2C/4G 30GB NVMe in San Jose. Order: https://portal.revivalhost.net/cart.php?a=add&pid=7</p>"
              }
            ]
        """.trimIndent()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/let/feed.rss" -> MockResponse().setResponseCode(500)
                "/let/offers.json" -> MockResponse().setResponseCode(200).setBody(vanillaV2Array)
                "/leb/feed/" -> MockResponse().setResponseCode(502).setBody("Bad Gateway")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val fetcher = OverseasFeedFetcher(
                letRssUrl = server.url("/let/feed.rss").toString(),
                letJsonFallbackUrl = server.url("/let/offers.json").toString(),
                lebRssUrl = server.url("/leb/feed/").toString(),
            )
            val result = fetcher.fetchAll(PreviewOverseasIntelRepository.buildDefaultFxState())

            assertTrue(result.usedLetJsonFallback)
            assertEquals(1, result.offers.size)
            assertTrue(result.sourceErrors.containsKey(OverseasSource.LEB))
            assertFalse(result.sourceErrors.containsKey(OverseasSource.LET))

            val offer = result.offers.first()
            assertEquals("let-211001", offer.id)
            assertEquals("RevivalHost", offer.authorOrProvider)
            assertEquals("https://lowendtalk.com/discussion/211001/revival-deal", offer.sourceLink)
            assertTrue(offer.orderLinks.contains("https://portal.revivalhost.net/cart.php?a=add&pid=7"))
            assertTrue(offer.specHighlights.contains("2 vCPU"))
            assertTrue(offer.specHighlights.contains("4GB RAM"))
            assertTrue(offer.specHighlights.contains("圣何塞"))
        } finally {
            server.shutdown()
        }
    }
}
