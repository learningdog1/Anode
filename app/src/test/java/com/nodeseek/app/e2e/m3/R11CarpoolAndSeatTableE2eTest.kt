package com.nodeseek.app.e2e.m3

import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.carpool.CarpoolBillingCycle
import com.nodeseek.app.domain.carpool.CarpoolCalculationInput
import com.nodeseek.app.domain.carpool.CarpoolCalculationOutcome
import com.nodeseek.app.domain.carpool.CarpoolCalculator
import com.nodeseek.app.domain.carpool.CarpoolMarkdownGenerator
import com.nodeseek.app.domain.carpool.CarpoolSeatSlot
import com.nodeseek.app.domain.carpool.CarpoolSeatSplitMode
import com.nodeseek.app.domain.carpool.CarpoolSeatStatus
import com.nodeseek.app.domain.carpool.CarpoolStatusUpdater
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.ui.composer.TopicComposerUiEvent
import com.nodeseek.app.ui.topic.TopicDetailUiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class R11CarpoolAndSeatTableE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private fun buildCarpoolThreadHtml(
        postId: Long = 601L,
        title: String = "[3=1] Netflix 4K 土区合租 季付",
        markdown: String = "### 🚗 拼车信息",
        rank: Int = 3,
        opUid: Long = 101L,
        opName: String = "alice101",
        viewerUid: Long? = 101L,
        viewerName: String = "alice101",
        page: Int = 1,
        pageCount: Int = 1,
    ): String {
        val baseHtml = E2eFixtures.loadText("nodeseek/topic_carpool_op_601.html")
        val encodedMatch = Regex("""<script id="temp-script"[^>]*>(.*?)</script>""").find(baseHtml)
            ?: error("Missing temp-script in topic_carpool_op_601.html")
        val decodedJson = Base64.getDecoder().decode(encodedMatch.groupValues[1]).toString(Charsets.UTF_8)
        val rootObj = TempScriptParser.json.parseToJsonElement(decodedJson).jsonObject
        val catsJson = rootObj.getValue("allCategory").toString()
        val userJson = if (viewerUid != null) {
            """{"member_id":$viewerUid,"member_name":${JsonPrimitive(viewerName)},"coin":1650,"stardust":42,"rank":4}"""
        } else {
            "null"
        }
        val commentsJson = if (page == 1) {
            """[{"commentId":60100,"floorIndex":0,"poster":{"uid":$opUid,"name":${JsonPrimitive(opName)},"avatar":"/avatar/$opUid.png","isOp":1,"roles":[]},"time":{"createdDate":"2026-11-24T15:00:00Z","createdDateFormated":"2026-11-24 23:00:00"},"markdown":${JsonPrimitive(markdown)},"likeCount":4,"dislikeCount":0,"upvoteCount":4,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]"""
        } else {
            """[{"commentId":60125,"floorIndex":25,"poster":{"uid":202,"name":"bob202","avatar":"/avatar/202.png","isOp":0,"roles":[]},"time":{"createdDate":"2026-11-24T16:00:00Z","createdDateFormated":"2026-11-25 00:00:00"},"markdown":"第 $page 页排队回复","likeCount":0,"dislikeCount":0,"upvoteCount":0,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]"""
        }
        val cfg = """{"user":$userJson,"enableCustomedStyle":1,"allCategory":$catsJson,"postData":{"postId":$postId,"postPage":$page,"postPageCount":$pageCount,"title":${JsonPrimitive(title)},"category":"carpool","categoryWord":"拼车","categoryLink":"/categories/carpool","views":310,"collectionCount":3,"rank":$rank,"locked":0,"collected":0,"award":0,"op":{"uid":$opUid,"name":${JsonPrimitive(opName)},"avatar":"/avatar/$opUid.png"},"comments":$commentsJson}}"""
        return E2eFixtures.wrapTempScriptHtml(
            configJson = cfg,
            bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>$title</h1><article class="post-content"></article></div></div></div>""",
            title = title,
        )
    }

    @Test
    fun `E2E-T1-R11-1 calculates per-person quarterly monthly yearly and daily split across CNY USD and DRIVER_EXCLUDED`() {
        val caseA = CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = "Netflix 4K",
                totalSeats = 4,
                occupiedSeats = 3,
                totalCycleCost = BigDecimal("240.00"),
                currency = ValuationCurrency.CNY,
                billingCycle = CarpoolBillingCycle.QUARTERLY,
                seatSplitMode = CarpoolSeatSplitMode.EQUAL_ALL_SEATS,
            ),
        )
        assertTrue(caseA is CarpoolCalculationOutcome.Success)
        val resA = (caseA as CarpoolCalculationOutcome.Success).result
        assertEquals(BigDecimal("240.00"), resA.totalCycleCostCny)
        assertEquals(BigDecimal("60.00"), resA.perPersonCycleCostCny)
        assertEquals(BigDecimal("20.00"), resA.perPersonMonthlyCny)
        assertEquals(BigDecimal("60.00"), resA.perPersonQuarterlyCny)
        assertEquals(BigDecimal("240.00"), resA.perPersonYearlyCny)
        assertEquals(BigDecimal("0.67"), resA.perPersonDailyCny)
        assertEquals(1, resA.remainingSeats)
        assertEquals("3=1", resA.statusTag)

        val caseB = CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = "ChatGPT Team",
                totalSeats = 4,
                occupiedSeats = 4,
                totalCycleCost = BigDecimal("100.00"),
                currency = ValuationCurrency.USD,
                fxRateToCny = BigDecimal("7.2000"),
                billingCycle = CarpoolBillingCycle.MONTHLY,
                seatSplitMode = CarpoolSeatSplitMode.DRIVER_EXCLUDED,
            ),
        )
        assertTrue(caseB is CarpoolCalculationOutcome.Success)
        val resB = (caseB as CarpoolCalculationOutcome.Success).result
        assertEquals(3, resB.payingSeats)
        assertEquals(BigDecimal("720.00"), resB.totalCycleCostCny)
        assertEquals(BigDecimal("240.00"), resB.perPersonCycleCostCny)
        assertEquals(BigDecimal("720.00"), resB.perPersonQuarterlyCny)
        assertEquals(BigDecimal("2880.00"), resB.perPersonYearlyCny)
        assertEquals("已满车", resB.statusTag)
    }

    @Test
    fun `E2E-T1-R11-2 calculates pro-rata daily crash and jump refund with penalty and formula strings`() {
        val midTerm = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = BigDecimal("120.00"),
            totalDays = 90,
            usedDays = 30,
            penaltyPercent = BigDecimal("10"),
        )
        assertEquals(60, midTerm.remainingDays)
        assertEquals(BigDecimal("80.00"), midTerm.crashRefundCny)
        assertEquals(BigDecimal("72.00"), midTerm.jumpRefundCny)
        assertEquals(BigDecimal("8.00"), midTerm.penaltyDeductedCny)
        assertEquals(BigDecimal("40.00"), midTerm.usedCostCny)
        assertTrue(midTerm.crashFormulaText.contains("60 / 90"))
        assertTrue(midTerm.crashFormulaText.contains("¥80.00"))
        assertTrue(midTerm.jumpFormulaText.contains("90%"))
        assertTrue(midTerm.jumpFormulaText.contains("¥72.00"))

        val dayZero = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = BigDecimal("120.00"),
            totalDays = 90,
            usedDays = 0,
            penaltyPercent = BigDecimal.ZERO,
        )
        assertEquals(90, dayZero.remainingDays)
        assertEquals(BigDecimal("120.00"), dayZero.crashRefundCny)
        assertEquals(BigDecimal("120.00"), dayZero.jumpRefundCny)
        assertEquals(dayZero.crashFormulaText, dayZero.jumpFormulaText)
    }

    @Test
    fun `E2E-T1-R11-3 generates standardized GFM seat table escapes pipe cells and round-trips through Composer and HtmlParser`() = runBlocking {
        val calc = (
            CarpoolCalculator.calculate(
                CarpoolCalculationInput(
                    serviceName = "DMIT | CN2 GIA",
                    totalSeats = 4,
                    occupiedSeats = 3,
                    totalCycleCost = BigDecimal("240.00"),
                    billingCycle = CarpoolBillingCycle.QUARTERLY,
                ),
            ) as CarpoolCalculationOutcome.Success
            ).result

        val slots = listOf(
            CarpoolSeatSlot(1, "车主|OP", CarpoolSeatStatus.OCCUPIED, "发起人\n长住"),
            CarpoolSeatSlot(2, "车友A", CarpoolSeatStatus.OCCUPIED, "已付"),
            CarpoolSeatSlot(3, "车友B", CarpoolSeatStatus.RESERVED, "待确认"),
            CarpoolSeatSlot(4, "空车位", CarpoolSeatStatus.OPEN, "欢迎上车"),
        )
        val generated = CarpoolMarkdownGenerator.generateMarkdown(calc, slots)

        assertTrue(generated.contains("### 🚗 拼车信息 · DMIT | CN2 GIA"))
        assertTrue(generated.contains("**当前状态**：`3=1`（共 4 车位，已上车 3，空余 1）"))
        assertTrue(generated.contains("| 车位 | 成员 / 状态 | 费用 / 备注 |"))
        assertTrue(generated.contains("| :--- | :--- | :--- |"))
        assertTrue(generated.contains("| #1 | 车主\\|OP · ${CarpoolSeatStatus.OCCUPIED.badge} |"))
        assertTrue(generated.contains("| #4 | 空车位 · ${CarpoolSeatStatus.OPEN.badge} |"))
        assertFalse("Generated markdown must not contain CR", generated.contains('\r'))

        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            val composer = harness.createTopicComposerViewModel(initialCategorySlug = "carpool")
            val job = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }

            val updatedTitle = CarpoolStatusUpdater.updateTitle("DMIT CN2 GIA 合租", calc.statusTag)
            val composedBody = "长期稳定车：\n\n$generated"
            composer.onTitleChanged(updatedTitle)
            composer.onMarkdownChanged(composedBody)

            val state = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    composer.uiState.first {
                        it.title == "[3=1] DMIT CN2 GIA 合租" && it.markdownContent == composedBody
                    }
                }
            }
            assertEquals("carpool", state.selectedCategorySlug)

            // Verify lossless HTML thread round-trip through public NodeSeekHtmlParser.parseThreadPage
            val threadHtml = buildCarpoolThreadHtml(
                postId = 602L,
                title = state.title,
                markdown = state.markdownContent,
            )
            val parsed = NodeSeekHtmlParser.parseThreadPage(threadHtml, "https://www.nodeseek.com/post-602-1")
            assertEquals("[3=1] DMIT CN2 GIA 合租", parsed.postData.title)
            val parsedBody = parsed.openingComment?.markdown.orEmpty()
            assertTrue(parsedBody.contains("| 车位 | 成员 / 状态 | 费用 / 备注 |"))
            assertTrue(parsedBody.contains("| #1 | 车主\\|OP · ${CarpoolSeatStatus.OCCUPIED.badge} |"))
            assertTrue(parsedBody.contains("**当前状态**：`3=1`（共 4 车位，已上车 3，空余 1）"))

            job.cancel()
        }
    }

    @Test
    fun `E2E-T1-R11-4 OP 1-tap updates 3=1 to 已满车 via edit-discussion preserving rank and omitting category`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val initialParsed = NodeSeekHtmlParser.parseThreadPage(
                E2eFixtures.loadText("nodeseek/topic_carpool_op_601.html"),
                "https://www.nodeseek.com/post-601-1",
            )
            val servedTitle = AtomicReference(initialParsed.postData.title)
            val servedBody = AtomicReference(initialParsed.openingComment!!.markdown)

            harness.forumDispatcher.register("GET", "/post-601-1") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        buildCarpoolThreadHtml(
                            postId = 601L,
                            title = servedTitle.get(),
                            markdown = servedBody.get(),
                            rank = 3,
                        ),
                    )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                servedTitle.set(obj.getValue("title").jsonPrimitive.content)
                servedBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 601L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.updateCarpoolStatus(
                statusToken = CarpoolStatusUpdater.FULL_CARPOOL_LABEL,
                occupiedSeats = 4,
                totalSeats = 4,
                updateBody = true,
            )

            val updatedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isMutatingTopic &&
                            it.pageData?.title == "[已满车] Netflix 4K 土区合租 季付" &&
                            it.openingFloor?.markdownBody?.contains("**当前状态**：`已满车`（共 4 车位，已上车 4，空余 0）") == true
                    }
                }
            }

            val editReq = harness.forumDispatcher.recordedRequests()
                .single { it.path == "/api/content/edit-discussion" }
            assertFalse(editReq.getHeader("csrf-token").isNullOrBlank())
            assertTrue(editReq.getHeader("Cookie")?.contains("session=ns_e2e_sess") == true)

            val editJson = TempScriptParser.json.parseToJsonElement(editReq.body.readUtf8()).jsonObject
            assertEquals("edit-discussion", editJson.getValue("mode").jsonPrimitive.content)
            assertEquals(601L, editJson.getValue("postId").jsonPrimitive.long)
            assertEquals("[已满车] Netflix 4K 土区合租 季付", editJson.getValue("title").jsonPrimitive.content)
            assertEquals(3, editJson.getValue("rank").jsonPrimitive.int)
            assertFalse("edit-discussion MUST omit category", editJson.containsKey("category"))

            val sentContent = editJson.getValue("content").jsonPrimitive.content
            assertTrue(sentContent.contains("**当前状态**：`已满车`（共 4 车位，已上车 4，空余 0）"))
            assertTrue(updatedState.openingFloor?.markdownBody?.contains("| #4 | 虚位以待") == true)

            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R11-5 TopicComposerViewModel publishes new carpool topic via new-discussion with category=carpool and seat table`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            harness.forumDispatcher.register("GET", "/api/content/list-categories") {
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"data":[{"key":"daily","cn_text":"日常","customizable":1,"showInIndex":1},{"key":"carpool","cn_text":"拼车","customizable":1,"showInIndex":1}]}""",
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/new-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":602}""")
            }

            val calc = (
                CarpoolCalculator.calculate(
                    CarpoolCalculationInput(
                        serviceName = "ChatGPT Team",
                        totalSeats = 4,
                        occupiedSeats = 3,
                        totalCycleCost = BigDecimal("240.00"),
                        billingCycle = CarpoolBillingCycle.QUARTERLY,
                    ),
                ) as CarpoolCalculationOutcome.Success
                ).result

            val composer = harness.createTopicComposerViewModel(initialCategorySlug = "carpool")
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val events = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val eventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.events.collect { events.add(it) } }

            val title = CarpoolStatusUpdater.updateTitle("ChatGPT Team 拼车", calc.statusTag)
            val body = "长期稳定车，\n\n${CarpoolMarkdownGenerator.generateMarkdown(calc)}"
            composer.onTitleChanged(title)
            composer.onMarkdownChanged(body)

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    composer.uiState.first { it.canSubmit && it.selectedCategorySlug == "carpool" }
                }
            }

            composer.onPublishClicked()

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it is TopicComposerUiEvent.PublishedSuccess && it.createdTopicId == 602L }) {
                        delay(20L)
                    }
                }
            }

            val newReq = harness.forumDispatcher.recordedRequests()
                .single { it.path == "/api/content/new-discussion" }
            assertFalse(newReq.getHeader("csrf-token").isNullOrBlank())
            val jsonObj = TempScriptParser.json.parseToJsonElement(newReq.body.readUtf8()).jsonObject
            assertEquals("new-discussion", jsonObj.getValue("mode").jsonPrimitive.content)
            assertEquals("carpool", jsonObj.getValue("category").jsonPrimitive.content)
            assertEquals("[3=1] ChatGPT Team 拼车", jsonObj.getValue("title").jsonPrimitive.content)
            assertEquals(0, jsonObj.getValue("rank").jsonPrimitive.int)
            assertTrue(jsonObj.getValue("content").jsonPrimitive.content.contains("| 车位 | 成员 / 状态 | 费用 / 备注 |"))

            stateJob.cancel()
            eventJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R11-1 odd-cent division uses BigDecimal HALF_UP with zero floating point drift`() {
        val monthly = (
            CarpoolCalculator.calculate(
                CarpoolCalculationInput(
                    totalSeats = 3,
                    occupiedSeats = 1,
                    totalCycleCost = BigDecimal("100.00"),
                    billingCycle = CarpoolBillingCycle.MONTHLY,
                ),
            ) as CarpoolCalculationOutcome.Success
            ).result
        assertEquals(BigDecimal("33.33"), monthly.perPersonCycleCostCny)
        assertEquals(BigDecimal("33.33"), monthly.perPersonMonthlyCny)

        val quarterly = (
            CarpoolCalculator.calculate(
                CarpoolCalculationInput(
                    totalSeats = 3,
                    occupiedSeats = 1,
                    totalCycleCost = BigDecimal("100.00"),
                    billingCycle = CarpoolBillingCycle.QUARTERLY,
                ),
            ) as CarpoolCalculationOutcome.Success
            ).result
        assertEquals(BigDecimal("33.33"), quarterly.perPersonCycleCostCny)
        assertEquals(BigDecimal("0.37"), quarterly.perPersonDailyCny)
        assertTrue(quarterly.perPersonDailyHighPrecisionCny > BigDecimal("0.3700"))

        val halfUp = (
            CarpoolCalculator.calculate(
                CarpoolCalculationInput(
                    totalSeats = 3,
                    occupiedSeats = 1,
                    totalCycleCost = BigDecimal("200.00"),
                    billingCycle = CarpoolBillingCycle.MONTHLY,
                ),
            ) as CarpoolCalculationOutcome.Success
            ).result
        assertEquals(BigDecimal("66.67"), halfUp.perPersonCycleCostCny)

        val usdSplit = (
            CarpoolCalculator.calculate(
                CarpoolCalculationInput(
                    totalSeats = 3,
                    occupiedSeats = 2,
                    totalCycleCost = BigDecimal("10.00"),
                    currency = ValuationCurrency.USD,
                    fxRateToCny = BigDecimal("7.25"),
                    billingCycle = CarpoolBillingCycle.MONTHLY,
                ),
            ) as CarpoolCalculationOutcome.Success
            ).result
        assertEquals(BigDecimal("72.50"), usdSplit.totalCycleCostCny)
        assertEquals(BigDecimal("24.17"), usdSplit.perPersonCycleCostCny)
    }

    @Test
    fun `E2E-T2-R11-2 invalid carpool inputs return CarpoolCalculationOutcome Invalid with descriptive errors`() {
        val invalidInputs = listOf(
            CarpoolCalculationInput(totalSeats = 0, occupiedSeats = 1, totalCycleCost = BigDecimal("100")),
            CarpoolCalculationInput(totalSeats = 1, occupiedSeats = 1, totalCycleCost = BigDecimal("100")),
            CarpoolCalculationInput(totalSeats = 33, occupiedSeats = 1, totalCycleCost = BigDecimal("100")),
            CarpoolCalculationInput(totalSeats = 4, occupiedSeats = 5, totalCycleCost = BigDecimal("100")),
            CarpoolCalculationInput(totalSeats = 4, occupiedSeats = 0, totalCycleCost = BigDecimal("100")),
            CarpoolCalculationInput(totalSeats = 4, occupiedSeats = 2, totalCycleCost = BigDecimal("-10.00")),
            CarpoolCalculationInput(totalSeats = 4, occupiedSeats = 2, totalCycleCost = BigDecimal.ZERO),
            CarpoolCalculationInput(
                totalSeats = 4,
                occupiedSeats = 2,
                totalCycleCost = BigDecimal("100"),
                currency = ValuationCurrency.USD,
                fxRateToCny = BigDecimal.ZERO,
            ),
            CarpoolCalculationInput(
                totalSeats = 4,
                occupiedSeats = 2,
                totalCycleCost = BigDecimal("100"),
                billingCycle = CarpoolBillingCycle.CUSTOM_DAYS,
                customCycleDays = 0,
            ),
            CarpoolCalculationInput(
                totalSeats = 4,
                occupiedSeats = 2,
                totalCycleCost = BigDecimal("100"),
                jumpPenaltyPercent = BigDecimal("101"),
            ),
            CarpoolCalculationInput(
                totalSeats = 4,
                occupiedSeats = 2,
                totalCycleCost = BigDecimal("100"),
                jumpPenaltyPercent = BigDecimal("-1"),
            ),
        )

        invalidInputs.forEach { input ->
            val outcome = CarpoolCalculator.calculate(input)
            assertTrue("Expected Invalid for $input", outcome is CarpoolCalculationOutcome.Invalid)
            val errors = (outcome as CarpoolCalculationOutcome.Invalid).errors
            assertTrue("Expected non-empty error list for $input", errors.isNotEmpty())
        }
    }

    @Test
    fun `E2E-T2-R11-3 exit on or after cycle expiry clamps refund to 0_00 and handles negative paid safely`() {
        val onExpiry = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = BigDecimal("120.00"),
            totalDays = 90,
            usedDays = 90,
            penaltyPercent = BigDecimal("20"),
        )
        assertEquals(0, onExpiry.remainingDays)
        assertEquals(BigDecimal("0.00"), onExpiry.crashRefundCny)
        assertEquals(BigDecimal("0.00"), onExpiry.jumpRefundCny)
        assertEquals(BigDecimal("0.00"), onExpiry.penaltyDeductedCny)
        assertEquals(BigDecimal("120.00"), onExpiry.usedCostCny)

        val pastExpiry = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = BigDecimal("120.00"),
            totalDays = 90,
            usedDays = 150,
            penaltyPercent = BigDecimal("20"),
        )
        assertEquals(0, pastExpiry.remainingDays)
        assertEquals(BigDecimal("0.00"), pastExpiry.crashRefundCny)
        assertEquals(BigDecimal("0.00"), pastExpiry.jumpRefundCny)

        val negativePaid = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = BigDecimal("-50.00"),
            totalDays = 90,
            usedDays = 10,
            penaltyPercent = BigDecimal("10"),
        )
        assertEquals(BigDecimal("0.00"), negativePaid.crashRefundCny)
        assertEquals(BigDecimal("0.00"), negativePaid.jumpRefundCny)
        assertEquals(BigDecimal("0.00"), negativePaid.usedCostCny)
    }

    @Test
    fun `E2E-T2-R11-4 reopening 已满车 to 3=1 on Page 2+ fetches Page 1 preserves rank and keeps seat table rows intact`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val fullBody = E2eFixtures.loadText("nodeseek/topic_carpool_op_601.html").let { html ->
                val parsed = NodeSeekHtmlParser.parseThreadPage(html, "https://www.nodeseek.com/post-601-1")
                CarpoolStatusUpdater.updateMarkdownStatus(
                    markdown = parsed.openingComment!!.markdown,
                    newStatusToken = CarpoolStatusUpdater.FULL_CARPOOL_LABEL,
                    occupiedSeats = 4,
                    totalSeats = 4,
                )
            }
            val servedTitle = AtomicReference("[已满车] Netflix 4K 土区合租 季付")
            val servedBody = AtomicReference(fullBody)
            val page1Calls = AtomicInteger(0)
            val page1Hits = AtomicInteger(0)

            // First Page 1 prefetch fails once so ensurePageOneForOpEdit() fetches GET /post-601-1 inside updateCarpoolStatus
            harness.forumDispatcher.register("GET", "/post-601-1") {
                if (page1Calls.getAndIncrement() == 0) {
                    MockResponse().setResponseCode(500).setBody("transient")
                } else {
                    page1Hits.incrementAndGet()
                    MockResponse().setResponseCode(200).setBody(
                        buildCarpoolThreadHtml(
                            postId = 601L,
                            title = servedTitle.get(),
                            markdown = servedBody.get(),
                            rank = 5,
                            page = 1,
                            pageCount = 3,
                        ),
                    )
                }
            }
            harness.forumDispatcher.register("GET", "/post-601-3") {
                MockResponse().setResponseCode(200).setBody(
                    buildCarpoolThreadHtml(
                        postId = 601L,
                        title = servedTitle.get(),
                        markdown = servedBody.get(),
                        rank = 0,
                        page = 3,
                        pageCount = 3,
                    ),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                servedTitle.set(obj.getValue("title").jsonPrimitive.content)
                servedBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            // floor 25 -> page 3
            val vm = harness.createTopicDetailViewModel(topicId = 601L, initialFloor = 25)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.pageData != null && !it.isInitialLoading }
                    while (page1Calls.get() < 1 || harness.forumDispatcher.recordedRequests().none { it.path == "/post-601-1" }) {
                        delay(10L)
                    }
                }
            }

            vm.updateCarpoolStatus("3=1", occupiedSeats = 3, totalSeats = 4, updateBody = true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isMutatingTopic &&
                            it.pageData?.title == "[3=1] Netflix 4K 土区合租 季付" &&
                            it.openingFloor?.markdownBody?.contains("**当前状态**：`3=1`（共 4 车位，已上车 3，空余 1）") == true
                    }
                }
            }

            val editReq = harness.forumDispatcher.recordedRequests()
                .single { it.path == "/api/content/edit-discussion" }
            val editJson = TempScriptParser.json.parseToJsonElement(editReq.body.readUtf8()).jsonObject
            assertEquals("[3=1] Netflix 4K 土区合租 季付", editJson.getValue("title").jsonPrimitive.content)
            assertEquals(5, editJson.getValue("rank").jsonPrimitive.int)
            assertFalse(editJson.containsKey("category"))

            val sentBody = editJson.getValue("content").jsonPrimitive.content
            assertTrue(sentBody.contains("**当前状态**：`3=1`（共 4 车位，已上车 3，空余 1）"))
            assertTrue(sentBody.contains("| #1 | 车主 ·"))
            assertTrue(sentBody.contains("| #2 | 车友 #2 ·"))
            assertTrue(sentBody.contains("| #3 | 车友 #3 ·"))
            assertTrue(sentBody.contains("| #4 | 虚位以待 ·"))
            assertTrue(page1Hits.get() >= 1)

            // Idempotency when invoked a second time with same status
            vm.updateCarpoolStatus("3=1", occupiedSeats = 3, totalSeats = 4, updateBody = true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (harness.forumDispatcher.recordedRequests().count { it.path == "/api/content/edit-discussion" } < 2) {
                        delay(20L)
                    }
                }
            }
            val secondBody = TempScriptParser.json.parseToJsonElement(
                harness.forumDispatcher.recordedRequests().last { it.path == "/api/content/edit-discussion" }.body.readUtf8(),
            ).jsonObject.getValue("content").jsonPrimitive.content
            assertEquals(sentBody, secondBody)

            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R11-5 non-OP or signed-out callers are blocked and server error rolls back mutating state cleanly`() = runBlocking {
        // Subcase A1: Signed-out user (guest HTML with `"user": null`)
        E2eTestHarness().use { harness ->
            harness.forumDispatcher.register("GET", "/post-601-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildCarpoolThreadHtml(opUid = 101L, opName = "alice101", viewerUid = null),
                )
            }
            val vm = harness.createTopicDetailViewModel(topicId = 601L)
            val job = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }
            assertFalse(loaded.canEditTopic)
            vm.updateCarpoolStatus("已满车")
            delay(150L)
            assertTrue(harness.forumDispatcher.recordedRequests().none { it.path == "/api/content/edit-discussion" })
            job.cancel()
        }

        // Subcase A2: Signed-in non-OP (uid=202 != op.uid=101)
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 202L, username = "bob202", rank = 3)
            harness.forumDispatcher.register("GET", "/post-601-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildCarpoolThreadHtml(opUid = 101L, opName = "alice101", viewerUid = 202L, viewerName = "bob202"),
                )
            }
            val vm = harness.createTopicDetailViewModel(topicId = 601L)
            val job = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }
            assertFalse(loaded.canEditTopic)
            vm.updateCarpoolStatus("已满车")
            delay(150L)
            assertTrue(harness.forumDispatcher.recordedRequests().none { it.path == "/api/content/edit-discussion" })
            job.cancel()
        }

        // Subcase B: Authenticated OP gets business error {"success":false,"message":"编辑频率过快"} -> rollback
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            val originalTitle = "[3=1] Netflix 4K 土区合租 季付"
            val originalBody = "原始车位正文"
            harness.forumDispatcher.register("GET", "/post-601-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildCarpoolThreadHtml(title = originalTitle, markdown = originalBody),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":false,"message":"编辑频率过快"}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 601L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            val events = CopyOnWriteArrayList<TopicDetailUiEvent>()
            val eventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events.add(it) } }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.updateCarpoolStatus("已满车")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it is TopicDetailUiEvent.ShowMessage && it.message.contains("编辑频率过快") }) {
                        delay(20L)
                    }
                }
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isMutatingTopic } }
            }
            assertFalse(vm.uiState.value.isMutatingTopic)
            assertEquals(originalTitle, vm.uiState.value.pageData?.title)
            assertEquals(originalBody, vm.uiState.value.openingFloor?.markdownBody)

            stateJob.cancel()
            eventJob.cancel()
        }
    }
}
