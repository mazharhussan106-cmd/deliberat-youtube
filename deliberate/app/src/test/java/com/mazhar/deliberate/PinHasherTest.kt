package com.mazhar.deliberate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests — `javax.crypto` is JDK, not Android, so no emulator is involved.
 *
 * Most cases run at a low iteration count to keep the suite fast; one runs at the real
 * [PinHasher.ITERATIONS] so a bad production constant cannot slip through.
 */
class PinHasherTest {

    private val fast = 1_000
    private val salt = ByteArray(16) { it.toByte() }
    private val otherSalt = ByteArray(16) { (it + 7).toByte() }

    @Test
    fun `same pin and salt derive the same hash`() {
        assertArrayEquals(
            PinHasher.derive("1947", salt, fast),
            PinHasher.derive("1947", salt, fast)
        )
    }

    @Test
    fun `the same pin under different salts derives different hashes`() {
        val a = PinHasher.derive("1947", salt, fast)
        val b = PinHasher.derive("1947", otherSalt, fast)
        assertFalse("salt is not being mixed in", a.contentEquals(b))
    }

    @Test
    fun `different pins derive different hashes`() {
        val a = PinHasher.derive("1947", salt, fast)
        val b = PinHasher.derive("1948", salt, fast)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `iteration count changes the hash`() {
        val a = PinHasher.derive("1947", salt, fast)
        val b = PinHasher.derive("1947", salt, fast * 2)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `matches accepts the right pin`() {
        val expected = PinHasher.derive("1947", salt, fast)
        assertTrue(PinHasher.matches("1947", salt, expected, fast))
    }

    @Test
    fun `matches rejects the wrong pin`() {
        val expected = PinHasher.derive("1947", salt, fast)
        assertFalse(PinHasher.matches("1948", salt, expected, fast))
    }

    @Test
    fun `matches rejects a near miss`() {
        val expected = PinHasher.derive("1947", salt, fast)
        assertFalse(PinHasher.matches("19470", salt, expected, fast))
        assertFalse(PinHasher.matches("194", salt, expected, fast))
        assertFalse(PinHasher.matches("", salt, expected, fast))
    }

    @Test
    fun `hash is 256 bits`() {
        assertEquals(32, PinHasher.derive("1947", salt, fast).size)
    }

    @Test
    fun `salts are the right size and not constant`() {
        val a = PinHasher.newSalt()
        val b = PinHasher.newSalt()
        assertEquals(PinHasher.SALT_BYTES, a.size)
        assertEquals(PinHasher.SALT_BYTES, b.size)
        assertFalse("SecureRandom returned the same salt twice", a.contentEquals(b))
    }

    @Test
    fun `longer pins are supported`() {
        // The defence against a 10,000-candidate search is more digits, so this has to work.
        val expected = PinHasher.derive("8675309421", salt, fast)
        assertTrue(PinHasher.matches("8675309421", salt, expected, fast))
        assertFalse(PinHasher.matches("8675309422", salt, expected, fast))
    }

    @Test
    fun `production settings round-trip`() {
        assertNotEquals(0, PinHasher.ITERATIONS)
        val realSalt = PinHasher.newSalt()
        val expected = PinHasher.derive("1947", realSalt)
        assertTrue(PinHasher.matches("1947", realSalt, expected))
        assertFalse(PinHasher.matches("0000", realSalt, expected))
    }
}
