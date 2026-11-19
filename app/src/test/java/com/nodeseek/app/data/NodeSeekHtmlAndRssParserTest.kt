package com.nodeseek.app.data

import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.NodeSeekPostAccessException
import com.nodeseek.app.data.parser.RssFeedParser
import com.nodeseek.app.data.repository.toDomainDetailPage
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NodeSeekHtmlAndRssParserTest {

    private fun encodeConfig(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `parseFeedPage merges rotateTopics and pinned rows, deduplicates, filters blocked, and detects pagination`() {
        val configB64 = encodeConfig(
            """
            {
              "allCategory": [
                { "key": "tech", "cn_text": "技术" },
                { "key": "daily", "cn_text": "日常" }
              ],
              "rotateTopics": [
                {
                  "postId": 100,
                  "titleText": "轮播置顶公告",
                  "category": "daily",
                  "categoryWord": "日常",
                  "op": { "uid": 1, "name": "Admin", "avatar": "/avatar/1.png" },
                  "views": 9800,
                  "comments": 45,
                  "lastCommentDate": "2026-10-15T10:00:00.000Z",
                  "rank": 0,
                  "blocked": false
                }
              ]
            }
            """.trimIndent(),
        )

        val feedHtml = """
            <!doctype html>
            <html>
              <body>
                <div id="nsk-body">
                  <!-- Duplicate of carousel topic 100 in normal DOM should be skipped -->
                  <div class="post-list-item">
                    <img class="avatar-normal" src="/avatar/1.png" />
                    <div class="post-title"><a href="/post-100-1">轮播置顶公告</a></div>
                  </div>

                  <!-- Pinned row in list -->
                  <div class="post-list-item">
                    <img class="avatar-normal" src="/avatar/2.png" />
                    <div class="post-title">
                      <span title="置顶">置顶</span>
                      <a href="/post-101-1">版块置顶指南</a>
                    </div>
                    <a class="post-category" href="/categories/tech">技术</a>
                    <span class="info-author"><a href="/space/2">ModUser</a></span>
                    <span class="info-views"><span>1,250</span></span>
                    <span class="info-comments-count"><span>32</span></span>
                    <time datetime="2026-10-15T09:00:00.000Z"></time>
                  </div>

                  <!-- Regular topic with Lv lock -->
                  <div class="post-list-item">
                    <img class="avatar-normal" src="/avatar/55.png" />
                    <div class="post-title">
                      <a href="/post-205-1">高性能新加坡节点测评</a>
                      <span><svg><use href="#lock"></use></svg>Lv3</span>
                    </div>
                    <a class="post-category" href="/categories/tech"></a>
                    <span class="info-author"><a href="/space/55">BenchTester</a></span>
                    <span class="info-views"><span>540</span></span>
                    <span class="info-comments-count"><span>19</span></span>
                    <time datetime="2026-10-15T08:30:00.000Z"></time>
                  </div>

                  <!-- Blocked topic -->
                  <div class="post-list-item blocked-post">
                    <div class="post-title"><a href="/post-999-1">被屏蔽的帖子</a></div>
                  </div>

                  <nav aria-label="pagination">
                    <a href="/page-1?sortBy=replyTime">1</a>
                    <a href="/page-2?sortBy=replyTime">2</a>
                  </nav>
                </div>
                <script id="temp-script" type="application/json">$configB64</script>
              </body>
            </html>
        """.trimIndent()

        val result = NodeSeekHtmlParser.parseFeedPage(
            html = feedHtml,
            pageUrl = "https://www.nodeseek.com/?sortBy=replyTime",
            includeBlocked = false,
        )

        assertEquals(1, result.currentPage)
        assertTrue(result.hasNextPage)
        assertFalse(result.isFromRssFallback)

        // Pinned strip: 100 (carousel) followed by 101 ([title="置顶"])
        assertEquals(listOf(100L, 101L), result.pinnedTopics.map { it.id })
        assertEquals("https://www.nodeseek.com/avatar/1.png", result.pinnedTopics[0].avatarUrl)

        // Normal topics: only 205 (100 & 101 are pinned, 999 is blocked)
        assertEquals(1, result.topics.size)
        val regular = result.topics.single()
        assertEquals(205L, regular.id)
        assertEquals("高性能新加坡节点测评", regular.title)
        assertEquals("tech", regular.categoryKey)
        assertEquals("技术", regular.categoryName) // Fallback from config.allCategory
        assertEquals(55L, regular.authorId)
        assertEquals("BenchTester", regular.author)
        assertEquals(540, regular.views)
        assertEquals(19, regular.replies)
        assertEquals(3, regular.requiredRank)

        // When includeBlocked = true, topic 999 appears
        val withBlocked = NodeSeekHtmlParser.parseFeedPage(
            html = feedHtml,
            pageUrl = "https://www.nodeseek.com/?sortBy=replyTime",
            includeBlocked = true,
        )
        assertEquals(listOf(205L, 999L), withBlocked.topics.map { it.id })
    }

    @Test
    fun `parseThreadPage orders replies as pined then hot then chronological and removes blocked`() {
        val threadConfigB64 = encodeConfig(
            """
            {
              "allCategory": [{ "key": "dev", "cn_text": "Dev" }],
              "postData": {
                "postId": 321,
                "postPage": 1,
                "postPageCount": 2,
                "title": "NodePulse 架构讨论",
                "category": "dev",
                "categoryWord": "Dev",
                "views": 300,
                "comments": [
                  { "commentId": 10, "floorIndex": 0, "poster": { "uid": 1, "name": "OP" }, "markdown": "一楼正文" },
                  { "commentId": 11, "floorIndex": 1, "poster": { "uid": 2, "name": "UserA" }, "markdown": "普通一楼回复" },
                  { "commentId": 12, "floorIndex": 2, "hot": 1, "poster": { "uid": 3, "name": "UserB" }, "markdown": "热评二楼" },
                  { "commentId": 13, "floorIndex": 3, "pined": 1, "poster": { "uid": 4, "name": "UserC" }, "markdown": "楼主置顶三楼" },
                  { "commentId": 14, "floorIndex": 4, "blocked": true, "poster": { "uid": 5, "name": "Spam" }, "markdown": "屏蔽楼层" }
                ]
              }
            }
            """.trimIndent(),
        )

        val html = """
            <html><body>
              <div id="nsk-body"><div class="nsk-post"></div></div>
              <script id="temp-script" type="application/json">$threadConfigB64</script>
            </body></html>
        """.trimIndent()

        val parsed = NodeSeekHtmlParser.parseThreadPage(html, "https://www.nodeseek.com/post-321-1")
        assertEquals(10L, parsed.openingComment?.commentId)
        assertEquals(listOf(13L, 12L, 11L), parsed.replies.map { it.commentId })
    }

    @Test
    fun `detectPostAccessFailure classifies private, level-locked, and sign-in-required pages`() {
        val privateDoc = Jsoup.parse(
            """<div id="nsk-body-left"><div class="notice">查看本帖需要 Lv 255</div></div>""",
        )
        assertEquals(
            NodeSeekPostAccessException.PrivatePost(),
            NodeSeekHtmlParser.detectPostAccessFailure(privateDoc),
        )

        val levelDoc = Jsoup.parse(
            """<div id="nsk-body-left"><div class="notice">权限不足，查看本帖需要 Lv 4</div></div>""",
        )
        assertEquals(
            NodeSeekPostAccessException.LevelRequired(4),
            NodeSeekHtmlParser.detectPostAccessFailure(levelDoc),
        )

        val signInDoc = Jsoup.parse(
            """<div id="nsk-body"><div class="notice">需要注册用户才能查看该主题</div></div>""",
        )
        assertEquals(
            NodeSeekPostAccessException.SignInRequired(),
            NodeSeekHtmlParser.detectPostAccessFailure(signInDoc),
        )

        val normalPostDoc = Jsoup.parse(
            """<div id="nsk-body-left"><article class="post-content">需要注册用户才能查看（正文引用文字不应误判）</article></div>""",
        )
        assertNull(NodeSeekHtmlParser.detectPostAccessFailure(normalPostDoc))
    }

    @Test
    fun `cleanCommentHtmlToExcerpt strips scripts and styles and preserves image alt placeholders`() {
        val rawHtml = """
            <style>.hidden { display: none }</style>
            <script>alert(1)</script>
            <p>第一段内容<img src="/a.png" alt="[AC娘]" />继续</p>
            <div>第二段<img src="/b.png" />结束</div>
        """.trimIndent()

        val excerpt = NodeSeekHtmlParser.cleanCommentHtmlToExcerpt(rawHtml)
        assertEquals("第一段内容[AC娘]继续 第二段[图片]结束", excerpt)
    }

    @Test
    fun `RssFeedParser parses RSS 2_0 items, resolves board slugs, and normalizes RFC-1123 dates`() {
        val rssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>NodeSeek RSS</title>
                <item>
                  <title><![CDATA[法兰克福 CN2 GIA 线路晚高峰实测]]></title>
                  <link>https://www.nodeseek.com/post-45678-1</link>
                  <guid>https://www.nodeseek.com/post-45678-1</guid>
                  <pubDate>Thu, 15 Oct 2026 06:30:00 GMT</pubDate>
                  <dc:creator><![CDATA[NetRunner]]></dc:creator>
                  <category><![CDATA[测评]]></category>
                  <description><![CDATA[<p>晚高峰零丢包 <img src="https://img.example.com/chart.png" /></p>]]></description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = RssFeedParser.parse(rssXml)
        assertTrue(parsed.isFromRssFallback)
        assertFalse(parsed.hasNextPage)
        assertEquals(1, parsed.topics.size)

        val item = parsed.topics.single()
        assertEquals(45678L, item.id)
        assertEquals("法兰克福 CN2 GIA 线路晚高峰实测", item.title)
        assertEquals("NetRunner", item.author)
        assertEquals("review", item.categoryKey)
        assertEquals("测评", item.categoryName)
        assertEquals("2026-10-15T06:30:00Z", item.activityDate)
        assertEquals("晚高峰零丢包 [图片]", item.excerpt)
    }

    @Test
    fun `htmlFragmentToMarkdown converts linked badge images, relative URLs, line breaks, and preserves plain markdown`() {
        val rawHtml = """<a href="https://example.com"><img src="/static/badge.svg" alt="Probe" /></a><br><strong>签名</strong>"""
        assertEquals(
            "[![Probe](https://www.nodeseek.com/static/badge.svg)](https://example.com)\n**签名**",
            NodeSeekHtmlParser.htmlFragmentToMarkdown(rawHtml),
        )

        val plainMarkdown = "[![A](https://example.com/a.svg)](https://example.com)"
        assertEquals(plainMarkdown, NodeSeekHtmlParser.htmlFragmentToMarkdown(plainMarkdown))
    }

    @Test
    fun `parseThreadPage and toDomainDetailPage resolve structured poster signature and DOM fallback signature`() {
        val threadConfigB64 = encodeConfig(
            """
            {
              "allCategory": [{ "key": "tech", "cn_text": "技术" }],
              "postData": {
                "postId": 500,
                "postPage": 1,
                "postPageCount": 1,
                "title": "签名档渲染测试帖",
                "category": "tech",
                "categoryWord": "技术",
                "views": 120,
                "comments": [
                  {
                    "commentId": 10,
                    "floorIndex": 0,
                    "poster": {
                      "uid": 1,
                      "name": "OP",
                      "signature": "[个人探针](https://probe.example.com)"
                    },
                    "markdown": "主楼正文"
                  },
                  {
                    "commentId": 11,
                    "floorIndex": 1,
                    "poster": { "uid": 2, "name": "ReplyUser" },
                    "markdown": "一楼回复"
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val html = """
            <html><body>
              <div id="nsk-body">
                <div class="nsk-post"></div>
                <div id="1" class="content-item">
                  <div class="signature"><a href="https://status.example.com"><img src="/sig.svg" alt="Sig" /></a></div>
                </div>
              </div>
              <script id="temp-script" type="application/json">$threadConfigB64</script>
            </body></html>
        """.trimIndent()

        val domainPage = NodeSeekHtmlParser
            .parseThreadPage(html, "https://www.nodeseek.com/post-500-1")
            .toDomainDetailPage()

        assertEquals("[个人探针](https://probe.example.com)", domainPage.openingFloor?.signatureMarkdown)
        assertEquals(1, domainPage.replies.size)
        assertEquals(
            "[![Sig](https://www.nodeseek.com/sig.svg)](https://status.example.com)",
            domainPage.replies.single().signatureMarkdown,
        )
    }

    @Test
    fun `toDomainDetailPage normalizes h2 and html tags inside structured signature and signatureMarkdown`() {
        val threadConfigB64 = encodeConfig(
            """
            {
              "allCategory": [{ "key": "daily", "cn_text": "日常" }],
              "postData": {
                "postId": 501,
                "postPage": 1,
                "postPageCount": 1,
                "title": "签名档 H2 HTML 转换测试",
                "category": "daily",
                "categoryWord": "日常",
                "views": 88,
                "comments": [
                  {
                    "commentId": 20,
                    "floorIndex": 0,
                    "signature": "<h2>出闲置小鸡</h2><p><a href=\"https://example.com\">探针面板</a></p>",
                    "poster": { "uid": 1, "name": "OP" },
                    "markdown": "主楼正文"
                  },
                  {
                    "commentId": 21,
                    "floorIndex": 1,
                    "signatureMarkdown": "<h2>我的签名标题</h2>\n**长期收鸡** [TG联系](https://t.me/example)",
                    "poster": { "uid": 2, "name": "User2" },
                    "markdown": "一楼正文"
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val html = """
            <html><body>
              <div id="nsk-body"><div class="nsk-post"></div></div>
              <script id="temp-script" type="application/json">$threadConfigB64</script>
            </body></html>
        """.trimIndent()

        val domainPage = NodeSeekHtmlParser
            .parseThreadPage(html, "https://www.nodeseek.com/post-501-1")
            .toDomainDetailPage()

        val opSig = domainPage.openingFloor?.signatureMarkdown.orEmpty()
        assertFalse("OP signature must not contain raw <h2> tags: $opSig", opSig.contains("<h2", ignoreCase = true))
        assertEquals("## 出闲置小鸡\n[探针面板](https://example.com)", opSig)

        val replySig = domainPage.replies.single().signatureMarkdown.orEmpty()
        assertFalse("Reply signature must not contain raw <h2> tags: $replySig", replySig.contains("<h2", ignoreCase = true))
        assertTrue(replySig.contains("## 我的签名标题"))
        assertTrue(replySig.contains("**长期收鸡** [TG联系](https://t.me/example)"))
    }

    @Test
    fun `parseFeedPage detects nsk-pager, rel=next, and ignores disabled or hash-only pager-next anchors`() {
        val configB64 = encodeConfig("""{ "allCategory": [{ "key": "review", "cn_text": "测评" }] }""")

        fun wrapHtml(pagerHtml: String): String = """
            <!doctype html>
            <html><body>
              <div id="nsk-body">
                <div class="post-list-item">
                  <div class="post-title"><a href="/post-301-1">测评贴</a></div>
                  <a class="post-category" href="/categories/review">测评</a>
                </div>
                $pagerHtml
              </div>
              <script id="temp-script" type="application/json">$configB64</script>
            </body></html>
        """.trimIndent()

        // 1. .nsk-pager numbered link to page 2
        val nskPagerResult = NodeSeekHtmlParser.parseFeedPage(
            html = wrapHtml("""<div class="nsk-pager"><a href="/categories/review/page-1">1</a><a href="/categories/review/page-2">2</a></div>"""),
            pageUrl = "https://www.nodeseek.com/categories/review",
        )
        assertTrue(nskPagerResult.hasNextPage)

        // 2. Enabled a.pager-next with href
        val pagerNextResult = NodeSeekHtmlParser.parseFeedPage(
            html = wrapHtml("""<div class="nsk-pager"><a class="pager-next" href="/categories/review/page-2">下一页</a></div>"""),
            pageUrl = "https://www.nodeseek.com/categories/review",
        )
        assertTrue(pagerNextResult.hasNextPage)

        // 3. Disabled or hash-only a.pager-next should NOT count as having a next page
        val disabledNextResult = NodeSeekHtmlParser.parseFeedPage(
            html = wrapHtml("""<div class="nsk-pager"><a class="pager-next disabled" href="/categories/review/page-2">下一页</a></div>"""),
            pageUrl = "https://www.nodeseek.com/categories/review",
        )
        assertFalse(disabledNextResult.hasNextPage)

        val hashNextResult = NodeSeekHtmlParser.parseFeedPage(
            html = wrapHtml("""<div class="nsk-pager"><a class="pager-next" href="#">下一页</a></div>"""),
            pageUrl = "https://www.nodeseek.com/categories/review",
        )
        assertFalse(hashNextResult.hasNextPage)

        val noHrefNextResult = NodeSeekHtmlParser.parseFeedPage(
            html = wrapHtml("""<div class="nsk-pager"><a class="pager-next">下一页</a></div>"""),
            pageUrl = "https://www.nodeseek.com/categories/review",
        )
        assertFalse(noHrefNextResult.hasNextPage)

        // 4. rel="next" anchor
        val relNextResult = NodeSeekHtmlParser.parseFeedPage(
            html = wrapHtml("""<nav><a rel="next" href="/categories/review/page-2">Next</a></nav>"""),
            pageUrl = "https://www.nodeseek.com/categories/review",
        )
        assertTrue(relNextResult.hasNextPage)
    }
}
