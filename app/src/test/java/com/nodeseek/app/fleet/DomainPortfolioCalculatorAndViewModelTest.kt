package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.DomainRdapClient
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.JvmTlsCertificateInspector
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.valuation.CurrencyRateQuote
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.fleet.DomainRdapResult
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.TlsPeerChain
import com.nodeseek.app.domain.fleet.TlsSocketOps
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.fleet.AssetCategoryFilter
import com.nodeseek.app.ui.fleet.MyServersTab
import com.nodeseek.app.ui.fleet.MyServersViewModel
import com.nodeseek.app.ui.fleet.resolveTlsHostAfterRdapSync
import com.nodeseek.app.ui.fleet.syncDomainAndTlsHostOnEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.util.Date
import javax.security.auth.x500.X500Principal

@OptIn(ExperimentalCoroutinesApi::class)
class DomainPortfolioCalculatorAndViewModelTest {

    private lateinit var tempDir: File
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("domain_vm_test").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `summarize separates VPS and Domain costs while reporting combined holding cost`() {
        val fx = FxRatesState(
            quotes = mapOf(
                ValuationCurrency.CNY to CurrencyRateQuote(ValuationCurrency.CNY, BigDecimal("1.00"), FxRateSource.OFFLINE_DEFAULT),
                ValuationCurrency.USD to CurrencyRateQuote(ValuationCurrency.USD, BigDecimal("7.00"), FxRateSource.OFFLINE_DEFAULT),
                ValuationCurrency.EUR to CurrencyRateQuote(ValuationCurrency.EUR, BigDecimal("8.00"), FxRateSource.OFFLINE_DEFAULT),
            ),
        )
        val today = LocalDate.of(2026, 6, 1)
        val assets = listOf(
            VpsAssetRecord(
                id = "vps_1",
                nickname = "DMIT Pro",
                providerName = "DMIT",
                specsSummary = "1C/1G",
                region = "LAX",
                renewalAmount = "100.00",
                currency = ValuationCurrency.USD, // 700 CNY/yr
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2026-12-01",
                assetType = FleetAssetType.VPS,
            ),
            VpsAssetRecord(
                id = "vps_2",
                nickname = "Netcup RS",
                providerName = "Netcup",
                specsSummary = "4C/8G",
                region = "DE",
                renewalAmount = "10.00",
                currency = ValuationCurrency.EUR, // 80/mo = 960 CNY/yr
                cyclePreset = BillingCyclePreset.MONTHLY,
                expiryDateIso = "2026-06-05",
                assetType = FleetAssetType.VPS,
            ),
            VpsAssetRecord(
                id = "dom_1",
                nickname = "anode.dev",
                providerName = "Porkbun",
                specsSummary = "",
                region = "",
                renewalAmount = "12.00",
                currency = ValuationCurrency.USD, // 84 CNY/yr
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2027-01-15",
                assetType = FleetAssetType.DOMAIN_CERT,
                domainName = "anode.dev",
                registrarName = "Porkbun",
            ),
            VpsAssetRecord(
                id = "dom_2",
                nickname = "nodeseek.cn",
                providerName = "阿里云万网",
                specsSummary = "",
                region = "",
                renewalAmount = "72.00",
                currency = ValuationCurrency.CNY, // 72 CNY / 2yr = 36 CNY/yr
                cyclePreset = BillingCyclePreset.BIENNIAL,
                expiryDateIso = "2026-06-04",
                assetType = FleetAssetType.DOMAIN_CERT,
                domainName = "nodeseek.cn",
                registrarName = "阿里云万网",
            ),
        )

        val summary = FleetPortfolioCalculator.summarize(assets, emptyList(), fx, today)

        assertEquals(2, summary.vpsCount)
        assertEquals(2, summary.totalMachineCount) // backwards compatible: VPS count
        assertEquals(2, summary.domainCount)
        assertEquals(4, summary.totalAssetCount)

        // VPS: 700 + 960 = 1660.00 / yr, Domain: 84 + 36 = 120.00 / yr, Combined: 1780.00 / yr
        assertEquals(BigDecimal("1660.00"), summary.vpsAnnualizedCostCny)
        assertEquals(BigDecimal("120.00"), summary.domainAnnualizedCostCny)
        assertEquals(BigDecimal("1780.00"), summary.totalAnnualizedCostCny)
        assertEquals(summary.vpsAnnualizedCostCny.add(summary.domainAnnualizedCostCny), summary.totalAnnualizedCostCny)

        assertEquals(BigDecimal("148.33"), summary.totalMonthlyCostCny)
        // Nearest due sorted first: dom_2 (3d) then vps_2 (4d)
        assertEquals("dom_2", summary.upcomingRenewals.first().asset.id)
        assertEquals(2, summary.dueSoonRenewalCount)
    }

