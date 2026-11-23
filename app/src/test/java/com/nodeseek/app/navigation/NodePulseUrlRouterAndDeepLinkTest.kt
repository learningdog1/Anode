package com.nodeseek.app.navigation

import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodePulseUrlRouterAndDeepLinkTest {

    private class RecordingUriHandler : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    @Test
    fun `DeepLinkParser maps award path to CuratedFeed`() {
        assertEquals(
            NodePulseDeepLink.CuratedFeed,
            DeepLinkParser.parseUrl("https://www.nodeseek.com/award"),
        )
        assertEquals(
            NodePulseDeepLink.CuratedFeed,
            DeepLinkParser.parseUrl("https://nodeseek.com/award/"),
        )
    }

    @Test
    fun `normalizeNodeSeekUrl preserves protocol-relative host instead of prefixing nodeseek`() {
        assertEquals(
            "https://cdn.example.com/path/img.png",
            normalizeNodeSeekUrl("//cdn.example.com/path/img.png"),
        )
        assertEquals(
            "https://www.nodeseek.com/post-123-1",
            normalizeNodeSeekUrl("/post-123-1"),
        )
    }

    @Test
    fun `isSafeExternalHttpUrl allows only http and https with valid authority`() {
        assertTrue(isSafeExternalHttpUrl("https://example.com/路径?a=1"))
        assertTrue(isSafeExternalHttpUrl("http://1.2.3.4:8080/test"))
        assertFalse(isSafeExternalHttpUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isSafeExternalHttpUrl("file:///data/data/com.nodeseek.app/shared_prefs"))
        assertFalse(isSafeExternalHttpUrl("tel:10086"))
        assertFalse(isSafeExternalHttpUrl("javascript:alert(1)"))
        assertFalse(isSafeExternalHttpUrl("content://com.android.contacts/data/1"))
        assertFalse(isSafeExternalHttpUrl("https://"))
        assertFalse(isSafeExternalHttpUrl("https://example.com/a\nb"))
    }

    @Test
    fun `openNodeSeekOrExternalUrl blocks non-http schemes and routes protocol-relative and award safely`() {
        val handler = RecordingUriHandler()
        var curatedOpened = false

        openNodeSeekOrExternalUrl(
            rawUrl = "intent://evil/#Intent;end",
            uriHandler = handler,
        )
        openNodeSeekOrExternalUrl(
            rawUrl = "file:///etc/hosts",
            uriHandler = handler,
        )
        openNodeSeekOrExternalUrl(
            rawUrl = "tel:10086",
            uriHandler = handler,
        )
        assertTrue("Non-HTTP schemes must never invoke UriHandler", handler.opened.isEmpty())

        openNodeSeekOrExternalUrl(
            rawUrl = "//cdn.example.org/pic.webp",
            uriHandler = handler,
        )
        assertEquals(listOf("https://cdn.example.org/pic.webp"), handler.opened)

        openNodeSeekOrExternalUrl(
            rawUrl = "/award",
            uriHandler = handler,
            onOpenCurated = { curatedOpened = true },
        )
        assertTrue(curatedOpened)
    }
}
