package com.nodeseek.app.data.network

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Provides a synchronized User-Agent string shared across Android WebView and OkHttpClient.
 *
 * Cloudflare binds session clearance (`cf_clearance`) to the exact User-Agent string used
 * during interactive verification/sign-in, so WebView and OkHttp must always present the
 * identical User-Agent. When [isSystemWebViewDefault] is `true`, Android `WebView` instances
 * should keep their native default `userAgentString` untouched so Chromium's `Sec-CH-UA`
 * client hints remain intact.
 *
 * Concurrency & Deadlock-Freedom Guarantees:
 * - No Kotlin/JVM lock is ever held while invoking `WebSettings.getDefaultUserAgent()` or
 *   reading/writing `SharedPreferences`.
 * - `WebSettings.getDefaultUserAgent()` is ONLY ever invoked on the Android main thread,
 *   either when the main looper becomes idle or when a WebView is explicitly created on main.
 * - [getUserAgent] and [initFromContext] never block the main thread on disk I/O or WebView
 *   initialization; callers immediately receive either an explicit override, a persisted
 *   system WebView UA, or a `Build`-synthesized fallback UA.
 */
class UserAgentProvider internal constructor(
    initialOverride: String?,
    private val isMainThread: () -> Boolean,
    private val systemUaFetcher: (Context) -> String?,
    private val persistedUaReader: (Context) -> String?,
    private val persistedUaWriter: (Context, String) -> Unit,
    private val mainIdleScheduler: (() -> Unit) -> Boolean,
    private val ioExecutor: (() -> Unit) -> Unit,
    fallbackUaSupplier: () -> String,
) {
    constructor(initialOverride: String? = null) : this(
        initialOverride = initialOverride,
        isMainThread = ::isCurrentlyMainThread,
        systemUaFetcher = ::fetchSystemWebViewUserAgent,
        persistedUaReader = ::readPersistedSystemUa,
        persistedUaWriter = ::writePersistedSystemUa,
        mainIdleScheduler = ::scheduleOnMainIdle,
        ioExecutor = ::submitToIoExecutor,
        fallbackUaSupplier = ::buildFallbackUserAgent,
    )

    private enum class UaSource {
        FALLBACK,
        PERSISTED_SYSTEM,
        LIVE_SYSTEM,
        EXPLICIT_OVERRIDE,
    }

    private data class UaState(
        val userAgent: String,
        val source: UaSource,
    ) {
        val isSystemWebViewDefault: Boolean
            get() = source == UaSource.PERSISTED_SYSTEM || source == UaSource.LIVE_SYSTEM
    }

    private val state: AtomicReference<UaState> = AtomicReference(
        initialOverride?.trim()?.takeIf { it.isNotEmpty() }?.let {
            UaState(userAgent = it, source = UaSource.EXPLICIT_OVERRIDE)
        } ?: UaState(
            userAgent = runCatching(fallbackUaSupplier).getOrNull()?.takeIf { it.isNotBlank() }
                ?: DEFAULT_ANDROID_USER_AGENT,
            source = UaSource.FALLBACK,
        ),
    )

    private val persistedLoadStarted = AtomicBoolean(false)
    private val mainIdleRefreshScheduled = AtomicBoolean(false)
    private val lastPersistedUa = AtomicReference<String?>(null)

    val isSystemWebViewDefault: Boolean
        get() = state.get().isSystemWebViewDefault

    /**
     * Warms up the cached system UA from `SharedPreferences` (always off the main thread) and
     * schedules a one-shot main-thread idle refresh of `WebSettings.getDefaultUserAgent()`.
     *
     * Never blocks on locks or WebView initialization, and never calls `WebSettings` off main.
     */
    fun initFromContext(context: Context): String {
        val current = state.get()
        if (current.source == UaSource.EXPLICIT_OVERRIDE || current.source == UaSource.LIVE_SYSTEM) {
            return current.userAgent
        }

        val appContext = context.applicationContext ?: context
        if (!isMainThread()) {
            loadPersistedUserAgentOnce(appContext)
        } else {
            schedulePersistedUserAgentLoadAsync(appContext)
        }
        scheduleMainIdleRefresh(appContext)
        return state.get().userAgent
    }

    /**
     * Refreshes the live system WebView UA immediately when already executing on the main thread
     * (e.g., right before constructing a `WebView`). Never acquires a lock across WebView APIs
     * and never invokes `WebSettings.getDefaultUserAgent()` from a background thread.
     */
    fun refreshSystemUserAgentOnMain(context: Context): String {
        val current = state.get()
        if (current.source == UaSource.EXPLICIT_OVERRIDE || current.source == UaSource.LIVE_SYSTEM) {
            return current.userAgent
        }
        val appContext = context.applicationContext ?: context
        if (!isMainThread()) {
            return initFromContext(appContext)
        }
        return refreshFromWebViewOnMainUnchecked(appContext)
    }

    fun getUserAgent(context: Context? = null): String {
        if (context != null) {
            val current = state.get()
            if (current.source == UaSource.FALLBACK) {
                return initFromContext(context)
            }
            if (current.source == UaSource.PERSISTED_SYSTEM) {
                val appContext = context.applicationContext ?: context
                scheduleMainIdleRefresh(appContext)
            }
        }
        return state.get().userAgent
    }

    fun overrideUserAgent(userAgent: String) {
        val trimmed = userAgent.trim()
        if (trimmed.isNotEmpty()) {
            state.set(UaState(userAgent = trimmed, source = UaSource.EXPLICIT_OVERRIDE))
        }
    }

    fun getAcceptLanguage(): String {
        val locale = Locale.getDefault()
        val language = locale.language.ifBlank { "zh" }
        val tag = locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "zh-CN"
        return if (language.equals("zh", ignoreCase = true)) {
            "$tag,zh;q=0.9,en-US;q=0.8,en;q=0.7"
        } else {
            "$tag,$language;q=0.9,zh-CN;q=0.8,zh;q=0.7,en;q=0.6"
        }
    }

    private fun schedulePersistedUserAgentLoadAsync(appContext: Context) {
        if (!persistedLoadStarted.compareAndSet(false, true)) return
        runCatching {
            ioExecutor {
                readAndApplyPersistedUa(appContext)
            }
        }.onFailure {
            persistedLoadStarted.set(false)
        }
    }

    private fun loadPersistedUserAgentOnce(appContext: Context) {
        if (!persistedLoadStarted.compareAndSet(false, true)) return
        readAndApplyPersistedUa(appContext)
    }

    private fun readAndApplyPersistedUa(appContext: Context) {
        val persisted = runCatching { persistedUaReader(appContext) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        lastPersistedUa.compareAndSet(null, persisted)
        state.updateAndGet { prev ->
            if (prev.source == UaSource.FALLBACK) {
                UaState(userAgent = persisted, source = UaSource.PERSISTED_SYSTEM)
            } else {
                prev
            }
        }
    }

    private fun scheduleMainIdleRefresh(appContext: Context) {
        val current = state.get()
        if (current.source == UaSource.EXPLICIT_OVERRIDE || current.source == UaSource.LIVE_SYSTEM) {
            return
        }
        if (!mainIdleRefreshScheduled.compareAndSet(false, true)) return
        val scheduled = runCatching {
            mainIdleScheduler {
                if (isMainThread()) {
                    refreshFromWebViewOnMainUnchecked(appContext)
                }
            }
        }.getOrDefault(false)
        if (!scheduled) {
            mainIdleRefreshScheduled.set(false)
        }
    }

    private fun refreshFromWebViewOnMainUnchecked(appContext: Context): String {
        val current = state.get()
        if (current.source == UaSource.EXPLICIT_OVERRIDE || current.source == UaSource.LIVE_SYSTEM) {
            return current.userAgent
        }
        val systemUa = runCatching { systemUaFetcher(appContext) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return state.get().userAgent

        val updated = state.updateAndGet { prev ->
            if (prev.source == UaSource.EXPLICIT_OVERRIDE) {
                prev
            } else {
                UaState(userAgent = systemUa, source = UaSource.LIVE_SYSTEM)
            }
        }
        if (updated.source == UaSource.LIVE_SYSTEM && lastPersistedUa.getAndSet(systemUa) != systemUa) {
            runCatching {
                ioExecutor {
                    runCatching { persistedUaWriter(appContext, systemUa) }
                }
            }
        }
        return updated.userAgent
    }

    companion object {
        private const val PREFS_NAME = "nodepulse_network_prefs"
        private const val KEY_CACHED_SYSTEM_UA = "cached_system_webview_ua"
        private const val MAIN_IDLE_REFRESH_DELAY_MS = 1_000L

        const val DEFAULT_ANDROID_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val sharedIoExecutor: ThreadPoolExecutor by lazy(LazyThreadSafetyMode.PUBLICATION) {
            ThreadPoolExecutor(
                1,
                1,
                30L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
            ) { runnable ->
                Thread(runnable, "UserAgentProvider-IO").apply { isDaemon = true }
            }.apply {
                allowCoreThreadTimeOut(true)
            }
        }

        internal fun buildFallbackUserAgent(
            androidRelease: String? = safeBuildRelease(),
            deviceModel: String? = safeBuildModel(),
        ): String {
            val release = sanitizeUaToken(androidRelease)
            val model = sanitizeUaToken(deviceModel)
            if (release == null && model == null) {
                return DEFAULT_ANDROID_USER_AGENT
            }
            val effectiveRelease = release ?: "14"
            val effectiveModel = model ?: "Android"
            return "Mozilla/5.0 (Linux; Android $effectiveRelease; $effectiveModel) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }

        private fun sanitizeUaToken(raw: String?): String? =
            raw?.trim()
                ?.filter { ch -> ch in ' '..'~' && ch != ';' && ch != '(' && ch != ')' }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun safeBuildRelease(): String? =
            runCatching { Build.VERSION.RELEASE }.getOrNull()

        private fun safeBuildModel(): String? =
            runCatching { Build.MODEL }.getOrNull()

        private fun isCurrentlyMainThread(): Boolean =
            runCatching { Looper.getMainLooper()?.thread === Thread.currentThread() }.getOrDefault(false)

        private fun fetchSystemWebViewUserAgent(appContext: Context): String? =
            WebSettings.getDefaultUserAgent(appContext)

        private fun readPersistedSystemUa(appContext: Context): String? =
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CACHED_SYSTEM_UA, null)

        private fun writePersistedSystemUa(appContext: Context, userAgent: String) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHED_SYSTEM_UA, userAgent)
                .apply()
        }

        private fun scheduleOnMainIdle(task: () -> Unit): Boolean =
            runCatching {
                val mainLooper = Looper.getMainLooper() ?: return false
                Handler(mainLooper).postDelayed(
                    {
                        val added = runCatching {
                            Looper.myQueue().addIdleHandler {
                                runCatching(task)
                                false
                            }
                            true
                        }.getOrDefault(false)
                        if (!added) {
                            runCatching(task)
                        }
                    },
                    MAIN_IDLE_REFRESH_DELAY_MS,
                )
            }.getOrDefault(false)

        private fun submitToIoExecutor(task: () -> Unit) {
            sharedIoExecutor.execute(task)
        }
    }
}
