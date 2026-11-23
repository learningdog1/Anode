package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.OverseasApiCrypto
import com.nodeseek.app.data.overseas.OverseasApiCrypto.toLowerHex
import com.nodeseek.app.data.overseas.OverseasApiException
import com.nodeseek.app.data.overseas.OverseasApiSecretVault
import com.nodeseek.app.data.overseas.OverseasIntelApiClient
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class OverseasApiCryptoAndClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun vaultReconstructsPinnedCertificateAndDerivedSubkeysMatchContract() {
        val baseUrl = OverseasApiSecretVault.resolveBaseUrl()
        assertTrue(baseUrl.startsWith("https://"))

        val spkiPin = OverseasApiSecretVault.resolveSpkiPin()
        assertTrue(spkiPin.startsWith("sha256/"))

        val cert = OverseasApiSecretVault.resolvePinnedCertificate()
        assertNotNull(cert)
        assertTrue(cert.subjectX500Principal.name.contains("NodeSeek-Intel-API"))

        val masterSecret = OverseasApiSecretVault.resolveMasterSecret()
        assertEquals(32, masterSecret.size)

        val hmacKey = OverseasApiCrypto.deriveHmacKey(masterSecret)
        val aesKey = OverseasApiCrypto.deriveAesKey(masterSecret)
        assertEquals(32, hmacKey.size)
        assertEquals(32, aesKey.size)
        assertFalse(hmacKey.contentEquals(aesKey))

        // Verify deterministic subkey derivation on synthetic test secret
        val syntheticSecret = ByteArray(32) { it.toByte() }
        val syntheticHmac1 = OverseasApiCrypto.deriveHmacKey(syntheticSecret)
        val syntheticHmac2 = OverseasApiCrypto.deriveHmacKey(syntheticSecret)
        val syntheticAes = OverseasApiCrypto.deriveAesKey(syntheticSecret)
        assertEquals(syntheticHmac1.toLowerHex(), syntheticHmac2.toLowerHex())
        assertNotEquals(syntheticHmac1.toLowerHex(), syntheticAes.toLowerHex())
    }

    @Test
    fun hmacSignatureMatchesContractTestVector() {
        val hmacKey = OverseasApiCrypto.deriveHmacKey(OverseasApiSecretVault.resolveMasterSecret())
        val sig = OverseasApiCrypto.signRequest(
            hmacKey = hmacKey,
            method = "GET",
            pathWithQuery = "/api/v1/offers?page=1&page_size=20",
            timestampSec = "1795149600",
            nonce = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
        )
        assertEquals(
            "6e3fd3a2e200b32b56f869264e64fbbedb4956169c1b6eac5cf842fee450aa6d",
            sig,
        )
    }

    @Test
    fun aesGcmDecryptsContractTestVectorAndRejectsTampering() {
        val aesKey = OverseasApiCrypto.deriveAesKey(OverseasApiSecretVault.resolveMasterSecret())
        val contractEnvelope = """
            {"v":1,"alg":"AES-256-GCM","iv":"AAECAwQFBgcICQoL","data":"5hio3JEUfPicDZG7vyW6dNq2bhzv6aHx+FqDLAn/Mou5ndJuSwyOlAz406qkSg=="}
        """.trimIndent()

        val decrypted = OverseasApiCrypto.decryptEnvelope(aesKey, contractEnvelope)
        assertEquals("""{"ok":true,"hello":"nodeseek"}""", decrypted)

        // Round-trip encrypt + decrypt
        val roundTripPlain = """{"page":1,"items":[{"id":"223518","title":"测试中文标题"}]}"""
        val encrypted = OverseasApiCrypto.encryptEnvelope(aesKey, roundTripPlain)
        assertEquals(roundTripPlain, OverseasApiCrypto.decryptEnvelope(aesKey, encrypted))

        // Tampered ciphertext must fail GCM authentication
        val tampered = """
            {"v":1,"alg":"AES-256-GCM","iv":"AAECAwQFBgcICQoL","data":"5hio3JEUfPicDZG7vyW6dNq2bhzv6aHx+FqDLAn/Mou5ndJuSwyOlAz406qkSw=="}
        """.trimIndent()
        try {
            OverseasApiCrypto.decryptEnvelope(aesKey, tampered)
            fail("Expected GeneralSecurityException on tampered GCM tag")
        } catch (_: GeneralSecurityException) {
            // Expected
        }
    }

    @Test
    fun parsesContractOffersListDetailAndRepostJsonPayloads() {
        val listJson = """
            {
              "page": 1,
              "page_size": 20,
              "total": 259,
              "has_more": true,
              "items": [
                {
                  "id": "223566",
                  "title": "LayerOne 限时 VPS 闪购促销活动",
                  "original_title": "LayerOne - Limited time offers! - ${'$'}7/yr FLASH SALE",
                  "summary": "LayerOne 推出限时闪购促销活动，涵盖多档入门与常规 VPS 方案，适合轻量测试与备用节点用户。",
                  "published_at": "2026-11-19T21:16:03+00:00",
                  "published_at_ms": 1795122963000,
                  "url": "https://lowendtalk.com/discussion/223566/layerone-flash-sale",
                  "merchant": "LayerOne"
                }
              ]
            }
        """.trimIndent()

        val pageResult = OverseasIntelApiClient.parseOffersPageJson(listJson)
        assertEquals(1, pageResult.page)
        assertEquals(20, pageResult.pageSize)
        assertEquals(259, pageResult.total)
        assertTrue(pageResult.hasMore)
        assertEquals(1, pageResult.items.size)
        val item = pageResult.items.first()
        assertEquals("223566", item.id)
        assertEquals("LayerOne 限时 VPS 闪购促销活动", item.title)
        assertEquals("LayerOne", item.merchant)
        assertEquals(1795122963000L, item.publishedAtEpochMillis)
        assertTrue(item.summary.contains("适合轻量测试与备用节点用户"))

        val detailJson = """
            {
              "id": "223566",
              "title": "LayerOne 限时闪购促销 → ${'$'}7/年起",
              "original_title": "LayerOne - Limited time offers! - ${'$'}7/yr FLASH SALE",
              "merchant": "LayerOne",
              "author": "LayerOne",
              "published_at": "2026-11-19T21:16:03+00:00",
              "published_at_ms": 1795122963000,
              "url": "https://lowendtalk.com/discussion/223566/layerone-flash-sale",
              "tg_markdown": "## LayerOne 限时闪购促销 → ${'$'}7/年起\n\n### VPS 套餐\n- 1 vCPU / 1GB RAM / 20GB NVMe | 1TB@1Gbps | 1 IPv4 → ${'$'}7/年\n\n### 购买链接\n- [下单](https://lowendtalk.com/discussion/223566/layerone-flash-sale)\n\n🏷 **优惠码**：`FLASH2026`",
              "structured": {
                "title_cn": "LayerOne 限时闪购促销 → ${'$'}7/年起",
                "sections": [
                  {
                    "header": "VPS 套餐",
                    "items": ["• 1 vCPU / 1GB RAM / 20GB NVMe | 1TB@1Gbps | 1 IPv4 → ${'$'}7/年"]
                  }
                ],
                "order_links": [
                  {"text": "下单", "url": "https://lowendtalk.com/discussion/223566/layerone-flash-sale"}
                ],
                "promo_code": "FLASH2026",
                "pros": ["年付门槛低"],
                "cons": ["闪购库存有限"],
                "suitable_for": "轻量建站与测试用户",
                "ai_comment": "低价年付适合玩具机试水，注意按需入手",
                "view_count": "71 views",
                "comment_count": "2 comments",
                "ai_model": "qwen3.8-flash"
              }
            }
        """.trimIndent()

        val detail = OverseasIntelApiClient.parseOfferDetailJson(detailJson)
        assertEquals("223566", detail.id)
        assertTrue(detail.tgMarkdown.contains("### VPS 套餐"))
        assertTrue(detail.tgMarkdown.contains("`FLASH2026`"))
        assertEquals("FLASH2026", detail.structured?.promoCode)
        assertEquals("qwen3.8-flash", detail.structured?.aiModel)

        val repostJson = """
            {
              "id": "223566",
              "title": "LayerOne - Limited time offers! - ${'$'}7/yr FLASH SALE",
              "title_cn": "LayerOne 限时闪购促销 → ${'$'}7/年起",
              "merchant": "LayerOne",
              "author": "LayerOne",
              "published_at": "2026-11-19T21:16:03+00:00",
              "published_at_ms": 1795122963000,
              "url": "https://lowendtalk.com/discussion/223566/layerone-flash-sale",
              "body_markdown": "### LayerOne Limited Time Offers\n\n![Banner](https://img.example.com/banner.png)\n\n- **1 vCPU** / 1GB DDR4 / 20GB NVMe — **${'$'}7/yr** — [Order Here](https://order.example.com/1)",
              "images": [{"alt": "Banner", "url": "https://img.example.com/banner.png"}],
              "links": [{"text": "Order Here", "url": "https://order.example.com/1"}],
              "suggested_category_slug": "info"
            }
        """.trimIndent()

        val repost = OverseasIntelApiClient.parseOfferRepostJson(repostJson)
        assertEquals("223566", repost.id)
        assertEquals("info", repost.suggestedCategorySlug)
        assertEquals(1, repost.images.size)
        assertEquals(1, repost.links.size)
        assertTrue(repost.bodyMarkdown.contains("![Banner](https://img.example.com/banner.png)"))
    }

    @Test
    fun apiClientRetries503And429WithFreshNonceAndHealsClockSkew() = runTest {
        val testSecret = ByteArray(32) { idx -> (idx + 7).toByte() }
        val aesKey = OverseasApiCrypto.deriveAesKey(testSecret)
        val hmacKey = OverseasApiCrypto.deriveHmacKey(testSecret)

        val server = MockWebServer()
        val encryptedSuccess = OverseasApiCrypto.encryptEnvelope(
            aesKey = aesKey,
            plaintextUtf8 = """{"page":1,"page_size":20,"total":1,"has_more":false,"items":[{"id":"101","title":"Noez 法兰克福 VPS 促销","summary":"法兰克福常规入门套餐","published_at_ms":1795122963000,"url":"https://lowendtalk.com/discussion/101"}]}""",
        )

        // 1st attempt -> 503, 2nd attempt -> 429 with Retry-After, 3rd attempt -> 200 encrypted
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"UNAVAILABLE","message":"busy"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1").setBody("""{"error":{"code":"RATE_LIMIT","message":"slow down"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(encryptedSuccess))
        server.start()

        val recordedDelays = mutableListOf<Long>()
        try {
            val client = OverseasIntelApiClient(
                baseUrlProvider = { server.url("/").toString() },
                masterSecretProvider = { testSecret.copyOf() },
                httpClient = OkHttpClient(),
                maxAttempts = 3,
                baseRetryDelayMillis = 100L,
                nowSecondsProvider = { 1795149600L },
                sleeper = { ms -> recordedDelays += ms },
            )

            val page = client.fetchOffersPage(page = 1, pageSize = 20)
            assertEquals(1, page.items.size)
            assertEquals("101", page.items.first().id)
            assertEquals(3, server.requestCount)
            assertEquals(2, recordedDelays.size)
            assertEquals(1000L, recordedDelays[1]) // Respected Retry-After: 1

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            val req3 = server.takeRequest()
            val nonce1 = req1.getHeader("X-Nonce").orEmpty()
            val nonce2 = req2.getHeader("X-Nonce").orEmpty()
            val nonce3 = req3.getHeader("X-Nonce").orEmpty()
            assertNotEquals(nonce1, nonce2)
            assertNotEquals(nonce2, nonce3)

            // Verify signature sent on 3rd request
            val expectedSig3 = OverseasApiCrypto.signRequest(
                hmacKey = hmacKey,
                method = "GET",
                pathWithQuery = "/api/v1/offers?page=1&page_size=20",
                timestampSec = "1795149600",
                nonce = nonce3,
            )
            assertEquals(expectedSig3, req3.getHeader("X-Signature"))

            // Verify clock skew recovery on 401 + Date header
            val serverEpoch = 1795155000L
            val dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.ofEpochSecond(serverEpoch).atZone(ZoneOffset.UTC))
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .addHeader("Date", dateHeader)
                    .setBody("""{"error":{"code":"TS_SKEW","message":"timestamp skew"}}"""),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(encryptedSuccess))

            val afterSkew = client.fetchOffersPage(page = 1, pageSize = 20)
            assertEquals(1, afterSkew.items.size)
            server.takeRequest() // 401 request
            val healedReq = server.takeRequest()
            assertEquals(serverEpoch.toString(), healedReq.getHeader("X-Timestamp"))

            // Verify non-retryable 404 fails immediately without retry
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"帖子不存在"}}"""))
            val before404Count = server.requestCount
            try {
                client.fetchOfferDetail("999999")
                fail("Expected OverseasApiException for 404")
            } catch (e: OverseasApiException) {
                assertEquals(404, e.statusCode)
                assertEquals("帖子不存在", e.message)
            }
            assertEquals(before404Count + 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun repositoryPersistsDiskCacheAndServesOfflineFallbackWhenNetworkFails() = runTest {
        val testSecret = ByteArray(32) { idx -> (idx + 21).toByte() }
        val aesKey = OverseasApiCrypto.deriveAesKey(testSecret)

        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                OverseasApiCrypto.encryptEnvelope(
                    aesKey = aesKey,
                    plaintextUtf8 = """{"page":1,"page_size":20,"total":1,"has_more":false,"items":[{"id":"223518","title":"noez.de 法兰克福 VPS 特惠","summary":"德国法兰克福节点多档配置促销","merchant":"noez.de","published_at_ms":1795122963000,"url":"https://lowendtalk.com/discussion/223518"}]}""",
                ),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                OverseasApiCrypto.encryptEnvelope(
                    aesKey = aesKey,
                    plaintextUtf8 = """{"id":"223518","title":"noez.de 法兰克福详情","merchant":"noez.de","url":"https://lowendtalk.com/discussion/223518","tg_markdown":"## noez.de 法兰克福促销\n\n- 优惠码 `iplease99`"}""",
                ),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                OverseasApiCrypto.encryptEnvelope(
                    aesKey = aesKey,
                    plaintextUtf8 = """{"id":"223518","title":"noez.de Frankfurt KVM Deals","title_cn":"noez.de 法兰克福 VPS 特惠","merchant":"noez.de","url":"https://lowendtalk.com/discussion/223518","body_markdown":"### Frankfurt KVM\n\nUse `iplease99`"}""",
                ),
            ),
        )
        server.start()

        val listCache = File(tempFolder.root, "offers.json")
        val detailCache = File(tempFolder.root, "details.json")
        val prefsFile = File(tempFolder.root, "prefs.json")

        val baseUrl = server.url("/").toString()
        try {
            val onlineClient = OverseasIntelApiClient(
                baseUrlProvider = { baseUrl },
                masterSecretProvider = { testSecret.copyOf() },
                httpClient = OkHttpClient(),
                sleeper = {},
            )
            val repo = OverseasIntelRepositoryImpl(
                listCacheFile = listCache,
                detailCacheFile = detailCache,
                prefsFile = prefsFile,
                apiClient = onlineClient,
            )

            val offers = repo.refresh().getOrThrow()
            assertEquals(1, offers.size)
            assertFalse(repo.snapshot.value.syncStatus.loadedFromCache)

            val detail = repo.getOfferDetail("223518").getOrThrow()
            assertTrue(detail.tgMarkdown.contains("iplease99"))

            val repost = repo.getRepostContent("223518").getOrThrow()
            assertTrue(repost.bodyMarkdown.contains("Frankfurt KVM"))
        } finally {
            server.shutdown()
        }

        // Now verify offline repository instance with server completely shut down!
        val offlineClient = OverseasIntelApiClient(
            baseUrlProvider = { "http://127.0.0.1:1" },
            masterSecretProvider = { testSecret.copyOf() },
            httpClient = OkHttpClient(),
            maxAttempts = 2,
            sleeper = {},
        )
        val offlineRepo = OverseasIntelRepositoryImpl(
            listCacheFile = listCache,
            detailCacheFile = detailCache,
            prefsFile = prefsFile,
            apiClient = offlineClient,
        )

        val coldOffers = offlineRepo.loadFromCache()
        assertEquals(1, coldOffers.size)
        assertTrue(offlineRepo.snapshot.value.syncStatus.loadedFromCache)

        // Refresh fails while offline, but cached list remains intact in snapshot!
        val offlineRefresh = offlineRepo.refresh()
        assertTrue(offlineRefresh.isFailure)
        assertEquals(1, offlineRepo.snapshot.value.offers.size)
        assertTrue(offlineRepo.snapshot.value.syncStatus.loadedFromCache)
        assertTrue(offlineRepo.snapshot.value.syncStatus.errorMessage.orEmpty().contains("离线缓存"))

        // Cached detail and repost also succeed offline even with forceRefresh = true!
        val offlineDetail = offlineRepo.getOfferDetail("223518", forceRefresh = true).getOrThrow()
        assertTrue(offlineDetail.tgMarkdown.contains("iplease99"))
        val offlineRepost = offlineRepo.getRepostContent("223518", forceRefresh = true).getOrThrow()
        assertTrue(offlineRepost.bodyMarkdown.contains("Frankfurt KVM"))
    }
}
