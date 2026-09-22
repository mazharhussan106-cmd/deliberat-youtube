package com.mazhar.deliberate

/**
 * Phase 4: hides the parts of YouTube's own UI that invite you into a blocked section.
 *
 * This is **polish, not security**. Every one of these elements is still behind the block
 * wall in [YouTubeWebViewClient]; hiding them just removes the temptation to tap. If a
 * selector below goes stale, the app gets slightly more tempting and stays exactly as safe.
 *
 * ## Why CSS rather than removing nodes
 *
 * `onPageFinished` fires once per real page load, and mobile YouTube then rewrites its own
 * DOM constantly — the bottom nav often does not exist yet when the page "finishes", and SPA
 * navigation never fires `onPageFinished` again. JavaScript that deletes nodes would have to
 * win a race it cannot win, repeatedly. A `<style>` element is declarative: inject it once and
 * it applies to matching elements whenever they appear, for as long as the document lives.
 *
 * ## When a selector goes stale
 *
 * YouTube renames its custom elements every so often. To fix it: connect the phone by USB,
 * open `chrome://inspect` on a desktop Chrome, inspect the WebView, find the element, and add
 * its selector to the right list below. Nothing else in the app has to change. Selectors are
 * kept deliberately narrow — an over-broad rule would blank out parts of the page you wanted,
 * which is much harder to notice than a Shorts shelf that failed to disappear.
 *
 * Pure Kotlin, no `android.*` imports, so the generated script is unit-testable.
 */
object PageCosmetics {

    /** Stable id so re-injection replaces the rules instead of stacking up new ones. */
    const val STYLE_ELEMENT_ID = "deliberate-hidden-sections"

    /**
     * The Home tab in the bottom nav bar.
     *
     * `tab-identifier` is YouTube's own name for the tab and has been stable for a long time;
     * the `href` fallback catches a renamed element as long as the tab still links to the root.
     */
    private val HOME_SELECTORS = listOf(
        "ytm-pivot-bar-item-renderer[tab-identifier='FEwhat_to_watch']",
        "ytm-pivot-bar-item-renderer a[href='/']",
        "ytm-pivot-bar-item-renderer a[href='https://m.youtube.com/']",
        "ytm-pivot-bar-item-renderer a[href='https://www.youtube.com/']"
    )

    /** The Shorts tab in the bottom nav bar. */
    private val SHORTS_TAB_SELECTORS = listOf(
        "ytm-pivot-bar-item-renderer[tab-identifier='FEshorts']",
        "ytm-pivot-bar-item-renderer a[href^='/shorts']"
    )

    /**
     * Shorts shelves and cards inside search results and browse pages.
     *
     * These are inline content, not navigations, so the block wall never sees them — tapping
     * one does get blocked, but the row of thumbnails sits there scrolling past regardless.
     * This is the only thing that removes it.
     */
    private val SHORTS_CONTENT_SELECTORS = listOf(
        "ytm-reel-shelf-renderer",
        "ytm-reel-item-renderer",
        "ytm-shorts-lockup-view-model",
        "ytm-shorts-lockup-view-model-v2",
        "ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model)"
    )

    /**
     * The script to hand to `WebView.evaluateJavascript`.
     *
     * Always safe to call: it is idempotent, it clears its own rules when nothing should be
     * hidden any more (so unlocking a section brings its tab back), and the whole body is
     * wrapped in try/catch because a cosmetic failure must never surface as a broken page.
     */
    fun script(hideHome: Boolean, hideShorts: Boolean): String {
        val selectors = mutableListOf<String>()
        if (hideHome) selectors += HOME_SELECTORS
        if (hideShorts) {
            selectors += SHORTS_TAB_SELECTORS
            selectors += SHORTS_CONTENT_SELECTORS
        }

        // ONE RULE PER SELECTOR, deliberately. CSS drops an entire rule if any selector in a
        // comma-separated list fails to parse, so grouping them would mean one unsupported
        // selector (`:has()` on an older WebView, say) silently taking the working ones down
        // with it. Separate rules fail one at a time.
        //
        // Built as a JS array literal so no CSS ever has to be escaped into a string. Every
        // selector above uses single quotes for exactly this reason.
        val cssArray = selectors.joinToString(separator = ", ") {
            "\"$it { display: none !important; }\""
        }

        return """
            (function () {
              try {
                var css = [$cssArray].join("\n");
                var el = document.getElementById("$STYLE_ELEMENT_ID");
                if (!el) {
                  el = document.createElement("style");
                  el.id = "$STYLE_ELEMENT_ID";
                  (document.head || document.documentElement).appendChild(el);
                }
                if (el.textContent !== css) { el.textContent = css; }
              } catch (e) {
                /* YouTube's DOM drifts. Cosmetics are never worth a broken page. */
              }
            })();
        """.trimIndent()
    }
}
