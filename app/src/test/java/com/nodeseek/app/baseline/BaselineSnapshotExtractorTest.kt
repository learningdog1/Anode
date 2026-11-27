package com.nodeseek.app.baseline

import com.nodeseek.app.domain.baseline.BaselineSnapshotExtractor
import com.nodeseek.app.domain.baseline.BaselineSourceType
import com.nodeseek.app.domain.baseline.StreamingUnlockStatus
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IcmpPingSummary
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3IntervalSample
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingSummary
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.netbench.TracerouteHop
import com.nodeseek.app.domain.netbench.TracerouteSummary
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineSnapshotExtractorTest {

    @Test
    fun `fromNetBenchReport extracts receiver-accurate iperf3, ping, ip intel, R5 traceroute, and US detour`() {
        val target = ResolvedTarget(
            displayHost = "hk.example.com",
            resolvedIp = "203.0.113.42",
            ipFamily = IpFamily.IPV4,
        )
        val forwardTrace = TracerouteSummary(
            direction = TracerouteDirection.FORWARD,
            targetInput = "hk.example.com",
            targetIp = "203.0.113.42",
            ipFamily = IpFamily.IPV4,
            hops = listOf(
                TracerouteHop(hopNumber = 1, ip = "59.43.130.1", countryCode = "CN", backboneTags = listOf(BackboneRouteTag.CN2_GIA), avgMs = 18.0),
                TracerouteHop(hopNumber = 2, ip = "203.0.113.42", countryCode = "HK", avgMs = 34.5, isTargetReached = true),
            ),
            reachedTarget = true,
            totalHops = 2,
            terminalAvgMs = 34.5,
            peakHopLossPercent = 0.0,
            hasRealPacketLoss = false,
            dominantBackboneTags = listOf(BackboneRouteTag.CN2_GIA),
            routeSignature = "CN2 GIA",
            isPeakHourWindow = true,
        )
        val reverseTrace = TracerouteSummary(
            direction = TracerouteDirection.REVERSE,
            targetInput = "114.240.88.10",
            targetIp = "114.240.88.10",
            ipFamily = IpFamily.IPV4,
            hops = listOf(
                TracerouteHop(hopNumber = 1, ip = "154.54.1.1", countryCode = "US", backboneTags = listOf(BackboneRouteTag.COGENT), avgMs = 155.0),
                TracerouteHop(hopNumber = 2, ip = "114.240.88.10", countryCode = "CN", avgMs = 188.0, isTargetReached = true),
            ),
            reachedTarget = true,
            totalHops = 2,
            terminalAvgMs = 188.0,
            peakHopLossPercent = 5.0,
            hasRealPacketLoss = true,
            dominantBackboneTags = listOf(BackboneRouteTag.COGENT),
            routeSignature = "Cogent",
            isPeakHourWindow = true,
        )
        val report = NetBenchReport(
            targetInput = "hk.example.com",
            resolvedTarget = target,
            icmpPingSummary = IcmpPingSummary(
                resolvedTarget = target,
                sent = 10,
                received = 10,
                lossPercent = 0.0,
                minMs = 31.0,
                avgMs = 35.2,
                maxMs = 40.0,
                jitterMdevMs = 2.1,
            ),
            tcpPingSummary = TcpPingSummary(
                resolvedTarget = target,
                port = 443,
                sent = 10,
                received = 10,
                lossPercent = 0.0,
                minMs = 32.0,
                avgMs = 36.4,
                maxMs = 42.0,
                jitterMdevMs = 2.4,
            ),
            httpLatencyResult = HttpLatencyResult(
                url = "https://hk.example.com",
                statusCode = 200,
                totalMs = 90.0,
                ttfbMs = 52.0,
                tlsHandshakeMs = 35.0,
                success = true,
                errorMessage = null,
            ),
            ipIntelligence = IpIntelligenceReport(
                ip = "203.0.113.42",
                ipFamily = IpFamily.IPV4,
                countryCode = "HK",
                countryName = "Hong Kong",
                regionName = null,
                cityName = null,
                asn = "AS4809",
                orgName = "DMIT",
                ispName = "DMIT",
                usageType = IpUsageType.HOSTING_DATACENTER,
                originType = IpOriginType.NATIVE,
                rirCountryCode = "HK",
                rirAuthority = "APNIC",
                originEvidence = "APNIC HK",
            ),
            iperfDownloadResult = Iperf3Result(
                direction = Iperf3Direction.DOWNLOAD,
                port = 5201,
                resolvedTarget = target,
                durationSec = 10.0,
                bytesTransferred = 625_000_000L,
                clientMbps = 500.0,
                serverMbps = 510.0,
                peakMbps = 560.0,
                retransmits = 4,
                success = true,
                intervals = listOf(
                    Iperf3IntervalSample(0, 0.0, 1.0, 61_250_000L, 490.0, omitted = false),
                    Iperf3IntervalSample(1, 1.0, 2.0, 63_750_000L, 510.0, omitted = false),
                ),
            ),
            iperfUploadResult = Iperf3Result(
                direction = Iperf3Direction.UPLOAD,
                port = 5201,
                resolvedTarget = target,
                durationSec = 10.0,
                bytesTransferred = 500_000_000L,
                clientMbps = 480.0, // client send buffer overstatement
                serverMbps = 320.0, // true receiver throughput
                peakMbps = 350.0,
                success = true,
            ),
            forwardTraceroute = forwardTrace,
            reverseTraceroute = reverseTrace,
        )

        val snap = BaselineSnapshotExtractor.fromNetBenchReport(
            report = report,
            id = "b1",
            label = "DMIT HK Purchase",
            serverId = "srv_1",
            capturedAtEpochMillis = 1_700_000_000_000L,
        )

        assertEquals(BaselineSourceType.MOBILE_NETBENCH, snap.sourceType)
        assertEquals("server:srv_1", snap.targetKey)
        assertEquals(500.0, snap.bandwidth.downloadMbps!!, 0.01)
        assertEquals(320.0, snap.bandwidth.uploadMbps!!, 0.01)
        assertEquals(35.2, snap.latency.icmpAvgMs!!, 0.01)
        assertEquals(52.0, snap.latency.httpTtfbMs!!, 0.01)
        assertEquals(listOf(BackboneRouteTag.CN2_GIA), snap.routing.forwardBackboneTags)
        assertEquals(listOf(BackboneRouteTag.COGENT), snap.routing.reverseBackboneTags)
        assertTrue(snap.routing.isDetourSuspected)
        assertTrue(snap.routing.detourReason!!.contains("US"))
        assertEquals(IpOriginType.NATIVE, snap.ipMetrics?.originType)
        assertEquals(true, snap.isPeakHourWindow)
    }

    @Test
    fun `fromSshRuns extracts and merges NodeQuality and YABS outputs cleanly without fabricating missing values`() {
        val nqOutput = """
            正在运行硬件质量测试...
            CPU 型号: AMD EPYC 7763
            正在运行 IP 质量测试...
            \u001B[32m原生IP\u001B[0m | 机房 IDC | AS4809 | 地区: [HK]
            Netflix: Yes (Region: HK)
            Disney+: Originals Only
            ChatGPT: Yes (Region: HK)
            TikTok: No
            正在运行网络质量测试...
            上海电信 | ↑ 420.0 Mbps | ↓ 480.0 Mbps | 35.0 ms | 0.0%
            广州联通 | ↑ 380.0 Mbps | ↓ 460.0 Mbps | 40.0 ms | 0.5%
            正在运行回程路由追踪...
            北京电信: CN2 GIA (59.43.*)
            北京联通: CU 9929
            北京移动: CMIN2
        """.trimIndent().replace("\\u001B", "\u001B")

        val yabsOutput = """
            fio Disk Speed Tests (Mixed R/W 50/50) (Gen 4):
            Block Size | 4k            (IOPS) | 64k           (IOPS)
              ------   | ---            ----  | ----           ----
            Read       | 150.0 MB/s   (37.5k) | 1.20 GB/s    (18.7k)
            Write      | 152.0 MB/s   (38.0k) | 1.25 GB/s    (19.5k)
            Total      | 302.0 MB/s   (75.5k) | 2.45 GB/s    (38.2k)

            iperf3 Network Speed Tests (IPv4):
            Provider        | Location (Link)           | Send Speed      | Recv Speed      | Ping
            -----           | -----                     | ----            | ----            | ----
            Clouvider       | London, UK (10G)          | 1.20 Gbits/sec  | 2.40 Gbits/sec  | 180 ms

            Geekbench 6 Benchmark Test:
            Single Core | 1450
            Multi Core  | 2980
        """.trimIndent()

        val nqRun = SshExecutionRecord(
            id = "run_nq",
            serverId = "s1",
            serverAlias = "HK-Pro",
            serverHost = "203.0.113.10",
            presetId = SshPresetCatalog.PRESET_NODE_QUALITY,
            presetName = "NodeQuality",
            tabTitle = "NodeQuality",
            command = "bash nq.sh",
            sanitizedAnsiOutput = nqOutput,
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 1_700_000_000_000L,
            durationMillis = 30_000L,
        )
        val yabsRun = SshExecutionRecord(
            id = "run_yabs",
            serverId = "s1",
            serverAlias = "HK-Pro",
            serverHost = "203.0.113.10",
            presetId = SshPresetCatalog.PRESET_YABS,
            presetName = "YABS",
            tabTitle = "YABS",
            command = "bash yabs.sh",
            sanitizedAnsiOutput = yabsOutput,
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 1_700_000_050_000L,
            durationMillis = 45_000L,
        )

        val snap = BaselineSnapshotExtractor.fromSshRuns(
            runs = listOf(nqRun, yabsRun),
            id = "base_1",
        )

        assertEquals(BaselineSourceType.SSH_BENCHMARK, snap.sourceType)
        assertEquals(listOf("nodequality", "yabs"), snap.presetIds)
        assertEquals(1450, snap.bandwidth.geekbenchSingle)
        assertEquals(2980, snap.bandwidth.geekbenchMulti)
        assertEquals(1250.0, snap.bandwidth.diskWriteMbPerSec!!, 0.1)
        assertEquals(470.0, snap.bandwidth.downloadMbps!!, 0.1)
        assertEquals(400.0, snap.bandwidth.uploadMbps!!, 0.1)
        assertEquals(37.5, snap.latency.icmpAvgMs!!, 0.1)
        assertEquals(IpOriginType.NATIVE, snap.ipMetrics?.originType)
        assertEquals(IpUsageType.HOSTING_DATACENTER, snap.ipMetrics?.usageType)
        assertEquals("HK", snap.ipMetrics?.countryCode)

        val unlocks = snap.streamingUnlocks.associateBy { it.platformKey }
        assertEquals(StreamingUnlockStatus.UNLOCKED, unlocks["netflix"]?.status)
        assertEquals("HK", unlocks["netflix"]?.regionCode)
        assertEquals(StreamingUnlockStatus.ORIGINALS_ONLY, unlocks["disney"]?.status)
        assertEquals(StreamingUnlockStatus.UNLOCKED, unlocks["chatgpt"]?.status)
        assertEquals(StreamingUnlockStatus.LOCKED, unlocks["tiktok"]?.status)

        assertTrue(snap.routing.reverseBackboneTags.contains(BackboneRouteTag.CN2_GIA))
        assertTrue(snap.routing.reverseBackboneTags.contains(BackboneRouteTag.CU_9929))
        assertTrue(snap.routing.reverseBackboneTags.contains(BackboneRouteTag.CMIN2))
        assertEquals("CN2 GIA", snap.routing.threeNetBackrouteSummary["电信"])
        assertFalse(snap.routing.isDetourSuspected)
        assertNull(snap.latency.tcpAvgMs)
    }

    @Test
    fun `fromSshRuns parses GoECS, Bench sh, NextTrace, and RegionRestrictionCheck`() {
        val goecsRun = SshExecutionRecord(
            id = "r_ecs",
            serverId = "s2",
            serverAlias = "JP-Test",
            serverHost = "198.51.100.7",
            presetId = SshPresetCatalog.PRESET_ECS,
            presetName = "GoECS",
            tabTitle = "GoECS",
            command = "ecs",
            sanitizedAnsiOutput = """
                I/O Speed(average) : 850.0 MB/s
                Node Name        Upload Speed      Download Speed      Latency
                Tokyo            500.0 Mbps        900.0 Mbps          45.0 ms
                三网回程:
                电信: 163 骨干
                联通: 4837
                移动: CMI
            """.trimIndent(),
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 1_700_000_000_000L,
            durationMillis = 10_000L,
        )
        val mediaRun = SshExecutionRecord(
            id = "r_media",
            serverId = "s2",
            serverAlias = "JP-Test",
            serverHost = "198.51.100.7",
            presetId = SshPresetCatalog.PRESET_MEDIA_UNLOCK,
            presetName = "MediaUnlock",
            tabTitle = "MediaUnlock",
            command = "bash check.sh",
            sanitizedAnsiOutput = """
                Netflix: Yes (Region: JP)
                YouTube Premium: Yes (Region: JP)
                Claude: No
            """.trimIndent(),
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 1_700_000_010_000L,
            durationMillis = 5_000L,
        )

        val snap = BaselineSnapshotExtractor.fromSshRuns(
            runs = listOf(goecsRun, mediaRun),
            id = "base_ecs",
        )
        assertEquals(850.0, snap.bandwidth.diskReadMbPerSec!!, 0.1)
        assertEquals(900.0, snap.bandwidth.downloadMbps!!, 0.1)
        assertEquals(500.0, snap.bandwidth.uploadMbps!!, 0.1)
        assertTrue(snap.routing.reverseBackboneTags.contains(BackboneRouteTag.CT_163))
        assertTrue(snap.routing.reverseBackboneTags.contains(BackboneRouteTag.CU_4837))
        assertTrue(snap.routing.reverseBackboneTags.contains(BackboneRouteTag.CMI))
        val unlocks = snap.streamingUnlocks.associateBy { it.platformKey }
        assertEquals(StreamingUnlockStatus.UNLOCKED, unlocks["netflix"]?.status)
        assertEquals("JP", unlocks["youtube"]?.regionCode)
        assertEquals(StreamingUnlockStatus.LOCKED, unlocks["claude"]?.status)
    }

    @Test
    fun `NextTrace RTTs like 59_43ms and 163_2ms do not false-tag CN2_GIA or CT_163`() {
        assertTrue(BaselineSnapshotExtractor.detectBackboneTagsInText("1  154.54.1.1  AS174  Cogent  59.43 ms").none { it == BackboneRouteTag.CN2_GIA })
        assertTrue(BaselineSnapshotExtractor.detectBackboneTagsInText("2  129.250.2.1  AS2914  NTT  163.2 ms").none { it == BackboneRouteTag.CT_163 })
        assertTrue(BaselineSnapshotExtractor.detectBackboneTagsInText("1  59.43.130.1  AS4809  CN2").contains(BackboneRouteTag.CN2_GIA))
        assertTrue(BaselineSnapshotExtractor.detectBackboneTagsInText("电信: 163 骨干 (AS4134)").contains(BackboneRouteTag.CT_163))

        val nextTraceRun = SshExecutionRecord(
            id = "r_nt",
            serverId = "s_nt",
            serverAlias = "TYO-Test",
            serverHost = "203.0.113.55",
            presetId = SshPresetCatalog.PRESET_NEXT_TRACE,
            presetName = "NextTrace",
            tabTitle = "NextTrace",
            command = "nexttrace 114.240.88.10",
            sanitizedAnsiOutput = """
                NextTrace v1.3.0
                1   154.54.1.1      AS174    Cogent   [US]  59.43 ms  59.43 ms
                2   129.250.2.1     AS2914   NTT      [JP]  163.2 ms  163.2 ms
                3   114.240.88.10   AS4837   CU       [CN]  180.1 ms  180.5 ms
            """.trimIndent(),
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 1_700_000_000_000L,
            durationMillis = 5_000L,
        )
        val snap = BaselineSnapshotExtractor.fromSshRuns(runs = listOf(nextTraceRun), id = "nt_snap")
        assertFalse(
            "RTT 59.43 ms must NOT trigger CN2_GIA: ${snap.routing.reverseBackboneTags}",
            BackboneRouteTag.CN2_GIA in snap.routing.reverseBackboneTags,
        )
        assertFalse(
            "RTT 163.2 ms must NOT trigger CT_163: ${snap.routing.reverseBackboneTags}",
            BackboneRouteTag.CT_163 in snap.routing.reverseBackboneTags,
        )
        assertTrue(BackboneRouteTag.COGENT in snap.routing.reverseBackboneTags)
        assertTrue(BackboneRouteTag.NTT in snap.routing.reverseBackboneTags)
    }

    @Test
    fun `parseIpQualityText extracts countryCode SG even when IP and AS appear earlier on same line`() {
        val nqRun = SshExecutionRecord(
            id = "r_nq_sg",
            serverId = "s_sg",
            serverAlias = "SG-Box",
            serverHost = "203.0.113.88",
            presetId = SshPresetCatalog.PRESET_NODE_QUALITY,
            presetName = "NodeQuality",
            tabTitle = "NodeQuality",
            command = "bash nq.sh",
            sanitizedAnsiOutput = """
                正在运行 IP 质量测试...
                Native IP | Hosting | AS4809 | Country: SG
                正在运行回程路由追踪...
                1  154.54.1.1  AS174  Cogent  165.0 ms
            """.trimIndent(),
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 1_700_000_000_000L,
            durationMillis = 10_000L,
        )
        val snap = BaselineSnapshotExtractor.fromSshRuns(runs = listOf(nqRun), id = "sg_snap")
        assertEquals("SG", snap.ipMetrics?.countryCode)
        assertTrue("Asian SG with Cogent >130ms should flag detour", snap.routing.isDetourSuspected)
    }

    @Test
    fun `measurement values and units never false-trigger CT_163, CU_4837, or CU_9929`() {
        val falsePositiveSamples = listOf(
            "speed=163 KB/s",
            "163 Mbps",
            "163 ms",
            "163s",
            "163 hops",
            "163%",
            "latency=4837ms",
            "latency=9929ms",
            "size=4837KB/s",
            "size=9929KB/s",
            "4837 bytes",
            "9929ms",
            "4134 KB/s",
        )
        for (sample in falsePositiveSamples) {
            val tags = BaselineSnapshotExtractor.detectBackboneTagsInText(sample)
            assertFalse("Unexpected CT_163 on '$sample': $tags", BackboneRouteTag.CT_163 in tags)
            assertFalse("Unexpected CU_4837 on '$sample': $tags", BackboneRouteTag.CU_4837 in tags)
            assertFalse("Unexpected CU_9929 on '$sample': $tags", BackboneRouteTag.CU_9929 in tags)
        }

        // Genuine backbone tokens must still match accurately
        assertTrue(BackboneRouteTag.CU_9929 in BaselineSnapshotExtractor.detectBackboneTagsInText("AS9929"))
        assertTrue(BackboneRouteTag.CU_9929 in BaselineSnapshotExtractor.detectBackboneTagsInText("CU9929"))
        assertTrue(BackboneRouteTag.CU_9929 in BaselineSnapshotExtractor.detectBackboneTagsInText("CU 9929"))
        assertTrue(BackboneRouteTag.CU_9929 in BaselineSnapshotExtractor.detectBackboneTagsInText("联通: 9929"))
        assertTrue(BackboneRouteTag.CU_9929 in BaselineSnapshotExtractor.detectBackboneTagsInText("9929 Shanghai"))

        assertTrue(BackboneRouteTag.CU_4837 in BaselineSnapshotExtractor.detectBackboneTagsInText("AS4837"))
        assertTrue(BackboneRouteTag.CU_4837 in BaselineSnapshotExtractor.detectBackboneTagsInText("CU4837"))
        assertTrue(BackboneRouteTag.CU_4837 in BaselineSnapshotExtractor.detectBackboneTagsInText("CU 4837"))
        assertTrue(BackboneRouteTag.CU_4837 in BaselineSnapshotExtractor.detectBackboneTagsInText("联通: 4837"))

        assertTrue(BackboneRouteTag.CT_163 in BaselineSnapshotExtractor.detectBackboneTagsInText("AS4134"))
        assertTrue(BackboneRouteTag.CT_163 in BaselineSnapshotExtractor.detectBackboneTagsInText("CT163"))
        assertTrue(BackboneRouteTag.CT_163 in BaselineSnapshotExtractor.detectBackboneTagsInText("CT 163"))
        assertTrue(BackboneRouteTag.CT_163 in BaselineSnapshotExtractor.detectBackboneTagsInText("163骨干"))
        assertTrue(BackboneRouteTag.CT_163 in BaselineSnapshotExtractor.detectBackboneTagsInText("163线路"))
        assertTrue(BackboneRouteTag.CT_163 in BaselineSnapshotExtractor.detectBackboneTagsInText("163 Shanghai"))
        assertTrue(BackboneRouteTag.CT_163 in BaselineSnapshotExtractor.detectBackboneTagsInText("ChinaNet"))

        // End-to-end NextTrace fallback (no valid hops -> parseBackrouteText) must not false-tag
        val fallbackNextTraceRun = SshExecutionRecord(
            id = "r_nt_fallback",
            serverId = "s_nt_fb",
            serverAlias = "LAX-Test",
            serverHost = "198.51.100.20",
            presetId = SshPresetCatalog.PRESET_NEXT_TRACE,
            presetName = "NextTrace",
            tabTitle = "NextTrace",
            command = "nexttrace 114.240.88.10",
            sanitizedAnsiOutput = """
                NextTrace v1.3.0
                联通: RTT 9929ms timeout
                电信: speed 163 KB/s
                移动: size=4837KB/s 4837 bytes
            """.trimIndent(),
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 1_700_000_000_000L,
            durationMillis = 5_000L,
        )
        val snap = BaselineSnapshotExtractor.fromSshRuns(
            runs = listOf(fallbackNextTraceRun),
            id = "nt_fallback_snap",
        )
        assertFalse(
            "Fallback NextTrace with 9929ms must NOT tag CU_9929: ${snap.routing.reverseBackboneTags}",
            BackboneRouteTag.CU_9929 in snap.routing.reverseBackboneTags,
        )
        assertFalse(
            "Fallback NextTrace with 163 KB/s must NOT tag CT_163: ${snap.routing.reverseBackboneTags}",
            BackboneRouteTag.CT_163 in snap.routing.reverseBackboneTags,
        )
        assertFalse(
            "Fallback NextTrace with 4837KB/s / 4837 bytes must NOT tag CU_4837: ${snap.routing.reverseBackboneTags}",
            BackboneRouteTag.CU_4837 in snap.routing.reverseBackboneTags,
        )
    }
}
