package com.nodeseek.app.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nodeseek.app.data.activity.ActivityPreferencesStore
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.local.BookmarkEntity
import com.nodeseek.app.data.local.DataStoreBlockedUsersCache
import com.nodeseek.app.data.local.DraftDao
import com.nodeseek.app.data.local.DraftEntity
import com.nodeseek.app.data.local.DraftRepositoryImpl
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.local.SoftwareSettingsSecretCipher
import com.nodeseek.app.data.mail.SeekMailApiClient
import com.nodeseek.app.data.mail.SeekMailDiskCache
import com.nodeseek.app.data.mail.SeekMailRepositoryImpl
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.NetBenchReportPreferences
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.OverseasPromoPreferencesStore
import com.nodeseek.app.data.radar.FakeRadarDao
import com.nodeseek.app.data.repository.BookmarkAndHistoryRepositoryImpl
import com.nodeseek.app.data.repository.FakeBookmarkDao
import com.nodeseek.app.data.repository.FakeReadHistoryDao
import com.nodeseek.app.data.repository.RadarRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.data.repository.UserRepositoryImpl
import com.nodeseek.app.data.restock.RestockRepositoryImpl
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.data.trust.OfficialTradeApiClient
import com.nodeseek.app.data.trust.OfficialTradeCacheStore
import com.nodeseek.app.data.trust.OfficialTradeKeyStore
import com.nodeseek.app.data.trust.OfficialTradeRepositoryImpl
import com.nodeseek.app.data.trust.SellerTrustRepositoryImpl
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.activity.ActivityMonitorSettings
import com.nodeseek.app.domain.backup.ContentFilterBackupBridge
import com.nodeseek.app.domain.backup.ContentFilterBackupDto
import com.nodeseek.app.domain.backup.ContentFilterRuleBackupDto
import com.nodeseek.app.domain.backup.EncryptedBackupEnvelope
import com.nodeseek.app.domain.backup.EncryptedBackupErrorKind
import com.nodeseek.app.domain.backup.EncryptedBackupException
import com.nodeseek.app.domain.backup.EncryptedBackupHeader
import com.nodeseek.app.domain.backup.NodePulseFullBackupBundle
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.domain.backup.SshVaultBackupDto
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.overseas.OverseasOfferFilterConfig
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorSettings
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.restock.FlareSolverrConfig
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.SecretCipher
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.domain.trust.TrustTag
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.util.Base64

class EncryptedBackupV2Test {

