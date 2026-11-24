package com.nodeseek.app.ui.hub

import com.nodeseek.app.domain.backup.BackupExportPayload
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.backup.BackupPreviewSummary
import com.nodeseek.app.domain.backup.BackupRestoreSummary
import com.nodeseek.app.domain.backup.EncryptedBackupErrorKind
import com.nodeseek.app.domain.backup.EncryptedBackupException
import com.nodeseek.app.domain.backup.EncryptedBackupFileNaming
import com.nodeseek.app.domain.backup.EncryptedBackupPreviewSummary
import com.nodeseek.app.domain.backup.EncryptedBackupRestoreSummary
import com.nodeseek.app.domain.backup.NodePulseBackupBundle
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.domain.model.FloorAuthor
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
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedBackupUiTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeEncryptedBackupManager(
        private val inspectResultProvider: (String, String) -> Result<EncryptedBackupPreviewSummary>,
        private val restoreResultProvider: (String, String, RestoreStrategy) -> Result<EncryptedBackupRestoreSummary>,
    ) : BackupManager {
        override suspend fun exportBackupJson(includeApiKeys: Boolean): Result<String> =
            Result.failure(UnsupportedOperationException())

        override suspend fun exportBundle(includeApiKeys: Boolean): Result<NodePulseBackupBundle> =
            Result.failure(UnsupportedOperationException())

        override suspend fun exportBackupPayload(includeApiKeys: Boolean): Result<BackupExportPayload> =
            Result.failure(UnsupportedOperationException())

        override suspend fun inspectBackup(rawJson: String): Result<BackupPreviewSummary> =
            Result.failure(UnsupportedOperationException())

        override suspend fun restoreBackup(
            rawJson: String,
            strategy: RestoreStrategy,
        ): Result<BackupRestoreSummary> = Result.failure(UnsupportedOperationException())

        override fun isEncryptedBackupEnvelope(rawContent: String): Boolean =
            rawContent.contains("ANODE_ENCRYPTED_BACKUP")

        override suspend fun inspectEncryptedFullBackup(
            rawContent: String,
            passphrase: String,
        ): Result<EncryptedBackupPreviewSummary> = inspectResultProvider(rawContent, passphrase)

        override suspend fun restoreEncryptedFullBackup(
            rawContent: String,
            passphrase: String,
            strategy: RestoreStrategy,
        ): Result<EncryptedBackupRestoreSummary> = restoreResultProvider(rawContent, passphrase, strategy)
    }

    @Test
    fun `validateExportPassphrase enforces min 8 chars, non-blank, and exact confirmation match`() {
        assertTrue(validateExportPassphrase("1234567", "1234567") is PassphraseValidationResult.Invalid)
        assertTrue(validateExportPassphrase("        ", "        ") is PassphraseValidationResult.Invalid)
        assertTrue(validateExportPassphrase("StrongPass1", "StrongPass2") is PassphraseValidationResult.Invalid)
        assertEquals(
            PassphraseValidationResult.Valid,
            validateExportPassphrase("StrongPass!2026", "StrongPass!2026"),
        )
    }

    @Test
    fun `EncryptedBackupFileNaming uses anode-full-backup timestamped extension`() {
        val fileName = EncryptedBackupFileNaming.createDefaultFileName(
            epochMillis = 1_765_000_000_000L,
            zoneId = ZoneOffset.UTC,
        )
        assertTrue(fileName.startsWith("anode-full-backup-"))
        assertTrue(fileName.endsWith(".anodebackup"))
    }

    @Test
    fun `formatEncryptedRestoreSnackbar summarizes counts and pending remote block sync`() {
        val summary = EncryptedBackupRestoreSummary(
            strategy = RestoreStrategy.OVERWRITE,
            radarKeywordRulesImported = 3,
            radarTrackedTopicsImported = 1,
            blockedUsersImported = 2,
            pendingRemoteBlockUsers = listOf(FloorAuthor(1L, "u1"), FloorAuthor(2L, "u2")),
            contentFilterRulesImported = 4,
            mutedCategoriesCount = 1,
            bookmarksImported = 5,
            draftsImported = 2,
            recentSearchQueriesCount = 6,
            fleetPanelsImported = 2,
            fleetAssetsImported = 7,
            restockWatchesImported = 4,
            sshServersImported = 3,
            sshCustomPresetsImported = 1,
            seekMailTagsImported = 2,
            seekMailArchivedImported = 1,
            sellerNotesImported = 2,
            officialTradeApiKeyImported = true,
            manualFxOverridesImported = 1,
        )
        val text = formatEncryptedRestoreSnackbar(summary)
        assertTrue(text.contains("覆盖恢复 完成"))
        assertTrue(text.contains("7 资产"))
        assertTrue(text.contains("2 探针"))
        assertTrue(text.contains("3 SSH"))
        assertTrue(text.contains("4 补货"))
        assertTrue(text.contains("2 位屏蔽用户"))
    }

    @Test
    fun `EncryptedBackupViewModel keeps unlock dialog open on wrong passphrase and transitions on success`() =
        runTest(testDispatcher) {
            val samplePreview = EncryptedBackupPreviewSummary(
                formatVersion = 2,
                schemaVersion = 2,
                appVersion = "2.11.2",
                exportedAtEpochMillis = 1_765_000_000_000L,
                kdfAlgorithm = "PBKDF2-HMAC-SHA256",
                kdfIterations = 310_000,
                cipherAlgorithm = "AES-256-GCM",
                radarKeywordRuleCount = 1,
                radarTrackedTopicCount = 0,
                blockedUserCount = 0,
                contentFilterRuleCount = 0,
                mutedCategoryCount = 0,
                bookmarkCount = 2,
                draftCount = 0,
                recentSearchQueryCount = 0,
                containsApiKeys = true,
                fleetPanelCount = 1,
                fleetAssetCount = 2,
                restockWatchCount = 1,
                sshServerCount = 3,
                sshCustomPresetCount = 1,
                seekMailTagCount = 1,
                seekMailArchivedCount = 0,
                sellerNoteCount = 1,
                hasOfficialTradeApiKey = true,
                overseasBlockedMerchantCount = 1,
                overseasBlockedKeywordCount = 0,
                overseasNotificationsEnabled = true,
                activityNotificationsEnabled = false,
                manualFxOverrideCount = 0,
            )
            val sampleRestore = EncryptedBackupRestoreSummary(
                strategy = RestoreStrategy.MERGE,
                radarKeywordRulesImported = 1,
                radarTrackedTopicsImported = 0,
                blockedUsersImported = 0,
                contentFilterRulesImported = 0,
                mutedCategoriesCount = 0,
                bookmarksImported = 2,
                draftsImported = 0,
                recentSearchQueriesCount = 0,
                fleetPanelsImported = 1,
                fleetAssetsImported = 2,
                restockWatchesImported = 1,
                sshServersImported = 3,
                sshCustomPresetsImported = 1,
                seekMailTagsImported = 1,
                seekMailArchivedImported = 0,
                sellerNotesImported = 1,
                officialTradeApiKeyImported = true,
                manualFxOverridesImported = 0,
            )

            val manager = FakeEncryptedBackupManager(
                inspectResultProvider = { _, pass ->
                    if (pass == "CorrectPass!2026") {
                        Result.success(samplePreview)
                    } else {
                        Result.failure(
                            EncryptedBackupException(
                                kind = EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED,
                                message = "口令错误或备份文件已被篡改，无法解密",
                            ),
                        )
                    }
                },
                restoreResultProvider = { _, _, strategy ->
                    Result.success(sampleRestore.copy(strategy = strategy))
                },
            )

            val vm = EncryptedBackupViewModel(manager, ioDispatcher = testDispatcher)
            assertFalse(vm.uiState.value.isBusy)

            // Simulate user opening export dialog and cancelling
            vm.openExportDialog()
            assertTrue(vm.uiState.value.showExportPassphraseDialog)
            vm.dismissExportDialog()
            assertFalse(vm.uiState.value.showExportPassphraseDialog)

            // Reject non-encrypted v1 JSON in v2 unlock path
            assertFalse(vm.openUnlockForRawEnvelope("""{"schemaVersion":1}"""))
            assertNull(vm.uiState.value.pendingUnlockRawEnvelope)

            // Open unlock dialog for valid v2 envelope
            assertTrue(vm.openUnlockForRawEnvelope("""{"format":"ANODE_ENCRYPTED_BACKUP"}"""))

            // Wrong passphrase -> stays on unlock dialog with inline error
            vm.unlockAndInspect("WrongPass123")
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.pendingUnlockRawEnvelope)
            assertTrue(vm.uiState.value.unlockErrorMessage!!.contains("口令错误"))
            assertNull(vm.uiState.value.pendingRestore)

            // Correct passphrase -> transitions to preview confirm dialog with MERGE default
            vm.unlockAndInspect("CorrectPass!2026")
            advanceUntilIdle()
            assertNull(vm.uiState.value.pendingUnlockRawEnvelope)
            assertNull(vm.uiState.value.unlockErrorMessage)
            assertNotNull(vm.uiState.value.pendingRestore)
            assertEquals(RestoreStrategy.MERGE, vm.uiState.value.selectedStrategy)

            // Switch to OVERWRITE and confirm restore
            vm.selectStrategy(RestoreStrategy.OVERWRITE)
            assertEquals(RestoreStrategy.OVERWRITE, vm.uiState.value.selectedStrategy)
            vm.confirmRestore()
            advanceUntilIdle()
            assertNull(vm.uiState.value.pendingRestore)
            assertFalse(vm.uiState.value.isRestoring)
        }
}
