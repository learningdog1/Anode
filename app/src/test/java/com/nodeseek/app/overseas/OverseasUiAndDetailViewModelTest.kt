package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasIntelSnapshot
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.ui.overseas.OfferTagTone
import com.nodeseek.app.ui.overseas.OverseasIntelDetailViewModel
import com.nodeseek.app.ui.overseas.OverseasIntelViewModel
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
    fun visualParserExtractsMerchantPriceSpecsRegionsAndStripsBoilerplate() {
        val aiOffer = OverseasDealOffer(
            id = "223566",
            title = "RackNerd 北美多机房 KVM 入门与常规套餐促销",
            originalTitle = "RackNerd - Los Angeles & San Jose KVM VPS - 2 vCPU 4GB RAM 45GB NVMe 4TB@1Gbps \$18.99/yr Recurring Promo Code: FLASH2026",
            summary = "RackNerd 推出洛杉矶、圣何塞及芝加哥多机房 KVM 虚拟服务器常规特惠方案，适合日常建站。",
            merchant = "RackNerd",
            sourceLink = "https://lowendtalk.com/discussion/223566/racknerd-special",
            publishedAtEpochMillis = 1_700_000_000_000L,
        )

        val aiCard = OverseasOfferVisualParser.parseCardModel(
            offer = aiOffer,
            displayMode = OverseasDisplayMode.BILINGUAL,
            nowEpochMillis = 1_700_001_500_000L,
        )
        assertEquals("RackNerd", aiCard.merchantLabel)
        assertEquals("R", aiCard.merchantInitial)
        assertEquals("LET", aiCard.sourceBadge)
        assertEquals("25 分钟前", aiCard.relativeTimeText)
        assertEquals("$18.99", aiCard.priceBadge?.amountText)
        assertEquals("/年", aiCard.priceBadge?.cycleLabel)
        assertEquals("FLASH2026", aiCard.promoCode)
        assertTrue(aiCard.regionTags.containsAll(listOf("洛杉矶", "圣何塞", "芝加哥")))
        assertFalse(aiCard.regionTags.contains("美国"))
        assertTrue(aiCard.specChips.containsAll(listOf("2 vCPU", "4GB RAM", "45GB NVMe", "4TB 流量", "1Gbps")))
        assertTrue(aiCard.promoTags.any { it.label == "续费同价" && it.tone == OfferTagTone.PERK })
        assertNotNull(aiCard.cleanSummary)
        assertNotNull(aiCard.secondaryTitle)
        assertFalse(aiCard.isEnglishSourceTitle)

        // Non-AI English offer with noisy merchant="BLACK FRIDAY", stray "– ·", and boilerplate summary
        val rawOffer = OverseasDealOffer(
            id = "223590",
            title = "[HostPapa] Black Friday Web Hosting – · cPanel | FREE Domain Y1 | CA/US/EU",
            originalTitle = "[HostPapa] Black Friday Web Hosting – \$1.25/mo | 25 GB NVMe | cPanel | FREE Domain Y1 | CA/US/EU |",
            summary = "来自 HostPapa 的 LowEndTalk 海外服务器与 VPS 促销情报，点击查看完整方案详情。",
            merchant = "BLACK FRIDAY",
            sourceLink = "https://lowendtalk.com/discussion/223590/hostpapa-bf",
            publishedAtEpochMillis = 1_700_000_000_000L,
        )

        val rawCard = OverseasOfferVisualParser.parseCardModel(
            offer = rawOffer,
            displayMode = OverseasDisplayMode.CHINESE,
            translatedTitle = "[HostPapa] 黑五虚拟主机特惠 · cPanel | 首年免费域名 | 加拿大/美国/欧洲",
            nowEpochMillis = 1_700_003_600_000L,
        )
        assertEquals("HostPapa", rawCard.merchantLabel)
        assertEquals("$1.25", rawCard.priceBadge?.amountText)
        assertEquals("/月", rawCard.priceBadge?.cycleLabel)
        assertTrue(rawCard.regionTags.containsAll(listOf("美国", "加拿大", "欧洲")))
        assertTrue(rawCard.specChips.containsAll(listOf("25GB NVMe", "cPanel")))
        assertTrue(rawCard.promoTags.any { it.label == "黑五特惠" && it.tone == OfferTagTone.PROMO })
        assertTrue(rawCard.promoTags.any { it.label == "赠域名" && it.tone == OfferTagTone.PERK })
        assertNull(rawCard.cleanSummary)
        assertTrue(rawCard.isEnglishSourceTitle)
        assertEquals("[HostPapa] 黑五虚拟主机特惠 · cPanel | 首年免费域名 | 加拿大/美国/欧洲", rawCard.headlineTitle)
        assertNull(rawCard.secondaryTitle)
    }

    @Test
    fun visualParserAvoidsFalsePriceAndRegionMatches() {
        val trickyOffer = OverseasDealOffer(
            id = "999",
            title = "CloudCone Tokyo 2C/4G NVMe €12/year",
            originalTitle = "CloudCone Tokyo 2C/4G 40GB NVMe €12/year - Get \$50 credit when you contact us",
            summary = "99.9% uptime on 11.11 Flash Sale in Tokyo",
            merchant = "CloudCone",
            sourceLink = "https://lowendbox.com/blog/cloudcone",
        )

        val card = OverseasOfferVisualParser.parseCardModel(
            offer = trickyOffer,
            displayMode = OverseasDisplayMode.ORIGINAL,
        )
        assertEquals("LEB", card.sourceBadge)
        assertEquals("€12", card.priceBadge?.amountText)
        assertEquals("/年", card.priceBadge?.cycleLabel)
        assertEquals(listOf("东京"), card.regionTags)
        assertFalse(card.regionTags.contains("美国"))
        assertTrue(card.specChips.containsAll(listOf("2 vCPU", "4GB RAM", "40GB NVMe")))
    }

    @Test
    fun detailViewModelLoadsAndRepostsWithoutAnyTranslationCalls() = runTest {
        val fakeRepo = TrackingDetailRepository()
        val vm = OverseasIntelDetailViewModel(offerId = "223566", repository = fakeRepo)
        advanceUntilIdle()

        val loadedState = vm.uiState.value
        assertFalse(loadedState.isLoading)
        assertNotNull(loadedState.detail)
        assertEquals("223566", loadedState.detail?.id)
        assertEquals(0, fakeRepo.translateDetailCalls)
        assertEquals(0, fakeRepo.updateDisplayModeCalls)
        assertEquals(0, fakeRepo.updateTranslationConfigCalls)
        assertEquals(1, fakeRepo.getDetailCalls)

        // Confirm OverseasOfferDetailUiState exposes only detail/loading/repost/error fields (no translation fields)
        val fieldNames = OverseasOfferDetailUiState::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fieldNames.any { it.contains("translat", ignoreCase = true) || it.contains("displayMode", ignoreCase = true) })

        val repostDeferred = async { vm.repostDraftEvents.first() }
        vm.prepareRepost()
        advanceUntilIdle()
        val draft = repostDeferred.await()
        assertTrue(draft.title.startsWith("[海外情报/LET]"))
        assertFalse(vm.uiState.value.isReposting)
    }

    @Test
    fun detailViewModelSurfacesFailureAndRecoversOnRetry() = runTest {
        val fakeRepo = TrackingDetailRepository(failFirstDetailFetch = true)
        val vm = OverseasIntelDetailViewModel(offerId = "223566", repository = fakeRepo)
        advanceUntilIdle()

        assertNull(vm.uiState.value.detail)
        assertEquals("网络超时", vm.uiState.value.errorMessage)

        vm.loadDetail(forceRefresh = true)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.detail)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun listViewModelPreservesListTranslationAndSkipsAlreadyChineseTitles() = runTest {
        val chineseOffer = OverseasDealOffer(
            id = "101",
            title = "RackNerd 洛杉矶黑五促销",
            originalTitle = "RackNerd LA Black Friday",
            summary = "洛杉矶 KVM 特惠套餐。",
            merchant = "RackNerd",
            sourceLink = "https://lowendtalk.com/discussion/101",
        )
        val englishOffer = OverseasDealOffer(
            id = "102",
            title = "Special Offers: Storage VPS in Frankfurt - Lifetime Recurring",
            originalTitle = "Special Offers: Storage VPS in Frankfurt - Lifetime Recurring",
            summary = "来自 Host 的 LowEndTalk 海外服务器与 VPS 促销情报，点击查看完整方案详情。",
            merchant = "Host",
            sourceLink = "https://lowendtalk.com/discussion/102",
        )
        val repo = TrackingDetailRepository(
            initialOffers = listOf(chineseOffer, englishOffer),
            initialConfig = OverseasTranslationConfig(
                engineKind = TranslationEngineKind.OFFLINE_GLOSSARY_ONLY,
                displayMode = OverseasDisplayMode.ORIGINAL,
            ),
        )
        val translator = OverseasTranslator(
            cacheFile = File(tempFolder.root, "trans.json"),
            ioDispatcher = testDispatcher,
        )
        val vm = OverseasIntelViewModel(
            repository = repo,
            autoLoadOnInit = false,
            translator = translator,
        )

        val collectJob = async {
            vm.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(OverseasDisplayMode.ORIGINAL, vm.uiState.value.translationConfig.displayMode)
        assertEquals(2, vm.uiState.value.offerCards.size)

        // Cycle to CHINESE mode: Chinese offer stays intact; English offer gets translated!
        vm.cycleDisplayMode()
        advanceUntilIdle()
        assertEquals(OverseasDisplayMode.CHINESE, vm.uiState.value.translationConfig.displayMode)

        val cardsAfterTranslate = vm.uiState.value.offerCards
        val cnCard = cardsAfterTranslate.first { it.id == "101" }
        val enCard = cardsAfterTranslate.first { it.id == "102" }
        assertEquals("RackNerd 洛杉矶黑五促销", cnCard.headlineTitle)
        assertTrue(enCard.headlineTitle.contains("法兰克福"))
        assertTrue(enCard.headlineTitle.contains("大盘鸡"))

        // Search matches translated Chinese keyword on English offer
        vm.updateSearchQuery("大盘鸡")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.offerCards.size)
        assertEquals("102", vm.uiState.value.offerCards.first().id)

        collectJob.cancel()
    }

    private class TrackingDetailRepository(
        initialOffers: List<OverseasDealOffer> = emptyList(),
        initialConfig: OverseasTranslationConfig = OverseasTranslationConfig(),
        private var failFirstDetailFetch: Boolean = false,
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
        var updateDisplayModeCalls = 0
        var updateTranslationConfigCalls = 0

        override suspend fun loadFromCache(): List<OverseasDealOffer> = _snapshot.value.offers

        override suspend fun refresh(query: String): Result<List<OverseasDealOffer>> =
            Result.success(_snapshot.value.offers)

        override suspend fun loadNextPage(query: String): Result<List<OverseasDealOffer>> =
            Result.success(_snapshot.value.offers)

        override suspend fun getOfferDetail(offerId: String, forceRefresh: Boolean): Result<OverseasOfferDetail> {
            getDetailCalls++
            if (failFirstDetailFetch) {
                failFirstDetailFetch = false
                return Result.failure(IllegalStateException("网络超时"))
            }
            return delegate.getOfferDetail(offerId, forceRefresh)
        }

        override suspend fun translateOfferDetail(offerId: String, forceRefresh: Boolean): Result<OverseasOfferDetail> {
            translateDetailCalls++
            return delegate.translateOfferDetail(offerId, forceRefresh)
        }

        override suspend fun getRepostContent(offerId: String, forceRefresh: Boolean): Result<OverseasRepostContent> =
            delegate.getRepostContent(offerId, forceRefresh)

        override suspend fun updateTranslationConfig(config: OverseasTranslationConfig) {
            updateTranslationConfigCalls++
            _snapshot.update { it.copy(translationConfig = config) }
        }

        override suspend fun updateDisplayMode(mode: OverseasDisplayMode) {
            updateDisplayModeCalls++
            _snapshot.update { it.copy(translationConfig = it.translationConfig.copy(displayMode = mode)) }
        }

        override suspend fun testTranslationConfig(config: OverseasTranslationConfig): Result<String> =
            Result.success("ok")

        override suspend fun clearTranslationCache() = Unit
    }
}
