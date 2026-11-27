package com.nodeseek.app.e2e.scenarios

import android.content.Intent
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.local.BookmarkEntity
import com.nodeseek.app.data.local.DraftEntity
import com.nodeseek.app.data.local.ReadHistoryEntity
import com.nodeseek.app.data.netbench.TracerouteProbeReply
import com.nodeseek.app.data.network.computeDynamicSign
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.valuation.FxRefreshOutcome
import com.nodeseek.app.data.widget.WidgetSnapshotRepositoryImpl
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import com.nodeseek.app.domain.activity.LuckyDrawSpec
import com.nodeseek.app.domain.activity.LuckyViewerStatus
import com.nodeseek.app.domain.alerts.AlertEventCategory
import com.nodeseek.app.domain.alerts.AlertForwardingConfig
import com.nodeseek.app.domain.alerts.BarkForwardConfig
import com.nodeseek.app.domain.alerts.TelegramForwardConfig
import com.nodeseek.app.domain.alerts.WebhookForwardConfig
import com.nodeseek.app.domain.baseline.BaselineBandwidthMetrics
import com.nodeseek.app.domain.baseline.BaselineComparisonFormatter
import com.nodeseek.app.domain.baseline.BaselineDiffEngine
import com.nodeseek.app.domain.baseline.BaselineOverallVerdict
import com.nodeseek.app.domain.baseline.BaselineRoutingMetrics
import com.nodeseek.app.domain.baseline.BaselineSourceType
import com.nodeseek.app.domain.baseline.BenchmarkBaselineSnapshot
import com.nodeseek.app.domain.baseline.StreamingPlatformResult
import com.nodeseek.app.domain.baseline.StreamingUnlockStatus
import com.nodeseek.app.domain.carpool.CarpoolBillingCycle
import com.nodeseek.app.domain.carpool.CarpoolCalculationInput
import com.nodeseek.app.domain.carpool.CarpoolCalculationOutcome
import com.nodeseek.app.domain.carpool.CarpoolCalculator
import com.nodeseek.app.domain.carpool.CarpoolMarkdownGenerator
import com.nodeseek.app.domain.carpool.CarpoolSeatSlot
import com.nodeseek.app.domain.carpool.CarpoolSeatSplitMode
import com.nodeseek.app.domain.carpool.CarpoolSeatStatus
import com.nodeseek.app.domain.carpool.CarpoolStatusUpdater
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.FleetDeliveryMethod
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.FleetSalePostBuilder
import com.nodeseek.app.domain.fleet.FleetSalePostOutcome
import com.nodeseek.app.domain.fleet.FleetSalePostRequest
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.image.BitmapMosaicProcessor
import com.nodeseek.app.domain.image.MosaicEdit
import com.nodeseek.app.domain.image.MosaicRect
import com.nodeseek.app.domain.mail.OtpWatchState
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotStatus
import com.nodeseek.app.domain.model.AccountSwitchResult
import com.nodeseek.app.domain.model.CookieHostScope
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.StardustTipTarget
import com.nodeseek.app.domain.model.StoredCookieRecord
import com.nodeseek.app.domain.model.UpgradeEtaStatus
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshSftpTransport
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.trade.TradeDeliveryChecklistEngine
import com.nodeseek.app.domain.trade.TradePrivacyOptions
import com.nodeseek.app.domain.trade.TradePrivacySanitizer
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.ValuationMarkdownStyle
import com.nodeseek.app.domain.valuation.ValuationMode
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.domain.valuation.VpsValuationInput
import com.nodeseek.app.domain.valuation.VpsValuationOutcome
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eFleetAlertSink
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eNetBenchNetworkBridge
import com.nodeseek.app.e2e.E2eSftpClient
import com.nodeseek.app.e2e.E2eSftpTransport
import com.nodeseek.app.e2e.E2eSshTransport
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.e2e.E2eTracerouteKernelOps
import com.nodeseek.app.e2e.fixtures.E2eBitmapAndTlsFixtures
import com.nodeseek.app.navigation.SeekMailRoute
import com.nodeseek.app.navigation.ShortcutCommand
import com.nodeseek.app.navigation.ShortcutIntentParser
import com.nodeseek.app.notifications.OtpWatchNotificationHelper
import com.nodeseek.app.ui.composer.ImagePrivacyBitmapCodec
import com.nodeseek.app.ui.composer.MosaicSupportStatus
import com.nodeseek.app.ui.composer.TopicComposerUiEvent
import com.nodeseek.app.ui.explore.ExploreViewModel
import com.nodeseek.app.ui.fleet.MyServersTab
import com.nodeseek.app.ui.fleet.MyServersViewModel
import com.nodeseek.app.ui.hub.CreditStardustLedgerViewModel
import com.nodeseek.app.ui.hub.EconomyLedgerTab
import com.nodeseek.app.ui.netbench.NetBenchPhase
import com.nodeseek.app.ui.share.LongImageThemeStyle
import com.nodeseek.app.ui.share.TopicLongImageAssetFetcher
import com.nodeseek.app.ui.share.TopicLongImageQrGenerator
import com.nodeseek.app.ui.share.TopicLongImageRenderer
import com.nodeseek.app.ui.share.TopicLongImageRequest
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class S1ToS8ScenarioE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private fun extractCookieValue(cookieHeader: String?, name: String): String? =
        cookieHeader.orEmpty()
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun buildThreadHtml(
        postId: Long,
        categorySlug: String,
        categoryWord: String,
        title: String,
        markdown: String,
        rank: Int = 0,
        opUid: Long = 101L,
        opName: String = "alice101",
        viewerUid: Long = opUid,
        viewerName: String = opName,
    ): String {
        val baseHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
        val encodedMatch = Regex("""<script id="temp-script"[^>]*>(.*?)</script>""").find(baseHtml)
            ?: error("Missing temp-script")
        val decodedJson = Base64.getDecoder().decode(encodedMatch.groupValues[1]).toString(Charsets.UTF_8)
        val catsJson = TempScriptParser.json.parseToJsonElement(decodedJson).jsonObject.getValue("allCategory").toString()
        val cfg = """{"user":{"member_id":$viewerUid,"member_name":${JsonPrimitive(viewerName)},"coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":$catsJson,"postData":{"postId":$postId,"postPage":1,"postPageCount":1,"title":${JsonPrimitive(title)},"category":${JsonPrimitive(categorySlug)},"categoryWord":${JsonPrimitive(categoryWord)},"categoryLink":"/categories/$categorySlug","views":320,"collectionCount":4,"rank":$rank,"locked":0,"collected":0,"award":0,"op":{"uid":$opUid,"name":${JsonPrimitive(opName)},"avatar":"/avatar/$opUid.png"},"comments":[{"commentId":${postId * 100},"floorIndex":0,"poster":{"uid":$opUid,"name":${JsonPrimitive(opName)},"avatar":"/avatar/$opUid.png","isOp":1,"rank":4,"roles":[]},"time":{"createdDate":"2026-11-25T10:00:00Z","createdDateFormated":"2026-11-25 18:00:00"},"markdown":${JsonPrimitive(markdown)},"likeCount":4,"dislikeCount":0,"upvoteCount":4,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]}}"""
        return E2eFixtures.wrapTempScriptHtml(
            configJson = cfg,
            bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>$title</h1><article class="post-content"></article></div></div></div>""",
            title = title,
        )
    }

    @Test
    fun `E2E-T4-S1 community lifecycle checkIn credit ledger ETA poll Drand lucky draw and stardust tip`() = runBlocking {
        val nowMs = Instant.parse("2026-11-25T12:00:00Z").toEpochMilli()
        E2eTestHarness(initialEpochMillis = nowMs).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 1, coin = 396, stardust = 42)

            val attendanceComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/attendance_board_states.json"),
            ).jsonObject
            val creditFixture = E2eFixtures.loadText("nodeseek/credit_ledger_30d.json")
            val stardustComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/stardust_ledger_and_transfer.json"),
            ).jsonObject
            val floorDataJson = E2eFixtures.loadText("drand/floor_data_456.json")
            val drandRound5618965Json = E2eFixtures.loadText("drand/drand_round_4500000.json")
                .replace("\"round\": 4500000", "\"round\": 5618965")

            harness.forumDispatcher.register("POST", "/api/attendance") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"gain":5,"current":401}""")
            }
            harness.forumDispatcher.register("GET", "/api/attendance/board") {
                MockResponse().setResponseCode(200).setBody(attendanceComposite.getValue("fiveGainChecked").toString())
            }
            harness.forumDispatcher.register("GET", "/api/account/credit/page-1") {
                MockResponse().setResponseCode(200).setBody(creditFixture)
            }

            var currentStardust = 42
            val sendReqBodyRef = AtomicReference<String?>(null)
            harness.forumDispatcher.register("GET", "/api/stardust/list") {
                val listObj = if (currentStardust == 42) {
                    stardustComposite.getValue("list").toString()
                } else {
                    """{"success":true,"records":[{"id":999,"diff":-10,"result":32,"type":"transfer","created_at":"2026-11-25T12:10:00Z","peer_id":202}],"exist_more":false,"cursor":null}"""
                }
                MockResponse().setResponseCode(200).setBody(listObj)
            }
            harness.forumDispatcher.register("POST", "/api/stardust/payment-prepare") {
                MockResponse().setResponseCode(200).setBody(stardustComposite.getValue("paymentPrepare").toString())
            }
            harness.forumDispatcher.register("POST", "/api/stardust/send") { req ->
                sendReqBodyRef.set(req.body.readUtf8())
                currentStardust = 32
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            // 1. Check-in (+5 -> 401, Lv.1 -> Lv.2)
            val checkInStatus = harness.checkInRepository.checkIn(randomReward = false).getOrThrow()
            assertTrue(checkInStatus.hasCheckedInToday)
            assertEquals(5, checkInStatus.todayGain)
            assertEquals(421, checkInStatus.todayOrder)
            assertEquals(2, AccountSession.calculateLevel(401))

            // 2. Credit ledger & Lv.3 ETA (900 - 401 = 499)
            val creditVm = harness.manage(
                CreditStardustLedgerViewModel(
                    repository = harness.communityToolsRepository,
                    initialTab = EconomyLedgerTab.DRUMSTICKS,
                    targetUid = 101L,
                    currentCoins = 401,
                    currentStardust = currentStardust,
                    canAccessDrumsticks = true,
                    clockMillis = harness.clock::nowMillis,
                ),
            )
            val ledgerState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { creditVm.uiState.first { !it.isLoadingCredit && it.creditAnalytics != null } }
            }
            val analytics = ledgerState.creditAnalytics!!
            assertEquals(499, analytics.upgradeEta.coinsRemaining)
            assertEquals(UpgradeEtaStatus.ESTIMATED, analytics.upgradeEta.status)

            // 3. Compose poll + lucky draw -> publish (new-discussion + edit-discussion)
            val publishedTitleRef = AtomicReference("S1 社区活动抽奖")
            val publishedContentRef = AtomicReference("")
            harness.forumDispatcher.register("POST", "/api/vote/info") {
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"vote":{"id":77,"title":"喜欢哪个节点？","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":701,"text":"HK","count":0,"voted":0,"voters":[]},{"vote_item_id":702,"text":"LAX","count":0,"voted":0,"voters":[]}]}}""",
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/new-discussion") { req ->
                val json = TempScriptParser.json.parseToJsonElement(req.body.readUtf8()).jsonObject
                publishedTitleRef.set(json.getValue("title").jsonPrimitive.content)
                publishedContentRef.set(json.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":456}""")
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val json = TempScriptParser.json.parseToJsonElement(req.body.readUtf8()).jsonObject
                publishedContentRef.set(json.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val composer = harness.createTopicComposerViewModel(initialCategorySlug = "daily")
            val composerEvents = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val cStateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val cEventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.events.collect { composerEvents.add(it) } }

            composer.createPollAndInsert("喜欢哪个节点？", multiple = false, isPublic = true, options = listOf("HK", "LAX"))
            val pollSnippet = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (composerEvents.none { it is TopicComposerUiEvent.InsertUploadedMarkdown }) delay(15L)
                    composerEvents.filterIsInstance<TopicComposerUiEvent.InsertUploadedMarkdown>().single().snippet
                }
            }
            composer.setPendingLuckyDrawSpec(
                LuckyDrawSpec(postId = 0L, drawTimeEpochMillis = 1_764_000_000_000L, winnerCount = 2, firstFloor = 1, removeDuplicates = true),
            )
            composer.onTitleChanged("S1 社区活动抽奖")
            composer.onMarkdownChanged("${pollSnippet.trim()}\n\n[投喂星尘](nsapp://stardust-receive?uid=202&amount=10&ref=456&once=1&note=感谢)")
            composer.onPublishClicked()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (composerEvents.none { it is TopicComposerUiEvent.PublishedSuccess }) delay(15L)
                }
            }
            assertTrue(publishedContentRef.get().contains("https://www.nodeseek.com/lucky?post=456"))

            // 4. Open TopicDetailViewModel -> vote + Drand lucky draw + winner reply + stardust tip
            harness.forumDispatcher.register("GET", "/post-456-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildThreadHtml(456L, "daily", "日常", publishedTitleRef.get(), publishedContentRef.get()),
                )
            }
            harness.forumDispatcher.enqueueNext(
                "GET",
                "/api/vote/info/77",
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"vote":{"id":77,"title":"喜欢哪个节点？","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":701,"text":"HK","count":0,"voted":0,"voters":[]},{"vote_item_id":702,"text":"LAX","count":0,"voted":0,"voters":[]}]}}""",
                ),
            )
            harness.forumDispatcher.enqueueNext(
                "GET",
                "/api/vote/info/77",
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"vote":{"id":77,"title":"喜欢哪个节点？","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":701,"text":"HK","count":1,"voted":1,"voters":[101]},{"vote_item_id":702,"text":"LAX","count":0,"voted":0,"voters":[]}]}}""",
                ),
            )
            harness.forumDispatcher.register("POST", "/api/vote/voteforitem") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(floorDataJson)
            }
            harness.externalDispatcher.register("GET", "/${DrandLuckyEngine.DRAND_CHAIN_HASH}/public/5618965") {
                MockResponse().setResponseCode(200).setBody(drandRound5618965Json)
            }
            val replyBodyRef = AtomicReference<String?>(null)
            harness.forumDispatcher.register("POST", "/api/content/new-comment") { req ->
                replyBodyRef.set(req.body.readUtf8())
                MockResponse().setResponseCode(200).setBody("""{"success":true,"commentId":45699}""")
            }
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html"))
            }

            val detailVm = harness.createTopicDetailViewModel(topicId = 456L)
            val dStateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.pageData != null } }
            }

            // Cast vote
            detailVm.nsAppWidgetController.ensureVoteLoaded(77L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.nsAppWidgetController.pollStateFor(77L).first { it.poll != null } }
            }
            detailVm.nsAppWidgetController.togglePollOption(77L, 701L, isMultiple = false)
            detailVm.nsAppWidgetController.submitVote(77L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.nsAppWidgetController.pollStateFor(77L).first { it.poll?.hasCurrentUserVoted == true } }
            }

            // Resolve Drand lucky draw & post winner reply
            val luckySpec = DrandLuckyEngine.extractFirstLuckySpec(loaded.openingFloor!!.markdownBody, fallbackPostId = 456L)!!
            val luckyOutcome = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = luckySpec,
                viewerUid = 202L,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()
            assertEquals(LuckyViewerStatus.MY_WIN, luckyOutcome.viewerStatus)
            val winnerReplyMd = DrandLuckyEngine.buildWinnerReplyMarkdown(luckyOutcome)
            assertTrue(winnerReplyMd.contains("@bob202"))
            detailVm.onReplyDraftChanged(winnerReplyMd)
            detailVm.submitReply()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { while (replyBodyRef.get() == null) delay(15L) }
            }
            assertTrue(replyBodyRef.get()!!.contains("@bob202"))

            // Stardust tip via public openStardustTip + submitStardustTip -> refresh stardust ledger (-10 -> 32)
            detailVm.openStardustTip(
                StardustTipTarget(
                    receiverUid = 202L,
                    receiverNameHint = "bob202",
                    suggestedAmount = 10,
                    refId = 456L,
                    once = true,
                    note = "感谢",
                ),
            )
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.stardustTipDialog?.isPreparing == false } }
            }
            detailVm.submitStardustTip()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { while (sendReqBodyRef.get() == null) delay(15L) }
            }
            assertEquals(32, currentStardust)

            val stardustVm = harness.manage(
                CreditStardustLedgerViewModel(
                    repository = harness.communityToolsRepository,
                    initialTab = EconomyLedgerTab.STARDUST,
                    targetUid = 101L,
                    currentCoins = 401,
                    currentStardust = currentStardust,
                    canAccessDrumsticks = true,
                    clockMillis = harness.clock::nowMillis,
                ),
            )
            val sdState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { stardustVm.uiState.first { !it.isLoadingStardust && it.stardustRecords.isNotEmpty() } }
            }
            assertEquals(-10, sdState.stardustRecords.single().diff)
            assertEquals(32, sdState.stardustRecords.single().balanceAfter)
            harness.assertNoCookiesLeakedToExternalServer()

            cStateJob.cancel()
            cEventJob.cancel()
            dStateJob.cancel()
        }
    }

    @Test
    fun `E2E-T4-S2 buy machine benchmark forward MTR reverse SSH trace purchase baseline SFTP bak edit and 14d compare`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101")
            harness.registerNetBenchGeoIpRoutes(clientEgressIp = "114.240.88.123")

            val bastion = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "HK Jump",
                    host = "203.0.113.10",
                    port = 22,
                    username = "jump",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "jump-pw",
                ),
            )
            val target = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "LAX Pro",
                    host = "154.17.12.88",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "root-pw",
                    jumpHostId = bastion.id,
                ),
            )
            val bastionKey = HostKeyInfo("203.0.113.10", 22, "ssh-ed25519", HostKeyTrustPolicy.computeSha256Fingerprint("bastion-s2".toByteArray()))
            val targetKey = HostKeyInfo("154.17.12.88", 22, "ssh-ed25519", HostKeyTrustPolicy.computeSha256Fingerprint("target-s2".toByteArray()))
            val keyMap = mapOf(bastion.id to bastionKey, target.id to targetKey)

            var presetRun = 0
            val sshTransport = E2eSshTransport(
                hostKeyByServerOrHost = keyMap,
                commandHandler = { _, cmd ->
                    val output = if (cmd.contains("traceroute") || cmd.contains("mtr") || cmd.contains("nexttrace")) {
                        E2eFixtures.loadMtrSection("REVERSE_NEXTTRACE_CN2_GIA")
                    } else {
                        presetRun += 1
                        if (presetRun == 1) {
                            "Download: 240.0 Mbps\nUpload: 180.0 Mbps\nPing: 145.0 ms\n电信回程: 163 骨干"
                        } else {
                            "Download: 780.0 Mbps\nUpload: 520.0 Mbps\nPing: 118.0 ms\n电信回程: CN2 GIA"
                        }
                    }
                    listOf(SshStreamEvent.Connected, SshStreamEvent.OutputChunk("$output\n"), SshStreamEvent.Completed(0))
                },
            )
            val originalSingBox = """{"congestion":"cubic"}"""
            val sftpClient = E2eSftpClient(
                homeDirectory = "/root",
                initialFiles = mapOf("/etc/sing-box/config.json" to originalSingBox.toByteArray()),
            )
            val sftpTransport = E2eSftpTransport(hostKeyByServerOrHost = keyMap, clientFactory = { sftpClient })
            val sshManager = harness.createSshSessionManager(
                transport = sshTransport,
                sftpTransport = sftpTransport,
                baselineRepo = harness.benchmarkBaselineStore,
            )

            val kernelOps = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 14.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "154.17.12.88", seq, 138.0)
                    }
                },
            )
            val netBench = harness.createNetBenchController(
                networkBridge = E2eNetBenchNetworkBridge(),
                tracerouteKernelOps = kernelOps,
                sshSessionManager = sshManager,
                baselineRepo = harness.benchmarkBaselineStore,
            )

            // 1. Forward MTR + SSH reverse trace
            netBench.updateTargetInput("154.17.12.88")
            netBench.updateTracerouteProbesPerHop(5)
            netBench.runForwardTracerouteOnly()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { netBench.uiState.first { !it.isRunning && it.activePhase == NetBenchPhase.COMPLETED } }
            }
            netBench.runReverseTraceroute(target)
            val afterReverse = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { netBench.uiState.first { !it.isRunningReverseTrace && it.reverseTracerouteResult != null } }
            }
            assertTrue(BackboneRouteTag.CN2_GIA in afterReverse.reverseTracerouteResult!!.dominantBackboneTags)
            assertFalse(afterReverse.generatedMarkdown.contains("114.240.88.123"))

            // Save NetBench baseline + run SSH preset baseline
            netBench.saveCurrentAsBaseline("手机测机基线")
            val sshVm = harness.manage(LiteSshViewModel(sshManager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { sshVm.uiState.first { it.servers.any { s -> s.id == target.id } } }
            }
            sshVm.selectServer(target.id)
            sshVm.selectPreset(SshPresetCatalog.PRESET_NODE_QUALITY)
            sshVm.runSelectedPreset()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { sshVm.uiState.first { !it.isRunning && it.sessionRuns.size == 1 } }
            }
            sshVm.saveCurrentAsBaseline("购入基线")
            val savedSshBaseline = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.benchmarkBaselineStore.baselinesFlow.first { list -> list.any { it.label == "购入基线" } }
                }
            }.first { it.label == "购入基线" }

            // 2. SFTP edit /etc/sing-box/config.json with .bak
            sshVm.openSftpDrawer(target.id)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { sshVm.uiState.first { it.sftpDrawer.isConnected } }
            }
            sshVm.sftpOpenFile("/etc/sing-box/config.json", preferEditMode = true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { sshVm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.isEditMode == true } }
            }
            val tunedSingBox = """{"congestion":"bbr"}"""
            sshVm.sftpUpdateEditorContent(tunedSingBox)
            sshVm.sftpSaveFileWithBak()
            val savedDrawer = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { sshVm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.isDirty == false } }
            }
            assertEquals(originalSingBox, sftpClient.readRawFileBytes("/etc/sing-box/config.json.bak")?.toString(Charsets.UTF_8))
            assertEquals(tunedSingBox, sftpClient.readRawFileBytes("/etc/sing-box/config.json")?.toString(Charsets.UTF_8))
            assertTrue(savedDrawer.sftpDrawer.lastBackupBanner?.contains(".bak") == true)

            // 3. Advance clock +14 days, re-bench, and compare vs Purchase Baseline (SSH + NetBench IP masking)
            harness.clock.advanceByMillis(14L * 86_400_000L)
            sshVm.clearSessionRuns()
            sshVm.runSelectedPreset()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { sshVm.uiState.first { !it.isRunning && it.sessionRuns.size == 1 } }
            }
            sshVm.openBaselineCompareSheet(savedSshBaseline.id)
            val compareState = sshVm.uiState.value
            assertTrue(compareState.showBaselineCompareSheet)
            val diff = compareState.activeBaselineDiff!!
            assertEquals(14L, diff.elapsedDays)
            assertEquals(BaselineOverallVerdict.IMPROVED, diff.overallVerdict)
            val formatted = BaselineComparisonFormatter.formatMarkdown(diff, maskTargetIp = true)
            assertTrue(formatted.contains("LAX Pro"))
            assertFalse(formatted.contains("154.17.12.88"))

            netBench.runForwardTracerouteOnly()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { netBench.uiState.first { !it.isRunning && it.activePhase == NetBenchPhase.COMPLETED } }
            }
            netBench.openBaselineCompareSheet()
            val netBenchDiff = netBench.uiState.value.activeBaselineDiff!!
            val netBenchFormatted = BaselineComparisonFormatter.formatMarkdown(netBenchDiff, maskTargetIp = true)
            assertTrue(netBenchFormatted.contains("154.17.12.*"))
            assertFalse(netBenchFormatted.contains("154.17.12.88"))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T4-S3 idle VPS resale closed loop baseline diff market premium ref publish trade post and one-tap sold closure`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val fxJson = E2eFixtures.loadText("nodeseek/fx_latest_cny.json")
            val feedTradeHtml = E2eFixtures.loadText("nodeseek/feed_trade_page1.html")
            val searchTradeHtml = E2eFixtures.loadText("nodeseek/search_trade_dmit_pro.html")
            harness.externalDispatcher.register("GET", "/fx/latest") {
                MockResponse().setResponseCode(200).setBody(fxJson)
            }
            harness.forumDispatcher.register("GET", "/categories/trade") {
                MockResponse().setResponseCode(200).setBody(feedTradeHtml)
            }
            harness.forumDispatcher.register("GET", "/search") {
                MockResponse().setResponseCode(200).setBody(searchTradeHtml)
            }

            // 1. Before-vs-After diff
            val t0 = harness.clock.nowMillis()
            val beforeSnap = BenchmarkBaselineSnapshot(
                id = "b_s3",
                label = "购入首测",
                targetKey = "198.51.100.88",
                serverId = "s3-vps",
                hostDisplay = "198.51.100.88",
                resolvedIp = "198.51.100.88",
                sourceType = BaselineSourceType.SSH_BENCHMARK,
                capturedAtEpochMillis = t0,
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 800.0, uploadMbps = 500.0),
                routing = BaselineRoutingMetrics(reverseRouteSignature = "CN2 GIA", reverseBackboneTags = listOf(BackboneRouteTag.CN2_GIA)),
                streamingUnlocks = listOf(StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.UNLOCKED, "US")),
            )
            val afterSnap = beforeSnap.copy(
                id = "a_s3",
                capturedAtEpochMillis = t0 + 30L * 86_400_000L,
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 400.0, uploadMbps = 250.0),
                routing = BaselineRoutingMetrics(reverseRouteSignature = "163", reverseBackboneTags = listOf(BackboneRouteTag.CT_163)),
            )
            val diffMd = BaselineComparisonFormatter.formatMarkdown(BaselineDiffEngine.compare(beforeSnap, afterSnap), maskTargetIp = true)
            assertTrue(diffMd.contains("-50.0%"))

            // 2. FX + market reference + valuation + sale post build
            assertTrue(harness.exchangeRateRepository.ensureFreshRates() is FxRefreshOutcome.Success)
            harness.forumRepository.getFeed(categorySlug = "trade", page = 1, sort = FeedSort.LATEST_REPLY).getOrThrow()
            val marketRef = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            assertEquals(MarketSearchStatus.SUCCESS, marketRef.searchStatus)
            assertNotNull(marketRef.distribution?.medianCny)

            val asset = VpsAssetRecord(
                id = "dmit-s3",
                nickname = "DMIT LAX.Pro.WEE",
                providerName = "DMIT",
                specsSummary = "1C/1G/20G",
                region = "洛杉矶",
                renewalAmount = "39.90",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = harness.clock.todayShanghai().plusDays(180).toString(),
            )
            val valOutcome = VpsValuationEngine.calculate(
                VpsValuationInput(
                    renewalAmount = BigDecimal("39.90"),
                    currency = ValuationCurrency.USD,
                    fxRateToCny = BigDecimal("7.20"),
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateTime = harness.clock.todayShanghai().plusDays(180).atStartOfDay(),
                    valuationDateTime = LocalDateTime.of(2025, 11, 25, 0, 0),
                    mode = ValuationMode.TRANSFER_DEAL,
                    askingPriceCny = BigDecimal("220.00"),
                ),
            )
            assertTrue(valOutcome is VpsValuationOutcome.Success)

            val saleOutcome = FleetSalePostBuilder.build(
                FleetSalePostRequest(
                    asset = asset,
                    askingPriceCny = BigDecimal("220.00"),
                    deliveryMethod = FleetDeliveryMethod.PUSH,
                    markdownStyle = ValuationMarkdownStyle.THREE_COLUMN_TABLE,
                    extraNote = "$diffMd\n\n测试 IP: 198.51.100.88 Root密码: S3SecretPw! 订单号: ORDER-2026-S3",
                ),
            )
            assertTrue(saleOutcome is FleetSalePostOutcome.Success)
            val salePost = (saleOutcome as FleetSalePostOutcome.Success).result

            // 3. Publish via TopicComposerViewModel("trade")
            val serverTitle = AtomicReference(salePost.title)
            val serverBody = AtomicReference(salePost.markdownBody)
            val lastEditBody = AtomicReference<String?>(null)
            harness.forumDispatcher.register("POST", "/api/content/new-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.readUtf8()).jsonObject
                serverTitle.set(obj.getValue("title").jsonPrimitive.content)
                serverBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":888}""")
            }
            harness.forumDispatcher.register("GET", "/post-888-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildThreadHtml(888L, "trade", "交易", serverTitle.get(), serverBody.get(), rank = 2),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val raw = req.body.readUtf8()
                lastEditBody.set(raw)
                val obj = TempScriptParser.json.parseToJsonElement(raw).jsonObject
                serverTitle.set(obj.getValue("title").jsonPrimitive.content)
                serverBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val composer = harness.createTopicComposerViewModel(
                initialCategorySlug = "trade",
                initialTitle = salePost.title,
                initialMarkdown = salePost.markdownBody,
            )
            val cEvents = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val cJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val eJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.events.collect { cEvents.add(it) } }
            composer.onPublishClicked()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { while (cEvents.none { it is TopicComposerUiEvent.PublishedSuccess }) delay(15L) }
            }

            // 4. Open TopicDetailViewModel(888L) -> checklist + closeTradeTopic + refreshLocalMarketReference
            val detailVm = harness.createTopicDetailViewModel(topicId = 888L)
            val dJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.pageData != null } }
            }
            val inferred = TradeDeliveryChecklistEngine.inferDeliveryMethod(loaded.pageData!!.title, loaded.openingFloor!!.markdownBody)
            assertTrue(TradeDeliveryChecklistEngine.checklistFor(inferred).isNotEmpty())

            detailVm.closeTradeTopic(TradePrivacyOptions())
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { while (lastEditBody.get() == null) delay(15L) }
            }
            val editJson = TempScriptParser.json.parseToJsonElement(lastEditBody.get()!!).jsonObject
            assertFalse(editJson.containsKey("category"))
            assertEquals(2, editJson.getValue("rank").jsonPrimitive.int)
            val closedTitle = editJson.getValue("title").jsonPrimitive.content
            val closedContent = editJson.getValue("content").jsonPrimitive.content
            assertTrue(closedTitle.startsWith("[已出]"))
            assertFalse(closedContent.contains("198.51.100.88"))
            assertFalse(closedContent.contains("S3SecretPw!"))
            assertFalse(closedContent.contains("ORDER-2026-S3"))
            assertTrue(closedContent.contains("-50.0%"))

            // Refresh market reference -> reflects closed sold without double [已出]
            detailVm.refreshLocalMarketReference("DMIT")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.marketReference != null } }
            }
            assertFalse(closedTitle.startsWith("[已出] [已出]"))
            harness.assertNoCookiesLeakedToExternalServer()

            cJob.cancel()
            eJob.cancel()
            dJob.cancel()
        }
    }

    @Test
    fun `E2E-T4-S4 combined VPS and domain assets RDAP TLS mail ingest portfolio cost widget and external forwarding`() = runBlocking {
        val tlsFixture = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
            commonName = "pulse-edge.dev",
            sanDnsNames = listOf("pulse-edge.dev", "www.pulse-edge.dev"),
            validityDays = 14,
        )
        val nowMs = maxOf(System.currentTimeMillis(), tlsFixture.certificate.notBefore.time + 1_000L)
        E2eTestHarness(initialEpochMillis = nowMs).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "s4_sess")

            val rdapJson = E2eFixtures.loadText("domains/rdap_domain_example.json")
                .replace("example.com", "pulse-edge.dev")
            val mailJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_and_billing.json"),
            ).jsonObject
            val probeFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/probe_and_restock_alerts.json"),
            ).jsonObject
            val responses = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject

            val tlsServer = harness.track(E2eBitmapAndTlsFixtures.LocalTlsServer(tlsFixture))
            harness.tlsSocketOpsOverride = E2eBitmapAndTlsFixtures.LoopbackTlsSocketOps(
                portOverride = tlsServer.port,
                trustManager = tlsFixture.trustManager,
                enforceHttpsHostnameMatch = true,
            )

            harness.externalDispatcher.register("GET", "/rdap/domain/pulse-edge.dev") {
                MockResponse().setResponseCode(302).setHeader("Location", harness.externalServer.url("/registry/pulse-edge.dev").toString())
            }
            harness.externalDispatcher.register("GET", "/registry/pulse-edge.dev") {
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/rdap+json").setBody(rdapJson)
            }

            // 1. MyServersViewModel(ASSETS) -> RDAP + TLS -> save DOMAIN_CERT + VPS
            val vm = harness.manage(
                MyServersViewModel(
                    fleetRepository = harness.fleetRepository,
                    exchangeRateRepository = harness.exchangeRateRepository,
                    todayProvider = harness.clock::todayShanghai,
                    nowMillisProvider = harness.clock::nowMillis,
                    initialTab = MyServersTab.ASSETS,
                    computationDispatcher = Dispatchers.Default,
                    domainRdapClient = harness.domainRdapClient,
                    tlsCertificateInspector = harness.tlsCertificateInspector,
                ),
            )
            val vmJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            vm.openAddDomainAssetSheet()
            vm.lookupDomainRdapForDraft("pulse-edge.dev")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isLookingUpRdap && it.assetEditorDraft?.rdapSyncRevision == 1 } }
            }
            vm.checkTlsForDraft("pulse-edge.dev")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isCheckingTls && it.assetEditorDraft?.tlsCheckRevision == 1 } }
            }
            val domainDraft = vm.uiState.value.assetEditorDraft!!
            vm.saveAsset(
                VpsAssetRecord(
                    id = "s4-domain",
                    nickname = domainDraft.nickname.ifBlank { "pulse-edge.dev" },
                    providerName = domainDraft.providerName,
                    specsSummary = domainDraft.specsSummary,
                    region = domainDraft.region,
                    renewalAmount = "10.00",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = domainDraft.expiryDateIso,
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = domainDraft.domainName,
                    registrarName = domainDraft.registrarName,
                    domainCreatedIso = domainDraft.domainCreatedIso,
                    rdapSyncedAtEpochMillis = domainDraft.rdapSyncedAtEpochMillis,
                    rdapStatusTags = domainDraft.rdapStatusTags,
                    tlsHost = domainDraft.tlsHost,
                    tlsPort = domainDraft.tlsPort.toIntOrNull() ?: VpsAssetRecord.DEFAULT_TLS_PORT,
                    tlsExpiresAtEpochMillis = domainDraft.tlsExpiresAtEpochMillis,
                    tlsIssuer = domainDraft.tlsIssuer,
                    tlsSubject = domainDraft.tlsSubject,
                    tlsCheckedAtEpochMillis = domainDraft.tlsCheckedAtEpochMillis,
                    tlsCheckError = domainDraft.tlsCheckError,
                ),
            )
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.fleetRepository.assetsFlow.first { it.any { a -> a.assetType == FleetAssetType.DOMAIN_CERT } } }
            }

            harness.fleetRepository.upsertPanel(
                ProbePanelConfig(
                    id = "p1",
                    name = "Panel1",
                    baseUrl = harness.externalServer.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                    notifyOnOffline = true,
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "s4-vps",
                    nickname = "SG-Pro",
                    providerName = "DMIT",
                    specsSummary = "1C/1G",
                    region = "SG",
                    renewalAmount = "100.00",
                    currency = ValuationCurrency.CNY,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = harness.clock.todayShanghai().plusDays(20).toString(),
                    linkedProbeKey = "p1:5",
                    monthlyTrafficQuotaGb = 100.0,
                    trafficResetDay = 1,
                ),
            )

            // 2. Domain renewal mail updates only domain expiry
            val domNode = mailJson
                .getValue("domainRenewalDetail").jsonObject
                .getValue("data").jsonObject
                .getValue("mail").jsonObject
            val domSummary = SeekMailSummary(
                id = domNode.getValue("id").jsonPrimitive.content.toLong(),
                mailboxId = domNode.getValue("mailboxId").jsonPrimitive.content.toLong(),
                fromAddress = domNode.getValue("fromAddress").jsonPrimitive.content,
                fromName = domNode.getValue("fromName").jsonPrimitive.content,
                toAddress = domNode.getValue("toAddress").jsonPrimitive.content,
                subject = domNode.getValue("subject").jsonPrimitive.content.replace("example.com", "pulse-edge.dev"),
                snippet = domNode.getValue("bodyText").jsonPrimitive.content.replace("example.com", "pulse-edge.dev"),
                messageId = domNode.getValue("messageId").jsonPrimitive.content,
            )
            val mailUpdates = harness.vpsMailAutoIngestService.processIncomingSummaries(listOf(domSummary))
            assertEquals(1, mailUpdates.size)
            assertEquals("2028-05-01", harness.fleetRepository.assetsFlow.value.first { it.assetType == FleetAssetType.DOMAIN_CERT }.expiryDateIso)

            // 3. Portfolio combined "养鸡+养米" cost & cold-load Widget snapshot
            val summary = FleetPortfolioCalculator.summarize(
                assets = harness.fleetRepository.assetsFlow.value,
                snapshots = emptyList(),
                fxRatesState = null,
                today = harness.clock.todayShanghai(),
            )
            assertEquals(1, summary.vpsCount)
            assertEquals(1, summary.domainCount)
            assertTrue(summary.totalAnnualizedCostCny > BigDecimal("100.00"))

            val isOnline = AtomicBoolean(true)
            val onlineTemplate = probeFixture.getValue("nezhaOnlineServerPayload").toString()
            val offlineTemplate = probeFixture.getValue("nezhaOfflineHighTrafficServerPayload").toString()
            harness.externalDispatcher.register("GET", "/api/v1/server") {
                val payload = if (isOnline.get()) {
                    onlineTemplate.replace("__ONLINE_ISO__", Instant.now().toString())
                } else {
                    offlineTemplate.replace("__OFFLINE_ISO__", Instant.now().minusSeconds(1_800L).toString())
                }
                MockResponse().setResponseCode(200).setBody(payload)
            }
            harness.fleetRepository.refreshAllPanels()

            val coldFleet = FleetRepositoryImpl(
                storeFile = harness.fleetStoreFile,
                tokenCipher = harness.fleetCipher,
                nowMillisProvider = harness.clock::nowMillis,
            )
            coldFleet.loadPersistedState()
            val widgetRepo = WidgetSnapshotRepositoryImpl(
                fleetRepository = coldFleet,
                sessionRepository = harness.sessionRepository,
                checkInRepository = harness.checkInRepository,
                settingsRepository = harness.settingsRepository,
                radarRepository = harness.radarRepository,
                seekMailRepository = harness.seekMailRepository,
                todayProvider = harness.clock::todayShanghai,
            )
            val widgetSnap = widgetRepo.loadServerStatusSnapshot()
            assertEquals(1, widgetSnap.onlineProbeCount)
            assertEquals("SG-Pro", widgetSnap.nearestVpsName)
            assertNotNull(widgetSnap.primaryServerName)

            // 4. Telegram + Bark + Webhook RFC 7230 alert forwarding via FleetMonitorRunner
            val botToken = "848484:S4Token"
            harness.externalDispatcher.register("POST", "/bot$botToken/sendMessage") {
                MockResponse().setResponseCode(200).setBody(responses.getValue("telegramSuccess").toString())
            }
            harness.externalDispatcher.register("POST", "/push") {
                MockResponse().setResponseCode(200).setBody(responses.getValue("barkSuccess").toString())
            }
            harness.externalDispatcher.register("POST", "/hooks/anode") {
                MockResponse().setResponseCode(200).setBody(responses.getValue("webhookSuccess").toString())
            }
            val (_, forwarder) = harness.createTelegramRoutingAlertForwarder()
            forwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = AlertEventCategory.entries.toSet(),
                    telegram = TelegramForwardConfig(enabled = true, botToken = botToken, chatId = "4242"),
                    bark = BarkForwardConfig(enabled = true, serverUrl = harness.externalServer.url("/").toString().trimEnd('/'), deviceKey = "dk_s4"),
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = harness.externalServer.url("/hooks/anode").toString(),
                        secretValue = "s4_hmac_secret",
                        customHeaders = mapOf("X-S4-Valid" to "yes"),
                    ),
                )
            }
            isOnline.set(false)
            val alertSink = E2eFleetAlertSink()
            harness.createFleetMonitorRunner(alertSink = alertSink, forwarder = forwarder).runOnce()
            assertEquals(1, alertSink.offlineAlerts.size)
            assertEquals(1, alertSink.trafficAlerts.size)
            val extReqs = harness.externalDispatcher.recordedRequests()
            assertTrue(extReqs.any { it.path == "/bot$botToken/sendMessage" })
            assertTrue(extReqs.any { it.path == "/push" })
            val hookReqs = extReqs.filter { it.path == "/hooks/anode" }
            assertTrue(hookReqs.isNotEmpty())
            assertTrue(hookReqs.all { it.getHeader("X-Anode-Signature")?.startsWith("sha256=") == true && it.getHeader("X-S4-Valid") == "yes" })
            harness.assertNoCookiesLeakedToExternalServer()
            vmJob.cancel()
        }
    }

    @Test
    fun `E2E-T4-S5 carpool split refund calculation seat table publish full-car update and closure privacy scrub`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            // 1. Split & pro-rata refund
            val calcOutcome = CarpoolCalculator.calculate(
                CarpoolCalculationInput(
                    serviceName = "Netflix 4K",
                    totalSeats = 4,
                    occupiedSeats = 3,
                    totalCycleCost = BigDecimal("240.00"),
                    currency = ValuationCurrency.CNY,
                    billingCycle = CarpoolBillingCycle.QUARTERLY,
                    seatSplitMode = CarpoolSeatSplitMode.EQUAL_ALL_SEATS,
                    jumpPenaltyPercent = BigDecimal("10"),
                ),
            )
            assertTrue(calcOutcome is CarpoolCalculationOutcome.Success)
            val calcResult = (calcOutcome as CarpoolCalculationOutcome.Success).result
            assertEquals(BigDecimal("60.00"), calcResult.perPersonQuarterlyCny)
            assertEquals(BigDecimal("20.00"), calcResult.perPersonMonthlyCny)
            assertEquals(BigDecimal("0.67"), calcResult.perPersonDailyCny)
            assertEquals("3=1", calcResult.statusTag)

            val refund = CarpoolCalculator.calculateProRataRefund(
                paidAmountCny = BigDecimal("60.00"),
                totalDays = 90,
                usedDays = 30,
                penaltyPercent = BigDecimal("10"),
            )
            assertEquals(60, refund.remainingDays)
            assertEquals(BigDecimal("40.00"), refund.crashRefundCny)
            assertEquals(BigDecimal("36.00"), refund.jumpRefundCny)

            // 2. Generate seat table + append contact/IP -> publish via TopicComposerViewModel("carpool")
            val seats = listOf(
                CarpoolSeatSlot(1, "车主", CarpoolSeatStatus.OCCUPIED),
                CarpoolSeatSlot(2, "bob202", CarpoolSeatStatus.OCCUPIED),
                CarpoolSeatSlot(3, "carol203", CarpoolSeatStatus.RESERVED),
                CarpoolSeatSlot(4, "待定", CarpoolSeatStatus.OPEN),
            )
            val carpoolMd = CarpoolMarkdownGenerator.generateMarkdown(calcResult, seats) +
                "\n\n上车联系：节点 IP 198.51.100.42 邮箱 owner@example.com TG: @alice_carpool"
            val initialTitle = CarpoolStatusUpdater.updateTitle("Netflix 4K 土区季付", "3=1")

            val serverTitle = AtomicReference(initialTitle)
            val serverBody = AtomicReference(carpoolMd)
            val editBodies = CopyOnWriteArrayList<String>()

            harness.forumDispatcher.register("POST", "/api/content/new-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.readUtf8()).jsonObject
                serverTitle.set(obj.getValue("title").jsonPrimitive.content)
                serverBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":601}""")
            }
            harness.forumDispatcher.register("GET", "/post-601-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildThreadHtml(601L, "carpool", "拼车", serverTitle.get(), serverBody.get(), rank = 3),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val raw = req.body.readUtf8()
                editBodies.add(raw)
                val obj = TempScriptParser.json.parseToJsonElement(raw).jsonObject
                serverTitle.set(obj.getValue("title").jsonPrimitive.content)
                serverBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val composer = harness.createTopicComposerViewModel(
                initialCategorySlug = "carpool",
                initialTitle = initialTitle,
                initialMarkdown = carpoolMd,
            )
            val cEvents = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val cJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val eJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.events.collect { cEvents.add(it) } }
            composer.onPublishClicked()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { while (cEvents.none { it is TopicComposerUiEvent.PublishedSuccess }) delay(15L) }
            }

            // 3. Update 3=1 -> 已满车 via TopicDetailViewModel + checklist + TradePrivacySanitizer + editTopicId save
            val detailVm = harness.createTopicDetailViewModel(topicId = 601L)
            val dJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.pageData != null } }
            }

            detailVm.updateCarpoolStatus("已满车", occupiedSeats = 4, totalSeats = 4, updateBody = true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { while (editBodies.isEmpty()) delay(15L) }
            }
            val firstEditJson = TempScriptParser.json.parseToJsonElement(editBodies.first()).jsonObject
            assertFalse(firstEditJson.containsKey("category"))
            assertEquals(3, firstEditJson.getValue("rank").jsonPrimitive.int)
            assertTrue(serverTitle.get().contains("[已满车]"))
            assertTrue(serverBody.get().contains("已满车"))

            val deliveryMethod = TradeDeliveryChecklistEngine.inferDeliveryMethod(serverTitle.get(), serverBody.get())
            val handoverNote = TradeDeliveryChecklistEngine.buildHandoverMessage(
                method = deliveryMethod,
                topicTitle = serverTitle.get(),
            )
            val sanitized = TradePrivacySanitizer.sanitize(
                markdown = "${serverBody.get()}\n\n$handoverNote\n\n已核对 4 人全部到位",
                options = TradePrivacyOptions(),
            )
            assertFalse(sanitized.sanitizedMarkdown.contains("198.51.100.42"))
            assertFalse(sanitized.sanitizedMarkdown.contains("owner@example.com"))
            assertFalse(sanitized.sanitizedMarkdown.contains("@alice_carpool"))
            assertTrue(sanitized.sanitizedMarkdown.contains("| #1 |"))

            val editComposer = harness.createTopicComposerViewModel(editTopicId = 601L)
            val ecEvents = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val ecJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { editComposer.uiState.collect {} }
            val eeJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { editComposer.events.collect { ecEvents.add(it) } }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { editComposer.uiState.first { !it.isLoadingInitialTopic } }
            }
            editComposer.onMarkdownChanged(sanitized.sanitizedMarkdown)
            editComposer.onPublishClicked()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { while (ecEvents.none { it is TopicComposerUiEvent.PublishedSuccess }) delay(15L) }
            }
            assertEquals(2, editBodies.size)
            assertFalse(serverBody.get().contains("198.51.100.42"))
            assertTrue(serverBody.get().contains("已核对 4 人全部到位"))
            harness.assertNoCookiesLeakedToExternalServer()

            cJob.cancel()
            eJob.cancel()
            dJob.cancel()
            ecJob.cancel()
            eeJob.cancel()
        }
    }

    @Test
    fun `E2E-T4-S6 shortcut intent Seek Mail 3-min OTP watcher notification widget sync and offline fallback`() = runBlocking {
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "6".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey } }
            }

            // 1. Shortcut intent routing
            val copyCmd = ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_SHORTCUT_COPY_OTP)
            assertEquals(ShortcutCommand.CopyLatestOtp, copyCmd)
            val openRoute = ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_WIDGET_OPEN_SEEK_MAIL)
            assertEquals(ShortcutCommand.Navigate(SeekMailRoute(initialMailboxId = 0L, initialMailId = 0L)), openRoute)

            // 2. 3-Min OTP Watcher via SeekMailViewModel
            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val mailboxesJson = fixture.getValue("mailboxes").toString()
            val baselineJson = fixture.getValue("baselineMailsPage").toString()
            val nonOtpJson = fixture.getValue("nonOtpMailsPage").toString()
            val newOtpPageJson = fixture.getValue("newOtpSummaryOnlyPage").toString()
            val nonOtpDetailJson = fixture.getValue("nonOtpDetail301").toString()
            val otpDetailJson = fixture.getValue("otpDetailNew").toString()

            val watchActive = AtomicBoolean(false)
            val pollIdx = AtomicInteger(0)
            harness.externalDispatcher.register("GET", "/openapi/v1/mailboxes") { MockResponse().setResponseCode(200).setBody(mailboxesJson) }
            harness.externalDispatcher.register("GET", "/openapi/v1/tags") { MockResponse().setResponseCode(200).setBody("""{"data":[]}""") }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                val body = if (!watchActive.get()) {
                    baselineJson
                } else {
                    when (pollIdx.getAndIncrement()) {
                        0 -> baselineJson
                        1 -> nonOtpJson
                        else -> newOtpPageJson
                    }
                }
                MockResponse().setResponseCode(200).setBody(body)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/301") { MockResponse().setResponseCode(200).setBody(nonOtpDetailJson) }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/302") { MockResponse().setResponseCode(200).setBody(otpDetailJson) }

            val gate = CompletableDeferred<Unit>()
            val watcher = harness.createSeekMailOtpWatcher(
                delayFn = { ms ->
                    harness.clock.advanceByMillis(ms)
                    kotlinx.coroutines.yield()
                    if (!gate.isCompleted) gate.await()
                },
            )
            val container = harness.buildAppContainer(overrides = mapOf("getSeekMailOtpWatcher" to watcher))
            val vm = harness.createSeekMailViewModel(initialMailboxId = 5L, container = container)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.hasConfiguredKey && !it.isLoadingMails && !it.isLoadingMailboxes } }
            }

            watchActive.set(true)
            vm.startOtpWatch()
            val watchingState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.otpWatchState is OtpWatchState.Watching } }.otpWatchState as OtpWatchState.Watching
            }
            assertEquals("03:00", OtpWatchNotificationHelper.formatRemainingCountdown(watchingState.remainingSeconds))

            gate.complete(Unit)
            val found = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.otpWatchState is OtpWatchState.Found } }.otpWatchState as OtpWatchState.Found
            }
            assertEquals("839201", found.code)
            assertEquals("839201", harness.otpWatchNotificationSink.foundNotifications.single().code)
            assertEquals("一键复制 839201", OtpWatchNotificationHelper.formatCopyActionLabel(found.code))

            // 3. Widget snapshot sync, HTTP 500 cached fallback, and local delete
            assertEquals("839201", harness.widgetSnapshotRepository.loadDailyPulseSnapshot().latestOtpCode)
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") { MockResponse().setResponseCode(500) }
            val fallbackOtp = harness.widgetSnapshotRepository.fetchOrCachedLatestOtp()
            assertEquals("839201", fallbackOtp?.code)

            harness.seekMailRepository.deleteMailLocally(302L)
            val afterDeleteOtp = harness.seekMailRepository.getCachedLatestOtp()
            assertTrue(afterDeleteOtp == null || afterDeleteOtp.mailId != 302L)
            assertTrue(harness.externalDispatcher.recordedRequests().all { it.getHeader("Authorization") == "Bearer $apiKey" })
            harness.assertNoCookiesLeakedToExternalServer()
            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T4-S7 MTR screenshot mosaic redaction NodeImage upload gallery deletion and magazine long-image QR share`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            harness.registerNetBenchGeoIpRoutes(clientEgressIp = "114.240.88.123")

            // 1. Run forward MTR + reverse trace
            val sshTarget = harness.sshVaultRepository.saveServer(
                SshServerDraft(alias = "Tokyo", host = "154.17.12.88", port = 22, username = "root", authMethod = SshAuthMethod.PASSWORD, secret = "pw"),
            )
            val sshTransport = E2eSshTransport(
                commandHandler = { _, _ ->
                    listOf(
                        SshStreamEvent.Connected,
                        SshStreamEvent.OutputChunk(E2eFixtures.loadMtrSection("REVERSE_NEXTTRACE_CN2_GIA") + "\n"),
                        SshStreamEvent.Completed(0),
                    )
                },
            )
            val sshMgr = harness.createSshSessionManager(transport = sshTransport)
            val kernelOps = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 10.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "154.17.12.88", seq, 130.0)
                    }
                },
            )
            val netBench = harness.createNetBenchController(
                networkBridge = E2eNetBenchNetworkBridge(),
                tracerouteKernelOps = kernelOps,
                sshSessionManager = sshMgr,
            )
            netBench.updateTargetInput("154.17.12.88")
            netBench.updateTracerouteProbesPerHop(5)
            netBench.runForwardTracerouteOnly()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { netBench.uiState.first { !it.isRunning && it.activePhase == NetBenchPhase.COMPLETED } }
            }
            netBench.runReverseTraceroute(sshTarget)
            val nbState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { netBench.uiState.first { !it.isRunningReverseTrace && it.reverseTracerouteResult != null } }
            }
            assertTrue(BackboneRouteTag.CN2_GIA in nbState.tracerouteProgress!!.summary.dominantBackboneTags)
            assertTrue(nbState.generatedMarkdown.contains("CN2 GIA"))

            // 2. Mosaic redact PNG -> Composer upload -> Gallery list & delete -> Publish post 907
            val validKey = "0123456789abcdef0123456789abcdef"
            harness.settingsRepository.setNodeImageApiKey(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.settingsRepository.settings.first { it.nodeImageApiKey == validKey } }
            }
            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            harness.externalDispatcher.register("POST", "/api/upload") {
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("uploadSuccess").toString())
            }
            harness.externalDispatcher.register("GET", "/api/v1/images") {
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("imagesList").toString())
            }
            harness.externalDispatcher.register("DELETE", "/api/v1/delete/img_e2e_02") {
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("deleteSuccess").toString())
            }

            val rawPixels = IntArray(32 * 32) { i -> (0xFF shl 24) or ((i * 5) and 0xFFFFFF) }
            val redactedPixels = BitmapMosaicProcessor.applyMosaicEdits(
                rawPixels,
                32,
                32,
                listOf(MosaicEdit.BoxRegion(MosaicRect(0f, 0f, 0.5f, 0.5f), blockSizeNorm = 0.25f)),
            )
            assertFalse(rawPixels.contentEquals(redactedPixels))
            val pngBytes = E2eBitmapAndTlsFixtures.solidRgbaPngBytes(32, 32, redactedPixels[0])
            assertTrue(ImagePrivacyBitmapCodec.inspectMosaicSupport(pngBytes, "image/png", "mtr.png") is MosaicSupportStatus.SupportedStatic)

            val publishedMd = AtomicReference("")
            harness.forumDispatcher.register("POST", "/api/content/new-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.readUtf8()).jsonObject
                publishedMd.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":907}""")
            }

            val composer = harness.createTopicComposerViewModel(initialCategorySlug = "review")
            val cEvents = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val cJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val eJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.events.collect { cEvents.add(it) } }
            composer.onPhotoBytesSelected(pngBytes, "image/png", "mtr.png")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { composer.uiState.first { it.showPrivacyEditorSheet } }
            }
            composer.confirmPrivacyImageUpload(pngBytes, "image/png", "mtr.png")
            val imgSnippet = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (cEvents.none { it is TopicComposerUiEvent.InsertUploadedMarkdown }) delay(15L)
                    cEvents.filterIsInstance<TopicComposerUiEvent.InsertUploadedMarkdown>().single().snippet
                }
            }
            composer.onTitleChanged("S7 隐私打码与长图分享")
            composer.onMarkdownChanged("${imgSnippet.trim()}\n\n```ansi\n\u001B[32mCN2 GIA 130ms\u001B[0m\n```\n:ac01:")
            composer.onPublishClicked()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { while (cEvents.none { it is TopicComposerUiEvent.PublishedSuccess }) delay(15L) }
            }

            val gallery = harness.imageHostRepository.listImages().getOrThrow()
            assertEquals(2, gallery.items.size)
            assertTrue(harness.imageHostRepository.deleteImage("img_e2e_02").isSuccess)

            // 3. Long-image share on post 907
            harness.forumDispatcher.register("GET", "/post-907-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildThreadHtml(907L, "review", "测评", "S7 隐私打码与长图分享", publishedMd.get()),
                )
            }
            val detailVm = harness.createTopicDetailViewModel(topicId = 907L)
            val dJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.pageData != null } }
            }
            val opening = detailVm.resolveOpeningFloorForShare()!!
            val longReq = TopicLongImageRequest(
                topicId = 907L,
                title = "S7 隐私打码与长图分享",
                categorySlug = "review",
                categoryName = "测评",
                targetFloor = opening,
                topicUrl = "https://www.nodeseek.com/post-907-1",
                themeStyle = LongImageThemeStyle.SLATE_STEEL_DARK,
            )
            val assetUrls = TopicLongImageAssetFetcher.collectRequiredAssetUrls(longReq)
            assertTrue(assetUrls.contains("https://img.nodeimage.com/i/img_e2e_01.png"))
            val qr = TopicLongImageQrGenerator.generateQrMatrix(longReq.topicUrl)
            assertTrue(qr.width >= 21 && qr.darkModuleCount() > 50)
            val renderedTexts = TopicLongImageRenderer.extractRenderedTexts(longReq).joinToString("\n")
            assertTrue(renderedTexts.contains("S7 隐私打码与长图分享"))
            assertTrue(renderedTexts.contains("CN2 GIA 130ms"))
            assertTrue(renderedTexts.contains(":ac01:"))
            assertTrue(renderedTexts.contains("https://www.nodeseek.com/post-907-1"))
            harness.assertNoCookiesLeakedToExternalServer()

            cJob.cancel()
            eJob.cancel()
            dJob.cancel()
        }
    }

    @Test
    fun `E2E-T4-S8 multi-account switch isolation A to B zero cross-contamination and switch back B to A full restore`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            val home101 = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            val home202 = E2eFixtures.loadText("nodeseek/home_logged_in_uid202.html")
            val homepageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/homepage_categories.json"),
            ).jsonObject
            val hp101 = homepageComposite.getValue("uid101").toString()
            val hp202 = homepageComposite.getValue("uid202").toString()
            val luckyHtml = E2eFixtures.loadText("nodeseek/topic_lucky_draw_456.html")
            val floorDataJson = E2eFixtures.loadText("drand/floor_data_456.json")
            val drandJson = E2eFixtures.loadText("drand/drand_round_4500000.json")
                .replace("\"round\": 4500000", "\"round\": 5618965")

            harness.forumDispatcher.register("GET", "/") { req ->
                when (extractCookieValue(req.getHeader("Cookie"), "session")) {
                    "sess_bob" -> MockResponse().setResponseCode(200).setBody(home202)
                    else -> MockResponse().setResponseCode(200).setBody(home101)
                }
            }
            harness.forumDispatcher.register("GET", "/api/homepage") { req ->
                when (extractCookieValue(req.getHeader("Cookie"), "session")) {
                    "sess_bob" -> MockResponse().setResponseCode(200).setBody(hp202)
                    else -> MockResponse().setResponseCode(200).setBody(hp101)
                }
            }
            harness.forumDispatcher.register("GET", "/api/attendance/board") { req ->
                val body = if (extractCookieValue(req.getHeader("Cookie"), "session") == "sess_bob") {
                    """{"hasCheckedInToday":false,"list":[]}"""
                } else {
                    """{"hasCheckedInToday":true,"todayGain":5,"todayOrder":12,"list":[]}"""
                }
                MockResponse().setResponseCode(200).setBody(body)
            }
            harness.forumDispatcher.register("GET", "/api/attendance/daily") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"data":{"count":0}}""")
            }
            harness.forumDispatcher.register("GET", "/api/notification/unread-count") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"unreadCount":{"all":0}}""")
            }
            harness.forumDispatcher.register("GET", "/api/account/collection/list") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"list":[]}""")
            }
            harness.forumDispatcher.register("GET", "/post-456-1") { req ->
                val isBob = extractCookieValue(req.getHeader("Cookie"), "session") == "sess_bob"
                MockResponse().setResponseCode(200).setBody(
                    buildThreadHtml(
                        456L,
                        "daily",
                        "日常",
                        "S8 多账号抽奖与投票",
                        "[选边站](nsapp://vote?id=42)\n\n[抽奖](https://www.nodeseek.com/lucky?post=456&time=1764000000000&count=2&start=1&duplicate=false&mode=view)",
                        viewerUid = if (isBob) 202L else 101L,
                        viewerName = if (isBob) "bob202" else "alice101",
                    ),
                )
            }
            val voteAJson = """{"success":true,"vote":{"id":42,"title":"选边站","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":1,"text":"A","count":0,"voted":0,"voters":[]},{"vote_item_id":2,"text":"B","count":0,"voted":0,"voters":[]}]}}"""
            val voteAVotedJson = """{"success":true,"vote":{"id":42,"title":"选边站","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":1,"text":"A","count":1,"voted":1,"voters":[101]},{"vote_item_id":2,"text":"B","count":0,"voted":0,"voters":[]}]}}"""
            val voteBJson = """{"success":true,"vote":{"id":42,"title":"选边站","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":1,"text":"A","count":1,"voted":0,"voters":[101]},{"vote_item_id":2,"text":"B","count":0,"voted":0,"voters":[]}]}}"""
            val voteBVotedJson = """{"success":true,"vote":{"id":42,"title":"选边站","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":1,"text":"A","count":1,"voted":0,"voters":[101]},{"vote_item_id":2,"text":"B","count":1,"voted":1,"voters":[202]}]}}"""
            val aVoted = AtomicBoolean(false)
            val bVoted = AtomicBoolean(false)
            harness.forumDispatcher.register("GET", "/api/vote/info/42") { req ->
                val tok = extractCookieValue(req.getHeader("Cookie"), "session")
                val body = if (tok == "sess_bob") {
                    if (bVoted.get()) voteBVotedJson else voteBJson
                } else {
                    if (aVoted.get()) voteAVotedJson else voteAJson
                }
                MockResponse().setResponseCode(200).setBody(body)
            }
            harness.forumDispatcher.register("POST", "/api/vote/voteforitem") { req ->
                if (extractCookieValue(req.getHeader("Cookie"), "session") == "sess_bob") bVoted.set(true) else aVoted.set(true)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
            harness.forumDispatcher.register("GET", "/api/content/floor-data") { MockResponse().setResponseCode(200).setBody(floorDataJson) }
            harness.externalDispatcher.register("GET", "/${DrandLuckyEngine.DRAND_CHAIN_HASH}/public/5618965") {
                MockResponse().setResponseCode(200).setBody(drandJson)
            }

            // 1. Seed A (101L) active & B (202L) ready
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", sessionCookie = "sess_alice")
            harness.sessionRepository.refreshSession().getOrThrow()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.categoryDao.observeCategories(101L).first { it.isNotEmpty() }
                }
            }
            harness.forumRepository.syncHomepageCategories().getOrThrow()
            harness.multiAccountManager.initializeAndSyncActiveAccount()
            harness.accountCookieVault.saveOrUpdateSlot(
                session = AccountSession(uid = 202L, username = "bob202"),
                cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
                status = AccountSlotStatus.READY,
            )

            val exploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = harness.forumRepository,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = harness.settingsRepository,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val exploreJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { exploreVm.uiState.collect {} }
            val hubVm = harness.createPulseHubViewModel()
            val hubJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { hubVm.uiState.collect {} }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        val slugs = state.orderedBoardTabs.map { t -> t.slug }
                        !state.isSyncingCategories && "trade" in slugs && "daily" !in slugs
                    }
                }
            }

            // Save A's Draft, Bookmark, ReadHistory, CSRF token, CheckIn
            harness.draftRepository.saveDraft(EditorDraft(EditorDraft.NEW_TOPIC_KEY, 101L, "Alice Draft", "Body A", "tech", harness.clock.nowMillis()))
            harness.bookmarkDao.upsert(BookmarkEntity(101L, 701L, "Alice BM", "tech", "技术", 101L, "alice101", "", 4, 1, 0, "", 0, false, null, true, harness.clock.nowMillis()))
            harness.readHistoryDao.upsert(ReadHistoryEntity(101L, 702L, "Alice Hist", "tech", "技术", "alice101", "", 1, 5, harness.clock.nowMillis()))
            harness.csrfTokenStore.seedCsrfToken("alice_csrf")
            harness.checkInRepository.getCheckInBoard(page = 1, forceRefresh = true)

            val detailVmA = harness.createTopicDetailViewModel(topicId = 456L)
            val dJobA = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVmA.uiState.collect {} }
            withContext(Dispatchers.Default) { withTimeout(5_000L) { detailVmA.uiState.first { it.pageData != null } } }
            detailVmA.nsAppWidgetController.ensureVoteLoaded(42L)
            withContext(Dispatchers.Default) { withTimeout(5_000L) { detailVmA.nsAppWidgetController.pollStateFor(42L).first { it.poll != null } } }
            detailVmA.nsAppWidgetController.togglePollOption(42L, 1L, isMultiple = false)
            detailVmA.nsAppWidgetController.submitVote(42L)
            withContext(Dispatchers.Default) { withTimeout(5_000L) { detailVmA.nsAppWidgetController.pollStateFor(42L).first { it.poll?.hasCurrentUserVoted == true } } }

            val luckySpec = DrandLuckyEngine.extractFirstLuckySpec(
                NodeSeekHtmlParser.parseThreadPage(luckyHtml, "https://www.nodeseek.com/post-456-1").openingComment!!.markdown,
                fallbackPostId = 456L,
            )!!
            assertEquals(
                LuckyViewerStatus.PARTICIPATED_NOT_WON,
                harness.luckyDrawRepository.resolveLuckyDraw(luckySpec, viewerUid = 101L, nowEpochMillis = 1_764_000_010_000L).getOrThrow().viewerStatus,
            )

            // 2. Switch A -> B via PulseHubViewModel
            hubVm.switchAccount(202L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { hubVm.uiState.first { !it.isSwitchingAccount && it.session?.uid == 202L } }
            }
            assertNull(harness.csrfTokenStore.csrfToken)
            assertTrue(harness.checkInRepository.checkInStatus.value?.hasCheckedInToday != true)
            assertNull(harness.draftDao.getDraft(202L, EditorDraft.NEW_TOPIC_KEY))
            assertTrue(harness.bookmarkDao.getBookmarkedTopicIds(202L).isEmpty())
            assertFalse(harness.forumDispatcher.recordedRequests().any { it.path?.contains("signOut") == true })

            val stateB = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        val slugs = state.orderedBoardTabs.map { it.slug }
                        !state.isSyncingCategories && state.session?.uid == 202L && "daily" in slugs && "trade" !in slugs
                    }
                }
            }
            assertEquals(listOf("", "carpool", "info", "daily"), stateB.orderedBoardTabs.take(4).map { it.slug })

            // B votes and checks /lucky -> MY_WIN
            val detailVmB = harness.createTopicDetailViewModel(topicId = 456L)
            val dJobB = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVmB.uiState.collect {} }
            withContext(Dispatchers.Default) { withTimeout(5_000L) { detailVmB.uiState.first { it.pageData != null } } }
            detailVmB.nsAppWidgetController.ensureVoteLoaded(42L)
            withContext(Dispatchers.Default) { withTimeout(5_000L) { detailVmB.nsAppWidgetController.pollStateFor(42L).first { it.poll != null } } }
            detailVmB.nsAppWidgetController.togglePollOption(42L, 2L, isMultiple = false)
            detailVmB.nsAppWidgetController.submitVote(42L)
            withContext(Dispatchers.Default) { withTimeout(5_000L) { detailVmB.nsAppWidgetController.pollStateFor(42L).first { it.poll?.hasCurrentUserVoted == true } } }
            assertEquals(
                LuckyViewerStatus.MY_WIN,
                harness.luckyDrawRepository.resolveLuckyDraw(luckySpec, viewerUid = 202L, nowEpochMillis = 1_764_000_010_000L).getOrThrow().viewerStatus,
            )

            // 3. Switch back B -> A -> full restore of A's draft, bookmark, history, homepage, and /lucky status
            hubVm.switchAccount(101L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { hubVm.uiState.first { !it.isSwitchingAccount && it.session?.uid == 101L } }
            }
            assertEquals("Alice Draft", harness.draftRepository.getDraft(101L, EditorDraft.NEW_TOPIC_KEY)?.title)
            assertEquals(listOf(701L), harness.bookmarkDao.getBookmarkedTopicIds(101L))
            assertEquals(702L, harness.readHistoryDao.getByTopicId(101L, 702L)?.topicId)
            assertEquals(AccountSlotStatus.ACTIVE, harness.accountCookieVault.getSlot(101L)?.status)
            assertEquals(AccountSlotStatus.READY, harness.accountCookieVault.getSlot(202L)?.status)
            val stateA2 = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        val slugs = state.orderedBoardTabs.map { it.slug }
                        !state.isSyncingCategories && state.session?.uid == 101L && "trade" in slugs && "daily" !in slugs
                    }
                }
            }
            assertEquals(listOf("", "tech", "trade", "review"), stateA2.orderedBoardTabs.take(4).map { it.slug })
            assertEquals(
                LuckyViewerStatus.PARTICIPATED_NOT_WON,
                harness.luckyDrawRepository.resolveLuckyDraw(luckySpec, viewerUid = 101L, nowEpochMillis = 1_764_000_010_000L).getOrThrow().viewerStatus,
            )
            harness.assertNoCookiesLeakedToExternalServer()

            exploreJob.cancel()
            hubJob.cancel()
            dJobA.cancel()
            dJobB.cancel()
        }
    }
}
