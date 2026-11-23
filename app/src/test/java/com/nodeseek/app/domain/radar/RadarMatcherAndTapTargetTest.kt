package com.nodeseek.app.domain.radar

import com.nodeseek.app.data.parser.FeedTopicDto
import com.nodeseek.app.data.radar.RadarKeywordMatcher
import com.nodeseek.app.data.radar.isWithinQuietHours
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarMatcherAndTapTargetTest {

    private fun sampleTopic(
        id: Long = 100L,
        title: String = "[出] RackNerd 圣何塞 年付 10刀 VPS",
        categoryKey: String = "trade",
        categoryName: String = "交易",
    ) = FeedTopicDto(
        id = id,
        title = title,
        author = "Seller",
        authorId = 200L,
        avatarUrl = "",
        categoryName = categoryName,
        categoryKey = categoryKey,
        views = 100,
        replies = 3,
        activityDate = "2026-11-15T10:00:00Z",
        pinned = false,
    )

    @Test
    fun `contains matching is case-insensitive and honors exclude words and category filter`() {
        val rule = KeywordWatchRule(
            id = 1L,
            keyword = "racknerd",
            excludeWords = "求购，收, 拼车",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
            isEnabled = true,
        )

        assertNotNull(RadarKeywordMatcher.findMatchingRule(sampleTopic(), listOf(rule)))

        // Wrong category
        assertNull(
            RadarKeywordMatcher.findMatchingRule(
                sampleTopic(categoryKey = "daily", categoryName = "日常"),
                listOf(rule),
            ),
        )

        // Excluded by Chinese comma-delimited exclude term
        assertNull(
            RadarKeywordMatcher.findMatchingRule(
                sampleTopic(title = "[求购] RackNerd 圣何塞闪购款"),
                listOf(rule),
            ),
        )
        assertNull(
            RadarKeywordMatcher.findMatchingRule(
                sampleTopic(title = "收一台 RackNerd 年付小鸡"),
                listOf(rule),
            ),
        )

        // Disabled rule does not match
        assertNull(
            RadarKeywordMatcher.findMatchingRule(
                sampleTopic(),
                listOf(rule.copy(isEnabled = false)),
            ),
        )
    }

    @Test
    fun `regex matching supports pattern matching and safely ignores malformed regex`() {
        val regexRule = KeywordWatchRule(
            id = 2L,
            keyword = """\b(?:dmit|claw)\s*(?:hk|jp)\b""",
            excludeWords = "测评",
            categorySlug = "",
            matchMode = KeywordMatchMode.REGEX,
            isEnabled = true,
        )
        val invalidRule = KeywordWatchRule(
            id = 3L,
            keyword = "[unclosed(",
            matchMode = KeywordMatchMode.REGEX,
            isEnabled = true,
        )

        assertEquals(
            regexRule,
            RadarKeywordMatcher.findMatchingRule(
                sampleTopic(title = "出 DMIT HK Pro 季付"),
                listOf(invalidRule, regexRule),
            ),
        )
        assertNull(
            RadarKeywordMatcher.findMatchingRule(
                sampleTopic(title = "Claw JP 测评报告"),
                listOf(invalidRule, regexRule),
            ),
        )
    }

    @Test
    fun `quiet hours handles overnight wrap and daytime windows accurately`() {
        val overnight = RadarPollingSettings(
            quietHoursEnabled = true,
            quietStartMinuteOfDay = 23 * 60, // 23:00
            quietEndMinuteOfDay = 7 * 60,    // 07:00
        )
        assertFalse(isWithinQuietHours(22 * 60 + 59, overnight))
        assertTrue(isWithinQuietHours(23 * 60, overnight))
        assertTrue(isWithinQuietHours(2 * 60 + 15, overnight))
        assertTrue(isWithinQuietHours(6 * 60 + 59, overnight))
        assertFalse(isWithinQuietHours(7 * 60, overnight))

        val sameDay = RadarPollingSettings(
            quietHoursEnabled = true,
            quietStartMinuteOfDay = 13 * 60, // 13:00
            quietEndMinuteOfDay = 14 * 60,   // 14:00
        )
        assertFalse(isWithinQuietHours(12 * 60 + 59, sameDay))
        assertTrue(isWithinQuietHours(13 * 60 + 30, sameDay))
        assertFalse(isWithinQuietHours(14 * 60, sameDay))

        assertFalse(isWithinQuietHours(23 * 60 + 30, overnight.copy(quietHoursEnabled = false)))
        assertFalse(
            isWithinQuietHours(
                10 * 60,
                overnight.copy(quietStartMinuteOfDay = 600, quietEndMinuteOfDay = 600),
            ),
        )
    }

    @Test
    fun `notificationTapTarget encodes and decodes topic, direct message, and alert history targets cleanly`() {
        val topicTarget = NotificationTapTarget.OpenTopic(
            topicId = 285109L,
            initialFloor = 17,
            titleHint = "出 Claw & DMIT 机器 #1",
            alertDedupKey = "reply:285109:17",
        )
        val encodedTopic = PulseNotificationDispatcher.encodeTapTarget(topicTarget)
        assertEquals(topicTarget, PulseNotificationDispatcher.decodeTapTarget(encodedTopic))

        val dmTarget = NotificationTapTarget.OpenDirectMessage(
            peerUid = 9981L,
            peerName = "Alice & Bob",
        )
        val encodedDm = PulseNotificationDispatcher.encodeTapTarget(dmTarget)
        assertEquals(dmTarget, PulseNotificationDispatcher.decodeTapTarget(encodedDm))

        val alertsTarget = NotificationTapTarget.OpenRadarAlerts
        val encodedAlerts = PulseNotificationDispatcher.encodeTapTarget(alertsTarget)
        assertEquals(alertsTarget, PulseNotificationDispatcher.decodeTapTarget(encodedAlerts))

        // Invalid payloads & null Intent
        assertNull(PulseNotificationDispatcher.decodeTapTarget(null))
        assertNull(PulseNotificationDispatcher.decodeTapTarget("https://www.nodeseek.com/post-1-1"))
        assertNull(PulseNotificationDispatcher.decodeTapTarget("nodepulse://radar/topic?topicId=0"))
        assertNull(PulseNotificationDispatcher.decodeTapTarget("nodepulse://radar/dm?peerUid=-1"))
        assertNull(PulseNotificationDispatcher.parseTapTarget(null))

        // Notification IDs are positive and separated across channels
        val kwId = PulseNotificationDispatcher.notificationIdForKey("kw:101", 10_000)
        val trackedId = PulseNotificationDispatcher.notificationIdForKey("tracked:101", 20_000)
        val dmId = PulseNotificationDispatcher.notificationIdForKey("dm:101", 40_000)
        assertTrue(kwId in 10_000 until 19_000)
        assertTrue(trackedId in 20_000 until 29_000)
        assertTrue(dmId in 40_000 until 49_000)
        assertNotEquals(kwId, trackedId)
    }

    @Test
    fun `inboxTargetResolver parses post links, page and floor numbers, and maps in-app popups to exact routes`() {
        // Full URL with page and floor fragment
        assertEquals(
            ResolvedTopicLocation(topicId = 285109L, page = 3, floor = 27),
            InboxTargetResolver.parseTopicLocationFromUrl("https://www.nodeseek.com/post-285109-3#27"),
        )
        // Relative path with floor-N fragment
        assertEquals(
            ResolvedTopicLocation(topicId = 285109L, page = 2, floor = 15),
            InboxTargetResolver.parseTopicLocationFromUrl("/post-285109-2#floor-15"),
        )
        // Bare post path with comment-N fragment
        assertEquals(
            ResolvedTopicLocation(topicId = 285109L, page = 4, floor = 35),
            InboxTargetResolver.parseTopicLocationFromUrl("post-285109-4#comment-35"),
        )
        // Page without fragment maps to first floor of that page
        assertEquals(
            ResolvedTopicLocation(topicId = 285109L, page = 3, floor = 21),
            InboxTargetResolver.parseTopicLocationFromUrl("/post-285109-3"),
        )
        // Query floor parameter fallback & /jump unwrap
        assertEquals(
            ResolvedTopicLocation(topicId = 285109L, page = 2, floor = 18),
            InboxTargetResolver.parseTopicLocationFromUrl("/post-285109-2?commentId=999&floor=18"),
        )
        assertEquals(
            ResolvedTopicLocation(topicId = 99123L, page = 3, floor = 27),
            InboxTargetResolver.parseTopicLocationFromUrl(
                "https://www.nodeseek.com/jump?to=%2Fpost-99123-3%2327",
            ),
        )
        assertNull(InboxTargetResolver.parseTopicLocationFromUrl("https://example.com/post-285109-1#2"))

        // NotificationItem resolution: direct topicId+floorNumber and embedded URL fallback
        val itemWithDirectFloor = NotificationItem(
            id = 55L,
            kind = NotificationKind.REPLIES,
            topicId = 500L,
            topicTitle = "测试主题",
            floorNumber = 27,
            senderUid = 202L,
            senderName = "Alice",
            createdAtIso = "2026-11-16T01:00:00Z",
            isRead = false,
        )
        assertEquals(
            ResolvedTopicLocation(topicId = 500L, page = 3, floor = 27),
            InboxTargetResolver.resolveForNotificationItem(itemWithDirectFloor),
        )

        val itemWithEmbeddedLink = itemWithDirectFloor.copy(
            topicId = 0L,
            floorNumber = 0,
            excerpt = "详见 https://www.nodeseek.com/post-601-2#16 回复",
        )
        assertEquals(
            ResolvedTopicLocation(topicId = 601L, page = 2, floor = 16),
            InboxTargetResolver.resolveForNotificationItem(itemWithEmbeddedLink),
        )

        // InAppMessagePopup -> NotificationTapTarget mapping & encode/decode roundtrip
        val mentionPopup = InAppMessagePopup(
            dedupKey = "interaction:601:16",
            kind = InAppMessageKind.MENTION,
            itemId = 88L,
            senderUid = 303L,
            senderName = "Carol",
            senderAvatarUrl = "https://www.nodeseek.com/avatar/303.png",
            title = "提及主题",
            previewText = "看下 16 楼",
            topicId = 601L,
            targetFloor = 16,
            targetPage = 2,
        )
        val mentionTarget = InboxTargetResolver.toTapTarget(mentionPopup)
        assertEquals(
            NotificationTapTarget.OpenTopic(
                topicId = 601L,
                initialFloor = 16,
                titleHint = "提及主题",
                notificationKind = NotificationKind.MENTIONS,
                notificationId = 88L,
            ),
            mentionTarget,
        )
        assertEquals(
            mentionTarget,
            PulseNotificationDispatcher.decodeTapTarget(PulseNotificationDispatcher.encodeTapTarget(mentionTarget!!)),
        )

        val dmPopup = InAppMessagePopup(
            dedupKey = "dm:888:501",
            kind = InAppMessageKind.DIRECT_MESSAGE,
            itemId = 501L,
            senderUid = 888L,
            senderName = "Bob",
            senderAvatarUrl = "https://www.nodeseek.com/avatar/888.png",
            title = "Bob",
            previewText = "你好",
            peerUid = 888L,
            peerName = "Bob",
        )
        val dmTarget = InboxTargetResolver.toTapTarget(dmPopup)
        assertEquals(
            NotificationTapTarget.OpenDirectMessage(
                peerUid = 888L,
                peerName = "Bob",
                peerAvatarUrl = "https://www.nodeseek.com/avatar/888.png",
            ),
            dmTarget,
        )
        assertEquals(
            dmTarget,
            PulseNotificationDispatcher.decodeTapTarget(PulseNotificationDispatcher.encodeTapTarget(dmTarget!!)),
        )
    }

    @Test
    fun `notification channel constants use upgraded v2 high-importance IDs for forum and radar alerts`() {
        assertEquals("pulse_forum_interactions_v2", PulseNotificationDispatcher.CHANNEL_FORUM_INTERACTIONS)
        assertEquals("pulse_tracked_replies_v2", PulseNotificationDispatcher.CHANNEL_TRACKED_REPLIES)
        assertEquals("pulse_keyword_alerts_v2", PulseNotificationDispatcher.CHANNEL_KEYWORD_ALERTS)
        assertEquals("pulse_direct_messages", PulseNotificationDispatcher.CHANNEL_DIRECT_MESSAGES)
    }

    @Test
    fun `keyword matcher matches topic excerpt as well as title and respects exclude words in excerpt`() {
        val rule = KeywordWatchRule(
            id = 10L,
            keyword = "cn2 gia",
            excludeWords = "已出",
            categorySlug = "trade",
            matchMode = KeywordMatchMode.CONTAINS,
            isEnabled = true,
        )
        val excerptHit = sampleTopic(title = "出几台闲置小鸡").copy(
            excerpt = "洛杉矶 CN2 GIA 年付特价款",
        )
        assertEquals(rule, RadarKeywordMatcher.findMatchingRule(excerptHit, listOf(rule)))

        val excerptExcluded = excerptHit.copy(
            excerpt = "洛杉矶 CN2 GIA 年付特价款，已出封帖",
        )
        assertNull(RadarKeywordMatcher.findMatchingRule(excerptExcluded, listOf(rule)))
    }

    @Test
    fun `nested quantifier regex patterns are rejected by canonicalizer and ignored safely by matcher`() {
        assertTrue(RadarRuleCanonicalizer.isPotentiallyUnsafeRegex("(a+)+$"))
        assertTrue(RadarRuleCanonicalizer.isPotentiallyUnsafeRegex("([a-zA-Z]+)*foo"))
        assertFalse(RadarRuleCanonicalizer.isPotentiallyUnsafeRegex("""\b(?:dmit|claw)\s*(?:hk|jp)\b"""))

        val validation = RadarRuleCanonicalizer.validateAndNormalizeKeyword("(a+)+$", KeywordMatchMode.REGEX)
        assertTrue(validation.isFailure)

        val dangerousRule = KeywordWatchRule(
            id = 11L,
            keyword = "(a+)+$",
            matchMode = KeywordMatchMode.REGEX,
            isEnabled = true,
        )
        val evilTopic = sampleTopic(title = "aaaaaaaaaaaaaaaaaaaaaaaaaaaa!")
        assertNull(RadarKeywordMatcher.findMatchingRule(evilTopic, listOf(dangerousRule)))
    }
}
