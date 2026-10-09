package com.smartplug.app.util

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Light gate in front of the hidden Diagnostics screen. This is a speed bump, NOT real security:
 * anyone with the APK can read the constants below or patch the check. Only a salted PBKDF2 hash is
 * kept in the code, never the password text, so it does not leak through strings/logs.
 */
object DiagnosticsGate {
    private const val SALT_HEX = "db42ad0357c3eeb80b96c58e25f25558"
    private const val HASH_HEX = "b3c52f670a3f487a74ca9f9888abe44a6f41227642fbf5433c2e1c2e9700df66"
    private const val ITERATIONS = 120_000

    const val MAX_ATTEMPTS = 5
    const val LOCK_MS = 30_000L
    const val TAP_COUNT = 5
    const val TAP_WINDOW_MS = 3_000L

    fun verify(password: String): Boolean = verify(password, SALT_HEX, HASH_HEX, ITERATIONS)

    internal fun verify(password: String, saltHex: String, expectedHex: String, iterations: Int): Boolean {
        val spec = PBEKeySpec(password.toCharArray(), hex(saltHex), iterations, expectedHex.length * 4)
        val actual = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return MessageDigest.isEqual(actual, hex(expectedHex))
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Counts wrong passwords; five in a row lock the dialog for 30 seconds. Memory only. */
class AttemptLimiter(private val nowMs: () -> Long = System::currentTimeMillis) {
    private var failures = 0
    private var lockedUntilMs = 0L

    fun lockedForMs(): Long = (lockedUntilMs - nowMs()).coerceAtLeast(0L)

    /** Returns false when the attempt is refused because the limiter is locked. */
    fun canTry(): Boolean = lockedForMs() == 0L

    fun recordFailure() {
        if (!canTry()) return
        failures++
        if (failures >= DiagnosticsGate.MAX_ATTEMPTS) {
            failures = 0
            lockedUntilMs = nowMs() + DiagnosticsGate.LOCK_MS
        }
    }

    fun recordSuccess() {
        failures = 0
        lockedUntilMs = 0L
    }
}

/** Opens the gate after [DiagnosticsGate.TAP_COUNT] taps within [DiagnosticsGate.TAP_WINDOW_MS]. */
class TapSequence(private val nowMs: () -> Long = System::currentTimeMillis) {
    private val taps = ArrayDeque<Long>()

    /** Returns true when this tap completes the sequence (and resets it). */
    fun tap(): Boolean {
        val now = nowMs()
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first() > DiagnosticsGate.TAP_WINDOW_MS) taps.removeFirst()
        if (taps.size >= DiagnosticsGate.TAP_COUNT) {
            taps.clear()
            return true
        }
        return false
    }
}
