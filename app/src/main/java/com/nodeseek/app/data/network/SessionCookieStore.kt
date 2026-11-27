package com.nodeseek.app.data.network

import com.nodeseek.app.domain.model.StoredCookieRecord
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

interface SessionCookieStore {
    fun exportAccountCookies(): List<StoredCookieRecord>

    fun restoreAccountCookies(cookies: List<StoredCookieRecord>)

    fun clearSessionCookies(includeClearance: Boolean = false)

    fun hasClearanceCookie(url: HttpUrl = NodeSeekUrlNormalizer.ORIGIN.toHttpUrl()): Boolean

    fun hasAnyAuthCookie(
        urls: List<HttpUrl> = listOf(
            NodeSeekUrlNormalizer.ORIGIN.toHttpUrl(),
            "https://${NodeSeekUrlNormalizer.BARE_HOST}/".toHttpUrl(),
        ),
    ): Boolean

    fun flush()
}
