package com.mazhar.deliberate

/**
 * The arithmetic behind "too many wrong tries, wait a minute".
 *
 * Pure Kotlin with `now` passed in, so every branch — including the awkward clock ones — is
 * testable on a plain JVM without sleeping.
 *
 * The cooldown is deliberately persisted by [PinManager] rather than held in memory. An
 * in-memory cooldown is cleared by force-stopping the app, and this whole app is built around
 * the idea that you might force-stop it.
 */
object CooldownPolicy {

    /** Wrong tries allowed before the lockout starts. */
    const val MAX_FAILURES = 5

    const val COOLDOWN_MS = 60_000L

    /**
     * How much lockout is left.
     *
     * Uses wall-clock time, which the user can change. Two guards: a lock already in the past
     * is over, and a lock further out than one full cooldown means the clock moved, so the
     * lock is discarded rather than left to strand the app for a decade. Moving the clock
     * backwards therefore defeats the cooldown — on your own phone that is more effort than
     * waiting the minute out, which is the entire point of the feature.
     *
     * @return remaining milliseconds, or 0 when not locked.
     */
    fun remainingMs(lockedUntil: Long, now: Long, cooldownMs: Long = COOLDOWN_MS): Long {
        if (lockedUntil <= 0L) return 0L
        val remaining = lockedUntil - now
        if (remaining <= 0L) return 0L
        if (remaining > cooldownMs) return 0L
        return remaining
    }

    /** What a wrong PIN produces, given how many wrong ones came before it. */
    fun onFailure(
        previousFailures: Int,
        now: Long,
        maxFailures: Int = MAX_FAILURES,
        cooldownMs: Long = COOLDOWN_MS
    ): Failure {
        val failures = previousFailures + 1
        return if (failures >= maxFailures) {
            // Counter resets alongside the lockout, so the next round starts from a clean slate.
            Failure(failures = 0, lockedUntil = now + cooldownMs, attemptsLeft = 0, lockedOut = true)
        } else {
            Failure(
                failures = failures,
                lockedUntil = 0L,
                attemptsLeft = maxFailures - failures,
                lockedOut = false
            )
        }
    }

    data class Failure(
        /** Failure count to persist. */
        val failures: Int,
        /** Wall-clock instant the lockout ends, or 0 when not locked out. */
        val lockedUntil: Long,
        /** Wrong tries left before a lockout. 0 when already locked out. */
        val attemptsLeft: Int,
        val lockedOut: Boolean
    )
}
