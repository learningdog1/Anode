package com.nodeseek.app.e2e

import com.nodeseek.app.data.local.BookmarkEntity
import com.nodeseek.app.data.local.CategoryEntity
import com.nodeseek.app.data.local.DraftEntity
import com.nodeseek.app.data.local.ReadHistoryEntity
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.mail.VpsMailAutoIngestServiceImpl
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.network.CloudflareChallengeDetector
import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.computeDynamicSign
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.repository.DetectedImageFormat
import com.nodeseek.app.data.repository.ImageUploadFormatInspector
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.alerts.AlertForwardingConfig
import com.nodeseek.app.domain.alerts.TelegramForwardConfig
import com.nodeseek.app.domain.digest.AwayDigestRepository
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.NetBenchHistoryEntry
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.e2e.fixtures.E2eBitmapAndTlsFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.temporal.ChronoUnit

class E2eHarnessSmokeTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `dual MockWebServer startup, fixture loading, wrapTempScriptHtml, and session hydration work end-to-end`() = runTest {
        E2eTestHarness().use { harness ->
            val sessionJson = E2eFixtures.loadText("nodeseek/session_config_authenticated.json")
            val wrappedHtml = E2eFixtures.wrapTempScriptHtml(sessionJson)

            harness.forumDispatcher.register(method = "GET", path = "/") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .addHeader("Set-Cookie", "session=hydrated_sess_101; Path=/")
                    .setBody(wrappedHtml)
            }

            val result = harness.sessionRepository.refreshSession()
            assertTrue(result.isSuccess)
            val session = result.getOrNull()
            assertNotNull(session)
            session!!
            assertEquals(101L, session.uid)
            assertEquals("alice101", session.username)
            assertEquals(1650, session.drumsticks)
            assertEquals(42, session.stardust)
            assertEquals(4, session.permissionRank)

            val recorded = harness.forumDispatcher.recordedRequests()
            assertEquals(1, recorded.size)
            assertEquals("/", recorded.single().path)
            assertEquals(harness.testUserAgent, recorded.single().getHeader("User-Agent"))

            // Verify HTML fixtures parse cleanly through TempScriptParser & NodeSeekHtmlParser
            val home101 = NodeSeekHtmlParser.parseFeedPage(
                html = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html"),
                pageUrl = "https://www.nodeseek.com/",
            )
            assertEquals(101L, home101.config?.user?.memberId)
            assertTrue(home101.pinnedTopics.isNotEmpty())
            assertTrue(home101.topics.isNotEmpty())

            val home202 = NodeSeekHtmlParser.parseFeedPage(
                html = E2eFixtures.loadText("nodeseek/home_logged_in_uid202.html"),
                pageUrl = "https://www.nodeseek.com/",
            )
            assertEquals(202L, home202.config?.user?.memberId)
            assertEquals("bob202", home202.config?.user?.memberName)

            val tradeThread = NodeSeekHtmlParser.parseThreadPage(
                html = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html"),
                pageUrl = "https://www.nodeseek.com/post-123-1",
            )
            assertEquals(123L, tradeThread.postData.postId)
            assertEquals("trade", tradeThread.postData.category)
            assertTrue(tradeThread.openingComment?.markdown?.contains("nsapp://vote?id=42") == true)
            assertTrue(tradeThread.openingComment?.markdown?.contains("nsapp://stardust-receive") == true)

            val luckyThread = NodeSeekHtmlParser.parseThreadPage(
                html = E2eFixtures.loadText("nodeseek/topic_lucky_draw_456.html"),
                pageUrl = "https://www.nodeseek.com/post-456-1",
            )
            assertEquals(456L, luckyThread.postData.postId)
            assertEquals(2, luckyThread.replies.size)

            val cfHtml = E2eFixtures.loadText("nodeseek/cf_challenge_403.html")
            try {
                CloudflareChallengeDetector.inspectHtmlResponse(
                    statusCode = 403,
                    headers = okhttp3.Headers.headersOf("cf-mitigated", "challenge"),
                    htmlBody = cfHtml,
                    url = "https://www.nodeseek.com/",
                )
                fail("Expected CloudflareChallengeException for 403 challenge fixture")
            } catch (_: CloudflareChallengeException) {
                // expected
            }

