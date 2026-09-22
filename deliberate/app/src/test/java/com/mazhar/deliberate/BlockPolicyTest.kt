package com.mazhar.deliberate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for the gating rule. Run with:
 *
 *     ./gradlew :app:testDebugUnitTest
 *
 * The rule has three inputs — what the URL is, whether this build gates that section, and
 * whether it has been unlocked this session — so all of them are injected rather than read
 * from the singletons.
 */
class BlockPolicyTest {

    /** Nothing unlocked. */
    private val allLocked: (NavigationType) -> Boolean = { false }

    /** Only the listed sections unlocked. */
    private fun unlocked(vararg types: NavigationType): (NavigationType) -> Boolean =
        { it in types }

    // ---- Home ------------------------------------------------------------

    @Test
    fun `home is blocked when gated and locked`() {
        assertTrue(
            BlockPolicy.isBlocked(
                NavigationType.HOME,
                blockHome = true, blockShorts = true, isUnlocked = allLocked
            )
        )
    }

    @Test
    fun `home is allowed once unlocked`() {
        assertFalse(
            BlockPolicy.isBlocked(
                NavigationType.HOME,
                blockHome = true, blockShorts = true,
                isUnlocked = unlocked(NavigationType.HOME)
            )
        )
    }

    @Test
    fun `home is allowed when this build does not gate it`() {
        assertFalse(
            BlockPolicy.isBlocked(
                NavigationType.HOME,
                blockHome = false, blockShorts = true, isUnlocked = allLocked
            )
        )
    }

    // ---- Shorts ----------------------------------------------------------

    @Test
    fun `shorts is blocked when gated and locked`() {
        assertTrue(
            BlockPolicy.isBlocked(
                NavigationType.SHORTS,
                blockHome = true, blockShorts = true, isUnlocked = allLocked
            )
        )
    }

    @Test
    fun `shorts is allowed once unlocked`() {
        assertFalse(
            BlockPolicy.isBlocked(
                NavigationType.SHORTS,
                blockHome = true, blockShorts = true,
                isUnlocked = unlocked(NavigationType.SHORTS)
            )
        )
    }

    @Test
    fun `shorts is allowed in a phase 1 style build`() {
        // Guards the Phase 1 configuration, so the flag keeps meaning what it says.
        assertFalse(
            BlockPolicy.isBlocked(
                NavigationType.SHORTS,
                blockHome = true, blockShorts = false, isUnlocked = allLocked
            )
        )
    }

    // ---- The two unlocks are independent ---------------------------------
    // This is the whole point of Phase 2 having two flags instead of one.

    @Test
    fun `unlocking home does not unlock shorts`() {
        assertTrue(
            BlockPolicy.isBlocked(
                NavigationType.SHORTS,
                blockHome = true, blockShorts = true,
                isUnlocked = unlocked(NavigationType.HOME)
            )
        )
    }

    @Test
    fun `unlocking shorts does not unlock home`() {
        assertTrue(
            BlockPolicy.isBlocked(
                NavigationType.HOME,
                blockHome = true, blockShorts = true,
                isUnlocked = unlocked(NavigationType.SHORTS)
            )
        )
    }

    // ---- Normal pages are never gated ------------------------------------

    @Test
    fun `normal is never blocked even with everything locked`() {
        assertFalse(
            BlockPolicy.isBlocked(
                NavigationType.NORMAL,
                blockHome = true, blockShorts = true, isUnlocked = allLocked
            )
        )
    }

    // ---- What this build actually ships ----------------------------------

    @Test
    fun `this build gates both home and shorts`() {
        // If someone flips a flag off by accident, this is where it gets caught.
        assertTrue("Phase 2 must gate Home", Features.BLOCK_HOME)
        assertTrue("Phase 2 must gate Shorts", Features.BLOCK_SHORTS)
    }

    @Test
    fun `defaults block both sections from a cold session`() {
        SessionState.lockAll()
        assertTrue(BlockPolicy.isBlocked(NavigationType.HOME))
        assertTrue(BlockPolicy.isBlocked(NavigationType.SHORTS))
        assertFalse(BlockPolicy.isBlocked(NavigationType.NORMAL))
    }
}
