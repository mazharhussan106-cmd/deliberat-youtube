package com.mazhar.deliberate

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** What came back from a PIN attempt. */
sealed interface PinResult {
    /** Correct. The caller unlocks. */
    object Correct : PinResult

    /** Wrong, and there is still room to try again. */
    data class Incorrect(val attemptsLeft: Int) : PinResult

    /** Too many wrong tries. Nothing was even checked this time. */
    data class LockedOut(val remainingMs: Long) : PinResult
}

/**
 * Stores the PIN as a salted PBKDF2 hash and enforces the lockout.
 *
 * Phase 3 replaced a source constant with this. The derivation and the lockout arithmetic
 * live in [PinHasher] and [CooldownPolicy] so they can be tested on a plain JVM; this class
 * is the thin Android-flavoured part that owns storage.
 *
 * WHAT THIS DOES NOT STOP: clearing the app's data wipes the stored hash, which sends the app
 * back to first-run setup where anyone can choose a new PIN. That is a full reset and a full
 * bypass, and no amount of hashing changes it — a sideloaded app cannot defend against the
 * person holding the unlocked phone. The point of this file is to make reaching the Home feed
 * deliberate, not impossible.
 */
class PinManager(context: Context) {

    private val prefs: SharedPreferences = openPrefs(context.applicationContext)

    /** False on first run, and after the app's data is cleared. */
    fun isPinSet(): Boolean =
        prefs.getString(KEY_SALT, null) != null && prefs.getString(KEY_HASH, null) != null

    /** Sets or replaces the PIN and clears any accumulated failures. */
    fun setPin(pin: String) {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.derive(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash))
            .remove(KEY_FAILURES)
            .remove(KEY_LOCKED_UNTIL)
            .apply()
    }

    fun verify(entered: String, now: Long = System.currentTimeMillis()): PinResult {
        val remaining = cooldownRemainingMs(now)
        if (remaining > 0L) return PinResult.LockedOut(remaining)

        val salt = decode(prefs.getString(KEY_SALT, null))
        val expected = decode(prefs.getString(KEY_HASH, null))
        if (salt == null || expected == null) {
            // No PIN on file. Treat as wrong rather than as a free pass; the caller's
            // first-run check is what should have caught this.
            return recordFailure(now)
        }

        if (PinHasher.matches(entered, salt, expected)) {
            prefs.edit().remove(KEY_FAILURES).remove(KEY_LOCKED_UNTIL).apply()
            return PinResult.Correct
        }

        return recordFailure(now)
    }

    /** Remaining lockout in milliseconds, 0 when not locked. Clears a stale lock as a side effect. */
    fun cooldownRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        if (lockedUntil <= 0L) return 0L

        val remaining = CooldownPolicy.remainingMs(lockedUntil, now)
        if (remaining == 0L) prefs.edit().remove(KEY_LOCKED_UNTIL).apply()
        return remaining
    }

    private fun recordFailure(now: Long): PinResult {
        val failure = CooldownPolicy.onFailure(prefs.getInt(KEY_FAILURES, 0), now)
        prefs.edit()
            .putInt(KEY_FAILURES, failure.failures)
            .putLong(KEY_LOCKED_UNTIL, failure.lockedUntil)
            .apply()

        return if (failure.lockedOut) {
            PinResult.LockedOut(CooldownPolicy.COOLDOWN_MS)
        } else {
            PinResult.Incorrect(failure.attemptsLeft)
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String?): ByteArray? = try {
        value?.let { Base64.decode(it, Base64.NO_WRAP) }
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val PREFS_ENCRYPTED = "pin_secure"
        const val PREFS_PLAIN = "pin_fallback"

        const val KEY_SALT = "salt"
        const val KEY_HASH = "hash"
        const val KEY_FAILURES = "failures"
        const val KEY_LOCKED_UNTIL = "locked_until"

        /**
         * EncryptedSharedPreferences where the device allows it, plain app-private prefs
         * where it does not.
         *
         * Some devices ship a keystore that fails to produce a master key, and on those the
         * encrypted path throws on every launch. Falling back keeps the app usable, and what
         * is stored either way is a PBKDF2 hash rather than the PIN, in app-private storage
         * with backups disabled. Encryption here is a second layer, not the only one.
         */
        fun openPrefs(context: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
        }
    }
}
