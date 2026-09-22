package com.mazhar.deliberate

/**
 * The single rule that decides whether a navigation is denied.
 *
 * Every callback in [YouTubeWebViewClient] funnels through here, so there is one rule to
 * reason about rather than one per hook.
 *
 * Pure Kotlin with injectable state — no `android.*` imports and no hidden reads of the
 * singletons — so every combination of (type, feature flag, unlock state) is testable on a
 * plain JVM. Production call sites just pass the type and take the defaults.
 */
object BlockPolicy {

    fun isBlocked(
        type: NavigationType,
        blockHome: Boolean = Features.BLOCK_HOME,
        blockShorts: Boolean = Features.BLOCK_SHORTS,
        isUnlocked: (NavigationType) -> Boolean = SessionState::isUnlocked
    ): Boolean {
        val gatedInThisBuild = when (type) {
            NavigationType.HOME -> blockHome
            NavigationType.SHORTS -> blockShorts
            NavigationType.NORMAL -> false
        }
        return gatedInThisBuild && !isUnlocked(type)
    }
}
