package com.nodeseek.app.baseline

import com.nodeseek.app.data.backup.BackupCryptoV2
import com.nodeseek.app.data.backup.BackupManagerImpl
import com.nodeseek.app.data.backup.BackupRestoreJournalEntry
import com.nodeseek.app.data.backup.BackupRestoreJournalStore
import com.nodeseek.app.data.backup.FullBackupV2Stores
import com.nodeseek.app.data.backup.LocalRestoreTransactionRunner
import com.nodeseek.app.data.baseline.BenchmarkBaselineStore
import com.nodeseek.app.data.local.DataStoreBlockedUsersCache
import com.nodeseek.app.data.local.DraftDao
import com.nodeseek.app.data.local.DraftEntity
import com.nodeseek.app.data.local.DraftRepositoryImpl
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.radar.FakeRadarDao
import com.nodeseek.app.data.repository.BookmarkAndHistoryRepositoryImpl
import com.nodeseek.app.data.repository.FakeBookmarkDao
import com.nodeseek.app.data.repository.FakeReadHistoryDao
import com.nodeseek.app.data.repository.RadarRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.data.repository.UserRepositoryImpl
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.backup.ContentFilterBackupBridge
import com.nodeseek.app.domain.backup.ContentFilterBackupDto
import com.nodeseek.app.domain.backup.EncryptedBackupEnvelope
import com.nodeseek.app.domain.backup.EncryptedBackupHeader
import com.nodeseek.app.domain.backup.NodePulseFullBackupBundle
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.domain.baseline.BaselineBandwidthMetrics
import com.nodeseek.app.domain.baseline.BaselineSourceType
import com.nodeseek.app.domain.baseline.BenchmarkBaselineSnapshot
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkBaselineStoreAndBackupTest {

    private lateinit var tempDir: File
    private lateinit var ioScope: CoroutineScope
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("baseline_store_test").toFile()
        ioScope = CoroutineScope(SupervisorJob() + testDispatcher)
    }

    @After
    fun tearDown() {
        ioScope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `store CRUD, serverId and host matching, reload, and corrupt file recovery`() = runTest(testDispatcher) {
        val file = File(tempDir, "benchmark_baselines.json")
        val store = BenchmarkBaselineStore(storeFile = file, ioDispatcher = testDispatcher)

        val snap1 = BenchmarkBaselineSnapshot(
            id = "b1",
            label = "DMIT LAX 购入基线",
            targetKey = "server:srv1",
            serverId = "srv1",
            hostDisplay = "lax.example.com",
            resolvedIp = "198.51.100.10",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_700_000_000_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 450.0, uploadMbps = 320.0),
        )
        store.saveBaseline(snap1)
        assertEquals(1, store.baselinesFlow.value.size)
        assertEquals(1, store.findMatchingBaselines(serverId = "srv1").size)
        assertEquals(1, store.findMatchingBaselines(hostOrIp = "198.51.100.10").size)
        assertEquals(1, store.findMatchingBaselines(hostOrIp = "LAX.EXAMPLE.COM").size)

        // Reload across a fresh store instance
        val reloaded = BenchmarkBaselineStore(storeFile = file, ioDispatcher = testDispatcher, deferInitialLoad = true)
        reloaded.ensureLoaded()
        assertEquals(1, reloaded.baselinesFlow.value.size)
        assertEquals(450.0, reloaded.baselinesFlow.value.first().bandwidth.downloadMbps!!, 0.01)

        reloaded.deleteBaseline("b1")
        assertTrue(reloaded.baselinesFlow.value.isEmpty())

        // Corrupt recovery
        file.writeText("{not-valid-json", Charsets.UTF_8)
        val recovered = BenchmarkBaselineStore(storeFile = file, ioDispatcher = testDispatcher)
        assertTrue(recovered.baselinesFlow.value.isEmpty())
        val corruptBackups = tempDir.listFiles { _, name -> name.contains(".corrupt.") }.orEmpty()
        assertTrue(corruptBackups.isNotEmpty())
    }

    @Test
    fun `EncryptedBackupV2 exports, restores MERGE and OVERWRITE, and stays compatible with legacy bundles lacking benchmarkBaselines`() =
        runTest(testDispatcher) {
            val dirA = File(tempDir, "instA").apply { mkdirs() }
            val dirB = File(tempDir, "instB").apply { mkdirs() }

            val baselineStoreA = BenchmarkBaselineStore(File(dirA, "benchmark_baselines.json"), ioDispatcher = testDispatcher)
            val snapA = BenchmarkBaselineSnapshot(
                id = "base_a",
                label = "瓦工 MEGABOX 基线",
                targetKey = "host:mega.example.com",
                hostDisplay = "mega.example.com",
                sourceType = BaselineSourceType.SSH_BENCHMARK,
                capturedAtEpochMillis = 1_700_000_100_000L,
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 880.0),
            )
            baselineStoreA.saveBaseline(snapA)

            val managerA = buildBackupManager(dirA, baselineStoreA)
            val exportRes = managerA.exportEncryptedFullBackup("CorrectHorseBatteryStaple1!")
            assertTrue(exportRes.isSuccess)
            val payload = exportRes.getOrThrow()
            assertEquals(1, payload.bundle.benchmarkBaselines.size)

            val preview = managerA.inspectEncryptedFullBackup(payload.rawEnvelopeJson, "CorrectHorseBatteryStaple1!").getOrThrow()
            assertEquals(1, preview.baselineCount)

            val baselineStoreB = BenchmarkBaselineStore(File(dirB, "benchmark_baselines.json"), ioDispatcher = testDispatcher)
            val existingB = snapA.copy(id = "base_b", label = "本地已有基线")
            baselineStoreB.saveBaseline(existingB)
            val managerB = buildBackupManager(dirB, baselineStoreB)

            // MERGE keeps both
            val mergeSummary = managerB.restoreEncryptedFullBackup(
                rawContent = payload.rawEnvelopeJson,
                passphrase = "CorrectHorseBatteryStaple1!",
                strategy = RestoreStrategy.MERGE,
            ).getOrThrow()
            assertEquals(1, mergeSummary.baselinesImported)
            assertEquals(2, baselineStoreB.baselinesFlow.value.size)

            // OVERWRITE replaces with backup only
            managerB.restoreEncryptedFullBackup(
                rawContent = payload.rawEnvelopeJson,
                passphrase = "CorrectHorseBatteryStaple1!",
                strategy = RestoreStrategy.OVERWRITE,
            ).getOrThrow()
            assertEquals(listOf("base_a"), baselineStoreB.baselinesFlow.value.map { it.id })

            // Legacy JSON without "benchmarkBaselines" field restores cleanly
            val legacyJson = """
                {
                  "schemaVersion": 2,
                  "appVersion": "1.0.0",
                  "exportedAtEpochMillis": 1700000000000
                }
            """.trimIndent()
            val legacyEnvelope = BackupCryptoV2(encryptIterations = 310_000)
                .encrypt(legacyJson, "CorrectHorseBatteryStaple1!")
            val rawLegacy = BackupCryptoV2().encodeEnvelope(legacyEnvelope)
            val legacyPreview = managerB.inspectEncryptedFullBackup(rawLegacy, "CorrectHorseBatteryStaple1!").getOrThrow()
            assertEquals(0, legacyPreview.baselineCount)
        }

    private fun buildBackupManager(
        dir: File,
        baselineStore: BenchmarkBaselineStore,
    ): BackupManagerImpl {
        val ds = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(dir, "settings.preferences_pb") },
        )
        val settingsRepo = SettingsRepositoryImpl(dataStore = ds, externalScope = ioScope)
        val radarPrefs = RadarPreferencesStore(
            PreferenceDataStoreFactory.create(scope = ioScope, produceFile = { File(dir, "radar.preferences_pb") }),
        )
        val radarRepo = RadarRepositoryImpl(radarDao = FakeRadarDao(), preferencesStore = radarPrefs)
        val bookmarkDao = FakeBookmarkDao()
        val readHistoryDao = FakeReadHistoryDao()
        val cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val apiClientProvider: () -> NodeSeekApiClient = { error("NodeSeekApiClient unused in baseline backup test") }
        val sessionRepo = SessionRepositoryImpl(
            apiClientProvider = apiClientProvider,
            cookieBridge = cookieBridge,
        )
        val bookmarkRepo = BookmarkAndHistoryRepositoryImpl(
            apiClientProvider = apiClientProvider,
            sessionRepository = sessionRepo,
            bookmarkDao = bookmarkDao,
            readHistoryDao = readHistoryDao,
        )
        val draftRepo = DraftRepositoryImpl(InMemoryDraftDao())
        val blockedCache = DataStoreBlockedUsersCache(
            PreferenceDataStoreFactory.create(scope = ioScope, produceFile = { File(dir, "blocked.preferences_pb") }),
        )
        val userRepo = UserRepositoryImpl(
            apiClientProvider = apiClientProvider,
            sessionRepository = sessionRepo,
            blockedUsersCache = blockedCache,
        )
        val journalStore = object : BackupRestoreJournalStore {
            private var entry: BackupRestoreJournalEntry? = null
            override suspend fun readPending(): BackupRestoreJournalEntry? = entry
            override suspend fun writePending(entry: BackupRestoreJournalEntry) { this.entry = entry }
            override suspend fun clear() { entry = null }
        }
        return BackupManagerImpl(
            settingsRepository = settingsRepo,
            radarRepository = radarRepo,
            bookmarkAndHistoryRepository = bookmarkRepo,
            draftRepository = draftRepo,
            userRepository = userRepo,
            sessionRepository = sessionRepo,
            blockedUsersCache = blockedCache,
            contentFilterBackupBridge = object : ContentFilterBackupBridge {
                override suspend fun export(): ContentFilterBackupDto = ContentFilterBackupDto()
                override suspend fun restore(dto: ContentFilterBackupDto, merge: Boolean) = Unit
            },
            transactionRunner = object : LocalRestoreTransactionRunner {
                override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
            },
            restoreJournalStore = journalStore,
            fullBackupStores = FullBackupV2Stores(
                benchmarkBaselineRepository = baselineStore,
                exchangeRateRepository = ExchangeRateRepository(File(dir, "fx.json"), ioDispatcher = testDispatcher),
            ),
            backupCryptoV2 = BackupCryptoV2(encryptIterations = 310_000),
            appVersionProvider = { "1.0.0" },
        )
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
}
