package com.nodeseek.app.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.radar.FakeRadarDao
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRuleCanonicalizer
import com.nodeseek.app.domain.radar.TrackedTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RadarRepositoryImplTest {

    private lateinit var tempDir: File
    private lateinit var ioScope: CoroutineScope

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("radar_repository_impl_test").toFile()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        ioScope.cancel()
        tempDir.deleteRecursively()
    }

    private fun createRepository(
        name: String,
        clockMillis: () -> Long = { 1_765_000_000_000L },
    ): Pair<RadarRepositoryImpl, FakeRadarDao> {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { File(tempDir, "${name}_radar.preferences_pb") },
        )
        val dao = FakeRadarDao()
        val repo = RadarRepositoryImpl(
            radarDao = dao,
            preferencesStore = RadarPreferencesStore(dataStore),
            clockMillis = clockMillis,
        )
        return repo to dao
    }

    @Test
    fun `2 rules differing only in exclude keywords export 2 and restore 2 across OVERWRITE and MERGE`() = runBlocking {
        val (sourceRepo, _) = createRepository("source")

        val ruleExcludeBuy = sourceRepo.addKeywordRule(
            keyword = "DMIT",
            excludeWords = "收",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()

        val ruleExcludeWant = sourceRepo.addKeywordRule(
            keyword = "DMIT",
            excludeWords = "求购",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()

        assertNotEquals(
            RadarRuleCanonicalizer.dedupeKey(ruleExcludeBuy),
            RadarRuleCanonicalizer.dedupeKey(ruleExcludeWant),
        )

        // 1. Export: 2 rules exported
        val exportedRules = sourceRepo.getKeywordRulesForBackup()
        assertEquals(2, exportedRules.size)

        // 2. OVERWRITE restore onto a repository with unrelated existing rules -> 2 rules restored
        val (overwriteTargetRepo, _) = createRepository("overwrite_target")
        overwriteTargetRepo.addKeywordRule(
            keyword = "Oracle",
            excludeWords = "",
            categorySlug = "info",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()

        val overwriteResult = overwriteTargetRepo.restoreFromBackup(
            pollingSettings = RadarPollingSettings(),
            keywordRules = exportedRules,
            trackedTopics = emptyList(),
            replaceExisting = true,
        )
        assertEquals(2, overwriteResult.keywordRulesImported)
        val afterOverwrite = overwriteTargetRepo.getKeywordRulesForBackup()
        assertEquals(2, afterOverwrite.size)
        assertEquals(setOf("收", "求购"), afterOverwrite.map { it.excludeWords }.toSet())

        // 3. MERGE restore onto an empty repository -> 2 rules restored
        val (mergeCleanRepo, _) = createRepository("merge_clean")
        val mergeCleanResult = mergeCleanRepo.restoreFromBackup(
            pollingSettings = RadarPollingSettings(),
            keywordRules = exportedRules,
            trackedTopics = emptyList(),
            replaceExisting = false,
        )
        assertEquals(2, mergeCleanResult.keywordRulesImported)
        assertEquals(2, mergeCleanRepo.getKeywordRulesForBackup().size)

        // 4. MERGE restore onto a repository that already has DMIT with empty excludeWords -> both rules imported (total 3)
        val (mergePartialRepo, _) = createRepository("merge_partial")
        mergePartialRepo.addKeywordRule(
            keyword = "DMIT",
            excludeWords = "",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()
        val mergePartialResult = mergePartialRepo.restoreFromBackup(
            pollingSettings = RadarPollingSettings(),
            keywordRules = exportedRules,
            trackedTopics = emptyList(),
            replaceExisting = false,
        )
        assertEquals(2, mergePartialResult.keywordRulesImported)
        assertEquals(
            setOf("", "收", "求购"),
            mergePartialRepo.getKeywordRulesForBackup().map { it.excludeWords }.toSet(),
        )

        // 5. MERGE restore onto a repository that already has one of the two rules -> only the missing rule is imported
        val (mergeOneExistingRepo, _) = createRepository("merge_one_existing")
        mergeOneExistingRepo.addKeywordRule(
            keyword = "dmit",
            excludeWords = "收",
            categorySlug = "TRADE",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()
        val mergeOneExistingResult = mergeOneExistingRepo.restoreFromBackup(
            pollingSettings = RadarPollingSettings(),
            keywordRules = exportedRules,
            trackedTopics = emptyList(),
            replaceExisting = false,
        )
        assertEquals(1, mergeOneExistingResult.keywordRulesImported)
        assertEquals(2, mergeOneExistingRepo.getKeywordRulesForBackup().size)
        assertEquals(
            setOf("收", "求购"),
            mergeOneExistingRepo.getKeywordRulesForBackup().map { it.excludeWords }.toSet(),
        )
    }

    @Test
    fun `canonical rule key normalizes exclude word order, delimiters, and casing uniformly across addRule and restore`() = runBlocking {
        val (repo, _) = createRepository("canonical_exclude")

        val added = repo.addKeywordRule(
            keyword = "Claw JP",
            excludeWords = "AFF, 求购；收",
            categorySlug = "TRADE",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()
        assertEquals("AFF, 求购, 收", added.excludeWords)
        assertEquals("trade", added.categorySlug)

        // Reordered & differently delimited/cased exclude words represent the exact same rule
        val duplicateAttempt = repo.addKeywordRule(
            keyword = "  claw jp  ",
            excludeWords = "收，aff\n求购",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
        )
        assertTrue(duplicateAttempt.isFailure)

        // Incoming backup containing two semantically identical rules with reordered exclude words dedupes to 1
        val (targetRepo, _) = createRepository("canonical_restore")
        val incomingDuplicates = listOf(
            added.copy(id = 10L, excludeWords = "AFF, 求购, 收"),
            added.copy(id = 11L, keyword = "claw jp", excludeWords = "收；aff，求购"),
        )
        val result = targetRepo.restoreFromBackup(
            pollingSettings = RadarPollingSettings(),
            keywordRules = incomingDuplicates,
            trackedTopics = emptyList(),
            replaceExisting = true,
        )
        assertEquals(1, result.keywordRulesImported)
        assertEquals(1, targetRepo.getKeywordRulesForBackup().size)
    }

    @Test
    fun `restoreExactSnapshot preserves original rule IDs, enabled state, polling settings, and tracked topics`() = runBlocking {
        val (repo, _) = createRepository("snapshot_test")

        val r1 = repo.addKeywordRule("RN", excludeWords = "收", categorySlug = "trade").getOrThrow()
        val r2 = repo.addKeywordRule("DMIT", excludeWords = "拼车", categorySlug = "trade").getOrThrow()
        repo.toggleKeywordRule(r2.id, enabled = false)
        repo.trackTopic(
            topicId = 777L,
            title = "原始追踪主题",
            categorySlug = "review",
            categoryName = "测评",
            authorName = "tester",
            currentMaxFloor = 18,
            totalPages = 2,
        )
        repo.updatePollingSettings {
            RadarPollingSettings(
                isEnabled = false,
                foregroundIntervalMinutes = 9,
                backgroundIntervalMinutes = 45,
                wifiOnlyInBackground = true,
            )
        }

        val snapshotPolling = repo.getPollingSettings()
        val snapshotRules = repo.getKeywordRulesForBackup()
        val snapshotTopics = repo.getTrackedTopicsForBackup()

        // Mutate everything
        repo.restoreFromBackup(
            pollingSettings = RadarPollingSettings(isEnabled = true, foregroundIntervalMinutes = 2),
            keywordRules = listOf(r1.copy(keyword = "TemporaryOverwrite", excludeWords = "")),
            trackedTopics = listOf(TrackedTopic(topicId = 999L, title = "Temp")),
            replaceExisting = true,
        )

        // Roll back using restoreExactSnapshot
        repo.restoreExactSnapshot(
            pollingSettings = snapshotPolling,
            keywordRules = snapshotRules,
            trackedTopics = snapshotTopics,
        )

        assertEquals(snapshotPolling, repo.getPollingSettings())
        val restoredRules = repo.getKeywordRulesForBackup()
        assertEquals(snapshotRules, restoredRules)
        assertFalse(restoredRules.first { it.id == r2.id }.isEnabled)
        assertEquals(snapshotTopics, repo.getTrackedTopicsForBackup())
    }

    @Test
    fun `markAlertsReadForTopic marks local alerts read and clears tracked topic unreadNewReplies`() = runBlocking {
        val (repo, dao) = createRepository("mark_read_clears_unread")
        repo.trackTopic(
            topicId = 888L,
            title = "追踪测试贴",
            categorySlug = "tech",
            categoryName = "技术",
            authorName = "tester",
            currentMaxFloor = 10,
            totalPages = 1,
        )
        repo.recordMonitorTopicProgress(
            topicId = 888L,
            title = "追踪测试贴",
            categorySlug = "tech",
            categoryName = "技术",
            authorName = "tester",
            newMaxFloor = 13,
            newTotalPages = 1,
            unreadDelta = 3,
            checkedAtEpochMillis = 1_765_000_005_000L,
        )
        repo.recordAlertIfAbsent(
            com.nodeseek.app.domain.radar.LocalAlertRecord(
                kind = com.nodeseek.app.domain.radar.LocalAlertKind.TRACKED_REPLY,
                dedupKey = "reply:888:13",
                topicId = 888L,
                topicTitle = "追踪测试贴",
                summarySnippet = "3 条新回复",
                isRead = false,
            ),
        )

        assertEquals(3, dao.getTrackedTopic(888L)?.unreadNewReplies)

        repo.markAlertsReadForTopic(888L)

        assertEquals(0, dao.getTrackedTopic(888L)?.unreadNewReplies)
        val alerts = dao.observeAlertRecords().first()
        assertEquals(1, alerts.size)
        assertTrue(alerts.single().isRead)
    }
}
