package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDetailStructured
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasIntelSnapshot
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.ui.overseas.OVERSEAS_MONITOR_TG_CHANNEL_URL
import com.nodeseek.app.ui.overseas.OverseasDetailContentSanitizer
import com.nodeseek.app.ui.overseas.OverseasIntelDetailViewModel
import com.nodeseek.app.ui.overseas.OverseasIntelUiState
import com.nodeseek.app.ui.overseas.OverseasIntelViewModel
import com.nodeseek.app.ui.overseas.OverseasOfferCardUiModel
import com.nodeseek.app.ui.overseas.OverseasOfferDetailUiState
import com.nodeseek.app.ui.overseas.OverseasOfferVisualParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class OverseasUiAndDetailViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun visualParserPreservesRawTitleWithoutTranslationAndExposesZeroPriceOrSpecTags() {
        val englishOffer = OverseasDealOffer(
            id = "223590",
            title = "[HostPapa] Black Friday Web Hosting – · cPanel | FREE Domain Y1 | CA/US/EU |",
            originalTitle = "[HostPapa] Black Friday Web Hosting – \$1.25/mo | 25 GB NVMe | cPanel | FREE Domain Y1 | CA/US/EU |",
            summary = "来自 HostPapa 的 LowEndTalk 海外服务器与 VPS 促销情报，点击查看完整方案详情。",
            merchant = "BLACK FRIDAY",
            sourceLink = "https://lowendtalk.com/discussion/223590/hostpapa-bf",
            publishedAtEpochMillis = 1_700_000_000_000L,
        )

        val card = OverseasOfferVisualParser.parseCardModel(
            offer = englishOffer,
            nowEpochMillis = 1_700_001_500_000L,
        )

        // Merchant extracted cleanly, boilerplate summary stripped, English title kept WITHOUT Chinese translation!
        assertEquals("HostPapa", card.merchantLabel)
        assertEquals("H", card.merchantInitial)
        assertEquals("25 分钟前", card.relativeTimeText)
        assertEquals(
            "[HostPapa] Black Friday Web Hosting · cPanel | FREE Domain Y1 | CA/US/EU",
            card.headlineTitle,
        )
        assertFalse(OverseasOfferVisualParser.containsCjk(card.headlineTitle))
        assertNull(card.cleanSummary)

        // Confirm OverseasOfferCardUiModel has NO priceBadge, specChips, regionTags, promoTags, or secondaryTitle!
        val cardFieldNames = OverseasOfferCardUiModel::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(cardFieldNames.contains("priceBadge"))
        assertFalse(cardFieldNames.contains("specChips"))
        assertFalse(cardFieldNames.contains("regionTags"))
        assertFalse(cardFieldNames.contains("promoTags"))
        assertFalse(cardFieldNames.contains("secondaryTitle"))
    }

    @Test
    fun listViewModelDoesNotTranslateListTitlesAndExposesNoTranslationUiState() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val englishOffer = OverseasDealOffer(
                id = "102",
                title = "Special Offers: Storage VPS in Frankfurt - Lifetime Recurring",
                originalTitle = "Special Offers: Storage VPS in Frankfurt - Lifetime Recurring",
                summary = "OpenVZ & KVM plans in Germany.",
                merchant = "Host",
                sourceLink = "https://lowendtalk.com/discussion/102",
            )
            val repo = TrackingDetailRepository(
                initialOffers = listOf(englishOffer),
                initialConfig = OverseasTranslationConfig(
                    engineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY,
                    autoTranslateOnRefresh = true,
                    displayMode = OverseasDisplayMode.CHINESE,
                ),
            )
            val translator = OverseasTranslator(
                cacheFile = File(tempFolder.root, "list_trans.json"),
                gtxBaseUrl = server.url("/translate_a/single").toString(),
                ioDispatcher = testDispatcher,
            )
            val vm = OverseasIntelViewModel(
                repository = repo,
                autoLoadOnInit = false,
                translator = translator,
            )

            val collectJob = async { vm.uiState.collect {} }
            advanceUntilIdle()

            // Zero online translation calls during list display, and English title stays completely untouched!
            assertEquals(0, server.requestCount)
            assertEquals(1, vm.uiState.value.offerCards.size)
            assertEquals(
                "Special Offers: Storage VPS in Frankfurt - Lifetime Recurring",
                vm.uiState.value.offerCards.first().headlineTitle,
            )

            // OverseasIntelUiState must not expose any translation UI fields
            val stateFields = OverseasIntelUiState::class.java.declaredFields.map { it.name }.toSet()
            assertFalse(stateFields.any { it.contains("translat", ignoreCase = true) || it.contains("displayMode", ignoreCase = true) })

            // Verify TG monitor channel URL constant on OverseasIntelScreen
            assertEquals("https://t.me/colmidad_lowendtalk_offer", OVERSEAS_MONITOR_TG_CHANNEL_URL)

            collectJob.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun detailViewModelStripsAllTelegramReferencesAndAutoTranslatesRepost() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[[["RackNerd 洛杉矶 KVM 年付特惠", "RackNerd LA KVM Annual Promo", null, null, 10]], null, "en"]""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[[["洛杉矶 2 核 4G 年付套餐，立即抢购", "Los Angeles 2vCPU 4GB Annual Plan, grab now", null, null, 10]], null, "en"]""",
            ),
        )
        server.start()

        try {
            val dirtyDetail = OverseasOfferDetail(
                id = "223566",
                title = "Telegram 促销通知：RackNerd 限时特惠",
                originalTitle = "RackNerd LA KVM Annual Promo (via Telegram)",
                merchant = "RackNerd Telegram 频道",
                author = "dustinc",
                sourceLink = "https://lowendtalk.com/discussion/223566/racknerd",
                tgMarkdown = """
                    📢 来自 Telegram 促销推送频道
                    ## RackNerd 洛杉矶特惠
                    - 2 vCPU / 4GB RAM → ${'$'}18.99/年
                    - 欢迎订阅 Telegram 频道获取更多资讯
                """.trimIndent(),
                structured = OverseasDetailStructured(
                    promoCode = "无",
                ),
            )
            val englishRepost = OverseasRepostContent(
                id = "223566",
                title = "RackNerd LA KVM Annual Promo",
                titleCn = "",
                merchant = "RackNerd",
                author = "dustinc",
                sourceLink = "https://lowendtalk.com/discussion/223566/racknerd",
                bodyMarkdown = "Los Angeles 2vCPU 4GB Annual Plan, grab now",
            )
            val fakeRepo = TrackingDetailRepository(
                initialOffers = listOf(
                    OverseasDealOffer(
                        id = "223566",
                        title = "RackNerd LA KVM Annual Promo",
                        originalTitle = "RackNerd LA KVM Annual Promo",
                        summary = "",
                        merchant = "RackNerd",
                        sourceLink = "https://lowendtalk.com/discussion/223566/racknerd",
                    ),
                ),
                customDetail = dirtyDetail,
                customRepost = englishRepost,
            )
            val translator = OverseasTranslator(
                cacheFile = File(tempFolder.root, "detail_repost_trans.json"),
                gtxBaseUrl = server.url("/translate_a/single").toString(),
                ioDispatcher = testDispatcher,
            )

            val vm = OverseasIntelDetailViewModel(
                offerId = "223566",
                repository = fakeRepo,
                translator = translator,
            )
            advanceUntilIdle()

            val loadedDetail = vm.uiState.value.detail
            assertNotNull(loadedDetail)
            assertEquals(0, fakeRepo.translateDetailCalls)

            // Every single detail field must be 100% free of "telegram" (case-insensitive)!
            assertFalse(loadedDetail!!.title.contains("telegram", ignoreCase = true))
            assertFalse(loadedDetail.originalTitle.contains("telegram", ignoreCase = true))
            assertFalse(loadedDetail.merchant.contains("telegram", ignoreCase = true))
            assertFalse(loadedDetail.tgMarkdown.contains("telegram", ignoreCase = true))
            assertTrue(loadedDetail.tgMarkdown.contains("## RackNerd 洛杉矶特惠"))

            // Placeholder promo_code = "无" must be ignored
            assertNull(OverseasDetailContentSanitizer.extractValidPromoCode(loadedDetail))
            assertEquals(
                "RN2026",
                OverseasDetailContentSanitizer.extractValidPromoCode(
                    loadedDetail.copy(structured = OverseasDetailStructured(promoCode = "RN2026")),
                ),
            )

            // Confirm OverseasIntelDetailScreen.kt source file itself contains zero literal "telegram"
            val detailSourceCandidates = listOf(
                File("src/main/java/com/nodeseek/app/ui/overseas/OverseasIntelDetailScreen.kt"),
                File("app/src/main/java/com/nodeseek/app/ui/overseas/OverseasIntelDetailScreen.kt"),
            )
            val detailSourceFile = detailSourceCandidates.firstOrNull { it.exists() }
            assertNotNull("Expected OverseasIntelDetailScreen.kt to exist", detailSourceFile)
            val detailSourceText = detailSourceFile!!.readText(Charsets.UTF_8)
            assertFalse(
                "OverseasIntelDetailScreen.kt must not contain any 'telegram' text",
                detailSourceText.contains("telegram", ignoreCase = true),
            )

            // Confirm OverseasOfferDetailUiState exposes no translation fields
            val fieldNames = OverseasOfferDetailUiState::class.java.declaredFields.map { it.name }.toSet()
            assertFalse(fieldNames.any { it.contains("translat", ignoreCase = true) || it.contains("displayMode", ignoreCase = true) })

            // Trigger one-click repost from DetailViewModel -> auto-translates English OP title & body!
            val repostDeferred = async { vm.repostDraftEvents.first() }
            vm.prepareRepost()
            advanceUntilIdle()
            val draft = repostDeferred.await()
            assertEquals("[海外情报] RackNerd 洛杉矶 KVM 年付特惠", draft.title)
            assertTrue(draft.markdownBody.contains("洛杉矶 2 核 4G 年付套餐，立即抢购"))
            assertFalse(draft.markdownBody.contains("telegram", ignoreCase = true))
            assertFalse(vm.uiState.value.isReposting)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun detailViewModelSurfacesSanitizedFailureAndRecoversOnRetry() = runTest {
        val fakeRepo = TrackingDetailRepository(
            failFirstDetailFetch = true,
            failureMessage = "Telegram 服务连接超时",
        )
        val vm = OverseasIntelDetailViewModel(offerId = "223566", repository = fakeRepo)
        advanceUntilIdle()

        assertNull(vm.uiState.value.detail)
        val errMsg = vm.uiState.value.errorMessage.orEmpty()
        assertTrue(errMsg.isNotBlank())
        assertFalse(errMsg.contains("telegram", ignoreCase = true))

        vm.loadDetail(forceRefresh = true)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.detail)
        assertNull(vm.uiState.value.errorMessage)
    }

    private class TrackingDetailRepository(
        initialOffers: List<OverseasDealOffer> = emptyList(),
        initialConfig: OverseasTranslationConfig = OverseasTranslationConfig(),
        private var failFirstDetailFetch: Boolean = false,
        private val failureMessage: String = "网络超时",
        private val customDetail: OverseasOfferDetail? = null,
        private val customRepost: OverseasRepostContent? = null,
    ) : OverseasIntelRepository {

        private val delegate = PreviewOverseasIntelRepository()
        private val _snapshot = MutableStateFlow(
            OverseasIntelSnapshot(
                offers = initialOffers.ifEmpty { delegate.snapshot.value.offers },
                translationConfig = initialConfig,
            ),
        )
        override val snapshot: StateFlow<OverseasIntelSnapshot> = _snapshot.asStateFlow()

        var getDetailCalls = 0
        var translateDetailCalls = 0

        override suspend fun loadFromCache(): List<OverseasDealOffer> = _snapshot.value.offers

        override suspend fun refresh(query: String): Result<List<OverseasDealOffer>> =
            Result.success(_snapshot.value.offers)

        override suspend fun loadNextPage(query: String): Result<List<OverseasDealOffer>> =
            Result.success(_snapshot.value.offers)

        override suspend fun getOfferDetail(offerId: String, forceRefresh: Boolean): Result<OverseasOfferDetail> {
            getDetailCalls++
            if (failFirstDetailFetch) {
                failFirstDetailFetch = false
                return Result.failure(IllegalStateException(failureMessage))
            }
            return customDetail?.let { Result.success(it) } ?: delegate.getOfferDetail(offerId, forceRefresh)
        }

        override suspend fun translateOfferDetail(offerId: String, forceRefresh: Boolean): Result<OverseasOfferDetail> {
            translateDetailCalls++
            return delegate.translateOfferDetail(offerId, forceRefresh)
        }

        override suspend fun getRepostContent(offerId: String, forceRefresh: Boolean): Result<OverseasRepostContent> =
            customRepost?.let { Result.success(it) } ?: delegate.getRepostContent(offerId, forceRefresh)

        override suspend fun updateTranslationConfig(config: OverseasTranslationConfig) {
            _snapshot.update { it.copy(translationConfig = config) }
        }

        override suspend fun updateDisplayMode(mode: OverseasDisplayMode) {
            _snapshot.update { it.copy(translationConfig = it.translationConfig.copy(displayMode = mode)) }
        }

        override suspend fun testTranslationConfig(config: OverseasTranslationConfig): Result<String> =
            Result.success("ok")

        override suspend fun clearTranslationCache() = Unit
    }
}
