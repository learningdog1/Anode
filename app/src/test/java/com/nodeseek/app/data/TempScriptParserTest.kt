package com.nodeseek.app.data

import com.nodeseek.app.data.parser.ConfigPayloadDto
import com.nodeseek.app.data.parser.TempScriptParser
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TempScriptParserTest {

    @Test
    fun `decodes Base64 temp-script with user session, roles, categories, and thread postData`() {
        val rawConfigJson = """
            {
              "enableCustomedStyle": 1,
              "user": {
                "member_id": 101,
                "member_name": "NodeExplorer",
                "coin": "1,650",
                "stardust": 42,
                "nPost": 18,
                "nComment": 320,
                "rank": 2,
                "roles": ["dev"],
                "collectionCount": 14,
                "telegram_id": 987654321,
                "unViewedCount": { "all": 5 }
              },
              "allCategory": [
                { "key": "daily", "cn_text": "日常", "customizable": 1, "showInIndex": true, "adminOnly": 0 },
                { "key": "tech", "cn_text": "技术", "customizable": true, "showInIndex": 1, "adminOnly": false }
              ],
              "postData": {
                "postId": 888,
                "postPage": 1,
                "postPageCount": 3,
                "title": "VPS 测评与记录",
                "category": "review",
                "categoryWord": "测评",
                "views": "2,405",
                "collectionCount": 9,
                "rank": 0,
                "locked": 0,
                "collected": 1,
                "award": false,
                "op": { "uid": 101, "name": "NodeExplorer", "avatar": "/avatar/101.png" },
                "comments": [
                  {
                    "commentId": 5001,
                    "floorIndex": 0,
                    "hot": 0,
                    "pined": 0,
                    "poster": {
                      "uid": 101,
                      "name": "NodeExplorer",
                      "isOp": true,
                      "roles": [{ "name": "admin", "display_text": "管理" }]
                    },
                    "time": { "createdDate": "2026-10-15T12:00:00.000Z" },
                    "markdown": "## 正文标题\n内容测试",
                    "likeCount": 12,
                    "upvoteCount": 4,
                    "dislikeCount": 0,
                    "liked": 1,
                    "blocked": false
                  },
                  {
                    "commentId": 5002,
                    "floorIndex": 1,
                    "hot": 1,
                    "pined": 1,
                    "poster": { "uid": 202, "name": "Reviewer" },
                    "markdown": "沙发置顶评论",
                    "blocked": 0
                  }
                ]
              }
            }
        """.trimIndent()

        val encoded = Base64.getEncoder().encodeToString(rawConfigJson.toByteArray(Charsets.UTF_8))
        val html = """
            <!doctype html>
            <html>
              <body>
                <div id="nsk-body"></div>
                <script id="temp-script" type="application/json">$encoded</script>
              </body>
            </html>
        """.trimIndent()

        val config: ConfigPayloadDto? = TempScriptParser.extractFromHtml(html)
        assertNotNull(config)
        val user = config!!.user!!
        assertEquals(101L, user.memberId)
        assertEquals("NodeExplorer", user.memberName)
        assertEquals(1650, user.coin)
        assertEquals(4, user.displayLevel) // 1600 <= 1650 < 2500 -> Lv4
        assertTrue(user.isTelegramBound)
        assertEquals("987654321", user.telegramId)
        assertEquals(5, user.unViewedCount?.all)
        assertTrue(config.canCustomizeStyle)

        val post = config.postData!!
        assertEquals(888L, post.postId)
        assertEquals(2405, post.views)
        assertEquals(false, post.locked)
        assertEquals(true, post.collected)
        assertEquals(2, post.comments.size)

        val opComment = post.comments[0]
        assertEquals("管理", opComment.poster.roles.single().displayText)
        assertEquals(true, opComment.liked)
        assertNull(opComment.upvoted) // Missing WebsiteFlag stays null (unknown)

        val reply = post.comments[1]
        assertEquals(true, reply.pined)
        assertEquals(true, reply.hot)
    }

    @Test(expected = SerializationException::class)
    fun `WebsiteFlagSerializer rejects out-of-range integers`() {
        val badJson = """
            {
              "allCategory": [
                { "key": "daily", "cn_text": "日常", "adminOnly": 2 }
              ]
            }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(badJson.toByteArray(Charsets.UTF_8))
        TempScriptParser.decodeBase64Config(encoded)
    }

    @Test
    fun `displayLevel follows 100 times level squared thresholds up to Lv6`() {
        fun levelForCoins(coins: Int): Int {
            val raw = """{"user":{"member_id":1,"member_name":"u","coin":$coins},"allCategory":[]}"""
            val b64 = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
            return TempScriptParser.decodeBase64Config(b64).user!!.displayLevel
        }

        assertEquals(0, levelForCoins(0))
        assertEquals(0, levelForCoins(99))
        assertEquals(1, levelForCoins(100))
        assertEquals(1, levelForCoins(399))
        assertEquals(2, levelForCoins(400))
        assertEquals(3, levelForCoins(900))
        assertEquals(4, levelForCoins(1600))
        assertEquals(5, levelForCoins(2500))
        assertEquals(6, levelForCoins(3600))
        assertEquals(6, levelForCoins(50000))
    }

    @Test
    fun `unViewedCount supports plural keys and uses maxOf all vs category sum`() {
        val raw = """
            {
              "user": {
                "member_id": 101,
                "member_name": "Tester",
                "coin": 200,
                "unViewedCount": {
                  "all": 1,
                  "replys": 1,
                  "atMe": 1,
                  "messages": 2,
                  "sys": 1
                }
              },
              "allCategory": []
            }
        """.trimIndent()
        val b64 = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        val parsed = TempScriptParser.decodeBase64Config(b64)
        assertEquals(5, parsed.user?.unViewedCount?.effectiveAll)
    }
}
