package com.nodeseek.app.overseas

import android.content.Context
import android.content.ContextWrapper
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.nodeseek.app.data.overseas.OverseasApiCrypto
import com.nodeseek.app.data.overseas.OverseasApiSecretVault
import com.nodeseek.app.data.overseas.OverseasIntelApiClient
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.OverseasPromoMonitorControllerImpl
import com.nodeseek.app.data.overseas.OverseasPromoMonitorHost
import com.nodeseek.app.data.overseas.OverseasPromoPreferencesStore
import com.nodeseek.app.data.overseas.OverseasPromoWorker
import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.data.overseas.TranslationTier
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasIntelSnapshot
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.OverseasOfferFilterConfig
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorController
import com.nodeseek.app.domain.overseas.OverseasPromoPollOutcome
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.VpsGlossaryTranslator
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.ui.overseas.OverseasIntelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class OverseasAuditFixRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun secretVaultDisablesRedirectsCookiesAndCryptoSupportsOptionalAad() {
        val backupPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val client = OverseasApiSecretVault.buildPinnedHttpClient(
            backupSpkiPins = listOf(backupPin),
        )
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(client.certificatePinner.pins.any { it.toString().contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") })

        val secret = ByteArray(32) { (it + 7).toByte() }
        val aesKey = OverseasApiCrypto.deriveAesKey(secret)
        val aad = "GET|/api/v1/offers".toByteArray(Charsets.UTF_8)
        val envelope = OverseasApiCrypto.encryptEnvelope(
            aesKey = aesKey,
            plaintextUtf8 = """{"ok":true}""",
            aad = aad,
        )
        assertEquals(
            """{"ok":true}""",
            OverseasApiCrypto.decryptEnvelope(aesKey = aesKey, rawEnvelopeJson = envelope, aad = aad),
        )
        try {
            OverseasApiCrypto.decryptEnvelope(
                aesKey = aesKey,
                rawEnvelopeJson = envelope,
                aad = "GET|/api/v1/other".toByteArray(Charsets.UTF_8),
            )
            fail("Expected GeneralSecurityException when AAD mismatches")
        } catch (_: GeneralSecurityException) {
            // Expected
        }
    }

    @Test
    fun apiClientDoesNotHangWhenBodyReadThrowsMidStream() = runBlocking {
        val secret = ByteArray(32) { (it + 1).toByte() }
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("x".repeat(8192))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        server.start()

        try {
            val client = OverseasIntelApiClient(
                baseUrlProvider = { server.url("/").toString() },
                masterSecretProvider = { secret.copyOf() },
                httpClient = OkHttpClient(),
                maxAttempts = 1,
                sleeper = {},
            )
            val thrown = withTimeout(5_000L) {
                runCatching { client.fetchOffersPage(page = 1, pageSize = 20) }.exceptionOrNull()
            }
            assertTrue("Expected IOException on truncated body, got $thrown", thrown is IOException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun translatorUnmasksDollarAndBackslashVerbatimSkipsOfflineCacheAndSplitsOversizedLines() = runTest {
        val server = MockWebServer()
        // 1) Offline failure on first call -> MUST NOT be written to disk cache
        server.enqueue(MockResponse().setResponseCode(503).setBody("Unavailable"))
        // 2) Second call recovers online -> returns translated text with KPH0KPH (Verbatim) & bare KPH1KPH (LinkUrl else branch)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[[["测试优惠码 KPH0KPH 与链接 KPH1KPH", "Test promo KPH0KPH and KPH1KPH", null, null, 10]], null, "en"]""",
            ),
        )
        // 3 & 4) Oversized single line (> 2800 chars) split into 2 GTX calls
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[[["第一段超长译文", "part1", null, null, 10]], null, "en"]""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[[["第二段超长译文", "part2", null, null, 10]], null, "en"]""",
            ),
        )
        server.start()

        val cacheFile = File(tempFolder.root, "translator_regression.json")
        try {
            val translator = OverseasTranslator(
                cacheFile = cacheFile,
                gtxBaseUrl = server.url("/translate_a/single").toString(),
            )
            val input = """Test promo `SAVE$10\OFF` and [View](https://example.com/buy?price=$1&path=a\b)"""

            val offlineRes = translator.translateText(input)
            assertEquals(TranslationTier.OFFLINE_GLOSSARY, offlineRes.tier)
            assertFalse("Offline fallback must not persist cache file", cacheFile.exists())

            val onlineRes = translator.translateText(input)
            assertEquals(TranslationTier.GOOGLE_GTX, onlineRes.tier)
            assertFalse(onlineRes.fromCache)
            assertTrue("Expected verbatim inline code without escaped $, got: ${onlineRes.translatedText}", onlineRes.translatedText.contains("""`SAVE$10\OFF`"""))
            assertTrue("Expected verbatim URL without escaped $, got: ${onlineRes.translatedText}", onlineRes.translatedText.contains("""https://example.com/buy?price=$1&path=a\b"""))
            assertFalse(onlineRes.translatedText.contains("""\$"""))

            // Oversized > 2800 char single line must split into 2 requests instead of truncating silently
            val longLine = List(250) { "RackNerd-Promo" }.joinToString(" ")
            assertTrue(longLine.length > OverseasTranslator.MAX_GTX_QUERY_CHARS)
            assertTrue(longLine.length < OverseasTranslator.MAX_GTX_QUERY_CHARS * 2)
            val longRes = translator.translateText(longLine)
            assertEquals(TranslationTier.GOOGLE_GTX, longRes.tier)
            assertTrue(longRes.translatedText.contains("第一段超长译文"))
            assertTrue(longRes.translatedText.contains("第二段超长译文"))
            assertEquals(4, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun glossaryTranslatorDoesNotCorruptLowercaseEnglishPronounUs() {
        val out = VpsGlossaryTranslator.translateTitleOffline("Contact us for US and EU KVM VPS offers")
        assertTrue("Lowercase 'us' must not become '美国': $out", out.contains("Contact us"))
        assertTrue("Uppercase 'US' should translate to '美国': $out", out.contains("美国"))
        assertTrue("Uppercase 'EU' should translate to '欧洲': $out", out.contains("欧洲"))
    }

    @Test
    fun repositorySearchQueryDoesNotPolluteDefaultRawOffersOrEtag() = runTest {
        val secret = ByteArray(32) { (it + 5).toByte() }
        val aesKey = OverseasApiCrypto.deriveAesKey(secret)
        val server = MockWebServer()

        val defaultPage = """
            {"page":1,"page_size":20,"total":2,"has_more":false,"items":[
              {"id":"1","title":"RackNerd LAX KVM VPS $10/yr","summary":"2C/2G","merchant":"RackNerd","url":"https://lowendtalk.com/discussion/1"},
              {"id":"2","title":"DMIT Tokyo KVM VPS $39/yr","summary":"2C/2G","merchant":"DMIT","url":"https://lowendtalk.com/discussion/2"}
            ]}
        """.trimIndent()
        val searchPage = """
            {"page":1,"page_size":20,"total":1,"has_more":false,"items":[
              {"id":"99","title":"CloudCone Search Hit KVM VPS $12/yr","summary":"1C/1G","merchant":"CloudCone","url":"https://lowendtalk.com/discussion/99"}
            ]}
        """.trimIndent()

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"default-etag\"")
                .setBody(OverseasApiCrypto.encryptEnvelope(aesKey, defaultPage)),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"search-etag\"")
                .setBody(OverseasApiCrypto.encryptEnvelope(aesKey, searchPage)),
        )
        server.enqueue(
            MockResponse().setResponseCode(304)
                .setHeader("ETag", "\"default-etag\""),
        )
        server.start()

        val nowMs = AtomicLong(1_763_700_000_000L)
        try {
            val client = OverseasIntelApiClient(
                baseUrlProvider = { server.url("/").toString() },
                masterSecretProvider = { secret.copyOf() },
                httpClient = OkHttpClient(),
                nowSecondsProvider = { nowMs.get() / 1000L },
                sleeper = {},
            )
            val repo = OverseasIntelRepositoryImpl(
                listCacheFile = File(tempFolder.root, "offers_q.json"),
                detailCacheFile = File(tempFolder.root, "details_q.json"),
                prefsFile = File(tempFolder.root, "prefs_q.json"),
                apiClient = client,
                nowMillisProvider = { nowMs.get() },
                minRefreshCooldownMillis = 0L,
            )

            val initial = repo.refresh().getOrThrow()
            assertEquals(listOf("1", "2"), initial.map { it.id })
            server.takeRequest()

            val searchResults = repo.refresh(query = "CloudCone").getOrThrow()
            assertEquals(listOf("99"), searchResults.map { it.id })
            server.takeRequest()

            // Default snapshot must remain unpolluted!
            assertEquals(listOf("1", "2"), repo.snapshot.value.offers.map { it.id })

            // Subsequent default refresh still sends original default ETag and preserves default items on 304
            val after304 = repo.refresh().getOrThrow()
            val req3 = server.takeRequest()
            assertEquals("\"default-etag\"", req3.getHeader("If-None-Match"))
            assertEquals(listOf("1", "2"), after304.map { it.id })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun promoMonitorReEnableWithStaleDiskCacheReBaselinesSilentlyWithoutFlooding() = runTest {
        var nowMs = 1_763_700_000_000L
        val storeFile = File(tempFolder.root, "promo_reenable.json")
        val prefs = OverseasPromoPreferencesStore(
            storeFile = storeFile,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val initialOffer = OverseasDealOffer(
            id = "old-1",
            title = "RackNerd KVM VPS $10/yr",
            summary = "2C/2G NVMe",
            merchant = "RackNerd",
            sourceLink = "https://lowendtalk.com/discussion/1",
        )
        val missedOffers = (1..5).map { idx ->
            OverseasDealOffer(
                id = "missed-$idx",
                title = "Missed Deal #$idx KVM VPS $12/yr",
                summary = "2C/2G NVMe",
                merchant = "Host$idx",
                sourceLink = "https://lowendtalk.com/discussion/10$idx",
            )
        }

        var refreshCalls = 0
        val fakeRepo = object : OverseasIntelRepository {
            var serverOffers: List<OverseasDealOffer> = listOf(initialOffer)
            private val _snapshot = MutableStateFlow(OverseasIntelSnapshot(offers = listOf(initialOffer)))
            override val snapshot: StateFlow<OverseasIntelSnapshot> = _snapshot.asStateFlow()

            fun setStaleDiskCache(offers: List<OverseasDealOffer>) {
                _snapshot.value = OverseasIntelSnapshot(
                    offers = offers,
                    syncStatus = OverseasSyncStatus(
                        lastSyncedEpochMillis = nowMs - 86_400_000L,
                        loadedFromCache = true,
                    ),
                )
            }

            override suspend fun loadFromCache(): List<OverseasDealOffer> = _snapshot.value.offers
            override suspend fun refresh(query: String): Result<List<OverseasDealOffer>> {
                refreshCalls++
                _snapshot.value = OverseasIntelSnapshot(
                    offers = serverOffers,
                    syncStatus = OverseasSyncStatus(lastSyncedEpochMillis = nowMs, loadedFromCache = false),
                )
                return Result.success(serverOffers)
            }
            override suspend fun loadNextPage(query: String) = Result.success(_snapshot.value.offers)
            override suspend fun getOfferDetail(offerId: String, forceRefresh: Boolean): Result<OverseasOfferDetail> =
                Result.failure(UnsupportedOperationException())
            override suspend fun getRepostContent(offerId: String, forceRefresh: Boolean): Result<OverseasRepostContent> =
                Result.failure(UnsupportedOperationException())
        }

        val notified = mutableListOf<OverseasDealOffer>()
        val dispatcher = object : PulseNotificationDispatcher(object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }) {
            override fun ensureChannels() = Unit
            override fun hasPostNotificationsPermission(): Boolean = true
            override fun postOverseasPromoNotifications(offers: List<OverseasDealOffer>) {
                notified += offers
            }
        }

        val controller = OverseasPromoMonitorControllerImpl(
            repository = fakeRepo,
            preferencesStore = prefs,
            notificationDispatcher = dispatcher,
            scope = this,
            clockMillis = { nowMs },
            startupDelayMillis = 0L,
        )

        // Establish initial baseline
        controller.checkNow(manualTrigger = true)
        assertTrue(notified.isEmpty())

        // Disable notifications; 5 offers arrive while disabled; only stale cache exists in snapshot
        controller.setNotificationsEnabled(false)
        fakeRepo.serverOffers = missedOffers + initialOffer
        fakeRepo.setStaleDiskCache(listOf(initialOffer))
        nowMs += 86_400_000L

        // Re-enable -> stale disk cache must NOT mark baseline initialized -> re-baseline silently with 0 flood!
        controller.setNotificationsEnabled(true)
        advanceUntilIdle()
        assertTrue("Expected zero historical flood on re-enable, got ${notified.map { it.id }}", notified.isEmpty())
        assertTrue(
            "Expected baseline re-initialized after re-enable poll (refreshCalls=$refreshCalls, enabled=${prefs.getSettings().notificationsEnabled}, status=${controller.runtimeStatus.value})",
            prefs.isBaselineInitialized(),
        )

        // Truly new offer after re-enable notifies immediately
        val freshOffer = OverseasDealOffer(
            id = "fresh-after",
            title = "Brand New Flash KVM VPS $8/yr",
            summary = "2C/2G NVMe",
            merchant = "GreenCloud",
            sourceLink = "https://lowendtalk.com/discussion/999",
        )
        fakeRepo.serverOffers = listOf(freshOffer) + missedOffers + initialOffer
        nowMs += 120_000L
        val nextOutcome = controller.checkNow(manualTrigger = false) as OverseasPromoPollOutcome.Completed
        assertEquals(1, nextOutcome.newOffersNotified)
        assertEquals(listOf("fresh-after"), notified.map { it.id })
    }

    @Test
    fun promoWorkerRetriesOnUninitializedPropertyAccessException() = runTest {
        val coldHostContext = object : ContextWrapper(null), OverseasPromoMonitorHost {
            override fun getApplicationContext(): Context = this
            override val overseasPromoMonitorController: OverseasPromoMonitorController
                get() = throw UninitializedPropertyAccessException("lateinit property container has not been initialized")
        }
        val worker = TestListenableWorkerBuilder<OverseasPromoWorker>(coldHostContext).build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun viewModelUpdatesFilterConfigAndRefreshesRelativeTimeBucket() {
        val mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        try {
            runTest(mainDispatcher) {
                var nowMs = 1_763_700_000_000L
                val offer = OverseasDealOffer(
                    id = "10",
                    title = "RackNerd KVM VPS $10/yr",
                    summary = "2C/2G",
                    merchant = "RackNerd",
                    sourceLink = "https://lowendtalk.com/discussion/10",
                    publishedAtEpochMillis = nowMs - 10_000L,
                )
                var updatedConfig: OverseasOfferFilterConfig? = null
                val fakeRepo = object : OverseasIntelRepository {
                    private val _snap = MutableStateFlow(OverseasIntelSnapshot(offers = listOf(offer), filteredOutCount = 2))
                    override val snapshot: StateFlow<OverseasIntelSnapshot> = _snap.asStateFlow()
                    override suspend fun loadFromCache() = listOf(offer)
                    override suspend fun refresh(query: String) = Result.success(listOf(offer))
                    override suspend fun loadNextPage(query: String) = Result.success(listOf(offer))
                    override suspend fun getOfferDetail(offerId: String, forceRefresh: Boolean): Result<OverseasOfferDetail> =
                        Result.failure(UnsupportedOperationException())
                    override suspend fun getRepostContent(offerId: String, forceRefresh: Boolean): Result<OverseasRepostContent> =
                        Result.failure(UnsupportedOperationException())
                    override suspend fun updateFilterConfig(config: OverseasOfferFilterConfig) {
                        updatedConfig = config
                        _snap.update { it.copy(filterConfig = config) }
                    }
                }

                val vm = OverseasIntelViewModel(
                    repository = fakeRepo,
                    autoLoadOnInit = false,
                    clockMillis = { nowMs },
                )
                val job = async { vm.uiState.collect {} }
                advanceUntilIdle()

                assertEquals("刚刚", vm.uiState.value.offerCards.single().relativeTimeText)
                assertEquals(2, vm.uiState.value.filteredOutCount)

                nowMs += 5L * 60_000L
                vm.refreshRelativeTime()
                advanceUntilIdle()
                assertEquals("5 分钟前", vm.uiState.value.offerCards.single().relativeTimeText)

                val customConfig = OverseasOfferFilterConfig(enabled = false)
                vm.updateFilterConfig(customConfig)
                advanceUntilIdle()
                assertEquals(customConfig, updatedConfig)
                assertFalse(vm.uiState.value.filterConfig.enabled)

                job.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
