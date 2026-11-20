package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.OverseasApiCrypto
import com.nodeseek.app.data.overseas.OverseasApiException
import com.nodeseek.app.data.overseas.OverseasIntelApiClient
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.domain.overseas.OfferFilterReason
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasOfferFilterConfig
import com.nodeseek.app.domain.overseas.OverseasOfferQualityFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class OverseasOfferFilterAndResilienceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun qualityFilterRejectsGenericBuzzSpamAndNonServerAdsWithoutKillingRealPromos() {
        val spamOffers = listOf(
            offer(
                id = "223563",
                title = "99.9% Uptime High powered Dedicated Servers 32+ Locations World Wide",
                merchant = "group44",
            ),
            offer(
                id = "223552",
                title = "99.9% Uptime High powered Cloud Servers on CloudVM 32+ Locations World Wide 3 Day TRIAL",
                merchant = "group44",
            ),
            offer(
                id = "218101",
                title = "PawPayments — licensed crypto payment gateway | conversion to USDT/USDC built in, flat 0.5%",
                merchant = "PawPayments",
            ),
            offer(
                id = "221543",
                title = "CorePanel - a safe and modern web hosting control panel",
                merchant = "CorePanel",
            ),
            offer(
                id = "219671",
                title = "Nobody needs another SSH client. We made one anyway.",
                merchant = "cloudblast",
            ),
            offer(
                id = "217775",
                title = "[Free Beta] AKDNS: unlock Netflix / Disney+ / ChatGPT via DNS — one command, no proxy, reversible",
                merchant = "AkileCloud",
            ),
        )

        spamOffers.forEach { spam ->
            val verdict = OverseasOfferQualityFilter.evaluate(spam)
            assertFalse("Expected spam offer ${spam.id} (${spam.title}) to be rejected", verdict.accepted)
        }

        assertEquals(
            OfferFilterReason.GENERIC_BUZZ_SPAM,
            OverseasOfferQualityFilter.evaluate(spamOffers[0]).reason,
        )
        assertEquals(
            OfferFilterReason.NON_SERVER_AD,
            OverseasOfferQualityFilter.evaluate(spamOffers[2]).reason,
        )

        val legitimateOffers = listOf(
            offer(
                id = "223558",
                title = "RackNerd 2026黑色星期五大促 → 全新AdminBolt主机 + ctrl面板免费",
                originalTitle = "RackNerd’s BLACK FRIDAY TAKEOVER! NEW DEALS, NEW TECH, AdminBolt HOSTING + HUNDREDS OF GIVEAWAYS!",
                summary = "涵盖核心亮点与新功能、促销活动预告相关方案；适合轻量建站者。",
                merchant = "RackNerd",
            ),
            offer(
                id = "221186",
                title = "Looking for a Hetzner Alternative? Compare Our Cloud VPS",
                merchant = "CubePath",
            ),
            offer(
                id = "218942",
                title = "A NEW DAWN HAS ARRIVED >> The i9 Dream™ << ONLY FROM DediRock!",
                merchant = "DediRock",
            ),
            offer(
                id = "214985",
                title = "tothost.vn| Hello TOT K-HCM - Add K, Save 20%| Asia Region| Native Clean IP| Unlimit Data",
                merchant = "tothost.vn",
            ),
            offer(
                id = "199102",
                title = "DeluxHost.net | High Performance | Fast Support | SPECIAL OFFERS | DE",
                merchant = "DeluxHost.net",
            ),
            offer(
                id = "221410",
                title = "K.N Cloud Mid-Autumn Festival Special",
                merchant = "koanode",
            ),
            offer(
                id = "990001",
                title = "99.9% Uptime KVM VPS - $12/yr 2 vCPU 4GB RAM 40GB NVMe",
                merchant = "ReliableHost",
            ),
        )

        legitimateOffers.forEach { legit ->
            val verdict = OverseasOfferQualityFilter.evaluate(legit)
            assertTrue("Expected legitimate offer ${legit.id} (${legit.title}) to pass, got ${verdict.reason}", verdict.accepted)
        }

        // Configurable disable & custom merchant/keyword rules
        val disabled = OverseasOfferFilterConfig(enabled = false)
        assertEquals(spamOffers.size, OverseasOfferQualityFilter.filter(spamOffers, disabled).size)

        val customBlocked = OverseasOfferFilterConfig(
            blockedMerchants = setOf("cubepath"),
            extraBlockedKeywords = setOf("AdminBolt"),
        )
        assertEquals(
            OfferFilterReason.BLOCKED_MERCHANT,
            OverseasOfferQualityFilter.evaluate(legitimateOffers[1], customBlocked).reason,
        )
        assertEquals(
            OfferFilterReason.BLOCKED_KEYWORD,
            OverseasOfferQualityFilter.evaluate(legitimateOffers[0], customBlocked).reason,
        )
    }

    @Test
    fun apiClientSupportsConditional304AndRespectsLargeRetryAfterCooldown() = runTest {
        val secret = ByteArray(32) { (it + 3).toByte() }
        val aesKey = OverseasApiCrypto.deriveAesKey(secret)
        val server = MockWebServer()
        val encryptedPage = OverseasApiCrypto.encryptEnvelope(
            aesKey = aesKey,
            plaintextUtf8 = """{"page":1,"page_size":20,"total":1,"has_more":false,"items":[{"id":"223566","title":"LayerOne 黑五促销","summary":"涵盖限时闪购方案。","merchant":"LayerOne","published_at_ms":1795122963000,"url":"https://lowendtalk.com/discussion/223566"}]}""",
        )

        // 1) Initial 200 with ETag & Last-Modified
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("ETag", "\"offers-v1\"")
                .addHeader("Last-Modified", "Fri, 20 Nov 2026 09:00:00 GMT")
                .setBody(encryptedPage),
        )
        // 2) Conditional 304 Not Modified
        server.enqueue(
            MockResponse()
                .setResponseCode(304)
                .addHeader("ETag", "\"offers-v1\""),
        )
        // 3) 429 with Retry-After: 30 (exceeds inline threshold -> fail fast & activate cooldown)
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "30")
                .setBody("""{"error":{"code":"RATE_LIMIT","message":"rate limited"}}"""),
        )
        // 4) Recovery response once 30s cooldown elapses
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("ETag", "\"offers-v2\"")
                .setBody(encryptedPage),
        )
        server.start()

        val clockSec = AtomicLong(1_795_160_000L)
        try {
            val client = OverseasIntelApiClient(
                baseUrlProvider = { server.url("/").toString() },
                masterSecretProvider = { secret.copyOf() },
                httpClient = OkHttpClient(),
                maxAttempts = 3,
                baseRetryDelayMillis = 100L,
                nowSecondsProvider = { clockSec.get() },
                sleeper = {},
            )

            val first = client.fetchOffersPageConditional(page = 1, pageSize = 20)
            assertFalse(first.notModified)
            assertEquals("\"offers-v1\"", first.etag)
            assertEquals("Fri, 20 Nov 2026 09:00:00 GMT", first.lastModified)
            assertEquals(1, first.data?.items?.size)
            server.takeRequest()

            val cond = client.fetchOffersPageConditional(
                page = 1,
                pageSize = 20,
                ifNoneMatch = first.etag,
                ifModifiedSince = first.lastModified,
            )
            assertTrue(cond.notModified)
            assertEquals("\"offers-v1\"", cond.etag)
            val req2 = server.takeRequest()
            assertEquals("\"offers-v1\"", req2.getHeader("If-None-Match"))
            assertEquals("Fri, 20 Nov 2026 09:00:00 GMT", req2.getHeader("If-Modified-Since"))

            // 429 Retry-After: 30 must NOT retry 3 times or truncate wait to 5s
            try {
                client.fetchOffersPage(page = 1, pageSize = 20)
                fail("Expected 429 OverseasApiException")
            } catch (e: OverseasApiException) {
                assertEquals(429, e.statusCode)
                assertEquals(30L, e.retryAfterSeconds)
            }
            assertEquals(3, server.requestCount)

            // Immediate retry during cooldown is blocked locally without hitting server
            clockSec.addAndGet(10L)
            try {
                client.fetchOffersPage(page = 1, pageSize = 20)
                fail("Expected local cooldown OverseasApiException")
            } catch (e: OverseasApiException) {
                assertEquals(429, e.statusCode)
                assertEquals("RATE_LIMITED", e.errorCode)
            }
            assertEquals(3, server.requestCount)

            // After 30s cooldown passes, request goes through normally
            clockSec.addAndGet(25L)
            val recovered = client.fetchOffersPage(page = 1, pageSize = 20)
            assertEquals(1, recovered.items.size)
            assertEquals(4, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun repositoryCoalescesConcurrentRequestsCachesWithTtlAndUpgradesPendingAiDetail() = runTest {
        val secret = ByteArray(32) { (it + 11).toByte() }
        val aesKey = OverseasApiCrypto.deriveAesKey(secret)

        val listRequests = AtomicInteger(0)
        val detailRequests = AtomicInteger(0)
        val detailPhase = AtomicInteger(1)

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v1/offers?") -> {
                        val callNum = listRequests.incrementAndGet()
                        val ifNoneMatch = request.getHeader("If-None-Match")
                        if (callNum >= 2 && ifNoneMatch == "\"list-etag-1\"") {
                            MockResponse()
                                .setResponseCode(304)
                                .addHeader("ETag", "\"list-etag-1\"")
                        } else {
                            val plain = """
                                {
                                  "page": 1,
                                  "page_size": 20,
                                  "total": 2,
                                  "has_more": false,
                                  "items": [
                                    {
                                      "id": "223566",
                                      "title": "LayerOne 黑五促销",
                                      "original_title": "LayerOne - BLACK FRIDAY! - ${'$'}7/yr",
                                      "summary": "涵盖限时闪购方案；适合轻量建站。",
                                      "merchant": "LayerOne",
                                      "published_at_ms": 1795122963000,
                                      "url": "https://lowendtalk.com/discussion/223566"
                                    },
                                    {
                                      "id": "223563",
                                      "title": "99.9% Uptime High powered Dedicated Servers 32+ Locations World Wide",
                                      "original_title": "99.9% Uptime High powered Dedicated Servers 32+ Locations World Wide",
                                      "summary": "来自 group44 的 LowEndTalk 海外服务器与 VPS 促销情报，点击查看完整方案详情。",
                                      "merchant": "group44",
                                      "published_at_ms": 1795117086000,
                                      "url": "https://lowendtalk.com/discussion/223563"
                                    }
                                  ]
                                }
                            """.trimIndent()
                            MockResponse()
                                .setResponseCode(200)
                                .addHeader("ETag", "\"list-etag-1\"")
                                .setBody(OverseasApiCrypto.encryptEnvelope(aesKey, plain))
                        }
                    }

                    path == "/api/v1/offers/223566" -> {
                        detailRequests.incrementAndGet()
                        if (detailPhase.get() == 1) {
                            val pendingPlain = """
                                {
                                  "id": "223566",
                                  "title": "LayerOne - BLACK FRIDAY!",
                                  "merchant": "LayerOne",
                                  "url": "https://lowendtalk.com/discussion/223566",
                                  "tg_markdown": "📢 **新优惠信息**\n\n**LayerOne - BLACK FRIDAY!**",
                                  "structured": {
                                    "title_cn": "LayerOne - BLACK FRIDAY!",
                                    "sections": [],
                                    "order_links": [],
                                    "promo_code": "无",
                                    "pros": [],
                                    "cons": [],
                                    "suitable_for": "",
                                    "ai_comment": ""
                                  }
                                }
                            """.trimIndent()
                            MockResponse()
                                .setResponseCode(200)
                                .addHeader("ETag", "\"detail-pending\"")
                                .setBody(OverseasApiCrypto.encryptEnvelope(aesKey, pendingPlain))
                        } else {
                            val hydratedPlain = """
                                {
                                  "id": "223566",
                                  "title": "LayerOne 黑五促销 → ${'$'}7/年起",
                                  "merchant": "LayerOne",
                                  "url": "https://lowendtalk.com/discussion/223566",
                                  "tg_markdown": "## LayerOne 黑五促销\n\n### VPS 套餐\n- 1 vCPU / 1GB RAM → ${'$'}7/年",
                                  "structured": {
                                    "title_cn": "LayerOne 黑五促销 → ${'$'}7/年起",
                                    "sections": [{"header": "VPS 套餐", "items": ["• 1 vCPU / 1GB RAM → ${'$'}7/年"]}],
                                    "ai_comment": "年付门槛低"
                                  }
                                }
                            """.trimIndent()
                            MockResponse()
                                .setResponseCode(200)
                                .addHeader("ETag", "\"detail-hydrated\"")
                                .setBody(OverseasApiCrypto.encryptEnvelope(aesKey, hydratedPlain))
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val nowMs = AtomicLong(1_000_000L)
        try {
            val client = OverseasIntelApiClient(
                baseUrlProvider = { server.url("/").toString() },
                masterSecretProvider = { secret.copyOf() },
                httpClient = OkHttpClient(),
                nowSecondsProvider = { nowMs.get() / 1000L },
                sleeper = {},
            )
            val repo = OverseasIntelRepositoryImpl(
                listCacheFile = File(tempFolder.root, "offers.json"),
                detailCacheFile = File(tempFolder.root, "details.json"),
                prefsFile = File(tempFolder.root, "prefs.json"),
                apiClient = client,
                translator = OverseasTranslator(cacheFile = File(tempFolder.root, "trans.json")),
                nowMillisProvider = { nowMs.get() },
                minRefreshCooldownMillis = 8_000L,
                detailCacheTtlMillis = 15L * 60_000L,
                pendingDetailCacheTtlMillis = 10_000L,
            )

            // 1. Concurrent refresh coalescing -> only 1 HTTP request, spam filtered out
            val concurrentLists = (1..5).map { async { repo.refresh().getOrThrow() } }.awaitAll()
            assertEquals(1, listRequests.get())
            concurrentLists.forEach { items ->
                assertEquals(1, items.size)
                assertEquals("223566", items.first().id)
            }
            assertEquals(1, repo.snapshot.value.filteredOutCount)

            // 2. Refresh within 8s cooldown -> served from memory without HTTP request
            nowMs.addAndGet(3_000L)
            repo.refresh().getOrThrow()
            assertEquals(1, listRequests.get())

            // 3. Refresh after 8s cooldown -> sends If-None-Match, gets 304 Not Modified
            nowMs.addAndGet(10_000L)
            val after304 = repo.refresh().getOrThrow()
            assertEquals(2, listRequests.get())
            assertEquals(1, after304.size)

            // 4. Toggling filterConfig off reveals raw item immediately in memory without network
            repo.updateFilterConfig(OverseasOfferFilterConfig(enabled = false))
            assertEquals(2, repo.snapshot.value.offers.size)
            assertEquals(0, repo.snapshot.value.filteredOutCount)
            assertEquals(2, listRequests.get())

            // 5. Concurrent detail coalescing -> only 1 HTTP request for pending-AI fallback
            val concurrentDetails = (1..4).map { async { repo.getOfferDetail("223566").getOrThrow() } }.awaitAll()
            assertEquals(1, detailRequests.get())
            assertTrue(concurrentDetails.first().isPendingAiHydration)

            // 6. Within pendingDetailCacheTtlMillis (10s), cached fallback is returned without network
            nowMs.addAndGet(4_000L)
            val cachedPending = repo.getOfferDetail("223566").getOrThrow()
            assertEquals(1, detailRequests.get())
            assertTrue(cachedPending.isPendingAiHydration)

            // 7. After pending TTL expires (total +11s) and server finishes AI hydration, re-fetch upgrades automatically!
            detailPhase.set(2)
            nowMs.addAndGet(7_000L)
            val upgraded = repo.getOfferDetail("223566").getOrThrow()
            assertEquals(2, detailRequests.get())
            assertFalse(upgraded.isPendingAiHydration)
            assertTrue(upgraded.tgMarkdown.contains("1 vCPU"))

            // 8. Once hydrated, cached detail stays fresh for 15 minutes without network calls
            nowMs.addAndGet(5L * 60_000L)
            val stillCached = repo.getOfferDetail("223566").getOrThrow()
            assertEquals(2, detailRequests.get())
            assertEquals("年付门槛低", stillCached.structured?.aiComment)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun apiClientRetries5xxWithExponentialBackoffJitterAndRepositoryThrottlesFailedPagination() = runTest {
        val secret = ByteArray(32) { (it + 19).toByte() }
        val aesKey = OverseasApiCrypto.deriveAesKey(secret)

        val server = MockWebServer()
        val page1Plain = """
            {
              "page": 1,
              "page_size": 1,
              "total": 2,
              "has_more": true,
              "items": [
                {
                  "id": "101",
                  "title": "Noez 法兰克福 VPS 促销",
                  "summary": "涵盖常规 VPS 方案。",
                  "merchant": "noez.de",
                  "published_at_ms": 1795122963000,
                  "url": "https://lowendtalk.com/discussion/101"
                }
              ]
            }
        """.trimIndent()
        val page2Plain = """
            {
              "page": 2,
              "page_size": 1,
              "total": 2,
              "has_more": false,
              "items": [
                {
                  "id": "102",
                  "title": "LayerOne 限时 VPS 闪购",
                  "summary": "涵盖限时闪购方案。",
                  "merchant": "LayerOne",
                  "published_at_ms": 1795120000000,
                  "url": "https://lowendtalk.com/discussion/102"
                }
              ]
            }
        """.trimIndent()

        // Page 1 succeeds; then Page 2 fails 3 times (503 -> 503 -> 503), then Page 2 succeeds after cooldown.
        server.enqueue(MockResponse().setResponseCode(200).setBody(OverseasApiCrypto.encryptEnvelope(aesKey, page1Plain)))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"BUSY","message":"busy"}}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"BUSY","message":"busy"}}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"BUSY","message":"busy"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(OverseasApiCrypto.encryptEnvelope(aesKey, page2Plain)))
        server.start()

        val recordedDelays = mutableListOf<Long>()
        val nowMs = AtomicLong(2_000_000L)
        try {
            val client = OverseasIntelApiClient(
                baseUrlProvider = { server.url("/").toString() },
                masterSecretProvider = { secret.copyOf() },
                httpClient = OkHttpClient(),
                maxAttempts = 3,
                baseRetryDelayMillis = 200L,
                nowSecondsProvider = { nowMs.get() / 1000L },
                sleeper = { ms -> recordedDelays += ms },
            )
            val repo = OverseasIntelRepositoryImpl(
                listCacheFile = File(tempFolder.root, "p_offers.json"),
                detailCacheFile = File(tempFolder.root, "p_details.json"),
                prefsFile = File(tempFolder.root, "p_prefs.json"),
                apiClient = client,
                translator = OverseasTranslator(cacheFile = File(tempFolder.root, "p_trans.json")),
                pageSize = 1,
                nowMillisProvider = { nowMs.get() },
                minRefreshCooldownMillis = 0L,
                loadMoreErrorCooldownMillis = 5_000L,
            )

            val p1 = repo.refresh().getOrThrow()
            assertEquals(1, p1.size)

            val failLoad = repo.loadNextPage()
            assertTrue(failLoad.isFailure)
            assertEquals(4, server.requestCount)
            assertEquals(2, recordedDelays.size)
            // Equal jitter ranges: attempt 1 in [100..200], attempt 2 in [200..400]
            assertTrue("Delay 0 out of range: ${recordedDelays[0]}", recordedDelays[0] in 100L..200L)
            assertTrue("Delay 1 out of range: ${recordedDelays[1]}", recordedDelays[1] in 200L..400L)

            // Immediate retry within 5s error cooldown must NOT hit network
            nowMs.addAndGet(2_000L)
            val throttledLoad = repo.loadNextPage()
            assertTrue(throttledLoad.isFailure)
            assertEquals(4, server.requestCount)

            // After 5s cooldown passes, retry hits network and appends page 2
            nowMs.addAndGet(4_000L)
            val p2 = repo.loadNextPage().getOrThrow()
            assertEquals(2, p2.size)
            assertEquals(5, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun offer(
        id: String,
        title: String,
        originalTitle: String = title,
        summary: String = "来自 $id 的 LowEndTalk 海外服务器与 VPS 促销情报，点击查看完整方案详情。",
        merchant: String = "TestMerchant",
    ): OverseasDealOffer = OverseasDealOffer(
        id = id,
        title = title,
        originalTitle = originalTitle,
        summary = summary,
        merchant = merchant,
        sourceLink = "https://lowendtalk.com/discussion/$id",
    )
}