    private lateinit var tempDir: File
    private lateinit var ioScope: CoroutineScope
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("encrypted_backup_v2_test").toFile()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ioScope.cancel()
        tempDir.deleteRecursively()
    }

    /**
     * Device-specific reversible test cipher simulating distinct Android Keystore keys on
     * source vs target devices (proving credentials are decrypted on export and re-encrypted
     * under the destination device's key on import).
     */
    private class DeviceScopedTestCipher(
        private val deviceTag: String,
    ) : SecretCipher {
        override fun encrypt(plaintext: String): String {
            if (plaintext.isEmpty()) return ""
            val b64 = Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))
            return "dev:$deviceTag:$b64"
        }

        override fun decrypt(ciphertext: String): String {
            if (ciphertext.isEmpty()) return ""
            val prefix = "dev:$deviceTag:"
            require(ciphertext.startsWith(prefix)) { "Ciphertext not encrypted for device $deviceTag" }
            val b64 = ciphertext.removePrefix(prefix)
            return String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        }
    }

    private class InMemoryDraftDao : DraftDao {
        private val state = MutableStateFlow<List<DraftEntity>>(emptyList())

        override suspend fun getDraft(accountUid: Long, draftKey: String): DraftEntity? =
            state.value.firstOrNull { it.accountUid == accountUid && it.draftKey == draftKey }

        override fun observeDraft(accountUid: Long, draftKey: String): Flow<DraftEntity?> =
            state.map { list -> list.firstOrNull { it.accountUid == accountUid && it.draftKey == draftKey } }

        override fun observeDrafts(accountUid: Long): Flow<List<DraftEntity>> =
            state.map { list -> list.filter { it.accountUid == accountUid } }

        override suspend fun upsert(entity: DraftEntity) {
            state.value = state.value
                .filterNot { it.accountUid == entity.accountUid && it.draftKey == entity.draftKey } + entity
        }

        override suspend fun deleteDraft(accountUid: Long, draftKey: String) {
            state.value = state.value.filterNot { it.accountUid == accountUid && it.draftKey == draftKey }
        }

        override suspend fun clearForAccount(accountUid: Long) {
            state.value = state.value.filterNot { it.accountUid == accountUid }
        }

        override suspend fun getAllDrafts(): List<DraftEntity> = state.value

        override suspend fun upsertAll(entities: List<DraftEntity>) {
            entities.forEach { upsert(it) }
        }

        override suspend fun clearAll() {
            state.value = emptyList()
        }
    }

    private class FakeContentFilterBridge : ContentFilterBackupBridge {
        var current: ContentFilterBackupDto = ContentFilterBackupDto()

        override suspend fun export(): ContentFilterBackupDto = current

        override suspend fun restore(dto: ContentFilterBackupDto, merge: Boolean) {
            current = if (merge) {
                val mergedRules = (current.rules + dto.rules).distinctBy { it.id }
                ContentFilterBackupDto(
                    isEnabled = dto.isEnabled,
                    mutedCategorySlugs = current.mutedCategorySlugs + dto.mutedCategorySlugs,
                    rules = mergedRules,
                )
            } else {
                dto
            }
        }
    }

    private class FullTestStack(
        val name: String,
        tempDir: File,
        ioScope: CoroutineScope,
        server: MockWebServer,
        val cryptoV2: BackupCryptoV2 = BackupCryptoV2(),
        overrideSshVaultRepository: ((SshVaultRepository) -> SshVaultRepository)? = null,
        journalStoreOverride: BackupRestoreJournalStore? = null,
        clockMillis: () -> Long = { 1_765_000_000_000L },
    ) {
        val dir = File(tempDir, name).apply { mkdirs() }
        val deviceCipher = DeviceScopedTestCipher(name)

        val settingsDataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(dir, "settings.preferences_pb") },
        )
        val radarDataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(dir, "radar.preferences_pb") },
        )
        val blockedDataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(dir, "blocked.preferences_pb") },
        )

        val settingsRepository = SettingsRepositoryImpl(
            dataStore = settingsDataStore,
            externalScope = ioScope,
        )
        val radarDao = FakeRadarDao()
        val radarRepository = RadarRepositoryImpl(
            radarDao = radarDao,
            preferencesStore = RadarPreferencesStore(radarDataStore),
            clockMillis = clockMillis,
        )
        val bookmarkDao = FakeBookmarkDao()
        val readHistoryDao = FakeReadHistoryDao()
        val draftDao = InMemoryDraftDao()
        val draftRepository = DraftRepositoryImpl(draftDao)
        val blockedUsersCache = DataStoreBlockedUsersCache(blockedDataStore)
        val contentFilterBridge = FakeContentFilterBridge()

        val cookieBridge = WebViewCookieBridge(forceInMemory = true)
        lateinit var apiClient: NodeSeekApiClient
        val sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        val bookmarkRepository = BookmarkAndHistoryRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            bookmarkDao = bookmarkDao,
            readHistoryDao = readHistoryDao,
        )
        val userRepository = UserRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            blockedUsersCache = blockedUsersCache,
        )

        val fleetRepository = FleetRepositoryImpl(
            storeFile = File(dir, "fleet_store.json"),
            nowMillisProvider = clockMillis,
        )
        val restockRepository = RestockRepositoryImpl(
            storeFile = File(dir, "restock_store.json"),
            clockMillis = clockMillis,
        )
        val rawSshVault = SshServerVault(
            vaultFile = File(dir, "ssh_vault.json"),
            cipher = deviceCipher,
            clock = clockMillis,
        )
        val sshVaultRepository: SshVaultRepository =
            overrideSshVaultRepository?.invoke(rawSshVault) ?: rawSshVault

        val seekMailDiskCache = SeekMailDiskCache(
            cacheDir = File(dir, "seek_mail_cache"),
            ioScope = ioScope,
        )
        val seekMailRepository = SeekMailRepositoryImpl(
            apiClient = SeekMailApiClient(baseHttpClient = okhttp3.OkHttpClient()),
            settingsRepository = settingsRepository,
            diskCache = seekMailDiskCache,
            externalScope = ioScope,
        )
        val overseasIntelRepository = OverseasIntelRepositoryImpl(
            listCacheFile = File(dir, "overseas_list.json"),
            detailCacheFile = File(dir, "overseas_detail.json"),
            prefsFile = File(dir, "overseas_prefs.json"),
            nowMillisProvider = clockMillis,
        )
        val overseasPromoPreferencesStore = OverseasPromoPreferencesStore(
            storeFile = File(dir, "overseas_promo_prefs.json"),
        )
        val activityPreferencesStore = ActivityPreferencesStore(
            storeFile = File(dir, "activity_prefs.json"),
        )
        val sellerTrustRepository: SellerTrustRepositoryImpl
        val officialTradeRepository: OfficialTradeRepositoryImpl
        val netBenchHistoryStore = NetBenchHistoryStore(
            historyFile = File(dir, "netbench_history.json"),
        )
        val exchangeRateRepository = ExchangeRateRepository(
            cacheFile = File(dir, "fx_cache.json"),
            nowMillisProvider = clockMillis,
        )

        val journalFile = File(dir, FileBackupRestoreJournalStore.DEFAULT_JOURNAL_FILE_NAME)
        val journalStore: BackupRestoreJournalStore = journalStoreOverride ?: FileBackupRestoreJournalStore(
            journalFile = journalFile,
            journalCipher = SoftwareSettingsSecretCipher.loadOrCreateFromFile(File(dir, ".journal_sw.key")),
        )

        val backupManager: BackupManagerImpl

        init {
            val baseUrl = server.url("/")
            val okHttp = NodeSeekApiClient.createDefaultHttpClient(
                cookieBridge = cookieBridge,
                userAgentProvider = UserAgentProvider("AnodeBackupV2Test/1.0"),
                additionalTargetHost = baseUrl.host,
            )
            apiClient = NodeSeekApiClient(
                httpClient = okHttp,
                baseOrigin = baseUrl,
                rssUrl = server.url("/rss.xml"),
                nodeImageOrigin = baseUrl,
                onConfigObserved = sessionRepository::onConfigPayloadObserved,
            )
            sellerTrustRepository = SellerTrustRepositoryImpl(
                userRepository = userRepository,
                forumRepository = com.nodeseek.app.data.repository.ForumRepositoryImpl(
                    apiClientProvider = { apiClient },
                    sessionRepository = sessionRepository,
                    settingsRepository = settingsRepository,
                    categoryDao = object : com.nodeseek.app.data.local.CategoryDao {
                        override fun observeCategories(accountUid: Long) =
                            MutableStateFlow(emptyList<com.nodeseek.app.data.local.CategoryEntity>())
                        override suspend fun getCategories(accountUid: Long) =
                            emptyList<com.nodeseek.app.data.local.CategoryEntity>()
                        override suspend fun getLastSyncedAtMillis(accountUid: Long): Long? = null
                        override suspend fun upsertAll(entities: List<com.nodeseek.app.data.local.CategoryEntity>) = Unit
                        override suspend fun clearForAccount(accountUid: Long) = Unit
                    },
                    readHistoryDao = readHistoryDao,
                    bookmarkDao = bookmarkDao,
                    bookmarkAndHistoryRepository = bookmarkRepository,
                ),
                notesFile = File(dir, "seller_trust_notes.json"),
                clockMillis = clockMillis,
            )
            officialTradeRepository = OfficialTradeRepositoryImpl(
                apiClient = OfficialTradeApiClient(baseHttpClient = okHttp),
                keyStore = OfficialTradeKeyStore(
                    keyFile = File(dir, "official_trade_key.json"),
                    cipher = deviceCipher,
                    clockMillis = clockMillis,
                ),
                cacheStore = OfficialTradeCacheStore(
                    cacheFile = File(dir, "official_trade_cache.json"),
                    clockMillis = clockMillis,
                ),
                clockMillis = clockMillis,
            )
            backupManager = BackupManagerImpl(
                settingsRepository = settingsRepository,
                radarRepository = radarRepository,
                bookmarkAndHistoryRepository = bookmarkRepository,
                draftRepository = draftRepository,
                userRepository = userRepository,
                sessionRepository = sessionRepository,
                blockedUsersCache = blockedUsersCache,
                contentFilterBackupBridge = contentFilterBridge,
                restoreJournalStore = journalStore,
                appVersionProvider = { "2.11.2-test" },
                clockMillis = clockMillis,
                fullBackupStores = FullBackupV2Stores(
                    fleetRepository = fleetRepository,
                    restockRepository = restockRepository,
                    sshVaultRepository = sshVaultRepository,
                    seekMailRepository = seekMailRepository,
                    overseasIntelRepository = overseasIntelRepository,
                    overseasPromoPreferencesStore = overseasPromoPreferencesStore,
                    activityPreferencesStore = activityPreferencesStore,
                    sellerTrustRepository = sellerTrustRepository,
                    officialTradeRepository = officialTradeRepository,
                    netBenchHistoryStore = netBenchHistoryStore,
                    exchangeRateRepository = exchangeRateRepository,
                ),
                backupCryptoV2 = cryptoV2,
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1. Cryptographic engine tests (PBKDF2-HMAC-SHA256 >= 310k + AES-256-GCM)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `crypto encrypts with PBKDF2-HMAC-SHA256 310k and AES-256-GCM using random salt and nonce`() {
        val crypto = BackupCryptoV2()
        val plaintext = """{"hello":"world","secret":"super-sensitive-ssh-key"}"""
        val passphrase = "StrongPassphrase!2026"

        val env1 = crypto.encrypt(plaintext, passphrase)
        val env2 = crypto.encrypt(plaintext, passphrase)

        assertEquals(EncryptedBackupEnvelope.FORMAT_MAGIC, env1.format)
        assertEquals(EncryptedBackupEnvelope.CURRENT_FORMAT_VERSION, env1.formatVersion)
        assertEquals(EncryptedBackupHeader.KDF_PBKDF2_HMAC_SHA256, env1.header.kdf)
        assertTrue(env1.header.iterations >= 310_000)
        assertEquals(256, env1.header.keyLengthBits)
        assertEquals(EncryptedBackupHeader.CIPHER_AES_256_GCM, env1.header.cipher)
        assertEquals(128, env1.header.tagLengthBits)
        assertEquals(32, Base64.getDecoder().decode(env1.header.saltBase64).size)
        assertEquals(12, Base64.getDecoder().decode(env1.header.nonceBase64).size)

        // Fresh random salt + nonce on every encryption
        assertNotEquals(env1.header.saltBase64, env2.header.saltBase64)
        assertNotEquals(env1.header.nonceBase64, env2.header.nonceBase64)
        assertNotEquals(env1.ciphertextBase64, env2.ciphertextBase64)

        val encoded = crypto.encodeEnvelope(env1)
        assertFalse("Ciphertext envelope must never contain plaintext secret", encoded.contains("super-sensitive-ssh-key"))
        assertTrue(BackupCryptoV2.isEncryptedEnvelope(encoded))

        val decrypted = crypto.decrypt(encoded, passphrase)
        assertEquals(plaintext, decrypted.plaintextUtf8)
    }

    @Test
    fun `crypto rejects weak passphrase shorter than 8 characters or blank`() {
        val crypto = BackupCryptoV2()
        for (bad in listOf("", "short7!", "        ")) {
            val ex = runCatching { crypto.encrypt("payload", bad) }.exceptionOrNull()
            assertTrue("Expected EncryptedBackupException for '$bad'", ex is EncryptedBackupException)
            assertEquals(
                EncryptedBackupErrorKind.WEAK_PASSPHRASE,
                (ex as EncryptedBackupException).kind,
            )
        }
    }

    @Test
    fun `crypto detects wrong passphrase and tampered ciphertext, GCM tag, and header AAD`() {
        val crypto = BackupCryptoV2()
        val plaintext = """{"payload":"secret-data-1234567890"}"""
        val env = crypto.encrypt(plaintext, "CorrectHorseBatteryStaple")
        val raw = crypto.encodeEnvelope(env)

        // 1. Wrong passphrase
        val wrongPassErr = runCatching { crypto.decrypt(raw, "WrongPassphrase123") }.exceptionOrNull()
        assertTrue(wrongPassErr is EncryptedBackupException)
        assertEquals(
            EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED,
            (wrongPassErr as EncryptedBackupException).kind,
        )
        assertTrue(wrongPassErr.message!!.contains("口令错误"))

        // 2. Flip 1 bit in ciphertext body
        val cipherBytes = Base64.getDecoder().decode(env.ciphertextBase64)
        val bodyTampered = cipherBytes.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        val bodyTamperedRaw = crypto.encodeEnvelope(
            env.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(bodyTampered)),
        )
        val bodyErr = runCatching { crypto.decrypt(bodyTamperedRaw, "CorrectHorseBatteryStaple") }.exceptionOrNull()
        assertTrue(bodyErr is EncryptedBackupException)
        assertEquals(EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED, (bodyErr as EncryptedBackupException).kind)

        // 3. Flip 1 bit in GCM auth tag (last byte)
        val tagTampered = cipherBytes.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 0x80).toByte()
        }
        val tagTamperedRaw = crypto.encodeEnvelope(
            env.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(tagTampered)),
        )
        val tagErr = runCatching { crypto.decrypt(tagTamperedRaw, "CorrectHorseBatteryStaple") }.exceptionOrNull()
        assertTrue(tagErr is EncryptedBackupException)
        assertEquals(EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED, (tagErr as EncryptedBackupException).kind)

        // 4. Tamper with header AAD (increment iterations by 1)
        val headerTamperedRaw = crypto.encodeEnvelope(
            env.copy(header = env.header.copy(iterations = env.header.iterations + 1)),
        )
        val headerErr = runCatching { crypto.decrypt(headerTamperedRaw, "CorrectHorseBatteryStaple") }.exceptionOrNull()
        assertTrue(headerErr is EncryptedBackupException)
        assertEquals(EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED, (headerErr as EncryptedBackupException).kind)

        // 5. Reject KDF iteration downgrade below 310,000
        val downgradedRaw = crypto.encodeEnvelope(
            env.copy(header = env.header.copy(iterations = 1_000)),
        )
        val downgradeErr = runCatching { crypto.decrypt(downgradedRaw, "CorrectHorseBatteryStaple") }.exceptionOrNull()
        assertTrue(downgradeErr is EncryptedBackupException)
        assertEquals(EncryptedBackupErrorKind.INVALID_FORMAT, (downgradeErr as EncryptedBackupException).kind)
    }

    @Test
    fun `version compatibility rejects unsupported formatVersion and inner schemaVersion and guides v1 inspect`() = runBlocking {
        val crypto = BackupCryptoV2()
        val stack = FullTestStack("version_compat", tempDir, ioScope, server, cryptoV2 = crypto)

        val validExport = stack.backupManager.exportEncryptedFullBackup("Passphrase2026!").getOrThrow()

        // Feeding encrypted v2 file into v1 inspectBackup yields clear guidance message
        val v1InspectError = stack.backupManager.inspectBackup(validExport.rawEnvelopeJson).exceptionOrNull()
        assertNotNull(v1InspectError)
        assertTrue(v1InspectError!!.message!!.contains("加密完整备份"))

        // Unsupported outer envelope formatVersion (v99)
        val futureEnvRaw = crypto.encodeEnvelope(validExport.envelope.copy(formatVersion = 99))
        val futureEnvErr = stack.backupManager.inspectEncryptedFullBackup(futureEnvRaw, "Passphrase2026!").exceptionOrNull()
        assertTrue(futureEnvErr is EncryptedBackupException)
        assertEquals(EncryptedBackupErrorKind.UNSUPPORTED_VERSION, (futureEnvErr as EncryptedBackupException).kind)

        // Unsupported inner schemaVersion (v99)
        val futureInner = NodePulseFullBackupBundle(
            schemaVersion = 99,
            appVersion = "9.9.9",
            exportedAtEpochMillis = 1_765_000_000_000L,
        )
        val futureInnerRaw = crypto.encodeEnvelope(
            crypto.encrypt(BackupManagerImpl.backupJson.encodeToString(futureInner), "Passphrase2026!"),
        )
        val futureInnerErr = stack.backupManager.inspectEncryptedFullBackup(futureInnerRaw, "Passphrase2026!").exceptionOrNull()
        assertTrue(futureInnerErr is EncryptedBackupException)
        assertEquals(EncryptedBackupErrorKind.UNSUPPORTED_VERSION, (futureInnerErr as EncryptedBackupException).kind)

        // Minimal v2 inner payload missing optional sections decodes cleanly with defaults
        val minimalInnerJson = """
            {
              "schemaVersion": 2,
              "appVersion": "2.11.2",
              "exportedAtEpochMillis": 1765000000000
            }
        """.trimIndent()
        val minimalRaw = crypto.encodeEnvelope(crypto.encrypt(minimalInnerJson, "Passphrase2026!"))
        val minimalPreview = stack.backupManager.inspectEncryptedFullBackup(minimalRaw, "Passphrase2026!").getOrThrow()
        assertEquals(2, minimalPreview.schemaVersion)
        assertEquals(0, minimalPreview.fleetAssetCount)
        assertEquals(0, minimalPreview.sshServerCount)

        // V1 plaintext backup remains 100% compatible and redacts API keys by default
        stack.settingsRepository.setNodeImageApiKey("ni_secret_v1_redacted")
        stack.settingsRepository.setSeekMailApiKey("sm_secret_v1_redacted")
        val v1Export = stack.backupManager.exportBackupPayload().getOrThrow()
        assertEquals(1, v1Export.bundle.schemaVersion)
        assertEquals("", v1Export.bundle.settings.nodeImageApiKey)
        assertEquals("", v1Export.bundle.settings.seekMailApiKey)
        assertFalse(v1Export.rawJson.contains("ni_secret_v1_redacted"))
        val v1Preview = stack.backupManager.inspectBackup(v1Export.rawJson).getOrThrow()
        assertEquals(1, v1Preview.schemaVersion)
        assertFalse(v1Preview.containsApiKeys)
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. Full v2 round-trip across ALL user data stores (with plaintext secrets inside cipher)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `encrypted full backup v2 round-trips all v1 and v2 stores including secrets and hides secrets on disk`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500)) // force blocked users fallback to local cache

        val source = FullTestStack("source_full", tempDir, ioScope, server)
        val target = FullTestStack("target_full", tempDir, ioScope, server)

        // Populate core v1 data on source
        source.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)
        source.settingsRepository.setNodeImageApiKey("ni_top_secret_999")
        source.settingsRepository.setSeekMailApiKey("sm_top_secret_888")
        source.radarRepository.updatePollingSettings {
            RadarPollingSettings(foregroundIntervalMinutes = 5, quietHoursEnabled = true)
        }
        source.radarRepository.addKeywordRule(keyword = "CN2 GIA", categorySlug = "trade", matchMode = KeywordMatchMode.CONTAINS)
        source.blockedUsersCache.replaceAll(listOf(FloorAuthor(uid = 42L, username = "bad_actor")))
        source.contentFilterBridge.current = ContentFilterBackupDto(
            isEnabled = true,
            mutedCategorySlugs = setOf("water"),
            rules = listOf(ContentFilterRuleBackupDto(id = "cf-1", pattern = "广告")),
        )
        source.bookmarkDao.upsert(
            BookmarkEntity.fromSummary(
                accountUid = 0L,
                summary = TopicSummary(id = 9001L, title = "东京 GIA 测评", categorySlug = "review", categoryName = "测评", authorName = "alice"),
                detailSnapshot = null,
                isSyncedWithServer = false,
                savedAtEpochMillis = 1_765_000_000_000L,
            ),
        )
        source.draftRepository.saveDraft(
            EditorDraft(draftKey = "new_topic", accountUid = 0L, title = "草稿标题", markdownContent = "正文内容"),
        )

        // Populate extended v2 data on source
        source.fleetRepository.upsertPanel(
            ProbePanelConfig(
                id = "panel-1",
                name = "主力哪吒",
                baseUrl = "https://probe.example.com",
                panelType = ProbePanelType.NEZHA_V1,
                apiToken = "nezha_super_secret_token_abc",
                notifyOnOffline = true,
                notifyOnRecovery = true,
            ),
        )
        source.fleetRepository.upsertAsset(
            VpsAssetRecord(
                id = "asset-1",
                nickname = "DMIT LAX Pro",
                providerName = "DMIT",
                specsSummary = "2C / 2G / 40G",
                region = "US-LAX",
                renewalAmount = "49.90",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2027-01-15",
                tags = listOf("CN2 GIA", "优化直连"),
                autoRenew = true,
                notes = "原油保留",
            ),
        )
        source.fleetRepository.updateMonitorSettings(
            FleetMonitorSettings(backgroundMonitorEnabled = true, pollIntervalMinutes = 30, notifyOnRecovery = true),
        )

        source.restockRepository.addOrUpdateWatch(
            RestockWatchItem(
                id = "watch-1",
                title = "BandwagonHost The Plan",
                rawUrl = "https://bwh81.net/cart.php?a=add&pid=123",
                resolvedOrderUrl = "https://bwh81.net/cart.php?a=add&pid=123",
                panelType = MerchantPanelType.WHMCS,
                priceHint = "$99/yr",
                pollIntervalMinutes = 5,
            ),
        )
        source.restockRepository.updateSettings {
            it.copy(
                backgroundIntervalMinutes = 30,
                flareSolverr = FlareSolverrConfig(enabled = true, baseUrl = "http://127.0.0.1:8191"),
            )
        }

        val bastion = source.rawSshVault.saveServer(
            SshServerDraft(
                id = "ssh-bastion",
                alias = "跳板机",
                host = "198.51.100.10",
                port = 2222,
                username = "ops",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "BastionPass!2026",
                groupTag = "核心",
            ),
        )
        source.rawSshVault.saveServer(
            SshServerDraft(
                id = "ssh-node1",
                alias = "日本落地",
                host = "203.0.113.20",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PRIVATE_KEY,
                secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nSECRET_KEY_BODY\n-----END OPENSSH PRIVATE KEY-----",
                passphrase = "KeyPassphrase#99",
                jumpHostId = bastion.id,
                groupTag = "东京",
            ),
        )
        source.rawSshVault.saveCustomPreset(
            CustomSshPreset(id = "preset-1", name = "自检脚本", command = "uname -a && free -m"),
        )
        source.rawSshVault.updateCollapseState { it.toggleGroupCollapsed("核心") }

        val createdTag = source.seekMailRepository.createLocalTag("账单", "#FF6600").getOrThrow()
        source.seekMailRepository.setLocalTagsForMail(mailId = 501L, tagIds = listOf(createdTag.id), mailboxId = 9L)
        source.seekMailRepository.archiveMailLocally(
            SeekMailSummary(
                id = 501L,
                mailboxId = 9L,
                fromAddress = "billing@dmit.io",
                fromName = "DMIT Billing",
                toAddress = "me@seek.li",
                subject = "Invoice Paid",
                snippet = "Thank you",
            ),
        )
        source.seekMailRepository.markMailReadLocally(501L)

        source.overseasIntelRepository.updateFilterConfig(
            OverseasOfferFilterConfig(
                enabled = true,
                blockedMerchants = setOf("SpamHost"),
                extraBlockedKeywords = setOf("crypto"),
            ),
        )
        source.overseasPromoPreferencesStore.updateSettings {
            it.copy(notificationsEnabled = false, backgroundIntervalMinutes = 45)
        }
        source.activityPreferencesStore.updateSettings {
            it.copy(notificationsEnabled = true, foregroundIntervalMinutes = 10)
        }

        source.sellerTrustRepository.saveLocalNote(
            uid = 777L,
            username = "ReliableVendor",
            tag = TrustTag.TRUSTED,
            noteText = "秒推原油靠谱",
        )
        source.officialTradeRepository.saveApiKey("ntk_official_trade_secret_555")
        source.netBenchHistoryStore.updatePreferences {
            NetBenchReportPreferences(
                maskTargetIp = false,
                includeClientNetwork = false,
                markdownStyle = NetBenchMarkdownStyle.REPLY_CARD,
                includeReportImages = true,
            )
        }
        source.exchangeRateRepository.setManualOverride(ValuationCurrency.USD, BigDecimal("7.35"))

        // Export encrypted v2 package
        val passphrase = "MyVaultPassword!2026"
        val exportPayload = source.backupManager.exportEncryptedFullBackup(passphrase).getOrThrow()
        val rawEncrypted = exportPayload.rawEnvelopeJson

        // Verify NO plaintext secrets appear anywhere in the exported encrypted file
        for (secret in listOf(
            "ni_top_secret_999",
            "sm_top_secret_888",
            "nezha_super_secret_token_abc",
            "BastionPass!2026",
            "SECRET_KEY_BODY",
            "KeyPassphrase#99",
            "ntk_official_trade_secret_555",
            "秒推原油靠谱",
        )) {
            assertFalse("Encrypted file must NOT contain plaintext secret: $secret", rawEncrypted.contains(secret))
        }

        // Preview on target device
        val preview = target.backupManager.inspectEncryptedFullBackup(rawEncrypted, passphrase).getOrThrow()
        assertEquals(2, preview.formatVersion)
        assertEquals(2, preview.schemaVersion)
        assertEquals(1, preview.fleetPanelCount)
        assertEquals(1, preview.fleetAssetCount)
        assertEquals(1, preview.restockWatchCount)
        assertEquals(2, preview.sshServerCount)
        assertEquals(1, preview.sshCustomPresetCount)
        assertEquals(1, preview.seekMailTagCount)
        assertEquals(1, preview.seekMailArchivedCount)
        assertEquals(1, preview.sellerNoteCount)
        assertTrue(preview.hasOfficialTradeApiKey)
        assertTrue(preview.containsApiKeys)
        assertEquals(1, preview.manualFxOverrideCount)

        // Restore onto target device (which has a DIFFERENT device cipher!)
        val restoreSummary = target.backupManager.restoreEncryptedFullBackup(
            rawContent = rawEncrypted,
            passphrase = passphrase,
            strategy = RestoreStrategy.OVERWRITE,
        ).getOrThrow()
        assertEquals(1, restoreSummary.fleetPanelsImported)
        assertEquals(1, restoreSummary.fleetAssetsImported)
        assertEquals(2, restoreSummary.sshServersImported)
        assertEquals(1, restoreSummary.restockWatchesImported)
        assertTrue(restoreSummary.officialTradeApiKeyImported)

        // Verify ALL restored state on target device
        val targetSettings = target.settingsRepository.exportSettingsForBackup(includeApiKeys = true)
        assertEquals(ThemeMode.OBSIDIAN_DARK, targetSettings.themeMode)
        assertEquals("ni_top_secret_999", targetSettings.nodeImageApiKey)
        assertEquals("sm_top_secret_888", targetSettings.seekMailApiKey)

        val targetFleet = target.fleetRepository.exportForBackup()
        assertEquals(1, targetFleet.panels.size)
        assertEquals("nezha_super_secret_token_abc", targetFleet.panels.first().apiToken)
        assertEquals(1, targetFleet.assets.size)
        assertEquals("DMIT LAX Pro", targetFleet.assets.first().nickname)
        assertEquals(30, targetFleet.monitorSettings.pollIntervalMinutes)

        val targetRestock = target.restockRepository.exportForBackup()
        assertEquals(1, targetRestock.watchItems.size)
        assertEquals("BandwagonHost The Plan", targetRestock.watchItems.first().title)
        assertTrue(targetRestock.settings.flareSolverr.enabled)

        // Verify SSH credentials were re-encrypted under target's deviceCipher and decrypt properly!
        val targetServers = target.rawSshVault.serversFlow.value
        assertEquals(2, targetServers.size)
        val targetNode1 = targetServers.first { it.id == "ssh-node1" }
        assertTrue(targetNode1.encryptedSecret.startsWith("dev:target_full:"))
        val decryptedNode1 = target.rawSshVault.decryptCredentials(targetNode1)
        assertTrue(decryptedNode1.secret.contains("SECRET_KEY_BODY"))
        assertEquals("KeyPassphrase#99", decryptedNode1.passphrase)
        assertEquals("ssh-bastion", targetNode1.jumpHostId)
        assertEquals(1, target.rawSshVault.customPresetsFlow.value.size)
        assertTrue(target.rawSshVault.collapseStateFlow.value.isGroupCollapsed("核心"))

        val targetMail = target.seekMailRepository.exportForBackup()
        assertEquals(1, targetMail.localTagStore.tags.size)
        assertEquals("账单", targetMail.localTagStore.tags.first().name)
        assertTrue(501L in targetMail.localArchiveStore.archivedMails)
        assertTrue(501L in targetMail.locallyReadMailIds)

        val targetOverseasFilter = target.overseasIntelRepository.exportFilterConfigForBackup()
        assertTrue("SpamHost" in targetOverseasFilter.blockedMerchants)
        assertTrue("crypto" in targetOverseasFilter.extraBlockedKeywords)
        assertFalse(target.overseasPromoPreferencesStore.exportSettingsForBackup().notificationsEnabled)
        assertTrue(target.activityPreferencesStore.exportSettingsForBackup().notificationsEnabled)
        assertEquals(10, target.activityPreferencesStore.exportSettingsForBackup().foregroundIntervalMinutes)

        val targetNotes = target.sellerTrustRepository.getAllNotes()
        assertEquals(1, targetNotes.size)
        assertEquals("秒推原油靠谱", targetNotes.first().noteText)
        assertEquals("ntk_official_trade_secret_555", target.officialTradeRepository.exportApiKeyForBackup())

        val targetNetBench = target.netBenchHistoryStore.exportPreferencesForBackup()
        assertFalse(targetNetBench.maskTargetIp)
        assertEquals(NetBenchMarkdownStyle.REPLY_CARD, targetNetBench.markdownStyle)
        assertEquals("7.35", target.exchangeRateRepository.exportManualOverridesForBackup()["USD"])
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. MERGE vs OVERWRITE mode tests
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `MERGE preserves local items across v2 stores while OVERWRITE replaces them cleanly`() = runBlocking {
        val fastCrypto = BackupCryptoV2(encryptIterations = 310_000, minDecryptIterations = 310_000)
        val source = FullTestStack("merge_source", tempDir, ioScope, server, cryptoV2 = fastCrypto)
        val target = FullTestStack("merge_target", tempDir, ioScope, server, cryptoV2 = fastCrypto)

        // Source backup items
        source.fleetRepository.upsertAsset(
            VpsAssetRecord(
                id = "asset-from-backup",
                nickname = "Backup Asset",
                providerName = "Bandwagon",
                specsSummary = "1C/1G",
                region = "US",
                renewalAmount = "20",
                expiryDateIso = "2027-05-01",
            ),
        )
        source.restockRepository.addOrUpdateWatch(
            RestockWatchItem(
                id = "watch-from-backup",
                title = "Backup Watch",
                rawUrl = "https://example.com/cart.php?pid=1",
                resolvedOrderUrl = "https://example.com/cart.php?pid=1",
            ),
        )
        source.rawSshVault.saveServer(
            SshServerDraft(
                id = "ssh-from-backup",
                alias = "Backup SSH",
                host = "198.51.100.5",
                secret = "backup-pass",
            ),
        )
        source.sellerTrustRepository.saveLocalNote(100L, "SellerFromBackup", TrustTag.TRUSTED, "备份备注")
        source.overseasIntelRepository.updateFilterConfig(
            OverseasOfferFilterConfig(blockedMerchants = setOf("BackupSpam")),
        )
        source.exchangeRateRepository.setManualOverride(ValuationCurrency.EUR, BigDecimal("7.90"))

        val encrypted = source.backupManager.exportEncryptedFullBackup("MergePass!2026").getOrThrow().rawEnvelopeJson

        // Existing local items on target
        target.fleetRepository.upsertAsset(
            VpsAssetRecord(
                id = "asset-local",
                nickname = "Local Asset",
                providerName = "Claw",
                specsSummary = "2C/4G",
                region = "SG",
                renewalAmount = "12",
                expiryDateIso = "2027-03-01",
            ),
        )
        target.restockRepository.addOrUpdateWatch(
            RestockWatchItem(
                id = "watch-local",
                title = "Local Watch",
                rawUrl = "https://local.example.com/cart.php?pid=2",
                resolvedOrderUrl = "https://local.example.com/cart.php?pid=2",
            ),
        )
        target.rawSshVault.saveServer(
            SshServerDraft(
                id = "ssh-local",
                alias = "Local SSH",
                host = "203.0.113.8",
                secret = "local-pass",
            ),
        )
        target.sellerTrustRepository.saveLocalNote(200L, "LocalSeller", TrustTag.CAUTION, "本地备注")
        target.overseasIntelRepository.updateFilterConfig(
            OverseasOfferFilterConfig(blockedMerchants = setOf("LocalSpam")),
        )
        target.exchangeRateRepository.setManualOverride(ValuationCurrency.USD, BigDecimal("7.25"))

        // 1. MERGE retains both local and incoming items
        target.backupManager.restoreEncryptedFullBackup(
            rawContent = encrypted,
            passphrase = "MergePass!2026",
            strategy = RestoreStrategy.MERGE,
        ).getOrThrow()

        assertEquals(setOf("asset-local", "asset-from-backup"), target.fleetRepository.exportForBackup().assets.map { it.id }.toSet())
        assertEquals(setOf("watch-local", "watch-from-backup"), target.restockRepository.exportForBackup().watchItems.map { it.id }.toSet())
        assertEquals(setOf("ssh-local", "ssh-from-backup"), target.rawSshVault.serversFlow.value.map { it.id }.toSet())
        assertEquals(setOf(100L, 200L), target.sellerTrustRepository.getAllNotes().map { it.uid }.toSet())
        assertEquals(
            setOf("LocalSpam", "BackupSpam"),
            target.overseasIntelRepository.exportFilterConfigForBackup().blockedMerchants,
        )
        assertEquals(
            mapOf("USD" to "7.25", "EUR" to "7.9"),
            target.exchangeRateRepository.exportManualOverridesForBackup(),
        )

        // 2. OVERWRITE replaces local items with only backup items
        target.backupManager.restoreEncryptedFullBackup(
            rawContent = encrypted,
            passphrase = "MergePass!2026",
            strategy = RestoreStrategy.OVERWRITE,
        ).getOrThrow()

        assertEquals(listOf("asset-from-backup"), target.fleetRepository.exportForBackup().assets.map { it.id })
        assertEquals(listOf("watch-from-backup"), target.restockRepository.exportForBackup().watchItems.map { it.id })
        assertEquals(listOf("ssh-from-backup"), target.rawSshVault.serversFlow.value.map { it.id })
        assertEquals(listOf(100L), target.sellerTrustRepository.getAllNotes().map { it.uid })
        assertEquals(setOf("BackupSpam"), target.overseasIntelRepository.exportFilterConfigForBackup().blockedMerchants)
        assertEquals(mapOf("EUR" to "7.9"), target.exchangeRateRepository.exportManualOverridesForBackup())
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. Zero partial writes on wrong passphrase & Transactional Rollback / Crash Recovery
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `wrong passphrase leaves all local stores untouched and writes no journal`() = runBlocking {
        val source = FullTestStack("wrong_pass_src", tempDir, ioScope, server)
        val target = FullTestStack("wrong_pass_dst", tempDir, ioScope, server)

        source.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)
        source.rawSshVault.saveServer(SshServerDraft(id = "src-1", alias = "Src", host = "198.51.100.1", secret = "s"))
        val encrypted = source.backupManager.exportEncryptedFullBackup("RightPassphrase!").getOrThrow().rawEnvelopeJson

        target.settingsRepository.setThemeMode(ThemeMode.PAPER_LIGHT)
        target.rawSshVault.saveServer(SshServerDraft(id = "dst-1", alias = "Dst", host = "203.0.113.1", secret = "d"))

        val result = target.backupManager.restoreEncryptedFullBackup(
            rawContent = encrypted,
            passphrase = "WrongPassphrase!",
            strategy = RestoreStrategy.OVERWRITE,
        )
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EncryptedBackupException
        assertEquals(EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED, ex.kind)

        // Verify target stores completely untouched and no journal file created
        assertEquals(ThemeMode.PAPER_LIGHT, target.settingsRepository.exportSettingsForBackup().themeMode)
        assertEquals(listOf("dst-1"), target.rawSshVault.serversFlow.value.map { it.id })
        assertFalse(target.journalFile.exists())
    }

    @Test
    fun `mid-restore failure rolls back all v1 and v2 stores atomically and clears journal`() = runBlocking {
        val source = FullTestStack("rollback_src", tempDir, ioScope, server)

        // Prepare source backup with different data across all stores
        source.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)
        source.settingsRepository.setSeekMailApiKey("sm_new_key")
        source.fleetRepository.upsertAsset(
            VpsAssetRecord(
                id = "new-asset",
                nickname = "New Asset",
                providerName = "NewHost",
                specsSummary = "4C/8G",
                region = "JP",
                renewalAmount = "88",
                expiryDateIso = "2028-01-01",
            ),
        )
        source.restockRepository.addOrUpdateWatch(
            RestockWatchItem(
                id = "new-watch",
                title = "New Watch",
                rawUrl = "https://new.example.com/cart.php?pid=9",
                resolvedOrderUrl = "https://new.example.com/cart.php?pid=9",
            ),
        )
        source.rawSshVault.saveServer(
            SshServerDraft(id = "new-ssh", alias = "New SSH", host = "198.51.100.99", secret = "new-secret"),
        )
        val encrypted = source.backupManager.exportEncryptedFullBackup("RollbackPass!2026").getOrThrow().rawEnvelopeJson

        // Target stack whose SshVaultRepository throws on first restoreFromBackup (AFTER settings, radar, fleet, restock mutated!)
        var failNextSshRestore = true
        val target = FullTestStack(
            name = "rollback_dst",
            tempDir = tempDir,
            ioScope = ioScope,
            server = server,
            overrideSshVaultRepository = { delegate ->
                object : SshVaultRepository by delegate {
                    override suspend fun restoreFromBackup(backup: SshVaultBackupDto, replaceExisting: Boolean) {
                        if (failNextSshRestore) {
                            failNextSshRestore = false
                            throw IOException("Simulated disk full during SSH vault restore")
                        }
                        delegate.restoreFromBackup(backup, replaceExisting)
                    }
                }
            },
        )

        // Populate pre-restore state on target
        target.settingsRepository.setThemeMode(ThemeMode.PAPER_LIGHT)
        target.settingsRepository.setSeekMailApiKey("sm_original_key")
        val origTag = target.seekMailRepository.createLocalTag("原有标签", "#2D637E").getOrThrow()
        target.seekMailRepository.setLocalTagsForMail(88L, listOf(origTag.id), 1L)
        target.fleetRepository.upsertAsset(
            VpsAssetRecord(
                id = "orig-asset",
                nickname = "Original Asset",
                providerName = "OrigHost",
                specsSummary = "1C/1G",
                region = "HK",
                renewalAmount = "15",
                expiryDateIso = "2027-06-01",
            ),
        )
        target.restockRepository.addOrUpdateWatch(
            RestockWatchItem(
                id = "orig-watch",
                title = "Original Watch",
                rawUrl = "https://orig.example.com/cart.php?pid=1",
                resolvedOrderUrl = "https://orig.example.com/cart.php?pid=1",
            ),
        )
        target.rawSshVault.saveServer(
            SshServerDraft(id = "orig-ssh", alias = "Original SSH", host = "203.0.113.55", secret = "orig-secret"),
        )

        // Attempt OVERWRITE restore -> fails inside SSH vault AFTER settings/fleet/restock already wrote
        val restoreResult = target.backupManager.restoreEncryptedFullBackup(
            rawContent = encrypted,
            passphrase = "RollbackPass!2026",
            strategy = RestoreStrategy.OVERWRITE,
        )
        assertTrue(restoreResult.isFailure)
        assertTrue(restoreResult.exceptionOrNull() is IOException)

        // Verify EVERY store was rolled back cleanly to exact pre-restore state!
        val restoredSettings = target.settingsRepository.exportSettingsForBackup(includeApiKeys = true)
        assertEquals(ThemeMode.PAPER_LIGHT, restoredSettings.themeMode)
        assertEquals("sm_original_key", restoredSettings.seekMailApiKey)
        assertEquals(listOf("orig-asset"), target.fleetRepository.exportForBackup().assets.map { it.id })
        assertEquals(listOf("orig-watch"), target.restockRepository.exportForBackup().watchItems.map { it.id })
        assertEquals(listOf("orig-ssh"), target.rawSshVault.serversFlow.value.map { it.id })
        assertEquals(listOf("原有标签"), target.seekMailRepository.exportForBackup().localTagStore.tags.map { it.name })
        assertNull(target.journalStore.readPending())
    }

    @Test
    fun `crash recovery from encrypted v2 WAL journal restores all v1 and v2 stores`() = runBlocking {
        val stack = FullTestStack("crash_recovery", tempDir, ioScope, server)

        // Establish pre-crash state
        stack.settingsRepository.setThemeMode(ThemeMode.PAPER_LIGHT)
        stack.fleetRepository.upsertAsset(
            VpsAssetRecord(
                id = "pre-crash-asset",
                nickname = "Pre-Crash Asset",
                providerName = "StableHost",
                specsSummary = "2C/2G",
                region = "DE",
                renewalAmount = "30",
                expiryDateIso = "2027-12-31",
            ),
        )
        stack.rawSshVault.saveServer(
            SshServerDraft(id = "pre-crash-ssh", alias = "Pre-Crash SSH", host = "198.51.100.77", secret = "pre-crash-secret"),
        )
        stack.officialTradeRepository.saveApiKey("ntk_pre_crash_key_123")

        // Export current state as the pre-restore snapshot and write pending WAL journal directly
        val preSnapshot = stack.backupManager.exportEncryptedFullBackup("SnapshotPass!2026").getOrThrow().bundle
        stack.journalStore.writePending(
            BackupRestoreJournalEntry(
                journalVersion = BackupRestoreJournalEntry.FULL_V2_JOURNAL_VERSION,
                journalId = "crashed-tx-1",
                createdAtEpochMillis = 1_765_000_000_000L,
                strategy = RestoreStrategy.OVERWRITE,
                preRestoreSnapshot = preSnapshot.toCoreV1Bundle(),
                preRestoreFullSnapshot = preSnapshot,
            ),
        )

        // Confirm journal file on disk is encrypted at rest (does NOT contain plaintext SSH secret)
        assertTrue(stack.journalFile.isFile)
        val rawJournalOnDisk = stack.journalFile.readText(Charsets.UTF_8)
        assertFalse("Encrypted WAL journal must not contain plaintext SSH secret", rawJournalOnDisk.contains("pre-crash-secret"))
        assertFalse("Encrypted WAL journal must not contain plaintext official key", rawJournalOnDisk.contains("ntk_pre_crash_key_123"))

        // Simulate partial mid-flight mutations left behind by a sudden process kill
        stack.settingsRepository.setThemeMode(ThemeMode.OBSIDIAN_DARK)
        stack.fleetRepository.deleteAsset("pre-crash-asset")
        stack.rawSshVault.deleteServer("pre-crash-ssh")
        stack.officialTradeRepository.clearApiKey()

        // Run startup recovery
        val recovered = stack.backupManager.recoverPendingRestoreIfNeeded().getOrThrow()
        assertTrue(recovered)

        // All v1 + v2 stores must be back to exact pre-crash state and journal cleared
        assertEquals(ThemeMode.PAPER_LIGHT, stack.settingsRepository.exportSettingsForBackup().themeMode)
        assertEquals(listOf("pre-crash-asset"), stack.fleetRepository.exportForBackup().assets.map { it.id })
        assertEquals(listOf("pre-crash-ssh"), stack.rawSshVault.serversFlow.value.map { it.id })
        assertEquals(
            "pre-crash-secret",
            stack.rawSshVault.decryptCredentials(stack.rawSshVault.serversFlow.value.first()).secret,
        )
        assertEquals("ntk_pre_crash_key_123", stack.officialTradeRepository.exportApiKeyForBackup())
        assertFalse(stack.journalFile.exists())
    }
}
