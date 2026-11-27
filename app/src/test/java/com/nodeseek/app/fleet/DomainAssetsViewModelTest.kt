package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.DomainRdapClient
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.JvmTlsCertificateInspector
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.fleet.DomainRdapResult
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.TlsPeerChain
import com.nodeseek.app.domain.fleet.TlsSocketOps
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.fleet.DomainAssetFilter
import com.nodeseek.app.ui.fleet.DomainAssetsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
class DomainAssetsViewModelTest {

    private lateinit var tempDir: File
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("domain_assets_vm_test").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `DomainAssetsViewModel isolates DOMAIN_CERT assets from VPS ledger and supports sparse DENIC and CentralNic RDAP`() =
        runTest(testDispatcher) {
            val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val fleetRepo = FleetRepositoryImpl(
                storeFile = File(tempDir, "fleet_domain_only.json"),
                tokenCipher = cipher,
                ioDispatcher = testDispatcher,
            )
            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempDir, "fx_domain_only.json"),
                endpointUrl = "http://127.0.0.1:1/fx",
                ioDispatcher = testDispatcher,
            )
            val today = LocalDate.of(2026, 11, 27)
            val nowMillis = today.atTime(12, 0).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()

            // Seed one VPS server asset + one existing domain asset
            fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "vps_1",
                    nickname = "HK VPS",
                    providerName = "DMIT",
                    specsSummary = "2C/2G",
                    region = "HK",
                    renewalAmount = "100.00",
                    currency = ValuationCurrency.CNY,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-12-01",
                    assetType = FleetAssetType.VPS,
                ),
            )
            fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "dom_existing",
                    nickname = "0ne.icu",
                    providerName = "Spaceship, Inc.",
                    specsSummary = "",
                    region = "",
                    renewalAmount = "24.00",
                    currency = ValuationCurrency.CNY,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-12-05",
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "0ne.icu",
                    registrarName = "Spaceship, Inc.",
                    tlsExpiresAtEpochMillis = today.plusDays(5).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli(),
                ),
            )

            val fakeRdap = object : DomainRdapClient(ioDispatcher = testDispatcher) {
                override suspend fun lookupDomain(rawDomain: String): Result<DomainRdapResult> = when (rawDomain) {
                    "0ne.icu" -> Result.success(
                        DomainRdapResult(
                            domainName = "0ne.icu",
                            registrarName = "Spaceship, Inc.",
                            registrarIanaId = "3862",
                            expiryDateIso = "2027-08-17",
                            registrationDateIso = "2024-08-17",
                            lastChangedDateIso = "2026-09-03",
                            statuses = listOf("client transfer prohibited"),
                            nameservers = listOf("leonard.ns.cloudflare.com", "stella.ns.cloudflare.com"),
                            sourceUrl = "https://rdap.centralnic.com/icu/domain/0ne.icu",
                            fetchedAtEpochMillis = nowMillis,
                        ),
                    )
                    "colmidad.de" -> Result.success(
                        DomainRdapResult(
                            domainName = "colmidad.de",
                            registrarName = null,
                            expiryDateIso = null,
                            registrationDateIso = null,
                            lastChangedDateIso = "2024-04-01",
                            statuses = listOf("active"),
                            nameservers = listOf("leonard.ns.cloudflare.com", "stella.ns.cloudflare.com"),
                            sourceUrl = "https://rdap.denic.de/domain/colmidad.de",
                            fetchedAtEpochMillis = nowMillis + 1_000L,
                        ),
                    )
                    else -> Result.failure(IllegalStateException("unexpected $rawDomain"))
                }
            }
            val fakeTls = JvmTlsCertificateInspector(
                socketOps = object : TlsSocketOps {
                    override suspend fun fetchPeerCertificates(host: String, port: Int, timeoutMillis: Int): TlsPeerChain {
                        val exp = today.plusDays(60).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()
                        return TlsPeerChain(certificates = listOf(simpleCert(nowMillis - 10_000L, exp)), isTrusted = true)
                    }
                },
                nowMillisProvider = { nowMillis },
            )

            val vm = DomainAssetsViewModel(
                fleetRepository = fleetRepo,
                exchangeRateRepository = fxRepo,
                todayProvider = { today },
                nowMillisProvider = { nowMillis },
                computationDispatcher = testDispatcher,
                domainRdapClient = fakeRdap,
                tlsCertificateInspector = fakeTls,
            )
            advanceUntilIdle()

            // Must show ONLY DOMAIN_CERT assets and compute domain-only CNY cost (24/yr, excluding 100/yr VPS)
            assertEquals(listOf("dom_existing"), vm.uiState.value.domainAssets.map { it.id })
            assertEquals(BigDecimal("24.00"), vm.uiState.value.domainAnnualizedCostCny)
            assertEquals(1, vm.uiState.value.tlsAlertCount)

            // Lookup 0ne.icu then switch to colmidad.de in same draft -> stale registrar must clear, no error on missing expiry
            vm.openAddDomainSheet()
            advanceUntilIdle()
            vm.lookupDomainRdapForDraft("0ne.icu")
            advanceUntilIdle()
            val icuDraft = vm.uiState.value.assetEditorDraft!!
            assertEquals("0ne.icu", icuDraft.domainName)
            assertEquals("Spaceship, Inc.", icuDraft.registrarName)
            assertEquals("2027-08-17", icuDraft.expiryDateIso)
            assertNull(vm.uiState.value.rdapLookupError)

            vm.lookupDomainRdapForDraft("colmidad.de")
            advanceUntilIdle()
            val deDraft = vm.uiState.value.assetEditorDraft!!
            assertEquals("colmidad.de", deDraft.domainName)
            assertEquals("", deDraft.registrarName)
            assertEquals(listOf("active"), deDraft.rdapStatusTags)
            assertNull(vm.uiState.value.rdapLookupError)
            assertNotNull(vm.uiState.value.rdapSyncSummary)
            assertTrue(vm.uiState.value.rdapSyncSummary!!.contains("注册局未公开到期日"))

            // Save colmidad.de and verify filter behavior
            vm.saveDomainAsset(
                VpsAssetRecord(
                    id = "dom_de",
                    nickname = "colmidad.de",
                    providerName = "",
                    specsSummary = "",
                    region = "",
                    renewalAmount = "36.00",
                    currency = ValuationCurrency.CNY,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2027-11-27",
                    assetType = FleetAssetType.VPS, // saveDomainAsset must enforce DOMAIN_CERT
                    domainName = "colmidad.de",
                    tlsExpiresAtEpochMillis = today.plusDays(60).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli(),
                ),
            )
            advanceUntilIdle()

            assertEquals(2, vm.uiState.value.domainAssets.size)
            assertEquals(BigDecimal("60.00"), vm.uiState.value.domainAnnualizedCostCny)

            vm.selectFilter(DomainAssetFilter.TLS_ALERT)
            advanceUntilIdle()
            assertEquals(listOf("dom_existing"), vm.uiState.value.filteredDomains.map { it.id })

            vm.selectFilter(DomainAssetFilter.DUE_SOON)
            advanceUntilIdle()
            assertEquals(listOf("dom_existing"), vm.uiState.value.filteredDomains.map { it.id })

            vm.selectFilter(DomainAssetFilter.ALL)
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.filteredDomains.size)
        }

    private fun simpleCert(notBeforeMillis: Long, notAfterMillis: Long): X509Certificate =
        object : X509Certificate() {
            override fun getNotBefore() = Date(notBeforeMillis)
            override fun getNotAfter() = Date(notAfterMillis)
            override fun getIssuerX500Principal() = X500Principal("CN=R10, O=Let's Encrypt, C=US")
            override fun getSubjectX500Principal() = X500Principal("CN=colmidad.de")
            override fun getSubjectAlternativeNames() = mutableListOf(mutableListOf<Any>(2, "colmidad.de"))
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
            override fun getSigAlgOID() = "1.2.840.1.1.11"
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