    @Test
    fun `MyServersViewModel supports category filter, draft RDAP and TLS inspection, and refreshing saved domain asset`() =
        runTest(testDispatcher) {
            val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val fleetRepo = FleetRepositoryImpl(
                storeFile = File(tempDir, "fleet.json"),
                tokenCipher = cipher,
                ioDispatcher = testDispatcher,
            )
            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempDir, "fx.json"),
                ioDispatcher = testDispatcher,
            )
            val today = LocalDate.of(2026, 6, 1)
            val nowMillis = today.atTime(12, 0).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()

            val fakeRdap = object : DomainRdapClient(ioDispatcher = testDispatcher) {
                override suspend fun lookupDomain(rawDomain: String): Result<DomainRdapResult> =
                    Result.success(
                        DomainRdapResult(
                            domainName = "demo.dev",
                            registrarName = "Porkbun LLC",
                            registrarIanaId = "472",
                            expiryDateIso = "2028-08-01",
                            registrationDateIso = "2020-08-01",
                            statuses = listOf("clientTransferProhibited"),
                            sourceUrl = "https://rdap.org/domain/demo.dev",
                            fetchedAtEpochMillis = nowMillis,
                        ),
                    )
            }
            val fakeTls = JvmTlsCertificateInspector(
                socketOps = object : TlsSocketOps {
                    override suspend fun fetchPeerCertificates(host: String, port: Int, timeoutMillis: Int): TlsPeerChain {
                        val exp = today.plusDays(75).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()
                        return TlsPeerChain(
                            certificates = listOf(simpleCert(nowMillis - 10_000L, exp)),
                            isTrusted = true,
                        )
                    }
                },
                nowMillisProvider = { nowMillis },
            )

            val vm = MyServersViewModel(
                fleetRepository = fleetRepo,
                exchangeRateRepository = fxRepo,
                todayProvider = { today },
                nowMillisProvider = { nowMillis },
                initialTab = MyServersTab.ASSETS,
                computationDispatcher = testDispatcher,
                domainRdapClient = fakeRdap,
                tlsCertificateInspector = fakeTls,
            )
            advanceUntilIdle()

            vm.openAddDomainAssetSheet()
            advanceUntilIdle()
            vm.lookupDomainRdapForDraft("demo.dev")
            advanceUntilIdle()

            val draftAfterRdap = vm.uiState.value.assetEditorDraft
            assertNotNull(draftAfterRdap)
            assertEquals("demo.dev", draftAfterRdap!!.domainName)
            assertEquals("Porkbun LLC", draftAfterRdap.registrarName)
            assertEquals("2028-08-01", draftAfterRdap.expiryDateIso)

            vm.checkTlsForDraft("demo.dev", 443)
            advanceUntilIdle()
            val draftAfterTls = vm.uiState.value.assetEditorDraft!!
            assertEquals("Let's Encrypt", draftAfterTls.tlsIssuer)
            assertNull(draftAfterTls.tlsCheckError)
            assertTrue(vm.uiState.value.tlsCheckResultMessage!!.contains("75"))

            // Save domain asset + 1 VPS asset and test category filtering
            fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "vps_only",
                    nickname = "HK VPS",
                    providerName = "DMIT",
                    specsSummary = "1C/1G",
                    region = "HK",
                    renewalAmount = "50",
                    expiryDateIso = "2027-01-01",
                    assetType = FleetAssetType.VPS,
                ),
            )
            vm.saveAsset(
                VpsAssetRecord(
                    id = "dom_saved",
                    nickname = "demo.dev",
                    providerName = "Porkbun LLC",
                    specsSummary = "",
                    region = "",
                    renewalAmount = "12",
                    expiryDateIso = "2028-08-01",
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "demo.dev",
                    registrarName = "Porkbun LLC",
                ),
            )
            advanceUntilIdle()

            assertEquals(2, vm.uiState.value.filteredAssets.size)
            vm.selectAssetCategoryFilter(AssetCategoryFilter.DOMAIN_CERT)
            advanceUntilIdle()
            assertEquals(listOf("dom_saved"), vm.uiState.value.filteredAssets.map { it.id })
            vm.selectAssetCategoryFilter(AssetCategoryFilter.VPS)
            advanceUntilIdle()
            assertEquals(listOf("vps_only"), vm.uiState.value.filteredAssets.map { it.id })

            // Refresh RDAP & TLS directly on saved asset
            vm.refreshDomainAndTlsForAsset("dom_saved")
            advanceUntilIdle()
            val refreshed = fleetRepo.assetsFlow.value.first { it.id == "dom_saved" }
            assertNotNull(refreshed.tlsExpiresAtEpochMillis)
            assertEquals("Let's Encrypt", refreshed.tlsIssuer)
        }

    @Test
    fun `AppContainer default getters for M2b dependencies exist and do not throw`() {
        val defaultMethod = AppContainer::class.java.methods.map { it.name }.toSet()
        assertTrue("getBenchmarkBaselineRepository" in defaultMethod)
        assertTrue("getDomainRdapClient" in defaultMethod)
        assertTrue("getTlsCertificateInspector" in defaultMethod)
    }

    @Test
    fun `summarize enforces vps plus domain equals total without HALF_UP rounding divergence`() {
        val fx = FxRatesState(
            quotes = mapOf(
                ValuationCurrency.CNY to CurrencyRateQuote(ValuationCurrency.CNY, BigDecimal("1.00"), FxRateSource.OFFLINE_DEFAULT),
            ),
        )
        val today = LocalDate.of(2026, 6, 1)

        // Case 1: 120.05 ANNUAL VPS + 120.05 ANNUAL Domain (monthly raw = 10.00416... each -> 10.00 + 10.00 = 20.00)
        val annualPair = listOf(
            VpsAssetRecord(
                id = "v1",
                nickname = "V1",
                providerName = "P",
                specsSummary = "",
                region = "",
                renewalAmount = "120.05",
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2027-01-01",
                assetType = FleetAssetType.VPS,
            ),
            VpsAssetRecord(
                id = "d1",
                nickname = "D1",
                providerName = "R",
                specsSummary = "",
                region = "",
                renewalAmount = "120.05",
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2027-01-01",
                assetType = FleetAssetType.DOMAIN_CERT,
                domainName = "d1.com",
            ),
        )
        val s1 = FleetPortfolioCalculator.summarize(annualPair, emptyList(), fx, today)
        assertEquals(s1.vpsMonthlyCostCny.add(s1.domainMonthlyCostCny), s1.totalMonthlyCostCny)
        assertEquals(s1.vpsAnnualizedCostCny.add(s1.domainAnnualizedCostCny), s1.totalAnnualizedCostCny)

        // Case 2: 20.009 BIENNIAL VPS + 30.014 TRIENNIAL Domain (annual raw = 10.0045 + 10.00466... -> 10.00 + 10.00 = 20.00)
        val multiYearPair = listOf(
            VpsAssetRecord(
                id = "v2",
                nickname = "V2",
                providerName = "P",
                specsSummary = "",
                region = "",
                renewalAmount = "20.009",
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.BIENNIAL,
                expiryDateIso = "2028-01-01",
                assetType = FleetAssetType.VPS,
            ),
            VpsAssetRecord(
                id = "d2",
                nickname = "D2",
                providerName = "R",
                specsSummary = "",
                region = "",
                renewalAmount = "30.014",
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.TRIENNIAL,
                expiryDateIso = "2029-01-01",
                assetType = FleetAssetType.DOMAIN_CERT,
                domainName = "d2.com",
            ),
        )
        val s2 = FleetPortfolioCalculator.summarize(multiYearPair, emptyList(), fx, today)
        assertEquals(s2.vpsMonthlyCostCny.add(s2.domainMonthlyCostCny), s2.totalMonthlyCostCny)
        assertEquals(s2.vpsAnnualizedCostCny.add(s2.domainAnnualizedCostCny), s2.totalAnnualizedCostCny)
    }

    @Test
    fun `AssetEditorBottomSheet domain and tlsHost sync helpers track keystrokes and recover after RDAP`() {
        var domain = ""
        var tlsHost = ""
        for (typed in listOf("e", "ex", "exa", "example.com")) {
            val (nextDomain, nextTls) = syncDomainAndTlsHostOnEdit(domain, tlsHost, typed)
            domain = nextDomain
            tlsHost = nextTls
        }
        assertEquals("example.com", domain)
        assertEquals("example.com", tlsHost)

        // Custom tlsHost is preserved on subsequent domain edits
        val (editedDomain, preservedTls) = syncDomainAndTlsHostOnEdit(
            currentDomain = "example.com",
            currentTlsHost = "cdn.example.com",
            incomingDomain = "example.org",
        )
        assertEquals("example.org", editedDomain)
        assertEquals("cdn.example.com", preservedTls)

        // Partial/invalid tlsHost ("e") is overwritten after RDAP autofill
        assertEquals(
            "example.com",
            resolveTlsHostAfterRdapSync(
                currentTlsHost = "e",
                previousDomain = "example.com",
                previousSyncedDomain = "",
                incomingRdapTlsHost = "example.com",
            ),
        )
        // Custom valid tlsHost is kept after RDAP sync
        assertEquals(
            "cdn.example.com",
            resolveTlsHostAfterRdapSync(
                currentTlsHost = "cdn.example.com",
                previousDomain = "example.com",
                previousSyncedDomain = "example.com",
                incomingRdapTlsHost = "example.com",
            ),
        )
    }

    @Test
    fun `editing existing DOMAIN_CERT domain and running RDAP keeps updated tlsHost while preserving custom tlsHost`() =
        runTest(testDispatcher) {
            val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val fleetRepo = FleetRepositoryImpl(
                storeFile = File(tempDir, "fleet_edit_rdap.json"),
                tokenCipher = cipher,
                ioDispatcher = testDispatcher,
            )
            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempDir, "fx_edit_rdap.json"),
                ioDispatcher = testDispatcher,
            )
            val today = LocalDate.of(2026, 6, 1)
            val nowMillis = today.atTime(12, 0).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()

            val fakeRdap = object : DomainRdapClient(ioDispatcher = testDispatcher) {
                override suspend fun lookupDomain(rawDomain: String): Result<DomainRdapResult> {
                    val normalized = VpsAssetRecord.normalizeDomainName(rawDomain) ?: rawDomain
                    return Result.success(
                        DomainRdapResult(
                            domainName = normalized,
                            registrarName = "Cloudflare, Inc.",
                            registrarIanaId = "1910",
                            expiryDateIso = "2029-05-01",
                            registrationDateIso = "2021-05-01",
                            statuses = listOf("clientTransferProhibited"),
                            sourceUrl = "https://rdap.org/domain/$normalized",
                            fetchedAtEpochMillis = nowMillis,
                        ),
                    )
                }
            }

            val vm = MyServersViewModel(
                fleetRepository = fleetRepo,
                exchangeRateRepository = fxRepo,
                todayProvider = { today },
                nowMillisProvider = { nowMillis },
                initialTab = MyServersTab.ASSETS,
                computationDispatcher = testDispatcher,
                domainRdapClient = fakeRdap,
            )
            advanceUntilIdle()

            // Case 1: Existing DOMAIN_CERT with matching tlsHost ("foo.io" -> "foo.com")
            val existingMatching = VpsAssetRecord(
                id = "dom_existing_1",
                nickname = "foo.io",
                providerName = "Porkbun",
                specsSummary = "",
                region = "",
                renewalAmount = "10",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2027-05-01",
                assetType = FleetAssetType.DOMAIN_CERT,
                domainName = "foo.io",
                registrarName = "Porkbun",
                tlsHost = "foo.io",
            )
            vm.openEditAssetSheet(existingMatching)
            advanceUntilIdle()

            // Simulate user editing domain field from "foo.io" to "foo.com" in AssetEditorBottomSheet
            val (localDomainAfterEdit, localTlsAfterEdit) = syncDomainAndTlsHostOnEdit(
                currentDomain = existingMatching.domainName!!,
                currentTlsHost = existingMatching.tlsHost!!,
                incomingDomain = "foo.com",
            )
            assertEquals("foo.com", localDomainAfterEdit)
            assertEquals("foo.com", localTlsAfterEdit)

            // User triggers RDAP lookup for the edited domain "foo.com"
            vm.lookupDomainRdapForDraft("foo.com")
            advanceUntilIdle()

            val draftAfterRdap = vm.uiState.value.assetEditorDraft!!
            assertEquals("foo.com", draftAfterRdap.domainName)
            assertEquals("foo.com", draftAfterRdap.tlsHost)

            val resolvedTlsAfterRdap = resolveTlsHostAfterRdapSync(
                currentTlsHost = localTlsAfterEdit,
                previousDomain = localDomainAfterEdit.trim(),
                previousSyncedDomain = existingMatching.domainName.trim(),
                incomingRdapTlsHost = draftAfterRdap.domainName,
            )
            assertEquals("foo.com", resolvedTlsAfterRdap)

            // Case 2: Existing DOMAIN_CERT with custom tlsHost ("cdn.foo.io") stays "cdn.foo.io"
            val existingCustom = existingMatching.copy(
                id = "dom_existing_2",
                tlsHost = "cdn.foo.io",
            )
            vm.openEditAssetSheet(existingCustom)
            advanceUntilIdle()

            val (customLocalDomain, customLocalTls) = syncDomainAndTlsHostOnEdit(
                currentDomain = existingCustom.domainName!!,
                currentTlsHost = existingCustom.tlsHost!!,
                incomingDomain = "foo.com",
            )
            assertEquals("foo.com", customLocalDomain)
            assertEquals("cdn.foo.io", customLocalTls)

            vm.lookupDomainRdapForDraft("foo.com")
            advanceUntilIdle()

            val customDraftAfterRdap = vm.uiState.value.assetEditorDraft!!
            assertEquals("foo.com", customDraftAfterRdap.domainName)
            assertEquals("cdn.foo.io", customDraftAfterRdap.tlsHost)

            val customResolvedTls = resolveTlsHostAfterRdapSync(
                currentTlsHost = customLocalTls,
                previousDomain = customLocalDomain.trim(),
                previousSyncedDomain = existingCustom.domainName.trim(),
                incomingRdapTlsHost = customDraftAfterRdap.domainName,
            )
            assertEquals("cdn.foo.io", customResolvedTls)
        }

    @Test
    fun `MyServersViewModel discards stale RDAP draft response after closeAssetEditor and prevents resurrected deleted asset`() =
        runTest(testDispatcher) {
            val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val fleetRepo = FleetRepositoryImpl(
                storeFile = File(tempDir, "fleet_gap13.json"),
                tokenCipher = cipher,
                ioDispatcher = testDispatcher,
            )
            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempDir, "fx_gap13.json"),
                ioDispatcher = testDispatcher,
            )
            val today = LocalDate.of(2026, 6, 1)
            val nowMillis = today.atTime(12, 0).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()

            val draftGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val assetGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val fakeRdap = object : DomainRdapClient(ioDispatcher = testDispatcher) {
                override suspend fun lookupDomain(rawDomain: String): Result<DomainRdapResult> {
                    if (rawDomain == "slow-draft.dev") draftGate.await()
                    if (rawDomain == "deleted-asset.dev") assetGate.await()
                    return Result.success(
                        DomainRdapResult(
                            domainName = rawDomain,
                            registrarName = "Porkbun LLC",
                            registrarIanaId = "472",
                            expiryDateIso = "2028-08-01",
                            registrationDateIso = "2020-08-01",
                            statuses = listOf("ok"),
                            sourceUrl = "https://rdap.org/domain/$rawDomain",
                            fetchedAtEpochMillis = nowMillis,
                        ),
                    )
                }
            }
            val fakeTls = JvmTlsCertificateInspector(
                socketOps = object : TlsSocketOps {
                    override suspend fun fetchPeerCertificates(host: String, port: Int, timeoutMillis: Int): TlsPeerChain {
                        val exp = today.plusDays(75).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()
                        return TlsPeerChain(certificates = listOf(simpleCert(nowMillis - 10_000L, exp)), isTrusted = true)
                    }
                },
                nowMillisProvider = { nowMillis },
            )

            val vm = MyServersViewModel(
                fleetRepository = fleetRepo,
                exchangeRateRepository = fxRepo,
                todayProvider = { today },
                nowMillisProvider = { nowMillis },
                initialTab = MyServersTab.ASSETS,
                computationDispatcher = testDispatcher,
                domainRdapClient = fakeRdap,
                tlsCertificateInspector = fakeTls,
            )
            advanceUntilIdle()

            // 1. Draft closed mid-flight must NOT be re-opened by completing RDAP lookup
            vm.openAddDomainAssetSheet()
            vm.lookupDomainRdapForDraft("slow-draft.dev")
            vm.closeAssetEditor()
            draftGate.complete(Unit)
            advanceUntilIdle()
            assertNull(vm.uiState.value.assetEditorDraft)

            // 2. Deleted asset must NOT be resurrected when mid-flight refresh completes
            fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "dom_del",
                    nickname = "deleted-asset.dev",
                    providerName = "Porkbun",
                    specsSummary = "",
                    region = "",
                    renewalAmount = "10",
                    expiryDateIso = "2028-08-01",
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "deleted-asset.dev",
                ),
            )
            advanceUntilIdle()
            vm.refreshDomainAndTlsForAsset("dom_del")
            vm.deleteAsset("dom_del")
            advanceUntilIdle()
            assetGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(fleetRepo.assetsFlow.value.any { it.id == "dom_del" })
        }

    private fun simpleCert(notBeforeMillis: Long, notAfterMillis: Long): X509Certificate =
        object : X509Certificate() {
            override fun getNotBefore() = Date(notBeforeMillis)
            override fun getNotAfter() = Date(notAfterMillis)
            override fun getIssuerX500Principal() = X500Principal("CN=R10, O=Let's Encrypt, C=US")
            override fun getSubjectX500Principal() = X500Principal("CN=demo.dev")
            override fun getSubjectAlternativeNames() = mutableListOf(mutableListOf<Any>(2, "demo.dev"))
            override fun checkValidity() = Unit
            override fun checkValidity(date: Date?) = Unit
            override fun getVersion() = 3
            override fun getSerialNumber() = java.math.BigInteger.ONE
            @Suppress("DEPRECATION")
            override fun getIssuerDN(): Principal = issuerX500Principal
            @Suppress("DEPRECATION")
            override fun getSubjectDN(): Principal = subjectX500Principal
            override fun getTBSCertificate() = ByteArray(0)
            override fun getSignature() = ByteArray(0)
            override fun getSigAlgName() = "SHA256withRSA"
            override fun getSigAlgOID() = "1.2.840.113549.1.1.11"
            override fun getSigAlgParams(): ByteArray? = null
            override fun getIssuerUniqueID(): BooleanArray? = null
            override fun getSubjectUniqueID(): BooleanArray? = null
            override fun getKeyUsage(): BooleanArray? = null
            override fun getBasicConstraints() = -1
            override fun getEncoded() = ByteArray(0)
            override fun verify(key: PublicKey?) = Unit
            override fun verify(key: PublicKey?, sigProvider: String?) = Unit
            override fun toString() = "DemoCert"
            override fun getPublicKey(): PublicKey = throw UnsupportedOperationException()
            override fun hasUnsupportedCriticalExtension() = false
            override fun getCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
            override fun getNonCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
            override fun getExtensionValue(oid: String?): ByteArray? = null
        }
}
