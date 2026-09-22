package com.mazhar.deliberate

import java.net.URI

/**
 * What a navigation target is, as far as this app cares.
 */
enum class NavigationType { HOME, SHORTS, NORMAL }

/**
 * Decides what a URL *is*. Nothing about blocking lives here.
 *
 * Deliberately pure Kotlin — no `android.*` imports — so the whole class runs in plain
 * JVM unit tests under `src/test` with no emulator and no Robolectric.
 * See [UrlClassifierTest] for the full matrix.
 */
object UrlClassifier {

    fun classify(url: String?): NavigationType {
        if (url.isNullOrBlank()) return NavigationType.NORMAL

        val parsed = parse(url) ?: return fallbackClassify(url)
        val (host, rawPath) = parsed

        if (!isYouTubeHost(host)) return NavigationType.NORMAL

        // Trailing slash is noise: "/" and "" are the same page.
        val path = rawPath.trimEnd('/').lowercase()

        return when {
            path == "/shorts" || path.startsWith("/shorts/") -> NavigationType.SHORTS
            path.isEmpty() -> NavigationType.HOME
            else -> NavigationType.NORMAL
        }
    }

    /**
     * Exact-suffix match rather than `contains("youtube.com")`.
     * Still tolerates the m. / www. / music. redirects the plan cared about,
     * but will not misfire on something like `youtube.com.example.net`.
     */
    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com")

    private fun parse(url: String): Pair<String, String>? = try {
        val uri = URI(url)
        val host = uri.host?.lowercase()
        if (host == null) null else host to (uri.rawPath ?: "")
    } catch (e: Exception) {
        null
    }

    /**
     * Only reached when the URL will not parse as a URI (unescaped spaces, odd schemes).
     * Conservative string matching so a malformed YouTube URL still gets classified
     * rather than silently waved through.
     */
    private fun fallbackClassify(url: String): NavigationType {
        val lower = url.lowercase()
        if (!lower.contains("youtube.com")) return NavigationType.NORMAL
        if (lower.contains("/shorts")) return NavigationType.SHORTS

        val afterHost = lower.substringAfter("youtube.com", "")
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trimEnd('/')

        return if (afterHost.isEmpty()) NavigationType.HOME else NavigationType.NORMAL
    }
}
