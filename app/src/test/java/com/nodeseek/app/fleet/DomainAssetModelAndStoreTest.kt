package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.domain.backup.VpsAssetBackupDto
import com.nodeseek.app.domain.backup.toBackupDto
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DomainAssetModelAndStoreTest {

    private lateinit var tempDir: File
    private lateinit var cipher: SoftwareAesGcmTokenCipher
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("domain_asset_store_test").toFile()
        cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `legacy fleet_store json without assetType or domain fields loads cleanly as VPS`() = runTest(testDispatcher) {
        val storeFile = File(tempDir, "fleet_store.json")
        storeFile.writeText(
            """
            {
              "version": 1,
              "panels": [],
              "assets": [
                {
                  "id": "vps_legacy_1",
                  "nickname": "瓦工旧鸡",
                  "providerName": "BandwagonHost",
                  "specsSummary": "1C / 1G",
                  "region": "洛杉矶",
                  "routeTag": "CN2 GIA",
                  "renewalAmount": "49.90",
                  "currency": "USD",
                  "cyclePreset": "ANNUAL",
                  "expiryDateIso": "2027-01-15"
                }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val repo = FleetRepositoryImpl(
            storeFile = storeFile,
            tokenCipher = cipher,
            ioDispatcher = testDispatcher,
        )
        repo.loadPersistedState()

        val loaded = repo.assetsFlow.value
        assertEquals(1, loaded.size)
        val vps = loaded.first()
        assertEquals(FleetAssetType.VPS, vps.assetType)
        assertNull(vps.domainName)
        assertEquals("", vps.registrarName)
        assertNull(vps.tlsExpiresAtEpochMillis)
    }

    @Test
    fun `DOMAIN_CERT asset round-trips through FleetRepositoryImpl and VpsAssetBackupDto`() = runTest(testDispatcher) {
        val storeFile = File(tempDir, "fleet_store.json")
        val repo = FleetRepositoryImpl(
            storeFile = storeFile,
            tokenCipher = cipher,
            ioDispatcher = testDispatcher,
            nowMillisProvider = { 1_700_000_000_000L },
        )
        val tlsExp = LocalDate.of(2027, 3, 10).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val domainRecord = VpsAssetRecord(
            id = "dom_1",
            nickname = "nodeseek-demo.dev",
            providerName = "Porkbun",
            specsSummary = "",
            region = "",
            renewalAmount = "10.18",
            currency = ValuationCurrency.USD,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = "2027-06-01",
            assetType = FleetAssetType.DOMAIN_CERT,
            domainName = "HTTPS://NodeSeek-Demo.DEV:443/path",
            registrarName = "Porkbun",
            domainCreatedIso = "2022-06-01",
            rdapSyncedAtEpochMillis = 1_700_000_000_000L,
            rdapStatusTags = listOf("clientTransferProhibited"),
            tlsHost = "app.nodeseek-demo.dev",
            tlsPort = 8443,
            tlsExpiresAtEpochMillis = tlsExp,
            tlsIssuer = "Let's Encrypt",
            tlsSubject = "app.nodeseek-demo.dev",
            tlsCheckedAtEpochMillis = 1_700_000_000_500L,
        )
        repo.upsertAsset(domainRecord)

        val reloaded = FleetRepositoryImpl(
            storeFile = storeFile,
            tokenCipher = cipher,
            ioDispatcher = testDispatcher,
        )
        reloaded.loadPersistedState()
        val saved = reloaded.assetsFlow.value.single()

        assertEquals(FleetAssetType.DOMAIN_CERT, saved.assetType)
        assertTrue(saved.isDomainAsset)
        assertEquals("nodeseek-demo.dev", saved.domainName)
        assertEquals("Porkbun", saved.registrarName)
        assertEquals("2022-06-01", saved.domainCreatedIso)
        assertEquals(listOf("clientTransferProhibited"), saved.rdapStatusTags)
        assertEquals("app.nodeseek-demo.dev", saved.tlsHost)
        assertEquals(8443, saved.tlsPort)
        assertEquals("Let's Encrypt", saved.tlsIssuer)
        assertNull(saved.linkedProbeKey)

        // Backup DTO round-trip
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val dtoJson = json.encodeToString(VpsAssetBackupDto.serializer(), saved.toBackupDto())
        val restored = json.decodeFromString(VpsAssetBackupDto.serializer(), dtoJson).toDomain()
        assertEquals(FleetAssetType.DOMAIN_CERT, restored.assetType)
        assertEquals("nodeseek-demo.dev", restored.domainName)
        assertEquals("Let's Encrypt", restored.tlsIssuer)
        assertEquals(8443, restored.tlsPort)
    }

    @Test
    fun `normalizeDomainName handles schemes, ports, trailing dots, IDN punycode, and rejects invalid inputs`() {
        assertEquals("example.com", VpsAssetRecord.normalizeDomainName("https://EXAMPLE.COM.:443/login?a=1"))
        assertEquals("xn--fiqs8s.cn", VpsAssetRecord.normalizeDomainName("中国.cn"))
        assertNull(VpsAssetRecord.normalizeDomainName(""))
        assertNull(VpsAssetRecord.normalizeDomainName("localhost"))
        assertNull(VpsAssetRecord.normalizeDomainName("192.168.1.1"))
    }
}
