package com.nodeseek.app.e2e.m2

import com.nodeseek.app.data.fleet.DomainRdapClient
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.mail.VpsMailAutoIngestServiceImpl
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.VpsBillingMailParser
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.e2e.fixtures.E2eBitmapAndTlsFixtures
import com.nodeseek.app.ui.fleet.AssetCategoryFilter
import com.nodeseek.app.ui.fleet.MyServersTab
import com.nodeseek.app.ui.fleet.MyServersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.net.ServerSocket

class R8DomainAndTlsAssetsE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T1-R8-1 looks up domain RDAP via 302 redirect and auto-populates draft fields and tlsHost`() = runTest {
        E2eTestHarness().use { harness ->
            val rdapJson = E2eFixtures.loadText("domains/rdap_domain_example.json")
            harness.externalDispatcher.register("GET", "/rdap/domain/example.com") {
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", harness.externalServer.url("/registry/domain/example.com").toString())
            }
            harness.externalDispatcher.register("GET", "/registry/domain/example.com") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/rdap+json")
                    .setBody(rdapJson)
            }

            val container = harness.buildAppContainer()
            val vm = harness.manage(
                MyServersViewModel(
                    fleetRepository = container.fleetRepository,
                    exchangeRateRepository = container.exchangeRateRepository,
                    todayProvider = harness.clock::todayShanghai,
                    nowMillisProvider = harness.clock::nowMillis,
                    initialTab = MyServersTab.ASSETS,
                    computationDispatcher = Dispatchers.Default,
                    domainRdapClient = container.domainRdapClient,
                    tlsCertificateInspector = container.tlsCertificateInspector,
                ),
            )

            vm.openAddDomainAssetSheet()
            vm.lookupDomainRdapForDraft("https://EXAMPLE.COM/path?foo=bar")

            val syncedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isLookingUpRdap && it.assetEditorDraft?.rdapSyncRevision == 1
                    }
                }
            }
            val draft = syncedState.assetEditorDraft!!
            assertEquals(FleetAssetType.DOMAIN_CERT, draft.assetType)
            assertEquals("example.com", draft.domainName)
            assertEquals("Cloudflare, Inc.", draft.registrarName)
            assertEquals("2027-05-01", draft.expiryDateIso)
            assertEquals("2020-05-01", draft.domainCreatedIso)
            assertEquals(listOf("client transfer prohibited"), draft.rdapStatusTags)
            assertEquals("example.com", draft.tlsHost)
            assertNull(syncedState.rdapLookupError)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R8-2 inspects real SSLServerSocket TLS cert expiry days, SANs, and refreshes saved asset`() = runTest {
        val tlsFixture = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
            commonName = "example.com",
            sanDnsNames = listOf("example.com", "www.example.com"),
            validityDays = 30,
        )
        E2eTestHarness(
            initialEpochMillis = maxOf(System.currentTimeMillis(), tlsFixture.certificate.notBefore.time + 1_000L),
        ).use { harness ->
            val tlsServer = harness.track(E2eBitmapAndTlsFixtures.LocalTlsServer(tlsFixture))
            harness.tlsSocketOpsOverride = E2eBitmapAndTlsFixtures.LoopbackTlsSocketOps(
                portOverride = tlsServer.port,
                trustManager = tlsFixture.trustManager,
                enforceHttpsHostnameMatch = true,
            )

            val directInspect = harness.tlsCertificateInspector.inspect("example.com", tlsServer.port)
            assertTrue(directInspect.isSuccess)
            val certInfo = directInspect.getOrThrow()
            assertTrue(certInfo.isTrusted)
            assertFalse(certInfo.isExpired)
            assertTrue("Expected daysRemaining in 29..30, was ${certInfo.daysRemaining}", certInfo.daysRemaining in 29L..30L)
            assertTrue(certInfo.sanDnsNames.containsAll(listOf("example.com", "www.example.com")))

            val container = harness.buildAppContainer()
            val vm = harness.manage(
                MyServersViewModel(
                    fleetRepository = container.fleetRepository,
                    exchangeRateRepository = container.exchangeRateRepository,
                    todayProvider = harness.clock::todayShanghai,
                    nowMillisProvider = harness.clock::nowMillis,
                    initialTab = MyServersTab.ASSETS,
                    computationDispatcher = Dispatchers.Default,
                    domainRdapClient = container.domainRdapClient,
                    tlsCertificateInspector = container.tlsCertificateInspector,
                ),
            )

            vm.openAddDomainAssetSheet()
            vm.checkTlsForDraft("example.com", tlsServer.port)
            val checkedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val d = it.assetEditorDraft
                        !it.isCheckingTls &&
                            d?.tlsCheckRevision == 1 &&
                            it.tlsCheckResultMessage?.contains("TLS 剩余") == true
                    }
                }
            }
            val draft = checkedState.assetEditorDraft!!
            assertEquals("example.com", draft.tlsHost)
            assertEquals(tlsServer.port.toString(), draft.tlsPort)
            assertNotNull(draft.tlsExpiresAtEpochMillis)
            assertNull(draft.tlsCheckError)
            assertTrue(checkedState.tlsCheckResultMessage?.contains("TLS 剩余") == true)
            assertTrue(checkedState.tlsCheckResultMessage?.contains(certInfo.expiresDateIso) == true)

            // Persist asset and invoke refreshDomainAndTlsForAsset
            harness.externalDispatcher.register("GET", "/rdap/domain/example.com") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/rdap+json")
                    .setBody(E2eFixtures.loadText("domains/rdap_domain_example.json"))
            }
            val asset = VpsAssetRecord(
                id = "dom_example",
                nickname = "example.com",
                providerName = "Cloudflare, Inc.",
                specsSummary = "主站域名",
                region = "Global",
                renewalAmount = "10.44",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2027-05-01",
                assetType = FleetAssetType.DOMAIN_CERT,
                domainName = "example.com",
                tlsHost = "example.com",
                tlsPort = tlsServer.port,
            )
            vm.saveAsset(asset)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { state -> state.assets.any { it.id == "dom_example" } }
                }
            }
            vm.refreshDomainAndTlsForAsset("dom_example")
            val refreshedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { state ->
                        val item = state.assets.firstOrNull { it.id == "dom_example" }
                        item?.tlsExpiresAtEpochMillis != null && item.rdapSyncedAtEpochMillis != null &&
                            "dom_example" !in state.refreshingDomainAssetIds
                    }
                }
            }
            val updated = refreshedState.assets.first { it.id == "dom_example" }
            assertEquals("Cloudflare, Inc.", updated.registrarName)
            assertNotNull(updated.tlsExpiresAtEpochMillis)
            assertNull(updated.tlsCheckError)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R8-3 auto-ingests registrar domain renewal mail exclusively to DOMAIN_CERT asset without touching VPS or SSH vault`() = runTest {
        E2eTestHarness().use { harness ->
            val domainAsset = harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "dom_1",
                    nickname = "pulse-edge.dev",
                    providerName = "Cloudflare, Inc.",
                    specsSummary = "主域名",
                    region = "",
                    renewalAmount = "10.44",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2027-05-01",
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "pulse-edge.dev",
                    registrarName = "Cloudflare, Inc.",
                ),
            )
            val vpsAsset = harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "vps_1",
                    nickname = "HK App Server",
                    providerName = "DMIT",
                    specsSummary = "2C / 2G",
                    region = "HK",
                    renewalAmount = "39.90",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-12-01",
                    notes = "Hosts https://pulse-edge.dev reverse proxy",
                    assetType = FleetAssetType.VPS,
                ),
            )

            val mailJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_and_billing.json"),
            ).jsonObject
            val domNode = mailJson.getValue("domainRenewalDetail").jsonObject
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

            val records = harness.vpsMailAutoIngestService.processIncomingSummaries(listOf(domSummary))
            assertEquals(1, records.size)
            assertEquals(domainAsset.id, records.single().assetId)
            assertEquals("2028-05-01", records.single().newExpiryDateIso)

            val allAssets = harness.fleetRepository.assetsFlow.value
            assertEquals("2028-05-01", allAssets.first { it.id == domainAsset.id }.expiryDateIso)
            assertEquals("2026-12-01", allAssets.first { it.id == vpsAsset.id }.expiryDateIso)
            assertTrue(harness.sshVaultRepository.serversFlow.value.isEmpty())
        }
    }

    @Test
    fun `E2E-T1-R8-4 aggregates combined VPS plus Domain annualized and monthly CNY cost with live FX and filters`() = runTest {
        E2eTestHarness().use { harness ->
            harness.externalDispatcher.register("GET", "/fx/latest") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "amount": 1.0,
                          "base": "CNY",
                          "date": "2026-11-25",
                          "rates": {
                            "USD": 0.13888889,
                            "EUR": 0.125
                          }
                        }
                        """.trimIndent(),
                    )
            }

            // Force online FX refresh before starting VM
            harness.exchangeRateRepository.refreshOnline()

            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "vps_usd",
                    nickname = "US KVM",
                    providerName = "RackNerd",
                    specsSummary = "2C/2G",
                    region = "LAX",
                    renewalAmount = "10.00",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-01-10",
                    assetType = FleetAssetType.VPS,
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "dom_eur",
                    nickname = "nodeseek.eu",
                    providerName = "OVH",
                    specsSummary = "域名",
                    region = "",
                    renewalAmount = "8.00",
                    currency = ValuationCurrency.EUR,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2025-12-10",
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "nodeseek.eu",
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "dom_cny",
                    nickname = "anode.cn",
                    providerName = "阿里云",
                    specsSummary = "域名",
                    region = "",
                    renewalAmount = "35.00",
                    currency = ValuationCurrency.CNY,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-02-01",
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "anode.cn",
                ),
            )

            val container = harness.buildAppContainer()
            val vm = harness.manage(
                MyServersViewModel(
                    fleetRepository = container.fleetRepository,
                    exchangeRateRepository = container.exchangeRateRepository,
                    todayProvider = harness.clock::todayShanghai,
                    nowMillisProvider = harness.clock::nowMillis,
                    initialTab = MyServersTab.ASSETS,
                    computationDispatcher = Dispatchers.Default,
                    domainRdapClient = container.domainRdapClient,
                    tlsCertificateInspector = container.tlsCertificateInspector,
                ),
            )

            val loadedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.assets.size == 3 && it.portfolioSummary.totalAssetCount == 3 }
                }
            }
            val summary = loadedState.portfolioSummary
            assertEquals(1, summary.vpsCount)
            assertEquals(2, summary.domainCount)
            assertEquals(3, summary.totalAssetCount)
            assertEquals(summary.vpsAnnualizedCostCny.add(summary.domainAnnualizedCostCny), summary.totalAnnualizedCostCny)
            assertEquals(summary.vpsMonthlyCostCny.add(summary.domainMonthlyCostCny), summary.totalMonthlyCostCny)
            assertTrue(summary.vpsAnnualizedCostCny > BigDecimal.ZERO)
            assertTrue(summary.domainAnnualizedCostCny > BigDecimal.ZERO)

            // Upcoming renewals sorted ascending by daysRemaining
            val days = summary.upcomingRenewals.map { it.daysRemaining }
            assertEquals(days.sorted(), days)
            assertEquals("dom_eur", summary.upcomingRenewals.first().asset.id)

            // AssetCategoryFilter partitions cleanly
            assertEquals(3, loadedState.filteredAssets.size)
            vm.selectAssetCategoryFilter(AssetCategoryFilter.VPS)
            val vpsFiltered = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.assetCategoryFilter == AssetCategoryFilter.VPS } }
            }
            assertEquals(listOf("vps_usd"), vpsFiltered.filteredAssets.map { it.id })

            vm.selectAssetCategoryFilter(AssetCategoryFilter.DOMAIN_CERT)
            val domFiltered = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.assetCategoryFilter == AssetCategoryFilter.DOMAIN_CERT } }
            }
            assertEquals(setOf("dom_eur", "dom_cny"), domFiltered.filteredAssets.map { it.id }.toSet())
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R8-5 persists DOMAIN_CERT fields across cold reload and backup round-trip while forcing null linkedProbeKey`() = runTest {
        E2eTestHarness().use { harness ->
            val domainRecord = VpsAssetRecord(
                id = "dom_persist",
                nickname = "anode.dev",
                providerName = "Cloudflare",
                specsSummary = "主站",
                region = "Global",
                renewalAmount = "12.00",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2027-06-01",
                linkedProbeKey = "panel1:should-be-cleared",
                assetType = FleetAssetType.DOMAIN_CERT,
                domainName = "anode.dev",
                registrarName = "Cloudflare, Inc.",
                domainCreatedIso = "2022-06-01",
                rdapSyncedAtEpochMillis = 1_764_000_000_000L,
                rdapStatusTags = listOf("client transfer prohibited"),
                tlsHost = "app.anode.dev",
                tlsPort = 8443,
                tlsExpiresAtEpochMillis = 1_770_000_000_000L,
                tlsIssuer = "Let's Encrypt",
                tlsSubject = "app.anode.dev",
                tlsCheckedAtEpochMillis = 1_764_000_000_000L,
            )
            val saved = harness.fleetRepository.upsertAsset(domainRecord)
            assertNull(saved.linkedProbeKey)

            val reloadedRepo = FleetRepositoryImpl(
                storeFile = harness.fleetStoreFile,
                tokenCipher = harness.fleetCipher,
                nowMillisProvider = harness.clock::nowMillis,
            )
            reloadedRepo.loadPersistedState()
            val reloaded = reloadedRepo.assetsFlow.value.single()
            assertEquals(FleetAssetType.DOMAIN_CERT, reloaded.assetType)
            assertEquals("anode.dev", reloaded.domainName)
            assertEquals("Cloudflare, Inc.", reloaded.registrarName)
            assertEquals("2022-06-01", reloaded.domainCreatedIso)
            assertEquals(listOf("client transfer prohibited"), reloaded.rdapStatusTags)
            assertEquals("app.anode.dev", reloaded.tlsHost)
            assertEquals(8443, reloaded.tlsPort)
            assertEquals(1_770_000_000_000L, reloaded.tlsExpiresAtEpochMillis)
            assertEquals("Let's Encrypt", reloaded.tlsIssuer)
            assertEquals("app.anode.dev", reloaded.tlsSubject)
            assertNull(reloaded.linkedProbeKey)

            // Also verify Encrypted Full Backup v2 round-trip preserves DOMAIN_CERT fields
            val backupMgr = harness.createBackupManager()
            val exported = backupMgr.exportEncryptedFullBackup("FleetPass!2026").getOrThrow()
            reloadedRepo.deleteAsset("dom_persist")
            harness.fleetRepository.deleteAsset("dom_persist")
            assertTrue(harness.fleetRepository.assetsFlow.value.isEmpty())

            val restored = backupMgr.restoreEncryptedFullBackup(
                rawContent = exported.rawEnvelopeJson,
                passphrase = "FleetPass!2026",
                strategy = RestoreStrategy.OVERWRITE,
            ).getOrThrow()
            assertEquals(1, restored.fleetAssetsImported)
            val fromBackup = harness.fleetRepository.assetsFlow.value.single()
            assertEquals(FleetAssetType.DOMAIN_CERT, fromBackup.assetType)
            assertEquals("anode.dev", fromBackup.domainName)
            assertEquals(8443, fromBackup.tlsPort)
            assertEquals("Let's Encrypt", fromBackup.tlsIssuer)
        }
    }

    @Test
    fun `E2E-T2-R8-1 loads legacy fleet_store json without domain or TLS fields and migrates routeTag cleanly`() = runTest {
        E2eTestHarness().use { harness ->
            val legacyJson = """
                {
                  "version": 2,
                  "panels": [],
                  "assets": [
                    {
                      "id": "legacy_vps_1",
                      "nickname": "瓦工 GIA",
                      "providerName": "BandwagonHost",
                      "specsSummary": "2C / 1G",
                      "region": "DC6",
                      "routeTag": "CN2 GIA / 9929",
                      "renewalAmount": "49.90",
                      "currency": "USD",
                      "cyclePreset": "ANNUAL",
                      "expiryDateIso": "2026-08-01"
                    }
                  ]
                }
            """.trimIndent()
            harness.fleetStoreFile.writeText(legacyJson, Charsets.UTF_8)

            val repo = FleetRepositoryImpl(
                storeFile = harness.fleetStoreFile,
                tokenCipher = harness.fleetCipher,
                nowMillisProvider = harness.clock::nowMillis,
            )
            repo.loadPersistedState()
            val loaded = repo.assetsFlow.value.single()
            assertEquals(FleetAssetType.VPS, loaded.assetType)
            assertNull(loaded.domainName)
            assertEquals("", loaded.registrarName)
            assertNull(loaded.tlsExpiresAtEpochMillis)
            assertEquals(listOf("CN2 GIA", "9929"), loaded.resolvedTags)
        }
    }

    @Test
    fun `E2E-T2-R8-2 handles RDAP 404, 500 IANA bootstrap fallback, malformed JSON, and subdomain parent fallback`() = runTest {
        E2eTestHarness().use { harness ->
            val rdapJson = E2eFixtures.loadText("domains/rdap_domain_example.json")
            val bootstrapJson = """
                {
                  "services": [
                    [
                      ["org", "net", "io"],
                      ["${harness.externalServer.url("/bootstrap_rdap/")}"]
                    ]
                  ]
                }
            """.trimIndent()

            harness.externalDispatcher.register("GET", "/rdap/domain/missing.org") {
                MockResponse().setResponseCode(404).setBody("""{"errorCode":404}""")
            }
            harness.externalDispatcher.register("GET", "/rdap/domain/broken.org") {
                MockResponse().setResponseCode(200).setBody("{bad json")
            }
            harness.externalDispatcher.register("GET", "/rdap/domain/app.example.com") {
                MockResponse().setResponseCode(404).setBody("""{"errorCode":404}""")
            }
            harness.externalDispatcher.register("GET", "/rdap/domain/example.com") {
                MockResponse().setResponseCode(200).setBody(rdapJson)
            }
            harness.externalDispatcher.register("GET", "/rdap/domain/fallback.io") {
                MockResponse().setResponseCode(500).setBody("""{"error":"primary down"}""")
            }
            harness.externalDispatcher.register("GET", "/iana/rdap/dns.json") {
                MockResponse().setResponseCode(200).setBody(bootstrapJson)
            }
            harness.externalDispatcher.register("GET", "/bootstrap_rdap/domain/fallback.io") {
                MockResponse().setResponseCode(200).setBody(
                    rdapJson.replace("\"example.com\"", "\"fallback.io\""),
                )
            }

            // Direct client checks for IANA bootstrap & subdomain fallback & malformed JSON
            val bootstrapRes = harness.domainRdapClient.lookupDomain("fallback.io")
            assertTrue(bootstrapRes.isSuccess)
            assertEquals("fallback.io", bootstrapRes.getOrThrow().domainName)
            assertEquals("Cloudflare, Inc.", bootstrapRes.getOrThrow().registrarName)

            val subdomainRes = harness.domainRdapClient.lookupDomain("app.example.com")
            assertTrue(subdomainRes.isSuccess)
            assertEquals("example.com", subdomainRes.getOrThrow().domainName)

            val malformedRes = harness.domainRdapClient.lookupDomain("broken.org")
            assertTrue(malformedRes.isFailure)

            // ViewModel keeps draft intact on 404 so user can still fill expiry manually and save
            val container = harness.buildAppContainer()
            val vm = harness.manage(
                MyServersViewModel(
                    fleetRepository = container.fleetRepository,
                    exchangeRateRepository = container.exchangeRateRepository,
                    todayProvider = harness.clock::todayShanghai,
                    nowMillisProvider = harness.clock::nowMillis,
                    initialTab = MyServersTab.ASSETS,
                    computationDispatcher = Dispatchers.Default,
                    domainRdapClient = container.domainRdapClient,
                    tlsCertificateInspector = container.tlsCertificateInspector,
                ),
            )
            vm.openAddDomainAssetSheet()
            vm.lookupDomainRdapForDraft("missing.org")
            val errState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isLookingUpRdap &&
                            it.rdapLookupError != null &&
                            it.assetEditorDraft != null
                    }
                }
            }
            assertNotNull(errState.assetEditorDraft)
            val errMsg = errState.rdapLookupError ?: ""
            assertTrue(errMsg.contains("404") || errMsg.contains("未查询到"))

            vm.saveAsset(
                VpsAssetRecord(
                    id = "manual_missing",
                    nickname = "missing.org",
                    providerName = "Porkbun",
                    specsSummary = "手动录入",
                    region = "",
                    renewalAmount = "9.73",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2027-01-01",
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "missing.org",
                ),
            )
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.assets.any { a -> a.id == "manual_missing" } }
                }
            }
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R8-3 detects expired TLS cert, wrong-SAN hostname mismatch, and closed port failure gracefully`() = runTest {
        val expiredFixture = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
            commonName = "expired.example.com",
            sanDnsNames = listOf("expired.example.com"),
            startDateOffsetDays = -30,
            validityDays = 7,
        )
        E2eTestHarness(
            initialEpochMillis = maxOf(System.currentTimeMillis(), expiredFixture.certificate.notAfter.time + 1_000L),
        ).use { harness ->
            val expiredServer = harness.track(E2eBitmapAndTlsFixtures.LocalTlsServer(expiredFixture))
            harness.tlsSocketOpsOverride = E2eBitmapAndTlsFixtures.LoopbackTlsSocketOps(
                portOverride = expiredServer.port,
                trustManager = expiredFixture.trustManager,
                enforceHttpsHostnameMatch = true,
            )

            val container = harness.buildAppContainer()
            val vm = harness.manage(
                MyServersViewModel(
                    fleetRepository = container.fleetRepository,
                    exchangeRateRepository = container.exchangeRateRepository,
                    todayProvider = harness.clock::todayShanghai,
                    nowMillisProvider = harness.clock::nowMillis,
                    initialTab = MyServersTab.ASSETS,
                    computationDispatcher = Dispatchers.Default,
                    domainRdapClient = container.domainRdapClient,
                    tlsCertificateInspector = container.tlsCertificateInspector,
                ),
            )

            vm.openAddDomainAssetSheet()
            vm.checkTlsForDraft("expired.example.com", expiredServer.port)
            val expiredState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val draft = it.assetEditorDraft
                        !it.isCheckingTls &&
                            draft?.tlsCheckRevision == 1 &&
                            draft.tlsCheckError != null &&
                            it.tlsCheckResultMessage?.contains("已过期") == true
                    }
                }
            }
            val expiredDraft = expiredState.assetEditorDraft!!
            assertTrue(expiredDraft.tlsCheckError?.contains("已过期") == true)
            assertTrue(expiredState.tlsCheckResultMessage?.contains("已过期") == true)

            val inspectDirect = harness.tlsCertificateInspector.inspect("expired.example.com", expiredServer.port).getOrThrow()
            assertTrue(inspectDirect.isExpired)
            assertTrue(inspectDirect.daysRemaining < 0L)

            // Wrong-SAN hostname mismatch against SAN=[valid.example.com, alt.example.com]
            val wrongSanFixture = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
                commonName = "valid.example.com",
                sanDnsNames = listOf("valid.example.com", "alt.example.com"),
                sanIps = emptyList(),
                validityDays = 30,
            )
            harness.clock.setEpochMillis(
                maxOf(harness.clock.nowMillis(), wrongSanFixture.certificate.notBefore.time + 1_000L),
            )
            val wrongSanServer = harness.track(E2eBitmapAndTlsFixtures.LocalTlsServer(wrongSanFixture))
            harness.tlsSocketOpsOverride = E2eBitmapAndTlsFixtures.LoopbackTlsSocketOps(
                portOverride = wrongSanServer.port,
                trustManager = wrongSanFixture.trustManager,
                enforceHttpsHostnameMatch = true,
            )
            val wrongSanDirect = harness.tlsCertificateInspector.inspect("wrong-san.example.com", wrongSanServer.port).getOrThrow()
            assertFalse(wrongSanDirect.isTrusted)
            assertFalse(wrongSanDirect.isExpired)
            assertNotNull(wrongSanDirect.warningMessage)
            assertTrue(wrongSanDirect.warningMessage!!.isNotBlank())
            assertEquals(listOf("valid.example.com", "alt.example.com"), wrongSanDirect.sanDnsNames)

            vm.checkTlsForDraft("wrong-san.example.com", wrongSanServer.port)
            val wrongSanState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val draft = it.assetEditorDraft
                        !it.isCheckingTls &&
                            draft?.tlsCheckRevision == 2 &&
                            draft.tlsCheckError != null &&
                            it.tlsCheckResultMessage?.contains("未受信任") == true
                    }
                }
            }
            assertNotNull(wrongSanState.assetEditorDraft?.tlsCheckError)
            assertTrue(wrongSanState.tlsCheckResultMessage?.contains("未受信任") == true)

            // Also verify wrong-SAN detection when loopback cert includes IP:127.0.0.1
            val loopbackSanFixture = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
                commonName = "valid.example.com",
                sanDnsNames = listOf("valid.example.com", "alt.example.com"),
                validityDays = 30,
            )
            harness.clock.setEpochMillis(
                maxOf(harness.clock.nowMillis(), loopbackSanFixture.certificate.notBefore.time + 1_000L),
            )
            val loopbackSanServer = harness.track(E2eBitmapAndTlsFixtures.LocalTlsServer(loopbackSanFixture))
            harness.tlsSocketOpsOverride = E2eBitmapAndTlsFixtures.LoopbackTlsSocketOps(
                portOverride = loopbackSanServer.port,
                trustManager = loopbackSanFixture.trustManager,
                enforceHttpsHostnameMatch = true,
            )
            val loopbackWrongSan = harness.tlsCertificateInspector.inspect("wrong-san.example.com", loopbackSanServer.port).getOrThrow()
            assertFalse(loopbackWrongSan.isTrusted)
            assertNotNull(loopbackWrongSan.warningMessage)

            // Closed ephemeral port connection-refused
            val closedPort = ServerSocket(0).use { it.localPort }
            harness.tlsSocketOpsOverride = E2eBitmapAndTlsFixtures.LoopbackTlsSocketOps(
                portOverride = closedPort,
                trustManager = null,
            )
            vm.checkTlsForDraft("unreachable.example.com", closedPort)
            val failedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val draft = it.assetEditorDraft
                        !it.isCheckingTls &&
                            draft?.tlsCheckRevision == 3 &&
                            draft.tlsCheckError != null &&
                            it.tlsCheckResultMessage != null
                    }
                }
            }
            assertNotNull(failedState.assetEditorDraft?.tlsCheckError)
            assertNotNull(failedState.tlsCheckResultMessage)
        }
    }

    @Test
    fun `E2E-T2-R8-4 deduplicates domain renewal mail across cold reload, ignores non-billing notices, and blocks expiry regression`() = runTest {
        E2eTestHarness().use { harness ->
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "dom_dedupe",
                    nickname = "pulse-edge.dev",
                    providerName = "Cloudflare, Inc.",
                    specsSummary = "主站",
                    region = "",
                    renewalAmount = "10.44",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2027-05-01",
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "pulse-edge.dev",
                ),
            )

            val mailJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_and_billing.json"),
            ).jsonObject
            val domNode = mailJson.getValue("domainRenewalDetail").jsonObject
                .getValue("data").jsonObject
                .getValue("mail").jsonObject
            val bodyText = domNode.getValue("bodyText").jsonPrimitive.content.replace("example.com", "pulse-edge.dev")
            val summary = SeekMailSummary(
                id = domNode.getValue("id").jsonPrimitive.content.toLong(),
                mailboxId = domNode.getValue("mailboxId").jsonPrimitive.content.toLong(),
                fromAddress = domNode.getValue("fromAddress").jsonPrimitive.content,
                fromName = domNode.getValue("fromName").jsonPrimitive.content,
                toAddress = domNode.getValue("toAddress").jsonPrimitive.content,
                subject = domNode.getValue("subject").jsonPrimitive.content.replace("example.com", "pulse-edge.dev"),
                snippet = bodyText,
                messageId = domNode.getValue("messageId").jsonPrimitive.content,
            )
            val detail = SeekMailDetail(summary = summary, bodyText = bodyText)

            // 1. First summary pass updates 2027-05-01 -> 2028-05-01
            val firstPass = harness.vpsMailAutoIngestService.processIncomingSummaries(listOf(summary))
            assertEquals(1, firstPass.size)
            assertEquals("2028-05-01", harness.fleetRepository.assetsFlow.value.single().expiryDateIso)

            // 2. Re-running processMailDetail & ingestMail on same mail must NOT advance again
            val detailPass = harness.vpsMailAutoIngestService.processMailDetail(detail)
            assertTrue(detailPass.isEmpty())

            val parsed = VpsBillingMailParser.parseDetail(detail)!!
            val ingestPass = harness.vpsMailAutoIngestService.ingestMail(summary, parsed).getOrThrow()
            assertEquals("2028-05-01", ingestPass.asset.expiryDateIso)

            // 3. Cold reload on vps_mail_ingest_state.json retains dedupe state
            val reloadedIngest = VpsMailAutoIngestServiceImpl(
                fleetRepository = harness.fleetRepository,
                sshVaultRepository = harness.sshVaultRepository,
                stateFile = harness.vpsMailIngestStateFile,
                todayProvider = harness.clock::todayShanghai,
                nowMillisProvider = harness.clock::nowMillis,
            )
            assertTrue(reloadedIngest.processIncomingSummaries(listOf(summary)).isEmpty())
            assertTrue(reloadedIngest.processMailDetail(detail).isEmpty())

            // 4. Non-billing Cloudflare alert is ignored
            assertNull(
                VpsBillingMailParser.parse(
                    subject = "Cloudflare Workers usage alert for pulse-edge.dev",
                    bodyText = "Your Workers script exceeded 80% of free daily requests.",
                    fromName = "Cloudflare",
                    fromAddress = "noreply@cloudflare.com",
                ),
            )

            // 5. Historical older expiry mail (2026-01-01) never regresses 2028-05-01
            val olderSummary = summary.copy(
                id = 999L,
                messageId = "<old-dom-999@cloudflare.com>",
                snippet = "Domain: pulse-edge.dev\nPayment Status: Paid\nAmount Paid: \$10.44 USD\nNew Expiration Date: 2026-01-01",
            )
            reloadedIngest.processIncomingSummaries(listOf(olderSummary))
            assertEquals("2028-05-01", harness.fleetRepository.assetsFlow.value.single().expiryDateIso)
        }
    }

    @Test
    fun `E2E-T2-R8-5 isolates DomainRdapClient from forum cookies and NodeSeek auth headers even when built with forumOkHttp`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                username = "alice101",
                sessionCookie = "secret_ns_cookie",
                cfClearance = "secret_cf_clearance",
            )

            val rdapJson = E2eFixtures.loadText("domains/rdap_domain_example.json")
            val bootstrapJson = """
                {
                  "services": [
                    [
                      ["com"],
                      ["${harness.externalServer.url("/bootstrap_rdap/")}"]
                    ]
                  ]
                }
            """.trimIndent()

            harness.externalDispatcher.register("GET", "/rdap/domain/example.com") {
                MockResponse().setResponseCode(500).setBody("""{"error":"retry bootstrap"}""")
            }
            harness.externalDispatcher.register("GET", "/iana/rdap/dns.json") {
                MockResponse().setResponseCode(200).setBody(bootstrapJson)
            }
            harness.externalDispatcher.register("GET", "/bootstrap_rdap/domain/example.com") {
                MockResponse().setResponseCode(200).setBody(rdapJson)
            }

            // Explicitly pass cookie-bearing forumOkHttp to verify sanitizeCookieFreeClient strips all interceptors & cookies
            val clientBuiltFromForumHttp = DomainRdapClient(
                baseHttpClient = harness.forumOkHttp,
                primaryEndpointTemplate = harness.externalServer.url("/rdap/domain/{domain}").toString(),
                ianaBootstrapUrl = harness.externalServer.url("/iana/rdap/dns.json").toString(),
                clockMillis = harness.clock::nowMillis,
            )

            val container = harness.buildAppContainer(
                overrides = mapOf("domainRdapClient" to clientBuiltFromForumHttp),
            )
            val vm = harness.manage(
                MyServersViewModel(
                    fleetRepository = container.fleetRepository,
                    exchangeRateRepository = container.exchangeRateRepository,
                    todayProvider = harness.clock::todayShanghai,
                    nowMillisProvider = harness.clock::nowMillis,
                    initialTab = MyServersTab.ASSETS,
                    computationDispatcher = Dispatchers.Default,
                    domainRdapClient = container.domainRdapClient,
                    tlsCertificateInspector = container.tlsCertificateInspector,
                ),
            )
            vm.openAddDomainAssetSheet()
            vm.lookupDomainRdapForDraft("example.com")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isLookingUpRdap && it.assetEditorDraft?.rdapSyncRevision == 1 }
                }
            }

            val externalReqs = harness.externalDispatcher.recordedRequests()
            val rdapReqs = externalReqs.filter {
                val p = it.path.orEmpty()
                p.contains("rdap") || p.contains("/iana/")
            }
            assertEquals(3, rdapReqs.size)
            for (req in externalReqs) {
                assertNull(req.getHeader("Cookie"))
                assertNull(req.getHeader("x-dynamic-sign"))
                assertNull(req.getHeader("csrf-token"))
                assertNull(req.getHeader("x-csrf-challenge"))
            }
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}
