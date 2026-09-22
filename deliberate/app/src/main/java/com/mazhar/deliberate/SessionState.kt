package com.mazhar.deliberate

/**
 * Which sections have been unlocked for this run of the app.
 *
 * A Kotlin `object` is a process-scoped singleton, which is exactly the scope the plan
 * asks for: it survives Activity recreation (rotation, config change, theme switch) and
 * resets only when the app process actually dies. That is what "session-only" means here.
 *
 * Holding this on the Activity would reset the unlock on every rotation — a bug, not a feature.
 */
object SessionState {

    @Volatile
    var homeUnlocked: Boolean = false

    @Volatile
    var shortsUnlocked: Boolean = false

    fun unlock(type: NavigationType) {
        when (type) {
            NavigationType.HOME -> homeUnlocked = true
            NavigationType.SHORTS -> shortsUnlocked = true
            NavigationType.NORMAL -> Unit
        }
    }

    fun isUnlocked(type: NavigationType): Boolean = when (type) {
        NavigationType.HOME -> homeUnlocked
        NavigationType.SHORTS -> shortsUnlocked
        NavigationType.NORMAL -> true
    }

    /** Only used by tests / a future "lock again" button. */
    fun lockAll() {
        homeUnlocked = false
        shortsUnlocked = false
    }
}

/**
 * Build-time switches for the phased rollout in the plan.
 *
 * Phase 2 (this build): both Home and Shorts are gated, each with its own unlock. Unlocking
 * one does not unlock the other — that is the point, and [BlockPolicyTest] holds it in place.
 */
object Features {
    const val BLOCK_HOME: Boolean = true
    const val BLOCK_SHORTS: Boolean = true
}
