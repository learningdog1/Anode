package com.nodeseek.app.domain.model

import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollAndStardustModelsTest {

    @Test
    fun `parseNsAppWidgetTarget parses vote id and voteId aliases and rejects invalid targets`() {
        val vote1 = parseNsAppWidgetTarget("nsapp://vote?id=42") as NsAppWidgetTarget.Vote
        assertEquals(42L, vote1.voteId)

        val vote2 = parseNsAppWidgetTarget("  NSAPP://VOTE?voteId=99  ") as NsAppWidgetTarget.Vote
        assertEquals(99L, vote2.voteId)

        assertNull(parseNsAppWidgetTarget("nsapp://vote?id=0"))
        assertNull(parseNsAppWidgetTarget("nsapp://vote?id=-5"))
        assertNull(parseNsAppWidgetTarget("nsapp://vote"))
        assertNull(parseNsAppWidgetTarget("https://www.nodeseek.com/vote?id=42"))
    }

    @Test
    fun `parseNsAppWidgetTarget parses stardust-receive with decoded note, once flag, amount and ref`() {
        val target = parseNsAppWidgetTarget(
            "nsapp://stardust-receive?uid=101&amount=10&ref=123&once=1&note=hello%20world%20%E6%98%9F%E5%B0%98",
        ) as NsAppWidgetTarget.StardustReceive

        assertEquals(101L, target.receiverUid)
        assertEquals(10, target.suggestedAmount)
        assertEquals(123L, target.refId)
        assertTrue(target.once)
        assertEquals("hello world 星尘", target.note)

        val minimal = parseNsAppWidgetTarget("nsapp://stardust-receive?uid=202") as NsAppWidgetTarget.StardustReceive
        assertEquals(202L, minimal.receiverUid)
        assertNull(minimal.suggestedAmount)
        assertEquals(0L, minimal.refId)
        assertFalse(minimal.once)
        assertEquals("", minimal.note)

        assertNull(parseNsAppWidgetTarget("nsapp://stardust-receive?uid=0"))
    }

    @Test
    fun `PollDetail handles null pre-vote counts vs populated post-vote percentages`() {
        val preVote = PollDetail(
            id = 42L,
            title = "选择线路",
            creatorUid = 10L,
            isPublic = true,
            isLocked = false,
            isMultiple = false,
            options = listOf(
                PollOption(voteItemId = 1L, text = "CN2 GIA", count = null, voted = false),
                PollOption(voteItemId = 2L, text = "CMIN2", count = null, voted = false),
            ),
        )
        assertFalse(preVote.hasCurrentUserVoted)
        assertFalse(preVote.hasVisibleCounts)
        assertEquals(0L, preVote.totalVotes)
        assertEquals(0f, preVote.shareFraction(preVote.options[0]), 0.0001f)
        assertEquals(0, preVote.sharePercent(preVote.options[0]))

        val postVote = preVote.copy(
            options = listOf(
                PollOption(voteItemId = 1L, text = "CN2 GIA", count = 3, voted = true, voters = listOf(10L)),
                PollOption(voteItemId = 2L, text = "CMIN2", count = 1, voted = false),
            ),
        )
        assertTrue(postVote.hasCurrentUserVoted)
        assertTrue(postVote.hasVisibleCounts)
        assertEquals(4L, postVote.totalVotes)
        assertEquals(0.75f, postVote.shareFraction(postVote.options[0]), 0.0001f)
        assertEquals(75, postVote.sharePercent(postVote.options[0]))
        assertEquals(25, postVote.sharePercent(postVote.options[1]))
    }

    @Test
    fun `PollDetail sums large vote counts in Long without Int overflow`() {
        val hugePoll = PollDetail(
            id = 99L,
            title = "Large Poll",
            creatorUid = 1L,
            isPublic = true,
            isLocked = false,
            isMultiple = false,
            options = listOf(
                PollOption(voteItemId = 1L, text = "A", count = Int.MAX_VALUE),
                PollOption(voteItemId = 2L, text = "B", count = Int.MAX_VALUE),
                PollOption(voteItemId = 3L, text = "Neg", count = -5),
            ),
        )

        val expectedTotal = Int.MAX_VALUE.toLong() * 2L
        assertEquals(expectedTotal, hugePoll.totalVotes)
        assertEquals(0.5f, hugePoll.shareFraction(hugePoll.options[0]), 0.0001f)
        assertEquals(50, hugePoll.sharePercent(hugePoll.options[0]))
        assertEquals(0f, hugePoll.shareFraction(hugePoll.options[2]), 0.0001f)
    }

    @Test
    fun `formatVoteWidgetMarkdown sanitizes brackets and round-trips into MdBlock SpecialWidget`() {
        val md = formatVoteWidgetMarkdown(voteId = 42L, title = "[调查] 哪家好?\n请投票")
        assertEquals("[【调查】 哪家好? 请投票](nsapp://vote?id=42)", md)

        val blocks = parseNodeSeekMarkdownBlocks(md)
        assertEquals(1, blocks.size)
        val widget = blocks.single() as MdBlock.SpecialWidget
        assertEquals("【调查】 哪家好? 请投票", widget.title)
        assertEquals("nsapp://vote?id=42", widget.url)
    }

    @Test
    fun `formatStardustWidgetMarkdown builds valid nsapp stardust-receive widget markdown`() {
        val md = formatStardustWidgetMarkdown(
            uid = 101L,
            amount = 20,
            note = "感谢测评",
            once = true,
            refId = 555L,
        )
        val blocks = parseNodeSeekMarkdownBlocks(md)
        assertEquals(1, blocks.size)
        val widget = blocks.single() as MdBlock.SpecialWidget
        val parsed = parseNsAppWidgetTarget(widget.url) as NsAppWidgetTarget.StardustReceive
        assertEquals(101L, parsed.receiverUid)
        assertEquals(20, parsed.suggestedAmount)
        assertEquals(555L, parsed.refId)
        assertTrue(parsed.once)
        assertEquals("感谢测评", parsed.note)
    }
}
