package com.nodeseek.app.data.radar

import android.annotation.SuppressLint
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.domain.radar.RadarPollingSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class RadarWorkSchedulerTest {

    private lateinit var tempDir: File
    private lateinit var dataStoreScope: CoroutineScope

    private class RecordingWorkManagerGateway : RadarWorkManagerGateway {
        data class EnqueueCall(
            val uniqueWorkName: String,
            val policy: ExistingPeriodicWorkPolicy,
            val request: PeriodicWorkRequest,
        )

        data class OneTimeEnqueueCall(
            val uniqueWorkName: String,
            val policy: ExistingWorkPolicy,
            val request: OneTimeWorkRequest,
        )

        val enqueueCalls = mutableListOf<EnqueueCall>()
        val oneTimeEnqueueCalls = mutableListOf<OneTimeEnqueueCall>()
        val cancelCalls = mutableListOf<String>()

        override fun enqueueUniquePeriodicWork(
            uniqueWorkName: String,
            existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            enqueueCalls.add(
                EnqueueCall(
                    uniqueWorkName = uniqueWorkName,
                    policy = existingPeriodicWorkPolicy,
                    request = request,
                ),
            )
        }

        override fun enqueueUniqueWork(
            uniqueWorkName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            oneTimeEnqueueCalls.add(
                OneTimeEnqueueCall(
                    uniqueWorkName = uniqueWorkName,
                    policy = existingWorkPolicy,
                    request = request,
                ),
            )
        }

        override fun cancelUniqueWork(uniqueWorkName: String) {
            cancelCalls.add(uniqueWorkName)
        }
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("radar_scheduler_test").toFile()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `buildConstraints maps wifiOnlyInBackground and requireBatteryNotLowInBackground accurately`() {
        val defaults = RadarPollingSettings()
        assertEquals(15, defaults.backgroundIntervalMinutes)
        assertFalse(defaults.requireBatteryNotLowInBackground)

        val defaultConstraints = RadarWorkScheduler.buildConstraints(defaults)
        assertEquals(NetworkType.CONNECTED, defaultConstraints.requiredNetworkType)
        assertFalse(defaultConstraints.requiresBatteryNotLow())

        val wifiAndRequireBattery = defaults.copy(
            wifiOnlyInBackground = true,
            requireBatteryNotLowInBackground = true,
        )
        val unmeteredConstraints = RadarWorkScheduler.buildConstraints(wifiAndRequireBattery)
        assertEquals(NetworkType.UNMETERED, unmeteredConstraints.requiredNetworkType)
        assertTrue(unmeteredConstraints.requiresBatteryNotLow())
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `buildPeriodicWorkRequest enforces 15-minute minimum interval and exponential backoff`() {
        assertEquals(15L, RadarWorkScheduler.coerceIntervalMinutes(1))
        assertEquals(15L, RadarWorkScheduler.coerceIntervalMinutes(5))
        assertEquals(15L, RadarWorkScheduler.coerceIntervalMinutes(14))
        assertEquals(15L, RadarWorkScheduler.coerceIntervalMinutes(15))
        assertEquals(30L, RadarWorkScheduler.coerceIntervalMinutes(30))
        assertEquals(60L, RadarWorkScheduler.coerceIntervalMinutes(60))
        assertEquals(180L, RadarWorkScheduler.coerceIntervalMinutes(300))

        val tooShortRequest = RadarWorkScheduler.buildPeriodicWorkRequest(
            RadarPollingSettings(
                backgroundIntervalMinutes = 5,
                wifiOnlyInBackground = true,
                requireBatteryNotLowInBackground = true,
            ),
        )
        assertEquals(
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            tooShortRequest.workSpec.intervalDuration,
        )
        assertEquals(NetworkType.UNMETERED, tooShortRequest.workSpec.constraints.requiredNetworkType)
        assertTrue(tooShortRequest.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(BackoffPolicy.EXPONENTIAL, tooShortRequest.workSpec.backoffPolicy)
        assertTrue(tooShortRequest.tags.contains(RadarWorkScheduler.WORK_TAG))

        val thirtyMinRequest = RadarWorkScheduler.buildPeriodicWorkRequest(
            RadarPollingSettings(backgroundIntervalMinutes = 30),
        )
        assertEquals(30L * 60_000L, thirtyMinRequest.workSpec.intervalDuration)
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `sync enqueues UPDATE periodic work when enabled with any feature, and cancels otherwise`() {
        val gateway = RecordingWorkManagerGateway()
        val scheduler = RadarWorkScheduler(gateway)

        // All enabled -> enqueue UPDATE
        scheduler.sync(RadarPollingSettings(isEnabled = true))
        assertEquals(1, gateway.enqueueCalls.size)
        assertEquals(0, gateway.cancelCalls.size)
        assertEquals(RadarWorkScheduler.UNIQUE_WORK_NAME, gateway.enqueueCalls.last().uniqueWorkName)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, gateway.enqueueCalls.last().policy)

        // Only inbox push enabled -> still schedules WorkManager
        scheduler.sync(
            RadarPollingSettings(
                isEnabled = true,
                keywordAlertsEnabled = false,
                replyTrackingEnabled = false,
                inboxPushEnabled = true,
                backgroundIntervalMinutes = 60,
            ),
        )
        assertEquals(2, gateway.enqueueCalls.size)
        assertEquals(60L * 60_000L, gateway.enqueueCalls.last().request.workSpec.intervalDuration)

        // Master enabled, but all 3 sub-features disabled -> cancel
        scheduler.sync(
            RadarPollingSettings(
                isEnabled = true,
                keywordAlertsEnabled = false,
                replyTrackingEnabled = false,
                inboxPushEnabled = false,
            ),
        )
        assertEquals(listOf(RadarWorkScheduler.UNIQUE_WORK_NAME), gateway.cancelCalls)

        // Master disabled even with sub-features enabled -> cancel
        scheduler.sync(
            RadarPollingSettings(
                isEnabled = false,
                keywordAlertsEnabled = true,
                replyTrackingEnabled = true,
                inboxPushEnabled = true,
            ),
        )
        assertEquals(2, gateway.cancelCalls.size)
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `observePollingSettings reacts immediately to background setting changes and avoids churn on foreground-only edits`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val gateway = RecordingWorkManagerGateway()
        val scheduler = RadarWorkScheduler(gateway)
        val settingsFlow = MutableStateFlow(RadarPollingSettings())

        scheduler.observePollingSettings(
            scope = CoroutineScope(testDispatcher),
            pollingSettingsFlow = settingsFlow,
        )
        advanceUntilIdle()
        assertEquals(1, gateway.enqueueCalls.size)
        assertEquals(0, gateway.cancelCalls.size)

        // Changing foreground-only settings should NOT re-enqueue WorkManager
        settingsFlow.value = settingsFlow.value.copy(
            foregroundIntervalMinutes = 1,
            quietHoursEnabled = true,
        )
        advanceUntilIdle()
        assertEquals(1, gateway.enqueueCalls.size)

        // Changing background interval / battery / wifi constraint updates WorkManager immediately
        settingsFlow.value = settingsFlow.value.copy(
            backgroundIntervalMinutes = 30,
            wifiOnlyInBackground = true,
            requireBatteryNotLowInBackground = false,
        )
        advanceUntilIdle()
        assertEquals(2, gateway.enqueueCalls.size)
        val updatedSpec = gateway.enqueueCalls.last().request.workSpec
        assertEquals(30L * 60_000L, updatedSpec.intervalDuration)
        assertEquals(NetworkType.UNMETERED, updatedSpec.constraints.requiredNetworkType)
        assertFalse(updatedSpec.constraints.requiresBatteryNotLow())

        // Turning off master switch cancels unique work immediately
        settingsFlow.value = settingsFlow.value.copy(isEnabled = false)
        advanceUntilIdle()
        assertEquals(1, gateway.cancelCalls.size)
        assertEquals(RadarWorkScheduler.UNIQUE_WORK_NAME, gateway.cancelCalls.single())

        scheduler.stopObserving()
    }

    @Test
    fun `RadarPreferencesStore persists requireBatteryNotLowInBackground and coerces legacy intervals below 15m`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir, "radar_prefs_workmanager.preferences_pb") },
        )
        // Seed legacy 5-minute background interval directly in DataStore
        dataStore.edit { prefs ->
            prefs[intPreferencesKey("radar_bg_interval_minutes")] = 5
        }

        val store = RadarPreferencesStore(dataStore)
        val migrated = store.getPollingSettings()
        assertEquals(15, migrated.backgroundIntervalMinutes)
        assertFalse(migrated.requireBatteryNotLowInBackground)

        store.updatePollingSettings {
            it.copy(
                backgroundIntervalMinutes = 45,
                requireBatteryNotLowInBackground = true,
                wifiOnlyInBackground = true,
            )
        }
        val reloaded = store.pollingSettingsFlow.first()
        assertEquals(45, reloaded.backgroundIntervalMinutes)
        assertTrue(reloaded.requireBatteryNotLowInBackground)
        assertTrue(reloaded.wifiOnlyInBackground)

        // Attempt to store < 15m gets coerced to 15m
        store.updatePollingSettings { it.copy(backgroundIntervalMinutes = 3) }
        assertEquals(15, store.getPollingSettings().backgroundIntervalMinutes)
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `onAppEnteredBackground keeps periodic schedule, enqueues prompt background check, and cancels on foreground return`() {
        val gateway = RecordingWorkManagerGateway()
        val scheduler = RadarWorkScheduler(gateway)

        // User has 0 keyword rules & 0 tracked topics, only inbox push enabled -> schedules KEEP + one-shot entry check
        scheduler.onAppEnteredBackground(
            RadarPollingSettings(
                isEnabled = true,
                keywordAlertsEnabled = false,
                replyTrackingEnabled = false,
                inboxPushEnabled = true,
            ),
        )
        assertEquals(1, gateway.enqueueCalls.size)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, gateway.enqueueCalls.single().policy)
        assertEquals(1, gateway.oneTimeEnqueueCalls.size)
        val entryCall = gateway.oneTimeEnqueueCalls.single()
        assertEquals(RadarWorkScheduler.UNIQUE_BACKGROUND_ENTRY_WORK_NAME, entryCall.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.REPLACE, entryCall.policy)
        assertEquals(
            RadarWorkScheduler.BACKGROUND_ENTRY_DELAY_SECONDS * 1_000L,
            entryCall.request.workSpec.initialDelay,
        )

        // Returning to foreground cancels pending one-shot background entry check while preserving periodic work
        scheduler.onAppEnteredForeground()
        assertEquals(listOf(RadarWorkScheduler.UNIQUE_BACKGROUND_ENTRY_WORK_NAME), gateway.cancelCalls)

        // Re-entering background then disabling cancels both periodic and background-entry work
        scheduler.onAppEnteredBackground(RadarPollingSettings(isEnabled = true))
        gateway.cancelCalls.clear()
        scheduler.sync(RadarPollingSettings(isEnabled = false))
        assertEquals(
            listOf(
                RadarWorkScheduler.UNIQUE_WORK_NAME,
                RadarWorkScheduler.UNIQUE_BACKGROUND_ENTRY_WORK_NAME,
            ),
            gateway.cancelCalls,
        )
    }

    @Test
    fun `onAppEnteredBackgroundAsync drops stale background entry enqueue when app returns to foreground before settings load`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val gateway = RecordingWorkManagerGateway()
        val scheduler = RadarWorkScheduler(gateway)
        val delayedSettings = CompletableDeferred<RadarPollingSettings>()

        // 1. Rapid background -> foreground bounce while settings read is still suspended
        scheduler.onAppEnteredBackgroundAsync(CoroutineScope(testDispatcher)) {
            delayedSettings.await()
        }
        scheduler.onAppEnteredForeground()
        delayedSettings.complete(RadarPollingSettings(isEnabled = true, inboxPushEnabled = true))
        advanceUntilIdle()

        assertTrue(gateway.oneTimeEnqueueCalls.isEmpty())
        assertTrue(gateway.enqueueCalls.isEmpty())

        // 2. Genuine stay-in-background enqueues both KEEP periodic work and one-shot entry check
        scheduler.onAppEnteredBackgroundAsync(CoroutineScope(testDispatcher)) {
            RadarPollingSettings(isEnabled = true, inboxPushEnabled = true)
        }
        advanceUntilIdle()
        assertEquals(1, gateway.enqueueCalls.size)
        assertEquals(1, gateway.oneTimeEnqueueCalls.size)
    }
}
