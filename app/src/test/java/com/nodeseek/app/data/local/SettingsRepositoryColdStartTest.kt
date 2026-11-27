package com.nodeseek.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nodeseek.app.domain.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SettingsRepositoryColdStartTest {

    private lateinit var tempDir: File
    private lateinit var ioScope: CoroutineScope

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("settings_cold_start_test").toFile()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        ioScope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `awaitLoadedSettings suspends until delayed DataStore hydrates and updates settings value`() = runBlocking {
        val dsFile = File(tempDir, "cold_start_settings.preferences_pb")
        val seedDataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { dsFile },
        )
        val seedRepo = SettingsRepositoryImpl(
            dataStore = seedDataStore,
            externalScope = ioScope,
            secretCipher = PassThroughCipher(),
        )
        seedRepo.setSeekMailApiKey("sk_live_persisted_secret_123")
        seedRepo.setPreferRandomCheckIn(true)
        seedRepo.awaitLoadedSettings()

        val delayedDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = seedDataStore.data.onStart {
                delay(40L)
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = seedDataStore.updateData(transform)
        }

        val coldRepo = SettingsRepositoryImpl(
            dataStore = delayedDataStore,
            externalScope = ioScope,
            secretCipher = PassThroughCipher(),
        )

        // Immediately on construction, StateFlow still holds initial default AppSettings().
        assertEquals("", coldRepo.settings.value.seekMailApiKey)
        assertFalse(coldRepo.settings.value.preferRandomCheckIn)

        val loaded = coldRepo.awaitLoadedSettings()
        assertEquals("sk_live_persisted_secret_123", loaded.seekMailApiKey)
        assertTrue(loaded.preferRandomCheckIn)
        // StateFlow is guaranteed to match before awaitLoadedSettings() returns.
        assertEquals(loaded, coldRepo.settings.value)
    }

    @Test
    fun `awaitLoadedSettings returns updated settings after post-hydration edits`() = runBlocking {
        val dsFile = File(tempDir, "warm_update_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { dsFile },
        )
        val repo = SettingsRepositoryImpl(
            dataStore = dataStore,
            externalScope = ioScope,
            secretCipher = PassThroughCipher(),
        )
        repo.setSeekMailApiKey("sk_initial")
        withTimeout(2_000L) {
            repo.settings.first { it.seekMailApiKey == "sk_initial" }
        }
        val first = repo.awaitLoadedSettings()
        assertEquals("sk_initial", first.seekMailApiKey)

        repo.setSeekMailApiKey("sk_updated")
        withTimeout(2_000L) {
            repo.settings.first { it.seekMailApiKey == "sk_updated" }
        }
        val latest = repo.awaitLoadedSettings()
        assertEquals("sk_updated", latest.seekMailApiKey)
    }

    @Test
    fun `awaitLoadedSettings completes cleanly with defaults on DataStore IOException`() = runBlocking {
        val failingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                throw IOException("Simulated disk error")
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = throw IOException("Simulated disk error")
        }

        val repo = SettingsRepositoryImpl(
            dataStore = failingDataStore,
            externalScope = ioScope,
            secretCipher = PassThroughCipher(),
        )

        val loaded = repo.awaitLoadedSettings()
        assertEquals(AppSettings(), loaded)
    }

    @Test
    fun `setNodeImageApiKey and setSeekMailApiKey update settings value immediately even when DataStore flow lags`() = runBlocking {
        val dsFile = File(tempDir, "lagging_flow_settings.preferences_pb")
        val backingStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { dsFile },
        )
        val laggingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = backingStore.data.onStart {
                delay(250L)
                emit(emptyPreferences())
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = backingStore.updateData(transform)
        }
        val repo = SettingsRepositoryImpl(
            dataStore = laggingDataStore,
            externalScope = ioScope,
            secretCipher = PassThroughCipher(),
        )

        repo.setNodeImageApiKey("  nk_live_123  ")
        assertEquals("nk_live_123", repo.settings.value.nodeImageApiKey)

        repo.setSeekMailApiKey("  sk_live_456  ")
        assertEquals("sk_live_456", repo.settings.value.seekMailApiKey)

        // Wait past the delayed stale snapshot emission and verify neither key regresses
        delay(350L)
        assertEquals("nk_live_123", repo.settings.value.nodeImageApiKey)
        assertEquals("sk_live_456", repo.settings.value.seekMailApiKey)
    }

    @Test
    fun `setNodeImageApiKey does not mutate settings value when DataStore edit or cipher fails`() = runBlocking {
        val failingEditDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                emit(emptyPreferences())
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = throw IOException("Simulated write error")
        }
        val ioFailRepo = SettingsRepositoryImpl(
            dataStore = failingEditDataStore,
            externalScope = ioScope,
            secretCipher = PassThroughCipher(),
        )
        ioFailRepo.awaitLoadedSettings()
        val ioResult = runCatching { ioFailRepo.setNodeImageApiKey("nk_should_fail") }
        assertTrue(ioResult.isFailure)
        assertTrue(ioResult.exceptionOrNull() is IOException)
        assertEquals("", ioFailRepo.settings.value.nodeImageApiKey)

        val dsFile = File(tempDir, "cipher_fail_settings.preferences_pb")
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { dsFile },
            )
            val failingCipher = object : SettingsSecretCipher {
                override fun isEncryptedEnvelope(storedValue: String): Boolean = false
                override fun encrypt(plainText: String): String =
                    throw IllegalStateException("No available cipher to encrypt settings API key")
                override fun decrypt(storedValue: String): String = storedValue
            }
            val cipherFailRepo = SettingsRepositoryImpl(
                dataStore = dataStore,
                externalScope = ioScope,
                secretCipher = failingCipher,
            )
            cipherFailRepo.awaitLoadedSettings()
            val cipherResult = runCatching { cipherFailRepo.setNodeImageApiKey("nk_cipher_fail") }
            assertTrue(cipherResult.isFailure)
            assertTrue(cipherResult.exceptionOrNull() is IllegalStateException)
            assertEquals("", cipherFailRepo.settings.value.nodeImageApiKey)
        } finally {
            dsScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `concurrent setting writers preserve DataStore and settings StateFlow consistency`() = runBlocking {
        val dsFile = File(tempDir, "concurrent_writers_settings.preferences_pb")
        val dsScope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val liveSnapshot = try {
            val dataStore1 = PreferenceDataStoreFactory.create(
                scope = dsScope1,
                produceFile = { dsFile },
            )
            val repo1 = SettingsRepositoryImpl(
                dataStore = dataStore1,
                externalScope = repoScope1,
                secretCipher = PassThroughCipher(),
            )
            repo1.awaitLoadedSettings()

            val jobs = (0 until 20).map { index ->
                launch(Dispatchers.IO) {
                    when (index % 4) {
                        0 -> repo1.setNodeImageApiKey("nk_concurrent_$index")
                        1 -> repo1.setSeekMailApiKey("sk_concurrent_$index")
                        2 -> repo1.setHomepageCategoryOrder(listOf("tech", "daily", "trade_$index"))
                        else -> repo1.setFontScale(0.90f + (index % 5) * 0.05f)
                    }
                }
            }
            jobs.joinAll()
            repo1.settings.value
        } finally {
            repoScope1.coroutineContext[Job]?.cancelAndJoin()
            dsScope1.coroutineContext[Job]?.cancelAndJoin()
        }

        val dsScope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore2 = PreferenceDataStoreFactory.create(
                scope = dsScope2,
                produceFile = { dsFile },
            )
            val repo2 = SettingsRepositoryImpl(
                dataStore = dataStore2,
                externalScope = repoScope2,
                secretCipher = PassThroughCipher(),
            )
            val reloaded = repo2.awaitLoadedSettings()
            assertEquals(liveSnapshot, reloaded)
        } finally {
            repoScope2.coroutineContext[Job]?.cancelAndJoin()
            dsScope2.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `settingsFlow_multipleStaleEmissionsAfterSequentialEdits_neverRevertsNewerState`() = runBlocking {
        val dataFlow = kotlinx.coroutines.flow.MutableSharedFlow<Preferences>(replay = 1)
        var currentBackingPrefs: Preferences = emptyPreferences()
        dataFlow.emit(currentBackingPrefs)

        val customDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = dataFlow

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences {
                val updated = transform(currentBackingPrefs)
                currentBackingPrefs = updated
                return updated
            }
        }

        val repo = SettingsRepositoryImpl(
            dataStore = customDataStore,
            externalScope = ioScope,
            secretCipher = PassThroughCipher(),
        )
        repo.awaitLoadedSettings()

        repo.setSeekMailApiKey("sk_edit_1")
        val snap1 = currentBackingPrefs

        repo.setSeekMailApiKey("sk_edit_2")
        val snap2 = currentBackingPrefs
        assertEquals("sk_edit_2", repo.settings.value.seekMailApiKey)

        // Emit stale initial snapshot and stale Edit 1 snapshot out of order after Edit 2
        dataFlow.emit(emptyPreferences())
        dataFlow.emit(snap1)
        delay(50L)
        assertEquals("sk_edit_2", repo.settings.value.seekMailApiKey)

        // Finally emit matching snap2 and ensure newer value remains intact
        dataFlow.emit(snap2)
        delay(50L)
        assertEquals("sk_edit_2", repo.settings.value.seekMailApiKey)
    }

    private class PassThroughCipher : SettingsSecretCipher {
        override fun isEncryptedEnvelope(storedValue: String): Boolean = storedValue.startsWith("enc:")
        override fun encrypt(plainText: String): String = if (plainText.isEmpty()) "" else "enc:$plainText"
        override fun decrypt(storedValue: String): String = storedValue.removePrefix("enc:")
    }
}
