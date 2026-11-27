package com.nodeseek.app.data.network

import com.nodeseek.app.domain.model.CookieHostScope
import com.nodeseek.app.domain.model.StoredCookieRecord
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebViewCookieBridgeAccountSwitchTest {

    private val wwwUrl = "https://www.nodeseek.com/".toHttpUrl()
    private val bareUrl = "https://nodeseek.com/".toHttpUrl()

    @Test
    fun `exportAccountCookies classifies shared, www-only, and bare-only cookies while stripping cf and sortBy`() {
        val bridge = WebViewCookieBridge(forceInMemory = true)
        val futureExpiry = System.currentTimeMillis() + 3_600_000L

        bridge.saveFromResponse(
            wwwUrl,
            listOf(
                Cookie.Builder()
                    .name("session")
                    .value("shared_sess_val")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
                Cookie.Builder()
                    .name("smac")
                    .value("www_only_smac")
                    .hostOnlyDomain("www.nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
                Cookie.Builder()
                    .name("cf_clearance")
                    .value("live_cf_clearance_token")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
                Cookie.Builder()
                    .name("__cf_bm")
                    .value("telemetry_bm")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
                Cookie.Builder()
                    .name("_cfuvid")
                    .value("telemetry_uvid")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
                Cookie.Builder()
                    .name("cf_chl_2")
                    .value("challenge_tok")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
                Cookie.Builder()
                    .name("colorscheme")
                    .value("dark")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
                Cookie.Builder()
                    .name("sortBy")
                    .value("postTime")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
            ),
        )
        bridge.saveFromResponse(
            bareUrl,
            listOf(
                Cookie.Builder()
                    .name("bare_token")
                    .value("bare_only_val")
                    .hostOnlyDomain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
            ),
        )

        val exported = bridge.exportAccountCookies()
        assertEquals(3, exported.size)
        assertTrue(
            exported.contains(
                StoredCookieRecord(
                    name = "session",
                    value = "shared_sess_val",
                    scope = CookieHostScope.SHARED_DOT_DOMAIN,
                ),
            ),
        )
        assertTrue(
            exported.contains(
                StoredCookieRecord(
                    name = "smac",
                    value = "www_only_smac",
                    scope = CookieHostScope.WWW_ONLY,
                ),
            ),
        )
        assertTrue(
            exported.contains(
                StoredCookieRecord(
                    name = "bare_token",
                    value = "bare_only_val",
                    scope = CookieHostScope.BARE_ONLY,
                ),
            ),
        )
        assertFalse(exported.any { it.name.equals("cf_clearance", ignoreCase = true) })
        assertFalse(exported.any { it.name.equals("sortBy", ignoreCase = true) })
        assertFalse(exported.any { it.name.startsWith("__cf") || it.name.startsWith("_cf") || it.name.startsWith("cf_") })
        assertFalse(exported.any { it.name == "colorscheme" })
    }

    @Test
    fun `clearSessionCookies preserves live cf_clearance while clearing auth cookies and restoreAccountCookies respects host scope`() {
        val bridge = WebViewCookieBridge(forceInMemory = true)
        val futureExpiry = System.currentTimeMillis() + 3_600_000L

        bridge.saveFromResponse(
            wwwUrl,
            listOf(
                Cookie.Builder()
                    .name("session")
                    .value("account_a_session")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
                Cookie.Builder()
                    .name("cf_clearance")
                    .value("live_clearance_keep_me")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(futureExpiry)
                    .build(),
            ),
        )

        assertTrue(bridge.hasAnyAuthCookie())
        assertTrue(bridge.hasClearanceCookie())

        bridge.clearSessionCookies(includeClearance = false)

        assertFalse(bridge.hasAnyAuthCookie())
        assertTrue(bridge.hasClearanceCookie())
        assertEquals(
            "live_clearance_keep_me",
            bridge.loadForRequest(wwwUrl).first { it.name == "cf_clearance" }.value,
        )

        val accountBRecords = listOf(
            StoredCookieRecord("session", "account_b_session", CookieHostScope.SHARED_DOT_DOMAIN),
            StoredCookieRecord("www_pref", "w1", CookieHostScope.WWW_ONLY),
            StoredCookieRecord("bare_pref", "b1", CookieHostScope.BARE_ONLY),
            // Defensive check: even if stale cf_clearance or sortBy were present, restore skips them
            StoredCookieRecord("cf_clearance", "stale_clearance_ignore", CookieHostScope.SHARED_DOT_DOMAIN),
            StoredCookieRecord("sortBy", "postTime", CookieHostScope.SHARED_DOT_DOMAIN),
        )

        bridge.restoreAccountCookies(accountBRecords)

        val wwwLoaded = bridge.loadForRequest(wwwUrl).associate { it.name to it.value }
        val bareLoaded = bridge.loadForRequest(bareUrl).associate { it.name to it.value }

        assertEquals("account_b_session", wwwLoaded["session"])
        assertEquals("account_b_session", bareLoaded["session"])
        assertEquals("w1", wwwLoaded["www_pref"])
        assertFalse(bareLoaded.containsKey("www_pref"))
        assertEquals("b1", bareLoaded["bare_pref"])
        assertFalse(wwwLoaded.containsKey("bare_pref"))
        assertEquals("live_clearance_keep_me", wwwLoaded["cf_clearance"])
        assertFalse(wwwLoaded.containsKey("sortBy"))
    }

    @Test
    fun `concurrent in-memory cookie operations across 8 threads complete with zero exceptions`() {
        val bridge = WebViewCookieBridge(forceInMemory = true)
        val threadCount = 8
        val iterationsPerThread = 250
        val pool = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val errors = CopyOnWriteArrayList<Throwable>()

        try {
            repeat(threadCount) { threadIdx ->
                pool.submit {
                    try {
                        startGate.await(5, TimeUnit.SECONDS)
                        repeat(iterationsPerThread) { iter ->
                            val futureExpiry = System.currentTimeMillis() + 3_600_000L
                            when ((threadIdx + iter) % 5) {
                                0 -> bridge.saveFromResponse(
                                    wwwUrl,
                                    listOf(
                                        Cookie.Builder()
                                            .name("session")
                                            .value("sess_${threadIdx}_$iter")
                                            .domain("nodeseek.com")
                                            .path("/")
                                            .secure()
                                            .expiresAt(futureExpiry)
                                            .build(),
                                        Cookie.Builder()
                                            .name("cf_clearance")
                                            .value("cf_${threadIdx}_$iter")
                                            .domain("nodeseek.com")
                                            .path("/")
                                            .secure()
                                            .expiresAt(futureExpiry)
                                            .build(),
                                        Cookie.Builder()
                                            .name("sortBy")
                                            .value("replyTime")
                                            .domain("nodeseek.com")
                                            .path("/")
                                            .secure()
                                            .expiresAt(futureExpiry)
                                            .build(),
                                    ),
                                )
                                1 -> {
                                    bridge.loadForRequest(wwwUrl)
                                    bridge.loadForRequest(bareUrl)
                                    bridge.hasClearanceCookie(wwwUrl)
                                    bridge.hasAnyAuthCookie()
                                }
                                2 -> bridge.exportAccountCookies()
                                3 -> bridge.restoreAccountCookies(
                                    listOf(
                                        StoredCookieRecord("session", "restored_${threadIdx}_$iter", CookieHostScope.SHARED_DOT_DOMAIN),
                                        StoredCookieRecord("smac", "www_${threadIdx}_$iter", CookieHostScope.WWW_ONLY),
                                        StoredCookieRecord("bare_tok", "bare_${threadIdx}_$iter", CookieHostScope.BARE_ONLY),
                                    ),
                                )
                                4 -> bridge.clearSessionCookies(includeClearance = false)
                            }
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        doneGate.countDown()
                    }
                }
            }

            startGate.countDown()
            assertTrue("Concurrent stress test timed out", doneGate.await(30, TimeUnit.SECONDS))
            assertTrue("Expected zero exceptions, got: $errors", errors.isEmpty())
        } finally {
            pool.shutdownNow()
        }
    }
}
