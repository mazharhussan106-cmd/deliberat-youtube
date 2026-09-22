package com.mazhar.deliberate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `now` is a parameter, so the awkward clock cases are ordinary tests rather than something
 * you can only find by changing the device date.
 */
class CooldownPolicyTest {

    private val now = 1_700_000_000_000L
    private val cooldown = CooldownPolicy.COOLDOWN_MS

    // ---- remainingMs -----------------------------------------------------

    @Test
    fun `no lock stored means nothing remaining`() {
        assertEquals(0L, CooldownPolicy.remainingMs(lockedUntil = 0L, now = now))
    }

    @Test
    fun `a lock in the future reports what is left`() {
        assertEquals(30_000L, CooldownPolicy.remainingMs(lockedUntil = now + 30_000L, now = now))
    }

    @Test
    fun `a lock that just expired is over`() {
        assertEquals(0L, CooldownPolicy.remainingMs(lockedUntil = now, now = now))
    }

    @Test
    fun `a lock in the past is over`() {
        assertEquals(0L, CooldownPolicy.remainingMs(lockedUntil = now - 1L, now = now))
    }

    @Test
    fun `a lock beyond one full cooldown means the clock moved, so it is discarded`() {
        // Otherwise winding the date back would strand the app in a lockout for years.
        assertEquals(
            0L,
            CooldownPolicy.remainingMs(lockedUntil = now + cooldown * 100, now = now)
        )
    }

    @Test
    fun `a lock of exactly one cooldown is still honoured`() {
        assertEquals(
            cooldown,
            CooldownPolicy.remainingMs(lockedUntil = now + cooldown, now = now)
        )
    }

    // ---- onFailure -------------------------------------------------------

    @Test
    fun `the first wrong try does not lock anything`() {
        val f = CooldownPolicy.onFailure(previousFailures = 0, now = now)
        assertFalse(f.lockedOut)
        assertEquals(1, f.failures)
        assertEquals(CooldownPolicy.MAX_FAILURES - 1, f.attemptsLeft)
        assertEquals(0L, f.lockedUntil)
    }

    @Test
    fun `attempts left counts down`() {
        for (previous in 0 until CooldownPolicy.MAX_FAILURES - 1) {
            val f = CooldownPolicy.onFailure(previousFailures = previous, now = now)
            assertFalse("should not lock out at $previous prior failures", f.lockedOut)
            assertEquals(CooldownPolicy.MAX_FAILURES - previous - 1, f.attemptsLeft)
        }
    }

    @Test
    fun `the last wrong try locks out`() {
        val f = CooldownPolicy.onFailure(
            previousFailures = CooldownPolicy.MAX_FAILURES - 1,
            now = now
        )
        assertTrue(f.lockedOut)
        assertEquals(0, f.attemptsLeft)
        assertEquals(now + cooldown, f.lockedUntil)
    }

    @Test
    fun `locking out resets the counter so the next round starts clean`() {
        val f = CooldownPolicy.onFailure(
            previousFailures = CooldownPolicy.MAX_FAILURES - 1,
            now = now
        )
        assertEquals(0, f.failures)
    }

    @Test
    fun `a lockout it produces is one it would then honour`() {
        val f = CooldownPolicy.onFailure(
            previousFailures = CooldownPolicy.MAX_FAILURES - 1,
            now = now
        )
        assertEquals(cooldown, CooldownPolicy.remainingMs(f.lockedUntil, now))
        assertEquals(0L, CooldownPolicy.remainingMs(f.lockedUntil, now + cooldown))
    }

    @Test
    fun `custom limits are respected`() {
        val f = CooldownPolicy.onFailure(
            previousFailures = 1, now = now, maxFailures = 2, cooldownMs = 5_000L
        )
        assertTrue(f.lockedOut)
        assertEquals(now + 5_000L, f.lockedUntil)
    }
}
