package com.nodeseek.app.data.network

import android.content.Context
import android.webkit.WebSettings
import java.util.Locale

/**
 * Provides a synchronized User-Agent string shared across Android WebView and OkHttpClient.
 *
 * Cloudflare binds session clearance (`cf_clearance`) to the exact User-Agent string used
 * during interactive verification/sign-in, so WebView and OkHttp must always present the
 * identical User-Agent. When [isSystemWebViewDefault] is `true`, Android `WebView` instances
 * should keep their native default `userAgentString` untouched so Chromium's `Sec-CH-UA`
 * client hints remain intact.
 */
class UserAgentProvider(
    initialOverride: String? = null,
) {
    @Volatile
    private var cachedUserAgent: String? = initialOverride?.takeIf { it.isNotBlank() }

    @Volatile
    var isSystemWebViewDefault: Boolean = false
        private set

    @Synchronized
    fun initFromContext(context: Context): String {
        cachedUserAgent?.let { existing ->
            if (existing != DEFAULT_ANDROID_USER_AGENT || isSystemWebViewDefault) {
                return existing
            }
        }
        val systemUa = runCatching {
            WebSettings.getDefaultUserAgent(context.applicationContext ?: context)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return if (systemUa != null) {
            isSystemWebViewDefault = true
            cachedUserAgent = systemUa
            systemUa
        } else {
            val fallback = cachedUserAgent ?: DEFAULT_ANDROID_USER_AGENT
            cachedUserAgent = fallback
            fallback
        }
    }

    fun getUserAgent(context: Context? = null): String {
        if (context != null && (cachedUserAgent == null || (!isSystemWebViewDefault && cachedUserAgent == DEFAULT_ANDROID_USER_AGENT))) {
            return initFromContext(context)
        }
        cachedUserAgent?.let { return it }
        synchronized(this) {
            cachedUserAgent?.let { return it }
            val fallback = DEFAULT_ANDROID_USER_AGENT
            cachedUserAgent = fallback
            return fallback
        }
    }

    @Synchronized
    fun overrideUserAgent(userAgent: String) {
        val trimmed = userAgent.trim()
        if (trimmed.isNotEmpty()) {
            isSystemWebViewDefault = false
            cachedUserAgent = trimmed
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

    companion object {
        const val DEFAULT_ANDROID_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
