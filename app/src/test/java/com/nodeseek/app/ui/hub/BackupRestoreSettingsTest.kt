package com.nodeseek.app.ui.hub

import com.nodeseek.app.data.backup.BackupManagerImpl
import com.nodeseek.app.domain.backup.AppSettingsBackupDto
import com.nodeseek.app.domain.backup.BackupRestoreSummary
import com.nodeseek.app.domain.backup.BackupSyncTargetAccount
import com.nodeseek.app.domain.backup.BlocklistBackupDto
import com.nodeseek.app.domain.backup.NodePulseBackupBundle
import com.nodeseek.app.domain.backup.RadarBackupDto
import com.nodeseek.app.domain.backup.RemoteBlocklistSyncResult
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.FloorAuthor
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreSettingsTest {

    @Test
    fun `formatSyncTargetAccountLabel surfaces username and uid or guest fallback`() {
        assertEquals(
            "@NodeTester (UID: 101)",
            formatSyncTargetAccountLabel(BackupSyncTargetAccount(uid = 101L, username = "NodeTester")),
        )
        assertEquals("未登录账号", formatSyncTargetAccountLabel(null))
        assertEquals("未登录账号", formatSyncTargetAccountLabel(BackupSyncTargetAccount(uid = 0L, username = "")))
    }

    @Test
    fun `formatRestoreOutcomeSnackbar distinguishes signed-in pending sync vs signed-out local-only import`() {
        val blockedUsers = listOf(
            FloorAuthor(uid = 11L, username = "spam_bot"),
            FloorAuthor(uid = 22L, username = "aff_troll"),
        )
        val signedInOutcome = BackupRestoreSummary(
            strategy = RestoreStrategy.OVERWRITE,
            radarKeywordRulesImported = 2,
            radarTrackedTopicsImported = 1,
            blockedUsersImported = 2,
            pendingRemoteBlockUsers = blockedUsers,
            targetSyncAccount = BackupSyncTargetAccount(uid = 101L, username = "Tester"),
            contentFilterRulesImported = 1,
            mutedCategoriesCount = 0,
            bookmarksImported = 3,
            draftsImported = 1,
            recentSearchQueriesCount = 2,
        )
        val signedInText = formatRestoreOutcomeSnackbar(signedInOutcome)
        assertTrue(signedInText.contains("覆盖恢复 完成"))
        assertTrue(signedInText.contains("@Tester (UID: 101)"))
        assertTrue(signedInText.contains("2 位屏蔽用户"))

        val guestOutcome = signedInOutcome.copy(targetSyncAccount = null)
        val guestText = formatRestoreOutcomeSnackbar(guestOutcome)
        assertTrue(guestText.contains("仅导入本地"))
        assertTrue(guestText.contains("登录目标账号后可确认同步"))
    }

    @Test
    fun `formatRemoteSyncOutcomeSnackbar and formatBlockedUsersPreview summarize explicit sync results`() {
        val target = BackupSyncTargetAccount(uid = 202L, username = "OpsAdmin")
        val fullSuccess = RemoteBlocklistSyncResult(
            targetAccount = target,
            requestedCount = 2,
            syncedCount = 2,
            failedCount = 0,
        )
        assertEquals(
            "已向 @OpsAdmin (UID: 202) 同步 2 位屏蔽用户",
            formatRemoteSyncOutcomeSnackbar(fullSuccess),
        )

        val partialFailure = RemoteBlocklistSyncResult(
            targetAccount = target,
            requestedCount = 3,
            syncedCount = 2,
            failedCount = 1,
            failedUsernames = listOf("flaky_user"),
        )
        val partialText = formatRemoteSyncOutcomeSnackbar(partialFailure)
        assertTrue(partialText.contains("@OpsAdmin (UID: 202)"))
        assertTrue(partialText.contains("同步 2 人"))
        assertTrue(partialText.contains("1 人失败"))

        val sevenUsers = (1..7).map { FloorAuthor(uid = it.toLong(), username = "u$it") }
        assertEquals(
            "@u1、@u2、@u3、@u4、@u5、@u6 等 7 人",
            formatBlockedUsersPreview(sevenUsers, maxNames = 6),
        )
    }

    @Test
    fun `legacy backup JSON without AwayDigest fields restores defaults and new fields round-trip cleanly`() {
        val legacyJson = """
            {
              "schemaVersion": 1,
              "appVersion": "1.0.0",
              "exportedAtEpochMillis": 1760000000000,
              "settings": {
                "themeMode": "OBSIDIAN_DARK",
                "purePitchAmoled": true,
                "fontScale": 1.0
              },
              "radar": {},
              "blocklist": {},
              "bookmarks": [],
              "drafts": []
            }
        """.trimIndent()

        val decodedLegacy = BackupManagerImpl.backupJson.decodeFromString<NodePulseBackupBundle>(legacyJson)
        assertTrue(decodedLegacy.settings.awayDigestEnabled)
        assertEquals(AwayDigestTriggerMode.CUSTOM_DURATION, decodedLegacy.settings.awayDigestTriggerMode)
        assertEquals(60, decodedLegacy.settings.awayDigestThresholdMinutes)

        val customBundle = NodePulseBackupBundle(
            appVersion = "1.2.0",
            exportedAtEpochMillis = 1765000000000L,
            settings = AppSettingsBackupDto(
                awayDigestEnabled = false,
                awayDigestTriggerMode = AwayDigestTriggerMode.DURATION_OR_REOPEN,
                awayDigestThresholdMinutes = 240,
            ),
            radar = RadarBackupDto(),
            blocklist = BlocklistBackupDto(),
            bookmarks = emptyList(),
            drafts = emptyList(),
        )
        val encoded = BackupManagerImpl.backupJson.encodeToString(customBundle)
        val roundTripped = BackupManagerImpl.backupJson.decodeFromString<NodePulseBackupBundle>(encoded)
        assertFalse(roundTripped.settings.awayDigestEnabled)
        assertEquals(AwayDigestTriggerMode.DURATION_OR_REOPEN, roundTripped.settings.awayDigestTriggerMode)
        assertEquals(240, roundTripped.settings.awayDigestThresholdMinutes)
    }
}
