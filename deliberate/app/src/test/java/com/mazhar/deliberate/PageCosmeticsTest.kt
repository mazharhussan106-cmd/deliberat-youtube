package com.mazhar.deliberate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These lock in the contract of the injected script rather than its exact text: what appears
 * for which flags, that it cannot stack up duplicates, that it can un-hide, and that it can
 * never throw into the page.
 *
 * The one thing a string test cannot tell you is whether the JavaScript parses. That is
 * checked separately by running the generated script through `node --check`; see the Phase 4
 * build notes.
 */
class PageCosmeticsTest {

    private fun script(home: Boolean, shorts: Boolean) = PageCosmetics.script(home, shorts)

    // ---- Conditional inclusion -------------------------------------------

    @Test
    fun `hiding home includes the home tab selector`() {
        assertTrue(script(home = true, shorts = false).contains("FEwhat_to_watch"))
    }

    @Test
    fun `not hiding home leaves the home tab alone`() {
        assertFalse(script(home = false, shorts = true).contains("FEwhat_to_watch"))
    }

    @Test
    fun `hiding shorts includes both the tab and the shelves`() {
        val js = script(home = false, shorts = true)
        assertTrue("shorts tab", js.contains("FEshorts"))
        assertTrue("shorts shelf", js.contains("ytm-reel-shelf-renderer"))
    }

    @Test
    fun `not hiding shorts leaves both alone`() {
        val js = script(home = true, shorts = false)
        assertFalse(js.contains("FEshorts"))
        assertFalse(js.contains("ytm-reel-shelf-renderer"))
    }

    @Test
    fun `hiding both includes both`() {
        val js = script(home = true, shorts = true)
        assertTrue(js.contains("FEwhat_to_watch"))
        assertTrue(js.contains("FEshorts"))
        assertTrue(js.contains("ytm-reel-shelf-renderer"))
    }

    // ---- Un-hiding --------------------------------------------------------

    @Test
    fun `with nothing to hide the script still runs and empties the rules`() {
        // This is what brings a tab back after an unlock: an empty rule list, not a no-op.
        val js = script(home = false, shorts = false)
        assertTrue("must still assign the style content", js.contains("textContent"))
        assertTrue("rule array must be empty", js.contains("var css = [].join"))
        assertFalse(js.contains("display: none"))
    }

    // ---- Safety -----------------------------------------------------------

    @Test
    fun `the whole body is wrapped in try catch`() {
        val js = script(home = true, shorts = true)
        val tryAt = js.indexOf("try {")
        val catchAt = js.indexOf("catch")
        assertTrue("try must exist", tryAt >= 0)
        assertTrue("catch must follow try", catchAt > tryAt)
        assertTrue("style work must be inside the try", js.indexOf("createElement") > tryAt)
        assertTrue("style work must be inside the try", js.indexOf("createElement") < catchAt)
    }

    @Test
    fun `it is an immediately invoked function so nothing leaks into the page`() {
        val js = script(home = true, shorts = true)
        assertTrue(js.trim().startsWith("(function ()"))
        assertTrue(js.trim().endsWith("})();"))
    }

    // ---- Idempotency ------------------------------------------------------

    @Test
    fun `it reuses one stable element instead of appending another`() {
        val js = script(home = true, shorts = true)
        assertTrue(js.contains("getElementById(\"${PageCosmetics.STYLE_ELEMENT_ID}\")"))
        assertEquals("should only ever append once", 1, js.split("appendChild").size - 1)
    }

    @Test
    fun `the same inputs produce the same script`() {
        assertEquals(script(home = true, shorts = true), script(home = true, shorts = true))
    }

    // ---- Quoting ----------------------------------------------------------

    @Test
    fun `selectors use single quotes so the JS string literals never break`() {
        // Every attribute selector lives inside a double-quoted JS string, so a double quote
        // in a selector would end that string early and produce a syntax error in the page.
        val js = script(home = true, shorts = true)
        val cssArray = js.substringAfter("var css = [").substringBefore("].join")
        assertFalse("no raw double quotes inside the rules", cssArray.contains("=\\\""))
        assertTrue("attribute selectors are single-quoted", cssArray.contains("[tab-identifier='"))
    }

    // ---- Scope ------------------------------------------------------------

    @Test
    fun `rules stay scoped to youtube's own elements`() {
        // An over-broad rule would blank out parts of the page and be far harder to notice
        // than a shelf that failed to disappear.
        val js = script(home = true, shorts = true)
        val cssArray = js.substringAfter("var css = [").substringBefore("].join")
        for (bare in listOf("\"div", "\"a ", "\"* ", "\"body", "\"section")) {
            assertFalse("rule must not start at $bare", cssArray.contains(bare))
        }
    }

    // ---- Rule isolation ---------------------------------------------------

    @Test
    fun `each selector gets its own rule`() {
        // CSS drops a whole rule when any selector in a comma-separated list fails to parse.
        // One selector per rule means an unsupported one (`:has()` on an older WebView) cannot
        // take the working selectors down with it.
        val js = script(home = true, shorts = true)
        val cssArray = js.substringAfter("var css = [").substringBefore("].join")
        val rules = cssArray.split("\", \"")
        assertTrue("expected several rules", rules.size > 4)
        for (r in rules) {
            assertEquals(
                "rule should declare display:none exactly once: $r",
                1, r.split("display: none").size - 1
            )
            assertFalse("rule should hold a single selector: $r", r.substringBefore(" {").contains(","))
        }
    }
}
