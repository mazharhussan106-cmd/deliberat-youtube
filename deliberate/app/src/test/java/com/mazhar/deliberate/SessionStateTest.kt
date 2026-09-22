package com.mazhar.deliberate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SessionState is a process-scoped singleton, so every test resets it first — otherwise the
 * tests leak into each other exactly the way the app is supposed to leak across an Activity
 * recreation.
 */
class SessionStateTest {

    @Before
    fun reset() {
        SessionState.lockAll()
    }

    @Test
    fun `starts fully locked`() {
        assertFalse(SessionState.isUnlocked(NavigationType.HOME))
        assertFalse(SessionState.isUnlocked(NavigationType.SHORTS))
    }

    @Test
    fun `normal never needs unlocking`() {
        assertTrue(SessionState.isUnlocked(NavigationType.NORMAL))
    }

    @Test
    fun `unlocking home leaves shorts locked`() {
        SessionState.unlock(NavigationType.HOME)
        assertTrue(SessionState.isUnlocked(NavigationType.HOME))
        assertFalse(SessionState.isUnlocked(NavigationType.SHORTS))
    }

    @Test
    fun `unlocking shorts leaves home locked`() {
        SessionState.unlock(NavigationType.SHORTS)
        assertTrue(SessionState.isUnlocked(NavigationType.SHORTS))
        assertFalse(SessionState.isUnlocked(NavigationType.HOME))
    }

    @Test
    fun `unlocking normal changes nothing`() {
        SessionState.unlock(NavigationType.NORMAL)
        assertFalse(SessionState.isUnlocked(NavigationType.HOME))
        assertFalse(SessionState.isUnlocked(NavigationType.SHORTS))
    }

    @Test
    fun `lockAll clears both`() {
        SessionState.unlock(NavigationType.HOME)
        SessionState.unlock(NavigationType.SHORTS)
        SessionState.lockAll()
        assertFalse(SessionState.isUnlocked(NavigationType.HOME))
        assertFalse(SessionState.isUnlocked(NavigationType.SHORTS))
    }
}
