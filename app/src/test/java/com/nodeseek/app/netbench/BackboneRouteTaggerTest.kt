package com.nodeseek.app.netbench

import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.BackboneRouteTagger
import com.nodeseek.app.domain.netbench.HopLossClassification
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.netbench.TracerouteHop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BackboneRouteTaggerTest {

    @Test
    fun `classifies all six required backbones via IP prefix ASN and rDNS`() {
        assertTrue(
            BackboneRouteTag.CN2_GIA in BackboneRouteTagger.classifyHop(
                ip = "59.43.138.10",
                asn = "AS4809",
                rdns = "core.ctgnet.cn",
            ),
        )
        assertTrue(
            BackboneRouteTag.CU_9929 in BackboneRouteTagger.classifyHop(
                ip = "218.105.2.5",
                asn = "AS9929",
            ),
        )
        assertTrue(
            BackboneRouteTag.CU_9929 in BackboneRouteTagger.classifyHop(
                ip = "210.51.10.1",
                asn = "AS10099",
                rdns = "peer.cuii.net",
            ),
        )
        assertTrue(
            BackboneRouteTag.CMIN2 in BackboneRouteTagger.classifyHop(
                ip = "223.119.26.10",
                asn = "AS58807",
            ),
        )
        assertTrue(
            BackboneRouteTag.CU_4837 in BackboneRouteTagger.classifyHop(
                ip = "219.158.100.1",
                asn = "AS4837",
            ),
        )
        assertTrue(
            BackboneRouteTag.IIJ in BackboneRouteTagger.classifyHop(
                ip = "210.130.1.1",
                asn = "AS2497",
                rdns = "tky001bb01.iij.net",
            ),
        )
        assertTrue(
            BackboneRouteTag.SOFTBANK in BackboneRouteTagger.classifyHop(
                ip = "210.175.1.1",
                asn = "AS17676",
                rdns = "bbtec.net",
            ),
        )
    }

    @Test
    fun `distinguishes CN2 GIA from CN2 GT at path level`() {
        val giaHops = listOf(
            TracerouteHop(hopNumber = 1, ip = "192.168.1.1", isPrivateOrCgnat = true),
            TracerouteHop(
                hopNumber = 2,
                ip = "202.97.33.1",
                countryCode = "CN",
                backboneTags = listOf(BackboneRouteTag.CT_163),
            ),
            TracerouteHop(
                hopNumber = 3,
                ip = "59.43.138.1",
                countryCode = "CN",
                backboneTags = listOf(BackboneRouteTag.CN2_GIA),
            ),
            TracerouteHop(
                hopNumber = 4,
                ip = "59.43.244.10",
                countryCode = "US",
                backboneTags = listOf(BackboneRouteTag.CN2_GIA),
            ),
        )
        val giaResult = BackboneRouteTagger.classifyPath(giaHops, TracerouteDirection.FORWARD)
        assertFalse(giaResult.downgradedCn2ToGt)
        assertTrue(BackboneRouteTag.CN2_GIA in giaResult.dominantTags)

        val gtHops = listOf(
            TracerouteHop(
                hopNumber = 1,
                ip = "59.43.138.1",
                countryCode = "CN",
                backboneTags = listOf(BackboneRouteTag.CN2_GIA),
            ),
            TracerouteHop(
                hopNumber = 2,
                ip = "202.97.50.10",
                countryCode = "US",
                backboneTags = listOf(BackboneRouteTag.CT_163),
            ),
        )
        val gtResult = BackboneRouteTagger.classifyPath(gtHops, TracerouteDirection.FORWARD)
        assertTrue(gtResult.downgradedCn2ToGt)
        assertTrue(BackboneRouteTag.CN2_GT in gtResult.dominantTags)
        assertFalse(BackboneRouteTag.CN2_GIA in gtResult.dominantTags)
    }

    @Test
    fun `classifies intermediate rate limit vs real packet loss vs timeout`() {
        val hops = listOf(
            TracerouteHop(hopNumber = 1, ip = "10.0.0.1", sent = 5, received = 5, lossPercent = 0.0),
            TracerouteHop(hopNumber = 2, ip = null, sent = 5, received = 0, lossPercent = 100.0),
            TracerouteHop(hopNumber = 3, ip = "202.97.33.1", sent = 5, received = 3, lossPercent = 40.0),
            TracerouteHop(hopNumber = 4, ip = "59.43.138.1", sent = 5, received = 5, lossPercent = 0.0),
            TracerouteHop(hopNumber = 5, ip = "45.12.34.56", sent = 5, received = 5, lossPercent = 0.0, isTargetReached = true),
        )
        val classified = BackboneRouteTagger.classifyHopLosses(hops)
        assertEquals(HopLossClassification.ZERO_LOSS, classified[0].lossClassification)
        assertEquals(HopLossClassification.TIMEOUT_NO_REPLY, classified[1].lossClassification)
        assertEquals(HopLossClassification.INTERMEDIATE_RATE_LIMIT, classified[2].lossClassification)
        assertEquals(HopLossClassification.ZERO_LOSS, classified[3].lossClassification)
        assertEquals(HopLossClassification.ZERO_LOSS, classified[4].lossClassification)

        val realLossHops = listOf(
            TracerouteHop(hopNumber = 1, ip = "10.0.0.1", sent = 5, received = 5, lossPercent = 0.0),
            TracerouteHop(hopNumber = 2, ip = "219.158.100.1", sent = 5, received = 3, lossPercent = 40.0),
            TracerouteHop(hopNumber = 3, ip = "45.12.34.56", sent = 5, received = 3, lossPercent = 40.0, isTargetReached = true),
        )
        val realClassified = BackboneRouteTagger.classifyHopLosses(realLossHops)
        assertEquals(HopLossClassification.REAL_PACKET_LOSS, realClassified[1].lossClassification)
        assertEquals(HopLossClassification.REAL_PACKET_LOSS, realClassified[2].lossClassification)
    }

    @Test
    fun `detects Asia Shanghai peak hour window 20_00 to 23_59`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val peakMillis = ZonedDateTime.of(2026, 5, 1, 21, 30, 0, 0, zone).toInstant().toEpochMilli()
        val offPeakMillis = ZonedDateTime.of(2026, 5, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

        val peak = BackboneRouteTagger.detectPeakWindow(peakMillis, zone)
        assertTrue(peak.isPeakHourWindow)
        assertTrue(peak.label.contains("晚高峰"))

        val offPeak = BackboneRouteTagger.detectPeakWindow(offPeakMillis, zone)
        assertFalse(offPeak.isPeakHourWindow)
        assertTrue(offPeak.label.contains("非高峰"))
    }

    @Test
    fun `unwraps IPv4-mapped IPv6 addresses for private CGNAT link-local and non-public checks`() {
        val nonPublicMapped = listOf(
            "::ffff:192.168.1.1",
            "::ffff:10.0.0.1",
            "::ffff:172.16.0.1",
            "::ffff:100.64.0.1",
            "::ffff:169.254.1.1",
            "::ffff:127.0.0.1",
            "::ffff:0.0.0.1",
            "0000:0000:0000:0000:0000:ffff:c0a8:0101",
            "0000:0000:0000:0000:0000:ffff:0a00:0001",
        )
        for (addr in nonPublicMapped) {
            assertTrue("Expected isPrivateOrCgnat=true for $addr", BackboneRouteTagger.isPrivateOrCgnat(addr))
            assertTrue("Expected isNonPublicIp=true for $addr", BackboneRouteTagger.isNonPublicIp(addr))
        }

        // Special non-routable IPv4-mapped ranges (Fake-IP 198.18/15, multicast 224/4, reserved 240/4)
        assertTrue(BackboneRouteTagger.isNonPublicIp("::ffff:198.18.0.1"))
        assertTrue(BackboneRouteTagger.isNonPublicIp("::ffff:224.0.0.1"))
        assertTrue(BackboneRouteTagger.isNonPublicIp("::ffff:240.0.0.1"))

        // Public IPv4-mapped IPv6 stays allowed
        assertFalse(BackboneRouteTagger.isPrivateOrCgnat("::ffff:1.1.1.1"))
        assertFalse(BackboneRouteTagger.isNonPublicIp("::ffff:1.1.1.1"))
        assertFalse(BackboneRouteTagger.isNonPublicIp("0000:0000:0000:0000:0000:ffff:0808:0808"))
    }

    @Test
    fun `rejects non-routable special-purpose IPv6 prefixes in isNonPublicIp while keeping public IPv6 allowed`() {
        val specialNonPublicV6 = listOf(
            "::192.168.1.1",
            "::127.0.0.1",
            "::10.0.0.1",
            "::100.64.0.1",
            "::169.254.1.1",
            "::c0a8:101",
            "::7f00:1",
            "2001:db8::1",
            "100::1",
            "2001:0::1",
            "2001::1",
            "2001:2::1",
            "2001:10::1",
            "2001:20::1",
            "2002:c0a8:0101::1",
            "2002:0a00:0001::1",
            "2002:7f00:0001::1",
            "64:ff9b::192.168.1.1",
            "64:ff9b::8.8.8.8",
            "64:ff9b:1::1",
            "::ffff:0:0:1",
        )
        for (addr in specialNonPublicV6) {
            assertTrue("Expected isNonPublicIp=true for $addr", BackboneRouteTagger.isNonPublicIp(addr))
        }

        val publicV6 = listOf(
            "2408:8207::1",
            "240e:390::1",
            "2001:4860:4860::8888",
            "2606:4700:4700::1111",
            "2001:67c::1",
        )
        for (addr in publicV6) {
            assertFalse("Expected isNonPublicIp=false for $addr", BackboneRouteTagger.isNonPublicIp(addr))
        }
    }
}
