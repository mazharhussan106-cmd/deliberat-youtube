package com.mazhar.deliberate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM tests — no emulator, no Robolectric. Run with:
 *
 *     ./gradlew :app:testDebugUnitTest
 *
 * Covers the matrix in the plan plus the edge cases that actually bite:
 * look-alike hosts, `/shortsomething`, unparseable URLs, and `about:blank`.
 */
class UrlClassifierTest {

    // ---- The plan's matrix ------------------------------------------------

    @Test
    fun `root with trailing slash is home`() {
        assertEquals(NavigationType.HOME, UrlClassifier.classify("https://www.youtube.com/"))
    }

    @Test
    fun `root without trailing slash is home`() {
        assertEquals(NavigationType.HOME, UrlClassifier.classify("https://www.youtube.com"))
    }

    @Test
    fun `mobile root is home`() {
        assertEquals(NavigationType.HOME, UrlClassifier.classify("https://m.youtube.com/"))
    }

    @Test
    fun `search results are normal`() {
        assertEquals(
            NavigationType.NORMAL,
            UrlClassifier.classify("https://www.youtube.com/results?search_query=kotlin+webview")
        )
    }

    @Test
    fun `watch page is normal`() {
        assertEquals(
            NavigationType.NORMAL,
            UrlClassifier.classify("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun `shorts is shorts`() {
        assertEquals(
            NavigationType.SHORTS,
            UrlClassifier.classify("https://www.youtube.com/shorts/abc123")
        )
    }

    @Test
    fun `handle page is normal`() {
        assertEquals(
            NavigationType.NORMAL,
            UrlClassifier.classify("https://www.youtube.com/@somechannel")
        )
    }

    @Test
    fun `channel page is normal`() {
        assertEquals(
            NavigationType.NORMAL,
            UrlClassifier.classify("https://www.youtube.com/channel/UCabcdefg")
        )
    }

    // ---- Root variants ----------------------------------------------------

    @Test
    fun `root with query params is still home`() {
        assertEquals(
            NavigationType.HOME,
            UrlClassifier.classify("https://www.youtube.com/?app=desktop")
        )
    }

    @Test
    fun `bare apex domain is home`() {
        assertEquals(NavigationType.HOME, UrlClassifier.classify("https://youtube.com"))
    }

    // ---- Shorts variants --------------------------------------------------

    @Test
    fun `bare shorts path is shorts`() {
        assertEquals(NavigationType.SHORTS, UrlClassifier.classify("https://www.youtube.com/shorts"))
    }

    @Test
    fun `shorts with trailing slash is shorts`() {
        assertEquals(
            NavigationType.SHORTS,
            UrlClassifier.classify("https://www.youtube.com/shorts/")
        )
    }

    @Test
    fun `a path that merely starts with the letters shorts is not shorts`() {
        // Guards the difference between startsWith("/shorts") and the stricter check.
        assertEquals(
            NavigationType.NORMAL,
            UrlClassifier.classify("https://www.youtube.com/shortsomething")
        )
    }

    // ---- Host matching ----------------------------------------------------

    @Test
    fun `look-alike host is not youtube`() {
        assertEquals(
            NavigationType.NORMAL,
            UrlClassifier.classify("https://youtube.com.example.net/")
        )
    }

    @Test
    fun `unrelated host root is normal`() {
        assertEquals(NavigationType.NORMAL, UrlClassifier.classify("https://example.com/"))
    }

    @Test
    fun `youtu dot be short link is normal`() {
        assertEquals(NavigationType.NORMAL, UrlClassifier.classify("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `music subdomain root is home`() {
        assertEquals(NavigationType.HOME, UrlClassifier.classify("https://music.youtube.com/"))
    }

    // ---- Degenerate input -------------------------------------------------

    @Test
    fun `null is normal`() {
        assertEquals(NavigationType.NORMAL, UrlClassifier.classify(null))
    }

    @Test
    fun `blank is normal`() {
        assertEquals(NavigationType.NORMAL, UrlClassifier.classify("   "))
    }

    @Test
    fun `about blank is normal`() {
        assertEquals(NavigationType.NORMAL, UrlClassifier.classify("about:blank"))
    }

    @Test
    fun `unparseable youtube root still classifies as home`() {
        // A raw space makes java.net.URI throw; the fallback path has to catch this.
        assertEquals(NavigationType.HOME, UrlClassifier.classify("https://www.youtube.com/ "))
    }

    @Test
    fun `unparseable shorts url still classifies as shorts`() {
        assertEquals(
            NavigationType.SHORTS,
            UrlClassifier.classify("https://www.youtube.com/shorts/ab c")
        )
    }

    // ---- Case handling ----------------------------------------------------

    @Test
    fun `uppercase host is home`() {
        assertEquals(NavigationType.HOME, UrlClassifier.classify("https://WWW.YOUTUBE.COM/"))
    }

    @Test
    fun `uppercase shorts path is shorts`() {
        assertEquals(
            NavigationType.SHORTS,
            UrlClassifier.classify("https://www.youtube.com/SHORTS/abc")
        )
    }
}
