package com.nodeseek.app.activity

import com.nodeseek.app.domain.activity.DrandBeacon
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import com.nodeseek.app.domain.activity.LuckyDrawOutcome
import com.nodeseek.app.domain.activity.LuckyDrawSpec
import com.nodeseek.app.domain.activity.LuckyFloorEntry
import com.nodeseek.app.domain.activity.LuckyViewerStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrandLuckyEngineTest {

    @Test
    fun `cyrb128 and sfc32 match exact JS reference vectors`() {
        val seedStr = "8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce"
        val hash = DrandLuckyEngine.cyrb128(seedStr)
        assertArrayEquals(
            uintArrayOf(2999962186u, 472113598u, 2003774896u, 4172855273u).toIntArray(),
            hash.toIntArray(),
        )

        val prng = DrandLuckyEngine.Sfc32(hash)
        val first8 = UIntArray(8) { prng.nextUInt32() }
        assertArrayEquals(
            uintArrayOf(
                3349963761u,
                1204765544u,
                2537588718u,
                4226790340u,
                246254636u,
                2583592621u,
                994288500u,
                2818640333u,
            ).toIntArray(),
            first8.toIntArray(),
        )
    }

    @Test
    fun `computeDrandRound computes exact League of Entropy mainnet rounds`() {
        assertEquals(1L, DrandLuckyEngine.computeDrandRound(1_595_431_080_000L))
        assertEquals(6_666_925L, DrandLuckyEngine.computeDrandRound(1_795_438_800_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `computeDrandRound rejects timestamp before Drand genesis`() {
        DrandLuckyEngine.computeDrandRound(1_595_431_049_000L)
    }

    @Test
    fun `parseLuckyUrl and buildLuckyUrl handle official params, HTML amps, relative paths, and subdomains`() {
        val spec = DrandLuckyEngine.parseLuckyUrl(
            "https://www.nodeseek.com/lucky?post=1073388&amp;time=1795438800000&amp;count=3&amp;start=1&amp;duplicate=false&amp;mode=view",
        )!!
        assertEquals(1073388L, spec.postId)
        assertEquals(1795438800000L, spec.drawTimeEpochMillis)
        assertEquals(3, spec.winnerCount)
        assertEquals(1, spec.firstFloor)
        assertTrue(spec.removeDuplicates)

        val rebuilt = DrandLuckyEngine.buildLuckyUrl(spec)
        assertEquals(
            "https://www.nodeseek.com/lucky?post=1073388&time=1795438800000&count=3&start=1&duplicate=false&mode=view",
            rebuilt,
        )

        val relative = DrandLuckyEngine.parseLuckyUrl("/lucky?post=888&time=1795438800&count=2&duplicate=true")!!
        assertEquals(888L, relative.postId)
        assertEquals(1795438800_000L, relative.drawTimeEpochMillis)
        assertFalse(relative.removeDuplicates)

        val subdomain = DrandLuckyEngine.parseLuckyUrl("https://lucky.nodeseek.com/?post=618001")!!
        assertEquals(618001L, subdomain.postId)
        assertNull(subdomain.drawTimeEpochMillis)

        assertNull(DrandLuckyEngine.parseLuckyUrl("https://www.nodeseek.com/lucky"))
        assertNull(DrandLuckyEngine.parseLuckyUrl("https://www.nodeseek.com/post-123-1"))
    }

    @Test
    fun `extractFirstLuckySpec locates relative and jump-wrapped lucky links in markdown`() {
        val md = """
            欢迎参加本次抽奖！
            [点击开奖](https://www.nodeseek.com/jump?to=https%3A%2F%2Fwww.nodeseek.com%2Flucky%3Fpost%3D501%26time%3D1795438800000%26count%3D2)
        """.trimIndent()
        val extracted = DrandLuckyEngine.extractFirstLuckySpec(md, fallbackPostId = 501L)
        assertNotNull(extracted)
        assertEquals(501L, extracted!!.postId)
        assertEquals(2, extracted.winnerCount)
    }

    @Test
    fun `drawWinners consumes PRNG per floor before filtering start and handles duplicate vs unique`() {
        val randomness = "8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce"
        val floors = listOf(
            LuckyFloorEntry(floorId = 0, memberId = 100L, memberName = "OP_User"),
            LuckyFloorEntry(floorId = 1, memberId = 100L, memberName = "OP_User"),
            LuckyFloorEntry(floorId = 2, memberId = 200L, memberName = "Alice"),
            LuckyFloorEntry(floorId = 3, memberId = 200L, memberName = "Alice"),
            LuckyFloorEntry(floorId = 4, memberId = 300L, memberName = "Bob"),
        )

        // Floor 0 is excluded by start=1, and MUST NOT prevent OP_User (memberId=100) from winning at floor 1!
        val uniqueSpec = LuckyDrawSpec(
            postId = 1L,
            drawTimeEpochMillis = 1_795_438_800_000L,
            winnerCount = 3,
            firstFloor = 1,
            removeDuplicates = true,
        )
        val uniqueWinners = DrandLuckyEngine.drawWinners(floors, uniqueSpec, randomness)
        assertEquals(3, uniqueWinners.size)
        assertEquals(listOf(100L, 200L, 300L), uniqueWinners.map { it.memberId }.sorted())
        assertTrue(uniqueWinners.all { it.floorId >= 1 })
        // Sorted by floorId ascending
        assertEquals(uniqueWinners.sortedBy { it.floorId }, uniqueWinners)

        // With duplicate=true (removeDuplicates=false), memberId=200 can win twice if both floors score lowest
        val dupSpec = uniqueSpec.copy(winnerCount = 4, removeDuplicates = false)
        val dupWinners = DrandLuckyEngine.drawWinners(floors, dupSpec, randomness)
        assertEquals(4, dupWinners.size)

        // winnerCount > eligible floors does not crash and returns all eligible
        val overSpec = uniqueSpec.copy(winnerCount = 99)
        val overWinners = DrandLuckyEngine.drawWinners(floors, overSpec, randomness)
        assertEquals(3, overWinners.size)
    }

    @Test
    fun `evaluateViewerStatus and buildWinnerReplyMarkdown produce valid mention formats`() {
        val spec = LuckyDrawSpec(
            postId = 777L,
            drawTimeEpochMillis = 1_795_438_800_000L,
            winnerCount = 2,
            firstFloor = 1,
            removeDuplicates = true,
        )
        val floors = listOf(
            LuckyFloorEntry(0, 1L, "OP"),
            LuckyFloorEntry(1, 10L, "WinnerA"),
            LuckyFloorEntry(2, 20L, "LoserB"),
            LuckyFloorEntry(3, 30L, "WinnerC"),
        )
        val winners = listOf(floors[1], floors[3])

        assertEquals(
            LuckyViewerStatus.NOT_LOGGED_IN,
            DrandLuckyEngine.evaluateViewerStatus(spec, floors, winners, null, isDrawn = true).first,
        )
        assertEquals(
            LuckyViewerStatus.MY_WIN,
            DrandLuckyEngine.evaluateViewerStatus(spec, floors, winners, 10L, isDrawn = true).first,
        )
        assertEquals(
            LuckyViewerStatus.PARTICIPATED_NOT_WON,
            DrandLuckyEngine.evaluateViewerStatus(spec, floors, winners, 20L, isDrawn = true).first,
        )
        assertEquals(
            LuckyViewerStatus.INELIGIBLE_FLOOR,
            DrandLuckyEngine.evaluateViewerStatus(spec, floors, winners, 1L, isDrawn = true).first,
        )
        assertEquals(
            LuckyViewerStatus.NOT_PARTICIPATED,
            DrandLuckyEngine.evaluateViewerStatus(spec, floors, winners, 999L, isDrawn = true).first,
        )
        assertEquals(
            LuckyViewerStatus.PARTICIPATED_PENDING,
            DrandLuckyEngine.evaluateViewerStatus(spec, floors, emptyList(), 20L, isDrawn = false).first,
        )

        val outcome = LuckyDrawOutcome(
            spec = spec,
            drandRound = 6_666_925L,
            beacon = DrandBeacon(round = 6_666_925L, randomness = "abcd1234"),
            floors = floors,
            eligibleFloorCount = 3,
            winners = winners,
            viewerStatus = LuckyViewerStatus.MY_WIN,
            viewerFloors = listOf(floors[1]),
            viewerWinningFloors = listOf(floors[1]),
            isDrawn = true,
        )
        val replyMd = DrandLuckyEngine.buildWinnerReplyMarkdown(outcome)
        assertTrue(replyMd.contains("@WinnerA @WinnerC"))
        assertTrue(replyMd.contains("#1楼 @WinnerA"))
        assertTrue(replyMd.contains("#3楼 @WinnerC"))
        assertTrue(replyMd.contains("https://www.nodeseek.com/lucky?post=777"))

        val mentionRegex = Regex("""(?<![A-Za-z0-9_@#])@([\p{L}\p{N}_-]+)""")
        val matchedNames = mentionRegex.findAll(replyMd).map { it.groupValues[1] }.toList()
        assertTrue(matchedNames.containsAll(listOf("WinnerA", "WinnerC")))
    }

    @Test
    fun `extractFirstLuckySpec strips adjacent CJK prose and prefers timed draw link over timeless link`() {
        val withCommaProse = DrandLuckyEngine.extractFirstLuckySpec(
            "抽奖链接：https://www.nodeseek.com/lucky?post=123&time=1795438800000，欢迎参加！",
            fallbackPostId = 123L,
        )
        assertEquals(1_795_438_800_000L, withCommaProse?.drawTimeEpochMillis)

        val withFullWidthParens = DrandLuckyEngine.extractFirstLuckySpec(
            "（https://www.nodeseek.com/lucky?post=123&time=1795438800000）",
            fallbackPostId = 123L,
        )
        assertEquals(1_795_438_800_000L, withFullWidthParens?.drawTimeEpochMillis)

        val withFullWidthPeriod = DrandLuckyEngine.extractFirstLuckySpec(
            "https://www.nodeseek.com/lucky?post=123&time=1795438800000。",
            fallbackPostId = 123L,
        )
        assertEquals(1_795_438_800_000L, withFullWidthPeriod?.drawTimeEpochMillis)

        val withDuplicateTrueAndCjk = DrandLuckyEngine.extractFirstLuckySpec(
            "https://www.nodeseek.com/lucky?post=123&time=1795438800000&duplicate=true，欢迎",
            fallbackPostId = 123L,
        )
        assertNotNull(withDuplicateTrueAndCjk)
        assertFalse(withDuplicateTrueAndCjk!!.removeDuplicates)

        val relativeWithCountAndCjk = DrandLuckyEngine.extractFirstLuckySpec(
            "请点击 /lucky?post=123&time=1795438800000&count=2，祝你好运！",
            fallbackPostId = 123L,
        )
        assertEquals(2, relativeWithCountAndCjk?.winnerCount)

        val jumpWithCjk = DrandLuckyEngine.extractFirstLuckySpec(
            "详情 /jump?to=https%3A%2F%2Fwww.nodeseek.com%2Flucky%3Fpost%3D123%26time%3D1795438800000%26count%3D4，别错过",
            fallbackPostId = 123L,
        )
        assertEquals(1_795_438_800_000L, jumpWithCjk?.drawTimeEpochMillis)
        assertEquals(4, jumpWithCjk?.winnerCount)

        val timelessBeforeTimed = DrandLuckyEngine.extractFirstLuckySpec(
            "参考 https://www.nodeseek.com/lucky?post=123 正式 https://www.nodeseek.com/lucky?post=123&time=1795438800000",
            fallbackPostId = 123L,
        )
        assertEquals(1_795_438_800_000L, timelessBeforeTimed?.drawTimeEpochMillis)

        val onlyTimeless = DrandLuckyEngine.extractFirstLuckySpec(
            "仅参考 https://www.nodeseek.com/lucky?post=123。",
            fallbackPostId = 123L,
        )
        assertEquals(123L, onlyTimeless?.postId)
        assertNull(onlyTimeless?.drawTimeEpochMillis)

        val directWithTrailingCjk = DrandLuckyEngine.parseLuckyUrl(
            "https://www.nodeseek.com/lucky?post=123&time=1795438800000。",
        )
        assertEquals(1_795_438_800_000L, directWithTrailingCjk?.drawTimeEpochMillis)
    }

    @Test
    fun `extractFirstLuckySpec and parseLuckyUrl exclude adjacent supplementary Unicode emojis and Ext-B CJK`() {
        val withAdjacentEmoji = DrandLuckyEngine.extractFirstLuckySpec(
            "开奖👉https://www.nodeseek.com/lucky?post=123&time=1795438800000🎲",
            fallbackPostId = 123L,
        )
        assertEquals(1_795_438_800_000L, withAdjacentEmoji?.drawTimeEpochMillis)

        val withCountAndEmojiCjk = DrandLuckyEngine.extractFirstLuckySpec(
            "🎲https://www.nodeseek.com/lucky?post=123&time=1795438800000&count=3🎉祝中奖",
            fallbackPostId = 123L,
        )
        assertEquals(3, withCountAndEmojiCjk?.winnerCount)
        assertEquals(1_795_438_800_000L, withCountAndEmojiCjk?.drawTimeEpochMillis)

        val withDuplicateAndEmoji = DrandLuckyEngine.extractFirstLuckySpec(
            "https://www.nodeseek.com/lucky?post=123&time=1795438800000&duplicate=true🔥",
            fallbackPostId = 123L,
        )
        assertNotNull(withDuplicateAndEmoji)
        assertFalse(withDuplicateAndEmoji!!.removeDuplicates)

        val withExtBCjk = DrandLuckyEngine.extractFirstLuckySpec(
            "https://www.nodeseek.com/lucky?post=123&time=1795438800000𠮷",
            fallbackPostId = 123L,
        )
        assertEquals(1_795_438_800_000L, withExtBCjk?.drawTimeEpochMillis)

        val directWithTrailingEmoji = DrandLuckyEngine.parseLuckyUrl(
            "https://www.nodeseek.com/lucky?post=123&time=1795438800000🎉",
        )
        assertEquals(1_795_438_800_000L, directWithTrailingEmoji?.drawTimeEpochMillis)
    }

    @Test
    fun `buildWinnerReplyMarkdown formats blank memberName safely as UID fallback`() {
        val spec = LuckyDrawSpec(
            postId = 888L,
            drawTimeEpochMillis = 1_795_438_800_000L,
            winnerCount = 2,
            firstFloor = 1,
            removeDuplicates = true,
        )
        val blankWinner = LuckyFloorEntry(floorId = 1, memberId = 99L, memberName = "   ")
        val namedWinner = LuckyFloorEntry(floorId = 2, memberId = 100L, memberName = "Alice")
        val outcome = LuckyDrawOutcome(
            spec = spec,
            drandRound = 6_666_925L,
            beacon = DrandBeacon(round = 6_666_925L, randomness = "abcd1234"),
            floors = listOf(blankWinner, namedWinner),
            eligibleFloorCount = 2,
            winners = listOf(blankWinner, namedWinner),
            viewerStatus = LuckyViewerStatus.NOT_PARTICIPATED,
            viewerFloors = emptyList(),
            viewerWinningFloors = emptyList(),
            isDrawn = true,
        )

        val replyMd = DrandLuckyEngine.buildWinnerReplyMarkdown(outcome)
        assertTrue(replyMd.contains("- #1楼 UID 99"))
        assertFalse(replyMd.contains("@ (UID:"))
        assertTrue(replyMd.contains("- #2楼 @Alice (UID: 100)"))
        assertTrue(replyMd.contains("恭喜以下中奖用户：@Alice"))
    }

    @Test
    fun `parseLuckyUrl_negativeZeroAndDupFlags_and_drawWinners_blankRandomnessAndInvertedBounds`() {
        val negativeCountAndStart = DrandLuckyEngine.parseLuckyUrl(
            "https://www.nodeseek.com/lucky?post=123&time=1795438800000&count=-3&start=-5&duplicate=1",
        )
        assertNotNull(negativeCountAndStart)
        assertEquals(1, negativeCountAndStart!!.winnerCount)
        assertEquals(0, negativeCountAndStart.firstFloor)
        assertFalse(negativeCountAndStart.removeDuplicates)

        // Unknown short key `dup=1` does not bypass protocol `duplicate` default (removeDuplicates=true)
        val nonStandardDup = DrandLuckyEngine.parseLuckyUrl(
            "https://www.nodeseek.com/lucky?post=123&time=1795438800000&dup=1&count=0&start=0",
        )
        assertNotNull(nonStandardDup)
        assertEquals(1, nonStandardDup!!.winnerCount)
        assertEquals(0, nonStandardDup.firstFloor)
        assertTrue(nonStandardDup.removeDuplicates)

        val floors = listOf(
            LuckyFloorEntry(floorId = 1, memberId = 101L, memberName = "Alice"),
            LuckyFloorEntry(floorId = 2, memberId = 102L, memberName = "Bob"),
        )
        assertTrue(DrandLuckyEngine.drawWinners(floors, nonStandardDup, "   ").isEmpty())

        val invertedSpec = nonStandardDup.copy(firstFloor = 999)
        assertTrue(
            DrandLuckyEngine.drawWinners(
                floors = floors,
                spec = invertedSpec,
                randomnessHex = "8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce",
            ).isEmpty(),
        )
    }
}
