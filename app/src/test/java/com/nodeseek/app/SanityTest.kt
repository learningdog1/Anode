package com.nodeseek.app

import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.navigation.DeepLinkParser
import com.nodeseek.app.navigation.NodePulseDeepLink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SanityTest {

    @Serializable
    private data class PulsePayload(val app: String, val ok: Boolean)

    @Test
    fun coreDependenciesWorkTogether() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="payload">{"app":"NodePulse","ok":true}</div></body></html>""")
        )
        server.start()
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(server.url("/health")).build()
            val html = client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                response.body?.string().orEmpty()
            }

            val rawJson = Jsoup.parse(html).selectFirst("#payload")?.text().orEmpty()
            val parsed = Json.decodeFromString<PulsePayload>(rawJson)
            assertEquals("NodePulse", parsed.app)
            assertEquals(true, parsed.ok)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun deepLinkParserResolvesTopicCategoryAndUserSpaceUrls() {
        assertEquals(
            NodePulseDeepLink.TopicDetail(topicId = 285109L, initialFloor = 0),
            DeepLinkParser.parseUrl("https://www.nodeseek.com/post-285109-1"),
        )
        val subPageLink = DeepLinkParser.parseUrl("https://nodeseek.com/post-285109-3")
        assertEquals(
            NodePulseDeepLink.TopicDetail(topicId = 285109L, initialFloor = 21),
            subPageLink,
        )
        assertEquals(3, TopicDetailPage.pageForFloor(21))

        assertEquals(
            NodePulseDeepLink.TopicDetail(topicId = 285109L, initialFloor = 27),
            DeepLinkParser.parseUrl("https://www.nodeseek.com/post-285109-3#27"),
        )
        assertEquals(
            NodePulseDeepLink.CategoryBoard(slug = "tech"),
            DeepLinkParser.parseUrl("https://www.nodeseek.com/categories/tech?sortBy=postTime"),
        )
        assertEquals(
            NodePulseDeepLink.CategoryBoard(slug = ""),
            DeepLinkParser.parseUrl("http://nodeseek.com/categories/all"),
        )
        assertEquals(
            NodePulseDeepLink.UserSpace(uid = 10086L),
            DeepLinkParser.parseUrl("https://www.nodeseek.com/space/10086"),
        )
        assertEquals(
            NodePulseDeepLink.TopicDetail(topicId = 99123L, initialFloor = 8),
            DeepLinkParser.parseUrl("https://www.nodeseek.com/jump?to=https%3A%2F%2Fwww.nodeseek.com%2Fpost-99123-1%238"),
        )
        assertEquals(
            NodePulseDeepLink.CheckInBoard,
            DeepLinkParser.parseUrl("https://www.nodeseek.com/board"),
        )
        assertEquals(
            NodePulseDeepLink.Inbox,
            DeepLinkParser.parseUrl("https://www.nodeseek.com/notification"),
        )
        assertNull(DeepLinkParser.parseUrl("https://example.com/post-285109-1"))
    }
}
