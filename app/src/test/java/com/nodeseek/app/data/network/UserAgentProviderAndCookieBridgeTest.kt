package com.nodeseek.app.data.network

import android.content.Context
import android.content.ContextWrapper
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UserAgentProviderAndCookieBridgeTest {

    private fun fakeContext(): Context = ContextWrapper(null)

    @Test
    fun `fallback UA without WebView synthesizes sanitized Build info or defaults cleanly`() {
        assertEquals(
            UserAgentProvider.DEFAULT_ANDROID_USER_AGENT,
            UserAgentProvider.buildFallbackUserAgent(androidRelease = null, deviceModel = null),
        )
        assertEquals(
            UserAgentProvider.DEFAULT_ANDROID_USER_AGENT,
            UserAgentProvider.buildFallbackUserAgent(androidRelease = "   ", deviceModel = ";()"),
        )
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            UserAgentProvider.buildFallbackUserAgent(
                androidRelease = "16\r\n",
                deviceModel = "Pixel (9); Pro",
            ),
        )

        val provider = UserAgentProvider()
        assertEquals(UserAgentProvider.DEFAULT_ANDROID_USER_AGENT, provider.getUserAgent())
        assertFalse(provider.isSystemWebViewDefault)
    }

    @Test
    fun `background init never calls WebSettings and upgrades from persisted then main idle refresh`() {
        val onMainThread = AtomicBoolean(false)
        val systemUaCalls = AtomicInteger(0)
        val persistedWrites = CopyOnWriteArrayList<String>()
        val scheduledIdleTask = AtomicReference<(() -> Unit)?>(null)
        val ioTasks = CopyOnWriteArrayList<() -> Unit>()

        val persistedUa = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36"
        val liveUa = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 Chrome/145.0.0.0 Mobile Safari/537.36"

        val provider = UserAgentProvider(
            initialOverride = null,
            isMainThread = { onMainThread.get() },
            systemUaFetcher = {
                assertTrue("WebSettings.getDefaultUserAgent must only run on main thread", onMainThread.get())
                systemUaCalls.incrementAndGet()
                liveUa
            },
            persistedUaReader = { persistedUa },
            persistedUaWriter = { _, ua -> persistedWrites.add(ua) },
            mainIdleScheduler = { task ->
                scheduledIdleTask.set(task)
                true
            },
            ioExecutor = { task -> ioTasks.add(task) },
            fallbackUaSupplier = { UserAgentProvider.DEFAULT_ANDROID_USER_AGENT },
        )

        // Background IO thread warm-up: loads persisted UA, never touches WebSettings, schedules idle refresh
        val afterBgInit = provider.initFromContext(fakeContext())
        assertEquals(persistedUa, afterBgInit)
        assertTrue(provider.isSystemWebViewDefault)
        assertEquals(0, systemUaCalls.get())
        assertTrue("Expected main idle refresh to be scheduled", scheduledIdleTask.get() != null)

        // Even calling refreshSystemUserAgentOnMain from a background thread must NOT invoke WebSettings
        assertEquals(persistedUa, provider.refreshSystemUserAgentOnMain(fakeContext()))
        assertEquals(0, systemUaCalls.get())

        // Now simulate the Main Looper running the scheduled idle task on the main thread
        onMainThread.set(true)
        scheduledIdleTask.get()!!.invoke()

        assertEquals(1, systemUaCalls.get())
        assertEquals(liveUa, provider.getUserAgent())
        assertTrue(provider.isSystemWebViewDefault)
        // Persisting the changed UA was dispatched asynchronously to ioExecutor (not run synchronously on main)
        assertEquals(0, persistedWrites.size)
        assertEquals(1, ioTasks.size)
        ioTasks.single().invoke()
        assertEquals(listOf(liveUa), persistedWrites)
    }

    @Test
    fun `main thread init and getUserAgent never block on disk IO or WebView`() {
        val persistedReaderCalledOnMain = AtomicBoolean(false)
        val systemUaCalled = AtomicInteger(0)
        val ioTasks = CopyOnWriteArrayList<() -> Unit>()
        val scheduledIdleTask = AtomicReference<(() -> Unit)?>(null)
        val fallback = "Mozilla/5.0 (Linux; Android 16; Emulator) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        val provider = UserAgentProvider(
            initialOverride = null,
            isMainThread = { true },
            systemUaFetcher = {
                systemUaCalled.incrementAndGet()
                "Mozilla/5.0 RealWebView/145"
            },
            persistedUaReader = {
                persistedReaderCalledOnMain.set(true)
                "Mozilla/5.0 PersistedWebView/144"
            },
            persistedUaWriter = { _, _ -> },
            mainIdleScheduler = { task ->
                scheduledIdleTask.set(task)
                true
            },
            ioExecutor = { task -> ioTasks.add(task) },
            fallbackUaSupplier = { fallback },
        )

        val ua = provider.getUserAgent(fakeContext())
        assertEquals(fallback, ua)
        assertFalse(provider.isSystemWebViewDefault)
        assertFalse("Main thread must not perform synchronous SharedPreferences read", persistedReaderCalledOnMain.get())
        assertEquals("Main thread getUserAgent must not eagerly invoke WebSettings", 0, systemUaCalled.get())
        assertEquals(1, ioTasks.size)
        assertTrue(scheduledIdleTask.get() != null)
    }

    @Test
    fun `refreshSystemUserAgentOnMain falls back gracefully when WebView is unavailable`() {
        val fallback = "Mozilla/5.0 (Linux; Android 16; TestDevice) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        val provider = UserAgentProvider(
            initialOverride = null,
            isMainThread = { true },
            systemUaFetcher = { throw IllegalStateException("No WebView package installed") },
            persistedUaReader = { null },
            persistedUaWriter = { _, _ -> },
            mainIdleScheduler = { false },
            ioExecutor = { it() },
            fallbackUaSupplier = { fallback },
        )

        val ua = provider.refreshSystemUserAgentOnMain(fakeContext())
        assertEquals(fallback, ua)
        assertFalse(provider.isSystemWebViewDefault)
    }

    @Test
    fun `explicit override is never clobbered by persisted or live WebView UA`() {
        val idleTask = AtomicReference<(() -> Unit)?>(null)
        val provider = UserAgentProvider(
            initialOverride = "CustomClient/1.0",
            isMainThread = { false },
            systemUaFetcher = { "SystemWebView/145" },
            persistedUaReader = { "PersistedWebView/144" },
            persistedUaWriter = { _, _ -> },
            mainIdleScheduler = { task ->
                idleTask.set(task)
                true
            },
            ioExecutor = { it() },
            fallbackUaSupplier = { UserAgentProvider.DEFAULT_ANDROID_USER_AGENT },
        )

        assertEquals("CustomClient/1.0", provider.initFromContext(fakeContext()))
        assertEquals("CustomClient/1.0", provider.refreshSystemUserAgentOnMain(fakeContext()))
        assertFalse(provider.isSystemWebViewDefault)
        assertEquals(null, idleTask.get())

        val dynamicProvider = UserAgentProvider(
            initialOverride = null,
            isMainThread = { true },
            systemUaFetcher = { "SystemWebView/145" },
            persistedUaReader = { "PersistedWebView/144" },
            persistedUaWriter = { _, _ -> },
            mainIdleScheduler = { task ->
                idleTask.set(task)
                true
            },
            ioExecutor = { it() },
            fallbackUaSupplier = { UserAgentProvider.DEFAULT_ANDROID_USER_AGENT },
        )
        dynamicProvider.overrideUserAgent("ManualOverride/2.0")
        assertEquals("ManualOverride/2.0", dynamicProvider.refreshSystemUserAgentOnMain(fakeContext()))
        assertFalse(dynamicProvider.isSystemWebViewDefault)
    }

    @Test
    fun `concurrent background init and main thread access never deadlock or block each other`() {
        val bgEnteredRead = CountDownLatch(1)
        val allowBgToFinish = CountDownLatch(1)
        val mainThreadRef = AtomicReference<Thread>(null)
        val mainEnteredWebView = CountDownLatch(1)
        val allowMainWebViewToFinish = CountDownLatch(1)
        val systemUaInvokedOffMain = AtomicBoolean(false)

        val provider = UserAgentProvider(
            initialOverride = null,
            isMainThread = { Thread.currentThread() === mainThreadRef.get() },
            systemUaFetcher = {
                if (Thread.currentThread() !== mainThreadRef.get()) {
                    systemUaInvokedOffMain.set(true)
                }
                mainEnteredWebView.countDown()
                allowMainWebViewToFinish.await(5, TimeUnit.SECONDS)
                "Mozilla/5.0 LiveChromium/145.0"
            },
            persistedUaReader = {
                bgEnteredRead.countDown()
                allowBgToFinish.await(5, TimeUnit.SECONDS)
                "Mozilla/5.0 PersistedChromium/144.0"
            },
            persistedUaWriter = { _, _ -> },
            mainIdleScheduler = { true },
            ioExecutor = { it() },
            fallbackUaSupplier = { UserAgentProvider.DEFAULT_ANDROID_USER_AGENT },
        )

        val pool = Executors.newFixedThreadPool(18)
        try {
            // 1. Background IO thread starts initFromContext and stalls inside SharedPreferences read
            val bgFuture = pool.submit<String> { provider.initFromContext(fakeContext()) }
            assertTrue("Background thread should enter persistedUaReader", bgEnteredRead.await(2, TimeUnit.SECONDS))

            // 2. Simulated Main thread concurrently calls getUserAgent(context) — MUST NOT block on bg thread
            val mainCheckDone = CountDownLatch(1)
            val mainFuture = pool.submit<String> {
                mainThreadRef.set(Thread.currentThread())
                val immediate = provider.getUserAgent(fakeContext())
                assertEquals(UserAgentProvider.DEFAULT_ANDROID_USER_AGENT, immediate)
                mainCheckDone.countDown()
                // Now enter slow WebSettings.getDefaultUserAgent on main thread
                provider.refreshSystemUserAgentOnMain(fakeContext())
            }
            assertTrue("Main thread must not block while background IO holds no lock", mainCheckDone.await(2, TimeUnit.SECONDS))
            assertTrue("Main thread should enter systemUaFetcher", mainEnteredWebView.await(2, TimeUnit.SECONDS))

            // 3. While Main thread is inside WebSettings.getDefaultUserAgent, 16 concurrent worker threads
            //    call getUserAgent() and initFromContext() — NONE may block or deadlock!
            val workersDone = CountDownLatch(16)
            val workerFutures = (0 until 16).map { idx ->
                pool.submit {
                    val ua = if (idx % 2 == 0) provider.getUserAgent() else provider.getUserAgent(fakeContext())
                    assertTrue(ua.isNotBlank())
                    workersDone.countDown()
                }
            }
            assertTrue(
                "16 concurrent readers must complete without blocking on main thread's WebView call",
                workersDone.await(2, TimeUnit.SECONDS),
            )
            workerFutures.forEach { it.get(1, TimeUnit.SECONDS) }

            // 4. Release both blocked operations and verify final state favors LIVE_SYSTEM over PERSISTED_SYSTEM
            allowMainWebViewToFinish.countDown()
            assertEquals("Mozilla/5.0 LiveChromium/145.0", mainFuture.get(2, TimeUnit.SECONDS))

            allowBgToFinish.countDown()
            bgFuture.get(2, TimeUnit.SECONDS)

            assertFalse("WebSettings.getDefaultUserAgent must never run off main thread", systemUaInvokedOffMain.get())
            assertEquals("Mozilla/5.0 LiveChromium/145.0", provider.getUserAgent())
            assertTrue(provider.isSystemWebViewDefault)
        } finally {
            allowBgToFinish.countDown()
            allowMainWebViewToFinish.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `WebViewCookieBridge does not hold a lock while resolving CookieManager`() {
        val threadAEnteredProvider = CountDownLatch(1)
        val threadBCompleted = CountDownLatch(1)
        val invocationCount = AtomicInteger(0)

        val bridge = WebViewCookieBridge(
            forceInMemory = false,
            cookieManagerProvider = {
                val n = invocationCount.incrementAndGet()
                if (n == 1) {
                    // Simulate Thread A (IO) triggering first CookieManager.getInstance() which
                    // requires Thread B (Main) to execute concurrently without deadlocking on any bridge lock.
                    threadAEnteredProvider.countDown()
                    assertTrue(
                        "Deadlock detected: Thread B blocked waiting for Thread A's CookieManager resolution",
                        threadBCompleted.await(2, TimeUnit.SECONDS),
                    )
                }
                null
            },
        )

        val pool = Executors.newFixedThreadPool(2)
        try {
            val url = "https://www.nodeseek.com/".toHttpUrl()
            val threadA = pool.submit {
                bridge.loadForRequest(url)
            }
            assertTrue(threadAEnteredProvider.await(2, TimeUnit.SECONDS))

            val threadB = pool.submit {
                bridge.flush()
                threadBCompleted.countDown()
            }

            threadB.get(3, TimeUnit.SECONDS)
            threadA.get(3, TimeUnit.SECONDS)
        } finally {
            threadBCompleted.countDown()
            pool.shutdownNow()
        }
    }
}
