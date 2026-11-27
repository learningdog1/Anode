package com.nodeseek.app.widget

import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.LatestSeekMailOtp
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.widget.WidgetSnapshotAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetSnapshotAggregatorTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 26)
    private val gib: Long = 1024L * 1024L * 1024L

    private fun probe(
        id: String,
        name: String = "node-$id",
        isOnline: Boolean = true,
        usedGib: Long = 100L,
        limitGib: Long? = 1000L,
        countryCode: String = "JP",
    ): ProbeServerSnapshot = ProbeServerSnapshot(
        panelId = "panel1",
        remoteServerId = id,
        name = name,
        countryCode = countryCode,
        monthlyTransferInBytes = usedGib * gib / 2L,
        monthlyTransferOutBytes = usedGib * gib / 2L,
        trafficLimitBytes = limitGib?.let { it * gib },
        isOnline = isOnline,
    )

    private fun vpsAsset(
        id: String,
        nickname: String,
        expiryIso: String,
        linkedProbeKey: String? = null,
        tags: List<String> = emptyList(),
        quotaGb: Double? = null,
        remindDays: Int = 7,
    ): VpsAssetRecord = VpsAssetRecord(
        id = id,
        nickname = nickname,
        providerName = "Provider-$id",
        specsSummary = "2C2G",
        region = "JP",
        renewalAmount = "10",
        expiryDateIso = expiryIso,
        linkedProbeKey = linkedProbeKey,
        monthlyTrafficQuotaGb = quotaGb,
        remindDaysBeforeExpiry = remindDays,
        tags = tags,
        assetType = FleetAssetType.VPS,
    )

    private fun domainAsset(
        id: String,
        domain: String,
        expiryIso: String,
    ): VpsAssetRecord = VpsAssetRecord(
        id = id,
        nickname = domain,
        providerName = "Cloudflare",
        specsSummary = "Domain",
        region = "",
        renewalAmount = "10",
        expiryDateIso = expiryIso,
        assetType = FleetAssetType.DOMAIN_CERT,
        domainName = domain,
    )

    @Test
    fun `primary machine prefers online主力 machine with limit over higher usage online node`() {
        val heavyProbe = probe(id = "1", name = "US-Backup", isOnline = true, usedGib = 900L, limitGib = 1000L)
        val primaryUnmetered = probe(id = "2", name = "主力-Unmetered", isOnline = true, usedGib = 10L, limitGib = null)
        val primaryMetered = probe(id = "3", name = "Tokyo-Box", isOnline = true, usedGib = 250L, limitGib = 1000L)
        val offlinePrimary = probe(id = "4", name = "主力-Offline", isOnline = false, usedGib = 800L, limitGib = 1000L)

        val assets = listOf(
            vpsAsset(
                id = "a3",
                nickname = "东京主力机",
                expiryIso = "2026-12-20",
                linkedProbeKey = primaryMetered.probeKey,
                tags = listOf("主力", "CN2 GIA"),
            ),
        )

        val snapshot = WidgetSnapshotAggregator.buildServerStatus(
            assets = assets,
            snapshots = listOf(heavyProbe, primaryUnmetered, primaryMetered, offlinePrimary),
            lastSyncEpochMillis = 12345L,
            today = today,
        )

        assertEquals(4, snapshot.totalProbeCount)
        assertEquals(3, snapshot.onlineProbeCount)
        assertEquals(1, snapshot.offlineProbeCount)
        assertEquals("东京主力机", snapshot.primaryServerName)
        assertEquals(250L * gib, snapshot.primaryTrafficUsedBytes)
        assertEquals(1000L * gib, snapshot.primaryTrafficLimitBytes)
        assertEquals(0.25f, snapshot.primaryTrafficRatio!!, 0.001f)
        assertEquals(12345L, snapshot.lastSyncEpochMillis)
    }

    @Test
    fun `primary machine falls back cleanly across metered online, metered offline, unmetered online, and first offline`() {
        // Tier 2: online metered with highest ratio wins, using linked asset quota fallback
        val lowRatio = probe(id = "1", name = "LowRatio", isOnline = true, usedGib = 200L, limitGib = 1000L)
        val highRatioLinked = probe(id = "2", name = "LinkedQuota", isOnline = true, usedGib = 400L, limitGib = null)
        val quotaAsset = vpsAsset(
            id = "q2",
            nickname = "SG-High",
            expiryIso = "2026-12-20",
            linkedProbeKey = highRatioLinked.probeKey,
            quotaGb = 500.0,
        )
        val tier2 = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(quotaAsset),
            snapshots = listOf(lowRatio, highRatioLinked),
            today = today,
        )
        assertEquals("SG-High", tier2.primaryServerName)
        assertEquals(0.80f, tier2.primaryTrafficRatio!!, 0.001f)

        // Tier 3: offline metered beats online unmetered
        val onlineUnmetered = probe(id = "3", name = "OnlineUnmetered", isOnline = true, usedGib = 50L, limitGib = null)
        val offlineMetered = probe(id = "4", name = "OfflineMetered", isOnline = false, usedGib = 100L, limitGib = 500L)
        val tier3 = WidgetSnapshotAggregator.buildServerStatus(
            assets = emptyList(),
            snapshots = listOf(onlineUnmetered, offlineMetered),
            today = today,
        )
        assertEquals("OfflineMetered", tier3.primaryServerName)

        // Tier 4: first online unmetered beats offline unmetered
        val offlineUnmetered = probe(id = "5", name = "OfflineUnmetered", isOnline = false, usedGib = 10L, limitGib = null)
        val tier4 = WidgetSnapshotAggregator.buildServerStatus(
            assets = emptyList(),
            snapshots = listOf(offlineUnmetered, onlineUnmetered),
            today = today,
        )
        assertEquals("OnlineUnmetered", tier4.primaryServerName)

        // Tier 5: first offline unmetered when all are offline & unmetered
        val offline2 = probe(id = "6", name = "OfflineTwo", isOnline = false, usedGib = 20L, limitGib = null)
        val tier5 = WidgetSnapshotAggregator.buildServerStatus(
            assets = emptyList(),
            snapshots = listOf(offlineUnmetered, offline2),
            today = today,
        )
        assertEquals("OfflineUnmetered", tier5.primaryServerName)
        assertNull(tier5.primaryTrafficRatio)

        // Empty snapshots
        val empty = WidgetSnapshotAggregator.buildServerStatus(
            assets = emptyList(),
            snapshots = emptyList(),
            today = today,
        )
        assertFalse(empty.hasProbes)
        assertNull(empty.primaryServerName)
        assertEquals(0, empty.totalProbeCount)
    }

    @Test
    fun `nearest expiring VPS strictly excludes DOMAIN_CERT assets even when domain expires earlier`() {
        val soonerDomain = domainAsset(id = "d1", domain = "urgent.example.com", expiryIso = "2026-11-27")
        val expiredDomain = domainAsset(id = "d2", domain = "expired.example.com", expiryIso = "2026-11-20")
        val vpsLater = vpsAsset(id = "v1", nickname = "HK-Edge", expiryIso = "2026-12-10")
        val vpsSoonest = vpsAsset(id = "v2", nickname = "DMIT-Pro", expiryIso = "2026-11-29")

        val snapshot = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(expiredDomain, soonerDomain, vpsLater, vpsSoonest),
            snapshots = emptyList(),
            today = today,
        )

        assertTrue(snapshot.hasNearestVps)
        assertEquals("DMIT-Pro", snapshot.nearestVpsName)
        assertEquals(3L, snapshot.nearestVpsDaysRemaining)
        assertEquals(AssetExpiryUrgency.CRITICAL, snapshot.nearestVpsUrgency)
        assertEquals("2026-11-29", snapshot.nearestVpsExpiryIso)
    }

    @Test
    fun `nearest expiring VPS handles domain-only portfolio, expired VPS, and due-today VPS`() {
        // Domain-only portfolio -> null nearest VPS
        val domainOnly = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(domainAsset("d1", "only.com", "2026-11-27")),
            snapshots = emptyList(),
            today = today,
        )
        assertFalse(domainOnly.hasNearestVps)
        assertNull(domainOnly.nearestVpsName)
        assertNull(domainOnly.nearestVpsDaysRemaining)

        // Due today -> 0 days, CRITICAL
        val dueToday = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(vpsAsset("v1", "TodayBox", "2026-11-26")),
            snapshots = emptyList(),
            today = today,
        )
        assertEquals(0L, dueToday.nearestVpsDaysRemaining)
        assertEquals(AssetExpiryUrgency.CRITICAL, dueToday.nearestVpsUrgency)

        // Expired -> negative days, EXPIRED
        val expired = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(vpsAsset("v2", "OldBox", "2026-11-24")),
            snapshots = emptyList(),
            today = today,
        )
        assertEquals(-2L, expired.nearestVpsDaysRemaining)
        assertEquals(AssetExpiryUrgency.EXPIRED, expired.nearestVpsUrgency)

        // Blank nickname and blank providerName -> falls back to "VPS #<id>" so hasNearestVps stays true
        val blankIdentityVps = VpsAssetRecord(
            id = "v-blank",
            nickname = "   ",
            providerName = " ",
            specsSummary = "1C1G",
            region = "US",
            renewalAmount = "5",
            expiryDateIso = "2026-12-01",
            assetType = FleetAssetType.VPS,
        )
        val blankFallback = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(blankIdentityVps),
            snapshots = emptyList(),
            today = today,
        )
        assertTrue(blankFallback.hasNearestVps)
        assertEquals("VPS #v-blank", blankFallback.nearestVpsName)
        assertEquals(5L, blankFallback.nearestVpsDaysRemaining)
    }

    @Test
    fun `buildDailyPulse maps signed-out and signed-in states accurately`() {
        val signedOut = WidgetSnapshotAggregator.buildDailyPulse(
            session = null,
            settings = AppSettings(preferRandomCheckIn = true, seekMailApiKey = ""),
            checkInStatus = CheckInStatus(hasCheckedInToday = true, todayGain = 5),
            radarPollingSettings = RadarPollingSettings(isEnabled = false),
            unreadAlertCount = 2,
            latestAlert = null,
            latestOtp = null,
        )
        assertFalse(signedOut.isSignedIn)
        assertFalse(signedOut.hasCheckedInToday)
        assertNull(signedOut.todayGain)
        assertEquals(0, signedOut.drumsticks)
        assertFalse(signedOut.radarEnabled)
        assertFalse(signedOut.hasSeekMailKey)

        val alert = LocalAlertRecord(
            id = 10L,
            dedupKey = "kw:99",
            kind = LocalAlertKind.KEYWORD_HIT,
            topicId = 99L,
            topicTitle = "DMIT LAX Pro 补货",
            matchedKeyword = "DMIT",
        )
        val otp = LatestSeekMailOtp(
            mailId = 88L,
            code = "482910",
            sender = "Cloudflare",
            subject = "Your verification code",
            receivedAt = "2026-11-26T08:00:00Z",
        )
        val signedIn = WidgetSnapshotAggregator.buildDailyPulse(
            session = AccountSession(uid = 1L, username = "alice", drumsticks = 420),
            settings = AppSettings(preferRandomCheckIn = true, seekMailApiKey = "sk_test_123"),
            checkInStatus = CheckInStatus(hasCheckedInToday = true, todayGain = 12, todayOrder = 8),
            radarPollingSettings = RadarPollingSettings(isEnabled = true),
            unreadAlertCount = 3,
            latestAlert = alert,
            latestOtp = otp,
        )
        assertTrue(signedIn.isSignedIn)
        assertEquals("alice", signedIn.username)
        assertEquals(420, signedIn.drumsticks)
        assertTrue(signedIn.hasCheckedInToday)
        assertEquals(12, signedIn.todayGain)
        assertEquals(8, signedIn.todayOrder)
        assertTrue(signedIn.preferRandomCheckIn)
        assertTrue(signedIn.radarEnabled)
        assertEquals(3, signedIn.unreadAlertCount)
        assertEquals("DMIT LAX Pro 补货", signedIn.latestAlertTitle)
        assertEquals("DMIT", signedIn.latestAlertKeyword)
        assertEquals("482910", signedIn.latestOtpCode)
        assertEquals("Cloudflare", signedIn.latestOtpSender)
        assertTrue(signedIn.hasSeekMailKey)
        assertNotNull(signedIn.latestOtpCode)
    }
}
