package com.mazhar.deliberate

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The block wall. This is the only thing standing between you and the Home feed.
 *
 * It hooks navigation, not resources. `shouldInterceptRequest` is deliberately NOT used:
 * it exists for resource loading, it runs on a background thread, and it gives weak
 * guarantees around redirects and history. Gating navigation is the correct boundary.
 *
 * Three navigation callbacks are covered, because YouTube reaches its Home feed three
 * different ways:
 *
 *  1. [shouldOverrideUrlLoading] — a real URL load (link tap, redirect, deep link).
 *     Returning true here means nothing loads at all. Cleanest case.
 *
 *  2. [doUpdateVisitedHistory] — mobile YouTube is a single-page app. Tapping Home in the
 *     bottom nav is a `history.pushState()`, which NEVER reaches shouldOverrideUrlLoading.
 *     This callback does fire for those. Without it, the single most likely way you would
 *     reach the Home feed is completely unguarded.
 *
 *  3. [onPageStarted] — belt and braces for redirect chains that land on the root.
 *
 * All three go through [BlockPolicy], so there is one rule, not three.
 *
 * @param onBlocked called on the UI thread when a navigation is denied. The boolean says
 *        whether the page already moved: false means we stopped it before anything loaded
 *        (so cancelling can simply dismiss), true means the SPA got there first (so
 *        cancelling has to actively retreat).
 * @param onSafeUrl called whenever we settle on a page that is allowed, so the Activity can
 *        remember somewhere to retreat to.
 */
class YouTubeWebViewClient(
    private val onBlocked: (NavigationType, String, Boolean) -> Unit,
    private val onSafeUrl: (String) -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Images, iframes and XHR are not navigations. Never gate them.
        if (!request.isForMainFrame) return false
        return gate(request.url.toString())
    }

    // Overriding a deprecated member on purpose: older WebView implementations still call the
    // String overload, and dropping it would leave those unguarded.
    @Suppress("OVERRIDE_DEPRECATION", "OverridingDeprecatedMember", "DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = gate(url)

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        val type = UrlClassifier.classify(url)
        if (isBlocked(type)) {
            view.stopLoading()
            onBlocked(type, url, true)
        } else {
            onSafeUrl(url)
            // SPA navigation never re-runs onPageFinished, and YouTube can replace <head>
            // during one. Re-asserting here is cheap and idempotent.
            applyCosmetics(view)
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val type = UrlClassifier.classify(url)
        if (isBlocked(type)) {
            view.stopLoading()
            onBlocked(type, url, true)
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        applyCosmetics(view)
    }

    /**
     * Phase 4 polish: hide the tab icons and Shorts shelves for whatever is still locked.
     *
     * Driven by the same [BlockPolicy] as the block wall, so unlocking a section brings its
     * tab back on the next injection rather than leaving a hole in the nav bar. Purely
     * cosmetic — see [PageCosmetics].
     */
    private fun applyCosmetics(view: WebView) {
        val script = PageCosmetics.script(
            hideHome = BlockPolicy.isBlocked(NavigationType.HOME),
            hideShorts = BlockPolicy.isBlocked(NavigationType.SHORTS)
        )
        try {
            view.evaluateJavascript(script, null)
        } catch (e: Exception) {
            // Never worth a crash.
        }
    }

    /** @return true if the navigation was blocked (caller should not load it). */
    private fun gate(url: String): Boolean {
        val type = UrlClassifier.classify(url)
        if (!isBlocked(type)) return false
        onBlocked(type, url, false)
        return true
    }

    private fun isBlocked(type: NavigationType): Boolean = BlockPolicy.isBlocked(type)
}
