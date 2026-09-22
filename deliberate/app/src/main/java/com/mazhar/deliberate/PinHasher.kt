package com.mazhar.deliberate

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 over the PIN. Pure JDK crypto — no `android.*` imports — so it runs in
 * plain JVM unit tests.
 *
 * A word on what this actually buys, because it is easy to oversell:
 *
 * A 4-digit PIN has 10,000 candidates. Anyone who can read this app's private storage can
 * try all of them, and [ITERATIONS] only makes that take minutes instead of milliseconds.
 * What the derivation genuinely prevents is the PIN sitting in storage in readable form, and
 * any rainbow-table shortcut. The real protection is that this is your own phone, with
 * `allowBackup="false"` and app-private storage.
 *
 * If that bothers you, use a longer PIN. Every extra digit multiplies the search by ten, and
 * nothing in this file has to change.
 */
object PinHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256

    /** Tuned to be unnoticeable once on a PIN prompt, not to win a benchmark. */
    const val ITERATIONS = 120_000

    const val SALT_BYTES = 16

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun derive(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time comparison, so a wrong PIN does not leak how wrong it was. */
    fun matches(
        pin: String,
        salt: ByteArray,
        expected: ByteArray,
        iterations: Int = ITERATIONS
    ): Boolean = MessageDigest.isEqual(expected, derive(pin, salt, iterations))
}
