package com.nodeseek.app.activity

import com.nodeseek.app.data.activity.ForumWatchActivityApiClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end live verification against the production ForumWatch Activity API
 * using the obfuscated vault credentials, pinned X.509 certificate + SPKI pin, HMAC-SHA256
 * request signing, AES-256-GCM response decryption, and ETag `304 Not Modified` caching.
 *
 * Run explicitly with:
 * `RUN_LIVE_ACTIVITY_VERIFY=1 ./gradlew :app:testDebugUnitTest --tests com.nodeseek.app.activity.ForumWatchLiveServerVerifyTest --info`
 */
class ForumWatchLiveServerVerifyTest {

    @Test
    fun verifiesLiveHealthEncryptedActivitiesFeedAndConditionalEtag304() = runTest {
        assumeTrue(
            "Set RUN_LIVE_ACTIVITY_VERIFY=1 to execute live ForumWatch network verification",
            System.getenv("RUN_LIVE_ACTIVITY_VERIFY") == "1" ||
                System.getProperty("runLiveActivityVerify") == "true",
        )

        val client = ForumWatchActivityApiClient()

        val healthy = client.checkHealth()
        assertTrue("Expected /healthz to return ok", healthy)

        val firstCond = client.fetchActivitiesPageConditional(page = 1, pageSize = 20)
        val page = checkNotNull(firstCond.data) { "Expected decrypted page data on initial 200 response" }
        assertNotNull("Expected ETag header on /api/v1/activities response", firstCond.etag)
        println(
            "[LIVE-ACTIVITY-VERIFY] /api/v1/activities -> total=${page.total}, counts=${page.counts}, etag=${firstCond.etag}, items=${page.items.size}",
        )

        val secondCond = client.fetchActivitiesPageConditional(
            page = 1,
            pageSize = 20,
            ifNoneMatch = firstCond.etag,
        )
        assertTrue(
            "Expected 304 Not Modified on immediate conditional poll with ETag ${firstCond.etag}",
            secondCond.notModified,
        )
        println("[LIVE-ACTIVITY-VERIFY] Conditional If-None-Match=${firstCond.etag} -> 304 Not Modified verified!")

        if (page.items.isNotEmpty()) {
            val firstId = page.items.first().id
            val detail = client.fetchActivityDetail(firstId)
            assertTrue(detail.item.id.isNotBlank())
            println("[LIVE-ACTIVITY-VERIFY] /api/v1/activities/$firstId -> title=${detail.item.title}, markdownLen=${detail.bodyMarkdown.length}")
        }
    }
}