            // Confirm all deterministic fixture files exist and load non-empty
            val allFixturePaths = listOf(
                "nodeseek/home_logged_in_uid101.html",
                "nodeseek/session_config_authenticated.json",
                "nodeseek/home_logged_in_uid202.html",
                "nodeseek/topic_trade_op_123.html",
                "nodeseek/topic_lucky_draw_456.html",
                "nodeseek/cf_challenge_403.html",
                "nodeseek/vote_info_42.json",
                "nodeseek/stardust_ledger_and_transfer.json",
                "nodeseek/credit_ledger_30d.json",
                "nodeseek/attendance_board_states.json",
                "nodeseek/invite_and_ruling.json",
                "nodeseek/homepage_categories.json",
                "drand/drand_round_4500000.json",
                "drand/floor_data_456.json",
                "netbench/geoip_and_rdap_hops.json",
                "netbench/mtr_sample_outputs.txt",
                "domains/rdap_domain_example.json",
                "mail/seekmail_otp_and_billing.json",
                "nodeimage/nodeimage_responses.json",
                "forwarding/telegram_bark_webhook.json",
            )
            for (path in allFixturePaths) {
                assertTrue("Expected non-empty fixture: $path", E2eFixtures.loadText(path).isNotBlank())
            }
        }
    }

    @Test
    fun `NodeSeekApiClient sends x-dynamic-sign and cookies to forumServer while NodeImage sends X-API-Key to externalServer with zero cookies`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                username = "alice101",
                rank = 4,
                coin = 1650,
                stardust = 42,
                sessionCookie = "ns_smoke_sess",
            )

            val voteFixture = E2eFixtures.loadText("nodeseek/vote_info_42.json")
            val creditFixture = E2eFixtures.loadText("nodeseek/credit_ledger_30d.json")
            val stardustComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/stardust_ledger_and_transfer.json"),
            ).jsonObject
            val stardustListJson = stardustComposite.getValue("list").toString()

            val nodeImageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            val nodeImageListJson = nodeImageComposite.getValue("imagesList").toString()
            val nodeImageUploadJson = nodeImageComposite.getValue("uploadSuccess").toString()

            harness.forumDispatcher.register("GET", "/api/vote/info/42") {
                MockResponse().setResponseCode(200).setBody(voteFixture)
            }
            harness.forumDispatcher.register("GET", "/api/account/credit/page-1") {
                MockResponse().setResponseCode(200).setBody(creditFixture)
            }
            harness.forumDispatcher.register("GET", "/api/stardust/list") {
                MockResponse().setResponseCode(200).setBody(stardustListJson)
            }

            harness.externalDispatcher.register("GET", "/api/v1/images") {
                MockResponse().setResponseCode(200).setBody(nodeImageListJson)
            }
            harness.externalDispatcher.register("POST", "/api/upload") {
                MockResponse().setResponseCode(200).setBody(nodeImageUploadJson)
            }

            val voteRes = harness.apiClient.getVote(42L)
            assertEquals(42L, voteRes.vote?.id)
            assertEquals(3, voteRes.vote?.items?.size)

            val creditRes = harness.apiClient.getCreditLedger(1)
            assertEquals(10, creditRes.data.size)
            assertEquals(5, creditRes.data.first().change)

            val stardustRes = harness.apiClient.listStardust(memberId = 101L, count = 10)
            assertEquals(3, stardustRes.records.size)
            assertEquals("transfer", stardustRes.records.first().type)

            val testApiKey = "0123456789abcdef0123456789abcdef"
            harness.apiClient.verifyNodeImageApiKey(testApiKey)
            val uploadedUrl = harness.apiClient.uploadImageToNodeImage(
                imageBytes = E2eFixtures.minimalPngBytes(),
                apiKey = testApiKey,
                mimeType = "image/png",
                fileName = "smoke.png",
            )
            assertEquals("https://img.nodeimage.com/i/img_e2e_01.png", uploadedUrl)

            // Verify forumServer requests received session cookies and valid x-dynamic-sign
            val forumReqs = harness.forumDispatcher.recordedRequests()
            assertEquals(3, forumReqs.size)
            for (req in forumReqs) {
                val cookie = req.getHeader("Cookie")
                assertTrue("Expected session cookie on ${req.path}, got: $cookie", cookie?.contains("session=ns_smoke_sess") == true)
            }
            val voteReq = forumReqs.first { it.path == "/api/vote/info/42" }
            val expectedVoteSign = computeDynamicSign(
                method = "GET",
                fullUrl = harness.forumServer.url("/api/vote/info/42").toString(),
                userAgent = harness.testUserAgent,
                rawBody = "",
            )
            assertEquals(expectedVoteSign, voteReq.getHeader("x-dynamic-sign"))

            val stardustReq = forumReqs.first { it.path?.startsWith("/api/stardust/list") == true }
            val expectedStardustSign = computeDynamicSign(
                method = "GET",
                fullUrl = stardustReq.requestUrl.toString(),
                userAgent = harness.testUserAgent,
                rawBody = "",
            )
            assertEquals(expectedStardustSign, stardustReq.getHeader("x-dynamic-sign"))

            // Verify externalServer requests carried X-API-Key and ZERO cookies
            val externalReqs = harness.externalDispatcher.recordedRequests()
            assertEquals(2, externalReqs.size)
            externalReqs.forEach { req ->
                assertEquals(testApiKey, req.getHeader("X-API-Key"))
                assertNull(req.getHeader("x-dynamic-sign"))
                assertNull(req.getHeader("csrf-token"))
            }
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `externalServer cookie-leak guard catches and rejects requests with Cookie header while cookie-free client stays clean`() = runTest {
        E2eTestHarness().use { harness ->
            harness.externalDispatcher.register("POST", "/bot123/sendMessage") {
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "leaked_tracker=abc; Path=/")
                    .setBody("""{"ok":true}""")
            }

            val jsonMediaType = "application/json".toMediaType()
            // 1. Cookie-free client drops Set-Cookie and sends zero cookies on subsequent calls
            val resp1 = harness.externalCookieFreeHttpClient.newCall(
                Request.Builder().url(harness.externalServer.url("/bot123/sendMessage")).post(
                    "{}".toRequestBody(jsonMediaType),
                ).build(),
            ).execute()
            assertEquals(200, resp1.code)
            resp1.close()

            val resp2 = harness.externalCookieFreeHttpClient.newCall(
                Request.Builder().url(harness.externalServer.url("/bot123/sendMessage")).post(
                    "{}".toRequestBody(jsonMediaType),
                ).build(),
            ).execute()
            assertEquals(200, resp2.code)
            resp2.close()
            harness.assertNoCookiesLeakedToExternalServer()

            // 2. Deliberately injecting a Cookie header triggers the guard (HTTP 500 + violation + AssertionError)
            val leakyClient = OkHttpClient()
            val leakyResp = leakyClient.newCall(
                Request.Builder()
                    .url(harness.externalServer.url("/bot123/sendMessage"))
                    .header("Cookie", "session=leaked_forum_cookie")
                    .post("{}".toRequestBody(jsonMediaType))
                    .build(),
            ).execute()
            assertEquals(500, leakyResp.code)
            val leakyBody = leakyResp.body.string()
            leakyResp.close()
            assertTrue(leakyBody.contains("COOKIE_LEAK_GUARD_VIOLATION"))
            assertEquals(1, harness.externalDispatcher.cookieViolations().size)

            try {
                harness.assertNoCookiesLeakedToExternalServer()
                fail("Expected assertNoCookiesLeakedToExternalServer() to throw AssertionError")
            } catch (_: AssertionError) {
                // expected
            }
        }
    }

    @Test
    fun `isolated tempDir persistence round-trips DataStore settings, Fleet, SshVault, VpsMailAutoIngest, NetBenchHistory, and DraftDao`() = runTest {
        E2eTestHarness().use { harness ->
            // 1. DataStore SettingsRepositoryImpl round-trip (await async dataStoreScope stateIn propagation)
            val apiKey = "abcdef0123456789abcdef0123456789"
            harness.settingsRepository.setNodeImageApiKey(apiKey)
            harness.settingsRepository.setPreferRandomCheckIn(true)
            val observedSettings = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first {
                        it.nodeImageApiKey == apiKey && it.preferRandomCheckIn
                    }
                }
            }
            assertEquals(apiKey, observedSettings.nodeImageApiKey)
            assertTrue(observedSettings.preferRandomCheckIn)

            // 2. DraftDao uid isolation
            harness.draftRepository.saveDraft(
                EditorDraft(
                    draftKey = "new_topic",
                    accountUid = 101L,
                    title = "Account 101 Draft",
                    markdownContent = "Body 101",
                    categorySlug = "trade",
                    updatedAtMillis = harness.clock.nowMillis(),
                ),
            )
            harness.draftRepository.saveDraft(
                EditorDraft(
                    draftKey = "new_topic",
                    accountUid = 202L,
                    title = "Account 202 Draft",
                    markdownContent = "Body 202",
                    categorySlug = "daily",
                    updatedAtMillis = harness.clock.nowMillis(),
                ),
            )
            assertEquals("Account 101 Draft", harness.draftRepository.getDraft(101L, "new_topic")?.title)
            assertEquals("Account 202 Draft", harness.draftRepository.getDraft(202L, "new_topic")?.title)

            // 3. Fleet + SSH Vault + VpsMailAutoIngestServiceImpl + NetBenchHistoryStore persistence & reload
            val vpsAsset = harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "rn-lax-88",
                    nickname = "RackNerd LAX",
                    providerName = "RackNerd",
                    specsSummary = "2C / 2G",
                    region = "洛杉矶",
                    renewalAmount = "10.98",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-03-15",
                    notes = "[SeekMail] IP: 198.51.100.88",
                ),
            )
            assertEquals("rn-lax-88", vpsAsset.id)

            val savedSsh = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "RackNerd LAX",
                    host = "198.51.100.88",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "SmokeSecret!23",
                ),
            )
            assertTrue(harness.sshVaultFile.readText().doesNotContain("SmokeSecret!23"))

            harness.netBenchHistoryStore.record(
                NetBenchHistoryEntry(
                    host = "198.51.100.88",
                    port = 22,
                    resolvedIp = "198.51.100.88",
                    ipFamily = IpFamily.IPV4,
                    lastAvgMs = 138.4,
                    lastOriginType = IpOriginType.NATIVE,
                    testedAtEpochMillis = harness.clock.nowMillis(),
                ),
            )

            // Ingest billing renewal mail from e2e/mail/seekmail_otp_and_billing.json
            val mailJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_and_billing.json"),
            ).jsonObject
            val mailNode = mailJson.getValue("vpsRenewalDetail").jsonObject
                .getValue("data").jsonObject
                .getValue("mail").jsonObject
            val mailDetail = SeekMailDetail(
                summary = SeekMailSummary(
                    id = mailNode.getValue("id").jsonPrimitive.content.toLong(),
                    mailboxId = mailNode.getValue("mailboxId").jsonPrimitive.content.toLong(),
                    fromAddress = mailNode.getValue("fromAddress").jsonPrimitive.content,
                    fromName = mailNode.getValue("fromName").jsonPrimitive.content,
                    toAddress = mailNode.getValue("toAddress").jsonPrimitive.content,
                    subject = mailNode.getValue("subject").jsonPrimitive.content,
                    snippet = mailNode.getValue("bodyText").jsonPrimitive.content,
                    messageId = mailNode.getValue("messageId").jsonPrimitive.content,
                ),
                bodyText = mailNode.getValue("bodyText").jsonPrimitive.content,
            )

            val updates = harness.vpsMailAutoIngestService.processMailDetail(mailDetail)
            assertEquals(1, updates.size)
            assertEquals("2027-03-20", updates.single().newExpiryDateIso)
            assertEquals(
                "2027-03-20",
                harness.fleetRepository.assetsFlow.value.first { it.id == "rn-lax-88" }.expiryDateIso,
            )

            // Cold restart verification against the same tempDir files
            val reloadedFleet = FleetRepositoryImpl(
                storeFile = harness.fleetStoreFile,
                tokenCipher = harness.fleetCipher,
                nowMillisProvider = harness.clock::nowMillis,
            )
            reloadedFleet.loadPersistedState()
            assertEquals(
                "2027-03-20",
                reloadedFleet.assetsFlow.value.first { it.id == "rn-lax-88" }.expiryDateIso,
            )

            val reloadedSsh = SshServerVault(
                vaultFile = harness.sshVaultFile,
                cipher = harness.sshCipher,
                ioDispatcher = Dispatchers.IO,
                clock = harness.clock::nowMillis,
            )
            assertEquals(1, reloadedSsh.serversFlow.value.size)
            val reloadedProfile = reloadedSsh.serversFlow.value.single()
            assertEquals(savedSsh.id, reloadedProfile.id)
            assertEquals("SmokeSecret!23", reloadedSsh.decryptCredentials(reloadedProfile).secret)

            val reloadedIngest = VpsMailAutoIngestServiceImpl(
                fleetRepository = reloadedFleet,
                sshVaultRepository = reloadedSsh,
                stateFile = harness.vpsMailIngestStateFile,
                todayProvider = harness.clock::todayShanghai,
                nowMillisProvider = harness.clock::nowMillis,
            )
            assertTrue(reloadedIngest.processMailDetail(mailDetail).isEmpty())

            val reloadedNetBench = NetBenchHistoryStore(historyFile = harness.netBenchHistoryFile)
            assertEquals(1, reloadedNetBench.entries.value.size)
            assertEquals("198.51.100.88", reloadedNetBench.entries.value.single().host)
        }
    }

    @Test
    fun `buildAppContainer proxy resolves wired repositories and E2eBitmapAndTlsFixtures produces valid PNG and TLS certificates`() = runTest {
        E2eTestHarness().use { harness ->
            val container = harness.buildAppContainer()
            assertTrue(container.sessionRepository === harness.sessionRepository)
            assertTrue(container.forumRepository === harness.forumRepository)
            assertTrue(container.checkInRepository === harness.checkInRepository)
            assertTrue(container.userRepository === harness.userRepository)
            assertTrue(container.bookmarkAndHistoryRepository === harness.bookmarkAndHistoryRepository)
            assertTrue(container.settingsRepository === harness.settingsRepository)
            assertTrue(container.draftRepository === harness.draftRepository)
            assertTrue(container.contentFilterRepository === harness.contentFilterRepository)
            assertTrue(container.imageHostRepository === harness.imageHostRepository)
            assertTrue(container.vpsMailAutoIngestService === harness.vpsMailAutoIngestService)
            assertTrue(container.fleetRepository === harness.fleetRepository)
            assertTrue(container.netBenchHistoryStore === harness.netBenchHistoryStore)
            assertTrue(container.sshVaultRepository === harness.sshVaultRepository)
            assertTrue(container.communityToolsRepository === harness.communityToolsRepository)
            assertTrue(container.pollAndStardustRepository === harness.pollAndStardustRepository)
            assertTrue(container.luckyDrawRepository === harness.luckyDrawRepository)
            assertNotNull(container.awayDigestRepository)
            assertNotNull(container.officialTradeRepository)

            // Verify PNG fixture generation with ImageUploadFormatInspector
            val pngBytes = E2eBitmapAndTlsFixtures.solidRgbaPngBytes(width = 16, height = 12)
            val sniffed = ImageUploadFormatInspector.inspect(pngBytes, fileName = "bench.png")
            assertNotNull(sniffed)
            assertEquals(DetectedImageFormat.PNG, sniffed?.format)
            assertTrue(sniffed?.detectedByMagic == true)
            assertFalse(sniffed?.isAnimated == true)

            // Verify valid & expired TLS fixtures + HTTPS MockWebServer round-trip
            val validTls = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(validityDays = 10)
            val expiredTls = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
                startDateOffsetDays = -30,
                validityDays = 7,
            )

            val now = Instant.now()
            assertTrue(ChronoUnit.DAYS.between(now, validTls.certificate.notAfter.toInstant()) > 0)
            assertTrue(ChronoUnit.DAYS.between(now, expiredTls.certificate.notAfter.toInstant()) < 0)

            val httpsServer = MockWebServer()
            httpsServer.useHttps(validTls.sslContext.socketFactory, false)
            httpsServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"rdapConformance":["rdap_level_0"]}"""))
            httpsServer.start()
            try {
                val httpsClient = OkHttpClient.Builder()
                    .sslSocketFactory(validTls.sslContext.socketFactory, validTls.trustManager)
                    .build()
                val response = httpsClient.newCall(
                    Request.Builder().url(httpsServer.url("/domain/example.com")).build(),
                ).execute()
                assertEquals(200, response.code)
                val peerCert = response.handshake?.peerCertificates?.firstOrNull()
                assertNotNull(peerCert)
                response.close()
            } finally {
                httpsServer.shutdown()
            }
        }
    }

    @Test
    fun `buildAppContainer supports Object methods, default interface methods, and overrides precedence`() = runTest {
        E2eTestHarness().use { harness ->
            val container = harness.buildAppContainer()
            val otherContainer = harness.buildAppContainer()

            // Object methods
            val expectedIdentity = System.identityHashCode(container)
            assertEquals("E2eAppContainerProxy@$expectedIdentity", container.toString())
            assertEquals(expectedIdentity, container.hashCode())
            assertTrue(container == container)
            assertFalse(container == otherContainer)
            assertFalse(container.equals(null))
            assertNotEquals(container.hashCode(), otherContainer.hashCode())

            // Java interface default methods (including M1+ defaults absent from defaultBindings)
            val defaultMethods = AppContainer::class.java.methods
                .filter { it.isDefault && it.parameterCount == 0 }
            assertTrue("Expected default methods on AppContainer", defaultMethods.isNotEmpty())
            defaultMethods.forEach { method ->
                val resolved = method.invoke(container)
                assertNotNull("Default method ${method.name} returned null", resolved)
            }

            // Overrides take precedence over defaultBindings (both property name & getter name)
            val customDigestRepo = Proxy.newProxyInstance(
                AwayDigestRepository::class.java.classLoader,
                arrayOf(AwayDigestRepository::class.java),
            ) { _, method, _ ->
                if (method.declaringClass == Any::class.java) method.name else null
            } as AwayDigestRepository
            val overriddenByProp = harness.buildAppContainer(
                overrides = mapOf("awayDigestRepository" to customDigestRepo),
            )
            assertSame(customDigestRepo, overriddenByProp.awayDigestRepository)

            val overriddenByGetter = harness.buildAppContainer(
                overrides = mapOf("getAwayDigestRepository" to customDigestRepo),
            )
            assertSame(customDigestRepo, overriddenByGetter.awayDigestRepository)

            // Overrides also take precedence over interface default methods absent from defaultBindings
            val nonBaselineDefault = defaultMethods.firstOrNull {
                it.name !in setOf(
                    "getAwayDigestRepository",
                    "getVpsMailAutoIngestService",
                    "getActivityMonitorController",
                    "getOfficialTradeRepository",
                    "getOverseasPromoMonitorController",
                ) && it.returnType.isInterface
            }
            if (nonBaselineDefault != null) {
                val customDefaultOverride = Proxy.newProxyInstance(
                    nonBaselineDefault.returnType.classLoader,
                    arrayOf(nonBaselineDefault.returnType),
                ) { _, method, _ ->
                    if (method.declaringClass == Any::class.java) method.name else null
                }
                val overriddenDefaultContainer = harness.buildAppContainer(
                    overrides = mapOf(nonBaselineDefault.name to customDefaultOverride),
                )
                assertSame(customDefaultOverride, nonBaselineDefault.invoke(overriddenDefaultContainer))
            }

            // Unmapped non-default abstract getter still fails loudly
            try {
                container.inboxRepository
                fail("Expected UnsupportedOperationException for unmapped abstract getter")
            } catch (_: UnsupportedOperationException) {
                // expected
            }
        }
    }

    @Test
    fun `E2eDraftDao retains 50 of 50 drafts under 50 concurrent upsertAll coroutines`() = runTest {
        E2eTestHarness().use { harness ->
            val totalDrafts = 50
            withContext(Dispatchers.Default) {
                (1..totalDrafts).map { index ->
                    async {
                        val accountUid = if (index % 2 == 0) 202L else 101L
                        harness.draftDao.upsertAll(
                            listOf(
                                DraftEntity(
                                    accountUid = accountUid,
                                    draftKey = "concurrent_draft_$index",
                                    title = "Title $index",
                                    markdownContent = "Body $index",
                                    categorySlug = "tech",
                                    updatedAtMillis = 1_764_000_000_000L + index,
                                ),
                            ),
                        )
                    }
                }.awaitAll()
            }

            val allDrafts = harness.draftDao.getAllDrafts()
            assertEquals(totalDrafts, allDrafts.size)
            assertEquals(25, allDrafts.count { it.accountUid == 101L })
            assertEquals(25, allDrafts.count { it.accountUid == 202L })

            // Concurrent deleteDraft and clearForAccount remain race-free
            withContext(Dispatchers.Default) {
                (1..10).map { index ->
                    async {
                        val accountUid = if (index % 2 == 0) 202L else 101L
                        harness.draftDao.deleteDraft(accountUid, "concurrent_draft_$index")
                    }
                }.awaitAll()
            }
            assertEquals(40, harness.draftDao.getAllDrafts().size)

            harness.draftDao.clearForAccount(101L)
            val remaining = harness.draftDao.getAllDrafts()
            assertEquals(20, remaining.size)
            assertTrue(remaining.all { it.accountUid == 202L })
        }
    }

    @Test
    fun `E2eCategoryDao retains all rows under concurrent upsertAll and performs atomic replaceForAccount`() = runTest {
        E2eTestHarness().use { harness ->
            val totalPerAccount = 25
            withContext(Dispatchers.Default) {
                (1..totalPerAccount).flatMap { index ->
                    listOf(
                        async {
                            harness.categoryDao.upsertAll(
                                listOf(
                                    CategoryEntity(
                                        accountUid = 101L,
                                        slug = "cat_101_$index",
                                        name = "Cat 101 $index",
                                        description = "Desc $index",
                                        sortOrder = index,
                                        isCustomizable = true,
                                        visibleOnHome = true,
                                        isAdminOnly = false,
                                        topicCoinCost = 0,
                                        supportsRss = true,
                                        syncedAtEpochMillis = 1_764_000_000_000L + index,
                                    ),
                                ),
                            )
                        },
                        async {
                            harness.categoryDao.upsertAll(
                                listOf(
                                    CategoryEntity(
                                        accountUid = 202L,
                                        slug = "cat_202_$index",
                                        name = "Cat 202 $index",
                                        description = "Desc $index",
                                        sortOrder = index,
                                        isCustomizable = true,
                                        visibleOnHome = index % 2 == 0,
                                        isAdminOnly = false,
                                        topicCoinCost = 0,
                                        supportsRss = true,
                                        syncedAtEpochMillis = 1_764_000_000_000L + index,
                                    ),
                                ),
                            )
                        },
                    )
                }.awaitAll()
            }

            assertEquals(totalPerAccount, harness.categoryDao.getCategories(101L).size)
            assertEquals(totalPerAccount, harness.categoryDao.getCategories(202L).size)

            // Concurrent replaceForAccount on uid=101 must never clobber uid=202 or drop replacement items
            val replacement101 = listOf(
                CategoryEntity(
                    accountUid = 101L,
                    slug = "tech",
                    name = "技术",
                    description = "Tech",
                    sortOrder = 0,
                    isCustomizable = true,
                    visibleOnHome = true,
                    isAdminOnly = false,
                    topicCoinCost = 0,
                    supportsRss = true,
                    syncedAtEpochMillis = 1_764_000_100_000L,
                ),
                CategoryEntity(
                    accountUid = 101L,
                    slug = "trade",
                    name = "交易",
                    description = "Trade",
                    sortOrder = 1,
                    isCustomizable = true,
                    visibleOnHome = false,
                    isAdminOnly = false,
                    topicCoinCost = 0,
                    supportsRss = true,
                    syncedAtEpochMillis = 1_764_000_100_000L,
                ),
            )
            withContext(Dispatchers.Default) {
                (1..12).map {
                    async {
                        harness.categoryDao.replaceForAccount(101L, replacement101)
                    }
                }.awaitAll()
            }
            val cats101 = harness.categoryDao.getCategories(101L)
            assertEquals(listOf("tech", "trade"), cats101.map { it.slug })
            assertEquals(totalPerAccount, harness.categoryDao.getCategories(202L).size)
        }
    }

    @Test
    fun `E2eBookmarkDao retains all rows under concurrent upsertAll, updateOfflineSnapshot, and replaceAllBookmarks`() = runTest {
        E2eTestHarness().use { harness ->
            val totalBookmarks = 50
            withContext(Dispatchers.Default) {
                (1..totalBookmarks).map { index ->
                    async {
                        val uid = if (index % 2 == 0) 202L else 101L
                        harness.bookmarkDao.upsertAll(
                            listOf(
                                BookmarkEntity(
                                    accountUid = uid,
                                    topicId = index.toLong(),
                                    title = "Bookmark $index",
                                    categorySlug = "tech",
                                    categoryName = "技术",
                                    authorId = uid,
                                    authorName = "user$uid",
                                    authorAvatarUrl = "",
                                    authorLevel = 3,
                                    viewCount = index * 10,
                                    replyCount = index,
                                    lastActivityIso = "2026-11-25T10:00:00Z",
                                    requiredViewLevel = 0,
                                    isLocked = false,
                                    offlineDetailJson = null,
                                    isSyncedWithServer = true,
                                    savedAtEpochMillis = 1_764_000_000_000L + index,
                                ),
                            ),
                        )
                    }
                }.awaitAll()
            }

            assertEquals(totalBookmarks, harness.bookmarkDao.getAllBookmarks().size)
            assertEquals(25, harness.bookmarkDao.getBookmarkedTopicIds(101L).size)
            assertEquals(25, harness.bookmarkDao.getBookmarkedTopicIds(202L).size)

            withContext(Dispatchers.Default) {
                (1..totalBookmarks).map { index ->
                    async {
                        val uid = if (index % 2 == 0) 202L else 101L
                        harness.bookmarkDao.updateOfflineSnapshot(uid, index.toLong(), """{"id":$index}""")
                    }
                }.awaitAll()
            }
            assertTrue(harness.bookmarkDao.getAllBookmarks().all { it.offlineDetailJson != null })

            val finalSnapshot = (1..4).map { index ->
                BookmarkEntity(
                    accountUid = 101L,
                    topicId = 900L + index,
                    title = "Replaced $index",
                    categorySlug = "daily",
                    categoryName = "日常",
                    authorId = 101L,
                    authorName = "alice101",
                    authorAvatarUrl = "",
                    authorLevel = 4,
                    viewCount = 1,
                    replyCount = 0,
                    lastActivityIso = "2026-11-25T10:00:00Z",
                    requiredViewLevel = 0,
                    isLocked = false,
                    offlineDetailJson = null,
                    isSyncedWithServer = true,
                    savedAtEpochMillis = 1_764_000_200_000L + index,
                )
            }
            withContext(Dispatchers.Default) {
                (1..8).map {
                    async {
                        harness.bookmarkDao.replaceAllBookmarks(finalSnapshot)
                    }
                }.awaitAll()
            }
            assertEquals(4, harness.bookmarkDao.getAllBookmarks().size)
        }
    }

    @Test
    fun `E2eReadHistoryDao retains all rows under 50 concurrent upsert coroutines and deleteByTopicId`() = runTest {
        E2eTestHarness().use { harness ->
            val totalEntries = 50
            withContext(Dispatchers.Default) {
                (1..totalEntries).map { index ->
                    async {
                        val uid = if (index % 2 == 0) 202L else 101L
                        harness.readHistoryDao.upsert(
                            ReadHistoryEntity(
                                accountUid = uid,
                                topicId = index.toLong(),
                                title = "History $index",
                                categorySlug = "tech",
                                categoryName = "技术",
                                authorName = "alice101",
                                authorAvatarUrl = "",
                                lastReadPage = 1,
                                lastReadFloor = index,
                                visitedAtEpochMillis = 1_764_000_000_000L + index,
                            ),
                        )
                    }
                }.awaitAll()
            }

            val history101 = harness.readHistoryDao.observeHistory(101L, limit = 100).first()
            val history202 = harness.readHistoryDao.observeHistory(202L, limit = 100).first()
            assertEquals(25, history101.size)
            assertEquals(25, history202.size)
            assertTrue(history101.first().visitedAtEpochMillis >= history101.last().visitedAtEpochMillis)

            withContext(Dispatchers.Default) {
                (1..10).map { index ->
                    async {
                        val uid = if (index % 2 == 0) 202L else 101L
                        harness.readHistoryDao.deleteByTopicId(uid, index.toLong())
                    }
                }.awaitAll()
            }
            assertEquals(20, harness.readHistoryDao.observeHistory(101L, limit = 100).first().size)
            assertEquals(20, harness.readHistoryDao.observeHistory(202L, limit = 100).first().size)
        }
    }

    @Test
    fun `RouteMockDispatcher matches longest prefix regardless of registration order`() = runTest {
        E2eTestHarness().use { harness ->
            // Case 1: Broad prefix registered BEFORE more specific prefix
            harness.forumDispatcher.registerPrefix(method = null, pathPrefix = "/api/") {
                MockResponse().setResponseCode(200).setBody("broad-api")
            }
            harness.forumDispatcher.registerPrefix(method = null, pathPrefix = "/api/vote/") {
                MockResponse().setResponseCode(200).setBody("specific-vote")
            }

            // Case 2: Specific prefix registered BEFORE broad prefix
            harness.forumDispatcher.registerPrefix(method = null, pathPrefix = "/api/stardust/") {
                MockResponse().setResponseCode(200).setBody("specific-stardust")
            }

            // Case 3: Equal prefix length with method-specific vs wildcard method
            harness.forumDispatcher.registerPrefix(method = "POST", pathPrefix = "/api/vote/") {
                MockResponse().setResponseCode(200).setBody("post-vote")
            }

            // Case 4: Exact route still beats longest prefix
            harness.forumDispatcher.register(method = "GET", path = "/api/vote/exact") {
                MockResponse().setResponseCode(200).setBody("exact-vote")
            }

            val client = OkHttpClient()
            fun fetchBody(method: String, path: String): String {
                val reqBuilder = Request.Builder().url(harness.forumServer.url(path))
                if (method == "POST") {
                    reqBuilder.post("{}".toRequestBody("application/json".toMediaType()))
                } else {
                    reqBuilder.get()
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    assertEquals(200, resp.code)
                    return resp.body.string()
                }
            }

            assertEquals("specific-vote", fetchBody("GET", "/api/vote/info/42"))
            assertEquals("post-vote", fetchBody("POST", "/api/vote/cast"))
            assertEquals("specific-stardust", fetchBody("GET", "/api/stardust/list"))
            assertEquals("broad-api", fetchBody("GET", "/api/account/credit/page-1"))
            assertEquals("exact-vote", fetchBody("GET", "/api/vote/exact"))
        }
    }

    @Test
    fun `M2 AppContainer bindings and loopback LocalTlsServer resolve cleanly`() = runTest {
        val tlsFixture = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
            commonName = "smoke.example.com",
            sanDnsNames = listOf("smoke.example.com"),
            validityDays = 14,
        )
        E2eTestHarness(
            initialEpochMillis = maxOf(System.currentTimeMillis(), tlsFixture.certificate.notBefore.time + 1_000L),
        ).use { harness ->
            val container = harness.buildAppContainer()
            assertSame(harness.benchmarkBaselineStore, container.benchmarkBaselineRepository)
            assertSame(harness.domainRdapClient, container.domainRdapClient)
            assertSame(harness.tlsCertificateInspector, container.tlsCertificateInspector)
            assertSame(harness.exchangeRateRepository, container.exchangeRateRepository)
            assertSame(harness.defaultSshSessionManager, container.sshSessionManager)

            val server = harness.track(E2eBitmapAndTlsFixtures.LocalTlsServer(tlsFixture))
            harness.tlsSocketOpsOverride = E2eBitmapAndTlsFixtures.LoopbackTlsSocketOps(
                portOverride = server.port,
                trustManager = tlsFixture.trustManager,
            )
            val inspected = container.tlsCertificateInspector.inspect("smoke.example.com", server.port)
            assertTrue(inspected.isSuccess)
            assertFalse(inspected.getOrThrow().isExpired)
            assertTrue(inspected.getOrThrow().daysRemaining in 13L..14L)
        }
    }

    @Test
    fun `M3 AppContainer bindings resolve radarRepository and tradeMarketReferenceRepository and factories create ViewModels`() = runTest {
        E2eTestHarness().use { harness ->
            val container = harness.buildAppContainer()
            assertSame(harness.radarRepository, container.radarRepository)
            assertSame(harness.tradeMarketReferenceRepository, container.tradeMarketReferenceRepository)

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(E2eFixtures.loadText("nodeseek/topic_trade_op_123.html"))
            }

            val detailVm = harness.createTopicDetailViewModel(topicId = 123L, container = container)
            val composerVm = harness.createTopicComposerViewModel(
                initialCategorySlug = "carpool",
                initialTitle = "[3=1] Smoke",
                initialMarkdown = "Body",
                container = container,
            )
            assertNotNull(detailVm)
            assertNotNull(composerVm)
            assertEquals("carpool", composerVm.uiState.value.selectedCategorySlug)
        }
    }

    @Test
    fun `M4 AppContainer bindings, upgraded E2eInMemoryRadarRepository, and M4 fixtures work cleanly`() = runTest {
        E2eTestHarness().use { harness ->
            val container = harness.buildAppContainer()
            assertSame(harness.seekMailRepository, container.seekMailRepository)
            assertSame(harness.defaultSeekMailOtpWatcher, container.seekMailOtpWatcher)
            assertSame(harness.alertForwarder, container.alertForwarder)
            assertSame(harness.widgetSnapshotRepository, container.widgetSnapshotRepository)

            // Verify new M4 deterministic fixtures load & parse cleanly
            val otpWatchFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            assertTrue(otpWatchFixture.containsKey("mailboxes"))
            assertTrue(otpWatchFixture.containsKey("baselineMailsPage"))
            assertTrue(otpWatchFixture.containsKey("otpDetailNew"))

            val probeRestockFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/probe_and_restock_alerts.json"),
            ).jsonObject
            assertTrue(probeRestockFixture.containsKey("nezhaOnlineServerPayload"))
            assertTrue(probeRestockFixture.containsKey("restockInStockHtml"))

            // Verify E2eInMemoryRadarRepository alert deduplication and unread counter
            val rule = harness.radarRepository.addKeywordRule(
                keyword = "DMIT",
                categorySlug = "trade",
                matchMode = KeywordMatchMode.CONTAINS,
            ).getOrThrow()
            assertEquals("DMIT", rule.keyword)

            val alert = LocalAlertRecord(
                dedupKey = "smoke:kw:1",
                kind = LocalAlertKind.KEYWORD_HIT,
                topicId = 9001L,
                topicTitle = "DMIT Pro Smoke",
                matchedKeyword = "DMIT",
                createdAtEpochMillis = harness.clock.nowMillis(),
            )
            assertTrue(harness.radarRepository.recordAlertIfAbsent(alert))
            assertFalse(harness.radarRepository.recordAlertIfAbsent(alert))
            assertEquals(1, harness.radarRepository.unreadAlertCountFlow.first())

            harness.radarRepository.markAllAlertsRead()
            assertEquals(0, harness.radarRepository.unreadAlertCountFlow.first())
        }
    }

    @Test
    fun `M4 createTelegramRoutingAlertForwarder intercepts Telegram HTTPS via externalDispatcher with zero cookie leak`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "m4_smoke_cookie")
            val container = harness.buildAppContainer()
            val seekMailVm = harness.createSeekMailViewModel(container = container)
            assertNotNull(seekMailVm)

            val (_, telegramForwarder) = harness.createTelegramRoutingAlertForwarder()
            val tgSuccess = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject.getValue("telegramSuccess").toString()

            harness.externalDispatcher.register("POST", "/bot999111:SmokeToken/sendMessage") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(tgSuccess)
            }

            telegramForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    telegram = TelegramForwardConfig(
                        enabled = true,
                        botToken = "999111:SmokeToken",
                        chatId = "123456",
                    ),
                )
            }
            val results = telegramForwarder.sendTestAlert()
            assertEquals(1, results.size)
            assertTrue("Expected Telegram success, got: ${results.single()}", results.single().success)
            assertEquals(200, results.single().httpStatus)
            assertEquals(1, harness.externalDispatcher.recordedRequests().size)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `M5 AppContainer bindings, useHttpsNodeSeekOrigin wire cookies, and M5 fixtures work cleanly`() = runTest {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            val container = harness.buildAppContainer()
            assertSame(harness.multiAccountManager, container.multiAccountManager)
            assertSame(harness.coilOkHttpClient, container.coilOkHttpClient)

            // Unmapped inboxRepository on default container MUST still throw UnsupportedOperationException
            try {
                container.inboxRepository
                fail("Expected UnsupportedOperationException for unmapped inboxRepository")
            } catch (_: UnsupportedOperationException) {
                // expected
            }

            harness.seedAuthenticatedSession(
                uid = 101L,
                username = "alice101",
                sessionCookie = "m5_https_sess",
            )
            val home101 = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(home101)
            }
            val session = harness.sessionRepository.refreshSession().getOrThrow()
            assertEquals(101L, session?.uid)
            val req = harness.forumDispatcher.recordedRequests().single()
            assertTrue(req.getHeader("Cookie")?.contains("session=m5_https_sess") == true)

            val hubVm = harness.createPulseHubViewModel()
            assertNotNull(hubVm)

            // Verify new M5 fixtures load & parse cleanly
            val longImageP1 = NodeSeekHtmlParser.parseThreadPage(
                html = E2eFixtures.loadText("nodeseek/topic_long_image_701.html"),
                pageUrl = "https://www.nodeseek.com/post-701-1",
            )
            assertEquals(701L, longImageP1.postData.postId)
            assertNotNull(longImageP1.openingComment)
            assertEquals(1, longImageP1.replies.size)

            val longImageP2 = NodeSeekHtmlParser.parseThreadPage(
                html = E2eFixtures.loadText("nodeseek/topic_long_image_701_page2.html"),
                pageUrl = "https://www.nodeseek.com/post-701-2",
            )
            assertEquals(2, longImageP2.postData.postPage)
            assertNull(longImageP2.openingComment)

            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            assertTrue(nodeImageJson.containsKey("imagesPage1NestedData"))
            assertTrue(nodeImageJson.containsKey("imagesPage2NestedData"))
            assertTrue(nodeImageJson.containsKey("imagesListAlias"))
            assertTrue(nodeImageJson.containsKey("imagesTopLevelArray"))
            assertTrue(nodeImageJson.containsKey("deleteFailure200"))
        }
    }

    private fun String.doesNotContain(other: String): Boolean = !this.contains(other)
}
