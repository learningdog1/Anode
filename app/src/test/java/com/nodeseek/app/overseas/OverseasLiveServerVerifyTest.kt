package com.nodeseek.app.overseas

import com.nodeseek.app.data.overseas.OverseasIntelApiClient
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end live verification against the production LowEndTalk Promotion Monitor API
 * using the obfuscated vault credentials, pinned self-signed TLS certificate, HMAC-SHA256
 * request signing, and AES-256-GCM response decryption.
 *
 * Run explicitly with:
 * `RUN_LIVE_OVERSEAS_VERIFY=1 ./gradlew :app:testDebugUnitTest --tests com.nodeseek.app.overseas.OverseasLiveServerVerifyTest --info`
 */
class OverseasLiveServerVerifyTest {

    @Test
    fun verifiesLiveHealthListDetailAndRepostEndpointsEndToEnd() = runTest {
        assumeTrue(
            "Set RUN_LIVE_OVERSEAS_VERIFY=1 to execute live network verification",
            System.getenv("RUN_LIVE_OVERSEAS_VERIFY") == "1" ||
                System.getProperty("runLiveOverseasVerify") == "true",
        )

        val client = OverseasIntelApiClient()

        val healthy = client.checkHealth()
        assertTrue("Expected /healthz to return ok", healthy)

        val page = client.fetchOffersPage(page = 1, pageSize = 5)
        assertTrue("Expected non-empty offers list from live API", page.items.isNotEmpty())
        println("[LIVE-VERIFY] /api/v1/offers -> total=${page.total}, hasMore=${page.hasMore}, firstId=${page.items.first().id}, firstTitle=${page.items.first().title}")

        val detailId = if (page.items.any { it.id == "223518" }) "223518" else page.items.first().id
        val detail = client.fetchOfferDetail(detailId)
        assertTrue("Expected non-blank tgMarkdown from detail endpoint", detail.tgMarkdown.isNotBlank())
        println("[LIVE-VERIFY] /api/v1/offers/$detailId -> title=${detail.title}, markdownLen=${detail.tgMarkdown.length}")

        val repostId = "223558"
        val repost = client.fetchOfferRepost(repostId)
        assertTrue("Expected non-blank repost bodyMarkdown", repost.bodyMarkdown.isNotBlank())
        val formattedPost = OverseasForumPostFormatter.format(repost)
        println("[LIVE-VERIFY] /api/v1/offers/$repostId/repost -> postTitle=${formattedPost.title}, images=${repost.images.size}, links=${repost.links.size}, bodyLen=${formattedPost.markdownBody.length}")
        assertTrue(formattedPost.title.startsWith("[海外情报/LET]"))
    }
}
