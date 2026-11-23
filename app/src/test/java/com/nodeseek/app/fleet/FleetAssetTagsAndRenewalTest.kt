package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.FleetAlertNotifier
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.FleetAlertEvaluator
import com.nodeseek.app.domain.fleet.FleetAlertTrackerState
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.fleet.commitCustomTagInput
import com.nodeseek.app.ui.fleet.toggleAssetTag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FleetAssetTagsAndRenewalTest {

    private lateinit var tempDir: File
    private lateinit var cipher: SoftwareAesGcmTokenCipher

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("fleet_asset_tags_renewal_test").toFile()
        cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
    }

    @After
    fun tearDown() {
        tempDir.walkBottomUp().forEach { file ->
            file.setReadable(true)
            file.setWritable(true)
        }
        tempDir.deleteRecursively()
    }

    // ── Multi-Select Tags & Legacy Migration ──────────────────────────────────

    @Test
    fun `legacy single-string routeTag migrates cleanly into multi-select tags without splitting spaces`() {
        assertEquals(listOf("CN2 GIA"), VpsAssetRecord.parseLegacyTags("CN2 GIA"))
        assertEquals(listOf("阿里云 BGP"), VpsAssetRecord.parseLegacyTags("阿里云 BGP"))
        assertEquals(
            listOf("CN2 GIA", "9929", "落地"),
            VpsAssetRecord.parseLegacyTags("CN2 GIA / 9929, 落地 · cn2 gia"),
        )
        assertEquals(emptyList<String>(), VpsAssetRecord.parseLegacyTags("   "))

        val legacyRecord = sampleAsset(routeTag = "CN2 GIA / 落地", tags = emptyList())
        assertEquals(listOf("CN2 GIA", "落地"), legacyRecord.resolvedTags)
        assertEquals("CN2 GIA · 落地", legacyRecord.resolvedRouteTagSummary)
    }

    @Test
    fun `custom tag input and chip toggle handle Enter creation, preset canonicalization, dedupe, and removal`() {
        var tags = emptyList<String>()
        tags = toggleAssetTag(tags, "CN2 GIA")
        tags = toggleAssetTag(tags, "落地")
        assertEquals(listOf("CN2 GIA", "落地"), tags)

        // Pressing Enter with custom tag containing slash preserves it intact
        tags = commitCustomTagInput(tags, "IPv4/IPv6")
        assertEquals(listOf("CN2 GIA", "落地", "IPv4/IPv6"), tags)

        // Pressing Enter with case-variant of an already-selected tag does not duplicate
        tags = commitCustomTagInput(tags, "cn2 gia")
        assertEquals(listOf("CN2 GIA", "落地", "IPv4/IPv6"), tags)

        // Pressing Enter with lowercase preset canonicalizes to preset casing
        tags = commitCustomTagInput(tags, "cmin2")
        assertEquals(listOf("CN2 GIA", "落地", "IPv4/IPv6", "CMIN2"), tags)

        // Tapping selected chip / close button removes it cleanly
        tags = toggleAssetTag(tags, "落地")
        assertEquals(listOf("CN2 GIA", "IPv4/IPv6", "CMIN2"), tags)
    }

    @Test
    fun `legacy fleet_store json migrates routeTag to tags list and new multi-tag payloads round-trip losslessly`() =
        runTest {
            val storeFile = File(tempDir, "legacy_tags_fleet_store.json")
            storeFile.writeText(
                """
                {
                  "version": 1,
                  "panels": [],
                  "snapshots": [],
                  "assets": [
                    {
                      "id": "legacy-single",
                      "nickname": "瓦工旧账本",
                      "providerName": "BandwagonHost",
                      "specsSummary": "2C / 2G",
                      "region": "香港",
                      "routeTag": "CN2 GIA",
                      "renewalAmount": "89.90",
                      "currency": "USD",
                      "cyclePreset": "ANNUAL",
                      "expiryDateIso": "2027-05-01"
                    },
                    {
                      "id": "legacy-multi-sep",
                      "nickname": "双线旧账本",
                      "providerName": "DMIT",
                      "specsSummary": "1C / 1G",
                      "region": "日本",
                      "routeTag": "CN2 GIA / 9929",
                      "renewalAmount": "49.90",
                      "currency": "USD",
                      "cyclePreset": "ANNUAL",
                      "expiryDateIso": "2027-06-01"
                    }
                  ],
                  "monitorSettings": {"backgroundMonitorEnabled": true, "pollIntervalMinutes": 15},
                  "alertTracker": {},
                  "lastSyncEpochMillis": 0
                }
                """.trimIndent(),
                Charsets.UTF_8,
            )

            val repo = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = cipher)
            repo.loadPersistedState()

            val byId = repo.assetsFlow.value.associateBy { it.id }
            assertEquals(listOf("CN2 GIA"), byId.getValue("legacy-single").tags)
            assertEquals("CN2 GIA", byId.getValue("legacy-single").routeTag)
            assertFalse(byId.getValue("legacy-single").autoRenew)

            assertEquals(listOf("CN2 GIA", "9929"), byId.getValue("legacy-multi-sep").tags)
            assertEquals("CN2 GIA · 9929", byId.getValue("legacy-multi-sep").routeTag)

            // Save a new multi-tag record including a tag with slash and autoRenew = true
            repo.upsertAsset(
                sampleAsset(
                    id = "modern-1",
                    routeTag = "CN2 GIA · IPv4/IPv6 · 主力建站",
                    tags = listOf("CN2 GIA", "IPv4/IPv6", "主力建站"),
                    autoRenew = true,
                ),
            )

            val reopened = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = cipher)
            reopened.loadPersistedState()
            val reloadedModern = reopened.assetsFlow.value.first { it.id == "modern-1" }
            assertEquals(listOf("CN2 GIA", "IPv4/IPv6", "主力建站"), reloadedModern.tags)
            assertTrue(reloadedModern.autoRenew)

            // Legacy caller updating the asset with matching routeTag & default empty tags preserves tags losslessly
            reopened.upsertAsset(
                reloadedModern.copy(
                    specsSummary = "4C / 4G",
                    tags = emptyList(),
                ),
            )
            val preserved = reopened.assetsFlow.value.first { it.id == "modern-1" }
            assertEquals(listOf("CN2 GIA", "IPv4/IPv6", "主力建站"), preserved.tags)

            // Explicitly clearing all tags persists empty list and empty routeTag
            reopened.upsertAsset(preserved.copy(tags = emptyList(), routeTag = ""))
            val cleared = reopened.assetsFlow.value.first { it.id == "modern-1" }
            assertEquals(emptyList<String>(), cleared.tags)
            assertEquals("", cleared.routeTag)
        }

    // ── Billing Cycle Advancement & Month-End Clamping ────────────────────────

    @Test
    fun `computeNextRenewalDate advances all billing cycles accurately when due soon or due today`() {
        val today = LocalDate.of(2026, 11, 19)

        assertEquals(
            LocalDate.of(2026, 12, 24) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 11, 24),
                cyclePreset = BillingCyclePreset.MONTHLY,
                today = today,
            ),
        )
        assertEquals(
            LocalDate.of(2027, 2, 19) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 11, 19),
                cyclePreset = BillingCyclePreset.QUARTERLY,
                today = today,
            ),
        )
        assertEquals(
            LocalDate.of(2027, 5, 20) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 11, 20),
                cyclePreset = BillingCyclePreset.SEMI_ANNUAL,
                today = today,
            ),
        )
        assertEquals(
            LocalDate.of(2027, 11, 20) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 11, 20),
                cyclePreset = BillingCyclePreset.ANNUAL,
                today = today,
            ),
        )
        assertEquals(
            LocalDate.of(2027, 11, 20) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 11, 20),
                cyclePreset = BillingCyclePreset.CALENDAR_NATURAL,
                today = today,
            ),
        )
        assertEquals(
            LocalDate.of(2028, 11, 20) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 11, 20),
                cyclePreset = BillingCyclePreset.BIENNIAL,
                today = today,
            ),
        )
        assertEquals(
            LocalDate.of(2029, 11, 20) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 11, 20),
                cyclePreset = BillingCyclePreset.TRIENNIAL,
                today = today,
            ),
        )
        assertEquals(
            LocalDate.of(2027, 1, 3) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 11, 19),
                cyclePreset = BillingCyclePreset.CUSTOM,
                customCycleDays = 45,
                today = today,
            ),
        )
    }

    @Test
    fun `computeNextRenewalDate handles month-end clamping and leap years without cumulative day drift`() {
        // Jan 31 + 1 month in non-leap year -> Feb 28
        assertEquals(
            LocalDate.of(2026, 2, 28) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 1, 31),
                cyclePreset = BillingCyclePreset.MONTHLY,
                today = LocalDate.of(2026, 1, 31),
            ),
        )

        // Jan 31 + 1 month in leap year -> Feb 29
        assertEquals(
            LocalDate.of(2024, 2, 29) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2024, 1, 31),
                cyclePreset = BillingCyclePreset.MONTHLY,
                today = LocalDate.of(2024, 1, 31),
            ),
        )

        // Jan 31 monthly overdue to Feb 28 (the clamped date itself) -> rolls 2 cycles to Mar 31 (restores day 31!)
        assertEquals(
            LocalDate.of(2026, 3, 31) to 2,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 1, 31),
                cyclePreset = BillingCyclePreset.MONTHLY,
                today = LocalDate.of(2026, 2, 28),
            ),
        )

        // Jan 31 monthly long overdue to Apr 5 -> rolls 3 cycles to Apr 30 (NOT Apr 28!)
        assertEquals(
            LocalDate.of(2026, 4, 30) to 3,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 1, 31),
                cyclePreset = BillingCyclePreset.MONTHLY,
                today = LocalDate.of(2026, 4, 5),
            ),
        )

        // Nov 30 quarterly overdue to Mar 1 -> rolls 2 cycles to May 30 (NOT May 28!)
        assertEquals(
            LocalDate.of(2026, 5, 30) to 2,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2025, 11, 30),
                cyclePreset = BillingCyclePreset.QUARTERLY,
                today = LocalDate.of(2026, 3, 1),
            ),
        )

        // Aug 31 semi-annual -> Feb 28
        assertEquals(
            LocalDate.of(2026, 2, 28) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2025, 8, 31),
                cyclePreset = BillingCyclePreset.SEMI_ANNUAL,
                today = LocalDate.of(2025, 8, 31),
            ),
        )

        // Leap day annual: 2024-02-29 -> 2025-02-28 (1 cycle), and long overdue to 2027-03-01 -> 2028-02-29 (4 cycles!)
        assertEquals(
            LocalDate.of(2025, 2, 28) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2024, 2, 29),
                cyclePreset = BillingCyclePreset.ANNUAL,
                today = LocalDate.of(2024, 2, 29),
            ),
        )
        assertEquals(
            LocalDate.of(2028, 2, 29) to 4,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2024, 2, 29),
                cyclePreset = BillingCyclePreset.ANNUAL,
                today = LocalDate.of(2027, 3, 1),
            ),
        )
    }

    @Test
    fun `computeNextRenewalDate rolls forward multiple cycles when long overdue and falls back cleanly on null expiry`() {
        // Monthly 6+ months overdue: 2026-05-10 -> today 2026-11-19 -> 7 cycles -> 2026-12-10
        assertEquals(
            LocalDate.of(2026, 12, 10) to 7,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 5, 10),
                cyclePreset = BillingCyclePreset.MONTHLY,
                today = LocalDate.of(2026, 11, 19),
            ),
        )

        // Annual 3+ years overdue: 2023-06-15 -> today 2026-11-19 -> 4 cycles -> 2027-06-15
        assertEquals(
            LocalDate.of(2027, 6, 15) to 4,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2023, 6, 15),
                cyclePreset = BillingCyclePreset.ANNUAL,
                today = LocalDate.of(2026, 11, 19),
            ),
        )

        // Custom 10-day cycle 16 days overdue -> 2 cycles (+20 days)
        assertEquals(
            LocalDate.of(2026, 12, 9) to 2,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = LocalDate.of(2026, 11, 19),
                cyclePreset = BillingCyclePreset.CUSTOM,
                customCycleDays = 10,
                today = LocalDate.of(2026, 12, 5),
            ),
        )

        // Null / malformed expiry falls back to today + 1 cycle
        assertEquals(
            LocalDate.of(2027, 11, 19) to 1,
            FleetPortfolioCalculator.computeNextRenewalDate(
                currentExpiryDate = null,
                cyclePreset = BillingCyclePreset.ANNUAL,
                today = LocalDate.of(2026, 11, 19),
            ),
        )
    }

    // ── Repository markAssetRenewed & Auto-Roll ───────────────────────────────

    @Test
    fun `markAssetRenewed persists advanced expiry, clears stale renewal dedup keys, and handles missing or read-only store`() =
        runTest {
            val storeFile = File(tempDir, "renew_repo_store.json")
            val repo = FleetRepositoryImpl(
                storeFile = storeFile,
                tokenCipher = cipher,
                nowMillisProvider = { 100_000L },
            )
            repo.upsertAsset(
                sampleAsset(
                    id = "a-monthly",
                    cyclePreset = BillingCyclePreset.MONTHLY,
                    expiryDateIso = "2026-01-31",
                    remindDaysBeforeExpiry = 7,
                ),
            )

            // Trigger overdue reminder on 2026-04-05 -> records overdue dedup key
            val evalBefore = repo.evaluateAlerts(today = LocalDate.of(2026, 4, 5), nowEpochMillis = 100_000L)
            assertEquals(1, evalBefore.renewalReminders.size)
            assertTrue(evalBefore.renewalReminders.single().daysRemaining < 0L)

            // User confirms renewal on 2026-04-05 -> rolls 3 cycles from Jan 31 to Apr 30
            val result = repo.markAssetRenewed(
                assetId = "a-monthly",
                today = LocalDate.of(2026, 4, 5),
            )
            assertNotNull(result)
            result!!
            assertEquals("2026-01-31", result.previousExpiryDateIso)
            assertEquals("2026-04-30", result.newExpiryDateIso)
            assertEquals(3, result.cyclesAdvanced)
            assertFalse(result.isAutoRolled)
            assertEquals("2026-04-30", repo.assetsFlow.value.single().expiryDateIso)

            // Unknown assetId returns null cleanly
            assertNull(repo.markAssetRenewed("missing-id", today = LocalDate.of(2026, 4, 5)))

            // Reopen from disk confirms persistence
            val reopened = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = cipher)
            reopened.loadPersistedState()
            assertEquals("2026-04-30", reopened.assetsFlow.value.single().expiryDateIso)

            // Next cycle reminder re-arms cleanly as Apr 30 approaches
            val evalNextCycle = reopened.evaluateAlerts(today = LocalDate.of(2026, 4, 25), nowEpochMillis = 200_000L)
            assertEquals(1, evalNextCycle.renewalReminders.size)
            assertEquals(5L, evalNextCycle.renewalReminders.single().daysRemaining)

            // Read-only failure throws IOException and does not mutate in-memory state
            assertTrue(storeFile.setWritable(false))
            try {
                reopened.markAssetRenewed("a-monthly", today = LocalDate.of(2026, 4, 25))
                fail("Expected IOException on read-only store")
            } catch (_: IOException) {
                // expected
            }
            assertEquals("2026-04-30", reopened.assetsFlow.value.single().expiryDateIso)
        }

    @Test
    fun `autoRollExpiredAutoRenewAssets and evaluateAlerts roll only expired autoRenew assets and avoid overdue spam`() =
        runTest {
            val storeFile = File(tempDir, "autoroll_store.json")
            val repo = FleetRepositoryImpl(
                storeFile = storeFile,
                tokenCipher = cipher,
                nowMillisProvider = { 500_000L },
            )
            repo.upsertAsset(
                sampleAsset(
                    id = "auto-expired",
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-11-18",
                    autoRenew = true,
                    remindDaysBeforeExpiry = 7,
                ),
            )
            repo.upsertAsset(
                sampleAsset(
                    id = "auto-today",
                    cyclePreset = BillingCyclePreset.MONTHLY,
                    expiryDateIso = "2026-11-19",
                    autoRenew = true,
                    remindDaysBeforeExpiry = 7,
                ),
            )
            repo.upsertAsset(
                sampleAsset(
                    id = "manual-expired",
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-11-18",
                    autoRenew = false,
                    remindDaysBeforeExpiry = 7,
                ),
            )

            // Evaluating on 2026-11-19:
            // - auto-expired (2026-11-18 < 2026-11-19) rolls to 2027-11-18 (no overdue notification)
            // - auto-today (2026-11-19 == today) stays at 2026-11-19 and emits upcoming due-today reminder
            // - manual-expired stays at 2026-11-18 and emits one-shot overdue reminder
            val report = repo.evaluateAlerts(today = LocalDate.of(2026, 11, 19), nowEpochMillis = 500_000L)
            assertEquals(1, report.autoRolledRenewals.size)
            assertEquals("auto-expired", report.autoRolledRenewals.single().updatedAsset.id)
            assertEquals("2027-11-18", report.autoRolledRenewals.single().newExpiryDateIso)
            assertTrue(report.autoRolledRenewals.single().isAutoRolled)

            val remindedIds = report.renewalReminders.map { it.asset.id }
            assertEquals(listOf("manual-expired", "auto-today"), remindedIds)

            val assetsById = repo.assetsFlow.value.associateBy { it.id }
            assertEquals("2027-11-18", assetsById.getValue("auto-expired").expiryDateIso)
            assertEquals("2026-11-19", assetsById.getValue("auto-today").expiryDateIso)
            assertEquals("2026-11-18", assetsById.getValue("manual-expired").expiryDateIso)

            // Calling autoRollExpiredAutoRenewAssets on the same day is a no-op
            assertTrue(repo.autoRollExpiredAutoRenewAssets(today = LocalDate.of(2026, 11, 19)).isEmpty())

            // Next day (2026-11-20), auto-today is now past expiry and auto-rolls to 2026-12-19
            val rolledDay2 = repo.autoRollExpiredAutoRenewAssets(today = LocalDate.of(2026, 11, 20))
            assertEquals(1, rolledDay2.size)
            assertEquals("auto-today", rolledDay2.single().updatedAsset.id)
            assertEquals("2026-12-19", rolledDay2.single().newExpiryDateIso)
        }

    // ── Evaluator Overdue Deduplication, Summary State, and Notification Copy ─

    @Test
    fun `FleetAlertEvaluator fires one upcoming reminder and one overdue transition reminder per cycle`() {
        val asset = sampleAsset(
            id = "a-remind",
            expiryDateIso = "2026-11-24",
            remindDaysBeforeExpiry = 7,
            autoRenew = false,
        )

        val passUpcoming = FleetAlertEvaluator.evaluate(
            previousTracker = FleetAlertTrackerState(),
            panels = emptyList(),
            snapshots = emptyList(),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 11, 20),
            nowEpochMillis = 10_000L,
        )
        assertEquals(1, passUpcoming.report.renewalReminders.size)
        assertEquals(4L, passUpcoming.report.renewalReminders.single().daysRemaining)

        // Still before expiry -> deduplicated
        val passStillUpcoming = FleetAlertEvaluator.evaluate(
            previousTracker = passUpcoming.updatedTracker,
            panels = emptyList(),
            snapshots = emptyList(),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 11, 24),
            nowEpochMillis = 20_000L,
        )
        assertTrue(passStillUpcoming.report.renewalReminders.isEmpty())

        // Crosses into overdue -> fires one overdue reminder
        val passOverdue = FleetAlertEvaluator.evaluate(
            previousTracker = passStillUpcoming.updatedTracker,
            panels = emptyList(),
            snapshots = emptyList(),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 11, 25),
            nowEpochMillis = 30_000L,
        )
        assertEquals(1, passOverdue.report.renewalReminders.size)
        assertEquals(-1L, passOverdue.report.renewalReminders.single().daysRemaining)

        // Remains overdue -> deduplicated (no spam)
        val passStillOverdue = FleetAlertEvaluator.evaluate(
            previousTracker = passOverdue.updatedTracker,
            panels = emptyList(),
            snapshots = emptyList(),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 11, 28),
            nowEpochMillis = 40_000L,
        )
        assertTrue(passStillOverdue.report.renewalReminders.isEmpty())
    }

    @Test
    fun `portfolio summary exposes dueRenewals, expiredRenewalCount, and renewal preview dates`() {
        val today = LocalDate.of(2026, 11, 19)
        val assets = listOf(
            sampleAsset(
                id = "due-soon",
                expiryDateIso = "2026-11-24",
                cyclePreset = BillingCyclePreset.ANNUAL,
                remindDaysBeforeExpiry = 7,
            ),
            sampleAsset(
                id = "expired-monthly",
                expiryDateIso = "2026-09-15",
                cyclePreset = BillingCyclePreset.MONTHLY,
                remindDaysBeforeExpiry = 7,
            ),
            sampleAsset(
                id = "muted-upcoming",
                expiryDateIso = "2026-11-23",
                cyclePreset = BillingCyclePreset.ANNUAL,
                remindDaysBeforeExpiry = 0,
            ),
            sampleAsset(
                id = "far-normal",
                expiryDateIso = "2027-06-01",
                cyclePreset = BillingCyclePreset.ANNUAL,
                remindDaysBeforeExpiry = 7,
            ),
        )

        val summary = FleetPortfolioCalculator.summarize(
            assets = assets,
            snapshots = emptyList(),
            today = today,
        )

        assertEquals(2, summary.dueSoonRenewalCount)
        assertEquals(1, summary.expiredRenewalCount)
        assertEquals(listOf("expired-monthly", "due-soon"), summary.dueRenewals.map { it.asset.id })

        val expiredItem = summary.dueRenewals[0]
        assertEquals(AssetExpiryUrgency.EXPIRED, expiredItem.urgency)
        assertTrue(expiredItem.isRenewalDue)
        assertEquals("2026-12-15", expiredItem.nextExpiryAfterRenewalIso)
        assertEquals(3, expiredItem.cyclesToAdvanceOnRenewal)

        val dueSoonItem = summary.dueRenewals[1]
        assertEquals("2027-11-24", dueSoonItem.nextExpiryAfterRenewalIso)
        assertEquals(1, dueSoonItem.cyclesToAdvanceOnRenewal)

        val mutedItem = summary.upcomingRenewals.first { it.asset.id == "muted-upcoming" }
        assertFalse(mutedItem.isRenewalDue)
    }

    @Test
    fun `FleetAlertNotifier formats distinct copy for overdue, today, tomorrow, upcoming, and autoRenew`() {
        val manualAsset = sampleAsset(nickname = "DMIT HK", expiryDateIso = "2026-11-19", autoRenew = false)
        val (overdueTitle, _, overdueBody) = FleetAlertNotifier.formatRenewalReminderCopy(
            asset = manualAsset,
            daysRemaining = -3L,
            renewalCny = BigDecimal("360.00"),
        )
        assertTrue(overdueTitle.contains("续费逾期"))
        assertTrue(overdueTitle.contains("已过期 3 天"))
        assertTrue(overdueBody.contains("已续费"))

        val (todayTitle, _, _) = FleetAlertNotifier.formatRenewalReminderCopy(
            asset = manualAsset,
            daysRemaining = 0L,
            renewalCny = BigDecimal("360.00"),
        )
        assertTrue(todayTitle.contains("今日到期"))

        val autoAsset = manualAsset.copy(autoRenew = true)
        val (autoTitle, autoSummary, autoBody) = FleetAlertNotifier.formatRenewalReminderCopy(
            asset = autoAsset,
            daysRemaining = 5L,
            renewalCny = BigDecimal("360.00"),
        )
        assertTrue(autoTitle.contains("剩余 5 天到期"))
        assertTrue(autoSummary.contains("自动续费"))
        assertTrue(autoBody.contains("自动续费"))
    }

    private fun sampleAsset(
        id: String = "asset-1",
        nickname: String = "DMIT LAX Pro",
        routeTag: String = "CN2 GIA",
        tags: List<String> = emptyList(),
        cyclePreset: BillingCyclePreset = BillingCyclePreset.ANNUAL,
        customCycleDays: Int? = null,
        expiryDateIso: String = "2026-11-25",
        remindDaysBeforeExpiry: Int = 7,
        autoRenew: Boolean = false,
    ) = VpsAssetRecord(
        id = id,
        nickname = nickname,
        providerName = "DMIT",
        specsSummary = "2C / 2G / 40G NVMe",
        region = "洛杉矶",
        routeTag = routeTag,
        renewalAmount = "49.90",
        currency = ValuationCurrency.USD,
        cyclePreset = cyclePreset,
        customCycleDays = customCycleDays,
        expiryDateIso = expiryDateIso,
        remindDaysBeforeExpiry = remindDaysBeforeExpiry,
        notes = "测试资产",
        tags = tags,
        autoRenew = autoRenew,
    )
}
